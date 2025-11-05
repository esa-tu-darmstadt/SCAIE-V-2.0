package scaiev.scal.strategy.decoupled;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.FNode;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistry;
import scaiev.scal.SCALUtil;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.util.Verilog;

/**
 * Strategy that pipelines RdIValid within a sub-pipeline (ignoring dynamic decoupled instructions).
 * Also produces and handles additional nodes related with sub-pipelines:
 * - {@link FNode#RdAnyValid} (per ISAX),
 * - {@link SpawnRdIValidStrategy#ISAXValidCounter} (per ISAX)
 * - {@link SpawnRdIValidStrategy#WrStallISAXEntry} (per ISAX)
 */
public class SpawnRdIValidStrategy extends MultiNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  /**
   * Required node for all requesters of {@link SpawnRdIValidStrategy#ISAXValidCounter} nodes,
   *  to be provided with the ISAX name and a unique aux value.
   * The value must be a string representation of the minimum required 'counter maximum' value, such that width=clog2(max).
   * Key({@link SpawnRdIValidStrategy#ISAXValidCounterWidth},stage,ISAX) will return the actual counter width.
   * The counter implementation will automatically stall (via {@link SpawnRdIValidStrategy#WrStallISAXEntry}) to prevent counter overflow.
   */
  public static final SCAIEVNode ISAXValidCounterMax = new SCAIEVNode("ISAXValidCounterMax");
  /**
   * Returns the counter width corresponding to {@link SpawnRdIValidStrategy#ISAXValidCounter} for a given ISAX.
   * If a user wants to provide a minimum width, they should request it via {@link SpawnRdIValidStrategy#ISAXValidCounterMax}.
   */
  public static final SCAIEVNode ISAXValidCounterWidth = new SCAIEVNode("ISAXValidCounterWidth");
  /**
   * Node that provides a counter for the number of instructions the given spawn ISAX has in the given stage's sub-pipelines.
   * All per-'spawn ISAX' FIFOs can use this to implement back-pressure in conjunction with {@link SpawnRdIValidStrategy#WrStallISAXEntry};
   *  if the counter value plus the current FIFO level is equal to the FIFO depth, WrStallISAXEntry should be set to 1.
   * Each user should output a {@link SpawnRdIValidStrategy#ISAXValidCounterWidth} node instance.
   * The counter can only track one ISAX at a time, i.e. the ISAX name must be set in the requested key.
   * Note: The width and max value of a particular counter NodeInstanceDesc can be retrieved via getKey().getNode().{size,elements}
   * respectively.
   */
  public static final SCAIEVNode ISAXValidCounter = new SCAIEVNode("ISAXValidCounter");
  /**
   * Backpressure node; but ISAX-sensitive stall, specifically stalling before increment of {@link SpawnRdIValidStrategy#ISAXValidCounter}.
   * Unlike WrStall, no base node is generated.
   * Instead, all WrStallISAXEntry nodes for any given ISAX are directly used in construction of sub-pipeline WrStall.
   * The ISAX field must be set to the name of a spawn ISAX with sub-pipelines below the given stage. Aux must be set to a unique value.
   */
  public static final SCAIEVNode WrStallISAXEntry = new SCAIEVNode("WrStallISAXEntry");
  /**
   * Companion node to {@link SpawnRdIValidStrategy#WrStallISAXEntry} that signals (at least) one cycle earlier.
   * Used for ISAXes that do not
   */
  public static final SCAIEVNode WrStallISAXEntryEarly = new SCAIEVNode("WrStallISAXEntryEarly");

  StrategyBuilders strategyBuilders;
  Verilog language;
  BNode bNodes;
  Core core;
  HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage;
  HashMap<String, SCAIEVInstr> allISAXes;
  /**
   * @param strategyBuilders The StrategyBuilders object to build sub-strategies with
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param spawn_instr_stage The Node-ISAX-Stage mapping providing the precise sub-pipeline stage for spawn operations
   * @param allISAXes The ISAX descriptions
   */
  public SpawnRdIValidStrategy(StrategyBuilders strategyBuilders, Verilog language, BNode bNodes, Core core,
                               HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                               HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage,
                               HashMap<String, SCAIEVInstr> allISAXes) {
    this.strategyBuilders = strategyBuilders;
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.spawn_instr_stage = spawn_instr_stage;
    this.allISAXes = allISAXes;
  }

  private List<PipelineStage> getEndStagesFor(PipelineStage stage, String isaxName) {
    return spawn_instr_stage.entrySet()
        .stream()
        .flatMap(spawn_instr_stage_entry -> Stream.ofNullable(spawn_instr_stage_entry.getValue().get(isaxName)))
        .filter(prevStage -> prevStage.getKind() == StageKind.Sub && prevStage.getParent().equals(Optional.of(stage)))
        .distinct()
        .toList();
  }
  private List<PipelineStage> getEntryStagesFor(PipelineStage stage, String isaxName) {
    return new PipelineFront(getEndStagesFor(stage, isaxName)).streamPrev_bfs().filter(prevStage -> prevStage.getPrev().isEmpty()).toList();
  }

  // private HashMap<NodeInstanceDesc.Key, CounterDef> implementedCounters = new HashMap<>();
  private HashSet<NodeInstanceDesc.Key> implementedCounters = new HashSet<>();
  private boolean implementCounter(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
    if (!nodeKey.getPurpose().matches(Purpose.REGULAR) || nodeKey.getISAX().isEmpty() || nodeKey.getAux() != 0)
      return false;
    if (nodeKey.getNode().equals(ISAXValidCounter) || nodeKey.getNode().equals(ISAXValidCounterMax) ||
        nodeKey.getNode().equals(ISAXValidCounterWidth)) {
      List<PipelineStage> endStages = getEndStagesFor(nodeKey.getStage(), nodeKey.getISAX());
      List<PipelineStage> entryStages = getEntryStagesFor(nodeKey.getStage(), nodeKey.getISAX());
      if (entryStages.isEmpty()) {
        logger.warn("SpawnRdIValidStrategy: Found no sub-pipelines for {}", nodeKey.toString());
        return false;
      }
      assert (!endStages.isEmpty());
      if (implementedCounters.add(new NodeInstanceDesc.Key(ISAXValidCounter, nodeKey.getStage(), nodeKey.getISAX()))) {
        var counterMaxOutputKey = new NodeInstanceDesc.Key(ISAXValidCounterMax, nodeKey.getStage(), nodeKey.getISAX());
        var counterWidthOutputKey = new NodeInstanceDesc.Key(ISAXValidCounterWidth, nodeKey.getStage(), nodeKey.getISAX());
        // Builder that determines the counter width and range (allowing non-power of two maximum count values).
        out.accept(NodeLogicBuilder.fromFunction("SpawnRdIValidStrategy_" + counterWidthOutputKey.toString(false), registry -> {
          var ret = new NodeLogicBlock();
          int counterMax = 0;
          for (NodeInstanceDesc counterMaxNode : registry.lookupAll(counterMaxOutputKey, false)) {
            if (counterMaxNode.getKey().getISAX().equals(nodeKey.getISAX())) {
              // The sub-node should have a unique identifier.
              assert (counterMaxNode.getKey().getAux() != 0);
              try {
                int val = Integer.parseUnsignedInt(counterMaxNode.getExpression());
                if (counterMax < val)
                  counterMax = val;
              } catch (NumberFormatException e) {
                logger.error("SpawnRdIValidStrategy: Invalid ISAXValidCounterMax value: {}", counterMaxNode.getExpression());
              }
            }
          }
          int counterWidth = 0;
          // counterWidth = clog2(counterMax+1) - need to be able to represent [0 .. counterMax], i.e. counterMax+1 values
          for (int counterMax_ = counterMax; counterMax_ > 0; counterWidth++, counterMax_ >>= 1)
            ;
          ret.outputs.add(new NodeInstanceDesc(counterMaxOutputKey, Integer.toString(counterMax), ExpressionType.AnyExpression));
          if (counterWidth == 0)
            counterWidth = 1;
          ret.outputs.add(new NodeInstanceDesc(counterWidthOutputKey, Integer.toString(counterWidth), ExpressionType.AnyExpression));
          return ret;
        }));
        SCAIEVNode counterOutputSVNode = new SCAIEVNode(ISAXValidCounter.name);
        var counterOutputKey = new NodeInstanceDesc.Key(counterOutputSVNode, nodeKey.getStage(), nodeKey.getISAX());
        String counterRegName = counterOutputKey.toString(false) + "_reg";
        RequestedForSet requestedFor = new RequestedForSet(nodeKey.getISAX());
        out.accept(NodeLogicBuilder.fromFunction("SpawnRdIValidStrategy_" + counterOutputKey.toString(false), (registry, aux) -> {
          final String tab = language.tab;
          var ret = new NodeLogicBlock();
          String counterWidthStr = registry.lookupExpressionRequired(counterWidthOutputKey);
          String counterMaxStr = registry.lookupExpressionRequired(counterMaxOutputKey);
          try {
            counterOutputSVNode.size = Integer.parseInt(counterWidthStr);
          } catch (NumberFormatException e) { // Can happen if the width builder hasn't been executed yet.
            counterOutputSVNode.size = 0;
          }
          try {
            counterOutputSVNode.elements = Integer.parseInt(counterMaxStr);
          } catch (NumberFormatException e) { // Can happen if the width builder hasn't been executed yet.
            counterOutputSVNode.elements = 0;
          }
          int counterMax = counterOutputSVNode.elements;
          String stallCond, stallCondEarly;
          if (counterMaxStr.equals("0")) {
            ret.declarations += String.format("wire %s;\n", counterRegName);
            ret.logic += String.format("assign %s = 0;\n", counterRegName);
            stallCond = "1'b1";
            stallCondEarly = "1'b1";
          } else {
            ret.declarations += String.format("reg [%s-1:0] %s;\n", counterWidthStr, counterRegName);

            String issueCond = String.format(
                "%s && %s",
                registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, nodeKey.getStage(), nodeKey.getISAX())),
                registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrDeqInstr, nodeKey.getStage(), "")));
            String commitCond = registry.lookupExpressionRequired(
                new NodeInstanceDesc.Key(bNodes.WrCommit_spawn_validReq, nodeKey.getStage(), nodeKey.getISAX()));

            // Assuming only the entire ISAX can be flushed, and the flush is visible on the end stages of all sub-pipelines.
            List<String> flushConds =
                endStages.stream()
                    .map(endStage -> registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, endStage, "")))
                    .toList();
            String flushCond = flushConds.get(0);
            if (!flushCond.startsWith(NodeRegistry.MISSING_PREFIX) &&
                flushConds.stream().skip(1).anyMatch(
                    otherFlushCond -> !otherFlushCond.startsWith(NodeRegistry.MISSING_PREFIX) && !flushConds.get(0).equals(otherFlushCond))) {
              logger.warn("RdFlush conditions don't match up between end stages for ISAX {}: {}", nodeKey.getISAX(), flushConds);
            }
            List<Optional<NodeInstanceDesc>> wrflushOptConds =
                endStages.stream()
                    .map(endStage -> registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrFlush, endStage, "")))
                    .toList();
            //						if (wrflushOptConds.stream().skip(1).anyMatch(otherFlushNodeOpt ->
            //								!wrflushOptConds.get(0).map(condNode->condNode.getExpression())
            //									.equals(otherFlushNodeOpt.map(otherFlushNode->otherFlushNode.getExpression()))))
            //{ 							logger.warn("WrFlush conditions don't match up between end stages
            // for ISAX {}: {}", nodeKey.getISAX(), wrflushOptConds);
            //						}
            var wrflushCond_opt = wrflushOptConds.get(0);
            if (wrflushCond_opt.isPresent())
              flushCond += " || " + wrflushCond_opt.get().getExpression();

            stallCond = String.format("%s == %s'd%s", counterRegName, counterWidthStr, counterMaxStr);
            if (counterMax >= 2) {
              stallCondEarly = String.format("%s >= %s'd%d", counterRegName, counterWidthStr, counterMax - 1);
            } else if (counterMax == 1) {
              stallCondEarly = String.format("%s || (%s)", stallCond, issueCond);
            } else {
              stallCondEarly = issueCond;
            }

            String counterLogic = "";
            counterLogic += String.format("if (%s) begin\n", language.reset);
            counterLogic += tab + String.format("%s <= 0;\n", counterRegName, counterWidthStr);
            counterLogic += "end\n";
            counterLogic += "else begin\n";
            counterLogic += tab + "`ifndef SYNTHESIS\n";
            counterLogic += tab + String.format("if (%s && (%s) && !(%s)) begin\n", stallCond, issueCond, commitCond);
            counterLogic += tab + tab + String.format("$display(\"ERROR: %s ISAX counter overflow\");\n", nodeKey.getISAX());
            counterLogic += tab + tab + "$stop;\n";
            counterLogic += tab + "end\n";
            counterLogic += tab + String.format("if (%s == 0 && !(%s) && (%s)) begin\n", counterRegName, issueCond, commitCond);
            counterLogic += tab + tab + String.format("$display(\"ERROR: %s ISAX counter underflow\");\n", nodeKey.getISAX());
            counterLogic += tab + tab + "$stop;\n";
            counterLogic += tab + "end\n";
            counterLogic += tab + "`endif\n";
            counterLogic += tab + String.format("%s <= (!(%s) ? (%s - (%s ? 1 : 0)) : 0) + (%s ? 1 : 0);\n", counterRegName, flushCond,
                                                counterRegName, commitCond, issueCond);
            counterLogic += "end\n";
            ret.logic += language.CreateInAlways(true, counterLogic);
          }
          ret.outputs.add(new NodeInstanceDesc(counterOutputKey, counterRegName, ExpressionType.WireName, requestedFor));

          ret.outputs.add(
              new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, WrStallISAXEntry, nodeKey.getStage(), nodeKey.getISAX(), aux),
                                   stallCond, ExpressionType.AnyExpression, requestedFor));
          ret.outputs.add(new NodeInstanceDesc(
              new NodeInstanceDesc.Key(Purpose.REGULAR, WrStallISAXEntryEarly, nodeKey.getStage(), nodeKey.getISAX(), aux), stallCondEarly,
              ExpressionType.AnyExpression, requestedFor));
          registry.lookupExpressionRequired(new NodeInstanceDesc.Key(WrStallISAXEntry, nodeKey.getStage(), nodeKey.getISAX()));

          return ret;
        }));
      }
      return true;
    }
    return false;
  }

  private HashSet<NodeInstanceDesc.Key> implementedRdAnyValidFor = new HashSet<>();
  private boolean implementRdAnyValid(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
    if (nodeKey.getNode().equals(bNodes.RdAnyValid)) {
      if (!nodeKey.getPurpose().matches(Purpose.REGULAR) || nodeKey.getAux() != 0)
        return false;
    } else
      return false;
    if (nodeKey.getISAX().isEmpty()) {
      assert (false); // Shouldn't happen.
      return false;
    }
    SCAIEVInstr instrDesc = allISAXes.get(nodeKey.getISAX());
    assert (instrDesc != null);
    if (instrDesc == null)
      return false;
    if (nodeKey.getStage().getKind() != StageKind.Core && nodeKey.getStage().getKind() != StageKind.Decoupled)
      return false;
    if (!implementedRdAnyValidFor.add(new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX())))
      return true; // Already implemented
    PipelineStage parentStage = nodeKey.getStage();
    PipelineFront parentFront = new PipelineFront(parentStage);
    // The list of stages that mark the end of the instruction.
    List<PipelineStage> endStages =
        spawn_instr_stage.entrySet()
            .stream()
            .flatMap(spawn_instr_stage_entry -> Stream.ofNullable(spawn_instr_stage_entry.getValue().get(nodeKey.getISAX())))
            //.flatMap(spawn_instr_stage_entry -> spawn_instr_stage_entry.getValue().entrySet().stream())
            //.map(instr_stage_entry -> instr_stage_entry.getValue())
            .distinct()
            .filter(endStage -> parentFront.isAround(endStage))
            .toList();
    List<PipelineStage> endStageOffenders = endStages.stream().filter(endStage -> endStage.getPrev().size() > 1).toList();
    if (endStageOffenders.size() > 0) {
      logger.warn("SpawnRdIValidStrategy_{}: Encountered sub-pipeline split before [{}], counter may end up invalid", nodeKey.toString(),
                  endStageOffenders.stream().map(endStage -> endStage.getName()).reduce((a, b) -> a + "," + b).orElse(""));
    }
    if (!instrDesc.GetRunsAsDynamic()) {
      endStageOffenders = endStages.stream().filter(endStage -> endStage.getPrev().isEmpty()).toList();
      if (endStageOffenders.size() > 0) {
        logger.warn("SpawnRdIValidStrategy_{}: Encountered single-stage sub-pipelines [{}] of statically-scheduled ISAX, which indicates "
                        + "immediate completion",
                    nodeKey.toString(),
                    endStageOffenders.stream().map(endStage -> endStage.getName()).reduce((a, b) -> a + "," + b).orElse(""));
      }
    }

    assert (endStages.stream().allMatch(endStage -> endStage.getKind() == StageKind.Sub));
    // The list of stages that the sub pipeline starts with.
    List<PipelineStage> entryStages = endStages.stream()
                                          .flatMap(endStage -> endStage.streamPrev_bfs())
                                          .filter(intermediateStage -> intermediateStage.getPrev().isEmpty())
                                          .toList();
    assert (entryStages.stream().allMatch(entryStage -> entryStage.getKind() == StageKind.Sub));
    assert (entryStages.stream().allMatch(entryStage -> parentFront.isAround(entryStage)));

    String signalName = nodeKey.toString(false) + "_internal_s";

    out.accept(NodeLogicBuilder.fromFunction("SpawnRdIValidStrategy_" + nodeKey.toString(), registry -> {
      var ret = new NodeLogicBlock();
      String entryCond = String.format(
          "(%s && %s)", registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, parentStage, nodeKey.getISAX())),
          registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdInStageValid, parentStage, "")));
      String dynamicAnyValidCond = "";
      List<PipelineStage> endStagesToWatch = endStages;

      ret.declarations += String.format("wire %s;\n", signalName);
      if (instrDesc.GetRunsAsDynamic() && !endStages.isEmpty()) {
        // Need input from the ISAX to generate RdAnyValid, for lack of a direct view on the ISAX pipeline.
        Optional<PipelineStage> isaxAnyValidIntfStage_opt =
            op_stage_instr.getOrDefault(bNodes.RdAnyValid, new HashMap<>())
                .entrySet()
                .stream()
                .filter(stage_instrs
                        -> stage_instrs.getValue().contains(nodeKey.getISAX()) && parentFront.isAround(stage_instrs.getKey()) &&
                               stage_instrs.getKey().getNext().isEmpty())
                .map(stage_instrs -> stage_instrs.getKey())
                .findAny();
        if (isaxAnyValidIntfStage_opt.isEmpty()) {
          logger.error("SpawnRdIValidStrategy: Cannot find RdAnyValid of ISAX {} below spawn base stage {}", nodeKey.getISAX(),
                       parentStage.getName());
        } else {
          dynamicAnyValidCond = registry.lookupExpressionRequired(
              new NodeInstanceDesc.Key(Purpose.WIREDIN, bNodes.RdAnyValid, isaxAnyValidIntfStage_opt.get(), nodeKey.getISAX()));
        }
        endStagesToWatch = endStages.stream()
                               .flatMap(endStage -> endStage.getPrev().stream())
                               .filter(beforeEndStage -> !entryStages.contains(beforeEndStage))
                               .toList();
        //				ret.logic += String.format("assign %s = %s || %s;\n",
        //					signalName,
        //					registry.lookupExpressionRequired(new NodeInstanceDesc.Key(Purpose.WIREDIN,
        // bNodes.RdAnyValid, parentStage, nodeKey.getISAX())), 					entryCond);
      }

      String counterCond = "";
      if (!endStagesToWatch.isEmpty()) {
        // Note: Very similar to ISAXValidCounter.
        // However, with dynamic instructions, we cannot predict a good max counter value.
        //  Instead, RdAnyValid is requested from the ISAX for the dynamic part of the sub-pipeline.
        long maxCounterElements = endStagesToWatch.stream().flatMap(endStage -> endStage.streamPrev_bfs()).count();

        int counterWidth = 0;
        while ((maxCounterElements >> counterWidth) != 0)
          ++counterWidth;
        final int counterWidth_ = counterWidth;

        SCAIEVNode counterNode = new SCAIEVNode("RdAnyValidCounter", counterWidth, true);
        String counterRegName = nodeKey.toString(false) + "_counter_r";
        ret.declarations += String.format("reg [%d-1:0] %s;\n", counterWidth, counterRegName);

        String counterLogic = counterRegName;

        // Assuming only the entire ISAX can be flushed, and the flush is visible on the end stages of all sub-pipelines.
        String flushISAXCond = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, endStagesToWatch.get(0), ""));
        var wrflushISAXCond_opt = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrFlush, endStagesToWatch.get(0), ""));
        if (wrflushISAXCond_opt.isPresent())
          flushISAXCond += " || " + wrflushISAXCond_opt.get().getExpression();

        Stream<String> endStagesConds = endStagesToWatch.stream().map(
            endStage -> registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, endStage, nodeKey.getISAX())));
        counterLogic +=
            endStagesConds.map(endCond -> String.format(" - %d'(%s)", counterWidth_, endCond)).reduce((a, b) -> a + b).orElse("");
        // endStagesConds is invalid now.

        // The counter decreases by one for each reached end stage (which differ between RdMem_spawn,WrMem_spawn,WrRD_spawn,etc.)
        // Correspondingly, it needs to be increased by that amount on start.
        counterLogic += String.format(" + (%s ? %d'd%d : %d'd0)", entryCond, counterWidth, endStagesToWatch.size(), counterWidth);

        if (!flushISAXCond.equals("0") && !flushISAXCond.equals("1'b0")) {
          // For flushing, entryCond is irrelevant, as any instruction entering the sub-pipeline will also be flushed.
          counterLogic = String.format("(%s) ? %d'd0 : %s", flushISAXCond, counterWidth, counterLogic);
        }

        ret.logic += language.CreateTextRegReset(counterRegName, counterLogic, "");

        ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, counterNode, nodeKey.getStage(), nodeKey.getISAX()),
                                             counterRegName, ExpressionType.WireName));

        counterCond = String.format("%s != %d'd0", counterRegName, counterWidth);
      }

      ret.logic +=
          String.format("assign %s = %s%s%s;\n", signalName, entryCond, dynamicAnyValidCond.isEmpty() ? "" : (" || " + dynamicAnyValidCond),
                        counterCond.isEmpty() ? "" : (" || " + counterCond));

      ret.outputs.add(
          new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.RdAnyValid, nodeKey.getStage(), nodeKey.getISAX()),
                               signalName, ExpressionType.WireName));

      return ret;
    }));

    return true;
  }

  private boolean implementStallISAXEntry(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
    if (!nodeKey.getNode().equals(WrStallISAXEntry) || !nodeKey.getPurpose().matches(Purpose.REGULAR) || nodeKey.getISAX().isEmpty() ||
        nodeKey.getAux() != 0)
      return false;

    List<PipelineStage> entryStages = getEntryStagesFor(nodeKey.getStage(), nodeKey.getISAX());
    if (entryStages.isEmpty()) {
      logger.warn("SpawnRdIValidStrategy: Found no sub-pipelines for {}", nodeKey.toString());
      return false;
    }

    out.accept(NodeLogicBuilder.fromFunction("SpawnRdIValidStrategy_" + nodeKey.toString(false), (registry, aux) -> {
      NodeLogicBlock ret = new NodeLogicBlock();

      // RdIValid must be set in the base stage.
      String ivalidIn = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, nodeKey.getStage(), nodeKey.getISAX()));

      // Apply WrStallISAXEntry for the given ISAX.
      String extraStallCond = "";
      for (NodeInstanceDesc subNode : registry.lookupAll(new NodeInstanceDesc.Key(WrStallISAXEntry, nodeKey.getStage(), ""), false)) {
        if (subNode.getKey().getISAX().equals(nodeKey.getISAX())) {
          // The sub-node should have a unique identifier.
          assert (subNode.getKey().getAux() != 0);
          extraStallCond += (extraStallCond.isEmpty() ? "" : " || ") + subNode.getExpressionWithParens();
        }
      }

      // Fulfill this builder's duty to provide the given node, even if it is just used as a marker.
      // -> Any user (lookupRequired caller) of this node should use RdStall||WrStall on the sub pipelines.
      //    The expression result must not be assumed valid.
      ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(WrStallISAXEntry, nodeKey.getStage(), nodeKey.getISAX()),
                                           "PLACEHOLDER_" + nodeKey.toString(), ExpressionType.AnyExpression));

      // Also add the WrStall condition of the combinational sub-pipeline entry to the base pipeline.
      String stallSubpipeCond = "";
      String flushSubpipeCond = "";
      for (PipelineStage entryStage : entryStages) {
        stallSubpipeCond += (stallSubpipeCond.isEmpty() ? "" : " || ") +
                            SCALUtil.buildCond_StageStalling(bNodes, registry, entryStage, false);
        flushSubpipeCond += (flushSubpipeCond.isEmpty() ? "" : " || ") +
                            SCALUtil.buildCond_StageFlushing(bNodes, registry, entryStage);
      }

      if (!extraStallCond.isEmpty() || !stallSubpipeCond.equals("0") && !stallSubpipeCond.equals("1'b0")) {
        String stallCondBaseExpr = "1'b0";

        if (!extraStallCond.isEmpty() &&
            !op_stage_instr.getOrDefault(bNodes.RdStall, new HashMap<>())
                 .entrySet()
                 .stream()
                 .anyMatch(
                     stage_instrs -> stage_instrs.getValue().contains(nodeKey.getISAX()) && entryStages.contains(stage_instrs.getKey()))) {
          // The ISAX does not check for stalls in the spawn entry stage, so we need to stall the stages before that.
          // For stalling of the early stages, use WrStallISAXEntryEarly.
          String earlyExtraStallCond = "";
          for (NodeInstanceDesc subNode :
               registry.lookupAll(new NodeInstanceDesc.Key(WrStallISAXEntryEarly, nodeKey.getStage(), ""), false)) {
            if (subNode.getKey().getISAX().equals(nodeKey.getISAX())) {
              // The sub-node should have a unique identifier.
              assert (subNode.getKey().getAux() != 0);
              earlyExtraStallCond += (earlyExtraStallCond.isEmpty() ? "" : " || ") + subNode.getExpressionWithParens();
            }
          }
          if (earlyExtraStallCond.isEmpty())
            earlyExtraStallCond = "0";

          for (PipelineStage preStage : nodeKey.getStage().getPrev())
            if (preStage.getKind() == StageKind.Core) {
              // Apply the 'early stall ISAX spawn entry' condition to the stages preceding the spawn base stage.
              String earlyIValid =
                  registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, preStage, nodeKey.getISAX()));

              String earlyStallCondWire = nodeKey.toString(false) + "_stallcond_early_" + preStage.getName() + "_s";
              ret.declarations += String.format("wire %s;\n", earlyStallCondWire);
              ret.logic += String.format("assign %s = %s && %s;\n", earlyStallCondWire, earlyIValid, earlyExtraStallCond);

              ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, preStage, "", aux),
                                                   earlyExtraStallCond, ExpressionType.AnyExpression));
              registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, preStage, ""));
            }
        } else {
          // Apply WrStallISAXEntry to the spawn base stage and all sub stages.
          if (extraStallCond.isEmpty())
            extraStallCond = "1'b0";
          stallCondBaseExpr = nodeKey.toString(false) + "_stallcond_base_s";
          ret.declarations += String.format("wire %s;\n", stallCondBaseExpr);
          ret.logic += String.format("assign %s = %s;\n", stallCondBaseExpr, extraStallCond);
          for (PipelineStage entryStage : entryStages) {
            // Also apply the stall to the entry stages.
            ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, entryStage, "", aux),
                                                 stallCondBaseExpr, ExpressionType.AnyExpression_Noparen));
            registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, entryStage, ""));
          }
        }
        // Apply the collected stall conditions (WrStallISAXEntry unless done in prior stage, sub-pipeline entry stalls) to the spawn base
        // stage.
        String stallCondWire = nodeKey.toString(false) + "_stallcond_s";
        ret.declarations += String.format("wire %s;\n", stallCondWire);
        ret.logic += String.format("assign %s = %s && (%s || %s) && !(%s);\n", stallCondWire, ivalidIn, stallSubpipeCond, stallCondBaseExpr,
                                   flushSubpipeCond);
        ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, nodeKey.getStage(), "", aux),
                                             stallCondWire, ExpressionType.WireName));
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, nodeKey.getStage(), ""));
      }

      return ret;
    }));
    return true;
  }

  private HashSet<NodeInstanceDesc.Key> implementedRdIValid = new HashSet<>();
  private HashSet<PipelineStage> implementedWrDeqInstrIValidDeps = new HashSet<>();

  /** Overrides the RdIValid sub-pipeline entry of SpawnStaticNodePipeStrategy, creating WrDeqInstr and WrStall in the base pipeline. */
  private boolean implementRdIValid(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
    if (nodeKey.getNode().equals(bNodes.WrDeqInstr) && nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0) {
      // Add dependencies to all RdIValid entry nodes, even for dynamic ISAXes.
      //  -> this ensures creation of all WrDeqInstr sub-conditions from the RdIValid sub-pipeline builder
      if (implementedWrDeqInstrIValidDeps.add(nodeKey.getStage())) {
        PipelineFront parentFront = new PipelineFront(nodeKey.getStage());
        // Get a list of <ISAX, entry stage> pairs below the current stage.
        List<Map.Entry<String, PipelineStage>> relevantISAXes =
            spawn_instr_stage.values()
                .stream()
                .flatMap(instr_stageMap -> instr_stageMap.entrySet().stream())
                .filter(instr_stage -> parentFront.isAround(instr_stage.getValue()))
                .flatMap(instr_stage
                         -> instr_stage.getValue()
                                .streamPrev_bfs()
                                .filter(prevStage -> prevStage.getPrev().isEmpty())
                                .map(entryStage -> Map.entry(instr_stage.getKey(), entryStage)))
                .distinct()
                .toList();
        out.accept(NodeLogicBuilder.fromFunction("SpawnRdIValidStrategy_addDependency_" + nodeKey.toString(false), registry -> {
          var ret = new NodeLogicBlock();
          relevantISAXes.forEach(
              isaxStage
              -> registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, isaxStage.getValue(), isaxStage.getKey())));
          return ret;
        }));
      }
      return false;
    }
    if (!(nodeKey.getNode().equals(bNodes.RdIValid)) || !nodeKey.getPurpose().matches(Purpose.PIPEDIN) || nodeKey.getAux() != 0)
      return false;
    if (nodeKey.getISAX().isEmpty()) {
      assert (false); // Shouldn't happen.
      return false;
    }
    if (nodeKey.getStage().getKind() != StageKind.Sub || nodeKey.getStage().getPrev().size() > 1)
      return false;
    assert (nodeKey.getStage().getParent().isPresent());
    if (nodeKey.getStage().getParent().get().getKind() == StageKind.Root) {
      logger.error("Found a Sub stage directly below Root: " + nodeKey.getStage().getName());
      return false;
    }

    if (nodeKey.getStage().getStagePos() == 0) {
      if (!implementedRdIValid.add(new NodeInstanceDesc.Key(bNodes.RdIValid, nodeKey.getStage(), nodeKey.getISAX())))
        return true; // Prevent duplicate implementation.

      List<PipelineStage> allEntryStages = getEntryStagesFor(nodeKey.getStage().getParent().get(), nodeKey.getISAX());
      if (allEntryStages.isEmpty()) {
        logger.error("SpawnRdIValidStrategy: {} is in a sub-pipeline, but getEntryStagesFor({},{}) is empty",
          nodeKey.toString(), nodeKey.getStage().getParent().get().toString(), nodeKey.getISAX());
        return false;
      }

      RequestedForSet requestedFor = new RequestedForSet(nodeKey.getISAX());
      out.accept(NodeLogicBuilder.fromFunction("SpawnRdIValidStrategy_fromParent_" + nodeKey.toString(false), (registry, aux) -> {
        NodeLogicBlock ret = new NodeLogicBlock();

        String signalName = nodeKey.toString(false) + "_pipedin";
        // Cannot generate a proper RdIValid for lack of a view on the pipeline. Only use it internally.
        if (allISAXes.get(nodeKey.getISAX()).GetRunsAsDynamic() && nodeKey.getStage().getNext().isEmpty()) {
          signalName += "_hidden";
          ret.outputs.add(new NodeInstanceDesc(
              new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, bNodes.RdIValid, nodeKey.getStage(), nodeKey.getISAX()),
              "UNDEFINED-dynamicISAX~" + nodeKey.toString(false), ExpressionType.AnyExpression));
        }
        ret.declarations += String.format("wire %s;\n", signalName);
        // RdIValid must be set in the parent stage.
        String ivalidIn = registry.lookupExpressionRequired(
            new NodeInstanceDesc.Key(bNodes.RdIValid, nodeKey.getStage().getParent().get(), nodeKey.getISAX()));
        // The instruction entering the parent stage must be actually valid.
        String instagevalidIn =
            registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdInStageValid, nodeKey.getStage().getParent().get(), ""));
        // The instruction entering the parent stage must be actually allowed to enter.
        //  -> commented out for now, as the entry stall condition is either applied to the preceding stage or the base stage and all
        //  related sub-pipeline entries.
        //     (by the builder for WrStallISAXEntry)
        Optional<NodeInstanceDesc> isaxentryStallIn_opt =
            Optional.empty(); // registry.lookupOptional(new NodeInstanceDesc.Key(WrStallISAXEntry, nodeKey.getStage().getParent().get(),
                              // nodeKey.getISAX()));
        ret.logic +=
            String.format("assign %s = %s && %s%s;\n", signalName, ivalidIn, instagevalidIn,
                          isaxentryStallIn_opt.isPresent() ? (" && !" + isaxentryStallIn_opt.get().getExpressionWithParens()) : "");
        ret.outputs.add(
            new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.PIPEDIN, bNodes.RdIValid, nodeKey.getStage(), nodeKey.getISAX()),
                                 signalName, ExpressionType.WireName));

        // Also set WrDeqInstr if the instruction does enter its sub-pipeline.
        NodeInstanceDesc.Key wrDeqInstrKey =
            new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrDeqInstr, nodeKey.getStage().getParent().get(), "", aux);
        // Request instantiation of the combined WrDeqInstr, and of the to-Core interface as needed.
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrDeqInstr, nodeKey.getStage().getParent().get(), ""),
                                          requestedFor);

        String wrDeqInstrWire = wrDeqInstrKey.toString(false) + "_s";
        ret.declarations += String.format("wire %s;\n", wrDeqInstrWire);

        //WrDeqInstr is low if any of the entry stages of the ISAX stalls.
        //TODO: Properly distribute WrStall/RdStall across neighboring sub-pipelines in the first place.
        String stallSubpipeCond = "";
        String flushSubpipeCond = "";
        for (PipelineStage entryStage : allEntryStages) {
          stallSubpipeCond += (stallSubpipeCond.isEmpty() ? "" : " || ") +
                              SCALUtil.buildCond_StageStalling(bNodes, registry, entryStage, false);
          flushSubpipeCond += (flushSubpipeCond.isEmpty() ? "" : " || ") +
                              SCALUtil.buildCond_StageFlushing(bNodes, registry, entryStage);
        }

        ret.logic += String.format("assign %s = %s && !(%s || %s);\n", wrDeqInstrWire, signalName, stallSubpipeCond, flushSubpipeCond);
        ret.outputs.add(new NodeInstanceDesc(wrDeqInstrKey, wrDeqInstrWire, ExpressionType.WireName, requestedFor));

        // Trigger generation of WrStallISAXEntry, which also applies the default stall condition in the base pipeline.
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(WrStallISAXEntry, nodeKey.getStage().getParent().get(), nodeKey.getISAX()));

        return ret;
      }));
      return true;
    }
    return false;
  }

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    var nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      NodeInstanceDesc.Key nodeKey = nodeKeyIter.next();
      if (this.implementRdIValid(out, nodeKey) || this.implementRdAnyValid(out, nodeKey) || this.implementStallISAXEntry(out, nodeKey) ||
          this.implementCounter(out, nodeKey))
        nodeKeyIter.remove();
    }
  }
}
