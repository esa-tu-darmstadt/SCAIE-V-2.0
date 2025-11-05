package scaiev.scal.strategy.standard;

import java.util.Optional;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.InterfaceRequestBuilder;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.util.Verilog;

/**
 * Computes default values for certain adjacent nodes (*Mem_addr, *Mem_size)
 */
public class DefaultMemAdjStrategy extends MultiNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  StrategyBuilders strategyBuilders;
  Verilog language;
  BNode bNodes;
  Core core;

  protected MultiNodeStrategy pipelinedMemSizeStrategy;
  protected MultiNodeStrategy regularPipelinedMemAddrStrategy;
  protected MultiNodeStrategy spawnPipelinedMemAddrStrategy;

  /**
   * @param strategyBuilders The StrategyBuilders object to build sub-strategies with
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core node description
   */
  public DefaultMemAdjStrategy(StrategyBuilders strategyBuilders, Verilog language, BNode bNodes, Core core) {
    this.strategyBuilders = strategyBuilders;
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;

    PipelineFront minDecodeFront = core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.RdInstr).GetEarliest());
    PipelineFront minDecodePipetoFront =
        new PipelineFront(minDecodeFront.asList().stream().flatMap(minDecodeStage -> minDecodeStage.getNext().stream()));

    this.pipelinedMemSizeStrategy = strategyBuilders.buildNodeRegPipelineStrategy(
        language, bNodes, minDecodePipetoFront, false, false, false,
        key
        -> key.getPurpose().matches(Purpose.PIPEDIN) &&
               (key.getNode().equals(bNodes.RdMem_size) || key.getNode().equals(bNodes.WrMem_size)) && key.getISAX().isEmpty() &&
               key.getStage().getKind() == StageKind.Core,
        key -> false, new DefaultMemSizeStrategy(),
        false);

    this.regularPipelinedMemAddrStrategy = makePipelinedMemAddrStrategy(bNodes.RdMem_defaultAddr, bNodes.WrMem_defaultAddr).orElse(null);
    this.spawnPipelinedMemAddrStrategy =
        makePipelinedMemAddrStrategy(bNodes.RdMem_spawn_defaultAddr, bNodes.WrMem_spawn_defaultAddr).orElse(null);
  }

  protected Optional<MultiNodeStrategy> makePipelinedMemAddrStrategy(SCAIEVNode readDefaultAddrNode, SCAIEVNode writeDefaultAddrNode) {
    PipelineFront minRdInstrFront = core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.RdInstr).GetEarliest());
    PipelineFront minRdRS1Front = core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.RdRS1).GetEarliest());
    PipelineFront minAddrPipetoFront =
        new PipelineFront(minRdRS1Front.asList().stream().flatMap(minRdRS1Stage -> minRdRS1Stage.getNext().stream()));
    PipelineFront latestDefaultAddrFront = minAddrPipetoFront;
    // One would expect the core knows the instruction word before being able to provide RdRS1.
    assert (minAddrPipetoFront.asList().stream().allMatch(stage -> minRdInstrFront.isBefore(stage, false)));

    boolean coreProvidesDefaultMemAddr = readDefaultAddrNode.mustToCore;
    if (writeDefaultAddrNode.mustToCore != readDefaultAddrNode.mustToCore) {
      logger.error("The core provides only one of {}, {}.", readDefaultAddrNode.name, writeDefaultAddrNode.name);
      coreProvidesDefaultMemAddr = false;
    }
    if (core.GetNodes().containsKey(readDefaultAddrNode)) {
      var defaultAddrCoreNode = core.GetNodes().get(readDefaultAddrNode);
      if (!Optional.ofNullable(core.GetNodes().get(writeDefaultAddrNode))
               .map(wrDefaultAddrCoreNode
                    -> wrDefaultAddrCoreNode.GetEarliest().equals(defaultAddrCoreNode.GetEarliest()) &&
                           wrDefaultAddrCoreNode.GetLatest().equals(defaultAddrCoreNode.GetLatest()))
               .orElse(false)) {
        logger.error("The core does not provide {} at the same stage range as {}.", writeDefaultAddrNode, readDefaultAddrNode);
        coreProvidesDefaultMemAddr = false;
      } else {
        coreProvidesDefaultMemAddr = true;
        PipelineFront minDefaultAddrFront = core.TranslateStageScheduleNumber(defaultAddrCoreNode.GetEarliest());
        minAddrPipetoFront =
            new PipelineFront(minDefaultAddrFront.asList().stream().flatMap(minAddrStage -> minAddrStage.getNext().stream()));
        latestDefaultAddrFront = core.TranslateStageScheduleNumber(defaultAddrCoreNode.GetLatest());
      }
    }
    if (!minAddrPipetoFront.asList().isEmpty()) {
      return Optional.of(strategyBuilders.buildNodeRegPipelineStrategy(
          language, bNodes, minAddrPipetoFront, false, false, false,
          key -> key.getPurpose().matches(Purpose.PIPEDIN) &&
                 (key.getNode().equals(readDefaultAddrNode) || key.getNode().equals(writeDefaultAddrNode)) && key.getISAX().isEmpty(),
          key -> false,
          coreProvidesDefaultMemAddr ? new RequestMemAddrFromCoreStrategy(readDefaultAddrNode, writeDefaultAddrNode, latestDefaultAddrFront)
                                     : new DefaultMemAddrStrategy(readDefaultAddrNode, writeDefaultAddrNode),
          false));
    }
    return Optional.empty();
  }

  protected class DefaultMemSizeStrategy extends SingleNodeStrategy {
    @Override
    public Optional<NodeLogicBuilder> implement(NodeInstanceDesc.Key nodeKey) {
      if (!(nodeKey.getNode().equals(bNodes.RdMem_size) || nodeKey.getNode().equals(bNodes.WrMem_size)) || !nodeKey.getISAX().isEmpty() ||
          !nodeKey.getPurpose().matches(Purpose.WIREDIN))
        return Optional.empty();
      var requestedFor = new RequestedForSet(nodeKey.getISAX());
      return Optional.of(NodeLogicBuilder.fromFunction("DefaultMemSizeStrategy_" + nodeKey.toString(), registry -> {
        var ret = new NodeLogicBlock();
        String rdInstr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdInstr, nodeKey.getStage(), ""), requestedFor);
        String wireName = language.CreateBasicNodeName(nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX(), false) + "_default";
        ret.declarations += String.format("wire [3-1:0] %s;\n", wireName);
        ret.logic += String.format("assign %s = %s[14:12];\n", wireName, rdInstr);
        ret.outputs.add(
            new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.WIREDIN, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()),
                                 wireName, ExpressionType.WireName, requestedFor));
        return ret;
      }));
    }
  }

  protected class DefaultMemAddrStrategy extends SingleNodeStrategy {
    SCAIEVNode readDefaultAddrNode;
    SCAIEVNode writeDefaultAddrNode;
    public DefaultMemAddrStrategy(SCAIEVNode readDefaultAddrNode, SCAIEVNode writeDefaultAddrNode) {
      this.readDefaultAddrNode = readDefaultAddrNode;
      this.writeDefaultAddrNode = writeDefaultAddrNode;
    }
    @Override
    public Optional<NodeLogicBuilder> implement(NodeInstanceDesc.Key nodeKey) {
      if (!(nodeKey.getNode().equals(readDefaultAddrNode) || nodeKey.getNode().equals(writeDefaultAddrNode)) ||
          !nodeKey.getISAX().isEmpty() || !nodeKey.getPurpose().matches(Purpose.WIREDIN))
        return Optional.empty();
      var requestedFor = new RequestedForSet(nodeKey.getISAX());
      return Optional.of(NodeLogicBuilder.fromFunction("DefaultMemAddrStrategy_" + nodeKey.toString(), registry -> {
        var ret = new NodeLogicBlock();
        String rdInstr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdInstr, nodeKey.getStage(), ""), requestedFor);
        String rdRS1 = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdRS1, nodeKey.getStage(), ""), requestedFor);
        String wireName = language.CreateLocalNodeName(nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
        String offsExpression = String.format("{{%d{%s[31]}}, %s[31:25], %s%s}", readDefaultAddrNode.size - 12, rdInstr, rdInstr, rdInstr,
                                              nodeKey.getNode().equals(readDefaultAddrNode) ? "[24:20]" : "[11:7]");
        ret.declarations += String.format("wire [%d-1:0] %s;\n", readDefaultAddrNode.size, wireName);
        ret.logic += String.format("assign %s = %s + %s;\n", wireName, rdRS1, offsExpression);
        ret.outputs.add(
            new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.WIREDIN, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()),
                                 wireName, ExpressionType.WireName, requestedFor));
        return ret;
      }));
    }
  }

  protected class RequestMemAddrFromCoreStrategy extends SingleNodeStrategy {
    SCAIEVNode readDefaultAddrNode;
    SCAIEVNode writeDefaultAddrNode;
    PipelineFront latestFront;
    public RequestMemAddrFromCoreStrategy(SCAIEVNode readDefaultAddrNode, SCAIEVNode writeDefaultAddrNode, PipelineFront latestFront) {
      this.readDefaultAddrNode = readDefaultAddrNode;
      this.writeDefaultAddrNode = writeDefaultAddrNode;
      this.latestFront = latestFront;
    }
    @Override
    public Optional<NodeLogicBuilder> implement(NodeInstanceDesc.Key nodeKey) {
      if (!(nodeKey.getNode().equals(readDefaultAddrNode) || nodeKey.getNode().equals(writeDefaultAddrNode)) ||
          !nodeKey.getISAX().isEmpty() || !latestFront.isAroundOrAfter(nodeKey.getStage(), false))
        return Optional.empty();
      return Optional.of(new InterfaceRequestBuilder(Purpose.MARKER_FROMCORE_PIN, nodeKey));
    }
  }

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    this.pipelinedMemSizeStrategy.implement(out, nodeKeys, isLast);
    if (this.regularPipelinedMemAddrStrategy != null)
      this.regularPipelinedMemAddrStrategy.implement(out, nodeKeys, isLast);
    if (this.spawnPipelinedMemAddrStrategy != null)
      this.spawnPipelinedMemAddrStrategy.implement(out, nodeKeys, isLast);

    // Assign addr from default addr.
    var nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      NodeInstanceDesc.Key nodeKey = nodeKeyIter.next();
      if (nodeKey.getPurpose().matches(Purpose.WIREDIN) &&
          (nodeKey.getNode().equals(bNodes.RdMem_addr) || nodeKey.getNode().equals(bNodes.WrMem_addr)) && nodeKey.getISAX().isEmpty()) {
        var requestedFor = new RequestedForSet(nodeKey.getISAX());
        out.accept(NodeLogicBuilder.fromFunction("DefaultMemAdjStrategy_" + nodeKey.toString(), registry -> {
          var ret = new NodeLogicBlock();
          ret.outputs.add(new NodeInstanceDesc(
              new NodeInstanceDesc.Key(Purpose.WIREDIN, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()),
              registry.lookupExpressionRequired(new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), ""), requestedFor),
              ExpressionType.AnyExpression, requestedFor));
          return ret;
        }));
        nodeKeyIter.remove();
      }
    }
  }
}
