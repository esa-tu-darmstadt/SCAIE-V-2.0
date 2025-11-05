package scaiev.scal.strategy.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.SCALUtil;
import scaiev.scal.TriggerableNodeLogicBuilder;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.pipeline.IDRetireSerializerStrategy.RetireSource;
import scaiev.scal.strategy.pipeline.NodeRegPipelineStrategy.ImplementedKeyInfo;
import scaiev.util.Log2;
import scaiev.util.Verilog;

/**
 * Strategy that implements ID-based pipelining of nodes from the previous stage.
 * <br/>
 * Requires incremental assignment and retirement of IDs matching the logical program order.
 * <br/>
 * Detailed requirements:
 * <br/>
 *   - The IDs from the given RdID SCAIEVNode are incrementally assigned (in logical instruction order), with wrap-around.
 *     If the processor does not have such an ID value natively, it needs to be tucked on in order to use this strategy.
 *     <br/>
 *   - The IDs from the given RdID SCAIEVNode are incrementally retired (in logical instruction order).
 *     <br/>
 *   - Consequently, the assign and retire stage fronts need to be in instruction order.
 *     <br/>
 *   - If there are several assign stages in the front, the instruction ordering may be random between those stages,
 *     but over all assign stages, must remain sequential across cycles. The same applies to several retire stages.
 * <br/><br/>
 * This strategy only pipelines between two consecutive pipeline stages, and can be called within NodeRegPipelineStrategy.
 * <br/>
 * If a smaller ID space is chosen than provided by the core, the strategy adds a translation table.
 * <br/>
 * This can be used if a specific set of nodes with accompanying 'valid' adjacent nodes is to be pipelined
 *  that are expected to be sparsely valid (thus, rarely run out of the smaller ID space).
 *  To get good results in this scenario, the strategy object should be restricted to select nodes.
 * <br/>
 * In this case, one ID is reserved to mark invalid core ID -> node ID assignments, so the actual buffer space is reduced by one.
 */
public class IDBasedPipelineStrategy extends MultiNodeStrategy {
  protected static final Logger logger = LogManager.getLogger();

  private static AtomicInteger nextUniqueID = new AtomicInteger(0);

  protected Verilog language;
  protected BNode bNodes;

  int uniqueID;

  SCAIEVNode node_RdID;
  NodeInstanceDesc.Key key_RdFlushID;
  int innerIDWidth;

  PipelineFront assignIDFront;
  boolean assignIDFrontIsOrdered;
  boolean assignIDFrontIsPredictable;

  PipelineFront retireIDFront;
  List<RetireSource> retireDiscardSources;

  Predicate<NodeInstanceDesc.Key> canPipelineTo;

  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param node_RdID the SCAIEVNode to read each stage's in-flight ID from, and the assigned ID in assignIDFront
   * @param key_RdFlushID the node key that specifies the next ID in assignIDFront after flushing.
   *        The value must be valid whenever RdFlush or WrFlush is set for any stage in assignIDFront.
   * @param innerIDWidth the width of the inner ID managed by this strategy, must be below or equal to node_RdID.size but above zero.
   *  If it matches node_RdID.size, no separate ID-to-ID mapping is constructed.
   *  If it is below node_RdID.size, the maximum ID ((2**innerIDWidth)-1) will get reserved as an 'invalid' marker.
   * @param assignIDFront the PipelineFront in which new IDs are to be assigned,
   *          matching the requirements from {@link IDBasedPipelineStrategy}
   * @param assignIDFrontIsOrdered if the ID values from node_RdID are guaranteed to be ascending in the order of assignIDFront.asList()
   *        [e.g. decodeB handles the instruction immediately following the one handled by decodeA, such that id@decodeB == id@decodeA+1,
   * never the other way round], set this to true. Stages that stall at a given point are ignored in this ordering. This avoids generation
   * of (inefficient) combinational ID sorting logic.
   * @param assignIDFrontIsPredictable if it is guaranteed that only a prefix of assignIDFront.asList() runs through,
   *        i.e. if any i-th stage in assignIDFront.asList() stalls, so does the i+1-th stage.
   *        May improve logic overhead in some cases.
   * @param retireIDFront the PipelineFront in which IDs are to be considered retired / no longer required,
   *          matching the requirements from {@link IDBasedPipelineStrategy}
   * @param retireDiscardSources retire sources indicating the exact IDs being flushed from the core's buffer between assignIDFront and retireIDFront.
   *                             Note: non-discard 'retiring' is inferred from instructions passing through retireIDFront.
   * @param canPipelineTo Predicate on the node key that indicates if this IDBasedPipelineStrategy instance should pipeline the given key.
   *        The key will have the purpose set to {@link IDBasedPipelineStrategy#purpose_ReadFromIDBasedPipeline}.
   */
  public IDBasedPipelineStrategy(Verilog language, BNode bNodes, SCAIEVNode node_RdID, NodeInstanceDesc.Key key_RdFlushID, int innerIDWidth,
                                 PipelineFront assignIDFront, boolean assignIDFrontIsOrdered, boolean assignIDFrontIsPredictable,
                                 PipelineFront retireIDFront, List<RetireSource> retireDiscardSources,
                                 Predicate<NodeInstanceDesc.Key> canPipelineTo) {
    this.language = language;
    this.bNodes = bNodes;

    if (assignIDFront.asList().isEmpty() || retireIDFront.asList().isEmpty()) {
      throw new IllegalArgumentException("assignIDFront, retireIDFront must not be empty");
    }
    if (assignIDFront.asList().stream().anyMatch(assignStage -> !retireIDFront.isAfter(assignStage, false))) {
      throw new IllegalArgumentException("retireIDFront must lie after all stages in assignIDFront");
    }
    if (retireIDFront.asList().stream().anyMatch(retireStage -> !assignIDFront.isBefore(retireStage, false))) {
      throw new IllegalArgumentException("assignIDFront must lie before all stages in retireIDFront");
    }

    this.uniqueID = nextUniqueID.getAndIncrement();
    this.node_RdID = node_RdID;
    this.key_RdFlushID = key_RdFlushID;
    this.innerIDWidth = innerIDWidth;
    if (innerIDWidth <= 0) {
      throw new IllegalArgumentException("innerWidth must be above zero");
    }
    if (innerIDWidth > node_RdID.size) {
      throw new IllegalArgumentException("innerWidth must not exceed node_RdID.size");
    }
    this.assignIDFront = assignIDFront;
    this.assignIDFrontIsOrdered = assignIDFrontIsOrdered || (assignIDFront.asList().size() == 1);
    this.assignIDFrontIsPredictable = assignIDFrontIsPredictable || (assignIDFront.asList().size() == 1);
    this.retireIDFront = retireIDFront;
    this.retireDiscardSources = retireDiscardSources;
    assert(retireDiscardSources.stream().allMatch(source -> source.isDiscard()));
    this.canPipelineTo = canPipelineTo;

    if (assignIDFront.asList().size() > (1 << innerIDWidth)) {
      throw new IllegalArgumentException("innerIDWidth must be large enough to fit all stages from assignIDFront");
    }
    //Key to output buildPipeRelevantCondition(...) to.
    this.node_pipeRelevant = new SCAIEVNode("ID_assigncond_" + this.uniqueID);
    if (innerIDWidth != node_RdID.size) {
      this.assignSources =
          assignIDFront.asList().stream()
              .map(assignStage ->
                  new IDMapperStrategy.IDSource(
                      new NodeInstanceDesc.Key(node_RdID, assignStage, ""),
                      Optional.empty(),
                      new NodeInstanceDesc.Key(node_pipeRelevant, assignStage, ""),
                      Optional.empty()))
              .toList();
      this.retireSources =
          retireIDFront.asList().stream()
              .map(retireStage ->
                  new IDMapperStrategy.IDSource(
                      new NodeInstanceDesc.Key(node_RdID, retireStage, ""),
                      Optional.empty(),
                      null,
                      Optional.empty()))
              .toList();
      this.idMapper = new IDMapperStrategy(language, bNodes, Optional.of(key_RdFlushID), Optional.empty(),
          innerIDWidth, assignSources, assignIDFrontIsOrdered, assignIDFrontIsPredictable, retireSources);
    }
    else
      this.idMapper = null;
  }

  /**
   * Node supplied to IDMapperStrategy's assignSource relevant condition.
   * To be filled with buildPipeRelevantCondition(...) for each assign stage.
   */
  SCAIEVNode node_pipeRelevant;

  /** Purpose required to trigger this strategy. Usually, the implement caller converts selected PIPEDIN nodes to this Purpose. */
  public static final Purpose purpose_ReadFromIDBasedPipeline =
      new Purpose("ReadFromIDBasedPipeline", true, Optional.empty(), List.of());

  private static class PipelineCondKey {
    SCAIEVNode node;
    String isax;
    int aux;
    public PipelineCondKey(SCAIEVNode node, String isax, int aux) {
      this.node = node;
      this.isax = isax;
      this.aux = aux;
    }
    @Override
    public String toString() {
      String ret = node.name;
      if (!isax.isEmpty())
        ret += "_" + isax;
      if (aux != 0)
        ret += "_" + aux;
      return ret;
    }
    @Override
    public int hashCode() {
      return Objects.hash(aux, isax, node);
    }
    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      PipelineCondKey other = (PipelineCondKey)obj;
      return aux == other.aux && Objects.equals(isax, other.isax) && Objects.equals(node, other.node);
    }
  }

  private static class BufferGroupKey {
    SCAIEVNode baseNode;
    String isax;
    int aux;
    public BufferGroupKey(SCAIEVNode baseNode, String isax, int aux) {
      this.baseNode = baseNode;
      this.isax = isax;
      this.aux = aux;
    }
    @Override
    public String toString() {
      String ret = baseNode.name;
      if (!isax.isEmpty())
        ret += "_" + isax;
      if (aux != 0)
        ret += "_" + aux;
      return ret;
    }
    @Override
    public int hashCode() {
      return Objects.hash(aux, baseNode, isax);
    }
    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      BufferGroupKey other = (BufferGroupKey)obj;
      return aux == other.aux && Objects.equals(baseNode, other.baseNode) && Objects.equals(isax, other.isax);
    }
  }

  private int getBufferDepth() {
    if (idMapper == null) {
      assert(innerIDWidth == node_RdID.size);
      return 1 << innerIDWidth;
    }
    return idMapper.getInnerIDCount();
  }

  private String buildPipeRelevantCondition(NodeRegistryRO registry, RequestedForSet requestedFor, PipelineStage stage,
                                            Predicate<PipelineCondKey> filterCond) {
    return subConditionsForIDAssign.stream()
               .filter(filterCond)
               .map(subConditionNode
                    -> registry
                           .lookupRequired(new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, subConditionNode.node,
                                                                    stage, subConditionNode.isax, subConditionNode.aux),
                                           requestedFor)
                           .getExpressionWithParens())
               .reduce((a, b) -> a + " || " + b)
               //.map(cond -> " && (" + cond + ")")
               .orElse("");
  }
  private String buildPipeCondition(NodeRegistryRO registry, RequestedForSet requestedFor, PipelineStage stage,
                                    Predicate<PipelineCondKey> filterCond, boolean checkFlush) {
    String pipeCond = SCALUtil.buildCond_StageNotStalling(bNodes, registry, stage, checkFlush, requestedFor);
    if (!forceAlwaysAssignID && idMapper != null && idMapper.hasDedicatedInvalidID()) {
      // Only write if the relevant parts of the ID assign condition apply
      String pipeRelevantCond = buildPipeRelevantCondition(registry, requestedFor, stage, filterCond);
      if (!pipeRelevantCond.isEmpty())
        pipeCond += " && (" + pipeRelevantCond + ")";
    }
    return pipeCond;
  }

  private static class PipelineToDesc {
    public PipelineToDesc(NodeInstanceDesc.Key key) {
      this.key = key;
      this.requestedFor = new RequestedForSet(key.getISAX());
    }
    NodeInstanceDesc.Key key;
    RequestedForSet requestedFor;
  }

  private class PipeBufferBuilder extends TriggerableNodeLogicBuilder {
    // If true, the write ports will be in different 'always' blocks.
    static final boolean WRITEPORTS_UNORDERED = false;

    BufferGroupKey groupKey;
    List<SCAIEVNode> allBufferedNodes = new ArrayList<>();
    PipelineFront pipelineFromFront = new PipelineFront();

    RequestedForSet commonRequestedFor = new RequestedForSet();
    List<PipelineToDesc> pipelineToDescs = new ArrayList<>();
    List<Map.Entry<NodeInstanceDesc.Key, ImplementedKeyInfo>> keyImplementations = new ArrayList<>();

    public PipeBufferBuilder(String name, NodeInstanceDesc.Key nodeKey, BufferGroupKey groupKey) {
      super(name, nodeKey);
      this.groupKey = groupKey;
    }

    @Override
    protected NodeLogicBlock applyTriggered(NodeRegistryRO registry, int aux) {
      var logicBlock = new NodeLogicBlock();
      String bufferNameBase = "buffer_" + uniqueID + "_" + groupKey.toString();
      String bufferName = bufferNameBase + "_byid";
      String bufferValidName = bufferNameBase + "_byid_valid";
      String bufferOuterIDName = bufferNameBase + "_outerid";

      List<PipelineStage> pipelineFromList =
          (overriddenPipelineFromFront != null ? overriddenPipelineFromFront : pipelineFromFront).asList();

      //Condition: Do we need to track validity for each buffer entry?
      // -> For pipelining, we can rely on the core not continuing with invalidated instruction IDs (until new data is written in the source stage).
      // -> If some logic in SCAL needs to see all pending entries, we need to add that tracking.
      boolean needsPerBufferValid = keyImplementations.stream().flatMap(keyimpl -> keyimpl.getValue().requestedGetallToPipeTo.stream())
                .map(requestedGetallKey -> requestedGetallKey.getNode())
                .anyMatch(node -> node.isValidNode() || node.getAdj() == AdjacentNode.cancelReq);

      assert (!pipelineFromList.isEmpty());
      if (pipelineFromList.isEmpty()) {
        logger.error("IDBasedPipelineStrategy has no stages to pipeline from");
        return new NodeLogicBlock();
      }

      pipelineToDescs.forEach(pipelineToDesc -> commonRequestedFor.addAll(pipelineToDesc.requestedFor, true));

      // Condition as Predicate for reuse: Checks if an entry in allBufferedNode is inferred by checking the translated ID for a magic value
      // (e.g. validReq).
      Predicate<SCAIEVNode> bufferedNodeInferredFromIDValid = bufferedNode
          -> (idMapper != null && bufferedNode.isValidNode() && !forceAlwaysAssignID && subConditionsForIDAssign.size() == 1 &&
              subConditionsForIDAssign.get(0).node.equals(bufferedNode));

      // Retrieve all inner IDs.
      String[] pipelineFromIDExpr = new String[pipelineFromList.size()];
      for (int iPipelineFrom = 0; iPipelineFrom < pipelineFromList.size(); ++iPipelineFrom) {
        NodeInstanceDesc.Key innerIDKey = new NodeInstanceDesc.Key(node_RdID, pipelineFromList.get(iPipelineFrom), "");
        if (idMapper != null) {
          IDMapperStrategy.IDSource curIDSource = idMapper.addTranslatedIDSource(innerIDKey, Optional.empty());
          innerIDKey = new NodeInstanceDesc.Key(curIDSource.node_RdInnerID, pipelineFromList.get(iPipelineFrom), "");
        }
        pipelineFromIDExpr[iPipelineFrom] = registry.lookupExpressionRequired(innerIDKey, commonRequestedFor);
      }

      // Compute the buffer offsets for all nodes.
      int[] bufferOffsets = new int[allBufferedNodes.size() + 1];
      bufferOffsets[0] = 0;
      for (int iBufferNode = 0; iBufferNode < allBufferedNodes.size(); ++iBufferNode) {
        SCAIEVNode curNode = allBufferedNodes.get(iBufferNode);
        int curSize = curNode.size;
        if (bufferedNodeInferredFromIDValid.test(curNode)) {
          assert (curSize == 1);
          curSize = 0;
        }
        bufferOffsets[iBufferNode + 1] = bufferOffsets[iBufferNode] + curSize;
      }

      int totalBufferSize = bufferOffsets[allBufferedNodes.size()];
      if (totalBufferSize > 0) {
        Predicate<PipelineCondKey> pred_FilterNodesForGroup =
            subConditionKey -> (subConditionKey.node.isAdj() ? subConditionKey.node.nameParentNode : subConditionKey.node.name)
            .equals(groupKey.baseNode.name);
        // Build the pipeline conditions for each stage in pipelineFromStage.
        String[] bufferWriteConds = pipelineFromList.stream().map(pipelineFromStage ->
                                        buildPipeCondition(registry, commonRequestedFor, pipelineFromStage,
                                          pred_FilterNodesForGroup,
                                          false /* can safely ignore flushes */)).toArray(n -> new String[n]);
        //Only if needed: Create a 'buffer entry valid' mask.
        // -> Marks each entry that still corresponds to an active instruction in the processor.
        if (needsPerBufferValid) {
          // Add the 'buffer entry valid' declaration.
          logicBlock.declarations +=
              String.format("logic [%d-1:0] %s;\n", getBufferDepth(), bufferValidName);
          Stream<String> resetAssigns = Stream.of("%s <= '0;".formatted(bufferValidName));
          if (idMapper != null) {
            // Add the 'outer ID by buffer entry' declaration.
            logicBlock.declarations +=
                String.format("logic [%d-1:0] %s[%d];\n", node_RdID.size, bufferOuterIDName, getBufferDepth());
            resetAssigns = Stream.concat(resetAssigns, Stream.of("%s <= '{default: '0};".formatted(bufferOuterIDName)));
          }
          String validBufLogic = """
              always_ff @(posedge %1$s) begin
                  if (%2$s) begin
              %3$s
                  end
                  else begin
              """.formatted(language.clk, language.reset, resetAssigns.map(line -> "        "+line).reduce((a,b)->a+"\n"+b).orElse(""));

          //Apply discards to the valid bits.
          for (int iDiscard = 0; iDiscard < retireDiscardSources.size(); ++iDiscard) {
            var discardSource = retireDiscardSources.get(iDiscard);
            assert(discardSource.is_discard);
            if (idMapper != null && discardSource instanceof IDRetireSerializerStrategy.IDAndCountRetireSource) {
              //Special case for reduced inner buffers:
              //- We receive an outer ID + count from the core, marking the discarded instructions
              //- Instead of generating a mask over the outer IDs and addressing that based on the `bufferFullIDName` lookup table,
              //   we can compare the outer ID from the lookup table directly with the range.
              var idAndCountDiscardSource = (IDRetireSerializerStrategy.IDAndCountRetireSource)discardSource;
              String countExpr = idAndCountDiscardSource.getCountExpr(registry, commonRequestedFor, false);
              int countWidth = Log2.clog2(idAndCountDiscardSource.key_id.getNode().elements + 1);
              String idExpr = registry.lookupRequired(idAndCountDiscardSource.key_id).getExpressionWithParens();

              String discardCountWire = "%s_discard_%d_count".formatted(bufferNameBase, iDiscard);
              logicBlock.declarations += "logic [%d-1:0] %s;\n".formatted(countWidth, discardCountWire);
              logicBlock.logic += "assign %s = %s;\n".formatted(discardCountWire, countExpr);

              //Set valid to 0 for all entries whose full ID is covered by the discard.
              int numInner = getBufferDepth();
              for (int iInner = 0; iInner < numInner; ++iInner) {
                validBufLogic += """
                            if (%2$s[%3$d] - %4$s < %5$s)
                                %1$s[%3$d] <= 1'b0;
                    """.formatted(bufferValidName, bufferOuterIDName, iInner,
                                  idExpr, discardCountWire);
              }
            }
            else {
              //Convert the discard source into a mask.
              String maskWireNameBase = "%s_discard_%d".formatted(bufferNameBase, iDiscard);
              String maskWire = discardSource.buildAsMask(registry, commonRequestedFor, maskWireNameBase, logicBlock);
              if (idMapper != null) {
                //Set valid to 0 for all entries whose full ID is marked in the mask.
                int numInner = getBufferDepth();
                for (int iInner = 0; iInner < numInner; ++iInner) {
                  validBufLogic += """
                              if (%4$s[%2$s[%3$d]])
                                  %1$s[%3$d] <= 1'b0;
                      """.formatted(bufferValidName, bufferOuterIDName, iInner,
                                    maskWire);
                }
              }
              else {
                //We have the same ID space as the mask, so we just AND the inverted discard mask to the valids.
                // (= keep what is not being discarded)
                validBufLogic += """
                            %1$s <= %1$s & ~%2$s;
                    """.formatted(bufferValidName, maskWire);
              }

            }
          }
          //Invalidate entries that leave the retire stage normally (i.e., active in a non-stalled, valid retire stage)
          //NOTE: Assumes that flushes are already covered by the discard sources
          //      (only covers flushes in case !RdStall&&!WrStall)
          for (PipelineStage retireStage : retireIDFront.asList()) {
            // Create the expression based on the ID.
            NodeInstanceDesc.Key innerIDKey = new NodeInstanceDesc.Key(node_RdID, retireStage, "");
            if (idMapper != null) {
              IDMapperStrategy.IDSource curIDSource = idMapper.addTranslatedIDSource(innerIDKey, Optional.empty());
              innerIDKey = new NodeInstanceDesc.Key(curIDSource.node_RdInnerID, retireStage, "");
            }
            String innerID = registry.lookupRequired(innerIDKey, commonRequestedFor).getExpressionWithParens();
            //Build condition: A instruction leaves the retire stage (i.e., not stalling).
            String notStallingAndIDValid = SCALUtil.buildCond_StageNotStalling(bNodes, registry, retireStage, needsPerBufferValid, commonRequestedFor);
            if (idMapper != null && idMapper.hasDedicatedInvalidID()) {
              //Ignore the dedicated invalid ID (could produce X-values otherwise).
              notStallingAndIDValid += " && %s != %s".formatted(innerID, idMapper.getInvalidIDExpr());
            }
            validBufLogic += """
                        if (%s)
                            %s[%s] <= 1'b0;
                """.formatted(notStallingAndIDValid, bufferValidName, innerID);
          }
          //Validate entries being written into the buffer (also invalidate if irrelevant).
          //Also set bufferFullIDName.
          for (int iPipelineFrom = 0; iPipelineFrom < pipelineFromList.size(); ++iPipelineFrom) {
            PipelineStage pipelineFromStage = pipelineFromList.get(iPipelineFrom);
            String innerID = pipelineFromIDExpr[iPipelineFrom];
            String outerIDAssignLine = "";
            if (idMapper != null) {
              //Update the outer ID mapping if we have an inner ID space.
              String outerID = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(node_RdID, pipelineFromList.get(iPipelineFrom), ""));
              outerIDAssignLine = "\n            " + "%s[%s] <= %s;".formatted(bufferOuterIDName, innerID, outerID);
            }

            String notStallingCond = SCALUtil.buildCond_StageNotStalling(bNodes, registry, pipelineFromStage, false, commonRequestedFor);
            String notFlushingCond = SCALUtil.buildCond_StageNotFlushing(bNodes, registry, pipelineFromStage, commonRequestedFor);
            String relevantCond = buildPipeRelevantCondition(registry, commonRequestedFor, pipelineFromStage, pred_FilterNodesForGroup);
            String assignVal = notFlushingCond;
            if (!relevantCond.isEmpty())
              assignVal += " && (%s)".formatted(relevantCond);
            validBufLogic += """
                        if (%s) begin
                            %s[%s] <= %s;%s
                        end
                """.formatted(notStallingCond, bufferValidName, innerID, assignVal, outerIDAssignLine);
          }
          validBufLogic += """
                  end
              end
              """;
          logicBlock.logic += validBufLogic;
        }

        // Add the buffer declaration.
        logicBlock.declarations +=
            String.format("logic [%d-1:0] %s[%d];\n", bufferOffsets[allBufferedNodes.size()], bufferName, getBufferDepth());
        // Add the declared buffer of one of the pipeline stages just to register the wire name.
        logicBlock.outputs.add(
            new NodeInstanceDesc(new NodeInstanceDesc.Key(new SCAIEVNode("IDBasedPipelineReg_" + uniqueID + "_" + groupKey.toString()),
                                                          assignIDFront.asList().get(0), ""),
                                 bufferName, ExpressionType.WireName));

        assert(pipelineFromList.size() > 0);
        String updateBufferLogic = "";
        updateBufferLogic += "if (%s) begin\n".formatted(language.reset);
        for (int iEntry = 0; iEntry < getBufferDepth(); ++iEntry) {
          //Build reset logic
          for (int iBufferNode = 0; iBufferNode < allBufferedNodes.size(); ++iBufferNode) {
            SCAIEVNode curNode = allBufferedNodes.get(iBufferNode);
            int rangeMin = bufferOffsets[iBufferNode];
            int rangeMax = bufferOffsets[iBufferNode + 1] - 1;
            if (rangeMax < rangeMin)
              continue;
            if (curNode.isValidNode() || curNode.getAdj() == AdjacentNode.cancelReq) {
              updateBufferLogic += language.tab + "%s[%d][%d:%d] <= %d'd0;\n".formatted(bufferName, iEntry, rangeMax, rangeMin, rangeMax-rangeMin + 1);
            }
          }
        }
        updateBufferLogic += "end\nelse begin\n".formatted(language.reset);
        boolean hasResetLogic = true;
        // Add logic to store all buffered nodes from all 'pipeline from' stages.
        for (int iPipelineFrom = 0; iPipelineFrom < pipelineFromList.size(); ++iPipelineFrom) {
          PipelineStage pipelineFromStage = pipelineFromList.get(iPipelineFrom);

          String curFromStageUpdatelogic = "";
          String indent = hasResetLogic ? language.tab : "";
          curFromStageUpdatelogic += String.format("%sif (%s) begin\n", indent, bufferWriteConds[iPipelineFrom]);
          curFromStageUpdatelogic += indent+language.tab + String.format("%s[%s] <= {", bufferName, pipelineFromIDExpr[iPipelineFrom]);

          boolean prependComma = false;
          // Most significant component is listed first (Verilog), so iterate in reverse.
          int bufferEndOffset = bufferOffsets[allBufferedNodes.size()] - 1;

          for (int iBufferNode = allBufferedNodes.size() - 1; iBufferNode >= 0; --iBufferNode) {
            SCAIEVNode curNode = allBufferedNodes.get(iBufferNode);
            int rangeMin = bufferOffsets[iBufferNode];
            int rangeMax = bufferOffsets[iBufferNode + 1] - 1;
            assert (rangeMax == bufferEndOffset);
            if (rangeMax < rangeMin)
              continue;
            var curNodeInst = registry.lookupRequired(
                new NodeInstanceDesc.Key(Purpose.PIPEOUT, curNode, pipelineFromStage, groupKey.isax, groupKey.aux), commonRequestedFor);
            commonRequestedFor.addAll(curNodeInst.getRequestedFor(), true);
            curFromStageUpdatelogic += String.format("%s%s", prependComma ? ", " : "", curNodeInst.getExpression());
            bufferEndOffset = rangeMin - 1;
            prependComma = true;
          }

          curFromStageUpdatelogic += "};\n";
          curFromStageUpdatelogic += indent+"end\n";
          updateBufferLogic += curFromStageUpdatelogic;

          if (WRITEPORTS_UNORDERED) {
            if (hasResetLogic)
              updateBufferLogic += "end\n";
            logicBlock.logic += language.CreateInAlways(true, updateBufferLogic);
            updateBufferLogic = "";
            hasResetLogic = false;
          }
        }
        if (!updateBufferLogic.isEmpty()) {
          if (hasResetLogic)
            updateBufferLogic += "end\n";
          logicBlock.logic += language.CreateInAlways(true, updateBufferLogic);
        }
      }

      // Add the read declarations and logic in the destination stages.
      for (PipelineToDesc pipelineToEntry : pipelineToDescs) {
        var pipelineToKey = pipelineToEntry.key;
        assert (pipelineToKey.getPurpose() == Purpose.PIPEDIN);

        int iBufferNode = allBufferedNodes.indexOf(pipelineToKey.getNode());
        assert (iBufferNode != -1);
        if (iBufferNode == -1)
          continue;

        // Declare the piped in wire.
        String namePipedin = pipelineToKey.toString(false) + "_regpipein";
        logicBlock.declarations +=
            language.CreateDeclSig(pipelineToKey.getNode(), pipelineToKey.getStage(), pipelineToKey.getISAX(), true, namePipedin);

        // Create the expression based on the ID.
        NodeInstanceDesc.Key innerIDKey = new NodeInstanceDesc.Key(node_RdID, pipelineToKey.getStage(), "");
        if (idMapper != null) {
          IDMapperStrategy.IDSource curIDSource = idMapper.addTranslatedIDSource(innerIDKey, Optional.empty());
          innerIDKey = new NodeInstanceDesc.Key(curIDSource.node_RdInnerID, pipelineToKey.getStage(), "");
        }
        String innerID = registry.lookupExpressionRequired(innerIDKey, pipelineToEntry.requestedFor);

        String assignExpression;
        if (bufferedNodeInferredFromIDValid.test(pipelineToKey.getNode())) {
          assignExpression = String.format("%s != %d'd%d", innerID, innerIDWidth, getBufferDepth());
        } else {
          assignExpression =
              String.format("%s[%s][%d:%d]", bufferName, innerID, bufferOffsets[iBufferNode + 1] - 1, bufferOffsets[iBufferNode]);
          if ((pipelineToEntry.key.getNode().isValidNode() || pipelineToEntry.key.getNode().getAdj() == AdjacentNode.cancelReq)
              && idMapper != null && idMapper.hasDedicatedInvalidID())
            assignExpression = "%s != %s && %s".formatted(innerID, idMapper.getInvalidIDExpr(), assignExpression);
        }
        logicBlock.logic += String.format("assign %s = %s;\n", namePipedin, assignExpression);

        logicBlock.outputs.add(new NodeInstanceDesc(pipelineToKey, namePipedin, ExpressionType.WireName, pipelineToEntry.requestedFor));
        logicBlock.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(pipelineToKey, purpose_ReadFromIDBasedPipeline),
                                                    namePipedin, ExpressionType.AnyExpression, pipelineToEntry.requestedFor));
      }
      //Create all requested 'Getall_ToPipeTo' nodes (if any)
      for (var pipeImplEntry : keyImplementations) {
        ImplementedKeyInfo implementation = pipeImplEntry.getValue();
        var pipelineToKey = pipeImplEntry.getKey();
        assert (pipelineToKey.getPurpose() == Purpose.PIPEDIN);

        int iBufferNode = allBufferedNodes.indexOf(pipelineToKey.getNode());
        assert(iBufferNode != -1);
        if (iBufferNode == -1)
          continue;

        for (NodeInstanceDesc.Key getallKey : implementation.requestedGetallToPipeTo) {
          assert(getallKey.equals(NodeInstanceDesc.Key.keyWithPurpose(pipelineToKey, NodeRegPipelineStrategy.Purpose_Getall_ToPipeTo)));
          SCAIEVNode nodeWithElements = SCAIEVNode.CloneNode(getallKey.getNode(), Optional.empty(), true);
          nodeWithElements.elements = getBufferDepth();
          NodeInstanceDesc.Key getallKey_elements = new NodeInstanceDesc.Key(NodeRegPipelineStrategy.Purpose_Getall_ToPipeTo,
                                                                             nodeWithElements,
                                                                             getallKey.getStage(), getallKey.getISAX(), getallKey.getAux());
          String nameAll = getallKey_elements.toString(false) + "_all";
          logicBlock.declarations += "logic [%d-1:0] %s%s;\n".formatted(getBufferDepth(),
                                                                        pipelineToKey.getNode().size > 1 ? "[%d-1:0] ".formatted(pipelineToKey.getNode().size) : "",
                                                                        nameAll);
          if (bufferedNodeInferredFromIDValid.test(pipelineToKey.getNode())) {
            assert(needsPerBufferValid);
            //Use the ID valid buffer.
            logicBlock.logic += "assign %s = %s;\n".formatted(nameAll, bufferValidName);
          }
          else {
            for (int i = 0; i < getBufferDepth(); ++i) {
              String assignExpression =
                  String.format("%s[%d][%d:%d]", bufferName, i, bufferOffsets[iBufferNode + 1] - 1, bufferOffsets[iBufferNode]);
              if (pipelineToKey.getNode().isValidNode() || pipelineToKey.getNode().getAdj() == AdjacentNode.cancelReq) {
                assert(needsPerBufferValid);
                //The expression assumes that 'i' is an inner ID translated from an outer one.
                assignExpression = "%s[%d] && %s".formatted(bufferValidName, i, assignExpression);
              }
              logicBlock.logic += "assign %s[%d] = %s;\n".formatted(nameAll, i, assignExpression);
            }
          }
          logicBlock.outputs.add(new NodeInstanceDesc(getallKey_elements, nameAll, ExpressionType.WireName));
        }
      }
      return logicBlock;
    }
  }

  public PipelineFront getAssignFront() { return this.assignIDFront; }
  private PipelineFront overriddenPipelineFromFront = null;
  /**
   * Override the stages from which the table will be updated. The default is to always update from the stages immediately preceding a read.
   * @param allowedPipelineFrom the front of 'pipeline from' stages, or null for default behavior
   * @return the old override (null if none)
   */
  public PipelineFront setPipelineFrom(PipelineFront allowedPipelineFrom) {
    PipelineFront oldFront = overriddenPipelineFromFront;
    this.overriddenPipelineFromFront = allowedPipelineFrom;
    return oldFront;
  }
  /**
   * Returns a key for the flushing information based on the table indices.
   * If used for multi-cycle resource free logic, the user can stall the assign stages while performing the operation (see {@link
   * IDBasedPipelineStrategy#getAssignFront()}). In case further flushes appear (unlikely in most microarchitectures), the bottom of the
   * range (id_from) would effectively move down, covering older instructions.
   * @param node any node in the table
   * @param isax the ISAX field associated with the table (often "")
   * @param aux the aux field associated with the table (often 0)
   * @return a key evaluating to a SystemVeilog struct variable or expression with {'flushing', 'id_from' (inclusive), 'id_to' (exclusive)}
   */
  public NodeInstanceDesc.Key getTableFlushInfoKey(SCAIEVNode node, String isax, int aux) {
    // TODO: Return a key to a struct that details the range of flushed IDs (either internal or global)
    //-> Will need a builder that creates the struct definition and an instantiation.
    // assign inst.flushing = buildAnyFlushCond(registry, assignIDFront.asList().stream(), new RequestedForSet())
    // assign inst.id_from = <has inner id> ? getRdInnerNodeFlushToIDKey() : key_RdFlushID;
    // assign inst.id_to = <TODO track this information;
    //                        need last max of node_RdInnerID across assignFront,
    //                        which depends on what the oldest/'lowest' ID is due to wrap around>
    //                      @(posedge clk) if (rst) newest_id <= 0; else if (any(valid in assignFront)) newest_id <= max(id-oldest_id in
    //                      assignFront where valid);
    //                      @(posedge clk) if (rst) oldest_id <= 0; else if (any(valid in retireIDFront)) oldest_id <= max(id-oldest_id in
    //                      retireIDFront where valid);
    throw new Error("Not implemented");
  }
  /**
   * Retrieves the key that the table builder outputs the buffer field name to.
   * Note: The table builder is only created in response to a read from the pipeline, i.e., {@link
   * IDBasedPipelineStrategy#purpose_ReadFromIDBasedPipeline}. The returned key will not resolve if no such read dependency is announced.
   * @param node any node in the table
   * @param isax the ISAX field associated with the table (often "")
   * @param aux the aux field associated with the table (often 0)
   * @return
   */
  public NodeInstanceDesc.Key getTableBufferKey(SCAIEVNode node, String isax, int aux) {
    // Use any valid stage for the table.
    PipelineStage tableStage = assignIDFront.asList().get(0);
    String groupName = getBufferGroupKeyFor(new NodeInstanceDesc.Key(Purpose.REGULAR, node, tableStage, isax, aux)).toString();
    return new NodeInstanceDesc.Key(Purpose.REGULAR, new SCAIEVNode("IDBasedPipelineReg_" + uniqueID + "_" + groupName), tableStage, "");
  }

  private NodeInstanceDesc.Key getRdInnerNodeFlushToIDKey() {
    return new NodeInstanceDesc.Key(Purpose.REGULAR, new SCAIEVNode("IDBasedPipelineReg_" + uniqueID + "_RdInnerNodeFlushToID"),
                                    assignIDFront.asList().get(0), "");
  }

  protected String buildAnyFlushCond(NodeRegistryRO registry, Stream<PipelineStage> stages, RequestedForSet requestedFor) {
    return stages
        .map(stage -> {
          return registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, stage, ""), requestedFor) +
              registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrFlush, stage, ""))
                  .map(wrflushCond -> " && !" + wrflushCond.getExpression())
                  .orElse("");
        })
        .reduce((a, b) -> "(" + a + ") || (" + b + ")")
        .get();
  }

  /**
   * Forces the assign ID sub-condition to '1'. Not applicable if idMapper == null.
   * Note: Must be explicitly applied to idMapper as well
   *       ({@link IDMapperStrategy#setForceAlwaysAssignID(boolean)}).
   */
  private boolean forceAlwaysAssignID = false;
  /** Nodes to OR to the assign ID sub-condition. */
  private List<PipelineCondKey> subConditionsForIDAssign = new ArrayList<>();

  /** The builder for node_pipeRelevant. */
  private TriggerableNodeLogicBuilder pipeRelevantBuilder = null;

  /** Buffers that are grouped together */
  private HashMap<BufferGroupKey, PipeBufferBuilder> bufferGroups = new HashMap<>();

  /** The ID mapper, non-null iff we are constructing a dedicated inner ID space. */
  private IDMapperStrategy idMapper = null;

  /** The assignSources supplied to idMapper, non-null iff idMapper is non-null */
  private List<IDMapperStrategy.IDSource> assignSources = null;
  /** The retireSources supplied to idMapper, non-null iff idMapper is non-null */
  private List<IDMapperStrategy.IDSource> retireSources = null;

  private Map<SCAIEVNode, SCAIEVNode> customGroupMapping = new HashMap<>();
  /**
   * Adds a node to group together with another, despite (possibly) not being adjacent to the reference node.
   * Note: Nodes not explicitly mapped will be treated as their own group if non-adjacent, or grouped by their parent if adjacent.
   * The caller should choose the reference node as the base node of a group, consistent with the other nodes to be included in the group.
   */
  public void mapToGroup(SCAIEVNode nodeToGroup, SCAIEVNode referenceNode) { customGroupMapping.put(nodeToGroup, referenceNode); }

  private BufferGroupKey getBufferGroupKeyFor(NodeInstanceDesc.Key nodeKey) {
    SCAIEVNode node = nodeKey.getNode();
    SCAIEVNode groupNode = customGroupMapping.get(node);
    if (groupNode == null) {
      groupNode = node;
      if (node.isAdj()) {
        groupNode = bNodes.GetSCAIEVNode(node.nameParentNode);
        if (groupNode.name.isEmpty())
          logger.warn("IDBasedPipelineStrategy: Assigning node {} to a generic group, as its parent is not registered in bNodes");
      }
    }
    return new BufferGroupKey(groupNode, nodeKey.getISAX(), nodeKey.getAux());
  }

  List<Map.Entry<NodeInstanceDesc.Key, ImplementedKeyInfo>> pendingKeyImplementations = new ArrayList<>();
  /**
   * Adds the implementation metadata of a {@link NodeRegPipelineStrategy} that this pipeliner is used for.
   * Required for the 'get entire buffer' keys with {@link NodeRegPipelineStrategy#Purpose_Getall_ToPipeTo}.
   */
  public void addKeyImplementation(NodeInstanceDesc.Key nodeKey, ImplementedKeyInfo implementation) {
    var pipelineToKey = NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.PIPEDIN);
    var groupKey = getBufferGroupKeyFor(nodeKey);

    PipeBufferBuilder groupBuilder = bufferGroups.get(groupKey);
    if (groupBuilder != null) {
      List<PipelineToDesc> targetedPipelineToDescs = groupBuilder.pipelineToDescs.stream()
                                                         .filter(pipelineToDesc -> pipelineToDesc.key.equals(pipelineToKey)).toList();
      if (!targetedPipelineToDescs.isEmpty()) {
        groupBuilder.keyImplementations.add(Map.entry(pipelineToKey, implementation));
        if (implementation.forwardRequestedFor) {
          //If requested, forward the 'requestedFor' from the source instances to the destination.
          targetedPipelineToDescs.forEach(pipelineToDesc -> pipelineToDesc.requestedFor.addAll(groupBuilder.commonRequestedFor, true));
        }
        logger.warn("Internal: IDBasedPipelineStrategy#addKeyImplementation called after builder for %s. Will not retrigger the builder.".formatted(pipelineToKey.toString(true))
                    + " This may cause missing instances with Purpose Getall_ToPipeTo.");
        return;
      }
    }

    pendingKeyImplementations.add(Map.entry(pipelineToKey, implementation));
  }

  /**
   * Implement method for the ID-based pipeliner.
   * Requests from {@link NodeRegPipelineStrategy} are allowed to be wrapped in a shared builder.
   * In that case, this strategy must still be added to the general implement call chain, as it produces its own internal dependencies.
   * @param out
   * @param nodeKeys
   * @param isLast
   */
  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    if (idMapper != null)
      idMapper.implement(out, nodeKeys, isLast);
    var nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      NodeInstanceDesc.Key nodeKey = nodeKeyIter.next();

      // Can only operate in the range from assign to retire.
      if (!assignIDFront.isAroundOrBefore(nodeKey.getStage(), false) || !retireIDFront.isAroundOrAfter(nodeKey.getStage(), false))
        continue;

      if (nodeKey.getPurpose().matches(Purpose.REGULAR) && nodeKey.getNode().equals(node_pipeRelevant)
          && assignIDFront.contains(nodeKey.getStage())) {
        if (!nodeKey.getISAX().isEmpty() || nodeKey.getAux() != 0)
          continue;
        if (pipeRelevantBuilder == null) {
          //Generate a 'pipe relevant condition' for IDMapperStrategy.
          // -> Immediately generate the condition for all assign stages.
          RequestedForSet requestedFor = new RequestedForSet();
          pipeRelevantBuilder = TriggerableNodeLogicBuilder.fromFunction(
              "IDBasedPipelineStrategy_pipeRelevant_" + nodeKey.getStage().getName(),
              nodeKey, //key to name trigger after
              registry -> {
                var ret = new NodeLogicBlock();
                for (PipelineStage assignStage : assignIDFront.asList()) {
                  String cond = buildPipeRelevantCondition(registry, requestedFor, assignStage, condKey -> true);
                  if (cond.isEmpty())
                    cond = "1'b1";
                  ret.outputs.add(new NodeInstanceDesc(
                      new NodeInstanceDesc.Key(Purpose.REGULAR, node_pipeRelevant, assignStage, ""),
                      cond,
                      ExpressionType.AnyExpression,
                      requestedFor));
                }
                return ret;
              }
          );
          out.accept(pipeRelevantBuilder);
        }
        nodeKeyIter.remove();
      }
      else if (nodeKey.getPurpose().equals(purpose_ReadFromIDBasedPipeline) && assignIDFront.isBefore(nodeKey.getStage(), false) &&
          canPipelineTo.test(nodeKey)) {

        // Get or create the builder for the group corresponding to nodeKey.
        var groupKey = getBufferGroupKeyFor(nodeKey);
        var builderAddedToOut = new Object() { boolean val = false; };
        var groupBuilder = bufferGroups.computeIfAbsent(groupKey, groupKey_ -> {
          var builder = new PipeBufferBuilder(
              String.format("IDBasedPipelineStrategy(%d)_%s", uniqueID, groupKey_.toString()),
              new NodeInstanceDesc.Key(Purpose.REGULAR, groupKey_.baseNode, nodeKey.getStage(), groupKey_.isax, groupKey_.aux), groupKey_);
          out.accept(builder);
          builderAddedToOut.val = true;

          return builder;
        });
        if (!builderAddedToOut.val) {
          // Make sure *some* builder is added to out, so NodeRegPipelineStrategy does not try building it another way.
          out.accept(NodeLogicBuilder.fromFunction(String.format("IDBasedPipelineStrategy(%d)-dummy_%s", uniqueID, nodeKey.toString()),
                                                   registry -> {
                                                     var ret = new NodeLogicBlock();
                                                     ret.treatAsNotEmpty = true;
                                                     return ret;
                                                   }));
        }

        // Add the current key to the builder as 'pipeline to' destination, and add its earlier stages as 'pipeline from' sources.
        var pipelineToKey =
            new NodeInstanceDesc.Key(Purpose.PIPEDIN, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX(), nodeKey.getAux());
        pendingKeyImplementations.stream().filter(implEntry -> implEntry.getKey().equals(pipelineToKey))
                                          .forEach(implEntry -> groupBuilder.keyImplementations.add(implEntry));
        if (!groupBuilder.pipelineToDescs.stream().anyMatch(pipelineToDesc -> pipelineToDesc.key.equals(pipelineToKey))) {
          var newPipelineToDesc = new PipelineToDesc(pipelineToKey);
          groupBuilder.pipelineToDescs.add(newPipelineToDesc);
          if (groupBuilder.keyImplementations.stream().anyMatch(implEntry -> implEntry.getKey().equals(pipelineToKey) && implEntry.getValue().forwardRequestedFor)) {
            //If requested, forward the 'requestedFor' from the source instances to the destination.
            //For simplicity, use the same requested for for all destination nodes in the group.
            newPipelineToDesc.requestedFor.addAll(groupBuilder.commonRequestedFor, true);
          }

          if (!groupBuilder.allBufferedNodes.contains(nodeKey.getNode()))
            groupBuilder.allBufferedNodes.add(nodeKey.getNode());

          // TODO: Apply pipeline from override
          List<PipelineStage> newPipelineFromList = null;
          for (PipelineStage pipelineFromStage : nodeKey.getStage().getPrev()) {
            if (!groupBuilder.pipelineFromFront.contains(pipelineFromStage) && assignIDFront.isAroundOrBefore(pipelineFromStage, false)) {
              if (newPipelineFromList == null)
                newPipelineFromList = new ArrayList<>(groupBuilder.pipelineFromFront.asList());
              newPipelineFromList.add(pipelineFromStage);
            }
          }
          if (newPipelineFromList != null)
            groupBuilder.pipelineFromFront = new PipelineFront(newPipelineFromList);

          groupBuilder.trigger(out);

          // Add the corresponding valid condition for the ID builder.
          SCAIEVNode baseNode = bNodes.GetNonAdjNode(nodeKey.getNode());
          Optional<SCAIEVNode> validBy =
              (nodeKey.getNode().isValidNode() || nodeKey.getNode().getAdj() == AdjacentNode.cancelReq)
                  ? Optional.of(nodeKey.getNode())
                  : bNodes.GetAdjSCAIEVNode(baseNode, nodeKey.getNode().validBy).filter(validByNode -> validByNode.isValidNode());

          if (!validBy.isPresent()) {
            // The valid condition is unknown, so we always have to allocate an ID.
            if (!forceAlwaysAssignID)
              bufferGroups.values().forEach(curGroupBuilder -> curGroupBuilder.trigger(out));
            forceAlwaysAssignID = true;
            if (idMapper != null) {
              idMapper.setForceAlwaysAssignID(true);
              idMapper.trigger(out);
            }
          } else {
            // If missing, add to the relevance sub condition used for node_pipeRelevant.
            var newSubcondKey = new PipelineCondKey(validBy.get(), nodeKey.getISAX(), nodeKey.getAux());
            if (!subConditionsForIDAssign.contains(newSubcondKey)) {
              subConditionsForIDAssign.add(newSubcondKey);
              // Retrigger the buffer builders, as those use the sub condition list.
              bufferGroups.values().forEach(curGroupBuilder -> curGroupBuilder.trigger(out));
              // Retrigger all node_pipeRelevant builders.
              if (pipeRelevantBuilder != null)
                pipeRelevantBuilder.trigger(out);
            }
          }
        }

        nodeKeyIter.remove();
      }
    }
  }
}
