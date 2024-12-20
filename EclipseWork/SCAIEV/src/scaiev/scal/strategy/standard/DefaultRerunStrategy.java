package scaiev.scal.strategy.standard;

import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeLogicBlock;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.util.Verilog;

/**
 * Default implementation for WrRerunNext, which waits for the next instruction to arrive in order to get its PC.
 * Requires that, for a given stage, all instructions will run through that stage (which is generally NOT the case for multi-issue cores).
 *   Does some rough checks to determine whether there are paths for instructions going around a stage / that end before that stage.
 * It the core already has a 'next PC' signal, it is recommended to override the strategy to provide that signal instead.
 */
public class DefaultRerunStrategy extends SingleNodeStrategy {

	// logging
	protected static final Logger logger = LogManager.getLogger();
	
	Verilog language;
	BNode bNodes;
	Core core;
	
	protected MultiNodeStrategy pipelinedMemSizeStrategy;
	protected MultiNodeStrategy regularPipelinedMemAddrStrategy;
	protected MultiNodeStrategy spawnPipelinedMemAddrStrategy;
	
	/**
	 * @param language The (Verilog) language object
	 * @param bNodes The BNode object for the node instantiation
	 * @param core The core node description
	 */
	public DefaultRerunStrategy(Verilog language, BNode bNodes, Core core) {
		this.language = language;
		this.bNodes = bNodes;
		this.core = core;
	}
	
	/**
	 * Determines whether the default WrRerunNext implementation works for a given stage.
	 */
	protected boolean useDefaultRerunNextImplementation(PipelineStage stage) {
		if (stage.getKind() != StageKind.Core)
			return false; //Note: Also excluding CoreInternal stages for now.
		assert(core.GetNodes().get(bNodes.RdPC) != null);
		if (!core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.RdPC).GetEarliest()).isAroundOrBefore(stage, false)
				|| !core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.RdPC).GetLatest()).isAroundOrAfter(stage, false)) {
			//Need to read the PC of the next instruction.
			return false;
		}
		if (core.GetRootStage().getChildrenTails().anyMatch(tailStage -> tailStage.getStagePos() < stage.getStagePos())
				|| core.GetRootStage().getChildrenByStagePos(stage.getStagePos()).filter(refStage -> refStage != stage)
					.anyMatch(refStage -> refStage.streamNext_bfs(succ -> succ != stage).noneMatch(succ -> succ == stage)
							|| refStage.streamNext_bfs(succ -> succ != stage).anyMatch(succ -> succ.getNext().size() > 1 && succ.getNext().contains(stage)))) {
			//If there is any path for an instruction around the stage, we can't reliably wait for the next instruction.
			return false;
		}
		return true;
	}

	@Override
	public Optional<NodeLogicBuilder> implement(NodeInstanceDesc.Key nodeKey) {
		if (nodeKey.getPurpose().matches(Purpose.MARKER_INTERNALIMPL_PIN) && nodeKey.getNode().equals(bNodes.WrRerunNext)) {
			assert(nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0);
			if (!useDefaultRerunNextImplementation(nodeKey.getStage())) {
				return Optional.of(NodeLogicBuilder.fromFunction("DefaultRerunStrategy_RequestCoreSpecific_" + nodeKey.getStage().getName(), registry -> {
					var ret = new NodeLogicBlock();
					//Request the MARKER_TOCORE_PIN node, which should then create the interface pin towards the core.
					registry.lookupExpressionRequired(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.MARKER_TOCORE_PIN));
					ret.outputs.add(new NodeInstanceDesc(nodeKey, "", ExpressionType.AnyExpression));
					return ret;
				}));
			}
			
			//TODO: Lookbehind/forwarding optimization: If nodeKey.getStage() has just one predecessor and is continuous to it, flush to its PC if it's valid
			// (could, in principle, extend that to several stages)
			
			return Optional.of(NodeLogicBuilder.fromFunction("DefaultRerunStrategy_" + nodeKey.getStage().getName(), (registry, aux) -> {
				var ret = new NodeLogicBlock();
				String tab = language.tab;
				//Request the MARKER_TOCORE_PIN node, which should then create the interface pin towards the core.
				String rerunNextCond = registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.WrRerunNext, nodeKey.getStage(), "")).getExpressionWithParens();
				String rdPCExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdPC, nodeKey.getStage(), ""));
				String rdFlushExpr = registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, nodeKey.getStage(), "")).getExpressionWithParens();
				Optional<String> wrFlushExpr_opt = registry.lookupOptional(new NodeInstanceDesc.Key(bNodes.WrFlush, nodeKey.getStage(), "")).map(desc -> desc.getExpressionWithParens());
				String rdStallExpr = registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdStall, nodeKey.getStage(), "")).getExpressionWithParens();
				Optional<String> wrStallExpr_opt = registry.lookupOptional(new NodeInstanceDesc.Key(bNodes.WrStall, nodeKey.getStage(), "")).map(desc -> desc.getExpressionWithParens());
				String rdInStageValidExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdInStageValid, nodeKey.getStage(), ""));

				String rerunRegName = String.format("WrRerunNext_%s_reg", nodeKey.getStage().getName());
				String rerunWrPCValidWireName = String.format("WrPC_WrRerunNext_%s_s", nodeKey.getStage().getName());
				String rerunWrFlushWireName = String.format("WrFlush_WrRerunNext_%s_s", nodeKey.getStage().getName());
				ret.declarations += String.format("logic %s;\n", rerunRegName);
				ret.declarations += String.format("logic %s;\n", rerunWrPCValidWireName);
				ret.declarations += String.format("logic %s;\n", rerunWrFlushWireName);
				ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrPC, nodeKey.getStage(), "", aux), rdPCExpr, ExpressionType.AnyExpression));
				ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrPC_valid, nodeKey.getStage(), "", aux), rerunWrPCValidWireName, ExpressionType.WireName));
				ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrFlush, nodeKey.getStage(), "", aux), rerunWrFlushWireName, ExpressionType.WireName));
				registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.WrFlush, nodeKey.getStage(), "")); //Ensure WrFlush generation
				registry.lookupRequired(new NodeInstanceDesc.Key(Purpose.MARKER_TOCORE_PIN, bNodes.WrPC, nodeKey.getStage(), "")); //Ensure WrPC pin generation
				registry.lookupRequired(new NodeInstanceDesc.Key(Purpose.MARKER_TOCORE_PIN, bNodes.WrPC_valid, nodeKey.getStage(), "")); //Ensure WrPC_valid pin generation
				
				ret.logic += "always_comb begin\n";
				ret.logic += tab+String.format("%s = 0;\n", rerunWrPCValidWireName);
				ret.logic += tab+String.format("%s = 0;\n", rerunWrFlushWireName);
				//TODO: Fix combinational loop for CVA5 issue stage: !WrFlush -> reg_ready -> inStageValid -> WrFlush 
				ret.logic += tab+String.format("if (%s && %s) begin\n", rerunRegName, rdInStageValidExpr);
				//Note: WrPC_valid is set based on !RdFlush; however, some WrFlush conditions may also be relevant.
				// The problem is, one must ensure there is no WrPC_valid->WrFlush->WrPC_valid combinational loop.
				ret.logic += tab+tab+String.format("%s = !%s;\n", rerunWrPCValidWireName, rdFlushExpr);
				ret.logic += tab+tab+String.format("%s = 1;\n", rerunWrFlushWireName);
				ret.logic += tab+"end\n";
				ret.logic += "end\n";
				
				ret.logic += String.format("always_ff @(posedge %s) begin\n", language.clk);
				//Clear on reset
				ret.logic += tab+String.format("if (%s) %s <= 0;\n", language.reset, rerunRegName);
				ret.logic += tab+String.format("else if (%s) begin\n", rerunRegName);
				//Clear on flush
				ret.logic += tab+tab+String.format("if (%s%s)\n", rdFlushExpr, wrFlushExpr_opt.map(expr->" || "+expr).orElse(""));
				ret.logic += tab+tab+tab+String.format("%s <= 0;\n", rerunRegName);
				ret.logic += tab+"end\n";
				ret.logic += tab+String.format("else if (!%s%s) begin\n", rdStallExpr, wrStallExpr_opt.map(expr->" && !"+expr).orElse("")); //!rerunRegName
				//Set from WrRerunNext
				ret.logic += tab+tab+String.format("%s <= %s;\n", rerunRegName, rerunNextCond); //!rerunRegName
				ret.logic += tab+"end\n";
				ret.logic += "end\n";
				
				ret.outputs.add(new NodeInstanceDesc(nodeKey, "", ExpressionType.AnyExpression));
				return ret;
			}));
		}
		return Optional.empty();
	}

}
