package scaiev.scal.strategy.decoupled;

import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.util.Verilog;

/** Generates {@link SCAIEVNode.AdjacentNode#validHandshakeResp} for decoupled spawn requests to the core */
public class DefaultHandshakeRespStrategy extends SingleNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  Verilog language;
  BNode bNodes;
  Core core;

  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core node description
   */
  public DefaultHandshakeRespStrategy(Verilog language, BNode bNodes, Core core) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
  }

  private Optional<NodeLogicBuilder> implementHandshakeResp(Key nodeKey) {
    assert(nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0);
    List<PipelineStage> relevantStartSpawnStages = DecoupledPipeStrategy.getRelevantStartSpawnStages(core, nodeKey.getStage());
    if (relevantStartSpawnStages.isEmpty()) {
      logger.error("DefaultHandshakeRespStrategy - Found no matching startSpawnStage for " + nodeKey.getStage().getName());
      return Optional.empty();
    }
    if (relevantStartSpawnStages.size() > 1)
      logger.warn("DefaultHandshakeRespStrategy - only considering first of several 'start spawn stages'");
    PipelineStage startSpawnStage = relevantStartSpawnStages.get(0);

    assert(nodeKey.getNode().getAdj() == AdjacentNode.validHandshakeResp);
    SCAIEVNode baseNode = bNodes.GetNonAdjNode(nodeKey.getNode());
    SCAIEVNode validReqNode = bNodes.GetAdjSCAIEVNode(baseNode, AdjacentNode.validReq).orElseThrow().makeFamilyNode();
    
    var requestedFor = new RequestedForSet();
    String wireName = nodeKey.toString(false) + "_default_r";
    return Optional.of(NodeLogicBuilder.fromFunction("DefaultHandshakeRespStrategy_" + nodeKey.toString(), registry -> {
      var ret = new NodeLogicBlock();
      String spawnAllowedCond = SpawnFireStrategy.ConditionSpawnAllowed(bNodes, registry, requestedFor, baseNode, startSpawnStage, nodeKey.getStage());
      String validReqExpr = registry.lookupRequired(new NodeInstanceDesc.Key(validReqNode, nodeKey.getStage(), "")).getExpressionWithParens();
      ret.declarations += String.format("logic %s;\n", wireName);
      ret.logic += "assign %s = %s && (%s);\n".formatted(wireName, validReqExpr, spawnAllowedCond);
      ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.WIREDIN_FALLBACK), wireName,
                                           ExpressionType.WireName, requestedFor));
      return ret;
    }));
  }

  @Override
  public Optional<NodeLogicBuilder> implement(Key nodeKey) {
    if (!nodeKey.getPurpose().matches(Purpose.WIREDIN_FALLBACK) || nodeKey.getAux() != 0)
      return Optional.empty();
    if (nodeKey.getNode().isSpawn() && nodeKey.getStage().getKind() == StageKind.Decoupled &&
        nodeKey.getISAX().isEmpty() &&
        nodeKey.getNode().getAdj() == AdjacentNode.validHandshakeResp)
      return implementHandshakeResp(nodeKey);
    return Optional.empty();
  }

}
