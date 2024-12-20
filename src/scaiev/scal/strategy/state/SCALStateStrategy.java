package scaiev.scal.strategy.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
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
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;
import scaiev.frontend.Scheduled;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.scal.strategy.pipeline.NodeRegPipelineStrategy;
import scaiev.scal.strategy.standard.ValidMuxStrategy;
import scaiev.util.Lang;
import scaiev.util.ListRemoveView;
import scaiev.util.Log2;
import scaiev.util.Verilog;

/** Strategy implementing custom registers and data hazard logic. */
public class SCALStateStrategy extends MultiNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  private StrategyBuilders strategyBuilders;
  public BNode bNodes;
  private Verilog language;
  private Core core;
  private HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  private HashMap<String, SCAIEVInstr> allISAXes;

  /**
   * @param strategyBuilders The StrategyBuilders object to build sub-strategies with
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param allISAXes The ISAX descriptions
   */
  public SCALStateStrategy(StrategyBuilders strategyBuilders, Verilog language, BNode bNodes, Core core,
                           HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                           HashMap<String, SCAIEVInstr> allISAXes /*, PipelineFront commitStages*/) {
    this.strategyBuilders = strategyBuilders;
    this.bNodes = bNodes;
    this.language = language;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.allISAXes = allISAXes;
  }

  private static final SCAIEVNode RegfileModuleNode = new SCAIEVNode("__RegfileModule");
  private static final SCAIEVNode RegfileModuleSingleregNode = new SCAIEVNode("__RegfileModuleSinglereg");
  private static final Purpose ReglogicImplPurpose = new Purpose("ReglogicImpl", true, Optional.empty(), List.of());

  private static String makeRegModuleSignalName(String regName, String signalName, int portIdx) {
    return regName + "_" + signalName + (portIdx > 0 ? "_" + (portIdx + 1) : "");
  }

  enum DHForwardApproach {
    /** On read/write announcement, stall as long as the register is marked dirty */
    WaitUntilCommit,
    /** Combinational search in the write queue */
    WriteQueueSearch,
    /** Use a second register file containing pre-commit renames for forwarding purposes */
    RenamedRegfile
  }

  static class PortRename {
    NodeInstanceDesc.Key from;
    NodeInstanceDesc.Key to;
    public PortRename(NodeInstanceDesc.Key from, NodeInstanceDesc.Key to) {
      this.from = from;
      this.to = to;
    }
  }
  static class PortMuxGroup {
    /** Note: groupKey.getStage() is not relevant */
    NodeInstanceDesc.Key groupKey;
    List<NodeInstanceDesc.Key> sourceKeys = new ArrayList<>();
    public PortMuxGroup(NodeInstanceDesc.Key groupKey) { this.groupKey = groupKey; }
  }

  static class RegfileInfo {
    String regName = null;
    PipelineFront issueFront = new PipelineFront();
    PipelineFront commitFront = new PipelineFront();
    int width = 0, depth = 0;

    /** The list of reads before issueFront. */
    List<NodeInstanceDesc.Key> earlyReads = new ArrayList<>();
    /** The list of writes before issueFront. */
    List<NodeInstanceDesc.Key> earlyWrites = new ArrayList<>();
    /**
     * The list of writes in or after issueFront.
     * Each entry is the key to the Wr<CustomReg> node.
     * Stages with regular ISAXes have a combined entry (with ISAX field empty),
     *   while 'always'-ISAXes are listed as separate keys (with ISAX field set).
     * aux!=0 is (currently) used to ensure always/no-opcode ISAXes get dedicated ports
     */
    List<NodeInstanceDesc.Key> regularWrites = new ArrayList<>();
    /** The list of reads to perform in issueFront. */
    List<NodeInstanceDesc.Key> regularReads = new ArrayList<>();

    /** The port-grouped reads to perform in issueFront. The key stage has no meaning, the read should be done in all issueFront stages. */
    List<NodeInstanceDesc.Key> issue_reads = new ArrayList<>();

    /**
     * The remapped write, write_spawn node keys in the commit stage(s).
     * Priority is ascending, i.e. the last write has the highest priority.
     * For writes in different commit stages, the order depends on the instruction ID.
     */
    List<NodeInstanceDesc.Key> commit_writes = new ArrayList<>();

    /** The renames to apply from requested to scheduled reads/writes */
    List<PortRename> portRenames = new ArrayList<>();

    /**
     * Mux groups to implement manually, overriding ValidMuxStrategy.
     */
    List<PortMuxGroup> portMuxGroups = new ArrayList<>();

    /**
     * A custom ValidMuxStrategy used for portMuxGroups.
     * Will be created on first dependency on the group key or an adjacent node key.
     * <br/>
     * Note: Any changes to portMuxGroups once portMuxStrategy!=null are not applied,
     *  as ValidMuxStrategy triggering is not supported currently.
     */
    ValidMuxStrategy portMuxStrategy = null;

    /**
     * The approach to use in implementing the 'early' register file. Does not support WaitUntilCommit.
     * Note: Since early register writes have no requirement to announce the address in any specific stage front,
     *       potential RaW hazards have to be resolved by issuing pipeline flushes.
     *       In cases where one ISAX writes in the same stage as another reads (i.e. at least one 'always' ISAX), no data hazard safety is
     * provided. If two ISAXes write to the same register in an instruction lifetime, the later write will be selected; if both writes are
     * sent in the same stage, one of both will be selected (priority selected statically, with no guarantees)
     */
    DHForwardApproach earlyForwardApproach = DHForwardApproach.WaitUntilCommit;
    DHForwardApproach forwardApproach = DHForwardApproach.WaitUntilCommit;
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
  private void generatePortMapping(RegfileInfo regfileInfo) {
    // Undo renames to start from scratch
    undoRenames(regfileInfo.portRenames, regfileInfo.earlyReads);
    undoRenames(regfileInfo.portRenames, regfileInfo.earlyWrites);
    undoRenames(regfileInfo.portRenames, regfileInfo.regularReads);
    undoRenames(regfileInfo.portRenames, regfileInfo.regularWrites);
    regfileInfo.issue_reads.clear();
    regfileInfo.commit_writes.clear();
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
      /** Commit stages deemed potentially relevant, sorted by {@link PipelineStage#getSortKey()} */
      List<PipelineStage> relevantCommitStages;
      // public WrInstanceInfo(NodeInstanceDesc.Key key) {
      public WrInstanceInfo(List<NodeInstanceDesc.Key> fromList, int listIndex) {
        super(fromList, listIndex);
        boolean decoupled = key.getStage().getKind() == StageKind.Decoupled;
        Predicate<PipelineStage> stageIsRelevant =
            (stage
             -> (!fromRegularISAX || !stage.getTags().contains(StageTag.NoISAX)) // Ignore NoISAX stages for regular ISAXes
                    && (decoupled || stage.getKind() != StageKind.Decoupled));   // Ignore decoupled stages for non-decoupled ISAXes
        this.relevantCommitStages =
            key.getStage()
                .streamNext_bfs(nextStage
                                -> !regfileInfo.commitFront.contains(nextStage) &&
                                       stageIsRelevant.test(nextStage)) // Stop iterating past each first one, apply relevance criteria
                .filter(nextStage
                        -> regfileInfo.commitFront.contains(nextStage) &&
                               stageIsRelevant.test(nextStage)) // Only process commit stages, apply relevance criteria
                .sorted((a, b) -> Long.compare(a.getSortKey(), b.getSortKey()))
                .toList();
      }
      @Override
      public boolean isCompatible(InstanceInfo other) {
        return super.isCompatible(other) && (other instanceof WrInstanceInfo) &&
            this.relevantCommitStages.equals(((WrInstanceInfo)other).relevantCommitStages);
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
    //   -> same-node different-commit keys are put in different groups.
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
        NodeInstanceDesc.Key renameToKey = new NodeInstanceDesc.Key(groupKey.getPurpose(), groupKey.getNode(), instance.key.getStage(),
                                                                    instance.key.getISAX(), instance.key.getAux());
        regfileInfo.portRenames.add(new PortRename(instance.key, renameToKey));
        instance.sourceList.set(instance.sourceListIndex, renameToKey);
        if (instance.fromRegularISAX) {
          SCAIEVNode addrNode = bNodes.GetAdjSCAIEVNode(key.getNode(), AdjacentNode.addr).orElseThrow();
          // Also add renames in case the instruction provides the address earlier.
          //(using the same node for the rename, as only the base node is compared against)
          op_stage_instr.getOrDefault(addrNode, new HashMap<>())
              .entrySet()
              .stream()
              .filter(stage_instr -> stage_instr.getKey() != key.getStage() && stage_instr.getValue().contains(key.getISAX()))
              .map(stage_instr -> stage_instr.getKey())
              .forEach(addrStage -> {
                regfileInfo.portRenames.add(
                    new PortRename(new NodeInstanceDesc.Key(key.getPurpose(), key.getNode(), addrStage, key.getISAX(), key.getAux()),
                                   new NodeInstanceDesc.Key(renameToKey.getPurpose(), renameToKey.getNode(), addrStage,
                                                            renameToKey.getISAX(), renameToKey.getAux())));
              });
        }
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
    // else if write on commit: Higher stage numbers have priority (represent a later point in execution of an instruction), i.e. need a
    // higher port number

    Iterator<Entry<PipelineStage, List<WrInstanceInfo>>> iter_relevantWrNodesByStage_prioritySorted =
        Stream
            .concat(
                relevantWrNodesByStage.entrySet().stream().filter(
                    entry -> regfileInfo.commitFront.isAfter(entry.getKey(), false) && !regfileInfo.commitFront.isAround(entry.getKey())),
                relevantWrNodesByStage.entrySet().stream().filter(entry -> regfileInfo.commitFront.isAround(entry.getKey())))
            .iterator();
    int nextPriorityVal = 0;
    while (iter_relevantWrNodesByStage_prioritySorted.hasNext()) {
      Entry<PipelineStage, List<WrInstanceInfo>> entry = iter_relevantWrNodesByStage_prioritySorted.next();
      for (WrInstanceInfo writeInstance : entry.getValue()) {
        if (writeInstance.relevantCommitStages.isEmpty()) {
          logger.error("SCALStageStrategy - Found no matching commit stage (from {}) for {}",
                       regfileInfo.commitFront.asList().stream().map(stage -> stage.getName()).toList(), writeInstance.key.toString(false));
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
          nextPriorityVal++;

          NodeInstanceDesc.Key newGroupKey =
              new NodeInstanceDesc.Key(Purpose.REGULAR, renamedPortNode, writeInstance.key.getStage(), "", 0);
          var newMuxGroupEx = new MuxGroupDescEx(newGroupKey);
          writeMuxGroups.add(newMuxGroupEx);
          muxGroupEx = newMuxGroupEx;

          for (PipelineStage commitStage : writeInstance.relevantCommitStages) {
            regfileInfo.commit_writes.add(new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, newGroupKey.getNode(),
                                                                   commitStage, newGroupKey.getISAX(), newGroupKey.getAux()));
          }
        }

        var groupKey = muxGroupEx.muxGroup.groupKey;
        muxGroupEx.instances.add(writeInstance);
        NodeInstanceDesc.Key renameToKey = util.renameToGroup(groupKey, writeInstance);
        muxGroupEx.muxGroup.sourceKeys.add(writeInstance.key);
      }
    }
    writeMuxGroups.stream().forEach(muxGroupEx -> regfileInfo.portMuxGroups.add(muxGroupEx.muxGroup));

    List<String> cannotReachCommitKeyNames = relevantWrNodesByStage.entrySet()
                                                 .stream()
                                                 .filter(entry -> !regfileInfo.commitFront.isAroundOrAfter(entry.getKey(), false))
                                                 .flatMap(entry -> entry.getValue().stream())
                                                 .map(keyAndRef -> keyAndRef.key.toString(false))
                                                 .toList();
    if (!cannotReachCommitKeyNames.isEmpty()) {
      logger.error("SCALStageStrategy - Found Wr{} nodes that do not have a path to commit ({}): {}", regfileInfo.regName,
                   regfileInfo.commitFront.asList().stream().map(stage -> stage.getName()).toList(), cannotReachCommitKeyNames);
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

        regfileInfo.issue_reads.add(new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, newGroupKey.getNode(),
                                                             readInstance.key.getStage(), newGroupKey.getISAX(), newGroupKey.getAux()));
      }

      var groupKey = muxGroupEx.muxGroup.groupKey;
      muxGroupEx.instances.add(readInstance);
      NodeInstanceDesc.Key renameToKey = util.renameToGroup(groupKey, readInstance);
      muxGroupEx.muxGroup.sourceKeys.add(readInstance.key);

      if (hasGlobalReadRenamesFor.add(readInstance.key.getStage())) {
        // In case the ISAX uses the old-style shared interface name (shared between ISAXes), add an automatic rename.
        regfileInfo.portRenames.add(
            new PortRename(new NodeInstanceDesc.Key(readInstance.key.getNode(), readInstance.key.getStage(), ""),
                           new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, groupKey.getNode(),
                                                    readInstance.key.getStage(), groupKey.getISAX(), groupKey.getAux())));
      }
    }
    readMuxGroups.stream().forEach(muxGroupEx -> regfileInfo.portMuxGroups.add(muxGroupEx.muxGroup));
  }
  private RegfileInfo configureRegfile(String name, PipelineFront issueFront, PipelineFront commitFront, List<NodeInstanceDesc.Key> reads,
                                       List<NodeInstanceDesc.Key> writes, int width, int depth) {
    RegfileInfo ret = regfilesByName.get(name);
    if (ret == null) {
      ret = new RegfileInfo();
      ret.regName = name;
      ret.commitFront = commitFront;
      ret.width = width;
      ret.depth = depth;
      regfiles.add(ret);
      regfilesByName.put(name, ret);
    } else {
      assert (ret.commitFront.asList().equals(commitFront.asList()));
      assert (ret.width == width);
      assert (ret.depth == depth);
    }
    HashSet<PipelineStage> issueFrontStages =
        Stream.concat(ret.issueFront.asList().stream(), issueFront.asList().stream()).collect(Collectors.toCollection(HashSet::new));
    ret.issueFront = new PipelineFront(issueFrontStages);
    ret.earlyReads = Stream.concat(ret.earlyReads.stream(), reads.stream())
                         .filter(readKey -> issueFront.isAfter(readKey.getStage(), false))
                         .distinct()
                         .collect(Collectors.toCollection(ArrayList::new));
    ret.earlyWrites = Stream.concat(ret.earlyWrites.stream(), writes.stream())
                          .filter(writeKey -> issueFront.isAfter(writeKey.getStage(), false))
                          .distinct()
                          .collect(Collectors.toCollection(ArrayList::new));
    ret.regularReads = Stream.concat(ret.regularReads.stream(), reads.stream())
                           .filter(readKey -> issueFront.isAroundOrBefore(readKey.getStage(), false))
                           .distinct()
                           .collect(Collectors.toCollection(ArrayList::new));
    ret.regularWrites = Stream.concat(ret.regularWrites.stream(), writes.stream())
                            .filter(writeKey -> issueFront.isAroundOrBefore(writeKey.getStage(), false))
                            .distinct()
                            .collect(Collectors.toCollection(ArrayList::new));
    generatePortMapping(ret);
    return ret;
  }

  private ArrayList<RegfileInfo> regfiles = new ArrayList<>();
  private HashMap<String, RegfileInfo> regfilesByName = new HashMap<>();

  /**
   * Implementation logic for the early stage portions of an early read/write.
   * (From the issue stage onwards, the writes behave like a regular port and are handled in the core part of the implementation.)
   */
  class EarlyRWImplementation {
    String getEarlyDirtyName(RegfileInfo regfile) { return null; }
    boolean rerunPreissueOn(RegfileInfo regfile, SCAIEVNode writeNode) { return true; }
    /**
     * Returns the FF logic to reset the 'dirty for early read' flag,
     * or an empty string if the group of the write node is already being forwarded or handled another way.
     */
    String earlyDirtyFFResetLogic(NodeRegistryRO registry, RegfileInfo regfile, SCAIEVNode writeNode, String addr, String lineTabs) {
      // All hazards handled by WrRerunNext plus stalling from the general dirty reg
      return "";
    }
    /**
     * Returns the FF logic to set the 'dirty for early read' flag,
     * or an empty string if the group of the write node is already being forwarded or handled another way.
     */
    String earlyDirtyFFSetLogic(NodeRegistryRO registry, RegfileInfo regfile, SCAIEVNode writeNode, String addr, String lineTabs) {
      // All hazards handled by WrRerunNext plus stalling from the general dirty reg
      return "";
    }
    /**
     * Returns the (comb) logic for forwarding to an early read.
     * Can set rdata_outLogic and stallOutLogic with blocking assignments.
     */
    String earlyReadForwardLogic(NodeRegistryRO registry, RegfileInfo regfile, NodeInstanceDesc.Key earlyReadKey, String rdata_outLogic,
                                 String raddr, String stallOutLogic, String lineTabs) {
      return "";
    }
    NodeLogicBlock earlyRead(NodeRegistryRO registry, RegfileInfo regfile, List<Integer> auxReads, String regfilePin_rdata,
                             String regfilePin_re, String regfilePin_raddr, String dirtyRegName) {
      String tab = language.tab;
      int elements = regfile.depth;
      NodeLogicBlock ret = new NodeLogicBlock();
      for (int iEarlyRead = 0; iEarlyRead < regfile.earlyReads.size(); ++iEarlyRead) {
        int readPortn = regfile.issue_reads.size() + iEarlyRead;
        if (auxReads.size() <= readPortn)
          auxReads.add(registry.newUniqueAux());
        int auxRead = auxReads.get(readPortn);

        NodeInstanceDesc.Key requestedReadKey = regfile.earlyReads.get(iEarlyRead);
        PipelineStage earlyReadStage = requestedReadKey.getStage();

        String wireName_earlyRead = requestedReadKey.toString(false) + "_s";
        ret.declarations += String.format("logic [%d-1:0] %s;\n", regfile.width, wireName_earlyRead);
        ret.outputs.add(new NodeInstanceDesc(requestedReadKey, wireName_earlyRead, ExpressionType.WireName));

        String wireName_dhInEarlyStage = String.format("dhRd%s_%s_%d", regfile.regName, earlyReadStage.getName(), iEarlyRead);
        ret.declarations += String.format("logic %s;\n", wireName_dhInEarlyStage);

        String rdAddrExpr = (elements > 1) ? registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
                                                 bNodes.GetAdjSCAIEVNode(requestedReadKey.getNode(), AdjacentNode.addr).orElseThrow(),
                                                 earlyReadStage, requestedReadKey.getISAX()))
                                           : "0";
        String rdAddrValidExpr = registry.lookupExpressionRequired(
            new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(requestedReadKey.getNode(), AdjacentNode.addrReq).orElseThrow(),
                                     earlyReadStage, requestedReadKey.getISAX()));

        String readLogic = "";
        readLogic += "always_comb begin \n";
        if (!regfile.commit_writes.isEmpty()) {
          // Stall the early read stage if the requested register is marked dirty.
          assert (regfile.regularReads.stream().allMatch(regularKey -> regularKey.getStage() != earlyReadStage));
          readLogic += tab + String.format("%s = %s && %s[%s];\n", wireName_dhInEarlyStage, rdAddrValidExpr, dirtyRegName, rdAddrExpr);
        } else
          readLogic += tab + String.format("%s = 0;\n", wireName_dhInEarlyStage);
        readLogic +=
            tab + String.format("%s = %s;\n", wireName_earlyRead, makeRegModuleSignalName(regfile.regName, regfilePin_rdata, readPortn));
        readLogic += tab + String.format("%s = 1;\n", makeRegModuleSignalName(regfile.regName, regfilePin_re, readPortn));
        if (elements > 1)
          readLogic += tab + String.format("%s = 'x;\n", makeRegModuleSignalName(regfile.regName, regfilePin_raddr, readPortn));
        readLogic +=
            earlyReadForwardLogic(registry, regfile, requestedReadKey, wireName_earlyRead, rdAddrExpr, wireName_dhInEarlyStage, tab);
        readLogic += "end\n";
        ret.logic += readLogic;

        ret.outputs.add(
            new NodeInstanceDesc(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.REGULAR, bNodes.WrStall, earlyReadStage, "", auxRead),
                                 wireName_dhInEarlyStage, ExpressionType.WireName));
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, earlyReadStage, ""));
      }
      return ret;
    }
    NodeLogicBlock issueWriteToEarlyReadHazard(NodeRegistryRO registry, RegfileInfo regfile, int auxRerun) {
      NodeLogicBlock ret = new NodeLogicBlock();
      if (regfile.commit_writes.size() > 0 && regfile.earlyReads.size() > 0) {
        // Issue WrRerunNext in an issue stage if it is announcing an (upcoming) write.
        // This flushes out any possible RaW hazards with early reads.
        // Note: If there are several issue stages (-> multi-issue core),
        //  the WrRerunNext implementation of the core may have to flush concurrent issues depending on the logical instruction ordering.
        for (PipelineStage issueStage : regfile.issueFront.asList()) {
          String anyWriteInitiatedExpr =
              regfile.commit_writes.stream()
                  .filter(commitWriteKey -> rerunPreissueOn(regfile, commitWriteKey.getNode()))
                  .map(commitWriteKey -> {
                    SCAIEVNode commitWriteNode = commitWriteKey.getNode();
                    SCAIEVNode nonspawnWriteNode = bNodes.GetEquivalentNonspawnNode(commitWriteNode).orElse(commitWriteNode);
                    return registry
                        .lookupRequired(
                            new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(nonspawnWriteNode, AdjacentNode.addrReq).orElseThrow(),
                                                     issueStage, commitWriteKey.getISAX()))
                        .getExpressionWithParens();
                  })
                  .reduce((a, b) -> a + " || " + b)
                  .orElseThrow();

          String rerunNextWire = String.format("WrRerunNext_RegDH_%s_%s", regfile.regName, issueStage.getName());
          ret.declarations += String.format("logic %s;\n", rerunNextWire);
          ret.logic += String.format("assign %s = %s;\n", rerunNextWire, anyWriteInitiatedExpr);
          ret.outputs.add(
              new NodeInstanceDesc(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.REGULAR, bNodes.WrRerunNext, issueStage, "", auxRerun),
                                   rerunNextWire, ExpressionType.WireName));
          registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrRerunNext, issueStage, ""));
        }
      }
      return ret;
    }
  }
  class EarlyRWForwardingImplementation extends EarlyRWImplementation {
    @Override
    String getEarlyDirtyName(RegfileInfo regfile) {
      return String.format("%s_dirty_early", regfile.regName);
    }
    boolean isForwardedEarlyWrite(RegfileInfo regfile, SCAIEVNode writeNode) {
      // Check if the writeNode is from an early write, and forwarding is supported for the other present early writes and reads.
      if (regfile.earlyWrites.size() == 1 &&
          regfile.earlyWrites.stream().anyMatch(earlyWriteKey -> earlyWriteKey.getNode().equals(writeNode))) {
        PipelineStage writeStage = regfile.earlyWrites.get(0).getStage();
        PipelineFront writeFront = new PipelineFront(writeStage);
        if (writeStage.getTags().contains(StageTag.InOrder) &&
            regfile.earlyReads.stream().allMatch(earlyReadKey -> writeFront.isAroundOrBefore(earlyReadKey.getStage(), false)))
          return true;
      }
      return false;
    }
    @Override
    boolean rerunPreissueOn(RegfileInfo regfile, SCAIEVNode writeNode) {
      return !isForwardedEarlyWrite(regfile, writeNode);
    }
    @Override
    String earlyDirtyFFResetLogic(NodeRegistryRO registry, RegfileInfo regfile, SCAIEVNode writeNode, String addr, String lineTabs) {
      // All hazards handled by WrRerunNext plus stalling from the general dirty reg
      if (isForwardedEarlyWrite(regfile, writeNode))
        return "";
      return lineTabs + String.format("%s%s <= 0;\n", getEarlyDirtyName(regfile), (regfile.depth > 1 ? String.format("[%s]", addr) : ""));
    }
    @Override
    String earlyDirtyFFSetLogic(NodeRegistryRO registry, RegfileInfo regfile, SCAIEVNode writeNode, String addr, String lineTabs) {
      // All hazards handled by WrRerunNext plus stalling from the general dirty reg
      if (isForwardedEarlyWrite(regfile, writeNode))
        return "";
      return lineTabs + String.format("%s%s <= 1;\n", getEarlyDirtyName(regfile), (regfile.depth > 1 ? String.format("[%s]", addr) : ""));
    }

    @Override
    String earlyReadForwardLogic(NodeRegistryRO registry, RegfileInfo regfile, NodeInstanceDesc.Key earlyReadKey, String rdata_outLogic,
                                 String raddr, String stallOutLogic, String lineTabs) {
      if (regfile.earlyWrites.size() > 0 &&
          regfile.earlyWrites.stream().anyMatch(earlyWriteKey -> isForwardedEarlyWrite(regfile, earlyWriteKey.getNode()))) {
        // Forwarding
        String forwardNameBase = String.format("%s_earlyfwd", regfile.regName);
        String forwardBufferName = forwardNameBase + "_data";
        String forwardAddrName = forwardNameBase + "_addr";
        String forwardValidName = forwardNameBase + "_valid";
      }
      throw new RuntimeException("Not implemented");
      // return "";
    }
    @Override
    NodeLogicBlock earlyRead(NodeRegistryRO registry, RegfileInfo regfile, List<Integer> auxReads, String regfilePin_rdata,
                             String regfilePin_re, String regfilePin_raddr, String dirtyRegName) {
      String tab = language.tab;
      boolean hasForward = false;
      NodeLogicBlock ret = new NodeLogicBlock();
      if (regfile.earlyWrites.size() > 0 &&
          regfile.earlyWrites.stream().anyMatch(earlyWriteKey -> isForwardedEarlyWrite(regfile, earlyWriteKey.getNode()))) {
        hasForward = true;
        int forwardBufferDepth = Math.min(regfile.depth, 2);

        String forwardNextNameBase = String.format("%s_earlyfwd_next", regfile.regName);
        String forwardNextBufferName = forwardNextNameBase + "_data";
        String forwardNextAddrName = forwardNextNameBase + "_addr";
        String forwardNextValidName = forwardNextNameBase + "_valid";
        String forwardNextUpdateName = forwardNextNameBase + "_update";

        String forwardNameBase = String.format("%s_earlyfwd", regfile.regName);
        String forwardBufferName = forwardNameBase + "_data";
        String forwardAddrName = forwardNameBase + "_addr";
        String forwardValidName = forwardNameBase + "_valid";

        ret.declarations += String.format("logic [%d-1:0] %s [%d];\n", regfile.width, forwardBufferName, forwardBufferDepth);
        ret.declarations += String.format("logic [%d-1:0] %s [%d];\n", regfile.width, forwardNextBufferName, forwardBufferDepth);
        if (regfile.depth > 1) {
          ret.declarations += String.format("logic [$clog2(%d)-1:0] %s [%d];\n", regfile.depth, forwardAddrName, forwardBufferDepth);
          ret.declarations += String.format("logic [$clog2(%d)-1:0] %s [%d];\n", regfile.depth, forwardNextAddrName, forwardBufferDepth);
        }
        ret.declarations += String.format("logic %s [%d];\n", forwardValidName, forwardBufferDepth);
        ret.declarations += String.format("logic %s [%d];\n", forwardNextValidName, forwardBufferDepth);

        ret.declarations += String.format("logic %s [%d];\n", forwardNextUpdateName, forwardBufferDepth);

        ret.logic += String.format("always_ff @(posedge %s) begin : %s_ff_scope\n", language.clk, forwardNameBase) + tab +
                     String.format("for (int i = 0; i < %d; i=i+1) begin\n", forwardBufferDepth) + tab + tab +
                     String.format("if (%s[i]) begin\n", forwardNextUpdateName) + tab + tab + tab +
                     String.format("%s[i] <= %s[i];\n", forwardBufferName, forwardNextBufferName) + tab + tab + tab +
                     String.format("%s[i] <= %s[i];\n", forwardAddrName, forwardNextAddrName) + tab + tab + tab +
                     String.format("%s[i] <= %s[i];\n", forwardValidName, forwardNextValidName) + tab + tab + "end\n" + tab + "end\n" +
                     tab + String.format("if (%s) begin\n", language.reset) + tab + tab +
                     String.format("for (int iRst = 0; iRst < %d; iRst=iRst+1) begin\n", forwardBufferDepth) + tab + tab + tab +
                     String.format("%s[i] <= 0;\n", forwardValidName) + tab + tab + "end\n" + tab + "end\n"
                     + "end\n";

        // TODO: Proper flush tracking -
        //- follow the forward index through the pipeline; on flush in any stage, flush the forward index of that stage
        //- need special handling for non-continuous stages
        //- may need several cycles for this operation
        //-> should use a separate strategy for this purpose, so it can more easily work with different cores)
      }
      // TODO: Override read with forward
      ret.addOther(
          super.earlyRead(registry, regfile, auxReads, regfilePin_rdata, regfilePin_re, regfilePin_raddr, getEarlyDirtyName(regfile)));
      return ret;
    }
    @Override
    NodeLogicBlock issueWriteToEarlyReadHazard(NodeRegistryRO registry, RegfileInfo regfile, int auxRerun) {
      NodeLogicBlock ret = super.issueWriteToEarlyReadHazard(registry, regfile, auxRerun);
      ret.declarations += String.format("logic [%d-1:0] %s;\n", regfile.depth, getEarlyDirtyName(regfile));
      return ret;
    }
  }

  NodeLogicBlock buildRegfileLogic(NodeRegistryRO registry, int auxRerun, List<Integer> auxReads, List<Integer> auxWrites,
                                   RegfileInfo regfile, NodeInstanceDesc.Key nodeKey) {
    NodeLogicBlock ret = new NodeLogicBlock();
    assert (nodeKey.getNode().name.equals(regfile.regName));

    final String tab = language.tab;
    int elements = regfile.depth;

    final int addrSize = Log2.clog2(elements);
    final int numReadPorts = regfile.earlyReads.size() + regfile.issue_reads.size();
    final int numWritePorts = regfile.commit_writes.size();
    final List<SCAIEVNode> writeNodes = regfile.commit_writes.stream().map(key -> key.getNode()).distinct().toList();

    final String[] rSignals = {"raddr_i", "rdata_o", "re_i", "waddr_i", "wdata_i", "we_i"};
    int[] rSignalsize = {addrSize, regfile.width, 1, addrSize, regfile.width, 1};
    int[] rSignalcount = {numReadPorts, numReadPorts, numReadPorts, numWritePorts, numWritePorts, numWritePorts};
    final String rSignal_raddr = rSignals[0];
    final String rSignal_rdata = rSignals[1];
    final String rSignal_re = rSignals[2];
    final String rSignal_waddr = rSignals[3];
    final String rSignal_wdata = rSignals[4];
    final String rSignal_we = rSignals[5];

    EarlyRWImplementation earlyrw = new EarlyRWImplementation();

    String dirtyRegName = String.format("%s_dirty", regfile.regName);

    ret.declarations += String.format("reg [%d-1:0] %s;\n", elements, dirtyRegName);

    String regfileModuleName = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
        Purpose.MARKER_CUSTOM_REG, elements > 1 ? RegfileModuleNode : RegfileModuleSingleregNode, core.GetRootStage(), ""));

    for (int i = 0; i < rSignals.length; i++) {
      if (rSignalsize[i] == 0)
        continue;
      for (int j = 0; j < rSignalcount[i]; ++j) {
        ret.declarations += "logic[" + (rSignalsize[i] - 1) + ":0] " + makeRegModuleSignalName(regfile.regName, rSignals[i], j) + ";\n";
      }
    }
    ret.logic += regfileModuleName + " #(.DATA_WIDTH(" + regfile.width + ")" + (elements > 1 ? (",.ELEMENTS(" + elements + ")") : "") +
                 ",.RD_PORTS(" + numReadPorts + ")"
                 + ",.WR_PORTS(" + numWritePorts + "))"
                 + "reg_" + regfile.regName + "(\n";

    for (int i = 0; i < rSignals.length; i++) {
      if (rSignalsize[i] == 0)
        continue;
      String rSignal = rSignals[i];
      ret.logic += "." + rSignal + "('{";
      // Comma-separated port signal names from 0..rSignalcount[i]
      ret.logic += IntStream.range(0, rSignalcount[i])
                       .mapToObj(j -> makeRegModuleSignalName(regfile.regName, rSignal, j))
                       .reduce((a, b) -> a + "," + b)
                       .orElse("");
      ret.logic += "}),\n";
    }
    ret.logic += ".*);\n";

    var utils = new Object() {
      public String getWireName_dhInIssueStage(int iRead, PipelineStage issueStage) {
        return String.format("dhRd%s_%s_%d", regfile.regName, issueStage.getName(), iRead);
      }

      public String getWireName_WrIssue_addr_valid(int iPort) {
        return String.format("wrReg_%s_%d_issue_addr_valid", regfile.regName, iPort);
      }
      public String getWireName_WrIssue_addr(int iPort) { return String.format("wrReg_%s_%d_issue_addr", regfile.regName, iPort); }
    };

    for (int iRead = 0; iRead < regfile.issue_reads.size(); ++iRead) {
      if (auxReads.size() <= iRead)
        auxReads.add(registry.newUniqueAux());
      int auxRead = auxReads.get(iRead);

      NodeInstanceDesc.Key readGroupKey = regfile.issue_reads.get(iRead);
      assert (readGroupKey.getAux() == 0); // keys are supposed to be general

      //			//Read during the issue stage.
      //			//A typical scenario, given several issue stages, is that the requested read result is in an execution unit.
      //			//In that case, only one issue stage is able to issue to that execution unit; thus, we can mux the register
      // port across the issue stages.
      //			//The result will be pipelined if needed.
      //			boolean specificIssueStageOnly = regfile.issueFront.contains(requestedReadKey.getStage());

      for (PipelineStage issueStage : regfile.issueFront.asList()) {
        //				if (specificIssueStageOnly && issueStage != requestedReadKey.getStage())
        //					continue;
        String wireName_dhInAddrStage = utils.getWireName_dhInIssueStage(iRead, issueStage);
        ret.declarations += String.format("logic %s;\n", wireName_dhInAddrStage);
        // add a WrFlush signal (equal to the wrPC_valid signal) as an output with aux != 0
        // StallFlushDeqStrategy will collect this flush signal
        ret.outputs.add(
            new NodeInstanceDesc(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.REGULAR, bNodes.WrStall, issueStage, "", auxRead),
                                 wireName_dhInAddrStage, ExpressionType.WireName));
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, issueStage, ""));
      }

      NodeInstanceDesc.Key nodeKey_readInFirstIssueStage =
          new NodeInstanceDesc.Key(Purpose.REGULAR, readGroupKey.getNode(), regfile.issueFront.asList().get(0), readGroupKey.getISAX());
      String wireName_readInIssueStage = nodeKey_readInFirstIssueStage.toString(false) + "_s";
      ret.declarations += String.format("logic [%d-1:0] %s;\n", regfile.width, wireName_readInIssueStage);
      ret.outputs.add(new NodeInstanceDesc(nodeKey_readInFirstIssueStage, wireName_readInIssueStage, ExpressionType.WireName));

      String readLogic = "";
      readLogic += "always_comb begin \n";
      readLogic +=
          tab + String.format("%s = %s;\n", wireName_readInIssueStage, makeRegModuleSignalName(regfile.regName, rSignal_rdata, iRead));
      readLogic += tab + String.format("%s = 0;\n", makeRegModuleSignalName(regfile.regName, rSignal_re, iRead));
      if (elements > 1)
        readLogic += tab + tab + String.format("%s = 'x;\n", makeRegModuleSignalName(regfile.regName, rSignal_raddr, iRead));
      for (PipelineStage issueStage : regfile.issueFront.asList())
        readLogic += tab + String.format("%s = 0;\n", utils.getWireName_dhInIssueStage(iRead, issueStage));

      for (PipelineStage issueStage : regfile.issueFront.asList()) {
        //				if (specificIssueStageOnly && issueStage != requestedReadKey.getStage())
        //					continue;

        String wireName_dhInAddrStage = utils.getWireName_dhInIssueStage(iRead, issueStage);

        if (issueStage != nodeKey_readInFirstIssueStage.getStage()) {
          NodeInstanceDesc.Key nodeKey_readInIssueStage =
              new NodeInstanceDesc.Key(Purpose.REGULAR, readGroupKey.getNode(), issueStage, readGroupKey.getISAX());
          ret.outputs.add(new NodeInstanceDesc(nodeKey_readInIssueStage, wireName_readInIssueStage, ExpressionType.AnyExpression_Noparen));
        }

        // Get the early address field that all ISAXes should provide at this point.
        //  ValidMuxStrategy will implement the ISAX selection logic.
        String rdAddrExpr =
            (elements > 1)
                ? registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
                      bNodes.GetAdjSCAIEVNode(readGroupKey.getNode(), AdjacentNode.addr).orElseThrow(), issueStage, readGroupKey.getISAX()))
                : "0";
        String rdRegAddrValidExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
            bNodes.GetAdjSCAIEVNode(readGroupKey.getNode(), AdjacentNode.addrReq).orElseThrow(), issueStage, readGroupKey.getISAX()));

        readLogic += tab + "if (" + rdRegAddrValidExpr + ") begin\n";
        readLogic += tab + tab + "if (!" + makeRegModuleSignalName(regfile.regName, rSignal_re, iRead) + ") begin\n";
        readLogic += tab + tab + String.format("%s = 1;\n", makeRegModuleSignalName(regfile.regName, rSignal_re, iRead));
        if (elements > 1)
          readLogic += tab + tab + String.format("%s = %s;\n", makeRegModuleSignalName(regfile.regName, rSignal_raddr, iRead), rdAddrExpr);

        readLogic += tab + tab + String.format("%s = %s[%s] == 1;\n", wireName_dhInAddrStage, dirtyRegName, rdAddrExpr);
        readLogic += tab + tab + "end\n";
        readLogic += tab + tab + "else\n" + tab + tab + String.format("%s = 1;\n", wireName_dhInAddrStage);
        readLogic += tab + "end\n";

        // Forward early write -> read from current instruction.
        //  -> Early writes wander through the pipeline alongside an instruction.
        //     However, early writes also are meant to affect reads starting with that instruction,
        //      in contrast to regular writes that are only visible to the following instructions.
        for (int iEarlyWrite = 0; iEarlyWrite < regfile.earlyWrites.size(); ++iEarlyWrite) {
          NodeInstanceDesc.Key earlyWriteKey = regfile.earlyWrites.get(iEarlyWrite);
          assert (Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN.matches(regfile.earlyWrites.get(iEarlyWrite).getPurpose()));
          assert (regfile.earlyWrites.get(iEarlyWrite).getAux() == 0);
          int iWritePort = (int)regfile.commit_writes.stream().takeWhile(entry -> !entry.getNode().equals(earlyWriteKey.getNode())).count();
          String wrAddrValidWire = utils.getWireName_WrIssue_addr_valid(iWritePort);
          String wrAddrExpr = (elements > 1) ? utils.getWireName_WrIssue_addr(iWritePort) : "0";
          // The early write value is already known in the issue stage, and is pipelined just like the address.
          String earlyWriteVal_issue =
              registry.lookupExpressionRequired(new NodeInstanceDesc.Key(earlyWriteKey.getNode(), issueStage, earlyWriteKey.getISAX()));
          readLogic += tab + String.format("if (%s && %s == %s) begin\n", wrAddrValidWire, rdAddrExpr, wrAddrExpr);
          readLogic += tab + tab + String.format("%s = %s;\n", wireName_readInIssueStage, earlyWriteVal_issue);
          readLogic += tab + "end\n";
        }
      }
      readLogic += "end\n";
      ret.logic += readLogic;
    }
    // Early reads
    ret.addOther(earlyrw.earlyRead(registry, regfile, auxReads, rSignal_rdata, rSignal_re, rSignal_raddr, dirtyRegName));

    String wrDirtyLines = "";

    wrDirtyLines += String.format("always_ff @ (posedge %s) begin\n", language.clk);
    wrDirtyLines += tab + String.format("if(%s) begin \n", language.reset);
    wrDirtyLines += tab + tab + String.format("%s <= 0;\n", dirtyRegName);
    wrDirtyLines += tab + "end else begin\n";

    // Write commit logic (reset dirty bit, apply write to the register file)
    for (int iPort = 0; iPort < regfile.commit_writes.size(); ++iPort) {
      NodeInstanceDesc.Key wrBaseKey = regfile.commit_writes.get(iPort);
      assert (!wrBaseKey.getNode().isAdj());
      assert (wrBaseKey.getAux() == 0);
      assert (Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN.matches(wrBaseKey.getPurpose()));

      String wrDataExpr =
          registry.lookupExpressionRequired(new NodeInstanceDesc.Key(wrBaseKey.getNode(), wrBaseKey.getStage(), wrBaseKey.getISAX()));
      String wrValidExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
          bNodes.GetAdjSCAIEVNode(wrBaseKey.getNode(), AdjacentNode.validReq).orElseThrow(), wrBaseKey.getStage(), wrBaseKey.getISAX()));
      String wrCancelExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
          bNodes.GetAdjSCAIEVNode(wrBaseKey.getNode(), AdjacentNode.cancelReq).orElseThrow(), wrBaseKey.getStage(), wrBaseKey.getISAX()));
      String wrAddrCommitExpr = (elements > 1) ? registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
                                                     bNodes.GetAdjSCAIEVNode(wrBaseKey.getNode(), AdjacentNode.addr).orElseThrow(),
                                                     wrBaseKey.getStage(), wrBaseKey.getISAX()))
                                               : "";

      String stallDataStageCond = "1'b0";
      if (wrBaseKey.getStage().getKind() != StageKind.Decoupled) {
        stallDataStageCond = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, wrBaseKey.getStage(), ""));
        Optional<NodeInstanceDesc> wrstallDataStage =
            registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrStall, wrBaseKey.getStage(), ""));
        if (wrstallDataStage.isPresent())
          stallDataStageCond += " || " + wrstallDataStage.get().getExpression();
      }

      wrDirtyLines += tab + tab + String.format("if ((%s || %s) && !(%s)) begin\n", wrValidExpr, wrCancelExpr, stallDataStageCond);
      if (elements > 1)
        wrDirtyLines += tab + tab + tab + String.format("%s[%s] <= 0;\n", dirtyRegName, wrAddrCommitExpr);
      else
        wrDirtyLines += tab + tab + tab + String.format("%s <= 0;\n", dirtyRegName);
      wrDirtyLines += earlyrw.earlyDirtyFFResetLogic(registry, regfile, wrBaseKey.getNode(), wrAddrCommitExpr, tab + tab + tab);
      wrDirtyLines += tab + tab + "end\n";

      // Combinational validResp wire.
      // Expected interface behavior is to have the validResp registered, which is done based off of this wire.
      String validRespWireName = "";
      Optional<SCAIEVNode> wrValidRespNode_opt = bNodes.GetAdjSCAIEVNode(wrBaseKey.getNode(), AdjacentNode.validResp);
      if (wrValidRespNode_opt.isPresent()) {
        var validRespKey = new NodeInstanceDesc.Key(wrValidRespNode_opt.get(), wrBaseKey.getStage(), wrBaseKey.getISAX());
        validRespWireName = validRespKey.toString(false) + "_next_s";
        String validRespRegName = validRespKey.toString(false) + "_r";
        ret.declarations += String.format("logic %s;\n", validRespWireName);
        ret.declarations += String.format("logic %s;\n", validRespRegName);
        ret.outputs.add(new NodeInstanceDesc(validRespKey, validRespRegName, ExpressionType.WireName));
        // Also register the combinational validResp as an output (differentiated with MARKER_CUSTOM_REG),
        //  just to have a WireName node registered and get a free duplicate check.
        ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(validRespKey, Purpose.MARKER_CUSTOM_REG),
                                             validRespWireName, ExpressionType.WireName));

        ret.logic += language.CreateInAlways(true, String.format("%s <= %s;\n", validRespRegName, validRespWireName));
      }

      String wrDataLines = "";
      wrDataLines += "always_comb begin\n";
      if (elements > 1)
        wrDataLines += tab + String.format("%s = %d'dX;\n", makeRegModuleSignalName(regfile.regName, rSignal_waddr, iPort), addrSize);
      wrDataLines += tab + String.format("%s = %d'dX;\n", makeRegModuleSignalName(regfile.regName, rSignal_wdata, iPort), regfile.width);
      wrDataLines += tab + String.format("%s = 0;\n", makeRegModuleSignalName(regfile.regName, rSignal_we, iPort));
      if (wrValidRespNode_opt.isPresent())
        wrDataLines += tab + String.format("%s = 0;\n", validRespWireName);

      wrDataLines += tab + String.format("if (%s && !(%s)) begin\n", wrValidExpr, stallDataStageCond);
      if (elements > 1)
        wrDataLines +=
            tab + tab + String.format("%s = %s;\n", makeRegModuleSignalName(regfile.regName, rSignal_waddr, iPort), wrAddrCommitExpr);
      wrDataLines += tab + tab + String.format("%s = %s;\n", makeRegModuleSignalName(regfile.regName, rSignal_wdata, iPort), wrDataExpr);
      wrDataLines += tab + tab + String.format("%s = 1;\n", makeRegModuleSignalName(regfile.regName, rSignal_we, iPort));
      if (wrValidRespNode_opt.isPresent())
        wrDataLines += tab + tab + String.format("%s = 1;\n", validRespWireName);
      wrDataLines += tab + "end\n";

      wrDataLines += "end\n";
      ret.logic += wrDataLines;
    }

    // Write issue logic
    for (int iWriteNode = 0; iWriteNode < writeNodes.size(); ++iWriteNode) {
      if (auxWrites.size() <= iWriteNode)
        auxWrites.add(registry.newUniqueAux());
      int auxWrite = auxWrites.get(iWriteNode);

      SCAIEVNode commitWriteNode = writeNodes.get(iWriteNode);
      SCAIEVNode nonspawnWriteNode = bNodes.GetEquivalentNonspawnNode(commitWriteNode).orElse(commitWriteNode);
      int[] writePorts = IntStream.range(0, regfile.commit_writes.size())
                             .filter(iPort -> regfile.commit_writes.get(iPort).getNode().equals(commitWriteNode))
                             .toArray();
      assert (writePorts.length > 0);
      assert (IntStream.of(writePorts).mapToObj(iPort -> regfile.commit_writes.get(iPort).getISAX()).distinct().limit(2).count() ==
              1); // ISAX equal for all commit ports with this node
      // Note: nodeISAX is probably an empty string due to the port group assignment.
      String nodeISAX = regfile.commit_writes.get(writePorts[0]).getISAX();

      // Read during the issue stage.
      // A typical scenario, given several issue stages, is that the requested read result is in an execution unit.
      // In that case, only one issue stage is able to issue to that execution unit; thus, we can mux the register port across the issue
      // stages. The result will be pipelined if needed.
      int[] tmp_writeInIssuePorts =
          IntStream.of(writePorts).filter(iPort -> regfile.issueFront.contains(regfile.commit_writes.get(iPort).getStage())).toArray();
      Optional<NodeInstanceDesc.Key> specificIssueKey_opt =
          tmp_writeInIssuePorts.length == 1 ? Optional.of(regfile.commit_writes.get(tmp_writeInIssuePorts[0])) : Optional.empty();
      String isImmediateWriteCond = "1";
      if (specificIssueKey_opt.isPresent()) {
        isImmediateWriteCond =
            registry
                .lookupRequired(new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(commitWriteNode, AdjacentNode.validReq).orElseThrow(),
                                                         specificIssueKey_opt.get().getStage(), specificIssueKey_opt.get().getISAX()))
                .getExpressionWithParens();
      }

      String addrValidWire = utils.getWireName_WrIssue_addr_valid(iWriteNode);
      String addrWire = utils.getWireName_WrIssue_addr(iWriteNode);
      ret.declarations += String.format("logic %s;\n", addrValidWire);
      if (elements > 1)
        ret.declarations += String.format("logic [%d-1:0] %s;\n", addrSize, addrWire);
      wrDirtyLines += tab + tab + String.format("%s = 0;\n", addrValidWire);
      if (elements > 1)
        wrDirtyLines += tab + tab + String.format("%s = 'x;\n", addrWire);

      boolean nextIsElseif = false;
      for (PipelineStage issueStage : regfile.issueFront.asList()) {
        if (specificIssueKey_opt.isPresent() && issueStage != specificIssueKey_opt.get().getStage())
          continue;
        String stallAddrStageCond = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, issueStage, ""));
        Optional<NodeInstanceDesc> wrstallAddrStage =
            registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrStall, issueStage, ""));
        if (wrstallAddrStage.isPresent())
          stallAddrStageCond += " || " + wrstallAddrStage.get().getExpression();

        String wrAddrExpr = (elements > 1)
                                ? registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
                                      bNodes.GetAdjSCAIEVNode(nonspawnWriteNode, AdjacentNode.addr).orElseThrow(), issueStage, nodeISAX))
                                : "0";
        String wrAddrValidExpr = registry.lookupExpressionRequired(
            new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(nonspawnWriteNode, AdjacentNode.addrReq).orElseThrow(), issueStage, nodeISAX));

        {
          // Stall the address stage if a new write has an address marked dirty.
          String wireName_dhInAddrStage = String.format("dhWr%s_%s_%d", regfile.regName, issueStage.getName(), iWriteNode);
          ret.declarations += String.format("wire %s;\n", wireName_dhInAddrStage);
          ret.logic += String.format("assign %s = %s && %s[%s];\n", wireName_dhInAddrStage, wrAddrValidExpr, dirtyRegName, wrAddrExpr);
          ret.outputs.add(
              new NodeInstanceDesc(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.REGULAR, bNodes.WrStall, issueStage, "", auxWrite),
                                   wireName_dhInAddrStage, ExpressionType.WireName));
          registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, issueStage, ""));
        }

        wrDirtyLines +=
            tab + tab + (nextIsElseif ? "else " : "") + String.format("if (%s && !(%s)) begin\n", wrAddrValidExpr, stallAddrStageCond);
        wrDirtyLines += tab + tab + tab + String.format("%s = 1;\n", addrValidWire);
        if (elements > 1)
          wrDirtyLines += tab + tab + tab + String.format("%s = %s;\n", addrWire, wrAddrExpr);
        wrDirtyLines += tab + tab + "end\n";
        nextIsElseif = true;
      }

      wrDirtyLines += tab + tab + String.format("if (%s) begin\n", addrValidWire);
      if (elements > 1)
        wrDirtyLines += tab + tab + tab + String.format("%s[%s] <= %s;\n", dirtyRegName, addrWire, isImmediateWriteCond);
      else
        wrDirtyLines += tab + tab + tab + String.format("%s <= %s;\n", dirtyRegName, isImmediateWriteCond);
      wrDirtyLines += earlyrw.earlyDirtyFFSetLogic(registry, regfile, commitWriteNode, addrWire, tab + tab + tab);
      wrDirtyLines += tab + tab + "end\n";
    }

    wrDirtyLines += tab + "end\n";
    wrDirtyLines += "end\n";
    ret.logic += wrDirtyLines;

    ret.addOther(earlyrw.issueWriteToEarlyReadHazard(registry, regfile, auxRerun));

    ret.outputs.add(
        new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, ReglogicImplPurpose), "", ExpressionType.AnyExpression));
    return ret;
  }

  boolean implementSingle_ReglogicImpl(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey, boolean isLast) {
    if (!nodeKey.getPurpose().matches(ReglogicImplPurpose) || !nodeKey.getISAX().isEmpty() || nodeKey.getAux() != 0)
      return false;
    if (!regfilesByName.containsKey(nodeKey.getNode().name))
      return false;
    RegfileInfo regfile = regfilesByName.get(nodeKey.getNode().name);
    assert (regfile.regName.equals(nodeKey.getNode().name));
    var runtimeData = new Object() {
      List<Integer> auxReads = new ArrayList<>();
      List<Integer> auxWrites = new ArrayList<>();
    };
    out.accept(NodeLogicBuilder.fromFunction("SCALStateBuilder_RegfileLogic(" + nodeKey.toString() + ")", (registry, aux) -> {
      return buildRegfileLogic(registry, aux, runtimeData.auxReads, runtimeData.auxWrites, regfile, nodeKey);
    }));
    return true;
  }

  boolean implementSingle_RegportRename(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey, boolean isLast, String regName) {
    assert (bNodes.IsUserBNode(nodeKey.getNode()));
    RegfileInfo regfile = regfilesByName.get(regName);
    if (regfile == null)
      return false;
    for (var rename : regfile.portRenames) {
      var fromAdjNode_opt = bNodes.GetAdjSCAIEVNode(rename.from.getNode(), nodeKey.getNode().getAdj());
      if (fromAdjNode_opt.isEmpty())
        continue;
      // isInput (ISAX -> SCAL): Rename from non-port 'rename.from' to ported 'rename.to'
      //! isInput (SCAL -> ISAX): Rename from port 'rename.to' to ported 'rename.from'
      NodeInstanceDesc.Key fromWithAdj = new NodeInstanceDesc.Key(rename.from.getPurpose(), fromAdjNode_opt.get(), rename.from.getStage(),
                                                                  rename.from.getISAX(), rename.from.getAux());
      NodeInstanceDesc.Key toWithAdj = new NodeInstanceDesc.Key(
          rename.to.getPurpose(), bNodes.GetAdjSCAIEVNode(rename.to.getNode(), nodeKey.getNode().getAdj()).orElseThrow(),
          rename.to.getStage(), rename.to.getISAX(), rename.to.getAux());
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
          var assignFromNodeInst = registry.lookupRequired(assignFromKey);
          String assignFromExpr = assignFromNodeInst.getExpression();
          if (isValidAdj && !assignFromKey.getISAX().isEmpty() && allISAXes.containsKey(assignFromKey.getISAX()) &&
              !allISAXes.get(assignFromKey.getISAX()).HasNoOp()) {
            // Regular ISAXes need to be masked by RdIValid.
            assignFromExpr = String.format(
                "%s && %s", assignFromNodeInst.getExpressionWithParens(),
                registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, assignFromKey.getStage(), assignFromKey.getISAX()))
                    .getExpressionWithParens());
          }
          ret.logic += String.format("assign %s = %s;\n", assignToWireName, assignFromExpr);
          ret.outputs.add(new NodeInstanceDesc(assignToKey, assignToWireName, ExpressionType.WireName));
          return ret;
        }));
        return true;
      }
    }
    return false;
  }

  static class PipelineStrategyKey {
    public PipelineStage stage;
    public boolean flushToZero;
    public PipelineStrategyKey(PipelineStage stage, boolean flushToZero) {
      this.stage = stage;
      this.flushToZero = flushToZero;
    }
    @Override
    public int hashCode() {
      return Objects.hash(flushToZero, stage);
    }
    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      PipelineStrategyKey other = (PipelineStrategyKey)obj;
      return flushToZero == other.flushToZero && Objects.equals(stage, other.stage);
    }
  }

  HashMap<PipelineStrategyKey, MultiNodeStrategy> pipelineStrategyByPipetoStage = new HashMap<>();

  boolean implementSingle(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey, boolean isLast) {
    // Implement register logic
    if (implementSingle_ReglogicImpl(out, nodeKey, isLast))
      return true;

    // Check if the node refers to a register. Retrieve basic register information.
    if (!bNodes.IsUserBNode(nodeKey.getNode()))
      return false;
    assert (nodeKey.getNode().name.startsWith(BNode.rdPrefix) || nodeKey.getNode().name.startsWith(BNode.wrPrefix));
    SCAIEVNode baseNode = bNodes.GetNonAdjNode(nodeKey.getNode());
    var baseNodeNonspawn_opt = bNodes.GetEquivalentNonspawnNode(baseNode);
    if (baseNodeNonspawn_opt.isEmpty()) {
      assert (false);
      return false;
    }
    SCAIEVNode baseNodeNonspawn = baseNodeNonspawn_opt.get();
    String regName =
        (baseNodeNonspawn.tags.contains(NodeTypeTag.isPortNode) ? baseNodeNonspawn.nameParentNode : baseNodeNonspawn.name).substring(2);
    if (implementSingle_RegportRename(out, nodeKey, isLast, regName))
      return true;
    var addrNode_opt = bNodes.GetAdjSCAIEVNode(baseNodeNonspawn, AdjacentNode.addr);
    if (addrNode_opt.isEmpty())
      return false;

    SCAIEVNode addrNode = addrNode_opt.get();

    if (nodeKey.getPurpose().matches(Purpose.REGULAR) && nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0 &&
        nodeKey.getNode().tags.contains(NodeTypeTag.isPortNode)) {
      // Since SCALStateStrategy renames operations to port nodes not represented in op_stage_instr and allISAXes,
      //  the standard ValidMuxStrategy won't work for the ports.
      // Instead, the following constructs a dedicated ValidMuxStrategy with op_stage_instr and allISAXes initialized accordingly.
      RegfileInfo regfile = regfilesByName.get(regName);
      var portGroupUtils = new Object() {
        SCAIEVInstr portInstr(String name, SCAIEVInstr origInstr, RegfileInfo regfile) {
          assert (origInstr.GetName().equals(name));
          // Construct, set name and opcode
          SCAIEVInstr ret =
              new SCAIEVInstr(name, origInstr.GetEncodingF7(Lang.None), origInstr.GetEncodingF3(Lang.None),
                              origInstr.GetEncodingOp(Lang.None), origInstr.GetInstrType(), origInstr.GetEncodingConstRD(Lang.None));
          // Apply port renames on sched nodes
          var origSchedNodes = origInstr.GetSchedNodes();
          for (var rename : regfile.portRenames)
            if (rename.from.getISAX().equals(name) && rename.from.getAux() == 0) {
              var addrNode_opt = bNodes.GetAdjSCAIEVNode(rename.from.getNode(), AdjacentNode.addr);
              for (var sched : origSchedNodes.getOrDefault(rename.from.getNode(), List.of())) {
                HashSet<AdjacentNode> newAdjacentSet =
                    Stream.of(AdjacentNode.values()).filter(adj -> sched.HasAdjSig(adj)).collect(Collectors.toCollection(HashSet::new));
                // There always is an address (explicitly represented in op_stage_instr)
                //  -> In this case, we skip adding the addr adjacent as a full Scheduled object (not needed)
                newAdjacentSet.add(AdjacentNode.addr);
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
                ret.PutSchedNode(rename.to.getNode(), newSched);
              }
            }
          return ret;
        }
      };
      if (regfile.portMuxGroups.stream().anyMatch(muxGroup -> muxGroup.groupKey.getNode().equals(baseNode))) {
        if (regfile.portMuxStrategy == null) {
          HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> muxOpStageInstr = new HashMap<>();
          HashMap<String, SCAIEVInstr> portedISAXes = new HashMap<>();
          // Construct custom op_stage_instr and allISAXes for the new ValidMuxStrategy.
          //  -> only contains the renamed custom register ports.
          for (var muxGroup : regfile.portMuxGroups) {
            var stage_instr_map = muxOpStageInstr.computeIfAbsent(muxGroup.groupKey.getNode(), node_ -> new HashMap<>());
            var groupAddrNode = bNodes.GetAdjSCAIEVNode(muxGroup.groupKey.getNode(), AdjacentNode.addr).orElseThrow();
            var stage_instr_map_addr = muxOpStageInstr.computeIfAbsent(groupAddrNode, node_ -> new HashMap<>());
            for (var sourceKey : muxGroup.sourceKeys)
              if (!sourceKey.getISAX().isEmpty()) {
                stage_instr_map.computeIfAbsent(sourceKey.getStage(), stage_ -> new HashSet<>()).add(sourceKey.getISAX());
                SCAIEVInstr origInstr = allISAXes.get(sourceKey.getISAX());
                if (origInstr != null) {
                  var sourceAddrNode = bNodes.GetAdjSCAIEVNode(sourceKey.getNode(), AdjacentNode.addr).orElseThrow();
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
          regfile.portMuxStrategy = new ValidMuxStrategy(language, bNodes, core, muxOpStageInstr, portedISAXes);
        }
        ListRemoveView<NodeInstanceDesc.Key> implementMux_RemoveView = new ListRemoveView<>(List.of(nodeKey));
        regfile.portMuxStrategy.implement(out, implementMux_RemoveView, isLast);
        if (implementMux_RemoveView.isEmpty()) {
          // regfile.portMuxStrategy removed the nodeKey from the list we created,
          //  so it thinks it can deliver this nodeKey.
          return true;
        }
      }
    }

    if (nodeKey.getPurpose().matches(Purpose.PIPEDIN)) {
      // Check if a pipeline can/should be instantiated for the given nodeKey.
      RegfileInfo regfile = regfilesByName.get(regName);
      if (regfile == null)
        return false;
      boolean instantiatePipeline = false;
      if (!regfile.issueFront.isAroundOrAfter(nodeKey.getStage(), false) && !nodeKey.getNode().isSpawn() && isLast) {
        // Pipeline the read data or read/write address
        instantiatePipeline = true;
      }
      if (nodeKey.getNode().name.startsWith(BNode.wrPrefix) // && regfile.commitFront.isAroundOrAfter(nodeKey.getStage(), false)
          && regfile.earlyWrites.stream().anyMatch(
                 earlyWriteKey
                 -> earlyWriteKey.getNode().equals(baseNode) &&
                        (baseNode.tags.contains(NodeTypeTag.isPortNode) ||
                         earlyWriteKey.getISAX().equals(nodeKey.getISAX()) && earlyWriteKey.getAux() == nodeKey.getAux()) &&
                        new PipelineFront(earlyWriteKey.getStage()).isBefore(nodeKey.getStage(), false)) &&
          isLast) {
        assert (!nodeKey.getNode().isSpawn());
        // Pipeline the early write request to the issue front.
        instantiatePipeline = true;
      }
      if (instantiatePipeline) {
        boolean flushToZero = nodeKey.getNode().isValidNode() || nodeKey.getNode().getAdj() == AdjacentNode.cancelReq;
        var pipelineStrategy = pipelineStrategyByPipetoStage.computeIfAbsent(
            new PipelineStrategyKey(nodeKey.getStage(), flushToZero),
            strategyMapKey
            -> strategyBuilders.buildNodeRegPipelineStrategy(language, bNodes, new PipelineFront(nodeKey.getStage()), false, false,
                                                             strategyMapKey.flushToZero,
                                                             _nodeKey -> true, _nodeKey -> false, MultiNodeStrategy.noneStrategy));
        pipelineStrategy.implement(out, new ListRemoveView<>(List.of(nodeKey)), false);
        return true;
      }
      return false;
    }

    // From the present CustomReg_addr nodes, select those within the allowed bounds (constrained by RdInstr availability and
    // CustReg_addr_constraint).
    PipelineFront rs1Front = core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.RdInstr).GetEarliest());
    var addrPipelineFrontStream =
        op_stage_instr.getOrDefault(addrNode, new HashMap<>()).keySet().stream().filter(stage -> rs1Front.isAroundOrBefore(stage, false));
    var coreNode_CustRegAddr = core.GetNodes().get(bNodes.CustReg_addr_constraint);
    if (coreNode_CustRegAddr != null) {
      PipelineFront regAddrEarlyFront = core.TranslateStageScheduleNumber(coreNode_CustRegAddr.GetEarliest());
      PipelineFront regAddrLateFront = core.TranslateStageScheduleNumber(coreNode_CustRegAddr.GetLatest());
      addrPipelineFrontStream = addrPipelineFrontStream.filter(
          stage -> regAddrEarlyFront.isAroundOrBefore(stage, false) && regAddrLateFront.isAroundOrAfter(stage, false));
    }
    final PipelineFront addrPipelineFront = new PipelineFront(addrPipelineFrontStream);

    if (nodeKey.getPurpose().matches(Purpose.MARKER_CUSTOM_REG)) {
      if (addrPipelineFront.asList().isEmpty()) {
        logger.error("Custom registers: Found no address/issue stage for {}", baseNode.name);
        return true;
      }
      final PipelineFront commitFront = new PipelineFront(core.GetRootStage().getAllChildren().filter(
          stage -> stage.getTags().contains(StageTag.Commit) && addrPipelineFront.isAroundOrBefore(stage, false)));
      boolean isRead = nodeKey.getNode().name.startsWith("Rd");
      List<NodeInstanceDesc.Key> opKeys = op_stage_instr.getOrDefault(nodeKey.getNode(), new HashMap<>())
                                              .entrySet()
                                              .stream()
                                              .flatMap(stage_instr
                                                       -> stage_instr.getValue().stream().map(
                                                           isax -> new NodeInstanceDesc.Key(nodeKey.getNode(), stage_instr.getKey(), isax)))
                                              .toList();
      RegfileInfo regfile = configureRegfile(regName, addrPipelineFront, commitFront, isRead ? opKeys : new ArrayList<>(),
                                             isRead ? new ArrayList<>() : opKeys, baseNode.size, baseNode.elements);
      out.accept(NodeLogicBuilder.fromFunction("SCALStateBuilder_Marker(" + nodeKey.toString() + ")", registry -> {
        var ret = new NodeLogicBlock();
        // Request register logic implementation.
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(ReglogicImplPurpose, new SCAIEVNode(regName), core.GetRootStage(), ""));

        ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.MARKER_CUSTOM_REG), "",
                                             ExpressionType.AnyExpression));
        return ret;
      }));
      return true;
    }
    return false;
  }

  private HashSet<SCAIEVNode> implementedRegfileModules = new HashSet<>();

  private boolean implementRegfileModule(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
    if (nodeKey.getPurpose().matches(Purpose.MARKER_CUSTOM_REG) && nodeKey.getStage().getKind() == StageKind.Root &&
        (nodeKey.getNode().equals(RegfileModuleNode) || nodeKey.getNode().equals(RegfileModuleSingleregNode))) {
      if (nodeKey.getAux() != 0 || !nodeKey.getISAX().isEmpty())
        return false;
      if (!implementedRegfileModules.add(nodeKey.getNode()))
        return true;

      boolean readDefaultX = false; // TODO: Make configurable, may save a tiny bit of logic in a potentially critical path

      boolean singleVariant = nodeKey.getNode().equals(RegfileModuleSingleregNode);
      out.accept(NodeLogicBuilder.fromFunction("SCALStateStrategy_RegfileModule", registry -> {
        var ret = new NodeLogicBlock();
        String moduleName = singleVariant ? "svsinglereg" : "svregfile";
        ret.otherModules += "module " + moduleName + " #(\n"
                            + "    parameter int unsigned DATA_WIDTH = 32,\n" +
                            (singleVariant ? "" : "    parameter int unsigned ELEMENTS = 4,\n") +
                            "    parameter int unsigned RD_PORTS = 1,\n"
                            + "    parameter int unsigned WR_PORTS = 2\n"
                            + ")(\n"
                            + "    input  logic                          clk_i,\n"
                            + "    input  logic                          rst_i,\n" +
                            (singleVariant ? "" : "    input  logic [$clog2(ELEMENTS)-1:0]   raddr_i [RD_PORTS],\n") +
                            "    output logic [DATA_WIDTH-1:0]         rdata_o [RD_PORTS],\n"
                            + "    input  logic                          re_i [RD_PORTS],\n" +
                            (singleVariant ? "" : "    input  logic [$clog2(ELEMENTS)-1:0]   waddr_i [WR_PORTS],\n") +
                            "    input  logic [DATA_WIDTH-1:0]         wdata_i [WR_PORTS],\n"
                            + "    input  logic                          we_i [WR_PORTS]\n"
                            + "    );\n" + (singleVariant ? "    localparam ELEMENTS = 1;\n" : "") + "    reg " +
                            (singleVariant ? "" : "[ELEMENTS-1:0]") + "[DATA_WIDTH-1:0] regfile;\n"
                            + "    always_ff @(posedge clk_i, posedge rst_i) begin : write_svreg\n"
                            + "        if(rst_i) begin\n"
                            + "            for (int unsigned j = 0; j < ELEMENTS; j++) begin\n"
                            + "                regfile" + (singleVariant ? "" : "[j]") + " <= 0;\n"
                            + "            end\n"
                            + "        end else begin\n"
                            + "            for (int unsigned j = 0; j < WR_PORTS; j++) begin\n"
                            + "                if(we_i[j] == 1) begin\n"
                            + "                    regfile" + (singleVariant ? "" : "[waddr_i[j]]") + " <= wdata_i[j];\n"
                            + "                end\n"
                            + "            end\n"
                            + "        end\n"
                            + "        \n"
                            + "    end\n"
                            + "    \n"
                            + "    generate\n"
                            + "    for (genvar iRead = 0; iRead < RD_PORTS; iRead++) begin\n"
                            + "        assign rdata_o[iRead] = re_i[iRead] ? regfile" + (singleVariant ? "" : "[raddr_i[iRead]]") +
                            " : " + (readDefaultX ? "'x" : "'0") + ";\n"
                            + "    end\n"
                            + "    endgenerate\n"
                            + "endmodule\n";
        ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.MARKER_CUSTOM_REG, nodeKey.getNode(), nodeKey.getStage(), ""),
                                             moduleName, ExpressionType.WireName));
        return ret;
      }));
      return true;
    }
    return false;
  }

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    Iterator<NodeInstanceDesc.Key> nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      var nodeKey = nodeKeyIter.next();
      if (implementRegfileModule(out, nodeKey) || implementSingle(out, nodeKey, isLast)) {
        nodeKeyIter.remove();
      }
    }
  }
}
