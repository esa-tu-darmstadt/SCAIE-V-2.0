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
import scaiev.frontend.SCAL;
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
import scaiev.scal.strategy.state.SCALStateContextStrategy;
import scaiev.ui.SCAIEVConfig;
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
  public SCALStateStrategy(StrategyBuilders strategyBuilders, Verilog language, BNode bNodes, Core core,
                           HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                           HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage,
                           HashMap<String, SCAIEVInstr> allISAXes, SCAIEVConfig cfg) {
    this.strategyBuilders = strategyBuilders;
    this.bNodes = bNodes;
    this.language = language;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.spawn_instr_stage = spawn_instr_stage;
    this.allISAXes = allISAXes;
    this.cfg = cfg;
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

  
  EarlyRWImplementation makeEarlyRW() {
    return new EarlyRWImplementation(this, language, bNodes, core);
  }

  public String makeRegModuleSignalName(String regName, String signalName, int portIdx) {
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
    /** A version of {@link RegfileInfo#earlyReads} before port mapping. */
    List<NodeInstanceDesc.Key> earlyReadsUnmapped = new ArrayList<>();

    /** The list of writes before issueFront. */
    List<NodeInstanceDesc.Key> earlyWrites = new ArrayList<>();
    /** A version of {@link RegfileInfo#earlyWrites} before port mapping. */
    List<NodeInstanceDesc.Key> earlyWritesUnmapped = new ArrayList<>();

    /**
     * The list of writes in or after issueFront.
     * Each entry is the key to the Wr<CustomReg> node.
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
    final int numWritePorts = regfile.commit_writes.size();
    final List<SCAIEVNode> writeNodes = regfile.commit_writes.stream().map(key -> key.getNode()).distinct().toList();

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

      ////Read during the issue stage.
      ////A typical scenario, given several issue stages, is that the requested read result is in an execution unit.
      ////In that case, only one issue stage is able to issue to that execution unit; thus, we can mux the register
      //// port across the issue stages.
      ////The result will be pipelined if needed.
      //boolean specificIssueStageOnly = regfile.issueFront.contains(requestedReadKey.getStage());

      for (PipelineStage issueStage : regfile.issueFront.asList()) {
        //if (specificIssueStageOnly && issueStage != requestedReadKey.getStage())
        //  continue;
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
        //if (specificIssueStageOnly && issueStage != requestedReadKey.getStage())
        //  continue;

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
      String flushDataStageCond = "1'b0";
      if (wrBaseKey.getStage().getKind() != StageKind.Decoupled) {
        stallDataStageCond = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, wrBaseKey.getStage(), ""));
        Optional<NodeInstanceDesc> wrstallDataStage =
            registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrStall, wrBaseKey.getStage(), ""));
        if (wrstallDataStage.isPresent())
          stallDataStageCond += " || " + wrstallDataStage.get().getExpression();

        flushDataStageCond = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, wrBaseKey.getStage(), ""));
        Optional<NodeInstanceDesc> wrflushDataStage =
            registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrFlush, wrBaseKey.getStage(), ""));
        if (wrflushDataStage.isPresent())
          flushDataStageCond += " || " + wrflushDataStage.get().getExpression();
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

      wrDataLines += tab + String.format("if (%s && !(%s) && !(%s)) begin\n", wrValidExpr, stallDataStageCond, flushDataStageCond);
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

      // Check if there is a write during the issue stage.
      // -> Only the targeted issue stage will need the associated logic.
      int[] tmp_writeInIssuePorts =
          IntStream.of(writePorts).filter(iPort -> regfile.issueFront.contains(regfile.commit_writes.get(iPort).getStage())).toArray();
      Optional<NodeInstanceDesc.Key> specificIssueKey_opt =
          tmp_writeInIssuePorts.length == 1 ? Optional.of(regfile.commit_writes.get(tmp_writeInIssuePorts[0])) : Optional.empty();
      String isNotAnImmediateWriteCond = "1";
      if (specificIssueKey_opt.isPresent()) {
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
        if (specificIssueKey_opt.isPresent() && issueStage != specificIssueKey_opt.get().getStage())
          continue;
        String stallAddrStageCond = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, issueStage, ""));
        Optional<NodeInstanceDesc> wrstallAddrStage =
            registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrStall, issueStage, ""));
        if (wrstallAddrStage.isPresent())
          stallAddrStageCond += " || " + wrstallAddrStage.get().getExpression();
        
        String flushAddrStageCond = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, issueStage, ""));
        Optional<NodeInstanceDesc> wrflushAddrStage =
            registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrFlush, issueStage, ""));
        if (wrflushAddrStage.isPresent())
          flushAddrStageCond += " || " + wrflushAddrStage.get().getExpression();

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

        wrIssueAddrLines +=
            tab + (nextIsElseif ? "else " : "") + String.format("if (%s && !(%s) && !(%s)) begin\n",
                                                                wrAddrValidExpr, stallAddrStageCond, flushAddrStageCond);
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
    var earlyRWImplementation = makeEarlyRW();
    out.accept(NodeLogicBuilder.fromFunction("SCALStateBuilder_RegfileLogic(" + nodeKey.toString() + ")", (registry, aux) -> {
      return buildRegfileLogic(registry, aux, runtimeData.auxReads, runtimeData.auxWrites, regfile, nodeKey, earlyRWImplementation);
    }));
    return true;
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
    if (getPortMapper().implementSingle(out, nodeKey, isLast, regName))
      return true;
    var addrNode_opt = bNodes.GetAdjSCAIEVNode(baseNodeNonspawn, AdjacentNode.addr);
    if (addrNode_opt.isEmpty())
      return false;

    SCAIEVNode addrNode = addrNode_opt.get();
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
