package scaiev.scal.strategy.decoupled;

import java.util.EnumSet;
import java.util.Optional;
import java.util.stream.Stream;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import scaiev.frontend.SCAIEVNode;
import scaiev.scal.NodeInstanceDesc;
import scaiev.scal.NodeInstanceDesc.ExpressionType;
import scaiev.scal.NodeInstanceDesc.Key;
import scaiev.scal.NodeInstanceDesc.Purpose;
import scaiev.scal.NodeLogicBlock;
import scaiev.scal.NodeLogicBuilder;
import scaiev.scal.strategy.SingleNodeStrategy;

/** Implements certain standard modules (SimpleFIFO, SimpleShift, SimpleCounter) via {@link NodeInstanceDesc.Purpose#HDL_MODULE}. */
public class DecoupledStandardModulesStrategy extends SingleNodeStrategy {
  // logging
  protected static final Logger logger = LogManager.getLogger();

  public DecoupledStandardModulesStrategy() {}

  // Module names
  private static final String FIFOmoduleName = "SimpleFIFO";
  private static final String ShiftmoduleName = "SimpleShift";
  private static final String CountermoduleName = "SimpleCounter";

  public enum FIFOFeature {
    /** Placeholder to allow for inline conditions in calls of {@link DecoupledStandardModulesStrategy#makeFIFONode(FIFOFeature...)}. */
    NotAFeature(""),
    /**
     * Adds the level_trim output to the FIFO, size $clog2(NR_ELEMENTS).
     * For NR_ELEMENTS==2**n, the level_trim value is 0 for both !not_empty and !not_full.
     */
    Level("_Level"),
    /**
     * Adds the "write_front_i" input to the 'FIFO'.
     * When "write_valid_i" is set, the default is to add the new element to the back (-> FIFO).
     * writeFrontNotBack changes the current write to behave like a stack push instead (-> LIFO).
     * Is only sampled in conjunction with "write_valid_i".
     */
    WriteFront("_Writefront"),
    /**
     * Adds the "readahead_i" input to the FIFO.
     * When set, data_o will be set to the second element from the front of the FIFO.
     * When set, data_o will only be valid if the FIFO level is at least 2.
     */
    Readahead("_Readahead");

    public final String suffix;
    private FIFOFeature(String suffix) { this.suffix = suffix; }
  }

  /** Constructs a FIFO node for the given features. */
  public static SCAIEVNode makeFIFONode(FIFOFeature... features) {
    String fullSuffix = Stream.of(features).map(feature -> feature.suffix).sorted().reduce("", (a, b) -> a + b);
    return new SCAIEVNode(FIFOmoduleName + fullSuffix);
  }

  private static EnumSet<FIFOFeature> getFeaturesFromFIFONode(SCAIEVNode simplefifoNode) {
    var ret = EnumSet.noneOf(FIFOFeature.class);
    if (!simplefifoNode.name.startsWith(FIFOmoduleName)) {
      throw new IllegalArgumentException(String.format("Expected %s node, got %s", FIFOmoduleName, simplefifoNode.name));
    }
    // As long as there is a suffix
    String nameSuffix = simplefifoNode.name.substring(FIFOmoduleName.length());
    while (!nameSuffix.isEmpty()) {
      boolean foundAny = false;
      for (FIFOFeature feature : FIFOFeature.values())
        if (feature != FIFOFeature.NotAFeature) {
          if (nameSuffix.startsWith(feature.suffix)) {
            ret.add(feature);
            nameSuffix = nameSuffix.substring(feature.suffix.length());
            foundAny = true;
            break;
          }
        }
      if (!foundAny) {
        logger.error(String.format("FIFO node %s has unexpected suffix %s", simplefifoNode.name, nameSuffix));
        break;
      }
    }
    return ret;
  }

  //////////////////////////////////////////////  FUNCTIONS: LOGIC OF ADDITIONAL MODULES (FIFOs, shift regs...) ////////////////////////

  private String FIFOModule(String moduleName, EnumSet<FIFOFeature> features) {
    boolean hasLevel = features.contains(FIFOFeature.Level);
    boolean hasWriteFront = features.contains(FIFOFeature.WriteFront);
    boolean hasReadahead = features.contains(FIFOFeature.Readahead);
    boolean needsPrevPointer = hasWriteFront;

    String writeToExpression = "write_pointer";
    if (hasWriteFront)
      writeToExpression =
          String.format("write_front_i ? (%s) : write_pointer", String.format("read_valid_i ? read_pointer : prevPointer(read_pointer)"));

    String readFromExpression = "read_pointer";
    if (hasReadahead)
      readFromExpression = "readahead_i ? nextPointer(read_pointer) : read_pointer";

    String FIFOModuleContent =
        "\n"
        + "module " + moduleName + " #(\n"
        + "    parameter NR_ELEMENTS,\n"
        + "    parameter DATAW\n"
        + ")(\n"
        + "    input                 clk_i,\n"
        + "    input                 rst_i,\n"
        + "    input                 clear_i,\n"
        + "    input                 write_valid_i,\n" + (hasWriteFront ? "    input                 write_front_i,\n" : "") +
        "    input                 read_valid_i,\n"
        + "    input  [DATAW-1:0]    data_i,\n"
        + "    output                not_empty,\n"
        + "    output                not_full,\n" + (hasLevel ? "    output [$clog2(NR_ELEMENTS)-1:0] level_trim,\n" : "") +
        (hasReadahead ? "    input                 readahead_i,\n" : "") + "    output [DATAW-1:0]    data_o\n"
        + "\n"
        + ");\n"
        + "\n"
        + "reg [DATAW-1:0] FIFOContent [NR_ELEMENTS-1:0];\n"
        + "\n"
        + "typedef logic [$clog2(NR_ELEMENTS)-1:0] FIFOPointer_t;\n"
        + "localparam FIFOPointer_t MAX_PTR_VAL = NR_ELEMENTS-1;\n"
        + "localparam FIFOPointer_t MIN_PTR_VAL = 0;\n"
        + "localparam FIFOPointer_t PTR_INC = 1;\n"
        + "FIFOPointer_t write_pointer;\n"
        + "FIFOPointer_t read_pointer;\n"
        + "function FIFOPointer_t nextPointer(input FIFOPointer_t val);\n"
        + "    if ($clog2(NR_ELEMENTS) == $clog2(NR_ELEMENTS+1)\n"
        + "            && val == MAX_PTR_VAL)\n"
        + "        nextPointer = MIN_PTR_VAL; // explicit wrap if NR_ELEMENTS is not a power of 2\n"
        + "    else\n"
        + "        nextPointer = val + PTR_INC;\n"
        + "endfunction\n" +
        (needsPrevPointer ? "function FIFOPointer_t prevPointer(input FIFOPointer_t val);\n"
                                + "    if ($clog2(NR_ELEMENTS) == $clog2(NR_ELEMENTS+1)\n"
                                + "            && val == MIN_PTR_VAL)\n"
                                + "        prevPointer = MAX_PTR_VAL; // explicit wrap if NR_ELEMENTS is not a power of 2\n"
                                + "    else\n"
                                + "        prevPointer = val - PTR_INC;\n"
                                + "endfunction\n"
                          : "") +
        "\n" + (hasLevel ? "reg [$clog2(NR_ELEMENTS)-1:0] level;\n" : "") + "reg is_empty;\n"
        + "\n"
        + "always @(posedge clk_i) begin\n"
        + "    if(write_valid_i)\n"
        + "        FIFOContent[" + writeToExpression + "] <= data_i;\n"
        + "end\n"
        + "assign data_o = FIFOContent[" + readFromExpression + "];\n"
        + "assign not_empty = !is_empty;\n"
        + "assign not_full = write_pointer != read_pointer || is_empty;\n" + (hasLevel ? "assign level_trim = level;\n" : "") +
        "always @(posedge clk_i) begin\n"
        + "    if(rst_i) begin\n"
        + "        is_empty <= 1;\n"
        + "    end\n"
        + "    else if(clear_i) begin\n"
        + "        is_empty <= 1;\n"
        + "    end\n"
        + "    else if(write_valid_i) begin\n"
        + "        is_empty <= 0;\n"
        + "    end\n"
        + "    else if(read_valid_i && write_pointer == nextPointer(read_pointer)) begin\n"
        + "        is_empty <= 1;\n"
        + "    end\n"
        + "end\n" +
        (hasLevel ? "always @(posedge clk_i) begin\n"
                        + "    if(rst_i) begin\n"
                        + "        level <= 0;\n"
                        + "    end\n"
                        + "    else if(clear_i) begin\n"
                        + "        level <= 0;\n"
                        + "    end\n"
                        + "    else begin\n"
                        + "        level <= level + (write_valid_i ? 1 : 0) - (read_valid_i ? 1 : 0);\n"
                        + "    end\n"
                        + "end\n"
                  : "")
        + "`ifndef SYNTHESIS\n"
        + "always @(posedge clk_i) if (!rst_i) begin\n"
        + "    if (is_empty && read_valid_i) begin\n"
        + "        $display(\"ERROR: SimpleFIFO underflow (%m)\");\n"
        + "        $stop;\n"
        + "    end\n"
        + "    if (!not_full && !read_valid_i && write_valid_i) begin\n"
        + "        $display(\"ERROR: SimpleFIFO overflow (%m)\");\n"
        + "        $stop;\n"
        + "    end\n"
        + "end\n"
        + "`endif\n"
        + "\n"
        + "always @(posedge clk_i) begin\n"
        + "    if(rst_i) begin\n"
        + "        write_pointer <= 0;\n"
        + "    end\n"
        + "    else if(clear_i) begin\n"
        + "        write_pointer <= 0;\n"
        + "    end\n"
        + "    else if(write_valid_i" + (hasWriteFront ? " && !write_front_i" : "") + ") begin\n"
        + "        write_pointer <= nextPointer(write_pointer);\n"
        + "    end\n"
        + "end\n"
        + "\n"
        + "always @(posedge clk_i) begin\n"
        + "    if(rst_i) begin\n"
        + "        read_pointer <= 0;\n"
        + "    end\n"
        + "    else if(clear_i) begin\n"
        + "        read_pointer <=0;\n"
        + "    end\n"
        + "    else if(read_valid_i) begin\n"
        + "        " +
        (hasWriteFront ? "if(!(write_valid_i && write_front_i))" // increment due to read, decrement due to write (+-0)
                       : "") +
        "read_pointer <= nextPointer(read_pointer);\n"
        + "    end\n" +
        (hasWriteFront ? "    else if(write_valid_i && write_front_i) begin\n"
                             + "        read_pointer <= prevPointer(read_pointer);\n"
                             + "    end\n"
                       : "") +
        "end "
        + "endmodule\n"
        + "\n";
    return FIFOModuleContent;
  }

  private String ShiftModule(boolean laterFlushes) {
    String returnStr = "";
    //		List<PipelineStage> startSpawnStagesList = this.core.GetStartSpawnStages().asList();
    //		assert(startSpawnStagesList.size() > 0);
    //		assert(startSpawnStagesList.stream().allMatch(startSpawnStage -> startSpawnStage.getStagePos() ==
    // startSpawnStagesList.get(0).getStagePos())); 		if(startSpawnStagesList.stream().anyMatch(startSpawnStage ->
    //			startSpawnStage.getNext().stream().anyMatch(postStage -> postStage.getKind() == StageKind.Core))
    //			) //roughly: startSpawnStage < maxStage
    //			returnStr += "`define LATER_FLUSHES\n";
    returnStr += "module " + ShiftmoduleName + (laterFlushes ? "_LateFlush" : "") + " #(\n"
                 + "    parameter NR_ELEMENTS,\n" // 64
                 + "    parameter DATAW,\n"       // 5
                 + "    parameter START_STAGE,\n" // startSpawnStagesList.get(0).getStagePos()
                 + "    parameter WB_STAGE\n"     // this.core.maxStage
                 + ")(\n"
                 + "    input                 clk_i,\n"
                 + "    input                 rst_i,\n"
                 + "    input                 kill_i,\n"
                 + "    input                 fence_i,\n"
                 + "    input  [DATAW-1:0]    data_i,\n" +
                 (laterFlushes ? "    input  [WB_STAGE-START_STAGE:1] flush_i,\n" : "") //`ifdef LATER_FLUSHES .. `endif
                 + "    input [WB_STAGE-START_STAGE:0] stall_i,\n"
                 + "    output stall_o,\n"
                 + "    output [DATAW-1:0]  data_o\n"
                 + "    );\n"
                 + "\n"
                 + "reg [DATAW-1:0] shift_reg [NR_ELEMENTS:1] ;\n"
                 + "reg is_active; \n"
                 + "always @(*) begin  \n"
                 + "  is_active = 0;\n"
                 + "  for(int i=1;i<=NR_ELEMENTS;i = i+1) begin \n"
                 + "    if(|shift_reg[i])\n"
                 + "      is_active = 1;\n"
                 + "  end\n"
                 + "end\n"
                 + "assign stall_o = is_active & fence_i;// stall when fence and active shift reg\n"
                 + "always @(posedge clk_i) begin\n"
                 + "   if(!stall_i[0])  shift_reg[1] <= data_i;\n"
                 + "   if((" + (laterFlushes ? "flush_i[1] && " : "") +
                 ("stall_i[0]) || ( stall_i[0] && !stall_i[1])) //  (flush_i[0] && !stall_i[0]) not needed, data_i should be zero in "
                  + "case of flush for shift valid bits in case of flush\n") +
                 "        shift_reg[1] <= 0 ;\n"
                 + "   for(int i=2;i<=NR_ELEMENTS;i = i+1) begin\n"
                 + "        if((i+START_STAGE)<=WB_STAGE) begin\n"
                 + "          if((" + (laterFlushes ? "flush_i[i] && " : "") + "stall_i[i-1]) || (" +
                 (laterFlushes ? "flush_i[i-1] && " : "") + "!stall_i[i-1]) || ( stall_i[i-1] && !stall_i[i]))\n"
                 + "            shift_reg[i] <=0;\n"
                 + "          else if(!stall_i[i-1])\n"
                 + "            shift_reg[i] <= shift_reg[i-1];\n"
                 + "     end else\n"
                 + "          shift_reg[i] <= shift_reg[i-1];\n"
                 + "   end\n"
                 + "   if(rst_i || kill_i) begin\n"
                 + "     for(int i=1;i<=NR_ELEMENTS;i=i+1)\n"
                 + "       shift_reg[i] <= 0;\n"
                 + "   end\n"
                 + "end\n "
                 + "assign data_o = shift_reg[NR_ELEMENTS];\n"
                 + "endmodule\n";
    return returnStr;
  }

  private String CounterModule() {
    String CounterModuleContent = "\n"
                                  + "module " + CountermoduleName + " #(\n"
                                  + "	parameter NR_CYCLES = 64\n"
                                  + ")(\n"
                                  + "	input 				clk_i, \n"
                                  + "	input 				rst_i, \n"
                                  + " input               stall_i,\n"
                                  + "	input 				write_valid_i, \n"
                                  + "	output          	zero_o\n"
                                  + "\n"
                                  + ");\n"
                                  + "\n"
                                  + "reg [$clog2(NR_CYCLES)-1:0] counter;\n"
                                  + " \n"
                                  + "always @(posedge clk_i) begin  \n"
                                  + "	if(rst_i) \n"
                                  + "	 counter <= 0; \n"
                                  + " else if(write_valid_i && !stall_i && counter==0)   \n"
                                  + "	 counter <= NR_CYCLES-1; \n"
                                  + " else if(counter>0)   \n"
                                  + "	 counter <= counter-1; \n"
                                  + "end \n"
                                  + "assign zero_o = (counter == 0 );"
                                  + "endmodule\n"
                                  + "\n";
    return CounterModuleContent;
  }

  @Override
  public Optional<NodeLogicBuilder> implement(Key nodeKey) {
    if (!nodeKey.getPurpose().equals(Purpose.HDL_MODULE) || !nodeKey.getISAX().isEmpty() || nodeKey.getAux() != 0)
      return Optional.empty();
    boolean withLateFlush = false;
    if (nodeKey.getNode().name.startsWith(FIFOmoduleName)) {
      EnumSet<FIFOFeature> features = getFeaturesFromFIFONode(nodeKey.getNode());
      String fifoModuleName = nodeKey.getNode().name;
      assert (fifoModuleName.equals(makeFIFONode(features.toArray(new FIFOFeature[features.size()])).name));
      return Optional.of(NodeLogicBuilder.fromFunction("StandardModulesStrategy_SimpleFIFO", registry -> {
        NodeLogicBlock ret = new NodeLogicBlock();
        ret.otherModules = FIFOModule(fifoModuleName, features);
        ret.outputs.add(new NodeInstanceDesc(nodeKey, fifoModuleName, ExpressionType.AnyExpression));
        return ret;
      }));
    } else if (nodeKey.getNode().name.equals("SimpleShift") ||
               (nodeKey.getNode().name.equals("SimpleShift_LateFlush") && (withLateFlush = true))) {
      final boolean withLateFlush_ = withLateFlush;
      return Optional.of(NodeLogicBuilder.fromFunction("StandardModulesStrategy_" + nodeKey.getNode().name, registry -> {
        NodeLogicBlock ret = new NodeLogicBlock();
        ret.otherModules = ShiftModule(withLateFlush_);
        ret.outputs.add(new NodeInstanceDesc(nodeKey, ShiftmoduleName, ExpressionType.AnyExpression));
        return ret;
      }));
    } else if (nodeKey.getNode().name.equals("SimpleCounter")) {
      return Optional.of(NodeLogicBuilder.fromFunction("StandardModulesStrategy_SimpleCounter", registry -> {
        NodeLogicBlock ret = new NodeLogicBlock();
        ret.otherModules = CounterModule();
        ret.outputs.add(new NodeInstanceDesc(nodeKey, FIFOmoduleName, ExpressionType.AnyExpression));
        return ret;
      }));
    }

    return Optional.empty();
  }
}
