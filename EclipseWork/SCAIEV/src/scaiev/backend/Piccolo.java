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
import scaiev.util.Bluespec;
import scaiev.util.FileWriter;
import scaiev.util.Lang;
import scaiev.util.ToWrite;

public class Piccolo extends CoreBackend{

	// logging
	protected static final Logger logger = LogManager.getLogger();

	//TODO: Support for Mem_*_size, defaultAddr

	HashSet<String> AddedIValid = new HashSet<String>();

	public  String 			pathCore = "CoresSrc/Piccolo";
	public String getCorePathIn() {
		return pathCore;
	}
	public  String 			pathPiccolo = "src_Core/";
	private Core 			piccolo_core;
	private HashMap <String,SCAIEVInstr>  ISAXes;
	private HashMap<SCAIEVNode, HashMap<PipelineStage,HashSet<String>>> op_stage_instr;
	private PipelineStage[] stages;
	private FileWriter 		toFile = new FileWriter(pathCore);
	public  String          tab = toFile.tab;
	private String 			extension_name;
	private Bluespec        language = null;
	private String          topModule = "mkCore";
	private String 			grepDeclareBehav = "// INTERFACE";
	
	private int nrTabs = 0;

	public void Prepare (HashMap <String,SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<PipelineStage,HashSet<String>>> op_stage_instr, Core core, SCALBackendAPI scalAPI, BNode user_BNode) {
		super.Prepare(ISAXes, op_stage_instr, core, scalAPI, user_BNode);
		this.stages = core.GetRootStage().getAllChildren().collect(Collectors.toList()).toArray(n -> new PipelineStage[n]);
		for (int i = 0; i < this.stages.length; ++i) assert(this.stages[i].getStagePos() == i);
		this.BNode = user_BNode;
		this.language = new Bluespec(user_BNode,toFile,this);
		scalAPI.OverrideEarliestValid(user_BNode.WrRD,new PipelineFront(stages[1]));
		//Ignore WrCommit_spawn for now.
		BNode.WrCommit_spawn.tags.add(NodeTypeTag.noCoreInterface);
		BNode.WrCommit_spawn_validReq.tags.add(NodeTypeTag.noCoreInterface);
		BNode.WrCommit_spawn_validResp.tags.add(NodeTypeTag.noCoreInterface);
		BNode.WrInStageID.tags.add(NodeTypeTag.noCoreInterface);
		BNode.WrInStageID_valid.tags.add(NodeTypeTag.noCoreInterface);
		
		core.PutNode(BNode.RdInStageValid, new CoreNode(0, 0, 2, 2+1, BNode.RdInStageValid.name));
	}
	
	public boolean Generate (HashMap <String,SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<PipelineStage,HashSet<String>>> op_stage_instr, String extension_name, Core core, String out_path) { // core needed for verification purposes
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
		IntegrateISAX_RdFlush();
		IntegrateISAX_SpawnRD();
		IntegrateISAX_FlushStages();
		IntegrateISAX_Mem();
		IntegrateISAX_WrRD();
		IntegrateISAX_WrPC();
		IntegrateISAX_SpawnMem();
		
		toFile.WriteFiles(language.GetDictModule(),language.GetDictEndModule(),out_path);
	
		return true;
	}
		
	private void IntegrateISAX_IOs() {

		 language.GenerateAllInterfaces(topModule,op_stage_instr,ISAXes,  piccolo_core, null);
		 for(int stagePos = 1;stagePos<=this.piccolo_core.maxStage;stagePos++) {
			 PipelineStage stage = stages[stagePos];
			 PipelineStage stagePrev = stages[stagePos-1];
			 // Typical for Piccolo: if useer wants flush in last stage, we need to generate RdInstr and Flush signals from pre-last stage . These will be used by datahaz mechanism in case of spawn 
			 if((stagePos > (this.piccolo_core.GetNodes().get(BNode.RdRS1).GetEarliest().asInt()+1)) && ContainsOpInStage(BNode.WrPC, stage)) {
				 if(!(op_stage_instr.containsKey(BNode.RdInstr) && op_stage_instr.get(BNode.RdInstr).containsKey(stagePrev)))
					 language.UpdateInterface(topModule,BNode.RdInstr, "",stagePrev,true,false);
				 if(!(op_stage_instr.containsKey(BNode.RdFlush) && op_stage_instr.get(BNode.RdFlush).containsKey(stagePrev)))
					 language.UpdateInterface(topModule,BNode.RdFlush, "",stagePrev,true,false);
				 
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
			PipelineStage earliestWrMemStage = stages[this.piccolo_core.GetNodes().get(BNode.WrMem).GetEarliest().asInt()];
			this.toFile.UpdateContent(this.ModFile("fv_ALU"), "function ALU_Outputs fv_STORE (ALU_Inputs inputs);",new ToWrite("let is_isax =  "+language.CreateAllEncoding(op_stage_instr.get(BNode.WrMem).get(earliestWrMemStage),ISAXes,rdInstr)+";\n",false,true,""));	
			toFile.ReplaceContent(this.ModFile("fv_ALU"), "Bool legal_STORE = ",new ToWrite("Bool legal_STORE = (   ((opcode == op_STORE) || is_isax)",false,true,""));
			this.toFile.UpdateContent(this.ModFile("fv_ALU"), "else begin",new ToWrite("else if( "+language.CreateAllEncoding(op_stage_instr.get(BNode.WrMem).get(earliestWrMemStage),ISAXes,rdInstr)+") begin\n"+tab+"alu_outputs = fv_STORE (inputs);\nend",true,false,"inputs.decoded_instr.opcode == op_STORE_FP)",true));	
		}
		if(this.op_stage_instr.containsKey(BNode.RdMem)) { 
			toFile.ReplaceContent(this.ModFile("fv_ALU"),"alu_outputs.op_stage2 = OP_Stage2_LD;", new ToWrite("alu_outputs.op_stage2 = is_isax ? OP_Stage2_ISAX :  OP_Stage2_LD;\n", false, true, ""));
			
			this.toFile.UpdateContent(this.ModFile("fv_ALU"), "function ALU_Outputs fv_LOAD (ALU_Inputs inputs);", new ToWrite("let "+rdInstr+" ={inputs.decoded_instr.funct7, 10'd0, inputs.decoded_instr.funct3,5'd0,inputs.decoded_instr.opcode};\n",false,true,""));
			PipelineStage earliestRdMemStage = stages[this.piccolo_core.GetNodes().get(BNode.RdMem).GetEarliest().asInt()];
			this.toFile.UpdateContent(this.ModFile("fv_ALU"), "function ALU_Outputs fv_LOAD (ALU_Inputs inputs);",new ToWrite("let is_isax =  "+language.CreateAllEncoding(op_stage_instr.get(BNode.RdMem).get(earliestRdMemStage),ISAXes,rdInstr)+";\n",false,true,""));	
			toFile.ReplaceContent(this.ModFile("fv_ALU"), "Bool legal_LOAD = ",new ToWrite("Bool legal_LOAD = (   ((opcode == op_LOAD) || is_isax)",false,true,""));
			this.toFile.UpdateContent(this.ModFile("fv_ALU"), "else begin",new ToWrite("else if( "+language.CreateAllEncoding(op_stage_instr.get(BNode.RdMem).get(earliestRdMemStage),ISAXes,rdInstr)+") begin\n"+tab+"alu_outputs = fv_LOAD (inputs);\nend",true,false,"inputs.decoded_instr.opcode == op_LOAD_FP)",true));	
		}
		if(this.op_stage_instr.containsKey(BNode.RdRS1) || this.op_stage_instr.containsKey(BNode.RdInstr) || this.op_stage_instr.containsKey(BNode.WrPC) || this.op_stage_instr.containsKey(BNode.RdPC) || this.op_stage_instr.containsKey(BNode.RdIValid)) {
			String ifClause = language.CreateAllNoMemEncoding(allISAX,ISAXes,rdInstr);
			if(!ifClause.isEmpty()) {
				this.toFile.UpdateContent(this.ModFile("fv_ALU"), "else begin",new ToWrite("else if( "+language.CreateAllNoMemEncoding(allISAX,ISAXes,rdInstr)+") begin\n"+tab+"alu_outputs = alu_outputs_base;\nalu_outputs.op_stage2 = OP_Stage2_ISAX;\nend",true,false,"else if (   (inputs.decoded_instr.opcode == op_STORE_FP))",true));	
			}
		}
		this.toFile.UpdateContent(this.ModFile("CPU_Globals"),"typedef enum {  OP_Stage2_ALU",new ToWrite(", OP_Stage2_ISAX",false,true,"")); // leave it heere outside of if-else to avid undefined err
		
	}

	private String CreateMemStageFrwrd(boolean bypass, boolean flush,boolean bypass_val) {
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
					+ "let result = "+language.CreateLocalNodeName(BNode.WrRD, stages[1], "")+";"
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
				+ "output_stage2 = Output_Stage2 {ostatus         : "+ostatus+", \n" //previously was always OSTATUS_PIPE
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
	
	private void IntegrateISAX_WrRD() {
		String wrRD = "// ISAX WrRD Logic //\n";
		int stageNum = this.piccolo_core.GetNodes().get(BNode.WrRD).GetLatest().asInt();
		PipelineStage stage = stages[stageNum];
		if(op_stage_instr.containsKey(BNode.WrRD)) {
			String mkCPU_validDataStage1Expr = (this.ContainsOpInStage(BNode.WrRD_validData, 1) ? language.CreateLocalNodeName(BNode.WrRD_validData, stages[1], "") : "False");
			if(this.ContainsOpInStage(BNode.WrRD, 2)) {
				
				// Valid bit
				toFile.UpdateContent( this.ModFile("mkCPU_Stage3"), "// BEHAVIOR", new ToWrite("let isax_rg_stage3_rd_valid = ("+language.CreateLocalNodeName(BNode.WrRD_validData, stage,"")+") ? "+language.CreateLocalNodeName(BNode.WrRD_valid, stage,"")+" : rg_stage3.rd_valid;\n",false,true,""));
				String textAdd = "rule wrrd_data_stage3 ;\n"
						+ "stage3.met_vWrRD_validData_1_i("+mkCPU_validDataStage1Expr+");\n"
						+ "endrule\n"; 
				this.toFile.UpdateContent(this.ModFile("mkCPU"),grepDeclareBehav,  new ToWrite(textAdd, false,true,""));
				textAdd = "(*always_enabled*) method Action met_vWrRD_validData_1_i(Bool x);";
				toFile.UpdateContent( this.ModFile("mkCPU_Stage3"),"endinterface" , new ToWrite(textAdd, false,true,"",true));
				textAdd = "method Action met_vWrRD_validData_1_i(Bool x);\n"
						+ "    "+language.CreateLocalNodeName(BNode.WrRD_validData, stages[1], "")+" <= x;\n"
						+ "endmethod\n";
				toFile.UpdateContent( this.ModFile("mkCPU_Stage3"),"endmodule" , new ToWrite(textAdd, false,true,"",true));
				textAdd ="Reg #(Bool) "+language.CreateLocalNodeName(BNode.WrRD_validData, stages[1], "")+" <- mkReg(False);";
				this.toFile.UpdateContent(this.ModFile("mkCPU_Stage3"), ");",new ToWrite(textAdd, true,false,"module mkCPU_Stage3"));
				
				
				/// Data
				toFile.UpdateContent( this.ModFile("mkCPU_Stage3"), "// BEHAVIOR", new ToWrite("let isax_rg_stage3_rd_val = (("+language.CreateLocalNodeName(BNode.WrRD_validData, stage,"")+" && (!"+language.CreateLocalNodeName(BNode.WrRD_validData, stages[stageNum-1],"")+")) ? "+language.CreateLocalNodeName(BNode.WrRD,stage,"")+" : rg_stage3.rd_val);\n",false,true,""));
				toFile.ReplaceContent(this.ModFile("mkCPU_Stage3"), "rd_val:", new ToWrite("rd_val:       isax_rg_stage3_rd_val\n",true,false,"let bypass_base = Bypass"));
				toFile.ReplaceContent(this.ModFile("mkCPU_Stage3"), "gpr_regfile.write_rd", new ToWrite("gpr_regfile.write_rd (rg_stage3.rd, isax_rg_stage3_rd_val);\n",true,false,"// Write to GPR"));
				toFile.ReplaceContent(this.ModFile("mkCPU_Stage3"), "rg_stage3.rd, rg_stage3.rd_val", new ToWrite("rg_stage3.rd, isax_rg_stage3_rd_val);\n",true,false,"write GRd 0x"));
				toFile.ReplaceContent(this.ModFile("mkCPU_Stage3"), "if (rg_stage3.rd_valid) begin",new ToWrite("if (isax_rg_stage3_rd_valid) begin", false, true, "" ));
				toFile.ReplaceContent(this.ModFile("mkCPU_Stage3"), "bypass.bypass_state = (rg_full && rg_stage3.rd_valid)", new ToWrite(" bypass.bypass_state = (rg_full && isax_rg_stage3_rd_valid) ? BYPASS_RD_RDVAL", false, true, "" ));
				toFile.ReplaceContent(this.ModFile("mkCPU_Stage3"), "bypass.bypass_state = (rg_full && rg_stage3.rd_valid)", new ToWrite(" bypass.bypass_state = (rg_full && isax_rg_stage3_rd_valid) ? BYPASS_RD_RDVAL", true, false, "`else" ));
				toFile.ReplaceContent(this.ModFile("mkCPU_Stage3"), "bypass.bypass_state = (rg_full && rg_stage3.rd_valid)", new ToWrite(" bypass.bypass_state = (rg_full && isax_rg_stage3_rd_valid) ? BYPASS_RD_RDVAL", true, false, "else begin" ));
				
				
//				// If not present lso in stage 1, make sure validreq is 
//				if(!this.ContainsOpInStage(BNode.WrRD, 1))
//					language.UpdateInterface(this.ModFile("mkCPU_Stage2"),BNode.WrRD_valid, "",stages[1],true,false);
				
			} else if(this.ContainsOpInStage(BNode.WrRD, 1))  {
//				if(!this.ContainsOpInStage(BNode.WrRD, 2))
//					language.UpdateInterface(this.ModFile("mkCPU"),BNode.WrRD_valid, "",stages[2],true,false); // Just to avoid wrapper cry that this signal does not exist. Will be optimized away anyway
			}
			// Instructions that write WrRD but don't write mem
			String addText = "";
			if(this.ContainsOpInStage(BNode.WrRD, 2) || this.ContainsOpInStage(BNode.WrRD, 1)) {
				String textAdd = "rule wrrd_data_stage2; \n"
						+ "stage2.met_vWrRD_validData_1_i("+mkCPU_validDataStage1Expr+");\n"
						+ "endrule\n"; 
				this.toFile.UpdateContent(this.ModFile("mkCPU"),grepDeclareBehav,  new ToWrite(textAdd, false,true,""));
				textAdd = "(*always_enabled*) method Action met_vWrRD_validData_1_i(Bool x);";
				toFile.UpdateContent( this.ModFile("mkCPU_Stage2"),"endinterface" , new ToWrite(textAdd, false,true,"",true));
				textAdd = "method Action met_vWrRD_validData_1_i(Bool x);\n"
						+ "    "+language.CreateLocalNodeName(BNode.WrRD_validData, stages[1], "")+" <= x;\n"
						+ "endmethod\n";
				toFile.UpdateContent( this.ModFile("mkCPU_Stage2"),"endmodule" , new ToWrite(textAdd, false,true,"",true));
				textAdd ="Wire #(Bool) "+language.CreateLocalNodeName(BNode.WrRD_validData, stages[1], "")+" <- mkDWire(False);";
				this.toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), ");",new ToWrite(textAdd, true,false,"module mkCPU_Stage2"));
//				if(!this.ContainsOpInStage(BNode.WrRD, 1)) // if it doesn't contain it to be generated automatically, add it artifically for Data Hazard logic
//					language.UpdateInterface("mkCore", BNode.WrRD_validData, "", stages[1], true, false); // Add validData stage 1 on top interface
//				if(!this.ContainsOpInStage(BNode.WrRD, 2)) // If it does not contain it, add it artificially for top module wrapper
//					language.UpdateInterface("mkCore", BNode.WrRD_validData, "", stages[2], true, false); // Add validData stage 2 on top interface, although not used!! Just for script top module
				
				if(this.ContainsOpInStage(BNode.WrRD, 1)) // Only if we have a WrRD in stage 1 it makes sense to check for WrRD_validData
					addText += " else if ("+language.CreateLocalNodeName(BNode.WrRD_valid, stages[1], "")+" && "+language.CreateLocalNodeName(BNode.WrRD_validData, stages[1], "")+") begin\n"+CreateMemStageFrwrd(true,false, true);
				addText += " else if ("+language.CreateLocalNodeName(BNode.WrRD_valid, stages[1], "")+") begin\n"+CreateMemStageFrwrd(true,false, false);
				//this.toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), "// This stage is just relaying ALU", new ToWrite(addText, false, true , "", true ));			
				this.toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), "return output_stage2;", new ToWrite(addText, false, true , "", true ));
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
		if (noWrRD) {
			//this.toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), "// This stage is just relaying ALU", new ToWrite("else if(rg_stage2.op_stage2 ==  OP_Stage2_ISAX ) begin\n"+CreateMemStageFrwrd(false,false,false),false,true,"", true));
			this.toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), "return output_stage2;", new ToWrite("else if(rg_stage2.op_stage2 ==  OP_Stage2_ISAX ) begin\n"+CreateMemStageFrwrd(false,false,false),false,true,"", true));
		}
	}
	
//	private void addAlwaysEnabledCPUOutput(SCAIEVNode operation, PipelineStage stage, String assignTo) {
//		String methodName = language.CreateMethodName(operation, stage, "");
//		String methodDecl = language.CreateMethodDecl(operation, stage, "", false);
//		this.toFile.UpdateContent(this.ModInterfFile("mkCPU"), "endinterface",new ToWrite(methodDecl+";\n",true,false,"interface",true));
//		this.toFile.UpdateContent(this.ModInterfFile("mkCore"), "endinterface",new ToWrite("(*always_enabled *)"+methodDecl+";\n",true,false,"interface",true));
//		String assignText = methodDecl+";\n"+tab+"return "+assignTo+";\nendmethod";
//		this.toFile.UpdateContent(this.ModFile("mkCPU"), "endmodule",new ToWrite(assignText,true,false,"module mkCPU",true));
//		String methodDeclShort = language.CreateMethodDecl(operation, stage, "", true);
//		this.toFile.UpdateContent(this.ModFile("mkCore"), "endmodule",new ToWrite(methodDeclShort+" = cpu."+methodName+";\n",true,false,"module mkCore",true));
//	}
	
	private void IntegrateISAX_RdStall() {
		for(int stageNum = 0; stageNum <=2; stageNum++) {
			PipelineStage stage = stages[stageNum];
			String localRdStallWire = language.CreateLocalNodeName(BNode.RdStall,stage,"");
			if(stageNum==1) {
				toFile.UpdateContent(this.ModFile("mkCPU"), "end", new ToWrite("else\n"+tab+language.CreateLocalNodeName(BNode.RdStall, stage,"")+" <= True;",true,false,"// Move instruction from Stage"+(stageNum+1)+" to Stage"+(stageNum+2)));
				toFile.UpdateContent(this.ModFile("mkCPU"), "stage3.enq (stage2.out.data_to_stage3);", new ToWrite(language.CreateLocalNodeName(BNode.RdStall, stage,"")+" <= False;",false,true,""));
			} else if(stageNum == 0) {
				toFile.UpdateContent(this.ModFile("mkCPU"), "begin", new ToWrite(language.CreateLocalNodeName(BNode.RdStall, stage,"")+" <= False;",true,false,"// Move instruction from Stage"+(stageNum+1)+" to Stage"+(stageNum+2)));
				toFile.UpdateContent(this.ModFile("mkCPU"), "end", new ToWrite("else\n"+tab+language.CreateLocalNodeName(BNode.RdStall, stage,"")+" <= True;",true,false,"// Move instruction from Stage"+(stageNum+1)+" to Stage"+(stageNum+2)));
			} else if (stageNum == 2) {
				String toAdd = language.CreateLocalNodeName(BNode.RdStall, stages[2], "")+ " <= False;";
				String grep  = "if (stage3.out.ostatus == OSTATUS_PIPE) begin";
				this.toFile.UpdateContent(this.ModFile("mkCPU"), grep,new ToWrite(toAdd, false, true,"", true));
			}
			String declareLocalRdStall = "Wire #( Bool ) "+localRdStallWire+" <- mkDWire( True );\n";
			this.toFile.UpdateContent(this.ModFile("mkCPU"), ");",new ToWrite(declareLocalRdStall, true,false,"module mkCPU"));
//			if(ContainsOpInStage(BNode.RdStall, stage)) {
//				addAlwaysEnabledCPUOutput(BNode.RdStall, stage, localRdStallWire);
//			}
		}
	}
	
	private void IntegrateISAX_WrStall() {
		if(ContainsOpInStage(BNode.WrStall,1))
			toFile.ReplaceContent(this.ModFile("mkCPU"), "if ((! stage3_full)", new ToWrite("if ((! stage3_full) && (stage2.out.ostatus == OSTATUS_PIPE) && (!"+language.CreateLocalNodeName(BNode.WrStall, stages[1],"")+")) begin\n",true,false,"// Move instruction from Stage2 to Stage3"));					
		if(ContainsOpInStage(BNode.WrStall,0))
			toFile.ReplaceContent(this.ModFile("mkCPU"), "if (   (! halting)", new ToWrite("if (   (! halting) && (!"+language.CreateLocalNodeName(BNode.WrStall, stages[0],"")+")",true,false,"// Move instruction from Stage1 to Stage2"));									
		if(ContainsOpInStage(BNode.WrStall,2)) 
			toFile.ReplaceContent(this.ModFile("mkCPU"), "rule rl_pipe",new ToWrite("rule rl_pipe (   (rg_state == CPU_RUNNING) && (!("+language.CreateLocalNodeName(BNode.WrStall, stages[2], "")+"))",false,true,""));
		
	}
	private void IntegrateISAX_WrPC() {
		int nrJumps = 0;
		String updatePC = "";
		String [] instrName = {"stage1.out.data_to_stage2.instr","stage2.out.data_to_stage3.instr","stage2.out.data_to_stage3.instr"};
		for(int stageNum = this.piccolo_core.maxStage; stageNum>=0 ; stageNum--) {
			PipelineStage stage = stages[stageNum];
			if(stageNum==0)
				if(op_stage_instr.containsKey(BNode.WrPC_spawn)) {
					updatePC += "("+language.CreateLocalNodeName(BNode.WrPC_spawn_valid, stages[this.piccolo_core.maxStage+1], "")+") ? ("+language.CreateLocalNodeName(BNode.WrPC_spawn, stages[this.piccolo_core.maxStage+1], "")+") : (";
					nrJumps++;
				}
			
			if(ContainsOpInStage(BNode.WrPC,stageNum)) {
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

	private void IntegrateISAX_RdFlush() {
		if(ContainsOpInStage(BNode.RdFlush, 1)) {
			String localFlushWire = language.CreateLocalNodeName(BNode.RdFlush,stages[1], "");
			// Declare the local wire set as the output value through ConfigPiccolo.
			toFile.UpdateContent(this.ModFile("mkCPU_Stage2"),"(CPU_Stage2_IFC);", new ToWrite("Wire #( Bool ) " + localFlushWire + " <- mkDWire( False );\n", true,false,"module mkCPU_Stage2"));
			
			//Within function fv_out:
			toFile.ReplaceContent(this.ModFile("mkCPU_Stage2"), "function Output_Stage2 fv_out;", new ToWrite("function Tuple2#(Output_Stage2,Bool) fv_out;\n",false,true,""));
			toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), "Output_Stage2 output_stage2 = ?;", new ToWrite("Bool flushing = False;\n",false,true,""));
			// For each conditional 'ostatus = ... ? OSTATUS_NONPIPE : ... ;',
			//  set the flush signal to True whenever ostatus comes out to OSTATUS_NONPIPE.
			// This way, we avoid combinational loops with some RdIValid-based inputs (e.g. RdFlush -> RdIValid -> WrRD_validReq);
			//  none of the combinational dependencies to such inputs set OSTATUS_NONPIPE.
			toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), ";", new ToWrite("flushing = ostatus == OSTATUS_NONPIPE;\n",true,false,"? OSTATUS_NONPIPE"));
			toFile.ReplaceContent(this.ModFile("mkCPU_Stage2"), "return output_stage2;", new ToWrite("return tuple2(output_stage2, flushing);\n",false,true,""));
			
			//Within method Output_Stage2 out, fix the return:
			toFile.ReplaceContent(this.ModFile("mkCPU_Stage2"), "return fv_out;", new ToWrite("return tpl_1(fv_out);\n",false,true,""));

			//Add a new rule directly after fv_out to retrieve the flush condition
			// (effectively calls fv_out a second time, which will make no difference, as fv_out has no side-effects)
			toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), "endfunction", new ToWrite(
				"\n"
				+"rule updateRdFlush;\n"
				+language.tab+localFlushWire+" <= tpl_2(fv_out);\n"
				+"endrule",
				true,false,"return output_stage2;"
			));
		}
	}
	private void IntegrateISAX_FlushStages () {
		// flush stages in case of ISAX jumps or WrFlush
		if( op_stage_instr.containsKey(BNode.WrFlush) ) {
			String  grep = "if (! rg_full) begin";
			String flushCond = "";
			if(ContainsOpInStage(BNode.WrFlush,1)) {
				flushCond += (flushCond.isEmpty() ? "" : " || ") + language.CreateLocalNodeName(BNode.WrFlush,stages[1], "");;
			}
			if (!flushCond.isEmpty()) {
				String validPCFlush = language.CreateLocalNodeName(BNode.WrFlush,stages[1], "");
				//Pass WrFlush from mkCPU to mkCPU_Stage2 (as WrFlush_1).
				//-> Create CPU_Stage2 interface
				language.UpdateInterface("mkCPU_Stage2", BNode.WrFlush, "", stages[1], true, false);
				//-> Call the mkCPU_Stage2 method with the local WrFlush from mkCPU (combining WrFlush from higher stages).
				String flushMethodName = language.CreateMethodName(BNode.WrFlush, stages[1], "");
				toFile.UpdateContent(this.ModFile("mkCPU"), "endrule", new ToWrite(
					"\n"
					+"(* no_implicit_conditions *)\n"
					+"rule assignWrFlush2;\n"
					+language.tab+"stage2."+flushMethodName+"("+flushCond+");\n"
					+"endrule",
					false, true, ""
				));
				
				//Add flush logic in mkCPU_Stage2
				toFile.UpdateContent(this.ModFile("mkCPU_Stage2"), grep,new ToWrite("if("+validPCFlush+") begin \n "+ CreateMemStageFrwrd(false,true, false),false,true,"",true));
			}
			if(ContainsOpInStage(BNode.WrFlush,0)) {
				String validPCFlush = language.CreateLocalNodeName(BNode.WrFlush,stages[0], "");
				flushCond += (flushCond.isEmpty() ? "" : " || ") + validPCFlush;
			}
			if (!flushCond.isEmpty()) {
				String addText = "if ("+flushCond+") begin \n"
						+ "output_stage1.ostatus = OSTATUS_EMPTY;\n"
						+ "	end else \n";
				toFile.UpdateContent(this.ModFile("mkCPU_Stage1"),grep,new ToWrite(addText,false,true,"",true));		
			}
		}
	}
	
	private void IntegrateISAX_SpawnRD() {
		int spawnStageNum = this.piccolo_core.maxStage+1;
		PipelineStage spawnStage = stages[spawnStageNum];
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
		int stageNum = this.piccolo_core.GetNodes().get(BNode.RdMem).GetEarliest().asInt();
		PipelineStage stage = stages[stageNum];
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
					+ (ContainsOpInStage(BNode.RdFlush, 1) ? "	 flushing = ostatus == OSTATUS_NONPIPE;\n" : "")
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
		int spawnStageNum = this.piccolo_core.maxStage +1;
		PipelineStage spawnStage = stages[spawnStageNum];
		String commit_stage = "stage2";
		
		if(op_stage_instr.containsKey(BNode.RdMem_spawn) || op_stage_instr.containsKey(BNode.WrMem_spawn)) {
			// RdAddr node - requried when user does not provide addr signal 
			String rdAddrLogic = language.CreateLocalNodeName(BNode.RdMem_spawn_defaultAddr, spawnStage, "") + " := x.addr;\n";
			this.toFile.UpdateContent(this.ModFile("mkCPU_Stage2"),"let funct3 = instr_funct3 (x.instr);", new ToWrite(rdAddrLogic,false,true,"")); //TODO Test

			
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

	private boolean ContainsOpInStage(SCAIEVNode op, int stage) {
		return ContainsOpInStage(op, stages[stage]);
	}
	private boolean ContainsOpInStage(SCAIEVNode op, PipelineStage stage) {
		return op_stage_instr.containsKey(op) && op_stage_instr.get(op).containsKey(stage);
	}
	
	private void ConfigPiccolo() {
	 	this.PopulateNodesMap();
	 	
	 	PutModule(this.pathPiccolo+"Core/Core_IFC.bsv",  "Core_IFC", this.pathPiccolo+"Core/Core.bsv", "", "mkCore");
	 	PutModule(this.pathPiccolo+"CPU/CPU_IFC.bsv",  "CPU_IFC", this.pathPiccolo+"CPU/CPU.bsv", "mkCore", "mkCPU");
	 	PutModule(this.pathPiccolo+"CPU/CPU_Stage1.bsv",  "CPU_Stage1_IFC", this.pathPiccolo+"CPU/CPU_Stage1.bsv", "mkCPU", "mkCPU_Stage1");
	 	PutModule(this.pathPiccolo+"CPU/CPU_Stage2.bsv",  "CPU_Stage2_IFC", this.pathPiccolo+"CPU/CPU_Stage2.bsv", "mkCPU", "mkCPU_Stage2");
	 	PutModule(this.pathPiccolo+"CPU/CPU_Stage3.bsv",  "CPU_Stage3_IFC", this.pathPiccolo+"CPU/CPU_Stage3.bsv", "mkCPU", "mkCPU_Stage3");
	 	PutModule("",  "", this.pathPiccolo+"CPU/EX_ALU_functions.bsv", "", "fv_ALU");
	 	PutModule("",  "", this.pathPiccolo+"CPU/CPU_Globals.bsv", "", "CPU_Globals");	
		for (Module module : fileHierarchy.values()) {
			if (!module.interfaceFile.isEmpty())
				toFile.AddFile(module.interfaceFile, false);
			toFile.AddFile(module.file, false);
		}
	 	
	 	int spawnStage = this.piccolo_core.maxStage+1;
	 	
		
	 	this.PutNode("Bit", "", "mkCPU_Stage1", BNode.WrPC,stages[0]);
	 	this.PutNode("Bool", "",  "mkCPU_Stage1", BNode.WrPC_valid,stages[0]);
	 	this.PutNode("Bit", "",  "mkCPU_Stage1", BNode.WrPC,stages[1]);
	 	this.PutNode("Bool", "",  "mkCPU_Stage1", BNode.WrPC_valid,stages[1]);
	 	this.PutNode("Bit", "",  "mkCPU_Stage1", BNode.WrPC,stages[2]);
	 	this.PutNode("Bool", "",  "mkCPU_Stage1", BNode.WrPC_valid,stages[2]);
	 	this.PutNode("Bit", "", "mkCPU_Stage1", BNode.WrPC_spawn,stages[3]);
	 	this.PutNode("Bool", "",  "mkCPU_Stage1", BNode.WrPC_spawn_valid,stages[3]);

	 	this.PutNode("Bit", "pc", "mkCPU_Stage1", BNode.RdPC,stages[0]);
	 	this.PutNode("Bit", "rg_stage2.pc", "mkCPU_Stage2", BNode.RdPC,stages[1]);
	 	this.PutNode("Bit", "rg_stage3.pc", "mkCPU_Stage3", BNode.RdPC,stages[2]);


	 	this.PutNode("Bit", "stage1.out.data_to_stage2.instr", "mkCPU", BNode.RdInstr,stages[0]);
	 	this.PutNode("Bit", "stage2.out.data_to_stage3.instr", "mkCPU", BNode.RdInstr,stages[1]);
	 	this.PutNode("Bit", "rg_stage3.instr", "mkCPU_Stage3", BNode.RdInstr,stages[2]);

	 	this.PutNode("Bit", "rs1_val_bypassed", "mkCPU_Stage1", BNode.RdRS1,stages[0]);		

	 	this.PutNode("Bit", "rs2_val_bypassed", "mkCPU_Stage1", BNode.RdRS2,stages[0]);

	 	this.PutNode("Bit", "", "mkCPU_Stage2", BNode.WrRD,stages[1]);
	 	this.PutNode("Bool", "", "mkCPU_Stage2", BNode.WrRD_valid,stages[1]);
	 	this.PutNode("Bool", "", "mkCPU", BNode.WrRD_validData,stages[1]);

	 	this.PutNode("Bit", "", "mkCPU_Stage3", BNode.WrRD,stages[2]);
	 	this.PutNode("Bool", "", "mkCPU_Stage3", BNode.WrRD_valid,stages[2]);
	 	this.PutNode("Bool", "", "mkCPU_Stage3", BNode.WrRD_validData,stages[2]);


	 	this.PutNode("Bool", "", "mkCPU", BNode.RdIValid,stages[0]);	 		
	 	this.PutNode("Bool", "", "mkCPU", BNode.RdIValid,stages[1]);	 		
	 	this.PutNode("Bool", "", "mkCPU_Stage3", BNode.RdIValid,stages[2]);	 		

	 	int stageMem = this.piccolo_core.GetNodes().get(BNode.RdMem).GetEarliest().asInt();
	 	//this.PutNode("Bit", "truncate( near_mem.dmem.word64)", "mkCPU", BNode.RdMem,stages[stageMem]);
	 	this.PutNode("Bit", "truncate(dcache.word64)", "mkCPU_Stage2", BNode.RdMem,stages[stageMem]);
	 	this.PutNode("Bool", "", "mkCPU_Stage2", BNode.RdMem_validReq,stages[stageMem]);
	 	this.PutNode("Bit", "", "mkCPU_Stage2", BNode.WrMem,stages[stageMem]);
	 	this.PutNode("Bool",  "","mkCPU_Stage2", BNode.WrMem_validReq,stages[stageMem]);
	 	this.PutNode("Bit", "", "mkCPU_Stage2",BNode.RdMem_addr,stages[stageMem]);
	 	this.PutNode("Bit", "", "mkCPU_Stage2",BNode.WrMem_addr,stages[stageMem]);
	 	this.PutNode("Bit", "", "mkCPU_Stage2",BNode.RdMem_size,stages[stageMem]);
	 	this.PutNode("Bit", "", "mkCPU_Stage2",BNode.WrMem_size,stages[stageMem]);
	 	this.PutNode("Bool", "", "mkCPU_Stage2",BNode.RdMem_addr_valid,stages[stageMem]);
	 	this.PutNode("Bool", "", "mkCPU_Stage2",BNode.WrMem_addr_valid,stages[stageMem]);

	 	this.PutNode("Bool", language.CreateLocalNodeName(BNode.RdStall,stages[0],""), "mkCPU", BNode.RdStall,stages[0]);
	 	this.PutNode("Bool", language.CreateLocalNodeName(BNode.RdStall,stages[1],""), "mkCPU", BNode.RdStall,stages[1]);
	 	this.PutNode("Bool", language.CreateLocalNodeName(BNode.RdStall,stages[2],""), "mkCPU", BNode.RdStall,stages[2]);

	 	this.PutNode("Bool", "stage1.out.ostatus != OSTATUS_EMPTY", "mkCPU", BNode.RdInStageValid,stages[0]);
	 	this.PutNode("Bool", "stage2.out.ostatus != OSTATUS_EMPTY", "mkCPU", BNode.RdInStageValid,stages[1]);
	 	this.PutNode("Bool", "stage3.out.ostatus != OSTATUS_EMPTY", "mkCPU", BNode.RdInStageValid,stages[2]);

	 	this.PutNode("Bool", "", "mkCPU", BNode.WrStall,stages[0]);
	 	this.PutNode("Bool", "", "mkCPU", BNode.WrStall,stages[1]);
	 	this.PutNode("Bool", "", "mkCPU", BNode.WrStall,stages[2]);


	 	this.PutNode("Bool", "", "mkCPU_Stage1", BNode.WrFlush,stages[0]);
	 	//this.PutNode("Bool", "", "mkCPU_Stage2", BNode.WrFlush,stages[1]);
	 	this.PutNode("Bool", "", "mkCPU", BNode.WrFlush,stages[1]);
	 	this.PutNode("Bool", "", "mkCPU_Stage3", BNode.WrFlush,stages[2]);

	 	//-> OSTATUS_NONPIPE only set during exceptions
	 	String wrFlush1Cond = ContainsOpInStage(BNode.WrFlush, 1) ? (" || "+language.CreateLocalNodeName(BNode.WrFlush, stages[1], "")) : "";
	 	this.PutNode("Bool", "(stage1.out.ostatus == OSTATUS_NONPIPE && stage1.out.control == CONTROL_TRAP)" + wrFlush1Cond, "mkCPU", BNode.RdFlush,stages[0]);
	 	//this.PutNode("Bool", "stage2.out.ostatus == OSTATUS_NONPIPE", "mkCPU", BNode.RdFlush,stages[1]);
	 	this.PutNode("Bool", language.CreateLocalNodeName(BNode.RdFlush, stages[1], ""), "mkCPU_Stage2", BNode.RdFlush,stages[1]);
	 	this.PutNode("Bool", "False", "mkCPU", BNode.RdFlush,stages[2]);


	 	this.PutNode("Bit", "", "mkCPU_Stage3", BNode.WrRD_spawn,stages[spawnStage]);
	 	this.PutNode("Bool", "", "mkCPU_Stage3", BNode.WrRD_spawn_valid,stages[spawnStage]);
	 	this.PutNode("Bit", "", "mkCPU_Stage3", BNode.WrRD_spawn_addr,stages[spawnStage]);
	 	this.PutNode("Bool", "True", "mkCPU_Stage3", BNode.WrRD_spawn_validResp,stages[spawnStage]);

	 	this.PutNode( "Bit", "truncate( near_mem.dmem.word64)", "mkCPU", BNode.RdMem_spawn,stages[spawnStage]);
	 	this.PutNode("Bool","","mkCPU", BNode.RdMem_spawn_validReq,stages[spawnStage]);
	 	this.PutNode( "Bool", "near_mem.dmem.valid && isax_memONgoing_wire", "mkCPU", BNode.RdMem_spawn_validResp,stages[spawnStage]);
	 	this.PutNode("Bit", "","mkCPU", BNode.RdMem_spawn_addr,stages[spawnStage]);	
	 	this.PutNode("Bit", "","mkCPU", BNode.RdMem_spawn_size,stages[spawnStage]);
	 	this.PutNode("Bit", "","mkCPU_Stage2", BNode.RdMem_spawn_defaultAddr,stages[spawnStage]);
	 	this.PutNode( "Bool", "", "mkCPU", BNode.RdMem_spawn_write,stages[spawnStage]);

	 	this.PutNode("Bit", "", "mkCPU", BNode.WrMem_spawn,stages[spawnStage]);
	 	this.PutNode("Bool","","mkCPU",BNode.WrMem_spawn_validReq,stages[spawnStage]);
	 	this.PutNode( "Bool", "near_mem.dmem.valid && isax_memONgoing_wire", "mkCPU", BNode.WrMem_spawn_validResp,stages[spawnStage]);
	 	this.PutNode( "Bool", "", "mkCPU", BNode.WrMem_spawn_write,stages[spawnStage]);	
	 	this.PutNode("Bit", "","mkCPU", BNode.WrMem_spawn_addr,stages[spawnStage]);	
	 	this.PutNode("Bit", "","mkCPU", BNode.WrMem_spawn_size,stages[spawnStage]);
	 	this.PutNode("Bit", "","mkCPU_Stage2", BNode.WrMem_spawn_defaultAddr,stages[spawnStage]); // x.addr

	 	this.PutNode( "Bool", "stage3.out.ostatus == OSTATUS_PIPE","mkCPU", BNode.ISAX_spawnAllowed,stages[0]);
	 	

	}
	
}
