package scaiev.backend;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.frontend.FNode;
import scaiev.frontend.SCAIEVNode;
import  scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;

/***********************
 * 
 * Backend supported SCAIEV nodes. 'isInput' in nodes is from the core's point of view.
 *
 */
public class BNode extends FNode{
	// logging
	protected static final Logger logger = LogManager.getLogger();

	// To add a new node, add it here and in GetAllBackNodes. 
	// If you need a node to have valid and addr signals, add validSuffix & addrSuffix to their names. This suffix is used in GenerateText class and other classes. If you don't use this pattern, some functions might not work. This could be improved in future versions of the tool
	// Please consider that _valid , _addr nodes are used in backend just for the names , in order to generate interf text & logic. They will not be in op_stage_instr, because it would be redundant. We would have same data stored multiple times in op_stage_instr 
	public static String validSuffix = AdjacentNode.validReq.suffix;
	public static String addrSuffix =AdjacentNode.addr.suffix;
	

	public SCAIEVNode WrRD_valid        = new SCAIEVNode(WrRD       , AdjacentNode.validReq   , 1 , true, false); 
	public SCAIEVNode WrRD_validData    = new SCAIEVNode(WrRD       , AdjacentNode.validData  , 1 , true, false) {{noInterfToISAX = true; DH = true;}}; 
	public SCAIEVNode WrRD_addr         = new SCAIEVNode(WrRD       , AdjacentNode.addr       , 5 , true, false) {{validBy = AdjacentNode.addrReq;}}; // JUST for dynamic decoupled wrrd
	public SCAIEVNode WrRD_addr_valid   = new SCAIEVNode(WrRD       , AdjacentNode.addrReq    , 5 , true, false);
	public SCAIEVNode RdMem_validReq    = new SCAIEVNode(RdMem      , AdjacentNode.validReq   , 1 , true, false); 
	public SCAIEVNode WrMem_validReq    = new SCAIEVNode(WrMem      , AdjacentNode.validReq   , 1 , true, false);

	public SCAIEVNode RdMem_validResp   = new SCAIEVNode(RdMem      , AdjacentNode.validResp  , 1 , false, false) {{oneInterfToISAX = true; tags.add(NodeTypeTag.defaultNotprovidedByCore);}};
	public SCAIEVNode WrMem_validResp   = new SCAIEVNode(WrMem      , AdjacentNode.validResp  , 1 , false, false) {{oneInterfToISAX = true; tags.add(NodeTypeTag.defaultNotprovidedByCore);}};
	public SCAIEVNode RdMem_defaultAddr = new SCAIEVNode(RdMem      , AdjacentNode.defaultAddr, datawidth, false, false) {{noInterfToISAX = true; tags.add(NodeTypeTag.staticReadResult);}}; //Provided by SCAL, core can output efficient version by setting mustToCore=true or adding the node to Core.
	public SCAIEVNode RdMem_addr        = new SCAIEVNode(RdMem      , AdjacentNode.addr       , datawidth, true, false) {{validBy = AdjacentNode.addrReq;}};

	public SCAIEVNode RdMem_size        = new SCAIEVNode(RdMem      , AdjacentNode.size       , 3, true, false) {{validBy = AdjacentNode.addrReq; mustToCore = true;}}; //funct3 value
	public SCAIEVNode RdMem_addr_valid  = new SCAIEVNode(RdMem      , AdjacentNode.addrReq    , 1, true, false) {{attachedNode = RdMem_addr.name;}};
	public SCAIEVNode WrMem_defaultAddr = new SCAIEVNode(WrMem      , AdjacentNode.defaultAddr, datawidth, false, false) {{noInterfToISAX = true; tags.add(NodeTypeTag.staticReadResult);}}; //If the core provides RdMem_defaultAddr, this must also be provided.
	public SCAIEVNode WrMem_addr        = new SCAIEVNode(WrMem      , AdjacentNode.addr       , datawidth, true, false) {{validBy = AdjacentNode.addrReq;}};
	public SCAIEVNode WrMem_size        = new SCAIEVNode(WrMem      , AdjacentNode.size       , 3, true, false) {{validBy = AdjacentNode.addrReq; mustToCore = true;}}; //funct3 value
	public SCAIEVNode WrMem_addr_valid  = new SCAIEVNode(WrMem      , AdjacentNode.addrReq    , 1, true, false) {{attachedNode = WrMem_addr.name;}};
	//public static SCAIEVNode RdMem_validResp   = new SCAIEVNode(RdMem      , AdjacentNode.validResp, 1 , false, false) ; // valdir read data
	public SCAIEVNode WrPC_valid        = new SCAIEVNode(WrPC       , AdjacentNode.validReq   , 1 , true, false);
	public SCAIEVNode WrPC_spawn        = new SCAIEVNode(WrPC       , AdjacentNode.none       , datawidth, true, true) {{oneInterfToISAX = true; allowMultipleSpawn = false; validBy = AdjacentNode.addrReq;}};
	public SCAIEVNode WrPC_spawn_valid  = new SCAIEVNode(WrPC_spawn , AdjacentNode.validReq   , 1 , true, true) {{oneInterfToISAX = true; allowMultipleSpawn = false;}};
	public SCAIEVNode WrRD_spawn        = new SCAIEVNode(WrRD       , AdjacentNode.none       , datawidth, true, true) {{DH = true;this.elements = 32; validBy = AdjacentNode.validReq;}};
	public SCAIEVNode WrRD_spawn_valid  = new SCAIEVNode(WrRD_spawn , AdjacentNode.validReq   , 1 , true, true); 					
	public SCAIEVNode WrRD_spawn_validResp    = new SCAIEVNode(WrRD_spawn, AdjacentNode.validResp	, 1 , false, true) {{oneInterfToISAX = false;}};
	public SCAIEVNode WrRD_spawn_addr         = new SCAIEVNode(WrRD_spawn, AdjacentNode.addr		, 5 , true, true){{noInterfToISAX = true; mandatory = true; this.validBy = AdjacentNode.validReq;}};
//	public SCAIEVNode WrRD_spawn_addrCommited = new SCAIEVNode(WrRD_spawn, AdjacentNode.addrCommited, 5 , true, false);
	public SCAIEVNode WrRD_spawn_allowed      = new SCAIEVNode(WrRD_spawn, AdjacentNode.spawnAllowed, 1, false, true);
	
	public SCAIEVNode RdMem_spawn             = new SCAIEVNode(RdMem       , AdjacentNode.none        , datawidth, false, true) {{this.familyName = "Mem";oneInterfToISAX = false; nameCousinNode = "WrMem_spawn"; allowMultipleSpawn = true;}}; // TODO unstable solution with nameCousin here
	//If an ISAX performs multiple memory accesses, it must wait for validResp of the previous one to prevent potential FIFO overflows in SCAL.
	public SCAIEVNode RdMem_spawn_validReq    = new SCAIEVNode(RdMem_spawn , AdjacentNode.validReq    , 1, true, true); 
	public SCAIEVNode RdMem_spawn_validResp   = new SCAIEVNode(RdMem_spawn , AdjacentNode.validResp   , 1, false, true) {{oneInterfToISAX = false;}};
	public SCAIEVNode RdMem_spawn_addr        = new SCAIEVNode(RdMem_spawn , AdjacentNode.addr        , datawidth, true, true) {{validBy = AdjacentNode.validReq;}};
	public SCAIEVNode RdMem_spawn_size        = new SCAIEVNode(RdMem_spawn , AdjacentNode.size        , 3, true, true) {{validBy = AdjacentNode.validReq; mustToCore = true;}}; //funct3 value

	public SCAIEVNode RdMem_spawn_defaultAddr = new SCAIEVNode(RdMem_spawn , AdjacentNode.defaultAddr , datawidth, false, true) {{noInterfToISAX = true; /*mustToCore = true;*/ tags.add(NodeTypeTag.staticReadResult);}}; //Analogous to Rd/WrMem_defaultAddr
	public SCAIEVNode RdMem_spawn_write       = new SCAIEVNode(RdMem_spawn , AdjacentNode.isWrite     , 1, true, true) {{noInterfToISAX = true; mustToCore = true;}};
	public SCAIEVNode RdMem_spawn_allowed     = new SCAIEVNode(RdMem_spawn , AdjacentNode.spawnAllowed, 1, false, true);

	

	public SCAIEVNode WrMem_spawn             = new SCAIEVNode(WrMem       , AdjacentNode.none        , datawidth, true, true) {{familyName = "Mem"; nameCousinNode = RdMem_spawn.name; allowMultipleSpawn = true; validBy = AdjacentNode.validReq;}};
	//If an ISAX performs multiple memory accesses, it must wait for validResp of the previous one to prevent potential FIFO overflows in SCAL.
	public SCAIEVNode WrMem_spawn_validReq    = new SCAIEVNode(WrMem_spawn , AdjacentNode.validReq    , 1, true, true); 
	public SCAIEVNode WrMem_spawn_addr        = new SCAIEVNode(WrMem_spawn , AdjacentNode.addr        , datawidth, true, true) {{validBy = AdjacentNode.validReq;}};
	public SCAIEVNode WrMem_spawn_size        = new SCAIEVNode(WrMem_spawn , AdjacentNode.size        , 3, true, true) {{validBy = AdjacentNode.validReq; mustToCore = true;}}; //funct3 value
	public SCAIEVNode WrMem_spawn_defaultAddr = new SCAIEVNode(WrMem_spawn , AdjacentNode.defaultAddr , datawidth, false, true) {{noInterfToISAX = true; tags.add(NodeTypeTag.staticReadResult);}}; //Analogous to Rd/WrMem_defaultAddr.
	public SCAIEVNode WrMem_spawn_validResp   = new SCAIEVNode(WrMem_spawn , AdjacentNode.validResp   , 1, false, true) {{oneInterfToISAX = false; mustToCore = true;}};
	public SCAIEVNode WrMem_spawn_write       = new SCAIEVNode(WrMem_spawn , AdjacentNode.isWrite     , 1, true, true) {{noInterfToISAX = true; mustToCore = true;}};
	public SCAIEVNode WrMem_spawn_allowed     = new SCAIEVNode(WrMem_spawn , AdjacentNode.spawnAllowed, 1, false, true);
	
	//public static SCAIEVNode WrJump_spawn_valid  = new SCAIEVNode(WrJump		, AdjacentNode.validReq	, 1 , true, true) {{this.oneInterfToISAX = true; this.allowMultipleSpawn = false;}};
	
	public SCAIEVNode WrCSR_valid           = new SCAIEVNode(WrCSR  , AdjacentNode.validReq, 1, true, false);
	
	public SCAIEVNode committed_rd_spawn       = new SCAIEVNode("Committed_rd_spawn", 5, false);
	public SCAIEVNode committed_rd_spawn_valid = new SCAIEVNode(committed_rd_spawn, AdjacentNode.validReq, 1, false, false);
	/** 
	 * For {@link scaiev.scal.strategy.decoupled.DecoupledDHStrategy}:
	 * The original logical ISA register number to unlock (decoupled stage) in the data hazard module.
	 * If not given to the core, the default unlock register is from the lower bits of WrRD_spawn_addr.
	 */
	public SCAIEVNode rd_dh_spawn_addr = new  SCAIEVNode("Rd_dh_spawn_addr", 5, false);
	
	public SCAIEVNode cancel_from_user        = new SCAIEVNode("cancel_frUser", 5, false);
	public SCAIEVNode cancel_from_user_valid  = new SCAIEVNode(cancel_from_user, AdjacentNode.validReq, 1, false, false);

	
	
	// Local Signal Names used in all cores for logic  of spawn
	public SCAIEVNode ISAX_spawnAllowed    = new SCAIEVNode("ISAX_spawnAllowed", 1, false) {{noInterfToISAX = true;}};
	
	public SCAIEVNode ISAX_spawnStall_regF_s = new SCAIEVNode("isax_spawnStall_regF_s", 1, false);
	public SCAIEVNode ISAX_spawnStall_mem_s  = new SCAIEVNode("isax_spawnStall_mem_s", 1, false);
	public SCAIEVNode IsBranch  = new SCAIEVNode("IsBranch", 1, false);
	public SCAIEVNode IsZOL  = new SCAIEVNode("IsZOL", 1, false);
	
	// Deprecated
	/** Deprecated global RdStall, automatic conversion from RdStall in core description. */
	public SCAIEVNode RdStallLegacy  = new SCAIEVNode("RdStallLegacy",1,false) {{oneInterfToISAX = true; tags.add(NodeTypeTag.perStageStatus); tags.add(NodeTypeTag.noCoreInterface);}};
	
	// Core<->SCAL additional nodes
	/**
	 * core->SCAL: For the given next stage, whether the instruction is being pipelined into that stage.
	 */
	public SCAIEVNode RdPipeInto = new SCAIEVNode("RdPipeInto", 1, false);
	
	// Pipelined Execution Unit support
	/** SCAL->core: The current ISAX instruction is entering the ISAX-pipeline, and the core is allowed to get the next instruction in the current stage
	 *               _without_ moving the current instruction further. Always comes with a WrStall.
	 *              If the core does not support having several instructions within the given stage, it can safely ignore this request.
	 */
	public SCAIEVNode WrDeqInstr = new SCAIEVNode("WrDeqInstr", 1, true);
	
	/** core->SCAL: The current instruction ID for differentiation between subsequent instructions. Does not have to be consistent across stages of the core, and does not have to be continuous.
	 *              If the core does not have a native instruction ID, it can also provide a toggling bit to indicate when a new instruction has entered a stage.
	 *              Whether the instruction actually is valid is to be determined through RdStall.
	 */
	public SCAIEVNode RdInStageID = new SCAIEVNode("RdInStageID", 0, false);
	/** core->SCAL: Indicates valid for {@link BNode#WrInStageID} and all other non-handshakey read nodes associated with the instruction (RdRS1, RdInstr, etc.)
	 *              If not supported, will be set to !RdStall by SCAL.
	 */
	public SCAIEVNode RdInStageValid = new SCAIEVNode("RdInStageValid", 1, false);
	/** SCAL->core: The overridden instruction ID to commit / to leave the stage.
	 *              If the core does not support having several instructions within the given stage, it can ignore this request (and commit the current instruction eventually).
	 *              Otherwise, the core should commit the instruction based on the given ID, comb. setting validResp once SCAL no longer needs to hold the signal&validReq.
	 *              WrStall in the same stage must not prevent WrInStageID from completing.
	 *              Note: The core should also set RdStall during WrInStageID if the overridden instruction ID is not the current instruction in the core pipeline.
	 *              Hint: SCAL only uses WrInStageID after a corresponding WrDeqInstr.
	 */
	public SCAIEVNode WrInStageID = new SCAIEVNode("WrInStageID", 0, true);
	/** SCAL->core: validReq for {@link BNode#WrInStageID} */
	public SCAIEVNode WrInStageID_valid = new SCAIEVNode(WrInStageID, AdjacentNode.validReq, 1, true, false);
	/** core->SCAL: validResp for {@link BNode#WrInStageID};
	 * is allowed to be 1 spuriously as long as WrInStageID_validReq is not set;
	 * when WrInStageID_validReq is set, must be equal to !RdStall&&!WrStall or !RdStall&&!WrStall&&!RdFlush&&!WrFlush
	 * */
	public SCAIEVNode WrInStageID_validResp = new SCAIEVNode(WrInStageID, AdjacentNode.validResp, 1, false, false);

	/** ISAX->SCAL: ISAX commit marker */
	public SCAIEVNode WrCommit_spawn = new SCAIEVNode(WrCommit, AdjacentNode.none, 1, true, true) {{validBy = AdjacentNode.validReq;}};
	/** ISAX->SCAL: 'validReq' for ISAX commit marker, allowed exactly once (i.e. for one cycle) per ISAX */
	public SCAIEVNode WrCommit_spawn_validReq = new SCAIEVNode(WrCommit_spawn, AdjacentNode.validReq, 1, true, true);
	/** ISAX->SCAL: 'validReq' for ISAX commit marker */
	public SCAIEVNode WrCommit_spawn_validResp = new SCAIEVNode(WrCommit_spawn, AdjacentNode.validResp, 1, false, true);
	
	public  HashSet<SCAIEVNode> user_BNode = new  HashSet<SCAIEVNode>();
	protected List<SCAIEVNode> core_BNode = new ArrayList<SCAIEVNode>();
	
	/**
	 * Adds a core-specific SCAIEVNode.
	 */
	public void AddCoreBNode(SCAIEVNode coreNode) {
		core_BNode.add(coreNode);
		refreshAllNodesSet();
	}

	/**
	 * Adds a user-defined register node.
	 */
	@Override
	public void AddUserNode (String name, int width, int elements) {
		SCAIEVNode RdNode = new SCAIEVNode(rdName+name, width, false);
		RdNode.elements  = elements;
		RdNode.tags.add(NodeTypeTag.staticReadResult); //Assuming there is only one global read stage/front.
		SCAIEVNode WrNode = new SCAIEVNode(wrName+name, width, true) {{this.validBy = AdjacentNode.validData;}};
		WrNode.elements  = elements;
		user_FNode.add(RdNode); 
		user_FNode.add(WrNode);
		user_BNode.add(RdNode); 
		user_BNode.add(WrNode);
		
		int addr_size = (int) Math.ceil((Math.log10(elements)/Math.log10(2)));
		user_BNode.add(new SCAIEVNode(WrNode,AdjacentNode.validReq  , 1, true, false));
		user_BNode.add(new SCAIEVNode(WrNode,AdjacentNode.cancelReq , 1, true, false));
		user_BNode.add(new SCAIEVNode(RdNode,AdjacentNode.validReq  , 1, true, false) {{noInterfToISAX = true;}});
		user_BNode.add(new SCAIEVNode(RdNode,AdjacentNode.validReq  , 1, true, false) {{noInterfToISAX = true;}});
		user_BNode.add(new SCAIEVNode(WrNode,AdjacentNode.validData , 1, true, false) {{noInterfToISAX = true;}});
		SCAIEVNode RdNode_Addr = new SCAIEVNode(RdNode,AdjacentNode.addr	, addr_size, true, false) {{validBy = AdjacentNode.addrReq; }};
		user_BNode.add(RdNode_Addr);
		SCAIEVNode WrNode_Addr = new SCAIEVNode(WrNode,AdjacentNode.addr	, addr_size, true, false) {{validBy = AdjacentNode.addrReq; }};
		user_BNode.add(WrNode_Addr);
		SCAIEVNode RdNode_AddrValid = new SCAIEVNode(RdNode,AdjacentNode.addrReq	, 1, true, false) {{attachedNode = RdNode_Addr.name;}};
		RdNode_AddrValid.elements = elements;
		SCAIEVNode WrNode_AddrValid = new SCAIEVNode(WrNode,AdjacentNode.addrReq	, 1, true, false) {{attachedNode = WrNode_Addr.name;}};
		WrNode_AddrValid.elements = elements;	
		user_BNode.add(WrNode_AddrValid);	
		user_BNode.add(RdNode_AddrValid);
		
		// Spawn just for write 
		// Set allowMultipleSpawn COULD BE SET HERE to false, and in AddUserNodesToCore(..) we check how many isaxes need spawn and update this param. For the moment by default true to generate in SCAL the fire logic
		SCAIEVNode  WrNode_spawn = new SCAIEVNode(WrNode ,       AdjacentNode.none		, width, true, true) {{oneInterfToISAX = false; allowMultipleSpawn = true; validBy = AdjacentNode.validReq;}};
		//removed 'DH = true;' in WrNode, WrNode_spawn and WrNode_validData
		user_BNode.add(WrNode_spawn);
		user_BNode.add(new SCAIEVNode(WrNode_spawn  , AdjacentNode.validReq	, 1, true, true){{oneInterfToISAX = false; allowMultipleSpawn = true;}});
		user_BNode.add(new SCAIEVNode(WrNode_spawn,AdjacentNode.cancelReq , 1, true, true){{allowMultipleSpawn = true;}}); 
		if(elements>1) // Don.t generate addr signal for spawn if not required
			user_BNode.add(new SCAIEVNode(WrNode_spawn  , AdjacentNode.addr		, addr_size, true, true){{oneInterfToISAX = false; allowMultipleSpawn = true; mandatory = true; validBy = AdjacentNode.validReq;}});
		user_BNode.add(new SCAIEVNode(WrNode_spawn  , AdjacentNode.validResp	, 1, false, true) {{oneInterfToISAX = false;}});
	//	user_BNode.add(new SCAIEVNode(WrNode_spawn  , AdjacentNode.spawnAllowed, 1, false, true)  {{noInterfToISAX = false;}}); // For the moment by default always allowed for internal state. Yet, core spawnAllowed must still be checked due to stalling	

		// Read spawn for direct reads (no DH ) 
		//SCAIEVNode  RdNode_spawn = new SCAIEVNode(RdNode ,       AdjacentNode.none		, width, false, true) {{oneInterfToISAX = true; DH = false;}};
		//user_BNode.add(RdNode_spawn);
		
		refreshAllNodesSet();
	} 
	
	public boolean IsUserBNode(SCAIEVNode node) {
		return user_BNode.contains(node);
	}
	private HashSet<SCAIEVNode> allBackNodes = null;
	@Override
	protected void refreshAllNodesSet() {
		super.refreshAllNodesSet();
		allBackNodes = null;
	}
	public  HashSet<SCAIEVNode>  GetAllBackNodes(){
		if (allBackNodes != null)
			return allBackNodes;
		HashSet<SCAIEVNode> bnodes = new HashSet<>(GetAllFrontendNodes());
		allBackNodes = bnodes;
		if(!this.user_BNode.isEmpty()) bnodes.addAll(this.user_BNode);
		bnodes.add(WrRD_valid);
		bnodes.add(WrRD_validData);
	//	bnodes.add(WrRD_addr);
		bnodes.add(RdMem_validReq);
		bnodes.add(RdMem_validResp);
		bnodes.add(RdMem_defaultAddr);
		bnodes.add(RdMem_addr);
		bnodes.add(RdMem_size);
		bnodes.add(RdMem_addr_valid);
		bnodes.add(WrMem_validReq);
		bnodes.add(WrMem_validResp);
		bnodes.add(WrMem_defaultAddr);
		bnodes.add(WrMem_addr);
		bnodes.add(WrMem_size);
		bnodes.add(WrMem_addr_valid);
		bnodes.add(WrPC_valid);
		bnodes.add(WrPC_spawn_valid);
		bnodes.add(WrPC_spawn);
		bnodes.add(WrRD_spawn_valid);
		bnodes.add(WrRD_spawn_addr);
		bnodes.add(WrRD_spawn_validResp);
		bnodes.add(WrRD_spawn);
		bnodes.add(WrRD_spawn_allowed);
		bnodes.add(RdMem_spawn_validResp);
		bnodes.add(RdMem_spawn_addr);
		bnodes.add(RdMem_spawn_size);
		bnodes.add(RdMem_spawn_defaultAddr);
		bnodes.add(RdMem_spawn);
		bnodes.add(RdMem_spawn_write);
		bnodes.add(RdMem_spawn_validReq);
		bnodes.add(RdMem_spawn_allowed);
		bnodes.add(WrMem_spawn);
		bnodes.add(WrMem_spawn_validReq);
		bnodes.add(WrMem_spawn_validResp);
		bnodes.add(WrMem_spawn_addr);
		bnodes.add(WrMem_spawn_size);
		bnodes.add(WrMem_spawn_defaultAddr);
		bnodes.add(WrMem_spawn_write);
		bnodes.add(WrMem_spawn_allowed);
		//bnodes.add(WrJump_spawn_valid);
		
		bnodes.add(WrCSR_valid);
		bnodes.add(committed_rd_spawn);
		bnodes.add(committed_rd_spawn_valid);
		bnodes.add(ISAX_spawnAllowed);
		bnodes.add(ISAX_spawnStall_regF_s);
		bnodes.add(ISAX_spawnStall_mem_s);
		
		bnodes.add(IsBranch);
		bnodes.add(IsZOL);

		
		bnodes.add(RdPipeInto);
		
		bnodes.add(WrDeqInstr);
		bnodes.add(RdInStageID);
		bnodes.add(RdInStageValid);
		bnodes.add(WrInStageID);
		bnodes.add(WrInStageID_valid);
		bnodes.add(WrInStageID_validResp);

		bnodes.add(WrCommit_spawn);
		bnodes.add(WrCommit_spawn_validReq);
		bnodes.add(WrCommit_spawn_validResp);

		bnodes.add(RdStallLegacy);
		
		bnodes.addAll(core_BNode);
		return bnodes;
	}
	
	

	public SCAIEVNode GetSCAIEVNode(String nodeName) {
		HashSet<SCAIEVNode> bnodes = GetAllBackNodes();
		for(SCAIEVNode node : bnodes)
			if(node.name.equals(nodeName))		 		
				return node;
 		
		return new SCAIEVNode("",0,false);
	}
	


	public  boolean HasSCAIEVBNode(String nodeName) {
		HashSet<SCAIEVNode> bnodes = GetAllBackNodes();
		for(SCAIEVNode node : bnodes)
			if(node.name.contains(nodeName))
				return true;
		logger.warn("Requested Node " + nodeName + " not found in BNode.");
		return false;
	}
	@Override
	public boolean HasSCAIEVNode(String nodeName) {
		return HasSCAIEVBNode(nodeName);
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
	public Optional<SCAIEVNode> GetAdjSCAIEVNode(SCAIEVNode parentNode, AdjacentNode adj){
		for(SCAIEVNode checkNode : GetAllBackNodes()) {
			if(checkNode.HasParentNode(parentNode) && checkNode.getAdj().equals(adj))
				 return Optional.of(checkNode);
		 }
		 return Optional.empty();
	}

	/**
	 * Returns the non-adj node of a given node. If the given node is non-adj, returns node itself.
	 * Returns a node with an empty name if it doesn't exist.
	 * @return
	 */
	public SCAIEVNode GetNonAdjNode(SCAIEVNode node){
		return node.isAdj() ? GetSCAIEVNode(node.nameParentNode) : node;
	}
	/**
	 * Returns the equivalent spawn adj/non-adj node to a non-spawn adj/non-adj node, or an empty Optional if it doesn't exist
	 * @return
	 */
	public Optional<SCAIEVNode> GetEquivalentSpawnNode(SCAIEVNode node){
		if (node.isSpawn())
			return Optional.of(node);
		SCAIEVNode nodeNonadj = node.isAdj() ? GetSCAIEVNode(node.nameParentNode) : node;
		return GetMySpawnNode(nodeNonadj).flatMap(nodeSpawnNonadj -> node.isAdj() ? GetAdjSCAIEVNode(nodeSpawnNonadj, node.getAdj()) : Optional.of(nodeSpawnNonadj));
	}
	/**
	 * Returns the equivalent non-spawn adj/non-adj node to a spawn adj/non-adj node, or an empty Optional if it doesn't exist
	 * @return
	 */
	public Optional<SCAIEVNode> GetEquivalentNonspawnNode(SCAIEVNode node){
		SCAIEVNode nodeNonadj = node.isAdj() ? GetSCAIEVNode(node.nameParentNode) : node;
		SCAIEVNode nodeNonspawnNonadj = nodeNonadj.isSpawn() ? GetSCAIEVNode(nodeNonadj.nameParentNode) : nodeNonadj;
		return node.isAdj() ? GetAdjSCAIEVNode(nodeNonspawnNonadj, node.getAdj()) : Optional.of(nodeNonspawnNonadj);
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
	public Optional<SCAIEVNode> GetMySpawnNode(SCAIEVNode look4Node){
		for(SCAIEVNode checkNode : GetAllBackNodes()) {
			if(checkNode.HasParentNode(look4Node) && checkNode.isSpawnOf(look4Node) && !checkNode.isAdj()) {
				return Optional.of(checkNode);
			}
		}
		return Optional.empty();
	}
}
