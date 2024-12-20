package scaiev.backend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.coreconstr.Core;
import scaiev.coreconstr.CoreNode;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.util.FileWriter;
import scaiev.util.Lang;
import scaiev.util.ToWrite;
import scaiev.util.VHDL;
import scaiev.util.Verilog;

public class PicoRV32 extends CoreBackend {
  // logging
  protected static final Logger logger = LogManager.getLogger();

  // TODO: Support for Mem_*_size, defaultAddr

  public String tab = "    ";
  HashSet<String> AddedIValid = new HashSet<String>();

  public String pathCore = "CoresSrc/PicoRV32";
  public String getCorePathIn() { return pathCore; }
  public String pathPicoRV32 = "";
  private Core picorv32;
  private HashMap<String, SCAIEVInstr> ISAXes;
  private HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr;
  private PipelineStage[] stages;
  private FileWriter toFile = new FileWriter(pathCore);
  private String extension_name;
  private Verilog language = null;
  private String topModule = "picorv32";
  private int nrTabs = 0;

  private HashMap<Integer, HashSet<String>> iValidDeclared = new HashMap<Integer, HashSet<String>>();
  private HashSet<Integer> rdInstrDeclared = new HashSet<Integer>();

  public void Prepare(HashMap<String, SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                      Core core, SCALBackendAPI scalAPI, BNode user_BNode) {
    super.Prepare(ISAXes, op_stage_instr, core, scalAPI, user_BNode);
    this.stages = core.GetRootStage().getAllChildren().collect(Collectors.toList()).toArray(n -> new PipelineStage[n]);
    for (int i = 0; i < this.stages.length; ++i)
      assert (this.stages[i].getStagePos() == i);
    assert (stages[stages.length - 1].getKind() == StageKind.Decoupled);
    assert (stages[stages.length - 2].getKind() == StageKind.Core);
    this.BNode = user_BNode;
    this.language = new Verilog(user_BNode, toFile, this);
    scalAPI.OverrideEarliestValid(user_BNode.WrMem, new PipelineFront(stages[0]));
    scalAPI.OverrideEarliestValid(user_BNode.RdMem, new PipelineFront(stages[0]));

    // Ignore WrCommit_spawn for now.
    BNode.WrCommit_spawn.tags.add(NodeTypeTag.noCoreInterface);
    BNode.WrCommit_spawn_validReq.tags.add(NodeTypeTag.noCoreInterface);
    BNode.WrCommit_spawn_validResp.tags.add(NodeTypeTag.noCoreInterface);
    core.PutNode(BNode.RdInStageValid, new CoreNode(0, 0, stages.length - 1, stages.length, BNode.RdInStageValid.name));
  }

  public boolean Generate(HashMap<String, SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<PipelineStage, HashSet<String>>> op_stage_instr,
                          String extension_name, Core core, String out_path) { // core needed for verification purposes
    // Print Info
    logger.info("Requested user nodes: " + op_stage_instr);
    // Set variables
    this.picorv32 = core;
    this.ISAXes = ISAXes;
    this.op_stage_instr = op_stage_instr;
    this.extension_name = extension_name;
    language.clk = "clk";
    language.reset = "!resetn";
    ConfigPicoRV32();
    for (int stage = 0; stage <= this.picorv32.maxStage; stage++)
      iValidDeclared.put(stage, new HashSet<String>());

    IntegrateISAX_IOs(topModule);
    IntegrateISAX_NoIllegalInstr();
    IntegrateISAX_RdStall_Prepare();
    IntegrateISAX_RdInstr();
    IntegrateISAX_SpawnRD(); // don't switch spawn with wrrd. spawn has priority
    IntegrateISAX_SpawnMem();
    IntegrateISAX_WrRD();
    IntegrateISAX_Mem();
    IntegrateISAX_WrPC();
    IntegrateISAX_WrStall(); // stall last
    IntegrateISAX_WrFlush();
    IntegrateISAX_RdStall();

    toFile.WriteFiles(language.GetDictModule(), language.GetDictEndModule(), out_path);

    return true;
  }

  private void IntegrateISAX_RdStall_Prepare() {
    stallConds = new String[] {"", "", "", ""};
    addToStallCond(0, "!decoder_trigger || cpu_state != cpu_state_fetch");
    addToStallCond(1, "cpu_state != cpu_state_ld_rs1");
    addToStallCond(2, "((cpu_state_exec ==  cpu_state) && (((TWO_CYCLE_ALU || TWO_CYCLE_COMPARE) && (alu_wait || alu_wait_2))))");
    addToStallCond(2, "((mem_do_prefetch || ~mem_done) && ((cpu_state == cpu_state_stmem) || (cpu_state == cpu_state_ldmem)))");
    addToStallCond(2, "cpu_state == cpu_state_fetch || cpu_state == cpu_state_ld_rs1");
  }
  private void IntegrateISAX_RdStall() {
    for (int stageNum = 0; stageNum < stallConds.length; stageNum++) {
      if (ContainsOpInStage(BNode.RdStall, stageNum)) {
        addDeclaration("wire " + language.CreateLocalNodeName(BNode.RdStall, stages[stageNum], "") + ";\n");
        String stallCond = stallConds[stageNum];
        if (stallCond.isEmpty())
          stallCond = "0";
        addLogic("assign " + language.CreateLocalNodeName(BNode.RdStall, stages[stageNum], "") + " = " + stallCond + ";\n");
      }
    }
  }

  private void IntegrateISAX_IOs(String topModule) {
    boolean mem_addr = false;
    boolean reg_addr = false;

    // Generate Interface as for other cores:
    language.GenerateAllInterfaces(topModule, op_stage_instr, ISAXes, picorv32, BNode.WrRD_validData);

    // only picorv32
    toFile.ReplaceContent(this.ModFile("picorv32"), "output reg [35:0] trace_data",
                          new ToWrite("output reg [35:0] trace_data,", false, true, ""));

    // only picorv32 workaround syntax err ,
    this.toFile.UpdateContent(this.ModFile("picorv32"), ");",
                              new ToWrite("output dummy_signal", true, false, "module picorv32 ", true, "picorv32"));
  }

  private void IntegrateISAX_NoIllegalInstr() {
    String isISAXSignal = "ISAX_isisax";
    toFile.ReplaceContent(
        this.ModFile("picorv32"), "instr_getq, instr_setq, instr_retirq, instr_maskirq",
        new ToWrite("instr_getq, instr_setq, instr_retirq, instr_maskirq, instr_waitirq, instr_timer, " + isISAXSignal + "};", true, false,
                    "assign instr_trap ="));
    HashSet<String> allISAXes = new HashSet<String>();
    allISAXes.addAll(ISAXes.keySet());
    String logicIsISAX = "always@(posedge " + language.clk + ")  begin\n" + tab + "if (mem_do_rinst && mem_done) begin\n" + tab + tab +
                         isISAXSignal + " <= " + language.CreateAllEncoding(allISAXes, ISAXes, "mem_rdata_latched") + ";\n" + tab + "end\n"
                         + "end\n";
    addDeclaration("reg " + isISAXSignal + ";");
    //	toFile.UpdateContent(this.ModFile("picorv32"),"endmodule",  new ToWrite(logicIsISAX,false,true,"",true));
    addLogic(logicIsISAX);

    // Make sure no err due to undefined sig occurs
    addDeclaration("reg [7:0] cpu_state;\n");
    String grep = "reg [7:0] cpu_state";
    toFile.ReplaceContent(this.ModFile("picorv32"), grep, new ToWrite(" \n", false, true, ""));

    // If I don't have any ISAX writing RegF or if there are some ISAXes which don't write regf
    int nrIsaxesWithWRRd = 0;
    if (this.op_stage_instr.containsKey(BNode.WrRD))
      for (PipelineStage stage : this.op_stage_instr.get(BNode.WrRD).keySet()) {
        nrIsaxesWithWRRd += this.op_stage_instr.get(BNode.WrRD).get(stage).stream().filter(isax -> !isax.isEmpty()).count();
      }
    if (!this.op_stage_instr.containsKey(BNode.WrRD) ||
        (this.op_stage_instr.containsKey(BNode.WrRD) && (nrIsaxesWithWRRd < ISAXes.size()))) {
      toFile.ReplaceContent(this.ModFile("picorv32"), "latched_store && !latched_branch",
                            new ToWrite("latched_store && !latched_branch && !" + isISAXSignal + "_2: begin", false, true, ""));
      addDeclaration("reg " + isISAXSignal + "_2;\n");
      addLogic("always@(posedge " + language.clk + ") begin " + language.tab + "if((cpu_state ==  cpu_state_fetch) && (decoder_trigger)) " +
               isISAXSignal + "_2 <= " + isISAXSignal + ";\n"
               + "end");
    }
  }

  private void IntegrateISAX_RdInstr() {
    String text = "";
    if (this.ContainsOpInStage(BNode.RdInstr, 0)) {
      text = "always @(posedge " + language.clk + ") begin \n"
             + "		if (mem_do_rinst && mem_done) \n"
             + "			rdInstr_0_r <= mem_rdata_latched;\n"
             + "	end\n "
             + "wire [32 -1:0] " + language.CreateLocalNodeName(BNode.RdInstr, stages[0], "") + ";\n"
             + "assign " + language.CreateLocalNodeName(BNode.RdInstr, stages[0], "") + " = rdInstr_0_r;";
      addDeclaration("reg [32 -1:0] rdInstr_0_r;\n");
      addLogic(text);
    }
  }

  private void IntegrateISAX_WrStall() {
    String spawnStall = "";
    if (this.op_stage_instr.containsKey(BNode.WrStall) | this.op_stage_instr.containsKey(BNode.RdStall)) {
      if (this.ContainsOpInStage(BNode.WrStall, 2)) {
        // toFile.UpdateContent(this.ModFile("picorv32"),"cpu_state <= cpu_state_fetch;", new
        // ToWrite("if(!"+language.CreateNodeName(BNode.WrStall, 2,"")+")cpu_state <= cpu_state_fetch;",true,false,"latched_is_lu: reg_out
        // <= mem_rdata_word;")); // Stall if in load state // TODO could be an issue like re-start reading
        toFile.ReplaceContent(this.ModFile("picorv32"), "if (!mem_do_prefetch || mem_done) begin",
                              new ToWrite("if(" + language.CreateNodeName(BNode.WrStall, stages[2], "") +
                                              ") begin end else if (!mem_do_prefetch || mem_done) begin",
                                          true, false, "cpu_state_stmem: begin")); // Stall if in write state
        toFile.ReplaceContent(
            this.ModFile("picorv32"), "if (reg_sh == 0) begin",
            new ToWrite("if(" + language.CreateNodeName(BNode.WrStall, stages[2], "") + ")begin   end else if (reg_sh == 0) begin", true,
                        false, "cpu_state_shift: begin")); // Stall if in shift state
        toFile.ReplaceContent(
            this.ModFile("picorv32"), "if ((TWO_CYCLE_ALU || TWO_CYCLE_COMPARE)",
            new ToWrite("if(" + language.CreateNodeName(BNode.WrStall, stages[2], "") +
                            ")begin end else if ((TWO_CYCLE_ALU || TWO_CYCLE_COMPARE) && (alu_wait || alu_wait_2)) begin",
                        true, false, "cpu_state_exec: begin")); // stall execute
        toFile.UpdateContent(this.ModFile("picorv32"), "if (!mem_do_prefetch || mem_done) begin",
                             new ToWrite("if(" + language.CreateNodeName(BNode.WrStall, stages[2], "") + ")begin end else\n", true, false,
                                         "cpu_state_ldmem: begin", true));

        // Make sure next instr is not decoded yet
        String replaceText = "assign mem_rdata_latched = COMPRESSED_ISA && mem_la_use_prefetched_high_word ? {16'bx, mem_16bit_buffer} :";
        String replaceWith =
            "reg [31:0] mem_rdata_latched_reg;\n"
            + "wire [31:0] isax_mem_rdata_latched;\n"
            + "assign isax_mem_rdata_latched = COMPRESSED_ISA && mem_la_use_prefetched_high_word ? {16'bx, mem_16bit_buffer} :";
        toFile.ReplaceContent(this.ModFile("picorv32"), replaceText, new ToWrite(replaceWith, false, true, ""));
        String addText = "always @(posedge " + language.clk + ") begin\n"
                         + "        if(!" + language.CreateNodeName(BNode.WrStall, stages[2], "") + " )\n"
                         + "        mem_rdata_latched_reg <= isax_mem_rdata_latched; \n"
                         + "        end\n"
                         + "    assign mem_rdata_latched =!WrStall_2_i ? isax_mem_rdata_latched:  mem_rdata_latched_reg;";
        addLogic(addText);

        // Avoid new decoding based on mem_data_q. Without this, alu result would be wrong
        replaceText = "if (decoder_trigger && !decoder_pseudo_trigger) begin";
        replaceWith = "if (decoder_trigger && !decoder_pseudo_trigger && (!" + language.CreateNodeName(BNode.WrStall, stages[2], "") +
                      " || (~(|cpu_state[3:0])))) begin";
        toFile.ReplaceContent(this.ModFile("picorv32"), replaceText, new ToWrite(replaceWith, false, true, ""));

      } else if (this.ContainsOpInStage(BNode.RdStall, 2)) {
        addDeclaration("wire " + language.CreateNodeName(BNode.WrStall, stages[2], "") + ";\n");
        addLogic("assign " + language.CreateNodeName(BNode.WrStall, stages[2], "") + " = 0;\n");
      }

      if (this.ContainsOpInStage(BNode.WrStall, 1)) {
        // avoid simulator error that signal used before definition
        addDeclaration("localparam cpu_state_ld_rs1 = 8'b00100000;\n");
        toFile.UpdateContent(
            this.ModFile("picorv32"), "(CATCH_ILLINSN || WITH_PCPI) && instr_trap: begin",
            new ToWrite("(" + language.CreateNodeName(BNode.WrStall, stages[1], "") + ") : begin end\n", false, true, "", true));

        toFile.ReplaceContent(this.ModFile("picorv32"), "localparam cpu_state_ld_rs1", new ToWrite(" ", false, true, ""));
        toFile.ReplaceContent(this.ModFile("picorv32"), "reg [7:0] cpu_state;", new ToWrite(" ", false, true, ""));
        toFile.ReplaceContent(this.ModFile("picorv32"), "if (mem_do_prefetch || mem_do_rinst || mem_do_rdata) begin",
                              new ToWrite("if ((mem_do_prefetch || mem_do_rinst || mem_do_rdata) && (!(" +
                                              language.CreateNodeName(BNode.WrStall, stages[1], "") +
                                              ") || (cpu_state != cpu_state_ld_rs1))) begin",
                                          false, true, ""));
        toFile.UpdateContent(this.ModFile("picorv32"), "endcase",
                             new ToWrite("if (" + spawnStall + " " + language.CreateNodeName(BNode.WrStall, stages[1], "") +
                                             ") cpu_state <= cpu_state_ld_rs1;",
                                         true, false, "cpu_state <= cpu_state_ld_rs2;"));
      } else if (this.ContainsOpInStage(BNode.RdStall, 1)) {
        addDeclaration("wire " + language.CreateNodeName(BNode.WrStall, stages[1], "") + ";\n");
        addLogic("assign " + language.CreateNodeName(BNode.WrStall, stages[1], "") + " = 0;\n");
      }

      if (this.ContainsOpInStage(BNode.WrStall, 0)) {
        String grep = "cpu_state_fetch: begin";
        String addLogic = "if (!" + language.CreateNodeName(BNode.WrStall, stages[0], "") + ") begin\n";
        toFile.UpdateContent(this.ModFile("picorv32"), grep, new ToWrite(addLogic, false, true, ""));
        grep = "cpu_state_ld_rs1: begin";
        addLogic = "end\n";
        toFile.UpdateContent(this.ModFile("picorv32"), grep, new ToWrite(addLogic, false, true, "", true));
      } else if (this.ContainsOpInStage(BNode.RdStall, 0)) {
        addDeclaration("wire " + language.CreateNodeName(BNode.WrStall, stages[0], "") + ";\n");
        addLogic("assign " + language.CreateNodeName(BNode.WrStall, stages[0], "") + " = 0;\n");
      }
    }
  }

  // WrFlush does not make sense without WrPC for this core and WrFlush is set in SCAL in case of WrPC
  private void IntegrateISAX_WrFlush() {
    for (int stageNum = 0; stageNum <= 3; stageNum++) {
      PipelineStage stage = stages[stageNum];
      if (this.ContainsOpInStage(BNode.RdFlush, stageNum)) {
        addDeclaration(language.CreateDeclSig(BNode.WrFlush, stage, "", false));
        if (!this.ContainsOpInStage(BNode.WrFlush, stageNum))
          addLogic(language.CreateAssign(language.CreateLocalNodeName(BNode.WrFlush, stage, ""), "0"));
        else
          addLogic(language.CreateAssign(language.CreateLocalNodeName(BNode.WrFlush, stage, ""),
                                         language.CreateNodeName(BNode.WrFlush, stage, "")));
      }
    }
    for (int stageNum = 0; stageNum < 2; stageNum++) { // later stages can have side-effects
      PipelineStage stage = stages[stageNum];
      if (this.ContainsOpInStage(BNode.WrFlush, stageNum)) {
        String grep = "irq_pending <= next_irq_pending & ~MASKED_IRQ;";
        String condition = "";
        if (stageNum == 0)
          condition = " && cpu_state ==cpu_state_fetch ";
        if (stageNum == 1)
          condition = " && cpu_state ==cpu_state_ld_rs1 ";
        String addLogic = "if(" + language.CreateNodeName(BNode.WrFlush, stage, "") + condition + " ) cpu_state <= cpu_state_fetch; \n";
        toFile.UpdateContent(this.ModFile("picorv32"), grep, new ToWrite(addLogic, false, true, ""));
      }
    }
  }

  private void IntegrateISAX_WrRD() {
    if (this.op_stage_instr.containsKey(BNode.WrRD))
      for (PipelineStage stage : this.op_stage_instr.get(BNode.WrRD).keySet()) {
        toFile.UpdateContent(this.ModFile("picorv32"), "case (1'b1)",
                             new ToWrite(language.CreateNodeName(BNode.WrRD_valid, stage, "") + ": begin\n" + tab + "cpuregs_wrdata = " +
                                             language.CreateNodeName(BNode.WrRD, stage, "") + ";\n" + tab + "cpuregs_write = 1;\n"
                                             + "end ",
                                         true, false, "cpuregs_wrdata = 'bx;"));
      }

    if (this.ContainsOpInStage(BNode.WrRD, 2)) {
      String grep = "if (cpu_state == cpu_state_fetch";
      String prereq = "cpuregs_wrdata = 'bx;";
      String replace = "if (cpu_state == cpu_state_fetch || " + (language.CreateNodeName(BNode.WrRD_valid, stages[2], "")) + ") begin";
      toFile.ReplaceContent(this.ModFile("picorv32"), grep, new ToWrite(replace, true, false, prereq));
    }
  }

  private void IntegrateISAX_SpawnRD() {
    int spawnStageNum = this.picorv32.maxStage + 1;

    // Spawn currently allowed?
    if (op_stage_instr.containsKey(BNode.WrRD_spawn) || op_stage_instr.containsKey(BNode.RdMem_spawn) ||
        op_stage_instr.containsKey(BNode.WrMem_spawn)) {
      String logic = "";
      String stall = "";

      if (this.ContainsOpInStage(BNode.WrStall, 1))
        stall += " || " + language.CreateNodeName(BNode.WrStall, stages[1], "");
      logic += language.CreateAssign(language.CreateNodeName(BNode.ISAX_spawnAllowed, stages[1], ""),
                                     "(cpu_state == cpu_state_ld_rs1) && (mem_state==0)" + stall);
      addLogic(logic);
    }

    if (op_stage_instr.containsKey(BNode.WrRD_spawn)) {
      toFile.ReplaceContent(this.ModFile("picorv32"), "if (resetn && cpuregs_write && latched_rd",
                            new ToWrite("if (resetn && cpuregs_write && latched_rd || " +
                                            language.CreateNodeName(BNode.WrRD_spawn_valid, stages[spawnStageNum], "") + ")",
                                        false, true, ""));
      toFile.ReplaceContent(this.ModFile("picorv32"), "if (cpu_state == cpu_state_fetch)",
                            new ToWrite("if ((cpu_state == cpu_state_fetch) || (" +
                                            language.CreateNodeName(BNode.WrRD_spawn_valid, stages[spawnStageNum], "") + ")) begin",
                                        true, false, "cpuregs_wrdata = 'bx;"));
      toFile.UpdateContent(this.ModFile("picorv32"), "case (1'b1)",
                           new ToWrite("(" + language.CreateNodeName(BNode.WrRD_spawn_valid, stages[spawnStageNum], "") + "): begin\n" +
                                           tab +
                                           "cpuregs_wrdata = " + language.CreateNodeName(BNode.WrRD_spawn, stages[spawnStageNum], "") +
                                           ";\n" + tab + "cpuregs_write = 1;\n"
                                           + "end ",
                                       true, false, "cpuregs_wrdata = 'bx;"));
      String commit = " begin if(" + language.CreateNodeName(BNode.WrRD_spawn_valid, stages[spawnStageNum], "") + ")\n" + tab + "cpuregs[" +
                      language.CreateNodeName(BNode.WrRD_spawn_addr, stages[spawnStageNum], "") + "] <= cpuregs_wrdata;\n"
                      + "else\n" + tab + "cpuregs[latched_rd] <= cpuregs_wrdata;\n"
                      + "end";
      toFile.ReplaceContent(this.ModFile("picorv32"), "cpuregs[latched_rd] <= cpuregs_wrdata;", new ToWrite("\n", false, true, ""));
      toFile.UpdateContent(this.ModFile("picorv32"), "`else", new ToWrite(commit, true, false, "`elsif PICORV32_TESTBUG_002"));
    }
  }

  private void IntegrateISAX_SpawnMem() {
    String toDeclare = "";
    int spawnStageNum = this.picorv32.maxStage + 1;
    if (op_stage_instr.containsKey(BNode.WrMem_spawn) || op_stage_instr.containsKey(BNode.RdMem_spawn)) {
      String rdMem = "";
      if (this.op_stage_instr.containsKey(BNode.RdMem_spawn))
        rdMem = "assign " + language.CreateNodeName(BNode.RdMem_spawn, stages[spawnStageNum], "") + " = mem_rdata;	";
      addDeclaration("reg mem_state_spawn;\n");
      String mem_FSM =
          "if(" + language.CreateNodeName(BNode.WrMem_spawn_validReq, stages[spawnStageNum], "") + ") begin\n "
          + "case (mem_state_spawn)\n"
          + "	0: begin\n"
          + "		if (" + language.CreateNodeName(BNode.WrMem_spawn_validReq, stages[spawnStageNum], "") + ") begin\n"
          + "			mem_valid <= 1;\n"
          + "			mem_addr <= " + language.CreateNodeName(BNode.WrMem_spawn_addr, stages[spawnStageNum], "") + ";\n"
          + "			mem_wstrb <= mem_la_wstrb & {4{" +
          language.CreateNodeName(BNode.WrMem_spawn_write, stages[spawnStageNum], "") + "}};\n"
          + "			mem_wdata <= " + language.CreateNodeName(BNode.WrMem_spawn, stages[spawnStageNum], "") + ";\n"
          + "		    mem_state_spawn <= 1;\n"
          + "         mem_instr <= 0;"
          + "			\n"
          + "		end\n"
          + "	end\n"
          + "	1: begin\n"
          + "		if (mem_valid && mem_ready) begin\n"
          + "				mem_valid <= 0;\n"
          + "				mem_la_secondword <= 0;\n"
          + "				\n"
          + "				mem_state_spawn <= 0;\n"
          + "		end\n"
          + "	end\n"
          + "endcase\n"
          + "end\n"
          + "if(" + language.reset + ")\n"
          + " mem_state_spawn <=0;\n"
          + "\n";
      addLogic("assign " + language.CreateNodeName(BNode.RdMem_spawn_validResp, stages[spawnStageNum], "") +
               " = mem_state_spawn && (mem_valid && mem_ready);\n" + rdMem);
      toFile.UpdateContent(this.ModFile("picorv32"), "if (clear_prefetched_high_word)", new ToWrite(mem_FSM, false, true, "", true));
    }
  }

  private void IntegrateISAX_Mem() {
    int stageNum = this.picorv32.GetNodes().get(BNode.WrMem).GetLatest().asInt();
    PipelineStage stage = stages[stageNum];
    boolean wrMem = this.op_stage_instr.containsKey(BNode.WrMem) && this.op_stage_instr.get(BNode.WrMem).containsKey(stage);
    boolean rdMem = this.op_stage_instr.containsKey(BNode.RdMem) && this.op_stage_instr.get(BNode.RdMem).containsKey(stage);

    if (wrMem) {
      String validISAXWrMem = language.CreateNodeName(BNode.WrMem_validReq, stages[stageNum - 2], "");

      toFile.ReplaceContent(
          this.ModFile("picorv32"), "instr_sb    <= is_sb_sh_sw",
          new ToWrite("instr_sb    <= (is_sb_sh_sw || (" + validISAXWrMem + ")) && mem_rdata_q[14:12] == 3'b000;", false, true, ""));
      toFile.ReplaceContent(
          this.ModFile("picorv32"), "instr_sh    <= is_sb_sh_sw",
          new ToWrite("instr_sh    <= (is_sb_sh_sw || (" + validISAXWrMem + ")) && mem_rdata_q[14:12] == 3'b001;", false, true, ""));
      toFile.ReplaceContent(
          this.ModFile("picorv32"), "instr_sw    <= is_sb_sh_sw",
          new ToWrite("instr_sw    <= (is_sb_sh_sw || (" + validISAXWrMem + ")) && mem_rdata_q[14:12] == 3'b010;", false, true, ""));

      validISAXWrMem = language.CreateNodeName(BNode.WrMem_validReq, stages[stageNum - 1], "");

      toFile.ReplaceContent(this.ModFile("picorv32"), "reg_op1 <=",
                            new ToWrite("reg_op1 <= (" + language.CreateNodeName(BNode.WrMem_addr_valid, stage, "") + ") ? " +
                                            language.CreateNodeName(BNode.WrMem_addr, stage, "") + " :  reg_op1 + decoded_imm;",
                                        true, false, "cpu_state_stmem: begin"));
      toFile.ReplaceContent(this.ModFile("picorv32"),
                            "is_sb_sh_sw:", new ToWrite("|{is_sb_sh_sw, " + validISAXWrMem + "}:", true, false, "|{instr_jalr,"));
      toFile.ReplaceContent(this.ModFile("picorv32"), "is_sb_sh_sw",
                            new ToWrite("is_sb_sh_sw ||" + validISAXWrMem + ": begin", true, false, "default: begin"));

      // Update WDATA with ISAX wdata
      toFile.ReplaceContent(this.ModFile("picorv32"), "mem_wdata <= mem_la_wdata;",
                            new ToWrite("mem_wdata <= (" + language.CreateNodeName(BNode.WrMem_validReq, stage, "") + ") ? " +
                                            language.CreateLocalNodeName("isax_" + BNode.WrMem, stage, "") + " : mem_la_wdata;\n",
                                        true, false, "if (mem_la_write) begin"));
      this.addLogic("assign " + language.CreateLocalNodeName("isax_" + BNode.WrMem, stage, "") + " = "
                    + " (mem_wordsize == 0) ? " + language.CreateNodeName(BNode.WrMem, stage, "") + " : ((mem_wordsize == 1) ? {2{" +
                    language.CreateNodeName(BNode.WrMem, stage, "") + "[15:0]}} : {4{" + language.CreateNodeName(BNode.WrMem, stage, "") +
                    "}});\n");
      this.addDeclaration("wire [32 -1:0] " + language.CreateLocalNodeName("isax_" + BNode.WrMem, stage, "") + ";\n");

      // Update LA_WDATA with ISAX data
      this.toFile.UpdateContent(this.ModFile("picorv32"), "mem_la_wdata = reg_op2;",
                                new ToWrite("if(" + language.CreateNodeName(BNode.WrMem_validReq, stage, "") +
                                                ") mem_la_wdata = " + language.CreateNodeName(BNode.WrMem, stage, "") + ";\n",
                                            false, true, ""));
      this.toFile.UpdateContent(this.ModFile("picorv32"), "mem_la_wdata = {2{reg_op2[15:0]}};",
                                new ToWrite("if(" + language.CreateNodeName(BNode.WrMem_validReq, stage, "") + ") mem_la_wdata = {2{" +
                                                language.CreateNodeName(BNode.WrMem, stage, "") + "[15:0]}};\n",
                                            false, true, ""));
      this.toFile.UpdateContent(this.ModFile("picorv32"), "mem_la_wdata = {4{reg_op2[7:0]}};",
                                new ToWrite("if(" + language.CreateNodeName(BNode.WrMem_validReq, stage, "") + ") mem_la_wdata = {4{" +
                                                language.CreateNodeName(BNode.WrMem, stage, "") + "[7:0]}};\n",
                                            false, true, ""));

      // Cancel invalid transfers
      String addText = "if(!" + language.CreateNodeName(BNode.WrMem_validReq, stage, "") + " && ISAX_isisax_2 ) begin \n" + tab +
                       "cpu_state <= cpu_state_fetch;\n" + tab + "decoder_trigger <= 1;\n"
                       + "end else";
      String grep = "if (!mem_do_wdata)";
      String prereq = "cpu_state_stmem: begin";
      this.toFile.UpdateContent(this.ModFile("picorv32"), grep, new ToWrite(addText, true, false, prereq, true));
    }
    if (rdMem) {
      String earlyValid0 = language.CreateNodeName(BNode.RdMem_validReq, stages[stageNum - 2], "");

      toFile.ReplaceContent(
          this.ModFile("picorv32"), "instr_lb    <= is_lb_lh_lw_lbu_lhu",
          new ToWrite("instr_lb    <= (is_lb_lh_lw_lbu_lhu || (" + earlyValid0 + ")) && mem_rdata_q[14:12] == 3'b000;", false, true, ""));
      toFile.ReplaceContent(
          this.ModFile("picorv32"), "instr_lh    <= is_lb_lh_lw_lbu_lhu",
          new ToWrite("instr_lh    <= (is_lb_lh_lw_lbu_lhu || (" + earlyValid0 + ")) && mem_rdata_q[14:12] == 3'b001;", false, true, ""));
      toFile.ReplaceContent(
          this.ModFile("picorv32"), "instr_lw    <= is_lb_lh_lw_lbu_lhu",
          new ToWrite("instr_lw    <= (is_lb_lh_lw_lbu_lhu || (" + earlyValid0 + ")) && mem_rdata_q[14:12] == 3'b010;", false, true, ""));
      toFile.ReplaceContent(
          this.ModFile("picorv32"), "instr_lbu   <= is_lb_lh_lw_lbu_lhu",
          new ToWrite("instr_lbu    <= (is_lb_lh_lw_lbu_lhu || (" + earlyValid0 + ")) && mem_rdata_q[14:12] == 3'b100;", false, true, ""));
      toFile.ReplaceContent(
          this.ModFile("picorv32"), "instr_lhu   <= is_lb_lh_lw_lbu_lhu",
          new ToWrite("instr_lhu    <= (is_lb_lh_lw_lbu_lhu || (" + earlyValid0 + ")) && mem_rdata_q[14:12] == 3'b101;", false, true, ""));

      toFile.ReplaceContent(this.ModFile("picorv32"), "reg_op1 <=",
                            new ToWrite("reg_op1 <= (" + language.CreateNodeName(BNode.RdMem_addr_valid, stage, "") + ") ? " +
                                            language.CreateNodeName(BNode.RdMem_addr, stage, "") + " :  reg_op1 + decoded_imm;",
                                        true, false, "cpu_state_ldmem: begin"));

      toFile.ReplaceContent(this.ModFile("picorv32"), "|{instr_jalr,",
                            new ToWrite("|{instr_jalr, is_lb_lh_lw_lbu_lhu, is_alu_reg_imm, " +
                                            language.CreateNodeName(BNode.RdMem_validReq, stages[stageNum - 2], "") + "}:",
                                        false, true, ""));

      toFile.ReplaceContent(this.ModFile("picorv32"), "is_lb_lh_lw_lbu_lhu && !instr_trap",
                            new ToWrite("(is_lb_lh_lw_lbu_lhu && !instr_trap) ||" +
                                            language.CreateNodeName(BNode.RdMem_validReq, stages[stageNum - 1], "") + ": begin",
                                        false, true, ""));
    }

    if (rdMem || wrMem) {
      String clause = "";
      if (rdMem)
        clause = " &&  !" + language.CreateNodeName(BNode.RdMem_addr_valid, stages[2], "") + " ";
      if (wrMem)
        clause = this.language.OpIfNEmpty(clause, " && ") + " !" + language.CreateNodeName(BNode.WrMem_addr_valid, stages[2], "") + " ";

      String toAdd = "if (CATCH_MISALIGN " + clause + " && resetn && (mem_do_rdata || mem_do_wdata)) begin\n";
      String grep = "if (CATCH_MISALIGN && resetn && (mem_do_rdata";
      toFile.ReplaceContent(this.ModFile("picorv32"), grep, new ToWrite(toAdd, false, true, ""));
    }
  }

  // condition expression will be added with ||
  String[] stallConds = new String[0];
  private void addToStallCond(int stage, String newCond) { stallConds[stage] += (stallConds[stage].isEmpty() ? "" : " || ") + newCond; }

  private void IntegrateISAX_WrPC() {
    // TODO check stages 0,3. 1,2 checked
    if (this.ContainsOpInStage(BNode.WrPC, 0)) {
      //NOTE: mem_do_rinst<=1, cpu_state<=cpu_state_fetch cause the fetch to be repeated, scrapping what was already read from memory.
      //      At the same time, RdStall_0 is set to 0, so SCAL does apply custom state updates.
      //NOTE: A combination of WrPC_0 and WrRD is not possible currently.
      String text = "if(" + language.CreateNodeName(BNode.WrPC_valid, stages[0], "") + " )begin \n" +
                    tab + "mem_do_rinst <= 1; \n" +
                    tab + "reg_next_pc <= " + language.CreateNodeName(BNode.WrPC, stages[0], "") + "; \n" +
                    tab + "cpu_state <= cpu_state_fetch; \n" +
                    "end";
      toFile.UpdateContent(this.ModFile("picorv32"), "end", new ToWrite(text, true, false, "cpu_state <= cpu_state_ld_rs1;"));
    }

    if (this.ContainsOpInStage(BNode.WrPC, 1)) {
      addToStallCond(1, String.format("(%s && mem_do_prefetch && !mem_done)", language.CreateNodeName(BNode.WrPC_valid, stages[1], "")));
      String textToAdd = "if(" + language.CreateNodeName(BNode.WrPC_valid, stages[1], "") + " )begin \n" + tab +
                         "reg_next_pc <= " + language.CreateNodeName(BNode.WrPC, stages[1], "") + "; \n" + tab +
                         "reg_out <= " + language.CreateNodeName(BNode.WrPC, stages[1], "") + ";\n" + tab + "decoder_trigger <= 0;\n" +
                         tab + "//Two uses of setting mem_do_rinst here:\n" + tab +
                         "//a) if prefetch is set, make sure the mem FIFO will commit it and get ready for the actual transfer;\n" + tab +
                         "//b) once the prefetched read is done, start the new read at the WrPC destination.\n" + tab +
                         "set_mem_do_rinst = 1;\n" + tab + "if (!mem_do_prefetch || mem_done) begin\n" + tab + tab +
                         "latched_rd <= 0;\n" + tab + tab + "latched_branch <= 1;\n" + tab + tab + "latched_stalu <= 0;\n" + tab + tab +
                         "cpu_state <= cpu_state_fetch; \n" + tab + "end\n"
                         + "end else";
      toFile.ReplaceContent(this.ModFile("picorv32"), "(* parallel_case *)",
                            new ToWrite(textToAdd, true, false, "cpu_state_ld_rs1: ", true));
    }
    if (this.ContainsOpInStage(BNode.WrPC, 2)) {
      String textToAdd = "if((cpu_state != cpu_state_ldmem && cpu_state != cpu_state_stmem) | (!mem_do_prefetch && mem_done)) begin\n" +
                         tab + "if(" + language.CreateNodeName(BNode.WrPC_valid, stages[2], "") +
                         " )begin // we don't need to check if we are in correct stage. This is done by SCAL \n" + tab + tab +
                         "reg_next_pc <= " + language.CreateNodeName(BNode.WrPC, stages[2], "") + "; \n" + tab + tab +
                         "reg_out <= " + language.CreateNodeName(BNode.WrPC, stages[2], "") + ";\n" + tab + tab +
                         "decoder_trigger <= 0;\n" + tab + tab + "set_mem_do_rinst = 1;\n" + tab + tab + "latched_rd <= 0;\n" + tab +
                         tab + "latched_branch <= 1;\n" + tab + tab + "latched_stalu <= 0;\n" + tab + tab +
                         "cpu_state <= cpu_state_fetch; \n" + tab + "end\n  "
                         + "end\n";
      String grepText = "if (CATCH_MISALIGN && resetn && (mem_do_rdata || mem_do_wdata)) begin";
      toFile.UpdateContent(this.ModFile("picorv32"), grepText, new ToWrite(textToAdd, false, true, "", true));
    }

    if (this.ContainsOpInStage(BNode.WrPC, 3)) {
      String text = "if(" + language.CreateNodeName(BNode.WrPC_valid, stages[3], "") + " )begin \n" + tab + "mem_do_rinst <= 1; \n" + tab +
                    "reg_next_pc <= " + language.CreateNodeName(BNode.WrPC, stages[3], "") + "; \n" + tab +
                    "cpu_state <= cpu_state_fetch; \n"
                    + "end";
      toFile.UpdateContent(this.ModFile("picorv32"), "end", new ToWrite(text, true, false, "cpu_state <= cpu_state_ld_rs1;"));
    }

    if (op_stage_instr.containsKey(BNode.WrPC_spawn)) {
      String spawn = "if(" + language.CreateNodeName(BNode.WrPC_spawn_valid, stages[this.picorv32.maxStage + 1], "") + ") begin \n" + tab +
                     "mem_do_rinst <= 1; \n" + tab +
                     "reg_next_pc <= " + language.CreateNodeName(BNode.WrPC_spawn, stages[this.picorv32.maxStage + 1], "") + "; \n" +
                     tab +
                     "cpu_state <= cpu_state_fetch; \n"
                     //	+ tab + "latched_branch <= 1;"
                     + "end";
      toFile.UpdateContent(this.ModFile("picorv32"), "end", new ToWrite(spawn, true, false, "cpu_state <= cpu_state_ld_rs1;"));
    }
  }

  private void addLogic(String text) {
    toFile.UpdateContent(this.ModFile(topModule), "endmodule", new ToWrite(text, false, true, "", true, topModule));
  }

  private void addDeclaration(String text) {
    toFile.UpdateContent(this.ModFile(topModule), ");", new ToWrite(text, true, false, "module picorv32 ", false, topModule));
  }

  private boolean ContainsOpInStage(SCAIEVNode operation, int stage) { return ContainsOpInStage(operation, stages[stage]); }
  private boolean ContainsOpInStage(SCAIEVNode operation, PipelineStage stage) {
    return op_stage_instr.containsKey(operation) && op_stage_instr.get(operation).containsKey(stage);
  }
  private void ConfigPicoRV32() {
    this.PopulateNodesMap();
    PutModule(pathPicoRV32 + "/picorv32.v", "picorv32", pathPicoRV32 + "/picorv32.v", "", "picorv32");
    Module newModule = new Module();
    newModule.name = "extension_name";

    int spawnStage = this.picorv32.maxStage + 1;
    for (int i = 0; i < spawnStage; i++) {
      this.PutNode(" ", "", "picorv32", BNode.WrPC, stages[i]);
      this.PutNode(" ", "", "picorv32", BNode.WrPC_valid, stages[i]);
    }
    this.PutNode(" ", "", "picorv32", BNode.WrPC_spawn, stages[spawnStage]);
    this.PutNode(" ", "", "picorv32", BNode.WrPC_spawn_valid, stages[spawnStage]);

    this.PutNode(" ", "(cpu_state ==  cpu_state_fetch) ? reg_next_pc : reg_pc", "picorv32", BNode.RdPC, stages[0]);
    this.PutNode(" ", "reg_pc", "picorv32", BNode.RdPC, stages[1]);
    this.PutNode(" ", "reg_pc", "picorv32", BNode.RdPC, stages[2]);
    this.PutNode(" ", "reg_pc", "picorv32", BNode.RdPC, stages[3]);

    this.PutNode(" ", "(cpu_state ==  cpu_state_fetch &&  mem_do_rinst) ? mem_rdata_latched :  rdInstr_0_r", "picorv32", BNode.RdInstr,
                 stages[0]);
    this.PutNode(" ", "", "picorv32", BNode.RdInstr, stages[4]);

    this.PutNode("", "cpuregs_rs1", "picorv32", BNode.RdRS1, stages[1]);
    this.PutNode("", "cpuregs_rs1", "picorv32", BNode.RdRS1, stages[2]); // TODO check, as not the same in model
    this.PutNode("", "cpuregs_rs1", "picorv32", BNode.RdRS1, stages[3]);

    this.PutNode("", "cpuregs_rs2", "picorv32", BNode.RdRS2, stages[1]);
    this.PutNode("reg", "cpuregs_rs2", "picorv32", BNode.RdRS2, stages[2]);
    this.PutNode("reg", "cpuregs_rs2", "picorv32", BNode.RdRS2, stages[3]);

    this.PutNode(" ", "", "picorv32", BNode.WrRD, stages[3]);
    this.PutNode(" ", "", "picorv32", BNode.WrRD_valid, stages[3]);

    this.PutNode(" ", "", "picorv32", BNode.WrRD, stages[2]);
    this.PutNode(" ", "", "picorv32", BNode.WrRD_valid, stages[2]);

    this.PutNode("wire", "", "picorv32", BNode.RdIValid, stages[0]);
    this.PutNode("wire", "", "picorv32", BNode.RdIValid, stages[1]);
    this.PutNode("wire", "", "picorv32", BNode.RdIValid, stages[2]);
    this.PutNode("reg", "", "picorv32", BNode.RdIValid, stages[3]);
    this.PutNode("reg", "", "picorv32", BNode.RdIValid, stages[4]);

    int stageMem = picorv32.GetNodes().get(BNode.RdMem).GetLatest().asInt();
    this.PutNode(" ", "mem_rdata", "picorv32", BNode.RdMem, stages[stageMem]);
    this.PutNode(" ", "mem_ready & !mem_instr & (mem_wstrb==0)", "picorv32", BNode.RdMem_spawn_validResp,
                 stages[stageMem]); // in theory should be covered by !stall in ISAX
    this.PutNode(" ", "", "picorv32", BNode.WrMem, stages[stageMem]);
    this.PutNode(" ", "", "picorv32", BNode.WrMem_validReq, stages[stageMem]);
    this.PutNode(" ", "", "picorv32", BNode.RdMem_validReq, stages[stageMem]);
    for (int iStageValid = 0; iStageValid < stageMem; ++iStageValid) {
      this.PutNode(" ", "", "picorv32", BNode.WrMem_validReq, stages[iStageValid]);
      this.PutNode(" ", "", "picorv32", BNode.RdMem_validReq, stages[iStageValid]);
    }
    this.PutNode(" ", "", "picorv32", BNode.WrMem_addr, stages[stageMem]);
    this.PutNode(" ", "", "picorv32", BNode.WrMem_size, stages[stageMem]);
    this.PutNode(" ", "", "picorv32", BNode.WrMem_addr_valid, stages[stageMem]);
    this.PutNode(" ", "", "picorv32", BNode.RdMem_addr, stages[stageMem]);
    this.PutNode(" ", "", "picorv32", BNode.RdMem_size, stages[stageMem]);
    this.PutNode(" ", "", "picorv32", BNode.RdMem_addr_valid, stages[stageMem]);

    this.PutNode(" ", language.CreateLocalNodeName(BNode.RdStall, stages[0], ""), "picorv32", BNode.RdStall, stages[0]);
    this.PutNode(" ", language.CreateLocalNodeName(BNode.RdStall, stages[1], ""), "picorv32", BNode.RdStall, stages[1]);
    this.PutNode(" ", language.CreateLocalNodeName(BNode.RdStall, stages[2], ""), "picorv32", BNode.RdStall, stages[2]);
    this.PutNode(" ", language.CreateLocalNodeName(BNode.RdStall, stages[3], ""), "picorv32", BNode.RdStall, stages[3]);

    this.PutNode(" ", "", "picorv32", BNode.WrStall, stages[0]);
    this.PutNode(" ", "", "picorv32", BNode.WrStall, stages[1]);
    this.PutNode(" ", "", "picorv32", BNode.WrStall, stages[2]);
    this.PutNode(" ", "", "picorv32", BNode.WrStall, stages[3]);

    this.PutNode(" ", "0", "picorv32", BNode.RdFlush, stages[3]); // TODO should be improved
    List<String> wrFlushOthers = new ArrayList<>();
    if (ContainsOpInStage(BNode.WrFlush, 3))
      wrFlushOthers.add(language.CreateLocalNodeName(BNode.WrFlush, stages[3], ""));
    this.PutNode(" ", wrFlushOthers.stream().reduce((a, b) -> a + " || " + b).orElse("0"), "picorv32", BNode.RdFlush, stages[2]);
    if (ContainsOpInStage(BNode.WrFlush, 2))
      wrFlushOthers.add(language.CreateLocalNodeName(BNode.WrFlush, stages[2], ""));
    this.PutNode(" ", wrFlushOthers.stream().reduce((a, b) -> a + " || " + b).orElse("0"), "picorv32", BNode.RdFlush, stages[1]);
    if (ContainsOpInStage(BNode.WrFlush, 1))
      wrFlushOthers.add(language.CreateLocalNodeName(BNode.WrFlush, stages[1], ""));
    this.PutNode(" ", wrFlushOthers.stream().reduce((a, b) -> a + " || " + b).orElse("0"), "picorv32", BNode.RdFlush, stages[0]);

    this.PutNode(" ", "(cpu_state == cpu_state_fetch)", "picorv32", BNode.RdInStageValid, stages[3]);
    this.PutNode(" ", " (|cpu_state[3:0])", "picorv32", BNode.RdInStageValid, stages[2]); // exec,shift,stmem,ldmem
    this.PutNode(" ", "(cpu_state == cpu_state_ld_rs1)", "picorv32", BNode.RdInStageValid, stages[1]);
    this.PutNode(" ", "(decoder_trigger && cpu_state == cpu_state_fetch)", "picorv32", BNode.RdInStageValid, stages[0]);

    // WrFlush does not make sense without WrPC for this core and WrFlush is set in SCAL in case of WrPC
    this.PutNode(" ", "", "picorv32", BNode.WrFlush, stages[0]);
    this.PutNode(" ", "", "picorv32", BNode.WrFlush, stages[1]);
    this.PutNode(" ", "", "picorv32", BNode.WrFlush, stages[2]);
    this.PutNode(" ", "", "picorv32", BNode.WrFlush, stages[3]);

    this.PutNode(" ", "", "picorv32", BNode.WrRD_spawn, stages[spawnStage]);
    this.PutNode(" ", "", "picorv32", BNode.WrRD_spawn_valid, stages[spawnStage]);
    this.PutNode(" ", "", "picorv32", BNode.WrRD_spawn_addr, stages[spawnStage]);
    this.PutNode(" ", "1", "picorv32", BNode.WrRD_spawn_validResp, stages[spawnStage]);

    this.PutNode("", "", "picorv32", BNode.RdMem_spawn, stages[spawnStage]);
    this.PutNode("", "", "picorv32", BNode.RdMem_spawn_validResp, stages[spawnStage]);
    this.PutNode(" ", "", "picorv32", BNode.WrMem_spawn, stages[spawnStage]);
    this.PutNode("", "", "picorv32", BNode.WrMem_spawn_validResp, stages[spawnStage]);
    this.PutNode(" ", "", "picorv32", BNode.RdMem_spawn_validReq, stages[spawnStage]);
    this.PutNode(" ", "", "picorv32", BNode.WrMem_spawn_validReq, stages[spawnStage]);
    this.PutNode(" ", "", "picorv32", BNode.RdMem_spawn_addr, stages[spawnStage]);
    this.PutNode(" ", "", "picorv32", BNode.WrMem_spawn_addr, stages[spawnStage]);
    this.PutNode(" ", "", "picorv32", BNode.RdMem_spawn_size, stages[spawnStage]);
    this.PutNode(" ", "", "picorv32", BNode.WrMem_spawn_size, stages[spawnStage]);
    this.PutNode(" ", "reg_op1 + decoded_imm", "picorv32", BNode.RdMem_spawn_defaultAddr, stages[spawnStage]); // TODO to be tested
    this.PutNode(" ", "reg_op1 + decoded_imm", "picorv32", BNode.WrMem_spawn_defaultAddr, stages[spawnStage]);
    this.PutNode(" ", "", "picorv32", BNode.RdMem_spawn_write, stages[spawnStage]);
    this.PutNode(" ", "", "picorv32", BNode.WrMem_spawn_write, stages[spawnStage]);

    this.PutNode(" ", "", "picorv32", BNode.ISAX_spawnAllowed, stages[1]);

    this.PutNode(" ", "current_spawn_addr", "picorv32", BNode.committed_rd_spawn, stages[spawnStage]); // was ISAX_execute_to_rf_select_s
  }
}
