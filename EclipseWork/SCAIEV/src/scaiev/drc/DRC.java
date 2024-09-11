package scaiev.drc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.util.Lang;

public class DRC {
	// logging
	protected static final Logger logger = LogManager.getLogger();

    private boolean errLevelHigh = false;
    private boolean hasFatalError = false;
    
	private HashMap<String,SCAIEVInstr> ISAXes; // database of requested Instructions
	private HashMap<SCAIEVNode, HashMap<PipelineStage,HashSet<String>>> op_stage_instr;
	Core core;
	private BNode BNodes = new BNode();


	public DRC(HashMap<String,SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<PipelineStage,HashSet<String>>> op_stage_instr, Core core, BNode BNodes) {
		this.ISAXes = ISAXes; 
		this.op_stage_instr = op_stage_instr;
		this.core = core;
		this.BNodes = BNodes;
	}
	
	public void SetErrLevel(boolean errLevelHigh) {
		this.errLevelHigh = errLevelHigh;
	}
	
	public boolean HasFatalError() {
		return hasFatalError;
	}
	
	public void CheckEncoding(SCAIEVInstr instruction) {
		String instrType = instruction.GetInstrType(); 
		
		if(!(instrType.equals("R"))&&(!instruction.GetEncodingF7(Lang.None).equals("-------"))) {
			instruction.SetEncoding("-------",instruction.GetEncodingF3(Lang.None),instruction.GetEncodingOp(Lang.None),instrType);
			logger.fatal("Instruction not R type, but F7 set. F7 will be set to - ");
			if(errLevelHigh)
				hasFatalError = true;
		}
		if((instrType.contains("U"))&&(!instruction.GetEncodingF3(Lang.None).equals("-------"))) {
			instruction.SetEncoding(instruction.GetEncodingF7(Lang.None), "-------",instruction.GetEncodingOp(Lang.None),instrType);
			logger.fatal("Instruction U type, but F3 set. F3 will be set to - ");
			if(errLevelHigh)
				hasFatalError = true;
		}
	}
	
	private static boolean EncodingOverlaps(String a, String b) {
		for (int i = 0; i < a.length() && i < b.length(); ++i) {
			if (a.charAt(i) == '0' && b.charAt(i) == '1')
				return false;
			if (a.charAt(i) == '1' && b.charAt(i) == '0')
				return false;
		}
		return true;
	}
	public void CheckEncodingOverlap() {
		//Plain O(N²) pairwise check, fast enough for any realistic scenario.
		List<String> isaxNames = new ArrayList<>();
		List<String> isaxEncodings = new ArrayList<>();
		ISAXes.values().forEach(isax -> {
			if (!isax.HasNoOp()) {
				isaxNames.add(isax.GetName());
				isaxEncodings.add(isax.GetEncodingBasic());
			}
		});
		int numMessages = 0;
		for (int i = 0; i < isaxEncodings.size(); ++i) {
			for (int j = i+1; j < isaxEncodings.size(); ++j) {
				if (EncodingOverlaps(isaxEncodings.get(i), isaxEncodings.get(j))) {
					logger.error("Found instruction encoding overlap: '{}' and '{}' have encodings '{}' and '{}'",
						isaxNames.get(i), isaxNames.get(j),
						isaxEncodings.get(i), isaxEncodings.get(j));
					hasFatalError = true;
					if (++numMessages > 5) {
						return;
					}
				}
			}
		}
	}
	
	public void CheckSchedErr() {
		for (var op_stage_instr_entry : op_stage_instr.entrySet()) {
			SCAIEVNode operation = op_stage_instr_entry.getKey();
			if (!operation.isSpawn() && !core.GetNodes().containsKey(operation)) {
				logger.fatal("Requested operation "+operation+" does not exist ");
				hasFatalError = true;
				continue;				
			}
			for (var stage_instr_entry : op_stage_instr_entry.getValue().entrySet()) {
				PipelineStage stage = stage_instr_entry.getKey();
				if( !operation.isSpawn() && !(stage.getKind() == StageKind.Sub && operation.equals(BNodes.RdAnyValid))) {
					var end_constrain_cycle = core.TranslateStageScheduleNumber(core.GetNodes().get(operation).GetLatest());
					var start_constrain_cycle = core.TranslateStageScheduleNumber(core.GetNodes().get(operation).GetEarliest());
					if ((!operation.isInput && !start_constrain_cycle.isAroundOrBefore(stage, false))
							|| (operation.isInput && !end_constrain_cycle.isAroundOrAfter(stage, false))) {
						String message = "For instruction(s) "+stage_instr_entry.getValue()+", node "+operation+" was scheduled in the wrong cycle: ";
						message += "Earliest allowed: " + start_constrain_cycle.asList().stream().map(stage_->stage_.getName()).toList() + "; ";
						message += "Latest allowed: " + end_constrain_cycle.asList().stream().map(stage_->stage_.getName()).toList() + "; ";
						message += "Actual: " + stage.getName();
						logger.fatal(message);
						hasFatalError = true;
					}
				}/* else if(operation.isSpawn() && stage.getKind() != StageKind.Decoupled) {
					logger.fatal("Spawn implemented in wrong cycle. Should have been in one of " + core.GetSpawnStages().asList().toString());
					System.exit(1);	
				}*/
			}
		}
		if(op_stage_instr.containsKey(BNodes.WrMem_spawn) && op_stage_instr.containsKey(BNodes.RdMem_spawn)) {
			if (op_stage_instr.get(BNodes.WrMem_spawn).values().stream()
					.flatMap(instrCollection -> instrCollection.stream())
					.anyMatch(instrWr ->
						op_stage_instr.get(BNodes.RdMem_spawn).values().stream()
							.anyMatch(instrRdSet -> instrRdSet.contains(instrWr))
					)) {
				logger.fatal("DRC - Currently SCAIE-V does not support RD & WR Mem spawn for the same instr. To be modified in near future");
				hasFatalError = true;
			}
		}

//		Supplier<Stream<Map.Entry<SCAIEVNode, SCAIEVInstr>>> makeSpawnInstructionsStream = () ->
//			core.GetSpawnStages().asList().stream()
//			.flatMap(spawnStage -> //For all spawn stages
//				//Get a Stream of (operation, instr) tuples / Map.Entry objects based on op_stage_instr.
//				this.op_stage_instr.entrySet().stream().flatMap(op_stage_instr_entry -> {
//					SCAIEVNode node = op_stage_instr_entry.getKey();
//					HashSet<String> instrSet = op_stage_instr_entry.getValue().get(spawnStage);
//					return (instrSet==null ? Stream.<String>empty() : instrSet.stream()).map(instr -> Map.entry(node, ISAXes.get(instr)));
//				}));
		Supplier<Stream<Map.Entry<SCAIEVNode, SCAIEVInstr>>> makeSpawnInstructionsStream = () ->
			this.op_stage_instr.entrySet().stream()
				.filter(op_stage_instr_entry -> op_stage_instr_entry.getKey().isSpawn()) /* all spawn nodes in op_stage_instr */
				.flatMap(op_stage_instr_entry -> op_stage_instr_entry.getValue().entrySet().stream()
						.map(stage_instr_entry -> Map.entry(op_stage_instr_entry.getKey(), stage_instr_entry.getValue()))) /* all operation-Set<ISAX name> pairs */
				.flatMap(op_instr_entry -> op_instr_entry.getValue().stream().filter(instrName -> !instrName.isEmpty())
						.map(instrName -> Map.entry(op_instr_entry.getKey(), ISAXes.get(instrName)))) /* all operation-ISAX pairs */
				.distinct();
		List<String> instructionsMissingDynamicValidReq =
			makeSpawnInstructionsStream.get()
			.filter(node_instr_pair -> {
				return !node_instr_pair.getValue().HasNoOp()
					&& node_instr_pair.getValue().GetRunsAsDynamic()
					&& node_instr_pair.getValue().HasSchedWith(node_instr_pair.getKey(), sched -> !sched.HasAdjSig(AdjacentNode.validReq));
			}).map(node_instr_pair -> node_instr_pair.getValue().GetName())
			.toList();
		if (!instructionsMissingDynamicValidReq.isEmpty()) {
			logger.fatal("DRC - Dynamic memory operations must come with a validReq signal. Add the `has valid: true` option to the ISAX description yaml."
					+ " Violating instructions: {}", instructionsMissingDynamicValidReq);
			hasFatalError = true;
		}
		List<String> instructionsViolatingMissingAddrSize =
			makeSpawnInstructionsStream.get()
			.filter(node_instr_pair -> 
				!node_instr_pair.getValue().HasNoOp()
				&& node_instr_pair.getValue().GetRunsAsDynamic()
				&& (node_instr_pair.getKey().equals(BNodes.RdMem_spawn) || node_instr_pair.getKey().equals(BNodes.WrMem_spawn))
				&& (node_instr_pair.getValue().HasSchedWith(node_instr_pair.getKey(), sched -> !sched.HasAdjSig(AdjacentNode.addr))
					|| node_instr_pair.getValue().HasSchedWith(node_instr_pair.getKey(), sched -> !sched.HasAdjSig(AdjacentNode.size))))
			.map(node_instr_pair -> node_instr_pair.getValue().GetName())
			.distinct()
			.toList();
		if (!instructionsViolatingMissingAddrSize.isEmpty()) {
			logger.warn("DRC - Dynamic memory operations should come with addr and size signals if they can run several or zero times over the instruction lifetime."
					+ " If this is the case, also make sure to add the `has addr: true` and `has size: true` options to the ISAX description yaml."
					+ " Affected instructions: {}", instructionsViolatingMissingAddrSize);
		}
		List<String> opinstrViolatingSemicoupledSingleCommittable =
			makeSpawnInstructionsStream.get()
			.filter(node_instr_pair -> !node_instr_pair.getValue().GetRunsAsDecoupled()
				&& (node_instr_pair.getKey().equals(BNodes.WrRD_spawn) || BNodes.IsUserBNode(node_instr_pair.getKey())))
			.filter(node_instr_pair -> {
				var iter = node_instr_pair.getValue().GetSchedWithIterator(node_instr_pair.getKey(), (a)->true);
				//'iter.count() > 0'
				if (!iter.hasNext()) {
					logger.error("DRC - Cannot find a scheduled node matching ({}:{})", node_instr_pair.getKey().name, node_instr_pair.getValue().GetName());
					return false;
				}
				//'iter.count() > 1'
				iter.next();
				return iter.hasNext();
			})
			.map(node_instr_pair -> String.format("(%s:%s)", node_instr_pair.getKey().name, node_instr_pair.getValue().GetName()))
			.toList();
		if (!opinstrViolatingSemicoupledSingleCommittable.isEmpty()) {
			logger.fatal("DRC - For semi-coupled spawn, only a single instance of each committable operation type is allowed per ISAX."
					+ " Violating operation-instruction pairs: {}", opinstrViolatingSemicoupledSingleCommittable);
			hasFatalError = true;
		}

		List<String> opinstrViolatingSemicoupledNonspawnShouldBeSpawn =
			this.op_stage_instr.entrySet().stream()
			.filter(op_stage_instr_entry -> !op_stage_instr_entry.getKey().isSpawn()
					&& BNodes.GetMySpawnNode(op_stage_instr_entry.getKey()).isPresent()) /* all non-spawn nodes in op_stage_instr that have a spawn variant */
			.flatMap(op_stage_instr_entry -> op_stage_instr_entry.getValue().entrySet().stream()
					.filter(stage_instr_entry -> stage_instr_entry.getKey().getKind() == StageKind.Sub) /* all such operations in a sub-pipeline */
					.map(stage_instr_entry -> Map.entry(op_stage_instr_entry.getKey(), stage_instr_entry.getValue()))) /* all operation-Set<ISAX name> pairs */
			.flatMap(op_instr_entry -> op_instr_entry.getValue().stream()
					.map(instrName -> Map.entry(op_instr_entry.getKey(), instrName))) /* all operation-<ISAX name> pairs */
			.distinct()
			.map(node_instr_pair -> String.format("(%s:%s)", node_instr_pair.getKey().name, node_instr_pair.getValue()))
			.toList();
		if (!opinstrViolatingSemicoupledNonspawnShouldBeSpawn.isEmpty()) {
			logger.fatal("DRC - Encountered currently not supported constellation: Non-spawn operation (that has a spawn counterpart) in a spawn pipeline."
					+ " This may be the case for semi-coupled ISAXes if the operation is used within the constrained range but not before the 'semi-coupled' execute stage chosen by SCAIE-V."
					+ " Violating operation-instruction pairs: {}", opinstrViolatingSemicoupledNonspawnShouldBeSpawn);
			hasFatalError = true;
		}

		List<String> instructionsViolatingDynamicNeedsRdAnyValid =
			makeSpawnInstructionsStream.get().map(op_instr->op_instr.getValue()).distinct() /* all ISAXes with spawn ops */
			.filter(instr -> instr.GetRunsAsDynamic() /* select dynamic spawn ISAXes... */
					&& !op_stage_instr /* .. that do not have RdAnyValid in their dynamic spawn stage */
						.getOrDefault(BNodes.RdAnyValid, new HashMap<>())
						.entrySet().stream()
							.anyMatch(stage_instrs -> stage_instrs.getValue().contains(instr.GetName())
									&& stage_instrs.getKey().getKind() == StageKind.Sub /* last sub stage of a dynamic ISAX -> dynamic stage */
									&& stage_instrs.getKey().getNext().isEmpty()))
			.map(instr -> instr.GetName())
			.toList();
		if (!instructionsViolatingDynamicNeedsRdAnyValid.isEmpty()) {
			logger.fatal("DRC - Encountered a dynamic spawn instruction that does not provide RdAnyValid in its spawn stage."
					+ " Violating instructions: {}", instructionsViolatingDynamicNeedsRdAnyValid);
			hasFatalError = true;
		}

		List<String> opinstrViolatingNoWrFlushInSubpipeline =
			this.op_stage_instr.getOrDefault(BNodes.WrFlush, new HashMap<>()).entrySet().stream()
			.filter(stage_instr_entry -> stage_instr_entry.getKey().getKind() == StageKind.Sub)
			.flatMap(stage_instr_entry -> stage_instr_entry.getValue().stream()) /* all ISAX names */
			.distinct()
			.toList();
		if (!opinstrViolatingNoWrFlushInSubpipeline.isEmpty()) {
			logger.fatal("DRC - A spawn instruction invokes WrFlush in its sub-pipeline, which is not allowed."
					+ " Violating instructions: {}", opinstrViolatingNoWrFlushInSubpipeline);
			hasFatalError = true;
		}

		List<String> opinstrViolatingNoWrStallInSubpipeline =
			this.op_stage_instr.getOrDefault(BNodes.WrStall, new HashMap<>()).entrySet().stream()
			.filter(stage_instr_entry -> stage_instr_entry.getKey().getKind() == StageKind.Sub && stage_instr_entry.getKey().getPrev().size() > 0)
			.flatMap(stage_instr_entry -> stage_instr_entry.getValue().stream()) /* all ISAX names */
			.distinct()
			.toList();
		if (!opinstrViolatingNoWrStallInSubpipeline.isEmpty()) {
			logger.fatal("DRC - A spawn instruction uses WrStall within its sub-pipeline beyond the entry stage. Stall propagation within sub-pipelines is currently not implemented."
					+ " Violating instructions: {}", opinstrViolatingNoWrStallInSubpipeline);
			hasFatalError = true;
		}
	}
	
	public void CheckEncPresent() { 
		for(SCAIEVInstr instr : ISAXes.values()) {
			logger.trace("Instruction {} has encoding {}", instr.GetName(), instr.GetEncodingBasic());
			if(instr.GetEncodingF3(Lang.None).isEmpty() || instr.GetEncodingF7(Lang.None).isEmpty() || instr.GetEncodingOp(Lang.None).isEmpty()) {
				logger.error("DRC Instruction {} has an empty encoding string.", instr.GetName());
				if(errLevelHigh) 
					hasFatalError = true;
			}	 
		}		
	}
}
