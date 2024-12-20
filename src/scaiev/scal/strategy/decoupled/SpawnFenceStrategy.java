package scaiev.scal.strategy.decoupled;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAL;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeInstanceDesc.RequestedForSet;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.util.Verilog;

/** Implements stalling logic and RdFence nodes for the disaxfence instruction. */
public class SpawnFenceStrategy extends SingleNodeStrategy {

  // logging
  protected static final Logger logger = LogManager.getLogger();

  Verilog language;
  BNode bNodes;
  Core core;
  HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  HashMap<String, SCAIEVInstr> allISAXes;
  boolean hasWrRD_datahazard;
  /**
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param allISAXes The ISAX descriptions
   * @param hasWrRD_datahazard If set, ignores WrRD_spawn nodes for fence
   */
  public SpawnFenceStrategy(Verilog language, BNode bNodes, Core core,
                            HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                            HashMap<String, SCAIEVInstr> allISAXes, boolean hasWrRD_datahazard) {
    this.language = language;
    this.bNodes = bNodes;
    this.core = core;
    this.op_stage_instr = op_stage_instr;
    this.allISAXes = allISAXes;
    this.hasWrRD_datahazard = hasWrRD_datahazard;
  }

  public static final SCAIEVNode disaxfence_stall_node = new SCAIEVNode("disaxfence_stall");
  @Override
  public Optional<NodeLogicBuilder> implement(Key nodeKey) {
    // Additional implementation for disaxfence
    if (nodeKey.getPurpose().matches(Purpose.REGULAR) && nodeKey.getNode().equals(disaxfence_stall_node) &&
        allISAXes.containsKey(SCAL.PredefInstr.fence.instr.GetName())) {
      PipelineFront startSpawnStages = core.GetStartSpawnStages();
      PipelineFront spawnStages = core.GetSpawnStages();
      List<PipelineStage> preSpawnStages = startSpawnStages.streamNext_bfs(intermediateStage -> !spawnStages.contains(intermediateStage))
                                               .filter(intermediateStage -> spawnStages.isAroundOrAfter(intermediateStage, false))
                                               .toList();
      List<String> relevantISAXes =
          op_stage_instr.entrySet()
              .stream()
              .filter(op_stage_instr_entry -> {
                // Skip any operation that does not have embedded data hazard handling / that affects the outside world.
                //  -> especially RdMem_spawn and WrMem_spawn
                SCAIEVNode op = op_stage_instr_entry.getKey();
                return op.isSpawn() &&
                    !op.isAdj()
                    // Ignore WrRD unless data hazard handling is disabled.
                    && (!op.equals(bNodes.WrRD_spawn) || !hasWrRD_datahazard)
                    // Assuming "IsUserBNode" ~ is operation on custom register.
                    // TODO: Condition whether data hazard handling is enabled for custom registers (in case there will be a toggle for
                    // that).
                    && !bNodes.IsUserBNode(op);
              })
              .flatMap(op_stage_instr_entry
                       -> op_stage_instr_entry.getValue()
                              .entrySet()
                              .stream()
                              .filter(stage_instrs -> stage_instrs.getKey().getKind() == StageKind.Decoupled)
                              .map(stage_instrs -> stage_instrs.getValue()))
              .flatMap(instrSet -> instrSet.stream())
              .filter(instrName -> allISAXes.containsKey(instrName) && !allISAXes.get(instrName).GetRunsAsDynamicDecoupled())
              .toList();
      RequestedForSet fenceOnlyRequestedFor = new RequestedForSet(SCAL.PredefInstr.fence.instr.GetName());
      RequestedForSet fenceRequestedFor = new RequestedForSet(SCAL.PredefInstr.fence.instr.GetName());
      fenceRequestedFor.addRelevantISAXes(relevantISAXes);
      return Optional.of(NodeLogicBuilder.fromFunction("disaxfence_stall", (registry, aux) -> {
        var ret = new NodeLogicBlock();
        // Build intermediate condition from RdIValid for all stages between 'start spawn' (incl.) and 'spawn' (excl.)
        String fenceActiveCondition =
            startSpawnStages.asList()
                .stream()
                .map(startSpawnStage
                     -> registry.lookupExpressionRequired(
                         new NodeInstanceDesc.Key(bNodes.RdIValid, startSpawnStage, SCAL.PredefInstr.fence.instr.GetName()),
                         fenceRequestedFor))
                .reduce((a, b) -> a + " || " + b)
                .orElse("1'b0");
        String intermediateCondition =
            relevantISAXes.stream()
                .flatMap(
                    isax -> preSpawnStages.stream().map(stage -> Map.entry(stage, isax))) // Get all Intermediate Stage-ISAX combinations
                .map(entry
                     -> registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdIValid, entry.getKey(), entry.getValue()),
                                                          fenceOnlyRequestedFor))
                .reduce((a, b) -> a + " || " + b)
                .orElse("1'b0");
        String spawnActiveCondition =
            relevantISAXes.stream()
                .flatMap(
                    isax -> spawnStages.asList().stream().map(stage -> Map.entry(stage, isax))) // Get all Spawn Stage-ISAX combinations
                .map(entry
                     -> registry.lookupExpressionRequired(new NodeInstanceDesc.Key(bNodes.RdAnyValid, entry.getKey(), entry.getValue()),
                                                          fenceOnlyRequestedFor))
                .reduce((a, b) -> a + " || " + b)
                .orElse("1'b0");
        String wireName = "stall_from_disaxfence_s";
        ret.declarations += String.format("wire %s;\n", wireName);
        ret.logic +=
            String.format("assign %s = %s && (%s || %s);\n", wireName, fenceActiveCondition, intermediateCondition, spawnActiveCondition);
        ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(disaxfence_stall_node, core.GetRootStage(), ""), wireName,
                                             ExpressionType.WireName, fenceOnlyRequestedFor));
        startSpawnStages.asList().forEach(
            startSpawnStage
            -> ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(Purpose.REGULAR, bNodes.WrStall, startSpawnStage, "", aux),
                                                    wireName, ExpressionType.AnyExpression, fenceOnlyRequestedFor)));
        return ret;
      }));
    } else if (nodeKey.getPurpose().matches(Purpose.REGULAR) && nodeKey.getNode().equals(bNodes.RdFence) &&
               allISAXes.containsKey(SCAL.PredefInstr.fence.instr.GetName()) && core.GetStartSpawnStages().contains(nodeKey.getStage()) &&
               nodeKey.getAux() == 0) {
      PipelineFront startSpawnStages = core.GetStartSpawnStages();
      RequestedForSet rdFenceRequestedFor = new RequestedForSet(SCAL.PredefInstr.fence.instr.GetName());
      return Optional.of(NodeLogicBuilder.fromFunction("RdFence_" + nodeKey.getStage().getName(), (registry, aux) -> {
        var ret = new NodeLogicBlock();
        if (!nodeKey.getISAX().isEmpty())
          rdFenceRequestedFor.addRelevantISAX(nodeKey.getISAX());
        String fenceActiveCondition =
            startSpawnStages.asList()
                .stream()
                .map(startSpawnStage
                     -> registry.lookupExpressionRequired(
                         new NodeInstanceDesc.Key(bNodes.RdIValid, startSpawnStage, SCAL.PredefInstr.fence.instr.GetName()),
                         rdFenceRequestedFor))
                .reduce((a, b) -> a + " || " + b)
                .orElse("1'b0");
        ret.outputs.add(new NodeInstanceDesc(new NodeInstanceDesc.Key(bNodes.RdFence, nodeKey.getStage(), nodeKey.getISAX()),
                                             fenceActiveCondition, ExpressionType.WireName, rdFenceRequestedFor));
        return ret;
      }));
    }
    return Optional.empty();
  }
}
