package scaiev.scal.strategy.standard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.util.Verilog;

/**
 * Provides a default implementation for RdInstr_RS, RdInstr_RD.
 */
public class DefaultRdInstrRSRDStrategy extends MultiNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  StrategyBuilders strategyBuilders;
  Verilog language;
  BNode bNodes;
  Core core;
  HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  HashMap<String, SCAIEVInstr> allISAXes;

  /**
   * @param strategyBuilders The StrategyBuilders object to build sub-strategies with
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core node description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param allISAXes The ISAX descriptions
   */
  public DefaultRdInstrRSRDStrategy(StrategyBuilders strategyBuilders, Verilog language, BNode bNodes, Core core,
                                    HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                                    HashMap<String, SCAIEVInstr> allISAXes) {
    this.strategyBuilders = strategyBuilders;
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.allISAXes = allISAXes;
  }
  
  MultiNodeStrategy pipeliner = null;
  List<PipelineStage> builtForStage = new ArrayList<>();
  SCAIEVNode sizedNode_RdInstr_RD = null;
  SCAIEVNode sizedNode_RdInstr_RS = null;

  private String CreateAllImport(NodeRegistryRO registry, BNode bNodes, PipelineStage stage, List<String> lookAtISAX,
                                        HashSet<NodeInstanceDesc.Key> existingPinKeys, boolean prefixOR) {
    String expr = "";
    for (String isax : lookAtISAX)
      if (!isax.isEmpty() && allISAXes.containsKey(isax) && !allISAXes.get(isax).HasNoOp()) {
        NodeInstanceDesc.Key ivalidKey = new NodeInstanceDesc.Key(bNodes.RdIValid, stage, isax);
        if (existingPinKeys.add(ivalidKey)) {
          expr += ((!prefixOR && expr.isEmpty()) ? "" : " || ") + registry.lookupRequired(ivalidKey).getExpressionWithParens();
        }
      }
    return expr;
  }

  private NodeLogicBlock buildForStage(NodeRegistryRO registry, PipelineStage stage) {
    NodeLogicBlock ret = new NodeLogicBlock();
    String inStageValidCond =
        registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdInStageValid, stage, "")).getExpressionWithParens();
    HashSet<NodeInstanceDesc.Key> existingPinKeys = new HashSet<>();
    var rdInstrInst = registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdInstr, stage, ""));
    String rdinstrExpr = rdInstrInst.getExpression();
    if (rdInstrInst.getExpressionType() != ExpressionType.WireName) {
      //Assign non-wire expression to a wire, as we'll have it appear several times in the decoding expressions.
      String rdinstrWire = "RdInstr_RSRD_%s_fallback_instrword_s".formatted(stage.getName());
      ret.declarations += "logic [%d-1:0] %s;\n".formatted(rdInstrInst.getKey().getNode().size, rdinstrWire);
      ret.logic += "assign %s = %s;\n".formatted(rdinstrWire, rdinstrExpr);
      rdinstrExpr = rdinstrWire;
    }
    // Detect register use from RISC-V standard instructions.
    String DH_rs1 = "( (" + inStageValidCond + " && "
             + "(%1$s[6:0] != 7'b0110111) && (%1$s[6:0] != 7'b0010111) && (%1$s[6:0] != 7'b1101111))".formatted(rdinstrExpr);
    String DH_rs2 = "( (" + inStageValidCond + " && "
             + ("(%1$s[6:0] != 7'b0110111) && (%1$s[6:0] != 7'b0010011) && (%1$s[6:0] != 7'b0000011) && ".formatted(rdinstrExpr)
              + "(%1$s[6:0] != 7'b0010111) && (%1$s[6:0] != 7'b1100111) && (%1$s[6:0] != 7'b1101111))".formatted(rdinstrExpr));
    String DH_rd = "( (" + inStageValidCond + " && (%1$s[6:0] != 7'b1100011) && (%1$s[6:0] != 7'b0100011))".formatted(rdinstrExpr);

    // Detect register use from ISAX instructions.
    List<String> lookAtISAX = new ArrayList<String>();
    if (this.op_stage_instr.containsKey(bNodes.RdRS1))
      for (var entry : this.op_stage_instr.get(bNodes.RdRS1).entrySet())
        lookAtISAX.addAll(entry.getValue());
    if (!lookAtISAX.isEmpty())
      DH_rs1 += CreateAllImport(registry, bNodes, stage, lookAtISAX, existingPinKeys, true);
    DH_rs1 += ")";

    lookAtISAX.clear();
    if (this.op_stage_instr.containsKey(bNodes.RdRS2))
      for (var entry : this.op_stage_instr.get(bNodes.RdRS2).entrySet())
        lookAtISAX.addAll(entry.getValue());
    if (!lookAtISAX.isEmpty())
      DH_rs2 += CreateAllImport(registry, bNodes, stage, lookAtISAX, existingPinKeys, true);
    DH_rs2 += ")";

    lookAtISAX.clear();
    if (this.op_stage_instr.containsKey(bNodes.WrRD))
      for (var entry : this.op_stage_instr.get(bNodes.WrRD).entrySet())
        lookAtISAX.addAll(entry.getValue());
    if (!lookAtISAX.isEmpty())
      DH_rd += CreateAllImport(registry, bNodes, stage, lookAtISAX, existingPinKeys, true);

    lookAtISAX.clear();
    if (this.op_stage_instr.containsKey(bNodes.WrRD_spawn)) // should be, that's why we are here...
      for (var entry : this.op_stage_instr.get(bNodes.WrRD_spawn).entrySet())
        lookAtISAX.addAll(entry.getValue()); // wrrd datahaz also for spawned instr (alternative would be to check if their latency is
                                             // larger than started ones...but additional HW)
    if (!lookAtISAX.isEmpty())
      DH_rd += CreateAllImport(registry, bNodes, stage, lookAtISAX, existingPinKeys, true);
    DH_rd += ")";

    //Generate a SCAIEVNode with size, elements for the default implementation.
    if (sizedNode_RdInstr_RD == null) {
      sizedNode_RdInstr_RD = SCAIEVNode.CloneNode(bNodes.RdInstr_RD, Optional.empty(), true);
      sizedNode_RdInstr_RD.size = 6; //{valid bit, register number}
      sizedNode_RdInstr_RD.elements = 1;
    }
    //Assign the condition to a wire and output it with a fallback-purposed key.
    var DH_rd_key = new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, sizedNode_RdInstr_RD, stage, "", 0);
    String DH_rd_wire = "RdInstr_RD_%s_fallback_s".formatted(stage.getName());
    ret.declarations += "logic [%d-1:0] %s;\n".formatted(6, DH_rd_wire);
    ret.logic += "assign %s = {%s, %s[11:7]};\n".formatted(DH_rd_wire, DH_rd, rdinstrExpr);
    ret.outputs.add(new NodeInstanceDesc(DH_rd_key, DH_rd_wire, ExpressionType.WireName));

    //Generate a SCAIEVNode with size, elements for the default implementation.
    if (sizedNode_RdInstr_RS == null) {
      sizedNode_RdInstr_RS = SCAIEVNode.CloneNode(bNodes.RdInstr_RS, Optional.empty(), true);
      sizedNode_RdInstr_RS.size = 12; //{valid bit rs2, register number rs2},{...rs1} 
      sizedNode_RdInstr_RS.elements = 2; //rs2,rs1
    }
    //Assign the condition to a wire and output it with a fallback-purposed key.
    var DH_rs_key = new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, sizedNode_RdInstr_RS, stage, "", 0);
    String DH_rs_wire = "RdInstr_RS_%s_fallback_s".formatted(stage.getName());
    ret.declarations += "logic [%d-1:0] %s;\n".formatted(12, DH_rs_wire);
    ret.logic += "assign %s = {%s, %s[24:20], %s, %s[19:15]};\n".formatted(DH_rs_wire, DH_rs2, rdinstrExpr, DH_rs1, rdinstrExpr);
    ret.outputs.add(new NodeInstanceDesc(DH_rs_key, DH_rs_wire, ExpressionType.WireName));
    return ret;
  }

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    // Assign id from RdIssueID.
    var nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      NodeInstanceDesc.Key nodeKey = nodeKeyIter.next();
      if (nodeKey.getAux() != 0
          || !nodeKey.getISAX().isEmpty()
          || (!nodeKey.getNode().equals(bNodes.RdInstr_RD)
              && !nodeKey.getNode().equals(bNodes.RdInstr_RS)))
        continue;
      var rdInstrCoreNode = core.GetNodes().get(bNodes.RdInstr);
      if (rdInstrCoreNode == null)
        continue;
      PipelineFront rdInstrEarliestFront = core.TranslateStageScheduleNumber(rdInstrCoreNode.GetEarliest());

      if (nodeKey.getPurpose().matches(Purpose.WIREDIN_FALLBACK) && rdInstrEarliestFront.contains(nodeKey.getStage())) {
        //Implement in the stage where RdInstr first becomes available.
        nodeKeyIter.remove();
        if (builtForStage.contains(nodeKey.getStage()))
          continue;
        builtForStage.add(nodeKey.getStage());
        out.accept(NodeLogicBuilder.fromFunction("DefaultRdInstrRSRDStrategy_" + nodeKey.getStage().getName(), registry -> {
          return buildForStage(registry, nodeKey.getStage());
        }));
      }
      else if (nodeKey.getPurpose().matches(Purpose.PIPEDIN)) {
        //Pipeline into all later stages
        if (pipeliner == null) {
          PipelineFront minPipeToFront = new PipelineFront(rdInstrEarliestFront.asList().stream().flatMap(stage->stage.getNext().stream()).distinct());
          pipeliner = strategyBuilders.buildNodeRegPipelineStrategy(language, bNodes, minPipeToFront,
                                                                    false, false, false,
                                                                    key -> rdInstrEarliestFront.isAroundOrBefore(key.getStage(), false),
                                                                    key -> rdInstrEarliestFront.contains(key.getStage()),
                                                                    MultiNodeStrategy.noneStrategy,
                                                                    false);
        }
        List<NodeInstanceDesc.Key> singleKeyList = new ArrayList<>(1);
        singleKeyList.add(nodeKey);
        pipeliner.implement(out, singleKeyList, isLast);
        if (singleKeyList.isEmpty())
          nodeKeyIter.remove();
      }
    }
  }
}
