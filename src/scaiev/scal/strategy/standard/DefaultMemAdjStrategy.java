package scaiev.scal.strategy.standard;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
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
  protected MultiNodeStrategy rdMemValidRespStrategy;
  protected MultiNodeStrategy wrMemValidRespStrategy;
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

    PipelineFront minDecodeFront = core.translateStageScheduleNumber(core.getNodes().get(bNodes.RdInstr).getEarliest());
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
    this.rdMemValidRespStrategy = makeMemValidRespStrategy(bNodes.RdMem).orElse(null);
    this.wrMemValidRespStrategy = makeMemValidRespStrategy(bNodes.WrMem).orElse(null);
  }

  protected Optional<MultiNodeStrategy> makePipelinedMemAddrStrategy(SCAIEVNode readDefaultAddrNode, SCAIEVNode writeDefaultAddrNode) {
    PipelineFront minRdInstrFront = core.translateStageScheduleNumber(core.getNodes().get(bNodes.RdInstr).getEarliest());
    PipelineFront minRdRS1Front = core.translateStageScheduleNumber(core.getNodes().get(bNodes.RdRS1).getEarliest());
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
    if (core.getNodes().containsKey(readDefaultAddrNode)) {
      var defaultAddrCoreNode = core.getNodes().get(readDefaultAddrNode);
      if (!Optional.ofNullable(core.getNodes().get(writeDefaultAddrNode))
               .map(wrDefaultAddrCoreNode
                    -> wrDefaultAddrCoreNode.getEarliest().equals(defaultAddrCoreNode.getEarliest()) &&
                           wrDefaultAddrCoreNode.getLatest().equals(defaultAddrCoreNode.getLatest()))
               .orElse(false)) {
        logger.error("The core does not provide {} at the same stage range as {}.", writeDefaultAddrNode, readDefaultAddrNode);
        coreProvidesDefaultMemAddr = false;
      } else {
        coreProvidesDefaultMemAddr = true;
        PipelineFront minDefaultAddrFront = core.translateStageScheduleNumber(defaultAddrCoreNode.getEarliest());
        minAddrPipetoFront =
            new PipelineFront(minDefaultAddrFront.asList().stream().flatMap(minAddrStage -> minAddrStage.getNext().stream()));
        latestDefaultAddrFront = core.translateStageScheduleNumber(defaultAddrCoreNode.getLatest());
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

  /** Computes the default memory size, assuming lw-/sw-like funct3 encoding */
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

  /** Computes the default memory address, assuming lw-/sw-like instruction encoding */
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

  /** Requests the node from the core, bounded by latestFront */
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

  /** Produces (RdMem|WrMem)_validResp from _validReq && !RdStall */
  protected class DefaultMemValidRespStrategy extends SingleNodeStrategy {
    SCAIEVNode validRespNode;
    Optional<SCAIEVNode> validReqNode_opt;
    PipelineFront earliestFront;
    PipelineFront latestFront;
    public DefaultMemValidRespStrategy(SCAIEVNode validRespNode, PipelineFront earliestFront, PipelineFront latestFront) {
      this.validRespNode = validRespNode;
      this.validReqNode_opt = bNodes.GetAdjSCAIEVNode(bNodes.GetNonAdjNode(validRespNode), AdjacentNode.validReq);
      this.earliestFront = earliestFront;
      this.latestFront = latestFront;
    }
    @Override
    public Optional<NodeLogicBuilder> implement(NodeInstanceDesc.Key nodeKey) {
      if (!nodeKey.getNode().equals(validRespNode) ||
          !nodeKey.getISAX().isEmpty() ||
          !nodeKey.getPurpose().matches(Purpose.WIREDIN_FALLBACK) ||
          !earliestFront.isAroundOrBefore(nodeKey.getStage(), false) ||
          !latestFront.isAroundOrAfter(nodeKey.getStage(), false))
        return Optional.empty();
      var requestedFor = new RequestedForSet(nodeKey.getISAX());
      return Optional.of(NodeLogicBuilder.fromFunction("DefaultMemValidRespStrategy_" + nodeKey.toString(), registry -> {
        var ret = new NodeLogicBlock();
        String validReq = "1'b1";
        if (validReqNode_opt.isPresent())
          validReq = registry.lookupRequired(new NodeInstanceDesc.Key(validReqNode_opt.get(), nodeKey.getStage(), ""), requestedFor).getExpressionWithParens();
        String rdStall = registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdStall, nodeKey.getStage(), ""), requestedFor).getExpressionWithParens();
        String wireName = language.CreateLocalNodeName(nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
        ret.declarations += String.format("logic %s;\n", wireName);
        ret.logic += String.format("assign %s = %s && !%s;\n", wireName, validReq, rdStall);
        ret.outputs.add(
            new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()),
                                 wireName, ExpressionType.WireName, requestedFor));
        return ret;
      }));
    }
  }

  /** Requests a node from the core, bounded by earliestFront and latestFront */
  protected class RequestNodeFromCoreStrategy extends SingleNodeStrategy {
    SCAIEVNode node;
    PipelineFront earliestFront;
    PipelineFront latestFront;
    public RequestNodeFromCoreStrategy(SCAIEVNode node, PipelineFront earliestFront, PipelineFront latestFront) {
      this.node = node;
      this.earliestFront = earliestFront;
      this.latestFront = latestFront;
    }
    Set<NodeInstanceDesc.Key> handledKeys = new HashSet<>();
    @Override
    public Optional<NodeLogicBuilder> implement(NodeInstanceDesc.Key nodeKey) {
      if (!nodeKey.getNode().equals(node) ||
          !nodeKey.getISAX().isEmpty() || nodeKey.getAux() != 0 ||
          !nodeKey.getPurpose().matches(Purpose.WIREDIN) ||
          !earliestFront.isAroundOrBefore(nodeKey.getStage(), false) ||
          !latestFront.isAroundOrAfter(nodeKey.getStage(), false))
        return Optional.empty();
      if (handledKeys.add(new NodeInstanceDesc.Key(node,  nodeKey.getStage(), "")))
        return Optional.of(new InterfaceRequestBuilder(Purpose.MARKER_FROMCORE_PIN, nodeKey));
      return Optional.empty();
    }
  }

  protected Optional<MultiNodeStrategy> makeMemValidRespStrategy(SCAIEVNode memNode) {
    SCAIEVNode validRespNode = bNodes.GetAdjSCAIEVNode(memNode, AdjacentNode.validResp).orElseThrow();

    boolean coreProvidesValidResp = !validRespNode.tags.contains(NodeTypeTag.defaultNotprovidedByCore) || core.getNodes().containsKey(validRespNode);
    var relevantCoreNode = (core.getNodes().containsKey(validRespNode) ? core.getNodes().get(validRespNode) : core.getNodes().get(memNode));
    if (relevantCoreNode == null)
      return Optional.empty();
    PipelineFront earliestFront = core.translateStageScheduleNumber(relevantCoreNode.getEarliest());
    PipelineFront latestFront = core.translateStageScheduleNumber(relevantCoreNode.getLatest());
    PipelineFront earliestFront_validResp = earliestFront;
    PipelineFront latestFront_validResp = latestFront;
    if (!core.getNodes().containsKey(validRespNode)) {
      //Advance earliestFront_validResp by latency
      for (int iLatency = 0; iLatency < relevantCoreNode.getLatency(); ++iLatency) {
        PipelineFront earliestFront_validResp_ = earliestFront_validResp;
        PipelineFront latestFront_validResp_ = latestFront_validResp;
        Stream<PipelineStage> nextStages = earliestFront_validResp
                                               .streamNext_bfs(successor -> earliestFront_validResp_.contains(successor))
                                               .filter(successor -> !earliestFront_validResp_.contains(successor));
        earliestFront_validResp = new PipelineFront(nextStages);
        if (earliestFront_validResp.asList().stream().anyMatch(earliestStage -> !latestFront_validResp_.isAroundOrAfter(earliestStage, coreProvidesValidResp)))
          latestFront_validResp = earliestFront_validResp; //Move along the latest front
      }
    }
    if (coreProvidesValidResp) {
      // Request directly from core
      return Optional.of(new RequestNodeFromCoreStrategy(validRespNode, earliestFront_validResp, latestFront_validResp));
    }
    return Optional.of(new DefaultMemValidRespStrategy(validRespNode, earliestFront_validResp, latestFront_validResp));
  }

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    this.pipelinedMemSizeStrategy.implement(out, nodeKeys, isLast);
    if (this.rdMemValidRespStrategy != null)
      this.rdMemValidRespStrategy.implement(out, nodeKeys, isLast);
    if (this.wrMemValidRespStrategy != null)
      this.wrMemValidRespStrategy.implement(out, nodeKeys, isLast);
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
