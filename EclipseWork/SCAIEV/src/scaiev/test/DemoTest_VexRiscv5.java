package scaiev.test;

import java.util.HashSet;

import scaiev.SCAIEV;
import scaiev.frontend.FrontendNodeException;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode.AdjacentNode;

public class DemoTest_VexRiscv5 {

public static void main(String[] args) {
		//POSSIBLY OUTDATED
		
		// Instantiate a SCAIE-V Object
		SCAIEV shim = new SCAIEV(); 
		// User needs a MAC instruction with the given encoding
		SCAIEVInstr isax1  = shim.addInstr("ISAX1","-------", "001", "0001011", "R");
		isax1.PutSchedNode(shim.FNodes.RdRS1, 2); 
		isax1.PutSchedNode(shim.FNodes.RdRS2, 2);
		isax1.PutSchedNode(shim.FNodes.WrRD, 2); 

		SCAIEVInstr isax2  = shim.addInstr("ISAX2","-------", "011", "0001011", "R");
		isax2.PutSchedNode(shim.FNodes.RdRS1, 2); 
		isax2.PutSchedNode(shim.FNodes.RdRS2, 2);
		isax2.PutSchedNode(shim.FNodes.RdIValid, 2);
		isax2.PutSchedNode(shim.FNodes.WrRD, 3, AdjacentNode.validReq); // Wr Register file
		
		SCAIEVInstr isax3  = shim.addInstr("ISAX3","-------", "010", "0001011", "R");
		HashSet<AdjacentNode> valid_addr = new HashSet<AdjacentNode>();
		valid_addr.add(AdjacentNode.validReq);
		//valid_addr.add(AdjacentNode.addr);	
		isax3.PutSchedNode(shim.FNodes.WrMem, 0, valid_addr); // memory write
		isax3.PutSchedNode(shim.FNodes.RdInstr, 2);
		isax3.PutSchedNode(shim.FNodes.RdIValid, 2);
		
		SCAIEVInstr isax4  = shim.addInstr("ISAX4","-------", "010", "1111011", "R");
		isax4.PutSchedNode(shim.FNodes.RdMem, 0); 
		
		SCAIEVInstr isax5  = shim.addInstr("ISAX5","-------", "101", "0001011", "R");
		valid_addr.add(AdjacentNode.validResp); // add also valid resp for spawn
		isax5.PutSchedNode(shim.FNodes.RdMem, 7,valid_addr); 
		isax5.PutSchedNode(shim.FNodes.RdIValid, 2);
		isax5.PutSchedNode(shim.FNodes.WrRD, 6);
		isax5.SetAsDecoupled(true); // for RdMem
		
		
		SCAIEVInstr isax6  = shim.addInstr("ISAX6","-------", "110", "0101011", "R");
		isax6.PutSchedNode(shim.FNodes.WrMem, 6, valid_addr); 
		isax6.SetAsDecoupled(true);
		
		SCAIEVInstr isax7  = shim.addInstr("ISAX7","-------", "111", "0101011", "R");
		isax7.PutSchedNode(shim.FNodes.WrStall, 2); 
		isax7.PutSchedNode(shim.FNodes.RdStall, 2); 
		isax7.PutSchedNode(shim.FNodes.WrPC, 2, AdjacentNode.validReq);
		isax7.PutSchedNode(shim.FNodes.RdPC, 1);
		
		SCAIEVInstr isax8  = shim.addInstr("ISAX8","-------", "000", "0101011", "R");
		isax8.PutSchedNode(shim.FNodes.RdRS1, 2); 
		isax8.PutSchedNode(shim.FNodes.RdIValid, 2); 
		
		
		// Generate SCAIE-V for a 5 stage Virtual Core
		try {
			shim.Generate("VexRiscv_5s", null);
		} catch (FrontendNodeException e) {
			e.printStackTrace();
		}
	
	}

}
