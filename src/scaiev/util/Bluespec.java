package scaiev.util;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.nio.channels.Pipe;
import java.util.HashMap;
import java.util.HashSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.backend.BNode;
import scaiev.backend.CoreBackend;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineStage;

public class Bluespec extends GenerateText {
  // logging
  protected static final Logger logger = LogManager.getLogger();

  FileWriter toFile;
  public String tab = "    ";

  public Bluespec(BNode user_BNode, FileWriter toFile, CoreBackend core) {
    super(user_BNode);
    // initialize dictionary
    dictionary.put(DictWords.module, "module");
    dictionary.put(DictWords.endmodule, "endmodule");
    dictionary.put(DictWords.reg, "Reg");
    dictionary.put(DictWords.wire, "Wire");
    dictionary.put(DictWords.assign, "<=");
    dictionary.put(DictWords.assign_eq, "<=");
    dictionary.put(DictWords.logical_or, "||");
    dictionary.put(DictWords.bitwise_or, "|");
    dictionary.put(DictWords.logical_and, "&&");
    dictionary.put(DictWords.bitwise_and, "&");
    dictionary.put(DictWords.bitsselectRight, "]");
    dictionary.put(DictWords.bitsselectLeft, "[");
    dictionary.put(DictWords.bitsRange, ":");
    dictionary.put(DictWords.ifeq, "==");
    dictionary.put(DictWords.False, "False");
    dictionary.put(DictWords.True, "True");
    dictionary.put(DictWords.ZeroBit, "1'b0");
    dictionary.put(DictWords.OneBit, "1'b1");

    this.toFile = toFile;
    tab = toFile.tab;
    this.coreBackend = core;
  }

  @Override
  public Lang getLang() {
    return Lang.Bluespec;
  }

  /**
   * In bluespec we don't allow variables with upper case ==> put a lower case in front to avoid compilation errs
   */
  @Override
  public String CreateBasicNodeName(SCAIEVNode operation, PipelineStage stage, String instr, boolean familyname) {
    return "v" + super.CreateBasicNodeName(operation, stage, instr, familyname);
  }

  public void UpdateInterface(String top_module, SCAIEVNode operation, String instr, PipelineStage stage, boolean top_interface,
                              boolean assigReg) {
    // Update interf bottom file
    String bottom_module = coreBackend.NodeAssignM(operation, stage);
    if (bottom_module == null) {
      logger.error("Cannot find a node declaration for " + operation.name + " in stage " + stage.getName());
      return;
    }
    String current_module = bottom_module;
    String prev_module = "";
    String instName = "";
    System.out.println("!!!!!!! operation " + operation);
    while (!prev_module.contentEquals(top_module)) {
      // Add interface OR local signal
      if (!current_module.contentEquals(top_module) || top_interface) { // top file should just instantiate signal in module instantiation
                                                                        // and not generate top interface if top_interface = false
        String additional_text = "(*always_enabled*) ";
        if (current_module.contentEquals(top_module))
          additional_text = "(*always_enabled *)";
        this.toFile.UpdateContent(
            coreBackend.ModInterfFile(current_module), "endinterface",
            new ToWrite(additional_text + CreateMethodDecl(operation, stage, instr, false) + ";\n", true, false, "interface", true));
      } else if (current_module.contentEquals(top_module)) {
        if (assigReg)
          this.toFile.UpdateContent(coreBackend.ModFile(current_module), ");",
                                    new ToWrite(CreateDeclReg(operation, stage, instr), true, false, "module " + current_module + " "));
        else
          this.toFile.UpdateContent(coreBackend.ModFile(current_module), ");",
                                    new ToWrite(CreateDeclSig(operation, stage, instr), true, false, "module " + current_module + " "));
      }

      // Find out inst name
      if (!prev_module.isEmpty()) {
        FileInputStream fis;
        try {

          fis = new FileInputStream(coreBackend.getCorePathIn() + "/" + coreBackend.ModFile(current_module));
          BufferedReader in = new BufferedReader(new InputStreamReader(fis));
          String currentLine;
          while ((currentLine = in.readLine()) != null) {
            if (currentLine.contains(prev_module) && currentLine.contains("<-")) {
              int index = currentLine.indexOf(currentLine.trim());
              char first_letter = currentLine.charAt(index);
              String[] words = currentLine.split("" + first_letter, 2);
              String[] words2 = words[1].split("<-", 2);
              String[] words3 = words2[0].split(" ");

              instName = " ";
              int i = 1;
              while (instName.length() <= 1) {
                instName = words3[i];
                i++;
              }
            }
          }
          in.close();
        } catch (IOException e) {
          logger.fatal("Error reading the file");
          e.printStackTrace();
        }
      }
      // Assign OR Forward
      if (prev_module.contentEquals("")) { // no previous files => this is bottom file
        // Connect signals to top interface. Assigns
        String assignText = CreateMethodDecl(operation, stage, instr, false) + ";\n";
        String assignValue = coreBackend.NodeAssign(operation, stage);
        if (assignValue.isEmpty()) {
          assignValue = CreateLocalNodeName(operation, stage, instr);
          // Local signal definition
          this.toFile.UpdateContent(coreBackend.ModFile(current_module), ");",
                                    new ToWrite(CreateDeclSig(operation, stage, instr), true, false, "module " + current_module + " "));
        }
        if (coreBackend.NodeIsInput(operation, stage))
          assignText += tab + assignValue + " " + dictionary.get(DictWords.assign_eq) + " x;\n";
        else
          assignText += tab + "return " + assignValue + ";\n";
        assignText += "endmethod\n";
        // Method definition
        this.toFile.UpdateContent(coreBackend.ModFile(current_module), "endmodule",
                                  new ToWrite(assignText, true, false, "module " + current_module + " ", true));

      } else if (!current_module.contentEquals(top_module) || top_interface) {
        String forwardSig =
            CreateMethodDecl(operation, stage, instr, true) + " = " + instName + ".met_" + CreateNodeName(operation, stage, instr);
        if (coreBackend.NodeIsInput(operation, stage))
          forwardSig += "(x);\n";
        else
          forwardSig += ";\n";
        this.toFile.UpdateContent(coreBackend.ModFile(current_module), "endmodule",
                                  new ToWrite(forwardSig, true, false, "module " + current_module + " ", true));
      }
      prev_module = current_module;
      if (!current_module.contentEquals(top_module) && !coreBackend.ModParentName(current_module).equals(""))
        current_module = coreBackend.ModParentName(current_module);
      else
        break;
    }
  }

  /**
   * Generates text like: Reg #(Bit#(1))  signalName    <- mkReg (0);
   * signalName created from <operation,  stage,  instr>
   */
  public String CreateDeclReg(SCAIEVNode operation, PipelineStage stage, String instr) {
    String decl = "";
    String size = "";
    String init = "0";
    String input = "_o";
    if (coreBackend.NodeIsInput(operation, stage))
      input = "_i";
    if (coreBackend.NodeDataT(operation, stage).contains("Bool"))
      init = "False";
    else
      size += "#(" + operation.size + ")";

    decl = "Reg #(" + coreBackend.NodeDataT(operation, stage) + size + ") " +
           CreateNodeName(operation, stage, instr).replace(input, "_reg") + " <- mkReg(" + init + ");\n";
    return decl;
  }

  /**
   * Generates text like: Wire #(Bit#(1))  signalName    <- mkDWire (0);
   * signalName created from <operation,  stage,  instr>
   */
  public String CreateDeclSig(SCAIEVNode operation, PipelineStage stage, String instr) {
    String decl = "";
    String size = "";
    String init = "0";
    if (coreBackend.NodeDataT(operation, stage).contains("Bool"))
      init = "False";
    else
      size += "#(" + operation.size + ")";

    decl = "Wire #(" + coreBackend.NodeDataT(operation, stage) + size + ") " + CreateLocalNodeName(operation, stage, instr) +
           " <- mkDWire(" + init + ");\n";
    return decl;
  }

  public String CreateMethodName(SCAIEVNode operation, PipelineStage stage, String instr) {
    return "met_" + CreateNodeName(operation, stage, instr);
  }

  public String CreateMethodDecl(SCAIEVNode operation, PipelineStage stage, String instr, boolean shortForm) {
    String methodName = "method ";
    String size = "";
    if (coreBackend.NodeDataT(operation, stage).contains("Bit") || coreBackend.NodeDataT(operation, stage).contains("Int"))
      size += "#(" + operation.size + ")";
    if (coreBackend.NodeIsInput(operation, stage))
      methodName += "Action ";
    else
      methodName += "(" + coreBackend.NodeDataT(operation, stage) + size + ") ";
    methodName += "met_" + CreateNodeName(operation, stage, instr);

    if (coreBackend.NodeIsInput(operation, stage)) {
      if (shortForm)
        methodName += "(x)";
      else
        methodName += "(" + coreBackend.NodeDataT(operation, stage) + size + " x)";
    }
    return methodName;
  }

  public String CreateAllNoMemEncoding(HashSet<String> lookAtISAX, HashMap<String, SCAIEVInstr> allISAXes, String rdInstr) {
    String body = "";
    for (String ISAX : lookAtISAX) {
      if (!allISAXes.get(ISAX).GetSchedNodes().containsKey(BNode.RdMem) && !allISAXes.get(ISAX).GetSchedNodes().containsKey(BNode.WrMem) &&
          !ISAX.contains(SCAIEVInstr.noEncodingInstr)) {
        if (!body.isEmpty())
          body += " " + dictionary.get(DictWords.logical_or) + " ";
        body += CreateValidEncodingOneInstr(ISAX, allISAXes, rdInstr);
      }
    }
    return body;
  }

  public String CreatePutInRule(String ruleBody, SCAIEVNode operation, PipelineStage stage, String instr) {
    String text = "rule rule_" + CreateLocalNodeName(operation, stage, instr) + ";\n" + tab + this.AlignText(tab, ruleBody) + "\nendrule\n";
    return text;
  }
}
