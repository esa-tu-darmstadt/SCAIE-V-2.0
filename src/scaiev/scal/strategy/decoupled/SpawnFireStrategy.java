package scaiev.scal.strategy.decoupled;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.function.Consumer;
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
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.util.Verilog;

/** Strategy that builds the fire/MUXing logic for spawn nodes */
public class SpawnFireStrategy extends MultiNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  Verilog language;
  BNode bNodes;
  Core core;
  HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  HashMap<String, SCAIEVInstr> allISAXes;
  Map<SCAIEVNode, Collection<String>> isaxesSortedByPriority;
  Collection<SCAIEVNode> disableSpawnFireStallNodes;
  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param allISAXes The ISAX descriptions
   * @param isaxesSortedByPriority For each spawn node, an ISAX name collection sorted by priority
   * @param disableSpawnFireStallNodes Spawn nodes that should stall the core when firing
   */
  public SpawnFireStrategy(Verilog language, BNode bNodes, Core core,
                           HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                           HashMap<String, SCAIEVInstr> allISAXes, Map<SCAIEVNode, Collection<String>> isaxesSortedByPriority,
                           Collection<SCAIEVNode> disableSpawnFireStallNodes) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.allISAXes = allISAXes;
    this.isaxesSortedByPriority = isaxesSortedByPriority;
    this.disableSpawnFireStallNodes = disableSpawnFireStallNodes;
  }

  @Override
  public void setLanguage(Verilog lang) {
    this.language = lang;
  }

  public static SCAIEVNode ISAX_fire2_r = new SCAIEVNode("ISAX_fire2_r", 1, false); // The request interface output
  public static SCAIEVNode ISAX_fire_s = new SCAIEVNode("ISAX_fire_s", 1, false);   // A new request (combinational)
  public static SCAIEVNode ISAX_fire_r =
      new SCAIEVNode("ISAX_fire_r", 1, false); // A buffered request that is waiting before being exposed to the interface
  public static SCAIEVNode ISAX_spawn_sum = new SCAIEVNode("ISAX_spawn_sum", 8, false);

  private String ConditionSpawnAllowed(NodeRegistryRO registry, RequestedForSet requestedForBase, SCAIEVNode node,
                                       PipelineStage startSpawnStage, PipelineStage spawnStage) {
    // List<PipelineStage> spawnStagesList = this.core.GetSpawnStages().asList();
    // assert(spawnStagesList.size() >= 1);
    // if (spawnStagesList.size() > 1)
    //	logger.warn("ConditionSpawnAllowed - only considering first of several 'spawn stages'");
    // PipelineStage spawnStage = spawnStagesList.get(0);

    registry.lookupExpressionRequired(new NodeInstanceDesc.Key(Purpose.MARKER_FROMCORE_PIN, bNodes.ISAX_spawnAllowed, startSpawnStage, ""));
    String spawn_allowed_cond =
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.ISAX_spawnAllowed, startSpawnStage, ""), requestedForBase);
    Optional<SCAIEVNode> specific_spawn_allowed_opt =
        bNodes.GetAdjSCAIEVNode(node, AdjacentNode.spawnAllowed).map(SCAIEVNode::makeFamilyNode);
    Optional<String> spawnAllowedExpr =
        specific_spawn_allowed_opt
            .flatMap(specific_spawn_allowed
                     -> registry.lookupOptionalUnique(new NodeInstanceDesc.Key(specific_spawn_allowed_opt.get(), spawnStage, "")))
            .map(inst -> inst.getExpression());
    if (spawnAllowedExpr.isPresent()) {
      spawn_allowed_cond += " && " + spawnAllowedExpr.get();
    }

    var opt_stallRS = registry
                          .lookupOptionalUnique(new NodeInstanceDesc.Key(DecoupledDHStrategy.makeToCOREStallRS(node), startSpawnStage, ""),
                                                requestedForBase)
                          .map(inst -> inst.getExpression());
    // If Scoreboard present here, we have to consider its stall to avoid deadlock:  exp, in execute there is a memory instr with DH
    if (opt_stallRS.isPresent())
      spawn_allowed_cond += " || " + opt_stallRS.get();
    return spawn_allowed_cond;
  }

  public void CommitSpawnFire(NodeLogicBlock logicBlock, NodeRegistryRO registry, RequestedForSet fireRequestedForBase, SCAIEVNode node,
                              List<PipelineStage> startSpawnStages, PipelineStage spawnStage, String fireNodeSuffix, int aux) {
    assert (startSpawnStages.size() >= 1);
    if (startSpawnStages.size() > 1)
      logger.warn("CommitSpawnFire - only considering first of several 'start spawn stages'");
    PipelineStage startSpawnStage = startSpawnStages.get(0);

    NodeInstanceDesc ISAX_fire_s_inst = registry.lookupRequired(new NodeInstanceDesc.Key(ISAX_fire_s, spawnStage, fireNodeSuffix));
    // String ISAX_fire_s = language.CreateLocalNodeName(this.ISAX_fire_s, spawnStage, fireNodeSuffix);
    NodeInstanceDesc ISAX_spawn_sum_inst = registry.lookupRequired(new NodeInstanceDesc.Key(ISAX_spawn_sum, spawnStage, fireNodeSuffix));
    // String ISAX_sum_spawn_s = language.CreateLocalNodeName(this.ISAX_spawn_sum, spawnStage, fireNodeSuffix);
    fireRequestedForBase.addAll(ISAX_fire_s_inst.getRequestedFor(), true);
    fireRequestedForBase.addAll(ISAX_spawn_sum_inst.getRequestedFor(), true);

    String ISAX_fire_r_sig = language.CreateRegNodeName(ISAX_fire_r, spawnStage, fireNodeSuffix);
    logicBlock.declarations += String.format("reg %s;\n", ISAX_fire_r_sig);
    var ISAX_fire_r_inst = new NodeInstanceDesc(new NodeInstanceDesc.Key(ISAX_fire_r, spawnStage, fireNodeSuffix), ISAX_fire_r_sig,
                                                ExpressionType.WireName, new RequestedForSet());
    ISAX_fire_r_inst.addRequestedFor(fireRequestedForBase, true);
    logicBlock.outputs.add(ISAX_fire_r_inst);
    String ISAX_fire2_r_sig = language.CreateRegNodeName(ISAX_fire2_r, spawnStage, fireNodeSuffix);
    logicBlock.declarations += String.format("reg %s;\n", ISAX_fire2_r_sig);
    var ISAX_fire2_r_inst = new NodeInstanceDesc(new NodeInstanceDesc.Key(ISAX_fire2_r, spawnStage, fireNodeSuffix), ISAX_fire2_r_sig,
                                                 ExpressionType.WireName, new RequestedForSet());
    ISAX_fire2_r_inst.addRequestedFor(fireRequestedForBase, true);
    logicBlock.outputs.add(ISAX_fire2_r_inst);

    String stageReady = ConditionSpawnAllowed(registry, fireRequestedForBase, node, startSpawnStage, spawnStage);
    Optional<SCAIEVNode> validRespNode_opt = bNodes.GetAdjSCAIEVNode(node, AdjacentNode.validResp).map(SCAIEVNode::makeFamilyNode);
    SCAIEVNode validReqToCore = bNodes.GetAdjSCAIEVNode(node, AdjacentNode.validReq).get().makeFamilyNode();

    // TODO: Why is the 'start spawn' stage stalling relevant here?
    String stallStage = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrStall, startSpawnStage, ""))
                            .map(inst -> inst.getExpression())
                            .orElse("");

    String validReqToCoreName = language.CreateLocalNodeName(validReqToCore, spawnStage, "");

    logicBlock.declarations += String.format("wire %s;\n", validReqToCoreName);
    var validReqToCoreInst = new NodeInstanceDesc(new NodeInstanceDesc.Key(validReqToCore, spawnStage, ""), validReqToCoreName,
                                                  ExpressionType.WireName, new RequestedForSet());
    validReqToCoreInst.addRequestedFor(fireRequestedForBase, true);
    logicBlock.outputs.add(validReqToCoreInst);

    String validResp = "";
    if (validRespNode_opt.isPresent()) {
      validResp = " && " + registry.lookupExpressionRequired(new NodeInstanceDesc.Key(validRespNode_opt.get(), spawnStage, ""),
                                                             fireRequestedForBase);
    }
    // Create stall logic
    String stall3Text = "";
    String stallFullLogic = "";
    String stageReadyText = "";
    if (!stallStage.isEmpty() && !stageReady.isEmpty())
      stall3Text += " || ";
    if (!stallStage.isEmpty())
      stall3Text += stallStage;
    if (!stageReady.isEmpty())
      stageReadyText = "(" + stageReady + ")";
    if (!stallStage.isEmpty() || !stageReady.isEmpty())
      stallFullLogic = "&& (" + stageReadyText + stall3Text + ")";

    logicBlock.logic += " // ISAX : Spawn fire logic\n"
                        + "always @ (posedge " + language.clk + ")  begin\n"
                        + "     if(" + ISAX_fire_s_inst.getExpression() + " && !" + stageReadyText + " )\n"
                        + "         " + ISAX_fire_r_sig + " <=  1'b1;\n"
                        + "     else if((" + ISAX_fire_r_sig + " ) " + stallFullLogic + ")\n"
                        + "         " + ISAX_fire_r_sig + " <=  1'b0;\n"
                        + "     if (" + language.reset + ")\n"
                        + "          " + ISAX_fire_r_sig + " <= 1'b0;\n"
                        + "end\n"
                        + "\n"
                        + "always @ (posedge " + language.clk + ")  begin\n"
                        + "     if((" + ISAX_fire_r_sig + " || " + ISAX_fire_s_inst.getExpression() + ") " + stallFullLogic + ")\n"
                        + "          " + ISAX_fire2_r_sig + " <=  1'b1;\n"
                        + "     else if(" + ISAX_fire2_r_sig + " && (" + ISAX_spawn_sum_inst.getExpression() + " == 1) " + validResp +
                        ")\n"
                        + "          " + ISAX_fire2_r_sig + " <= 1'b0;\n"
                        + "     if (" + language.reset + ")\n"
                        + "          " + ISAX_fire2_r_sig + " <= 1'b0;\n"
                        + "end\n"
                        + "\n "
                        + "assign " + validReqToCoreName + " = " + ISAX_fire2_r_sig + ";\n";

    if (!disableSpawnFireStallNodes.contains(node)) {
      // WrRD_spawn: Stall entire core
      //(Wr|Rd)Mem_spawn : Stall until the first available stage for WrMem.
      PipelineFront maxStall = new PipelineFront();
      if (node.equals(bNodes.WrRD_spawn))
        maxStall = new PipelineFront(core.GetRootStage().getChildrenTails());
      else if (node.equals(bNodes.WrMem_spawn) || node.equals(bNodes.RdMem_spawn))
        maxStall = core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.WrMem).GetEarliest());
      final PipelineFront maxStall_ = maxStall;
      core.GetRootStage().getAllChildren()
                         .filter(stage -> stage.getKind() != StageKind.CoreInternal) //Filter out CoreInternal stages, which may not have WrStall.
                         .filter(stage -> maxStall_.isAroundOrAfter(stage, false))
                         .forEach(stage -> {
        var stallInst = new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, stage, "", aux), ISAX_fire2_r_sig,
                                             ExpressionType.AnyExpression);
        stallInst.addRequestedFor(fireRequestedForBase, true);
        logicBlock.outputs.add(stallInst);
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, stage, ""));
      });
    }
  }

  /**
   * Builds the suffix for ISAX_fire* and ISAX_spawn_sum for a given spawn node, for use as the {@link NodeInstanceDesc.Key} ISAX field.
   * Note: Several spawn nodes may map to the same suffix (iff they are to share a fire port).
   */
  public static String getFireNodeSuffix(SCAIEVNode spawnNode) {
    assert (spawnNode.isSpawn());
    if (spawnNode.nameCousinNode.isEmpty())
      return spawnNode.name.split("_")[0];
    return spawnNode.familyName;
  }

  /**
   * For a given spawn node (non-adj), get a Stream of all node-ISAX pairs that share the fire port, ordered by fire priority.
   * Note: If there are several spawn stages, not all pairs may actually appear in any given stage.
   * @param bNodes the BNode SCAIEVNode registry to use
   * @param priorities the priorities map
   * @param spawnNode the node with isSpawn() && !isAdj()
   * */
  public static Stream<Map.Entry<SCAIEVNode, String>> getISAXPriority(BNode bNodes, Map<SCAIEVNode, Collection<String>> priorities,
                                                                      SCAIEVNode spawnNode) {
    assert (!spawnNode.isAdj());
    assert (spawnNode.isSpawn());
    String portName = bNodes.getPortName(spawnNode);
    assert(portName.isEmpty() == !spawnNode.tags.contains(NodeTypeTag.isPortNode));
    SCAIEVNode nonportSpawnNode = spawnNode.tags.contains(NodeTypeTag.isPortNode)
        ? bNodes.GetSCAIEVNode(spawnNode.nameParentNode)
        : spawnNode;
    SortedSet<SCAIEVNode> sortedNodes = new TreeSet<>((a, b) -> a.name.compareTo(b.name));
    do {
      if (!sortedNodes.add(nonportSpawnNode))
        break;
      nonportSpawnNode = bNodes.GetSCAIEVNode(nonportSpawnNode.nameCousinNode);
    } while (!nonportSpawnNode.nameCousinNode.isEmpty());
    return sortedNodes.stream().flatMap(node -> {
      SCAIEVNode nodePorted = bNodes.GetAllPortsByBaseName().getOrDefault(node, List.of()).stream()
          .filter(portnode -> bNodes.getPortName(portnode).equals(portName))
          .findAny().orElse(node);
      return priorities.getOrDefault(node, List.of()).stream().map(isax -> Map.entry(nodePorted, isax));
    });
  }
  /**
   * Given a priority stream (see {@link SpawnFireStrategy#getISAXPriority(BNode, Map, SCAIEVNode)}),
   *   builds a Stream of all condition key lists (lists of {@link NodeInstanceDesc.Key}), the keys being for adjacent nodes such as
   * validReq or cancelReq. A request can be considered requested if any of the expressions for the List entries evaluates to 1. Filters out
   * entries that do not appear in op_stage_instr in the given spawnStage.
   * @param validReqPurpose the Purpose to assign to the Key
   * @param bNodes the BNode SCAIEVNode registry to use
   * @param op_stage_instr the op_stage_instr mapping to use
   * @param spawnStage the stage in which priorities are to be viewed
   * @param priorityStream the priority stream (see {@link SpawnFireStrategy#getISAXPriority(BNode, Map, SCAIEVNode)})
   */
  public static Stream<List<NodeInstanceDesc.Key>>
  getPriorityValidReqKeysStream(Purpose validReqPurpose, BNode bNodes,
                                HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr, PipelineStage spawnStage,
                                Stream<Map.Entry<SCAIEVNode, String>> priorityStream) {
    return priorityStream
        .filter(entry
                -> // Does op_stage_instr contain the node and instruction described by entry, in spawnStage?
                Optional.ofNullable(op_stage_instr.get(entry.getKey()))
                    .flatMap(stage_instr -> Optional.ofNullable(stage_instr.get(spawnStage)))
                    .map(instrs -> instrs.contains(entry.getValue()))
                    .orElse(false))
        .map(entry -> {
          var validReqKey = new NodeInstanceDesc.Key(validReqPurpose, bNodes.GetAdjSCAIEVNode(entry.getKey(), AdjacentNode.validReq).get(),
                                                     spawnStage, entry.getValue());
          var cancelReqNode_opt = bNodes.GetAdjSCAIEVNode(entry.getKey(), AdjacentNode.cancelReq);
          if (cancelReqNode_opt.isPresent())
            return List.of(validReqKey, new NodeInstanceDesc.Key(validReqPurpose, cancelReqNode_opt.get(), spawnStage, entry.getValue()));
          return List.of(validReqKey);
        });
  }

  private HashSet<String> builtSpawnSumSet = new HashSet<String>();
  private HashSet<String> builtFireSSet = new HashSet<String>();
  private HashSet<String> builtValidReqToCoreSet = new HashSet<String>();

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    // Invoke for: ISAX_fire_r; ISAX_fire2_r; *_validReq (stage in spawnStages, ISAX="")
    Iterator<NodeInstanceDesc.Key> nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      NodeInstanceDesc.Key nodeKey = nodeKeyIter.next();
      if (!nodeKey.getPurpose().matches(Purpose.REGULAR) || nodeKey.getAux() != 0 ||
          (nodeKey.getStage().getKind() != StageKind.Decoupled && nodeKey.getStage().getKind() != StageKind.Core))
        continue;
      SCAIEVNode spawnNode = null;
      String fireNodeSuffix = "";
      if (nodeKey.getNode().equals(ISAX_spawn_sum) || nodeKey.getNode().equals(ISAX_fire_s) || nodeKey.getNode().equals(ISAX_fire_r) ||
          nodeKey.getNode().equals(ISAX_fire2_r)) {
        String familyName = nodeKey.getISAX();
        var spawnNodeOpt = bNodes.GetAllBackNodes()
                               .stream()
                               .filter(node_ -> !node_.isAdj() && node_.isSpawn() && getFireNodeSuffix(node_).equals(familyName))
                               .max((a, b) -> a.name.compareTo(b.name)); // Get the last such node by priority order.
        if (!spawnNodeOpt.isPresent()) {
          logger.error("SpawnFireStrategy - Cannot find a node matching fire node " + nodeKey.toString(false));
        } else {
          spawnNode = spawnNodeOpt.get();
          fireNodeSuffix = nodeKey.getISAX();
        }
      } else if (nodeKey.getNode().isSpawn() && nodeKey.getNode().getAdj().equals(AdjacentNode.validReq) && nodeKey.getISAX().isEmpty()) {
        /* general node validReq: output of fire logic */
        spawnNode = bNodes.GetSCAIEVNode(nodeKey.getNode().nameParentNode);
        fireNodeSuffix = getFireNodeSuffix(spawnNode);
      }

      if (spawnNode == null || !spawnNode.allowMultipleSpawn)
        continue;
      List<PipelineStage> relevantStartSpawnStages = DecoupledPipeStrategy.getRelevantStartSpawnStages(core, nodeKey.getStage());
      if (relevantStartSpawnStages.isEmpty()) {
        logger.error("SpawnFireStrategy - Found no matching startSpawnStage for " + nodeKey.getStage().getName());
        continue;
      }

      final SCAIEVNode spawnNode_ = spawnNode;
      final String fireNodeSuffix_ = fireNodeSuffix;
      if (nodeKey.getNode().equals(ISAX_spawn_sum) && builtSpawnSumSet.add(fireNodeSuffix)) {
        RequestedForSet relevantForSet = new RequestedForSet();
        out.accept(NodeLogicBuilder.fromFunction("SpawnFireStrategy-spawn_sum-" + fireNodeSuffix, (registry, aux) -> {
          var ret = new NodeLogicBlock();
          // Build ISAX_spawn_sum for the node family, summing over all registered ISAX validReq nodes (-> REGISTERED).
          String sumStr =
              getPriorityValidReqKeysStream(Purpose.REGISTERED, bNodes, op_stage_instr, nodeKey.getStage(),
                                            getISAXPriority(bNodes, isaxesSortedByPriority, spawnNode_))
                  .flatMap(keys -> {
                    String validCond =
                        keys.stream().map(key -> registry.lookupExpressionRequired(key)).reduce((a, b) -> a + " || " + b).orElse("1'b0");
                    Stream<String> validReqAsSize = Stream.of(String.format("%d'(%s)", ISAX_spawn_sum.size, validCond));
                    // Also, if the associated input FIFO is present, add a 'level>1' condition.
                    var optInputFIFOBlock = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(
                        // SpawnOptionalInputFIFOStrategy.makeNotEmptyNode(bNodes, spawnNode_),
                        SpawnOptionalInputFIFOStrategy.makeLevelNode(bNodes, spawnNode_, 0 /*not relevant*/), keys.get(0).getStage(),
                        keys.get(0).getISAX()));
                    var optInputFIFONotFullBlock = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(
                        SpawnOptionalInputFIFOStrategy.makeNotFullNode(bNodes, spawnNode_), keys.get(0).getStage(),
                        keys.get(0).getISAX()));
                    relevantForSet.addRelevantISAX(keys.get(0).getISAX());
                    String optInputFIFONotFullCond = optInputFIFONotFullBlock.map(x->x.getExpressionWithParens()).orElse("1'b0");
                    Stream<String> spawnInputFIFOAsSize =
                        optInputFIFOBlock.filter(inputFIFOBlock -> inputFIFOBlock.getKey().getNode().size > 1)
                            .map(inputFIFOBlock
                                 -> Stream.of(String.format("%d'(%s>%d'd1||!%s)", ISAX_spawn_sum.size, inputFIFOBlock.getExpression(),
                                                            inputFIFOBlock.getKey().getNode().size, optInputFIFONotFullCond)))
                            .orElse(Stream.empty());
                    return Stream.concat(validReqAsSize, spawnInputFIFOAsSize);
                  })
                  .reduce(ISAX_spawn_sum.size + "'d0", (a, b) -> a + " + " + b);
          String sumWireName = language.CreateLocalNodeName(ISAX_spawn_sum, nodeKey.getStage(), fireNodeSuffix_);
          ret.declarations += String.format("wire [%d-1:0] %s;\n", ISAX_spawn_sum.size, sumWireName);
          ret.logic += String.format("assign %s = %s;\n", sumWireName, sumStr);
          ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(ISAX_spawn_sum, nodeKey.getStage(), fireNodeSuffix_), sumWireName,
                                               ExpressionType.WireName, relevantForSet));
          return ret;
        }));
      } else if (nodeKey.getNode().equals(ISAX_fire_s) && builtFireSSet.add(fireNodeSuffix)) {
        RequestedForSet relevantForSet = new RequestedForSet();
        out.accept(NodeLogicBuilder.fromFunction("SpawnFireStrategy-fire_s-" + fireNodeSuffix, (registry, aux) -> {
          var ret = new NodeLogicBlock();
          // Build ISAX_fire_s for the node family, considering all ISAX validReq input nodes (-> REGULAR).
          String fireStr =
              getPriorityValidReqKeysStream(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, bNodes, op_stage_instr, nodeKey.getStage(),
                                            getISAXPriority(bNodes, isaxesSortedByPriority, spawnNode_))
                  .flatMap(
                      keys
                      -> keys.stream()) // Since we're ORing all together anyway, there is no need to keep the condition key lists intact.
                  .map(key -> {
                    relevantForSet.addRelevantISAX(key.getISAX());
                    return registry.lookupExpressionRequired(key);
                  })
                  .reduce("1'b0", (a, b) -> a + " || " + b);
          String fireWireName = language.CreateLocalNodeName(ISAX_fire_s, nodeKey.getStage(), fireNodeSuffix_);
          ret.declarations += String.format("wire %s;\n", fireWireName);
          ret.logic += String.format("assign %s = %s;\n", fireWireName, fireStr);
          ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(ISAX_fire_s, nodeKey.getStage(), fireNodeSuffix_), fireWireName,
                                               ExpressionType.WireName, relevantForSet));
          return ret;
        }));
      } else if (nodeKey.getISAX().isEmpty() && builtValidReqToCoreSet.add(fireNodeSuffix)) {
        RequestedForSet fireRequestedForBase = new RequestedForSet();
        out.accept(NodeLogicBuilder.fromFunction("SpawnFireStrategy-fire_r,fire2_r,validReqToCore-" + fireNodeSuffix, (registry, aux) -> {
          var ret = new NodeLogicBlock();
          CommitSpawnFire(ret, registry, fireRequestedForBase, spawnNode_, relevantStartSpawnStages, nodeKey.getStage(), fireNodeSuffix_,
                          aux);
          return ret;
        }));
      }
      nodeKeyIter.remove();
    }
  }
}
