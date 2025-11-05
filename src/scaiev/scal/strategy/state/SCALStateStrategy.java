package scaiev.scal.strategy.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
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
import scaiev.frontend.SCAL;
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
import scaiev.scal.SCALUtil;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.scal.strategy.pipeline.IDRetireSerializerStrategy;
import scaiev.scal.strategy.pipeline.NodeRegPipelineStrategy;
import scaiev.scal.strategy.standard.ValidMuxStrategy;
import scaiev.scal.strategy.state.SCALStateStrategy_IDBasedDHCommit.DH_IDMapping;
import scaiev.scal.strategy.state.SCALStateStrategy_IDBasedDHCommit.IDBasedDHCommitHandler;
import scaiev.ui.SCAIEVConfig;
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
  private HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage;
  private HashMap<String, SCAIEVInstr> allISAXes;
  private SCAIEVConfig cfg;
  private Optional<IDRetireSerializerStrategy> retireSerializer_opt;

  /**
   * @param strategyBuilders The StrategyBuilders object to build sub-strategies with
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param spawn_instr_stage The Node-ISAX-Stage mapping providing the precise sub-pipeline stage for spawn operations
   * @param allISAXes The ISAX descriptions
   * @param cfg The SCAIE-V global config
   * @param retireSerializer_opt the serializer for instruction retires (required for cores with an Issue-tagged stage, can be empty for most MCU-class cores)
   */
  public SCALStateStrategy(StrategyBuilders strategyBuilders, Verilog language, BNode bNodes, Core core,
                           HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                           HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage,
                           HashMap<String, SCAIEVInstr> allISAXes, SCAIEVConfig cfg,
                           Optional<IDRetireSerializerStrategy> retireSerializer_opt) {
    this.strategyBuilders = strategyBuilders;
    this.bNodes = bNodes;
    this.language = language;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.spawn_instr_stage = spawn_instr_stage;
    this.allISAXes = allISAXes;
    this.cfg = cfg;
    this.retireSerializer_opt = retireSerializer_opt;
  }

  private static final SCAIEVNode RegfileModuleNode = new SCAIEVNode("__RegfileModule");
  private static final SCAIEVNode RegfileModuleSingleregNode = new SCAIEVNode("__RegfileModuleSinglereg");
  private static final Purpose ReglogicImplPurpose = new Purpose("ReglogicImpl", true, Optional.empty(), List.of());

  protected SCALStateStrategy_PortMapping portMapper;
  protected SCALStateStrategy_PortMapping makePortMapper() {
    return new SCALStateStrategy_PortMapping(this, strategyBuilders, language, bNodes, core,
                                             op_stage_instr, spawn_instr_stage, allISAXes, cfg);
  }
  protected SCALStateStrategy_PortMapping getPortMapper() {
    if (portMapper == null)
      portMapper = makePortMapper();
    return portMapper;
  }


  EarlyRWImplementation makeEarlyRW(RegfileInfo regfile) {
    if (regfile.earlyReads.size() > 0 && regfile.depth == 1)
      return new EarlyRWSimpleForwardingImplementation(this, language, bNodes, core);
    return new EarlyRWImplementation(this, language, bNodes, core);
  }

  public String makeRegModuleSignalName(String regName, String signalName, int portIdx) {
    return regName + "_" + signalName + (portIdx > 0 ? "_" + (portIdx + 1) : "");
  }

  protected class RegInternalUtils {
    RegfileInfo regfile;
    List<SCAIEVNode> writeNodes;
    RegInternalUtils(RegfileInfo regfile) {
      this.regfile = regfile;
      writeNodes = regfile.writeback_writes.stream().map(key -> key.getNode()).distinct().toList();
    }
    public String getWireName_dhInIssueStage(int iRead, PipelineStage issueStage) {
      return String.format("dhRd%s_%s_%d", regfile.regName, issueStage.getName(), iRead);
    }

    public String getWireName_WrIssue_addr_valid(int iPort) {
      return String.format("wrReg_%s_%d_issue_addr_valid", regfile.regName, iPort);
    }
    public String getWireName_WrIssue_addr_valid_perstage(int iPort, PipelineStage issueStage) {
      return String.format("wrReg_%s_%d_issue_addr_valid_%s", regfile.regName, iPort, issueStage.getName());
    }
    public String getWireName_WrIssue_addr(int iPort) { return String.format("wrReg_%s_%d_issue_addr", regfile.regName, iPort); }
  }

  abstract protected static class CommitHandler {
    /**
     * Resets the state of the commit handler
     * ({@link #getResetDirtyConds()}, {@link #getWriteToBackingConds()})
     * for another builder invocation.
     */
    abstract void reset();

    /** Processes a write port. Adds assign logic for 'validRespWireName'. */
    abstract void processWritePort(int iPort, WritebackExpr writeback, NodeRegistryRO registry, NodeLogicBlock logicBlock);
    /** All 'reset dirty' conditions from the processWritePort calls since the last reset. */
    abstract List<ResetDirtyCond> getResetDirtyConds();
    /** All writeback conditions from the processWritePort calls since the last reset. */
    abstract List<WriteToBackingCond> getWriteToBackingConds();

    /** Performs an additional build step after all processWritePort calls have been made. */
    void buildPost(NodeRegistryRO registry, NodeLogicBlock logicBlock) {}

    /** Implements any inner strategies */
    void implementInner(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {}
  }

  abstract protected static class ReadForwardHandler {
    /**
     * Resets the state of the forward handler
     * ({@link #getForwardConds()})
     * for another builder invocation.
     */
    abstract void reset();

    /**
     * Processes a read port.
     * Called after all calls to CommitHandler.processWritePort.
     * Only called for ports that have forwarding enabled.
     */
    abstract void processReadPort(int iPort, ReadreqExpr readRequest, NodeRegistryRO registry, NodeLogicBlock logicBlock);
    /** All forward conditions from the processReadPort calls since the last reset. */
    abstract List<ReadForwardCond> getForwardConds();

    /** Performs an additional build step after all processReadPort calls have been made. */
    void buildPost(NodeRegistryRO registry, NodeLogicBlock logicBlock) {}

    /** Implements any inner strategies */
    void implementInner(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {}
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

  record EarlyWritePipelinerEntry(
      /** The stage that the pipeliner pipelines to */
      PipelineStage pipetoStage,
      /** The ported write node (non-adj) being pipelined*/
      SCAIEVNode earlyWritePortNode,
      /** The pipeliner */
      NodeRegPipelineStrategy pipeliner) {}
  record IssueReadEntry(NodeInstanceDesc.Key readKey, boolean disableForwarding) {}

  static class RegfileInfo {
    String regName = null;
    PipelineFront issueFront = new PipelineFront();
    PipelineFront writebackFront = new PipelineFront();
    int width = 0, depth = 0;

    /** The list of reads before issueFront. */
    List<NodeInstanceDesc.Key> earlyReads = new ArrayList<>();
    /** A version of {@link RegfileInfo#earlyReads} before port mapping. */
    List<NodeInstanceDesc.Key> earlyReadsUnmapped = new ArrayList<>();

    /** The list of writes before issueFront. */
    List<NodeInstanceDesc.Key> earlyWrites = new ArrayList<>();
    /** The list of pipeliners for early write requests */
    List<EarlyWritePipelinerEntry> earlyWriteValidPipeliners = new ArrayList<>();
    /** A version of {@link RegfileInfo#earlyWrites} before port mapping. */
    List<NodeInstanceDesc.Key> earlyWritesUnmapped = new ArrayList<>();

    /**
     * The list of writes in or after issueFront.
     * Each entry is the key to the Wr&lt;CustomReg&gt; node.
     * Stages with regular ISAXes have a combined entry (with ISAX field empty),
     *   while 'always'-ISAXes are listed as separate keys (with ISAX field set).
     * aux!=0 is (currently) used to ensure always/no-opcode ISAXes get dedicated ports
     */
    List<NodeInstanceDesc.Key> regularWrites = new ArrayList<>();
    /** A version of {@link RegfileInfo#regularWrites} before port mapping. */
    List<NodeInstanceDesc.Key> regularWritesUnmapped = new ArrayList<>();

    /** The list of reads to perform in issueFront. */
    List<NodeInstanceDesc.Key> regularReads = new ArrayList<>();
    /** A version of {@link RegfileInfo#regularReads} before port mapping. */
    List<NodeInstanceDesc.Key> regularReadsUnmapped = new ArrayList<>();

    /** The port-grouped reads to perform in issueFront. The key stage has no meaning, the read should be done in all issueFront stages. */
    List<IssueReadEntry> issue_reads = new ArrayList<>();

    /**
     * The remapped write, write_spawn node keys in the writeback stage(s).
     * Priority is ascending, i.e. the last write has the highest priority.
     * For writes in different writeback stages, the order depends on the instruction ID.
     */
    List<NodeInstanceDesc.Key> writeback_writes = new ArrayList<>();

    /**
     * The commit handlers, listed by writeback port.
     */
    List<CommitHandler> commit_handlers_by_writeport = new ArrayList<>();

    /**
     * The list of unique commit handlers. Must contain all handlers from {@link #commit_handlers_by_writeport} and must not contain duplicates.
     */
    List<CommitHandler> unique_commit_handlers = new ArrayList<>();

    /**
     * The list of read forward handlers. Must not contain duplicates.
     */
    List<ReadForwardHandler> read_forward_handlers = new ArrayList<>();

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
     * If set, will write into a scoreboard before writing back to the register file.
     * This mode tracks the commit/flush state and discards the result on flush.
     * This mode also ensures the 'dirty' flag is reset correctly on flush.
     */
    boolean useScoreboard = true; //TODO: make configurable

    /**
     * The utils object for this register file.
     */
    RegInternalUtils utils = null;
  }

  /** Information on a write request source */
  protected static class WritebackExpr {
    /** The (ported) write node of this request */
    SCAIEVNode baseNode;
    /** The pipeline stage of this request */
    PipelineStage stage;
    /** Whether the write request is valid */
    String wrValidExpr;
    /** Whether the write request is being cancelled */
    String wrCancelExpr;
    /** The register address to write to */
    String wrAddrExpr;
    /** The data to write */
    String wrDataExpr;
    /** Whether the stage the write request comes from is stalling */
    String stallDataStageCond;
    /** Whether the stage the write request comes from is being flushed */
    String flushDataStageCond;
    /** */
    String validRespWireName;
  }
  /** Condition and expressions for when to reset the 'dirty flag' for a register */
  protected static class ResetDirtyCond {
    /** The (ported) write node which completed, or null if it is not clear */
    SCAIEVNode baseNode;
    /** The 'reset dirty flag' condition */
    String condExpr;
    /** The address corresponding to the condition */
    String addrExpr;
  }
  /** Condition and expressions for when to write back the register */
  protected static class WriteToBackingCond {
    /** The writeback condition */
    String condExpr;
    /** The writeback register address */
    String addrExpr;
    /** The writeback data */
    String dataExpr;
    /** The write port index (physical register file == {@link RegfileInfo#writeback_writes} index) */
    int iPort;
  }
  /** Information on a read request */
  protected static record ReadreqExpr (
    /** The key of the read group (shared across regfile.issueFront) */
    NodeInstanceDesc.Key readGroupKey,
    /** The 'address valid' wire name */
    String addrValidExpr,
    /** The address wire name */
    String addrExpr
  ) {}
  /** Information on a read port, internally used for forwarding */
  private static record ForwardToWires (
    /** The wire to which to write the 'forward valid' flag to */
    String forwardValidWire,
    /** The wire to which to write the forwarded data to */
    String forwardDataWire
  ) {}
  /** Condition and expressions from the forwarder */
  protected static record ReadForwardCond (
    /** The 'forward valid' expression */
    String dataValidExpr,
    /** The 'forward data' expression */
    String dataExpr,
    /** The read port index to forward to */
    int iPort
  ) {}

  class DefaultCommitHandler extends CommitHandler {
    List<ResetDirtyCond> resetDirtyConds = new ArrayList<>();
    List<WriteToBackingCond> writeToBackingConds = new ArrayList<>();
    @Override
    void reset() {
      resetDirtyConds.clear();
      writeToBackingConds.clear();
    }
    /** Processes a write port. Returns the 'validResp' condition. */
    @Override
    void processWritePort(int iPort, WritebackExpr writeback, NodeRegistryRO registry, NodeLogicBlock logicBlock) {
      //Reset dirty whenever a write request is valid or being cancelled (or flushed)
      ResetDirtyCond dirtyCond = new ResetDirtyCond();
      dirtyCond.baseNode = writeback.baseNode;
      dirtyCond.condExpr = String.format("(%s || %s) && !(%s)", writeback.wrValidExpr, writeback.wrCancelExpr, writeback.stallDataStageCond);
      dirtyCond.addrExpr = writeback.wrAddrExpr;
      resetDirtyConds.add(dirtyCond);
      //Write back whenever a write request is valid and not being flushed
      WriteToBackingCond writeCond = new WriteToBackingCond();
      writeCond.condExpr = String.format("%s && !(%s) && !(%s)", writeback.wrValidExpr, writeback.stallDataStageCond, writeback.flushDataStageCond);
      writeCond.addrExpr = writeback.wrAddrExpr;
      writeCond.dataExpr = writeback.wrDataExpr;
      writeCond.iPort = iPort;
      writeToBackingConds.add(writeCond);
      if (!writeback.validRespWireName.isEmpty())
        logicBlock.logic += String.format("assign %s = %s && !(%s);\n", writeback.validRespWireName, dirtyCond.condExpr, writeback.flushDataStageCond);
    }
    @Override
    List<ResetDirtyCond> getResetDirtyConds() {
      return resetDirtyConds;
    }
    @Override
    List<WriteToBackingCond> getWriteToBackingConds() {
      return writeToBackingConds;
    }
  }

  /**
   * Selects/constructs the CommitHandlers to use for each writeback port (regfile.writebackWrites)
   *  and the corresponding ReadForwardHandlers (if any).
   * Sets up regfile.commit_handlers_by_writeport and regfile.read_forward_handlers.
   * Returns immediately if commit_handlers_by_writeport is filled already.
   */
  void setupCommitAndForwardHandlers(RegfileInfo regfile) {
    assert(regfile.utils != null);
    assert(regfile.commit_handlers_by_writeport.isEmpty()
           || regfile.commit_handlers_by_writeport.size() == regfile.writeback_writes.size());
    if (!regfile.commit_handlers_by_writeport.isEmpty())
      return;
    regfile.unique_commit_handlers.clear();
    regfile.read_forward_handlers.clear();
    if (regfile.writeback_writes.isEmpty())
      return; //no need to construct anything
    if (retireSerializer_opt.isEmpty())
      regfile.useScoreboard = false;
    //For now: Use only one type per register file.
    CommitHandler useForAll = null;
    if (regfile.useScoreboard) {
      IDRetireSerializerStrategy serializer = constructOrReuseRetireSerializer(regfile.issueFront.asList());
      DH_IDMapping idMapping = new DH_IDMapping(core, language, bNodes, serializer,
          2, 1, 2, //TODO: make configurable
                   // innerIDWidth must be min. 2 for now (-> 3 scoreboard entries)
                   // -> 'overflow stall' condition does not work properly with width 1
          regfile.utils, regfile);
      var idBasedHandler = new IDBasedDHCommitHandler(core, language, bNodes, idMapping, regfile, regfile.utils);
      //TODO: Configure forwarder (enable/disable, use only for some writes/reads, etc.)
      regfile.read_forward_handlers.add(idBasedHandler.getReadForwarder());
      useForAll = idBasedHandler;
    }
    else {
      useForAll = new DefaultCommitHandler();
    }
    for (int i = 0; i < regfile.writeback_writes.size(); ++i)
      regfile.commit_handlers_by_writeport.add(useForAll);
    regfile.unique_commit_handlers.add(useForAll);

    assert(regfile.commit_handlers_by_writeport.stream().allMatch(handler -> regfile.unique_commit_handlers.contains(handler)));
  }

  private RegfileInfo configureRegfile(String name, PipelineFront issueFront, PipelineFront writebackFront, List<NodeInstanceDesc.Key> reads,
                                       List<NodeInstanceDesc.Key> writes, int width, int depth) {
    RegfileInfo ret = regfilesByName.get(name);
    if (ret == null) {
      ret = new RegfileInfo();
      ret.regName = name;
      ret.writebackFront = writebackFront;
      ret.width = width;
      ret.depth = depth;
      regfiles.add(ret);
      regfilesByName.put(name, ret);
    } else {
      assert (ret.writebackFront.asList().equals(writebackFront.asList()));
      assert (ret.width == width);
      assert (ret.depth == depth);
    }
    HashSet<PipelineStage> issueFrontStages =
        Stream.concat(ret.issueFront.asList().stream(), issueFront.asList().stream()).collect(Collectors.toCollection(HashSet::new));
    ret.issueFront = new PipelineFront(issueFrontStages);
    ret.earlyReadsUnmapped = Stream.concat(ret.earlyReadsUnmapped.stream(), reads.stream())
                         .filter(readKey -> issueFront.isAfter(readKey.getStage(), false))
                         .distinct()
                         .collect(Collectors.toCollection(ArrayList::new));
    ret.earlyWritesUnmapped = Stream.concat(ret.earlyWritesUnmapped.stream(), writes.stream())
                          .filter(writeKey -> issueFront.isAfter(writeKey.getStage(), false))
                          .distinct()
                          .collect(Collectors.toCollection(ArrayList::new));
    ret.regularReadsUnmapped = Stream.concat(ret.regularReadsUnmapped.stream(), reads.stream())
                           .filter(readKey -> issueFront.isAroundOrBefore(readKey.getStage(), false))
                           .distinct()
                           .collect(Collectors.toCollection(ArrayList::new));
    ret.regularWritesUnmapped = Stream.concat(ret.regularWritesUnmapped.stream(), writes.stream())
                            .filter(writeKey -> issueFront.isAroundOrBefore(writeKey.getStage(), false))
                            .distinct()
                            .collect(Collectors.toCollection(ArrayList::new));
    getPortMapper().generatePortMapping(ret);
    ret.commit_handlers_by_writeport.clear();
    ret.unique_commit_handlers.clear();
    return ret;
  }

  ArrayList<RegfileInfo> regfiles = new ArrayList<>();
  HashMap<String, RegfileInfo> regfilesByName = new HashMap<>();

  NodeLogicBlock buildRegfileLogic(NodeRegistryRO registry, int auxRerun, List<Integer> auxReads, List<Integer> auxWrites,
                                   RegfileInfo regfile, NodeInstanceDesc.Key nodeKey, EarlyRWImplementation earlyrw) {
    NodeLogicBlock ret = new NodeLogicBlock();
    assert (nodeKey.getNode().name.equals(regfile.regName));

    final String tab = language.tab;
    int elements = regfile.depth;

    final int addrSize = Log2.clog2(elements);
    final int numReadPorts = regfile.earlyReads.size() + regfile.issue_reads.size();
    final int numWritePorts = regfile.writeback_writes.size();

    // global signals shared between all regfiles (context info)
    final String[] gSignals = {"rcontext_i", "wcontext_i"};
    int[] gSignalsize = {1, 1};
    int[] gSignalcount = {numReadPorts, numWritePorts};

    // control signals for this specific regfile (r/w ports)
    final String[] rSignals = {"raddr_i", "rdata_o", "re_i", "waddr_i", "wdata_i", "we_i"};
    final boolean[] rSignalInDir = {true, false, true, true, true, true};
    int[] rSignalsize = {addrSize, regfile.width, 1, addrSize, regfile.width, 1};
    int[] rSignalcount = {numReadPorts, numReadPorts, numReadPorts, numWritePorts, numWritePorts, numWritePorts};
    final String rSignal_raddr = rSignals[0];
    final String rSignal_rdata = rSignals[1];
    final String rSignal_re = rSignals[2];
    final String rSignal_waddr = rSignals[3];
    final String rSignal_wdata = rSignals[4];
    final String rSignal_we = rSignals[5];

    String dirtyRegName = String.format("%s_dirty", regfile.regName);

    ret.declarations += String.format("reg [%d-1:0] %s;\n", elements, dirtyRegName);

    String regfileModuleName = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
        Purpose.MARKER_CUSTOM_REG, elements > 1 ? RegfileModuleNode : RegfileModuleSingleregNode, core.GetRootStage(), ""));

    for (int i = 0; i < rSignals.length; i++) {
      if (rSignalsize[i] == 0)
        continue;
      ret.declarations += "logic[" + (rSignalcount[i] - 1) + ":0][" + (rSignalsize[i] - 1) + ":0] _" +
                          makeRegModuleSignalName(regfile.regName, rSignals[i], 0) + ";\n";
      for (int j = 0; j < rSignalcount[i]; ++j) {
        ret.declarations += "logic[" + (rSignalsize[i] - 1) + ":0] " + makeRegModuleSignalName(regfile.regName, rSignals[i], j) + ";\n";
        if (rSignalInDir[i])
          ret.logic += "assign _" + makeRegModuleSignalName(regfile.regName, rSignals[i], 0) + "[" + j +
                       "] = " + makeRegModuleSignalName(regfile.regName, rSignals[i], j) + ";\n";
        else
          ret.logic += "assign " + makeRegModuleSignalName(regfile.regName, rSignals[i], j) + " = _" +
                       makeRegModuleSignalName(regfile.regName, rSignals[i], 0) + "[" + j + "];\n";
      }
    }
    boolean implement_context_switches = allISAXes.containsKey(SCAL.PredefInstr.ctx.instr.GetName());
    ret.logic += regfileModuleName + " #(.DATA_WIDTH(" + regfile.width + ")" + (elements > 1 ? (",.ELEMENTS(" + elements + ")") : "") +
                 (implement_context_switches ? ",.CONTEXTS(" + cfg.number_of_contexts + ")" : "") + ",.RD_PORTS(" + numReadPorts + ")"
                 + ",.WR_PORTS(" + numWritePorts + "))"
                 + "reg_" + regfile.regName + "(\n";

    for (int i = 0; i < rSignals.length; i++) {
      if (rSignalsize[i] == 0)
        continue;
      String rSignal = rSignals[i];
      ret.logic += "." + rSignal + "(_" + makeRegModuleSignalName(regfile.regName, rSignal, 0) + "),\n";
    }

    if (implement_context_switches) {
      // global signal connection - bodge context switching without hazard
      // detection
      for (int i = 0; i < gSignals.length; i++) {
        if (gSignalsize[i] == 0)
          continue;
        String gSignal = gSignals[i];
        ret.logic += "." + gSignal + "('{";
        // Comma-separated port signal names from 0..gSignalcount[i]

        var context_switching = registry.lookupExpressionRequired(
            new NodeInstanceDesc.Key(Purpose.MARKER_INTERNALIMPL_PIN, SCALStateContextStrategy.isaxctx_node, core.GetRootStage(), ""));

        ret.logic += IntStream.range(0, gSignalcount[i]).mapToObj(j -> context_switching).reduce((a, b) -> a + "," + b).orElse("");
        ret.logic += "}),\n";
      }
    }

    ret.logic += ".*);\n";

    if (regfile.utils == null)
      regfile.utils = new RegInternalUtils(regfile);
    var utils = regfile.utils;
    setupCommitAndForwardHandlers(regfile);

    List<PipelineStage> issueStages = regfile.issueFront.asList();
    ReadreqExpr[] issueReadForwardRequest = new ReadreqExpr[regfile.issue_reads.size()];
    ForwardToWires[] issueReadForwardToWires = new ForwardToWires[regfile.issue_reads.size()];
    for (int iRead = 0; iRead < regfile.issue_reads.size(); ++iRead) {
      final int iRead_ = iRead;
      if (auxReads.size() <= iRead)
        auxReads.add(registry.newUniqueAux());
      int auxRead = auxReads.get(iRead);

      IssueReadEntry readGroupEntry = regfile.issue_reads.get(iRead);
      assert(readGroupEntry.readKey().getAux() == 0); // keys are supposed to be general
      assert(issueStages.size() > 0);

      ////Read during the issue stage.
      ////A typical scenario, given several issue stages, is that the requested read result is in an execution unit.
      ////In that case, only one issue stage is able to issue to that execution unit; thus, we can mux the register
      //// port across the issue stages.
      ////The result will be pipelined if needed.
      //boolean specificIssueStageOnly = regfile.issueFront.contains(requestedReadKey.getStage());

      for (PipelineStage issueStage : issueStages) {
        //if (specificIssueStageOnly && issueStage != requestedReadKey.getStage())
        //  continue;
        String wireName_dhInAddrStage = utils.getWireName_dhInIssueStage(iRead, issueStage);
        ret.declarations += String.format("logic %s;\n", wireName_dhInAddrStage);
        String wireName_dhInAddrStage_preforward = wireName_dhInAddrStage + "_preforward";
        ret.declarations += String.format("logic %s;\n", wireName_dhInAddrStage_preforward);
        // add a WrFlush signal (equal to the wrPC_valid signal) as an output with aux != 0
        // StallFlushDeqStrategy will collect this flush signal
        ret.outputs.add(
            new NodeInstanceDesc(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.REGULAR, bNodes.WrStall, issueStage, "", auxRead),
                                 wireName_dhInAddrStage, ExpressionType.WireName));
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, issueStage, ""));
      }

      NodeInstanceDesc.Key nodeKey_readInFirstIssueStage =
          new NodeInstanceDesc.Key(Purpose.REGULAR, readGroupEntry.readKey().getNode(),
                                  regfile.issueFront.asList().get(0), readGroupEntry.readKey().getISAX());
      String wireName_readInIssueStage = nodeKey_readInFirstIssueStage.toString(false) + "_s";
      ret.declarations += String.format("logic [%d-1:0] %s;\n", regfile.width, wireName_readInIssueStage);
      ret.outputs.add(new NodeInstanceDesc(nodeKey_readInFirstIssueStage, wireName_readInIssueStage, ExpressionType.WireName));

      String wireName_readInIssueStage_preforward = nodeKey_readInFirstIssueStage.toString(false) + "_preforward_s";
      ret.declarations += String.format("logic [%d-1:0] %s;\n", regfile.width, wireName_readInIssueStage_preforward);

      //Bitmap wire specifying if the selected read is from stage I but stalls due to DH.
      // -> is also 0 for stalls from resource conflicts.
      String wireName_readIsPrimaryDH = makeRegModuleSignalName(regfile.regName, "primaryDH", iRead); //(not a regfile module pin)
      ret.declarations += String.format("logic [%d-1:0] %s;\n", issueStages.size(), wireName_readIsPrimaryDH);

      String readLogic = "";
      readLogic += "always_comb begin\n";
      readLogic +=
          tab + String.format("%s = %s;\n", wireName_readInIssueStage_preforward, makeRegModuleSignalName(regfile.regName, rSignal_rdata, iRead));
      readLogic += tab + String.format("%s = 0;\n", makeRegModuleSignalName(regfile.regName, rSignal_re, iRead));
      if (elements > 1)
        readLogic += tab + tab + String.format("%s = 'x;\n", makeRegModuleSignalName(regfile.regName, rSignal_raddr, iRead));
      for (PipelineStage issueStage : regfile.issueFront.asList())
        readLogic += tab + String.format("%s_preforward = 0;\n", utils.getWireName_dhInIssueStage(iRead, issueStage));
      readLogic += tab + String.format("%s = '0;\n", wireName_readIsPrimaryDH);

      String[] stageReadAddrExpr = new String[issueStages.size()];
      //The read port can be used from several issue stages (only one at a time).
      for (int iIssueStage = 0; iIssueStage < issueStages.size(); ++iIssueStage) {
        PipelineStage issueStage = issueStages.get(iIssueStage);
        //if (specificIssueStageOnly && issueStage != requestedReadKey.getStage())
        //  continue;

        String wireName_dhInAddrStage = utils.getWireName_dhInIssueStage(iRead, issueStage);

        if (issueStage != nodeKey_readInFirstIssueStage.getStage()) {
          NodeInstanceDesc.Key nodeKey_readInIssueStage =
              new NodeInstanceDesc.Key(Purpose.REGULAR, readGroupEntry.readKey().getNode(), issueStage, readGroupEntry.readKey().getISAX());
          ret.outputs.add(new NodeInstanceDesc(nodeKey_readInIssueStage, wireName_readInIssueStage, ExpressionType.AnyExpression_Noparen));
        }

        // Get the early address field that all ISAXes should provide at this point.
        //  ValidMuxStrategy will implement the ISAX selection logic.
        String rdAddrExpr =
            (elements > 1)
                ? registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
                      bNodes.GetAdjSCAIEVNode(readGroupEntry.readKey().getNode(), AdjacentNode.addr).orElseThrow(),
                      issueStage,
                      readGroupEntry.readKey().getISAX()))
                : "0";
        stageReadAddrExpr[iIssueStage] = rdAddrExpr;
        String rdRegAddrValidExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
            bNodes.GetAdjSCAIEVNode(readGroupEntry.readKey().getNode(), AdjacentNode.addrReq).orElseThrow(),
            issueStage,
            readGroupEntry.readKey().getISAX()));

        //TODO: Detect hazards with regfile writes from another issue stage.

        readLogic += tab + "if (" + rdRegAddrValidExpr + ") begin\n";
        readLogic += tab + tab + "if (!" + makeRegModuleSignalName(regfile.regName, rSignal_re, iRead) + ") begin\n";
        readLogic += tab + tab + tab + String.format("%s = 1;\n", makeRegModuleSignalName(regfile.regName, rSignal_re, iRead));
        if (elements > 1)
          readLogic += tab + tab + tab + String.format("%s = %s;\n", makeRegModuleSignalName(regfile.regName, rSignal_raddr, iRead), rdAddrExpr);

        readLogic += tab + tab + tab + String.format("%s_preforward = %s[%s] == 1;\n", wireName_dhInAddrStage, dirtyRegName, rdAddrExpr);
        readLogic += tab + tab + tab + String.format("%s[%d] = %s_preforward;\n", wireName_readIsPrimaryDH, iIssueStage, wireName_dhInAddrStage);
        readLogic += tab + tab + "end\n";
        // Stall if there's multiple issue stages attempting to use this read port.
        readLogic += tab + tab + "else begin\n"
                     + tab + tab + tab + String.format("%s_preforward = 1;\n", wireName_dhInAddrStage)
                     //+ tab + tab + tab + String.format("%s = 0;\n", makeRegModuleSignalName(regfile.regName, rSignal_re, iRead))
                     + tab + tab + "end\n";
        readLogic += tab + "end\n";
      }
      //Start a new always_comb block for the forwarding.
      //Default: Use the results (data, is DH) without forwarding.
      readLogic += """
          end
          always_comb begin
              %s = %s;
              %s
          """.formatted(wireName_readInIssueStage, wireName_readInIssueStage_preforward,
                        issueStages.stream().map(issueStage -> "%1$s = %1$s_preforward;\n"
                                                               .formatted(utils.getWireName_dhInIssueStage(iRead_, issueStage)))
                                            .reduce((a,b)->a+b).orElseThrow());

      if (!readGroupEntry.disableForwarding) {
        String wireName_forwardValid = makeRegModuleSignalName(regfile.regName, "forwardValid", iRead); //(not a regfile module pin)
        ret.declarations += String.format("logic %s;\n", wireName_forwardValid);
        String wireName_forwardData = makeRegModuleSignalName(regfile.regName, "forwardData", iRead); //(not a regfile module pin)
        ret.declarations += String.format("logic [%d-1:0] %s;\n", regfile.width, wireName_forwardData);
        //The forward request expression.
        issueReadForwardRequest[iRead] = new ReadreqExpr(readGroupEntry.readKey(),
                                                         makeRegModuleSignalName(regfile.regName, rSignal_re, iRead),
                                                         (elements <= 1) ? "0" : makeRegModuleSignalName(regfile.regName, rSignal_raddr, iRead));
        //The wires to assign the forward to.
        issueReadForwardToWires[iRead] = new ForwardToWires(wireName_forwardValid, wireName_forwardData);
        //Apply the forward, if valid.
        //- Overwrite the data.
        //- Clear the DH flag for the read masked by <wireName_readIsPrimaryDH>.
        readLogic += """
                if (%1$s) begin
                    %3$s = %2$s;%4$s
                end
            """.formatted(wireName_forwardValid, wireName_forwardData, wireName_readInIssueStage,
                          IntStream.range(0,issueStages.size()).mapToObj(iStage->
                            "\n"+tab+tab+"if (%s[%d]) %s = 1'b0;".formatted(
                              wireName_readIsPrimaryDH, iStage,
                              utils.getWireName_dhInIssueStage(iRead_, issueStages.get(iStage)))
                          ).reduce((a,b)->a+b).orElse(""));
      }
      for (int iIssueStage = 0; iIssueStage < issueStages.size(); ++iIssueStage) {
        PipelineStage issueStage = issueStages.get(iIssueStage);

        // Forward early write -> read from current instruction.
        //  -> Early writes wander through the pipeline alongside an instruction.
        //     However, early writes also are meant to affect reads starting with that instruction,
        //      in contrast to regular writes that are only visible to the following instructions.
        //  -> This needs to be done even if regular forwarding is disabled for this port.
        for (int iEarlyWrite = 0; iEarlyWrite < regfile.earlyWrites.size(); ++iEarlyWrite) {
          NodeInstanceDesc.Key earlyWriteKey = regfile.earlyWrites.get(iEarlyWrite);
          assert (Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN.matches(regfile.earlyWrites.get(iEarlyWrite).getPurpose()));
          assert (regfile.earlyWrites.get(iEarlyWrite).getAux() == 0);
          int iWritePort = (int)regfile.writeback_writes.stream().takeWhile(entry -> !entry.getNode().equals(earlyWriteKey.getNode())).count();
          String wrAddrValidWire = utils.getWireName_WrIssue_addr_valid(iWritePort);
          String wrAddrExpr = (elements > 1) ? utils.getWireName_WrIssue_addr(iWritePort) : "0";
          // The early write value is already known in the issue stage, and is pipelined just like the address.
          String earlyWriteVal_issue =
              registry.lookupExpressionRequired(new NodeInstanceDesc.Key(earlyWriteKey.getNode(), issueStage, earlyWriteKey.getISAX()));
          readLogic += tab + String.format("if (%s && %s == %s) begin\n", wrAddrValidWire, stageReadAddrExpr[iIssueStage], wrAddrExpr);
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
    wrDirtyLines += earlyrw.earlyDirtyFFResetLogic(registry, regfile, null, null, tab + tab);
    wrDirtyLines += tab + "end else begin\n";

    WritebackExpr[] writebackExprs = new WritebackExpr[regfile.writeback_writes.size()];

    assert(regfile.unique_commit_handlers.stream().distinct().count() == regfile.unique_commit_handlers.size());
    assert(regfile.commit_handlers_by_writeport.size() == regfile.writeback_writes.size());
    assert(regfile.commit_handlers_by_writeport.stream().allMatch(handler -> regfile.unique_commit_handlers.contains(handler)));

    regfile.unique_commit_handlers.forEach(commitHandler -> commitHandler.reset());
    regfile.read_forward_handlers.forEach(forwardHandler -> forwardHandler.reset());

    // Write commit logic (reset dirty bit, apply write to the register file)
    for (int iPort = 0; iPort < regfile.writeback_writes.size(); ++iPort) {
      NodeInstanceDesc.Key wrBaseKey = regfile.writeback_writes.get(iPort);
      assert (!wrBaseKey.getNode().isAdj());
      assert (wrBaseKey.getAux() == 0);
      assert (Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN.matches(wrBaseKey.getPurpose()));

      writebackExprs[iPort] = new WritebackExpr();
      var writebackExpr = writebackExprs[iPort];

      writebackExpr.baseNode = wrBaseKey.getNode();
      writebackExpr.stage = wrBaseKey.getStage();
      writebackExpr.wrDataExpr =
          registry.lookupExpressionRequired(new NodeInstanceDesc.Key(wrBaseKey.getNode(), wrBaseKey.getStage(), wrBaseKey.getISAX()));
      writebackExpr.wrValidExpr = registry.lookupRequired(new NodeInstanceDesc.Key(
              bNodes.GetAdjSCAIEVNode(wrBaseKey.getNode(), AdjacentNode.validReq).orElseThrow(), wrBaseKey.getStage(), wrBaseKey.getISAX()))
          .getExpressionWithParens();
      writebackExpr.wrCancelExpr = registry.lookupRequired(new NodeInstanceDesc.Key(
              bNodes.GetAdjSCAIEVNode(wrBaseKey.getNode(), AdjacentNode.cancelReq).orElseThrow(), wrBaseKey.getStage(), wrBaseKey.getISAX()))
          .getExpressionWithParens();
      writebackExpr.wrAddrExpr = (elements > 1) ? registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
                                                     bNodes.GetAdjSCAIEVNode(wrBaseKey.getNode(), AdjacentNode.addr).orElseThrow(),
                                                     wrBaseKey.getStage(), wrBaseKey.getISAX()))
                                               : "";

      String stallDataStageCond_ = "1'b0";
      String flushDataStageCond_ = "1'b0";
      if (wrBaseKey.getStage().getKind() != StageKind.Decoupled) {
        stallDataStageCond_ = SCALUtil.buildCond_StageStalling(bNodes, registry, wrBaseKey.getStage(), false);

        flushDataStageCond_ = SCALUtil.buildCond_StageFlushing(bNodes, registry, wrBaseKey.getStage());
      }
      writebackExpr.stallDataStageCond = stallDataStageCond_;
      writebackExpr.flushDataStageCond = flushDataStageCond_;

      // Combinational validResp wire.
      String validRespWireName_ = "";
      Optional<SCAIEVNode> wrValidRespNode_opt = bNodes.GetAdjSCAIEVNode(wrBaseKey.getNode(), AdjacentNode.validResp);
      if (wrValidRespNode_opt.isPresent()) {
        var validRespKey = new NodeInstanceDesc.Key(wrValidRespNode_opt.get(), wrBaseKey.getStage(), wrBaseKey.getISAX());
        validRespWireName_ = validRespKey.toString(false) + "_s";
        ret.declarations += String.format("logic %s;\n", validRespWireName_);
        ret.outputs.add(new NodeInstanceDesc(validRespKey, validRespWireName_, ExpressionType.WireName));
      }
      writebackExpr.validRespWireName = validRespWireName_;

      regfile.commit_handlers_by_writeport.get(iPort).processWritePort(iPort, writebackExpr, registry, ret);
    }

    //Call the read forwarders.
    List<ReadForwardCond> issueReadForwardResponse = new ArrayList<>();
    for (int iRead = 0; iRead < regfile.issue_reads.size(); ++iRead) {
      if (issueReadForwardRequest[iRead] == null)
        continue; // No forwarding to apply.
      for (ReadForwardHandler handler : regfile.read_forward_handlers)
        handler.processReadPort(iRead, issueReadForwardRequest[iRead], registry, ret);
    }

    //Apply the commit handlers.
    for (CommitHandler commitHandler : regfile.unique_commit_handlers) {
      for (var writeToCond : commitHandler.getWriteToBackingConds()) {
        int iPort = writeToCond.iPort;
        String wrDataLines = "";
        wrDataLines += "always_comb begin\n";
        if (elements > 1)
          wrDataLines += tab + String.format("%s = %d'dX;\n", makeRegModuleSignalName(regfile.regName, rSignal_waddr, iPort), addrSize);
        wrDataLines += tab + String.format("%s = %d'dX;\n", makeRegModuleSignalName(regfile.regName, rSignal_wdata, iPort), regfile.width);
        wrDataLines += tab + String.format("%s = 0;\n", makeRegModuleSignalName(regfile.regName, rSignal_we, iPort));
        wrDataLines += tab + String.format("if (%s) begin\n", writeToCond.condExpr);
        if (elements > 1)
          wrDataLines +=
              tab + tab + String.format("%s = %s;\n", makeRegModuleSignalName(regfile.regName, rSignal_waddr, iPort), writeToCond.addrExpr);
        wrDataLines += tab + tab + String.format("%s = %s;\n", makeRegModuleSignalName(regfile.regName, rSignal_wdata, iPort), writeToCond.dataExpr);
        wrDataLines += tab + tab + String.format("%s = 1;\n", makeRegModuleSignalName(regfile.regName, rSignal_we, iPort));
        wrDataLines += tab + "end\n";
  
        wrDataLines += "end\n";
        ret.logic += wrDataLines;
      }
      commitHandler.buildPost(registry, ret);
    }

    //Apply read forwards (if any).
    for (ReadForwardHandler forwardHandler : regfile.read_forward_handlers) {
      issueReadForwardResponse.addAll(forwardHandler.getForwardConds());
      forwardHandler.buildPost(registry, ret);
    }
    for (int iRead = 0; iRead < regfile.issue_reads.size(); ++iRead) {
      final int iRead_ = iRead;
      ForwardToWires forwardToWires = issueReadForwardToWires[iRead];
      if (forwardToWires == null) {
        // Read port has no forwarding.
        assert(issueReadForwardResponse.stream().filter(resp -> resp.iPort == iRead_).count() == 0);
        continue;
      }
      StringBuilder forwardToLogic = new StringBuilder();
      forwardToLogic.append("""
          always_comb begin
              %s = 0;
              %s = '0;
          """.formatted(forwardToWires.forwardValidWire, forwardToWires.forwardDataWire));
      issueReadForwardResponse.stream().filter(resp -> resp.iPort == iRead_).forEach(forwardResp -> {
        forwardToLogic.append("""
                if (%3$s) begin
                    %1$s = 1;
                    %2$s = %4$s;
                end
            """.formatted(forwardToWires.forwardValidWire, forwardToWires.forwardDataWire,
                          forwardResp.dataValidExpr, forwardResp.dataExpr));
      });
      forwardToLogic.append("end\n");
      ret.logic += forwardToLogic;
    }

    // Write issue logic
    for (int iWriteNode = 0; iWriteNode < utils.writeNodes.size(); ++iWriteNode) {
      if (auxWrites.size() <= iWriteNode)
        auxWrites.add(registry.newUniqueAux());
      int auxWrite = auxWrites.get(iWriteNode);

      SCAIEVNode commitWriteNode = utils.writeNodes.get(iWriteNode);
      SCAIEVNode nonspawnWriteNode = bNodes.GetEquivalentNonspawnNode(commitWriteNode).orElse(commitWriteNode);
      int[] writePorts = IntStream.range(0, regfile.writeback_writes.size())
                             .filter(iPort -> regfile.writeback_writes.get(iPort).getNode().equals(commitWriteNode))
                             .toArray();
      assert (writePorts.length > 0);
      assert (IntStream.of(writePorts).mapToObj(iPort -> regfile.writeback_writes.get(iPort).getISAX()).distinct().limit(2).count() ==
              1); // ISAX equal for all commit ports with this node
      // Note: nodeISAX is probably an empty string due to the port group assignment.
      String nodeISAX = regfile.writeback_writes.get(writePorts[0]).getISAX();

      // Check if there is a write during the issue stage.
      // -> Only the targeted issue stage will need the associated logic.
      int[] tmp_writeInIssuePorts =
          IntStream.of(writePorts).filter(iPort -> regfile.issueFront.contains(regfile.writeback_writes.get(iPort).getStage())).toArray();
      Optional<NodeInstanceDesc.Key> specificIssueKey_opt =
          tmp_writeInIssuePorts.length == 1 ? Optional.of(regfile.writeback_writes.get(tmp_writeInIssuePorts[0])) : Optional.empty();
      String isNotAnImmediateWriteCond = "1";
      if (specificIssueKey_opt.isPresent()
          && (specificIssueKey_opt.get().getStage().getTags().contains(StageTag.Commit)
             || specificIssueKey_opt.get().getStage().getNext().stream().allMatch(successor -> successor.getContinuous()
                                                                                               && successor.getTags().contains(StageTag.Commit)))) {
        // -> Limit 'write in issue' if we know it also commits in the stage.
        //TODO: ResetDirtyCond entries should be applied after emitting the 'dirty <= 1' logic. 
        isNotAnImmediateWriteCond =
            registry
                .lookupRequired(new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(commitWriteNode, AdjacentNode.validReq).orElseThrow(),
                                                         specificIssueKey_opt.get().getStage(), specificIssueKey_opt.get().getISAX()))
                .getExpressionWithParens()
            + " || "
            + registry
                .lookupRequired(new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(commitWriteNode, AdjacentNode.cancelReq).orElseThrow(),
                                                         specificIssueKey_opt.get().getStage(), specificIssueKey_opt.get().getISAX()))
                .getExpressionWithParens();
        isNotAnImmediateWriteCond = "!(" + isNotAnImmediateWriteCond + ")";
      }

      String addrValidWire = utils.getWireName_WrIssue_addr_valid(iWriteNode);
      String addrWire = utils.getWireName_WrIssue_addr(iWriteNode);
      String wrIssueAddrLines = "always_comb begin\n";
      ret.declarations += String.format("logic %s;\n", addrValidWire);
      if (elements > 1)
        ret.declarations += String.format("logic [%d-1:0] %s;\n", addrSize, addrWire);
      wrIssueAddrLines += tab + String.format("%s = 0;\n", addrValidWire);
      if (elements > 1)
        wrIssueAddrLines += tab + String.format("%s = 'x;\n", addrWire);

      boolean nextIsElseif = false;
      for (PipelineStage issueStage : regfile.issueFront.asList()) {
        String addrValid_perstage_wire = utils.getWireName_WrIssue_addr_valid_perstage(iWriteNode, issueStage);
        ret.declarations += String.format("logic %s;\n", addrValid_perstage_wire);

        if (specificIssueKey_opt.isPresent() && issueStage != specificIssueKey_opt.get().getStage()) {
          ret.logic += String.format("assign %s = 0;\n", addrValid_perstage_wire);
          continue;
        }

        String stallAddrStageCond = SCALUtil.buildCond_StageStalling(bNodes, registry, issueStage, false);
        String flushAddrStageCond = SCALUtil.buildCond_StageFlushing(bNodes, registry, issueStage);

        String wrAddrExpr = (elements > 1)
                                ? registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
                                      bNodes.GetAdjSCAIEVNode(nonspawnWriteNode, AdjacentNode.addr).orElseThrow(), issueStage, nodeISAX))
                                : "0";
        String wrAddrValidExpr = registry.lookupExpressionRequired(
            new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(nonspawnWriteNode, AdjacentNode.addrReq).orElseThrow(), issueStage, nodeISAX));

        ret.logic += String.format("assign %s = %s;\n", addrValid_perstage_wire, wrAddrValidExpr);

        String wireName_dhInAddrStage = String.format("conflictWr%s_%s_%d", regfile.regName, issueStage.getName(), iWriteNode);
        {
          // Stall the address stage if a new write has an address marked dirty, or if it conflicts with a parallel issue.
          ret.declarations += String.format("logic %s;\n", wireName_dhInAddrStage);
          wrIssueAddrLines += tab + String.format("%s = %s && %s%s[%s]%s;\n",
                                                  wireName_dhInAddrStage,
                                                  wrAddrValidExpr,
                                                  nextIsElseif ? "(" : "",
                                                  dirtyRegName, wrAddrExpr,
                                                  nextIsElseif ? " || %s)".formatted(addrValidWire) : "");
          ret.outputs.add(
              new NodeInstanceDesc(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.REGULAR, bNodes.WrStall, issueStage, "", auxWrite),
                                   wireName_dhInAddrStage, ExpressionType.WireName));
          registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, issueStage, ""));
        }

        wrIssueAddrLines += tab + String.format("if (!%s && %s && !(%s) && !(%s)) begin\n",
                                                addrValidWire, wrAddrValidExpr, stallAddrStageCond, flushAddrStageCond);
        wrIssueAddrLines += tab + tab + String.format("%s = 1;\n", addrValidWire);
        if (elements > 1)
          wrIssueAddrLines += tab + tab + String.format("%s = %s;\n", addrWire, wrAddrExpr);
        wrIssueAddrLines += tab + "end\n";
        nextIsElseif = true;
      }

      wrDirtyLines += tab + tab + String.format("if (%s) begin\n", addrValidWire);
      if (elements > 1)
        wrDirtyLines += tab + tab + tab + String.format("%s[%s] <= %s;\n", dirtyRegName, addrWire, isNotAnImmediateWriteCond);
      else
        wrDirtyLines += tab + tab + tab + String.format("%s <= %s;\n", dirtyRegName, isNotAnImmediateWriteCond);
      wrDirtyLines += earlyrw.earlyDirtyFFSetLogic(registry, regfile, commitWriteNode, addrWire, tab + tab + tab);
      wrDirtyLines += tab + tab + "end\n";

      wrIssueAddrLines += "end\n";
      ret.logic += wrIssueAddrLines;
    }

    for (CommitHandler commitHandler : regfile.unique_commit_handlers) {
      for (var resetDirtyCond : commitHandler.getResetDirtyConds()) {
        wrDirtyLines += tab + tab + String.format("if (%s) begin\n", resetDirtyCond.condExpr);
        if (elements > 1)
          wrDirtyLines += tab + tab + tab + String.format("%s[%s] <= 0;\n", dirtyRegName, resetDirtyCond.addrExpr);
        else
          wrDirtyLines += tab + tab + tab + String.format("%s <= 0;\n", dirtyRegName);
        wrDirtyLines += earlyrw.earlyDirtyFFResetLogic(registry, regfile, resetDirtyCond.baseNode, resetDirtyCond.addrExpr, tab + tab + tab);
        wrDirtyLines += tab + tab + "end\n";
      }
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
    EarlyRWImplementation earlyRWImplementation = makeEarlyRW(regfile);
    out.accept(NodeLogicBuilder.fromFunction("SCALStateBuilder_RegfileLogic(" + nodeKey.toString() + ")", (registry, aux) -> {
      return buildRegfileLogic(registry, aux, runtimeData.auxReads, runtimeData.auxWrites, regfile, nodeKey, earlyRWImplementation);
    }));
    return true;
  }

  static record PipelineStrategyKey(PipelineStage stage, boolean resetToZero) {}

  HashMap<PipelineStrategyKey, NodeRegPipelineStrategy> pipelineStrategyByPipetoStage = new HashMap<>();

  /**
   * Constructs or reuses an IDRetireSerializerStrategy for the given set of issue (assign ID) stages.
   */
  protected final IDRetireSerializerStrategy constructOrReuseRetireSerializer(List<PipelineStage> assignStages) {
    if (!assignStages.isEmpty() && !retireSerializer_opt.isPresent()) {
      throw new IllegalArgumentException("Got no retire serializer, assignStages should be empty.");
    }
    if (assignStages.isEmpty())
      return null;
    PipelineFront serializerAssignFront = retireSerializer_opt.get().getIssueFront();
    if (assignStages.stream().anyMatch(stage -> !serializerAssignFront.contains(stage))) {
      throw new IllegalArgumentException("assignStages contains stages not processed by the retire serializer.");
    }
    return retireSerializer_opt.get();
  }

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
    if (getPortMapper().implementSingle(out, nodeKey, isLast, regName))
      return true;
    var addrNode_opt = bNodes.GetAdjSCAIEVNode(baseNodeNonspawn, AdjacentNode.addr);
    if (addrNode_opt.isEmpty())
      return false;

    SCAIEVNode addrNode = addrNode_opt.get();
    if (nodeKey.getPurpose().matches(Purpose.PIPEDIN) || nodeKey.getPurpose().matches(NodeRegPipelineStrategy.Purpose_Getall_ToPipeTo)) {
      // Check if a pipeline can/should be instantiated for the given nodeKey.
      RegfileInfo regfile = regfilesByName.get(regName);
      if (regfile == null)
        return false;
      boolean implementWithPipeliner = false;
      if (!regfile.issueFront.isAroundOrAfter(nodeKey.getStage(), false) && !nodeKey.getNode().isSpawn() && isLast) {
        // Pipeline the read data or read/write address
        implementWithPipeliner = true;
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
        implementWithPipeliner = true;
      }
      if (implementWithPipeliner) {
        boolean resetToZero = nodeKey.getNode().isValidNode() || nodeKey.getNode().getAdj() == AdjacentNode.cancelReq;
        var pipelinerMapKey = new PipelineStrategyKey(nodeKey.getStage(), resetToZero);
        MultiNodeStrategy pipelineStrategy = pipelineStrategyByPipetoStage.get(pipelinerMapKey);
        if (pipelineStrategy == null && nodeKey.getPurpose().matches(Purpose.PIPEDIN)) { //Create a new one
          var pipeliner = strategyBuilders.buildNodeRegPipelineStrategy(language, bNodes, new PipelineFront(nodeKey.getStage()),
                                                                        false, false, pipelinerMapKey.resetToZero,
                                                                        _nodeKey -> true, _nodeKey -> false, MultiNodeStrategy.noneStrategy,
                                                                        false);
          if (resetToZero) //
            regfile.earlyWriteValidPipeliners.add(new EarlyWritePipelinerEntry(
                nodeKey.getStage(), bNodes.GetNonAdjNode(nodeKey.getNode()), pipeliner));
          pipelineStrategyByPipetoStage.put(pipelinerMapKey, pipeliner);
          pipelineStrategy = pipeliner;
        }
        if (pipelineStrategy != null) {
          pipelineStrategy.implement(out, new ListRemoveView<>(List.of(nodeKey)), false);
          return true;
        }
      }
      return false;
    }

    // From the present CustomReg_addr nodes, select those within the allowed bounds (constrained by RdInstr availability and
    // CustReg_addr_constraint).
    PipelineFront rdInstrFront = core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.RdInstr).GetEarliest());
    var addrPipelineFrontStream =
        op_stage_instr.getOrDefault(addrNode, new HashMap<>()).keySet().stream().filter(stage -> rdInstrFront.isAroundOrBefore(stage, false));
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
      PipelineFront commitFront = new PipelineFront(core.GetRootStage().getAllChildren().filter(
          stage -> stage.getTags().contains(StageTag.Commit) && addrPipelineFront.isAroundOrBefore(stage, false)));
      if (commitFront.asList().isEmpty()) {
        var coreNode = core.GetNodes().get(bNodes.WrCustReg_data_constraint);
        if (coreNode == null) {
          logger.error("Custom registers: Found no viable writeback stage constraint for core {}", core.GetName());
          return true;
        }
        PipelineFront earliestFront = core.TranslateStageScheduleNumber(coreNode.GetEarliest());
        PipelineFront latestFront = core.TranslateStageScheduleNumber(coreNode.GetLatest());
        commitFront = new PipelineFront(core.GetRootStage().getAllChildren().filter(
                                        stage -> earliestFront.isAroundOrBefore(stage, false)
                                                 && (latestFront.isAroundOrAfter(stage, false)
                                                     || stage.getKind() == StageKind.Decoupled)
                                                 && addrPipelineFront.isAroundOrBefore(stage, false)));
      }
      if (commitFront.asList().isEmpty()) {
        logger.error("Custom registers: Found no writeback stages for {}", baseNode.name);
        return true;
      }
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
      boolean implement_context_switches = allISAXes.containsKey(SCAL.PredefInstr.ctx.instr.GetName());
      out.accept(NodeLogicBuilder.fromFunction("SCALStateStrategy_RegfileModule", registry -> {
        var ret = new NodeLogicBlock();
        String moduleName = singleVariant ? "svsinglereg" : "svregfile";
        ret.otherModules += "module " + moduleName + " #(\n"
                            + "    parameter int unsigned DATA_WIDTH = 32,\n" +
                            (singleVariant ? "" : 
                              "    parameter int unsigned ELEMENTS = 4,\n")
                            + (implement_context_switches ? "    parameter int unsigned CONTEXTS = 2,\n" : "")
                            + "    parameter int unsigned RD_PORTS = 1,\n"
                            + "    parameter int unsigned WR_PORTS = 2\n"
                            + ")(\n"
                            + "    input  logic                                      clk_i,\n"
                            + "    input  logic                                      rst_i,\n" +
                            (singleVariant ? "" : 
                              "    input  logic [RD_PORTS-1:0][$clog2(ELEMENTS)-1:0] raddr_i,\n")
                            + "    output logic [RD_PORTS-1:0][DATA_WIDTH-1:0]       rdata_o,\n"
                            + "    input  logic [RD_PORTS-1:0]                       re_i,\n" +
                            (singleVariant ? "" : 
                              "    input  logic [WR_PORTS-1:0][$clog2(ELEMENTS)-1:0] waddr_i,\n") +
                            (implement_context_switches ? 
                              "    input  logic [RD_PORTS-1:0][$clog2(CONTEXTS)-1:0]   rcontext_i,\n" : "") +
                            (implement_context_switches ? 
                              "    input  logic [WR_PORTS-1:0][$clog2(CONTEXTS)-1:0]   wcontext_i,\n" : "")
                            + "    input  logic [WR_PORTS-1:0][DATA_WIDTH-1:0]       wdata_i,\n"
                            + "    input  logic [WR_PORTS-1:0]                       we_i\n"
                            + ");\n" + (singleVariant ? "    localparam ELEMENTS = 1;\n" : "") 

                            + "    reg " + (implement_context_switches ? "[CONTEXTS-1:0]" : "") 
                                         + (singleVariant ? "" : "[ELEMENTS-1:0]")
                                         + "[DATA_WIDTH-1:0] regfile "
                                         + ";\n"
                            + "    always_ff @(posedge clk_i, posedge rst_i) begin : write_svreg\n"
                            + "        if(rst_i) begin\n"
                            + "            for (int unsigned j = 0; j < ELEMENTS; j++) begin\n"
                            + (implement_context_switches ? "                for (int unsigned k = 0; k < CONTEXTS; k++)\n" : "")
                            + "                regfile" + (implement_context_switches ? "[k]" : "") + (singleVariant ? "" : "[j]") + " <= 0;\n"
                            + "            end\n"
                            + "        end else begin\n"
                            + "            for (int unsigned j = 0; j < WR_PORTS; j++) begin\n"
                            + "                if(we_i[j] == 1) begin\n"
                            + "                    regfile" + (implement_context_switches ? "[wcontext_i[j]]" : "")
                            + (singleVariant ? "" : "[waddr_i[j]]") + " <= wdata_i[j];\n"
                            + "                end\n"
                            + "            end\n"
                            + "        end\n"
                            + "        \n"
                            + "    end\n"
                            + "    \n"
                            + "    generate\n"
                            + "    for (genvar iRead = 0; iRead < RD_PORTS; iRead++) begin\n"
                            + "        assign rdata_o[iRead] = re_i[iRead] ? regfile" + (implement_context_switches ? "[rcontext_i[iRead]]" : "") +
                            (singleVariant ? "" : "[raddr_i[iRead]]") + " : " + (readDefaultX ? "'x" : "'0") + ";\n"
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

  /**
   * Call inner strategies
   */
  void implementInner(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    for (var regfile : regfiles) {
      for (CommitHandler commitHandler : regfile.unique_commit_handlers) {
        commitHandler.implementInner(out, nodeKeys, isLast);
      }
    }
  }

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    implementInner(out, nodeKeys, isLast);
    Iterator<NodeInstanceDesc.Key> nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      var nodeKey = nodeKeyIter.next();
      if (implementRegfileModule(out, nodeKey) || implementSingle(out, nodeKey, isLast)) {
        nodeKeyIter.remove();
      }
    }
  }
}
