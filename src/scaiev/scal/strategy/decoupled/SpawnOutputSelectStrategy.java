package scaiev.scal.strategy.decoupled;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.TriggerableNodeLogicBuilder;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.ui.SCAIEVConfig;
import scaiev.util.Verilog;

/**
 * Strategy that selects to-core / to-ISAX output values for spawn nodes, taking into account fire results and priority.
 * Note: Makes assumptions on the SpawnFireStrategy implementation for selection conditions.
 */
public class SpawnOutputSelectStrategy extends MultiNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  Verilog language;
  BNode bNodes;
  Core core;
  HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  Map<SCAIEVNode, Collection<String>> isaxesSortedByPriority;
  SCAIEVConfig cfg;
  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param isaxesSortedByPriority For each spawn node, an ISAX name collection sorted by priority
   * @param cfg The SCAIE-V global config
   */
  public SpawnOutputSelectStrategy(Verilog language, BNode bNodes, Core core,
                                   HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                                   Map<SCAIEVNode, Collection<String>> isaxesSortedByPriority,
                                   SCAIEVConfig cfg) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.isaxesSortedByPriority = isaxesSortedByPriority;
    this.cfg = cfg;
  }

  @Override
  public void setLanguage(Verilog lang) {
    this.language = lang;
  }

  private void LogicToCoreSpawn(NodeLogicBlock logicBlock, NodeRegistryRO registry, RequestedForSet requestedFor, SCAIEVNode node,
                                PipelineStage stage) {
    StringBuilder body = new StringBuilder();
    SCAIEVNode mainNode = (node.isAdj() ? bNodes.GetSCAIEVNode(node.nameParentNode) : node);
    SCAIEVNode nodeToCore = node.makeFamilyNode();
    String toCoreSignalName = language.CreateLocalNodeName(nodeToCore, stage, "");

    StringBuilder priority = new StringBuilder();
    SpawnFireStrategy
        .getPriorityValidReqKeysStream(Purpose.REGISTERED, bNodes, op_stage_instr, stage,
                                       SpawnFireStrategy.getISAXPriority(bNodes, isaxesSortedByPriority, mainNode))
        .forEachOrdered(curValidReqKeys -> {
          String curValidReq =
              curValidReqKeys.stream().map(key -> registry.lookupExpressionRequired(key)).reduce((a, b) -> a + " || " + b).orElse("1'b0");
          requestedFor.addRelevantISAX(curValidReqKeys.get(0).getISAX());
          String align = "";
          if (!priority.isEmpty()) {
            body.append("if (!(" + priority.toString() + "))\n");
            align = language.tab;
          }
          SCAIEVNode selectedMainNode = bNodes.GetSCAIEVNode(curValidReqKeys.get(0).getNode().nameParentNode);
          Optional<SCAIEVNode> selectedNode =
              node.isAdj() ? bNodes.GetAdjSCAIEVNode(selectedMainNode, node.getAdj()) : Optional.of(selectedMainNode);
          if (selectedNode.isPresent()) {
            assert (selectedNode.get().getAdj() == node.getAdj());
            if (selectedNode.get().getAdj() == AdjacentNode.isWrite) {
              body.append(String.format("%s%s = %s;\n", align, toCoreSignalName, (selectedMainNode.isInput ? "1" : "0")));
            } else if (node.canRepresentAsFamily() || selectedNode.get().equals(node)) {
              assert (selectedNode.get().getAdj() != AdjacentNode.validReq);
              String curNodeKey = registry.lookupExpressionRequired(
                  new NodeInstanceDesc.Key(Purpose.REGISTERED, selectedNode.get(), stage, curValidReqKeys.get(0).getISAX()));
              body.append(String.format("%s%s = %s;\n", align, toCoreSignalName, curNodeKey));
            } else {
              body.append(String.format("%s%s = 0;\n", align, toCoreSignalName));
            }
          }
          priority.append((priority.isEmpty() ? "" : " || ") + curValidReq);
        });
    if (!body.isEmpty()) {
      logicBlock.logic += language.CreateInAlways(false, body.toString());
      logicBlock.declarations += (nodeToCore.size > 0) ? String.format("reg [%d-1:0] %s;\n", nodeToCore.size, toCoreSignalName)
                                                       : String.format("reg %s;\n", toCoreSignalName);
      logicBlock.outputs.add(
          new NodeInstanceDesc(new NodeInstanceDesc.Key(nodeToCore, stage, ""), toCoreSignalName, ExpressionType.WireName, requestedFor));
    }
  }

  protected static Purpose requestHistoryPurpose = new Purpose("SpawnRequestHistory", true, Optional.empty(), List.of());
  //NOTE: not verified to work with port nodes
  /**
   * Manages the building of a request history. The history indicates the ISAX (and node) a request response belongs to.
   * Relevant for RdMem and, due to channel sharing, also covers WrMem.
   */
  protected class RequestHistoryBuilder extends TriggerableNodeLogicBuilder {
    public SCAIEVNode spawnNode;
    public PipelineStage spawnStage;
    protected record BuildForKey(SCAIEVNode spawnNode, String isax) { }
    List<BuildForKey> buildForISAXes = new ArrayList<>();

    public RequestHistoryBuilder(SCAIEVNode spawnNode, PipelineStage spawnStage) {
      super("SpawnOutputSelectStrategy_RequestHistory(%s)".formatted(new NodeInstanceDesc.Key(spawnNode, spawnStage, "").toString(false)),
                                                                     new NodeInstanceDesc.Key(spawnNode, spawnStage, ""));
      this.spawnNode = spawnNode;
      this.spawnStage = spawnStage;
      if (!spawnNode.isSpawn() || spawnNode.isAdj())
        throw new IllegalArgumentException("spawnNode should be a non-adj spawn node");
      if (spawnStage.getKind() != StageKind.Decoupled)
        throw new IllegalArgumentException("spawnStage should be a Decoupled stage");
      if (bNodes.GetAdjSCAIEVNode(spawnNode, AdjacentNode.validHandshakeResp).isEmpty())
        throw new IllegalArgumentException("spawnNode (%s) has no validHandshakeResp adjacent".formatted(spawnNode.name));
    }
    private NodeInstanceDesc.Key getISAXKey(SCAIEVNode spawnNode, String isax) {
      return new NodeInstanceDesc.Key(requestHistoryPurpose, spawnNode, spawnStage, isax);
    }
    /**
     * To be called from within a strategy's implement method.
     * @param spawnBaseNode the actual spawn node.
     *                      Is often the same as this.spawnNode, but nodes in the same family (e.g., RdMem_spawn, WrMem_spawn)
     *                      are handled by a single RequestHistoryBuilder.
     * @param isax the ISAX to build for
     * @param out 
     * @return
     */
    public NodeInstanceDesc.Key buildFor(SCAIEVNode spawnNode, String isax, Consumer<NodeLogicBuilder> out) {
      if (!spawnNode.isSpawn() || spawnNode.isAdj())
        throw new IllegalArgumentException("spawnNode should be a non-adj spawn node");
      if (!spawnNode.equals(this.spawnNode) && (spawnNode.familyName.isEmpty() || !spawnNode.familyName.equals(this.spawnNode.familyName)))
        throw new IllegalArgumentException("spawnNode does not match the object");
      var newEntry = new BuildForKey(spawnNode, isax);
      if (!buildForISAXes.contains(newEntry)) {
        buildForISAXes.add(newEntry);
        this.trigger(out);
      }
      return getISAXKey(spawnNode, isax);
    }
    @Override
    protected NodeLogicBlock applyTriggered(NodeRegistryRO registry, int aux) {
      NodeLogicBlock ret = new NodeLogicBlock();
      String requestHistoryNameBase = "%s_%s_RequestHistory".formatted(spawnNode.name, spawnStage.getName());
      ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(requestHistoryPurpose, spawnNode, spawnStage, "", 0),
                                           requestHistoryNameBase, ExpressionType.WireName));
      //Also includes cousin nodes
      SpawnFireStrategy.getISAXPriority(bNodes, isaxesSortedByPriority, spawnNode).map(entry -> entry.getKey().name).distinct();

      if (buildForISAXes.isEmpty())
        return ret;
      String FIFOinstName = requestHistoryNameBase + "_FIFO";

      //Parameter: NR_ELEMENTS, DATAW
      //Pins: clk_i, rst_i, clear_i, write_valid_i, read_valid_i, data_i, not_empty, not_full, data_o
      //Lis anyEnqueueCond
      List<SCAIEVNode> allSpawnNodes = new ArrayList<>(); //all spawn nodes related with the node
      List<String> allISAXNames = new ArrayList<>(); //all ISAX names sitting on any of these nodes (even if not tracked in the priority encoding)
      List<String> priorityEntryIsValidCond = new ArrayList<>();
      var iterData = new Object() { int idx = 0; };
      SpawnFireStrategy.getISAXPriority(bNodes, isaxesSortedByPriority, spawnNode).forEach(priorityEntry -> {
        if (!allSpawnNodes.contains(priorityEntry.getKey()))
          allSpawnNodes.add(priorityEntry.getKey());
        allISAXNames.add(priorityEntry.getValue());
        if (buildForISAXes.contains(new BuildForKey(priorityEntry.getKey(), priorityEntry.getValue()))) {
          //- define isaxKey by checking whether the FIFO output matches the expected value
          // -> one-hot encoding
          NodeInstanceDesc.Key isaxKey = getISAXKey(priorityEntry.getKey(), priorityEntry.getValue());
          String isISAXRespWire = "%s_%d_%s".formatted(requestHistoryNameBase, iterData.idx, priorityEntry.getValue());
          ret.declarations += "logic %s;\n".formatted(isISAXRespWire);
          String isResponseForISAX = "%s_readData[%d]".formatted(FIFOinstName, iterData.idx);
          //if (iterData.idx > 0)
          //  isResponseForISAX += " && !(|%s_readData[%d-1:0])".formatted(FIFOinstName, iterData.idx);
          ret.logic += "assign %s = %s_readValid && %s;\n".formatted(isISAXRespWire, FIFOinstName, isResponseForISAX);
          ret.outputs.add(new NodeInstanceDesc(isaxKey, isISAXRespWire, ExpressionType.WireName));
          //- define the valid bit to put into the priority FIFO
          priorityEntryIsValidCond.add(
              SpawnFireStrategy.getPriorityValidReqKeysStream(Purpose.REGISTERED, bNodes, op_stage_instr, spawnStage, Stream.of(priorityEntry))
                .flatMap(keys -> keys.stream())
                .map(key -> registry.lookupExpressionRequired(key))
                .reduce((a, b) -> a + " || " + b).orElse("1'b0"));
          ++iterData.idx;
        }
      });
      if (priorityEntryIsValidCond.isEmpty())
        return ret;
      assert(!allSpawnNodes.isEmpty());
      String spawnAcceptedCond = allSpawnNodes.stream()
                                .map(spawnNode_-> bNodes.GetAdjSCAIEVNode(spawnNode, AdjacentNode.validHandshakeResp).orElseThrow().makeFamilyNode())
                                .distinct()
                                .map(handshakeRespNode -> registry.lookupRequired(new NodeInstanceDesc.Key(handshakeRespNode, spawnStage, "")))
                                .map(nodeInst -> nodeInst.getExpressionWithParens())
                                .reduce((a,b) -> a+" || "+b).orElse("1'b0");
      String spawnRespCond = allSpawnNodes.stream()
                               .map(spawnNode_-> bNodes.GetAdjSCAIEVNode(spawnNode, AdjacentNode.validResp).orElseThrow().makeFamilyNode())
                               .distinct()
                               .map(respNode -> registry.lookupRequired(new NodeInstanceDesc.Key(respNode, spawnStage, "")))
                               .map(nodeInst -> nodeInst.getExpressionWithParens())
                               .reduce((a,b) -> a+" || "+b).orElse("1'b0");

      String FIFOmoduleName = registry.lookupExpressionRequired(
          new NodeInstanceDesc.Key(Purpose.HDL_MODULE, DecoupledStandardModulesStrategy.makeFIFONode(), core.GetRootStage(), ""));

      //Construct data encoding
      int fifoWidth = priorityEntryIsValidCond.size();

      String onehotEncodeLogic = """
          always_comb begin
              %1$s_writeData = '0;
              case (1'b1)
          """.formatted(FIFOinstName);
      for (int iPriority = 0; iPriority < priorityEntryIsValidCond.size(); ++iPriority) {
        String priorityCond = priorityEntryIsValidCond.get(iPriority);
        //Create a line outputting the one-hot encoding for the current request.
        onehotEncodeLogic += "        %s: %s_writeData = %d'b%s1%s;\n".formatted(priorityCond, FIFOinstName, fifoWidth,
                                                                                 "0".repeat(fifoWidth-1 - iPriority),
                                                                                 "0".repeat(iPriority));
      }
      onehotEncodeLogic += """
              endcase
          end
          """.formatted(language.clk, language.reset, FIFOinstName);
      ret.logic += onehotEncodeLogic;
      // // Removed assertion (priority matches are possible while !spawnAllowed)
      //  `ifndef SYNTHESIS
      //  always_ff @(posedge %1$s) begin
      //      // If a priority matched, we should be posting a request to the FIFO (-> validHandshakeResp should be set).
      //      if (!%2$s && %3$s_writeData != '0 && !%3$s_writeValid)
      //          $display("ERROR: %3$s write condition is inconsistent");
      //  end
      //  `endif

      //Construct FIFO
      ret.declarations += Stream.of("clear", "writeValid", "readValid", "notEmpty", "notFull")
                                .map(x->"logic %s_%s;\n".formatted(FIFOinstName, x))
                                .reduce((a,b)->a+b).get();
      ret.declarations += "logic [%d-1:0] %s_writeData;\n".formatted(fifoWidth, FIFOinstName);
      ret.declarations += "logic [%d-1:0] %s_readData;\n".formatted(fifoWidth, FIFOinstName);
      ret.logic += "assign %s_clear = 1'b0;\n".formatted(FIFOinstName);
      ret.logic += "assign %s_writeValid = %s;\n".formatted(FIFOinstName, spawnAcceptedCond);
      ret.logic += "assign %s_readValid = %s;\n".formatted(FIFOinstName, spawnRespCond);

      int fifoDepth = cfg.spawn_input_fifo_depth;
      for (String isaxName : allISAXNames) {
        NodeInstanceDesc isaxValidCounterInst =
            registry.lookupRequired(new NodeInstanceDesc.Key(SpawnRdIValidStrategy.ISAXValidCounter, spawnStage, isaxName));
        if (isaxValidCounterInst.getKey().getNode().elements > 0)
          fifoDepth = Math.min(isaxValidCounterInst.getKey().getNode().elements, fifoDepth);
        
      }
      fifoDepth = Math.min(cfg.decoupled_parallel_max, fifoDepth);

      ret.logic += """
          %1$s #(%3$d, %4$d) %2$s (
              %5$s,
              %6$s,
              %7$s
          );
          """.formatted(FIFOmoduleName, FIFOinstName, //1,2
              fifoDepth, fifoWidth, //3,4
              language.clk, language.reset, //5,6
              Stream.of("clear", "writeValid", "readValid", "writeData", "notEmpty", "notFull", "readData")
                .map(signal -> "%s_%s".formatted(FIFOinstName, signal))
                .reduce((a,b) -> a+",\n    "+b).orElseThrow() //7
              );
      //NOTE: Makes the implicit assumption that this is only used by dynamic-latency ISAXes.
      //      -> Backpressure from SpawnOptionalInputFIFO does not count ISAXes already in this history FIFO
      //      -> If needed in the future, we should add the 'notFull' condition as backpressure,
      //         delaying the validReq (and validHandshakeResp)

      return ret;
    }
  }
  List<RequestHistoryBuilder> requestHistoryBuilders = new ArrayList<>();
  RequestHistoryBuilder getOrAddRequestHistoryBuilderFor(SCAIEVNode spawnBaseNode, PipelineStage spawnStage, Consumer<NodeLogicBuilder> out) {
    Optional<RequestHistoryBuilder> builder_opt = requestHistoryBuilders.stream().filter(builder ->
        builder.spawnStage.equals(spawnStage) &&
        (builder.spawnNode.equals(spawnBaseNode) ||
            (!builder.spawnNode.familyName.isEmpty() && builder.spawnNode.familyName.equals(spawnBaseNode.familyName))))
      .findAny();
    if (builder_opt.isPresent())
      return builder_opt.get();
    var builder = new RequestHistoryBuilder(spawnBaseNode.makeFamilyNode(), spawnStage);
    requestHistoryBuilders.add(builder);
    out.accept(builder);
    return builder;
  }

  /**
   * Handles 1-bit Core->ISAX signals based on the stored request history. Assumes that the responses follow the request order.
   * If the original request to the core is not from the given ISAX, the signal will be set to zero and otherwise passed through.
   * @param selectValidKey retrieved from a RequestHistoryBuilder
   */
  private void LogicToISAXSpawnAdj_ReqHistorySorted(NodeLogicBlock logicBlock, NodeRegistryRO registry,
      SCAIEVNode node, PipelineStage stage, String ISAX,
      NodeInstanceDesc.Key selectValidKey) {
    var outputKey = new NodeInstanceDesc.Key(node, stage, ISAX);
    String toISAXSignalName = outputKey.toString(false) + "_s";
    logicBlock.declarations += String.format("wire %s;\n", toISAXSignalName);
    logicBlock.logic += "assign %s = %s && %s;\n".formatted(
                          toISAXSignalName,
                          registry.lookupRequired(selectValidKey).getExpressionWithParens(),
                          registry.lookupRequired(new NodeInstanceDesc.Key(node.makeFamilyNode(), stage, "")).getExpressionWithParens());
    logicBlock.outputs.add(new NodeInstanceDesc(outputKey, toISAXSignalName, ExpressionType.WireName));
  }
  /**
   * Handles 1-bit Core->ISAX signals based on the static ISAX priority.
   * If the current request to the core is not from the given ISAX, the signal will be set to zero and otherwise passed through.
   */
  private void LogicToISAXSpawnAdj_PrioritySorted(NodeLogicBlock logicBlock, NodeRegistryRO registry, SCAIEVNode node, PipelineStage stage, String ISAX) {
    SCAIEVNode mainNode = (node.isAdj() ? bNodes.GetSCAIEVNode(node.nameParentNode) : node);
    var outputKey = new NodeInstanceDesc.Key(node, stage, ISAX);
    String toISAXSignalName = outputKey.toString(false) + "_s";

    // Produce the selection condition for higher-priority nodes/ISAXes.
    String priority =
        SpawnFireStrategy
            .getPriorityValidReqKeysStream(Purpose.REGISTERED, bNodes, op_stage_instr, stage,
                                           SpawnFireStrategy.getISAXPriority(bNodes, isaxesSortedByPriority, mainNode)
                                               .takeWhile(entry -> !(entry.getKey().equals(mainNode) && entry.getValue().equals(ISAX))))
            .flatMap(
                keys -> keys.stream()) // Since we're ORing all together anyway, there is no need to keep the condition key lists intact.
            .map(key -> registry.lookupExpressionRequired(key))
            .reduce("1'b0", (a, b) -> a + " || " + b);

    // Assign the result validity condition for the ISAX.
    // Example: -> Result `RdMem_spawn_ISAX` will be marked as valid for ISAX by `RdMem_spawn_validResp_ISAX == 1`.
    logicBlock.logic += String.format("assign %s = %s && !(%s) && %s && %s;\n", toISAXSignalName,
                                      registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
                                          SpawnFireStrategy.ISAX_fire2_r, stage, SpawnFireStrategy.getFireNodeSuffix(node))),
                                      priority,
                                      registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
                                          Purpose.REGISTERED, bNodes.GetAdjSCAIEVNode(mainNode, AdjacentNode.validReq).get(), stage, ISAX)),
                                      registry.lookupExpressionRequired(new NodeInstanceDesc.Key(node.makeFamilyNode(), stage, "")));

    logicBlock.declarations += String.format("wire %s;\n", toISAXSignalName);
    logicBlock.outputs.add(new NodeInstanceDesc(outputKey, toISAXSignalName, ExpressionType.WireName));
  }

  private HashSet<String> builtToCoreNodeNameSet = new HashSet<String>();
  private HashSet<String> builtToISAXNodeNameSet = new HashSet<>();

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    Iterator<NodeInstanceDesc.Key> keyIter = nodeKeys.iterator();
    while (keyIter.hasNext()) {
      NodeInstanceDesc.Key nodeKey = keyIter.next();
      if (!nodeKey.getPurpose().matches(Purpose.REGULAR) || !nodeKey.getNode().isSpawn() ||
          !(nodeKey.getStage().getKind() == StageKind.Decoupled || nodeKey.getStage().getKind() == StageKind.Core) || nodeKey.getAux() != 0)
        continue;
      if (nodeKey.getISAX().isEmpty() && nodeKey.getNode().isInput) {
        SCAIEVNode familyNode = nodeKey.getNode().makeFamilyNode();
        String builderIdentifier = String.format("%s_%s", familyNode.name, nodeKey.getStage().getName());
        //-> to core
        if (builtToCoreNodeNameSet.add(builderIdentifier)) {
          var requestedFor = new RequestedForSet();
          out.accept(NodeLogicBuilder.fromFunction("SpawnOutputSelectStrategy_toCore_" + builderIdentifier, registry -> {
            NodeLogicBlock ret = new NodeLogicBlock();
            LogicToCoreSpawn(ret, registry, requestedFor, familyNode, nodeKey.getStage());
            return ret;
          }));
        }
        keyIter.remove();
      } else if (nodeKey.getISAX().isEmpty() && !nodeKey.getNode().isInput && nodeKey.getStage().getKind() != StageKind.Decoupled &&
                 !nodeKey.getNode().equals(bNodes.ISAX_spawnAllowed) // special case exclusion
                 &&
                 bNodes.GetEquivalentNonspawnNode(nodeKey.getNode())
                     .map(nonspawnNode
                          -> !nonspawnNode.tags.contains(NodeTypeTag.defaultNotprovidedByCore) || core.GetNodes().containsKey(nonspawnNode))
                     .orElse(false)) {
        // Create the spawn node directly from the non-spawn equivalent,
        //  e.g. RdMem -> RdMem_spawn, *_validResp -> *_spawn_validResp (if non-spawn validResp is explicitly present in the core)
        SCAIEVNode nodeNonspawnAdj = bNodes.GetEquivalentNonspawnNode(nodeKey.getNode()).orElseThrow();

        String builderIdentifier = String.format("%s_%s", nodeKey.getNode().name, nodeKey.getStage().getName());
        if (builtToCoreNodeNameSet.add(builderIdentifier)) {
          var requestedFor = new RequestedForSet();
          out.accept(NodeLogicBuilder.fromFunction("SpawnOutputSelectStrategy_nonspawnToSpawn_" + builderIdentifier, registry -> {
            NodeLogicBlock ret = new NodeLogicBlock();

            NodeInstanceDesc nodespawnNodeInst =
                registry.lookupRequired(new NodeInstanceDesc.Key(nodeNonspawnAdj, nodeKey.getStage(), ""), requestedFor);
            requestedFor.addAll(nodespawnNodeInst.getRequestedFor(), true);

            String wireName = nodeKey.toString(false) + "_s";
            int nodeSize = nodespawnNodeInst.getKey().getNode().size;
            if (nodeSize > 1)
              ret.declarations += String.format("wire [%d-1:0] %s;\n", nodeSize, wireName);
            else
              ret.declarations += String.format("wire %s;\n", wireName);
            ret.logic += String.format("assign %s = %s;\n", wireName, nodespawnNodeInst.getExpression());
            ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR), wireName,
                                                 ExpressionType.WireName, requestedFor));

            return ret;
          }));
        }
        keyIter.remove();
      } else if (!nodeKey.getISAX().isEmpty() && !nodeKey.getNode().isInput) {
        //-> to ISAX
        // Do not build the same pin twice (using full name, as the ISAX does not care about node families)
        String builderIdentifier = String.format("%s_%s_%s", nodeKey.getNode().name, nodeKey.getISAX(), nodeKey.getStage().getName());
        if (builtToISAXNodeNameSet.add(builderIdentifier)) {

          if (nodeKey.getNode().DefaultMandatoryAdjSig()) {
            //validResp, validHandshakeResp
            if (nodeKey.getNode().size != 1) {
              logger.error("SpawnOutputSelectStrategy: Encountered node " + nodeKey.getNode().name + " with size " +
                           nodeKey.getNode().size + " (expected size 1).");
              continue;
            }
            if (nodeKey.getNode().getAdj() != AdjacentNode.validResp && nodeKey.getNode().getAdj() != AdjacentNode.validHandshakeResp) {
              // Placed this check, since it appears likely that if a new 'validResp'-like pin was added, this logic would not fit.
              logger.error("SpawnOutputSelectStrategy: Encountered 'mandatory adj' node " + nodeKey.getNode().name +
                           " (not supported yet).");
              continue;
            }
            if (nodeKey.getNode().getAdj() != AdjacentNode.validHandshakeResp &&
                bNodes.GetAdjSCAIEVNode(bNodes.GetNonAdjNode(nodeKey.getNode()), AdjacentNode.validHandshakeResp).isPresent()) {
              SCAIEVNode baseNode = bNodes.GetNonAdjNode(nodeKey.getNode());
              assert(!baseNode.name.isEmpty());
              // Select the correct node/ISAX based on a history FIFO
              RequestHistoryBuilder builder = getOrAddRequestHistoryBuilderFor(baseNode, nodeKey.getStage(), out);
              NodeInstanceDesc.Key selectValidKey = builder.buildFor(baseNode, nodeKey.getISAX(), out);
              out.accept(NodeLogicBuilder.fromFunction("SpawnOutputSelectStrategy_toISAX_" + builderIdentifier, registry -> {
                NodeLogicBlock ret = new NodeLogicBlock();
                LogicToISAXSpawnAdj_ReqHistorySorted(ret, registry, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX(), selectValidKey);
                return ret;
              }));
            }
            else {
              out.accept(NodeLogicBuilder.fromFunction("SpawnOutputSelectStrategy_toISAX_" + builderIdentifier, registry -> {
                NodeLogicBlock ret = new NodeLogicBlock();
                LogicToISAXSpawnAdj_PrioritySorted(ret, registry, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
                return ret;
              }));
            }

          }
          else {
            // Assign any SCAL->ISAX pin directly from the general Core->SCAL pin,
            //  aside from validResp-style nodes (handled above).
            //-> The core generally has a single SCAIE-V result port (for now).
            //    The ISAX knows when that data is valid via validResp (-> DefaultMandatoryAdjSig()).
            out.accept(NodeLogicBuilder.fromFunction("SpawnOutputSelectStrategy_toISAX_" + builderIdentifier, registry -> {
              NodeLogicBlock ret = new NodeLogicBlock();
              ret.outputs.add(new NodeInstanceDesc(
                  new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()),
                  registry.lookupExpressionRequired(new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), "")),
                  ExpressionType.AnyExpression));
              return ret;
            }));
          }
        }
        keyIter.remove();
      }
    }
  }
}
