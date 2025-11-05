package scaiev.scal.strategy.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Map.Entry;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.Scheduled;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;
import scaiev.frontend.Scheduled.ScheduledNodeTag;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistry;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.scal.strategy.standard.ValidMuxStrategy;
import scaiev.scal.strategy.state.SCALStateStrategy.IssueReadEntry;
import scaiev.scal.strategy.state.SCALStateStrategy.PortMuxGroup;
import scaiev.scal.strategy.state.SCALStateStrategy.PortRename;
import scaiev.scal.strategy.state.SCALStateStrategy.RegfileInfo;
import scaiev.ui.SCAIEVConfig;
import scaiev.util.Lang;
import scaiev.util.Verilog;

/** Port mapping logic used in {@link SCALStateStrategy} */
public class SCALStateStrategy_PortMapping {

  // logging
  protected static final Logger logger = LogManager.getLogger();
  
  protected SCALStateStrategy stateStrategy;
  private StrategyBuilders strategyBuilders;
  public BNode bNodes;
  private Verilog language;
  private Core core;
  private HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  private HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage;
  private HashMap<String, SCAIEVInstr> allISAXes;
  private SCAIEVConfig cfg;

  /**
   * @param strategyBuilders The StrategyBuilders object to build sub-strategies with
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param spawn_instr_stage The Node-ISAX-Stage mapping providing the precise sub-pipeline stage for spawn operations
   * @param allISAXes The ISAX descriptions
   */
  public SCALStateStrategy_PortMapping(SCALStateStrategy stateStrategy, StrategyBuilders strategyBuilders,
                           Verilog language, BNode bNodes, Core core,
                           HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                           HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage,
                           HashMap<String, SCAIEVInstr> allISAXes, SCAIEVConfig cfg) {
    this.stateStrategy = stateStrategy;
    this.strategyBuilders = strategyBuilders;
    this.bNodes = bNodes;
    this.language = language;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.spawn_instr_stage = spawn_instr_stage;
    this.allISAXes = allISAXes;
    this.cfg = cfg;
  }
  
  private static void undoRenames(List<PortRename> renames, List<NodeInstanceDesc.Key> keyList) {
    // Handles the case where several renames have the same 'to' key, and keyList is deduplicated.
    //-> Need to add new entries to keyList for the 2nd..Nth rename to each key.
    //-> A rename entry may not be intended for keyList;
    //    so, if there is no initial match for rename.to in keyList, all renames with that key are ignored.
    Set<NodeInstanceDesc.Key> renameTosForKeylist = new HashSet<>();
    for (PortRename portRename : renames) {
      int matchIdx = keyList.indexOf(portRename.to);
      if (matchIdx == -1 && renameTosForKeylist.contains(portRename.to)) {
        // Add an entry as needed, if several keys were renamed to the same.
        keyList.add(portRename.from);
      } else if (matchIdx != -1) {
        renameTosForKeylist.add(portRename.to);
        // Replace an existing entry.
        keyList.set(matchIdx, portRename.from);
      }
    }
  }
  public void generatePortMapping(RegfileInfo regfileInfo) {
    // Undo renames to start from scratch
    regfileInfo.earlyReads = new ArrayList<>(regfileInfo.earlyReadsUnmapped);
    regfileInfo.earlyWrites = new ArrayList<>(regfileInfo.earlyWritesUnmapped);
    regfileInfo.regularReads = new ArrayList<>(regfileInfo.regularReadsUnmapped);
    regfileInfo.regularWrites = new ArrayList<>(regfileInfo.regularWritesUnmapped);
    //undoRenames(regfileInfo.portRenames, regfileInfo.earlyReads);
    //undoRenames(regfileInfo.portRenames, regfileInfo.earlyWrites);
    //undoRenames(regfileInfo.portRenames, regfileInfo.regularReads);
    //undoRenames(regfileInfo.portRenames, regfileInfo.regularWrites);

    regfileInfo.issue_reads.clear();
    regfileInfo.writeback_writes.clear();
    regfileInfo.portRenames.clear();
    regfileInfo.portMuxGroups.clear();
    if (regfileInfo.portMuxStrategy != null)
      logger.error(
          "SCALStateStrategy: configureRegfile invoked again after having used portMuxStrategy; port mapping updates will not apply");

    class InstanceInfo {
      /** The exact key, including ISAX/aux if present */
      NodeInstanceDesc.Key key;
      List<NodeInstanceDesc.Key> sourceList;
      int sourceListIndex;
      /** If set, the key is from a known non-NoOp ISAX. */
      boolean fromRegularISAX;
      public InstanceInfo(List<NodeInstanceDesc.Key> fromList, int listIndex) {
        this.sourceList = fromList;
        this.sourceListIndex = listIndex;
        this.key = fromList.get(listIndex);
        this.fromRegularISAX = !key.getISAX().isEmpty() && allISAXes.containsKey(key.getISAX()) && !allISAXes.get(key.getISAX()).HasNoOp();
        assert (key.getStage().getKind() != StageKind.Sub);
      }
      public boolean isCompatible(InstanceInfo other) {
        if (this == other)
          return true;
        assert (this.getClass().equals(other.getClass()));
        assert (this.key.getNode().name.startsWith(BNode.rdPrefix) == other.key.getNode().name.startsWith(BNode.rdPrefix));
        assert (this.key.getNode().name.startsWith(BNode.wrPrefix) == other.key.getNode().name.startsWith(BNode.wrPrefix));
        if (this.key.getISAX().equals(other.key.getISAX()) && this.key.getAux() == other.key.getAux()) {
          if (!this.key.getNode().equals(other.key.getNode()))
            return false;
          if (this.key.getStage() != other.key.getStage())
            return false;
          assert (false); // Two different RdInstanceInfo for the same node (maybe only differs by purpose)?
          return false;
        }
        if (!fromRegularISAX || !other.fromRegularISAX)
          return false;
        return true;
      }
    }
    class WrInstanceInfo extends InstanceInfo {
      /** Writeback stages deemed potentially relevant, sorted by {@link PipelineStage#getSortKey()} */
      List<PipelineStage> relevantWritebackStages;
      // public WrInstanceInfo(NodeInstanceDesc.Key key) {
      public WrInstanceInfo(List<NodeInstanceDesc.Key> fromList, int listIndex) {
        super(fromList, listIndex);
        boolean decoupled = key.getStage().getKind() == StageKind.Decoupled;
        Predicate<PipelineStage> stageIsRelevant =
            (stage
             -> (!fromRegularISAX || !stage.getTags().contains(StageTag.NoISAX)) // Ignore NoISAX stages for regular ISAXes
                    && (decoupled || stage.getKind() != StageKind.Decoupled));   // Ignore decoupled stages for non-decoupled ISAXes
        this.relevantWritebackStages =
            key.getStage()
                .streamNext_bfs(nextStage
                                -> !regfileInfo.writebackFront.contains(nextStage) &&
                                       stageIsRelevant.test(nextStage)) // Stop iterating past each first one, apply relevance criteria
                .filter(nextStage
                        -> regfileInfo.writebackFront.contains(nextStage) &&
                               stageIsRelevant.test(nextStage)) // Only process writeback stages, apply relevance criteria
                .sorted((a, b) -> Long.compare(a.getSortKey(), b.getSortKey()))
                .toList();
      }
      @Override
      public boolean isCompatible(InstanceInfo other) {
        return super.isCompatible(other) && (other instanceof WrInstanceInfo) &&
            this.relevantWritebackStages.equals(((WrInstanceInfo)other).relevantWritebackStages);
      }
    }
    class MuxGroupDescEx {
      PortMuxGroup muxGroup;
      List<InstanceInfo> instances = new ArrayList<>();
      public MuxGroupDescEx(NodeInstanceDesc.Key writeBaseKey) { this.muxGroup = new PortMuxGroup(writeBaseKey); }
    }

    // - Rename all keys (not just some).
    //     While not always necessary, this easily prevents cases where some non-renamed group still has ISAX/aux-cousins up for confusion
    //     (ValidMuxStrategy) Renaming means creating a new port node and disregarding the non-renamed one. The non-renamed one still exists
    //     in the registry, though.
    //   -> NoOP ISAX keys are always put in a new group.
    //   -> same-ISAX different-stage keys are put in different groups.
    //   -> same-node different-writeback keys are put in different groups.
    var relevantWrNodesByStage =
        new TreeMap<PipelineStage, List<WrInstanceInfo>>((a, b) -> Integer.compare(a.getStagePos(), b.getStagePos()));
    var util = new Object() {
      void addRelevantWrNode(List<NodeInstanceDesc.Key> fromList, int listIndex) {
        var entry = new WrInstanceInfo(fromList, listIndex);
        assert (!entry.key.getNode().isAdj()); // only base nodes expected
        relevantWrNodesByStage.computeIfAbsent(entry.key.getStage(), stage_ -> new ArrayList<>()).add(entry);
      }
      NodeInstanceDesc.Key renameToGroup(NodeInstanceDesc.Key groupKey, InstanceInfo instance) {
        var key = instance.key;
        boolean isReadResult = instance.key.getNode().tags.contains(NodeTypeTag.staticReadResult);
        assert(groupKey.getISAX().isEmpty());
        assert(groupKey.getAux() == 0);
        //ValidMuxStrategy does not apply to read results (that go back to all ISAXes listening for it)
        // -> Rename directly between the group key and the ISAX read instance
        // -> Otherwise, if MUXing or selection is required,
        //     rename to the group key with ISAX/aux set.
        String renameToISAX = isReadResult ? groupKey.getISAX() : instance.key.getISAX();
        int renameToAux = isReadResult ? groupKey.getAux() : instance.key.getAux();
        NodeInstanceDesc.Key renameToKey = new NodeInstanceDesc.Key(groupKey.getPurpose(), groupKey.getNode(), instance.key.getStage(),
                                                                    renameToISAX, renameToAux);
        regfileInfo.portRenames.add(new PortRename(instance.key, renameToKey));
        instance.sourceList.set(instance.sourceListIndex, renameToKey);
        
        SCAIEVNode nodeNonspawn = bNodes.GetEquivalentNonspawnNode(key.getNode()).orElseThrow();
        SCAIEVNode renameToNodeNonspawn = bNodes.GetEquivalentNonspawnNode(groupKey.getNode()).orElseThrow();

        SCAIEVNode addrNode = bNodes.GetAdjSCAIEVNode(nodeNonspawn, AdjacentNode.addr).orElseThrow();
        // Also add renames in case the instruction (or 'always' block) provides the address earlier.
        //(using the same node for the rename, as only the base node is compared against)
        op_stage_instr.getOrDefault(addrNode, new HashMap<>())
            .entrySet()
            .stream()
            .filter(stage_instr -> stage_instr.getKey() != renameToKey.getStage() && stage_instr.getValue().contains(key.getISAX()))
            .map(stage_instr -> stage_instr.getKey())
            .forEach(addrStage -> {
              regfileInfo.portRenames.add(
                  new PortRename(new NodeInstanceDesc.Key(key.getPurpose(), nodeNonspawn, addrStage, key.getISAX(), key.getAux()),
                                  new NodeInstanceDesc.Key(renameToKey.getPurpose(), renameToNodeNonspawn, addrStage,
                                                          renameToKey.getISAX(), renameToKey.getAux())));
            });
        return renameToKey;
      }
    };
    IntStream.range(0, regfileInfo.earlyWrites.size()).forEach(idx -> util.addRelevantWrNode(regfileInfo.earlyWrites, idx));
    IntStream.range(0, regfileInfo.regularWrites.size()).forEach(idx -> util.addRelevantWrNode(regfileInfo.regularWrites, idx));

    // Assumption: Stages can sensibly be ordered by stagePos, i.e. stages with equal stagePos are not before/after one another.
    for (PipelineStage a : relevantWrNodesByStage.keySet()) {
      var aFront = new PipelineFront(a);
      for (PipelineStage b : relevantWrNodesByStage.keySet())
        if (b != a) {
          // No cycles
          assert (!aFront.isBefore(b, false) || !aFront.isAfter(b, false));
        }
      for (PipelineStage b : relevantWrNodesByStage.keySet())
        if (b != a && b.getStagePos() == a.getStagePos()) {
          var bFront = new PipelineFront(b);
          // No ordering between same-pos stages
          assert (!aFront.isBefore(b, false) && !bFront.isAfter(a, false));
        }
    }

    List<MuxGroupDescEx> writeMuxGroups = new ArrayList<>();

    // if immediate write: Lower stage numbers have priority (represent a later instruction), i.e. need a higher port number
    // else if write on writeback: Higher stage numbers have priority (represent a later point in execution of an instruction), i.e. need a
    // higher port number

    Iterator<Entry<PipelineStage, List<WrInstanceInfo>>> iter_relevantWrNodesByStage_prioritySorted =
        Stream
            .concat(
                relevantWrNodesByStage.entrySet().stream().filter(
                    entry -> regfileInfo.writebackFront.isAfter(entry.getKey(), false) && !regfileInfo.writebackFront.isAround(entry.getKey())),
                relevantWrNodesByStage.entrySet().stream().filter(entry -> regfileInfo.writebackFront.isAround(entry.getKey())))
            .iterator();
    int nextPriorityVal = 0;
    while (iter_relevantWrNodesByStage_prioritySorted.hasNext()) {
      Entry<PipelineStage, List<WrInstanceInfo>> entry = iter_relevantWrNodesByStage_prioritySorted.next();
      for (WrInstanceInfo writeInstance : entry.getValue()) {
        if (writeInstance.relevantWritebackStages.isEmpty()) {
          logger.error("SCALStageStrategy - Found no matching writeback stage (from {}) for {}",
                       regfileInfo.writebackFront.asList().stream().map(stage -> stage.getName()).toList(), writeInstance.key.toString(false));
          continue;
        }
        MuxGroupDescEx muxGroupEx = null;
        for (MuxGroupDescEx curMuxGroupEx : writeMuxGroups) {
          if (curMuxGroupEx.instances.get(0).isCompatible(writeInstance)) {
            curMuxGroupEx.instances.add(writeInstance);
            muxGroupEx = curMuxGroupEx;
            break;
          }
        }

        if (muxGroupEx == null) {
          // Create a new mux group
          SCAIEVNode renamedPortNode = bNodes.AddUserNodePort(writeInstance.key.getNode(), "scal" + nextPriorityVal);
          if (writeInstance.key.getNode().isSpawn()) {
            //addr is associated with the non-spawn node only
            SCAIEVNode writeInstanceNodeNonspawn = bNodes.GetEquivalentNonspawnNode(writeInstance.key.getNode()).orElseThrow();
            bNodes.AddUserNodePort(writeInstanceNodeNonspawn, "scal" + nextPriorityVal);
          }
          nextPriorityVal++;

          NodeInstanceDesc.Key newGroupKey =
              new NodeInstanceDesc.Key(Purpose.REGULAR, renamedPortNode, writeInstance.key.getStage(), "", 0);
          var newMuxGroupEx = new MuxGroupDescEx(newGroupKey);
          writeMuxGroups.add(newMuxGroupEx);
          muxGroupEx = newMuxGroupEx;

          for (PipelineStage writebackStage : writeInstance.relevantWritebackStages) {
            regfileInfo.writeback_writes.add(new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, newGroupKey.getNode(),
                                                                   writebackStage, newGroupKey.getISAX(), newGroupKey.getAux()));
          }
        }

        var groupKey = muxGroupEx.muxGroup.groupKey;
        muxGroupEx.instances.add(writeInstance);
        //NodeInstanceDesc.Key renameToKey =
        util.renameToGroup(groupKey, writeInstance);
        muxGroupEx.muxGroup.sourceKeys.add(writeInstance.key);
      }
    }
    writeMuxGroups.stream().forEach(muxGroupEx -> regfileInfo.portMuxGroups.add(muxGroupEx.muxGroup));

    List<String> cannotReachWritebackKeyNames = relevantWrNodesByStage.entrySet()
                                                    .stream()
                                                    .filter(entry -> !regfileInfo.writebackFront.isAroundOrAfter(entry.getKey(), false))
                                                    .flatMap(entry -> entry.getValue().stream())
                                                    .map(keyAndRef -> keyAndRef.key.toString(false))
                                                    .toList();
    if (!cannotReachWritebackKeyNames.isEmpty()) {
      logger.error("SCALStageStrategy - Found Wr{} nodes that do not have a path to writeback ({}): {}", regfileInfo.regName,
                   regfileInfo.writebackFront.asList().stream().map(stage -> stage.getName()).toList(), cannotReachWritebackKeyNames);
    }

    // Next, rename issueReads to groups.
    // As early reads all get their own port, no muxing is needed; thus, no extra group/port nodes are needed for those.
    class RdInstanceInfo extends InstanceInfo {
      public RdInstanceInfo(List<NodeInstanceDesc.Key> fromList, int listIndex) { super(fromList, listIndex); }
    }
    List<MuxGroupDescEx> readMuxGroups = new ArrayList<>();
    // Tracking set for global interface renames
    HashSet<PipelineStage> hasGlobalReadRenamesFor = new HashSet<>();
    int nextReadPort = 0;
    for (int iIssueRead = 0; iIssueRead < regfileInfo.regularReads.size(); ++iIssueRead) {
      RdInstanceInfo readInstance = new RdInstanceInfo(regfileInfo.regularReads, iIssueRead);
      MuxGroupDescEx muxGroupEx = null;
      for (MuxGroupDescEx curMuxGroupEx : readMuxGroups) {
        if (curMuxGroupEx.instances.get(0).isCompatible(readInstance)) {
          curMuxGroupEx.instances.add(readInstance);
          muxGroupEx = curMuxGroupEx;
          break;
        }
      }

      if (muxGroupEx == null) {
        // Create a new mux group
        SCAIEVNode renamedPortNode = bNodes.AddUserNodePort(readInstance.key.getNode(), "scal" + nextReadPort);
        nextReadPort++;

        NodeInstanceDesc.Key newGroupKey = new NodeInstanceDesc.Key(Purpose.REGULAR, renamedPortNode, readInstance.key.getStage(), "", 0);
        var newMuxGroupEx = new MuxGroupDescEx(newGroupKey);
        readMuxGroups.add(newMuxGroupEx);
        muxGroupEx = newMuxGroupEx;

      }

      var groupKey = muxGroupEx.muxGroup.groupKey;
      muxGroupEx.instances.add(readInstance);

      util.renameToGroup(groupKey, readInstance);
      muxGroupEx.muxGroup.sourceKeys.add(readInstance.key);
      if (hasGlobalReadRenamesFor.add(readInstance.key.getStage())) {
        // In case the ISAX uses the old-style shared interface name (shared between ISAXes), add an automatic rename.
        regfileInfo.portRenames.add(
            new PortRename(new NodeInstanceDesc.Key(readInstance.key.getNode(), readInstance.key.getStage(), ""),
                           new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, groupKey.getNode(),
                                                    readInstance.key.getStage(), groupKey.getISAX(), groupKey.getAux())));
      }
    }
    //Create issue_reads entries for the readMuxGroups.
    for (var muxGroup : readMuxGroups) {
      boolean hasISAXWithForwardingDisabled = false;
      boolean hasISAXWithForwardingEnabled = false;
      PipelineStage readStage = null;
      for (var sourceInstance : muxGroup.instances) {
        readStage = sourceInstance.key.getStage();
        if (!sourceInstance.fromRegularISAX)
          continue; //NOTE: Currently, non-ISAX reads have no 'voting rights' on whether to disable forwarding.
        assert(allISAXes.get(sourceInstance.key.getISAX()) != null);
        //FIXME: If the ISAX has multiple reads from the regfile,
        //       there is no good way to tell which Scheduled entry is the one we're looking for.
        //       -> Need some op_stage_instr -> Scheduled mapping that configureRegfile's caller
        //          would includes in regularReadsUnmapped.
        //For now, only disable forwarding if _all_ reads of that regfile from the ISAX have it disabled.
        if (allISAXes.get(sourceInstance.key.getISAX()).HasSchedWith(
              sourceInstance.key.getNode(),
              sched -> sched.GetTags().contains(ScheduledNodeTag.Custreg_DisableReadForwarding)))
          hasISAXWithForwardingDisabled = true;
        if (allISAXes.get(sourceInstance.key.getISAX()).HasSchedWith(
              sourceInstance.key.getNode(),
              sched -> !sched.GetTags().contains(ScheduledNodeTag.Custreg_DisableReadForwarding)))
          hasISAXWithForwardingEnabled = true;
      }
      assert(readStage != null);
      var readKey = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, muxGroup.muxGroup.groupKey.getNode(),
                                             readStage, muxGroup.muxGroup.groupKey.getISAX(), muxGroup.muxGroup.groupKey.getAux());
      boolean disableForwarding = hasISAXWithForwardingDisabled && !hasISAXWithForwardingEnabled;
      regfileInfo.issue_reads.add(new IssueReadEntry(readKey, disableForwarding));
    }

    readMuxGroups.stream().forEach(muxGroupEx -> regfileInfo.portMuxGroups.add(muxGroupEx.muxGroup));
  }
  


  boolean implementSingle_RegportRename(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey, boolean isLast, String regName) {
    assert (bNodes.IsUserBNode(nodeKey.getNode()));
    RegfileInfo regfile = stateStrategy.regfilesByName.get(regName);
    if (regfile == null)
      return false;
    for (var rename : regfile.portRenames) {
      var fromAdjNode_opt = bNodes.GetAdjSCAIEVNode(rename.from.getNode(), nodeKey.getNode().getAdj());
      if (fromAdjNode_opt.isEmpty())
        continue;
      if (nodeKey.getNode().isInput && !nodeKey.getISAX().isEmpty() &&
          !allISAXes.get(nodeKey.getISAX()).HasSchedWith(rename.from.getNode(),
                                                             sched -> sched.HasAdjSig(nodeKey.getNode().getAdj())) &&
          !allISAXes.get(nodeKey.getISAX()).HasSchedWith(rename.to.getNode(),
                                                             sched -> sched.HasAdjSig(nodeKey.getNode().getAdj()))
          ) {
        //Nothing to rename.
        continue;
      }
      if (nodeKey.getNode().isInput && !nodeKey.getPurpose().matches(Purpose.WIREDIN))
        continue;
      PipelineStage stageOverride = null;
      if (regfile.issueFront.contains(nodeKey.getStage()) &&
          (nodeKey.getNode().getAdj() == AdjacentNode.addr || nodeKey.getNode().getAdj() == AdjacentNode.addrReq)) {
        // Special handling for issueFront.
        stageOverride = nodeKey.getStage();
      }
      // isInput (ISAX -> SCAL): Rename from non-port 'rename.from' to ported 'rename.to'
      //! isInput (SCAL -> ISAX): Rename from port 'rename.to' to ported 'rename.from'
      NodeInstanceDesc.Key fromWithAdj = new NodeInstanceDesc.Key(
          rename.from.getPurpose(), fromAdjNode_opt.get(),
          (stageOverride == null) ? rename.from.getStage() : stageOverride,
          rename.from.getISAX(), rename.from.getAux());
      NodeInstanceDesc.Key toWithAdj = new NodeInstanceDesc.Key(
          rename.to.getPurpose(), bNodes.GetAdjSCAIEVNode(rename.to.getNode(), nodeKey.getNode().getAdj()).orElseThrow(),
          (stageOverride == null) ? rename.to.getStage() : stageOverride,
          rename.to.getISAX(), rename.to.getAux());
      if (nodeKey.getNode().isSpawn() != fromWithAdj.getNode().isSpawn())
        continue;
      assert (fromWithAdj.getNode().isSpawn() == nodeKey.getNode().isSpawn());
      assert (toWithAdj.getNode().isSpawn() == nodeKey.getNode().isSpawn());
      NodeInstanceDesc.Key assignToKey = nodeKey.getNode().isInput ? toWithAdj : fromWithAdj;
      NodeInstanceDesc.Key assignFromKey = nodeKey.getNode().isInput
                                               ? fromWithAdj
                                               : NodeInstanceDesc.Key.keyWithPurpose(toWithAdj, Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN);
      boolean isValidAdj = nodeKey.getNode().isValidNode() && nodeKey.getNode().isInput;
      if (nodeKey.matches(NodeInstanceDesc.Key.keyWithPurpose(assignToKey, assignToKey.getPurpose().getRegisterAs()))) {
        out.accept(NodeLogicBuilder.fromFunction("SCALStateBuilder_RegportRename(" + nodeKey.toString() + ")", registry -> {
          var ret = new NodeLogicBlock();
          String assignToWireName = assignToKey.toString(false) + "_renamed_from_" + assignFromKey.toString(false) + "_s";
          ret.declarations += String.format("logic [%d-1:0] %s;\n", assignToKey.getNode().size, assignToWireName);
          NodeInstanceDesc assignFromNodeInst = null;
          if (nodeKey.getNode().isInput) {
            assignFromNodeInst = registry.lookupRequired(NodeInstanceDesc.Key.keyWithPurpose(assignFromKey, Purpose.match_WIREDIN_OR_PIPEDIN));
            if (assignFromNodeInst.getExpression().startsWith(NodeRegistry.MISSING_PREFIX))
              assignFromNodeInst = null;
          }
          if (assignFromNodeInst == null)
            assignFromNodeInst = registry.lookupRequired(assignFromKey);
          String assignFromExpr = assignFromNodeInst.getExpression();
          if (isValidAdj && !assignFromKey.getISAX().isEmpty() && allISAXes.containsKey(assignFromKey.getISAX()) &&
              !allISAXes.get(assignFromKey.getISAX()).HasNoOp() && !nodeKey.getNode().isSpawn()) {
            // Regular ISAXes need to be masked by RdIValid.
            assignFromExpr = String.format(
                "%s && %s", assignFromNodeInst.getExpressionWithParens(),
                registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, assignFromKey.getStage(), assignFromKey.getISAX()))
                    .getExpressionWithParens());
          }
          ret.logic += String.format("assign %s = %s;\n", assignToWireName, assignFromExpr);
          //TODO: It may be necessary to rename different Purpose nodes based on nodeKey.getPurpose().
          NodeInstanceDesc.Key actualAssignToKey = assignToKey;
          if (nodeKey.getNode().isInput)
            actualAssignToKey = NodeInstanceDesc.Key.keyWithPurpose(assignToKey, Purpose.WIREDIN);
          ret.outputs.add(new NodeInstanceDesc(actualAssignToKey, assignToWireName, ExpressionType.WireName));
          return ret;
        }));
        return true;
      }
    }
    return false;
  }

  boolean implementSingle(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey, boolean isLast, String regName) {
    if (implementSingle_RegportRename(out, nodeKey, isLast, regName))
      return true;

    SCAIEVNode baseNode = bNodes.GetNonAdjNode(nodeKey.getNode());
    
    if (nodeKey.getPurpose().matches(Purpose.REGULAR) && nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0 &&
        nodeKey.getNode().tags.contains(NodeTypeTag.isPortNode)) {
      // Since SCALStateStrategy renames operations to port nodes not represented in op_stage_instr and allISAXes,
      //  the standard ValidMuxStrategy won't work for the ports.
      // Instead, the following constructs a dedicated ValidMuxStrategy with op_stage_instr and allISAXes initialized accordingly.
      RegfileInfo regfile = stateStrategy.regfilesByName.get(regName);
      var portGroupUtils = new Object() {
        SCAIEVInstr portInstr(String name, SCAIEVInstr origInstr, RegfileInfo regfile) {
          assert (origInstr.GetName().equals(name));
          // Construct, set name and opcode
          SCAIEVInstr ret =
              new SCAIEVInstr(name, origInstr.GetEncodingF7(Lang.None), origInstr.GetEncodingF3(Lang.None),
                              origInstr.GetEncodingOp(Lang.None), origInstr.GetInstrType(), origInstr.GetEncodingConstRD(Lang.None));
          ret.SetAsDecoupled(origInstr.GetRunsAsDecoupled());
          ret.SetAsDynamic(origInstr.GetRunsAsDynamic());
          // Apply port renames on sched nodes
          var origSchedNodes = origInstr.GetSchedNodes();
          HashSet<SCAIEVNode> renamedNodes = new HashSet<>();
          for (var rename : regfile.portRenames) {
            if (rename.from.getISAX().equals(name) && rename.from.getAux() == 0) {
              renamedNodes.add(rename.from.getNode());
              for (var sched : origSchedNodes.getOrDefault(rename.from.getNode(), List.of())) {
                HashSet<AdjacentNode> newAdjacentSet =
                    Stream.of(AdjacentNode.values()).filter(adj -> sched.HasAdjSig(adj)).collect(Collectors.toCollection(HashSet::new));
                // There always is an address (explicitly represented in op_stage_instr)
                //  -> In this case, we skip adding the addr adjacent as a full Scheduled object (not needed)
                //newAdjacentSet.add(AdjacentNode.addr);
                HashMap<AdjacentNode, Integer> newAdjacentConstMap =
                    Stream.of(AdjacentNode.values())
                        .map(adj -> Map.entry(adj, sched.GetConstAdjSig(adj)))
                        .filter(adjAndConst -> adjAndConst.getValue() != -1)
                        .collect(Collectors.toMap(entry -> entry.getKey(), entry -> entry.getValue(), (a, b) -> {
                          if (a != null && b != null)
                            throw new IllegalStateException("unexpected duplicate in constAdj");
                          return a;
                        }, HashMap::new));
                var newSched = new Scheduled(sched.GetStartCycle(), newAdjacentSet, newAdjacentConstMap);
                sched.GetTags().forEach(tag -> newSched.AddTag(tag));
                ret.PutSchedNode(rename.to.getNode(), newSched);
              }
              assert(!rename.from.getNode().isAdj());
              //Add a separate addr entry (non-spawn).
              SCAIEVNode renameFrom_nonspawn = bNodes.GetEquivalentNonspawnNode(rename.from.getNode()).orElseThrow();
              SCAIEVNode renameTo_nonspawn = bNodes.GetEquivalentNonspawnNode(rename.to.getNode()).orElseThrow();
              SCAIEVNode addrNodeFrom = bNodes.GetAdjSCAIEVNode(renameFrom_nonspawn, AdjacentNode.addr).orElseThrow();
              renamedNodes.add(addrNodeFrom);
              SCAIEVNode addrNodeTo = bNodes.GetAdjSCAIEVNode(renameTo_nonspawn, AdjacentNode.addr).orElseThrow();
              for (var sched : origSchedNodes.getOrDefault(addrNodeFrom, List.of())) {
                HashSet<AdjacentNode> newAdjacentSet =
                    Stream.of(AdjacentNode.values()).filter(adj -> sched.HasAdjSig(adj)).collect(Collectors.toCollection(HashSet::new));
                var newSched = new Scheduled(sched.GetStartCycle(), newAdjacentSet, new HashMap<AdjacentNode, Integer>());
                sched.GetTags().forEach(tag -> newSched.AddTag(tag));
                ret.PutSchedNode(addrNodeTo, newSched);
              }
            }
          }
          //Transfer all untouched sched nodes to the returned instruction.
          origSchedNodes.entrySet().stream()
              .filter(nodeSchedlist -> !renamedNodes.contains(nodeSchedlist.getKey()))
              .forEach(nodeSchedlist -> nodeSchedlist.getValue().forEach(sched -> ret.PutSchedNode(nodeSchedlist.getKey(), sched)));
          return ret;
        }
      };
      if (regfile.portMuxGroups.stream().anyMatch(muxGroup -> muxGroup.groupKey.getNode().equals(baseNode))) {
        //TODO: Do this immediately after the MARKER_CUSTOM_REG nodes are processed
        if (regfile.portMuxStrategy == null) {
          HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> muxOpStageInstr = new HashMap<>();
          HashMap<String, SCAIEVInstr> portedISAXes = new HashMap<>();
          // Construct custom op_stage_instr and allISAXes for the new ValidMuxStrategy.
          //  -> only contains the renamed custom register ports.
          for (var muxGroup : regfile.portMuxGroups) {
            var stage_instr_map = muxOpStageInstr.computeIfAbsent(muxGroup.groupKey.getNode(), node_ -> new HashMap<>());
            var groupNonspawnNode = bNodes.GetEquivalentNonspawnNode(muxGroup.groupKey.getNode()).orElseThrow();
            var groupAddrNode = bNodes.GetAdjSCAIEVNode(groupNonspawnNode, AdjacentNode.addr).orElseThrow();
            var stage_instr_map_addr = muxOpStageInstr.computeIfAbsent(groupAddrNode, node_ -> new HashMap<>());
            for (var sourceKey : muxGroup.sourceKeys) {
              if (!sourceKey.getISAX().isEmpty()) {
                stage_instr_map.computeIfAbsent(sourceKey.getStage(), stage_ -> new HashSet<>()).add(sourceKey.getISAX());
                SCAIEVInstr origInstr = allISAXes.get(sourceKey.getISAX());
                if (origInstr != null) {
                  if (sourceKey.getNode().isSpawn()) {
                    PipelineStage spawnFromStage = spawn_instr_stage.getOrDefault(sourceKey.getNode(), new HashMap<>())
                                                                    .getOrDefault(sourceKey.getISAX(), null);
                    if (spawnFromStage != null) {
                      //NOTE: directly changes spawn_instr_stage
                      spawn_instr_stage.get(sourceKey.getNode()).remove(sourceKey.getISAX());
                      spawn_instr_stage.computeIfAbsent(muxGroup.groupKey.getNode(), node_ -> new HashMap<>())
                                       .put(sourceKey.getISAX(), spawnFromStage);
                    }
                  }
                  var sourceNonspawnNode = bNodes.GetEquivalentNonspawnNode(sourceKey.getNode()).orElseThrow();
                  var sourceAddrNode = bNodes.GetAdjSCAIEVNode(sourceNonspawnNode, AdjacentNode.addr).orElseThrow();
                  // Based on op_stage_instr, fill in the addr stage in stage_instr_map_addr
                  op_stage_instr.getOrDefault(sourceAddrNode, new HashMap<>())
                      .entrySet()
                      .stream()
                      .filter(stage_instrs -> stage_instrs.getValue().contains(sourceKey.getISAX()))
                      .map(stage_instrs -> stage_instrs.getKey())
                      .forEach(
                          addrStage -> stage_instr_map_addr.computeIfAbsent(addrStage, stage_ -> new HashSet<>()).add(sourceKey.getISAX()));
                  portedISAXes.computeIfAbsent(sourceKey.getISAX(), isaxName -> portGroupUtils.portInstr(isaxName, origInstr, regfile));
                } else {
                  assert (false); // Undefined behaviour (access without an associated ISAX)
                  stage_instr_map_addr.computeIfAbsent(sourceKey.getStage(), stage_ -> new HashSet<>()).add(sourceKey.getISAX());
                }
              }
            }
          }
          regfile.portMuxStrategy = new ValidMuxStrategy(language, bNodes, core, muxOpStageInstr, portedISAXes);
          
          //NOTE: directly changes op_stage_instr
          muxOpStageInstr.forEach((node, stage_instr_map) -> {
            var global_stage_instr_map = op_stage_instr.computeIfAbsent(node, node_ -> new HashMap<>());
            stage_instr_map.forEach((stage, instr_set) -> {
              var global_instr_set = global_stage_instr_map.computeIfAbsent(stage, stage_ -> new HashSet<>());
              global_instr_set.addAll(instr_set);
            });
          });
          //NOTE: directly changes allISAXes
          portedISAXes.forEach((instr_name, instr_ported) -> {
            allISAXes.put(instr_name, instr_ported);
          });          
        }
        //ListRemoveView<NodeInstanceDesc.Key> implementMux_RemoveView = new ListRemoveView<>(List.of(nodeKey));
        //regfile.portMuxStrategy.implement(out, implementMux_RemoveView, isLast);
        //if (implementMux_RemoveView.isEmpty()) {
        //  // regfile.portMuxStrategy removed the nodeKey from the list we created,
        //  //  so it thinks it can deliver this nodeKey.
        //  return true;
        //}
      }
    }
    
    return false;
  }
}
