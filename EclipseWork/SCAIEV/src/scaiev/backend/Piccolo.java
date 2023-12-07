package scaiev.backend;

import java.util.HashMap;
import java.util.HashSet;

import scaiev.coreconstr.Core;
import scaiev.frontend.FNode;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.util.Bluespec;
import scaiev.util.FileWriter;
import scaiev.util.Lang;
import scaiev.util.ToWrite;

public class Piccolo extends CoreBackend{


	HashSet<String> AddedIValid = new HashSet<String>();

	public  String 			pathCore = "CoresSrc/Piccolo";
	public String getCorePathIn() {
		return pathCore;
	}
	public  String 			pathPiccolo = "src_Core/";
	private Core 			piccolo_core;
	private HashMap <String,SCAIEVInstr>  ISAXes;
	private HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr;
	private FileWriter 		toFile = new FileWriter(pathCore);
	public  String          tab = toFile.tab;
	private String 			extension_name;
	private Bluespec        language = new Bluespec(toFile,this);
	private String          topModule = "mkCore";
	private String 			grepDeclareBehav = "// INTERFACE";
	
	private int nrTabs = 0;
	public boolean Generate (HashMap <String,SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr, String extension_name, Core core, String out_path) { // core needed for verification purposes
		// Set variables
		this.piccolo_core = core;
		this.ISAXes = ISAXes;
		this.op_stage_instr = op_stage_instr;
		this.extension_name = extension_name;
		ConfigPiccolo();
		IntegrateISAX_IOs();
		IntegrateISAX_NoIllegalInstr();
		IntegrateISAX_RdStall();
		IntegrateISAX_WrStall();
		IntegrateISAX_SpawnRD();
		IntegrateISAX_FlushStages();
		IntegrateISAX_Mem();
		IntegrateISAX_WrRD();
		IntegrateISAX_WrPC();
		IntegrateISAX_SpawnMem();
		
		toFile.WriteFiles(language.GetDictModule(),language.GetDictEndModule(),out_path);
	
		return false;
	}
	
	// Infos for SCAL
	public HashMap<SCAIEVNode, Integer> PrepareEarliest() {
		HashMap<SCAIEVNode, Integer> node_stageValid = new HashMap<SCAIEVNode, Integer>();
		node_stageValid.put(BNode.WrRD,1);
		return node_stageValid;		
	};
		
	private void IntegrateISAX_IOs() {

		 language.GenerateAllInterfaces(topModule,op_stage_instr,ISAXes,  piccolo_core ,BNode.RdStall);
		 for(int stage = 0;stage<=this.piccolo_core.maxStage;stage++) {
			 // Typical for Piccolo: if useer wants flush in last stage, we need to generate RdInstr and Flush signals from pre-last stage . These will be used by datahaz mechanism in case of spawn 
			 if((stage > (this.piccolo_core.GetNodes().get(BNode.RdRS1).GetEarliest()+1)) && op_stage_instr.containsKey(BNode.WrPC) && op_stage_instr.get(BNode.WrPC).containsKey(stage)) {
				 if(!(op_stage_instr.containsKey(BNode.RdInstr) && op_stage_instr.get(BNode.RdInstr).containsKey(stage-1)))
					 language.UpdateInterface(topModule,BNode.RdInstr, "",stage-1,true,false);
				 if(!(op_stage_instr.containsKey(BNode.RdFlush) && op_stage_instr.get(BNode.RdFlush).containsKey(stage-1)))
					 language.UpdateInterface(topModule,BNode.RdFlush, "",stage-1,true,false);
				 
			 }
		 }	
	}
	
	private void IntegrateISAX_NoIllegalInstr() {
		HashSet<String> allISAX = new HashSet<String>();
		for(String ISAX : ISAXes.keySet())
			if(!ISAXes.get(ISAX).HasNoOp())
				allISAX.add(ISAX);
		String rdInstr  = "isax_instr";
		this.toFile.UpdateContent(this.ModFile("fv_ALU"), "function ALU_Outputs fv_ALU (ALU_Inputs inputs);",new ToWrite("let "+rdInstr+" = {inputs.decoded_instr.funct7, 10'd0, inputs.decoded_instr.funct3,5'd0,inputs.decoded_instr.opcode};\n",false,true,""));
		if(this.op_stage_instr.containsKey(BNode.WrMem)) {
			toFile.ReplaceContent(this.ModFile("fv_ALU"),"alu_outputs.op_stage2 = OP_Stage2_ST;", new ToWrite("alu_outputs.op_stage2 = is_isax ? OP_Stage2_ISAX :  OP_Stage2_ST;\n", false, true, ""));
			this.toFile.UpdateContent(this.ModFile("fv_ALU"), "function ALU_Outputs fv_STORE (ALU_Inputs inputs);", new ToWrite("let "+rdInstr+" = {inputs.decoded_instr.funct7, 10'd0, inputs.decoded_instr.funct3,5'd0,inputs.decoded_instr.opcode};\n",false,true,""));
			this.toFile.UpdateContent(this.ModFile("fv_ALU"), "function ALU_Outputs fv_STORE (ALU_Inputs inputs);",new ToWrite("let is_isax =  "+language.CreateAllEncoding(op_stage_instr.get(BNode.WrMem).get(this.piccolo_core.GetNodes().get(BNode.WrMem).GetEarliest()),ISAXes,rdInstr)+";\n",false,true,""));	
			toFile.ReplaceContent(this.ModFile("fv_ALU"), "Bool legal_STORE = ",new ToWrite("Bool legal_STORE = (   ((opcode == op_STORE) || is_isax)",false,true,""));
			this.toFile.UpdateContent(this.ModFile("fv_ALU"), "else begin",new ToWrite("else if( "+language.CreateAllEncoding(op_stage_instr.get(BNode.WrMem).get(this.piccolo_core.GetNodes().get(BNode.WrMem).GetEarliest()),ISAXes,rdInstr)+") begin\n"+tab+"alu_outputs = fv_STORE (inputs);\nend",true,false,"inputs.decoded_instr.opcode == op_STORE_FP)",true));	
		}
		if(this.op_stage_instr.containsKey(BNode.RdMem)) { 
			toFile.ReplaceContent(this.ModFile("fv_ALU"),"alu_outputs.op_stage2 = OP_Stage2_LD;", new ToWrite("alu_outputs.op_stage2 = is_isax ? OP_Stage2_ISAX :  OP_Stage2_LD;\n", false, true, ""));
			
			this.toFile.UpdateContent(this.ModFile("fv_ALU"), "function ALU_Outputs fv_LOAD (ALU_Inputs inputs);", new ToWrite("let "+rdInstr+" ={inputs.decoded_instr.funct7, 10'd0, inputs.decoded_instr.funct3,5'd0,inputs.decoded_instr.opcode};\n",false,true,""));
			this.toFile.UpdateContent(this.ModFile("fv_ALU"), "function ALU_Outputs fv_LOAD (ALU_Inputs inputs);",new ToWrite("let is_isax =  "+language.CreateAllEncoding(op_stage_instr.get(BNode.RdMem).get(this.piccolo_core.GetNodes().get(BNode.RdMem).GetEarliest()),ISAXes,rdInstr)+";\n",false,true,""));	
			toFile.ReplaceContent(this.ModFile("fv_ALU"), "Bool legal_LOAD = ",new ToWrite("Bool legal_LOAD = (   ((opcode == op_LOAD) || is_isax)",false,true,""));
			this.toFile.UpdateContent(this.ModFile("fv_ALU"), "else begin",new ToWrite("else if( "+language.CreateAllEncoding(op_stage_instr.get(BNode.RdMem).get(this.piccolo_core.GetNodes().get(BNode.RdMem).GetEarliest()),ISAXes,rdInstr)+") begin\n"+tab+"alu_outputs = fv_LOAD (inputs);\nend",true,false,"inputs.decoded_instr.opcode == op_LOAD_FP)",true));	
		}
		if(this.op_stage_instr.containsKey(BNode.RdRS1) || this.op_stage_instr.containsKey(BNode.RdInstr) || this.op_stage_instr.containsKey(BNode.WrPC) || this.op_stage_instr.containsKey(BNode.RdPC) || this.op_stage_instr.containsKey(BNode.RdIValid)) {
			String ifClause = language.CreateAllNoMemEncoding(allISAX,ISAXes,rdInstr);
			System.out.println("ifClause = "+ ifClause);
			if(!ifClause.isEmpty()) {
				this.toFile.UpdateContent(this.ModFile("fv_ALU"), "else begin",new ToWrite("else if( "+language.CreateAllNoMemEncoding(allISAX,ISAXes,rdInstr)+") begin\n"+tab+"alu_outputs = alu_outputs_base;\nalu_outputs.op_stage2 = OP_Stage2_ISAX;\nend",true,false,"else if (   (inputs.decoded_instr.opcode == op_STORE_FP))",true));	
			}
		}
		this.toFile.UpdateContent(this.ModFile("CPU_Globals"),"typedef enum {  OP_Stage2_ALU",new ToWrite(", OP_Stage2_ISAX",false,true,"")); // leave it heere outside of if-else to avid undefined err
		
	}
	
	private void IntegrateISAX_WrRD() {
		String wrRD = "// ISAX WrRD Logic //\n";
		int stage = this.piccolo_core.GetNodes().get(BNode.WrRD).GetLatest();
		if(op_stage_instr.containsKey(BNode.WrRD)) {
			if(this.ContainsOpInStage(BNode.WrRD, 2)) {
				// Valid bit
				toFile.UpdateContent( this.ModFile("mkCPU_Stage3"), "// BEHAVIOR", new ToWrite("let isax_rg_stage3_rd_valid = ("+language.CreateLocalNodeName(BNode.WrRD_validData, stage,"")+") ? "+language.CreateLocalNodeName(BNode.WrRD_valid, stage,"")+" : rg_stage3.rd_valid;\n",false,true,""));
				String textAdd = "rule wrrd_data_stage3 ;\n"
						+ "stage3.met_vWrRD_validData_1_i("+language.CreateLocalNodeName(BNode.WrRD_validData, 1, "")+");\n"
						+ "endrule\n"; 
				this.toFile.UpdateContent(this.ModFile("mkCPU"),grepDeclareBehav,  new ToWrite(textAdd, false,true,""));
				textAdd = "(*always_enabled*) method Action met_vWrRD_validData_1_i(Bool x);";
				toFile.UpdateContent( this.ModFile("mkCPU_Stage3"),"endinterface" , new ToWrite(textAdd, false,true,"",true));
				textAdd = "method Action met_vWrRD_validData_1_i(Bool x);\n"
						+ "    "+language.CreateLocalNodeName(BNode.WrRD_validData, 1, "")+" <= x;\n"
						+ "endmethod\n";
				toFile.UpdateContent( this.ModFile("mkCPU_Stage3"),"endmodule" , new ToWrite(textAdd, false,true,"",true));
				textAdd ="Reg #(Bool) "+language.CreateLocalNodeName(BNode.WrRD_validData, 1, "")+" <- mkReg(False);";
				this.toFile.UpdateContent(this.ModFile("mkCPU_Stage3"), ");",new ToWrite(textAdd, true,false,"module mkCPU_Stage3"));
				
				
				/// Data
				toFile.UpdateContent( this.ModFile("mkCPU_Stage3"), "// BEHAVIOR", new ToWrite("let isax_rg_stage3_rd_val = (("+language.CreateLocalNodeName(BNode.WrRD_validData, stage,"")+" && (!"+language.CreateLocalNodeName(BNode.WrRD_validData, stage-1,"")+")) ? "+language.CreateLocalNodeName(BNode.WrRD,stage,"")+" : rg_stage3.rd_val);\n",false,true,""));
				toFile.ReplaceContent(this.ModFile("mkCPU_Stage3"), "rd_val:", new ToWrite("rd_val:       isax_rg_stage3_rd_val\n",true,false,"let bypass_base = Bypass"));
				toFile.ReplaceContent(this.ModFile("mkCPU_Stage3"), "gpr_regfile.write_rd", new ToWrite("gpr_regfile.write_rd (rg_stage3.rd, isax_rg_stage3_rd_val);\n",true,false,"// Write to GPR"));
				toFile.ReplaceContent(this.ModFile("mkCPU_Stage3"), "rg_stage3.rd, rg_stage3.rd_val", new ToWrite("rg_stage3.rd, isax_rg_stage3_rd_val);\n",true,false,"write GRd 0x"));
				toFile.ReplaceContent(this.ModFile("mkCPU_Stage3"), "if (rg_stage3.rd_valid) begin",new ToWrite("if (isax_rg_stage3_rd_valid) begin", false, true, "" ));
				toFile.ReplaceContent(this.ModFile("mkCPU_Stage3"), "bypass.bypass_state = (rg_full && rg_stage3.rd_valid)", new ToWrite(" bypass.bypass_state = (rg_full && isax_rg_stage3_rd_valid) ? BYPASS_RD_RDVAL", false, true, "" ));
				toFile.ReplaceContent(this.ModFile("mkCPU_Stage3"), "bypass.bypass_state = (rg_full && rg_stage3.rd_valid)", new ToWrite(" bypass.bypass_state = (rg_full && isax_rg_stage3_rd_valid) ? BYPASS_RD_RDVAL", true, false, "`else" ));
				toFile.ReplaceContent(this.ModFile("mkCPU_Stage3"), "bypass.bypass_state = (rg_full && rg_stage3.rd_valid)", new ToWrite(" bypass.bypass_state = (rg_full && isax_rg_stage3_rd_valid) ? BYPASS_RD_RDVAL", true, false, "else begin" ));
				
				
				// If not present lso in stage 1, make sure validreq is 
				if(!this.ContainsOpInStage(BNode.WrRD, 1))
					language.UpdateInterface(this.ModFile("mkCPU_Stage2"),BNode.WrRD_valid, "",1,true,false);
				
			} else if(this.ContainsOpInStage(BNode.WrRD, 1))  {
				if(!this.ContainsOpInStage(BNode.WrRD, 2))
					language.UpdateInterface(this.ModFile("mkCPU"),BNode.WrRD_valid, "",2,true,false); // Just to avoid wrapper cry that this signal does not exist. Will be optimized away anyway
			}
			// Instructions that write WrRD but don't write mem
			String addText = "";
			if(this.ContainsOpInStage(BNode.WrRD, 2) || this.ContainsOpInStage(BNode.WrRD, 1)) {
				String textAdd = "rule wrrd_data_stage2; \n"
						+ "stage2.met_vWrRD_validData_1_i("+language.CreateLocalNodeName(BNode.WrRD_validData, 1, "")+");\n"
						+ "endrule\n"; 
				this.toFile.UpdateContent(this.ModFile("mkCPU"),grepDeclareBehav,  new ToWrite(textAdd, false,true,""));
				textAdd = "(*always_enabled*) method Action met_vWrRD_validData_1_i(Bool x);";
				toFile.UpdateContent( this.ModFile("mkCPU_Stage2"),"endinterface" , new ToWrite(textAdd, false,true,"",true));
				textAdd = "method Action met_vWrRD_validData_1_i(Bool x);\n"
						+ "    "+language.CreateLocalNodeName(BNode.WrRD_validData, 1, "")+" <= x;\n"
						+ "endmethod\n";
				toFile.UpdateContent( this.ModFile("mkCPU_Stage2"),"endmodule" , new ToWrite(textAdd, false,true,"",true));
				textAdd ="Wire #(Bool) "+language.CreateLocalNodeName(BNode.WrRD_validData, 1, "")+" <- mkDWire(False);";
				this.toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), ");",new ToWrite(textAdd, true,false,"module mkCPU_Stage2"));
				if(!this.ContainsOpInStage(BNode.WrRD, 1)) // if it doesn't contain it to be generated automatically, add it artifically for Data Hazard logic
				language.UpdateInterface("mkCore", BNode.WrRD_validData, "", 1, true, false); // Add validData stage 1 on top interface
				if(!this.ContainsOpInStage(BNode.WrRD, 2)) // If it does not contain it, add it artificially for top module wrapper
				language.UpdateInterface("mkCore", BNode.WrRD_validData, "", 2, true, false); // Add validData stage 2 on top interface, although not used!! Just for script top module
				
				if(this.ContainsOpInStage(BNode.WrRD, 1)) // Only if we have a WrRD in stage 1 it makes sense to check for WrRD_validData
					addText += " else if ("+language.CreateLocalNodeName(BNode.WrRD_valid, 1, "")+" && "+language.CreateLocalNodeName(BNode.WrRD_validData, 1, "")+") begin\n"+language.CreateMemStageFrwrd(true,false, true);
				addText += " else if ("+language.CreateLocalNodeName(BNode.WrRD_valid, 1, "")+") begin\n"+language.CreateMemStageFrwrd(true,false, false);
				this.toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), "// This stage is just relaying ALU", new ToWrite(addText, false, true , "", true ));			
			}
		}	
		
		// No Bypass or data to write to RegF if: 1) I have a WrRD with user valid that can be canceled 2) I have at least 1 instr not writing no regfile
		boolean noWrRD = false;
		if (!this.op_stage_instr.containsKey(BNode.WrRD))
			noWrRD = true; 
		else {
			for(String ISAX : ISAXes.keySet()) {
				if(!ISAXes.get(ISAX).GetSchedNodes().containsKey(BNode.WrRD) && !ISAXes.get(ISAX).HasNoOp())
					noWrRD = true;
				if(ISAXes.get(ISAX).GetSchedNodes().containsKey(BNode.WrRD) && ISAXes.get(ISAX).GetSchedNodes().get(BNode.WrRD).get(0).HasAdjSig(AdjacentNode.validReq)) // TODO list , we should get all
					noWrRD = true;
			}
		}
		if (noWrRD)
			this.toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), "// This stage is just relaying ALU", new ToWrite("else if(rg_stage2.op_stage2 ==  OP_Stage2_ISAX ) begin\n"+language.CreateMemStageFrwrd(false,false,false),false,true,"", true));
		
	}
	
	private void IntegrateISAX_RdStall() {
		if(op_stage_instr.containsKey(BNode.RdStall)) {
			for(int stage = 0; stage <=2; stage++) {
				if(op_stage_instr.get(BNode.RdStall).containsKey(stage)) {
					if(stage==1) {
						toFile.UpdateContent(this.ModFile("mkCPU"), "end", new ToWrite("else\n"+tab+language.CreateLocalNodeName(BNode.RdStall, stage,"")+" <= True;",true,false,"// Move instruction from Stage"+(stage+1)+" to Stage"+(stage+2)));					
						toFile.UpdateContent(this.ModFile("mkCPU"), "stage3.enq (stage2.out.data_to_stage3);", new ToWrite(language.CreateLocalNodeName(BNode.RdStall, stage,"")+" <= False;",false,true,""));					
					} else if(stage == 0) {
						toFile.UpdateContent(this.ModFile("mkCPU"), "begin", new ToWrite(language.CreateLocalNodeName(BNode.RdStall, stage,"")+" <= False;",true,false,"// Move instruction from Stage"+(stage+1)+" to Stage"+(stage+2)));					
						toFile.UpdateContent(this.ModFile("mkCPU"), "end", new ToWrite("else\n"+tab+language.CreateLocalNodeName(BNode.RdStall, stage,"")+" <= True;",true,false,"// Move instruction from Stage"+(stage+1)+" to Stage"+(stage+2)));					
					} else if (stage == 2) {
						String toAdd = language.CreateLocalNodeName(BNode.RdStall, 2, "")+ " <= False;";
						String grep  = "if (stage3.out.ostatus == OSTATUS_PIPE) begin";
						this.toFile.UpdateContent(this.ModFile("mkCPU"), grep,new ToWrite(toAdd, false, true,"", true));
						
					} 
					String declare = "Wire #( Bool ) "+language.CreateLocalNodeName(BNode.RdStall,stage,"")+" <- mkDWire( True );\n";
					this.toFile.UpdateContent(this.ModFile("mkCPU"), ");",new ToWrite(declare, true,false,"module mkCPU"));
					this.toFile.UpdateContent(this.ModInterfFile("mkCPU"), "endinterface",new ToWrite(language.CreateMethodName(BNode.RdStall,stage,"",false)+";\n",true,false,"interface",true));
					this.toFile.UpdateContent(this.ModInterfFile("mkCore"), "endinterface",new ToWrite("(*always_enabled *)"+language.CreateMethodName(BNode.RdStall,stage,"",false)+";\n",true,false,"interface",true));
					String assignText = language.CreateMethodName(BNode.RdStall,stage,"",false)+";\n"+tab+"return "+language.CreateLocalNodeName(BNode.RdStall,stage, "")+";\nendmethod";
					this.toFile.UpdateContent(this.ModFile("mkCPU"), "endmodule",new ToWrite(assignText,true,false,"module mkCPU",true));
					this.toFile.UpdateContent(this.ModFile("mkCore"), "endmodule",new ToWrite(language.CreateMethodName(BNode.RdStall,stage,"",true)+" = cpu.met_"+language.CreateNodeName(BNode.RdStall, stage,"")+";\n",true,false,"module mkCore",true));
				}
			}
		}
	}
	
	private void IntegrateISAX_WrStall() {
		if(ContainsOpInStage(BNode.WrStall,1))
			toFile.ReplaceContent(this.ModFile("mkCPU"), "if ((! stage3_full)", new ToWrite("if ((! stage3_full) && (stage2.out.ostatus == OSTATUS_PIPE) && (!"+language.CreateLocalNodeName(BNode.WrStall, 1,"")+")) begin\n",true,false,"// Move instruction from Stage2 to Stage3"));					
		if(ContainsOpInStage(BNode.WrStall,0))
			toFile.ReplaceContent(this.ModFile("mkCPU"), "if (   (! halting)", new ToWrite("if (   (! halting) && (!"+language.CreateLocalNodeName(BNode.WrStall, 0,"")+")",true,false,"// Move instruction from Stage1 to Stage2"));									
		if(ContainsOpInStage(BNode.WrStall,2)) 
			toFile.ReplaceContent(this.ModFile("mkCPU"), "rule rl_pipe",new ToWrite("rule rl_pipe (   (rg_state == CPU_RUNNING) && (!("+language.CreateLocalNodeName(BNode.WrStall, 2, "")+"))",false,true,""));
		
	}
	private void IntegrateISAX_WrPC() {
		int nrJumps = 0;
		String updatePC = "";
		String [] instrName = {"stage1.out.data_to_stage2.instr","stage2.out.data_to_stage3.instr","stage2.out.data_to_stage3.instr"};
		for(int stage = this.piccolo_core.maxStage; stage>=0 ; stage--) {
			if(stage==0)
				if(op_stage_instr.containsKey(BNode.WrPC_spawn)) {
					updatePC += "("+language.CreateLocalNodeName(BNode.WrPC_spawn_valid, this.piccolo_core.maxStage+1, "")+") ? ("+language.CreateLocalNodeName(BNode.WrPC_spawn, this.piccolo_core.maxStage+1, "")+") : (";
					nrJumps++;
				}
			
			if(ContainsOpInStage(BNode.WrPC,stage)) {
				updatePC += "("+language.CreateLocalNodeName(BNode.WrPC_valid, stage, "")+") ? ("+language.CreateLocalNodeName(BNode.WrPC, stage, "")+") : (";
				nrJumps++;
				
			
			}	
		}
			
		// Change next_pc with ISAX one
		if(nrJumps>0) {
			updatePC += "next_pc"+ ")".repeat(nrJumps)+";";
			toFile.ReplaceContent(this.ModFile("mkCPU_Stage1"), "output_stage1.next_pc", new ToWrite("output_stage1.next_pc = isax_next_pc;",true,false,"let next_pc"));									
			toFile.UpdateContent(this.ModFile("mkCPU_Stage1"), ": fall_through_pc);", new ToWrite("let isax_next_pc ="+updatePC,true,false,"let next_pc"));											
		}
	}
	
	private void IntegrateISAX_FlushStages () {
		// flush stages in case of ISAX jumps or WrFlush
		if( op_stage_instr.containsKey(BNode.WrFlush) ) {
			String validPCFlush = "";
			String  grep = "if (! rg_full) begin";
			if(ContainsOpInStage(BNode.WrFlush,1)) {
				validPCFlush = language.CreateLocalNodeName(BNode.WrFlush,1, "");
				toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), grep,new ToWrite("if("+validPCFlush+") begin \n "+ language.CreateMemStageFrwrd(false,true, false),false,true,"",true));
			}
			if(ContainsOpInStage(BNode.WrFlush,0)) {
				validPCFlush = language.CreateLocalNodeName(BNode.WrFlush,0, "");
				String addText = "if ("+validPCFlush+") begin \n"
						+ "output_stage1.ostatus = OSTATUS_EMPTY;\n"
						+ "	end else \n";
				toFile.UpdateContent(this.ModFile("mkCPU_Stage1"),grep,new ToWrite(addText,false,true,"",true));		
			}
		}
	}
	
	private void IntegrateISAX_SpawnRD() {
		int spawnStage = this.piccolo_core.maxStage+1;
		String commit_stage = "stage3";
		if(op_stage_instr.containsKey(BNode.WrRD_spawn)) {		
			// Update stage 3 
			String stage3 ="rule rule_"+language.CreateNodeName(BNode.WrRD_spawn_valid, spawnStage,"")+"("+language.CreateLocalNodeName(BNode.WrRD_spawn_valid, spawnStage, "")+");\n"
					+ tab + "if("+language.CreateLocalNodeName(BNode.WrRD_spawn_valid, spawnStage, "")+")\n"
					+ tab + "gpr_regfile.write_rd ("+language.CreateLocalNodeName(BNode.WrRD_spawn_addr, spawnStage, "")+", "+language.CreateLocalNodeName(BNode.WrRD_spawn, spawnStage, "")+");\n"
					+ "endrule\n";
			this.toFile.UpdateContent(this.ModFile("mkCPU_Stage3"), grepDeclareBehav, new ToWrite(stage3+"\n",false,true,"", true));
			
		} 
	}
	
	private void IntegrateISAX_Mem() {
		int stage = this.piccolo_core.GetNodes().get(FNode.RdMem).GetEarliest();
		boolean rdMem = op_stage_instr.containsKey(BNode.RdMem); 
		boolean wrMem =  op_stage_instr.containsKey(BNode.WrMem);
		
		if(rdMem || wrMem) {
			String validMem = "";
			String toGrep = "";
			String toAdd = "";
			// Start access 
			// Valid 
			if(wrMem) {
				validMem += language.CreateLocalNodeName(BNode.WrMem_validReq, stage, "");
				toGrep = "else if (x.op_stage2 == OP_Stage2_ST"; 
				toAdd =  "else if (x.op_stage2 == OP_Stage2_ST || "+language.CreateLocalNodeName(BNode.WrMem_validReq, stage, "")+")  cache_op = CACHE_ST;\n";
				this.toFile.ReplaceContent(this.ModFile("mkCPU_Stage2"),toGrep, new ToWrite(toAdd,false,true,""));
				
			}
			if(rdMem) {
				validMem  = language.OpIfNEmpty(validMem, " || ")+ language.CreateLocalNodeName(BNode.RdMem_validReq, stage, "");
				toGrep = "if      (x.op_stage2 == OP_Stage2_LD"; 
				toAdd = "if      (x.op_stage2 == OP_Stage2_LD || "+language.CreateLocalNodeName(BNode.RdMem_validReq, stage, "")+")  cache_op = CACHE_LD;\n";
				this.toFile.ReplaceContent(this.ModFile("mkCPU_Stage2"),toGrep, new ToWrite(toAdd,false,true,""));
				
			}
			toGrep = "if ((x.op_stage2 == OP_Stage2_LD) || (x.op_stage2 == OP_Stage2_ST)";
			toAdd = "if ((x.op_stage2 == OP_Stage2_LD) || (x.op_stage2 == OP_Stage2_ST) || op_stage2_amo || (x.op_stage2 == OP_Stage2_ISAX && ("+validMem+") ) ) begin\n";
			this.toFile.ReplaceContent(this.ModFile("mkCPU_Stage2"),toGrep, new ToWrite(toAdd,false,true,""));
			
			// Stall if mem  not ready 
			toGrep = "If DMem access, initiate it";
			toAdd = " isax_mem <= "+validMem+";\n"; // Create reg to store that it.s a mem access 
			this.toFile.UpdateContent(this.ModFile("mkCPU_Stage2"),toGrep, new ToWrite(toAdd,false,true,"", true));
			
			toAdd = "Reg #(Bool) isax_mem   <- mkReg (False);\n"; // declare reg
			this.toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), ");", new ToWrite(toAdd,true,false,"module mkCPU_Stage2"));
			
			toAdd = "else if (rg_stage2.op_stage2 == OP_Stage2_ISAX && (! dcache.valid || dcache.exc) && isax_mem) begin\n"
					+ "\n"
					+ "	 let ostatus = (  (! dcache.valid)\n"
					+ "			     ? OSTATUS_BUSY\n"
					+ "			     : (  dcache.exc\n"
					+ "				? OSTATUS_NONPIPE\n"
					+ "				: OSTATUS_PIPE));\n"
					+ "	 let data_to_stage3 = data_to_stage3_base;\n"
					+ "	 data_to_stage3.rd_valid = (ostatus == OSTATUS_PIPE);\n"
					+ "	 data_to_stage3.rd       = 0;\n"
					+ "	 output_stage2 = Output_Stage2 {ostatus         : ostatus,\n"
					+ "					trap_info       : trap_info_dmem,\n"
					+ "					data_to_stage3  : data_to_stage3,\n"
					+ "					bypass          : no_bypass\n"
					+ "`ifdef ISA_F\n"
					+ "					, fbypass       : no_fbypass\n"
					+ "`endif\n"
					+ "					};\n"
					+ "end \n";
			this.toFile.UpdateContent(this.ModFile("mkCPU_Stage2"),"// This stage is just relaying ALU", new ToWrite(toAdd,false,true,"", true));
			
			
			// Wdata
			if(wrMem) {
				this.toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), "dcache.req", new ToWrite("let iasx_wdata_from_gpr = ("+language.CreateLocalNodeName(BNode.WrMem_validReq, stage, "")+") ? zeroExtend ("+language.CreateLocalNodeName(BNode.WrMem, stage, "")+") : wdata_from_gpr;",false,true,"",true));
				this.toFile.ReplaceContent(this.ModFile("mkCPU_Stage2"), "wdata_from_gpr,", new ToWrite("iasx_wdata_from_gpr,",true,false,"dcache.req"));
			}
			
			// Address
			String addr = "";
			if(rdMem) {
				addr += language.CreateLocalNodeName(BNode.RdMem_addr_valid, stage, "") +" ? " +language.CreateLocalNodeName(BNode.RdMem_addr, stage, "") + " : " ; 				
			}
			if(wrMem)
				addr = language.OpIfNEmpty(addr, "( ")+ language.CreateLocalNodeName(BNode.WrMem_addr_valid, stage, "") +" ? " +language.CreateLocalNodeName(BNode.WrMem_addr, stage, "") + " : " ; 
			addr += "  x.addr "; 
			if(rdMem && wrMem )
				addr += ")";
			this.toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), "dcache.req", new ToWrite("let isax_x_addr = "+addr+";",false,true,"",true));
			this.toFile.ReplaceContent(this.ModFile("mkCPU_Stage2"), "x.addr,", new ToWrite("isax_x_addr,",true,false,"dcache.req"));
			
				
		}
	}
	
	
	private void IntegrateISAX_SpawnMem() {
		int spawnStage = this.piccolo_core.maxStage +1;
		String commit_stage = "stage2";
		
		if(op_stage_instr.containsKey(BNode.RdMem_spawn) || op_stage_instr.containsKey(BNode.WrMem_spawn)) {
			
			// Fire and Commit Logic
			String writeData = "32'd0";
			if(op_stage_instr.containsKey(BNode.WrMem_spawn))
				writeData =  language.CreateLocalNodeName(BNode.WrMem_spawn, spawnStage,"");
			String writeRes = "rule rule_send_to_stage2_spawn( "+language.CreateLocalNodeName(BNode.WrMem_spawn_validReq, spawnStage, "")+" &&  !isax_memONgoing);\n"
					+ tab+"stage2.met_commitspawn ("+language.CreateLocalNodeName(BNode.RdMem_spawn_addr, spawnStage,"")+","+writeData+","+language.CreateLocalNodeName(BNode.RdMem_spawn_validReq, spawnStage,"")+","+language.CreateLocalNodeName(BNode.RdMem_spawn_write, spawnStage,"")+");\n"
					+ tab+"if("+language.CreateLocalNodeName(BNode.RdMem_spawn_validReq, spawnStage,"")+") isax_memONgoing <= True; \n"
					+ "endrule\n";
			this.toFile.UpdateContent(this.ModFile("mkCPU"), grepDeclareBehav, new ToWrite(writeRes+"\n",false,true,"", true));

			// Update stage 3 
			String stage2 = "method Action met_commitspawn (Bit#(32) addr, Bit#(32) data, Bool valid, Bool write); \n"
					+ "   CacheOp cache_op = ?;\n"
					+ "	    if      (write)  cache_op = CACHE_ST;\n"
					+ "	    else  cache_op = CACHE_LD;\n"
					+ "   Bit #(7) amo_funct7 = 0; // SCAIE-V not yet supported\n"
					+ "   Priv_Mode  mem_priv =m_Priv_Mode; // SCAIE-V TODO TO BE UPDATED WITH x.priv\n"
					+ "	  if (csr_regfile.read_mstatus [17] == 1'b1) \n"
					+ "	       mem_priv = csr_regfile.read_mstatus [12:11];\n"
					+ "   if(valid) \n"
					+ "        dcache.req (cache_op, \n"
					+ "             instr_funct3 ({17'd0,3'b010,12'd0}), \n"
					+ "`ifdef ISA_A\n"
					+ "             amo_funct7,\n"
					+ "`endif"
					+ "             addr, \n"
					+ "             {32'd0,data}, \n"
					+ "             mem_priv, \n"
					+ "              0, \n"
					+ "             (csr_regfile.read_mstatus)[19], \n"
					+ "              csr_regfile.read_satp); \n"
					+ "endmethod\n";

			this.toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), "endmodule", new ToWrite(stage2+"\n",false,true,"", true));
			this.toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), "endinterface", new ToWrite("method Action met_commitspawn (Bit#(32) x,Bit#(32) y,Bool z, Bool read);\n",false,true,"", true));
			
			
			String checkSpawnDone = "rule rule_isaxwire;\n"
					+ "isax_memONgoing_wire <= isax_memONgoing;\n"
					+ "endrule\n"
					+ "\n"
					+ "rule rule_resetMemOngoing (isax_memONgoing) ; \n"
					+ "if(near_mem.dmem.valid)\n"
					+ "isax_memONgoing <= False;\n"
					+ "endrule\n"
					+ "\n";
			this.toFile.UpdateContent(this.ModFile("mkCPU"), "module mkCPU",new ToWrite("Wire #(Bool) isax_memONgoing_wire <- mkDWire(False);\nReg #(Bool) isax_memONgoing <- mkReg(False);\n",false,true,""));
			
			this.toFile.UpdateContent(this.ModFile("mkCPU"), grepDeclareBehav, new ToWrite(checkSpawnDone,false,true,"", true));
			
		}
	}
	
	private boolean ContainsOpInStage(SCAIEVNode wrStall, int stage) {
		return op_stage_instr.containsKey(wrStall) && op_stage_instr.get(wrStall).containsKey(stage);
	}
	
	private void ConfigPiccolo() {
	 	this.PopulateNodesMap(this.piccolo_core.maxStage);
	 	
	 	PutModule(this.pathPiccolo+"Core/Core_IFC.bsv",  "Core_IFC", this.pathPiccolo+"Core/Core.bsv", "", "mkCore");
	 	PutModule(this.pathPiccolo+"CPU/CPU_IFC.bsv",  "CPU_IFC", this.pathPiccolo+"CPU/CPU.bsv", "mkCore", "mkCPU");
	 	PutModule(this.pathPiccolo+"CPU/CPU_Stage1.bsv",  "CPU_Stage1_IFC", this.pathPiccolo+"CPU/CPU_Stage1.bsv", "mkCPU", "mkCPU_Stage1");
	 	PutModule(this.pathPiccolo+"CPU/CPU_Stage2.bsv",  "CPU_Stage2_IFC", this.pathPiccolo+"CPU/CPU_Stage2.bsv", "mkCPU", "mkCPU_Stage2");
	 	PutModule(this.pathPiccolo+"CPU/CPU_Stage3.bsv",  "CPU_Stage3_IFC", this.pathPiccolo+"CPU/CPU_Stage3.bsv", "mkCPU", "mkCPU_Stage3");
	 	PutModule("",  "", this.pathPiccolo+"CPU/EX_ALU_functions.bsv", "", "fv_ALU");
	 	PutModule("",  "", this.pathPiccolo+"CPU/CPU_Globals.bsv", "", "CPU_Globals");	
	 	
	 	int spawnStage = this.piccolo_core.maxStage+1;
	 	
		
	 	this.PutNode("Bit", "", "mkCPU_Stage1", BNode.WrPC,0);
 		this.PutNode("Bool", "",  "mkCPU_Stage1", BNode.WrPC_valid,0);
 		this.PutNode("Bit", "",  "mkCPU_Stage1", BNode.WrPC,1);
 		this.PutNode("Bool", "",  "mkCPU_Stage1", BNode.WrPC_valid,1);
 		this.PutNode("Bit", "",  "mkCPU_Stage1", BNode.WrPC,2);
 		this.PutNode("Bool", "",  "mkCPU_Stage1", BNode.WrPC_valid,2);
 		this.PutNode("Bit", "", "mkCPU_Stage1", BNode.WrPC_spawn,3);
 		this.PutNode("Bool", "",  "mkCPU_Stage1", BNode.WrPC_spawn_valid,3);
 	
	 	this.PutNode("Bit", "pc", "mkCPU_Stage1", BNode.RdPC,0);
	 	this.PutNode("Bit", "rg_stage2.pc", "mkCPU_Stage2", BNode.RdPC,1);
	 	this.PutNode("Bit", "rg_stage3.pc", "mkCPU_Stage3", BNode.RdPC,2);
	

	 	this.PutNode("Bit", "stage1.out.data_to_stage2.instr", "mkCPU", BNode.RdInstr,0);
	 	this.PutNode("Bit", "stage2.out.data_to_stage3.instr", "mkCPU", BNode.RdInstr,1);
	 	this.PutNode("Bit", "rg_stage3.instr", "mkCPU_Stage3", BNode.RdInstr,2);
 		
	 	this.PutNode("Bit", "rs1_val_bypassed", "mkCPU_Stage1", BNode.RdRS1,0);		

	 	this.PutNode("Bit", "rs2_val_bypassed", "mkCPU_Stage1", BNode.RdRS2,0);
	 	
	 	this.PutNode("Bit", "", "mkCPU_Stage2", BNode.WrRD,1);
	 	this.PutNode("Bool", "", "mkCPU_Stage2", BNode.WrRD_valid,1);
	 	this.PutNode("Bool", "", "mkCPU", BNode.WrRD_validData,1);
	 	
	 	this.PutNode("Bit", "", "mkCPU_Stage3", BNode.WrRD,2);
	 	this.PutNode("Bool", "", "mkCPU_Stage3", BNode.WrRD_valid,2);
	 	this.PutNode("Bool", "", "mkCPU_Stage3", BNode.WrRD_validData,2);
	 	
	 	
	 	this.PutNode("Bool", "", "mkCPU", BNode.RdIValid,0);	 		
	 	this.PutNode("Bool", "", "mkCPU", BNode.RdIValid,1);	 		
	 	this.PutNode("Bool", "", "mkCPU_Stage3", BNode.RdIValid,2);	 		
	 	
		int stageMem = this.piccolo_core.GetNodes().get(BNode.RdMem).GetEarliest();
	 	this.PutNode("Bit", "truncate( near_mem.dmem.word64)", "mkCPU", BNode.RdMem,stageMem);
	 	this.PutNode("Bool", "", "mkCPU_Stage2", BNode.RdMem_validReq,stageMem);
	 	this.PutNode("Bit", "", "mkCPU_Stage2", BNode.WrMem,stageMem);
	 	this.PutNode("Bool",  "","mkCPU_Stage2", BNode.WrMem_validReq,stageMem);
	 	this.PutNode("Bit", "", "mkCPU_Stage2",BNode.RdMem_addr,stageMem);
	 	this.PutNode("Bit", "", "mkCPU_Stage2",BNode.WrMem_addr,stageMem);
	 	this.PutNode("Bool", "", "mkCPU_Stage2",BNode.RdMem_addr_valid,stageMem);
	 	this.PutNode("Bool", "", "mkCPU_Stage2",BNode.WrMem_addr_valid,stageMem);
		 
	 	this.PutNode("Bool", "", "mkCPU", BNode.RdStall,0);
	 	this.PutNode("Bool", "", "mkCPU", BNode.RdStall,1);
	 	this.PutNode("Bool", "", "mkCPU", BNode.RdStall,2);
	 	
	 	this.PutNode("Bool", "", "mkCPU", BNode.WrStall,0);
	 	this.PutNode("Bool", "", "mkCPU", BNode.WrStall,1);
	 	this.PutNode("Bool", "", "mkCPU", BNode.WrStall,2);
	 	
	 	
	 	this.PutNode("Bool", "", "mkCPU_Stage1", BNode.WrFlush,0);
	 	this.PutNode("Bool", "", "mkCPU_Stage2", BNode.WrFlush,1);
	 	this.PutNode("Bool", "", "mkCPU_Stage3", BNode.WrFlush,2);
	 	
	 	this.PutNode("Bool", "stage1.out.ostatus == OSTATUS_EMPTY", "mkCPU", BNode.RdFlush,0);
	 	this.PutNode("Bool", "stage2.out.ostatus == OSTATUS_EMPTY", "mkCPU", BNode.RdFlush,1);
	 	this.PutNode("Bool", "stage3.out.ostatus == OSTATUS_EMPTY", "mkCPU", BNode.RdFlush,2);
	 	
	 	
	 	this.PutNode("Bit", "", "mkCPU_Stage3", BNode.WrRD_spawn,spawnStage);
	 	this.PutNode("Bool", "", "mkCPU_Stage3", BNode.WrRD_spawn_valid,spawnStage);
	 	this.PutNode("Bit", "", "mkCPU_Stage3", BNode.WrRD_spawn_addr,spawnStage);
	 	this.PutNode("Bool", "True", "mkCPU_Stage3", BNode.WrRD_spawn_validResp,spawnStage);
	 	
	 	this.PutNode( "Bit", "truncate( near_mem.dmem.word64)", "mkCPU", BNode.RdMem_spawn,spawnStage);
	 	this.PutNode("Bool","","mkCPU", BNode.RdMem_spawn_validReq,spawnStage);
	 	this.PutNode( "Bool", "near_mem.dmem.valid && isax_memONgoing_wire", "mkCPU", BNode.RdMem_spawn_validResp,spawnStage);
	 	this.PutNode("Bit", "","mkCPU", BNode.RdMem_spawn_addr,spawnStage);
	 	this.PutNode( "Bool", "", "mkCPU", BNode.RdMem_spawn_write,spawnStage);
	 	
	 	this.PutNode("Bit", "", "mkCPU", BNode.WrMem_spawn,spawnStage);
	 	this.PutNode("Bool","","mkCPU",BNode.WrMem_spawn_validReq,spawnStage);
	 	this.PutNode( "Bool", "near_mem.dmem.valid && isax_memONgoing_wire", "mkCPU", BNode.WrMem_spawn_validResp,spawnStage);
	 	this.PutNode( "Bool", "", "mkCPU", BNode.WrMem_spawn_write,spawnStage);	
	 	this.PutNode("Bit", "","mkCPU", BNode.WrMem_spawn_addr,spawnStage);
	 	
	 	this.PutNode( "Bool", "stage3.out.ostatus == OSTATUS_PIPE","mkCPU", BNode.ISAX_spawnAllowed,0);
	 	

	}
	
}
