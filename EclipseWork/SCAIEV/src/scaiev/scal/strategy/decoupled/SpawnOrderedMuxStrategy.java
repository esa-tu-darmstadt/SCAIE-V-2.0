package scaiev.scal.strategy.decoupled;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.util.Verilog;

/**
 * Issue-ordered arbitration logic for spawn nodes in decoupled or in-core (i.e. semi-coupled) sub-pipelines.
 * Arbitration is done per spawn node, choosing the active ISAX.
 * Note: Assumes that each ISAX uses its nodes in issue order only.
 *       Were that not to be the case, separate issue ID tracking passed through each ISAX
 *        would be required in addition or as a replacement to this strategy.
 **/
public class SpawnOrderedMuxStrategy extends MultiNodeStrategy {

	// logging
	protected static final Logger logger = LogManager.getLogger();

	Verilog language;
	BNode bNodes;
	Core core;
	HashMap<SCAIEVNode,HashMap<PipelineStage,HashSet<String>>> op_stage_instr;
	HashMap<SCAIEVNode,HashMap<String,PipelineStage>> spawn_instr_stage;
	HashMap<String,SCAIEVInstr> allISAXes;
	boolean SETTINGenforceOrdering_Memory_Semicoupled;
	boolean SETTINGenforceOrdering_Memory_Decoupled;
	boolean SETTINGenforceOrdering_User_Semicoupled;
	boolean SETTINGenforceOrdering_User_Decoupled;
	/**
	 * @param language The (Verilog) language object
	 * @param bNodes The BNode object for the node instantiation
	 * @param core The core nodes description
	 * @param op_stage_instr The Node-Stage-ISAX mapping
	 * @param spawn_instr_stage The Node-ISAX-Stage mapping providing the precise sub-pipeline stage for spawn operations
	 * @param allISAXes The ISAX descriptions
	 * @param SETTINGenforceOrdering_Memory_Semicoupled Flag if semi-coupled memory operations should be handled in ISAX issue order
	 * @param SETTINGenforceOrdering_Memory_Decoupled Flag if decoupled memory operations should be handled in ISAX issue order
	 * @param SETTINGenforceOrdering_User_Semicoupled Flag if semi-coupled user operations should be handled in ISAX issue order
	 * @param SETTINGenforceOrdering_User_Decoupled Flag if decoupled user operations should be handled in ISAX issue order
	 */
	public SpawnOrderedMuxStrategy(Verilog language, BNode bNodes, Core core,
			HashMap<SCAIEVNode,HashMap<PipelineStage,HashSet<String>>> op_stage_instr,
			HashMap<SCAIEVNode,HashMap<String,PipelineStage>> spawn_instr_stage,
			HashMap<String,SCAIEVInstr> allISAXes,
			boolean SETTINGenforceOrdering_Memory_Semicoupled,
			boolean SETTINGenforceOrdering_Memory_Decoupled,
			boolean SETTINGenforceOrdering_User_Semicoupled,
			boolean SETTINGenforceOrdering_User_Decoupled) {
		this.language = language;
		this.bNodes = bNodes;
		this.core = core;
		this.op_stage_instr = op_stage_instr;
		this.spawn_instr_stage = spawn_instr_stage;
		this.allISAXes = allISAXes;
		this.SETTINGenforceOrdering_Memory_Semicoupled = SETTINGenforceOrdering_Memory_Semicoupled;
		this.SETTINGenforceOrdering_Memory_Decoupled = SETTINGenforceOrdering_Memory_Decoupled;
		this.SETTINGenforceOrdering_User_Semicoupled = SETTINGenforceOrdering_User_Semicoupled;
		this.SETTINGenforceOrdering_User_Decoupled = SETTINGenforceOrdering_User_Decoupled;
	}

	
	private static final Purpose SubpipeSelectionPurpose = new Purpose("SubpipeSelectionNum", true, Optional.empty(), List.of());
	private static final Purpose SubpipeSelectionInternalPurpose = new Purpose("SubpipeSelectionInternal", true, Optional.empty(), List.of());
	private int getSubpipeSelectionWidth(List<MuxISAXInfo> muxISAXes) {
		int ret = 0;
		for (int tmp = muxISAXes.size()-1; tmp > 0; ret++, tmp >>= 1);
		return ret;
	}
	
	/**
	 * Determines whether the operation will only be applied by advancing in the core pipeline,
	 * and needs to be sent alongside all other 'commit' operations emitted by that instruction.
	 * This can be relevant if the spawn nodes are presented to the core as non-spawn nodes (e.g. the ISAX sets WrMem_spawn but the core gets WrMem).
	 * @param operation the base spawn operation
	 * @param stage the stage where the general spawn operation lives
	 * @return
	 */
	protected boolean requiresCommitToCore(SCAIEVNode operation, PipelineStage stage) {
		return (operation.equals(bNodes.WrCommit_spawn)
				|| operation.equals(bNodes.WrRD_spawn)
				|| operation.equals(bNodes.WrRD)
				|| bNodes.IsUserBNode(operation))
				&& stage.getKind() == StageKind.Core;
	}
	/**
	 * Determines whether the SubpipeSelectionPurpose node should contain the commit ID (in [selectWidth+:bNodes.RdInStageID.size].
	 * @param operation the base spawn operation
	 * @param stage the stage where the general spawn operation lives
	 * @return
	 */
	protected boolean carriesCommitID(SCAIEVNode operation, PipelineStage stage) {
		return operation.equals(bNodes.WrCommit_spawn) && stage.getKind() == StageKind.Core;
	}
	
	protected NodeLogicBlock buildSelectFromISAX(NodeInstanceDesc.Key nodeKey, SCAIEVNode selectBaseNode, RequestedForSet requestedFor,
			List<MuxISAXInfo> muxISAXes, NodeRegistryRO registry, int aux) {
		final String tab = language.tab;
		var ret = new NodeLogicBlock();
		
		String nodeWireName = nodeKey.toString(false) + "_s";
		if (nodeKey.getNode().size > 1)
			ret.declarations += String.format("logic [%d-1:0] %s;\n", nodeKey.getNode().size, nodeWireName);
		else
			ret.declarations += String.format("logic %s;\n", nodeWireName);
		ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR), nodeWireName, ExpressionType.WireName, requestedFor));
		if (nodeKey.getNode().getAdj() == AdjacentNode.validReq) {
			//Required for SpawnRegisterStrategy: ISAX_fire2_r
			//-> SpawnOrderedMuxStrategy replaces SpawnFireStrategy
			String fireSuffix = SpawnFireStrategy.getFireNodeSuffix(bNodes.GetSCAIEVNode(nodeKey.getNode().nameParentNode));
			ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(SpawnFireStrategy.ISAX_fire2_r, nodeKey.getStage(), fireSuffix), nodeWireName, ExpressionType.AnyExpression, requestedFor));
		}
		//Mark the node as 'must be in-order' to ensure correct behaviour of SpawnOptionalInputFIFO if instantiated.
		ret.outputs.add(new NodeInstanceDesc(
			new NodeInstanceDesc.Key(Purpose.REGULAR,
				SpawnOptionalInputFIFOStrategy.makeMustBeInorderMarkerNode(bNodes, bNodes.GetNonAdjNode(nodeKey.getNode())),
				nodeKey.getStage(), "", aux),
			"1", ExpressionType.AnyExpression
		));
		
		assert(muxISAXes.size() > 0); //Checked by caller, thus shouldn't happen here.
		
		int selectWidth = getSubpipeSelectionWidth(muxISAXes);
		
		if (muxISAXes.size() > 1) {
			//Select between the ISAX nodes based on the selection FIFO.
			String body = "";
			body += String.format("case(%s[%d-1:0])\n", 
				registry.lookupExpressionRequired(new NodeInstanceDesc.Key(SubpipeSelectionPurpose, selectBaseNode, nodeKey.getStage(), "")),
				selectWidth);
			for (int iSel = 0; iSel < muxISAXes.size(); ++iSel) {
				MuxISAXInfo muxISAX = muxISAXes.get(iSel);
				var inputNode = registry.lookupRequired(
					new NodeInstanceDesc.Key(Purpose.REGISTERED, nodeKey.getNode(), nodeKey.getStage(), muxISAX.isaxName),
					requestedFor
				);
				requestedFor.addAll(inputNode.getRequestedFor(), true);
				String assignStatement = String.format("%s = %s;", nodeWireName, inputNode.getExpression());
				if (iSel < muxISAXes.size() - 1)
					body += tab + String.format("%d'd%d: %s\n", selectWidth, iSel, assignStatement);
				else
					body += tab + String.format("default: %s\n", assignStatement);
			}
			body += "endcase\n";
			
			ret.logic += language.CreateInAlways(false, body);
		}
		else {
			var inputNode = registry.lookupRequired(
				new NodeInstanceDesc.Key(Purpose.REGISTERED, nodeKey.getNode(), nodeKey.getStage(), muxISAXes.get(0).isaxName),
				requestedFor
			);
			requestedFor.addAll(inputNode.getRequestedFor(), true);
			ret.logic += String.format("assign %s = %s;\n", nodeWireName, inputNode.getExpression());
		}
		
		return ret;
	}

	protected NodeLogicBlock buildSelectToISAX(NodeInstanceDesc.Key nodeKey, SCAIEVNode selectBaseNode, RequestedForSet requestedFor,
			List<MuxISAXInfo> muxISAXes, NodeRegistryRO registry, int aux) {
		var ret = new NodeLogicBlock();

		String nodeWireName = language.CreateLocalNodeName(nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
		if (nodeKey.getNode().size > 1)
			ret.declarations += String.format("wire [%d-1:0] %s;\n", nodeKey.getNode().size, nodeWireName);
		else
			ret.declarations += String.format("wire %s;\n", nodeWireName);
		ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR), nodeWireName, ExpressionType.WireName, requestedFor));
		requestedFor.addRelevantISAX(nodeKey.getISAX());
		
		int iMuxISAX_found = -1;
		for (int iSel = 0; iSel < muxISAXes.size(); ++iSel) {
			MuxISAXInfo muxISAX = muxISAXes.get(iSel);
			if (muxISAX.isaxName.equals(nodeKey.getISAX())) {
				if (iMuxISAX_found != -1) {
					logger.error("SpawnOrderedMuxStrategy: Found a duplicate spawn mapping for {}", nodeKey.toString(false));
					break;
				}
				iMuxISAX_found = iSel;
			}
		}
		
		if (iMuxISAX_found == -1) {
			//Should not happen.
			logger.error("SpawnOrderedMuxStrategy: No spawn mapping entry for {}", nodeKey.toString(false));
			return ret;
		}

		NodeInstanceDesc inputNode = registry.lookupRequired(
			new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), ""),
			requestedFor
		);
		
		String valueExpression = inputNode.getExpression();
		if (nodeKey.getNode().isValidNode() /* No need to zero out data nodes */ && muxISAXes.size() > 1) {
			String selectISAXExpression = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(SubpipeSelectionPurpose, selectBaseNode, nodeKey.getStage(), ""));
			int selectWidth = getSubpipeSelectionWidth(muxISAXes);
			valueExpression = String.format("(%s[%d-1:0] == %d'd%d) ? %s : %d'd0",
				selectISAXExpression, selectWidth, selectWidth, iMuxISAX_found,
				valueExpression,
				nodeKey.getNode().size
			);
		}
		ret.logic += String.format("assign %s = %s;\n", nodeWireName, valueExpression);
		
		return ret;
	}

	protected NodeLogicBlock buildSubpipeSelect(NodeInstanceDesc.Key nodeKey, RequestedForSet requestedFor, 
			List<MuxISAXInfo> muxISAXes, NodeRegistryRO registry, int aux) {
		final String tab = language.tab;
		var ret = new NodeLogicBlock();
		
		assert(!nodeKey.getNode().isAdj());
		assert(nodeKey.getISAX().isEmpty());
		int selectWidth = getSubpipeSelectionWidth(muxISAXes);
		assert(selectWidth > 0 || this.carriesCommitID(nodeKey.getNode(), nodeKey.getStage())); //Shouldn't be requested if not needed.
		
		int additionalIDWidth = 0;
		String stageEntryID = "";
		if (this.carriesCommitID(nodeKey.getNode(), nodeKey.getStage())) {
			additionalIDWidth = bNodes.RdInStageID.size;
			stageEntryID = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdInStageID, nodeKey.getStage(), ""), requestedFor);
		}
		
		String stageEntryValid = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdInStageValid, nodeKey.getStage(), ""), requestedFor); 
		
		String wireNameBase = String.format("%s_%s_orderselect", nodeKey.getNode().name, nodeKey.getStage().getName());
		
		String selectWireName = String.format("%s_id", wireNameBase);
		ret.declarations += String.format("wire [%d-1:0] %s;\n", additionalIDWidth + selectWidth, selectWireName);
		ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(SubpipeSelectionPurpose, nodeKey.getNode(), nodeKey.getStage(), ""), selectWireName, ExpressionType.WireName));
		
		String enqcondWireName = String.format("%s_enq", wireNameBase);
		ret.declarations += String.format("logic %s;\n", enqcondWireName);
		ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(SubpipeSelectionInternalPurpose, nodeKey.getNode(), nodeKey.getStage(), "enq"), enqcondWireName, ExpressionType.WireName));
		
		String enqidWireName = String.format("%s_enqid", wireNameBase);
		ret.declarations += String.format("logic [%d-1:0] %s;\n", additionalIDWidth + selectWidth, enqidWireName);
		ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(SubpipeSelectionInternalPurpose, nodeKey.getNode(), nodeKey.getStage(), "enqid"), enqidWireName, ExpressionType.WireName));
		
		//Logic to detect relevant ISAXes and set the IDs to enqueue into the FIFO.
		String enqBody = ""; 
		enqBody += (selectWidth == 0)
			? String.format("%s = %s;\n", enqidWireName, stageEntryID)
			: ((additionalIDWidth > 0)
				? String.format("%s = {%s, %d'dX};\n", enqidWireName, stageEntryID, selectWidth)
				: String.format("%s = %d'dX;\n", enqidWireName, selectWidth));
		enqBody += String.format("%s = 1'b0;\n", enqcondWireName);
		enqBody += String.format("if (%s) begin\n", stageEntryValid);
		enqBody += tab+String.format("%s = 1'b1;\n", enqcondWireName);
		enqBody += tab+"case(1'b1)\n";
		for (int iMux = 0; iMux < muxISAXes.size(); ++iMux) {
			MuxISAXInfo muxISAX = muxISAXes.get(iMux);
			String isaxName = muxISAX.isaxName;
			requestedFor.addRelevantISAX(isaxName);
			assert(!muxISAX.subpipelineFirstStages.isEmpty());
			String ivalidCond = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, nodeKey.getStage(), isaxName));
			var stallCond_opt = muxISAX.subpipelineFirstStages.stream().map(firstStage -> {
				String stallCond = "!(" + registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, firstStage, "")) + ")";
				var wrstallEntryNode_opt = registry.lookupOptional(new NodeInstanceDesc.Key(bNodes.WrStall, firstStage, ""));
				if (wrstallEntryNode_opt.isPresent())
					stallCond += " && !(" + wrstallEntryNode_opt.get().getExpression() + ")";
				return "("+stallCond+")";
			}).reduce((a,b) -> a+" || "+b);
			if (stallCond_opt.isPresent())
				ivalidCond += " && (" + stallCond_opt.get() + ")";
			
			String assignExpr = (selectWidth == 0)
				? stageEntryID
				: ((additionalIDWidth > 0)
					? String.format("{%s, %d'd%d}", stageEntryID, selectWidth, iMux)
					: String.format("%d'd%d", selectWidth, iMux));
			enqBody += tab+tab + String.format("%s: %s = %s;\n", ivalidCond, enqidWireName, assignExpr);
		}
		enqBody += tab+tab + String.format("default: %s = 1'b0;\n", enqcondWireName);
		enqBody += tab+"endcase\n";
		enqBody += "end\n";
		ret.logic += language.CreateInAlways(false, enqBody);
		
		String deqcondWireName = String.format("%s_deq", wireNameBase);
		ret.declarations += String.format("logic %s;\n", deqcondWireName);
		ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(SubpipeSelectionInternalPurpose, nodeKey.getNode(), nodeKey.getStage(), "deq"), deqcondWireName, ExpressionType.WireName));
		
		if (this.requiresCommitToCore(nodeKey.getNode(), nodeKey.getStage())) {
			String isWritingCurrentIDCond = "";
			if (additionalIDWidth > 0) {
				String inStageID = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrInStageID, nodeKey.getStage(), ""), requestedFor);
				isWritingCurrentIDCond = String.format("(%s[%d-1:%d] == %s) && ", selectWireName, selectWidth+additionalIDWidth, selectWidth, inStageID);
			}
			String inStageIDValidReq = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrInStageID_valid, nodeKey.getStage(), ""), requestedFor);
			String inStageIDValidResp = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrInStageID_validResp, nodeKey.getStage(), ""), requestedFor);
			ret.logic += String.format("assign %s = %s%s && %s;\n",
				deqcondWireName,
				isWritingCurrentIDCond,
				inStageIDValidReq,
				inStageIDValidResp);
		}
		else {
			String deqBody = String.format("case(%s)\n", selectWireName);
			for (int iMux = 0; iMux < muxISAXes.size(); ++iMux) {
				MuxISAXInfo muxISAX = muxISAXes.get(iMux);
				String isaxName = muxISAX.isaxName;
				String anyEndValidCond = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
					Purpose.REGISTERED, //Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN_NONLATCH,
					bNodes.GetAdjSCAIEVNode(nodeKey.getNode(), AdjacentNode.validReq).orElseThrow(),
					nodeKey.getStage(),
					isaxName
				));
				var validRespNode_opt = bNodes.GetAdjSCAIEVNode(nodeKey.getNode(), AdjacentNode.validResp);
				if (validRespNode_opt.isPresent()) {
					anyEndValidCond += " && " + registry.lookupExpressionRequired(new NodeInstanceDesc.Key(
						validRespNode_opt.get(),
						nodeKey.getStage(),
						isaxName
					));
				}
//				String anyEndValidCond = muxISAX.spawnFromStage.stream().map(fromStage -> {
//					String ivalidCond = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, fromStage, isaxName));
//					ivalidCond += " && !(" + registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, fromStage, "")) + ")";
//					var wrstallNode_opt = registry.lookupOptional(new NodeInstanceDesc.Key(bNodes.WrStall, fromStage, ""));
//					if (wrstallNode_opt.isPresent())
//						ivalidCond += " && !(" + wrstallNode_opt.get().getExpression() + ")";
//					return "("+ivalidCond+")";
//				}).reduce((a,b) -> a+" || "+b).orElse(null);
//				if (anyEndValidCond == null) {
//					logger.error("SpawnOrderedMuxStrategy: Found no stages indicating the end of ISAX {} node {}. The ordering logic will likely overflow.", isaxName, nodeKey.getNode());
//					continue;
//				}
				deqBody += tab + String.format("%d'd%d: %s = %s;\n", selectWidth, iMux, deqcondWireName, anyEndValidCond);
			}
			deqBody += tab + "default: begin\n";
			deqBody += tab+tab + String.format("%s = 1'b0;\n", deqcondWireName);
			deqBody += tab + "end\n";
			deqBody += "endcase\n";
			ret.logic += language.CreateInAlways(false, deqBody);
		}
		
		int fifoDepth = 4;
		
		String validWireName = String.format("%s_valid", wireNameBase);
		ret.declarations += String.format("wire %s;\n", validWireName);
		ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(SubpipeSelectionInternalPurpose, nodeKey.getNode(), nodeKey.getStage(), "valid"), validWireName, ExpressionType.WireName));
		
		String notFullWireName = String.format("%s_notFull", wireNameBase);
		ret.declarations += String.format("wire %s;\n", notFullWireName);
		ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(SubpipeSelectionInternalPurpose, nodeKey.getNode(), nodeKey.getStage(), "notFull"), notFullWireName, ExpressionType.WireName));
		
		String fifoName = "FIFO_"+wireNameBase+"_inst";
		ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(SubpipeSelectionInternalPurpose, nodeKey.getNode(), nodeKey.getStage(), "fifo"), fifoName, ExpressionType.WireName));
		
		String clearCond = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, nodeKey.getStage(), ""));
		var wrflushNode = registry.lookupOptional(new NodeInstanceDesc.Key(bNodes.WrFlush, nodeKey.getStage(), ""));
		if (wrflushNode.isPresent())
			clearCond += " || " + wrflushNode.get().getExpression();
		
		String FIFOmoduleName = registry.lookupExpressionRequired(
			new NodeInstanceDesc.Key(Purpose.HDL_MODULE, DecoupledStandardModulesStrategy.makeFIFONode(), core.GetRootStage(), ""));
		ret.logic +=
				FIFOmoduleName+"#("+fifoDepth+","+(additionalIDWidth+selectWidth)+") "+fifoName+" (\n"
				+ tab+language.clk+",\n"
				+ tab+language.reset+",\n"
				+ tab+clearCond+",\n"
				+ tab+enqcondWireName+",\n"
				+ tab+deqcondWireName+",\n"
				+ tab+enqidWireName+",\n"
				+ tab+validWireName+",\n"
				+ tab+notFullWireName+",\n"
				+ tab+selectWireName+"\n"
				+ ");\n";

		// Backpressure on all sub-pipeline entries of the ISAX - not only those sub-pipelines we are handling - to prevent FIFO overflow.
		muxISAXes.stream().map(muxISAX->muxISAX.isaxName) /* for each ISAX we are handling... */
				.flatMap(isaxName->spawn_instr_stage.entrySet().stream() /* .. get the spawn operation stages */
						.filter(op_instrstages->op_instrstages.getKey().isSpawn())
						.flatMap(op_instrstages->op_instrstages.getValue().entrySet().stream())
						.filter(instr_stage->instr_stage.getValue().getKind() == StageKind.Sub && instr_stage.getKey().equals(isaxName))
						.map(stage_instrs -> stage_instrs.getValue()))
				.flatMap(spawnFromStage -> spawnFromStage.streamPrev_bfs()) /* look backwards */
				.filter(subStage -> subStage.getPrev().isEmpty()) /* select just the entry stage into the sub pipeline */
				.distinct() /* filter out duplicates */
				.forEach(firstStage -> {
			ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, firstStage, "", aux), "!"+notFullWireName, ExpressionType.AnyExpression));
			registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, firstStage, ""));
		});
		
		// Stall non-spawn instructions entering the base stage, if they are using conflicting nodes.
		Stream<HashMap<PipelineStage,HashSet<String>>> relevant_stage_instr;
		if (this.carriesCommitID(nodeKey.getNode(), nodeKey.getStage())) {
			// Look at all operations that need some commit condition.
			relevant_stage_instr = op_stage_instr.entrySet().stream()
				.filter(op_stage_instr_entry -> !op_stage_instr_entry.getKey().isSpawn() && requiresCommitToCore(op_stage_instr_entry.getKey(), nodeKey.getStage()))
				.map(op_stage_instr_entry -> op_stage_instr_entry.getValue());
		}
		else {
			// Look at just the non-spawn version of the operation.
			// (note: isAdj should be guaranteed to be false at this point, leaving the check in for reference)
			SCAIEVNode baseNode = nodeKey.getNode().isAdj() ? bNodes.GetSCAIEVNode(nodeKey.getNode().nameParentNode) : nodeKey.getNode();
			SCAIEVNode nodeNonspawn = baseNode.isSpawn() ? bNodes.GetSCAIEVNode(baseNode.nameParentNode) : baseNode;
			if (nodeKey.getNode().isAdj()) {
				//Get the corresponding adjacent non-spawn node.
				//If the adjacent node only exists in the spawn-world, take the base node instead.
				nodeNonspawn = bNodes.GetAdjSCAIEVNode(nodeNonspawn, nodeKey.getNode().getAdj()).orElse(nodeNonspawn);
			}
			
			relevant_stage_instr = Stream.ofNullable(op_stage_instr.get(nodeNonspawn));
		}
		List<Entry<PipelineStage,String>> anyConflictingNonspawnConds = relevant_stage_instr
			.flatMap(stage_instr -> stage_instr.getOrDefault(nodeKey.getStage(), new HashSet<>()).stream())
			.distinct()
			.filter(conflictInstr -> !conflictInstr.isEmpty() && allISAXes.containsKey(conflictInstr))
			.flatMap(conflictInstr -> (nodeKey.getStage().getContinuous() ? nodeKey.getStage().getPrev().stream() : Stream.of(nodeKey.getStage()))
					.filter(prevStage -> prevStage.getKind() == StageKind.Core)
					.map(prevStage -> Map.entry(prevStage, registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, prevStage, conflictInstr)))))
			.toList();
		if (!anyConflictingNonspawnConds.isEmpty()) {
			//Find the distinct stages to stall.
			List<PipelineStage> stallInStages = anyConflictingNonspawnConds.stream().map(stage_cond -> stage_cond.getKey()).distinct().toList();
			if (stallInStages.contains(nodeKey.getStage())) {
				assert(stallInStages.size() == 1);
				assert(!nodeKey.getStage().getContinuous());
				//ValidMuxStrategy does not know about spawn operations before 'validReq' is applied, thus we can see a wrong operation order.
				logger.error("SpawnOrderedMuxStrategy: Cannot stall entry of non-spawn instructions into execute because the execute stage '{}' is marked non-continuous. Non-active instructions may violate the ordering.", nodeKey.getStage().getName());
				//Continue applying WrStall to just the current stage, so at least commit operations cannot be applied prematurely.
			}
			for (PipelineStage stallInStage : stallInStages) {
				//If any such potentially conflicting instruction is valid in the given stage (either a direct predecessor or the current stage),
				// check if there is a spawn pending, and if so, stall the stage.
				String anyConflictingNonspawnCond = anyConflictingNonspawnConds.stream()
					.filter(stage_cond -> stage_cond.getKey() == stallInStage)
					.map(stage_cond -> stage_cond.getValue())
					.reduce((a,b) -> a+" || "+b)
					.orElse("");
				String conflictWireName = String.format("%s_conflictingInstrStall_%s", wireNameBase, stallInStage.getName());
				ret.declarations += String.format("wire %s;\n", conflictWireName);
				String maybeValidCond = (stallInStage == nodeKey.getStage()) ? validWireName : String.format("(%s || %s)",validWireName,enqcondWireName);
				ret.logic += String.format("assign %s = %s && (%s);\n", conflictWireName, maybeValidCond, anyConflictingNonspawnCond);
				ret.outputs.add(new NodeInstanceDesc(
					new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, stallInStage, "", aux),
					conflictWireName,
					ExpressionType.WireName,
					requestedFor
				));
				registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, stallInStage, ""));
			}
		}
		
		return ret;
	}


	protected NodeLogicBlock buildWrInStageID(PipelineStage stage, RequestedForSet requestedFor, NodeRegistryRO registry, int aux) {
		var ret = new NodeLogicBlock();

		String selectISAXExpression = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(SubpipeSelectionPurpose, bNodes.WrCommit_spawn, stage, ""));
		String commitRequestExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrCommit_spawn_validReq, stage, ""));
		
		List<MuxISAXInfo> muxISAXes = muxableISAXesByNodeStage.get(new MuxKey(bNodes.WrCommit_spawn, stage));
		if (muxISAXes == null || muxISAXes.isEmpty()) {
			ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(bNodes.WrInStageID, stage, ""), "0", ExpressionType.AnyExpression, requestedFor));
			ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(bNodes.WrInStageID_valid, stage, ""), "0", ExpressionType.AnyExpression, requestedFor));
			ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(bNodes.WrCommit_spawn_validResp, stage, ""), "0", ExpressionType.AnyExpression, requestedFor));
			return ret;
		}
		
		//Add a dependency on WrCommit_spawn, to indirectly trigger SpawnOptionalInputFIFO.
		registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrCommit_spawn, stage, ""));
		
		String inStageIDValidResp = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrInStageID_validResp, stage, ""), requestedFor);
		
		int selectWidth = getSubpipeSelectionWidth(muxISAXes);
		for (var muxISAX : muxISAXes)
			requestedFor.addRelevantISAX(muxISAX.isaxName);

		String commitIDExpr = String.format("%s[%d-1:%d]", selectISAXExpression, bNodes.RdInStageID.size+selectWidth, selectWidth);
		ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(bNodes.WrInStageID, stage, ""), commitIDExpr, ExpressionType.AnyExpression, requestedFor));
		ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(bNodes.WrInStageID_valid, stage, ""), commitRequestExpr, ExpressionType.AnyExpression, requestedFor));
		ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(bNodes.WrCommit_spawn_validResp, stage, ""), inStageIDValidResp, ExpressionType.AnyExpression, requestedFor));
		
		return ret;
	}
	
	private static class MuxKey {
		public MuxKey(SCAIEVNode baseNode, PipelineStage spawnToStage) {
			this.baseNode = baseNode;
			this.spawnToStage = spawnToStage;
		}
		SCAIEVNode baseNode; //Spawn base node to select
		PipelineStage spawnToStage; //Core stage where the spawn is handled
		@Override
		public int hashCode() {
			return Objects.hash(baseNode, spawnToStage);
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			MuxKey other = (MuxKey) obj;
			return Objects.equals(baseNode, other.baseNode) && spawnToStage == other.spawnToStage;
		}
	}
	private static class MuxISAXInfo {
		public MuxISAXInfo(String isaxName) {
			this.isaxName = isaxName;
			this.baseNodes = new ArrayList<>();
			this.subpipelineFirstStages = new ArrayList<>();
			this.spawnFromStage = new ArrayList<>();
		}
		String isaxName;
		//Spawn nodes grouped together
		List<SCAIEVNode> baseNodes;
		//First stages in each sub-pipeline for this ISAX.
		List<PipelineStage> subpipelineFirstStages;
		//Last stages in each relevant sub-pipeline from which a spawn of the associated node can be initiated.
		List<PipelineStage> spawnFromStage;
	}
	//Once generated, provides a consistent numbering (index) for semi-coupled ISAXes in a stage per spawn node.
	// This is intended to give an identifier for each ISAX.
	// Note: For all node/stage pairs with requiresCommitToCore, baseNode is set to WrCommit_spawn.
	private HashMap<MuxKey, List<MuxISAXInfo>> muxableISAXesByNodeStage = new HashMap<>();
	private HashMap<NodeInstanceDesc.Key, RequestedForSet> requestedForByKey = new HashMap<>();
	private HashSet<PipelineStage> wrInStageIDBuilders = new HashSet<>();
	
	protected boolean handlesNode(SCAIEVNode baseNode, PipelineStage stage) {
		if (!baseNode.isSpawn())
			return false;
		boolean isCoreStage = stage.getKind() == StageKind.Core || stage.getKind() == StageKind.CoreInternal;
		if (!(isCoreStage ? SETTINGenforceOrdering_User_Semicoupled : SETTINGenforceOrdering_User_Decoupled)
				&& bNodes.IsUserBNode(baseNode))
			return false;
		if (!(isCoreStage ? SETTINGenforceOrdering_Memory_Semicoupled : SETTINGenforceOrdering_Memory_Decoupled)
				&& (baseNode.equals(bNodes.WrMem_spawn) || baseNode.equals(bNodes.RdMem_spawn)))
			return false;
		if (stage.getKind() == StageKind.Decoupled && baseNode.equals(bNodes.WrRD_spawn))
			return false; //WrRD_spawn: This strategy is quite inefficient for WaW hazards on decoupled. Only required to uphold semi-coupled issue ordering.
		if (baseNode.equals(bNodes.ISAX_spawnAllowed))
			return false; //Doesn't belong here
		if (!bNodes.GetAllBackNodes().contains(baseNode))
			return false;
		return true;
	}
	
	@Override
	public void implement(Consumer<NodeLogicBuilder> out, Iterable<Key> nodeKeys, boolean isLast) {

		Iterator<NodeInstanceDesc.Key> keyIter = nodeKeys.iterator();
		while (keyIter.hasNext()) {
			NodeInstanceDesc.Key nodeKey = keyIter.next();
			if ((nodeKey.getStage().getKind() != StageKind.Core && nodeKey.getStage().getKind() != StageKind.Decoupled)
				|| nodeKey.getAux() != 0)
				continue;
			if (nodeKey.getPurpose().matches(Purpose.REGULAR) && nodeKey.getISAX().isEmpty() && 
					(nodeKey.getNode().equals(bNodes.WrInStageID) || nodeKey.getNode().equals(bNodes.WrInStageID_valid))) {
				if (wrInStageIDBuilders.add(nodeKey.getStage())) {
					var requestedForSet = new RequestedForSet();
					out.accept(NodeLogicBuilder.fromFunction(
						"SpawnOrderedMuxStrategy_buildWrInStageID_"+nodeKey.getStage().getName(),
						(registry, aux) -> buildWrInStageID(nodeKey.getStage(), requestedForSet, registry, aux)
					));
				}
				keyIter.remove();
				continue;
			}
			if (!nodeKey.getNode().isSpawn())
				continue;
			if (!nodeKey.getPurpose().matches(Purpose.REGULAR) && !nodeKey.getPurpose().matches(SubpipeSelectionPurpose))
				continue;
			SCAIEVNode baseNode = nodeKey.getNode().isAdj() ? bNodes.GetSCAIEVNode(nodeKey.getNode().nameParentNode) : nodeKey.getNode();
			if (!handlesNode(baseNode, nodeKey.getStage()))
				continue;
			
			var stageFront = new PipelineFront(nodeKey.getStage());
			
			boolean handleAllCommitOps = requiresCommitToCore(baseNode, nodeKey.getStage());
			List<SCAIEVNode> handledBaseNodes;
			if (handleAllCommitOps) {
				handledBaseNodes = bNodes.GetAllBackNodes().stream()
					.filter(bnode -> !bnode.isAdj() && handlesNode(bnode, nodeKey.getStage()) && requiresCommitToCore(bnode, nodeKey.getStage()))
					.toList();
			}
			else {
				handledBaseNodes = List.of(baseNode);
			}
			
			var muxKey = new MuxKey(handleAllCommitOps ? bNodes.WrCommit_spawn : baseNode, nodeKey.getStage());
			
			var muxISAXesEntry = muxableISAXesByNodeStage.computeIfAbsent(muxKey, muxKey_ -> {
				var muxInfoByISAX = new LinkedHashMap<String,MuxISAXInfo>();
				//Fill in muxInfoByISAX via spawn_instr_stage.
				//Note: spawn_instr_stage currently does not allow an ISAX spawn node to appear in multiple stages.
				// This code is written under the assumption this will change in the future.
				for (SCAIEVNode handledBaseNode : handledBaseNodes) {
					spawn_instr_stage.getOrDefault(handledBaseNode, new HashMap<>()).entrySet().stream()
						.filter(instr_stage -> !instr_stage.getKey().isEmpty() && stageFront.isAround(instr_stage.getValue()))
						.forEach(instr_stage -> {
							var isaxInfo = muxInfoByISAX.computeIfAbsent(instr_stage.getKey(), key_ -> new MuxISAXInfo(instr_stage.getKey()));
							isaxInfo.spawnFromStage.add(instr_stage.getValue());
							isaxInfo.baseNodes.add(handledBaseNode);
						});
				}
				ArrayList<MuxISAXInfo> retList = new ArrayList<>(muxInfoByISAX.values());
				for (MuxISAXInfo isaxInfo : retList) {
					//Reduce isaxInfo.spawnFromStage to contain only the last stage(s) of each relevant sub-pipeline.
					HashMap<PipelineStage, PipelineStage> spawnFromStageBySubpipeline = new HashMap<>();
					for (PipelineStage spawnFrom : isaxInfo.spawnFromStage) {
						PipelineStage spawnFromRoot = null;
						for (PipelineStage curSpawnFromPredecessor : spawnFrom.iterablePrev_bfs())
							spawnFromRoot = curSpawnFromPredecessor;
						if (!spawnFromStageBySubpipeline.containsKey(spawnFromRoot))
							spawnFromStageBySubpipeline.put(spawnFromRoot, spawnFrom);
						else {
							//Determine which one is the last stage.
							//This assumes that the sub-pipelines are linear+continuous, and thus each sub-pipeline has a strong ordering between stages.
							var oldLastFront = new PipelineFront(spawnFromStageBySubpipeline.get(spawnFromRoot));
							if (oldLastFront.isBefore(spawnFromRoot, false))
								spawnFromStageBySubpipeline.put(spawnFromRoot, spawnFrom);
							else if (!oldLastFront.isAroundOrAfter(spawnFromRoot, false))
								logger.error("SpawnOrderedMuxStrategy: Sub-pipeline for {} (ISAX {}) unexpectedly branches off", nodeKey.toString(false), isaxInfo.isaxName);
						}
					}
					isaxInfo.subpipelineFirstStages = new ArrayList<>(spawnFromStageBySubpipeline.keySet());
					isaxInfo.spawnFromStage = new ArrayList<>(spawnFromStageBySubpipeline.values());
				}
				//Warn about ISAXes with several sub-pipelines for a node. In the list, stages in the same sub-pipeline are already reduced to the last one.
				String isaxDuplicateStagesReport = retList.stream().filter(isaxInfo -> isaxInfo.spawnFromStage.size() > isaxInfo.baseNodes.size() /*Assuming one sub-pipeline per spawn node*/)
						.map(isaxInfo -> {return "<ISAX "+isaxInfo.isaxName+", nodes ["+isaxInfo.baseNodes.stream().map(curNode->curNode.name).reduce((a,b)->a+","+b).orElse("")+"]>"
							+ ":["+isaxInfo.spawnFromStage.stream().map(stage->stage.getName()).reduce((a,b)->a+","+b).orElse("")+"]";})
						.reduce((a,b)->a+","+b).map(a->"{"+a+"}").orElse("");
				if (!isaxDuplicateStagesReport.isEmpty()) {
					logger.warn("SpawnOrderedMuxStrategy: Some ISAXes have several sub-pipelines for some nodes. Assuming either one of these stages indicate the last possible spawn : {}", baseNode.name, isaxDuplicateStagesReport);
				}
				return retList;
			});
			if (muxISAXesEntry.isEmpty()) {
				if (!nodeKey.getNode().noInterfToISAX) //noInterfToISAX: it's probably some other strategy's job to generate the node. 
					logger.error("SpawnOrderedMuxStrategy: No matching ISAXes for " + nodeKey.toString(false));
				continue;
			}
			
			var requestedForKey = new NodeInstanceDesc.Key(nodeKey.getPurpose().matches(Purpose.REGULAR) ? Purpose.REGULAR : nodeKey.getPurpose(),
					nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
			boolean isNew = !requestedForByKey.containsKey(requestedForKey);
			var requestedForSet = isNew ? new RequestedForSet() : requestedForByKey.get(requestedForKey); 
			if (nodeKey.getPurpose().matches(Purpose.REGULAR) && nodeKey.getNode().isInput /*ISAX -> SCAL( -> core)*/) {
				if (!nodeKey.getISAX().isEmpty())
					continue; //Can only generate the MUXed node across ISAXes.
				if (isNew) {
					requestedForByKey.put(requestedForKey, requestedForSet);
					//Generate a MUX, selecting across the ISAXes.
					out.accept(NodeLogicBuilder.fromFunction("SpawnOrderedMuxStrategy_fromISAX_"+nodeKey.toString(), (registry, aux) -> {
						return buildSelectFromISAX(nodeKey, muxKey.baseNode, requestedForSet, muxISAXesEntry, registry, aux);
					}));
				}
				keyIter.remove();
			}
			else if (nodeKey.getPurpose().matches(Purpose.REGULAR) && !nodeKey.getNode().isInput /*(core -> ) SCAL -> ISAX*/) {
				if (nodeKey.getISAX().isEmpty() && nodeKey.getNode().getAdj() == AdjacentNode.validResp
					&& requiresCommitToCore(bNodes.GetSCAIEVNode(nodeKey.getNode().nameParentNode), nodeKey.getStage())) {
					//Special case: Set validResp for 'commit-to-core' operations to the commit condition.
					if (isNew) {
						out.accept(NodeLogicBuilder.fromFunction("SpawnOrderedMuxStrategy_buildGeneralValidResp_"+nodeKey.toString(), (registry, aux) -> {
							var ret = new NodeLogicBlock();
							var inputNode = registry.lookupRequired(
								new NodeInstanceDesc.Key(bNodes.WrCommit_spawn_validResp, nodeKey.getStage(), ""),
								requestedForSet
							);
							requestedForSet.addAll(inputNode.getRequestedFor(), true);
							ret.outputs.add(new NodeInstanceDesc(
								NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR),
								inputNode.getExpression(),
								ExpressionType.AnyExpression,
								requestedForSet
							));
							return ret;
						}));
					}
					keyIter.remove();
					continue;
				}
				else if (nodeKey.getISAX().isEmpty()) {
					continue; //Can only generate the ISAX-specific selection.
				}
				if (isNew) {
					requestedForByKey.put(requestedForKey, requestedForSet);
					//Assign from the general value, 
					out.accept(NodeLogicBuilder.fromFunction("SpawnOrderedMuxStrategy_toISAX_"+nodeKey.toString(), (registry, aux) -> {
						return buildSelectToISAX(nodeKey, muxKey.baseNode, requestedForSet, muxISAXesEntry, registry, aux);
					}));
				}
				keyIter.remove();
			}
			else if (nodeKey.getPurpose().matches(SubpipeSelectionPurpose)) {
				keyIter.remove();
				if (!nodeKey.getNode().equals(muxKey.baseNode))
					continue;
				assert(nodeKey.getISAX().isEmpty());
				if (!nodeKey.getISAX().isEmpty())
					continue;
				if (isNew) {
					requestedForByKey.put(requestedForKey, requestedForSet);
					out.accept(NodeLogicBuilder.fromFunction("SpawnOrderedMuxStrategy_"+nodeKey.toString(), (registry, aux) -> {
						return buildSubpipeSelect(nodeKey, requestedForSet, muxISAXesEntry, registry, aux);
					}));
				}
			}
		}
	}

}
