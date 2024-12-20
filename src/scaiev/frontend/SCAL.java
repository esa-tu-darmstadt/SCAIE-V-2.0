package scaiev.frontend;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.backend.CoreBackend;
import scaiev.backend.SCALBackendAPI;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.scal.EitherOrNodeLogicBuilder;
import scaiev.scal.InterfaceRequestBuilder;
import scaiev.scal.ModuleComposer;
import scaiev.scal.ModuleComposer.NodeBuilderEntry;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.NodeRegistry;
import scaiev.scal.NodeRegistryRO;
import scaiev.scal.SCALPinNet;
import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.SingleNodeStrategy;
import scaiev.scal.strategy.StrategyBuilders;
import scaiev.scal.strategy.decoupled.DecoupledDHStrategy;
import scaiev.scal.strategy.decoupled.SpawnFenceStrategy;
import scaiev.util.FileWriter;
import scaiev.util.Verilog;

/**
 * This class generates logic to be used in all cores, no matter what configuration. Class generates Verilog module
 * @author elada
 *
 */
public class SCAL implements SCALBackendAPI {
  // Public Variables
  public enum DecoupledStrategy { none, withDH, withStall }
  // logging
  protected static final Logger logger = LogManager.getLogger();

  // Private Variables
  private HashMap<String, SCAIEVInstr> ISAXes;
  private HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  private HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage = new HashMap<SCAIEVNode, HashMap<String, PipelineStage>>();
  HashMap<SCAIEVNode, PipelineFront> node_earliestStageValid =
      new HashMap<SCAIEVNode, PipelineFront>(); // Key = node, Value = earliest stage for which a valid must be generated
  private Core core;
  private Verilog myLanguage;
  private ArrayList<NodeLogicBuilder> globalLogicBuilders = new ArrayList<>();
  private static class VirtualBackend extends CoreBackend {
    @Override
    public String getCorePathIn() {
      return "";
    }
    @Override
    public boolean Generate(HashMap<String, SCAIEVInstr> ISAXes,
                            HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr, String extension_name, Core core,
                            String out_path) {
      return false;
    }
  }
  private CoreBackend virtualBackend = new VirtualBackend();
  FileWriter toFile = null;
  public static class RdIValidStageDesc {
    public HashMap<String, CustomCoreInterface> validCond_isax = new HashMap<>(); // key "" if shared across all ISAXes
    public HashSet<String> instructions = new HashSet<>();

    @Override
    public String toString() {
      return " RdIValidStageDesc.instructions = " + instructions + " RdIValidStageDesc.valid_cond_by_isax = " + validCond_isax + " ";
    }
  }
  private HashMap<PipelineStage, RdIValidStageDesc> stage_containsRdIValid = new HashMap<>();
  private HashSet<SCAIEVNode> adjSpawnAllowedNodes = new HashSet<>();
  private HashMap<SCAIEVNode, HashSet<String>> nodePerISAXOverride = new HashMap<>();
  private List<CustomCoreInterface> spawnRDAddrOverrides = new ArrayList<>();

  public BNode BNode = new BNode();

  // Predefined instructions (exp fence, kill)
  public enum PredefInstr {
    /** Cancel all decoupled instructions */
    kill(new SCAIEVInstr("disaxkill", "-------", "110", "0001011", "S")),
    /** Stall pipeline till all decoupled instructions are done */
    fence(new SCAIEVInstr("disaxfence", "-------", "111", "0001011", "S"));
    public final SCAIEVInstr instr;

    private PredefInstr(SCAIEVInstr instr) { this.instr = instr; }
  }

  ////!!!!!!!!!!!!!
  // SCAL Settings
  //!!!!!!!!!!!!!!
  public boolean SETTINGWithScoreboard =
      true; // true = Scoreboard instantiated within SCAL,  false = scoreboard implemented within ISAX by user
  // public boolean SETTINGWithValid = true;       // true = generate shift register for user valid bit within SCAL. False is a setting used
  // by old SCAIEV version, possibly not stable. If false, user generates valid trigger public boolean SETTINGWithAddr = true;		  //
  // true = FIFO for storing dest addr instantiated within SCAL, If false, user must store this info and provide it toghether with result.
  public boolean SETTINGwithInputFIFO = true;   // true =  it may happen that multiple ISAXes commit result, input FIFO to store them
                                                // instantiated within SCAL. false = no input FIFOs instantiated
  private boolean hasCustomWrRDSpawnDH = false; // true = if scoreboard instantiation within SCAL is disabled, we can still assume correct
                                                // data hazard handling due to some core-specific implementation

  public boolean SETTINGenforceOrdering_Memory_Semicoupled =
      true; // true = semi-coupled memory operations should be handled in ISAX issue order
  public boolean SETTINGenforceOrdering_Memory_Decoupled =
      false; // true = decoupled memory operations should be handled in ISAX issue order
  public boolean SETTINGenforceOrdering_User_Semicoupled =
      true;                                                    // true = semi-coupled user operations should be handled in ISAX issue order
  public boolean SETTINGenforceOrdering_User_Decoupled = true; // true = decoupled user operations should be handled in ISAX issue order

  public HashSet<String> SETTINGdisableSpawnFireStall_families = new HashSet<>();
  public List<CustomCoreInterface> customCoreInterfaces = new ArrayList<>();
  public HashMap<String, SCALPinNet> netlist = new HashMap<>(); // Nets by scal_module_pin
  private HashMap<NodeInstanceDesc.Key, NodeInstanceDesc.Key> renamedISAXInterfacePins = new HashMap<>();

  public void SetSCAL(boolean nonDecWithDH, boolean decWithValid, boolean decWithAddr, boolean decWithInpFIFO) {

    this.SETTINGWithScoreboard = nonDecWithDH;
    // this.SETTINGWithValid = decWithValid;
    // this.SETTINGWithAddr = decWithAddr;
    this.SETTINGwithInputFIFO = decWithInpFIFO;
  }

  public void AddISAXInterfacePinRenames(Iterable<Map.Entry<NodeInstanceDesc.Key, NodeInstanceDesc.Key>> renames) {
    for (var rename : renames)
      renamedISAXInterfacePins.put(rename.getKey(), rename.getValue());
  }

  /**
   * SCALBackendAPI impl: Register a valid condition (with a stage and an optional ISAX name) to apply for all matching RdIValid outputs.
   * @param valid_signal_from_core signal from the core with a set stage and an optional ISAX name ("" to apply to all ISAXes)
   */
  @Override
  public void RegisterRdIValid_valid(CustomCoreInterface valid_signal_from_core) {
    PipelineStage stage = valid_signal_from_core.stage;
    customCoreInterfaces.add(valid_signal_from_core);
    RdIValidStageDesc stageDesc = stage_containsRdIValid.get(stage);
    if (stageDesc == null) {
      stageDesc = new RdIValidStageDesc();
      stage_containsRdIValid.put(stage, stageDesc);
    }
    stageDesc.validCond_isax.put(valid_signal_from_core.instr_name, valid_signal_from_core);
  }
  /**
   * SCALBackendAPI impl: Add an override to the WrRD_spawn destination address signal (default: take from the `rd` instruction field).
   * @param addr_signal_from_core signal from the core with a set stage; ISAX name is unused.
   */
  @Override
  public void OverrideSpawnRDAddr(CustomCoreInterface addr_signal_from_core) {
    customCoreInterfaces.add(addr_signal_from_core);
    spawnRDAddrOverrides.add(addr_signal_from_core);
  }
  /**
   * SCALBackendAPI impl: Request SCAL to generate a to-core interface pin.
   */
  @Override
  public void RequestToCorePin(SCAIEVNode node, PipelineStage stage, String instruction) {
    customCoreInterfaces.add(new CustomCoreInterface(node.name, "wire", stage, node.size, false, instruction));
  }

  /**
   * SCALBackendAPI impl: Register a spawnAllowed adjacent node to SCAL.
   * @param spawnAllowed Adjacent spawnAllowed node
   */
  @Override
  public void SetHasAdjSpawnAllowed(SCAIEVNode spawnAllowed) {
    SCAIEVNode parentNode = BNode.GetSCAIEVNode(spawnAllowed.nameParentNode);
    if (parentNode != null && !parentNode.nameCousinNode.isEmpty()) {
      spawnAllowed = new SCAIEVNode(spawnAllowed.replaceRadixNameWith(parentNode.familyName), spawnAllowed.size, spawnAllowed.isInput);
    }
    adjSpawnAllowedNodes.add(spawnAllowed);
  }

  /**
   * SCALBackendAPI impl: Register an override of an otherwise shared read operation for a particular ISAX.
   * Does not work for operations with adjacent nodes, and does not work for RdFlush.
   * @param node operation node
   * @param instruction ISAX to apply the override to
   */
  @Override
  public void RegisterNodeOverrideForISAX(SCAIEVNode node, String instruction) {
    HashSet<String> isaxSet = nodePerISAXOverride.get(node);
    if (isaxSet == null) {
      isaxSet = new HashSet<String>();
      nodePerISAXOverride.put(node, isaxSet);
    }
    isaxSet.add(instruction);
  }

  /**
   * SCALBackendAPI impl: Prevent instantiation of SCAL's data hazard unit, while retaining the optional address FIFOs and valid shiftregs
   * for WrRD_spawn.
   */
  @Override
  public void SetUseCustomSpawnDH() {
    SETTINGWithScoreboard = false;
    hasCustomWrRDSpawnDH = true;
  }

  protected List<SCAIEVNode> disableSpawnFireStallNodes = new ArrayList<>();

  /**
   * Prevent SCAL from stalling the core if a given type of spawn operation (by family name) is to be committed.
   *  -> The core backend may handle collisions between in-pipeline and spawn operations in a different way.
   *  -> If the core backend injects a writeback into the instruction pipeline, stalling the pipeline until completion could
   * lead to a deadlock.
   * @param spawn_node Node whose family do not need stalls on commit
   */
  @Override
  public void DisableStallForSpawnCommit(SCAIEVNode spawn_node) {
    assert (!spawn_node.isAdj());
    assert (spawn_node.isSpawn());
    disableSpawnFireStallNodes.add(spawn_node);
    // SETTINGdisableSpawnFireStall_families.add(spawn_node.nameCousinNode.isEmpty() ? spawn_node.name : spawn_node.familyName);
  }

  protected StrategyBuilders strategyBuilders = new StrategyBuilders();

  /**
   * Initialize SCAL, prepare for SCALBackendAPI calls.
   *
   */
  public void Prepare(HashMap<String, SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                      HashMap<SCAIEVNode, HashMap<String, PipelineStage>> spawn_instr_stage, Core core) {
    this.ISAXes = ISAXes;

    this.op_stage_instr = op_stage_instr;

    this.spawn_instr_stage = spawn_instr_stage;
    this.core = core;

    // Populate virtual core
    PopulateVirtualCore();
    this.myLanguage = new Verilog(BNode, new FileWriter(""), virtualBackend); // core needed for verification purposes

    this.strategyBuilders = new StrategyBuilders();

    // Special handling for non-decoupled spawn operations.
    //-> GenerateAllInterfToISAX handles renaming of the ISAX interface stage (being bound to the 'decoupled' stage)
    List<NodeInstanceDesc.Key> semicoupledSpawnOps =
        this.op_stage_instr.entrySet()
            .stream()
            .filter(op_stages_entry -> op_stages_entry.getKey().isSpawn()) // Only look at spawn operations...
            .flatMap(op_stages_entry -> { // Expand the stream for each entry in the operation's stage&isaxes sub-map
              return op_stages_entry.getValue()
                  .entrySet()
                  .stream()
                  .filter(stage_isaxes_entry
                          -> stage_isaxes_entry.getKey().getKind() == StageKind.Core) // Only look at Core stages (i.e. non-decoupled)...
                  .flatMap(stage_isaxes_entry -> { // Expand the stream for each ISAX, generating the actual keys.
                    return stage_isaxes_entry.getValue().stream().map(
                        isax -> new NodeInstanceDesc.Key(op_stages_entry.getKey(), stage_isaxes_entry.getKey(), isax));
                  });
            })
            .collect(Collectors.toCollection(ArrayList::new));
    // a) Add non-spawn and non-isax op_stage_instr entries from semicoupledSpawnOps for the SCAL-to-core interface.
    // b) Add WrInStageID to op_stage_instr.
    for (NodeInstanceDesc.Key semicoupledSpawnOp : semicoupledSpawnOps) {
      SCAIEVNode operationSpawn = semicoupledSpawnOp.getNode();
      SCAIEVNode operationSpawnBase = operationSpawn.isAdj() ? BNode.GetSCAIEVNode(operationSpawn.nameParentNode) : operationSpawn;
      SCAIEVNode operationNonSpawnBase = BNode.GetSCAIEVNode(operationSpawnBase.nameParentNode);
      if (operationNonSpawnBase.name.isEmpty()) {
        logger.error("Unable to get the non-spawn base operation of {}. Ignoring operation.", operationSpawn.name);
        continue;
      }
      var operationNonSpawn_opt = operationSpawn.isAdj() ? BNode.GetAdjSCAIEVNode(operationNonSpawnBase, operationSpawn.getAdj())
                                                         : Optional.of(operationNonSpawnBase);
      if (operationNonSpawn_opt.isPresent() && !operationNonSpawn_opt.get().equals(BNode.WrCommit))
        AddIn_op_stage_instr(operationNonSpawn_opt.get(), semicoupledSpawnOp.getStage(), "");
      AddIn_op_stage_instr(BNode.WrInStageID, semicoupledSpawnOp.getStage(), "");
    }
  }

  @Override
  public void OverrideEarliestValid(SCAIEVNode node, PipelineFront stage_for_valid) {
    this.node_earliestStageValid.put(node, stage_for_valid);
  }

  /**
   * SCALBackendAPI impl:
   * Adds a custom SCAL->Core interface pin. The builder should provide an output expression for interfacePin.makeKey(Purpose.REGULAR).
   * @param interfacePin
   * @param builder
   */
  @Override
  public void AddCustomToCorePinUsing(CustomCoreInterface interfacePin, NodeLogicBuilder builder) {
    if (interfacePin.isInputToSCAL)
      throw new IllegalArgumentException("interfacePin.isInputToSCAL is unexpected: pin must be an output from SCAL");
    this.customCoreInterfaces.add(interfacePin);
    NodeInstanceDesc.Key interfaceKey = interfacePin.makeKey(Purpose.REGULAR);
    globalLogicBuilders.add(new EitherOrNodeLogicBuilder(
        "CustomToCorePin:" + builder.toString(), builder,
        NodeLogicBuilder.fromFunction("CutomToCorePin_" + interfaceKey.toString(false), registry -> {
          var ret = new NodeLogicBlock();
          // Add a placeholder to prevent other strategies.
          ret.outputs.add(new NodeInstanceDesc(interfaceKey, "MISSING_" + interfaceKey.toString(), ExpressionType.AnyExpression));
          return ret;
        })));
  }
  /**
   * SCALBackendAPI impl:
   * Adds a custom Core->SCAL interface pin that will be visible to SCAL logic generation.
   * @param interfacePin
   */
  @Override
  public void AddCustomToSCALPin(CustomCoreInterface interfacePin) {
    if (!interfacePin.isInputToSCAL)
      throw new IllegalArgumentException("interfacePin.isInputToSCAL is unexpected: pin must be an input to SCAL");
    this.customCoreInterfaces.add(interfacePin);
  }

  /**
   * SCALBackendAPI impl:
   * Retrieves the StrategyBuilders object of SCAL.
   * The caller is allowed to use {@link StrategyBuilders#put(java.util.UUID, java.util.function.Function)}.
   */
  @Override
  public StrategyBuilders getStrategyBuilders() {
    return strategyBuilders;
  }

  /**
   * Generate module to contain common logic. Common = the same across multiple cores
   *
   */
  public void Generate(String inPath, String outPath, Optional<CoreBackend> coreBackend) {
    logger.info("Generating SCAL for core: " + core.GetName());
    this.toFile = new FileWriter(inPath);
    this.myLanguage = new Verilog(BNode, toFile, virtualBackend); // core needed for verification purposes
    myLanguage.BNode = BNode;
    String interfToISAX = "", interfToCore = "", declarations = "", logic = "", otherModules = "";

    ModuleComposer composer = new ModuleComposer(true, true); // TODO: Disable build log initially

    NodeRegistry globalNodeRegistry = new NodeRegistry();

    // Populate virtual core
    PopulateVirtualCore();

    //////////////////////  GENERATE INTERFACE & DEFAULT/FRWRD LOGIC /////////////////
    // Go through required operations/nodes

    // Temporary set used to prevent duplicate interface pins.
    var interfaceToISAXSet = new HashSet<String>();
    var interfaceToCoreSet = new HashSet<String>();

    for (SCAIEVNode operation : this.op_stage_instr.keySet().stream().collect(Collectors.toUnmodifiableList())) {
      // Go through stages...
      Collection<PipelineStage> stages = new LinkedHashSet<>(this.op_stage_instr.get(operation).keySet());
      // For nodes that require sigs also in earlier stages
      PipelineFront latestNodeStage = core.TranslateStageScheduleNumber(operation.commitStage);
      // int latestNodeStage = core.maxStage;
      // if(operation.commitStage !=0)
      //	latestNodeStage = operation.commitStage;
      Iterator<PipelineStage> stageForValidPipeline_it =
          node_earliestStageValid.getOrDefault(operation, new PipelineFront())
              .asList()
              .stream()
              // Make a Stream of all stages from the requested earliest stage front up until the latest stage.
              .flatMap(frontStage -> frontStage.streamNext_bfs())
              .filter(stage -> stage.getKind() == StageKind.Core && latestNodeStage.isAroundOrAfter(stage, false))
              .iterator();
      for (; stageForValidPipeline_it.hasNext();) {
        PipelineStage stageForValidPipeline = stageForValidPipeline_it.next();
        if (!stages.contains(stageForValidPipeline))
          GenerateAllInterfToCore(operation, stageForValidPipeline, interfaceToCoreSet, globalNodeRegistry, globalLogicBuilders);
      }
      // For main user nodes
      for (PipelineStage stage : stages) {
        assert (ContainsOpInStage(operation, stage));
        Iterator<String> instrIter = op_stage_instr.get(operation).getOrDefault(stage, emptyStrHashSet).iterator();
        // Generate interface for main node if output from ISAX
        GenerateAllInterfToISAX(operation, stage, instrIter, interfaceToISAXSet, globalNodeRegistry, globalLogicBuilders);
      }
      if (!operation.isSpawn() && core.GetNodes().containsKey(operation)) {
        // The ISAX may contain certain operations before the earliest allowed core stage (generally writes) / after the latest allowed one
        // (generally reads).
        //  After having processed the ISAX interfaces, move those operations to the earliest/latest allowed stage,
        //  which will in turn force pipeline instantiation.
        PipelineFront coreOpEarliestFront = BNode.IsUserBNode(operation)
                                                ? new PipelineFront(core.GetRootStage().getChildren())
                                                : core.TranslateStageScheduleNumber(core.GetNodes().get(operation).GetEarliest());
        PipelineFront coreOpLatestFront = BNode.IsUserBNode(operation)
                                              ? new PipelineFront(core.GetRootStage().getChildrenTails())
                                              : core.TranslateStageScheduleNumber(core.GetNodes().get(operation).GetLatest());
        stages = new ArrayList<>(this.op_stage_instr.get(operation).keySet());
        var stagesIter = stages.iterator();
        while (stagesIter.hasNext()) {
          PipelineStage stage = stagesIter.next();
          HashSet<String> instructionsSet = op_stage_instr.get(operation).get(stage);
          if (instructionsSet.isEmpty())
            continue;
          if (!coreOpEarliestFront.isAroundOrBefore(stage, false)) {
            for (PipelineStage stageInEarliest : coreOpEarliestFront.asList())
              if (new PipelineFront(stageInEarliest).isAfter(stage, false)) {
                op_stage_instr.get(operation).computeIfAbsent(stageInEarliest, stage_ -> new HashSet<>(List.of("")));
              }
            stagesIter.remove();
            // op_stage_instr.get(operation).put(stage, new HashSet<>());
          } else if (!coreOpLatestFront.isAroundOrAfter(stage, false)) {
            for (PipelineStage stageInLatest : coreOpLatestFront.asList())
              if (new PipelineFront(stageInLatest).isBefore(stage, false)) {
                op_stage_instr.get(operation).computeIfAbsent(stageInLatest, stage_ -> new HashSet<>(List.of("")));
              }
            stagesIter.remove();
            // op_stage_instr.get(operation).put(stage, new HashSet<>());
          }
        }
      }
      for (PipelineStage stage : stages) {
        boolean isSemicoupledSpawn = operation.isSpawn() && stage.getKind() == StageKind.Core;
        if (!isSemicoupledSpawn && stage.getKind() != StageKind.Sub /*semi-coupled spawn not visible to core as such*/)
          GenerateAllInterfToCore(operation, stage, interfaceToCoreSet, globalNodeRegistry, globalLogicBuilders);
      }
    }
    // Add custom interfaces between Core and SCAL.
    for (CustomCoreInterface intf : this.customCoreInterfaces) {
      SCAIEVNode customNode = new SCAIEVNode(intf.name, intf.size, !intf.isInputToSCAL);
      NodeInstanceDesc.Key key = new NodeInstanceDesc.Key(customNode, intf.stage, intf.instr_name);
      String newinterf =
          myLanguage.CreateTextInterface(customNode.name, intf.stage, intf.instr_name, intf.isInputToSCAL, customNode.size, intf.dataT);
      if (interfaceToCoreSet.add(newinterf)) {
        if (intf.isInputToSCAL) {
          globalLogicBuilders.add(new InterfaceRequestBuilder(Purpose.MARKER_FROMCORE_PIN, key));
        } else {
          globalLogicBuilders.add(new InterfaceRequestBuilder(Purpose.MARKER_TOCORE_PIN, key));
        }
      }
    }
    List<String> customRegNames =
        op_stage_instr.keySet()
            .stream()
            .filter(node -> BNode.IsUserBNode(node))
            .map(node
                 -> node.familyName.startsWith("Wr") || node.familyName.startsWith("Rd") ? node.familyName.substring(2) : node.familyName)
            .distinct()
            .toList();
    for (String customRegName : customRegNames) {
      Optional<SCAIEVNode> readNode_opt = BNode.GetSCAIEVNode_opt(FNode.rdPrefix + customRegName);
      Optional<SCAIEVNode> writeNode_opt = BNode.GetSCAIEVNode_opt(FNode.wrPrefix + customRegName);

      if (readNode_opt.isPresent()) {
        NodeInstanceDesc.Key rdMarkerKey = new NodeInstanceDesc.Key(Purpose.MARKER_CUSTOM_REG, readNode_opt.get(), core.GetRootStage(), "");
        NodeLogicBuilder rdBuilder = NodeLogicBuilder.fromFunction("customRegBuilder_" + rdMarkerKey.toString(false), registry -> {
          // explicitly request output
          registry.lookupExpressionRequired(rdMarkerKey);
          return new NodeLogicBlock();
        });
        globalLogicBuilders.add(rdBuilder);
      }
      if (writeNode_opt.isPresent()) {
        NodeInstanceDesc.Key wrMarkerKey =
            new NodeInstanceDesc.Key(Purpose.MARKER_CUSTOM_REG, writeNode_opt.get(), core.GetRootStage(), "");
        NodeLogicBuilder wrBuilder = NodeLogicBuilder.fromFunction("customRegBuilder_" + wrMarkerKey.toString(false), registry -> {
          // explicitly request output
          registry.lookupExpressionRequired(wrMarkerKey);
          return new NodeLogicBlock();
        });
        globalLogicBuilders.add(wrBuilder);
      }
    }

    PipelineFront generalMinPipelineFront = new PipelineFront(core.GetRootStage().getChildrenByStagePos(1));

    // Strategy to use for RdIValid nodes.
    MultiNodeStrategy ivalidStrategy = strategyBuilders.buildPipeliningRdIValidStrategy(myLanguage, BNode, core, generalMinPipelineFront,
                                                                                        ISAXes, stage -> stage_containsRdIValid.get(stage));
    MultiNodeStrategy rdInStageValidStrategy = strategyBuilders.buildRdInStageValidStrategy(myLanguage, BNode, core);

    // Strategy to request additional read nodes, to be used as part of other strategies.
    SingleNodeStrategy readNodeStrategy_direct = strategyBuilders.buildDirectReadNodeStrategy(myLanguage, BNode, core);

    // For RdStall, RdFlush. Assuming '0' where not provided by the core backend.
    SingleNodeStrategy readNodeStrategy_corePipeStatus = new SingleNodeStrategy() {
      @Override
      public Optional<NodeLogicBuilder> implement(Key nodeKey) {
        if (!nodeKey.getPurpose().matches(NodeInstanceDesc.Purpose.REGULAR))
          return Optional.empty();
        if (!nodeKey.getNode().equals(BNode.RdStall) && !nodeKey.getNode().equals(BNode.RdFlush))
          return Optional.empty();
        Optional<NodeLogicBuilder> directBuilder = readNodeStrategy_direct.implement(nodeKey);
        if (directBuilder.isPresent())
          return directBuilder;

        return Optional.of(NodeLogicBuilder.fromFunction("defaultCorePipeZero_" + nodeKey.toString(false), (NodeRegistryRO registry) -> {
          NodeLogicBlock ret = new NodeLogicBlock();
          ret.outputs.add(new NodeInstanceDesc(nodeKey, "1'b0", ExpressionType.AnyExpression));
          return ret;
        }));
      }
    };

    // For RdMem, RdRS*, RdInstr, etc.
    MultiNodeStrategy readNodeStrategy_pipelineable = strategyBuilders.buildNodeRegPipelineStrategy(
        this.myLanguage, BNode, generalMinPipelineFront, false, false, false, // No need zeroing non-control data registers
        key
        -> {
          PipelineStage stage = key.getStage();
          while (stage.getKind() == StageKind.Sub) {
            stage = stage.getParent().get();
          }
          boolean staticReadResultOnly = (stage.getKind() == StageKind.Decoupled);
          if (stage.getKind() != StageKind.Core && stage.getKind() != StageKind.CoreInternal && stage.getKind() != StageKind.Decoupled)
            return false;
          if (!key.getPurpose().matches(Purpose.PIPEDIN))
            return false;
          if (BNode.IsUserBNode(key.getNode()))
            return false;
          SCAIEVNode baseNode = key.getNode().isAdj() ? BNode.GetSCAIEVNode(key.getNode().nameParentNode) : key.getNode();
          PipelineStage stage_ = stage;
          return (key.getNode().tags.contains(NodeTypeTag.staticReadResult) ||
                  !staticReadResultOnly && key.getNode().tags.contains(NodeTypeTag.nonStaticReadResult)) &&
              Optional
                  .ofNullable(this.core.GetNodes().get(baseNode)) /* If the node exists in the core... */
                  .map(coreNode
                       -> core.TranslateStageScheduleNumber(coreNode.GetEarliest())
                              .isAroundOrBefore(stage_, false)) /* ...and is available in the given stage */
                  .orElse(false);
        },
        // Prefer direct generation in the given stage over pipelining...
        key
        -> Optional.ofNullable(this.core.GetNodes().get(key.getNode())) /* ...if the node exists in the core... */
                   .map(coreNode
                        -> core.TranslateStageScheduleNumber(coreNode.GetExpensive())
                               .isAfter(key.getStage(), false)) /* ...and is not expensive in the given stage */
                   .orElse(false) &&
               key.getStage().getKind() == StageKind.Core && !BNode.IsUserBNode(key.getNode()),
        MultiNodeStrategy.filter(readNodeStrategy_direct, key -> !BNode.IsUserBNode(key.getNode())));

    // Step 1: Generate Valid bits for earlier stages. For exp for mem operations, core needs to know if it.s a mem op before mem stage
    MultiNodeStrategy writeNodeEarlyValidStrategy =
        strategyBuilders.buildEarlyValidStrategy(myLanguage, BNode, core, op_stage_instr, ISAXes, node_earliestStageValid);

    // Step 2: generate Valid bits for all other nodes

    MultiNodeStrategy scalStateStrategy = strategyBuilders.buildSCALStateStrategy(myLanguage, BNode, core, op_stage_instr, ISAXes);

    SingleNodeStrategy normalValidBitStrategy = strategyBuilders.buildValidMuxStrategy(myLanguage, BNode, core, op_stage_instr, ISAXes);

    // Pipeline nodes that the ISAX provides before the core can handle them.
    MultiNodeStrategy pipelinedOpStrategy =
        strategyBuilders.buildNodeRegPipelineStrategy(myLanguage, BNode, generalMinPipelineFront, false, false, false, key -> {
          if (!key.getPurpose().matches(Purpose.PIPEDIN) || key.getISAX().isEmpty() || !ISAXes.containsKey(key.getISAX()))
            return false;

          SCAIEVNode baseNode = (key.getNode().isAdj() && !core.GetNodes().containsKey(key.getNode()))
                                    ? BNode.GetSCAIEVNode(key.getNode().nameParentNode)
                                    : key.getNode();
          if (!core.GetNodes().containsKey(baseNode))
            return false;
          PipelineFront baseNodeEarliest = core.GetNodes().containsKey(baseNode)
                                               ? core.TranslateStageScheduleNumber(core.GetNodes().get(baseNode).GetEarliest())
                                               : new PipelineFront();
          // return op_stage_
          return ISAXes.get(key.getISAX())
              .HasSchedWith(baseNode,
                            sched
                            -> core.TranslateStageScheduleNumber(sched.GetStartCycle())
                                   .asList()
                                   .stream()
                                   .allMatch(schedStartStage -> baseNodeEarliest.isAfter(schedStartStage, false)));
        }, key_ -> false, MultiNodeStrategy.noneStrategy);

    //////////////////////////  WrStall, RdStall //////////////////////////
    SingleNodeStrategy rdwrStallFlushDeqStrategy = strategyBuilders.buildStallFlushDeqStrategy(myLanguage, BNode, core);
    SingleNodeStrategy SCALInputOutputStrategy = strategyBuilders.buildSCALInputOutputStrategy(myLanguage, BNode);
    SingleNodeStrategy pipeoutRegularStrategy = strategyBuilders.buildPipeoutRegularStrategy();
    MultiNodeStrategy defaultMemAdjStrategy = strategyBuilders.buildDefaultMemAdjStrategy(myLanguage, BNode, core);
    SingleNodeStrategy defaultValidCancelReqStrategy = strategyBuilders.buildDefaultValidCancelReqStrategy(myLanguage, BNode, core, ISAXes);
    SingleNodeStrategy defaultRerunStrategy = strategyBuilders.buildDefaultRerunStrategy(myLanguage, BNode, core);

    //////////////////////////  Spawn/Decoupled //////////////////////////
    Map<SCAIEVNode, Collection<String>> isaxPriorities = new HashMap<>();
    for (SCAIEVNode node : this.spawn_instr_stage.keySet()) {
      if (this.core.GetSpawnStages().asList().stream().anyMatch(spawnStage -> this.ContainsOpInStage(node, spawnStage)) &&
          node.allowMultipleSpawn) {
        // Set basic priority.
        // TODO: Make configurable! Currently, the priority is only based on HashMap ordering, which is rather chaotic.
        isaxPriorities.put(node, new ArrayList<>(this.spawn_instr_stage.get(node).keySet()));
      }
    }
    if (SETTINGWithScoreboard) {
      // Request Data Hazard logic for relevant decoupled nodes.
      for (var op_stage_instr_entry : op_stage_instr.entrySet()) {
        SCAIEVNode op = op_stage_instr_entry.getKey();
        if (op.isSpawn() && !op.isAdj() && op.DH) {
          for (var stage_instr_entry : op_stage_instr_entry.getValue().entrySet())
            if (stage_instr_entry.getKey().getKind() == StageKind.Decoupled) {
              PipelineStage stage = stage_instr_entry.getKey();
              // Add a pseudo builder to trigger decoupledDHStrategy.
              globalLogicBuilders.add(NodeLogicBuilder.fromFunction("RequestDH_" + op.name + "_" + stage.getName(), registry -> {
                registry.lookupExpressionRequired(new NodeInstanceDesc.Key(DecoupledDHStrategy.purpose_MARKER_BUILT_DH, op, stage, ""));
                return new NodeLogicBlock();
              }));
            }
        }
      }
    }

    SingleNodeStrategy decoupledStandardModulesStrategy = strategyBuilders.buildDecoupledStandardModulesStrategy();
    MultiNodeStrategy decoupledDHStrategy = strategyBuilders.buildDecoupledDHStrategy(myLanguage, BNode, core, op_stage_instr, ISAXes);
    MultiNodeStrategy decoupledPipeStrategy = strategyBuilders.buildDecoupledPipeStrategy(myLanguage, BNode, core, op_stage_instr,
                                                                                          spawn_instr_stage, ISAXes, spawnRDAddrOverrides);
    MultiNodeStrategy decoupledKillStrategy = strategyBuilders.buildDecoupledKillStrategy(myLanguage, BNode, core, ISAXes);
    MultiNodeStrategy spawnRdIValidStrategy =
        strategyBuilders.buildSpawnRdIValidStrategy(myLanguage, BNode, core, op_stage_instr, spawn_instr_stage, ISAXes);
    MultiNodeStrategy spawnStaticNodePipeStrategy =
        strategyBuilders.buildSpawnStaticNodePipeStrategy(myLanguage, BNode, core, spawn_instr_stage, ISAXes);
    MultiNodeStrategy spawnCommittedRdStrategy = strategyBuilders.buildSpawnCommittedRdStrategy(myLanguage, BNode, core);
    MultiNodeStrategy spawnOrderedMuxStrategy = strategyBuilders.buildSpawnOrderedMuxStrategy(
        myLanguage, BNode, core, op_stage_instr, spawn_instr_stage, ISAXes, SETTINGenforceOrdering_Memory_Semicoupled,
        SETTINGenforceOrdering_Memory_Decoupled, SETTINGenforceOrdering_User_Semicoupled, SETTINGenforceOrdering_User_Decoupled);
    MultiNodeStrategy spawnFireStrategy = strategyBuilders.buildSpawnFireStrategy(myLanguage, BNode, core, op_stage_instr, ISAXes,
                                                                                  isaxPriorities, disableSpawnFireStallNodes);
    SingleNodeStrategy spawnRegisterStrategy =
        strategyBuilders.buildSpawnRegisterStrategy(myLanguage, BNode, core, op_stage_instr, isaxPriorities);
    MultiNodeStrategy spawnOutputSelectStrategy =
        strategyBuilders.buildSpawnOutputSelectStrategy(myLanguage, BNode, core, op_stage_instr, isaxPriorities);
    MultiNodeStrategy spawnOptionalInputFIFOStrategy =
        strategyBuilders.buildSpawnOptionalInputFIFOStrategy(myLanguage, BNode, core, op_stage_instr, ISAXes, this.SETTINGwithInputFIFO);
    MultiNodeStrategy spawnFenceStrategy = strategyBuilders.buildSpawnFenceStrategy(myLanguage, BNode, core, op_stage_instr, ISAXes,
                                                                                    SETTINGWithScoreboard || hasCustomWrRDSpawnDH);

    MultiNodeStrategy defaultRdwrInStageStrategy = strategyBuilders.buildDefaultRdwrInStageStrategy(myLanguage, BNode, core);
    MultiNodeStrategy defaultWrCommitStrategy = strategyBuilders.buildDefaultWrCommitStrategy(myLanguage, BNode, core);

    // Request disaxfence stall implementation
    if (ISAXes.containsKey(SCAL.PredefInstr.fence.instr.GetName())) {
      globalLogicBuilders.add(NodeLogicBuilder.fromFunction("Request disaxfence_stall", registry -> {
        registry.lookupExpressionRequired(new NodeInstanceDesc.Key(SpawnFenceStrategy.disaxfence_stall_node, core.GetRootStage(), ""));
        return new NodeLogicBlock();
      }));
    }

    // get additional SCAL strategies from CoreBackend to run first
    var backendPreStrategies = coreBackend.isPresent() ? coreBackend.get().getAdditionalSCALPreStrategies() : null;
    // get additional SCAL strategies from CoreBackend to run last
    var backendPostStrategies = coreBackend.isPresent() ? coreBackend.get().getAdditionalSCALPostStrategies() : null;
    // set language object for those additional strategies
    if (backendPreStrategies != null)
      backendPreStrategies.forEach((s) -> s.setLanguage(myLanguage));
    if (backendPostStrategies != null)
      backendPostStrategies.forEach((s) -> s.setLanguage(myLanguage));

    // Root strategy to build nodes for SCAL.
    MultiNodeStrategy scalStrategy = new MultiNodeStrategy() {
      @Override
      public void implement(Consumer<NodeLogicBuilder> out, Iterable<Key> nodeKeys, boolean isLast) {

        // execute additional coreBackend strategies to run prior to common ones
        if (backendPreStrategies != null)
          backendPreStrategies.forEach((s) -> s.implement(out, nodeKeys, isLast));

        // execute common strategies
        decoupledDHStrategy.implement(out, nodeKeys, isLast);
        spawnOrderedMuxStrategy.implement(out, nodeKeys, isLast);
        spawnFireStrategy.implement(out, nodeKeys, isLast);
        spawnCommittedRdStrategy.implement(out, nodeKeys, isLast);
        spawnOutputSelectStrategy.implement(out, nodeKeys, isLast);
        spawnOptionalInputFIFOStrategy.implement(out, nodeKeys, isLast);
        decoupledPipeStrategy.implement(out, nodeKeys, isLast);
        decoupledKillStrategy.implement(out, nodeKeys, isLast);
        spawnRdIValidStrategy.implement(out, nodeKeys, isLast);
        spawnStaticNodePipeStrategy.implement(out, nodeKeys, isLast);
        decoupledStandardModulesStrategy.implement(out, nodeKeys, isLast);
        spawnRegisterStrategy.implement(out, nodeKeys, isLast);
        spawnFenceStrategy.implement(out, nodeKeys, isLast);
        scalStateStrategy.implement(out, nodeKeys, isLast);
        defaultRdwrInStageStrategy.implement(out, nodeKeys, isLast);
        defaultRerunStrategy.implement(out, nodeKeys, isLast);

        SCALInputOutputStrategy.implement(out, nodeKeys, isLast);
        ivalidStrategy.implement(out, nodeKeys, isLast);
        rdInStageValidStrategy.implement(out, nodeKeys, isLast);
        rdwrStallFlushDeqStrategy.implement(out, nodeKeys, isLast);
        readNodeStrategy_corePipeStatus.implement(out, nodeKeys, isLast);
        writeNodeEarlyValidStrategy.implement(out, nodeKeys, isLast);
        normalValidBitStrategy.implement(out, nodeKeys, isLast);
        readNodeStrategy_pipelineable.implement(out, nodeKeys, isLast);

        if (isLast) {
          pipelinedOpStrategy.implement(out, nodeKeys, isLast);
          pipeoutRegularStrategy.implement(out, nodeKeys, isLast);
          defaultMemAdjStrategy.implement(out, nodeKeys, isLast);
          defaultValidCancelReqStrategy.implement(out, nodeKeys, isLast);
          defaultWrCommitStrategy.implement(out, nodeKeys, isLast);
        }

        // execute additional coreBackend strategies to run after common ones
        if (backendPreStrategies != null)
          backendPreStrategies.forEach((s) -> s.implement(out, nodeKeys, isLast));
      }
    };

    // Generate the SCAL module logic using scalStrategy
    ArrayList<NodeBuilderEntry> builtNodes = composer.Generate(scalStrategy, globalNodeRegistry, globalLogicBuilders, true);

    // Optionally output the composer log as DOT for debugging.
    if (composer.hasLog()) {
      File dotFile = new File(outPath, "CommonLogicModule_log.dot");
      System.out.println("[SCAL] writing composition log to CommonLogicModule_log.dot");
      try {
        FileOutputStream fos = new FileOutputStream(dotFile, false);
        PrintWriter out = new PrintWriter(fos);
        composer.writeLogAsDot(out);
        out.close();
      } catch (IOException e) {
        e.printStackTrace();
      }
    }

    // Temporary, as long as the old logic is in place.
    interfToISAX = "";
    interfToCore = "";
    declarations = "";
    logic = "";
    otherModules = "";

    // Build the declarations, logic, interfTo*, etc. strings out of the composer outputs.
    for (NodeBuilderEntry builderEntry : builtNodes) {
      if (builderEntry.block.isEmpty())
        continue;
      NodeLogicBlock logicBlock = builderEntry.block.get();
      for (Map.Entry<String, NodeLogicBlock.InterfacePin> pin : logicBlock.interfPins) {
        NodeInstanceDesc nodeDesc = pin.getValue().nodeDesc;
        SCAIEVNode operation = nodeDesc.getKey().getNode();
        PipelineStage stage = nodeDesc.getKey().getStage();
        String instrName = nodeDesc.getKey().getISAX();
        String scalPinName = nodeDesc.getExpression();
        if (pin.getKey().equals(NodeLogicBlock.InterfToISAXKey)) {
          String topWireName = "isax_" + this.myLanguage.CreateBasicNodeName(operation, stage, instrName, false) +
                               (operation.isInput ? "_to_scal" : "_from_scal");

          List<String> netISAXes = new ArrayList<>();
          if (instrName.isEmpty()) {
            var overrides = nodePerISAXOverride.getOrDefault(operation, emptyStrHashSet);
            // if (nodePerISAXOverride.getOrDefault(operation, emptyStrHashSet).contains(instruction))
            //	interfaceInstr = instruction;
            for (String instruction : op_stage_instr.get(operation).getOrDefault(stage, emptyStrHashSet)) {
              if (!instruction.isEmpty() && !overrides.contains(instruction))
                netISAXes.add(instruction);
            }
            if (netISAXes.isEmpty()) {
              logger.warn("The SCAL<->ISAX pin '" + topWireName + "' is not assigned to any ISAX");
            }
          } else
            netISAXes.add(instrName);

          // The name can differ between ISAXes due to renames.
          // Since each netlist entry only supports one ISAX pin name, we may need to create several entries.
          // Still, the same net should be reused for equal ISAX names,
          //  since modules housing several ISAXes would otherwise get duplicate connections.
          HashMap<String, SCALPinNet> curNetsByISAXPinName = new HashMap<>();
          for (String netISAX : netISAXes) {
            // Look for key rename.
            //'rename from' keys always include the ISAX name
            NodeInstanceDesc.Key isaxPinKeyOverride = renamedISAXInterfacePins.get(new NodeInstanceDesc.Key(operation, stage, netISAX));
            // By default, use the actual node key.
            NodeInstanceDesc.Key isaxPinKey =
                (isaxPinKeyOverride != null) ? isaxPinKeyOverride : new NodeInstanceDesc.Key(operation, stage, instrName);
            assert (isaxPinKey.getNode().isInput == operation.isInput);
            String isaxPinName =
                this.myLanguage.CreateFamNodeName(isaxPinKey.getNode().NodeNegInput(), isaxPinKey.getStage(), isaxPinKey.getISAX(), false);

            SCALPinNet net = curNetsByISAXPinName.computeIfAbsent(isaxPinName, _tmp -> {
              String curTopWireName = topWireName;
              if (isaxPinKeyOverride != null)
                curTopWireName += " " + netISAX;
              return netlist.computeIfAbsent(curTopWireName,
                                             newTopWireName -> new SCALPinNet(operation.size, scalPinName, "", isaxPinName));
            });
            net.isaxes.add(netISAX);
            assert (net.size == operation.size);
            assert (net.isax_module_pin.equals(isaxPinName));
          }

          // if (!instrName.isEmpty() && !BNode.IsUserBNode(operation))
          //	AddIn_op_stage_instr(operation, stage, instrName);

          interfToISAX += pin.getValue().declaration;
        } else if (pin.getKey().equals(NodeLogicBlock.InterfToCoreKey)) {
          String topWireName = "core_" + this.myLanguage.CreateBasicNodeName(operation, stage, instrName, false) +
                               (operation.isInput ? "_to_scal" : "_from_scal");
          String corePinName = this.myLanguage.CreateFamNodeName(operation.NodeNegInput(), stage, instrName, false);
          if (!netlist.containsKey(topWireName)) {
            SCALPinNet net = new SCALPinNet(operation.size, scalPinName, corePinName, "");
            netlist.put(topWireName, net);
          } else {
            SCALPinNet net = netlist.get(topWireName);
            assert (net.size == operation.size);
            assert (net.core_module_pin.equals(corePinName));
          }
          interfToCore += pin.getValue().declaration;
        } else {
          logger.error("SCAL.Generate: Unknown interface key " + pin.getKey());
        }
      }
      if (!logicBlock.declarations.isEmpty()) {
        declarations += String.format("//Declarations - %s\n", builderEntry.builder.toString());
        declarations += logicBlock.declarations + (logicBlock.declarations.endsWith("\n") ? "" : "\n");
      }
      if (!logicBlock.logic.isEmpty()) {
        logic += String.format("//Logic - %s\n", builderEntry.builder.toString());
        logic += logicBlock.logic + (logicBlock.logic.endsWith("\n") ? "" : "\n");
      }
      if (!logicBlock.otherModules.isEmpty()) {
        otherModules += String.format("//Modules - %s\n", builderEntry.builder.toString());
        otherModules += logicBlock.otherModules + "\n";
      }
    }

    op_stage_instr.clear();
    for (NodeBuilderEntry builderEntry : builtNodes) {
      if (builderEntry.block.isEmpty())
        continue;
      NodeLogicBlock logicBlock = builderEntry.block.get();
      for (var interfEntry : logicBlock.interfPins) {
        NodeLogicBlock.InterfacePin interfPin = interfEntry.getValue();
        if (interfEntry.getKey().equals(NodeLogicBlock.InterfToCoreKey)) {
          NodeInstanceDesc nodeDesc = interfPin.nodeDesc;
          SCAIEVNode operation = nodeDesc.getKey().getNode().NodeNegInput();
          PipelineStage stage = nodeDesc.getKey().getStage();
          // String instrName = nodeDesc.getKey().getISAX();

          // Only add registered nodes to op_stage_instr
          if (!BNode.GetSCAIEVNode(operation.name).name.isEmpty())
            AddIn_op_stage_instr(operation, stage, nodeDesc.getRequestedFor().getRelevantISAXes());
          else if (!operation.familyName.isEmpty()) {
            // For Mem nodes (and anything else with a shared family name): Insert the first matching specific node by lexicographical
            // order.
            var matchingBNode_opt = BNode.GetAllBackNodes()
                                        .stream()
                                        .filter(node
                                                -> !node.nameCousinNode.isEmpty() && operation.familyName.equals(node.familyName) &&
                                                       node.replaceRadixNameWith(node.familyName).equals(operation.name))
                                        .reduce((nodeA, nodeB) -> nodeA.name.compareTo(nodeB.name) < 0 ? nodeA : nodeB);
            if (matchingBNode_opt.isPresent())
              AddIn_op_stage_instr(matchingBNode_opt.get(), stage, nodeDesc.getRequestedFor().getRelevantISAXes());
          }
        }
      }
    }

    ////////////////////// Write all this logic to file //////////////////////
    String clkrst = "\ninput " + myLanguage.clk + ",\ninput " + myLanguage.reset + "\n";
    WriteFileUsingTemplate(interfToISAX, interfToCore + clkrst, declarations, logic, otherModules, outPath);
  }

  //////////////////////////////////////////////FUNCTIONS: FOR MAIN ADAPTER LOGIC ////////////////////////

  /**
   * Function to create a virtual core. Used by myLanguage functions
   */
  private void PopulateVirtualCore() {
    if (!op_stage_instr.isEmpty()) {
      for (SCAIEVNode operation : this.op_stage_instr.keySet()) {
        for (PipelineStage stage : this.op_stage_instr.get(operation).keySet()) {
          if (operation.isInput && operation != BNode.WrFlush)
            this.virtualBackend.PutNode("reg", "", "", operation, stage);
          else
            this.virtualBackend.PutNode("", "", "", operation, stage);
          for (SCAIEVNode adjNode : BNode.GetAdjSCAIEVNodes(operation)) {
            if (adjNode.isInput)
              this.virtualBackend.PutNode("reg", "", "", adjNode, stage);
            else
              this.virtualBackend.PutNode("", "", "", adjNode, stage);
          }
        }
      }
    }
  }

  static final HashSet<String> emptyStrHashSet = new HashSet<>();
  static final HashMap<PipelineStage, HashSet<String>> emptyStageToStrSetHashMap = new HashMap<>();

  /**
   * Function to generate text for interface to Core.Assumes that operation is requested in stage. Returns assigns for corresponding
   * interfaces. If node is User Node, it does not generate interface but it declares the signal as wire/reg
   *
   */
  private void GenerateAllInterfToCore(SCAIEVNode operation, PipelineStage stage, HashSet<String> interfaceText, NodeRegistry registry,
                                       List<NodeLogicBuilder> interfaceBuilders) {
    if (operation.equals(BNode.RdIValid)) // SCAL handles RdIValid and ISAX Internal state
      return;

    String newinterf = "";
    String dataT = "";
    assert (emptyStageToStrSetHashMap.isEmpty());
    assert (emptyStrHashSet.isEmpty());
    HashSet<String> instructions = op_stage_instr.getOrDefault(operation, emptyStageToStrSetHashMap).getOrDefault(stage, emptyStrHashSet);
    HashMap<String, InterfaceRequestBuilder> existingBuildersByInterfaceText = new HashMap<>();
    for (String instruction : instructions) {
      // Generate main FNode interface
      // String instrName = "";
      dataT = "";
      // if( !operation.isInput && operation.oneInterfToISAX)
      //	instrName = instruction;
      {
        String interfaceInstr = "";
        if (nodePerISAXOverride.getOrDefault(operation, emptyStrHashSet).contains(instruction))
          interfaceInstr = instruction;
        boolean isSCALInput = !operation.isInput; /*operation.isInput -> is output from SCAL*/

        // The familyName will only be applied to operations with isAdj (-> GenerateText.CreateBasicNodeName) .
        boolean noInterface = operation.tags.contains(NodeTypeTag.noCoreInterface);
        if (noInterface) {
        } else if (!BNode.IsUserBNode(operation)) {
          newinterf = myLanguage.CreateTextInterface(operation.name, stage, interfaceInstr, isSCALInput, operation.size, dataT);
          // newinterf = this.CreateAndRegisterTextInterfaceForCore(operation.NodeNegInput(), stage, interfaceInstr, dataT);
          boolean isNew = interfaceText.add(newinterf);
          NodeInstanceDesc.Key key = new NodeInstanceDesc.Key(operation, stage, interfaceInstr);
          if (isNew) {
            var builder = new InterfaceRequestBuilder(
                isSCALInput ? NodeInstanceDesc.Purpose.MARKER_FROMCORE_PIN : NodeInstanceDesc.Purpose.MARKER_TOCORE_PIN, key);
            existingBuildersByInterfaceText.put(newinterf, builder);
            interfaceBuilders.add(builder);
          }
          Optional.ofNullable(existingBuildersByInterfaceText.get(newinterf))
              .ifPresent(builder -> builder.requestedFor.addRelevantISAX(instruction));
        } else {
          //					String pinName = myLanguage.CreateNodeName(operation.NodeNegInput(), stage, "");
          //					declares.add(myLanguage.CreateDeclSig(operation.NodeNegInput(), stage,"", operation.isInput,
          // pinName)); 					if (!operation.isInput /*is input to SCAL*/)
          // registry.registerUnique(new NodeInstanceDesc(new NodeInstanceDesc.Key(operation, stage, ""), pinName,
          // ExpressionType.WireName));
        }
      }
      // Generate adjacent signals on the interface
      // Scheduled snode = ISAXes.get(instruction).GetSchedWith(operation, _snode -> _snode.GetStartCycle() == stage.getStagePos());
      for (AdjacentNode adjacent : BNode.GetAdj(operation)) {
        SCAIEVNode adjOperation = BNode.GetAdjSCAIEVNode(operation, adjacent).get();
        dataT = this.virtualBackend.NodeDataT(adjOperation, stage);
        if ((!instruction.isEmpty() && ISAXes.get(instruction).GetFirstNode(operation).HasAdjSig(adjacent)) ||
            adjOperation.DefaultMandatoryAdjSig() || adjOperation.mandatory || adjOperation.mustToCore ||
            adjacent == AdjacentNode.spawnAllowed /*HACK*/) {
          if (!operation.nameCousinNode.isEmpty())
            adjOperation = SCAIEVNode.CloneNode(adjOperation, Optional.of(adjOperation.replaceRadixNameWith(operation.familyName)), true);
          if (adjacent == AdjacentNode.spawnAllowed && !adjSpawnAllowedNodes.contains(adjOperation) && !BNode.IsUserBNode(adjOperation))
            continue; // Core may not provide specific spawnAllowed node.
          if (adjOperation.tags.contains(NodeTypeTag.defaultNotprovidedByCore) && !core.GetNodes().containsKey(adjOperation))
            continue;
          String interfaceInstr = "";
          boolean noInterface = operation.tags.contains(NodeTypeTag.noCoreInterface);
          if (noInterface) {
          } else if (!BNode.IsUserBNode(adjOperation)) {
            boolean isSCALInput = !adjOperation.isInput; /*adjOperation.isInput -> is output from SCAL*/
            newinterf = myLanguage.CreateTextInterface(adjOperation.name, stage, interfaceInstr, isSCALInput, adjOperation.size, dataT);
            // newinterf = this.CreateAndRegisterTextInterfaceForCore(adjOperation.NodeNegInput(), stage, interfaceInstr, dataT);
            boolean isNew = interfaceText.add(newinterf);
            NodeInstanceDesc.Key key = new NodeInstanceDesc.Key(adjOperation, stage, interfaceInstr);
            if (isNew) {
              var builder = new InterfaceRequestBuilder(
                  isSCALInput ? NodeInstanceDesc.Purpose.MARKER_FROMCORE_PIN : NodeInstanceDesc.Purpose.MARKER_TOCORE_PIN, key);
              existingBuildersByInterfaceText.put(newinterf, builder);
              interfaceBuilders.add(builder);
            }
            Optional.ofNullable(existingBuildersByInterfaceText.get(newinterf))
                .ifPresent(builder -> builder.requestedFor.addRelevantISAX(instruction));
          } else {
            //							String pinName = myLanguage.CreateNodeName(adjOperation.NodeNegInput(),
            // stage, "");
            //							//Output from the SCALState module
            //							// TODO: Make SCALState its own logic block that instantiates a sub-module
            // using the same node infrastructure.
            // declares.add(myLanguage.CreateDeclSig(adjOperation.NodeNegInput(), stage,"", adjOperation.isInput, pinName));
            // if (!adjOperation.isInput /*is input to SCAL*/) registry.registerUnique(new NodeInstanceDesc(new
            // NodeInstanceDesc.Key(adjOperation, stage, ""), pinName, ExpressionType.WireName));
          }
        }
      }
    }

    // Generate validReq interfaces required by some nodes in earlier stages (usually Memory nodes )
    if (Optional
            .ofNullable( //-> (stage >= earliestFront)
                this.node_earliestStageValid.get(operation))
            .map(earliestFront -> earliestFront.isAroundOrBefore(stage, false))
            .orElse(false)) {
      if (instructions.isEmpty() && !BNode.IsUserBNode(operation)) {
        SCAIEVNode validReqNode = BNode.GetAdjSCAIEVNode(operation, AdjacentNode.validReq).get();
        dataT = ""; //"reg"; //this.virtualBackend.NodeDataT(adjOperation, stage);
        newinterf = myLanguage.CreateTextInterface(validReqNode.name, stage, "", false, validReqNode.size, dataT);
        boolean isNew = interfaceText.add(newinterf);

        NodeInstanceDesc.Key key = new NodeInstanceDesc.Key(validReqNode, stage, "");

        if (isNew) {
          var builder = new InterfaceRequestBuilder(NodeInstanceDesc.Purpose.MARKER_TOCORE_PIN, key);
          existingBuildersByInterfaceText.put(newinterf, builder);
          interfaceBuilders.add(builder);
        }
      }
      if (!BNode.IsUserBNode(operation) && operation.DH) {
        SCAIEVNode validDataNode = BNode.GetAdjSCAIEVNode(operation, AdjacentNode.validData).get();
        dataT = ""; //"reg"; //this.virtualBackend.NodeDataT(adjOperation, stage);
        newinterf = myLanguage.CreateTextInterface(validDataNode.name, stage, "", false, validDataNode.size, dataT);
        boolean isNew = interfaceText.add(newinterf);

        NodeInstanceDesc.Key key = new NodeInstanceDesc.Key(validDataNode, stage, "");

        if (isNew) {
          var builder = new InterfaceRequestBuilder(NodeInstanceDesc.Purpose.MARKER_TOCORE_PIN, key);
          existingBuildersByInterfaceText.put(newinterf, builder);
          interfaceBuilders.add(builder);
        }
      }
      //			if (!BNode.IsUserBNode(operation)) {
      //				dataT = "reg";//this.virtualBackend.NodeDataT(adjOperation, stage);
      //				newinterf = this.CreateAndRegisterTextInterfaceForCore(adjOperation.NodeNegInput(), stage, "",
      // dataT); 				interfaceText.add(newinterf); 			} else {
      //				//TODO: Dependency?
      //				declares.add(myLanguage.CreateDeclSig(adjOperation.NodeNegInput(), stage,"", adjOperation.isInput,
      // myLanguage.CreateNodeName(adjOperation.NodeNegInput(), stage, "")));
      //			}
    }
  }

  private void GenerateAllInterfToISAX(SCAIEVNode operation, PipelineStage stage, Iterator<String> instrIter, HashSet<String> interfaceText,
                                       NodeRegistry registry, List<NodeLogicBuilder> assigns) {
    // String assigns = "";
    String newinterf = "";
    String dataT = "";
    assert (emptyStrHashSet.isEmpty());
    class AddReqData {
      SCAIEVNode op;
      PipelineStage stage;
      String instruction;
      public AddReqData(SCAIEVNode op, PipelineStage stage, String instruction) {
        this.op = op;
        this.stage = stage;
        this.instruction = instruction;
      }
    };
    List<AddReqData> addToOpStageInstr = new ArrayList<>();
    HashMap<String, InterfaceRequestBuilder> existingBuildersByInterfaceText = new HashMap<>();
    while (instrIter.hasNext()) {
      String instruction = instrIter.next();
      if (instruction.isEmpty())
        continue;
      // Generate main FNode interface
      String instrName = "";
      if (!operation.oneInterfToISAX || nodePerISAXOverride.getOrDefault(operation, emptyStrHashSet).contains(instruction) ||
          operation.tags.contains(NodeTypeTag.supportsPortNodes) && (!ISAXes.containsKey(instruction) || ISAXes.get(instruction).HasNoOp()))
        instrName = instruction;

      // Change the interface stage to the decoupled stage for semi-coupled instructions mechanism
      PipelineStage interfaceStage = stage;
      if (operation.isSpawn() && stage.getKind() == StageKind.Core) {
        // Find a decoupled stage to generate the ISAX interface based on.
        var relevantDecoupledStages = stage.streamNext_bfs(succ -> succ.getKind() == StageKind.Core)
                                          .filter(stageNext -> stageNext.getKind() == StageKind.Decoupled)
                                          .toList();
        var asKey = new NodeInstanceDesc.Key(operation, stage, instrName);
        if (relevantDecoupledStages.isEmpty()) {
          logger.error("Couldn't find a decoupled stage after {}. Cannot generate ISAX interface for semi-coupled node {}.",
                       stage.getName(), asKey.toString(false));
          continue;
        }
        PipelineStage decoupledStage = relevantDecoupledStages.get(0);
        if (decoupledStage.getGlobalStageID().isEmpty()) {
          logger.error("The decoupled stage {} has no interface ID. Cannot generate ISAX interface for node {}.", decoupledStage.getName(),
                       asKey.toString(false));
          continue;
        }
        if (relevantDecoupledStages.stream().skip(1).anyMatch(
                otherStage -> !otherStage.getGlobalStageID().equals(decoupledStage.getGlobalStageID()))) {
          logger.error("Found decoupled stages after {} with non-matching interface IDs. Cannot generate ISAX interface for node {}.",
                       stage.getName(), asKey.toString(false));
          continue;
        }
        interfaceStage = decoupledStage;
      }

      NodeInstanceDesc.Key baseKey = new NodeInstanceDesc.Key(operation, stage, instrName);
      boolean noInterface =
          (renamedISAXInterfacePins.containsKey(baseKey) && renamedISAXInterfacePins.get(baseKey).getNode().name.isEmpty());
      if (!operation.noInterfToISAX) {

        // newinterf = CreateAndRegisterTextInterfaceForISAX(addOperation, addStage, instruction, !instrName.isEmpty(), dataT);
        newinterf = myLanguage.CreateTextInterface(operation.name, interfaceStage, instruction, operation.isInput, operation.size, dataT);
        var key = baseKey;
        NodeInstanceDesc.Key keyRenameTo =
            (interfaceStage != stage) ? new NodeInstanceDesc.Key(operation, interfaceStage, instrName) : null;
        if (!noInterface && interfaceText.add(newinterf)) {
          boolean isSCALInput = operation.isInput;
          InterfaceRequestBuilder builder = new InterfaceRequestBuilder(
              isSCALInput ? NodeInstanceDesc.Purpose.MARKER_FROMISAX_PIN : NodeInstanceDesc.Purpose.MARKER_TOISAX_PIN, key);
          existingBuildersByInterfaceText.put(newinterf, builder);
          assigns.add(builder);
        }
        Optional.ofNullable(existingBuildersByInterfaceText.get(newinterf))
            .ifPresent(builder -> builder.requestedFor.addRelevantISAX(instruction));
        instrIter.remove();
        if (!noInterface && keyRenameTo != null)
          renamedISAXInterfacePins.put(key, keyRenameTo);
        addToOpStageInstr.add(new AddReqData(operation, stage, instruction));
      }

      // Generate adjacent signals on the interface
      // Scheduled snode = ISAXes.get(instruction).GetSchedWith(operation, _snode -> _snode.GetStartCycle() == stage);

      for (AdjacentNode adjacent : BNode.GetAdj(operation)) {
        SCAIEVNode adjOperation = BNode.GetAdjSCAIEVNode(operation, adjacent).get();
        instrName = "";
        if (!adjOperation.oneInterfToISAX)
          instrName = instruction;
        if (adjOperation.noInterfToISAX && !(adjOperation.mandatory && ISAXes.get(instruction).GetRunsAsDynamic()))
          continue;

        if (ISAXes.get(instruction).GetFirstNode(operation).HasAdjSig(adjacent)) {
          newinterf =
              myLanguage.CreateTextInterface(adjOperation.name, interfaceStage, instrName, adjOperation.isInput, adjOperation.size, dataT);
          NodeInstanceDesc.Key key = new NodeInstanceDesc.Key(adjOperation, stage, instrName);
          NodeInstanceDesc.Key keyRenameTo =
              (interfaceStage != stage) ? new NodeInstanceDesc.Key(adjOperation, interfaceStage, instrName) : null;
          if (!noInterface && interfaceText.add(newinterf)) {
            boolean isSCALInput = adjOperation.isInput;
            InterfaceRequestBuilder builder = new InterfaceRequestBuilder(
                isSCALInput ? NodeInstanceDesc.Purpose.MARKER_FROMISAX_PIN : NodeInstanceDesc.Purpose.MARKER_TOISAX_PIN, key);
            existingBuildersByInterfaceText.put(newinterf, builder);
            assigns.add(builder);
          }
          Optional.ofNullable(existingBuildersByInterfaceText.get(newinterf))
              .ifPresent(builder -> builder.requestedFor.addRelevantISAX(instruction));
          if (!noInterface && keyRenameTo != null)
            renamedISAXInterfacePins.put(key, keyRenameTo);
          addToOpStageInstr.add(new AddReqData(adjOperation, stage, instruction));
        }
      }
    }
    addToOpStageInstr.forEach(addReq -> AddIn_op_stage_instr(addReq.op, addReq.stage, addReq.instruction));
  }

  private boolean AddIn_op_stage_instr(SCAIEVNode operation, PipelineStage stage, String instruction) {
    var stage_instr_map = op_stage_instr.computeIfAbsent(operation, operation_ -> new HashMap<>());
    var instr_set = stage_instr_map.computeIfAbsent(stage, stage_ -> new HashSet<>());
    return instr_set.add(instruction);
  }
  private boolean AddIn_op_stage_instr(SCAIEVNode operation, PipelineStage stage, Collection<String> instructions) {
    var stage_instr_map = op_stage_instr.computeIfAbsent(operation, operation_ -> new HashMap<>());
    var instr_set = stage_instr_map.computeIfAbsent(stage, stage_ -> new HashSet<>());
    if (instructions.isEmpty())
      return instr_set.add("");
    return instr_set.addAll(instructions);
  }

  /**
   * Check if op_stage_instr has operation in stage
   * @param operation
   * @param stage
   * @return
   */
  private boolean ContainsOpInStage(SCAIEVNode operation, PipelineStage stage) {
    return op_stage_instr.containsKey(operation) && op_stage_instr.get(operation).containsKey(stage);
  }

  //////////////////////////////////////////////FUNCTIONS: WRITE ALL TEXT ////////////////////////
  /**
   * Write text based on template
   *
   */
  private void WriteFileUsingTemplate(String interfToISAX, String interfToCore, String declarations, String logic, String otherModules,
                                      String outPath) {
    String tab =
        myLanguage
            .tab; //  no need to use the same tab as in Core's module files. It is important just to have same tab across the same file
    String endl = "\n";
    String textToWrite = "" + endl + tab.repeat(0) + "// SystemVerilog file \n " + endl + tab.repeat(0) + "module SCAL (" + endl +
                         tab.repeat(1) + "// Interface to the ISAX Module" + endl + tab.repeat(1) + "\n" +
                         this.myLanguage.AlignText(tab.repeat(1), interfToISAX) + endl + tab.repeat(1) + "" + endl + tab.repeat(1) +
                         "// Interface to the Core" + endl + tab.repeat(1) + "\n" +
                         this.myLanguage.AlignText(tab.repeat(1), interfToCore) + endl + tab.repeat(1) + "" + endl + tab.repeat(0) + ");" +
                         endl + tab.repeat(0) + "// Declare local signals" + endl + tab.repeat(0) + declarations + endl + tab.repeat(0) +
                         "" + endl + tab.repeat(0) + "// Logic" + endl + tab.repeat(0) + logic + endl + tab.repeat(0) + "" + endl +
                         tab.repeat(0) + "endmodule\n" + endl + tab.repeat(0) + "\n" + endl + tab.repeat(0) + otherModules + "\n";

    // Write text to file CommonLogicModule.sv
    String fileName = "CommonLogicModule.sv";
    toFile.AddFile(fileName, true);
    toFile.UpdateContent(fileName, textToWrite);
    toFile.WriteFiles(myLanguage.GetDictModule(), myLanguage.GetDictEndModule(), outPath);
  }
}
