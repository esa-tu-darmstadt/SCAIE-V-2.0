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
import scaiev.frontend.SCAIEVNode.NodeTypeTag;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.decoupled.DecoupledStandardModulesStrategy.FIFOFeature;
import scaiev.ui.SCAIEVConfig;
import scaiev.util.Log2;
import scaiev.util.Verilog;

/**
 * Adds a bypassing FIFO to buffer backpressure from spawn operations.
 * Takes all Purpose.match_WIREDIN_OR_PIPEDIN spawn nodes with isInput and outputs corresponding REGULAR nodes.
 */
public class SpawnOptionalInputFIFOStrategy extends MultiNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  Verilog language;
  BNode bNodes;
  Core core;
  HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  HashMap<String, SCAIEVInstr> allISAXes;
  boolean SETTINGwithInputFIFO;
  SCAIEVConfig cfg;
  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param allISAXes The ISAX descriptions
   * @param SETTINGwithInputFIFO Flag if a FIFO should be created rather than a plain assignment
   * @param cfg The SCAIE-V global config
   */
  public SpawnOptionalInputFIFOStrategy(Verilog language, BNode bNodes, Core core,
                                        HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                                        HashMap<String, SCAIEVInstr> allISAXes, boolean SETTINGwithInputFIFO,
                                        SCAIEVConfig cfg) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.allISAXes = allISAXes;
    this.SETTINGwithInputFIFO = SETTINGwithInputFIFO;
    this.cfg = cfg;
  }

  @Override
  public void setLanguage(Verilog lang) {
    this.language = lang;
  }

  private static SCAIEVNode makeSpawnInputFIFOSubNode(BNode bNodes, SCAIEVNode spawnNode, String subnodeName, int size) {
    SCAIEVNode baseNode = spawnNode.isAdj() ? bNodes.GetSCAIEVNode(spawnNode.nameParentNode) : spawnNode;
    return new SCAIEVNode("SpawnInputFIFO_" + subnodeName + "_" + baseNode.name, size, true);
  }
  /** The node for the input FIFO 'not empty' status. */
  public static SCAIEVNode makeNotEmptyNode(BNode bNodes, SCAIEVNode spawnNode) {
    return makeSpawnInputFIFOSubNode(bNodes, spawnNode, "notEmpty", 1);
  }
  /** The node for the input FIFO 'not full' status. */
  public static SCAIEVNode makeNotFullNode(BNode bNodes, SCAIEVNode spawnNode) {
    return makeSpawnInputFIFOSubNode(bNodes, spawnNode, "notFull", 1);
  }
  /**
   * The node for the input FIFO level of a spawn node.
   * For registry lookups, fifoDepth should be set to 0. The node from the lookup will have the correct {@link SCAIEVNode#size} value.
   */
  public static SCAIEVNode makeLevelNode(BNode bNodes, SCAIEVNode spawnNode, int fifoDepth) {
    int width = (fifoDepth == 0) ? 0 : Log2.clog2(fifoDepth);
    return makeSpawnInputFIFOSubNode(bNodes, spawnNode, "level", width);
  }
  /**
   * A marker node that, if there is a reason the input FIFO has to keep ordering intact,
   * should be registered in the spawn stage with expression value "1" and a unique aux/ISAX.
   */
  public static SCAIEVNode makeMustBeInorderMarkerNode(BNode bNodes, SCAIEVNode spawnNode) {
    return makeSpawnInputFIFOSubNode(bNodes, spawnNode, "mustBeInorderMarker", 1);
  }

  Purpose purpose_markerNEEDSFIFO_spawn = new Purpose("markerNEEDSFIFO_spawn", true, Optional.empty(), List.of());

  // nodes: All nodes that the FIFO should include. All nodes must be adj nodes to a common parent, or be that parent.
  private void LogicToISAXSpawn_OptionalInputFIFO(NodeLogicBlock logicBlock, NodeRegistryRO registry, int aux, List<SCAIEVNode> nodes,
                                                  PipelineStage spawnStage, String ISAX) {
    assert (!nodes.isEmpty());
    if (nodes.isEmpty())
      return;

    // Add a dependency to trigger rebuild of a FIFO whenever something is added to nodes.
    // NOTE: Due to the 'all-or-nothing' Key wildcard lookup limitation, a rebuild will be triggered regardless of ISAX.
    registry.lookupAll(new NodeInstanceDesc.Key(purpose_markerNEEDSFIFO_spawn, nodes.get(0), spawnStage, ISAX), false);

    SCAIEVNode baseNode = nodes.get(0).isAdj() ? bNodes.GetSCAIEVNode(nodes.get(0).nameParentNode) : nodes.get(0);
    if (baseNode.name.isEmpty()) {
      logger.error("Unable to find the parent of node '" + nodes.get(0).name + "'");
      return;
    }

    boolean implementAsFIFO = this.SETTINGwithInputFIFO;

    class InOutDesc {
      String inExpression; // Expression for the value.
      String outDest;      // Destination wire or reg name.
      int bitPos;
      int numBits;
      boolean fifoReadAsValidReq;
    }
    List<InOutDesc> fifoElements = new ArrayList<>();
    Optional<InOutDesc> cancelFifoIO = Optional.empty();
    int totalNumBits = 0;

    for (SCAIEVNode node : nodes) {
      String nodeWireName = new NodeInstanceDesc.Key(node, spawnStage, ISAX).toString(false) + "_frombypassfifo";
      // Match the source node that should either come directly from the ISAX-to-SCAL interface -> Purpose.WIREDIN,
      //  or be refined (e.g. adding RdIValid to validReq for static ISAXes) -> Purpose.REGULAR.
      String inExpressionName = registry.lookupExpressionRequired(
          new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN_NONLATCH, node, spawnStage, ISAX));
      if (implementAsFIFO) {
        logicBlock.declarations += String.format("reg [%d-1:0] %s;\n", node.size, nodeWireName);
        logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR_LATCHING, node, spawnStage, ISAX),
                                                    nodeWireName, ExpressionType.WireName));
        var newIO = new InOutDesc();
        newIO.inExpression = inExpressionName;
        newIO.outDest = nodeWireName;
        newIO.bitPos = -1;
        newIO.fifoReadAsValidReq = (node.getAdj() == AdjacentNode.validReq);
        newIO.numBits = newIO.fifoReadAsValidReq ? 0 : node.size;
        fifoElements.add(newIO);
        totalNumBits += newIO.numBits;
        if (node.getAdj() == AdjacentNode.cancelReq)
          cancelFifoIO = Optional.of(newIO);
      } else {
        logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR_LATCHING, node, spawnStage, ISAX),
                                                    inExpressionName, ExpressionType.AnyExpression));
      }
    }
    {
      // Fill the bitPos field in fifoElements, assigning in reverse order matching the FIFO input logic
      int curBitPos = 0;
      for (int iElem = fifoElements.size() - 1; iElem >= 0; --iElem) {
        var io = fifoElements.get(iElem);
        io.bitPos = curBitPos;
        curBitPos += io.numBits;
      }
    }

    if (implementAsFIFO) {
      SCAIEVNode validReq = bNodes.GetAdjSCAIEVNode(baseNode, AdjacentNode.validReq).get();
      SCAIEVNode validResp = bNodes.GetAdjSCAIEVNode(baseNode, AdjacentNode.validResp).get();
      Optional<SCAIEVNode> cancelReq_opt = bNodes.GetAdjSCAIEVNode(baseNode, AdjacentNode.cancelReq);
      String validReqVal = registry.lookupExpressionRequired(
          new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN_NONLATCH, validReq, spawnStage, ISAX));
      String validReqRegVal = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(Purpose.REGISTERED, validReq, spawnStage, ISAX));
      String validRespVal = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(validResp, spawnStage, ISAX));
      Optional<String> cancelReqVal_opt =
          cancelReq_opt.map(cancelReq
                            -> registry.lookupExpressionRequired(
                                new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN_NONLATCH, cancelReq, spawnStage, ISAX)));

      int fifoDepth = cfg.spawn_input_fifo_depth;

      boolean mustBeInorder = false;
      for (var nodeInst :
           registry.lookupAll(new NodeInstanceDesc.Key(makeMustBeInorderMarkerNode(bNodes, baseNode), spawnStage, ""), false)) {
        if (nodeInst.getExpression().equals("1")) {
          mustBeInorder = true;
          break;
        }
      }
      boolean hasReadahead = mustBeInorder && fifoDepth > 1;

      String fifoNameBase = "INPUTFIFO_" + baseNode.name + "_" + ISAX;
      // Declare Signals and Instantiate Module
      String fifo_in_wireName = fifoNameBase + "_in_s";
      String fifo_out_wireName = fifoNameBase + "_out_s";
      String fifo_write_wireName = fifoNameBase + "_write_s";
      String fifo_writeFrontNotBack_wireName = fifoNameBase + "_writeFrontNotBack_s";
      String fifo_read_wireName = fifoNameBase + "_read_s";
      String fifo_readahead_wireName = fifoNameBase + "_readahead_s";
      String fifo_notEmpty_wireName = fifoNameBase + "_notEmpty_s";
      String fifo_notFull_wireName = fifoNameBase + "_notFull_s";
      String fifo_level_wireName = fifoNameBase + "_level_s";
      String fifo_stallIntoSpawn_wireName = fifoNameBase + "_stallIntoSpawn_s";
      String fifo_stallIntoSpawn_early_wireName = fifoNameBase + "_stallIntoSpawn_early_s";
      logicBlock.declarations += "wire [" + (totalNumBits == 0 ? 1 : totalNumBits) + "-1:0] " + fifo_in_wireName + ";\n"
                                 + "wire [" + (totalNumBits == 0 ? 1 : totalNumBits) + "-1:0] " + fifo_out_wireName + ";\n"
                                 + "wire " + fifo_write_wireName + ";\n" +
                                 (!mustBeInorder ? "logic " + fifo_writeFrontNotBack_wireName + ";\n" : "") + "reg " + fifo_read_wireName +
                                 ";\n" + (hasReadahead ? "logic " + fifo_readahead_wireName + ";\n" : "") + "wire " +
                                 fifo_notEmpty_wireName + ";\n"
                                 + "wire " + fifo_notFull_wireName + ";\n"
                                 + "wire [$clog2(" + fifoDepth + ")-1:0] " + fifo_level_wireName + ";\n"
                                 + "wire " + fifo_stallIntoSpawn_wireName + ";\n"
                                 + "wire " + fifo_stallIntoSpawn_early_wireName + ";\n";
      // Register all wire names for duplicate detection.
      logicBlock.outputs.add(
          new NodeInstanceDesc(new NodeInstanceDesc.Key(new SCAIEVNode("SpawnInputFIFO_in_" + baseNode.name), spawnStage, ISAX),
                               fifo_in_wireName, ExpressionType.WireName));
      logicBlock.outputs.add(
          new NodeInstanceDesc(new NodeInstanceDesc.Key(new SCAIEVNode("SpawnInputFIFO_out_" + baseNode.name), spawnStage, ISAX),
                               fifo_out_wireName, ExpressionType.WireName));
      logicBlock.outputs.add(
          new NodeInstanceDesc(new NodeInstanceDesc.Key(new SCAIEVNode("SpawnInputFIFO_write_" + baseNode.name), spawnStage, ISAX),
                               fifo_write_wireName, ExpressionType.WireName));
      if (!mustBeInorder)
        logicBlock.outputs.add(new NodeInstanceDesc(
            new NodeInstanceDesc.Key(new SCAIEVNode("SpawnInputFIFO_writeFrontNotBack_" + baseNode.name), spawnStage, ISAX),
            fifo_writeFrontNotBack_wireName, ExpressionType.WireName));
      logicBlock.outputs.add(
          new NodeInstanceDesc(new NodeInstanceDesc.Key(new SCAIEVNode("SpawnInputFIFO_read_" + baseNode.name), spawnStage, ISAX),
                               fifo_read_wireName, ExpressionType.WireName));
      if (hasReadahead)
        logicBlock.outputs.add(
            new NodeInstanceDesc(new NodeInstanceDesc.Key(new SCAIEVNode("SpawnInputFIFO_readahead_" + baseNode.name), spawnStage, ISAX),
                                 fifo_readahead_wireName, ExpressionType.WireName));
      logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(makeNotEmptyNode(bNodes, baseNode), spawnStage, ISAX),
                                                  fifo_notEmpty_wireName, ExpressionType.WireName));
      logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(makeNotFullNode(bNodes, baseNode), spawnStage, ISAX),
                                                  fifo_notFull_wireName, ExpressionType.WireName));
      var fifoLevelNode = makeLevelNode(bNodes, baseNode, fifoDepth);
      logicBlock.outputs.add(
          new NodeInstanceDesc(new NodeInstanceDesc.Key(fifoLevelNode, spawnStage, ISAX), fifo_level_wireName, ExpressionType.WireName));

      // Backpressure: Since the ISAX may commit before it leaves the SpawnInputFIFO is empty (decreasing ISAXValidCounter),
      //  dynamically reduce the concurrent ISAX limit by the FIFO level.
      logicBlock.outputs.add(
          new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, SpawnRdIValidStrategy.ISAXValidCounterMax, spawnStage, ISAX, aux),
                               "" + fifoDepth, ExpressionType.AnyExpression));
      NodeInstanceDesc isaxValidCounterInst =
          registry.lookupRequired(new NodeInstanceDesc.Key(SpawnRdIValidStrategy.ISAXValidCounter, spawnStage, ISAX));
      int isaxValidCounterWidth = isaxValidCounterInst.getKey().getNode().size;
      String isaxValidCounterExpr = isaxValidCounterInst.getExpression();
      // Zero-extend fifo_level_wireName to the width of isaxValidCounterExpr.
      String fifoLevelExtendedExpr = (fifoLevelNode.size >= isaxValidCounterWidth
                                          ? fifo_level_wireName
                                          : String.format("{%d'd0,%s}", isaxValidCounterWidth - fifoLevelNode.size, fifo_level_wireName));
      logicBlock.logic += String.format(
          "assign %s = !%s || %s >= (%d'd%d - %s); //Reduce instr limit by FIFO level, as FIFO entries may outlive instr commit\n",
          fifo_stallIntoSpawn_wireName, fifo_notFull_wireName, isaxValidCounterExpr, isaxValidCounterWidth, fifoDepth,
          fifoLevelExtendedExpr);
      logicBlock.logic +=
          String.format("assign %s = (%s + %d'd1) >= (%d'd%d - %s);\n", fifo_stallIntoSpawn_early_wireName, isaxValidCounterExpr,
                        isaxValidCounterWidth, isaxValidCounterWidth, fifoDepth, fifoLevelExtendedExpr);
      // Apply backpressure via WrStallISAXEntry.
      logicBlock.outputs.add(
          new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, SpawnRdIValidStrategy.WrStallISAXEntry, spawnStage, ISAX, aux),
                               fifo_stallIntoSpawn_wireName, ExpressionType.WireName));
      logicBlock.outputs.add(new NodeInstanceDesc(
          new NodeInstanceDesc.Key(Purpose.REGULAR, SpawnRdIValidStrategy.WrStallISAXEntryEarly, spawnStage, ISAX, aux),
          fifo_stallIntoSpawn_early_wireName, ExpressionType.WireName));
      registry.lookupExpressionRequired(new NodeInstanceDesc.Key(SpawnRdIValidStrategy.WrStallISAXEntry, spawnStage, ISAX));

      var requestedForISAXSet = new RequestedForSet(ISAX);
      String FIFOmoduleName = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
          Purpose.HDL_MODULE,
          DecoupledStandardModulesStrategy.makeFIFONode(FIFOFeature.Level,
                                                        !mustBeInorder ? FIFOFeature.WriteFront : FIFOFeature.NotAFeature,
                                                        hasReadahead ? FIFOFeature.Readahead : FIFOFeature.NotAFeature),
          core.GetRootStage(), ""));
      final String tab = language.tab;
      String clearCond = (spawnStage.getKind() == StageKind.Decoupled)
                             ? registry.lookupExpressionRequired(
                                   new NodeInstanceDesc.Key(DecoupledPipeStrategy.PseudoNode_StartSpawnToSpawnPipe_KillAll, spawnStage, ""),
                                   requestedForISAXSet)
                             : "0";
      logicBlock.logic += String.format("%s#(%d,%d) %s_valid_INPUTs_inst (\n", FIFOmoduleName, fifoDepth,
                                        (totalNumBits == 0 ? 1 : totalNumBits), fifoNameBase) +
                          tab + language.clk + ",\n" + tab + language.reset + ",\n" + tab + clearCond + ",\n" + tab + fifo_write_wireName +
                          ",\n" + (!mustBeInorder ? tab + fifo_writeFrontNotBack_wireName + ",\n" : "") + tab + fifo_read_wireName +
                          ",\n" + tab + fifo_in_wireName + ",\n" + tab + fifo_notEmpty_wireName + ",\n" + tab + fifo_notFull_wireName +
                          ",\n" + tab + fifo_level_wireName + ",\n" + (hasReadahead ? tab + fifo_readahead_wireName + ",\n" : "") + tab +
                          fifo_out_wireName + "\n"
                          + ");\n";

      // FIFO write condition
      // validReqVal+" && "+registry.lookupExpressionRequired(new NodeInstanceDesc.Key(Purpose.REGULAR, validReq, spawnStage, ISAX))
      //			String writeCondition = String.format("%s && %s && (!%s || %s)",
      //				validReqVal, validReqRegVal,
      //				validRespVal, fifo_notEmpty_wireName);
      String writeCondition = validReqVal;
      if (cancelReqVal_opt.isPresent()) {
        // writeCondition += String.format(" || %s && %s", cancelReqVal_opt.get(), fifo_notEmpty_wireName);
        writeCondition += String.format(" || %s", cancelReqVal_opt.get());
      }
      logicBlock.logic += String.format("assign %s = %s;\n", fifo_write_wireName, writeCondition);

      // FIFO input data
      if (totalNumBits == 0)
        logicBlock.logic += String.format("assign %s = 1'b0;\n", fifo_in_wireName);
      else {
        logicBlock.logic += String.format(
            "assign %s = {%s};\n", fifo_in_wireName,
            fifoElements.stream().filter(inp -> inp.numBits > 0).map(inp -> inp.inExpression).reduce((a, b) -> a + "," + b).orElse(""));
      }

      // Readahead condition
      if (hasReadahead) {
        logicBlock.logic += String.format("assign %s = %s;\n", fifo_readahead_wireName, validRespVal);
      }

      // FIFO output data and read condition
      String readLogic = "";
      readLogic += "//Default: Pass through the new request\n";
      readLogic += fifoElements.stream().map(io -> String.format("%s = %s;\n", io.outDest, io.inExpression)).reduce("", (a, b) -> a + b);
      readLogic += String.format("%s = 0;\n", fifo_read_wireName);
      if (!mustBeInorder)
        readLogic += String.format("%s = 0;\n", fifo_writeFrontNotBack_wireName);
      readLogic += "\n";

      String mayPushRequestCond = String.format("!%s || %s", validReqRegVal, validRespVal);

      readLogic += String.format("if (%s) begin\n", fifo_notEmpty_wireName);
      readLogic += tab + "//Prefer requests already stored in the FIFO;\n";
      readLogic += tab + "//But if the buffered request (sampled via REGISTERED) is finishing, " + (hasReadahead ? "read ahead or " : "") +
                   "still output the new request.\n";
      if (hasReadahead) {
        // We can implicitly read either from the front of the FIFO or the element after the front (-> readahead FIFO feature).
        readLogic += tab + String.format("if (!%s || %s != %d'd1) begin\n", validRespVal, fifo_level_wireName, fifoLevelNode.size);
      } else {
        // We can only read from the front of the FIFO,
        //  i.e. there is no way to combinationally dequeue and read the request after that from FIFO.
        readLogic += tab + String.format("if (!%s) begin\n", validRespVal);
      }
      //-> If the request is ending and the FIFO has no other element yet, pass through the new request instead.
      for (int iElem = fifoElements.size() - 1; iElem >= 0; --iElem) {
        var io = fifoElements.get(iElem);
        if (io.fifoReadAsValidReq) {
          if (cancelFifoIO.isPresent())
            readLogic += tab + tab + String.format("%s = !%s[%d];\n", io.outDest, fifo_out_wireName, cancelFifoIO.get().bitPos);
          else
            readLogic += tab + tab + String.format("%s = 1;\n", io.outDest);
        } else if (io.numBits > 0) {
          readLogic += tab + tab + String.format("%s = %s[%d+%d-1:%d];\n", io.outDest, fifo_out_wireName, io.bitPos, io.numBits, io.bitPos);
        }
      }
      readLogic += tab + "end\n";
      readLogic += tab + String.format("if (%s && %s != %d'd1) begin\n", validRespVal, fifo_level_wireName, fifoLevelNode.size);
      if (mustBeInorder) {
        // If there is another FIFO element, output it directly; else, pass through the new input.
        //  -> Needs SimpleFIFO support: read at wrap(read_ptr+1)
        //     (maybe a shiftreg over the two next FIFO elements would do)

        if (!hasReadahead) {
          // For now: Simply set the validReq output to 0, so any new input will not be sampled (= stall cycle).
          readLogic +=
              tab + tab + "//There is another FIFO element that needs to be passed on before any new request. Stall to preserve ordering\n";
          for (InOutDesc io : fifoElements)
            if (io.fifoReadAsValidReq) {
              readLogic += tab + tab + String.format("%s = 0;\n", io.outDest);
            }
          if (cancelFifoIO.isPresent())
            readLogic += tab + tab + String.format("%s = 0;\n", cancelFifoIO.get().outDest);
        } else {
          readLogic += tab + tab + "//Readahead already ensures the next request from the FIFO will be used (preserving ordering).\n";
        }
      } else {
        readLogic += tab + tab + "//Still pass through the new request immediately (if present), but write it to the front\n";
        readLogic += tab + tab + String.format("%s = 1;\n", fifo_writeFrontNotBack_wireName);
      }
      readLogic += tab + "end\n";
      String pushingRequestOrCancelCond = mayPushRequestCond;
      if (cancelFifoIO.isPresent()) {
        // When canceling, immediately pop from the FIFO (no need to / must not wait for validResp).
        pushingRequestOrCancelCond += String.format(" || %s", cancelFifoIO.get().outDest);
      }
      readLogic += language.tab + String.format("%s = %s;\n", fifo_read_wireName, pushingRequestOrCancelCond);
      readLogic += "end\n";

      readLogic += String.format("if (!(%s)) begin\n", mayPushRequestCond);
      readLogic += tab + "//Clear validReq if downstream has already received the pending request.\n";
      readLogic += tab + "//validReq should only be set for one cycle per request.\n";
      for (InOutDesc io : fifoElements)
        if (io.fifoReadAsValidReq) {
          readLogic += tab + String.format("%s = 0;\n", io.outDest);
        }
      readLogic += "end\n";

      logicBlock.logic += language.CreateInAlways(false, readLogic);

      //// Compute outputs from FIFO
      // String userOptValid = "";
      // if(ISAXes.get(ISAX).GetFirstNode(node).HasAdjSig(AdjacentNode.validReq))
      //	userOptValid = myLanguage.CreateFamNodeName(validReq, spawnStage, ISAX, false)+" && ";
      // String FIFO_out = myLanguage.CreateLocalNodeName(validReq, spawnStage, ISAX) +" = "+userOptValid
      // +myLanguage.CreateFamNodeName(validReq, spawnStage, ISAX, true)+ShiftmoduleSuffix+"; // Signals rest of logic valid spawn sig\n";
    }
    // logic += "assign "+ myLanguage.CreateLocalNodeName(validReq, spawnStage, ISAX)+" =
    // "+(nodeSched.HasAdjSig(validReq)?myLanguage.CreateFamNodeName(adjOperation, spawnStage, ISAX, false)+" && ":"")+
    // myLanguage.CreateFamNodeName(adjOperation, spawnStage, ISAX,false)+ShiftmoduleSuffix+";\n";
  }

  private HashMap<String, List<SCAIEVNode>> builtToISAXNodeNameSet = new HashMap<>();

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    Iterator<NodeInstanceDesc.Key> keyIter = nodeKeys.iterator();
    while (keyIter.hasNext()) {
      NodeInstanceDesc.Key nodeKey = keyIter.next();
      if (!nodeKey.getPurpose().matches(Purpose.REGULAR_LATCHING) || !nodeKey.getNode().isSpawn() || nodeKey.getISAX().isEmpty() ||
          !(nodeKey.getStage().getKind() == StageKind.Decoupled || nodeKey.getStage().getKind() == StageKind.Core) || nodeKey.getAux() != 0)
        continue;
      SCAIEVNode nodeNonadj = bNodes.GetNonAdjNode(nodeKey.getNode());
      if (nodeNonadj.name.isEmpty()) {
        logger.error("SpawnOptionalInputFIFOStrategy: Not handling node {} with non-existing base", nodeKey.getNode().name);
        continue;
      }
      if (nodeNonadj.tags.contains(NodeTypeTag.supportsPortNodes) && !nodeNonadj.tags.contains(NodeTypeTag.isPortNode)
          && op_stage_instr.keySet().stream()
             .filter(op->op.tags.contains(NodeTypeTag.isPortNode) && op.nameParentNode.equals(nodeNonadj.name))
             .anyMatch(op->op_stage_instr.get(op).getOrDefault(nodeKey.getStage(), new HashSet<>())
                 .contains(nodeKey.getISAX()))
          ) {
        continue;
      }
      //			if (nodeKey.getNode().isAdj()
      //				&&
      //! allISAXes.get(nodeKey.getISAX()).GetFirstNode(bNodes.GetSCAIEVNode(nodeKey.getNode().nameParentNode))
      //				   .HasAdjSig(nodeKey.getNode().getAdj())
      //				&& !op_stage_instr.getOrDefault(nodeKey.getNode(), new HashMap<>()).getOrDefault(nodeKey.getStage(),
      // new HashSet<>()).contains(nodeKey.getISAX())) 				continue;
      if (/*!nodeKey.getNode().DefaultMandatoryAdjSig() && */ nodeKey.getNode().isInput) {
        //-> from ISAX
        // Do not build the same pin twice (using full name, as the ISAX does not care about node families)
        String builderIdentifier =
            String.format("%s_%s_%s", nodeKey.getNode().isAdj() ? nodeKey.getNode().nameParentNode : nodeKey.getNode().name,
                          nodeKey.getISAX(), nodeKey.getStage().getName());
        List<SCAIEVNode> handledNodesByIdentifier =
            builtToISAXNodeNameSet.computeIfAbsent(builderIdentifier, builderIdentifier_ -> new ArrayList<>());
        if (handledNodesByIdentifier.isEmpty()) {
          handledNodesByIdentifier.add(nodeKey.getNode());
          out.accept(NodeLogicBuilder.fromFunction("SpawnOptionalInputFIFOStrategy_" + builderIdentifier, (registry, aux) -> {
            NodeLogicBlock ret = new NodeLogicBlock();
            LogicToISAXSpawn_OptionalInputFIFO(ret, registry, aux, handledNodesByIdentifier, nodeKey.getStage(), nodeKey.getISAX());
            return ret;
          }));
        } else if (!handledNodesByIdentifier.contains(nodeKey.getNode())) {
          handledNodesByIdentifier.add(nodeKey.getNode());
          // Trigger FIFO update.
          out.accept(NodeLogicBuilder.fromFunction(
              "SpawnOptionalInputFIFOStrategy_" + builderIdentifier + "||Marker for " + nodeKey.getNode(), (registry, aux) -> {
                NodeLogicBlock ret = new NodeLogicBlock();
                ret.outputs.add(
                    new NodeInstanceDesc(new NodeInstanceDesc.Key(purpose_markerNEEDSFIFO_spawn, handledNodesByIdentifier.get(0),
                                                                  nodeKey.getStage(), nodeKey.getISAX(), aux),
                                         "1", ExpressionType.AnyExpression));
                return ret;
              }));
        }
        keyIter.remove();
      }
    }
  }
}
