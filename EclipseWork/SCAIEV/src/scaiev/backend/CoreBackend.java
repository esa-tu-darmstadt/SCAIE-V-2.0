package scaiev.backend;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;


class NodePropBackend {
	public String data_type;
	public String assignSignal; 
	public String assignModule; 
	public String name;	
	public int stage; // RS has different assign signals in different stages
}

class Module {
	public String name; 
	public String parentName;
	public String file; 
	public String interfaceName; 
	public String interfaceFile;	
}


public class CoreBackend {
	public HashMap <SCAIEVNode,HashMap<Integer, NodePropBackend>> nodes = new HashMap <SCAIEVNode, HashMap<Integer, NodePropBackend>>(); // <NodeName,Module>
	public HashMap <String, Module> fileHierarchy = new HashMap <String, Module>(); // <moduleName,Module>
	public HashMap <Integer, String> stages = new HashMap <Integer, String>(); // <stageNr,name>, useful for Vex
	public String topModule;
	private BNode  BNode= new BNode();
	
	public String getCorePathIn() {
		return null;
	}
	
	/**
	 * Preparation function intended to configure SCAL before it generates its modules.
	 * @param ISAXes ISAX descriptors by name of ISAX
	 * @param op_stage_instr Set of ISAXes by stage by node
	 * @param core General core constraints descriptor
	 * @param scalAPI SCAL configuration interface
	 */
	public void Prepare (HashMap <String,SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr, Core core, SCALBackendAPI scalAPI) {
	}

	/**
	 * Integrate all changes into the core's source code, and write the modified HDL files.
	 * @param ISAXes ISAX descriptors by name of ISAX
	 * @param op_stage_instr Set of ISAXes by stage by node
	 * @param extension_name Arbitrary name for the overall integration given by the user
	 * @param core General core constraints descriptor
	 * @param out_path Output path to write the modified HDL files to.
	 */
	public boolean Generate (HashMap <String,SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr, String extension_name, Core core, String out_path) {
		return false;
	}
	
	
	/**
	 * Some nodes like Memory require the Valid bit in earlier stages. In case of Memory for exp. for computing dest. address. SCAL will generate these valid bits, 
	 * but SCAL must know from which stage they are requried. These valid bits are not combined with the optional user valid bit. They are just based on instr decoding
	 * Function to be overwritten by cores which need this.
	 * @return A hash map containing the SCAIE-V Node and the corresponding earliest stage where valid bit is requested
	 */
	public HashMap<SCAIEVNode, Integer> PrepareEarliest(){
		return new  HashMap<SCAIEVNode, Integer>();
	};
	
	
	public void PopulateNodesMap(int maxStage) {
		Set<SCAIEVNode> backendNodes = BNode.GetAllBackNodes();
		for(SCAIEVNode node:  backendNodes ) {
			HashMap<Integer, NodePropBackend> newStageNode = new HashMap<Integer, NodePropBackend>();
			for(int i=0;i<=maxStage;i++) {
				NodePropBackend prop = new NodePropBackend();
				newStageNode.put(i, prop);
			}
			nodes.put(node, newStageNode);
		}
		
		// Default nodes for spawn

		this.PutNode("","","",BNode.ISAX_spawnStall_regF_s,maxStage+1);
		this.PutNode("","","",BNode.ISAX_spawnStall_mem_s,maxStage+1);
	}
	
	public boolean IsNodeInStage(SCAIEVNode node, int stage) {
		if(nodes.containsKey(node) && nodes.get(node).containsKey(stage))
			return true; 
		else 
			return false; 
	}
	public int NodeSize(SCAIEVNode node, int stage) {
		return node.size;
	}	
	public boolean NodeIn(SCAIEVNode node,int stage) {
		return node.isInput;
	}	
	public String NodeDataT(SCAIEVNode node,int stage) {
		return nodes.get(node).get(stage).data_type;
	}
	public String NodeAssign(SCAIEVNode node,int stage) {
		return nodes.get(node).get(stage).assignSignal;
	}	
	/**
	 * 
	 * @param node
	 * @param stage
	 * @return
	 */
	public String NodeAssignM(SCAIEVNode node,int stage) {
		return nodes.get(node).get(stage).assignModule;
	}
	public String NodeName(SCAIEVNode node,int stage) {
		return nodes.get(node).get(stage).name;
	}
	public int NodeStage(SCAIEVNode node,int stage) {
		return nodes.get(node).get(stage).stage;
	}
	public String ModName(String module) {
		return fileHierarchy.get(module).name;
	}
	public String ModParentName(String module) {
		return fileHierarchy.get(module).parentName;
	}
	public String ModFile(String module) {
		return fileHierarchy.get(module).file;
	}
	public String ModInterfName(String module) {
		return fileHierarchy.get(module).interfaceName;
	}
	public String ModInterfFile(String module) {
		return fileHierarchy.get(module).interfaceFile;
	}
	
	

	
	/**
	 * Add node into HashMap with corresponding Info. For signals that are typical for one core we won't define them in BNode, but pass their name as string to PutNode.
	 * @param size
	 * @param input
	 * @param data_type
	 * @param assignSignal
	 * @param assignModule
	 * @param addNode
	 * @param stage
	 */
	public void PutNode(int size,  boolean input, String data_type, String assignSignal, String assignModule, String addNode, int stage) {
		NodePropBackend node = new NodePropBackend();
		node.data_type = data_type;
		node.assignSignal = assignSignal;
		node.assignModule = assignModule;
		node.name = addNode;
		node.stage = stage;
		
		boolean alreadyAdded = false;
		for (SCAIEVNode checkNode : nodes.keySet())
			if(checkNode.name.contentEquals(addNode)) {
				alreadyAdded = true;
				nodes.get(checkNode).put(stage, node);
			}
		if(!alreadyAdded){
			SCAIEVNode addNew;
			if(BNode.HasSCAIEVNode(addNode))
				addNew = BNode.GetSCAIEVNode(addNode);
			else 
				addNew = new SCAIEVNode( addNode,size,input); 
			nodes.put(addNew, new HashMap<Integer, NodePropBackend>());
			nodes.get(addNew).put(stage, node);	
		}
	}
	
	
	
	public void PutNode(String data_type, String assignSignal, String assignModule, SCAIEVNode addNode, int stage) {
		NodePropBackend node = new NodePropBackend();
		node.data_type = data_type;
		node.assignSignal = assignSignal;
		node.assignModule = assignModule;
		node.name = addNode.name;
		node.stage = stage;

		HashMap<Integer, NodePropBackend> stageMap = nodes.get(addNode);
		if (stageMap == null) {
			stageMap = new HashMap<Integer, NodePropBackend>();
			nodes.put(addNode, stageMap);
		}
		stageMap.put(stage, node);	
	}
	
	
	public SCAIEVNode GetNode( String node) {
		for (SCAIEVNode checkNode : nodes.keySet())
			if(checkNode.name.contentEquals(node)) {
				return checkNode;
			}
		return null; 
	}
	
	public void PutModule(Path interfaceFile,  String interfaceName, Path file, String parentName, String name) {
		PutModule(interfaceFile.toString(), interfaceName, file.toString(), parentName, name);
	}

	public void PutModule(String interfaceFile,  String interfaceName, String file, String parentName, String name) {
		Module module = new Module();
		module.interfaceFile = interfaceFile;
		module.interfaceName = interfaceName;
		module.file = file;
		module.parentName = parentName;
		module.name = name;
		fileHierarchy.put(name,module);	
		if(parentName.equals(""))
			topModule = name;
	}

}
