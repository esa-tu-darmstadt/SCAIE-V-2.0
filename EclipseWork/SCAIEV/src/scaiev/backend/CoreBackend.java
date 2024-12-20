package scaiev.backend;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.ArrayList;

import scaiev.coreconstr.Core;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;

import scaiev.scal.strategy.MultiNodeStrategy;
import scaiev.scal.strategy.SingleNodeStrategy;


class NodePropBackend {
	public String data_type;
	public String assignSignal; 
	public String assignModule; 
	public String name;	
	public PipelineStage stage; // RS has different assign signals in different stages
}

class Module {
	public String name; 
	public String parentName;
	public String file; 
	public String interfaceName; 
	public String interfaceFile;	
}


public class CoreBackend {
	public HashMap <SCAIEVNode,HashMap<PipelineStage, NodePropBackend>> nodes = new HashMap <SCAIEVNode, HashMap<PipelineStage, NodePropBackend>>(); // <NodeName,Module>
	public HashMap <String, Module> fileHierarchy = new HashMap <String, Module>(); // <moduleName,Module>
	public String topModule;
	protected Core core = null;
	protected BNode  BNode= new BNode();
	
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
	public void Prepare (HashMap <String,SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<PipelineStage,HashSet<String>>> op_stage_instr, Core core, SCALBackendAPI scalAPI, BNode user_BNode) {
		this.BNode = user_BNode;
		this.core = core;
	}

	/**
	 * Integrate all changes into the core's source code, and write the modified HDL files.
	 * @param ISAXes ISAX descriptors by name of ISAX
	 * @param op_stage_instr Set of ISAXes by stage by node
	 * @param extension_name Arbitrary name for the overall integration given by the user
	 * @param core General core constraints descriptor
	 * @param out_path Output path to write the modified HDL files to.
	 */
	public boolean Generate (HashMap <String,SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<PipelineStage,HashSet<String>>> op_stage_instr, String extension_name, Core core, String out_path) {
		return false;
	}
	
	
	public void PopulateNodesMap() {
		for(SCAIEVNode node:  BNode.GetAllBackNodes()) {
			HashMap<PipelineStage, NodePropBackend> newStageNode = new HashMap<PipelineStage, NodePropBackend>();
			core.GetRootStage().getAllChildren().forEach(stage -> {
				NodePropBackend prop = new NodePropBackend();
				newStageNode.put(stage, prop);
			});
			nodes.put(node, newStageNode);
		}
		
		// Default nodes for spawn
		core.GetRootStage().getAllChildren().filter(stage -> stage.getKind() == StageKind.Decoupled).forEach(decoupledStage -> {
			this.PutNode("","","",BNode.ISAX_spawnStall_regF_s,decoupledStage);
			this.PutNode("","","",BNode.ISAX_spawnStall_mem_s,decoupledStage);
		});
	}
	
	public boolean IsNodeInStage(SCAIEVNode node, PipelineStage stage) {
		if(nodes.containsKey(node) && nodes.get(node).containsKey(stage) && NodeDataT(node, stage) != null)
			return true; 
		else 
			return false; 
	}
	public int NodeSize(SCAIEVNode node, PipelineStage stage) {
		return node.size;
	}	
	public boolean NodeIsInput(SCAIEVNode node,PipelineStage stage) {
		return node.isInput;
	}
	private Optional<NodePropBackend> NodeProp(SCAIEVNode node,PipelineStage stage) {
		return Optional.ofNullable(nodes.get(node)).flatMap(a -> Optional.ofNullable(a.get(stage)));
	}
	public String NodeDataT(SCAIEVNode node,PipelineStage stage) {
		return NodeProp(node, stage).map(entry -> entry.data_type).orElse(null);
	}
	public String NodeAssign(SCAIEVNode node,PipelineStage stage) {
		return NodeProp(node, stage).map(entry -> entry.assignSignal).orElse(null);
	}	
	/**
	 * 
	 * @param node
	 * @param stage
	 * @return
	 */
	public String NodeAssignM(SCAIEVNode node,PipelineStage stage) {
		return NodeProp(node, stage).map(entry -> entry.assignModule).orElse(null);
	}
	public String NodeName(SCAIEVNode node,PipelineStage stage) {
		return NodeProp(node, stage).map(entry -> entry.name).orElse(null);
	}
	public PipelineStage NodeStage(SCAIEVNode node,PipelineStage stage) {
		return NodeProp(node, stage).map(entry -> entry.stage).orElse(null);
	}
	public String ModName(String module) {
		return Optional.ofNullable(fileHierarchy.get(module)).map(entry -> entry.name).orElse(null);
	}
	public String ModParentName(String module) {
		return Optional.ofNullable(fileHierarchy.get(module)).map(entry -> entry.parentName).orElse(null);
	}
	public String ModFile(String module) {
		return Optional.ofNullable(fileHierarchy.get(module)).map(entry -> entry.file).orElse(null);
	}
	public String ModInterfName(String module) {
		return Optional.ofNullable(fileHierarchy.get(module)).map(entry -> entry.interfaceName).orElse(null);
	}
	public String ModInterfFile(String module) {
		return Optional.ofNullable(fileHierarchy.get(module)).map(entry -> entry.interfaceFile).orElse(null);
	}
	
	/**
	 * Add strategies to SCAL generation.
	 * Run before all other strategies.
	 */
	public List<MultiNodeStrategy> getAdditionalSCALPreStrategies() {
		return new ArrayList<MultiNodeStrategy>();
	}
	/**
	 * Add strategies to SCAL generation.
	 * Run after all other strategies.
	 */
	public List<MultiNodeStrategy> getAdditionalSCALPostStrategies() {
		return new ArrayList<MultiNodeStrategy>();
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
	public void PutNode(int size,  boolean input, String data_type, String assignSignal, String assignModule, String addNode, PipelineStage stage) {
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
			nodes.put(addNew, new HashMap<PipelineStage, NodePropBackend>());
			nodes.get(addNew).put(stage, node);	
		}
	}
	
	
	
	public void PutNode(String data_type, String assignSignal, String assignModule, SCAIEVNode addNode, PipelineStage stage) {
		NodePropBackend node = new NodePropBackend();
		node.data_type = data_type;
		node.assignSignal = assignSignal;
		node.assignModule = assignModule;
		node.name = addNode.name;
		node.stage = stage;

		HashMap<PipelineStage, NodePropBackend> stageMap = nodes.get(addNode);
		if (stageMap == null) {
			stageMap = new HashMap<>();
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
