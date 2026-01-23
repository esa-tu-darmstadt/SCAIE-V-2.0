package scaiev.scal.strategy.standard;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.coreconstr.CoreNode.CoreNodeTag;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.MultiportStallAttributes;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;
import scaiev.scal.InterfaceRequestBuilder;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.SCALUtil;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.util.Verilog;

/** Strategy that combines all nodes tagged with a non-empty ISAX / non-zero aux value to global WrStall and WrFlush nodes. */
public class StallFlushDeqStrategy extends SingleNodeStrategy {
  protected static final Logger logger = LogManager.getLogger();

  Verilog language;
  BNode bNodes;
  Core core;
  HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   */
  public StallFlushDeqStrategy(Verilog language, BNode bNodes, Core core,
                               HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
  }

  @Override
  public Optional<NodeLogicBuilder> implement(NodeInstanceDesc.Key nodeKey) {
    boolean opNeedsCoreSupport = !nodeKey.getNode().equals(bNodes.WrDeqInstr) && !nodeKey.getNode().equals(bNodes.WrRerunNext);

    var multiportStallAttr = nodeKey.getStage().getMultiportBase().getTagAttr(StageTag.MultiportStall, MultiportStallAttributes.class);
    assert((nodeKey.getStage().getMultiportBase().getKind() == StageKind.CoreMultiport) == (multiportStallAttr != null));

    boolean sharedAcrossPorts_ = false;
    boolean perPort_ = !opNeedsCoreSupport;
    if (multiportStallAttr != null && nodeKey.getNode().equals(bNodes.WrFlush)) {
      sharedAcrossPorts_ = opNeedsCoreSupport && multiportStallAttr.hasSharedFlush();
      perPort_ = perPort_ || multiportStallAttr.perPortFlush();
    }
    else if (multiportStallAttr != null && nodeKey.getNode().equals(bNodes.WrStall)) {
      sharedAcrossPorts_ = opNeedsCoreSupport && multiportStallAttr.hasSharedStall();
      perPort_ = perPort_ || multiportStallAttr.perPortStall();
    }
    final boolean sharedAcrossPorts = sharedAcrossPorts_;
    final boolean perPort = perPort_;

    //WrStall, etc. - build overall Wr node from all individual condition sub-nodes
    if ((nodeKey.getNode().equals(bNodes.WrStall) || nodeKey.getNode().equals(bNodes.WrFlush) ||
         nodeKey.getNode().equals(bNodes.WrDeqInstr) || nodeKey.getNode().equals(bNodes.WrRerunNext)) &&
        nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.REGULAR) && nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0 &&
        (!opNeedsCoreSupport || nodeKey.getStage().getKind() == StageKind.Decoupled ||
         (core.getNodes().containsKey(nodeKey.getNode()) &&
          core.translateStageScheduleNumber(core.getNodes().get(nodeKey.getNode()).getEarliest())
              .isAroundOrBefore(nodeKey.getStage(), false)))) {

      if (!sharedAcrossPorts && nodeKey.getStage().getKind() == StageKind.CoreMultiport)
        return Optional.empty();

      InterfaceRequestBuilder interfBuilder = new InterfaceRequestBuilder(
          opNeedsCoreSupport ? NodeInstanceDesc.Purpose.MARKER_TOCORE_PIN : NodeInstanceDesc.Purpose.MARKER_INTERNALIMPL_PIN, nodeKey);

      // Logging util var
      var onceContainer = new Object() { boolean warnMultiportWriteSharing = false; };

      return Optional.of(
          NodeLogicBuilder.fromFunction("StallFlushDeqStrategy (" + nodeKey.toString() + ")", (NodeRegistryRO registry, Integer aux) -> {
            NodeLogicBlock ret = new NodeLogicBlock();

            String nodeWire = language.CreateLocalNodeName(nodeKey.getNode(), nodeKey.getStage(), "");
            ret.declarations += "wire " + nodeWire + ";\n";

            // Find all sub-nodes (i.e. from some ISAX, or some internal node with aux != 0)
            // Note: A dedicated purpose (say, OR_COMBINE_REGULAR) could be appropriate here,
            //  but since WrStall will be set via SCALInputOutputStrategy,
            //  it would require additional special-case handling
            //  to generate these OR_COMBINE_REGULAR nodes for WrStall,WrFlush only.
            var valueBuilder = new Object() {
              String newValue = "";
              void consumeSubNode(NodeInstanceDesc subNode) {
                newValue += (newValue.isEmpty() ? "" : " || ") + subNode.getExpressionWithParens();
                interfBuilder.requestedFor.addAll(subNode.getRequestedFor(), true);
              }
            };

            for (NodeInstanceDesc subNode :
                 registry.lookupAll(new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), ""), false)) {
              // The sub-node should have some unique identifier.
              assert (subNode.getKey().getISAX().length() > 0 || subNode.getKey().getAux() != 0);
              valueBuilder.consumeSubNode(subNode);
            }
            //Multi-port stage handling
            if (sharedAcrossPorts) {
              if (nodeKey.getStage().getKind() == StageKind.CoreMultiport && !perPort) {
                // Retrieve sub-conditions from each port.
                for (PipelineStage portStage : nodeKey.getStage().getChildren()) if (portStage.getKind() == StageKind.Core) {
                  var subNode = registry.lookupOptionalUnique(new NodeInstanceDesc.Key(nodeKey.getNode(), portStage, ""));
                  if (subNode.isPresent())
                    valueBuilder.consumeSubNode(subNode.get());
                }

                if (!onceContainer.warnMultiportWriteSharing) {
                  // Warn once: Applying WrFlush, WrStall etc. from individual stages,
                  //  which may be unexpected behavior.
                  logger.warn("StallFlushDeqStrategy: Combining {} nodes from all ports of {}. This may produce unexpected results.",
                              nodeKey.getNode().name, nodeKey.getStage().getName());
                  onceContainer.warnMultiportWriteSharing = true;
                }
              }
              else {
                // nodeKey.getStage() is an individual port stage.
                assert(nodeKey.getStage().getMultiportBase().getKind() == StageKind.CoreMultiport);
                // Make sure the shared Multiport-stage node is instantiated.
                registry.lookupRequired(new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage().getMultiportBase(), ""));
              }
            }
            //ISAXMux stage handling
            if (perPort && multiportStallAttr != null && nodeKey.getStage() != nodeKey.getStage().getMultiportBase()) {
              // For a port stage, consider all ISAXes where the operation is listed in the ISAXMux stage.
              // PortMuxStrategy will handle creating the per-ISAX nodes in each port stage.
              // -> The lookupAll above will see the new PortMuxStrategy output in a later iteration.
              PipelineStage multiportBase = nodeKey.getStage().getMultiportBase();
              Optional<PipelineStage> muxStage_opt = multiportBase.getChildren().stream().filter(st->st.getKind()==StageKind.ISAXMux).findAny();
              if (muxStage_opt.isPresent()) {
                for (String isax : this.op_stage_instr.getOrDefault(nodeKey.getNode(), new HashMap<>()).getOrDefault(muxStage_opt.get(), new HashSet<>()))
                  registry.lookupExpressionRequired(new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), isax));
              }
            }

            if (valueBuilder.newValue.isEmpty())
              valueBuilder.newValue = "1'b0";
            else if (!opNeedsCoreSupport ||
                     (nodeKey.getStage().getKind() == StageKind.Core && (perPort || nodeKey.getStage().getMultiportBase() == nodeKey.getStage()) ||
                      nodeKey.getStage().getKind() == StageKind.CoreMultiport && sharedAcrossPorts ||
                      nodeKey.getStage().getKind() == StageKind.CoreInternal)) {
              // Explicitly request instantiation of the SCAL->core output pin
              ret.addOther(interfBuilder.apply(registry, aux));
            }

            ret.logic += "assign " + nodeWire + " = " + valueBuilder.newValue + ";";
            var output =
                new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR), nodeWire, ExpressionType.WireName);
            output.addRequestedFor(interfBuilder.requestedFor, false);
            ret.outputs.add(output);

            return ret;
          }));
    }
    //RdStallLegacy
    if (nodeKey.getNode().equals(bNodes.RdStallLegacy) && nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.REGULAR) &&
        nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0 &&
        core.translateStageScheduleNumber(core.getNodes().get(nodeKey.getNode()).getEarliest())
            .isAroundOrBefore(nodeKey.getStage(), false)) {

      // Special handling for deprecated global RdStall.

      NodeInstanceDesc.Key generalKey =
          new NodeInstanceDesc.Key(bNodes.RdStall, nodeKey.getStage(), ""); // RdStall (e.g. from core) without any added conditions
      RequestedForSet requestedFor = new RequestedForSet(nodeKey.getISAX());

      return Optional.of(
          NodeLogicBuilder.fromFunction("StallFlushDeqStrategy (" + nodeKey.toString() + ")", (NodeRegistryRO registry, Integer aux) -> {
            NodeLogicBlock ret = new NodeLogicBlock();
            String nodeWire = language.CreateLocalNodeName(nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
            ret.declarations += "wire " + nodeWire + ";\n";
            String newValue = "";
            // Find all write sub-nodes that do not directly belong to any ISAX.
            for (NodeInstanceDesc subNode : registry.lookupAll(new NodeInstanceDesc.Key(bNodes.WrStall, nodeKey.getStage(), ""), false)) {
              if (subNode.getKey().getISAX().isEmpty() && subNode.getKey().getAux() == 0)
                continue; // Ignore main WrStall/WrFlush node
              if (subNode.getKey().getISAX().isEmpty())
                newValue += (newValue.isEmpty() ? "" : " || ") + subNode.getExpressionWithParens();
            }
            newValue += (newValue.isEmpty() ? "" : " || ") + registry.lookupRequired(generalKey, requestedFor).getExpressionWithParens();
            ret.logic += "assign " + nodeWire + " = " + newValue + ";";
            ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR), nodeWire,
                                                 ExpressionType.WireName, requestedFor));
            return ret;
          }));
    }
    //RdStall, RdFlush
    if ((nodeKey.getNode().equals(bNodes.RdStall) || nodeKey.getNode().equals(bNodes.RdFlush)) &&
        nodeKey.getStage().getTags().contains(StageTag.MultiportPipe) &&
        !SCALUtil.nodeIsPerPort(nodeKey.getNode(), nodeKey.getStage()) &&
        nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.REGULAR) && nodeKey.getAux() == 0 &&
        core.translateStageScheduleNumber(core.getNodes().get(nodeKey.getNode()).getEarliest())
            .isAroundOrBefore(nodeKey.getStage(), false)) {
      // Assign per-port signal (ISAX or general) from shared signal.
      RequestedForSet requestedFor = new RequestedForSet(nodeKey.getISAX());

      return Optional.of(
          NodeLogicBuilder.fromFunction("StallFlushDeqStrategy~assignFromShared (" + nodeKey.toString() + ")", (NodeRegistryRO registry, Integer aux) -> {
            NodeLogicBlock ret = new NodeLogicBlock();

            String nodeWire = language.CreateLocalNodeName(nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
            ret.declarations += "wire " + nodeWire + ";\n";
            String sharedValue = registry.lookupExpressionRequired(
                new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage().getMultiportBase(), nodeKey.getISAX()),
                requestedFor);

            ret.logic += "assign %s = %s;\n".formatted(nodeWire, sharedValue);

            ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR), nodeWire,
                                                 ExpressionType.WireName, requestedFor));

            return ret;
          }));
    }
    if ((nodeKey.getNode().equals(bNodes.RdStall) || nodeKey.getNode().equals(bNodes.RdFlush)) &&
        nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.REGULAR) && !nodeKey.getISAX().isEmpty() && nodeKey.getAux() == 0 &&
        core.translateStageScheduleNumber(core.getNodes().get(nodeKey.getNode()).getEarliest())
            .isAroundOrBefore(nodeKey.getStage(), false)) {
      // Construct per-ISAX RdStall/RdFlush that includes all WrStalls/WrFlushes from other ISAXes or from SCAL internally
      NodeInstanceDesc.Key generalKey =
          new NodeInstanceDesc.Key(nodeKey.getNode(), nodeKey.getStage(), ""); // RdStall (e.g. from core) without any added conditions
      SCAIEVNode wrNode = bNodes.GetSCAIEVNode(bNodes.GetNameWrNode(nodeKey.getNode()));

      InterfaceRequestBuilder interfBuilder = new InterfaceRequestBuilder(NodeInstanceDesc.Purpose.MARKER_FROMCORE_PIN, generalKey);
      interfBuilder.requestedFor.addRelevantISAX(nodeKey.getISAX());
      RequestedForSet requestedFor = new RequestedForSet(nodeKey.getISAX());

      return Optional.of(
          NodeLogicBuilder.fromFunction("StallFlushDeqStrategy (" + nodeKey.toString() + ")", (NodeRegistryRO registry, Integer aux) -> {
            NodeLogicBlock ret = new NodeLogicBlock();

            String nodeWire = language.CreateLocalNodeName(nodeKey.getNode(), nodeKey.getStage(), nodeKey.getISAX());
            ret.declarations += "wire " + nodeWire + ";\n";

            String newValue = "";
            if (nodeKey.getStage().getKind() == StageKind.Sub && nodeKey.getNode().equals(bNodes.RdStall)) {
              // Sub-stages currently do not fully implement RdStall. Add RdIValid to force RdStall while no instruction is present.
              newValue +=
                  (newValue.isEmpty() ? "" : " || ") + "!" +
                  registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, nodeKey.getStage(), nodeKey.getISAX()));
            }
            Stream<PipelineStage> relevantWriteStages = Stream.empty();
            boolean existsInCurrentStage = true;
            if (perPort && nodeKey.getStage().getTags().contains(StageTag.MultiportPipe)) {
              relevantWriteStages = Stream.of(nodeKey.getStage());
              int portIdx = nodeKey.getStage().getMultiportBase().getChildren().indexOf(nodeKey.getStage());
              assert(portIdx != -1);
              //Commented out: Explicitly include writes from earlier ports
              // -> assuming the core's signal does that already
              //if (portIdx > 0 && (nodeKey.getNode().equals(bNodes.RdFlush) || nodeKey.getNode().equals(bNodes.RdStall) && multiportStallAttr.stallAffectsNext()))
              //  relevantWriteStages = Stream.concat(nodeKey.getStage().getMultiportBase().getChildren().stream().limit(portIdx), relevantWriteStages);
            }
            else if (sharedAcrossPorts) {
              relevantWriteStages = Stream.of(nodeKey.getStage().getMultiportBase());
              existsInCurrentStage = (nodeKey.getStage() == nodeKey.getStage().getMultiportBase());
            }
            else
              relevantWriteStages = Stream.of(nodeKey.getStage());
            for (PipelineStage wrStage : relevantWriteStages.toList()) {
              // Find all write sub-nodes that do not belong to this ISAX.
              for (NodeInstanceDesc subNode : registry.lookupAll(new NodeInstanceDesc.Key(wrNode, wrStage, ""), false)) {
                if (subNode.getKey().getISAX().isEmpty() && subNode.getKey().getAux() == 0)
                  continue; // Ignore main WrStall/WrFlush node
                if (!subNode.getKey().getISAX().equals(nodeKey.getISAX()))
                  newValue += (newValue.isEmpty() ? "" : " || ") + subNode.getExpressionWithParens();
              }
            }
            if (existsInCurrentStage && (nodeKey.getStage().getKind() == StageKind.Core || nodeKey.getStage().getKind() == StageKind.CoreInternal)) {
              // Explicitly request instantiation of the core->SCAL input pin
              ret.addOther(interfBuilder.apply(registry, aux));
            }
            if (existsInCurrentStage) {
              newValue += (newValue.isEmpty() ? "" : " || ") +
                          registry.lookupRequired(generalKey, interfBuilder.requestedFor).getExpressionWithParens();
            }

            ret.logic += "assign " + nodeWire + " = " + newValue + ";";
            ret.outputs.add(new NodeInstanceDesc(NodeInstanceDesc.Key.keyWithPurpose(nodeKey, Purpose.REGULAR), nodeWire,
                                                 ExpressionType.WireName, requestedFor));

            return ret;
          }));
    }
    return Optional.empty();
  }
}
