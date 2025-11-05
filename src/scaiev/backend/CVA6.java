package scaiev.backend;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.SCALBackendAPI.CustomCoreInterface;
import scaiev.coreconstr.Core;
import scaiev.coreconstr.Core.CoreTag;
import scaiev.coreconstr.CoreNode;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.frontend.SCAL;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.ScheduleFront;
import scaiev.scal.CombinedNodeLogicBuilder;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.SCALPinNet;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.scal.strategy.pipeline.IDBasedPipelineStrategy;
import scaiev.scal.strategy.pipeline.IDRetireSerializerStrategy;
import scaiev.scal.strategy.pipeline.NodeRegPipelineStrategy;
import scaiev.util.FileWriter;
import scaiev.util.Log2;
import scaiev.util.ToWrite;
import scaiev.util.Verilog;

public class CVA6 extends CoreBackend {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  static final Path pathCore = Path.of("CoresSrc/CVA6");

  public String getCorePathIn() { return pathCore.toString(); }

  static final Path pathcva6 = Path.of("core");
  static final String topModule = "cva6_ariane_wrapper";
  static final String wrapperModule = "cva6_glue_wrapper";
  static final String targetModule = "scaiev_glue";
  static final String configPackage = "scaiev_config";
  static final String tab = "    ";
  static final String tab2 = tab + tab;
  static final String tab3 = tab2 + tab;
  static final String tab4 = tab3 + tab;
  static final String tab5 = tab4 + tab;
  static final String tab6 = tab5 + tab;

  static final int trans_id_bits = 3;

  static final int stagePos_fetch = 0;
  static final int stagePos_realign = 1;
  static final int stagePos_decode = 2;
  static final int stagePos_issue = 3;
  static final int stagePos_execute = 4;
  static final int pseudostagePos_spawn = 5;

  private PipelineStage stage_fetch;
  private PipelineStage stage_realign;
  private PipelineStage stage_decode;
  private PipelineStage stage_issue;
  private PipelineStage stage_execute;
  private PipelineStage stage_executeothers;
  private PipelineStage pseudostage_spawn;
  private PipelineStage[] stages;
  private ArrayList<PipelineStage> stageList = new ArrayList<PipelineStage>();

  static final String[] intToStage = {"scaiev_fetch_", "scaiev_realign_", "scaiev_decode_",
                                      "scaiev_issue_", "scaiev_execute_", "scaiev_spawn_"};

  static final String signame_issue_stall_before_cf = "issue_stall_before_cf";
  static final String signame_execute_stall_mem = "execute_stall_mem";

  static final String signame_to_scal_stage_valid_all = "glueToSCAL_IValid_cond_all";
  static final String signame_to_scal_stage_valid_instr = "glueToSCAL_IValid_cond_instr";
  static final String signame_from_scal_rdivalid_unmasked = "SCALToGlue_RdIValid_unmasked";

  private BNode BNode = new BNode();

  private Core cva6_core;
  private HashMap<String, SCAIEVInstr> ISAXes;
  private HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  private FileWriter toFile = new FileWriter(pathCore.toString());
  private Verilog language = null;

  private HashMap<String, Boolean> configFlags = new HashMap<>();
  private SCAL scal;
  private HashSet<String> addedInputs = new HashSet<String>();
  private HashSet<String> addedOutputs = new HashSet<String>();

  public int log2(int n) {
    int result = (int)(Math.log(n) / Math.log(2));

    return result;
  }

  private void addLogic(String text) {
    toFile.UpdateContent(this.ModFile(targetModule), "endmodule", new ToWrite(text, false, true, "", true, targetModule));
  }

  private void addDeclaration(String text) {
    toFile.UpdateContent(this.ModFile(targetModule), ");", new ToWrite(text, true, false, "module ", false, targetModule));
  }

  private String sigor0(String sig) {
    String ret = "0";
    if (addedInputs.contains(sig) || addedOutputs.contains(sig)) {
      ret = sig;
    }
    return ret;
  }

  private boolean ContainsOpInStage(SCAIEVNode operation, PipelineStage stage) {
    return op_stage_instr.containsKey(operation) && op_stage_instr.get(operation).containsKey(stage) &&
        !op_stage_instr.get(operation).get(stage).isEmpty();
  }

  private boolean ContainsOpInStage(SCAIEVNode operation, PipelineStage stage, String instr_name) {
    return op_stage_instr.containsKey(operation) && op_stage_instr.get(operation).containsKey(stage) &&
        op_stage_instr.get(operation).get(stage).contains(instr_name);
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
            //new NodeInstanceDesc.Key(BNode.RdIValid, stage_issue, instr),
            new NodeInstanceDesc.Key(BNode.RdAnyValid.NodeNegInput(), stage_execute, instr)
        ));
    //All ISAXes: Decode; some special conditions.
    ivalidKeyStream = Stream.concat(ivalidKeyStream, ISAXes.entrySet().stream()
        .filter(isaxEntry -> !isaxEntry.getValue().HasNoOp())
        .flatMap(isaxEntry -> {
          String isaxName = isaxEntry.getKey();
          SCAIEVInstr isax = isaxEntry.getValue();
          var ret = Stream.of(new NodeInstanceDesc.Key(BNode.RdIValid, stage_decode, isaxName));
          if (isax.equals(SCAL.PredefInstr.kill.instr) || isax.HasNode(BNode.RdMem))
            ret = Stream.concat(ret, Stream.of(new NodeInstanceDesc.Key(BNode.RdIValid, stage_issue, isaxName)));
          if (isax.HasNode(BNode.WrPC))
            ret = Stream.concat(ret, Stream.of(new NodeInstanceDesc.Key(BNode.RdAnyValid.NodeNegInput(), stage_execute, isaxName)));
          if (isax.HasNode(BNode.WrRD) || (isax.HasNode(BNode.WrRD_spawn) && !isax.GetRunsAsDecoupled()))
            ret = Stream.concat(ret, Stream.of(new NodeInstanceDesc.Key(BNode.RdIValid, stage_execute, isaxName)));
          return ret;
        }));

    //Call the consumer for each _unique_ pin.
    ivalidKeyStream.distinct().forEach(ivalidKey -> {
      consumer.accept(ivalidKey.getNode(), ISAXes.get(ivalidKey.getISAX()), ivalidKey.getStage());
    });
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
  private String makeIValidExpression(List<String> isaxNames, PipelineStage stage) {
    return isaxNames.stream()
        .map(isaxName ->  language.CreateNodeName(BNode.RdIValid.NodeNegInput(), stage, isaxName))
        .reduce((a, b) -> a + " || " + b)
        .orElse("1'b0");
  }

  private String signame_to_scal_pipeinto_executesv1;
  private String signame_to_scal_pipeinto_executecva;

  //NOTE: Values from cva6/core/include/cv64a6_imafdc_sv39_config_pkg.sv
  private int CVA6ConfigXlen = 64;
  private final int CVA6ConfigNrCommitPorts = 2;
  private final int CVA6ConfigNrScoreboardEntries = 8;


  private SCAIEVNode node_RdZOLOverride = new SCAIEVNode("RdPCOverride", 64, true) {
    {
      validBy = AdjacentNode.validReq;
    }
  };
  private SCAIEVNode node_RdZOLOverride_valid = new SCAIEVNode(node_RdZOLOverride, AdjacentNode.validReq, 1, true, false);
  //private SCAIEVNode node_RdFetchReplay = new SCAIEVNode("RdFetchReplay", 1, false);

  //Strategy to pipeline RdZOLOverride and _valid from fetch to realign.
  MultiNodeStrategy zolOverridePipelineStrategy;

  @Override
  public void Prepare(HashMap<String, SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                      Core core, SCALBackendAPI scalAPI, BNode user_BNode) {
    super.Prepare(ISAXes, op_stage_instr, core, scalAPI, user_BNode);
    this.BNode = user_BNode;
    this.BNode.AddCoreBNode(node_RdFetchID);
    this.BNode.AddCoreBNode(node_RdIQueueID);
    this.BNode.AddCoreBNode(node_RdFetchFlushCount);
    this.BNode.AddCoreBNode(node_RdFetchFlushFromID);
    this.BNode.AddCoreBNode(node_RdIQueueFlushMask);
    this.language = new Verilog(user_BNode, toFile, this);
    this.cva6_core = core;
    this.ISAXes = ISAXes;
    this.CVA6ConfigXlen = core.getTags().contains(CoreTag.RV64) ? 64 : 32;
    this.node_RdZOLOverride.size = CVA6ConfigXlen;
    //		if (zol != null)
    //			ISAXes.put("ZOL", zol);
    this.op_stage_instr = op_stage_instr;

    this.stage_fetch = core.GetRootStage().getChildren().get(0);
    this.stage_realign = stage_fetch.getNext().get(0);
    this.stage_decode = stage_realign.getNext().get(0);

    this.stage_issue = stage_decode.getNext().stream().filter(stage -> stage.getName().equals("issue")).findAny().orElseThrow();
    this.stage_execute = stage_issue.getNext().stream().filter(stage -> stage.getName().equals("executesv1")).findAny().orElseThrow();
    this.stage_executeothers = stage_issue.getNext().stream().filter(stage -> stage.getName().equals("executecva")).findAny().orElseThrow();
    this.pseudostage_spawn = stage_execute.getNext().stream().filter(stage -> stage.getName().equals("decoupled1")).findAny().orElseThrow();
    this.stages = new PipelineStage[] {stage_fetch, stage_realign, stage_decode, stage_issue, stage_execute, pseudostage_spawn};

    for (int i = 0; i < this.stages.length; ++i) {
      assert (this.stages[i].getStagePos() == i);
      stageList.add(stages[i]);
    }

    node_RdFetchID.elements = 1 << node_RdFetchID.size;
    node_RdIQueueID.elements = 1 << node_RdIQueueID.size;
    node_RdFetchFlushFromID.elements = 1 << node_RdFetchFlushFromID.size;
    node_RdFetchFlushCount.elements = node_RdFetchFlushFromID.elements; //Can flush all from fetch queue

    BNode.RdInstr_RS.size = 6*2;
    BNode.RdInstr_RS.elements = 2;
    core.PutNode(BNode.RdInstr_RS, new CoreNode(stagePos_decode, 0, stagePos_decode, stagePos_issue, BNode.RdInstr_RS.name));
    BNode.RdInstr_RD.size = 6;
    BNode.RdInstr_RD.elements = 1;
    core.PutNode(BNode.RdInstr_RD, new CoreNode(stagePos_decode, 0, stagePos_decode, stagePos_issue, BNode.RdInstr_RD.name));

    ScheduleFront rootSchedFront = new ScheduleFront(new PipelineFront(core.GetRootStage()));
    BNode.RdInStageID.size = Log2.clog2(CVA6ConfigNrScoreboardEntries);
    BNode.WrInStageID.size = Log2.clog2(CVA6ConfigNrScoreboardEntries);
    core.PutNode(BNode.WrDeqInstr, new CoreNode(stagePos_execute, 0, stagePos_execute, stagePos_execute + 1, BNode.WrDeqInstr.name));
    core.PutNode(BNode.RdInStageID, new CoreNode(stagePos_execute, 0, stagePos_execute, stagePos_execute + 1, BNode.RdInStageID.name));
    core.PutNode(BNode.RdInStageValid, new CoreNode(stagePos_fetch, 0, stagePos_execute, stagePos_execute + 1, BNode.RdInStageValid.name));
    core.PutNode(BNode.WrInStageID, new CoreNode(stagePos_execute, 0, stagePos_execute, stagePos_execute + 1, BNode.WrInStageID.name));

    BNode.RdIssueID.size = Log2.clog2(CVA6ConfigNrScoreboardEntries);
    BNode.RdIssueID.elements = CVA6ConfigNrScoreboardEntries;
    core.PutNode(BNode.RdIssueID, new CoreNode(stagePos_issue, 0, stagePos_issue, stagePos_issue + 1, BNode.RdIssueID.name));
    BNode.RdIssueFlushID.size = Log2.clog2(CVA6ConfigNrScoreboardEntries);
    BNode.RdIssueFlushID.elements = CVA6ConfigNrScoreboardEntries;
    core.PutNode(BNode.RdIssueFlushID, new CoreNode(stagePos_issue, 0, stagePos_issue, stagePos_issue + 1, BNode.RdIssueFlushID.name));

    BNode.RdCommitID.size = Log2.clog2(CVA6ConfigNrScoreboardEntries);
    BNode.RdCommitID.elements = CVA6ConfigNrScoreboardEntries;
    BNode.RdCommitIDCount.size = Log2.clog2(CVA6ConfigNrCommitPorts+1);
    BNode.RdCommitIDCount.elements = CVA6ConfigNrCommitPorts;
    core.PutNode(BNode.RdCommitID, new CoreNode(rootSchedFront, 0, rootSchedFront, new ScheduleFront(), BNode.RdCommitID.name));
    core.PutNode(BNode.RdCommitIDCount, new CoreNode(rootSchedFront, 0, rootSchedFront, new ScheduleFront(), BNode.RdCommitIDCount.name));

    BNode.RdCommitFlushID.size = Log2.clog2(CVA6ConfigNrScoreboardEntries);
    BNode.RdCommitFlushID.elements = CVA6ConfigNrScoreboardEntries;
    BNode.RdCommitFlushIDCount.size = Log2.clog2(CVA6ConfigNrCommitPorts+1);
    BNode.RdCommitFlushIDCount.elements = CVA6ConfigNrCommitPorts;
    core.PutNode(BNode.RdCommitFlushID, new CoreNode(rootSchedFront, 0, rootSchedFront, new ScheduleFront(), BNode.RdCommitFlushID.name));
    core.PutNode(BNode.RdCommitFlushIDCount, new CoreNode(rootSchedFront, 0, rootSchedFront, new ScheduleFront(), BNode.RdCommitFlushIDCount.name));

    BNode.RdCommitFlushMask.size = 0;

    BNode.RdCommitFlushAll.size = 1;
    BNode.RdCommitFlushAllID.size = Log2.clog2(CVA6ConfigNrScoreboardEntries);
    core.PutNode(BNode.RdCommitFlushAll, new CoreNode(rootSchedFront, 0, rootSchedFront, new ScheduleFront(), BNode.RdCommitFlushAll.name));
    core.PutNode(BNode.RdCommitFlushAllID, new CoreNode(rootSchedFront, 0, rootSchedFront, new ScheduleFront(), BNode.RdCommitFlushAllID.name));

    BNode.RdMem_addr.mustToCore = true;
    BNode.WrMem_addr.mustToCore = true;
    BNode.RdMem_instrID.mustToCore = true;
    BNode.WrMem_instrID.mustToCore = true;

    // NodeRegPipelineStrategy looks for an optional RdPipeInto node with "stage_<dest stage name>" in the ISAX field.
    var interface_toscal_pipeinto_executesv1 =
        new CustomCoreInterface(BNode.RdPipeInto.name, "wire", stage_issue, 1, true, "stage_" + stage_execute.getName());
    scalAPI.AddCustomToSCALPin(interface_toscal_pipeinto_executesv1);
    this.signame_to_scal_pipeinto_executesv1 = interface_toscal_pipeinto_executesv1.getSignalName(this.language, true);

    var interface_toscal_pipeinto_executecva =
        new CustomCoreInterface(BNode.RdPipeInto.name, "wire", stage_issue, 1, true, "stage_" + stage_executeothers.getName());
    scalAPI.AddCustomToSCALPin(interface_toscal_pipeinto_executecva);
    this.signame_to_scal_pipeinto_executecva = interface_toscal_pipeinto_executecva.getSignalName(this.language, true);

    // Request RdIValid/RdAnyValid interface pins
    forEachRdValidPin((node, isax, stage) -> scalAPI.RequestToCorePin(node, stage, isax.GetName()));
    if (ContainsOpInStage(BNode.WrPC, stage_fetch)) {
      //Fetch replay logic
      scalAPI.RequestToCorePin(BNode.RdOrigPC, stage_realign, "");
      scalAPI.RequestToCorePin(BNode.RdOrigPC_valid, stage_realign, "");
    }

    scalAPI.SetHasAdjSpawnAllowed(BNode.RdMem_spawn_allowed);
    scalAPI.SetHasAdjSpawnAllowed(BNode.WrMem_spawn_allowed);
    scalAPI.SetHasAdjSpawnAllowed(BNode.WrRD_spawn_allowed);
    // Resource conflict resolution is handled via scaiev_glue.sv.
    scalAPI.DisableStallForSpawnCommit(BNode.WrRD_spawn);
    scalAPI.DisableStallForSpawnCommit(BNode.WrMem_spawn);
    scalAPI.DisableStallForSpawnCommit(BNode.RdMem_spawn);

    scalAPI.getStrategyBuilders().put(StrategyBuilders.UUID_NodeRegPipelineStrategy, args -> this.build_cva6NodeRegPipelineStrategy(args));

    //Generate and pipeline RdZOLOverride and _validReq to the realign stage.
    // -> Feed the signal through SCAL for pipelining.
    BNode.AddCoreBNode(node_RdZOLOverride);
    BNode.AddCoreBNode(node_RdZOLOverride_valid);

    //Note: Use of CVA6's language object in SCAL is not optimal.
    var innerPipelineStrategy = scalAPI.getStrategyBuilders().buildNodeRegPipelineStrategy(language, BNode,
        new PipelineFront(stage_fetch), false, false, false,
        _nodeKey -> _nodeKey.getNode().equals(node_RdZOLOverride) || _nodeKey.getNode().equals(node_RdZOLOverride_valid),
        _nodeKey -> false,
        MultiNodeStrategy.noneStrategy,
        false);
    this.zolOverridePipelineStrategy = new MultiNodeStrategy() {
      @Override
      public void implement(Consumer<NodeLogicBuilder> out, Iterable<Key> nodeKeys, boolean isLast) {
        if (isLast) //avoid processing PIPEOUT nodes before PipeoutRegularStrategy
          innerPipelineStrategy.implement(out, nodeKeys, isLast);
      }
    };

    var pcoverride_from_scal_interface = new CustomCoreInterface(node_RdZOLOverride.name, "wire", stage_decode, node_RdZOLOverride.size, false, "");
    //var fetchreplay_from_core_interface = new CustomCoreInterface(node_RdFetchReplay.name, "wire", stage_fetch, node_RdFetchReplay.size, true, "");
    //var fetchreplay_from_core_key = fetchreplay_from_core_interface.makeKey(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN);
    //Strategy that requests the node in all stages
    scalAPI.AddCustomToCorePinUsing(pcoverride_from_scal_interface, NodeLogicBuilder.fromFunction("CVA6_RdZOLOverride_export", registry -> {
      var ret = new NodeLogicBlock();
      //RdZOLOverride_fetch := WrPC_fetch
      var fetchStageKey = new NodeInstanceDesc.Key(node_RdZOLOverride, stage_fetch, "");
      var fetchStageValidKey = new NodeInstanceDesc.Key(node_RdZOLOverride_valid, stage_fetch, "");
      var fetchStageFromInst_opt = registry.lookupOptional(new NodeInstanceDesc.Key(BNode.WrPC, stage_fetch, ""));
      var fetchStageFromInstValid_opt = registry.lookupOptional(new NodeInstanceDesc.Key(BNode.WrPC_valid, stage_fetch, ""));
      String realignStageExpr = "%d'd0".formatted(node_RdZOLOverride.size);
      if (fetchStageFromInst_opt.isPresent() && fetchStageFromInstValid_opt.isPresent()) {
        String wrpcExpr = fetchStageFromInst_opt.get().getExpression();
        int wrpcInSize = fetchStageFromInst_opt.get().getKey().getNode().size;
        if (wrpcInSize < node_RdZOLOverride.size)
          wrpcExpr = "{%d'd0,%s}".formatted(node_RdZOLOverride.size - wrpcInSize, wrpcExpr);
        ret.outputs.add(new NodeInstanceDesc(fetchStageKey, wrpcExpr, ExpressionType.AnyExpression));
        ret.outputs.add(new NodeInstanceDesc(fetchStageValidKey, fetchStageFromInstValid_opt.get().getExpression(), ExpressionType.AnyExpression));
        realignStageExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(Purpose.PIPEDIN, node_RdZOLOverride, stage_decode, ""));
      }
      else {
        //No WrPC -> don't pipeline, assign zero-defaults directly in decode
        realignStageExpr = "%d'd0".formatted(node_RdZOLOverride.size);
        ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.WIREDIN, node_RdZOLOverride_valid, stage_decode, ""),
                                             "1'b0", ExpressionType.AnyExpression_Noparen));
      }
      var exportKey = pcoverride_from_scal_interface.makeKey(Purpose.WIREOUT);
      ret.outputs.add(new NodeInstanceDesc(exportKey, realignStageExpr, ExpressionType.AnyExpression));
      return ret;
    }));
    var pcoverridevalid_from_scal_interface = new CustomCoreInterface(node_RdZOLOverride_valid.name, "wire", stage_decode, 1, false, "");
    scalAPI.AddCustomToCorePinUsing(pcoverridevalid_from_scal_interface, NodeLogicBuilder.fromFunction("CVA6_RdZOLOverride_validReq_export", registry -> {
      var ret = new NodeLogicBlock();
      String realignStageExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(Purpose.match_WIREDIN_OR_PIPEDIN,
                                                                                           node_RdZOLOverride_valid, stage_decode, ""));
      var exportKey = pcoverridevalid_from_scal_interface.makeKey(Purpose.WIREOUT);
      ret.outputs.add(new NodeInstanceDesc(exportKey, realignStageExpr, ExpressionType.AnyExpression));
      return ret;
    }));
    scal = ((SCAL)scalAPI);
  }

  private SCAIEVNode node_RdFetchID = new SCAIEVNode("RdCVA6FetchID", 2, false) {
    { tags.add(NodeTypeTag.staticReadResult); }
  };
  // private SCAIEVNode node_RdFetchPostFlushID = new SCAIEVNode("RdCVA6FetchPostFlushID", 4, false);
  //scaiev_fetch_reqID_flushFrom, scaiev_fetch_reqID_flushCount
  private SCAIEVNode node_RdFetchFlushFromID = new SCAIEVNode("RdCVA6FetchFlushFromID", 2, false);
  private SCAIEVNode node_RdFetchFlushCount = new SCAIEVNode("RdCVA6FetchFlushCount", 3, false);

  private SCAIEVNode node_RdIQueueID = new SCAIEVNode("RdCVA6IQueueID", 3, false) {
    { tags.add(NodeTypeTag.staticReadResult); }
  };
  private SCAIEVNode node_RdIQueueFlushMask = new SCAIEVNode("RdCVA6IQueueFlushMask", 8, false); //(scaiev_decode_isFlushing | scaiev_decode_flush)

  private List<MultiNodeStrategy> idbasedPipelineSubstrategies = new ArrayList<>();
  @SuppressWarnings("unchecked")
  private MultiNodeStrategy build_cva6NodeRegPipelineStrategy(Map<String, Object> args) {
    // Build a NodeRegPipelineStrategy that calls into IDBasedPipelineStrategy objects when faced with fetch-to-decode pipelining.
    return new NodeRegPipelineStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (PipelineFront)args.get("minPipeFront"),
                                       (Boolean)args.get("zeroOnFlushSrc"), (Boolean)args.get("zeroOnFlushDest"),
                                       (Boolean)args.get("zeroOnBubble"), (Predicate<NodeInstanceDesc.Key>)args.get("can_pipe"),
                                       (Predicate<NodeInstanceDesc.Key>)args.get("prefer_direct"),
                                       (MultiNodeStrategy)args.get("strategy_instantiateNew"),
                                       (Boolean)args.get("forwardRequestedFor")) {
      // Use one strategy for all nodes pipelined at full ID width (i.e. cannot run out of IDs/buffer space before the core does)
      private IDBasedPipelineStrategy fetchRealignPipeFullWidth = null;
      private IDBasedPipelineStrategy realignDecodePipe = null;

      private IDBasedPipelineStrategy makeFetchRealignPipeStrategy() {
        return new IDBasedPipelineStrategy(language, bNodes, node_RdFetchID, new NodeInstanceDesc.Key(node_RdFetchID, stage_realign, ""),
                                           node_RdFetchID.size, new PipelineFront(stage_fetch), true, true,
                                           new PipelineFront(stage_realign),
                                           List.of(new IDRetireSerializerStrategy.IDAndCountRetireSource(
                                                       new NodeInstanceDesc.Key(node_RdFetchFlushFromID, stage_fetch, ""),
                                                       new NodeInstanceDesc.Key(node_RdFetchFlushCount, stage_fetch, ""), true)),
                                           key -> key.getStage().equals(stage_realign));
      }
      private IDBasedPipelineStrategy makeRealignDecodePipeStrategy() {
        // Note: The ID space is non-continuous (two parallel FIFOs)
        return new IDBasedPipelineStrategy(language, bNodes, node_RdIQueueID, new NodeInstanceDesc.Key(node_RdIQueueID, stage_decode, ""),
                                           node_RdIQueueID.size, new PipelineFront(stage_realign), true, true,
                                           new PipelineFront(stage_decode),
                                           List.of(new IDRetireSerializerStrategy.BitmaskRetireSource(
                                                       new NodeInstanceDesc.Key(node_RdIQueueFlushMask, stage_realign, ""), true)),
                                           key -> key.getStage().equals(stage_decode));
      }

      @Override
      protected NodeLogicBuilder makePipelineBuilder_single(NodeInstanceDesc.Key nodeKey, ImplementedKeyInfo implementation) {
        IDBasedPipelineStrategy strategy = null;
        if (nodeKey.getStage().equals(stage_realign)) {
          // Always use the full width (the fetch ID space has node_RdFetchID.size == 2 -> 4 elements in buffer)
          if (fetchRealignPipeFullWidth == null) {
            fetchRealignPipeFullWidth = makeFetchRealignPipeStrategy();
            idbasedPipelineSubstrategies.add(fetchRealignPipeFullWidth);
          }
          strategy = fetchRealignPipeFullWidth;
        } else if (nodeKey.getStage().equals(stage_decode)) {
          //.. the full width hurts a bit more here (node_RdIQueueID.size == 3 -> 8 elements),
          //   but stalling realign is not supported in the core fork.
          //   -> Stalling realign would require replaying the fetch (-> essentially a flush).
          if (realignDecodePipe == null) {
            realignDecodePipe = makeRealignDecodePipeStrategy();
            idbasedPipelineSubstrategies.add(realignDecodePipe);
          }
          strategy = realignDecodePipe;
        } else
          return super.makePipelineBuilder_single(nodeKey, implementation);

        List<NodeLogicBuilder> subBuilders = new ArrayList<>();
        List<NodeInstanceDesc.Key> nodeKeys = new ArrayList<>();
        nodeKeys.add(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, IDBasedPipelineStrategy.purpose_ReadFromIDBasedPipeline));
        strategy.addKeyImplementation(nodeKey, implementation);
        strategy.implement(builderToAdd -> subBuilders.add(builderToAdd), nodeKeys, false);
        return CombinedNodeLogicBuilder.of("CVA6_IDBasedPipelineStrategy-fetch-to-decode(" + nodeKey.toString() + ")", subBuilders);
      }
    };
  }

  @Override
  public List<MultiNodeStrategy> getAdditionalSCALPostStrategies() {
    return List.of(zolOverridePipelineStrategy);
  }
  @Override
  public List<MultiNodeStrategy> getAdditionalSCALPreStrategies() {
    // Add a strategy that adds required RdFetchID, RdFetchPostFlushID, etc. interface pins.
    return List.of(new MultiNodeStrategy() {
      record ImplementedNodeStage (SCAIEVNode node, PipelineStage stage) {}
      Set<ImplementedNodeStage> implemented = new HashSet<>();

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
          SCAIEVNode node = nodeKey.getNode();
          //Check for the special CVA6->SCAL nodes, create a builder that triggers interface generation.
          if (nodeKey.getStage().equals(stage_fetch) && (node.equals(node_RdFetchID) || node.equals(node_RdFetchFlushFromID) || node.equals(node_RdFetchFlushCount))
              || nodeKey.getStage().equals(stage_realign) && (node.equals(node_RdFetchID) || node.equals(node_RdIQueueID) || node.equals(node_RdIQueueFlushMask))
              || nodeKey.getStage().equals(stage_decode) && node.equals(node_RdIQueueID)) {
            if (implemented.add(new ImplementedNodeStage(node, nodeKey.getStage()))) {
              out.accept(makeInterfaceRequestBuilder(nodeKey));
            }
          }
        }
        idbasedPipelineSubstrategies.forEach(substrategy -> substrategy.implement(out, nodeKeys, isLast));
      }
    });
  }

  public boolean Generate(HashMap<String, SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                          String extension_name, Core core, String out_path) {
    this.cva6_core = core;
    this.ISAXes = ISAXes;
    this.op_stage_instr = op_stage_instr;

    Configcva6();
    language.clk = "clk";
    language.reset = "rst";
    IntegrateISAX_Defaults();
    IntegrateISAX_IOs();
    IntegrateISAX_Encoding();
    IntegrateISAX_RdRS();
    IntegrateISAX_RdInstr();
    IntegrateISAX_RdFlush();
    IntegrateISAX_RdStall();
    IntegrateISAX_RdRD();

    IntegrateISAX_WrRD();
    IntegrateISAX_Branch();
    IntegrateISAX_Mem();
    IntegrateISAX_WrPC();
    IntegrateISAX_Jump();

    IntegrateISAX_WrStall();
    IntegrateISAX_WrFlush();

    IntegrateISAX_MiscPipeline();

    language.FinalizeInterfaces();
    toFile.WriteFiles(language.GetDictModule(), language.GetDictEndModule(), out_path);

    return true;
  }

  private void IntegrateISAX_Defaults() {
    for (int i = stagePos_fetch; i <= stagePos_execute; i++) {
      if (ContainsOpInStage(BNode.RdInStageValid, stages[i])) {
        String validCond = "1'b1"; // TODO: Fix this!
        if (i == stagePos_issue)
          validCond = "scaiev_issue_isValid";
        if (i == stagePos_execute)
          validCond = "scaiev_execute_isValid";
        addLogic("assign %s = %s;".formatted(language.CreateNodeName(BNode.RdInStageValid, stages[i], ""), validCond));
      }
    }
  }

  private void IntegrateISAX_MiscPipeline() {
    addLogic(String.format("assign %s = scaiev_issue_pipeinto_scaievfu;", this.signame_to_scal_pipeinto_executesv1));
    addLogic(String.format("assign %s = !scaiev_issue_pipeinto_scaievfu;", this.signame_to_scal_pipeinto_executecva));
    ;

    if (ContainsOpInStage(BNode.WrDeqInstr, stage_execute)) {
      String wrDeq_node = language.CreateNodeName(BNode.WrDeqInstr, stage_execute, "");
      addLogic(String.format("assign scaiev_execute_semicoupled_deq = %s;", wrDeq_node));
    } else
      addLogic("assign scaiev_execute_semicoupled_deq = 0;");

    if (ContainsOpInStage(BNode.WrInStageID, stage_execute)) {
      String wrID_node = language.CreateNodeName(BNode.WrInStageID, stage_execute, "");
      String wrIDValid_node = language.CreateNodeName(BNode.WrInStageID_valid, stage_execute, "");

      addLogic(String.format("assign scaiev_execute_trans_id_i = %s ? %s : scaiev_execute_trans_id_o;", wrIDValid_node, wrID_node));
      addLogic(String.format("assign scaiev_execute_semicoupled_reenq = %s;", wrIDValid_node));
    }
    else {
      addLogic("assign scaiev_execute_trans_id_i = scaiev_execute_trans_id_o;");
      addLogic("assign scaiev_execute_semicoupled_reenq = 0;");
    }
  }

  private void IntegrateISAX_IOs() {
    HashMap<String, SCALPinNet> netlist = scal.netlist;

    //TODO: Use ContainsOpInStage, etc. instead
    for (String s : netlist.keySet()) {
      SCALPinNet net = netlist.get(s);
      String a = "";
      String b = "";
      if (!net.core_module_pin.equals("")) {
        if (net.core_module_pin.endsWith("o")) {
          a = net.core_module_pin;
        } else {
          b = net.core_module_pin;
        }
      }
      if (!net.scal_module_pin.equals("")) {
        if (net.scal_module_pin.endsWith("o")) {
          a = net.scal_module_pin;
        } else {
          b = net.scal_module_pin;
        }
      }
      if (!net.isax_module_pin.equals("")) {
        if (net.isax_module_pin.endsWith("o")) {
          a = net.isax_module_pin;
        } else {
          b = net.isax_module_pin;
        }
      }
      if (!addedInputs.contains(b)) {
        // addSignalTop(b,a,net.size);
        addedInputs.add(b);
      }
      if (!addedOutputs.contains(a)) {
        addedOutputs.add(a);
      }
    }
    // Add all interface pins except RdIValid.
    //  GenerateAllInterfaces does not generate per-ISAX pins.
    language.GenerateAllInterfaces(topModule,
                                   op_stage_instr.entrySet()
                                       .stream()
                                       .filter(entry_
                                               -> !entry_.getKey().equals(BNode.RdIValid) && !entry_.getKey().equals(BNode.RdAnyValid) &&
                                                      !entry_.getKey().equals(BNode.RdPipeInto))
                                       .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue, (a, b) -> b, HashMap::new)),
                                   ISAXes, cva6_core, null);

    forEachRdValidPin((node, isax, stage) -> language.UpdateInterface(topModule, node.NodeNegInput(), isax.GetName(), stage, true, false));

    language.UpdateInterface(topModule, BNode.RdPipeInto, "stage_" + stage_execute.getName(), stage_issue, true, false);
    language.UpdateInterface(topModule, BNode.RdPipeInto, "stage_" + stage_executeothers.getName(), stage_issue, true, false);
  }

  private void IntegrateISAX_Encoding() {
    List<String> allISAXes =
        ISAXes.entrySet().stream().filter(isaxEntry -> !isaxEntry.getValue().HasNoOp()).map(isaxEntry -> isaxEntry.getKey()).toList();
    // Generate lists of ISAXes by register read/write fields in the encoding.
    List<String> allISAXes_RS1 = getISAXesWithOpInAnyStage(BNode.RdRS1).filter(instr -> !instr.HasNoOp()).map(instr -> instr.GetName()).toList();
    List<String> allISAXes_RS2 = getISAXesWithOpInAnyStage(BNode.RdRS2).filter(instr -> !instr.HasNoOp()).map(instr -> instr.GetName()).toList();
    List<String> allISAXes_RD = getISAXesWithOpInAnyStage(BNode.WrRD).filter(instr -> !instr.HasNoOp()).map(instr -> instr.GetName()).toList();
    List<String> allISAXes_RDdec = getISAXesWithOpInStage(BNode.WrRD_spawn, pseudostage_spawn)
                                   .filter(instr -> !instr.HasNoOp()).map(instr -> instr.GetName()).toList();
    List<String> allISAXes_RdMem = getISAXesWithOpInAnyStage(BNode.RdMem).filter(instr -> !instr.HasNoOp()).map(instr -> instr.GetName()).toList();
    List<String> allISAXes_WrMem = getISAXesWithOpInAnyStage(BNode.WrMem).filter(instr -> !instr.HasNoOp()).map(instr -> instr.GetName()).toList();

    addLogic("assign scaiev_decode_isSCAIEV = " + makeIValidExpression(allISAXes, stage_decode) + ";");
    addLogic("assign scaiev_decode_isSCAIEV_hasRS1 = " + makeIValidExpression(allISAXes_RS1, stage_decode) + ";");
    addLogic("assign scaiev_decode_isSCAIEV_hasRS2 = " + makeIValidExpression(allISAXes_RS2, stage_decode) + ";");
    addLogic("assign scaiev_decode_isSCAIEV_hasRD = " + makeIValidExpression(allISAXes_RD, stage_decode) + ";");
    addLogic("assign scaiev_decode_isSCAIEV_hasRD_decoupled = " + makeIValidExpression(allISAXes_RDdec, stage_decode) + ";");
    addLogic("assign scaiev_decode_isLoad = " + makeIValidExpression(allISAXes_RdMem, stage_decode) + ";");
    addLogic("assign scaiev_decode_isStore = " + makeIValidExpression(allISAXes_WrMem, stage_decode) + ";");
    this.PutNode("logic", "{!scaiev_decode_decInstr.rd_fpr, scaiev_decode_decInstr.rd}", "scaiev_glue", BNode.RdInstr_RD, stage_decode);
    this.PutNode("logic", "{!scaiev_decode_decInstr.rd_fpr, scaiev_decode_decInstr.rd}", "scaiev_glue", BNode.RdInstr_RS, stage_decode);
    if (ContainsOpInStage(BNode.RdInstr_RD, stage_decode)) {
      String rdInstr_RD_expr = "!scaiev_decode_decInstr.rd_fpr, scaiev_decode_decInstr.rd";
      addLogic("assign %s = {%s};".formatted(language.CreateNodeName(BNode.RdInstr_RD, stage_decode, ""), rdInstr_RD_expr));
    }
    if (ContainsOpInStage(BNode.RdInstr_RS, stage_decode)) {
      String rdInstr_RS_expr = "!scaiev_decode_decInstr.rs2_fpr, scaiev_decode_decInstr.rs2"
                               + ", !scaiev_decode_decInstr.rs1_fpr, scaiev_decode_decInstr.rs1";
      addLogic("assign %s = {%s};".formatted(language.CreateNodeName(BNode.RdInstr_RS, stage_decode, ""), rdInstr_RS_expr));
    }
  }

  private void IntegrateISAX_RdRD() {}

  private void IntegrateISAX_RdRS() {
    if (op_stage_instr.keySet().contains(BNode.RdRS1)) {
      for (PipelineStage i : op_stage_instr.get(BNode.RdRS1).keySet()) {
        addLogic("assign RdRS1_" + i.getStagePos() + "_o = " + intToStage[i.getStagePos()] + "rdRS1;");
      }
    }
    if (op_stage_instr.keySet().contains(BNode.RdRS2)) {
      for (PipelineStage i : op_stage_instr.get(BNode.RdRS2).keySet()) {
        addLogic("assign RdRS2_" + i.getStagePos() + "_o = " + intToStage[i.getStagePos()] + "rdRS2;");
      }
    }
  }

  private void IntegrateISAX_RdInstr() {
    if (op_stage_instr.keySet().contains(BNode.RdInstr)) {
      for (PipelineStage i : op_stage_instr.get(BNode.RdInstr).keySet()) {
        addLogic("assign RdInstr_" + i.getStagePos() + "_o = " + intToStage[i.getStagePos()] + "rdInstr;");
      }
    }
  }

  private void IntegrateISAX_RdFlush() {
    if (op_stage_instr.keySet().contains(BNode.RdFlush)) {
      for (PipelineStage i : op_stage_instr.get(BNode.RdFlush).keySet()) {
        addLogic("assign RdFlush_" + i.getStagePos() + "_o = " + intToStage[i.getStagePos()] + "isFlushing;");
      }
    }
  }

  private void IntegrateISAX_RdStall() {
    if (op_stage_instr.keySet().contains(BNode.RdStall)) {
      for (PipelineStage i : op_stage_instr.get(BNode.RdStall).keySet()) {
        String isStalling = intToStage[i.getStagePos()] + "isStalling";
        if (i == stage_execute) {
          addLogic("assign %s = %s || %s;".formatted(language.CreateNodeName(BNode.RdStall, i, ""), signame_execute_stall_mem, isStalling));
        }
        else if (i == stage_issue) {
          addLogic("assign %s = %s || %s;".formatted(language.CreateNodeName(BNode.RdStall, i, ""), signame_issue_stall_before_cf, isStalling));
        }
        else {
          addLogic("assign %s = %s;".formatted(language.CreateNodeName(BNode.RdStall, i, ""), isStalling));
        }
      }
    }
  }

  private void IntegrateISAX_WrStall() {
    // Assign core stall signals by the ISAX WrStall request.
    // Also stall issue in case of injected loads/stores and decoupled WrRD
    // conflicts.
    for (int stagePos = stagePos_fetch; stagePos <= stagePos_execute; stagePos++) {
      PipelineStage stage = stages[stagePos];
      String stallLogic = "";
      if (ContainsOpInStage(BNode.WrStall, stage))
        stallLogic += language.CreateNodeName(BNode.WrStall, stage, "") + " || ";
      if (stage == stage_execute)
        stallLogic += signame_execute_stall_mem + " || ";
      if (stage == stage_issue)
        stallLogic += signame_issue_stall_before_cf + " || ";
      stallLogic += "0";
      if (stage == stage_fetch || stage == stage_issue || stage == stage_execute)
        addLogic("assign " + intToStage[stagePos] + "stall = " + stallLogic + ";\n");
    }
  }

  private void IntegrateISAX_WrFlush() {

    for (int stage = stagePos_decode; stage <= stagePos_issue; stage++) {
      PipelineStage stageP = stages[stage];
      if (ContainsOpInStage(BNode.WrFlush, stageP)) {
        addLogic("assign " + intToStage[stage] + "flush = WrFlush_" + stage + "_i;\n");
      }
    }
  }

  private void IntegrateISAX_WrRD() {
    // ArrayList<String> statement_lines = new ArrayList<>();
    addDeclaration("reg[" + (trans_id_bits - 1) + ":0] saved_trans_id;");
    addDeclaration("reg saved_trans_id_valid;");
    String WrInStageID_validResp_4_o = sigor0("WrInStageID_validResp_4_o");
    String RdInStageID_4_o = sigor0("RdInStageID_4_o");
    String WrPC_validReq_4_i = sigor0("WrPC_validReq_4_i");
    String WrRD_validReq_4_i = sigor0("WrRD_validReq_4_i");
    String WrRD_4_i = sigor0("WrRD_4_i");

    if (!RdInStageID_4_o.equals("0"))
      addLogic("assign RdInStageID_4_o = scaiev_execute_trans_id_o;");
    if (ContainsOpInStage(BNode.RdIssueID, stage_issue))
      addLogic("assign %s = scaiev_issue_trans_id_o;".formatted(language.CreateNodeName(BNode.RdIssueID, stage_issue, "")));
    if (ContainsOpInStage(BNode.RdIssueFlushID, stage_issue))
      addLogic("assign %s = '0;".formatted(language.CreateNodeName(BNode.RdIssueFlushID, stage_issue, "")));
    // addLogic("assign scaiev_execute_trans_id_i = "+WrInStageID_validReq_4_i+" ? "+WrInStageID_4_i+" : scaiev_execute_trans_id_o;");
    if (!WrInStageID_validResp_4_o.equals("0"))
      addLogic("assign WrInStageID_validResp_4_o = 1'b1;");
    addLogic("assign scaiev_execute_wrRD = " + WrRD_4_i + ";");
    addLogic("assign scaiev_execute_wrRD_valid = " + WrRD_validReq_4_i + " || " + WrPC_validReq_4_i +
             ";");

    if (ContainsOpInStage(BNode.WrRD_spawn, pseudostage_spawn)) {
      if (ContainsOpInStage(BNode.WrRD_spawn_allowed, pseudostage_spawn))
        addLogic("assign %s = 1'b1;".formatted(language.CreateNodeName(BNode.WrRD_spawn_allowed, pseudostage_spawn, "")));
      addLogic("assign scaiev_writeback_spawn_valid = %s;".formatted(language.CreateNodeName(BNode.WrRD_spawn_valid, pseudostage_spawn, "")));
      addLogic("assign scaiev_writeback_spawn_addr = %s;".formatted(language.CreateNodeName(BNode.WrRD_spawn_addr, pseudostage_spawn, "")));
      addLogic("assign scaiev_writeback_spawn_data = %s;".formatted(language.CreateNodeName(BNode.WrRD_spawn, pseudostage_spawn, "")));
      if (ContainsOpInStage(BNode.WrRD_spawn_validResp, pseudostage_spawn))
        addLogic("assign %s = 1'b1;".formatted(language.CreateNodeName(BNode.WrRD_spawn_validResp, pseudostage_spawn, "")));
    }
    else {
      addLogic("assign scaiev_writeback_spawn_valid = 1'b0;");
      addLogic("assign scaiev_writeback_spawn_addr = '0;");
      addLogic("assign scaiev_writeback_spawn_data = '0;");
    }

    if (ContainsOpInStage(BNode.RdCommitIDCount, core.GetRootStage())) {
      addDeclaration("logic [$clog2(CVA6Cfg.NrCommitPorts+1)-1:0] commit_trans_id_count;");
      addLogic("""
          always_comb begin
              commit_trans_id_count = '0;
              for (int i = 0; i < CVA6Cfg.NrCommitPorts; i=i+1)
                  commit_trans_id_count = commit_trans_id_count + scaiev_commit_trans_id_valid[i];
          end
          """);
      //Assign to interface
      addLogic("assign %s = commit_trans_id_count;".formatted(language.CreateNodeName(BNode.RdCommitIDCount, core.GetRootStage(), "")));
      //Assertion logic
      addLogic("""
          `ifndef SYNTHESIS
          always_ff @(posedge %1$s) begin
              if (!%2$s) begin
                  //Check some assumptions on how multiple commit ports behave in CVA6.
                  for (int i = 1; i < CVA6Cfg.NrCommitPorts; i=i+1) begin : commit_check_scope
                      if (scaiev_commit_trans_id_valid[i]) begin
                          if (!scaiev_commit_trans_id_valid[i-1])
                              $display("%%t ERROR: scaiev_glue - Commit %%d is valid but %%d is not", $time, i, i-1);
                          else if (scaiev_commit_trans_id[i] != scaiev_commit_trans_id[i-1]+$bits(scaiev_commit_trans_id[i])'(1))
                              $display("%%t ERROR: scaiev_glue - Commit %%d ID is %%d, expected %%d = 'Commit %%d ID'+1", $time,
                                       i, scaiev_commit_trans_id[i], scaiev_commit_trans_id[i-1] + 1, i-1);
                          else if (!scaiev_commit_drop[i] && scaiev_commit_drop[i-1])
                              $display("%%t ERROR: scaiev_glue - Commit %%d is dropped, but commit %%d is kept", $time,
                                       i-1, i);
                      end
                  end
              end
          end
          `endif
          """.formatted(language.clk, language.reset));
    }
    if (ContainsOpInStage(BNode.RdCommitID, core.GetRootStage()))
      addLogic("assign %s = scaiev_commit_trans_id[0];".formatted(language.CreateNodeName(BNode.RdCommitID, core.GetRootStage(), "")));
    if (ContainsOpInStage(BNode.RdCommitFlushID, core.GetRootStage()) || ContainsOpInStage(BNode.RdCommitFlushIDCount, core.GetRootStage())) {
      addDeclaration("logic [$clog2(CVA6Cfg.NrCommitPorts+1)-1:0] commit_flush_id_count;");
      addDeclaration("logic [CVA6Cfg.TRANS_ID_BITS-1:0] commit_flush_id;");
      addLogic("""
          always_comb begin
              commit_flush_id_count = '0;
              commit_flush_id = scaiev_commit_trans_id[0];
              for (int i = 0; i < CVA6Cfg.NrCommitPorts; i=i+1)
                  commit_flush_id_count = commit_flush_id_count + (scaiev_commit_trans_id_valid[i] && scaiev_commit_drop[i]);
              for (int i = CVA6Cfg.NrCommitPorts-1; i >= 0; i=i-1)
                  commit_flush_id = (scaiev_commit_trans_id_valid[i] && scaiev_commit_drop[i]) ? scaiev_commit_trans_id[i] : commit_flush_id;
          end
          """);
      if (ContainsOpInStage(BNode.RdCommitFlushIDCount, core.GetRootStage()))
        addLogic("assign %s = commit_flush_id_count;".formatted(language.CreateNodeName(BNode.RdCommitFlushIDCount, core.GetRootStage(), "")));
      if (ContainsOpInStage(BNode.RdCommitFlushID, core.GetRootStage()))
        addLogic("assign %s = commit_flush_id;".formatted(language.CreateNodeName(BNode.RdCommitFlushID, core.GetRootStage(), "")));
    }
    if (ContainsOpInStage(BNode.RdCommitFlushAll, core.GetRootStage()))
      addLogic("assign %s = scaiev_scoreboard_isFlushing;".formatted(language.CreateNodeName(BNode.RdCommitFlushAll, core.GetRootStage(), "")));
    if (ContainsOpInStage(BNode.RdCommitFlushAllID, core.GetRootStage()))
      addLogic("assign %s = '0;".formatted(language.CreateNodeName(BNode.RdCommitFlushAllID, core.GetRootStage(), "")));

    String anyWritebackExpr = makeIValidExpression(getISAXesWithOpInStage(BNode.WrRD, stage_execute)
                                                       .filter(instr -> !instr.HasNoOp())
                                                       .map(instr -> instr.GetName())
                                                       .toList(),
                                                   stage_execute);
    addLogic("assign scaiev_execute_hasWriteback = " + anyWritebackExpr + ";");
  }

  private void IntegrateISAX_Branch() {
    ArrayList<String> statement_lines = new ArrayList<>();
    HashSet<String> allISAXes_Branch = new HashSet<String>();
    allISAXes_Branch.addAll(op_stage_instr.getOrDefault(BNode.BranchTaken, new HashMap<>()).getOrDefault(stage_execute, new HashSet<>()));
    if (ContainsOpInStage(BNode.BranchTaken, stage_execute)) {

      statement_lines.add("always_comb begin");
      statement_lines.add(tab + "unique case (1'b1)");
      final String indent = tab + tab;
      for (String branchISAX : allISAXes_Branch) {
        String branchTaken_node_name = language.CreateNodeName(BNode.BranchTaken, stage_execute, branchISAX);
        statement_lines.add(indent + "(" + branchTaken_node_name + "): begin");
        statement_lines.add(indent + tab + "scaiev_execute_branch_isTaken = 1'b1;");
        statement_lines.add(indent + "end");
      }
      statement_lines.add(indent + "default: begin");
      statement_lines.add(indent + tab + "scaiev_execute_branch_isTaken = 1'b0;");
      statement_lines.add(indent + "end");
      statement_lines.add(tab + "endcase");
      statement_lines.add("end");

    } else {
      statement_lines.add("assign scaiev_execute_branch_isTaken = 1'b0;");
    }
    if (allISAXes_Branch.size() != 0) {
      String toAddRealign =
          "assign scaiev_realign_isBranch = " + language.CreateAllEncoding(allISAXes_Branch, ISAXes, "scaiev_realign_rdInstr") + ";";
      addLogic(toAddRealign);
    }
    else {
      addLogic("assign scaiev_realign_isBranch = 1'b0;");
    }

    if (allISAXes_Branch.size() != 0) {

      String toAddDecode =
          "assign scaiev_decode_isBranch = " + language.CreateAllEncoding(allISAXes_Branch, ISAXes, "scaiev_decode_rdInstr") + ";";
      String toAddExecute =
          "assign scaiev_execute_isBranch = " + language.CreateAllEncoding(allISAXes_Branch, ISAXes, "scaiev_execute_rdInstr") + ";";

      addLogic(toAddDecode);
      addLogic(toAddExecute);
    }
    else {
      addLogic("assign scaiev_decode_isBranch = 1'b0;");
      addLogic("assign scaiev_execute_isBranch = 1'b0;");
    }
    addLogic(String.join("\n", statement_lines));
  }

  private void IntegrateISAX_Jump() {
    ArrayList<String> statement_lines = new ArrayList<>();
    if (ContainsOpInStage(BNode.WrJump, stage_execute) || ContainsOpInStage(BNode.WrJump, pseudostage_spawn)) {

      final String indent = tab + tab;

      HashSet<String> allISAXes_WrJump = new HashSet<String>();
      HashSet<String> allISAXes_WrJumpSpawn = new HashSet<String>();
      allISAXes_WrJump.addAll(op_stage_instr.getOrDefault(BNode.WrJump, new HashMap<>()).getOrDefault(4, new HashSet<>()));
      allISAXes_WrJumpSpawn.addAll(op_stage_instr.getOrDefault(BNode.WrJump, new HashMap<>()).getOrDefault(5, new HashSet<>()));

      for (String s : allISAXes_WrJump) {
        //addIOGlue("WrJump_" + s + "_4", BNode.WrJump.size, true);
      }
      for (String s : allISAXes_WrJumpSpawn) {
        //addIOGlue("WrJump_" + s + "_5", BNode.WrJump.size, true);
        //addIOGlue("WrJumpValid_" + s + "_5", 1, true);
        //addSignalTop("WrJumpValid_" + s + "_5", 1);
      }

      statement_lines.add("always_comb begin");

      statement_lines.add(tab + "unique case (1'b1)");
      String rdvalid_node_name = language.CreateNodeName(BNode.WrRD_valid, stage_execute, "");
      String rd_node_name = language.CreateNodeName(BNode.WrRD, stage_execute, "");
      for (String s : allISAXes_WrJump) {
        statement_lines.add(indent + "(RdIValid_" + s + "_4_o ): begin");
        statement_lines.add(indent + tab + "scaiev_execute_isJump = 1'b1;");
        statement_lines.add(indent + tab + "scaiev_execute_jumpAddress = WrJump_" + s + "_4_i;");
        statement_lines.add(indent + "end");
      }
      for (String s : allISAXes_WrJumpSpawn) {
        statement_lines.add(indent + "(WrJumpValid_" + s + "_5_i ): begin");
        statement_lines.add(indent + tab + "scaiev_execute_isJump = 1'b1;");
        statement_lines.add(indent + tab + "scaiev_execute_jumpAddress = WrJump_" + s + "_5_i;");
        statement_lines.add(indent + "end");
      }

      statement_lines.add(indent + "default: begin");
      statement_lines.add(indent + tab + "scaiev_execute_isJump = 1'b0;");
      statement_lines.add(indent + tab + "scaiev_execute_jumpAddress = 'X;");
      statement_lines.add(indent + "end");
      statement_lines.add(tab + "endcase");
      statement_lines.add("end");

      allISAXes_WrJump.addAll(allISAXes_WrJumpSpawn);
      if (allISAXes_WrJump.size() != 0) {
        String toAddRealign =
            "assign scaiev_realign_isJump = " + language.CreateAllEncoding(allISAXes_WrJump, ISAXes, "scaiev_realign_rdInstr") + ";";
        addLogic(toAddRealign);
      }
      else {
        statement_lines.add("assign scaiev_realign_isJump = 1'b0;");
      }
    } else {
      statement_lines.add("assign scaiev_realign_isJump = 1'b0;");
      statement_lines.add("assign scaiev_execute_isJump = 1'b0;");
      statement_lines.add("assign scaiev_execute_jumpAddress = 'X;");
    }
    addLogic(String.join("\n", statement_lines));
  }

  private void IntegrateISAX_Mem() {
    addDeclaration("logic " + signame_execute_stall_mem + ";");

    String memRequestLogic = """
        always_comb begin
            scaiev_execute_memAddr_valid = 1'b0;
            scaiev_execute_memAddr = 0;
            scaiev_execute_memSize = 0;
            scaiev_execute_mem_has_trans_id = 1'b1;
            scaiev_execute_rdMem_valid = 1'b0;
            scaiev_execute_wrMem_valid = 1'b0;
            scaiev_execute_wrMem = 0;
            scaiev_issue_mem_stall = 1'b0;
        """;
    if (ContainsOpInStage(BNode.RdMem_spawn, pseudostage_spawn) || ContainsOpInStage(BNode.WrMem_spawn, pseudostage_spawn)) {
      memRequestLogic += """
              set_mem_read_spawn_pending = 1'b0;
              set_mem_write_spawn_pending = 1'b0;
          """;
    }
    String spawnMemRequestCond = "1'b0";
    if (ContainsOpInStage(BNode.RdMem_spawn, pseudostage_spawn) || ContainsOpInStage(BNode.WrMem_spawn, pseudostage_spawn)) {
      addDeclaration("logic spawn_read_mem_pending_reg;");
      addDeclaration("logic set_mem_read_spawn_pending;");
      if (ContainsOpInStage(BNode.RdMem_spawn, pseudostage_spawn)) {
        addLogic("""
              always_ff @(posedge clk) begin
                  if (rst)
                      spawn_read_mem_pending_reg <= 1'b0;
                  else if (scaiev_execute_rdMem_result_valid && !scaiev_execute_rdMem_result_has_trans_id)
                      spawn_read_mem_pending_reg <= 1'b0;
                  else if (set_mem_read_spawn_pending)
                      spawn_read_mem_pending_reg <= 1'b1;
              end""");
      }
      else {
        addLogic("assign spawn_read_mem_pending_reg = 1'b0;");
      }
      spawnMemRequestCond = Stream.of(BNode.RdMem_spawn_validReq, BNode.WrMem_spawn_validReq)
          .filter(node->ContainsOpInStage(node, pseudostage_spawn))
          .map(node->language.CreateNodeName(node, pseudostage_spawn, "")).distinct()
          .reduce((a,b)->a+" || "+b)
          .orElse("0");
      // Assert scaiev_issue_mem_stall during (Rd|Wr)Mem_spawn_validReq
      // -> only need to stall if scaiev_execute_lsuvalid
      memRequestLogic += "    if (scaiev_execute_lsuvalid && (%s)) scaiev_issue_mem_stall = 1'b1;\n".formatted(spawnMemRequestCond);

      // Deassert spawnAllowed while |lsu_valid_i in ex_stage or !scaiev_execute_mem_ready
      String rd_spawn_allowed = language.CreateNodeName(BNode.RdMem_spawn_allowed, pseudostage_spawn, "");
      String wr_spawn_allowed = language.CreateNodeName(BNode.WrMem_spawn_allowed, pseudostage_spawn, "");
      if (!rd_spawn_allowed.equals(wr_spawn_allowed) || !ContainsOpInStage(BNode.WrMem_spawn, pseudostage_spawn))
        this.addLogic("assign " + rd_spawn_allowed + "= !scaiev_execute_lsuvalid && scaiev_execute_mem_ready;");
      if (ContainsOpInStage(BNode.WrMem_spawn, pseudostage_spawn)) {
        this.addLogic("assign " + wr_spawn_allowed + " = !scaiev_execute_lsuvalid && scaiev_execute_mem_ready "
                     + "&& (!spawn_read_mem_pending_reg || scaiev_execute_rdMem_result_valid && !scaiev_execute_rdMem_result_has_trans_id);");
      }
      if (rd_spawn_allowed.equals(wr_spawn_allowed) && ContainsOpInStage(BNode.RdMem_spawn, pseudostage_spawn) && ContainsOpInStage(BNode.WrMem_spawn, pseudostage_spawn)) {
        //FIXME, RdMem_spawn_allowed and WrMem_spawn_allowed should be separate wires
        logger.warn("CVA6: Disabled overlapping RdMem_spawn due to presence of WrMem_spawn");
      }
    }

    if (ContainsOpInStage(BNode.RdMem, stage_execute) || ContainsOpInStage(BNode.WrMem, stage_execute)) {
      String RdMem_validReq_4_i = sigor0("RdMem_validReq_4_i");
      if (ContainsOpInStage(BNode.RdMem, stage_execute)) {
        assert (!RdMem_validReq_4_i.equals("0"));
        addDeclaration("reg read_mem_pending_reg;");
        addDeclaration("reg read_mem_waiting_reg;");
        addDeclaration("reg read_mem_potential_new_reg;");
        addLogic("""
              always_ff @(posedge clk) begin
                  if (rst || scaiev_execute_isFlushing) begin
                      read_mem_waiting_reg <= 1'b0;
                      read_mem_pending_reg <= 1'b0;
                  end
                  else if (scaiev_execute_rdMem_result_valid && scaiev_execute_rdMem_result_has_trans_id) begin
                      read_mem_waiting_reg <= 1'b0;
                      read_mem_pending_reg <= 1'b0;
                  end
                  else begin
                      if (%1$s && !(%2$s))
                          read_mem_pending_reg <= 1'b1;
                      if (read_mem_potential_new_reg && scaiev_execute_isNew)
                          read_mem_waiting_reg <= 1'b1;
                  end
              end
            """.formatted(RdMem_validReq_4_i, spawnMemRequestCond));
        String anyRdmemIssue = makeIValidExpression(getISAXesWithOpInStage(BNode.RdMem, stage_execute)
                                                        .filter(instr -> !instr.HasNoOp())
                                                        .map(instr -> instr.GetName())
                                                        .toList(), stage_issue);
        addLogic("always_ff @(posedge clk) read_mem_potential_new_reg <= (%s) && %s;".formatted(anyRdmemIssue, signame_to_scal_pipeinto_executesv1));
        addLogic("assign " + signame_execute_stall_mem + " = "
                 + "read_mem_pending_reg && !(scaiev_execute_rdMem_result_valid && scaiev_execute_rdMem_result_has_trans_id)"
                 + "|| read_mem_potential_new_reg && scaiev_execute_isNew && " + RdMem_validReq_4_i + ";\n");
      } else {
        addLogic("assign " + signame_execute_stall_mem + " = 1'b0;\n");
      }
      memRequestLogic += "    if (read_mem_potential_new_reg && scaiev_execute_isNew && !%s || read_mem_waiting_reg) scaiev_issue_mem_stall = 1'b1;\n"
                         .formatted(RdMem_validReq_4_i);

      String RdMem_addr_valid_4_i = sigor0("RdMem_addr_valid_4_i");
      String RdMem_addr_4_i = sigor0("RdMem_addr_4_i");
      String RdMem_4_o = sigor0("RdMem_4_o");
      String RdMem_instrID_4_i = sigor0(language.CreateNodeName(BNode.RdMem_instrID, stage_execute, ""));
      assert(RdMem_4_o.equals("0") == !ContainsOpInStage(BNode.RdMem, stage_execute));
      if (ContainsOpInStage(BNode.RdMem, stage_execute)) {
        addLogic("assign " + RdMem_4_o + " = scaiev_execute_rdMem_result;");
      }
      if (ContainsOpInStage(BNode.RdMem_validResp, stage_execute)) {
        assert(!sigor0("RdMem_validResp_4_o").equals("0"));
        addLogic("assign %s = scaiev_execute_rdMem_result_valid && scaiev_execute_rdMem_result_has_trans_id;"
                 .formatted(language.CreateNodeName(BNode.RdMem_validResp, stage_execute, "")));
      }
      String RdMem_size_4_i = sigor0("RdMem_size_4_i");

      String WrMem_validReq_4_i = sigor0("WrMem_validReq_4_i");
      String WrMem_validResp_4_o = sigor0("WrMem_validResp_4_o");
      if (!WrMem_validResp_4_o.equals("0")) {
        assert(ContainsOpInStage(BNode.WrMem_validResp, stage_execute));
        addLogic("assign %s = %s && !(%s);".formatted(WrMem_validResp_4_o, WrMem_validReq_4_i, spawnMemRequestCond));
      }
      String WrMem_4_i = sigor0("WrMem_4_i");
      String WrMem_addr_valid_4_i = sigor0("WrMem_addr_valid_4_i");
      String WrMem_addr_4_i = sigor0("WrMem_addr_4_i");
      String WrMem_size_4_i = sigor0("WrMem_size_4_i");
      String WrMem_instrID_4_i = sigor0(language.CreateNodeName(BNode.WrMem_instrID, stage_execute, ""));
      assert(WrMem_4_i.equals("0") == !ContainsOpInStage(BNode.WrMem, stage_execute));

      if (!RdMem_addr_valid_4_i.equals("0")) {
        addLogic("""
                assert property (@(posedge clk) disable iff (rst) %s == %s)
                else $fatal(1, "SCAIE-V read request valid != address valid");
                """.formatted(RdMem_validReq_4_i, RdMem_addr_valid_4_i));
      }
      if (!WrMem_addr_valid_4_i.equals("0")) {
        addLogic("""
                assert property (@(posedge clk) disable iff (rst) %s == %s)
                else $fatal(1, "SCAIE-V write request valid != address valid");
                """.formatted(WrMem_validReq_4_i, WrMem_addr_valid_4_i));
      }
      //memRequestLogic += "scaiev_execute_memAddr_valid = %s ? %s : %s;\n".formatted(RdMem_validReq_4_i, RdMem_addr_valid_4_i, WrMem_addr_valid_4_i);
      memRequestLogic += "    scaiev_execute_memAddr_valid = %s && !read_mem_pending_reg || %s;\n".formatted(RdMem_addr_valid_4_i, WrMem_addr_valid_4_i);
      memRequestLogic += "    scaiev_execute_memAddr = %s ? %s : %s;\n".formatted(RdMem_addr_valid_4_i, RdMem_addr_4_i, WrMem_addr_4_i);
      memRequestLogic += "    scaiev_execute_memSize = %s ? %s : %s;\n".formatted(RdMem_validReq_4_i, RdMem_size_4_i, WrMem_size_4_i);
      memRequestLogic += "    scaiev_execute_mem_has_trans_id = 1'b1;\n";
      memRequestLogic += "    scaiev_execute_mem_trans_id = %s ? %s : %s;\n".formatted(RdMem_validReq_4_i, RdMem_instrID_4_i, WrMem_instrID_4_i);
      if (ContainsOpInStage(BNode.RdMem, stage_execute))
        memRequestLogic += "    scaiev_execute_rdMem_valid = %s && !read_mem_pending_reg;\n".formatted(RdMem_validReq_4_i);
      else
        memRequestLogic += "    scaiev_execute_rdMem_valid = 1'b0;\n";
      memRequestLogic += "    scaiev_execute_wrMem_valid = %s;\n".formatted(WrMem_validReq_4_i);
      memRequestLogic += "    scaiev_execute_wrMem = %s;\n".formatted(WrMem_4_i.equals("0") ? "'X" : WrMem_4_i);
    } else {
      addLogic("assign " + signame_execute_stall_mem + " = 1'b0;\n");
    }
    if (ContainsOpInStage(BNode.RdMem_spawn, pseudostage_spawn) || ContainsOpInStage(BNode.WrMem_spawn, pseudostage_spawn)) {
      String spawn_addr = language.CreateNodeName(BNode.RdMem_spawn_addr, pseudostage_spawn, "");
      String spawn_size = language.CreateNodeName(BNode.RdMem_spawn_size, pseudostage_spawn, "");
      String spawn_val = language.CreateNodeName(BNode.RdMem_spawn, pseudostage_spawn, "");
      String spawn_valid_resp = language.CreateNodeName(BNode.RdMem_spawn_validResp, pseudostage_spawn, "");
      assert(language.CreateNodeName(BNode.WrMem_spawn_validResp, pseudostage_spawn, "").equals(spawn_valid_resp));
      String spawn_is_write = language.CreateNodeName(BNode.RdMem_spawn_write, pseudostage_spawn, "");
      String spawn_wrval = language.CreateNodeName(BNode.WrMem_spawn, pseudostage_spawn, "");
      addDeclaration("logic set_mem_write_spawn_pending;");
      memRequestLogic += """
              if (%1$s) begin
                  scaiev_execute_memAddr_valid = !scaiev_execute_lsuvalid;
                  scaiev_execute_memAddr = %2$s;
                  scaiev_execute_memSize = %3$s;
                  scaiev_execute_mem_has_trans_id = 1'b0;
                  scaiev_execute_rdMem_valid = !scaiev_execute_lsuvalid && !%4$s;
                  scaiev_execute_wrMem_valid = !scaiev_execute_lsuvalid && %4$s;
                  set_mem_read_spawn_pending = !scaiev_execute_lsuvalid && !%4$s;
                  set_mem_write_spawn_pending = !scaiev_execute_lsuvalid && %4$s;
                  scaiev_execute_wrMem = %5$s;
              end
          """.formatted(spawnMemRequestCond, //1
                        spawn_addr, spawn_size, spawn_is_write, //2,3,4
                        ContainsOpInStage(BNode.WrMem_spawn, pseudostage_spawn) ? spawn_wrval : "'X" //5
                       );
      String validRespCond = "";
      if (ContainsOpInStage(BNode.RdMem_spawn, pseudostage_spawn)) {
        addLogic("assign " + spawn_val + " = scaiev_execute_rdMem_result;");
        //Contained in RdMem_spawn_allowed: !scaiev_execute_lsuvalid && scaiev_execute_mem_ready
        validRespCond = "scaiev_execute_rdMem_result_valid && !scaiev_execute_rdMem_result_has_trans_id";
      }
      if (ContainsOpInStage(BNode.WrMem_spawn, pseudostage_spawn)) {
        addLogic("""
            always_ff @(posedge clk) begin
                mem_spawn_validResp_r <= set_mem_write_spawn_pending;
            end""");
        validRespCond += (validRespCond.isEmpty() ? "" : " || ") + "mem_spawn_validResp_r";
      }
      addLogic("assign " + spawn_valid_resp + " = " + validRespCond + ";");
    }
    memRequestLogic += "end\n";
    addLogic(memRequestLogic);
  }

  private void IntegrateISAX_WrPC() {
    ArrayList<String> statement_lines = new ArrayList<>();
    boolean has_previous_case = false;
    addDeclaration("logic " + signame_issue_stall_before_cf + ";");
    statement_lines.add("always_comb begin");
    addLogic("assign scaiev_fetch_ignoreReplay = 0;"); //not needed
    if (ContainsOpInStage(BNode.WrPC, stage_fetch)) {
      //Apply the original PC in the next cycle and hold until the fetch runs through.
      //-> The cycle delay gives SCAL time to flush its logic.
      //-> The hold (until !stall) ensures RdPC remains stable, as SCAL samples at !stall.
      String origPCValid = language.CreateNodeName(BNode.RdOrigPC_valid.NodeNegInput(), stage_realign, "");
      String origPC = language.CreateNodeName(BNode.RdOrigPC.NodeNegInput(), stage_realign, "");
      //addLogic("assign scaiev_fetch_ignoreReplay = %s;".formatted(origPCValid)); //not needed
      addDeclaration("logic scaiev_fetch_isReplaying_r;");
      addDeclaration("logic [%d-1:0] scaiev_fetch_isReplaying_origPC_r;".formatted(BNode.RdOrigPC.size));
      addDeclaration("logic scaiev_fetch_isReplaying_origPCValid_r;");
      addLogic("""
          always_ff @(posedge clk) begin
              scaiev_fetch_isReplaying_r <= scaiev_fetch_isReplaying;
              scaiev_fetch_isReplaying_origPC_r <= %s;
              scaiev_fetch_isReplaying_origPCValid_r <= %s;
              if ((scaiev_fetch_isStalling | scaiev_fetch_stall) && !scaiev_fetch_isReplaying && !scaiev_fetch_isFlushing) begin
                  scaiev_fetch_isReplaying_r <= scaiev_fetch_isReplaying_r;
                  scaiev_fetch_isReplaying_origPC_r <= scaiev_fetch_isReplaying_origPC_r;
                  scaiev_fetch_isReplaying_origPCValid_r <= scaiev_fetch_isReplaying_origPCValid_r;
              end
          end
          """.formatted(origPC, origPCValid));

      statement_lines.add(tab + "if (scaiev_fetch_isReplaying_r && %s) begin".formatted("scaiev_fetch_isReplaying_origPCValid_r"));
      statement_lines.add(tab + tab + "scaiev_fetch_wrPCValid = 1'b1;");
      statement_lines.add(tab + tab + "scaiev_fetch_wrPC = %s;".formatted("scaiev_fetch_isReplaying_origPC_r"));
      statement_lines.add(tab + "end");
    }
    for (int stagePos = stagePos_execute; stagePos >= stagePos_fetch; --stagePos) {
      PipelineStage stage = stages[stagePos];
      if (!ContainsOpInStage(BNode.WrPC, stage)) {
        if (stagePos == stagePos_execute)
          addLogic("assign " + signame_issue_stall_before_cf + " = 0;");
        continue;
      }
      String valid_node = language.CreateNodeName(BNode.WrPC_valid, stage, "");
      String pc_node = language.CreateNodeName(BNode.WrPC, stage, "");

      String ivalid_expr = "(" +
                          getISAXesWithOpInStage(BNode.WrPC, stage)
                              .filter(isax -> !isax.HasNoOp())
                              .map(isax -> language.CreateNodeName(stage == stage_execute ? BNode.RdAnyValid : BNode.RdIValid,
                                                                   stage, isax.GetName()))
                              .reduce((a, b) -> a + " || " + b)
                              .orElse("1'b0") +
                          ")";
      if (stagePos == stagePos_execute) {
        // Stall issue if Execute has a control flow ISAX that isn't finishing.
        addLogic("assign " + signame_issue_stall_before_cf + " = (scaiev_execute_isStalling || scaiev_execute_stall) && " + ivalid_expr +
                 ";");
      }
      if (stagePos == stagePos_fetch) {
        //fetch_wrPC also updates the next PC register
        //-> Ignore WrPC_fetch if the fetch stage is stalling,
        //   so the next PC register remains intact
        valid_node = valid_node + " && !scaiev_fetch_isStalling && !scaiev_fetch_stall";
      }

      statement_lines.add(tab + (has_previous_case ? "else if" : "if") + " (" + valid_node + ") begin");
      statement_lines.add(tab + tab + "scaiev_fetch_wrPCValid = 1'b1;");
      statement_lines.add(tab + tab + "scaiev_fetch_wrPC = " + pc_node + ";");
      statement_lines.add(tab + "end");
      has_previous_case = true;
    }
    if (has_previous_case)
      statement_lines.add(tab + "else begin");
    statement_lines.add(tab + tab + "scaiev_fetch_wrPCValid = 1'b0;");
    statement_lines.add(tab + tab + "scaiev_fetch_wrPC = 'X;");
    if (has_previous_case)
      statement_lines.add(tab + "end");

    statement_lines.add("end");

    addLogic(String.join("\n", statement_lines));
    
    addLogic("assign scaiev_decode_pcOverride = %s;\n"
        .formatted(language.CreateNodeName(node_RdZOLOverride, stage_decode, "")));
    addLogic("assign scaiev_decode_pcOverride_valid = %s;\n"
        .formatted(language.CreateNodeName(node_RdZOLOverride_valid, stage_decode, "")));
  }

  private void Configcva6() {
    this.PopulateNodesMap();
    PutModule(pathcva6.resolve("cva6_ariane_wrapper.sv"), "cva6_ariane_wrapper", pathcva6.resolve("cva6_ariane_wrapper.sv"), "",
              "cva6_ariane_wrapper");
    PutModule(pathcva6.resolve("cva6_glue_wrapper.sv"), "cva6_glue_wrapper", pathcva6.resolve("cva6_glue_wrapper.sv"),
              "cva6_ariane_wrapper", "cva6_glue_wrapper");
    PutModule(pathcva6.resolve("cva6.sv"), "cva6", pathcva6.resolve("cva6.sv"), "cva6_glue_wrapper", "cva6");
    PutModule(pathcva6.resolve("scaiev_glue.sv"), "scaiev_glue", pathcva6.resolve("scaiev_glue.sv"), "cva6_glue_wrapper", "scaiev_glue");

    this.configFlags.clear();

    this.PutNode("logic", "", "scaiev_glue", BNode.WrPC, stage_fetch);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrPC_valid, stage_fetch);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrPC, stage_issue);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrPC_valid, stage_issue);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrPC, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrPC_valid, stage_execute);

    this.PutNode("wire", "", "scaiev_glue", node_RdZOLOverride, stage_decode);
    this.PutNode("wire", "", "scaiev_glue", node_RdZOLOverride_valid, stage_decode);

    if (ContainsOpInStage(BNode.WrPC, stage_fetch)) {
      this.PutNode("logic", "(scaiev_fetch_isReplaying_r && %s) ? %s : scaiev_fetch_PC"
                            .formatted("scaiev_fetch_isReplaying_origPCValid_r", "scaiev_fetch_isReplaying_origPC_r"),
                   "scaiev_glue", BNode.RdPC, stage_fetch);
    }
    else {
      this.PutNode("logic", "scaiev_fetch_PC", "scaiev_glue", BNode.RdPC, stage_fetch);
    }
    this.PutNode("logic", "scaiev_realign_PC", "scaiev_glue", BNode.RdPC, stage_realign);
    this.PutNode("logic", "scaiev_issue_PC", "scaiev_glue", BNode.RdPC, stage_issue);
    this.PutNode("logic", "scaiev_execute_PC", "scaiev_glue", BNode.RdPC, stage_execute);

    this.PutNode("logic", "scaiev_fetch_reqID", "scaiev_glue", node_RdFetchID, stage_fetch);
    this.PutNode("logic", "scaiev_realign_reqID", "scaiev_glue", node_RdFetchID, stage_realign);
    this.PutNode("logic", "scaiev_fetch_reqID_flushFrom", "scaiev_glue", node_RdFetchFlushFromID, stage_fetch);
    this.PutNode("logic", "scaiev_fetch_reqID_flushCount", "scaiev_glue", node_RdFetchFlushCount, stage_fetch);
    this.PutNode("logic", "scaiev_realign_instrqueueID", "scaiev_glue", node_RdIQueueID, stage_realign);
    this.PutNode("logic", "{%d{scaiev_decode_isFlushing | scaiev_decode_flush}}".formatted(node_RdIQueueFlushMask.size), "scaiev_glue", node_RdIQueueFlushMask, stage_realign);
    this.PutNode("logic", "scaiev_decode_instrqueueID", "scaiev_glue", node_RdIQueueID, stage_decode);

    this.PutNode("logic", "", "scaiev_glue", BNode.RdCommitIDCount, core.GetRootStage());
    this.PutNode("logic", "", "scaiev_glue", BNode.RdCommitID, core.GetRootStage());
    this.PutNode("logic", "", "scaiev_glue", BNode.RdCommitFlushIDCount, core.GetRootStage());
    this.PutNode("logic", "", "scaiev_glue", BNode.RdCommitFlushID, core.GetRootStage());
    this.PutNode("logic", "", "scaiev_glue", BNode.RdCommitFlushAll, core.GetRootStage());
    this.PutNode("logic", "", "scaiev_glue", BNode.RdCommitFlushAllID, core.GetRootStage());

    this.PutNode("logic", "", "scaiev_glue", BNode.RdInstr_RS, stage_decode);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdInstr_RD, stage_decode);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdInstr, stage_decode);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdInstr, stage_issue);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdInstr, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", BNode.RdIValid, stage_decode);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdIValid, stage_issue);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdIValid, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdAnyValid, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", BNode.RdOrigPC, stage_realign);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdOrigPC_valid, stage_realign);

    this.PutNode("logic", "", "scaiev_glue", BNode.RdRS1, stage_issue);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdRS1, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", BNode.RdRS2, stage_issue);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdRS2, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", BNode.RdInStageValid, stage_fetch);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdInStageValid, stage_realign);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdInStageValid, stage_decode);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdInStageValid, stage_issue);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdInStageValid, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", BNode.RdIssueID, stage_issue);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdIssueFlushID, stage_issue);

    this.PutNode("logic", "", "scaiev_glue", BNode.RdInStageID, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrDeqInstr, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrInStageID, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrInStageID_valid, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrInStageID_validResp, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", BNode.RdPipeInto, stage_issue);

    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem, stage_execute);
    // this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_validResp,stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_validReq, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_validResp, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_validReq, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_validResp, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_instrID, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_addr, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_size, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_addr_valid, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_instrID, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_addr, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_size, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrMem_addr_valid, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", BNode.RdStall, stage_fetch);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdStall, stage_realign);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdStall, stage_decode);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdStall, stage_issue);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdStall, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", BNode.WrStall, stage_fetch);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrStall, stage_realign);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrStall, stage_decode);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrStall, stage_issue);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrStall, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", BNode.RdFlush, stage_fetch);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdFlush, stage_realign);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdFlush, stage_decode);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdFlush, stage_issue);
    this.PutNode("logic", "", "scaiev_glue", BNode.RdFlush, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", BNode.WrFlush, stage_decode);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrFlush, stage_issue);

    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD_validData, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD_valid, stage_execute);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD, stage_execute);

    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD_spawn, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD_spawn_valid, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD_spawn_validResp, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD_spawn_addr, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrRD_spawn_allowed, pseudostage_spawn);

    this.PutNode("logic", "", "scaiev_glue", BNode.RdMem_spawn, pseudostage_spawn);
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
    this.PutNode("logic", "1", "scaiev_glue", BNode.ISAX_spawnAllowed, stage_issue);

    this.PutNode("logic", "", "scaiev_glue", BNode.WrCommit_spawn, pseudostage_spawn);
    this.PutNode("logic", "", "scaiev_glue", BNode.WrCommit_spawn_validReq, pseudostage_spawn);
    this.PutNode("logic", "1'b1", "scaiev_glue", BNode.WrCommit_spawn_validResp, pseudostage_spawn);
  }
}
