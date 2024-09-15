// NEW Version
package scaiev.frontend;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

import scaiev.backend.BNode;
import scaiev.coreconstr.Core;
import scaiev.drc.DRC;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.util.Lang;

public class SCAIEVInstr {
	private String encodingOp;
	private String encodingF3;
	private String encodingF7;
	private String constRd;
	private String instr_name;
	private String instr_type;
	private boolean decoupled = false;
	private boolean dynamic_decoupled = false;
	private HashMap<SCAIEVNode, List<Scheduled>> node_scheduled = new HashMap<SCAIEVNode, List<Scheduled>>();

	public boolean ignoreEncoding = false; 
	
	public static final String noEncodingInstr = "DUMMYInstr";
	
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
		DRC.CheckEncoding(this);
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
		DRC.CheckEncoding(this);
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
		
		for (SCAIEVNode node : userBNode.GetAllBackNodes()) {
			SCAIEVNode parentNode = node; 
			if(node.isSpawn())
				parentNode = userBNode.GetSCAIEVNode(node.nameParentNode);
			
			int spawnStage = parentNode.spawnStage;
			boolean isRequired = HasSchedWith(node, snode -> snode.GetStartCycle()>=0);
			boolean isSpawn = HasSchedWith(parentNode, snode -> snode.GetStartCycle()>=spawnStage);
			Scheduled oldSched =this.GetFirstNode(parentNode);
			if(this.HasNoOp()  && !node.isAdj() && isRequired) { // if no op = always, no matter which stage
					oldSched.UpdateStartCycle(parentNode.commitStage); // for write nodes, take the WB stage of that node // for read nodes, take the "read regfile" stage
			} else if(node.isSpawn() && !node.isAdj() && isSpawn) { // If we look at a main spawn node & the user wants this spawn operation (>=spawnStage)
				// This is no spawn in traditional (scaiev trad) sense, it's an instruction that writes right away based on its valid bit. Not really started by an opcode. Always block
				if(node.isInput | (node==BNode.RdMem_spawn) ){ // spawn for reading state not supported for the moment. Just for write nodes. Or spawn as instr without decoding,which is actually mapped on read stage
					// Memory spawn write sig should have interf to core by default. It won't have to ISAX, as it's defined like this within BNode prop
					for(SCAIEVNode adjNode : userBNode.GetAdjSCAIEVNodes(node))
						if(adjNode.mustToCore && adjNode.noInterfToISAX) { // we need noInterf, otherwise addr signal added on ISAX interf even when not required by user
							oldSched.AddAdjSig(adjNode.getAdj());
						    System.out.println("INFO SCAIEVInstr. Adj Signal "+adjNode+ " added for node "+node);
						}
					// We must distinguish : a) SCAL must implement spawn as decoupled b) SCAL must implement spawn with stall
					if(!decoupled) {
						oldSched.UpdateStartCycle(core.GetStartSpawnStage()); // in stall strategy, operands read & result returned in start spawn stage
					} else {
						node_scheduled.remove(parentNode); // Remove parent node which was not spawn
						PutSchedNode(node, oldSched); // Add spawn, leave user options for portability	
					}
				
				}
			}
		}
		
		// Make sure sched contains the same BNode feature as updated by SCAIEV Class 
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
	public void SetAsDynamicDecoupled (boolean dynamic_decoupled) {
		this.dynamic_decoupled = dynamic_decoupled;
		if(dynamic_decoupled)
			this.decoupled = true;
	}
	public void SetEncoding(String encodingF7, String encodingF3,String encodingOp, String instrType) {		 
		 this.encodingOp = encodingOp;
		 this.encodingF3 = encodingF3;
		 this.encodingF7 = encodingF7;
		 this.instr_type  = instrType;
		 DRC.CheckEncoding(this);
		 System.out.println("INTEGRATE. Encoding updated. Op Codes F7|F3|Op: " +this.encodingF7+"|"+this.encodingF3+"|"+this.encodingOp+" and instruction type now is: "+this.instr_type);
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
	
	public String GetInstrType() {
		return instr_type;
	}
	

	public String GetEncodingString() {
		if(encodingOp.isEmpty())
			return "";
		else 
			return "key  = M\""+encodingF7+"----------"+encodingF3+"-----"+encodingOp+"\",";	// Return encoding
	}
	
	public String GetEncodingBasic() {
			return encodingF7+"----------"+encodingF3+"-----"+encodingOp;	// Return encoding
	}
	
	public String GetEncodingOp(Lang language) {		
		if(language == Lang.VHDL)
			return "\""+encodingOp+"\"";	
		else if(language == Lang.Verilog  || language == Lang.Bluespec) {
			String returnEnc = encodingF7.replace("-","?");
			return "7'b"+returnEnc;
		} else 
			return encodingOp;
	}
	
	
	public boolean HasNoOp() {
		return encodingOp.equals("-------") ||  encodingOp.isEmpty();
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
	
	
	public boolean GetRunsAsDecoupled () {
		return decoupled;
	}
	
	public boolean GetRunsAsDynamicDecoupled () {
		return dynamic_decoupled;
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
