module ISAX_lwdotprod_bias(	// @[outputs/run_12/scheduling_solutions.mlir:2:3]
  input  wire        clk_i,
                     rst_i,
  input  wire [31:0] RdRS1_DOTPBSET_3_i,
                     RdRS2_DOTPBSET_3_i,
  output wire [15:0] WrBIASES_DOTPBSET_3_o,
  output wire        WrBIASES_validReq_DOTPBSET_3_o,
  input  wire        RdStall_DOTPBSET_3_i,
                     RdIValid_DOTPBSET_3_i,
  input  wire [15:0] RdBIASES_LWDOTPB_3_i, //4
  input  wire [31:0] RdRS1_LWDOTPB_3_i, //4
                     RdInstr_LWDOTPB_3_i, //4
  input  wire [31:0] RdADDR_LWDOTPB_3_i, //4
  output wire [31:0] WrRD_spawn_LWDOTPB_5_o,
  output wire        WrRD_spawn_validReq_LWDOTPB_5_o,
  input  wire        RdStall_LWDOTPB_3_i,
                     RdFlush_LWDOTPB_3_i,
                     RdIValid_LWDOTPB_3_i,
                     RdStall_LWDOTPB_4_i,
                     RdIValid_LWDOTPB_4_i,
  input  wire [31:0] RdMem_spawn_LWDOTPB_5_i,
  output wire [31:0] RdMem_spawn_addr_LWDOTPB_5_o,
  output wire        RdMem_spawn_validReq_LWDOTPB_5_o,
  output wire [2:0]  RdMem_spawn_size_LWDOTPB_5_o,
  input wire         RdMem_spawn_validResp_LWDOTPB_5_i,
  output wire        WrCommit_spawn_LWDOTPB_5_o,
  output wire        WrCommit_spawn_validReq_LWDOTPB_5_o,
  output wire        RdAnyValid_LWDOTPB_5_o,
  input  wire [31:0] RdRS1_DOTPBSETADDR_3_i,
  output wire [31:0] WrADDR_DOTPBSETADDR_3_o,
  output wire        WrADDR_validReq_DOTPBSETADDR_3_o,
  input  wire [31:0] RdInstr_DOTPBINCRADDR_3_i,
                     RdADDR_DOTPBINCRADDR_3_i,
  output wire [31:0] WrADDR_DOTPBINCRADDR_3_o,
  output wire        WrADDR_validReq_DOTPBINCRADDR_3_o,
  input  wire        RdStall_DOTPBINCRADDR_3_i,
                     RdIValid_DOTPBINCRADDR_3_i
);
  `ifdef COCOTB_SIM
    `ifndef VERILATOR
      initial begin
        $dumpfile("dotprod_bias.vcd"); $dumpvars;
      end // initial
    `endif // not def VERILATOR
  `endif // COCOTB_SIM

  wire _GEN;
  wire _GEN_0;
  wire _GEN_1;
  assign _GEN = RdIValid_DOTPBSET_3_i;
  assign _GEN_0 = RdIValid_LWDOTPB_3_i;
  assign _GEN_1 = RdIValid_LWDOTPB_4_i;

  //Post-read: Valid signals for computation pipeline
  logic computation_valid_pipe_0;
  logic computation_valid_pipe_1;
  logic computation_valid_pipe_2;
  logic valid_spawn_entry;
  always_ff @(posedge clk_i)
  	valid_spawn_entry <= rst_i ? 1'b0 : (RdIValid_LWDOTPB_4_i && !RdStall_LWDOTPB_4_i);

  //Issue a RdMem_spawn ("stage 5") in stage 3 already
  // -> RdMem_spawn_validResp triggers actual execution
  assign RdMem_spawn_size_LWDOTPB_5_o = 3'b010;
  assign RdMem_spawn_validReq_LWDOTPB_5_o = RdIValid_LWDOTPB_3_i && !RdStall_LWDOTPB_3_i && !RdFlush_LWDOTPB_3_i; //RdIValid_LWDOTPB_4_i && !RdStall_LWDOTPB_4_i
  assign RdMem_spawn_addr_LWDOTPB_5_o =
    {{20{RdInstr_LWDOTPB_3_i[31]}}, RdInstr_LWDOTPB_3_i[31:20]} + RdADDR_LWDOTPB_3_i; //RdInstr_LWDOTPB_4_i //RdADDR_LWDOTPB_4_i

  //Use ISAX_lwdotprod_bias_fifo to store the reg operand
  logic opB_fifo_notEmpty;
  logic opB_fifo_notFull;
  logic [32+16-1:0] opB_fifo_out;
  ISAX_lwdotprod_bias_fifo#(8, 32+16, 1) opB_fifo(
    .clk (clk_i),
    .rst (rst_i),
    .clear_i (1'b0),
    .enq_valid_i (RdIValid_LWDOTPB_3_i && !RdStall_LWDOTPB_3_i && !RdFlush_LWDOTPB_3_i), //RdIValid_LWDOTPB_4_i && !RdStall_LWDOTPB_4_i
    .deq_valid_i (computation_valid_pipe_0),
    .data_i ({RdBIASES_LWDOTPB_3_i,RdRS1_LWDOTPB_3_i}), //({RdBIASES_LWDOTPB_4_i,RdRS1_LWDOTPB_4_i}),
    .not_empty (opB_fifo_notEmpty),
    .not_full (opB_fifo_notFull),
    .data_o (opB_fifo_out)
  );
  //Pipeline into stage 5 (4 might also work?), issue a RdMem_spawn
  //      Use ISAX_lwdotprod_bias_fifo to store the reg operand
  //      Wait for RdMem_spawn result, then pass result and reg operand into SharingGroup module
  ISAX_dotprod_bias_SharingGroup_2 inst_ISAX_dotprod_bias_SharingGroup_2 (
    .clk                  (clk_i),
    .rst                  (rst_i),
    .ready                (/* unused */),
    .RdBIASES_DOTPB_2_i   (opB_fifo_out[32+16-1:32]),
    .RdRS1_DOTPB_2_i      (RdMem_spawn_LWDOTPB_5_i),
    .RdRS2_DOTPB_2_i      (opB_fifo_out[31:0]),
    .WrRD_spawn_DOTPB_4_o (WrRD_spawn_LWDOTPB_5_o),
    //.RdStall_DOTPB_2_i    (RdStall_LWDOTPB_3_i),
    .RdStall_DOTPB_2_i    (1'b0),
    .RdIValid_DOTPB_2_i   (computation_valid_pipe_0),
    //.RdStall_DOTPB_3_i    (RdStall_LWDOTPB_4_i),
    .RdStall_DOTPB_3_i    (1'b0),
    .RdIValid_DOTPB_3_i   (computation_valid_pipe_1)
  );
  assign computation_valid_pipe_0 = RdMem_spawn_validResp_LWDOTPB_5_i;
  always_ff @(posedge clk_i)
  	computation_valid_pipe_1 <= rst_i ? 1'b0 : computation_valid_pipe_0;
  always_ff @(posedge clk_i)
  	computation_valid_pipe_2 <= rst_i ? 1'b0 : computation_valid_pipe_1;

  assign WrRD_spawn_validReq_LWDOTPB_5_o = computation_valid_pipe_2;
  assign WrCommit_spawn_LWDOTPB_5_o = 1'b1;
  assign WrCommit_spawn_validReq_LWDOTPB_5_o = computation_valid_pipe_2;
  assign RdAnyValid_LWDOTPB_5_o = valid_spawn_entry | opB_fifo_notEmpty | computation_valid_pipe_0 | computation_valid_pipe_1 | computation_valid_pipe_2;

  assign WrBIASES_DOTPBSET_3_o = {RdRS2_DOTPBSET_3_i[7:0], RdRS1_DOTPBSET_3_i[7:0]};	// @[outputs/run_12/scheduling_solutions.mlir:25:16, :27:16, :28:16]
  assign WrBIASES_validReq_DOTPBSET_3_o = 1'h1;

  assign WrADDR_DOTPBSETADDR_3_o = RdRS1_DOTPBSETADDR_3_i;
  assign WrADDR_validReq_DOTPBSETADDR_3_o = 1'h1;

  assign WrADDR_DOTPBINCRADDR_3_o =
    {{20{RdInstr_DOTPBINCRADDR_3_i[31]}}, RdInstr_DOTPBINCRADDR_3_i[31:20]}
    + RdADDR_DOTPBINCRADDR_3_i;	// @[outputs/run_13/scheduling_solutions.mlir:328:16, :330:16, :331:16, :332:16, :333:16]
  assign WrADDR_validReq_DOTPBINCRADDR_3_o = 1'h1;
endmodule

module ISAX_lwdotprod_bias_fifo #(
    parameter int DEPTH=1,
    parameter int WIDTH=1,
    parameter bit SHIFT=0 //If 1: Implement as shift reg (more complex write path, very simple read path)
)(
    input                 clk,
    input                 rst,
    input                 clear_i,
    input                 enq_valid_i,
    input                 deq_valid_i,
    input  [WIDTH-1:0]    data_i,
    output                not_empty,
    output                not_full,
    output [WIDTH-1:0]    data_o

);

reg [WIDTH-1:0] FIFOContent [DEPTH-1:0];

typedef logic [$clog2(DEPTH)-1:0] FIFOPointer_t;
localparam FIFOPointer_t MAX_PTR_VAL = DEPTH-1;
localparam FIFOPointer_t MIN_PTR_VAL = 0;
localparam FIFOPointer_t PTR_INC = 1;
FIFOPointer_t write_pointer;
FIFOPointer_t read_pointer;
function FIFOPointer_t nextPointer(input FIFOPointer_t val);
    if ($clog2(DEPTH) == $clog2(DEPTH+1)
            && val == MAX_PTR_VAL)
        nextPointer = MIN_PTR_VAL; // explicit wrap if DEPTH is not a power of 2
    else
        nextPointer = val + PTR_INC;
endfunction
function FIFOPointer_t prevPointer(input FIFOPointer_t val);
    if ($clog2(DEPTH) == $clog2(DEPTH+1)
            && val == MIN_PTR_VAL)
        prevPointer = MAX_PTR_VAL; // explicit wrap if DEPTH is not a power of 2
    else
        prevPointer = val - PTR_INC;
endfunction

reg is_empty;

if(SHIFT) begin : gen_shiftregs
    always_ff @(posedge clk) begin
	    for (int i = 0; i < DEPTH-1; i=i+1) begin
		    if (deq_valid_i)
			    FIFOContent[i] <= FIFOContent[i+1];
	    end
        if(enq_valid_i)
            FIFOContent[write_pointer - (deq_valid_i ? 1 : 0)] <= data_i;
    end
end else begin : gen_nonshiftregs
    always @(posedge clk) begin    
        if(enq_valid_i)
            FIFOContent[write_pointer] <= data_i;
    end
end
assign data_o = FIFOContent[read_pointer];
assign not_empty = !is_empty;
assign not_full = write_pointer != read_pointer || is_empty;
always @(posedge clk) begin
    if(rst) begin
        is_empty <= 1;
    end
    else if(clear_i) begin
        is_empty <= 1;
    end
    else if(enq_valid_i) begin
        is_empty <= 0;
    end
    else if(deq_valid_i && write_pointer == nextPointer(read_pointer)) begin
        is_empty <= 1;
    end
end
`ifndef SYNTHESIS
always @(posedge clk) if (!rst) begin
    if (is_empty && deq_valid_i) begin
        $display("ERROR: FIFO underflow (%m)");
        $stop;
    end
    if (!not_full && !deq_valid_i && enq_valid_i) begin
        $display("ERROR: FIFO overflow (%m)");
        $stop;
    end
end
`endif

always @(posedge clk) begin
    if(rst) begin
        write_pointer <= 0;
    end
    else if(clear_i) begin
        write_pointer <= 0;
    end
    else if(enq_valid_i) begin
        if(!SHIFT || !deq_valid_i)
            write_pointer <= nextPointer(write_pointer);
    end
    else if(SHIFT && deq_valid_i) begin
        write_pointer <= prevPointer(write_pointer);
    end
end

if(SHIFT) begin
	assign read_pointer = 0;
end else begin
	always @(posedge clk) begin
		if(rst) begin
		    read_pointer <= 0;
		end
		else if(clear_i) begin
		    read_pointer <=0;
		end
		else if(deq_valid_i) begin
		    read_pointer <= nextPointer(read_pointer);
		end
	end
end

endmodule

module ISAX_dotprod_bias_SharingGroup_2(
  input  wire        clk,
                     rst,
  output wire        ready,
  input  wire [15:0] RdBIASES_DOTPB_2_i,
  input  wire [31:0] RdRS1_DOTPB_2_i,
                     RdRS2_DOTPB_2_i,
  output wire [31:0] WrRD_spawn_DOTPB_4_o,
  input  wire        RdStall_DOTPB_2_i,
                     RdIValid_DOTPB_2_i,
                     RdStall_DOTPB_3_i,
                     RdIValid_DOTPB_3_i
);

  wire [17:0] _inst_mul_18_res;	// @[outputs/run_12/scheduling_solutions.mlir:215:17]
  wire [17:0] _inst_mul_18_res_0;	// @[outputs/run_12/scheduling_solutions.mlir:208:17]
  wire [17:0] _inst_mul_18_res_1;	// @[outputs/run_12/scheduling_solutions.mlir:201:17]
  wire [17:0] _inst_mul_18_res_2;	// @[outputs/run_12/scheduling_solutions.mlir:194:16]
  wire [8:0]  _inst_sub_9_res;	// @[outputs/run_12/scheduling_solutions.mlir:183:17]
  wire [8:0]  _inst_sub_9_res_0;	// @[outputs/run_12/scheduling_solutions.mlir:179:17]
  wire [8:0]  _inst_sub_9_res_1;	// @[outputs/run_12/scheduling_solutions.mlir:175:17]
  wire [8:0]  _inst_sub_9_res_2;	// @[outputs/run_12/scheduling_solutions.mlir:171:17]
  wire [8:0]  _inst_sub_9_res_3;	// @[outputs/run_12/scheduling_solutions.mlir:167:17]
  wire [8:0]  _inst_sub_9_res_4;	// @[outputs/run_12/scheduling_solutions.mlir:163:17]
  wire [8:0]  _inst_sub_9_res_5;	// @[outputs/run_12/scheduling_solutions.mlir:159:17]
  wire [8:0]  _inst_sub_9_res_6;	// @[outputs/run_12/scheduling_solutions.mlir:151:17]
  wire        masked_stall_2_DOTPB =
    RdStall_DOTPB_2_i & RdIValid_DOTPB_2_i | RdStall_DOTPB_3_i & RdIValid_DOTPB_3_i;
  wire        masked_stall_3_DOTPB = RdStall_DOTPB_3_i & RdIValid_DOTPB_3_i;
  wire [8:0]  _GEN = {RdBIASES_DOTPB_2_i[7], RdBIASES_DOTPB_2_i[7:0]};	// @[outputs/run_12/scheduling_solutions.mlir:146:16, :149:16, :150:16]
  wire [8:0]  _GEN_0 = {RdBIASES_DOTPB_2_i[15], RdBIASES_DOTPB_2_i[15:8]};	// @[outputs/run_12/scheduling_solutions.mlir:154:17, :157:17, :158:17]
  sub_9 inst_sub_9 (	// @[outputs/run_12/scheduling_solutions.mlir:151:17]
    .clk     (clk),
    .arg1    ({RdRS1_DOTPB_2_i[7], RdRS1_DOTPB_2_i[7:0]}),	// @[outputs/run_12/scheduling_solutions.mlir:145:16, :147:16, :148:16]
    .arg2    (_GEN),	// @[outputs/run_12/scheduling_solutions.mlir:150:16]
    .stall_0 (masked_stall_2_DOTPB),
    .res     (_inst_sub_9_res_6)
  );	// @[outputs/run_12/scheduling_solutions.mlir:151:17]
  sub_9 inst_sub_9_0 (	// @[outputs/run_12/scheduling_solutions.mlir:159:17]
    .clk     (clk),
    .arg1    ({RdRS2_DOTPB_2_i[7], RdRS2_DOTPB_2_i[7:0]}),	// @[outputs/run_12/scheduling_solutions.mlir:153:17, :155:17, :156:17]
    .arg2    (_GEN_0),	// @[outputs/run_12/scheduling_solutions.mlir:158:17]
    .stall_0 (masked_stall_2_DOTPB),
    .res     (_inst_sub_9_res_5)
  );	// @[outputs/run_12/scheduling_solutions.mlir:159:17]
  sub_9 inst_sub_9_1 (	// @[outputs/run_12/scheduling_solutions.mlir:163:17]
    .clk     (clk),
    .arg1    ({RdRS1_DOTPB_2_i[15], RdRS1_DOTPB_2_i[15:8]}),	// @[outputs/run_12/scheduling_solutions.mlir:160:17, :161:17, :162:17]
    .arg2    (_GEN),	// @[outputs/run_12/scheduling_solutions.mlir:150:16]
    .stall_0 (masked_stall_2_DOTPB),
    .res     (_inst_sub_9_res_4)
  );	// @[outputs/run_12/scheduling_solutions.mlir:163:17]
  sub_9 inst_sub_9_2 (	// @[outputs/run_12/scheduling_solutions.mlir:167:17]
    .clk     (clk),
    .arg1    ({RdRS2_DOTPB_2_i[15], RdRS2_DOTPB_2_i[15:8]}),	// @[outputs/run_12/scheduling_solutions.mlir:164:17, :165:17, :166:17]
    .arg2    (_GEN_0),	// @[outputs/run_12/scheduling_solutions.mlir:158:17]
    .stall_0 (masked_stall_2_DOTPB),
    .res     (_inst_sub_9_res_3)
  );	// @[outputs/run_12/scheduling_solutions.mlir:167:17]
  sub_9 inst_sub_9_3 (	// @[outputs/run_12/scheduling_solutions.mlir:171:17]
    .clk     (clk),
    .arg1    ({RdRS1_DOTPB_2_i[23], RdRS1_DOTPB_2_i[23:16]}),	// @[outputs/run_12/scheduling_solutions.mlir:168:17, :169:17, :170:17]
    .arg2    (_GEN),	// @[outputs/run_12/scheduling_solutions.mlir:150:16]
    .stall_0 (masked_stall_2_DOTPB),
    .res     (_inst_sub_9_res_2)
  );	// @[outputs/run_12/scheduling_solutions.mlir:171:17]
  sub_9 inst_sub_9_4 (	// @[outputs/run_12/scheduling_solutions.mlir:175:17]
    .clk     (clk),
    .arg1    ({RdRS2_DOTPB_2_i[23], RdRS2_DOTPB_2_i[23:16]}),	// @[outputs/run_12/scheduling_solutions.mlir:172:17, :173:17, :174:17]
    .arg2    (_GEN_0),	// @[outputs/run_12/scheduling_solutions.mlir:158:17]
    .stall_0 (masked_stall_2_DOTPB),
    .res     (_inst_sub_9_res_1)
  );	// @[outputs/run_12/scheduling_solutions.mlir:175:17]
  sub_9 inst_sub_9_5 (	// @[outputs/run_12/scheduling_solutions.mlir:179:17]
    .clk     (clk),
    .arg1    ({RdRS1_DOTPB_2_i[31], RdRS1_DOTPB_2_i[31:24]}),	// @[outputs/run_12/scheduling_solutions.mlir:176:17, :177:17, :178:17]
    .arg2    (_GEN),	// @[outputs/run_12/scheduling_solutions.mlir:150:16]
    .stall_0 (masked_stall_2_DOTPB),
    .res     (_inst_sub_9_res_0)
  );	// @[outputs/run_12/scheduling_solutions.mlir:179:17]
  sub_9 inst_sub_9_6 (	// @[outputs/run_12/scheduling_solutions.mlir:183:17]
    .clk     (clk),
    .arg1    ({RdRS2_DOTPB_2_i[31], RdRS2_DOTPB_2_i[31:24]}),	// @[outputs/run_12/scheduling_solutions.mlir:180:17, :181:17, :182:17]
    .arg2    (_GEN_0),	// @[outputs/run_12/scheduling_solutions.mlir:158:17]
    .stall_0 (masked_stall_2_DOTPB),
    .res     (_inst_sub_9_res)
  );	// @[outputs/run_12/scheduling_solutions.mlir:183:17]
  mul_18 inst_mul_18 (	// @[outputs/run_12/scheduling_solutions.mlir:194:16]
    .clk     (clk),
    .arg1    ({{9{_inst_sub_9_res_6[8]}}, _inst_sub_9_res_6}),	// @[outputs/run_12/scheduling_solutions.mlir:151:17, :188:16, :189:16, :190:16]
    .arg2    ({{9{_inst_sub_9_res_5[8]}}, _inst_sub_9_res_5}),	// @[outputs/run_12/scheduling_solutions.mlir:159:17, :191:16, :192:16, :193:16]
    .stall_0 (masked_stall_3_DOTPB),
    .res     (_inst_mul_18_res_2)
  );	// @[outputs/run_12/scheduling_solutions.mlir:194:16]
  mul_18 inst_mul_18_0 (	// @[outputs/run_12/scheduling_solutions.mlir:201:17]
    .clk     (clk),
    .arg1    ({{9{_inst_sub_9_res_4[8]}}, _inst_sub_9_res_4}),	// @[outputs/run_12/scheduling_solutions.mlir:163:17, :195:16, :196:17, :197:17]
    .arg2    ({{9{_inst_sub_9_res_3[8]}}, _inst_sub_9_res_3}),	// @[outputs/run_12/scheduling_solutions.mlir:167:17, :198:17, :199:17, :200:17]
    .stall_0 (masked_stall_3_DOTPB),
    .res     (_inst_mul_18_res_1)
  );	// @[outputs/run_12/scheduling_solutions.mlir:201:17]
  mul_18 inst_mul_18_1 (	// @[outputs/run_12/scheduling_solutions.mlir:208:17]
    .clk     (clk),
    .arg1    ({{9{_inst_sub_9_res_2[8]}}, _inst_sub_9_res_2}),	// @[outputs/run_12/scheduling_solutions.mlir:171:17, :202:17, :203:17, :204:17]
    .arg2    ({{9{_inst_sub_9_res_1[8]}}, _inst_sub_9_res_1}),	// @[outputs/run_12/scheduling_solutions.mlir:175:17, :205:17, :206:17, :207:17]
    .stall_0 (masked_stall_3_DOTPB),
    .res     (_inst_mul_18_res_0)
  );	// @[outputs/run_12/scheduling_solutions.mlir:208:17]
  mul_18 inst_mul_18_2 (	// @[outputs/run_12/scheduling_solutions.mlir:215:17]
    .clk     (clk),
    .arg1    ({{9{_inst_sub_9_res_0[8]}}, _inst_sub_9_res_0}),	// @[outputs/run_12/scheduling_solutions.mlir:179:17, :209:17, :210:17, :211:17]
    .arg2    ({{9{_inst_sub_9_res[8]}}, _inst_sub_9_res}),	// @[outputs/run_12/scheduling_solutions.mlir:183:17, :212:17, :213:17, :214:17]
    .stall_0 (masked_stall_3_DOTPB),
    .res     (_inst_mul_18_res)
  );	// @[outputs/run_12/scheduling_solutions.mlir:215:17]
  assign ready = 1'h1;
  assign WrRD_spawn_DOTPB_4_o =
    {{14{_inst_mul_18_res_2[17]}}, _inst_mul_18_res_2}
    + {{14{_inst_mul_18_res_1[17]}}, _inst_mul_18_res_1}
    + {{14{_inst_mul_18_res_0[17]}}, _inst_mul_18_res_0}
    + {{14{_inst_mul_18_res[17]}}, _inst_mul_18_res};	// @[outputs/run_12/scheduling_solutions.mlir:194:16, :201:17, :208:17, :215:17, :220:16, :221:16, :222:16, :223:16, :224:16, :225:16, :226:16, :227:16, :228:17, :229:17, :230:17, :231:17, :232:17, :233:17, :234:17]
endmodule

module sub_9(
  input  wire       clk,
  input  wire [8:0] arg1,
                    arg2,
  input  wire       stall_0,
  output wire [8:0] res
);

  reg [8:0] mockup_stage_1;
  always_ff @(posedge clk)
    mockup_stage_1 <= stall_0 ? mockup_stage_1 : arg1 - arg2;	// @[outputs/run_12/scheduling_solutions.mlir:151:17]
  assign res = mockup_stage_1;
endmodule

module mul_18(
  input  wire        clk,
  input  wire [17:0] arg1,
                     arg2,
  input  wire        stall_0,
  output wire [17:0] res
);

  reg [17:0] mockup_stage_1;
  always_ff @(posedge clk)
    mockup_stage_1 <= stall_0 ? mockup_stage_1 : arg1 * arg2;	// @[outputs/run_12/scheduling_solutions.mlir:194:16]
  assign res = mockup_stage_1;
endmodule

