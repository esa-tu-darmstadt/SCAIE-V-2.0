package scaiev.coreconstr;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.TypeDescription;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import scaiev.backend.BNode;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.NodeTypeTag;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.ScheduleFront;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.pipeline.PipelineStage.StageTag;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class CoreDatab {
	// logging
	protected static final Logger logger = LogManager.getLogger();

	Boolean debug = true;
	HashMap <String, Core> cores = new HashMap<String, Core>();
	BNode BNode = new BNode(); 
	
	public CoreDatab() {
		//System.out.println("To constrain HLS:\n"
		//		+ "- write constrain file \n"
		//		+ "- import it using ReadAvailCores\n"
		//		+ "OR\n"
		//		+ "- use AddCore() to add it manually in java \n");
	}
	
	public void SetDebug(boolean debug) {
		this.debug = debug;
	}

	@SuppressWarnings("serial")
	protected static class SerialCoreDescription implements Serializable
	{
		public static class PipelineStageDesc implements Serializable
		{
			/** Unique pipeline stage name. Stage names starting with 'decoupled' will be used for decoupled pipeline instantiation. */
			String name = "";
			/** 
			 * Stage ID for ISAX import
			 * Since Java Optionals aren't serializable, we resort to the magic value -1 to represent 'Optional.empty()'
			 */
			int externalID = -1;
			/** Names of next neighbors */
			List<String> nextStages = new ArrayList<>();
			/**
			 * Is signal pipelining from the previous to this stage reproducible with low costs
			 * (e.g. would require a simple pipeline register, and not a reorder buffer)
			 */
			boolean continuous = false;
			/**
			 * List of all sub-stages to this stage.
			 * The first child is the start of the (only) sub-pipeline.
			 */
			List<PipelineStageDesc> children = new ArrayList<>();
			/** Kind of this stage (PipelineStage.StageKind string representation) */
			String kind = "";
			/** Tags for this stage (PipelineStage.StageTag string representation) */
			List<String> tags = new ArrayList<>();

			public String getName() {
				return name;
			}
			public void setName(String name) {
				this.name = name;
			}
			public int getExternalID() {
				return externalID;
			}
			public void setExternalID(int externalID) {
				this.externalID = externalID;
			}
			public List<String> getNextStages() {
				return nextStages;
			}
			public void setNextStages(List<String> nextStages) {
				this.nextStages = nextStages;
			}
			public boolean isContinuous() {
				return continuous;
			}
			public void setContinuous(boolean continuous) {
				this.continuous = continuous;
			}
			public List<PipelineStageDesc> getChildren() {
				return children;
			}
			public void setChildren(List<PipelineStageDesc> children) {
				this.children = children;
			}
			public String getKind() {
				return kind;
			}
			public void setKind(String kind) {
				this.kind = kind;
			}
			public List<String> getTags() {
				return tags;
			}
			public void setTags(List<String> tags) {
				this.tags = tags;
			}
			
			/** 
			 * Converts this stage and its children to a PipelineStage, ignoring this.nextStages.
			 */
			protected Optional<PipelineStage> asPipeline_thisAndChildren(boolean isRoot) {
				var kindVal_opt = Stream.of(StageKind.values()).filter(kindVal -> kindVal.serialName.equals(kind)).findAny();
				if (kindVal_opt.isEmpty()) {
					logger.error("CoreDatab. Enountered invalid stage kind '{}'.", kind);
					return Optional.empty();
				}
				StageKind kindVal = kindVal_opt.get();
				if ((kindVal == StageKind.Root) != isRoot) {
					logger.error("CoreDatab. Only and all root stages should have stage kind {}.", StageKind.Root.serialName);
					return Optional.empty();
				}
				if (kindVal == StageKind.Sub) {
					logger.error("CoreDatab. Encountered a Sub stage, which is not supported for use in the core datasheet.");
					return Optional.empty();
				}
				
				EnumSet<StageTag> tagsVal = EnumSet.noneOf(StageTag.class);
				for (String tagName : tags) {
					var tagVal_opt = Stream.of(StageTag.values()).filter(tagVal -> tagVal.serialName.equals(tagName)).findAny();
					if (tagVal_opt.isEmpty()) {
						logger.error("CoreDatab. Enountered invalid stage tag '{}'.", tagName);
						return Optional.empty();
					}
					tagsVal.add(tagVal_opt.get());
				}
				
				PipelineStage ret = new PipelineStage(kindVal, tagsVal, name, 
						externalID==-1?Optional.empty():Optional.of(externalID),
						continuous);
				if (!this.children.isEmpty()) {
					if (kindVal != StageKind.Root) {
						//Sub pipelines are intended to be created by SCAIE-V itself.
						// There is no fundamental reason why this shouldn't work, but at the time of writing this, 
						// there also is no reason to support this, given that no core nodes can be accessed directly from a Sub node.
						// Thus, this (currently) seems more likely to be an accident in the yaml file.
						// (If the need arises in the future, this code would support it, as long as the child StageKind is set to StageKind.Sub).
						logger.error("CoreDatab. Encountered a sub-pipeline below stage '{}', which is not supported as part of a core currently.", name);
						return Optional.empty();
					}
					PipelineStage directChild = null;
					ArrayList<PipelineStage> childrenConverted = new ArrayList<>(this.children.size());
					HashMap<String,PipelineStage> childByName = new HashMap<>();
					HashSet<String> unusedChildren = new HashSet<>();
					for (int i = 0; i < this.children.size(); ++i) {
						//Convert all children, without linking their next/prev points for now.
						PipelineStageDesc curChildDesc = this.children.get(i);
						Optional<PipelineStage> curChild_opt = curChildDesc.asPipeline_thisAndChildren(false);
						if (curChild_opt.isEmpty()) //Errors in child 
							return Optional.empty();
						var curChild = curChild_opt.get();
						childrenConverted.add(curChild);
						if (i == 0)
							directChild = curChild;
						else
							unusedChildren.add(curChildDesc.name); //Keep track of unused children to detect possible user errors in yaml file creation.
						
						if (childByName.containsKey(curChildDesc.name)) {
							logger.error("CoreDatab. Encountered duplicate stage name '{}'.", curChildDesc.name);
							return Optional.empty();
						}
						childByName.put(curChildDesc.name, curChild);
						if (curChildDesc.nextStages.stream().anyMatch(succName -> childByName.containsKey(succName))) {
							//Prevents cycles (strict but simple condition).
							//Also forces the yaml to list the stages from leftmost (start) to rightmost (tail).
							logger.error("CoreDatab. Successor of stage '{}' must be defined after '{}' itself.", curChildDesc.name, curChildDesc.name);
							return Optional.empty();
						}
					}
					assert(directChild != null);
					ret.addChild(directChild);

					for (int i = 0; i < this.children.size(); ++i) {
						//Connect the PipelineStage graph via next and prev.
						PipelineStageDesc curChildDesc = this.children.get(i);
						PipelineStage curChild = childrenConverted.get(i);
						for (String nextStageName : curChildDesc.nextStages) {
							PipelineStage successor = childByName.get(nextStageName);
							if (successor == null) {
								logger.error("CoreDatab. Encountered undefined stage name '{}' as successor of '{}'.", nextStageName, curChildDesc.name);
								return Optional.empty();
							}
							curChild.addNext(successor);
							unusedChildren.remove(nextStageName);
						}
					}
					
					if (!unusedChildren.isEmpty()) {
						String unusedStagesText = unusedChildren.stream().map(a -> "'"+a+"'").reduce((a,b) -> a+", "+b).get();
						logger.error("CoreDatab. Encountered unused stages {}.", unusedStagesText);
						return Optional.empty();
					}
				}
				return Optional.of(ret);
			}
		}
		public static class OperationDesc implements Serializable
		{
			String operation = "";
			int earliest = 0;
			int latest = -1;
			int latency = 0;
			int costly = 0;
			
			public OperationDesc() {}
			public OperationDesc(String operation, int earliest, int latest, int latency, int costly) {
				this.operation = operation;
				this.earliest = earliest;
				this.latest = latest;
				this.latency = latency;
				this.costly = costly;
			}
			public String getOperation() {
				return operation;
			}
			public void setOperation(String operation) {
				this.operation = operation;
			}
			public int getEarliest() {
				return earliest;
			}
			public void setEarliest(int earliest) {
				this.earliest = earliest;
			}
			public int getLatest() {
				return latest;
			}
			public void setLatest(int latest) {
				this.latest = latest;
			}
			public int getLatency() {
				return latency;
			}
			public void setLatency(int latency) {
				this.latency = latency;
			}
			public int getCostly() {
				return costly;
			}
			public void setCostly(int costly) {
				this.costly = costly;
			}
		}
		PipelineStageDesc pipeline = new PipelineStageDesc();
		List<OperationDesc> operations = new ArrayList<>();
		public SerialCoreDescription() {
			pipeline.name = "root";
			pipeline.kind = "root";
		}
		public PipelineStageDesc getPipeline() {
			return pipeline;
		}
		public void setPipeline(PipelineStageDesc pipeline) {
			this.pipeline = pipeline;
		}
		/** Converts the serializable pipeline description to a root PipelineStage */
		public Optional<PipelineStage> asPipeline() {
			return pipeline.asPipeline_thisAndChildren(true);			
		}
		public List<OperationDesc> getOperations() {
			return operations;
		}
		public void setOperations(List<OperationDesc> operations) {
			this.operations = operations;
		}
	}
	
	/** Translate legacy operation names to match BNode. */
	private String translateLegacyOperationName(String nameIn) {
		switch (nameIn) {
		case "RdCustReg":
			return "RdCustReg.data_constraint";
		case "WrCustReg.addr":
			return "CustReg.addr_constraint";
		case "WrCustReg.data":
			return "WrCustReg.data_constraint";
		default:
			return nameIn;
		}
	}

	//private PipelineStage parsePipeline()
	
	public void ReadAvailCores (String path) {
		try {
			File dir = new File(path);
			File[] directoryListing = dir.listFiles();
			if (directoryListing != null) {
				for (File coreFile : directoryListing) {
					if (!coreFile.getName().endsWith(".yaml"))
						continue;
					Constructor yamlConstructor = new Constructor(new LoaderOptions());
					yamlConstructor.addTypeDescription(new TypeDescription(SerialCoreDescription.class, "!SerialCoreDescription"));
					Yaml yamlCore = new Yaml(yamlConstructor);
					InputStream readFile = new FileInputStream(coreFile);
					Object parseResult = yamlCore.load(readFile);
					//yamlCore.loadAs(readFile, SerialCoreDescription.class);
					try {
						readFile.close();
					} catch (IOException e) {} 
					SerialCoreDescription coreDescr = null;
					if (parseResult instanceof SerialCoreDescription) {
						coreDescr = (SerialCoreDescription)parseResult;
						if (coreDescr.pipeline.nextStages.size() > 0 || coreDescr.pipeline.externalID != -1) {
							logger.error("CoreDatab. Invalid root pipeline node in {}", coreFile.getName());
							continue;
						}
						
					}
					else if (parseResult instanceof List && ((List)parseResult).stream().allMatch(entry -> entry instanceof LinkedHashMap)) {
						coreDescr = new SerialCoreDescription();
						for (Object operationObj : (List)parseResult) 
						{
							
							@SuppressWarnings("rawtypes")
							LinkedHashMap operation = (LinkedHashMap)operationObj;
							@SuppressWarnings("unchecked")
							String name = (String)operation.getOrDefault("operation", "");
							if (name.contains("CustReg"))
								continue; //Expression parsing can of worms not supported yet, need to construct a can opener first.
							int earliest = Optional.ofNullable(operation.get("earliest")).map(strval -> (Integer)strval).orElse(0);
							int latency = Optional.ofNullable(operation.get("latency")).map(strval -> (Integer)strval).orElse(0);
							int latest = Optional.ofNullable(operation.get("latest")).map(strval -> (Integer)strval).orElse(-1);
							int costly = Optional.ofNullable(operation.get("costly")).map(strval -> (Integer)strval).orElse(0);
							if (!name.contains("CustReg"))
								coreDescr.operations.add(new SerialCoreDescription.OperationDesc(name, earliest, latest, latency, costly));
						}
					}
					if (coreDescr == null) {
						logger.error("CoreDatab. Unable to parse core description yaml file {}", coreFile.getName());
						continue;
					}

					for (SerialCoreDescription.OperationDesc opDesc : coreDescr.operations) {
						//Fix legacy operation names.
						opDesc.operation = translateLegacyOperationName(opDesc.operation);
					}
					
					var unsupportedNodeOps = coreDescr.operations.stream()
							.filter(opDesc -> !BNode.HasSCAIEVNode(opDesc.operation)
									|| (!BNode.HasSCAIEVFNode(opDesc.operation) && !BNode.GetSCAIEVNode(opDesc.operation).tags.contains(NodeTypeTag.defaultNotprovidedByCore)
											 && !BNode.GetSCAIEVNode(opDesc.operation).tags.contains(NodeTypeTag.constraintMarkerOnlyNode)))
							.map(opDesc -> opDesc.operation)
							.collect(Collectors.toList());
					if (unsupportedNodeOps.size() > 0) {
						logger.error("CoreDatab. Nodes {} not supported while processing {}. Supported nodes are Node_* : {}",
								unsupportedNodeOps.stream().reduce((a,b) -> a+", "+b).get(),
								coreFile.getName(),
								Stream.concat(BNode.GetAllFrontendNodes().stream(), BNode.GetAllBackNodes().stream().filter(op ->
										op.tags.contains(NodeTypeTag.defaultNotprovidedByCore)
										|| op.tags.contains(NodeTypeTag.constraintMarkerOnlyNode))
									).distinct().toList().toString());
						continue;
					}
					int maxStage = 0;


					String coreName = coreFile.getName().split("\\.")[0];

					// System.out.println("INFO: Reading core: "+coreName);
					HashMap<SCAIEVNode, CoreNode> nodes_of1_core = new HashMap<SCAIEVNode, CoreNode>();

					for (SerialCoreDescription.OperationDesc opDesc : coreDescr.operations) {
						SCAIEVNode addNode = BNode.GetSCAIEVNode(opDesc.operation);
						if (addNode.equals(BNode.WrMem) || addNode.equals(BNode.RdMem)) {
							//TODO: Is this necessary? Could it be moved to an error message during RdMem/WrMem implementation?
							if (opDesc.earliest >= 0)
								opDesc.latest = opDesc.earliest;
						}
						CoreNode newNode = new CoreNode(opDesc.earliest, opDesc.latency, opDesc.latest, opDesc.costly, opDesc.operation);
						if (newNode.GetLatest().asInt() > maxStage)
							maxStage = newNode.GetLatest().asInt();
						nodes_of1_core.put(addNode, newNode);
					}
					// Make sure no node has latest = -1
					for (SCAIEVNode fnode : nodes_of1_core.keySet()) {
						if (nodes_of1_core.get(fnode).GetLatest().asInt() == -1) {
							nodes_of1_core.get(fnode).OverrideLatest(new ScheduleFront(maxStage));
						}
					}
					// System.out.println("INFO: Core, with max nr stages/states: "+maxStage+" and
					// SCAIE-V operations: "+nodes_of1_core);
					
					// Convert the parsed pipeline description into a PipelineStage graph.
					Optional<PipelineStage> rootStage_opt = coreDescr.asPipeline();
					if (rootStage_opt.isEmpty()) {
						logger.error("CoreDatab. Unable to convert core pipeline from yaml file {}", coreFile.getName());
						continue;
					}
					PipelineStage rootStage = rootStage_opt.get();
					if (coreDescr.pipeline.children.isEmpty()) {
						assert(rootStage.getChildren().isEmpty());
						//If no explicit pipeline is given, construct a simple one from the known stage number range. 
						PipelineStage corePipelineFront = PipelineStage.constructLinearContinuous(StageKind.Core, maxStage + 1, Optional.of(0));
						rootStage.addChild(corePipelineFront);
						int lastRdFlushStage = Optional.ofNullable(nodes_of1_core.get(BNode.RdFlush)).map(node -> node.GetLatest().asInt()).orElse(-1);
						if (lastRdFlushStage == -1)
							lastRdFlushStage = maxStage;
						rootStage.getChildrenByStagePos(lastRdFlushStage).forEach(commitStage -> commitStage.addTag(StageTag.Commit));
						
						//Add the special decoupled stage to the end of the pipeline.
						PipelineStage decoupledStage = new PipelineStage(
							StageKind.Decoupled, EnumSet.of(StageTag.InOrder), "decoupled",
							Optional.of(maxStage + 1), true
						);
						rootStage.getChildrenTails().forEach(tailStage -> {
							assert(tailStage.getKind() == StageKind.Core);
							tailStage.addNext(decoupledStage);
						});
						assert(decoupledStage.getPrev().size() == 1);
					}
					else {
						if (!rootStage.getChildren().stream().allMatch(firstStage -> firstStage.streamNext_bfs().anyMatch(stage -> stage.getKind() == StageKind.Decoupled))) {
							logger.error("CoreDatab. Could not find decoupled stage in core pipeline from yaml file {}", coreFile.getName());
							continue;
						}
					}
					assert(rootStage.getChildren().size() == 1);
					
					Core newCore = new Core(coreName, rootStage);
					newCore.PutNodes(nodes_of1_core);
					newCore.maxStage = maxStage;
					cores.put(coreName, newCore);
				}
			} else {
				logger.fatal("Directory not found.");
				System.exit(1);
			}

		} catch (FileNotFoundException e) {
			logger.fatal("FileNotFoundException when reading core constraints from file.");
			e.printStackTrace();
		}
	}
	
			
	// Get constraints of a certain Core
	public Core GetCore (String name) {
		return cores.get(name);
	}
	
	
	public Set<String> GetCoreNames () {
		return cores.keySet();
	}
	
}
