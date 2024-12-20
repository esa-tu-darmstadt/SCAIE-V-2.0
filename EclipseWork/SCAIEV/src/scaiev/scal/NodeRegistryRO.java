package scaiev.scal;

import java.util.Optional;

import scaiev.scal.NodeInstanceDesc.RequestedForSet;

/**
 * Read-only portion of the interface of {@link NodeRegistry}. May record lookups to determine the dependency relationship.
 */
public interface NodeRegistryRO {
	/**
	 * Looks up all node instances associated with a key.
	 * @param key the key that identifies the node.
	 *        Must not be partially-anonymized/-wildcarded, only {@link NodeRegistry#anonymizeKey(scaiev.scal.NodeInstanceDesc.Key)} is allowed.
	 * @param fullMatch true to search for precise key matches including the ISAX and aux, false to ignore ISAX and aux
	 * @return an Iterable over the node instances
	 */
	Iterable<NodeInstanceDesc> lookupAll(NodeInstanceDesc.Key key, boolean fullMatch);
	/**
	 * Looks up the unique node instance associated with a key. Additionally outputs an error message if it is not unique.
	 * @param key the key that identifies the node
	 *        Must not be partially-anonymized/-wildcarded, only {@link NodeRegistry#anonymizeKey(scaiev.scal.NodeInstanceDesc.Key)} is allowed.
	 * @return the node instance wrapped in Optional (if unique), or an empty Optional
	 */
	Optional<NodeInstanceDesc> lookupOptionalUnique(NodeInstanceDesc.Key key);
	/**
	 * Looks up the first node instance associated with a key
	 * @param key the key that identifies the node
	 *        Must not be partially-anonymized/-wildcarded, only {@link NodeRegistry#anonymizeKey(scaiev.scal.NodeInstanceDesc.Key)} is allowed.
	 * @return the node instance wrapped in Optional, or an empty Optional
	 */
	Optional<NodeInstanceDesc> lookupOptional(NodeInstanceDesc.Key key);
	
	/**
	 * Looks up the node instance associated with a key. Assumes a strong dependency (noting missing nodes internally). 
	 * @param key the key that identifies the node
	 * @return the resolved node instance, or a placeholder with expression value starting with "MISSING_"
	 */
	NodeInstanceDesc lookupRequired(NodeInstanceDesc.Key key);
	
	/**
	 * Deduplicates a node by choosing a unique non-zero aux value.
	 * Note: Does not modify internal state, only considers registered nodes.
	 * A common use-case in SCAIE-V is having multiple sources for WrStall and WrFlush.
	 * @param key the key to deduplicate
	 * @return a key with deduplicated aux
	 */
	NodeInstanceDesc.Key deduplicateNodeKeyAux(NodeInstanceDesc.Key key);
	
	/**
	 * Returns a new unique non-zero aux value.
	 */
	int newUniqueAux();
	

	/**
	 * Variant of {@link NodeRegistryRO#lookupAll(scaiev.scal.NodeInstanceDesc.Key, boolean)} with the additional requestedFor parameter.
	 * @param requestedFor the tracking set for relevant entities (e.g. ISAXes) that the key is being requested for; will be added to all returned nodes as a lazy reference
	 */
	default Iterable<NodeInstanceDesc> lookupAll(NodeInstanceDesc.Key key, boolean fullMatch, RequestedForSet requestedFor) {
		var ret = lookupAll(key, fullMatch);
		if (requestedFor != RequestedForSet.empty) {
			for (NodeInstanceDesc nodeInst : ret)
				nodeInst.addRequestedFor(requestedFor, true);
		}
		return ret;
	}
	
	/**
	 * Variant of {@link NodeRegistryRO#lookupOptionalUnique(scaiev.scal.NodeInstanceDesc.Key)} with the additional requestedFor parameter.
	 * @param requestedFor the tracking set for relevant entities (e.g. ISAXes) that the key is being requested for; will be added to the returned node as a lazy reference
	 */
	default Optional<NodeInstanceDesc> lookupOptionalUnique(NodeInstanceDesc.Key key, RequestedForSet requestedFor) {
		var ret = lookupOptionalUnique(key);
		if (ret.isPresent() && requestedFor != RequestedForSet.empty)
			ret.get().addRequestedFor(requestedFor, true);
		return ret;
	}
	/**
	 * Variant of {@link NodeRegistryRO#lookupOptional(scaiev.scal.NodeInstanceDesc.Key)} with the additional requestedFor parameter.
	 * @param requestedFor the tracking set for relevant entities (e.g. ISAXes) that the key is being requested for; will be added to the returned node as a lazy reference
	 */
	default Optional<NodeInstanceDesc> lookupOptional(NodeInstanceDesc.Key key, RequestedForSet requestedFor) {
		var ret = lookupOptional(key);
		if (ret.isPresent() && requestedFor != RequestedForSet.empty)
			ret.get().addRequestedFor(requestedFor, true);
		return ret;
	}
	
	/**
	 * Looks up the node instance associated with a key. Assumes a strong dependency (noting missing nodes internally). 
	 * @param key the key that identifies the node
	 * @param requestedFor the tracking set for relevant entities (e.g. ISAXes) that the key is being requested for; will be added to the returned node as a lazy reference
	 * @return the resolved node instance, or a placeholder with expression value starting with "MISSING_"
	 */
	default NodeInstanceDesc lookupRequired(NodeInstanceDesc.Key key, RequestedForSet requestedFor) {
		var ret = lookupRequired(key);
		if (requestedFor != RequestedForSet.empty)
			ret.addRequestedFor(requestedFor, true);
		return ret;
	}
	
	/**
	 * Looks up the expression associated with a key. Assumes a strong dependency (noting missing nodes internally). 
	 * @param key the key that identifies the node
	 * @param requestedFor the tracking set for relevant entities (e.g. ISAXes) that the key is being requested for; will be added to the node as a lazy reference
	 * @return the expression to retrieve the value of the given node (or a placeholder starting with "MISSING_")
	 */
	default String lookupExpressionRequired(NodeInstanceDesc.Key key, RequestedForSet requestedFor) {
		return lookupRequired(key, requestedFor).expression;
	}
	/**
	 * Looks up the expression associated with a key. Assumes a strong dependency (noting missing nodes internally). 
	 * @param key the key that identifies the node
	 * @return the expression to retrieve the value of the given node (or a placeholder starting with "MISSING_")
	 */
	default String lookupExpressionRequired(NodeInstanceDesc.Key key) {
		return lookupRequired(key).expression;
	}
	
//	/**
//	 * Using the registered nodes, deduplicates a signal name.
//	 * Important: Does not modify state, hence would return the same deduplicated name again.
//	 * @param signalName the signal name to deduplicate
//	 * @return the deduplicated signal name
//	 */
//	String deduplicateSignalName(String signalName);
}
