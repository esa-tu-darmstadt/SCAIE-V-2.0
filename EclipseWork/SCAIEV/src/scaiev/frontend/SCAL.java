package scaiev.frontend;

// TODO RDIVALID DUMMY REMOVE
// TODO WRCSR
// TODO rd/wrmem not later than mem stage but prev to writeback should be translated to spwn
// TODO Commited addr to ISAX required? (only when DH is outside of SCAL, right?
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

import scaiev.backend.BNode;
import scaiev.backend.CoreBackend;
import scaiev.backend.SCALBackendAPI;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.util.FileWriter;
import scaiev.util.GenerateText;
import scaiev.util.Verilog;
import scaiev.util.GenerateText.DictWords;
import scaiev.util.Lang;

/** 
 * This class generates logic to be used in all cores, no matter what configuration. Class generates Verilog module
 * @author elada
 *
 */
public class SCAL implements SCALBackendAPI {
	// Public Variables
	public enum DecoupledStrategy {
		none, 
		withDH, 
		withStall	
	}
	public boolean multicycle = false; 
	
	// Private Variables
	private HashMap <String,SCAIEVInstr> ISAXes;
	private HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr;
	private HashMap<SCAIEVNode, HashMap<String, Integer>> spawn_instr_stage = new  HashMap<SCAIEVNode, HashMap<String, Integer>> ();
	HashMap<SCAIEVNode, Integer> node_earliestStageValid = new HashMap<SCAIEVNode, Integer>(); // Key = node, Value = earliest stage for which a valid must be generated
	private Core core;
	private  Verilog myLanguage ;
	private static class VirtualBackend extends CoreBackend
	{
		@Override
		public String getCorePathIn() {
			return "";
		}
		@Override
		public boolean Generate(HashMap<String, SCAIEVInstr> ISAXes,
				HashMap<SCAIEVNode, HashMap<Integer, HashSet<String>>> op_stage_instr, String extension_name,
				Core core, String out_path) {
			return false;
		}
	}
	private CoreBackend virtualBackend = new VirtualBackend();
	FileWriter toFile = null;
	private static class RdIValidStageDesc
	{
		public HashMap<String, String> validCond_isax = new HashMap<>(); //key "" if shared across all ISAXes
		public HashSet<String> instructions = new HashSet<>();
		
		@Override
		public String toString() {
			return " RdIValidStageDesc.intrucions = "+instructions+" RdIValidStageDesc.valid_cond_by_isax = "+validCond_isax+" ";
		}
	}
	private HashMap<Integer, RdIValidStageDesc> stage_containsRdIValid = new HashMap<>();
	private HashSet<SCAIEVNode> adjSpawnAllowedNodes = new HashSet<>();
	private HashMap<SCAIEVNode, HashSet<String>> nodePerISAXOverride = new HashMap<>(); // TODO ?
	private static class NodeStagePair
	{
		@Override
		public int hashCode() {
			return Objects.hash(node, stage);
		}
		@Override
		public boolean equals(Object obj) {
			if (this == obj)
				return true;
			if (obj == null)
				return false;
			if (getClass() != obj.getClass())
				return false;
			NodeStagePair other = (NodeStagePair) obj;
			return Objects.equals(node, other.node) && stage == other.stage;
		}
		public SCAIEVNode node;
		public int stage;
		public NodeStagePair(SCAIEVNode node, int stage)
		{
			this.node = node;
			this.stage = stage;
		}
	}
	private HashSet<NodeStagePair>  removeFrCoreInterf = new HashSet<>();
	private HashMap<SCAIEVNode, HashSet<Integer>>  addToCoreInterf = new HashMap<SCAIEVNode, HashSet<Integer>> ();
	private HashMap<SCAIEVNode, HashSet<Integer>>  addRdNodeReg = new HashMap<SCAIEVNode, HashSet<Integer>> ();
	private int maxStageInstr = -1;
	private SCAIEVNode ISAX_fire2_r = new SCAIEVNode("ISAX_fire2_r",1, false );
	private SCAIEVNode ISAX_fire_s = new SCAIEVNode("ISAX_fire_s",1, false );
	private SCAIEVNode ISAX_fire_r = new SCAIEVNode("ISAX_fire_r",1, false );
	private SCAIEVNode ISAX_spawn_sum = new SCAIEVNode("ISAX_spawn_sum",8, false );
	
	public BNode BNode = new BNode(); 
	public FNode FNode = new FNode(); 
	
	// Module names 
	private String FIFOmoduleName = "SimpleFIFO";
	private String ShiftmoduleName = "SimpleShift";
	private String CountermoduleName = "SimpleCounter";
	private String ShiftmoduleSuffix = "_shiftreg_s";
	////!!!!!!!!!!!!!
	// SCAL Settings 
	//!!!!!!!!!!!!!!
	public boolean SETTINGWithScoreboard = true; // true = Scoreboard instantiated within SCAL,  false = scoreboard implemented within ISAX by user   
	public boolean SETTINGWithValid = true;      // true = generate shift register for user valid bit within SCAL. False is a setting used by old SCAIEV version, possibly not stable. If false, user generates valid trigger 
	public boolean SETTINGWithAddr = true;		 // true = FIFO for storing dest addr instantiated within SCAL, If false, user must store this info and provide it toghether with result. 
	public boolean SETTINGwithInputFIFO = true;  // true =  it may happen that multiple ISAXes commit result, input FIFO to store them instantiated within SCAL. false = no input FIFOs instantiated
	public HashSet<String> SETTINGdisableSpawnFireStall_families = new HashSet<>();
	public HashSet<CustomCoreInterface> customCoreInterfaces = new HashSet<>();
	public HashMap<String,SCALPinNet> netlist = new HashMap<>(); //Nets by scal_module_pin

	public void SetSCAL (boolean nonDecWithDH, boolean decWithValid, boolean decWithAddr, boolean decWithInpFIFO) {

		this.SETTINGWithScoreboard = nonDecWithDH;
		this.SETTINGWithValid = decWithValid;
		this.SETTINGWithAddr = decWithAddr;
		this.SETTINGwithInputFIFO = decWithInpFIFO;

	}

	/**
	 * SCALBackendAPI impl: Register a valid condition (with a stage and an optional ISAX name) to apply for all matching RdIValid outputs.
	 * @param valid_signal_from_core signal from the core with a set stage and an optional ISAX name ("" to apply to all ISAXes)
	 */
	public void RegisterRdIValid_valid(CustomCoreInterface valid_signal_from_core)
	{
		int stage = valid_signal_from_core.stage;
		customCoreInterfaces.add(valid_signal_from_core);
		RdIValidStageDesc stageDesc = stage_containsRdIValid.get(stage);
		if (stageDesc == null)
		{
			stageDesc = new RdIValidStageDesc();
			stage_containsRdIValid.put(stage, stageDesc);
		}
		stageDesc.validCond_isax.put(valid_signal_from_core.instr_name, " && " + valid_signal_from_core.getSignalName(myLanguage, false));
	}
	/**
	 * SCALBackendAPI impl: Request SCAL to generate a RdIValid interface, regardless of whether an ISAX uses it directly.
	 */
	public void RequestRdIValid(int stage, String instruction)
	{
		RdIValidStageDesc stageDesc = stage_containsRdIValid.get(stage);
		if (stageDesc == null)
		{
			stageDesc = new RdIValidStageDesc();
			stage_containsRdIValid.put(stage, stageDesc);
		}
		stageDesc.instructions.add(instruction);
	}

	/**
	 * SCALBackendAPI impl: Register a spawnAllowed adjacent node to SCAL.
	 * @param spawnAllowed Adjacent spawnAllowed node
	 */
	public void SetHasAdjSpawnAllowed(SCAIEVNode spawnAllowed) {
		SCAIEVNode parentNode = BNode.GetSCAIEVNode(spawnAllowed.nameParentNode);
		if(parentNode != null && !parentNode.nameQousinNode.isEmpty()) {
			spawnAllowed = new SCAIEVNode(
				spawnAllowed.replaceRadixNameWith(parentNode.familyName),
				spawnAllowed.size,
				spawnAllowed.isInput);
		}
		adjSpawnAllowedNodes.add(spawnAllowed);
	}

	/**
	 * SCALBackendAPI impl: Register an override of an otherwise shared read operation for a particular ISAX.
	 * Does not work for operations with adjacent nodes, and does not work for RdFlush.
	 * @param node operation node
	 * @param instruction ISAX to apply the override to
	 */
	public void RegisterNodeOverrideForISAX(SCAIEVNode node, String instruction) {
		HashSet<String> isaxSet = nodePerISAXOverride.get(node);
		if (isaxSet == null) {
			isaxSet = new HashSet<String>();
			nodePerISAXOverride.put(node, isaxSet);
		}
		isaxSet.add(instruction);
	}

	/**
	 * SCALBackendAPI impl: Prevent instantiation of SCAL's data hazard unit, while retaining the optional address FIFOs and valid shiftregs for WrRD_spawn.
	 */
	public void SetUseCustomSpawnDH()
	{
		SETTINGWithScoreboard = false;
	}

	/**
	 * Prevent SCAL from stalling the core if a given type of spawn operation (by family name) is to be committed.
	 *  -> The core backend may handle collisions between in-pipeline and spawn operations in a different way.
	 *  -> If the core backend  injects a writeback into the instruction pipeline, stalling the pipeline until completion could lead to a deadlock.
	 * @param spawn_node Node whose family do not need stalls on commit
	 */
	public void DisableStallForSpawnCommit(SCAIEVNode spawn_node)
	{
		SETTINGdisableSpawnFireStall_families.add(spawn_node.nameQousinNode.isEmpty() ? spawn_node.name : spawn_node.familyName);
	}

	/**
	 * Initialize SCAL, prepare for SCALBackendAPI calls.
	 * 
	 */
	public void Prepare (HashMap <String,SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr,  HashMap<SCAIEVNode, HashMap<String, Integer>> spawn_instr_stage, Core core) {
		this.ISAXes = ISAXes; 
		this.op_stage_instr = op_stage_instr; 
			
		this.spawn_instr_stage = spawn_instr_stage;
		this.core = core;
		
		
		// Populate virtual core 
	    PopulateVirtualCore();
	    this.myLanguage = new Verilog(new FileWriter(""),virtualBackend); // core needed for verification purposes  
	}
	
	public void PrepareEarliest(HashMap<SCAIEVNode, Integer> node_earliestStageValid ) {
		this.node_earliestStageValid = node_earliestStageValid;
	}
	
	/**
	 * Generate module to contain common logic. Common = the same across multiple cores
	 * 
	 */
	public void Generate (String inPath, String outPath) {
		System.out.println("INFO. Generating SCAL for core: "+core.GetName());
		this.toFile = new FileWriter(inPath);
	    this.myLanguage = new Verilog(toFile,virtualBackend); // core needed for verification purposes    
	    myLanguage.BNode = BNode;
	    String interfToISAX = "",  interfToCore = "",  declarations = "",  logic = "", otherModules = "";
	     

		
	    //////////////////////   GENERATE LOGIC FOR INTERNAL STATES ////////////
    	// Generate module for private registers
	    System.out.println("INFO. First generating ISAX private registers");  
 	    SCALState privateregs = new SCALState(BNode,op_stage_instr, ISAXes , core);		
 	    node_earliestStageValid.putAll(privateregs.PrepareEarliest()); // Write earliest stages for DH mechanism
 		otherModules += privateregs.InstAllRegs();
 		// Instantiate module within SCAL
 		if(privateregs.hasPrivateRegs)
 				logic += privateregs.GetInstantiationText();
 		
 		////////////////////// Additional interface to Core ////////////
	    // Check what additional signals are required 
	    int maxStageRdInstr = AddRequiredOperations(privateregs); 
	     
	    // Populate virtual core 
	    PopulateVirtualCore();
	     
			
		  //////////////////////  GENERATE INTERFACE & DEFAULT/FRWRD LOGIC /////////////////
    	 // Go through required operations/nodes 
	     LinkedHashSet<String> newInterfaceToISAX = new LinkedHashSet<String> (); // LinkedHashSet because it's annoying to have a rndom order in the interfaces
	     LinkedHashSet<String> newInterfaceToCore = new LinkedHashSet<String> ();
	     
    	 for(SCAIEVNode operation : this.op_stage_instr.keySet()) {
    		 // Go through stages...
    		 Set<Integer> stages = this.op_stage_instr.get(operation).keySet();
    		 // For nodes that require sigs also in earlier stages
    		 int latestNodeStage = core.maxStage; 
    		 if(operation.commitStage !=0)
    			 latestNodeStage = operation.commitStage;
    		 for (int stage = node_earliestStageValid.getOrDefault(operation, Integer.MAX_VALUE); stage <=latestNodeStage; ++stage) {
    			if(!stages.contains(stage))
    				declarations += GenerateAllInterfToCore(operation, stage,newInterfaceToCore);	
    		 }
    		 // For main user nodes
    	     for(int stage: stages) {
	    		 if(ContainsOpInStage(operation,stage)) {
		    		 // Generate interface for main node if output from ISAX 
	    			 logic        += GenerateAllInterfToISAX(operation, stage,newInterfaceToISAX); // Ar return: It also generates the default assigns for RdNodes
	    			 declarations += GenerateAllInterfToCore(operation, stage,newInterfaceToCore); // As return: It also generates declarations for sigs that are not really on the interface
		    			 
	    		 }
	    	 }	    	 
	     }
		//Add custom interfaces between Core and SCAL.
		for (CustomCoreInterface intf : this.customCoreInterfaces) {
			SCAIEVNode customNode = new SCAIEVNode(intf.name, intf.size, intf.isInputToSCAL);
			String newinterf = this.CreateAndRegisterTextInterfaceForCore(customNode, intf.stage, intf.instr_name, intf.dataT);
			newInterfaceToCore.add(newinterf);
		}
	  
    	 
    	 
    	 
	     //////////////////////   GENERATE LOGIC FOR RdIValid ////////////
    	 for(int stage = 0 ; stage <= core.maxStage; stage++) {
			RdIValidStageDesc iValidStageDesc = stage_containsRdIValid.get(stage);
			if (iValidStageDesc == null) iValidStageDesc = new RdIValidStageDesc();
			String condStageValid = iValidStageDesc.validCond_isax.getOrDefault("", "") + " && !"+this.myLanguage.CreateNodeName(BNode.RdFlush.NodeNegInput(),stage, "");
			if(!iValidStageDesc.instructions.isEmpty())
				for(String instrName : iValidStageDesc.instructions) {
					if(!ISAXes.get(instrName).HasNoOp()) { // If it's an instruction without opcode, we can not generate a RdIValid signal ( we can not decode). That logic is by default always enabled
						String nameIValidLocal = this.myLanguage.CreateLocalNodeName(new SCAIEVNode(FNode.RdIValid.name, stage, true), stage, instrName);
						// TODO clarify what was meant here
						String condValidInstr = condStageValid + iValidStageDesc.validCond_isax.getOrDefault(instrName, "");
						
						declarations += this.myLanguage.CreateDeclSig(FNode.RdIValid,stage,instrName,false);
						if(ContainsOpInStage(FNode.RdIValid, stage) && this.op_stage_instr.get(FNode.RdIValid).get(stage).contains(instrName))
							logic += "assign "+ this.myLanguage.CreateNodeName(FNode.RdIValid, stage,instrName)+" = "+ nameIValidLocal +";\n";
						if(maxStageRdInstr >= stage && stage >= this.core.GetNodes().get(FNode.RdInstr).GetExpensive()) // if instruction present in this stage as register
							logic += "assign "+nameIValidLocal+" = "+ this.myLanguage.Decode(instrName, this.myLanguage.CreateRegNodeName(BNode.RdInstr, stage, ""), ISAXes)+condValidInstr+";\n" ;
						else if(this.core.GetNodes().get(FNode.RdInstr).GetExpensive()>stage) // not a register required in SCAL for RdInstr
							logic += "assign "+nameIValidLocal+" = "+ this.myLanguage.Decode(instrName, this.myLanguage.CreateNodeName(BNode.RdInstr.NodeNegInput(), stage, ""), ISAXes)+condValidInstr+";\n" ;
						else {
							declarations += this.myLanguage.CreateDeclReg(FNode.RdIValid,stage,instrName);
							logic += "assign "+nameIValidLocal+" = "+ this.myLanguage.CreateRegNodeName(BNode.RdIValid, stage, instrName)+condValidInstr+";\n" ;
							logic += this.myLanguage.CreateTextRegReset(this.myLanguage.CreateRegNodeName(BNode.RdIValid, stage, instrName), this.myLanguage.CreateLocalNodeName(FNode.RdIValid, stage-1, instrName), this.myLanguage.CreateNodeName(BNode.RdStall.NodeNegInput(), stage-1, ""));
						
						}
					}
				}
		}
	     
	     ////////////////////// Add RdNode Registers ///////////////////
	     // If signal not present in the core's pipeline, we need to add registers within SCAIE-V
	     for(SCAIEVNode rdNode : addRdNodeReg.keySet()) 
		     for(int stage : addRdNodeReg.get(rdNode)) {
		    	 if(stage<= this.core.maxStage) { // NOT a spawn. 	 
			    	 String signalName = this.myLanguage.CreateRegNodeName(rdNode, stage, "");
			    	 String signalAssign = this.myLanguage.CreateRegNodeName(rdNode, stage-1, "");
			    	 declarations += this.myLanguage.CreateDeclReg(rdNode,stage,"");
			    	 if(!addRdNodeReg.get(rdNode).contains(stage-1))
			    		 signalAssign = this.myLanguage.CreateNodeName(rdNode.NodeNegInput(), stage-1, "");
			    	 logic += this.myLanguage.CreateTextRegReset(signalName, "("+this.myLanguage.CreateNodeName(BNode.RdFlush.NodeNegInput(), stage-1, "")+") ? 0 : "+signalAssign, this.myLanguage.CreateNodeName(BNode.RdStall.NodeNegInput(), stage-1, ""));
			     
		    	 }
		     }
	     
	     //////////////////////   GENERATE LOGIC FOR WrNodes ////////////
	     // generate logic for all Wr Nodes (inputs to Core, outputs from ISAX)
	     // It is basically case(1) rdIValid_ISAX1: WrNode = WrNode_ISAX1; rdIValid_ISAX2: WrNode = WrNode_ISAX2...;
	     // WrStall should not be generated in the same way. The stall to core is a || of all stalls from ISAX
	     
	     // Step 1: Generate Valid bits for earlier stages. For exp for mem operations, core needs to know if it.s a mem op before mem stage
	     HashMap<SCAIEVNode, Integer> node_LatestStageValid = new HashMap<SCAIEVNode, Integer>();
	     if(!this.node_earliestStageValid.isEmpty()) {                  // if core has any nodes which requrie valid signals in earlier stages
	    	 for(SCAIEVNode node : node_earliestStageValid.keySet()) {  // go through these nodes
	    		 SCAIEVNode corrctPropNode = this.BNode.GetSCAIEVNode(node.name); // get newest properties for this node (commit stage)
	    		 if(this.op_stage_instr.containsKey(corrctPropNode))      {        // if the user actually wants this node (otherwise no logic is added)
	    			 HashMap<Integer,HashSet<String>> stage_lookAtISAX = new  HashMap<Integer,HashSet<String>>();
	    			 int latestStage = 0; 	    			 
	    			 for (int stage: this.op_stage_instr.get(corrctPropNode).keySet()) {
	    				 if(latestStage<stage)
	    					 latestStage = stage;
	    				 stage_lookAtISAX.put(stage,  this.op_stage_instr.get(corrctPropNode).get(stage));
	    			 }
	    			 node_LatestStageValid.put(corrctPropNode, latestStage);
	    			 for(int stage = node_earliestStageValid.get(node); stage <= latestStage; stage++) 
	    				 logic += this.myLanguage.CreateValidReqEncodingEarlierStages(stage_lookAtISAX, ISAXes, stage, corrctPropNode, node_earliestStageValid.get(corrctPropNode));
	    		 }
	    	 }
	     }
	     
	     // Step 2: generate Valid bits for all other nodes 
	     for (SCAIEVNode node : this.op_stage_instr.keySet())
	    	 for(int stage : this.op_stage_instr.get(node).keySet()) {
	    		 if(node.isInput && !node.isSpawn() && node!=FNode.WrFlush && node!=FNode.WrStall) {    			 
	    			 logic += this.myLanguage.CreateValidEncodingIValid(this.op_stage_instr.get(node).get(stage), ISAXes, stage, node, node, 0);    			 
	    		 }
	    		 for(AdjacentNode adjacent : BNode.GetAdj(node)) {
	    			 SCAIEVNode adjNode = BNode.GetAdjSCAIEVNode(node, adjacent);
	    			 boolean earliestOK =  !node_earliestStageValid.containsKey(node) || ( adjacent != AdjacentNode.validReq);
	    			 if(adjNode.isInput && !adjNode.isSpawn() && earliestOK) {
	    				 // if it is a RdCustom node, we need to consider in the read regsfile stage all reads, also from later stages 
	    				 // Code bellow not optimal approach. It's a fast solution which ensures no bugs are addded
	    				 if(this.BNode.IsUserBNode(node) && !node.isInput) { // if it's a user state node and it's inpt (RdCustomReg)
	    					 if(stage == node.commitStage) { // if current stage is > than its read stage, we don't need the logic. Actual logic is needed by DH mechanism in read stage
	    						 HashSet<String> allReads = new  HashSet<String> (); 
	    						 for(int stage_2 : this.op_stage_instr.get(node).keySet())
	    							 allReads.addAll(this.op_stage_instr.get(node).get(stage_2));
	    						 logic += this.myLanguage.CreateValidEncodingIValid(allReads, ISAXes, stage, node,adjNode,1 ); // consider this is based on rdivalid, so this must also be added
	    					 }  					 
	    				 } else // for all othe rnodes beside RdCustomUserRegister
	    					 logic += this.myLanguage.CreateValidEncodingIValid(this.op_stage_instr.get(node).get(stage), ISAXes, stage, node,adjNode,1 );
	    			 }
	    		 }	    			 
	    	 }
	     
	     
	     //////////////////////   GENERATE LOGIC FOR Datahaz common nodes (no spawn) ////////////
	     // Here a signal required for Datahazard unit is generated. It says if data valid or not (validData). This in combination with valid request signal generated above (validReq) can be used to implement a DH mechanism 
	     // Not generated for all cores. Just the ones that require WrRD or WrUserNode in earlier stages 
	     if(!this.node_earliestStageValid.isEmpty())
	    	 for(SCAIEVNode node : node_earliestStageValid.keySet()) {
		    	 if(node.DH && this.op_stage_instr.containsKey(node)) { // If it requires DH and is requested earlies, is a valid DH node. Info: ORCA also requests it. If not used, the WrRD_DataValid will be optimized by synth tool
		    		 HashMap<Integer,HashSet<String>> stage_lookAtISAX = new  HashMap<Integer,HashSet<String>>();	    			 
	    			 for (int stage: this.op_stage_instr.get(node).keySet()) {
	    				 stage_lookAtISAX.put(stage,  this.op_stage_instr.get(node).get(stage));
	    			 }
	    			 for(int stage = node_earliestStageValid.get(node); stage <= node_LatestStageValid.get(node); stage++) { 
						logic += this.myLanguage.CreateValidDataEncodingEarlierStages(stage_lookAtISAX, ISAXes, stage, node );
						SCAIEVNode nodeValidData =  BNode.GetAdjSCAIEVNode(node, AdjacentNode.validData);
						if(!BNode.IsUserBNode(node)) {
							String newinterf = this.CreateAndRegisterTextInterfaceForCore(nodeValidData.NodeNegInput(), stage, "", "reg");
							newInterfaceToCore.add(newinterf);
						} else
							declarations += (myLanguage.CreateDeclSig(nodeValidData.NodeNegInput(), stage, "", nodeValidData.isInput, myLanguage.CreateNodeName(nodeValidData.NodeNegInput(), stage, "")));
	    			 }
		    	 }
		     }
	    	 
	     
         ////////////////////// LOGIC FOR WRFLUSH WRPC //////////////////
		String wrFlush = "";
		for(int stage = this.core.maxStage; stage>=0; stage--) {
			if(this.ContainsOpInStage(FNode.WrFlush, stage)) {
				//Create a local signal that ORs WrFlush of each ISAX.
		    	declarations += this.myLanguage.CreateDeclSig(FNode.WrFlush,stage,"",false);
		    	int _stage = stage;
		    	logic += this.myLanguage.CreateAssign(
		    			   myLanguage.CreateLocalNodeName(BNode.WrFlush, stage, ""), 
		    			   this.op_stage_instr.get(BNode.WrFlush).get(stage).stream()
		    		        .map((isax) -> myLanguage.CreateNodeName(BNode.WrFlush, _stage, isax))
		    		        .reduce((s1,s2) -> s1 + " || " + s2).orElse("1'b0"));
		    	
				wrFlush =  GenerateText.OpIfNEmpty(wrFlush, this.myLanguage.GetDict(DictWords.logical_or))+this.myLanguage.CreateLocalNodeName(BNode.WrFlush, stage, "");
			}
			if(this.ContainsOpInStage(FNode.WrPC, stage+1))
				for(String instr : this.op_stage_instr.get(FNode.WrPC).get(stage+1))
					wrFlush = GenerateText.OpIfNEmpty(wrFlush, this.myLanguage.GetDict(DictWords.logical_or))+this.myLanguage.CreateNodeName(BNode.WrPC_valid.NodeNegInput(), stage+1, "");
			if(!wrFlush.isEmpty()) 
				logic += this.myLanguage.CreateAssign(myLanguage.CreateNodeName(FNode.WrFlush.NodeNegInput(), stage, "") , wrFlush); // assign flush signal which goes to core
			// New spawn flush concept: rdflush comes only from core
			//if(this.ContainsOpInStage(FNode.RdFlush, stage)) // TODO how does this work with spwn?
			//	logic += this.myLanguage.CreateAssign(myLanguage.CreateNodeName(FNode.RdFlush, stage, "") , GenerateText.OpIfNEmpty(wrFlush, this.myLanguage.GetDict(DictWords.logical_or) ) +  myLanguage.CreateNodeName(FNode.RdFlush.NodeNegInput(), stage, "") );
		
		}
	    
		
	    ////////////////////// LOGIC FOR WR STALL //////////////////////
	    // Has to be handled separately because spawn operations need to update it. 
	    String [] stallStagesPrefix = new String[ this.core.maxStage+1];
	    String [] stallStages = new String[ this.core.maxStage+1];
	    for(int stage= this.core.maxStage; stage >=0;stage--) {
	    	stallStages[stage] ="";
		    if(this.ContainsOpInStage(BNode.WrStall, stage)) {
				//Create a local signal that ORs WrStall of each ISAX.
		    	declarations += this.myLanguage.CreateDeclSig(FNode.WrStall,stage,"",false);
		    	int _stage = stage;
		    	logic += this.myLanguage.CreateAssign(
		    			   myLanguage.CreateLocalNodeName(BNode.WrStall, stage, ""), 
		    			   this.op_stage_instr.get(BNode.WrStall).get(stage).stream()
		    		        .map((isax) -> myLanguage.CreateNodeName(BNode.WrStall, _stage, isax))
		    		        .reduce((s1,s2) -> s1 + " || " + s2).orElse("1'b0"));
		    	stallStagesPrefix[stage] = myLanguage.CreateLocalNodeName(BNode.WrStall, stage, "");
		    }
		    else if(stage!= this.core.maxStage && !stallStagesPrefix[stage+1].isEmpty()) {
		    	stallStagesPrefix[stage] = myLanguage.CreateNodeName(BNode.WrStall.NodeNegInput(), stage+1, "");
		    } else
		    	stallStagesPrefix[stage] ="";
	    }
	    
	    
	    ////////////////////// GENERATE LOGIC FOR Spawn ////////////	
	    int spawnStage = this.core.GetSpawnStage();
	    HashMap<String, String > priority = new HashMap<String, String >();
	    String logicToCoreSpawn = "";
	    for (SCAIEVNode node : this.spawn_instr_stage.keySet()) {
	     System.out.println("INFO. SCAL. Generating logic for spawn node: "+node);
	     
	     // op_stage_instr contains as spawn node "node" only if there are any instr that require decoupled. Hence , if bellow will only generate for decoupled instr
	     if(this.ContainsOpInStage(node, spawnStage) && node.allowMultipleSpawn) { // if user opts for stall-based solution, it's not possible to have multiple results in parallel and the core anyway waits for spawn result (so no fire logic)
	    	 String priorityStr = "";
	    	 String fireNodeSuffix = "";
	    	
	    	 // Prepare for qousin if needed (with qousin = mem for exp, wrrrd is normal)
	    	 if(!node.nameQousinNode.isEmpty() && priority.containsKey(node.nameQousinNode)) { // this is valid for exp for memory spawn
	    		 priorityStr = priority.get(node.nameQousinNode); // if we had in previous for iteration a rdMem and now we have a wrMem, we need the priority list from rdMem
	 			 fireNodeSuffix = node.familyName ;
	    	 }else {
	    		 String fireNodeName = node.name;
	    		 SCAIEVNode fireNode = BNode.GetSCAIEVNode(fireNodeName);
	    		 fireNodeSuffix = fireNode.name.split("_")[0] ;
	    		 if(!node.nameQousinNode.isEmpty())
	    			 fireNodeSuffix = node.familyName ;	    				
	    	 }
	    	 SCAIEVNode cousinNode = node.nameQousinNode.isEmpty() ? null : BNode.GetSCAIEVNode(node.nameQousinNode); 
	    	 boolean cousinNodePresent = this.spawn_instr_stage.containsKey(cousinNode) && this.op_stage_instr.get(cousinNode).containsKey(spawnStage);
	    	
	    	  
	    	// Optional Logic with INput FIFO
	    	logic += AddOptionalInputFIFO(node,myLanguage.CreateRegNodeName(this.ISAX_fire2_r, spawnStage, fireNodeSuffix));
	    	 
	    	 
	    	 // Declare fire signals
	    	 if(!(!node.nameQousinNode.isEmpty() && priority.containsKey(node.nameQousinNode))) // signals already declared by other Wr/RdMem Spawn node
	    		 declarations += "wire ["+this.ISAX_spawn_sum.size+"-1:0] "+ myLanguage.CreateLocalNodeName(this.ISAX_spawn_sum, spawnStage, fireNodeSuffix)+";\n"
	    	 		+ "wire  "+ myLanguage.CreateLocalNodeName(this.ISAX_fire_s, spawnStage, fireNodeSuffix)+";\n"
	    	 		+ "reg "+ myLanguage.CreateRegNodeName(this.ISAX_fire_r, spawnStage, fireNodeSuffix)+";\n"
	    	 		+ "reg "+ myLanguage.CreateRegNodeName(this.ISAX_fire2_r, spawnStage, fireNodeSuffix)+";\n";
	    	 
	    	 
	    	 // For main node, create regs , logic of the regs and compute output to core, and compute output to ISAX
		   	 for(String ISAX :this.op_stage_instr.get(node).get(spawnStage) ) {
		   		 // declare sigs
		   		 declarations += myLanguage.CreateDeclReg(node, spawnStage, ISAX);
		   		 // regs logic
		   		 logic += LogicRegsSpawn(node,BNode.GetAdjSCAIEVNode(node, SCAIEVNode.GetValidRequest()), ISAX ,spawnStage,priorityStr,fireNodeSuffix);
		   		 // set signals to be sent to core
		   		 logicToCoreSpawn += LogicToCoreSpawn(ISAX,spawnStage, node, node,priorityStr );
		   		 // set signals to be sent to ISAX 
		   		 if(!node.isInput)
					 logic += myLanguage.CreateAssign(myLanguage.CreateFamNodeName(node,spawnStage,ISAX,false), myLanguage.CreateNodeName(node.NodeNegInput(),spawnStage,""));
		   		// update priority list
		   		 priorityStr   = Verilog.OpIfNEmpty(priorityStr, " || ") + myLanguage.CreateRegNodeName(BNode.GetAdjSCAIEVNode(node, SCAIEVNode.GetValidRequest()), spawnStage,ISAX);
		   	 }
		   	 
		   	 // Compute sum (decides if we still have spawn) + compute fire logic (ISAX_fire_r, ISAX_fire2_r)
		   	 if(node.nameQousinNode.isEmpty() || !cousinNodePresent || priority.containsKey(node.nameQousinNode)) {
		   		 String sumNodeName =  myLanguage.CreateLocalNodeName(this.ISAX_spawn_sum, spawnStage, fireNodeSuffix);
		    	 logic += "assign "+sumNodeName +" = "+this.ISAX_spawn_sum.size+"'("+priorityStr.replace(" || ", ") + "+this.ISAX_spawn_sum.size+"'(")+");\n";
		    	 logic += "assign "+ myLanguage.CreateLocalNodeName(this.ISAX_fire_s, spawnStage,fireNodeSuffix)+" = "+priorityStr.replace(myLanguage.regSuffix,"_s")+";\n";
		    	 String stall3 = "";
	    		 if (ContainsOpInStage(BNode.WrStall,this.core.GetStartSpawnStage()))
	    				stall3 = myLanguage.CreateLocalNodeName(BNode.WrStall, this.core.GetStartSpawnStage(), "");
	    		 logic +=CommitSpawnFire (node, stall3, spawnStage,fireNodeSuffix );
		     }
		   	 
		   	 if(!node.nameQousinNode.isEmpty())
		   		 priority.put(node.name, priorityStr);
		   	 
		   	// For adjacent nodes, create: logic to isax, logic to core and sigs declarations
		   	 for(AdjacentNode adjacent : BNode.GetAdj(node)) if (adjacent != AdjacentNode.spawnAllowed){
		   		 priorityStr = "";
		    	 if(!node.nameQousinNode.isEmpty() && priority.containsKey(node.nameQousinNode))
		   			 priorityStr = priority.get(node.nameQousinNode);
	    		 SCAIEVNode adjNode = BNode.GetAdjSCAIEVNode(node, adjacent);
	    		 for(String ISAX :this.op_stage_instr.get(node).get(spawnStage) ) {
	    			 if(adjNode.DefaultMandatoryAdjSig() && !adjNode.isInput) {
	    				 String priorityLogic = "";
	    				 if(!priorityStr.isEmpty())
	    					 priorityLogic = " && !("+priorityStr+") ";
	    				 // Compute Outputs to ISAX
	    				 logic += myLanguage.CreateText1or0(myLanguage.CreateFamNodeName(adjNode,spawnStage,ISAX,false), 
						                                    myLanguage.CreateRegNodeName(this.ISAX_fire2_r, spawnStage,fireNodeSuffix)
						                                    +priorityLogic
						                                    +" && "+myLanguage.CreateRegNodeName(BNode.GetAdjSCAIEVNode(node, AdjacentNode.validReq), spawnStage, ISAX)
						                                    +" && "+myLanguage.CreateNodeName(adjNode.NodeNegInput(),spawnStage,"") );
	    			 } 
	    			 // Declare sigs
	    			 declarations += myLanguage.CreateDeclReg(adjNode, spawnStage, ISAX);
	    			 // Compute spawn signals to core
	    			 logicToCoreSpawn += LogicToCoreSpawn(ISAX,spawnStage, adjNode, node,priorityStr );
	    			 logic += LogicRegsSpawn(adjNode,BNode.GetAdjSCAIEVNode(node, SCAIEVNode.GetValidRequest()), ISAX ,spawnStage,priorityStr,fireNodeSuffix);    
		   			 if(!priorityStr.isEmpty())
		   				 priorityStr += " || ";
		   			 priorityStr   +=myLanguage.CreateRegNodeName(BNode.GetAdjSCAIEVNode(node, SCAIEVNode.GetValidRequest()), spawnStage,ISAX);
	    		 }
		  	}
			    

		   	 
		   	 // Stall stages to commit result of decoupled node 
		   	 // TODO @fm general solution for line bellow. For the moment workaround fast
		    // Usually spawn simply stalls entire core. For mem till mem start stage, for wrrd entire core. 
			int maxStall = core.maxStage;
			if(node.equals(BNode.WrMem_spawn) || node.equals(BNode.RdMem_spawn) )
				maxStall = core.GetNodes().get(BNode.WrMem).GetEarliest();
		   	 if (!SETTINGdisableSpawnFireStall_families.contains(fireNodeSuffix) || !this.core.GetName().contains("cva")) {
		   		 for(int stage=0; stage <=maxStall;stage++) {
			    	if(!stallStages[stage].isEmpty())
			    		stallStages[stage] += " || ";
			    	stallStages[stage] +=  myLanguage.CreateRegNodeName(this.ISAX_fire2_r, spawnStage, fireNodeSuffix);
			     }
			    
    		 }
	     } else if(this.ContainsOpInStage(node, spawnStage)) { // WrPC Spawn
	    	 logic += myLanguage.CreateAssign(myLanguage.CreateNodeName(node.NodeNegInput(), spawnStage, ""), myLanguage.CreateNodeName(node, spawnStage, ""));
	    	 SCAIEVNode validNode = BNode.GetAdjSCAIEVNode(node, AdjacentNode.validReq);
	    	  
	    	 logic += myLanguage.CreateAssign(myLanguage.CreateNodeName(validNode.NodeNegInput(), spawnStage, ""), myLanguage.CreateNodeName(validNode, spawnStage, ""));
	    	 // For the moment all internal state spawn ops allow multiple spawn writes and have DH logic, so it should not enter this 
	    	 if (this.BNode.IsUserBNode(node)) {
	    		 // spawnAllowed has no interface to ISAX
		    	 //SCAIEVNode allowedNode = BNode.GetAdjSCAIEVNode(node, AdjacentNode.spawnAllowed);
		    	 //logic += myLanguage.CreateAssign(myLanguage.CreateNodeName(allowedNode, spawnStage, ""), myLanguage.CreateNodeName(allowedNode.NodeNegInput(), spawnStage, ""));
		    	 SCAIEVNode respNode = BNode.GetAdjSCAIEVNode(node, AdjacentNode.validResp);
		    	 logic += myLanguage.CreateAssign(myLanguage.CreateNodeName(respNode, spawnStage, ""), myLanguage.CreateNodeName(respNode.NodeNegInput(), spawnStage, ""));
	    		 
	    	 } 
	     }
	    }
	    if(!logicToCoreSpawn.isEmpty())
	    	logic += myLanguage.CreateInAlways(false, logicToCoreSpawn);
	    
	    
	    
	    // Additional Spawn logic  , like DH , store address, store valid register bit
		boolean DHReq = false;
    	System.out.println("Info. SCAL. spawn_instr_stage = "+spawn_instr_stage);
    	System.out.println("Info. SCAL. op_ins = "+this.op_stage_instr);
    	for(SCAIEVNode node : spawn_instr_stage.keySet() ) {
    		
    		if(node.DH || node.name.contains("Mem")) { // for internal regs with DH, RegF or mem transfers
    		 String fireNodeSuffix = "";
	    	 if(!node.nameQousinNode.isEmpty() && priority.containsKey(node.nameQousinNode)) {
	    		 fireNodeSuffix = node.familyName ;
	    	 }else {
	    		 String fireNodeName = node.name;
	    		 SCAIEVNode fireNode = BNode.GetSCAIEVNode(fireNodeName);
	    		 fireNodeSuffix = fireNode.name.split("_")[0] ;
	    		 if(!node.nameQousinNode.isEmpty())
	    			 fireNodeSuffix = node.familyName ;	    				
	    	 }
    		 // Committed addr required for DH spawn
	    	 // if op_stage_instr contains node, it means there are decoupled instr
	    	 if(this.ContainsOpInStage(node, spawnStage) && node.DH) {
    			 String interf_commited_rd_spawn = "";
    			 String interf_commited_rd_spawn_valid = "";
        		 for(String ISAX : spawn_instr_stage.get(node).keySet()) {
        			 interf_commited_rd_spawn = this.CreateAndRegisterTextInterfaceForISAX(new SCAIEVNode(BNode.commited_rd_spawn+"_"+node, BNode.commited_rd_spawn.size, false), spawnStage, ISAX, false, "");
        			 interf_commited_rd_spawn_valid = this.CreateAndRegisterTextInterfaceForISAX(new SCAIEVNode(BNode.commited_rd_spawn_valid+"_"+node, BNode.commited_rd_spawn_valid.size, false), spawnStage, ISAX, false, "");
        		 }
        		 if (!interf_commited_rd_spawn.isEmpty()) {
        			 newInterfaceToISAX.add(interf_commited_rd_spawn);
        			 if(BNode.GetAdjSCAIEVNode(node, SCAIEVNode.GetAddr())!= null)
        				 logic += "assign "+myLanguage.CreateNodeName(BNode.commited_rd_spawn+"_"+node, spawnStage, "",false)+" = "+myLanguage.CreateNodeName(BNode.GetAdjSCAIEVNode(node, AdjacentNode.addr).NodeNegInput(), spawnStage, "")+";\n";
        			 else 
        				 logic += "assign "+myLanguage.CreateNodeName(BNode.commited_rd_spawn+"_"+node, spawnStage, "",false)+" = 0; // constant to be removed, no addr for this signal\n";
        		 }
        		 if (!interf_commited_rd_spawn_valid.isEmpty()) {
        			 newInterfaceToISAX.add(interf_commited_rd_spawn_valid);
        			 logic += "assign "+myLanguage.CreateNodeName(BNode.commited_rd_spawn_valid+"_"+node, spawnStage, "",false)+" = "+ myLanguage.CreateRegNodeName(this.ISAX_fire2_r, spawnStage,fireNodeSuffix)+";\n";
        			 
        		 }   			  				 
   		 
    		 }
    		 DHReq = true;
    		 String flush = "";
    		 String allIValid = "";
    		 String stallShiftReg = "";
    		 String stallFrSpawn = "";
    		 String stallFrCore = "";
    		 int startSpawnStage =  this.core.GetStartSpawnStage();
    		
    		 // Construct stall, flush signals for optional modules. Required by the DH spawn module, shift reg module for ValidReq, FIFO for addr
    		 if(startSpawnStage<this.core.maxStage) {
				 flush += "}";
				 stallShiftReg += myLanguage.CreateNodeName(BNode.RdStall.NodeNegInput(), startSpawnStage, "")+"}"; // already contains DH stall, ISAX stall, barrier stall
				 stallFrSpawn += "}";
				 if(ContainsOpInStage(BNode.WrStall,startSpawnStage))
					 stallFrSpawn = myLanguage.CreateLocalNodeName(BNode.WrStall, startSpawnStage, "")+stallFrSpawn;
				 else 
					 stallFrSpawn = "1'b0"+stallFrSpawn;
				 stallFrCore =  myLanguage.CreateNodeName(BNode.RdStall.NodeNegInput(), startSpawnStage, "")+"}"+stallFrCore;
				 for (int flStage = this.core.GetStartSpawnStage()+1; flStage<=this.core.maxStage;flStage++) {
					 if(flStage> startSpawnStage+1)
						 flush = ","+flush;
					 if(flStage <= core.GetNodes().get(BNode.RdFlush).GetLatest())
					 {
						 flush = myLanguage.CreateNodeName(BNode.RdFlush.NodeNegInput(),  flStage, "")+flush;
						 if(ContainsOpInStage(BNode.WrFlush,flStage))
							 flush = myLanguage.CreateLocalNodeName(BNode.WrFlush, flStage, "")+ " || "+flush;
					 }
					 else
						 flush = "1'b0" + flush;
					 if(ContainsOpInStage(BNode.WrStall,flStage))
    					 stallFrSpawn = myLanguage.CreateLocalNodeName(BNode.WrStall, flStage, "")+","+stallFrSpawn;
    				 else 
    					 stallFrSpawn = "1'b0,"+stallFrSpawn;
					 stallShiftReg = myLanguage.CreateNodeName(BNode.RdStall.NodeNegInput(), flStage, "") +","+ stallShiftReg;
					 if(ContainsOpInStage(BNode.WrStall,flStage))
						 stallShiftReg =  myLanguage.CreateLocalNodeName(BNode.WrStall, flStage, "")+" || "+stallShiftReg;
					 stallFrCore = myLanguage.CreateNodeName(BNode.RdStall.NodeNegInput(), flStage, "")+","+stallFrCore;
				 }
				 flush = "{"+flush;
				 stallFrSpawn = "{"+stallFrSpawn;
				 stallShiftReg = "{"+stallShiftReg;
				 stallFrCore = "{"+stallFrCore ;
			 }
    		 // Some declarations won't be used, but doesn.t matter
    		 declarations += "wire to_CORE_stall_RS_"+node+"_o_s;\n";
    		 
    		 ///////////////////    SCAL SPAWN MODULES /////////////////////////
    		 // Instantiate modules for FIFO Addr, shift reg for valid request bit...
    		 for(String ISAX : spawn_instr_stage.get(node).keySet()) {
    			
    			 if(!allIValid.isEmpty())
    				 allIValid += " || ";
	    		 allIValid += myLanguage.CreateLocalNodeName(BNode.RdIValid.NodeNegInput(), startSpawnStage, ISAX);
	    		 
	    		 // RdInstr required by the DH module to determine result address. If core can provide it, read it from interface, otherwise read it from local register 
	    		 if(this.core.GetNodes().get(BNode.RdInstr).GetExpensive()>startSpawnStage)
	    			 AddToCoreInterfHashMap(BNode.RdInstr, startSpawnStage);
	    		 else {
	    			 declarations += "wire [31:0] "+ myLanguage.CreateNodeName(BNode.RdInstr.NodeNegInput(), startSpawnStage, "")+";\n";
	    			 logic += myLanguage.CreateAssign(myLanguage.CreateNodeName(BNode.RdInstr.NodeNegInput(), startSpawnStage, ""), myLanguage.CreateRegNodeName(BNode.RdInstr.NodeNegInput(), startSpawnStage, ""));
	    		     if(maxStageInstr<startSpawnStage) { // RdInstr Reg not pressent . !!!! TODO not recursive till stage where available. It helps for picorv32 where rdinstr is in 0 and spawn in 1 but  would not work if rdinstr is in 0 and spawn in 2
	    		    	 int stage = startSpawnStage;
	    		    	 String signalName = this.myLanguage.CreateRegNodeName(BNode.RdInstr, stage, "");
				    	 String signalAssign = this.myLanguage.CreateRegNodeName(BNode.RdInstr, stage-1, "");
				    	 declarations += this.myLanguage.CreateDeclReg(BNode.RdInstr,stage,"");
				    	 if(!addRdNodeReg.containsKey(BNode.RdInstr) || !addRdNodeReg.get(BNode.RdInstr).contains(stage-1))
				    		 signalAssign = this.myLanguage.CreateNodeName(BNode.RdInstr.NodeNegInput(), stage-1, "");
				    	 logic += this.myLanguage.CreateTextRegReset(signalName, "("+this.myLanguage.CreateNodeName(BNode.RdFlush.NodeNegInput(), stage-1, "")+") ? 0 : "+signalAssign, this.myLanguage.CreateNodeName(BNode.RdStall.NodeNegInput(), stage-1, ""));
				     
	    		     }
	    		 }
	    		 
	    		 
	    	     // Prepare signals
	    		 int dataW ;
	    		 if(BNode.GetAdjSCAIEVNode(node, SCAIEVNode.GetAddr())!= null) 
		    		 dataW = BNode.GetAdjSCAIEVNode(node, AdjacentNode.addr).size; // TODO this line versus next one, make it uniform
	    		 else 
	    			 dataW = 1;
	    		 SCAIEVNode validReqNode = BNode.GetAdjSCAIEVNode(node, SCAIEVNode.GetValidRequest());
	    		 SCAIEVNode addrNode = BNode.GetAdjSCAIEVNode(node, SCAIEVNode.GetAddr());

		         // FIFO Module: Required to store addr in a FIFO? 
	    		 // Yes if: 1) wrrd and SETTINGWithAddr set = node with Addr sig to core but not to ISAX and SCAL uses FIFO addr 
	    		 //         2) mem without optional addr sig, and SETTINGWithAddr set = no cutsom addr from user, addr comes from core and FIFO addr enabled
	    		 boolean FIFOAddrRequired = false; 
	    		 if(this.ISAXes.get(ISAX).GetRunsAsDecoupled() &&  addrNode!= null) {
	    			 // Case 1: 
	    			 if(addrNode.noInterfToISAX && !this.ISAXes.get(ISAX).GetRunsAsDynamicDecoupled() && SETTINGWithAddr)
	    				 FIFOAddrRequired = true; 
	    			 if(node.name.contains("Mem"))
	    			 if(!ISAXes.get(ISAX).GetSchedWith(node, _snode -> _snode.GetStartCycle() >= node.commitStage).HasAdjSig(AdjacentNode.addr))
	    				 if(SETTINGWithAddr)
	    					 FIFOAddrRequired = true; 
	    				 else 
	    					 System.out.println("CRITICAL WARNING. SCAL. Spawn Detected, FIFO Addr not enabled within SCAL, and user does not provide custom addr. Functionality issue");
	    			 
	    		 } 
	    		 if(FIFOAddrRequired) { // make addr fifo available also without scoreboard, but NOT for stall mechanism, for which addr is in pipeline instr reg already
	    			 String addrReadSig = myLanguage.CreateNodeName(BNode.RdInstr.NodeNegInput(), startSpawnStage, "")+"[11:7],\n"; // WrRD address
	    			 if(BNode.IsUserBNode(node) && addrNode!=null)
	    				 addrReadSig =  myLanguage.CreateNodeName(addrNode, startSpawnStage, "")+",\n";
	    			 else if(!addrNode.noInterfToISAX)  // if this node has in theory interf to ISAX, we are in FIFOAddrRequired bc user does not provide addr; we need addr from core (as for Mem)
	    				 addrReadSig =  myLanguage.CreateNodeName( BNode.GetAdjSCAIEVNode(node,AdjacentNode.rdAddr).NodeNegInput(), spawnStage, "")+",\n";
	    			
	    			 
	    			 logic += "\n"+this.FIFOmoduleName + " #( "+(ISAXes.get(ISAX).GetFirstNode(node).GetStartCycle()-startSpawnStage)+", "+dataW +" ) "+this.FIFOmoduleName+"_ADDR_"+node+"_"+ISAX+"_"+spawnStage+"_inst (\n"
	    		 		+ myLanguage.tab+myLanguage.clk+",\n"
	    		 		+ myLanguage.tab+myLanguage.reset+",\n"
	    		 		+ myLanguage.tab+myLanguage.CreateLocalNodeName(BNode.RdIValid, startSpawnStage, ISAX)
	    		 		  +" && ! "+myLanguage.CreateNodeName(BNode.RdStall.NodeNegInput(), this.core.GetStartSpawnStage(), "")+",\n // no need for wrStall, it.s included in RdStall\n" // write fifo
	    		 		+ myLanguage.tab+myLanguage.CreateNodeName(validReqNode,  spawnStage, ISAX)+ShiftmoduleSuffix+",\n"            // read fifo
	    		 		+ myLanguage.tab+addrReadSig // write data
	    		 		+ "dummy"+ISAX+","
	    		 		+ myLanguage.tab+myLanguage.CreateFamNodeName(addrNode,  spawnStage, ISAX,false)+"\n" // read data . No family node name (for Mem we need full name as on interf to ISAX)         
	    		 		+ ");\n";
	    			 declarations += "wire ["+addrNode.size+"-1:0] "+ myLanguage.CreateFamNodeName(addrNode,  spawnStage, ISAX,false)+";\n";
	    			 declarations += "wire dummy"+ISAX+";\n";
	    		 }
	    		 
	    	//	 Scheduled oldSched = new Scheduled();
				//	oldSched = GetCheckUniqueSchedWith(parentNode, snode -> snode.GetStartCycle()>=spawnStage);
					
	    		 
	    		 // Shift Module: Required to store valid bit (trigger of valid response)?
	    		 if(this.ISAXes.get(ISAX).GetRunsAsDecoupled() || this.ISAXes.get(ISAX).GetRunsAsDynamicDecoupled()) { // For STALL mechanism this is done based on counter, NOT on shift reg valid bit
		    		 dataW = BNode.GetAdjSCAIEVNode(node, AdjacentNode.validReq).size;
		    		 declarations +=  "wire "+myLanguage.CreateNodeName(validReqNode,  spawnStage, ISAX)+this.ShiftmoduleSuffix+";\n";
		    		 if(SETTINGWithValid && !this.ISAXes.get(ISAX).GetRunsAsDynamicDecoupled()){ // Make shift reg available also without scoreboard; for dynamic decoupled, latency unknown, so valid comes from user	    				
		    			 logic +=  this.ShiftmoduleName + " #( "+(ISAXes.get(ISAX).GetFirstNode(node).GetStartCycle()-startSpawnStage)+", "+dataW +" ) "+this.ShiftmoduleName+"_"+node+"_"+ISAX+"_"+spawnStage+"_inst (\n"
			   			 		+ myLanguage.tab+myLanguage.clk+",\n"
			   			 		+ myLanguage.tab+myLanguage.reset+",\n"
			   			 		+ myLanguage.tab+myLanguage.CreateNodeName(BNode.RdInstr.NodeNegInput(), startSpawnStage, "")+",\n" 
			   			 		+ myLanguage.tab+myLanguage.CreateLocalNodeName(BNode.RdIValid, startSpawnStage, ISAX)+",\n" // insert data into shift reg
			   			 		+ myLanguage.tab+flush+",\n"
			   			 		+ myLanguage.tab+stallShiftReg+",\n"
			   			 		+ myLanguage.tab+myLanguage.CreateNodeName(validReqNode,  spawnStage, ISAX)+ShiftmoduleSuffix+"\n" // read data          
			   			 		+ ");\n";
		    		 } else if(!this.ISAXes.get(ISAX).GetRunsAsDynamicDecoupled())
		    			 logic += "assign "+myLanguage.CreateNodeName(validReqNode,  spawnStage, ISAX)+ShiftmoduleSuffix+" = "+myLanguage.CreateNodeName(validReqNode,  spawnStage, ISAX)+";\n"; 		
		    		 else // this.ISAXes.get(ISAX).GetRunsAsDynamicDecoupled() 
		    			 logic += "assign "+myLanguage.CreateNodeName(validReqNode,  spawnStage, ISAX)+ShiftmoduleSuffix+" = |"+myLanguage.CreateNodeName(addrNode,  spawnStage, ISAX)+";\n";
	    		 }
	    		 
	    		 // Add Stall mechanism if no DH desired
	    		 if(!this.ISAXes.get(ISAX).GetRunsAsDecoupled()) {
	    			 SCAIEVNode nonSpawnNode = BNode.GetSCAIEVNode(node.nameParentNode);
	    			 declarations += "wire "+myLanguage.CreateNodeName(validReqNode,  spawnStage, ISAX)+"_count_s;\n";
	    			 logic +=  this.CountermoduleName+"  #(\n"
					+ ( spawn_instr_stage.get(node).get(ISAX) - this.core.GetStartSpawnStage()) + "\n"
					+ ") counter_"+ISAX+"_inst (\n"
					+ myLanguage.tab+myLanguage.clk+", \n"
					+ myLanguage.tab+myLanguage.reset+", \n"
					+ myLanguage.tab+myLanguage.CreateNodeName(BNode.RdStall.NodeNegInput(),startSpawnStage,"")+",\n"
					+ myLanguage.tab+myLanguage.CreateLocalNodeName(BNode.RdIValid, startSpawnStage, ISAX)+", \n"
					+ myLanguage.tab+myLanguage.CreateNodeName(validReqNode,  spawnStage, ISAX)+"_count_s\n);\n";
	    			
	    			// Stall stages to commit result of decoupled node
	    			for(int stage=0; stage <= (startSpawnStage+1);stage++) {
	    	    		if(!stallStages[stage].isEmpty())
	    	 	    		stallStages[stage] += " || ";
	    	 	    	stallStages[stage] +=  "to_CORE_stall_WB_"+node+"_"+ISAX+"_o_s";
	    			}
	    		    declarations += "reg to_CORE_stall_WB_"+node+"_"+ISAX+"_o_s;\n";
	    			logic += "always @(*)  \n"
	    			 		+ myLanguage.tab + "to_CORE_stall_WB_"+node+"_"+ISAX+"_o_s = "+myLanguage.CreateLocalNodeName(BNode.RdIValid, startSpawnStage+1, ISAX)+" &&  !"+myLanguage.CreateNodeName(validReqNode,  spawnStage, ISAX)+"_count_s;\n";
	    			
	    			// Send Result
	    			declarations += myLanguage.CreateDeclSig(nonSpawnNode, (startSpawnStage+1), ISAX, false, myLanguage.CreateNodeName(nonSpawnNode, (startSpawnStage+1), ISAX));
	    			logic += myLanguage.CreateAssign(myLanguage.CreateNodeName(nonSpawnNode, (startSpawnStage+1), ISAX), myLanguage.CreateNodeName(node, this.core.GetSpawnStage(), ISAX));
	    			if(this.ISAXes.get(ISAX).GetNodes(nonSpawnNode).get(0).HasAdjSig(AdjacentNode.validReq)) {
	    				SCAIEVNode validNonSpawnReq =BNode.GetAdjSCAIEVNode(nonSpawnNode, AdjacentNode.validReq);
	    				declarations += myLanguage.CreateDeclSig(validNonSpawnReq, (startSpawnStage+1), ISAX, false, myLanguage.CreateNodeName(validNonSpawnReq, (startSpawnStage+1), ISAX));
	    				logic += myLanguage.CreateAssign(myLanguage.CreateNodeName(validNonSpawnReq, (startSpawnStage+1), ISAX), myLanguage.CreateNodeName(BNode.GetAdjSCAIEVNode(node, AdjacentNode.validReq), this.core.GetSpawnStage(), ISAX));
	    			}
	    		 }

	    	 }
    		
	    	 
	    	 /// ADD DH Mechanism 
	    	 if(this.ContainsOpInStage(node, spawnStage) && SETTINGWithScoreboard && node.DH) { // Add it only if spawn node in op_stage_instr (means there are instr runnning decoupled) AND if scoreboard within SCAL desired by user
	    		 // Differentiate between Wr Regfile and user nodes or address signals 
    			 String instrSig = myLanguage.CreateNodeName(BNode.RdInstr.NodeNegInput(), startSpawnStage, "");
    			 String destSig = instrSig+"[11:7]"; 
    			 SCAIEVNode parentWrNode = this.BNode.GetSCAIEVNode(node.nameParentNode);
    			 SCAIEVNode parentRdNode = this.BNode.GetSCAIEVNode( this.BNode.GetNameRdNode(parentWrNode));
    			 if(BNode.IsUserBNode(node))
    				 if(node.elements>1)
    					 destSig = myLanguage.CreateNodeName(BNode.GetAdjSCAIEVNode(parentWrNode, AdjacentNode.addr).NodeNegInput(),  this.core.GetStartSpawnStage(), "");
    				 else 
    					 destSig = "5'd0";
    			 String RdRS1Sig = instrSig+"[19:15]"; 
    			 if(BNode.IsUserBNode(node))
    				 if(node.elements>1)
    					 RdRS1Sig = myLanguage.CreateNodeName(BNode.GetAdjSCAIEVNode(parentRdNode, AdjacentNode.addr).NodeNegInput(),  this.core.GetStartSpawnStage(), "");
    				 else 
    					 RdRS1Sig = "5'd0";
    			 // Compute cancel signal in case of user validReq = 0 
    			 logic += CreateUserCancelSpawn(node, spawnStage);
    			 // Instantiate module
    			 logic +=  "spawndatah_"+node+" spawndatah_"+node+"_inst(        \n"
    			 + "	.clk_i("+myLanguage.clk+"),                           \n"
    			 + "	.rst_i("+myLanguage.reset+"),             \n"
    			 + "	.flush_i({"+flush+","+ myLanguage.CreateNodeName(BNode.RdFlush.NodeNegInput(), this.core.GetStartSpawnStage(), "")+"}),	 \n"
    			 + "	.RdIValid_ISAX0_2_i("+allIValid+"), \n"
    			 + "	.RdInstr_RDRS_i({"+instrSig+"[31:20], "+RdRS1Sig+","+instrSig+"[14:12],"+destSig+" ,"+instrSig+"[6:0] }),     \n"
    			 + "	.WrRD_spawn_valid_i("+myLanguage.CreateNodeName(BNode.commited_rd_spawn_valid+"_"+node, spawnStage, "",false)+"),       		    \n"
    			 + "	.WrRD_spawn_addr_i("+myLanguage.CreateNodeName(BNode.commited_rd_spawn+"_"+node, spawnStage, "",false)+"),  \n"
    			 + "    .cancel_from_user_valid_i("+myLanguage.CreateNodeName(BNode.cancel_from_user_valid+"_"+node, spawnStage, "",false)+"),\n"
    			 + "    .cancel_from_user_addr_i("+myLanguage.CreateNodeName(BNode.cancel_from_user+"_"+node, spawnStage, "",false)+"),\n"
    			 + "	.stall_RDRS_o(to_CORE_stall_RS_"+node+"_o_s),  \n"
    			 + "	.stall_RDRS_i("+stallFrCore+")  \n"
    			 + "    );\n";
    			 
    			 // Stall stages because of DH 
    	    	 for(int stage=0; stage <= this.core.GetStartSpawnStage();stage++) {
    	 	    	if(!stallStages[stage].isEmpty())
    	 	    		stallStages[stage] += " || ";
    	 	    	stallStages[stage] +=  "to_CORE_stall_RS_"+node+"_o_s";
    	 	     }
    	    	 
    		     otherModules += DHModule(node);
    		 }
    	 }
    	}
    	if((SETTINGwithInputFIFO || SETTINGWithAddr) && !spawn_instr_stage.isEmpty()) { // Address storage needs fifo, OR input need FIFO if mul inputs possible till resolved 
    	 otherModules += FIFOModule();
    	}
    	if(DHReq) { // simply add all modules. If they are not required, they won't be instantiated.
	     otherModules += ShiftModule();
	     otherModules += Counter();
    	}
	    
    	
	    
    	//////////////////////////  WrStall, RdStall //////////////////////////
    	// Stall of internal regs 
    	if(privateregs.hasPrivateRegs &&  privateregs.GetInstantiation().containsKey(BNode.RdStall)) // only if it has stall sig. For RdSpawn ONLY, not required
	    	for( int stage : privateregs.GetInstantiation().get(BNode.RdStall) ) {
		    	if(!stallStages[stage].isEmpty())
		    		stallStages[stage] += " || ";
			    stallStages[stage] += privateregs.GetNodeInstName(BNode.RdStall, stage);
			    declarations += "wire "+ privateregs.GetNodeInstName(BNode.RdStall, stage)+";\n";
	    	}
    	
	    // Finish array for WrStall, RdStall signals 
	    for(int stage=0; stage <= this.core.maxStage;stage++) {
	    	if (!stallStages[stage].isEmpty() || this.ContainsOpInStage(BNode.WrStall,  stage) || (addToCoreInterf.containsKey(BNode.WrStall) && addToCoreInterf.get(BNode.WrStall).contains(stage))) {
	    	 	String wrstallVal = stallStagesPrefix[stage];
	    	 	if(!stallStages[stage].isEmpty())
	    	 		wrstallVal += (wrstallVal.isEmpty() ? "" : " || ") + stallStages[stage];
				if(wrstallVal.isEmpty())
					wrstallVal = "0";
				logic += "assign "+myLanguage.CreateNodeName(BNode.WrStall.NodeNegInput(), stage, "")+" = "+wrstallVal+";\n";

	    	 }
	    }
	    
	    // Create RdStall Logic 
	    // New Stall concept:  RdStall set by core BUT for stall decoupled we need this 
	    for(int stage=0; stage <= this.core.maxStage;stage++) 
	    	if (this.ContainsOpInStage(BNode.RdStall,  stage)) {
	    	 	String rdstall_val = myLanguage.CreateNodeName(BNode.RdStall.NodeNegInput(), stage, "");
	    	 	String IStall = "";
	    	 	if(stallStages[stage].contains("to_CORE_stall_WB_")) {
	    	 		for (SCAIEVNode node : this.spawn_instr_stage.keySet())
	    	 			for(String instr : this.spawn_instr_stage.get(node).keySet())
							if(!this.ISAXes.get(instr).GetRunsAsDecoupled())
								IStall += " && !"+"to_CORE_stall_WB_"+node+"_"+instr+"_o_s " ;
	    	 		rdstall_val += IStall; // TODO. RdStall comes from core only!!
	    	 	}
				logic += "assign "+myLanguage.CreateNodeName(BNode.RdStall, stage, "")+" = "+rdstall_val+";\n";
	    		
	    	}
	    
	    
		////////////////////// ADDITIONAL INTERF TO CORE //////////////////////
    	/// Generate any additional interf to core which was required by other nodes /////////
    	// Generate interf between this module and core 
	    for(SCAIEVNode operation : this.addToCoreInterf.keySet()) {
	    	if(!operation.isAdj()) { // Add main nodes (not adjacent)
	    		HashSet<String> overrideIsaxes = nodePerISAXOverride.getOrDefault(operation, new HashSet<>());
				for(int stage :  this.addToCoreInterf.get(operation)) {
					if(!this.removeFrCoreInterf.contains(new NodeStagePair(operation, stage))) {
						boolean existingInstancesArePerIsax = this.ContainsOpInStage(operation, stage) && overrideIsaxes != null
								&& this.op_stage_instr.get(operation).get(stage).stream().allMatch((String instr)->overrideIsaxes.contains(instr));
						boolean doAddInterf = !this.ContainsOpInStage(operation, stage) || existingInstancesArePerIsax;
						if (doAddInterf) {
							AddIn_op_stage_instr(operation,stage, SCAIEVInstr.noEncodingInstr+operation+stage); // Update the op_stage_instrhashmap
							newInterfaceToCore.add(this.CreateAndRegisterTextInterfaceForCore(operation.NodeNegInput(), stage, "", ""));
						}
						SCAIEVInstr dummy = new SCAIEVInstr(SCAIEVInstr.noEncodingInstr+operation+stage);
						dummy.PutSchedNode(operation, stage);
						ISAXes.put(SCAIEVInstr.noEncodingInstr+operation+stage, dummy);
					}
				}
			} 
	    }
		     
		////////////////////// REMOVE interf to core, which is handled in SCAL ////////	
	    for(NodeStagePair op_stage : this.removeFrCoreInterf) {
	    	SCAIEVNode operation = op_stage.node;
			int stage = op_stage.stage;
			if(this.ContainsOpInStage(operation, stage)) { 
				System.out.println("INFO. Removing operation: "+operation+" fr stage: "+stage+" because it was handled in SCAL");
				this.op_stage_instr.get(operation).remove(stage);
				if(this.op_stage_instr.get(operation).isEmpty())
					this.op_stage_instr.remove(operation);
				for(String ISAX : ISAXes.keySet()) {
					this.ISAXes.get(ISAX).RemoveNodeStage(operation, stage);
				}
			}
	    }
	    HashSet<SCAIEVNode> removeUserNode = new HashSet<SCAIEVNode>();
	    for( SCAIEVNode node : op_stage_instr.keySet()) {
	    	if(this.BNode.IsUserBNode(node))
	    		removeUserNode.add(node);
	    }
	    for(SCAIEVNode node : removeUserNode ) {
	    	System.out.println("INFO. Removing User operation: "+node+" because it was handled in SCAL");
	    	op_stage_instr.remove(node);
	    }
	    
		for(String addInterf : newInterfaceToISAX)
			interfToISAX += addInterf;
		newInterfaceToISAX = null;
		for(String addInterf : newInterfaceToCore)
			interfToCore += addInterf;
		newInterfaceToCore = null;
	     
		////////////////////// Write all this logic to file //////////////////////
		String clkrst = "\ninput "+myLanguage.clk+",\ninput "+myLanguage.reset+"\n";
	    WriteFileUsingTemplate(interfToISAX, interfToCore+clkrst, declarations, logic,otherModules, outPath);
	     
	}
	
	
	//////////////////////////////////////////////FUNCTIONS: FOR MAIN ADAPTER LOGIC ////////////////////////
	


	/** 
	 * Function to create a virtual core. Used by myLanguage functions
	 */
	private void PopulateVirtualCore() {
		if(!op_stage_instr.isEmpty()) {
			for(SCAIEVNode operation : this.op_stage_instr.keySet()) {
				for(int stage : this.op_stage_instr.get(operation).keySet()) {
					if(operation.isInput && operation!=FNode.WrFlush)
						this.virtualBackend.PutNode("reg", "", "", operation, stage);
					else 
						this.virtualBackend.PutNode("", "", "", operation, stage);
					for(SCAIEVNode adjNode : BNode.GetAdjSCAIEVNodes(operation))  {
						if(adjNode.isInput)
							this.virtualBackend.PutNode("reg", "", "", adjNode, stage);
						else 
							this.virtualBackend.PutNode("", "", "", adjNode, stage);
					}
				}
			}
		}
	}
	
	
	/**
	 * Function to generate text for interface to Core.Assumes that operation is requested in stage. Returns assigns for corresponding interfaces. If node is User Node, it does not generate interface but it declares the signal as wire/reg
	 * 
	 */
	private String GenerateAllInterfToCore( SCAIEVNode operation,int stage,HashSet<String> interfaceText) {
		if(operation.equals(FNode.RdIValid)) // SCAL handles RdIValid and ISAX Internal state
			return "";

		HashSet<String> declares = new HashSet<String> () ;
		String newinterf = "";
		String dataT = "";
		HashSet<String> emptyHashSet = new HashSet<>();
		HashSet<String> instructions = op_stage_instr.get(operation).getOrDefault(stage, emptyHashSet);
		for(String instruction : instructions) {
			// Generate main FNode interface 
			//String instrName = "";
			dataT = this.virtualBackend.NodeDataT(operation, stage);
			//if( !operation.isInput && operation.oneInterfToISAX)
			//	instrName = instruction;
			if( !removeFrCoreInterf.contains(new NodeStagePair(operation, stage))) {
				String interfaceInstr = "";
				if (nodePerISAXOverride.getOrDefault(operation, emptyHashSet).contains(instruction))
					interfaceInstr = instruction;
				
				//The familyName will only be applied to operations with isAdj (-> GenerateText.CreateBasicNodeName) .
				if(!BNode.IsUserBNode(operation)) {
					newinterf = this.CreateAndRegisterTextInterfaceForCore(operation.NodeNegInput(), stage, interfaceInstr, dataT);
					interfaceText.add(newinterf);
				} else 
					declares.add(myLanguage.CreateDeclSig(operation.NodeNegInput(), stage,"", operation.isInput, myLanguage.CreateNodeName(operation.NodeNegInput(), stage, "")));	
						
			}
			// Generate adjacent signals on the interface
			Scheduled snode = ISAXes.get(instruction).GetSchedWith(operation, _snode -> _snode.GetStartCycle() == stage);
			for(AdjacentNode adjacent : BNode.GetAdj(operation)) {
				SCAIEVNode adjOperation = BNode.GetAdjSCAIEVNode(operation,adjacent);
				dataT = this.virtualBackend.NodeDataT(adjOperation, stage);
				// TODO revert
			//	if(snode.HasAdjSig(adjacent) || (adjOperation.MandatoryAdjSig())) {
				if(ISAXes.get(instruction).GetFirstNode(operation).HasAdjSig(adjacent) || adjOperation.DefaultMandatoryAdjSig() || adjOperation.mandatory ) {
					if(!operation.nameQousinNode.isEmpty()) 
						adjOperation = new SCAIEVNode(adjOperation.replaceRadixNameWith(operation.familyName),adjOperation.size,adjOperation.isInput);
					if (adjacent == AdjacentNode.spawnAllowed && !adjSpawnAllowedNodes.contains(adjOperation) && !BNode.IsUserBNode(adjOperation))
						continue; //Core may not provide specific spawnAllowed node.
					String interfaceInstr = "";
					if(!removeFrCoreInterf.contains(new NodeStagePair(adjOperation, stage)))   {
						if(!BNode.IsUserBNode(adjOperation)) {
							newinterf = this.CreateAndRegisterTextInterfaceForCore(adjOperation.NodeNegInput(), stage, interfaceInstr, dataT);					
							interfaceText.add(newinterf);	
						} else 
							declares.add(myLanguage.CreateDeclSig(adjOperation.NodeNegInput(), stage,"", adjOperation.isInput, myLanguage.CreateNodeName(adjOperation.NodeNegInput(), stage, "")));
					}
				}	    			 
			}
		}
		
		// Generate valid interfaces requires by some nodes in earlier stages (usually Memory nodes )
		if(instructions.isEmpty() && stage >= this.node_earliestStageValid.getOrDefault(operation, Integer.MAX_VALUE)) {
			SCAIEVNode adjOperation = BNode.GetAdjSCAIEVNode(operation,AdjacentNode.validReq);
			if (!BNode.IsUserBNode(operation)) {
				dataT = "reg";//this.virtualBackend.NodeDataT(adjOperation, stage);
				newinterf = this.CreateAndRegisterTextInterfaceForCore(adjOperation.NodeNegInput(), stage, "", dataT);			
				interfaceText.add(newinterf);
			} else 
				declares.add(myLanguage.CreateDeclSig(adjOperation.NodeNegInput(), stage,"", adjOperation.isInput, myLanguage.CreateNodeName(adjOperation.NodeNegInput(), stage, "")));
		}
		

		String returnText = ""; 
		for(String text : declares)
			returnText += text;
		return returnText;
	}
	
	private String GenerateAllInterfToISAX( SCAIEVNode operation,int stage,HashSet<String> interfaceText) {
		boolean signalIsInput = operation.isInput;
		String assigns = "";
		String newinterf = "";
		String dataT = "";
		HashSet<String> emptyHashSet = new HashSet<>();
		for(String instruction : op_stage_instr.get(operation).getOrDefault(stage, emptyHashSet)) {
			// Generate main FNode interface 
			String instrName = "";
			if(!operation.oneInterfToISAX || nodePerISAXOverride.getOrDefault(operation, emptyHashSet).contains(instruction))
				instrName = instruction;
			if (!operation.noInterfToISAX) {
				// Set interface to spawn for multicycle instr running with stall mechanism 
				int addStage = stage;
				SCAIEVNode addOperation = operation; 
				SCAIEVNode spawnOperation = this.BNode.GetMySpawnNode(operation);
				if(this.spawn_instr_stage.containsKey(spawnOperation) &&  this.spawn_instr_stage.get(spawnOperation).containsKey(instruction) && !this.ISAXes.get(instruction).GetRunsAsDecoupled() && operation.isInput) { // only spawn for write nodes
					addOperation = spawnOperation;
					addStage = this.core.GetSpawnStage();
				}
							
				newinterf = CreateAndRegisterTextInterfaceForISAX(addOperation, addStage, instruction, !instrName.isEmpty(), dataT);
				String scalPinName = this.myLanguage.CreateFamNodeName(addOperation, addStage, instrName, false);
				if( !interfaceText.contains(newinterf) ) {
					interfaceText.add(newinterf);
					// Assigns for read nodes
					if(!operation.isInput &&  operation.oneInterfToISAX && !operation.isSpawn() && !operation.equals(BNode.RdStall) ) {// TODO split wrstall and rdstall in core and put it in SCAL
						if(this.addRdNodeReg.containsKey(operation) && addRdNodeReg.get(operation).contains(stage))
							assigns += this.myLanguage.CreateAssign(scalPinName,this.myLanguage.CreateRegNodeName(operation, stage, instrName));
						else
							assigns += this.myLanguage.CreateAssign(scalPinName,this.myLanguage.CreateNodeName(operation.NodeNegInput(), stage, instrName));
					}
				}
			}
					
			
			// Generate adjacent signals on the interface
			// TODO Null pointer exception. Reverted it to be able to further develop other cores and avoid exception. Must be reverted after we fix the err
			// Scheduled snode = ISAXes.get(instruction).GetSchedWith(operation, _snode -> _snode.GetStartCycle() == stage);
			
			for(AdjacentNode adjacent : BNode.GetAdj(operation)) {
				SCAIEVNode adjOperation = BNode.GetAdjSCAIEVNode(operation,adjacent);
				instrName = "";
				if(!adjOperation.oneInterfToISAX)
					instrName = instruction;
				if(adjOperation.noInterfToISAX && !(adjOperation.mandatory && ISAXes.get(instruction).GetRunsAsDynamicDecoupled())) 			
					continue;

		    // TODO revert this after fixing null pointer exception
			//	if(snode.HasAdjSig(adjacent)) {
				if(ISAXes.get(instruction).GetFirstNode(operation).HasAdjSig(adjacent)) {
					boolean oneInterf = adjOperation.oneInterfToISAX;
					//boolean notReqSpawnSig = ((SETTINGdecWithValid && adjOperation.DHSpawnModule && adjOperation.isSpawn() && adjOperation.getAdj()==AdjacentNode.validReq) || (SETTINGdecWithAddr && adjOperation.DHSpawnModule && adjOperation.isSpawn() && adjOperation.getAdj()==AdjacentNode.addr));
					if(!removeFrCoreInterf.contains(new NodeStagePair(adjOperation, stage)))   {
						String scalPinName = this.myLanguage.CreateFamNodeName(adjOperation, stage, instrName, false);
						String isaxPinName = this.myLanguage.CreateFamNodeName(adjOperation.NodeNegInput(), stage, instrName, false);

						newinterf = CreateAndRegisterTextInterfaceForISAX(adjOperation, stage, instruction, !instrName.isEmpty(), dataT);
						SCALPinNet net = null;
						if( !(interfaceText.contains(newinterf))) {
							interfaceText.add(newinterf);
							
							if(!adjOperation.isInput && oneInterf && !operation.isSpawn() && !this.addRdNodeReg.containsKey(operation)) // TODO if signal is pipelined in SCAL? Does such a case exist for adjacent node?
								assigns += this.myLanguage.CreateAssign(scalPinName, this.myLanguage.CreateNodeName(adjOperation.NodeNegInput(), stage, ""));		   					
						}
					}
				}	    			 
			}
		}
		return assigns;
	}
	/** 
	 * This function should add nodes that are required to generate logic. So, for example, in order to know whether we need to write WrRD from ISAX, we need to know if currently we have an ISAX in WB Stage ==> we need instruction fields 
	 * @return 
	 */
	private int AddRequiredOperations(SCALState privateregs) {
		// piepelined RdNodes 
		// We need to know which read nodes are required in stage X, where core does not provide this information. Later, we need to pipeline this data
		// maxStageRdInstr used for RdInstr, which is requried for RdIValid
		int maxStageRdInstr = -1;
		for(SCAIEVNode rdNode :  this.op_stage_instr.keySet()) {
			if(!rdNode.isInput) { // input to ISAX = output for core
				for(int stage : this.op_stage_instr.get(rdNode).keySet()) {
					if(!rdNode.isSpawn() && stage>=this.core.GetNodes().get(rdNode).GetExpensive() && (rdNode != FNode.RdIValid)) { // RdIValid handled bellow (requires also info about instructions & wrNodes require implicit rdIvalid). Rd_Spawn should not generate registers here
						removeFrCoreInterf.add(new NodeStagePair(rdNode, stage)); // core should not generate an interface for this, because info not in pipeline anymore
						AddToCoreInterfHashMap(rdNode, this.core.GetNodes().get(rdNode).GetExpensive()-1); // create interface for the latest stage where data available
						for(int prev_stage = this.core.GetNodes().get(rdNode).GetExpensive(); prev_stage<=stage;prev_stage++ ) {
							if(addRdNodeReg.containsKey(rdNode))
								addRdNodeReg.get(rdNode).add(prev_stage); 
							else {
								HashSet<Integer> stages = new HashSet<Integer>(); 
								stages.add(prev_stage);
								addRdNodeReg.put(rdNode, stages);
							}
							if((prev_stage-1)>=0) { // bullet proof, avoid errors
								AddToCoreInterfHashMap(FNode.RdStall,prev_stage-1);
								AddToCoreInterfHashMap(FNode.RdFlush,prev_stage-1);
							}
						}
					}
					// For RdInstr we also need to store the information about latest stage in which it was available. Required for decoding for rdIValid
					if(stage>maxStageRdInstr  && (rdNode == FNode.RdInstr))
						maxStageRdInstr = stage;
				}			
			}
		}
			
		
		// Nodes required by RdIValid
		for(SCAIEVNode node : this.op_stage_instr.keySet()) {
			for(int stage : this.op_stage_instr.get(node).keySet()) {
				if(NodeReqValid(node) || node.equals(FNode.RdIValid)) {
					AddRdIValid(this.op_stage_instr.get(node).get(stage), node,stage,maxStageRdInstr); // this adds all RdFlush, RdStall and RdInstr for generating RdIValid
				}
			}										
		}
		
		// Add RdIValid also for spawn nodes that don't run decoupled, but wih stall mechanism
		for(SCAIEVNode node : this.spawn_instr_stage.keySet()) {
			HashSet<String> lookAtIsax =  new HashSet<>();
			for(String instr : this.spawn_instr_stage.get(node).keySet()) {
				if(!this.ISAXes.get(instr).GetRunsAsDecoupled())
					lookAtIsax.add(instr);
			}
			if(!lookAtIsax.isEmpty())
				AddRdIValid(lookAtIsax, node,this.core.GetStartSpawnStage(),maxStageRdInstr);
		}
		
		// Add RdIvalid for nodes which require a WrNode_ValidReq signal also in earlier stages, not only where the user needs (due to core's uA)	
		if(!this.node_earliestStageValid.isEmpty()) {                  // if core has any nodes which requrie valid signals in earlier stages
	    	 for(SCAIEVNode node : node_earliestStageValid.keySet()) {  // go through these nodes
	    		 if(this.op_stage_instr.containsKey(node))      {        // if the user actually wants this node (otherwise no logic is added)
	    			 HashSet<String> lookAtISAX = new HashSet<String>();
	    			 for (int stage: this.op_stage_instr.get(node).keySet())
	    				 lookAtISAX.addAll(this.op_stage_instr.get(node).get(stage));
	    			 for(int stage = node_earliestStageValid.get(node); stage < this.core.GetNodes().get(node).GetEarliest(); stage++) {
	    				 AddRdIValid(lookAtISAX,node,stage,maxStageRdInstr); // this adds all RdFlush, RdStall and RdInstr for generating RdIValid
	    			 }
	    		 }
	    	 }
	     }
		
		
		// Add rdivalid for wrrd nodes with data hazards & stall
		 HashMap<SCAIEVNode, Integer> node_LatestStageValid = new HashMap<SCAIEVNode, Integer>();
	     if(!this.node_earliestStageValid.isEmpty()) {                  // if core has any nodes which requrie valid signals in earlier stages
	    	 for(SCAIEVNode node : node_earliestStageValid.keySet()) {  // go through these nodes
	    		 if(this.op_stage_instr.containsKey(node))      {        // if the user actually wants this node (otherwise no logic is added)
	    			 HashSet<String> lookAtISAX = new  HashSet<String>();
	    			 int latestStage = 0; 	    			 
	    			 for (int stage: this.op_stage_instr.get(node).keySet()) {
	    				 if(latestStage<stage)
	    					 latestStage = stage;
	    				 lookAtISAX.addAll(  this.op_stage_instr.get(node).get(stage));
	    			 }
	    			 node_LatestStageValid.put(node, latestStage);
	    			 for(int stage = node_earliestStageValid.get(node); stage <= latestStage; stage++) {
	    				 AddRdIValid(lookAtISAX,node,stage,maxStageRdInstr);
	    				 AddToCoreInterfHashMap(FNode.RdStall,stage); // if latest equals to earliest, not really needed & will be optimized
	    			 }
	    		 }
	    	 }
	     }
	     
	     // Add RdIvalid for user RdCustomRegisters 
	     for (SCAIEVNode node : this.op_stage_instr.keySet())
    		 for(AdjacentNode adjacent : BNode.GetAdj(node)) {
    			 SCAIEVNode adjNode = BNode.GetAdjSCAIEVNode(node, adjacent);
    			 if( !node.isInput && this.BNode.IsUserBNode(node) &&  !adjNode.isSpawn() &&  adjacent == AdjacentNode.validReq) { // if it's a user state node and it's output (RdCustomReg)
    				 // if it is a RdCustom node, we need to consider in the read regsfile stage all reads, also from later stages 
    				 // Code bellow not optimal approach. It's a fast solution which ensures no bugs are addded
					 HashSet<String> allReads = new  HashSet<String> (); 
					 for(int stage_2 : this.op_stage_instr.get(node).keySet())
						 allReads.addAll(this.op_stage_instr.get(node).get(stage_2));
					 AddRdIValid(allReads,node, node.commitStage,maxStageRdInstr);		// afterwards,RdCustom_valid_req handled in SCAL In the part where we generate all validreq for writes		 
    			 }
	    	 }

		
		// Add WrFlush for WrPC 
		if(this.op_stage_instr.containsKey(FNode.WrPC)) {
			for(int stage : this.op_stage_instr.get(FNode.WrPC).keySet())
				for(int i=0;i<stage;i++)
					AddToCoreInterfHashMap(FNode.WrFlush,i);
		}
		// Add WrFlush to prior stages for WrFlush
		if(this.op_stage_instr.containsKey(FNode.WrFlush)) {
			for(int stage : this.op_stage_instr.get(FNode.WrFlush).keySet())
				for(int i=0;i<stage;i++)
					AddToCoreInterfHashMap(FNode.WrFlush,i);
		}
		
		// Required by SPAWN
		// Add stall for wrrd_spawn. Used by DH. Add ISAX_spawnAllowed, used by firing logic. Add addr signal   
		HashSet<SCAIEVNode> spawnNode = new HashSet<SCAIEVNode> ();
		for(SCAIEVNode operation: op_stage_instr.keySet()) {
			if(operation.isSpawn())
				spawnNode.add(operation);
		}
		if(spawn_instr_stage.containsKey(BNode.WrRD_spawn) && !ContainsOpInStage(BNode.RdStall,this.core.GetStartSpawnStage()) )
			AddToCoreInterfHashMap (BNode.RdStall,this.core.GetStartSpawnStage());	
		for(SCAIEVNode operation: this.spawn_instr_stage.keySet()) {
				// Usually spawn simply stalls entire core. For mem till mem start stage, for wrrd entire core. 
				int maxStall = core.maxStage;
				if(operation.equals(BNode.WrMem_spawn) || operation.equals(BNode.RdMem_spawn) )
					maxStall = core.GetNodes().get(BNode.WrMem).GetEarliest();
				for(int stage = this.core.GetNodes().get(BNode.WrStall).GetEarliest(); stage<=maxStall; stage++) // TODO workaround
						AddToCoreInterfHashMap (BNode.WrStall,stage);
				
				if(operation.DH) {
					AddToCoreInterfHashMap (BNode.RdInstr,this.core.GetStartSpawnStage());
					for(int stage = this.core.GetStartSpawnStage()+1; stage <= this.core.maxStage; stage ++ ) {
						AddToCoreInterfHashMap (BNode.RdStall,stage);
						AddToCoreInterfHashMap (BNode.RdFlush,stage);
					}
				}
				// For adding addr to spawn, commented out because done through mandatory param of SCAIEVNode
				// if(operation.elements>1)
				//	AddToCoreInterfHashMap (BNode.GetAdjSCAIEVNode(operation, AdjacentNode.addr),core.maxStage+1); // Addr for spawn is mandatory
				
				if(operation.allowMultipleSpawn)
					AddToCoreInterfHashMap(BNode.ISAX_spawnAllowed,this.core.GetStartSpawnStage());
		}
		
		// Add sigs required by user internal state 
		// Interface only to the module with internal register (so quasi to core, but won.t be connected to core)
		for(SCAIEVNode node : privateregs.GetInstantiation().keySet()) { 
			if(node.equals(BNode.WrStall) | node.equals(BNode.RdInstr))
				for(int stage : privateregs.GetInstantiation().get(node)) {
					AddToCoreInterfHashMap(privateregs.GetNodeInst(node),stage);
				}
			if(node.equals(BNode.RdStall)) // TODO not optimal, but I'm tired
				for(int stage : privateregs.GetInstantiation().get(node)) {
					AddToCoreInterfHashMap(BNode.WrStall,stage);
			}
		}

		
		
		return maxStageRdInstr;// info required in caller function
		
	}
	
	/** 
	 * Is an IValid bit required by this node (decoding)?
	 * @param operation
	 * @return
	 */
	private boolean NodeReqValid(SCAIEVNode operation) {
		if(operation.isSpawn()) // All decoupled operations need it for valid bit. PC spawn does not exist anymore
			return true;
		if(operation.isInput && !operation.oneInterfToISAX)
			return true; 
		for(SCAIEVNode adj : BNode.GetAdjSCAIEVNodes(operation))
			if(adj.isInput && !adj.oneInterfToISAX)
				return true; 
		return false;
	}
	
	private void AddRdIValid (HashSet<String> lookAtISAX, SCAIEVNode node, int stage, int maxStageRdInstr) {
		System.out.println("INFO. SCAL: RdIvalid within SCAL requested for node: "+ node);
		
		if(node.equals(FNode.RdIValid)) 
			removeFrCoreInterf.add(new NodeStagePair(FNode.RdIValid, stage));
		int toStage = stage;
		if(node.isSpawn())
			toStage = this.core.GetStartSpawnStage();
		
		// Store that we need RdIValid
		RdIValidStageDesc ISAXSetWithIValid;
		if(stage_containsRdIValid.containsKey(toStage))
			ISAXSetWithIValid = stage_containsRdIValid.get(toStage);
		else	
			ISAXSetWithIValid = new RdIValidStageDesc();
		// Store only instr with opcode 
		HashSet<String> addISAX = new HashSet<String>();
		for(String instr : lookAtISAX) {
			if(!ISAXes.get(instr).HasNoOp())
				addISAX.add(instr);
		}
		ISAXSetWithIValid.instructions.addAll(addISAX);
		
		// Add all logic : flush, instr bits decoding etc. only if there are any instr with opcode (= different than don.t care)
		if(!addISAX.isEmpty()) {
			if(this.core.GetNodes().get(FNode.RdInstr).GetExpensive()<=toStage && this.core.GetNodes().get(FNode.RdInstr).GetExpensive()>(maxStageRdInstr-1)) {// if this is getting expensive, don't instantiate instr reg, rather use rdIValid bits							
				AddToCoreInterfHashMap(FNode.RdInstr, this.core.GetNodes().get(FNode.RdInstr).GetExpensive()-1);
				AddToCoreInterfHashMap(BNode.RdStall,toStage-1);
			}						
			if(this.core.GetNodes().get(FNode.RdInstr).GetExpensive()>toStage && (!this.op_stage_instr.containsKey(FNode.RdInstr)  || !this.op_stage_instr.get(FNode.RdInstr).containsKey(toStage)))
				AddToCoreInterfHashMap(FNode.RdInstr,toStage);
		
			// Flush required for computing RdIValid
			if(!ContainsOpInStage(FNode.RdFlush,toStage)) 
				AddToCoreInterfHashMap(FNode.RdFlush,toStage);
			
			stage_containsRdIValid.put(toStage, ISAXSetWithIValid);
			AddToCoreInterfHashMap(BNode.RdFlush,toStage);
			if(this.core.GetNodes().get(FNode.RdInstr).GetExpensive()<=toStage) // TODO paranthesis or {
			    AddToCoreInterfHashMap(BNode.RdStall,toStage-1);
			for(int i = this.core.GetNodes().get(FNode.RdInstr).GetExpensive()-1;i<toStage;i++) {
				// If there are other instr already in stage i, let's get them and not overwrite them. ISAXSetWithIValid was created for stage "stage"
				RdIValidStageDesc istageISAXSetWithIValid;
				if(stage_containsRdIValid.containsKey(i))
					istageISAXSetWithIValid = stage_containsRdIValid.get(i);
				else	
					istageISAXSetWithIValid = new RdIValidStageDesc();
				istageISAXSetWithIValid.instructions.addAll(ISAXSetWithIValid.instructions);
				
				stage_containsRdIValid.put(i, istageISAXSetWithIValid);
				AddToCoreInterfHashMap(FNode.RdFlush,i);
				AddToCoreInterfHashMap(FNode.RdStall,i);
			}
		}
		
	}
	
	private void AddToCoreInterfHashMap (SCAIEVNode node, int stage) {
		if(addToCoreInterf.containsKey(node))
			addToCoreInterf.get(node).add(stage); 
		else {
			HashSet<Integer> stages = new HashSet<Integer>(); 
			stages.add(stage);
			addToCoreInterf.put(node, stages);
		}
		if(this.core.GetNodes().containsKey(node) && this.core.GetNodes().get(node).GetExpensive()<=stage && !node.isInput) {
			this.removeFrCoreInterf.add(new NodeStagePair(node,stage));
		}
		
	}

	
	private boolean AddIn_op_stage_instr(SCAIEVNode operation,int stage, String instruction) {
		if(!op_stage_instr.containsKey(operation)) 
			op_stage_instr.put(operation, new HashMap<Integer,HashSet<String>>()); 
		else if(op_stage_instr.get(operation).containsKey(stage) && op_stage_instr.get(operation).get(stage).contains(instruction))
			return false;
		if(!op_stage_instr.get(operation).containsKey(stage))
			op_stage_instr.get(operation).put(stage, new HashSet<String>()); 
		op_stage_instr.get(operation).get(stage).add(instruction);
		return true;
	}
	
	/**
	 * Check if op_stage_instr has operation in stage
	 * @param operation
	 * @param stage
	 * @return
	 */
	private boolean ContainsOpInStage(SCAIEVNode operation, int stage ) {
		return op_stage_instr.containsKey(operation) && op_stage_instr.get(operation).containsKey(stage);
	}
	
	
	//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	//////////////////////////////////////// FUNCTIONS: FOR SPAWN LOGIC //////////////////////////////////////////////
//////////////////////////////////////////////////////////////////////////////////////////////////////////////////
	
	private String ConditionSpawnAllowed (SCAIEVNode node) {
		 String spawn_allowed_cond = myLanguage.CreateNodeName(BNode.ISAX_spawnAllowed.NodeNegInput(), this.core.GetStartSpawnStage(), "");
		 SCAIEVNode specific_spawn_allowed = BNode.GetAdjSCAIEVNode(node, AdjacentNode.spawnAllowed);
		 if(specific_spawn_allowed != null && !node.nameQousinNode.isEmpty()) {
			 specific_spawn_allowed = new SCAIEVNode(
					 specific_spawn_allowed.replaceRadixNameWith(node.familyName),
					 specific_spawn_allowed.size,
					 specific_spawn_allowed.isInput);
		 }
		 if (specific_spawn_allowed != null && adjSpawnAllowedNodes.contains(specific_spawn_allowed)) {
			 spawn_allowed_cond += " && " + myLanguage.CreateNodeName(specific_spawn_allowed.NodeNegInput(), this.core.GetSpawnStage(), "");
		 }
		 
		 // If Scoreboard present here, we have to consider its stall to avoid deadlock:  exp, in execute there is a memory instr with DH 
		 if (spawn_allowed_cond != null && this.SETTINGWithScoreboard) 
			 spawn_allowed_cond += " || to_CORE_stall_RS_"+node+"_o_s";
		 return spawn_allowed_cond;
	}
	
	/** 
	 * Create logic to store in regs input values , which will be later committed through the decoupled mechanism
	 * @param node
	 * @param validNode
	 * @param ISAX
	 * @param spawnStage
	 * @param priority
	 * @param fireNodeSuffix
	 * @return
	 */
	private String LogicRegsSpawn (SCAIEVNode node, SCAIEVNode validNode, String ISAX, int spawnStage, String priority, String fireNodeSuffix ) {
		String regsSpawn = "";
		if(node.isSpawn() && node.allowMultipleSpawn && node.isInput) {
			String mainSig = this.myLanguage.CreateLocalNodeName(node, spawnStage, ISAX);
			String mainSigReg = this.myLanguage.CreateRegNodeName(node, spawnStage, ISAX);
			String validSig =  this.myLanguage.CreateLocalNodeName(validNode, spawnStage, ISAX);
			String validResponse = "";
			SCAIEVNode validRespNode = BNode.GetAdjSCAIEVNode(node, SCAIEVNode.GetValidResponse());
			SCAIEVNode parentNode = node;
			if(node.isAdj()) {
				parentNode = BNode.GetSCAIEVNode(node.nameParentNode);
				validRespNode = BNode.GetAdjSCAIEVNode(parentNode, SCAIEVNode.GetValidResponse());
			}
			if(!(validRespNode == null)) {
				validResponse = " && "+myLanguage.CreateNodeName(validRespNode.NodeNegInput() , spawnStage, "");
				//if(!validRespNode.oneInterfToISAX)
				//	validResponse = " && "+ myLanguage.CreateNodeName(new SCAIEVNode(validRespNodeName,validRespNode.size,!validRespNode.isInput) ,spawnStage,ISAX); // !validRespNode.isInput because it comes from core
			}
			String assignValue = mainSig;
			String elseLogic = "";
			if(!priority.isEmpty())
				priority = " "+myLanguage.GetDict(DictWords.logical_and)+" !("+priority+") "; 
			if(node.DefaultMandatoryAdjSig()) {
				assignValue = "1";
				elseLogic = "else if(("+myLanguage.CreateRegNodeName(ISAX_fire2_r, spawnStage, fireNodeSuffix)+" ) "+priority+validResponse+" )  \n"  // TODO node.name.split("_")[0] not a general solution but good enough for now
			 		+ myLanguage.tab.repeat(2)+mainSigReg+" <= 0;  \n"
			 		+ myLanguage.tab.repeat(1)+"if ("+myLanguage.reset+" ) \n"
			 		+ myLanguage.tab.repeat(2)+mainSigReg+" <= 0;  \n";
			}		 
			
			regsSpawn += 	"always@(posedge "+myLanguage.clk+") begin // ISAX Spawn Regs for node "+node+" \n"
		 		+ myLanguage.tab.repeat(1)+"if("+validSig+" ) begin  \n"
		 		+ myLanguage.tab.repeat(2)+mainSigReg+" <= "+assignValue+";  \n"
		 		+ myLanguage.tab.repeat(1)+"end "+elseLogic+"\n"
		 		+ myLanguage.tab.repeat(0)+"end	\n";
			}
		 return regsSpawn;
		
	}
	
	private String LogicToCoreSpawn(String instr, int stage, SCAIEVNode node, SCAIEVNode mainNode, String priority ) {
		String body = "";
		if(node.isInput) {
			if(!priority.isEmpty()) {
				body += "if (!("+priority+"))\n"; 
			}
			String nodeName = node.name;
			if(!node.nameQousinNode.isEmpty() && node.isAdj())
				nodeName = node.replaceRadixNameWith(node.familyName);
			SCAIEVNode nodeToCore = new SCAIEVNode(nodeName, node.size, !node.isInput);
			if(node.getAdj().equals(AdjacentNode.isWrite)) { // TODO not a general solution. Fast one for now
				if(BNode.GetSCAIEVNode(node.nameParentNode).isInput)
					body += myLanguage.CreateNodeName(nodeToCore, stage, "") +" = 1;\n";
				else
					body += myLanguage.CreateNodeName(nodeToCore, stage, "") +" = 0;\n";
			} else if(!node.getAdj().equals(SCAIEVNode.GetValidRequest())) 
				body += myLanguage.CreateNodeName(nodeToCore, stage, "") +" = "+myLanguage.CreateRegNodeName(node, stage, instr)+";\n";
			else 
				return "";
		}
		return body;
	}
	
	public String CommitSpawnFire (SCAIEVNode node, String stallStage, int spawnStage, String fireNodeSuffix) {
		String ISAX_fire_r = myLanguage.CreateRegNodeName(this.ISAX_fire_r, spawnStage, fireNodeSuffix);     
		String ISAX_fire_s =  myLanguage.CreateLocalNodeName(this.ISAX_fire_s, spawnStage, fireNodeSuffix); 
		String ISAX_fire2_r =  myLanguage.CreateRegNodeName(this.ISAX_fire2_r, spawnStage,fireNodeSuffix);
		String ISAX_sum_spawn_s = myLanguage.CreateLocalNodeName(this.ISAX_spawn_sum, spawnStage, fireNodeSuffix);
		String stageReady = ConditionSpawnAllowed(node);
		SCAIEVNode validRespNode = BNode.GetAdjSCAIEVNode(node, SCAIEVNode.GetValidResponse());
		SCAIEVNode validReqToCore = BNode.GetAdjSCAIEVNode(node, SCAIEVNode.GetValidRequest()).NodeNegInput();
		String validReqToCoreName = myLanguage.CreateNodeName(validReqToCore,spawnStage,"");
		if(!node.nameQousinNode.isEmpty())
			validReqToCoreName = myLanguage.CreateNodeName(new SCAIEVNode(validReqToCore.replaceRadixNameWith(validReqToCore.familyName), validReqToCore.size,validReqToCore.isInput), spawnStage, "");
		String validResp = "";
		if(!(validRespNode == null)) {
			validRespNode = validRespNode.NodeNegInput();
			validResp =  " && "+myLanguage.CreateNodeName(validRespNode, spawnStage, "");
		}
		// Create stall logic 
		String stall3Text = "";
		String stallFullLogic = "";
		String stageReadyText = "";
		if (!stallStage.isEmpty() && !stageReady.isEmpty())
			stall3Text += " || ";
		if (!stallStage.isEmpty())
			stall3Text += stallStage;
		if (!stageReady.isEmpty())
			stageReadyText = "(" + stageReady + ")";
		if(!stallStage.isEmpty() || !stageReady.isEmpty())
			stallFullLogic = "&& ("+stageReadyText+stall3Text+")";
		
		String default_logic = " // ISAX : Spawn fire logic\n"
				+ "always @ (posedge "+myLanguage.clk+")  begin\n"
				+ "     if("+ISAX_fire_s+" && !"+stageReadyText+" )  \n"
				+ "         "+ISAX_fire_r+" <=  1'b1; \n" 
				+ "     else if(("+ISAX_fire_r+" ) "+stallFullLogic+")   \n"
				+ "         "+ISAX_fire_r+" <=  1'b0; \n"
				+ "     if ("+myLanguage.reset+") \n"
				+ "          "+ISAX_fire_r+" <= 1'b0; \n"
				+ "end \n"
				+ "   \n"
				+ "always @ (posedge "+myLanguage.clk+")  begin\n"
				+ "     if(("+ISAX_fire_r+" || "+ISAX_fire_s+") "+stallFullLogic+")    \n"
				+ "          "+ISAX_fire2_r+" <=  1'b1; \n"
				+ "     else if("+ISAX_fire2_r+" && ("+ISAX_sum_spawn_s+" == 1) "+validResp+")    \n"
				+ "          "+ISAX_fire2_r+" <= 1'b0; \n"
				+ "     if ("+myLanguage.reset+") \n"
				+ "          "+ISAX_fire2_r+" <= 1'b0; \n"
				+ "  end \n"
				+ "\n "
				+ "assign "+  validReqToCoreName + " = " + ISAX_fire2_r + " ;\n" ;
		
				
		return default_logic;
	}
	
	private String CreateUserCancelSpawn(SCAIEVNode node , int spawnStage ) {
		SCAIEVNode nodeValid = BNode.GetAdjSCAIEVNode(node,AdjacentNode.validReq);
		SCAIEVNode nodeAddr = BNode.GetAdjSCAIEVNode(node,AdjacentNode.addr);
		
		String cancelValid = myLanguage.CreateNodeName(BNode.cancel_from_user_valid+"_"+node, spawnStage, "",false);
		String cancelAddr = myLanguage.CreateNodeName(BNode.cancel_from_user+"_"+node, spawnStage, "",false);
		String  declareLogicValid = "wire "+cancelValid+";\n"
				                 + "assign "+cancelValid+" = ";
		String declareLogicAddr = "wire [4:0]"+cancelAddr+";\n";
		String cancelLogicValid = "0"; //default
		String  cancelLogicAddr = cancelAddr+" = 0;\n"; 
		for(String ISAX : this.op_stage_instr.get(node).get(spawnStage)) {
			if(ISAXes.get(ISAX).GetFirstNode(node).HasAdjSig(AdjacentNode.validReq) ) {
				cancelLogicValid += " || "+ myLanguage.CreateNodeName(nodeValid, spawnStage, ISAX)+ShiftmoduleSuffix+" && ~"+ myLanguage.CreateNodeName(nodeValid, spawnStage,ISAX);
				if(node.elements>1)
					cancelLogicAddr += "if("+ myLanguage.CreateNodeName(nodeValid, spawnStage, ISAX)+ShiftmoduleSuffix+") "+cancelAddr+" = "+myLanguage.CreateNodeName(nodeAddr, spawnStage, ISAX)+";\n";
			}
		}
		if(node.elements>1 && cancelLogicValid.length()>15) {
			declareLogicAddr = "reg [4:0]"+cancelAddr+";\n";
			cancelLogicAddr = this.myLanguage.CreateInAlways(false, cancelLogicAddr);
		} else 
			cancelLogicAddr = "assign "+ cancelAddr+" = 0;\n"; 
		return declareLogicValid+cancelLogicValid+";\n"+declareLogicAddr+cancelLogicAddr;

	}
	// OPTIONAL INPUT FIFO? 
/** new implementation with one fifo / instruction. If not desired, assigns user input without FIFO
 * 
 * @param node
 * @param fire2_reg
 * @return
 */
private String AddOptionalInputFIFO(SCAIEVNode node, String fire2_reg) {
	System.out.println("INFO. SCAL. Adding input FIFO for spawn node: "+node);
	String logic ="";
	int spawnStage = this.core.maxStage+1;
	for (String ISAX : this.op_stage_instr.get(node).get(spawnStage)) {
		String wire = "wire "; 
		if(this.SETTINGwithInputFIFO)
			wire = "reg "; 
		logic += wire  +"["+node.size+"-1:0]"+  myLanguage.CreateLocalNodeName(node, spawnStage, ISAX)+";\n";
		for(AdjacentNode adjacent : BNode.GetAdj(node)) {
   			 SCAIEVNode adjOperation = BNode.GetAdjSCAIEVNode(node,adjacent);
   			 if(adjOperation.isInput)
   				logic += wire +"["+adjOperation.size+"-1:0]"+ myLanguage.CreateLocalNodeName(adjOperation, spawnStage, ISAX)+";\n";
		}
	
		if(this.SETTINGwithInputFIFO) {
			SCAIEVNode validReq = BNode.GetAdjSCAIEVNode(node, SCAIEVNode.GetValidRequest());
			SCAIEVNode addr = BNode.GetAdjSCAIEVNode(node, SCAIEVNode.GetAddr());
			// Check out if addr needed
			boolean hasAddr = false;
			if(addr !=null) {
				hasAddr = true;
			}
			// Find out nr of bits for FIFO 
			int totalBitsNr =  node.size;
			if(hasAddr)
			  totalBitsNr += addr.size;
			// Declare Signals and Instantiate Module
			logic += "reg ["+totalBitsNr+"-1:0]  "+node+"_"+ISAX+"_FIFO_in_s;  \n"
					+ "reg ["+totalBitsNr+"-1:0]  "+node+"_"+ISAX+"_FIFO_out_s;  \n"
					+ "wire      "+node+"_"+ISAX+"_FIFO_write_s; \n"
					+ "reg      "+node+"_"+ISAX+"_FIFO_read_s;\n"
					+ "wire      "+node+"_"+ISAX+"_FIFO_notempty_s; \n"
					+ this.FIFOmoduleName+" #( 4,"+totalBitsNr+" ) SimpleFIFO_"+node+"_"+ISAX+"_valid_INPUTs_inst ( \n"
					+ "    clk_i, \n"
					+ "    rst_i, \n"
					+ "    "+node+"_"+ISAX+"_FIFO_write_s, \n"
					+ "    "+node+"_"+ISAX+"_FIFO_read_s, \n"
					+ "    "+node+"_"+ISAX+"_FIFO_in_s, \n"
					+ "    "+node+"_"+ISAX+"_FIFO_notempty_s, \n"
					+ "    "+node+"_"+ISAX+"_FIFO_out_s \n"
					+ ");\n";
			
			// Compute inputs to FIFO 
			// Write in FIFO?
			String FIFO_write = "assign "+node+"_"+ISAX+"_FIFO_write_s = ("+myLanguage.CreateRegNodeName(validReq, spawnStage, ISAX) + " && "+myLanguage.CreateLocalNodeName(validReq, spawnStage, ISAX)+");\n";
			logic += FIFO_write;
			// What data to write in FIFO?
			String FIFO_in = node+"_"+ISAX+"_FIFO_in_s = "; 
			String addrSig = "";
			if(hasAddr)
				addrSig = myLanguage.CreateFamNodeName(addr, spawnStage, ISAX, false) + ",";
			if(node.isInput)
				FIFO_in += "{"+addrSig+myLanguage.CreateFamNodeName(node, spawnStage, ISAX, false)+"};\n";
			else 
				FIFO_in += "{"+addrSig+node.size+"'d0};\n";
			
			logic += myLanguage.CreateInAlways(false, FIFO_in);
			
			// Compute outputs from FIFO
			String userOptValid = "";
			if(ISAXes.get(ISAX).GetFirstNode(node).HasAdjSig(AdjacentNode.validReq))
				userOptValid = myLanguage.CreateFamNodeName(validReq, spawnStage, ISAX, false)+" && ";
			String FIFO_out = myLanguage.CreateLocalNodeName(validReq, spawnStage, ISAX) +" = "+userOptValid +myLanguage.CreateFamNodeName(validReq, spawnStage, ISAX, true)+ShiftmoduleSuffix+"; // Signals rest of logic valid spawn sig\n";
			if(hasAddr)
				FIFO_out +=  myLanguage.CreateLocalNodeName(addr, spawnStage, ISAX) +" = "+myLanguage.CreateFamNodeName(addr, spawnStage, ISAX,false)+";\n"; 
			if(node.isInput)
				FIFO_out +=  myLanguage.CreateLocalNodeName(node, spawnStage, ISAX) +" = "+myLanguage.CreateFamNodeName(node, spawnStage, ISAX,false)+";\n";
			FIFO_out +=  node+"_"+ISAX+"_FIFO_read_s = 0;\n"
					 +  "if("+node+"_"+ISAX+"_FIFO_notempty_s && !"+myLanguage.CreateRegNodeName(validReq, spawnStage, ISAX)+") begin \n"	
					 + myLanguage.tab+" "+ myLanguage.CreateLocalNodeName(validReq, spawnStage, ISAX)+" = 1;\n "
					 + node+"_"+ISAX+"_FIFO_read_s"+" = 1;\n ";
			if(node.isInput)
				FIFO_out += myLanguage.CreateLocalNodeName(node, spawnStage, ISAX)+" = "+node+"_"+ISAX+"_FIFO_out_s["+node.size+"-1:0];\n ";
			if(hasAddr)
				FIFO_out += myLanguage.CreateLocalNodeName(addr, spawnStage, ISAX)+" = "+node+"_"+ISAX+"_FIFO_out_s["+addr.size+"+32-1 : 32]; \n";
			FIFO_out +=  "end\n";
			logic += myLanguage.CreateInAlways(false, FIFO_out);
			
		} else {
			logic += "assign "+ myLanguage.CreateLocalNodeName(node, spawnStage, ISAX)+" = "+ myLanguage.CreateFamNodeName(node, spawnStage, ISAX,false)+";\n";
			for(AdjacentNode adjacent : BNode.GetAdj(node)) {
	   			 SCAIEVNode adjOperation = BNode.GetAdjSCAIEVNode(node,adjacent);
	   			 if(adjOperation.isInput) {
	   				if(adjacent == AdjacentNode.validReq ) { // Valid bit ALWAYS generated within SCAL for spawn. Unless shiftReg not desired
		   				String userOptValid = "";
		   				if(ISAXes.get(ISAX).GetFirstNode(node).HasAdjSig(AdjacentNode.validReq))
		   					userOptValid = myLanguage.CreateFamNodeName(adjOperation, spawnStage, ISAX, false)+" && ";
		   				logic += "assign "+ myLanguage.CreateLocalNodeName(adjOperation, spawnStage, ISAX)+" = "+userOptValid+ myLanguage.CreateFamNodeName(adjOperation, spawnStage, ISAX,false)+ShiftmoduleSuffix+";\n";	
		   			} else 
		   				logic += "assign "+ myLanguage.CreateLocalNodeName(adjOperation, spawnStage, ISAX)+" = "+ myLanguage.CreateFamNodeName(adjOperation, spawnStage, ISAX,false)+";\n";	
	   			 } 
			}
		}
	}
	return logic;
}

	//////////////////////////////////////////////  FUNCTIONS: LOGIC OF ADDITIONAL MODULES (FIFOs, shift regs...) ////////////////////////

	private String FIFOModule() {
		String FIFOModuleContent = "\n"
				+ "module "+FIFOmoduleName+" #(\n"
				+ "	parameter NR_ELEMENTS = 64,\n"
				+ "	parameter DATAW = 5\n"
				+ ")(\n"
				+ "		input 				clk_i,  \n"
				+ "	input 				rst_i,  \n"
				+ "	input 				write_valid_i,  \n"
				+ "	input 				read_valid_i,  \n"
				+ "	input  [DATAW-1:0] 	data_i, \n"
				+ "	output              not_empty,  \n"
				+ "	output [DATAW-1:0]	data_o \n"
				+ " \n"
				+ "); \n"
				+ " \n"
				+ "reg [DATAW-1:0] FIFOContent [NR_ELEMENTS-1:0]; \n"
				+ "reg [$clog2(NR_ELEMENTS)-1:0] write_pointer; \n"
				+ "reg [$clog2(NR_ELEMENTS)-1:0] read_pointer; \n"
				+ "assign not_empty = write_pointer != read_pointer; \n"
				+ "always @(posedge clk_i) begin  \n"
				+ "	if(write_valid_i)  \n"
				+ "		FIFOContent[write_pointer] <= data_i; \n"
				+ "end \n"
				+ "assign data_o = FIFOContent[read_pointer]; \n"
				+ " \n"
				+ "// FIFO ignores case when data is overwritten, as it shouldn't happen in our scenario \n"
				+ "always @(posedge clk_i) begin  \n"
				+ "	if(rst_i) \n"
				+ "		write_pointer <=0;  \n"
				+ "    else if(write_valid_i) begin  \n"
				+ "		if(NR_ELEMENTS'(write_pointer) == (NR_ELEMENTS-1)) // useful if NR_ELEMENTS not a power of 2 -1 \n"
				+ "			write_pointer <= 0; \n"
				+ "	    else  \n"
				+ "			write_pointer <= write_pointer+1; \n"
				+ "	end \n"
				+ "end  \n"
				+ " \n"
				+ "always @(posedge clk_i) begin  \n"
				+ "	if(rst_i) \n"
				+ "		read_pointer <=0;  \n"
				+ "    else if(read_valid_i) begin  \n"
				+ "		if(NR_ELEMENTS'(read_pointer) == (NR_ELEMENTS-1)) // useful if NR_ELEMENTS not a power of 2 -1 \n"
				+ "			read_pointer <= 0; \n"
				+ "	    else  \n"
				+ "			read_pointer <= read_pointer+1; \n"
				+ "	end \n"
				+ "end "
				+ "endmodule\n"
				+ "\n";
		return FIFOModuleContent;
	}
	
	private String Counter() {
		String CounterModuleContent = "\n"
				+ "module "+this.CountermoduleName+" #(\n"
				+ "	parameter NR_CYCLES = 64\n"
				+ ")(\n"
				+ "	input 				clk_i, \n"
				+ "	input 				rst_i, \n"
				+ " input               stall_i,\n"
				+ "	input 				write_valid_i, \n"
				+ "	output          	zero_o\n"
				+ "\n"
				+ ");\n"
				+ "\n"
				+ "reg [$clog2(NR_CYCLES)-1:0] counter;\n"
				+ " \n"
				+ "always @(posedge clk_i) begin  \n"
				+ "	if(rst_i) \n"
				+ "	 counter <= 0; \n"
				+ " else if(write_valid_i && !stall_i && counter==0)   \n"
				+ "	 counter <= NR_CYCLES-1; \n"
				+ " else if(counter>0)   \n"
				+ "	 counter <= counter-1; \n"
				+ "end \n"
				+ "assign zero_o = (counter == 0 );"
				+ "endmodule\n"
				+ "\n";
		return CounterModuleContent;
	}
	
	private String ShiftModule() {
		String returnStr = "" ; 
		if(this.core.GetStartSpawnStage()< this.core.maxStage)
			returnStr += "`define LATER_FLUSHES\n";
		returnStr += "module "+ShiftmoduleName+" #(\n"
		+ "	parameter NR_ELEMENTS = 64,\n"
		+ "	parameter DATAW = 5,\n"
		+ "	parameter START_STAGE = "+this.core.GetStartSpawnStage()+", \n"
		+ "	parameter WB_STAGE = "+this.core.maxStage+"\n"
		+ ")(\n"
		+ "	input 				clk_i,  \n"
		+ "	input 				rst_i,  \n"
		+ " input  [31:0]       instr_i,	input  [DATAW-1:0]  data_i,  \n"
		+ "	`ifdef LATER_FLUSHES \n"
		+ "		input  [WB_STAGE-START_STAGE:1] flush_i,  \n"
		+ "	`endif input [WB_STAGE-START_STAGE:0] stall_i, \n"
		+ "	output [DATAW-1:0]  data_o  \n"
		+ "	); \n"
		+ "	 \n"
		+ "reg [DATAW-1:0] shift_reg [NR_ELEMENTS:1] ;	 \n"
		+ "wire kill_spawn;  \n"
		+ "assign kill_spawn = (7'b0001011 == instr_i[6:0]) && (3'b110 ==  instr_i[14:12]);  \n"
		+ "always @(posedge clk_i) begin   \n"
		+ "   if(!stall_i[0])  shift_reg[1] <= data_i;  \n"
		+ "   if((flush_i[1] && stall_i[0]) || ( stall_i[0] && !stall_i[1])) //  (flush_i[0] && !stall_i[0]) not needed, data_i should be zero in case of flush for shift valid bits in case of flush  \n"
		+ "        shift_reg[1] <= 0 ;\n"
		+ "   for(int i=2;i<=NR_ELEMENTS;i = i+1) begin   \n"
		+ "	    if((i+START_STAGE)<=WB_STAGE) begin   \n"
		+ "		  if((flush_i[i] && stall_i[i-1]) || (flush_i[i-1] && !stall_i[i-1]) || ( stall_i[i-1] && !stall_i[i]))  \n"
		+ "		    shift_reg[i] <=0;  \n"
		+ "		  else if(!stall_i[i-1]) \n"
		+ "	        shift_reg[i] <= shift_reg[i-1];  \n"
		+ "     end else \n"
		+ "	      shift_reg[i] <= shift_reg[i-1];  \n"
		+ "   end \n"
		+ "   if(rst_i || kill_spawn) begin   \n"
		+ "     for(int i=1;i<=NR_ELEMENTS;i=i+1)  \n"
		+ "       shift_reg[i] = 0;  \n"
		+ "   end  \n"
		+ "end\n "
		+ "assign data_o = shift_reg[NR_ELEMENTS];\n"
		+ "endmodule\n";
		return returnStr;
	}

	private String DHModule(SCAIEVNode spawnNode) {
		// Compute DH decoding checks for detecting DHs 
		String DH_rs1 = "";
		String DH_rs2 = "";
		String DH_rd = "";
		SCAIEVNode node = BNode.GetSCAIEVNode(spawnNode.nameParentNode );
		if(node.equals(BNode.WrRD)) {
			DH_rs1 = "( ((RdInstr_RDRS_i[6:0] !==7'b0110111) && (RdInstr_RDRS_i[6:0] !==7'b0010111) && (RdInstr_RDRS_i[6:0] !==7'b1101111)) || ";
			DH_rs2 = "( ((RdInstr_RDRS_i[6:0] !==7'b0110111) && (RdInstr_RDRS_i[6:0] !==7'b0010011) && (RdInstr_RDRS_i[6:0] !==7'b0000011) && (RdInstr_RDRS_i[6:0] !==7'b0010111) &&  (RdInstr_RDRS_i[6:0] !==7'b1100111)  &&  (RdInstr_RDRS_i[6:0] !==7'b1101111)) ||";
			DH_rd  = "( ((RdInstr_RDRS_i[6:0] !==7'b1100011) && (RdInstr_RDRS_i[6:0] !==7'b0100011)) ||";
			HashSet<String> lookAtISAX = new HashSet<String> ();
			if(this.op_stage_instr.containsKey(BNode.RdRS1)) 
				for(int stage : this.op_stage_instr.get(BNode.RdRS1).keySet())
					lookAtISAX.addAll(this.op_stage_instr.get(BNode.RdRS1).get(stage));
			DH_rs1 += this.myLanguage.CreateAllEncoding(lookAtISAX, ISAXes, "RdInstr_RDRS_i") +")";
			 
			lookAtISAX = new HashSet<String> ();
			if(this.op_stage_instr.containsKey(BNode.RdRS2)) 
				for(int stage : this.op_stage_instr.get(BNode.RdRS2).keySet())
					lookAtISAX.addAll(this.op_stage_instr.get(BNode.RdRS2).get(stage));
			DH_rs2 += this.myLanguage.CreateAllEncoding(lookAtISAX, ISAXes, "RdInstr_RDRS_i") +")";
			
			lookAtISAX = new HashSet<String> ();
			if(this.op_stage_instr.containsKey(BNode.WrRD)) 
				for(int stage : this.op_stage_instr.get(BNode.WrRD).keySet())
					lookAtISAX.addAll(this.op_stage_instr.get(BNode.WrRD).get(stage));
			DH_rd += this.myLanguage.CreateAllEncoding(lookAtISAX, ISAXes, "RdInstr_RDRS_i") +" || ";
			
			lookAtISAX = new HashSet<String> ();
			if(this.op_stage_instr.containsKey(BNode.WrRD_spawn))  // should be, that's why we are here...
				for(int stage : this.op_stage_instr.get(BNode.WrRD_spawn).keySet())
					lookAtISAX.addAll(this.op_stage_instr.get(BNode.WrRD_spawn).get(stage)); // wrrd datahaz also for spawned instr (alternative would be to check if their latency is larger than started ones...but additional HW)
			DH_rd += this.myLanguage.CreateAllEncoding(lookAtISAX, ISAXes, "RdInstr_RDRS_i") +")";
			
		} else {
			SCAIEVNode regRdNode = BNode.GetSCAIEVNode(BNode.GetNameRdNode(node));
			HashSet<String> lookAtISAX = new HashSet<String> ();
			if(this.op_stage_instr.containsKey(regRdNode)) 
				for(int stage : this.op_stage_instr.get(regRdNode).keySet())
					lookAtISAX.addAll(this.op_stage_instr.get(regRdNode).get(stage));
			DH_rs1 += this.myLanguage.CreateAllEncoding(lookAtISAX, ISAXes, "RdInstr_RDRS_i");
			
			DH_rs2  = "1'b0"; 
			
			lookAtISAX = new HashSet<String> ();
			if(this.op_stage_instr.containsKey(node)) 
				for(int stage : this.op_stage_instr.get(node).keySet())
					lookAtISAX.addAll(this.op_stage_instr.get(node).get(stage));
			DH_rd += this.myLanguage.CreateAllEncoding(lookAtISAX, ISAXes, "RdInstr_RDRS_i") +" || ";
			
			lookAtISAX = new HashSet<String> ();
			if(this.op_stage_instr.containsKey(spawnNode))  // should be, that's why we are here...
				for(int stage : this.op_stage_instr.get(spawnNode).keySet())
					lookAtISAX.addAll(this.op_stage_instr.get(spawnNode).get(stage));
			DH_rd += this.myLanguage.CreateAllEncoding(lookAtISAX, ISAXes, "RdInstr_RDRS_i");
		}	
		
		String RdRS2DH =  "assign data_hazard_rs2 = 0;   \n"; 
		if(spawnNode.equals(this.BNode.WrRD_spawn))
			RdRS2DH =  "assign dirty_bit_rs2 = rd_table_mem[RdInstr_RDRS_i[24:20]];   \n"
					+ "assign data_hazard_rs2 =  !flush_i[0] && dirty_bit_rs2 && ("+DH_rs2+");  \n"; 
		int sizeAddr = 0; 
		String sizeZero = "";
		if(spawnNode.elements>1) {
			sizeAddr = ((int) Math.ceil((Math.log10(spawnNode.elements)/Math.log10(2))));
			sizeZero = "[RD_W_P-1:0]";
		}
		String returnStr = "" ; 
		if(this.core.GetStartSpawnStage() < this.core.maxStage)
			returnStr += "`define LATER_FLUSHES\n";
		if(this.core.GetStartSpawnStage()+1 < this.core.maxStage)
			returnStr += "`define LATER_FLUSHES_DH\n";
		returnStr +=  "module spawndatah_"+spawnNode+"#(                                              \n"
				+ " parameter RD_W_P = "+sizeAddr+",                                                \n"
				+ " parameter INSTR_W_P = 32,  \n"
				+ " parameter START_STAGE = "+this.core.GetStartSpawnStage()+",  \n"
				+ " parameter WB_STAGE = "+this.core.maxStage+"                                           \n"
				+ "                                                                      \n"
				+ ")(                                                                    \n"
				+ "   input clk_i,                                                           \n"
				+ "    input rst_i,   \n"
				+ " `ifdef LATER_FLUSHES   \n"
				+ "    input [WB_STAGE-START_STAGE:0] flush_i,  \n"
				+ "`endif                                                         \n"
				+ "    input RdIValid_ISAX0_2_i,  \n"
				+ "    input [INSTR_W_P-1:0] RdInstr_RDRS_i,  \n"
				+ "    input  WrRD_spawn_valid_i,                                   \n"
				+ "    input "+sizeZero+" WrRD_spawn_addr_i,                                    \n"
				+ "    input  cancel_from_user_valid_i,// user validReq bit was zero, but we need to clear its scoreboard dirty bit \n"
				+ "    input "+sizeZero+"cancel_from_user_addr_i,\n"
				+ "    output stall_RDRS_o, //  stall from ISAX,  barrier ISAX,  OR spawn DH   \n"
				+ "    input  [WB_STAGE-START_STAGE:0] stall_RDRS_i // input from core. core stalled. Includes user stall, as these sigs are combined within core  \n"
				+ ");                                                                     \n"
				+ "  \n"
				+ "                                                                      \n"
				+ "wire dirty_bit_rs1;                                                    \n"
				+ "wire dirty_bit_rs2;     \n"
				+ "wire dirty_bit_rd;  \n"
				+ "wire data_hazard_rs1;   \n"
				+ "wire data_hazard_rs2;   \n"
				+ "wire data_hazard_rd;   \n"
				+ "wire we_spawn_start;            \n"
				+ "reg [2**RD_W_P-1:0] mask_start;  \n"
				+ "reg [2**RD_W_P-1:0] mask_stop_or_flush;  \n"
				+ "wire [14:0] opcode_kill = 15'b110xxxxx0001011;  \n"
				+ "wire [14:0] opcode_barrier = 15'b111xxxxx0001011;  \n"
				+ "reg barrier_set;   \n"
				+ "									  \n"
				+ "reg  [2**RD_W_P-1:0] rd_table_mem ;      \n"
				+ "reg  [2**RD_W_P-1:0] rd_table_temp;  \n"
				+ "   \n"
				+ "    \n"
				+ "   \n"
				+ "`ifdef LATER_FLUSHES_DH                                                                       \n"
				+ "    reg  [RD_W_P-1:0] RdInstr_7_11_reg[WB_STAGE - START_STAGE-1:0];     \n"
				+ "    reg [WB_STAGE-START_STAGE:1] RdIValid_reg;   \n"
				+ "    always @(posedge clk_i ) begin   \n"
				+ "        if(!stall_RDRS_i[0])  \n"
				+ "            RdIValid_reg[1] <= RdIValid_ISAX0_2_i; // or of all decoupled spawn  \n"
				+ "        if((flush_i[1] && stall_RDRS_i[0]) || (flush_i[0] && !stall_RDRS_i[0]) || ( stall_RDRS_i[0] && !stall_RDRS_i[1]))  \n"
				+ "RdIValid_reg[1] <= 0 ;\n"
				+ "        for(int k=2;k<=(WB_STAGE - START_STAGE);k=k+1) begin   \n"
				+ "            if((flush_i[k] && stall_RDRS_i[k-1]) || (flush_i[k-1] && !stall_RDRS_i[k-1]) || ( stall_RDRS_i[k-1] && !stall_RDRS_i[k]))  \n"
				+ "		          RdIValid_reg[k] <=0;  \n"
				+ "		       else if(!stall_RDRS_i[k-1]) \n"
				+ "                RdIValid_reg[k] <= RdIValid_reg[k-1];  \n"
				+ "        end   \n"
				+ "        if(rst_i) begin  \n"
				+ "            for(int k=1;k<(WB_STAGE - START_STAGE);k=k+1) \n"
				+ "                RdIValid_reg[k] <= 0; \n"
				+ "        end      \n"
				+ "    end  \n"
				+ "    always @(posedge clk_i ) begin   \n"
				+ "        if(!stall_RDRS_i[0])  \n"
				+ "            RdInstr_7_11_reg[1] <= RdInstr_RDRS_i[11:7];  \n"
				+ "        for(int k=2;k<(WB_STAGE - START_STAGE);k=k+1) begin   \n"
				+ "        if(!stall_RDRS_i[k-1])  \n"
				+ "             RdInstr_7_11_reg[k] <= RdInstr_7_11_reg[k-1];  \n"
				+ "        end  \n"
				+ "        if(rst_i) begin  \n"
				+ "            for(int k=1;k<(WB_STAGE - START_STAGE);k=k+1) \n"
				+ "                RdInstr_7_11_reg[k] <= 0; \n"
				+ "        end  \n"
				+ "    end  \n"
				+ "   \n"
				+ "`endif  \n"
				+ "assign we_spawn_start   = (RdIValid_ISAX0_2_i && !stall_RDRS_i[0]);                                                                                                                          \n"
				+ "                                                         								  \n"
				+ "always @(posedge clk_i)                                                \n"
				+ "begin   \n"
				+ "    if(rst_i)   \n"
				+ "        rd_table_mem <= 0;  \n"
				+ "    else                                                                   \n"
				+ "        rd_table_mem <= rd_table_temp;          							   \n"
				+ "end                                                                    \n"
				+ "  \n"
				+ "always @(*) begin  \n"
				+ "    mask_start = {(2**RD_W_P){1'b0}};  \n"
				+ "    if(we_spawn_start)  \n"
				+ "        mask_start[RdInstr_RDRS_i[11:7]] = 1'b1;   \n"
				+ "end  \n"
				+ "  \n"
				+ "always @(*) begin  \n"
				+ "    mask_stop_or_flush = {(2**RD_W_P){1'b1}};  \n"
				+ "    if(WrRD_spawn_valid_i)  \n"
				+ "        mask_stop_or_flush[WrRD_spawn_addr_i] = 1'b0; \n  "
				+ "    if(cancel_from_user_valid_i)  \n"
				+ "        mask_stop_or_flush[cancel_from_user_addr_i] = 1'b0; \n"
				+ "    if ((opcode_kill[6:0] == RdInstr_RDRS_i[6:0]) && (opcode_kill[14:12] == RdInstr_RDRS_i[14:12])) begin  \n"
				+ "        mask_stop_or_flush = 0;  \n"
				+ "    end  \n"
				+ "`ifdef LATER_FLUSHES_DH   \n"
				+ "	for(int k=1;k<(WB_STAGE - START_STAGE);k=k+1) begin   \n"
				+ "		if(flush_i[k] && RdIValid_reg[k] )   \n"
				+ "			mask_stop_or_flush[RdInstr_7_11_reg[k]] = 1'b0;   \n"
				+ "	end  \n"
				+ "`endif  \n"
				+ "end  \n"
				+ "  \n"
				+ "always @(*) begin        \n"
				+ "  rd_table_temp = (rd_table_mem | mask_start) & mask_stop_or_flush;    \n"
				+ " end                                                                        \n"
				+ "  \n"
				+ "always @(posedge clk_i)                                                \n"
				+ "begin   \n"
				+ "    if((|rd_table_mem) == 0)      \n"
				+ "        barrier_set <= 0;   \n"
				+ "    else if((opcode_barrier[6:0] === RdInstr_RDRS_i[6:0]) && (opcode_barrier[14:12] === RdInstr_RDRS_i[14:12]) && !flush_i[0])                                                                  \n"
				+ "        barrier_set <= 1;   							   \n"
				+ "end    \n"
				+ "wire stall_fr_barrier = barrier_set;  \n"
				+ "                                                                       \n"
				+ "assign dirty_bit_rs1 = rd_table_mem[RdInstr_RDRS_i[19:15]];     \n"
				+ "assign dirty_bit_rd =  rd_table_mem[RdInstr_RDRS_i[11:7]];   \n"
				+ "assign data_hazard_rs1 =  !flush_i[0] &&  dirty_bit_rs1 && ("+DH_rs1+");  \n"
				+ "assign data_hazard_rd =  !flush_i[0] && dirty_bit_rd   && ("+DH_rd+");  \n"
				+ RdRS2DH
				+ "assign stall_RDRS_o = data_hazard_rs1 || data_hazard_rs2 || data_hazard_rd || stall_fr_barrier;           \n"
				+ "  \n"                                                            
				+ "endmodule        \n"
				+ ""; // TODO ISAX Encoding
		return returnStr;
	}

	///////////////////////////////////////// FUNCTIONS: CREATE Interface and scaiev_netlist ////////////////////
	/**
	 * Creates the interface text and adds it to the netlist for SCAL<->ISAX. TODO Hack for always (no op -> stage 0)
	 * @param operation operation, with 'isInput' from the view of SCAL
	 * @param stage stage
	 * @param instrName instruction name (must be given even if the signal name does not contain it)
	 * @param namePerISAX set if the signal name should contain the instruction name
	 * @param dataT data type to pass to CreateTextInterface
	 * @return
	 */
	private String CreateAndRegisterTextInterfaceForISAX(SCAIEVNode operation, int stage, String instrName, boolean namePerISAX, String dataT)
	{
		String topWireName = "isax_" + this.myLanguage.CreateBasicNodeName(operation, stage, namePerISAX ? instrName : "", false) + (operation.isInput ? "_to_scal" : "_from_scal");
		String scalPinName = this.myLanguage.CreateFamNodeName(operation, stage, namePerISAX ? instrName : "", false);
		int stageISAX = stage;
		// TODO Remove hack with clean implementation
		if(this.ISAXes.get(instrName).HasNoOp())
			stageISAX = 0;
		String isaxPinName = this.myLanguage.CreateFamNodeName(operation.NodeNegInput(), stageISAX, namePerISAX ? instrName : "", false);
		SCALPinNet net = null;
		if (!netlist.containsKey(topWireName))
		{
			net = new SCALPinNet(operation.size, scalPinName, "", isaxPinName);
			netlist.put(topWireName, net);
		}
		else 
			net = netlist.get(topWireName);
		assert(net.size == operation.size);
		assert(net.isax_module_pin.equals(isaxPinName));
		net.isaxes.add(instrName);
		
		return myLanguage.CreateTextInterface(operation.name, stage, namePerISAX ? instrName : "", operation.isInput, operation.size, dataT);
	}
	/**
	 * Creates the interface text and adds it to the netlist for SCAL<->Core.
	 * @param operation operation, with 'isInput' from the view of SCAL
	 * @param stage stage
	 * @param instrName instruction name, set to "" if not to be included in the operation name
	 * @param dataT data type to pass to CreateTextInterface
	 * @return
	 */
	private String CreateAndRegisterTextInterfaceForCore(SCAIEVNode operation, int stage, String instrName, String dataT)
	{
		String topWireName = "core_" + this.myLanguage.CreateBasicNodeName(operation, stage, instrName, false) + (operation.isInput ? "_to_scal" : "_from_scal");
		String scalPinName = this.myLanguage.CreateFamNodeName(operation, stage, instrName, false);
		String corePinName = this.myLanguage.CreateFamNodeName(operation.NodeNegInput(), stage, instrName, false);
		if (!netlist.containsKey(topWireName))
		{
			SCALPinNet net = new SCALPinNet(operation.size, scalPinName, corePinName, "");
			netlist.put(topWireName, net);
		}
		else
		{
			SCALPinNet net = netlist.get(topWireName);
			assert(net.size == operation.size);
			assert(net.core_module_pin.equals(corePinName));
		}
		return myLanguage.CreateTextInterface(operation.name, stage, instrName, operation.isInput, operation.size, dataT);
	}
	
	
	//////////////////////////////////////////////FUNCTIONS: WRITE ALL TEXT ////////////////////////
	/**
	 * Write text based on template
	 * 
	 */
	private void WriteFileUsingTemplate(String interfToISAX, String interfToCore, String declarations, String logic, String otherModules, String outPath) {
		String tab = myLanguage.tab; //  no need to use the same tab as in Core's module files. It is important just to have same tab across the same file
		String endl = "\n";
		String textToWrite = ""
				+ endl+tab.repeat(0)+"// SystemVerilog file \n "
				+ endl+tab.repeat(0)+"module SCAL ("
				+ endl+tab.repeat(1)+"// Interface to the ISAX Module"
				+ endl+tab.repeat(1)+"\n"+this.myLanguage.AllignText(tab.repeat(1), interfToISAX)  
				+ endl+tab.repeat(1)+""
				+ endl+tab.repeat(1)+"// Interface to the Core"
				+ endl+tab.repeat(1)+"\n"+this.myLanguage.AllignText(tab.repeat(1), interfToCore) 
				+ endl+tab.repeat(1)+""
				+ endl+tab.repeat(0)+");"
				+ endl+tab.repeat(0)+"// Declare local signals"
				+ endl+tab.repeat(0)+declarations
				+ endl+tab.repeat(0)+""
				+ endl+tab.repeat(0)+"// Logic"
				+ endl+tab.repeat(0)+logic
				+ endl+tab.repeat(0)+""
				+ endl+tab.repeat(0)+"endmodule\n"
				+ endl+tab.repeat(0)+"\n"
				+ endl+tab.repeat(0)+otherModules+"\n";
		
		// Write text to file CoresSrc/CommonLogicModule.sv
		toFile.UpdateContent("CommonLogicModule.sv", textToWrite);
		toFile.WriteFiles(myLanguage.GetDictModule(),myLanguage.GetDictEndModule(), outPath);
	}

}
