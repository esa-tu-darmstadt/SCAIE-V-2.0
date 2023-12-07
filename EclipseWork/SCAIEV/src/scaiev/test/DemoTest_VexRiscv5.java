package scaiev.test;

import java.util.HashSet;

import scaiev.SCAIEV;
import scaiev.frontend.FNode;
import scaiev.frontend.FrontendNodeException;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.test.DemoTest.TestScenario;

public class DemoTest_VexRiscv5 {

public static void main(String[] args) {
		// Instantiate a SCAIE-V Object
		SCAIEV shim = new SCAIEV(); 
		// User needs a MAC instruction with the given encoding
		SCAIEVInstr isax1  = shim.addInstr("ISAX1","-------", "001", "0001011", "R");
		isax1.PutSchedNode(FNode.RdRS1, 2); 
		isax1.PutSchedNode(FNode.RdRS2, 2);
		isax1.PutSchedNode(FNode.WrRD, 2); 

		SCAIEVInstr isax2  = shim.addInstr("ISAX2","-------", "011", "0001011", "R");
		isax2.PutSchedNode(FNode.RdRS1, 3); 
		isax2.PutSchedNode(FNode.RdRS2, 3);
		isax2.PutSchedNode(FNode.RdIValid, 3);
		isax2.PutSchedNode(FNode.WrRD, 3, AdjacentNode.validReq); // Wr Register file
		
		SCAIEVInstr isax3  = shim.addInstr("ISAX3","-------", "010", "0001011", "R");
		HashSet<AdjacentNode> valid_addr = new HashSet<AdjacentNode>();
		valid_addr.add(AdjacentNode.validReq);
		valid_addr.add(AdjacentNode.addr);	
		isax3.PutSchedNode(FNode.WrMem, 2, valid_addr); // memory write
		isax3.PutSchedNode(FNode.RdInstr, 2);
		isax3.PutSchedNode(FNode.RdIValid, 2);
		
		SCAIEVInstr isax4  = shim.addInstr("ISAX4","-------", "010", "1111011", "R");
		isax4.PutSchedNode(FNode.RdMem, 2); 
		
		SCAIEVInstr isax5  = shim.addInstr("ISAX5","-------", "101", "0001011", "R");
		valid_addr.add(AdjacentNode.validResp); // add also valid resp for spawn
		isax5.PutSchedNode(FNode.RdMem, 7,valid_addr); 
		isax5.PutSchedNode(FNode.RdIValid, 2);
		isax5.PutSchedNode(FNode.WrRD, 6);
		isax5.SetAsDecoupled(true); // for RdMem
		
		
		SCAIEVInstr isax6  = shim.addInstr("ISAX6","-------", "110", "0101011", "R");
		isax6.PutSchedNode(FNode.WrMem, 6, valid_addr); 
		isax6.SetAsDecoupled(true);
		
		SCAIEVInstr isax7  = shim.addInstr("ISAX7","-------", "111", "0101011", "R");
		isax7.PutSchedNode(FNode.WrStall, 3); 
		isax7.PutSchedNode(FNode.RdStall, 3); 
		isax7.PutSchedNode(FNode.WrPC, 2, AdjacentNode.validReq);
		isax7.PutSchedNode(FNode.RdPC, 1);
		
		SCAIEVInstr isax8  = shim.addInstr("ISAX8","-------", "000", "0101011", "R");
		isax8.PutSchedNode(FNode.RdRS1, 2); 
		isax8.PutSchedNode(FNode.RdIValid, 2); 
		
		
		// Generate SCAIE-V for a 5 stage Virtual Core
		try {
			//shim.Generate("RandomCore");
			shim.Generate("VexRiscv_5s", null);
		} catch (FrontendNodeException e) {
			e.printStackTrace();
		}
	
	}

}
