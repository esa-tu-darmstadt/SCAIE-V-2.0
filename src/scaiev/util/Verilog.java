package scaiev.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.function.BiConsumer;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.backend.CoreBackend;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineStage;
import scaiev.util.GenerateText.DictWords;

public class Verilog extends GenerateText {
  // logging
  protected static final Logger logger = LogManager.getLogger();

  FileWriter toFile;
  public String tab = "    ";
  public String clk = "clk_i";
  public String reset = "rst_i";
  /**
   * Class constructor
   * @param toFile
   * @param core
   */
  public Verilog(BNode user_BNode, FileWriter toFile, CoreBackend core) {
    super(user_BNode);
    // initialize dictionary
    DictionaryDefinition();
    this.toFile = toFile;
    tab = toFile.tab;
    this.coreBackend = core;
  }

  /**
   * Use this constructor if you simply want to use the Verilog functions which do not use toFile or coreBackend. However, be aware it won't
   * work to use functions which require coreBackend &amp; toFile!!
   */
  public Verilog(BNode user_BNode) {
    super(user_BNode);
    // initialize dictionary
    DictionaryDefinition();
  }

  private void DictionaryDefinition() {
    // initialize dictionary
    dictionary.put(DictWords.module, "module");
    dictionary.put(DictWords.endmodule, "endmodule");
    dictionary.put(DictWords.reg, "reg");
    dictionary.put(DictWords.wire, "wire");
    dictionary.put(DictWords.assign, "assign");
    dictionary.put(DictWords.assign_eq, "=");
    dictionary.put(DictWords.logical_or, "||");
    dictionary.put(DictWords.bitwise_or, "|");
    dictionary.put(DictWords.logical_and, "&&");
    dictionary.put(DictWords.bitwise_and, "&");
    dictionary.put(DictWords.bitsselectRight, "]");
    dictionary.put(DictWords.bitsselectLeft, "[");
    dictionary.put(DictWords.ifeq, "==");
    dictionary.put(DictWords.bitsRange, ":");
    dictionary.put(DictWords.in, "input");
    dictionary.put(DictWords.out, "output");
    dictionary.put(DictWords.False, "0");
    dictionary.put(DictWords.True, "1");
    dictionary.put(DictWords.ZeroBit, "1'b0");
    dictionary.put(DictWords.OneBit, "1'b1");
  }

  @Override
  public Lang getLang() {
    return Lang.Verilog;
  }

  /**
   * Generates text like : signal signalName_s  :  std_logic_vector(1 : 0);
   * signalName created from &gt;operation, stage, instr&lt;
   */
  public String CreateDeclSig(SCAIEVNode operation, PipelineStage stage, String instr, boolean reg) {
    String decl = "";
    String size = "";
    if (coreBackend.NodeSize(operation, stage) != 1)
      size += dictionary.get(DictWords.bitsselectLeft) + " " + operation.size + " -1 : 0 " + dictionary.get(DictWords.bitsselectRight);
    String wire = "wire";
    if (reg)
      wire = "reg";
    decl = wire + " " + size + " " + CreateLocalNodeName(operation, stage, instr) + ";\n";
    return decl;
  }

  public String CreateDeclSig(SCAIEVNode operation, PipelineStage stage, String instr, boolean reg, String specificName) {
    String decl = "";
    String size = "";
    if (coreBackend.NodeSize(operation, stage) != 1)
      size += dictionary.get(DictWords.bitsselectLeft) + " " + operation.size + " -1 : 0 " + dictionary.get(DictWords.bitsselectRight);
    String wire = "wire";
    if (reg)
      wire = "reg";
    decl = wire + " " + size + " " + specificName + ";\n";
    return decl;
  }

  /**
   * Generates text like : signal signalName_reg  :  std_logic_vector(1 : 0);
   * signalName created from &gt;operation, stage, instr&lt;
   */
  public String CreateDeclReg(SCAIEVNode operation, PipelineStage stage, String instr) {
    String decl = "";
    String size = "";
    if (coreBackend.NodeSize(operation, stage) != 1)
      size += dictionary.get(DictWords.bitsselectLeft) + " " + operation.size + " -1 : 0 " + dictionary.get(DictWords.bitsselectRight);
    String regName = "";
    if (coreBackend.NodeIsInput(operation, stage))
      regName = CreateRegNodeName(operation, stage, instr);
    else
      regName = CreateRegNodeName(operation, stage, instr);
    decl = "reg " + size + " " + regName + ";\n";
    return decl;
  }

  /**
   *
   * Generates a string like "input [31:0] nodeName;". Uses a CoreBackend object to determine whether the signal is input/output, size etc.
   * @param operation
   * @param stage
   * @param instr
   * @return
   */
  public String CreateTextInterface(SCAIEVNode operation, PipelineStage stage, String instr) {
    String dataT = coreBackend.IsNodeInStage(operation, stage) ? coreBackend.NodeDataT(operation, stage) : "";
    return CreateTextInterface(operation, stage, instr, dataT);
  }

  /**
   * More general implementation of CreateTextInterface, which does not query dataT from CoreBackend
   * @param operation
   * @param stage
   * @param instr
   * @param dataT
   * @return
   */
  public String CreateTextInterface(SCAIEVNode operation, PipelineStage stage, String instr, String dataT) {
    String interf_lineToBeInserted = "";
    String sig_name = this.CreateNodeName(operation, stage, instr);
    String sig_in = this.dictionary.get(DictWords.out);
    if (operation.isInput)
      sig_in = this.dictionary.get(DictWords.in);
    String size = "";
    if (operation.size > 1)
      size += "[" + operation.size + " -1 : 0]";
    if (!dataT.isEmpty())
      dataT += " ";
    // Add top interface
    interf_lineToBeInserted = sig_in + " " + dataT + size + " " + sig_name + ",// ISAX\n";
    return interf_lineToBeInserted;
  }
  /**
   * Variant of CreateTextInterface with separate parameters for the required SCAIEVNode properties
   * @param operation
   * @param stage
   * @param instr
   * @param input
   * @param signalSize
   * @param dataT
   * @return
   */
  public String CreateTextInterface(String operation, PipelineStage stage, String instr, boolean input, int signalSize, String dataT) {
    SCAIEVNode node = new SCAIEVNode(operation, signalSize, input);
    return CreateTextInterface(node, stage, instr, dataT);
  }

  public TreeMap<Integer, String> OrderISAXOpCode(HashSet<String> lookAtISAX, HashMap<String, SCAIEVInstr> allISAXes) {
    TreeMap<Integer, String> lookAtISAXOrdered = new TreeMap<Integer, String>();
    int withOp = -1;
    int noop = 0;
    for (String ISAX : lookAtISAX)
      if (allISAXes.containsKey(ISAX)) {
        if (allISAXes.get(ISAX).HasNoOp())
          lookAtISAXOrdered.put(noop++, ISAX);
        else
          lookAtISAXOrdered.put(withOp--, ISAX);
      }
    return lookAtISAXOrdered;
  }

  public void UpdateInterface(String top_module, SCAIEVNode operation, String instr, PipelineStage stage, boolean top_interface,
                              boolean instReg) {
    // Update interf bottom file
    logger.debug("Update interface stage = " + stage.getName() + " operation = " + operation);

    // Update interf bottom file
    String assign_lineToBeInserted = "";

    // Add top interface

    String sig_name = this.CreateNodeName(operation, stage, instr);
    String bottom_module = coreBackend.NodeAssignM(operation, stage);
    if (bottom_module == null) {
      logger.error("Cannot find a node declaration for " + operation.name + " in stage " + stage.getName());
      return;
    }
    if (top_module.contentEquals(bottom_module) && !top_interface)
      sig_name = this.CreateLocalNodeName(operation, stage, instr);
    String current_module = bottom_module;
    String prev_module = "";
    while (!prev_module.contentEquals(top_module)) {
      String dataT = coreBackend.NodeDataT(operation, stage);
      String modFile = coreBackend.ModFile(current_module);
      if (modFile.endsWith(".v")) {
        // For Verilog wrappers around SystemVerilog, change the 'logic' type into 'wire'.
        if (dataT.contentEquals("logic"))
          dataT = "wire";
      }
      String interfaceDataT = (prev_module.isEmpty() && dataT.contentEquals("reg")) ? "wire" : dataT;
      if (top_interface ||
          !current_module.contentEquals(
              top_module)) { // top file should just instantiate signal in module instantiation and not generate top interface
        String interfaceText = CreateTextInterface(operation, stage, instr, interfaceDataT);
        this.toFile.UpdateContent(modFile, ");",
                                  new ToWrite(interfaceText, false, true, "module " + current_module + " ", true, current_module));
      } else { //.. else if(current_module.contentEquals(top_module))
        this.toFile.UpdateContent(
            modFile, ");",
            new ToWrite(CreateDeclSig(operation, stage, instr, instReg), false, true, "module " + current_module + " ", current_module));
      }

      if (prev_module.contentEquals("")) {
        // Innermost module for this interface. If requested via NodeAssign, assign the port.
        if (!coreBackend.NodeAssign(operation, stage).contentEquals("")) {
          if (dataT.contentEquals("reg"))
            assign_lineToBeInserted +=
                "always@(posedge  " + clk + ")\n" + tab + sig_name + " <= " + coreBackend.NodeAssign(operation, stage) + ";\n";
          else
            assign_lineToBeInserted += "assign " + sig_name + " = " + coreBackend.NodeAssign(operation, stage) + ";\n";
          this.toFile.UpdateContent(
              modFile, "endmodule",
              new ToWrite(assign_lineToBeInserted, false, true, "module " + current_module + " ", true, current_module));
        } /*
        else {
                assign_lineToBeInserted += this.CreateDeclSig(operation, stage, instr,coreBackend.NodeDataT(operation,
        stage).contains("reg"))+" \n"; this.toFile.UpdateContent(coreBackend.ModFile(current_module),");", new
        ToWrite(assign_lineToBeInserted,true,false,"module "+current_module,current_module));
        }*/
      } else {
        // Add to the inner module instantiation.
        String instance_sig = "";

        if (current_module.contentEquals(top_module) && !top_interface)
          instance_sig = "." + sig_name + " ( " + this.CreateLocalNodeName(operation, stage, instr) + "),\n";
        else
          instance_sig = "." + sig_name + " ( " + sig_name + "),\n";
        this.toFile.UpdateContent(modFile, ");", new ToWrite(instance_sig, true, false, prev_module + " ", true, current_module));
      }
      prev_module = current_module;
      if (!current_module.contentEquals(top_module) && !coreBackend.ModParentName(current_module).equals(""))
        current_module = coreBackend.ModParentName(current_module);
      else
        break;
    }
  }

  // Hack to fix missing as well as trailing module&interface port commas in added lines.
  public void FinalizeInterfaces() {
    class PortConsumer_CommaFixer implements BiConsumer<ToWrite, String> {
      HashMap<String, ArrayList<ToWrite>> ports = new HashMap<>();
      @Override
      public void accept(ToWrite to_write, String grep) {
        // NOTE: Depends on UpdateInterface internals!
        boolean is_moduleport =
            grep.equals(");") && to_write.prereq_val && to_write.prereq_text.startsWith("module ") && to_write.before && !to_write.replace;
        boolean is_instanceport = grep.equals(");") && to_write.prereq && to_write.text.startsWith(".") && to_write.text.endsWith("),\n") &&
                                  to_write.before && !to_write.replace;
        if (is_moduleport || is_instanceport) {
          // For module port: prereq_text is "module <module_name>"
          // For instance port: prereq_text is "<module_name> "
          //-> Can safely store both by prereq_text in same map without collisions.
          ArrayList<ToWrite> port_entry_list = ports.get(to_write.prereq_text);
          if (port_entry_list == null) {
            port_entry_list = new ArrayList<>();
            ports.put(to_write.prereq_text, port_entry_list);
            // Add comma before first added port
            //(Assumption: Module has at least one assigned port in the source file already).
            to_write.text = ", " + to_write.text;
          }
          port_entry_list.add(to_write);
        }
      }
      public void remove_trailing_commas() {
        for (ArrayList<ToWrite> updates : ports.values()) {
          // Assumption: Each port's list has at least one update.
          ToWrite last_update = updates.get(updates.size() - 1);
          int comma_index = last_update.text.lastIndexOf(',');
          if (comma_index == -1 || comma_index == 0) {
            logger.warn("Port update \"" + last_update.text + "\" has no comma at the end");
            continue;
          }
          // Remove comma from last update.
          last_update.text = last_update.text.substring(0, comma_index) + last_update.text.substring(comma_index + 1);
        }
      }
    }
    PortConsumer_CommaFixer consumer = new PortConsumer_CommaFixer();
    toFile.ConsumeUpdates(consumer);
    consumer.remove_trailing_commas();
  }

  public String CreateTextRegReset(String signalName, String signalAssign, String stall) {
    String text = "";
    String stallText = "";
    if (!stall.isEmpty())
      stallText = "if (!(" + stall + "))";
    text += "always@(posedge " + this.clk + ") begin\n" + tab + "if (" + this.reset + ")\n" + tab.repeat(2) + signalName + " <= 0;\n" +
            tab + "else " + stallText + "\n" + tab.repeat(2) + signalName + " <= " + signalAssign + ";\n"
            + "end;\n\n";
    return text;
  }

  public String CreateTextRegReset(String signalName, String signalAssign, String stall, String addrSignal) {
    String text = "";
    String stallText = "";
    if (!stall.isEmpty())
      stallText = "if (!(" + stall + "))";
    text += "always@(posedge " + this.clk + ") begin\n" + tab + "if (" + this.reset + ") begin \n" + tab.repeat(2) +
            "for (int i = 0 ; i< $bits(" + signalName + ")-1; i= i+1 )\n" + tab.repeat(3) + signalName + "[i] <= 0;\n" + tab +
            "end else " + stallText + "\n" + tab.repeat(2) + signalName + "[" + addrSignal + "] <= " + signalAssign + ";\n"
            + "end;\n";
    return text;
  }

  public String CreateText1or0(String new_signal, String condition) {
    String text = "assign " + new_signal + " = (" + condition + ") ? 1'b1 : 1'b0;\n";
    return text;
  }

  public String CreateInAlways(boolean with_clk, String text) {
    if (text.isEmpty())
      return "";
    //always_comb necessary over always@(*) if the body only has constant assigns (i.e., an empty sensitivity list)
    String head = (with_clk) ? "always_ff @(posedge %s)".formatted(clk) : "always_comb";
    String body = head + " begin\n" + AlignText(tab, text) + (text.endsWith("\n") ? "" : "\n") + "end\n";
    return body;
  }

  public String CreateAssign(String assigSig, String toAssign) { return "assign " + assigSig + " = " + toAssign + ";\n"; }
}
