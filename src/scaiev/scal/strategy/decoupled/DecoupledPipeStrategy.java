package scaiev.scal.strategy.decoupled;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.backend.SCALBackendAPI.CustomCoreInterface;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.frontend.SCAL;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.TriggerableNodeLogicBuilder;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.util.Verilog;

/**
 * Strategy that produces pipe utility signals (stall-, flush- related) and default implementations for per-ISAX spawn
 * validReq,validResp,addr
 */
public class DecoupledPipeStrategy extends MultiNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  StrategyBuilders strategyBuilders;
  Verilog language;
  BNode bNodes;
  Core core;
  HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage;
  HashMap<String, SCAIEVInstr> allISAXes;
  List<CustomCoreInterface> spawnRDAddrOverrides;
  /**
   * @param strategyBuilders The StrategyBuilders object to build sub-strategies with
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param spawn_instr_stage The Node-ISAX-Stage mapping providing the precise sub-pipeline stage for spawn operations
   * @param allISAXes The ISAX descriptions
   * @param spawnRDAddrOverrides Custom SCAL<->Core interfaces that specify the destination register address/ID for ISAXes entering a spawn
   *     stage.
   *                             This could be one interface for the Execute stage (for semi-coupled spawn ISAXes)
   *                              and one for the Decoupled stage (for actual decoupled spawn ISAXes).
   *                             By default, the 'rd' field in the instruction encoding is used.
   */
  public DecoupledPipeStrategy(StrategyBuilders strategyBuilders, Verilog language, BNode bNodes, Core core,
                               HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                               HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage,
                               HashMap<String, SCAIEVInstr> allISAXes, List<CustomCoreInterface> spawnRDAddrOverrides) {
    this.strategyBuilders = strategyBuilders;
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.spawn_instr_stage = spawn_instr_stage;
    this.allISAXes = allISAXes;
    this.spawnRDAddrOverrides = spawnRDAddrOverrides;
  }

  @Override
  public void setLanguage(Verilog lang) {
    this.language = lang;
  }

  public static final SCAIEVNode PseudoNode_StartSpawnToSpawnPipe_Flush = new SCAIEVNode("RdStartSpawnToSpawnPipe_Flush");
  public static final SCAIEVNode PseudoNode_StartSpawnToSpawnPipe_StallShiftReg = new SCAIEVNode("RdStartSpawnToSpawnPipe_StallShiftReg");
  public static final SCAIEVNode PseudoNode_StartSpawnToSpawnPipe_StallFrSpawn = new SCAIEVNode("RdStartSpawnToSpawnPipe_StallFrSpawn");
  public static final SCAIEVNode PseudoNode_StartSpawnToSpawnPipe_StallFrCore = new SCAIEVNode("RdStartSpawnToSpawnPipe_StallFrCore");
  public static final SCAIEVNode PseudoNode_StartSpawnToSpawnPipe_KillAll = new SCAIEVNode("RdStartSpawnToSpawnPipe_KillAll");
  public static final SCAIEVNode PseudoNode_StartSpawnToSpawnPipe_Fence = new SCAIEVNode("RdStartSpawnToSpawnPipe_Fence");

  private void CreateExtraPipeSignals(NodeLogicBlock logicBlock, NodeRegistryRO registry, PipelineStage onlyStartSpawnStage,
                                      PipelineStage spawnStage) {
    PipelineFront startSpawnFront = new PipelineFront(onlyStartSpawnStage);
    PipelineFront spawnFront = new PipelineFront(spawnStage);

    RequestedForSet commonRequestedFor = new RequestedForSet();

    String flush = "";
    String stallShiftReg = "";
    String stallFrSpawn = "";
    String stallFrCore = "";

    // Construct stall, flush signals for optional modules. Required by the DH spawn module, shift reg module for ValidReq, FIFO for addr
    if (startSpawnFront.isBefore(spawnStage, false)) {
      List<PipelineStage> stages = this.core.GetRootStage()
                                       .getAllChildren()
                                       .filter(stage -> startSpawnFront.isAroundOrBefore(stage, false) && spawnFront.isAfter(stage, false))
                                       .collect(Collectors.toList());
      int prevStagePos = -1;
      for (PipelineStage flStage : stages) {
        if (!startSpawnFront.contains(flStage)) {
          flush = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, flStage, ""), commonRequestedFor) +
                  (flush.isEmpty() ? "" : ",") + flush;
          flush = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrFlush, flStage, "")) + " || " + flush;
        }
        if (prevStagePos != -1 && flStage.getStagePos() != prevStagePos + 1) {
          logger.error("DecoupledPipeStrategy CreateExtraPipeSignals: Encountered parallel stages, which is not supported.");
        }
        if (!flStage.getContinuous()) {
          logger.error("DecoupledPipeStrategy CreateExtraPipeSignals: Encountered non-continuous stage (" + flStage.getName() +
                       "), which is not supported.");
        }
        prevStagePos = flStage.getStagePos();
        String flStageRdStall =
            registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, flStage, ""), commonRequestedFor);
        String flStageWrStall = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, flStage, ""));
        stallFrSpawn = flStageWrStall + (stallFrSpawn.isEmpty() ? "" : ",") + stallFrSpawn;
        stallShiftReg = flStageRdStall + (stallShiftReg.isEmpty() ? "" : ",") + stallShiftReg;
        stallShiftReg = flStageWrStall + " || " + stallShiftReg;
        stallFrCore = flStageRdStall + " || " + flStageWrStall + (stallFrCore.isEmpty() ? "" : ",") + stallFrCore;
      }
      flush = "{" + flush + "}";
      stallFrSpawn = "{" + stallFrSpawn + "}";
      stallShiftReg = "{" + stallShiftReg + "}";
      stallFrCore = "{" + stallFrCore + "}";
    }

    RequestedForSet flushRequestedFor = new RequestedForSet();
    commonRequestedFor.addAll(flushRequestedFor, true);
    logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(PseudoNode_StartSpawnToSpawnPipe_Flush, spawnStage, ""), flush,
                                                ExpressionType.AnyExpression, flushRequestedFor));
    RequestedForSet stallRequestedFor = new RequestedForSet();
    commonRequestedFor.addAll(stallRequestedFor, true);
    logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(PseudoNode_StartSpawnToSpawnPipe_StallShiftReg, spawnStage, ""),
                                                stallShiftReg, ExpressionType.AnyExpression, stallRequestedFor));
    logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(PseudoNode_StartSpawnToSpawnPipe_StallFrSpawn, spawnStage, ""),
                                                stallFrSpawn, ExpressionType.AnyExpression, stallRequestedFor));
    logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(PseudoNode_StartSpawnToSpawnPipe_StallFrCore, spawnStage, ""),
                                                stallFrCore, ExpressionType.AnyExpression, stallRequestedFor));

    String fenceValidExpr = startSpawnFront.asList()
                                .stream()
                                .map(startSpawnStage
                                     -> registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, startSpawnStage,
                                                                                                   SCAL.PredefInstr.fence.instr.GetName())))
                                .reduce((a, b) -> a + " || " + b)
                                .orElse("1'b0");
    String killValidExpr = startSpawnFront.asList()
                               .stream()
                               .map(startSpawnStage
                                    -> registry.lookupExpressionRequired(
                                        new NodeInstanceDesc.Key(bNodes.RdIValid, startSpawnStage, SCAL.PredefInstr.kill.instr.GetName())))
                               .reduce((a, b) -> a + " || " + b)
                               .orElse("1'b0");

    logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(PseudoNode_StartSpawnToSpawnPipe_Fence, spawnStage, ""),
                                                "(" + fenceValidExpr + ")", ExpressionType.AnyExpression));
    logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(PseudoNode_StartSpawnToSpawnPipe_KillAll, spawnStage, ""),
                                                "(" + killValidExpr + ")", ExpressionType.AnyExpression));
  }
  private HashSet<PipelineStage> implementedStartSpawnToSpawnPipe_for_decoupledStage = new HashSet<>();
  private boolean implementStartSpawnToSpawnPipe(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
    if (!nodeKey.getPurpose().matches(Purpose.REGULAR) || nodeKey.getStage().getKind() != StageKind.Decoupled)
      return false;

    SCAIEVNode node = nodeKey.getNode();
    if ((node.equals(PseudoNode_StartSpawnToSpawnPipe_Flush) || node.equals(PseudoNode_StartSpawnToSpawnPipe_StallShiftReg) ||
         node.equals(PseudoNode_StartSpawnToSpawnPipe_StallFrSpawn) || node.equals(PseudoNode_StartSpawnToSpawnPipe_StallFrCore) ||
         node.equals(PseudoNode_StartSpawnToSpawnPipe_KillAll) || node.equals(PseudoNode_StartSpawnToSpawnPipe_Fence))) {
      if (implementedStartSpawnToSpawnPipe_for_decoupledStage.contains(nodeKey.getStage()))
        return true;

      PipelineFront spawnStageFront = new PipelineFront(nodeKey.getStage());
      List<PipelineStage> startSpawnStages = this.core.GetStartSpawnStages()
                                                 .asList()
                                                 .stream()
                                                 .filter(startSpawnStage -> spawnStageFront.isAfter(startSpawnStage, false))
                                                 .collect(Collectors.toList());
      if (startSpawnStages.isEmpty()) {
        logger.error("DecoupledPipeStrategy: Cannot find the 'start spawn' stage for 'spawn' stage " + nodeKey.getStage().getName());
        return false;
      }
      if (startSpawnStages.size() > 1) {
        logger.error("DecoupledPipeStrategy: Only using one 'start spawn' stage for 'spawn' stage " + nodeKey.getStage().getName());
      }
      PipelineStage startSpawnStage = startSpawnStages.get(0);
      implementedStartSpawnToSpawnPipe_for_decoupledStage.add(nodeKey.getStage());
      out.accept(NodeLogicBuilder.fromFunction("DecoupledPipeStrategy_" + nodeKey.getStage().getName(), registry -> {
        NodeLogicBlock ret = new NodeLogicBlock();
        CreateExtraPipeSignals(ret, registry, startSpawnStage, nodeKey.getStage());
        return ret;
      }));
      return true;
    }
    return false;
  }

  public static List<PipelineStage> getRelevantStartSpawnStages(Core core, PipelineStage spawnStage) {
    List<PipelineStage> relevantStartSpawnStages = new ArrayList<>();
    for (PipelineStage startSpawnStage : core.GetStartSpawnStages().asList()) {
      if (new PipelineFront(startSpawnStage).isAroundOrBefore(spawnStage, false))
        relevantStartSpawnStages.add(startSpawnStage);
    }
    return relevantStartSpawnStages;
  }

  private static class AddrSizeFIFOBuilderDesc {
    public boolean forAddr = false, triggeredForAddr = false; // AdjacentNode.addr
    public boolean forSize = false, triggeredForSize = false; // AdjacentNode.size
    public TriggerableNodeLogicBuilder fifoBuilder = null;
  }
  private HashMap<NodeInstanceDesc.Key, AddrSizeFIFOBuilderDesc> implementedAddrSizeFIFOs = new HashMap<>();

  private boolean implementAddrSizeFIFO(Consumer<NodeLogicBuilder> out, SCAIEVNode spawnNode, AdjacentNode adj, PipelineStage stage,
                                        String isax, PipelineStage spawnSubStage) {
    assert (adj == AdjacentNode.addr || adj == AdjacentNode.size);
    assert (!spawnNode.isAdj());
    assert (spawnNode.isSpawn());

    var spawnNodeKey = new NodeInstanceDesc.Key(spawnNode, stage, isax);
    var fifoBuilderDesc = implementedAddrSizeFIFOs.computeIfAbsent(spawnNodeKey, key_ -> new AddrSizeFIFOBuilderDesc());
    boolean isNew = (fifoBuilderDesc.fifoBuilder == null);
    if (isNew) {
      // Construct the actual FIFO builder.

      List<PipelineStage> relevantStartSpawnStages = getRelevantStartSpawnStages(core, stage);
      if (relevantStartSpawnStages.isEmpty()) {
        logger.error("DecoupledPipeStrategy - Found no matching startSpawnStage for " + stage.getName());
        return false;
      }
      if (relevantStartSpawnStages.size() > 1) {
        logger.warn("DecoupledPipeStrategy - Got several 'start spawn' stages for ISAX '" + isax +
                    "', but can only handle one spawn start at a time");
        //-> FIFO cannot handle multiple concurrent pushes.
      }

      int minSpawnStagePos = relevantStartSpawnStages.stream().map(stage_ -> stage_.getStagePos()).min(Integer::compare).get();
      List<Optional<CustomCoreInterface>> defaultAddrOverrides =
          relevantStartSpawnStages.stream()
              .map(startSpawnStage -> {
                return spawnRDAddrOverrides.stream().filter(override -> override.stage.equals(startSpawnStage)).findAny();
              })
              .toList();

      var addrRequestedFor = new RequestedForSet(isax);
      var sizeRequestedFor = new RequestedForSet(isax);
      var commonRequestedFor = new RequestedForSet();
      commonRequestedFor.addAll(addrRequestedFor, true);
      commonRequestedFor.addAll(sizeRequestedFor, true);

      var builder = NodeLogicBuilder.fromFunction("DecoupledPipeStrategy_AddrSizeFIFO_" + spawnNodeKey.toString(false), (registry, aux) -> {
        NodeLogicBlock ret = new NodeLogicBlock();

        int fifoEntryW = 0;

        Optional<SCAIEVNode> addrNode_opt = bNodes.GetAdjSCAIEVNode(spawnNode, AdjacentNode.addr);
        if (fifoBuilderDesc.forAddr)
          assert (addrNode_opt.isPresent());

        if (fifoBuilderDesc.forAddr &&
            registry.lookupOptionalUnique(new NodeInstanceDesc.Key(Purpose.WIREDIN, addrNode_opt.get(), stage, isax)).isPresent()) {
          // If the ISAX already provides this signal, there is no need to generate it.
          fifoBuilderDesc.forAddr = false;
        }
        if (fifoBuilderDesc.forAddr && addrNode_opt.get().validBy != AdjacentNode.validReq) {
          fifoBuilderDesc.forAddr = false;
          logger.error("Node " + addrNode_opt.get().name + " is set to sample by " + addrNode_opt.get().validBy.name() +
                       ", expected validReq");
        }
        String[] addrReadSigs = (fifoBuilderDesc.forAddr ? new String[relevantStartSpawnStages.size()] : null);
        String addrRange = "";
        int addrW = 0;
        if (fifoBuilderDesc.forAddr) {
          addrW = 0;
          if (bNodes.IsUserBNode(spawnNode) && addrNode_opt.isPresent()) {
            SCAIEVNode nonspawnAddrNode = bNodes.GetEquivalentNonspawnNode(addrNode_opt.get()).get();
            // The custom register implementation has to provide an addr node with the default value.
            for (int i = 0; i < relevantStartSpawnStages.size(); ++i)
              addrReadSigs[i] = registry.lookupExpressionRequired(
                  new NodeInstanceDesc.Key(nonspawnAddrNode, relevantStartSpawnStages.get(i), ""), addrRequestedFor);
            addrW = addrNode_opt.get().size;
          } else if (addrNode_opt.isPresent() && !addrNode_opt.get().noInterfToISAX) {
            // This is (mostly) intended for Mem.
            // The core has to provide a rdAddr node with the default value.
            Optional<SCAIEVNode> fromCoreAddrNode_opt = bNodes.GetAdjSCAIEVNode(spawnNode, AdjacentNode.defaultAddr);
            if (!fromCoreAddrNode_opt.isPresent()) {
              logger.error("Cannot find default address node for " + addrNode_opt.get().name);
              fifoBuilderDesc.forAddr = false;
            } else {
              for (int i = 0; i < relevantStartSpawnStages.size(); ++i) {
                addrReadSigs[i] = registry.lookupExpressionRequired(
                    new NodeInstanceDesc.Key(fromCoreAddrNode_opt.get(), relevantStartSpawnStages.get(i), ""), addrRequestedFor);
              }
              addrW = addrNode_opt.get().size;
            }
          } else if (spawnNode.equals(bNodes.WrRD_spawn)) {
            addrW = defaultAddrOverrides.get(0).isPresent() ? defaultAddrOverrides.get(0).get().size : addrNode_opt.get().size;
            boolean foundDifferent = false;
            for (int i = 0; i < relevantStartSpawnStages.size(); ++i) {
              int curDataW;
              if (defaultAddrOverrides.get(i).isPresent()) {
                addrReadSigs[i] = registry.lookupExpressionRequired(defaultAddrOverrides.get(i).get().makeKey(Purpose.WIREDIN));
                // addrReadSigs[i] = defaultAddrOverrides.get(i).get().getSignalName(language, false);
                curDataW = defaultAddrOverrides.get(i).get().size;
              } else {
                addrReadSigs[i] = registry.lookupExpressionRequired(
                                      new NodeInstanceDesc.Key(bNodes.RdInstr, relevantStartSpawnStages.get(i), ""), addrRequestedFor) +
                                  "[11:7]";
                curDataW = addrNode_opt.get().size;
              }
              if (addrW != curDataW)
                foundDifferent = true; // For logging
              if (addrW < curDataW)
                addrW = curDataW;
            }
            if (foundDifferent)
              logger.warn("Found different default address widths for node " + spawnNode.name +
                          " across start spawn stages: " + relevantStartSpawnStages.stream().map(stage_ -> stage_.getName()).toList());
          }
          if (addrW == 0) {
            if (fifoBuilderDesc.forAddr)
              logger.error("Cannot build default address for " + spawnNode.name);
            fifoBuilderDesc.forAddr = false;
          } else {
            addrRange = String.format("[%d-1:%d]", fifoEntryW + addrW, fifoEntryW);
            fifoEntryW += addrW;
          }
        }

        Optional<SCAIEVNode> sizeNode_opt = bNodes.GetAdjSCAIEVNode(spawnNode, AdjacentNode.size);
        if (fifoBuilderDesc.forSize)
          assert (sizeNode_opt.isPresent());
        if (fifoBuilderDesc.forSize &&
            registry.lookupOptionalUnique(new NodeInstanceDesc.Key(Purpose.WIREDIN, sizeNode_opt.get(), stage, isax)).isPresent()) {
          // If the ISAX already provides this signal, there is no need to generate it.
          fifoBuilderDesc.forSize = false;
        }
        if (fifoBuilderDesc.forSize && !spawnNode.equals(bNodes.WrMem_spawn) && !spawnNode.equals(bNodes.RdMem_spawn)) {
          fifoBuilderDesc.forSize = false;
          logger.error("Cannot provide a default value for " + sizeNode_opt.get().name);
        }
        if (fifoBuilderDesc.forSize && sizeNode_opt.get().validBy != AdjacentNode.validReq) {
          fifoBuilderDesc.forSize = false;
          logger.error("Node " + sizeNode_opt.get().name + " is set to sample by " + sizeNode_opt.get().validBy.name() +
                       ", expected validReq");
        }
        String[] sizeReadSigs = (fifoBuilderDesc.forSize ? new String[relevantStartSpawnStages.size()] : null);
        String sizeRange = "";
        int sizeW = 0;
        if (fifoBuilderDesc.forSize) {
          sizeW = sizeNode_opt.get().size;

          for (int i = 0; i < relevantStartSpawnStages.size(); ++i) {
            // RdInstr funct3
            sizeReadSigs[i] = registry.lookupExpressionRequired(
                                  new NodeInstanceDesc.Key(bNodes.RdInstr, relevantStartSpawnStages.get(i), ""), sizeRequestedFor) +
                              "[14:12]";
          }

          sizeRange = String.format("[%d-1:%d]", fifoEntryW + sizeW, fifoEntryW);
          fifoEntryW += sizeW;
        }

        if (fifoEntryW == 0) {
          // No need to create a FIFO.
          // Return now, assuming we don't need zero-width outputs from this builder.
          return ret;
        }

        Function<Integer, String> makeWriteDataExpr = iStartSpawnStage -> {
          String writeExpr = "";
          if (fifoBuilderDesc.forAddr)
            writeExpr = addrReadSigs[iStartSpawnStage];
          if (fifoBuilderDesc.forSize)
            writeExpr = sizeReadSigs[iStartSpawnStage] + (writeExpr.isEmpty() ? "" : ", ") + writeExpr;
          if ((fifoBuilderDesc.forAddr ? 1 : 0) + (fifoBuilderDesc.forSize ? 1 : 0) > 1)
            writeExpr = "{" + writeExpr + "}";
          return writeExpr;
        };

        // Build a FIFO for the addr signal from 'start spawn' to the destination stage.

        String FIFOmoduleName = registry.lookupExpressionRequired(
            new NodeInstanceDesc.Key(Purpose.HDL_MODULE, DecoupledStandardModulesStrategy.makeFIFONode(), core.GetRootStage(), ""));
        // TODO: Make configurable
        int fifoDepth = stage.getStagePos() - minSpawnStagePos + 1;
        if (spawnSubStage.getKind() == StageKind.Sub)
          fifoDepth += spawnSubStage.getStagePos();
        String fifoWriteCondExpr = "1'b0";
        String fifoWriteDataExpr = makeWriteDataExpr.apply(0);
        String[] fifoWriteStageStallConds = new String[relevantStartSpawnStages.size()];
        fifoWriteStageStallConds[0] = "";
        for (int i = 0; i < relevantStartSpawnStages.size(); ++i) {
          PipelineStage startSpawnStage = relevantStartSpawnStages.get(i);
          // Is the ISAX in this 'start spawn' stage?
          String curStartSpawnValid =
              "(" +
              registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, startSpawnStage, isax), commonRequestedFor) +
              " && !" +
              registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, startSpawnStage, ""), commonRequestedFor) +
              " && !" +
              registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, startSpawnStage, ""),
                                                commonRequestedFor) // WrStall is not generally included in RdStall
              + ")";
          fifoWriteCondExpr += " || " + curStartSpawnValid;
          if (i + 1 < relevantStartSpawnStages.size()) {
            // Initialize fifoWriteStageStallConds[i]
            fifoWriteStageStallConds[i + 1] = curStartSpawnValid + " && (";
          }
          if (i > 0) {
            // Select from where to add the address to the FIFO.
            fifoWriteDataExpr = curStartSpawnValid + " ? " + makeWriteDataExpr.apply(i) + " : " + fifoWriteDataExpr;
            // Add the current 'start spawn' condition as a stall condition to all previous fifoWriteStallConds.
            for (int i_stallcond = i; i_stallcond > 0; --i_stallcond) {
              fifoWriteStageStallConds[i_stallcond] +=
                  (fifoWriteStageStallConds[i_stallcond].endsWith("(") ? "" : " || ") + curStartSpawnValid;
            }
          }
        }

        String fifoReadCondExpr =
            registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrCommit_spawn_validReq, stage, isax), commonRequestedFor);
        //    			String fifoReadCondExpr = registry.lookupExpressionRequired(
        //    				new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(spawnNode, AdjacentNode.validReq).get(), stage,
        //    isax), 				commonRequestedFor
        //    			);

        String fifoName = "addrsizeFIFO_" + spawnNode.name + "_" + isax + "_" + stage.getName();
        String outValidWire = "unused_" + fifoName + "_not_empty";
        String outNotFullWire = fifoName + "_not_full";
        String outDataWire = fifoName + "_out";
        String clearCond = (stage.getKind() == StageKind.Decoupled)
                               ? registry.lookupExpressionRequired(
                                     new NodeInstanceDesc.Key(DecoupledPipeStrategy.PseudoNode_StartSpawnToSpawnPipe_KillAll, stage, ""),
                                     commonRequestedFor)
                               : "0";
        ret.declarations += String.format("wire %s;\n", outValidWire);
        ret.declarations += String.format("wire %s;\n", outNotFullWire);
        ret.declarations += String.format("wire [%d-1:0] %s;\n", fifoEntryW, outDataWire);
        ret.logic += "\n" + FIFOmoduleName + " #( " + fifoDepth + ", " + fifoEntryW + " ) " + fifoName + "_inst (\n" + language.tab +
                     language.clk + ",\n" + language.tab + language.reset + ",\n" + language.tab + clearCond + ",\n" + language.tab +
                     fifoWriteCondExpr + ",\n"                  // write fifo
                     + language.tab + fifoReadCondExpr + ",\n"  // read fifo
                     + language.tab + fifoWriteDataExpr + ",\n" // write data
                     + language.tab + outValidWire + ",\n" + language.tab + outNotFullWire + ",\n" + language.tab + outDataWire +
                     "\n" // read data . No family node name (for Mem we need full name as on interf to ISAX)
                     + ");\n";
        if (fifoBuilderDesc.forAddr) {
          String addrFromFIFOWire = language.CreateBasicNodeName(addrNode_opt.get(), stage, isax, false) + "_fromfifo";
          ret.declarations += String.format("wire [%d-1:0] %s;\n", addrW, addrFromFIFOWire);
          ret.logic += String.format("assign %s = %s%s;\n", addrFromFIFOWire, outDataWire, addrRange);
          ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, addrNode_opt.get(), stage, isax),
                                               addrFromFIFOWire, ExpressionType.WireName, addrRequestedFor));
        }
        if (fifoBuilderDesc.forSize) {
          String sizeFromFIFOWire = language.CreateBasicNodeName(sizeNode_opt.get(), stage, isax, false) + "_fromfifo";
          ret.declarations += String.format("wire [%d-1:0] %s;\n", sizeW, sizeFromFIFOWire);
          ret.logic += String.format("assign %s = %s%s;\n", sizeFromFIFOWire, outDataWire, sizeRange);
          ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, sizeNode_opt.get(), stage, isax),
                                               sizeFromFIFOWire, ExpressionType.WireName, sizeRequestedFor));
        }

        for (int i = 0; i < fifoWriteStageStallConds.length; ++i) {
          // Stall the 'start spawn' stages that try to start the same spawn ISAX as the selected stage.
          if (!fifoWriteStageStallConds[i].isEmpty())
            fifoWriteStageStallConds[i] += ")";
          PipelineStage startSpawnStage = relevantStartSpawnStages.get(i);
          fifoWriteStageStallConds[i] +=
              (fifoWriteStageStallConds[i].isEmpty() ? "" : " || ") +
              String.format(
                  "%s && !%s",
                  registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, startSpawnStage, isax), commonRequestedFor),
                  outNotFullWire);
          String stallCondWire = String.format("%s_stallStart_%s_s", fifoName, relevantStartSpawnStages.get(i).getName());
          ret.declarations += String.format("wire %s;\n", stallCondWire);
          ret.logic += String.format("assign %s = %s;\n", stallCondWire, fifoWriteStageStallConds[i]);
          ret.outputs.add(
              new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, relevantStartSpawnStages.get(i), "", aux),
                                   stallCondWire, ExpressionType.WireName, commonRequestedFor));
          registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, relevantStartSpawnStages.get(i), ""));
        }
        return ret;
      });
      // Make the builder triggerable.
      fifoBuilderDesc.fifoBuilder = TriggerableNodeLogicBuilder.makeWrapper(builder, spawnNodeKey);
      // Register the builder.
      out.accept(fifoBuilderDesc.fifoBuilder);
    }
    // Configure the FIFO builder with observed adj nodes.
    switch (adj) {
    case addr:
      fifoBuilderDesc.forAddr = true;
      if (!fifoBuilderDesc.triggeredForAddr) {
        fifoBuilderDesc.triggeredForAddr = true;
        fifoBuilderDesc.fifoBuilder.trigger(isNew ? null : out);
      }
      break;
    case size:
      fifoBuilderDesc.forSize = true;
      if (!fifoBuilderDesc.triggeredForSize) {
        fifoBuilderDesc.triggeredForSize = true;
        fifoBuilderDesc.fifoBuilder.trigger(isNew ? null : out);
      }
      break;
    default:
      assert (false);
    }
    return true;
  }

  private static class SpawnNodeReqSettings {
    public boolean implementRegular = false;
    public TriggerableNodeLogicBuilder triggerable = null;
  }
  private HashMap<NodeInstanceDesc.Key, SpawnNodeReqSettings> alreadyImplementedValidReq = new HashMap<>();
  private HashSet<NodeInstanceDesc.Key> alreadyImplementedOtherReq = new HashSet<>();

  /**
   * Generate rd addresses and validReq not given by the ISAX,
   *  lowering it down to sub-pipeline nodes, which SCAL will then generate.
   */
  private boolean implementSpawnNodeRequest(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
    if (!(nodeKey.getPurpose().matches(Purpose.WIREDIN_FALLBACK) || nodeKey.getPurpose().matches(Purpose.REGULAR)) ||
        !nodeKey.getNode().isSpawn() || nodeKey.getISAX().isEmpty() || nodeKey.getStage().getKind() == StageKind.Sub ||
        nodeKey.getStage().getChildren().isEmpty() || nodeKey.getAux() != 0)
      return false;
    SCAIEVNode spawnNode = bNodes.GetNonAdjNode(nodeKey.getNode());
    assert (spawnNode.isSpawn());

    if (spawnNode.name.isEmpty() || !spawn_instr_stage.containsKey(spawnNode) ||
        !spawn_instr_stage.get(spawnNode).containsKey(nodeKey.getISAX()) ||
        !new PipelineFront(nodeKey.getStage()).isAround(spawn_instr_stage.get(spawnNode).get(nodeKey.getISAX())))
      return false;

    PipelineStage spawnSubStage = spawn_instr_stage.get(spawnNode).get(nodeKey.getISAX());
    final String builderName = "DecoupledPipeStrategy_GenerateSpawnNodeRequest_" + nodeKey.toString();
    RequestedForSet requestedFor;

    switch (nodeKey.getNode().getAdj()) {
    case addr:
    case size:
      if (!nodeKey.getPurpose().matches(Purpose.WIREDIN_FALLBACK))
        return false;
      // For WrRD_spawn, AdjacentNode.addr is the register address;
      //  for (Rd|Wr)Mem_spawn, AdjacentNode.addr is the memory address.
      return implementAddrSizeFIFO(out, spawnNode, nodeKey.getNode().getAdj(), nodeKey.getStage(), nodeKey.getISAX(), spawnSubStage);
    case validReq:
      // the generated WIREDIN_FALLBACK takes RdIValid from the sub-stage;
      // the generated REGULAR combines RdIValid from the sub-stage with the WIREDIN value from the ISAX
      //  -> the ISAX is allowed to just set validReq=1, since SCAL has to check RdIValid
      // This only applies to non-dynamic ISAXes (or non-dynamic portions of a partially dynamic one)
      boolean canImplement = allISAXes.containsKey(nodeKey.getISAX()) &&
                             (!allISAXes.get(nodeKey.getISAX()).GetRunsAsDynamic() || !spawnSubStage.getNext().isEmpty());
      if (!canImplement)
        return false;

      var triggerKey = new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
      SpawnNodeReqSettings settings = alreadyImplementedValidReq.computeIfAbsent(triggerKey, keyKey_ -> new SpawnNodeReqSettings());

      boolean implementedRegular = settings.implementRegular;
      settings.implementRegular = settings.implementRegular || nodeKey.getPurpose().matches(Purpose.REGULAR);
      if (settings.triggerable != null) {
        if (!implementedRegular && settings.implementRegular)
          settings.triggerable.trigger(out); // Ensure the builder will get re-run to actually generate the REGULAR node.
        return true;
      }
      // NOTE: Existing SCAIE-V allows non-dynamic decoupled ISAXes to provide 'validReq',
      //       such that !validReq while an operation is scheduled would skip that operation (?).
      //       (is that only meant for Mem?)
      // semantics for non-dynamic decoupled   (need to decide the semantics)

      requestedFor = new RequestedForSet(nodeKey.getISAX());
      // Generate from RdIValid in spawnSubStage
      settings.triggerable = TriggerableNodeLogicBuilder.fromFunction(builderName, triggerKey, registry -> {
        NodeLogicBlock ret = new NodeLogicBlock();
        // TODO: Behavior - validReq WIREDIN (i.e., from ISAX) may be present for static decoupled
        //       However, the intention is that if it is set to 0 while scheduled, the operation should be considered cancelled;
        //                Otherwise, it should be set to 1 if scheduled. (It may be set to 1 while not scheduled, which should be ignored)
        //       -> The better approach may be to have a 'cancel' node, which would be more in line with what dynamic decoupled would
        //       require This also needs consideration for Register Renaming: For cancellation, need to copy the previous logical register
        //       value into the new physical register.
        var wiredinValidreq_opt = registry.lookupOptionalUnique(
            new NodeInstanceDesc.Key(Purpose.WIREDIN, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()));

        String isValid = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, spawnSubStage, nodeKey.getISAX()));
        String isStalling = "";
        for (NodeInstanceDesc stallInstance :
             registry.lookupAll(new NodeInstanceDesc.Key(bNodes.WrStall, spawnSubStage, ""), false, requestedFor)) {
          isStalling += (isStalling.isEmpty() ? "" : " || ") + stallInstance.getExpression();
        }
        String validReqSubpipeWireName = nodeKey.toString(false) + "_fromsubpipeline";
        ret.declarations += String.format("wire %s;\n", validReqSubpipeWireName);
        ret.logic +=
            String.format("assign %s = %s && !(%s);\n", validReqSubpipeWireName, isValid, isStalling.isEmpty() ? "1'b0" : isStalling);
        ret.outputs.add(new NodeInstanceDesc(
            new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()),
            validReqSubpipeWireName, ExpressionType.WireName, requestedFor));
        if (wiredinValidreq_opt.isPresent() && settings.implementRegular) {
          // Combine the incoming validReq with RdIValid.
          String validReqMainWireName = nodeKey.toString(false) + "_s";
          ret.declarations += String.format("wire %s;\n", validReqMainWireName);
          ret.logic += String.format("assign %s = %s && %s;\n", validReqMainWireName, validReqSubpipeWireName,
                                     wiredinValidreq_opt.get().getExpression());
          ret.outputs.add(
              new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()),
                                   validReqMainWireName, ExpressionType.WireName, requestedFor));
        }
        return ret;
      });
      out.accept(settings.triggerable);
      return true;
    case validData:
    case addrReq:
      if (!nodeKey.getPurpose().matches(Purpose.WIREDIN_FALLBACK))
        return false;
      if (!alreadyImplementedOtherReq.add(new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX())))
        return true;
      requestedFor = new RequestedForSet(nodeKey.getISAX());
      // If the ISAX does not provide validData/addrReq, assume it to be equal to validReq.
      out.accept(NodeLogicBuilder.fromFunction(builderName, registry -> {
        NodeLogicBlock ret = new NodeLogicBlock();
        if (registry
                .lookupOptionalUnique(new NodeInstanceDesc.Key(Purpose.WIREDIN, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()))
                .isPresent()) {
          // If the ISAX already provides this signal, there is no need to generate it.
          return ret;
        }

        ret.outputs.add(new NodeInstanceDesc(
            new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()),
            // Request the neighboring validReq
            registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
                bNodes.GetAdjSCAIEVNode(bNodes.GetSCAIEVNode(nodeKey.getNode().nameParentNode), AdjacentNode.validReq).get(),
                nodeKey.getStage(), nodeKey.getISAX())),
            ExpressionType.AnyExpression, requestedFor));
        return ret;
      }));
      return true;
    case none:
      // Missing main node.
      // This may resolve itself over time (e.g. interface pin generation is already requested but not complete yet).
      break;
    default:
      break;
    }
    // if (spawn_instr_stage.containsKey(nodeKey.getNode()))
    // if (nodeKey.get)
    return false;
  }

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    var nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      var nodeKey = nodeKeyIter.next();
      if (implementStartSpawnToSpawnPipe(out, nodeKey) || implementSpawnNodeRequest(out, nodeKey)) {
        nodeKeyIter.remove();
      }
    }
  }
}
