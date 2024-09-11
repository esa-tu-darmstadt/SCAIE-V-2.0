package scaiev.scal.strategy;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.function.Consumer;
import java.util.function.Predicate;

import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBuilder;
import scaiev.util.ListRemoveView;
import scaiev.util.Verilog;

public abstract class MultiNodeStrategy {
	/**
	 * Tries to create builders for the given nodes.
	 * @param out A consumer that accepts the generated logic builders
	 * @param nodeKeys The nodes to possibly implement.
	 *                 For convenience when calling further MultiNodeStrategies,
	 *                 use of {@link java.util.Iterator#remove()} is generally allowed.
	 * @param isLast Set during the last chance call if there are remaining unresolved nodes.
	 *               For instance, if SCAL needs to add an input node from the core,
	 *               and the root strategy is designed to instantiate those only if really necessary,
	 *               isLast can be used as a trigger.
	 */
	public abstract void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast);
	
	/**
	 * Set the language object
	 * @param lang The verilog language object to use
	 */
	public void setLanguage(Verilog lang) {}
	
	/**
	 * Converts singleStrategy into a MultiNodeStrategy.
	 * If singleStrategy returns a NodeLogicBuilder, the key will be removed from nodeKeys.
	 */
	public static MultiNodeStrategy from(SingleNodeStrategy singleStrategy) {
		return new MultiNodeStrategy() {
			@Override
			public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
				var iter = nodeKeys.iterator();
				while (iter.hasNext()) {
					var builder_opt = singleStrategy.implement(iter.next());
					if (builder_opt.isPresent()) {
						out.accept(builder_opt.get());
						iter.remove();
					}
				}
			}
		};
	}
	
	/**
	 * Wraps a strategy, filtering the keys passed to its implement strategy.
	 * Passes through key removal from the inner strategy to the nodeKeys parameter.
	 * @param existingStrategy the strategy to wrap
	 * @param keep a Predicate that returns true iff a key should be passed to the inner strategy
	 * @return the wrapped strategy
	 */
	public static MultiNodeStrategy filter(MultiNodeStrategy existingStrategy, Predicate<NodeInstanceDesc.Key> keep) {
		return new MultiNodeStrategy() {
			@Override
			public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
				//Filter the keys into a new ArrayList, or just reuse the nodeKeys if all are to be passed on.
				ArrayList<NodeInstanceDesc.Key> nodeKeysFiltered = null;
				var iter = nodeKeys.iterator();
				while (iter.hasNext()) {
					var key = iter.next();
					if (!keep.test(key)) {
						if (nodeKeysFiltered == null) {
							nodeKeysFiltered = new ArrayList<>();
							for (NodeInstanceDesc.Key copyKey : nodeKeys) {
								if (copyKey == key)
									break;
								nodeKeysFiltered.add(copyKey);
							}
						}
					}
					else if (nodeKeysFiltered != null)
						nodeKeysFiltered.add(key);
				}
				ListRemoveView<NodeInstanceDesc.Key> removeView = nodeKeysFiltered == null ? null : new ListRemoveView<>(nodeKeysFiltered);
				
				//Call the inner strategy.
				existingStrategy.implement(out, removeView == null ? nodeKeys : removeView, isLast);
				
				if (removeView != null && removeView.size() != nodeKeysFiltered.size()) {
					assert(removeView.size() <= nodeKeysFiltered.size());
					HashSet<NodeInstanceDesc.Key> removedKeys = new HashSet<>();
					//Determine which keys have been removed from removeView.
					var nonremovedIter = removeView.iterator();
					NodeInstanceDesc.Key nextNonremoved = (nonremovedIter.hasNext() ? nonremovedIter.next() : null);
					for (int i = 0; i < nodeKeysFiltered.size(); ++i) {
						assert(nodeKeysFiltered.get(i) != null);
						if (nodeKeysFiltered.get(i) != nextNonremoved) 
							removedKeys.add(nodeKeysFiltered.get(i));
						else
							nextNonremoved = (nonremovedIter.hasNext() ? nonremovedIter.next() : null);
					}
					assert(removedKeys.size() == nodeKeysFiltered.size() - removeView.size());
					//Remove those keys from nodeKeys.
					iter = nodeKeys.iterator();
					while (iter.hasNext()) {
						var key = iter.next();
						if (removedKeys.contains(key))
							iter.remove();
					}
				}
			}
		};
	}
	
	/** An empty strategy that always returns an empty Optional. */
	public static MultiNodeStrategy noneStrategy = new MultiNodeStrategy() {
		@Override
		public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {}
	};
}
