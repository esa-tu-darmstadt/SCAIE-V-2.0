package scaiev.scal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;

/**
 * Class that manages nodes during HDL generation.
 */
public class NodeRegistry implements NodeRegistryRO {
  // logging
  protected static final Logger logger = LogManager.getLogger();

  // Fully anonymized Purpose. Only for quick HashMap lookups.
  private static NodeInstanceDesc.Purpose MAP_ANONYMIZED =
      new NodeInstanceDesc.Purpose("MAP_ANONYMIZED", false, Optional.empty(), List.of());

  // Stores nodes by their anonymized key (i.e. regardless of key.isax)
  HashMap<NodeInstanceDesc.Key, ArrayList<NodeInstanceDesc>> map = new HashMap<>();
  HashSet<String> signalNames = new HashSet<>();

  Set<NodeInstanceDesc.Key> missingDependencySet = null;
  Set<NodeInstanceDesc.Key> weakDependencySet = null;
  Set<NodeInstanceDesc> resolvedSet = null;

  AtomicInteger auxCounter;

  public NodeRegistry() { this(null, null, null, null); }
  public NodeRegistry(NodeRegistry other) { this(null, null, null, other); }
  /**
   * Constructs a NodeRegistry with an attached set of missing nodes.
   * @param missingDependencySet the set to be filled with encountered missing nodes from required expression lookups
   * @param weakDependencySet the set to be filled with encountered weak or already fulfilled dependencies
   */
  public NodeRegistry(Set<NodeInstanceDesc.Key> missingDependencySet, Set<NodeInstanceDesc.Key> weakDependencySet,
                      Set<NodeInstanceDesc> resolvedSet) {
    this(missingDependencySet, weakDependencySet, resolvedSet, null);
  }
  /**
   * Constructs a NodeRegistry with an attached set of missing nodes.
   * @param missingDependencySet the set to be filled with encountered missing nodes from required expression lookups, or null
   * @param weakDependencySet the set to be filled with encountered weak or already fulfilled dependencies, or null
   * @param other an existing NodeRegistry whose nodes will be added to the new object, or null
   */
  public NodeRegistry(Set<NodeInstanceDesc.Key> missingDependencySet, Set<NodeInstanceDesc.Key> weakDependencySet,
                      Set<NodeInstanceDesc> resolvedSet, NodeRegistry other) {
    this.missingDependencySet = missingDependencySet;
    this.weakDependencySet = weakDependencySet;
    this.resolvedSet = resolvedSet;
    if (other != null) {
      other.map.forEach(
          (NodeInstanceDesc.Key key, ArrayList<NodeInstanceDesc> val) -> this.map.put(key, new ArrayList<NodeInstanceDesc>(val)));
      auxCounter = other.auxCounter;
    } else
      auxCounter = new AtomicInteger();
  }

  /**
   * Looks up all node instances associated with a key, but does not touch weakDependencySet.
   * @param key the key that identifies the node
   * @return an Iterable over the node instances
   */
  protected Iterable<NodeInstanceDesc> lookupAllIndep(NodeInstanceDesc.Key key, boolean fullMatch) {
    NodeInstanceDesc.Key keyAnon = anonymizeKey(key, false);

    if (key.equals(keyAnon))
      fullMatch = false;
    boolean fullMatch_ = fullMatch;

    NodeInstanceDesc.Key mapKeyAnon = anonymizeKey(key, true);
    List<NodeInstanceDesc> result = map.get(mapKeyAnon);
    if (result == null)
      result = Collections.emptyList();
    List<NodeInstanceDesc> result_ = result; // Java :)
    Purpose queryPurpose = key.getPurpose();
    // Check matches. Also deduplicate results, e.g. for a query with match_REGULAR_WIREDIN_OR_PIPEDIN,
    //  if the same node exists with REGULAR and with PIPEDIN, only return the result with REGULAR (higher priority).
    // NOTE: Each filter evaluation is O(N), i.e. full stream is O(N^2).
    //       Since there is one bucket per node-stage pair, N should be small, so this should be fine.
    // Immediately evaluate the stream into a List.
    // Another option would be to return an Iterable that always recreates the stream.
    List<NodeInstanceDesc> deduplicatedResults =
        result_.stream()
            .filter(nodeinst
                    -> (fullMatch_ // Test match
                            ? key.matches(nodeinst.getKey())
                            : keyAnon.matches(anonymizeKey(nodeinst.getKey()))))
            .filter(match -> {
              NodeInstanceDesc.Key matchKey = match.getKey();
              int matchPriority = key.getPurpose().matchPriority(matchKey.getPurpose());
              if (matchPriority == 0)
                return false;
              // Linear search for keys with equal ISAX and aux. Then checked for higher priorities.
              boolean isLowerPriorityDuplicate =
                  result_.stream()
                      .filter(submatch -> {
                        NodeInstanceDesc.Key submatchKey = submatch.getKey();
                        int submatchPriority = queryPurpose.matchPriority(submatchKey.getPurpose());
                        // Node and stage are supposed to be equal,
                        //  since the results are taken from a single bucket.
                        assert (submatchKey.getNode().equals(matchKey.getNode()) && submatchKey.getStage().equals(matchKey.getStage()));
                        return submatchPriority != 0 && submatchKey.getISAX().equals(matchKey.getISAX()) &&
                            submatchKey.getAux() == matchKey.getAux();
                      })
                      .anyMatch(submatch -> (queryPurpose.matchPriority(submatch.getKey().getPurpose()) > matchPriority));

              return !isLowerPriorityDuplicate;
            })
            .toList();
    return deduplicatedResults;
  }

  /**
   * Looks up all node instances associated with a key.
   * If there are matches with equal ISAX and aux values,
   *  only the one(s) with a maximum Purpose match priority are visible.
   * @param key the key that identifies the node
   * @return an Iterable over the node instances
   */
  public Iterable<NodeInstanceDesc> lookupAll(NodeInstanceDesc.Key key, boolean fullMatch) {
    if (!fullMatch) {
      if ((key.isax == null) ^ (key.aux == Integer.MIN_VALUE)) {
        // Relevant for use of keys as triggers in ModuleComposer.
        throw new IllegalArgumentException("key must not be partially anonymized");
      }
    }
    if (weakDependencySet != null) {
      weakDependencySet.add(fullMatch ? key : anonymizeKey(key, false));
    }
    Iterable<NodeInstanceDesc> ret = lookupAllIndep(key, fullMatch);
    if (resolvedSet != null) {
      ret.forEach(nodeInst -> { resolvedSet.add(nodeInst); });
    }
    return ret;
  }
  /**
   * Looks up the unique node instance associated with a key. Additionally outputs an error message if it is not unique.
   * @param key the key that identifies the node
   * @return the node instance wrapped in Optional (if unique), or an empty Optional
   */
  public Optional<NodeInstanceDesc> lookupOptionalUnique(NodeInstanceDesc.Key key) {
    if ((key.isax == null) ^ (key.aux == Integer.MIN_VALUE)) {
      // Relevant for use of keys as triggers in ModuleComposer.
      throw new IllegalArgumentException("key must not be partially anonymized");
    }
    if (weakDependencySet != null) {
      weakDependencySet.add(key);
    }
    Iterator<NodeInstanceDesc> iter = lookupAllIndep(key, true).iterator();
    if (!iter.hasNext())
      return Optional.empty();
    NodeInstanceDesc ret = iter.next();
    if (iter.hasNext()) {
      logger.error("lookupOptionalUnique: Not returning unexpected duplicate node " + ret.getKey().toString());
      return Optional.empty();
    }
    if (resolvedSet != null) {
      resolvedSet.add(ret);
    }
    return Optional.of(ret);
  }
  /**
   * Looks up the first node instance associated with a key
   * @param key the key that identifies the node
   * @return the node instance wrapped in Optional, or an empty Optional
   */
  public Optional<NodeInstanceDesc> lookupOptional(NodeInstanceDesc.Key key) {
    if ((key.isax == null) ^ (key.aux == Integer.MIN_VALUE)) {
      // Relevant for use of keys as triggers in ModuleComposer.
      throw new IllegalArgumentException("key must not be partially anonymized");
    }
    if (weakDependencySet != null) {
      weakDependencySet.add(key);
    }
    for (NodeInstanceDesc entry : lookupAllIndep(key, true)) {
      if (resolvedSet != null) {
        resolvedSet.add(entry);
      }
      return Optional.of(entry);
    }
    return Optional.empty();
  }

  /**
   * Looks up the node instance associated with a key. Assumes a strong dependency (noting missing nodes internally).
   * @param key the key that identifies the node
   * @return the resolved node instance, or a placeholder with expression value starting with "MISSING_"
   */
  public NodeInstanceDesc lookupRequired(NodeInstanceDesc.Key key) {
    if ((key.isax == null) ^ (key.aux == Integer.MIN_VALUE)) {
      // Relevant for use of keys as triggers in ModuleComposer.
      throw new IllegalArgumentException("key must not be partially anonymized");
    }
    NodeInstanceDesc retEntry = null;
    NodeInstanceDesc prevEntry = null;
    NodeInstanceDesc duplicatePseudoEntry = null;
    boolean logged = false;
    weakDependencySet.add(key);
    for (NodeInstanceDesc entry : lookupAllIndep(key, true)) {
      if (prevEntry != null) {
        // Detect duplicate matches.
        if (prevEntry.key.getPurpose().equals(entry.getKey().getPurpose())) {
          if (!logged)
            logger.error("Found a duplicate match for key {}", key);
        } else {
          // Matched instances with different Purpose values but with the same priority (w.r.t. the query key's Purpose).
          //-> There currently is no mechanism to handle this.
          //    A possible way would be to add one or several higher-priority Purposes as a missing dependency,
          //    which would ideally trigger some strategy that takes the lower-priority instances as inputs.
          //    (though, sensibly, the implicit higher-priority should then be added
          //     regardless of having duplicate matches or not)
          if (!logged)
            logger.error("Found duplicate (same-priority) matches for key {}", key);
        }
        logged = true;
        if (duplicatePseudoEntry != null)
          duplicatePseudoEntry =
              new NodeInstanceDesc(entry.getKey(), "DUPLICATE_MATCH_" + key.toString(), ExpressionType.AnyExpression_Noparen);
        duplicatePseudoEntry.addRequestedFor(entry.getRequestedFor(), true);
        retEntry = duplicatePseudoEntry;
      } else {
        retEntry = entry;
      }
      prevEntry = entry;
    }
    if (retEntry != null) {
      if (resolvedSet != null) {
        resolvedSet.add(retEntry);
      }
      int matchPriority = key.getPurpose().matchPriority(retEntry.getKey().getPurpose());
      if (!key.getPurpose().equals(retEntry.getKey().getPurpose()) && matchPriority < key.getPurpose().getMaxPriority()) {
        // Use the given match, but still try creating a better one.
        missingDependencySet.add(new NodeInstanceDesc.Key(Purpose.withMinPriority(key.getPurpose(), matchPriority + 1), key.getNode(),
                                                          key.getStage(), key.getISAX(), key.getAux()));
      }
      return retEntry;
    }
    missingDependencySet.add(key);
    return new NodeInstanceDesc(key, "MISSING_" + key.toString(), ExpressionType.AnyExpression_Noparen, new RequestedForSet());
  }

  /**
   * Anonymizes a key to search across all ISAXes and aux values (but not across Purposes).
   * @param key the key to anonymize
   * @param anonPurpose whether to replace the purpose with MAP_ANONYMIZED. Only for internal use (storage as HashMap Key).
   * @return a new NodeInstanceDesc.Key
   */
  private static NodeInstanceDesc.Key anonymizeKey(NodeInstanceDesc.Key key, boolean anonPurpose) {
    return new NodeInstanceDesc.Key(anonPurpose ? MAP_ANONYMIZED : key.getPurpose(), key.getNode(), key.getStage(), null,
                                    Integer.MIN_VALUE);
  }
  /**
   * Anonymizes a key to search across all ISAXes and aux values (but not across Purposes).
   * @param key the key to anonymize
   * @return a new NodeInstanceDesc.Key
   */
  public static NodeInstanceDesc.Key anonymizeKey(NodeInstanceDesc.Key key) { return anonymizeKey(key, false); }

  private void register(NodeInstanceDesc instance, boolean requireUnique) {
    if (instance.expressionType == ExpressionType.ModuleOutput)
      return; // Module outputs cannot be looked up as logic inputs.
    if (!instance.getKey().getPurpose().getRegisterAllowed()) {
      logger.error("Purpose for key " + instance.getKey().toString() + " is not allowed for registration");
      return;
    }
    instance.getKey().purpose = instance.getKey().purpose.getRegisterAs();
    // Anonymize the ISAX in the map.
    NodeInstanceDesc.Key anonymizedMapKey = anonymizeKey(instance.getKey(), true);
    ArrayList<NodeInstanceDesc> entries = map.get(anonymizedMapKey);
    if (requireUnique && entries != null && !entries.isEmpty() &&
        entries.stream().anyMatch((nodeinst) -> nodeinst.getKey().equals(instance.getKey()))) {
      logger.error("Not registering unexpected duplicate node " + instance.getKey().toString());
      return;
    }
    if (entries == null) {
      entries = new ArrayList<>(1);
    }
    map.put(anonymizedMapKey, entries);
    switch (instance.expressionType) {
    case ModuleInput:
    case WireName:
      registerSignalName(instance.expression);
      break;
    case AnyExpression:
    case AnyExpression_Noparen:
      break;
    case ModuleOutput:
      assert (false);
      return;
    }
    entries.add(instance);
  }
  /**
   * Registers a node instance and asserts it unique.
   * @param instance the node instance to register
   */
  public void registerUnique(NodeInstanceDesc instance) { register(instance, true); }
  /**
   * Registers a node instance without asserting uniqueness.
   * @param instance the node instance to register
   */
  public void register(NodeInstanceDesc instance) { register(instance, false); }

  /**
   * Removes a node from the registry.
   * @param key the exact key that identifies the node
   */
  public void unregister(NodeInstanceDesc.Key key) {
    NodeInstanceDesc.Key anonymizedMapKey = anonymizeKey(key, true);
    ArrayList<NodeInstanceDesc> entries = map.get(anonymizedMapKey);
    boolean found = false;
    int limit = (entries == null) ? 0 : entries.size();
    for (int i = 0; i < limit; ++i) {
      if (entries.get(i).getKey().equals(key)) {
        if (found) {
          logger.warn("Unregistering several nodes " + key.toString() + " (?)");
        }
        switch (entries.get(i).expressionType) {
        case ModuleInput:
        case WireName:
          // Also remove from the signal name set.
          signalNames.remove(entries.get(i).expression);
          break;
        case AnyExpression:
        case AnyExpression_Noparen:
          break;
        case ModuleOutput:
          // Module outputs should not be in the registry.
          assert (false);
          continue;
        }
        // swap and remove
        entries.set(i, entries.get(limit - 1));
        entries.remove(limit - 1);
        --i;
        --limit;
        found = true;
      }
    }
    if (!found) {
      logger.warn("Could not find node " + key.toString() + " to unregister");
    }
  }

  /**
   * Explicitly registers a signal name, asserting its uniqueness.
   * Note: Both {@link #register(NodeInstanceDesc)} and {@link #registerUnique(NodeInstanceDesc)} do this implicitly.
   * @param signalName the name to register
   */
  public void registerSignalName(String signalName) {
    if (!signalNames.add(signalName)) {
      logger.error("Encountered duplicate signal name " + signalName);
    }
  }

  //	/**
  //	 * Using the registered nodes, deduplicates a signal name.
  //	 * Important: Does not modify state, hence would return the same deduplicated name again.
  //	 * @param signalName the signal name to deduplicate
  //	 * @return the deduplicated signal name
  //	 */
  //	public String deduplicateSignalName(String signalName) {
  //		String signalName_base = signalName; int i = 1;
  //		//Will be slow if there are many duplicates. Should be fine for the SCAL use case.
  //		while (signalNames.contains(signalName)) {
  //			signalName = String.format("%s__%d", signalName_base, ++i);
  //		}
  //		return signalName;
  //	}

  /**
   * Clears the internal signal names set.
   * Useful to regenerate logic blocks.
   */
  public void clearSignalNames() { signalNames.clear(); }

  /**
   * Deduplicates a node by choosing a new unique non-zero aux value.
   * Note: Should only be called once per added node in a NodeLogicBuilder, as this method always reserves a new aux value.
   * @param key the key to deduplicate
   * @return a key with deduplicated aux
   */
  public Key deduplicateNodeKeyAux(Key key) {
    return new Key(key.getPurpose(), key.getNode(), key.getStage(), key.getISAX(), auxCounter.incrementAndGet());
  }

  /**
   * Returns a new unique non-zero aux value.
   */
  public int newUniqueAux() { return auxCounter.incrementAndGet(); }
}
