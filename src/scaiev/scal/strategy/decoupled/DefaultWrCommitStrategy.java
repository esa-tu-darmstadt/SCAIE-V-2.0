package scaiev.scal.strategy.decoupled;

import java.util.Optional;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.util.Verilog;

/** Implements the default WrCommit_spawn stub */
public class DefaultWrCommitStrategy extends SingleNodeStrategy {
  Verilog language;
  BNode bNodes;
  Core core;
  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   */
  public DefaultWrCommitStrategy(Verilog language, BNode bNodes, Core core) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
  }

  @Override
  public Optional<NodeLogicBuilder> implement(NodeInstanceDesc.Key nodeKey) {
    if (nodeKey.getPurpose().matches(Purpose.WIREDIN_FALLBACK) && nodeKey.getNode().equals(bNodes.WrCommit_spawn) &&
        !nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0) {
      RequestedForSet requestedFor = new RequestedForSet(nodeKey.getISAX());
      return Optional.of(NodeLogicBuilder.fromFunction("DefaultWrCommitStrategy_" + nodeKey.toString(), registry -> {
        var ret = new NodeLogicBlock();
        String wireName = nodeKey.toString(false) + "_default";
        ret.declarations += String.format("wire %s;\n", wireName);
        String validReqExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
            Purpose.match_WIREDIN_OR_PIPEDIN, bNodes.WrCommit_spawn_validReq, nodeKey.getStage(), nodeKey.getISAX()));
        ret.logic += String.format("assign %s = %s;\n", wireName, validReqExpr);
        ret.outputs.add(new NodeInstanceDesc(
            new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, bNodes.WrCommit_spawn, nodeKey.getStage(), nodeKey.getISAX()), wireName,
            ExpressionType.WireName, requestedFor));

        return ret;
      }));
    }
    if (nodeKey.getPurpose().matches(Purpose.WIREDIN_FALLBACK) && nodeKey.getNode().equals(bNodes.WrCommit_spawn_validResp) &&
        nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0) {
      RequestedForSet requestedFor = new RequestedForSet();
      return Optional.of(NodeLogicBuilder.fromFunction("DefaultWrCommitStrategy_" + nodeKey.toString(), registry -> {
        var ret = new NodeLogicBlock();
        String wireName = nodeKey.toString(false) + "_default";
        ret.declarations += String.format("wire %s;\n", wireName);
        ret.logic += String.format("assign %s = 1'b1;\n", wireName);
        ret.outputs.add(new NodeInstanceDesc(
            new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, bNodes.WrCommit_spawn_validResp, nodeKey.getStage(), nodeKey.getISAX()),
            wireName, ExpressionType.WireName, requestedFor));

        return ret;
      }));
    }
    return Optional.empty();
  }
}
