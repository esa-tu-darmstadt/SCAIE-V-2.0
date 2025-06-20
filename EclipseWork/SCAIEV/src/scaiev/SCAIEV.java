package scaiev;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.yaml.snakeyaml.Yaml;

import scaiev.backend.BNode;
import scaiev.backend.CoreBackend;
import scaiev.backend.Orca;
import scaiev.backend.Piccolo;
import scaiev.backend.PicoRV32;
import scaiev.backend.VexRiscv;
import scaiev.coreconstr.Core;
import scaiev.coreconstr.CoreDatab;
import scaiev.coreconstr.CoreNode;
import scaiev.drc.DRC;
import scaiev.frontend.FNode;
import scaiev.frontend.FrontendNodeException;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAL;
import scaiev.frontend.Scheduled;
import scaiev.frontend.SCAIEVNode.AdjacentNode;


public class SCAIEV {	

	private CoreDatab coreDatab;   									// database with supported cores 
	private HashMap <String,SCAIEVInstr>  instrSet = new HashMap <String,SCAIEVInstr> ();	// database of requested Instructions
	private HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr = new HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>>();
    private String extensionName = "DEMO";
    private HashMap<SCAIEVNode, HashMap<String, Integer>> spawn_instr_stage = new  HashMap<SCAIEVNode, HashMap<String, Integer>>();
    private HashMap<String, Integer> earliest_useroperation = new HashMap<String, Integer>();
    
	public BNode BNodes = new BNode(); 
	public FNode FNodes = new FNode(); 
	
    // SCAIE-V Adaptive Layer
    private SCAL scalLayer = new SCAL();
	
    // DRC 
    boolean errLevelHigh = true;
    
	public SCAIEV() {
		// Print currently supported nodes 
		System.out.println("SHIM. Instantiated shim layer. Supported nodes are: "+FNodes.toString());
		this.coreDatab = new CoreDatab();
		coreDatab.ReadAvailCores("./Cores");
	}
	
	public void SetErrLevel (boolean errLevelHigh) {
		this.errLevelHigh = errLevelHigh;
	}
	public SCAIEVInstr addInstr(String name, String encodingF7, String encodingF3,String encodingOp, String instrType) {
		SCAIEVInstr newISAX = new SCAIEVInstr(name,encodingF7, encodingF3,encodingOp, instrType);
		instrSet.put(name,newISAX);
		return newISAX;		
	}
	public SCAIEVInstr addInstr(String name) {
		SCAIEVInstr newISAX = new SCAIEVInstr(name);
		instrSet.put(name,newISAX);
		return newISAX;		
	}
	
	public void setSCAL (boolean nonDecWithDH, boolean decWithValid, boolean decWithAddr, boolean decWithDH, boolean decWithStall, boolean decWithInpFIFO) {
		scalLayer.SetSCAL(nonDecWithDH,decWithValid ,decWithAddr , decWithInpFIFO);
	}
	
	public boolean Generate(String coreName, String outPath) throws FrontendNodeException {
		boolean success = true; 
		
		
		// Select Core
		Core core = coreDatab.GetCore(coreName);
		
		AddCommitStagesToNodes(core); // update FNodes based on core datasheet (their commit stages, only for relevant nodes)

		// Create HashMap with <operations, <stages,instructions>>. 
		CreateOpStageInstr(core);		
		
		// Print generated hashMap as Info for user
		OpStageInstrToString(); 
		
		AddUserNodesToCore(core);
		scalLayer.BNode = BNodes; scalLayer.FNode = FNodes;	
		
		// Check errors
		DRC drc = new DRC(instrSet, op_stage_instr, core, BNodes);
		drc.SetErrLevel(errLevelHigh);
		drc.CheckSchedErr(core,op_stage_instr);
		drc.CheckEncPresent(instrSet);
		
		// Get metadata from core required by SCAL
		Optional<CoreBackend> coreInstanceOpt = Optional.empty();
			
		if(coreName.contains("VexRiscv")) {
			coreInstanceOpt = Optional.of(new VexRiscv(core));
			scalLayer.PrepareEarliest(coreInstanceOpt.get().PrepareEarliest());
		}
		else if(coreName.contains("Piccolo")) {
			coreInstanceOpt = Optional.of(new Piccolo());
			scalLayer.PrepareEarliest(coreInstanceOpt.get().PrepareEarliest());
		}
		else if(coreName.contains("ORCA")) {
			coreInstanceOpt = Optional.of(new Orca());
			scalLayer.PrepareEarliest(coreInstanceOpt.get().PrepareEarliest());
		}
		else if(coreName.contains("PicoRV32")) {
			coreInstanceOpt = Optional.of(new PicoRV32());
			scalLayer.PrepareEarliest(coreInstanceOpt.get().PrepareEarliest());
		}
		// Generate Interface
		// First generate common logic
		System.out.println("INFO: spawn operations with actual stage numbers: "+spawn_instr_stage);
		System.out.println("INFO: all operations (spawn stage = "+(core.maxStage+1)+"): "+op_stage_instr);

		
		String inPath = coreInstanceOpt.map(coreInstance -> coreInstance.getCorePathIn()).orElse("CoresSrc");
		
		scalLayer.Prepare(instrSet, op_stage_instr, spawn_instr_stage, core);

		coreInstanceOpt.ifPresent(coreInstance -> coreInstance.Prepare(instrSet, op_stage_instr, core, scalLayer));
	 		
		scalLayer.Generate(inPath, outPath);
		
		// Remove user nodes before calling backend classes. Cores do not have to implement them, they were already handled in SCAL 
		RemoveUserNodes();

		success = coreInstanceOpt.map(coreInstance ->
			coreInstance.Generate(instrSet, op_stage_instr, this.extensionName, core, outPath)
		).orElse(false);
		
		
		Yaml netlistYaml = new Yaml();
		String netlistPath = "scaiev_netlist.yaml";
		if (outPath == null)
			netlistPath = Path.of(inPath, netlistPath).toString(); 
		else
			netlistPath = Path.of(outPath, netlistPath).toString();
		try {
			java.io.Writer netlistWriter = new java.io.FileWriter(netlistPath);
			netlistYaml.dump(scalLayer.netlist, netlistWriter);
		} catch (IOException e) {
			e.printStackTrace();
			return false;
		}
		
		return success;
	}
	
	private void CreateOpStageInstr(Core core) throws FrontendNodeException {
		int rdrsStage = core.GetNodes().get(BNode.RdRS1).GetEarliest();
		boolean barrierInstrRequired = false;
		
		for(String instructionName : instrSet.keySet()) {
			SCAIEVInstr instruction = instrSet.get(instructionName);
			// Prereq: make sure nodes in instr map have correct metadata (spawn, commit, stage) 
			instruction.UpdateNodesMetadata(this.BNodes);

			
			// STEP 1: store actual spawn stages , to be used later in SCAL for fire logic & Scoreboard
			// HEADSUP. Make sure code bellow does NOT add always blocks, otherwise SCAL will generate fire logic
			HashMap<SCAIEVNode, List<Scheduled>> originalSchedNodes = instruction.GetSchedNodes();
			if(!instruction.HasNoOp())
				for(SCAIEVNode operation : originalSchedNodes.keySet()) {
					for (Scheduled sched : originalSchedNodes.get(operation)) {
						SCAIEVNode spawnOperation = this.BNodes.GetMySpawnNode(operation);
						if(spawnOperation != null) { // if this is a node that has a spawn feature
							int stage = sched.GetStartCycle();
							if(stage>=operation.spawnStage) {
								if(!spawn_instr_stage.containsKey(spawnOperation)) 
									spawn_instr_stage.put(spawnOperation, new HashMap<String,Integer>()); 
								if(!spawn_instr_stage.get(spawnOperation).containsKey(instructionName))
									spawn_instr_stage.get(spawnOperation).put(instructionName, stage);
							}
						}
						
					}
				} 
			
			// STEP 2: Update Instruction metadata for backend
			// Now, after storing actual spawn stages in case of spawn IF using stall method, let's update ISAX schedule 
			instruction.ConvertToBackend(core,BNodes);
			
			// STEP 3: Add nodes to op_stage_instr
			HashMap<SCAIEVNode, List<Scheduled>> schedNodes = instruction.GetSchedNodes();
			for(SCAIEVNode operation : schedNodes.keySet()) {			
				for (Scheduled sched : schedNodes.get(operation)) {
					SCAIEVNode addOperation = operation; 				
					int stage = sched.GetStartCycle();
					if(stage>=operation.spawnStage) {
						if(!instruction.GetRunsAsDecoupled()) { // If it is a spawn but we have Stall strategy, make it common WrRD so that core handles DH 
							addOperation = this.BNodes.GetSCAIEVNode(operation.nameParentNode);
							stage = core.GetNodes().get(operation).GetEarliest();
						} else {
							stage = core.GetSpawnStage(); // it's actually mapped in HW in node's CommitStage, but interf contains core.spawnStage in naming for debug purposes 
							barrierInstrRequired = true;
						}
					}
						
					if(!op_stage_instr.containsKey(addOperation)) 
						op_stage_instr.put(addOperation, new HashMap<Integer,HashSet<String>>()); 
					if(!op_stage_instr.get(addOperation).containsKey(stage))
						op_stage_instr.get(addOperation).put(stage, new HashSet<String>()); 
					op_stage_instr.get(addOperation).get(stage).add(instructionName);
					
				}
			}
		}
		// Check if any spawn and if yes, add the barrier instr (WrRD spawn & Internal state spawn) 
		boolean barrierNeeded = false;
		for(SCAIEVNode node : op_stage_instr.keySet() )
			if(node.isSpawn())
				barrierNeeded = true;
		if(barrierNeeded && barrierInstrRequired) {
			AddIn_op_stage_instr(BNode.RdIValid,rdrsStage,"disaxkill");
			AddIn_op_stage_instr(BNode.RdIValid,rdrsStage,"disaxfence");
			SCAIEVInstr kill  = SCAL.PredefInstr.kill.instr;
			SCAIEVInstr fence = SCAL.PredefInstr.fence.instr;
			kill.PutSchedNode(FNode.RdIValid,rdrsStage);  
			fence.PutSchedNode(FNode.RdIValid, rdrsStage);  
			instrSet.put("disaxkill", kill);
			instrSet.put("disaxfence", fence);
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
	
	private void OpStageInstrToString() {
		for(SCAIEVNode operation : op_stage_instr.keySet())
			for(Integer stage : op_stage_instr.get(operation).keySet())
				System.out.println("INFO. SCAIEV. Operation = "+ operation+ "in stage = "+stage+ " for instruction/s: "+op_stage_instr.get(operation).get(stage).toString());
		
	}
	
	public void SetExtensionName(String name) {
		this.extensionName = name;
	}
	
	public void DefNewNodes(HashMap<String, Integer> earliest_operation) {
		this.earliest_useroperation = earliest_operation;
		
	}
	
	private void AddCommitStagesToNodes (Core core){
		// Add default values for all BNodes 
		for(SCAIEVNode node : this.BNodes.GetAllBackNodes()) {
			if(!node.isInput && !node.isAdj() &&  core.GetNodes().containsKey(node))  // Non-User Rd Nodes already have earliest defined in Core's datasheet. Only commit stage must be defined
				UpdateSpawnCommitStage(node, core.maxStage+1, core.GetNodes().get(node).GetEarliest() );
			else if(node.isInput && !node.isAdj() &&  core.GetNodes().containsKey(node)) // Write nodes
				UpdateSpawnCommitStage(node, core.maxStage+1, core.GetNodes().get(node).GetLatest() );
		}
		
		// Overwrite default values for specific core nodes 
		UpdateSpawnCommitStage(BNodes.RdMem, core.GetNodes().get(BNode.RdMem).GetEarliest()+1,core.GetNodes().get(BNode.RdMem).GetEarliest());
		UpdateSpawnCommitStage(BNodes.WrMem, core.GetNodes().get(BNode.WrMem).GetEarliest()+1,core.GetNodes().get(BNode.WrMem).GetEarliest());
		UpdateSpawnCommitStage(BNodes.WrRD,core.maxStage+1,core.maxStage);
		UpdateSpawnCommitStage(BNodes.WrPC,core.maxStage+1,0);

		
		// Add commit stage info of WrUser node. First write nodes, than read nodes 
		for(SCAIEVNode node : this.BNodes.GetAllBackNodes()) {
			if(BNodes.IsUserBNode(node) && node.isInput && !node.isAdj()) {
				// Get Latest/Earliest stage
				int latest = 0;
				for(String instr: this.instrSet.keySet()) { // for each instruction 
					if(instrSet.get(instr).HasNode(node)) { // get node
						List<Scheduled> scheds = instrSet.get(instr).GetNodes(node); //check out latest sched stage
						for(Scheduled sched : scheds) {
							if(sched.GetStartCycle()>latest && sched.GetStartCycle()<=core.maxStage) //was:  && !this.instrSet.get(instr).HasNoOp())
								latest = sched.GetStartCycle();
						}	
					}				
				}
				UpdateSpawnCommitStage(node,latest+1,latest); 
			}
		}		
		 
		// Add commit stage info of RdUser node 
		for(SCAIEVNode node : this.BNodes.GetAllBackNodes()) {
			if(BNodes.IsUserBNode(node) && !node.isInput && !node.isAdj()) {
				// Get Latest/Earliest stage
				int earliest = core.maxStage+1;
				for(String instr: this.instrSet.keySet()) { // for each instruction 
					if(instrSet.get(instr).HasNode(node)) { // get node
						List<Scheduled> scheds = instrSet.get(instr).GetNodes(node); //check out latest sched stage
						for(Scheduled sched : scheds) {
							if(sched.GetStartCycle()<earliest && !this.instrSet.get(instr).HasNoOp())
								earliest = sched.GetStartCycle();
						}	
					}				
				}
				if(earliest == core.maxStage+1) {
					SCAIEVNode WrNode = BNodes.GetSCAIEVNode(BNodes.GetNameWrNode(node));
					earliest = WrNode.commitStage;
				}
				UpdateSpawnCommitStage(node,core.maxStage+1,earliest);  
				
			} 			
		}
		
	} 
	
	private void UpdateSpawnCommitStage (SCAIEVNode node, int spawnStage, int commitStage){
		node.commitStage =commitStage;
		node.spawnStage = spawnStage;
		for(SCAIEVNode nodeAdj : BNodes.GetAdjSCAIEVNodes(node)) {
			nodeAdj.commitStage = node.commitStage;
			nodeAdj.spawnStage = node.spawnStage ;
		}
	}
	
	private void AddUserNodesToCore (Core core){
		boolean added = false;
		for(SCAIEVNode operation: this.op_stage_instr.keySet()) {
			if(this.BNodes.IsUserBNode(operation) && !operation.isSpawn()) {
				// Get Latest stage
				int latest =  core.maxStage; 
				Integer earliest = 0;
				boolean noDHWr = true; // is there a write with opcode  = with DH handling?
				boolean noDHRd = true; // is there a read with opcode  = with DH handling?
				// Check if it needs DH (data hazard) 
				SCAIEVNode otheroperation;
				if(operation.isInput) 
					otheroperation = BNodes.GetSCAIEVNode(BNodes.GetNameRdNode(operation));
				else 
					otheroperation = BNodes.GetSCAIEVNode(BNodes.GetNameWrNode(operation));
				for(String instr: this.instrSet.keySet()) { // for each instruction 
					if(instrSet.get(instr).HasNode(operation)) { // get node
						if(!instrSet.get(instr).HasNoOp())
							noDHWr = false;
					}	
					if(instrSet.get(instr).HasNode(otheroperation)) { // get node
						if(!instrSet.get(instr).HasNoOp())
							noDHRd = false;
					}	
				}
				if(noDHWr | noDHRd) { // No write/read with opcode => both rd & wr nodes have no DH
					operation.DH = false; 
					otheroperation.DH = false;
				} else {
					operation.DH = true;
					otheroperation.DH = true;
				}
				
				if(operation.isInput) {
					latest = 0;
					for(String instr: this.instrSet.keySet()) { // for each instruction 
						if(instrSet.get(instr).HasNode(operation)) { // get node
							List<Scheduled> scheds = instrSet.get(instr).GetNodes(operation); //check out latest sched stage
							for(Scheduled sched : scheds) {
								if(sched.GetStartCycle()>latest)
									latest = sched.GetStartCycle();
							}	
						}				
					}
				}
				 
				added = true; 
				if(!operation.isInput)  // Read Node
					earliest = earliest_useroperation.get(FNodes.GetNameWrNode(operation)); // earliest_useroperation has just write nodes (due to wrnode.addr)
				else // WrNode
					earliest = earliest_useroperation.get(operation);
				// TODO latest for WrNode should be infinite
				if(operation.isInput) {
					CoreNode corenode = new CoreNode(earliest, 0,latest, latest+1, operation.name); // default values, it is anyways supposed user defined node well
					core.PutNode(operation, corenode);
				} else {
					CoreNode corenode = new CoreNode(earliest, 0,latest, earliest+1, operation.name); // default values, it is anyways supposed user defined node well
					core.PutNode(operation, corenode);
				}
			}
			
			// Update node if there are multiple spawn instructions. Default was 1
			if(this.BNodes.IsUserBNode(operation) && operation.isSpawn()) {
				if(op_stage_instr.get(operation).get(core.maxStage+1).size()>1) {
					operation.allowMultipleSpawn = true;
					operation.oneInterfToISAX = false;
				}
			}
		}
		
		if(added)
			System.out.println("INFO. After adding user-mode nodes, the core is: "+core);
		
	}
	
	private void RemoveUserNodes(){
		HashSet<SCAIEVNode> remove = new HashSet<SCAIEVNode>();
		for(SCAIEVNode operation: this.op_stage_instr.keySet()) 
			if(this.FNodes.IsUserFNode(operation)) 
				remove.add(operation);
		for(SCAIEVNode operation: remove)
			op_stage_instr.remove(operation);
		
	}
	
}

