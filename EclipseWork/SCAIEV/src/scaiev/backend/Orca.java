package scaiev.backend;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Optional;

import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.util.FileWriter;
import scaiev.util.Lang;
import scaiev.util.ToWrite;
import scaiev.util.VHDL;

class Parse {
	public static String declare = "architecture"; 
	public static String behav  = "begin";
	//public String behav = "end architecture";
}
public class Orca extends CoreBackend {
	public String tab = "    ";
	HashSet<String> AddedIValid = new HashSet<String>();
	
	public  String 			pathCore = "CoresSrc/orca";
	public String getCorePathIn() {
		return pathCore;
	}
	public  String 			pathORCA = "ip/orca/hdl";
	private Core 			orca_core;
	private HashMap <String,SCAIEVInstr>  ISAXes;
	private HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr;
	private FileWriter 		toFile = new FileWriter(pathCore);
	private String 			extension_name;
	private VHDL            language = new VHDL(toFile,this);
	private String 			topModule = "orca";
	private int lastJumpStage = 0;
	private int nrTabs = 0;
	private SCAIEVNode ISAX_FWD_ALU = new SCAIEVNode("ISAX_FWD_ALU", 1,false);
	SCAIEVNode ISAX_REG_reg = new SCAIEVNode("ISAX_REG",1,false);
	SCAIEVNode is_ISAX = new SCAIEVNode("is_ISAX",1,true);
	SCAIEVNode ISAX_frwrd_to_stage1_ready = new SCAIEVNode("ISAX_frwrd_to_stage1_ready",1,false);
	SCAIEVNode nodePCCorrValid = new SCAIEVNode("isax_tostage1_pc_correction", 1, true);
	
	// Infos for SCAL
	public HashMap<SCAIEVNode, Integer> PrepareEarliest() {
		HashMap<SCAIEVNode, Integer> node_stageValid = new HashMap<SCAIEVNode, Integer>();
		node_stageValid.put(BNode.WrRD, 3);
		return node_stageValid;		
	};
	
	public boolean Generate (HashMap <String,SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr, String extension_name, Core core, String out_path) { // core needed for verification purposes
		// Set variables
		this.orca_core = core;
		this.ISAXes = ISAXes;
		this.op_stage_instr = op_stage_instr;
		this.extension_name = extension_name;
		ConfigOrca();
		language.clk = "clk";
		language.reset = "reset";
		
		
		 // Only for orca, make instr in stage 2 visible, if not used logic will be redundant but also removed as it has no driver 
		 if(!(op_stage_instr.containsKey(BNode.RdInstr) && op_stage_instr.get(BNode.RdInstr).containsKey(2)))
			 language.UpdateInterface(topModule,BNode.RdInstr, "",2,false,false);	
		IntegrateISAX_IOs(topModule);
		IntegrateISAX_NoIllegalInstr();
		IntegrateISAX_WrStall();
		IntegrateISAX_WrFlush();
		IntegrateISAX_RdStall();
		IntegrateISAX_WrRD();
		IntegrateISAX_Mem();
		IntegrateISAX_WrPC();
		toFile.WriteFiles(language.GetDictModule(),language.GetDictEndModule(), out_path);
		
		return true;
	}
	
	
	private void IntegrateISAX_IOs(String topModule) {
		language.GenerateAllInterfaces(topModule,op_stage_instr,ISAXes,  orca_core ,null);			
	
	
	}
	
	private void IntegrateISAX_RdStall() {
		if(op_stage_instr.containsKey(BNode.RdStall)) {
			for(int stage : op_stage_instr.get(BNode.RdStall).keySet()) {
				if(stage==3) {
					String RdStallText = this.language.CreateNodeName(BNode.RdStall, stage, "") + " <= not((not to_execute_valid) or (from_writeback_ready and\n"
							+ "                                                   (((not lsu_select) or from_lsu_ready) and\n"
							+ "                                                    ((not alu_select) or from_alu_ready) and\n"
							+ "                                                    ((not syscall_select) or from_syscall_ready) and\n"
							+ "                                                    ((not vcp_select) or vcp_ready)))) or (not  (from_writeback_ready));";
					toFile.UpdateContent(this.ModFile(NodeAssignM(BNode.RdStall, 3)), "to_vcp_valid     <=",new ToWrite(RdStallText,false,true,""));
					
				}
				if(stage==2) {
					language.UpdateInterface(NodeAssignM(BNode.RdStall, stage), ISAX_frwrd_to_stage1_ready, "", stage, false, false); 
				}
			}
		}
	}
	

	private void IntegrateISAX_WrFlush() {
		if(op_stage_instr.containsKey(BNode.WrFlush))
			for(int stage : op_stage_instr.get(BNode.WrFlush).keySet()) {
				toFile.UpdateContent(this.ModFile(NodeAssignM(BNode.WrFlush, stage)), Parse.declare, new ToWrite(language.CreateDeclSig(BNode.WrFlush, stage, ""),false,true,""));
				if(stage ==1)
					toFile.ReplaceContent(this.ModFile(NodeAssignM(BNode.WrFlush, stage)),"to_decode_valid                    => ", new ToWrite("to_decode_valid                    => to_decode_valid or "+language.CreateLocalNodeName(BNode.WrFlush, stage,"")+",", false, true , "") );
				if(stage ==2 && !(this.ContainsOpInStage(BNode.WrPC_valid, 3) || this.ContainsOpInStage(BNode.WrPC_valid, 4))) // TODO, to simulate this
					toFile.ReplaceContent(this.ModFile(NodeAssignM(BNode.WrFlush, stage)),"quash_decode       => ", new ToWrite("quash_decode       => to_pc_correction_valid or "+language.CreateLocalNodeName(BNode.WrFlush, stage,"")+",", false, true , "") );
				if(stage ==3)
					toFile.ReplaceContent(this.ModFile(NodeAssignM(BNode.WrFlush, stage))," to_execute_valid            => to_execute_valid,", new ToWrite(" to_execute_valid            => to_execute_valid or "+language.CreateLocalNodeName(BNode.WrFlush, stage,"")+",", false, true , "") );
			}
		
		
	}
	private void IntegrateISAX_NoIllegalInstr() {
		String isISAXSignal = language.CreateLocalNodeName(is_ISAX, 3, "");
		String textIllegal = "if ("+isISAXSignal+" = '1') then\n"
				+ tab+ "from_opcode_illegal <= '0';\n"
				+ "else\n"
				+ tab+"from_opcode_illegal <= '1';\n"
				+ "end if;";
		toFile.ReplaceContent(this.ModFile("execute"), "from_opcode_illegal <= '1';",new ToWrite(textIllegal,true,false,"when others =>"));
		String aluSelect = "if ("+isISAXSignal+" = '0') then  --  otherwise all ISAXes will be considered ALU and will write to regfile \n"
				+ tab + "alu_select <= '1';\n"
				+ "else\n"
				+ tab + "alu_select <= '0';\n"
				+ "end if;";
		toFile.ReplaceContent(this.ModFile("execute"), "alu_select <= '1';",new ToWrite(aluSelect,true,false,"when others =>"));
		
		language.UpdateInterface("execute", is_ISAX, "", 3, false, false);
		
		toFile.UpdateContent(this.ModFile("execute"),Parse.declare,  new ToWrite("signal "+isISAXSignal+": std_logic;\n",false,true,""));
		
		String mem = "";
		int memStage =  this.orca_core.GetNodes().get(BNode.WrMem).GetEarliest();
		if(this.ContainsOpInStage(BNode.WrMem, memStage))
			mem += ","+language.CreateNodeName(BNode.WrMem_validReq, memStage, "");
		if(this.ContainsOpInStage(BNode.RdMem, memStage))
			mem += "," + language.CreateNodeName(BNode.RdMem_validReq, memStage, "");
		
		toFile.ReplaceContent(this.ModFile("execute"),"process (opcode) is",new ToWrite("process (opcode,"+isISAXSignal+mem+") is",false,true,""));
		
		HashSet<String> allISAXes =  new HashSet<String>();
		allISAXes.addAll(ISAXes.keySet());
		String addText = language.CreateText1or0(isISAXSignal,language.CreateAllEncoding(allISAXes, ISAXes, "to_execute_instruction"));
		toFile.UpdateContent(this.ModFile("execute"),Parse.behav,  new ToWrite(addText,false,true,""));
		
		// is_ISAX register in stage 4. Declare & behavior. Needded only for RdFlush 4
		if(this.ContainsOpInStage(BNode.RdFlush, 4)) {
			toFile.UpdateContent(this.ModFile("execute"),Parse.declare,  new ToWrite("signal "+language.CreateRegNodeName(is_ISAX, 4, "")+" : std_logic;\n",false,true,""));		
			addText = language.CreateTextRegResetStall(language.CreateRegNodeName(is_ISAX, 4, ""), isISAXSignal,"(not from_execute_ready)" );
			toFile.UpdateContent(this.ModFile("execute"),Parse.behav,  new ToWrite(addText,false,true,""));
		}
	}
	
	
	
	
		
	private void IntegrateISAX_WrStall() {
		HashMap <Integer, String> readySigs = new HashMap <Integer, String>();
		readySigs.put(1, "decode_to_ifetch_ready");
		readySigs.put(2, "execute_to_decode_ready");
		HashMap <Integer, String> readyReplace = new HashMap <Integer, String>();
		readyReplace.put(1, "to_ifetch_ready             =>");
		readyReplace.put(2, "to_decode_ready              =>");
				
		
		
		if(this.ContainsOpInStage(BNode.WrStall, 0)){
			String addStallClause = "";
			if(this.ContainsOpInStage(BNode.WrStall, 0) )
			    addStallClause += "not ("+language.CreateNodeName(BNode.WrStall, 0 ,"")+") and ";
			toFile.UpdateContent(this.ModFile("orca_core"),Parse.behav,    new ToWrite("ISAX_to_1_ready <= "+addStallClause+" "+readySigs.get(1)+";",false,true,""));
			toFile.UpdateContent(this.ModFile("orca_core"),Parse.declare,  new ToWrite("signal ISAX_to_1_ready: std_logic;\n",false,true,""));
			toFile.ReplaceContent(this.ModFile("orca_core"), readyReplace.get(1),new ToWrite(readyReplace.get(1)+"ISAX_to_1_ready,\n",false,true,""));
				
			String addtext = " to_ifetch_pause_ifetch <= "+language.CreateNodeName(BNode.WrStall, 0, "")+" or ((not (from_decode_incomplete_instruction and ifetch_idle)) and from_execute_pause_ifetch);\n";
			toFile.ReplaceContent(this.ModFile("orca_core"), "to_ifetch_pause_ifetch <=",new ToWrite(addtext,false,true,"", true));
		}
		
		if(this.ContainsOpInStage(BNode.WrStall, 1)) {
			String addtext = "from_decode_ready <= (not "+language.CreateNodeName(BNode.WrStall, 1, "")+") and (to_stage1_ready or (not from_stage1_valid) );";
			toFile.ReplaceContent(this.ModFile("decode"), "from_decode_ready <=", new ToWrite(addtext,false,true,"", true));
		}
		
		if(this.ContainsOpInStage(BNode.WrStall, 2)) {
			String addStallClause = "";
			if(this.ContainsOpInStage(BNode.WrStall, 2) )
			    addStallClause += "not ("+language.CreateNodeName(BNode.WrStall, 2 ,"")+") and ";
			toFile.UpdateContent(this.ModFile("orca_core"),Parse.behav,    new ToWrite("ISAX_to_2_ready <= "+addStallClause+" "+readySigs.get(2)+";",false,true,""));
			toFile.UpdateContent(this.ModFile("orca_core"),Parse.declare,  new ToWrite("signal ISAX_to_2_ready: std_logic;\n",false,true,""));
			toFile.ReplaceContent(this.ModFile("orca_core"), readyReplace.get(2),new ToWrite(readyReplace.get(2)+"ISAX_to_2_ready\n",false,true,""));
		}
		
		if(this.ContainsOpInStage(BNode.WrStall, 3)) {
			toFile.ReplaceContent(this.ModFile(NodeAssignM(BNode.WrStall, 3)), "((not vcp_select) or vcp_ready)))",new ToWrite("((not vcp_select) or vcp_ready))));\n",false,true,"", true));
			toFile.ReplaceContent(this.ModFile(NodeAssignM(BNode.WrStall, 3)), "from_execute_ready <=",new ToWrite(" from_execute_ready <= (not WrStall_3_i) and ((not to_execute_valid) or (from_writeback_ready and",false,true,"", true));
			
		}
		
		if(this.ContainsOpInStage(BNode.WrStall, 4)) { // mainly for spawn  {
			toFile.ReplaceContent(this.ModFile(NodeAssignM(BNode.WrStall, 4)), "from_writeback_ready <= ", new ToWrite("from_writeback_ready <= (not use_after_produce_stall) and (not writeback_stall_from_lsu) and  (not "+language.CreateNodeName(BNode.WrStall, 4, "")+");\n", false,true,"", true));
			toFile.ReplaceContent(this.ModFile(NodeAssignM(BNode.WrStall, 4)), "to_rf_valid <= to_rf_select_writeable", new ToWrite("to_rf_valid <= (not "+language.CreateNodeName(BNode.WrStall, 4, "")+") and to_rf_select_writeable and (from_syscall_valid or", false,true,"", true));
		}
		
	}
	private void IntegrateISAX_WrRD(){
		if(this.ContainsOpInStage(BNode.WrRD, 4) | this.ContainsOpInStage(BNode.WrRD, 3) | this.ContainsOpInStage(BNode.WrRD_spawn, 5) ) {
			String ISAX_execute_to_rf_data_s = "ISAX_execute_to_rf_data_s";
			String ISAX_execute_to_rf_valid_s = "ISAX_execute_to_rf_valid_s";	
			String decl_lineToBeInserted = "";
			
			// Declarations 
			decl_lineToBeInserted += "signal "+ISAX_execute_to_rf_valid_s+" : std_logic;\n";
			decl_lineToBeInserted += "signal "+ISAX_execute_to_rf_data_s+" : std_logic_vector(REGISTER_SIZE-1 downto 0);\n";
			toFile.UpdateContent(this.ModFile("orca_core"),Parse.declare, new ToWrite(decl_lineToBeInserted,false,true,""));
					
			// Send to decode correct result
			toFile.ReplaceContent(this.ModFile("orca_core"),"to_rf_data   =>", new ToWrite("to_rf_data => "+ISAX_execute_to_rf_data_s+",",true,false,"D : decode"));
			toFile.ReplaceContent(this.ModFile("orca_core"),"to_rf_valid  =>", new ToWrite("to_rf_valid => "+ISAX_execute_to_rf_valid_s+",",true,false,"D : decode"));
			
			// Compute result
			String wrrdDataBody = "";
			if(this.ContainsOpInStage(BNode.WrRD_spawn, 5)) {
				wrrdDataBody +=  "if( "+language.CreateNodeName(BNode.WrRD_spawn_valid, 5, "")+" = '1' ) then\n"
						+ tab+ISAX_execute_to_rf_data_s+" <= "+language.CreateNodeName(BNode.WrRD_spawn, 5, "")+"; \n"
						+ "    els";
			}
			if(this.ContainsOpInStage(BNode.WrRD, 3)) {
				wrrdDataBody +=  "if( "+language.CreateRegNodeName(BNode.WrRD_valid, 3, "")+" = '1' and  "+language.CreateRegNodeName(BNode.WrRD_validData, 3, "")+" = '1' ) then\n"
						+ tab+ISAX_execute_to_rf_data_s +" <= "+language.CreateRegNodeName(BNode.WrRD, 3, "")+"; \n"
						+ "    els";			
			}
			
			if(this.ContainsOpInStage(BNode.WrRD, 4)) {
				//language.UpdateInterface("orca",BNode.WrRD_validData, "",4,true,false);
				wrrdDataBody +=  "if( "+language.CreateNodeName(BNode.WrRD_validData, 4, "")+" = '1' and "+language.CreateRegNodeName(BNode.WrRD_validData, 4, "")+" = '0') then\n"
						+ tab+ISAX_execute_to_rf_data_s+" <= "+language.CreateNodeName(BNode.WrRD, 4, "")+"; \n"
						+ "    els";
			}
			wrrdDataBody += "e\n"+ tab+ISAX_execute_to_rf_data_s+" <= execute_to_rf_data;\nend if;\n";		
			toFile.UpdateContent(this.ModFile("orca_core"),Parse.behav, new ToWrite(language.CreateInProc(false, wrrdDataBody),false,true,""));
			
			
			// Compute valid 
			String wrrdValid = ""; 
			if(this.ContainsOpInStage(BNode.WrRD_spawn, 5)) {
				wrrdValid +=  "if( "+language.CreateNodeName(BNode.WrRD_spawn_valid, 5, "")+" = '1'  and "+language.CreateNodeName(BNode.WrRD_spawn_addr, 5, "")+" /= \"00000\" ) then\n"
						+ tab+ISAX_execute_to_rf_valid_s+" <= '1';\n"
						+ "    els";
			}
			if(this.ContainsOpInStage(BNode.WrRD, 4)) {
				wrrdValid +=  "if( "+language.CreateNodeName(BNode.WrRD_valid, 4, "")+" = '1' and (execute_to_rf_select /=\"00000\") ) then\n"
						+ tab+ISAX_execute_to_rf_valid_s+" <=  '1';\n"
						+ "    els";
			}
			wrrdValid =  this.language.OpIfNEmpty(wrrdValid, "e\n") + tab+ISAX_execute_to_rf_valid_s+" <= execute_to_rf_valid;\n " +this.language.OpIfNEmpty(wrrdValid,"end if;\n");		
			toFile.UpdateContent(this.ModFile("orca_core"),Parse.behav, new ToWrite(language.CreateInProc(false, wrrdValid),false,true,""));
			
			
			
			// Compute Adddress 
			if(this.ContainsOpInStage(BNode.WrRD_spawn, 5)) {
				String addr = "if( "+language.CreateNodeName(BNode.WrRD_spawn_valid, 5, "")+" = '1' ) then\n"
						+ "		        ISAX_execute_to_rf_select <= "+language.CreateNodeName(BNode.WrRD_spawn_addr, 5, "")+";\n"
						+ "		     else \n"
						+ "		        ISAX_execute_to_rf_select <= execute_to_rf_select;\n"
						+ "		    end if;"; 
				toFile.UpdateContent(this.ModFile("orca_core"),Parse.behav, new ToWrite(language.CreateInProc(false, addr),false,true,"")); // add logic
				toFile.UpdateContent(this.ModFile("orca_core"),Parse.declare, new ToWrite("signal ISAX_execute_to_rf_select: std_logic_vector(5-1 downto 0);\n",false,true,"")); // declare new sig
				toFile.ReplaceContent(this.ModFile("orca_core"),"to_rf_select =>", new ToWrite(" to_rf_select => ISAX_execute_to_rf_select,",true,false,"D : decode"));
				
			}
			
			// Compute ValidData DH Signal 
			// If we have writes both in stage 3 and 4, we need to know which data to take in 4, for this we need if _validData[4] && !_validData_reg[3] --> reg for validData[3]
			decl_lineToBeInserted += this.language.CreateDeclReg(BNode.WrRD_validData, 4, ""); 
			if(this.ContainsOpInStage(BNode.WrRD, 3)) { 
				//language.UpdateInterface("orca",BNode.WrRD_validData, "",3,true,false);
				if(this.ContainsOpInStage(BNode.WrRD, 4))
					toFile.UpdateContent(this.ModFile("orca_core"),Parse.behav, new ToWrite(language.CreateTextRegResetStall(language.CreateRegNodeName(BNode.WrRD_validData, 4, ""), language.CreateNodeName(BNode.WrRD_validData, 3, ""), "from_execute_ready"),false,true,"")); // add logic			
			} else 
				toFile.UpdateContent(this.ModFile("orca_core"),Parse.behav, new ToWrite(language.CreateRegNodeName(BNode.WrRD_validData, 4, "") + " <= '0';\n",false,true,"")); // add default logic			
			

			// Datahaz. ISAX to standard 
			if(this.ContainsOpInStage(BNode.WrRD, 4)) {
				String replace = "";
				String newText = "";
				String grep = "";
				replace = "from_alu_data  => from_alu_data,";
				newText = "from_alu_data  => ISAX_from_alu_data,";
				toFile.ReplaceContent(this.ModFile("execute"),replace,new ToWrite(newText,false,true,""));
				
				newText = "signal ISAX_from_alu_data : std_logic_vector(REGISTER_SIZE-1 downto 0);"; 
				toFile.UpdateContent(this.ModFile("execute"),Parse.declare,new ToWrite(newText,false,true,""));
				
				newText = "from_alu_data <= "+language.CreateNodeName(BNode.WrRD, 4, "")+" when "+language.CreateNodeName(BNode.WrRD_validData, 4, "")+" = '1' else ISAX_from_alu_data;";
				toFile.UpdateContent(this.ModFile("execute"),Parse.behav, new ToWrite(newText,false,true,""));
				//newText = language.CreateDeclReg(ISAX_REG_reg, 4, "");
				//toFile.UpdateContent(this.ModFile("orca_core"),Parse.declare, new ToWrite(newText,false,true,""));
				
				
				
				newText = language.CreateNodeName(ISAX_FWD_ALU, 3, "")+" <= "+ language.CreateNodeName(BNode.WrRD_valid, 3, "")+";\n";
				toFile.UpdateContent(this.ModFile("execute"),Parse.behav, new ToWrite(newText,false,true,""));
				
				replace = "if to_alu_valid = '1' and from_alu_ready = '1'";
				newText = "if ( to_alu_valid = '1' and from_alu_ready = '1') or ("+ language.CreateNodeName(ISAX_FWD_ALU, 3, "")+" = '1') then";
				toFile.ReplaceContent(this.ModFile("execute"),replace, new ToWrite(newText,false,true,""));				
				language.UpdateInterface("orca_core",ISAX_FWD_ALU, "",3,false,false);
				
				// Make sure a forward path is canceled if user signal says so
				replace = "rs1_data <= from_alu_data when rs1_mux = ALU_FWD";
				newText = 	replace + " and ( "+language.CreateNodeName(BNode.WrRD_validData, 4, "")+"  = '0' or  "+language.CreateNodeName(BNode.WrRD_valid, 4, "")+" = '1' ) else";
				toFile.ReplaceContent(this.ModFile("execute"),replace, new ToWrite(newText,false,true,""));		
				replace = "rs2_data <= from_alu_data when rs2_mux = ALU_FWD";
				newText = 	replace + " and ( "+language.CreateNodeName(BNode.WrRD_validData, 4, "")+" = '0'  or "+language.CreateNodeName(BNode.WrRD_valid, 4, "")+" = '1'  ) else";
				toFile.ReplaceContent(this.ModFile("execute"),replace, new ToWrite(newText,false,true,""));		
				replace = "rs3_data <= from_alu_data when rs3_mux = ALU_FWD";
				newText = 	replace + " and ( "+language.CreateNodeName(BNode.WrRD_validData, 4, "")+"  = '0' or "+language.CreateNodeName(BNode.WrRD_valid, 4, "")+"  = '1' ) else";
				toFile.ReplaceContent(this.ModFile("execute"),replace, new ToWrite(newText,false,true,""));						
			} 
			if(this.ContainsOpInStage(BNode.WrRD, 3)) {
				toFile.ReplaceContent(this.ModFile("execute"),"from_branch_valid or", new ToWrite("from_branch_valid or "+language.CreateNodeName(BNode.WrRD_validData, 3, "")+"or\n",true,false,"to_rf_valid  <="));
			}
		}
	}	

	
	
	private void IntegrateISAX_Mem() {
		int stage = this.orca_core.GetNodes().get(BNode.RdMem).GetLatest(); // stage for Mem trasnfers
		boolean readRequired = op_stage_instr.containsKey(BNode.RdMem) && op_stage_instr.get(BNode.RdMem).containsKey(stage);
		boolean writeRequired = op_stage_instr.containsKey(BNode.WrMem) && op_stage_instr.get(BNode.WrMem).containsKey(stage);
		// If reads or writes to mem are required...
		if( readRequired || writeRequired)  {
			// Add in files logic for Memory reads
			// Generate text to select load store unit like :
			//		if(if((to_execute_instruction(6 downto 0) = "1011011" and to_execute_instruction(14 downto 12) = "010")) then -- ISAX load 
	        //			lsu_select <= '1';
	        //		end if;
			String textToAdd = "";
			if(readRequired) {
				textToAdd =  "if("+language.CreateNodeName(BNode.RdMem_validReq, stage, "")+" = '1') then -- ISAX load \n" // CreateValidEncoding generates text to decode instructions in op_stage_instr.get(BNode.RdMem).get(stage)
								+ tab+"lsu_select <= '1';\n"
								+"end if;\n";
				toFile.UpdateContent(this.ModFile("load_store_unit"),Parse.declare,new ToWrite("signal read_s :std_logic; -- ISAX, signaling a read", false, true,""));
				toFile.UpdateContent(this.ModFile("load_store_unit"),Parse.behav,new ToWrite(language.CreateText1or0("read_s", language.CreateNodeName(BNode.RdMem_validReq,stage,"")+" = '1' or ((opcode(5) = LOAD_OP(5)) and ("+language.CreateNodeName(is_ISAX, 3, "")+" = '0'))"), false,true,""));
				toFile.ReplaceContent(this.ModFile("load_store_unit"),"process (opcode, func3) is", new ToWrite("process (opcode, func3, read_s) is", false,true,""));
				toFile.ReplaceContent(this.ModFile("load_store_unit"),"if opcode(5) = LOAD_OP(5) then", new ToWrite("if read_s = '1' then", false,true,""));
				toFile.ReplaceContent(this.ModFile("load_store_unit"),"oimm_readnotwrite <= '1' when opcode(5) = LOAD_OP(5)",new ToWrite("oimm_readnotwrite <= '1' when (read_s  = '1') else '0';",false,true,""));
			}
			if(writeRequired) { 
				textToAdd += "if("+language.CreateNodeName(BNode.WrMem_validReq,stage,"")+" = '1') then -- ISAX store \n"
								+ tab+"lsu_select <= '1';\n"
								+"end if;\n";
				toFile.UpdateContent(this.ModFile("execute"),Parse.declare, new ToWrite("signal ISAX_rs2_data : std_logic_vector(31 downto 0) ;",false,true,""));
				toFile.UpdateContent(this.ModFile("execute"),Parse.behav,new ToWrite("ISAX_rs2_data <= "+language.CreateNodeName(BNode.WrMem, stage, "")+" when ("+language.CreateNodeName(BNode.WrMem_validReq, stage , "")+" = '1') else rs2_data;",false,true,"")); 
				toFile.ReplaceContent(this.ModFile("execute"),"rs2_data       => rs2_data,",new ToWrite(" rs2_data       => ISAX_rs2_data, -- ISAX",true,false,"ls_unit : load_store_unit"));
				
			}
			// Add text in file of module "execute" before line "end process;". Don't add it unless you already saw line "lsu_select <= '1';"  = requries prerequisite. 
			toFile.UpdateContent(this.ModFile("execute"),"end process;", new ToWrite(textToAdd,true,false,"lsu_select <= '1';",true)); //ToWrite (text to add, requries prereq?, start value for prereq =false if prereq required, prepreq text, text has to be added BEFORE grepped line?)		
		
			// Address logic 
			String isaxAddr = "";
			if(writeRequired)
				isaxAddr += language.CreateNodeName(BNode.WrMem_addr, stage, "")+" when ("+language.CreateNodeName(BNode.WrMem_addr_valid, stage, "")+" = '1') ";
			if(readRequired)
				isaxAddr  = language.OpIfNEmpty(isaxAddr, " else ") + language.CreateNodeName(BNode.RdMem_addr, stage, "")+" when ("+language.CreateNodeName(BNode.RdMem_addr_valid, stage, "")+" = '1') ";
			toFile.ReplaceContent(this.ModFile("load_store_unit"),"address_unaligned <= std_logic_vector", new ToWrite("address_unaligned <= "+isaxAddr+" else std_logic_vector(unsigned(sign_extension(REGISTER_SIZE-12-1 downto 0) & imm)+unsigned(base_address));-- Added ISAX support ", false, true, ""));
			toFile.ReplaceContent(this.ModFile("load_store_unit"),"imm)+unsigned(base_address));", new ToWrite(" ",false,true,""));

		}
		
		if(this.op_stage_instr.containsKey(BNode.WrMem_spawn) ||this.op_stage_instr.containsKey(BNode.RdMem_spawn) ) {
			// RdAddr signal required by SCAL 
			String rdAddrLogic = language.CreateLocalNodeName(BNode.RdMem_spawn_rdAddr,  this.orca_core.maxStage+1, "") + " <= oimm_address;\n";
			this.toFile.UpdateContent(this.ModFile("load_store_unit"),"oimm_address <= address_unaligned(REGISTER_SIZE-1 downto 0);", new ToWrite(rdAddrLogic,false,true,"")); //TODO Test
			
			HashSet<String> allMemSpawn = new HashSet<String>();
			int spawnStage = this.orca_core.maxStage+1;
			allMemSpawn.addAll(this.op_stage_instr.getOrDefault(BNode.WrMem_spawn, new HashMap<>()).getOrDefault(spawnStage, new HashSet<>()));
			allMemSpawn.addAll(this.op_stage_instr.getOrDefault(BNode.RdMem_spawn, new HashMap<>()).getOrDefault(spawnStage, new HashSet<>()));
				
			toFile.ReplaceContent(this.ModFile("orca_core"),"lsu_oimm_address       => lsu_oimm_address,", new ToWrite("lsu_oimm_address       => isax_lsu_oimm_address,",false,true,""));
			toFile.ReplaceContent(this.ModFile("orca_core"),"lsu_oimm_byteenable    => lsu_oimm_byteenable,", new ToWrite("lsu_oimm_byteenable    => isax_lsu_oimm_byteenable,",false,true,""));
			toFile.ReplaceContent(this.ModFile("orca_core"),"lsu_oimm_requestvalid  => lsu_oimm_requestvalid,", new ToWrite("lsu_oimm_requestvalid  => isax_lsu_oimm_requestvalid,",false,true,""));
			toFile.ReplaceContent(this.ModFile("orca_core"),"lsu_oimm_readnotwrite  => lsu_oimm_readnotwrite,", new ToWrite("lsu_oimm_readnotwrite  => isax_lsu_oimm_readnotwrite,",false,true,""));
			toFile.ReplaceContent(this.ModFile("orca_core"),"lsu_oimm_writedata     => lsu_oimm_writedata,", new ToWrite("lsu_oimm_writedata     => isax_lsu_oimm_writedata,",false,true,""));
			String declare =  "signal isax_lsu_oimm_address : std_logic_vector(31 downto 0);\n"
					+ "signal isax_lsu_oimm_byteenable : std_logic_vector(3 downto 0);\n"
					+ "signal isax_lsu_oimm_requestvalid : std_logic;\n"
					+ "signal isax_lsu_oimm_readnotwrite : std_logic;\n"
					+ "signal isax_spawn_lsu_oimm_writedata : std_logic_vector(31 downto 0);\n"
					+ "signal isax_lsu_oimm_writedata  : std_logic_vector(31 downto 0);\n"
					+ "signal isax_spawnRdReq : std_logic;\n"
					+ "signal isax_RdStarted : std_logic;";
			toFile.UpdateContent(this.ModFile("orca_core"),Parse.declare, new ToWrite( declare,false, true, ""));
			String checkOngoingTransf = "";
			if(this.op_stage_instr.containsKey(BNode.RdMem_spawn)) {
				checkOngoingTransf = "process(clk) begin \n"
		    		+ tab.repeat(1)+"if rising_edge(clk) then \n"
		    		+ tab.repeat(2)+"if(lsu_oimm_requestvalid = '1') then\n"
		    		+ tab.repeat(3)+"isax_RdStarted <= lsu_oimm_readnotwrite;\n"
		    		+ tab.repeat(2)+"elsif(lsu_oimm_readdatavalid = '1') then\n"
		    		+ tab.repeat(3)+"isax_RdStarted <='0';\n"
		    		+ tab.repeat(2)+"end if; \n"
		    		+ tab.repeat(2)+"if reset = '1' then \n"
		    		+ tab.repeat(3)+"isax_RdStarted <= '0'; \n"
		    		+ tab.repeat(2)+"end if; \n"
		    		+ tab.repeat(1)+"end if;\n"
		    		+ "end process;\n";
				checkOngoingTransf += "isax_spawnRdReq <= "+ language.CreateNodeName(BNode.WrMem_spawn_validReq, spawnStage, "")+" and (not "+language.CreateNodeName(BNode.WrMem_spawn_write, spawnStage, "")+");\n";
			} else checkOngoingTransf = "isax_spawnRdReq <= 0;\n"
		    		+"isax_RdStarted <= 0;\n";
			String setSignals = "lsu_oimm_address      <= "+language.CreateNodeName(BNode.WrMem_spawn_addr, spawnStage, "")+" when ("+language.CreateNodeName(BNode.WrMem_spawn_validReq, spawnStage, "")+" = '1')  else isax_lsu_oimm_address;\n"
					+ "lsu_oimm_byteenable    <= \"1111\" when ("+language.CreateNodeName(BNode.WrMem_spawn_validReq, spawnStage, "")+" = '1') else isax_lsu_oimm_byteenable;\n"
					+ "lsu_oimm_requestvalid  <= (not lsu_oimm_waitrequest and not isax_RdStarted) when ("+language.CreateNodeName(BNode.WrMem_spawn_validReq, spawnStage, "")+" = '1') else isax_lsu_oimm_requestvalid; -- todo possible comb path in slave between valid and wait\n"
					+ "lsu_oimm_readnotwrite  <= not "+language.CreateNodeName(BNode.WrMem_spawn_write, spawnStage, "")+" when ("+language.CreateNodeName(BNode.WrMem_spawn_validReq, spawnStage, "")+" = '1') else isax_lsu_oimm_readnotwrite;\n"
					+ "lsu_oimm_writedata     <= "+language.CreateNodeName(BNode.WrMem_spawn, spawnStage, "")+" when ("+language.CreateNodeName(BNode.WrMem_spawn_validReq, spawnStage, "")+" = '1') else isax_lsu_oimm_writedata;\n";
			toFile.UpdateContent(this.ModFile("orca_core"),Parse.behav, new ToWrite( setSignals+checkOngoingTransf,false, true, ""));
			
			// Compute Mem Spawn Done 
			String memdone = "(((lsu_oimm_waitrequest = '0') and (lsu_oimm_readnotwrite = '0')) or (lsu_oimm_readnotwrite = '1' and lsu_oimm_readdatavalid = '1'))";
			toFile.UpdateContent(this.ModFile("orca_core"),Parse.behav, new ToWrite( language.CreateText1or0(language.CreateNodeName(BNode.WrMem_spawn_validResp, spawnStage, ""), memdone),false, true, "")); 
			
			
		}
	}

	private void IntegrateISAX_WrPC() {
		String assign_lineToBeInserted = "";
		String decl_lineToBeInserted = "";
		String sub_top_file = this.ModFile("orca_core");
		HashMap<Integer,String> array_PC_clause = new HashMap<Integer,String> ();
		int max_stage = this.orca_core.maxStage;
		for(int stage=0; stage<=max_stage; stage++) 
			if(this.ContainsOpInStage(BNode.WrPC, stage)) 			
				array_PC_clause.put(stage, language.CreateNodeName(BNode.WrPC_valid, stage, ""));
		
		if(!array_PC_clause.isEmpty() || op_stage_instr.containsKey(BNode.WrPC_spawn)) {
			decl_lineToBeInserted += "signal ISAX_to_pc_correction_data_s : unsigned(REGISTER_SIZE-1 downto 0);\n";
			decl_lineToBeInserted += "signal ISAX_to_pc_correction_valid_s : std_logic;\n";
			
			String PC_text = "ISAX_to_pc_correction_data_s <=X\"00000000\";\n"; 
			String PC_clause = ""; 
			String PC_clause_stage0 = " ( "+language.CreateNodeName(BNode.WrPC_valid, 0, "")+" = '1') ";
			for(int stage =0; stage <=orca_core.GetNodes().get(BNode.WrPC).GetLatest()+1; stage++) {
				
				if(array_PC_clause.containsKey(stage) || (stage == (orca_core.GetNodes().get(BNode.WrPC).GetLatest()+1))) {
					if(this.ContainsOpInStage(BNode.WrPC, max_stage+1) && stage==0) { // not supported anymore!!!
						System.out.println("CRITICAL WARNING. Spawn PC not supported anymore");
						PC_text += "if("+language.CreateNodeName(BNode.WrPC_spawn_valid, max_stage+1,"")+" = '1') then\n"+tab.repeat(2)+"ISAX_to_pc_correction_data_s <= "+language.CreateNodeName(BNode.WrPC_spawn, max_stage+1,"")+";\n";
						PC_clause += "( "+language.CreateNodeName(BNode.WrPC_spawn_valid, max_stage+1,"")+" = '1')";
					}
					if(array_PC_clause.containsKey(stage)) {
						PC_text +="if ("+array_PC_clause.get(stage)+" = '1') then\n"+tab.repeat(2)+"ISAX_to_pc_correction_data_s <= "+language.CreateNodeName(BNode.WrPC, stage, "")+";\nend if;\n";  
						if(PC_clause!="")
							PC_clause += " or ";
						PC_clause += "( "+ array_PC_clause.get(stage)+" = '1')";
					}
				}	
				if((stage == orca_core.GetNodes().get(BNode.WrPC).GetLatest()))  
					PC_text += "if( to_pc_correction_valid = '1') then"+tab.repeat(2)+"ISAX_to_pc_correction_data_s <= to_pc_correction_data;\nend if;\n";
				if(array_PC_clause.containsKey(stage) && stage != 0)
					PC_clause_stage0 += "and ("+language.CreateNodeName(BNode.WrPC_valid, stage, "")+" = '0')";
			}
			if(array_PC_clause.containsKey(0)) {
				String addDecl = "signal isax_noquashing_readdata:  std_logic;\n"
						+ "signal isax_rememberpc : unsigned(REGISTER_SIZE-1 downto 0);\n ";
				toFile.UpdateContent(this.ModFile("instruction_fetch"),Parse.declare, new ToWrite(addDecl,false,true,""));
					
				language.UpdateInterface("orca_core", nodePCCorrValid, "", 1, false, false);
				String addText  = "process(all) begin \n"
						+ "    if( "+PC_clause_stage0+" ) then\n"
						+ "        "+language.CreateLocalNodeName(nodePCCorrValid, 1, "")+" <= '1'; \n"
						+ "    else \n"
						+ "        "+language.CreateLocalNodeName(nodePCCorrValid, 1, "")+" <='0';\n"
						+ "    end if;\n"
						+ "end process;\n";
				toFile.UpdateContent(this.ModFile("orca_core"),Parse.behav, new ToWrite(addText,false, true, "")); 
				
				addText = "if(isax_tostage1_pc_correction_1_i = '1') then\n"
						+ "      isax_rememberpc <= from_ifetch_program_counter;\n"
						+ "\n"
						+ "      isax_noquashing_readdata <= '1';\n"
						+ "      elsif(from_ifetch_valid) then\n"
						+ "      isax_noquashing_readdata <= '0' ;\n"
						+ "      end if;\n";
				String grep = "if to_pc_correction_valid = '1' and from_pc_correction_ready = '1' then"; 
				toFile.UpdateContent(this.ModFile("instruction_fetch"),grep, new ToWrite(addText,false, true, "",true)); 
				
				addText = "ifetch_valid           <= (not instruction_fifo_empty) or (oimm_readdatavalid and (not quashing_readdata or isax_noquashing_readdata));\n";
				grep = "ifetch_valid           <=";
				toFile.ReplaceContent(this.ModFile("instruction_fetch"), grep, new ToWrite(addText,false, true, ""));
							
				addText = "pc_fifo_read               <= ifetch_valid and to_ifetch_ready and (not isax_noquashing_readdata);\n"; 
				grep = "pc_fifo_read               <=";
				toFile.ReplaceContent(this.ModFile("instruction_fetch"), grep, new ToWrite(addText,false, true, ""));
				
				addText = "from_ifetch_program_counter <= isax_rememberpc when (isax_noquashing_readdata = '1' ) else unsigned(pc_fifo_readdata(REGISTER_SIZE-1 downto 0));\n";
				grep = "from_ifetch_program_counter <=";
				toFile.ReplaceContent(this.ModFile("instruction_fetch"), grep, new ToWrite(addText,false, true, ""));				
			}
			
			assign_lineToBeInserted += language.CreateInProc(false, PC_text);
			assign_lineToBeInserted +=  language.CreateTextISAXorOrig(PC_clause, "ISAX_to_pc_correction_valid_s","'1'","to_pc_correction_valid");
			toFile.ReplaceContent(sub_top_file, "to_pc_correction_data        => to_pc_correction_data,", new ToWrite("to_pc_correction_data        => ISAX_to_pc_correction_data_s,",true, false, "I : instruction_fetch"));
			toFile.ReplaceContent(sub_top_file, "to_pc_correction_valid       =>", new ToWrite("to_pc_correction_valid        => ISAX_to_pc_correction_valid_s,",true, false, "I : instruction_fetch"));
			if(this.ContainsOpInStage(BNode.WrFlush, 2))
				toFile.ReplaceContent(sub_top_file, "quash_decode =>", new ToWrite("quash_decode        => to_pc_correction_valid or "+language.CreateNodeName(BNode.WrFlush, 2,"")+",",true, false, "D : decode"));
			
				
		}
		//insert.put("port (", new ToWrite(interf_lineToBeInserted,false,true,""));
		toFile.UpdateContent(sub_top_file,"architecture rtl of orca_core is", new ToWrite(decl_lineToBeInserted,false,true,""));
		toFile.UpdateContent(sub_top_file,"begin", new ToWrite(assign_lineToBeInserted,false,true,""));
		
		
	}
	private boolean ContainsOpInStage(SCAIEVNode operation, int stage) {
		return op_stage_instr.containsKey(operation) && op_stage_instr.get(operation).containsKey(stage);
	}
	private void ConfigOrca() {
	 	this.PopulateNodesMap(this.orca_core.maxStage);
	 	
		PutModule(pathORCA+"/components.vhd","instruction_fetch"	, pathORCA+"/instruction_fetch.vhd", "orca_core","instruction_fetch");
		PutModule(pathORCA+"/components.vhd","decode"				, pathORCA+"/decode.vhd",			   "orca_core","decode");
		PutModule(pathORCA+"/components.vhd","execute"				, pathORCA+"/execute.vhd", 		   "orca_core","execute");
		PutModule(pathORCA+"/components.vhd","arithmetic_unit"		, pathORCA+"/alu.vhd",			   "execute",  "arithmetic_unit ");
		PutModule(pathORCA+"/components.vhd","branch_unit"			, pathORCA+"/branch_unit.vhd",	   "execute",  "branch_unit");
		PutModule(pathORCA+"/components.vhd","load_store_unit"		, pathORCA+"/load_store_unit.vhd",   "execute",  "load_store_unit");
		PutModule(pathORCA+"/components.vhd","orca_core"			, pathORCA+"/orca_core.vhd",		   "orca",     "orca_core");
		PutModule(pathORCA+"/components.vhd","orca"				, pathORCA+"/orca.vhd", 			   "",		   "orca");
	 	Module newModule = new Module();
	 	newModule.name = "extension_name";

	 	
	 	int spawnStage = this.orca_core.maxStage+1;
	 	for(int i =0; i<spawnStage;i++) {
	 		this.PutNode( "unsigned", "", "orca_core", BNode.WrPC,i); // was to_pc_correction_data
	 		this.PutNode("std_logic", "", "orca_core", BNode.WrPC_valid,i); // was to_pc_correction_valid
	 	}
	 	this.PutNode( "unsigned", "", "orca_core", BNode.WrPC_spawn,5);
 		this.PutNode( "std_logic", "", "orca_core", BNode.WrPC_spawn_valid,5);
	 	this.PutNode( "unsigned", "program_counter", "orca_core", BNode.RdPC,0);
	 	this.PutNode( "unsigned", "ifetch_to_decode_program_counter", "orca_core", BNode.RdPC,1);
	 	this.PutNode( "unsigned", "from_stage1_program_counter", "decode", BNode.RdPC,2);
	 	this.PutNode( "unsigned", "decode_to_execute_program_counter", "orca_core", BNode.RdPC,3);
 		
	 	this.PutNode( "std_logic_vector", "ifetch_to_decode_instruction", "orca_core", BNode.RdInstr,1);
	 	this.PutNode( "std_logic_vector", "from_stage1_instruction", "decode", BNode.RdInstr,2);
	 	this.PutNode( "std_logic_vector", "decode_to_execute_instruction", "orca_core", BNode.RdInstr,3);

 		
	 	this.PutNode( "std_logic_vector", "rs1_data", "decode", BNode.RdRS1,2);
	 	this.PutNode( "std_logic_vector", "rs1_data", "execute", BNode.RdRS1,3); 		 		

	 	this.PutNode( "std_logic_vector", "rs2_data", "decode", BNode.RdRS2,2);
	 	this.PutNode( "std_logic_vector", "rs2_data", "execute", BNode.RdRS2,3);	
	 	
	 	this.PutNode( "std_logic_vector", "", "execute", BNode.WrRD,3); 
	 	this.PutNode( "std_logic_vector", "", "execute", BNode.WrRD,4); 
	 	this.PutNode( "std_logic", "", "execute", BNode.WrRD_valid,3); 
	 	this.PutNode( "std_logic", "", "execute", BNode.WrRD_validData,3); 
	 	//this.PutNode( "std_logic_vector", "", "orca_core", BNode.WrRD_addr,4);
	 	this.PutNode( "std_logic", "", "execute", BNode.WrRD_valid,4);
	 	this.PutNode( "std_logic", "", "execute", BNode.WrRD_validData,4); 
	 	
	 	
	 	//this.PutNode( "std_logic", "", "orca_core", BNode.RdIValid,1);	 		
	 	//this.PutNode( "std_logic", "", "decode", BNode.RdIValid,2);	 		
	 	//this.PutNode( "std_logic", "", "orca_core", BNode.RdIValid,3);	 		
	 	//this.PutNode( "std_logic", "", "orca_core", BNode.RdIValid,4);
	 	
		int stageMem = this.orca_core.GetNodes().get(BNode.RdMem).GetLatest();
	 	this.PutNode( "std_logic_vector", "from_lsu_data", "load_store_unit", BNode.RdMem,stageMem);
	 	//this.PutNode( "std_logic", "from_lsu_valid", "load_store_unit", BNode.RdMem_validResp,stageMem);
	 	this.PutNode( "std_logic_vector", "", "load_store_unit", BNode.WrMem,stageMem);
	 	this.PutNode( "std_logic", "", "load_store_unit", BNode.RdMem_validReq,stageMem);
	 	this.PutNode( "std_logic_vector", "", "load_store_unit", BNode.RdMem_addr,stageMem);
	 	this.PutNode( "std_logic", "", "load_store_unit", BNode.WrMem_validReq,stageMem);
	 	this.PutNode( "std_logic_vector", "", "load_store_unit", BNode.WrMem_addr,stageMem);
	 	this.PutNode( "std_logic", "", "load_store_unit", BNode.WrMem_addr_valid,stageMem);
	 	this.PutNode( "std_logic", "", "load_store_unit", BNode.RdMem_addr_valid,stageMem);
	 	
	 
	 	this.PutNode( "std_logic", "not (pc_fifo_write) ", "instruction_fetch", BNode.RdStall,0);
	 	this.PutNode( "std_logic", "not (decode_to_ifetch_ready) ", "orca_core", BNode.RdStall,1);
	 	this.PutNode( "std_logic", "not ("+language.CreateLocalNodeName(ISAX_frwrd_to_stage1_ready, 2, "")+")", "orca_core", BNode.RdStall,2);
	 	this.PutNode( "std_logic", "", "execute", BNode.RdStall,3);	  	
	 	this.PutNode( "std_logic", "not (from_writeback_ready)", "execute", BNode.RdStall,4);
	 	
	 	this.PutNode( "std_logic", "", "instruction_fetch", BNode.WrStall,0);
	 	this.PutNode( "std_logic", "", "decode", BNode.WrStall,1);
	 	this.PutNode( "std_logic", "", "orca_core", BNode.WrStall,2);
	 	this.PutNode( "std_logic", "", "execute", BNode.WrStall,3);
	 	this.PutNode( "std_logic", "", "execute", BNode.WrStall,4);
	 	
	 	this.PutNode("std_logic", "to_pc_correction_valid ", "orca_core", BNode.RdFlush,0);
	 	this.PutNode("std_logic", "not (to_decode_valid)  or (not to_decode_valid) ", "orca_core", BNode.RdFlush,1);
	 	this.PutNode("std_logic", "not (from_stage1_valid)", "decode", BNode.RdFlush,2);// TODO to_decode_valid
	 	this.PutNode("std_logic", "to_pc_correction_valid  or (not to_execute_valid) ", "orca_core", BNode.RdFlush,3);
	 	this.PutNode("std_logic", " not (from_syscall_valid or from_lsu_valid or from_branch_valid or from_alu_valid or "+language.CreateRegNodeName(is_ISAX, 4, "")+") ", "execute", BNode.RdFlush,4); // is_ISAX for internal reg
	 	
	 	
	 	this.PutNode("std_logic", "" , "orca_core", BNode.WrFlush,0);
	 	this.PutNode("std_logic", "" , "decode", BNode.WrFlush,1);
	 	this.PutNode("std_logic", "" , "decode",    BNode.WrFlush,2);
	 	this.PutNode("std_logic", "" , "orca_core", BNode.WrFlush,3);
	 	this.PutNode("std_logic", "" , "execute", BNode.WrFlush,4);
	 	

	 	this.PutNode( "std_logic_vector", "" , "orca_core", BNode.WrRD_spawn,spawnStage);
	 	this.PutNode( "std_logic"       , "'1'", "orca_core", BNode.WrRD_spawn_validResp,spawnStage);
	 	this.PutNode( "std_logic"       , "" , "orca_core", BNode.WrRD_spawn_valid,spawnStage);
	 	this.PutNode( "std_logic_vector", "" , "orca_core", BNode.WrRD_spawn_addr,spawnStage);

   	 	this.PutNode( "std_logic_vector", "lsu_oimm_readdata", "orca_core", BNode.RdMem_spawn,spawnStage);
	 	this.PutNode( "std_logic"       ,"" ,"orca_core", BNode.RdMem_spawn_validResp,spawnStage);
	 	this.PutNode( "std_logic_vector", "","orca_core", BNode.WrMem_spawn,spawnStage);
	 	this.PutNode( "std_logic"       ,"" ,"orca_core", BNode.WrMem_spawn_validResp,spawnStage); // ? ((lsu_oimm_waitrequest = '0')  or (lsu_oimm_readdatavalid = '1'))
	 	this.PutNode( "std_logic"       , "","orca_core", BNode.RdMem_spawn_validReq,spawnStage);
	 	this.PutNode( "std_logic_vector", "","orca_core", BNode.RdMem_spawn_addr,spawnStage);
	 	this.PutNode( "std_logic", "","orca_core", BNode.RdMem_spawn_write,spawnStage);
	 	this.PutNode( "std_logic", "","orca_core", BNode.WrMem_spawn_write,spawnStage);


	 	this.PutNode( "std_logic"       , "", "orca_core", BNode.WrMem_spawn_validReq,spawnStage);
	 	this.PutNode( "std_logic_vector", "", "orca_core", BNode.WrMem_spawn_addr,spawnStage);
	 	
	 	this.PutNode( "std_logic", "execute_to_decode_ready","orca_core", BNode.ISAX_spawnAllowed,3);
	 	this.PutNode( "std_logic", "","instruction_fetch", this.nodePCCorrValid,1);//
	 	this.PutNode( "std_logic","","execute",ISAX_FWD_ALU,3);
	 	this.PutNode( "std_logic","to_stage1_ready", "decode", ISAX_frwrd_to_stage1_ready, 2);
	 	this.PutNode( "std_logic","","load_store_unit", is_ISAX, 3);
     }


	
}

