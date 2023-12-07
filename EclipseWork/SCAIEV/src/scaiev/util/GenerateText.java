package scaiev.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;

import scaiev.backend.BNode;
import scaiev.backend.CoreBackend;
import scaiev.coreconstr.Core;
import scaiev.frontend.FNode;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.Scheduled;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;





public class GenerateText {
	public String currentFile;
	public CoreBackend coreBackend =  new CoreBackend();
	public String  regSuffix = "_reg";
	public String localSuffix = "_s";
	public String inputSuffix ="_i";
	public String outputSuffix = "_o";
	public boolean withinCore = false;
	public 	String clk = "clk";
	public String reset = "reset";
	public BNode BNode = new BNode();
	public enum DictWords {

		module           ,
		endmodule        ,
		wire             ,
		reg              ,
		assign           ,
		assign_eq        ,
		logical_or       ,
		logical_and      ,
		bitwise_and      ,
		bitwise_or       ,
		bitsselectRight  ,
		bitsselectLeft   ,
		bitsRange        ,
		in               ,
		out              ,
		ifeq             , 
		True             , 
		False            ,
		ZeroBit 		, 
		OneBit
		
	}
	
	public HashMap<DictWords,String> dictionary = new HashMap<DictWords,String>(){{ 
		put(DictWords.module, "");
		put(DictWords.endmodule, "");
		put(DictWords.assign, "");
		put(DictWords.wire, "");
		put(DictWords.reg, "");
		put(DictWords.assign_eq,"");
		put(DictWords.logical_or,"");
		put(DictWords.logical_and,"");
		put(DictWords.bitwise_or,"");
		put(DictWords.bitwise_and,"");
		put(DictWords.bitsselectRight,"");
		put(DictWords.bitsselectLeft,"");
		put(DictWords.bitsRange,"");
		put(DictWords.in,"");
		put(DictWords.out,"");
		put(DictWords.True,"");
		put(DictWords.False,"");
	}};
	public String allISAXNameText(String separator, String textBefore, String textAfter, HashSet<String> ISAXes) { // ISAXes array of ISAXes that are required, for exp in case of decoupled would be array with names of instr that are decoupled
		String returnString = "";
		for(String ISAXname : ISAXes) {
			if(!ISAXname.contains("DUMMY")) {// ela!
				if(!returnString.contentEquals(""))
					returnString += (separator);
				returnString += (textBefore+ISAXname+textAfter);
			}
		}
		return returnString;
	}
	
	
	public String allSCAIEVInstrText(String separator, String textBefore, String textAfter, HashMap<String,SCAIEVInstr> ISAXes) { // ISAXes array of ISAXes that are required, for exp in case of decoupled would be array with names of instr that are decoupled
		String returnString = "";
		for(String ISAXName : ISAXes.keySet()) {
			if(!ISAXName.contains("DUMMY")) {// TODO make this more general, error prone
				SCAIEVInstr ISAX = ISAXes.get(ISAXName);
				if(!returnString.contentEquals(""))
					returnString += (separator);
				returnString += (textBefore+ISAX.GetName()+textAfter);
			}
		}
		return returnString;
	}
	
	
	
	public String CreateNodeName (SCAIEVNode operation, int stage, String instr) {
		String nodeName = "";
		String suffix = this.outputSuffix;
		if(coreBackend.NodeIn(operation, stage))
			suffix =inputSuffix;	
		nodeName = CreateBasicNodeName(operation,stage,instr, true)+suffix;
		return nodeName;
	}
	

	public String CreateNodeName (String operation, int stage, String instr,boolean isInput) {
		String suffix = this.outputSuffix;
		if(isInput)
			suffix =inputSuffix;
		return operation+instr+"_"+stage+suffix;
	}
	
	public String CreateFamNodeName (SCAIEVNode operation, int stage, String instr, boolean familyName) {
		String nodeName = "";
		String suffix = this.outputSuffix;
		if(coreBackend.NodeIn(operation, stage))
			suffix =inputSuffix;	
		nodeName = CreateBasicNodeName(operation,stage,instr, familyName)+suffix;
		return nodeName;
	}
	
	
	public String CreateLocalNodeName (SCAIEVNode operation, int stage, String instr) {
		String nodeName = "";
		String suffix =localSuffix;	
		nodeName =  CreateBasicNodeName(operation,stage,instr, true)+suffix;
		return nodeName;
	}
	
	public String CreateRegNodeName (SCAIEVNode operation, int stage, String instr) {
		String nodeName = "";
		String suffix = regSuffix;	
		nodeName = CreateBasicNodeName(operation,stage,instr, true)+suffix;
		return nodeName;
	}
	
	public String CreateBasicNodeName(SCAIEVNode operation, int stage, String instr, boolean familyname) {
		if(!instr.isEmpty())
			instr = "_"+instr;	
		String opName = operation.name;
		if(!operation.nameQousinNode.isEmpty() && operation.isAdj() && familyname)
			opName = operation.replaceRadixNameWith(operation.familyName);
		 return opName+instr+"_"+stage;
	}

	
	
	
	
	public String GetDict(DictWords input) {
		return dictionary.get(input);
	}
	
	public String GetDictModule() {
		return dictionary.get(DictWords.module);
	}
	
	public String GetDictEndModule() {
		return dictionary.get(DictWords.endmodule);
	}
	
	public String AllignText(String allignment, String text) {
		String newText = allignment + text;
		newText = newText.replaceAll("(\\r|\\n)","\n"+allignment);
		text = newText;
		return text;
	}


	public Lang getLang() {
		// TODO Auto-generated method stub
		return null;
	}
	
	
	
	
	/// official repo
	public String CreateLocalNodeName (String operation, int stage, String instr) {
		String nodeName = "";
		String suffix = "_s";
		if(!instr.isEmpty())
			instr = "_"+instr;		
		nodeName = operation+instr+"_"+stage+suffix;
		return nodeName;
	}
	

	

	public String  CreateValidEncodingOneInstr(String ISAX, HashMap <String,SCAIEVInstr>  allISAXes, String rdInstr) {
		String body = "";
		body += this.Decode(ISAX, rdInstr, allISAXes);
		return body;		
	}
	
	public String  CreateAddrEncoding(HashSet<String> lookAtISAX, HashMap <String,SCAIEVInstr>  allISAXes, String rdInstr, SCAIEVNode operation) {
		String body = "";
		for (String ISAX  :  lookAtISAX) {
			if(allISAXes.get(ISAX).HasSchedWith(operation, snode -> snode.HasAdjSig(AdjacentNode.addr))) {
				if(!body.isEmpty())
					body += " "+dictionary.get(DictWords.logical_or)+" ";
				body += this.Decode(ISAX, rdInstr, allISAXes);
			}
		}
		return body;		
	}
	public String  CreateValidEncoding(HashSet<String> lookAtISAX, HashMap <String,SCAIEVInstr>  allISAXes, String rdInstr, SCAIEVNode operation) {
		String body = "";
		for (String ISAX  :  lookAtISAX) {
			if(!body.isEmpty())
				body += " "+dictionary.get(DictWords.logical_or)+" ";
			body +=this.Decode(ISAX, rdInstr, allISAXes);
			
			Iterator<Scheduled> snode_iter = allISAXes.get(ISAX).GetSchedWithIterator(operation, _snode -> _snode.HasAdjSig(AdjacentNode.validReq));
			if (snode_iter.hasNext()) {
				body += " "+dictionary.get(DictWords.logical_and)+" (";
				boolean is_first = true;
				do {
					Scheduled snode = snode_iter.next();
					if (!is_first)
						body += " "+dictionary.get(DictWords.logical_or)+" ";
					body += this.CreateLocalNodeName(BNode.GetAdjSCAIEVNode(operation, AdjacentNode.validReq), snode.GetStartCycle(), "");
					is_first = false;
				} while (snode_iter.hasNext());
				body += ")";
			}
			body += ")";
		}
		return body;		
	}
	
		
	public String CreateAllEncoding(HashSet<String> lookAtISAX, HashMap <String,SCAIEVInstr>  allISAXes, String rdInstr) {
		String body = "";
		if(lookAtISAX ==null)
			return this.dictionary.get(DictWords.False);
		for (String ISAX  :  lookAtISAX) {
			if(!ISAX.contains(allISAXes.get(ISAX).noEncodingInstr) &&  !allISAXes.get(ISAX).HasNoOp()) { // DUMMYInstr should not be decoded
				if(!body.isEmpty())
					body +=  " "+dictionary.get(DictWords.logical_or)+" ";
				body += this.Decode(ISAX, rdInstr, allISAXes);;	
			}
		}
		if (body.isEmpty())
			return dictionary.get(DictWords.False);
		return body;		
	}
	
	public String Decode(String ISAX, String instrSig,  HashMap <String,SCAIEVInstr>  allISAXes) {
		String decode = "";
		String [] splitedEncoding = allISAXes.get(ISAX).GetEncodingBasic().split("");
		for(int i=0; i<splitedEncoding.length; i++)
			if(!splitedEncoding[i].equals("-")) {
				if(!decode.isEmpty())
					decode += " "+dictionary.get(DictWords.logical_and)+" ";
				String bitvalue = dictionary.get(DictWords.ZeroBit);
				if(splitedEncoding[i].contains("1"))
					bitvalue = dictionary.get(DictWords.OneBit);
				decode +=  " ( "+ instrSig + " "+dictionary.get(DictWords.bitsselectLeft)+" "+(31-i)+" "+dictionary.get(DictWords.bitsselectRight)+" "+" "+dictionary.get(DictWords.ifeq)+" "+" "+bitvalue+" ) ";
			
			}
		return "( "+decode+" )";
		
	}
	/*public void GenerateAllInterfaces (String topModule,HashMap<String, HashMap<Integer,HashSet<String>>> op_stage_instr, HashMap <String,SCAIEVInstr>  ISAXes, Core core, String specialCase, boolean mem_wr_per_instr ) {
		for(int stage = 0;stage<=core.maxStage;stage++) {
			for (String operation: op_stage_instr.keySet())	 
				if(op_stage_instr.get(operation).containsKey(stage)) {
					String instructionName = "";
					boolean valid = false;
					boolean data = false;
					for(String instruction : op_stage_instr.get(operation).get(stage))  {
						boolean mem_addr = false;
						boolean reg_addr = false;
						if(operation.equals(BNode.RdIValid)) {				 
							instructionName = instruction;
							data = false;
						} 
						if (mem_wr_per_instr && (operation.contains(BNode.WrRD) || (operation.contains("Mem") && !operation.contains(BNode.RdMem)))) {				 
							instructionName = instruction;
							//WrRD, WrMem: data input, control, address per-instruction
							data = false;
						} 
						if(!data && !operation.equals(specialCase)) {
							UpdateInterface(topModule,operation, instructionName,stage,true,false);	
							data = true;
						}
						if (mem_wr_per_instr && operation.contains("Mem")) {				 
							instructionName = instruction;
							valid = false;
							//RdMem: data output global, control and address per-instruction
						} 
						final int _stage = stage;
						Scheduled op_sched = ISAXes.get(instruction).GetNodeWith(operation, node -> node.GetStartCycle() == _stage);
						if(op_sched.GetValidInterf() && !operation.contains("spawn") && !valid ) {
							if(operation.contains("Mem")) {
								UpdateInterface(topModule,BNode.Mem_valid, instructionName,stage,true,false);
							}
							else {
								UpdateInterface(topModule,operation+"_valid", instructionName,stage,true,false);
							}
							valid = true;
						}
						if(op_sched.GetAddrInterf() && !operation.contains("spawn")) {
							if(operation.contains("Mem") && !mem_addr) {
								UpdateInterface(topModule,BNode.Mem_addr, instructionName,stage,true,false);	
								mem_addr = true;
							} else if(operation.contains(BNode.WrRD) && !reg_addr) {
								UpdateInterface(topModule,BNode.WrRD_addr, instructionName,stage,true,false);	
								reg_addr = true;
							}
						}
						
					}
				}
			}
		}
	}*/
	public void GenerateAllInterfaces (String topModule,HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr, HashMap <String,SCAIEVInstr>  ISAXes, Core core, SCAIEVNode specialCase ) {
		System.out.println("INFO: Generating interface for: "+ op_stage_instr);
		HashSet<String> addedFamilyNodes = new HashSet<>();
		for (SCAIEVNode operation: op_stage_instr.keySet())	 {
			for(int stage  : op_stage_instr.get(operation).keySet()) {
				boolean spawnValid = !operation.nameParentNode.isEmpty() && BNode.HasSCAIEVNode(operation.nameParentNode) && operation.isSpawn()  && stage == core.GetNodes().get(BNode.GetSCAIEVNode(operation.nameParentNode)).GetLatest();
				int stageNr = stage;
				if(spawnValid)
					stageNr =  core.maxStage+1;
				if(operation!=specialCase) {
					UpdateInterface(topModule,operation, "",stageNr,true,false);	
					// Generate adjacent signals on the interface
					for(AdjacentNode adjacent : BNode.GetAdj(operation)) {
						SCAIEVNode adjOperation = BNode.GetAdjSCAIEVNode(operation,adjacent);
						if(adjacent != AdjacentNode.none && adjOperation !=specialCase ) {
							if (!adjOperation.nameQousinNode.isEmpty() && BNode.HasSCAIEVNode(adjOperation.nameQousinNode)
								&& !addedFamilyNodes.add(CreateNodeName(adjOperation, stage, ""))) {
								//Prevent doubled interfaces nodes that will go by their family name.
								//Example: RdMem_spawn_validReq and WrMem_spawn_validReq would each be added as Mem_spawn_validReq
								continue;
							}
							UpdateInterface(topModule,adjOperation, "",stageNr,true,false);
						}
					}
				}
			}			 
		 }
	}
	
	public static String OpIfNEmpty(String text, String op) {
		String returnText = text;
		if(!text.isEmpty())
			returnText += " "+op+" ";
		return returnText;
	}
	
	
	public String CreateNodeName (SCAIEVNode operation, int stage, String instr, boolean input) {
		String nodeName = "";
		String suffix = "_o";
		if(input)
			suffix = "_i";
		nodeName = CreateBasicNodeName(operation,stage,instr, false)+suffix;
		return nodeName;
	}
	// TODO to be implemented by sub-class, if the subclass Language wants to use the GenerateAllInterfaces function
	public void UpdateInterface(String top_module,SCAIEVNode operation, String instr, int stage, boolean top_interface, boolean assigReg) {		
		System.out.println("ERR Function called, but not implemented by the language subclass");
	}
}
