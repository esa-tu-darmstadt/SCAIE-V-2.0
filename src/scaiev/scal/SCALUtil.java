package scaiev.scal;

import java.util.Optional;
import java.util.stream.Stream;

import scaiev.backend.BNode;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineStage;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;

/**
 * Utility methods for use in SCAL strategies
 */
public class SCALUtil {
  
  private static Stream<String> makeRdwrCondStream(BNode bNodes, NodeRegistryRO registry, PipelineStage stage,
                                                    RequestedForSet requestedFor, Stream<SCAIEVNode> nodesToCheck) {
    //AND of RdStall, RdFlush, etc.
    return nodesToCheck
        .map(node -> new NodeInstanceDesc.Key(node, stage, ""))
        .map(key -> key.getNode().isInput/*Wr*/
                    ? registry.lookupOptionalUnique(key, requestedFor)
                    : Optional.of(registry.lookupRequired(key, requestedFor)))
        .filter(inst_opt -> inst_opt.isPresent())
        .map(inst_opt -> inst_opt.get().getExpressionWithParens()); 
  }

  /** @see #buildCond_StageStalling(BNode, NodeRegistryRO, PipelineStage, boolean, RequestedForSet) */
  public static String buildCond_StageStalling(BNode bNodes, NodeRegistryRO registry, PipelineStage stage, boolean checkFlush) {
    return buildCond_StageStalling(bNodes, registry, stage, checkFlush, RequestedForSet.empty);
  }
  /**
   * Builds an OR over bNodes.(Rd|Wr)(Stall|Flush). Only uses stall nodes if checkFlush==false.
   * @param bNodes BNode object to get the SCAIEVNodes from
   * @param registry the registry for lookups
   * @param stage the stage to build the condition for
   * @param checkFlush true iff flushes should be included in the condition
   * @param requestedFor RequestedForSet to add to the looked up instances
   * @return the condition string in the format '(...) || (...) || ...'
   */
  public static String buildCond_StageStalling(BNode bNodes, NodeRegistryRO registry, PipelineStage stage, boolean checkFlush,
                                               RequestedForSet requestedFor) {
    var nodes = Stream.of(bNodes.RdStall, bNodes.WrStall);
    if (checkFlush)
      nodes = Stream.concat(nodes, Stream.of(bNodes.RdFlush, bNodes.WrFlush));
    var condStream = makeRdwrCondStream(bNodes, registry, stage, requestedFor, nodes);
    //OR of RdStall, RdFlush, etc.
    return condStream.reduce((a,b) -> a + " || " + b).orElse("1'b0");
  }

  /** @see #buildCond_StageFlushing(BNode, NodeRegistryRO, PipelineStage, RequestedForSet) */
  public static String buildCond_StageFlushing(BNode bNodes, NodeRegistryRO registry, PipelineStage stage) {
    return buildCond_StageFlushing(bNodes, registry, stage, RequestedForSet.empty);
  }
  /**
   * Builds an OR over bNodes.(Rd|Wr)Flush.
   * @param bNodes BNode object to get the SCAIEVNodes from
   * @param registry the registry for lookups
   * @param stage the stage to build the condition for
   * @param requestedFor RequestedForSet to add to the looked up instances
   * @return the condition string in the format '(...) || (...)'
   */
  public static String buildCond_StageFlushing(BNode bNodes, NodeRegistryRO registry, PipelineStage stage, RequestedForSet requestedFor) {
    var nodes = Stream.of(bNodes.RdFlush, bNodes.WrFlush);
    var condStream = makeRdwrCondStream(bNodes, registry, stage, requestedFor, nodes);
    //OR of RdStall, RdFlush, etc.
    return condStream.reduce((a,b) -> a + " || " + b).orElse("1'b0");
  }


  /** @see #buildCond_StageNotStalling(BNode, NodeRegistryRO, PipelineStage, boolean, RequestedForSet) */
  public static String buildCond_StageNotStalling(BNode bNodes, NodeRegistryRO registry, PipelineStage stage, boolean checkFlush) {
    return buildCond_StageNotStalling(bNodes, registry, stage, checkFlush, RequestedForSet.empty);
  }
  /**
   * Builds an AND-NOT over bNodes.(Rd|Wr)(Stall|Flush). Only uses stall nodes if checkFlush==false.
   * @param bNodes BNode object to get the SCAIEVNodes from
   * @param registry the registry for lookups
   * @param stage the stage to build the condition for
   * @param checkFlush true iff flushes should be included in the condition
   * @param requestedFor RequestedForSet to add to the looked up instances
   * @return the condition string in the format '!(...) &amp;&amp; !(...) &amp;&amp; ...'
   */
  public static String buildCond_StageNotStalling(BNode bNodes, NodeRegistryRO registry, PipelineStage stage, boolean checkFlush,
                                                  RequestedForSet requestedFor) {
    var nodes = Stream.of(bNodes.RdStall, bNodes.WrStall);
    if (checkFlush)
      nodes = Stream.concat(nodes, Stream.of(bNodes.RdFlush, bNodes.WrFlush));
    var condStream = makeRdwrCondStream(bNodes, registry, stage, requestedFor, nodes);
    //AND of (not RdStall), (not RdFlush), etc.
    return condStream.map(a -> "!" + a).reduce((a,b) -> a + " && " + b).orElse("1'b1");
  }

  /** @see #buildCond_StageNotFlushing(BNode, NodeRegistryRO, PipelineStage, RequestedForSet) */
  public static String buildCond_StageNotFlushing(BNode bNodes, NodeRegistryRO registry, PipelineStage stage) {
    return buildCond_StageNotFlushing(bNodes, registry, stage, RequestedForSet.empty);
  }
  /**
   * Builds an AND-NOT over bNodes.(Rd|Wr)Flush.
   * @param bNodes BNode object to get the SCAIEVNodes from
   * @param registry the registry for lookups
   * @param stage the stage to build the condition for
   * @param requestedFor RequestedForSet to add to the looked up instances
   * @return the condition string in the format '!(...) &amp;&amp; !(...)'
   */
  public static String buildCond_StageNotFlushing(BNode bNodes, NodeRegistryRO registry, PipelineStage stage, RequestedForSet requestedFor) {
    var nodes = Stream.of(bNodes.RdFlush, bNodes.WrFlush);
    var condStream = makeRdwrCondStream(bNodes, registry, stage, requestedFor, nodes);
    //AND of (not RdStall), (not RdFlush), etc.
    return condStream.map(a -> "!" + a).reduce((a,b) -> a + " && " + b).orElse("1'b1");
  }
}
