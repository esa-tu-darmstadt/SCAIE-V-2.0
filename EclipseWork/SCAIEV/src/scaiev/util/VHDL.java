package scaiev.util;

import java.util.HashMap;
import java.util.HashSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.backend.CoreBackend;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineStage;

public class VHDL extends GenerateText {
	// logging
	protected static final Logger logger = LogManager.getLogger();

	FileWriter toFile;
	public String tab = "    ";
	
	public VHDL(BNode user_BNode, FileWriter toFile, CoreBackend core) {
		super(user_BNode);
		// initialize dictionary 
		dictionary.put(DictWords.module,"architecture");
		dictionary.put(DictWords.endmodule,"end architecture");
		dictionary.put(DictWords.reg,"signal");
		dictionary.put(DictWords.wire,"signal");		
		dictionary.put(DictWords.assign,"");
		dictionary.put(DictWords.assign_eq,"<=");
		dictionary.put(DictWords.logical_or,"or");
		dictionary.put(DictWords.bitwise_or,"or");
		dictionary.put(DictWords.logical_and,"and");
		dictionary.put(DictWords.bitwise_and,"and");
		dictionary.put(DictWords.bitsselectRight,")");
		dictionary.put(DictWords.bitsselectLeft,"(");
		dictionary.put(DictWords.ifeq,"=");
		dictionary.put(DictWords.bitsRange,"downto");
		dictionary.put(DictWords.in,"in");
		dictionary.put(DictWords.out,"out");
		dictionary.put(DictWords.False,"'0'");
		dictionary.put(DictWords.True,"'1'");
		dictionary.put(DictWords.ZeroBit,"'0'");
		dictionary.put(DictWords.OneBit,"'1'");
		this.toFile = toFile;
		tab = toFile.tab;
		this.coreBackend = core;
	}
	
	@Override 
	public Lang getLang () {
		return Lang.VHDL;		
	}
	public void  UpdateInterface(String top_module,SCAIEVNode operation, String instr, PipelineStage stage, boolean top_interface, boolean special_case) {
		// Update interf bottom file
		logger.debug("Update interface stage = "+stage.getName()+" operation = "+operation);
		HashMap<String, HashMap<String,ToWrite>> update_orca = new HashMap<String, HashMap<String,ToWrite>>();
		
		// Update interf bottom file
		String assign_lineToBeInserted = "";
		
		// Add top interface	

		String sig_name = this.CreateNodeName(operation, stage, instr);
		String bottom_module = coreBackend.NodeAssignM(operation, stage);
		if (bottom_module == null) {
			logger.error("Cannot find a node declaration for " + operation.name + " in stage " + stage.getName());
			return;
		}
		if(top_module.contentEquals(bottom_module) && !top_interface)
			sig_name = this.CreateLocalNodeName(operation, stage, instr);
		String current_module = bottom_module;
		String prev_module = "";
		while(!prev_module.contentEquals(top_module)) {
			HashMap<String,ToWrite> insert = new HashMap<String,ToWrite>();
			if(!current_module.contentEquals(top_module) || top_interface) {  // top file should just instantiate signal in module instantiation and not generate top interface
				logger.debug("INTEGRATE. DEBUG. Inserting in components.vhd with prereq "+ current_module);
				this.toFile.UpdateContent(coreBackend.ModInterfFile(current_module),"port (", new ToWrite(CreateTextInterface(operation,stage,instr,special_case),true,false," "+current_module+" "));
				this.toFile.UpdateContent(coreBackend.ModFile(current_module),"port (", new ToWrite(CreateTextInterface(operation,stage,instr,special_case),true,false," "+current_module+" "));		
			} else if(current_module.contentEquals(top_module)) {
				this.toFile.UpdateContent(coreBackend.ModFile(current_module),"architecture rtl",new ToWrite(CreateDeclSig(operation, stage, instr),true,false," "+current_module+" "));
			}
			
			if(prev_module.contentEquals("")) {
				// Connect signals to top interface
				if(!coreBackend.NodeAssign(operation, stage).contentEquals("")) {
					assign_lineToBeInserted += sig_name+" <= "+coreBackend.NodeAssign(operation, stage)+" ;\n";
					this.toFile.UpdateContent(coreBackend.ModFile(current_module),"begin", new ToWrite(assign_lineToBeInserted,false,true,""));	
				}
				/*
				else {
					assign_lineToBeInserted += this.CreateDeclSig(operation, stage, instr)+" \n";
					this.toFile.UpdateContent(coreBackend.ModFile(current_module),"architecture rtl", new ToWrite(assign_lineToBeInserted,false,true,"",false));	
				}*/
			} else {
				String instance_sig = sig_name+" => "+sig_name+",\n";
				if(current_module.contentEquals(top_module) && !top_interface)
					instance_sig = sig_name+" => "+this.CreateLocalNodeName(operation, stage, instr)+",\n";
				this.toFile.UpdateContent(coreBackend.ModFile(current_module),"port map (", new ToWrite(instance_sig,true,false,": "+prev_module));
			}
			prev_module = current_module;
			if(!current_module.contentEquals(top_module) && !coreBackend.ModParentName(current_module).equals(""))
				current_module = coreBackend.ModParentName(current_module);	
			else 
				break;				
		}
		
	}

	
	/**
	 * Generates text like : signal signalName_s  :  std_logic_vector(1 downto 0);
	 * signalName created from <operation,  stage,  instr>
	 */
	public String CreateDeclSig(SCAIEVNode operation, PipelineStage stage, String instr) {
		String decl = "";
		String size = "";
		if(coreBackend.NodeSize(operation,stage) != 1 ) 
			size += "("+operation.size+" -1 downto 0)";
		
		decl = "signal "+CreateLocalNodeName(operation,stage,instr)+"  :  " + coreBackend.NodeDataT(operation, stage) +size+";\n";
		return decl;	
	}
	
	/**
	 * Generates text like : signal signalName_reg  :  std_logic_vector(1 downto 0);
	 * signalName created from <operation,  stage,  instr>
	 */
	public String CreateDeclReg(SCAIEVNode operation, PipelineStage stage, String instr) {
		String decl = "";
		String size = "";
		if(coreBackend.NodeSize(operation,stage) > 1  ) 
			size += "("+operation.size+" -1 downto 0)";
		String regName = CreateNodeName(operation,stage,instr);
		if(coreBackend.NodeIsInput(operation, stage))
			regName = regName.replace("_i", "_reg");
		else 
			regName = regName.replace("_o", "_reg");
		decl = "signal "+regName+"  :  " + coreBackend.NodeDataT(operation, stage) +size+";\n";
		return decl;	
	}
	



	
	public String CreateTextInterface(SCAIEVNode operation, PipelineStage stage, String instr, boolean special_case) {
		String interf_lineToBeInserted = "";
		String sig_name = this.CreateNodeName(operation, stage, instr);
		String sig_in = this.dictionary.get(DictWords.out);
		String sig_type = coreBackend.NodeDataT(operation, stage);   
		if(coreBackend.NodeIsInput(operation, stage))
			sig_in = this.dictionary.get(DictWords.in);
		String size = "";
		if(operation.size>1 ) 
			size += "("+operation.size+" -1 downto 0)";
		// Add top interface	
		interf_lineToBeInserted = sig_name+" : "+sig_in+" "+sig_type+" "+size+";-- ISAX\n";
		if(special_case)
			interf_lineToBeInserted = sig_name+" : "+"buffer"+" "+size+";-- ISAX\n";
		return interf_lineToBeInserted;
	}
	
	
	
	
	public String CreateTextISAXorOrig(String if_clause, String new_signal, String ISAX_signal, String orig_signal) {
		String text ="";
		text += "process(all) begin \n"
				+ tab+"if( "+if_clause+" ) then\n"
				+ tab.repeat(2)+new_signal+" <= "+ISAX_signal+"; \n"
				+ tab+"else \n"
				+ tab.repeat(2)+new_signal+" <= "+orig_signal+";\n"
				+ tab+"end if;\n"
				+"end process;\n\n";
		return text;
	}

	
	public String CreateTextRegResetStall(String signal_name, String signal_assign, String stall) {
		String text ="";
		text += "process (clk, reset) \n"
				+ "begin\n"
				+ tab+"if reset = '1' then\n"
				+ tab.repeat(2)+signal_name+" <= '0';\n"
				+ tab+"elsif clk'EVENT and clk = '1' and "+stall+" = '0' then\n"
				+ tab.repeat(2)+signal_name+" <= "+signal_assign+";\n"
				+ tab+"end if;\n"
				+ "end process;\n\n";
		return text;
	}
	
	public String CreateText1or0(String new_signal, String condition) {
		String text =  new_signal + " <= '1' when ("+condition+") else '0';\n";
		return text;
	}
	
	public String CreateInProc(boolean clk, String text) {
		int i = 1;
		String sensitivity = "all";
		String clockEdge = "";
		String endclockEdge = "";
		if(clk) {
			sensitivity = "clk";
			clockEdge = tab+"if rising_edge(clk) then\n";
			i++;
			endclockEdge = tab+"end if;\n";
		}
		String body ="process("+sensitivity+") begin -- ISAX Logic\n "+clockEdge+AlignText(tab.repeat(i),text)+"\n"+endclockEdge+"end process;\n" ;
		return body;		
	}
	
	

	

	
}
