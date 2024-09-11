package scaiev.scal.strategy.standard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.scal.CombinedNodeLogicBuilder;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAL.RdIValidStageDesc;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.util.ListRemoveView;
import scaiev.util.Verilog;

/** Implements RdIValid with pipelining support, also taking combinational 'RdFlush' and core-specific conditions of the destination stage into account. */
public class PipeliningRdIValidStrategy extends MultiNodeStrategy {

	// logging
	protected static final Logger logger = LogManager.getLogger();
	
	StrategyBuilders strategyBuilders;
	Verilog language;
	BNode bNodes;
	Core core;
	PipelineFront minPipelineFront;
	HashMap<String,SCAIEVInstr> allISAXes;
	Function<PipelineStage,RdIValidStageDesc> stage_getRdIValidDesc;
	
	MultiNodeStrategy ivalidStrategy;
	/**
	 * @param strategyBuilders The StrategyBuilders object to build sub-strategies with
	 * @param language The (Verilog) language object
	 * @param bNodes The BNode object for the node instantiation
	 * @param core The core node description
	 * @param minPipeFront The minimum stages to instantiate an RdIValid pipeline for
	 * @param allISAXes The ISAX descriptions
	 * @param stage_getRdIValidDesc A stage mapping providing additional conditional expressions to RdIValid per ISAX
	 */
	public PipeliningRdIValidStrategy(StrategyBuilders strategyBuilders, 
			Verilog language, BNode bNodes, Core core, 
			PipelineFront minPipelineFront, 
			HashMap<String,SCAIEVInstr> allISAXes,
			Function<PipelineStage,RdIValidStageDesc> stage_getRdIValidDesc) {
		this.strategyBuilders = strategyBuilders;
		this.language = language;
		this.bNodes = bNodes;
		this.minPipelineFront = minPipelineFront;
		this.core = core;
		this.allISAXes = allISAXes;
		this.stage_getRdIValidDesc = stage_getRdIValidDesc;

		//Strategy to use for RdIValid nodes.
		this.ivalidStrategy = strategyBuilders.buildNodeRegPipelineStrategy(language, bNodes,
			minPipelineFront,
			true, true, true,
			key -> key.getNode().equals(bNodes.RdIValid) && (key.getStage().getKind() == StageKind.Core || key.getStage().getKind() == StageKind.Decoupled),
			key -> false, //always try pipelining first
			strategyBuilders.buildRdIValidStrategy(language, bNodes, core, allISAXes, stage_getRdIValidDesc)
		);
	}

	//Keys for which the additional builder for RdIValid has been instantiated, but not necessarily activated.
	HashMap<NodeInstanceDesc.Key, RdIValidPostPipeliningBuilder> keysToImplement = new HashMap<>(); 

	/** Builder that adds combinational conditions to RdIValid in the destination stage */
	protected class RdIValidPostPipeliningBuilder extends RdIValidBuilder {
		public RdIValidPostPipeliningBuilder(String name, NodeInstanceDesc.Key nodeKey) {
			super(name, nodeKey, language, bNodes, core, allISAXes, stage_getRdIValidDesc);
			markerPurpose = marker_ReevalPipeliningRdIValid;
		}
		
		String baseCond = "MISSING_cond";
		/** Sets the base condition replacing the instruction decode logic. */
		void setBaseCond(String baseCond) {
			this.baseCond = baseCond;
		}
		
		@Override
		protected boolean canCreateUpUntilStage(NodeRegistryRO registry, RequestedForSet requestedFor) {
			return true;
		}
		@Override
		protected String getBaseDecodeCond(NodeRegistryRO registry, RequestedForSet requestedFor) {
			return this.baseCond;
		}
		protected static final Purpose marker_ReevalPipeliningRdIValid = new Purpose("marker_ReevalPipeliningRdIValid", true, Optional.empty(), List.of());
	}
	
	@Override
	public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
		Iterator<NodeInstanceDesc.Key> nodeKeyIter = nodeKeys.iterator();
		while (nodeKeyIter.hasNext()) {
			var nodeKey = nodeKeyIter.next();
			if (!((nodeKey.getPurpose().matches(Purpose.REGULAR) || nodeKey.getPurpose().matches(Purpose.WIREDIN) || nodeKey.getPurpose().matches(Purpose.PIPEDIN))
					&& nodeKey.getNode().equals(bNodes.RdIValid)
					&& (nodeKey.getStage().getKind() == StageKind.Core || nodeKey.getStage().getKind() == StageKind.Decoupled)
					&& !nodeKey.getISAX().isEmpty()
					&& nodeKey.getAux() == 0)) {
				continue;
			}
			List<NodeLogicBuilder> ivalidBuilders = new ArrayList<>();
			var ivalidRemoveView = new ListRemoveView<>(List.of(nodeKey));
			ivalidStrategy.implement(builder -> ivalidBuilders.add(builder), ivalidRemoveView, false);
			
			NodeInstanceDesc.Key pipedinKey = new NodeInstanceDesc.Key(Purpose.PIPEDIN, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
			NodeInstanceDesc.Key additionalKey = new NodeInstanceDesc.Key(Purpose.REGULAR, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
			Supplier<String> makeBuilderName = () -> "PipeliningRdIValidStrategy("+nodeKey.toString(false)+")";

			boolean removeKeyFromIter = (ivalidRemoveView.isEmpty());
			if (ivalidBuilders.size() > 0) {
				String builderName = makeBuilderName.get();
				NodeLogicBuilder combinedIValidBuilder = CombinedNodeLogicBuilder.of(builderName+"_inner", 
						ivalidBuilders.toArray(new NodeLogicBuilder[ivalidBuilders.size()]));
				//The keysToImplement entry may already 
				RdIValidPostPipeliningBuilder additionalIValidBuilder = keysToImplement.computeIfAbsent(additionalKey, 
						additionalKey_ -> new RdIValidPostPipeliningBuilder(builderName+"_WIREDIN+REGULAR", nodeKey));
				
				//Wrap the pipelined builder for RdIValid with generation of the optional 'RdIValid_pipedin && !RdFlush' signal.
				NodeLogicBuilder mergedBuilder = NodeLogicBuilder.fromFunction(builderName, (registry, aux) -> {
					NodeLogicBlock logicBlock = combinedIValidBuilder.apply(registry, aux);
					Optional<NodeInstanceDesc> pipedinNode_opt = logicBlock.outputs.stream().filter(output -> output.getKey().equals(pipedinKey)).findFirst();
					if (pipedinNode_opt.isPresent()) {
						//Using the PIPEDIN output ...
						additionalIValidBuilder.setBaseCond(pipedinNode_opt.get().getExpression());
						//... build the WIREDIN and/or REGULAR RdIValid outputs.
						logicBlock.addOther(additionalIValidBuilder.apply(registry, 0));
					}
					return logicBlock;
				});
				out.accept(mergedBuilder);
				removeKeyFromIter = true; //Prevent anybody else from also implementing this key.
			}
			
			if (nodeKey.getPurpose().matches(Purpose.REGULAR) || nodeKey.getPurpose().matches(Purpose.WIREDIN)) {
				//Make sure the builder for the additional REGULAR node with combinational '!RdFlush' is triggered if necessary.
				var additionalIValidBuilder = keysToImplement.get(additionalKey);
				if (additionalIValidBuilder != null) {
					//Trigger an update in case this purpose adds a new key.
					additionalIValidBuilder.updateForNodeKeyPurpose(out, nodeKey.getPurpose());
					removeKeyFromIter = true; //Prevent anybody else from also implementing this key.
				}
				else if (!nodeKey.getPurpose().matches(Purpose.PIPEDIN)) {
					//If the key does not include the PIPEDIN purpose,
					// add it explicitly so NodeRegPipelineStrategy will try implementing a pipeline.
					//Record this key in keysToImplement (so we know which of the sub-RdIValids to output). 
					keysToImplement.put(additionalKey,
						new RdIValidPostPipeliningBuilder(makeBuilderName.get()+"_WIREDIN+REGULAR", nodeKey));
					//Trigger instantiation of the PIPEDIN signal (if possible).
					out.accept(NodeLogicBuilder.fromFunction("PipeliningRdIValidStrategy Trigger", registry -> {
						registry.lookupExpressionRequired(pipedinKey);
						return new NodeLogicBlock();
					}));
					removeKeyFromIter = true; //Prevent anybody else from also implementing this key.
				}
				else {
					logger.warn("Unable to create an RdIValid pipeline for " + nodeKey.toString(true));
				}
			}
			if (removeKeyFromIter)
				nodeKeyIter.remove();
		}
	}

}
