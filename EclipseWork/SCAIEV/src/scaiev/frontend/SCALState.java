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
		for(SCAIEVNode node : op_stage_instr.keySet()) {
			if(allBNodes.IsUserBNode(node) && node.isInput && !node.isSpawn())
				node_stageValid.put(node,this.core.GetNodes().get(node).GetEarliest()); // considered that also in rd stage wr possible
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
				textInst += ",\n." + myLanguage.CreateNodeName(node, stage, "");
				if(node.equals(allBNodes.RdStall))
					signalSCALInst = myLanguage.CreateLocalNodeName(allBNodes.WrStall, stage, "")+"_"+myModuleName;
				else if(node.equals(allBNodes.WrStall))
					signalSCALInst =  myLanguage.CreateNodeName(allBNodes.RdStall.NodeNegInput(), stage, "");
				else if(!node.equals(allBNodes.RdInstr))
					signalSCALInst = myLanguage.CreateNodeName(node.NodeNegInput(), stage, "");
				else
					signalSCALInst = myLanguage.CreateNodeName(node, stage, "");
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
		if(node.equals(allBNodes.RdInstr))
			nodeName = myLanguage.CreateNodeName(allBNodes.RdInstr, stage, "");
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
		String textRegisterModule = "";
		String textDelcare = "";
		String textLogic  = "";
		textRegisterModule = "module "+myModuleName+"(input "+myLanguage.clk+",input "+myLanguage.reset+",\n ";
		
		// Any user nodes? 
		boolean userNodePresent = false;
		// Datahazard variables
		boolean checkDatahaz = false;  // for datahazard, whether datahazard mechanism is needed
		String datahaz = "";
		
		for(SCAIEVNode node: op_stage_instr.keySet()) {

			///////// Create interfaces 
			if(allBNodes.IsUserBNode(node)) {
				
				userNodePresent = true;
				for(int stage : op_stage_instr.get(node).keySet() ) {	
					AddToInterface(node, stage);
					for(AdjacentNode adjacent : allBNodes.GetAdj(node)) {
			   			 SCAIEVNode adjOperation = allBNodes.GetAdjSCAIEVNode(node,adjacent);
			   			 boolean adjRequired = false; 
			   			 for(String instruction : ISAXes.keySet())
			   				if(ISAXes.get(instruction).HasNode(node) && ISAXes.get(instruction).HasSchedWith(node, _snode -> _snode.HasAdjSig(adjacent))) {
			   					adjRequired = true; 
			   				 }	   			 
			   			 if(adjRequired || adjOperation.DefaultMandatoryAdjSig() || adjOperation.mandatory) {
			   				 int stageInterf = stage;
			   				 if(AdjacentNode.addr ==adjacent || AdjacentNode.addrReq==adjacent) {
			   					 if(node.isSpawn())
			   						stageInterf = this.core.maxStage+1;
			   					 else
			   						 stageInterf =  this.core.GetNodes().get(node).GetEarliest();
			   				 }
			   				AddToInterface(adjOperation, stageInterf);
			   			 }	    			 
			   		 }
				}
			}
			
			////////// Create regs logic
			if(node.isInput && allBNodes.IsUserBNode(node)  && !node.isSpawn())
				textLogic += InstReg(node.name, node.size , node.elements);
			/////////// Signals on interf for deeper regs  (addr, addr_valid)
			if(node.elements>1 && allBNodes.IsUserFNode(node) && node.isInput && !node.isSpawn()) {
				SCAIEVNode addrWrNode = allBNodes.GetAdjSCAIEVNode(node,AdjacentNode.addr );
				SCAIEVNode addrWrReqNode = allBNodes.GetAdjSCAIEVNode(node,AdjacentNode.addrReq );
				SCAIEVNode RdNode = allBNodes.GetSCAIEVNode(allBNodes.GetNameRdNode(node));
				SCAIEVNode addrRdNode = allBNodes.GetAdjSCAIEVNode(RdNode,AdjacentNode.addr );
				SCAIEVNode addrRdReqNode = allBNodes.GetAdjSCAIEVNode(RdNode,AdjacentNode.addrReq );
				int earliest = this.core.GetNodes().get(RdNode).GetEarliest();
				int writebackStage = this.core.GetNodes().get(node).GetLatest();	
				AddToInterface(BNode.RdInstr.NodeNegInput(), earliest); // to read data 
				AddToInterface(addrRdReqNode, earliest);
				AddToInterface(addrRdNode, earliest);
				
				// Interf for write nodes
				for(int stage = earliest;stage <= writebackStage;stage++ )  
					if(this.op_stage_instr.get(node).containsKey(stage)) {
						AddToInterface(addrWrNode, stage);
						AddToInterface(addrWrReqNode, stage);
					}	
			}
			
			////////// Datahazard mechanism
			if(allBNodes.IsUserFNode(node) && node.isInput && !node.isSpawn() && node.DH ) {
				SCAIEVNode RdNode = allBNodes.GetSCAIEVNode(allBNodes.GetNameRdNode(node));
				
				int earliest = this.core.GetNodes().get(RdNode).GetEarliest();
				int writebackStage = this.core.GetNodes().get(node).GetLatest();
				
				if(node.elements>1) {
					SCAIEVNode addrNode = allBNodes.GetAdjSCAIEVNode(node,AdjacentNode.addr );
					SCAIEVNode addrReqNode = allBNodes.GetAdjSCAIEVNode(node,AdjacentNode.addrReq );
					SCAIEVNode addrRdNode = allBNodes.GetAdjSCAIEVNode(RdNode,AdjacentNode.addr );
					SCAIEVNode addrRdReqNode = allBNodes.GetAdjSCAIEVNode(RdNode,AdjacentNode.addrReq );					
					SCAIEVNode reqRdNode = allBNodes.GetAdjSCAIEVNode(RdNode,AdjacentNode.validReq );
					SCAIEVNode reqWrNode = allBNodes.GetAdjSCAIEVNode(node,AdjacentNode.validReq );
					String defaultDatahaz = myLanguage.CreateNodeName(allBNodes.RdStall, earliest  , "")+" = 0;\n";
					if(!datahaz.contains(defaultDatahaz))
						datahaz += defaultDatahaz;
					for(int stage = earliest;stage <= writebackStage;stage++ ) { 
						if (earliest!=writebackStage) { // only then we need DH
							String addrReq = myLanguage.CreateRegNodeName(addrReqNode, stage, "");
							String addrSig = myLanguage.CreateRegNodeName(addrNode, stage, "");
							
							if(stage == earliest){
								String toAddText =  myLanguage.CreateTextInterface(allBNodes.RdStall.name, stage, "", false,1 ,"reg");  // add interface for stall signal
								if(!textInterface.contains(toAddText))
									textInterface.add(toAddText);	
								checkDatahaz = true;
								AddToInterface(allBNodes.RdStall, stage);
								addrReq = myLanguage.CreateNodeName(addrReqNode, stage, "");
								addrSig = myLanguage.CreateNodeName(addrNode, stage, "");
								
								//textLogic += myLanguage.CreateAssign(myLanguage.CreateLocalNodeName(addrReqNode, stage, ""), myLanguage.CreateNodeName(addrReqNode, stage, ""));
							} else  { 
								textDelcare += myLanguage.CreateDeclReg(addrReqNode, stage, "");
								textDelcare += myLanguage.CreateDeclSig(addrReqNode, stage, "",false);
							System.out.println("   reqRdNode  "+reqRdNode+" reqWrNode "+reqWrNode);
								datahaz += "if( ("+myLanguage.CreateNodeName(addrRdNode,earliest , "")+" & {"+addrRdNode.size+"{"+myLanguage.CreateNodeName(addrRdReqNode, earliest, "")+"}} | "
										+ myLanguage.CreateNodeName(BNode.RdInstr.NodeNegInput(),earliest , "")+"[19:15] & ~{"+addrRdNode.size+"{"+myLanguage.CreateNodeName(addrRdReqNode, earliest, "")+"}} )"
										+ " == "
										+ "("+addrSig+" & {"+addrRdNode.size+"{"+addrReq+"}} | "
										+ myLanguage.CreateNodeName(BNode.RdInstr.NodeNegInput(),stage , "")+"[19:15] & ~{"+addrRdNode.size+"{"+addrReq+"}} )"
										+" && "+ myLanguage.CreateNodeName(reqRdNode,earliest , "")+" && "+myLanguage.CreateNodeName(reqWrNode,stage , "")+") \n"
										+ tab+ myLanguage.CreateNodeName(allBNodes.RdStall, earliest, "")+" = 1;\n";
								AddToInterface(reqRdNode, earliest);
							}
						}
				  }	
				} else  { // Only if DH required
					String defaultDatahaz = myLanguage.CreateNodeName(allBNodes.RdStall, earliest  , "")+" = 0;\n";
					if(!datahaz.contains(defaultDatahaz))
						datahaz += defaultDatahaz;
					SCAIEVNode reqNode = allBNodes.GetAdjSCAIEVNode(node,AdjacentNode.validReq );
					SCAIEVNode reqRdNode = allBNodes.GetAdjSCAIEVNode(allBNodes.GetSCAIEVNode(allBNodes.GetNameRdNode(node)),AdjacentNode.validReq );
					
					for(int stage = earliest;stage <= writebackStage;stage++ ) { 
						if (earliest!=writebackStage) { // only then we need DH
							if(stage == earliest){
								String toAddText =  myLanguage.CreateTextInterface(allBNodes.RdStall.name, stage, "", false,1 ,"reg");  // add interface for stall signal
								textInterface.add(toAddText);	
								checkDatahaz = true;
								AddToInterface(allBNodes.RdStall, stage);	
								//textLogic += myLanguage.CreateAssign(myLanguage.CreateLocalNodeName(reqNode, stage, ""), myLanguage.CreateNodeName(reqNode, stage, ""));
							} else { 
							 
								datahaz += "if("+myLanguage.CreateNodeName(reqRdNode,earliest , "")+" && "+ myLanguage.CreateNodeName(reqNode, stage, "") +") \n"
										+ tab+ myLanguage.CreateNodeName(allBNodes.RdStall, earliest, "")+" = 1;\n";
								AddToInterface(reqNode, stage);
								AddToInterface(reqRdNode, earliest);	
							}
						}							
					}
					
				}
			}
				
			////////// WR STALL /////
			if(allBNodes.IsUserFNode(node) && node.isInput && !node.isSpawn() ) {
				int earliest = this.core.GetNodes().get(node).GetEarliest();
				int writebackStage = this.core.GetNodes().get(node).GetLatest();
				
				for(int stage = earliest;stage <= writebackStage;stage++ ) { 					
					AddToInterface(allBNodes.WrStall, stage);					
				}
			}
				
				
			/////////// SPAWN MECHANISM ////////
			if(node.isSpawn() && allBNodes.IsUserBNode(node)) {
				int stageSpawn = this.core.maxStage+1;
				textDelcare += "wire [32-1:0] spawn_"+node.nameParentNode+"_data,spawn_"+node.nameParentNode+"_addr ;\nwire spawn_"+node.nameParentNode+"_valid;\n";
			//	textLogic += myLanguage.CreateAssign(myLanguage.CreateNodeName(allBNodes.GetAdjSCAIEVNode(node  , AdjacentNode.spawnAllowed), stageSpawn, ""),"1'b1"); For the moment default 1, so not added
				textLogic += myLanguage.CreateAssign(myLanguage.CreateNodeName(allBNodes.GetAdjSCAIEVNode(node  , AdjacentNode.validResp), stageSpawn, ""),"1'b1");
				textLogic += myLanguage.CreateAssign("spawn_"+node.nameParentNode+"_valid",myLanguage.CreateNodeName(allBNodes.GetAdjSCAIEVNode(node  , AdjacentNode.validReq), stageSpawn, ""));
				if(node.elements>1) {
					textLogic += myLanguage.CreateAssign("spawn_"+node.nameParentNode+"_addr",myLanguage.CreateNodeName(allBNodes.GetAdjSCAIEVNode(node  , AdjacentNode.addr), stageSpawn, ""));
					textDelcare += "wire ["+allBNodes.GetAdjSCAIEVNode(node  , AdjacentNode.addr).size+"-1:0] spawn_"+node.nameParentNode+"_addr;\n";				
				}
				textLogic += myLanguage.CreateAssign("spawn_"+node.nameParentNode+"_data",myLanguage.CreateNodeName(node, stageSpawn, ""));
		    }
			// If there is no spawn for this node, write defaults = 0
			if(!node.isSpawn()  && node.isInput && allBNodes.IsUserBNode(node)) {
				HashSet<SCAIEVNode> spawnNodes = allBNodes.GetMySpawnNodes(node);
				boolean containsSpawn = false;
				for(SCAIEVNode spawnNode: spawnNodes)
					if(this.op_stage_instr.containsKey(spawnNode))
						containsSpawn = true;
				if(!containsSpawn) {
					textDelcare += "wire [32-1:0] spawn_"+node+"_data,spawn_"+node+"_addr ;\nwire spawn_"+node+"_valid;\n";
					textLogic += myLanguage.CreateAssign("spawn_"+node+"_valid","0");
					textLogic += myLanguage.CreateAssign("spawn_"+node+"_data","0");
				}
			}
				
			
		}
		
		
		if(checkDatahaz)  textLogic += myLanguage.CreateInAlways(false, datahaz);
		
		for(SCAIEVNode node : mapInterface.keySet())
			for(int stage : mapInterface.get(node)) 
				if(!node.equals(allBNodes.RdStall))
					textInterface.add(myLanguage.CreateTextInterface(node, stage, ""));
		String printtextInterface = "";
		for(String text : textInterface)
			printtextInterface += text;
		printtextInterface += "output dummy_signal);\n"; // avoid syntax issue
		
		// Any user nodes so that we return entire module?
		if(userNodePresent)
			return textRegisterModule+myLanguage.AllignText(myLanguage.tab, printtextInterface)  +textDelcare+ textLogic+"endmodule\n";
		else 
			return "";
	}
	
	
	/**
	*Create logic for one register 
	*Currently only cores with writeback stage - read reg stage <= 2 are supported 
	**/
	public String InstReg( String register, int width, int elements) {
		String registerinst = "";
		HashSet<String> declare = new HashSet<String>(); // hashset to avoid double definitions
		
		// Get all needed SCAIEV Nodes
		SCAIEVNode regNode = allBNodes.GetSCAIEVNode(register);
		SCAIEVNode regRdNode = allBNodes.GetSCAIEVNode(allBNodes.GetNameRdNode(regNode));
		SCAIEVNode regNodeValid =allBNodes.GetAdjSCAIEVNode(regNode, AdjacentNode.validReq);
		SCAIEVNode regNodeDataValid  =allBNodes.GetAdjSCAIEVNode(regNode, AdjacentNode.validData);
		SCAIEVNode addrNode =allBNodes.GetAdjSCAIEVNode(regNode, AdjacentNode.addr);
		SCAIEVNode addrRdReqNode = allBNodes.GetAdjSCAIEVNode(regRdNode,AdjacentNode.addrReq );					
		SCAIEVNode addrRdNode =allBNodes.GetAdjSCAIEVNode(regRdNode, AdjacentNode.addr); // Adress signal for Read Node
		int earliest = this.core.GetNodes().get(regNode).GetEarliest();
		int writebackStage = this.core.GetNodes().get(regNode).GetLatest();

		
		// Create Regfile/regs (always seq). If request valid, register stores new value, otherwise no update
		if(elements>1) {
			declare.add("reg [32-1: 0] "+  myLanguage.CreateRegNodeName(regNode, writebackStage+1, "") +" ["+ elements +" -1:0];\n");
			String assignSig = "spawn_"+register+"_valid ? spawn_"+register+"_data : "+myLanguage.CreateLocalNodeName(regNode, writebackStage, "");
			String addrSig ="spawn_"+register+"_valid ? spawn_"+register+"_addr : "+ myLanguage.CreateLocalNodeName(addrNode,writebackStage, "" );
			String validSig ="("+ myLanguage.CreateNodeName(allBNodes.WrStall,  writebackStage, "") + " || !"+myLanguage.CreateNodeName(regNodeValid, writebackStage, "")+") && !(spawn_"+register+"_valid)";
			registerinst += myLanguage.CreateTextRegReset( myLanguage.CreateRegNodeName(regNode, writebackStage+1, ""),assignSig,validSig,addrSig  );
		} else { 
			declare.add(myLanguage.CreateDeclReg(regNode, writebackStage+1, ""));
			String assignSig = "spawn_"+register+"_valid ? spawn_"+register+"_data :  "+myLanguage.CreateLocalNodeName(regNode, writebackStage, "");
			String validSig ="("+ myLanguage.CreateNodeName(allBNodes.WrStall,  writebackStage, "") + " || !"+myLanguage.CreateNodeName(regNodeValid, writebackStage, "")+") && !(spawn_"+register+"_valid)";
			registerinst += myLanguage.CreateTextRegReset( myLanguage.CreateRegNodeName(regNode, writebackStage+1, ""),assignSig,validSig  );
		}
		
		// Read values of Reg
		// If Read Node is common (no Spawn, data read in earliest stage. Else, it's a spawn, simple assignment & no DH 
		String addr = "";
		if(this.op_stage_instr.containsKey(regRdNode)) {
			// Let's check if all our reads are just spawn. 
			int minStage = writebackStage+1;
			for(int stage : this.op_stage_instr.get(regRdNode).keySet()) 
				if(minStage>stage)
					minStage = stage;
			if(minStage> writebackStage)  // Just Spawn FOR READ { TODO also write
				noDH = true;		
			
			if(elements>1) {
				addr = "[("+myLanguage.CreateNodeName(addrRdNode,minStage , "")+" & { "+addrRdNode.size+"{"+  myLanguage.CreateNodeName(addrRdReqNode, minStage, "")+"}} | "
			+ myLanguage.CreateNodeName(BNode.RdInstr.NodeNegInput(),minStage , "")+"[19:15] & {"+addrRdNode.size+"{!"+myLanguage.CreateNodeName(addrRdReqNode, minStage, "") +"}})]";
			}
			registerinst += myLanguage.CreateAssign(
								myLanguage.CreateNodeName(regRdNode, minStage, "") ,
								myLanguage.CreateRegNodeName(regNode, writebackStage+1, "")+addr);
		}
		
		// Compute Local signals: valid request bit and data to be written. If present in this stage, take from interface, otherwise, take from prev stage 
		boolean added = false;
		for(int stage =earliest; stage<=writebackStage;stage++ ) {
			declare.add(myLanguage.CreateDeclSig(regNodeDataValid,stage, "", false ));
			declare.add(myLanguage.CreateDeclSig(regNode,stage, "", false ));
			if(op_stage_instr.get(regNode).containsKey(stage)) {
				
				if(added) {
					registerinst += myLanguage.CreateAssign(myLanguage.CreateLocalNodeName(regNodeDataValid,stage, "" ),myLanguage.CreateNodeName(regNodeDataValid,stage, "" )+" ? 1'b1 : "+myLanguage.CreateRegNodeName(regNodeDataValid,stage, "" ));
					registerinst += myLanguage.CreateAssign(myLanguage.CreateLocalNodeName(regNode, stage, ""),"("+myLanguage.CreateNodeName(regNodeDataValid,stage, "" )+" && ~"+myLanguage.CreateRegNodeName(regNodeDataValid,stage, "" )+") ? "+ myLanguage.CreateNodeName(regNode,stage, "" )+" : "+myLanguage.CreateRegNodeName(regNode,stage, "" ));					
				} else {
					registerinst += myLanguage.CreateAssign(myLanguage.CreateLocalNodeName(regNodeDataValid,stage, "" ),myLanguage.CreateNodeName(regNodeDataValid,stage, "" ));
					registerinst += myLanguage.CreateAssign(myLanguage.CreateLocalNodeName(regNode, stage, ""),myLanguage.CreateNodeName(regNode,stage, "" ));		
				}
				added = true; 
				
			} else if(added) {
				registerinst += myLanguage.CreateAssign(myLanguage.CreateLocalNodeName(regNodeDataValid,stage, "" ),myLanguage.CreateRegNodeName(regNodeDataValid,stage, "" ));			
		        registerinst += myLanguage.CreateAssign(myLanguage.CreateLocalNodeName(regNode, stage, ""),myLanguage.CreateRegNodeName(regNode,stage, "" ));
			}
			if(added)
				AddToInterface(regNodeDataValid, stage);  // validData sig we need independent of DH for commiting result
		}
		
		// Instantiate regs for data and validData
		added = false;
		for(int stage =earliest; stage<writebackStage;stage++ ) {
			if(op_stage_instr.get(regNode).containsKey(stage) | added) {
				added = true; 
				declare.add(myLanguage.CreateDeclReg(regNodeDataValid,stage+1, "" ));
				declare.add(myLanguage.CreateDeclReg(regNode,stage+1, "" ));
				registerinst += myLanguage.CreateTextRegReset(myLanguage.CreateRegNodeName(regNode, stage+1, ""), myLanguage.CreateLocalNodeName(regNode, stage, ""), myLanguage.CreateNodeName(allBNodes.WrStall, stage, ""));
				registerinst += myLanguage.CreateTextRegReset(myLanguage.CreateRegNodeName(regNodeDataValid, stage+1, ""), myLanguage.CreateLocalNodeName(regNodeDataValid, stage, ""), myLanguage.CreateNodeName(allBNodes.WrStall, stage, ""));
				//System.out.println("Step 4: "+ registerinst);
			}
		}
		
		// Address for writeback 
		for(int stage = earliest;stage <= writebackStage;stage++) {
			if(regNode.elements>1) { // if we need addr signal at all (more elements)
	 			// Declare local sigs and regs
				declare.add(myLanguage.CreateDeclSig( addrNode, stage, "",false));
				if(stage>earliest)
					declare.add(myLanguage.CreateDeclReg( addrNode, stage, ""));
	 			// Add logic
				if(stage == this.core.GetNodes().get(regNode).GetEarliest()) {
					registerinst +=  myLanguage.CreateAssign( myLanguage.CreateLocalNodeName(addrNode, stage, ""),myLanguage.CreateNodeName(addrNode, stage, ""));
				} else {
					registerinst += myLanguage.CreateAssign( myLanguage.CreateLocalNodeName(addrNode, stage, ""),myLanguage.CreateRegNodeName(addrNode, stage, ""));				
					registerinst += myLanguage.CreateTextRegReset( myLanguage.CreateRegNodeName(addrNode, stage, ""),myLanguage.CreateLocalNodeName(addrNode, stage-1, ""), myLanguage.CreateNodeName(BNode.WrStall, stage-1,"")  );
				}
			}
		}
		
		String returnDeclare = "";
		for (String decl : declare)
			returnDeclare += decl;
		return returnDeclare+registerinst;
	}
}
