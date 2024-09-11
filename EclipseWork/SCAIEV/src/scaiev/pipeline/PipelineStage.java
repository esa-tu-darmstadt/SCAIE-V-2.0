package scaiev.pipeline;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.Spliterators;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Predicate;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

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
		return String.format("PipelineStage %s\"%s\" (%d)", globalStageID.isPresent() ? String.format("%d-",globalStageID.get()) : "", name, instanceID);
	}

	public PipelineStage(StageKind kind, EnumSet<StageTag> tags, String name, Optional<Integer> globalStageID, boolean continuous) {
		this.kind = kind;
		this.tags = tags.clone();
		this.name = name;
		this.globalStageID = globalStageID;
		this.continuous = continuous;
		this.instanceID = instanceCounter.incrementAndGet();
	}
	
	/**
	 * Constructs a linear, continuous in-order pipeline with simple index names.
	 * Appropriate for most single-issue in-order cores.
	 * @param depth number of stages that are in a row
	 * @param firstGlobalStageID optional: global stage ID for the first stage, will be incremented for each successor
	 */
	public static PipelineStage constructLinearContinuous(
			StageKind kind, int depth,
			Optional<Integer> firstGlobalStageID) {
		if (depth <= 0)
			throw new IllegalArgumentException("depth must be above zero");
		var pipeline = new ArrayList<PipelineStage>(depth);
		for (int i = 0; i < depth; ++i) {
			int i_ = i;
			var newStage = new PipelineStage(kind, EnumSet.of(StageTag.InOrder), ""+i, firstGlobalStageID.map(globalID -> globalID+i_), true);
			if (i > 0)
				pipeline.get(i-1).addNext(newStage);
			pipeline.add(newStage);
		}
		return pipeline.get(0);
	}
	
	private void fixSuccessorStagePos() {
		if (this.stagePos == Integer.MAX_VALUE)
			return;
		//Possible optimization: Stop iterating along a stage if its stagePos was correct already.
		for (PipelineStage curStage : this.iterableNext_bfs()) {
			//BFS from root stage -> iteration order is by monotonic ascending depth
			// -> can directly infer the depth from the predecessor with stagePos set already
			curStage.stagePos = curStage.prev.stream().mapToInt(prevStage -> prevStage.stagePos).min().orElse(-1);
			assert(curStage.stagePos != Integer.MAX_VALUE); //We must have gotten to this node by someone with stagePos initialized.
			++curStage.stagePos;
		}
	}
	
	/**
	 * Links a stage to this via the 'this.next' link.
	 * @param newNext
	 * @return newNext
	 */
	public PipelineStage addNext(PipelineStage newNext) {
		//Hoping that the caller is careful enough to not produce cycles via newNext.next, newNext.children.
		assert(!this.next.contains(newNext));
		assert(!newNext.prev.contains(this));

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
			assert(curChildStage.parent.isEmpty());
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
			//The set specifying the current search window (by design only containing elements at depth i and possibly i+1)
			Deque<PipelineStage> front = new ArrayDeque<>(List.of(init));
			int next_idx = 0;
			Optional<PipelineStage> nextVal = Optional.of(init);
			//We have to tag all visited stages to prevent duplicate iteration.
			//(we cannot rely on optimizations like only going through the shortest paths via getStagePos() and testing in front
			// since *this* may be somewhere in the middle of the graph;
			// also, processSuccessors could block the shortest path either way)
			HashSet<PipelineStage> visitedSet = new HashSet<>();
			private void updateNext() {
				nextVal = Optional.empty();
				while (!front.isEmpty()) {
					nextVal = Optional.empty();
					//Continue processing the stage in front of <front> (say, depth i).
					PipelineStage cur = front.getFirst();
					List<PipelineStage> successors = iterNext ? cur.next : cur.prev;
					if (next_idx < successors.size() //if the index is within the current successors list range
						&& (next_idx != 0 || processSuccessors.test(cur)) //invocate processSuccessors only once per stage (-> if next_idx == 0)
						) {
						PipelineStage next = successors.get(next_idx);
						++next_idx;
						if (!visitedSet.add(next))
							continue; //Prevent duplicate iteration
						nextVal = Optional.of(next);
						//Add the successor at the end of <front> (the successor has depth i+1).
						front.addLast(nextVal.get());
						break;
					}
					else {
						//Remove the current stage from <front>.
						front.removeFirst();
						next_idx = 0;
					}
				}
			}
			public boolean hasNext() {
				return nextVal.isPresent();
			}
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
	public Iterator<PipelineStage> iterateNext_bfs() {
		return iterateNext_bfs(stage -> true);
	}
	/**
	 * Creates an Iterable using {@link PipelineStage#iterateNext_bfs(Predicate)}
	 */
	public Iterable<PipelineStage> iterableNext_bfs(Predicate<PipelineStage> processSuccessors) {
		return new Iterable<PipelineStage>() {
			public Iterator<PipelineStage> iterator() {
				return iterateNext_bfs(processSuccessors);
			}
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
	public Iterator<PipelineStage> iteratePrev_bfs() {
		return iteratePrev_bfs(stage -> true);
	}
	/**
	 * Creates an Iterable using {@link PipelineStage#iteratePrev_bfs(Predicate)}
	 */
	public Iterable<PipelineStage> iterablePrev_bfs(Predicate<PipelineStage> processPredecessors) {
		return new Iterable<PipelineStage>() {
			public Iterator<PipelineStage> iterator() {
				return iteratePrev_bfs(processPredecessors);
			}
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
		//Produce a stream across all children.
		return children.stream().flatMap(child -> //For each child, produce a sub-stream for the stage position.
			child.streamNext_bfs(stage -> stage.getStagePos() < stagePos).filter(stage -> stage.getStagePos() >= stagePosMin && stage.getStagePos() <= stagePosMax)
		);
	}
	/**
	 * Returns a stream of all child stages with the given stage position.
	 *  Can be used to construct a {@link PipelineFront}.
	 * @param stagePos the exact stage position to look for ({@link PipelineStage#getStagePos()})
	 */
	public Stream<PipelineStage> getChildrenByStagePos(int stagePos) {
		return getChildrenByStagePos(stagePos, stagePos);
	}
	/**
	 * Returns a breadth-first stream of all stages across all children (where parent == this).
	 */
	public Stream<PipelineStage> getAllChildren() {
		//Add a temporary stage with all children as successors.
		// -> this PipelineStage does not adhere to the invariants
		//    enforced for any outwards-facing objects,
		//    e.g. its successors do not point back to it
		PipelineStage searchFront = new PipelineStage(StageKind.Core, EnumSet.noneOf(StageTag.class), null, Optional.empty(), false);
		searchFront.next = children;
		//Produce a stream across all children (but not recursive children). Skip searchFront itself.
		return searchFront.streamNext_bfs().skip(1);
	}
	/**
	 * Returns a breadth-first stream of all stages across all children and sub-children.
	 * Note: In the stream, sub-pipelines are situated right after their parents. 
	 */
	public Stream<PipelineStage> getAllChildrenRecursive() {
		//Produce a stream across all children using flatMap and (pseudo-)recursion back into this function..
		return getAllChildren().flatMap(childStage -> Stream.concat(Stream.of(childStage), childStage.getAllChildrenRecursive()));
	}
	/**
	 * Returns a stream of the tail-end stages across all children.
	 *  Can be used to construct a {@link PipelineFront}.
	 */
	public Stream<PipelineStage> getChildrenTails() {
		//Produce a stream across all children.
		return children.stream().flatMap(child -> //For each child, produce a sub-stream for the last stage.
			child.streamNext_bfs().filter(stage -> stage.next.isEmpty())
		);
	}

	/** Categorization enum for true and synthetic PipelineStages */
	public enum StageKind {
		/** A stage from the core itself. Adheres to the constraints from the corresponding {@link scaiev.coreconstr.Core}. */
		Core("core"),
		/** A stage from the core itself, not visible to most SCAIE-V ISAXes or operations. */
		CoreInternal("core_internal"),
		/**
		 * The decoupled super-stage, placed as a {@link PipelineStage#next} neighbor of a {@link StageKind#Core} stage.
		 *  For many in-order microarchitectures, this is right after the last {@link StageKind#Core} stage.
		 * Inputs need to be pipelined from the {@link StageKind#Core} stage from which decoupled execution is issued.
		 * Further interaction with the core pipeline needs to be done by requesting a decoupled interface
		 */
		Decoupled("decoupled"),
		/** 
		 * A sub-stage embedded into a 'super stage' (i.e. part of a sub-pipeline embedded into a PipelineStage),
		 *  that does not directly adhere to {@link scaiev.coreconstr.Core}, requiring pipelining/forwarding to/from its parent.
		 * For now, Sub stages are direct children of either a {@link StageKind#Core} or a {@link StageKind#Decoupled} stage.
		 */
		Sub("sub"),
		/**
		 * The root stage, into which the core pipeline is embedded as the child.
		 * Does not have any pipeline semantics on its own, and thus is expected not to have any neighbors.
		 */
		Root("root");
		
		public final String serialName;
		
		private StageKind(String serialName) {
			this.serialName = serialName;
		}
	}
	
	public enum StageTag {
		/** 
		 * Stage is in-order, i.e. any instruction/operation that enters the stage also leaves the stage in the same order.
		 * For now, 'inorder' is also assumed to indicate that instructions/operations enter the stage in logical order. 
		 */
		InOrder("inorder"),
		/** 
		 * Execute stage marker for semi-coupled spawn. The default is to assume the second stage with RdRS1 ability to be the execute stage.
		 */
		Execute("execute"),
		/**
		 * Commit stage marker: Any operation that enters this stage can be considered committable (if not committed already), as it cannot be flushed anymore.
		 */
		Commit("commit");
		
		public final String serialName;
		
		private StageTag(String serialName) {
			this.serialName = serialName;
		}
	}

	StageKind kind;
	/** This PipelineStage's {@link StageKind} */
	public StageKind getKind() { return kind; }
	EnumSet<StageTag> tags;
	public void addTag(StageTag tag) { tags.add(tag); }
	public Set<StageTag> getTags() { return Collections.unmodifiableSet(tags); } 
	
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

	//Stage subdivision: High-level stages that are mapped to a sub-pipeline of several actual stages
	// (and that can still process several elements in parallel).
	
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
