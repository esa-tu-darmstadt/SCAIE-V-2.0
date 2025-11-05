package scaiev.scal.strategy.standard;

import java.util.HashSet;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.InterfaceRequestBuilder;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.util.Verilog;

/**
 * Generates either default implementations or core interface pins for RdInStageValid, and aggregates WIREDIN_FALLBACK or REGULAR
 * subconditions aux nodes by '&amp;&amp;'.
 */
public class RdInStageValidStrategy extends MultiNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  Verilog language;
  BNode bNodes;
  Core core;
  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   */
  public RdInStageValidStrategy(Verilog language, BNode bNodes, Core core) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
  }

  private HashSet<NodeInstanceDesc.Key> implementedKeys = new HashSet<>();

  private String aggregateSubconditions(NodeRegistryRO registry, Purpose purpose, PipelineStage stage, RequestedForSet requestedFor) {
    String condition = "";
    for (NodeInstanceDesc matchInst : registry.lookupAll(new NodeInstanceDesc.Key(purpose, bNodes.RdInStageValid, stage, ""), false)) {
      condition += (condition.isEmpty() ? "" : " && ") + matchInst.getExpressionWithParens();
      requestedFor.addAll(matchInst.getRequestedFor(), true);
    }
    return condition;
  }

  private boolean implementSingle_stallmode(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
    SCAIEVNode node = nodeKey.getNode();
    PipelineStage stage = nodeKey.getStage();
    // Early node check
    if (!node.equals(bNodes.RdInStageValid))
      return false;

    var outFallbackNodeKey = new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, node, stage, "");
    var outRegularNodeKey = new NodeInstanceDesc.Key(Purpose.REGULAR, node, stage, "");

    boolean isCoreStage = nodeKey.getStage().getKind() == StageKind.Core || nodeKey.getStage().getKind() == StageKind.CoreInternal;
    boolean coreSupportsNodeInStage =
        isCoreStage && Optional.ofNullable(core.GetNodes().get(node)).map(coreNode -> core.StageIsInRange(coreNode, stage)).orElse(false);

    // Check if the core already supports this node.
    if (coreSupportsNodeInStage && nodeKey.getPurpose().matches(Purpose.WIREDIN)) {
      if (!implementedKeys.add(new NodeInstanceDesc.Key(Purpose.MARKER_FROMCORE_PIN, node, stage, "")))
        return false; // Create only one interface builder per key.
      // Request the relevant core interfaces.
      out.accept(new InterfaceRequestBuilder(Purpose.MARKER_FROMCORE_PIN, new NodeInstanceDesc.Key(node, stage, "")));
    }

    boolean handled = false;
    if (!coreSupportsNodeInStage && nodeKey.getPurpose().matches(Purpose.WIREDIN_FALLBACK)) {
      if (implementedKeys.add(outFallbackNodeKey)) {
        if (isCoreStage) {
          logger.info("RdInStageValidStrategy: Using !RdStall as a baseline 'instruction valid' condition for stage " +
                      nodeKey.getStage().getName() +
                      (", since the core does not provide its own. This might lead to combinational loops depending on the ISAX/core "
                       + "combination."));
        }
        var requestedFor = new RequestedForSet();
        // RdInStageValid(WIREDIN_FALLBACK): Set if the core does not stall the stage on its own.
        out.accept(NodeLogicBuilder.fromFunction("RdInStageValidStrategy_" + outFallbackNodeKey.toString(), registry -> {
          var ret = new NodeLogicBlock();
          String outWire = outFallbackNodeKey.toString(false) + "_default_s";
          ret.declarations += String.format("wire %s;\n", outWire);
          ret.outputs.add(new NodeInstanceDesc(outFallbackNodeKey, outWire, ExpressionType.WireName, requestedFor));

          String stallFromCore =
              registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdStall, stage, ""), requestedFor).getExpressionWithParens();
          String aggregatedSubconditions = aggregateSubconditions(registry, Purpose.WIREDIN_FALLBACK, stage, requestedFor);
          if (!aggregatedSubconditions.isEmpty())
            aggregatedSubconditions = " && " + aggregatedSubconditions;

          // Assumption: The core does not set RdStall even if it (implicitly) stalls waiting for WrRD from the ISAX.
          ret.logic += String.format("assign %s = !(%s)%s;\n", outWire, stallFromCore, aggregatedSubconditions);
          return ret;
        }));
      }
      handled = true;
    }
    if (nodeKey.getPurpose().matches(Purpose.REGULAR)) {
      if (implementedKeys.add(outRegularNodeKey)) {
        var requestedFor = new RequestedForSet();
        // RdInStageValid(REGULAR): Override WIREDIN to false if we are hiding the instruction (from external conditions)
        out.accept(NodeLogicBuilder.fromFunction("RdInStageValidStrategy_" + outRegularNodeKey.toString(), registry -> {
          var ret = new NodeLogicBlock();
          String outWire = outRegularNodeKey.toString(false) + "_s";
          ret.declarations += String.format("wire %s;\n", outWire);
          ret.outputs.add(new NodeInstanceDesc(outRegularNodeKey, outWire, ExpressionType.WireName, requestedFor));

          var wiredinInst = registry.lookupRequired(
              new NodeInstanceDesc.Key(Purpose.match_WIREDIN_OR_PIPEDIN, bNodes.RdInStageValid, stage, ""), requestedFor);
          requestedFor.addAll(wiredinInst.getRequestedFor(), true);

          String aggregatedSubconditions = aggregateSubconditions(registry, Purpose.REGULAR, stage, requestedFor);
          if (!aggregatedSubconditions.isEmpty())
            aggregatedSubconditions = " && " + aggregatedSubconditions;

          ret.logic += String.format("assign %s = %s%s;\n", outWire, wiredinInst.getExpressionWithParens(), aggregatedSubconditions);
          return ret;
        }));
      }
      handled = true;
    }
    return handled;
  }

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    var nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      NodeInstanceDesc.Key nodeKey = nodeKeyIter.next();
      if (!nodeKey.getISAX().isEmpty() || nodeKey.getAux() != 0)
        continue;
      if (implementSingle_stallmode(out, nodeKey))
        nodeKeyIter.remove();
    }
  }
}
