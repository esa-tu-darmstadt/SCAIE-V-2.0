package scaiev;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.yaml.snakeyaml.Yaml;
import scaiev.backend.BNode;
import scaiev.backend.CVA5;
import scaiev.backend.CVA6;
import scaiev.backend.CoreBackend;
import scaiev.backend.Orca;
import scaiev.backend.Piccolo;
import scaiev.backend.PicoRV32;
import scaiev.backend.VexRiscv;
import scaiev.coreconstr.Core;
import scaiev.coreconstr.Core.CoreTag;
import scaiev.coreconstr.CoreDatab;
import scaiev.coreconstr.CoreNode;
import scaiev.drc.DRC;
import scaiev.frontend.FNode;
import scaiev.frontend.FrontendNodeException;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVInstr.InstrTag;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;
import scaiev.frontend.SCAL;
import scaiev.frontend.Scheduled;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.pipeline.ScheduleFront;
import scaiev.scal.NodeInstanceDesc;
import scaiev.ui.SCAIEVConfig;

public class SCAIEV {

  private CoreDatab coreDatab;                                                        // database with supported cores
  private HashMap<String, SCAIEVInstr> instrSet = new HashMap<String, SCAIEVInstr>(); // database of requested Instructions
  private HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr =
      new HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>>();
  private String extensionName = "DEMO";
  private HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage = new HashMap<>();
  private HashMap<String, Integer> earliest_useroperation = new HashMap<String, Integer>();
  private SCAIEVConfig cfg;

  public BNode BNodes = new BNode();
  public FNode FNodes = BNodes;

  // SCAIE-V Adaptive Layer
  private SCAL scal = new SCAL();

  // logging
  protected static final Logger logger = LogManager.getLogger();

  private boolean errLevelHigh = true;

  public SCAIEV() {
    logger.debug("SHIM. Instantiated shim layer. Supported nodes are: " + FNodes.toString());
    this.coreDatab = new CoreDatab();
    coreDatab.ReadAvailCores("./Cores");
  }

  public void SetErrLevel(boolean errLevelHigh) { this.errLevelHigh = errLevelHigh; }
  public SCAIEVInstr addInstr(String name, String encodingF7, String encodingF3, String encodingOp, String instrType) {
    SCAIEVInstr newISAX = new SCAIEVInstr(name, encodingF7, encodingF3, encodingOp, instrType);
    instrSet.put(name, newISAX);
    return newISAX;
  }
  public Iterable<SCAIEVInstr> getInstructions() { return new ArrayList<>(instrSet.values()); }

  public SCAIEVInstr addInstr(String name) {
    SCAIEVInstr newISAX = new SCAIEVInstr(name);
    instrSet.put(name, newISAX);
    return newISAX;
  }

  public void setSCAL(SCAIEVConfig cfg) {
    scal.SetSCAL(cfg);
    this.cfg = cfg;
  }

  void FixUserNodeSchedule() {
    // Make sure the (Rd|Wr)<reg>_addr node is present and correct for all custom registers.
    for (SCAIEVNode fnode : FNodes.GetAllFrontendNodes()) {
      if (!FNodes.IsUserFNode(fnode))
        continue;
      assert (!fnode.isAdj()); // FNodes shouldn't have adjacent nodes.

      var fnode_addr_opt = BNodes.GetAdjSCAIEVNode(fnode, AdjacentNode.addr);
      if (fnode_addr_opt.isEmpty()) {
        logger.error("Internal error: Custom register node {} has no addr adjacent node", fnode.name);
        continue;
      }
      if (!fnode.name.startsWith("Rd"))
        continue;

      SCAIEVNode fnodeRd = fnode;
      SCAIEVNode fnodeRd_addr = fnode_addr_opt.get();
      SCAIEVNode fnodeWr = BNodes.GetSCAIEVNode(BNodes.GetNameWrNode(fnode));
      SCAIEVNode fnodeWr_addr = BNodes.GetAdjSCAIEVNode(fnodeWr, AdjacentNode.addr).orElseThrow();

      // Get the minimum of all connected earliest_operation entries as the 'issue' stage number.
      var earliestStream = Stream.ofNullable(earliest_useroperation.get(fnodeRd.name));
      earliestStream = Stream.concat(earliestStream, Stream.ofNullable(earliest_useroperation.get(fnodeRd_addr.name)));
      earliestStream = Stream.concat(earliestStream, Stream.ofNullable(earliest_useroperation.get(fnodeWr.name)));
      earliestStream = Stream.concat(earliestStream, Stream.ofNullable(earliest_useroperation.get(fnodeWr_addr.name)));
      var earliestRdwr_opt = earliestStream.min(Integer::compare);
      if (earliestRdwr_opt.isEmpty())
        continue; // Nobody uses this register
      int earliestRdwr = earliestRdwr_opt.get();

      // Correct earliest_operation for RdReg, RdReg_addr, WrReg, WrReg_addr.
      earliest_useroperation.put(fnodeRd.name, earliestRdwr);
      earliest_useroperation.put(fnodeWr.name, earliestRdwr);
      earliest_useroperation.put(fnodeRd_addr.name, earliestRdwr);
      earliest_useroperation.put(fnodeWr_addr.name, earliestRdwr);

      // For each instruction, add missing _addr nodes or update the start cycle to match the global 'earliest'.
      for (SCAIEVInstr instr : getInstructions()) {
        record _util(SCAIEVInstr instr, SCAIEVNode fnode, SCAIEVNode fnode_addr, int earliest) {
          void apply() {
            if (instr.HasNode(fnode)) {
              var schedBases = instr.GetSchedNodes().get(fnode);
              if (!instr.HasNode(fnode_addr)) {
                var addrSched = instr.PutSchedNode(fnode_addr, earliest, new HashSet<>());
              }
              instr.GetSchedWithIterator(fnode_addr, sched -> true).forEachRemaining(sched -> {
                if (sched.GetStartCycle() > earliest && (sched.HasAdjSig(AdjacentNode.addrReq) || fnode.elements > 1))
                  logger.error("Instruction {}: Provides {} in stage {} although it is required in stage {}; Overriding to required stage.",
                               instr, fnode_addr.name, sched.GetStartCycle(), earliest);
                if (sched.GetStartCycle() > earliest)
                  sched.UpdateStartCycle(earliest);
                //Transfer tags that all fnode Scheduled-s have in common to the addr node.
                schedBases.get(0).GetTags().stream()
                    .filter(tag->schedBases.stream().skip(1).allMatch(otherSched->otherSched.GetTags().contains(tag)))
                    .forEach(tag->sched.AddTag(tag));
              });
            }
          }
        };
        new _util(instr, fnodeRd, fnodeRd_addr, earliestRdwr).apply();
        new _util(instr, fnodeWr, fnodeWr_addr, earliestRdwr).apply();
      }
    }
  }

  public boolean Generate(String coreName, String outPath) throws FrontendNodeException {
    boolean success = true;

    // Select Core
    Core core = coreDatab.GetCore(coreName);
    if (core == null) {
      logger.error("Cannot find the core description. If the file is present, check for CoreDatab errors in the log.");
      return false;
    }
    BNodes.updateBitness(core.getTags().contains(CoreTag.RV64) ? 64 : 32);

    // For all user nodes, make sure the _addr adjacent nodes are all present and earliest_useroperation is consistent across Read,Write and
    // adjacents.
    FixUserNodeSchedule();

    AddCommitStagesToNodes(core); // update FNodes based on core datasheet (their commit stages, only for relevant nodes)

    // Create HashMap with <operations, <stages,instructions>>.
    CreateOpStageInstr(core);

    // Print generated hashMap as Info for user
    OpStageInstrToString();

    AddUserNodesToCore(core);

    scal.BNode = BNodes;
    // Check errors
    DRC drc = new DRC(instrSet, op_stage_instr, core, BNodes);
    drc.SetErrLevel(errLevelHigh);
    for (SCAIEVInstr instr : instrSet.values())
      drc.CheckEncoding(instr);
    drc.CheckSchedErr();
    drc.CheckEncPresent();
    drc.CheckEncodingOverlap();
    if (drc.HasFatalError()) {
      logger.fatal("Exiting due to DRC failure.");
      return false;
    }

    // Get metadata from core required by SCAL
    Optional<CoreBackend> coreInstanceOpt = Optional.empty();

    if (coreName.startsWith("VexRiscv")) {
      coreInstanceOpt = Optional.of(new VexRiscv(core));
    } else if (coreName.equals("Piccolo")) {
      coreInstanceOpt = Optional.of(new Piccolo());
    } else if (coreName.equals("ORCA")) {
      coreInstanceOpt = Optional.of(new Orca());
    } else if (coreName.equals("PicoRV32")) {
      coreInstanceOpt = Optional.of(new PicoRV32());
    } else if (coreName.equals("CVA5")) {
      coreInstanceOpt = Optional.of(new CVA5());
    } else if (coreName.equals("CVA6") || coreName.equals("CVA6_64")) {
      coreInstanceOpt = Optional.of(new CVA6());
    }

    // Generate Interface
    // First generate common logic
    logger.debug("INFO: spawn operations with actual stage numbers: " +
                 spawn_instr_stage.entrySet()
                     .stream()
                     .map(entrySpInSt -> {
                       return Map.entry(entrySpInSt.getKey(),
                                        entrySpInSt.getValue()
                                            .entrySet()
                                            .stream()
                                            .map(entryInSt -> Map.entry(entryInSt.getKey(), entryInSt.getValue().getName()))
                                            .toList());
                     })
                     .toList());
    logger.debug(
        "INFO: all operations (spawn stages: " + (core.GetSpawnStages().asList().stream().map(stage -> stage.getName())).toList() + "): " +
        op_stage_instr.entrySet()
            .stream()
            .map(entryOpStIn -> {
              return Map.entry(entryOpStIn.getKey(), entryOpStIn.getValue()
                                                         .entrySet()
                                                         .stream()
                                                         .map(entryStIn -> Map.entry(entryStIn.getKey().getName(), entryStIn.getValue()))
                                                         .toList());
            })
            .toList());

    String inPath = coreInstanceOpt.map(coreInstance -> coreInstance.getCorePathIn()).orElse("CoresSrc");

    scal.Prepare(instrSet, op_stage_instr, spawn_instr_stage, core);

    coreInstanceOpt.ifPresent(coreInstance -> coreInstance.Prepare(instrSet, op_stage_instr, core, scal, BNodes));
    BNodes.updateInstrIDSize(BNodes.RdIssueID.size, BNodes.RdIssueID.elements);

    scal.Generate(inPath, outPath, coreInstanceOpt);

    // Remove user nodes before calling backend classes. Cores do not have to implement them, they were already handled in SCAL
    RemoveUserNodes();

    success = coreInstanceOpt.map(coreInstance -> coreInstance.Generate(instrSet, op_stage_instr, this.extensionName, core, outPath))
                  .orElse(false);

    Yaml netlistYaml = new Yaml();
    String netlistPath = "scaiev_netlist.yaml";
    if (outPath == null)
      netlistPath = Path.of(inPath, netlistPath).toString();
    else
      netlistPath = Path.of(outPath, netlistPath).toString();
    try {
      java.io.Writer netlistWriter = new java.io.FileWriter(netlistPath);
      netlistYaml.dump(scal.netlist, netlistWriter);
    } catch (IOException e) {
      e.printStackTrace();
      return false;
    }

    return success;
  }

  private void CreateOpStageInstr(Core core) throws FrontendNodeException {
    List<PipelineStage> spawnStages = core.GetSpawnStages().asList();
    assert (spawnStages.size() > 0);
    if (spawnStages.size() > 1)
      logger.warn("CreateOpStageInstr - only considering first of several 'spawn stages'");
    PipelineStage spawnStage = spawnStages.get(0);
    // Use execute stages as semi-coupled spawn targets.
    List<PipelineStage> rdrsStages = core.TranslateStageScheduleNumber(core.GetNodes().get(BNodes.RdRS1).GetEarliest()).asList();
    assert (rdrsStages.size() > 0);
    if (rdrsStages.isEmpty()) {
      logger.error("Cannot find a stage that allows RdRS1");
      return;
    }
    List<PipelineStage> execStages =
        core.GetRootStage().streamNext_bfs().filter(stage -> stage.getTags().contains(StageTag.Execute)).toList();
    if (execStages.isEmpty()) {
      PipelineFront rdRS1Expensive = core.TranslateStageScheduleNumber(core.GetNodes().get(BNodes.RdRS1).GetExpensive());
      // For stall pseudo-decoupled mode, choose the first stage after rdrsStage if possible.
      execStages = rdrsStages.stream()
                       .filter(rdrsStage -> rdRS1Expensive.isAfter(rdrsStage, false))
                       .flatMap(rdrsStage
                                -> rdrsStage.streamNext_bfs(nextStage -> nextStage == rdrsStage)
                                       .filter(rdrsNextStage -> rdrsNextStage != rdrsStage && rdrsNextStage.getKind() == StageKind.Core))
                       .distinct()
                       .toList();
    }
    if (execStages.isEmpty()) {
      logger.error("Cannot find a viable execute stage for semi-coupled spawn");
    }
    if (execStages.size() > 1)
      logger.warn("CreateOpStageInstr - only considering first of several execute stages");
    PipelineStage execStage = execStages.isEmpty() ? null : execStages.get(0);
    if (rdrsStages.size() > 1)
      logger.warn("CreateOpStageInstr - only considering first of several RdRS1 stages");
    PipelineStage rdrsStage = rdrsStages.get(0);

    List<PipelineStage> memStages = core.TranslateStageScheduleNumber(core.GetNodes().get(BNodes.RdMem).GetEarliest()).asList();
    assert (memStages.size() > 0);
    if (memStages.size() > 1)
      logger.warn("CreateOpStageInstr - only considering first of several RdMem stages");
    PipelineStage memStage = memStages.get(0);
    boolean barrierInstrRequired = false;

    HashMap<SCAIEVNode, HashMap<String, Integer>> spawn_instr_stagePos = new HashMap<>();
    HashMap<NodeInstanceDesc.Key, NodeInstanceDesc.Key> renamedISAXInterfacePins = new HashMap<>();

    for (String instructionName : instrSet.keySet()) {
      SCAIEVInstr instruction = instrSet.get(instructionName);

      // STEP 1: store actual spawn stages , to be used later in SCAL for fire logic & Scoreboard
      // HEADSUP. Make sure code bellow does NOT add always blocks, otherwise SCAL will generate fire logic
      HashMap<SCAIEVNode, List<Scheduled>> originalSchedNodes = instruction.GetSchedNodes();
      if (!instruction.HasNoOp()) {
        for (SCAIEVNode operation : originalSchedNodes.keySet()) {
          for (Scheduled sched : originalSchedNodes.get(operation)) {
            var spawnOperation_opt = this.BNodes.GetMySpawnNode(operation);
            if (spawnOperation_opt.isPresent()) { // if this is a node that has a spawn feature
              SCAIEVNode spawnOperation = spawnOperation_opt.get();
              int minSpawnStagePos = spawnStage.getStagePos();
              if (spawnOperation.equals(BNodes.RdMem_spawn) || spawnOperation.equals(BNodes.WrMem_spawn)) {
                minSpawnStagePos = memStage.getStagePos() + 1; // Mem stage has spawn from 1+ earliest memory stage
              }

              int stage = sched.GetStartCycle();
              if (stage >= minSpawnStagePos) {
                spawn_instr_stagePos.computeIfAbsent(spawnOperation, _op -> new HashMap<>()).putIfAbsent(instructionName, stage);
              }
            }
          }
        }
      }

      // STEP 2: Update Instruction metadata for backend
      // Now, after storing actual spawn stages in case of spawn IF using stall method, let's update ISAX schedule
      instruction.ConvertToBackend(core, BNodes);

      // STEP 3: Add nodes to op_stage_instr
      HashMap<SCAIEVNode, List<Scheduled>> schedNodes = instruction.GetSchedNodes();

      // 3.1 Add spawn nodes to op_stage_instr
      int numSchedStages = instruction.GetSchedNodes()
                               .values()
                               .stream()
                               .flatMap(schedSet -> schedSet.stream())
                               .map(sched -> sched.GetStartCycle() + 1)
                               .max(Integer::compare)
                               .orElse(0);
      PipelineStage[] moveToSpawnPipeline = new PipelineStage[numSchedStages];

      boolean insertedWrCommit = false;
      if (instruction.GetSchedNodes().keySet().stream().anyMatch(operation -> operation.isSpawn()) /* has any spawn operation */
          && !instruction.HasNode(BNodes.WrCommit_spawn) /* but no WrCommit_spawn */) {
        if (instruction.GetRunsAsDynamic()) {
          logger.error("ISAX {} is dynamic but does not have WrCommit_spawn. SCAL will not be able to detect the end of execution.",
                       instructionName);
        }
        // Add WrCommit_spawn to the schedule.
        int maxStartCycle = instruction.GetSchedNodes()
                                .values()
                                .stream()
                                .flatMap(list -> list.stream())
                                .map(sched -> sched.GetStartCycle())
                                .max(Integer::compare)
                                .orElseThrow();
        instruction.PutSchedNode(BNodes.WrCommit_spawn,
                                 new Scheduled(maxStartCycle, new HashSet<>(List.of(AdjacentNode.validReq)), new HashMap<>()));
        spawn_instr_stagePos.computeIfAbsent(BNodes.WrCommit_spawn, _op -> new HashMap<>()).put(instructionName, maxStartCycle);
        insertedWrCommit = true;
      }
      var isaxReadResultOps = instruction.GetSchedNodes()
                                  .keySet()
                                  .stream()
                                  .filter(node
                                          -> node.oneInterfToISAX && (node.tags.contains(NodeTypeTag.staticReadResult) ||
                                                                      node.tags.contains(NodeTypeTag.nonStaticReadResult)))
                                  .toList();
      if (!instruction.hasTag(InstrTag.PerISAXReadResults) && !isaxReadResultOps.isEmpty()) {
        String nodeReport = isaxReadResultOps.stream().map(op -> op.name).distinct().sorted().reduce((a, b) -> a + "," + b).orElse("");
        logger.warn("Deprecated: ISAX {} uses global port names for [{}]."
                        + " This can cause naming mismatches if no global value can be provided due to ISAX scheduling or other reasons."
                        + " Tag the ISAX with {} to mark support for the per-ISAX interface.",
                    instructionName, nodeReport, InstrTag.PerISAXReadResults.serialName);
      }
      if (instruction.GetSchedNodes().keySet().contains(BNodes.RdStall) && !instruction.hasTag(InstrTag.PerISAXRdStallFlush)) {
        logger.warn("Deprecated: ISAX {} uses the legacy global RdStall input. "
                        + "This can cause logic issues with WrStall of a different ISAX. "
                        + "Tag the ISAX with {} to mark support for the per-ISAX interface.",
                    instructionName, InstrTag.PerISAXRdStallFlush.serialName);
        if (instruction.GetSchedNodes().keySet().stream().anyMatch(operation -> operation.isSpawn()) && !instruction.GetRunsAsDecoupled())
          logger.error("Legacy global RdStall does not work with semi-coupled spawn (ISAX {}).", instruction.GetName());

        instruction.GetSchedNodes()
            .computeIfAbsent(BNodes.RdStallLegacy, _x -> new ArrayList<>())
            .addAll(instruction.GetSchedNodes()
                        .get(BNodes.RdStall)
                        .stream()
                        .map(sched -> new Scheduled(sched.GetStartCycle(), new HashSet<>(), new HashMap<>()))
                        .toList());
        instruction.GetSchedNodes().remove(BNodes.RdStall);
        if (!core.GetNodes().containsKey(BNodes.RdStallLegacy)) {
          var regularCoreNode = core.GetNodes().get(BNodes.RdStall);
          core.PutNode(BNodes.RdStallLegacy,
                       new CoreNode(regularCoreNode.GetEarliest(), regularCoreNode.GetLatency(), regularCoreNode.GetLatest(),
                                    regularCoreNode.GetExpensive(), BNodes.RdStallLegacy.name));
        }
      }
      if (instruction.GetSchedNodes().keySet().contains(BNodes.RdFlush) && !instruction.hasTag(InstrTag.PerISAXRdStallFlush)) {
        logger.error(
            "Obsolete: ISAX {} uses the legacy global RdFlush input. Tag the ISAX with {} to mark support for the per-ISAX interface.",
            instructionName, InstrTag.PerISAXRdStallFlush.serialName);
      }

      for (SCAIEVNode operation : schedNodes.keySet())
        if (operation.isSpawn()) {
          for (Scheduled sched : schedNodes.get(operation)) {
            SCAIEVNode addOperation = operation;
            int actualSpawnStagePos = spawnStage.getStagePos();
            if (operation.equals(BNodes.RdMem_spawn) || operation.equals(BNodes.WrMem_spawn))
              actualSpawnStagePos = memStage.getStagePos() + 1; // Mem stage has spawn from 1+ earliest memory stage

            PipelineStage stage;
            if (!instruction.GetRunsAsDecoupled()) {
              //// If it is a spawn but we have Stall strategy, make it common WrRD so that core handles DH
              // addOperation = this.BNodes.GetSCAIEVNode(operation.nameParentNode);
              if (execStage == null) {
                logger.error("Cannot schedule {} as semi-coupled/stall, no execute stage found", operation.name);
                continue;
              }
              stage = execStage; // rdrsStage+1;
            } else {
              stage = spawnStage;
            }
            if (sched.GetStartCycle() >= actualSpawnStagePos) {
              if (instruction.GetRunsAsDecoupled())
                barrierInstrRequired = true;
              assert (spawn_instr_stagePos.containsKey(operation) && spawn_instr_stagePos.get(operation).containsKey(instructionName));

              // Actual time step where the operation is scheduled by the ISAX
              int isaxOpStagePos = spawn_instr_stagePos.get(operation).get(instructionName);
              // Time step within the stage to run the operation in.
              int subStagePos = isaxOpStagePos - stage.getStagePos();

              // TODO: Right now, non-dynamic&decoupled ISAXes have no way of waiting for read results. This also affects semi-coupled
              // spawn.
              //  -> Here: Use the same sub-pipeline for all spawn operations of an ISAX.
              //  -> In SCAL generation: Stall sub-pipeline stages until the result is available.

              // Add a sub-stage with depth matching the stage position.
              String subStageNameBase = stage.getName() + "_sub" + stage.getChildren().size() + "_pos";
              PipelineStage childTail =
                  new PipelineStage(StageKind.Sub, EnumSet.noneOf(StageTag.class), subStageNameBase + "0", Optional.empty(), true);
              // Set moveToSpawnPipeline to the sub-stages, without overwriting existing any longer sub-pipeline 'strips' in it.
              boolean applyMove = Stream.of(moveToSpawnPipeline)
                                      .skip(stage.getStagePos())
                                      .limit(subStagePos + 1)
                                      .anyMatch(moveToStage -> moveToStage == null);
              if (applyMove)
                moveToSpawnPipeline[stage.getStagePos()] = childTail;
              stage.addChild(childTail);
              while (childTail.getStagePos() < subStagePos) {
                childTail = childTail.addNext(new PipelineStage(StageKind.Sub, EnumSet.noneOf(StageTag.class),
                                                                subStageNameBase + (childTail.getStagePos() + 1), Optional.empty(), true));
                if (applyMove)
                  moveToSpawnPipeline[stage.getStagePos() + childTail.getStagePos()] = childTail;
              }
              PipelineStage isaxStage = childTail;

              if (null != spawn_instr_stage.computeIfAbsent(operation, _op -> new HashMap<>()).putIfAbsent(instructionName, isaxStage)) {
                logger.error("ISAX {} contains multiple scheduled {} nodes, which is currently not allowed.", instructionName,
                             operation.name);
              }
              if (operation.equals(BNodes.WrCommit_spawn) && insertedWrCommit) {
                // Prevent generation of the interface by adding a rename to an empty node (detected by SCAL#GenerateAllInterfToISAX).
                renamedISAXInterfacePins.put(new NodeInstanceDesc.Key(operation, stage, instructionName),
                                             new NodeInstanceDesc.Key(new SCAIEVNode(""), stage, instructionName));
              }
              // Put our spawn operation in op_stage_instr at the parent of the sub-pipeline.
              op_stage_instr.computeIfAbsent(addOperation, _op -> new HashMap<>())
                  .computeIfAbsent(stage, _stage -> new HashSet<>())
                  .add(instructionName);
            }
          }
        }
      // 3.2 Add non-spawn operations to op_stage_instr
      // -> TODO: Certain non-spawn operations (Rd/WrMem, WrCustomReg) will need additional conversion,
      //    since the ISAX has non-spawn interfaces but the behavior will be like spawn.
      //    This edge case will occur for non-decoupled spawn instructions
      //    where one of these non-spawn operations already is in stallExecStage.
      for (SCAIEVNode operation : schedNodes.keySet())
        if (!operation.isSpawn()) {
          for (Scheduled sched : schedNodes.get(operation)) {
            SCAIEVNode operationRenameTo = operation;
            PipelineStage stageRenameTo = null;
            String instructionRenameTo = instructionName;
            boolean doRename = false;
            if (operation.equals(BNodes.RdStallLegacy)) {
              operationRenameTo = BNodes.RdStall;
              instructionRenameTo = "";
            } else if (instruction.hasTag(InstrTag.PerISAXReadResults) && operation.oneInterfToISAX &&
                       (operation.tags.contains(NodeTypeTag.staticReadResult) ||
                        operation.tags.contains(NodeTypeTag.nonStaticReadResult))) {
              doRename = true;
            }
            if (BNodes.IsUserBNode(operation) && operation.getAdj() == AdjacentNode.addr &&
                BNodes.GetSCAIEVNode(operation.nameParentNode).elements <= 1) {
              // Custom registers: Omit the address interface if there only is a single element.
              // Still keep track of it in op_stage_instr as a marker for the custom register issue stage.
              operationRenameTo = new SCAIEVNode("");
            }

            // For all non-spawn operations, apply any scheduling overrides from semi-coupled spawn.
            // For instance, a semi-coupled spawn could run over N cycles within stage 4
            //  -> those N cycles from the ISAX schedule will be mapped to some part of stage 4 (stage 4 itself or a sub-pipeline),
            //     the following cycles will be shifted to match the natural core mapping.
            PipelineStage stage;
            int schedNumOffset = 0;
            if (moveToSpawnPipeline[sched.GetStartCycle()] != null) {
              stage = moveToSpawnPipeline[sched.GetStartCycle()];
            } else {
              // Shift depending on the stage mappings of prior cycles.
              for (int iIntermStagePos = sched.GetStartCycle() - 1; iIntermStagePos >= 0; --iIntermStagePos) {
                if (moveToSpawnPipeline[iIntermStagePos] != null) {
                  // Get the difference from the 'move to' base stage number and the highest ISAX cycle number that uses it in a
                  // sub-pipeline.
                  schedNumOffset =
                      core.GetStageNumber(moveToSpawnPipeline[iIntermStagePos].getParent().orElseThrow()).orElseThrow() - iIntermStagePos;
                  break;
                }
              }
              var stages = core.TranslateStageScheduleNumber(sched.GetStartCycle() + schedNumOffset);
              if (stages.asList().isEmpty()) {
                logger.error(
                    "Cannot schedule a node of ISAX {} to the core pipeline: {}; it must be succeeded by or simultaneous to a spawn node.",
                    instructionName, sched.toString());
                continue;
              }
              stage = stages.asList().get(0);
              PipelineFront earliestStages = core.GetNodes().containsKey(operation)
                                                 ? core.TranslateStageScheduleNumber(core.GetNodes().get(operation).GetEarliest())
                                                 : null;
              if (instruction.HasNoOp() /*shift scheduled 'always'-mode operation to the earliest stage*/
                  && earliestStages != null && !earliestStages.isAroundOrBefore(stage, false) && !earliestStages.isAfter(stage, false)) {
                stage = earliestStages.asList().get(0);
              }
            }
            stageRenameTo = stage;
            var originalStages = core.TranslateStageScheduleNumber(sched.GetStartCycle()).asList();
            if (originalStages.isEmpty() || originalStages.get(0) != stage) {
              PipelineStage originalStage =
                  (originalStages.isEmpty() ? new PipelineStage(StageKind.CoreInternal, EnumSet.of(StageTag.InOrder),
                                                                "pseudo" + sched.GetStartCycle(), Optional.of(sched.GetStartCycle()), true)
                                            : originalStages.get(0));
              // Rename the ISAX pin (towards the ISAX module) as if it were scheduled in the default stage.
              stageRenameTo = originalStage;
            }
            if (doRename || operationRenameTo != operation || stageRenameTo != stage || instructionRenameTo != instructionName) {
              // Apply rename.
              renamedISAXInterfacePins.put(new NodeInstanceDesc.Key(operation, stage, instructionName),
                                           new NodeInstanceDesc.Key(operationRenameTo, stageRenameTo, instructionRenameTo));
            }
            op_stage_instr.computeIfAbsent(operation, _op -> new HashMap<>())
                .computeIfAbsent(stage, _stage -> new HashSet<>())
                .add(instructionName);
          }
        }
    }
    this.scal.AddISAXInterfacePinRenames(renamedISAXInterfacePins.entrySet());

    // Check if any spawn and if yes, add the barrier instr (WrRD spawn & Internal state spawn)
    boolean barrierNeeded = false;
    for (SCAIEVNode node : op_stage_instr.keySet())
      if (node.isSpawn())
        barrierNeeded = true;
    if (barrierNeeded && barrierInstrRequired) {
      if (cfg.maygenerate_disaxkill) {
        AddIn_op_stage_instr(BNodes.RdRS1, rdrsStage, "disaxkill");
        SCAIEVInstr kill = SCAL.PredefInstr.kill.instr;
        // SCAIEVInstr kill = addInstr("disaxkill","-------", "110", "0001011", "S");
        kill.PutSchedNode(FNodes.RdRS1, rdrsStage.getStagePos());
        instrSet.put("disaxkill", kill);
      }
      if (cfg.maygenerate_disaxfence) {
        AddIn_op_stage_instr(BNodes.RdRS1, rdrsStage, "disaxfence");
        SCAIEVInstr fence = SCAL.PredefInstr.fence.instr;
        // SCAIEVInstr fence = addInstr("disaxfence","-------", "111", "0001011", "S");
        fence.PutSchedNode(FNodes.RdRS1, rdrsStage.getStagePos());
        instrSet.put("disaxfence", fence);
      }
    }

    // add instruction context switching for custom registers
    if (cfg.number_of_contexts > 1) {
      SCAIEVInstr ctx = SCAL.PredefInstr.ctx.instr;
      instrSet.put("isaxctx", ctx);
    }
  }

  private boolean AddIn_op_stage_instr(SCAIEVNode operation, PipelineStage stage, String instruction) {
    if (!op_stage_instr.containsKey(operation))
      op_stage_instr.put(operation, new HashMap<>());
    else if (op_stage_instr.get(operation).containsKey(stage) && op_stage_instr.get(operation).get(stage).contains(instruction))
      return false;
    if (!op_stage_instr.get(operation).containsKey(stage))
      op_stage_instr.get(operation).put(stage, new HashSet<String>());
    op_stage_instr.get(operation).get(stage).add(instruction);
    return true;
  }

  private void OpStageInstrToString() {
    for (SCAIEVNode operation : op_stage_instr.keySet())
      for (PipelineStage stage : op_stage_instr.get(operation).keySet()) {
        logger.debug("INFO. SCAIEV. Operation = " + operation + " in stage = " + stage.getName() +
                     " for instruction/s: " + op_stage_instr.get(operation).get(stage).toString());
      }
  }

  public void SetExtensionName(String name) { this.extensionName = name; }

  public void DefNewNodes(HashMap<String, Integer> earliest_operation) { this.earliest_useroperation = earliest_operation; }

  private void AddCommitStagesToNodes(Core core) {

    // Add commit stage of core
    BNodes.RdMem.commitStage = core.GetNodes().get(BNodes.RdMem).GetEarliest();
    for (SCAIEVNode nodeAdj : BNodes.GetAdjSCAIEVNodes(BNodes.RdMem))
      nodeAdj.commitStage = new ScheduleFront(core.maxStage);
    BNodes.WrMem.commitStage = core.GetNodes().get(BNodes.WrMem).GetEarliest();
    for (SCAIEVNode nodeAdj : BNodes.GetAdjSCAIEVNodes(BNodes.WrMem))
      nodeAdj.commitStage = new ScheduleFront(core.maxStage);
    BNodes.WrRD.commitStage = new ScheduleFront(core.maxStage);
    for (SCAIEVNode nodeAdj : BNodes.GetAdjSCAIEVNodes(BNodes.WrRD))
      nodeAdj.commitStage = new ScheduleFront(core.maxStage);
    BNodes.WrPC.commitStage = new ScheduleFront(core.maxStage);
    BNodes.WrPC_valid.commitStage = new ScheduleFront(core.maxStage);
    BNodes.WrCommit.commitStage = new ScheduleFront(0);

    // Add commit stage info of WrUser node. First write nodes, than read nodes
    for (SCAIEVNode node : this.BNodes.GetAllBackNodes()) {
      if (BNodes.IsUserBNode(node) && node.isInput && !node.isAdj()) {
        // Get Latest/Earliest stage
        int latest = 0;
        for (String instr : this.instrSet.keySet()) {                    // for each instruction
          if (instrSet.get(instr).HasNode(node)) {                       // get node
            List<Scheduled> scheds = instrSet.get(instr).GetNodes(node); // check out latest sched stage
            for (Scheduled sched : scheds) {
              if (sched.GetStartCycle() > latest && sched.GetStartCycle() <= core.maxStage && !this.instrSet.get(instr).HasNoOp())
                latest = sched.GetStartCycle();
            }
          }
        }
        node.commitStage = new ScheduleFront(latest);
        for (SCAIEVNode nodeAdj : BNodes.GetAdjSCAIEVNodes(node)) {
          nodeAdj.commitStage = new ScheduleFront(latest);
        }
      }
    }

    // Add commit stage info of RdUser node
    for (SCAIEVNode node : this.BNodes.GetAllBackNodes()) {
      if (BNodes.IsUserBNode(node) && !node.isInput && !node.isAdj()) {
        // Get Latest/Earliest stage
        int earliest_num = core.maxStage + 1;
        for (String instr : this.instrSet.keySet()) {                    // for each instruction
          if (instrSet.get(instr).HasNode(node)) {                       // get node
            List<Scheduled> scheds = instrSet.get(instr).GetNodes(node); // check out latest sched stage
            for (Scheduled sched : scheds) {
              if (sched.GetStartCycle() < earliest_num && !this.instrSet.get(instr).HasNoOp())
                earliest_num = sched.GetStartCycle();
            }
          }
        }
        ScheduleFront earliest = new ScheduleFront(earliest_num);
        if (earliest_num == core.maxStage + 1) {
          SCAIEVNode WrNode = BNodes.GetSCAIEVNode(BNodes.GetNameWrNode(node));
          earliest = WrNode.commitStage;
        }
        node.commitStage = earliest;
        for (SCAIEVNode nodeAdj : BNodes.GetAdjSCAIEVNodes(node)) {
          nodeAdj.commitStage = earliest;
        }
      }
    }
  }

  private void AddUserNodesToCore(Core core) {
    boolean added = false;
    for (SCAIEVNode operation : this.op_stage_instr.keySet()) {
      if (this.BNodes.IsUserBNode(operation) && !operation.isSpawn()) {
        //(Rd|Wr)CustomReg, (Rd|Wr)CustomReg_addr

        // Get Latest stage
        int latest = core.maxStage;

        SCAIEVNode baseOperation = BNodes.GetNonAdjNode(operation);

        if (operation.isInput) { // WrCustomReg, (Rd|Wr)CustomReg_addr
          latest = 0;
          for (String instr : this.instrSet.keySet()) {                         // for each instruction
            if (instrSet.get(instr).HasNode(operation)) {                       // get node
              List<Scheduled> scheds = instrSet.get(instr).GetNodes(operation); // check out latest sched stage
              for (Scheduled sched : scheds) {
                if (sched.GetStartCycle() > latest)
                  latest = sched.GetStartCycle();
              }
            }
          }
        }

        added = true;

        int earliest = earliest_useroperation.get(operation.name);

        // latest for WrNode is effectively infinite
        if (baseOperation.isInput) { // WrCustomReg
          CoreNode corenode = new CoreNode(earliest, 0, latest, latest + 1,
                                           operation.name); // default values, it is anyways supposed user defined node well
          core.PutNode(operation, corenode);
        } else { // RdCustomReg, (Rd|Wr)CustomReg_addr
          CoreNode corenode = new CoreNode(earliest, 0, earliest, earliest + 1,
                                           operation.name); // default values, it is anyways supposed user defined node well
          core.PutNode(operation, corenode);
        }
      }

      // Update node if there are multiple spawn instructions. Default was 1
      if (this.BNodes.IsUserBNode(operation) && operation.isSpawn()) {
        if (op_stage_instr.get(operation)
                .entrySet()
                .stream()
                //.filter(stage_instr_entry -> stage_instr_entry.getKey().getKind() == StageKind.Decoupled)
                .flatMap(stage_instr_entry -> stage_instr_entry.getValue().stream())
                .count() > 1) {
          operation.allowMultipleSpawn = true;
          operation.oneInterfToISAX = false;
        }
      }
    }

    if (added)
      logger.debug("INFO. After adding user-mode nodes, the core is: " + core);
  }

  private void RemoveUserNodes() {
    HashSet<SCAIEVNode> remove = new HashSet<SCAIEVNode>();
    for (SCAIEVNode operation : this.op_stage_instr.keySet())
      if (this.FNodes.IsUserFNode(operation))
        remove.add(operation);
    for (SCAIEVNode operation : remove)
      op_stage_instr.remove(operation);
  }
}
