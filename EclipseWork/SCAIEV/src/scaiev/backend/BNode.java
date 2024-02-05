package scaiev.backend;

import java.util.HashSet;

import scaiev.frontend.FNode;
import scaiev.frontend.SCAIEVNode;
import  scaiev.frontend.SCAIEVNode.AdjacentNode;

/***********************
 * 
 * Backend supported SCAIEV nodes
 *
 */
public class BNode extends FNode{
	// To add a new node, add it here and in GetAllBackNodes. 
	// If you need a node to have valid and addr signals, add validSuffix & addrSuffix to their names. This suffix is used in GenerateText class and other classes. If you don't use this pattern, some functions might not work. This could be improved in future versions of the tool
	// Please consider that _valid , _addr nodes are used in backend just for the names , in order to generate interf text & logic. They will not be in op_stage_instr, because it would be redundant. We would have same data stored multiple times in op_stage_instr 
	public static String validSuffix = AdjacentNode.validReq.suffix;
	public static String addrSuffix =AdjacentNode.addr.suffix;
	
	public static SCAIEVNode WrRD_valid        = new SCAIEVNode(FNode.WrRD   	, AdjacentNode.validReq	, 1 , true, false); 
	public static SCAIEVNode WrRD_validData    = new SCAIEVNode(FNode.WrRD   	, AdjacentNode.validData, 1 , true, false) {{noInterfToISAX = true; DH = true;}}; 
	//public static SCAIEVNode WrRD_addr         = new SCAIEVNode(FNode.WrRD		, AdjacentNode.addr		, 5 , true, false);
	public static SCAIEVNode WrRD_addr_valid   = new SCAIEVNode(BNode.WrRD	, AdjacentNode.addrReq	, 5 , true, false);
	public static SCAIEVNode RdMem_validReq    = new SCAIEVNode(FNode.RdMem		, AdjacentNode.validReq	, 1 , true, false); 
	public static SCAIEVNode WrMem_validReq    = new SCAIEVNode(FNode.WrMem		, AdjacentNode.validReq	, 1 , true, false); 
	public static SCAIEVNode RdMem_addr        = new SCAIEVNode(FNode.RdMem		, AdjacentNode.addr		, 32, true, false);
	public static SCAIEVNode RdMem_addr_valid  = new SCAIEVNode(BNode.RdMem		, AdjacentNode.addrReq		, 1, true, false){{this.attachedNode = RdMem_addr.name;}};
	public static SCAIEVNode WrMem_addr        = new SCAIEVNode(FNode.WrMem		, AdjacentNode.addr		, 32, true, false);
	public static SCAIEVNode WrMem_addr_valid  = new SCAIEVNode(BNode.WrMem		, AdjacentNode.addrReq		, 1, true, false){{this.attachedNode = WrMem_addr.name;}};
	//public static SCAIEVNode RdMem_validResp   = new SCAIEVNode(FNode.RdMem		, AdjacentNode.validResp, 1 , false, false) ; // valdir read data
	public static SCAIEVNode WrPC_valid        = new SCAIEVNode(FNode.WrPC		, AdjacentNode.validReq	, 1 , true, false);
	public static SCAIEVNode WrPC_spawn        = new SCAIEVNode(FNode.WrPC	    , AdjacentNode.none		, 32, true, true) {{this.oneInterfToISAX = true; this.allowMultipleSpawn = false; }};
	public static SCAIEVNode WrPC_spawn_valid  = new SCAIEVNode(WrPC_spawn		, AdjacentNode.validReq	, 1 , true, true) {{this.oneInterfToISAX = true; this.allowMultipleSpawn = false;}};
	public static SCAIEVNode WrRD_spawn        = new SCAIEVNode(FNode.WrRD		, AdjacentNode.none		, 32, true, true) {{DH = true;this.elements = 32;}};
	public static SCAIEVNode WrRD_spawn_valid  = new SCAIEVNode(WrRD_spawn		, AdjacentNode.validReq	, 1 , true, true); 					
	public static SCAIEVNode WrRD_spawn_validResp    = new SCAIEVNode(WrRD_spawn, AdjacentNode.validResp	, 1 , false, true) {{oneInterfToISAX = false;}};
	public static SCAIEVNode WrRD_spawn_addr         = new SCAIEVNode(WrRD_spawn, AdjacentNode.addr		, 5 , true, true){{noInterfToISAX = true; mandatory = true;}};
//	public static SCAIEVNode WrRD_spawn_addrCommited = new SCAIEVNode(WrRD_spawn, AdjacentNode.addrCommited, 5 , true, false);
	public static SCAIEVNode WrRD_spawn_allowed      = new SCAIEVNode(WrRD_spawn, AdjacentNode.spawnAllowed, 1, false, true);
	
	public static SCAIEVNode RdMem_spawn           = new SCAIEVNode(FNode.RdMem  , AdjacentNode.none		, 32, false, true) {{this.familyName = "Mem";oneInterfToISAX = false; this.nameQousinNode = "WrMem_spawn"; this.allowMultipleSpawn = true;}}; // TODO unstable solution with nameQousin here
	public static SCAIEVNode RdMem_spawn_validReq  = new SCAIEVNode(RdMem_spawn	 , AdjacentNode.validReq	, 1, true, true); 
	public static SCAIEVNode RdMem_spawn_validResp = new SCAIEVNode(RdMem_spawn	 , AdjacentNode.validResp	, 1, false, true) {{oneInterfToISAX = false;}};
	public static SCAIEVNode RdMem_spawn_addr      = new SCAIEVNode(RdMem_spawn  , AdjacentNode.addr		, 32, true, true);
	public static SCAIEVNode RdMem_spawn_rdAddr    = new SCAIEVNode(RdMem_spawn  , AdjacentNode.rdAddr		, 32, false, true) {{noInterfToISAX = true; mustToCore = true;}};
	public static SCAIEVNode RdMem_spawn_write     = new SCAIEVNode(RdMem_spawn  , AdjacentNode.isWrite     , 1, true, true) {{noInterfToISAX = true; mustToCore = true;}};
	public static SCAIEVNode RdMem_spawn_allowed   = new SCAIEVNode(RdMem_spawn  , AdjacentNode.spawnAllowed, 1, false, true);
	
	public static SCAIEVNode WrMem_spawn           = new SCAIEVNode(FNode.WrMem  , AdjacentNode.none		, 32, true, true) {{this.familyName = "Mem"; nameQousinNode = RdMem_spawn.name; this.allowMultipleSpawn = true; }};
	public static SCAIEVNode WrMem_spawn_validReq  = new SCAIEVNode(WrMem_spawn  , AdjacentNode.validReq	, 1, true, true); 
	public static SCAIEVNode WrMem_spawn_addr      = new SCAIEVNode(WrMem_spawn  , AdjacentNode.addr		, 32, true, true);
	public static SCAIEVNode WrMem_spawn_rdAddr    = new SCAIEVNode(WrMem_spawn  , AdjacentNode.rdAddr		, 32, false, true);// {{noInterfToISAX = true; mustToCore = true;}};
	public static SCAIEVNode WrMem_spawn_validResp = new SCAIEVNode(WrMem_spawn  , AdjacentNode.validResp	, 1, false, true) {{oneInterfToISAX = false;  mustToCore = true;}};
	public static SCAIEVNode WrMem_spawn_write     = new SCAIEVNode(WrMem_spawn  , AdjacentNode.isWrite     , 1, true, true) {{noInterfToISAX = true; mustToCore = true;}};
	public static SCAIEVNode WrMem_spawn_allowed   = new SCAIEVNode(WrMem_spawn  , AdjacentNode.spawnAllowed, 1, false, true);
	
	public static SCAIEVNode WrCSR_valid           = new SCAIEVNode(FNode.WrCSR  , AdjacentNode.validReq, 1, true, false);
	
	public static SCAIEVNode commited_rd_spawn       = new SCAIEVNode("Commited_rd_spawn", 5, false);
	public static SCAIEVNode commited_rd_spawn_valid = new SCAIEVNode(commited_rd_spawn, AdjacentNode.validReq, 1, false, false);
	
	public static SCAIEVNode cancel_from_user        = new SCAIEVNode("cancel_frUser", 5, false);
	public static SCAIEVNode cancel_from_user_valid  = new SCAIEVNode(cancel_from_user, AdjacentNode.validReq, 1, false, false);
	
	
	// Local Signal Names used in all cores for logic  of spawn
	public static SCAIEVNode ISAX_spawnAllowed    = new SCAIEVNode("ISAX_spawnAllowed", 1, false) {{noInterfToISAX = true;}};
	
	public static SCAIEVNode ISAX_spawnStall_regF_s = new SCAIEVNode("isax_spawnStall_regF_s", 1, false);
	public static SCAIEVNode ISAX_spawnStall_mem_s  = new SCAIEVNode("isax_spawnStall_mem_s", 1, false);
	
	public  HashSet<SCAIEVNode> user_BNode = new  HashSet<SCAIEVNode>();
	
	public void AddUserBNode (String name, int width, int elements) {
		SCAIEVNode RdNode = new SCAIEVNode(rdName+name, width, false);
		RdNode.elements  = elements;
		SCAIEVNode WrNode = new SCAIEVNode(wrName+name, width, true) {{DH = true;}};
		WrNode.elements  = elements;
		user_FNode.add(RdNode); 
		user_FNode.add(WrNode);
		user_BNode.add(RdNode); 
		user_BNode.add(WrNode);
		
		int addr_size = (int) Math.ceil((Math.log10(elements)/Math.log10(2)));
		user_BNode.add(new SCAIEVNode(WrNode,AdjacentNode.validReq	, 1, true, false));
		user_BNode.add(new SCAIEVNode(RdNode,AdjacentNode.validReq	, 1, true, false) {{noInterfToISAX = true;}});
		user_BNode.add(new SCAIEVNode(WrNode,AdjacentNode.validData	, 1, true, false) {{noInterfToISAX = true; DH = true;}});
		user_BNode.add(new SCAIEVNode(RdNode,AdjacentNode.addr	, addr_size, true, false));
		user_BNode.add(new SCAIEVNode(WrNode,AdjacentNode.addr	, addr_size, true, false));
		SCAIEVNode RdNode_AddrValid = new SCAIEVNode(RdNode,AdjacentNode.addrReq	, 1, true, false) {{noInterfToISAX = true;this.attachedNode = RdMem_addr.name;}};
		RdNode_AddrValid.elements = elements;
		SCAIEVNode WrNode_AddrValid = new SCAIEVNode(WrNode,AdjacentNode.addrReq	, 1, true, false) {{noInterfToISAX = true;this.attachedNode = RdMem_addr.name;}};
		WrNode_AddrValid.elements = elements;	
		user_BNode.add(WrNode_AddrValid);	
		user_BNode.add(RdNode_AddrValid);
		
		// Spawn just for write 
		// Set allowMultipleSpawn COULD BE SET HERE to false, and in AddUserNodesToCore(..) we check how many isaxes need spawn and update this param. For the moment by default true to generate in SCAL the fire logic
		SCAIEVNode  WrNode_spawn = new SCAIEVNode(WrNode ,       AdjacentNode.none		, width, true, true) {{this.oneInterfToISAX = false; DH = true; this.allowMultipleSpawn = true;}};
		user_BNode.add(WrNode_spawn);
		user_BNode.add(new SCAIEVNode(WrNode_spawn  , AdjacentNode.validReq	, 1, true, true){{this.oneInterfToISAX = false; this.allowMultipleSpawn = true;}}); 
		if(elements>1) // Don.t generate addr signal for spawn if not required
			user_BNode.add(new SCAIEVNode(WrNode_spawn  , AdjacentNode.addr		, addr_size, true, true){{this.oneInterfToISAX = false; this.allowMultipleSpawn = true; this.mandatory = true;}});
		user_BNode.add(new SCAIEVNode(WrNode_spawn  , AdjacentNode.validResp	, 1, false, true) {{oneInterfToISAX = false;}});
	//	user_BNode.add(new SCAIEVNode(WrNode_spawn  , AdjacentNode.spawnAllowed, 1, false, true)  {{noInterfToISAX = false;}}); // For the moment by default always allowed for internal state. Yet, core spawnAllowed must still be checked due to stalling	

		// Read spawn for direct reads (no DH ) 
		SCAIEVNode  RdNode_spawn = new SCAIEVNode(RdNode ,       AdjacentNode.none		, width, false, true) {{this.oneInterfToISAX = true; DH = false;}};
		user_BNode.add(RdNode_spawn);
		
	} 
	
	public boolean IsUserBNode(SCAIEVNode node) {
		//System.out.println("user node "+user_BNode);
		return user_BNode.contains(node);
	}
	public  HashSet<SCAIEVNode>  GetAllBackNodes(){
		HashSet<SCAIEVNode> bnodes = GetAllFrontendNodes();
		if(!this.user_BNode.isEmpty()) bnodes.addAll(this.user_BNode);
		bnodes.add(WrRD_valid);
		bnodes.add(WrRD_validData);
	//	bnodes.add(WrRD_addr);
		bnodes.add(RdMem_validReq);
		bnodes.add(RdMem_addr);
		bnodes.add(RdMem_addr_valid);		
		//bnodes.add(RdMem_validResp);
		bnodes.add(WrMem_validReq);
		bnodes.add(WrMem_addr);
		bnodes.add(WrMem_addr_valid);
		bnodes.add(WrPC_valid);
		bnodes.add(WrPC_spawn_valid);
		bnodes.add(WrPC_spawn);
		bnodes.add(WrRD_spawn_valid);
		bnodes.add(WrRD_spawn_addr);
		bnodes.add(WrRD_spawn_validResp);
		bnodes.add(WrRD_spawn);
		//bnodes.add(WrRD_spawn_allowed);
		bnodes.add(RdMem_spawn_validResp);
		bnodes.add(RdMem_spawn_addr);
		bnodes.add(RdMem_spawn_rdAddr);
		bnodes.add(RdMem_spawn);
		bnodes.add(RdMem_spawn_write);
		bnodes.add(RdMem_spawn_validReq);
		//bnodes.add(RdMem_spawn_allowed);
		bnodes.add(WrMem_spawn);
		bnodes.add(WrMem_spawn_validReq);
		bnodes.add(WrMem_spawn_validResp);
		bnodes.add(WrMem_spawn_addr);		
		bnodes.add(WrMem_spawn_rdAddr);
		bnodes.add(WrMem_spawn_write);
		//bnodes.add(WrMem_spawn_allowed);
		
		bnodes.add(WrCSR_valid);
		bnodes.add(commited_rd_spawn);
		bnodes.add(commited_rd_spawn_valid);
		bnodes.add(ISAX_spawnAllowed);
		bnodes.add(ISAX_spawnStall_regF_s);
		bnodes.add(ISAX_spawnStall_mem_s);
		return bnodes;
	}
	
	

	public SCAIEVNode GetSCAIEVNode(String nodeName) {
		HashSet<SCAIEVNode> bnodes = GetAllBackNodes();
		for(SCAIEVNode node : bnodes)
			if(node.name.equals(nodeName))		 		
				return node;
 		
		//System.out.println("Critical Warning, nodeName = "+nodeName+" not found in BNode, although it was expected so");
		return new SCAIEVNode("",0,false);
	}
	

	
	public  boolean HasSCAIEVNode(String nodeName) {
		HashSet<SCAIEVNode> bnodes = GetAllBackNodes();
		for(SCAIEVNode node : bnodes)
			if(node.name.contains(nodeName))
				return true;
		System.out.println("Warning, nodeName = "+nodeName+" not found in BNode. HasSCAIEVNode() false");
		return false;
	}
	
	/**
	 * Returns adjacent nodes of given SCAIEVNode. For exp. in case of WrPC: validReq (valid request bit). Returns a SCAIEVNode
	 * @return
	 */
	public  HashSet<SCAIEVNode> GetAdjSCAIEVNodes(SCAIEVNode look4Node){
		 HashSet<SCAIEVNode> returnSet = new  HashSet<SCAIEVNode>(); 
		 for(SCAIEVNode checkNode : GetAllBackNodes()) {
			 if(checkNode.HasParentNode(look4Node))
				 returnSet.add(checkNode);
		 }
		 
		 return returnSet;		 
	}
	
	/**
	 * Returns  a SCAIEVNode, which is the child of the given SCAIEVNode and implements the given adjacent signal
	 * @return
	 */
	public  SCAIEVNode GetAdjSCAIEVNode(SCAIEVNode parentNode, AdjacentNode adj){
		for(SCAIEVNode checkNode : GetAllBackNodes()) {
			if(checkNode.HasParentNode(parentNode) && checkNode.getAdj().equals(adj))
				 return checkNode;
		 }
		 
		 return null;		 
	}
	
	/**
	 * Returns adjacent nodes of given SCAIEVNode. For exp. in case of WrPC: validReq (valid request bit)
	 * @return
	 */
	public  HashSet<AdjacentNode> GetAdj(SCAIEVNode look4Node){
		 HashSet<AdjacentNode> returnSet = new  HashSet<AdjacentNode>(); 
		 for(SCAIEVNode checkNode : GetAllBackNodes()) {
			 if(checkNode.HasParentNode(look4Node))
				 returnSet.add(checkNode.getAdj());
		 }		 
		 return returnSet;		 
	}
	
	
	/** Function to get a list of spawn nodes. For exp for WrRD, it would be WrRD_spawn, WrRD_spawn_valid and WrRd_spawn_addr
	 * 
	 * @param look4Node
	 * @return
	 */
	public  HashSet<SCAIEVNode> GetMySpawnNodes(SCAIEVNode look4Node){
		 HashSet<SCAIEVNode> returnSet = new  HashSet<SCAIEVNode>(); 
		 SCAIEVNode mainSpawnNode = look4Node;
		 for(SCAIEVNode checkNode : GetAllBackNodes()) {
			 if(checkNode.HasParentNode(look4Node) && checkNode.isSpawnOf(look4Node)) {
				 mainSpawnNode = checkNode;
				 break;
			 }
		 }
		 
		 // Node not found
		 if(mainSpawnNode.equals(look4Node))
			 return null;
		 
		 for(SCAIEVNode checkNode : GetAllBackNodes()) {
			 if(checkNode.HasParentNode(mainSpawnNode)) {
				 returnSet.add(checkNode);
			 }
		 }
		 returnSet.add(mainSpawnNode);
		 
		 return returnSet;		 
	}
	
	
	/** Function to get this node's main spawn node. For exp for WrRD, it would be WrRD_spawn
	 * 
	 * @param look4Node
	 * @return
	 */
	public  SCAIEVNode GetMySpawnNode(SCAIEVNode look4Node){
		 HashSet<SCAIEVNode> returnSet = new  HashSet<SCAIEVNode>(); 
		 SCAIEVNode mainSpawnNode = look4Node;
		 for(SCAIEVNode checkNode : GetAllBackNodes()) {
			 if(checkNode.HasParentNode(look4Node) && checkNode.isSpawnOf(look4Node) && !checkNode.isAdj()) {
				 mainSpawnNode = checkNode;
				 break;
			 }
		 }
		 
		 // Node not found
		 if(mainSpawnNode.equals(look4Node))
			 return null;
		 
		 return mainSpawnNode;		 
	}
}
