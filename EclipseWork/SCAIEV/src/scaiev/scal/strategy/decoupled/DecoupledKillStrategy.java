package scaiev.scal.strategy.decoupled;

import java.util.HashMap;
import java.util.HashSet;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAL;
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
import scaiev.util.Verilog;

/** Strategy that adds the 'kill all' condition as WrFlush nodes in decoupled sub-stages. Needs to be run before RdIValid generation. */
public class DecoupledKillStrategy extends MultiNodeStrategy {
	
	// logging
	protected static final Logger logger = LogManager.getLogger();
	
	Verilog language;
	BNode bNodes;
	Core core;
	HashMap<String,SCAIEVInstr> allISAXes;
	/**
	 * @param language The (Verilog) language object
	 * @param bNodes The BNode object for the node instantiation
	 * @param core The core nodes description
	 * @param allISAXes The ISAX descriptions
	 */
	public DecoupledKillStrategy(Verilog language, BNode bNodes, Core core,
			HashMap<String,SCAIEVInstr> allISAXes) {
		this.language = language;
		this.bNodes = bNodes;
		this.core = core;
		this.allISAXes = allISAXes;
	}
	
	HashSet<PipelineStage> implementedForSubStages = new HashSet<>();
	
	@Override
	public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
		var nodeKeyIter = nodeKeys.iterator();
		while (nodeKeyIter.hasNext()) {
			NodeInstanceDesc.Key nodeKey = nodeKeyIter.next();
			//Hook before RdIValid generation for a (partially) static decoupled instruction.
			// -> Pure dynamic decoupled instructions shouldn't have RdIValid requested within the sub stages.
			if ((nodeKey.getNode().equals(bNodes.RdIValid))
					&& nodeKey.getStage().getKind() == StageKind.Sub
					&& nodeKey.getStage().getParent().map(parentStage -> parentStage.getKind() == StageKind.Decoupled).orElse(false)) {
				if (!implementedForSubStages.add(nodeKey.getStage()))
					continue;
				RequestedForSet killRequestedFor = new RequestedForSet();
				out.accept(NodeLogicBuilder.fromFunction("DecoupledKillStrategy_"+nodeKey.getStage().getName(), (registry, aux) -> {
					NodeInstanceDesc killCondInst = registry.lookupRequired(
						new NodeInstanceDesc.Key(DecoupledPipeStrategy.PseudoNode_StartSpawnToSpawnPipe_KillAll, nodeKey.getStage().getParent().get(), "")
					);
					killRequestedFor.addAll(killCondInst.getRequestedFor(), true);
					var ret = new NodeLogicBlock();
					ret.outputs.add(new NodeInstanceDesc(
						new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrFlush, nodeKey.getStage(), "", aux),
						killCondInst.getExpression(),
						ExpressionType.AnyExpression,
						killRequestedFor
					));
					//Force WrFlush generation.
					registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrFlush, nodeKey.getStage(), ""));
					return ret;
				}));
				//Keep the node key to allow WrStall generation.
			}
	    	else if (nodeKey.getPurpose().matches(Purpose.REGULAR)
	    			&& nodeKey.getNode().equals(bNodes.RdKill)
	    			&& allISAXes.containsKey(SCAL.PredefInstr.kill.instr.GetName())
	    			&& core.GetStartSpawnStages().contains(nodeKey.getStage())
	    			&& nodeKey.getAux() == 0) {
	    		//TODO: How do we guarantee the 'start spawn' stage is non-speculative?
	    		PipelineFront startSpawnStages = core.GetStartSpawnStages();
				RequestedForSet rdKillRequestedFor = new RequestedForSet(SCAL.PredefInstr.kill.instr.GetName());
	    		out.accept(NodeLogicBuilder.fromFunction("DecoupledKillStrategy_RdKill_"+nodeKey.getStage().getName(), (registry,aux) -> {
					if (!nodeKey.getISAX().isEmpty())
						rdKillRequestedFor.addRelevantISAX(nodeKey.getISAX());
	    			var ret = new NodeLogicBlock();
	    			String killActiveCondition = startSpawnStages.asList().stream()
	    				.map(startSpawnStage -> registry.lookupExpressionRequired(
    						new NodeInstanceDesc.Key(bNodes.RdIValid, startSpawnStage, SCAL.PredefInstr.kill.instr.GetName()), 
	    					rdKillRequestedFor
	    				)).reduce((a,b) -> a+" || "+b).orElse("1'b0");
	    			ret.outputs.add(new NodeInstanceDesc(
	    				new NodeInstanceDesc.Key(bNodes.RdKill, nodeKey.getStage(), nodeKey.getISAX()),
	    				killActiveCondition,
	    				ExpressionType.WireName,
	    				rdKillRequestedFor
	    			));
	    			return ret;
	    		}));
	    		nodeKeyIter.remove();
	    	}
//			//Implement disaxfence kill for dynamic decoupled ISAXes
//    		SCAIEVNode disaxkillCondNode = new SCAIEVNode("disaxkill_dynamic_cond");
//	    	if (nodeKey.getNode().equals(disaxkillCondNode) && ISAXes.containsKey(SCAL.PredefInstr.kill.instr.GetName())) {
//	    		PipelineFront startSpawnStages = core.GetStartSpawnStages();
//	    		List<Map.Entry<PipelineStage, String>> relevantStageISAXPairs = op_stage_instr.entrySet().stream()
//	    			.filter(op_stage_instr_entry -> {
//		    			SCAIEVNode op = op_stage_instr_entry.getKey();
//		    			return op.isSpawn() && !op.isAdj();
//		    		})
//	    			.flatMap(op_stage_instr_entry -> op_stage_instr_entry.getValue().entrySet().stream())
//		    		.flatMap((Map.Entry<PipelineStage, HashSet<String>> stage_instr_entry) -> {
//		    			PipelineStage stage = stage_instr_entry.getKey();
//		    			Stream<String> isaxNames = stage_instr_entry.getValue().stream();
//		    			return isaxNames.map(isaxName -> Map.entry(stage, isaxName));
//		    		})
//		    		.filter((Map.Entry<PipelineStage, String> stage_isax) -> 
//		    			ISAXes.containsKey(stage_isax.getValue()) && ISAXes.get(stage_isax.getValue()).GetRunsAsDynamicDecoupled()
//		    		)
//		    		.distinct()
//		    		.toList();
//				out.accept(NodeLogicBuilder.fromFunction("disaxkill_dynamic", registry -> {			
//					var ret = new NodeLogicBlock();
//					String globalKillCondExpr = startSpawnStages.asList().stream()
//						.map(startSpawnStage -> 
//							registry.lookupExpressionRequired(new NodeInstanceDesc.Key(BNode.RdIValid, startSpawnStage, SCAL.PredefInstr.kill.instr.GetName()))
//						).reduce((a,b) -> a+" || "+b).orElse("1'b0");
//					String globalKillCondWire = disaxkillCondNode.name+"_s";
//					ret.declarations += String.format("wire %s;\n", globalKillCondWire);
//					ret.logic += String.format("assign %s = %s;\n", globalKillCondWire, globalKillCondExpr);
//					ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(disaxkillCondNode, core.GetRootStage(), ""), globalKillCondWire, ExpressionType.WireName));
//					for (var stageISAXPair : relevantStageISAXPairs) {
//						ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(BNode.ISAX_killReq, stageISAXPair.getKey(), stageISAXPair.getValue()), globalKillCondWire, ExpressionType.AnyExpression));
//					}
//					return ret; 
//				}));
//	    	}
		}
	}

}
