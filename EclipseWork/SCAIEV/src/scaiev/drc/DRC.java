package scaiev.drc;

import java.util.HashMap;
import java.util.HashSet;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.FNode;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.util.Lang;

public class DRC {
    public static boolean errLevelHigh = false;
    private static String errMessage = "WARNING";
    
	private HashMap <String,SCAIEVInstr>  ISAXes = new HashMap <String,SCAIEVInstr> ();	// database of requested Instructions
	private HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr = new HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>>();
	Core core; 
	private BNode BNodes = new BNode(); 
	

	public DRC(HashMap <String,SCAIEVInstr>  ISAXes,  HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr,Core core, BNode BNodes  ) {
		this.ISAXes = ISAXes; 
		this.op_stage_instr = op_stage_instr;
		this.core = core;
		this.BNodes = BNodes;
	}
	
	public void SetErrLevel(boolean setErrLevelHigh) {
		errLevelHigh = setErrLevelHigh;
		if(errLevelHigh) 
			errMessage = "ERROR";
	}
	
	public static void CheckEncoding(SCAIEVInstr instruction) {
		String instrType = instruction.GetInstrType(); 
		
		if(!(instrType.equals("R"))&&(!instruction.GetEncodingF7(Lang.None).equals("-------"))) {
			instruction.SetEncoding("-------",instruction.GetEncodingF3(Lang.None),instruction.GetEncodingOp(Lang.None),instrType);
			System.out.println(errMessage+"! Instruction not R type, but F7 set. F7 will be set to - ");
			if(errLevelHigh)
				 System.exit(1);
		}
		if((instrType.contains("U"))&&(!instruction.GetEncodingF3(Lang.None).equals("-------"))) {
			instruction.SetEncoding(instruction.GetEncodingF7(Lang.None), "-------",instruction.GetEncodingOp(Lang.None),instrType);
			System.out.println(errMessage+"! Instruction U type, but F3 set. F3 will be set to - ");
			if(errLevelHigh)
				 System.exit(1);
		}			
	}
	
	public void CheckSchedErr(Core core,HashMap<SCAIEVNode, HashMap<Integer, HashSet<String>>> op_stage_instr) {
		 int end_constrain_cycle;
		 int start_constrain_cycle;
		 int max_stage = core.GetNodes().get(FNode.WrRD).GetLatest();
		 
		 for(int stage = 0;stage<max_stage+2;stage++) {
			 for(SCAIEVNode operation : op_stage_instr.keySet()) {
				 if(op_stage_instr.get(operation).containsKey(stage)) {
					 if( !operation.isSpawn()) {
						 if(!core.GetNodes().containsKey(operation)) {
							 System.out.println("ERROR. DRC. Requested operation "+operation+" does not exist ");
							 System.exit(1);					
						 }						 
						 end_constrain_cycle = core.GetNodes().get(operation).GetLatest();
						 start_constrain_cycle = core.GetNodes().get(operation).GetEarliest();
						 if(stage<start_constrain_cycle || stage>end_constrain_cycle) {
							 System.out.println("ERROR. DRC. For an instruction node "+operation+" was scheduled in wrong cycle ");
							 System.exit(1);					
						 }
					 } else if(operation.isSpawn() && (stage==max_stage+1))
						 System.out.println("INFO. DRC. Spawn requirement detected in DRC."); 
					 else {
						 System.out.println("ERR Spawn implemented in wrong cycle. Should have been in  "+(max_stage+1));
						 System.exit(1);	
					 }
				 }
			 }
		 }
		 if(op_stage_instr.containsKey(BNode.WrMem_spawn) && op_stage_instr.containsKey(BNode.RdMem_spawn)) {
			 for(String operation : op_stage_instr.get(BNode.WrMem_spawn).get(max_stage+1)) {
				 if(op_stage_instr.get(BNode.RdMem_spawn).get(max_stage+1).contains(operation)) {
					 System.out.println("DRC. ERR Currently SCAIE-V does not support RD & WR Mem spawn for the same instr. To be modified in near future");
					 System.exit(1);
				 }
			 }
			 
		 }
		 
		 
		// Dynamic dec mem should store their addr, bc they may arrive out of order and  a FIFO can not handle that. Would make RTL within SCAL too complicated while it can be done cheap in ISAX
		for(SCAIEVNode node : this.op_stage_instr.keySet()) {
			if(node.isSpawn() && node.allowMultipleSpawn) {
				for(String instr : this.op_stage_instr.get(node).get(core.GetSpawnStage()))
					if(!ISAXes.get(instr).HasNoOp() && ISAXes.get(instr).GetRunsAsDynamicDecoupled() && (!ISAXes.get(instr).GetFirstNode(node).HasAdjSig(AdjacentNode.addr) || !ISAXes.get(instr).GetFirstNode(node).HasAdjSig(AdjacentNode.validReq)))
						System.out.println("DRC. CRITICAL WARNING. For dynamic dec mem, user must provide addr signal. Therefore, please add the is addr option in yaml file. Reason: instr. may arrive out of order and a FIFO can not handle that. That would make RTL within SCAL too complicated while it can be done cheap in ISAX ");
			}	
			
		
			// mandatory signals should be specified by useer
		    for(SCAIEVNode adjNode : BNodes.GetAdjSCAIEVNodes(node)) {
		    	if(adjNode.mandatory) {
		    		for(String instr : this.op_stage_instr.get(node).get(core.GetSpawnStage()))
		    			if(ISAXes.get(instr).GetFirstNode(node).HasAdjSig(adjNode.getAdj()))
		    				System.out.println("DRC. CRITICAL WARNING. Node "+node+" has mandatory adjacent signal "+ adjNode+" but this adj sig was not reuested in YAML"); 
		    				
		    	}
		    }
		}
	}
	
	public static void CheckEncPresent(HashMap<String,SCAIEVInstr> allInstr) { 
		for(String instrName :allInstr.keySet()) {
			SCAIEVInstr instr = allInstr.get(instrName);
			 System.out.println("INFO. DRC. Instruction: "+instrName+" has encoding: "+instr.GetEncodingString());
			 if(instr.GetEncodingString()=="") {
				 System.out.println(errLevelHigh+". DRC Instruction "+instrName+" does not have encoding.");
				 if(errLevelHigh) 
					 System.exit(1);
			 }			 
		 }			
	}
}
