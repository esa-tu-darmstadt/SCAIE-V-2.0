package scaiev.scal;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Function;

/** A builder repeatedly outputting NodeLogicBlocks */
public abstract class NodeLogicBuilder {
  private static AtomicInteger nextUniqueID = new AtomicInteger(0);

  private int uniqueID;
  /** name of the builder */
  protected String name;
  /**
   * Creates a NodeLogicBuilder with a new unique ID
   * @param name a short, descriptive name for the builder
   */
  public NodeLogicBuilder(String name) {
    this.uniqueID = nextUniqueID.getAndIncrement();
    this.name = name;
  }
  @Override
  public int hashCode() {
    return Integer.hashCode(uniqueID);
  }
  @Override
  public String toString() {
    return String.format("%s(%d)", name.isEmpty() ? "NodeLogicBuilder" : name, uniqueID);
  }

  /**
   * Builder procedure that, given a set of existing nodes through the registry parameter, produces logic that outputs the given and
   * possibly more nodes. Must support being called several times; the list of NodeInstanceDesc outputs should generally remain stable
   * across calls, regardless of existing nodes.
   * @param registry the registry providing the existing nodes and that collects a list of missing dependencies
   * @param aux a unique value to tag accumulated nodes with - e.g. for WrStall, WrFlush
   * @return the logic instance created by the builder, usually providing one or several nodes
   */
  public abstract NodeLogicBlock apply(NodeRegistryRO registry, int aux);

  /**
   * Convenience method that creates a NodeLogicBuilder for a lambda expression
   * @param name name for toString
   * @param fn with args (NodeRegistryRO registry, int aux), implements {@link NodeLogicBuilder#apply(NodeRegistryRO, int)}
   * @return a NodeLogicBuilder for fn
   */
  public static NodeLogicBuilder fromFunction(String name, BiFunction<NodeRegistryRO, Integer, NodeLogicBlock> fn) {
    return new NodeLogicBuilder(name) {
      @Override
      public NodeLogicBlock apply(NodeRegistryRO registry, int aux) {
        return fn.apply(registry, aux);
      }
    };
  }
  /**
   * Convenience method that creates a NodeLogicBuilder for a lambda expression
   * @param name name for toString
   * @param fn with args (NodeRegistryRO registry), implements {@link NodeLogicBuilder#apply(NodeRegistryRO, int)} ignoring the aux
   *     parameter
   * @return a NodeLogicBuilder for fn
   */
  public static NodeLogicBuilder fromFunction(String name, Function<NodeRegistryRO, NodeLogicBlock> fn) {
    return new NodeLogicBuilder(name) {
      @Override
      public NodeLogicBlock apply(NodeRegistryRO registry, int aux) {
        return fn.apply(registry);
      }
    };
  }

  /**
   * Convenience method that creates a NodeLogicBuilder that returns empty NodeLogicBlocks.
   * @return a NodeLogicBuilder
   */
  public static NodeLogicBuilder makeEmpty() { return fromFunction("(empty builder)", registry -> new NodeLogicBlock()); }
}
