package scaiev.frontend;

import java.util.HashSet;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.frontend.SCAIEVNode.NodeTypeTag;

/***********************
 * 
 * Frontend supported nodes. 'isInput' in nodes is from the core's point of view.
 *
 */
public class FNode{
	protected static final int datawidth = 32;
	// logging
	protected static final Logger logger = LogManager.getLogger();

	//TODO: CVA6 only.
	public SCAIEVNode ISAX_isReady = new SCAIEVNode("IsReady", 1, true){{oneInterfToISAX = false;}};
	public SCAIEVNode BranchTaken = new SCAIEVNode("BranchTaken",1,true);
	public SCAIEVNode RdReg    = new SCAIEVNode("RdReg",datawidth,false);
	public SCAIEVNode WrReg    = new SCAIEVNode("WrReg",datawidth,true);
	public SCAIEVNode WrJump    = new SCAIEVNode("WrJump",datawidth,true);
	

	public SCAIEVNode WrRD     = new SCAIEVNode("WrRD",datawidth,true) {{DH = true; validBy = AdjacentNode.validData;}};
	public SCAIEVNode WrMem    = new SCAIEVNode("WrMem",datawidth,true) {{elements = 2; validBy = AdjacentNode.validData;}};//2 is a dummy value for the system to know that this interef requires mandatory addr signal to core. {{this.familyName = "Mem";}}; // input data to core
	public SCAIEVNode RdMem    = new SCAIEVNode("RdMem",datawidth,false){{elements = 2; validBy = AdjacentNode.validResp; tags.add(NodeTypeTag.nonStaticReadResult);}};//2 is a dummy value for the system to know that this interef requires mandatory addr signal to core. {{this.familyName = "Mem"; nameCousinNode = WrMem.name; WrMem.nameCousinNode = this.name; }}; // output data from core
	public SCAIEVNode WrPC     = new SCAIEVNode("WrPC",datawidth,true) {{validBy = AdjacentNode.validReq;}};
	public SCAIEVNode RdPC     = new SCAIEVNode("RdPC",datawidth,false) {{tags.add(NodeTypeTag.staticReadResult);}};	
	public SCAIEVNode RdImm     = new SCAIEVNode("RdImm",datawidth,true) {{tags.add(NodeTypeTag.staticReadResult);}};	
	public SCAIEVNode RdRS1    = new SCAIEVNode("RdRS1",datawidth,false) {{tags.add(NodeTypeTag.staticReadResult);}};
	public SCAIEVNode RdRS2    = new SCAIEVNode("RdRS2",datawidth,false) {{tags.add(NodeTypeTag.staticReadResult);}};
	public SCAIEVNode RdRD    = new SCAIEVNode("RdRD",datawidth,false) {{tags.add(NodeTypeTag.staticReadResult);}};
	public SCAIEVNode RdInstr  = new SCAIEVNode("RdInstr",32,false) {{tags.add(NodeTypeTag.staticReadResult);}};
	public SCAIEVNode RdIValid = new SCAIEVNode("RdIValid",1,false) {{oneInterfToISAX = false; tags.add(NodeTypeTag.staticReadResult);}};//{{RdIValid.forEachInstr = true;}}; // interface to be generated for each Instr
	/** SCAL->Core only: Value 1 iff there is an in-flight instruction of this ISAX */
	public SCAIEVNode RdAnyValid = new SCAIEVNode("RdAnyValid",1,true);
	/** Interface for dynamic decoupled ISAXes: Apply WrStall to the stage of the RdFence input until no more instructions of this ISAX are in-flight */
	public SCAIEVNode RdFence = new SCAIEVNode("RdFence",1,false);
	/** Interface for dynamic decoupled ISAXes: Initiate a kill of all in-flight instructions of this ISAX */
	public SCAIEVNode RdKill = new SCAIEVNode("RdKill",1,false);
	public SCAIEVNode RdStall  = new SCAIEVNode("RdStall",1,false) {{oneInterfToISAX = false; tags.add(NodeTypeTag.perStageStatus);}};
	public SCAIEVNode WrStall  = new SCAIEVNode("WrStall",1,true){{oneInterfToISAX = false; tags.add(NodeTypeTag.perStageStatus);}};
	public SCAIEVNode RdFlush  = new SCAIEVNode("RdFlush",1,false) {{oneInterfToISAX = false; tags.add(NodeTypeTag.perStageStatus);}};
	public SCAIEVNode WrFlush  = new SCAIEVNode("WrFlush",1,true){{oneInterfToISAX = false; tags.add(NodeTypeTag.perStageStatus);}};
	public SCAIEVNode RdCSR  = new SCAIEVNode("RdCSR",datawidth,false) {{tags.add(NodeTypeTag.nonStaticReadResult);}};
	public SCAIEVNode WrCSR  = new SCAIEVNode("WrCSR",datawidth,true) {{validBy = AdjacentNode.validReq;}};
	/** Optional interface for spawn ISAXes, should be set to 1. ISAXes should always create the spawn variant of this operation and set validReq accordingly. */
	public SCAIEVNode WrCommit = new SCAIEVNode("WrCommit",1,true); 
	
	public  HashSet<SCAIEVNode> user_FNode = new  HashSet<SCAIEVNode>();
	public static String rdPrefix = "Rd";
	public static String wrPrefix = "Wr";

	private HashSet<SCAIEVNode> allFrontendNodes = null;
	protected void refreshAllNodesSet() {
		allFrontendNodes = null;
	}
	public HashSet<SCAIEVNode>  GetAllFrontendNodes(){



		if (allFrontendNodes != null)
			return allFrontendNodes;
		allFrontendNodes = new HashSet<SCAIEVNode>();
		
		allFrontendNodes.add(ISAX_isReady);
		allFrontendNodes.add(BranchTaken);
		allFrontendNodes.add(RdReg);
		allFrontendNodes.add(WrReg);
		allFrontendNodes.add(WrJump);
		
		allFrontendNodes.add(WrRD);
		allFrontendNodes.add(WrMem);
		allFrontendNodes.add(RdMem);
		allFrontendNodes.add(WrPC);
		allFrontendNodes.add(RdPC);
		allFrontendNodes.add(RdRS1);
		allFrontendNodes.add(RdRS2);
		allFrontendNodes.add(RdRD);
		allFrontendNodes.add(RdInstr);
		allFrontendNodes.add(RdIValid);
		allFrontendNodes.add(RdAnyValid);
		allFrontendNodes.add(RdFence);
		allFrontendNodes.add(RdKill);
		allFrontendNodes.add(RdStall);
		allFrontendNodes.add(WrStall);
		allFrontendNodes.add(RdFlush);
		allFrontendNodes.add(WrFlush);
		allFrontendNodes.add(RdCSR);
		allFrontendNodes.add(WrCSR);
		allFrontendNodes.add(WrCommit);
		if(!this.user_FNode.isEmpty()) allFrontendNodes.addAll(this.user_FNode);
		return allFrontendNodes;

	}
	
	public boolean IsUserFNode(SCAIEVNode node) {
		return user_FNode.contains(node);
	}


	/**
	 * Adds a user-defined register node. The default node 
	 */
	public void AddUserNode (String name, int width, int elements) {
		SCAIEVNode RdNode = new SCAIEVNode(rdPrefix+name, width, false);
		RdNode.elements  = elements;
		RdNode.familyName = name;
		RdNode.tags.add(NodeTypeTag.supportsPortNodes);
		SCAIEVNode WrNode = new SCAIEVNode(wrPrefix+name, width, true){{DH = true;}};
		WrNode.elements  = elements;
		WrNode.familyName = name;
		WrNode.tags.add(NodeTypeTag.supportsPortNodes);
		user_FNode.add(RdNode); 
		user_FNode.add(WrNode);
		if (allFrontendNodes != null) {
			allFrontendNodes.add(RdNode);
			allFrontendNodes.add(WrNode);
		}
	} 
	/**
	 * Adds a user-defined register node additional port.
	 */
	public SCAIEVNode AddUserNodePort (SCAIEVNode userNode, String portName) {
		if (!user_FNode.contains(userNode)) {
			logger.error("AddUserNodePort called on a non-registered user node {}", userNode.name);
			return null;
		}
		SCAIEVNode portNode = SCAIEVNode.makePortNodeOf(userNode, portName);
		user_FNode.add(portNode);
		if (allFrontendNodes != null)
			allFrontendNodes.add(portNode);
		return portNode;
	}
	
	public SCAIEVNode GetSCAIEVNode(String nodeName) {

		HashSet<SCAIEVNode> fnodes = GetAllFrontendNodes();
		for(SCAIEVNode node : fnodes)
			if(node.name.toLowerCase().contains(nodeName.toLowerCase()))
				return node;
		logger.error("nodeName = "+nodeName+" not found in FNode, although it was expected so");
		return new SCAIEVNode(" ",0,false);
	}
	
	
	public boolean HasSCAIEVFNode(String nodeName) {
		HashSet<SCAIEVNode> fnodes = GetAllFrontendNodes();
		for(SCAIEVNode node : fnodes)
			if(node.name.contains(nodeName))
				return true;
		return false;
	}
	public boolean HasSCAIEVNode(String nodeName) {
		return HasSCAIEVFNode(nodeName);
	}
	
	public String GetNameWrNode(SCAIEVNode node) {
		if(node.name.startsWith(rdPrefix))
			return wrPrefix+node.name.split(rdPrefix)[1];
		return node.name;
	}
	
	public String GetNameRdNode(SCAIEVNode node) {
		if(node.name.startsWith(wrPrefix))
			return rdPrefix+node.name.split(wrPrefix)[1];
		return node.name;
	}	
}
