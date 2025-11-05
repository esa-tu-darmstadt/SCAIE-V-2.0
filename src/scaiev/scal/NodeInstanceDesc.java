package scaiev.scal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterators;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineStage;

/**
 * Description of an instantiated SCAIE-V node, with an HDL expression that evaluates to the node value.
 */
public class NodeInstanceDesc {
  /** An object identifying how a node should be generated/used (changing priority or semantics on the otherwise same key) */
  public static class Purpose {
    private static final AtomicInteger nextValue = new AtomicInteger();

    private final int key;
    private final String name;
    private final boolean canRegister;
    private final List<Map.Entry<Purpose, Integer>> matchesOthers;
    private final Optional<Purpose> registerAs;
    private final int maxPriority;

    /**
     * Instantiates a Purpose object. Only one such object should be generated per intended Purpose value.
     * @param name Human-readable name of the Purpose. For practical reasons, should be unique across all Purpose objects.
     * @param canRegister Set to true iff a NodeInstanceDesc with this Purpose is allowed to be passed to registration.
     * @param registerAs The Purpose to replace this with if a NodeInstanceDesc is registered with this. Set to empty to keep.
     * @param matchesOthers An exhaustive collection of Purpose-priority(Integer) pairs.
     *        If a given pair has a non-zero priority, `this` will match another non-equal Purpose with the given match priority.
     *        The priority values should be kept fairly low (but positive).
     */
    public Purpose(String name, boolean canRegister, Optional<Purpose> registerAs, Collection<Map.Entry<Purpose, Integer>> matchesOthers) {
      this.key = nextValue.getAndIncrement();
      this.name = name;
      this.canRegister = canRegister;
      // Makes sure by construction that registerAs points to a leaf (registerAs.registerAs does not point to something else).
      this.registerAs = registerAs.map(registerAsPurpose -> registerAsPurpose.getRegisterAs());
      this.matchesOthers = new ArrayList<>(matchesOthers);
      this.maxPriority = this.matchesOthers.stream().map(entry -> entry.getValue()).max(Integer::compare).orElse(0);
      if (this.matchesOthers.stream().anyMatch(entry -> entry.getValue() <= 0)) {
        throw new IllegalArgumentException("All priority values must be positive, non-zero");
      }
    }

    /**
     * Retrieves a read-only List of Purpose-priority(Integer) match pairs, excluding this.
     * @return a read-only match pair list
     */
    public List<Map.Entry<Purpose, Integer>> getMatchesOthers() { return Collections.unmodifiableList(matchesOthers); }

    /**
     * Tests if this Purpose matches another (i.e. this Purpose is at least as general as other).
     * @param other the other Purpose
     * @return the match result
     */
    public boolean matches(Purpose other) { return matchPriority(other) != 0; }
    /**
     * Gets the match priority if this Purpose matches another (see {@link Purpose#matches(Purpose)}), else returns zero.
     * If the Purpose objects are equal, returns 100.
     * @param other the other Purpose
     * @return the match priority
     */
    public int matchPriority(Purpose other) {
      return (key == other.key ? 100
                               : this.matchesOthers.stream()
                                     .filter(entry -> entry.getKey().key == other.key)
                                     .findFirst()
                                     .map(entry -> entry.getValue())
                                     .orElse(0));
    }
    /**
     * Gets the maximum priority value that matchPriority can output (excluding the '100' special value for exact matches).
     * @return the highest priority for non-exact matches
     */
    public int getMaxPriority() { return maxPriority; }
    /**
     * Specific alternative to Object.equals.
     * @param other the other Purpose
     * @return the comparison result
     */
    public boolean equals(Purpose other) { return other != null && key == other.key; }

    /**
     * Returns true iff a NodeInstanceDesc with this Purpose is allowed to be passed to registration.
     * @return the flag
     */
    public boolean getRegisterAllowed() { return canRegister; }
    /**
     * Returns the name of this Purpose object.
     * @return the name string
     */
    public String getName() { return this.name; }
    /**
     * Returns the Purpose to replace this with during NodeInstanceDesc registration.
     * @return the register-as Purpose
     */
    public Purpose getRegisterAs() { return registerAs.orElse(this); }

    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      return key == ((Purpose)obj).key;
    }
    @Override
    public int hashCode() {
      return Long.hashCode(key);
    }
    @Override
    public String toString() {
      return String.format("%s(%d)", this.name, this.key);
    }

    private static class WithMinPriorityKey {
      Purpose base;
      int minPriority;
      public WithMinPriorityKey(Purpose base, int minPriority) {
        this.base = base;
        this.minPriority = minPriority;
      }
      @Override
      public int hashCode() {
        return Objects.hash(base, minPriority);
      }
      @Override
      public boolean equals(Object obj) {
        if (this == obj)
          return true;
        if (obj == null)
          return false;
        if (getClass() != obj.getClass())
          return false;
        WithMinPriorityKey other = (WithMinPriorityKey)obj;
        return Objects.equals(base, other.base) && minPriority == other.minPriority;
      }
    }

    private static final ConcurrentHashMap<WithMinPriorityKey, Purpose> existingMinPriorityInstances = new ConcurrentHashMap<>();

    /**
     * Creates a Purpose from an existing base, matching only from a given minimal priority and base itself.
     * Matches base with base.maxPriority.
     * 
     * @param base existing Purpose
     * @param minPriority minimum priority to match
     * @return a new Purpose
     */
    public static Purpose withMinPriority(Purpose base, int minPriority) {
      if (minPriority < 0)
        throw new IllegalArgumentException("minPriority should be >= 0");
      if (minPriority == 0)
        return base;
      if (minPriority > base.maxPriority)
        minPriority = base.maxPriority + 1;
      final int minPriority_ = minPriority;
      return existingMinPriorityInstances.computeIfAbsent(new WithMinPriorityKey(base, minPriority), key_ -> {
        if (minPriority_ == base.maxPriority + 1) {
          return new Purpose(String.format("%s[exact]", base.name), base.canRegister && base.getRegisterAs().equals(base),
                             Optional.of(base), List.of(Map.entry(base, (int)1)));
        }
        if (minPriority_ > base.maxPriority) {
          return new Purpose("None", false, Optional.empty(), List.of());
        }
        return new Purpose(String.format("%s[priority>=%d]", base.name, minPriority_),
                           base.canRegister && base.matchPriority(base.getRegisterAs()) >= minPriority_,
                           (base.canRegister && base.matchPriority(base.getRegisterAs()) >= minPriority_)
                               ? Optional.of(base.getRegisterAs())
                               : Optional.empty(),
                           Stream
                               .concat(
                                   // Give maximum priority to base
                                   Stream.of(Map.entry(base, base.maxPriority + 1)),
                                   // Filter existing entries from base by minimum priority
                                   base.matchesOthers.stream().filter(other -> other.getValue() >= minPriority_))
                               .toList());
      });
    }

    /**
     * Latched/buffered values with a combinational bypass.
     * Common use case is input FIFOs for incoming requests that may still have a refined condition over the {@link Purpose#WIREDIN} value.
     */
    public static final Purpose REGULAR_LATCHING = new Purpose("REGULAR_LATCHING", true, Optional.empty(), List.of());
    /** Standard Purpose, used for most nodes with computation within the stage. */
    public static final Purpose REGULAR = new Purpose("REGULAR", true, Optional.empty(), List.of());
    /** Default value in case an external module input ({@link Purpose#WIREDIN}) does not exist. */
    public static final Purpose WIREDIN_FALLBACK = new Purpose("WIREDIN_FALLBACK", true, Optional.empty(), List.of());
    /** Unprocessed value from an external module. */
    public static final Purpose WIREDIN =
        new Purpose("WIREDIN", true, Optional.empty(), List.of(Map.entry(Purpose.WIREDIN_FALLBACK, (int)1)));
    /** Value piped in to a stage. Use in queries if the caller is *certain* the value comes from a previous stage. */
    public static final Purpose PIPEDIN = new Purpose("PIPEDIN", true, Optional.empty(), List.of());
    /**
     * Value to pipe out of a stage. For pipelining, a stage should request its predecessors' PIPEOUT and not match REGULAR or PIPEIN
     * instances.
     */
    public static final Purpose PIPEOUT = new Purpose("PIPEOUT", true, Optional.empty(), List.of());
    /** Value to use for outputs to external modules. */
    public static final Purpose WIREOUT = new Purpose("WIREOUT", true, Optional.empty(), List.of());

    /** Value registered in the context of a stage, intended for handling shared access to a stage's resources. */
    public static final Purpose REGISTERED = new Purpose("REGISTERED", true, Optional.empty(), List.of());

    /** Marker node to indicate/request presence of a SCAL->Core interface node, value to be ignored. */
    public static final Purpose MARKER_TOCORE_PIN = new Purpose("HASTOCORE", true, Optional.empty(), List.of());
    /** Marker node to indicate/request presence of a Core->SCAL interface node, value to be ignored. */
    public static final Purpose MARKER_FROMCORE_PIN = new Purpose("HASFROMCORE", true, Optional.empty(), List.of());
    /** Marker node to indicate/request presence of a SCAL->ISAX interface node, value to be ignored. */
    public static final Purpose MARKER_TOISAX_PIN = new Purpose("HASTOISAX", true, Optional.empty(), List.of());
    /** Marker node to indicate/request presence of a ISAX->SCAL interface node, value to be ignored. */
    public static final Purpose MARKER_FROMISAX_PIN = new Purpose("HASFROMISAX", true, Optional.empty(), List.of());
    /**
     * Marker node to request the implementation handling an internal SCAL logic output, value to be ignored.
     * Intended for default implementations, where a feature is not explicitly provided by a core. In that case, replaces {@link
     * Purpose#MARKER_TOCORE_PIN}.
     */
    public static final Purpose MARKER_INTERNALIMPL_PIN = new Purpose("HASINTERNALIMPL", true, Optional.empty(), List.of());
    /** Marker node to indicate/request presence of a custom register, value to be ignored. */
    public static final Purpose MARKER_CUSTOM_REG = new Purpose("CUSTOMREG", true, Optional.empty(), List.of());

    /** Purpose for HDL modules. The expression of an HDL_MODULE output is the module name. */
    public static final Purpose HDL_MODULE = new Purpose("HDL_MODULE", true, Optional.empty(), List.of());

    /**
     * Matches Purpose.WIREDIN (highest priority) and Purpose.PIPEDIN (lowest priority).
     * Cannot be used for NodeInstanceDesc registration.
     **/
    public static final Purpose match_WIREDIN_OR_PIPEDIN = new Purpose(
        "match_WIREDIN_OR_PIPEDIN", false, Optional.empty(),
        List.of(Map.entry(Purpose.WIREDIN, (int)3), Map.entry(Purpose.WIREDIN_FALLBACK, (int)2), Map.entry(Purpose.PIPEDIN, (int)1)));
    /**
     * Matches {@link Purpose#REGULAR} (highest priority) and {@link Purpose#WIREDIN} and {@link Purpose#PIPEDIN} (lowest priority).
     * If used for NodeInstanceDesc registration, will be changed to Purpose.REGULAR.
     * NodeRegistry uses the priorities to
     * a) request instantiation for a node key with a higher-priority registered Purpose
     *   (e.g. if PIPEDIN is available, to also request WIREDIN and/or REGULAR as a pseudo-'required dependency')
     * b) differentiate between processing stages of a node
     *   e.g. the PIPEDIN node may be directly taken from a previous stage,
     *        lacking combinational additions from the current stage only added to the REGULAR node.
     **/
    public static final Purpose match_REGULAR_WIREDIN_OR_PIPEDIN_NONLATCH = new Purpose(
        "match_REGULAR_WIREDIN_OR_PIPEDIN_NONLATCH", true, Optional.of(Purpose.REGULAR),
        Stream.concat(Stream.of(Map.entry(Purpose.REGULAR, (int)4)), match_WIREDIN_OR_PIPEDIN.getMatchesOthers().stream()).toList());
    /**
     * Matches {@link Purpose#REGULAR_LATCHING} (highest priority) and then everything from {@link
     * Purpose#match_REGULAR_WIREDIN_OR_PIPEDIN_NONLATCH}.
     */
    public static final Purpose match_REGULAR_WIREDIN_OR_PIPEDIN =
        new Purpose("match_REGULAR_WIREDIN_OR_PIPEDIN", true, Optional.of(Purpose.REGULAR),
                    Stream
                        .concat(Stream.of(Map.entry(Purpose.REGULAR_LATCHING, (int)5)),
                                match_REGULAR_WIREDIN_OR_PIPEDIN_NONLATCH.getMatchesOthers().stream())
                        .toList());
    /** Matches WIREOUT (highest priority) and then everything from match_REGULAR_WIREDIN_OR_PIPEDIN. */
    public static final Purpose match_WIREOUT_OR_default = new Purpose(
        "match_WIREOUT_OR_default", true, Optional.of(Purpose.REGULAR),
        Stream.concat(Stream.of(Map.entry(Purpose.WIREOUT, (int)6)), match_REGULAR_WIREDIN_OR_PIPEDIN.getMatchesOthers().stream())
            .toList());
  }
  /**
   * A wrapper that tracks ISAXes (and potentially other entities) a node is created for.
   * Transparently keeps references to other sets, as needed.
   */
  public static class RequestedForSet {
    /** A read-only empty set */
    public static final RequestedForSet empty = new RequestedForSet(new RequestedForSet());

    Set<String> fromISAX = new HashSet<>();
    List<RequestedForSet> otherRefs = new ArrayList<>();
    // Used as duplicate lookup if otherRefs grows beyond a threshold
    Set<RequestedForSet> otherRefs_set = null;

    /** An empty RequestedForSet */
    public RequestedForSet() {}
    /**
     * A RequestedForSet, initially containing an ISAX (if non-empty)
     * @param isax the ISAX name (or an empty string)
     */
    public RequestedForSet(String isax) {
      if (!isax.isEmpty())
        addRelevantISAX(isax);
    }
    /** Private constructor for a read-only view to `from`. */
    private RequestedForSet(RequestedForSet from) {
      fromISAX = Collections.unmodifiableSet(from.fromISAX);
      otherRefs = Collections.unmodifiableList(from.otherRefs);
    }

    /**
     * Creates a flattened Iterator of RequestedForSet including this and this.otherRefs, recursively.
     * Does not return any duplicates. Handles cycles gracefully.
     * @return the flattened RequestedForSet subgraph Iterator
     */
    private Iterator<RequestedForSet> allSetsToEvaluate() {
      ////Stream-based implementation. Ignores cycles,
      //// but runtime can still explode in cases where neighbors point to each other on several hierarchy levels.
      // var parents_ = new ArrayList<>(parents);
      // parents_.add(this);
      // return Stream.concat(
      //	Stream.of(this),
      //	otherRefs.stream().flatMap(otherSet -> parents_.contains(otherSet) ? Stream.empty() : otherSet.allSetsToEvaluate(parents_))
      //);
      // Iterator-based implementation, prevents any duplicate iteration.
      RequestedForSet start = this;
      return new Iterator<RequestedForSet>() {
        static class IterStackEntry {
          public IterStackEntry(RequestedForSet set) { this.set = set; }
          RequestedForSet set;
          int otherRefsIdx = 0;
        }
        HashSet<RequestedForSet> seenBefore = new HashSet<>();
        List<IterStackEntry> iterStack = new ArrayList<>(List.of(new IterStackEntry(start)));
        RequestedForSet nextRet = start;

        private void findNext() {
          if (nextRet != null)
            return;
          while (!iterStack.isEmpty()) {
            var stackTop = iterStack.get(iterStack.size() - 1);
            if (stackTop.otherRefsIdx >= stackTop.set.otherRefs.size()) {
              // Finished iterating through entry.
              iterStack.remove(iterStack.size() - 1);
              continue;
            }
            nextRet = stackTop.set.otherRefs.get(stackTop.otherRefsIdx++);
            if (seenBefore.add(nextRet)) {
              // Found a new sub-set for nextRet.
              // Add it to the stack for future iterations.
              iterStack.add(new IterStackEntry(nextRet));
              break;
            }
            nextRet = null;
          }
        }

        @Override
        public boolean hasNext() {
          findNext();
          return (nextRet != null);
        }

        @Override
        public RequestedForSet next() {
          findNext();
          var ret = this.nextRet;
          this.nextRet = null;
          if (ret == null)
            throw new NoSuchElementException();
          return ret;
        }
      };
    }

    /**
     * Returns a set of ISAX names that are relevant for a node instance.
     * The returned set is either a read-only view to this object's internal state, or an entirely new Set.
     * @return a set of relevant ISAX names
     */
    public Set<String> getRelevantISAXes() {
      if (otherRefs.isEmpty())
        return Collections.unmodifiableSet(fromISAX);
      HashSet<String> ret = new HashSet<>();
      var setsIter = allSetsToEvaluate();
      while (setsIter.hasNext())
        ret.addAll(setsIter.next().fromISAX);
      return ret;
    }
    /**
     * Adds the given name of an ISAX relevant to a node instance to the internal tracking set.
     * @param isax the ISAX to add
     * @return true iff the internal set has changed
     */
    public boolean addRelevantISAX(String isax) { return fromISAX.add(isax); }
    /**
     * Adds the given names of ISAXes relevant to a node instance to the internal tracking set.
     * @param isaxes the ISAXes to add
     * @return true iff the internal set has changed
     */
    public boolean addRelevantISAXes(Collection<String> isaxes) { return fromISAX.addAll(isaxes); }

    /**
     * Indirectly combines this with another RequestedForSet, adding a read-only reference to other.
     * Note: Always adds the reference, thus always returns true.
     * @param other the set to add
     * @param lazy if true, the other set will be kept as a reference and evaluated lazily; if lazy is set, addAll will always return true
     * @return true iff this object has changed
     */
    public boolean addAll(RequestedForSet other, boolean lazy) {
      if (lazy) {
        // Only add a reference to the other set if it is not already present.
        boolean doAdd;
        if (otherRefs.size() > 10) {
          // At some arbitrary point, use a HashSet for duplicate checking.
          if (otherRefs_set == null)
            otherRefs_set = new HashSet<>(otherRefs);
          doAdd = otherRefs_set.add(other);
        } else
          doAdd = !otherRefs.contains(other);
        if (doAdd)
          otherRefs.add(other);
        return true;
      }
      return addRelevantISAXes(other.getRelevantISAXes());
    }

    /**
     * Generates an unmodifiable view of this
     * @return an unmodifiable view of this
     */
    public RequestedForSet asUnmodifiableView() { return new RequestedForSet(this); }

    /** Clears this set */
    public void clear() {
      fromISAX.clear();
      otherRefs.clear();
      otherRefs_set = null;
    }

    /**
     * Emptiness check that does not include references.
     * @return true iff empty
     */
    protected boolean isEmpty_nonrecursive() { return fromISAX.isEmpty(); }
    /**
     * Determines whether this set is empty
     * @return whether this set is empty
     */
    public boolean isEmpty() {
      var setsIter = allSetsToEvaluate();
      while (setsIter.hasNext()) {
        if (!setsIter.next().isEmpty_nonrecursive())
          return false;
      }
      return true;
    }
  }
  /** An identifier for nodes in logic generation, unique at any given generation step */
  public static class Key {
    SCAIEVNode node;
    Purpose purpose = Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN;
    PipelineStage stage;
    /**
     * ISAX or internal node-specific string as part of the key.
     * null is reserved as a wildcard for NodeRegistry.anonymizeKey.
     */
    String isax;
    /**
     * Additional internal node-specific int value as part of the key.
     *  Integer.MIN_VALUE is reserved as a wildcard for NodeRegistry.anonymizeKey.
     */
    int aux = 0;

    /**
     * See {@link Key#Key(Purpose, SCAIEVNode, PipelineStage, String, int)}. Uses purpose={@link Purpose#match_REGULAR_WIREDIN_OR_PIPEDIN},
     * aux=0.
     * @param node the node
     * @param stage the stage
     * @param isax the ISAX (or "")
     */
    public Key(SCAIEVNode node, PipelineStage stage, String isax) {
      this.node = node;
      this.stage = stage;
      this.isax = isax;
    }
    /**
     * See {@link Key#Key(Purpose, SCAIEVNode, PipelineStage, String, int)}. Uses aux=0.
     * @param purpose the Purpose
     * @param node the node
     * @param stage the stage
     * @param isax the ISAX (or "")
     */
    public Key(Purpose purpose, SCAIEVNode node, PipelineStage stage, String isax) {
      this(node, stage, isax);
      this.purpose = purpose;
    }
    /**
     * Instantiates a Key for use in queries or NodeInstanceDesc registration.
     * @param purpose the Purpose that defines how to interpret the Key (will be overwritten with {@link Purpose#getRegisterAs()} during
     *     registration)
     * @param node the node
     * @param stage the stage
     * @param isax the ISAX (or "")
     * @param aux the aux value (or 0) to identify internal sub-nodes
     */
    public Key(Purpose purpose, SCAIEVNode node, PipelineStage stage, String isax, int aux) {
      this(purpose, node, stage, isax);
      this.aux = aux;
    }
    /**
     * Creates a Key from an existing Key while setting a new Purpose value.
     * @param existing the existing Key
     * @param newPurpose the new Purpose
     * @return a new Key
     */
    public static Key keyWithPurpose(Key existing, Purpose newPurpose) {
      return new Key(newPurpose, existing.getNode(), existing.getStage(), existing.getISAX(), existing.getAux());
    }
    /**
     * Creates a Key from an existing Key while setting new Purpose and aux values.
     * 
     * @param existing the existing Key
     * @param newPurpose the new Purpose
     * @param aux the new aux value
     * @return a new Key
     */
    public static Key keyWithPurposeAux(Key existing, Purpose newPurpose, int aux) {
      return new Key(newPurpose, existing.getNode(), existing.getStage(), existing.getISAX(), aux);
    }
    /**
     * @return the node
     */
    public SCAIEVNode getNode() { return node; }
    /**
     * @return the Purpose
     */
    public Purpose getPurpose() { return purpose; }
    /**
     * @return the stage
     */
    public PipelineStage getStage() { return stage; }
    /**
     * @return the ISAX
     */
    public String getISAX() { return isax; }
    /**
     * @return the aux value
     */
    public int getAux() { return aux; }

    /**
     * Matches against another non-null Key.
     * Similar to {@link Key#equals(Object)}, but using {@link Purpose#matches(Purpose)} instead of {@link Purpose#equals(Object)}.
     * Also supports wildcards for `this.isax` and `this.aux`. Wildcards in `other` are only matched by wildcards in `this`.
     * @param other the other Key
     * @return the match result
     **/
    public boolean matches(Key other) {
      return Objects.equals(node, other.node) && purpose.matches(other.purpose) && Objects.equals(stage, other.stage) &&
          (isax == null || Objects.equals(isax, other.isax)) && (aux == Integer.MIN_VALUE || aux == other.aux);
    }

    @Override
    public int hashCode() {
      return Objects.hash(node, isax, stage, aux);
    }
    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      Key other = (Key)obj;
      return Objects.equals(node, other.node) && Objects.equals(purpose, other.purpose) && Objects.equals(stage, other.stage) &&
          Objects.equals(isax, other.isax) && aux == other.aux;
    }
    /**
     * @param includePurpose whether to include the Purpose name (separated with ~)
     * @return a string representation
     */
    public String toString(boolean includePurpose) {
      return (includePurpose ? (purpose.toString() + "~") : "") + node.name +
          (isax == null ? "(_*)?" : (isax.isEmpty() ? "" : ("_" + isax))) + "_" +
          stage.getGlobalStageID().map(id -> "" + id).orElse(stage.getName()) +
          (aux == Integer.MIN_VALUE ? "(_*)?" : (aux == 0 ? "" : ("_" + aux)));
    }
    @Override
    public String toString() {
      return toString(true);
    }
  }
  Key key;

  /** The type of a string expression, relevant for uniqueness (wire names, ports) and expression composition */
  public enum ExpressionType {
    /** An HDL expression; no new signal name is defined for this field */
    AnyExpression(true),
    /** An HDL expression that does not need additional parentheses; no new signal name is defined for this field */
    AnyExpression_Noparen(false),
    /** A wire name; must be unique across nodes (including {@link #ModuleInput} and {@link #ModuleOutput}) */
    WireName(false),
    /** A module input name; must be unique across nodes (including {@link #WireName} and {@link #ModuleOutput}) */
    ModuleInput(false),
    /**
     * A module output name, used for interface housekeeping but cannot be used as inputs to other nodes;
     * must be unique across nodes (including {@link #WireName} and {@link #ModuleInput} expressions)
     */
    ModuleOutput(false);

    /**
     * Set if an expression of this type may be a composite expression without outer parentheses
     * and may thus need to be embedded in parentheses depending on the context
     */
    public final boolean mayBeComposite;

    private ExpressionType(boolean mayBeComposite) { this.mayBeComposite = mayBeComposite; }
  }

  String expression;

  ExpressionType expressionType;

  RequestedForSet requestedFor = new RequestedForSet();

  /**
   * Constructor. Uses a caller-specified RequestedForSet.
   * 
   * NOTE: Make sure that repeat builder invocations (that create new NodeInstanceDescs for the same key) keep the same requestedFor object.
   *       Changes in the requestedFor object instance do not mark dependant builders (that queried an older instance of this node) as dirty.
   *       A dependant builder thus is not guaranteed to see the new RequestedForSet objects, hindering the intended upstream/downstream travel.
   * @param key the key
   * @param expression the expression string
   * @param expressionType the expression type (must be set carefully to prevent building/HDL errors)
   * @param requestedFor the RequestedForSet for this object
   */
  public NodeInstanceDesc(Key key, String expression, ExpressionType expressionType, RequestedForSet requestedFor) {
    this.key = key;
    this.expression = expression;
    this.expressionType = expressionType;
    this.requestedFor = requestedFor;
  }

  /**
   * Constructor. Creates a new RequestedForSet with the ISAX from key (if present).
   * NOTE: If the ISAX field is used for a node-specific purpose,
   *       the caller should provide an empty RequestedForSet to the constructor variant.
   * NOTE: If the RequestedForSet values added by dependant nodes are relevant for this node,
   *       the caller should use the variant of the constructor with a fixed RequestedForSet instead.
   * @param key the key
   * @param expression the expression string
   * @param expressionType the expression type (must be set carefully to prevent building/HDL errors)
   */
  public NodeInstanceDesc(Key key, String expression, ExpressionType expressionType) {
    this.key = key;
    this.expression = expression;
    this.expressionType = expressionType;
    if (key.getISAX() != null && !key.getISAX().isEmpty())
      requestedFor.fromISAX.add(key.getISAX());
  }

  /**
   * Retrieves the key that this object instantiates.
   * @return the key
   */
  public Key getKey() { return key; }

  /**
   * Retrieves the HDL expression that evaluates to the node value.
   * @return the expression string
   */
  public String getExpression() { return expression; }

  /**
   * Retrieves the HDL expression that evaluates to the node value,
   *  embedded in parentheses '()' if possibly needed.
   * @return the expression string with parantheses if composite
   */
  public String getExpressionWithParens() {
    if (expressionType.mayBeComposite)
      return "(" + expression + ")";
    return expression;
  }

  /**
   * Returns the {@link ExpressionType} for this node expression.
   * @return the expression type
   */
  public ExpressionType getExpressionType() { return expressionType; }

  /**
   * Returns the internal tracking set of entities (e.g. ISAXes) that this node instance is relevant for.
   * The returned object is an unmodifiable view into the actual object.
   * @return a read-only view into the RequestedForSet
   */
  public RequestedForSet getRequestedFor() { return this.requestedFor.asUnmodifiableView(); }

  /**
   * Merges the internal tracking set of relevant entities (e.g. ISAXes) with another.
   * @param otherRequestedFor the set to add
   * @param lazy if true, the other set will be evaluated lazily and included as a reference;
   *        reference cycles are allowed (and, in some cases, encouraged when dealing with interdependent NodeInstanceDescs)
   * @return true iff the internal tracking set may have changed
   */
  public boolean addRequestedFor(RequestedForSet otherRequestedFor, boolean lazy) {
    return this.requestedFor.addAll(otherRequestedFor, lazy);
  }

  @Override
  public int hashCode() {
    return key.hashCode();
  }
  @Override
  public boolean equals(Object obj) {
    if (this == obj)
      return true;
    if (obj == null)
      return false;
    if (getClass() != obj.getClass())
      return false;
    NodeInstanceDesc other = (NodeInstanceDesc)obj;
    return Objects.equals(key, other.key) && Objects.equals(expression, other.expression) && expressionType == other.expressionType;
    //&& requestedFor == other.requestedFor;
  }

  @Override
  public String toString() {
    return key.toString() + " = " + expressionType.toString() + "(" + expression + ")";
  }
}
