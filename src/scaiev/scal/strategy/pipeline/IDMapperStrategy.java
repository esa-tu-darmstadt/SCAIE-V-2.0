package scaiev.scal.strategy.pipeline;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.SCALUtil;
import scaiev.scal.TriggerableNodeLogicBuilder;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.util.Verilog;


/**
 * Strategy to map the core's instruction ID space to a smaller inner space,
 *  for tracking lower-frequency operations and/or tracking scoreboard retire/flush.
 */
public class IDMapperStrategy extends MultiNodeStrategy {
  protected static final Logger logger = LogManager.getLogger();

  private static AtomicInteger nextUniqueID = new AtomicInteger(0);

  protected Verilog language;
  protected BNode bNodes;

  int uniqueID;


  /**
   * In case of a flush in a retire ID source, the ID to flush to.
   * Can be empty iff the assign ID is guaranteed not to jump.
   */
  Optional<NodeInstanceDesc.Key> key_RdFlushID;
  /** Indicates if an issued ID is flushing. */
  Optional<NodeInstanceDesc.Key> key_isFlushing;
  int outerIDWidth;
  int outerIDCount;
  int innerIDWidth;

  boolean assignIDSourcesAreOrdered;
  boolean assignIDSourcesArePredictable;

  /**
   * 
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param key_RdFlushID the node key that specifies the next ID in assignIDFront after flushing.
   *        The value must be valid whenever RdFlush or WrFlush is set for any stage in assignIDFront.
   *        May be null for issue stages (i.e., the flush does not change the next ID; discards will be processed from retireIDSources).
   * @param key_isFlushing indicates if the retire is a flush, used iff retireIDSources has an entry with the Root stage as trigger stage.
   * @param innerIDWidth the width of the inner ID managed by this strategy, must be below or equal to the outer ID size but above zero.
   *  If it matches &lt;any ID source&gt;.key_ID.getNode().size, no separate ID-to-ID mapping is constructed.
   *  If it is below the outer size, the maximum ID ((2**innerIDWidth)-1) will get reserved as an 'invalid' marker.
   * @param assignIDSources the IDSources describing the condition (and stage) by which new IDs are to be assigned,
   *          matching the requirements from {@link IDBasedPipelineStrategy}
   * @param assignIDSourcesAreOrdered if the ID values from assignIDSources are guaranteed to be ascending in the list order
   *        [e.g. decodeB handles the instruction immediately following the one handled by decodeA, such that id@decodeB == id@decodeA+1,
   *         never the other way round], set this to true. Stages that stall at a given point are ignored in this ordering.
   *        This avoids generation of (inefficient) combinational ID sorting logic.
   * @param assignIDSourcesArePredictable if it is guaranteed that only a prefix of assignIDSources is valid at a given time,
   *        i.e. if any i-th entry in assignIDSources stalls, so does the i+1' entry.
   *        May improve logic overhead in some cases.
   * @param retireIDSources the IDSources describing the condition (and stage) by which IDs
   *        are to be considered retired / no longer required, matching the requirements from {@link IDBasedPipelineStrategy}
   */
  public IDMapperStrategy(Verilog language, BNode bNodes,
      Optional<NodeInstanceDesc.Key> key_RdFlushID, Optional<NodeInstanceDesc.Key> key_isFlushing,
      int innerIDWidth,
      List<IDSource> assignIDSources,
      boolean assignIDSourcesAreOrdered, boolean assignIDSourcesArePredictable,
      List<IDSource> retireIDSources) {
    this.language = language;
    this.bNodes = bNodes;

    if (assignIDSources.isEmpty() || retireIDSources.isEmpty()) {
      throw new IllegalArgumentException("assignIDSources, retireIDSources must not be empty");
    }

    //Determine the width of IDs, and number of possible IDs.
    outerIDWidth = assignIDSources.get(0).key_ID.getNode().size;
    outerIDCount = assignIDSources.get(0).key_ID.getNode().elements;
    if (outerIDWidth <= 0) {
      throw new IllegalArgumentException("assignIDSources must have ID nodes with sizes above zero");
    }
    if (Stream.concat(assignIDSources.stream().map(source->source.key_ID),
                      retireIDSources.stream().map(source->source.key_ID))
        .anyMatch(key -> key.getNode().size != outerIDWidth || key.getNode().elements != outerIDCount)) {
      throw new IllegalArgumentException("assignIDSources, retireIDSources must have consistent ID sizes, elements");
    }
    if (outerIDCount <= 0) {
      //If elements is not set (== 0), assume it is two to the power of the size/width.
      outerIDCount = 1 << outerIDWidth;
    }

    PipelineFront assignInpipeSources = new PipelineFront(assignIDSources.stream()
                                                          .map(source -> source.getTriggerStage())
                                                          .filter(stage ->
                                                                  stage.getKind() == StageKind.Core
                                                                  || stage.getKind() == StageKind.CoreInternal
                                                                  || stage.getKind() == StageKind.Sub));
    PipelineFront retireInpipeSources = new PipelineFront(retireIDSources.stream()
                                                          .map(source -> source.getTriggerStage())
                                                          .filter(stage ->
                                                                  stage.getKind() == StageKind.Core
                                                                  || stage.getKind() == StageKind.CoreInternal
                                                                  || stage.getKind() == StageKind.Sub));
    if (!retireInpipeSources.asList().isEmpty()
        && assignInpipeSources.asList().stream().anyMatch(assignStage -> !retireInpipeSources.isAroundOrAfter(assignStage, false))) {
      throw new IllegalArgumentException("retireIDSources must lie after all stages in assignIDSources (if in core pipeline)");
    }
    if (!assignInpipeSources.asList().isEmpty()
        && retireInpipeSources.asList().stream().anyMatch(retireStage ->  !assignInpipeSources.isBefore(retireStage, false))) {
      throw new IllegalArgumentException("assignIDSources must lie before all stages in retireIDSources (if in core pipeline)");
    }

    this.uniqueID = nextUniqueID.getAndIncrement();
    this.key_RdFlushID = key_RdFlushID;
    this.key_isFlushing = key_isFlushing;
    this.innerIDWidth = innerIDWidth;
    if (innerIDWidth <= 0) {
      throw new IllegalArgumentException("innerWidth must be above zero");
    }
    if (innerIDWidth > outerIDWidth) {
      throw new IllegalArgumentException("innerWidth must not exceed node_RdID.size");
    }
    this.assignIDSources = assignIDSources;
    this.assignIDSourcesAreOrdered = assignIDSourcesAreOrdered || (assignIDSources.size() == 1);
    this.assignIDSourcesArePredictable = assignIDSourcesArePredictable || (retireIDSources.size() == 1);
    this.retireIDSources = retireIDSources;
    this.translationIDSources = new ArrayList<>();

    if (assignIDSources.size() > (1 << innerIDWidth)) {
      throw new IllegalArgumentException("innerIDWidth must be large enough to fit all assignIDSources");
    }

    this.assignIDSourcesFront = new PipelineFront(assignIDSources.stream().map(source -> source.getTriggerStage()).distinct());
    this.retireIDSourcesFront = new PipelineFront(retireIDSources.stream().map(source -> source.getTriggerStage()).distinct());

    this.node_scratch_RdInnerID = new SCAIEVNode("RdID_inner_scratch_" + this.uniqueID);
    this.key_RdLastMaxID = new NodeInstanceDesc.Key(
        PipeIDBuilder.purpose_PipeIDBuilderInternal,
        new SCAIEVNode("RdLastMaxID_" + uniqueID, outerIDWidth, false),
        assignIDSources.get(0).getTriggerStage(), "");
  }

  /** Reference to an ID assigner/issuer or retirer, usually corresponding to a pipeline stage. */
  public static class IDSource {
    private static AtomicInteger nextUniqueSourceID = new AtomicInteger(0);
    /**
     * Constructs the IDSource and creates a unique inner ID output node ({@link IDSource#node_RdInnerID}).
     * @param key_ID The outer ID (input), {@link IDSource#key_ID}
     * @param key_valid Optional base valid condition (input),
     *                  defaults to stall/flush standard condition,
     *                  {@link IDSource#key_valid}
     * @param key_relevant Whether an inner ID should be allocated (input),
     *                     null for retire and translation-only sources,
     *                     {@link IDSource#key_relevant}
     * @param node_response_stall Optional stall output node (output),
     *                            defaults to using WrStall,
     *                            {@link IDSource#node_response_stall}
     */
    public IDSource(NodeInstanceDesc.Key key_ID,
                          Optional<NodeInstanceDesc.Key> key_valid,
                          NodeInstanceDesc.Key key_relevant,
                          Optional<SCAIEVNode> node_response_stall) {
      this.key_ID = key_ID;
      this.key_valid = key_valid;
      this.key_relevant = key_relevant;
      this.node_response_stall = node_response_stall;
      int uniqueID = nextUniqueSourceID.getAndIncrement();
      this.node_RdInnerID = new SCAIEVNode(key_ID.getNode().name + "_inner_" + uniqueID);
      this.node_RdInnerIDValid = new SCAIEVNode(key_ID.getNode().name + "_inner_valid_" + uniqueID);
    }
    /**
     * Constructs an IDSource from another, changing only key_relevant.
     * @param other
     * @param key_relevant_override the key_relevant override.
     *                              In most cases, the override should be stricter than (i.e., imply) the original.
     */
    public IDSource(IDSource other, NodeInstanceDesc.Key key_relevant_override) {
      this.key_ID = other.key_ID;
      this.key_valid = other.key_valid;
      this.key_relevant = key_relevant_override;
      this.node_response_stall = other.node_response_stall;
      this.node_RdInnerID = other.node_RdInnerID;
      this.node_RdInnerIDValid = other.node_RdInnerIDValid;
    }

    /** Returns the stage the ID comes from, defined by key_valid (if present) or otherwise key_ID. */
    public PipelineStage getTriggerStage() {
      return key_valid.map(key->key.getStage()).orElse(key_ID.getStage());
    }

    /** The ID to assign/retire (input) */
    public NodeInstanceDesc.Key key_ID;
    /** Whether the assign/retire is valid (input).
     *  If absent, will default to stage stall/flush conditions.
     *  Must be present if the Rd/Wr Stall/Flush nodes don't exist in the stage
     *   (e.g., root stages)
     */
    public Optional<NodeInstanceDesc.Key> key_valid;
    /** Whether an inner ID should be allocated (input), null for retire */
    public NodeInstanceDesc.Key key_relevant;

    /**
     * The node by which to apply stalling (output). If absent, will use WrStall.
     * Any user will do a lookupRequired on the node in stage {@link IDSource#getTriggerStage()}
     *  and output the stall condition with an aux value (Purpose REGULAR).
     */
    public Optional<SCAIEVNode> node_response_stall;

    /**
     * The node where the inner ID will be written to (output).
     * The key will have purpose REGULAR, stage key_ID.getStage(), ISAX "", aux 0.
     */
    public SCAIEVNode node_RdInnerID;

    /**
     * The node where the inner ID 'valid' flag will be written to (output).
     * For assign, can be assumed true when valid&amp;&amp;relevant&amp;&amp;!stall (need not be checked otherwise).
     * 
     * Note: Excludes WrStall and node_response_stall to prevent combinational loops.
     */
    public SCAIEVNode node_RdInnerIDValid;

    /**
     * Retrieves the key where the inner ID will be written to (output).
     * Readers can leave size, elements to 0.
     * The queried NodeInstanceDesc's key.getNode() will contains the correct size and elements values.
     */
    public NodeInstanceDesc.Key makeKey_RdInnerID(int size, int elements) {
      SCAIEVNode node = new SCAIEVNode(node_RdInnerID.name, size, false);
      node.elements = elements;
      return new NodeInstanceDesc.Key(Purpose.REGULAR, node, key_ID.getStage(), "", 0);
    }
    /**
     * Retrieves the key where the inner ID 'valid' flag will be written to (output).
     */
    public NodeInstanceDesc.Key makeKey_RdInnerIDValid() {
      return new NodeInstanceDesc.Key(Purpose.REGULAR, node_RdInnerIDValid, key_ID.getStage(), "", 0);
    }
  }

  /** The sources that assign/issue IDs. */
  List<IDSource> assignIDSources;
  /** The sources that retire IDs. */
  List<IDSource> retireIDSources;
  /** The sources that neither assign nor retire IDs, but translation is requested for. */
  List<IDSource> translationIDSources;

  /**
   * Adds an IDSource for translation to an inner ID.
   * See {@link IDSource#node_RdInnerID}.
   * Reuses existing IDSources with the same key_ID, regardless of key_valid.
   */
  public IDSource addTranslatedIDSource(NodeInstanceDesc.Key key_ID, Optional<NodeInstanceDesc.Key> key_valid) {
    var opt_existing = Stream.concat(assignIDSources.stream(), translationIDSources.stream())
                           .filter(source -> source.key_ID.matches(key_ID))
                           .findAny();
    if (opt_existing.isPresent())
      return opt_existing.get();
    //Check that the requested translation is not after retirement
    if (!retireIDSourcesFront.asList().isEmpty()
        && (!retireIDSourcesFront.isAroundOrAfter(key_ID.getStage(), false)) ) {
      throw new IllegalArgumentException("retireIDSources must not lie before the stage of key_ID");
    }
    //Check that the requested translation is not in or before the assign stage
    if (!assignIDSourcesFront.asList().isEmpty()
        && (!assignIDSourcesFront.isAroundOrBefore(key_ID.getStage(), false)
            || assignIDSourcesFront.contains(key_ID.getStage()))
        && key_ID.getStage().getKind() != StageKind.Root) {
      throw new IllegalArgumentException("assignIDSources must lie before the stage of key_ID");
    }
    IDSource ret = new IDSource(key_ID, key_valid, null, Optional.empty());
    translationIDSources.add(ret);
    return ret;
  }

  /** Pipeline front with the assign stages ({@link IDSource#getTriggerStage()}) */
  PipelineFront assignIDSourcesFront;
  /** Pipeline front with the retire stages ({@link IDSource#getTriggerStage()}) */
  PipelineFront retireIDSourcesFront;

  SCAIEVNode node_scratch_RdInnerID;
  NodeInstanceDesc.Key key_RdLastMaxID;

  public boolean hasDedicatedInvalidID() {
    //If the inner ID space is different from the outer one,
    // reserve one ID as an "illegal mapping" marker.
    return (1 << innerIDWidth) != outerIDCount;
  }
  /** Returns the number of valid inner IDs, for dimensioning of ID-based buffers */
  public int getInnerIDCount() {
    if ((1 << innerIDWidth) >= outerIDCount)
      return outerIDCount;
    assert(hasDedicatedInvalidID());
    return (1 << innerIDWidth) - 1;
  }
  public String getInvalidIDExpr() { return String.format("%d'd%d", innerIDWidth, (1 << innerIDWidth) - 1); }
  /** Checks if the 'invalid ID' is indicated by a dedicated bit [true] or only one dedicated ID [false] (currently always false) */
  public boolean invalidIsSeparateBit() { return hasDedicatedInvalidID() && (1 << innerIDWidth) >= 2*getInnerIDCount(); }

  /** Forces the assign ID sub-condition to '1'. Not applicable if node_RdInnerID == node_RdID. */
  private boolean forceAlwaysAssignID = false;

  /** If true, forces the assign ID sub-condition to '1'. Not applicable if !hasDedicatedInvalidID(). */
  public void setForceAlwaysAssignID(boolean forceAlwaysAssignID) {
    this.forceAlwaysAssignID = forceAlwaysAssignID;
  }

  private String getWrStallCondition(NodeRegistryRO registry, IDSource source) {
    return Stream.concat(source.node_response_stall.stream(),
                         Stream.ofNullable(source.getTriggerStage().getKind() == StageKind.Root ? null : bNodes.WrStall))
                 .map(node -> registry.lookupRequired(new NodeInstanceDesc.Key(node, source.getTriggerStage(), "")).getExpressionWithParens())
                 .reduce((a,b) -> a + " || " + b)
                 .orElse("1'b0");
  }
  private String buildPipeCondition(NodeRegistryRO registry, RequestedForSet requestedFor, IDSource source,
                                    boolean checkRelevantCond, boolean checkFlush, boolean checkWrStall) {
    PipelineStage stage = source.getTriggerStage();
    String pipeCond;
    if (source.key_valid.isPresent()) {
      pipeCond = registry.lookupRequired(source.key_valid.get(), requestedFor).getExpressionWithParens();
      if (checkWrStall)
        pipeCond += " && !(" + getWrStallCondition(registry, source) + ")";
    }
    else if (stage.getKind() == StageKind.Root) {
      pipeCond = "1'b0";
      assert(false); //source.key_valid is supposed to be present in this case
    }
    else {
      String wrStallCond = "";
      if (checkWrStall)
        wrStallCond = " && !(" + getWrStallCondition(registry, source) + ")";
      pipeCond = "!" + registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdStall, stage, ""), requestedFor)
                 + wrStallCond
                 + (checkFlush
                     ? (" && " + SCALUtil.buildCond_StageNotFlushing(bNodes, registry, stage, requestedFor))
                     : "");
    }
    if (!forceAlwaysAssignID && hasDedicatedInvalidID() && checkRelevantCond) {
      // Only write if the ID assign condition applies
      assert(source.key_relevant != null); //Is null for retire, but checkRelevantCond should be false in that case.
      pipeCond += " && " + registry.lookupRequired(source.key_relevant, requestedFor).getExpressionWithParens();
    }
    return pipeCond;
  }

  /**
   * Retrieves the 'flush to' inner ID. Valid iff any of the assignStages is flushing (or key_isFlushing, if a Root stage is used as assign stage).
   * The node will only be initialized if the inner ID is in use.
   */
  public NodeInstanceDesc.Key getRdInnerNodeFlushToIDKey() {
    return new NodeInstanceDesc.Key(Purpose.REGULAR, new SCAIEVNode("IDBasedPipelineReg_" + uniqueID + "_RdInnerNodeFlushToID"),
                                    assignIDSources.get(0).getTriggerStage(), "");
  }

  private class PipeIDBuilder extends TriggerableNodeLogicBuilder {
    List<PipelineStage> stagesToImplementRead = new ArrayList<>();
    RequestedForSet commonRequestedFor = new RequestedForSet();

    public PipeIDBuilder(String name, NodeInstanceDesc.Key nodeKey) { super(name, nodeKey); }

    protected static final Purpose purpose_PipeIDBuilderInternal = new Purpose("PipeIDBuilderInternal", true, Optional.empty(), List.of());

    /**
     * Builds the 'next ID' register update logic.
     * Increments the 'next ID' with the expression from `incrementByExpr`.
     * If `flushCond` applies, it instead overwrites the 'next ID' by `flushToInnerIDExpr`.
     * 
     * @param logicBlock the block to add outputs, declarations, logic to
     * @param phaseStages the stages where the inner ID will be used in (only used to avoid conflicts with internal node keys)
     * @param namePhase the name of the phase to use in declarations
     * @param incrementByExpr the expression to add to the current 'next ID'
     * @param flushCond the flush condition expression
     * @param flushToInnerIDExpr the 'next ID' expression to apply in case of a flush
     */
    private String[] buildNextInnerIDRegisters(NodeLogicBlock logicBlock, List<PipelineStage> phaseStages, String namePhase,
                                               String incrementByExpr, String flushCond, String flushToInnerIDExpr) {

      String updateNextIDWire = String.format("innerID_%d_%sNext_update", uniqueID, namePhase);
      logicBlock.declarations += String.format("wire [%d-1:0] %s;\n", innerIDWidth, updateNextIDWire);
      logicBlock.outputs.add(new NodeInstanceDesc(
          new NodeInstanceDesc.Key(purpose_PipeIDBuilderInternal, node_scratch_RdInnerID, phaseStages.get(0), "next" + namePhase + "Update", 0),
          updateNextIDWire, ExpressionType.WireName));

      //Names of all 'next ID (plus N)' registers.
      // The additional 'plus N' registers are there to avoid having an adder on the assign path.
      int maxIncrement = Math.min(phaseStages.size(), getInnerIDCount());
      String[] phaseInnerNextIDPlusIReg = new String[maxIncrement+1];

      String phaseInnerNextIDPlusIRegs = String.format("innerID_%d_%sNext_plusN_regs", uniqueID, namePhase);
      logicBlock.declarations += String.format("reg [%d-1:0] %s [%d];\n", innerIDWidth, phaseInnerNextIDPlusIRegs, phaseInnerNextIDPlusIReg.length);

      String resetLogic = "", updateRegLogic = "";
      for (int iIncr = 0; iIncr < phaseInnerNextIDPlusIReg.length; ++iIncr) {
        phaseInnerNextIDPlusIReg[iIncr] = String.format("%s[%d]", phaseInnerNextIDPlusIRegs, iIncr);
        logicBlock.outputs.add(new NodeInstanceDesc(
            new NodeInstanceDesc.Key(purpose_PipeIDBuilderInternal, node_scratch_RdInnerID, phaseStages.get(0), "next" + namePhase, iIncr),
            phaseInnerNextIDPlusIReg[iIncr], ExpressionType.WireName));

        // Reset to 0 + iStage
        resetLogic += language.tab + String.format("%s <= %d'd%d;\n", phaseInnerNextIDPlusIReg[iIncr], innerIDWidth, iIncr);
        String updateExpr;
        if (invalidIsSeparateBit()) {
          // Increment by iStage, ignoring the INVALID bit
          updateExpr = String.format("{1'b0, %s[%d-1:0] + %d'd%d}", updateNextIDWire, innerIDWidth-1, innerIDWidth-1, iIncr);
        }
        else {
          // Increment by iStage, skipping INVALID (all-1)
          updateExpr = String.format("%s + %d'd%d", updateNextIDWire, innerIDWidth, iIncr);
          updateExpr = String.format("(%s + %d'd1 <= %s) ? (%s + %d'd1) : (%s)", updateExpr, innerIDWidth, updateNextIDWire, updateExpr,
                                     innerIDWidth, updateExpr);
        }
        updateRegLogic += language.tab + String.format("%s <= %s; // test wrap-around, skip invalid (%s)\n",
                                                       phaseInnerNextIDPlusIReg[iIncr], updateExpr, getInvalidIDExpr());
      }

      String assignExpr = phaseInnerNextIDPlusIReg[0] + " + " + incrementByExpr;
      if (!flushCond.isEmpty())
        assignExpr = String.format("(%s) ? (%s) : (%s)", flushCond, flushToInnerIDExpr, assignExpr);

      logicBlock.logic += String.format("assign %s = %s;\n", updateNextIDWire, assignExpr);
      logicBlock.logic += language.CreateInAlways(
          true, String.format("if (%s) begin\n%send\nelse begin\n%send\n", language.reset, resetLogic, updateRegLogic));

      return phaseInnerNextIDPlusIReg;
    }

    /** Reduces the conditions from condExpr to an addition with the inner ID width. */
    private String makeInnerIDCountExpr(Stream<String> condExpr) {
      return condExpr.map(wireName -> String.format("%d'(%s)", innerIDWidth, wireName))
          .reduce((a, b) -> a + " + " + b)
          .orElse(String.format("%d'd0", innerIDWidth));
    }

    /** Declares and assigns a wire to the sum of pipeCondWires ( inner ID width. */
    private void makeCountDeclLogic(NodeLogicBlock logicBlock, List<PipelineStage> phaseStages, String[] pipeCondWires, String namePhase,
                                    String countWireName) {

      logicBlock.declarations += String.format("wire [%d-1:0] %s;\n", innerIDWidth, countWireName);
      logicBlock.outputs.add(new NodeInstanceDesc(
          new NodeInstanceDesc.Key(purpose_PipeIDBuilderInternal, node_scratch_RdInnerID, phaseStages.get(0), namePhase + "_count"), countWireName,
          ExpressionType.WireName));
      logicBlock.logic += String.format("assign %s = %s;\n", countWireName, makeInnerIDCountExpr(Stream.of(pipeCondWires)));
    }
    protected String buildAnyFlushCond(NodeRegistryRO registry, List<IDSource> idSources, RequestedForSet requestedFor) {
      Stream<String> subconds = idSources.stream()
          .filter(idSource -> idSource.getTriggerStage().getKind() != StageKind.Root)
          .map(idSource -> {
            PipelineStage stage = idSource.getTriggerStage();
            return registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, stage, ""), requestedFor) +
               registry.lookupOptionalUnique(new NodeInstanceDesc.Key(bNodes.WrFlush, stage, ""))
                   .map(wrflushCond -> " && !" + wrflushCond.getExpression())
                   .orElse("");
          });
      if (idSources.stream().anyMatch(idSource -> idSource.getTriggerStage().getKind() == StageKind.Root)) {
        assert(key_isFlushing.isPresent());

        subconds = Stream.concat(subconds, Stream.of(registry.lookupExpressionRequired(key_isFlushing.get())));
      }
      return subconds.reduce((a, b) -> (a.isEmpty()?b:(b.isEmpty()?a:("(" + a + ") || (" + b + ")"))))
          .orElse("1'b0");
    }

    @Override
    protected NodeLogicBlock applyTriggered(NodeRegistryRO registry, int aux) {
      if (stagesToImplementRead.isEmpty())
        return new NodeLogicBlock(); // Nothing to do
      var logicBlock = new NodeLogicBlock();

      List<PipelineStage> assignIDStages = assignIDSources.stream().map(source -> source.getTriggerStage()).toList();
      List<PipelineStage> retireIDStages = retireIDSources.stream().map(source -> source.getTriggerStage()).toList();

      String mappingArray = String.format("innerID_%d_mappingByRdID", uniqueID);

      // Declarations and logic for the assign and retire stage internal IDs
      String assignCountWire = String.format("innerID_%d_assign_count", uniqueID);
      String retireCountWire = String.format("innerID_%d_retire_count", uniqueID);

      logicBlock.logic += String.format("// Tracking for 'next inner ID' in retire (%s)\n",
                                        retireIDStages.stream().map(stage -> stage.getName()).reduce((a, b) -> a + "," + b).orElse(""));
      String[] retireInnerNextIDPlusIRegs = buildNextInnerIDRegisters(logicBlock, retireIDStages, "retire", retireCountWire, "", "");

      // When flushing, move the assign pointer back to where RdFlushID points to;
      //  if that doesn't point anywhere, move it to the retired counter position.
      logicBlock.logic += String.format("// Tracking for 'next inner ID' in assign (%s)\n",
                                        assignIDStages.stream().map(stage -> stage.getName()).reduce((a, b) -> a + "," + b).orElse(""));
      String anyAssignmentFlushCond = buildAnyFlushCond(registry, assignIDSources, commonRequestedFor);
      String anyRetireFlushCond = buildAnyFlushCond(registry, retireIDSources, commonRequestedFor);
      NodeInstanceDesc.Key flushToInnerIDKey = getRdInnerNodeFlushToIDKey();
      String flushToInnerIDWire = flushToInnerIDKey.toString(false);
      String[] assignInnerNextIDPlusIRegs =
          buildNextInnerIDRegisters(logicBlock, assignIDStages, "assign", assignCountWire, anyAssignmentFlushCond, flushToInnerIDWire);
      logicBlock.outputs.add(new NodeInstanceDesc(flushToInnerIDKey, flushToInnerIDWire, ExpressionType.WireName));
      String flushToInnerID;
      if (key_RdFlushID.isPresent()) {
        String rdFlushIDExpr = registry.lookupExpressionRequired(key_RdFlushID.get(), commonRequestedFor);
        flushToInnerID = String.format("%s[%s]", mappingArray, rdFlushIDExpr);
        flushToInnerID = String.format("(%s == %s || %s) ? %s : %s", flushToInnerID, getInvalidIDExpr(), anyRetireFlushCond,
                                       retireInnerNextIDPlusIRegs[0], flushToInnerID);
      }
      else {
        //Keep the inner ID (only the current instruction in the assign stage is being flushed)
        flushToInnerID = assignInnerNextIDPlusIRegs[0];
      }
      logicBlock.declarations += String.format("wire [%d-1:0] %s;\n", innerIDWidth, flushToInnerIDWire);
      logicBlock.logic += String.format("assign %s = %s;\n", flushToInnerIDWire, flushToInnerID);

      String[] assignAnyPipeConditionExprs = new String[assignIDSources.size()];
      String[] assignPipeConditionWires = new String[assignIDSources.size()];
      String[] assignPipeConditionWires_nostall = new String[assignIDSources.size()];

      String[] assignStageRdIDExpr = new String[assignIDSources.size()];
      String[] retireStageRdIDExpr = new String[retireIDSources.size()];

      // Assign assignPipeConditionWires and assignStageRdIDExpr, apply WrStall for overflow prevention.
      for (int iAssignSource = 0; iAssignSource < assignIDSources.size(); ++iAssignSource) {
        IDSource assignIDSource = assignIDSources.get(iAssignSource);
        PipelineStage assignIDSourceStage = assignIDSource.getTriggerStage();
        
        String wrstallCond = getWrStallCondition(registry, assignIDSource);

        String pipeCondAny = buildPipeCondition(registry, commonRequestedFor, assignIDSource, false, true, false);
        String pipeCondInner = forceAlwaysAssignID
                                   ? pipeCondAny
                                   : buildPipeCondition(registry, commonRequestedFor, assignIDSource, true, true, false);

        // Give the source a name, based on its stage name or index.
        String sourceName;
        if (assignIDSourceStage.getKind() == StageKind.Core || assignIDSourceStage.getKind() == StageKind.CoreInternal)
          sourceName = assignIDSourceStage.getName();
        else
          sourceName = Integer.toString(iAssignSource);
        assignAnyPipeConditionExprs[iAssignSource] = pipeCondAny + String.format(" && !(%s)", wrstallCond);
        assignPipeConditionWires[iAssignSource] = String.format("innerID_%d_assignpipecond_%s", uniqueID, sourceName);
        assignPipeConditionWires_nostall[iAssignSource] = String.format("innerID_%d_assignpipecond_prewrstall_%s", uniqueID, sourceName);
        logicBlock.declarations += String.format("wire %s;\n", assignPipeConditionWires[iAssignSource]);
        logicBlock.declarations += String.format("wire %s;\n", assignPipeConditionWires_nostall[iAssignSource]);
        logicBlock.logic += String.format("// Condition: is stage %s assigning a new inner ID?\n", sourceName);
        logicBlock.logic += String.format("assign %s = %s;\n", assignPipeConditionWires_nostall[iAssignSource], pipeCondInner);
        logicBlock.logic += String.format("assign %s = %s && !(%s);\n", assignPipeConditionWires[iAssignSource], assignPipeConditionWires_nostall[iAssignSource], wrstallCond);

        // Check for buffer overflows when adding iAssignSource new elements.
        // If we have one free ID but two or more assign sources, this will simply stall the second assign source stage onwards.
        // We need to check if the first..<iAssignStage>th increment of 'assign ID' would catch up with 'retire ID',
        //  indicating a potential overflow.
        // Note1: This condition is kept simple and may stall more often than necessary.
        //  The exact condition would also check for stalls and the ordering between the assign stages.
        // Note2: This condition will end up in the main WrStall, and thus will be included in buildPipeCondition at some point.
        String overflowCond = Stream.of(assignInnerNextIDPlusIRegs)
                                  .limit(iAssignSource + 2)
                                  .skip(1)
                                  .map(assignPlusIExpr -> String.format("%s == %s", assignPlusIExpr, retireInnerNextIDPlusIRegs[0]))
                                  .reduce((a, b) -> a + " || " + b)
                                  .get();
        if (getInnerIDCount() == 1) {
          logger.error("IDMapperStrategy: ERROR stall condition does not support '1 ID, no invalid ID' special case, will deadlock");
        }
        String overflowCondWire = String.format("stallFrom_innerID_%d_assignPossiblyFull_%s", uniqueID, sourceName);
        logicBlock.declarations += String.format("wire %s;\n", overflowCondWire);
        logicBlock.logic += String.format("// Condition: Inner ID overflow about to occur in assign stage %s\n", sourceName);
        logicBlock.logic += String.format("assign %s = %s;\n", overflowCondWire, overflowCond);

        //.. default: WrStall
        SCAIEVNode stallNode = assignIDSource.node_response_stall.orElse(bNodes.WrStall);

        logicBlock.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, stallNode, assignIDSourceStage, "", aux),
                                                    overflowCondWire, ExpressionType.WireName, commonRequestedFor));
        //Already 'looked up' above
        //registry.lookupExpressionRequired(new NodeInstanceDesc.Key(stallNode, assignIDSourceStage, ""));

        assignStageRdIDExpr[iAssignSource] =
            registry.lookupExpressionRequired(assignIDSource.key_ID, commonRequestedFor);
      }

      for (int iRetireSource = 0; iRetireSource < retireIDSources.size(); ++iRetireSource) {
        IDSource retireIDSource = retireIDSources.get(iRetireSource);

        retireStageRdIDExpr[iRetireSource] =
            registry.lookupExpressionRequired(retireIDSource.key_ID, commonRequestedFor);
      }

      // Declare and make assign logic for the assign_count wire.
      logicBlock.logic += String.format("// Number of new ID allocations in assign\n");
      makeCountDeclLogic(logicBlock, assignIDStages, assignPipeConditionWires, "assign", assignCountWire);

      Function<Integer, String> getStageAssignIDExpr;
      {
        Optional<String> registeredLastMaxRdIDExpr =
            assignIDSourcesAreOrdered ? Optional.empty() : Optional.of(registry.lookupExpressionRequired(key_RdLastMaxID, commonRequestedFor));

        String rdIDDistArray = String.format("innerID_%d_RdIDDistanceByStage", uniqueID);
        if (!assignIDSourcesAreOrdered) {
          // Add logic to compute the RdID-based instruction ordering (0=first, assignIDStages.size()=last)
          // From checks in the constructor, we know that innerIDWidth is enough to store this range
          //  (though it may still be larger than required).
          logicBlock.logic += String.format("logic [%d-1:0] %s [%d];\n", innerIDWidth, rdIDDistArray, assignIDStages.size());
          for (int iAssignStage = 0; iAssignStage < assignIDStages.size(); ++iAssignStage) {
            logicBlock.logic += String.format("assign %s[%d] = %s;\n", rdIDDistArray, iAssignStage,
                                              String.format("%d'(%s - %s) - %d'd1", innerIDWidth, assignStageRdIDExpr[iAssignStage],
                                                            registeredLastMaxRdIDExpr.get(), innerIDWidth));
          }
        }

        String skipAmountArray = String.format("innerID_%d_assignSkipAmountByRdIDDistance", uniqueID);
        int maxRdIDDistance = assignIDStages.size() - 1;
        if (!assignIDSourcesAreOrdered && !forceAlwaysAssignID) {
          // Since we may have holes in the intended RdID->RdInnerID mapping,
          //  aggregate the hole offsets so we know which RdInnerID to assign.
          logicBlock.logic += String.format("logic [%d-1:0] %s [%d];\n", innerIDWidth, skipAmountArray, maxRdIDDistance + 1);
          String assignLogic = "";
          assignLogic += String.format("%s[0] = %d'd0;\n", skipAmountArray, innerIDWidth);
          for (int i = 1; i <= maxRdIDDistance; ++i) {
            // If the stage with (RdID distance == i-1) is valid in the core pipeline world but invalid in our view,
            //  the negative offset / subtrahend for any stage with (RdID distance >= i) increases by one.
            int i_ = i; // Java :)
            String curIsInvalidCond =
                IntStream.range(0, assignIDStages.size())
                    .mapToObj(iAssignStage
                              -> String.format("(%s[%d] == %d'd%d && (%s) && !(%s))", rdIDDistArray, iAssignStage, innerIDWidth,
                                               i_ - 1,                                    // RdID offset for iAssignStage == i-1
                                               assignAnyPipeConditionExprs[iAssignStage], // Valid condition for the core pipeline
                                               assignPipeConditionWires[iAssignStage]     // Valid condition from our view
                                               ))
                    .reduce((a, b) -> a + " || " + b)
                    .get();
            // Fill in the negative offset table.
            assignLogic += String.format("%s[%d] = %s[%d] + %d'(%s);\n", skipAmountArray, i, // Set distance i..
                                         skipAmountArray, i - 1,                             //..based on distance i-1..
                                         innerIDWidth, curIsInvalidCond                      //..and add 1 if 'curIsInvalidCond'
            );
          }
          logicBlock.logic += language.CreateInAlways(false, assignLogic);
        }
        // Creates the expression for RdInnerID in the given assign stage index.
        getStageAssignIDExpr = iAssignStage -> {
          String offsetExpr;
          if (assignIDSourcesAreOrdered) {
            // RdID (and RdInnerID) are monotonic with iAssignStage [in 0- or 1-increments].
            if (assignIDSourcesArePredictable && forceAlwaysAssignID) {
              // There are no 'stall holes' in assignIDStages.
              //-> we statically know the offset
              //    and can just use the existing offset register.
              return assignInnerNextIDPlusIRegs[iAssignStage];
            }
            String previousStageCondSum = makeInnerIDCountExpr(Stream.of(assignPipeConditionWires).limit(iAssignStage));
            offsetExpr = previousStageCondSum;
          } else {
            offsetExpr = String.format("%s[%d]", rdIDDistArray, iAssignStage);
            if (!forceAlwaysAssignID) {
              // Very slow: RdID is unordered, thus the sparse RdInnerID cannot directly be reconstructed from RdID.
              // Use the combinational table added above.
              offsetExpr = String.format("(%s - %s[%s])", offsetExpr, skipAmountArray, offsetExpr);
            }
          }
          // The final addition could be substituted by MUXing from assignInnerNextIDPlusIRegs,
          //  but with how small the IDs usually are (<< 8 bits), no sensible improvement should be expected from that.
          return String.format("(%s + %s)", assignInnerNextIDPlusIRegs[0], offsetExpr);
        };
      }

      String[] retirePipeConditionWires = new String[retireIDStages.size()];
      logicBlock.logic += "// Condition: Retire inner ID\n";
      // Assign retirePipeConditionWires.
      for (int iRetireSource = 0; iRetireSource < retireIDSources.size(); ++iRetireSource) {
        IDSource retireIDSource = retireIDSources.get(iRetireSource);
        PipelineStage retireIDSourceStage = retireIDSource.getTriggerStage();

        String rdIDExpr = registry.lookupExpressionRequired(retireIDSource.key_ID, commonRequestedFor);
        String pipeCondAny = buildPipeCondition(registry, commonRequestedFor, retireIDSource, false, true, true);
        String pipeCondInner = forceAlwaysAssignID
                                   ? pipeCondAny
                                   : String.format("%s && %s[%s] != %s", pipeCondAny, mappingArray, rdIDExpr, getInvalidIDExpr() // otherwise assign the 'invalid ID' marker
                                     );

        // Give the source a name, based on its stage name or index.
        String sourceName;
        if (retireIDSourceStage.getKind() == StageKind.Core || retireIDSourceStage.getKind() == StageKind.CoreInternal)
          sourceName = retireIDSourceStage.getName();
        else
          sourceName = Integer.toString(iRetireSource);

        retirePipeConditionWires[iRetireSource] = String.format("innerID_%d_retirepipecond_%s", uniqueID, sourceName);
        logicBlock.declarations += String.format("wire %s;\n", retirePipeConditionWires[iRetireSource]);
        logicBlock.logic += String.format("assign %s = %s;\n", retirePipeConditionWires[iRetireSource], pipeCondInner);
      }

      logicBlock.logic += String.format("// Number of newly retired ID allocations\n");
      // Declare and make assign logic for the retire_count wire.
      makeCountDeclLogic(logicBlock, retireIDStages, retirePipeConditionWires, "retire", retireCountWire);

      logicBlock.logic +=
          "`ifndef SYNTHESIS\n"
          + "always_comb begin\n" + language.tab.repeat(1) +
          String.format("if (%s > (%s - %s)) begin\n", retireCountWire, retireInnerNextIDPlusIRegs[0], assignInnerNextIDPlusIRegs[0]) +
          language.tab.repeat(2) + String.format("$display(\"ERROR: Underflow in IDBasedPipelineStrategy(%d)\");\n", uniqueID) +
          language.tab.repeat(2) + String.format("$stop;\n", uniqueID) + language.tab.repeat(1) + "end\n"
          + "end\n`endif\n";

      logicBlock.declarations += String.format("reg [%d-1:0] %s [%d];\n", innerIDWidth, mappingArray, outerIDCount);
      String mappingUpdateLogic = "";
      // Mapping table invalidation logic for retire.
      for (int iRetireSource = 0; iRetireSource < retireIDSources.size(); ++iRetireSource) {
        mappingUpdateLogic +=
            language.tab + String.format("if (%s)\n%s%s[%s] <= %s;\n",
                                         retirePipeConditionWires[iRetireSource], // Only update if this retire does run through.
                                         language.tab.repeat(2), mappingArray,
                                         retireStageRdIDExpr[iRetireSource], // Update the mapping array for the retire source's RdID.
                                         getInvalidIDExpr()                 // Update the retired entry to the 'invalid ID' marker.
                           );
      }
      logicBlock.logic += "// Current inner ID in assign stages\n";
      // Build RdInnerID for the assign stages, and update the mapping table.
      for (int iAssignSource = 0; iAssignSource < assignIDSources.size(); ++iAssignSource) {
        IDSource assignIDSource = assignIDSources.get(iAssignSource);

        var assignStageRdInnerIDKey = assignIDSource.makeKey_RdInnerID(innerIDWidth, getInnerIDCount());
        String assignStageRdInnerIDWire = assignStageRdInnerIDKey.toString(false) + "_s";
        logicBlock.declarations += String.format("wire [%d-1:0] %s;\n", innerIDWidth, assignStageRdInnerIDWire);
        logicBlock.outputs.add(
            new NodeInstanceDesc(assignStageRdInnerIDKey, assignStageRdInnerIDWire, ExpressionType.WireName, commonRequestedFor));

        String assignStageRdInnerIDExpr = getStageAssignIDExpr.apply(iAssignSource);
        logicBlock.logic += String.format("assign %s = %s;\n", assignStageRdInnerIDWire, assignStageRdInnerIDExpr);

        // mappingResetLogic += language.tab + String.format("%s[%d] <= %d'd%d;\n", mappingArray, iAssignStage, innerIDWidth, (1 <<
        // innerIDWidth) - 1);
        String updateExpr = forceAlwaysAssignID ? assignStageRdInnerIDWire
                                                : String.format("%s ? %s : %s",
                                                                assignPipeConditionWires_nostall[iAssignSource], // if we have assigned an inner ID,
                                                                assignStageRdInnerIDWire,               // then assign that inner ID,
                                                                getInvalidIDExpr() // otherwise assign the 'invalid ID' marker
                                                  );
        mappingUpdateLogic +=
            language.tab + String.format("if (%s)\n%s%s[%s] <= %s;\n",
                                         assignAnyPipeConditionExprs[iAssignSource], // Only update if this assign stage does run through.
                                         language.tab.repeat(2),
                                         mappingArray,
                                         assignStageRdIDExpr[iAssignSource], // Update the mapping array for the assign source's RdID.
                                         updateExpr);

        var assignStageRdInnerIDValidKey = assignIDSource.makeKey_RdInnerIDValid();
        String assignStageRdInnerIDValidWire = assignStageRdInnerIDValidKey.toString(false) + "_s";
        logicBlock.declarations += String.format("wire %s;\n", assignStageRdInnerIDValidWire);
        logicBlock.logic += String.format("assign %s = %s;\n", assignStageRdInnerIDValidWire, assignPipeConditionWires_nostall[iAssignSource]);
        logicBlock.outputs.add(
            new NodeInstanceDesc(assignStageRdInnerIDValidKey, assignStageRdInnerIDValidWire, ExpressionType.WireName, commonRequestedFor));
      }
      String mappingResetLogic = "";
      for (int iID = 0; iID < outerIDCount; ++iID) {
        mappingResetLogic += language.tab + String.format("%s[%d] <= %s;\n", mappingArray, iID, getInvalidIDExpr());
      }
      logicBlock.logic += "// ID map update logic\n";
      logicBlock.logic += language.CreateInAlways(
          true, String.format("if (%s) begin\n%send\nelse begin\n%send\n", language.reset, mappingResetLogic, mappingUpdateLogic));

      //Output the remaining RdInnerID. The assign ID sources' inner ID is handled above.
      logicBlock.logic += "// Current inner ID in other stages\n";
      Stream.concat(retireIDSources.stream(), translationIDSources.stream()).forEach(translateSource -> {
        assert(!assignIDSourcesFront.contains(translateSource.key_ID.getStage()));
        var readStageRdInnerIDKey = translateSource.makeKey_RdInnerID(innerIDWidth, getInnerIDCount());
        String readStageRdInnerIDWire = readStageRdInnerIDKey.toString(false) + "_s";
        logicBlock.declarations += String.format("wire [%d-1:0] %s;\n", innerIDWidth, readStageRdInnerIDWire);
        logicBlock.outputs.add(
            new NodeInstanceDesc(readStageRdInnerIDKey, readStageRdInnerIDWire, ExpressionType.WireName, commonRequestedFor));
        String rdIDExpr = registry.lookupExpressionRequired(translateSource.key_ID, commonRequestedFor);
        //Read the inner ID from the mapping array.
        logicBlock.logic += String.format("assign %s = %s[%s];\n", readStageRdInnerIDWire, mappingArray, rdIDExpr);

        var readStageRdInnerIDValidKey = translateSource.makeKey_RdInnerIDValid();
        String readStageRdInnerIDValidWire = readStageRdInnerIDValidKey.toString(false) + "_s";
        logicBlock.declarations += String.format("wire %s;\n", readStageRdInnerIDValidWire);

        //Build a 'valid' condition for the ID wire, based on whether the retire is running
        // and if the inner ID is the 'invalid' marker.
        String pipeCondAny_nostall = (translateSource.getTriggerStage().getKind() == StageKind.Root
                                         && translateSource.key_valid.isEmpty())
                                     ? "1'b1"
                                     : buildPipeCondition(registry, commonRequestedFor, translateSource, false, false, false);
        if (hasDedicatedInvalidID()) {
          logicBlock.logic += String.format("assign %s = %s && %s != %s;\n",
                                              readStageRdInnerIDValidWire,
                                              pipeCondAny_nostall,
                                              readStageRdInnerIDWire, getInvalidIDExpr());
        }
        else {
          logicBlock.logic += String.format("assign %s = %s;\n", readStageRdInnerIDValidWire, pipeCondAny_nostall);
        }
        logicBlock.outputs.add(
            new NodeInstanceDesc(readStageRdInnerIDValidKey, readStageRdInnerIDValidWire, ExpressionType.WireName, commonRequestedFor));
      });
      return logicBlock;
    }
  }
  private PipeIDBuilder pipeIDBuilder = null;

  /** Triggers the ID builder(s), if already generated previously. */
  public void trigger(Consumer<NodeLogicBuilder> out) {
    if (pipeIDBuilder != null)
      pipeIDBuilder.trigger(out);
  }

  @Override
  public void implement(Consumer<NodeLogicBuilder> out, Iterable<NodeInstanceDesc.Key> nodeKeys, boolean isLast) {
    var nodeKeyIter = nodeKeys.iterator();
    while (nodeKeyIter.hasNext()) {
      NodeInstanceDesc.Key nodeKey = nodeKeyIter.next();

      // Can only operate in the range from assign to retire.
      if (!assignIDSourcesFront.isAroundOrBefore(nodeKey.getStage(), false)
          || !retireIDSourcesFront.isAroundOrAfter(nodeKey.getStage(), false))
        continue;
      if (nodeKey.getPurpose().matches(Purpose.REGULAR)
          && Stream.concat(Stream.concat(assignIDSources.stream(), retireIDSources.stream()), translationIDSources.stream())
             .anyMatch(source -> nodeKey.getNode().equals(source.node_RdInnerID) || nodeKey.getNode().equals(source.node_RdInnerIDValid))) {
        if (!nodeKey.getISAX().isEmpty() || nodeKey.getAux() != 0)
          continue;
        if (pipeIDBuilder == null) {
          pipeIDBuilder = new PipeIDBuilder(String.format("IDBasedPipelineStrategy(%d)_RdInnerID", uniqueID), nodeKey);
          out.accept(pipeIDBuilder);
        }

        if (!pipeIDBuilder.stagesToImplementRead.contains(nodeKey.getStage())) {
          pipeIDBuilder.stagesToImplementRead.add(nodeKey.getStage());
          pipeIDBuilder.trigger(out);
        }

        nodeKeyIter.remove();
      } else if (nodeKey.equals(key_RdLastMaxID)) {
        RequestedForSet requestedFor = new RequestedForSet();
        out.accept(NodeLogicBuilder.fromFunction(String.format("IDBasedPipelineStrategy(%d)_RdLastMaxID", uniqueID), registry -> {
          var logicBlock = new NodeLogicBlock();
          String maxIDReg = nodeKey.toString(false) + "_reg";
          // Create {ID-last_max, is valid} string pairs.
          List<String[]> maxIDCandidates =
              assignIDSources
                  .stream()
                  .map(assignSource ->
                         new String[] {String.format("%s - %s",
                                         registry.lookupExpressionRequired(assignSource.key_ID, requestedFor),
                                         maxIDReg),
                                       buildPipeCondition(registry, requestedFor, assignSource, false, false, true)
                         })
                  .collect(Collectors.toCollection(ArrayList::new));
          // Generate a binary reduction tree to get the maximum (newest) ID.
          assert(assignIDSources.size() < outerIDCount);
          int outerIndex = 0;
          while (maxIDCandidates.size() > 1) {
            boolean isLastRound = (maxIDCandidates.size() == 2);
            int iOutput = 0;
            for (int iIn = 0; iIn < maxIDCandidates.size(); ++iIn, ++iOutput) {
              String[] inCandidateA = maxIDCandidates.get(iIn);
              if (iIn + 1 < maxIDCandidates.size()) {
                String[] inCandidateB = maxIDCandidates.get(iIn + 1);
                //(!valid_b || valid_a && id_a > id_b) ? {id_a, valid_a} : {id_b, valid_b}
                String combinedExprID = String.format("((!%s || %s && %s[%d+1-1:1] > %s[%d+1-1:1]) ? %s : %s)",
                                                       inCandidateB[1], inCandidateA[1], inCandidateA, outerIDWidth, inCandidateB, outerIDWidth,
                                                       inCandidateA, inCandidateB);
                String combinedExprValid = String.format("%s || %s", inCandidateA[1], inCandidateB[1]);

                if (!isLastRound) {
                  // Store intermediate values in a wire, so the expression doesn't explode.
                  String combinedExprIDWire = String.format("%s_reduce_%d_%d", nodeKey.toString(false), outerIndex, iOutput);
                  logicBlock.declarations += String.format("wire [%d-1:0] %s;\n", nodeKey.getNode().size + 1, combinedExprIDWire);
                  logicBlock.logic += String.format("assign %s = %s;\n", combinedExprIDWire, combinedExprID);
                  combinedExprID = combinedExprIDWire;

                  String combinedExprValidWire = String.format("%s_reduce_%d_%d_valid", nodeKey.toString(false), outerIndex, iOutput);
                  logicBlock.declarations += String.format("wire %s;\n", combinedExprValidWire);
                  logicBlock.logic += String.format("assign %s = %s;\n", combinedExprValidWire, combinedExprValid);
                  combinedExprValid = combinedExprValidWire;
                }

                maxIDCandidates.set(iOutput, new String[] { combinedExprID, combinedExprValid });
                ++iIn;
              } else
                maxIDCandidates.set(iOutput, inCandidateA);
            }
            // Trim unused space of the candidate list.
            maxIDCandidates.subList(iOutput, maxIDCandidates.size()).clear();
            ++outerIndex;
          }
          // Create the register based on the combinational maximum ID.
          logicBlock.declarations += String.format("wire [%d-1:0] %s;\n", nodeKey.getNode().size, maxIDReg);
          logicBlock.logic += language.CreateInAlways(true,
                                String.format("%s <= %s ? '0 : (%s[0] ? (%s[%d+1-1:1]+%s) : %s);\n",
                                                maxIDReg,
                                                language.reset,
                                                maxIDCandidates.get(0),
                                                maxIDCandidates.get(0), outerIDWidth, maxIDReg,
                                                maxIDReg));
          logicBlock.outputs.add(new NodeInstanceDesc(key_RdLastMaxID, maxIDReg, ExpressionType.WireName, requestedFor));
          return logicBlock;
        }));

        nodeKeyIter.remove();
      }
    }
  }
}
