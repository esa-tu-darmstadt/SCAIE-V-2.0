package scaiev.scal.strategy.decoupled;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
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
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistry;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.SCALUtil;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.util.Log2;
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
  private static SCAIEVNode makeNodeHazardWriteAddr(SCAIEVNode spawnNode, int addrWidth) {
    SCAIEVNode ret = new SCAIEVNode("HazardWriteAddr_" + spawnNode, addrWidth, false);
    ret.noInterfToISAX = true;
    ret.tags.add(NodeTypeTag.accumulatesUntilCommit);
    return ret;
  }
  private static SCAIEVNode makeNodeHazardWriteAddrValid(SCAIEVNode hazardWriteAddr) {
    SCAIEVNode ret = new SCAIEVNode(hazardWriteAddr, AdjacentNode.validReq, 1, false, false);
    ret.noInterfToISAX = true;
    return ret;
  }
  private static final Purpose DHInternalPurpose = new Purpose("DHInternal", true, Optional.empty(), List.of());

  private void CreateUserCancelSpawn(NodeLogicBlock logicBlock, NodeRegistryRO registry, SCAIEVNode spawnNode, PipelineStage spawnStage) {
    SCAIEVNode nodeValid = bNodes.GetAdjSCAIEVNode(spawnNode, AdjacentNode.validReq).get();
    Optional<SCAIEVNode> nodeCancel = bNodes.GetAdjSCAIEVNode(spawnNode, AdjacentNode.cancelReq);
    SCAIEVNode nodeAddr = bNodes.GetAdjSCAIEVNode(spawnNode, AdjacentNode.addr).get();

    SCAIEVNode nodeCancelValid = makeNodeCancelValid(spawnNode);
    SCAIEVNode nodeCancelAddr = makeNodeCancelAddr(spawnNode);
    nodeCancelAddr.size = nodeAddr.size;

    String cancelValid = language.CreateNodeName(nodeCancelValid.name, spawnStage, "", false);
    String cancelAddr = language.CreateNodeName(nodeCancelAddr.name, spawnStage, "", false);
    String declareLogicValid = "wire " + cancelValid + ";\n";
    String declareLogicAddr = "logic [%d-1:0] %s;\n".formatted(nodeAddr.size, cancelAddr);
    String cancelLogicValid = "assign " + cancelValid + " = 0"; // default
    String cancelLogicAddr = cancelAddr + " = 0;\n";

    for (String ISAX : this.op_stage_instr.get(spawnNode).get(spawnStage)) {
      if (nodeCancel.isPresent()) {
        String cancelExpr = registry.lookupRequired(new NodeInstanceDesc.Key(nodeCancel.get(), spawnStage, ISAX))
                                    .getExpressionWithParens();
        String addrExpr = registry.lookupRequired(new NodeInstanceDesc.Key(nodeAddr, spawnStage, ISAX))
                                  .getExpression();
        cancelLogicValid += " || " + cancelExpr + "";
        if (spawnNode.elements > 1)
          cancelLogicAddr += "if(" + cancelExpr + ") " + cancelAddr + " = " + addrExpr + ";\n";
      }
      else if (allISAXes.get(ISAX).GetFirstNode(spawnNode).HasAdjSig(AdjacentNode.validReq)) {
        var validExprResult =
            registry.lookupExpressionRequired(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.REGULAR, nodeValid, spawnStage, ISAX));
        var validExprISAX =
            registry.lookupExpressionRequired(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.PIPEDIN, nodeValid, spawnStage, ISAX));
        var addrExprISAX =
            registry.lookupExpressionRequired(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.PIPEDIN, nodeAddr, spawnStage, ISAX));
        cancelLogicValid += " || (" + validExprISAX + " && ~" + validExprResult + ")";
        if (spawnNode.elements > 1)
          cancelLogicAddr += "if(" + validExprISAX + ")\n    " + cancelAddr + " = " + addrExprISAX + ";\n";
        // cancelLogicValid += " || "+ language.CreateNodeName(nodeValid, spawnStage, ISAX)+ShiftmoduleSuffix+" && ~"+
        // language.CreateNodeName(nodeValid, spawnStage,ISAX); if(node.elements>1)     cancelLogicAddr += "if("+
        // language.CreateNodeName(nodeValid, spawnStage, ISAX)+ShiftmoduleSuffix+") "+cancelAddr+" = "+language.CreateNodeName(nodeAddr,
        // spawnStage, ISAX)+";\n";
      }
    }
    cancelLogicAddr = language.CreateInAlways(false, cancelLogicAddr);
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

  private static record HazardWriteAddr_NodeDesc(SCAIEVNode spawnNode,
                                                 SCAIEVNode addrNode, SCAIEVNode validNode,
                                                 MultiNodeStrategy pipeliner) {}
  private List<HazardWriteAddr_NodeDesc> hazardWriteNodes = new ArrayList<>();

  private static record DHSourceEntry(String validCond, String addrExpr) {}
  private static class StartSpawnStage_DHDesc {
    public PipelineStage startSpawnStage;
    public List<DHSourceEntry> dhSource_read = new ArrayList<>();
    public List<DHSourceEntry> dhSource_write = new ArrayList<>();
    public int regAddrWidth = -1;
    /** startSpawnStage is stalling cond (module pin name); includes WrStall */
    public String stageStallingCond = null;
    /** startSpawnStage is flushing cond (module pin name); includes WrFlush */
    public String stageFlushingCond = null;
    /** startSpawnStage WrStall output to assign */
    public String stageWrStallOutput = null;

    /** Condition expr: Is any relevant ISAX in startSpawnStage? */
    public String isaxStartSpawnCond = null;
    /** Condition epxr: Is there a RAW hazard in startSpawnStage? */
    public String readDHWire = null;
    /** Condition epxr: Is there a WAW hazard in startSpawnStage? */
    public String writeDHWire = null;

    public String addtoModuleDecl = "";
    public String addtoModuleLogic = "";

    public StartSpawnStage_DHDesc(PipelineStage startSpawnStage) {
      this.startSpawnStage = startSpawnStage;
    }
  }
  /**
   * 
   * @param logicBlock
   * @param registry
   * @param spawnNode
   * @param startSpawnStage the current 'start spawn stage'
   * @param existingPinKeys a set tracking the RdIValid (or other) keys already added as module interface pins
   * @param instantiationBuilder StringBuilder for additional module instantiation lines, each entry should adhere to ",\n.&lt;name&gt;(&lt;value&gt;)"
   * @param interfaceBuilder StringBuilder for additional module interface lines, each entry should start with ",\n    " and end without comma
   * @return an object containing the 'is ISAX' condition and the read/write descriptors ({@link DHSourceEntry}) 
   */
  @SuppressWarnings("unused")
  private StartSpawnStage_DHDesc DHModule_StagePortion(NodeLogicBlock logicBlock, NodeRegistryRO registry, int aux, SCAIEVNode spawnNode,
                                                        PipelineStage startSpawnStage,
                                                        HashSet<NodeInstanceDesc.Key> existingPinKeys,
                                                        StringBuilder instantiationBuilder, StringBuilder interfaceBuilder) {
    SCAIEVNode node = bNodes.GetSCAIEVNode(spawnNode.nameParentNode);
    var ret = new StartSpawnStage_DHDesc(startSpawnStage);

    String rdFlushModPortName = "RdFlush_%s_i".formatted(startSpawnStage.getName());
    if (existingPinKeys.add(new NodeInstanceDesc.Key(bNodes.RdFlush, startSpawnStage, ""))) {
      String flushCond = SCALUtil.buildCond_StageFlushing(bNodes, registry, startSpawnStage);

      instantiationBuilder.append(",\n.%s(%s)".formatted(rdFlushModPortName, flushCond));
      interfaceBuilder.append(",\n    input %s".formatted(rdFlushModPortName));
    }
    ret.stageFlushingCond = rdFlushModPortName;
    String rdStallModPortName = "RdStall_%s_i".formatted(startSpawnStage.getName());
    if (existingPinKeys.add(new NodeInstanceDesc.Key(bNodes.RdStall, startSpawnStage, ""))) {
      String stallCond = SCALUtil.buildCond_StageStalling(bNodes, registry, startSpawnStage, false);

      instantiationBuilder.append(",\n.%s(%s)".formatted(rdStallModPortName, stallCond));
      interfaceBuilder.append(",\n    input %s".formatted(rdStallModPortName));
    }
    ret.stageStallingCond = rdStallModPortName;

    String wrStallModPortName = "WrStall_%s_o".formatted(startSpawnStage.getName());
    var wrStallKey = new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, startSpawnStage, "", aux);
    if (existingPinKeys.add(wrStallKey)) {
      String wrStallExternalName = "WrStall_DH_%s_%d_s".formatted(startSpawnStage.getName(), aux);
      logicBlock.declarations += "logic %s;\n".formatted(wrStallExternalName);
      logicBlock.outputs.add(new NodeInstanceDesc(wrStallKey, wrStallExternalName, ExpressionType.WireName));
      registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, startSpawnStage, ""));

      instantiationBuilder.append(",\n.%s(%s)".formatted(wrStallModPortName, wrStallExternalName));
      interfaceBuilder.append(",\n    output %s".formatted(wrStallModPortName));
    }
    ret.stageWrStallOutput = wrStallModPortName;

    if (node.equals(bNodes.WrRD)) {
      ret.regAddrWidth = 5;
      // parent of WrRD_spawn is WrRD

      var instrRSInstance = registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdInstr_RS, startSpawnStage, ""));
      var instrRDInstance = registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdInstr_RD, startSpawnStage, ""));
      String inStageValidCond =
          registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdInStageValid, startSpawnStage, "")).getExpression();
      if (!inStageValidCond.startsWith(NodeRegistry.MISSING_PREFIX)) {
        String instageValidName = "inStageValid_%s_i".formatted(startSpawnStage.getName());
        instantiationBuilder.append(",\n.%s(%s)".formatted(instageValidName, inStageValidCond));
        interfaceBuilder.append(",\n    input %s".formatted(instageValidName));
        inStageValidCond = "%s && ".formatted(instageValidName);
      } else
        inStageValidCond = "";
      final String inStageValidCond_ = inStageValidCond; //..
      var rsrdNodeProcessor = new Object() {
        void process(NodeInstanceDesc instrRSRDInstance, List<DHSourceEntry> outList) {
          if (!instrRSRDInstance.getExpression().startsWith(NodeRegistry.MISSING_PREFIX)) {
            // Process the RdInstr_RS / RdInstr_RD node instance
            SCAIEVNode rsrdNode = instrRSRDInstance.getKey().getNode();
            assert(rsrdNode.elements > 0);
            assert((rsrdNode.size % rsrdNode.elements) == 0);

            int sizePerElem = rsrdNode.size / rsrdNode.elements;
            assert(sizePerElem == 5 || sizePerElem == 6);
            boolean hasValidBit = sizePerElem == 6;
            String inputName = "%s_%s_i".formatted(rsrdNode.name, startSpawnStage.getName());
            instantiationBuilder.append(",\n.%s(%s)".formatted(inputName, instrRSRDInstance.getExpression()));
            interfaceBuilder.append(",\n    input [%d-1:0] %s".formatted(rsrdNode.size, inputName));
            for (int iRS = 0; iRS < rsrdNode.elements; ++iRS) {
              String regNumExpr = "%s[%2$d+5-1:%2$d]".formatted(inputName, iRS*sizePerElem);
              String validCond = hasValidBit ? "%s[%d]".formatted(inputName, iRS*sizePerElem + 5)
                                             : "%s != 5'd0".formatted(regNumExpr);
              outList.add(new DHSourceEntry("(%s%s)".formatted(inStageValidCond_, validCond), regNumExpr));
            }
          }
        }
      };
      rsrdNodeProcessor.process(instrRSInstance, ret.dhSource_read);
      rsrdNodeProcessor.process(instrRDInstance, ret.dhSource_write);
    } else {
      // NOTE: This code-path is currently unreachable
      //   and serves as a template for handling spawn write nodes other than WrRD_spawn.
      assert(false);
      if (spawnNode.elements > 1) {
        ret.regAddrWidth = Log2.clog2(spawnNode.elements);
      }

      SCAIEVNode regRdNode = bNodes.GetSCAIEVNode(bNodes.GetNameRdNode(node));
      List<String> lookAtISAX = new ArrayList<String>();
      if (this.op_stage_instr.containsKey(regRdNode))
        for (var entry : this.op_stage_instr.get(regRdNode).entrySet())
          lookAtISAX.addAll(entry.getValue());
      if (!lookAtISAX.isEmpty())
        ret.dhSource_read.add(new DHSourceEntry(
             CreateAllImport(registry, bNodes, startSpawnStage, lookAtISAX, existingPinKeys, instantiationBuilder, interfaceBuilder),
             "0")); //NOTE: Fill in actual address

      lookAtISAX.clear();

      lookAtISAX = Stream.concat(Stream.ofNullable(this.op_stage_instr.get(node))
                                       .flatMap(stages_instrs -> stages_instrs.entrySet().stream().flatMap(kv->kv.getValue().stream())),
                                 Stream.ofNullable(this.op_stage_instr.get(spawnNode))
                                       .flatMap(stages_instrs -> stages_instrs.entrySet().stream().flatMap(kv->kv.getValue().stream())))
                         .toList();
      if (!lookAtISAX.isEmpty())
        ret.dhSource_write.add(new DHSourceEntry(
            CreateAllImport(registry, bNodes, startSpawnStage, lookAtISAX, existingPinKeys, instantiationBuilder, interfaceBuilder),
            "0")); //NOTE: Fill in actual address
    }
    assert(ret.regAddrWidth >= 0);

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
          RdIValid_ISAX_startSpawn = "(%s)".formatted(
              CreateAllImport(registry, bNodes, startSpawnStage, lookAtISAX, existingPinKeys, instantiationBuilder, interfaceBuilder));
      }
    }
    ret.isaxStartSpawnCond = RdIValid_ISAX_startSpawn;

    String sizeZero = "";
    String logic_RdReadDH = "";
    String readDHWire = "data_hazard_read_%s".formatted(startSpawnStage.getName());
    ret.addtoModuleDecl += "wire %s;\n".formatted(readDHWire);
    ret.addtoModuleLogic += "assign %s = %s;\n"
                            .formatted(readDHWire,
                                       ret.dhSource_read.stream().map(dhReadEntry -> "rd_table_mem[%s] && %s"
                                                                          .formatted(dhReadEntry.addrExpr, dhReadEntry.validCond))
                                                        .reduce((a,b)->a+" || "+b)
                                                        .orElse("1'b0"));
    String logic_RdWriteDH = "";
    String writeDHWire = "data_hazard_write_%s".formatted(startSpawnStage.getName());
    ret.addtoModuleDecl += "wire %s;\n".formatted(writeDHWire);
    ret.addtoModuleLogic += "assign %s = %s;\n"
                            .formatted(writeDHWire,
                                       ret.dhSource_write.stream().map(dhWriteEntry -> "rd_table_mem[%s] && %s"
                                                                           .formatted(dhWriteEntry.addrExpr, dhWriteEntry.validCond))
                                                         .reduce((a,b)->a+" || "+b)
                                                         .orElse("1'b0"));
    ret.readDHWire = readDHWire;
    ret.writeDHWire = writeDHWire;
    return ret;
  }

  /**
   * Creates a spawndatah_&lt;node&gt; module.
   * @param logicBlock the logic block to add the module and additional flushing-related logic into
   * @param registry the node registry
   * @param spawnNode the node to detect data hazards on (usually: WrRD_spawn)
   * @param startSpawnStagesList all 'start spawn' stages to process
   * @return Verilog for additional pin assignments instantiation from within SCAL, starting with a comma but without a trailing comma.
   */
  private String DHModule(NodeLogicBlock logicBlock, NodeRegistryRO registry, int aux, SCAIEVNode spawnNode,
                          List<PipelineStage> startSpawnStagesList) {
    // Compute DH decoding checks for detecting DHs
    HashSet<NodeInstanceDesc.Key> existingPinKeys = new HashSet<>();
    StringBuilder instantiationBuilder = new StringBuilder();
    StringBuilder interfaceBuilder = new StringBuilder();

    List<StartSpawnStage_DHDesc> startSpawnDHDescs =
        startSpawnStagesList.stream()
                            .map(startSpawnStage -> DHModule_StagePortion(logicBlock, registry, aux, spawnNode, startSpawnStage,
                                                                          existingPinKeys, instantiationBuilder, interfaceBuilder))
                            .toList();
    assert(!startSpawnDHDescs.isEmpty());

    int regAddrWidth = startSpawnDHDescs.get(0).regAddrWidth;
    if (startSpawnDHDescs.stream().skip(1).anyMatch(desc -> desc.regAddrWidth != regAddrWidth)) {
      logger.error("DecoupledDHStrategy DHModule: Inconsistent address widths for " + spawnNode.name);
    }

    //Note: MCU-cores previously had '!flush_i[0]' ANDed to data hazard condition
    String sizeZero = "";
    if (regAddrWidth > 0) {
      sizeZero = "[RD_W_P-1:0]";
    }
    String returnStr = "";
    //Note: For now, wait until we get the cancellation of the decoupled op, rather than directly process the core's retires.
    //PipelineFront issueFront = new PipelineFront(issueStages);
    PipelineFront rdflushLatestFront = core.GetNodes().containsKey(bNodes.RdFlush)
                                           ? core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.RdFlush).GetLatest())
                                           : new PipelineFront();
    //PipelineFront latestObserveFlushesFront = issueStages.isEmpty() ? rdflushLatestFront : issueFront;
    PipelineFront latestObserveFlushesFront = rdflushLatestFront;
    var startSpawnStagesFront = new PipelineFront(startSpawnStagesList);
    List<PipelineStage> flushableStages = startSpawnStagesFront
                                              .streamNext_bfs(stage -> latestObserveFlushesFront.isAroundOrAfter(stage, false))
                                              .filter(stage -> !startSpawnStagesFront.contains(stage) && latestObserveFlushesFront.isAroundOrAfter(stage, false))
                                              .filter(stage -> !stage.getTags().contains(StageTag.NoISAX))
                                              .toList();
    boolean later_flushes = !flushableStages.isEmpty();

    StringBuilder additionalModuleDeclBuilder = new StringBuilder();
    StringBuilder additionalModuleLogicBuilder = new StringBuilder();
    additionalModuleDeclBuilder.append(startSpawnDHDescs.stream().map(desc->desc.addtoModuleDecl).reduce("", (a,b)->a+b));
    additionalModuleLogicBuilder.append(startSpawnDHDescs.stream().map(desc->desc.addtoModuleLogic).reduce("", (a,b)->a+b));

    // Pipeline the write requests to all pipeline stages that need to be observed for flushes.
    record FlushableStageDesc(/** The flushable pipeline stage */ PipelineStage stage,
        /** The flush condition (SCAL-side), to be assigned to module flush_i[&lt;idx&gt;] */ String flushCond,
        /** The write addresses and conditions (module-side) */ List<DHSourceEntry> decIsaxWrites) {}
    List<FlushableStageDesc> flushablesToObserve = new ArrayList<>();

    if (later_flushes) {
      HazardWriteAddr_NodeDesc hazardNodeDesc = hazardWriteNodes.stream().filter(desc->desc.spawnNode.equals(spawnNode)).findFirst().orElse(null);
      if (hazardNodeDesc == null) {
        //Create custom write request address&valid nodes.
        SCAIEVNode addrNode = makeNodeHazardWriteAddr(spawnNode, regAddrWidth);
        SCAIEVNode validNode = makeNodeHazardWriteAddrValid(addrNode);
        //Add the custom nodes to bNodes.
        bNodes.AddCoreBNode(addrNode);
        bNodes.AddCoreBNode(validNode);
        //Create a pipeliner for the nodes (to be called by the DecoupledDHStrategy implement method).
        MultiNodeStrategy pipeliner = strategyBuilders.buildNodeRegPipelineStrategy(
                                           language, bNodes,
                                           new PipelineFront(startSpawnStagesList.stream().flatMap(stage->stage.getNext().stream())),
                                           false, false, false,
                                           keyPipe -> keyPipe.getISAX().isEmpty() && keyPipe.getAux() == 0
                                                      && (keyPipe.getNode().equals(addrNode) || keyPipe.getNode().equals(validNode)),
                                           keyPipe -> false, noneStrategy, false);
        //Add an entry for internal tracking.
        hazardNodeDesc = new HazardWriteAddr_NodeDesc(spawnNode, addrNode, validNode, pipeliner);
        hazardWriteNodes.add(hazardNodeDesc);
      }
      int numWrites = startSpawnDHDescs.stream().mapToInt(desc -> desc.dhSource_write.size()).max().orElse(0);
      for (var desc : startSpawnDHDescs) {
        //Output all dhSource_write entries with a custom node and an adjacent validReq ANDed by isaxStartSpawnCond;
        //     add the custom node (+adj) to bNodes.
        for (int iWrite = 0; iWrite < numWrites; ++iWrite)  {
          boolean exists = iWrite < desc.dhSource_write.size();
          //Output the write addr&valid outputs to pipeline.
          //Generate both the SCAL-side and the dh-module-side portions.
          var validKey = new NodeInstanceDesc.Key(Purpose.REGULAR, hazardNodeDesc.validNode, desc.startSpawnStage, "", iWrite);
          var addrKey = new NodeInstanceDesc.Key(Purpose.REGULAR, hazardNodeDesc.addrNode, desc.startSpawnStage, "", iWrite);
          String validOutputName = validKey.toString(false) + "_o", validSCALName = validKey.toString(false) + "_s";
          String addrOutputName = addrKey.toString(false) + "_o", addrSCALName = addrKey.toString(false) + "_s";
          logicBlock.declarations += "logic %s;\n".formatted(validSCALName);
          logicBlock.declarations += "logic [%d-1:0] %s;\n".formatted(hazardNodeDesc.addrNode.size, addrSCALName);
          instantiationBuilder.append(",\n.%s(%s)".formatted(validOutputName, validSCALName));
          instantiationBuilder.append(",\n.%s(%s)".formatted(addrOutputName, addrSCALName));
          interfaceBuilder.append(",\n    output %s".formatted(validOutputName));
          interfaceBuilder.append(",\n    output [%d-1:0] %s".formatted(hazardNodeDesc.addrNode.size, addrOutputName));
          if (exists) {
            var writeDesc = desc.dhSource_write.get(iWrite);
            additionalModuleLogicBuilder.append("assign %s = %s && %s;\n"
                                                .formatted(validOutputName, writeDesc.validCond, desc.isaxStartSpawnCond));
            additionalModuleLogicBuilder.append("assign %s = %s;\n".formatted(addrOutputName, writeDesc.addrExpr));
          }
          else {
            additionalModuleLogicBuilder.append("assign %s = 1'b0;\n".formatted(validOutputName));
            additionalModuleLogicBuilder.append("assign %s = '0;\n".formatted(addrOutputName));
          }
          logicBlock.outputs.add(new NodeInstanceDesc(validKey, validSCALName, ExpressionType.WireName));
          logicBlock.outputs.add(new NodeInstanceDesc(addrKey, addrSCALName, ExpressionType.WireName));
        }
      }
      var stageWarnings = new ArrayList<String>();
      // Request PIPEDIN versions of the 'write address/valid' nodes
      //  for all stages between startSpawnStagesList (excl) and latestObserveFlushesFront (incl).
      // Condense the results in the flushablesToObserve list.
      assert(flushablesToObserve.isEmpty());
      for (int iFlushable = 0; iFlushable < flushableStages.size(); ++iFlushable) {
        PipelineStage stage = flushableStages.get(iFlushable);
        assert(latestObserveFlushesFront.isAroundOrAfter(stage, false));
        if (!stage.getContinuous())
          stageWarnings.add(stage.getName());
        String stageFlushingCond = SCALUtil.buildCond_StageFlushing(bNodes, registry, stage);
        var flushable = new FlushableStageDesc(stage, stageFlushingCond, new ArrayList<>());
        for (int iWrite = 0; iWrite < numWrites; ++iWrite) {
          //Retrieve the pipelined write addresses and requests. Factor in RdInStageValid.
          var addrKey = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
                                                 hazardNodeDesc.addrNode, stage, "", iWrite);
          var validKey = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
                                                  hazardNodeDesc.validNode, stage, "", iWrite);
          var inStageValidInst = registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdInStageValid, stage, ""));
          if (inStageValidInst.getExpression().startsWith(NodeRegistry.MISSING_PREFIX))
            continue;
          String validCond = registry.lookupRequired(validKey).getExpressionWithParens();
          validCond += " && " + inStageValidInst.getExpressionWithParens();
          //Add the results as module inputs.
          String validInputName = validKey.toString(false) + "_i";
          String addrInputName = addrKey.toString(false) + "_i";
          instantiationBuilder.append(",\n.%s(%s)".formatted(validInputName, validCond));
          instantiationBuilder.append(",\n.%s(%s)".formatted(addrInputName, registry.lookupExpressionRequired(addrKey)));
          interfaceBuilder.append(",\n    input %s".formatted(validInputName));
          interfaceBuilder.append(",\n    input [%d-1:0] %s".formatted(hazardNodeDesc.addrNode.size, addrInputName));
          //Add the write descriptor (module-side).
          flushable.decIsaxWrites.add(new DHSourceEntry(validInputName, addrInputName));
        }
        flushablesToObserve.add(flushable);
      }
      assert(flushablesToObserve.size() == flushableStages.size());
      if (!stageWarnings.isEmpty()) {
        logger.warn("DecoupledDHStrategy %s: Cannot track flushes properly across non-continuous stages (%s)."
                    .formatted(spawnNode.name, stageWarnings.stream().reduce((a,b)->a+","+b).orElse("")));
      }
      assert(!flushableStages.isEmpty());

      instantiationBuilder.append(",\n.flush_i(%s)".formatted(
          (flushableStages.size() > 1 ? "{" : "")
          + IntStream.range(0, flushableStages.size()).map(i->flushableStages.size()-1-i)
              .mapToObj(i->flushablesToObserve.get(i).flushCond)
              .reduce((a,b)->a+","+b).orElse("")
          + (flushableStages.size() > 1 ? "}" : "")));
      if (flushableStages.size() >= 1)
        interfaceBuilder.append(",\n    input [%d-1:0] flush_i".formatted(flushableStages.size()));
      else
        interfaceBuilder.append(",\n    input [0:0] flush_i");
    }

    returnStr +=
        "module spawndatah_" + spawnNode + "#(\n"
        + " parameter RD_W_P = " + regAddrWidth + ",\n"
        + " parameter INSTR_W_P = 32,\n"
        + " parameter START_STAGE = " + startSpawnStagesList.get(0).getStagePos() + ",\n"
        + " parameter WB_STAGE = " + (latestObserveFlushesFront.asList().get(0).getStagePos()) + "\n"
        + "\n"
        + ")(\n"
        + "    input clk_i,\n"
        + "    input rst_i,\n"
        + "    input killall_i,\n"
        + "    input fence_i" + interfaceBuilder.toString() + ",\n"
        + "    input  WrRD_spawn_valid_i,\n"
        + "    input " + sizeZero + " WrRD_spawn_addr_i,\n"
        + "    input  cancel_from_user_valid_i,// user validReq bit was zero, but we need to clear its scoreboard dirty bit\n"
        + "    input " + sizeZero + "cancel_from_user_addr_i\n"
        + ");\n"
        + "\n"
        + "\n"
        + additionalModuleDeclBuilder.toString()
        + "reg [2**RD_W_P-1:0] mask_start;\n"
        + "reg [2**RD_W_P-1:0] mask_stop_or_flush;\n"
        + "\n"
        + "reg  [2**RD_W_P-1:0] rd_table_mem ;\n"
        + "reg  [2**RD_W_P-1:0] rd_table_temp;\n"
        + "\n"
        + "always_ff @(posedge clk_i) begin\n"
        + "    if (rst_i)\n"
        + "        rd_table_mem <= '0;\n"
        + "    else\n"
        + "        rd_table_mem <= rd_table_temp;\n"
        + "end\n"
        + "\n"
        + "always_comb begin\n"
        + "    mask_start = {(2**RD_W_P){1'b0}};\n"
          //For each ISAX starting: Set mask_start[rd] = 1'b1
        + startSpawnDHDescs.stream().map(desc ->
              "    if (%s && !%s && !%s) begin\n".formatted(desc.isaxStartSpawnCond, desc.stageStallingCond, desc.stageFlushingCond)
              + desc.dhSource_write.stream().map(writer->
                    "        if (%1$s != '0) mask_start[%1$s] = %2$s;\n".formatted(writer.addrExpr, writer.validCond))
                  .reduce("", (a,b)->a+b)
              + "    end\n")
            .reduce("", (a,b)->a+b)
        + "end\n"
        + "\n"
        + "always_comb begin\n"
        + "    mask_stop_or_flush = {(2**RD_W_P){1'b1}};\n"
        + "    if (WrRD_spawn_valid_i)\n"
        + "        mask_stop_or_flush[WrRD_spawn_addr_i] = 1'b0; \n  "
        + "    if (cancel_from_user_valid_i)\n"
        + "        mask_stop_or_flush[cancel_from_user_addr_i] = 1'b0;\n"
        + "    if (killall_i) begin\n"
        + "        mask_stop_or_flush = '0;\n"
        + "    end\n"
          //For all flushed intermediate stages, reset the 'inuse' bit.
        + IntStream.range(0, flushablesToObserve.size()).mapToObj(i -> {
               FlushableStageDesc flushable = flushablesToObserve.get(i);
               return flushable.decIsaxWrites.stream()
                        .map(writeDesc -> "    if (flush_i[%d] && %s) mask_stop_or_flush[%s] = 1'b0;\n"
                                         .formatted(i, writeDesc.validCond, writeDesc.addrExpr))
                        .reduce((a,b)->a+b).orElse("");
             }).reduce("",(a,b)->a+b)
        + "end\n"
        + "\n"
        + "always_comb begin\n"
        + "  rd_table_temp = (rd_table_mem | mask_start) & mask_stop_or_flush;\n"
        + " end\n"
        + "\n"
        // For now, use general ISAX pipeline tracking for disaxfence instead of the data hazard unit.
        //                + "always @(posedge clk_i)\n"
        //                + "begin\n"
        //                + "    if((|rd_table_mem) == 0)\n"
        //                + "        barrier_set <= 0;\n"
        //                + "    else if(fence_i && !flush_i[0])\n"
        //                + "        barrier_set <= 1;\n"
        //                + "end\n"
        //                + "wire stall_fr_barrier = barrier_set;\n"
        + "wire stall_fr_barrier = 0;\n"
        + "\n"
        + additionalModuleLogicBuilder.toString()
        + startSpawnDHDescs.stream().map(desc -> "assign %s = %s || %s || stall_fr_barrier;\n"
                                                 .formatted(desc.stageWrStallOutput, desc.readDHWire, desc.writeDHWire))
                                    .reduce("", (a,b)->a+b)
        + "\n"
        + "endmodule\n"
        + "";
    logicBlock.otherModules += returnStr;
    return instantiationBuilder.toString();
  }

  public static NodeInstanceDesc.Purpose purpose_MARKER_BUILT_DH =
      new NodeInstanceDesc.Purpose("MARKER_BUILT_DH", true, Optional.empty(), List.of());

  private void AddDHModule(NodeInstanceDesc.Key markerKey, NodeLogicBlock logicBlock, NodeRegistryRO registry, SCAIEVNode spawnNode,
                           PipelineStage spawnStage, int aux,
                           List<PipelineStage> startSpawnStagesList) {
    if (!buildWithScoreboard) {
      logicBlock.outputs.add(new NodeInstanceDesc(markerKey, "(disabled)", ExpressionType.AnyExpression_Noparen));
      return;
    }

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

    String dhAdditionalInstantiation = DHModule(logicBlock, registry, aux, spawnNode, startSpawnStagesList);

    /// ADD DH Mechanism
    // Differentiate between Wr Regfile and user nodes or address signals
    //String instrSig = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdInstr, startSpawnStage, ""));
    //String destSig = instrSig + "[11:7]";
    // Use cancel signal in case of user validReq = 0
    SCAIEVNode nodeCancelValid = makeNodeCancelValid(spawnNode);
    SCAIEVNode nodeCancelAddr = makeNodeCancelAddr(spawnNode);
    SCAIEVNode nodeCommittedRdSpawnValid = makeNodeCommittedRdSpawnValid(spawnNode);
    SCAIEVNode nodeCommittedRdSpawnAddr = makeNodeCommittedRdSpawnAddr(spawnNode);
    //String flush = registry.lookupExpressionRequired(
    //    new NodeInstanceDesc.Key(DecoupledPipeStrategy.PseudoNode_StartSpawnToSpawnPipe_Flush, spawnStage, ""));
    //String stallFrCore = registry.lookupExpressionRequired(
    //    new NodeInstanceDesc.Key(DecoupledPipeStrategy.PseudoNode_StartSpawnToSpawnPipe_StallFrCore, spawnStage, ""));
    String killall = registry.lookupExpressionRequired(
        new NodeInstanceDesc.Key(DecoupledPipeStrategy.PseudoNode_StartSpawnToSpawnPipe_KillAll, spawnStage, ""));
    String fence = registry.lookupExpressionRequired(
        new NodeInstanceDesc.Key(DecoupledPipeStrategy.PseudoNode_StartSpawnToSpawnPipe_Fence, spawnStage, ""));
    //String toCOREstallRSName = "to_CORE_stall_RS_" + spawnNode + "_s";
    // Instantiate module
    logicBlock.logic +=
        "spawndatah_" + spawnNode + " " + moduleInstanceName + "(\n"
        + "    .clk_i(" + language.clk + "),\n"
        + "    .rst_i(" + language.reset + "),\n"
        + "    .killall_i(" + killall + "),\n"
        + "    .fence_i(" + fence + "),\n"
        //+ "    .flush_i({" + flush + "," +
        //registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, startSpawnStage, "")) +
        //"}),\n"
        //+ "    .RdIValid_ISAX0_2_i("+allIValid+"),\n"
        + "    .WrRD_spawn_valid_i(" +
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(nodeCommittedRdSpawnValid, spawnStage, "")) + "),\n"
        + "    .WrRD_spawn_addr_i(" +
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(nodeCommittedRdSpawnAddr, spawnStage, "")) + "),\n"
        + "    .cancel_from_user_valid_i(" +
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(DHInternalPurpose, nodeCancelValid, spawnStage, "")) + "),\n"
        + "    .cancel_from_user_addr_i(" +
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(DHInternalPurpose, nodeCancelAddr, spawnStage, "")) + ")"
        //+ "    .stall_RDRS_o(" + toCOREstallRSName + "),\n"
        //+ "    .stall_RDRS_i(" + stallFrCore + ")\n"
        + dhAdditionalInstantiation.replace("\n", "\n    ") + "\n"
        + ");\n";
    //logicBlock.declarations += "wire " + toCOREstallRSName + ";\n";
    //logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(makeToCOREStallRS(spawnNode), startSpawnStage, ""),
    //                                            toCOREstallRSName, ExpressionType.WireName));
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
      if (!nodeKey.getNode().equals(bNodes.WrRD_spawn)) {
        logger.warn("DecoupledDHStrategy: Cannot build DH module for unsupported node - " + nodeKey.toString());
        continue;
      }
      List<PipelineStage> startSpawnStagesList = DecoupledPipeStrategy.getRelevantStartSpawnStages(core, nodeKey.getStage());
      assert (startSpawnStagesList.size() > 0);
      assert (startSpawnStagesList.stream().allMatch(
          startSpawnStage -> startSpawnStage.getStagePos() == startSpawnStagesList.get(0).getStagePos()));
      if (implementedDHModules.add(nodeKey.getNode())) {
        out.accept(NodeLogicBuilder.fromFunction("DecoupledDHStrategy_DHModule_" + nodeKey.getNode().name,
           (NodeRegistryRO registry, Integer aux) -> {
             NodeLogicBlock ret = new NodeLogicBlock();
             AddDHModule(nodeKey, ret, registry, nodeKey.getNode(), nodeKey.getStage(), aux, startSpawnStagesList);
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
    hazardWriteNodes.forEach(desc -> desc.pipeliner.implement(out, nodeKeys, isLast));
  }
}
