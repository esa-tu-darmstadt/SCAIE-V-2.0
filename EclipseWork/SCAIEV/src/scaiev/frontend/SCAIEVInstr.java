package scaiev.frontend;

import java.util.ArrayList;

import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Stream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.pipeline.PipelineFront;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.util.Lang;



public class SCAIEVInstr {
	// logging
	protected static final Logger logger = LogManager.getLogger();

	private String encodingOp;
	private String encodingF3;
	private String encodingF7;
	private String constRd;
	private String instr_name;
	private String instr_type;
	private boolean decoupled = false;
	private boolean dynamic = false;
	private Set<InstrTag> tags = EnumSet.noneOf(InstrTag.class);
	private HashMap<SCAIEVNode, List<Scheduled>> node_scheduled = new HashMap<SCAIEVNode, List<Scheduled>>();
	private HashMap<Integer, Integer> regs = new HashMap<>(); //0->read, 1->write, 2-> read and write
	private int zolLoopDepth = 0;

	public boolean ignoreEncoding = false; 
	
	public static final String noEncodingInstr = "DUMMYInstr";

	public enum InstrTag {
		/** The ISAX interface has the ISAX name in its RdStall, RdFlush pins */
		PerISAXRdStallFlush("RdStallFlush-Is-Per-ISAX"),
		/** The ISAX interface has the ISAX name all read node pins (RdRS1/2, RdRD, RdMem, RdInstr, RdCustomReg, etc.) */
		PerISAXReadResults("ReadResults-Are-Per-ISAX");
		
		public final String serialName;
		
		private InstrTag(String serialName) {
			this.serialName = serialName;
		}
		public static Optional<InstrTag> fromSerialName(String serialName) {
			return Stream.of(InstrTag.values()).filter(tagVal -> tagVal.serialName.equals(serialName)).findAny();
		}
	}
	
	// Constructors
	public SCAIEVInstr(String instr_name, String encodingF7, String encodingF3,String encodingOp, String instrType) {
		this.instr_name = instr_name;
		this.encodingOp = encodingOp;
		this.encodingF3 = encodingF3;
		this.encodingF7 = encodingF7;
		this.instr_type  = instrType;
		this.constRd = "";
		if(instr_name.contains(noEncodingInstr))
			ignoreEncoding = true;
	}	
	public SCAIEVInstr(String instr_name, String encodingF7, String encodingF3,String encodingOp, String instrType, String constRD) {
		this.instr_name = instr_name;
		this.encodingOp = encodingOp;
		this.encodingF3 = encodingF3;
		this.encodingF7 = encodingF7;
		this.instr_type  = instrType;
		this.constRd = constRD;
		if(instr_name.contains(noEncodingInstr))
			ignoreEncoding = true;
	}
	public SCAIEVInstr(String instr_name) {
		this.instr_name = instr_name;
		this.encodingOp = "";
		this.encodingF3 = "";
		this.encodingF7 = "";
		this.constRd = "";
		this.instr_type  = "R";
		if(instr_name.contains(noEncodingInstr))
			ignoreEncoding = true;
	}
	
	@FunctionalInterface
	public interface FrontendNodeBiConsumer<T, U> {
	   void accept(T t, U u) throws FrontendNodeException;
	}
	
	private void CheckThrowNodeUniquePerCycle(SCAIEVNode node) throws FrontendNodeException {
		if (node_scheduled.containsKey(node)) {
			HashSet<Integer> presentStartCycles = new HashSet<Integer>();
			for (Scheduled sched : node_scheduled.get(node)) {
				if (!presentStartCycles.add(sched.GetStartCycle()))
					throw new FrontendNodeException(instr_name + ": " + node + " present several times in cycle " + sched.GetStartCycle());
			}
		}

	}

	public void ConvertToBackend(Core core, BNode userBNode) throws FrontendNodeException {
		for (SCAIEVNode node : userBNode.GetAllFrontendNodes()) {
			CheckThrowNodeUniquePerCycle(node);
		}
		List<PipelineStage> startSpawnStagesList = core.GetStartSpawnStages().asList();
		assert(startSpawnStagesList.size() >= 1);
		if (startSpawnStagesList.size() > 1)
			logger.warn("ConvertToBackend - only considering first of several 'start spawn stages'");
		PipelineStage startSpawnStage = startSpawnStagesList.get(0);
		int postStartSpawnStagePos = startSpawnStage.getStagePos() + 1;
		
		List<PipelineStage> postStartSpawnStagesList = new PipelineFront(startSpawnStage.getNext().stream().filter(nextStage -> nextStage.getKind() == StageKind.Core)).asList();
		if (postStartSpawnStagesList.size() > 0) {
			postStartSpawnStagePos = postStartSpawnStagesList.get(0).getStagePos();
			int postStartSpawnStagePos_ = postStartSpawnStagePos;
			if (postStartSpawnStagesList.stream().anyMatch(postStartSpawnStage -> postStartSpawnStage.getStagePos() != postStartSpawnStagePos_)) {
				logger.warn("ConvertToBackend - assumption about consistent stagePos for post-startSpawnStage broken");
			}
		}
		
		for (SCAIEVNode node : userBNode.GetAllBackNodes()) {
			SCAIEVNode parentNode = userBNode.GetSCAIEVNode(node.nameParentNode);
			if (parentNode.name.isEmpty() || !node.isSpawn() || node.isAdj())
				continue;
			
			//Get commit and spawn stage positions.
			PipelineFront commitFront = core.TranslateStageScheduleNumber(parentNode.commitStage);
			if (commitFront.asList().isEmpty()) {
				logger.error("The commitStage list for node {} is empty; skipping spawn scheduling.", parentNode.name);
				continue;
			}
			// node's commit stage = its WB stage (for internal states may be diff than WB core's stage)
			PipelineFront spawnFront = new PipelineFront(commitFront
				.streamNext_bfs(succ -> commitFront.contains(succ)) //Do not iterate beyond commitStage's direct successors
				.filter(curStage -> !commitFront.contains(curStage))); //Do not include commitStage itself
			if (spawnFront.asList().isEmpty()) {
				logger.error("The commitStage successor list for node {} is empty; skipping spawn scheduling.", parentNode.name);
				continue;
			}
			int commitFrontPos = commitFront.asList().get(0).getStagePos();
			if (commitFront.asList().stream().anyMatch(otherCommitStage -> otherCommitStage.getStagePos() != commitFrontPos)) {
				logger.error("The commitStage list position for node {} is inconsistent; skipping spawn scheduling.", parentNode.name);
				continue;
			}
			int spawnFrontPos = spawnFront.asList().get(0).getStagePos();
			if (spawnFront.asList().stream().anyMatch(otherSpawnStage -> otherSpawnStage.getStagePos() != spawnFrontPos)) {
				logger.error("The commitStage successor list position for node {} is inconsistent; skipping spawn scheduling.", parentNode.name);
				continue;
			}
			
			boolean isSpawn = HasSchedWith(parentNode, snode -> snode.GetStartCycle() >= spawnFrontPos);
			if(node.isSpawn() && !node.isAdj() && isSpawn) { // If we look at a main spawn node & the user wants this spawn operation (>=spawnStage)
				Scheduled oldSched = new Scheduled();
				oldSched = GetCheckUniqueSchedWith(parentNode, snode -> spawnFront.asList().stream().allMatch(spawnStage -> snode.GetStartCycle() >= spawnStage.getStagePos()));
				// This is no spawn in traditional (scaiev trad) sense, it's an instruction that writes right away based on its valid bit. Not really started by an opcode
				if(this.HasNoOp()) {
					oldSched.UpdateStartCycle(commitFrontPos); // for write nodes, take the WB stage of that node // for read nodes, take the "read regfile" stage
				} else if(node.isInput || (node==userBNode.RdMem_spawn) ){ // spawn for reading state not supported for the moment. Just for write nodes. Or spawn as instr without decoding,which is actually mapped on read stage
					// Memory spawn should have interf to core by default. It won't have to ISAX, as it's defined like this within BNode prop
//					for(SCAIEVNode adjNode : userBNode.GetAdjSCAIEVNodes(node))
//						if(adjNode.mustToCore) {
//							oldSched.AddAdjSig(adjNode.getAdj());
//						    logger.info("INFO SCAIEVInstr. Adj Signal "+adjNode+ " added for node "+node);
//						}
					// We must distinguish : a) SCAL must implement spawn as decoupled b) SCAL must implement spawn with stall
					if(!decoupled) {
						//if(node.name.contains("Mem")) logger.error("Stall not yet supported for memory. please select is_decoupled parameter");
						//oldSched.UpdateStartCycle(postStartSpawnStagePos); // in stall strategy, result returned in start spawn stage + 1 
					}
					node_scheduled.remove(parentNode); // Remove parent node which was not spawn
					PutSchedNode(node, oldSched); // Add spawn, leave user options for portability
				
				}
			}
		}
		
		// Make sure sched contains the same BNode feature as updated by SCAIEVLogic Class 
		for(SCAIEVNode node: userBNode.GetAllFrontendNodes()) {
			if(this.node_scheduled.containsKey(node)) {
				List<Scheduled> oldSched = node_scheduled.get(node); 
				node_scheduled.remove(node); 
				node_scheduled.put(node, oldSched);
			}			
		}
	}
	
	public void AddAdjacentNode (SCAIEVNode node , AdjacentNode adj, int stage) throws FrontendNodeException {
		Scheduled oldSched =  GetCheckUniqueSchedWith(node, snode -> snode.GetStartCycle()==stage);
		oldSched.AddAdjSig(adj);
		node_scheduled.remove(node); 
		PutSchedNode(node, oldSched);
		
	}
	
	public void PutSchedNode( SCAIEVNode node, Scheduled newShedNode) {
		List<Scheduled> lst = node_scheduled.get(node);
		if (lst == null) {
			lst = new ArrayList<Scheduled>();
			node_scheduled.put(node, lst);
		}
		
		lst.add(newShedNode);
	}
	
	public void PutSchedNode(SCAIEVNode node, int startCycle) {
		Scheduled new_scheduled = new Scheduled(startCycle, new HashSet<AdjacentNode>(),new HashMap<AdjacentNode,Integer>());
		PutSchedNode(node, new_scheduled);
	}
	
	
	public void PutSchedNode ( SCAIEVNode node, int startCycle, AdjacentNode adjSignal) {
		HashSet<AdjacentNode> adjSignals = new HashSet<AdjacentNode>();
		adjSignals.add(adjSignal) ;
		Scheduled new_scheduled = new Scheduled(startCycle, adjSignals,new HashMap<AdjacentNode,Integer>());
		PutSchedNode(node, new_scheduled);
	}
	
	public void PutSchedNode ( SCAIEVNode node, int startCycle, AdjacentNode adjSignal1, AdjacentNode adjSignal2) {
		HashSet<AdjacentNode> adjSignals = new HashSet<AdjacentNode>();
		adjSignals.add(adjSignal1) ;
		adjSignals.add(adjSignal2) ;
		Scheduled new_scheduled = new Scheduled(startCycle, adjSignals,new HashMap<AdjacentNode,Integer>());
		PutSchedNode(node, new_scheduled);
	}
	
	
	public void PutSchedNode ( SCAIEVNode node, int startCycle, boolean hasValid, boolean hasAddr) {
		HashSet<AdjacentNode> adjSignals = new HashSet<AdjacentNode>();
		if(hasValid)
			adjSignals.add(AdjacentNode.validReq) ;
		if(hasAddr)
			adjSignals.add(AdjacentNode.addr) ;
		Scheduled new_scheduled = new Scheduled(startCycle, adjSignals,new HashMap<AdjacentNode,Integer>());
		PutSchedNode(node, new_scheduled);
	}
	
	public void  PutSchedNode ( SCAIEVNode node, int startCycle, HashSet<AdjacentNode> adjSignals) {
		Scheduled new_scheduled = new Scheduled(startCycle, adjSignals,new HashMap<AdjacentNode,Integer>());
		PutSchedNode(node, new_scheduled);
	}

	public void PutSchedNode ( SCAIEVNode node, int startCycle,AdjacentNode adjSignal,AdjacentNode constAdjSignal, int constValue) { // for CSR
		HashSet<AdjacentNode> adjSignals = new HashSet<AdjacentNode>();
		adjSignals.add(adjSignal) ;
		HashMap<AdjacentNode,Integer> constSig = new HashMap<AdjacentNode,Integer>();
		constSig.put(constAdjSignal, constValue);
		Scheduled new_scheduled = new Scheduled(startCycle, adjSignals,constSig);
		PutSchedNode(node, new_scheduled);
	}
	
	public void PutSchedNode ( SCAIEVNode node, int startCycle,AdjacentNode constAdjSignal, int constValue) { // for CSR
		HashMap<AdjacentNode,Integer> constSig = new HashMap<AdjacentNode,Integer>();
		constSig.put(constAdjSignal, constValue);
		Scheduled new_scheduled = new Scheduled(startCycle, new HashSet<AdjacentNode>(),constSig);
		PutSchedNode(node, new_scheduled);
	}
	
	public void PutSchedNode ( SCAIEVNode node, int startCycle,HashSet<AdjacentNode> adjSignals,HashMap<AdjacentNode,Integer> constAdj) { // for CSR
		Scheduled new_scheduled = new Scheduled(startCycle,adjSignals,constAdj);
		PutSchedNode(node, new_scheduled);
	}
	
	public void SetAsDecoupled (boolean decoupled) {
		this.decoupled = decoupled;
	}
	public void SetAsDynamic (boolean dynamic) {
		this.dynamic = dynamic;
	}
	public void SetEncoding(String encodingF7, String encodingF3,String encodingOp, String instrType) {		 
		 this.encodingOp = encodingOp;
		 this.encodingF3 = encodingF3;
		 this.encodingF7 = encodingF7;
		 this.instr_type  = instrType;
		 logger.debug("Encoding updated. Op Codes F7|F3|Op: " +this.encodingF7+"|"+this.encodingF3+"|"+this.encodingOp+" and instruction type now is: "+this.instr_type);
	 }
	
	public void RemoveNodeWith(SCAIEVNode node, Predicate<Scheduled> cond){
		List<Scheduled> nodes = node_scheduled.get(node);
		if (nodes != null)
			nodes.removeIf(cond);
	}
	public void RemoveNodeStage(SCAIEVNode node, int stage){
		RemoveNodeWith(node, snode -> (snode.GetStartCycle() == stage));
	}

	// Function to check if an instruction has scheduled a certain node (for exp if it writes PC)
	public Boolean HasNode(SCAIEVNode node_name) {
		if(node_scheduled.containsKey(node_name))
			return true;
		else
			return false;
	}

	public Scheduled GetCheckUniqueSchedWith(SCAIEVNode node, Predicate<Scheduled> cond) throws FrontendNodeException {
		Iterator<Scheduled> iter = GetSchedWithIterator(node, cond);
		if (!iter.hasNext())
			return null;
		Scheduled ret = iter.next();
		if (iter.hasNext())
			throw new FrontendNodeException(instr_name + ": " + node.name + " present several times (max 1)");
		return ret;
	}
	public Iterator<Scheduled> GetSchedWithIterator(SCAIEVNode node, Predicate<Scheduled> cond) {
		List<Scheduled> nodes = node_scheduled.get(node);
		if (nodes == null) {
			return new Iterator<Scheduled>() {
				@Override
				public boolean hasNext() {
					return false;
				}
				@Override
				public Scheduled next() {
					return null;
				}};
		}
		return nodes.stream().filter(cond).iterator();
	}
	public Scheduled GetSchedWith(SCAIEVNode node, Predicate<Scheduled> cond) {
		Iterator<Scheduled> iter = GetSchedWithIterator(node, cond);
		if (!iter.hasNext())
			return null;
		return iter.next();
	}
	public Boolean HasSchedWith(SCAIEVNode node, Predicate<Scheduled> cond) {
		return GetSchedWithIterator(node, cond).hasNext();
	}

	public List<Scheduled> GetNodes(SCAIEVNode node) {
			return node_scheduled.get(node);
	}
	public Scheduled GetFirstNode(SCAIEVNode node) {
		if(node_scheduled.containsKey(node)) 
			return node_scheduled.get(node).get(0);			
		else 
			return null;
	}
	
	public void addTag(InstrTag tag) { tags.add(tag); }
	public boolean hasTag(InstrTag tag) { return tags.contains(tag); }
	public Set<InstrTag> getTags() { return Collections.unmodifiableSet(tags); } 
	
	public String GetInstrType() {
		return instr_type;
	}
	
	public HashMap<Integer,Integer> getRegs(){
		return regs;
	}
	
	public int getLoopDepth() {
		return zolLoopDepth;
	}
	
	public String GetEncodingBasic() {
			return encodingF7+"----------"+encodingF3+"-----"+encodingOp;	// Return encoding
	}
	
	public String GetEncodingOp(Lang language) {		
		if(language == Lang.VHDL)
			return "\""+encodingOp+"\"";	
		else if(language == Lang.Verilog  || language == Lang.Bluespec) {
			String returnEnc = encodingOp.replace("-","?");
			return "7'b"+returnEnc;
		} else 
			return encodingOp;
	}
	
	
	public boolean HasNoOp() {
		return encodingOp.equals("-------") || encodingOp.isEmpty();
	}
	
	
	public String GetEncodingConstRD(Lang language) {
		if(this.constRd.isEmpty())
			return "-----";
		if(language == Lang.VHDL)
			return "\""+this.constRd+"\"";	
		else if(language == Lang.Verilog || language == Lang.Bluespec) {
			String returnEnc = constRd.replace("-","?");		
			return "7'b"+returnEnc;
		} else 
			return this.constRd;
	}
	
	public String GetEncodingF7(Lang language) {		
		if(language == Lang.VHDL)
			return "\""+encodingF7+"\"";	
		else if(language == Lang.Verilog || language == Lang.Bluespec) {
			String returnEnc = encodingF7.replace("-","?");
			return "7'b"+returnEnc;
		} else 
			return encodingF7;
	}
	
	public String GetEncodingF3(Lang language) {		
		if(language == Lang.VHDL)
			return "\""+encodingF3+"\"";	
		else if(language == Lang.Verilog  || language == Lang.Bluespec) {
			String returnEnc = encodingF3.replace("-","?");
			return "3'b"+returnEnc;
		} else 
			return encodingF3;
	}
	
	/** True if the ISAX has a decoupled portion (static or dynamic) */
	public boolean GetRunsAsDecoupled () {
		return decoupled;
	}

	/** True if the ISAX has a dynamic decoupled portion */
	public boolean GetRunsAsDynamicDecoupled () {
		return dynamic && decoupled;
	}

	/** True if the ISAX has a dynamic portion (decoupled or semi-coupled) */
	public boolean GetRunsAsDynamic () {
		return dynamic;
	}
	
	public HashMap<SCAIEVNode, List<Scheduled>> GetSchedNodes(){
		return node_scheduled;
	}
	
	public String GetName() {
		return instr_name;
	}
	
	
	@Override
    public String toString() { 
        return String.format("ISAX named:" +this.instr_name+ " with nodes: " + this.node_scheduled); 
    } 
	
}
