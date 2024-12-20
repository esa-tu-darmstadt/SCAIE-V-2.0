package scaiev.backend;

import java.util.HashMap;
import java.util.HashSet;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.coreconstr.Core;
import scaiev.coreconstr.CoreNode;
import scaiev.frontend.FNode;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.util.FileWriter;
import scaiev.util.GenerateText.DictWords;
import scaiev.util.Lang;
import scaiev.util.SpinalHDL;
import scaiev.util.ToWrite;
// c524335
public class VexRiscv extends CoreBackend {
  // logging
  protected static final Logger logger = LogManager.getLogger();
  // TODO: Support for Mem_*_size, defaultAddr
  private static final String[] vexInterfaceStageNames = new String[] {"", "decode", "execute", "memory", "writeBack"};
  public String pathCore = "CoresSrc/VexRiscv";
  public String getCorePathIn() { return pathCore; }
  public String pathRISCV = "src/main/scala/vexriscv";
  public String baseConfig = "VexRiscvAhbLite3";
  private Core vex_core;
  private HashMap<String, SCAIEVInstr> ISAXes;
  private HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  private PipelineStage[] stages;
  private FileWriter toFile = new FileWriter(pathCore);
  private String filePlugin;
  private String extension_name;
  private SpinalHDL language = null;
  private HashMap<SCAIEVNode, Integer> spawnStages = new HashMap<SCAIEVNode, Integer>();
  public FNode FNode = new FNode();
  public BNode BNode = new BNode();
  private int nrTabs = 0;
  public VexRiscv(Core vex_core) { this.vex_core = vex_core; }
  public void Prepare(HashMap<String, SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                      Core core, SCALBackendAPI scalAPI, BNode user_BNode) {
    super.Prepare(ISAXes, op_stage_instr, core, scalAPI, user_BNode);
    this.stages = core.GetRootStage().getAllChildren().collect(Collectors.toList()).toArray(n -> new PipelineStage[n]);
    for (int i = 0; i < this.stages.length; ++i)
      assert (this.stages[i].getStagePos() == i);
    this.BNode = user_BNode;
    this.language = new SpinalHDL(user_BNode, toFile, this);
    int stage_mem_valid = this.vex_core.GetNodes().get(BNode.RdInstr).GetEarliest().asInt();
    scalAPI.OverrideEarliestValid(user_BNode.WrMem, new PipelineFront(stages[stage_mem_valid]));
    scalAPI.OverrideEarliestValid(user_BNode.RdMem, new PipelineFront(stages[stage_mem_valid]));
    // Ignore WrCommit_spawn for now.
    BNode.WrCommit_spawn.tags.add(NodeTypeTag.noCoreInterface);
    BNode.WrCommit_spawn_validReq.tags.add(NodeTypeTag.noCoreInterface);
    BNode.WrCommit_spawn_validResp.tags.add(NodeTypeTag.noCoreInterface);
    core.PutNode(BNode.RdInStageValid,
                 new CoreNode(1, 0, vexInterfaceStageNames.length, vexInterfaceStageNames.length + 1, BNode.RdInStageValid.name));
  }
  public boolean Generate(HashMap<String, SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                          String extension_name, Core core, String out_path) { // core needed for verification purposes
    // Set variables
    this.vex_core = vex_core;
    this.ISAXes = ISAXes;
    this.op_stage_instr = op_stage_instr;
    this.extension_name = extension_name;
    ConfigVex();
    this.filePlugin = fileHierarchy.get(extension_name).file;
    language.currentFile = this.filePlugin;
    language.withinCore = true;
    toFile.AddFile(this.filePlugin, true);
    IntegrateISAX_DefImports();
    IntegrateISAX_DefObjValidISAX();
    IntegrateISAX_OpenClass();
    IntegrateISAX_Services();
    IntegrateISAX_OpenSetupPhase();
    for (var isaxEntry : ISAXes.entrySet())
      if (!isaxEntry.getValue().HasNoOp())
        IntegrateISAX_SetupDecode(isaxEntry.getValue());
    IntegrateISAX_SetupServices();
    IntegrateISAX_CloseSetupPhase();
    IntegrateISAX_OpenBuild();
    for (int i = 0; i <= this.vex_core.maxStage; i++)
      IntegrateISAX_Build(stages[i]);
    language.CloseBrackets(); // close build section
    language.CloseBrackets(); // close class
    // Configs
    IntegrateISAX_UpdateConfigFile();      // If everything went well, also update config file
    IntegrateISAX_UpdateArbitrationFile(); // in case of WrRD spawn, update arbitration
    IntegrateISAX_UpdateRegFforSpawn();    // in case of WrRD spawn, update arbitration
    IntegrateISAX_UpdateIFetchFile();
    IntegrateISAX_UpdateDHFile();
    IntegrateISAX_UpdateServiceFile();
    IntegrateISAX_UpdateMemFile();
    // Write all files
    toFile.WriteFiles(language.GetDictModule(), language.GetDictEndModule(), out_path);
    return true;
  }
  // #################################### IMPORT PHASE ####################################
  private void IntegrateISAX_DefImports() {
    String addText = """
      package vexriscv.plugin
      import vexriscv._
      import spinal.core._
      import spinal.lib._
      import scala.collection.mutable.ArrayBuffer
      import scala.collection.mutable
      import scala.collection.JavaConverters._
    """;
    toFile.UpdateContent(filePlugin, addText);
  }

  // Declare ISAXes as objects IValid
  private void IntegrateISAX_DefObjValidISAX() {
    toFile.UpdateContent(filePlugin, "object " + extension_name + " {");
    toFile.nrTabs++;
    String stageables = "object IS_ISAX extends Stageable(Bool)"; // no MUXing required, done by SCAL
    toFile.UpdateContent(filePlugin, stageables);
    language.CloseBrackets();
  }

  // Define ISAX plugin class
  private void IntegrateISAX_OpenClass() {
    String defineClass = "class " + extension_name + "(writeRfInMemoryStage: Boolean = false)extends Plugin[VexRiscv] {";
    toFile.UpdateContent(filePlugin, defineClass);
    toFile.nrTabs++;
    toFile.UpdateContent(filePlugin, "import " + extension_name + "._");
  }

  // Declare Required Services
  private void IntegrateISAX_Services() {
    if (op_stage_instr.containsKey(BNode.WrPC))
      for (int i = 1; i < this.vex_core.maxStage + 2; i++)
        if (op_stage_instr.get(BNode.WrPC).containsKey(stages[i]))
          toFile.UpdateContent(filePlugin, "var jumpInterface_" + i + ": Flow[UInt] = null");
    if (op_stage_instr.containsKey(BNode.RdMem) || op_stage_instr.containsKey(BNode.WrMem) ||
        op_stage_instr.containsKey(BNode.RdMem_spawn) || op_stage_instr.containsKey(BNode.WrMem_spawn))
      toFile.UpdateContent(filePlugin, "var dBusAccess : DBusAccess = null");

    if (ContainsOpInStage(BNode.WrPC, 0) || op_stage_instr.containsKey(BNode.WrPC_spawn) || ContainsOpInStage(BNode.RdPC, 0))
      toFile.UpdateContent(filePlugin, "var  jumpInFetch : JumpInFetch = null");
  }

  // ######################################################################################################
  // #############################################   SETUP    #############################################
  // ######################################################################################################
  private void IntegrateISAX_OpenSetupPhase() {
    toFile.UpdateContent(filePlugin, "\n// Setup phase.");
    toFile.UpdateContent(filePlugin, "override def setup(pipeline: VexRiscv): Unit = {");
    toFile.UpdateContent(filePlugin, "import pipeline._");
    toFile.nrTabs++; // should become 2
    toFile.UpdateContent(filePlugin, "import pipeline.config._");
    toFile.UpdateContent(filePlugin, "val decoderService = pipeline.service(classOf[DecoderService])");
    toFile.UpdateContent(filePlugin, "decoderService.addDefault(IS_ISAX, False)\n");
  }

  private void IntegrateISAX_SetupDecode(SCAIEVInstr isax) {
    if (isax.HasNoOp())
      return;
    String setupText = "";
    int tabs = toFile.nrTabs;
    String tab = toFile.tab;
    boolean defaultMemAddr = (isax.HasNode(BNode.RdMem) && !isax.GetFirstNode(BNode.RdMem).HasAdjSig(AdjacentNode.addr)) ||
                             (isax.HasNode(BNode.WrMem) && !isax.GetFirstNode(BNode.WrMem).HasAdjSig(AdjacentNode.addr)) ||
                             (isax.HasNode(BNode.RdMem_spawn) && !isax.GetFirstNode(BNode.RdMem_spawn).HasAdjSig(AdjacentNode.addr)) ||
                             (isax.HasNode(BNode.WrMem_spawn) && !isax.GetFirstNode(BNode.WrMem_spawn).HasAdjSig(AdjacentNode.addr));
    setupText += tab.repeat(tabs) + "decoderService.add(\n";

    tabs++;
    if (!isax.HasNoOp())
      setupText += tab.repeat(tabs) + "key = M\"" + isax.GetEncodingBasic() + "\","
                   + "\n"; // Set encoding
    setupText += tab.repeat(tabs) + "List(\n";

    // Signal this ISAX
    tabs++;
    setupText += tab.repeat(tabs) + "IS_ISAX                  -> True,\n";

    // 	? PC INCREMENT DESIRED?
    // OP1
    if (isax.HasNode(BNode.RdImm) && isax.GetInstrType().equals("U"))
      setupText += tab.repeat(tabs) + "SRC1_CTRL                -> Src1CtrlEnum.IMU,\n";
    else if (isax.HasNode(BNode.RdRS1) || defaultMemAddr)
      setupText += tab.repeat(tabs) + "SRC1_CTRL                -> Src1CtrlEnum.RS,\n";

    // OP2
    if (isax.HasNode(BNode.RdImm) || defaultMemAddr) {
      if (isax.GetInstrType().equals("I") || defaultMemAddr) {
        setupText += tab.repeat(tabs) + "SRC2_CTRL                -> Src2CtrlEnum.IMI,\n";
        setupText += tab.repeat(tabs) + "SRC_USE_SUB_LESS  	     -> False,\n"; //  EC
      } else if (isax.GetInstrType().equals("S"))
        setupText += tab.repeat(tabs) + "SRC2_CTRL                -> Src2CtrlEnum.IMS,\n";
    } else
      setupText += tab.repeat(tabs) + "SRC2_CTRL                -> Src2CtrlEnum.RS,\n";

    // WRITE REGFILE
    // if(isax.HasNode(BNode.WrRD) && isax.GetNode(BNode.WrRD).GetStartCycle()<=this.vex_core.maxStage)
    boolean reqWrRD = false; // seems werid the current implementation of wrrd = true, but it is required because of spawn nodes which stall
                             // the pipeline and are implemented in execute although they requested later stage
    int WrRDcycle = this.vex_core.maxStage + 1;
    if (isax.HasNode(BNode.WrRD))
      WrRDcycle = isax.GetFirstNode(BNode.WrRD).GetStartCycle();
    if (this.op_stage_instr.containsKey(BNode.WrRD)) {
      for (PipelineStage stage : this.op_stage_instr.get(BNode.WrRD).keySet()) {
        if (stage.getKind() == StageKind.Core && this.op_stage_instr.get(BNode.WrRD).get(stage).contains(isax.GetName())) {
          reqWrRD = true;
          WrRDcycle = stage.getStagePos();
        }
      }
    }
    if (reqWrRD)
      setupText += tab.repeat(tabs) + "REGFILE_WRITE_VALID      -> True,\n";
    else
      setupText += tab.repeat(tabs) + "REGFILE_WRITE_VALID      -> False,\n";

    // Read Reg file?
    String add_comma = "";
    if (isax.HasNode(BNode.WrRD))
      add_comma = tab.repeat(tabs) + ",";
    if (isax.HasNode(BNode.RdRS1) || defaultMemAddr)
      setupText += tab.repeat(tabs) + "RS1_USE                  -> True,\n";
    else
      setupText += tab.repeat(tabs) + "RS1_USE                  -> False,\n";
    if (isax.HasNode(BNode.RdRS2))
      setupText += tab.repeat(tabs) + "RS2_USE                  -> True" + add_comma + "\n";
    else
      setupText += tab.repeat(tabs) + "RS2_USE                  -> False" + add_comma + "\n";

    if (reqWrRD) {
      if (WrRDcycle == 2) // spawn is not bypassable. 3 hardcoded for memory stage of the core
        setupText += tab.repeat(tabs) + "BYPASSABLE_EXECUTE_STAGE  -> True,\n";
      else
        setupText += tab.repeat(tabs) + "BYPASSABLE_EXECUTE_STAGE  -> False,\n";
      if (WrRDcycle <= 3) // spawn is not bypassable. 3 hardcoded for memory stage of the core
        setupText += tab.repeat(tabs) + "BYPASSABLE_MEMORY_STAGE  -> True\n";
      else
        setupText += tab.repeat(tabs) + "BYPASSABLE_MEMORY_STAGE  -> False\n";
    }
    tabs--; // should become 3
    setupText += tab.repeat(tabs) + ")\n";
    tabs--; // should become 2
    setupText += tab.repeat(tabs) + ")\n";
    toFile.UpdateContent(filePlugin, setupText);
  }

  private void IntegrateISAX_SetupServices() {
    // toFile.nrTabs should be 2
    String setupServices = "";
    if (op_stage_instr.containsKey(BNode.WrPC)) {
      boolean warning_spawn = false;
      for (int i = 1; i <= this.vex_core.maxStage; i++) {
        if (op_stage_instr.get(BNode.WrPC).containsKey(stages[i])) {
          if (i == 1)
            warning_spawn = true;
          setupServices += "val flushStage_" + i + " = if (memory != null) memory else execute\n";
          setupServices += "val pcManagerService_" + i + " = pipeline.service(classOf[JumpService])\n";
          setupServices += "jumpInterface_" + i + " = pcManagerService_" + i + ".createJumpInterface(flushStage_" + i + ")\n";
        }
      }
    }
    if (op_stage_instr.containsKey(BNode.WrPC_spawn) || this.ContainsOpInStage(BNode.WrPC, 0) || this.ContainsOpInStage(BNode.RdPC, 0)) {
      setupServices += "jumpInFetch = pipeline.service(classOf[JumpInFetchService]).createJumpInFetchInterface();\n";
    }
    if (op_stage_instr.containsKey(BNode.RdMem) || op_stage_instr.containsKey(BNode.RdMem_spawn) ||
        op_stage_instr.containsKey(BNode.WrMem) || op_stage_instr.containsKey(BNode.WrMem_spawn)) {
      setupServices += "// Get service for memory transfers\n";
      setupServices += "dBusAccess = pipeline.service(classOf[DBusAccessService]).newDBusAccess();\n";
    }
    toFile.UpdateContent(filePlugin, setupServices);
  }

  private void IntegrateISAX_CloseSetupPhase() { language.CloseBrackets(); }

  // #####################################################################################
  // #################################### BUILD PHASE ####################################
  // #####################################################################################
  /***************************************
   * Open build section
   **************************************/
  private void IntegrateISAX_OpenBuild() {
    toFile.UpdateContent(filePlugin, "\n// Build phase.");
    toFile.UpdateContent(filePlugin, "override def build(pipeline: VexRiscv): Unit = {");
    toFile.nrTabs++;
    toFile.UpdateContent(filePlugin, "import pipeline._");
    toFile.UpdateContent(filePlugin, "import pipeline.config._");

    if (op_stage_instr.containsKey(BNode.WrRD)) // EC
      toFile.UpdateContent(filePlugin, "val writeStage = if (writeRfInMemoryStage) pipeline.memory else pipeline.stages.last ");
  }

  /***************************************
   * Open build section for ONE plugin/stage
   ***************************************/
  private void IntegrateISAX_Build(PipelineStage stage) {
    int stageNum = stage.getStagePos();
    for (SCAIEVNode operation : op_stage_instr.keySet())
      if (!operation.isAdj()) {
        boolean spawnReq =
            ((operation.equals(BNode.WrMem_spawn) || operation.equals(BNode.RdMem_spawn) || operation.equals(BNode.WrRD_spawn)) &&
             stage.getStagePos() == this.vex_core.maxStage);
        if (op_stage_instr.get(operation).containsKey(stage) || spawnReq || (stageNum == 1 && this.ContainsOpInStage(BNode.WrFlush, 0))) {
          if (stageNum > 0) {
            toFile.UpdateContent(filePlugin, " ");
            toFile.UpdateContent(filePlugin, vexInterfaceStageNames[stageNum] + " plug new Area {");
            toFile.UpdateContent(filePlugin, "import " + vexInterfaceStageNames[stageNum] + "._");
            toFile.nrTabs++;
            IntegrateISAX_BuildIOs(stage);
            IntegrateISAX_BuildBody(stage);
            language.CloseBrackets(); // close build stage

            break;
          }
        }
      }
    if (stageNum == 0) {
      IntegrateISAX_BuilPCNodes();
    }
  }

  /**************************************
   * Build body, Inputs/Outputs.
   **************************************/
  private void IntegrateISAX_BuildIOs(PipelineStage stage) {
    int stageNum = stage.getStagePos();
    toFile.UpdateContent(filePlugin, "val io = new Bundle {");
    toFile.nrTabs++;

    String interfaces = "";

    for (SCAIEVNode operation : op_stage_instr.keySet())
      if (!operation.isAdj()) {
        // Check if it is a spawn node to number its stage correctly. Check that it is an FNode, because otherwise, the core does not have
        // any timing constrints on it.
        boolean spawnValid = !operation.nameParentNode.isEmpty() && FNode.HasSCAIEVNode(operation.nameParentNode) && operation.isSpawn() &&
                             stageNum == this.spawnStages.get(operation);
        int opStageNum = stageNum;
        if (spawnValid)
          opStageNum = this.vex_core.maxStage + 1;
        if (spawnValid || ContainsOpInStage(operation, stage) || operation.equals(BNode.WrFlush)) {
          if (!(stageNum == 0 && operation.equals(BNode.WrFlush)))
            interfaces += language.CreateInterface(operation, stages[opStageNum], "");
          if (stageNum == 1 && ContainsOpInStage(operation, stageNum - 1) && operation.equals(BNode.WrFlush))
            interfaces += language.CreateInterface(operation, stages[opStageNum - 1], "");
          // Generate adjacent signals on the interface
          for (AdjacentNode adjacent : BNode.GetAdj(operation)) {
            if (adjacent != AdjacentNode.none) {
              if (spawnValid && !operation.nameCousinNode.isEmpty() &&
                  this.op_stage_instr.containsKey(BNode.GetSCAIEVNode(operation.nameCousinNode)) && operation.isInput)
                continue;
              SCAIEVNode adjOperation = BNode.GetAdjSCAIEVNode(operation, adjacent).get();
              if (adjOperation != BNode.WrRD_validData && super.IsNodeInStage(adjOperation, stages[opStageNum])) {
                interfaces += language.CreateInterface(adjOperation, stages[opStageNum], "");
              }
            }
          }
        }
      }

    if ((this.op_stage_instr.containsKey(BNode.RdMem) | this.op_stage_instr.containsKey(BNode.WrMem)) &&
        stageNum == this.vex_core.GetNodes().get(BNode.RdMem).GetEarliest().asInt()) {
      for (int earlierStage = this.vex_core.GetNodes().get(BNode.RdInstr).GetEarliest().asInt();
           earlierStage < this.vex_core.GetNodes().get(BNode.RdMem).GetEarliest().asInt(); earlierStage++) {
        if (this.op_stage_instr.containsKey(BNode.RdMem))
          interfaces +=
              language.CreateInterface(BNode.GetAdjSCAIEVNode(BNode.RdMem, AdjacentNode.validReq).get(), stages[earlierStage], "");
        if (this.op_stage_instr.containsKey(BNode.WrMem))
          interfaces +=
              language.CreateInterface(BNode.GetAdjSCAIEVNode(BNode.WrMem, AdjacentNode.validReq).get(), stages[earlierStage], "");
      }
    }

    toFile.UpdateContent(filePlugin, interfaces);
    language.CloseBrackets();
  }

  public void IntegrateISAX_BuilPCNodes() {
    int spawnStage = this.vex_core.maxStage + 1;
    String declareIO = "";
    String logic = "";
    if (this.ContainsOpInStage(BNode.WrPC, 0)) {
      declareIO += toFile.tab.repeat(2) + language.CreateInterface(BNode.WrPC, stages[0], "") + toFile.tab.repeat(2) +
                   language.CreateInterface(BNode.WrPC_valid, stages[0], "");
      logic += toFile.tab.repeat(1) + "jumpInFetch.target_PC := io." + language.CreateNodeName(BNode.WrPC, stages[0], "") + ";\n" +
               toFile.tab.repeat(1) + "jumpInFetch.update_PC := io." + language.CreateNodeName(BNode.WrPC_valid, stages[0], "") + ";\n";
    }
    if (this.ContainsOpInStage(BNode.RdPC, 0)) {
      declareIO += toFile.tab.repeat(2) + language.CreateInterface(BNode.RdPC, stages[0], "");
      logic += toFile.tab.repeat(1) + "io." + language.CreateNodeName(BNode.RdPC, stages[0], "") + " := jumpInFetch.current_pc;\n";
    }
    if (this.op_stage_instr.containsKey(BNode.WrPC_spawn)) {
      declareIO += toFile.tab.repeat(2) + language.CreateInterface(BNode.WrPC_spawn, stages[spawnStage], "") + toFile.tab.repeat(2) +
                   language.CreateInterface(BNode.WrPC_spawn_valid, stages[spawnStage], "");
      logic += toFile.tab.repeat(1) + "jumpInFetch.target_PC := io." + language.CreateNodeName(BNode.WrPC_spawn, stages[spawnStage], "") +
               ";\n" + toFile.tab.repeat(1) + "jumpInFetch.update_PC := io." +
               language.CreateNodeName(BNode.WrPC_spawn_valid, stages[spawnStage], "") + ";\n";
    }
    if (this.op_stage_instr.containsKey(BNode.WrPC_spawn) | this.ContainsOpInStage(BNode.WrPC, 0))
      logic += toFile.tab.repeat(1) + "decode.arbitration.flushNext setWhen jumpInFetch.update_PC;\n";
    else if (this.ContainsOpInStage(BNode.RdPC, 0))
      logic += toFile.tab.repeat(1) + "jumpInFetch.update_PC := False;\n";
    String text = "pipeline plug new Area {\n" + toFile.tab.repeat(1) + "import pipeline._\n" + toFile.tab.repeat(1) +
                  "val io = new Bundle {\n" + declareIO + toFile.tab.repeat(1) + "}\n" + logic + "}\n";
    if (!logic.isEmpty())
      toFile.UpdateContent(filePlugin, text);
  }

  /*************************************
   * Build body for ONE stage.
   *************************************/
  public void IntegrateISAX_BuildBody(PipelineStage stage) {
    String thisStagebuild = "";
    String clause = "";

    // Simple assigns
    for (SCAIEVNode operation : op_stage_instr.keySet())
      if (!operation.isAdj()) {
        if ((stage.getPrev().size() > 0) && op_stage_instr.get(operation).containsKey(stage) &&
            (!operation.equals(BNode.WrRD) && !operation.equals(BNode.WrMem) &&
             !operation.equals(BNode.RdMem))) { // it cannot be spawn, as stage does not go to max_stage + 1
          if (!FNode.HasSCAIEVNode(operation.name) ||
              stage.getStagePos() >= this.vex_core.GetNodes().get(operation).GetEarliest().asInt()) {
            thisStagebuild +=
                language.CreateAssignToISAX(operation, stage, "", (operation.equals(BNode.WrStall) || operation.equals(BNode.WrFlush)));
          }
        }
      }

    // WrPC valid clause
    if (ContainsOpInStage(BNode.WrPC, stage)) {
      thisStagebuild += "jumpInterface_" + stage.getStagePos() + ".valid := io." + language.CreateNodeName(BNode.WrPC_valid, stage, "") +
                        " && !arbitration.isStuckByOthers\n";
      thisStagebuild += "arbitration.flushNext setWhen (jumpInterface_" + stage.getStagePos() + ".valid);";
    }
    if (!thisStagebuild.isEmpty()) {
      thisStagebuild += "\n\n\n";
      toFile.UpdateContent(filePlugin, thisStagebuild);
    }
    IntegrateISAX_WrRDBuild(stage);

    if (stage.getStagePos() == this.vex_core.GetNodes().get(BNode.RdMem).GetEarliest().asInt() &&
        (op_stage_instr.containsKey(BNode.WrMem) || op_stage_instr.containsKey(BNode.WrMem_spawn) ||
         op_stage_instr.containsKey(BNode.RdMem) || op_stage_instr.containsKey(BNode.RdMem_spawn)))
      IntegrateISAX_MemBuildBody(stage);
  }

  // RD/WR Memory
  public void IntegrateISAX_MemBuildBody(PipelineStage stage) {
    int memStageNum = this.vex_core.GetNodes().get(BNode.RdMem).GetLatest().asInt();
    int spawnStageNum = this.vex_core.maxStage + 1;
    if (stage.getStagePos() == memStageNum &&
        ((op_stage_instr.containsKey(BNode.WrMem) || op_stage_instr.containsKey(BNode.WrMem_spawn) ||
          op_stage_instr.containsKey(BNode.RdMem) || op_stage_instr.containsKey(BNode.RdMem_spawn)))) {
      String response = "";
      String rdData = "";
      // Repair original file
      // toFile.ReplaceContent(pathRISCV+"/plugin/DBusSimplePlugin.scala","when(!stages.dropWhile(_ != execute)", new
      // ToWrite("when(stages.dropWhile(_ != execute).map(_.arbitration.isValid).orR){",false,true,""));

      // Add RESPONSE State and default values for reads
      if (this.ContainsOpInStage(BNode.RdMem, memStageNum) || this.op_stage_instr.containsKey(BNode.RdMem_spawn)) {
        response += ", RESPONSE";
      }
      if (this.ContainsOpInStage(BNode.RdMem, memStageNum)) {
        rdData += "io." + language.CreateNodeName(BNode.RdMem, stages[memStageNum], "") + " := 0\n";
      }

      if (this.ContainsOpInStage(BNode.RdMem_spawn, stages[spawnStageNum])) {
        rdData += "io." + language.CreateNodeName(BNode.RdMem_spawn, stages[spawnStageNum], "") + " := 0\n";
      }

      // Do we need Rd/Wr Mem custom address?
      boolean rdaddr = !language.CreateClauseAddr(ISAXes, BNode.RdMem, stage, "").isEmpty();
      boolean wraddr = !language.CreateClauseAddr(ISAXes, BNode.WrMem, stage, "").isEmpty();
      String rdaddrText = "";
      String wraddrText = "";
      String spawnaddr = "";
      if (rdaddr)
        rdaddrText += "			               when(io." +
                      language.CreateNodeName(BNode.RdMem_addr_valid, stages[memStageNum], "") +
                      ") { // set to 0 if no ISAX needs it and removed by synth tool \n"
                      + "			                  dBusAccess.cmd.address := io." +
                      language.CreateNodeName(BNode.RdMem_addr, stages[memStageNum], "") + "\n"
                      + "			               }\n";
      if (wraddr)
        wraddrText += "			               when(io." +
                      language.CreateNodeName(BNode.WrMem_addr_valid, stages[memStageNum], "") +
                      ") { // set to 0 if no ISAX needs it and removed by synth tool \n"
                      + "			                  dBusAccess.cmd.address := io." +
                      language.CreateNodeName(BNode.WrMem_addr, stages[memStageNum], "") + "\n"
                      + "			               }\n";
      if (op_stage_instr.containsKey(BNode.WrMem_spawn) || op_stage_instr.containsKey(BNode.RdMem_spawn))
        spawnaddr += "			               when(io." +
                     language.CreateNodeName(BNode.RdMem_spawn_validReq, stages[spawnStageNum], "") + ") {\n"
                     + "			                  dBusAccess.cmd.address := io." +
                     language.CreateNodeName(BNode.RdMem_spawn_addr, stages[spawnStageNum], "") + "\n"
                     + "			               }\n";

      // Is it a write or a read? Is it a valid mem ? What is the transfer size?
      String writeText = "False";
      String valid = "";
      String valid_1 = "False";
      String spawnvalid = "";
      String invalidTransfer = "";
      String transferSize = "";
      String defaultSigs = "";

      if (op_stage_instr.containsKey(BNode.WrMem)) {
        writeText = "io." + language.CreateNodeName(BNode.WrMem_validReq, stages[memStageNum], "");
        valid += "io." + language.CreateNodeName(BNode.WrMem_validReq, stages[memStageNum], "");
        valid_1 = "io." + language.CreateNodeName(BNode.WrMem_validReq, stages[memStageNum - 1], "");
      }
      if (op_stage_instr.containsKey(BNode.WrMem_spawn))
        writeText =
            language.OpIfNEmpty(writeText, " || ") + "io." + language.CreateNodeName(BNode.WrMem_spawn_write, stages[spawnStageNum], "");
      if (op_stage_instr.containsKey(BNode.RdMem)) {
        valid = language.OpIfNEmpty(valid, " || ") + "io." + language.CreateNodeName(BNode.RdMem_validReq, stages[memStageNum], "");
        valid_1 = language.OpIfNEmpty(valid_1, " || ") + "io." + language.CreateNodeName(BNode.RdMem_validReq, stages[memStageNum - 1], "");
      }

      if (op_stage_instr.containsKey(BNode.WrMem_spawn) || op_stage_instr.containsKey(BNode.RdMem_spawn))
        spawnvalid += " || io." + language.CreateNodeName(BNode.RdMem_spawn_validReq, stages[spawnStageNum], "");

      // Compute write data depending on transfer type
      String writedata = "";
      if (op_stage_instr.containsKey(BNode.WrMem) || op_stage_instr.containsKey(BNode.WrMem_spawn)) {
        if (op_stage_instr.containsKey(BNode.RdMem) || op_stage_instr.containsKey(BNode.RdMem_spawn))
          writedata += "                        dBusAccess.cmd.data   := 0 ;\n";
        if (op_stage_instr.containsKey(BNode.WrMem))
          writedata += "                        when(io." + language.CreateNodeName(BNode.WrMem_validReq, stages[memStageNum], "") + ") {\n"
                       + "                            dBusAccess.cmd.data := io." +
                       language.CreateNodeName(BNode.WrMem, stages[memStageNum], "") + "\n"
                       + "                        }\n";
        if (op_stage_instr.containsKey(BNode.WrMem_spawn))
          writedata += "                        when(io." + language.CreateNodeName(BNode.WrMem_spawn_validReq, stages[spawnStageNum], "") +
                       ") {\n"
                       + "                            dBusAccess.cmd.data := io." +
                       language.CreateNodeName(BNode.WrMem_spawn, stages[spawnStageNum], "") + "\n"
                       + "                        }\n";
      } else
        writedata += "                        dBusAccess.cmd.data   := 0 ;\n";

      // Compute condition to return to IDLE state if no valid memory  transfer
      if (op_stage_instr.containsKey(BNode.WrMem)) {
        invalidTransfer += "io." + language.CreateNodeName(BNode.WrMem_validReq, stages[memStageNum], "");
      }
      if (op_stage_instr.containsKey(BNode.RdMem)) {
        invalidTransfer =
            language.OpIfNEmpty(invalidTransfer, " || ") + "io." + language.CreateNodeName(BNode.RdMem_validReq, stages[memStageNum], "");
      }
      if (op_stage_instr.containsKey(BNode.RdMem_spawn) || op_stage_instr.containsKey(BNode.WrMem_spawn)) {
        invalidTransfer = language.OpIfNEmpty(invalidTransfer, " || ") + "io." +
                          language.CreateNodeName(BNode.RdMem_spawn_validReq, stages[spawnStageNum], "");
        defaultSigs += "io." + language.CreateNodeName(BNode.RdMem_spawn_validResp, stages[spawnStageNum], "") +
                       " := " + this.language.GetDict(DictWords.False) + ";  \n";
      }

      // Compute data size. For common instr this is INSTRUCTION [14:13] , for spawn this is 2
      if (op_stage_instr.containsKey(BNode.RdMem) || op_stage_instr.containsKey(BNode.WrMem))
        transferSize += "                        dBusAccess.cmd.size    := execute.input(INSTRUCTION)(13 downto 12).asUInt	\n";
      if (op_stage_instr.containsKey(BNode.RdMem_spawn) || op_stage_instr.containsKey(BNode.WrMem_spawn))
        transferSize += "                        when(io." +
                        language.CreateNodeName(BNode.RdMem_spawn_validReq, stages[spawnStageNum], "") +
                        ") { dBusAccess.cmd.size    := 2 }\n";

      // Compute response state depending on whether it's a read or a write
      String nextstateRd = "";
      String nextstateWr = "";
      String rdState = "";
      if (op_stage_instr.containsKey(BNode.WrMem)) {
        nextstateWr +=
            "when (io." + language.CreateNodeName(BNode.WrMem_validReq, stages[memStageNum], "") + ") {\n    state := State.IDLE\n}";
        nextstateWr = language.AlignText(language.tab.repeat(7), nextstateWr);
        nextstateWr += "\n";
      }
      if (op_stage_instr.containsKey(BNode.WrMem_spawn)) {
        nextstateWr += "when (io." + language.CreateNodeName(BNode.WrMem_spawn_validReq, stages[spawnStageNum], "") + " && io." +
                       language.CreateNodeName(BNode.WrMem_spawn_write, stages[spawnStageNum], "") + ") {\n    state := State.IDLE\nio." +
                       language.CreateNodeName(BNode.WrMem_spawn_validResp, stages[spawnStageNum], "") + " := True;\n}";
        nextstateWr = language.AlignText(language.tab.repeat(7), nextstateWr);
        nextstateWr += "\n";
      }
      if (op_stage_instr.containsKey(BNode.RdMem) | op_stage_instr.containsKey(BNode.RdMem_spawn)) {
        String condNextState = "";
        String logicInState = "";
        if (op_stage_instr.containsKey(BNode.RdMem)) {
          condNextState += "io." + language.CreateNodeName(BNode.RdMem_validReq, stages[memStageNum], "");
          logicInState += "                        io." + language.CreateNodeName(BNode.RdMem, stages[memStageNum], "") +
                          " := dBusAccess.rsp.data;  \n";
        }
        if (op_stage_instr.containsKey(BNode.RdMem_spawn)) {
          condNextState = this.language.OpIfNEmpty(condNextState, " || ") + "io." +
                          language.CreateNodeName(BNode.RdMem_spawn_validReq, stages[spawnStageNum], "") + " && !io." +
                          language.CreateNodeName(BNode.RdMem_spawn_write, stages[spawnStageNum], "");
          logicInState += "                        io." + language.CreateNodeName(BNode.RdMem_spawn, stages[spawnStageNum], "") +
                          " := dBusAccess.rsp.data;  \n";
          logicInState +=
              "                        when(io." + language.CreateNodeName(BNode.RdMem_spawn_validReq, stages[spawnStageNum], "") + ") {\n "
              + "                            io." + language.CreateNodeName(BNode.RdMem_spawn_validResp, stages[spawnStageNum], "") +
              " := " + this.language.GetDict(DictWords.True) + ";  \n"
              + "                        }";
        }
        nextstateRd += "when (" + condNextState + ") {\n    state := State.RESPONSE\n}";
        nextstateRd = language.AlignText(language.tab.repeat(7), nextstateRd);
        rdState = "                is(State.RESPONSE){  \n"
                  + "                    when(dBusAccess.rsp.valid){ \n"
                  + "                        state := State.IDLE\n" + logicInState + "                    } .otherwise {\n"
                  + "                        memory.arbitration.haltItself := " + this.language.GetDict(DictWords.True) + "\n"
                  + "                    }\n"
                  + "                }\n";
      }

      // Entire FSM logic puzzled together
      String logicText = "            val State = new SpinalEnum{\n"
                         + "                val IDLE, CMD " + response + " = newElement()\n"
                         + "            }\n"
                         + "            val state = RegInit(State.IDLE)\n"
                         + "            \n"
                         //	+ "            io."+language.CreateNodeName(BNode.RdMem_validResp, memStage, "")+" := False\n"
                         + "	          " + rdData + "            // Define some default values for memory FSM\n"
                         + "            dBusAccess.cmd.valid := False\n"
                         + "            dBusAccess.cmd.write := False\n"
                         + "            dBusAccess.cmd.size := 0 \n"
                         + "            dBusAccess.cmd.address.assignDontCare() \n"
                         + "            dBusAccess.cmd.data.assignDontCare() \n"
                         + "            dBusAccess.cmd.writeMask.assignDontCare()\n" + defaultSigs + "            \n"
                         + "            val ldst_in_decode = Bool()		    \n"
                         + "            when(state !==  State.IDLE) {\n"
                         + "                when(ldst_in_decode) {\n"
                         + "                    decode.arbitration.haltItself := True;\n"
                         + "                }\n"
                         + "            }\n"
                         + "            ldst_in_decode := ((" + valid_1 + ") && decode.input(IS_ISAX))\n"
                         + "            switch(state){\n"
                         + "                is(State.IDLE){\n"
                         + "                    when(ldst_in_decode && decode.arbitration.isFiring " + spawnvalid + ") { \n"
                         + "                        state := State.CMD\n"
                         + "                    }\n"
                         + "                }\n"
                         + "                is(State.CMD){\n"
                         + "                    when(~(" + invalidTransfer + ")) {\n"
                         + "                            state := State.IDLE\n"
                         + "                    }.otherwise {\n"
                         + "                     when(execute.arbitration.isValid " + spawnvalid + ") {\n"
                         + "                        dBusAccess.cmd.valid   := True \n" + transferSize + "\n" + writedata + "\n"
                         + "                        dBusAccess.cmd.write   := " + writeText + "\n"
                         + "                        dBusAccess.cmd.address := execute.input(SRC_ADD).asUInt  \n" + wraddrText +
                         rdaddrText + spawnaddr + "                        when(dBusAccess.cmd.ready) {// Next state \n" + nextstateWr +
                         "\n" + nextstateRd + "\n"
                         + "                        }.otherwise {\n"
                         + "                            execute.arbitration.haltItself := True\n"
                         + "                        }\n"
                         + "                     }\n"
                         + "                    }\n"
                         + "                }\n" + rdState + "            }\n"
                         + "";

      // Add rdAddr needed if user does not implement it. Will be removed by synth tool if user implements it, because it won't be used
      // within SCAL anyway
      if (op_stage_instr.containsKey(BNode.WrMem_spawn) || op_stage_instr.containsKey(BNode.RdMem_spawn)) {
        logicText += "io." +
                     language.CreateFamNodeName(BNode.GetAdjSCAIEVNode(BNode.WrMem_spawn, AdjacentNode.defaultAddr).get(),
                                                stages[spawnStageNum], "", true) +
                     " := execute.input(SRC_ADD).asUInt ; \n ";
      }
      toFile.UpdateContent(filePlugin, logicText);
    }
  }

  public void IntegrateISAX_WrRDBuild(PipelineStage stage) {
    // COMMON WrRD, NO SPAWN :
    if (ContainsOpInStage(BNode.WrRD, stage)) {
      String stage_valid = "";
      if (stage.getStagePos() == 4)
        stage_valid =
            " && !writeBack.input(BYPASSABLE_MEMORY_STAGE) && !writeBack.input(BYPASSABLE_EXECUTE_STAGE) && writeBack.input(IS_ISAX)";
      if (stage.getStagePos() == 3)
        stage_valid = " && memory.input(BYPASSABLE_MEMORY_STAGE) && !memory.input(BYPASSABLE_EXECUTE_STAGE) && memory.input(IS_ISAX)";
      if (stage.getStagePos() == 2)
        stage_valid = " && execute.input(BYPASSABLE_EXECUTE_STAGE) && execute.input(IS_ISAX)";

      toFile.UpdateContent(filePlugin, "when(io." + language.CreateNodeName(BNode.WrRD_valid, stage, "") + ") {\n");
      toFile.nrTabs++;
      toFile.UpdateContent(filePlugin, vexInterfaceStageNames[stage.getStagePos()] + ".output(REGFILE_WRITE_DATA) := io." +
                                           language.CreateNodeName(BNode.WrRD, stage, "") + ";\n");
      toFile.nrTabs--;
      toFile.UpdateContent(filePlugin, "}\n");

      toFile.UpdateContent(filePlugin, "when(!io." + language.CreateNodeName(BNode.WrRD_valid, stage, "") + stage_valid + " ) {\n");
      toFile.nrTabs++;
      toFile.UpdateContent(filePlugin, vexInterfaceStageNames[stage.getStagePos()] + ".output(REGFILE_WRITE_VALID) := False;\n");
      toFile.nrTabs--;
      toFile.UpdateContent(filePlugin, "}\n");
    }

    // SPAWN
    int spawnStageNum = this.vex_core.maxStage + 1;
    if (op_stage_instr.containsKey(BNode.WrRD_spawn) &&
        stage.getStagePos() == this.vex_core.GetNodes().get(BNode.WrRD).GetLatest().asInt()) {
      String logic = "";
      logic += "when (io." + language.CreateNodeName(BNode.WrRD_spawn_valid, stages[spawnStageNum], "") + ") {\n " + toFile.tab +
               vexInterfaceStageNames[stage.getStagePos()] + ".output(INSTRUCTION) := ((11 downto 7) ->io." +
               language.CreateNodeName(BNode.WrRD_spawn_addr, stages[spawnStageNum], "") + ", default -> false)\n" + toFile.tab +
               vexInterfaceStageNames[stage.getStagePos()] + ".arbitration.isRegFSpawn := True\n" + toFile.tab +
               vexInterfaceStageNames[stage.getStagePos()] + ".output(REGFILE_WRITE_DATA) := io." +
               language.CreateNodeName(BNode.WrRD_spawn, stages[spawnStageNum], "") + "\n" + toFile.tab + "}\n"
               + "io." + language.CreateNodeName(BNode.WrRD_spawn_validResp, stages[spawnStageNum], "") + " := True;\n"
               + "io." + language.CreateNodeName(BNode.WrRD_spawn_allowed, stages[spawnStageNum], "") + " := True;\n";
      toFile.UpdateContent(filePlugin, logic);
    }
  }

  /**
   * Function for updating configuration file and adding new instructions
   *
   */
  private void IntegrateISAX_UpdateConfigFile() {

    String filePath = pathRISCV + "/demo"
                      + "/" + baseConfig + ".scala";
    toFile.AddFile(filePath, false);
    logger.debug("INTEGRATE. Updating " + filePath);

    String lineToBeInserted = "new " + extension_name + "(),";

    toFile.UpdateContent(filePath, "plugins = List(", new ToWrite(lineToBeInserted, false, true, ""));
    if (this.vex_core.GetNodes().get(BNode.RdRS1).GetLatest().asInt() == 3) // Disable MulPlugin for 4-stage Vex (cannot build Vex
                                                                            // otherwise)
      toFile.ReplaceContent(filePath, "new MulPlugin,", new ToWrite("//new MulPlugin, // SCAIEV Paper", false, true, ""));
    // toFile.ReplaceContent(filePath,"new DivPlugin,", new ToWrite("//new DivPlugin, // SCAIEV Paper",false,true,""));
    toFile.ReplaceContent(filePath, "earlyBranch = false,", new ToWrite("earlyBranch = true, // SCAIEV Paper", false, true, ""));
    if (vex_core.maxStage > 3)
      toFile.UpdateContent(filePath, "plugins = List(", new ToWrite("withWriteBackStage = true, //SCAIEV Paper", false, true, "", true));
    else
      toFile.UpdateContent(filePath, "plugins = List(", new ToWrite("withWriteBackStage = false, //SCAIEV Paper", false, true, "", true));
    if (vex_core.maxStage > 2)
      toFile.UpdateContent(filePath, "plugins = List(", new ToWrite("withMemoryStage    = true, // SCAIEV Paper", false, true, "", true));
    else
      toFile.UpdateContent(filePath, "plugins = List(", new ToWrite("withMemoryStage    = false, // SCAIEV Paper", false, true, "", true));
    if (this.vex_core.GetNodes().get(BNode.RdRS1).GetLatency() == 0)
      toFile.ReplaceContent(filePath, "regFileReadyKind = plugin.SYNC",
                            new ToWrite("regFileReadyKind = plugin.ASYNC, //SCAIEV Paper", false, true, ""));
    if (this.vex_core.GetNodes().get(BNode.RdRS1).GetEarliest().asInt() == 2)
      toFile.UpdateContent(filePath, "regFileReadyKind = plugin.SYNC", new ToWrite("readInExecute = true,", false, true, ""));
  }

  private void IntegrateISAX_UpdateMemFile() {
    String filePath = pathRISCV + "/plugin"
                      + "/"
                      + "DBusSimplePlugin.scala";
    toFile.AddFile(filePath, false);
    logger.debug("INTEGRATE. Updating " + filePath);
    String lineToBeInserted = "";
    boolean memory_required = false;
    if (this.op_stage_instr.containsKey(BNode.WrMem) || this.op_stage_instr.containsKey(BNode.RdMem))
      memory_required = true;
    if (this.op_stage_instr.containsKey(BNode.WrMem_spawn) || this.op_stage_instr.containsKey(BNode.RdMem_spawn) || memory_required) {
      lineToBeInserted = "// when(stages.dropWhile(_ != execute).map(_.arbitration.isValid).orR){ // scaiev make sure spawn works";
      toFile.ReplaceContent(filePath, "when(!stages.dropWhile(_ != execute).map(_.arbitration.isValid).orR",
                            new ToWrite(lineToBeInserted, false, true, ""));
      lineToBeInserted = "// } // scaiev make sure spawn works";
      toFile.ReplaceContent(
          filePath, "}", new ToWrite(lineToBeInserted, true, false, "when(!stages.dropWhile(_ != execute).map(_.arbitration.isValid).orR"));
    }
  }

  /**
   * Function for updating arbitration for WrRD Spawn
   *
   */
  private void IntegrateISAX_UpdateRegFforSpawn() {
    String filePath = pathRISCV + "/plugin"
                      + "/"
                      + "RegFilePlugin.scala";
    toFile.AddFile(filePath, false);
    if (this.op_stage_instr.containsKey(BNode.WrRD_spawn)) {
      logger.debug("INTEGRATE. Updating " + filePath);
      String lineToBeInserted = "regFileWrite.valid :=  output(REGFILE_WRITE_VALID) && arbitration.isFiring || arbitration.isRegFSpawn "
                                + "// Logic updated for Spawn WrRD ISAX";

      toFile.ReplaceContent(filePath, "regFileWrite.valid :=", new ToWrite(lineToBeInserted, false, true, ""));
    }
  }

  /**
   * Function for updating RegF for  WrRD Spawn
   *
   */
  private void IntegrateISAX_UpdateArbitrationFile() {
    String filePath = pathRISCV + "/Stage.scala";
    toFile.AddFile(filePath, false);
    if (this.op_stage_instr.containsKey(BNode.WrRD_spawn)) {
      logger.debug("INTEGRATE. Updating " + filePath);
      String lineToBeInserted = " val isRegFSpawn     = False    //Inform if an instruction using the spawn construction is ready to "
                                + "write its result in the RegFile\n";

      toFile.UpdateContent(filePath, "val arbitration =", new ToWrite(lineToBeInserted, false, true, ""));
    }
  }

  /**
   * Function for updating IFetch
   *
   */
  private void IntegrateISAX_UpdateIFetchFile() {
    String tab = toFile.tab;
    String filePath = pathRISCV + "/plugin"
                      + "/"
                      + "Fetcher.scala";
    toFile.AddFile(filePath, false);
    logger.debug("INTEGRATE. Updating " + filePath);
    String lineToBeInserted = "";
    if (this.op_stage_instr.containsKey(BNode.WrPC_spawn) ||
        (this.op_stage_instr.containsKey(BNode.WrPC) && this.op_stage_instr.get(BNode.WrPC).containsKey(0)) ||
        (this.op_stage_instr.containsKey(BNode.RdPC) && this.op_stage_instr.get(BNode.RdPC).containsKey(0))) {
      lineToBeInserted = "var jumpInFetch: JumpInFetch = null\n"
                         + "override def createJumpInFetchInterface(): JumpInFetch = {\n" + tab + "assert(jumpInFetch == null)\n" + tab +
                         "jumpInFetch = JumpInFetch()\n" + tab + "jumpInFetch\n"
                         + "}";
      toFile.UpdateContent(filePath, "class FetchArea", new ToWrite(lineToBeInserted, false, true, "", true));

      lineToBeInserted =
          "val predictionBuffer : Boolean = true) extends Plugin[VexRiscv] with JumpService with IBusFetcher with JumpInFetchService{ ";
      toFile.ReplaceContent(filePath, "val predictionBuffer : Boolean = true) extends Plugin[VexRiscv] with JumpService with IBusFetcher{",
                            new ToWrite(lineToBeInserted, false, true, ""));
    }
    if (this.op_stage_instr.containsKey(BNode.WrPC_spawn) ||
        (this.op_stage_instr.containsKey(BNode.WrPC) && this.op_stage_instr.get(BNode.WrPC).containsKey(0))) {
      lineToBeInserted = "when (jumpInFetch.update_PC){\n" + tab + "correction := True\n" + tab + "pc := jumpInFetch.target_PC\n" + tab +
                         "flushed := False\n"
                         + "}\n";
      toFile.UpdateContent(filePath, "when(booted && (output.ready || correction || pcRegPropagate)){",
                           new ToWrite(lineToBeInserted, false, true, "", true));
    }

    if (this.op_stage_instr.containsKey(BNode.RdPC) && this.op_stage_instr.get(BNode.RdPC).containsKey(0)) {
      lineToBeInserted = "jumpInFetch.current_pc := pcReg;";
      toFile.UpdateContent(filePath, "output.payload := pc", new ToWrite(lineToBeInserted, false, true, ""));
    }
  }

  /**
   * Function for updating DH Vex Unit. Needed for ISAXes writing regFile. This is one ay of solving DH issue with ISAXes when supporting
   * ISAXes writing in different stages
   *
   */
  private void IntegrateISAX_UpdateDHFile() {
    String filePath = pathRISCV + "/plugin"
                      + "/"
                      + "HazardSimplePlugin.scala";
    toFile.AddFile(filePath, false);
    if (this.op_stage_instr.containsKey(BNode.WrRD))
      toFile.ReplaceContent(filePath, "when(stage.arbitration.isValid && stage.input(REGFILE_WRITE_VALID))",
                            new ToWrite("when(stage.arbitration.isValid && stage.output(REGFILE_WRITE_VALID)) {", false, true, ""));
  }

  /**
   * Function for updating Services
   *
   */
  private void IntegrateISAX_UpdateServiceFile() {
    String filePath = pathRISCV + "/Services.scala";
    toFile.AddFile(filePath, false);
    String tab = toFile.tab;
    logger.debug("INTEGRATE. Updating " + filePath);
    String lineToBeInserted = "";

    if (this.op_stage_instr.containsKey(BNode.WrPC_spawn) ||
        (this.op_stage_instr.containsKey(BNode.WrPC) && this.op_stage_instr.get(BNode.WrPC).containsKey(0)) ||
        (this.op_stage_instr.containsKey(BNode.RdPC) && this.op_stage_instr.get(BNode.RdPC).containsKey(0))) {
      lineToBeInserted = "case class JumpInFetch() extends Bundle {\n" + tab + "val update_PC  = Bool\n" + tab +
                         "val target_PC =  UInt(32 bits)\n" + tab + "val current_pc = UInt(32 bits)\n"
                         + "}\n"
                         + "trait JumpInFetchService {\n" + tab + "def createJumpInFetchInterface() : JumpInFetch\n"
                         + "}\n";
      toFile.UpdateContent(filePath, "trait JumpService{", new ToWrite(lineToBeInserted, false, true, "", true));
    }
  }

  private boolean ContainsOpInStage(SCAIEVNode operation, int stage) { return this.ContainsOpInStage(operation, stages[stage]); }
  private boolean ContainsOpInStage(SCAIEVNode operation, PipelineStage stage) {
    return op_stage_instr.containsKey(operation) && op_stage_instr.get(operation).containsKey(stage);
  }

  private void ConfigVex() {
    this.PopulateNodesMap();
    Module newModule = new Module();
    newModule.name = extension_name;
    newModule.file = pathRISCV + "/plugin" + extension_name + ".scala";
    this.fileHierarchy.put(extension_name, newModule);
    int spawnStage = this.vex_core.maxStage + 1;

    spawnStages.put(BNode.RdMem_spawn, this.vex_core.GetNodes().get(BNode.RdMem).GetEarliest().asInt());
    spawnStages.put(BNode.WrMem_spawn, this.vex_core.GetNodes().get(BNode.RdMem).GetEarliest().asInt());
    spawnStages.put(BNode.WrPC_spawn, this.vex_core.GetNodes().get(BNode.WrPC).GetEarliest().asInt());
    spawnStages.put(BNode.WrRD_spawn, this.vex_core.GetNodes().get(BNode.WrRD).GetLatest().asInt());

    this.PutNode("UInt", "jumpInFetch.target_PC", "", BNode.WrPC, stages[0]);
    this.PutNode("Bool", "jumpInFetch.update_PC", "", BNode.WrPC_valid, stages[0]);
    this.PutNode("UInt", "jumpInFetch.current_pc", "", BNode.RdPC, stages[0]);
    this.PutNode("UInt", "jumpInFetch.target_PC", "", BNode.WrPC_spawn, stages[spawnStage]);
    this.PutNode("Bool", "jumpInFetch.update_PC", "", BNode.WrPC_spawn_valid, stages[spawnStage]);
    for (int stageNum = 1; stageNum < vexInterfaceStageNames.length; ++stageNum) {
      String stageName = vexInterfaceStageNames[stageNum];
      // assign rdInstr = vexInterfaceStageNames[stage]+".input(INSTRUCTION)";
      this.PutNode("Bits", stageName + ".input(INSTRUCTION)", stageName, BNode.RdInstr, stages[stageNum]);
      this.PutNode("UInt", stageName + ".input(PC)", stageName, BNode.RdPC, stages[stageNum]);
      this.PutNode("Bool", stageName + ".arbitration.isStuck || (!" + stageName + ".arbitration.isValid)", stageName, BNode.RdStall,
                   stages[stageNum]);
      this.PutNode("Bool", stageName + ".arbitration.isValid", stageName, BNode.RdInStageValid, stages[stageNum]);
      this.PutNode("Bool", stageName + ".arbitration.isFlushed", stageName, BNode.RdFlush,
                   stages[stageNum]); //|| (!"+vexInterfaceStageNames[stage]+".arbitration.isValid)"
      this.PutNode("Bool", stageName + ".arbitration.haltByOther", stageName, BNode.WrStall, stages[stageNum]);
      if (stageNum > 0)
        this.PutNode("Bool", stageName + ".arbitration.flushIt", stageName, BNode.WrFlush, stages[stageNum]);
      if (this.vex_core.GetNodes().get(BNode.WrRD).GetLatest().asInt() >= stageNum &&
          this.vex_core.GetNodes().get(BNode.WrRD).GetEarliest().asInt() <= stageNum) {
        this.PutNode("Bits", stageName + ".output(REGFILE_WRITE_DATA)", stageName, BNode.WrRD, stages[stageNum]);
        this.PutNode("Bool", stageName + ".output(REGFILE_WRITE_VALID)", stageName, BNode.WrRD_valid, stages[stageNum]);
      }
    }
    this.PutNode("Bool", "", vexInterfaceStageNames[1], BNode.WrFlush, stages[0]);

    this.PutNode("Bool", vexInterfaceStageNames[1] + ".arbitration.flushNext", vexInterfaceStageNames[1], BNode.WrFlush, stages[0]);

    for (int stageNum = 1; stageNum < vexInterfaceStageNames.length; ++stageNum) {
      if (stageNum > 1) {
        this.PutNode("Bits", vexInterfaceStageNames[stageNum] + ".input(RS1)", vexInterfaceStageNames[stageNum], BNode.RdRS1,
                     stages[stageNum]);
        this.PutNode("Bits", vexInterfaceStageNames[stageNum] + ".input(RS2)", vexInterfaceStageNames[stageNum], BNode.RdRS2,
                     stages[stageNum]);
      }
      if (stageNum > 0) {
        this.PutNode("UInt", "jumpInterface_" + stageNum + ".payload", "memory", BNode.WrPC, stages[stageNum]);
        this.PutNode("Bool", "", "memory", BNode.WrPC_valid, stages[stageNum]);
      }
    }
    // Node = Operation rdrs rdinstr wrrd
    int stageWrRD = this.vex_core.GetNodes().get(BNode.WrRD).GetLatest().asInt();
    this.PutNode("Bits", vexInterfaceStageNames[stageWrRD] + ".output(REGFILE_WRITE_DATA)", vexInterfaceStageNames[stageWrRD],
                 BNode.WrRD_spawn, stages[spawnStage]);
    this.PutNode("Bool", vexInterfaceStageNames[stageWrRD] + ".output(REGFILE_WRITE_VALID)", vexInterfaceStageNames[stageWrRD],
                 BNode.WrRD_spawn_valid, stages[spawnStage]);
    this.PutNode("Bits", vexInterfaceStageNames[stageWrRD] + ".output(INSTRUCTION)", vexInterfaceStageNames[stageWrRD],
                 BNode.WrRD_spawn_addr, stages[spawnStage]);
    this.PutNode("Bool", "True", vexInterfaceStageNames[stageWrRD], BNode.WrRD_spawn_validResp, stages[spawnStage]);
    this.PutNode("Bool", "True", vexInterfaceStageNames[stageWrRD], BNode.WrRD_spawn_allowed, stages[spawnStage]);

    int stageMem = this.vex_core.GetNodes().get(BNode.RdMem).GetLatest().asInt();
    this.PutNode("Bits", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.RdMem, stages[stageMem]);
    //			this.PutNode("Bool", vexInterfaceStageNames[stageMem] +"", vexInterfaceStageNames[stageMem], BNode.RdMem_validResp,
    // stages[stageMem]);
    this.PutNode("Bits", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.WrMem, stages[stageMem]);
    this.PutNode("Bool", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.WrMem_validReq, stages[stageMem]);
    this.PutNode("Bool", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.RdMem_validReq, stages[stageMem]);
    this.PutNode("Bool", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.WrMem_validReq,
                 stages[stageMem - 1]);
    this.PutNode("Bool", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.RdMem_validReq,
                 stages[stageMem - 1]);
    this.PutNode("Bool", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.WrMem_validReq,
                 stages[stageMem - 2]);
    this.PutNode("Bool", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.RdMem_validReq,
                 stages[stageMem - 2]);
    //		 	this.PutNode("Bool", vexInterfaceStageNames[stageMem] +"", vexInterfaceStageNames[stageMem], BNode.RdMem_validResp,
    // stages[stageMem]);
    this.PutNode("UInt", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.RdMem_addr, stages[stageMem]);
    this.PutNode("UInt", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.RdMem_size, stages[stageMem]);
    this.PutNode("Bool", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.RdMem_addr_valid, stages[stageMem]);
    this.PutNode("UInt", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.WrMem_addr, stages[stageMem]);
    this.PutNode("UInt", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.WrMem_size, stages[stageMem]);
    this.PutNode("Bool", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.WrMem_addr_valid, stages[stageMem]);

    this.PutNode("Bits", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.RdMem_spawn, stages[spawnStage]);
    this.PutNode("Bool", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.RdMem_spawn_validResp,
                 stages[spawnStage]);
    this.PutNode("Bits", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.WrMem_spawn, stages[spawnStage]);
    this.PutNode("Bool", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.RdMem_spawn_validReq,
                 stages[spawnStage]);
    this.PutNode("Bool", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.WrMem_spawn_validReq,
                 stages[spawnStage]);
    this.PutNode("Bool", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.WrMem_spawn_validResp,
                 stages[spawnStage]);
    this.PutNode("UInt", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.RdMem_spawn_addr,
                 stages[spawnStage]);
    this.PutNode("UInt", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.WrMem_spawn_addr,
                 stages[spawnStage]);
    this.PutNode("UInt", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.RdMem_spawn_defaultAddr,
                 stages[spawnStage]);
    this.PutNode("UInt", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.WrMem_spawn_defaultAddr,
                 stages[spawnStage]);
    this.PutNode("UInt", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.RdMem_spawn_size,
                 stages[spawnStage]);
    this.PutNode("UInt", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.WrMem_spawn_size,
                 stages[spawnStage]);
    this.PutNode("Bool", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.WrMem_spawn_write,
                 stages[spawnStage]);
    this.PutNode("Bool", vexInterfaceStageNames[stageMem] + "", vexInterfaceStageNames[stageMem], BNode.RdMem_spawn_write,
                 stages[spawnStage]);

    PipelineStage startSpawnStage = this.vex_core.GetStartSpawnStages().asList().get(0);
    assert (startSpawnStage != null);
    this.PutNode("Bool",
                 vexInterfaceStageNames[stageMem] + ".arbitration.isFiring || io." +
                     language.CreateNodeName(BNode.WrStall, startSpawnStage, ""),
                 vexInterfaceStageNames[startSpawnStage.getStagePos()], BNode.ISAX_spawnAllowed, startSpawnStage);
  }
}
