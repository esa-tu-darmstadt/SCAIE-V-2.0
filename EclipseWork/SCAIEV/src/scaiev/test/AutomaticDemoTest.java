package scaiev.test;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;


import org.yaml.snakeyaml.Yaml;

import scaiev.SCAIEV;
import scaiev.backend.BNode;
import scaiev.frontend.FNode;
import scaiev.frontend.FrontendNodeException;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;



public class AutomaticDemoTest {
	public static HashMap<String, Integer> earliest_operation = new HashMap<String, Integer>();
	
	
	public static void main(String[] args) {
		//////////   SETTINGS   //////////
		String testFilePath = "TestMe.yaml";
		String core = "VexRiscv_5s";
		//////////   GENERATE   //////////
		SCAIEV shim = new SCAIEV();
		if(readRequest(testFilePath, core, shim)) {
			shim.DefNewNodes(earliest_operation);
			try {
				//shim.Generate("RandomCore");
				shim.Generate(core, null);
			} catch (FrontendNodeException e) {
				e.printStackTrace();
			}
		}

	}
	
	
	private static boolean readRequest (String testFilePath,String core , SCAIEV shim ) {
		File testFile = new File(testFilePath);		
		Yaml yamlSched = new Yaml();
		InputStream readFile;
		FNode FNode = new FNode();
		BNode BNode = new BNode();
		boolean addedUserNode = false;
		try {
			readFile = new FileInputStream(testFile);
			List<LinkedHashMap> readData = yamlSched.load(readFile); 
			for(LinkedHashMap readInstr : readData) {
				String instrName = "", mask = "", f3 = "---", f7 = "-------", op = "-------";
				String usernodeName ="NOTDEFINED"; 
				boolean is_always = false;
				int usernodeSize = 0, usernodeElements = 0;
				for(Object setting: readInstr.keySet()) {
					SCAIEVInstr setaddr;
					
					if(setting.toString().equals("instruction")) {
						instrName = (String) readInstr.get(setting);
						is_always = false;
					}
					if(setting.toString().equals("always")) {
						instrName = (String) readInstr.get(setting);
						is_always = true;
					}
					if(setting.toString().equals("mask")) {
						mask = (String) readInstr.get(setting);
						char[] encoding = mask.toCharArray();
						f7 = ""; f3 = ""; op = "";
						for (int i=0;i<7;i++)
							f7 += encoding[i]; 
						for (int i=17;i<20;i++)
							f3 += encoding[i]; 
						for (int i=25;i<32;i++)
							op += encoding[i]; 					
					}
				
					if(setting.toString().equals("register"))  {
						addedUserNode = true;
						usernodeName = (String) readInstr.get(setting);		
					}
					
					if(setting.toString().equals("width"))  {
						usernodeSize =  (int) readInstr.get(setting);					
					}
					if(setting.toString().equals("elements"))  {
						usernodeElements =  (int) readInstr.get(setting);					
					}
						
					if(setting.toString().equals("schedule")) {
						if(instrName.isEmpty() || f7.isEmpty())
							System.out.println("ERROR. Please first give instr name and encoding in yaml file");
						System.out.println("INFO. Just added instr. "+instrName+" with f7 "+f7+" f3 "+f3+" op "+op);
						SCAIEVInstr newSCAIEVInstr  = shim.addInstr(instrName,f7,f3,op,is_always?"<always>":"R"); // TODO R is here by default
						List<LinkedHashMap> nodes =  (List<LinkedHashMap>) readInstr.get(setting);
						for(LinkedHashMap readNode : nodes) {
							String nodeName = "";
							int nodeStage = 0;
							ArrayList<Integer> additionalStages = new ArrayList<Integer>();
						    boolean addEarliest = false;
						    boolean decoupled = false;
						    boolean dynamic_decoupled = false;
						    HashSet<AdjacentNode> adjSignals = new HashSet<AdjacentNode> () ;
							for(Object nodeSetting: readNode.keySet()) {								
								if(nodeSetting.toString().equals("interface")) {
									String newName = (String) readNode.get(nodeSetting);
									if(newName.contains(".")) {
										String[] splitted = newName.split("\\.");
										nodeName = splitted[0];
									
									} else 
										nodeName = newName;
									if(newName.contains(".") && newName.contains("addr")) { // always block  has wrong addr stage, so ignore it
										addEarliest = true;
									}
								}
								if(nodeSetting.toString().equals("stage")) {
									if(addEarliest) {
										if(!op.contentEquals("-------")) {
											if(earliest_operation.containsKey(nodeName)) { // If there was another entry with an ""earliest"" higher, overwrite it, otherwise, don't
												int readEarliest = earliest_operation.get(nodeName);
												if(readEarliest>  (int) readNode.get(nodeSetting))
													earliest_operation.put(nodeName, (int) readNode.get(nodeSetting));
											} else
												earliest_operation.put(nodeName, (int) readNode.get(nodeSetting));
										}
									} else {
										nodeStage = (int) readNode.get(nodeSetting);
									}
									if(is_always) // For read/writes without opcode: now set as spawn without decoding; in scaiev mapped on Read stage /WB stage; are direct read/writes
										if( FNode.GetSCAIEVNode(nodeName)==null | (FNode.GetSCAIEVNode(nodeName)!=null && FNode.GetSCAIEVNode(nodeName).DH))
											nodeStage = 10000; // immediate write
								}
								
								// Multiple stages only supported for read nodes
								if(nodeSetting.toString().equals("stages")) {									
									//nodeStage = (int) readNode.get(nodeSetting);
									additionalStages = (ArrayList<Integer>) readNode.get(nodeSetting);	
									nodeStage = additionalStages.get(0);
									if(is_always)
										System.out.println("CRITICAL WARNING. No multiple stages supported for always. Node: "+readNode);
								}
								
								if(nodeSetting.toString().equals("has valid")) 
									adjSignals.add(AdjacentNode.validReq);
								if(nodeSetting.toString().equals("has validResp")) 
									adjSignals.add(AdjacentNode.validResp);
								if(nodeSetting.toString().equals("has addr"))
									adjSignals.add(AdjacentNode.addr);
								if(nodeSetting.toString().equals("is decoupled"))
									decoupled = true;
								if(nodeSetting.toString().equals("is dynamic decoupled")) {
									dynamic_decoupled = true;
									decoupled = true;
									nodeStage = 10000;
									adjSignals.add(AdjacentNode.validReq);
									adjSignals.add(AdjacentNode.addr); // is a must for dynamic decoupled, to be able to associate them in case of wrrd
								}
							}
							if(!addEarliest) {
								if(FNode.IsUserFNode(FNode.GetSCAIEVNode(nodeName)) && (FNode.GetSCAIEVNode(nodeName).elements>1))  {
									adjSignals.add(AdjacentNode.addr);
									adjSignals.add(AdjacentNode.addrReq);
								}
								newSCAIEVInstr.PutSchedNode(FNode.GetSCAIEVNode(nodeName),nodeStage,adjSignals);  
								for(int i = 1; i< additionalStages.size();i++) {
									newSCAIEVInstr.PutSchedNode(FNode.GetSCAIEVNode(nodeName),i);
								}
								newSCAIEVInstr.SetAsDecoupled(decoupled);
								System.out.println("INFO. Just added for instr. "+instrName+" nodeName "+nodeName+" nodeStage = "+nodeStage+" hasValid "+ adjSignals.contains(AdjacentNode.validReq) +" hasAddr "+ adjSignals.contains(AdjacentNode.addr)+ " hasValidResp "+ adjSignals.contains(AdjacentNode.validResp));
							}
						}
					}				
				}
				if(!usernodeName.equals("NOTDEFINED")) {
					FNode.AddUserFNode(usernodeName,usernodeSize,usernodeElements);
					BNode.AddUserBNode(usernodeName,usernodeSize,usernodeElements);
				}
			}
			shim.FNodes = FNode; 
			shim.BNodes = BNode; 
			
			if(addedUserNode)
				System.out.println("INFO. User added new nodes. Now the FNode becomes: "+shim.FNodes.GetAllFrontendNodes());
			return true;
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
			return false;
		}	
	}
}
