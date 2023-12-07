package scaiev.drc;

import java.util.HashMap;
import java.util.HashSet;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.FNode;
import scaiev.frontend.SCAIEVInstr;
import scaiev.util.Lang;

public class DRC {
    public static boolean errLevelHigh = false;
    private static String errMessage = "WARNING";
    
	public DRC () {		
	}
	
	public static void SetErrLevel(boolean setErrLevelHigh) {
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
	
	public static void CheckSchedErr(Core core,HashMap<String, HashMap<Integer, HashSet<String>>> op_stage_instr) {
		 int end_constrain_cycle;
		 int start_constrain_cycle;
		 int max_stage = core.GetNodes().get(FNode.WrRD).GetLatest();
		 
		 for(int stage = 0;stage<max_stage+2;stage++) {
			 for(String operation : op_stage_instr.keySet()) {
				 if(op_stage_instr.get(operation).containsKey(stage)) {
					 if( !operation.contains("spawn")) {
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
					 } else if(operation.contains("spawn") && (stage==max_stage+1))
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
					 System.out.println("ERR Currently SCAIE-V does not support RD & WR Mem spawn for the same instr. To be modified in near future");
					 System.exit(1);
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
