package scaiev.scal.strategy.standard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.coreconstr.CoreNode.CoreNodeTag;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.MultiportStallAttributes;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.scal.InterfaceRequestBuilder;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.SCALUtil;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.scal.strategy.pipeline.NodeRegPipelineStrategy;
import scaiev.util.ListRemoveView;
import scaiev.util.Verilog;

/**
 * Strategy that replaces the WrFlush generation from {@link StallFlushDeqStrategy}
 *  in case a CoreMultiport stage only supports shared flushing.
 */
public class MultiportFlushSpecificStrategy extends MultiNodeStrategy {
  protected static final Logger logger = LogManager.getLogger();

  StrategyBuilders strategyBuilders;
  Verilog language;
  BNode bNodes;
  Core core;
  /**
   * @param strategyBuilders The StrategyBuilders object to build sub-strategies with
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   */
  public MultiportFlushSpecificStrategy(StrategyBuilders strategyBuilders, Verilog language, BNode bNodes, Core core) {
    this.strategyBuilders = strategyBuilders;
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
  }

  private static final SCAIEVNode FlushingTokenNode = new SCAIEVNode("FlushingToken", 1, false);
  /** (each only works across ports in the current stage)*/
  private Map<PipelineStage, NodeRegPipelineStrategy> tokenPipeliners = new HashMap<>();

  private boolean implementSingle(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
    //Note: this strategy implements WrFlush on the CoreMultiport stage, FlushingTokenNode on the port stages.
    PipelineStage stage = nodeKey.getStage();
    PipelineStage baseStage = stage.getMultiportBase();
    if (baseStage.getKind() != StageKind.CoreMultiport)
      return false;
    var multiportStallAttr = nodeKey.getStage().getMultiportBase().getTagAttr(StageTag.MultiportStall, MultiportStallAttributes.class);
    assert(multiportStallAttr != null);
    if (multiportStallAttr.perPortFlush() || !multiportStallAttr.hasSharedFlush())
      return false; //No need to do anything
    if (nodeKey.getNode().equals(FlushingTokenNode) && nodeKey.getPurpose().matches(Purpose.PIPEDIN)) {
      // Pipeline a flushing token
      if (nodeKey.getStage().getKind() == StageKind.CoreMultiport)
        return false; // Can't pipeline the token to a multiport base stage.
      var toPipelineList = new ListRemoveView<>(List.of(nodeKey));
      tokenPipeliners.computeIfAbsent(baseStage, baseStage_ ->
        strategyBuilders.buildNodeRegPipelineStrategy(language, bNodes, new PipelineFront(baseStage), true, true, false,
            subkey->true, subkey->false, noneStrategy, false)
      ).implement(out, toPipelineList, false);
      return toPipelineList.isEmpty();
    }

    //Build WrFlush
    if (nodeKey.getNode().equals(bNodes.WrFlush) && nodeKey.getStage().getKind() == StageKind.CoreMultiport &&
        nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.REGULAR) && nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0 &&
        (core.getNodes().containsKey(nodeKey.getNode()) &&
          core.translateStageScheduleNumber(core.getNodes().get(nodeKey.getNode()).getEarliest())
              .isAroundOrBefore(nodeKey.getStage(), false))) {

      InterfaceRequestBuilder interfBuilder = new InterfaceRequestBuilder(NodeInstanceDesc.Purpose.MARKER_TOCORE_PIN, nodeKey);

      // Based on StallFlushDeqStrategy
      out.accept(
          NodeLogicBuilder.fromFunction("MultiportFlushSpecificStrategy (" + nodeKey.toString() + ")", (NodeRegistryRO registry, Integer aux) -> {
            NodeLogicBlock ret = new NodeLogicBlock();

            String nodeWire = language.CreateLocalNodeName(nodeKey.getNode(), nodeKey.getStage(), "");
            ret.declarations += "wire " + nodeWire + ";\n";

            // Find all sub-nodes (i.e. from some ISAX, or some internal node with aux != 0)
            var valueBuilder = new Object() {
              String newValue = "";
              void consumeSubNode(NodeInstanceDesc subNode) {
                newValue += (newValue.isEmpty() ? "" : " || ") + subNode.getExpressionWithParens();
                interfBuilder.requestedFor.addAll(subNode.getRequestedFor(), true);
              }
            };

            for (NodeInstanceDesc subNode :
                 registry.lookupAll(new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), ""), false)) {
              // The sub-node should have some unique identifier.
              assert (subNode.getKey().getISAX().length() > 0 || subNode.getKey().getAux() != 0);
              valueBuilder.consumeSubNode(subNode);
            }
            //Multi-port stage handling
            if (nodeKey.getStage().getKind() == StageKind.CoreMultiport) {
              // Retrieve sub-conditions from each port.
              for (PipelineStage portStage : nodeKey.getStage().getChildren()) if (portStage.getKind() == StageKind.Core) {
                var subNode = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(nodeKey.getNode(), portStage, ""));
                if (subNode.isPresent()) {
                  valueBuilder.consumeSubNode(subNode.get());
                  // TODO: Create a latching FlushingTokenNode output for the portStage,
                  // that accepts from
                }
              }
            }
            // TODO: Stall all ports with a set FlushingTokenNode;
            //  only once a prefix of the stage's valid ports have the token set, actually flush the instruction.
            // TODO: Stall the previous stage (/ all its ports) while flush is in progress
            //  (should be sufficient to check for the registered FlushingTokenNodes)

            if (valueBuilder.newValue.isEmpty())
              valueBuilder.newValue = "1'b0";
            else {
              // Explicitly request instantiation of the SCAL->core output pin
              ret.addOther(interfBuilder.apply(registry, aux));
            }

            //TODO: Only set WrFlush once we're actually flushing
            //TODO: Make sure we repeat WrPC after the delay... (?)
            ret.logic += "assign " + nodeWire + " = " + valueBuilder.newValue + ";";
            var output =
                new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR), nodeWire, ExpressionType.WireName);
            output.addRequestedFor(interfBuilder.requestedFor, false);
            ret.outputs.add(output);

            return ret;
          }));
      return true;
    }
    return false;
  }


  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    var nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      NodeInstanceDesc.Key nodeKey = nodeKeyIter.next();
      if (this.implementSingle(out, nodeKey))
        nodeKeyIter.remove();
    }
  }
}
