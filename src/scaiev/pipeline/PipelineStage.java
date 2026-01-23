package scaiev.pipeline;

import java.io.Serializable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import scaiev.pipeline.PipelineStage.MultiportPipeAttributes;
import scaiev.pipeline.PipelineStage.StageTag;

/**
 * Represents an individual pipeline stage as part of a linked pipeline graph.
 * Note: This pipeline is from the view of SCAIE-V and may be
 *       incomplete w.r.t. the actual processor pipeline,
 *       and may also contain additional decoupled stages or sub-stages.
 */
public class PipelineStage {
  private static final AtomicLong instanceCounter = new AtomicLong();
  private final long instanceID;

  @Override
  public String toString() {
    return String.format("PipelineStage %s\"%s\" (%d)", globalStageID.isPresent() ? String.format("%d-", globalStageID.get()) : "", name,
                         instanceID);
  }

  public static record TagAttrPair(StageTag tag, Object attr) {}

  public PipelineStage(StageKind kind, List<TagAttrPair> tagsAndAttributes, String name, Optional<Integer> globalStageID, boolean continuous) {
    this.kind = kind;
    this.tags = EnumSet.noneOf(StageTag.class);
    this.attributes = new HashMap<>();
    tagsAndAttributes.forEach(entry -> this.addTagAttr(entry.tag, entry.attr));
    this.name = name;
    this.globalStageID = globalStageID;
    this.continuous = continuous;
    this.instanceID = instanceCounter.incrementAndGet();
  }

  /**
   * Returns a value unique across all PipelineStage objects (in the same class loader), which can be used as a sort key,
   * e.g. if two PipelineStage lists need to be in the same order.
   * The order is based on object construction and may change between subsequent runs;
   * no guarantee is made to how stages with different keys relate to another.
   */
  public long getSortKey() { return instanceID; }

  /**
   * Constructs a linear, continuous in-order pipeline with simple index names.
   * Appropriate for most single-issue in-order cores.
   * @param depth number of stages that are in a row
   * @param firstGlobalStageID optional: global stage ID for the first stage, will be incremented for each successor
   */
  public static PipelineStage constructLinearContinuous(StageKind kind, int depth, Optional<Integer> firstGlobalStageID) {
    if (depth <= 0)
      throw new IllegalArgumentException("depth must be above zero");
    var pipeline = new ArrayList<PipelineStage>(depth);
    for (int i = 0; i < depth; ++i) {
      int i_ = i;
      var newStage = new PipelineStage(kind, List.of(new TagAttrPair(StageTag.InOrder, null)), "" + i, firstGlobalStageID.map(globalID -> globalID + i_), true);
      if (i > 0)
        pipeline.get(i - 1).addNext(newStage);
      pipeline.add(newStage);
    }
    return pipeline.get(0);
  }

  private void fixSuccessorStagePos() {
    if (this.stagePos == Integer.MAX_VALUE)
      return;
    // Possible optimization: Stop iterating along a stage if its stagePos was correct already.
    for (PipelineStage curStage : this.iterableNext_bfs()) {
      // BFS from root stage -> iteration order is by monotonic ascending depth
      //  -> can directly infer the depth from the predecessor with stagePos set already
      curStage.stagePos = curStage.prev.stream().mapToInt(prevStage -> prevStage.stagePos).min().orElse(-1);
      assert (curStage.stagePos != Integer.MAX_VALUE); // We must have gotten to this node by someone with stagePos initialized.
      ++curStage.stagePos;
    }
  }

  /**
   * Links a stage to this via the 'this.next' link.
   * @param newNext
   * @return newNext
   */
  public PipelineStage addNext(PipelineStage newNext) {
    // Hoping that the caller is careful enough to not produce cycles via newNext.next, newNext.children.
    assert (!this.next.contains(newNext));
    assert (!newNext.prev.contains(this));

    this.next.add(newNext);
    newNext.prev.add(this);
    int stagePos_fromthis = (this.stagePos == Integer.MAX_VALUE) ? Integer.MAX_VALUE : (this.stagePos + 1);
    if (newNext.stagePos > stagePos_fromthis) {
      newNext.stagePos = stagePos_fromthis;
      newNext.fixSuccessorStagePos();
    }
    for (PipelineStage nextStage : newNext.iterableNext_bfs(nextStage -> !nextStage.parent.equals(this.parent))) {
      nextStage.parent = this.parent;
    }
    return newNext;
  }

  /**
   * Links a sub-pipeline to this via the 'this.children' link.
   * @param newChild
   * @return newChild
   */
  public PipelineStage addChild(PipelineStage newChild) {
    for (PipelineStage curChildStage : newChild.iterableNext_bfs()) {
      assert (curChildStage.parent.isEmpty());
      if (curChildStage.parent.isPresent()) {
        throw new IllegalArgumentException("A stage in the given pipeline already has a parent");
      }
      curChildStage.stagePos = Integer.MAX_VALUE;
    }

    for (PipelineStage curChildStage : newChild.iterableNext_bfs()) {
      curChildStage.parent = Optional.of(this);
    }
    newChild.stagePos = 0;
    newChild.fixSuccessorStagePos();

    this.children.add(newChild);
    return newChild;
  }

  private Iterator<PipelineStage> iteratePrevOrNext_bfs(Predicate<PipelineStage> processSuccessors, boolean iterNext) {
    PipelineStage init = this;
    return new Iterator<PipelineStage>() {
      // The set specifying the current search window (by design only containing elements at depth i and possibly i+1)
      Deque<PipelineStage> front = new ArrayDeque<>(List.of(init));
      int next_idx = 0;
      Optional<PipelineStage> nextVal = Optional.of(init);
      // We have to tag all visited stages to prevent duplicate iteration.
      //(we cannot rely on optimizations like only going through the shortest paths via getStagePos() and testing in front
      //  since *this* may be somewhere in the middle of the graph;
      //  also, processSuccessors could block the shortest path either way)
      HashSet<PipelineStage> visitedSet = new HashSet<>();
      private void updateNext() {
        nextVal = Optional.empty();
        while (!front.isEmpty()) {
          nextVal = Optional.empty();
          // Continue processing the stage in front of <front> (say, depth i).
          PipelineStage cur = front.getFirst();
          List<PipelineStage> successors = iterNext ? cur.next : cur.prev;
          if (next_idx < successors.size()                      // if the index is within the current successors list range
              && (next_idx != 0 || processSuccessors.test(cur)) // invocate processSuccessors only once per stage (-> if next_idx == 0)
          ) {
            PipelineStage next = successors.get(next_idx);
            ++next_idx;
            if (!visitedSet.add(next))
              continue; // Prevent duplicate iteration
            nextVal = Optional.of(next);
            // Add the successor at the end of <front> (the successor has depth i+1).
            front.addLast(nextVal.get());
            break;
          } else {
            // Remove the current stage from <front>.
            front.removeFirst();
            next_idx = 0;
          }
        }
      }
      public boolean hasNext() { return nextVal.isPresent(); }
      public PipelineStage next() {
        PipelineStage ret = nextVal.orElse(null);
        updateNext();
        return ret;
      }
    };
  }

  /**
   * Creates an iterator starting at this stage, continuing through the stages reachable via next in a breadth-first search.
   * @param processSuccessors predicate that indicates whether the next list of a given stage should be iterated through.
   */
  public Iterator<PipelineStage> iterateNext_bfs(Predicate<PipelineStage> processSuccessors) {
    return iteratePrevOrNext_bfs(processSuccessors, true);
  }
  /**
   * Creates an iterator starting at this stage, continuing through the stages reachable via next in a breadth-first search.
   */
  public Iterator<PipelineStage> iterateNext_bfs() { return iterateNext_bfs(stage -> true); }
  /**
   * Creates an Iterable using {@link PipelineStage#iterateNext_bfs(Predicate)}
   */
  public Iterable<PipelineStage> iterableNext_bfs(Predicate<PipelineStage> processSuccessors) {
    return new Iterable<PipelineStage>() {
      public Iterator<PipelineStage> iterator() { return iterateNext_bfs(processSuccessors); }
    };
  }
  /**
   * Creates an Iterable using {@link PipelineStage#iterateNext_bfs()}
   */
  public Iterable<PipelineStage> iterableNext_bfs() { return iterableNext_bfs(stage -> true); }
  /**
   * Creates a Stream using {@link PipelineStage#iterateNext_bfs(Predicate)}
   */
  public Stream<PipelineStage> streamNext_bfs(Predicate<PipelineStage> processSuccessors) {
    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(this.iterateNext_bfs(processSuccessors), 0), false);
  }
  /**
   * Creates a Stream using {@link PipelineStage#iterateNext_bfs()}.
   */
  public Stream<PipelineStage> streamNext_bfs() { return streamNext_bfs(stage -> true); }

  /**
   * Creates an iterator starting at this stage, continuing through the stages reachable via prev in a breadth-first search.
   * @param processPredecessors predicate that indicates whether the prev list of a given stage should be iterated through
   */
  public Iterator<PipelineStage> iteratePrev_bfs(Predicate<PipelineStage> processPredecessors) {
    return iteratePrevOrNext_bfs(processPredecessors, false);
  }
  /**
   * Creates an iterator starting at this stage, continuing through the stages reachable via next in a breadth-first search.
   */
  public Iterator<PipelineStage> iteratePrev_bfs() { return iteratePrev_bfs(stage -> true); }
  /**
   * Creates an Iterable using {@link PipelineStage#iteratePrev_bfs(Predicate)}
   */
  public Iterable<PipelineStage> iterablePrev_bfs(Predicate<PipelineStage> processPredecessors) {
    return new Iterable<PipelineStage>() {
      public Iterator<PipelineStage> iterator() { return iteratePrev_bfs(processPredecessors); }
    };
  }
  /**
   * Creates an Iterable using {@link PipelineStage#iteratePrev_bfs()}
   */
  public Iterable<PipelineStage> iterablePrev_bfs() { return iterablePrev_bfs(stage -> true); }
  /**
   * Creates a Stream using {@link PipelineStage#iteratePrev_bfs(Predicate)}
   */
  public Stream<PipelineStage> streamPrev_bfs(Predicate<PipelineStage> processPredecessors) {
    return StreamSupport.stream(Spliterators.spliteratorUnknownSize(this.iteratePrev_bfs(processPredecessors), 0), false);
  }
  /**
   * Creates a Stream using {@link PipelineStage#iteratePrev_bfs()}.
   */
  public Stream<PipelineStage> streamPrev_bfs() { return streamPrev_bfs(stage -> true); }

  /**
   * Returns a stream of all child stages with or after the given stage position.
   *  Can be used to construct a {@link PipelineFront}.
   * @param stagePosMin the minimum stage position to look for ({@link PipelineStage#getStagePos()})
   * @param stagePosMax the maximum (inclusive)
   */
  public Stream<PipelineStage> getChildrenByStagePos(int stagePosMin, int stagePosMax) {
    // Produce a stream across all children.
    return children.stream().flatMap(child
                                     -> // For each child, produce a sub-stream for the stage position.
                                     child.streamNext_bfs(stage -> stage.getStagePos() < stagePos)
                                         .filter(stage -> stage.getStagePos() >= stagePosMin && stage.getStagePos() <= stagePosMax));
  }
  /**
   * Returns a stream of all child stages with the given stage position.
   *  Can be used to construct a {@link PipelineFront}.
   * @param stagePos the exact stage position to look for ({@link PipelineStage#getStagePos()})
   */
  public Stream<PipelineStage> getChildrenByStagePos(int stagePos) { return getChildrenByStagePos(stagePos, stagePos); }
  /**
   * Returns a breadth-first stream of all stages across all children (where parent == this).
   */
  public Stream<PipelineStage> getAllChildren() {
    // Add a temporary stage with all children as successors.
    //  -> this PipelineStage does not adhere to the invariants
    //     enforced for any outwards-facing objects,
    //     e.g. its successors do not point back to it
    PipelineStage searchFront = new PipelineStage(StageKind.Core, List.of(), null, Optional.empty(), false);
    searchFront.next = children;
    // Produce a stream across all children (but not recursive children). Skip searchFront itself.
    return searchFront.streamNext_bfs().skip(1);
  }
  /**
   * Returns a breadth-first stream of all stages across all children and sub-children.
   * Note: In the stream, sub-pipelines are situated right after their parents.
   */
  public Stream<PipelineStage> getAllChildrenRecursive() {
    // Produce a stream across all children using flatMap and (pseudo-)recursion back into this function..
    return getAllChildren().flatMap(childStage -> Stream.concat(Stream.of(childStage), childStage.getAllChildrenRecursive()));
  }
  /**
   * Returns a stream of the tail-end stages across all children.
   *  Can be used to construct a {@link PipelineFront}.
   */
  public Stream<PipelineStage> getChildrenTails() {
    // Produce a stream across all children.
    return children.stream().flatMap(child
                                     -> // For each child, produce a sub-stream for the last stage.
                                     child.streamNext_bfs().filter(stage -> stage.next.isEmpty()));
  }

  /**
   * Determines whether a stage 'from' could pipe into a directly succeeding stage 'to'.
   * Also considers port -> (non-port|port) transitions.<br/>
   * {@link StageKind#CoreMultiport} super-stage to port transitions will return false,
   *   whereas non-port stage to port transitions will return true. <br/>
   * Transitions to {@link StageKind#CoreMultiport} stages are not allowed from port stages, but are allowed in general. <br/>
   * Background: Port stages have a {@link StageTag#MultiportPipe} attribute and a successor set from the parent {@link StageKind#CoreMultiport},
   *  and more complex rules on whether a stage is a direct successor or not.
   * @param to the regular or port stage to possibly 'pipe to'
   * @return true iff this may pipe (directly) into to, without other stages in-between
   */
  public boolean hasDirectPipeTo(PipelineStage to) {
    PipelineStage from = this;
    if (from.getNext().contains(to))
      return true; // Obvious direct connection (implies non-port on both ends)
    // No obvious from->to connection.
    if (to.getKind() == StageKind.CoreMultiport) {
      // a) from is port: Don't pipe from a port to a multiport super-stage.
      // b) from is non-port: Missing connection.
      return false;
    }
    if (from.getTags().contains(StageTag.MultiportPipe)) {
      //from is a port stage
      // -> Check for additional port-to-port or port-to-nonport cases
      MultiportPipeAttributes portFromAttr = from.getTagAttr(StageTag.MultiportPipe, MultiportPipeAttributes.class);
      assert(portFromAttr != null);
      assert(new PipelineFront(to).isAfter(from, false));
      
      if (to.getTags().contains(StageTag.MultiportPipe)) {
        //port stage -> port stage
        if (portFromAttr.directMapping()) {
          //direct mapping: Port N only pipes into successor CoreMultiport-stage's port N
          int portFromIdx = from.getParent().orElseThrow().getChildren().indexOf(from);
          assert(portFromIdx != -1);
          int portToIdx = to.getParent().orElseThrow().getChildren().indexOf(to);
          assert(portToIdx != -1);
          return portFromIdx == portToIdx;
        }
      }
      //port stage -> (port|non-port) stage
      return portFromAttr.explicitSuccessorStageNames().isEmpty()
          || portFromAttr.explicitSuccessorStageNames().contains(to.getName());
    }
    //'from' is a non-port stage, 'to' is a non-port|port stage that is not CoreMultiport
    //The only remaining scenario where we have a direct connection
    // is if 'to' is a port in a connected CoreMultiport stage.
    return to.getTags().contains(StageTag.MultiportPipe) && from.getNext().contains(to.getParent().orElseThrow());
  }
  /**
   * Resolves the Stream of effective successor or predecessor stages to ref, including transitions involving port stages.
   * @param ref the reference stage to go from
   * @param forward true: look for successors, otherwise: look for predecessors
   * @param listCoreMultiport if true, {@link StageKind#CoreMultiport} super-stages are also listed (but only if 'this' is not a port stage).
   * @return the found stages as a Stream
   */
  private static Stream<PipelineStage> resolveEffectivePrevOrNext(PipelineStage ref, boolean forward, boolean listCoreMultiport) {
    Stream<PipelineStage> cessorStream;
    if (ref.getTags().contains(StageTag.MultiportPipe)) {
      assert(ref.getPrev().isEmpty() && ref.getNext().isEmpty());
      PipelineStage refParent = ref.getParent().orElseThrow();
      cessorStream = (forward ? refParent.getNext() : refParent.getPrev()).stream();
    }
    else {
      cessorStream = (forward ? ref.getNext() : ref.getPrev()).stream();
    }
    // For CoreMultiport stages, consider its children as possible successors.
    cessorStream = cessorStream.flatMap(cessorStage -> cessorStage.getKind() == StageKind.CoreMultiport
                                             ? Stream.concat(listCoreMultiport ? Stream.of(cessorStage) : Stream.empty(),
                                                             cessorStage.getChildren().stream())
                                             : Stream.of(cessorStage));
    if (ref.getTags().contains(StageTag.MultiportPipe)) {
      // Apply the special-conditions for transitions from a port stage.
      cessorStream = forward ? cessorStream.filter(to -> ref.hasDirectPipeTo(to))
                             : cessorStream.filter(from -> from.hasDirectPipeTo(ref));
    }
    else {
      // There should be no special rules on which port of the successor to consider.
      // For debugging, inject some assertions into the Stream (no-op if assertions are disabled).
      cessorStream = cessorStream.map(cessor -> {
        assert(forward ? ref.hasDirectPipeTo(cessor) : cessor.hasDirectPipeTo(ref));
        return cessor;
      });
    }
    return cessorStream;
  }
  /**
   * Resolves the Stream of effective successor stages, including transitions involving port stages. <br/>
   * @param listCoreMultiport if true, {@link StageKind#CoreMultiport} super-stages are also listed (but only if 'this' is not a port stage).
   * @return the found stages as a Stream
   */
  public Stream<PipelineStage> resolveEffectiveNext(boolean listCoreMultiport) {
    return resolveEffectivePrevOrNext(this, true, listCoreMultiport);
  }
  /**
   * Resolves the Stream of effective predecessor stages, including transitions involving port stages. <br/>
   * @param listCoreMultiport if true, {@link StageKind#CoreMultiport} super-stages are also listed (but only if 'this' is not a port stage).
   * @return the found stages as a Stream
   */
  public Stream<PipelineStage> resolveEffectivePrev(boolean listCoreMultiport) {
    return resolveEffectivePrevOrNext(this, false, listCoreMultiport);
  }
  /**
   * For port stages (with a {@link StageTag#MultiportPipe} tag/attribute), returns the {@link StageKind#CoreMultiport} super-stage.
   * Otherwise returns the stage itself.
   */
  public PipelineStage getMultiportBase() {
    if (getTags().contains(StageTag.MultiportPipe)) {
      PipelineStage base = getParent().orElseThrow();
      //this is Core -> parent is CoreMultiport
      assert(getKind() != StageKind.Core || base.getKind() == StageKind.CoreMultiport);
      //currently, multiport is only supported for Core stages
      assert(getKind() == StageKind.Core);
      return base;
    }
    else if (getKind() == StageKind.ISAXMux)
      return getParent().orElseThrow();
    return this;
  }

  /** Categorization enum for true and synthetic PipelineStages */
  public enum StageKind {
    /** A stage from the core itself. Adheres to the constraints from the corresponding {@link scaiev.coreconstr.Core}. */
    Core("core"),
    /**
     * A stage from the core itself, with several Core-type ports as children.
     * Each child stage can house an instruction and has no successors.
     *  Stages of this kind must come with a {@link StageTag#MultiportStall} attribute tag.
     * Adheres to the constraints from the corresponding {@link scaiev.coreconstr.Core}.
     */
    CoreMultiport("core_multi"),
    /** A stage from the core itself, not visible to most SCAIE-V ISAXes or operations. */
    CoreInternal("core_internal"),
    /**
     * The decoupled super-stage, placed as a {@link PipelineStage#next} neighbor of a {@link StageKind#Core} stage.
     *  For many in-order microarchitectures, this is right after the last {@link StageKind#Core} stage.
     * Inputs need to be pipelined from the {@link StageKind#Core} stage from which decoupled execution is issued.
     *  Further interaction with the core pipeline needs to be done by requesting a decoupled spawn interface.
     */
    Decoupled("decoupled"),
    /**
     * A sub-stage embedded into a 'super stage' (i.e. part of a sub-pipeline embedded into a PipelineStage),
     *  that does not directly adhere to {@link scaiev.coreconstr.Core}, requiring pipelining/forwarding to/from its parent.
     * For now, Sub stages are direct children of either a {@link StageKind#Core} or a {@link StageKind#Decoupled} stage.
     */
    Sub("sub"),
    /**
     * A pseudo sub-stage for multiplexing between CoreMultiport port stages, depending on the active ISAXes.
     * Child of a CoreMultiport stage, listed after all stage ports (of kind Core).
     */
    ISAXMux("isaxmux"),
    /**
     * The root stage, into which the core pipeline is embedded as the child.
     * Does not have any pipeline semantics on its own, and thus is expected not to have any neighbors.
     */
    Root("root");

    public final String serialName;

    private StageKind(String serialName) { this.serialName = serialName; }
  }

  /**
   * Stall attributes of a {@link StageKind#CoreMultiport} stage.
   */
  public static record MultiportStallAttributes(
      /**
       * The multi-port pipeline stage has a general stall signal that affects all ports.
       * Can be set in combination with perPortStall.
       * <br/>
       * Note: If perPortStall is also set, the RdStall port signal is assumed to contain the shared condition.
       */
      boolean hasSharedStall,
      /**
       * Each port has an individual stall signal.
       * If a port does not stall, its data travels onwards regardless of any other ports. 
       */
      boolean perPortStall,
      /**
       * The multi-port pipeline stage has a general flush signal that affects all ports.
       * Can be set in combination with perPortFlush.
       */
      boolean hasSharedFlush,
      /**
       * Each port has an individual flush signal.
       * If a port flushes, the flush affects ports with later instructions.
       * Current assumption: If port N flushes, so do all ports N+k.
       */
      boolean perPortFlush,
      /** If port N stalls, so does port N+1,etc. */
      boolean stallAffectsNext,
      /**
       * The core shifts instructions up (lower port num) after a stall cycle to fill in any gaps.
       * NOTE: Assumes the core *always* (if true) or *never* (if false) shifts up slots.
       * E.g., if ports 0..N run through but N+1 is stalled and valid,
       *       the instruction from port N+1 will move to port 0 in the next cycle.
       * Also implies that, if port N+1 runs through, so does port N (have to) run through.
       */
      boolean shiftUp
      ) implements Serializable
  {
    public MultiportStallAttributes {
      if (stallAffectsNext && !perPortStall)
        throw new IllegalArgumentException("stallAffectsNext requires perPortStall");
      if (shiftUp && !perPortStall)
        throw new IllegalArgumentException("shiftUp requires perPortStall");
    }
  }

  /**
   * Pipeline attributes of a {@link StageKind#Core} port stage (i.e., a child stage of a {@link StageKind#CoreMultiport} stage).
   */
  public static record MultiportPipeAttributes(
      /**
       * Port N of this stage always travels to port N of the next stage.
       * Otherwise: {@link scaiev.backend.BNode#RdPipeInto} will be used in hardware to determine the next stage.
       */
      boolean directMapping,
      /**
       * Provides the successor stage names to consider. Can either refer to <br/>
       *   - port stages (i.e., children of the next CoreMultiport stage) <br/>
       *   (this.parent.get().next[[size()==1]].get(0)[[getKind()==CoreMultiport]].children) <br/>
       *   - or regular Core/CoreInternal stages (i.e., direct successor stages) <br/>
       *   (this.parent.get().next[[size()&gt;=1]].get(i)[[getKind()!=CoreMultiport]]) <br/>
       * If directMapping==true, this must be empty. <br/>
       * If directMapping==false and this is empty, all of stage.parent.next's ports will be considered. <br/>
       * If directMapping==false and this is non-empty, only the listed stages will be considered.
       */
      List<String> explicitSuccessorStageNames
      ) implements Serializable
  {
    public MultiportPipeAttributes {
      if (directMapping && !explicitSuccessorStageNames.isEmpty())
        throw new IllegalArgumentException("explicitSuccessorStageNames must be empty if directMapping");
    }
  }

  public enum StageTag {
    /**
     * Stage is in-order, i.e. any instruction/operation that enters the stage also leaves the stage in the same order.
     * For now, 'inorder' is also assumed to indicate that instructions/operations enter the stage in logical order.
     */
    InOrder("inorder"),
    /**
     * Register rename stage. SCAIE-V should perform any hazard handling stalls on ISA register numbers in this stage,
     *    as later stages of the core may not read the ISA register again / may wait on physical register numbers instead. 
     * If no RegRename stage is given, SCAIE-V will default to apply hazard stalling to the first stage where RdRS1 is available.
     */
    RegRename("regrename"),
    /**
     * Issue stage marker. All instructions leaving this stage (without flushing in Issue) will have corresponding RdCommit... messages.
     * RdIssueID must be available in this stage.
     * Up until the Issue stage, SCAIE-V will use RdFlush/WrFlush (+ stall) to check if an instruction completes or is discarded.
     *  If no Issue stage is given, the flush check extends to the rest of the pipeline (before commit).
     */
    Issue("issue"),
    /**
     * Execute stage marker for semi-coupled spawn. The default is to assume the second stage with RdRS1 ability to be the execute stage.
     */
    Execute("execute"),
    /**
     * Marks a stage as only ever containing nonspeculated instructions that are known to be executed
     */
    Nonspeculative("nonspeculative"),
    /**
     * Commit stage marker: Any operation that leaves this stage can be considered committable (if not committed already), as it cannot be
     * flushed anymore.
     */
    Commit("commit"),
    /**
     * No ISAXes pass through this stage, intended for issues to other execution units.
     * Marker used to track custom register commit for 'always' ISAXes while ignoring regular ISAXes.
     */
    NoISAX("noisax"),
    /**
     * Attributes for a stage of kind {@link StageKind#CoreMultiport}, see {@link MultiportStallAttributes}.
     */
    MultiportStall("multiport_stall", MultiportStallAttributes.class),
    /**
     * Attributes for a port stage that has a parent of kind {@link StageKind#CoreMultiport}, see {@link MultiportPipeAttributes}.
     */
    MultiportPipe("ported_pipe", MultiportPipeAttributes.class);

    public final String serialName;
    public final Class<? extends Serializable> attributesClass;

    private StageTag(String serialName) {
      this.serialName = serialName;
      this.attributesClass = null;
    }
    private StageTag(String serialName, Class<? extends Serializable> attributesClass) {
      this.serialName = serialName;
      this.attributesClass = attributesClass;
    }
  }

  StageKind kind;
  /** This PipelineStage's {@link StageKind} */
  public StageKind getKind() { return kind; }
  EnumSet<StageTag> tags;
  Map<StageTag, Object> attributes;
  public void addTag(StageTag tag) { addTagAttr(tag, null); }
  /**
   * Adds a tag with an associated attributes object.
   * If the tag type has no attributes type ({@link StageTag#attributesClass} == null),
   *  attr must be null. Otherwise, attr must be compatible with the type.
   */
  public void addTagAttr(StageTag tag, Object attr) {
    if (tag.attributesClass != null) {
      if (attr == null)
        throw new IllegalArgumentException("attr must be non-null for attribute tags");
      if (!tag.attributesClass.isAssignableFrom(attr.getClass()))
        throw new IllegalArgumentException("attr must be compatible with the tag's expected attribute type");
    }
    else if (attr != null)
      throw new IllegalArgumentException("attr must be null for non-attribute tags");
    
    tags.add(tag);
    if (attr != null)
      attributes.put(tag, attr);
  }
  /** Returns a read-only version of the stage's tag set. */
  public Set<StageTag> getTags() { return Collections.unmodifiableSet(tags); }
  /**
   * @param tag the tag to check
   * @return Returns the attribute object associated with a StageTag.
   * Returns null for unset tags and for tags that don't have an attribute class.
   */
  public Object getTagAttr(StageTag tag) {
    return attributes.get(tag);
  }
  /**
   * @param tag the tag to check
   * @return Returns the attribute object associated with a StageTag.
   * Returns null for unset tags and for tags that don't have an attribute class.
   * @throws java.lang.ClassCastException invalid (non-null) cast
   */
  @SuppressWarnings("unchecked")
  public <T> T getTagAttr(StageTag tag, Class<T> retClass) {
    Object ret = attributes.get(tag);
    if (ret == null)
      return null;
    if (retClass.isAssignableFrom(ret.getClass()))
      return (T)ret;
    throw new ClassCastException("%s to T=%s".formatted(ret.getClass().getName(), retClass.getName()));
  }

  String name;
  /** The name of the stage to present to users and to use for interface pins */
  public String getName() { return name; }

  Optional<Integer> globalStageID = Optional.empty();
  /** The global stage ID for external interfaces. */
  public Optional<Integer> getGlobalStageID() { return globalStageID; }

  List<PipelineStage> prev = new ArrayList<>();
  List<PipelineStage> next = new ArrayList<>();

  /**
   * The previous neighboring stages, empty for the first stage.
   * This will usually contain a single predecessor.
   * However, for instance, the retire stage for a multi-EU core can have several predecessors.
   * @return an unmodifiable view of the prev list
   */
  public List<PipelineStage> getPrev() { return Collections.unmodifiableList(prev); }
  /**
   * The next neighboring stages, empty for the last stage.
   * This will usually contain a single successor.
   * However, for instance, the issue stage for a multi-EU core can have several successors.
   * @return an unmodifiable view of the next list
   */
  public List<PipelineStage> getNext() { return Collections.unmodifiableList(next); }

  /**
   * Indicates whether the pipeline is easy to construct from the previous stage(s).
   * Meaningless if prev is empty.
   */
  boolean continuous = true;
  public boolean getContinuous() { return continuous; }

  /** The shortest recursive depth along prev */
  int stagePos = Integer.MAX_VALUE;
  /** The shortest recursive depth along prev */
  public int getStagePos() { return stagePos; }

  // Stage subdivision: High-level stages that are mapped to a sub-pipeline of several actual stages
  //  (and that can still process several elements in parallel).

  /**
   * For sub-stages, the parent specifies the overarching subdivided stage; else empty
   * Note: SCAIE-V uses a root PipelineStage across the entire core pipeline
   *       (ending with a further subdivided stage for decoupled operations).
   * */
  Optional<PipelineStage> parent = Optional.empty();
  public Optional<PipelineStage> getParent() { return parent; }

  List<PipelineStage> children = new ArrayList<>();
  /**
   * Returns an unmodifiable view of the children list.
   * children specifies the first stage for each encompassed sub-pipeline.
   *   empty if the stage is not divided into sub-pipelines.
   * If there are several children, some sort of MUXing is required.
   * Note: there can be several last children in a sub-pipeline, e.g. if a core has several execute or retire stages.
   */
  public List<PipelineStage> getChildren() { return Collections.unmodifiableList(children); }
}
