package scaiev.util;

import java.util.HashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.backend.CoreBackend;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.Scheduled;
import scaiev.pipeline.PipelineStage;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;

public class SpinalHDL extends GenerateText{
	// logging
	protected static final Logger logger = LogManager.getLogger();
	
	FileWriter toFile;
	public String tab = "    ";
	
	public SpinalHDL(BNode user_BNode, FileWriter toFile, CoreBackend core) {
		super(user_BNode);
		// initialize dictionary 
		dictionary.put(DictWords.module,"class");
		dictionary.put(DictWords.endmodule,"}");
		dictionary.put(DictWords.reg,"Reg");
		dictionary.put(DictWords.wire,"UInt");		
		dictionary.put(DictWords.assign,"");
		dictionary.put(DictWords.assign_eq,":=");
		dictionary.put(DictWords.logical_or,"||");
		dictionary.put(DictWords.logical_and,"&&");
		dictionary.put(DictWords.bitwise_or,"|");
		dictionary.put(DictWords.in,"in");
		dictionary.put(DictWords.out,"out");
		dictionary.put(DictWords.False,"False");
		dictionary.put(DictWords.True,"True");
		this.toFile = toFile;
		tab = toFile.tab;
		this.coreBackend = core;
	}
	
	
	public void CloseBrackets () {
		this.toFile.nrTabs--;
		this.toFile.UpdateContent(this.currentFile, "}");		
	}
	

	public String CreateInterface(SCAIEVNode operation, PipelineStage stage, String instr) {
		String in = dictionary.get(DictWords.out);
		if(operation.isInput)
			in =dictionary.get(DictWords.in);
		String nodeName = CreateNodeName(operation, stage,instr);
		String size = "";
		String nodeDataT = coreBackend.NodeDataT(operation, stage);
		if (nodeDataT == null) {
			logger.error("CreateInterface on unknown node " + operation.name + "_" + stage.getName());
			nodeDataT = "Bits";
		}
		if(operation.size>0 && !nodeDataT.contains("Bool")) // better use coreBackend for size, maybe some cores have wider interf
			size = operation.size+" bits";
		String interfaceText = "val "+nodeName+" = "+in+" "+nodeDataT+"("+size+")\n"
				+ nodeName+".setName(\""+nodeName+"\")\n";
		return interfaceText;
	}
	
	/**
	 * 
	 * @param operation - node name
	 * @param stage - stage nr
	 * @param instr - instruction Name (for IValid for example)
	 * @param conditional -  is it a "when(Signal) {assignSignal = True}" scenario?
	 * @return
	 */
	public String CreateAssignToISAX(SCAIEVNode operation, PipelineStage stage, String instr, boolean conditional) { 
		String assignText = "";
		String nodeName = CreateNodeName(operation, stage,instr);
		if(!operation.isInput)
			assignText = "io."+ nodeName + " := "+ coreBackend.NodeAssign(operation, stage)+";\n";
		else 
			assignText = coreBackend.NodeAssign(operation, stage)+ " := "+ "io."+ nodeName +";\n";
		if(!instr.isEmpty() && operation.equals(BNode.RdIValid))
			assignText = "io."+ nodeName + " := "+coreBackend.NodeAssign(operation, stage).replaceAll("IS_ISAX","IS_"+instr)+"\n";
		if(conditional && coreBackend.NodeIsInput(operation, stage))
			assignText = "when(io."+nodeName+") {\n"+this.tab+coreBackend.NodeAssign(operation, stage)+ " := "+ "True;\n}\n";
		return assignText;
	}
	
	
	public String CreateClauseValid(HashMap<String,SCAIEVInstr> ISAXes, SCAIEVNode operation, PipelineStage stage, String pluginStage) {
		String clause = "";
		 boolean first = true;
		 for(String instructionName : ISAXes.keySet()) {
			 Scheduled op_sched = ISAXes.get(instructionName).GetSchedWith(operation, snode -> stage.getStagePos() == snode.GetStartCycle());
			 if(op_sched != null) { 
				 if(!first)
					 clause += " "+dictionary.get(DictWords.logical_or)+" ";
				 first = false;
				 if(!pluginStage.isEmpty())
					 clause += pluginStage+".";
				 if(!op_sched.HasAdjSig(AdjacentNode.validReq))
					 clause += "input(IS_"+instructionName+")";
				 else 
					 clause += "(input(IS_"+instructionName+") && io."+CreateNodeName(BNode.GetSCAIEVNode(operation+BNode.validSuffix),stage,"")+")";
			 }
		 }
		 return clause;
	}
	
	public String CreateClause(HashMap<String,SCAIEVInstr> ISAXes, SCAIEVNode operation, PipelineStage stage, String pluginStage) {
		String clause = "";
		 boolean first = true;
		 for(String instructionName : ISAXes.keySet()) {
			 Scheduled op_sched = ISAXes.get(instructionName).GetSchedWith(operation, snode -> stage.getStagePos() == snode.GetStartCycle());
			 if(op_sched != null) { 
				 if(!first)
					 clause += " "+dictionary.get(DictWords.logical_or)+" ";
				 first = false;
				 if(!pluginStage.isEmpty())
					 clause +=pluginStage+".";
				 clause += "input(IS_"+instructionName+")";
				}
		 }
		 return clause;
	}
	
	public String CreateClauseAddr(HashMap<String,SCAIEVInstr> ISAXes, SCAIEVNode operation, PipelineStage stage, String pluginStage) {
		String clause = "";
		 boolean first = true;
		 for(String instructionName : ISAXes.keySet()) {
			 Scheduled op_sched = ISAXes.get(instructionName).GetSchedWith(operation, snode -> stage.getStagePos() == snode.GetStartCycle());
			 if(op_sched != null && op_sched.HasAdjSig(AdjacentNode.addr)) {  
				 if(!first)
					 clause += " "+dictionary.get(DictWords.logical_or)+" ";
				 if(!pluginStage.isEmpty())
					 clause += pluginStage+".";
				 clause += "input(IS_"+instructionName+")";
				 first = false;
			 }
		 }
		 return clause;
	}
	

	public String CreateDeclReg(SCAIEVNode operation, PipelineStage stage, String instr) {
		String decl = "";
		String size = "";
		String init = "0";
		String input = "_o";
		if(coreBackend.NodeIsInput(operation, stage))
			input = "_i";
		if(operation.size>1)
			size = coreBackend.NodeDataT(operation, stage) + "("+operation.size+" bits)"; 
		else { //1b (Bool?)
			size =  coreBackend.NodeDataT(operation, stage);
			init = "False"; // considering Bool...
		}
		decl = "val "+CreateNodeName(operation,stage,instr).replace(input,"_reg")+" = Reg("+size+") init("+init+");\n";
		return decl;
	}
	
	public String CreateDeclSig(SCAIEVNode operation, PipelineStage stage, String instr) {
		String decl = "";
		String size = "";
		if(operation.size>1)
			size = coreBackend.NodeDataT(operation, stage) + "("+operation.size+" bits)"; 
		else 
			size =  coreBackend.NodeDataT(operation, stage);	
		decl = "val "+CreateNodeName(operation,stage,instr)+" = "+size+";\n";
		return decl;		
	}
	
	public String CreateSpawnLogicWrRD(PipelineStage stage) {
		String body = "";
		body += "when("+this.CreateNodeName(BNode.WrRD_spawn_valid, stage, "")+") {\n"
			 +	tab+ "writeStage.arbitration.isRegFSpawn := True\n"
			 +  tab+ "writeStage.output(REGFILE_WRITE_DATA) := "+this.CreateNodeName(BNode.WrRD_spawn, stage, "")+"\n" 
			 +  tab+"writeStage.output(INSTRUCTION) := ((11 downto 7) ->"+this.CreateNodeName(BNode.WrRD_spawn_addr, stage, "")+", default -> false)\n"
			 + "}\n"; 
		return body;
		
	}
	
	
	
	public String CreateSpawnCMDMem(SCAIEVNode operation,PipelineStage stage, int tabNr) {
		String body = "";
		String write = "True";
		if(operation.equals(BNode.RdMem_spawn))
			write = "False";
		String wrMemData = "";
		if(operation.equals(BNode.WrMem_spawn))
			wrMemData= tab.repeat(tabNr+1)+"dBusAccess.cmd.data  := "+CreateNodeName(BNode.WrMem_spawn,stage,"")+"\n";
		body = 	  tab.repeat(tabNr)  +"when("+ CreateNodeName(BNode.GetAdjSCAIEVNode(operation, AdjacentNode.validReq).get(),stage,"")+"){\n"
				+ tab.repeat(tabNr+1)+"dBusAccess.cmd.valid := True \n"
				+ tab.repeat(tabNr+1)+"dBusAccess.cmd.size := 2\n"
		 		+ tab.repeat(tabNr+1)+"dBusAccess.cmd.write := "+write+"\n"
		 		+ wrMemData
		 		+ tab.repeat(tabNr+1)+"dBusAccess.cmd.address :="+CreateNodeName(BNode.GetAdjSCAIEVNode(operation, AdjacentNode.addr).get(),stage,"")+"\n"
		 		+ tab.repeat(tabNr)  +"}\n";
		 return body;
	}
	
	public String CreateSpawnCMDRDYMem(SCAIEVNode operation,PipelineStage stage, int tabNr) {
		String body = "";
		if(operation.equals(BNode.WrMem_spawn))
			body =    tab.repeat(tabNr)  +"when(io."+ CreateNodeName(BNode.WrMem_spawn_write,stage,"")+") {\n"
					+ tab.repeat(tabNr+1)+"state := State.CMD\n"
					+ tab.repeat(tabNr+1)+"io."+CreateNodeName(BNode.WrMem_spawn_validResp, stage, "")+":= True\n"
					+ tab.repeat(tabNr)  +"}\n";
		else 
			body =    tab.repeat(tabNr)  +"when("+this.CreateNodeName(BNode.RdMem_spawn_validReq, stage, "")+") {\n"
					+ tab.repeat(tabNr+1)+"state := State.RESPONSE\n"
					+ tab.repeat(tabNr)  +"}\n";
		 return body;
	}
	public String CreateSpawnRSPRDYMem(PipelineStage stage, int tabNr) {
		String body = "";
		body = tab.repeat(tabNr)+"when("+this.CreateNodeName(BNode.RdMem_spawn_validReq, stage, "")+") {\n"
				    + tab.repeat(tabNr+1)+"io."+CreateNodeName(BNode.RdMem_spawn, stage, "") +":= dBusAccess.rsp.data\n"
				    + tab.repeat(tabNr+1)+"io."+CreateNodeName(BNode.RdMem_spawn_validResp, stage, "") +" := True\n"
					+ tab.repeat(tabNr)+"}\n";
		 return body;
	}


	@Override
	public Lang getLang() {
		return Lang.SpinalHDL;
	}
	

	
}
