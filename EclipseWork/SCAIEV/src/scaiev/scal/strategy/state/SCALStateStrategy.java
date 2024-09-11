package scaiev.scal.strategy.state;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.util.ListRemoveView;
import scaiev.util.Verilog;

public class SCALStateStrategy extends MultiNodeStrategy {

	// logging
	protected static final Logger logger = LogManager.getLogger();

	public BNode bNodes;
	private HashMap<SCAIEVNode, HashMap<PipelineStage,HashSet<String>>> op_stage_instr;
	private Verilog language;
	private Core core;
	private StrategyBuilders strategyBuilders;

	public  SCALStateStrategy (StrategyBuilders strategyBuilders, Verilog language, BNode bNodes, Core core, HashMap<SCAIEVNode, HashMap<PipelineStage,HashSet<String>>> op_stage_instr/*, PipelineFront commitStages*/) {
		this.strategyBuilders = strategyBuilders;
		this.bNodes = bNodes;
		this.op_stage_instr = op_stage_instr;
		this.language = language; 
		this.core = core;
	}
	
	private static int log2(int n){
	    if(n < 0) throw new IllegalArgumentException();
	    return 31 - Integer.numberOfLeadingZeros(n);
	}
	
	private static int clog2(int n) {
		 if(n <= 0) throw new IllegalArgumentException();
		 return log2(n-1)+1;
	}
	
	private static final SCAIEVNode RegfileModuleNode = new SCAIEVNode("__RegfileModule");
	private static final SCAIEVNode RegfileModuleSingleregNode = new SCAIEVNode("__RegfileModuleSinglereg");
	
	private static String makeRegModuleSignalName(String regName, String signalName, int portIdx) {
		return regName+"_"+signalName+(portIdx>0?"_"+(portIdx+1):"");
	}
	
	boolean implementSingle(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey, boolean isLast) {
		if(!bNodes.IsUserBNode(nodeKey.getNode()))
			return false;
		
		SCAIEVNode baseNode = bNodes.GetNonAdjNode(nodeKey.getNode());
		var baseNodeNonspawn_opt = bNodes.GetEquivalentNonspawnNode(baseNode);
		if (baseNodeNonspawn_opt.isEmpty()) {
			assert(false);
			return false;
		}
		SCAIEVNode baseNodeNonspawn = baseNodeNonspawn_opt.get();
		var addrNode_opt = bNodes.GetAdjSCAIEVNode(baseNodeNonspawn, AdjacentNode.addr);
		if (addrNode_opt.isEmpty())
			return false;
		
		SCAIEVNode addrNode = addrNode_opt.get();
		//TODO (ZOL support): Get a PipelineFront matching the earliest custom reg issue stage (with RdIValid presence)
		Set<PipelineStage> addrPipelineFrontSet = new HashSet<>(op_stage_instr.getOrDefault(addrNode, new HashMap<>()).keySet());
		PipelineFront baseNodeFront = new PipelineFront(op_stage_instr.getOrDefault(baseNode, new HashMap<>()).keySet());

		if(nodeKey.getPurpose().matches(Purpose.PIPEDIN)
				&& (!addrPipelineFrontSet.contains(nodeKey.getStage()) || baseNodeFront.isBefore(nodeKey.getStage(), false))
				&& !nodeKey.getNode().isSpawn() && isLast){
			//Pipeline the read data or read/write address 
			var pipelineStrategy = strategyBuilders.buildNodeRegPipelineStrategy(
				language, bNodes, new PipelineFront(nodeKey.getStage()), false, false, true,
				_nodeKey -> true, _nodeKey -> false,
				MultiNodeStrategy.noneStrategy
			);
			pipelineStrategy.implement(out, new ListRemoveView<>(List.of(nodeKey)), false);
			return true;
		}
		
		if (nodeKey.getPurpose().matches(Purpose.MARKER_CUSTOM_REG)) {
			if (addrPipelineFrontSet.isEmpty()) {
				logger.error("Custom registers: Found no address/issue stage for {}", baseNode.name);
				return true;
			}
			final PipelineFront addrPipelineFront = new PipelineFront(addrPipelineFrontSet);
			final PipelineStage addrP = addrPipelineFront.asList().get(0);
			if(addrPipelineFrontSet.size() > 1) {
				logger.error("Encountered multiple address signals in different stages for the same Rd/WrReg! Will pretend there is only the first one: {}.", addrP.getName());
			}
			out.accept(NodeLogicBuilder.fromFunction("SCALStateBuilder("+nodeKey.toString()+")",(NodeRegistryRO registry, Integer aux) -> {
				final String tab = language.tab;
				int elements = baseNode.elements;
				NodeLogicBlock ret = new NodeLogicBlock();
				SCAIEVNode node = nodeKey.getNode();
				
				SCAIEVNode wrRegularNode = bNodes.GetSCAIEVNode(bNodes.GetNameWrNode(baseNode));
				SCAIEVNode wrSpawnNode = bNodes.GetEquivalentSpawnNode(wrRegularNode)
						.orElse(new SCAIEVNode("MISSING~"+bNodes.GetNameWrNode(baseNode)+"_spawn"));
				List<PipelineStage> relevantWrStages = Stream.concat(
						//Take all regular stages with non-spawn nodes
						Optional.ofNullable(op_stage_instr.get(wrRegularNode)).map(stages_instrs -> stages_instrs.entrySet().stream()).orElse(Stream.empty())
							.filter(stage_instrs_entry -> stage_instrs_entry.getKey().getKind() != StageKind.Decoupled),
						//and all decoupled stages with the spawn write node
						Optional.ofNullable(op_stage_instr.get(wrSpawnNode)).map(stages_instrs -> stages_instrs.entrySet().stream()).orElse(Stream.empty())
							.filter(stage_instrs_entry -> stage_instrs_entry.getKey().getKind() == StageKind.Decoupled))
					.filter(stage_instrs_entry -> !stage_instrs_entry.getValue().isEmpty())
					.map(stage_instrs_entry -> stage_instrs_entry.getKey())
					.toList();
				assert(relevantWrStages.stream().distinct().count() == relevantWrStages.size());
				
				final String[] rSignals = {"raddr_i","rdata_o","re_i",
						"waddr_i","wdata_i","we_i"};
				final int addrSize = clog2(elements);
				int[] rSignalsize = {addrSize,node.size,1,
						addrSize,node.size,1};
				int[] rSignalcount = {1,1,1,
						relevantWrStages.size(),relevantWrStages.size(),relevantWrStages.size()};
				final String rSignal_raddr = rSignals[0];
				final String rSignal_rdata = rSignals[1];
				final String rSignal_re = rSignals[2];
				final String rSignal_waddr = rSignals[3];
				final String rSignal_wdata = rSignals[4];
				final String rSignal_we = rSignals[5];

				String regName = node.name.substring(2);
				String dirtyRegName = String.format("%s_dirty",regName);
				
				if(node.name.startsWith("Rd")) {
					ret.declarations += String.format("reg [%d-1:0] %s;\n", elements, dirtyRegName);
					
					String regfileModuleName = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(Purpose.MARKER_CUSTOM_REG, 
							elements > 1 ? RegfileModuleNode : RegfileModuleSingleregNode, core.GetRootStage(), ""));
					
					for(int i = 0; i < rSignals.length; i++) {
						if (rSignalsize[i] == 0)
							continue;
						for (int j = 0; j < rSignalcount[i]; ++j) {
							ret.declarations +="logic["+(rSignalsize[i]-1)+":0] "+makeRegModuleSignalName(regName, rSignals[i], j)+";\n";
						}
					}
					ret.logic += regfileModuleName+" #(.DATA_WIDTH("+node.size+")"
							+(elements>1 ? (",.ELEMENTS("+elements+")") :"")
							+",.WB_PORTS("+relevantWrStages.size()+"))"
							+"reg_"+regName+"(\n";
					
					for(int i = 0; i < rSignals.length; i++) {
						if (rSignalsize[i] == 0)
							continue;
						String rSignal = rSignals[i];
						ret.logic +="."+rSignal+"(";
						if (i >= 3)
							ret.logic+="'{";
						//Comma-separated port signal names from 0..rSignalcount[i]
						ret.logic += IntStream.range(0, rSignalcount[i])
							.mapToObj(j -> makeRegModuleSignalName(regName, rSignal, j))
							.reduce((a,b)->a+","+b).orElse("");
						if (i >= 3)
							ret.logic+="}";
						ret.logic +="),\n";
					}
					ret.logic += ".*);\n";
					

					var nodeKey_readInAddrStage = new NodeInstanceDesc.Key(baseNodeNonspawn, addrP, "");
					String wireName_readInAddrStage = nodeKey_readInAddrStage.toString(false)+"_s";
					ret.declarations += String.format("logic [%d-1:0] %s;\n", baseNodeNonspawn.size, wireName_readInAddrStage);
					ret.outputs.add(new NodeInstanceDesc(nodeKey_readInAddrStage, wireName_readInAddrStage, ExpressionType.WireName));
					
					String wireName_dhInAddrStage = String.format("dhRd%s_%s", regName, addrP.getName());
					ret.declarations += String.format("logic %s;\n", wireName_dhInAddrStage);
					// add a WrFlush signal (equal to the wrPC_valid signal) as an output with aux != 0
					// StallFlushDeqStrategy will collect this flush signal
					ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.REGULAR, bNodes.WrStall, addrP, "", aux), wireName_dhInAddrStage, ExpressionType.WireName));
					registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, addrP, ""));
					
					ret.logic +="always_comb begin \n";
					
					ret.logic += tab+String.format("%s = %s;\n", wireName_readInAddrStage, makeRegModuleSignalName(regName, rSignal_rdata, 0));
					ret.logic += tab+String.format("%s = 0;\n", makeRegModuleSignalName(regName, rSignal_re, 0));
					if (elements > 1)
						ret.logic += tab+tab+String.format("%s = 'x;\n", makeRegModuleSignalName(regName, rSignal_raddr, 0));
					ret.logic += tab+String.format("%s = 0;\n", wireName_dhInAddrStage);	
					
					//Get the early address field that all ISAXes should provide at this point.
					// ValidMuxStrategy will implement the ISAX selection logic.
					String rdAddrExpr = (elements > 1)
						? registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(baseNodeNonspawn, AdjacentNode.addr).orElseThrow(), addrP, ""))
						: "0";
					String rdRegAddrValidExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(baseNodeNonspawn, AdjacentNode.addrReq).orElseThrow(), addrP, ""));

					ret.logic += tab+"if("+rdRegAddrValidExpr+") begin\n";
					ret.logic += tab+tab+String.format("%s = 1;\n", makeRegModuleSignalName(regName, rSignal_re, 0));
					if (elements > 1)
						ret.logic += tab+tab+String.format("%s = %s;\n", makeRegModuleSignalName(regName, rSignal_raddr, 0), rdAddrExpr);
					
					ret.logic += tab+tab+String.format("%s = %s[%s] == 1;\n", wireName_dhInAddrStage, dirtyRegName, rdAddrExpr);
					ret.logic += tab+"end\n";
					
					ret.logic +="end \n";
				}
				if(node.name.startsWith("Wr")) {
					
					String wrDirtyLines = "";
					
					wrDirtyLines +=String.format("always_ff @ (posedge %s) begin\n", language.clk);
					wrDirtyLines +=tab+String.format("if(%s) begin \n", language.reset);
					wrDirtyLines +=tab+tab+String.format("%s <= 0;\n", dirtyRegName);
					wrDirtyLines +=tab+"end else begin \n";

					String earlyWrAddrExpr = (elements > 1)
						? registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(baseNodeNonspawn, AdjacentNode.addr).orElseThrow(), addrP, ""))
						: "0";
					String earlyWrAddrValidExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(baseNodeNonspawn, AdjacentNode.addrReq).orElseThrow(), addrP, ""));
					
					
					{
						//Stall the address stage if a new write has an address marked dirty.
						String wireName_dhInAddrStage = String.format("dhWr%s_%s", regName, addrP.getName());
						ret.declarations += String.format("wire %s;\n", wireName_dhInAddrStage);
						ret.logic += String.format("assign %s = %s && %s[%s];\n", wireName_dhInAddrStage, earlyWrAddrValidExpr, dirtyRegName, earlyWrAddrExpr);
						ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.REGULAR, bNodes.WrStall, addrP, "", aux), wireName_dhInAddrStage, ExpressionType.WireName));
						registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, addrP, ""));
					}

					String stallAddrStageCond = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, addrP, ""));
					Optional<NodeInstanceDesc> wrstallAddrStage = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrStall, addrP, ""));
					if (wrstallAddrStage.isPresent())
						stallAddrStageCond += " || " + wrstallAddrStage.get().getExpression();

					String isImmediateWriteCond = "";
					for (int iPort = 0; iPort < relevantWrStages.size(); ++iPort) {
						PipelineStage wrStage = relevantWrStages.get(iPort);
						assert(!node.isAdj());
						SCAIEVNode wrNode = (wrStage.getKind() == StageKind.Decoupled) ? wrSpawnNode : wrRegularNode;
						String wrDataExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(wrNode, wrStage, ""));
						String wrValidExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(wrNode, AdjacentNode.validReq).orElseThrow(), wrStage, ""));
						String wrCancelExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(wrNode, AdjacentNode.cancelReq).orElseThrow(), wrStage, ""));
						String wrAddrExpr = (elements > 1)
							? registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.GetAdjSCAIEVNode(wrNode, AdjacentNode.addr).orElseThrow(), wrStage, ""))
							: "";
						if (wrStage == addrP)
							isImmediateWriteCond += (isImmediateWriteCond.isEmpty() ? "" : " || ") + wrValidExpr;

						String stallDataStageCond = "1'b0";
						if (wrStage.getKind() != StageKind.Decoupled) {
							stallDataStageCond = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, wrStage, ""));
							Optional<NodeInstanceDesc> wrstallDataStage = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrStall, wrStage, ""));
							if (wrstallDataStage.isPresent())
								stallDataStageCond += " || " + wrstallDataStage.get().getExpression();
						}

						wrDirtyLines += tab+tab+String.format("if ((%s || %s) && !(%s)) begin\n", wrValidExpr, wrCancelExpr, stallDataStageCond);
						if (elements > 1)
							wrDirtyLines += tab+tab+tab+String.format("%s[%s] <= 0;\n", dirtyRegName, wrAddrExpr);
						else
							wrDirtyLines += tab+tab+tab+String.format("%s <= 0;\n", dirtyRegName);
						wrDirtyLines += tab+tab+"end\n";
						
						//Combinational validResp wire.
						//Expected interface behavior is to have the validResp registered, which is done based off of this wire.
						String validRespWireName = "";
						Optional<SCAIEVNode> wrValidRespNode_opt = bNodes.GetAdjSCAIEVNode(wrNode, AdjacentNode.validResp);
						if (wrValidRespNode_opt.isPresent()) {
							var validRespKey = new NodeInstanceDesc.Key(wrValidRespNode_opt.get(), wrStage, "");
							validRespWireName = validRespKey.toString(false) + "_next_s";
							String validRespRegName = validRespKey.toString(false) + "_r";
							ret.declarations += String.format("logic %s;\n", validRespWireName);
							ret.declarations += String.format("logic %s;\n", validRespRegName);
							ret.outputs.add(new NodeInstanceDesc(validRespKey, validRespRegName, ExpressionType.WireName));
							//Also register the combinational validResp as an output (differentiated with MARKER_CUSTOM_REG),
							// just to have a WireName node registered and get a free duplicate check.
							ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(validRespKey, Purpose.MARKER_CUSTOM_REG), validRespWireName, ExpressionType.WireName));
							
							ret.logic += language.CreateInAlways(true, String.format("%s <= %s;\n", validRespRegName, validRespWireName));
						}
						
						String wrDataLines = "";
						wrDataLines += "always_comb begin\n";
						if (elements > 1)
							wrDataLines += tab+String.format("%s = %d'dX;\n", makeRegModuleSignalName(regName, rSignal_waddr, iPort), addrSize);
						wrDataLines += tab+String.format("%s = %d'dX;\n", makeRegModuleSignalName(regName, rSignal_wdata, iPort), node.size);
						wrDataLines += tab+String.format("%s = 0;\n", makeRegModuleSignalName(regName, rSignal_we, iPort));
						if (wrValidRespNode_opt.isPresent())
							wrDataLines += tab+String.format("%s = 0;\n", validRespWireName);
						
						wrDataLines += tab+String.format("if (%s && !(%s)) begin\n", wrValidExpr, stallDataStageCond);
						if (elements > 1)
							wrDataLines += tab+tab+String.format("%s = %s;\n", makeRegModuleSignalName(regName, rSignal_waddr, iPort), wrAddrExpr);
						wrDataLines += tab+tab+String.format("%s = %s;\n", makeRegModuleSignalName(regName, rSignal_wdata, iPort), wrDataExpr);
						wrDataLines += tab+tab+String.format("%s = 1;\n", makeRegModuleSignalName(regName, rSignal_we, iPort));
						if (wrValidRespNode_opt.isPresent())
							wrDataLines += tab+tab+String.format("%s = 1;\n", validRespWireName);
						wrDataLines += tab+"end\n";
						
						wrDataLines += "end\n";
						ret.logic += wrDataLines;
					}
					
					wrDirtyLines += tab+tab+String.format("if (%s && !(%s)) begin\n", earlyWrAddrValidExpr, stallAddrStageCond);
					wrDirtyLines += tab+tab+tab+String.format("%s[%s] <= %s;\n", dirtyRegName, earlyWrAddrExpr, isImmediateWriteCond.isEmpty() ? 1 : ("!("+isImmediateWriteCond+")"));
					wrDirtyLines += tab+tab+"end\n";
					wrDirtyLines += tab+"end\n";
					wrDirtyLines += "end\n";
					ret.logic += wrDirtyLines;
				}
				
				ret.outputs.add(new NodeInstanceDesc(nodeKey, "", ExpressionType.AnyExpression));
				return ret;
			}));
			return true;
		}
			return false;
	}
	
	private HashSet<SCAIEVNode> implementedRegfileModules = new HashSet<>();

	private boolean implementRegfileModule(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
		if (nodeKey.getPurpose().matches(Purpose.MARKER_CUSTOM_REG) && nodeKey.getStage().getKind() == StageKind.Root
				&& (nodeKey.getNode().equals(RegfileModuleNode) || nodeKey.getNode().equals(RegfileModuleSingleregNode))) {
			if (nodeKey.getAux() != 0 || !nodeKey.getISAX().isEmpty())
				return false;
			if (!implementedRegfileModules.add(nodeKey.getNode()))
				return true;
			
			boolean singleVariant = nodeKey.getNode().equals(RegfileModuleSingleregNode);
			out.accept(NodeLogicBuilder.fromFunction("SCALStateStrategy_RegfileModule", registry -> {
				var ret = new NodeLogicBlock();
				String moduleName = singleVariant ? "svsinglereg" : "svregfile";
				ret.otherModules +="module "+moduleName+" #(\n"
						+ "    parameter int unsigned DATA_WIDTH = 32,\n"
						+ (singleVariant?"":"    parameter int unsigned ELEMENTS = 4,\n")
						+ "    parameter int unsigned WB_PORTS = 2\n"
						+ ")(\n"
						+ "    input  logic                          clk_i,\n"
						+ "    input  logic                          rst_i,\n"
						+ (singleVariant?"":"    input  logic [$clog2(ELEMENTS)-1:0]   raddr_i,\n")
						+ "    output logic [DATA_WIDTH-1:0]         rdata_o,\n"
						+ "    input  logic                          re_i,\n"
						+ (singleVariant?"":"    input  logic [$clog2(ELEMENTS)-1:0]   waddr_i [WB_PORTS],\n")
						+ "    input  logic [DATA_WIDTH-1:0]         wdata_i [WB_PORTS],\n"
						+ "    input  logic                          we_i [WB_PORTS]\n"
						+ "    );\n"
						+ (singleVariant?"    localparam ELEMENTS = 1;\n":"")
						+ "    reg "+(singleVariant?"":"[ELEMENTS-1:0]")+"[DATA_WIDTH-1:0] regfile;\n"
						+ "    always_ff @(posedge clk_i, posedge rst_i) begin : write_svreg\n"
						+ "        if(rst_i) begin\n"
						+ "            for (int unsigned j = 0; j < ELEMENTS; j++) begin\n"
						+ "                regfile"+(singleVariant?"":"[j]")+" <= 0;\n"
						+ "            end\n"
						+ "        end else begin\n"
						+ "            for (int unsigned j = 0; j < WB_PORTS; j++) begin\n"
						+ "                if(we_i[j] == 1) begin\n"
						+ "                    regfile"+(singleVariant?"":"[waddr_i[j]]")+" <= wdata_i[j];\n"
						+ "                end\n"
						+ "            end\n"
						+ "        end\n"
						+ "        \n"
						+ "    end\n"
						+ "    \n"
						+ "    assign rdata_o = re_i ? regfile"+(singleVariant?"":"[raddr_i]")+" : '0;\n"
						+ "endmodule\n";
				ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.MARKER_CUSTOM_REG, nodeKey.getNode(), nodeKey.getStage(), ""), moduleName, ExpressionType.WireName));
				return ret;
			}));
			return true;
		}
		return false;
	}
	
    @Override
    public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
        Iterator<NodeInstanceDesc.Key> nodeKeyIter = nodeKeys.iterator();
        while (nodeKeyIter.hasNext()) {
            var nodeKey = nodeKeyIter.next();
            if (implementRegfileModule(out, nodeKey) || implementSingle(out, nodeKey, isLast)) {
                nodeKeyIter.remove();
            }
        }
    }
}
