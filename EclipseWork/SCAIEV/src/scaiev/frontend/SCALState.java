package scaiev.frontend;

import java.util.HashMap;
import java.util.HashSet;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.util.Verilog;

public class SCALState {

private String clk = "clk_i";
private String reset = "rst_i"; 
public  String tab = "  ";	
public BNode allBNodes;
private HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr;
private HashMap <String,SCAIEVInstr> ISAXes;
private Verilog myLanguage;
private Core core;
public String myModuleName = "InternalRegs";
public boolean hasPrivateRegs = false;
boolean noDH = false;

private HashMap<SCAIEVNode, HashSet<Integer>> mapInterface = new HashMap<SCAIEVNode,  HashSet<Integer>>() ;
private HashSet<String> textInterface = new  HashSet<String>();

	public  SCALState (BNode allBNodes, HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr, HashMap <String,SCAIEVInstr> ISAXes, Core core) {
		this.allBNodes = allBNodes;
		this.op_stage_instr = op_stage_instr;
		this.ISAXes = ISAXes;
		myLanguage = new Verilog(allBNodes); 
		this.core = core;
		for(SCAIEVNode node : op_stage_instr.keySet())
			if(allBNodes.IsUserBNode(node))
				hasPrivateRegs = true;
	}
	
	// Infos for SCAL
	public HashMap<SCAIEVNode, Integer> PrepareEarliest() {
		HashMap<SCAIEVNode, Integer> node_stageValid = new HashMap<SCAIEVNode, Integer>();
		for(SCAIEVNode node : op_stage_instr.keySet()) 
			if(allBNodes.IsUserBNode(node) && node.isInput && !node.isSpawn()) {
				SCAIEVNode RdNode = allBNodes.GetSCAIEVNode(allBNodes.GetNameRdNode(node));
				System.out.println(" RdNode = "+RdNode);
				System.out.println(" this.core.GetNodes().get(RdNode).GetEarliest() = "+this.core.GetNodes().get(RdNode).GetEarliest());
				node_stageValid.put(node,this.core.GetNodes().get(RdNode).GetEarliest()); // we need starting from read stage for DH
			}
		return node_stageValid;		
	};
		

	public  HashMap<SCAIEVNode, HashSet<Integer>> GetInstantiation () {
		return mapInterface;
	}
	
	public String GetInstantiationText () {
		String textInst = "";
		
		for(SCAIEVNode node : mapInterface.keySet())
			for(int stage : mapInterface.get(node)) {
				String signalSCALInst;
				if(node.equals(allBNodes.RdInstr))
					textInst += ",\n." + myLanguage.CreateNodeName(node.NodeNegInput(), stage, "");
				else 
					textInst += ",\n." + myLanguage.CreateNodeName(node, stage, "");
				if(node.equals(allBNodes.RdStall))
					signalSCALInst = myLanguage.CreateLocalNodeName(allBNodes.WrStall, stage, "")+"_"+myModuleName;
				else if(node.equals(allBNodes.WrStall))
					signalSCALInst =  myLanguage.CreateNodeName(allBNodes.RdStall.NodeNegInput(), stage, "");
				else //if(!node.equals(allBNodes.RdInstr))
					signalSCALInst = myLanguage.CreateNodeName(node.NodeNegInput(), stage, "");
			//	else
			//		signalSCALInst = myLanguage.CreateNodeName(node, stage, "");
				textInst += "("+signalSCALInst+")";
			}
		if(!textInst.isEmpty())
			return  myModuleName + " "+myModuleName+"_inst (\n"
									+"."+myLanguage.clk+"("+myLanguage.clk+"),\n"
									+"."+myLanguage.reset+"("+myLanguage.reset+")"
									+ textInst+" \n);\n"; 
		else 
			return null;
	}
	
	private void AddToInterface(SCAIEVNode node, int stage) {
		HashSet<Integer> mySet =  new HashSet<Integer>();
		if(node==null) {
			System.out.println("ERROR. SCALState. Node in addToInterf is null. Exit.");
			System.exit(1);
		}
		if(mapInterface.containsKey(node))
			mySet = mapInterface.get(node);
		mySet.add(stage);
		mapInterface.put(node, mySet);
		
	}
	
	public String GetNodeInstName(SCAIEVNode node , int stage) {
		String nodeName  = myLanguage.CreateNodeName(node.NodeNegInput(), stage, "") ; 
		if(node.equals(allBNodes.RdStall))
			nodeName = myLanguage.CreateLocalNodeName(allBNodes.WrStall, stage, "")+"_"+myModuleName;
		if(node.equals(allBNodes.WrStall))
			nodeName = myLanguage.CreateNodeName(allBNodes.RdStall, stage, "");
		//if(node.equals(allBNodes.RdInstr))
		//	nodeName = myLanguage.CreateNodeName(allBNodes.RdInstr, stage, "");
		return nodeName;
	}
	
	public SCAIEVNode GetNodeInst(SCAIEVNode node) {
		SCAIEVNode returnNode  =node.NodeNegInput() ; 
		if(node.equals(allBNodes.RdStall))
			returnNode = null;
		if(node.equals(allBNodes.WrStall))
			returnNode =allBNodes.RdStall;
		if(node.equals(allBNodes.RdInstr))
			returnNode =allBNodes.RdInstr;
		return returnNode;
	}
	
	public String InstAllRegs() {
		String moduleText = "";
		String interfText = "";
		HashMap<Integer, String> stage_stalltext = new HashMap<Integer, String>();
		// Any user nodes? 
		boolean userNodePresent = false;
		boolean first = true; 
		for(SCAIEVNode node: op_stage_instr.keySet()) {

			// We found a user node 
			if(allBNodes.IsUserBNode(node) && !node.isInput) { // generate logic once (for exp when we find the rd node)
				userNodePresent = true;
				
				SCAIEVNode WrNode = allBNodes.GetSCAIEVNode(allBNodes.GetNameWrNode(node));
				int earliest = this.core.GetNodes().get(node).GetEarliest();
				
				int writebackStage = this.core.GetNodes().get(WrNode).GetExpensive()-1; //spawn -1. Write nodes that support spawn don.t have latest	
				
				int lastRdStage = -1;
				for (int stage : this.op_stage_instr.get(node).keySet()) {
					if (lastRdStage<stage)
						lastRdStage = stage;
				}
				
				boolean justSpawn = false; 
				if(!op_stage_instr.containsKey(WrNode))
					justSpawn = true;
				
				// Module logic
				String[] moduleinterflogic =  ModuleLogic (node, earliest, writebackStage, lastRdStage,first, justSpawn);
				moduleText += moduleinterflogic[1];
				interfText += moduleinterflogic[0];
				for(int stage = earliest; stage <=lastRdStage; stage++) {
					String current = ""; 
					if(stage_stalltext.containsKey(stage)) current = stage_stalltext.get(stage)+ " || "; 
					current += BNode.RdStall.name+node+"_"+stage+"_o";
					stage_stalltext.put(stage, current);
				}
				first = false;
				
				// Interfaces needed in SCAL 
				// input ["+regW+":0] "+WrNode+"_"+stage+"_i=0
				// input  "+WrNode_validReq+"_"+stage+"_i
				// input "+WrNode_validData+"_"+stage+"_i
				// input  "+RdNode_validReq+"_"+stage+"_i 							// For DH. Not required for WB stage
				// output reg ["+regW+" -1 : 0] "+RdNode+"_"+stage+"_o			
				// input ["+addrW+" -1 : 0] "+WrNode_spawn_addr+"_"+spawnstage+"_i
				// input ["+regW+" -1 : 0] "+WrNode_spawn+"_"+spawnstage+"_i
				// input  "+WrNode_spawn_validReq+"_"+spawnstage+"_i
				// input [32 -1 : 0] "+RdInstr+"_"+stage+"_i						// For DH and dest/source addr
				// input  "+WrStall+"_"+stage+"_i									// To update internal regs in sync
				// output  "+RdStall+"_"+stage+"_o									// For DH
				
				// Read interf
				for (int stage : this.op_stage_instr.get(node).keySet()) {
					AddToInterface(node,stage); 
					AddToInterface(allBNodes.RdInstr,stage);
					if(stage <writebackStage) // for DH 
						AddToInterface(allBNodes.GetAdjSCAIEVNode(node, AdjacentNode.validReq),stage); 
				}
				
				// DH between read and write
				if(op_stage_instr.containsKey(WrNode)) { // could also contain just spawn
					for(int stage = earliest; stage <= lastRdStage; stage ++) 
						if(stage<this.core.GetSpawnStage()) 
							AddToInterface(allBNodes.RdStall,stage);// stall to create read reg 
					
					for(int stage = earliest; stage <= writebackStage; stage ++) {
						if(stage<this.core.GetSpawnStage()) {
							AddToInterface(allBNodes.RdInstr,stage);
							AddToInterface(allBNodes.WrStall,stage);// for DH and for regf update in wb stage
							AddToInterface(allBNodes.GetAdjSCAIEVNode(WrNode, AdjacentNode.validReq),stage); 
							AddToInterface(allBNodes.GetAdjSCAIEVNode(WrNode, AdjacentNode.validData),stage); 
						}
					}
				}
				
				// Write interf
				if(this.op_stage_instr.containsKey(WrNode)) // maybe it has just spwn
					for (int stage : this.op_stage_instr.get(WrNode).keySet()) {
						AddToInterface(WrNode,stage); 
					}
				
				// Spawn interface 
				SCAIEVNode spawnNode = allBNodes.GetMySpawnNode(WrNode);
				if(op_stage_instr.containsKey(spawnNode)) {
					AddToInterface(spawnNode,this.core.GetSpawnStage());
					AddToInterface(allBNodes.GetAdjSCAIEVNode(spawnNode, AdjacentNode.validReq),this.core.GetSpawnStage());
					AddToInterface(allBNodes.GetAdjSCAIEVNode(spawnNode, AdjacentNode.addr),this.core.GetSpawnStage());
				}
				// Spawn also has addr
				/* I don't remember what the code below was for...
				SCAIEVNode spawnNode = allBNodes.GetMySpawnNode(WrNode);
				if(op_stage_instr.containsKey(spawnNode))
					for (int stage : this.op_stage_instr.get(spawnNode).keySet()) {
						if(stage>=this.core.GetSpawnStage()) {
							AddToInterface(allBNodes.GetAdjSCAIEVNode(node, AdjacentNode.addr),stage); 
						}
					}
				*/
				System.out.println(this.mapInterface);
				
			}
		}
			
		//Create the stalling signal from all internal regs DH 
		for(int stage: stage_stalltext.keySet()) 
			moduleText += "assign "+ myLanguage.CreateNodeName(BNode.RdStall, stage, "") + " = "+stage_stalltext.get(stage)+";\n";
		
			
		
		// Any user nodes so that we return entire module?
		if(userNodePresent)
			return "module "+myModuleName +"(\n"
					+interfText
					+"input "+myLanguage.clk+",\n"
					+"input "+myLanguage.reset+");\n"
					+ moduleText
					+ "\n"
					+ "endmodule\n"; 
		else 
			return "";
	}
	
	
	private String[] ModuleLogic (SCAIEVNode RdNode, int firststage, int writebackstage, int lastread, boolean commonsigs, boolean justSpawn) {
		SCAIEVNode WrNode = allBNodes.GetSCAIEVNode(allBNodes.GetNameWrNode(RdNode));
		String RdNode_validReq = allBNodes.GetAdjSCAIEVNode(RdNode, AdjacentNode.validReq).name;
		String WrNode_validReq = allBNodes.GetAdjSCAIEVNode(WrNode, AdjacentNode.validReq).name;
		String WrNode_validData = allBNodes.GetAdjSCAIEVNode(WrNode, AdjacentNode.validData).name;	
		SCAIEVNode WrNode_spawn_node = allBNodes.GetMySpawnNode(WrNode);
		String WrNode_spawn = WrNode_spawn_node.name;
		
		String WrNode_spawn_addr = "noaddrsig"+WrNode;
		if(WrNode.elements>1) 
			WrNode_spawn_addr = allBNodes.GetAdjSCAIEVNode(WrNode_spawn_node, AdjacentNode.addr).name;
		String WrNode_spawn_validReq = allBNodes.GetAdjSCAIEVNode(WrNode_spawn_node, AdjacentNode.validReq).name;
		
		String RdStall = BNode.RdStall.name;
		String WrStall = BNode.WrStall.name;
		String RdInstr = BNode.RdInstr.name;
		
		int regW = WrNode.size;
		int addrW = 1; 
		if(WrNode.elements>1) 
			addrW = allBNodes.GetAdjSCAIEVNode(WrNode, AdjacentNode.addr).size;
				
		int secondstage = firststage+1;
		int thirdstage = firststage+2;
		int spawn = this.core.GetSpawnStage();
		
		String WRSECOND = "WRSECOND"+WrNode;
		String WRTHIRD = "WRTHIRD"+WrNode;
		String RDSECOND = "RDSECOND"+RdNode;
		String RDTHIRD = "RDTHIRD"+RdNode;
		String MULTIPLEREGS="MULTIPLEREGS"+RdNode;

		String WRSECONDDEF = "";
		if(writebackstage>firststage && !justSpawn)
			WRSECONDDEF = "`define "+WRSECOND;
		String WRTHIRDDEF = "";
		if(writebackstage>secondstage && !justSpawn)
			WRTHIRDDEF = "`define "+WRTHIRD;
		String RDSECONDDEF ="";
		if(lastread>firststage)	
			RDSECONDDEF ="`define "+RDSECOND;
		String RDTHIRDDEF="";
		if(lastread>secondstage)	
			RDTHIRDDEF ="`define "+RDTHIRD;
		String MULTIPLEREGSDEF = "";
		if(WrNode.elements>1)
			MULTIPLEREGSDEF="`define "+MULTIPLEREGS;
		String interf = "    input  "+RdNode_validReq+"_"+firststage+"_i,\n"
				+ "	   input ["+regW+"-1:0] "+WrNode+"_"+firststage+"_i=0,\n"
				+ "	   input  "+WrNode_validReq+"_"+firststage+"_i=0,\n"
				+ "	   input "+WrNode_validData+"_"+firststage+"_i=0,\n"
				+ "    output reg ["+regW+" -1 : 0] "+RdNode+"_"+firststage+"_o,\n"
				+ "	\n"
				+ "	   input ["+regW+"-1:0] "+WrNode+"_"+secondstage+"_i=0,\n"
				+ "    input  "+WrNode_validReq+"_"+secondstage+"_i,\n"
				+ "	   input "+WrNode_validData+"_"+secondstage+"_i,\n"
				+ "    input  "+RdNode_validReq+"_"+secondstage+"_i=0,\n"
				+ "	   output reg ["+regW+" -1 : 0] "+RdNode+"_"+secondstage+"_o,\n"			
				+ "	 \n"
				+ "    input ["+regW+"-1:0] "+WrNode+"_"+thirdstage+"_i=0,\n"
				+ "    input  "+WrNode_validReq+"_"+thirdstage+"_i,\n"
				+ "	   input "+WrNode_validData+"_"+thirdstage+"_i,\n"
				+ "	   output reg ["+regW+" -1 : 0] "+RdNode+"_"+thirdstage+"_o,\n"
				+ "	\n"
				+ "	   input ["+addrW+" -1 : 0] "+WrNode_spawn_addr+"_"+spawn+"_i,\n"
				+ "	   input ["+regW+" -1 : 0] "+WrNode_spawn+"_"+spawn+"_i,\n"
				+ "	   input  "+WrNode_spawn_validReq+"_"+spawn+"_i=0,\n"
				+ "	\n";
	if(commonsigs)
		interf += "	   input [32 -1 : 0] "+RdInstr+"_"+thirdstage+"_i, \n"
				+ "    input [32 -1 : 0] "+RdInstr+"_"+secondstage+"_i, \n"
				+ "	   input [32 -1 : 0] "+RdInstr+"_"+firststage+"_i,	\n"
				+ "	\n"
				+ "	\n"
				+ "	   input  "+WrStall+"_"+firststage+"_i,\n"
				+ "    input  "+WrStall+"_"+secondstage+"_i,\n"
				+ "    input  "+WrStall+"_"+thirdstage+"_i, \n"
				+ "	\n"
				+ "    output  "+RdStall+"_"+firststage+"_o,\n"
				+ "    output  "+RdStall+"_"+secondstage+"_o,\n"
				+ "    output  "+RdStall+"_"+thirdstage+"_o,\n";
		String module  = ""
				+ WRSECONDDEF+"\n"
				+ WRTHIRDDEF+"\n"
				+ RDSECONDDEF+"\n"
				+ RDTHIRDDEF+"\n"
				+ MULTIPLEREGSDEF+"\n"
				+ "// DRC \n"
				+ "always @(*) begin \n"
				+ "if ("+RdNode_validReq+"_"+firststage+"_i === 1'bz) begin\n"
				+ "  $display(\"Signal Rd Intenal Reg valid request not connected\");\n"
				+ "end\n"
				+ "if ("+WrStall+"_"+firststage+"_i === 1'bz) begin\n"
				+ "  $display(\"Signal WrStall in "+firststage+"not connected\");\n"
				+ "end\n"
				+ "if ("+WrNode_validReq+"_"+firststage+"_i === 1'bz) begin\n"
				+ "  $display(\"Signal Wr Intenal Reg valid request not connected\");\n"
				+ "end\n"
				+ "\n"
				+ "`ifdef "+WRSECOND+"\n"
				+ "	if ("+WrNode_validReq+"_"+secondstage+"_i === 1'bz) begin\n"
				+ "	  $display(\"Signal Wr Intenal Reg valid request not connected\");\n"
				+ "	end\n"
				+ "	if ("+WrNode_validData+"_"+secondstage+"_i === 1'bz) begin\n"
				+ "	  $display(\"Signal Wr Intenal Reg valid data request not connected\");\n"
				+ "	end\n"
				+ "`endif\n"
				+ "\n"
				+ "`ifdef "+WRTHIRD+"\n"
				+ "	if ("+WrNode_validReq+"_"+thirdstage+"_i === 1'bz) begin\n"
				+ "	  $display(\"Signal Wr Intenal Reg valid request in stage "+thirdstage+" not connected\");\n"
				+ "	end\n"
				+ "	if ("+WrNode_validData+"_"+thirdstage+"_i === 1'bz) begin\n"
				+ "	  $display(\"Signal Wr Intenal Reg valid request in stage "+thirdstage+" not connected\");\n"
				+ "	end\n"
				+ " if("+WrStall+"_"+thirdstage+"_i === 1'bz) begin \n"
				+ "   $display(\"Signal Wr Stall in stage "+thirdstage+" not connected\");\n"
				+ " end\n"
				+ "`endif\n"
				+ "end\n"
				+ "\n"
				+ "`ifdef "+RDSECOND+"\n"
				+ "	parameter RDSEC"+RdNode+" = 1; \n"
				+ "`else \n"
				+ "	parameter RDSEC"+RdNode+" = 0; \n"
				+ "`endif\n"
				+ "\n"
				+ "\n"
				+ "// Valid payload available logic (validData)\n"
				+ "reg "+WrNode_validData+"_"+firststage+"_reg = 0;\n"
				+ "wire "+WrNode_validData+"_"+firststage+"_s; \n"
				+ "reg "+WrNode_validData+"_"+secondstage+"_reg;\n"
				+ "wire "+WrNode_validData+"_"+secondstage+"_s; \n"
				+ "reg "+WrNode_validData+"_"+thirdstage+"_reg;\n"
				+ "wire "+WrNode_validData+"_"+thirdstage+"_s;\n"
				+ "assign  "+WrNode_validData+"_"+firststage+"_s =  "+WrNode_validData+"_"+firststage+"_i;\n"
				+ "assign "+WrNode_validData+"_"+secondstage+"_s = "+WrNode_validData+"_"+secondstage+"_reg || "+WrNode_validData+"_"+secondstage+"_i;\n"
				+ "assign "+WrNode_validData+"_"+thirdstage+"_s = "+WrNode_validData+"_"+thirdstage+"_reg || "+WrNode_validData+"_"+thirdstage+"_i;\n"
				+ "`ifdef "+WRSECOND+"\n"
				+ "	always @(posedge clk_i) begin \n"
				+ "		if(rst_i)\n"
				+ "			"+WrNode_validData+"_"+secondstage+"_reg <= 1'b0;\n"
				+ "		else if(!"+WrStall+"_"+firststage+"_i)\n"
				+ "			"+WrNode_validData+"_"+secondstage+"_reg <="+ WrNode_validData+"_"+firststage+"_s ; // no flush needed, it does not update regf\n"
				+ "	end\n"
				+ "`else \n"
				+ "	always @(*) \n"
				+ "		"+WrNode_validData+"_"+secondstage+"_reg = 0;\n"
				+ "`endif\n"
				+ "\n"
				+ "`ifdef "+WRTHIRD+"\n"
				+ "	always @(posedge clk_i) begin \n"
				+ "		if(rst_i)\n"
				+ "			"+WrNode_validData+"_"+thirdstage+"_reg <= 1'b0;\n"
				+ "		else if(!"+WrStall+"_"+secondstage+"_i)\n"
				+ "			"+WrNode_validData+"_"+thirdstage+"_reg <= "+WrNode_validData+"_"+secondstage+"_s ; // no flush needed, it does not update regf\n"
				+ "	end\n"
				+ "`else \n"
				+ "	always @(*) \n"
				+ "		"+WrNode_validData+"_"+thirdstage+"_reg = 0;\n"
				+ "`endif\n"
				+ "\n"				
				+ "// Regfile \n"				
				+ "wire ["+regW+"-1: 0]"+  WrNode+"_"+writebackstage+"_s;\n"
				+ "`ifdef "+MULTIPLEREGS+"\n"
				+ "reg ["+regW+"-1: 0] "+WrNode+"_"+(writebackstage+1)+"_reg [(1<<"+addrW+") -1:0];\n"
				+ "always@(posedge clk_i) begin\n"
				+ "    if (rst_i) begin \n"
				+ "        for (int i = 0 ; i< (1 << "+addrW+") ; i= i+1 )\n"
				+ "            "+WrNode+"_"+(writebackstage+1)+"_reg[i] <= '0;\n"
				+ "    end else if (!(("+WrStall+"_"+writebackstage+"_i || !"+WrNode_validReq+"_"+writebackstage+"_i) && !"+WrNode_spawn_validReq+"_"+spawn+"_i))\n"
				+ "        "+WrNode+"_"+(writebackstage+1)+"_reg["+WrNode_spawn_validReq+"_"+spawn+"_i ? "+WrNode_spawn_addr+"_"+spawn+"_i : "+RdInstr+"_"+writebackstage+"_i[8+"+addrW+"-1:8]] <= "+WrNode_spawn_validReq+"_"+spawn+"_i ? "+WrNode_spawn+"_"+spawn+"_i : "+WrNode+"_"+writebackstage+"_s;\n"
				+ "end;\n"
				+ "`else\n"
				+ "reg ["+regW+"-1: 0] "+WrNode+"_"+(writebackstage+1)+"_reg;\n"
				+ "always@(posedge clk_i) begin\n"
				+ "    if (rst_i) begin \n"
				+ "        "+WrNode+"_"+(writebackstage+1)+"_reg <= '0;\n"
				+ "    end else if (!(("+WrStall+"_"+writebackstage+"_i || !"+WrNode_validReq+"_"+writebackstage+"_i) && !"+WrNode_spawn_validReq+"_"+spawn+"_i))\n"
				+ "        "+WrNode+"_"+(writebackstage+1)+"_reg <= "+WrNode_spawn_validReq+"_"+spawn+"_i ? "+WrNode_spawn+"_"+spawn+"_i : "+WrNode+"_"+writebackstage+"_s;\n"
				+ "end;\n"
				+ "`endif"
				+ " \n"
				+ "// Pipeline results \n"
				+ "reg ["+regW+"-1: 0]  "+WrNode+"_"+firststage+"_reg;\n"
				+ "always @(*)\n"
				+ "	 "+WrNode+"_"+firststage+"_reg = 0;\n"
				+ "	 \n"
				+ "`ifdef "+WRSECOND+"\n"
				+ "	reg ["+regW+"-1: 0]  "+WrNode+"_"+secondstage+"_reg;\n"
				+ "	always @(posedge clk_i) begin \n"
				+ "		if(rst_i)\n"
				+ "			"+WrNode+"_"+secondstage+"_reg <= 1'b0;\n"
				+ "		else if(!"+WrStall+"_"+firststage+"_i)\n"
				+ "			"+WrNode+"_"+secondstage+"_reg <= "+WrNode+"_"+firststage+"_i ; \n"
				+ "	end\n"
				+ "`endif	\n"
				+ "	\n"
				+ "`ifdef "+WRTHIRD+"\n"
				+ "	reg ["+regW+"-1: 0]  "+WrNode+"_"+thirdstage+"_reg;\n"
				+ "	always @(posedge clk_i) begin \n"
				+ "		if(rst_i)\n"
				+ "			"+WrNode+"_"+thirdstage+"_reg <= 1'b0;\n"
				+ "		else if(!"+WrStall+"_"+firststage+"_i)\n"
				+ "			"+WrNode+"_"+thirdstage+"_reg <= ("+WrNode_validReq+"_"+secondstage+"_i && !"+WrNode_validData+"_"+secondstage+"_reg) ? "+WrNode+"_"+secondstage+"_i :"+ WrNode+"_"+secondstage+"_reg; \n"
				+ "	end\n"
				+ "`else\n"
				+ "  wire [32-1: 0]  "+WrNode+"_"+thirdstage+"_reg =0;\n"
				+ "`endif	\n"
				+ "\n"
				+ "// Write data \n"
				+ "assign "+WrNode+"_"+writebackstage+"_s = "+WrNode_validData+"_"+writebackstage+"_reg ?  "+WrNode+"_"+writebackstage+"_reg : "+WrNode+"_"+writebackstage+"_i;\n"
				+ " \n"			
				+ " // Datahazard?\n"
				+ "wire DH_in_second"+RdNode+",DH_in_third"+RdNode+",DH_fr_second_to_third"+RdNode+";\n"
				+ "generate \n"
				+ "	if("+(writebackstage-firststage)+" > 0)\n"
				+ "		assign DH_in_second"+RdNode+" = ("+RdInstr+"_"+firststage+"_i[15+"+addrW+"-1:15] == "+RdInstr+"_"+secondstage+"_i[15+"+addrW+"-1:15]) && "+RdNode_validReq+"_"+firststage+"_i && "+WrNode_validReq+"_"+secondstage+"_i;\n"
				+ "	else \n"
				+ "		assign DH_in_second"+RdNode+" = 0;\n"
				+ "	\n"
				+ "	if("+(writebackstage-firststage)+" > 1)\n"
				+ "		assign DH_in_third"+RdNode+" = ("+RdInstr+"_"+firststage+"_i[15+"+addrW+"-1:15] == "+RdInstr+"_"+thirdstage+"_i[15+"+addrW+"-1:15]) && "+RdNode_validReq+"_"+firststage+"_i && "+WrNode_validReq+"_"+thirdstage+"_i;\n"
				+ "	else \n"
				+ "		assign DH_in_third"+RdNode+" = 0;\n"
				+ "	\n"
				+ "	if("+(writebackstage-firststage)+" > 1 && RDSEC"+RdNode+" >0)\n"
				+ "		assign DH_fr_second_to_third"+RdNode+" = ("+RdInstr+"_"+secondstage+"_i[15+"+addrW+"-1:15] == "+RdInstr+"_"+thirdstage+"_i[15+"+addrW+"-1:15]) && "+RdNode_validReq+"_"+secondstage+"_i && "+WrNode_validReq+"_"+thirdstage+"_i;\n"
				+ "	else \n"
				+ "		assign DH_fr_second_to_third"+RdNode+" = 0;	\n"
				+ "endgenerate\n"
				+ "\n"
				+ "\n"
				+ "wire ["+regW+"-1:0] data_from_reg"+RdNode+";\n"
				+ "`ifdef "+MULTIPLEREGS+"\n"
				+ "	assign data_from_reg"+RdNode+" = "+WrNode+"_"+(writebackstage+1)+"_reg["+RdInstr+"_"+firststage+"_i[15+"+addrW+"-1:15]];\n"
				+ "`else\n"
				+ "	assign data_from_reg"+RdNode+" = "+WrNode+"_"+(writebackstage+1)+"_reg;\n"
				+ "`endif\n"
				+ "\n"
				+ "\n"
				+ " // First stage read\n"
				+ "reg "+RdStall+RdNode+"_"+firststage+"_o;\n"
				+ "always@(*) begin\n"
				+ "	"+RdNode+"_"+firststage+"_o = data_from_reg"+RdNode+";\n"
				+ "	"+RdStall+RdNode+"_"+firststage+"_o = 0;\n"
				+ "	`ifdef "+WRSECOND+"\n"
				+ "		if(DH_in_second"+RdNode+") begin \n"
				+ "			if("+WrNode_validData+"_"+secondstage+"_i && !"+WrNode_validData+"_"+secondstage+"_reg)\n"
				+ "				"+RdNode+"_"+firststage+"_o = "+WrNode+"_"+secondstage+"_i;\n"
				+ "			else if("+WrNode_validData+"_"+secondstage+"_reg) \n"
				+ "				"+RdNode+"_"+firststage+"_o = "+WrNode+"_"+secondstage+"_reg;\n"
				+ "			else \n"
				+ "				"+RdStall+RdNode+"_"+firststage+"_o = 1;\n"
				+ "		end\n"
				+ "	`endif	\n"
				+ "	`ifdef "+WRTHIRD+"\n"
				+ "		if(DH_in_third"+RdNode+") begin \n"
				+ "			if("+WrNode_validData+"_"+thirdstage+"_i && !"+WrNode_validData+"_"+thirdstage+"_reg)\n"
				+ "				"+RdNode+"_"+firststage+"_o = "+WrNode+"_"+thirdstage+"_i;\n"
				+ "			else if("+WrNode_validData+"_"+thirdstage+"_reg) \n"
				+ "				"+RdNode+"_"+firststage+"_o = "+WrNode+"_"+thirdstage+"_reg;\n"
				+ "			else \n"
				+ "				"+RdStall+RdNode+"_"+firststage+"_o = 1;\n"
				+ "		end\n"
				+ "	`endif			\n"
				+ "end\n"
				+ "\n"
				+ "\n"
				+ "reg "+RdStall+RdNode+"_"+secondstage+"_o;\n"
				+ "`ifdef "+RDSECOND+"\n"
				+ "// Second stage read\n"
				+ "reg [31:0] data_from_reg"+RdNode+"_"+secondstage+"_reg;\n"
				+ "always@(*) begin\n"
				+ "	"+RdNode+"_"+secondstage+"_o = data_from_reg"+RdNode+"_"+secondstage+"_reg;\n"
				+ "	"+RdStall+RdNode+"_"+secondstage+"_o = 0;\n"
				+ "	`ifdef "+WRTHIRD+"\n"
				+ "		if(DH_fr_second_to_third"+RdNode+") begin \n"
				+ "			if("+WrNode_validData+"_"+thirdstage+"_i && !"+WrNode_validData+"_"+thirdstage+"_reg)\n"
				+ "				"+RdNode+"_"+secondstage+"_o = "+WrNode+"_"+thirdstage+"_i;\n"
				+ "			else if("+WrNode_validData+"_"+thirdstage+"_reg) \n"
				+ "				"+RdNode+"_"+secondstage+"_o = "+WrNode+"_"+thirdstage+"_reg;\n"
				+ "			else \n"
				+ "				"+RdStall+RdNode+"_"+secondstage+"_o = 1;\n"
				+ "		end\n"
				+ "	always @(posedge clk_i) begin \n"
				+ "		if(rst_i)\n"
				+ "			data_from_reg"+RdNode+"_"+secondstage+"_reg <= '0;\n"
				+ "		else if(!"+WrStall+"_"+firststage+"_i)\n"
				+ "			data_from_reg"+RdNode+"_"+secondstage+"_reg <= data_from_reg"+RdNode+";\n"
				+ "	end\n"
				+ "	`endif			\n"
				+ "end\n"
				+ "`else \n"
				+ "always@(*)\n"
				+ "	"+RdStall+RdNode+"_"+secondstage+"_o = 0;\n"
				+ "	`endif			\n"
				+ "`ifdef "+RDTHIRD+"\n"
				+ "// Third stage read ("+RDSECOND+" should be defined)\n"
				+ "reg [31:0] data_from_reg"+RdNode+"_"+thirdstage+"_reg;\n"
				+ "always@(*)\n"
				+ "	"+RdNode+"_"+thirdstage+"_o = data_from_reg"+RdNode+"_"+thirdstage+"_reg;\n"
				+ "	always @(posedge clk_i) begin \n"
				+ "		if(rst_i)\n"
				+ "			data_from_reg"+RdNode+"_"+thirdstage+"_reg <= '0;\n"
				+ "		else if(!"+WrStall+"_"+secondstage+"_i)\n"
				+ "			data_from_reg"+RdNode+"_"+thirdstage+"_reg <= data_from_reg"+RdNode+"_"+secondstage+"_reg;\n"
				+ "	end\n"
				+ "`endif			\n"
				+ "\n";
		String[] arr = { interf, module };
		return arr;
		
	}
}
