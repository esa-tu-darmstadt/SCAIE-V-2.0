package scaiev.scal.strategy.standard;

import java.util.Optional;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.InterfaceRequestBuilder;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.util.Verilog;

/** Strategy that combines all nodes tagged with a non-empty ISAX / non-zero aux value to global WrStall and WrFlush nodes. */
public class StallFlushDeqStrategy extends SingleNodeStrategy {
  Verilog language;
  BNode bNodes;
  Core core;
  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   */
  public StallFlushDeqStrategy(Verilog language, BNode bNodes, Core core) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
  }

  @Override
  public Optional<NodeLogicBuilder> implement(NodeInstanceDesc.Key nodeKey) {
    boolean opNeedsCoreSupport = !nodeKey.getNode().equals(bNodes.WrDeqInstr) && !nodeKey.getNode().equals(bNodes.WrRerunNext);
    if ((nodeKey.getNode().equals(bNodes.WrStall) || nodeKey.getNode().equals(bNodes.WrFlush) ||
         nodeKey.getNode().equals(bNodes.WrDeqInstr) || nodeKey.getNode().equals(bNodes.WrRerunNext)) &&
        nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.REGULAR) && nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0 &&
        (!opNeedsCoreSupport || nodeKey.getStage().getKind() == StageKind.Decoupled ||
         (core.GetNodes().containsKey(nodeKey.getNode()) &&
          core.TranslateStageScheduleNumber(core.GetNodes().get(nodeKey.getNode()).GetEarliest())
              .isAroundOrBefore(nodeKey.getStage(), false)))) {
      InterfaceRequestBuilder interfBuilder = new InterfaceRequestBuilder(
          opNeedsCoreSupport ? NodeInstanceDesc.Purpose.MARKER_TOCORE_PIN : NodeInstanceDesc.Purpose.MARKER_INTERNALIMPL_PIN, nodeKey);
      return Optional.of(
          NodeLogicBuilder.fromFunction("StallFlushDeqStrategy (" + nodeKey.toString() + ")", (NodeRegistryRO registry, Integer aux) -> {
            NodeLogicBlock ret = new NodeLogicBlock();

            String nodeWire = language.CreateLocalNodeName(nodeKey.getNode(), nodeKey.getStage(), "");
            ret.declarations += "wire " + nodeWire + ";\n";

            String newValue = "";
            // Find all sub-nodes (i.e. from some ISAX, or some internal node with aux != 0)
            // Note: A dedicated purpose (say, OR_COMBINE_REGULAR) could be appropriate here,
            //  but since WrStall will be set via SCALInputOutputStrategy,
            //  it would require additional special-case handling
            //  to generate these OR_COMBINE_REGULAR nodes for WrStall,WrFlush only.
            for (NodeInstanceDesc subNode :
                 registry.lookupAll(new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), ""), false)) {
              // The sub-node should have some unique identifier.
              assert (subNode.getKey().getISAX().length() > 0 || subNode.getKey().getAux() != 0);
              newValue += (newValue.isEmpty() ? "" : " || ") + subNode.getExpressionWithParens();
              interfBuilder.requestedFor.addAll(subNode.getRequestedFor(), true);
            }
            if (newValue.isEmpty())
              newValue = "1'b0";
            else if (!opNeedsCoreSupport ||
                     (nodeKey.getStage().getKind() == StageKind.Core || nodeKey.getStage().getKind() == StageKind.CoreInternal)) {
              // Explicitly request instantiation of the SCAL->core output pin
              ret.addOther(interfBuilder.apply(registry, aux));
            }

            ret.logic += "assign " + nodeWire + " = " + newValue + ";";
            var output =
                new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR), nodeWire, ExpressionType.WireName);
            output.addRequestedFor(interfBuilder.requestedFor, false);
            ret.outputs.add(output);

            return ret;
          }));
    }
    if (nodeKey.getNode().equals(bNodes.RdStallLegacy) && nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.REGULAR) &&
        nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0 &&
        core.TranslateStageScheduleNumber(core.GetNodes().get(nodeKey.getNode()).GetEarliest())
            .isAroundOrBefore(nodeKey.getStage(), false)) {

      // Special handling for deprecated global RdStall.

      NodeInstanceDesc.Key generalKey =
          new NodeInstanceDesc.Key(bNodes.RdStall, nodeKey.getStage(), ""); // RdStall (e.g. from core) without any added conditions
      RequestedForSet requestedFor = new RequestedForSet(nodeKey.getISAX());

      return Optional.of(
          NodeLogicBuilder.fromFunction("StallFlushDeqStrategy (" + nodeKey.toString() + ")", (NodeRegistryRO registry, Integer aux) -> {
            NodeLogicBlock ret = new NodeLogicBlock();
            String nodeWire = language.CreateLocalNodeName(nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
            ret.declarations += "wire " + nodeWire + ";\n";
            String newValue = "";
            // Find all write sub-nodes that do not directly belong to any ISAX.
            for (NodeInstanceDesc subNode : registry.lookupAll(new NodeInstanceDesc.Key(bNodes.WrStall, nodeKey.getStage(), ""), false)) {
              if (subNode.getKey().getISAX().isEmpty() && subNode.getKey().getAux() == 0)
                continue; // Ignore main WrStall/WrFlush node
              if (subNode.getKey().getISAX().isEmpty())
                newValue += (newValue.isEmpty() ? "" : " || ") + subNode.getExpressionWithParens();
            }
            newValue += (newValue.isEmpty() ? "" : " || ") + registry.lookupRequired(generalKey, requestedFor).getExpressionWithParens();
            ret.logic += "assign " + nodeWire + " = " + newValue + ";";
            ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR), nodeWire,
                                                 ExpressionType.WireName, requestedFor));
            return ret;
          }));
    }
    if ((nodeKey.getNode().equals(bNodes.RdStall) || nodeKey.getNode().equals(bNodes.RdFlush)) &&
        nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.REGULAR) && !nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0 &&
        core.TranslateStageScheduleNumber(core.GetNodes().get(nodeKey.getNode()).GetEarliest())
            .isAroundOrBefore(nodeKey.getStage(), false)) {
      // Construct per-ISAX RdStall/RdFlush that includes all WrStalls/WrFlushes from other ISAXes or from SCAL internally
      NodeInstanceDesc.Key generalKey =
          new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), ""); // RdStall (e.g. from core) without any added conditions
      SCAIEVNode wrNode = bNodes.GetSCAIEVNode(bNodes.GetNameWrNode(nodeKey.getNode()));

      InterfaceRequestBuilder interfBuilder = new InterfaceRequestBuilder(NodeInstanceDesc.Purpose.MARKER_FROMCORE_PIN, generalKey);
      interfBuilder.requestedFor.addRelevantISAX(nodeKey.getISAX());
      RequestedForSet requestedFor = new RequestedForSet(nodeKey.getISAX());

      return Optional.of(
          NodeLogicBuilder.fromFunction("StallFlushDeqStrategy (" + nodeKey.toString() + ")", (NodeRegistryRO registry, Integer aux) -> {
            NodeLogicBlock ret = new NodeLogicBlock();

            String nodeWire = language.CreateLocalNodeName(nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
            ret.declarations += "wire " + nodeWire + ";\n";

            String newValue = "";
            if (nodeKey.getStage().getKind() == StageKind.Sub && nodeKey.getNode().equals(bNodes.RdStall)) {
              // Sub-stages currently do not fully implement RdStall. Add RdIValid to force RdStall while no instruction is present.
              newValue +=
                  (newValue.isEmpty() ? "" : " || ") + "!" +
                  registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, nodeKey.getStage(), nodeKey.getISAX()));
            }
            // Find all write sub-nodes that do not belong to this ISAX.
            for (NodeInstanceDesc subNode : registry.lookupAll(new NodeInstanceDesc.Key(wrNode, nodeKey.getStage(), ""), false)) {
              if (subNode.getKey().getISAX().isEmpty() && subNode.getKey().getAux() == 0)
                continue; // Ignore main WrStall/WrFlush node
              if (!subNode.getKey().getISAX().equals(nodeKey.getISAX()))
                newValue += (newValue.isEmpty() ? "" : " || ") + subNode.getExpressionWithParens();
            }
            if (nodeKey.getStage().getKind() == StageKind.Core || nodeKey.getStage().getKind() == StageKind.CoreInternal) {
              // Explicitly request instantiation of the core->SCAL input pin
              ret.addOther(interfBuilder.apply(registry, aux));
            }
            newValue += (newValue.isEmpty() ? "" : " || ") +
                        registry.lookupRequired(generalKey, interfBuilder.requestedFor).getExpressionWithParens();

            ret.logic += "assign " + nodeWire + " = " + newValue + ";";
            ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR), nodeWire,
                                                 ExpressionType.WireName, requestedFor));

            return ret;
          }));
    }
    return Optional.empty();
  }
}
