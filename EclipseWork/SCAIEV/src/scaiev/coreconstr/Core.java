package scaiev.coreconstr;


import java.util.HashMap;

import scaiev.frontend.FNode;
import scaiev.frontend.SCAIEVNode;

public class Core {
	private Boolean debug = true;
	private HashMap<SCAIEVNode,CoreNode> nodes = new HashMap<SCAIEVNode,CoreNode>();
	private Boolean flush;
	private Boolean datahaz;
	private int[] stall;
	private String name; 
	public int maxStage;
	
	public Core (HashMap<SCAIEVNode,CoreNode> nodes,Boolean flush, Boolean datahaz, int[] stall, String name) {
		this.datahaz  = datahaz;
		this.flush = flush;
		this.nodes = nodes;
		this.stall = stall;
		this.name = name;
	}
	
	public Core () {}
	
	@Override
    public String toString() { 
        return String.format("INFO. Core. Core named:" +name+ " with nodes = "+nodes.toString()); 
    } 
	 
	public void PutName(String name) {
		this.name = name;		
	}
	
	public void PutNodes(HashMap<SCAIEVNode,CoreNode> nodes) {
		this.nodes = nodes;		
	}
	
	public void PutNode(SCAIEVNode fnode,CoreNode corenode) {
		this.nodes.put(fnode, corenode);		
	}
	

	public int GetSpawnStage() {
		return maxStage+1;
	}
	
	public int GetStartSpawnStage() {
		return nodes.get(FNode.RdRS1).GetEarliest();
	}
	public String  GetName() {
		return name;	
	}
	
	public HashMap<SCAIEVNode,CoreNode>  GetNodes() {
		return nodes;	
	}
	
	
	
}
