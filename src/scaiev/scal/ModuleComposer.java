package scaiev.scal;

import java.io.IOException;
import java.io.Writer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.util.ListRemoveView;

/**
 * Generates logic for an HDL module based on caller-supplied strategies.
 * The object may contain some state that will not persist across calls of {@link ModuleComposer#Generate(MultiNodeStrategy, NodeRegistry,
 * ArrayList, boolean)}
 */
public class ModuleComposer {
  /**
   * Holds a NodeLogicBuilder alongside the resulting NodeLogicBlock and dependencies.
   */
  public static class NodeBuilderEntry {
    /**
     * Holds the index of this NodeBuilderEntry in an array.
     * Only meaningful if set appropriately, may get broken by trimming.
     */
    int i;
    int aux;
    public NodeLogicBuilder builder;
    /** The optional NodeLogicBlock from the builder. Should only be empty if the builder has not been called yet. */
    public Optional<NodeLogicBlock> block = Optional.empty(); // built by builder
    /** The last reported missing dependencies of this builder */
    public ArrayList<NodeInstanceDesc.Key> missingDependencySet = new ArrayList<>();
    /** The last reported optional or present dependencies of this builder */
    public ArrayList<NodeInstanceDesc.Key> weakDependencySet = new ArrayList<>();
    /** The last reported resolved dependencies of this builder */
    public ArrayList<Map.Entry<NodeBuilderEntry, NodeInstanceDesc>> resolvedDependencySet = new ArrayList<>();
    NodeBuilderEntry(int i, AtomicInteger aux, NodeLogicBuilder builder) {
      this.i = i;
      this.aux = aux.incrementAndGet();
      this.builder = builder;
    }
  }

  private static class BuildLogEntry {
    /** The build iteration */
    public int iteration;
    /**
     * The NodeBuilderEntry for the build.
     *  Note that the entry's block and dependency fields may be from a future iteration at the time of evaluation.
     */
    public NodeBuilderEntry builderEntry;
    /** The resulting NodeLogicBlock at the iteration. */
    public NodeLogicBlock built;
    /** The NodeBuilderEntries that this build enqueued an update on */
    public ArrayList<NodeBuilderEntry> triggeredUpdatesOn = new ArrayList<>();
    /** The NodeBuilderEntries that this build enqueued an update on */
    public ArrayList<NodeInstanceDesc.Key> missingDependencies = new ArrayList<>();
    public BuildLogEntry(int iteration, NodeBuilderEntry builderEntry) {
      this.iteration = iteration;
      this.builderEntry = builderEntry;
      this.built = builderEntry.block.get();
      this.missingDependencies = new ArrayList<>(builderEntry.missingDependencySet);
    }
  }
  /** The log of the last build. */
  private ArrayList<BuildLogEntry> buildLog = new ArrayList<>();
  private boolean initial_enableBuildLog = false;
  private boolean late_enableBuildLog = true;

  public ModuleComposer() {}
  public ModuleComposer(boolean initial_enableBuildLog, boolean late_enableBuildLog) {
    this();
    this.initial_enableBuildLog = initial_enableBuildLog;
    this.late_enableBuildLog = late_enableBuildLog;
  }

  /** Retrieves all keys for nodeUpdateTriggers that is relevant to a given dependency key. */
  private static Stream<NodeInstanceDesc.Key> getRelevantTriggerKeys(NodeInstanceDesc.Key depKey) {
    return Stream.concat(Stream.of(depKey.getPurpose()), depKey.getPurpose().getMatchesOthers().stream().map(entry_ -> entry_.getKey()))
        .map(specificPurpose
             -> new NodeInstanceDesc.Key(specificPurpose, depKey.getNode(), depKey.getStage(), depKey.getISAX(), depKey.getAux()));
  }

  private static final Purpose MAPPURPOSE = new Purpose("MAPPURPOSE", false, Optional.empty(), List.of());

  /** Utility: Object wrapper to compare objects with overridden equals method by instance equality. */
  private static class ObjInstanceComparatorWrapper<T> {
    public T val;
    public ObjInstanceComparatorWrapper(T val) { this.val = val; }
    @Override
    public int hashCode() {
      return Objects.hash(val);
    }
    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      @SuppressWarnings("rawtypes") ObjInstanceComparatorWrapper other = (ObjInstanceComparatorWrapper)obj;
      return val == other.val;
    }
  }

  /**
   * Builds the given nodes and all dependencies iteratively, using a strategy.
   * The strategy is expected to ensure that only one builder will be instantiated per node,
   *   or that the builders always return the same set of nodes (regardless of missing dependencies).
   * @param rootStrategy Strategy that handles all dependencies that no builder exists for already
   * @param initialNodeRegistry A node registry containing statically provided nodes (e.g. predefined module inputs)
   * @param initialLogicBuilders Initial logic builders (e.g. simple 'assign <module_output_pin> = <node dependency>;' logic blocks)
   * @param trim If set to true, all logic blocks that are not part of the (final) inferred dependency graph from initialLogicBuilders will
   *     be removed
   *             (however, this also means that the strategy cannot add new root logic builders that nothing depends on internally,
   *             e.g. for a new distinct module output port)
   *
   */
  public ArrayList<NodeBuilderEntry> Generate(MultiNodeStrategy rootStrategy, NodeRegistry initialNodeRegistry,
                                              ArrayList<NodeLogicBuilder> initialLogicBuilders, boolean trim) {

    HashSet<NodeInstanceDesc.Key> lastRecordedMissingDependencies = new HashSet<>();
    HashSet<NodeInstanceDesc.Key> lastRecordedWeakDependencies = new HashSet<>();
    HashSet<NodeInstanceDesc> lastRecordedResolvedDependencies = new HashSet<>();
    NodeRegistry composerNodeRegistry = new NodeRegistry(lastRecordedMissingDependencies, lastRecordedWeakDependencies,
                                                         lastRecordedResolvedDependencies, initialNodeRegistry);
    ArrayList<NodeBuilderEntry> globalLogic = new ArrayList<>(initialLogicBuilders.size());
    initialLogicBuilders.forEach(
        builder -> globalLogic.add(new NodeBuilderEntry(globalLogic.size(), composerNodeRegistry.auxCounter, builder)));

    // Map to identify node builders to rerun after a node changes
    HashMap<NodeInstanceDesc.Key, ArrayList<NodeBuilderEntry>> nodeUpdateTriggers = new HashMap<>();
    final ArrayList<NodeBuilderEntry> updateList_empty = new ArrayList<>(0);

    // Record a mapping of NodeInstanceDesc to NodeBuilderEntry.
    HashMap<ObjInstanceComparatorWrapper<NodeInstanceDesc>, NodeBuilderEntry> builderEntryByNodeInstance = new HashMap<>();

    // For the first iteration, put all node builders in the update list.
    ArrayList<NodeBuilderEntry> updateList = new ArrayList<NodeBuilderEntry>(globalLogic);

    int i_iter = 0;

    boolean enable_log = initial_enableBuildLog;
    this.buildLog.clear();

    // Lookup for existing combined Purposes.
    HashMap<Set<Purpose>, Purpose> temporaryCombinedPurposes = new HashMap<>();

    while (!updateList.isEmpty()) {
      // Sort updateList by inner dependencies.
      //  (not strictly necessary, but should reduce required iterations count)
      //- Does not error out on cycles.
      //   If the NodeLogicBuilders within the cycle provide stable outputs at some point,
      //   the problem will resolve after some iterations.
      HashMap<NodeInstanceDesc.Key, ArrayList<Integer>> dependantByKey = new HashMap<>();
      ArrayList<Integer> dfs_status = new ArrayList<>(updateList.size());
      // Initialize dependantByKey and dfs_status.
      for (int i = 0; i < updateList.size(); ++i) {
        int i_ = i; // Java :)
        Consumer<NodeInstanceDesc.Key> addDep = dep -> {
          ArrayList<Integer> dep_list = dependantByKey.get(dep);
          if (dep_list == null) {
            dep_list = new ArrayList<>(1);
            dependantByKey.put(dep, dep_list);
          }
          dep_list.add(i_);
        };
        updateList.get(i).missingDependencySet.forEach(addDep);
        updateList.get(i).weakDependencySet.forEach(addDep);
        dfs_status.add(0);
      }

      // Textbook-style depth-first search for topological ordering.
      // We shouldn't run into stack limits with SCAL.
      ArrayList<NodeBuilderEntry> updateList_reverseOrder = new ArrayList<>(updateList.size());
      ArrayList<Integer> dfs_stack = new ArrayList<>();
      ArrayList<NodeBuilderEntry> updateList_ = updateList; // Java :)

      class SupplierBoolWrap {
        public Supplier<Boolean> fn = null;
      }
      SupplierBoolWrap dfsProcess = new SupplierBoolWrap(); // Java :)
      dfsProcess.fn = () -> {
        int i = dfs_stack.get(dfs_stack.size() - 1);
        dfs_status.set(i, 1);
        Function<Integer, Boolean> visit = dependantIdx -> {
          if (dfs_status.get(dependantIdx) == 1) {
            // Notify caller about cycle
            return true;
          }
          if (dfs_status.get(dependantIdx) == 0) {
            dfs_stack.add(dependantIdx); // push
            return dfsProcess.fn.get();
          }
          return dfs_status.get(dependantIdx) == 3;
        };
        Optional<NodeLogicBlock> logicBlockOpt = updateList_.get(i).block;
        boolean has_cycle = logicBlockOpt
                                .map(logicBlock
                                     -> logicBlock.outputs.stream()
                                            .map(output -> {
                                              NodeInstanceDesc.Key key = output.key;
                                              ArrayList<Integer> depListA = dependantByKey.get(key);
                                              ArrayList<Integer> depListB = dependantByKey.get(NodeRegistry.anonymizeKey(key));
                                              boolean output_has_cycle = false;
                                              if (depListA != null)
                                                output_has_cycle |= depListA.stream().map(visit).reduce(false, (a, b) -> a | b);
                                              if (depListB != null && depListB != depListA)
                                                output_has_cycle |= depListB.stream().map(visit).reduce(false, (a, b) -> a | b);
                                              return output_has_cycle;
                                            })
                                            .reduce(false, (a, b) -> a | b))
                                .orElse(false);
        dfs_status.set(i, has_cycle ? 3 : 2);
        updateList_reverseOrder.add(updateList_.get(i));
        dfs_stack.remove(dfs_stack.size() - 1); // pop
        return has_cycle;
      };
      for (int i = 0; i < updateList.size(); ++i) {
        if (dfs_status.get(i) == 0) {
          dfs_stack.add(i); // push
          dfsProcess.fn.get();
        }
      }

      LinkedHashSet<NodeBuilderEntry> nextUpdateSet = new LinkedHashSet<>();

      // Visit the updates in the determined order (i.e. from last to first entry of updateList_reverseOrder).

      for (int i = 0; i < updateList.size(); ++i) {
        NodeBuilderEntry entry = updateList_reverseOrder.get(updateList.size() - 1 - i);

        HashSet<NodeInstanceDesc> oldBlockOutputs = new HashSet<>();
        if (entry.block.isPresent()) {
          oldBlockOutputs = new HashSet<>(entry.block.get().outputs);
          // Remove any old outputs from composerNodeRegistry
          for (NodeInstanceDesc output : entry.block.get().outputs) {
            if (output.expressionType != ExpressionType.ModuleOutput)
              composerNodeRegistry.unregister(output.key);
            builderEntryByNodeInstance.remove(new ObjInstanceComparatorWrapper<>(output));
          }
          // Remove builder from all relevant trigger sets
          Consumer<NodeInstanceDesc.Key> removeTrigger = depKey -> getRelevantTriggerKeys(depKey).forEach(curKey -> {
            ArrayList<NodeBuilderEntry> triggersOld = nodeUpdateTriggers.get(curKey);
            // swap and remove
            int trigger_entry_idx = triggersOld.indexOf(entry);
            if (trigger_entry_idx != -1) {
              triggersOld.set(trigger_entry_idx, triggersOld.get(triggersOld.size() - 1));
              triggersOld.remove(triggersOld.size() - 1);
            }
            assert (trigger_entry_idx != -1);
          });
          for (NodeInstanceDesc.Key depKey : entry.missingDependencySet) {
            removeTrigger.accept(depKey);
          }
          for (NodeInstanceDesc.Key depKey : entry.weakDependencySet) {
            removeTrigger.accept(depKey);
          }
        }

        lastRecordedMissingDependencies.clear();
        lastRecordedWeakDependencies.clear();
        lastRecordedResolvedDependencies.clear();
        NodeLogicBlock newBlock = entry.builder.apply(composerNodeRegistry, entry.aux);
        // Immediately replace the Purposes of all output keys with their 'register as' values.
        // This prevents mis-detections of changed outputs.
        newBlock.outputs = newBlock.outputs.stream()
                               .map(nodeInst
                                    -> new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(
                                                                nodeInst.getKey(), nodeInst.getKey().getPurpose().getRegisterAs()),
                                                            nodeInst.getExpression(), nodeInst.getExpressionType(), nodeInst.requestedFor))
                               .collect(Collectors.toList());

        // Keep a reference to the old logic block for change detection.
        Optional<NodeLogicBlock> oldBlock_opt = entry.block;

        // Update the logic builder/block entry.
        entry.block = Optional.of(newBlock);
        entry.missingDependencySet.clear();
        entry.missingDependencySet.addAll(lastRecordedMissingDependencies);
        entry.weakDependencySet.clear();
        entry.weakDependencySet.addAll(lastRecordedWeakDependencies);
        entry.resolvedDependencySet.clear();
        for (NodeInstanceDesc outputInstanceDesc : lastRecordedResolvedDependencies) {
          var builderEntry = builderEntryByNodeInstance.get(new ObjInstanceComparatorWrapper<>(outputInstanceDesc));
          assert (builderEntry != null);
          entry.resolvedDependencySet.add(Map.entry(builderEntry, outputInstanceDesc));
        }

        // Create a log entry if requested.
        BuildLogEntry logEntry = null;
        if (enable_log) {
          logEntry = new BuildLogEntry(i_iter, entry);
          buildLog.add(logEntry);
        }

        BuildLogEntry logEntry_ = logEntry; // Java :)
        boolean enable_log_ = enable_log;   // Java :)
        Consumer<List<NodeBuilderEntry>> enqueueRebuild = dependencies -> {
          nextUpdateSet.addAll(dependencies);
          if (enable_log_)
            logEntry_.triggeredUpdatesOn.addAll(dependencies);
        };

        HashSet<NodeInstanceDesc> newBlockOutputs = new HashSet<>(newBlock.outputs);
        // Detect changes to the NodeLogicBlock outputs -> enqueue dependant builders for updates.
        //  For NodeInstanceDescs that are (by full comparison) in old outputs but not in new outputs.
        if (oldBlock_opt.isPresent())
          for (NodeInstanceDesc oldOutput : oldBlock_opt.get().outputs) {
            if (oldOutput.expressionType == ExpressionType.ModuleOutput || newBlockOutputs.contains(oldOutput))
              continue;
            enqueueRebuild.accept(nodeUpdateTriggers.getOrDefault(oldOutput.key, updateList_empty));
            enqueueRebuild.accept(nodeUpdateTriggers.getOrDefault(NodeRegistry.anonymizeKey(oldOutput.key), updateList_empty));
          }
        // For NodeInstanceDescs that are (by full comparison) in new outputs but not in old outputs
        for (NodeInstanceDesc newOutput : newBlock.outputs) {
          if (newOutput.expressionType == ExpressionType.ModuleOutput || oldBlockOutputs.contains(newOutput))
            continue;
          enqueueRebuild.accept(nodeUpdateTriggers.getOrDefault(newOutput.key, updateList_empty));
          enqueueRebuild.accept(nodeUpdateTriggers.getOrDefault(NodeRegistry.anonymizeKey(newOutput.key), updateList_empty));
        }

        // Add the new outputs to builderEntryByNodeInstance.
        for (NodeInstanceDesc newOutput : newBlock.outputs) {
          var oldVal = builderEntryByNodeInstance.put(new ObjInstanceComparatorWrapper<>(newOutput), entry);
          assert (oldVal == null); // Each NodeInstanceDesc should only be used once.
        }

        // Add all dependencies to the update trigger map (for each matching Purpose).
        Consumer<NodeInstanceDesc.Key> addTrigger = depKey -> getRelevantTriggerKeys(depKey).forEach(curKey -> {
          ArrayList<NodeBuilderEntry> triggers = nodeUpdateTriggers.computeIfAbsent(curKey, curKey_ -> new ArrayList<>());
          // TODO: Allow partially anonymized triggers (i.e. aux as wildcard but not ISAX)
          assert ((depKey.getISAX() == null) == (depKey.getAux() == Integer.MIN_VALUE));
          triggers.add(entry);
        });
        for (NodeInstanceDesc.Key depKey : entry.missingDependencySet) {
          addTrigger.accept(depKey);
        }
        for (NodeInstanceDesc.Key depKey : entry.weakDependencySet) {
          addTrigger.accept(depKey);
        }

        // Register the output nodes.
        for (NodeInstanceDesc output : newBlock.outputs) {
          if (output.expressionType != ExpressionType.ModuleOutput)
            composerNodeRegistry.registerUnique(output);
        }
      }

      // Only instantiate new builders if the build state (in globalLogic) is up-to-date.
      if (nextUpdateSet.isEmpty()) {
        // For all dependencies reported missing that are still missing, call the strategy.
        //  -> In general, this approach works if there are no cycles in the dependency graph,
        //     or at the very least, outputs of a builder are not removed from one invocation to another.
        //     Some cycles are allowed, such as nodes writing a WrStall sub-entry and then reading the overall WrStall.
        HashMap<NodeInstanceDesc.Key, List<Purpose>> dependenciesToInstantiate = new LinkedHashMap<>();
        for (int i = 0; i < globalLogic.size(); ++i) {
          NodeBuilderEntry entry = globalLogic.get(i);
          for (NodeInstanceDesc.Key depKey : entry.missingDependencySet) {
            // Check for any match, excluding matches from entry itself.
            boolean isPresentAlready = false;
            for (NodeInstanceDesc resolvedInstance : composerNodeRegistry.lookupAll(depKey, true)) {
              boolean isSelfMatch = entry.block.isPresent() && entry.block.get().outputs.contains(resolvedInstance);
              if (!isSelfMatch) {
                isPresentAlready = true;
                break;
              }
            }
            assert (!isPresentAlready);
            if (isPresentAlready)
              continue; // All updates are done (nextUpdateSet.isEmpty()), dependency is fulfilled, but it is still marked missing for some
                        // logic.

            // To avoid calling strategies with overlapping Purposes on the otherwise same key,
            //  subtract 'getMatchesOthers' of existing Purposes from this one.
            // If this Purpose is present in 'getMatchesOthers' already, it will be skipped entirely.
            // NOTE: This assumes the strategies are well-behaved
            //  and create builders that immediately output the node they were requested to build,
            //  or that at least do not remove the nodeKey from the list again.
            var existingPurposes = dependenciesToInstantiate.computeIfAbsent(NodeInstanceDesc.Key.keyWithPurpose(depKey, MAPPURPOSE),
                                                                             depKey_ -> new ArrayList<>());
            if (!existingPurposes.contains(depKey.getPurpose())) {
              Purpose addPurpose = depKey.getPurpose();

              Map<Purpose, Integer> keyMatchedPurposes = new LinkedHashMap<>();
              for (var matchEntry : depKey.getPurpose().getMatchesOthers()) {
                keyMatchedPurposes.put(matchEntry.getKey(), matchEntry.getValue());
              }
              // Also check for matches against the new purpose itself.
              keyMatchedPurposes.putIfAbsent(depKey.getPurpose(), depKey.getPurpose().getMaxPriority() + 1);
              boolean matchMapChanged = false;
              for (Purpose existingPurpose : existingPurposes) {
                // Check against each matched Purpose of our new key.
                var keyMatchedIter = keyMatchedPurposes.entrySet().iterator();
                while (keyMatchedIter.hasNext()) {
                  var matchPriorityEntry = keyMatchedIter.next();
                  Purpose newKeyMatchPurpose = matchPriorityEntry.getKey();
                  if (existingPurpose.matches(newKeyMatchPurpose)) {
                    // If there is a match, remove it from the set via the iterator.
                    keyMatchedIter.remove();
                    matchMapChanged = true;
                  }
                }
              }
              if (matchMapChanged) {
                // The match map changed, so we need to create a new Purpose for it.

                // To prevent excessive construction of new Purpose objects (which increases a global counter in Purpose),
                //  cache the objects in temporaryCombinedPurposes.
                addPurpose = temporaryCombinedPurposes.get(keyMatchedPurposes.keySet());
                if (addPurpose == null && !keyMatchedPurposes.isEmpty()) {
                  addPurpose = new Purpose("match_" + keyMatchedPurposes.entrySet()
                                                          .stream()
                                                          .sorted((a, b) -> a.getValue().compareTo(b.getValue()))
                                                          .map(a -> a.getKey().getName())
                                                          .reduce((a, b) -> a + "_OR_" + b)
                                                          .orElseThrow(),
                                           false, Optional.empty(), keyMatchedPurposes.entrySet());
                  temporaryCombinedPurposes.put(Set.copyOf(keyMatchedPurposes.keySet()), addPurpose);
                }
              }
              if (addPurpose != null)
                existingPurposes.add(addPurpose);
            }
          }
        }

        Consumer<NodeLogicBuilder> addBuilder = builder -> {
          NodeBuilderEntry newEntry = new NodeBuilderEntry(globalLogic.size(), composerNodeRegistry.auxCounter, builder);
          globalLogic.add(newEntry);
          nextUpdateSet.add(newEntry);
        };
        if (!dependenciesToInstantiate.isEmpty()) {
          // For all entries in dependenciesToInstantiate, create keys for all listed Purposes.
          List<NodeInstanceDesc.Key> dependenciesToInstantiate_list =
              dependenciesToInstantiate.entrySet()
                  .stream()
                  .flatMap(key_purposes -> key_purposes.getValue().stream().map(purpose -> Map.entry(key_purposes.getKey(), purpose)))
                  .map(key_purpose -> NodeInstanceDesc.Key.keyWithPurpose(key_purpose.getKey(), key_purpose.getValue()))
                  .collect(Collectors.toCollection(ArrayList::new));
          // Finally, call the strategy on the list, using ListRemoveView for removal efficiency.
          rootStrategy.implement(addBuilder, new ListRemoveView<>(dependenciesToInstantiate_list), false);
          if (nextUpdateSet.isEmpty())
            rootStrategy.implement(addBuilder, new ListRemoveView<>(dependenciesToInstantiate_list), true); // last chance
        }
      }

      updateList = new ArrayList<>(nextUpdateSet);

      if (i_iter == 100) {
        System.out.println("[ModuleComposer] This is taking a while... Maybe there are instable cycles of NodeLogicBuilders?");
        // If requested, use this as a trigger to enable the build log for debugging purposes.
        enable_log = late_enableBuildLog;
      }
      if (i_iter == 500) {
        System.out.println("[ModuleComposer] Aborting after 500 iterations.");
        trim = false;
        break;
      }
      ++i_iter;
    }

    BiFunction<List<NodeBuilderEntry>, Integer, Stream<Integer>> getLogicDependencies = (logicList, globalLogicIdx) -> {
      return logicList.get(globalLogicIdx).resolvedDependencySet.stream().map(logicAndOutputPair -> {
        assert (logicAndOutputPair.getKey() == logicList.get(logicAndOutputPair.getKey().i));
        return logicAndOutputPair.getKey().i;
      });
    };
    //			return globalLogic.get(globalLogicIdx).weakDependencySet.stream()
    //				.flatMap(weakDep -> getRelevantTriggerKeys(weakDep))
    //				.flatMap(curKey -> {
    //					return Stream.concat(
    //						nodeUpdateTriggers.getOrDefault(curKey, updateList_empty).stream().map(entry -> {
    //							assert(entry == globalLogic.get(entry.i));
    //							return entry.i;
    //						}),
    //						nodeUpdateTriggers.getOrDefault(NodeRegistry.anonymizeKey(curKey),
    // updateList_empty).stream().map(entry -> { 							assert(entry ==
    // globalLogic.get(entry.i)); 							return entry.i;
    //						})
    //					);
    //				});
    //		};

    // Trim unused blocks
    if (trim) {
      // Run a DFS from the initial builders.
      boolean[] visitList = new boolean[globalLogic.size()]; //(implicit init to false)
      class VisitFnWrap {
        public Consumer<Integer> fn = null;
      }
      VisitFnWrap dfsVisit = new VisitFnWrap();
      dfsVisit.fn = i -> {
        if (visitList[i])
          return;
        visitList[i] = true;
        getLogicDependencies.apply(globalLogic, i).forEach(depIdx -> dfsVisit.fn.accept(depIdx));
      };
      for (int i = 0; i < globalLogic.size(); ++i) {
        // Treat all output ports as relevant, regardless of whether they are linked to the initial builders.
        // This should also capture all relevant logic hooks that are not in the dependency chain of the initial builders,
        //  but may keep some unneeded logic in place (e.g. constant zero WrStall conditions).
        if (globalLogic.get(i)
                .block
                .map(block -> block.interfPins.stream().anyMatch(interfPin -> !interfPin.getValue().nodeDesc.getKey().getNode().isInput))
                .orElse(false))
          dfsVisit.fn.accept(i);
      }
      for (int i = globalLogic.size() - 1; i >= 0; --i) {
        if (!visitList[i]) {
          globalLogic.get(i).i = -1;
          globalLogic.remove(i);
        }
      }
      for (int i = 0; i < globalLogic.size(); ++i) {
        // Repair the index field.
        globalLogic.get(i).i = i;
      }
    }

    ArrayList<NodeBuilderEntry> dependencySortedLogic = new ArrayList<>();
    {
      boolean[] visitList = new boolean[globalLogic.size()]; //(implicit init to false)
      boolean[] alreadyAddedList = new boolean[globalLogic.size()];
      Deque<Integer> processingStack = new ArrayDeque<>(globalLogic.size());
      for (int i = 0; i < globalLogic.size(); ++i) {
        // Add all included logic to the stack.
        // Keep visitList[i] false, as we don't know the correct order yet.
        processingStack.add(i);
      }
      while (!processingStack.isEmpty()) {
        Integer i = processingStack.peekFirst();
        int sizeLast = processingStack.size();
        getLogicDependencies.apply(globalLogic, i).forEach(depIdx -> {
          if (!visitList[depIdx]) {
            visitList[depIdx] = true;
            processingStack.addFirst(depIdx);
          }
        });
        if (processingStack.size() == sizeLast) { // No unprocessed dependencies.
          // Since visitList is not set for the initial entries, check the additional 'already added' flag.
          if (!alreadyAddedList[i]) {
            alreadyAddedList[i] = true;
            dependencySortedLogic.add(globalLogic.get(i));
          }
          processingStack.removeFirst();
        }
      }
      for (int i = 0; i < dependencySortedLogic.size(); ++i) {
        // Repair the index field.
        dependencySortedLogic.get(i).i = i;
      }
      // NOTE: At this point, the index fields are valid only w.r.t. dependencySortedLogic
    }

    return dependencySortedLogic;
  }

  /** Returns if there is any data to export for a build log. */
  public boolean hasLog() { return !buildLog.isEmpty(); }
  /** Writes the DOT graph for the build log. */
  public void writeLogAsDot(Writer writer) throws IOException {
    writer.write("digraph composition_updatelog {\n  compound=true;\n");
    String suffix = "";
    int subgraph_idx = -1;
    Function<BuildLogEntry, String> makeNodeName = logEntry -> {
      return String.format("\"iter_%d_%s\"", logEntry.iteration, logEntry.builderEntry.builder.toString().replace("\"", "\\\""));
    };
    HashMap<NodeBuilderEntry, BuildLogEntry> updateTargetMap = new HashMap<>();

    class State_ {
      // Index range in buildLog for the previous iteration, i.e. subgraph_idx-1.
      int range_prevIter_min = -1;
      int range_prevIter_max = -1;
      // Start index in buildLog for the current iteration, i.e. subgraph_idx.
      int range_curIter_min = -1;
      int numIndent = 1;
      String indent = "  ";
    };
    State_ s = new State_(); // Java :)

    Supplier<IOException> emitEdges = () -> {
      try {
        if (s.range_prevIter_min != -1 && s.range_prevIter_max > s.range_prevIter_min) {
          int prevIteration = buildLog.get(s.range_prevIter_min).iteration;
          writer.write(s.indent.repeat(s.numIndent) +
                       String.format("iter_%d -> iter_%d [ltail=itercluster_%d, lhead=itercluster_%d, style=invis, weight=1000];\n",
                                     prevIteration, prevIteration + 1, prevIteration, prevIteration + 1));
          for (int iFrom = s.range_prevIter_min; iFrom <= s.range_prevIter_max; ++iFrom) {
            BuildLogEntry logEntryFrom = buildLog.get(iFrom);
            String nodeNameFrom = makeNodeName.apply(logEntryFrom);
            for (NodeBuilderEntry builderEntryTo : logEntryFrom.triggeredUpdatesOn) {
              BuildLogEntry logEntryTo = updateTargetMap.get(builderEntryTo);
              if (logEntryTo == null)
                continue;
              String nodeNameTo = makeNodeName.apply(logEntryTo);
              writer.write(s.indent.repeat(s.numIndent) + String.format("%s -> %s;\n", nodeNameFrom, nodeNameTo));
            }
          }
          writer.write("\n");
        }
        return null;
      } catch (IOException e) {
        return e;
      } // no throws support in lambda
    };

    for (int i = 0; i < buildLog.size(); ++i) {
      BuildLogEntry logEntry = buildLog.get(i);
      if (logEntry.iteration > subgraph_idx) {
        if (!suffix.isEmpty()) {
          writer.write(suffix);
          writer.write("\n");
          s.numIndent--;
        }

        /* Before processing the new iteration, resolve edges from subgraph_idx-1 to subgraph_idx
         * (note: at this point, subgraph_idx<=logEntry.iteration-1) */
        IOException e = emitEdges.get();
        if (e != null)
          throw e;

        // Update index ranges.
        if (logEntry.iteration == subgraph_idx + 1) {
          // The previous entry marks the last with subgraph_idx-1.
          s.range_prevIter_min = s.range_curIter_min;
          s.range_prevIter_max = i - 1;
        } else {
          assert (logEntry.iteration > subgraph_idx);
          /* The previous entry is from an older iteration,
           * and thus has no direct edges to the current entry iteration.*/
          s.range_prevIter_min = -1;
          s.range_prevIter_max = -1;
        }
        s.range_curIter_min = i;

        // Enter subgraph.
        subgraph_idx = logEntry.iteration;
        writer.write(s.indent.repeat(s.numIndent) + String.format("subgraph itercluster_%d { rankdir=LR; \n", subgraph_idx));
        suffix = "";
        suffix += s.indent.repeat(s.numIndent + 1) + String.format("rankdir=TB; iter_%d [style=invisible];\n", subgraph_idx);
        suffix += s.indent.repeat(s.numIndent) + "}\n";
        s.numIndent++;
        // Set cluster flag of subgraph.
        writer.write(s.indent.repeat(s.numIndent) + String.format("iter_%d_ltrdummy [style=invisible];\n", subgraph_idx));
        writer.write(s.indent.repeat(s.numIndent) + "cluster=true;\n");
      }
      updateTargetMap.put(logEntry.builderEntry, logEntry);

      // Format a node label.
      String nodeName = makeNodeName.apply(logEntry);
      String outputsStr = logEntry.built.outputs.stream()
                              .filter(output -> output.getExpressionType() != ExpressionType.ModuleOutput)
                              .map(output
                                   -> output.getKey().toString() +
                                          ((output.getExpressionType() == ExpressionType.ModuleInput) ? " (from module input)" : ""))
                              .reduce("", (a, b) -> a + (a.isEmpty() || b.isEmpty() ? "" : ", ") + b);
      if (!outputsStr.isEmpty())
        outputsStr = "\nOutputs: " + outputsStr;
      String moduleOutputsStr = logEntry.built.outputs.stream()
                                    .filter(output -> output.getExpressionType() == ExpressionType.ModuleOutput)
                                    .map(output -> output.getKey().toString())
                                    .reduce("", (a, b) -> a + (a.isEmpty() || b.isEmpty() ? "" : ", ") + b);
      if (!moduleOutputsStr.isEmpty())
        moduleOutputsStr = "\nAdds Module Outputs: " + moduleOutputsStr;
      String missingStr = logEntry.missingDependencies.stream()
                              .map(key -> key.toString())
                              .reduce("", (a, b) -> a + (a.isEmpty() || b.isEmpty() ? "" : ", ") + b);
      if (!missingStr.isEmpty())
        missingStr = "\nMissing: " + missingStr;
      String nodeLabel = String.format("Iter %d %s%s%s%s", logEntry.iteration, logEntry.builderEntry.builder.toString(), moduleOutputsStr,
                                       outputsStr, missingStr);

      // Add node name to subgraph.
      writer.write(s.indent.repeat(s.numIndent) + String.format("%s [label=\"%s\"];\n", nodeName, nodeLabel));

      // Enforce ordering between nodes in this subgraph
      String leftNodeName =
          (i > s.range_curIter_min) ? makeNodeName.apply(buildLog.get(i - 1)) : String.format("iter_%d_ltrdummy", logEntry.iteration);
      writer.write(s.indent.repeat(s.numIndent) + String.format("%s -> %s [style = invis];\n", leftNodeName, nodeName));
    }
    if (!suffix.isEmpty()) {
      writer.write(suffix);
      writer.write("\n");
      s.numIndent--;
    }
    IOException e = emitEdges.get();
    if (e != null)
      throw e;

    writer.write("}");
  }
}
