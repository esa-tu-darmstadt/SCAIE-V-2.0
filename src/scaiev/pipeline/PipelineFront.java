package scaiev.pipeline;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;

/**
 * Represents a collection of {@link PipelineStage}s to compare against.
 *   a.isBefore(b) means that at least one stage in the PipelineFront a is ordered before stage b in the PipelineStage graph.
 * <p>
 * In most cases, a PipelineFront should only contain stages of the same stage position ({@link PipelineStage#getStagePos()}).
 *   However, generally, a PipelineFront can consist of several stage positions, such that even a.isBefore(b) && a.isAfter(b) can hold true.
 * <p>
 * Ordering rules: <p>
 * - Before/After are based around {@link PipelineStage#prev} and {@link PipelineStage#next}
 *   e.g. {@code new PipelineFront(stage.prev.get(i)).isBefore(stage)} would be true.
 * <p>
 * - Any stage encompasses its children.
 *   Thus, {@code new PipelineFront(stage.parent).isAround(stage)} is true while {@code new PipelineFront(stage).isAround(stage.parent)} is
 * not. There is no before/after ordering between parents and children. <p>
 * - The children of stages in the given stage's {@link PipelineStage#next} are 'after' the given stage.
 *   The children of stages in the given stage's {@link PipelineStage#prev} are 'before' the given stage.
 *   e.g. {@code new PipelineFront(stage.parent.get().prev.get(i)).isBefore(stage)} would be true.
 * <p>
 * The comparison functions are not designed to perform well on large pipeline graphs.
 */
public class PipelineFront {
  ArrayList<PipelineStage> frontList = new ArrayList<>();
  HashSet<PipelineStage> front = new HashSet<>();

  /** Constructs an empty PipelineFront. */
  public PipelineFront() {}
  /** Constructs a PipelineFront for a single stage. */
  public PipelineFront(PipelineStage stage) {
    frontList.add(stage);
    front.add(stage);
  }
  /** Constructs a PipelineFront by copying a collection. Retains the order of the collection for asList. */
  public PipelineFront(Collection<PipelineStage> stages) {
    frontList.addAll(stages);
    front.addAll(stages);
    if (front.size() != frontList.size()) {
      throw new IllegalArgumentException("stages contains duplicates");
    }
  }
  /** Constructs a PipelineFront by consuming a stream. Retains the order of the stream for asList. */
  public PipelineFront(Stream<PipelineStage> stages) {
    // Collapse stream <stages> into <frontList>.
    stages.collect(Collectors.toCollection(() -> frontList));
    front.addAll(frontList);
    if (front.size() != frontList.size()) {
      throw new IllegalArgumentException("stages contains duplicates");
    }
  }

  /** Returns the {@link PipelineStage}s in the front as an unmodifiable List. */
  public List<PipelineStage> asList() { return Collections.unmodifiableList(frontList); }

  /**
   * Determines if this front contains queryStage, but does not check its ancestors.
   * @param queryStage
   */
  public boolean contains(PipelineStage queryStage) { return front.contains(queryStage); }

  /**
   * Determines if this front contains queryStage or one of its ancestors.
   * @param queryStage
   */
  public boolean isAround(PipelineStage queryStage) {
    return front.contains(queryStage) || (queryStage.parent.isPresent() && this.isAround(queryStage.parent.get()));
  }

  /**
   * Determines if this front lies at least partially before queryStage (or before an ancestor) through {@link PipelineStage#prev}.
   * @param queryStage
   * @param needs_continuous if set, only iterate beyond stages with the {@link PipelineStage#getContinuous()} property set
   */
  public boolean isBefore(PipelineStage queryStage, boolean needs_continuous) {
    if (needs_continuous && !queryStage.continuous)
      return false;
    while (queryStage.prev.isEmpty()) {
      // Ascend to the next ancestor.
      if (!queryStage.parent.isPresent())
        return false;
      queryStage = queryStage.parent.get();
      if (needs_continuous && !queryStage.continuous)
        return false;
    }
    // Simple recursive implementation. Performance should not be too much of a concern.
    return queryStage.prev.stream().anyMatch(
        prevStage
        -> (this.isAroundOrBefore(prevStage, needs_continuous) // Test the previous stage
                                                               // Test the child tails of the previous stage (note: if prevStage does have
                                                               // children, the check below will overlap with the one above)
            || prevStage.getChildrenTails().anyMatch(prevStageSubtail -> this.isAroundOrBefore(prevStageSubtail, needs_continuous))));
  }

  /**
   * Determines if this front contains or lies at least partially before queryStage (or an ancestor) through {@link PipelineStage#prev}.
   * @param queryStage
   * @param needs_continuous if set, only iterate beyond stages with the {@link PipelineStage#getContinuous()} property set
   * @return
   */
  public boolean isAroundOrBefore(PipelineStage queryStage, boolean needs_continuous) {
    return isAround(queryStage) || isBefore(queryStage, needs_continuous);
  }

  /**
   * Determines if this front lies at least partially after queryStage (or after an ancestor) through {@link PipelineStage#next}.
   * @param queryStage
   * @param needs_continuous if set, only iterate beyond stages with the {@link PipelineStage#getContinuous()} property set
   */
  public boolean isAfter(PipelineStage queryStage, boolean needs_continuous) {
    while (queryStage.next.isEmpty()) {
      // Ascend to the next ancestor.
      if (!queryStage.parent.isPresent())
        return false;
      queryStage = queryStage.parent.get();
    }
    Stream<PipelineStage> nextStream = queryStage.next.stream();
    if (needs_continuous)
      nextStream = nextStream.filter(stage -> stage.continuous);
    // Simple recursive implementation. Performance should not be too much of a concern.
    return nextStream.anyMatch(
        nextStage
        -> this.isAroundOrAfter(nextStage, needs_continuous) // Test the next stage
                                                             // Test the children of the next stage (note: if nextStage does have children,
                                                             // the check below will overlap with the one above)
               || nextStage.children.stream().anyMatch(nextStageChild -> this.isAroundOrAfter(nextStageChild, needs_continuous)));
  }

  /**
   * Determines if this front contains or lies at least partially after queryStage (or an ancestor) through {@link PipelineStage#next}.
   * @param queryStage
   * @param needs_continuous if set, only iterate beyond stages with the {@link PipelineStage#getContinuous()} property set
   * @return
   */
  public boolean isAroundOrAfter(PipelineStage queryStage, boolean needs_continuous) {
    return isAround(queryStage) || isAfter(queryStage, needs_continuous);
  }

  /**
   * Creates a Stream starting at the stages of this front, continuing through the stages reachable via next in a breadth-first search.
   * @param processSuccessors predicate that indicates whether the next list of a given stage should be iterated through
   */
  public Stream<PipelineStage> streamNext_bfs(Predicate<PipelineStage> processSuccessors) {
    // Add a temporary stage with all stages in the front as successors.
    //  -> this PipelineStage does not adhere to the invariants
    //     enforced for any outwards-facing objects,
    //     e.g. its successors do not point back to it
    PipelineStage searchFront = new PipelineStage(StageKind.Core, EnumSet.noneOf(StageTag.class), null, Optional.empty(), false);
    searchFront.next = frontList;
    // Produce a stream across all children (but not recursive children). Skip searchFront itself.
    return searchFront.streamNext_bfs(refStage -> refStage == searchFront || processSuccessors.test(refStage)).skip(1);
  }
  /**
   * Creates a Stream using {@link PipelineFront#streamNext_bfs(Predicate)} with an always-true predicate.
   */
  public Stream<PipelineStage> streamNext_bfs() { return streamNext_bfs(stage_ -> true); }
  /**
   * Creates a Stream starting at the stages of this front, continuing through the stages reachable via prev in a breadth-first search.
   * @param processPredecessors predicate that indicates whether the prev list of a given stage should be iterated through
   */
  public Stream<PipelineStage> streamPrev_bfs(Predicate<PipelineStage> processPredecessors) {
    // Add a temporary stage with all stages in the front as predecessors.
    //  -> this PipelineStage does not adhere to the invariants
    //     enforced for any outwards-facing objects,
    //     e.g. its successors do not point back to it
    PipelineStage searchFront = new PipelineStage(StageKind.Core, EnumSet.noneOf(StageTag.class), null, Optional.empty(), false);
    searchFront.prev = frontList;
    // Produce a stream across all children (but not recursive children). Skip searchFront itself.
    return searchFront.streamPrev_bfs(refStage -> refStage == searchFront || processPredecessors.test(refStage)).skip(1);
  }
  /**
   * Creates a Stream using {@link PipelineFront#streamPrev_bfs(Predicate)} with an always-true predicate.
   */
  public Stream<PipelineStage> streamPrev_bfs() { return streamPrev_bfs(stage_ -> true); }
}
