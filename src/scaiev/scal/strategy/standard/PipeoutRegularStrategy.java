package scaiev.scal.strategy.standard;

import java.util.Optional;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.strategy.SingleNodeStrategy;

/** Strategy that outputs PIPEOUT from REGULAR or PIPEDIN node instances. */
public class PipeoutRegularStrategy extends SingleNodeStrategy {

  public PipeoutRegularStrategy() {}

  @Override
  public Optional<NodeLogicBuilder> implement(Key nodeKey) {
    if (nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.PIPEOUT)) {
      var requestedFor = new RequestedForSet();
      return Optional.of(
          NodeLogicBuilder.fromFunction("PipeoutRegularStrategy (" + nodeKey.toString(false) + ")", (NodeRegistryRO registry) -> {
            var fromKey = new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN, nodeKey.getNode(),
                                                   nodeKey.getStage(), nodeKey.getISAX(), nodeKey.getAux());
            var fromKeyNodeInst = registry.lookupRequired(fromKey, requestedFor);

            NodeLogicBlock ret = new NodeLogicBlock();
            ret.outputs.add(new NodeInstanceDesc(nodeKey, fromKeyNodeInst.getExpression(), ExpressionType.AnyExpression, requestedFor));

            return ret;
          }));
    }
    return Optional.empty();
  }
}
