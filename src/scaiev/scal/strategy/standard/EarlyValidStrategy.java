package scaiev.scal.strategy.standard;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;
import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.coreconstr.CoreNode;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
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
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.util.ListRemoveView;
import scaiev.util.Verilog;

/** Announces the presence of an ISAX using a node early through validReq. Also produces validData. */
public class EarlyValidStrategy extends MultiNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  StrategyBuilders strategyBuilders;
  Verilog language;
  BNode bNodes;
  Core core;
  HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  HashMap<String, SCAIEVInstr> allISAXes;
  HashMap<SCAIEVNode, PipelineFront> node_earliestStageValid;
  /**
   * @param strategyBuilders The StrategyBuilders object to build sub-strategies with
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param allISAXes The ISAX descriptions
   * @param node_earliestStageValid The node attribute that says when the presence of an ISAX using a node should be announced.
   */
  public EarlyValidStrategy(StrategyBuilders strategyBuilders, Verilog language, BNode bNodes, Core core,
                            HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                            HashMap<String, SCAIEVInstr> allISAXes, HashMap<SCAIEVNode, PipelineFront> node_earliestStageValid) {
    this.strategyBuilders = strategyBuilders;
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.allISAXes = allISAXes;
    this.node_earliestStageValid = node_earliestStageValid;
  }

  /**
   * This function creates the text for Node_ValidReq based on instruction decoding.
   * NOT applicable for always-mode ISAXes
   * @param registry
   * @param stage_lookAtISAX
   * @param stage
   * @param operation
   * @param earliestStage
   * @return
   */
  private NodeLogicBlock CreateValidReqEncodingEarlierStages(NodeRegistryRO registry,
                                                             HashMap<PipelineStage, HashSet<String>> stage_lookAtISAX, PipelineStage stage,
                                                             SCAIEVNode operation, PipelineFront earliestStage) {
    final String tab = language.tab;

    SCAIEVNode checkAdj = bNodes.GetAdjSCAIEVNode(operation, AdjacentNode.validReq).get();

    String checkAdjNodeName_out = language.CreateRegNodeName(checkAdj, stage, "");

    NodeLogicBlock ret = new NodeLogicBlock();
    // Add the local declaration for the validReq output.
    //(Could alternatively set the module output directly,
    //  but this way the generic 'assign <output> = <node dependency>' logic block can be used)
    ret.declarations += "reg " + checkAdjNodeName_out + ";\n"; // language.CreateDeclSig(checkAdj, stage, "", true);
    // ret.declarations += "reg " + checkAdjNodeName_internal_next + "; // Info: if not really needed, will be optimized away by synth tool
    // \n";

    var requestedFor = new RequestedForSet();
    ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.WIREOUT, checkAdj, stage, "", 0), checkAdjNodeName_out,
                                         ExpressionType.WireName, requestedFor));
    // Additionally, output the validReq signal up until the current stage (<stage>) as a separate node tagged with the source stage.
    //  This is then pipelined as an input for the next stage validReq builder.
    ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.PIPEOUT, checkAdj, stage, "", 0), checkAdjNodeName_out,
                                         ExpressionType.AnyExpression, requestedFor));

    String body = "always_comb begin \n" + tab.repeat(1) + "case(1'b1)\n";
    boolean returnEmpty = true;
    for (PipelineStage stageISAX : stage_lookAtISAX.keySet())
      if (!stage.equals(stageISAX)) {

        // Order ISAXes so that the ones without opcode have priority (they are like spawn)
        TreeMap<Integer, String> lookAtISAXOrdered = language.OrderISAXOpCode(stage_lookAtISAX.get(stageISAX), allISAXes);
        for (int priority : lookAtISAXOrdered.descendingKeySet()) {
          String ISAX = lookAtISAXOrdered.get(priority);
          // Check if no opcode ISAX (which does not have rdivalid).
          // if(allISAXes.get(ISAX).HasNoOp() && !stage.equals(operation.commitStage))
          // 	continue;
          // else
          // 	returnEmpty = false;

          // if (allISAXes.get(ISAX).HasNoOp() && !stage.equals(stageISAX))
          //	continue;

          // Create RdIValid = user valid for instr without encoding, else decode instr and create IValid
          String RdIValid = null;
          if (allISAXes.get(ISAX).HasNoOp()) {
            SCAIEVNode userValid = bNodes.GetAdjSCAIEVNode(operation, AdjacentNode.validReq).get();
            // TODO: In which case is this first condition false?
            if (operation.getAdj() == AdjacentNode.validReq)
              RdIValid = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(operation, stage, ISAX));
            else if (!operation.isInput && !operation.DH) // if output (read node) and has no DH ==> no valid bit required, constant read
              RdIValid = "1'b0";
            else
              RdIValid = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(userValid, stage, ISAX));
          } else if (core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.RdIValid).GetEarliest()).isAfter(stage, false)) {
            // For early stages where RdIValid is not available (-> usually the fetch stage), only include no-opcode/'always' ISAXes.
            continue;
          }
          requestedFor.addRelevantISAX(ISAX);
          returnEmpty = false;
          if (RdIValid == null)
            RdIValid = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, stage, ISAX));
          // For ISAXes with opcode, generate case-logic
          if (new PipelineFront(stage).isAround(stageISAX) &&
              allISAXes.get(ISAX).GetFirstNode(operation).HasAdjSig(AdjacentNode.validReq)) {
            body += tab.repeat(2) + RdIValid + " : " + checkAdjNodeName_out + " = " +
                    registry.lookupExpressionRequired(new NodeInstanceDesc.Key(checkAdj, stage, ISAX)) + ";\n";
          } else if (new PipelineFront(stage).isAroundOrBefore(stageISAX, false))
            body += tab.repeat(2) + RdIValid + " : " + checkAdjNodeName_out + " = 1'b1;\n";
        }
      }
    String defaultExpression = "1'b0";

    // Use the pipelined (or otherwise generated) result from the previous stage as the default, if possible.
    var pipedinNodeInst = registry.lookupRequired(new NodeInstanceDesc.Key(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, checkAdj, stage, ""));
    if (!pipedinNodeInst.getExpression().startsWith(NodeRegistry.MISSING_PREFIX)) {
      requestedFor.addAll(pipedinNodeInst.getRequestedFor(), true); // Add the previous stage's requestedFor set.
      String flushExpr =
          registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, stage, ""), requestedFor).getExpressionWithParens();
      var wrflushInst_opt = registry.lookupOptional(new NodeInstanceDesc.Key(bNodes.WrFlush, stage, ""), requestedFor);
      if (wrflushInst_opt.isPresent())
        flushExpr = String.format("(%s || %s)", flushExpr, wrflushInst_opt.get().getExpressionWithParens());
      defaultExpression = pipedinNodeInst.getExpression() + " && !" + flushExpr;
    } else {
      // No pipelining possible into the earliest stage where this node is emitted.
      // Use 1'b0 as default.
    }
    body += tab.repeat(2) + String.format("default : %s = %s;\n", checkAdjNodeName_out, defaultExpression);

    body += tab.repeat(1) + "endcase\nend\n";
    //		if(returnEmpty) {
    //			logger.warn("EarlyValidStrategy building {} in stage {}: NO ISAX detected with opcode, RTL probably not functional",
    // checkAdjNodeName_out, stage.getName()); 			ret.logic += "assign "+ checkAdjNodeName_out +" = 1'b1; // CRITICAL WARNING,
    // NO ISAX detected with opcode, RTL probably not functional\n";
    //		}
    //		else
    ret.logic += body;

    return ret;
  }
  /**
   * This function creates the text for Node_ValidData based on instruction decoding.
   * @param registry
   * @param stage_lookAtISAX
   * @param stage
   * @param baseNode
   * @return
   */
  private NodeLogicBlock CreateValidDataEncodingEarlierStages(NodeRegistryRO registry,
                                                              HashMap<PipelineStage, HashSet<String>> stage_lookAtISAX, PipelineStage stage,
                                                              SCAIEVNode baseNode) {
    String tab = language.tab;
    String body = "always_comb begin \n" + tab.repeat(1) + "case(1'b1)\n";
    SCAIEVNode nodeValidData = bNodes.GetAdjSCAIEVNode(baseNode, AdjacentNode.validData).get().makeFamilyNode();
    String nodeNameValidData = language.CreateLocalNodeName(nodeValidData, stage, "");
    boolean returnEmpty = true;

    NodeLogicBlock ret = new NodeLogicBlock();

    var requestedFor = new RequestedForSet();

    PipelineStage stageISAX = stage;
    var isaxesMap = stage_lookAtISAX.get(stage);

    if (isaxesMap != null) {
      for (String ISAX : isaxesMap) {
        // Check if no opcode ISAX (which does not have rdivalid).
        if (!ISAX.isEmpty() && allISAXes.get(ISAX).HasNoOp() && !stage.equals(stageISAX))
          continue;

        // Create RdIValid = user valid for instr without encoding, else decode instr and create IValid
        String RdIValid = null;
        if (ISAX.isEmpty()) {
          // Mostly semi-coupled spawn.
          // TODO: validData is only ever used for EarlyValid.
          //       It appears like validData here is what most of the codebase considers validReq to be,
          //        whereas the validReq provided by this strategy is something else entirely.
          //       The 'early validReq' should be renamed along the lines of futureValidReq,
          //        while the 'early validData' should become validReq.
          SCAIEVNode userValid = bNodes.GetAdjSCAIEVNode(baseNode, AdjacentNode.validReq).get();
          RdIValid = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(userValid, stage, ISAX));
          if (RdIValid.startsWith(NodeRegistry.MISSING_PREFIX))
            continue;
        } else if (allISAXes.get(ISAX).HasNoOp()) {
          SCAIEVNode userValid = bNodes.GetAdjSCAIEVNode(baseNode, AdjacentNode.validData).get();
          // TODO: In which case is this first condition false?
          if (baseNode.getAdj() == AdjacentNode.validData)
            RdIValid = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(baseNode, stage, ISAX));
          else if (!baseNode.isInput && !baseNode.DH) // if output (read node) and has no DH ==> no valid bit required, constant read
            RdIValid = "1'b0";
          else
            RdIValid = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(userValid, stage, ISAX));
        } else if (core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.RdIValid).GetEarliest()).isAfter(stage, false)) {
          // For early stages where RdIValid is not available (-> usually the fetch stage), only include no-opcode/'always' ISAXes.
          continue;
        }
        if (!ISAX.isEmpty())
          requestedFor.addRelevantISAX(ISAX);
        // For ISAXes with opcode, generate case-logic
        if (RdIValid == null)
          RdIValid = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, stage, ISAX));
        returnEmpty = false;
        if (new PipelineFront(stage).isAroundOrAfter(stageISAX, false))
          body += tab.repeat(2) + RdIValid + " : " + nodeNameValidData + " = 1'b1;\n";
        else
          body += tab.repeat(2) + RdIValid + " : " + nodeNameValidData + " = 1'b0;\n";
      }
    }
    body += tab.repeat(2) + "default : " + nodeNameValidData + " = 1'b0;\n";
    body += tab.repeat(1) + "endcase\nend\n";
    //		if(returnEmpty)
    //			ret.logic += "assign "+ nodeNameValidData +" = 1'b1; // CRITICAL WARNING, NO ISAX detected with opcode, RTL probably
    // not functional\n"; 		else
    ret.logic += body;

    //			if(!BNode.IsUserBNode(baseNode)) {
    //				//TODO: In this case, it should be a core interface
    //				String newinterf = language.CreateTextInterface(nodeValidData.name, stage, "",
    //						!nodeValidData.isInput, nodeValidData.size, "reg");
    //				ret.interfPins.add(Map.entry(NodeLogicBlock.InterfToCoreKey, newinterf));
    //				//String newinterf = CreateAndRegisterTextInterfaceForCore(nodeValidData.NodeNegInput(), stage, "", "reg");
    //				newInterfaceToCore.add(newinterf);
    //			}
    ret.declarations += (language.CreateDeclSig(nodeValidData, stage, "", true, nodeNameValidData));
    var newOutput = new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.WIREOUT, nodeValidData, stage, ""), nodeNameValidData,
                                         ExpressionType.WireName, requestedFor);
    ret.outputs.add(newOutput);
    return ret;
  }

  protected boolean implementSingle(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
    // Will be called on a validReq node.
    // If requested at any later stage, will produce a pipeline to the previous stage,
    //  which will continue down to the stage marked by the core as 'earliest valid'.
    // Assumes that validReq and validData are added to the core's interface correctly already.
    //       (Else, this strategy would never apply)
    SCAIEVNode baseNode = bNodes.GetSCAIEVNode(nodeKey.getNode().nameParentNode);
    if (baseNode.name.isEmpty() || !op_stage_instr.containsKey(baseNode))
      return false;
    if (nodeKey.getStage().getKind() != StageKind.Core || !nodeKey.getISAX().isEmpty())
      return false;
    AdjacentNode adj = nodeKey.getNode().getAdj();
    if ((adj.equals(AdjacentNode.validReq) || adj.equals(AdjacentNode.validData)) && node_earliestStageValid.containsKey(baseNode)) {
      // TODO: This comparison may run into issues if the same node is requested as a multi-match Purpose, due to which the pure PIPEDIN may
      // get lost.
      if (nodeKey.getPurpose().equals(Purpose.PIPEDIN)) {
        assert (adj.equals(AdjacentNode.validReq));
        // This node is just the pipeline for the previous stage node.
        // Since the validReq signals are combined over all ISAXes and thus, possibly, over multiple stages,
        //  the pipeline strategy is applied per stage.
        //  The resulting output of the pipeline is then taken to add on any additional validReqs.
        var pipelineStrategy =
            strategyBuilders.buildNodeRegPipelineStrategy(language, bNodes, new PipelineFront(nodeKey.getStage()), false, false, true,
                                                          _nodeKey -> true, _nodeKey -> false, MultiNodeStrategy.noneStrategy,
                                                          true);
        pipelineStrategy.implement(out, new ListRemoveView<>(List.of(nodeKey)), false);
        return true;
      }

      HashMap<PipelineStage, HashSet<String>> stage_lookAtISAX = new HashMap<>(op_stage_instr.get(baseNode));
      CoreNode coreNode = core.GetNodes().get(baseNode);
      if (coreNode == null)
        return false;
      PipelineFront relevantStages =
          core.TranslateStageScheduleNumber(coreNode.GetLatest()); // new PipelineFront(op_stage_instr.get(baseNode).keySet());

      if (!(node_earliestStageValid.get(baseNode).isAroundOrBefore(nodeKey.getStage(), false)) ||
          !relevantStages.isAroundOrAfter(nodeKey.getStage(), false))
        return false;
      if (!nodeKey.getPurpose().matches(Purpose.WIREOUT))
        return false;
      if (adj.equals(AdjacentNode.validReq)) {
        out.accept(NodeLogicBuilder.fromFunction("EarlyValidStrategy (" + nodeKey.toString() + ")", registry -> {
          return CreateValidReqEncodingEarlierStages(registry, stage_lookAtISAX, nodeKey.getStage(), baseNode,
                                                     node_earliestStageValid.get(baseNode));
        }));
        return true;
      } else if (adj.equals(AdjacentNode.validData)) {
        out.accept(NodeLogicBuilder.fromFunction("EarlyValidStrategy (" + nodeKey.toString() + ")", registry -> {
          return CreateValidDataEncodingEarlierStages(registry, stage_lookAtISAX, nodeKey.getStage(), baseNode);
        }));
        return true;
      } else
        assert (false); // unreachable
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
