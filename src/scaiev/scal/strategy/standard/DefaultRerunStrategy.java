package scaiev.scal.strategy.standard;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeLogicBlock;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.util.ListRemoveView;
import scaiev.util.Verilog;

/**
 * Default implementation for WrRerunNext, which waits for the next instruction to arrive in order to get its PC.
 * Requires that, for a given stage, all instructions will run through that stage (which is generally NOT the case for multi-issue cores).
 *   Does some rough checks to determine whether there are paths for instructions going around a stage / that end before that stage.
 * It the core already has a 'next PC' signal, it is recommended to override the strategy to provide that signal instead.
 */
public class DefaultRerunStrategy extends MultiNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  StrategyBuilders strategyBuilders;
  Verilog language;
  BNode bNodes;
  Core core;

  protected MultiNodeStrategy pipelinedMemSizeStrategy;
  protected MultiNodeStrategy regularPipelinedMemAddrStrategy;
  protected MultiNodeStrategy spawnPipelinedMemAddrStrategy;

  /**
   * @param strategyBuilders The StrategyBuilders object to build sub-strategies with
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core node description
   */
  public DefaultRerunStrategy(StrategyBuilders strategyBuilders, Verilog language, BNode bNodes, Core core) {
    this.strategyBuilders = strategyBuilders;
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
  }

  /**
   * Determines whether the default WrRerunNext implementation works for a given stage.
   */
  protected boolean useDefaultRerunNextImplementation(PipelineStage stage) {
    if (stage.getKind() != StageKind.Core)
      return false; // Note: Also excluding CoreInternal stages for now.
    assert (core.GetNodes().get(bNodes.RdPC) != null);
    if (!core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.RdPC).GetEarliest()).isAroundOrBefore(stage, false) ||
        !core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.RdPC).GetLatest()).isAroundOrAfter(stage, false)) {
      // Need to read the PC of the next instruction.
      return false;
    }
    if (core.GetRootStage().getChildrenTails().anyMatch(tailStage -> tailStage.getStagePos() < stage.getStagePos()) ||
        core.GetRootStage()
            .getChildrenByStagePos(stage.getStagePos())
            .filter(refStage -> refStage != stage)
            .anyMatch(refStage
                      -> refStage.streamNext_bfs(succ -> succ != stage).noneMatch(succ -> succ == stage) ||
                             refStage.streamNext_bfs(succ -> succ != stage)
                                 .anyMatch(succ -> succ.getNext().size() > 1 && succ.getNext().contains(stage)))) {
      // If there is any path for an instruction around the stage, we can't reliably wait for the next instruction.
      return false;
    }
    return true;
  }

  protected boolean needsOrigPCNode(NodeRegistryRO registry, PipelineStage toStage) {
    // ASSUMPTION: Earliest WrPC also has RdPC.
    return toStage.streamPrev_bfs()
        .filter(fromStage -> fromStage.getPrev().isEmpty())
        .anyMatch(fromStage -> registry.lookupOptional(new NodeInstanceDesc.Key(bNodes.WrPC_valid, fromStage, "")).isPresent());
  }
  
  /**
   * Returns true iff WrFlush causes undefined behavior if used in the same cycle as WrPC 
   */
  protected boolean wrFlushPreventsFetch() {
    return false;
  }

  // Per-stage pipeline strategies for RdOrigPC/RdOrigPC_valid
  HashMap<PipelineStage, MultiNodeStrategy> origPCPipelineStrategyByPipetoStage = new HashMap<>();
  HashSet<PipelineStage> origPCImplementedForSet = new HashSet<>();

  private boolean implementSingle(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
    if ((nodeKey.getNode().equals(bNodes.RdOrigPC) || nodeKey.getNode().equals(bNodes.RdOrigPC_valid)) && nodeKey.getISAX().isEmpty() &&
        nodeKey.getAux() == 0) {
      if (!nodeKey.getStage().getPrev().isEmpty()) {
        if (!nodeKey.getPurpose().matches(Purpose.PIPEDIN))
          return false;
        // Pipeline to this stage.
        var pipelineStrategy = origPCPipelineStrategyByPipetoStage.computeIfAbsent(
            nodeKey.getStage(),
            strategyMapKey
            -> strategyBuilders.buildNodeRegPipelineStrategy(language, bNodes, new PipelineFront(nodeKey.getStage()), false, false, false,
                                                             _nodeKey -> true, _nodeKey -> false, MultiNodeStrategy.noneStrategy));
        pipelineStrategy.implement(out, new ListRemoveView<>(List.of(nodeKey)), false);
        return true;
      }
      if (!nodeKey.getPurpose().matches(Purpose.REGULAR))
        return false;
      if (!origPCImplementedForSet.add(nodeKey.getStage()))
        return true; // RdOrigPC, RdOrigPC_valid both handled by the same builder.
      // ASSUMPTION: Stage with no predecessor = fetch stage, where WrPC overrides the PC of the new instruction
      //                 (RdPC being defined to not have a combinational path from WrPC)
      out.accept(NodeLogicBuilder.fromFunction("DefaultRerunStrategy_RdOrigPC_" + nodeKey.getStage().getName(), registry -> {
        var ret = new NodeLogicBlock();

        // If a WrPC is present, set RdOrigPC = RdPC, RdOrigPC_validReq = WrPC_validReq

        String rdOrigPCWire = String.format("RdOrigPC_%s_s", nodeKey.getStage().getName());
        String rdOrigPCValidWire = String.format("RdOrigPC_validReq_%s_s", nodeKey.getStage().getName());

        ret.declarations += String.format("logic [%d-1:0] %s;\n", bNodes.RdOrigPC.size, rdOrigPCWire);
        ret.declarations += String.format("logic %s;\n", rdOrigPCValidWire);

        var wrPCValidNode_opt = registry.lookupOptional(new NodeInstanceDesc.Key(bNodes.WrPC_valid, nodeKey.getStage(), ""));
        if (wrPCValidNode_opt.isPresent()) {
          String rdPCVal = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdPC, nodeKey.getStage(), ""));
          ret.logic += String.format("assign %s = %s;\n", rdOrigPCWire, rdPCVal);
          ret.logic += String.format("assign %s = %s;\n", rdOrigPCValidWire, wrPCValidNode_opt.get().getExpression());
        } else {
          ret.logic += String.format("assign %s = '0;\n", rdOrigPCWire);
          ret.logic += String.format("assign %s = 1'b0;\n", rdOrigPCValidWire);
        }

        ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.RdOrigPC, nodeKey.getStage(), ""),
                                             rdOrigPCWire, ExpressionType.WireName));
        ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.RdOrigPC_valid, nodeKey.getStage(), ""),
                                             rdOrigPCValidWire, ExpressionType.WireName));
        return ret;
      }));
      return true;
    }

    if (nodeKey.getPurpose().matches(Purpose.MARKER_INTERNALIMPL_PIN) && nodeKey.getNode().equals(bNodes.WrRerunNext)) {
      assert (nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0);
      if (!useDefaultRerunNextImplementation(nodeKey.getStage())) {
        out.accept(NodeLogicBuilder.fromFunction("DefaultRerunStrategy_RequestCoreSpecific_" + nodeKey.getStage().getName(), registry -> {
          var ret = new NodeLogicBlock();
          // Request the MARKER_TOCORE_PIN node, which should then create the interface pin towards the core.
          registry.lookupExpressionRequired(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.MARKER_TOCORE_PIN));
          ret.outputs.add(new NodeInstanceDesc(nodeKey, "", ExpressionType.AnyExpression));
          return ret;
        }));
        return true;
      }

      // TODO: Lookbehind/forwarding optimization: If nodeKey.getStage() has just one predecessor and is continuous to it, flush to its PC
      // if it's valid
      //  (could, in principle, extend that to several stages)

      out.accept(NodeLogicBuilder.fromFunction("DefaultRerunStrategy_" + nodeKey.getStage().getName(), (registry, aux) -> {
        var ret = new NodeLogicBlock();
        String tab = language.tab;
        // Request the MARKER_TOCORE_PIN node, which should then create the interface pin towards the core.
        String rerunNextCond =
            registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.WrRerunNext, nodeKey.getStage(), "")).getExpressionWithParens();
        String rdPCExpr = registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdPC, nodeKey.getStage(), "")).getExpressionWithParens();
        String rdPCOrigExpr = rdPCExpr;
        if (needsOrigPCNode(registry, nodeKey.getStage())) {
          String rdOrigPC =
              registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdOrigPC, nodeKey.getStage(), "")).getExpressionWithParens();
          String rdOrigPCValid =
              registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdOrigPC_valid, nodeKey.getStage(), "")).getExpressionWithParens();
          rdPCOrigExpr = String.format("%s ? %s : %s", rdOrigPCValid, rdOrigPC, rdPCExpr);
        }
        String rdFlushExpr =
            registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, nodeKey.getStage(), "")).getExpressionWithParens();
        Optional<String> wrFlushExpr_opt = registry.lookupOptional(new NodeInstanceDesc.Key(bNodes.WrFlush, nodeKey.getStage(), ""))
                                               .map(desc -> desc.getExpressionWithParens());
        String rdStallExpr =
            registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdStall, nodeKey.getStage(), "")).getExpressionWithParens();
        Optional<String> wrStallExpr_opt = registry.lookupOptional(new NodeInstanceDesc.Key(bNodes.WrStall, nodeKey.getStage(), ""))
                                               .map(desc -> desc.getExpressionWithParens());
        String rdInStageValidExpr =
            registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdInStageValid, nodeKey.getStage(), ""));

        String rerunRegName = String.format("WrRerunNext_%s_reg", nodeKey.getStage().getName());
        String rerunWrPCRegName = String.format("WrPC_WrRerunNext_%s_r", nodeKey.getStage().getName());
        String rerunWrPCValidWireName = String.format("WrPC_validReq_WrRerunNext_%s_s", nodeKey.getStage().getName());
        String rerunWrPCValidRegName = String.format("WrPC_validReq_WrRerunNext_%s_r", nodeKey.getStage().getName());
        String rerunWrFlushWireName = String.format("WrFlush_WrRerunNext_%s_s", nodeKey.getStage().getName());
        ret.declarations += String.format("logic %s;\n", rerunRegName);
        if (wrFlushPreventsFetch()) {
          ret.declarations += String.format("logic [%d-1:0] %s;\n", bNodes.WrPC.size, rerunWrPCRegName);
          ret.declarations += String.format("logic %s;\n", rerunWrPCValidRegName);
          ret.logic += String.format("always_ff @(posedge %s) begin\n", language.clk);
          ret.logic += tab + String.format("%s <= %s ? 1'b0 : %s;\n", rerunWrPCValidRegName, language.reset, rerunWrPCValidWireName);
          ret.logic += tab + String.format("%s <= %s;\n", rerunWrPCRegName, rdPCOrigExpr);
          ret.logic += "end\n";
        }
        ret.declarations += String.format("logic %s;\n", rerunWrPCValidWireName);
        ret.declarations += String.format("logic %s;\n", rerunWrFlushWireName);
        ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrPC, nodeKey.getStage(), "", aux),
                                             wrFlushPreventsFetch() ? rerunWrPCRegName : rdPCOrigExpr, ExpressionType.AnyExpression));
        ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrPC_valid, nodeKey.getStage(), "", aux),
                                             wrFlushPreventsFetch() ? rerunWrPCValidRegName : rerunWrPCValidWireName,
                                             ExpressionType.WireName));
        ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrFlush, nodeKey.getStage(), "", aux),
                                             rerunWrFlushWireName, ExpressionType.WireName));
        registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.WrFlush, nodeKey.getStage(), "")); // Ensure WrFlush generation
        registry.lookupRequired(
            new NodeInstanceDesc.Key(Purpose.MARKER_TOCORE_PIN, bNodes.WrPC, nodeKey.getStage(), "")); // Ensure WrPC pin generation
        registry.lookupRequired(new NodeInstanceDesc.Key(Purpose.MARKER_TOCORE_PIN, bNodes.WrPC_valid, nodeKey.getStage(),
                                                         "")); // Ensure WrPC_valid pin generation

        ret.logic += "always_comb begin\n";
        ret.logic += tab + String.format("%s = 0;\n", rerunWrPCValidWireName);
        ret.logic += tab + String.format("%s = 0;\n", rerunWrFlushWireName);
        ret.logic += tab + String.format("if (%s && %s) begin\n", rerunRegName, rdInStageValidExpr);
        // Note: WrPC_valid is set based on !RdFlush; however, some WrFlush conditions may also be relevant.
        //  The problem is, one must ensure there is no WrPC_valid->WrFlush->WrPC_valid combinational loop.
        ret.logic += tab + tab + String.format("%s = !%s;\n", rerunWrPCValidWireName, rdFlushExpr);
        ret.logic += tab + tab + String.format("%s = 1;\n", rerunWrFlushWireName);
        ret.logic += tab + "end\n";
        ret.logic += "end\n";

        ret.logic += String.format("always_ff @(posedge %s) begin\n", language.clk);
        // Clear on reset
        ret.logic += tab + String.format("if (%s) %s <= 0;\n", language.reset, rerunRegName);
        ret.logic += tab + String.format("else if (%s) begin\n", rerunRegName);
        // Clear on flush
        ret.logic += tab + tab + String.format("if (%s%s)\n", rdFlushExpr, wrFlushExpr_opt.map(expr -> " || " + expr).orElse(""));
        ret.logic += tab + tab + tab + String.format("%s <= 0;\n", rerunRegName);
        ret.logic += tab + "end\n";
        ret.logic += tab + String.format("else if (!%s%s) begin\n", rdStallExpr,
                                         wrStallExpr_opt.map(expr -> " && !" + expr).orElse("")); //! rerunRegName
        // Set from WrRerunNext
        ret.logic += tab + tab + String.format("%s <= %s;\n", rerunRegName, rerunNextCond); //! rerunRegName
        ret.logic += tab + "end\n";
        ret.logic += "end\n";

        ret.outputs.add(new NodeInstanceDesc(nodeKey, "", ExpressionType.AnyExpression));
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
      if (implementSingle(out, nodeKey)) {
        nodeKeyIter.remove();
      }
    }
  }
}
