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

	//protected NodeLogicBuilder inner;
	protected NodeInstanceDesc.Key triggerKey;
	protected boolean triggerPending = true; //The builder will be triggered once from registering the builder.

	/**
	 * @param inner the inner NodeLogicBuilder
	 * @param nodeKey the key to use for triggering; its Purpose value will be replaced with a new one; ISAX and aux will be ignored for implementation reasons.
	 */
	public TriggerableNodeLogicBuilder(String name, NodeInstanceDesc.Key nodeKey) {
		super(name);
		var triggerPurpose = new Purpose("Trigger-"+name, true, Optional.empty(), List.of());
		this.triggerKey = new NodeInstanceDesc.Key(triggerPurpose, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX(), nodeKey.getAux());
	}

	/**
	 * Builds a TriggerableNodeLogicBuilder that wraps an existing NodeLogicBuilder.
	 * @param inner the inner NodeLogicBuilder
	 * @param nodeKey the key to use for triggering; its Purpose value will be replaced with a new one; ISAX and aux will be ignored for implementation reasons.
	 */
	public static TriggerableNodeLogicBuilder makeWrapper(NodeLogicBuilder inner, NodeInstanceDesc.Key nodeKey) {
		return new TriggerableNodeLogicBuilder("Triggerable-"+inner.name, nodeKey) {
			@Override
			public NodeLogicBlock applyTriggered(NodeRegistryRO registry, int aux) {
				return inner.apply(registry, aux);
			}
		};
	}
	
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
			out.accept(NodeLogicBuilder.fromFunction("Trigger:"+this.name, (registry, aux) -> {
				var ret = new NodeLogicBlock();
				var curTriggerKey = new NodeInstanceDesc.Key(triggerKey.getPurpose(), triggerKey.getNode(), triggerKey.getStage(), triggerKey.getISAX(), aux);
				ret.outputs.add(new NodeInstanceDesc(curTriggerKey, "1", ExpressionType.AnyExpression));
				return ret;
			}));
		}
	}
	
	/**
	 * Determines if the NodeLogicBuilder should continue to listen for triggers.
	 * The default behavior in {@link TriggerableNodeLogicBuilder} is to always be triggerable. 
	 */
	protected boolean isTriggerable() { return true; }
	
	/**
	 * The inner equivalent of {@link NodeLogicBuilder#apply(NodeRegistryRO, int)} for {@link TriggerableNodeLogicBuilder}.
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
	 * @param triggerNodeKey the key to use for triggering; its Purpose value will be replaced with a new one; ISAX and aux will be ignored for implementation reasons
	 * @param fn with args (NodeRegistryRO registry, int aux), implements {@link TriggerableNodeLogicBuilder#applyTriggered(NodeRegistryRO, int)}
	 * @return 
	 */
	public static TriggerableNodeLogicBuilder fromFunction(String name, NodeInstanceDesc.Key triggerNodeKey, BiFunction<NodeRegistryRO, Integer, NodeLogicBlock> fn) {
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
	 * @param triggerNodeKey the key to use for triggering; its Purpose value will be replaced with a new one; ISAX and aux will be ignored for implementation reasons
	 * @param fn with args (NodeRegistryRO registry), implements {@link TriggerableNodeLogicBuilder#applyTriggered(NodeRegistryRO, int)} ignoring the aux parameter
	 * @return 
	 */
	public static TriggerableNodeLogicBuilder fromFunction(String name, NodeInstanceDesc.Key triggerNodeKey, Function<NodeRegistryRO, NodeLogicBlock> fn) {
		return new TriggerableNodeLogicBuilder(name, triggerNodeKey) {
			@Override
			public NodeLogicBlock applyTriggered(NodeRegistryRO registry, int aux) {
				return fn.apply(registry);
			}
		};
	}
}
