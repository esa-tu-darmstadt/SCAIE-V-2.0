package scaiev.scal.strategy.standard;

import java.util.function.Consumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.util.Verilog;

/**
 * Provides a default for ID adjacent nodes
 */
public class DefaultIDAdjStrategy extends MultiNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  StrategyBuilders strategyBuilders;
  Verilog language;
  BNode bNodes;
  Core core;

  /**
   * @param strategyBuilders The StrategyBuilders object to build sub-strategies with
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core node description
   */
  public DefaultIDAdjStrategy(StrategyBuilders strategyBuilders, Verilog language, BNode bNodes, Core core) {
    this.strategyBuilders = strategyBuilders;
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
  }

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    // Assign id from RdIssueID.
    var nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      NodeInstanceDesc.Key nodeKey = nodeKeyIter.next();
      if (nodeKey.getPurpose().matches(Purpose.WIREDIN_FALLBACK) && nodeKey.getNode().getAdj() == AdjacentNode.instrID) {
        var requestedFor = new RequestedForSet(nodeKey.getISAX());
        out.accept(NodeLogicBuilder.fromFunction("DefaultIDAdjStrategy_" + nodeKey.toString(), registry -> {
          var ret = new NodeLogicBlock();
          ret.outputs.add(new NodeInstanceDesc(
              new NodeInstanceDesc.Key(Purpose.WIREDIN_FALLBACK, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()),
              registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIssueID, nodeKey.getStage(), ""), requestedFor),
              ExpressionType.AnyExpression, requestedFor));
          return ret;
        }));
        nodeKeyIter.remove();
      }
    }
  }
}
