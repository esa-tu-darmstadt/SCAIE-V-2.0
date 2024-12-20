package scaiev.pipeline;

import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PipelineTest {

  @BeforeEach
  void setUp() throws Exception {}

  @RepeatedTest(128)
  void testFront_random() {
    long seed = new Random().nextLong();
    try {
      testFront(seed);
    } catch (Throwable t) {
      System.err.println("FAILED testFront with seed " + seed);
      throw t;
    }
  }

  @ParameterizedTest
  @ValueSource(longs = {5893163784830298700L, 1234, 68392, -6733423670758169604L})
  void testFront(long seed) {
    var rand = new Random(seed);
    var builder = new TestPipelineBuilder(rand);
    var builtPipeline = builder.build(3, 8);
    builtPipeline.coreDepthBuckets.forEach(bucket -> { assert (bucket.stream().distinct().count() == bucket.size()); });

    //'surface stage' refers to the stages with parent == builtPipeline.rootStage
    HashMap<PipelineStage, Integer> bucketBySurfaceStage = new HashMap<>();
    for (int i = 0; i < builtPipeline.coreDepthBuckets.size(); ++i) {
      for (PipelineStage stage : builtPipeline.coreDepthBuckets.get(i)) {
        var prevVal = bucketBySurfaceStage.put(stage, i);
        // Assert: no duplicate stages in coreDepthBuckets
        Assertions.assertTrue(prevVal == null);
      }
    }
    // Also add the remaining stages from each bucket to bucketBySurfaceStage.
    for (int i = 0; i < builtPipeline.coreDepthBuckets.size(); ++i) {
      int i_ = i;
      Predicate<PipelineStage> pred_isInCurrentBucket = stage_ -> bucketBySurfaceStage.getOrDefault(stage_, i_) == i_;
      builtPipeline.coreDepthBuckets.get(i)
          .stream()
          .flatMap(frontStage -> frontStage.streamNext_bfs(pred_isInCurrentBucket))
          .filter(pred_isInCurrentBucket)
          .forEach(nextStage -> bucketBySurfaceStage.put(nextStage, i_));
    }

    final int numSurfaceProbes = 16;
    final int numSubProbes = 16;

    for (int iProbe = 0; iProbe < numSurfaceProbes; ++iProbe) {
      int iFrontBucket = rand.nextInt(builtPipeline.coreDepthBuckets.size());
      var frontBucket = builtPipeline.coreDepthBuckets.get(iFrontBucket);
      int iOtherBucket = rand.nextInt(builtPipeline.coreDepthBuckets.size());
      var otherBucket = builtPipeline.coreDepthBuckets.get(iOtherBucket);

      PipelineFront front;
      boolean frontIsEntireBucket = rand.nextBoolean();
      Predicate<PipelineStage> pred_isInCurrentBucket = stage_ -> bucketBySurfaceStage.get(stage_) == iFrontBucket;
      List<PipelineStage> frontBucketAllStages = frontBucket.stream()
                                                     .flatMap(entryStage -> entryStage.streamNext_bfs(pred_isInCurrentBucket))
                                                     .filter(pred_isInCurrentBucket)
                                                     .distinct()
                                                     .collect(Collectors.toList());
      Assertions.assertTrue(frontBucketAllStages.size() >= frontBucket.size(), "Unexpected streamNext_bfs result size");
      Assertions.assertTrue(frontBucket.stream().allMatch(a -> frontBucketAllStages.contains(a)),
                            "Unexpected streamNext_bfs result contents");
      if (iFrontBucket + 1 < builtPipeline.coreDepthBuckets.size()) {
        // Check that we haven't collected
        Assertions.assertTrue(
            builtPipeline.coreDepthBuckets.get(iFrontBucket + 1).stream().allMatch(a -> !frontBucketAllStages.contains(a)),
            "Unexpected streamNext_bfs result contents");
      }

      frontIsEntireBucket = frontBucket.size() == frontBucketAllStages.size();
      if (frontIsEntireBucket) {
        // Construct front from entire bucket
        front = new PipelineFront(frontBucketAllStages);
      } else {
        // Construct front from bucket entry stages only
        front = new PipelineFront(builtPipeline.coreDepthBuckets.get(iFrontBucket));
      }

      Predicate<PipelineStage> pred_isInOtherBucket = stage_ -> bucketBySurfaceStage.get(stage_) == iOtherBucket;
      List<PipelineStage> otherBucketAllStages = frontBucket.stream()
                                                     .flatMap(entryStage -> entryStage.streamNext_bfs(pred_isInOtherBucket))
                                                     .filter(pred_isInOtherBucket)
                                                     .distinct()
                                                     .collect(Collectors.toList());
      boolean frontIsEntireBucket_ = frontIsEntireBucket; // Java stuff
      Assertions.assertTrue(otherBucketAllStages.stream().allMatch(
          otherStage
          ->
          //
          (front.contains(otherStage) == (iFrontBucket == iOtherBucket) || (iFrontBucket == iOtherBucket && !frontIsEntireBucket_))));
      Assertions.assertTrue(otherBucketAllStages.stream().allMatch(otherStage
                                                                   ->
                                                                   // We're not looking at any sub stages.
                                                                   front.isAround(otherStage) == front.contains(otherStage)));
      Assertions.assertTrue(otherBucketAllStages.stream().allMatch(
          otherStage
          ->
          // isBefore <=> a) front bucket is before other bucket,
          //           or b) buckets are the same but otherStage is not at (i.e. is after) the entry to the bucket
          (front.isBefore(otherStage, false) ==
           (iFrontBucket < iOtherBucket || ((iFrontBucket == iOtherBucket) && !otherBucket.contains(otherStage))))));
      otherBucketAllStages.forEach(otherStage -> {
        boolean cond =
            // isAfter <=>  a) front bucket is after other bucket,
            //           or b) buckets are the same and <front> contains the entire bucket and otherStage is not at the end of the bucket
            //                 (i.e. there is some stage in the bucket that is after otherStage, and <front> contains that stage)
            front.isAfter(otherStage, false) ==
            (iFrontBucket > iOtherBucket ||
             (iFrontBucket == iOtherBucket && frontIsEntireBucket_ &&
              otherStage.next.stream().anyMatch(pred_isInOtherBucket) /*Note: here, front bucket == other bucket*/));
        Assertions.assertTrue(cond);
      });
      Assertions.assertTrue(otherBucketAllStages.stream().allMatch(
          otherStage -> front.isAroundOrAfter(otherStage, false) == front.isAround(otherStage) || front.isAfter(otherStage, false)));
      Assertions.assertTrue(otherBucketAllStages.stream().allMatch(
          otherStage -> front.isAroundOrBefore(otherStage, false) == front.isAround(otherStage) || front.isBefore(otherStage, false)));
    }

    // Probe sub-pipelines.
    for (PipelineStage subPipelinedStage : builtPipeline.subPipelinedStages) {
      var childList = subPipelinedStage.getAllChildren().collect(Collectors.toList());

      PipelineFront rootFront = new PipelineFront(builtPipeline.rootStage);
      PipelineFront subPipelinedStageFront = new PipelineFront(subPipelinedStage);
      PipelineFront bothFront = new PipelineFront(List.of(builtPipeline.rootStage, subPipelinedStage));
      Assertions.assertTrue(childList.stream().allMatch(
          childStage
          -> rootFront.isAround(childStage) && subPipelinedStageFront.isAround(childStage) && bothFront.isAround(childStage) &&
                 bothFront.isAroundOrBefore(childStage, false) && bothFront.isAroundOrAfter(childStage, false)));

      for (int iProbe = 0; iProbe < numSubProbes; ++iProbe) {
        PipelineStage childStage = childList.get(rand.nextInt(childList.size()));

        PipelineFront childFront = new PipelineFront(childStage);
        int iOtherBucket = rand.nextInt(builtPipeline.coreDepthBuckets.size());
        var otherBucketFront = builtPipeline.coreDepthBuckets.get(iOtherBucket);
        PipelineStage otherStage = otherBucketFront.get(rand.nextInt(otherBucketFront.size()));
        Assertions.assertTrue(rootFront.isAround(otherStage));
        Assertions.assertFalse(childFront.isAround(otherStage));

        if (subPipelinedStageFront.isAround(otherStage))
          Assertions.assertTrue(otherStage == subPipelinedStage);

        Assertions.assertTrue(subPipelinedStageFront.isAfter(otherStage, false) == childFront.isAfter(otherStage, false));
        Assertions.assertTrue(subPipelinedStageFront.isAroundOrAfter(otherStage, false) ==
                              (childFront.isAfter(otherStage, false) || otherStage == subPipelinedStage));
        Assertions.assertTrue(subPipelinedStageFront.isBefore(otherStage, false) == childFront.isBefore(otherStage, false));
        Assertions.assertTrue(subPipelinedStageFront.isAroundOrBefore(otherStage, false) ==
                              (childFront.isBefore(otherStage, false) || otherStage == subPipelinedStage));
        Assertions.assertFalse(childFront.isAround(builtPipeline.rootStage));
        Assertions.assertFalse(childFront.isAround(subPipelinedStage));
      }
    }
  }
}
