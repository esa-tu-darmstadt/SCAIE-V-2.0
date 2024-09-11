package scaiev.pipeline;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.stream.Collectors;

import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;

public class TestPipelineBuilder {
	Random rand = new Random();
	public TestPipelineBuilder() {}
	public TestPipelineBuilder(Random rand) {
		this.rand = rand;
	}
	
	public static class GeneratedPipeline {
		public PipelineStage rootStage = new PipelineStage(StageKind.Root, EnumSet.noneOf(StageTag.class), "root", Optional.empty(), false);
		public List<List<PipelineStage>> coreDepthBuckets = new ArrayList<List<PipelineStage>>();
		public List<PipelineStage> subPipelinedStages = new ArrayList<PipelineStage>();
	}
	/**
	 * 
	 * @param minDepthCore minimum number of first-level graph depth groups (buckets)
	 * @param maxDepthCore maximum number of first-level graph depth groups (buckets)
	 * @return
	 */
	public GeneratedPipeline build(int minDepthCore, int maxDepthCore) {
		GeneratedPipeline ret = new GeneratedPipeline();
		if (!(minDepthCore > 0 && maxDepthCore >= minDepthCore))
			throw new IllegalArgumentException("invalid bounds");
		int nDepthBuckets = rand.ints(minDepthCore, maxDepthCore + 1).findFirst().getAsInt();
		ret.coreDepthBuckets = new ArrayList<List<PipelineStage>>(nDepthBuckets);
		int iSubpipedBucket = rand.nextInt(nDepthBuckets);
		int iStage = 0;
		for (int iBucket = 0; iBucket < nDepthBuckets; ++iBucket) {
			var curBucket = new ArrayList<PipelineStage>();
			ret.coreDepthBuckets.add(curBucket);

			//Generate 1..3 entry stages into the bucket
			int nEntries = rand.ints(1, 4).findFirst().getAsInt();
			int iSubpipeEntry = rand.nextInt(nEntries);
			for (int iEntry = 0; iEntry < nEntries; ++iEntry) {
				PipelineStage curStage = new PipelineStage(StageKind.Core, EnumSet.noneOf(StageTag.class),
					"core"+iBucket+"_"+iEntry, Optional.of(iStage++), true
				);
				curBucket.add(curStage);
				//Sometimes make a bucket two stages deep.
				if (rand.nextInt(128) < 16) {
					curStage.addNext(new PipelineStage(StageKind.Core, EnumSet.noneOf(StageTag.class),
						curStage.getName()+"_b", Optional.of(iStage++), false
					));
				}
				if (iBucket == iSubpipedBucket && iEntry == iSubpipeEntry) {
					ret.subPipelinedStages.add(curStage);
					int nSubpipelines = rand.nextInt(3)+1;
					int iSubStage = 0;
					for (int iSubpipeline = 0; iSubpipeline < nSubpipelines; ++iSubpipeline) {
						int childDepth = rand.nextInt(16)+1;
						curStage.addChild(PipelineStage.constructLinearContinuous(StageKind.Sub, childDepth, Optional.of(iSubStage)));
						iSubStage += childDepth;
						//Consistency check for child pipeline
						assert(curStage.getChildren().get(iSubpipeline).streamNext_bfs().count() == childDepth);
						assert(curStage.getChildren().get(iSubpipeline).streamNext_bfs().count() 
								== curStage.getChildren().get(iSubpipeline).streamNext_bfs().distinct().count());
						assert(curStage.getChildren().get(iSubpipeline).streamNext_bfs().allMatch(childStage -> 
							(childStage.prev.size() == 1 || childStage.getStagePos() == 0) //Has one prev, one next (except first, last respectively)
							&& (childStage.next.size() == 1 || childStage.getStagePos() == childDepth - 1)));
					}
					
					//Allow several sub-pipelined stages in the bucket.
					if (iEntry+1 < nEntries)
						iSubpipeEntry = rand.ints(iEntry + 1, nEntries + 1).findFirst().getAsInt(); //Explicit off-by-one to add a chance not to select another.
				}
			}
		}
		ret.coreDepthBuckets.get(0).forEach(startStage -> ret.rootStage.addChild(startStage));
		for (int iBucket = 1; iBucket < nDepthBuckets; ++iBucket) {
			List<PipelineStage> bucketEntries = ret.coreDepthBuckets.get(iBucket);
			List<PipelineStage> prevBucketEnds = ret.coreDepthBuckets.get(iBucket-1).stream()
					.flatMap(prevEntry -> prevEntry.streamNext_bfs())
					.filter(prevEntry -> prevEntry.getNext().isEmpty())
					.collect(Collectors.toList());
			
			//Based on how the buckets are constructed, do some sanity checks so any bugs in 'streamNext_bfs' would not degrade test quality.
			assert(prevBucketEnds.size() == ret.coreDepthBuckets.get(iBucket-1).size());
			assert(prevBucketEnds.stream().distinct().count() == prevBucketEnds.size());
			
			//Connect each entry stage to the current bucket to one or several tail stages of the previous bucket.
			bucketEntries.forEach(bucketEntryStage -> {
				int iConnectTo = -1;
				boolean connected = false;
				while (iConnectTo < prevBucketEnds.size() - 1
						&& (iConnectTo = rand.ints(iConnectTo + 1, prevBucketEnds.size()+(iConnectTo>=0?1:0)/*Explicit conditional off-by-one*/).findFirst().getAsInt()) 
						< prevBucketEnds.size()) {
					prevBucketEnds.get(iConnectTo).addNext(bucketEntryStage);
					connected = true;
				}
				assert(connected);
			});
		}
		
		//Various PipelineStage consistency checks.
		assert(ret.coreDepthBuckets.get(0).stream().allMatch(startStage -> ret.rootStage.getChildren().contains(startStage)));
		assert(ret.rootStage.getChildren().stream().allMatch(startStage -> ret.coreDepthBuckets.get(0).contains(startStage)));
		assert(ret.rootStage.getAllChildren().flatMap(stage -> stage.getGlobalStageID().stream()).distinct().count() == iStage);
		assert(ret.rootStage.getAllChildren().flatMap(stage -> stage.getGlobalStageID().stream()).count() == iStage);
		{
			int maxStage = ret.rootStage.getAllChildrenRecursive().flatMap(stage -> stage.getGlobalStageID().stream()).max(Integer::compare).get();
			assert(maxStage >= iStage - 1);
			//(sub-stage IDs start at 0 again, i.e. they will likely repeat -> use distinct() to roughly approach testing if we have all)
			assert(ret.rootStage.getAllChildrenRecursive().flatMap(stage -> stage.getGlobalStageID().stream()).distinct().count() == maxStage + 1);
		}
		boolean[] hitMapGlobal = new boolean[iStage];
		for (PipelineStage childStage : ret.rootStage.getChildren()) {
			//Check initial stage pos
			assert(childStage.getStagePos() == 0);
			boolean[] hitMapCur = new boolean[iStage];
			for (PipelineStage stage : childStage.iterableNext_bfs()) {
				int stageIdx = stage.getGlobalStageID().get();
				//Should visit each stage only once.
				assert(!hitMapCur[stageIdx]); 
				hitMapCur[stageIdx] = hitMapGlobal[stageIdx] = true;
				
				//Additional check
				assert(stage.getParent().get() == ret.rootStage);
			}
		}
		
		//Check that the BFS behaves correctly, based on stage pos
		int prevDepth = 0;
		for (PipelineStage stage : ret.rootStage.getAllChildren().collect(Collectors.toList())) {
			assert(stage.getStagePos() == prevDepth || stage.getStagePos() == prevDepth + 1);
			prevDepth = stage.getStagePos();
		}
		
		//Must have seen each direct child stage at least once over all BFSes.
		for (boolean didHit : hitMapGlobal)
			assert(didHit);

		//Check consistency of stage pos.
		assert(ret.rootStage.getAllChildrenRecursive().allMatch(stage -> 
			stage.getStagePos() >= 0
			&& (stage.getPrev().stream().anyMatch(prevStage -> prevStage.getStagePos() == stage.getStagePos() - 1)
			    || (stage.getStagePos() == 0 && stage.getParent().get().getChildren().contains(stage)))
			));

		//Assert next/prev equality
		assert(ret.rootStage.getAllChildrenRecursive().allMatch(child -> child.getNext().stream().allMatch(next -> next.getPrev().contains(child))));
		assert(ret.rootStage.getAllChildrenRecursive().allMatch(child -> child.getPrev().stream().allMatch(prev -> prev.getNext().contains(child))));
		
		return ret;
	}
}
