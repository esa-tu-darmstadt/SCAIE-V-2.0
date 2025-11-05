package scaiev.scal.strategy.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.SCALUtil;
import scaiev.scal.TriggerableNodeLogicBuilder;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.pipeline.IDRetireSerializerStrategy.BitmaskRetireSource;
import scaiev.scal.strategy.pipeline.IDRetireSerializerStrategy.FlushResetAllSource;
import scaiev.scal.strategy.pipeline.IDRetireSerializerStrategy.IDAndCountRetireSource;
import scaiev.scal.strategy.pipeline.IDRetireSerializerStrategy.RetireSource;
import scaiev.scal.strategy.pipeline.IDRetireSerializerStrategy.StageBoundRetireSource;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.util.Log2;
import scaiev.util.Verilog;

/**
 * Summarizes all retire/flush sources into a set of ordered ID/valid signals.
 * Buffers unprocessed retires/flushes.
 * Applies backpressure to the assign sources (e.g., instruction issue)
 *  if there is an ID collision with a still-unprocessed retire.
 * <br/>
 * Assumes the first ID is 0 and are retired without gaps. A non-discarding retire takes priority over a discard.
 * In case a 'flush all' resets the next issue ID, see {@link RetireSource#resetIDToAfterDiscard(NodeRegistryRO, RequestedForSet, String, NodeLogicBlock)}.
 */
public class IDRetireSerializerStrategy extends MultiNodeStrategy {
  protected static final Logger logger = LogManager.getLogger();

  private static AtomicInteger nextUniqueID = new AtomicInteger(0);
  /** A unique instance number for this IDRetireSerializerStrategy object (_not_ an instruction ID) */
  int uniqueID;

  protected Verilog language;
  protected BNode bNodes;
  protected Core core;


  /** The width of each ID */
  int idWidth;
  /** The number of possible IDs */
  int idCount;

  /** Flag to pass on new retires from IDAndCountRetireSource combinationally */
  boolean combForward_IDAndCountSource;
  /** Flag to pass on new retires combinationally */
  boolean combForward_All;
  /** Flag to track issues and ignore invalid commits */
  boolean ignoreFlushNonissue;
  /** Flag to allow combinational commit during issue of same ID */
  boolean allowCombRetireOnIssue;

  /**
  * 
  * @param language The (Verilog) language object
  * @param bNodes The BNode object for the node instantiation
  * @param core The core nodes description
  * @param idWidth The width of each ID
  * @param idCount The size of the ID space: [0..idCount-1]
  * @param combForward_IDAndCountSource If true, will also pass on new retires from IDAndCountRetireSource combinationally.
  * @param combForward_All If true, will also pass on new retires of any type combinationally.
  * @param ignoreFlushNonissue If true, will track issued IDs and ignore commits of non-issued IDs.
  *                            Caused undefined behavior for IDAndCountRetireSource with non-issued IDs if combForward_IDAndCountSource is enabled.
  *                            Will also trigger a sim warning if a non-flush retire occurs on a non-issued ID.
  * @param allowCombRetireOnIssue If true and ignoreFlushNonissue, will allow flushes/retires on IDs that are still being issued.
  * @param assignIDSources The ID assign sources to apply backpressure to in case of collisions. Ignores {@link IDMapperStrategy.IDSource#key_relevant}.
  * @param retireSources The retire sources to observe (typically {@link IDAndCountRetireSource}, {@link BitmaskRetireSource})
  */
  public IDRetireSerializerStrategy(Verilog language, BNode bNodes, Core core,
      int idWidth, int idCount,
      boolean combForward_IDAndCountSource, boolean combForward_All,
      boolean ignoreFlushNonissue, boolean allowCombRetireOnIssue,
      List<IDMapperStrategy.IDSource> assignIDSources,
      List<RetireSource> retireSources) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.assignIDSources = List.copyOf(assignIDSources);
    this.retireSources = List.copyOf(retireSources);

    this.idWidth = idWidth;
    this.idCount = idCount;
    if (idWidth <= 0) {
      throw new IllegalArgumentException("idWidth must be above 0");
    }
    if (idCount <= (1 << (idWidth - 1))) {
      throw new IllegalArgumentException("idCount must be in 2**(idWidth-1)+1 .. 2**idWidth (incl.)");
    }
    this.combForward_IDAndCountSource = combForward_IDAndCountSource;
    this.combForward_All = combForward_All;
    this.ignoreFlushNonissue = ignoreFlushNonissue;
    this.allowCombRetireOnIssue = allowCombRetireOnIssue;

    this.uniqueID = nextUniqueID.getAndIncrement();
    PipelineStage referenceStage = this.retireSources.isEmpty() ? this.core.GetRootStage() : this.retireSources.get(0).getReferenceStage();
    this.signals = new SerialRetireSignals(referenceStage, uniqueID, idWidth, idCount);
    this.builderTriggerKey = new NodeInstanceDesc.Key(Purpose.REGULAR, new SCAIEVNode("SerialRetire_Trigger" + uniqueID), referenceStage, "");
  }

  List<IDMapperStrategy.IDSource> assignIDSources;
  List<RetireSource> retireSources;

  /**
   * Returns the issue trigger PipelineFront for this serializer.
   */
  public PipelineFront getIssueFront() {
    return new PipelineFront(this.assignIDSources.stream().map(idSource->idSource.getTriggerStage()));
  }

  /**
   * Returns an expression whether a stage is proceeding,
   *  based on (Rd|Wr)Stall and (Rd|Wr)Flush.
   * @param registry
   * @param bNodes
   * @param stage
   * @return
   */
  private static String makeStageNonstallExpr(NodeRegistryRO registry, BNode bNodes, PipelineStage stage, boolean withParens) {
    String ret = SCALUtil.buildCond_StageNotStalling(bNodes, registry, stage, true);
    if (withParens)
      return "("+ret+")";
    return ret;
  }

  /**
   * Generates a mask of retiring IDs.
   */
  public static abstract class RetireSource {
    boolean is_discard;
    public RetireSource(boolean is_discard) {
      this.is_discard = is_discard;
    }
    /**
     * Iff true, the retires from this source are for discarded instructions
     */
    public boolean isDiscard() {
      return is_discard;
    }
    /**
     * Builds an expression for the mask of retiring IDs.
     * @param registry
     * @param requestedFor
     * @param namePrefix the unique prefix to use for variable declarations
     * @param into the NodeLogicBlock to place declarations and logic in
     * @return an indexable expression for the mask (i.e., a wire name)
     */
    public abstract String buildAsMask(NodeRegistryRO registry, RequestedForSet requestedFor, String namePrefix, NodeLogicBlock into);

    /**
     * Retrieves some stage related to this source, to place logic nodes in an intuitive location.
     * No specific requirements on how this stage relates to the core.
     */
    public abstract PipelineStage getReferenceStage();

    /**
     * Retrieves the 'next retire' ID to reset to after a discard completed.
     * @param registry
     * @param requestedFor
     * @param namePrefix_mask the unique prefix also used in the buildAsMask call
     * @param into the NodeLogicBlock to place declarations and logic in
     * @return the ID expression (wire name) to reset to;
     *         can be null if this source is not discarding or does not reset the ID
     */
    public String resetIDToAfterDiscard(NodeRegistryRO registry, RequestedForSet requestedFor, String namePrefix_mask, NodeLogicBlock into) {
      return null;
    }
  }
  /**
   * A retire source based on 'first ID', 'number of IDs to retire'.
   */
  public static class IDAndCountRetireSource extends RetireSource {
    NodeInstanceDesc.Key key_id;
    NodeInstanceDesc.Key key_count;
    public String getCountExpr(NodeRegistryRO registry, RequestedForSet requestedFor, boolean withParens) {
      assert(key_count != null);
      NodeInstanceDesc countInst = registry.lookupRequired(key_count, requestedFor);
      return withParens ? countInst.getExpressionWithParens() : countInst.getExpression();
    }
    public int getMaxConcurrentRetires() {
      assert(key_count != null);
      return key_count.getNode().elements;
    }
    /**
     * 
     * @param key_id the key for the ID, with idKey.getNode().size being accurate
     *              and [0, idKey.getNode().elements) being the range of IDs
     * @param key_count the key for the number of retires performed, starting with the ID
     * @param is_discard iff true, the retires are for discarded instructions
     */
    public IDAndCountRetireSource(NodeInstanceDesc.Key key_id, NodeInstanceDesc.Key key_count, boolean is_discard) {
      super(is_discard);
      this.key_id = key_id;
      this.key_count = key_count;
    }

    @Override
    public String buildAsMask(NodeRegistryRO registry, RequestedForSet requestedFor, String namePrefix, NodeLogicBlock into) {
      NodeInstanceDesc idInst = registry.lookupRequired(key_id, requestedFor);
      String idWire;
      if (idInst.getExpressionType() == ExpressionType.WireName)
        idWire = idInst.getExpression();
      else {
        idWire = namePrefix + "_id";
        into.declarations += String.format("logic [%d-1:0] %s;\n", key_id.getNode().size, idWire);
        into.logic += String.format("assign %s = %s;\n", idWire, idInst.getExpression());
      }

      int countWidth = key_count == null ? Log2.clog2(key_id.getNode().elements + 1) : key_count.getNode().size;
      int maxRetires = getMaxConcurrentRetires();
      String countWire = namePrefix + "_count";
      into.declarations += String.format("logic [%d-1:0] %s;\n", countWidth, countWire);
      into.logic += String.format("assign %s = %s;\n", countWire, getCountExpr(registry, requestedFor, false));
      int countmaskWidth = Math.min(maxRetires, key_id.getNode().elements);
      String countmaskWire = namePrefix + "_countmask";
      into.declarations += String.format("logic [%d-1:0] %s;\n", countmaskWidth, countmaskWire);
      into.logic += String.format("assign %s = {%s};\n", countmaskWire,
                                  IntStream.range(0,countmaskWidth)
                                  .map(i->countmaskWidth-1-i)
                                  .mapToObj(i->String.format("%s > %d'd%d", countWire, countWidth, i))
                                  .reduce((a,b)->a+", "+b).orElse(""));

      String maskWire = namePrefix;
      into.declarations += String.format("logic [%d-1:0] %s;\n", key_id.getNode().elements, maskWire);
      String expr = String.format("(%d'(%s) << %s) | (%d'(%s) >> %d'(~%s + 1))",
                                    key_id.getNode().elements, countmaskWire, idWire,
                                    key_id.getNode().elements, countmaskWire, key_id.getNode().size, idWire);
      into.logic += String.format("assign %s = %s;\n", maskWire, expr);
      return maskWire;
    }

    @Override
    public PipelineStage getReferenceStage() {
      return key_id.getStage();
    }
  }
  /**
   * A retire source (!is_discard), based on 'ID', waiting for !stall &amp;&amp; !flush.
   */
  public static class StageBoundRetireSource extends IDAndCountRetireSource {
    BNode bNodes;
    /**
     * 
     * @param key_id the key for the ID, with idKey.getNode().size being accurate
     * @param bNodes the BNode instance, needed to get the stall and flush nodes
     */
    public StageBoundRetireSource(NodeInstanceDesc.Key key_id, BNode bNodes) {
      super(key_id, null, false);
      this.bNodes = bNodes;
    }

    @Override
    public String getCountExpr(NodeRegistryRO registry, RequestedForSet requestedFor, boolean withParens) {
      PipelineStage stage = key_id.getStage();
      assert(stage == getReferenceStage());
      return makeStageNonstallExpr(registry, bNodes, stage, withParens);
    }
    @Override
    public int getMaxConcurrentRetires() {
      return 1;
    }
  }
  /**
   * A retire source from a bitmask (1=retiring, 0=in-flight/bubble).
   */
  public static class BitmaskRetireSource extends RetireSource {
    NodeInstanceDesc.Key key_bitmask;
    /**
     * 
     * @param key_bitmask The key for the bitmask
     * @param is_discard Iff true, the retires are for discarded instructions
     */
    public BitmaskRetireSource(NodeInstanceDesc.Key key_bitmask, boolean is_discard) {
      super(is_discard);
      this.key_bitmask = key_bitmask;
    }

    @Override
    public String buildAsMask(NodeRegistryRO registry, RequestedForSet requestedFor, String namePrefix, NodeLogicBlock into) {
      NodeInstanceDesc maskInst = registry.lookupRequired(key_bitmask, requestedFor);
      String maskWire;
      if (maskInst.getExpressionType() == ExpressionType.WireName)
        maskWire = maskInst.getExpression();
      else {
        maskWire = namePrefix;
        into.declarations += String.format("logic [%d-1:0] %s;\n", maskInst.getKey().getNode().size, maskWire);
        into.logic += String.format("assign %s = %s;\n", maskWire, maskInst.getExpression());
      }
      return maskWire;
    }

    @Override
    public PipelineStage getReferenceStage() {
      return key_bitmask.getStage();
    }
  }
  /**
   * A retire source discarding all instructions if a condition key applies.
   * 
   * Consider setting ignoreFlushNonissue to true if clients of this strategy assume prior issue.
   */
  public static class FlushResetAllSource extends RetireSource {
    PipelineStage referenceStage;
    int idWidth;
    int idCount;
    NodeInstanceDesc.Key key_resetCond;
    Optional<NodeInstanceDesc.Key> key_resetIDTo;
    /**
     * @param referenceStage a stage to possibly create node keys into (no semantic meaning)
     * @param idWidth The width of an ID
     * @param idCount The number of possible IDs
     * @param key_resetCond The condition for a full discard
     * @param key_resetIDTo (optional) The key to the expression to reset to (e.g., constant "'1")
     */
    public FlushResetAllSource(PipelineStage referenceStage,
                               int idWidth, int idCount,
                               NodeInstanceDesc.Key key_resetCond,
                               Optional<NodeInstanceDesc.Key> key_resetIDTo) {
      super(true);
      this.referenceStage = referenceStage;
      this.idWidth = idWidth;
      this.idCount = idCount;
      this.key_resetCond = key_resetCond;
      this.key_resetIDTo = key_resetIDTo;
    }

    @Override
    public String buildAsMask(NodeRegistryRO registry, RequestedForSet requestedFor, String namePrefix, NodeLogicBlock into) {
      String maskWire = namePrefix;
      into.declarations += String.format("logic [%d-1:0] %s;\n", idCount, maskWire);
      into.logic += String.format("assign %s = {%d{%s}};\n", maskWire, idCount, registry.lookupExpressionRequired(key_resetCond, requestedFor));
      return maskWire;
    }

    @Override
    public PipelineStage getReferenceStage() {
      return referenceStage;
    }

    @Override
    public String resetIDToAfterDiscard(NodeRegistryRO registry, RequestedForSet requestedFor, String namePrefix_mask,
        NodeLogicBlock into) {
      if (!key_resetIDTo.isPresent())
        return null;
      String resetToWire = namePrefix_mask + "_resetIDTo";
      into.declarations += String.format("logic [%d-1:0] %s;\n", idWidth, resetToWire);
      into.logic += String.format("assign %s = %s;\n", resetToWire, registry.lookupExpressionRequired(key_resetIDTo.get()));
      return resetToWire;
    }
  }
  
  /**
   * Creates the RetireSources for the IDRetireSerializerStrategy.
   */
  protected static List<RetireSource> constructRetireSources(BNode bNodes, Core core) {
    var ret = new ArrayList<IDRetireSerializerStrategy.RetireSource>();
    PipelineFront coreCommitFront = new PipelineFront(core.GetRootStage().getAllChildren()
                                                     .filter(stage -> stage.getKind() == StageKind.Core
                                                                      || stage.getKind() == StageKind.CoreInternal)
                                                     .filter(stage -> stage.getTags().contains(StageTag.Commit)));
    if (coreCommitFront.asList().isEmpty()) {
      if (bNodes.RdCommitIDCount.size > 0 && bNodes.RdCommitIDCount.elements > 0) {
        assert(bNodes.RdCommitIDCount.size == Log2.clog2(bNodes.RdCommitIDCount.elements+1));
        ret.add(new IDAndCountRetireSource(
                    new NodeInstanceDesc.Key(bNodes.RdCommitID, core.GetRootStage(), ""),
                    new NodeInstanceDesc.Key(bNodes.RdCommitIDCount, core.GetRootStage(), ""),
                    false));
      }
    }
    else {
      coreCommitFront.asList().forEach(commitStage -> {
        var key = new NodeInstanceDesc.Key(bNodes.RdIssueID, commitStage, "");
        ret.add(new StageBoundRetireSource(key, bNodes));
      });
    }
    if (bNodes.RdCommitFlushIDCount.size > 0 && bNodes.RdCommitFlushIDCount.elements > 0) {
      assert(bNodes.RdCommitFlushIDCount.size == Log2.clog2(bNodes.RdCommitFlushIDCount.elements+1));
      ret.add(new IDAndCountRetireSource(
                  new NodeInstanceDesc.Key(bNodes.RdCommitFlushID, core.GetRootStage(), ""),
                  new NodeInstanceDesc.Key(bNodes.RdCommitFlushIDCount, core.GetRootStage(), ""),
                  true));
    }
    if (bNodes.RdCommitFlushMask.size > 0) {
      ret.add(new BitmaskRetireSource(
                  new NodeInstanceDesc.Key(bNodes.RdCommitFlushMask, core.GetRootStage(), ""),
                  true));
    }
    if (bNodes.RdCommitFlushAll.size > 0) {
      assert(bNodes.RdCommitFlushAll.size == 1);
      assert(bNodes.RdCommitFlushAllID.size == 0 || bNodes.RdCommitFlushAllID.size == bNodes.RdIssueID.size);
      ret.add(new FlushResetAllSource(core.GetRootStage(),
                 bNodes.RdIssueID.size, bNodes.RdIssueID.elements,
                 new NodeInstanceDesc.Key(bNodes.RdCommitFlushAll, core.GetRootStage(), ""),
                 (bNodes.RdCommitFlushAllID.size > 0)
                      ? Optional.of(new NodeInstanceDesc.Key(bNodes.RdCommitFlushAllID, core.GetRootStage(), ""))
                      : Optional.empty()));
    }
    return ret;
  }

  /**
   * Constructs an IDRetireSerializerStrategy using the core's Issue stages.
   */
  public static IDRetireSerializerStrategy constructRetireSerializer(Verilog language, BNode bNodes, Core core) {
    //Select all first-reachable Issue stages.
    List<PipelineStage> assignStages = core.GetRootStage().getChildren().stream()
                                       .flatMap(child -> child.streamNext_bfs(stage->!stage.getTags().contains(StageTag.Issue)))
                                       .filter(stage->stage.getTags().contains(StageTag.Issue))
                                       .toList();
    //Should not contain duplicates, assuming core.GetRootStage().getChildren() returns sub-pipelines without any common sub-graphs.
    assert(assignStages.stream().distinct().count() == assignStages.size());

    List<IDMapperStrategy.IDSource> assignSources = IntStream.range(0, assignStages.size()).mapToObj(iAssign -> {
      return new IDMapperStrategy.IDSource(
          new NodeInstanceDesc.Key(bNodes.RdIssueID, assignStages.get(iAssign), ""),
          Optional.empty(),
          null, //not used by IDRetireSerializerStrategy
          Optional.empty());
    }).toList();
    return new IDRetireSerializerStrategy(language, bNodes, core,
        bNodes.RdIssueID.size,
        bNodes.RdIssueID.elements,
        true, false, //TODO configurable
                     //Note: IDBasedDHCommitHandler does not support retire during assign/issue
                     // -> CVA5, CVA6: forwarding is viable, as retire happens at least one cycle after issue
        false, false, //core-specific; constructRetireSerializer method overrides may be required
        assignSources, constructRetireSources(bNodes, core));
  }

  public static class SerialRetireSignals {
    NodeInstanceDesc.Key key_retireID;
    NodeInstanceDesc.Key key_valid;
    NodeInstanceDesc.Key key_isDiscard;

    SCAIEVNode node_stall;
    NodeInstanceDesc.Key key_stall;

    int widthLimit = Integer.MAX_VALUE;

    SerialRetireSignals(PipelineStage stage, int uniqueID, int idWidth, int idCount) {
      var idNode = new SCAIEVNode("SerialRetire_ID_" + uniqueID, idWidth, false);
      idNode.elements = idCount;
      key_retireID = new NodeInstanceDesc.Key(Purpose.REGULAR, idNode, stage, "");
      key_valid = new NodeInstanceDesc.Key(Purpose.REGULAR, new SCAIEVNode("SerialRetire_IDValid_" + uniqueID, 1, false), stage, "");
      key_isDiscard = new NodeInstanceDesc.Key(Purpose.REGULAR, new SCAIEVNode("SerialRetire_IDIsDiscard_" + uniqueID, 1, false), stage, "");
      node_stall = new SCAIEVNode("SerialRetire_Stall_" + uniqueID + "_retport", 1, false);
    }

    /**
     * The ID being retired.
     * Request with i in [0..widthLimit-1]. Out-of-range keys will return 0.
     */
    public NodeInstanceDesc.Key getKey_retireID(int i) {
      //Use the aux field for the port number.
      //These ports are not statically allocated,
      //  so SCAIEVNode#makePortNodeOf is not the tool of choice here.
      return NodeInstanceDesc.Key.keyWithPurposeAux(key_retireID, Purpose.REGULAR, i);
    }

    /**
     * Whether the retire is valid (may be a discard). Note: Does not include the {@link #getKey_stall(int)} condition.
     * Request with i in [0..widthLimit-1]. Out-of-range keys will return 0.
     */
    public NodeInstanceDesc.Key getKey_valid(int i) {
      //use the aux field for the port number
      return NodeInstanceDesc.Key.keyWithPurposeAux(key_valid, Purpose.REGULAR, i);
    }

    /**
     * Whether the retire is a discard.
     * Request with i in [0..widthLimit-1]. Out-of-range keys will return 0.
     */
    public NodeInstanceDesc.Key getKey_isDiscard(int i) {
      //use the aux field for the port number
      return NodeInstanceDesc.Key.keyWithPurposeAux(key_isDiscard, Purpose.REGULAR, i);
    }

    /**
     * The stall signal.
     * Like WrStall, to add a stall subcondition, each user should call lookupRequired(getKey_stall(i))
     *  and add its output to the key with unique aux.
     * When using IDMapperStrategy in conjunction with this,
     *  the caller may want to apply the stall signal to {@link IDMapperStrategy.IDSource#key_valid}.
     * The stall signals for any port i+k, given the port exists physically, inherit the stall from port i.
     * Request with i in [0..widthLimit-1]. Out-of-range keys will return 0.
     */
    public NodeInstanceDesc.Key getKey_stall(int i) {
      //Embed the index in the node name
      return new NodeInstanceDesc.Key(Purpose.REGULAR, new SCAIEVNode(node_stall.name + i, 1, false), key_valid.getStage(), "");
    }

    /**
     * The width limit, defined by the narrowest retire consumer.
     * Note: Is initialized to Integer.MAX_VALUE.
     * Important: Call {@link IDRetireSerializerStrategy#registerRetireConsumer(int)} to ensure the retire signals are created properly.
     */
    public int getWidthLimit() {
      return widthLimit;
    }
  }
  private SerialRetireSignals signals;

  /**
   * 'Registers' a consumer of the retire signals ({@link #getSignals()}),
   *  with a given width limit.
   * The width limit should be at least the core's retire width to prevent bottlenecks.
   * If only certain instruction IDs are relevant (e.g., tracked with IDMapperStrategy),
   *  it may be advisable to select the first match(es) out of the retires and apply the stall signal on any other matches ({@link SerialRetireSignals#getKey_stall(int)}).
   * @param maxRetires
   */
  public void registerRetireConsumer(int maxRetires) {
    signals.widthLimit = Math.min(signals.widthLimit, maxRetires);
  }
  /**
   * Retrieves the signals object of this strategy.
   */
  public SerialRetireSignals getSignals() {
    return signals;
  }

  List<Integer> requestedPorts = new ArrayList<>();
  NodeInstanceDesc.Key builderTriggerKey;

  /** Builds the complete logic for the retire serializer. */
  protected NodeLogicBlock build(NodeRegistryRO registry, RequestedForSet requestedFor, int aux) {
    NodeLogicBlock ret = new NodeLogicBlock();
    //Register storing the mask of retired instructions still to be processed
    String mask_regName = String.format("idretire_%d_mask_r", uniqueID);
    //Wire, mask with 0s for each retired instruction finishing processing in this cycle.
    String maskclear_wireName = String.format("idretire_%d_mask_clear", uniqueID);
    //Wire, mask with 1s for each instruction retiring in this cycle.
    String masknew_wireName = String.format("idretire_%d_mask_new", uniqueID);
    ret.declarations += String.format("logic [%d-1:0] %s;\n", idCount, mask_regName);
    ret.declarations += String.format("logic [%d-1:0] %s;\n", idCount, maskclear_wireName);
    ret.declarations += String.format("logic [%d-1:0] %s;\n", idCount, masknew_wireName);

    //Register storing the 'is not a discard' flag alongside mask_regName.
    String masknodiscard_regName = String.format("idretire_%d_isnodiscard_r", uniqueID);
    //Wire for the new 'is not a discard' flags alongside masknew_wireName.
    // 1-bits are only allowed if masknew_wireName has a corresponding 1-bit.
    String masknodiscardnew_wireName = String.format("idretire_%d_isnodiscard_new", uniqueID);
    ret.declarations += String.format("logic [%d-1:0] %s;\n", idCount, masknodiscard_regName);
    ret.declarations += String.format("logic [%d-1:0] %s;\n", idCount, masknodiscardnew_wireName);

    //Register storing the next ID to present to the SerialRetireSignals.
    String id_next_regName = String.format("idretire_%d_next_r", uniqueID);
    ret.declarations += String.format("logic [%d-1:0] %s;\n", idWidth, id_next_regName);

    //Build the masks for each retire source.
    String[] masknew_wireName_bySource = IntStream.range(0, this.retireSources.size())
        .mapToObj((i) -> {
          return retireSources.get(i).buildAsMask(registry, requestedFor,
              String.format("idretire_%d_source%d_mask_new", uniqueID, i), ret);
        }).toArray((n) -> new String[n]);
    String[] id_after_discard_bySource = IntStream.range(0, this.retireSources.size())
        .mapToObj((i) -> {
          if (!retireSources.get(i).isDiscard())
            return null;
          return retireSources.get(i).resetIDToAfterDiscard(registry, requestedFor,
              String.format("idretire_%d_source%d_mask_new", uniqueID, i), ret);
        }).toArray((n) -> new String[n]);
    //masknewExpr[0]: Valid mask. masknewExpr[1]: Is No-Discard mask.
    // -> "is not a discard" is sticky, so a discard cannot affect a committing nor previously committed instruction.
    String[] masknewExpr = IntStream.range(0, this.retireSources.size())
        .mapToObj((i) -> {
            String validMask = masknew_wireName_bySource[i];
            String nodiscardMask = retireSources.get(i).is_discard ? "'0" : validMask;
            return new String[] {validMask, nodiscardMask};
        })
        //reduce valid and 'no discard' masks
        .reduce((a,b) -> new String[] {a[0] + " | " + b[0], a[1] + " | " + b[1]})
        .orElse(new String[]{"'0", "'0"});

    //Optionally track issues and filter retires that weren't issued.
    String masknew_filter_expr = "";
    if (ignoreFlushNonissue) {
      String issued_regName = String.format("idretire_%d_issued_mask_r", uniqueID);
      String issuednew_wireName = String.format("idretire_%d_issued_mask_new", uniqueID);
      ret.declarations += String.format("logic [%d-1:0] %s;\n", idCount, issued_regName);
      ret.declarations += String.format("logic [%d-1:0] %s;\n", idCount, issuednew_wireName);
      masknew_filter_expr = issued_regName;
      if (allowCombRetireOnIssue) {
        //Add combinational issues to the mask.
        String issued_wireName = String.format("idretire_%d_issued_mask_s", uniqueID);
        ret.declarations += String.format("logic [%d-1:0] %s;\n", idCount, issued_wireName);
        ret.logic += String.format("assign %s = %s | %s;\n", issued_wireName, issued_regName, issuednew_wireName);
        masknew_filter_expr = issued_wireName;
      }
      var issueLogic = new StringBuilder();
      issueLogic.append("""
          always_comb begin
              %s = '0;
          """.formatted(issuednew_wireName));
      //Set the mask bit of each issued ID to 1.
      for (var source : assignIDSources) {
        String idExpr = registry.lookupExpressionRequired(source.key_ID, requestedFor);
        String validExpr;
        if (source.key_valid.isPresent())
          validExpr = registry.lookupExpressionRequired(source.key_valid.get(), requestedFor);
        else {
          //AND of RdStall, RdFlush, etc.
          validExpr = makeStageNonstallExpr(registry, bNodes, source.getTriggerStage(), false);
        }
        issueLogic.append(String.format("    if (%s) %s[%s] = 1'b1;\n", validExpr, issuednew_wireName, idExpr));
      }
      issueLogic.append("end\n");
      ret.logic += issueLogic.toString();
      //Issue mask register logic
      ret.logic += """
          always_ff @(posedge %s) begin
              if (%s) %s <= '0;
              else begin
                  if ((~%s & %s & %s) != '0) //not issued & retiring & not discarded
                      $error("A retire was triggered on an ID that was not issued");
                  %s <= (%s & %s) | %s;
              end
          end
          """.formatted(
              language.clk,
              language.reset, issued_regName,
              masknew_filter_expr, masknew_wireName, masknodiscardnew_wireName,
              issued_regName, issued_regName, maskclear_wireName, issuednew_wireName
              );
    }

    ret.logic += String.format("assign %s = %s;\n", masknew_wireName, masknewExpr[0]);
    ret.logic += String.format("assign %s = %s;\n", masknodiscardnew_wireName, masknewExpr[1]);

    //Logic head for maskclear_wireName.
    // Per-port conditions added in the for-loop below.
    StringBuilder clearMaskLogicBuilder = new StringBuilder();
    clearMaskLogicBuilder.append("""
        always_comb begin
            %s = '1;
        """.formatted(maskclear_wireName));

    String cumulativeValid = "";
    String cumulativeStall = "";

    boolean[] is_requested = new boolean[signals.widthLimit == Integer.MAX_VALUE ? 0 : Math.min(signals.widthLimit, idCount)];
    //Utility storing the keys/wires for each processed port of this.signals.
    class PortBuildResult {
      NodeInstanceDesc.Key idKey;
      NodeInstanceDesc.Key validKey;
      NodeInstanceDesc.Key isDiscardKey;
      NodeInstanceDesc.Key stallKey;

      String idExpr;
      String validExpr;
      String isDiscardExpr;
      String stallExpr;
    }
    PortBuildResult[] results = new PortBuildResult[is_requested.length];

    requestedPorts.forEach(iPort -> {if (iPort < is_requested.length) is_requested[iPort] = true;});

    for (int iPort = 0; iPort < is_requested.length; ++iPort) {
      var result = new PortBuildResult();
      results[iPort] = result;

      result.idKey = signals.getKey_retireID(iPort);
      result.validKey = signals.getKey_valid(iPort);
      result.isDiscardKey = signals.getKey_isDiscard(iPort);
      result.stallKey = signals.getKey_stall(iPort);

      result.idExpr = result.idKey.toString(false);
      ret.declarations += String.format("logic [%d-1:0] %s;\n", idWidth, result.idExpr);
      result.validExpr = result.validKey.toString(false);
      ret.declarations += String.format("logic %s;\n", result.validExpr);
      result.isDiscardExpr = result.isDiscardKey.toString(false);
      ret.declarations += String.format("logic %s;\n", result.isDiscardExpr);
      result.stallExpr = result.stallKey.toString(false);
      ret.declarations += String.format("logic %s;\n", result.stallExpr);

      if (is_requested[iPort]) {
        //Present results to the downstream.
        // Otherwise, hide from the downstream until requested (i.e., lookupRequired called).
        ret.outputs.add(new NodeInstanceDesc(result.idKey, result.idExpr, ExpressionType.WireName, requestedFor));
        ret.outputs.add(new NodeInstanceDesc(result.validKey, result.validExpr, ExpressionType.WireName, requestedFor));
        ret.outputs.add(new NodeInstanceDesc(result.isDiscardKey, result.isDiscardExpr, ExpressionType.WireName, requestedFor));
        ret.outputs.add(new NodeInstanceDesc(result.stallKey, result.stallExpr, ExpressionType.WireName, requestedFor));
      }

      //Create ID
      if (idCount == (1 << idWidth)) {
        //ID[iPort] = id_next + iPort
        ret.logic += String.format("assign %s = %s + %d'd%d;\n", result.idExpr, id_next_regName, idWidth, iPort);
      }
      else {
        //Wrap around logic
        ret.logic += String.format("assign %s = (%s >= %d'd%d) ? (%s - %d'd%d) : (%s + %d'd%d);\n",
            result.idExpr,
            id_next_regName, idWidth, idCount - iPort,
            id_next_regName, idWidth, idCount - iPort,
            id_next_regName, idWidth, iPort);
      }

      //Create valid, isDiscard
      String validCond = String.format("%s[%s]", mask_regName, result.idExpr);
      String isDiscardCond = String.format("!%s[%s]", masknodiscard_regName, result.idExpr);
      if (this.combForward_All || this.combForward_IDAndCountSource) {
        String _masknew_filter_expr = masknew_filter_expr; //effectively final..
        //Forward from the retire source
        String[] addValidAndIsDiscard = IntStream.range(0, this.retireSources.size())
            .mapToObj((i) -> {
              RetireSource retireSource = this.retireSources.get(i);

              String curIsDiscardCond = retireSource.is_discard ? "1'b1" : "1'b0";
              if (retireSource instanceof IDAndCountRetireSource) {
                //Directly compare a span of IDs.
                var idAndCountSource = (IDAndCountRetireSource)retireSource;
                String curIDExpr = registry.lookupExpressionRequired(idAndCountSource.key_id, requestedFor);
                String curCountExpr = registry.lookupExpressionRequired(idAndCountSource.key_count, requestedFor);
                if (idCount == (1 << idWidth))
                  return new String[] { String.format("(%s - %s < %d'(%s))", result.idExpr, curIDExpr, idWidth, curCountExpr), curIsDiscardCond };
                //Explicit wraparound case check
                //IDs covered by this source: [curIDExpr, wrap(curIDExpr+1), ..., wrap(curIDExpr+curCountExpr-1)]
                //If idWire < curIDExpr, the retire only is a match if it wraps around.
                // idCount - curCountExpr is the number of IDs _not_ covered by the retire.
                // If that number of IDs fits in [idWire+1, curIDExpr-1], we know idWire is covered by the retire.
                //  <=> (curIDExpr-idWire-1 >= <number>) <=> (curIDExpr-idWire > <number>),
                return new String[] {
                    String.format("(%s < %s) ? (%s - %s > %d'd%d - %d'(%s)) : (%s - %s < %d'(%s))",
                                  result.idExpr, curIDExpr,
                                  curIDExpr, result.idExpr, idWidth, idCount, idWidth, curCountExpr,
                                  result.idExpr, curIDExpr, idWidth, curCountExpr),
                    curIsDiscardCond
                };
              }
              else if (combForward_All) {
                //Check the produced mask.
                return new String[] {
                    String.format("%s[%s]", masknew_wireName_bySource[i], result.idExpr)
                     + (!_masknew_filter_expr.isEmpty() ? (String.format(" && %s[%s]", _masknew_filter_expr, result.idExpr)) : ""),
                    curIsDiscardCond
                };
              }
              return new String[] {"", ""};
            })
            //Make the 'is discard' condition depend on validity.
            .map(a -> new String[] {a[0], a[0].isEmpty() ? "" : String.format("(%s && %s)", a[0], a[1])})
            //OR each valid condition, each discard condition (ignoring empty entries)
            .reduce((a,b) -> //OR valid and discard conditions separately.
                      a[0].isEmpty() ? b //If a is empty, just use b
                      : (b[0].isEmpty() ? a //If b is empty, just use a
                        : new String[]{a[0] + " || " + b[0], a[1] + " || " + b[1]}))
            .orElse(new String[] {"", ""});
        if (!addValidAndIsDiscard[0].isEmpty()) {
          isDiscardCond = String.format("(%s && %s)", validCond, isDiscardCond);
          validCond += " || " + addValidAndIsDiscard[0];
          isDiscardCond += " || " + addValidAndIsDiscard[1];
        }
      }

      ret.logic += String.format("assign %s = %s(%s);\n", result.validExpr, cumulativeValid, validCond);
      ret.logic += String.format("assign %s = %s;\n", result.isDiscardExpr, isDiscardCond);

      //Create stall condition.
      StringBuilder stallCondBuilder = new StringBuilder();
      for (NodeInstanceDesc stallSubcond : registry.lookupAll(result.stallKey, false)) {
        if (!stallCondBuilder.isEmpty())
          stallCondBuilder.append(" || ");
        stallCondBuilder.append(stallSubcond.getExpressionWithParens());
      }
      ret.logic += String.format("assign %s = %s%s;\n", result.stallExpr, cumulativeStall, stallCondBuilder.toString());

      cumulativeValid += String.format("%s && ", result.validExpr);
      cumulativeStall += String.format("%s || ", result.stallExpr);

      //Clear the valid mask if this entry is not stalling.
      clearMaskLogicBuilder.append("""
              if (%s && !%s) begin
                  %s[%s] = 1'b0;
              end
          """.formatted(result.validExpr, result.stallExpr, maskclear_wireName, result.idExpr));
    }

    //Assign the out-of-range ports to defaults (= no retire).
    // -> this can happen if the retire listeners have different widths
    for (int iPort : requestedPorts) {
      assert(signals.widthLimit != Integer.MAX_VALUE);
      if (iPort >= is_requested.length) {
        var idKey = signals.getKey_retireID(iPort);
        var validKey = signals.getKey_valid(iPort);
        var isDiscardKey = signals.getKey_isDiscard(iPort);
        var stallKey = signals.getKey_stall(iPort);

        ret.outputs.add(new NodeInstanceDesc(idKey, String.format("%d'd0", idWidth), ExpressionType.AnyExpression_Noparen, requestedFor));
        ret.outputs.add(new NodeInstanceDesc(validKey, "1'b0", ExpressionType.AnyExpression_Noparen, requestedFor));
        ret.outputs.add(new NodeInstanceDesc(isDiscardKey, "1'b0", ExpressionType.AnyExpression_Noparen, requestedFor));
        ret.outputs.add(new NodeInstanceDesc(stallKey, "1'b0", ExpressionType.AnyExpression_Noparen, requestedFor));
      }
    }

    //Add the 'unmask' logic to the block.
    clearMaskLogicBuilder.append("end\n");
    ret.logic += clearMaskLogicBuilder.toString();

    //For resetIDToAfterDiscard support:
    // Condition if the ID is being reset, waiting for all discards to finish.
    String idResettingExpr = "";
    String idResetToReg = "";
    if (Stream.of(id_after_discard_bySource).anyMatch(idExpr -> idExpr != null)) {
      //Register: Condition if we are waiting for the discard to run through the downstream (i.e. users of this.signals).
      idResettingExpr = String.format("idretire_%d_ondiscarded_valid_r", uniqueID);
      ret.declarations += String.format("logic %s;\n", idResettingExpr);
      //Register: New ID to apply when the discards have run through.
      idResetToReg = String.format("idretire_%d_ondiscarded_next_r", uniqueID);
      ret.declarations += String.format("logic [%d-1:0] %s;\n", idWidth, idResetToReg);

      var resetIDLogic = new StringBuilder();
      resetIDLogic.append("""
          always_ff @(posedge %s) begin
              if (%s) begin
                  %s <= 1'b0;
                  %s <= '0;
              end
          """.formatted(
              language.clk,
              language.reset,
              idResettingExpr,
              idResetToReg));
      //For each (discarding) retire source with 'reset ID to' set,
      // add an else if block.
      for (int iRetire = 0; iRetire < retireSources.size(); ++iRetire) {
        String idResetToExpr = id_after_discard_bySource[iRetire];
        String newMaskExpr = masknew_wireName_bySource[iRetire];
        if (idResetToExpr == null)
          continue;
        resetIDLogic.append("""
                else if (|%s) begin
                    %s <= 1'b1;
                    %s <= %s;
                end
            """.formatted(
                newMaskExpr,
                idResettingExpr,
                idResetToReg, idResetToExpr));
      }
      //Reset idResettingExpr once the 'next ID' logic
      // (constructed below) has applied the ID reset.
      resetIDLogic.append("""
              if (%s && !(|%s)) begin
                  %s <= 1'b0;
              end
          """.formatted(
              idResettingExpr, mask_regName,
              idResettingExpr));
      resetIDLogic.append("end\n");
      ret.logic += resetIDLogic.toString();
    }

    //Logic for the next ID register.
    var nextIDLogic = new StringBuilder();
    nextIDLogic.append("""
        always_ff @(posedge %s) begin
            if (%s) %s <= '0;
        """.formatted(
            language.clk,
            language.reset, id_next_regName));
    for (int iRetire = results.length - 1; iRetire >= 0; --iRetire) {
      PortBuildResult result = results[iRetire];
      String idExpr = String.format("%s + %d'd%d", id_next_regName, idWidth, iRetire + 1);
      nextIDLogic.append(String.format("    else if (%s && !%s) %s <= %s;\n",
                                         result.validExpr, result.stallExpr,
                                         id_next_regName, idExpr));
    }
    if (!idResettingExpr.isEmpty()) {
      nextIDLogic.append(String.format("    if (%s && !(|%s)) %s <= %s;\n",
          idResettingExpr, mask_regName,
          id_next_regName, idResetToReg));
    }
    nextIDLogic.append("end\n");
    ret.logic += nextIDLogic.toString();

    //Logic for the valid mask register.
    ret.logic += """
        always_ff @(posedge %s) begin
            if (%s) %s <= '0;
            else %s <= (%s | (%s%s)) & %s;
        end
        """.formatted(
            language.clk,
            language.reset, mask_regName,
            mask_regName, mask_regName, masknew_wireName, !masknew_filter_expr.isEmpty() ? (" & " + masknew_filter_expr) : "", maskclear_wireName);
    //Logic for the 'is not a discard' vector register.
    ret.logic += """
        always_ff @(posedge %s) begin
            if (%s) %s <= '0;
            else %s <= (%s | (%s%s)) & %s;
        end
        """.formatted(
            language.clk,
            language.reset, masknodiscard_regName,
            masknodiscard_regName, masknodiscard_regName, masknodiscardnew_wireName, !masknew_filter_expr.isEmpty() ? (" & " + masknew_filter_expr) : "", maskclear_wireName);

    //Collect stall conditions,
    // prevent collisions in the stall output key.
    class StallEntry {
      String cond;
      NodeInstanceDesc.Key stallBaseKey;
      StallEntry(String cond, NodeInstanceDesc.Key stallBaseKey) {
        this.cond = cond;
        this.stallBaseKey = stallBaseKey;
      }
    }
    StallEntry[] stallSourceEntries = new StallEntry[this.assignIDSources.size()];
    for (int iSource = 0; iSource < this.assignIDSources.size(); ++iSource) {
      var assignSource = this.assignIDSources.get(iSource);

      //Stall if the assigned ID collides with a still-retiring instruction.

      String assignIDExpr = registry.lookupRequired(assignSource.key_ID).getExpressionWithParens();
      //Only check special 'valid' conditions.
      // -> The standard RdStall/WrStall and flush checks are not relevant,
      //     since we're only issuing a stall.
      //    (Assumption: assignIDExpr will not evaluate to X once <mask_regName>!=0)
      String assignIDValidExpr = assignSource.key_valid.isPresent()
          ? (registry.lookupRequired(assignSource.key_valid.get()).getExpressionWithParens() + " && ")
          : "";
      //Check the retire mask register for conflicts.
      String stallCond = String.format("%s%s[%s]", assignIDValidExpr, mask_regName, assignIDExpr);

      NodeInstanceDesc.Key stallBaseKey = new NodeInstanceDesc.Key(
          assignSource.node_response_stall.orElse(bNodes.WrStall),
          assignSource.getTriggerStage(),
          "");

      //If the same stall key is applicable for a previous source,
      // add to the existing StallEntry instead of creating a new one.
      StallEntry stallEntry = null;
      for (int iOtherSource = iSource - 1; iOtherSource >= 0; --iOtherSource) {
        if (stallSourceEntries[iOtherSource] != null
            && stallBaseKey.equals(stallSourceEntries[iOtherSource].stallBaseKey)) {
          stallEntry = stallSourceEntries[iOtherSource];
          stallEntry.cond += " || " + stallCond;
        }
      }
      if (stallEntry == null) {
        //No stallBaseKey conflict, create a new StallEntry.
        stallSourceEntries[iSource] = new StallEntry(stallCond, stallBaseKey);
      }
    }
    for (StallEntry stallEntry : stallSourceEntries) {
      if (stallEntry != null) {
        //Also stall if the ID is resetting after a discard.
        if (!idResettingExpr.isEmpty())
          stallEntry.cond += " || " + idResettingExpr;
        //Lookup to ensure the stall logic is built.
        registry.lookupExpressionRequired(stallEntry.stallBaseKey);
        //Store our stall condition in a wire,
        // assign it to the stall key with the builder's aux value.
        String condWire = String.format("%s_idretire_%d", stallEntry.stallBaseKey.toString(false), uniqueID);
        ret.declarations += String.format("logic %s;\n", condWire);
        ret.logic += String.format("assign %s = %s;\n", condWire, stallEntry.cond);
        ret.outputs.add(new NodeInstanceDesc(
            NodeInstanceDesc.Key.keyWithPurposeAux(stallEntry.stallBaseKey, Purpose.REGULAR, aux),
            condWire,
            ExpressionType.WireName
        ));
      }
    }
    return ret;
  }

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    PipelineStage referenceStage = signals.key_retireID.getStage();
    var nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      NodeInstanceDesc.Key nodeKey = nodeKeyIter.next();

      if (!nodeKey.getPurpose().matches(Purpose.REGULAR)
          || nodeKey.getStage() != referenceStage
          || !nodeKey.getISAX().isEmpty())
        continue;
      int iPort = -1;
      if ((nodeKey.getNode().equals(signals.key_retireID.getNode())
           || nodeKey.getNode().equals(signals.key_valid.getNode())
           || nodeKey.getNode().equals(signals.key_isDiscard.getNode()))
          && nodeKey.getAux() >= 0) {
        iPort = nodeKey.getAux();
      }
      else if (nodeKey.getNode().name.startsWith(signals.node_stall.name)
          && nodeKey.getAux() == 0) {
        // Port is stored in the node name suffix here.
        try { iPort = Integer.parseInt(nodeKey.getNode().name.substring(signals.node_stall.name.length())); }
        catch (NumberFormatException e) { continue; }
      }
      else
        continue;
      if (requestedPorts.isEmpty()) {
        RequestedForSet requestedFor = new RequestedForSet();
        out.accept(TriggerableNodeLogicBuilder.fromFunction(
            "IDRetireSerializerStrategy_"+uniqueID,
            builderTriggerKey,
            (registry, aux) -> this.build(registry, requestedFor, aux)
        ));
      }
      if (!requestedPorts.contains(iPort))
        requestedPorts.add(iPort);
      nodeKeyIter.remove();
    }
  }

}
