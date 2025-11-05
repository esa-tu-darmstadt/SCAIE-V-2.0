package scaiev.backend;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.SCALBackendAPI.CustomCoreInterface;
import scaiev.coreconstr.Core;
import scaiev.coreconstr.CoreNode;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;
import scaiev.frontend.SCAL;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.pipeline.ScheduleFront;
import scaiev.scal.CombinedNodeLogicBuilder;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.scal.strategy.pipeline.IDBasedPipelineStrategy;
import scaiev.scal.strategy.pipeline.IDRetireSerializerStrategy;
import scaiev.scal.strategy.pipeline.NodeRegPipelineStrategy;
import scaiev.util.FileWriter;
import scaiev.util.Lang;
import scaiev.util.Log2;
import scaiev.util.ToWrite;
import scaiev.util.Verilog;

public class CVA5 extends CoreBackend {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  // TODO: Support for Mem_*_size, defaultAddr

  static final Path pathCore = Path.of("CoresSrc/CVA5");
  public String getCorePathIn() { return pathCore.toString(); }
  static final Path pathCVA5 = Path.of("core");
  static final String topModule = "cva5_wrapper_verilog";
  static final String targetModule = "scaiev_glue";
  static final String configPackage = "scaiev_config";
  static final String tab = "    ";

  private boolean use_native_RdRD = true;
  private boolean use_wbdecoupled_cva5alloc = true; // Set if decoupled writeback should use CVA5's allocator.
  private boolean use_reduced_fetchdecode_pipebuffers = true;

  /** The number of commit ports CVA5 has */
  static final int num_commit_ports = 2;
  /** The width of the core's issue ID space */
  static final int id_space_width = 3;
  /** The number of issue IDs, most commonly 2**(id_space_width) */
  static final int id_count = 8;

  static final int stagePos_fetch = 0;
  static final int stagePos_decode = 1;
  static final int stagePos_issue = 2;
  static final int stagePos_execute = 3;
  static final int pseudostagePos_spawn = 4;
  private PipelineStage stage_fetch;
  private PipelineStage stage_fetch_interm;
  private PipelineStage stage_decode;
  private PipelineStage stage_issue;
  private PipelineStage stage_execute;
  private PipelineStage stage_executeothers; // CVA5 non-ISAX execution units (placeholder)
  private PipelineStage pseudostage_spawn;
  private PipelineStage[] stages;
  private static final String[] cva5InterfaceStageNames = new String[] {"fetch", "decode", "issue", "execute", ""};

  static final String signame_issue_stall_before_cf = "issue_stall_before_cf";
  static final String signame_issue_stall_before_mem = "issue_stall_before_mem";
  static final String signame_execute_stall_mem = "execute_stall_mem";

  /**
   * Stored WrPC (if set from a later stage) if Pre-Fetch was stalling at the time.
   * -> WrPC from Decode,Issue,Execute may need to be repeated until the stall ends.
   */
  static final String signame_WrPC_reg_after_stall = "fetch_wrPC_r";
  static final String signame_WrPC_reg_after_stall_valid = "fetch_wrPCValid_r";

  /**
   * Comb WrPC from a later stage (applied to the Pre-Fetch stage)
   * -> needs to be visible on Pre-Fetch RdPC,
   *    so its WrPC does not accidentally override branch corrections / trap handler calls.
   */
  static final String signame_WrPC_after_Fetch = "wrPC_after_fetch";
  static final String signame_WrPC_after_Fetch_valid = "wrPC_after_fetch_valid";

  static final String signame_to_scal_stage_valid_all = "glueToSCAL_IValid_cond_all";
  static final String signame_to_scal_stage_valid_instr = "glueToSCAL_IValid_cond_instr";
  static final String signame_from_scal_rdivalid_unmasked = "SCALToGlue_RdIValid_unmasked";
  static final String signame_to_scal_wrrd_spawn_addr = "glueToSCAL_WrRD_spawn_addr";

  static final SCAIEVNode from_scal_rdivalid_unmasked_generic_node = new SCAIEVNode(signame_from_scal_rdivalid_unmasked, 1, true) {
    { oneInterfToISAX = false; }
  };

  private Core cva5_core;
  private HashMap<String, SCAIEVInstr> ISAXes;
  private HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  private FileWriter toFile = new FileWriter(pathCore.toString());
  private Verilog language = null;

  private HashMap<String, Boolean> configFlags = new HashMap<>();

  private void addLogic(String text) {
    toFile.UpdateContent(this.ModFile(targetModule), "endmodule", new ToWrite(text, false, true, "", true, targetModule));
  }
  private void addDeclaration(String text) {
    toFile.UpdateContent(this.ModFile(targetModule), ");", new ToWrite(text, true, false, "module ", false, targetModule));
  }
  private void setConfigFlag(String name, boolean newval) { configFlags.put(name, newval); }
  private void CommitConfigFlags() {
    configFlags.forEach((String name, Boolean newval) -> {
      toFile.ReplaceContent(this.ModFile(configPackage), "localparam " + name + " = ",
                            new ToWrite("localparam " + name + " = " + (newval ? "1" : "0") + ";", true, false, ""));
    });
  }
  private boolean ContainsOp(SCAIEVNode operation) {
    return op_stage_instr.containsKey(operation)
        && op_stage_instr.get(operation).values().stream().anyMatch(instrSet -> !instrSet.isEmpty());
  }
  private boolean ContainsOpInStage(SCAIEVNode operation, PipelineStage stage) {
    return op_stage_instr.containsKey(operation) && op_stage_instr.get(operation).containsKey(stage) &&
        !op_stage_instr.get(operation).get(stage).isEmpty();
  }
  private boolean ContainsOpInStage(SCAIEVNode operation, PipelineStage stage, String instr_name) {
    return op_stage_instr.containsKey(operation) && op_stage_instr.get(operation).containsKey(stage) &&
        op_stage_instr.get(operation).get(stage).contains(instr_name);
  }
  private Stream<SCAIEVInstr> getISAXesWithDecoupledOp(SCAIEVNode operation) {
    return getISAXesWithOpInAnyStage(operation).filter(isax -> isax.GetRunsAsDecoupled());
    //		return ISAXes.values().stream()
    //			.filter(isax -> {
    //				return isax.GetRunsAsDecoupled() && isax.HasNode(operation);
    //			});
  }
  private Stream<SCAIEVInstr> getISAXesWithOpInAnyStage(SCAIEVNode operation) {
    return !op_stage_instr.containsKey(operation) ? Stream.<SCAIEVInstr>of()
                                                  : op_stage_instr.get(operation)
                                                        .values()
                                                        .stream()
                                                        .flatMap(valuesSet -> valuesSet.stream())
                                                        .map(instr_name -> ISAXes.get(instr_name))
                                                        .filter(instr -> instr != null)
                                                        .distinct();
  }
  private Stream<SCAIEVInstr> getISAXesWithOpInStage(SCAIEVNode operation, PipelineStage stage) {
    return (!op_stage_instr.containsKey(operation) || !op_stage_instr.get(operation).containsKey(stage))
        ? Stream.<SCAIEVInstr>of()
        : op_stage_instr.get(operation).get(stage).stream().map(instr_name -> ISAXes.get(instr_name)).filter(instr -> instr != null);
  }

  @FunctionalInterface
  private interface RdValidPinConsumer {
    void accept(SCAIEVNode node, SCAIEVInstr isax, PipelineStage stage);
  }

  private void forEachRdValidPin(RdValidPinConsumer consumer) {
    //All ISAXes that use memory operations (counting indirect uses through op_stage_instr)
    Stream<NodeInstanceDesc.Key> ivalidKeyStream =
        Stream.of(BNode.RdMem, BNode.WrMem, BNode.RdMem_spawn, BNode.WrMem_spawn)
        .flatMap(node -> Stream.ofNullable(op_stage_instr.get(node)).flatMap(map -> map.entrySet().stream()))
        .filter(stage_instrs -> !stage_instrs.getKey().equals(pseudostage_spawn))
        .flatMap(stage_instrs -> stage_instrs.getValue().stream().map(instr -> instr))
        .distinct()
        .flatMap(instr -> Stream.of(
            new NodeInstanceDesc.Key(BNode.RdIValid, stage_issue, instr),
            new NodeInstanceDesc.Key(BNode.RdAnyValid.NodeNegInput(), stage_execute, instr)
        ));
    //All ISAXes: Decode; some special conditions.
    ivalidKeyStream = Stream.concat(ivalidKeyStream, ISAXes.entrySet().stream()
        .filter(isaxEntry -> !isaxEntry.getValue().HasNoOp())
        .flatMap(isaxEntry -> {
          String isaxName = isaxEntry.getKey();
          SCAIEVInstr isax = isaxEntry.getValue();
          var ret = Stream.of(new NodeInstanceDesc.Key(BNode.RdIValid, stage_decode, isaxName));
          if (isax.equals(SCAL.PredefInstr.kill.instr))
            ret = Stream.concat(ret, Stream.of(new NodeInstanceDesc.Key(BNode.RdIValid, stage_issue, isaxName)));
          else if (isax.HasNode(BNode.WrRD_spawn)) // Required for use_wbdecoupled_cva5alloc
            ret = Stream.concat(ret, Stream.of(new NodeInstanceDesc.Key(BNode.RdIValid, stage_issue, isaxName)));
          if (isax.HasNode(BNode.WrPC))
            ret = Stream.concat(ret, Stream.of(new NodeInstanceDesc.Key(BNode.RdIValid, stage_execute, isaxName)));
          return ret;
        }));

    //Call the consumer for each _unique_ pin.
    ivalidKeyStream.distinct().forEach(ivalidKey -> {
      consumer.accept(ivalidKey.getNode(), ISAXes.get(ivalidKey.getISAX()), ivalidKey.getStage());
    });
  }

  private String signame_to_scal_pipeinto_executesv1;
  private String signame_to_scal_pipeinto_executecva;

  @Override
  public void Prepare(HashMap<String, SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                      Core core, SCALBackendAPI scalAPI, BNode user_BNode) {
    super.Prepare(ISAXes, op_stage_instr, core, scalAPI, user_BNode);
    use_wbdecoupled_cva5alloc = !scalAPI.getSVConfig().cva5_wrrdspawn_injectmode;
    use_reduced_fetchdecode_pipebuffers = !scalAPI.getSVConfig().cva5_fetchdecodepipe_wideid;
    this.BNode = user_BNode;
    this.BNode.AddCoreBNode(node_RdFetchID);
    this.BNode.AddCoreBNode(node_RdFetchPostFlushID);
    this.BNode.AddCoreBNode(node_RdFetchFlushCount);
    this.language = new Verilog(user_BNode, toFile, this);
    this.cva5_core = core;
    this.ISAXes = ISAXes;
    this.op_stage_instr = op_stage_instr;

    this.stage_fetch = core.GetRootStage().getChildren().get(0);
    this.stage_fetch_interm =
        new PipelineStage(StageKind.CoreInternal, EnumSet.of(StageTag.InOrder), "fetch_intermediate", Optional.empty(), true);
    this.stage_decode = stage_fetch.getNext().get(0);
    this.stage_fetch.addNext(stage_fetch_interm);
    stage_fetch_interm.addNext(stage_decode);

    this.stage_issue = stage_decode.getNext().get(0);
    this.stage_execute = stage_issue.getNext().stream().filter(stage -> stage.getName().equals("executesv1")).findAny().orElseThrow();
    this.stage_executeothers = stage_issue.getNext().stream().filter(stage -> stage.getName().equals("executecva")).findAny().orElseThrow();
    this.pseudostage_spawn = stage_execute.getNext().stream().filter(stage -> stage.getName().equals("decoupled1")).findAny().orElseThrow();
    this.stages = new PipelineStage[] {stage_fetch, stage_decode, stage_issue, stage_execute, pseudostage_spawn};
    for (int i = 0; i < this.stages.length; ++i)
      assert (this.stages[i].getStagePos() == i);

    core.GetNodes().get(BNode.WrFlush).OverrideEarliest(new ScheduleFront(new PipelineFront(List.of(stage_fetch_interm, stage_decode))));

    BNode.RdInstr_RS.size = 6*2;
    BNode.RdInstr_RS.elements = 2;
    core.PutNode(BNode.RdInstr_RS, new CoreNode(stagePos_decode, 0, stagePos_decode, stagePos_issue, BNode.RdInstr_RS.name));
    BNode.RdInstr_RD.size = 6;
    BNode.RdInstr_RD.elements = 1;
    core.PutNode(BNode.RdInstr_RD, new CoreNode(stagePos_decode, 0, stagePos_decode, stagePos_issue, BNode.RdInstr_RD.name));

    // Support for pipelined semi-coupled: Transfer handling of an instruction from the core's execution unit to SCAL.
    core.PutNode(BNode.WrDeqInstr, new CoreNode(stagePos_execute, 0, stagePos_execute, stagePos_execute + 1, BNode.WrDeqInstr.name));
    core.PutNode(BNode.RdInStageID, new CoreNode(stagePos_execute, 0, stagePos_execute, stagePos_execute + 1, BNode.RdInStageID.name));
    BNode.RdInStageID.size = id_space_width + 1; // ID and flag 'expects rd'
    core.PutNode(BNode.RdInStageValid, new CoreNode(stagePos_fetch, 0, stagePos_execute, stagePos_execute + 1, BNode.RdInStageValid.name));
    BNode.WrInStageID.size = id_space_width + 1; // ID and flag 'expects rd'
    core.PutNode(BNode.WrInStageID, new CoreNode(stagePos_execute, 0, stagePos_execute, stagePos_execute + 1, BNode.WrInStageID.name));

    // Commit information: Pass instruction ID to the core,
    ScheduleFront rootSchedFront = new ScheduleFront(new PipelineFront(core.GetRootStage()));
    BNode.RdIssueID.size = id_space_width;
    BNode.RdIssueID.elements = id_count;
    core.PutNode(BNode.RdIssueID, new CoreNode(stagePos_issue, 0, stagePos_execute, stagePos_execute + 1, BNode.RdIssueID.name));
    BNode.RdIssueFlushID.size = id_space_width;
    BNode.RdIssueFlushID.elements = id_count;
    core.PutNode(BNode.RdIssueFlushID, new CoreNode(stagePos_issue, 0, stagePos_execute, stagePos_execute + 1, BNode.RdIssueFlushID.name));
    //core.PutNode(BNode.RdIssueIDValid, new CoreNode(stagePos_issue, 0, stagePos_execute, stagePos_execute + 1, BNode.RdIssueIDValid.name));
    BNode.RdCommitID.size = id_space_width;
    BNode.RdCommitID.elements = id_count;
    BNode.RdCommitIDCount.elements = num_commit_ports;
    BNode.RdCommitIDCount.size = Log2.clog2(num_commit_ports+1);
    core.PutNode(BNode.RdCommitID, new CoreNode(rootSchedFront, 0, rootSchedFront, new ScheduleFront(), BNode.RdCommitID.name));
    core.PutNode(BNode.RdCommitIDCount, new CoreNode(rootSchedFront, 0, rootSchedFront, new ScheduleFront(), BNode.RdCommitIDCount.name));
    BNode.RdCommitFlushID.size = id_space_width;
    BNode.RdCommitFlushID.elements = id_count;
    BNode.RdCommitFlushIDCount.elements = num_commit_ports;
    BNode.RdCommitFlushIDCount.size = Log2.clog2(num_commit_ports+1);
    BNode.RdCommitFlushMask.size = 0; //not used
    core.PutNode(BNode.RdCommitFlushID, new CoreNode(rootSchedFront, 0, rootSchedFront, new ScheduleFront(), BNode.RdCommitFlushID.name));
    core.PutNode(BNode.RdCommitFlushIDCount, new CoreNode(rootSchedFront, 0, rootSchedFront, new ScheduleFront(), BNode.RdCommitFlushIDCount.name));
    core.PutNode(BNode.RdCommitFlushMask, new CoreNode(rootSchedFront, 0, rootSchedFront, new ScheduleFront(), BNode.RdCommitFlushMask.name));

    BNode.RdMem_addr.mustToCore = true;
    BNode.WrMem_addr.mustToCore = true;
    BNode.WrRD_spawn_cancel.mustToCore = true;

    // NodeRegPipelineStrategy looks for an optional RdPipeInto node with "stage_<dest stage name>" in the ISAX field.
    var interface_toscal_pipeinto_executesv1 =
        new CustomCoreInterface(BNode.RdPipeInto.name, "wire", stage_issue, 1, true, "stage_" + stage_execute.getName());
    scalAPI.AddCustomToSCALPin(interface_toscal_pipeinto_executesv1);
    this.signame_to_scal_pipeinto_executesv1 = interface_toscal_pipeinto_executesv1.getSignalName(this.language, true);
    var interface_toscal_pipeinto_executecva =
        new CustomCoreInterface(BNode.RdPipeInto.name, "wire", stage_issue, 1, true, "stage_" + stage_executeothers.getName());
    scalAPI.AddCustomToSCALPin(interface_toscal_pipeinto_executecva);
    this.signame_to_scal_pipeinto_executecva = interface_toscal_pipeinto_executecva.getSignalName(this.language, true);

    ////Only for IDBasedPipelineStrategy testing (force RdPC to be pipelined):
    // core.GetNodes().get(BNode.RdPC).OverrideLatest(new ScheduleFront(new PipelineFront(List.of(stage_fetch, stage_fetch_interm))));
    // core.GetNodes().get(BNode.RdPC).OverrideExpensive(new ScheduleFront(new PipelineFront(stage_decode)));

    for (int stagePos = stagePos_decode; stagePos <= stagePos_execute; ++stagePos) {
      PipelineStage stage = stages[stagePos];
      CustomCoreInterface valid_to_scal_generic_interface =
          new CustomCoreInterface(signame_to_scal_stage_valid_all, "wire", stage, 1, true,
                                  ""); // TODO we should avoid adding custom interfaces between SCAL and core. This contradicts the concept
                                       // of SCAL. However, I also have a case of custom interf. We need to discuss this.
      scalAPI.RegisterRdIValid_valid(valid_to_scal_generic_interface);
    }
    for (String instr_name : ISAXes.keySet()) {
      boolean has_any_rdRD =
          this.ContainsOpInStage(BNode.RdRD, stage_issue, instr_name) || this.ContainsOpInStage(BNode.RdRD, stage_execute, instr_name);
      if (has_any_rdRD && use_native_RdRD) {
        BNode.RdInstr_RS.size = 6*3;
        BNode.RdInstr_RS.elements = 3;
      }
      if (has_any_rdRD && !use_native_RdRD) {
        for (int stagePos = stagePos_decode; stagePos <= stagePos_execute; ++stagePos) {
          PipelineStage stage = stages[stagePos];
          CustomCoreInterface valid_to_scal_instr_interface =
              new CustomCoreInterface(signame_to_scal_stage_valid_instr, "wire", stage, 1, true, instr_name);
          scalAPI.RegisterRdIValid_valid(valid_to_scal_instr_interface);
          scalAPI.RegisterNodeOverrideForISAX(BNode.RdRS1, instr_name);
        }
        // We need a variant of RdIValid in decode that does not include our combinational valid condition,
        //  since we would get combinational loops otherwise.
        // This adds an interface pin for RdIValid with Purpose.WIREDIN.
        CustomCoreInterface valid_from_scal_instr_interface =
            new CustomCoreInterface(signame_from_scal_rdivalid_unmasked, "wire", stage_decode, 1, false, instr_name);
        scalAPI.AddCustomToCorePinUsing(
            valid_from_scal_instr_interface, NodeLogicBuilder.fromFunction("unmasked RdIValid " + instr_name, registry -> {
              var ret = new NodeLogicBlock();
              ret.outputs.add(new NodeInstanceDesc(
                  valid_from_scal_instr_interface.makeKey(Purpose.REGULAR),
                  registry.lookupExpressionRequired(new NodeInstanceDesc.Key(Purpose.WIREDIN, BNode.RdIValid, stage_decode, instr_name)),
                  ExpressionType.AnyExpression));
              return ret;
            }));
      }
    }
    // Request RdIValid/RdAnyValid interface pins
    forEachRdValidPin((node, isax, stage) -> scalAPI.RequestToCorePin(node, stage, isax.GetName()));

    scalAPI.SetHasAdjSpawnAllowed(BNode.RdMem_spawn_allowed);
    scalAPI.SetHasAdjSpawnAllowed(BNode.WrMem_spawn_allowed);
    scalAPI.SetHasAdjSpawnAllowed(BNode.WrRD_spawn_allowed);
    // WrRD allocate method: Custom behaviour in general.
    // WrRD inject method: Need to handle hazards in Decode already (implemented in scaiev_glue.sv), which is before the 'start spawn node'
    // (=Issue) that SCAL's DH watches.
    scalAPI.SetUseCustomSpawnDH();
    // Resource conflict resolution is handled via scaiev_glue.sv for both WrRD methods.
    //  -> WrRD allocate method: SCAL does not have access to the various execution unit stalls, so this is handled within the CVA5 fork.
    //  -> WrRD inject method: Original instruction entering Decode is preserved for the next cycle (/ until no injection is done in a
    //  cycle).
    scalAPI.DisableStallForSpawnCommit(BNode.WrRD_spawn);

    // scaiev_glue also handles memory resource conflicts.
    scalAPI.DisableStallForSpawnCommit(BNode.WrMem_spawn);
    scalAPI.DisableStallForSpawnCommit(BNode.RdMem_spawn);

    if (use_wbdecoupled_cva5alloc) {
      BNode.WrRD_spawn_addr.size = 6 + 1 + 6;
      BNode.committed_rd_spawn.size = 6 + 1 + 6;
      CustomCoreInterface addr_to_scal_interface =
          new CustomCoreInterface(signame_to_scal_wrrd_spawn_addr, "wire", stage_issue, BNode.WrRD_spawn_addr.size, true, "");
      scalAPI.OverrideSpawnRDAddr(addr_to_scal_interface);
      CustomCoreInterface addr_to_scal_interface_decode =
          new CustomCoreInterface(signame_to_scal_wrrd_spawn_addr, "wire", stage_decode, BNode.WrRD_spawn_addr.size, true, "");
      scalAPI.OverrideSpawnRDAddr(addr_to_scal_interface_decode);
    }

    node_RdFetchID.size = id_space_width;
    node_RdFetchPostFlushID.size = id_space_width;
    node_RdFetchFlushCount.size = id_space_width+1;
    node_RdFetchFlushCount.elements = id_count;
    scalAPI.getStrategyBuilders().put(StrategyBuilders.UUID_NodeRegPipelineStrategy, args -> this.build_cva5NodeRegPipelineStrategy(args));
  }

  private SCAIEVNode node_RdFetchID = new SCAIEVNode("RdCVA5FetchID", 3, false) {
    { tags.add(NodeTypeTag.staticReadResult); }
  };
  private SCAIEVNode node_RdFetchPostFlushID = new SCAIEVNode("RdCVA5FetchPostFlushID", 3, false);
  private SCAIEVNode node_RdFetchFlushCount = new SCAIEVNode("RdCVA5FetchFlushCount", 4, false);

  private List<MultiNodeStrategy> idbasedPipelineSubstrategies = new ArrayList<>();
  @SuppressWarnings("unchecked")
  private MultiNodeStrategy build_cva5NodeRegPipelineStrategy(Map<String, Object> args) {
    // Build a NodeRegPipelineStrategy that calls into IDBasedPipelineStrategy objects when faced with fetch-to-decode pipelining.
    return new NodeRegPipelineStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (PipelineFront)args.get("minPipeFront"),
                                       (Boolean)args.get("zeroOnFlushSrc"), (Boolean)args.get("zeroOnFlushDest"),
                                       (Boolean)args.get("zeroOnBubble"), (Predicate<NodeInstanceDesc.Key>)args.get("can_pipe"),
                                       (Predicate<NodeInstanceDesc.Key>)args.get("prefer_direct"),
                                       (MultiNodeStrategy)args.get("strategy_instantiateNew"),
                                       (Boolean)args.get("forwardRequestedFor")) {
      // Use one strategy for all nodes pipelined at full ID width (i.e. cannot run out of IDs/buffer space before the core does)
      private IDBasedPipelineStrategy pipeFullWidth = null;
      // Use one strategy per custom register node at a reduced ID width, so unrelated registers do not reserve buffer space for each other.
      private HashMap<SCAIEVNode, IDBasedPipelineStrategy> pipeReducedWidthByCustomReg = new HashMap<>();

      private IDBasedPipelineStrategy makeFetchDecodePipeStrategy(int idWidth) {
        if (idWidth > id_space_width)
          idWidth = id_space_width;
        return new IDBasedPipelineStrategy(
            language, bNodes, node_RdFetchID, new NodeInstanceDesc.Key(node_RdFetchPostFlushID, stage_fetch_interm, ""), idWidth,
            new PipelineFront(stage_fetch_interm), true, true, new PipelineFront(stage_decode),
            List.of(new IDRetireSerializerStrategy.IDAndCountRetireSource(
                         new NodeInstanceDesc.Key(node_RdFetchPostFlushID, stage_fetch_interm, ""),
                         new NodeInstanceDesc.Key(node_RdFetchFlushCount, stage_fetch_interm, ""), true)),
            key -> key.getStage().equals(stage_decode));
      }

      protected boolean useReducedWidthForNode(SCAIEVNode node) {
        if (!use_reduced_fetchdecode_pipebuffers)
          return false;
        if (node.equals(bNodes.RdOrigPC) || node.equals(bNodes.RdOrigPC_valid))
          return true; // Associated with ZOL
        return (bNodes.IsUserFNode(node) || bNodes.IsUserBNode(node)) && node.name.equals(bNodes.GetNameWrNode(node));
      }

      @Override
      protected NodeLogicBuilder makePipelineBuilder_single(NodeInstanceDesc.Key nodeKey, ImplementedKeyInfo implementation) {
        if (!nodeKey.getStage().equals(stage_decode)) {
          boolean old_onFlushDest = super.zeroOnFlushDest;
          if (nodeKey.getStage().equals(stage_fetch_interm)) {
            // validReqs (etc.) will not naturally get cleared from stage_fetch_interm.
            // This is because stage_fetch_interm is actually the first stage of the core,
            //  so on flush, the first pipeline entry will not be updated from stage_fetch.

            //- During a flush, the ISAX is allowed to control validReq (etc.) in stage_fetch, thus keep zeroOnFlushSrc unchanged
            //  (also, RdFlush = 0 for stage_fetch)
            //- Otherwise, validReq (etc.) needs to be zeroed:
            super.zeroOnFlushDest = true;
          }
          NodeLogicBuilder ret = super.makePipelineBuilder_single(nodeKey, implementation);
          super.zeroOnFlushDest = old_onFlushDest; // true
          return ret;
        }

        IDBasedPipelineStrategy strategy = null;
        if (useReducedWidthForNode(nodeKey.getNode())) {
          // For custom register writes, reduce the number of pending elements to 3 (2 ID bits, with reserved 'invalid' ID).
          SCAIEVNode baseNode = bNodes.GetNonAdjNode(nodeKey.getNode());
          // if (nodeKey.getNode().getAdj().validMarkerFor != null)
          //	baseNode = bNodes.GetAdjSCAIEVNode(baseNode, nodeKey.getNode().getAdj().validMarkerFor).orElseThrow();
          strategy = pipeReducedWidthByCustomReg.computeIfAbsent(baseNode, baseNode_ -> {
            var ret = makeFetchDecodePipeStrategy(2);
            idbasedPipelineSubstrategies.add(ret);
            return ret;
          });
        } else {
          if (pipeFullWidth == null) {
            pipeFullWidth = makeFetchDecodePipeStrategy(id_space_width);
            idbasedPipelineSubstrategies.add(pipeFullWidth);
          }
          strategy = pipeFullWidth;
        }
        List<NodeLogicBuilder> subBuilders = new ArrayList<>();
        List<NodeInstanceDesc.Key> nodeKeys = new ArrayList<>();
        nodeKeys.add(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, IDBasedPipelineStrategy.purpose_ReadFromIDBasedPipeline));
        strategy.addKeyImplementation(nodeKey, implementation);
        strategy.implement(builderToAdd -> subBuilders.add(builderToAdd), nodeKeys, false);
        return CombinedNodeLogicBuilder.of("CVA5_IDBasedPipelineStrategy-fetch-to-decode(" + nodeKey.toString() + ")", subBuilders);
      }
    };
  }

  @Override
  public List<MultiNodeStrategy> getAdditionalSCALPreStrategies() {
    // Add a strategy that adds required RdFetchID, RdFetchPostFlushID interface pins.
    return List.of(new MultiNodeStrategy() {
      boolean implemented_RdFetchID_fetch = false;
      boolean implemented_RdFetchID_decode = false;
      boolean implemented_RdFetchPostFlushID = false;
      boolean implemented_RdFetchFlushCount = false;
      private NodeLogicBuilder makeInterfaceRequestBuilder(NodeInstanceDesc.Key nodeKey) {
        NodeInstanceDesc.Key nodeKeyReq = NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.MARKER_FROMCORE_PIN);
        return NodeLogicBuilder.fromFunction("Request core->SCAL " + nodeKeyReq.toString(false), registry -> {
          registry.lookupExpressionRequired(nodeKeyReq);
          return new NodeLogicBlock();
        });
      }
      @Override
      public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
        var nodeKeyIter = nodeKeys.iterator();
        while (nodeKeyIter.hasNext()) {
          var nodeKey = nodeKeyIter.next();
          if (!nodeKey.getPurpose().matches(Purpose.WIREDIN) || !nodeKey.getISAX().isEmpty() || nodeKey.getAux() != 0)
            continue;
          if (!implemented_RdFetchID_fetch && nodeKey.getNode().equals(node_RdFetchID) && nodeKey.getStage().equals(stage_fetch_interm)) {
            out.accept(makeInterfaceRequestBuilder(nodeKey));
            implemented_RdFetchID_fetch = true;
          } else if (!implemented_RdFetchID_decode && nodeKey.getNode().equals(node_RdFetchID) && nodeKey.getStage().equals(stage_decode)) {
            out.accept(makeInterfaceRequestBuilder(nodeKey));
            implemented_RdFetchID_decode = true;
          } else if (!implemented_RdFetchPostFlushID && nodeKey.getNode().equals(node_RdFetchPostFlushID) &&
                     nodeKey.getStage().equals(stage_fetch_interm)) {
            out.accept(makeInterfaceRequestBuilder(nodeKey));
            implemented_RdFetchPostFlushID = true;
          } else if (!implemented_RdFetchFlushCount && nodeKey.getNode().equals(node_RdFetchFlushCount) &&
                     nodeKey.getStage().equals(stage_fetch_interm)) {
            out.accept(makeInterfaceRequestBuilder(nodeKey));
            implemented_RdFetchFlushCount = true;
          }
        }
        idbasedPipelineSubstrategies.forEach(substrategy -> substrategy.implement(out, nodeKeys, isLast));
      }
    });
  }

  public boolean Generate(HashMap<String, SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                          String extension_name, Core core, String out_path) {
    this.cva5_core = core;
    this.ISAXes = ISAXes;
    this.op_stage_instr = op_stage_instr;

    ConfigCVA5();
    language.clk = "clk";
    language.reset = "rst";

    IntegrateISAX_Defaults();
    IntegrateISAX_IOs();
    IntegrateISAX_NoIllegalInstr();
    IntegrateISAX_RdIValid();
    IntegrateISAX_RdRD();
    IntegrateISAX_WrStall();
    IntegrateISAX_WrFlush();
    IntegrateISAX_WrRD();
    if (use_wbdecoupled_cva5alloc)
      IntegrateISAX_WrRDSpawn_allocatemethod();
    else
      IntegrateISAX_WrRDSpawn_injectmethod();
    IntegrateISAX_Mem();
    IntegrateISAX_WrPC();
    IntegrateISAX_MiscPipeline();

    CommitConfigFlags();

    language.FinalizeInterfaces();
    toFile.WriteFiles(language.GetDictModule(), language.GetDictEndModule(), out_path);

    return true;
  }

  private void IntegrateISAX_Defaults() {
    int lastJumpStage = -1;
    for (int i = 0; i <= stagePos_execute; i++)
      if (this.ContainsOpInStage(BNode.WrPC, stages[i]))
        lastJumpStage = i;

    for (int i = 0; i <= stagePos_execute; i++) {
      if (i > lastJumpStage) {
        addLogic("wire ISAX_wrPCValid_" + i + "_s = 0;");
      }
    }
  }

  private void IntegrateISAX_IOs() {
    // Add all interface pins except RdIValid.
    //  GenerateAllInterfaces does not generate per-ISAX pins.
    language.GenerateAllInterfaces(topModule,
                                   op_stage_instr.entrySet()
                                       .stream()
                                       .filter(entry_
                                               -> !entry_.getKey().equals(BNode.RdIValid) && !entry_.getKey().equals(BNode.RdAnyValid) &&
                                                      !entry_.getKey().equals(BNode.RdPipeInto))
                                       .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b, HashMap::new)),
                                   ISAXes, cva5_core, null);

    // Add RdIValid/RdAnyValid pins per ISAX.
    forEachRdValidPin((node, isax, stage) -> language.UpdateInterface(topModule, node.NodeNegInput(), isax.GetName(), stage, true, false));

    language.UpdateInterface(topModule, BNode.RdPipeInto, "stage_" + stage_execute.getName(), stage_issue, true, false);
    language.UpdateInterface(topModule, BNode.RdPipeInto, "stage_" + stage_executeothers.getName(), stage_issue, true, false);
  }

  // Standard opcodes that ISAXes could use (due to free funct3, funct7 encoding space)
  private static final List<String> stdOpcodesWithFreeFunct =
      List.of("1100011" /*BRANCH*/, "0000011" /*LOAD*/, "0100011" /*STORE*/, "0010011" /*OP-IMM*/, "0110011" /*OP*/
      );
  private static boolean EncodingMatches(String query, String ref) {
    if (query == null)
      return true;
    assert (query.length() == ref.length());
    for (int i = 0; i < query.length(); ++i) {
      if (query.charAt(i) != '-' && query.charAt(i) != ref.charAt(i))
        return false;
    }
    return true;
  }

  private void IntegrateISAX_NoIllegalInstr() {
    List<String> allISAXes =
        ISAXes.entrySet().stream().filter(isaxEntry -> !isaxEntry.getValue().HasNoOp()).map(isaxEntry -> isaxEntry.getKey()).toList();
    List<String> allISAXes_stdencoding =
        ISAXes.entrySet()
            .stream()
            .filter(isaxEntry -> {
              return !isaxEntry.getValue().HasNoOp() &&
                  stdOpcodesWithFreeFunct.stream().anyMatch(ref -> EncodingMatches(isaxEntry.getValue().GetEncodingOp(Lang.None), ref));
            })
            .map(isaxEntry -> isaxEntry.getKey())
            .toList();
    // Generate lists of ISAXes by register read/write fields in the encoding.
    List<String> allISAXes_RS1;
    List<String> allISAXes_RS2;
    List<String> allISAXes_RD_AS_RS;
    List<String> allISAXes_RD;
    List<String> allISAXes_RDdecoupled_cva5alloc;
    allISAXes_RS1 = getISAXesWithOpInAnyStage(BNode.RdRS1).filter(instr -> !instr.HasNoOp()).map(instr -> instr.GetName()).toList();
    allISAXes_RS2 = getISAXesWithOpInAnyStage(BNode.RdRS2).filter(instr -> !instr.HasNoOp()).map(instr -> instr.GetName()).toList();
    allISAXes_RD_AS_RS =
        use_native_RdRD ? getISAXesWithOpInAnyStage(BNode.RdRD).filter(instr -> !instr.HasNoOp()).map(instr -> instr.GetName()).toList()
                        : List.of();
    allISAXes_RD = getISAXesWithOpInAnyStage(BNode.WrRD).filter(instr -> !instr.HasNoOp()).map(instr -> instr.GetName()).toList();
    allISAXes_RDdecoupled_cva5alloc =
        use_wbdecoupled_cva5alloc
            ? getISAXesWithOpInAnyStage(BNode.WrRD_spawn).filter(instr -> !instr.HasNoOp()).map(instr -> instr.GetName()).toList()
            : List.of();

    Function<List<String>, String> makeIValidExpression = isaxNames -> {
      return isaxNames.stream()
          .map(isaxName -> {
            boolean has_rdrd =
                this.ContainsOpInStage(BNode.RdRD, stage_issue, isaxName) || this.ContainsOpInStage(BNode.RdRD, stage_execute, isaxName);
            if (has_rdrd && !use_native_RdRD)
              return language.CreateNodeName(from_scal_rdivalid_unmasked_generic_node, stage_decode, isaxName);
            return language.CreateNodeName(BNode.RdIValid.NodeNegInput(), stage_decode, isaxName);
          })
          .reduce((a, b) -> a + " || " + b)
          .orElse("1'b0");
    };
    addLogic("assign scaiev.decode_isSCAIEV = " + makeIValidExpression.apply(allISAXes) + ";");
    addLogic("assign scaiev.decode_isSCAIEV_stdencoding = " + makeIValidExpression.apply(allISAXes_stdencoding) + ";");
    addLogic("assign scaiev.decode_isSCAIEV_usesRS1 = " + makeIValidExpression.apply(allISAXes_RS1) + ";");
    addLogic("assign scaiev.decode_isSCAIEV_usesRS2 = " + makeIValidExpression.apply(allISAXes_RS2) + ";");
    addLogic("assign scaiev.decode_isSCAIEV_usesRD_AS_RS = " + makeIValidExpression.apply(allISAXes_RD_AS_RS) + ";");
    addLogic("assign scaiev.decode_isSCAIEV_usesRD = " + makeIValidExpression.apply(allISAXes_RD) + ";\n");
    addLogic("assign scaiev.decode_isSCAIEV_usesRD_decoupled = " + makeIValidExpression.apply(allISAXes_RDdecoupled_cva5alloc) + ";\n");

    if (ContainsOpInStage(BNode.RdInstr_RD, stage_decode)) {
      //NOTE: Assuming scaiev.decode_wrReg <=> decoupled writeback with inject approach.
      // -> Hiding injected decoupled writebacks, so SCAL's DH mechanism lets them pass.
      String rdInstr_RD_expr = "scaiev.decode_RD_valid && !scaiev.decode_wrReg, scaiev.decode_RD_id";
      addLogic("assign %s = {%s};".formatted(language.CreateNodeName(BNode.RdInstr_RD, stage_decode, ""), rdInstr_RD_expr));
    }
    if (ContainsOpInStage(BNode.RdInstr_RS, stage_decode)) {
      String rdInstr_RS_expr = "scaiev.decode_RS2_valid, scaiev.decode_RS2_id, scaiev.decode_RS1_valid, scaiev.decode_RS1_id";
      if (use_native_RdRD && ContainsOp(BNode.RdRD)) {
        assert(BNode.RdInstr_RS.size == 6*3);
        rdInstr_RS_expr = "scaiev.decode_RD_AS_RS_valid, scaiev.decode_RD_AS_RS_id, " + rdInstr_RS_expr;
      }
      else
        assert(BNode.RdInstr_RS.size == 6*2);
      addLogic("assign %s = {%s};".formatted(language.CreateNodeName(BNode.RdInstr_RS, stage_decode, ""), rdInstr_RS_expr));
    }
  }

  private void IntegrateISAX_RdIValid() {
    // Generate IValid logic:
    // For each stage, tells each ISAX whether it has an active instruction.
    for (int stagePos = stagePos_fetch; stagePos <= stagePos_execute; ++stagePos) {
      PipelineStage stage = stages[stagePos];
      SCAIEVNode valid_to_scal_generic_node = new SCAIEVNode(signame_to_scal_stage_valid_all, 1, false) {
        { oneInterfToISAX = true; }
      };

      String validSig = "";
      // Overall valid signal by stage (regardless of whether it runs a given ISAX).
      switch (stagePos) {
      default:
      case stagePos_fetch:
        validSig = "0";
        break; // Fetch not relevant, as it is before the instruction word has been read.
      case stagePos_decode:
        validSig = "scaiev.decode_valid && !scaiev.decode_wrReg && !scaiev.decode_rdReg";
        break;
      case stagePos_issue:
        validSig = "scaiev.issue_valid";
        break; //&& !scaiev.issue_injected - would hide injected instructions (bad for ISAX with non-native RdRD)
      case stagePos_execute:
        validSig = "scaiev.execute_valid";
        break;
      }
      this.PutNode("logic", "", "scaiev_glue", valid_to_scal_generic_node, stage);
      if (stage != stage_fetch) {
        addLogic("assign " + language.CreateNodeName(valid_to_scal_generic_node, stage, "", false) + " = " + validSig + ";");
        language.UpdateInterface(topModule, valid_to_scal_generic_node, "", stage, true, false);
      }

      SCAIEVNode valid_to_scal_perISAX_node = new SCAIEVNode(signame_to_scal_stage_valid_instr, 1, false) {
        { oneInterfToISAX = false; }
      };
      this.PutNode("logic", "", "scaiev_glue", valid_to_scal_perISAX_node, stage);

      String flushSig = language.CreateNodeName(BNode.RdFlush, stage, "");
      if (!this.ContainsOpInStage(BNode.RdFlush, stage)) {
        flushSig = this.NodeAssign(BNode.RdFlush, stage);
      }
      // Instruction: Either the RdInstr field, or in case of fetch, an undefined value.
      //			String instrSig = "";
      //			if (stage != stage_fetch)
      //				instrSig = this.NodeAssign(BNode.RdInstr, stage);
      validSig = " && (" + validSig + ")";
      flushSig = " && !(" + flushSig + ")";

      for (var isaxEntry : ISAXes.entrySet()) {
        if (isaxEntry.getValue().HasNoOp())
          continue;
        String instrName = isaxEntry.getKey();
        // For each ISAX that uses RdIValid or WrRD in the current stage,
        //  declare and assign a local RdIValid node.
        // In the former case, assign the ISAX signal, too.
        boolean has_any_rdRD =
            this.ContainsOpInStage(BNode.RdRD, stage_issue, instrName) || this.ContainsOpInStage(BNode.RdRD, stage_execute, instrName);
        String extra_cond = "";
        if (has_any_rdRD && !use_native_RdRD) {
          // For instructions with RdRD, the instruction is issued twice.
          // Only the second time is to be shown to the ISAX, as that is when all requested register values are available.
          switch (stagePos) {
          case stagePos_decode:
            extra_cond = "(!decode_rdReg_RD || decode_isRepeated_RdRD)";
            break;
          case stagePos_issue:
            extra_cond = "!issue_isFirst_RdRD";
            break;
          case stagePos_execute:
            extra_cond = "!execute_isFirst_RdRD";
            break;
          default:
            extra_cond = "0";
            break;
          }
          if (stage != stage_fetch) {
            addLogic("assign " + language.CreateNodeName(valid_to_scal_perISAX_node, stage, instrName, false) + " = " + extra_cond + ";");
            language.UpdateInterface(topModule, valid_to_scal_perISAX_node, instrName, stage, false, false);
          }
        }
        //				if (!extra_cond.isEmpty())
        //					extra_cond = " && " + extra_cond;
        //				String ivalid_local_node_name = language.CreateLocalNodeName(BNode.RdIValid, stage, instr_name);
        //				addDeclaration("logic " + ivalid_local_node_name + ";");
        //				addLogic("assign " + ivalid_local_node_name + " = "
        //						+ ((instrSig.isEmpty()) ? "0" : language.CreateAllEncoding(new HashSet<String>()
        //{{add(instr_name);}}, ISAXes, instrSig))
        //						+ validSig+flushSig+extra_cond
        //						+ ";");
      }
    }
  }

  private void IntegrateISAX_RdRD() {
    // Operation: Read original value of ISAX' destination register.
    addDeclaration("logic decode_wrReg_hold;");
    addDeclaration("logic decode_rdReg_RD;");
    addDeclaration("logic decode_isRepeated_RdRD;");
    addDeclaration("logic issue_isFirst_RdRD;");
    addDeclaration("logic execute_isFirst_RdRD;");

    boolean has_any_rdRD = ContainsOpInStage(BNode.RdRD, stage_execute) || ContainsOpInStage(BNode.RdRD, stage_issue);
    if (has_any_rdRD && use_native_RdRD) {
      setConfigFlag("ENABLE_NATIVE_RD_AS_RS", true);
    }
    if (has_any_rdRD && !use_native_RdRD) {
      HashSet<String> rdRD_ISAXes = new HashSet<String>();
      rdRD_ISAXes.addAll(
          getISAXesWithOpInStage(BNode.RdRD, stage_issue).filter(isax -> !isax.HasNoOp()).map(isax -> isax.GetName()).toList());
      rdRD_ISAXes.addAll(
          getISAXesWithOpInStage(BNode.RdRD, stage_execute).filter(isax -> !isax.HasNoOp()).map(isax -> isax.GetName()).toList());

      for (String isaxName : rdRD_ISAXes) {
        // Add the interface pins for the unmasked RdIValid.
        language.UpdateInterface(topModule, from_scal_rdivalid_unmasked_generic_node, isaxName, stage_decode, true, false);
      }

      // Have to read the 'third' register.
      //-> Show instruction twice: Prevent writeback in the first cycle, in the second cycle override RS1 with RD, and enable writeback.
      //-> Otherwise, the core would stall the read to wait for our instruction's writeback
      //    (in all cases where rd != x0), leading to a deadlock.

      //- On Decode of RdRD ISAX:
      //  First cycle: Mark instr. as 'no writeback' (to CVA5 and to SCAIE-V Scoreboard), keep RS1&RS2 as-is
      //   RdIValid (see resp. method): Hide first instance of instr. in pipeline from ISAX [Decode,Issue,Execute], but put at least the
      //   RS1,RS2 values into scaiev_unit (Execute stage)
      //  Second cycle: Repeat instr., this time with writeback and RS1:=RD
      //    Make sure scaiev_unit is stalled until reg[RD] is known synchronously. -> Representation of Execute stage even with WrRD_spawn
      //    Show instr. in Decode to ISAX now, also in Issue and Execute.
      //    For Issue stage to ISAX, show RS1,RS2 from scaiev_unit instead of from Issue stage. Show reg[RD] from Issue stage's RS1 value.

      String cond_inject_rdReg =
          "scaiev.decode_valid && !decode_isRepeated_RdRD && ("
          // Check whether it is an ISAX that uses RdRD, based on the original instruction word (pre injection).
          // Use the special RdIValid that excludes our custom mask condition.
          + rdRD_ISAXes.stream()
                .map(isaxName -> language.CreateNodeName(from_scal_rdivalid_unmasked_generic_node, stage_decode, isaxName))
                .reduce((a, b) -> a + " || " + b)
                .orElse("1'b0")
          // language.CreateAllEncoding(rdRD_ISAXes, ISAXes, "scaiev.decode_Instr")
          + ")";
      addLogic("assign decode_rdReg_RD = " + cond_inject_rdReg + ";");

      addDeclaration("logic [31:0] issue_rdRD_rs1_val;");
      addDeclaration("logic [31:0] execute_rdRD_rs1_val;");

      addLogic("assign scaiev.decode_repeat_next = decode_rdReg_RD;");

      // NOTE: Not properly compatible with scaiev.decode_flush

      ArrayList<String> statement_lines = new ArrayList<>();
      statement_lines.add("always_ff @(posedge clk) begin");
      statement_lines.add("\tif (issue_isFirst_RdRD && scaiev.issue_RS1_valid) begin");
      statement_lines.add("\t\t//RS1 value of original instruction is ready.");
      statement_lines.add("\t\tissue_rdRD_rs1_val <= scaiev.issue_RS1;");
      statement_lines.add("\tend");
      statement_lines.add("\tif (!issue_isFirst_RdRD && !(scaiev.issue_stall || scaiev.issue_isStalling) && scaiev.issue_isSCAIEV) begin");
      statement_lines.add("\t\t//Instruction other than original leaves Issue stage.");
      statement_lines.add("\t\texecute_rdRD_rs1_val <= issue_rdRD_rs1_val;");
      statement_lines.add("\tend");
      statement_lines.add(
          "\tdecode_isRepeated_RdRD <= (decode_rdReg_RD && !scaiev.decode_isStalling && !scaiev.decode_stall) || (decode_isRepeated_RdRD "
          + "&& (scaiev.decode_stall || scaiev.decode_isStalling) && !scaiev.issue_flush && !scaiev.decode_flush);");
      statement_lines.add("\tissue_isFirst_RdRD <= (decode_rdReg_RD && !scaiev.decode_isStalling && !scaiev.decode_stall) || "
                          + "(issue_isFirst_RdRD && (scaiev.issue_stall || scaiev.issue_isStalling) && !scaiev.issue_flush);");
      statement_lines.add("\texecute_isFirst_RdRD <= (issue_isFirst_RdRD && !scaiev.issue_isStalling && !scaiev.issue_stall) || "
                          + "(execute_isFirst_RdRD && (scaiev.execute_stall || scaiev.execute_isStalling) && !scaiev.execute_flush);");
      statement_lines.add("end");
      addLogic(String.join("\n", statement_lines));

      // For our injected "read register" instruction, set rs1 to the rd field of the original instruction.
      addLogic("assign scaiev.decode_RS1_override = decode_isRepeated_RdRD;");
      addLogic("assign scaiev.decode_RS2_override = 1'b0;");
      addLogic("assign scaiev.decode_RD_AS_RS_override = 1'b0;");
      addLogic("assign scaiev.decode_skip_wb = decode_rdReg_RD && !decode_isRepeated_RdRD;");
      addLogic("assign scaiev.decode_rdReg_RS1 = scaiev.decode_Instr[11:7];");
      addLogic("assign scaiev.decode_rdReg_RS2 = 5'd0;");
      addLogic("assign scaiev.decode_rdReg_RD_AS_RS = 5'd0;");

      if (ContainsOpInStage(BNode.RdRD, stage_issue))
        addLogic("assign " + language.CreateNodeName(BNode.RdRD, stage_issue, "") + " = scaiev.issue_RS1;");
      if (ContainsOpInStage(BNode.RdRD, stage_execute))
        addLogic("assign " + language.CreateNodeName(BNode.RdRD, stage_execute, "") + " = scaiev.execute_RS1;");

      // HACK: Otherwise, language.UpdateInterface would assign the generic value from ConfigCVA5.
      HashMap<PipelineStage, NodePropBackend> old_rdrs1_node = this.nodes.remove(BNode.RdRS1);
      this.PutNode("logic", "", "scaiev_glue", BNode.RdRS1, stage_issue);
      this.PutNode("logic", "", "scaiev_glue", BNode.RdRS1, stage_execute);

      for (String rdRD_isax : rdRD_ISAXes) {
        if (ContainsOpInStage(BNode.RdRD, stage_issue, rdRD_isax) && ContainsOpInStage(BNode.RdRD, stage_execute, rdRD_isax)) {
          logger.error("ERR CVA5: ISAX " + rdRD_isax + " has RdRD in both issue and execute");
        }
        if (ContainsOpInStage(BNode.RdRS1, stage_issue, rdRD_isax) && ContainsOpInStage(BNode.RdRS1, stage_execute, rdRD_isax)) {
          logger.error("ERR CVA5: ISAX " + rdRD_isax + " has RdRS1 in both issue and execute");
        }
        if (ContainsOpInStage(BNode.RdRS1, stage_issue, rdRD_isax)) {
          // Add RdRS1 node specific to ISAX.
          language.UpdateInterface(topModule, BNode.RdRS1, rdRD_isax, stage_issue, true, false);
          addLogic("assign " + language.CreateNodeName(BNode.RdRS1, stage_issue, rdRD_isax) + " = issue_rdRD_rs1_val;");
        }
        if (ContainsOpInStage(BNode.RdRS1, stage_execute, rdRD_isax)) {
          // Add RdRS1 node specific to ISAX.
          language.UpdateInterface(topModule, BNode.RdRS1, rdRD_isax, stage_execute, true, false);
          addLogic("assign " + language.CreateNodeName(BNode.RdRS1, stage_execute, rdRD_isax) + " = execute_rdRD_rs1_val;");
        }
      }
      if (old_rdrs1_node != null)
        this.nodes.put(BNode.RdRS1, old_rdrs1_node);

      // Also delay any WrRD_spawn while this happens.
      addLogic("assign decode_wrReg_hold = decode_rdReg_RD;");

      setConfigFlag("ENABLE_DECODE_INJECT", true);
    } else {
      addLogic("assign decode_isRepeated_RdRD = 1'b0;");
      addLogic("assign decode_rdReg_RD = 1'b0;");
      addLogic("assign issue_isFirst_RdRD = 1'b0;");
      addLogic("assign execute_isFirst_RdRD = 1'b0;");
      addLogic("assign scaiev.decode_repeat_next = 1'b0;");
      addLogic("assign scaiev.decode_rdReg = 1'b0;");
      addLogic("assign scaiev.decode_rdReg_RS1 = 5'd0;");
      addLogic("assign scaiev.decode_rdReg_RS2 = 5'd0;");
      addLogic("assign scaiev.decode_skip_wb = 1'b0;");
      addLogic("assign scaiev.decode_RS1_override = 1'b0;");
      addLogic("assign scaiev.decode_RS2_override = 1'b0;");
      addLogic("assign scaiev.decode_RD_AS_RS_override = 1'b0;");
      addLogic("assign decode_wrReg_hold = 1'b0;");
    }
  }

  private void IntegrateISAX_WrStall() {
    // Assign core stall signals by the ISAX WrStall request.
    // Also stall issue in case of injected loads/stores and decoupled WrRD conflicts.
    for (int stagePos = stagePos_fetch; stagePos <= stagePos_execute; stagePos++) {
      PipelineStage stage = stages[stagePos];
      String stallLogic = "";
      if (ContainsOpInStage(BNode.WrStall, stage)) {
        stallLogic += language.CreateNodeName(BNode.WrStall, stage, "");
        if (stage == stage_decode)
          stallLogic = "(" + stallLogic + " && !scaiev.decode_wrReg)";
        stallLogic += " || ";
      }
      if (stage == stage_fetch && ContainsOpInStage(BNode.WrStall, stage_fetch_interm))
        stallLogic += language.CreateNodeName(BNode.WrStall, stage_fetch_interm, "") + " || ";
      if (stage == stage_decode)
        stallLogic += "hazard_unit_scaiev_decode_stall_set || ";
      if (stage == stage_issue)
        stallLogic += "scaiev.issue_injectLS_potentiallyValid && scaiev.issue_isLS || " +
                      "scaiev.execute_injectLS_potentiallyValid && scaiev.issue_isLS || " +
                      signame_issue_stall_before_mem + " || " + signame_issue_stall_before_cf + " || ";
      if (stage == stage_execute)
        stallLogic += signame_execute_stall_mem + " || ";
      stallLogic += "0";
      addLogic("assign scaiev." + cva5InterfaceStageNames[stagePos] + "_stall = " + stallLogic + ";\n");
    }
  }
  private void IntegrateISAX_WrFlush() {
    if (ContainsOpInStage(BNode.WrFlush, stage_execute))
      logger.error("WrFlush is not supported in Execute stage.");
    for (int stagePos = stagePos_execute; stagePos >= stagePos_decode; stagePos--) {
      PipelineStage stage = stages[stagePos];
      if (ContainsOpInStage(BNode.WrFlush, stage)) {
        String flushLogic = language.CreateNodeName(BNode.WrFlush, stage, "");
        addLogic("assign scaiev." + cva5InterfaceStageNames[stagePos] + "_flush = " + flushLogic + ";\n");
      }
      else {
        addLogic("assign scaiev." + cva5InterfaceStageNames[stagePos] + "_flush = 1'b0;\n");
      }
    }
    String fetchFlushLogic = signame_WrPC_reg_after_stall_valid;
    if (ContainsOpInStage(BNode.WrFlush, stage_fetch))
      fetchFlushLogic += " || " + language.CreateNodeName(BNode.WrFlush, stage_fetch, "");
    if (ContainsOpInStage(BNode.WrFlush, stage_fetch_interm))
      fetchFlushLogic += " || " + language.CreateNodeName(BNode.WrFlush, stage_fetch_interm, "");

    addLogic("assign scaiev.fetch_flush = " + fetchFlushLogic + ";\n");
  }

  private void IntegrateISAX_WrRD() {
    ArrayList<String> statement_lines = new ArrayList<>();
    if (ContainsOpInStage(BNode.WrRD, stage_execute)) {
      statement_lines.add("always_comb begin");
      statement_lines.add(tab + "unique case (1'b1)");
      final String indent = tab + tab;

      String rdvalid_node_name = language.CreateNodeName(BNode.WrRD_valid, stage_execute, "");
      String rd_node_name = language.CreateNodeName(BNode.WrRD, stage_execute, "");
      statement_lines.add(indent + "(" + rdvalid_node_name + "): begin");
      statement_lines.add(indent + tab + "scaiev.execute_wrRD = 1'b1;");
      statement_lines.add(indent + tab + "scaiev.execute_wrRD_data = " + rd_node_name + ";");
      statement_lines.add(indent + "end");

      statement_lines.add(indent + "default: begin");
      statement_lines.add(indent + tab + "scaiev.execute_wrRD = 1'b0;");
      statement_lines.add(indent + tab + "scaiev.execute_wrRD_data = 'X;");
      statement_lines.add(indent + "end");
      statement_lines.add(tab + "endcase");
      statement_lines.add("end");
    } else {
      statement_lines.add("assign scaiev.execute_wrRD = 1'b0;");
      statement_lines.add("assign scaiev.execute_wrRD_data = 'X;");
    }
    addLogic(String.join("\n", statement_lines));
    if (op_stage_instr.containsKey(BNode.WrRD)) {
      // Debug: Check for illegal WrRD.
      for (PipelineStage stage : op_stage_instr.get(BNode.WrRD).keySet()) {
        if (!stage.equals(stage_execute) && !op_stage_instr.get(BNode.WrRD).get(stage).isEmpty()) {
          logger.error("WrRD used in unexpected stage (expected " + stage_execute + ", got " + stage + "). "
                       + "Offending ISAX(es): " +
                       getISAXesWithOpInStage(BNode.WrRD, stage).map(isax -> isax.GetName()).reduce((a, b) -> a + ',' + b).orElse(""));
        }
      }
    }
  }

  private void IntegrateISAX_WrRDSpawn_allocatemethod() {
    SCAIEVNode addr_to_scal_node = new SCAIEVNode(signame_to_scal_wrrd_spawn_addr, BNode.WrRD_spawn_addr.size, false) {
      { oneInterfToISAX = true; }
    };
    this.PutNode("logic", "", "scaiev_glue", addr_to_scal_node, stage_issue);
    addLogic("assign " + language.CreateNodeName(addr_to_scal_node, stage_issue, "", false) +
             " = {scaiev.issue_prev_phys_RD_decoupled, scaiev.issue_phys_RD_decoupled};");
    language.UpdateInterface(topModule, addr_to_scal_node, "", stage_issue, false, false);

    this.PutNode("logic", "", "scaiev_glue", addr_to_scal_node, stage_decode);
    addLogic("assign " + language.CreateNodeName(addr_to_scal_node, stage_decode, "", false) +
             " = {scaiev.decode_prev_phys_RD_decoupled, scaiev.decode_phys_RD_decoupled};");
    language.UpdateInterface(topModule, addr_to_scal_node, "", stage_decode, false, false);

    ArrayList<String> statement_lines = new ArrayList<>();
    boolean has_previous_case = false;
    statement_lines.add("always_comb begin");
    String spawn_valid_resp = language.CreateNodeName(BNode.WrRD_spawn_validResp, pseudostage_spawn, "");
    if (ContainsOpInStage(BNode.WrRD_spawn, pseudostage_spawn)) {
      statement_lines.add(tab + "if (explicit_free_reg) begin");
      statement_lines.add(tab + tab + "scaiev.rf_wrReg = scaiev.rf_ready;");
      statement_lines.add(tab + tab + "scaiev.rf_wrReg_cancel = 1'b0;");
      statement_lines.add(tab + tab + "scaiev.rf_wrReg_phys_RD = explicit_free_reg_addr;");
      statement_lines.add(tab + tab + "scaiev.rf_wrReg_prev_phys_RD = {1'b1, explicit_free_reg_addr}; //writeback group 1");
      statement_lines.add(tab + tab + "scaiev.rf_wrReg_data = 'X;");
      statement_lines.add(tab + tab + "scaiev.rf_isDecoupledWB = 0;");
      statement_lines.add(tab + tab + spawn_valid_resp + " = 0;");
      statement_lines.add(tab + "end");
      has_previous_case = true;

      String spawn_addr_wire = language.CreateNodeName(BNode.WrRD_spawn_addr, pseudostage_spawn, "");
      String spawn_data_wire = language.CreateNodeName(BNode.WrRD_spawn, pseudostage_spawn, "");
      //Treat cancel like valid, since the register is still allocated to us.
      String spawn_valid_wire = language.CreateNodeName(BNode.WrRD_spawn_valid, pseudostage_spawn, "");
      String spawn_cancel_wire = language.CreateNodeName(BNode.WrRD_spawn_cancel, pseudostage_spawn, "");
      String spawn_allowed = language.CreateNodeName(BNode.WrRD_spawn_allowed, pseudostage_spawn, "");

      statement_lines.add(tab + (has_previous_case ? "else if" : "if") + " (%s || %s) begin".formatted(spawn_valid_wire, spawn_cancel_wire));
      statement_lines.add(tab + tab + "scaiev.rf_wrReg = scaiev.rf_ready;");
      statement_lines.add(tab + tab + "scaiev.rf_wrReg_cancel = %s;".formatted(spawn_cancel_wire));
      statement_lines.add(tab + tab + "scaiev.rf_wrReg_phys_RD = " + spawn_addr_wire + "[5:0];");
      statement_lines.add(tab + tab + "scaiev.rf_wrReg_prev_phys_RD = " + spawn_addr_wire + "[12:6];");
      statement_lines.add(tab + tab + "scaiev.rf_wrReg_data = " + spawn_data_wire + ";");
      statement_lines.add(tab + tab + "scaiev.rf_isDecoupledWB = 1;");
      statement_lines.add(tab + tab + spawn_valid_resp + " = scaiev.rf_ready;");
      statement_lines.add(tab + "end");

      this.addLogic("assign " + spawn_allowed + " = scaiev.rf_ready;");
      //this.addLogic("assign " + spawn_allowed + " = 1;");

      setConfigFlag("ENABLE_DECOUPLED_WRITEBACK", true);
    } else
      spawn_valid_resp = "";
    if (has_previous_case)
      statement_lines.add(tab + "else begin");
    statement_lines.add(tab + tab + "scaiev.rf_wrReg = 1'b0;");
    statement_lines.add(tab + tab + "scaiev.rf_wrReg_cancel = 1'b0;");
    statement_lines.add(tab + tab + "scaiev.rf_wrReg_phys_RD = 'X;");
    statement_lines.add(tab + tab + "scaiev.rf_wrReg_prev_phys_RD = 'X;");
    statement_lines.add(tab + tab + "scaiev.rf_wrReg_data = 'X;");
    statement_lines.add(tab + tab + "scaiev.rf_isDecoupledWB = 0;");
    if (!spawn_valid_resp.isEmpty())
      statement_lines.add(tab + tab + spawn_valid_resp + " = 0;");
    if (has_previous_case)
      statement_lines.add(tab + "end");

    statement_lines.add("end");

    addLogic(String.join("\n", statement_lines));

    // For DH: Set condition to lock the writeback register for a decoupled instruction about to enter Issue.
    String cond_issue_is_isax_decoupled_wb = "0";
    if (ContainsOpInStage(BNode.WrRD_spawn, pseudostage_spawn)) {
      cond_issue_is_isax_decoupled_wb =
          getISAXesWithDecoupledOp(BNode.WrRD_spawn)
              .filter(isax -> !isax.HasNoOp())
              .map(isax -> language.CreateNodeName(BNode.RdIValid.NodeNegInput(), stage_issue, isax.GetName()))
              .reduce((a, b) -> a + " || " + b)
              .orElse("0");
      if (ISAXes.containsKey(SCAL.PredefInstr.kill.instr.GetName())) {
        // Unlock all on decoupled ISAX kill.
        String ivalid_expression =
            language.CreateNodeName(BNode.RdIValid.NodeNegInput(), stage_issue, SCAL.PredefInstr.kill.instr.GetName());
        toFile.ReplaceContent(this.ModFile("scaiev_glue"), "wire unlock_all_registers = ",
                              new ToWrite("wire unlock_all_registers = " + ivalid_expression + ";", true, false, ""));
      }
    }
    toFile.ReplaceContent(this.ModFile("scaiev_glue"), "wire issue_is_isax_decoupled_wb = ",
                          new ToWrite("wire issue_is_isax_decoupled_wb = !scaiev.issue_isStalling && !scaiev.issue_stall && (" +
                                          cond_issue_is_isax_decoupled_wb + ");",
                                      true, false, ""));

    addLogic("assign scaiev.decode_wrReg = 1'b0;");
    addLogic("assign decode_wrReg_cancel = 1'b0;");
    addLogic("assign scaiev.decode_wrReg_RD = 5'd0;");
    addLogic("assign scaiev.decode_wrReg_data = 32'd0;");
  }

  private void IntegrateISAX_WrRDSpawn_injectmethod() {
    ArrayList<String> statement_lines = new ArrayList<>();
    boolean has_previous_case = false;
    statement_lines.add("always_comb begin");
    String spawn_valid_resp = language.CreateNodeName(BNode.WrRD_spawn_validResp, pseudostage_spawn, "");
    if (ContainsOpInStage(BNode.WrRD_spawn, pseudostage_spawn)) {
      String spawn_addr_wire = language.CreateNodeName(BNode.WrRD_spawn_addr, pseudostage_spawn, "");
      String spawn_data_wire = language.CreateNodeName(BNode.WrRD_spawn, pseudostage_spawn, "");
      String spawn_valid_wire = language.CreateNodeName(BNode.WrRD_spawn_valid, pseudostage_spawn, "");
      String spawn_cancel_wire = language.CreateNodeName(BNode.WrRD_spawn_cancel, pseudostage_spawn, "");
      String spawn_allowed = language.CreateNodeName(BNode.WrRD_spawn_allowed, pseudostage_spawn, "");

      statement_lines.add(tab + (has_previous_case ? "else if" : "if") + " (%s || %s) begin".formatted(spawn_valid_wire, spawn_cancel_wire));
      statement_lines.add(tab + tab + "scaiev.decode_wrReg = !decode_wrReg_hold && %s;".formatted(spawn_valid_wire));
      statement_lines.add(tab + tab + "decode_wrReg_cancel = %s;".formatted(spawn_cancel_wire));
      statement_lines.add(tab + tab + "scaiev.decode_wrReg_RD = " + spawn_addr_wire + ";");
      statement_lines.add(tab + tab + "scaiev.decode_wrReg_data = " + spawn_data_wire + ";");
      statement_lines.add(tab + tab + spawn_valid_resp + " = " + spawn_allowed + ";");
      statement_lines.add(tab + "end");
      has_previous_case = true;

      // this.addLogic("assign " + spawn_allowed + " = !scaiev.decode_isStalling && !scaiev.decode_stall && !decode_wrReg_hold;");
      this.addLogic("assign " + spawn_allowed + " = !scaiev.decode_isStalling && !decode_wrReg_hold;");

      setConfigFlag("ENABLE_SCAIEV_REGHAZARD", true);
      setConfigFlag("ENABLE_DECODE_INJECT", true);
    } else
      spawn_valid_resp = "";
    if (has_previous_case)
      statement_lines.add(tab + "else begin");
    statement_lines.add(tab + tab + "scaiev.decode_wrReg = 1'b0;");
    statement_lines.add(tab + tab + "decode_wrReg_cancel = 1'b0;");
    statement_lines.add(tab + tab + "scaiev.decode_wrReg_RD = 'X;");
    statement_lines.add(tab + tab + "scaiev.decode_wrReg_data = 'X;");
    if (!spawn_valid_resp.isEmpty())
      statement_lines.add(tab + tab + spawn_valid_resp + " = 0;");
    if (has_previous_case)
      statement_lines.add(tab + "end");

    statement_lines.add("end");

    addLogic(String.join("\n", statement_lines));

    // For DH: Set condition to lock the writeback register for a decoupled instruction about to enter Issue.
    String cond_decode_is_isax_decoupled_wb = "0";
    if (ContainsOpInStage(BNode.WrRD_spawn, pseudostage_spawn)) {
      cond_decode_is_isax_decoupled_wb =
          getISAXesWithDecoupledOp(BNode.WrRD_spawn)
              .filter(isax -> !isax.HasNoOp())
              .map(isax -> language.CreateNodeName(BNode.RdIValid.NodeNegInput(), stage_decode, isax.GetName()))
              .reduce((a, b) -> a + " || " + b)
              .orElse("0");
      if (ISAXes.containsKey(SCAL.PredefInstr.kill.instr.GetName())) {
        // Unlock all on decoupled ISAX kill.
        String ivalid_local_node_name =
            language.CreateNodeName(BNode.RdIValid.NodeNegInput(), stage_decode, SCAL.PredefInstr.kill.instr.GetName());
        toFile.ReplaceContent(this.ModFile("scaiev_glue"), "wire unlock_all_registers = ",
                              new ToWrite("wire unlock_all_registers = " + ivalid_local_node_name + ";", true, false, ""));
      }
    }
    toFile.ReplaceContent(this.ModFile("scaiev_glue"), "wire decode_is_isax_decoupled_wb = ",
                          new ToWrite("wire decode_is_isax_decoupled_wb = " + cond_decode_is_isax_decoupled_wb + ";", true, false, ""));

    addLogic("assign scaiev.rf_wrReg = 1'b0;");
    addLogic("assign scaiev.rf_wrReg_cancel = 1'b0;");
    addLogic("assign scaiev.rf_wrReg_phys_RD = '0;");
    addLogic("assign scaiev.rf_wrReg_prev_phys_RD = '0;");
    addLogic("assign scaiev.rf_wrReg_data = 32'd0;");
    addLogic("assign scaiev.rf_isDecoupledWB = 1'b0;");
  }

  private void IntegrateISAX_Mem() {
    boolean injectLS_has_previous_case = false;

    addDeclaration("logic " + signame_issue_stall_before_mem + ";");
    addDeclaration("logic " + signame_execute_stall_mem + ";");
    // execute_delay_rdwr
    boolean injectLS_regularRd = ContainsOpInStage(BNode.RdMem, stage_execute);
    boolean injectLS_regularWr = ContainsOpInStage(BNode.WrMem, stage_execute);
    boolean injectLS_regular = injectLS_regularRd || injectLS_regularWr;
    boolean injectLS_spawnRd = ContainsOpInStage(BNode.RdMem_spawn, pseudostage_spawn);
    boolean injectLS_spawnWr = ContainsOpInStage(BNode.WrMem_spawn, pseudostage_spawn);
    boolean injectLS_spawn = injectLS_spawnRd || injectLS_spawnWr;

    String issue_stall_assigns = "";
    String execute_stall_assigns = "";

    String cond_injectLS_possibleSpawn = "";

    if (injectLS_spawn) {
      if (ContainsOpInStage(BNode.RdMem_spawn, pseudostage_spawn)) {
        cond_injectLS_possibleSpawn += language.CreateNodeName(BNode.RdMem_spawn_validReq, pseudostage_spawn, "");
        cond_injectLS_possibleSpawn += " || ";
      } else if (ContainsOpInStage(BNode.WrMem_spawn, pseudostage_spawn)) // SCAL->ISAX RdMem_spawn_* signals are the same as WrMem_spawn_*
      {
        cond_injectLS_possibleSpawn += language.CreateNodeName(BNode.WrMem_spawn_validReq, pseudostage_spawn, "");
        cond_injectLS_possibleSpawn += " || ";
      }
      // Stall issue in the cycle where a Mem operation (here: matches not only Mem_Spawn) is issued.
      issue_stall_assigns += "(scaiev.execute_injectLS_potentiallyValid && scaiev.issue_isLS && scaiev.injectLS_ready) || ";
    }
    cond_injectLS_possibleSpawn += "0";

    if (injectLS_regular) {
      // Stall issue if an ISAX that possibly issues memory ops is in Execute, regardless of whether it does so in the current cycle
      // already.
      //-> Otherwise, a later regular instruction could enter the LSU,
      //    but since the ISAX is not retired, the regular instruction would be stuck waiting just before writeback,
      //    and also block any LSU requests by the ISAX (deadlock).
      HashSet<String> memISAXes_regular = new HashSet<>();
      if (injectLS_regularRd) {
        getISAXesWithOpInStage(BNode.RdMem, stage_execute).map(isax -> isax.GetName()).forEach(isaxName -> memISAXes_regular.add(isaxName));
      }
      if (injectLS_regularWr) {
        getISAXesWithOpInStage(BNode.WrMem, stage_execute).map(isax -> isax.GetName()).forEach(isaxName -> memISAXes_regular.add(isaxName));
      }
      // Stall Issue if it has an LSU instruction or an ISAX with a memory operation...
      issue_stall_assigns += "((scaiev.issue_isLS || ";
      issue_stall_assigns += memISAXes_regular.stream()
                                 .map(isaxName -> language.CreateNodeName(BNode.RdIValid.NodeNegInput(), stage_issue, isaxName))
                                 .reduce((a, b) -> a + " || " + b)
                                 .orElse("0");
      //... and another memory ISAX is in Execute at the time.
      issue_stall_assigns += ") && (";
      issue_stall_assigns += memISAXes_regular.stream()
                                 .map(isaxName -> language.CreateNodeName(BNode.RdAnyValid, stage_execute, isaxName))
                                 .reduce((a, b) -> a + " || " + b)
                                 .orElse("0");
      issue_stall_assigns += ")) || ";

      // Stall execute if it wants to do a memory operation but A) the LSU is not ready or B) a Mem Spawn op is present (as those are
      // prioritised),
      //  or if a read operation is still running.
      execute_stall_assigns += "((";
      if (injectLS_regularRd) {
        execute_stall_assigns += language.CreateNodeName(BNode.RdMem_validReq, stage_execute, "") +
                                 " || (mem_read_pending && !scaiev.execute_injectLS_done) || ";
      }
      if (injectLS_regularWr) {
        execute_stall_assigns += language.CreateNodeName(BNode.WrMem_validReq, stage_execute, "") + " || ";
      }

      // Logic: Is any request started in a previous cycle still waiting?
      addDeclaration("logic mem_current_request_waiting;");
      addLogic(
          "always @(posedge clk) begin\n\tmem_current_request_waiting <= scaiev.execute_injectLS_valid && !scaiev.injectLS_ready;\nend");

      // execute_stall_assigns += "0) && (!scaiev.injectLS_ready || " + cond_injectLS_possibleSpawn + ")) || ";
      //-> Prevent combinational loop
      execute_stall_assigns += "0) && (mem_current_request_waiting || " + cond_injectLS_possibleSpawn + ")) || ";

      // Stall execute if it possibly wants to start a read but another read is still pending.
      // TODO (optional): May be removed to allow several pending reads, but the regular_read_pending tracker currently does not support
      // that.
      if (injectLS_regularRd/* || injectLS_spawnRd*/) {
        execute_stall_assigns += "((" + language.CreateNodeName(BNode.RdMem_validReq, stage_execute, "") +
                                 " && !mem_read_pending) || (mem_read_pending && !scaiev.execute_injectLS_done)) || ";
      }
    }

    issue_stall_assigns += "0";
    addLogic("assign " + signame_issue_stall_before_mem + " = " + issue_stall_assigns + ";");
    execute_stall_assigns += "0";
    addLogic("assign " + signame_execute_stall_mem + " = " + execute_stall_assigns + ";");

    addDeclaration("logic mem_read_pending;");
    addDeclaration("logic set_mem_read_pending;");
    if (injectLS_regularRd/* || injectLS_spawnRd*/) {
      //-> If a read is done but a new is to be started, mem_read_pending needs to remain set.
      ArrayList<String> read_pending_statement_lines = new ArrayList<>();
      read_pending_statement_lines.add("always_ff @(posedge clk) begin");
      read_pending_statement_lines.add(tab + "if (set_mem_read_pending) begin");
      read_pending_statement_lines.add(tab + tab + "mem_read_pending <= 1;");
      read_pending_statement_lines.add(tab + "end");
      read_pending_statement_lines.add(tab + "if (rst || (!set_mem_read_pending && scaiev.execute_injectLS_done"
                                           + " && !scaiev.injectLS_done_decoupled)) begin");
      read_pending_statement_lines.add(tab + tab + "mem_read_pending <= 0;");
      read_pending_statement_lines.add(tab + "end");
      read_pending_statement_lines.add("end");
      addLogic(String.join("\n", read_pending_statement_lines));
    } else {
      addLogic("assign mem_read_pending = 0;\n");
    }
    ArrayList<String> injectLS_pre_statement_lines = new ArrayList<>();
    ArrayList<String> injectLS_statement_lines = new ArrayList<>();
    injectLS_pre_statement_lines.add("always_comb begin");
    if (injectLS_spawn) {
      // Select the first decoupled ISAX with an active spawn request.
      injectLS_pre_statement_lines.add(tab + "set_mem_write_spawn_pending = 0;");

      if (injectLS_spawnRd || injectLS_spawnWr) {
        addDeclaration("logic mem_write_spawn_pending;");
        addDeclaration("logic set_mem_write_spawn_pending;");
        addDeclaration("logic mem_spawn_validResp_r;");

        // Scal->Core does not have separate RdMem and WrMem spawn interfaces (but it has for non-spawn!)
        String spawn_addr = language.CreateNodeName(BNode.RdMem_spawn_addr, pseudostage_spawn, "");
        String spawn_val = language.CreateNodeName(BNode.RdMem_spawn, pseudostage_spawn, "");
        String spawn_valid = language.CreateNodeName(BNode.RdMem_spawn_validReq, pseudostage_spawn, "");
        String spawn_valid_resp = language.CreateNodeName(BNode.RdMem_spawn_validResp, pseudostage_spawn, "");
        String spawn_is_write = language.CreateNodeName(BNode.RdMem_spawn_write, pseudostage_spawn, "");
        String spawn_wrval = language.CreateNodeName(BNode.WrMem_spawn, pseudostage_spawn, "");

        injectLS_statement_lines.add(tab + (injectLS_has_previous_case ? "else if" : "if") + " (" + spawn_valid + ") begin");
        injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_potentiallyValid = 1'b1;");// + spawn_is_write + " || !mem_read_pending;");
        injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_valid = 1'b1;");// + spawn_is_write + " || !mem_read_pending;");
        injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_decoupled = 1'b1;");
        injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_rnw = !" + spawn_is_write + ";");
        injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_relaxedOrdering = 1'b1;");
        injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_addr = " + spawn_addr + ";");
        injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_storeVal = " + (injectLS_spawnWr ? spawn_wrval : "'X") + ";");
        //injectLS_statement_lines.add(tab + tab + "set_mem_read_pending = !" + spawn_is_write +
        //                             " && scaiev.injectLS_ready && !mem_read_pending;");
        injectLS_statement_lines.add(tab + tab + "set_mem_write_spawn_pending = " + spawn_is_write + " && scaiev.injectLS_ready;");
        injectLS_statement_lines.add(tab + "end");
        injectLS_has_previous_case = true;
        if (injectLS_spawnRd)
          addLogic("assign " + spawn_val + " = scaiev.injectLS_readData;");
        ArrayList<String> valid_resp_statement_lines = new ArrayList<>();
        valid_resp_statement_lines.add("always_ff @(posedge clk) begin");
        valid_resp_statement_lines.add(tab + "mem_write_spawn_pending <= set_mem_write_spawn_pending;");
        valid_resp_statement_lines.add(tab + "mem_spawn_validResp_r <= mem_write_spawn_pending;");
        valid_resp_statement_lines.add("end");
        addLogic(String.join("\n", valid_resp_statement_lines));
        //addLogic("assign " + spawn_valid_resp + " = (mem_read_pending && scaiev.execute_injectLS_done) || mem_spawn_validResp_r;");
        addLogic("assign " + spawn_valid_resp + " = (scaiev.injectLS_done_decoupled && scaiev.execute_injectLS_done) || mem_spawn_validResp_r;");
      }

      // stageReady = "1" -> Will not wait for synchronous ready event before setting ISAX_fire2_mem_reg;
      // mem_state = "scaiev.execute_injectLS && scaiev.injectLS_ready" -> ISAX_fire2_mem_reg will only be reset once the Load/Store is
      // actually started.

      String rd_spawn_allowed = language.CreateNodeName(BNode.RdMem_spawn_allowed, pseudostage_spawn, "");
      String wr_spawn_allowed = language.CreateNodeName(BNode.WrMem_spawn_allowed, pseudostage_spawn, "");
      if (!rd_spawn_allowed.equals(wr_spawn_allowed) || !injectLS_spawnWr)
        this.addLogic("assign " + rd_spawn_allowed + " = scaiev.injectLS_ready;");
      if (injectLS_spawnWr) {
        this.addLogic("assign " + wr_spawn_allowed + " = scaiev.injectLS_ready;");
        //" = mem_read_pending ? scaiev.execute_injectLS_done : scaiev.injectLS_ready;");
      }
    }
    if (injectLS_regularRd) {
      addDeclaration("logic mem_read_regular_pending;");
      addDeclaration("logic set_mem_read_regular_pending;");
      injectLS_pre_statement_lines.add(tab + "set_mem_read_regular_pending = 0;");

      String mem_valid_req = language.CreateNodeName(BNode.RdMem_validReq, stage_execute, "");
      // String mem_valid_resp = language.CreateNodeName(BNode.RdMem_validResp, stage_execute, "");
      String is_pending = "mem_read_pending";
      // String is_stalling = language.CreateNodeName(BNode.RdStall, stage_execute, "");
      String mem_addr = language.CreateNodeName(BNode.RdMem_addr, stage_execute, "");
      String mem_rd_val = language.CreateNodeName(BNode.RdMem, stage_execute, "");
      injectLS_statement_lines.add(tab + (injectLS_has_previous_case ? "else if" : "if") + " (" + mem_valid_req + ") begin");
      injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_potentiallyValid = !" + is_pending + ";");
      injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_valid = !" + is_pending + ";");
      injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_decoupled = 1'b0;");
      injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_rnw = 1'b1;");
      injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_relaxedOrdering = 1'b0;");
      injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_addr = " + mem_addr + ";");
      injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_storeVal = 'X;");
      injectLS_statement_lines.add(tab + tab + "set_mem_read_pending = !" + is_pending + " && scaiev.injectLS_ready;");
      injectLS_statement_lines.add(tab + tab + "set_mem_read_regular_pending = set_mem_read_pending;");
      injectLS_statement_lines.add(tab + "end");
      injectLS_has_previous_case = true;
      addLogic("assign " + mem_rd_val + " = scaiev.injectLS_readData;");
      // addLogic("assign " + mem_valid_resp + " = scaiev.execute_injectLS_done;");

      if (op_stage_instr.containsKey(BNode.RdMem_validResp)) {
        ArrayList<String> valid_resp_statement_lines = new ArrayList<>();
        valid_resp_statement_lines.add("always_ff @(posedge clk) begin");
        valid_resp_statement_lines.add(tab + "if (rst) begin");
        valid_resp_statement_lines.add(tab + tab + "mem_read_regular_pending <= 0;");
        valid_resp_statement_lines.add(tab + "end");
        valid_resp_statement_lines.add(tab + "else begin");
        valid_resp_statement_lines.add(
            tab + tab +
            "mem_read_regular_pending <= set_mem_read_regular_pending || mem_read_regular_pending && !scaiev.execute_injectLS_done;");
        valid_resp_statement_lines.add(tab + "end");
        valid_resp_statement_lines.add("end");
        valid_resp_statement_lines.add("assign " + language.CreateNodeName(BNode.RdMem_validResp, stage_execute, "") +
                                       " = mem_read_regular_pending && !scaiev.injectLS_done_decoupled && scaiev.execute_injectLS_done;");
        addLogic(String.join("\n", valid_resp_statement_lines));
      }
    } else {
      if (op_stage_instr.containsKey(BNode.RdMem_validResp))
        addLogic("assign " + language.CreateNodeName(BNode.RdMem_validResp, stage_execute, "") + " = 0;");
    }
    if (injectLS_regularWr) {
      addDeclaration("logic mem_write_regular_r;");
      addDeclaration("logic mem_write_regular_starting;");
      injectLS_pre_statement_lines.add(tab + "mem_write_regular_starting = 0;");

      String mem_valid_req = language.CreateNodeName(BNode.WrMem_validReq, stage_execute, "");
      String is_pending = "0";
      String mem_addr = language.CreateNodeName(BNode.WrMem_addr, stage_execute, "");
      String mem_wr_val = language.CreateNodeName(BNode.WrMem, stage_execute, "");
      injectLS_statement_lines.add(tab + (injectLS_has_previous_case ? "else if" : "if") + " (" + mem_valid_req + ") begin");
      injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_potentiallyValid = !" + is_pending + ";");
      injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_valid = !" + is_pending + ";");
      injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_decoupled = 1'b0;");
      injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_rnw = 1'b0;");
      injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_relaxedOrdering = 1'b0;");
      injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_addr = " + mem_addr + ";");
      injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_storeVal = " + mem_wr_val + ";");
      injectLS_statement_lines.add(tab + tab + "set_mem_read_pending = 1'b0;");
      injectLS_statement_lines.add(tab + tab + "mem_write_regular_starting = !" + is_pending + " && scaiev.injectLS_ready;");
      injectLS_statement_lines.add(tab + "end");
      injectLS_has_previous_case = true;
      if (op_stage_instr.containsKey(BNode.WrMem_validResp)) {
        ArrayList<String> valid_resp_statement_lines = new ArrayList<>();
        valid_resp_statement_lines.add("always_ff @(posedge clk) begin");
        valid_resp_statement_lines.add(tab + "mem_write_regular_r <= mem_write_regular_starting;");
        valid_resp_statement_lines.add("end");
        valid_resp_statement_lines.add("assign " + language.CreateNodeName(BNode.WrMem_validResp, stage_execute, "") +
                                       " = mem_write_regular_r;");
        addLogic(String.join("\n", valid_resp_statement_lines));
      }
    } else {
      if (op_stage_instr.containsKey(BNode.WrMem_validResp))
        addLogic("assign " + language.CreateNodeName(BNode.WrMem_validResp, stage_execute, "") + " = 0;");
    }
    if (injectLS_has_previous_case)
      injectLS_statement_lines.add(tab + "else begin");
    injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_potentiallyValid = 1'b0;");
    injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_valid = 1'b0;");
    injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_decoupled = 1'b0;");
    injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_rnw = 'X;");
    injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_relaxedOrdering = 'X;");
    injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_addr = 'X;");
    injectLS_statement_lines.add(tab + tab + "scaiev.execute_injectLS_storeVal = 'X;");
    injectLS_statement_lines.add(tab + tab + "set_mem_read_pending = 1'b0;");
    if (injectLS_has_previous_case)
      injectLS_statement_lines.add(tab + "end");
    injectLS_statement_lines.add("end");

    addLogic(String.join("\n", injectLS_pre_statement_lines));
    addLogic(String.join("\n", injectLS_statement_lines));
    addLogic(String.join("\n", List.of(
        "assign scaiev.issue_injectLS_potentiallyValid = 1'b0;",
        "assign scaiev.issue_injectLS_valid = 1'b0;",
        "assign scaiev.issue_injectLS_decoupled = 1'b0;",
        "assign scaiev.issue_injectLS_rnw = 1'b0;",
        "assign scaiev.issue_injectLS_relaxedOrdering = 1'b0;",
        "assign scaiev.issue_injectLS_addr = '0;",
        "assign scaiev.issue_injectLS_storeVal = '0;")));
  }

  private void IntegrateISAX_WrPC() {
    // Select fetch_wrPC (earliest instruction, i.e. always from higher stage index; spawn takes precedence)
    ArrayList<String> statement_lines = new ArrayList<>();
    boolean has_previous_case = false;
    addDeclaration("logic " + signame_WrPC_reg_after_stall_valid + ";");
    addDeclaration("logic [31:0] " + signame_WrPC_reg_after_stall + ";");
    addDeclaration("logic " + signame_issue_stall_before_cf + ";");
    addDeclaration("logic " + signame_WrPC_after_Fetch_valid + ";");
    addDeclaration("logic [31:0] " + signame_WrPC_after_Fetch + ";");

    boolean hasPostFetchWrPC = Stream.of(stages).skip(1).anyMatch(stage -> ContainsOpInStage(BNode.WrPC, stage));
    if (hasPostFetchWrPC) {
      // If Fetch stalls during a late WrPC,
      //  the pre-Issue stage should repeatedly repeat that WrPC
      //  until the stall is lifted.
      // Otherwise, a ZOL ISAX could issue a jump back
      //  without the WrZOLCOUNTER update or RdOrigPC being pipelined.
      //  (the jump back is essentially repeated this way)
        statement_lines.add("""
            always_ff @(posedge clk) begin
                if (rst) begin
                    %s <= 1'b0;
                    %s <= 'X;
                end
                else begin
                    if ((scaiev.fetch_stall || scaiev.fetch_isStalling) && %s) begin
                        %s <= %s;
                        %s <= 1'b1;
                    end
                    if (!(scaiev.fetch_stall || scaiev.fetch_isStalling)) begin
                        %s <= 1'b0;
                    end
                end
            end""".formatted(
                signame_WrPC_reg_after_stall_valid, //rst
                signame_WrPC_reg_after_stall, //rst
                signame_WrPC_after_Fetch_valid, //cond
                signame_WrPC_reg_after_stall, signame_WrPC_after_Fetch, //assign
                signame_WrPC_reg_after_stall_valid, //assign 1'b1
                signame_WrPC_reg_after_stall_valid //assign 1'b0
            ));
    } else {
      statement_lines.add("assign %s = 1'b0;".formatted(signame_WrPC_reg_after_stall_valid));
      statement_lines.add("assign %s = 'X;".formatted(signame_WrPC_reg_after_stall));
    }

    statement_lines.add("always_comb begin");
    statement_lines.add(tab + signame_WrPC_after_Fetch_valid + " = " + signame_WrPC_reg_after_stall_valid + ";");
    statement_lines.add(tab + signame_WrPC_after_Fetch + " = " + signame_WrPC_reg_after_stall + ";");
    statement_lines.add(tab + "scaiev.fetch_wrPCValid = " + signame_WrPC_reg_after_stall_valid + ";");
    statement_lines.add(tab + "scaiev.fetch_wrPC = " + signame_WrPC_reg_after_stall + ";");
    if (ContainsOpInStage(BNode.WrPC_spawn, pseudostage_spawn)) {
      String spawn_addr_node = language.CreateNodeName(BNode.WrPC_spawn, pseudostage_spawn, "");
      String spawn_valid_node = language.CreateNodeName(BNode.WrPC_spawn_valid, pseudostage_spawn, "");

      statement_lines.add(tab + (has_previous_case ? "else if" : "if") + " (" + spawn_valid_node + ") begin");
      statement_lines.add(tab + tab + "scaiev.fetch_wrPCValid = 1'b1;");
      statement_lines.add(tab + tab + "scaiev.fetch_wrPC = " + spawn_addr_node + ";");
      statement_lines.add(tab + tab + signame_WrPC_after_Fetch_valid + " = 1'b1;");
      statement_lines.add(tab + tab + signame_WrPC_after_Fetch + " = " + spawn_addr_node + ";");
      statement_lines.add(tab + "end");
      has_previous_case = true;
    }
    BiFunction<Integer, Boolean, Boolean> addWrPCCaseIfNeeded = (Integer stagePos, Boolean has_previous_case_) -> {
      PipelineStage stage = stages[stagePos];
      if (!ContainsOpInStage(BNode.WrPC, stage)) {
        if (stagePos == stagePos_execute)
          addLogic("assign " + signame_issue_stall_before_cf + " = 0;");
        return false;
      }
      String valid_node = language.CreateNodeName(BNode.WrPC_valid, stage, "");
      String pc_node = language.CreateNodeName(BNode.WrPC, stage, "");

      if (stagePos == stagePos_execute) {
        String valid_expr = "(" +
                            getISAXesWithOpInStage(BNode.WrPC, stage)
                                .filter(isax -> !isax.HasNoOp())
                                .map(isax -> language.CreateNodeName(BNode.RdIValid.NodeNegInput(), stage, isax.GetName()))
                                .reduce((a, b) -> a + " || " + b)
                                .orElse("1'b0") +
                            ")";
        // Stall issue if Execute has a control flow ISAX that isn't finishing.
        addLogic("assign " + signame_issue_stall_before_cf + " = (scaiev.execute_isStalling || scaiev.execute_stall) && " + valid_expr +
                 ";");
      }
      statement_lines.add(tab + (has_previous_case_ ? "else if" : "if") + " (" + valid_node + ") begin");
      statement_lines.add(tab + tab + "scaiev.fetch_wrPCValid = 1'b1;");
      statement_lines.add(tab + tab + "scaiev.fetch_wrPC = " + pc_node + ";");
      if (stagePos != stagePos_fetch) {
        statement_lines.add(tab + tab + signame_WrPC_after_Fetch_valid + " = 1'b1;");
        statement_lines.add(tab + tab + signame_WrPC_after_Fetch + " = " + pc_node + ";");
      }
      statement_lines.add(tab + "end");
      return true;
    };
    // Execute has priority over Issue over Decode.
    for (int stagePos = stagePos_execute; stagePos > stagePos_fetch; --stagePos) {
      if (addWrPCCaseIfNeeded.apply(stagePos, has_previous_case))
        has_previous_case = true;
    }
    // Fetch has priority, since it is set to see comb WrPC from Decode/Issue/Execute (as RdPC).
    addWrPCCaseIfNeeded.apply(stagePos_fetch, false);

    statement_lines.add("end");

    addLogic(String.join("\n", statement_lines));
  }

  private void IntegrateISAX_MiscPipeline() {
    addLogic(String.format("assign %s = scaiev.issue_isSCAIEV;\n", this.signame_to_scal_pipeinto_executesv1));
    addLogic(String.format("assign %s = !scaiev.issue_isSCAIEV;\n", this.signame_to_scal_pipeinto_executecva));

    if (ContainsOpInStage(BNode.WrDeqInstr, stage_execute)) {
      String wrDeq_node = language.CreateNodeName(BNode.WrDeqInstr, stage_execute, "");
      addLogic(String.format("assign scaiev.execute_deq = %s;", wrDeq_node));
    } else
      addLogic("assign scaiev.execute_deq = 0;");

    if (ContainsOpInStage(BNode.WrInStageID, stage_execute)) {
      String wrID_node = language.CreateNodeName(BNode.WrInStageID, stage_execute, "");
      String wrIDValid_node = language.CreateNodeName(BNode.WrInStageID_valid, stage_execute, "");

      addLogic(String.format("assign scaiev.execute_commitNow = %s;", wrIDValid_node));
      addLogic(String.format("assign scaiev.execute_commitNow_ID = %s[%d-1:0];", wrID_node, id_space_width));
      addLogic(String.format("assign scaiev.execute_commitNow_expects_rd = %s[%d];", wrID_node, id_space_width));
    }
    else {
      addLogic("assign scaiev.execute_commitNow = 1'b0;");
      addLogic("assign scaiev.execute_commitNow_ID = '0;");
      addLogic("assign scaiev.execute_commitNow_expects_rd = 1'b0;");
    }
  }

  // TODO (longer term):
  //- Handle LS exceptions from ISAX-internal requests
  //- Handle exceptions outside ISAX - e.g. by delaying until ISAX is done. Behaviour may need per-ISAX settings, or to be implemented
  // within the ISAX.

  private void ConfigCVA5() {
    this.PopulateNodesMap();
    PutModule(pathCVA5.resolve("cva5_wrapper_verilog.v"), "cva5_wrapper_verilog", pathCVA5.resolve("cva5_wrapper_verilog.v"), "",
              "cva5_wrapper_verilog");
    PutModule(pathCVA5.resolve("cva5_wrapper.sv"), "cva5_wrapper", pathCVA5.resolve("cva5_wrapper.sv"), "cva5_wrapper_verilog",
              "cva5_wrapper");
    PutModule(pathCVA5.resolve("scaiev_glue.sv"), "scaiev_glue", pathCVA5.resolve("scaiev_glue.sv"), "cva5_wrapper", "scaiev_glue");
    PutModule(pathCVA5.resolve("scaiev_config.sv"), "scaiev_config", pathCVA5.resolve("scaiev_config.sv"), "", "scaiev_config");

    this.configFlags.clear();
    setConfigFlag("ENABLE_SCAIEV_REGHAZARD", false);
    setConfigFlag("ENABLE_DECODE_INJECT", false);
    setConfigFlag("ENABLE_NATIVE_RD_AS_RS", false);

    //		this.stages.clear();
    //		this.stages.put(stage_fetch, "fetch");
    //		this.stages.put(stage_decode, "decode");
    //		this.stages.put(stage_issue, "issue");
    //		this.stages.put(stage_execute, "execute");

    for (int stagePos = stagePos_fetch; stagePos <= stagePos_execute; stagePos++) {
      PipelineStage stage = stages[stagePos];
      this.PutNode("logic", "", "scaiev_glue", BNode.WrPC, stage);
      this.PutNode("logic", "", "scaiev_glue", BNode.WrPC_valid, stage);
    }
    this.PutNode("logic", "", "scaiev_glue", BNode.WrPC_spawn, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrPC_spawn_valid, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrCommit_spawn, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrCommit_spawn_validReq, pseudostage_spawn);
    this.PutNode("logic", "1'b1", "scaiev_glue", BNode.WrCommit_spawn_validResp, pseudostage_spawn);

    this.PutNode("logic", "scaiev.issue_ID", "scaiev_glue", BNode.RdIssueID, stage_issue);
    this.PutNode("logic", "scaiev.issue_flushID", "scaiev_glue", BNode.RdIssueFlushID, stage_issue);
    //this.PutNode("logic", "1'b1", "scaiev_glue", BNode.RdIssueIDValid, stage_issue);
    this.PutNode("logic", "scaiev.execute_ID", "scaiev_glue", BNode.RdIssueID, stage_execute);
    this.PutNode("logic", "scaiev.issue_flushID", "scaiev_glue", BNode.RdIssueFlushID, stage_execute);
    //this.PutNode("logic", "1'b1", "scaiev_glue", BNode.RdIssueIDValid, stage_execute);
    this.PutNode("logic", "scaiev.retire_ID", "scaiev_glue", BNode.RdCommitID, core.GetRootStage());
    this.PutNode("logic", "scaiev.retire_suppress ? '0 : scaiev.retire_count", "scaiev_glue", BNode.RdCommitIDCount, core.GetRootStage());
    this.PutNode("logic", "scaiev.retire_ID", "scaiev_glue", BNode.RdCommitFlushID, core.GetRootStage());
    this.PutNode("logic", "scaiev.retire_suppress ? scaiev.retire_count : '0", "scaiev_glue", BNode.RdCommitFlushIDCount, core.GetRootStage());
    this.PutNode("logic", "'0", "scaiev_glue", BNode.RdCommitFlushMask, core.GetRootStage());

    this.PutNode("logic", "scaiev.fetch_fetchID", "scaiev_glue", node_RdFetchID, stage_fetch_interm);
    this.PutNode("logic", "scaiev.fetch_fetchFlushID", "scaiev_glue", node_RdFetchPostFlushID, stage_fetch_interm);
    this.PutNode("logic", "scaiev.fetch_fetchFlushCount", "scaiev_glue", node_RdFetchFlushCount, stage_fetch_interm);
    this.PutNode("logic", "scaiev.decode_fetchID", "scaiev_glue", node_RdFetchID, stage_decode);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdPipeInto, stage_issue);

    this.PutNode("logic", String.format("%s ? %s : scaiev.pre_fetch_PC", signame_WrPC_after_Fetch_valid, signame_WrPC_after_Fetch),
                 "scaiev_glue", BNode.RdPC, stage_fetch);
    this.PutNode("logic", "scaiev.fetch_PC", "scaiev_glue", BNode.RdPC, stage_fetch_interm);
    this.PutNode("logic", "scaiev.decode_PC", "scaiev_glue", BNode.RdPC, stage_decode);
    this.PutNode("logic", "scaiev.issue_PC", "scaiev_glue", BNode.RdPC, stage_issue);
    this.PutNode("logic", "scaiev.execute_PC", "scaiev_glue", BNode.RdPC, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", BNode.RdInstr_RS, stage_decode);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdInstr_RD, stage_decode);
    this.PutNode("logic", "scaiev.decode_Instr", "scaiev_glue", BNode.RdInstr, stage_decode);
    this.PutNode("logic", "scaiev.issue_Instr", "scaiev_glue", BNode.RdInstr, stage_issue);
    this.PutNode("logic", "scaiev.execute_Instr", "scaiev_glue", BNode.RdInstr, stage_execute);

    this.PutNode("logic", "scaiev.issue_RS1", "scaiev_glue", BNode.RdRS1, stage_issue);
    this.PutNode("logic", "scaiev.execute_RS1", "scaiev_glue", BNode.RdRS1, stage_execute);

    this.PutNode("logic", "scaiev.issue_RS2", "scaiev_glue", BNode.RdRS2, stage_issue);
    this.PutNode("logic", "scaiev.execute_RS2", "scaiev_glue", BNode.RdRS2, stage_execute);

    if (use_native_RdRD) {
      this.PutNode("logic", "scaiev.issue_RD_AS_RS", "scaiev_glue", BNode.RdRD, stage_issue);
      this.PutNode("logic", "scaiev.execute_RD_AS_RS", "scaiev_glue", BNode.RdRD, stage_execute);
    } else {
      this.PutNode("logic", "", "scaiev_glue", BNode.RdRD, stage_issue);
      this.PutNode("logic", "", "scaiev_glue", BNode.RdRD, stage_execute);
    }

    this.PutNode("logic", "{scaiev.execute_expects_rd, scaiev.execute_ID_or_counter}", "scaiev_glue", BNode.RdInStageID, stage_execute);

    this.PutNode("logic", "!scaiev.pre_fetch_isStalling", "scaiev_glue", BNode.RdInStageValid, stage_fetch);
    this.PutNode("logic", "scaiev.fetch_valid", "scaiev_glue", BNode.RdInStageValid, stage_fetch_interm);
    this.PutNode("logic", "scaiev.decode_valid", "scaiev_glue", BNode.RdInStageValid, stage_decode);
    this.PutNode("logic", "scaiev.issue_valid", "scaiev_glue", BNode.RdInStageValid, stage_issue);
    this.PutNode("logic", "scaiev.execute_valid", "scaiev_glue", BNode.RdInStageValid, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", BNode.WrDeqInstr, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrInStageID, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrInStageID_valid, stage_execute);
    this.PutNode("logic", "scaiev.execute_commitNow_resp", "scaiev_glue", BNode.WrInStageID_validResp, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", from_scal_rdivalid_unmasked_generic_node, stage_decode);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdIValid, stage_decode);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdIValid, stage_issue);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdIValid, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdAnyValid, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem, stage_execute);
    // this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_validResp,stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_validReq, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_validResp, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_validReq, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_validResp, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_addr, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_size, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_addr_valid, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_addr, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_size, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_addr_valid, stage_execute);

    this.PutNode("logic", "scaiev.pre_fetch_isStalling", "scaiev_glue", BNode.RdStall, stage_fetch);
    this.PutNode("logic", "scaiev.fetch_isStalling", "scaiev_glue", BNode.RdStall, stage_fetch_interm);
    this.PutNode("logic", "scaiev.decode_isStalling || hazard_unit_scaiev_decode_stall_set || (decode_rdReg_RD && !decode_isRepeated_RdRD)",
                 "scaiev_glue", BNode.RdStall, stage_decode);
    this.PutNode("logic",
                 "scaiev.issue_isStalling || scaiev.issue_injectLS_potentiallyValid && scaiev.issue_isLS || " +
                     "scaiev.execute_injectLS_potentiallyValid && scaiev.issue_isLS || " +
                     signame_issue_stall_before_mem + " || " + signame_issue_stall_before_cf + " || issue_isFirst_RdRD",
                 "scaiev_glue", BNode.RdStall, stage_issue);
    this.PutNode("logic", "scaiev.execute_isStalling_reason_core || " + signame_execute_stall_mem + " || execute_isFirst_RdRD",
                 "scaiev_glue", BNode.RdStall, stage_execute);

    // this.PutNode(1,  true, "logic", "", "scaiev_glue", BNode.WrStall,stage_fetch);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrStall, stage_fetch);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrStall, stage_fetch_interm);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrStall, stage_decode);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrStall, stage_issue);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrStall, stage_execute);

    this.PutNode("logic", "scaiev.fetch_isFlushing_internal", "scaiev_glue", BNode.RdFlush, stage_fetch);
    this.PutNode("logic", "scaiev.fetch_isFlushing_internal || " + signame_WrPC_reg_after_stall_valid, "scaiev_glue", BNode.RdFlush,
                 stage_fetch_interm);
    this.PutNode("logic", "scaiev.decode_isFlushing_internal", "scaiev_glue", BNode.RdFlush, stage_decode);
    this.PutNode("logic", "scaiev.issue_isFlushing_internal", "scaiev_glue", BNode.RdFlush, stage_issue);
    this.PutNode("logic", "0", "scaiev_glue", BNode.RdFlush, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", BNode.WrFlush, stage_fetch);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrFlush, stage_fetch_interm);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrFlush, stage_decode);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrFlush, stage_issue);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrFlush, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD_validData, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD_valid, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD, stage_execute);
    // Not present: WrRD_addr

    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD_spawn, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD_spawn_valid, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD_spawn_cancel, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD_spawn_validResp, pseudostage_spawn);
    //-> Prepare changes address size to 6+1+6 if use_wbdecoupled_cva5alloc
    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD_spawn_addr, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD_spawn_allowed, pseudostage_spawn);

    this.PutNode("logic", "scaiev.injectLS_readData", "scaiev_glue", BNode.RdMem_spawn, pseudostage_spawn);
    // this.PutNode("logic", "scaiev.execute_injectLS_done && regular_read_pending", "scaiev_glue",
    // BNode.RdMem_spawn_validResp,pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_spawn_validResp, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_spawn_validReq, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_spawn_addr, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_spawn_size, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_spawn_write, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_spawn_allowed, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_spawn, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_spawn_validReq, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_spawn_addr, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_spawn_size, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_spawn_validResp, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_spawn_write, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_spawn_allowed, pseudostage_spawn);

    this.PutNode("logic", "1", "scaiev_glue", BNode.ISAX_spawnAllowed, stage_decode);

    if (use_wbdecoupled_cva5alloc) {
      // this.PutNode("logic", "scaiev.rf_wrReg","scaiev_glue", BNode.commited_rd_spawn_valid,pseudostage_spawn);
      //-> Prepare changes address size to 6+1+6
      this.PutNode("logic", "{scaiev.rf_wrReg_prev_phys_RD, scaiev.rf_wrReg_phys_RD}", "scaiev_glue", BNode.committed_rd_spawn,
                   pseudostage_spawn);
      // this.PutNode(6+1+6, false,  "logic", "{scaiev.rf_wrReg_prev_phys_RD, scaiev.rf_wrReg_phys_RD}",
      // "scaiev_glue",BNode.commited_rd_spawn,pseudostage_spawn);
    } else {
      // this.PutNode("logic", BNode.ISAX_fire2_regF_reg,"scaiev_glue", BNode.commited_rd_spawn_valid,pseudostage_spawn);
      this.PutNode("logic", "scaiev.decode_wrReg_RD", "scaiev_glue", BNode.committed_rd_spawn, pseudostage_spawn);
    }
  }
}
