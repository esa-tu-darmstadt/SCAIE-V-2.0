package scaiev.frontend;

import java.util.HashMap;
import java.util.HashSet;

import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode.AdjacentNode;

public class SCAIEVDRC {

	
	private HashMap <String,SCAIEVInstr>  ISAXes = new HashMap <String,SCAIEVInstr> ();	// database of requested Instructions
	private HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr = new HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>>();
	Core core; 
	
	public SCAIEVDRC(HashMap <String,SCAIEVInstr>  ISAXes,  HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr,Core core ) {
		this.ISAXes = ISAXes; 
		this.op_stage_instr = op_stage_instr;
		this.core = core;
	}
	
	// TODO maybe change it to bool in case of critical err
	public void run() {
		// Dynamic dec mem should store their addr, bc they may arrive out of order and  a FIFO can not handle that. Would make RTL within SCAL too complicated while it can be done cheap in ISAX
		for(SCAIEVNode node : this.op_stage_instr.keySet()) {
			if(node.isSpawn() && node.allowMultipleSpawn) { 
				for(String instr : this.op_stage_instr.get(node).get(core.GetSpawnStage()))
					if(!ISAXes.get(instr).HasNoOp() && ISAXes.get(instr).GetRunsAsDynamicDecoupled() && !ISAXes.get(instr).GetFirstNode(node).HasAdjSig(AdjacentNode.addr))
						System.out.println("DRC. CRITICAL WARNING. For dynamic dec mem, user must provide addr signal. Therefore, please add the is addr option in yaml file. Reason: instr. may arrive out of order and a FIFO can not handle that. That would make RTL within SCAL too complicated while it can be done cheap in ISAX ");
				}	
			}
	}
}
