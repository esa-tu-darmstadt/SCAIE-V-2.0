package scaiev.scal.strategy.standard;

import java.util.HashMap;
import java.util.Iterator;
import java.util.function.Consumer;
import java.util.function.Function;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAL.RdIValidStageDesc;
import scaiev.pipeline.PipelineStage;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.util.Verilog;

/** Strategy that builds a decoder for RdIValid per ISAX, stage.
 *  The REGULAR node for an ISAX/stage will contain the full condition.
 *  If requested explicitly, a separate WIREDIN node will be generated that omits any custom conditions from stage_getRdIValidDesc.
 **/
public class RdIValidStrategy extends MultiNodeStrategy {
	private Verilog language;
	private BNode bNodes;
	private Core core;
	private HashMap<String,SCAIEVInstr> allISAXes;
	private Function<PipelineStage,RdIValidStageDesc> stage_getRdIValidDesc;
	/**
	 * @param language The (Verilog) language object
	 * @param bNodes The BNode object for the node instantiation
	 * @param core The core nodes description
	 * @param allISAXes The ISAX descriptions
	 * @param stage_getRdIValidDesc A stage mapping providing additional conditional expressions to RdIValid per ISAX
	 */
	public RdIValidStrategy(Verilog language, BNode bNodes,
			Core core,
			HashMap<String,SCAIEVInstr> allISAXes,
			Function<PipelineStage,RdIValidStageDesc> stage_getRdIValidDesc) {
		this.language = language;
		this.bNodes = bNodes;
		this.core = core;
		this.allISAXes = allISAXes;
		this.stage_getRdIValidDesc = stage_getRdIValidDesc;
	}
	
	private HashMap<NodeInstanceDesc.Key, RdIValidBuilder> existingBuilders = new HashMap<>();

	@Override
	public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
		Iterator<NodeInstanceDesc.Key> nodeKeyIter = nodeKeys.iterator();
		while (nodeKeyIter.hasNext()) {
			var nodeKey = nodeKeyIter.next();
			if (!nodeKey.getNode().equals(bNodes.RdIValid)
					|| (!nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.REGULAR) 
						&& !nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.WIREDIN)))
				continue;
			if (core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.RdInstr).GetEarliest()).isAfter(nodeKey.getStage(), false))
				continue;
			if (!this.allISAXes.containsKey(nodeKey.getISAX()) || this.allISAXes.get(nodeKey.getISAX()).HasNoOp())
				continue; //Do not generate RdIValid for 'always'/NoOp ISAX, nor for invalid ISAXes. 
			
			NodeInstanceDesc.Key genericKey = new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()); //Store without the specific Purpose
			RdIValidBuilder existingBuilder = existingBuilders.get(genericKey);
			if (existingBuilder == null) {
				var newBuilder = new RdIValidBuilder("RdIValidStrategy ("+nodeKey.toString()+")", nodeKey, 
					language, bNodes, core, allISAXes, stage_getRdIValidDesc);
				out.accept(newBuilder);
				existingBuilders.put(genericKey, newBuilder);
			}
			else {
				//Only add partial or full node generation, depending on nodeKey.getPurpose().
				existingBuilder.updateForNodeKeyPurpose(out, nodeKey.getPurpose());					
			}
			nodeKeyIter.remove();
		}
	}
}
