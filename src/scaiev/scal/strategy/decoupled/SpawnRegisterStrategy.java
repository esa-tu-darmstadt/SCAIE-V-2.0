package scaiev.scal.strategy.decoupled;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineStage;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.util.Verilog;

/** Strategy that creates registered nodes for pending spawn commits */
public class SpawnRegisterStrategy extends SingleNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  Verilog language;
  BNode bNodes;
  Core core;
  HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  Map<SCAIEVNode, Collection<String>> isaxesSortedByPriority;
  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param isaxesSortedByPriority For each spawn node, an ISAX name collection sorted by priority
   */
  public SpawnRegisterStrategy(Verilog language, BNode bNodes, Core core,
                               HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                               Map<SCAIEVNode, Collection<String>> isaxesSortedByPriority) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.isaxesSortedByPriority = isaxesSortedByPriority;
  }

  void LogicRegsSpawn(NodeLogicBlock logicBlock, NodeRegistryRO registry, SCAIEVNode spawnNode, String ISAX, PipelineStage spawnStage) {
    //(even if a WIREDIN result is returned, if there's any strategy that would create a builder for REGULAR, the lookup will trigger that
    // by adding a dependency)
    String mainSig =
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, spawnNode, spawnStage, ISAX));
    String mainSigReg = this.language.CreateBasicNodeName(spawnNode, spawnStage, ISAX, false) + "_reg";

    SCAIEVNode parentNode = bNodes.GetNonAdjNode(spawnNode);

    SCAIEVNode validNode = bNodes.GetAdjSCAIEVNode(parentNode, AdjacentNode.validReq).get();
    Optional<SCAIEVNode> validRespNode_opt = bNodes.GetAdjSCAIEVNode(parentNode, AdjacentNode.validHandshakeResp)
                                                .or(() -> bNodes.GetAdjSCAIEVNode(parentNode, AdjacentNode.validResp));
    Optional<SCAIEVNode> cancelReqNode_opt = bNodes.GetAdjSCAIEVNode(parentNode, AdjacentNode.cancelReq);
    String validSig =
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, validNode, spawnStage, ISAX));
    if (!spawnNode.DefaultMandatoryAdjSig() && cancelReqNode_opt.isPresent()) {
      // May still need some of the adjacent nodes for cancellation, e.g. the address of a register.
      validSig += " || " + registry.lookupExpressionRequired(new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
                                                                                      cancelReqNode_opt.get(), spawnStage, ISAX));
    }
    String validResponse = "";
    if (validRespNode_opt.isPresent()) {
      // validResponse = " && "+registry.lookupExpressionRequired(new NodeInstanceDesc.Key(validRespNode_opt.get().makeFamilyNode(),
      // spawnStage, "")); Note: Used the non-ISAX-specific validResp before (with family node, and with the full ISAX_fire2_r condition).
      //-> With SpawnOrderedMuxStrategy, priority is not reliable, and we need to use the per-ISAX validResp.
      validResponse = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(validRespNode_opt.get(), spawnStage, ISAX));
    }

    String assignValue = mainSig;
    final SCAIEVNode parentNode_ = parentNode;
    String priority =
        SpawnFireStrategy
            .getPriorityValidReqKeysStream(Purpose.REGISTERED, bNodes, op_stage_instr, spawnStage,
                                           SpawnFireStrategy.getISAXPriority(bNodes, isaxesSortedByPriority, parentNode)
                                               .takeWhile(entry -> !(entry.getKey().equals(parentNode_) && entry.getValue().equals(ISAX))))
            .flatMap(
                keys -> keys.stream()) // Since we're ORing all together anyway, there is no need to keep the condition key lists intact.
            .map(key -> registry.lookupExpressionRequired(key))
            .reduce("1'b0", (a, b) -> a + " || " + b);
    priority = " && !(" + priority + ") ";
    String fireNodeSuffix = SpawnFireStrategy.getFireNodeSuffix(parentNode);
    String elseLogic = "";
    if (spawnNode.DefaultMandatoryAdjSig()) {
      assignValue = "1";
      String elseCond =
          validResponse.isEmpty()
              ? (registry.lookupExpressionRequired(new NodeInstanceDesc.Key(SpawnFireStrategy.ISAX_fire2_r, spawnStage, fireNodeSuffix)) +
                 priority)
              : validResponse;
      elseLogic = language.tab.repeat(1) + "else if (" + elseCond + ")  \n" + language.tab.repeat(2) + mainSigReg + " <= 0;\n" +
                  language.tab.repeat(1) + "if (" + language.reset + ")\n" + language.tab.repeat(2) + mainSigReg + " <= 0;\n";
    } else if (spawnNode.getAdj() == AdjacentNode.cancelReq) {
      validSig = "1";
      assignValue = mainSig;
    }

    logicBlock.logic += "always @(posedge " + language.clk + ") begin // ISAX Spawn Regs for node " + spawnNode.name + " \n" +
                        language.tab.repeat(1) + "if (" + validSig + ") begin\n" + language.tab.repeat(2) + mainSigReg +
                        " <= " + assignValue + ";\n" + language.tab.repeat(1) + "end\n" + elseLogic + language.tab.repeat(0) + "end\n";

    if (spawnNode.size > 1)
      logicBlock.declarations += String.format("reg [%d-1:0] %s;\n", spawnNode.size, mainSigReg);
    else
      logicBlock.declarations += String.format("reg %s;\n", mainSigReg);
    logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGISTERED, spawnNode, spawnStage, ISAX), mainSigReg,
                                                ExpressionType.WireName));
  }

  @Override
  public Optional<NodeLogicBuilder> implement(NodeInstanceDesc.Key nodeKey) {
    if (!nodeKey.getPurpose().matches(Purpose.REGISTERED) || !nodeKey.getNode().isSpawn() || !nodeKey.getNode().allowMultipleSpawn ||
        !nodeKey.getNode().isInput || nodeKey.getAux() != 0)
      return Optional.empty();
    if (nodeKey.getISAX().isEmpty()) {
      logger.error("SpawnRegisterStrategy assumes all REGISTERED spawn nodes are associated with an ISAX");
      return Optional.empty();
    }

    return Optional.of(NodeLogicBuilder.fromFunction("SpawnRegisterStrategy_" + nodeKey.toString(), registry -> {
      NodeLogicBlock ret = new NodeLogicBlock();
      this.LogicRegsSpawn(ret, registry, nodeKey.getNode(), nodeKey.getISAX(), nodeKey.getStage());
      return ret;
    }));
  }
}
