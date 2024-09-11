package scaiev.scal.strategy.decoupled;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.util.ListRemoveView;
import scaiev.util.Verilog;

/** Pipelines nodes tagged with {@link NodeTypeTag#staticReadResult} into and through spawn ISAX sub-pipelines.
 *  Note: 'Pipeline into' for RdIValid is overridden by {@link SpawnRdIValidStrategy}.
 **/
public class SpawnStaticNodePipeStrategy extends MultiNodeStrategy {

	// logging
	protected static final Logger logger = LogManager.getLogger();

	StrategyBuilders strategyBuilders;
	Verilog language;
	BNode bNodes;
	Core core;
	HashMap<SCAIEVNode,HashMap<String,PipelineStage>> spawn_instr_stage;
	HashMap<String,SCAIEVInstr> allISAXes;
	/**
	 * @param strategyBuilders The StrategyBuilders object to build sub-strategies with
	 * @param language The (Verilog) language object
	 * @param bNodes The BNode object for the node instantiation
	 * @param core The core nodes description
	 * @param spawn_instr_stage The Node-ISAX-Stage mapping providing the precise sub-pipeline stage for spawn operations
	 * @param allISAXes The ISAX descriptions
	 */
	public SpawnStaticNodePipeStrategy(StrategyBuilders strategyBuilders,
			Verilog language, BNode bNodes, Core core,
			HashMap<SCAIEVNode,HashMap<String,PipelineStage>> spawn_instr_stage,
			HashMap<String,SCAIEVInstr> allISAXes) {
		this.strategyBuilders = strategyBuilders;
		this.language = language;
		this.bNodes = bNodes;
		this.core = core;
		this.spawn_instr_stage = spawn_instr_stage;
		this.allISAXes = allISAXes;
	}

	private boolean implementSingle(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
		if (!nodeKey.getNode().tags.contains(NodeTypeTag.staticReadResult) || !nodeKey.getPurpose().matches(Purpose.PIPEDIN) || nodeKey.getAux() != 0)
			return false;
		if (nodeKey.getStage().getKind() != StageKind.Sub || nodeKey.getStage().getPrev().size() > 1)
			return false;
		assert(nodeKey.getStage().getParent().isPresent());
		if (nodeKey.getStage().getParent().get().getKind() == StageKind.Root) {
			logger.error("Found a Sub stage directly below Root: " + nodeKey.getStage().getName());
			return false;
		}
		
		String subpipelineISAX = nodeKey.getISAX();
		if (subpipelineISAX.isEmpty()) {
			subpipelineISAX = spawn_instr_stage.values().stream()
				.flatMap(instr_stagemap -> instr_stagemap.entrySet().stream())
				.filter(instr_stage -> new PipelineFront(instr_stage.getValue()).isAroundOrAfter(nodeKey.getStage(), false))
				.map(instr_stage -> instr_stage.getKey())
				.findFirst().orElse("");
		}
		if (subpipelineISAX.isEmpty()) {
			//Note: This strategy doesn't strictly require an associated ISAX;
			// the main point of looking for the ISAX is to be able to check if this is a dynamic sub-pipeline.
			logger.warn("SpawnStaticNodePipeStrategy: Could not find an associated ISAX with sub-pipeline stage {}", nodeKey.getStage().getName());
			return false;
		}
		if (allISAXes.get(subpipelineISAX).GetRunsAsDynamic() && nodeKey.getStage().getNext().isEmpty())
			return false; //Cannot generate RdIValid for lack of a view on the pipeline.
		
		String signalName = nodeKey.toString(false) + "_pipedin";
		RequestedForSet requestedFor = new RequestedForSet(subpipelineISAX);
		if (nodeKey.getStage().getStagePos() == 0) {
			out.accept(NodeLogicBuilder.fromFunction("SpawnStaticNodePipeStrategy_fromParent_" + nodeKey.toString(false), (registry, aux) -> {
				NodeLogicBlock ret = new NodeLogicBlock();
				if (nodeKey.getNode().size > 1)
					ret.declarations += String.format("wire [%d-1:0] %s;\n", nodeKey.getNode().size, signalName);
				else
					ret.declarations += String.format("wire %s;\n", signalName);
				String nodeValIn = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage().getParent().get(), nodeKey.getISAX()), requestedFor);
				ret.logic += String.format("assign %s = %s;\n", 
					signalName,
					nodeValIn
				);
				ret.outputs.add(new NodeInstanceDesc(
						new NodeInstanceDesc.Key(Purpose.PIPEDIN, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()),
						signalName,
						ExpressionType.WireName,
						requestedFor
				));
				
				return ret;
			}));
			return true;
		}
		out.accept(NodeLogicBuilder.fromFunction("SpawnStaticNodePipeStrategy_" + nodeKey.toString(false), registry -> {
			Optional<String> prevValue_opt = Optional.empty();
			Optional<PipelineStage> prevValueStage_opt = Optional.empty();
//			List<String> rdwrStallConditions = new ArrayList<>();
			PipelineStage firstStage = null;
			for (PipelineStage prevStage : nodeKey.getStage().iterablePrev_bfs())
				firstStage = prevStage; //Update until start of sub-pipeline.
			assert(firstStage != null); //iterablePrev_bfs should always at least emit the stage itself
			for (PipelineStage prevStage : nodeKey.getStage().iterablePrev_bfs()) {
				NodeInstanceDesc.Key prevValueKey = new NodeInstanceDesc.Key(nodeKey.getNode(), prevStage, nodeKey.getISAX());

				prevValue_opt = registry.lookupOptionalUnique(prevValueKey, requestedFor)
						.map(nodeInst -> nodeInst.getExpression());
				if (prevValue_opt.isPresent()) {
					prevValueStage_opt = Optional.of(prevStage);
					break;
				}
				if (prevStage.getPrev().size() > 1) {
					logger.warn("SpawnStaticNodePipeStrategy: Encountered sub-pipeline split into " + prevStage.getName() + ". Deferring implementation to different Strategy.");
					prevValue_opt = Optional.of(registry.lookupExpressionRequired(prevValueKey, requestedFor));
					prevValueStage_opt = Optional.of(prevStage);
					break;
				}
			}
			if (prevValue_opt.isPresent() && prevValueStage_opt.get() == nodeKey.getStage()) {
				logger.error("SpawnStaticNodePipeStrategy: Node is already present");
				return new NodeLogicBlock();
			}
			if (prevValue_opt.isEmpty())
				registry.lookupExpressionRequired(new NodeInstanceDesc.Key(nodeKey.getNode(), firstStage, nodeKey.getISAX()));
			PipelineStage prevValueStage = prevValueStage_opt.orElse(firstStage);

			//TODO: Implement as dedicated shiftreg-style module with stall/flush inputs, to prevent cluttering the Verilog output.
			//      (also to support inference of FPGA shift reg primitives, possibly?)
			MultiNodeStrategy pipeliner = strategyBuilders.buildNodeRegPipelineStrategy(
					language, bNodes, 
					new PipelineFront(prevValueStage), 
					true, false, true, 
					key -> true, key -> false, 
					MultiNodeStrategy.noneStrategy);
			List<NodeLogicBuilder> pipelineBuilders = new ArrayList<>();
			pipeliner.implement(builder -> pipelineBuilders.add(builder), new ListRemoveView<>(List.of(nodeKey)), false);
			if (pipelineBuilders.isEmpty()) {
				logger.error("SpawnStaticNodePipeStrategy: Couldn't apply NodeRegPipelineStrategy");
				return new NodeLogicBlock();
			}
			NodeLogicBlock ret = new NodeLogicBlock();
			for (NodeLogicBuilder builder : pipelineBuilders)
				ret.addOther(builder.apply(registry, 0));
			if (ret.isEmpty()) {
				ret.outputs.add(new NodeInstanceDesc(
					new NodeInstanceDesc.Key(Purpose.PIPEDIN, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()), 
					"MISSING_SpawnStaticNodePipeStrategy_"+nodeKey.toString(false), 
					ExpressionType.AnyExpression,
					requestedFor
				));
			}
			return ret;
		}));
		return true;
	}

	@Override
	public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
		var nodeKeyIter = nodeKeys.iterator();
		while (nodeKeyIter.hasNext()) {
			NodeInstanceDesc.Key nodeKey = nodeKeyIter.next();
			if (this.implementSingle(out, nodeKey))
				nodeKeyIter.remove();
		}
	}

}
