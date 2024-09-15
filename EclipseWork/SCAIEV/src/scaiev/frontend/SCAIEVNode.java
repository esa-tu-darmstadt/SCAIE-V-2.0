package scaiev.frontend;

public class SCAIEVNode {
	public enum AdjacentNode {
		none(" "),					    // ! considered null
		spawnAllowed("_spawnAllowed"),  // for spawn, optional, synchronously keeps spawn fire active 
		validReq("_validReq"),			// ! considered to trigger node and be mandatory
		validData("_validData"),		// Currently used for wrrd/wrrd_user_node data hazard mechanism. Not mandatory
		validResp("_validResp"),        // ! considered to return valid response from core to ISAX
		addr("_addr"),
		addrReq("_addr_valid"),
		rdAddr("_rdAddr"),              // for exp for memory accesses when SCAL needs to get from core the adress
		addrCommited("_addrCommited"),
		isWrite("_write");				// for memory transctions for example, to make sure it is a write mem transaction
		public final String suffix;
		
		private AdjacentNode(String suffix) {
			this.suffix = suffix;
		}
	}
	// private vars
	private AdjacentNode adjSignal = AdjacentNode.none; // default value. If it doesn't have any parent node, than this value has to be AdjacentNode.none
	
	// public vars
	// Node properties
	public int size; 
	public int commitStage = -1;
	public int spawnStage = -1;
	public boolean isInput; 
	public String name = "";
	public int uniqueID = 0;
	public String nameParentNode = "";      	// Does this node belong to another node (logically)
	public boolean oneInterfToISAX = false;  	// for exp. WrStall should have 1 interf/stage to ISAX, but WrRD an interface for each ISAX result. So for WrStall this variable would be true and for WrRD false. By default Wr Nodes have false and Rd Nodes true
	public boolean allowMultipleSpawn = false; 	// By default true for all spawn nodes. WrPC_spawn should have it false, as no multiple spawn instructions are allowed to concurrently update PC..it doesn't make sense
	public String nameQousinNode = "";			// For example, rdmem and wrmem are qousin nodes. Spawn fire logic must be computed for bnoth these nodes because they use the same resources
	public boolean noInterfToISAX = false;		// Don't generate interface to ISAX for this node
	public String familyName = "";   			// used when nameQousinNode not empty. For exp for WrMem and RdMem, familyName = Mem
	public boolean DH = false;					// Datahazard required? For exp for WrRD spawn
	public int elements = 0; 					// used by user-added nodes (number of regfile elements)
	public String attachedNode = ""; 			// instead of creating a graph with parents to parent and so on, this is used if this is already an adj signal but is attached to other adj signal. For exp for wrmem_addr_valid this would be wrmem_addr
	public boolean mustToCore = false;          // This interf MUST be supported to the core. For exp _write for memory spawn
	public boolean mandatory = false; 
	
	// Other public vars
	public String spawnSuffix = "_spawn";	// 
	/**
	 * when just name is relevant & size & input irrelevant
	 * @param name
	 */
	public SCAIEVNode (String name) {
		this.name = name;
		this.uniqueID = name.hashCode();
		this.familyName = name;
		this.adjSignal = AdjacentNode.none;
	}
	
	/**
	 * Constructor of SCAIEVNode. Name has to be unique (not used by other SCAIEVNodes, as it is used by the hashCode() function.  
	 * @param uniqueID
	 * @param name
	 * @param size
	 * @param isInput
	 */
	public SCAIEVNode (String name, int size, boolean isInput) {
		this.size = size; 
		this.isInput = isInput;
		this.name = name;
		this.uniqueID = name.hashCode();
		if(isInput)
			oneInterfToISAX = false;
		else 
			oneInterfToISAX = true;
		this.familyName = name;
		this.adjSignal = AdjacentNode.none;
	}
	

	/**
	 * Constructor of SCAIEVNode. Name is generated automatically based on parent node and adjNode. 
	 * @param uniqueID
	 * @param name
	 * @param size
	 * @param isInput
	 */
	public SCAIEVNode (SCAIEVNode parentNode, AdjacentNode adjNode, int size, boolean isInput, boolean isSpawn) {
		this.size = size; 
		this.isInput = isInput;
		String adjNode_suffix = "";
		if (adjNode != AdjacentNode.none)
			adjNode_suffix = adjNode.suffix;
		if(isSpawn && !parentNode.isSpawn())
			this.name = CreateSpawnName(parentNode) + adjNode_suffix;
		else 
			this.name = parentNode.name + adjNode_suffix;
		if(parentNode.isSpawn())
			allowMultipleSpawn = parentNode.allowMultipleSpawn;
		else if(isSpawn)
			allowMultipleSpawn = true;
		this.uniqueID = name.hashCode();
		this.nameParentNode = parentNode.name;
		this.adjSignal = adjNode;
		if(isInput)
			oneInterfToISAX = false;
		else 
			oneInterfToISAX = true;
		this.familyName = parentNode.familyName;
		this.nameQousinNode = parentNode.nameQousinNode;
		this.DH = parentNode.DH;
		this.elements = parentNode.elements;
	}
	
	/** 
	 * When generating common module, it is mandatory to have the AdjacentNode as given in this function. For example, in case of WrRD, it is mandatory to compute valid signal. This will be used by core as RegFile[rd] = WrRd_valid ? WrRD : deafult_logic ==> it is mandatory in common logic module to have WrRd_valid signal
	 * @param adjNode
	 * @return
	 */
	public  boolean DefaultMandatoryAdjSig() {
		if(this.adjSignal.equals(AdjacentNode.validReq) || (this.isSpawn() && this.adjSignal.equals(AdjacentNode.validResp)) || (this.adjSignal.equals(AdjacentNode.addrReq) && (elements>1)))
			return true; 
		else 
			return false;
	}
	
	/** 
	 * Function to generate the same SCAIEVNode but with !isInput 
	 * @return
	 */
	public SCAIEVNode NodeNegInput() {
		String newName = this.name;
		SCAIEVNode returnNode = new  SCAIEVNode(newName,this.size,!this.isInput);
		returnNode.adjSignal = this.adjSignal;
		returnNode.familyName = this.familyName;
		returnNode.nameQousinNode = this.nameQousinNode;
		returnNode.allowMultipleSpawn = this.allowMultipleSpawn;
		returnNode.nameParentNode = this.nameParentNode;
		returnNode.oneInterfToISAX = this.oneInterfToISAX;
		returnNode.DH = this.DH;
		return  returnNode;
	}
	/** 
	 * Function returns AdjacentNode valid signal which triggers a transaction of node. Current implementation expects, that transactions are signaled through validReq  AdjacentNode. It is by default as this and should be considered when defining new BNodes. Could be replaced by a variable in future which is set true only for valid request signals.
	 * @param node
	 * @return
	 */
	public static AdjacentNode GetValidRequest() {
		return AdjacentNode.validReq;
	}
	
	public static AdjacentNode GetValidResponse() {
		return AdjacentNode.validResp;
	}
	
	public static AdjacentNode GetAddr() {
		return AdjacentNode.addr;
	}
	
	/** 
	 * is node2 spawn of node1?
	 * @param node1
	 * @param node2
	 * @return
	 */
	public boolean isSpawnOf(SCAIEVNode node1) {
		return this.name.contains( CreateSpawnName(node1) );
	}
	
	/** 
	 * is node2 spawn of node1?
	 * @param node1
	 * @param node2
	 * @return
	 */
	public boolean isSpawn() {
		return this.name.contains(spawnSuffix);
	}
	
	/** 
	 * Make one function to use in different other functions & make sure you create name consistent
	 * @param parentNode
	 */
	private String CreateSpawnName (SCAIEVNode parentNode) {
		return parentNode.name + spawnSuffix;
	}
	public String printInfo() {
		return "Node named: "+name+" with ID = "+uniqueID+", with size "+size+" is Input?"+isInput;
	}
	
	public String getVldName() {
		return this.name+"_valid";
	}
	
	public String getAddrName() {
		return this.name+"_addr";
	}
	
		
		
	public String replaceRadixNameWith (String replace) {
		return replace+"_"+this.name.split("_",2)[1];
		
	}
	public AdjacentNode getAdj() {
		if(this.nameParentNode.equals(""))
			return AdjacentNode.none;
		else 
			return this.adjSignal;
	}
	
	public boolean isAdj() {
		if(this.adjSignal.equals(AdjacentNode.none))
			return false;
		else 
			return true;
	}
	
	public boolean HasParentNode(SCAIEVNode checkNode) {
		return checkNode.name.equals(this.nameParentNode);
	}
	@Override
	public String toString() {
		return name; 
	}
	
	@Override
	public int	hashCode() {
		return uniqueID; 
	} 
	
	@Override
	public boolean equals(Object obj) {
        return (this.uniqueID == obj.hashCode());
    }

	
}
