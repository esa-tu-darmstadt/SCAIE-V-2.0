package scaiev.scal;

import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * A NodeLogicBuilder wrapper that activates the inner builder only after a call to the trigger method, using a single-shot trigger.
 * A typical use case is when a strategy creates a single combined NodeLogicBuilder covering several node variants differing in Purpose.
 *  If a variant is not already encountered in the first strategy invocation,
 *  this class can be used to implement it as a sleeper that can be activated at a later point.
 *
 * Subclasses should override applySwitched rather than apply.
 **/
public abstract class SwitchableNodeLogicBuilder extends TriggerableNodeLogicBuilder {

  /** True iff the builder has been switched on */
  protected boolean active = false;

  /**
   * @param name the name of the builder
   * @param nodeKey the key to use for triggering; its Purpose value will be replaced with a new one; ISAX and aux will be ignored.
   */
  public SwitchableNodeLogicBuilder(String name, NodeInstanceDesc.Key nodeKey) { super(name, nodeKey); }

  /**
   * Builds a SwitchableNodeLogicBuilder that wraps an existing NodeLogicBuilder.
   * @param inner the inner NodeLogicBuilder
   * @param nodeKey the key to use for triggering; its Purpose value will be replaced with a new one; ISAX and aux will be ignored for
   *     implementation reasons.
   */
  public static SwitchableNodeLogicBuilder makeWrapper(NodeLogicBuilder inner, NodeInstanceDesc.Key nodeKey) {
    return new SwitchableNodeLogicBuilder("Switchable-" + inner.name, nodeKey) {
      @Override
      public NodeLogicBlock applySwitched(NodeRegistryRO registry, int aux) {
        return inner.apply(registry, aux);
      }
    };
  }

  /**
   * Activates the trigger if not active already.
   * If out is non-null, adds a trigger builder if necessary.
   * @param out the Consumer that adds a NodeLogicBuilder to the composer.
   *            Should not be set to null, unless the caller is sure that this SwitchableNodeLogicBuilder is about to be invoked regardless.
   */
  @Override
  public void trigger(Consumer<NodeLogicBuilder> out) {
    if (active)
      return;
    super.trigger(out);
    active = true;
  }

  @Override
  protected boolean isTriggerable() {
    return !active;
  }

  /**
   * The inner equivalent of {@link NodeLogicBuilder#apply(NodeRegistryRO, int)} for {@link SwitchableNodeLogicBuilder}.
   * Will only be called once the builder has been triggered.
   * @param registry the registry providing the existing nodes and that collects a list of missing dependencies
   * @param aux a unique value to tag accumulated nodes with
   * @return the logic instance created by the builder, usually providing one or several nodes
   */
  protected abstract NodeLogicBlock applySwitched(NodeRegistryRO registry, int aux);

  @Override
  protected NodeLogicBlock applyTriggered(NodeRegistryRO registry, int aux) {
    if (active)
      return applySwitched(registry, aux);
    return new NodeLogicBlock();
  }

  /**
   * Convenience method that creates a SwitchableNodeLogicBuilder for a lambda expression
   * @param name name for toString
   * @param triggerNodeKey the key to use for triggering; its Purpose value will be replaced with a new one; ISAX and aux will be ignored
   *     for implementation reasons
   * @param fn with args (NodeRegistryRO registry, int aux), implements {@link SwitchableNodeLogicBuilder#applySwitched(NodeRegistryRO,
   *     int)}
   * @return a SwitchableNodeLogicBuilder for fn
   */
  public static SwitchableNodeLogicBuilder fromFunction(String name, NodeInstanceDesc.Key triggerNodeKey,
                                                        BiFunction<NodeRegistryRO, Integer, NodeLogicBlock> fn) {
    return new SwitchableNodeLogicBuilder(name, triggerNodeKey) {
      @Override
      public NodeLogicBlock applySwitched(NodeRegistryRO registry, int aux) {
        return fn.apply(registry, aux);
      }
    };
  }
  /**
   * Convenience method that creates a SwitchableNodeLogicBuilder for a lambda expression
   * @param name name for toString
   * @param triggerNodeKey the key to use for triggering; its Purpose value will be replaced with a new one; ISAX and aux will be ignored
   *     for implementation reasons
   * @param fn with args (NodeRegistryRO registry), implements {@link SwitchableNodeLogicBuilder#applySwitched(NodeRegistryRO, int)}
   *     ignoring the aux parameter
   * @return a SwitchableNodeLogicBuilder for fn
   */
  public static SwitchableNodeLogicBuilder fromFunction(String name, NodeInstanceDesc.Key triggerNodeKey,
                                                        Function<NodeRegistryRO, NodeLogicBlock> fn) {
    return new SwitchableNodeLogicBuilder(name, triggerNodeKey) {
      @Override
      public NodeLogicBlock applySwitched(NodeRegistryRO registry, int aux) {
        return fn.apply(registry);
      }
    };
  }
}
