package scaiev.util;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;

import scaiev.backend.BNode;
import scaiev.backend.CoreBackend;
import scaiev.coreconstr.Core;
import scaiev.frontend.FNode;
import scaiev.frontend.SCAIEVInstr;
import scaiev.frontend.Scheduled;
import scaiev.pipeline.PipelineStage;
import scaiev.pipeline.PipelineStage.StageKind;
import scaiev.frontend.SCAIEVNode;
import scaiev.frontend.SCAIEVNode.AdjacentNode;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;



public abstract class GenerateText {
	// logging
	protected static final Logger logger = LogManager.getLogger();

	public String currentFile;
	public CoreBackend coreBackend =  new CoreBackend();
	public String  regSuffix = "_reg";
	public String localSuffix = "_s";
	public String inputSuffix ="_i";
	public String outputSuffix = "_o";
	public boolean withinCore = false;
	public 	String clk = "clk";
	public String reset = "reset";
	public BNode BNode;
	public enum DictWords {

		module           ,
		endmodule        ,
		wire             ,
		reg              ,
		assign           ,
		assign_eq        ,
		logical_or       ,
		logical_and      ,
		bitwise_and      ,
		bitwise_or       ,
		bitsselectRight  ,
		bitsselectLeft   ,
		bitsRange        ,
		in               ,
		out              ,
		ifeq             , 
		True             , 
		False            ,
		ZeroBit 		, 
		OneBit
		
	}
	
	public GenerateText(BNode user_BNode)
	{
		this.BNode = user_BNode;
	}
	
	public HashMap<DictWords,String> dictionary = new HashMap<DictWords,String>(){{ 
		put(DictWords.module, "");
		put(DictWords.endmodule, "");
		put(DictWords.assign, "");
		put(DictWords.wire, "");
		put(DictWords.reg, "");
		put(DictWords.assign_eq,"");
		put(DictWords.logical_or,"");
		put(DictWords.logical_and,"");
		put(DictWords.bitwise_or,"");
		put(DictWords.bitwise_and,"");
		put(DictWords.bitsselectRight,"");
		put(DictWords.bitsselectLeft,"");
		put(DictWords.bitsRange,"");
		put(DictWords.in,"");
		put(DictWords.out,"");
		put(DictWords.True,"");
		put(DictWords.False,"");
	}};	
	
	public String CreateNodeName (SCAIEVNode operation, PipelineStage stage, String instr) {
		String nodeName = "";
		String suffix = this.outputSuffix;
		if(coreBackend.NodeIsInput(operation, stage))
			suffix =inputSuffix;	
		nodeName = CreateBasicNodeName(operation,stage,instr, true)+suffix;
		return nodeName;
	}
	

	public String CreateNodeName (String operation, PipelineStage stage, String instr,boolean isInput) {
		String suffix = this.outputSuffix;
		if(isInput)
			suffix =inputSuffix;
		return operation+instr+"_"+stage.getName()+suffix;
	}
	
	public String CreateFamNodeName (SCAIEVNode operation, PipelineStage stage, String instr, boolean familyName) {
		String nodeName = "";
		String suffix = this.outputSuffix;
		if(coreBackend.NodeIsInput(operation, stage))
			suffix =inputSuffix;	
		nodeName = CreateBasicNodeName(operation,stage,instr, familyName)+suffix;
		return nodeName;
	}
	
	
	public String CreateLocalNodeName (SCAIEVNode operation, PipelineStage stage, String instr) {
		String nodeName = "";
		String suffix =localSuffix;	
		nodeName =  CreateBasicNodeName(operation,stage,instr, true)+suffix;
		return nodeName;
	}
	
	public String CreateRegNodeName (SCAIEVNode operation, PipelineStage stage, String instr) {
		String nodeName = "";
		String suffix = regSuffix;	
		nodeName = CreateBasicNodeName(operation,stage,instr, true)+suffix;
		return nodeName;
	}
	
	public String CreateBasicNodeName(SCAIEVNode operation, PipelineStage stage, String instr, boolean familyname) {
		if(!instr.isEmpty())
			instr = "_"+instr;	
		String opName = operation.name;
		if(!operation.nameCousinNode.isEmpty() && operation.isAdj() && familyname)
			opName = operation.replaceRadixNameWith(operation.familyName);
		return opName+instr+"_"+CreateStageName(stage);
	}
	
	public String CreateStageName(PipelineStage stage) {
		// Use the 'global stage ID' if present (for compatibility), else use the stage name.
		return stage.getGlobalStageID().map(stageID->""+stageID).orElse(stage.getName());
	}

	
	
	
	
	public String GetDict(DictWords input) {
		return dictionary.get(input);
	}
	
	public String GetDictModule() {
		return dictionary.get(DictWords.module);
	}
	
	public String GetDictEndModule() {
		return dictionary.get(DictWords.endmodule);
	}
	
	public String AlignText(String alignment, String text) {
		String newText = alignment + text;
		newText = newText.replaceAll("(\\r\\n|\\n)(?!$)","\n"+alignment); //Don't replace trailing newline
		text = newText;
		return text;
	}


	public abstract Lang getLang();
	
	
	
	
	/// official repo
	public String CreateLocalNodeName (String operation, PipelineStage stage, String instr) {
		String nodeName = "";
		String suffix = "_s";
		if(!instr.isEmpty())
			instr = "_"+instr;		
		nodeName = operation+instr+"_"+CreateStageName(stage)+suffix;
		return nodeName;
	}
	
	private static String EncodeBitstringFor(String raw, Lang language) {
		if (language == Lang.VHDL)
			return "\"" + raw + "\"";
		else if (language == Lang.Verilog || language == Lang.Bluespec)
			return String.format("%d'b%s", raw.length(), raw);
		else
			return raw;
	}

	private String CreateValidSubencodings(String encodingRaw, String rdInstr, int bitmin, int bitmax) {
		String body = "";
		assert (encodingRaw.length() == bitmax - bitmin + 1);
		// Note: encodingRaw[0] is the highest bit -> bit index in rdInstr for
		// encodingRaw[i] is bitmax-i.
		int next_ibitmax = 0;
		for (int i = 0; i <= encodingRaw.length(); ++i) // encodingRaw.length() is in range (intentionally).
		{
			if (i == encodingRaw.length() || encodingRaw.charAt(i) == '-') {
				if (next_ibitmax < i) {
					if (!body.isEmpty())
						body += " " + dictionary.get(DictWords.logical_and) + " ";
					int cur_bitmax = bitmax - next_ibitmax;
					int cur_bitmin = bitmax - (i - 1);
					body += String.format("(%s%s%d%s%s %s %s)",
							rdInstr,
							dictionary.get(DictWords.bitsselectLeft),
							cur_bitmax,
							(cur_bitmin < cur_bitmax)
									? String.format(" %s %d", dictionary.get(DictWords.bitsRange), cur_bitmin)
									: "",
							dictionary.get(DictWords.bitsselectRight),
							dictionary.get(DictWords.ifeq),
							EncodeBitstringFor(encodingRaw.substring(next_ibitmax, i), getLang()));
				}
				next_ibitmax = i + 1;
			}
		}
		return body;
	}
 
	public String  CreateValidEncodingOneInstr(String ISAX, HashMap <String,SCAIEVInstr>  allISAXes, String rdInstr) {
		String body = "";
		if(!allISAXes.get(ISAX).GetEncodingOp(Lang.None).equals("-------"))
			body += CreateValidSubencodings(allISAXes.get(ISAX).GetEncodingOp(Lang.None), rdInstr, 0, 6);// "("+rdInstr+dictionary.get(DictWords.bitsselectLeft)+"6 "+dictionary.get(DictWords.bitsRange)+" 0"+dictionary.get(DictWords.bitsselectRight)+" "+dictionary.get(DictWords.ifeq)+" "+allISAXes.get(ISAX).GetEncodingOp(getLang())+")";
		if(!allISAXes.get(ISAX).GetEncodingF3(Lang.None).equals("---"))
			body += String.format("%s%s", body.isEmpty() ? "" : (" " + dictionary.get(DictWords.logical_and) + " "), CreateValidSubencodings(allISAXes.get(ISAX).GetEncodingF3(Lang.None), rdInstr, 12, 14));
		if(!allISAXes.get(ISAX).GetEncodingF7(Lang.None).equals("-------"))
			body += String.format("%s%s", body.isEmpty() ? "" : (" " + dictionary.get(DictWords.logical_and) + " "), CreateValidSubencodings(allISAXes.get(ISAX).GetEncodingF7(Lang.None), rdInstr, 25, 31));
		body = String.format("(%s)",  body);
		return body;            
	}

	
	public String  CreateAddrEncoding(HashSet<String> lookAtISAX, HashMap <String,SCAIEVInstr>  allISAXes, String rdInstr, SCAIEVNode operation) {
		String body = "";
		for (String ISAX  :  lookAtISAX) {
			if(allISAXes.get(ISAX).HasSchedWith(operation, snode -> snode.HasAdjSig(AdjacentNode.addr))) {
				if(!body.isEmpty())
					body += " "+dictionary.get(DictWords.logical_or)+" ";
				body += CreateValidEncodingOneInstr(ISAX, allISAXes, rdInstr);
			}
		}
		return body;		
	}
//	public String  CreateValidEncoding(HashSet<String> lookAtISAX, HashMap <String,SCAIEVInstr>  allISAXes, String rdInstr, SCAIEVNode operation) {
//		String body = "";
//		for (String ISAX  :  lookAtISAX) {
//			if(!body.isEmpty())
//				body += " "+dictionary.get(DictWords.logical_or)+" ";
//			body += CreateValidEncodingOneInstr(ISAX, allISAXes, rdInstr);
//			
//			Iterator<Scheduled> snode_iter = allISAXes.get(ISAX).GetSchedWithIterator(operation, _snode -> _snode.HasAdjSig(AdjacentNode.validReq));
//			if (snode_iter.hasNext()) {
//				body += " "+dictionary.get(DictWords.logical_and)+" (";
//				boolean is_first = true;
//				do {
//					Scheduled snode = snode_iter.next();
//					if (!is_first)
//						body += " "+dictionary.get(DictWords.logical_or)+" ";
//					body += this.CreateLocalNodeName(BNode.GetAdjSCAIEVNode(operation, AdjacentNode.validReq), snode.GetStartCycle(), "");
//					is_first = false;
//				} while (snode_iter.hasNext());
//				body += ")";
//			}
//			body += ")";
//		}
//		return body;		
//	}
	
		
	public String CreateAllEncoding(HashSet<String> lookAtISAX, HashMap <String,SCAIEVInstr>  allISAXes, String rdInstr) {
		String body = "";
		if(lookAtISAX ==null)
			return this.dictionary.get(DictWords.False);
		for (String ISAX  :  lookAtISAX) {
			if(!ISAX.isEmpty() &&  !allISAXes.get(ISAX).HasNoOp()) { // Ignore non-instr operations (SCAL-internal: ISAX empty, always: NoOp) 
				if(!body.isEmpty())
					body +=  " "+dictionary.get(DictWords.logical_or)+" ";
				body += CreateValidEncodingOneInstr(ISAX, allISAXes, rdInstr);;
			}
		}
		if (body.isEmpty())
			return dictionary.get(DictWords.False);
		return body;		
	}
	
	public void GenerateAllInterfaces (String topModule,HashMap<SCAIEVNode,HashMap<PipelineStage,HashSet<String>>> op_stage_instr, HashMap <String,SCAIEVInstr>  ISAXes, Core core, SCAIEVNode specialCase ) {
		logger.info("Generating interface for: "
			+ op_stage_instr.entrySet().stream().map(entryOpStIn -> {
					return Map.entry(entryOpStIn.getKey(), entryOpStIn.getValue().entrySet().stream().map(entryStIn ->
						Map.entry(entryStIn.getKey().getName(), entryStIn.getValue().stream().map(instr->"\""+instr+"\"").toList())
				).toList());
			}).toList()
		);
		HashSet<String> addedFamilyNodes = new HashSet<>();
		for (SCAIEVNode operation: op_stage_instr.keySet())	 {
			for(PipelineStage stage  : op_stage_instr.get(operation).keySet()) {
				//boolean spawnValid = !operation.nameParentNode.isEmpty() && BNode.HasSCAIEVNode(operation.nameParentNode) && operation.isSpawn() && stage.getKind() == StageKind.Decoupled;
				//		//&& stage == core.GetNodes().get(BNode.GetSCAIEVNode(operation.nameParentNode)).GetLatest();
				//if(spawnValid)
				//	stage = core.maxStage+1;
				if(!operation.equals(specialCase)) {
					UpdateInterface(topModule,operation, "",stage,true,false);	
//					// Generate adjacent signals on the interface
//					for(AdjacentNode adjacent : BNode.GetAdj(operation)) {
//						SCAIEVNode adjOperation = BNode.GetAdjSCAIEVNode(operation,adjacent).get();
//						if(adjacent != AdjacentNode.none && adjOperation !=specialCase ) {
//							if (!adjOperation.nameCousinNode.isEmpty() && BNode.HasSCAIEVNode(adjOperation.nameCousinNode)
//								&& !addedFamilyNodes.add(CreateNodeName(adjOperation, stage, ""))) {
//								//Prevent doubled interfaces nodes that will go by their family name.
//								//Example: RdMem_spawn_validReq and WrMem_spawn_validReq would each be added as Mem_spawn_validReq
//								continue;
//							}
//							UpdateInterface(topModule,adjOperation, "",stage,true,false);
//						}
//					}
				}
			}			 
		 }
	}
	
	public String OpIfNEmpty(String text, String op) {
		String returnText = text;
		if(!text.isEmpty())
			returnText += " "+op+" ";
		return returnText;
	}
	
	
	public String CreateNodeName (SCAIEVNode operation, PipelineStage stage, String instr, boolean input) {
		String nodeName = "";
		String suffix = "_o";
		if(input)
			suffix = "_i";
		nodeName = CreateBasicNodeName(operation,stage,instr, false)+suffix;
		return nodeName;
	}
	// TODO to be implemented by sub-class, if the subclass Language wants to use the GenerateAllInterfaces function
	public void UpdateInterface(String top_module,SCAIEVNode operation, String instr, PipelineStage stage, boolean top_interface, boolean assigReg) {
		throw new UnsupportedOperationException("Function not implemented");
	}
}
