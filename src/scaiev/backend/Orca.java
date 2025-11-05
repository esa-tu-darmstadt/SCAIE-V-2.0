package scaiev.backend;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.coreconstr.Core;
import scaiev.coreconstr.CoreNode;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.util.FileWriter;
import scaiev.util.ToWrite;
import scaiev.util.VHDL;

class Parse {
  public static String declare = "architecture";
  public static String behav = "begin";
  // public String behav = "end architecture";
}
public class Orca extends CoreBackend {
  // logging
  protected static final Logger logger = LogManager.getLogger();

  // TODO: Support for Mem_*_size, defaultAddr

  public String tab = "    ";
  HashSet<String> AddedIValid = new HashSet<String>();

  public String pathCore = "CoresSrc/ORCA";
  public String getCorePathIn() { return pathCore; }
  public String pathORCA = "ip/orca/hdl";
  private Core orca_core;
  private HashMap<String, SCAIEVInstr> ISAXes;
  private HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  private PipelineStage[] stages;
  private FileWriter toFile = new FileWriter(pathCore);
  private String extension_name;
  private VHDL language = null;
  private String topModule = "orca";

  private SCAIEVNode ISAX_FWD_ALU = new SCAIEVNode("ISAX_FWD_ALU", 1, false);
  SCAIEVNode ISAX_REG_reg = new SCAIEVNode("ISAX_REG", 1, false);
  SCAIEVNode is_ISAX = new SCAIEVNode("is_ISAX", 1, true);
  SCAIEVNode ISAX_frwrd_to_stage1_ready = new SCAIEVNode("ISAX_frwrd_to_stage1_ready", 1, false);
  SCAIEVNode nodePCCorrValid = new SCAIEVNode("isax_tostage1_pc_correction", 1, true);

  @Override
  public void Prepare(HashMap<String, SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                      Core core, SCALBackendAPI scalAPI, BNode user_BNode) {
    super.Prepare(ISAXes, op_stage_instr, core, scalAPI, user_BNode);
    this.stages = core.GetRootStage().getAllChildren().collect(Collectors.toList()).toArray(n -> new PipelineStage[n]);
    for (int i = 0; i < this.stages.length; ++i)
      assert (this.stages[i].getStagePos() == i);
    this.BNode = user_BNode;
    this.language = new VHDL(user_BNode, toFile, this);
    scalAPI.OverrideEarliestValid(user_BNode.WrRD, new PipelineFront(stages[3]));
    // Ignore WrCommit_spawn for now.
    BNode.WrCommit_spawn.tags.add(NodeTypeTag.noCoreInterface);
    BNode.WrCommit_spawn_validReq.tags.add(NodeTypeTag.noCoreInterface);
    BNode.WrCommit_spawn_validResp.tags.add(NodeTypeTag.noCoreInterface);
    BNode.WrInStageID.tags.add(NodeTypeTag.noCoreInterface);
    BNode.WrInStageID_valid.tags.add(NodeTypeTag.noCoreInterface);

    int stage_execute = core.GetNodes().get(BNode.RdRS1).GetEarliest().asInt() + 1;
    core.PutNode(BNode.RdInStageValid, new CoreNode(0, 0, stage_execute, stage_execute + 1, BNode.RdInStageValid.name));

    scalAPI.SetHasAdjSpawnAllowed(BNode.WrRD_spawn_allowed);
  }

  @Override
  public boolean Generate(HashMap<String, SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                          String extension_name, Core core, String out_path) { // core needed for verification purposes
    // Set variables
    this.orca_core = core;
    this.ISAXes = ISAXes;
    this.op_stage_instr = op_stage_instr;
    this.extension_name = extension_name;
    this.localSignals.clear();
    ConfigOrca();
    language.clk = "clk";
    language.reset = "reset";

    // Only for orca, make instr in stage 2 visible, if not used logic will be redundant but also removed as it has no driver
    if (!ContainsOpInStage(BNode.RdInstr, 2))
      language.UpdateInterface(topModule, BNode.RdInstr, "", stages[2], false, false);
    IntegrateISAX_IOs(topModule);
    IntegrateISAX_NoIllegalInstr();
    IntegrateISAX_WrStall();
    IntegrateISAX_WrFlush();
    IntegrateISAX_RdStall();
    IntegrateISAX_RdFlush();
    IntegrateISAX_WrRD();
    IntegrateISAX_Mem();
    IntegrateISAX_WrPC();
    toFile.WriteFiles(language.GetDictModule(), language.GetDictEndModule(), out_path);

    return true;
  }

  private void IntegrateISAX_IOs(String topModule) { language.GenerateAllInterfaces(topModule, op_stage_instr, ISAXes, orca_core, null); }

  private static class LocalSignalLocation {
    String module;
    String signalName;
    public LocalSignalLocation(String module, String signalName) {
      this.module = module;
      this.signalName = signalName;
    }
    @Override
    public int hashCode() {
      return Objects.hash(module, signalName);
    }
    @Override
    public boolean equals(Object obj) {
      if (this == obj)
        return true;
      if (obj == null)
        return false;
      if (getClass() != obj.getClass())
        return false;
      LocalSignalLocation other = (LocalSignalLocation) obj;
      return Objects.equals(module, other.module) && Objects.equals(signalName, other.signalName);
    }
  }

  private HashSet<LocalSignalLocation> localSignals = new HashSet<>();

  //For WrFlush, WrStall
  /**
   * Create a module-local declaration for WrFlush or WrStall in the given stage,
   *  assigning from the module's input but defaulting to '0' if the SCAIE-V node is not present.
   * @param module the HDL module to add the local declaration and assign to
   * @param wrNode BNode.WrFlush or BNode.WrStall
   * @param stage the stage to look at
   */
  private void addLocalWrSignal(String module, SCAIEVNode wrNode, PipelineStage stage) {
    //Note: location.signalName also identifies the stage.
    LocalSignalLocation localWrSignal = new LocalSignalLocation(module,
        language.CreateLocalNodeName(wrNode, stage, ""));
    if (!localSignals.add(localWrSignal))
      return;
    toFile.UpdateContent(this.ModFile(localWrSignal.module), Parse.declare,
        new ToWrite(language.CreateDeclSig(wrNode, stage, ""), false, true, ""));
    String assignVal = this.ContainsOpInStage(wrNode, stage)
        ? language.CreateNodeName(wrNode, stage, "")
        : "'0'";
    toFile.UpdateContent(this.ModFile(localWrSignal.module), Parse.behav,
                         new ToWrite(localWrSignal.signalName + " <= " + assignVal + ";\n", false, true, ""));
  }

  private void IntegrateISAX_RdStall() {
    Stream<PipelineStage> rdStallStages = Stream.empty();
    if (op_stage_instr.containsKey(BNode.RdStall))
      rdStallStages = Stream.concat(rdStallStages, op_stage_instr.get(BNode.RdStall).keySet().stream());
    if (this.ContainsOpInStage(BNode.WrRD, 4)) // Datahaz. ISAX to standard needs RdStall_3
      rdStallStages = Stream.concat(rdStallStages, Stream.of(stages[3]));
    rdStallStages.distinct().forEach(stage -> {
      if (stage.getStagePos() < 3) {
        addLocalWrSignal(NodeAssignM(BNode.RdStall, stage), BNode.WrStall, stage);
      }
      if (stage.getStagePos() == 2) {
        language.UpdateInterface(NodeAssignM(BNode.RdStall, stage), ISAX_frwrd_to_stage1_ready, "", stage, false, false);
      }
    });
  }

  private void IntegrateISAX_RdFlush() {
    if (op_stage_instr.containsKey(BNode.RdFlush)) {
      for (PipelineStage stage : op_stage_instr.get(BNode.RdFlush).keySet()) {
        addLocalWrSignal(NodeAssignM(BNode.RdFlush, stage), BNode.WrFlush, stage);
      }
    }
  }
  private void IntegrateISAX_WrFlush() {
    String combinedFlushCond = "";
    for (int stagePos = 3; stagePos >= 1; --stagePos) {
      PipelineStage stage = stages[stagePos];
      if (ContainsOpInStage(BNode.WrFlush, stagePos)) {
        addLocalWrSignal(NodeAssignM(BNode.WrFlush, stage), BNode.WrFlush, stage);
        combinedFlushCond += (combinedFlushCond.isEmpty() ? "" : " or ") + language.CreateNodeName(BNode.WrFlush, stage, "");
      }
      if (stagePos == 1 && !combinedFlushCond.isEmpty()) {
        toFile.ReplaceContent(
            this.ModFile("orca_core"), "to_decode_valid                    => ",
            new ToWrite("to_decode_valid                    => to_decode_valid and not (" + combinedFlushCond + "),", false, true, ""));
      }
      if (stagePos == 2 && !combinedFlushCond.isEmpty()) {
        toFile.ReplaceContent(this.ModFile("orca_core"), "quash_decode => ",
                              new ToWrite("quash_decode => to_pc_correction_valid or " + combinedFlushCond + ",", false, true, ""));
      }
      if (stagePos == 3 && !combinedFlushCond.isEmpty()) {
        toFile.ReplaceContent(
            this.ModFile("orca_core"), " to_execute_valid            => to_execute_valid,",
            new ToWrite(" to_execute_valid            => to_execute_valid and not (" + combinedFlushCond + "),", false, true, ""));
      }
    }
    if (ContainsOpInStage(BNode.WrFlush, 1)) {
      String flushCond = language.CreateNodeName(BNode.WrFlush, stages[1], "") + " = '1'";
      toFile.ReplaceContent(
          this.ModFile("decode"), "if reset = '1' or quash_decode = '1' then",
          new ToWrite("if reset = '1' or quash_decode = '1' or " + flushCond + " then", true, false,
              "if from_decode_ready = '1' then"));
    }
  }
  private void IntegrateISAX_NoIllegalInstr() {
    String isISAXSignal = language.CreateLocalNodeName(is_ISAX, stages[3], "");
    //		Function<String,String> makeTextIllegal = tab -> "if ("+isISAXSignal+" = '1') then\n"
    //				+ tab+ "from_opcode_illegal <= '0';\n"
    //				+ "else\n"
    //				+ tab+"from_opcode_illegal <= '1';\n"
    //				+ "end if;";
    String textIllegal = "if (" + isISAXSignal + " = '1') then\n" + tab + "from_opcode_illegal <= '0';\n"
                         + "else\n" + tab + "from_opcode_illegal <= '1';\n"
                         + "end if;";
    toFile.ReplaceContent(this.ModFile("execute"), "from_opcode_illegal <= '1';",
                          new ToWrite(textIllegal, true, false, "when VCP32_OP =>"));
    toFile.ReplaceContent(this.ModFile("execute"), "from_opcode_illegal <= '1';",
                          new ToWrite(textIllegal, true, false, "when VCP64_OP =>"));
    toFile.ReplaceContent(this.ModFile("execute"), "from_opcode_illegal <= '1';", new ToWrite(textIllegal, true, false, "when others =>"));
    String aluSelect = "if (" + isISAXSignal + " = '0') then  --  do not select the ALU for an ISAX \n" + tab + "alu_select <= '1';\n"
                       + "else\n" + tab + "alu_select <= '0';\n"
                       + "end if;";
    toFile.ReplaceContent(this.ModFile("execute"), "alu_select <= '1';", new ToWrite(aluSelect, true, false, "when VCP32_OP =>"));
    toFile.ReplaceContent(this.ModFile("execute"), "alu_select <= '1';", new ToWrite(aluSelect, true, false, "when VCP64_OP =>"));
    toFile.ReplaceContent(this.ModFile("execute"), "alu_select <= '1';", new ToWrite(aluSelect, true, false, "when others =>"));
    toFile.ReplaceContent(this.ModFile("execute"), "alu_select <= '1';", new ToWrite(aluSelect, true, false, "when ALU_OP"));

    language.UpdateInterface("execute", is_ISAX, "", stages[3], false, false);

    toFile.UpdateContent(this.ModFile("execute"), Parse.declare, new ToWrite("signal " + isISAXSignal + ": std_logic;\n", false, true, ""));

    String mem = "";
    int memStage = this.orca_core.GetNodes().get(BNode.WrMem).GetEarliest().asInt();
    if (this.ContainsOpInStage(BNode.WrMem, memStage))
      mem += "," + language.CreateNodeName(BNode.WrMem_validReq, stages[memStage], "");
    if (this.ContainsOpInStage(BNode.RdMem, memStage))
      mem += "," + language.CreateNodeName(BNode.RdMem_validReq, stages[memStage], "");

    toFile.ReplaceContent(this.ModFile("execute"), "process (opcode) is",
                          new ToWrite("process (opcode," + isISAXSignal + mem + ") is", false, true, ""));

    HashSet<String> allISAXes = new HashSet<String>();
    allISAXes.addAll(ISAXes.keySet());
    String addText = language.CreateText1or0(isISAXSignal, language.CreateAllEncoding(allISAXes, ISAXes, "to_execute_instruction"));
    toFile.UpdateContent(this.ModFile("execute"), Parse.behav, new ToWrite(addText, false, true, ""));

    // is_ISAX register in stage 4. Declare & behavior
    toFile.UpdateContent(this.ModFile("execute"), Parse.declare,
                         new ToWrite("signal " + language.CreateRegNodeName(is_ISAX, stages[4], "") + " : std_logic;\n", false, true, ""));
    addText =
        language.CreateTextRegResetStall(language.CreateRegNodeName(is_ISAX, stages[4], ""), isISAXSignal, "(not from_execute_ready)");
    toFile.UpdateContent(this.ModFile("execute"), Parse.behav, new ToWrite(addText, false, true, ""));
  }

  private void IntegrateISAX_WrStall() {
    HashMap<Integer, String> readySigs = new HashMap<Integer, String>();
    readySigs.put(1, "decode_to_ifetch_ready");
    readySigs.put(2, "execute_to_decode_ready");
    HashMap<Integer, String> readyReplace = new HashMap<Integer, String>();
    readyReplace.put(1, "to_ifetch_ready             =>");
    readyReplace.put(2, "to_decode_ready              =>");

    if (this.ContainsOpInStage(BNode.WrStall, 0) || this.ContainsOpInStage(BNode.WrFlush, 0)) {
      String addStallClause = "";
      if (this.ContainsOpInStage(BNode.WrStall, 0))
        addStallClause += "not (" + language.CreateNodeName(BNode.WrStall, stages[0], "") + ") and ";
      if (this.ContainsOpInStage(BNode.WrFlush, 0))
        addStallClause += "not (" + language.CreateNodeName(BNode.WrFlush, stages[0], "") + ") and ";
      toFile.UpdateContent(this.ModFile("orca_core"), Parse.behav,
                           new ToWrite("ISAX_to_1_ready <= " + addStallClause + " " + readySigs.get(1) + ";", false, true, ""));
      toFile.UpdateContent(this.ModFile("orca_core"), Parse.declare, new ToWrite("signal ISAX_to_1_ready: std_logic;\n", false, true, ""));
      toFile.ReplaceContent(this.ModFile("orca_core"), readyReplace.get(1),
                            new ToWrite(readyReplace.get(1) + "ISAX_to_1_ready,\n", false, true, ""));

      String addtext = " to_ifetch_pause_ifetch <= " + language.CreateNodeName(BNode.WrStall, stages[0], "") +
                       " or ((not (from_decode_incomplete_instruction and ifetch_idle)) and from_execute_pause_ifetch);\n";
      toFile.ReplaceContent(this.ModFile("orca_core"), "to_ifetch_pause_ifetch <=", new ToWrite(addtext, false, true, "", true));
    }

    if (this.ContainsOpInStage(BNode.WrStall, 1) || this.ContainsOpInStage(BNode.WrFlush, 1)) {
      String noStallCond = "";
      if (this.ContainsOpInStage(BNode.WrStall, 1))
        noStallCond += "not (" + language.CreateNodeName(BNode.WrStall, stages[1], "") + ")";
      if (this.ContainsOpInStage(BNode.WrFlush, 1))
        noStallCond += (noStallCond.isEmpty()?"":" and ") + "not (" + language.CreateNodeName(BNode.WrFlush, stages[1], "") + ")";
      //String addtext_ready = "from_decode_ready <= " + noStallCond + " and (to_stage1_ready or (not from_stage1_valid) );";
      //toFile.ReplaceContent(this.ModFile("decode"), "from_decode_ready <=", new ToWrite(addtext_ready, false, true, "", true));
      toFile.UpdateContent(this.ModFile("decode"), Parse.declare, new ToWrite("signal from_decode_ready_prescaiev: std_logic;\n", false, true, ""));

      String replace_orig_ready = "from_decode_ready_prescaiev <= to_stage1_ready or (not from_stage1_valid);";
      toFile.ReplaceContent(this.ModFile("decode"), "from_decode_ready <=", new ToWrite(replace_orig_ready, false, true, "", true));

      String decode_ready_assign = "from_decode_ready <= from_decode_ready_prescaiev and " + noStallCond + ";";
      toFile.UpdateContent(this.ModFile("decode"), Parse.behav, new ToWrite(decode_ready_assign, false, true, ""));

      String grep_stage1_pipe_cond = "if from_decode_ready = '1' then";
      String pretext_stage1_pipe_cond = "from_stage1_valid                  <= '1';";
      String replace_stage1_pipe_cond = "if from_decode_ready_prescaiev = '1' then";
      toFile.ReplaceContent(this.ModFile("decode"), grep_stage1_pipe_cond,
                            new ToWrite(replace_stage1_pipe_cond, true, false, pretext_stage1_pipe_cond, true));

      String grep_valid = "from_stage1_valid                                    <= to_decode_valid and (not to_decode_sixty_four_bit_instruction)";
      String addtext_valid = grep_valid + " and " + noStallCond + ";";
      toFile.ReplaceContent(this.ModFile("decode"), grep_valid, new ToWrite(addtext_valid, false, true, "", true));
    }

    if (this.ContainsOpInStage(BNode.WrStall, 2) || this.ContainsOpInStage(BNode.WrFlush, 2)) {
      String addStallClause = "";
      if (this.ContainsOpInStage(BNode.WrStall, 2))
        addStallClause += "not (" + language.CreateNodeName(BNode.WrStall, stages[2], "") + ") and ";
      if (this.ContainsOpInStage(BNode.WrFlush, 2))
        addStallClause += "not (" + language.CreateNodeName(BNode.WrFlush, stages[2], "") + ") and ";
      toFile.UpdateContent(this.ModFile("orca_core"), Parse.behav,
                           new ToWrite("ISAX_to_2_ready <= " + addStallClause + " " + readySigs.get(2) + ";", false, true, ""));
      toFile.UpdateContent(this.ModFile("orca_core"), Parse.declare, new ToWrite("signal ISAX_to_2_ready: std_logic;\n", false, true, ""));
      toFile.ReplaceContent(this.ModFile("orca_core"), readyReplace.get(2),
                            new ToWrite(readyReplace.get(2) + "ISAX_to_2_ready\n", false, true, ""));
    }

    if (this.ContainsOpInStage(BNode.WrStall, 3) || this.ContainsOpInStage(BNode.WrFlush, 3)) {
      String noStallCond = "";
      if (this.ContainsOpInStage(BNode.WrStall, 3))
        noStallCond += "not (" + language.CreateNodeName(BNode.WrStall, stages[3], "") + ") and ";
      if (this.ContainsOpInStage(BNode.WrFlush, 3))
        noStallCond += "not (" + language.CreateNodeName(BNode.WrFlush, stages[3], "") + ") and ";
      toFile.ReplaceContent(this.ModFile(NodeAssignM(BNode.WrStall, stages[3])), "((not vcp_select) or vcp_ready)))",
                            new ToWrite("((not vcp_select) or vcp_ready))));\n", false, true, "", true));
      toFile.ReplaceContent(this.ModFile(NodeAssignM(BNode.WrStall, stages[3])), "from_execute_ready <=",
                            new ToWrite(" from_execute_ready <= " + noStallCond + "((not to_execute_valid) or (from_writeback_ready and",
                                        false, true, "", true));
    }

    if (this.ContainsOpInStage(BNode.WrStall, 4) || this.ContainsOpInStage(BNode.WrFlush, 4)) { // mainly for spawn  {
      String noStallCond = "";
      if (this.ContainsOpInStage(BNode.WrStall, 4))
        noStallCond += "not (" + language.CreateNodeName(BNode.WrStall, stages[4], "") + ") and ";
      if (this.ContainsOpInStage(BNode.WrFlush, 4))
        noStallCond += "not (" + language.CreateNodeName(BNode.WrFlush, stages[4], "") + ") and ";
      toFile.ReplaceContent(
          this.ModFile(NodeAssignM(BNode.WrStall, stages[4])), "from_writeback_ready <= ",
          new ToWrite("from_writeback_ready <= (not use_after_produce_stall) and (not writeback_stall_from_lsu) and  (not " +
                          language.CreateNodeName(BNode.WrStall, stages[4], "") + ");\n",
                      false, true, "", true));
      //allow regfile writes during WrRD_spawn (i.e., delaying WrRD_spawn) for all but branch instructions
      toFile.ReplaceContent(
          this.ModFile(NodeAssignM(BNode.WrStall, stages[4])), "from_branch_valid or",
          new ToWrite("(" + noStallCond + "from_branch_valid) or", true, false, "to_rf_valid <= to_rf_select_writeable", true));
      //branch_unit: Hold from_branch_valid until to_branch_ready.
      toFile.ReplaceContent(
          this.ModFile("branch_unit"), "from_branch_valid <= '0';",
          new ToWrite("", false, true, "", true));
      toFile.UpdateContent(
          this.ModFile("branch_unit"), "if to_branch_ready = '1' then",
          new ToWrite("from_branch_valid <= '0';", true, false, "from_branch_valid <= '0';", false));
    }
  }
  private void IntegrateISAX_WrRD() {
    if (this.ContainsOpInStage(BNode.WrRD, 4) | this.ContainsOpInStage(BNode.WrRD, 3) | this.ContainsOpInStage(BNode.WrRD_spawn, 5)) {
      String ISAX_execute_to_rf_data_s = "ISAX_execute_to_rf_data_s";
      String ISAX_execute_to_rf_valid_s = "ISAX_execute_to_rf_valid_s";
      String decl_lineToBeInserted = "";

      // Declarations
      decl_lineToBeInserted += "signal " + ISAX_execute_to_rf_valid_s + " : std_logic;\n";
      decl_lineToBeInserted += "signal " + ISAX_execute_to_rf_data_s + " : std_logic_vector(REGISTER_SIZE-1 downto 0);\n";
      toFile.UpdateContent(this.ModFile("orca_core"), Parse.declare, new ToWrite(decl_lineToBeInserted, false, true, ""));
      if (!this.ContainsOpInStage(BNode.WrRD_valid, 3))
        language.UpdateInterface("orca", BNode.WrRD_valid, "", stages[3], true, false);
      if (!this.ContainsOpInStage(BNode.WrRD_validData, 3))
        language.UpdateInterface("orca", BNode.WrRD_validData, "", stages[3], true, false);

      // Send to decode correct result
      toFile.ReplaceContent(this.ModFile("orca_core"), "to_rf_data   =>",
                            new ToWrite("to_rf_data => " + ISAX_execute_to_rf_data_s + ",", true, false, "D : decode"));
      toFile.ReplaceContent(this.ModFile("orca_core"), "to_rf_valid  =>",
                            new ToWrite("to_rf_valid => " + ISAX_execute_to_rf_valid_s + ",", true, false, "D : decode"));

      // Compute result
      String wrrdDataBody = "";
      if (this.ContainsOpInStage(BNode.WrRD_spawn, 5)) {
        wrrdDataBody += "if( " + language.CreateNodeName(BNode.WrRD_spawn_valid, stages[5], "") + " = '1' ) then\n" + tab +
                        ISAX_execute_to_rf_data_s + " <= " + language.CreateNodeName(BNode.WrRD_spawn, stages[5], "") + "; \n"
                        + "    els";
      }
      if (this.ContainsOpInStage(BNode.WrRD, 3)) {
        wrrdDataBody += "if( " + language.CreateRegNodeName(BNode.WrRD_valid, stages[3], "") + " = '1' ) then\n" + tab +
                        ISAX_execute_to_rf_data_s + " <= " + language.CreateRegNodeName(BNode.WrRD, stages[3], "") + "; \n"
                        + "    els";
      }
      if (this.ContainsOpInStage(BNode.WrRD, 4)) {
        wrrdDataBody += "if( " + language.CreateNodeName(BNode.WrRD_valid, stages[4], "") + " = '1' ) then\n" + tab +
                        ISAX_execute_to_rf_data_s + " <= " + language.CreateNodeName(BNode.WrRD, stages[4], "") + "; \n"
                        + "    els";
      }
      wrrdDataBody += "e\n" + tab + ISAX_execute_to_rf_data_s + " <= execute_to_rf_data;\nend if;\n";
      toFile.UpdateContent(this.ModFile("orca_core"), Parse.behav,
                           new ToWrite(language.CreateInProc(false, wrrdDataBody), false, true, ""));

      // Compute valid
      String wrrdValid = "";
      if (this.ContainsOpInStage(BNode.WrRD_spawn, 5)) {
        wrrdValid += "if( " + language.CreateNodeName(BNode.WrRD_spawn_valid, stages[5], "") + " = '1'  and " +
                     language.CreateNodeName(BNode.WrRD_spawn_addr, stages[5], "") + " /= \"00000\" ) then\n" + tab +
                     ISAX_execute_to_rf_valid_s + " <= '1';\n"
                     + "    els";
      }
      if (this.ContainsOpInStage(BNode.WrRD, 4)) {
        wrrdValid += "if( " + language.CreateNodeName(BNode.WrRD_valid, stages[4], "") +
                     " = '1' and (execute_to_rf_select /=\"00000\") ) then\n" + tab + ISAX_execute_to_rf_valid_s + " <=  '1';\n"
                     + "    els";
      }
      wrrdValid += "e\n" + tab + ISAX_execute_to_rf_valid_s + " <= execute_to_rf_valid;\nend if;\n";
      toFile.UpdateContent(this.ModFile("orca_core"), Parse.behav, new ToWrite(language.CreateInProc(false, wrrdValid), false, true, ""));

      // Compute Adddress
      if (this.ContainsOpInStage(BNode.WrRD_spawn, 5)) {
        String addr =
            "if( " + language.CreateNodeName(BNode.WrRD_spawn_valid, stages[5], "") + " = '1' ) then\n"
            + "		        ISAX_execute_to_rf_select <= " + language.CreateNodeName(BNode.WrRD_spawn_addr, stages[5], "") +
            ";\n"
            + "		     else \n"
            + "		        ISAX_execute_to_rf_select <= execute_to_rf_select;\n"
            + "		    end if;";
        toFile.UpdateContent(this.ModFile("orca_core"), Parse.behav,
                             new ToWrite(language.CreateInProc(false, addr), false, true, "")); // add logic
        toFile.UpdateContent(
            this.ModFile("orca_core"), Parse.declare,
            new ToWrite("signal ISAX_execute_to_rf_select: std_logic_vector(5-1 downto 0);\n", false, true, "")); // declare new sig
        toFile.ReplaceContent(this.ModFile("orca_core"), "to_rf_select =>",
                              new ToWrite(" to_rf_select => ISAX_execute_to_rf_select,", true, false, "D : decode"));
      }

      // Instantiate regs in case wrrd in stage 3
      if (this.ContainsOpInStage(BNode.WrRD, 3)) {
        String newText =
            language.CreateTextRegResetStall(language.CreateRegNodeName(BNode.WrRD_validData, stages[4], ""),
                                             language.CreateNodeName(BNode.WrRD_valid, stages[3], ""), "(not from_execute_ready)");
        toFile.UpdateContent(this.ModFile("execute"), Parse.behav, new ToWrite(newText, false, true, ""));
        language.UpdateInterface("orca", BNode.WrRD_validData, "", stages[3], true, false);

        newText = language.CreateTextRegResetStall(language.CreateRegNodeName(BNode.WrRD, stages[4], ""),
                                                   language.CreateNodeName(BNode.WrRD, stages[3], ""), "(not from_execute_ready)");
        toFile.UpdateContent(this.ModFile("execute"), Parse.behav, new ToWrite(newText, false, true, ""));
      }

      // Datahaz. ISAX to standard
      if (this.ContainsOpInStage(BNode.WrRD, 4)) {
        String replace = "";
        String newText = "";
        replace = "from_alu_data  => from_alu_data,";
        newText = "from_alu_data  => ISAX_from_alu_data,";
        toFile.ReplaceContent(this.ModFile("execute"), replace, new ToWrite(newText, false, true, ""));

        newText = "signal ISAX_from_alu_data : std_logic_vector(REGISTER_SIZE-1 downto 0);";
        toFile.UpdateContent(this.ModFile("execute"), Parse.declare, new ToWrite(newText, false, true, ""));

        newText = "from_alu_data <= " + language.CreateNodeName(BNode.WrRD, stages[4], "") + " when " +
                  language.CreateNodeName(BNode.WrRD_validData, stages[4], "") + " = '1' else ISAX_from_alu_data;";
        toFile.UpdateContent(this.ModFile("execute"), Parse.behav, new ToWrite(newText, false, true, ""));
        // newText = language.CreateDeclReg(ISAX_REG_reg, 4, "");
        // toFile.UpdateContent(this.ModFile("orca_core"),Parse.declare, new ToWrite(newText,false,true,""));

        newText = language.CreateNodeName(ISAX_FWD_ALU, stages[3], "") + " <= " + language.CreateNodeName(BNode.WrRD_valid, stages[3], "") +
                  ";\n";
        toFile.UpdateContent(this.ModFile("execute"), Parse.behav, new ToWrite(newText, false, true, ""));

        replace = "if to_alu_valid = '1' and from_alu_ready = '1'";
        newText = "if ( to_alu_valid = '1' and from_alu_ready = '1') or (" + language.CreateNodeName(ISAX_FWD_ALU, stages[3], "") +
                  " = '1' and " + language.CreateNodeName(BNode.RdStall, stages[3], "") + " = '0') then";
        toFile.ReplaceContent(this.ModFile("execute"), replace, new ToWrite(newText, false, true, ""));
        language.UpdateInterface("orca_core", ISAX_FWD_ALU, "", stages[3], false, false);

        // Make sure a forward path is canceled if user signal says so
        replace = "rs1_data <= from_alu_data when rs1_mux = ALU_FWD";
        newText = replace + " and ( " + language.CreateNodeName(BNode.WrRD_validData, stages[4], "") + "  = '0' or  " +
                  language.CreateNodeName(BNode.WrRD_valid, stages[4], "") + " = '1' ) else";
        toFile.ReplaceContent(this.ModFile("execute"), replace, new ToWrite(newText, false, true, ""));
        replace = "rs2_data <= from_alu_data when rs2_mux = ALU_FWD";
        newText = replace + " and ( " + language.CreateNodeName(BNode.WrRD_validData, stages[4], "") + " = '0'  or " +
                  language.CreateNodeName(BNode.WrRD_valid, stages[4], "") + " = '1'  ) else";
        toFile.ReplaceContent(this.ModFile("execute"), replace, new ToWrite(newText, false, true, ""));
        replace = "rs3_data <= from_alu_data when rs3_mux = ALU_FWD";
        newText = replace + " and ( " + language.CreateNodeName(BNode.WrRD_validData, stages[4], "") + "  = '0' or " +
                  language.CreateNodeName(BNode.WrRD_valid, stages[4], "") + "  = '1' ) else";
        toFile.ReplaceContent(this.ModFile("execute"), replace, new ToWrite(newText, false, true, ""));
      }
    }
  }

  private void IntegrateISAX_Mem() {
    int stagePos = this.orca_core.GetNodes().get(BNode.RdMem).GetLatest().asInt(); // stage for Mem trasnfers
    PipelineStage stage = this.stages[stagePos];
    boolean readRequired = op_stage_instr.containsKey(BNode.RdMem) && op_stage_instr.get(BNode.RdMem).containsKey(stage);
    boolean writeRequired = op_stage_instr.containsKey(BNode.WrMem) && op_stage_instr.get(BNode.WrMem).containsKey(stage);
    // If reads or writes to mem are required...
    if (readRequired || writeRequired) {
      // Add in files logic for Memory reads
      // Generate text to select load store unit like :
      //		if(if((to_execute_instruction(6 downto 0) = "1011011" and to_execute_instruction(14 downto 12) = "010")) then --
      // ISAX load
      //			lsu_select <= '1';
      //		end if;
      String textToAdd = "";
      if (readRequired) {
        String rdValidReq = language.CreateNodeName(BNode.RdMem_validReq, stage, "");
        textToAdd = "if(" + rdValidReq +
                    " = '1') then -- ISAX load \n" // CreateValidEncoding generates text to decode instructions in
                                                     // op_stage_instr.get(BNode.RdMem).get(stage)
                    + tab + "lsu_select <= '1';\n"
                    + "end if;\n";
        toFile.UpdateContent(this.ModFile("load_store_unit"), Parse.declare,
                             new ToWrite("signal read_s :std_logic; -- ISAX, signaling a read", false, true, ""));
        toFile.UpdateContent(this.ModFile("load_store_unit"), Parse.declare,
                             new ToWrite("signal load_is_isax : std_logic;", false, true, ""));
        toFile.UpdateContent(
            this.ModFile("load_store_unit"), Parse.behav,
            new ToWrite(language.CreateText1or0("read_s", language.CreateNodeName(BNode.RdMem_validReq, stage, "") +
                                                              " = '1' or ((opcode(5) = LOAD_OP(5)) and (" +
                                                              language.CreateNodeName(is_ISAX, stages[3], "") + " = '0'))"),
                        false, true, ""));
        toFile.ReplaceContent(this.ModFile("load_store_unit"), "process (opcode, func3) is",
                              new ToWrite("process (opcode, func3, read_s) is", false, true, ""));
        toFile.ReplaceContent(this.ModFile("load_store_unit"), "if opcode(5) = LOAD_OP(5) then",
                              new ToWrite("if read_s = '1' then", false, true, ""));
        toFile.ReplaceContent(this.ModFile("load_store_unit"), "oimm_readnotwrite <= '1' when opcode(5) = LOAD_OP(5)",
                              new ToWrite("oimm_readnotwrite <= '1' when (read_s  = '1') else '0';", false, true, ""));

        toFile.UpdateContent(this.ModFile("load_store_unit"), "load_in_progress <= '1'",
                              new ToWrite("load_is_isax <= %s;".formatted(rdValidReq), false, true, ""));
        toFile.ReplaceContent(this.ModFile("load_store_unit"), "from_lsu_valid <= oimm_readdatavalid;",
                              new ToWrite("from_lsu_valid <= oimm_readdatavalid and (not load_is_isax);", false, true, ""));
      }
      if (writeRequired) {
        textToAdd += "if(" + language.CreateNodeName(BNode.WrMem_validReq, stage, "") + " = '1') then -- ISAX store \n" + tab +
                     "lsu_select <= '1';\n"
                     + "end if;\n";
        toFile.UpdateContent(this.ModFile("execute"), Parse.declare,
                             new ToWrite("signal ISAX_rs2_data : std_logic_vector(31 downto 0) ;", false, true, ""));
        toFile.UpdateContent(this.ModFile("execute"), Parse.behav,
                             new ToWrite("ISAX_rs2_data <= " + language.CreateNodeName(BNode.WrMem, stage, "") + " when (" +
                                             language.CreateNodeName(BNode.WrMem_validReq, stage, "") + " = '1') else rs2_data;",
                                         false, true, ""));
        toFile.ReplaceContent(this.ModFile("execute"), "rs2_data       => rs2_data,",
                              new ToWrite(" rs2_data       => ISAX_rs2_data, -- ISAX", true, false, "ls_unit : load_store_unit"));
      }
      // Add text in file of module "execute" before line "end process;". Don't add it unless you already saw line "lsu_select <= '1';"  =
      // requries prerequisite.
      toFile.UpdateContent(this.ModFile("execute"), "end process;",
                           new ToWrite(textToAdd, true, false, "lsu_select <= '1';",
                                       true)); // ToWrite (text to add, requries prereq?, start value for prereq =false if prereq required,
                                               // prepreq text, text has to be added BEFORE grepped line?)

      // Size logic
      toFile.ReplaceContent(this.ModFile("load_store_unit"), "alias func3  : std_logic_vector(2 downto 0) is instruction(INSTR_FUNC3'range);",
          new ToWrite("signal func3 : std_logic_vector(2 downto 0);", false, true, ""));
      String func3_expr = "instruction(INSTR_FUNC3'range)";
      if (ContainsOpInStage(BNode.WrMem_size, stage)) {
        func3_expr = "%s when (%s = '1') else %s".formatted(language.CreateNodeName(BNode.WrMem_size, stage, ""),
                                                            language.CreateNodeName(BNode.WrMem_addr_valid, stage, ""),
                                                            func3_expr);
      }
      if (ContainsOpInStage(BNode.RdMem_size, stage)) {
        func3_expr = "%s when (%s = '1') else %s".formatted(language.CreateNodeName(BNode.RdMem_size, stage, ""),
                                                            language.CreateNodeName(BNode.RdMem_addr_valid, stage, ""),
                                                            func3_expr);
      }
      toFile.UpdateContent(this.ModFile("load_store_unit"), Parse.behav,
          new ToWrite("func3 <= %s;".formatted(func3_expr), false, true, ""));

      // Address logic
      String isaxAddr = "";
      if (writeRequired)
        isaxAddr += language.CreateNodeName(BNode.WrMem_addr, stage, "") + " when (" +
                    language.CreateNodeName(BNode.WrMem_addr_valid, stage, "") + " = '1') ";
      if (readRequired)
        isaxAddr = language.OpIfNEmpty(isaxAddr, " else ") + language.CreateNodeName(BNode.RdMem_addr, stage, "") + " when (" +
                   language.CreateNodeName(BNode.RdMem_addr_valid, stage, "") + " = '1') ";
      toFile.ReplaceContent(this.ModFile("load_store_unit"), "address_unaligned <= std_logic_vector",
                            new ToWrite("address_unaligned <= " + isaxAddr +
                                            (" else std_logic_vector(unsigned(sign_extension(REGISTER_SIZE-12-1 downto 0) & "
                                             + "imm)+unsigned(base_address));-- Added ISAX support "),
                                        false, true, ""));
      toFile.ReplaceContent(this.ModFile("load_store_unit"), "imm)+unsigned(base_address));", new ToWrite(" ", false, true, ""));
    }

    if (this.op_stage_instr.containsKey(BNode.WrMem_spawn) || this.op_stage_instr.containsKey(BNode.RdMem_spawn)) {
      // RdAddr signal required by SCAL
      String rdAddrLogic =
          language.CreateLocalNodeName(BNode.RdMem_spawn_defaultAddr, stages[this.orca_core.maxStage + 1], "") + " <= oimm_address;\n";
      this.toFile.UpdateContent(this.ModFile("load_store_unit"), "oimm_address <= address_unaligned(REGISTER_SIZE-1 downto 0);",
                                new ToWrite(rdAddrLogic, false, true, "")); // TODO Test

      HashSet<String> allMemSpawn = new HashSet<String>();
      int spawnStagePos = this.orca_core.maxStage + 1;
      PipelineStage spawnStage = this.stages[spawnStagePos];
      this.op_stage_instr.getOrDefault(BNode.WrMem_spawn, new HashMap<>())
          .getOrDefault(spawnStage, new HashSet<>())
          .stream()
          .filter(isax -> !isax.isEmpty())
          .forEach(isax -> allMemSpawn.add(isax));
      this.op_stage_instr.getOrDefault(BNode.RdMem_spawn, new HashMap<>())
          .getOrDefault(spawnStage, new HashSet<>())
          .stream()
          .filter(isax -> !isax.isEmpty())
          .forEach(isax -> allMemSpawn.add(isax));

      toFile.ReplaceContent(this.ModFile("orca_core"), "lsu_oimm_address       => lsu_oimm_address,",
                            new ToWrite("lsu_oimm_address       => isax_lsu_oimm_address,", false, true, ""));
      toFile.ReplaceContent(this.ModFile("orca_core"), "lsu_oimm_byteenable    => lsu_oimm_byteenable,",
                            new ToWrite("lsu_oimm_byteenable    => isax_lsu_oimm_byteenable,", false, true, ""));
      toFile.ReplaceContent(this.ModFile("orca_core"), "lsu_oimm_requestvalid  => lsu_oimm_requestvalid,",
                            new ToWrite("lsu_oimm_requestvalid  => isax_lsu_oimm_requestvalid,", false, true, ""));
      toFile.ReplaceContent(this.ModFile("orca_core"), "lsu_oimm_readnotwrite  => lsu_oimm_readnotwrite,",
                            new ToWrite("lsu_oimm_readnotwrite  => isax_lsu_oimm_readnotwrite,", false, true, ""));
      toFile.ReplaceContent(this.ModFile("orca_core"), "lsu_oimm_writedata     => lsu_oimm_writedata,",
                            new ToWrite("lsu_oimm_writedata     => isax_lsu_oimm_writedata,", false, true, ""));
      String declare = "signal isax_lsu_oimm_address : std_logic_vector(31 downto 0);\n"
                       + "signal isax_lsu_oimm_byteenable : std_logic_vector(3 downto 0);\n"
                       + "signal isax_lsu_oimm_requestvalid : std_logic;\n"
                       + "signal isax_lsu_oimm_readnotwrite : std_logic;\n"
                       + "signal isax_spawn_lsu_oimm_writedata : std_logic_vector(31 downto 0);\n"
                       + "signal isax_lsu_oimm_writedata  : std_logic_vector(31 downto 0);\n"
                       + "signal isax_spawnRdReq : std_logic;\n"
                       + "signal isax_RdStarted : std_logic;";
      toFile.UpdateContent(this.ModFile("orca_core"), Parse.declare, new ToWrite(declare, false, true, ""));
      String checkOngoingTransf = "";
      if (this.op_stage_instr.containsKey(BNode.RdMem_spawn)) {
        checkOngoingTransf = "process(clk) begin \n" + tab.repeat(1) + "if rising_edge(clk) then \n" + tab.repeat(2) +
                             "if(lsu_oimm_requestvalid = '1') then\n" + tab.repeat(3) + "isax_RdStarted <= lsu_oimm_readnotwrite;\n" +
                             tab.repeat(2) + "elsif(lsu_oimm_readdatavalid = '1') then\n" + tab.repeat(3) + "isax_RdStarted <='0';\n" +
                             tab.repeat(2) + "end if; \n" + tab.repeat(2) + "if reset = '1' then \n" + tab.repeat(3) +
                             "isax_RdStarted <= '0'; \n" + tab.repeat(2) + "end if; \n" + tab.repeat(1) + "end if;\n"
                             + "end process;\n";
        checkOngoingTransf += "isax_spawnRdReq <= " + language.CreateNodeName(BNode.WrMem_spawn_validReq, spawnStage, "") + " and (not " +
                              language.CreateNodeName(BNode.WrMem_spawn_write, spawnStage, "") + ");\n";
      } else
        checkOngoingTransf = "isax_spawnRdReq <= 0;\n"
                             + "isax_RdStarted <= 0;\n";
      String setSignals = "lsu_oimm_address      <= " + language.CreateNodeName(BNode.WrMem_spawn_addr, spawnStage, "") + " when (" +
                          language.CreateNodeName(BNode.WrMem_spawn_validReq, spawnStage, "") + " = '1')  else isax_lsu_oimm_address;\n";
      //TODO: Decode WrMem_spawn_size, process lower two bits of address for 1/2 byte accesses
      setSignals +=       "lsu_oimm_byteenable    <= \"1111\" when (" +
                          language.CreateNodeName(BNode.WrMem_spawn_validReq, spawnStage, "") + " = '1') else isax_lsu_oimm_byteenable;\n";
      setSignals +=       "lsu_oimm_requestvalid  <= (not lsu_oimm_waitrequest and not isax_RdStarted) when (" +
                          language.CreateNodeName(BNode.WrMem_spawn_validReq, spawnStage, "") +
                          " = '1') else isax_lsu_oimm_requestvalid; -- todo possible comb path in slave between valid and wait\n";
      setSignals +=       "lsu_oimm_readnotwrite  <= not " + language.CreateNodeName(BNode.WrMem_spawn_write, spawnStage, "") +
                          " when (" + language.CreateNodeName(BNode.WrMem_spawn_validReq, spawnStage, "") +
                          " = '1') else isax_lsu_oimm_readnotwrite;\n";
      setSignals +=       "lsu_oimm_writedata     <= " + language.CreateNodeName(BNode.WrMem_spawn, spawnStage, "") + " when (" +
                          language.CreateNodeName(BNode.WrMem_spawn_validReq, spawnStage, "") + " = '1') else isax_lsu_oimm_writedata;\n";
      toFile.UpdateContent(this.ModFile("orca_core"), Parse.behav, new ToWrite(setSignals + checkOngoingTransf, false, true, ""));

      // Compute Mem Spawn Done
      String memdone = "(((lsu_oimm_waitrequest = '0') and (lsu_oimm_readnotwrite = '0')) or (lsu_oimm_readnotwrite = '1' and "
                       + "lsu_oimm_readdatavalid = '1'))";
      toFile.UpdateContent(
          this.ModFile("orca_core"), Parse.behav,
          new ToWrite(language.CreateText1or0(language.CreateNodeName(BNode.WrMem_spawn_validResp, spawnStage, ""), memdone), false, true,
                      ""));
    }
  }

  private void IntegrateISAX_WrPC() {
    String assign_lineToBeInserted = "";
    String decl_lineToBeInserted = "";
    String sub_top_file = this.ModFile("orca_core");
    HashMap<Integer, String> array_PC_clause = new HashMap<Integer, String>();
    int max_stage = this.orca_core.maxStage;
    for (int stagePos = 1; stagePos <= max_stage; stagePos++) {
      PipelineStage stage = stages[stagePos];
      if (this.ContainsOpInStage(BNode.WrPC, stage))
        array_PC_clause.put(stagePos, language.CreateNodeName(BNode.WrPC_valid, stage, ""));
    }

    if (this.ContainsOpInStage(BNode.WrPC, stages[0])) {
      String addDecl = "signal program_counter_prescaiev : unsigned(REGISTER_SIZE-1 downto 0);\n";
      toFile.UpdateContent(this.ModFile("instruction_fetch"), Parse.declare, new ToWrite(addDecl, false, true, ""));

      String[] program_counter_seqassigns = new String[] {
          "program_counter  <= to_pc_correction_data;",
          "program_counter <= predicted_program_counter;",
          "program_counter  <= unsigned(RESET_VECTOR(REGISTER_SIZE-1 downto 0));"
      };
      for (String assign : program_counter_seqassigns) {
        toFile.ReplaceContent(this.ModFile("instruction_fetch"), assign,
                              new ToWrite("program_counter_prescaiev" + assign.substring("program_counter".length()),
                                          false, true, ""));
      }
      String wrpc0_valid_signal = language.CreateNodeName(BNode.WrPC_valid, stages[0], "");
      String wrpc0_signal = language.CreateNodeName(BNode.WrPC, stages[0], "");
      String addText = "process(all) begin \n"
          + "    if( " + wrpc0_valid_signal + " ) then\n"
          + "        program_counter <= " + wrpc0_signal + "; \n"
          + "    else \n"
          + "        program_counter <= program_counter_prescaiev;\n"
          + "    end if;\n"
          + "end process;\n";
      toFile.UpdateContent(this.ModFile("instruction_fetch"), Parse.behav, new ToWrite(addText, false, true, ""));
    }

    if (!array_PC_clause.isEmpty() || op_stage_instr.containsKey(BNode.WrPC_spawn)) {
      decl_lineToBeInserted += "signal ISAX_to_pc_correction_data_s : unsigned(REGISTER_SIZE-1 downto 0);\n";
      decl_lineToBeInserted += "signal ISAX_to_pc_correction_valid_s : std_logic;\n";

      String PC_text = "ISAX_to_pc_correction_data_s <=X\"00000000\";\n";
      String PC_clause = "";
      String PC_clause_stage0 = " ( " + language.CreateNodeName(BNode.WrPC_valid, stages[0], "") + " = '1') ";
      for (int stagePos = 0; stagePos <= orca_core.GetNodes().get(BNode.WrPC).GetLatest().asInt() + 1; stagePos++) {
        PipelineStage stage = stages[stagePos];
        if (array_PC_clause.containsKey(stagePos) || (stagePos == (orca_core.GetNodes().get(BNode.WrPC).GetLatest().asInt() + 1))) {
          if (this.ContainsOpInStage(BNode.WrPC, max_stage + 1) && stagePos == 0) { // not supported anymore!!!
            logger.error("Spawn PC not supported anymore");
            PC_text += "if(" + language.CreateNodeName(BNode.WrPC_spawn_valid, stages[max_stage + 1], "") + " = '1') then\n" +
                       tab.repeat(2) +
                       "ISAX_to_pc_correction_data_s <= " + language.CreateNodeName(BNode.WrPC_spawn, stages[max_stage + 1], "") + ";\n";
            PC_clause += "( " + language.CreateNodeName(BNode.WrPC_spawn_valid, stages[max_stage + 1], "") + " = '1')";
          }
          if (array_PC_clause.containsKey(stagePos)) {
            PC_text += "if (" + array_PC_clause.get(stagePos) + " = '1') then\n" + tab.repeat(2) +
                       "ISAX_to_pc_correction_data_s <= " + language.CreateNodeName(BNode.WrPC, stage, "") + ";\nend if;\n";
            if (PC_clause != "")
              PC_clause += " or ";
            PC_clause += "( " + array_PC_clause.get(stagePos) + " = '1')";
          }
        }
        if ((stagePos == orca_core.GetNodes().get(BNode.WrPC).GetLatest().asInt()))
          PC_text += "if( to_pc_correction_valid = '1') then" + tab.repeat(2) +
                     "ISAX_to_pc_correction_data_s <= to_pc_correction_data;\nend if;\n";
        if (array_PC_clause.containsKey(stagePos) && stagePos != 0)
          PC_clause_stage0 += "and (" + language.CreateNodeName(BNode.WrPC_valid, stage, "") + " = '0')";
      }
      if (array_PC_clause.containsKey(0)) {
        String addDecl = "signal isax_noquashing_readdata:  std_logic;\n"
                         + "signal isax_rememberpc : unsigned(REGISTER_SIZE-1 downto 0);\n ";
        toFile.UpdateContent(this.ModFile("instruction_fetch"), Parse.declare, new ToWrite(addDecl, false, true, ""));

        language.UpdateInterface("orca_core", nodePCCorrValid, "", stages[1], false, false);
        String addText = "process(all) begin \n"
                         + "    if( " + PC_clause_stage0 + " ) then\n"
                         + "        " + language.CreateLocalNodeName(nodePCCorrValid, stages[1], "") + " <= '1'; \n"
                         + "    else \n"
                         + "        " + language.CreateLocalNodeName(nodePCCorrValid, stages[1], "") + " <='0';\n"
                         + "    end if;\n"
                         + "end process;\n";
        toFile.UpdateContent(this.ModFile("orca_core"), Parse.behav, new ToWrite(addText, false, true, ""));

        addText = "if(isax_tostage1_pc_correction_1_i = '1') then\n"
                  + "      isax_rememberpc <= from_ifetch_program_counter;\n"
                  + "\n"
                  + "      isax_noquashing_readdata <= '1';\n"
                  + "      elsif(from_ifetch_valid) then\n"
                  + "      isax_noquashing_readdata <= '0' ;\n"
                  + "      end if;\n";
        String grep = "if to_pc_correction_valid = '1' and from_pc_correction_ready = '1' then";
        toFile.UpdateContent(this.ModFile("instruction_fetch"), grep, new ToWrite(addText, false, true, "", true));

        addText = "ifetch_valid           <= (not instruction_fifo_empty) or (oimm_readdatavalid and (not quashing_readdata or "
                  + "isax_noquashing_readdata));\n";
        grep = "ifetch_valid           <=";
        toFile.ReplaceContent(this.ModFile("instruction_fetch"), grep, new ToWrite(addText, false, true, ""));

        addText = "pc_fifo_read               <= ifetch_valid and to_ifetch_ready and (not isax_noquashing_readdata);\n";
        grep = "pc_fifo_read               <=";
        toFile.ReplaceContent(this.ModFile("instruction_fetch"), grep, new ToWrite(addText, false, true, ""));

        addText = "from_ifetch_program_counter <= isax_rememberpc when (isax_noquashing_readdata = '1' ) else "
                  + "unsigned(pc_fifo_readdata(REGISTER_SIZE-1 downto 0));\n";
        grep = "from_ifetch_program_counter <=";
        toFile.ReplaceContent(this.ModFile("instruction_fetch"), grep, new ToWrite(addText, false, true, ""));
      }

      assign_lineToBeInserted += language.CreateInProc(false, PC_text);
      assign_lineToBeInserted += language.CreateTextISAXorOrig(PC_clause, "ISAX_to_pc_correction_valid_s", "'1'", "to_pc_correction_valid");
      toFile.ReplaceContent(
          sub_top_file, "to_pc_correction_data        => to_pc_correction_data,",
          new ToWrite("to_pc_correction_data        => ISAX_to_pc_correction_data_s,", true, false, "I : instruction_fetch"));
      toFile.ReplaceContent(
          sub_top_file, "to_pc_correction_valid       =>",
          new ToWrite("to_pc_correction_valid        => ISAX_to_pc_correction_valid_s,", true, false, "I : instruction_fetch"));
      String cond_noWrPC_todecode = IntStream.range(2, max_stage)
                                        .mapToObj(i -> array_PC_clause.get(i))
                                        .filter(validExpr -> validExpr != null)
                                        .reduce((a, b) -> a + " || " + b)
                                        .orElse("'0'");
      String cond_noWrPC_toexecute = IntStream.range(4, max_stage)
                                         .mapToObj(i -> array_PC_clause.get(i))
                                         .filter(validExpr -> validExpr != null)
                                         .reduce((a, b) -> a + " || " + b)
                                         .orElse("'0'");
      toFile.ReplaceContent(
          sub_top_file, "to_decode_valid <=",
          new ToWrite("to_decode_valid <= from_ifetch_valid and (not to_pc_correction_valid or " + cond_noWrPC_todecode + ");", false, true,
                      ""));
      toFile.ReplaceContent(
          sub_top_file, "to_execute_valid <=",
          new ToWrite("to_execute_valid <= from_decode_valid and (not to_pc_correction_valid or " + cond_noWrPC_toexecute + ");", false,
                      true, ""));
      // if(this.ContainsOpInStage(BNode.WrFlush, 2))
      //	toFile.ReplaceContent(sub_top_file, "quash_decode =>", new ToWrite("quash_decode        => to_pc_correction_valid or
      //"+language.CreateNodeName(BNode.WrFlush, stages[2],"")+",",true, false, "D : decode"));
    }
    // insert.put("port (", new ToWrite(interf_lineToBeInserted,false,true,""));
    toFile.UpdateContent(sub_top_file, "architecture rtl of orca_core is", new ToWrite(decl_lineToBeInserted, false, true, ""));
    toFile.UpdateContent(sub_top_file, "begin", new ToWrite(assign_lineToBeInserted, false, true, ""));
  }
  private boolean ContainsOpInStage(SCAIEVNode operation, int stage) { return ContainsOpInStage(operation, stages[stage]); }
  private boolean ContainsOpInStage(SCAIEVNode operation, PipelineStage stage) {
    return op_stage_instr.containsKey(operation) && op_stage_instr.get(operation).containsKey(stage);
  }
  private void ConfigOrca() {
    this.PopulateNodesMap();

    PutModule(pathORCA + "/components.vhd", "instruction_fetch", pathORCA + "/instruction_fetch.vhd", "orca_core", "instruction_fetch");
    PutModule(pathORCA + "/components.vhd", "decode", pathORCA + "/decode.vhd", "orca_core", "decode");
    PutModule(pathORCA + "/components.vhd", "execute", pathORCA + "/execute.vhd", "orca_core", "execute");
    PutModule(pathORCA + "/components.vhd", "arithmetic_unit", pathORCA + "/alu.vhd", "execute", "arithmetic_unit");
    PutModule(pathORCA + "/components.vhd", "branch_unit", pathORCA + "/branch_unit.vhd", "execute", "branch_unit");
    PutModule(pathORCA + "/components.vhd", "load_store_unit", pathORCA + "/load_store_unit.vhd", "execute", "load_store_unit");
    PutModule(pathORCA + "/components.vhd", "orca_core", pathORCA + "/orca_core.vhd", "orca", "orca_core");
    PutModule(pathORCA + "/components.vhd", "orca", pathORCA + "/orca.vhd", "", "orca");
    for (Module module : fileHierarchy.values()) {
      toFile.AddFile(module.file, false);
    }

    int spawnStage = this.orca_core.maxStage + 1;
    for (int i = 0; i < spawnStage; i++) {
      //Pass WrPC_0 to instruction_fetch.
      String moduleInto = (i==0)?"instruction_fetch":"orca_core";
      this.PutNode("unsigned", "", moduleInto, BNode.WrPC, stages[i]);        // was to_pc_correction_data
      this.PutNode("std_logic", "", moduleInto, BNode.WrPC_valid, stages[i]); // was to_pc_correction_valid
    }
    this.PutNode("unsigned", "", "orca_core", BNode.WrPC_spawn, stages[5]);
    this.PutNode("std_logic", "", "orca_core", BNode.WrPC_spawn_valid, stages[5]);
    if (this.ContainsOpInStage(BNode.WrPC, stages[0]))
      this.PutNode("unsigned", "program_counter_prescaiev", "instruction_fetch", BNode.RdPC, stages[0]);
    else
      this.PutNode("unsigned", "program_counter", "orca_core", BNode.RdPC, stages[0]);
    this.PutNode("unsigned", "ifetch_to_decode_program_counter", "orca_core", BNode.RdPC, stages[1]);
    this.PutNode("unsigned", "from_stage1_program_counter", "decode", BNode.RdPC, stages[2]);
    this.PutNode("unsigned", "decode_to_execute_program_counter", "orca_core", BNode.RdPC, stages[3]);

    this.PutNode("std_logic_vector", "ifetch_to_decode_instruction", "orca_core", BNode.RdInstr, stages[1]);
    this.PutNode("std_logic_vector", "from_stage1_instruction", "decode", BNode.RdInstr, stages[2]);
    this.PutNode("std_logic_vector", "decode_to_execute_instruction", "orca_core", BNode.RdInstr, stages[3]);

    this.PutNode("std_logic_vector", "rs1_data", "decode", BNode.RdRS1, stages[2]);
    this.PutNode("std_logic_vector", "rs1_data", "execute", BNode.RdRS1, stages[3]);

    this.PutNode("std_logic_vector", "rs2_data", "decode", BNode.RdRS2, stages[2]);
    this.PutNode("std_logic_vector", "rs2_data", "execute", BNode.RdRS2, stages[3]);

    this.PutNode("std_logic_vector", "", "execute", BNode.WrRD, stages[4]);
    this.PutNode("std_logic", "", "execute", BNode.WrRD_valid, stages[3]);
    this.PutNode("std_logic", "", "execute", BNode.WrRD_validData, stages[3]);
    // this.PutNode( "std_logic_vector", "", "orca_core", BNode.WrRD_addr, stages[4]);
    this.PutNode("std_logic", "", "execute", BNode.WrRD_valid, stages[4]);
    this.PutNode("std_logic", "", "execute", BNode.WrRD_validData, stages[4]);

    // this.PutNode( "std_logic", "", "orca_core", BNode.RdIValid, stages[1]);
    // this.PutNode( "std_logic", "", "decode", BNode.RdIValid, stages[2]);
    // this.PutNode( "std_logic", "", "orca_core", BNode.RdIValid, stages[3]);
    // this.PutNode( "std_logic", "", "orca_core", BNode.RdIValid, stages[4]);

    int stageMem = this.orca_core.GetNodes().get(BNode.RdMem).GetLatest().asInt();
    this.PutNode("std_logic_vector", "from_lsu_data", "load_store_unit", BNode.RdMem, stages[stageMem]);
    // this.PutNode( "std_logic", "from_lsu_valid", "load_store_unit", BNode.RdMem_validResp,stages[stageMem]);
    this.PutNode("std_logic_vector", "", "load_store_unit", BNode.WrMem, stages[stageMem]);
    this.PutNode("std_logic", "", "load_store_unit", BNode.RdMem_validReq, stages[stageMem]);
    this.PutNode("std_logic_vector", "", "load_store_unit", BNode.RdMem_addr, stages[stageMem]);
    this.PutNode("std_logic_vector", "", "load_store_unit", BNode.RdMem_size, stages[stageMem]);
    this.PutNode("std_logic", "", "load_store_unit", BNode.WrMem_validReq, stages[stageMem]);
    this.PutNode("std_logic_vector", "", "load_store_unit", BNode.WrMem_addr, stages[stageMem]);
    this.PutNode("std_logic_vector", "", "load_store_unit", BNode.WrMem_size, stages[stageMem]);
    this.PutNode("std_logic", "", "load_store_unit", BNode.WrMem_addr_valid, stages[stageMem]);
    this.PutNode("std_logic", "", "load_store_unit", BNode.RdMem_addr_valid, stages[stageMem]);

    this.PutNode("std_logic", "not (pc_fifo_write)", "instruction_fetch", BNode.RdStall, stages[0]);
    this.PutNode("std_logic", "not (to_decode_valid) or not (decode_to_ifetch_ready)", "orca_core", BNode.RdStall, stages[1]);
    this.PutNode("std_logic",
                 "not (from_stage1_valid) or not (" + language.CreateLocalNodeName(ISAX_frwrd_to_stage1_ready, stages[2], "") + ")",
                 "decode", BNode.RdStall, stages[2]);
    String rdStall3 = "(not to_execute_valid) or \n"
                      + "(to_execute_valid and not (from_writeback_ready and\n"
                      + "    (((not lsu_select) or from_lsu_ready) and\n"
                      + "    ((not alu_select) or from_alu_ready) and\n"
                      + "    ((not syscall_select) or from_syscall_ready) and\n"
                      + "    ((not vcp_select) or vcp_ready))))"
                      + "or (not from_writeback_ready)";
    this.PutNode("std_logic", rdStall3, "execute", BNode.RdStall, stages[3]); // wrstall already within execute_to_decode_ready
    this.PutNode("std_logic",
                 "not (from_syscall_valid or from_lsu_valid or from_branch_valid or from_alu_valid or " +
                     language.CreateRegNodeName(is_ISAX, stages[4], "") + ") or not (from_writeback_ready)",
                 "execute", BNode.RdStall, stages[4]);

    this.PutNode("std_logic", "pc_fifo_write", "instruction_fetch", BNode.RdInStageValid, stages[0]);
    this.PutNode("std_logic", "to_decode_valid", "orca_core", BNode.RdInStageValid, stages[1]);
    this.PutNode("std_logic", "from_stage1_valid", "decode", BNode.RdInStageValid, stages[2]);
    {
      String toExecuteValidNoflushCond = "";
      //		 	if (this.ContainsOpInStage(BNode.WrFlush, 2))
      //		 		toExecuteValidNoflushCond = " and not " + language.CreateNodeName(BNode.WrFlush,stages[2], "");
      //		 	//HACK: Add RdFlush_2 to op_stage_instr, i.e. to the outer interface
      //		 	op_stage_instr.computeIfAbsent(BNode.RdFlush, node_ -> new HashMap<>()).computeIfAbsent(stages[2], stage_ ->
      // new HashSet<>()).add(""); 	 		toExecuteValidNoflushCond += " and not " +
      // language.CreateNodeName(BNode.RdFlush,stages[2], "");
      //String inStageValidCond = "to_execute_valid" + toExecuteValidNoflushCond;
      //this.PutNode("std_logic", inStageValidCond, "orca_core", BNode.RdInStageValid, stages[3]);
      this.PutNode("std_logic", "to_execute_valid", "execute", BNode.RdInStageValid, stages[3]);
    }
    // TODO: May want to make this equal to RdStall_4 once from_writeback_ready (or its replacement) is decoupled from WrStall_4
    this.PutNode("std_logic",
                 "(from_syscall_valid or from_lsu_valid or from_branch_valid or from_alu_valid or " +
                     language.CreateRegNodeName(is_ISAX, stages[4], "") + ")",
                 "execute", BNode.RdInStageValid, stages[4]);

    this.PutNode("std_logic", "", "instruction_fetch", BNode.WrStall, stages[0]);
    this.PutNode("std_logic", "", "decode", BNode.WrStall, stages[1]);
    this.PutNode("std_logic", "", "decode", BNode.WrStall, stages[2]);
    this.PutNode("std_logic", "", "execute", BNode.WrStall, stages[3]);
    this.PutNode("std_logic", "", "execute", BNode.WrStall, stages[4]);

    this.PutNode("std_logic", "'0'", "execute", BNode.RdFlush, stages[4]); // is_ISAX for internal reg
    String rdflush_wrflushCond = "";
    if (this.ContainsOpInStage(BNode.WrFlush, 4))
      rdflush_wrflushCond = " or " + language.CreateNodeName(BNode.WrFlush, stages[4], "");
    // TODO RdFlush_1, RdFlush_2 were previously without to_pc_correction_valid
    for (int iFlushStage = 3; iFlushStage >= 0; --iFlushStage) {
      this.PutNode("std_logic", "to_pc_correction_valid" + rdflush_wrflushCond, "orca_core", BNode.RdFlush, stages[iFlushStage]);
      if (this.ContainsOpInStage(BNode.WrFlush, iFlushStage))
        rdflush_wrflushCond = " or " + language.CreateLocalNodeName(BNode.WrFlush, stages[iFlushStage], "") + rdflush_wrflushCond;
    }

    this.PutNode("std_logic", "", "instruction_fetch", BNode.WrFlush, stages[0]);
    this.PutNode("std_logic", "", "decode", BNode.WrFlush, stages[1]);
    this.PutNode("std_logic", "", "decode", BNode.WrFlush, stages[2]);
    this.PutNode("std_logic", "", "execute", BNode.WrFlush, stages[3]);
    this.PutNode("std_logic", "", "execute", BNode.WrFlush, stages[4]);

    this.PutNode("std_logic_vector", "", "orca_core", BNode.WrRD_spawn, stages[spawnStage]);
    this.PutNode("std_logic", "not execute_to_rf_valid", "orca_core", BNode.WrRD_spawn_validResp, stages[spawnStage]);
    this.PutNode("std_logic", "", "orca_core", BNode.WrRD_spawn_valid, stages[spawnStage]);
    this.PutNode("std_logic_vector", "", "orca_core", BNode.WrRD_spawn_addr, stages[spawnStage]);
    this.PutNode("std_logic", "not execute_to_rf_valid", "orca_core", BNode.WrRD_spawn_allowed, stages[spawnStage]);

    this.PutNode("std_logic_vector", "lsu_oimm_readdata", "orca_core", BNode.RdMem_spawn, stages[spawnStage]);
    this.PutNode("std_logic", "", "orca_core", BNode.RdMem_spawn_validResp, stages[spawnStage]);
    this.PutNode("std_logic_vector", "", "orca_core", BNode.WrMem_spawn, stages[spawnStage]);
    this.PutNode("std_logic", "", "orca_core", BNode.WrMem_spawn_validResp,
                 stages[spawnStage]); // ? ((lsu_oimm_waitrequest = '0')  or (lsu_oimm_readdatavalid = '1'))
    this.PutNode("std_logic", "", "orca_core", BNode.RdMem_spawn_validReq, stages[spawnStage]);
    this.PutNode("std_logic_vector", "", "orca_core", BNode.RdMem_spawn_addr, stages[spawnStage]);
    this.PutNode("std_logic_vector", "", "orca_core", BNode.RdMem_spawn_size, stages[spawnStage]);
    this.PutNode("std_logic", "", "orca_core", BNode.RdMem_spawn_write, stages[spawnStage]);
    this.PutNode("std_logic", "", "orca_core", BNode.WrMem_spawn_write, stages[spawnStage]);

    this.PutNode("std_logic", "", "orca_core", BNode.WrMem_spawn_validReq, stages[spawnStage]);
    this.PutNode("std_logic_vector", "", "orca_core", BNode.WrMem_spawn_addr, stages[spawnStage]);
    this.PutNode("std_logic_vector", "", "orca_core", BNode.WrMem_spawn_size, stages[spawnStage]);

    this.PutNode("std_logic", "execute_to_decode_ready", "orca_core", BNode.ISAX_spawnAllowed, stages[3]);
    this.PutNode("std_logic", "", "instruction_fetch", this.nodePCCorrValid, stages[1]); //
    this.PutNode("std_logic", "", "execute", ISAX_FWD_ALU, stages[3]);
    this.PutNode("std_logic", "to_stage1_ready", "decode", ISAX_frwrd_to_stage1_ready, stages[2]);
    this.PutNode("std_logic", "", "load_store_unit", is_ISAX, stages[3]);
  }
}
