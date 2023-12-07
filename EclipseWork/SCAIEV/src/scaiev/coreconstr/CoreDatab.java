package scaiev.coreconstr;


import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

import org.yaml.snakeyaml.Yaml;

import scaiev.frontend.FNode;
import scaiev.frontend.SCAIEVNode;



public class CoreDatab {
	Boolean debug = true;
	HashMap <String, Core> cores = new HashMap<String, Core>();
	FNode FNode = new FNode(); 
	
	public CoreDatab() {
		System.out.println("To constrain HLS:\n"
				+ "- write constrain file \n"
				+ "- import it using ReadAvailCores\n"
				+ "OR\n"
				+ "- use AddCore() to add it manually in java \n");
	}
	
	public void SetDebug(boolean debug) {
		this.debug = debug;
	}
	
	public void ReadAvailCores (String path) {
		 try {
			 	File dir = new File(path);
			 	File[] directoryListing = dir.listFiles();
			 	if (directoryListing != null) {
			 		for (File coreFile : directoryListing) { if (!coreFile.getName().endsWith(".yaml")) continue;		 			
			 			Yaml yamlCore = new Yaml();
			 			InputStream readFile =  new FileInputStream(coreFile);	
			 			List<LinkedHashMap> readData = yamlCore.load(readFile);  		
			 			int maxStage = 0;
			 			
			 			Core newCore = new Core();				      
			 			
			 			String coreName = coreFile.getName().split("\\.")[0];
			 			newCore.PutName(coreName);
			 			System.out.println("INFO: Reading core: "+coreName);
			 			HashMap<SCAIEVNode,CoreNode> nodes_of1_core = new HashMap<SCAIEVNode,CoreNode>();
			 			
						for(LinkedHashMap readNode : readData) {
							int earliest = 0,latency = 0,latest = 0,costly = 0;
							String name = "";
							for(Object setting: readNode.keySet()) {
								if(setting.toString().equals("operation")) {
									name = (String) readNode.get(setting);
									if(name.contains("CustReg"))
										break;
								}
								if(setting.toString().equals("earliest"))
									earliest = (int) readNode.get(setting);
								if(setting.toString().equals("latest"))
									latest =  (int) readNode.get(setting);
								if(setting.toString().equals("latency"))
									latency = (int) readNode.get(setting);
								if(setting.toString().equals("costly"))
									costly =  (int) readNode.get(setting);
									
							}
							
							if(name.contains("CustReg"))
								continue;
							
							
							if(!FNode.HasSCAIEVNode(name) && !name.contains("CustReg")) {
				        		System.out.println("ERROR. CoreDatab. Node named: "+name+" not supported. Supported nodes are Node_* : "+FNode.GetAllFrontendNodes().toString());
							 	System.exit(1);
				        	}
							SCAIEVNode addFNode = FNode.GetSCAIEVNode(name);
							if(addFNode.equals(FNode.WrMem) || addFNode.equals(FNode.RdMem))
								latest = earliest;
							CoreNode newNode = new CoreNode(earliest, latency, latest, costly, name);
							if(newNode.GetLatest()>maxStage)
								maxStage = newNode.GetLatest();
							nodes_of1_core.put(addFNode, newNode);
						}
						// Make sure no node has latest = 0
						for(SCAIEVNode fnode: nodes_of1_core.keySet())
							if(nodes_of1_core.get(fnode).GetLatest()==0)
								nodes_of1_core.get(fnode).latestTime = maxStage;
						System.out.println("INFO: Core, with max nr stages/states: "+maxStage+" and SCAIE-V operations: "+nodes_of1_core);
 
						newCore.PutNodes(nodes_of1_core);
						newCore.maxStage = maxStage;
						cores.put(coreName,newCore);	    	  	
			 		}
			 	} else {
			 		System.out.println("CONSTRAIN. ERROR. Directory not found.");
			 		System.exit(1);
			 	}
		      
		    } catch (FileNotFoundException e) {
		      System.out.println("ERROR. CoreDatab. FileNotFoundException when reading core constraints from file.");
		      e.printStackTrace();
		    }
	}
	
			
	// Get constraints of a certain Core
	public Core GetCore (String name) {
		return cores.get(name);
	}
	
	
	public String  GetCoreNames () {
		String coreNames = "SCAIE-V supported cores are: \n";
		for(String name : cores.keySet())
			coreNames += "- "+name+"\n";
		return coreNames;
	}
	
}
