package scaiev.scal.strategy.standard;

import java.util.HashMap;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistry;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.util.Verilog;

/** Implements per-ISAX defaults for the validReq and cancelReq adjacent nodes */
public class DefaultValidCancelReqStrategy extends SingleNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  StrategyBuilders strategyBuilders;
  Verilog language;
  BNode bNodes;
  Core core;
  HashMap<String, SCAIEVInstr> allISAXes;

  protected MultiNodeStrategy pipelinedMemSizeStrategy;
  protected MultiNodeStrategy regularPipelinedMemAddrStrategy;
  protected MultiNodeStrategy spawnPipelinedMemAddrStrategy;

  /**
   * @param strategyBuilders The StrategyBuilders object to build sub-strategies with
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core node description
   * @param allISAXes The ISAX descriptions
   */
  public DefaultValidCancelReqStrategy(StrategyBuilders strategyBuilders, Verilog language, BNode bNodes, Core core,
                                       HashMap<String, SCAIEVInstr> allISAXes) {
    this.strategyBuilders = strategyBuilders;
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.allISAXes = allISAXes;
  }

  private Optional<NodeLogicBuilder> implementValidReq(Key nodeKey) {
    if (allISAXes.containsKey(nodeKey.getISAX()) && allISAXes.get(nodeKey.getISAX()).HasNoOp())
      return Optional.empty();
    var requestedFor = new RequestedForSet(nodeKey.getISAX());
    String wireName = nodeKey.toString(false) + "_default_s";
    return Optional.of(NodeLogicBuilder.fromFunction("DefaultValidCancelReqStrategy_" + nodeKey.toString(), registry -> {
      var ret = new NodeLogicBlock();
      ret.declarations += String.format("wire %s;\n", wireName);
      ret.logic += String.format(
          "assign %s = %s;\n", wireName,
          registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, nodeKey.getStage(), nodeKey.getISAX())));
      ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.WIREDIN_FALLBACK), wireName,
                                           ExpressionType.WireName, requestedFor));
      return ret;
    }));
  }

  private Optional<NodeLogicBuilder> implementNoCancelReq(Key nodeKey) {
    var requestedFor = new RequestedForSet(nodeKey.getISAX());
    String wireName = nodeKey.toString(false) + "_default_s";
    return Optional.of(NodeLogicBuilder.fromFunction("DefaultValidCancelReqStrategy_" + nodeKey.toString() + " (never)", registry -> {
      var ret = new NodeLogicBlock();
      ret.declarations += String.format("wire %s;\n", wireName);
      ret.logic += String.format("assign %s = 1'b0;\n", wireName);
      ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.WIREDIN_FALLBACK), wireName,
          ExpressionType.WireName, requestedFor));
      return ret;
    }));
  }
  private Optional<NodeLogicBuilder> implementCancelReq(Key nodeKey) {
    var requestedFor = new RequestedForSet(nodeKey.getISAX());
    String wireName = nodeKey.toString(false) + "_default_s";
    SCAIEVNode validReqNode = bNodes.GetAdjSCAIEVNode(bNodes.GetNonAdjNode(nodeKey.getNode()), AdjacentNode.validReq).orElseThrow();
    return Optional.of(NodeLogicBuilder.fromFunction("DefaultValidCancelReqStrategy_" + nodeKey.toString(), registry -> {
      var ret = new NodeLogicBlock();
      ret.declarations += String.format("wire %s;\n", wireName);
      var wiredinValidreqInst = registry.lookupRequired(
          new NodeInstanceDesc.Key(Purpose.WIREDIN, validReqNode, nodeKey.getStage(), nodeKey.getISAX()), requestedFor);
      // validReq is not given by the ISAX, so it can't cancel this operation
      if (wiredinValidreqInst.getExpression().startsWith(NodeRegistry.MISSING_PREFIX)
          || wiredinValidreqInst.getKey().getPurpose().equals(Purpose.WIREDIN_FALLBACK) ||
          allISAXes.containsKey(nodeKey.getISAX()) &&
              allISAXes.get(nodeKey.getISAX()).HasNoOp() //'always'/NoOpcode ISAXes can't cancel from omission of validReq
      ) {
        ret.logic += String.format("assign %s = 1'b0;\n", wireName);
      } else {
        // Set the cancel signal if the default validReq is set (i.e. ISAX is present and proceeding)
        //  while the ISAX has it not set.
        String defaultValidreqExpr = registry.lookupExpressionRequired(
            new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, validReqNode, nodeKey.getStage(), nodeKey.getISAX()), requestedFor);
        ret.logic += String.format("assign %s = %s && !%s;\n", wireName, defaultValidreqExpr, wiredinValidreqInst.getExpression());
      }
      ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.WIREDIN_FALLBACK), wireName,
                                           ExpressionType.WireName, requestedFor));
      return ret;
    }));
  }

  private Optional<NodeLogicBuilder> implementAddrReq(Key nodeKey) {
    var requestedFor = new RequestedForSet(nodeKey.getISAX());
    String wireName = nodeKey.toString(false) + "_default_s";
    SCAIEVNode validReqNode = bNodes.GetAdjSCAIEVNode(bNodes.GetNonAdjNode(nodeKey.getNode()), AdjacentNode.validReq).orElseThrow();
    boolean isAlwaysISAX = allISAXes.containsKey(nodeKey.getISAX()) && allISAXes.get(nodeKey.getISAX()).HasNoOp();
    return Optional.of(NodeLogicBuilder.fromFunction("DefaultValidCancelReqStrategy_" + nodeKey.toString(), registry -> {
      var ret = new NodeLogicBlock();
      ret.declarations += String.format("wire %s;\n", wireName);
      var wiredinValidreqInst =
          validReqNode.noInterfToISAX
              ? null
              : registry.lookupRequired(new NodeInstanceDesc.Key(Purpose.WIREDIN, validReqNode, nodeKey.getStage(), nodeKey.getISAX()),
                                        requestedFor);
      if (wiredinValidreqInst == null || wiredinValidreqInst.getExpression().startsWith(NodeRegistry.MISSING_PREFIX)) {
        //				String ivalidExpr = isAlwaysISAX
        //					? "1'b1"
        //					: registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid,
        // nodeKey.getStage(), nodeKey.getISAX()));
        String ivalidExpr = "1'b1";
        ret.logic += String.format("assign %s = %s;\n", wireName, ivalidExpr);
      } else {
        ret.logic += String.format("assign %s = %s;\n", wireName, wiredinValidreqInst.getExpression());
      }
      ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.WIREDIN_FALLBACK), wireName,
                                           ExpressionType.WireName, requestedFor));
      return ret;
    }));
  }

  @Override
  public Optional<NodeLogicBuilder> implement(Key nodeKey) {
    if (!nodeKey.getPurpose().matches(Purpose.WIREDIN_FALLBACK) || nodeKey.getISAX().isEmpty() || nodeKey.getAux() != 0)
      return Optional.empty();
    if (nodeKey.getNode().getAdj() == AdjacentNode.cancelReq) {
      if (nodeKey.getNode().isSpawn() && allISAXes.containsKey(nodeKey.getISAX()) && allISAXes.get(nodeKey.getISAX()).GetRunsAsDynamic())
        return implementNoCancelReq(nodeKey);
      return implementCancelReq(nodeKey);
    }
    if (nodeKey.getNode().getAdj() == AdjacentNode.validReq && !nodeKey.getNode().isSpawn())
      return implementValidReq(nodeKey);
    if (nodeKey.getNode().getAdj() == AdjacentNode.addrReq && !nodeKey.getNode().isSpawn())
      return implementAddrReq(nodeKey);
    return Optional.empty();
  }
}
