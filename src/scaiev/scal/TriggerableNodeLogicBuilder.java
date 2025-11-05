package scaiev.scal;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;

/**
 * A NodeLogicBuilder that calls and adds a dependency on a special trigger node.
 * This allows a strategy to trigger a new invocation after changing some property of an existing NodeLogicBuilder.
 * Allows an arbitrary number of triggers, but should be used sparingly for performance reasons.
 *
 * Subclasses should override applyTriggered rather than apply.
 **/
public abstract class TriggerableNodeLogicBuilder extends NodeLogicBuilder {

  // protected NodeLogicBuilder inner;
  /** The trigger key to listen for (unspecified aux) */
  protected NodeInstanceDesc.Key triggerKey;
  /** True iff the trigger will already be updated in the next build iteration */
  protected boolean triggerPending = true; // The builder will be triggered once from registering the builder.

  /**
   * @param name the name for the builder
   * @param nodeKey the key to use for triggering; its Purpose value will be replaced with a new one; ISAX and aux will be ignored for
   *     implementation reasons.
   */
  public TriggerableNodeLogicBuilder(String name, NodeInstanceDesc.Key nodeKey) {
    super(name);
    var triggerPurpose = new Purpose("Trigger-" + name, true, Optional.empty(), List.of());
    this.triggerKey = new NodeInstanceDesc.Key(triggerPurpose, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX(), nodeKey.getAux());
  }

  /**
   * Builds a TriggerableNodeLogicBuilder that wraps an existing NodeLogicBuilder.
   * @param inner the inner NodeLogicBuilder
   * @param nodeKey the key to use for triggering; its Purpose value will be replaced with a new one; ISAX and aux will be ignored for
   *     implementation reasons.
   * @return a new TriggerableNodeLogicBuilder
   */
  public static TriggerableNodeLogicBuilder makeWrapper(NodeLogicBuilder inner, NodeInstanceDesc.Key nodeKey) {
    return new TriggerableNodeLogicBuilder("Triggerable-" + inner.name, nodeKey) {
      @Override
      public NodeLogicBlock applyTriggered(NodeRegistryRO registry, int aux) {
        return inner.apply(registry, aux);
      }
    };
  }

  /** A trigger output emitter for use in a NodeLogicBuilder */
  public class RepeatingTrigger {
    int lastVal = 0;
    boolean didInitialTrigger = false;
    /** constructor */
    RepeatingTrigger() {}
    /**
     * Function that should be called for each invocation of the calling builder.
     * Note that repeated invocations with refresh==false will not refresh the triggered builder, as the output does not change.
     * Similarly, not calling the trigger method in builder invocation N+1 after having called it in invocation N also forces a refresh.
     * @param aux the aux value for the trigger key, usually the aux value passed to the calling builder. The same value should be used for
     *     all invocations.
     * @param refresh whether the trigger value should be changed from the previous invocation;
     *        if true, this will cause reinvocation of the triggerable builder even if the previous trigger was processed already
     * @return a NodeLogicBlock with the trigger output to integrate in the result of the caller
     */
    public NodeLogicBlock trigger(int aux, boolean refresh) {
      if (triggerPending)
        refresh = false; // no need
      triggerPending = triggerPending || refresh || !didInitialTrigger;
      if (refresh)
        ++lastVal;
      var ret = new NodeLogicBlock();
      var curTriggerKey =
          new NodeInstanceDesc.Key(triggerKey.getPurpose(), triggerKey.getNode(), triggerKey.getStage(), triggerKey.getISAX(), aux);
      ret.outputs.add(new NodeInstanceDesc(curTriggerKey, Integer.toString(lastVal), ExpressionType.AnyExpression));
      didInitialTrigger = true;
      return ret;
    }
  }
  /** Constructs a new RepeatingTrigger to use from within a NodeLogicBuilder. */
  public RepeatingTrigger makeRepeatingTrigger() { return new RepeatingTrigger(); }

  /**
   * Activates the trigger if not active already.
   * If out is non-null, adds a trigger builder if necessary.
   * @param out the Consumer that adds a NodeLogicBuilder to the composer.
   *            Should only be set to null if the caller is sure that this SwitchableNodeLogicBuilder is about to be invoked regardless.
   */
  public void trigger(Consumer<NodeLogicBuilder> out) {
    if (triggerPending)
      return;
    triggerPending = true;
    if (out != null) {
      RepeatingTrigger trigger = new RepeatingTrigger();
      out.accept(NodeLogicBuilder.fromFunction("Trigger:" + this.name, (registry, aux) -> { return trigger.trigger(aux, false); }));
    }
  }

  /**
   * Determines if the NodeLogicBuilder should continue to listen for triggers.
   * The default behavior in {@link TriggerableNodeLogicBuilder} is to always be triggerable.
   * @return true iff triggers should be handled
   */
  protected boolean isTriggerable() { return true; }

  /**
   * The inner equivalent of {@link NodeLogicBuilder#apply(NodeRegistryRO, int)} for {@link TriggerableNodeLogicBuilder}.
   * @param registry the registry providing the existing nodes and that collects a list of missing dependencies
   * @param aux a unique value to tag accumulated nodes with
   * @return the logic instance created by the builder, usually providing one or several nodes
   */
  protected abstract NodeLogicBlock applyTriggered(NodeRegistryRO registry, int aux);

  @Override
  public NodeLogicBlock apply(NodeRegistryRO registry, int aux) {
    if (isTriggerable()) {
      triggerPending = false;
      registry.lookupAll(triggerKey, false);
    }
    return applyTriggered(registry, aux);
  }

  /**
   * Convenience method that creates a TriggerableNodeLogicBuilder for a lambda expression
   * @param name name for toString
   * @param triggerNodeKey the key to use for triggering; its Purpose value will be replaced with a new one; ISAX and aux will be ignored
   *     for implementation reasons
   * @param fn with args (NodeRegistryRO registry, int aux), implements {@link TriggerableNodeLogicBuilder#applyTriggered(NodeRegistryRO,
   *     int)}
   * @return a TriggerableNodeLogicBuilder for fn
   */
  public static TriggerableNodeLogicBuilder fromFunction(String name, NodeInstanceDesc.Key triggerNodeKey,
                                                         BiFunction<NodeRegistryRO, Integer, NodeLogicBlock> fn) {
    return new TriggerableNodeLogicBuilder(name, triggerNodeKey) {
      @Override
      public NodeLogicBlock applyTriggered(NodeRegistryRO registry, int aux) {
        return fn.apply(registry, aux);
      }
    };
  }
  /**
   * Convenience method that creates a TriggerableNodeLogicBuilder for a lambda expression
   * @param name name for toString
   * @param triggerNodeKey the key to use for triggering; its Purpose value will be replaced with a new one; ISAX and aux will be ignored
   *     for implementation reasons
   * @param fn with args (NodeRegistryRO registry), implements {@link TriggerableNodeLogicBuilder#applyTriggered(NodeRegistryRO, int)}
   *     ignoring the aux parameter
   * @return a TriggerableNodeLogicBuilder for fn
   */
  public static TriggerableNodeLogicBuilder fromFunction(String name, NodeInstanceDesc.Key triggerNodeKey,
                                                         Function<NodeRegistryRO, NodeLogicBlock> fn) {
    return new TriggerableNodeLogicBuilder(name, triggerNodeKey) {
      @Override
      public NodeLogicBlock applyTriggered(NodeRegistryRO registry, int aux) {
        return fn.apply(registry);
      }
    };
  }
}
