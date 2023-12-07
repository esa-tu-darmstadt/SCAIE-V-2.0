package scaiev.backend;

import java.util.HashMap;
import java.util.HashSet;

import scaiev.coreconstr.Core;
import scaiev.frontend.FNode;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;
import scaiev.util.FileWriter;
import scaiev.util.GenerateText.DictWords;
import scaiev.util.Lang;
import scaiev.util.SpinalHDL;
import scaiev.util.ToWrite;

// c524335

public class VexRiscv extends CoreBackend{
	public  String 			pathCore = "CoresSrc/VexRiscv";
	public String getCorePathIn() {
		return pathCore;
	}
	public  String 			pathRISCV = "src/main/scala/vexriscv";
	public  String 			baseConfig = "VexRiscvAhbLite3";
	private Core 			vex_core;
	private HashMap <String,SCAIEVInstr>  ISAXes;
	private HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr;
	private FileWriter 		toFile = new FileWriter(pathCore);
	private String 			filePlugin ;
	private String 			extension_name;
	private SpinalHDL       language = new SpinalHDL(toFile,this);
	private HashMap<SCAIEVNode, Integer> spawnStages = new HashMap<SCAIEVNode, Integer>();
	
	
	public FNode FNode = new FNode();
	public BNode BNode = new BNode();
	
	private int nrTabs = 0;
	
	public VexRiscv(Core vex_core) {
		this.vex_core = vex_core;
	}
	

	public boolean Generate (HashMap <String,SCAIEVInstr> ISAXes, HashMap<SCAIEVNode, HashMap<Integer,HashSet<String>>> op_stage_instr, String extension_name, Core core,  String out_path) { // core needed for verification purposes
		// Set variables
		this.vex_core = vex_core;
		this.ISAXes = ISAXes;
		this.op_stage_instr = op_stage_instr;
		this.extension_name = extension_name;
		
		
		ConfigVex();
		this.filePlugin = fileHierarchy.get(extension_name).file;
		language.currentFile = this.filePlugin;
		language.withinCore = true;
		IntegrateISAX_DefImports();
		IntegrateISAX_DefObjValidISAX(); 
		IntegrateISAX_OpenClass();
		IntegrateISAX_Services();
		IntegrateISAX_OpenSetupPhase();
		for(String ISAX : ISAXes.keySet()) 
			if(!ISAX.contains(SCAIEVInstr.noEncodingInstr))
			IntegrateISAX_SetupDecode(ISAXes.get(ISAX));
		IntegrateISAX_SetupServices();
		IntegrateISAX_CloseSetupPhase();
		IntegrateISAX_OpenBuild();
		for(int i = 0;i<=this.vex_core.maxStage;i++) 
			IntegrateISAX_Build(i);
		language.CloseBrackets(); // close build section
		language.CloseBrackets(); // close class
		
		
		// Configs
		IntegrateISAX_UpdateConfigFile(); // If everything went well, also update config file
		IntegrateISAX_UpdateArbitrationFile(); // in case of WrRD spawn, update arbitration
		IntegrateISAX_UpdateRegFforSpawn(); // in case of WrRD spawn, update arbitration
		IntegrateISAX_UpdateIFetchFile();
		IntegrateISAX_UpdateDHFile();
		IntegrateISAX_UpdateServiceFile();
		IntegrateISAX_UpdateMemFile();
		
		
		// Write all files 
		toFile.WriteFiles(language.GetDictModule(),language.GetDictEndModule(), out_path);
		return true;
	}
	
	// Infos for SCAL
	public HashMap<SCAIEVNode, Integer> PrepareEarliest() {
		HashMap<SCAIEVNode, Integer> node_stageValid = new HashMap<SCAIEVNode, Integer>();
		int stage_mem_valid =  this.vex_core.GetNodes().get(BNode.RdInstr).GetEarliest();
		node_stageValid.put(BNode.WrMem, stage_mem_valid);
		node_stageValid.put(BNode.RdMem, stage_mem_valid);
		return node_stageValid;		
	};
	 // #################################### IMPORT PHASE ####################################
	 private void IntegrateISAX_DefImports() { 
		 String addText = """
		 		package vexriscv.plugin
				import vexriscv._ 
				import spinal.core._
				import spinal.lib._;
				import scala.collection.mutable.ArrayBuffer
				import scala.collection.mutable
				import scala.collection.JavaConverters._
		 		""";
		 toFile.UpdateContent(filePlugin, addText);
	 }
	  
	 // Declare ISAXes as objects IValid
	 private void IntegrateISAX_DefObjValidISAX() {
		 toFile.UpdateContent(filePlugin, "object "+extension_name+" {");
		 toFile.nrTabs++;
		 String stageables = "object IS_ISAX extends Stageable(Bool)"; // no MUXing required, done by SCAL
		 toFile.UpdateContent(filePlugin, stageables);
		 language.CloseBrackets();
	 }
	 
	 // Define ISAX plugin class
	 private void IntegrateISAX_OpenClass() {
		 String defineClass = "class "+extension_name+"(writeRfInMemoryStage: Boolean = false)extends Plugin[VexRiscv] {"; 
		 toFile.UpdateContent(filePlugin,defineClass); 
		 toFile.nrTabs++;
		 toFile.UpdateContent(filePlugin,"import "+extension_name+"._");			
	 }
		 
	// Declare Required Services
	 private void IntegrateISAX_Services() {
		 if(op_stage_instr.containsKey(BNode.WrPC)) 
			 for(int i=1;i<this.vex_core.maxStage+2;i++)
				 if(op_stage_instr.get(BNode.WrPC).containsKey(i))
					 toFile.UpdateContent(filePlugin,"var jumpInterface_"+i+": Flow[UInt] = null");
		 if(op_stage_instr.containsKey(BNode.RdMem) || op_stage_instr.containsKey(BNode.WrMem) )
			 toFile.UpdateContent(filePlugin,"var dBusAccess : DBusAccess = null");
		 
		 if(ContainsOpInStage(BNode.WrPC,0) || op_stage_instr.containsKey(BNode.WrPC_spawn) || ContainsOpInStage(BNode.RdPC,0)) 
			 toFile.UpdateContent(filePlugin,"var  jumpInFetch : JumpInFetch = null");
	 }
	 
	// ######################################################################################################
	 // #############################################   SETUP    #############################################
	 // ######################################################################################################
	 private void IntegrateISAX_OpenSetupPhase() {
		 toFile.UpdateContent(filePlugin,"\n// Setup phase.");
		 toFile.UpdateContent(filePlugin,"override def setup(pipeline: VexRiscv): Unit = {");
		 toFile.UpdateContent(filePlugin,"import pipeline._");
		 toFile.nrTabs++; // should become 2
		 toFile.UpdateContent(filePlugin,"import pipeline.config._");
		 toFile.UpdateContent(filePlugin,"val decoderService = pipeline.service(classOf[DecoderService])");
		 toFile.UpdateContent(filePlugin,"decoderService.addDefault(IS_ISAX, False)\n"); 
			
	 }
	 
	 private void IntegrateISAX_SetupDecode(SCAIEVInstr isax) {
		if (isax.HasNoOp())
			return;
		String setupText  = "" ; 
		int tabs = toFile.nrTabs;
		String tab = toFile.tab;
		boolean defaultMemAddr = (isax.HasNode(BNode.RdMem) && !isax.GetFirstNode(BNode.RdMem).HasAdjSig(AdjacentNode.addr)  ) ||  (isax.HasNode(BNode.WrMem) && !isax.GetFirstNode(BNode.WrMem).HasAdjSig(AdjacentNode.addr)  ) ;
		setupText += tab.repeat(tabs)+"decoderService.add(\n";
		
		tabs++; 
		setupText += tab.repeat(tabs)+isax.GetEncodingString()+"\n";	// Set encoding			
		setupText += tab.repeat(tabs)+"List(\n";
		
		// Signal this ISAX
		tabs++; 
		setupText += tab.repeat(tabs)+"IS_ISAX                  -> True,\n"; 
		
		// 	? PC INCREMENT DESIRED?
		// OP1
		if(isax.HasNode(BNode.RdImm) && isax.GetInstrType().equals("U"))
			setupText += tab.repeat(tabs)+"SRC1_CTRL                -> Src1CtrlEnum.IMU,\n";
		else if(isax.HasNode(BNode.RdRS1) || defaultMemAddr )
			setupText += tab.repeat(tabs)+"SRC1_CTRL                -> Src1CtrlEnum.RS,\n";
		
		// OP2
		if(isax.HasNode(BNode.RdImm) || defaultMemAddr) {
			if(isax.GetInstrType().equals("I") || defaultMemAddr) {
				setupText += tab.repeat(tabs)+"SRC2_CTRL                -> Src2CtrlEnum.IMI,\n";
				setupText += tab.repeat(tabs)+"SRC_USE_SUB_LESS  	     -> False,\n"; //  EC
			}
			else if(isax.GetInstrType().equals("S"))
				setupText += tab.repeat(tabs)+"SRC2_CTRL                -> Src2CtrlEnum.IMS,\n";
		}
		else 
			setupText += tab.repeat(tabs)+"SRC2_CTRL                -> Src2CtrlEnum.RS,\n";
		
		// WRITE REGFILE
		//if(isax.HasNode(BNode.WrRD) && isax.GetNode(BNode.WrRD).GetStartCycle()<=this.vex_core.maxStage)
		boolean reqWrRD = false; // seems werid the current implementation of wrrd = true, but it is required because of spawn nodes which stall the pipeline and are implemented in execute although they requested later stage
		int WrRDcycle = this.vex_core.maxStage+1; 
		if(isax.HasNode(BNode.WrRD)) 
			WrRDcycle =  isax.GetFirstNode(BNode.WrRD).GetStartCycle();
		if(this.op_stage_instr.containsKey(BNode.WrRD)) {
			for(int stage : this.op_stage_instr.get(BNode.WrRD).keySet()) {
				if(stage<=this.vex_core.maxStage && this.op_stage_instr.get(BNode.WrRD).get(stage).contains(isax.GetName())) {
					reqWrRD = true;
					WrRDcycle = stage;
				}
			}
		}
		if(reqWrRD)
			setupText += tab.repeat(tabs)+"REGFILE_WRITE_VALID      -> True,\n";
		else 
			setupText += tab.repeat(tabs)+"REGFILE_WRITE_VALID      -> False,\n";
						
		// Read Reg file?
		String add_comma = "";
		if(isax.HasNode(BNode.WrRD))
			add_comma = tab.repeat(tabs)+",";
		if(isax.HasNode(BNode.RdRS1) || defaultMemAddr)
			setupText += tab.repeat(tabs)+"RS1_USE                  -> True,\n";
		else 
			setupText += tab.repeat(tabs)+"RS1_USE                  -> False,\n";
		if(isax.HasNode(BNode.RdRS2))
			setupText += tab.repeat(tabs)+"RS2_USE                  -> True"+add_comma+"\n";
		else 
			setupText += tab.repeat(tabs)+"RS2_USE                  -> False"+add_comma+"\n";	
		
		if(reqWrRD) {
			if(WrRDcycle==2) // spawn is not bypassable. 3 hardcoded for memory stage of the core
				setupText += tab.repeat(tabs)+"BYPASSABLE_EXECUTE_STAGE  -> True,\n";
			else 
				setupText += tab.repeat(tabs)+"BYPASSABLE_EXECUTE_STAGE  -> False,\n";
			if(WrRDcycle<=3) // spawn is not bypassable. 3 hardcoded for memory stage of the core
				setupText += tab.repeat(tabs)+"BYPASSABLE_MEMORY_STAGE  -> True\n";
			else 
				setupText += tab.repeat(tabs)+"BYPASSABLE_MEMORY_STAGE  -> False\n";
		}
		tabs--; // should become 3
		setupText += tab.repeat(tabs)+")\n";
		tabs--; //should become 2
		setupText += tab.repeat(tabs)+")\n";
		toFile.UpdateContent(filePlugin,setupText);
	}
		 
		 
	 private void IntegrateISAX_SetupServices() {
		 // toFile.nrTabs should be 2
		 String setupServices = "";
		 if(op_stage_instr.containsKey(BNode.WrPC)) {
			 boolean warning_spawn = false;
			 for(int i=1;i<=this.vex_core.maxStage;i++) { 
				 if(op_stage_instr.get(BNode.WrPC).containsKey(i)) {
					if(i==1)
						warning_spawn = true;
					setupServices += "val flushStage_"+i+" = if (memory != null) memory else execute\n";
					setupServices += "val pcManagerService_"+i+" = pipeline.service(classOf[JumpService])\n";
					setupServices += "jumpInterface_"+i+" = pcManagerService_"+i+".createJumpInterface(flushStage_"+i+")\n"; 				
				}
			 }			
		 }
		 if(op_stage_instr.containsKey(BNode.WrPC_spawn) || this.ContainsOpInStage(BNode.WrPC, 0) || this.ContainsOpInStage(BNode.RdPC, 0))  {
			 setupServices += "jumpInFetch = pipeline.service(classOf[JumpInFetchService]).createJumpInFetchInterface();\n";
		 }
		 if(op_stage_instr.containsKey(BNode.RdMem) || op_stage_instr.containsKey(BNode.RdMem_spawn) ||  op_stage_instr.containsKey(BNode.WrMem) || op_stage_instr.containsKey(BNode.WrMem_spawn) ) {
			 setupServices += "// Get service for memory transfers\n";
			 setupServices += "dBusAccess = pipeline.service(classOf[DBusAccessService]).newDBusAccess();\n";
		}
		 toFile.UpdateContent(filePlugin,setupServices);
	 }
		 
		 
	 private void IntegrateISAX_CloseSetupPhase() {
		 language.CloseBrackets();
	 }
	 
	 // #####################################################################################
	 // #################################### BUILD PHASE ####################################
	 // #####################################################################################
	 /***************************************
	  * Open build section 
	  **************************************/
	 private void IntegrateISAX_OpenBuild() {
		 toFile.UpdateContent(filePlugin,"\n// Build phase.");
		 toFile.UpdateContent(filePlugin,"override def build(pipeline: VexRiscv): Unit = {");
		 toFile.nrTabs++;
		 toFile.UpdateContent(filePlugin,"import pipeline._");
		 toFile.UpdateContent(filePlugin,"import pipeline.config._");

		 if(op_stage_instr.containsKey(BNode.WrRD)) // EC
			 toFile.UpdateContent(filePlugin,"val writeStage = if (writeRfInMemoryStage) pipeline.memory else pipeline.stages.last ");
	 }
	 
	 /***************************************
	  * Open build section for ONE plugin/stage
	  ***************************************/
	 private void IntegrateISAX_Build(int stage) {
		 for(SCAIEVNode operation : op_stage_instr.keySet()) {
			 boolean spawnReq = ( (operation.equals(BNode.WrMem_spawn) || operation.equals(BNode.RdMem_spawn) || operation.equals(BNode.WrRD_spawn))  && stage==this.vex_core.maxStage) ;
			 if(op_stage_instr.get(operation).containsKey(stage) || spawnReq || (stage==1 && this.ContainsOpInStage(BNode.WrFlush, 0)))
			 {
				 if(stage>0)  {
					 toFile.UpdateContent(filePlugin," ");
					 toFile.UpdateContent(filePlugin,stages.get(stage)+" plug new Area {");
					 toFile.UpdateContent(filePlugin,"import "+stages.get(stage)+"._");
					 toFile.nrTabs++;
					 IntegrateISAX_BuildIOs(stage);
					 IntegrateISAX_BuildBody(stage);
					 language.CloseBrackets(); // close build stage
					 
					 break;
				 }
			 }
		 }
		 if(stage==0)  {
			 IntegrateISAX_BuilPCNodes();
		 }
		 
	 }
	 
	 /**************************************
	  * Build body, Inputs/Outputs.
	  **************************************/
	 private void IntegrateISAX_BuildIOs(int stage) {
		 toFile.UpdateContent(filePlugin,"val io = new Bundle {");
		 toFile.nrTabs++;

		String interfaces = "";
			
		 for (SCAIEVNode operation: op_stage_instr.keySet())	 {
			 // Check if it is a spawn node to number its stage correctly. Check that it is an FNode, because otherwise, the core does not have any timing constrints on it. 
			 boolean spawnValid = !operation.nameParentNode.isEmpty() && FNode.HasSCAIEVNode(operation.nameParentNode) && operation.isSpawn()  && stage == this.spawnStages.get(operation);
			 int stageNr = stage;
			 if(spawnValid)
				 stageNr =  this.vex_core.maxStage+1;
			 if(spawnValid || ContainsOpInStage(operation,stage) || operation.equals(BNode.WrFlush)) {
				if(!(stage ==0 && operation.equals(BNode.WrFlush)))
					interfaces += language.CreateInterface(operation,stageNr, "");	
				if(stage ==1 &&  ContainsOpInStage(operation,stage-1) && operation.equals(BNode.WrFlush) )
					interfaces += language.CreateInterface(operation,stageNr-1, "");	
				// Generate adjacent signals on the interface
				for(AdjacentNode adjacent : BNode.GetAdj(operation)) {
					if(adjacent != AdjacentNode.none) {
						if(spawnValid && !operation.nameQousinNode.isEmpty() && this.op_stage_instr.containsKey(BNode.GetSCAIEVNode(operation.nameQousinNode)) && operation.isInput)
							continue;
						SCAIEVNode adjOperation = BNode.GetAdjSCAIEVNode(operation,adjacent);
						if(adjOperation != BNode.WrRD_validData)
							interfaces += language.CreateInterface(adjOperation,stageNr, "");	
					}
				}
			 }
			 
		 }
		 
		 if((this.op_stage_instr.containsKey(BNode.RdMem) | this.op_stage_instr.containsKey(BNode.WrMem)) && stage == this.vex_core.GetNodes().get(BNode.RdMem).GetEarliest() ) {
			 for(int earlierStage = this.vex_core.GetNodes().get(BNode.RdInstr).GetEarliest();earlierStage< this.vex_core.GetNodes().get(BNode.RdMem).GetEarliest(); earlierStage++ ) {
			 if(this.op_stage_instr.containsKey(BNode.RdMem))
				 interfaces += language.CreateInterface(BNode.GetAdjSCAIEVNode(BNode.RdMem, AdjacentNode.validReq),earlierStage, "");
			 if(this.op_stage_instr.containsKey(BNode.WrMem))
				 interfaces += language.CreateInterface(BNode.GetAdjSCAIEVNode(BNode.WrMem, AdjacentNode.validReq),earlierStage, "");
			 }
			 
		 }
	 
		 toFile.UpdateContent(filePlugin,interfaces);
		 language.CloseBrackets();
	 }
	 
	 public void IntegrateISAX_BuilPCNodes() {
		 int spawnStage = this.vex_core.maxStage+1;
		 String declareIO = "";
		 String logic = "";
		 if(this.ContainsOpInStage(BNode.WrPC,0)) {
			 declareIO += toFile.tab.repeat(2) +language.CreateInterface(scaiev.backend.BNode.WrPC, 0, "")
					     +toFile.tab.repeat(2) +language.CreateInterface(scaiev.backend.BNode.WrPC_valid, 0, "");
			 logic +=  toFile.tab.repeat(1)+"jumpInFetch.target_PC := io."+language.CreateNodeName(BNode.WrPC, 0, "")+";\n"
				     + toFile.tab.repeat(1)+"jumpInFetch.update_PC := io."+language.CreateNodeName(BNode.WrPC_valid, 0, "")+";\n";
		 }
		 if(this.ContainsOpInStage(BNode.RdPC,0)) {
			 declareIO += toFile.tab.repeat(2) +language.CreateInterface(scaiev.backend.BNode.RdPC, 0, "");
			 logic +=  toFile.tab.repeat(1)+"io."+language.CreateNodeName(BNode.RdPC, 0, "")+" := jumpInFetch.current_pc;\n";
		 }
		 if(this.op_stage_instr.containsKey(BNode.WrPC_spawn)) {
			 declareIO += toFile.tab.repeat(2) + language.CreateInterface(scaiev.backend.BNode.WrPC_spawn, spawnStage, "")
					    + toFile.tab.repeat(2) + language.CreateInterface(scaiev.backend.BNode.WrPC_spawn_valid, spawnStage, "");
			 logic +=  toFile.tab.repeat(1)+"jumpInFetch.target_PC := io."+language.CreateNodeName(BNode.WrPC_spawn,spawnStage, "")+";\n"
				     + toFile.tab.repeat(1)+"jumpInFetch.update_PC := io."+language.CreateNodeName(BNode.WrPC_spawn_valid, spawnStage, "")+";\n";
		 }
		 if(this.op_stage_instr.containsKey(BNode.WrPC_spawn) | this.ContainsOpInStage(BNode.WrPC,0))
			 logic += toFile.tab.repeat(1)+"decode.arbitration.flushNext setWhen jumpInFetch.update_PC;\n";
		 else if(this.ContainsOpInStage(BNode.RdPC,0))
			 logic +=  toFile.tab.repeat(1)+"jumpInFetch.update_PC := False;\n";
		 String text = "pipeline plug new Area {\n"
			        +toFile.tab.repeat(1)+"import pipeline._\n"
		            +toFile.tab.repeat(1)+"val io = new Bundle {\n"
		            +declareIO
		            +toFile.tab.repeat(1)+"}\n"
			 		+logic
		 		    +"}\n"; 
		 if(!logic.isEmpty())
			 toFile.UpdateContent(filePlugin,text);
	 }
		 
	 
	 /*************************************
	  * Build body for ONE stage.
	  *************************************/	 
	 public void IntegrateISAX_BuildBody(int stage) {
		String thisStagebuild = "";
		String clause = "";
		
	    
		// Simple assigns
		for(SCAIEVNode operation : op_stage_instr.keySet()) {
			if( (stage >0) && op_stage_instr.get(operation).containsKey(stage) && (!operation.equals(BNode.WrRD) && !operation.equals(BNode.WrMem) && !operation.equals(BNode.RdMem)) ) { // it cannot be spawn, as stage does not go to max_stage + 1
				if(!FNode.HasSCAIEVNode(operation.name) || stage>=this.vex_core.GetNodes().get(operation).GetEarliest()) {
					thisStagebuild += language.CreateAssignToISAX(operation,stage,"",(operation.equals(BNode.WrStall) || operation.equals(BNode.WrFlush)) );
				}
			} 
		}
		
		
		// WrPC valid clause
		if(ContainsOpInStage(BNode.WrPC,stage))  {
			thisStagebuild += "jumpInterface_"+stage+".valid := io."+language.CreateNodeName(BNode.WrPC_valid, stage, "")+" && !arbitration.isStuckByOthers\n";
			thisStagebuild += "arbitration.flushNext setWhen (jumpInterface_"+stage+".valid);";
		}
		if(!thisStagebuild.isEmpty()) {
			thisStagebuild += "\n\n\n";
			toFile.UpdateContent(filePlugin,thisStagebuild);
		}
		IntegrateISAX_WrRDBuild(stage);
		
		if(stage == this.vex_core.GetNodes().get(BNode.RdMem).GetEarliest() && (op_stage_instr.containsKey(BNode.WrMem) || op_stage_instr.containsKey(BNode.WrMem_spawn) || op_stage_instr.containsKey(BNode.RdMem) || op_stage_instr.containsKey(BNode.RdMem_spawn)))
			 IntegrateISAX_MemBuildBody(stage);
		
		
      }
	 
	 
	// RD/WR Memory
	 public void IntegrateISAX_MemBuildBody(int stage) {
		 int memStage = this.vex_core.GetNodes().get(BNode.RdMem).GetLatest();
		 int spawnStage = this.vex_core.maxStage+1;
		 if(stage == memStage && ((op_stage_instr.containsKey(BNode.WrMem) || op_stage_instr.containsKey(BNode.WrMem_spawn) || op_stage_instr.containsKey(BNode.RdMem) || op_stage_instr.containsKey(BNode.RdMem_spawn)))) {
			 String response = "";
			 String rdData = "";
			 // Repair original file 
			 //toFile.ReplaceContent(pathRISCV+"/plugin/DBusSimplePlugin.scala","when(!stages.dropWhile(_ != execute)", new ToWrite("when(stages.dropWhile(_ != execute).map(_.arbitration.isValid).orR){",false,true,""));
			
			 // Add RESPONSE State and default values for reads
			 if(this.ContainsOpInStage(BNode.RdMem, memStage) || this.op_stage_instr.containsKey(BNode.RdMem_spawn)) {
				 response += ", RESPONSE";
			 }
			 if(this.ContainsOpInStage(BNode.RdMem, memStage)) {
				 rdData += "io."+language.CreateNodeName(BNode.RdMem, memStage, "") +" := 0\n";
			 }
			 
			 if(this.ContainsOpInStage(BNode.RdMem_spawn, spawnStage)) {
				 rdData += "io."+language.CreateNodeName(BNode.RdMem_spawn, spawnStage, "") +" := 0\n";
				 rdData += "io."+language.CreateNodeName(BNode.RdMem_spawn_validResp, spawnStage, "") +" := "+this.language.GetDict(DictWords.False)+"\n";
			 }
			 
			 // Do we need Rd/Wr Mem custom address? 
			 boolean rdaddr = !language.CreateClauseAddr(ISAXes, BNode.RdMem, stage, "").isEmpty(); 
			 boolean wraddr = !language.CreateClauseAddr(ISAXes, BNode.WrMem, stage, "").isEmpty(); 
			 String rdaddrText = "";
			 String wraddrText = "";
			 String spawnaddr = "";
			 if(rdaddr)
				 rdaddrText += "			               when(io."+language.CreateNodeName(BNode.RdMem_addr_valid, memStage, "")+") { // set to 0 if no ISAX needs it and removed by synth tool \n"
					 		 + "			                  dBusAccess.cmd.address := io."+language.CreateNodeName(BNode.RdMem_addr, memStage, "")+"\n"
					 		 + "			               }\n";
			 if(wraddr)
				 wraddrText += "			               when(io."+language.CreateNodeName(BNode.WrMem_addr_valid, memStage, "")+") { // set to 0 if no ISAX needs it and removed by synth tool \n"
					 	 	 + "			                  dBusAccess.cmd.address := io."+language.CreateNodeName(BNode.WrMem_addr, memStage, "")+"\n"
					 	     + "			               }\n";
			 if(op_stage_instr.containsKey(BNode.WrMem_spawn) || op_stage_instr.containsKey(BNode.RdMem_spawn)) 
				 spawnaddr +=  "			               when(io."+language.CreateNodeName(BNode.RdMem_spawn_validReq, spawnStage, "")+") {\n"
					 		 + "			                  dBusAccess.cmd.address := io."+language.CreateNodeName(BNode.RdMem_spawn_addr, spawnStage, "")+"\n"
					 		 + "			               }\n"; 
			 
			 
			 // Is it a write or a read? Is it a valid mem ? What is the transfer size? 
			 String writeText = "False";
			 String valid = "";
			 String valid_1 = "";
			 String spawnvalid = "";
			 String invalidTransfer = "";
			 String transferSize = "";
			 
			 if(op_stage_instr.containsKey(BNode.WrMem)) {
				 writeText = "io."+language.CreateNodeName(BNode.WrMem_validReq, memStage, "");
				 valid += "io."+language.CreateNodeName(BNode.WrMem_validReq, memStage, ""); 
				 valid_1 += "io."+language.CreateNodeName(BNode.WrMem_validReq, memStage-1, ""); 
			 }
			 if(op_stage_instr.containsKey(BNode.WrMem_spawn)) 
				 writeText = language.OpIfNEmpty(writeText, " || ") +  "io."+language.CreateNodeName(BNode.WrMem_spawn_write, spawnStage, "");
			 if(op_stage_instr.containsKey(BNode.RdMem)) {
				 valid   = language.OpIfNEmpty(valid  , " || ") +  "io."+language.CreateNodeName(BNode.RdMem_validReq, memStage, ""); 
				 valid_1 = language.OpIfNEmpty(valid_1, " || ") +  "io."+language.CreateNodeName(BNode.RdMem_validReq, memStage-1, ""); 
			 }
			 if(op_stage_instr.containsKey(BNode.WrMem_spawn) || op_stage_instr.containsKey(BNode.RdMem_spawn)) 
				 spawnvalid += " || io."+language.CreateNodeName(BNode.RdMem_spawn_validReq,spawnStage, ""); 
			 
			 // Compute write data depending on transfer type 
			 String writedata = "";
			 if(op_stage_instr.containsKey(BNode.WrMem) || op_stage_instr.containsKey(BNode.WrMem_spawn)) {
				 if(op_stage_instr.containsKey(BNode.RdMem) || op_stage_instr.containsKey(BNode.RdMem_spawn))
					 writedata += "                        dBusAccess.cmd.data   := 0 ;\n";
				 if(op_stage_instr.containsKey(BNode.WrMem))
					 writedata +=  "                        when(io."+language.CreateNodeName(BNode.WrMem_validReq, memStage, "")+") {\n"
				 		         + "                            dBusAccess.cmd.data := io."+language.CreateNodeName(BNode.WrMem, memStage, "")+"\n"
				 		         + "                        }\n"; 
				 if(op_stage_instr.containsKey(BNode.WrMem_spawn))
					 writedata +=  "                        when(io."+language.CreateNodeName(BNode.WrMem_spawn_validReq, spawnStage, "")+") {\n"
				 		         + "                            dBusAccess.cmd.data := io."+language.CreateNodeName(BNode.WrMem_spawn, spawnStage, "")+"\n"
				 		         + "                        }\n"; 
			 } else 
				 writedata += "                        dBusAccess.cmd.data   := 0 ;\n";
			 
			 // Compute condition to return to IDLE state if no valid memory  transfer 
			 if(op_stage_instr.containsKey(BNode.WrMem)) {
				 invalidTransfer += "io."+ language.CreateNodeName(BNode.WrMem_validReq,memStage, "");
			 }
			 if(op_stage_instr.containsKey(BNode.RdMem)) {
				 invalidTransfer = language.OpIfNEmpty(invalidTransfer  , " || ") +  "io."+ language.CreateNodeName(BNode.RdMem_validReq,memStage, "");
			 }	 
			 if(op_stage_instr.containsKey(BNode.RdMem_spawn) | op_stage_instr.containsKey(BNode.WrMem_spawn)) {
				 invalidTransfer = language.OpIfNEmpty(invalidTransfer  , " || ") + "io."+ language.CreateNodeName(BNode.RdMem_spawn_validReq,spawnStage, "");
			 }
				
			 // Compute data size. For common instr this is INSTRUCTION [14:13] , for spawn this is 2 
			 if(op_stage_instr.containsKey(BNode.RdMem) | op_stage_instr.containsKey(BNode.WrMem))
				 transferSize += "                        dBusAccess.cmd.size    := execute.input(INSTRUCTION)(13 downto 12).asUInt	\n";
			 if(op_stage_instr.containsKey(BNode.RdMem_spawn) | op_stage_instr.containsKey(BNode.WrMem_spawn))
				 transferSize += "                        when(io."+language.CreateNodeName(BNode.RdMem_spawn_validReq,spawnStage, "")+") { dBusAccess.cmd.size    := 2 }\n";
			 
			 // Compute response state depending on whether it's a read or a write 
			 String nextstateRd = ""; 
			 String nextstateWr = "";
			 String rdState = "";
			 if(op_stage_instr.containsKey(BNode.WrMem)) {
				 nextstateWr += "when (io."+language.CreateNodeName(BNode.WrMem_validReq, memStage, "")+") {\n    state := State.IDLE\n}";
				 nextstateWr = language.AllignText(language.tab.repeat(7), nextstateWr);
				 nextstateWr += "\n";
			 }
			 if(op_stage_instr.containsKey(BNode.WrMem_spawn)) {
				 nextstateWr += "when (io."+language.CreateNodeName(BNode.WrMem_spawn_validReq, spawnStage, "")+" && io."+language.CreateNodeName(BNode.WrMem_spawn_write, spawnStage, "")+") {\n    state := State.IDLE\nio."+language.CreateNodeName(BNode.WrMem_spawn_validResp, spawnStage, "")+" := True;\n}";
				 nextstateWr = language.AllignText(language.tab.repeat(7), nextstateWr);
				 nextstateWr += "\n";
			 }
			 if(op_stage_instr.containsKey(BNode.RdMem) | op_stage_instr.containsKey(BNode.RdMem_spawn)) {
				 String condNextState = ""; 
				 String logicInState = "";
				 if(op_stage_instr.containsKey(BNode.RdMem)) {
					 condNextState += "io."+ language.CreateNodeName(BNode.RdMem_validReq, memStage, "");
					 logicInState +="                        io."+language.CreateNodeName(BNode.RdMem, memStage, "")+" := dBusAccess.rsp.data;  \n";
				 }
				 if(op_stage_instr.containsKey(BNode.RdMem_spawn)) {
					 condNextState  = this.language.OpIfNEmpty(condNextState, " || ") +"io."+ language.CreateNodeName(BNode.RdMem_spawn_validReq, spawnStage, "") + " && !io."+ language.CreateNodeName(BNode.RdMem_spawn_write, spawnStage, "");
					 logicInState +="                        io."+language.CreateNodeName(BNode.RdMem_spawn, spawnStage, "")+" := dBusAccess.rsp.data;  \n";
					 logicInState +="                        when(io."+language.CreateNodeName(BNode.RdMem_spawn_validReq, spawnStage, "")+") {\n "
					 		       +"                            io."+language.CreateNodeName(BNode.RdMem_spawn_validResp, spawnStage, "")+" := "+this.language.GetDict(DictWords.True)+";  \n"
					 		       +"                        }";
				 }
				 nextstateRd +="when ("+condNextState+") {\n    state := State.RESPONSE\n}";  
				 nextstateRd = language.AllignText(language.tab.repeat(7), nextstateRd);
				 rdState =    "                is(State.RESPONSE){  \n"
					 		+ "                    when(dBusAccess.rsp.valid){ \n"
					 		+ "                        state := State.IDLE\n"
					 		+ logicInState
					 		+ "                    } .otherwise {\n"
					 		+ "                        memory.arbitration.haltItself := "+this.language.GetDict(DictWords.True)+"\n"
					 		+ "                    }\n"
					 		+ "                }\n";
				
			 }
			 
			 // Entire FSM logic puzzled together
			 String logicText = "            val State = new SpinalEnum{\n"
			 		+ "                val IDLE, CMD "+response+" = newElement()\n"
			 		+ "            }\n"
			 		+ "            val state = RegInit(State.IDLE)\n"
			 		+ "            \n"
			 	//	+ "            io."+language.CreateNodeName(BNode.RdMem_validResp, memStage, "")+" := False\n"
			 		+ "	          "+rdData
			 		+ "            // Define some default values for memory FSM\n"
			 		+ "            dBusAccess.cmd.valid := False\n"
			 		+ "            dBusAccess.cmd.write := False\n"
			 		+ "            dBusAccess.cmd.size := 0 \n"
			 		+ "            dBusAccess.cmd.address.assignDontCare() \n"
			 		+ "            dBusAccess.cmd.data.assignDontCare() \n"
			 		+ "            dBusAccess.cmd.writeMask.assignDontCare()\n"
			 		+ "            \n"
			 		+ "            val ldst_in_decode = Bool()		    \n"
			 		+ "            when(state !==  State.IDLE) {\n"
			 		+ "                when(ldst_in_decode) {\n"
			 		+ "                    decode.arbitration.haltItself := True;\n"
			 		+ "                }\n"
			 		+ "            }\n"
			 		+ "            ldst_in_decode := (("+valid_1+") && decode.input(IS_ISAX))\n"
			 		+ "            switch(state){\n"
			 		+ "                is(State.IDLE){\n"
			 		+ "                    when(ldst_in_decode && decode.arbitration.isFiring "+spawnvalid+") { \n"
			 		+ "                        state := State.CMD\n"
			 		+ "                    }\n"
			 		+ "                }\n"
			 		+ "                is(State.CMD){\n"
			 		+ "                    when(~("+invalidTransfer+")) {\n"
					+ "                            state := State.IDLE\n"
					+ "                    }.otherwise {\n"
			 		+ "                     when(execute.arbitration.isValid "+spawnvalid+") {\n"
			 		+ "                        dBusAccess.cmd.valid   := True \n"
			 		+ transferSize+"\n"
			 		+ writedata+"\n"
			 		+ "                        dBusAccess.cmd.write   := "+writeText+"\n"
			 		+ "                        dBusAccess.cmd.address := execute.input(SRC_ADD).asUInt  \n"
			 		+ wraddrText
			 		+ rdaddrText
			 		+ spawnaddr
			 		+ "                        when(dBusAccess.cmd.ready) {// Next state \n"
			 		+ nextstateWr +"\n"
			 		+ nextstateRd +"\n"
			 		+ "                        }.otherwise {\n"
			 		+ "                            execute.arbitration.haltItself := True\n"
			 		+ "                        }\n"
			 		+ "                     }\n"
			 		+ "                    }\n"
			 		+ "                }\n"
                    + rdState
			 		+ "            }\n"
			 		+ "";
			 toFile.UpdateContent(filePlugin,logicText);
			}
		 
	 }
	
	 public void IntegrateISAX_WrRDBuild(int stage) {
		 // COMMON WrRD, NO SPAWN :
		if(ContainsOpInStage(BNode.WrRD,stage) ) {
			 String stage_valid = "";
			 if(stage==4)
				 stage_valid = " && !writeBack.input(BYPASSABLE_MEMORY_STAGE) && !writeBack.input(BYPASSABLE_EXECUTE_STAGE) && writeBack.input(IS_ISAX)";
			 if(stage==3)
				 stage_valid = " && memory.input(BYPASSABLE_MEMORY_STAGE) && !memory.input(BYPASSABLE_EXECUTE_STAGE) && memory.input(IS_ISAX)";
			 if(stage==2)
				 stage_valid = " && execute.input(BYPASSABLE_EXECUTE_STAGE) && execute.input(IS_ISAX)";
			 
			 toFile.UpdateContent(filePlugin,"when(io."+language.CreateNodeName(BNode.WrRD_valid, stage, "")+") {\n");
			 toFile.nrTabs++;	 
			 toFile.UpdateContent(filePlugin,stages.get(stage)+".output(REGFILE_WRITE_DATA) := io."+language.CreateNodeName(BNode.WrRD, stage, "")+";\n");				 		
			 toFile.nrTabs--;
			 toFile.UpdateContent(filePlugin,"}\n");
			 
			 toFile.UpdateContent(filePlugin,"when(!io."+language.CreateNodeName(BNode.WrRD_valid, stage, "")+stage_valid+" ) {\n");
			 toFile.nrTabs++;	 
			 toFile.UpdateContent(filePlugin,stages.get(stage)+".output(REGFILE_WRITE_VALID) := False;\n");				 		
			 toFile.nrTabs--;
			 toFile.UpdateContent(filePlugin,"}\n");
		 }
		 
		 // SPAWN
		 int spawnStage = this.vex_core.maxStage+1;
		 if(op_stage_instr.containsKey(BNode.WrRD_spawn) && stage==this.vex_core.GetNodes().get(BNode.WrRD).GetLatest()) {			 
			 String logic = "";
			 logic += "when (io."+language.CreateNodeName(BNode.WrRD_spawn_valid, spawnStage, "")+") {\n "
			 		+ toFile.tab +stages.get(stage)+".output(INSTRUCTION) := ((11 downto 7) ->io."+language.CreateNodeName(BNode.WrRD_spawn_addr, spawnStage, "")+", default -> false)\n"
			 		+ toFile.tab +stages.get(stage)+".arbitration.isRegFSpawn := True\n"
			 		+ toFile.tab +stages.get(stage)+".output(REGFILE_WRITE_DATA) := io."+language.CreateNodeName(BNode.WrRD_spawn, spawnStage, "")+"\n"
			 		+ toFile.tab + "}\n"
			 		+ "io."+language.CreateNodeName(BNode.WrRD_spawn_validResp, spawnStage, "")+" := True;\n";
			 toFile.UpdateContent(filePlugin,logic);
		 }
		 
		
	 }

	 /** Function for updating configuration file and adding new instructions
	  * 
	  */
	 private void IntegrateISAX_UpdateConfigFile() {

		 String filePath = pathRISCV+"/demo"+"/"+baseConfig + ".scala";
		 System.out.println("INTEGRATE. Updating "+filePath);
		 
		 String lineToBeInserted = "new "+extension_name+"(),";
		 
		 toFile.UpdateContent(filePath,"plugins = List(", new ToWrite(lineToBeInserted,false,true,""));
		 toFile.ReplaceContent(filePath,"new MulPlugin,", new ToWrite("//new MulPlugin, // SCAIEV Paper",false,true,""));
		 toFile.ReplaceContent(filePath,"new MulPlugin,", new ToWrite("//new DivPlugin, // SCAIEV Paper",false,true,""));
		 toFile.ReplaceContent(filePath,"earlyBranch = false,",new ToWrite("earlyBranch = true, // SCAIEV Paper",false,true,""));
		 if(vex_core.maxStage>3) 
			 toFile.UpdateContent(filePath,"plugins = List(", new ToWrite("withWriteBackStage = true, //SCAIEV Paper",false,true,"",true));
		 else 
			 toFile.UpdateContent(filePath,"plugins = List(", new ToWrite("withWriteBackStage = false, //SCAIEV Paper",false,true,"",true));
		 if(vex_core.maxStage>2)  
			 toFile.UpdateContent(filePath,"plugins = List(", new ToWrite("withMemoryStage    = true, // SCAIEV Paper",false,true,"",true));
		 else 
			 toFile.UpdateContent(filePath,"plugins = List(", new ToWrite("withMemoryStage    = false, // SCAIEV Paper",false,true,"",true));
		 if(this.vex_core.GetNodes().get(BNode.RdRS1).GetLatency()==0) 
			 toFile.ReplaceContent(filePath,"regFileReadyKind = plugin.SYNC", new ToWrite("regFileReadyKind = plugin.ASYNC, //SCAIEV Paper", false, true, ""));
		 if(this.vex_core.GetNodes().get(BNode.RdRS1).GetEarliest()==2) 
			 toFile.UpdateContent(filePath,"regFileReadyKind = plugin.SYNC", new ToWrite("readInExecute = true,",false,true,""));
	 }
	 
	 private void IntegrateISAX_UpdateMemFile() {
		 String filePath = pathRISCV+"/plugin"+"/"+"DBusSimplePlugin.scala";
		 System.out.println("INTEGRATE. Updating "+filePath);
		 String lineToBeInserted = "";
		 boolean memory_required = false;
		 if(this.op_stage_instr.containsKey(BNode.WrMem) || this.op_stage_instr.containsKey(BNode.RdMem))
			 memory_required = true;
		 if (this.op_stage_instr.containsKey(BNode.WrMem_spawn) || this.op_stage_instr.containsKey(BNode.RdMem_spawn) || memory_required) {
			 lineToBeInserted = "// when(stages.dropWhile(_ != execute).map(_.arbitration.isValid).orR){ // scaiev make sure spawn works";
			 toFile.ReplaceContent(filePath,"when(!stages.dropWhile(_ != execute).map(_.arbitration.isValid).orR", new ToWrite(lineToBeInserted,false,true,""));	
			 lineToBeInserted = "// } // scaiev make sure spawn works";
			 toFile.ReplaceContent(filePath,"}", new ToWrite(lineToBeInserted,true,false,"when(!stages.dropWhile(_ != execute).map(_.arbitration.isValid).orR"));		
		 }
			 
		 
	 }

	 /** Function for updating arbitration for WrRD Spawn
	  * 
	  */
	 private void IntegrateISAX_UpdateRegFforSpawn() {
		 if(this.op_stage_instr.containsKey(BNode.WrRD_spawn)) {
			 String filePath = pathRISCV+"/plugin"+"/"+ "RegFilePlugin.scala";
			 System.out.println("INTEGRATE. Updating "+filePath);
			 String lineToBeInserted = "regFileWrite.valid :=  output(REGFILE_WRITE_VALID) && arbitration.isFiring || arbitration.isRegFSpawn // Logic updated for Spawn WrRD ISAX";
		
			 toFile.ReplaceContent(filePath,"regFileWrite.valid :=", new ToWrite(lineToBeInserted,false,true,""));	
		 }
	 }
	 
	 /** Function for updating RegF for  WrRD Spawn
	  * 
	  */
	 private void IntegrateISAX_UpdateArbitrationFile() {
		 if(this.op_stage_instr.containsKey(BNode.WrRD_spawn)) {
			 String filePath =  pathRISCV+"/Stage.scala";
			 System.out.println("INTEGRATE. Updating "+filePath);
			 String lineToBeInserted = " val isRegFSpawn     = False    //Inform if an instruction using the spawn construction is ready to write its result in the RegFile\n";
			
			 toFile.UpdateContent(filePath,"val arbitration =", new ToWrite(lineToBeInserted,false,true,""));	
		 }
	 }
	 
	 /** Function for updating IFetch 
	  * 
	  */
	 private void IntegrateISAX_UpdateIFetchFile() {
		 String tab = toFile.tab;
		 String filePath = pathRISCV+"/plugin"+"/"+ "Fetcher.scala";
		 System.out.println("INTEGRATE. Updating "+filePath);
		 String lineToBeInserted ="";
		 if(this.op_stage_instr.containsKey(BNode.WrPC_spawn)  || (this.op_stage_instr.containsKey(BNode.WrPC) && this.op_stage_instr.get(BNode.WrPC).containsKey(0)) || (this.op_stage_instr.containsKey(BNode.RdPC) && this.op_stage_instr.get(BNode.RdPC).containsKey(0))  ) {
			 lineToBeInserted = "var jumpInFetch: JumpInFetch = null\n"
					  +"override def createJumpInFetchInterface(): JumpInFetch = {\n"
					  +tab+"assert(jumpInFetch == null)\n"
					  +tab+"jumpInFetch = JumpInFetch()\n"
					  +tab+"jumpInFetch\n"
					  +"}";
			 toFile.UpdateContent(filePath,"class FetchArea", new ToWrite(lineToBeInserted,false,true,"",true));	
			 
			 lineToBeInserted =  "val predictionBuffer : Boolean = true) extends Plugin[VexRiscv] with JumpService with IBusFetcher with JumpInFetchService{ ";
			 toFile.ReplaceContent(filePath,"val predictionBuffer : Boolean = true) extends Plugin[VexRiscv] with JumpService with IBusFetcher{", new ToWrite(lineToBeInserted,false,true,""));	
		 }
		 if(this.op_stage_instr.containsKey(BNode.WrPC_spawn)  || (this.op_stage_instr.containsKey(BNode.WrPC) && this.op_stage_instr.get(BNode.WrPC).containsKey(0)) ) {
			 lineToBeInserted = "when (jumpInFetch.update_PC){\n"
					 			+tab+"correction := True\n"
					 		    +tab+"pc := jumpInFetch.target_PC\n"
					 		    +tab+"flushed := False\n"       
					 		   +"}\n";
			 toFile.UpdateContent(filePath,"when(booted && (output.ready || correction || pcRegPropagate)){", new ToWrite(lineToBeInserted,false,true,"",true));	
			 
		 }
		 
		 if( this.op_stage_instr.containsKey(BNode.RdPC) && this.op_stage_instr.get(BNode.RdPC).containsKey(0) ) {
			 lineToBeInserted = "jumpInFetch.current_pc := pcReg;";
			 toFile.UpdateContent(filePath,"output.payload := pc", new ToWrite(lineToBeInserted,false,true,""));
			 
		 }
	 }
	 

	 /** Function for updating DH Vex Unit. Needed for ISAXes writing regFile. This is one ay of solving DH issue with ISAXes when supporting ISAXes writing in different stages 
	  * 
	  */
	 private void IntegrateISAX_UpdateDHFile() {
		 String filePath = pathRISCV+"/plugin"+"/"+ "HazardSimplePlugin.scala";
		 if(this.op_stage_instr.containsKey(BNode.WrRD))
			 toFile.ReplaceContent(filePath,"when(stage.arbitration.isValid && stage.input(REGFILE_WRITE_VALID))", new ToWrite("when(stage.arbitration.isValid && stage.output(REGFILE_WRITE_VALID)) {",false,true,""));	
			
	 }
	 
	 
	 /** Function for updating Services 
	  * 
	  */
	 private void IntegrateISAX_UpdateServiceFile() {
		 String filePath =  pathRISCV+"/Services.scala";
		 String tab = toFile.tab;
		 System.out.println("INTEGRATE. Updating "+filePath);
		 String lineToBeInserted ="";
		 
		 if(this.op_stage_instr.containsKey(BNode.WrPC_spawn)  || (this.op_stage_instr.containsKey(BNode.WrPC) && this.op_stage_instr.get(BNode.WrPC).containsKey(0))  || (this.op_stage_instr.containsKey(BNode.RdPC) && this.op_stage_instr.get(BNode.RdPC).containsKey(0))  ) {
			 lineToBeInserted ="case class JumpInFetch() extends Bundle {\n"
			  +tab+"val update_PC  = Bool\n"
			  +tab+"val target_PC =  UInt(32 bits)\n"
			  +tab+"val current_pc = UInt(32 bits)\n"
			  +"}\n"
			  +"trait JumpInFetchService {\n"
			  +tab+"def createJumpInFetchInterface() : JumpInFetch\n"
			  +"}\n";
			 toFile.UpdateContent(filePath,"trait JumpService{", new ToWrite(lineToBeInserted,false,true,"",true));
		 }

	 }
	 
	 private boolean ContainsOpInStage(SCAIEVNode operation, int stage) {
		 return op_stage_instr.containsKey(operation) && op_stage_instr.get(operation).containsKey(stage);
	 }
	 
	 
	 private void ConfigVex() {
	 	this.PopulateNodesMap(this.vex_core.maxStage);
	 	Module newModule = new Module();
	 	newModule.name = extension_name;
	 	newModule.file = pathRISCV + "/plugin"+extension_name+".scala";
	 	this.fileHierarchy.put(extension_name,newModule );
	 	stages.put(1, "decode");
	 	stages.put(2, "execute");
	 	stages.put(3, "memory");
	 	stages.put(4, "writeBack");
	 	int spawnStage = this.vex_core.maxStage+1;
	 	
	 	spawnStages.put(BNode.RdMem_spawn, this.vex_core.GetNodes().get(BNode.RdMem).GetEarliest());
	 	spawnStages.put(BNode.WrMem_spawn, this.vex_core.GetNodes().get(BNode.RdMem).GetEarliest());
	 	spawnStages.put(BNode.WrPC_spawn, this.vex_core.GetNodes().get(BNode.WrPC).GetEarliest());
	 	spawnStages.put(BNode.WrRD_spawn, this.vex_core.GetNodes().get(BNode.WrRD).GetLatest());
	 	
	 	this.PutNode( "UInt", "jumpInFetch.target_PC", "", BNode.WrPC,0);
	 	this.PutNode(  "Bool", "jumpInFetch.update_PC", "", BNode.WrPC_valid,0);
	 	this.PutNode( "UInt", "jumpInFetch.current_pc", "", BNode.RdPC,0);
	 	this.PutNode( "UInt", "jumpInFetch.target_PC", "", BNode.WrPC_spawn,spawnStage);
	 	this.PutNode(  "Bool", "jumpInFetch.update_PC", "", BNode.WrPC_spawn_valid,spawnStage);
	 	for(int stage : stages.keySet()) {
	 		//assign rdInstr = stages.get(stage)+".input(INSTRUCTION)";
	 		this.PutNode( "Bits", stages.get(stage)+".input(INSTRUCTION)", stages.get(stage), BNode.RdInstr,stage);
	 		this.PutNode( "UInt", stages.get(stage)+".input(PC)", stages.get(stage),BNode.RdPC,stage);
	 		this.PutNode( "Bool", stages.get(stage)+".arbitration.isStuck", stages.get(stage), BNode.RdStall,stage);
	 		this.PutNode( "Bool", stages.get(stage)+".arbitration.isFlushed || (!"+stages.get(stage)+".arbitration.isValid)", stages.get(stage), BNode.RdFlush,stage); //|| (!"+stages.get(stage)+".arbitration.isValid)"
	 		this.PutNode(  "Bool", stages.get(stage)+".arbitration.haltByOther", stages.get(stage), BNode.WrStall,stage);
	 		if(stage>0)
	 			this.PutNode(  "Bool", stages.get(stage)+".arbitration.flushIt", stages.get(stage), BNode.WrFlush,stage);
	 		if(this.vex_core.GetNodes().get(BNode.WrRD).GetLatest()>=stage && this.vex_core.GetNodes().get(BNode.WrRD).GetEarliest()<=stage) {
	 			this.PutNode( "Bits", stages.get(stage)+".output(REGFILE_WRITE_DATA)", stages.get(stage), BNode.WrRD,stage);
	 			this.PutNode( "Bool", stages.get(stage)+".output(REGFILE_WRITE_VALID)", stages.get(stage), BNode.WrRD_valid,stage);
	 		}
	 	}
		this.PutNode(  "Bool", "", stages.get(1), BNode.WrFlush,0);
	 	
	 	this.PutNode(  "Bool", stages.get(1)+".arbitration.flushNext", stages.get(1), BNode.WrFlush,0);
	 	
	 	for(int stage : stages.keySet()) {
	 		if(stage>1) {
		 		this.PutNode( "Bits", stages.get(stage)+".input(RS1)", stages.get(stage), BNode.RdRS1,stage);
		 		this.PutNode( "Bits", stages.get(stage)+".input(RS2)", stages.get(stage), BNode.RdRS2,stage);
		 	}
	 		if(stage>0) {
		 		this.PutNode(  "UInt", "jumpInterface_"+stage+".payload", "memory", BNode.WrPC,stage);
		 		this.PutNode(  "Bool", "", "memory", BNode.WrPC_valid,stage);
		 	}
	 	}
	 // Node = Operation rdrs rdinstr wrrd 
	 	int stageWrRD = this.vex_core.GetNodes().get(BNode.WrRD).GetLatest();
	 	this.PutNode( "Bits", stages.get(stageWrRD)+".output(REGFILE_WRITE_DATA)", stages.get(stageWrRD), BNode.WrRD_spawn,spawnStage);
	 	this.PutNode( "Bool", stages.get(stageWrRD)+".output(REGFILE_WRITE_VALID)", stages.get(stageWrRD), BNode.WrRD_spawn_valid,spawnStage);
	 	this.PutNode( "Bits", stages.get(stageWrRD)+".output(INSTRUCTION)", stages.get(stageWrRD), BNode.WrRD_spawn_addr,spawnStage);
	 	this.PutNode( "Bool", "True", stages.get(stageWrRD), BNode.WrRD_spawn_validResp,spawnStage);
	// 	this.PutNode("Bool", "True", stages.get(stageWrRD), BNode.WrRD_spawn_allowed,spawnStage);

	 	
	 	int stageMem = this.vex_core.GetNodes().get(BNode.RdMem).GetLatest();
	 	this.PutNode( "Bits", stages.get(stageMem)+"", stages.get(stageMem), BNode.RdMem,stageMem);
	 //	this.PutNode("Bool", stages.get(stageMem) +"", stages.get(stageMem), BNode.RdMem_validResp,stageMem);
	 	this.PutNode("Bits", stages.get(stageMem) +"", stages.get(stageMem), BNode.WrMem,stageMem);
	 	this.PutNode("Bool", stages.get(stageMem) +"", stages.get(stageMem), BNode.WrMem_validReq,stageMem);
	 	this.PutNode("Bool", stages.get(stageMem) +"", stages.get(stageMem), BNode.RdMem_validReq,stageMem);
	 	this.PutNode("Bool", stages.get(stageMem) +"", stages.get(stageMem), BNode.WrMem_validReq,stageMem-1);
	 	this.PutNode("Bool", stages.get(stageMem) +"", stages.get(stageMem), BNode.RdMem_validReq,stageMem-1);
	 	this.PutNode("Bool", stages.get(stageMem) +"", stages.get(stageMem), BNode.WrMem_validReq,stageMem-2);
	 	this.PutNode("Bool", stages.get(stageMem) +"", stages.get(stageMem), BNode.RdMem_validReq,stageMem-2);
	// 	this.PutNode("Bool", stages.get(stageMem) +"", stages.get(stageMem), BNode.RdMem_validResp,stageMem);
	 	this.PutNode("UInt", stages.get(stageMem) +"", stages.get(stageMem), BNode.RdMem_addr,stageMem);
	 	this.PutNode("Bool", stages.get(stageMem) +"", stages.get(stageMem), BNode.RdMem_addr_valid,stageMem);
	 	this.PutNode("UInt", stages.get(stageMem) +"", stages.get(stageMem), BNode.WrMem_addr,stageMem);
	 	this.PutNode("Bool", stages.get(stageMem) +"", stages.get(stageMem), BNode.WrMem_addr_valid,stageMem);
	 	
	 	this.PutNode("Bits", stages.get(stageMem) +"", stages.get(stageMem), BNode.RdMem_spawn,spawnStage);
	 	this.PutNode("Bool", stages.get(stageMem) +"", stages.get(stageMem), BNode.RdMem_spawn_validResp,spawnStage);
	 	this.PutNode("Bits", stages.get(stageMem) +"", stages.get(stageMem), BNode.WrMem_spawn,spawnStage);
	 	this.PutNode("Bool", stages.get(stageMem) +"", stages.get(stageMem), BNode.RdMem_spawn_validReq,spawnStage);
	 	this.PutNode("Bool", stages.get(stageMem) +"", stages.get(stageMem), BNode.WrMem_spawn_validReq,spawnStage);
	 	this.PutNode("Bool", stages.get(stageMem) +"", stages.get(stageMem), BNode.WrMem_spawn_validResp,spawnStage);
	 	this.PutNode("UInt", stages.get(stageMem) +"", stages.get(stageMem), BNode.RdMem_spawn_addr,spawnStage);
	 	this.PutNode("UInt", stages.get(stageMem) +"", stages.get(stageMem), BNode.WrMem_spawn_addr,spawnStage);
	 	this.PutNode("Bool", stages.get(stageMem) +"", stages.get(stageMem), BNode.WrMem_spawn_write,spawnStage);
	 	this.PutNode("Bool", stages.get(stageMem) +"", stages.get(stageMem), BNode.RdMem_spawn_write,spawnStage);
	 	
	 	this.PutNode( "Bool", stages.get(stageMem)+".arbitration.isFiring || io."+language.CreateNodeName(BNode.WrStall, this.vex_core.GetStartSpawnStage(), ""), stages.get(this.vex_core.GetStartSpawnStage()), BNode.ISAX_spawnAllowed,this.vex_core.GetStartSpawnStage());
	 	
     }

}
