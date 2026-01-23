package scaiev.scal;

import java.util.Optional;
import java.util.stream.Stream;

import scaiev.backend.BNode;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;

/**
 * Utility methods for use in SCAL strategies and in interaction with SCAL
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

  /**
   * Returns an ExpressionType that can be used when outputting a looked-up node expression as-is.
   * Translates unique types (WireName, ModuleInput, ModuleOutput) to AnyExpression_Noparen.
   * @param inherited the NodeInstanceDesc the reused expression comes from
   * @return an ExpressionType that is never WireName
   */
  public static ExpressionType typeOfInheritedExpression(NodeInstanceDesc inherited) {
    switch (inherited.getExpressionType()) {
      case WireName:
        return ExpressionType.AnyExpression_Noparen;
      case ModuleInput:
        return ExpressionType.AnyExpression_Noparen;
      case ModuleOutput:
        return ExpressionType.AnyExpression_Noparen;
      default:
        return inherited.getExpressionType();
    }
  }

  /**
   * Determines, for port/ported stages, whether a node is per-port or per-CoreMultiport
   * @param node the node to check
   * @param stage a port or multiport base stage
   * @return true iff the node is considered per-port
   */
  public static boolean nodeIsPerPort(SCAIEVNode node, PipelineStage stage) {
    if (node.tags.contains(NodeTypeTag.sharedAcrossPorts))
      return false;
    var multiportStall = stage.getMultiportBase().getTagAttr(StageTag.MultiportStall, PipelineStage.MultiportStallAttributes.class);
    if (multiportStall != null) {
      assert(stage.getMultiportBase().getKind() == StageKind.CoreMultiport);
      if (node.name.equals("RdStall") || node.name.equals("RdStallLegacy") || node.name.equals("WrStall")) {
        //ignoring hasSharedStall, since that is fed into the per-port information
        return multiportStall.perPortStall();
      }
      if (node.name.equals("RdFlush") || node.name.equals("WrFlush") || node.name.equals("WrPC")) {
        //ignoring hasSharedFlush, since that is fed into the per-port information
        return multiportStall.perPortFlush();
      }
      if (node.name.equals("WrPC") || node.name.startsWith("WrPC_")) {
        //Shared
        return false;
      }
    }
    //for now, we don't consider data shared across ports (i.e., across instructions) moving in sync
    return true;
  }

  /**
   * Determines if cancelResp should be considered (as backpressure on cancelReq) for the given spawn operation.
   * Callers should additionally check for existence of the cancelResp in BNode.
   * @param spawnBaseNode the spawn node, e.g. WrCUSTOMREG_spawn or WrMem_spawn
   * @param spawnStage the stage the spawn takes place in (Core or Decoupled stage)
   * @return true iff cancelResp should be considered (given that the node exists)
   */
  public static boolean hasCancelResp(SCAIEVNode spawnBaseNode, PipelineStage spawnStage) {
    // assuming there is no Sub and no CoreMultiport spawnStage
    assert(spawnStage.getKind() == StageKind.Core || spawnStage.getKind() == StageKind.Decoupled);
    return spawnStage.getKind() == StageKind.Core;
  }

  /**
   * Maps port stages of the given stream to the multi-port base/super stage (non-distinct).
   * @param stream input stream that will be devoured by map
   * @return result stream
   */
  public static Stream<PipelineStage> mapIntoMultiportBase(Stream<PipelineStage> stream) {
    return stream.map(intermStage -> intermStage.getTags().contains(StageTag.MultiportPipe)
                                           ? intermStage.getParent().orElseThrow()
                                           : intermStage);
  }

  /**
   * Maps multi-port stages of the given stream into the individual port stages.
   * The multi-port super stage will be removed from the stream. Keeps all non-multiport stages.
   * @param stream input stream that will be devoured by flatMap
   * @return result stream
   */
  public static Stream<PipelineStage> flatmapIntoPorts(Stream<PipelineStage> stream) {
    return stream.flatMap(intermStage -> intermStage.getKind() == StageKind.CoreMultiport
                                           ? intermStage.getChildren().stream().filter(st->st.getKind()==StageKind.Core)
                                           : Stream.of(intermStage));
  }

  /**
   * Adds in individual port stages to the stream after any multi-port stage.
   * Retains all stages in the stream, including the multi-port super stage.
   * @param stream input stream that will be devoured by flatMap
   * @return result stream
   */
  public static Stream<PipelineStage> flatmapAddPorts(Stream<PipelineStage> stream) {
    return stream.flatMap(intermStage -> Stream.concat(Stream.of(intermStage),
                                                       intermStage.getKind() == StageKind.CoreMultiport
                                                         ? intermStage.getChildren().stream().filter(st->st.getKind()==StageKind.Core)
                                                         : Stream.empty()));
  }

  /**
   * Adds in individual port stages to the stream before any multi-port stage.
   * Retains all stages in the stream, including the multi-port super stage.
   * @param stream input stream that will be devoured by flatMap
   * @return result stream
   */
  public static Stream<PipelineStage> flatmapAddPortsBefore(Stream<PipelineStage> stream) {
    return stream.flatMap(intermStage -> Stream.concat(intermStage.getKind() == StageKind.CoreMultiport
                                                         ? intermStage.getChildren().stream().filter(st->st.getKind()==StageKind.Core)
                                                         : Stream.empty(),
                                                       Stream.of(intermStage)));
  }
}
