package scaiev.scal.strategy.standard;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeLogicBlock;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.MultiportStallAttributes;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.SCALUtil;
import scaiev.scal.strategy.MultiNodeStrategy;
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
    if (stage.getKind() != StageKind.Core && stage.getKind() != StageKind.CoreMultiport)
      return false; // Note: Also excluding CoreInternal stages for now.
    assert (core.getNodes().get(bNodes.RdPC) != null);
    if (!core.translateStageScheduleNumber(core.getNodes().get(bNodes.RdPC).getEarliest()).isAroundOrBefore(stage, false) ||
        !core.translateStageScheduleNumber(core.getNodes().get(bNodes.RdPC).getLatest()).isAroundOrAfter(stage, false)) {
      // Need to read the PC of the next instruction.
      return false;
    }
    PipelineStage baseStage = stage.getMultiportBase();
    if (core.getRootStage().getChildrenTails().anyMatch(tailStage -> tailStage.getStagePos() < baseStage.getStagePos()) ||
        core.getRootStage()
            .getChildrenByStagePos(baseStage.getStagePos())
            .filter(refStage -> refStage != baseStage)
            .anyMatch(refStage
                      -> refStage.streamNext_bfs(succ -> succ != baseStage).noneMatch(succ -> succ == baseStage) ||
                             refStage.streamNext_bfs(succ -> succ != baseStage)
                                 .anyMatch(succ -> succ.getNext().size() > 1 && succ.getNext().contains(baseStage)))) {
      // If there is any path for an instruction around the stage, we can't reliably wait for the next instruction.
      return false;
    }
    return true;
  }

  protected boolean needsOrigPCNode(NodeRegistryRO registry, PipelineStage toStage) {
    // ASSUMPTION: Earliest WrPC also has RdPC.
    // ASSUMPTION: Earliest WrPC does not sit in a particular port.
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
      if (!nodeKey.getStage().getMultiportBase().getPrev().isEmpty()) {
        if (!nodeKey.getPurpose().matches(Purpose.PIPEDIN))
          return false;
        // Pipeline to this stage.
        var pipelineStrategy = origPCPipelineStrategyByPipetoStage.computeIfAbsent(
            nodeKey.getStage().getMultiportBase(),
            strategyMapKey
            -> strategyBuilders.buildNodeRegPipelineStrategy(language, bNodes, new PipelineFront(nodeKey.getStage().getMultiportBase()),
                                                             false, false, false,
                                                             _nodeKey -> true, _nodeKey -> false, MultiNodeStrategy.noneStrategy,
                                                             false));
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
      if (nodeKey.getStage().getTags().contains(StageTag.MultiportPipe)) {
        out.accept(NodeLogicBuilder.fromFunction("DefaultRerunStrategy_ReqMultiport_" + nodeKey.getStage().getName(), registry -> {
          var ret = new NodeLogicBlock();
          // Request MARKER_INTERNALIMPL_PIN in the multiport base stage.
          registry.lookupExpressionRequired(new NodeInstanceDesc.Key(Purpose.MARKER_INTERNALIMPL_PIN, bNodes.WrRerunNext,
                                                                     nodeKey.getStage().getMultiportBase(), ""));
          ret.outputs.add(new NodeInstanceDesc(nodeKey, "", ExpressionType.AnyExpression));
          return ret;
        }));
        return true;
      }

      // Possible lookbehind/forwarding optimization: If nodeKey.getStage() has just one predecessor and is continuous to it, flush to its PC
      // if it's valid
      //  (could, in principle, extend that to several stages)
      
      List<PipelineStage> portStages = nodeKey.getStage().getKind() == StageKind.CoreMultiport
                                         ? nodeKey.getStage().getChildren().stream().filter(st->st.getKind()==StageKind.Core).toList()
                                         : List.of(nodeKey.getStage());
      //When a new WrRerunNext occurs, we stall all following ports.
      //If the core is wired to produce no gaps from port 0 onwards, we only ever need to flush to port 0's PC.
      var stallAttr = nodeKey.getStage().getTagAttr(StageTag.MultiportStall, MultiportStallAttributes.class);
      boolean hasNoPortGaps = nodeKey.getStage().getKind() == StageKind.CoreMultiport
                                ? stallAttr.shiftUp()
                                : true; //value doesn't matter if we don't have more than 1 port
      if (portStages.size() > 1 && !hasNoPortGaps && stallAttr != null && !stallAttr.perPortFlush()) {
        logger.error("DefaultRerunStrategy: Cannot correctly implement WrRerunNext for a stage without shiftUp and without per-port flushing.");
      }
      var persistentMisc = new Object() {int auxCombStall = 0;};

      out.accept(NodeLogicBuilder.fromFunction("DefaultRerunStrategy_" + nodeKey.getStage().getName(), (registry, aux) -> {
        registry.newUniqueAux();
        var ret = new NodeLogicBlock();
        String tab = language.tab;
        // Implements the default (port-aware) WrRerunNext logic.

        List<String> rerunNextCond = portStages.stream()
            .map(portStage -> registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.WrRerunNext, portStage, "")).getExpressionWithParens())
            .toList();
        List<String> rdPCExpr = (hasNoPortGaps ? Stream.of(portStages.get(0)) : portStages.stream())
            .map(portStage -> registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdPC, portStage, "")).getExpressionWithParens())
            .toList();
        List<String> rdPCOrigExpr = rdPCExpr;
        if (needsOrigPCNode(registry, nodeKey.getStage())) {
          rdPCOrigExpr = new ArrayList<>(portStages.size());
          for (int iPort = 0; iPort < rdPCExpr.size(); ++iPort) {
            String rdOrigPC =
                registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdOrigPC, portStages.get(iPort), "")).getExpressionWithParens();
            String rdOrigPCValid =
                registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdOrigPC_valid, portStages.get(iPort), "")).getExpressionWithParens();
            rdPCOrigExpr.add(String.format("%s ? %s : %s", rdOrigPCValid, rdOrigPC, rdPCExpr.get(iPort)));
          }
        }
        for (int iPortCombStall = 1; iPortCombStall < portStages.size(); ++iPortCombStall) {
          if (persistentMisc.auxCombStall == 0)
            persistentMisc.auxCombStall = registry.newUniqueAux();
          PipelineStage portStage = portStages.get(iPortCombStall);

          // Stall all ports following a WrRerunNext.
          // This ensures we don't have any following instructions in other ports sneaking past alongside the WrRerunNext instruction.
          String accumStallCondWire = String.format("WrStall_WrRerunNext_%s_combReq", portStage.getName());
          ret.declarations += "logic %s;\n".formatted(accumStallCondWire);
          ret.logic += "assign %s = %s;\n".formatted(accumStallCondWire, rerunNextCond.stream().limit(iPortCombStall).reduce((a,b)->a+" || "+b).get());
          ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, portStage, "", persistentMisc.auxCombStall),
                                               accumStallCondWire, ExpressionType.WireName));
          registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.WrStall, portStage, "")); // Ensure WrStall generation
        }

        //Access (read) RdFlush/WrFlush
        List<String> rdFlushExpr = portStages.stream()
            .map(portStage -> registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, portStage, "")).getExpressionWithParens())
            .toList();
        Optional<String> wrFlushExpr_base = registry.lookupOptional(new NodeInstanceDesc.Key(bNodes.WrFlush, nodeKey.getStage(), ""))
            .map(desc -> desc.getExpressionWithParens());
        List<Optional<String>> wrFlushExpr_opt = new ArrayList<>(portStages.size());
        for (int iPort = 0; iPort < portStages.size(); ++iPort) {
          Optional<String> wrFlushExpr_opt_cur = registry.lookupOptional(new NodeInstanceDesc.Key(bNodes.WrFlush, portStages.get(iPort), ""))
                                                     .map(desc -> desc.getExpressionWithParens());
          if (wrFlushExpr_opt_cur.isEmpty()) {
            //Inherit flush from logically earlier port.
            final int iPort_ = iPort;
            wrFlushExpr_opt_cur = wrFlushExpr_opt_cur.or(() -> (iPort_ == 0) ? wrFlushExpr_base : wrFlushExpr_opt.get(iPort_ - 1)); 
          }
          wrFlushExpr_opt.add(wrFlushExpr_opt_cur);
        }

        List<String> nostallExpr = portStages.stream()
            .map(portStage -> SCALUtil.buildCond_StageNotStalling(bNodes, registry, portStage, false))
            .toList();
        List<String> rdInStageValidExpr = portStages.stream()
            .map(portStage -> registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdInStageValid, portStage, "")))
            .toList();

        String rerunRegName = String.format("WrRerunNext_%s_reg", nodeKey.getStage().getName());
        List<String> rerunWrPCRegName = List.of();
        List<String> rerunWrPCValidWireName = IntStream.range(0, rdPCExpr.size())
                                                .mapToObj(iPort -> String.format("WrPC_validReq_WrRerunNext_%s_s", portStages.get(iPort).getName()))
                                                .toList();
        List<String> rerunWrPCValidRegName = List.of();
        List<String> rerunWrFlushWireName = IntStream.range(0, rdPCExpr.size())
                                              .mapToObj(iPort -> String.format("WrFlush_WrRerunNext_%s_s", portStages.get(iPort).getName()))
                                              .toList();
        ret.declarations += String.format("logic %s;\n", rerunRegName);
        if (wrFlushPreventsFetch()) {
          rerunWrPCRegName = IntStream.range(0, rdPCExpr.size())
              .mapToObj(iPort -> String.format("WrPC_WrRerunNext_%s_r", portStages.get(iPort).getName()))
              .toList();
          rerunWrPCValidRegName = IntStream.range(0, rdPCExpr.size())
              .mapToObj(iPort -> String.format("WrPC_validReq_WrRerunNext_%s_r", portStages.get(iPort).getName()))
              .toList();
          for (int iPort = 0; iPort < rdPCExpr.size(); ++iPort) {
            ret.declarations += String.format("logic [%d-1:0] %s;\n", bNodes.WrPC.size, rerunWrPCRegName.get(iPort));
            ret.declarations += String.format("logic %s;\n", rerunWrPCValidRegName.get(iPort));
            ret.logic += String.format("always_ff @(posedge %s) begin\n", language.clk);
            ret.logic += tab + String.format("%s <= %s ? 1'b0 : %s;\n", rerunWrPCValidRegName.get(iPort), language.reset, rerunWrPCValidWireName.get(iPort));
            ret.logic += tab + String.format("%s <= %s;\n", rerunWrPCRegName.get(iPort), rdPCOrigExpr.get(iPort));
            ret.logic += "end\n";
          }
        }
        assert(SCALUtil.nodeIsPerPort(bNodes.WrPC, nodeKey.getStage()) || rdPCExpr.size() == 1);
        for (int iPort = 0; iPort < rdPCExpr.size(); ++iPort) {
          PipelineStage portStage = portStages.get(iPort);
          PipelineStage wrPCStage = (!SCALUtil.nodeIsPerPort(bNodes.WrPC, nodeKey.getStage()) && iPort == 0)
                                      ? portStage.getMultiportBase()
                                      : portStage;
          ret.declarations += String.format("logic %s;\n", rerunWrPCValidWireName.get(iPort));
          ret.declarations += String.format("logic %s;\n", rerunWrFlushWireName.get(iPort));
          ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrPC, wrPCStage, "", aux),
                                               wrFlushPreventsFetch() ? rerunWrPCRegName.get(iPort) : rdPCOrigExpr.get(iPort), ExpressionType.AnyExpression));
          ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrPC_valid, wrPCStage, "", aux),
                                               wrFlushPreventsFetch() ? rerunWrPCValidRegName.get(iPort) : rerunWrPCValidWireName.get(iPort),
                                               ExpressionType.WireName));
          PipelineStage flushStage = hasNoPortGaps ? portStage.getMultiportBase() : portStage;
          ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrFlush, flushStage, "", aux),
                                               rerunWrFlushWireName.get(iPort), ExpressionType.WireName));
          registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.WrFlush, flushStage, "")); // Ensure WrFlush generation
          registry.lookupRequired(
              new NodeInstanceDesc.Key(Purpose.MARKER_TOCORE_PIN, bNodes.WrPC, wrPCStage, "")); // Ensure WrPC pin generation
          registry.lookupRequired(new NodeInstanceDesc.Key(Purpose.MARKER_TOCORE_PIN, bNodes.WrPC_valid, wrPCStage,
                                                           "")); // Ensure WrPC_valid pin generation
        }
        for (int iPort = rdPCExpr.size(); iPort < portStages.size(); ++iPort) {
          PipelineStage portStage = portStages.get(iPort);
          //Stall all ports we can't flush directly, until they shift up to a port we can flush.
          ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, portStage, "", aux),
                                               rerunRegName, ExpressionType.AnyExpression_Noparen));
          registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.WrStall, portStage, "")); // Ensure WrStall generation
        }

        ret.logic += "always_comb begin\n";
        for (int iPort = 0; iPort < (hasNoPortGaps ? 1 : (portStages.size()-1)); ++iPort) {
          ret.logic += tab + String.format("%s = 0;\n", rerunWrPCValidWireName.get(iPort));
          ret.logic += tab + String.format("%s = 0;\n", rerunWrFlushWireName.get(iPort));
        }
        for (int iPort = 0; iPort < (hasNoPortGaps ? 1 : (portStages.size()-1)); ++iPort) {
          ret.logic += tab + String.format("%sif (%s && %s) begin\n", iPort==0?"":"else ", rerunRegName, rdInStageValidExpr.get(iPort));
          // Note: WrPC_valid is set based on !RdFlush; however, some WrFlush conditions may also be relevant.
          //  The problem is, one must ensure there is no WrPC_valid->WrFlush->WrPC_valid combinational loop.
          ret.logic += tab + tab + String.format("%s = !%s;\n", rerunWrPCValidWireName.get(iPort), rdFlushExpr.get(iPort));
          ret.logic += tab + tab + String.format("%s = 1;\n", rerunWrFlushWireName.get(iPort));
          ret.logic += tab + "end\n";
        }
        ret.logic += "end\n";

        ret.logic += String.format("always_ff @(posedge %s) begin\n", language.clk);
        // Clear on reset
        ret.logic += tab + String.format("if (%s) %s <= 0;\n", language.reset, rerunRegName);
        ret.logic += tab + String.format("else if (%s) begin\n", rerunRegName);
        for (int iPort = 0; iPort < portStages.size(); ++iPort) {
          // Clear on flush
          ret.logic += tab + tab + String.format("if (%s%s)\n", rdFlushExpr.get(iPort), wrFlushExpr_opt.get(iPort).map(expr -> " || " + expr).orElse(""));
          ret.logic += tab + tab + tab + String.format("%s <= 0;\n", rerunRegName);
        }
        ret.logic += tab + "end\n";
        for (int iPort = portStages.size()-1; iPort >= 0; --iPort) {
          // Check last non-stalling port first (i.e. newest instruction)
          ret.logic += tab + String.format("else if (%s)\n", nostallExpr.get(iPort)); //! rerunRegName
          // Set from WrRerunNext
          ret.logic += tab + tab + String.format("%s <= %s;\n", rerunRegName, rerunNextCond.get(iPort)); //! rerunRegName
        }
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
