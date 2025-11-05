package scaiev.scal.strategy.standard;

import java.util.Optional;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.coreconstr.CoreNode;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.InterfaceRequestBuilder;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.util.Verilog;

/** Strategy that assigns a per-ISAX read node from the common node expression. */
public class DirectReadNodeStrategy extends SingleNodeStrategy {
  Verilog language;
  BNode bNodes;
  Core core;
  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core node description
   */
  public DirectReadNodeStrategy(Verilog language, BNode bNodes, Core core) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
  }

  private boolean supports(Key nodeKey) {
    SCAIEVNode node = nodeKey.getNode();
    PipelineStage stage = nodeKey.getStage();
    CoreNode coreNode = this.core.GetNodes().get(node);
    if (coreNode == null)
      return false;
    if (stage.getKind() != StageKind.Core && stage.getKind() != StageKind.CoreInternal && stage.getKind() != StageKind.Root)
      return false;
    if (!nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.REGULAR))
      return false;
    if (nodeKey.getAux() != 0)
      return false;
    if (node.isInput /* isInput: Is input to Core */
        || node.isSpawn() ||
        node.equals(bNodes.RdIValid) /* Handled separately (check not needed assuming the RdIValid strategy is always called first) */)
      return false;
    return (core.TranslateStageScheduleNumber(coreNode.GetEarliest()).isAroundOrBefore(stage, false)) &&
        (stage.getKind() == StageKind.Root || core.TranslateStageScheduleNumber(coreNode.GetExpensive()).isAfter(stage, false)) &&
        core.TranslateStageScheduleNumber(coreNode.GetLatest()).isAroundOrAfter(stage, false);
  }

  @Override
  public Optional<NodeLogicBuilder> implement(NodeInstanceDesc.Key nodeKey) {
    if (supports(nodeKey)) {
      NodeInstanceDesc.Key nodeKeyGenericPurpose = new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
      NodeInstanceDesc.Key nodeKeyGeneral = new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), "");

      var interfaceRequestBuilder = new InterfaceRequestBuilder(Purpose.MARKER_FROMCORE_PIN, nodeKeyGeneral);
      if (!nodeKey.getISAX().isEmpty())
        interfaceRequestBuilder.requestedFor.addRelevantISAX(nodeKey.getISAX());

      // Allocate the RequestedForSet outside the builder to avoid having stale back-references from the chosen node.
      var nodeOutputRequestedFor = new RequestedForSet(nodeKey.getISAX());

      return Optional.of(
          NodeLogicBuilder.fromFunction("DirectReadNodeStrategy (" + nodeKey.toString() + ")", (NodeRegistryRO registry, Integer aux) -> {
            NodeLogicBlock ret = new NodeLogicBlock();
            Optional<NodeInstanceDesc> nodeOpt = registry.lookupOptional(nodeKey);
            if (nodeOpt.isEmpty()) {
              // If this specific key does not exist already, check if more generic keys (by Purpose, ISAX) do match.
              nodeOpt = registry.lookupOptional(nodeKeyGenericPurpose).or(() -> registry.lookupOptional(nodeKeyGeneral));
              if (nodeOpt.isPresent()) {
                // If we do have a match now,
                //  assign it to the specific requested node
                //  and add cross-references in the 'requested for' sets.
                var newOutput = new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR),
                                                     nodeOpt.get().getExpression(), ExpressionType.AnyExpression, nodeOutputRequestedFor);
                ret.outputs.add(newOutput);

                nodeOutputRequestedFor.addAll(nodeOpt.get().getRequestedFor(), true);
                nodeOpt.get().addRequestedFor(newOutput.getRequestedFor(), true);
              }
            }
            // Require the core->SCAL interface only if the node does not exist already.
            interfaceRequestBuilder.applyOptional(registry, aux, nodeOpt.isPresent());

            ret.treatAsNotEmpty = true;
            return ret;
          }));
    }
    return Optional.empty();
  }
}
