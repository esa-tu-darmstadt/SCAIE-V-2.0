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
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.MultiportPipeAttributes;
import scaiev.pipeline.PipelineStage.MultiportStallAttributes;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
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
import scaiev.util.JavaUtil;
import scaiev.util.ListRemoveView;
import scaiev.util.Log2;
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

    /** Set by makePipelineBuilder_single, true iff the 'getallToPipeTo' value is the same across all ports. */
    boolean getallToPipeToSharedMultiport = false;
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
      PipelineStage stage, List<PipelineStage> stagesFrom) {
    var requestedFor = new RequestedForSet(nodeKey.getISAX());
    boolean zeroOnFlushSrc = this.zeroOnFlushSrc;
    boolean zeroOnFlushDest = this.zeroOnFlushDest;
    boolean zeroOnBubble = this.zeroOnBubble;
  //Each port has its own GetallToPipeTo (which just is the currently stored value).
    implementation.getallToPipeToSharedMultiport = false;
    return NodeLogicBuilder.fromFunction("pipelineBuilder_single (" + nodeKey.toString(false) + ")", (NodeRegistryRO registry) -> {
      String tab = language.tab;

      List<NodeInstanceDesc> prevStageNodeInstances = stagesFrom.stream().map(stageFrom -> {
        NodeInstanceDesc.Key nodeKey_prevStage =
            new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.PIPEOUT, nodeKey.getNode(), stageFrom, nodeKey.getISAX(), nodeKey.getAux());
        Optional<NodeInstanceDesc> prevStageNodeInstance = registry.lookupOptionalUnique(nodeKey_prevStage, requestedFor);
        if (implementation.pipeliningIsRequired)
          registry.lookupExpressionRequired(nodeKey_prevStage);
        return prevStageNodeInstance.orElse(null);
      }).toList();
      
      NodeLogicBlock ret = new NodeLogicBlock();
      if (prevStageNodeInstances.stream().allMatch(inst -> inst != null)) {
        if (implementation.forwardRequestedFor)
          prevStageNodeInstances.forEach(prevStageNodeInstance -> requestedFor.addAll(prevStageNodeInstance.getRequestedFor(), true));
        // The name of the register to declare.
        String nameReg = language.CreateBasicNodeName(nodeKey.getNode(), stage, nodeKey.getISAX(), true) +
                         (nodeKey.getAux() != 0 ? "_" + nodeKey.getAux() : "") + "_regpipein";
        // Add the declaration for the register.
        ret.declarations += language.CreateDeclSig(nodeKey.getNode(), stage, nodeKey.getISAX(), true, nameReg);

        // Add the register logic.
        String regLogic = "";
        regLogic += "always_ff @(posedge " + language.clk + ") begin\n"
                    + tab + "if (" + language.reset + ")\n"
                    + tab.repeat(2) + nameReg + " <= 0;\n"; // Reset value: 0
        
        List<String> allStallConditions = new ArrayList<>(stagesFrom.size());

        // Register write logic for each source stage.
        for (int iFrom = 0; iFrom < stagesFrom.size(); ++iFrom) {
          NodeInstanceDesc prevStageNodeInstance = prevStageNodeInstances.get(iFrom);
          PipelineStage stageFrom = stagesFrom.get(iFrom);

          // Special rule: Rotations across ports (e.g. port 0 runs, port 1 stalls -> shift port 1 to port 0)
          // -> In this case, pipe from the stalling port whenever RdPipeInto.
          boolean isPortRotation = stageFrom.getMultiportBase() == stage.getMultiportBase();
          assert(!isPortRotation || stage.getMultiportBase().getKind() == StageKind.CoreMultiport && stage != stage.getMultiportBase());

          // The conditions for whether a new value is being pipelined.
          String stallPrevStage = isPortRotation ? "1'b0" : SCALUtil.buildCond_StageStalling(bNodes, registry, stageFrom, false, requestedFor);
          
          // Check RdPipeInto if needed, if a stage has multiple possible successors
          NodeInstanceDesc.Key pipeintoCondKey = new NodeInstanceDesc.Key(bNodes.RdPipeInto, stageFrom, "stage_" + stage.getName());
          boolean needsPipeInto = isPortRotation
              || stageFrom.getMultiportBase().getKind() == StageKind.CoreMultiport && portNeedsRdPipeInto(nodeKey.getNode(), stageFrom);
          Optional<NodeInstanceDesc> pipeintoCondInst_opt = needsPipeInto
                                                              ? Optional.of(registry.lookupRequired(pipeintoCondKey))
                                                              : registry.lookupOptionalUnique(pipeintoCondKey);
          String pipeintoCond = pipeintoCondInst_opt.isPresent() ? String.format(" && %s", pipeintoCondInst_opt.get().getExpression()) : "";

          // The expression that defines the updated register value.
          String value = prevStageNodeInstance.getExpression();
          if (zeroOnFlushSrc) {
            // If the previous stage is being flushed and not stalling, register a zero instead of the current value.
            String flushPrevStage = SCALUtil.buildCond_StageFlushing(bNodes, registry, stageFrom, requestedFor);
            value = "(" + flushPrevStage + ") ? 0 : " + value;
          }
          
          
          String pipeCond = "!(%s)%s".formatted(stallPrevStage, pipeintoCond);
          regLogic += tab + "else if (" + pipeCond + ")\n"
                      + tab.repeat(2) + nameReg + " <= " + value + ";\n"; //
          allStallConditions.add(pipeCond);
        }
        assert(allStallConditions.size() == stagesFrom.size());

        if (stagesFrom.size() > 1) {
          // Simulation 'assertion' to check that we don't get values from two different stages.
          int assertutil_validWidth = Log2.clog2(stagesFrom.size()+1);
          ret.logic += """
              `ifndef SYNTHESIS
              wire [$clog2(%3$d+1)-1:0][%3$d-1:0] NodeRegPipeline_%4$s_assertutil_valid;%5$s
              always_ff @(posedge %1$s) begin : ctx_NodeRegPipeline_%4$s_assert
                  if (!%2$s && (%6$s > %7$d'd1)) begin
                      $display("ERROR: FF for %4$s fed by several source stages at the same time");
                      $stop;
                  end
              end
              `endif
              """.formatted(language.clk, language.reset, stagesFrom.size(), nodeKey.toString(false), //1,2,3,4
                            IntStream //assign, zero-extend the individual valid conditions
                                .range(0, stagesFrom.size())
                                .mapToObj(iFrom -> "\nassign NodeRegPipeline_%s_assertutil_valid[%d] = {%d'd0,%s};"
                                                       .formatted(nodeKey.toString(false), iFrom,
                                                                  assertutil_validWidth-1, allStallConditions.get(iFrom)))
                                .reduce((a,b)->a+b).orElse(""), //5
                            IntStream //sum over all valid conditions
                                .range(0, stagesFrom.size())
                                .mapToObj(iFrom -> "NodeRegPipeline_%s_assertutil_valid[%d]"
                                                       .formatted(nodeKey.toString(false), iFrom))
                                .reduce((a,b)->a+"+"+b).orElse(""), //6
                            assertutil_validWidth);
        }

        boolean anyValueMissing = prevStageNodeInstances.stream().anyMatch(inst -> inst.getExpression().startsWith(NodeRegistry.MISSING_PREFIX));

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
        ExpressionType generatedNodeExprType = !anyValueMissing ? ExpressionType.WireName : ExpressionType.AnyExpression_Noparen;
        String generatedNodeVal = !anyValueMissing ? nameReg : String.format("%s%s~from_previous", NodeRegistry.MISSING_PREFIX, nameReg);
        if (anyValueMissing) {
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
    Iterable<PipelineStage> ports_prev = List.of();
    if (stage.getTags().contains(StageTag.MultiportPipe)) {
      if (SCALUtil.nodeIsPerPort(nodeKey.getNode(), stage.getMultiportBase())) {
        PipelineStage baseStage = stage.getMultiportBase();
        assert(baseStage == stage.getParent().orElseThrow());
        stage_prev = baseStage.getPrev();

        // Check for previous ports
        if (baseStage.getTagAttr(StageTag.MultiportStall) != null) {
          int iPort = baseStage.getChildren().indexOf(stage);
          assert(iPort != -1);
          var multiportStallAttr = baseStage.getTagAttr(StageTag.MultiportStall, PipelineStage.MultiportStallAttributes.class);
          if (iPort != -1 && multiportStallAttr.shiftUp())
            ports_prev = JavaUtil.iterableSkip(baseStage.getChildren(), iPort + 1);
        }
      }
      else
        stage_prev = List.of();
    }
    if (stage_prev.size() == 0 && (!ports_prev.iterator().hasNext() || ports_prev.iterator().next().getKind() != StageKind.Core)) {
      return NodeLogicBuilder.makeEmpty();
    }
    if (stage_prev.size() > 1) {
      // Should be fine if the core pipeline is declared with care and all RdPipeInto&&!(stalling/flushing) are mutually exclusive
      //logger.warn("Unsupported: Cannot select from several predecessor stages");
    }
    List<PipelineStage> prevStages = new ArrayList<>();
    for (PipelineStage prevStage : stage_prev) {
      if (prevStage.getKind() == StageKind.CoreMultiport && SCALUtil.nodeIsPerPort(nodeKey.getNode(), prevStage)) {
        prevStage.getChildren().stream().filter(portStage -> portStage.getKind() == StageKind.Core && portStage.hasDirectPipeTo(stage))
            .forEach(x -> prevStages.add(x));
      }
      else {
        prevStages.add(prevStage);
      }
    }
    for (PipelineStage port : ports_prev) if (port.getKind() == StageKind.Core) {
      //If the payload shifts if only the later ports of a multi-port stage stall,
      // we need to treat that like a pipeline transition.
      //Add all later ports as possible 'previous stages' to pipeline from.
      prevStages.add(port);
    }
    if (prevStages.isEmpty()) {
      logger.error("Could not find any viable stages / stage ports to pipeline from ({})", nodeKey.toString());
      return NodeLogicBuilder.makeEmpty();
    }
    return makePipelineBuilder_singleFF(nodeKey, implementation, stage, prevStages);
  }

  /**
   * Checks if a port stage needs RdPipeInto to determine where it pipes into.
   * Also considers the CoreMultiport -> CoreMultiport stage scenario.
   * @param portFrom the port stage
   * @return true iff there are multiple 'next' stages to pipe into
   */
  protected boolean portNeedsRdPipeInto(SCAIEVNode node, PipelineStage portFrom) {
    assert(portFrom.getTags().contains(StageTag.MultiportPipe));
    PipelineStage parent = portFrom.getMultiportBase();
    assert(parent != portFrom);
    //Port shift-after-stall condition / maximum distance.
    // -> If the core does shift within the multi-port stage, we may need RdPipeInto
    //    even if there is only one "true" destination stage.
    int maxShiftupDistance = 0;
    if (parent.getTagAttr(StageTag.MultiportStall, MultiportStallAttributes.class).shiftUp()) {
      int portIdx = parent.getChildren().indexOf(portFrom);
      assert(portIdx >= 0);
      //Only alter the bounds of the condition:
      // if a core (for some reason) has a bottleneck with only one stage output port,
      // we may not need RdPipeInto after all.
      maxShiftupDistance = portIdx;
    }

    //NOTE: the listCoreMultiport arg makes no difference here
    return portFrom.resolveEffectiveNext(!SCALUtil.nodeIsPerPort(node, portFrom)).count() > (1 - maxShiftupDistance);
  }
  /**
   * Returns either a list of just 'stage', if stage is not multi-port or nodeToPipe is not per-port,
   *  or a list of the port stages that can pipe into 'stageToPipeTo'.
   * @param stage the stage to pipe from, which may or may not be CoreMultiport
   * @param nodeToPipe the node to pipe
   * @param stagesToPipeTo the stages to pipe to (at least one needs to be a destination)
   * @return a list of stages
   */
  protected List<PipelineStage> portsOrStage(PipelineStage stage, SCAIEVNode nodeToPipe, List<PipelineStage> stagesToPipeTo) {
    List<PipelineStage> stagePorts;
    if (stage.getKind() == StageKind.CoreMultiport && SCALUtil.nodeIsPerPort(nodeToPipe, stage)) {
      //Only consider ports of prevStage that can actually pipe into stage.
      stagePorts = stage.getChildren().stream().filter(
            portStage -> portStage.getKind() == StageKind.Core && stagesToPipeTo.stream().anyMatch(dest -> portStage.hasDirectPipeTo(dest))
          ).toList();
    }
    else {
      stagePorts = List.of(stage);
    }
    return stagePorts;
  }

  protected boolean implementSingle(NodeInstanceDesc.Key nodeKey, Consumer<NodeLogicBuilder> out) {
    List<NodeLogicBuilder> baseBuilders = new ArrayList<>();
    ListRemoveView<NodeInstanceDesc.Key> implementKeyAsNew_RemoveView = new ListRemoveView<>(List.of(nodeKey));
    if (!nodeKey.getPurpose().matches(Purpose_Getall_ToPipeTo)) {
      if (nodeKey.getStage().getKind() == StageKind.CoreMultiport && SCALUtil.nodeIsPerPort(nodeKey.getNode(), nodeKey.getStage())) {
        //Don't pipeline a node into a CoreMultiport 'group stage' unless the node is whitelisted for that
        return false;
      }
      this.strategy_instantiateNew.implement(builder -> baseBuilders.add(builder), implementKeyAsNew_RemoveView, false);
    }
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

    // Multiport Purpose_Getall_ToPipeTo: If shared, return the from the multiport super stage.
    if (nodeKey.getStage().getKind() == StageKind.CoreMultiport && SCALUtil.nodeIsPerPort(nodeKey.getNode(), nodeKey.getStage())) {
      assert(nodeKey.getPurpose().matches(Purpose_Getall_ToPipeTo));
      assert(implementation == null);
      if (nodeKey.getPurpose().matches(Purpose_Getall_ToPipeTo)) {
        for (PipelineStage portStage : nodeKey.getStage().getChildren()) if (portStage.getKind() == StageKind.Core) {
          var portKey = new NodeInstanceDesc.Key(Purpose.PIPEDIN, nodeKey.getNode(), portStage, nodeKey.getISAX(), nodeKey.getAux());
          var portImplementation = implementedKeys.get(portKey);
          if (portImplementation != null && portImplementation.getallToPipeToSharedMultiport) {
            implementation = portImplementation;
            break;
          }
        }
      }
    }

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
          //Port stage: need to check the multiport parent for any predecessors in the graph.
          PipelineStage baseStage = (stage.getParent().isPresent() && stage.getParent().get().getKind() == StageKind.CoreMultiport)
                                        ? stage.getParent().get() : stage;

          List<PipelineStage> needsPipetoAnyOf = List.of(stage);

          //Iterable over previous ports (i.e. higher indices) of the same stage that may shift up to this stage.
          Iterable<PipelineStage> prevPortsIterable = List.of();
          if (stage != baseStage && baseStage.getTagAttr(StageTag.MultiportStall) != null) {
            if (!baseStage.getTags().contains(StageTag.InOrder))
              logger.warn("Unsupported: Pipelining to a multi-port stage that is not tagged InOrder.");
            int iPort = baseStage.getChildren().indexOf(stage);
            assert(iPort != -1);
            var multiportStallAttr = baseStage.getTagAttr(StageTag.MultiportStall, PipelineStage.MultiportStallAttributes.class);
            if (iPort != -1 && multiportStallAttr.shiftUp())
              prevPortsIterable = JavaUtil.iterableSkip(baseStage.getChildren(), iPort + 1);
          }
          //Iterable over previous stages.
          Iterable<PipelineStage> prevDiscoveryIterable = baseStage.iterablePrev_bfs(predecStage
                  -> predecStage.getKind() != StageKind.CoreInternal &&
                  !minPipeFront.contains(predecStage) // Don't iterate past minPipeFront
          );

          for (PipelineStage prevStage : JavaUtil.concatIterable(prevPortsIterable, prevDiscoveryIterable)) {
            if (prevStage == baseStage || prevStage.getKind() == StageKind.CoreInternal || prevStage.getKind() == StageKind.ISAXMux)
              continue;
            if (prevStage.getPrev().stream().filter(prevprevStage -> prevprevStage.getKind() != StageKind.CoreInternal).count() > 1) {
              // This sub-builder would probably work, but force pipelining through *all* predecessor sub-graphs, which probably would be
              // fairly inefficient.
              logger.warn("Unsupported for pipelining currently: Encountered a multi stage with multiple predecessors");
              continue;
            }
            //Check all relevant ports of prevStage, or from prevStage itself
            List<PipelineStage> prevStagePorts = portsOrStage(prevStage, nodeKey.getNode(), needsPipetoAnyOf);
            for (PipelineStage prevStagePort : prevStagePorts) {
              NodeInstanceDesc.Key nodeKey_prevStage = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
                                                                                nodeKey.getNode(), prevStagePort,
                                                                                nodeKey.getISAX(), nodeKey.getAux());
              // This optional lookup will also be used to establish the ordering relationship in construction.
              Optional<NodeInstanceDesc> prevStageNodeInstance = registry.lookupOptionalUnique(nodeKey_prevStage);
              if (prevStageNodeInstance.isPresent()) {
                // Assert no other NodeLogicBuilder has output a matching node in the destination stage.
                assert (prevStagePort != stage || !prevStageNodeInstance.get().getKey().getPurpose().matches(Purpose.PIPEDIN));
  
                if (prevStagePort != stage)
                  stages_found.add(prevStagePort);
              }
            }
            needsPipetoAnyOf = prevStagePorts;
          }
          if (!stages_found.isEmpty()) {
            PipelineFront foundAtFront = new PipelineFront(stages_found.stream().map(st->st.getMultiportBase()).distinct());
            class IterationParam {
              boolean need_full_comparison = false;
            };
            var iterationParam = new IterationParam(); // Also accessed by lambda
            needsPipetoAnyOf = List.of(stage);
            // Note: Assumes that iterablePrev_bfs calls the 'processSuccessors' lambda on a stage's successors _after_ listing the stage
            // itself.
            //       This is for the need_full_comparison check to work as expected;
            //       however, even if this assumption were to become wrong,
            //       this would only add more discarded loop iterations (via `continue`) and not introduce faults.
            Iterable<PipelineStage> prevRetraceIterable = baseStage.iterablePrev_bfs(prevStage
                -> !minPipeFront.contains(prevStage) // Don't iterate past minPipeFront
                && (iterationParam.need_full_comparison
                        ? foundAtFront.isBefore(prevStage, false)
                        : !foundAtFront.contains(prevStage)) // Only iterate towards foundAtFront
                /* Don't iterate past stages marked non-continuous,
                 * besides `stage`, where we know we need to iterate past to make any progress.
                 *  */
                && (prevStage == stage || prevStage.getContinuous()));
            for (PipelineStage prevStage : JavaUtil.concatIterable(prevPortsIterable, prevRetraceIterable)) {
              if (prevStage.getKind() == StageKind.ISAXMux)
                continue;
              if (prevStage.getPrev().size() > 1
                  || prevStage.getPrev().size() > 0 && prevStage.getPrev().get(0).getKind() == StageKind.CoreMultiport
                     && prevStage.getPrev().get(0).getChildren().size() > 1)
                iterationParam.need_full_comparison =
                    true; // If there are several paths in the graph, we need to make sure we took one of the correct turns.
              if (prevStage == stage || prevStage == baseStage)
                continue;
              if (iterationParam.need_full_comparison && !foundAtFront.isAroundOrBefore(prevStage, false))
                continue;
              //Add the dependency from all relevant ports of prevStage, or from prevStage itself
              List<PipelineStage> prevStagePorts = portsOrStage(prevStage, nodeKey.getNode(), needsPipetoAnyOf);
              for (PipelineStage prevIndivStage : prevStagePorts) {
                NodeInstanceDesc.Key nodeKey_prevStage =
                    new NodeInstanceDesc.Key(Purpose.PIPEOUT, nodeKey.getNode(), prevIndivStage, nodeKey.getISAX(), nodeKey.getAux());
                registry.lookupExpressionRequired(nodeKey_prevStage);
              }
              needsPipetoAnyOf = prevStagePorts;
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
      if (preferDirect || !nodeKey.getStage().getMultiportBase().getContinuous()) {
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
        // If that wasn't possible, go further back and check if there is any logic block to create a pipeline from.
        baseBlock.addOther(pipelineBuilder_optionalCheckAny.apply(registry, aux));
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
