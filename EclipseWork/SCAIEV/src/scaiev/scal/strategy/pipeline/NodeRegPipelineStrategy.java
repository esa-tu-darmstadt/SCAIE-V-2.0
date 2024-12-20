package scaiev.scal.strategy.pipeline;

import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.strategy.MultiNodeStrategy;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.TriggerableNodeLogicBuilder;
import scaiev.util.ListRemoveView;
import scaiev.util.Verilog;

/** Builds a node pipeline stage using a stage register, or falls back to implementing a new node.
 *  Should not be used across different module compositions, as it has per-composition state. */
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
	
	protected static class ImplementedKeyInfo {
		TriggerableNodeLogicBuilder triggerable = null;
		boolean allowPipein = false;
		boolean pipeliningIsRequired = false;
		List<NodeLogicBuilder> baseBuilders = new ArrayList<>();
	}
	
	/** Per-composition state: Keys already implemented */
	HashMap<NodeInstanceDesc.Key,ImplementedKeyInfo> implementedKeys = new HashMap<>();


	//TODO: Implement a per-stage selection of the implementation for pipelineBuilder_optionalSingle
	
	/**
	 * @param language The (Verilog) language object
	 * @param bNodes The BNode object for the node instantiation
	 * @param minPipeFront The minimum stages to instantiate a pipeline for.
	 * @param zeroOnFlushSrc If set, a zero value will be pipelined instead of the input value if the source stage is being flushed.
	 * @param zeroOnFlushDest If set, the pipelined value will be set to zero if the destination stage is being flushed.
	 * @param zeroOnBubble If set, the signal will be overwritten with zero if the destination stage becomes a bubble (due to source stage stalling).
	 * @param can_pipe The condition to check before instantiating pipeline builders.
	 * @param prefer_direct A condition that says whether pipeline instantiation should be done after (true) or before (false) trying direct generation through strategy_instantiateNew. 
	 * @param strategy_instantiateNew The strategy to generate a new instance;
	 *           if its implement method returns Optional.empty(), the pipeline builder will mark the prior stage node as mandatory.
	 *        Accepts a MultiNodeStrategy, but only used for one node at a time.
	 *        Important: The builders returned by a strategy invocation may get combined to a single builder,
	 *        and thus cannot rely on seeing each other's outputs in the registry.
	 *        Important: The returned builders will not be able to see the PIPEDIN variant of the same key.
	 */
	public NodeRegPipelineStrategy(Verilog language, BNode bNodes,
			PipelineFront minPipeFront, boolean zeroOnFlushSrc, boolean zeroOnFlushDest, boolean zeroOnBubble,
			Predicate<NodeInstanceDesc.Key> can_pipe, Predicate<NodeInstanceDesc.Key> prefer_direct,
			MultiNodeStrategy strategy_instantiateNew) {
		this.language = language;
		this.bNodes = bNodes;
		this.minPipeFront = minPipeFront;
		this.zeroOnFlushSrc = zeroOnFlushSrc;
		this.zeroOnFlushDest = zeroOnFlushDest;
		this.zeroOnBubble = zeroOnBubble;
		
		this.can_pipe = can_pipe;
		this.prefer_direct = prefer_direct;
		
		this.strategy_instantiateNew = strategy_instantiateNew;
	}
	
	protected static String flushstallCombineRdWrCondition(String rdExpr, Optional<NodeInstanceDesc> wrExpr) {
		return wrExpr.isEmpty() ? rdExpr : String.format("(%s || %s)", rdExpr, wrExpr.get().getExpression());
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
	protected NodeLogicBuilder makePipelineBuilder_single(NodeInstanceDesc.Key nodeKey, ImplementedKeyInfo implementation)
	{
		PipelineStage stage = nodeKey.getStage();
		assert(minPipeFront.isAroundOrBefore(stage, false));
		var stage_prev = stage.getPrev();
		if (stage_prev.size() == 0) {
			return NodeLogicBuilder.makeEmpty();
		}
		if (stage_prev.size() > 1) {
			//Could be fixable by adding a 'TOPIPEIN' Purpose or something that does all the MUXing.
			logger.error("Unsupported: Cannot select from several predecessor stages");
			return NodeLogicBuilder.makeEmpty();
		}
		PipelineStage prevStage = stage_prev.get(0);
		var requestedFor = new RequestedForSet(nodeKey.getISAX());
		boolean zeroOnFlushSrc = this.zeroOnFlushSrc;
		boolean zeroOnFlushDest = this.zeroOnFlushDest;
		boolean zeroOnBubble = this.zeroOnBubble;
		return NodeLogicBuilder.fromFunction("pipelineBuilder_single (" + nodeKey.toString(false) + ")", (NodeRegistryRO registry) -> {
			
			NodeInstanceDesc.Key nodeKey_prevStage = new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.PIPEOUT, nodeKey.getNode(), prevStage, nodeKey.getISAX(), nodeKey.getAux());
			Optional<NodeInstanceDesc> prevStageNodeInstance = registry.lookupOptionalUnique(nodeKey_prevStage, requestedFor);
			if (implementation.pipeliningIsRequired)
				registry.lookupExpressionRequired(nodeKey_prevStage);
			
			NodeLogicBlock ret = new NodeLogicBlock();
			if (prevStageNodeInstance.isPresent()) {
				//The name of the register to declare.
				String nameReg = language.CreateBasicNodeName(nodeKey.getNode(), stage, nodeKey.getISAX(), true)
					+ (nodeKey.getAux()!=0 ? "_"+nodeKey.getAux() : "")
					+ "_regpipein";
				//The expression that defines the updated register value.
				String value = prevStageNodeInstance.get().getExpression();
				boolean value_missing = prevStageNodeInstance.get().getExpression().startsWith("MISSING_");
				if (zeroOnFlushSrc) {
					//If the previous stage is being flushed and not stalling, register a zero instead of the current value.
					String rdflushPrevStage = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, prevStage, ""), requestedFor);
					Optional<NodeInstanceDesc> wrflushPrevStage = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrFlush, prevStage, ""));
					value = "("+flushstallCombineRdWrCondition(rdflushPrevStage, wrflushPrevStage)+") ? 0 : " + value;
				}
				//Add the declaration for the register.
				ret.declarations += language.CreateDeclSig(nodeKey.getNode(), stage, nodeKey.getISAX(), true, nameReg);
				
				//The conditions for whether a new value is being pipelined.
				String rdstallPrevStage = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, prevStage, ""), requestedFor);
				Optional<NodeInstanceDesc> wrstallPrevStage = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrStall, prevStage, ""));
				var pipeintoCondInst_opt = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.RdPipeInto, prevStage, "stage_"+stage.getName()));
				String pipeintoCond = pipeintoCondInst_opt.isPresent() ? String.format(" && %s", pipeintoCondInst_opt.get().getExpression()) : "";
				
				//Add the register logic.
				
				String tab = language.tab;
				String regLogic ="";
				regLogic += "always@(posedge "+language.clk+") begin\n"
						  + tab+"if ("+language.reset+")\n"
						  + tab.repeat(2)+nameReg+" <= 0;\n" //Reset value: 0
				          + tab+"else if (!("+flushstallCombineRdWrCondition(rdstallPrevStage,wrstallPrevStage)+")"+pipeintoCond+")\n"
						  + tab.repeat(2)+nameReg+" <= "+value+";\n"; //
				if (zeroOnBubble) {
					String rdstallCurStage = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, stage, ""), requestedFor);
					Optional<NodeInstanceDesc> wrstallCurStage = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrStall, stage, ""));
					//Previous stage is stalled -> no new value to pipeline.
					//Now, if the current stage is not stalling, it will become a bubble. 
					regLogic += tab+"else if (!("+flushstallCombineRdWrCondition(rdstallCurStage,wrstallCurStage)+"))\n"
					          + tab.repeat(2)+nameReg+" <= 0;\n";
				}
				if (zeroOnFlushDest) {
					String rdflushCurStage = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, stage, ""), requestedFor);
					Optional<NodeInstanceDesc> wrflushCurStage = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrFlush, stage, ""));
					//Current stage is being flushed.
					regLogic += tab+"else if ("+flushstallCombineRdWrCondition(rdflushCurStage,wrflushCurStage)+")\n"
					          + tab.repeat(2)+nameReg+" <= 0;\n";
				}
				regLogic += "end\n";
				ret.logic += regLogic;
				
				NodeInstanceDesc.Key generatedNodeKey = new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.PIPEDIN, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX(), nodeKey.getAux());
				if (value_missing) {
					//Output "MISSING_<..>" in case a rule polls for NodeRegPipelineStrategy validity.
					//Also clear the logic for the same reason, while still keeping the dependencies,
					// in case downstream node construction just needs a couple more iterations.
					ret.logic = "";
					ret.declarations = "";
					ret.outputs.add(new NodeInstanceDesc(generatedNodeKey, "MISSING_"+nameReg+"~from_previous", ExpressionType.AnyExpression, requestedFor));
				}
				else
					ret.outputs.add(new NodeInstanceDesc(generatedNodeKey, nameReg, ExpressionType.WireName, requestedFor));
			}
			
				
			return ret;
		});
	}
	
	protected boolean implementSingle(NodeInstanceDesc.Key nodeKey, Consumer<NodeLogicBuilder> out) {
		List<NodeLogicBuilder> baseBuilders = new ArrayList<>();
		ListRemoveView<NodeInstanceDesc.Key> implementKeyAsNew_RemoveView = new ListRemoveView<>(List.of(nodeKey));
		this.strategy_instantiateNew.implement(builder -> baseBuilders.add(builder), implementKeyAsNew_RemoveView, false);
		//Determine if strategy_inatantiateNew can handle the key, based on whether it removed it from the list.
		boolean canBeImplementedAsNew = implementKeyAsNew_RemoveView.isEmpty();
		if (!minPipeFront.isAroundOrBefore(nodeKey.getStage(), false)
			|| !this.can_pipe.test(nodeKey)) {
			baseBuilders.forEach(builder -> out.accept(builder));
			return canBeImplementedAsNew;
		}
		//if (!this.can_pipe.test(nodeKey))
		//	return false;
		NodeInstanceDesc.Key implementedKey = new NodeInstanceDesc.Key(Purpose.PIPEDIN, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX(), nodeKey.getAux());
		ImplementedKeyInfo implementation = implementedKeys.get(implementedKey);
		if (implementation != null) {
			//Reconfigure the existing builder.
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
				implementation.baseBuilders.addAll(baseBuilders);
				implementation.triggerable.trigger(out);
			}
			return nodeKey.getPurpose().matches(Purpose.PIPEDIN) || canBeImplementedAsNew;
		}
		implementation = new ImplementedKeyInfo();
		implementation.baseBuilders = baseBuilders;
		implementation.allowPipein = nodeKey.getPurpose().matches(Purpose.PIPEDIN);
		implementation.pipeliningIsRequired = baseBuilders.isEmpty();
		
		implementedKeys.put(implementedKey, implementation);
		
		NodeLogicBuilder pipelineBuilder_optionalSingle = this.makePipelineBuilder_single(nodeKey, implementation);
		
		
		final ImplementedKeyInfo implementation_ = implementation;
		//Pseudo logic builder that, given a requested node in stage N,
		//   adds a 'required' dependency on a previous stage's instance of the same node,
		//   in order to force building a pipeline up until stage N-1.
		//This will also trigger PIPEOUT generation in stage N-1 if only REGULAR or PIPEDIN is present.
		//If the node does not exist in any previous stage,
		//   this will only add an optional dependency on stage N-1.
		//Always outputs an empty NodeLogicBlock.
		NodeLogicBuilder pipelineBuilder_optionalCheckAny = NodeLogicBuilder.fromFunction("pipelineBuilder_optionalCheckAny (" + nodeKey.toString(false) + ")", (NodeRegistryRO registry) -> {
			PipelineStage stage = nodeKey.getStage();
			List<PipelineStage> stages_found = new ArrayList<>();
			//Go backwards, checking for existing instances of the node.
			for (PipelineStage prevStage : stage.iterablePrev_bfs(predecStage -> predecStage.getKind() != StageKind.CoreInternal
					&& !minPipeFront.contains(predecStage) //Don't iterate past minPipeFront
					)) {
				if (prevStage.getKind() == StageKind.CoreInternal)
					continue;
				if (prevStage.getPrev().stream().filter(prevprevStage -> prevprevStage.getKind() != StageKind.CoreInternal).count() > 1) {
					//This sub-builder would probably work, but force pipelining through *all* predecessor sub-graphs, which probably would be fairly inefficient.
					logger.warn("Unsupported for pipelining currently: Encountered a multi stage with multiple predecessors");
					continue;
				}
				//if (prevStage == stage)
				//	continue;
				
				NodeInstanceDesc.Key nodeKey_prevStage = new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, nodeKey.getNode(), prevStage, nodeKey.getISAX(), nodeKey.getAux());
				//This optional lookup will also be used to establish the ordering relationship,
				Optional<NodeInstanceDesc> prevStageNodeInstance = registry.lookupOptionalUnique(nodeKey_prevStage);
				if (prevStageNodeInstance.isPresent())
				{
					//Assert no other NodeLogicBuilder has output a matching node in the destination stage.
					assert(prevStage != stage || !prevStageNodeInstance.get().getKey().getPurpose().matches(Purpose.PIPEDIN));
					
					if (prevStage != stage)
						stages_found.add(prevStage);
				}
			}
			if (!stages_found.isEmpty()) {
				PipelineFront foundAtFront = new PipelineFront(stages_found);
				class IterationParam {boolean need_full_comparison = false;};
				var iterationParam = new IterationParam(); //Also accessed by lambda
				//Note: Assumes that iterablePrev_bfs calls the 'processSuccessors' lambda on a stage's successors _after_ listing the stage itself.
				//      This is for the need_full_comparison check to work as expected;
				//      however, even if this assumption were to become wrong,
				//      this would only add more discarded loop iterations (via `continue`) and not introduce faults.
				for (PipelineStage prevStage : stage.iterablePrev_bfs(prevStage -> !minPipeFront.contains(prevStage) //Don't iterate past minPipeFront
						&& (iterationParam.need_full_comparison ? foundAtFront.isBefore(prevStage, false) : !foundAtFront.contains(prevStage)) //Only iterate towards foundAtFront
						  /* Don't iterate past stages marked non-continuous,
						   * besides `stage`, where we know we need to iterate past to make any progess.
						   *  */
						&& (prevStage == stage || prevStage.getContinuous())   
						)) {
					if (prevStage.getPrev().size() > 1)
						iterationParam.need_full_comparison = true; //If there are several paths in the graph, we need to make sure we took one of the correct turns.
					if (prevStage == nodeKey.getStage())
						continue;
					if (iterationParam.need_full_comparison && !foundAtFront.isAroundOrBefore(prevStage, false))
						continue;
					NodeInstanceDesc.Key nodeKey_prevStage = new NodeInstanceDesc.Key(Purpose.PIPEOUT, nodeKey.getNode(), prevStage, nodeKey.getISAX(), nodeKey.getAux());
					registry.lookupExpressionRequired(nodeKey_prevStage);
				}
			}
			
			return new NodeLogicBlock();
		});
		
		NodeLogicBuilder defaultBuilder = NodeLogicBuilder.fromFunction("pipelineBuilder_MISSING (" + nodeKey.toString(false) + ")", (NodeRegistryRO registry) -> {
			NodeLogicBlock ret = new NodeLogicBlock();
			ret.outputs.add(new NodeInstanceDesc(implementedKey, "MISSING_"+nodeKey.toString(), ExpressionType.AnyExpression_Noparen));
			return ret;
		});

		boolean preferDirect = this.prefer_direct.test(nodeKey);
		var overallBuilder = NodeLogicBuilder.fromFunction("NodeRegPipelineStrategy||Direct ("+nodeKey.toString()+")", (registry, aux) -> {
			//If building in the current stage is preferable, try so first.
			//Also, if the current stage is marked non-continuous, first try building in the current stage.
			boolean triedDirect = false;
			NodeLogicBlock baseBlock = new NodeLogicBlock();
			if (preferDirect || !nodeKey.getStage().getContinuous()) {
				for (var baseBuilder : implementation_.baseBuilders) {
					baseBlock.addOther(baseBuilder.apply(registry, aux));
				}
				assert(!implementation_.pipeliningIsRequired || implementation_.allowPipein);
				if (!implementation_.pipeliningIsRequired && !baseBlock.isEmpty())
					return baseBlock;
				triedDirect = true;
			}
			//Next, try building a direct pipeline from the previous stage(s).
			if (implementation_.allowPipein) {
				//If there is a way to build the node in the current stage, i.e. baseBuilder is present,
				// this will add a 'required' dependency (mark required if there is no way to build in the current stage)..
				baseBlock.addOther(pipelineBuilder_optionalSingle.apply(registry, aux));
				if (!baseBlock.isEmpty())
					return baseBlock;
				baseBlock.addOther(pipelineBuilder_optionalCheckAny.apply(registry, aux));
				//If that wasn't possible, go further back and check if there is any logic block to create a pipeline from.
				if (!baseBlock.isEmpty())
					return baseBlock;
			}
			if (!triedDirect && baseBlock.isEmpty()) {
				//Since we couldn't build a pipeline, try building it directly.
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
