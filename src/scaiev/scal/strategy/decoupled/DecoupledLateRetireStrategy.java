package scaiev.scal.strategy.decoupled;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.IntStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.SCALUtil;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.pipeline.IDRetireSerializerStrategy;
import scaiev.ui.SCAIEVConfig;
import scaiev.util.Log2;
import scaiev.util.Verilog;

/**
 * Implements retire/discard tracking for decoupled instructions,
 * issuing confirmations or cancellations to the spawn operation InputFIFO.
 * Should only be used if SpawnOptionalInputFIFOStrategy is enabled.
 * E.g. {@link SpawnOptionalInputFIFOStrategy#makeCommitConfirmedNode(scaiev.backend.BNode, scaiev.frontend.SCAIEVNode)}
 */
public class DecoupledLateRetireStrategy extends MultiNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  Verilog language;
  BNode bNodes;
  Core core;
  HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage;
  HashMap<String, SCAIEVInstr> allISAXes;
  IDRetireSerializerStrategy retireSerializer;
  SCAIEVConfig cfg;
  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param spawn_instr_stage The Node-ISAX-Stage mapping providing the precise sub-pipeline stage for spawn operations
   * @param allISAXes The ISAX descriptions
   * @param retireSerializer The serializer for instruction retires
   * @param cfg The SCAIE-V global config
   */
  public DecoupledLateRetireStrategy(Verilog language, BNode bNodes, Core core,
                                     HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                                     HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage,
                                     HashMap<String, SCAIEVInstr> allISAXes,
                                     IDRetireSerializerStrategy retireSerializer,
                                     SCAIEVConfig cfg) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.spawn_instr_stage = spawn_instr_stage;
    this.allISAXes = allISAXes;
    this.retireSerializer = retireSerializer;
    this.cfg = cfg;
  }


  private void BuildForNode(NodeInstanceDesc.Key markerKey, NodeLogicBlock logicBlock, NodeRegistryRO registry, SCAIEVNode spawnNode,
                            PipelineStage spawnStage, int aux,
                            List<PipelineStage> issueStagesList) {
    if (issueStagesList.isEmpty() || !cfg.decoupled_retire_hazard_handling
        || !op_stage_instr.containsKey(spawnNode) || !op_stage_instr.get(spawnNode).containsKey(spawnStage)) {
      logicBlock.outputs.add(new NodeInstanceDesc(markerKey, "(disabled)", ExpressionType.AnyExpression_Noparen));
      return;
    }
    String nodeNonspawnName = spawnNode.nameParentNode.isEmpty() ? spawnNode.name : spawnNode.nameParentNode;

    var relevantISAXes = List.copyOf(op_stage_instr.get(spawnNode).get(spawnStage));
    if (relevantISAXes.isEmpty()) {
      logicBlock.outputs.add(new NodeInstanceDesc(markerKey, "(disabled)", ExpressionType.AnyExpression_Noparen));
      return;
    }
    String declNameBase = "DecLateRetire_%s_%d".formatted(nodeNonspawnName, aux);

    //Create a buffer specifying which decoupled ISAX corresponds to an IssueID.
    // -> Use 0 as invalid, then one number per entry in relevantISAXes.
    //TODO: Share across spawnNodes (move bitmap logic to separate builder)
    int isaxNumWidth = Log2.clog2(1 + relevantISAXes.size());
    String ivalidBufferName = "%s_isaxvalid".formatted(declNameBase);
    logicBlock.declarations += "logic [%d-1:0] %s [%d];\n".formatted(isaxNumWidth, ivalidBufferName, bNodes.RdIssueID.elements);

    StringBuilder updateBufferLogic = new StringBuilder();
    //Reset the buffer to 0 (all invalid)
    updateBufferLogic.append("if (%s) begin\n".formatted(language.reset));
    for (int iID = 0; iID < bNodes.RdIssueID.elements; ++iID) {
      updateBufferLogic.append("    %s[%d] <= %d'd0;\n".formatted(ivalidBufferName, iID, isaxNumWidth));
    }
    updateBufferLogic.append("end\n");
    updateBufferLogic.append("else begin\n");
    for (PipelineStage issueStage : issueStagesList) {
      String stageNotStallingCond = SCALUtil.buildCond_StageNotStalling(bNodes, registry, issueStage, true);
      //For each issue stage that runs through, write to the ivalidBuffer.
      updateBufferLogic.append("    if (%s) begin : %s_scope_%s\n".formatted(stageNotStallingCond, declNameBase, issueStage.getName()));
      String indent = "        ";
      updateBufferLogic.append(indent + "logic [%d-1:0] isaxid;\n".formatted(isaxNumWidth));
      //Default to invalid (no relevant decoupled ISAX).
      updateBufferLogic.append(indent + "isaxid = %d'd0;\n".formatted(isaxNumWidth));
      for (int iISAX = 0; iISAX < relevantISAXes.size(); ++iISAX) {
        //Assign the ID based on the present decoupled ISAX.
        String ivalid = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, issueStage, relevantISAXes.get(iISAX)));
        updateBufferLogic.append(indent+"%sif (%s) begin\n".formatted((iISAX > 0) ? "else " : "", ivalid));
        updateBufferLogic.append(indent+"    isaxid = %d'd%d;\n".formatted(isaxNumWidth, iISAX+1));
        updateBufferLogic.append(indent+"end\n");
      }
      String issueIDExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIssueID, issueStage, ""));
      updateBufferLogic.append(indent+"%s[%s] <= isaxid;\n".formatted(ivalidBufferName, issueIDExpr));
      updateBufferLogic.append("    end\n");
    }
    updateBufferLogic.append("end\n");
    logicBlock.logic += language.CreateInAlways(true, updateBufferLogic.toString());

    retireSerializer.registerRetireConsumer(3); //tracking logic should be fairly cheap
    var retireSignals = retireSerializer.getSignals();

    SCAIEVNode fifo_commitConfirmedNode = SpawnOptionalInputFIFOStrategy.makeCommitConfirmedNode(bNodes, spawnNode);
    SCAIEVNode fifo_commitCancelledNode = SpawnOptionalInputFIFOStrategy.makeCommitCancelledNode(bNodes, spawnNode);
    SCAIEVNode fifo_commitReadaheadConfirmedNode = SpawnOptionalInputFIFOStrategy.makeCommitReadaheadConfirmedNode(bNodes, spawnNode);
    SCAIEVNode fifo_isDequeueingNode = SpawnOptionalInputFIFOStrategy.makeReadNode(bNodes, spawnNode);
    class RetireSignalsTmp {
        String isValidExpr; //pure valid expression (not including stall)
        String isDiscardExpr; //if a valid retire is a discard
        String idExpr; //the ID being retired
        NodeInstanceDesc.Key stallRetireKey; //key to apply stalls to
        String isStallingExpr; //full stalling condition (including added stalls)
    }
    RetireSignalsTmp[] retirePorts = new RetireSignalsTmp[retireSignals.getWidthLimit()];
    for (int iRetirePort = 0; iRetirePort < retireSignals.getWidthLimit(); ++iRetirePort) {
      retirePorts[iRetirePort] = new RetireSignalsTmp();
      retirePorts[iRetirePort].isValidExpr = registry.lookupRequired(retireSignals.getKey_valid(iRetirePort)).getExpressionWithParens();
      retirePorts[iRetirePort].isDiscardExpr = registry.lookupRequired(retireSignals.getKey_isDiscard(iRetirePort)).getExpressionWithParens();
      retirePorts[iRetirePort].idExpr = registry.lookupRequired(retireSignals.getKey_retireID(iRetirePort)).getExpressionWithParens();

      retirePorts[iRetirePort].stallRetireKey = retireSignals.getKey_stall(iRetirePort);
      retirePorts[iRetirePort].isStallingExpr = registry.lookupRequired(retirePorts[iRetirePort].stallRetireKey).getExpressionWithParens();
    }
    for (int iISAX = 0; iISAX < relevantISAXes.size(); ++iISAX) {
      String isaxName = relevantISAXes.get(iISAX);
      var fifoIsDequeueingInst_opt = registry.lookupOptional(new NodeInstanceDesc.Key(fifo_isDequeueingNode, spawnStage, isaxName));
      if (fifoIsDequeueingInst_opt.isEmpty())
        continue; //No FIFO built for this node (so far).

      if (spawnNode.name.isEmpty() || !spawn_instr_stage.containsKey(spawnNode) ||
          !spawn_instr_stage.get(spawnNode).containsKey(isaxName) ||
          !new PipelineFront(spawnStage).isAround(spawn_instr_stage.get(spawnNode).get(isaxName))) {
        logger.warn("DecoupledLateRetireStrategy: Skipping retire hazard handling for ISAX %s node %s (cannot find spawn entry)"
                    .formatted(isaxName, spawnNode.name));
        continue;
      }
      PipelineStage spawnSubStage = spawn_instr_stage.get(spawnNode).get(isaxName);

      String fifoIsDequeueingCond = fifoIsDequeueingInst_opt.get().getExpression();

      // Counters named as <counterNameBase>_<iCounter>.
      int maxCounterVal = bNodes.RdIssueID.elements - 1;
      assert(spawnSubStage.getKind() == StageKind.Sub); //Kind of expected
      if (spawnSubStage.getKind() == StageKind.Sub)
        maxCounterVal += spawnSubStage.getStagePos();
      maxCounterVal = Math.min(cfg.decoupled_parallel_max, maxCounterVal);
      int counterWidth = Log2.clog2(maxCounterVal + 1);
      maxCounterVal = (1 << counterWidth) - 1;

      //One counter for discard, one for successful retire.
      String counterName = "%s_counter_%s".formatted(declNameBase, isaxName);
      logicBlock.declarations += "logic [%d-1:0] %s [2];\n".formatted(counterWidth, counterName);
      String newCounterName = "%s_counter_%s_new".formatted(declNameBase, isaxName);
      logicBlock.declarations += "logic [%d-1:0] %s [2];\n".formatted(counterWidth, newCounterName);

      String underflowErrWire = "%s_counter_%s_underflow".formatted(declNameBase, isaxName);
      logicBlock.declarations += "logic %s;\n".formatted(underflowErrWire);
      String overflowErrWire = "%s_counter_%s_overflow".formatted(declNameBase, isaxName);
      logicBlock.declarations += "logic %s;\n".formatted(overflowErrWire);

      StringBuilder retireCountLogic = new StringBuilder();
      retireCountLogic.append("""
        always_comb begin
            %1$s[0] = %2$s[0];
            %1$s[1] = %2$s[1];
            %5$s = 1'b0;
            %6$s = 1'b0;
            if (%4$s) begin
                // Decrease counter on FIFO dequeue (=> operation complete)
                if (%1$s[0] > %3$d'd0)
                    %1$s[0] = %1$s[0] - %3$d'd1;
                else if (%1$s[1] > %3$d'd0)
                    %1$s[1] = %1$s[1] - %3$d'd1;
                else
                    %5$s = 1'b1; //"underflow": FIFO dequeue with no matching retire
            end
        """.formatted(newCounterName/*1*/, counterName/*2*/,
                      counterWidth/*3*/, fifoIsDequeueingCond/*4*/,
                      underflowErrWire/*5*/, overflowErrWire/*6*/));
      for (int iRetirePort = 0; iRetirePort < retirePorts.length; ++iRetirePort) {
        String isISAXWire = "%s_retireisisax_%s_%d".formatted(declNameBase, isaxName, iRetirePort);
        String stallRetireWire = "%s_retirestall_%s_%d".formatted(declNameBase, isaxName, iRetirePort);
        logicBlock.declarations += "logic %s;\n".formatted(isISAXWire);
        logicBlock.declarations += "logic %s;\n".formatted(stallRetireWire);
        var retirePort = retirePorts[iRetirePort];

        logicBlock.logic += "assign %s = %s && %s[%s] == %d'd%d;\n"
                            .formatted(isISAXWire, retirePort.isValidExpr, ivalidBufferName, retirePort.idExpr, isaxNumWidth, iISAX+1);
        var stallRetireKey = new NodeInstanceDesc.Key(Purpose.REGULAR, retirePort.stallRetireKey.getNode(), retirePort.stallRetireKey.getStage(), isaxName, aux);
        //Stall retire if it is on the current ISAX and:
        //- the counter is full already
        String counterFullExpr = "%s[%s] == '1".formatted(counterName, retirePort.isDiscardExpr);
        //- this is a regular retire but still have some discards left
        String orderHazardExpr = "(!%s && %s[1] != '0)".formatted(retirePort.isDiscardExpr, counterName);
        //- this is a regular retire but an earlier retire port has a discard
        String anyPreviousIsDiscard = IntStream.range(0,iRetirePort).mapToObj(i->retirePorts[i].isDiscardExpr).reduce((a,b)->a+" || "+b).orElse("");
        String orCombDiscardConflict = (iRetirePort > 0) ? " || !%s && (%s)".formatted(retirePort.isDiscardExpr, anyPreviousIsDiscard) : "";
        String stallRetireCond = "%s && (%s || %s%s)".formatted(isISAXWire, counterFullExpr, orderHazardExpr, orCombDiscardConflict);
        logicBlock.logic += "assign %s = %s;\n".formatted(stallRetireWire, stallRetireCond);
        logicBlock.outputs.add(new NodeInstanceDesc(stallRetireKey, stallRetireWire, ExpressionType.AnyExpression));

        //Update the counter wire.
        //The update applies if the retire is valid, points to the ISAX and is not being stalled.
        retireCountLogic.append("""
                if (%1$s && !%2$s) begin
                    if (%3$s[%4$s] == '1)
                        %6$s = 1'b1; //Overflow: Counter already max.
                    %3$s[%4$s] = %3$s[%4$s] + %5$d'd1;
                end
            """.formatted(isISAXWire/*1*/, retirePort.isStallingExpr/*2*/, newCounterName/*3*/, retirePort.isDiscardExpr/*4*/, counterWidth/*5*/, overflowErrWire/*6*/));
      }

      retireCountLogic.append("end\n");
      logicBlock.logic += retireCountLogic.toString();
      //Update counter registers.
      logicBlock.logic += """
          always_ff @(posedge %1$s) begin
              if (%2$s) begin
                  %3$s[0] <= '0;
                  %3$s[1] <= '0;
              end
              else begin
                  %3$s[0] <= %4$s[0];
                  %3$s[1] <= %4$s[1];
                  `ifndef SYNTHESIS
                  if (%5$s)
                      $error("SCAL: %s - Underflow");
                  if (%6$s)
                      $error("SCAL: %s - Overflow");
                  `endif
              end
          end
          """.formatted(language.clk/*1*/, language.reset/*2*/,
                        counterName/*3*/, newCounterName/*4*/,
                        underflowErrWire/*5*/, overflowErrWire/*6*/);

      //Create the FIFO control signals.
      //Confirmed <=> non-discard counter is above zero
      String nextFifoDeqConfirmedWire = "%s_nextvalid_%s".formatted(declNameBase, isaxName);
      logicBlock.declarations += "logic %s;\n".formatted(nextFifoDeqConfirmedWire);
      logicBlock.logic += "assign %s = %s[0] > %d'd0;\n".formatted(nextFifoDeqConfirmedWire, counterName, counterWidth);
      logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(fifo_commitConfirmedNode, spawnStage, isaxName),
                                                  nextFifoDeqConfirmedWire, ExpressionType.WireName));

      //Confirmed <=> non-discard counter is above one
      String readaheadFifoDeqConfirmedWire = "%s_readaheadvalid_%s".formatted(declNameBase, isaxName);
      logicBlock.declarations += "logic %s;\n".formatted(readaheadFifoDeqConfirmedWire);
      logicBlock.logic += "assign %s = %s[0] > %d'd1;\n".formatted(readaheadFifoDeqConfirmedWire, counterName, counterWidth);
      logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(fifo_commitReadaheadConfirmedNode, spawnStage, isaxName),
                                                  readaheadFifoDeqConfirmedWire, ExpressionType.WireName));

      //Cancelled <=> non-discard counter is zero and discard counter is above zero
      String nextFifoDeqCancelledWire = "%s_nextcancel_%s".formatted(declNameBase, isaxName);
      logicBlock.declarations += "logic %s;\n".formatted(nextFifoDeqCancelledWire);
      logicBlock.logic += "assign %1$s = %2$s[0] == %3$d'd0 && %2$s[1] > %3$d'd0;\n"
                          .formatted(nextFifoDeqCancelledWire/*1*/, counterName/*2*/, counterWidth/*3*/);
      logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(fifo_commitCancelledNode, spawnStage, isaxName),
                                                  nextFifoDeqCancelledWire, ExpressionType.WireName));
    }

    logicBlock.outputs.add(new NodeInstanceDesc(markerKey, "1", ExpressionType.AnyExpression_Noparen));
  }

  public static NodeInstanceDesc.Purpose purpose_MARKER_BUILT_DEC_LATE_RETIRE =
      new NodeInstanceDesc.Purpose("MARKER_BUILT_DEC_LATE_RETIRE", true, Optional.empty(), List.of());
  private HashSet<SCAIEVNode> implementedDHModules = new HashSet<>();

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    Iterator<NodeInstanceDesc.Key> keyIter = nodeKeys.iterator();
    while (keyIter.hasNext()) {
      NodeInstanceDesc.Key nodeKey = keyIter.next();
      if (!nodeKey.getPurpose().matches(purpose_MARKER_BUILT_DEC_LATE_RETIRE) || nodeKey.getStage().getKind() != StageKind.Decoupled ||
          !nodeKey.getNode().DH || !nodeKey.getNode().isSpawn() || nodeKey.getAux() != 0)
        continue;
      if (nodeKey.getNode().isAdj()) {
        logger.warn("DecoupledLateRetireStrategy: Cannot build DH module for adj node - " + nodeKey.toString());
        continue;
      }

      List<PipelineStage> issueStagesList = DecoupledPipeStrategy.getRelevantIssueStages(core, nodeKey.getStage());
      if (implementedDHModules.add(nodeKey.getNode())) {
        out.accept(NodeLogicBuilder.fromFunction("DecoupledLateRetireStrategy_DHModule_" + nodeKey.getNode().name,
           (NodeRegistryRO registry, Integer aux) -> {
             NodeLogicBlock ret = new NodeLogicBlock();
             BuildForNode(nodeKey, ret, registry, nodeKey.getNode(), nodeKey.getStage(), aux, issueStagesList);
             return ret;
           }));
      }
      keyIter.remove();
    }
  }

}
