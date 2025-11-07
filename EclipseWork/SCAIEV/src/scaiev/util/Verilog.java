package scaiev.util;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.function.BiConsumer;

import scaiev.backend.BNode;
import scaiev.backend.CoreBackend;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.util.GenerateText.DictWords;

public class Verilog extends GenerateText {

	FileWriter toFile;
	public String tab = "    ";
	public String clk = "clk_i";
	public String reset = "rst_i";
	public BNode BNode = new BNode();
	
	/**
	 * Basic Class constructor only with dictionary
	 */
	public Verilog() {
		// initialize dictionary 
		DictionaryDefinition();
	}
	
	/**
	 * Class constructor
	 * @param toFile
	 * @param core
	 */
	public Verilog(FileWriter toFile, CoreBackend core) {
		// initialize dictionary 
		DictionaryDefinition();
		this.toFile = toFile;
		tab = toFile.tab;
		this.coreBackend = core;
	}
	
	/**
	 * Use this constructor if you simply want to use the Verilog functions which do not use toFile or coreBackend. However, be aware it won't work to use functions which require coreBackend & toFile!!
	 */
	public Verilog(BNode BNode) {
		// initialize dictionary 
		DictionaryDefinition();
		this.BNode = BNode;
	}
	
	
	private void DictionaryDefinition () {
		// initialize dictionary 
		dictionary.put(DictWords.module,"module");
		dictionary.put(DictWords.endmodule,"endmodule");
		dictionary.put(DictWords.reg,"reg");
		dictionary.put(DictWords.wire,"wire");		
		dictionary.put(DictWords.assign,"assign");
		dictionary.put(DictWords.assign_eq,"=");
		dictionary.put(DictWords.logical_or,"||");
		dictionary.put(DictWords.bitwise_or,"|");
		dictionary.put(DictWords.logical_and,"&&");
		dictionary.put(DictWords.bitwise_and,"&");
		dictionary.put(DictWords.bitsselectRight,"]");
		dictionary.put(DictWords.bitsselectLeft,"[");
		dictionary.put(DictWords.ifeq,"==");
		dictionary.put(DictWords.bitsRange,":");
		dictionary.put(DictWords.in,"input");
		dictionary.put(DictWords.out,"output");
		dictionary.put(DictWords.False,"0");
		dictionary.put(DictWords.True,"1");
		dictionary.put(DictWords.ZeroBit,"1'b0");
		dictionary.put(DictWords.OneBit,"1'b1");
	} 
	
	@Override 
	public Lang getLang () {
		return Lang.Verilog;		
	}
	

	
	/**
	 * Generates text like : signal signalName_s  :  std_logic_vector(1 : 0);
	 * signalName created from <operation,  stage,  instr>
	 */
	public String CreateDeclSig(SCAIEVNode operation, int stage, String instr,boolean reg) {
		String decl = "";
		String size = "";
		if(coreBackend.NodeSize(operation,stage) != 1 ) 
			size += dictionary.get(DictWords.bitsselectLeft)+" "+operation.size+" -1 : 0 "+dictionary.get(DictWords.bitsselectRight);
		String wire = "wire";
		if(reg)
			wire = "reg";
		decl = wire+" "+size+" "+CreateLocalNodeName(operation,stage,instr)+";\n";
		return decl;	
	}
	
	public String CreateDeclSig(SCAIEVNode operation, int stage, String instr,boolean reg, String specificName) {
		String decl = "";
		String size = "";
		if(coreBackend.NodeSize(operation,stage) != 1 ) 
			size += dictionary.get(DictWords.bitsselectLeft)+" "+operation.size+" -1 : 0 "+dictionary.get(DictWords.bitsselectRight);
		String wire = "wire";
		if(reg)
			wire = "reg";
		decl = wire+" "+size+" "+specificName+";\n";
		return decl;	
	}
	
	/**
	 * Generates text like : signal signalName_reg  :  std_logic_vector(1 : 0);
	 * signalName created from <operation,  stage,  instr>
	 */
	public String CreateDeclReg(SCAIEVNode operation, int stage, String instr) {
		String decl = "";
		String size = "";
		if(coreBackend.NodeSize(operation,stage) != 1 ) 
			size += dictionary.get(DictWords.bitsselectLeft)+" "+operation.size+" -1 : 0 "+dictionary.get(DictWords.bitsselectRight);
		String regName = "";
		if(coreBackend.NodeIn(operation, stage))
			regName = CreateRegNodeName(operation,stage,instr);
		else 
			regName = CreateRegNodeName(operation,stage,instr);
		decl = "reg "+size+" "+regName+";\n";
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
	public String CreateTextInterface(SCAIEVNode operation, int stage, String instr) {
		String interf_lineToBeInserted = "";
		String sig_name = this.CreateNodeName(operation, stage, instr);
		String sig_in = this.dictionary.get(DictWords.out);
		if(operation.isInput)
			sig_in = this.dictionary.get(DictWords.in);
		String size = "";
		if(operation.size> 1 ) 
			size += "["+operation.size+" -1 : 0]";
		// Add top interface	
		if(coreBackend.IsNodeInStage(operation, stage))
			interf_lineToBeInserted = sig_in + " " + coreBackend.NodeDataT(operation, stage) + " "+size +" "+ sig_name+",// ISAX\n";
		else 
			interf_lineToBeInserted = sig_in + " " +size +" "+ sig_name+",// ISAX\n";
		return interf_lineToBeInserted;
	}
	
	/**
	 * More general implementation of CreateTextInterface, which does not require CoreBackend
	 * @param operation
	 * @param stage
	 * @param instr
	 * @param input
	 * @param signalSize
	 * @return
	 */
	public String CreateTextInterface(String operation, int stage, String instr, boolean input, int signalSize) {
		String interf_lineToBeInserted = "";
		SCAIEVNode node = new SCAIEVNode(operation, signalSize,input);
		String sig_name = this.CreateNodeName(node, stage, instr);
		String sig_in = this.dictionary.get(DictWords.out);
		if(input)
			sig_in = this.dictionary.get(DictWords.in);
		String size = "";
		if(signalSize> 1 ) 
			size += "["+signalSize+" -1 : 0]";
		// Add top interface	
		interf_lineToBeInserted = sig_in + " " +size +" "+ sig_name+",// ISAX\n";
		return interf_lineToBeInserted;
	}
	

	public String CreateTextInterface(String operation, int stage, String instr, boolean input, int signalSize, String dataT) {
		String interf_lineToBeInserted = "";
		SCAIEVNode node = new SCAIEVNode(operation, signalSize,input);
		String sig_name = this.CreateNodeName(node, stage, instr);
		String sig_in = this.dictionary.get(DictWords.out);
		if(input)
			sig_in = this.dictionary.get(DictWords.in);
		String size = "";
		if(signalSize> 1 ) 
			size += "["+signalSize+" -1 : 0]";
		// Add top interface	
		interf_lineToBeInserted = sig_in + " "+ dataT+" "+size +" "+ sig_name+",// ISAX\n";
		return interf_lineToBeInserted;
	}
	
	public String  CreateValidEncodingIValid(HashSet<String> lookAtISAX, HashMap <String,SCAIEVInstr>  allISAXes, int stage, SCAIEVNode operation, SCAIEVNode checkAdj, int defaultValue) {
		String body = "always @(*) begin \n"
				+this.tab.repeat(1)+ "case(1'b1)\n";
		String assignLogic = "";
		String empty_assignLogic = "";
		int nrElem = 0;
		SCAIEVNode assignNode = operation;
		if(!checkAdj.getAdj().equals(AdjacentNode.none))
			assignNode = checkAdj;
		SCAIEVNode correctcheckAdj;
		if(!checkAdj.attachedNode.isEmpty()) // for exp in case of wrmem_addr_valid, we need to check if ISAX contains addr Adj
			correctcheckAdj = BNode.GetSCAIEVNode(checkAdj.attachedNode);
		else 
			correctcheckAdj = checkAdj;
		int expectedElem = lookAtISAX.size();
		
		// Order ISAXes so that the ones without opcode have priority (they are like spawn)
		TreeMap<Integer,String> lookAtISAXOrdered =  OrderISAXOpCode( lookAtISAX, allISAXes);
	
		for (Integer priority  :  lookAtISAXOrdered.descendingKeySet()) {
			String ISAX = lookAtISAXOrdered.get(priority);
			
			// Create RdIValid = user valid for instr without encoding, else decode instr and create IValid
			String RdIValid = this.CreateLocalNodeName(BNode.RdIValid, stage, ISAX); 
			if(allISAXes.get(ISAX).HasNoOp()) {
				SCAIEVNode userValid = BNode.GetAdjSCAIEVNode(operation, AdjacentNode.validReq);
				if(operation.getAdj()== AdjacentNode.validReq)
					RdIValid = this.CreateNodeName(operation, stage, ISAX);
				else if(!operation.isInput && !operation.DH) // if output (read node) and has no DH ==> no valid bit required, constant read
					RdIValid = "1'b0";
				else 
					RdIValid = this.CreateNodeName(userValid, stage, ISAX);
			}
			
			// Create body
			boolean requiredByNode = allISAXes.get(ISAX).HasSchedWith(operation, snode -> snode.HasAdjSig(correctcheckAdj.getAdj())) && !assignNode.noInterfToISAX; // not required if it does not have interf to ISAX
			if((requiredByNode || !checkAdj.isAdj())) { // should not go on this path if it has no opcode (= encoding don.t care)
				nrElem++;
				String assignSignal =  this.CreateNodeName(assignNode, stage, ISAX);
				if(!checkAdj.attachedNode.isEmpty()) // for wrmem_addr_valid. It is simply 1 in case of an instr using addr bits
					assignSignal = ""+defaultValue+"";
				if(expectedElem == nrElem && !checkAdj.DefaultMandatoryAdjSig())
					body += this.tab.repeat(2) +"default : "+this.CreateNodeName(assignNode.NodeNegInput(), stage, "") +" = " + assignSignal+ ";\n"; //avoid latch
				else
					body += this.tab.repeat(2) +RdIValid+" : "+this.CreateNodeName(assignNode.NodeNegInput(), stage, "") +" = " + assignSignal+ ";\n";				
				assignLogic = "always @(*)  "+this.CreateNodeName(assignNode.NodeNegInput(), stage, "") +" = " + assignSignal+ ";\n";			
			} else if(checkAdj.DefaultMandatoryAdjSig() && checkAdj.attachedNode.isEmpty())  { // for exp in case of addrValid, it should not go on this path
				body += this.tab.repeat(2) +RdIValid+" : "+this.CreateNodeName(assignNode.NodeNegInput(), stage, "") +" = "+defaultValue+ ";\n";
			} else if(checkAdj.DefaultMandatoryAdjSig()) // here should go addrValid if no instructions require it
			    empty_assignLogic =  "always @(*)  "+this.CreateNodeName(assignNode.NodeNegInput(), stage, "") +" = ~" + defaultValue+ ";\n";
		}
		if(checkAdj.DefaultMandatoryAdjSig())
			body += this.tab.repeat(2)+"default : "+this.CreateNodeName(assignNode.NodeNegInput(), stage, "")+" = ~"+defaultValue+";\n";
		body += this.tab.repeat(1)+"endcase\nend\n";
		if((nrElem==0) && !checkAdj.DefaultMandatoryAdjSig())
			return "";
		if(nrElem==1 && !checkAdj.DefaultMandatoryAdjSig())
			return assignLogic;
		if((nrElem==0) && checkAdj.getAdj() ==AdjacentNode.addrReq)
			return empty_assignLogic; 
		return body  ;		
	}
	
	/** 
	 * This function creates the text for Node_ValidReq based on instruction decoding. If instr in this stage & user wants valid bit, this is used, otherwise not. NOT generated for ISAXes without opcode
	 * @param stage_lookAtISAX
	 * @param allISAXes
	 * @param stage
	 * @param operation
	 * @return
	 */
	public String  CreateValidReqEncodingEarlierStages(HashMap<Integer,HashSet<String>> stage_lookAtISAX,  HashMap <String,SCAIEVInstr>  allISAXes, int stage, SCAIEVNode operation, int earliestStage, int latestStage) {
		String body = "always @(*) begin \n" 
					+this.tab.repeat(1)+ "case(1'b1)\n";
		SCAIEVNode checkAdj = BNode.GetAdjSCAIEVNode(operation, AdjacentNode.validReq);
		boolean onlyAlways = true;
		for (int stageISAX :  stage_lookAtISAX.keySet()) {
			
			// Order ISAXes so that the ones without opcode have priority (they are like spawn)
			TreeMap<Integer,String> lookAtISAXOrdered = OrderISAXOpCode( stage_lookAtISAX.get(stageISAX), allISAXes);
			for(int priority : lookAtISAXOrdered.descendingKeySet())  {
				String ISAX = lookAtISAXOrdered.get(priority);
				// Check if no opcode ISAX (which does not have rdivalid).  
				if(allISAXes.get(ISAX).HasNoOp() && (stage != operation.commitStage)) //always block must still write result in commit stage
					continue; 
				if(!allISAXes.get(ISAX).HasNoOp())
					onlyAlways = false;
							
				// Create RdIValid = user valid for instr without encoding, else decode instr and create IValid
				String RdIValid = this.CreateLocalNodeName(BNode.RdIValid, stage, ISAX); 
				if(allISAXes.get(ISAX).HasNoOp()) {
					SCAIEVNode userValid = BNode.GetAdjSCAIEVNode(operation, AdjacentNode.validReq);
					if(operation.getAdj()== AdjacentNode.validReq)
						RdIValid = this.CreateNodeName(operation, stage, ISAX);
					else if(!operation.isInput && !operation.DH) // if output (read node) and has no DH ==> no valid bit required, constant read
						RdIValid = "1'b0";
					else 
						RdIValid = this.CreateNodeName(userValid, stage, ISAX);
				}
				// Generate case-logic
				if(stage == stageISAX && allISAXes.get(ISAX).GetFirstNode(operation).HasAdjSig( AdjacentNode.validReq)) {			
					body += this.tab.repeat(2) +RdIValid+ " : "+this.CreateNodeName(checkAdj.NodeNegInput(), stage, "") +" = "+this.CreateNodeName(checkAdj, stage,ISAX)+";\n";
				} else if(stageISAX>=stage)
					body += this.tab.repeat(2) +RdIValid+" : "+this.CreateNodeName(checkAdj.NodeNegInput(), stage, "") +" = 1'b1;\n";			
			}
		}
		if(stage == earliestStage || onlyAlways)
			body += this.tab.repeat(2)+"default : "+this.CreateNodeName(checkAdj.NodeNegInput(), stage, "")+" = 1'b0;\n";
		else 
			body += this.tab.repeat(2)+"default : "+this.CreateNodeName(checkAdj.NodeNegInput(), stage, "")+" = "+this.CreateRegNodeName(checkAdj.NodeNegInput(), stage, "")+" && ! "+this.CreateNodeName(BNode.RdFlush.NodeNegInput(), stage, "")+";\n";
		
		body += this.tab.repeat(1)+"endcase\nend\n";
		// Generates the reg for next stage. If not needed, it will be optimized away 
		if(!((stage+1)>=operation.spawnStage))
			body +=   "reg "+ this.CreateRegNodeName(checkAdj.NodeNegInput(), stage+1, "") +"; // Info: if not really needed, will be optimized away by synth tool \n"
				+ "always @(posedge "+this.clk+") begin \n"
				+ tab+"if("+this.reset+")\n"
				+ tab+tab+this.CreateRegNodeName(checkAdj.NodeNegInput(), stage+1, "")+" <= 1'b0;\n"
				+ tab+"else if(!"+this.CreateNodeName(BNode.RdStall.NodeNegInput(), stage, "")+")\n"
				+ tab+tab+this.CreateRegNodeName(checkAdj.NodeNegInput(), stage+1, "") +" <= "+ this.CreateNodeName(checkAdj.NodeNegInput(), stage, "")+";\n"
				+ "end\n"; 
		if(latestStage == stage && (stage+1) < operation.spawnStage) // patch for ORCA, when we have operations only in 2, latestStage = 2, but we need validreq in 3 too due to DH frwrd PATH FROM STAGE 4 TO 3
			body +=  "assign "+ this.CreateNodeName(checkAdj.NodeNegInput(), stage+1, "") +" = "+this.CreateRegNodeName(checkAdj.NodeNegInput(), stage+1, "")+"; // will be optimized away if not used\n";
		if(onlyAlways && stage != operation.commitStage)
			return "assign "+ this.CreateNodeName(checkAdj.NodeNegInput(), stage, "") +" = 1'b0; // CRITICAL WARNING, NO ISAX detected with opcode, RTL might be not functional\n";
		else
			return body  ;		
	}
	
	/** 
	 * This function creates the text for Node_ValidReq based on instruction decoding. If instr in this stage & user wants valid bit, this is used, otherwise not. NOT generated for ISAXes without opcode
	 * @param stage_lookAtISAX
	 * @param allISAXes
	 * @param stage
	 * @param operation
	 * @return
	 */
	public String  CreateValidDataEncodingEarlierStages(HashMap<Integer,HashSet<String>> stage_lookAtISAX,  HashMap <String,SCAIEVInstr>  allISAXes, int stage, SCAIEVNode operation) {
		String body = "always @(*) begin \n"
				+this.tab.repeat(1)+ "case(1'b1)\n";
		SCAIEVNode checkAdj = BNode.GetAdjSCAIEVNode(operation, AdjacentNode.validData);
		boolean onlyAlways = true;
		
		for (int stageISAX :  stage_lookAtISAX.keySet()) 
			for (String ISAX  :  stage_lookAtISAX.get(stageISAX)) {
				// Check if no opcode ISAX (which does not have rdivalid).  
				if(allISAXes.get(ISAX).HasNoOp() && (stage != operation.commitStage)) //always block must still write result in commit stage
					continue; 
				if(!allISAXes.get(ISAX).HasNoOp())
					onlyAlways = false;
				// For ISAXes with opcode, generate case-logic
				if(allISAXes.get(ISAX).HasNoOp()) // in case of always block, we don't use RdIVAlid, but the valid req interf signal
					body += this.tab.repeat(2) +this.CreateNodeName(BNode.GetAdjSCAIEVNode(operation, AdjacentNode.validReq),  stage, ISAX)+" : "+this.CreateNodeName(checkAdj.NodeNegInput(), stage, "") +" = 1'b1;\n";
				else if(stageISAX>stage)
					body += this.tab.repeat(2) +this.CreateLocalNodeName(BNode.RdIValid, stage, ISAX)+" : "+this.CreateNodeName(checkAdj.NodeNegInput(), stage, "") +" = 1'b0;\n";
				else 
					body += this.tab.repeat(2) +this.CreateLocalNodeName(BNode.RdIValid, stage, ISAX)+" : "+this.CreateNodeName(checkAdj.NodeNegInput(), stage, "") +" = 1'b1;\n";
			
			}
		body += this.tab.repeat(2)+"default : "+this.CreateNodeName(checkAdj.NodeNegInput(), stage, "")+" = 1'b0;\n";
		body += this.tab.repeat(1)+"endcase\nend\n";
		if(onlyAlways && stage != operation.commitStage)
			return "assign "+ this.CreateNodeName(checkAdj.NodeNegInput(), stage, "") +" = 1'b0; // CRITICAL WARNING, NO ISAX detected with opcode, RTL could be not functional\n";
		else
			return body  ;
	}
	
	private TreeMap<Integer,String> OrderISAXOpCode (HashSet<String> lookAtISAX,HashMap<String, SCAIEVInstr> allISAXes  ) {
		TreeMap<Integer,String> lookAtISAXOrdered = new TreeMap<Integer,String>();
		int withOp =-1; 
		int noop =0; 
		for(String ISAX  :  lookAtISAX) {
			if(allISAXes.get(ISAX).HasNoOp()) 
				lookAtISAXOrdered.put(noop++, ISAX);
			else 
				lookAtISAXOrdered.put(withOp--, ISAX);
		}
		return lookAtISAXOrdered;
	}
	
	public void  UpdateInterface(String top_module,SCAIEVNode operation, String instr, int stage, boolean top_interface, boolean instReg) {
		// Update interf bottom file
		System.out.println("INTEGRATE. DEBUG. stage = "+stage+" operation = "+operation);
		
		// Update interf bottom file
		String assign_lineToBeInserted = "";
		
		// Add top interface	

		String sig_name = this.CreateNodeName(operation, stage, instr);
		String bottom_module = coreBackend.NodeAssignM(operation, stage);
		if(top_module.contentEquals(bottom_module) && !top_interface)
			sig_name = this.CreateLocalNodeName(operation, stage, instr);
		String current_module = bottom_module;
		String prev_module = "";
		while(!prev_module.contentEquals(top_module)) {
			HashMap<String,ToWrite> insert = new HashMap<String,ToWrite>();
			if(!current_module.contentEquals(top_module) || top_interface) {  // top file should just instantiate signal in module instantiation and not generate top interface
				this.toFile.UpdateContent(coreBackend.ModFile(current_module),");",new ToWrite(CreateTextInterface(operation,stage,instr),false,true,"module "+current_module+" ",true,current_module));								
			} else if(current_module.contentEquals(top_module)) {
				this.toFile.UpdateContent(coreBackend.ModFile(current_module),");",new ToWrite(CreateDeclSig(operation, stage, instr,instReg),false,true,"module "+current_module+" ",current_module));
			}
			
			if(prev_module.contentEquals("")) {
				// Connect signals to top interface
				if(!coreBackend.NodeAssign(operation, stage).contentEquals("")) {
					if(coreBackend.NodeDataT(operation, stage).contains("reg"))
						assign_lineToBeInserted += "always@(posedge  "+clk+")\n"+sig_name+" <= "+coreBackend.NodeAssign(operation, stage)+"; \n";
					else 
						assign_lineToBeInserted += "assign "+sig_name+" = "+coreBackend.NodeAssign(operation, stage)+"; \n";
					this.toFile.UpdateContent(coreBackend.ModFile(current_module),"endmodule", new ToWrite(assign_lineToBeInserted,false,true,"module "+current_module+" ",true,current_module));	
				} /*
				else {
					assign_lineToBeInserted += this.CreateDeclSig(operation, stage, instr,coreBackend.NodeDataT(operation, stage).contains("reg"))+" \n";
					this.toFile.UpdateContent(coreBackend.ModFile(current_module),");", new ToWrite(assign_lineToBeInserted,true,false,"module "+current_module,current_module));	
				}*/
			} else {
				String instance_sig = "";
				
				if(current_module.contentEquals(top_module) && !top_interface)
					instance_sig = "."+sig_name+" ( "+this.CreateLocalNodeName(operation, stage, instr)+"),\n";
				else
					instance_sig = "."+sig_name+" ( "+sig_name+"),\n";
				this.toFile.UpdateContent(coreBackend.ModFile(current_module),");", new ToWrite(instance_sig,true,false,prev_module+" ",true,current_module));
			}
			prev_module = current_module;
			if(!current_module.contentEquals(top_module) && !coreBackend.ModParentName(current_module).equals(""))
				current_module = coreBackend.ModParentName(current_module);	
			else 
				break;				
		}
		
	}
	
	//Hack to fix missing as well as trailing module&interface port commas in added lines. 
	public void FinalizeInterfaces() {
		class PortConsumer_CommaFixer implements BiConsumer<ToWrite, String> {
			HashMap<String, ArrayList<ToWrite>> ports = new HashMap<>();
			@Override
			public void accept(ToWrite to_write, String grep) {
				//NOTE: Depends on UpdateInterface internals!
				boolean is_moduleport = grep.equals(");") && to_write.prereq_val && to_write.prereq_text.startsWith("module ") && to_write.before && !to_write.replace;
				boolean is_instanceport = grep.equals(");") && to_write.prereq && to_write.text.startsWith(".") && to_write.text.endsWith("),\n") && to_write.before && !to_write.replace;
				if (is_moduleport || is_instanceport) {
					//For module port: prereq_text is "module <module_name>" 
					//For instance port: prereq_text is "<module_name> "
					//-> Can safely store both by prereq_text in same map without collisions.
					ArrayList<ToWrite> port_entry_list = ports.get(to_write.prereq_text);
					if (port_entry_list == null) {
						port_entry_list = new ArrayList<>();
						ports.put(to_write.prereq_text, port_entry_list);
						//Add comma before first added port
						//(Assumption: Module has at least one assigned port in the source file already).
						to_write.text = ", " + to_write.text;
					}
					port_entry_list.add(to_write);
				}
			}
			public void remove_trailing_commas() {
				for (ArrayList<ToWrite> updates : ports.values()) {
					//Assumption: Each port's list has at least one update.
					ToWrite last_update = updates.get(updates.size() - 1);
					int comma_index = last_update.text.lastIndexOf(',');
					if (comma_index == -1 || comma_index == 0) {
						System.out.println("WARN: Port update \"" + last_update.text + "\" has no comma at the end");
						continue;
					}
					//Remove comma from last update.
					last_update.text = last_update.text.substring(0, comma_index) + last_update.text.substring(comma_index + 1);
				}
			}
		}
		PortConsumer_CommaFixer consumer = new PortConsumer_CommaFixer();
		toFile.ConsumeUpdates(consumer);
		consumer.remove_trailing_commas();
	}
	
	public String CreateTextISAXorOrig(String ifClause, String newSignal, String ISAXSignal, String origSignal) {
		String text ="";
		text += "always@(*) begin \n"
				+ tab+"if( "+ifClause+" ) \n"
				+ tab.repeat(2)+newSignal+" <= "+ISAXSignal+"; \n"
				+ tab+"else \n"
				+ tab.repeat(2)+newSignal+" <= "+origSignal+";\n"
				+"end;\n\n";
		return text;
	}
	public String CreateTextRegReset(String signalName, String signalAssign, String stall) {
		String text ="";
		String stallText = "";
		if(!stall.isEmpty())
			stallText = "if (!("+stall+"))";
		text += "always@(posedge "+this.clk+") begin\n"
				+ tab+"if ("+this.reset+")\n"
				+ tab.repeat(2)+signalName+" <= 0;\n"
				+ tab+"else "+stallText+"\n"
				+ tab.repeat(2)+signalName+" <= "+signalAssign+";\n"
				+ "end;\n\n";
		return text;
	}
	
	public String CreateTextRegReset(String signalName, String signalAssign, String stall, String addrSignal, int nr_elem) {
		String text ="";
		String stallText = "";
		if(!stall.isEmpty())
			stallText = "if (!("+stall+"))";
		text += "always@(posedge "+this.clk+") begin\n"
				+ tab+"if ("+this.reset+") begin \n"
				+ tab.repeat(2)+"for (int i = 0 ; i< "+nr_elem+"-1; i= i+1 )\n"
				+ tab.repeat(3)+signalName+"[i] <= '0;\n"
				+ tab+"end else "+stallText+"\n"
				+ tab.repeat(2)+signalName+"["+addrSignal+"] <= "+signalAssign+";\n"
				+ "end;\n";
		return text;
	}
	
	public String CreateText1or0(String new_signal, String condition) {
		String text = "assign "+ new_signal + " = ("+condition+") ? 1'b1 : 1'b0;\n";
		return text;
	}
	
	public String CreateInAlways( boolean with_clk ,String text) {
		int i = 1;
		String sensitivity = "*";
		if(with_clk) {
			sensitivity = "posedge "+clk;
			i++;
		}
		String body ="always@("+sensitivity+") begin // ISAX Logic\n "+AllignText(tab.repeat(i),text)+"\n"+"end\n" ;
		return body;		
	}
	
	
	public String CreateAssign( String assigSig ,String toAssign) {
		return "assign "+ assigSig +" = "+toAssign+";\n";		
	}
	

	
}

	
	

