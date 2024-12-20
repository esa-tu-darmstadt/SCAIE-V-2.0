package scaiev.scal.strategy.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
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
import scaiev.scal.TriggerableNodeLogicBuilder;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.util.Verilog;

/**
 * Strategy that implements ID-based pipelining of nodes from the previous stage.
 * Requires incremental assignment and retirement of IDs matching the logical program order.
 * Detailed requirements:
 *   - The IDs from the given RdID SCAIEVNode are incrementally assigned (in logical instruction order), with wrap-around.
 *     If the processor does not have such an ID value natively, it needs to be tucked on in order to use this strategy. 
 *   - The IDs from the given RdID SCAIEVNode are incrementally retired (in logical instruction order).
 *   - Consequently, the assign and retire stage fronts need to be in instruction order.
 *   - If there are several assign stages in the front, the instruction ordering may be random between those stages,
 *     but over all assign stages, must remain sequential across cycles. The same applies to several retire stages.
 * This strategy only pipelines between two consecutive pipeline stages, and can be called within NodeRegPipelineStrategy.
 * 
 * If a smaller ID space is chosen than provided by the core, the strategy adds a translation table.
 * This can be used if a specific set of nodes with accompanying 'valid' adjacent nodes is to be pipelined
 *  that are expected to be sparsely valid (thus, rarely run out of the smaller ID space).
 *  To get good results in this scenario, the strategy object should be restricted to select nodes.
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
	 *        [e.g. decodeB handles the instruction immediately following the one handled by decodeA, such that id@decodeB == id@decodeA+1, never the other way round],
	 *        set this to true. Stages that stall at a given point are ignored in this ordering.
	 *        This avoids generation of (inefficient) combinational ID sorting logic.
	 * @param assignIDFrontIsPredictable if it is guaranteed that only a prefix of assignIDFront.asList() runs through,
	 *        i.e. if any i-th stage in assignIDFront.asList() stalls, so does the i+1-th stage.
	 *        May improve logic overhead in some cases.
	 * @param retireIDFront the PipelineFront in which IDs are to be considered retired / no longer required,
	 *          matching the requirements from {@link IDBasedPipelineStrategy}
	 * @param canPipelineTo Predicate on the node key that indicates if this IDBasedPipelineStrategy instance should pipeline the given key.
	 *        The key will have the purpose set to {@link IDBasedPipelineStrategy#purpose_ReadFromIDBasedPipeline}.
	 */
	public IDBasedPipelineStrategy(Verilog language, BNode bNodes,
			SCAIEVNode node_RdID, NodeInstanceDesc.Key key_RdFlushID, int innerIDWidth,
			PipelineFront assignIDFront, boolean assignIDFrontIsOrdered, boolean assignIDFrontIsPredictable,
			PipelineFront retireIDFront,
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
		this.canPipelineTo = canPipelineTo;
		
		if (assignIDFront.asList().size() > (1 << innerIDWidth)) {
			throw new IllegalArgumentException("innerIDWidth must be large enough to fit all stages from assignIDFront");
		}
		
		this.node_RdInnerID = (innerIDWidth == node_RdID.size) ? node_RdID : new SCAIEVNode("RdID_inner_" + this.uniqueID);
		this.key_RdLastMaxID = new NodeInstanceDesc.Key(PipeIDBuilder.purpose_PipeIDBuilderInternal, new SCAIEVNode("RdLastMaxID_" + uniqueID), assignIDFront.asList().get(0), "");
	}
	
	/** Purpose required to trigger this strategy. Usually, the implement caller converts selected PIPEDIN nodes to this Purpose. */
	public static final Purpose purpose_ReadFromIDBasedPipeline = new Purpose("ReadFromIDBasedPipeline", true, Optional.empty(), List.of());
	
	private SCAIEVNode node_RdInnerID;
	NodeInstanceDesc.Key key_RdLastMaxID;
	

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
			PipelineCondKey other = (PipelineCondKey) obj;
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
			BufferGroupKey other = (BufferGroupKey) obj;
			return aux == other.aux && Objects.equals(baseNode, other.baseNode) && Objects.equals(isax, other.isax);
		}
	}
	
	private int getBufferDepth() {
		if (node_RdID == node_RdInnerID)
			return 1 << innerIDWidth;
		return (1 << innerIDWidth) - 1;
	}
	private String getInvalidIDExpr() {
		return String.format("%d'd%d", innerIDWidth, (1 << innerIDWidth) - 1);
	}
	
	private String buildPipeCondition(NodeRegistryRO registry, RequestedForSet requestedFor, PipelineStage stage, Predicate<PipelineCondKey> filterCond, boolean checkFlush) {
		String pipeCond = 
			"!" + registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, stage, ""), requestedFor)
			+ registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrStall, stage, ""))
			  .map(wrstallCond -> " && !"+wrstallCond.getExpression()).orElse("")
			+ (checkFlush ? (
				" && !" + registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, stage, ""), requestedFor)
				+ registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrFlush, stage, ""))
				  .map(wrflushCond -> " && !"+wrflushCond.getExpression()).orElse("")
				) : ""
			);
		if (!forceAlwaysAssignID && node_RdInnerID != node_RdID) {
			//Only write if the relevant parts of the ID assign condition apply
			pipeCond += subConditionsForIDAssign.stream()
				.filter(filterCond)
				.map(subConditionNode -> registry.lookupRequired(new NodeInstanceDesc.Key(
					Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN,
					subConditionNode.node,
					stage,
					subConditionNode.isax,
					subConditionNode.aux), requestedFor).getExpressionWithParens())
				.reduce((a,b)->a+" || "+b).map(cond -> " && ("+cond+")").orElse("");
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
		//If true, the write ports will all be in the same 'always' block.
		static final boolean WRITEPORTS_UNORDERED = true;
		
		BufferGroupKey groupKey;
		List<SCAIEVNode> allBufferedNodes = new ArrayList<>();
		PipelineFront pipelineFromFront = new PipelineFront();

		RequestedForSet commonRequestedFor = new RequestedForSet();
		List<PipelineToDesc> pipelineToDescs = new ArrayList<>();
		
		public PipeBufferBuilder(String name, NodeInstanceDesc.Key nodeKey, BufferGroupKey groupKey) {
			super(name, nodeKey);
			this.groupKey = groupKey;
		}

		@Override
		protected NodeLogicBlock applyTriggered(NodeRegistryRO registry, int aux) {
			var logicBlock = new NodeLogicBlock();
			String bufferName = "buffer_" + uniqueID + "_" + groupKey.toString() + "_byid";
			
			List<PipelineStage> pipelineFromList = (overriddenPipelineFromFront != null ? overriddenPipelineFromFront : pipelineFromFront).asList();
			
			assert(!pipelineFromList.isEmpty());
			if (pipelineFromList.isEmpty()) {
				logger.error("IDBasedPipelineStrategy has no stages to pipeline from");
				return new NodeLogicBlock();
			}
			
			pipelineToDescs.forEach(pipelineToDesc -> commonRequestedFor.addAll(pipelineToDesc.requestedFor, true));
			
			//Condition as Predicate for reuse: Checks if an entry in allBufferedNode is inferred by checking the translated ID for a magic value (e.g. validReq).
			Predicate<SCAIEVNode> bufferedNodeInferredFromIDValid =
					bufferedNode -> (node_RdInnerID != node_RdID
						&& bufferedNode.isValidNode()
						&& !forceAlwaysAssignID
						&& subConditionsForIDAssign.size() == 1
						&& subConditionsForIDAssign.get(0).node.equals(bufferedNode)
					);
			
			//Retrieve all inner IDs.
			String[] pipelineFromIDExpr = new String[pipelineFromList.size()];
			for (int iPipelineFrom = 0; iPipelineFrom < pipelineFromList.size(); ++iPipelineFrom)
				pipelineFromIDExpr[iPipelineFrom] = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(node_RdInnerID, pipelineFromList.get(iPipelineFrom), ""), commonRequestedFor);
			
			//Compute the buffer offsets for all nodes.
			int[] bufferOffsets = new int[allBufferedNodes.size()+1];
			bufferOffsets[0] = 0;
			for (int iBufferNode = 0; iBufferNode < allBufferedNodes.size(); ++iBufferNode) {
				SCAIEVNode curNode = allBufferedNodes.get(iBufferNode);
				int curSize = curNode.size;
				if (bufferedNodeInferredFromIDValid.test(curNode)) {
					assert(curSize == 1);
					continue;
				}
				bufferOffsets[iBufferNode+1] = bufferOffsets[iBufferNode] + curSize;
			}
			
			int totalBufferSize = bufferOffsets[allBufferedNodes.size()];
			if (totalBufferSize > 0) {
				//Add the buffer declaration.
				logicBlock.declarations += String.format("logic [%d-1:0] %s[%d];\n", bufferOffsets[allBufferedNodes.size()], bufferName, getBufferDepth());
				//Add the declared buffer of one of the pipeline stages just to register the wire name.
				logicBlock.outputs.add(new NodeInstanceDesc(
					new NodeInstanceDesc.Key(new SCAIEVNode("IDBasedPipelineReg_"+uniqueID+"_"+groupKey.toString()), assignIDFront.asList().get(0), ""),
					bufferName,
					ExpressionType.WireName
				));
				
				String updateBufferLogic = "";
				//Add logic to store all buffered nodes from all 'pipeline from' stages.
				for (int iPipelineFrom = 0; iPipelineFrom < pipelineFromList.size(); ++iPipelineFrom) {
					PipelineStage pipelineFromStage = pipelineFromList.get(iPipelineFrom);
					
					String pipeCond = buildPipeCondition(registry, commonRequestedFor, pipelineFromStage, 
						subConditionKey -> 
							(subConditionKey.node.isAdj() ? subConditionKey.node.nameParentNode : subConditionKey.node.name)
							.equals(groupKey.baseNode.name),
						false /* can safely ignore flushes */);
					
					String curFromStageUpdatelogic = "";
					curFromStageUpdatelogic += String.format("if (%s) begin\n", pipeCond);
					curFromStageUpdatelogic += language.tab + String.format("%s[%s] <= {", bufferName, pipelineFromIDExpr[iPipelineFrom]);
					
					boolean prependComma = false;
					//Most significant component is listed first (Verilog), so iterate in reverse.
					int bufferEndOffset = bufferOffsets[allBufferedNodes.size()] - 1;
					
					for (int iBufferNode = allBufferedNodes.size() - 1; iBufferNode >= 0; --iBufferNode) {
						SCAIEVNode curNode = allBufferedNodes.get(iBufferNode);
						int rangeMin = bufferOffsets[iBufferNode];
						int rangeMax = bufferOffsets[iBufferNode + 1] - 1;
						assert(rangeMax == bufferEndOffset);
						if (rangeMax < rangeMin)
							continue;
						var curNodeInst = registry.lookupRequired(new NodeInstanceDesc.Key(Purpose.PIPEOUT, curNode, pipelineFromStage, groupKey.isax, groupKey.aux), commonRequestedFor);
						commonRequestedFor.addAll(curNodeInst.getRequestedFor(), true);
						curFromStageUpdatelogic += String.format("%s%s", prependComma ? ", " : "", curNodeInst.getExpression());
						bufferEndOffset = rangeMin - 1;
						prependComma = true;
					}
					
					curFromStageUpdatelogic += "};\n";
					curFromStageUpdatelogic += "end\n";
					updateBufferLogic += curFromStageUpdatelogic;
					
					if (WRITEPORTS_UNORDERED) {
						logicBlock.logic += language.CreateInAlways(true, updateBufferLogic);
						updateBufferLogic = "";
					}
				}
				if (!updateBufferLogic.isEmpty())
					logicBlock.logic += language.CreateInAlways(true, updateBufferLogic);
			}
			
			
			//Add the read declarations and logic in the destination stages.
			for (PipelineToDesc pipelineToEntry : pipelineToDescs) {
				var pipelineToKey = pipelineToEntry.key;
				assert(pipelineToKey.getPurpose() == Purpose.PIPEDIN);
				
				int iBufferNode = allBufferedNodes.indexOf(pipelineToKey.getNode());
				assert(iBufferNode != -1);
				if (iBufferNode == -1)
					continue;
				
				//Declare the piped in wire.
				String namePipedin = pipelineToKey.toString(false)+"_regpipein";
				logicBlock.declarations += language.CreateDeclSig(pipelineToKey.getNode(), pipelineToKey.getStage(), pipelineToKey.getISAX(), true, namePipedin);
				
				//Create the expression based on the ID.
				String innerID = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(node_RdInnerID, pipelineToKey.getStage(), ""), pipelineToEntry.requestedFor);
				
				String assignExpression;
				if (bufferedNodeInferredFromIDValid.test(pipelineToKey.getNode())) {
					assignExpression = String.format("%s != %d'd%d", innerID, innerIDWidth, getBufferDepth());
				}
				else {
					assignExpression = String.format("%s[%s][%d:%d]", bufferName, innerID, bufferOffsets[iBufferNode + 1] - 1, bufferOffsets[iBufferNode]);
				}
				logicBlock.logic += String.format("assign %s = %s;\n", namePipedin, assignExpression);
				
				logicBlock.outputs.add(new NodeInstanceDesc(pipelineToKey, namePipedin, ExpressionType.WireName, pipelineToEntry.requestedFor));
				logicBlock.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(pipelineToKey, purpose_ReadFromIDBasedPipeline), namePipedin, ExpressionType.AnyExpression, pipelineToEntry.requestedFor));
			}
			return logicBlock;
		}
	}
	
	public PipelineFront getAssignFront() {
		return this.assignIDFront;
	}
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
	 * If used for multi-cycle resource free logic, the user can stall the assign stages while performing the operation (see {@link IDBasedPipelineStrategy#getAssignFront()}).
	 * In case further flushes appear (unlikely in most microarchitectures), the bottom of the range (id_from) would effectively move down, covering older instructions.
	 * @param node any node in the table
	 * @param isax the ISAX field associated with the table (often "")
	 * @param aux the aux field associated with the table (often 0)
	 * @return a key evaluating to a SystemVeilog struct variable or expression with {'flushing', 'id_from' (inclusive), 'id_to' (exclusive)}
	 */
	public NodeInstanceDesc.Key getTableFlushInfoKey(SCAIEVNode node, String isax, int aux) {
		//TODO: Return a key to a struct that details the range of flushed IDs (either internal or global)
		//-> Will need a builder that creates the struct definition and an instantiation.
		//assign inst.flushing = buildAnyFlushCond(registry, assignIDFront.asList().stream(), new RequestedForSet())
		//assign inst.id_from = <has inner id> ? getRdInnerNodeFlushToIDKey() : key_RdFlushID;
		//assign inst.id_to = <TODO track this information;
		//                       need last max of node_RdInnerID across assignFront,
		//                       which depends on what the oldest/'lowest' ID is due to wrap around>
		//                     @(posedge clk) if (rst) newest_id <= 0; else if (any(valid in assignFront)) newest_id <= max(id-oldest_id in assignFront where valid);
		//                     @(posedge clk) if (rst) oldest_id <= 0; else if (any(valid in retireIDFront)) oldest_id <= max(id-oldest_id in retireIDFront where valid);
		throw new Error("Not implemented");
	}
	/**
	 * Retrieves the key that the table builder outputs the buffer field name to.
	 * Note: The table builder is only created in response to a read from the pipeline, i.e., {@link IDBasedPipelineStrategy#purpose_ReadFromIDBasedPipeline}.
	 * The returned key will not resolve if no such read dependency is announced. 
	 * @param node any node in the table
	 * @param isax the ISAX field associated with the table (often "")
	 * @param aux the aux field associated with the table (often 0)
	 * @return
	 */
	public NodeInstanceDesc.Key getTableBufferKey(SCAIEVNode node, String isax, int aux) {
		//Use any valid stage for the table.
		PipelineStage tableStage = assignIDFront.asList().get(0);
		String groupName = getBufferGroupKeyFor(new NodeInstanceDesc.Key(Purpose.REGULAR, node, tableStage, isax, aux)).toString();
		return new NodeInstanceDesc.Key(Purpose.REGULAR, new SCAIEVNode("IDBasedPipelineReg_"+uniqueID+"_"+groupName), tableStage, "");
	}
	
	private NodeInstanceDesc.Key getRdInnerNodeFlushToIDKey() {
		return new NodeInstanceDesc.Key(
			Purpose.REGULAR,
			new SCAIEVNode("IDBasedPipelineReg_"+uniqueID+"_RdInnerNodeFlushToID"),
			assignIDFront.asList().get(0),
			""
		);
	}
	
	protected String buildAnyFlushCond(NodeRegistryRO registry, Stream<PipelineStage> stages, RequestedForSet requestedFor) {
		return stages
			.map(stage -> { return
				registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, stage, ""), requestedFor)
				+ registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrFlush, stage, ""))
				  .map(wrflushCond -> " && !"+wrflushCond.getExpression()).orElse("");})
			.reduce((a,b)->"("+a+") || ("+b+")").get();
	}
	
	/** Forces the assign ID sub-condition to '1'. Not applicable if node_RdInnerID == node_RdID. */
	private boolean forceAlwaysAssignID = false;
	/** Nodes to OR to the assign ID sub-condition. */
	private List<PipelineCondKey> subConditionsForIDAssign = new ArrayList<>();
	
	/** Buffers that are grouped together */
	private HashMap<BufferGroupKey, PipeBufferBuilder> bufferGroups = new HashMap<>();
	
	private class PipeIDBuilder extends TriggerableNodeLogicBuilder {
		List<PipelineStage> stagesToImplementRead = new ArrayList<>();
		RequestedForSet commonRequestedFor = new RequestedForSet();
		
		public PipeIDBuilder(String name, NodeInstanceDesc.Key nodeKey) {
			super(name, nodeKey);
		}
		
		protected static final Purpose purpose_PipeIDBuilderInternal = new Purpose("PipeIDBuilderInternal", true, Optional.empty(), List.of());
		
		private String[] buildNextInnerIDRegisters(NodeLogicBlock logicBlock, 
				List<PipelineStage> phaseStages, String namePhase, 
				String incrementByExpr,
				String flushCond, String flushToInnerIDExpr) {
			
			String updateNextIDWire = String.format("innerID_%d_%sNext_update", uniqueID, namePhase);
			logicBlock.declarations += String.format("wire [%d-1:0] %s;\n", innerIDWidth, updateNextIDWire);
			logicBlock.outputs.add(new NodeInstanceDesc(
				new NodeInstanceDesc.Key(purpose_PipeIDBuilderInternal, node_RdInnerID, phaseStages.get(0), "next"+namePhase+"Update", 0),
				updateNextIDWire,
				ExpressionType.WireName));
			
			String[] phaseInnerNextIDPlusIReg = new String[phaseStages.size() + 1];
			String resetLogic = "", updateRegLogic = ""; 
			for (int iIncr = 0; iIncr < phaseInnerNextIDPlusIReg.length; ++iIncr) {
				phaseInnerNextIDPlusIReg[iIncr] = String.format("innerID_%d_%sNext%s_reg", uniqueID, namePhase, (iIncr > 0 ? ("_plus"+iIncr) : ""));
				logicBlock.declarations += String.format("reg [%d-1:0] %s;\n", innerIDWidth, phaseInnerNextIDPlusIReg[iIncr]);
				logicBlock.outputs.add(new NodeInstanceDesc(
					new NodeInstanceDesc.Key(purpose_PipeIDBuilderInternal, node_RdInnerID, phaseStages.get(0), "next"+namePhase, iIncr),
					phaseInnerNextIDPlusIReg[iIncr],
					ExpressionType.WireName));
				
				//Reset to 0 + iStage
				resetLogic += language.tab + String.format("%s <= %d'd%d;\n", phaseInnerNextIDPlusIReg[iIncr], innerIDWidth, iIncr);
				//Increment by iStage, skipping INVALID (all-1)
				String updateExpr = String.format("%s + %d'd%d", updateNextIDWire, innerIDWidth, iIncr);
				updateExpr = String.format("(%s + %d'd1 <= %s) ? (%s + %d'd1) : (%s)",
					updateExpr, innerIDWidth, updateNextIDWire, 
					updateExpr, innerIDWidth,
					updateExpr);
				updateRegLogic += language.tab + String.format("%s <= %s; // test wrap-around, skip invalid (%s)\n", phaseInnerNextIDPlusIReg[iIncr], updateExpr, getInvalidIDExpr());
			}
			
			String assignExpr = phaseInnerNextIDPlusIReg[0] + " + " + incrementByExpr;
			if (!flushCond.isEmpty())
				assignExpr = String.format("(%s) ? (%s) : (%s)", flushCond, flushToInnerIDExpr, assignExpr);

			logicBlock.logic += String.format("assign %s = %s;\n", updateNextIDWire, assignExpr);
			logicBlock.logic += language.CreateInAlways(true,
				String.format("if (%s) begin\n%send\nelse begin\n%send\n", language.reset, resetLogic, updateRegLogic)
			);
			
			return phaseInnerNextIDPlusIReg;
		}
		
		private String makeInnerIDCountExpr(Stream<String> condExpr) {
			return condExpr.map(wireName -> String.format("%d'(%s)", innerIDWidth, wireName))
				.reduce((a,b) -> a+" + "+b)
				.orElse(String.format("%d'd0", innerIDWidth));
		}
		
		private void makeCountDeclLogic(NodeLogicBlock logicBlock, 
				List<PipelineStage> phaseStages, String[] pipeCondWires, String namePhase,
				String countWireName) {
			
			logicBlock.declarations += String.format("wire [%d-1:0] %s;\n", innerIDWidth, countWireName);
			logicBlock.outputs.add(new NodeInstanceDesc(
				new NodeInstanceDesc.Key(purpose_PipeIDBuilderInternal, node_RdInnerID, phaseStages.get(0), namePhase+"_count"),
				countWireName,
				ExpressionType.WireName));
			logicBlock.logic += String.format("assign %s = %s;\n",
				countWireName,
				makeInnerIDCountExpr(Stream.of(pipeCondWires))
			);
		}

		@Override
		protected NodeLogicBlock applyTriggered(NodeRegistryRO registry, int aux) {
			if (stagesToImplementRead.isEmpty())
				return new NodeLogicBlock(); //Nothing to do
			assert(node_RdInnerID != node_RdID && node_RdInnerID != null);
			
			var logicBlock = new NodeLogicBlock();
			
			List<PipelineStage> assignIDStages = assignIDFront.asList();
			List<PipelineStage> retireIDStages = retireIDFront.asList();
			
			String mappingArray = String.format("innerID_%d_mappingByRdID", uniqueID);

			//Declarations and logic for the assign and retire stage internal IDs
			String assignCountWire = String.format("innerID_%d_assign_count", uniqueID);
			String retireCountWire = String.format("innerID_%d_retire_count", uniqueID);
			
			logicBlock.logic += String.format("// Tracking for 'next inner ID' in retire (%s)\n", 
				retireIDStages.stream().map(stage->stage.getName()).reduce((a,b)->a+","+b).orElse("")
			);
			String[] retireInnerNextIDPlusIRegs = buildNextInnerIDRegisters(logicBlock, retireIDStages, "retire", retireCountWire, "", "");
			
			//When flushing, move the assign pointer back to where RdFlushID points to;
			// if that doesn't point anywhere, move it to the retired counter position.
			logicBlock.logic += String.format("// Tracking for 'next inner ID' in assign (%s)\n", 
					assignIDStages.stream().map(stage->stage.getName()).reduce((a,b)->a+","+b).orElse("")
			);
			String anyAssignmentFlushCond = buildAnyFlushCond(registry, assignIDStages.stream(), commonRequestedFor);
			String anyRetireFlushCond = buildAnyFlushCond(registry, retireIDStages.stream(), commonRequestedFor);
			String flushToInnerID = String.format("%s[%s]", mappingArray, registry.lookupExpressionRequired(key_RdFlushID, commonRequestedFor));
			flushToInnerID = String.format("(%s == %s || %s) ? %s : %s",
				flushToInnerID, getInvalidIDExpr(), anyRetireFlushCond,
				retireInnerNextIDPlusIRegs[0],
				flushToInnerID
			);
			NodeInstanceDesc.Key flushToInnerIDKey = getRdInnerNodeFlushToIDKey();
			String flushToInnerIDWire = flushToInnerIDKey.toString(false);
			logicBlock.declarations += String.format("wire [%d-1:0] %s;\n", innerIDWidth, flushToInnerIDWire);
			logicBlock.logic += String.format("assign %s = %s;\n", flushToInnerIDWire, flushToInnerID);
			String[] assignInnerNextIDPlusIRegs = buildNextInnerIDRegisters(logicBlock, assignIDStages, "assign", assignCountWire,
				anyAssignmentFlushCond, flushToInnerIDWire
			);
			logicBlock.outputs.add(new NodeInstanceDesc(flushToInnerIDKey, flushToInnerIDWire, ExpressionType.WireName));

			String[] assignAnyPipeConditionExprs = new String[assignIDStages.size()];
			String[] assignPipeConditionWires = new String[assignIDStages.size()];
			
			String[] assignStageRdIDExpr = new String[assignIDStages.size()];
			String[] retireStageRdIDExpr = new String[retireIDStages.size()];
			
			//Assign assignPipeConditionWires and assignStageRdIDExpr, apply WrStall for overflow prevention.
			for (int iAssignStage = 0; iAssignStage < assignIDStages.size(); ++iAssignStage) {
				PipelineStage assignIDStage = assignIDStages.get(iAssignStage);
				
				String pipeCondAny = buildPipeCondition(registry, commonRequestedFor, assignIDStage, _subConditionKey -> false, true);
				String pipeCondInner = forceAlwaysAssignID ? pipeCondAny : buildPipeCondition(registry, commonRequestedFor, assignIDStage, _subConditionKey -> true, true);
				
				assignAnyPipeConditionExprs[iAssignStage] = pipeCondAny;
				assignPipeConditionWires[iAssignStage] = String.format("innerID_%d_assignpipecond_%s", uniqueID, assignIDStage.getName());
				logicBlock.declarations += String.format("wire %s;\n", assignPipeConditionWires[iAssignStage]);
				logicBlock.logic += String.format("// Condition: is stage %s assigning a new inner ID?\n", assignIDStage.getName());
				logicBlock.logic += String.format("assign %s = %s;\n", assignPipeConditionWires[iAssignStage], pipeCondInner);
				
				//Check for buffer overflows when adding iAssignStage new elements.
				//If we have one free ID but two or more assign stages, this will simply stall the second assign stage onwards. 
				//We need to check if the first..<iAssignStage>th increment of 'assign ID' would catch up with 'retire ID',
				// indicating a potential overflow.
				//Note1: This condition is kept simple and may stall more often than necessary.
				// The exact condition would also check for stalls and the ordering between the assign stages.
				//Note2: This condition will end up in the main WrStall, and thus will be included in buildPipeCondition at some point. 
				String overflowCond= Stream.of(assignInnerNextIDPlusIRegs)
					.limit(iAssignStage + 2).skip(1)
					.map(assignPlusIExpr -> String.format("%s == %s", assignPlusIExpr, retireInnerNextIDPlusIRegs[0]))
					.reduce((a,b)->a+" || "+b).get();
				String overflowCondWire = String.format("stallFrom_innerID_%d_assignPossiblyFull_%s", uniqueID, assignIDStage.getName());
				logicBlock.declarations += String.format("wire %s;\n", overflowCondWire);
				logicBlock.logic += String.format("// Condition: Inner ID overflow about to occur in assign stage %s\n", assignIDStage.getName());
				logicBlock.logic += String.format("assign %s = %s;\n", overflowCondWire, overflowCond);
				
				logicBlock.outputs.add(new NodeInstanceDesc(
					new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, assignIDStage, "", aux),
					overflowCondWire, 
					ExpressionType.WireName,
					commonRequestedFor
				));
				registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, assignIDStage, ""));
				
				assignStageRdIDExpr[iAssignStage] = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(node_RdID, assignIDStage, ""), commonRequestedFor);
			}

			for (int iRetireStage = 0; iRetireStage < retireIDStages.size(); ++iRetireStage) {
				PipelineStage retireIDStage = retireIDStages.get(iRetireStage);
				
				retireStageRdIDExpr[iRetireStage] = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(node_RdID, retireIDStage, ""), commonRequestedFor);				
			}
			
			//Declare and make assign logic for the assign_count wire.
			logicBlock.logic += String.format("// Number of new ID allocations in assign\n");
			makeCountDeclLogic(logicBlock, assignIDStages, assignPipeConditionWires, "assign", assignCountWire);
			
			
			Function<Integer,String> getStageAssignIDExpr;
			{
				Optional<String> registeredLastMaxRdIDExpr = assignIDFrontIsOrdered
					? Optional.empty()
					: Optional.of(registry.lookupExpressionRequired(key_RdLastMaxID, commonRequestedFor));
				
				String rdIDDistArray = String.format("innerID_%d_RdIDDistanceByStage", uniqueID);
				if (!assignIDFrontIsOrdered) {
					//Add logic to compute the RdID-based instruction ordering (0=first, assignIDStages.size()=last)
					//From checks in the constructor, we know that innerIDWidth is enough to store this range
					// (though it may still be larger than required).
					logicBlock.logic += String.format("logic [%d-1:0] %s [%d];\n", innerIDWidth, rdIDDistArray, assignIDStages.size());
					for (int iAssignStage = 0; iAssignStage < assignIDStages.size(); ++iAssignStage) {
						logicBlock.logic += String.format("assign %s[%d] = %s;\n",
							rdIDDistArray, iAssignStage,
							String.format(
								"%d'(%s - %s) - %d'd1",
								innerIDWidth,
								assignStageRdIDExpr[iAssignStage], registeredLastMaxRdIDExpr.get(),
								innerIDWidth
							)
						);
					}
				}
				
				String skipAmountArray = String.format("innerID_%d_assignSkipAmountByRdIDDistance", uniqueID);
				int maxRdIDDistance = assignIDStages.size() - 1;
				if (!assignIDFrontIsOrdered && !forceAlwaysAssignID) {
					//Since we may have holes in the intended RdID->RdInnerID mapping,
					// aggregate the hole offsets so we know which RdInnerID to assign.
					logicBlock.logic += String.format("logic [%d-1:0] %s [%d];\n", innerIDWidth, skipAmountArray, maxRdIDDistance + 1);
					String assignLogic = "";
					assignLogic += String.format("%s[0] = %d'd0;\n", skipAmountArray, innerIDWidth);
					for (int i = 1; i <= maxRdIDDistance; ++i) {
						//If the stage with (RdID distance == i-1) is valid in the core pipeline world but invalid in our view,
						// the negative offset / subtrahend for any stage with (RdID distance >= i) increases by one.
						int i_ = i; //Java :)
						String curIsInvalidCond = IntStream.range(0, assignIDStages.size())
							.mapToObj(iAssignStage -> String.format(
								"(%s[%d] == %d'd%d && (%s) && !(%s))",
								rdIDDistArray, iAssignStage, innerIDWidth, i_-1, //RdID offset for iAssignStage == i-1
								assignAnyPipeConditionExprs[iAssignStage], //Valid condition for the core pipeline
								assignPipeConditionWires[iAssignStage] //Valid condition from our view
							))
							.reduce((a,b)->a+" || "+b).get();
						//Fill in the negative offset table.
						assignLogic += String.format("%s[%d] = %s[%d] + %d'(%s);\n",
							skipAmountArray, i, //Set distance i..
							skipAmountArray, i-1, //..based on distance i-1..
							innerIDWidth, curIsInvalidCond //..and add 1 if 'curIsInvalidCond'
						);
					}
					logicBlock.logic += language.CreateInAlways(false, assignLogic);
				}
				//Creates the expression for RdInnerID in the given assign stage index.
				getStageAssignIDExpr = iAssignStage -> {
					String offsetExpr;
					if (assignIDFrontIsOrdered) {
						//RdID (and RdInnerID) are monotonic with iAssignStage [in 0- or 1-increments].
						if (assignIDFrontIsPredictable && forceAlwaysAssignID) {
							//There are no 'stall holes' in assignIDStages.
							//-> we statically know the offset
							//   and can just use the existing offset register.
							return assignInnerNextIDPlusIRegs[iAssignStage];
						}
						String previousStageCondSum = makeInnerIDCountExpr(Stream.of(assignPipeConditionWires).limit(iAssignStage));
						offsetExpr = previousStageCondSum;
					}
					else {
						offsetExpr = String.format("%s[%d]", rdIDDistArray, iAssignStage);
						if (!forceAlwaysAssignID) {
							//Very slow: RdID is unordered, thus the sparse RdInnerID cannot directly be reconstructed from RdID.
							//Use the combinational table added above.
							offsetExpr = String.format("(%s - %s[%s])", offsetExpr, skipAmountArray, offsetExpr);
						}
					}
					//The final addition could be substituted by MUXing from assignInnerNextIDPlusIRegs,
					// but with how small the IDs usually are (<< 8 bits), no sensible improvement should be expected from that.
					return String.format("(%s + %s)", assignInnerNextIDPlusIRegs[0], offsetExpr);
				};
			}
			
			
			String[] retirePipeConditionWires = new String[retireIDStages.size()];
			logicBlock.logic += "// Condition: Retire inner ID\n";
			//Assign retirePipeConditionWires.
			for (int iRetireStage = 0; iRetireStage < retireIDStages.size(); ++iRetireStage) {
				PipelineStage retireIDStage = retireIDStages.get(iRetireStage);
				
				String rdIDExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(node_RdID, retireIDStage, ""), commonRequestedFor);
				String pipeCondAny = buildPipeCondition(registry, commonRequestedFor, retireIDStage, _subConditionKey -> false, true);
				String pipeCondInner = forceAlwaysAssignID ? pipeCondAny : String.format(
					"%s && %s[%s] != %d'd%d", 
					pipeCondAny, mappingArray, rdIDExpr, 
					innerIDWidth, (1 << innerIDWidth) - 1 // otherwise assign the 'invalid ID' marker
				);
				
				retirePipeConditionWires[iRetireStage] = String.format("innerID_%d_retirepipecond_%s", uniqueID, retireIDStage.getName());
				logicBlock.declarations += String.format("wire %s;\n", retirePipeConditionWires[iRetireStage]);
				logicBlock.logic += String.format("assign %s = %s;\n", retirePipeConditionWires[iRetireStage], pipeCondInner);
			}

			logicBlock.logic += String.format("// Number of newly retired ID allocations\n");
			//Declare and make assign logic for the retire_count wire.
			makeCountDeclLogic(logicBlock, retireIDStages, retirePipeConditionWires, "retire", retireCountWire);
			
			logicBlock.logic += "always_comb begin\n"
					+ language.tab.repeat(1) + String.format("if (%s > (%s - %s)) begin\n", 
							retireCountWire, retireInnerNextIDPlusIRegs[0], assignInnerNextIDPlusIRegs[0])
					+ language.tab.repeat(2) + String.format("$display(\"ERROR: Underflow in IDBasedPipelineStrategy(%d)\");\n", uniqueID)
					+ language.tab.repeat(2) + String.format("$stop;\n", uniqueID)
					+ language.tab.repeat(1) + "end\n"
					+ "end\n";

			logicBlock.declarations += String.format("reg [%d-1:0] %s [%d];\n", innerIDWidth, mappingArray, 1 << node_RdID.size);
			String mappingUpdateLogic = "";
			//Mapping table invalidation logic for retire.
			for (int iRetireStage = 0; iRetireStage < retireIDStages.size(); ++iRetireStage) {
				mappingUpdateLogic += language.tab + String.format(
					"if (%s)\n%s%s[%s] <= %s;\n",
					retirePipeConditionWires[iRetireStage], //Only update if this retire does run through.
					language.tab.repeat(2), mappingArray, retireStageRdIDExpr[iRetireStage], //Update the mapping array for the retire stage's RdID.
					getInvalidIDExpr() //Update the retired entry to the 'invalid ID' marker.
				);
			}
			logicBlock.logic += "// Current inner ID in assign stages\n";
			//Build RdInnerID for the assign stages, and update the mapping table.
			for (int iAssignStage = 0; iAssignStage < assignIDStages.size(); ++iAssignStage) {
				PipelineStage assignIDStage = assignIDStages.get(iAssignStage);

				var assignStageRdInnerIDKey = new NodeInstanceDesc.Key(node_RdInnerID, assignIDStage, "");
				String assignStageRdInnerIDWire = assignStageRdInnerIDKey.toString(false) + "_s";
				logicBlock.declarations += String.format("wire [%d-1:0] %s;\n", innerIDWidth, assignStageRdInnerIDWire);
				logicBlock.outputs.add(new NodeInstanceDesc(
					assignStageRdInnerIDKey,
					assignStageRdInnerIDWire,
					ExpressionType.WireName,
					commonRequestedFor
				));
				
				String assignStageRdInnerIDExpr = getStageAssignIDExpr.apply(iAssignStage);
				logicBlock.logic += String.format("assign %s = %s;\n", assignStageRdInnerIDWire, assignStageRdInnerIDExpr);
				
				//mappingResetLogic += language.tab + String.format("%s[%d] <= %d'd%d;\n", mappingArray, iAssignStage, innerIDWidth, (1 << innerIDWidth) - 1);
				String updateExpr = forceAlwaysAssignID ? assignStageRdInnerIDWire : String.format(
					"%s ? %s : %s",
					assignPipeConditionWires[iAssignStage], // if we have assigned an inner ID, 
					assignStageRdInnerIDWire, // then assign that inner ID,
					getInvalidIDExpr() // otherwise assign the 'invalid ID' marker
				);
				mappingUpdateLogic += language.tab + String.format(
					"if (%s)\n%s%s[%s] <= %s;\n",
					assignAnyPipeConditionExprs[iAssignStage], //Only update if this assign stage does run through.
					language.tab.repeat(2), mappingArray, assignStageRdIDExpr[iAssignStage], //Update the mapping array for the assign stage's RdID.
					updateExpr
				);
			}
			String mappingResetLogic = "";
			for (int iID = 0; iID < (1 << node_RdID.size); ++iID) {
				mappingResetLogic += language.tab + String.format("%s[%d] <= %s;\n", mappingArray, iID, getInvalidIDExpr());
			}
			logicBlock.logic += "// ID map update logic\n";
			logicBlock.logic += language.CreateInAlways(true,
				String.format("if (%s) begin\n%send\nelse begin\n%send\n", language.reset, mappingResetLogic, mappingUpdateLogic)
			);
			
			//Output all requested RdInnerID.
			logicBlock.logic += "// Current inner ID in other stages\n";
			for (PipelineStage readStage : stagesToImplementRead) if (!assignIDFront.contains(readStage)) {
				var readStageRdInnerIDKey = new NodeInstanceDesc.Key(node_RdInnerID, readStage, "");
				String readStageRdInnerIDWire = readStageRdInnerIDKey.toString(false) + "_s";
				logicBlock.declarations += String.format("wire [%d-1:0] %s;\n", innerIDWidth, readStageRdInnerIDWire);
				logicBlock.outputs.add(new NodeInstanceDesc(
					readStageRdInnerIDKey,
					readStageRdInnerIDWire,
					ExpressionType.WireName,
					commonRequestedFor
				));
				String rdIDExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(node_RdID, readStage, ""), commonRequestedFor);
				//Read the inner ID from the mapping array.
				logicBlock.logic += String.format("assign %s = %s[%s];\n", readStageRdInnerIDWire, mappingArray, rdIDExpr);
			}
			
			return logicBlock;
		}
		
	}
	private PipeIDBuilder pipeIDBuilder = null;
	
	private Map<SCAIEVNode,SCAIEVNode> customGroupMapping = new HashMap<>();
	/**
	 * Adds a node to group together with another, despite (possibly) not being adjacent to the reference node.
	 * Note: Nodes not explicitly mapped will be treated as their own group if non-adjacent, or grouped by their parent if adjacent.
	 * The caller should choose the reference node as the base node of a group, consistent with the other nodes to be included in the group. 
	 */
	public void mapToGroup(SCAIEVNode nodeToGroup, SCAIEVNode referenceNode) {
		customGroupMapping.put(nodeToGroup, referenceNode);
	}
	
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
	
	@Override
	public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
		var nodeKeyIter = nodeKeys.iterator();
		while (nodeKeyIter.hasNext()) {
			NodeInstanceDesc.Key nodeKey = nodeKeyIter.next();
			
			//Can only operate in the range from assign to retire.
			if (!assignIDFront.isAroundOrBefore(nodeKey.getStage(), false)
					|| !retireIDFront.isAroundOrAfter(nodeKey.getStage(), false))
				continue;
			
			if (nodeKey.getPurpose().equals(purpose_ReadFromIDBasedPipeline)
					&& assignIDFront.isBefore(nodeKey.getStage(), false)
					&& canPipelineTo.test(nodeKey)) {
				
				//Get or create the builder for the group corresponding to nodeKey.
				var groupKey = getBufferGroupKeyFor(nodeKey);
				var builderAddedToOut = new Object() { boolean val = false; };
				var groupBuilder = bufferGroups.computeIfAbsent(groupKey, groupKey_ -> {
					var builder = new PipeBufferBuilder(
						String.format("IDBasedPipelineStrategy(%d)_%s",uniqueID,groupKey_.toString()),
						new NodeInstanceDesc.Key(Purpose.REGULAR, groupKey_.baseNode, nodeKey.getStage(), groupKey_.isax, groupKey_.aux),
						groupKey_
					);
					out.accept(builder);
					builderAddedToOut.val = true;

					return builder;
				});
				if (!builderAddedToOut.val) {
					//Make sure *some* builder is added to out, so NodeRegPipelineStrategy does not try building it another way.
					out.accept(NodeLogicBuilder.fromFunction(String.format("IDBasedPipelineStrategy(%d)-dummy_%s",uniqueID,nodeKey.toString()), registry -> {
						var ret = new NodeLogicBlock();
						ret.treatAsNotEmpty = true;
						return ret;
					}));
				}
				
				//Add the current key to the builder as 'pipeline to' destination, and add its earlier stages as 'pipeline from' sources.
				var pipelineToKey = new NodeInstanceDesc.Key(Purpose.PIPEDIN, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX(), nodeKey.getAux());
				if (!groupBuilder.pipelineToDescs.stream().anyMatch(pipelineToDesc -> pipelineToDesc.key.equals(pipelineToKey))) {
					groupBuilder.pipelineToDescs.add(new PipelineToDesc(pipelineToKey));
					
					if (!groupBuilder.allBufferedNodes.contains(nodeKey.getNode()))
						groupBuilder.allBufferedNodes.add(nodeKey.getNode());
					
					//TODO: Apply pipeline from override
					List<PipelineStage> newPipelineFromList = null;
					for (PipelineStage pipelineFromStage : nodeKey.getStage().getPrev()) {
						if (!groupBuilder.pipelineFromFront.contains(pipelineFromStage)
								&& assignIDFront.isAroundOrBefore(pipelineFromStage, false)) {
							if (newPipelineFromList == null)
								newPipelineFromList = new ArrayList<>(groupBuilder.pipelineFromFront.asList());
							newPipelineFromList.add(pipelineFromStage);
						}
					}
					if (newPipelineFromList != null)
						groupBuilder.pipelineFromFront = new PipelineFront(newPipelineFromList);
					
					groupBuilder.trigger(out);
					
					//Add the corresponding valid condition for the ID builder.
					SCAIEVNode baseNode = bNodes.GetNonAdjNode(nodeKey.getNode());
					Optional<SCAIEVNode> validBy = (nodeKey.getNode().isValidNode() || nodeKey.getNode().getAdj() == AdjacentNode.cancelReq)
						? Optional.of(nodeKey.getNode())
						: bNodes.GetAdjSCAIEVNode(baseNode, nodeKey.getNode().validBy).filter(validByNode -> validByNode.isValidNode());
					
					if (!validBy.isPresent()) {
						if (!forceAlwaysAssignID)
							bufferGroups.values().forEach(curGroupBuilder -> curGroupBuilder.trigger(out));
						forceAlwaysAssignID = true;
						if (pipeIDBuilder != null) pipeIDBuilder.trigger(out);
					}
					else {
						var newSubcondKey = new PipelineCondKey(validBy.get(), nodeKey.getISAX(), nodeKey.getAux());
						if (!subConditionsForIDAssign.contains(newSubcondKey)) {
							subConditionsForIDAssign.add(newSubcondKey);
							bufferGroups.values().forEach(curGroupBuilder -> curGroupBuilder.trigger(out));
							if (pipeIDBuilder != null) pipeIDBuilder.trigger(out);
						}
					}			
				}
				
				nodeKeyIter.remove();
			}
			else if (innerIDWidth < node_RdID.size
					&& nodeKey.getPurpose().matches(Purpose.REGULAR)
					&& nodeKey.getNode().equals(this.node_RdInnerID)) {
				
				if (pipeIDBuilder == null) {
					pipeIDBuilder = new PipeIDBuilder(String.format("IDBasedPipelineStrategy(%d)_RdInnerID",uniqueID), nodeKey);
					out.accept(pipeIDBuilder);
				}
				
				if (!pipeIDBuilder.stagesToImplementRead.contains(nodeKey.getStage())) {
					pipeIDBuilder.stagesToImplementRead.add(nodeKey.getStage());
					pipeIDBuilder.trigger(out);
				}
				
				nodeKeyIter.remove();
			}
			else if (innerIDWidth < node_RdID.size
					&& nodeKey.equals(key_RdLastMaxID)) {
				RequestedForSet requestedFor = new RequestedForSet();
				out.accept(NodeLogicBuilder.fromFunction(String.format("IDBasedPipelineStrategy(%d)_RdLastMaxID",uniqueID), registry -> {
					var logicBlock = new NodeLogicBlock();
					List<String> maxIDCandidates = assignIDFront.asList().stream()
						.map(assignStage -> registry.lookupExpressionRequired(new NodeInstanceDesc.Key(node_RdID, assignStage, ""), requestedFor))
						.collect(Collectors.toCollection(ArrayList::new));
					//Generate a binary reduction tree to get the maximum ID.
					int outerIndex = 0;
					while (maxIDCandidates.size() > 1) {
						boolean isLastRound = (maxIDCandidates.size() == 2);
						int iOutput = 0;
						for (int iIn=0; iIn < maxIDCandidates.size(); ++iIn,++iOutput) {
							String inCandidateA = maxIDCandidates.get(iIn);
							if (iIn + 1 < maxIDCandidates.size()) {
								String inCandidateB = maxIDCandidates.get(iIn + 1);
								String combinedExpr = String.format(
									"((%s > %s) ? %s : %s)", 
									inCandidateA, inCandidateB,
									inCandidateA, inCandidateB
								);
								if (!isLastRound) {
									//Store intermediate values in a wire, so the expression doesn't explode.
									String combinedExprWire = String.format("%s_reduce_%d_%d", nodeKey.toString(false), outerIndex, iOutput);
									logicBlock.declarations += String.format("wire [%d-1:0] %s;\n", nodeKey.getNode().size, combinedExprWire);
									logicBlock.logic += String.format("assign %s = %s;\n", combinedExprWire, combinedExpr);
									combinedExpr = combinedExprWire;
								}
								maxIDCandidates.set(iOutput, combinedExpr);
								++iIn;
							}
							else
								maxIDCandidates.set(iOutput, inCandidateA);
						}
						//Trim unused space of the candidate list.
						maxIDCandidates.subList(iOutput, maxIDCandidates.size()).clear();
						++outerIndex;
					}
					//Create the register based on the combinational maximum ID.
					String maxIDReg = nodeKey.toString(false) + "_reg";
					logicBlock.declarations += String.format("wire [%d-1:0] %s;\n", nodeKey.getNode().size, maxIDReg);
					logicBlock.logic += language.CreateInAlways(true, String.format("%s <= %s;\n", maxIDReg, maxIDCandidates.get(0)));
					logicBlock.outputs.add(new NodeInstanceDesc(key_RdLastMaxID, maxIDReg, ExpressionType.WireName, requestedFor));
					return logicBlock;
				}));
				
				nodeKeyIter.remove();
			}
		}
	}

}
