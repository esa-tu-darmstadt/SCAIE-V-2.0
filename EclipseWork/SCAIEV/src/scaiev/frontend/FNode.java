package scaiev.frontend;

import java.util.HashSet;

/***********************
 * 
 * Frontend supported nodes
 *
 */
public class FNode{

	// To add a new node, add it here and in GetAllFrontendNodes. No 999 ID allowed, it is considered illegal. Check GetIllegalID()!!
	public static SCAIEVNode WrRD     = new SCAIEVNode("WrRD",32,true) {{DH = true;}};
	public static SCAIEVNode WrMem    = new SCAIEVNode("WrMem",32,true) {{this.elements = 2; }};//2 is a dummy value for the system to know that this interef requires mandatory addr signal to core. {{this.familyName = "Mem";}}; // input data to core
	public static SCAIEVNode RdMem    = new SCAIEVNode("RdMem",32,false){{this.elements = 2; }};//2 is a dummy value for the system to know that this interef requires mandatory addr signal to core. {{this.familyName = "Mem"; nameQousinNode = WrMem.name; WrMem.nameQousinNode = this.name; }}; // output data from core
	public static SCAIEVNode WrPC     = new SCAIEVNode("WrPC",32,true);	
	public static SCAIEVNode RdPC     = new SCAIEVNode("RdPC",32,false);	
	public static SCAIEVNode RdImm     = new SCAIEVNode("RdImm",32,true);	
	public static SCAIEVNode RdRS1    = new SCAIEVNode("RdRS1",32,false);
	public static SCAIEVNode RdRS2    = new SCAIEVNode("RdRS2",32,false);
	public static SCAIEVNode RdInstr  = new SCAIEVNode("RdInstr",32,false) ;
	public static SCAIEVNode RdIValid = new SCAIEVNode("RdIValid",1,false) {{oneInterfToISAX = false;}};//{{RdIValid.forEachInstr = true;}}; // interface to be generated for each Instr
	public static SCAIEVNode RdStall  = new SCAIEVNode("RdStall",1,false);
	public static SCAIEVNode WrStall  = new SCAIEVNode("WrStall",1,true){{oneInterfToISAX = false;}};
	public static SCAIEVNode RdFlush  = new SCAIEVNode("RdFlush",1,false);
	public static SCAIEVNode WrFlush  = new SCAIEVNode("WrFlush",1,true){{oneInterfToISAX = false;}};
	public static SCAIEVNode RdCSR  = new SCAIEVNode("RdCSR",32,false);
	public static SCAIEVNode WrCSR  = new SCAIEVNode("WrCSR",32,true);
	public static SCAIEVNode RdFence  = new SCAIEVNode("RdFence",1,false);
	public static SCAIEVNode RdKill  = new SCAIEVNode("RdKill",1,false);
	
	public  HashSet<SCAIEVNode> user_FNode = new  HashSet<SCAIEVNode>();
	public static String rdName = "Rd";
	public static String wrName = "Wr";
	
	public HashSet<SCAIEVNode>  GetAllFrontendNodes(){
	 HashSet<SCAIEVNode> fnodes = new HashSet<SCAIEVNode>();
		fnodes.add(WrRD);
		fnodes.add(WrMem);
		fnodes.add(RdMem);
		fnodes.add(WrPC);
		fnodes.add(RdPC);
		fnodes.add(RdRS1);
		fnodes.add(RdRS2);
		fnodes.add(RdInstr);
		fnodes.add(RdIValid);
		fnodes.add(RdStall);
		fnodes.add(WrStall);
		fnodes.add(RdFlush);
		fnodes.add(WrFlush);
		fnodes.add(RdCSR);
		fnodes.add(WrCSR);
		fnodes.add(RdFence);
		fnodes.add(RdKill);
		if(!this.user_FNode.isEmpty()) fnodes.addAll(this.user_FNode);
		return fnodes;
	}
	
	public boolean IsUserFNode(SCAIEVNode node) {
		return user_FNode.contains(node);
	}
	
	public void AddUserFNode (String name, int width, int elements) {
		SCAIEVNode RdNode = new SCAIEVNode(rdName+name, width, false);
		RdNode.elements  = elements;
		SCAIEVNode WrNode = new SCAIEVNode(wrName+name, width, true){{DH = true;}};
		WrNode.elements  = elements;
		user_FNode.add(RdNode); 
		user_FNode.add(WrNode); 		
	} 
	
	public SCAIEVNode GetSCAIEVNode(String nodeName) {
		HashSet<SCAIEVNode> fnodes = GetAllFrontendNodes();
		for(SCAIEVNode node : fnodes)
			if(node.name.toLowerCase().contains(nodeName.toLowerCase()))
				return node;
		System.out.println("Critical Warning, nodeName = "+nodeName+" not found in FNode, although it was expected so");
		return new SCAIEVNode(" ",0,false);
	}
	
	
	public boolean HasSCAIEVNode(String nodeName) {
		HashSet<SCAIEVNode> fnodes = GetAllFrontendNodes();
		for(SCAIEVNode node : fnodes)
			if(node.name.contains(nodeName))
				return true;
		System.out.println("Warning, nodeName = "+nodeName+" not found in FNode. HasSCAIEVNode() false");
		return false;
	}
	
	public String GetNameWrNode(SCAIEVNode node) {
		return wrName+node.name.split(rdName)[1];
	}
	
	public String GetNameRdNode(SCAIEVNode node) {
		return rdName+node.name.split(wrName)[1];
	}
	
	public static int GetIllegalID() {
		return " ".hashCode();
	}
	
	
}
