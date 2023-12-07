package scaiev.util;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.util.HashMap;
import java.util.HashSet;

import scaiev.backend.BNode;
import scaiev.backend.CoreBackend;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;

public class Bluespec extends GenerateText {
	FileWriter toFile;
	public String tab = "    ";

	public Bluespec(FileWriter toFile, CoreBackend core) {
		// initialize dictionary 
		dictionary.put(DictWords.module,"module");
		dictionary.put(DictWords.endmodule,"endmodule");
		dictionary.put(DictWords.reg,"Reg");
		dictionary.put(DictWords.wire,"Wire");		
		dictionary.put(DictWords.assign,"<=");
		dictionary.put(DictWords.assign_eq,"<=");
		dictionary.put(DictWords.logical_or,"||");
		dictionary.put(DictWords.bitwise_or,"|");
		dictionary.put(DictWords.logical_and,"&&");
		dictionary.put(DictWords.bitwise_and,"&");
		dictionary.put(DictWords.bitsselectRight,"]");
		dictionary.put(DictWords.bitsselectLeft,"[");
		dictionary.put(DictWords.bitsRange,":");
		dictionary.put(DictWords.ifeq,"==");
		dictionary.put(DictWords.False,"False");
		dictionary.put(DictWords.True,"True");
		dictionary.put(DictWords.ZeroBit,"1'b0");
		dictionary.put(DictWords.OneBit,"1'b1");
		
		this.toFile = toFile;
		tab = toFile.tab;
		this.coreBackend = core;
	}
	
	@Override 
	public Lang getLang () {
		return Lang.Bluespec;		
	}
	
	/** 
	 * In bluespec we don't allow variables with upper case ==> put a lower case in front to avoid compilation errs
	 */	 
	@Override public String CreateBasicNodeName(SCAIEVNode operation, int stage, String instr, boolean familyname) {
		if(!instr.isEmpty())
			instr = "_"+instr;	
		String opName = operation.name;
		if(!operation.nameQousinNode.isEmpty() && operation.isAdj() && familyname)
			opName = operation.replaceRadixNameWith(operation.familyName);
		 return "v"+opName+instr+"_"+stage;
	}
	
	public void  UpdateInterface(String top_module,SCAIEVNode operation, String instr, int stage, boolean top_interface, boolean assigReg) {
		// Update interf bottom file
		String bottom_module = coreBackend.NodeAssignM(operation, stage);
		String current_module = bottom_module;
		String prev_module = "";
		String instName = "";
		while(!prev_module.contentEquals(top_module)) {
			// Add interface OR local signal
			if(!current_module.contentEquals(top_module) || top_interface) {  // top file should just instantiate signal in module instantiation and not generate top interface if top_interface = false
				String additional_text  = "(*always_enabled*) " ;
				if(current_module.contentEquals(top_module))
					additional_text = "(*always_enabled *)";
				this.toFile.UpdateContent(coreBackend.ModInterfFile(current_module), "endinterface",new ToWrite(additional_text+CreateMethodName(operation,stage,instr,false)+";\n",true,false,"interface",true));		
			} else if(current_module.contentEquals(top_module)) {
				if(assigReg)
					this.toFile.UpdateContent(coreBackend.ModFile(current_module), ");",new ToWrite(CreateDeclReg(operation, stage, instr),true,false, "module "+current_module+" "));	
				else
					this.toFile.UpdateContent(coreBackend.ModFile(current_module),");",new ToWrite(CreateDeclSig(operation, stage, instr),true,false, "module "+current_module+" "));	
			}
			
			// Find out inst name 
			if(!prev_module.isEmpty()) {
				FileInputStream fis;
				try {
					
					fis = new FileInputStream(coreBackend.getCorePathIn() +"/"+  coreBackend.ModFile(current_module));
					BufferedReader in = new BufferedReader(new InputStreamReader(fis));
					String currentLine;						
					while ((currentLine = in.readLine()) != null) {
						if(currentLine.contains(prev_module) && currentLine.contains("<-")) {
							int index = currentLine.indexOf(currentLine.trim());
							char first_letter = currentLine.charAt(index);
							String[]  words = currentLine.split("" + first_letter,2);
							String[]  words2 = words[1].split("<-",2);
							String[] words3 = words2[0].split(" ");
							
							instName = " ";
							int  i = 1;
							while(instName.length()<=1){
								instName = words3[i];
								i++;
						    }
						}
					}
					in.close();
				}	catch (IOException e) {
					System.out.println("ERROR. Error reading the file");
					e.printStackTrace();
				}	
			}
			// Assign OR Forward
			if(prev_module.contentEquals("")) { // no previous files => this is bottom file
				// Connect signals to top interface. Assigns
				String assignText = CreateMethodName(operation,stage,instr,false)+";\n";
				String assignValue = coreBackend.NodeAssign(operation, stage); 
				if(assignValue.isEmpty()) {
					assignValue = CreateLocalNodeName(operation,stage, instr);
					// Local signal definition 
					this.toFile.UpdateContent(coreBackend.ModFile(current_module), ");",new ToWrite(CreateDeclSig(operation, stage, instr),true,false,"module "+current_module+" "));
					
				}
				if(coreBackend.NodeIn(operation, stage))
					assignText += tab+assignValue +" "+ dictionary.get(DictWords.assign_eq) + " x;\n";
				else 
					assignText += tab+"return "+assignValue +";\n";
				assignText += "endmethod\n";
				// Method definition
				this.toFile.UpdateContent(coreBackend.ModFile(current_module), "endmodule",new ToWrite(assignText,true,false,"module "+current_module+" ",true));
				
			} else if(!current_module.contentEquals(top_module) || top_interface){
				String forwardSig = CreateMethodName(operation,stage,instr,true)+" = "+instName + ".met_"+CreateNodeName(operation, stage,instr);
				if(coreBackend.NodeIn(operation, stage))
					forwardSig += "(x);\n";
				else 
					forwardSig += ";\n";
				this.toFile.UpdateContent(coreBackend.ModFile(current_module), "endmodule",new ToWrite(forwardSig,true,false,"module "+current_module+" ",true));				 
						
			}
			prev_module = current_module;
			if(!current_module.contentEquals(top_module) && !coreBackend.ModParentName(current_module).equals(""))
				current_module = coreBackend.ModParentName(current_module);	
			else 
				break;
		}
		
	}
	
	/**
	 * Generates text like: Reg #(Bit#(1))  signalName    <- mkReg (0);
	 * signalName created from <operation,  stage,  instr>
	 */
	public String CreateDeclReg(SCAIEVNode operation, int stage, String instr) {
		String decl = "";
		String size = "";
		String init = "0";
		String input = "_o";
		if(coreBackend.NodeIn(operation, stage))
			input = "_i";
		if(coreBackend.NodeDataT(operation, stage).contains("Bool") ) 
			init = "False";
		else  
			size += "#("+operation.size+")";
		
		decl = "Reg #("+coreBackend.NodeDataT(operation, stage) + size+") "+CreateNodeName(operation,stage,instr).replace(input,"_reg")+" <- mkReg("+init+");\n";
		return decl;
	}
	
	/**
	 * Generates text like: Wire #(Bit#(1))  signalName    <- mkDWire (0);
	 * signalName created from <operation,  stage,  instr>
	 */
	public String CreateDeclSig(SCAIEVNode operation, int stage, String instr) {
		String decl = "";
		String size = "";
		String init = "0";
		if(coreBackend.NodeDataT(operation, stage).contains("Bool") ) 
			init = "False";
		else  
			size += "#("+operation.size+")";
		
		decl = "Wire #("+coreBackend.NodeDataT(operation, stage) + size+") "+CreateLocalNodeName(operation,stage,instr)+" <- mkDWire("+init+");\n";
		return decl;	
	}


	public String CreateMethodName(SCAIEVNode operation, int stage, String instr, boolean shortForm) {	
		String methodName = "method ";
		String size  ="";
		if(coreBackend.NodeDataT(operation, stage).contains("Bit") || coreBackend.NodeDataT(operation, stage).contains("Int"))
			size += "#("+operation.size+")";
		if(coreBackend.NodeIn(operation, stage))
			methodName += "Action ";
		else 
			methodName += "("+coreBackend.NodeDataT(operation, stage)+size+") ";
		methodName += "met_"+CreateNodeName(operation, stage,instr);
		
		if(coreBackend.NodeIn(operation, stage)) {
			if(shortForm) 
				methodName += "(x)";
			else 
				methodName += "("+coreBackend.NodeDataT(operation, stage)+size+" x)";
		}
		return methodName;
	}
	
	// TODO to be moved in piccolo, as it is piccolo specific
	public String CreateMemStageFrwrd(boolean bypass, boolean flush,boolean bypass_val) {
		String rdVal = "";
		String no = "no_";
		String isTrue = "False";
		String ostatus = "OSTATUS_PIPE";
		String comment = "//";
		String rdValue = "";
		
		if(bypass) {
			rdVal = "bypass.bypass_state = BYPASS_RD;";
			isTrue = "True";
			no = "";
			comment = "";
		}
		if(bypass_val) {
			rdVal = "bypass.bypass_state = BYPASS_RD_RDVAL;\n"
					+ "let result = "+this.CreateLocalNodeName(BNode.WrRD, 1, "")+";"
					+ "bypass.rd_val = result;";
			isTrue = "True";
			no = "";
			comment = "";
			rdValue = "data_to_stage3.rd_val = result;";

		}
		if(flush) {
			ostatus = "OSTATUS_EMPTY";
			no = "no_";
			isTrue = "False";
			comment = "//";
		}
		String body = "let data_to_stage3 = data_to_stage3_base; \n"
				+ "data_to_stage3.rd_valid = "+isTrue+"; \n"
				+ " \n"
				+ comment+"let bypass = bypass_base; \n"
				+ rdVal +"\n"
				+ rdValue+"\n"
				+ "output_stage2 = Output_Stage2 {ostatus         : OSTATUS_PIPE, \n"
				+ "    trap_info       : ?, \n"
				+ "    data_to_stage3  : data_to_stage3, \n"
				+ "    bypass          : "+no+"bypass \n"
				+ "`ifdef ISA_F \n"
				+ "		, fbypass       : "+no+"fbypass \n"
				+ "`endif \n"
				+ "		}; \n"
				+ "end";
		return body;
	}
	

	
	public String CreateAllNoMemEncoding(HashSet<String> lookAtISAX, HashMap <String,SCAIEVInstr>  allISAXes, String rdInstr) {
		String body = "";
		for (String ISAX : lookAtISAX) {
			if(!allISAXes.get(ISAX).GetSchedNodes().containsKey(BNode.RdMem) && !allISAXes.get(ISAX).GetSchedNodes().containsKey(BNode.WrMem) && !ISAX.contains(SCAIEVInstr.noEncodingInstr)) {
				if(!body.isEmpty())
					body += " "+dictionary.get(DictWords.logical_or)+" ";
				body += this.Decode(ISAX, rdInstr, allISAXes);
			}
		}
		return body;		
	}
	

	
	public String CreatePutInRule(	String ruleBody, SCAIEVNode operation,int stage, String instr) {
		String text ="rule rule_"+CreateLocalNodeName(operation, stage, instr)+";\n"+tab+this.AllignText(tab, ruleBody)+"\nendrule\n" ;
		return text;		
	}
	
	
	
	
	

	

	
}
