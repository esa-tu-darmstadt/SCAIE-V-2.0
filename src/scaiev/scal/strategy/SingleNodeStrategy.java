package scaiev.scal.strategy;

import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;

public abstract class SingleNodeStrategy extends MultiNodeStrategy {
  /**
   * Tries to create a builder for a particular node.
   * Note: If the strategy does return a builder, the builder should immediately output something nodeKey matches to.
   *  Otherwise, the same strategy may get called again.
   *  If that is not possible, use a MultiNodeStrategy and remove already-handled keys from nodeKeys.
   * @param nodeKey the node to possibly implement
   * @return A logic block builder in Optional, or empty if the strategy is not able to implement it.
   */
  public abstract Optional<NodeLogicBuilder> implement(NodeInstanceDesc.Key nodeKey);

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<Key> nodeKeys, boolean isLast) {
    var nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      var nodeKey = nodeKeyIter.next();
      var opt_nodeLogicBuilder = implement(nodeKey);
      if (opt_nodeLogicBuilder.isPresent()) {
        out.accept(opt_nodeLogicBuilder.get());
        nodeKeyIter.remove();
      }
    }
  }

  static SingleNodeStrategy fromFunctions(String name, Predicate<NodeInstanceDesc.Key> supported,
                                          BiFunction<NodeRegistryRO, NodeInstanceDesc.Key, NodeLogicBlock> fn) {
    return new SingleNodeStrategy() {
      @Override
      public Optional<NodeLogicBuilder> implement(NodeInstanceDesc.Key nodeKey) {
        if (supported.test(nodeKey)) {
          NodeLogicBuilder builder = new NodeLogicBuilder(name + " (" + nodeKey.toString() + ")") {
            @Override
            public NodeLogicBlock apply(NodeRegistryRO registry, int aux) {
              return fn.apply(registry, nodeKey);
            }
          };
          return Optional.of(builder); // fn.apply(registry, nodeKey);
        }
        return Optional.empty();
      }
    };
  }
  static SingleNodeStrategy fromFunctions(String name, Predicate<NodeInstanceDesc.Key> supported,
                                          Function<NodeRegistryRO, NodeLogicBlock> fn) {
    return fromFunctions(name, supported, (NodeRegistryRO registry, NodeInstanceDesc.Key nodeKey) -> { return fn.apply(registry); });
  }

  /** An empty strategy that always returns an empty Optional. */
  public static SingleNodeStrategy noneStrategy = new SingleNodeStrategy() {
    @Override
    public Optional<NodeLogicBuilder> implement(Key nodeKey) {
      return Optional.empty();
    }
  };
}
