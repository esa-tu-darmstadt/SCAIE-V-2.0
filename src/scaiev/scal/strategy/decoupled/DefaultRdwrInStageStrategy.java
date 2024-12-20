package scaiev.scal.strategy.decoupled;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.scal.InterfaceRequestBuilder;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.util.Verilog;

/** Generates either default implementations or core interface pins for RdInStageID, RdInStageValid, WrDeqInstr, WrInStageID_validResp. */
public class DefaultRdwrInStageStrategy extends MultiNodeStrategy {

  Verilog language;
  BNode bNodes;
  Core core;
  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   */
  public DefaultRdwrInStageStrategy(Verilog language, BNode bNodes, Core core) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
  }

  private HashSet<NodeInstanceDesc.Key> implementedKeys = new HashSet<>();

  private static final Purpose DefaultRdwrInStageInternalPurpose =
      new Purpose("DefaultRdwrInStage-Internal", true, Optional.empty(), List.of());

  private boolean implementSingle_stallmode(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
    SCAIEVNode node = nodeKey.getNode();
    PipelineStage stage = nodeKey.getStage();
    // Early node check
    if (node.equals(bNodes.RdInStageID) || (node.equals(bNodes.RdInStageValid) && stage.getTags().contains(StageTag.Execute)) ||
        node.equals(bNodes.WrInStageID_validResp)) {
    } else if (node.equals(bNodes.WrDeqInstr) && nodeKey.getPurpose().matches(Purpose.MARKER_INTERNALIMPL_PIN)) {
    } else
      return false;

    if (!nodeKey.getISAX().isEmpty() || nodeKey.getAux() != 0)
      return false;

    var outFallbackNodeKey = new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, node, stage, "");
    SCAIEVNode baseNode = node.isAdj() ? bNodes.GetSCAIEVNode(node.nameParentNode) : node;

    boolean coreSupportsNodeInStage =
        Optional.ofNullable(core.GetNodes().get(baseNode)).map(coreNode -> core.StageIsInRange(coreNode, stage)).orElse(false);
    //		boolean coreSupportsDeqInstrInStage = Optional.ofNullable(core.GetNodes().get(bNodes.WrDeqInstr))
    //				.map(coreNode -> core.StageIsInRange(coreNode, stage)).orElse(false);

    // Check if the core already supports this node.
    if (coreSupportsNodeInStage && nodeKey.getPurpose().matches(Purpose.WIREDIN_FALLBACK)) {
      if (!implementedKeys.add(new NodeInstanceDesc.Key(Purpose.WIREDIN, baseNode, stage, "")))
        return false; // Create only one interface builder per key.
      // Request the relevant core interfaces.
      out.accept(new InterfaceRequestBuilder(Purpose.MARKER_FROMCORE_PIN, new NodeInstanceDesc.Key(node, stage, "")));
      //			if (node.equals(bNodes.WrInStageID_validResp) && coreSupportsDeqInstrInStage) {
      //				out.accept(new InterfaceRequestBuilder(Purpose.MARKER_TOCORE_PIN, new
      // NodeInstanceDesc.Key(bNodes.WrDeqInstr, stage, "")));
      //			}
    }
    if (coreSupportsNodeInStage && nodeKey.getPurpose().matches(Purpose.MARKER_INTERNALIMPL_PIN) && node.equals(bNodes.WrDeqInstr)) {
      if (!implementedKeys.add(new NodeInstanceDesc.Key(Purpose.MARKER_TOCORE_PIN, baseNode, stage, "")))
        return false; // Create only one interface builder per key.
      // Request the relevant core interfaces.
      var requestBuilder = new InterfaceRequestBuilder(Purpose.MARKER_TOCORE_PIN, new NodeInstanceDesc.Key(node, stage, ""));
      out.accept(NodeLogicBuilder.fromFunction("DefaultRdwrInStageStrategy_RequestStandard_" + nodeKey.toString(false), (registry, aux) -> {
        var ret = new NodeLogicBlock();
        ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.MARKER_INTERNALIMPL_PIN), "",
                                             ExpressionType.AnyExpression, requestBuilder.requestedFor));
        ret.addOther(requestBuilder.apply(registry, aux));
        return ret;
      }));
    }

    NodeInstanceDesc.Key wrDeqInstrKey = new NodeInstanceDesc.Key(bNodes.WrDeqInstr, stage, "");
    // if (!coreSupportsDeqInstrInStage && implementedKeys.add(wrDeqInstrKey)) {
    if (!coreSupportsNodeInStage && nodeKey.getPurpose().matches(Purpose.MARKER_INTERNALIMPL_PIN) && node.equals(bNodes.WrDeqInstr)) {
      if (!implementedKeys.add(wrDeqInstrKey))
        return true;
      String instrHiddenReg = wrDeqInstrKey.toString(false) + "_r";
      String pendingInstrStallWire = wrDeqInstrKey.toString(false) + "_stallcondition";

      var wrDeqRequestedFor = new RequestedForSet();
      // WrDeqInstr: Assign instrHiddenReg accordingly, and also do WrStall while hiding the instruction.
      out.accept(NodeLogicBuilder.fromFunction("DefaultRdwrInStageStrategy_WrDeqInstr_" + stage.getName(), (registry, aux) -> {
        final String tab = language.tab;
        var ret = new NodeLogicBlock();

        NodeInstanceDesc deqInstrNode = registry.lookupRequired(wrDeqInstrKey);
        wrDeqRequestedFor.addAll(deqInstrNode.getRequestedFor(), true);

        ret.declarations += String.format("reg %s;\n", instrHiddenReg);
        ret.declarations += String.format("logic %s;\n", pendingInstrStallWire);

        // nodeHiddenReg 'set' condition: Current instruction is being removed.
        String deqInstrExpr = deqInstrNode.getExpression();
        // nodeHiddenReg 'clear' condition (semantically before 'set')
        String wrInStageExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrInStageID_valid, stage, ""));
        // Also reset nodeHiddenReg on flush.
        String flushExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, stage, ""));
        var flushWrNode = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrFlush, stage, ""));
        if (flushWrNode.isPresent())
          flushExpr += " || " + flushWrNode.get().getExpression();
        String stallExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, stage, ""));
        var stallWrNode = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrStall, stage, ""));
        if (stallWrNode.isPresent())
          stallExpr += " || " + stallWrNode.get().getExpression();

        ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(wrDeqInstrKey, DefaultRdwrInStageInternalPurpose),
                                             instrHiddenReg, ExpressionType.WireName));

        String instrHiddenRegBody = "";
        instrHiddenRegBody += String.format("if (%s || %s) %s <= 0;\n", language.reset, flushExpr, instrHiddenReg);
        instrHiddenRegBody += String.format("else if (%s) %s <= 1;\n", deqInstrExpr, instrHiddenReg);
        instrHiddenRegBody += String.format("else if (%s && !(%s)) %s <= 0;\n", wrInStageExpr, stallExpr, instrHiddenReg);
        ret.logic += language.CreateInAlways(true, instrHiddenRegBody);

        // Stall the stage while an instruction is being hidden.
        String stallCondBody = "";
        stallCondBody +=
            String.format("%s = %s /*comb. new instruction dequeue*/ || %s && !%s /*still pending sub-pipeline instruction*/;\n",
                          pendingInstrStallWire, deqInstrExpr, instrHiddenReg, wrInStageExpr);
        // Add an assertion to the assumption that a commit override can only be done with a priorly hidden instruction.
        stallCondBody += "`ifndef SYNTHESIS\n";
        stallCondBody += String.format("if (%s && !%s) begin\n", wrInStageExpr, instrHiddenReg);
        stallCondBody +=
            tab +
            String.format(
                "$display(\"ERROR: Overriding retire in stage %s: Cannot perform WrInStageID without a corresponding WrDeqInstr\");\n",
                stage.getName());
        stallCondBody += tab + "$stop;\n";
        stallCondBody += "end\n";
        stallCondBody += "`endif\n";
        ret.logic += language.CreateInAlways(false, stallCondBody);

        ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, stage, "", aux),
                                             pendingInstrStallWire, ExpressionType.WireName, wrDeqRequestedFor));
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.WrStall, stage, "")); // Ensure WrStall creation.

        // Add !<instrHiddenReg> as a subcondition to REGULAR RdInStageValid (if present).
        ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.RdInStageValid, stage, "", aux),
                                             "!" + instrHiddenReg, ExpressionType.AnyExpression, wrDeqRequestedFor));
        return ret;
      }));
      return true;
    }
    if (node.equals(bNodes.RdInStageID) && !coreSupportsNodeInStage && nodeKey.getPurpose().matches(Purpose.WIREDIN_FALLBACK)) {
      if (implementedKeys.add(outFallbackNodeKey)) {
        // Simply assign a fixed 0 ID.
        var requestedFor = new RequestedForSet();
        out.accept(NodeLogicBuilder.fromFunction("DefaultRdwrInStageStrategy_" + nodeKey.toString(), registry -> {
          var ret = new NodeLogicBlock();
          ret.outputs.add(new NodeInstanceDesc(outFallbackNodeKey, "0", ExpressionType.AnyExpression_Noparen, requestedFor));
          return ret;
        }));
      }
      return true;
    }
    if (node.equals(bNodes.WrInStageID_validResp) && !coreSupportsNodeInStage && nodeKey.getPurpose().matches(Purpose.WIREDIN_FALLBACK)) {
      if (implementedKeys.add(outFallbackNodeKey)) {
        // Simply check !RdStall && !WrStall to indicate when the instruction (previously hidden or not) is retired from this stage.
        // Note: This only works for the 'stall hide mode' implemented with WrDeqInstr.
        var requestedFor = new RequestedForSet();
        out.accept(NodeLogicBuilder.fromFunction("DefaultRdwrInStageStrategy_" + nodeKey.toString(), registry -> {
          var ret = new NodeLogicBlock();

          String outWire = outFallbackNodeKey.toString(false) + "_default_s";
          ret.declarations += String.format("wire %s;\n", outWire);
          ret.outputs.add(new NodeInstanceDesc(outFallbackNodeKey, outWire, ExpressionType.WireName, requestedFor));

          String stallExpr = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, stage, ""));
          var stallWrNode = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrStall, stage, ""));
          if (stallWrNode.isPresent())
            stallExpr += " || " + stallWrNode.get().getExpression();

          ret.logic += String.format("assign %s = !(%s);\n", outWire, stallExpr);
          return ret;
        }));
      }
      return true;
    }
    return false;
  }

  private boolean implementSingle_decoupled(Consumer<NodeLogicBuilder> out, NodeInstanceDesc.Key nodeKey) {
    // TODO: Add a default implementation for Decoupled that does not rely on stalling.
    // This is currently a stub, assuming no commit is required from decoupled.

    if (!nodeKey.getPurpose().matches(Purpose.WIREDIN_FALLBACK))
      return false;

    SCAIEVNode node = nodeKey.getNode();
    PipelineStage stage = nodeKey.getStage();
    // Early node check
    if (!(node.equals(bNodes.RdInStageID) || node.equals(bNodes.RdInStageValid) || node.equals(bNodes.WrInStageID_validResp)))
      return false;
    var outNodeKey = new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, node, stage, "");

    if (node.equals(bNodes.RdInStageValid)) {
      if (implementedKeys.add(outNodeKey)) {
        var requestedFor = new RequestedForSet();

        // RdInStageValid: Set if the core does not stall the stage on its own, and override to false if we are hiding the instruction.
        out.accept(NodeLogicBuilder.fromFunction("DefaultRdwrInStageStrategy_stub_" + nodeKey.toString(), registry -> {
          var ret = new NodeLogicBlock();
          String outWire = outNodeKey.toString(false) + "_stub_s";
          ret.declarations += String.format("wire %s;\n", outWire);
          ret.outputs.add(new NodeInstanceDesc(outNodeKey, outWire, ExpressionType.WireName, requestedFor));

          String stallBasepipeCond = registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, nodeKey.getStage(), ""));
          var wrstallBasepipe_opt = registry.lookupOptional(new NodeInstanceDesc.Key(bNodes.WrStall, nodeKey.getStage(), ""));
          if (wrstallBasepipe_opt.isPresent())
            stallBasepipeCond += " || " + wrstallBasepipe_opt.get().getExpression();

          ret.logic += String.format("assign %s = !(%s);\n", outWire, stallBasepipeCond);
          return ret;
        }));
      }
      return true;
    }
    return false;
  }

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    var nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      NodeInstanceDesc.Key nodeKey = nodeKeyIter.next();
      boolean isCoreStage = nodeKey.getStage().getKind() == StageKind.Core || nodeKey.getStage().getKind() == StageKind.CoreInternal;
      if (!nodeKey.getISAX().isEmpty() || nodeKey.getAux() != 0)
        continue;
      if (isCoreStage) {
        if (implementSingle_stallmode(out, nodeKey))
          nodeKeyIter.remove();
      }
      //			else if (nodeKey.getStage().getKind() == StageKind.Decoupled) {
      //				if (implementSingle_decoupled(out, nodeKey))
      //					nodeKeyIter.remove();
      //			}
    }
  }
}
