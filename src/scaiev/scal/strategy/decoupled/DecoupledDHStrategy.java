package scaiev.scal.strategy.decoupled;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.util.Verilog;

/** Strategy that implements the data hazard unit for decoupled WrRD_spawn, stalling the 'start spawn' stage on hazard detection. */
public class DecoupledDHStrategy extends MultiNodeStrategy {

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
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param allISAXes The ISAX descriptions
   */
  public DecoupledDHStrategy(StrategyBuilders strategyBuilders, Verilog language, BNode bNodes, Core core,
                             HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                             HashMap<String, SCAIEVInstr> allISAXes) {
    this.strategyBuilders = strategyBuilders;
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.allISAXes = allISAXes;
  }

  @Override
  public void setLanguage(Verilog lang) {
    this.language = lang;
  }

  private boolean buildWithScoreboard = true;
  /**
   * Set whether SCAIE-V should instantiate a scoreboard.
   */
  public void setBuildWithScoreboard(boolean val) { this.buildWithScoreboard = val; }

  private SCAIEVNode makeNodeCancelValid(SCAIEVNode spawnNode) {
    return SCAIEVNode.CloneNode(spawnNode, Optional.of(bNodes.cancel_from_user_valid + "_" + spawnNode), false);
  }
  private SCAIEVNode makeNodeCancelAddr(SCAIEVNode spawnNode) {
    return SCAIEVNode.CloneNode(spawnNode, Optional.of(bNodes.cancel_from_user + "_" + spawnNode), false);
  }
  private SCAIEVNode makeNodeCommittedRdSpawnValid(SCAIEVNode spawnNode) {
    return SCAIEVNode.CloneNode(spawnNode, Optional.of(bNodes.committed_rd_spawn_valid + "_" + spawnNode), false);
  }
  private SCAIEVNode makeNodeCommittedRdSpawnAddr(SCAIEVNode spawnNode) {
    return SCAIEVNode.CloneNode(spawnNode, Optional.of(bNodes.committed_rd_spawn + "_" + spawnNode), false);
  }
  public static SCAIEVNode makeToCOREStallRS(SCAIEVNode spawnNode) {
    return SCAIEVNode.CloneNode(spawnNode, Optional.of("to_CORE_stall_RS_" + spawnNode), false);
  }
  private static final Purpose DHInternalPurpose = new Purpose("DHInternal", true, Optional.empty(), List.of());

  private void CreateUserCancelSpawn(NodeLogicBlock logicBlock, NodeRegistryRO registry, SCAIEVNode spawnNode, PipelineStage spawnStage) {
    SCAIEVNode nodeValid = bNodes.GetAdjSCAIEVNode(spawnNode, AdjacentNode.validReq).get();
    SCAIEVNode nodeAddr = bNodes.GetAdjSCAIEVNode(spawnNode, AdjacentNode.addr).get();

    SCAIEVNode nodeCancelValid = makeNodeCancelValid(spawnNode);
    SCAIEVNode nodeCancelAddr = makeNodeCancelAddr(spawnNode);

    String cancelValid = language.CreateNodeName(nodeCancelValid.name, spawnStage, "", false);
    String cancelAddr = language.CreateNodeName(nodeCancelAddr.name, spawnStage, "", false);
    String declareLogicValid = "wire " + cancelValid + ";\n";
    String declareLogicAddr = "wire [4:0]" + cancelAddr + ";\n";
    String cancelLogicValid = "assign " + cancelValid + " = 0"; // default
    String cancelLogicAddr = cancelAddr + " = 0;\n";

    // TODO: Get all sub-pipelines with parent=spawnStage
    for (String ISAX : this.op_stage_instr.get(spawnNode).get(spawnStage)) {
      if (allISAXes.get(ISAX).GetFirstNode(spawnNode).HasAdjSig(AdjacentNode.validReq)) {
        var validExprResult =
            registry.lookupExpressionRequired(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.REGULAR, nodeValid, spawnStage, ISAX));
        var validExprISAX =
            registry.lookupExpressionRequired(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.PIPEDIN, nodeValid, spawnStage, ISAX));
        var addrExprISAX =
            registry.lookupExpressionRequired(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.PIPEDIN, nodeAddr, spawnStage, ISAX));
        cancelLogicValid += " || (" + validExprISAX + " && ~" + validExprResult + ")";
        if (spawnNode.elements > 1)
          cancelLogicAddr += "if(" + validExprISAX + ") " + cancelAddr + " = " + addrExprISAX + ";\n";
        // cancelLogicValid += " || "+ language.CreateNodeName(nodeValid, spawnStage, ISAX)+ShiftmoduleSuffix+" && ~"+
        // language.CreateNodeName(nodeValid, spawnStage,ISAX); if(node.elements>1) 	cancelLogicAddr += "if("+
        // language.CreateNodeName(nodeValid, spawnStage, ISAX)+ShiftmoduleSuffix+") "+cancelAddr+" = "+language.CreateNodeName(nodeAddr,
        // spawnStage, ISAX)+";\n";
      }
    }
    if (spawnNode.elements > 1 && cancelLogicValid.length() > 15) {
      declareLogicAddr = "reg [4:0]" + cancelAddr + ";\n";
      cancelLogicAddr = language.CreateInAlways(false, cancelLogicAddr);
    } else
      cancelLogicAddr = "assign " + cancelAddr + " = 0;\n";
    logicBlock.declarations += declareLogicAddr + declareLogicValid;
    logicBlock.logic += cancelLogicValid + ";\n" + cancelLogicAddr;
    logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(DHInternalPurpose, nodeCancelValid, spawnStage, ""), cancelValid,
                                                ExpressionType.WireName));
    logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(DHInternalPurpose, nodeCancelAddr, spawnStage, ""), cancelAddr,
                                                ExpressionType.WireName));
  }
  private HashSet<SCAIEVNode> implementedUserCancelSpawns = new HashSet<>();
  private void implementUserCancelSpawn(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    Iterator<NodeInstanceDesc.Key> nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      var nodeKey = nodeKeyIter.next();
      if (!nodeKey.getPurpose().matches(DHInternalPurpose) || nodeKey.getStage().getKind() != StageKind.Decoupled)
        continue;
      assert (bNodes.cancel_from_user_valid.name.startsWith(bNodes.cancel_from_user.name)); //(Check assumption for condition below)
      if (!nodeKey.getNode().name.startsWith(bNodes.cancel_from_user.name))
        continue;
      SCAIEVNode parentNode = bNodes.GetSCAIEVNode(nodeKey.getNode().nameParentNode);
      if (parentNode.name.isEmpty() || !parentNode.isSpawn()) {
        logger.warn("implementUserCancelSpawn: Encountered node with unsupported parent - " + nodeKey.toString() + " (parent " +
                    nodeKey.getNode().nameParentNode + ")");
        continue;
      }
      if (!nodeKey.getNode().name.equals(bNodes.cancel_from_user.name + "_" + parentNode.name) &&
          !nodeKey.getNode().name.equals(bNodes.cancel_from_user_valid + "_" + parentNode.name)) {
        logger.warn("implementUserCancelSpawn: Encountered unsupported node " + nodeKey.toString());
        continue;
      }
      // Only instantiate once (e.g. if both cancel_from_user and cancel_from_user_valid are requested)
      if (implementedUserCancelSpawns.add(parentNode)) {
        out.accept(NodeLogicBuilder.fromFunction("DecoupledDHStrategy_UserCancelSpawn_" + parentNode.name, registry -> {
          NodeLogicBlock ret = new NodeLogicBlock();
          CreateUserCancelSpawn(ret, registry, parentNode, nodeKey.getStage());
          return ret;
        }));
      }
      nodeKeyIter.remove();
    }
  }

  private static String CreateAllImport(NodeRegistryRO registry, BNode bNodes, PipelineStage stage, List<String> lookAtISAX,
                                        HashSet<NodeInstanceDesc.Key> existingPinKeys, StringBuilder instantiationBuilder,
                                        StringBuilder interfaceBuilder) {
    String expr = "";
    for (String isax : lookAtISAX)
      if (!isax.isEmpty()) {
        String moduleInterfaceName = "RdIValid_" + isax + "_" + stage.getName();
        NodeInstanceDesc.Key ivalidKey = new NodeInstanceDesc.Key(bNodes.RdIValid, stage, isax);

        expr += (expr.isEmpty() ? "" : " || ") + moduleInterfaceName;
        if (existingPinKeys.add(ivalidKey)) {
          instantiationBuilder.append(",\n." + moduleInterfaceName + "(" + registry.lookupExpressionRequired(ivalidKey) + ")");
          interfaceBuilder.append(",\n    input " + moduleInterfaceName);
        }
      }
    return expr;
  }

  /**
   * Creates a spawndatah_<node> module.
   * @param logicBlock the logic block to add the module into
   * @param registry the node registry
   * @param spawnNode the node to detect daza hazards on (usually: WrRD_spawn)
   * @return Verilog for additional pin assignments instantiation from within SCAL, starting with a comma but without a trailing comma.
   */
  private String DHModule(NodeLogicBlock logicBlock, NodeRegistryRO registry, SCAIEVNode spawnNode,
                          List<PipelineStage> startSpawnStagesList) {
    // Compute DH decoding checks for detecting DHs
    String DH_rs1 = "";
    String DH_rs2 = "";
    String DH_rd = "";
    HashSet<NodeInstanceDesc.Key> existingPinKeys = new HashSet<>();
    StringBuilder instantiationBuilder = new StringBuilder();
    StringBuilder interfaceBuilder = new StringBuilder();

    if (startSpawnStagesList.size() > 1) {
      logger.error("DecoupledDHStrategy DHModule: Only looking at one of the 'start spawn' stages.");
    }
    PipelineStage startSpawnStage = startSpawnStagesList.get(0);

    SCAIEVNode node = bNodes.GetSCAIEVNode(spawnNode.nameParentNode);
    if (node.equals(bNodes.WrRD)) {
      // parent of WrRD_spawn is WrRD

      String inStageValidCond =
          registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdInStageValid, startSpawnStage, "")).getExpressionWithParens();
      if (!inStageValidCond.startsWith("MISSING_")) {
        instantiationBuilder.append(",\n.inStageValid_i(" + inStageValidCond + ")");
        interfaceBuilder.append(",\n    input inStageValid_i");
        inStageValidCond = "inStageValid_i && ";
      } else
        inStageValidCond = "";

      // Detect register use from RISC-V standard instructions.
      DH_rs1 = "( (" + inStageValidCond +
               "(RdInstr_RDRS_i[6:0] !==7'b0110111) && (RdInstr_RDRS_i[6:0] !==7'b0010111) && (RdInstr_RDRS_i[6:0] !==7'b1101111))";
      DH_rs2 = "( (" + inStageValidCond +
               ("(RdInstr_RDRS_i[6:0] !==7'b0110111) && (RdInstr_RDRS_i[6:0] !==7'b0010011) && (RdInstr_RDRS_i[6:0] !==7'b0000011) && "
                + "(RdInstr_RDRS_i[6:0] !==7'b0010111) &&  (RdInstr_RDRS_i[6:0] !==7'b1100111)  &&  (RdInstr_RDRS_i[6:0] !==7'b1101111))");
      DH_rd = "( (" + inStageValidCond + "(RdInstr_RDRS_i[6:0] !==7'b1100011) && (RdInstr_RDRS_i[6:0] !==7'b0100011))";

      List<String> lookAtISAX = new ArrayList<String>();
      if (this.op_stage_instr.containsKey(bNodes.RdRS1))
        for (var entry : this.op_stage_instr.get(bNodes.RdRS1).entrySet())
          lookAtISAX.addAll(entry.getValue());
      if (!lookAtISAX.isEmpty())
        DH_rs1 += " || " +
                  CreateAllImport(registry, bNodes, startSpawnStage, lookAtISAX, existingPinKeys, instantiationBuilder, interfaceBuilder);
      DH_rs1 += ")";

      lookAtISAX.clear();
      if (this.op_stage_instr.containsKey(bNodes.RdRS2))
        for (var entry : this.op_stage_instr.get(bNodes.RdRS2).entrySet())
          lookAtISAX.addAll(entry.getValue());
      if (!lookAtISAX.isEmpty())
        DH_rs2 += " || " +
                  CreateAllImport(registry, bNodes, startSpawnStage, lookAtISAX, existingPinKeys, instantiationBuilder, interfaceBuilder);
      DH_rs2 += ")";

      lookAtISAX.clear();
      if (this.op_stage_instr.containsKey(bNodes.WrRD))
        for (var entry : this.op_stage_instr.get(bNodes.WrRD).entrySet())
          lookAtISAX.addAll(entry.getValue());
      if (!lookAtISAX.isEmpty())
        DH_rd += " || " +
                 CreateAllImport(registry, bNodes, startSpawnStage, lookAtISAX, existingPinKeys, instantiationBuilder, interfaceBuilder);

      lookAtISAX.clear();
      if (this.op_stage_instr.containsKey(bNodes.WrRD_spawn)) // should be, that's why we are here...
        for (var entry : this.op_stage_instr.get(bNodes.WrRD_spawn).entrySet())
          lookAtISAX.addAll(entry.getValue()); // wrrd datahaz also for spawned instr (alternative would be to check if their latency is
                                               // larger than started ones...but additional HW)
      if (!lookAtISAX.isEmpty())
        DH_rd += " || " +
                 CreateAllImport(registry, bNodes, startSpawnStage, lookAtISAX, existingPinKeys, instantiationBuilder, interfaceBuilder);
      DH_rd += ")";
    } else {
      // TODO: Which nodes is this intended for?
      //(the restriction to node.DH && !node.isAdj() && node.isSpawn()
      //  by implementDHModule(..) currently only applies to WrRD_spawn,
      //  i.e. this branch isn't currently reachable)
      SCAIEVNode regRdNode = bNodes.GetSCAIEVNode(bNodes.GetNameRdNode(node));
      List<String> lookAtISAX = new ArrayList<String>();
      if (this.op_stage_instr.containsKey(regRdNode))
        for (var entry : this.op_stage_instr.get(regRdNode).entrySet())
          lookAtISAX.addAll(entry.getValue());
      if (!lookAtISAX.isEmpty())
        DH_rs1 = CreateAllImport(registry, bNodes, startSpawnStage, lookAtISAX, existingPinKeys, instantiationBuilder, interfaceBuilder);
      else
        DH_rs1 = "1'b0";

      DH_rs2 = "1'b0";

      lookAtISAX.clear();
      if (this.op_stage_instr.containsKey(node))
        for (var entry : this.op_stage_instr.get(node).entrySet())
          lookAtISAX.addAll(entry.getValue());
      if (!lookAtISAX.isEmpty())
        DH_rd = CreateAllImport(registry, bNodes, startSpawnStage, lookAtISAX, existingPinKeys, instantiationBuilder, interfaceBuilder);

      lookAtISAX.clear();
      if (this.op_stage_instr.containsKey(spawnNode)) // should be, that's why we are here...
        for (var entry : this.op_stage_instr.get(spawnNode).entrySet())
          lookAtISAX.addAll(entry.getValue());
      if (!lookAtISAX.isEmpty())
        DH_rd += (DH_rd.isEmpty() ? "" : " || ") +
                 CreateAllImport(registry, bNodes, startSpawnStage, lookAtISAX, existingPinKeys, instantiationBuilder, interfaceBuilder);
      if (DH_rd.isEmpty())
        DH_rd = "1'b0";
    }

    String RdIValid_ISAX_startSpawn = "1'b0";
    // RdIValid_ISAX0_startSpawn = expression for 'any ISAX with `spawnNode` is in startSpawnStage'
    {
      if (this.op_stage_instr.containsKey(spawnNode)) { // should be, that's why we are here...
        List<String> lookAtISAX = new ArrayList<String>();
        for (var entry : this.op_stage_instr.get(spawnNode).entrySet())
          if (entry.getKey().getKind() == StageKind.Decoupled) {
            lookAtISAX.addAll(entry.getValue());
          }
        if (!lookAtISAX.isEmpty())
          RdIValid_ISAX_startSpawn =
              CreateAllImport(registry, bNodes, startSpawnStage, lookAtISAX, existingPinKeys, instantiationBuilder, interfaceBuilder);
      }
    }

    String RdRS2DH = "assign data_hazard_rs2 = 0;\n";
    int sizeAddr = 0;
    String sizeZero = "";
    if (spawnNode.equals(bNodes.WrRD_spawn)) {
      RdRS2DH = "assign dirty_bit_rs2 = rd_table_mem[RdInstr_RDRS_i[24:20]];\n"
                + "assign data_hazard_rs2 =  !flush_i[0] && dirty_bit_rs2 && (" + DH_rs2 + ");\n";
      sizeAddr = bNodes.WrRD_spawn_addr.size;
    }
    if (spawnNode.elements > 1) {
      sizeAddr = ((int)Math.ceil((Math.log10(spawnNode.elements) / Math.log10(2))));
    }
    if (sizeAddr != 0) {
      sizeZero = "[RD_W_P-1:0]";
    }
    String returnStr = "";
    boolean later_flushes = (startSpawnStagesList.stream()
                                 .flatMap(startSpawnStage_ -> startSpawnStage_.getNext().stream())
                                 .anyMatch(postStage -> postStage.getKind() == StageKind.Core)); // roughly: startSpawnStage < maxStage
    boolean later_flushes_dh = (startSpawnStagesList.stream()
                                    .flatMap(startSpawnStage_ -> startSpawnStage_.getNext().stream())
                                    .flatMap(afterStartSpawnStage -> afterStartSpawnStage.getNext().stream())
                                    .anyMatch(postStage -> postStage.getKind() == StageKind.Core)); // roughly: startSpawnStage+1 < maxStage
    returnStr +=
        "module spawndatah_" + spawnNode + "#(\n"
        + " parameter RD_W_P = " + sizeAddr + ",\n"
        + " parameter INSTR_W_P = 32,\n"
        + " parameter START_STAGE = " + startSpawnStagesList.get(0).getStagePos() + ",\n"
        + " parameter WB_STAGE = " + this.core.maxStage + "\n"
        + "\n"
        + ")(\n"
        + "    input clk_i,\n"
        + "    input rst_i,\n" + (later_flushes ? "    input [WB_STAGE-START_STAGE:0] flush_i,\n" : "") + "    input killall_i,\n"
        + "    input fence_i,\n"
        + "    input [INSTR_W_P-1:0] RdInstr_RDRS_i" + interfaceBuilder.toString() + ",\n"
        + "    input  WrRD_spawn_valid_i,\n"
        + "    input " + sizeZero + " WrRD_spawn_addr_i,\n"
        + "    input  cancel_from_user_valid_i,// user validReq bit was zero, but we need to clear its scoreboard dirty bit\n"
        + "    input " + sizeZero + "cancel_from_user_addr_i,\n"
        + "    output stall_RDRS_o, //  stall from ISAX,  barrier ISAX,  OR spawn DH\n" +
        ("    input  [WB_STAGE-START_STAGE:0] stall_RDRS_i // input from core. core stalled. Includes user stall, as these sigs are "
         + "combined within core\n") +
        ");\n"
        + "\n"
        + "\n"
        + "wire dirty_bit_rs1;\n"
        + "wire dirty_bit_rs2;\n"
        + "wire dirty_bit_rd;\n"
        + "wire data_hazard_rs1;\n"
        + "wire data_hazard_rs2;\n"
        + "wire data_hazard_rd;\n"
        + "wire we_spawn_start;\n"
        + "wire RdIValid_ISAX_startSpawn;\n"
        + "reg [2**RD_W_P-1:0] mask_start;\n"
        + "reg [2**RD_W_P-1:0] mask_stop_or_flush;\n"
        + "reg barrier_set;\n"
        + "\n"
        + "reg  [2**RD_W_P-1:0] rd_table_mem ;\n"
        + "reg  [2**RD_W_P-1:0] rd_table_temp;\n"
        + "\n"
        + "assign RdIValid_ISAX_startSpawn = " + RdIValid_ISAX_startSpawn + ";\n"
        + "\n"
        + "\n" +
        (later_flushes_dh
             ? "    reg  [RD_W_P-1:0] RdInstr_7_11_reg[WB_STAGE - START_STAGE-1:0];\n"
                   + "    reg [WB_STAGE-START_STAGE:1] RdIValid_reg;\n"
                   + "    always @(posedge clk_i ) begin\n"
                   + "        if(!stall_RDRS_i[0])\n"
                   + "            RdIValid_reg[1] <= RdIValid_ISAX_startSpawn; // or of all decoupled spawn\n" // RdIValid_ISAX0_2_i
                   + "        if((flush_i[1] && stall_RDRS_i[0]) || (flush_i[0] && !stall_RDRS_i[0]) || ( stall_RDRS_i[0] && "
                   + "!stall_RDRS_i[1]))\n"
                   + "RdIValid_reg[1] <= 0 ;\n"
                   + "        for(int k=2;k<=(WB_STAGE - START_STAGE);k=k+1) begin\n"
                   + "            if((flush_i[k] && stall_RDRS_i[k-1]) || (flush_i[k-1] && !stall_RDRS_i[k-1]) || ( stall_RDRS_i[k-1] && "
                   + "!stall_RDRS_i[k]))\n"
                   + "		          RdIValid_reg[k] <=0;\n"
                   + "		       else if(!stall_RDRS_i[k-1])\n"
                   + "                RdIValid_reg[k] <= RdIValid_reg[k-1];\n"
                   + "        end\n"
                   + "        if(rst_i) begin\n"
                   + "            for(int k=1;k<(WB_STAGE - START_STAGE);k=k+1)\n"
                   + "                RdIValid_reg[k] <= 0;\n"
                   + "        end\n"
                   + "    end\n"
                   + "    always @(posedge clk_i ) begin\n"
                   + "        if(!stall_RDRS_i[0])\n"
                   + "            RdInstr_7_11_reg[1] <= RdInstr_RDRS_i[11:7];\n"
                   + "        for(int k=2;k<(WB_STAGE - START_STAGE);k=k+1) begin\n"
                   + "        if(!stall_RDRS_i[k-1])\n"
                   + "             RdInstr_7_11_reg[k] <= RdInstr_7_11_reg[k-1];\n"
                   + "        end\n"
                   + "        if(rst_i) begin\n"
                   + "            for(int k=1;k<(WB_STAGE - START_STAGE);k=k+1)\n"
                   + "                RdInstr_7_11_reg[k] <= 0;\n"
                   + "        end\n"
                   + "    end\n"
                   + "\n"
             : "") +
        "assign we_spawn_start   = (RdIValid_ISAX_startSpawn && !stall_RDRS_i[0]);\n" //(RdIValid_ISAX0_2_i && ...
        + "\n"
        + "always_ff @(posedge clk_i)\n"
        + "begin\n"
        + "    if(rst_i)\n"
        + "        rd_table_mem <= 0;\n"
        + "    else\n"
        + "        rd_table_mem <= rd_table_temp;\n"
        + "end\n"
        + "\n"
        + "always_comb begin\n"
        + "    mask_start = {(2**RD_W_P){1'b0}};\n"
        + "    if(we_spawn_start)\n"
        + "        mask_start[RdInstr_RDRS_i[11:7]] = 1'b1;\n"
        + "end\n"
        + "\n"
        + "always_comb begin\n"
        + "    mask_stop_or_flush = {(2**RD_W_P){1'b1}};\n"
        + "    if(WrRD_spawn_valid_i)\n"
        + "        mask_stop_or_flush[WrRD_spawn_addr_i] = 1'b0; \n  "
        + "    if(cancel_from_user_valid_i)\n"
        + "        mask_stop_or_flush[cancel_from_user_addr_i] = 1'b0;\n"
        + "    if (killall_i) begin\n"
        + "        mask_stop_or_flush = 0;\n"
        + "    end\n" +
        (later_flushes_dh ? "	for(int k=1;k<(WB_STAGE - START_STAGE);k=k+1) begin\n"
                                + "		if(flush_i[k] && RdIValid_reg[k] )\n"
                                + "			mask_stop_or_flush[RdInstr_7_11_reg[k]] = 1'b0;\n"
                                + "	end\n"
                          : "") +
        "end\n"
        + "\n"
        + "always @(*) begin\n"
        + "  rd_table_temp = (rd_table_mem | mask_start) & mask_stop_or_flush;\n"
        + " end\n"
        + "\n"
        // For now, use general ISAX pipeline tracking for disaxfence instead of the data hazard unit.
        //				+ "always @(posedge clk_i)\n"
        //				+ "begin\n"
        //				+ "    if((|rd_table_mem) == 0)\n"
        //				+ "        barrier_set <= 0;\n"
        //				+ "    else if(fence_i && !flush_i[0])\n"
        //				+ "        barrier_set <= 1;\n"
        //				+ "end\n"
        //				+ "wire stall_fr_barrier = barrier_set;\n"
        + "wire stall_fr_barrier = 0;\n"
        + "\n"
        + "assign dirty_bit_rs1 = rd_table_mem[RdInstr_RDRS_i[19:15]];\n"
        + "assign dirty_bit_rd =  rd_table_mem[RdInstr_RDRS_i[11:7]];\n"
        + "assign data_hazard_rs1 =  !flush_i[0] &&  dirty_bit_rs1 && (" + DH_rs1 + ");\n"
        + "assign data_hazard_rd =  !flush_i[0] && dirty_bit_rd   && (" + DH_rd + ");\n" + RdRS2DH +
        "assign stall_RDRS_o = data_hazard_rs1 || data_hazard_rs2 || data_hazard_rd || stall_fr_barrier;\n"
        + "\n"
        + "endmodule\n"
        + ""; // TODO ISAX Encoding
    logicBlock.otherModules += returnStr;
    return instantiationBuilder.toString();
  }

  public static NodeInstanceDesc.Purpose purpose_MARKER_BUILT_DH =
      new NodeInstanceDesc.Purpose("MARKER_BUILT_DH", true, Optional.empty(), List.of());

  private void AddDHModule(NodeInstanceDesc.Key markerKey, NodeLogicBlock logicBlock, NodeRegistryRO registry, SCAIEVNode spawnNode,
                           PipelineStage spawnStage, int aux) {
    if (!buildWithScoreboard) {
      logicBlock.outputs.add(new NodeInstanceDesc(markerKey, "(disabled)", ExpressionType.AnyExpression_Noparen));
      return;
    }

    List<PipelineStage> startSpawnStagesList = DecoupledPipeStrategy.getRelevantStartSpawnStages(core, spawnStage);
    assert (startSpawnStagesList.size() > 0);
    assert (startSpawnStagesList.stream().allMatch(
        startSpawnStage -> startSpawnStage.getStagePos() == startSpawnStagesList.get(0).getStagePos()));
    if (startSpawnStagesList.size() > 1) {
      logger.warn("DecoupledDHStrategy AddDHModule: Only looking at one of the 'start spawn' stages.");
    }
    PipelineStage startSpawnStage = startSpawnStagesList.get(0);
    PipelineFront startSpawnStageFront = new PipelineFront(startSpawnStage);
    if (spawnStage.streamPrev_bfs(prevStage -> startSpawnStageFront.isBefore(prevStage, false))
            .filter(prevStage -> startSpawnStageFront.isBefore(prevStage, false))
            .anyMatch(stage_ -> !stage_.getContinuous())) {
      logger.warn("DecoupledDHStrategy AddDHModule: Not all stages between 'spawn' ({}) and 'start spawn' ({}) are continuous, breaking "
                      + "assumptions of built-in data hazard tracking.",
                  spawnStage.getName(), startSpawnStage.getName());
    }

    String moduleInstanceName = "spawndatah_" + spawnNode + "_inst";
    logicBlock.outputs.add(new NodeInstanceDesc(markerKey, moduleInstanceName, ExpressionType.WireName));

    String dhAdditionalInstantiation = DHModule(logicBlock, registry, spawnNode, startSpawnStagesList);

    /// ADD DH Mechanism
    // Differentiate between Wr Regfile and user nodes or address signals
    String instrSig = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdInstr, startSpawnStage, ""));
    String destSig = instrSig + "[11:7]";
    SCAIEVNode parentWrNode = bNodes.GetSCAIEVNode(spawnNode.nameParentNode);
    SCAIEVNode parentRdNode = bNodes.GetSCAIEVNode(bNodes.GetNameRdNode(parentWrNode));
    if (bNodes.IsUserBNode(spawnNode))
      if (spawnNode.elements > 1)
        destSig = registry.lookupExpressionRequired(
            new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(parentWrNode, AdjacentNode.addr).get(), startSpawnStage, ""));
      else
        destSig = "5'd0";
    String RdRS1Sig = instrSig + "[19:15]";
    if (bNodes.IsUserBNode(spawnNode))
      if (spawnNode.elements > 1)
        RdRS1Sig = registry.lookupExpressionRequired(
            new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(parentRdNode, AdjacentNode.addr).get(), startSpawnStage, ""));
      else
        RdRS1Sig = "5'd0";
    // Use cancel signal in case of user validReq = 0
    SCAIEVNode nodeCancelValid = makeNodeCancelValid(spawnNode);
    SCAIEVNode nodeCancelAddr = makeNodeCancelAddr(spawnNode);
    SCAIEVNode nodeCommittedRdSpawnValid = makeNodeCommittedRdSpawnValid(spawnNode);
    SCAIEVNode nodeCommittedRdSpawnAddr = makeNodeCommittedRdSpawnAddr(spawnNode);
    String flush = registry.lookupExpressionRequired(
        new NodeInstanceDesc.Key(DecoupledPipeStrategy.PseudoNode_StartSpawnToSpawnPipe_Flush, spawnStage, ""));
    String stallFrCore = registry.lookupExpressionRequired(
        new NodeInstanceDesc.Key(DecoupledPipeStrategy.PseudoNode_StartSpawnToSpawnPipe_StallFrCore, spawnStage, ""));
    String killall = registry.lookupExpressionRequired(
        new NodeInstanceDesc.Key(DecoupledPipeStrategy.PseudoNode_StartSpawnToSpawnPipe_KillAll, spawnStage, ""));
    String fence = registry.lookupExpressionRequired(
        new NodeInstanceDesc.Key(DecoupledPipeStrategy.PseudoNode_StartSpawnToSpawnPipe_Fence, spawnStage, ""));
    String toCOREstallRSName = "to_CORE_stall_RS_" + spawnNode + "_s";
    // Instantiate module
    logicBlock.logic +=
        "spawndatah_" + spawnNode + " " + moduleInstanceName + "(\n"
        + "    .clk_i(" + language.clk + "),\n"
        + "    .rst_i(" + language.reset + "),\n"
        + "    .killall_i(" + killall + "),\n"
        + "    .fence_i(" + fence + "),\n"
        + "    .flush_i({" + flush + "," +
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, startSpawnStage, "")) +
        "}),\n"
        //+ "    .RdIValid_ISAX0_2_i("+allIValid+"),\n"
        + "    .RdInstr_RDRS_i({" + instrSig + "[31:20], " + RdRS1Sig + "," + instrSig + "[14:12]," + destSig + " ," + instrSig +
        "[6:0] })" + dhAdditionalInstantiation.replace("\n", "\n    ") + ",\n"
        + "    .WrRD_spawn_valid_i(" +
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(nodeCommittedRdSpawnValid, spawnStage, "")) + "),\n"
        + "    .WrRD_spawn_addr_i(" +
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(nodeCommittedRdSpawnAddr, spawnStage, "")) + "),\n"
        + "    .cancel_from_user_valid_i(" +
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(DHInternalPurpose, nodeCancelValid, spawnStage, "")) + "),\n"
        + "    .cancel_from_user_addr_i(" +
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(DHInternalPurpose, nodeCancelAddr, spawnStage, "")) + "),\n"
        + "    .stall_RDRS_o(" + toCOREstallRSName + "),\n"
        + "    .stall_RDRS_i(" + stallFrCore + ")\n"
        + ");\n";
    logicBlock.declarations += "wire " + toCOREstallRSName + ";\n";
    logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(makeToCOREStallRS(spawnNode), startSpawnStage, ""),
                                                toCOREstallRSName, ExpressionType.WireName));

    PipelineFront startSpawnFront = this.core.GetStartSpawnStages();

    // Stall stages because of DH
    // for(int stage=0; stage <= this.core.GetStartSpawnStage();stage++) {
    this.core.GetRootStage().getAllChildren().filter(stage_ -> startSpawnFront.isAroundOrAfter(stage_, true)).forEach(stage -> {
      // TODO: The core backend itself should propagate the stall condition to previous stages.
      //       -> Check the backends. Then add the stall condition only to the startSpawn stage.
      logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, stage, "", aux),
                                                  toCOREstallRSName, ExpressionType.AnyExpression));
      registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, stage, ""));
    });
  }
  private HashSet<SCAIEVNode> implementedDHModules = new HashSet<>();
  private void implementDHModule(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    Iterator<NodeInstanceDesc.Key> nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      var nodeKey = nodeKeyIter.next();
      if (!nodeKey.getPurpose().matches(purpose_MARKER_BUILT_DH) || nodeKey.getStage().getKind() != StageKind.Decoupled ||
          !nodeKey.getNode().DH || !nodeKey.getNode().isSpawn() || nodeKey.getAux() != 0)
        continue;
      if (nodeKey.getNode().isAdj()) {
        logger.warn("DecoupledDHStrategy: Cannot build DH module for adj node - " + nodeKey.toString());
        continue;
      }
      // Only instantiate once (e.g. if both cancel_from_user and cancel_from_user_valid are requested)
      if (implementedDHModules.add(nodeKey.getNode())) {
        out.accept(NodeLogicBuilder.fromFunction("DecoupledDHStrategy_DHModule_" + nodeKey.getNode().name,
                                                 (NodeRegistryRO registry, Integer aux) -> {
                                                   NodeLogicBlock ret = new NodeLogicBlock();
                                                   AddDHModule(nodeKey, ret, registry, nodeKey.getNode(), nodeKey.getStage(), aux);
                                                   return ret;
                                                 }));
      }
      nodeKeyIter.remove();
    }
  }

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    this.implementUserCancelSpawn(out, nodeKeys, isLast);
    this.implementDHModule(out, nodeKeys, isLast);
  }
}
