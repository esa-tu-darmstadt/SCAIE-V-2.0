package scaiev.scal.strategy;

import static java.util.Map.entry;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import scaiev.backend.BNode;
import scaiev.backend.SCALBackendAPI.CustomCoreInterface;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAL.RdIValidStageDesc;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.strategy.decoupled.DecoupledDHStrategy;
import scaiev.scal.strategy.decoupled.DecoupledKillStrategy;
import scaiev.scal.strategy.decoupled.DecoupledPipeStrategy;
import scaiev.scal.strategy.decoupled.DecoupledStandardModulesStrategy;
import scaiev.scal.strategy.decoupled.DefaultRdwrInStageStrategy;
import scaiev.scal.strategy.decoupled.DefaultWrCommitStrategy;
import scaiev.scal.strategy.decoupled.SpawnCommittedRdStrategy;
import scaiev.scal.strategy.decoupled.SpawnFenceStrategy;
import scaiev.scal.strategy.decoupled.SpawnFireStrategy;
import scaiev.scal.strategy.decoupled.SpawnOptionalInputFIFOStrategy;
import scaiev.scal.strategy.decoupled.SpawnOrderedMuxStrategy;
import scaiev.scal.strategy.decoupled.SpawnOutputSelectStrategy;
import scaiev.scal.strategy.decoupled.SpawnRdIValidStrategy;
import scaiev.scal.strategy.decoupled.SpawnRegisterStrategy;
import scaiev.scal.strategy.decoupled.SpawnStaticNodePipeStrategy;
import scaiev.scal.strategy.pipeline.NodeRegPipelineStrategy;
import scaiev.scal.strategy.standard.DefaultMemAdjStrategy;
import scaiev.scal.strategy.standard.DefaultRerunStrategy;
import scaiev.scal.strategy.standard.DefaultValidCancelReqStrategy;
import scaiev.scal.strategy.standard.DirectReadNodeStrategy;
import scaiev.scal.strategy.standard.EarlyValidStrategy;
import scaiev.scal.strategy.standard.PipeliningRdIValidStrategy;
import scaiev.scal.strategy.standard.PipeoutRegularStrategy;
import scaiev.scal.strategy.standard.RdIValidStrategy;
import scaiev.scal.strategy.standard.RdInStageValidStrategy;
import scaiev.scal.strategy.standard.SCALInputOutputStrategy;
import scaiev.scal.strategy.standard.StallFlushDeqStrategy;
import scaiev.scal.strategy.standard.ValidMuxStrategy;
import scaiev.scal.strategy.state.SCALStateStrategy;
import scaiev.util.Verilog;

/**
 * Manages a configurable map of builder functions that output either a SingleNodeStrategy or a MultiNodeStrategy.
 * Provides default implementations.
 */
public class StrategyBuilders {
  /** Individual strategy builders. Each function must return either a SingleNodeStrategy or a MultiNodeStrategy.*/
  protected HashMap<UUID, Function<Map<String, Object>, MultiNodeStrategy>> builders = new HashMap<>();

  /**
   * Sets (or overwrites) the builder for the specified UUID.
   * @param uuid
   * @param builder A builder that, given arguments in a String-to-Object map, returns either a SingleNodeStrategy or a MultiNodeStrategy
   *     (cast as Object).
   */
  public void put(UUID uuid, Function<Map<String, Object>, MultiNodeStrategy> builder) { builders.put(uuid, builder); }

  public SingleNodeStrategy buildSingleNodeStrategy(UUID uuid, Map<String, Object> args) {
    Function<Map<String, Object>, MultiNodeStrategy> entry = builders.get(uuid);
    if (entry == null)
      return null;
    Object ret = entry.apply(args);
    if (ret instanceof SingleNodeStrategy)
      return (SingleNodeStrategy)ret;
    throw new IllegalArgumentException("Builder does not output a SingleNodeStrategy");
  }
  public MultiNodeStrategy buildMultiNodeStrategy(UUID uuid, Map<String, Object> args) {
    Function<Map<String, Object>, MultiNodeStrategy> entry = builders.get(uuid);
    if (entry == null)
      return null;
    return entry.apply(args);
  }

  private void putUniqueBuilder(UUID uuid, Function<Map<String, Object>, MultiNodeStrategy> builder) {
    var prevBuilder = builders.put(uuid, builder);
    assert (prevBuilder == null);
  }

  public StrategyBuilders() {
    putUniqueBuilder(UUID_NodeRegPipelineStrategy, (Map<String, Object> args) -> this.default_buildNodeRegPipelineStrategy(args));
    putUniqueBuilder(UUID_DirectReadNodeStrategy, (Map<String, Object> args) -> this.default_buildDirectReadNodeStrategy(args));
    putUniqueBuilder(UUID_EarlyValidStrategy, (Map<String, Object> args) -> this.default_buildEarlyValidStrategy(args));
    putUniqueBuilder(UUID_ValidMuxStrategy, (Map<String, Object> args) -> this.default_buildValidMuxStrategy(args));
    putUniqueBuilder(UUID_StallFlushDeqStrategy, (Map<String, Object> args) -> this.default_buildStallFlushDeqStrategy(args));
    putUniqueBuilder(UUID_RdIValidStrategy, (Map<String, Object> args) -> this.default_buildRdIValidStrategy(args));
    putUniqueBuilder(UUID_RdInStageValidStrategy, (Map<String, Object> args) -> this.default_buildRdInStageValidStrategy(args));
    putUniqueBuilder(UUID_PipeliningRdIValidStrategy, (Map<String, Object> args) -> this.default_buildPipeliningRdIValidStrategy(args));
    putUniqueBuilder(UUID_SCALInputOutputStrategy, (Map<String, Object> args) -> this.default_buildSCALInputOutputStrategy(args));
    putUniqueBuilder(UUID_PipeoutRegularStrategy, (Map<String, Object> args) -> this.default_buildPipeoutRegularStrategy(args));
    putUniqueBuilder(UUID_DefaultMemAdjStrategy, (Map<String, Object> args) -> this.default_buildDefaultMemAdjStrategy(args));
    putUniqueBuilder(UUID_DefaultValidCancelReqStrategy,
                     (Map<String, Object> args) -> this.default_buildDefaultValidCancelReqStrategy(args));
    putUniqueBuilder(UUID_DefaultRerunStrategy, (Map<String, Object> args) -> this.default_buildDefaultRerunStrategy(args));

    putUniqueBuilder(UUID_DecoupledDHStrategy, (Map<String, Object> args) -> this.default_buildDecoupledDHStrategy(args));
    putUniqueBuilder(UUID_DecoupledPipeStrategy, (Map<String, Object> args) -> this.default_buildDecoupledPipeStrategy(args));
    putUniqueBuilder(UUID_DecoupledKillStrategy, (Map<String, Object> args) -> this.default_buildDecoupledKillStrategy(args));
    putUniqueBuilder(UUID_SpawnRdIValidStrategy, (Map<String, Object> args) -> this.default_buildSpawnRdIValidStrategy(args));
    putUniqueBuilder(UUID_SpawnStaticNodePipeStrategy, (Map<String, Object> args) -> this.default_buildSpawnStaticNodePipeStrategy(args));
    putUniqueBuilder(UUID_DecoupledStandardModulesStrategy,
                     (Map<String, Object> args) -> this.default_buildDecoupledStandardModulesStrategy(args));
    putUniqueBuilder(UUID_SpawnCommittedRdStrategy, (Map<String, Object> args) -> this.default_buildSpawnCommittedRdStrategy(args));
    putUniqueBuilder(UUID_SpawnFenceStrategy, (Map<String, Object> args) -> this.default_buildSpawnFenceStrategy(args));
    putUniqueBuilder(UUID_SpawnFireStrategy, (Map<String, Object> args) -> this.default_buildSpawnFireStrategy(args));
    putUniqueBuilder(UUID_SpawnRegisterStrategy, (Map<String, Object> args) -> this.default_buildSpawnRegisterStrategy(args));
    putUniqueBuilder(UUID_SpawnOutputSelectStrategy, (Map<String, Object> args) -> this.default_buildSpawnOutputSelectStrategy(args));
    putUniqueBuilder(UUID_SpawnOptionalInputFIFOStrategy,
                     (Map<String, Object> args) -> this.default_buildSpawnOptionalInputFIFOStrategy(args));

    putUniqueBuilder(UUID_SCALStateStrategy, (Map<String, Object> args) -> this.default_buildSCALStateStrategy(args));
    putUniqueBuilder(UUID_SpawnOrderedMuxStrategy, (Map<String, Object> args) -> this.default_buildSpawnOrderedMuxStrategy(args));
    putUniqueBuilder(UUID_DefaultRdwrInStageStrategy, (Map<String, Object> args) -> this.default_buildDefaultRdwrInStageStrategy(args));
    putUniqueBuilder(UUID_DefaultWrCommitStrategy, (Map<String, Object> args) -> this.default_buildDefaultWrCommitStrategy(args));
  }

  private static UUID uuidFor(String str) { return UUID.nameUUIDFromBytes(StandardCharsets.UTF_8.encode(str).array()); }

  /**
   * UUID for a NodeRegPipelineStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.pipeline.NodeRegPipelineStrategy}
   *  Args:  Verilog language, BNode bNodes, PipelineFront minPipeFront,
   *         boolean zeroOnFlushSrc, boolean zeroOnFlushDest, boolean zeroOnBubble,
   *         Predicate<NodeInstanceDesc.Key> can_pipe, Predicate<NodeInstanceDesc.Key> prefer_direct,
   *         SingleNodeStrategy strategy_instantiateNew
   */
  public static UUID UUID_NodeRegPipelineStrategy = uuidFor("NodeRegPipelineStrategy");
  /**
   * UUID for a DirectReadNodeStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.standard.DirectReadNodeStrategy}
   *  Args: Verilog language, BNode bNodes, Core core
   */
  public static UUID UUID_DirectReadNodeStrategy = uuidFor("DirectReadNodeStrategy");
  /**
   * UUID for a EarlyValidStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.standard.EarlyValidStrategy}
   *  Args: Verilog language, BNode bNodes, Core core,
   *        HashMap<SCAIEVNode,HashMap<PipelineStage,HashSet<String>>> op_stage_instr,
   *        HashMap<String,SCAIEVInstr> allISAXes,
   *        HashMap<SCAIEVNode, PipelineFront> node_earliestStageValid
   */
  public static UUID UUID_EarlyValidStrategy = uuidFor("EarlyValidStrategy");
  /**
   * UUID for a ValidMuxStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.standard.ValidMuxStrategy}
   *  Args: Verilog language, BNode bNodes, Core core,
   *        HashMap<SCAIEVNode,HashMap<PipelineStage,HashSet<String>>> op_stage_instr,
   *        HashMap<String,SCAIEVInstr> allISAXes
   */
  public static UUID UUID_ValidMuxStrategy = uuidFor("ValidMuxStrategy");
  /**
   * UUID for a StallFlushDeqStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.standard.StallFlushDeqStrategy}
   *  Args: Verilog language, BNode bNodes, Core core
   */
  public static UUID UUID_StallFlushDeqStrategy = uuidFor("StallFlushDeqStrategy");
  /**
   * UUID for a RdIValidStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.standard.RdIValidStrategy}
   * Args: Verilog language, BNode bNodes, Core core,
   *       HashMap<String,SCAIEVInstr> allISAXes,
   *       Function<PipelineStage,RdIValidStageDesc> stage_getRdIValidDesc
   */
  public static UUID UUID_RdIValidStrategy = uuidFor("RdIValidStrategy");
  /**
   * UUID for a RdInStageValidStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.standard.RdInStageValidStrategy}
   * Args: Verilog language, BNode bNodes, Core core
   */
  public static UUID UUID_RdInStageValidStrategy = uuidFor("RdInStageValidStrategy");
  /**
   * UUID for a SCALInputOutputStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.standard.SCALInputOutputStrategy}
   *  Args: Verilog language, BNode bNodes
   */
  public static UUID UUID_SCALInputOutputStrategy = uuidFor("SCALInputOutputStrategy");
  /**
   * UUID for a PipeoutRegularStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.standard.PipeoutRegularStrategy}
   * Args: (none)
   */
  public static UUID UUID_PipeoutRegularStrategy = uuidFor("PipeoutRegularStrategy");
  /**
   * UUID for a DefaultMemAdjStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.standard.DefaultMemAdjStrategy}
   * Args: Verilog language, BNode bNodes, Core core
   */
  public static UUID UUID_DefaultMemAdjStrategy = uuidFor("DefaultMemAdjStrategy");
  /**
   * UUID for a DefaultValidCancelReqStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.standard.DefaultValidCancelReqStrategy}
   * Args: Verilog language, BNode bNodes, Core core,
                  HashMap<String,SCAIEVInstr> allISAXes
   */
  public static UUID UUID_DefaultValidCancelReqStrategy = uuidFor("DefaultValidCancelReqStrategy");
  /**
   * UUID for a DefaultRerunStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.standard.DefaultRerunStrategy}
   * Args: Verilog language, BNode bNodes, Core core
   */
  public static UUID UUID_DefaultRerunStrategy = uuidFor("DefaultRerunStrategy");

  /**
   * UUID for a DecoupledDHStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.decoupled.DecoupledDHStrategy}
   * Args: Verilog language, BNode bNodes, Core core,
                  HashMap<SCAIEVNode,HashMap<PipelineStage,HashSet<String>>> op_stage_instr,
                  HashMap<String,SCAIEVInstr> allISAXes
   */
  public static UUID UUID_DecoupledDHStrategy = uuidFor("DecoupledDHStrategy");
  /**
   * UUID for a DecoupledPipeStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.decoupled.DecoupledPipeStrategy}
   * Args: Verilog language, BNode bNodes, Core core,
   *       HashMap<SCAIEVNode,HashMap<PipelineStage,HashSet<String>>> op_stage_instr,
   *       HashMap<SCAIEVNode,HashMap<String,PipelineStage>> spawn_instr_stage,
   *       HashMap<String,SCAIEVInstr> allISAXes,
   *       List<CustomCoreInterface> spawnRDAddrOverrides
   */
  public static UUID UUID_DecoupledPipeStrategy = uuidFor("DecoupledPipeStrategy");
  /**
   * UUID for a DecoupledKillStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.decoupled.DecoupledKillStrategy}
   * Args: Verilog language, BNode bNodes, Core core,
   *       HashMap<String,SCAIEVInstr> allISAXes
   */
  public static UUID UUID_DecoupledKillStrategy = uuidFor("DecoupledKillStrategy");
  /**
   * UUID for a SpawnRdIValidStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.decoupled.SpawnRdIValidStrategy}
   * Args: Verilog language, BNode bNodes, Core core,
   *       HashMap<SCAIEVNode,HashMap<PipelineStage,HashSet<String>>> op_stage_instr,
   *       HashMap<SCAIEVNode,HashMap<String,PipelineStage>> spawn_instr_stage
   *       HashMap<String,SCAIEVInstr> allISAXes
   */
  public static UUID UUID_SpawnRdIValidStrategy = uuidFor("SpawnRdIValidStrategy");
  /**
   * UUID for a SpawnStaticNodePipeStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.decoupled.SpawnStaticNodePipeStrategy}
   * Args: Verilog language, BNode bNodes, Core core,
   *       HashMap<SCAIEVNode,HashMap<String,PipelineStage>> spawn_instr_stage
   *       HashMap<String,SCAIEVInstr> allISAXes
   */
  public static UUID UUID_SpawnStaticNodePipeStrategy = uuidFor("SpawnStaticNodePipeStrategy");
  /**
   * UUID for a DecoupledStandardModulesStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.decoupled.DecoupledStandardModulesStrategy}
   * Args: -
   */
  public static UUID UUID_DecoupledStandardModulesStrategy = uuidFor("DecoupledStandardModulesStrategy");
  /**
   * UUID for a SpawnCommittedRdStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.decoupled.SpawnCommittedRdStrategy}
   * Args: Verilog language, BNode bNodes, Core core
   */
  public static UUID UUID_SpawnCommittedRdStrategy = uuidFor("SpawnCommittedRdStrategy");
  /**
   * UUID for a SpawnFenceStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.decoupled.SpawnFenceStrategy}
   * Args: Verilog language, BNode bNodes, Core core,
   *       HashMap<SCAIEVNode,HashMap<PipelineStage,HashSet<String>>> op_stage_instr,
   *       HashMap<String,SCAIEVInstr> allISAXes,
   *       boolean hasWrRD_datahazard
   */
  public static UUID UUID_SpawnFenceStrategy = uuidFor("SpawnFenceStrategy");
  /**
   * UUID for a SpawnFireStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.decoupled.SpawnFireStrategy}
   * Args: Verilog language, BNode bNodes, Core core,
   *       HashMap<SCAIEVNode,HashMap<PipelineStage,HashSet<String>>> op_stage_instr,
   *       HashMap<String,SCAIEVInstr> allISAXes,
   *       Map<SCAIEVNode,Collection<String>> isaxesSortedByPriority,
   *       Collection<SCAIEVNode> disableSpawnFireStallNodes
   */
  public static UUID UUID_SpawnFireStrategy = uuidFor("SpawnFireStrategy");
  /**
   * UUID for a SpawnRegisterStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.decoupled.SpawnRegisterStrategy}
   * Args: Verilog language, BNode bNodes, Core core,
   *       HashMap<SCAIEVNode,HashMap<PipelineStage,HashSet<String>>> op_stage_instr,
   *       Map<SCAIEVNode,Collection<String>> isaxesSortedByPriority
   */
  public static UUID UUID_SpawnRegisterStrategy = uuidFor("SpawnRegisterStrategy");
  /**
   * UUID for a SpawnOutputSelectStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.decoupled.SpawnOutputSelectStrategy}
   * Args: Verilog language, BNode bNodes, Core core,
   *       HashMap<SCAIEVNode,HashMap<PipelineStage,HashSet<String>>> op_stage_instr,
   *       Map<SCAIEVNode,Collection<String>> isaxesSortedByPriority
   */
  public static UUID UUID_SpawnOutputSelectStrategy = uuidFor("SpawnOutputSelectStrategy");
  /**
   * UUID for a SpawnOptionalInputFIFOStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.decoupled.SpawnOutputSelectStrategy}
   * Args: Verilog language, BNode bNodes, Core core,
   *       HashMap<SCAIEVNode,HashMap<PipelineStage,HashSet<String>>> op_stage_instr,
   *       HashMap<String,SCAIEVInstr> allISAXes,
   *       boolean SETTINGwithInputFIFO
   */
  public static UUID UUID_SpawnOptionalInputFIFOStrategy = uuidFor("SpawnOptionalInputFIFOStrategy");
  /**
   * UUID for a PipeliningRdIValidStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.decoupled.PipeliningRdIValidStrategy}
   * Args: Verilog language, BNode bNodes, Core core,
   *       PipelineFront minPipelineFront,
   *       HashMap<String,SCAIEVInstr> allISAXes,
   *       Function<PipelineStage,RdIValidStageDesc> stage_getRdIValidDesc
   */
  public static UUID UUID_PipeliningRdIValidStrategy = uuidFor("PipeliningRdIValidStrategy");

  /**
   * UUID for a SCALStateStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.state.SCALStateStrategy}
   * Args: Verilog language, BNode bNodes, Core core,
   *       HashMap<SCAIEVNode, HashMap<PipelineStage,HashSet<String>>> op_stage_instr,
   *       HashMap<String, SCAIEVInstr> allISAXes
   */
  public static UUID UUID_SCALStateStrategy = uuidFor("SCALStateStrategy");

  /**
   * UUID for a SpawnOrderedMuxStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.decoupled.SpawnOrderedMuxStrategy}
   * Args: Verilog language, BNode bNodes, Core core,
   *       HashMap<SCAIEVNode,HashMap<PipelineStage,HashSet<String>>> op_stage_instr,
   *       HashMap<SCAIEVNode,HashMap<String,PipelineStage>> spawn_instr_stage,
   *       HashMap<String,SCAIEVInstr> allISAXes,
   *       boolean SETTINGenforceOrdering_Memory_Semicoupled,
   *       boolean SETTINGenforceOrdering_Memory_Decoupled,
   *       boolean SETTINGenforceOrdering_User_Semicoupled,
   *       boolean SETTINGenforceOrdering_User_Decoupled
   */
  public static UUID UUID_SpawnOrderedMuxStrategy = uuidFor("SpawnOrderedMuxStrategy");
  /**
   * UUID for a DefaultRdwrInStageStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.decoupled.DefaultRdwrInStageStrategy}
   * Args: Verilog language, BNode bNodes, Core core
   */
  public static UUID UUID_DefaultRdwrInStageStrategy = uuidFor("DefaultRdwrInStageStrategy");
  /**
   * UUID for a DefaultWrCommitStrategy-compatible implementation.
   * {@link scaiev.scal.strategy.decoupled.DefaultWrCommitStrategy}
   * Args: Verilog language, BNode bNodes, Core core
   */
  public static UUID UUID_DefaultWrCommitStrategy = uuidFor("DefaultWrCommitStrategy");

  /**
   * Helper function to call the builder for NodeRegPipelineStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param minPipeFront The minimum stages to instantiate a pipeline for.
   * @param zeroOnFlushSrc If set, a zero value will be pipelined instead of the input value if the source stage is being flushed.
   * @param zeroOnFlushDest If set, the pipelined value will be set to zero if the destination stage is being flushed.
   * @param zeroOnBubble If set, the signal will be overwritten with zero if the destination stage becomes a bubble (due to source stage
   *     stalling).
   * @param can_pipe The condition to check before instantiating pipeline builders.
   * @param prefer_direct A condition that says whether pipeline instantiation should be done after (true) or before (false) trying direct
   *     generation through strategy_instantiateNew.
   * @param strategy_instantiateNew The strategy to generate a new instance;
   *           if its implement method returns Optional.empty(),
   *           the pipeline builder will mark the prior stage node as mandatory.
   *        Accepts a MultiNodeStrategy, but only used for one node at a time.
   *        Important: The builders returned by a strategy invocation may get combined to a single builder,
   *        and thus cannot rely on seeing each other's outputs in the registry.
   */
  public final MultiNodeStrategy buildNodeRegPipelineStrategy(Verilog language, BNode bNodes, PipelineFront minPipeFront,
                                                              boolean zeroOnFlushSrc, boolean zeroOnFlushDest, boolean zeroOnBubble,
                                                              Predicate<NodeInstanceDesc.Key> can_pipe,
                                                              Predicate<NodeInstanceDesc.Key> prefer_direct,
                                                              MultiNodeStrategy strategy_instantiateNew) {
    return buildMultiNodeStrategy(UUID_NodeRegPipelineStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("minPipeFront", minPipeFront),
                                                entry("zeroOnFlushSrc", zeroOnFlushSrc), entry("zeroOnFlushDest", zeroOnFlushDest),
                                                entry("zeroOnBubble", zeroOnBubble), entry("can_pipe", can_pipe),
                                                entry("prefer_direct", prefer_direct),
                                                entry("strategy_instantiateNew", strategy_instantiateNew)));
  }

  /**
   * Helper function to call the builder for DirectReadNodeStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   */
  public final SingleNodeStrategy buildDirectReadNodeStrategy(Verilog language, BNode bNodes, Core core) {
    return buildSingleNodeStrategy(UUID_DirectReadNodeStrategy,
                                   Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core)));
  }

  /**
   * Helper function to call the builder for EarlyValidStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param allISAXes The ISAX descriptions
   * @param node_earliestStageValid The node attribute that says when the presence of an ISAX using a node should be announced.
   */
  public final MultiNodeStrategy buildEarlyValidStrategy(Verilog language, BNode bNodes, Core core,
                                                         HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                                                         HashMap<String, SCAIEVInstr> allISAXes,
                                                         HashMap<SCAIEVNode, PipelineFront> node_earliestStageValid) {
    return buildMultiNodeStrategy(UUID_EarlyValidStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core),
                                                entry("op_stage_instr", op_stage_instr), entry("allISAXes", allISAXes),
                                                entry("node_earliestStageValid", node_earliestStageValid)));
  }

  /**
   * Helper function to call the builder for ValidMuxStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param allISAXes The ISAX descriptions
   */
  public final SingleNodeStrategy buildValidMuxStrategy(Verilog language, BNode bNodes, Core core,
                                                        HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                                                        HashMap<String, SCAIEVInstr> allISAXes) {
    return buildSingleNodeStrategy(UUID_ValidMuxStrategy,
                                   Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core),
                                                 entry("op_stage_instr", op_stage_instr), entry("allISAXes", allISAXes)));
  }

  /**
   * Helper function to call the builder for RdIValidStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param allISAXes The ISAX descriptions
   * @param stage_getRdIValidDesc A stage mapping providing additional conditional expressions to RdIValid per ISAX
   */
  public final MultiNodeStrategy buildRdIValidStrategy(Verilog language, BNode bNodes, Core core, HashMap<String, SCAIEVInstr> allISAXes,
                                                       Function<PipelineStage, RdIValidStageDesc> stage_getRdIValidDesc) {
    return buildMultiNodeStrategy(UUID_RdIValidStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core),
                                                entry("allISAXes", allISAXes), entry("stage_getRdIValidDesc", stage_getRdIValidDesc)));
  }
  /**
   * Helper function to call the builder for PipeliningRdIValidStrategy.
   * @param strategyBuilders The StrategyBuilders object to build sub-strategies with
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core node description
   * @param minPipeFront The minimum stages to instantiate an RdIValid pipeline for
   * @param allISAXes The ISAX descriptions
   * @param stage_getRdIValidDesc A stage mapping providing additional conditional expressions to RdIValid per ISAX
   */
  public final MultiNodeStrategy buildPipeliningRdIValidStrategy(Verilog language, BNode bNodes, Core core, PipelineFront minPipelineFront,
                                                                 HashMap<String, SCAIEVInstr> allISAXes,
                                                                 Function<PipelineStage, RdIValidStageDesc> stage_getRdIValidDesc) {
    return buildMultiNodeStrategy(UUID_PipeliningRdIValidStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core),
                                                entry("minPipelineFront", minPipelineFront), entry("allISAXes", allISAXes),
                                                entry("stage_getRdIValidDesc", stage_getRdIValidDesc)));
  }

  /**
   * Helper function to call the builder for RdInStageValidStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   */
  public final MultiNodeStrategy buildRdInStageValidStrategy(Verilog language, BNode bNodes, Core core) {
    return buildMultiNodeStrategy(UUID_RdInStageValidStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core)));
  }

  /**
   * Helper function to call the builder for SCALInputOutputStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   */
  public final SingleNodeStrategy buildSCALInputOutputStrategy(Verilog language, BNode bNodes) {
    return buildSingleNodeStrategy(UUID_SCALInputOutputStrategy, Map.ofEntries(entry("language", language), entry("bNodes", bNodes)));
  }

  /**
   * Helper function to call the builder for StallFlushDeqStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   */
  public final SingleNodeStrategy buildStallFlushDeqStrategy(Verilog language, BNode bNodes, Core core) {
    return buildSingleNodeStrategy(UUID_StallFlushDeqStrategy,
                                   Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core)));
  }

  /**
   * Helper function to call the builder for PipeoutRegularStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   */
  public final SingleNodeStrategy buildPipeoutRegularStrategy() {
    return buildSingleNodeStrategy(UUID_PipeoutRegularStrategy, Map.ofEntries());
  }

  /**
   * Helper function to call the builder for DefaultMemAdjStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core node description
   */
  public final MultiNodeStrategy buildDefaultMemAdjStrategy(Verilog language, BNode bNodes, Core core) {
    return buildMultiNodeStrategy(UUID_DefaultMemAdjStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core)));
  }
  /**
   * Helper function to call the builder for DefaultValidCancelReqStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core node description
   * @param allISAXes The ISAX descriptions
   */
  public final SingleNodeStrategy buildDefaultValidCancelReqStrategy(Verilog language, BNode bNodes, Core core,
                                                                     HashMap<String, SCAIEVInstr> allISAXes) {
    return buildSingleNodeStrategy(UUID_DefaultValidCancelReqStrategy, Map.ofEntries(entry("language", language), entry("bNodes", bNodes),
                                                                                     entry("core", core), entry("allISAXes", allISAXes)));
  }
  /**
   * Helper function to call the builder for DefaultRerunStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core node description
   */
  public final SingleNodeStrategy buildDefaultRerunStrategy(Verilog language, BNode bNodes, Core core) {
    return buildSingleNodeStrategy(UUID_DefaultRerunStrategy,
                                   Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core)));
  }

  /**
   * Helper function to call the builder for DecoupledDHStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param allISAXes The ISAX descriptions
   */
  public final MultiNodeStrategy buildDecoupledDHStrategy(Verilog language, BNode bNodes, Core core,
                                                          HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                                                          HashMap<String, SCAIEVInstr> allISAXes) {
    return buildMultiNodeStrategy(UUID_DecoupledDHStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core),
                                                entry("op_stage_instr", op_stage_instr), entry("allISAXes", allISAXes)));
  }

  /**
   * Helper function to call the builder for DecoupledPipeStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param spawn_instr_stage The Node-ISAX-Stage mapping providing the precise sub-pipeline stage for spawn operations
   * @param allISAXes The ISAX descriptions
   * @param spawnRDAddrOverrides Custom SCAL<->Core interfaces that specify the destination register address/ID for ISAXes entering a spawn
   *     stage.
   *                             This could be one interface for the Execute stage (for semi-coupled spawn ISAXes)
   *                              and one for the Decoupled stage (for actual decoupled spawn ISAXes).
   *                             By default, the 'rd' field in the instruction encoding is used.
   */
  public final MultiNodeStrategy buildDecoupledPipeStrategy(Verilog language, BNode bNodes, Core core,
                                                            HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                                                            HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage,
                                                            HashMap<String, SCAIEVInstr> allISAXes,
                                                            List<CustomCoreInterface> spawnRDAddrOverrides) {
    return buildMultiNodeStrategy(UUID_DecoupledPipeStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core),
                                                entry("op_stage_instr", op_stage_instr), entry("spawn_instr_stage", spawn_instr_stage),
                                                entry("allISAXes", allISAXes), entry("spawnRDAddrOverrides", spawnRDAddrOverrides)));
  }
  /**
   * Helper function to call the builder for DecoupledStandardModulesStrategy.
   */
  public final SingleNodeStrategy buildDecoupledStandardModulesStrategy() {
    return buildSingleNodeStrategy(UUID_DecoupledStandardModulesStrategy, Map.ofEntries());
  }
  /**
   * Helper function to call the builder for DecoupledKillStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param allISAXes The ISAX descriptions
   */
  public final MultiNodeStrategy buildDecoupledKillStrategy(Verilog language, BNode bNodes, Core core,
                                                            HashMap<String, SCAIEVInstr> allISAXes) {
    return buildMultiNodeStrategy(UUID_DecoupledKillStrategy, Map.ofEntries(entry("language", language), entry("bNodes", bNodes),
                                                                            entry("core", core), entry("allISAXes", allISAXes)));
  }
  /**
   * Helper function to call the builder for SpawnRdIValidStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param spawn_instr_stage The Node-ISAX-Stage mapping providing the precise sub-pipeline stage for spawn operations
   * @param allISAXes The ISAX descriptions
   */
  public final MultiNodeStrategy buildSpawnRdIValidStrategy(Verilog language, BNode bNodes, Core core,
                                                            HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                                                            HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage,
                                                            HashMap<String, SCAIEVInstr> allISAXes) {
    return buildMultiNodeStrategy(UUID_SpawnRdIValidStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core),
                                                entry("op_stage_instr", op_stage_instr), entry("spawn_instr_stage", spawn_instr_stage),
                                                entry("allISAXes", allISAXes)));
  }
  /**
   * Helper function to call the builder for SpawnStaticNodePipeStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param spawn_instr_stage The Node-ISAX-Stage mapping providing the precise sub-pipeline stage for spawn operations
   * @param allISAXes The ISAX descriptions
   */
  public final MultiNodeStrategy buildSpawnStaticNodePipeStrategy(Verilog language, BNode bNodes, Core core,
                                                                  HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage,
                                                                  HashMap<String, SCAIEVInstr> allISAXes) {
    return buildMultiNodeStrategy(UUID_SpawnStaticNodePipeStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core),
                                                entry("spawn_instr_stage", spawn_instr_stage), entry("allISAXes", allISAXes)));
  }
  /**
   * Helper function to call the builder for SpawnCommittedRdStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   */
  public final MultiNodeStrategy buildSpawnCommittedRdStrategy(Verilog language, BNode bNodes, Core core) {
    return buildMultiNodeStrategy(UUID_SpawnCommittedRdStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core)));
  }
  /**
   * Helper function to call the builder for SpawnFenceStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param allISAXes The ISAX descriptions
   * @param hasWrRD_datahazard If set, ignores WrRD_spawn nodes for fence
   */
  public final MultiNodeStrategy buildSpawnFenceStrategy(Verilog language, BNode bNodes, Core core,
                                                         HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                                                         HashMap<String, SCAIEVInstr> allISAXes, boolean hasWrRD_datahazard) {
    return buildMultiNodeStrategy(UUID_SpawnFenceStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core),
                                                entry("op_stage_instr", op_stage_instr), entry("allISAXes", allISAXes),
                                                entry("hasWrRD_datahazard", hasWrRD_datahazard)));
  }
  /**
   * Helper function to call the builder for SpawnFireStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param allISAXes The ISAX descriptions
   * @param isaxesSortedByPriority For each spawn node, an ISAX name collection sorted by priority
   * @param disableSpawnFireStallNodes Spawn nodes that should stall the core when firing
   */
  public final MultiNodeStrategy buildSpawnFireStrategy(Verilog language, BNode bNodes, Core core,
                                                        HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                                                        HashMap<String, SCAIEVInstr> allISAXes,
                                                        Map<SCAIEVNode, Collection<String>> isaxesSortedByPriority,
                                                        Collection<SCAIEVNode> disableSpawnFireStallNodes) {
    return buildMultiNodeStrategy(UUID_SpawnFireStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core),
                                                entry("op_stage_instr", op_stage_instr), entry("allISAXes", allISAXes),
                                                entry("isaxesSortedByPriority", isaxesSortedByPriority),
                                                entry("disableSpawnFireStallNodes", disableSpawnFireStallNodes)));
  }
  /**
   * Helper function to call the builder for SpawnRegisterStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param isaxesSortedByPriority For each spawn node, an ISAX name collection sorted by priority
   */
  public final SingleNodeStrategy buildSpawnRegisterStrategy(Verilog language, BNode bNodes, Core core,
                                                             HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                                                             Map<SCAIEVNode, Collection<String>> isaxesSortedByPriority) {
    return buildSingleNodeStrategy(UUID_SpawnRegisterStrategy, Map.ofEntries(entry("language", language), entry("bNodes", bNodes),
                                                                             entry("core", core), entry("op_stage_instr", op_stage_instr),
                                                                             entry("isaxesSortedByPriority", isaxesSortedByPriority)));
  }

  /**
   * Helper function to call the builder for SpawnRegisterStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param allISAXes The ISAX descriptions
   */
  public final MultiNodeStrategy buildSCALStateStrategy(Verilog language, BNode bNodes, Core core,
                                                        HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                                                        HashMap<String, SCAIEVInstr> allISAXes) {
    return buildMultiNodeStrategy(UUID_SCALStateStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core),
                                                entry("op_stage_instr", op_stage_instr), entry("allISAXes", allISAXes)));
  }

  /**
   * Helper function to call the builder for SpawnOutputSelectStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param isaxesSortedByPriority For each spawn node, an ISAX name collection sorted by priority
   */
  public final MultiNodeStrategy buildSpawnOutputSelectStrategy(Verilog language, BNode bNodes, Core core,
                                                                HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                                                                Map<SCAIEVNode, Collection<String>> isaxesSortedByPriority) {
    return buildMultiNodeStrategy(UUID_SpawnOutputSelectStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core),
                                                entry("op_stage_instr", op_stage_instr),
                                                entry("isaxesSortedByPriority", isaxesSortedByPriority)));
  }
  /**
   * Helper function to call the builder for SpawnOutputSelectStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param allISAXes The ISAX descriptions
   * @param SETTINGwithInputFIFO Flag if a FIFO should be created rather than a plain assignment
   */
  public final MultiNodeStrategy buildSpawnOptionalInputFIFOStrategy(
      Verilog language, BNode bNodes, Core core, HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
      HashMap<String, SCAIEVInstr> allISAXes, boolean SETTINGwithInputFIFO) {
    return buildMultiNodeStrategy(UUID_SpawnOptionalInputFIFOStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core),
                                                entry("op_stage_instr", op_stage_instr), entry("allISAXes", allISAXes),
                                                entry("SETTINGwithInputFIFO", SETTINGwithInputFIFO)));
  }
  /**
   * Helper function to call the builder for SpawnOrderedMuxStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   * @param op_stage_instr The Node-Stage-ISAX mapping
   * @param spawn_instr_stage The Node-ISAX-Stage mapping providing the precise sub-pipeline stage for spawn operations
   * @param allISAXes The ISAX descriptions
   * @param SETTINGenforceOrdering_Memory_Semicoupled Flag if semi-coupled memory operations should be handled in ISAX issue order
   * @param SETTINGenforceOrdering_Memory_Decoupled Flag if decoupled memory operations should be handled in ISAX issue order
   * @param SETTINGenforceOrdering_User_Semicoupled Flag if semi-coupled user operations should be handled in ISAX issue order
   * @param SETTINGenforceOrdering_User_Decoupled Flag if decoupled user operations should be handled in ISAX issue order
   */
  public final MultiNodeStrategy buildSpawnOrderedMuxStrategy(
      Verilog language, BNode bNodes, Core core, HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
      HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage, HashMap<String, SCAIEVInstr> allISAXes,
      boolean SETTINGenforceOrdering_Memory_Semicoupled, boolean SETTINGenforceOrdering_Memory_Decoupled,
      boolean SETTINGenforceOrdering_User_Semicoupled, boolean SETTINGenforceOrdering_User_Decoupled) {
    return buildMultiNodeStrategy(
        UUID_SpawnOrderedMuxStrategy,
        Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core), entry("op_stage_instr", op_stage_instr),
                      entry("spawn_instr_stage", spawn_instr_stage), entry("allISAXes", allISAXes),
                      entry("SETTINGenforceOrdering_Memory_Semicoupled", SETTINGenforceOrdering_Memory_Semicoupled),
                      entry("SETTINGenforceOrdering_Memory_Decoupled", SETTINGenforceOrdering_Memory_Decoupled),
                      entry("SETTINGenforceOrdering_User_Semicoupled", SETTINGenforceOrdering_User_Semicoupled),
                      entry("SETTINGenforceOrdering_User_Decoupled", SETTINGenforceOrdering_User_Decoupled)));
  }
  /**
   * Helper function to call the builder for DefaultRdwrInStageStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   */
  public final MultiNodeStrategy buildDefaultRdwrInStageStrategy(Verilog language, BNode bNodes, Core core) {
    return buildMultiNodeStrategy(UUID_DefaultRdwrInStageStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core)));
  }
  /**
   * Helper function to call the builder for DefaultWrCommitStrategy.
   * @param language The (Verilog) language object
   * @param bNodes The BNode object for the node instantiation
   * @param core The core nodes description
   */
  public final MultiNodeStrategy buildDefaultWrCommitStrategy(Verilog language, BNode bNodes, Core core) {
    return buildMultiNodeStrategy(UUID_DefaultWrCommitStrategy,
                                  Map.ofEntries(entry("language", language), entry("bNodes", bNodes), entry("core", core)));
  }

  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final MultiNodeStrategy default_buildNodeRegPipelineStrategy(Map<String, Object> args) {
    return new NodeRegPipelineStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (PipelineFront)args.get("minPipeFront"),
                                       (Boolean)args.get("zeroOnFlushSrc"), (Boolean)args.get("zeroOnFlushDest"),
                                       (Boolean)args.get("zeroOnBubble"), (Predicate<NodeInstanceDesc.Key>)args.get("can_pipe"),
                                       (Predicate<NodeInstanceDesc.Key>)args.get("prefer_direct"),
                                       (MultiNodeStrategy)args.get("strategy_instantiateNew"));
  }
  private final SingleNodeStrategy default_buildDirectReadNodeStrategy(Map<String, Object> args) {
    return new DirectReadNodeStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"));
  }
  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final MultiNodeStrategy default_buildEarlyValidStrategy(Map<String, Object> args) {
    return new EarlyValidStrategy(this, (Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
                                  (HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>>)args.get("op_stage_instr"),
                                  (HashMap<String, SCAIEVInstr>)args.get("allISAXes"),
                                  (HashMap<SCAIEVNode, PipelineFront>)args.get("node_earliestStageValid"));
  }
  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final SingleNodeStrategy default_buildValidMuxStrategy(Map<String, Object> args) {
    return new ValidMuxStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
                                (HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>>)args.get("op_stage_instr"),
                                (HashMap<String, SCAIEVInstr>)args.get("allISAXes"));
  }
  private final SingleNodeStrategy default_buildStallFlushDeqStrategy(Map<String, Object> args) {
    return new StallFlushDeqStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"));
  }
  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final MultiNodeStrategy default_buildRdIValidStrategy(Map<String, Object> args) {
    return new RdIValidStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
                                (HashMap<String, SCAIEVInstr>)args.get("allISAXes"),
                                (Function<PipelineStage, RdIValidStageDesc>)args.get("stage_getRdIValidDesc"));
  }
  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final MultiNodeStrategy default_buildPipeliningRdIValidStrategy(Map<String, Object> args) {
    return new PipeliningRdIValidStrategy(this, (Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
                                          (PipelineFront)args.get("minPipelineFront"), (HashMap<String, SCAIEVInstr>)args.get("allISAXes"),
                                          (Function<PipelineStage, RdIValidStageDesc>)args.get("stage_getRdIValidDesc"));
  }
  private final MultiNodeStrategy default_buildRdInStageValidStrategy(Map<String, Object> args) {
    return new RdInStageValidStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"));
  }
  private final SingleNodeStrategy default_buildSCALInputOutputStrategy(Map<String, Object> args) {
    return new SCALInputOutputStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"));
  }
  private final SingleNodeStrategy default_buildPipeoutRegularStrategy(Map<String, Object> args) { return new PipeoutRegularStrategy(); }
  private final MultiNodeStrategy default_buildDefaultMemAdjStrategy(Map<String, Object> args) {
    return new DefaultMemAdjStrategy(this, (Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"));
  }
  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final SingleNodeStrategy default_buildDefaultValidCancelReqStrategy(Map<String, Object> args) {
    return new DefaultValidCancelReqStrategy(this, (Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
                                             (HashMap<String, SCAIEVInstr>)args.get("allISAXes"));
  }
  private final SingleNodeStrategy default_buildDefaultRerunStrategy(Map<String, Object> args) {
    return new DefaultRerunStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"));
  }

  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final MultiNodeStrategy default_buildDecoupledDHStrategy(Map<String, Object> args) {
    return new DecoupledDHStrategy(this, (Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
                                   (HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>>)args.get("op_stage_instr"),
                                   (HashMap<String, SCAIEVInstr>)args.get("allISAXes"));
  }
  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final MultiNodeStrategy default_buildDecoupledPipeStrategy(Map<String, Object> args) {
    return new DecoupledPipeStrategy(this, (Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
                                     (HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>>)args.get("op_stage_instr"),
                                     (HashMap<SCAIEVNode, HashMap<String, PipelineStage>>)args.get("spawn_instr_stage"),
                                     (HashMap<String, SCAIEVInstr>)args.get("allISAXes"),
                                     (List<CustomCoreInterface>)args.get("spawnRDAddrOverrides"));
  }
  @SuppressWarnings("unchecked")
  private final MultiNodeStrategy default_buildDecoupledKillStrategy(Map<String, Object> args) {
    return new DecoupledKillStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
                                     (HashMap<String, SCAIEVInstr>)args.get("allISAXes"));
  }
  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final MultiNodeStrategy default_buildSpawnRdIValidStrategy(Map<String, Object> args) {
    return new SpawnRdIValidStrategy(this, (Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
                                     (HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>>)args.get("op_stage_instr"),
                                     (HashMap<SCAIEVNode, HashMap<String, PipelineStage>>)args.get("spawn_instr_stage"),
                                     (HashMap<String, SCAIEVInstr>)args.get("allISAXes"));
  }
  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final MultiNodeStrategy default_buildSpawnStaticNodePipeStrategy(Map<String, Object> args) {
    return new SpawnStaticNodePipeStrategy(this, (Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
                                           (HashMap<SCAIEVNode, HashMap<String, PipelineStage>>)args.get("spawn_instr_stage"),
                                           (HashMap<String, SCAIEVInstr>)args.get("allISAXes"));
  }
  private final SingleNodeStrategy default_buildDecoupledStandardModulesStrategy(Map<String, Object> args) {
    return new DecoupledStandardModulesStrategy();
  }
  private final MultiNodeStrategy default_buildSpawnCommittedRdStrategy(Map<String, Object> args) {
    return new SpawnCommittedRdStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"));
  }
  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final MultiNodeStrategy default_buildSpawnFenceStrategy(Map<String, Object> args) {
    return new SpawnFenceStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
                                  (HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>>)args.get("op_stage_instr"),
                                  (HashMap<String, SCAIEVInstr>)args.get("allISAXes"), (Boolean)args.get("hasWrRD_datahazard"));
  }
  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final MultiNodeStrategy default_buildSpawnFireStrategy(Map<String, Object> args) {
    return new SpawnFireStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
                                 (HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>>)args.get("op_stage_instr"),
                                 (HashMap<String, SCAIEVInstr>)args.get("allISAXes"),
                                 (Map<SCAIEVNode, Collection<String>>)args.get("isaxesSortedByPriority"),
                                 (Collection<SCAIEVNode>)args.get("disableSpawnFireStallNodes"));
  }
  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final SingleNodeStrategy default_buildSpawnRegisterStrategy(Map<String, Object> args) {
    return new SpawnRegisterStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
                                     (HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>>)args.get("op_stage_instr"),
                                     (Map<SCAIEVNode, Collection<String>>)args.get("isaxesSortedByPriority"));
  }
  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final MultiNodeStrategy default_buildSCALStateStrategy(Map<String, Object> args) {
    return new SCALStateStrategy(this, (Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
                                 (HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>>)args.get("op_stage_instr"),
                                 (HashMap<String, SCAIEVInstr>)args.get("allISAXes"));
  }
  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final MultiNodeStrategy default_buildSpawnOutputSelectStrategy(Map<String, Object> args) {
    return new SpawnOutputSelectStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
                                         (HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>>)args.get("op_stage_instr"),
                                         (Map<SCAIEVNode, Collection<String>>)args.get("isaxesSortedByPriority"));
  }
  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final MultiNodeStrategy default_buildSpawnOptionalInputFIFOStrategy(Map<String, Object> args) {
    return new SpawnOptionalInputFIFOStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
                                              (HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>>)args.get("op_stage_instr"),
                                              (HashMap<String, SCAIEVInstr>)args.get("allISAXes"),
                                              (Boolean)args.get("SETTINGwithInputFIFO"));
  }
  @SuppressWarnings("unchecked") /* Need to rely on the caller */
  private final MultiNodeStrategy default_buildSpawnOrderedMuxStrategy(Map<String, Object> args) {
    return new SpawnOrderedMuxStrategy(
        (Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"),
        (HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>>)args.get("op_stage_instr"),
        (HashMap<SCAIEVNode, HashMap<String, PipelineStage>>)args.get("spawn_instr_stage"),
        (HashMap<String, SCAIEVInstr>)args.get("allISAXes"), (Boolean)args.get("SETTINGenforceOrdering_Memory_Semicoupled"),
        (Boolean)args.get("SETTINGenforceOrdering_Memory_Decoupled"), (Boolean)args.get("SETTINGenforceOrdering_User_Semicoupled"),
        (Boolean)args.get("SETTINGenforceOrdering_User_Decoupled"));
  }
  private final MultiNodeStrategy default_buildDefaultRdwrInStageStrategy(Map<String, Object> args) {
    return new DefaultRdwrInStageStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"));
  }
  private final MultiNodeStrategy default_buildDefaultWrCommitStrategy(Map<String, Object> args) {
    return new DefaultWrCommitStrategy((Verilog)args.get("language"), (BNode)args.get("bNodes"), (Core)args.get("core"));
  }
}
