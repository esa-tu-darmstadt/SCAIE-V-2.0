package scaiev.scal.strategy.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistry;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.SCALUtil;
import scaiev.scal.TriggerableNodeLogicBuilder;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.util.ListRemoveView;
import scaiev.util.Verilog;

/**
 * Builds a node pipeline stage using a stage register, or falls back to implementing a new node.
 *  Should not be used across different module compositions, as it has per-composition state.
 */
public class NodeRegPipelineStrategy extends MultiNodeStrategy {
  protected static final Logger logger = LogManager.getLogger();

  protected Verilog language;
  protected BNode bNodes;
  protected PipelineFront minPipeFront;
  /** See constructor for details. Can be changed before makePipelineBuilder_single, won't apply to previously created builders. */
  protected boolean zeroOnFlushSrc;
  /** See constructor for details. Can be changed before makePipelineBuilder_single, won't apply to previously created builders. */
  protected boolean zeroOnFlushDest;
  /** See constructor for details. Can be changed before makePipelineBuilder_single, won't apply to previously created builders. */
  protected boolean zeroOnBubble;

  protected Predicate<NodeInstanceDesc.Key> can_pipe;
  protected Predicate<NodeInstanceDesc.Key> prefer_direct;

  protected MultiNodeStrategy strategy_instantiateNew;

  protected boolean forwardRequestedFor;

  /**
   * Carries metadata for an implemented key (specific to each adjacent node).
   * For internal use by NodeRegPipelineStrategy and any overriding pipelining implementations.
   */
  public static class ImplementedKeyInfo {
    /**
     * A triggerable builder wrapping several builders (pipelining / non-pipelining implementation).
     * The builders wrapped by this must not depend on another.
     * If a pipelining primitive wants to create builders depending on another,
     *   it should only create the main builder for the key and instantiate the rest through separate calls of {@link MultiNodeStrategy#implement(Consumer, Iterable, boolean)}.
     * <br/>
     * Note: This is initially null.
     */
    TriggerableNodeLogicBuilder triggerable = null;
    /**
     * Internal tracking for NodeRegPipelineStrategy. Indicates if a PIPEDIN instance has been requested.
     */
    boolean allowPipein = false;
    /**
     * Internal tracking for NodeRegPipelineStrategy. Indicates if the key must always be pipelined.
     */
    boolean pipeliningIsRequired = false;
    /**
     * Indicates if the node instance in the destination stage should receive
     * the 'requestedFor' entries from the source instance.
     */
    boolean forwardRequestedFor = false;
    /**
     * Internal tracking for NodeRegPipelineStrategy.
     * Possibly: Builders that can implement the node in the given stage without pipelining
     *           (should not be a concern for pipelining primitives)
     */
    List<NodeLogicBuilder> baseBuilders = new ArrayList<>();

    /** 
     * The list of all requested Purpose_Getall_ToPipeTo nodes associated with the builder.
     * The {@link #triggerable} builder will be triggered if the set changes.
     */
    Set<NodeInstanceDesc.Key> requestedGetallToPipeTo = new HashSet<>();
  }

  /** Per-composition state: Keys already implemented */
  HashMap<NodeInstanceDesc.Key, ImplementedKeyInfo> implementedKeys = new HashMap<>();

  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param minPipeFront The minimum stages to instantiate a pipeline for.
   * @param zeroOnFlushSrc If set, a zero value will be pipelined instead of the input value if the source stage is being flushed.
   * @param zeroOnFlushDest If set, the pipelined value will be set to zero if the destination stage is being flushed.
   * @param zeroOnBubble If set, the signal will be overwritten with zero if the destination stage becomes a bubble (due to source stage
   *     stalling).
   * @param can_pipe The condition to check before instantiating pipeline builders.
   * @param prefer_direct A condition that says whether pipeline instantiation should be done after (true) or before (false) trying direct
   *     generation through strategy_instantiateNew.
   * @param strategy_instantiateNew The strategy to generate a new instance;
   *           if its implement method returns Optional.empty(), the pipeline builder will mark the prior stage node as mandatory.
   *        Accepts a MultiNodeStrategy, but only used for one node at a time.
   *        Important: The builders returned by a strategy invocation may get combined to a single builder,
   *        and thus cannot rely on seeing each other's outputs in the registry.
   *        Important: The returned builders will not be able to see the PIPEDIN variant of the same key.
   * @param forwardRequestedFor If true, adds the source stage's requestedFor set to the node in the destination stage.
   */
  public NodeRegPipelineStrategy(Verilog language, BNode bNodes, PipelineFront minPipeFront, boolean zeroOnFlushSrc,
                                 boolean zeroOnFlushDest, boolean zeroOnBubble, Predicate<NodeInstanceDesc.Key> can_pipe,
                                 Predicate<NodeInstanceDesc.Key> prefer_direct, MultiNodeStrategy strategy_instantiateNew,
                                 boolean forwardRequestedFor) {
    this.language = language;
    this.bNodes = bNodes;
    this.minPipeFront = minPipeFront;
    this.zeroOnFlushSrc = zeroOnFlushSrc;
    this.zeroOnFlushDest = zeroOnFlushDest;
    this.zeroOnBubble = zeroOnBubble;

    this.can_pipe = can_pipe;
    this.prefer_direct = prefer_direct;

    this.strategy_instantiateNew = strategy_instantiateNew;

    this.forwardRequestedFor = forwardRequestedFor;
  }

  /**
   * A special Purpose to read the pipelining buffers.
   * If there are several elements in the buffer to a given stage, the resulting node's {@link SCAIEVNode#elements} field will be set accordingly;
   * in that case, the resulting expression will be multi-dimensional.
   * {@link SCAIEVNode#elements} values below 2 indicate a single element and a single dimension.
   * Note: If there is no pipeliner for the given key, the lookup with this Purpose value will not resolve (i.e., NodeRegistry will create a stub with {@link NodeRegistry#MISSING_PREFIX}).
   */
  public static final NodeInstanceDesc.Purpose Purpose_Getall_ToPipeTo = new NodeInstanceDesc.Purpose("Getall_ToPipeTo", true, Optional.empty(), List.of());

  /**
   * Constructs a NodeLogicBuilder that builds a simple register implementation using RdStall.
   *   Additionally, if baseBuilders is empty, it will mark the node in stageFrom as required.
   * See {@link NodeRegPipelineStrategy#makePipelineBuilder_single(scaiev.scal.NodeInstanceDesc.Key, ImplementedKeyInfo)}.
   * @return a valid NodeLogicBuilder
   */
  protected NodeLogicBuilder makePipelineBuilder_singleFF(NodeInstanceDesc.Key nodeKey, ImplementedKeyInfo implementation,
      PipelineStage stage, PipelineStage stageFrom) {
    var requestedFor = new RequestedForSet(nodeKey.getISAX());
    boolean zeroOnFlushSrc = this.zeroOnFlushSrc;
    boolean zeroOnFlushDest = this.zeroOnFlushDest;
    boolean zeroOnBubble = this.zeroOnBubble;
    return NodeLogicBuilder.fromFunction("pipelineBuilder_single (" + nodeKey.toString(false) + ")", (NodeRegistryRO registry) -> {
      NodeInstanceDesc.Key nodeKey_prevStage =
          new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.PIPEOUT, nodeKey.getNode(), stageFrom, nodeKey.getISAX(), nodeKey.getAux());
      Optional<NodeInstanceDesc> prevStageNodeInstance = registry.lookupOptionalUnique(nodeKey_prevStage, requestedFor);
      if (implementation.pipeliningIsRequired)
        registry.lookupExpressionRequired(nodeKey_prevStage);

      NodeLogicBlock ret = new NodeLogicBlock();
      if (prevStageNodeInstance.isPresent()) {
        if (implementation.forwardRequestedFor)
          requestedFor.addAll(prevStageNodeInstance.get().getRequestedFor(), true);
        // The name of the register to declare.
        String nameReg = language.CreateBasicNodeName(nodeKey.getNode(), stage, nodeKey.getISAX(), true) +
                         (nodeKey.getAux() != 0 ? "_" + nodeKey.getAux() : "") + "_regpipein";
        // The expression that defines the updated register value.
        String value = prevStageNodeInstance.get().getExpression();
        boolean value_missing = prevStageNodeInstance.get().getExpression().startsWith(NodeRegistry.MISSING_PREFIX);
        if (zeroOnFlushSrc) {
          // If the previous stage is being flushed and not stalling, register a zero instead of the current value.
          String flushPrevStage = SCALUtil.buildCond_StageFlushing(bNodes, registry, stageFrom, requestedFor);
          value = "(" + flushPrevStage + ") ? 0 : " + value;
        }
        // Add the declaration for the register.
        ret.declarations += language.CreateDeclSig(nodeKey.getNode(), stage, nodeKey.getISAX(), true, nameReg);

        // The conditions for whether a new value is being pipelined.
        String stallPrevStage = SCALUtil.buildCond_StageStalling(bNodes, registry, stageFrom, false, requestedFor);
        var pipeintoCondInst_opt =
            registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.RdPipeInto, stageFrom, "stage_" + stage.getName()));
        String pipeintoCond = pipeintoCondInst_opt.isPresent() ? String.format(" && %s", pipeintoCondInst_opt.get().getExpression()) : "";

        // Add the register logic.

        String tab = language.tab;
        String regLogic = "";
        regLogic += "always@(posedge " + language.clk + ") begin\n" + tab + "if (" + language.reset + ")\n" + tab.repeat(2) + nameReg +
                    " <= 0;\n" // Reset value: 0
                    + tab + "else if (!(" + stallPrevStage + ")" + pipeintoCond +
                    ")\n" + tab.repeat(2) + nameReg + " <= " + value + ";\n"; //
        boolean doZeroOnBubble = zeroOnBubble || !implementation.requestedGetallToPipeTo.isEmpty()
                                                 && (nodeKey.getNode().getAdj().isValidMarker() || nodeKey.getNode().getAdj() == AdjacentNode.cancelReq);
        if (doZeroOnBubble) {
          String stallCurStage = SCALUtil.buildCond_StageStalling(bNodes, registry, stage, false, requestedFor);
          // Previous stage is stalled -> no new value to pipeline.
          // Now, if the current stage is not stalling, it will become a bubble.
          regLogic += tab + "else if (!(" + stallCurStage + "))\n" + tab.repeat(2) +
                      nameReg + " <= 0;\n";
        }
        if (zeroOnFlushDest) {
          String flushCurStage = SCALUtil.buildCond_StageFlushing(bNodes, registry, stage, requestedFor);
          // Current stage is being flushed.
          regLogic += tab + "else if (" + flushCurStage + ")\n" + tab.repeat(2) +
                      nameReg + " <= 0;\n";
        }
        regLogic += "end\n";
        ret.logic += regLogic;

        NodeInstanceDesc.Key generatedNodeKey = new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.PIPEDIN, nodeKey.getNode(),
                                                                         nodeKey.getStage(), nodeKey.getISAX(), nodeKey.getAux());
        ExpressionType generatedNodeExprType = !value_missing ? ExpressionType.WireName : ExpressionType.AnyExpression_Noparen;
        String generatedNodeVal = !value_missing ? nameReg : String.format("%s%s~from_previous", NodeRegistry.MISSING_PREFIX, nameReg);
        if (value_missing) {
          // Output NodeRegistry.MISSING_PREFIX+"<..>" in case a rule polls for NodeRegPipelineStrategy validity.
          // Also clear the logic for the same reason, while still keeping the dependencies,
          //  in case downstream node construction just needs a couple more iterations.
          ret.logic = "";
          ret.declarations = "";
        }
        ret.outputs.add(new NodeInstanceDesc(generatedNodeKey, generatedNodeVal, generatedNodeExprType, requestedFor));

        for (NodeInstanceDesc.Key getallToPipeToKey : implementation.requestedGetallToPipeTo) {
          assert(getallToPipeToKey.getNode().equals(nodeKey.getNode()));
          assert(getallToPipeToKey.getStage().equals(nodeKey.getStage()));
          assert(getallToPipeToKey.getISAX().equals(nodeKey.getISAX()) && getallToPipeToKey.getAux() == nodeKey.getAux());
          var getallOutputKey = new NodeInstanceDesc.Key(getallToPipeToKey.getPurpose(), nodeKey.getNode(), getallToPipeToKey.getStage(),
                                                         getallToPipeToKey.getISAX(), getallToPipeToKey.getAux());
          if (nodeKey.getNode().getAdj().isValidMarker() || nodeKey.getNode().getAdj() == AdjacentNode.cancelReq) {
            String inStageValid = registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdInStageValid, getallToPipeToKey.getStage(), ""))
                                      .getExpressionWithParens();
            ret.outputs.add(new NodeInstanceDesc(getallOutputKey, generatedNodeVal + " && " + inStageValid, ExpressionType.AnyExpression, requestedFor));
          }
          else {
            ret.outputs.add(new NodeInstanceDesc(getallOutputKey, generatedNodeVal, ExpressionType.AnyExpression_Noparen, requestedFor));
          }
         }
      }

      return ret;
    });
  }

  /**
   * Constructs a NodeLogicBuilder that optionally builds the node by pipelining from the previous stage.
   *   The default implementation builds a simple register implementation using RdStall.
   *   Additionally, if baseBuilder is empty, it will mark the node in stage N-1 as required.
   * @param nodeKey
   * @param implementation the requiresPipelining field indicates whether the node should be marked as required in the previous stage,
   *                             so it will be built to finally enable building this pipeline.
   * @return a valid NodeLogicBuilder
   */
  protected NodeLogicBuilder makePipelineBuilder_single(NodeInstanceDesc.Key nodeKey, ImplementedKeyInfo implementation) {
    PipelineStage stage = nodeKey.getStage();
    assert (minPipeFront.isAroundOrBefore(stage, false));
    var stage_prev = stage.getPrev();
    if (stage_prev.size() == 0) {
      return NodeLogicBuilder.makeEmpty();
    }
    if (stage_prev.size() > 1) {
      // Could be fixable by adding a 'TOPIPEIN' Purpose or something that does all the MUXing.
      logger.error("Unsupported: Cannot select from several predecessor stages");
      return NodeLogicBuilder.makeEmpty();
    }
    PipelineStage prevStage = stage_prev.get(0);
    return makePipelineBuilder_singleFF(nodeKey, implementation, stage, prevStage);
  }

  protected boolean implementSingle(NodeInstanceDesc.Key nodeKey, Consumer<NodeLogicBuilder> out) {
    List<NodeLogicBuilder> baseBuilders = new ArrayList<>();
    ListRemoveView<NodeInstanceDesc.Key> implementKeyAsNew_RemoveView = new ListRemoveView<>(List.of(nodeKey));
    if (!nodeKey.getPurpose().matches(Purpose_Getall_ToPipeTo))
      this.strategy_instantiateNew.implement(builder -> baseBuilders.add(builder), implementKeyAsNew_RemoveView, false);
    // Determine if strategy_instantiateNew can handle the key, based on whether it removed it from the list.
    boolean canBeImplementedAsNew = implementKeyAsNew_RemoveView.isEmpty();
    if (!minPipeFront.isAroundOrBefore(nodeKey.getStage(), false) || !this.can_pipe.test(nodeKey)) {
      baseBuilders.forEach(builder -> out.accept(builder));
      return canBeImplementedAsNew;
    }
    // if (!this.can_pipe.test(nodeKey))
    //	return false;
    NodeInstanceDesc.Key implementedKey =
        new NodeInstanceDesc.Key(Purpose.PIPEDIN, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX(), nodeKey.getAux());
    ImplementedKeyInfo implementation = implementedKeys.get(implementedKey);
    if (implementation != null) {
      // Reconfigure the existing builder.
      if (!implementation.pipeliningIsRequired && baseBuilders.isEmpty()) {
        implementation.pipeliningIsRequired = true;
        implementation.allowPipein = true;
        implementation.triggerable.trigger(out);
      }
      if (!implementation.allowPipein && nodeKey.getPurpose().matches(Purpose.PIPEDIN)) {
        implementation.allowPipein = true;
        implementation.triggerable.trigger(out);
      }
      if (!baseBuilders.isEmpty()) {
        assert(!nodeKey.getPurpose().matches(Purpose_Getall_ToPipeTo));
        implementation.baseBuilders.addAll(baseBuilders);
        implementation.triggerable.trigger(out);
      }
      if (nodeKey.getPurpose().matches(Purpose_Getall_ToPipeTo)) {
        implementation.requestedGetallToPipeTo.add(nodeKey);
        implementation.triggerable.trigger(out);
      }
      return nodeKey.getPurpose().matches(Purpose.PIPEDIN) || nodeKey.getPurpose().matches(Purpose_Getall_ToPipeTo) || canBeImplementedAsNew;
    }
    if (nodeKey.getPurpose().matches(Purpose_Getall_ToPipeTo))
      return false; //Will not create a pipeliner just for Purpose_Getall_ToPipeTo.
    implementation = new ImplementedKeyInfo();
    implementation.baseBuilders = baseBuilders;
    implementation.allowPipein = nodeKey.getPurpose().matches(Purpose.PIPEDIN);
    implementation.pipeliningIsRequired = baseBuilders.isEmpty();
    implementation.forwardRequestedFor = this.forwardRequestedFor;

    implementedKeys.put(implementedKey, implementation);

    NodeLogicBuilder pipelineBuilder_optionalSingle = this.makePipelineBuilder_single(nodeKey, implementation);

    final ImplementedKeyInfo implementation_ = implementation;
    // Pseudo logic builder that, given a requested node in stage N,
    //    adds a 'required' dependency on a previous stage's instance of the same node,
    //    in order to force building a pipeline up until stage N-1.
    // This will also trigger PIPEOUT generation in stage N-1 if only REGULAR or PIPEDIN is present.
    // If the node does not exist in any previous stage,
    //    this will only add an optional dependency on stage N-1.
    // Always outputs an empty NodeLogicBlock.
    NodeLogicBuilder pipelineBuilder_optionalCheckAny =
        NodeLogicBuilder.fromFunction("pipelineBuilder_optionalCheckAny (" + nodeKey.toString(false) + ")", (NodeRegistryRO registry) -> {
          PipelineStage stage = nodeKey.getStage();
          List<PipelineStage> stages_found = new ArrayList<>();
          // Go backwards, checking for existing instances of the node.
          for (PipelineStage prevStage :
               stage.iterablePrev_bfs(predecStage
                                      -> predecStage.getKind() != StageKind.CoreInternal &&
                                             !minPipeFront.contains(predecStage) // Don't iterate past minPipeFront
                                      )) {
            if (prevStage.getKind() == StageKind.CoreInternal)
              continue;
            if (prevStage.getPrev().stream().filter(prevprevStage -> prevprevStage.getKind() != StageKind.CoreInternal).count() > 1) {
              // This sub-builder would probably work, but force pipelining through *all* predecessor sub-graphs, which probably would be
              // fairly inefficient.
              logger.warn("Unsupported for pipelining currently: Encountered a multi stage with multiple predecessors");
              continue;
            }
            // if (prevStage == stage)
            //	continue;

            NodeInstanceDesc.Key nodeKey_prevStage = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, nodeKey.getNode(),
                                                                              prevStage, nodeKey.getISAX(), nodeKey.getAux());
            // This optional lookup will also be used to establish the ordering relationship,
            Optional<NodeInstanceDesc> prevStageNodeInstance = registry.lookupOptionalUnique(nodeKey_prevStage);
            if (prevStageNodeInstance.isPresent()) {
              // Assert no other NodeLogicBuilder has output a matching node in the destination stage.
              assert (prevStage != stage || !prevStageNodeInstance.get().getKey().getPurpose().matches(Purpose.PIPEDIN));

              if (prevStage != stage)
                stages_found.add(prevStage);
            }
          }
          if (!stages_found.isEmpty()) {
            PipelineFront foundAtFront = new PipelineFront(stages_found);
            class IterationParam {
              boolean need_full_comparison = false;
            };
            var iterationParam = new IterationParam(); // Also accessed by lambda
            // Note: Assumes that iterablePrev_bfs calls the 'processSuccessors' lambda on a stage's successors _after_ listing the stage
            // itself.
            //       This is for the need_full_comparison check to work as expected;
            //       however, even if this assumption were to become wrong,
            //       this would only add more discarded loop iterations (via `continue`) and not introduce faults.
            for (PipelineStage prevStage :
                 stage.iterablePrev_bfs(prevStage
                                        -> !minPipeFront.contains(prevStage) // Don't iterate past minPipeFront
                                               && (iterationParam.need_full_comparison
                                                       ? foundAtFront.isBefore(prevStage, false)
                                                       : !foundAtFront.contains(prevStage)) // Only iterate towards foundAtFront
                                               /* Don't iterate past stages marked non-continuous,
                                                * besides `stage`, where we know we need to iterate past to make any progess.
                                                *  */
                                               && (prevStage == stage || prevStage.getContinuous()))) {
              if (prevStage.getPrev().size() > 1)
                iterationParam.need_full_comparison =
                    true; // If there are several paths in the graph, we need to make sure we took one of the correct turns.
              if (prevStage == nodeKey.getStage())
                continue;
              if (iterationParam.need_full_comparison && !foundAtFront.isAroundOrBefore(prevStage, false))
                continue;
              NodeInstanceDesc.Key nodeKey_prevStage =
                  new NodeInstanceDesc.Key(Purpose.PIPEOUT, nodeKey.getNode(), prevStage, nodeKey.getISAX(), nodeKey.getAux());
              registry.lookupExpressionRequired(nodeKey_prevStage);
            }
          }

          return new NodeLogicBlock();
        });

    NodeLogicBuilder defaultBuilder =
        NodeLogicBuilder.fromFunction("pipelineBuilder_MISSING (" + nodeKey.toString(false) + ")", (NodeRegistryRO registry) -> {
          NodeLogicBlock ret = new NodeLogicBlock();
          ret.outputs.add(new NodeInstanceDesc(implementedKey, NodeRegistry.MISSING_PREFIX + nodeKey.toString(), ExpressionType.AnyExpression_Noparen));
          return ret;
        });

    boolean preferDirect = this.prefer_direct.test(nodeKey);
    var overallBuilder = NodeLogicBuilder.fromFunction("NodeRegPipelineStrategy||Direct (" + nodeKey.toString() + ")", (registry, aux) -> {
      // If building in the current stage is preferable, try so first.
      // Also, if the current stage is marked non-continuous, first try building in the current stage.
      boolean triedDirect = false;
      NodeLogicBlock baseBlock = new NodeLogicBlock();
      if (preferDirect || !nodeKey.getStage().getContinuous()) {
        for (var baseBuilder : implementation_.baseBuilders) {
          baseBlock.addOther(baseBuilder.apply(registry, aux));
        }
        assert (!implementation_.pipeliningIsRequired || implementation_.allowPipein);
        if (!implementation_.pipeliningIsRequired && !baseBlock.isEmpty())
          return baseBlock;
        triedDirect = true;
      }
      // Next, try building a direct pipeline from the previous stage(s).
      if (implementation_.allowPipein) {
        // If there is a way to build the node in the current stage, i.e. baseBuilder is present,
        //  this will add a 'required' dependency (mark required if there is no way to build in the current stage)..
        baseBlock.addOther(pipelineBuilder_optionalSingle.apply(registry, aux));
        if (!baseBlock.isEmpty())
          return baseBlock;
        baseBlock.addOther(pipelineBuilder_optionalCheckAny.apply(registry, aux));
        // If that wasn't possible, go further back and check if there is any logic block to create a pipeline from.
        if (!baseBlock.isEmpty())
          return baseBlock;
      }
      if (!triedDirect && baseBlock.isEmpty()) {
        // Since we couldn't build a pipeline, try building it directly.
        for (var baseBuilder : implementation_.baseBuilders) {
          baseBlock.addOther(baseBuilder.apply(registry, aux));
        }
        if (!baseBlock.isEmpty())
          return baseBlock;
      }
      return defaultBuilder.apply(registry, aux);
    });
    implementation.triggerable = TriggerableNodeLogicBuilder.makeWrapper(overallBuilder, nodeKey);
    out.accept(implementation.triggerable);

    return true;
  }

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    Iterator<NodeInstanceDesc.Key> nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      var nodeKey = nodeKeyIter.next();
      if (implementSingle(nodeKey, out))
        nodeKeyIter.remove();
    }
  }
}
