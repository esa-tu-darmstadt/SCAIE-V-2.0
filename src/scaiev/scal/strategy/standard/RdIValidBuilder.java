package scaiev.scal.strategy.standard;

import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAL.RdIValidStageDesc;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistry;
import scaiev.scal.NodeRegistryRO;
import scaiev.util.Verilog;

/**
 * Common builder implementation, used by RdIValidStrategy and derived by PipeliningRdIValidStrategy.
 * Builds the outputs for REGULAR and WIREDIN RdIValid. This default implementation decodes RdInstr.
 **/
public class RdIValidBuilder extends NodeLogicBuilder {
  private Verilog language;
  private BNode bNodes;
  private Core core;
  private HashMap<String, SCAIEVInstr> allISAXes;
  private Function<PipelineStage, RdIValidStageDesc> stage_getRdIValidDesc;

  NodeInstanceDesc.Key nodeKey;

  RequestedForSet requestedFor;

  boolean sentMarkerPartial = false;
  boolean sentMarkerFull = false;

  boolean requestedPartialCond = false;
  boolean requestedFullCond = false;
  public RdIValidBuilder(String name, NodeInstanceDesc.Key nodeKey, Verilog language, BNode bNodes, Core core,
                         HashMap<String, SCAIEVInstr> allISAXes, Function<PipelineStage, RdIValidStageDesc> stage_getRdIValidDesc) {
    super(name);
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.allISAXes = allISAXes;
    this.stage_getRdIValidDesc = stage_getRdIValidDesc;

    this.nodeKey = nodeKey;
    this.updateForNodeKeyPurpose(null, nodeKey.getPurpose());
    this.requestedFor = new RequestedForSet();
    this.requestedFor.addRelevantISAXes(allISAXes.values().stream().map(isax -> isax.GetName()).toList());
  }

  /**
   * Call this if a new key matching WIREDIN and/or REGULAR is encountered,
   * to ensure the builder instantiates the logic for all requested keys.
   * @param out the way to add new NodeLogicBuilders to the composer, used to construct a wake-up marker for RdIValidBuilder.
   *            Should not be set to null, unless the caller is sure that this RdIValidBuilder is going to be invoked regardless.
   * @param newPurpose the Purpose of the new encountered key
   **/
  public void updateForNodeKeyPurpose(Consumer<NodeLogicBuilder> out, Purpose newPurpose) {
    boolean doSendMarker = false;
    boolean setPartial = newPurpose.matches(Purpose.WIREDIN) && !newPurpose.matches(Purpose.REGULAR);
    boolean setFull = newPurpose.matches(Purpose.REGULAR);
    if (setPartial && !requestedPartialCond) {
      assert (!sentMarkerPartial);
      requestedPartialCond = true;
      doSendMarker = true;
    }
    if (setFull && !requestedFullCond) {
      assert (!sentMarkerFull);
      requestedFullCond = true;
      doSendMarker = true;
    }
    if (doSendMarker && out != null) {
      sendMarker(out);
    }
  }

  /** Invoked (up to) once per builder call. */
  protected boolean canCreateUpUntilStage(NodeRegistryRO registry, RequestedForSet requestedFor) {
    NodeInstanceDesc.Key rdInstrKey = new NodeInstanceDesc.Key(bNodes.RdInstr, nodeKey.getStage(), "");
    Optional<NodeInstanceDesc> rdInstrOptional = registry.lookupOptionalUnique(rdInstrKey, requestedFor);
    return (rdInstrOptional.isPresent() ||
            core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.RdInstr).GetExpensive()).isAfter(nodeKey.getStage(), false));
  }

  /** Retrieves the base RdIValid condition. The default implementation decodes RdInstr from the current stage. */
  protected String getBaseDecodeCond(NodeRegistryRO registry, RequestedForSet requestedFor) {
    NodeInstanceDesc.Key rdInstrKey = new NodeInstanceDesc.Key(bNodes.RdInstr, nodeKey.getStage(), "");
    return language.CreateValidEncodingOneInstr(nodeKey.getISAX(), allISAXes, registry.lookupExpressionRequired(rdInstrKey, requestedFor));
  }

  protected String getAdditionalCond_fromCore(Optional<RdIValidStageDesc> iValidStageDesc_opt, NodeRegistryRO registry,
                                              RequestedForSet requestedFor) {
    Optional<String> globalStageValidCond =
        iValidStageDesc_opt.flatMap(iValidStageDesc -> Optional.ofNullable(iValidStageDesc.validCond_isax.get("")))
            .map(customInterface
                 -> registry.lookupExpressionRequired(customInterface.makeKey(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN), requestedFor));
    Optional<String> isaxStageValidCond =
        iValidStageDesc_opt.flatMap(iValidStageDesc -> Optional.ofNullable(iValidStageDesc.validCond_isax.get(nodeKey.getISAX())))
            .map(customInterface
                 -> registry.lookupExpressionRequired(customInterface.makeKey(Purpose.match_REGULAR_WIREDIN_OR_PIPEDIN), requestedFor));
    return (isaxStageValidCond.isPresent() ? globalStageValidCond.map(expr -> expr + " && ") : globalStageValidCond).orElse("") +
        isaxStageValidCond.orElse("");
  }
  protected String getAdditionalCond_internal(NodeRegistryRO registry, RequestedForSet requestedFor) {
    return "!" + registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdFlush, nodeKey.getStage(), ""), requestedFor);
  }
  protected String getAdditionalCond(Optional<RdIValidStageDesc> iValidStageDesc_opt, NodeRegistryRO registry,
                                     RequestedForSet requestedFor) {
    String condFromCore = getAdditionalCond_fromCore(iValidStageDesc_opt, registry, requestedFor);
    String condInternal = getAdditionalCond_internal(registry, requestedFor);
    return condFromCore + (condFromCore.isEmpty() ? "" : " && ") + condInternal;
  }

  // Can be used once to trigger a re-evaluation when setting requestedPartialCond or requestedFullCond after initial node implementation.
  protected static final Purpose marker_ReevalRdIValid = new Purpose("marker_ReevalRdIValid", true, Optional.empty(), List.of());
  protected Purpose markerPurpose = marker_ReevalRdIValid;

  private void sendMarker(Consumer<NodeLogicBuilder> out) {
    if (!((requestedPartialCond && !sentMarkerPartial) || (requestedFullCond && !sentMarkerFull)))
      return;
    boolean requestedPartialCond_ = requestedPartialCond;
    boolean requestedFullCond_ = requestedFullCond;
    out.accept(NodeLogicBuilder.fromFunction(this.name + "||SendReeval", registry -> {
      return new NodeLogicBlock() {
        {
          outputs.add(
              new NodeInstanceDesc(new NodeInstanceDesc.Key(markerPurpose, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX(),
                                                            (requestedPartialCond_ ? (1 << 0) : 0) | (requestedFullCond_ ? (1 << 1) : 0)),
                                   "1", ExpressionType.AnyExpression));
        }
      };
    }));
    sentMarkerPartial = sentMarkerPartial || requestedPartialCond;
    sentMarkerFull = sentMarkerFull || requestedFullCond;
  }

  @Override
  public NodeLogicBlock apply(NodeRegistryRO registry, int aux) {
    NodeInstanceDesc.Key partialOutputKey =
        new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.WIREDIN, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
    NodeInstanceDesc.Key fullOutputKey =
        new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.REGULAR, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
    PipelineStage stage = nodeKey.getStage();
    NodeLogicBlock ret = new NodeLogicBlock();

    if (!requestedPartialCond || !requestedFullCond) {
      // Listen for re-evaluation requests (non-full match since aux varies).
      registry.lookupAll(new NodeInstanceDesc.Key(markerPurpose, nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX()), false);
    }

    // Optional RdIValid stage N->N+1 pipelining is handled by pipelineBuilder_*.
    //  -> If we're past native availability for RdInstr in the core pipeline,
    //     pipeline RdIValid rather than RdInstr.

    if (canCreateUpUntilStage(registry, requestedFor)) {
      // Create RdIValid by decoding the instruction in this stage.
      Optional<RdIValidStageDesc> iValidStageDesc_opt = Optional.ofNullable(stage_getRdIValidDesc.apply(stage));
      String condStageValid_internal = getAdditionalCond_internal(registry, requestedFor);
      if (!condStageValid_internal.isEmpty())
        condStageValid_internal = " && " + condStageValid_internal;
      // Partial condition: RdInstr decoding and internal condition.
      String ivalidCond = getBaseDecodeCond(registry, requestedFor) // requiring RdInstr (by default)
                          + condStageValid_internal;
      if (requestedPartialCond) {
        // Create a WIREDIN node with just the basic and internal conditions.
        String namePartialWire = language.CreateBasicNodeName(nodeKey.getNode(), stage, nodeKey.getISAX(), true) + "_partial";
        // Add the declaration for the wire.
        ret.declarations += String.format("wire %s;\n", namePartialWire);
        // Add the decode logic.
        ret.logic += String.format("assign %s = %s;\n", namePartialWire, ivalidCond);
        ret.outputs.add(new NodeInstanceDesc(partialOutputKey, namePartialWire, ExpressionType.WireName, requestedFor));

        ivalidCond = namePartialWire;
      }
      if (requestedFullCond) {
        // Add the additional conditions from the core (if any).
        String condStageValid_fromCore = getAdditionalCond_fromCore(iValidStageDesc_opt, registry, requestedFor);
        if (!condStageValid_fromCore.isEmpty())
          condStageValid_fromCore = " && " + condStageValid_fromCore;
        ivalidCond += condStageValid_fromCore;
        String inStageValidCond =
            registry.lookupRequired(new NodeInstanceDesc.Key(bNodes.RdInStageValid, stage, ""), requestedFor).getExpressionWithParens();
        if (!inStageValidCond.startsWith(NodeRegistry.MISSING_PREFIX))
          ivalidCond += " && " + inStageValidCond;

        // Create the full node as REGULAR.
        String nameWire = language.CreateLocalNodeName(nodeKey.getNode(), stage, nodeKey.getISAX());
        // Add the declaration for the wire.
        ret.declarations += String.format("wire %s;\n", nameWire);
        // Add the decode logic.
        ret.logic += String.format("assign %s = %s;\n", nameWire, ivalidCond);
        ret.outputs.add(new NodeInstanceDesc(fullOutputKey, nameWire, ExpressionType.WireName, requestedFor));
      }
    } else if (core.TranslateStageScheduleNumber(core.GetNodes().get(bNodes.RdInstr).GetEarliest()).isBefore(stage, false)) {
      // This builder doesn't handle pipelining on its own;
      //  here, it only adds the previous stage as a requirement
      //  to force creation of RdIValid in the previous stage.
      // The intention is that this builder is only called if the pipeline builder finds no node in stage-1 to pipeline with.
      String _prevnode = registry.lookupExpressionRequired(
          new NodeInstanceDesc.Key(NodeInstanceDesc.Purpose.PIPEDIN, nodeKey.getNode(), stage, nodeKey.getISAX(), nodeKey.getAux()),
          requestedFor);
      // This builder should only be called if the node in stage-1 for pipelining is not present already.
      assert (_prevnode.startsWith(NodeRegistry.MISSING_PREFIX));
      ret.outputs.add(new NodeInstanceDesc(requestedFullCond ? fullOutputKey : partialOutputKey, NodeRegistry.MISSING_PREFIX + fullOutputKey.toString(false),
                                           ExpressionType.AnyExpression, requestedFor));
    }
    return ret;
  }
}
