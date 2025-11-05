package scaiev.scal;

import java.util.Collection;

/** NodeLogicBuilder that runs several inner builders. The inner builders will not see each other's outputs in the registry. */
public class CombinedNodeLogicBuilder extends NodeLogicBuilder {

  NodeLogicBuilder[] inner;

  /**
   * @param name the name to give to the combined builder
   * @param inner the inner builders
   */
  public CombinedNodeLogicBuilder(String name, NodeLogicBuilder... inner) {
    super(name + (inner.length == 0 ? "(empty)" : ""));
    this.inner = inner;
  }

  /**
   * Convenience function that creates either a CombinedLogicBuilder or,
   * if only one inner builder is given, directly returns the inner builder.
   * @param name the name of the combined builder; will not be applied if inner.length==1.
   * @param inner the inner builders to combine
   * @return a NodeLogicBuilder
   */
  public static NodeLogicBuilder of(String name, NodeLogicBuilder... inner) {
    if (inner.length == 1)
      return inner[0];
    return new CombinedNodeLogicBuilder(name, inner);
  }

  /**
   * Convenience function that creates either a CombinedLogicBuilder or,
   * if only one inner builder is given, directly returns the inner builder.
   * @param name the name of the combined builder; will not be applied if inner.length==1.
   * @param inner the inner builders to combine
   * @return a NodeLogicBuilder
   */
  public static NodeLogicBuilder of(String name, Collection<NodeLogicBuilder> inner) {
    if (inner.size() == 1)
      return inner.iterator().next();
    return new CombinedNodeLogicBuilder(name, inner.toArray(new NodeLogicBuilder[inner.size()]));
  }

  @Override
  public NodeLogicBlock apply(NodeRegistryRO registry, int aux) {
    if (inner.length == 0)
      return new NodeLogicBlock();
    if (inner.length == 1)
      return inner[0].apply(registry, aux);
    NodeLogicBlock ret = new NodeLogicBlock();
    for (NodeLogicBuilder baseBuilder : inner)
      ret.addOther(baseBuilder.apply(registry, aux));
    return ret;
  }
}
