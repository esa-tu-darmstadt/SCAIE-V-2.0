 

// SystemVerilog file 
 
module SCAL (
    // Interface to the ISAX Module
    
    output  [32 -1 : 0] RdPC_1_o,// ISAX
    input  [32 -1 : 0] WrMem_ISAX3_2_i,// ISAX
    input  [32 -1 : 0] WrMem_addr_ISAX3_2_i,// ISAX
    input   WrMem_validReq_ISAX3_2_i,// ISAX
    input  [32 -1 : 0] WrRD_ISAX1_2_i,// ISAX
    input  [32 -1 : 0] WrRD_ISAX2_3_i,// ISAX
    input   WrRD_validReq_ISAX2_3_i,// ISAX
    output   RdStall_3_o,// ISAX
    output  [32 -1 : 0] RdInstr_2_o,// ISAX
    input  [32 -1 : 0] WrPC_ISAX7_2_i,// ISAX
    input   WrPC_validReq_ISAX7_2_i,// ISAX
    input   WrStall_ISAX7_3_i,// ISAX
    output  [32 -1 : 0] RdRS1_2_o,// ISAX
    output  [32 -1 : 0] RdRS1_3_o,// ISAX
    output  [32 -1 : 0] RdRS2_2_o,// ISAX
    output  [32 -1 : 0] RdRS2_3_o,// ISAX
    output   RdIValid_ISAX3_2_o,// ISAX
    output   RdIValid_ISAX8_2_o,// ISAX
    output   RdIValid_ISAX5_2_o,// ISAX
    output   RdIValid_ISAX2_3_o,// ISAX
    output  [32 -1 : 0] RdMem_2_o,// ISAX
    input  [32 -1 : 0] WrMem_spawn_ISAX6_5_i,// ISAX
    output   WrMem_spawn_validResp_ISAX6_5_o,// ISAX
    input  [32 -1 : 0] WrMem_spawn_addr_ISAX6_5_i,// ISAX
    input   WrMem_spawn_validReq_ISAX6_5_i,// ISAX
    output  [32 -1 : 0] RdMem_spawn_ISAX5_5_o,// ISAX
    output   RdMem_spawn_validResp_ISAX5_5_o,// ISAX
    input  [32 -1 : 0] RdMem_spawn_addr_ISAX5_5_i,// ISAX
    input   RdMem_spawn_validReq_ISAX5_5_i,// ISAX
    input  [32 -1 : 0] WrRD_spawn_ISAX5_5_i,// ISAX
    output  [5 -1 : 0] Commited_rd_spawn_WrRD_spawn_5_o,// ISAX
    output   Commited_rd_spawn_validReq_WrRD_spawn_5_o,// ISAX
    
    
    // Interface to the Core
    
    input  [32 -1 : 0] RdPC_1_i,// ISAX
    output reg  WrMem_validReq_1_o,// ISAX
    output reg [32 -1 : 0] WrMem_2_o,// ISAX
    output reg [32 -1 : 0] WrMem_addr_2_o,// ISAX
    output reg  WrMem_validReq_2_o,// ISAX
    output reg  WrMem_addr_valid_2_o,// ISAX
    output reg [32 -1 : 0] WrRD_2_o,// ISAX
    output reg  WrRD_validReq_2_o,// ISAX
    output reg [32 -1 : 0] WrRD_3_o,// ISAX
    output reg  WrRD_validReq_3_o,// ISAX
    input   RdStall_3_i,// ISAX
    input  [32 -1 : 0] RdInstr_2_i,// ISAX
    output reg [32 -1 : 0] WrPC_2_o,// ISAX
    output reg  WrPC_validReq_2_o,// ISAX
    output reg  WrStall_3_o,// ISAX
    input  [32 -1 : 0] RdRS1_2_i,// ISAX
    input  [32 -1 : 0] RdRS1_3_i,// ISAX
    input  [32 -1 : 0] RdRS2_2_i,// ISAX
    input  [32 -1 : 0] RdRS2_3_i,// ISAX
    output reg  RdMem_validReq_1_o,// ISAX
    input  [32 -1 : 0] RdMem_2_i,// ISAX
    output reg  RdMem_validReq_2_o,// ISAX
    output reg  RdMem_addr_valid_2_o,// ISAX
    output reg [32 -1 : 0] WrMem_spawn_5_o,// ISAX
    input   Mem_spawn_validResp_5_i,// ISAX
    output reg [32 -1 : 0] Mem_spawn_addr_5_o,// ISAX
    output reg  Mem_spawn_validReq_5_o,// ISAX
    output reg  Mem_spawn_write_5_o,// ISAX
    input  [32 -1 : 0] RdMem_spawn_5_i,// ISAX
    output reg [32 -1 : 0] WrRD_spawn_5_o,// ISAX
    input   WrRD_spawn_validResp_5_i,// ISAX
    output reg [5 -1 : 0] WrRD_spawn_addr_5_o,// ISAX
    output reg  WrRD_spawn_validReq_5_o,// ISAX
    input   RdFlush_1_i,// ISAX
    input   RdFlush_2_i,// ISAX
    input   RdFlush_3_i,// ISAX
    input   RdFlush_4_i,// ISAX
    input   RdStall_1_i,// ISAX
    input   RdStall_2_i,// ISAX
    input   RdStall_4_i,// ISAX
    input  [32 -1 : 0] RdInstr_1_i,// ISAX
    input  [32 -1 : 0] RdInstr_3_i,// ISAX
    output   WrFlush_0_o,// ISAX
    output   WrFlush_1_o,// ISAX
    output   WrStall_1_o,// ISAX
    output   WrStall_2_o,// ISAX
    output   WrStall_4_o,// ISAX
    input   ISAX_spawnAllowed_2_i,// ISAX
    
    input clk_i,
    input rst_i
    
    
);
// Declare local signals
wire  RdIValid_ISAX4_1_s;
wire  RdIValid_ISAX3_1_s;
wire  RdIValid_ISAX4_2_s;
wire  RdIValid_ISAX3_2_s;
wire  RdIValid_ISAX1_2_s;
wire  RdIValid_ISAX8_2_s;
wire  RdIValid_ISAX7_2_s;
wire  RdIValid_ISAX6_2_s;
wire  RdIValid_ISAX5_2_s;
wire  RdIValid_ISAX2_3_s;
wire  RdIValid_ISAX7_3_s;
wire  WrStall_3_s;
wire [8-1:0] ISAX_spawn_sum_Mem_5_s;
wire  ISAX_fire_s_Mem_5_s;
reg ISAX_fire_r_Mem_5_reg;
reg ISAX_fire2_r_Mem_5_reg;
reg [ 32 -1 : 0 ] WrMem_spawn_ISAX6_5_reg;
reg  Mem_spawn_validResp_ISAX6_5_reg;
reg [ 32 -1 : 0 ] Mem_spawn_addr_ISAX6_5_reg;
reg  Mem_spawn_validReq_ISAX6_5_reg;
reg  Mem_spawn_write_ISAX6_5_reg;
reg [ 32 -1 : 0 ] RdMem_spawn_ISAX5_5_reg;
reg  Mem_spawn_validResp_ISAX5_5_reg;
reg [ 32 -1 : 0 ] Mem_spawn_addr_ISAX5_5_reg;
reg  Mem_spawn_validReq_ISAX5_5_reg;
reg  Mem_spawn_write_ISAX5_5_reg;
wire [8-1:0] ISAX_spawn_sum_WrRD_5_s;
wire  ISAX_fire_s_WrRD_5_s;
reg ISAX_fire_r_WrRD_5_reg;
reg ISAX_fire2_r_WrRD_5_reg;
reg [ 32 -1 : 0 ] WrRD_spawn_ISAX5_5_reg;
reg  WrRD_spawn_validResp_ISAX5_5_reg;
reg [ 5 -1 : 0 ] WrRD_spawn_addr_ISAX5_5_reg;
reg  WrRD_spawn_validReq_ISAX5_5_reg;
wire to_CORE_stall_RS_WrMem_spawn_o_s;
wire [32-1:0] Mem_spawn_addr_ISAX6_5_i;
wire dummyISAX6;
wire Mem_spawn_validReq_ISAX6_5_i_shiftreg_s;
wire to_CORE_stall_RS_RdMem_spawn_o_s;
wire [32-1:0] Mem_spawn_addr_ISAX5_5_i;
wire dummyISAX5;
wire Mem_spawn_validReq_ISAX5_5_i_shiftreg_s;
wire to_CORE_stall_RS_WrRD_spawn_o_s;
wire [5-1:0] WrRD_spawn_addr_ISAX5_5_i;
wire dummyISAX5;
wire WrRD_spawn_validReq_ISAX5_5_i_shiftreg_s;


// Logic
assign RdPC_1_o = RdPC_1_i;
assign RdInstr_2_o = RdInstr_2_i;
assign RdRS1_2_o = RdRS1_2_i;
assign RdRS1_3_o = RdRS1_3_i;
assign RdRS2_2_o = RdRS2_2_i;
assign RdRS2_3_o = RdRS2_3_i;
assign RdMem_2_o = RdMem_2_i;
assign RdIValid_ISAX4_1_s = (  ( RdInstr_1_i [ 14 ]  ==  1'b0 )  &&  ( RdInstr_1_i [ 13 ]  ==  1'b1 )  &&  ( RdInstr_1_i [ 12 ]  ==  1'b0 )  &&  ( RdInstr_1_i [ 6 ]  ==  1'b1 )  &&  ( RdInstr_1_i [ 5 ]  ==  1'b1 )  &&  ( RdInstr_1_i [ 4 ]  ==  1'b1 )  &&  ( RdInstr_1_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_1_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_1_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_1_i [ 0 ]  ==  1'b1 )  ) && !RdFlush_1_i;
assign RdIValid_ISAX3_1_s = (  ( RdInstr_1_i [ 14 ]  ==  1'b0 )  &&  ( RdInstr_1_i [ 13 ]  ==  1'b1 )  &&  ( RdInstr_1_i [ 12 ]  ==  1'b0 )  &&  ( RdInstr_1_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_1_i [ 5 ]  ==  1'b0 )  &&  ( RdInstr_1_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_1_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_1_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_1_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_1_i [ 0 ]  ==  1'b1 )  ) && !RdFlush_1_i;
assign RdIValid_ISAX4_2_s = (  ( RdInstr_2_i [ 14 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 13 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 12 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 6 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 5 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 4 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 0 ]  ==  1'b1 )  ) && !RdFlush_2_i;
assign RdIValid_ISAX3_2_o = RdIValid_ISAX3_2_s;
assign RdIValid_ISAX3_2_s = (  ( RdInstr_2_i [ 14 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 13 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 12 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 5 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 0 ]  ==  1'b1 )  ) && !RdFlush_2_i;
assign RdIValid_ISAX1_2_s = (  ( RdInstr_2_i [ 14 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 13 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 12 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 5 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 0 ]  ==  1'b1 )  ) && !RdFlush_2_i;
assign RdIValid_ISAX8_2_o = RdIValid_ISAX8_2_s;
assign RdIValid_ISAX8_2_s = (  ( RdInstr_2_i [ 14 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 13 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 12 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 5 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 0 ]  ==  1'b1 )  ) && !RdFlush_2_i;
assign RdIValid_ISAX7_2_s = (  ( RdInstr_2_i [ 14 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 13 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 12 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 5 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 0 ]  ==  1'b1 )  ) && !RdFlush_2_i;
assign RdIValid_ISAX6_2_s = (  ( RdInstr_2_i [ 14 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 13 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 12 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 5 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 0 ]  ==  1'b1 )  ) && !RdFlush_2_i;
assign RdIValid_ISAX5_2_o = RdIValid_ISAX5_2_s;
assign RdIValid_ISAX5_2_s = (  ( RdInstr_2_i [ 14 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 13 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 12 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 5 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_2_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_2_i [ 0 ]  ==  1'b1 )  ) && !RdFlush_2_i;
assign RdIValid_ISAX2_3_o = RdIValid_ISAX2_3_s;
assign RdIValid_ISAX2_3_s = (  ( RdInstr_3_i [ 14 ]  ==  1'b0 )  &&  ( RdInstr_3_i [ 13 ]  ==  1'b1 )  &&  ( RdInstr_3_i [ 12 ]  ==  1'b1 )  &&  ( RdInstr_3_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_3_i [ 5 ]  ==  1'b0 )  &&  ( RdInstr_3_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_3_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_3_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_3_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_3_i [ 0 ]  ==  1'b1 )  ) && !RdFlush_3_i;
assign RdIValid_ISAX7_3_s = (  ( RdInstr_3_i [ 14 ]  ==  1'b1 )  &&  ( RdInstr_3_i [ 13 ]  ==  1'b1 )  &&  ( RdInstr_3_i [ 12 ]  ==  1'b1 )  &&  ( RdInstr_3_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_3_i [ 5 ]  ==  1'b1 )  &&  ( RdInstr_3_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_3_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_3_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_3_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_3_i [ 0 ]  ==  1'b1 )  ) && !RdFlush_3_i;
always @(*) begin 
    case(1'b1)
        RdIValid_ISAX4_1_s : RdMem_validReq_1_o = 1'b1;
        default : RdMem_validReq_1_o = 1'b0;
    endcase
end
reg RdMem_validReq_2_reg; // Info: if not really needed, will be optimized away by synth tool 
always @(posedge clk_i) begin 
    if(rst_i)
        RdMem_validReq_2_reg <= 1'b0;
    else if(!RdStall_1_i)
        RdMem_validReq_2_reg <= RdMem_validReq_1_o;
end
always @(*) begin 
    case(1'b1)
        RdIValid_ISAX4_2_s : RdMem_validReq_2_o = 1'b1;
        default : RdMem_validReq_2_o = RdMem_validReq_2_reg && ! RdFlush_2_i;
    endcase
end
reg RdMem_validReq_3_reg; // Info: if not really needed, will be optimized away by synth tool 
always @(posedge clk_i) begin 
    if(rst_i)
        RdMem_validReq_3_reg <= 1'b0;
    else if(!RdStall_2_i)
        RdMem_validReq_3_reg <= RdMem_validReq_2_o;
end
always @(*) begin 
    case(1'b1)
        RdIValid_ISAX3_1_s : WrMem_validReq_1_o = 1'b1;
        default : WrMem_validReq_1_o = 1'b0;
    endcase
end
reg WrMem_validReq_2_reg; // Info: if not really needed, will be optimized away by synth tool 
always @(posedge clk_i) begin 
    if(rst_i)
        WrMem_validReq_2_reg <= 1'b0;
    else if(!RdStall_1_i)
        WrMem_validReq_2_reg <= WrMem_validReq_1_o;
end
always @(*) begin 
    case(1'b1)
        RdIValid_ISAX3_2_s : WrMem_validReq_2_o = WrMem_validReq_ISAX3_2_i;
        default : WrMem_validReq_2_o = WrMem_validReq_2_reg && ! RdFlush_2_i;
    endcase
end
reg WrMem_validReq_3_reg; // Info: if not really needed, will be optimized away by synth tool 
always @(posedge clk_i) begin 
    if(rst_i)
        WrMem_validReq_3_reg <= 1'b0;
    else if(!RdStall_2_i)
        WrMem_validReq_3_reg <= WrMem_validReq_2_o;
end
always @(*)  WrMem_2_o = WrMem_ISAX3_2_i;
always @(*)  WrMem_addr_2_o = WrMem_addr_ISAX3_2_i;
always @(*) begin 
    case(1'b1)
        RdIValid_ISAX3_2_s : WrMem_addr_valid_2_o = 1;
        default : WrMem_addr_valid_2_o = ~1;
    endcase
end
always @(*)  WrRD_2_o = WrRD_ISAX1_2_i;
always @(*) begin 
    case(1'b1)
        RdIValid_ISAX1_2_s : WrRD_validReq_2_o = 1;
        default : WrRD_validReq_2_o = ~1;
    endcase
end
always @(*)  WrRD_3_o = WrRD_ISAX2_3_i;
always @(*) begin 
    case(1'b1)
        RdIValid_ISAX2_3_s : WrRD_validReq_3_o = WrRD_validReq_ISAX2_3_i;
        default : WrRD_validReq_3_o = ~1;
    endcase
end
always @(*)  WrPC_2_o = WrPC_ISAX7_2_i;
always @(*) begin 
    case(1'b1)
        RdIValid_ISAX7_2_s : WrPC_validReq_2_o = WrPC_validReq_ISAX7_2_i;
        default : WrPC_validReq_2_o = ~1;
    endcase
end
always @(*)  RdMem_addr_valid_2_o = ~1;
assign WrFlush_1_o = WrPC_validReq_2_o;
assign WrFlush_0_o = WrPC_validReq_2_o;
assign WrStall_3_s = WrStall_ISAX7_3_i;
reg [32-1:0]WrMem_spawn_ISAX6_5_s;
reg [32-1:0]Mem_spawn_addr_ISAX6_5_s;
reg [1-1:0]Mem_spawn_validReq_ISAX6_5_s;
reg [1-1:0]Mem_spawn_write_ISAX6_5_s;
reg [64-1:0]  WrMem_spawn_ISAX6_FIFO_in_s;  
reg [64-1:0]  WrMem_spawn_ISAX6_FIFO_out_s;  
wire      WrMem_spawn_ISAX6_FIFO_write_s; 
reg      WrMem_spawn_ISAX6_FIFO_read_s;
wire      WrMem_spawn_ISAX6_FIFO_notempty_s; 
SimpleFIFO #( 4,64 ) SimpleFIFO_WrMem_spawn_ISAX6_valid_INPUTs_inst ( 
    clk_i, 
    rst_i, 
    WrMem_spawn_ISAX6_FIFO_write_s, 
    WrMem_spawn_ISAX6_FIFO_read_s, 
    WrMem_spawn_ISAX6_FIFO_in_s, 
    WrMem_spawn_ISAX6_FIFO_notempty_s, 
    WrMem_spawn_ISAX6_FIFO_out_s 
);
assign WrMem_spawn_ISAX6_FIFO_write_s = (Mem_spawn_validReq_ISAX6_5_reg && Mem_spawn_validReq_ISAX6_5_s);
always@(*) begin // ISAX Logic
     WrMem_spawn_ISAX6_FIFO_in_s = {WrMem_spawn_addr_ISAX6_5_i,WrMem_spawn_ISAX6_5_i};
    
end
always@(*) begin // ISAX Logic
     Mem_spawn_validReq_ISAX6_5_s = WrMem_spawn_validReq_ISAX6_5_i && Mem_spawn_validReq_ISAX6_5_i_shiftreg_s; // Signals rest of logic valid spawn sig
    Mem_spawn_addr_ISAX6_5_s = WrMem_spawn_addr_ISAX6_5_i;
    WrMem_spawn_ISAX6_5_s = WrMem_spawn_ISAX6_5_i;
    WrMem_spawn_ISAX6_FIFO_read_s = 0;
    if(WrMem_spawn_ISAX6_FIFO_notempty_s && !Mem_spawn_validReq_ISAX6_5_reg) begin 
         Mem_spawn_validReq_ISAX6_5_s = 1;
     WrMem_spawn_ISAX6_FIFO_read_s = 1;
     WrMem_spawn_ISAX6_5_s = WrMem_spawn_ISAX6_FIFO_out_s[32-1:0];
     Mem_spawn_addr_ISAX6_5_s = WrMem_spawn_ISAX6_FIFO_out_s[32+32-1 : 32]; 
    end
    
end
always@(posedge clk_i) begin // ISAX Spawn Regs for node WrMem_spawn 
    if(Mem_spawn_validReq_ISAX6_5_s ) begin  
        WrMem_spawn_ISAX6_5_reg <= WrMem_spawn_ISAX6_5_s;  
    end 
end	
assign WrMem_spawn_validResp_ISAX6_5_o = (ISAX_fire2_r_Mem_5_reg && Mem_spawn_validReq_ISAX6_5_reg && Mem_spawn_validResp_5_i) ? 1'b1 : 1'b0;
always@(posedge clk_i) begin // ISAX Spawn Regs for node WrMem_spawn_addr 
    if(Mem_spawn_validReq_ISAX6_5_s ) begin  
        Mem_spawn_addr_ISAX6_5_reg <= Mem_spawn_addr_ISAX6_5_s;  
    end 
end	
always@(posedge clk_i) begin // ISAX Spawn Regs for node WrMem_spawn_validReq 
    if(Mem_spawn_validReq_ISAX6_5_s ) begin  
        Mem_spawn_validReq_ISAX6_5_reg <= 1;  
    end else if((ISAX_fire2_r_Mem_5_reg )  && Mem_spawn_validResp_5_i )  
        Mem_spawn_validReq_ISAX6_5_reg <= 0;  
    if (rst_i ) 
        Mem_spawn_validReq_ISAX6_5_reg <= 0;  

end	
always@(posedge clk_i) begin // ISAX Spawn Regs for node WrMem_spawn_write 
    if(Mem_spawn_validReq_ISAX6_5_s ) begin  
        Mem_spawn_write_ISAX6_5_reg <= Mem_spawn_write_ISAX6_5_s;  
    end 
end	
reg [32-1:0]RdMem_spawn_ISAX5_5_s;
reg [32-1:0]Mem_spawn_addr_ISAX5_5_s;
reg [1-1:0]Mem_spawn_validReq_ISAX5_5_s;
reg [1-1:0]Mem_spawn_write_ISAX5_5_s;
reg [64-1:0]  RdMem_spawn_ISAX5_FIFO_in_s;  
reg [64-1:0]  RdMem_spawn_ISAX5_FIFO_out_s;  
wire      RdMem_spawn_ISAX5_FIFO_write_s; 
reg      RdMem_spawn_ISAX5_FIFO_read_s;
wire      RdMem_spawn_ISAX5_FIFO_notempty_s; 
SimpleFIFO #( 4,64 ) SimpleFIFO_RdMem_spawn_ISAX5_valid_INPUTs_inst ( 
    clk_i, 
    rst_i, 
    RdMem_spawn_ISAX5_FIFO_write_s, 
    RdMem_spawn_ISAX5_FIFO_read_s, 
    RdMem_spawn_ISAX5_FIFO_in_s, 
    RdMem_spawn_ISAX5_FIFO_notempty_s, 
    RdMem_spawn_ISAX5_FIFO_out_s 
);
assign RdMem_spawn_ISAX5_FIFO_write_s = (Mem_spawn_validReq_ISAX5_5_reg && Mem_spawn_validReq_ISAX5_5_s);
always@(*) begin // ISAX Logic
     RdMem_spawn_ISAX5_FIFO_in_s = {RdMem_spawn_addr_ISAX5_5_i,32'd0};
    
end
always@(*) begin // ISAX Logic
     Mem_spawn_validReq_ISAX5_5_s = RdMem_spawn_validReq_ISAX5_5_i && Mem_spawn_validReq_ISAX5_5_i_shiftreg_s; // Signals rest of logic valid spawn sig
    Mem_spawn_addr_ISAX5_5_s = RdMem_spawn_addr_ISAX5_5_i;
    RdMem_spawn_ISAX5_FIFO_read_s = 0;
    if(RdMem_spawn_ISAX5_FIFO_notempty_s && !Mem_spawn_validReq_ISAX5_5_reg) begin 
         Mem_spawn_validReq_ISAX5_5_s = 1;
     RdMem_spawn_ISAX5_FIFO_read_s = 1;
     Mem_spawn_addr_ISAX5_5_s = RdMem_spawn_ISAX5_FIFO_out_s[32+32-1 : 32]; 
    end
    
end
assign RdMem_spawn_ISAX5_5_o = RdMem_spawn_5_i;
assign ISAX_spawn_sum_Mem_5_s = 8'(Mem_spawn_validReq_ISAX6_5_reg ) + 8'( Mem_spawn_validReq_ISAX5_5_reg);
assign ISAX_fire_s_Mem_5_s = Mem_spawn_validReq_ISAX6_5_s  ||  Mem_spawn_validReq_ISAX5_5_s;
 // ISAX : Spawn fire logic
always @ (posedge clk_i)  begin
     if(ISAX_fire_s_Mem_5_s && !(ISAX_spawnAllowed_2_i || to_CORE_stall_RS_RdMem_spawn_o_s) )  
         ISAX_fire_r_Mem_5_reg <=  1'b1; 
     else if((ISAX_fire_r_Mem_5_reg ) && ((ISAX_spawnAllowed_2_i || to_CORE_stall_RS_RdMem_spawn_o_s)))   
         ISAX_fire_r_Mem_5_reg <=  1'b0; 
     if (rst_i) 
          ISAX_fire_r_Mem_5_reg <= 1'b0; 
end 
   
always @ (posedge clk_i)  begin
     if((ISAX_fire_r_Mem_5_reg || ISAX_fire_s_Mem_5_s) && ((ISAX_spawnAllowed_2_i || to_CORE_stall_RS_RdMem_spawn_o_s)))    
          ISAX_fire2_r_Mem_5_reg <=  1'b1; 
     else if(ISAX_fire2_r_Mem_5_reg && (ISAX_spawn_sum_Mem_5_s == 1)  && Mem_spawn_validResp_5_i)    
          ISAX_fire2_r_Mem_5_reg <= 1'b0; 
     if (rst_i) 
          ISAX_fire2_r_Mem_5_reg <= 1'b0; 
  end 

 assign Mem_spawn_validReq_5_o = ISAX_fire2_r_Mem_5_reg ;
assign RdMem_spawn_validResp_ISAX5_5_o = (ISAX_fire2_r_Mem_5_reg && !(Mem_spawn_validReq_ISAX6_5_reg)  && Mem_spawn_validReq_ISAX5_5_reg && Mem_spawn_validResp_5_i) ? 1'b1 : 1'b0;
always@(posedge clk_i) begin // ISAX Spawn Regs for node RdMem_spawn_addr 
    if(Mem_spawn_validReq_ISAX5_5_s ) begin  
        Mem_spawn_addr_ISAX5_5_reg <= Mem_spawn_addr_ISAX5_5_s;  
    end 
end	
always@(posedge clk_i) begin // ISAX Spawn Regs for node RdMem_spawn_validReq 
    if(Mem_spawn_validReq_ISAX5_5_s ) begin  
        Mem_spawn_validReq_ISAX5_5_reg <= 1;  
    end else if((ISAX_fire2_r_Mem_5_reg )  && !(Mem_spawn_validReq_ISAX6_5_reg)  && Mem_spawn_validResp_5_i )  
        Mem_spawn_validReq_ISAX5_5_reg <= 0;  
    if (rst_i ) 
        Mem_spawn_validReq_ISAX5_5_reg <= 0;  

end	
always@(posedge clk_i) begin // ISAX Spawn Regs for node RdMem_spawn_write 
    if(Mem_spawn_validReq_ISAX5_5_s ) begin  
        Mem_spawn_write_ISAX5_5_reg <= Mem_spawn_write_ISAX5_5_s;  
    end 
end	
reg [32-1:0]WrRD_spawn_ISAX5_5_s;
reg [5-1:0]WrRD_spawn_addr_ISAX5_5_s;
reg [1-1:0]WrRD_spawn_validReq_ISAX5_5_s;
reg [37-1:0]  WrRD_spawn_ISAX5_FIFO_in_s;  
reg [37-1:0]  WrRD_spawn_ISAX5_FIFO_out_s;  
wire      WrRD_spawn_ISAX5_FIFO_write_s; 
reg      WrRD_spawn_ISAX5_FIFO_read_s;
wire      WrRD_spawn_ISAX5_FIFO_notempty_s; 
SimpleFIFO #( 4,37 ) SimpleFIFO_WrRD_spawn_ISAX5_valid_INPUTs_inst ( 
    clk_i, 
    rst_i, 
    WrRD_spawn_ISAX5_FIFO_write_s, 
    WrRD_spawn_ISAX5_FIFO_read_s, 
    WrRD_spawn_ISAX5_FIFO_in_s, 
    WrRD_spawn_ISAX5_FIFO_notempty_s, 
    WrRD_spawn_ISAX5_FIFO_out_s 
);
assign WrRD_spawn_ISAX5_FIFO_write_s = (WrRD_spawn_validReq_ISAX5_5_reg && WrRD_spawn_validReq_ISAX5_5_s);
always@(*) begin // ISAX Logic
     WrRD_spawn_ISAX5_FIFO_in_s = {WrRD_spawn_addr_ISAX5_5_i,WrRD_spawn_ISAX5_5_i};
    
end
always@(*) begin // ISAX Logic
     WrRD_spawn_validReq_ISAX5_5_s = WrRD_spawn_validReq_ISAX5_5_i_shiftreg_s; // Signals rest of logic valid spawn sig
    WrRD_spawn_addr_ISAX5_5_s = WrRD_spawn_addr_ISAX5_5_i;
    WrRD_spawn_ISAX5_5_s = WrRD_spawn_ISAX5_5_i;
    WrRD_spawn_ISAX5_FIFO_read_s = 0;
    if(WrRD_spawn_ISAX5_FIFO_notempty_s && !WrRD_spawn_validReq_ISAX5_5_reg) begin 
         WrRD_spawn_validReq_ISAX5_5_s = 1;
     WrRD_spawn_ISAX5_FIFO_read_s = 1;
     WrRD_spawn_ISAX5_5_s = WrRD_spawn_ISAX5_FIFO_out_s[32-1:0];
     WrRD_spawn_addr_ISAX5_5_s = WrRD_spawn_ISAX5_FIFO_out_s[5+32-1 : 32]; 
    end
    
end
always@(posedge clk_i) begin // ISAX Spawn Regs for node WrRD_spawn 
    if(WrRD_spawn_validReq_ISAX5_5_s ) begin  
        WrRD_spawn_ISAX5_5_reg <= WrRD_spawn_ISAX5_5_s;  
    end 
end	
assign ISAX_spawn_sum_WrRD_5_s = 8'(WrRD_spawn_validReq_ISAX5_5_reg);
assign ISAX_fire_s_WrRD_5_s = WrRD_spawn_validReq_ISAX5_5_s;
 // ISAX : Spawn fire logic
always @ (posedge clk_i)  begin
     if(ISAX_fire_s_WrRD_5_s && !(ISAX_spawnAllowed_2_i || to_CORE_stall_RS_WrRD_spawn_o_s) )  
         ISAX_fire_r_WrRD_5_reg <=  1'b1; 
     else if((ISAX_fire_r_WrRD_5_reg ) && ((ISAX_spawnAllowed_2_i || to_CORE_stall_RS_WrRD_spawn_o_s)))   
         ISAX_fire_r_WrRD_5_reg <=  1'b0; 
     if (rst_i) 
          ISAX_fire_r_WrRD_5_reg <= 1'b0; 
end 
   
always @ (posedge clk_i)  begin
     if((ISAX_fire_r_WrRD_5_reg || ISAX_fire_s_WrRD_5_s) && ((ISAX_spawnAllowed_2_i || to_CORE_stall_RS_WrRD_spawn_o_s)))    
          ISAX_fire2_r_WrRD_5_reg <=  1'b1; 
     else if(ISAX_fire2_r_WrRD_5_reg && (ISAX_spawn_sum_WrRD_5_s == 1)  && WrRD_spawn_validResp_5_i)    
          ISAX_fire2_r_WrRD_5_reg <= 1'b0; 
     if (rst_i) 
          ISAX_fire2_r_WrRD_5_reg <= 1'b0; 
  end 

 assign WrRD_spawn_validReq_5_o = ISAX_fire2_r_WrRD_5_reg ;
assign WrRD_spawn_validResp_ISAX5_5_o = (ISAX_fire2_r_WrRD_5_reg && WrRD_spawn_validReq_ISAX5_5_reg && WrRD_spawn_validResp_5_i) ? 1'b1 : 1'b0;
always@(posedge clk_i) begin // ISAX Spawn Regs for node WrRD_spawn_addr 
    if(WrRD_spawn_validReq_ISAX5_5_s ) begin  
        WrRD_spawn_addr_ISAX5_5_reg <= WrRD_spawn_addr_ISAX5_5_s;  
    end 
end	
always@(posedge clk_i) begin // ISAX Spawn Regs for node WrRD_spawn_validReq 
    if(WrRD_spawn_validReq_ISAX5_5_s ) begin  
        WrRD_spawn_validReq_ISAX5_5_reg <= 1;  
    end else if((ISAX_fire2_r_WrRD_5_reg )  && WrRD_spawn_validResp_5_i )  
        WrRD_spawn_validReq_ISAX5_5_reg <= 0;  
    if (rst_i ) 
        WrRD_spawn_validReq_ISAX5_5_reg <= 0;  

end	
always@(*) begin // ISAX Logic
     WrMem_spawn_5_o = WrMem_spawn_ISAX6_5_reg;
    Mem_spawn_addr_5_o = Mem_spawn_addr_ISAX6_5_reg;
    Mem_spawn_write_5_o = 1;
    if (!(Mem_spawn_validReq_ISAX6_5_reg))
    Mem_spawn_addr_5_o = Mem_spawn_addr_ISAX5_5_reg;
    if (!(Mem_spawn_validReq_ISAX6_5_reg))
    Mem_spawn_write_5_o = 0;
    WrRD_spawn_5_o = WrRD_spawn_ISAX5_5_reg;
    WrRD_spawn_addr_5_o = WrRD_spawn_addr_ISAX5_5_reg;
    
end

SimpleFIFO #( 4, 32 ) SimpleFIFO_WrMem_spawn_ISAX6_5_inst (
    clk_i,
    rst_i,
    RdIValid_ISAX6_2_s && ! RdStall_2_i,
 // no need for wrStall, it.s included in RdStall
    Mem_spawn_validReq_ISAX6_5_i_shiftreg_s,
    RdInstr_2_i[11:7],
dummyISAX6,    Mem_spawn_addr_ISAX6_5_i
);
SimpleShift #( 4, 1 ) SimpleShift_WrMem_spawn_ISAX6_5_inst (
    clk_i,
    rst_i,
    RdInstr_2_i,
    RdIValid_ISAX6_2_s,
    {RdFlush_4_i,RdFlush_3_i},
    {RdStall_4_i,WrStall_3_s || RdStall_3_i,RdStall_2_i},
    Mem_spawn_validReq_ISAX6_5_i_shiftreg_s
);

SimpleFIFO #( 5, 32 ) SimpleFIFO_RdMem_spawn_ISAX5_5_inst (
    clk_i,
    rst_i,
    RdIValid_ISAX5_2_s && ! RdStall_2_i,
 // no need for wrStall, it.s included in RdStall
    Mem_spawn_validReq_ISAX5_5_i_shiftreg_s,
    RdInstr_2_i[11:7],
dummyISAX5,    Mem_spawn_addr_ISAX5_5_i
);
SimpleShift #( 5, 1 ) SimpleShift_RdMem_spawn_ISAX5_5_inst (
    clk_i,
    rst_i,
    RdInstr_2_i,
    RdIValid_ISAX5_2_s,
    {RdFlush_4_i,RdFlush_3_i},
    {RdStall_4_i,WrStall_3_s || RdStall_3_i,RdStall_2_i},
    Mem_spawn_validReq_ISAX5_5_i_shiftreg_s
);
assign Commited_rd_spawn_WrRD_spawn_5_o = WrRD_spawn_addr_5_o;
assign Commited_rd_spawn_validReq_WrRD_spawn_5_o = ISAX_fire2_r_WrRD_5_reg;

SimpleFIFO #( 4, 5 ) SimpleFIFO_WrRD_spawn_ISAX5_5_inst (
    clk_i,
    rst_i,
    RdIValid_ISAX5_2_s && ! RdStall_2_i,
 // no need for wrStall, it.s included in RdStall
    WrRD_spawn_validReq_ISAX5_5_i_shiftreg_s,
    RdInstr_2_i[11:7],
dummyISAX5,    WrRD_spawn_addr_ISAX5_5_i
);
SimpleShift #( 4, 1 ) SimpleShift_WrRD_spawn_ISAX5_5_inst (
    clk_i,
    rst_i,
    RdInstr_2_i,
    RdIValid_ISAX5_2_s,
    {RdFlush_4_i,RdFlush_3_i},
    {RdStall_4_i,WrStall_3_s || RdStall_3_i,RdStall_2_i},
    WrRD_spawn_validReq_ISAX5_5_i_shiftreg_s
);
wire cancel_frUser_validReq_WrRD_spawn_5_o;
assign cancel_frUser_validReq_WrRD_spawn_5_o = 0;
wire [4:0]cancel_frUser_WrRD_spawn_5_o;
assign cancel_frUser_WrRD_spawn_5_o = 0;
spawndatah_WrRD_spawn spawndatah_WrRD_spawn_inst(        
	.clk_i(clk_i),                           
	.rst_i(rst_i),             
	.flush_i({{RdFlush_4_i,RdFlush_3_i},RdFlush_2_i}),	 
	.RdIValid_ISAX0_2_i(RdIValid_ISAX5_2_s), 
	.RdInstr_RDRS_i({RdInstr_2_i[31:20], RdInstr_2_i[19:15],RdInstr_2_i[14:12],RdInstr_2_i[11:7] ,RdInstr_2_i[6:0] }),     
	.WrRD_spawn_valid_i(Commited_rd_spawn_validReq_WrRD_spawn_5_o),       		    
	.WrRD_spawn_addr_i(Commited_rd_spawn_WrRD_spawn_5_o),  
    .cancel_from_user_valid_i(cancel_frUser_validReq_WrRD_spawn_5_o),
    .cancel_from_user_addr_i(cancel_frUser_WrRD_spawn_5_o),
	.stall_RDRS_o(to_CORE_stall_RS_WrRD_spawn_o_s),  
	.stall_RDRS_i({RdStall_4_i,RdStall_3_i,RdStall_2_i})  
    );
assign WrStall_0_o = WrStall_1_o || ISAX_fire2_r_Mem_5_reg || ISAX_fire2_r_Mem_5_reg || ISAX_fire2_r_WrRD_5_reg || to_CORE_stall_RS_WrRD_spawn_o_s;
assign WrStall_1_o = WrStall_2_o || ISAX_fire2_r_Mem_5_reg || ISAX_fire2_r_Mem_5_reg || ISAX_fire2_r_WrRD_5_reg || to_CORE_stall_RS_WrRD_spawn_o_s;
assign WrStall_2_o = WrStall_3_o || ISAX_fire2_r_Mem_5_reg || ISAX_fire2_r_Mem_5_reg || ISAX_fire2_r_WrRD_5_reg || to_CORE_stall_RS_WrRD_spawn_o_s;
assign WrStall_3_o = WrStall_3_s || ISAX_fire2_r_WrRD_5_reg;
assign WrStall_4_o = ISAX_fire2_r_WrRD_5_reg;
assign RdStall_3_o = RdStall_3_i;


endmodule



`define LATER_FLUSHES
`define LATER_FLUSHES_DH
module spawndatah_WrRD_spawn#(                                              
 parameter RD_W_P = 5,                                                
 parameter INSTR_W_P = 32,  
 parameter START_STAGE = 2,  
 parameter WB_STAGE = 4                                           
                                                                      
)(                                                                    
   input clk_i,                                                           
    input rst_i,   
 `ifdef LATER_FLUSHES   
    input [WB_STAGE-START_STAGE:0] flush_i,  
`endif                                                         
    input RdIValid_ISAX0_2_i,  
    input [INSTR_W_P-1:0] RdInstr_RDRS_i,  
    input  WrRD_spawn_valid_i,                                   
    input [RD_W_P-1:0] WrRD_spawn_addr_i,                                    
    input  cancel_from_user_valid_i,// user validReq bit was zero, but we need to clear its scoreboard dirty bit 
    input [RD_W_P-1:0]cancel_from_user_addr_i,
    output stall_RDRS_o, //  stall from ISAX,  barrier ISAX,  OR spawn DH   
    input  [WB_STAGE-START_STAGE:0] stall_RDRS_i // input from core. core stalled. Includes user stall, as these sigs are combined within core  
);                                                                     
  
                                                                      
wire dirty_bit_rs1;                                                    
wire dirty_bit_rs2;     
wire dirty_bit_rd;  
wire data_hazard_rs1;   
wire data_hazard_rs2;   
wire data_hazard_rd;   
wire we_spawn_start;            
reg [2**RD_W_P-1:0] mask_start;  
reg [2**RD_W_P-1:0] mask_stop_or_flush;  
wire [14:0] opcode_kill = 15'b110xxxxx0001011;  
wire [14:0] opcode_barrier = 15'b111xxxxx0001011;  
reg barrier_set;   
									  
reg  [2**RD_W_P-1:0] rd_table_mem ;      
reg  [2**RD_W_P-1:0] rd_table_temp;  
   
    
   
`ifdef LATER_FLUSHES_DH                                                                       
    reg  [RD_W_P-1:0] RdInstr_7_11_reg[WB_STAGE - START_STAGE-1:0];     
    reg [WB_STAGE-START_STAGE:1] RdIValid_reg;   
    always @(posedge clk_i ) begin   
        if(!stall_RDRS_i[0])  
            RdIValid_reg[1] <= RdIValid_ISAX0_2_i; // or of all decoupled spawn  
        if((flush_i[1] && stall_RDRS_i[0]) || (flush_i[0] && !stall_RDRS_i[0]) || ( stall_RDRS_i[0] && !stall_RDRS_i[1]))  
RdIValid_reg[1] <= 0 ;
        for(int k=2;k<=(WB_STAGE - START_STAGE);k=k+1) begin   
            if((flush_i[k] && stall_RDRS_i[k-1]) || (flush_i[k-1] && !stall_RDRS_i[k-1]) || ( stall_RDRS_i[k-1] && !stall_RDRS_i[k]))  
		          RdIValid_reg[k] <=0;  
		       else if(!stall_RDRS_i[k-1]) 
                RdIValid_reg[k] <= RdIValid_reg[k-1];  
        end   
        if(rst_i) begin  
            for(int k=1;k<(WB_STAGE - START_STAGE);k=k+1) 
                RdIValid_reg[k] <= 0; 
        end      
    end  
    always @(posedge clk_i ) begin   
        if(!stall_RDRS_i[0])  
            RdInstr_7_11_reg[1] <= RdInstr_RDRS_i[11:7];  
        for(int k=2;k<(WB_STAGE - START_STAGE);k=k+1) begin   
        if(!stall_RDRS_i[k-1])  
             RdInstr_7_11_reg[k] <= RdInstr_7_11_reg[k-1];  
        end  
        if(rst_i) begin  
            for(int k=1;k<(WB_STAGE - START_STAGE);k=k+1) 
                RdInstr_7_11_reg[k] <= 0; 
        end  
    end  
   
`endif  
assign we_spawn_start   = (RdIValid_ISAX0_2_i && !stall_RDRS_i[0]);                                                                                                                          
                                                         								  
always @(posedge clk_i)                                                
begin   
    if(rst_i)   
        rd_table_mem <= 0;  
    else                                                                   
        rd_table_mem <= rd_table_temp;          							   
end                                                                    
  
always @(*) begin  
    mask_start = {(2**RD_W_P){1'b0}};  
    if(we_spawn_start)  
        mask_start[RdInstr_RDRS_i[11:7]] = 1'b1;   
end  
  
always @(*) begin  
    mask_stop_or_flush = {(2**RD_W_P){1'b1}};  
    if(WrRD_spawn_valid_i)  
        mask_stop_or_flush[WrRD_spawn_addr_i] = 1'b0; 
      if(cancel_from_user_valid_i)  
        mask_stop_or_flush[cancel_from_user_addr_i] = 1'b0; 
    if ((opcode_kill[6:0] == RdInstr_RDRS_i[6:0]) && (opcode_kill[14:12] == RdInstr_RDRS_i[14:12])) begin  
        mask_stop_or_flush = 0;  
    end  
`ifdef LATER_FLUSHES_DH   
	for(int k=1;k<(WB_STAGE - START_STAGE);k=k+1) begin   
		if(flush_i[k] && RdIValid_reg[k] )   
			mask_stop_or_flush[RdInstr_7_11_reg[k]] = 1'b0;   
	end  
`endif  
end  
  
always @(*) begin        
  rd_table_temp = (rd_table_mem | mask_start) & mask_stop_or_flush;    
 end                                                                        
  
always @(posedge clk_i)                                                
begin   
    if((|rd_table_mem) == 0)      
        barrier_set <= 0;   
    else if((opcode_barrier[6:0] === RdInstr_RDRS_i[6:0]) && (opcode_barrier[14:12] === RdInstr_RDRS_i[14:12]) && !flush_i[0])                                                                  
        barrier_set <= 1;   							   
end    
wire stall_fr_barrier = barrier_set;  
                                                                       
assign dirty_bit_rs1 = rd_table_mem[RdInstr_RDRS_i[19:15]];     
assign dirty_bit_rd =  rd_table_mem[RdInstr_RDRS_i[11:7]];   
assign data_hazard_rs1 =  !flush_i[0] &&  dirty_bit_rs1 && (( ((RdInstr_RDRS_i[6:0] !==7'b0110111) && (RdInstr_RDRS_i[6:0] !==7'b0010111) && (RdInstr_RDRS_i[6:0] !==7'b1101111)) || (  ( RdInstr_RDRS_i [ 14 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 13 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 12 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 5 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 0 ]  ==  1'b1 )  ) || (  ( RdInstr_RDRS_i [ 14 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 13 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 12 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 5 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 0 ]  ==  1'b1 )  ) || (  ( RdInstr_RDRS_i [ 14 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 13 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 12 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 5 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 0 ]  ==  1'b1 )  ) || (  ( RdInstr_RDRS_i [ 14 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 13 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 12 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 5 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 0 ]  ==  1'b1 )  ) || (  ( RdInstr_RDRS_i [ 14 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 13 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 12 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 5 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 0 ]  ==  1'b1 )  )));  
assign data_hazard_rd =  !flush_i[0] && dirty_bit_rd   && (( ((RdInstr_RDRS_i[6:0] !==7'b1100011) && (RdInstr_RDRS_i[6:0] !==7'b0100011)) ||(  ( RdInstr_RDRS_i [ 14 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 13 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 12 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 5 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 0 ]  ==  1'b1 )  ) || (  ( RdInstr_RDRS_i [ 14 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 13 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 12 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 5 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 0 ]  ==  1'b1 )  ) || (  ( RdInstr_RDRS_i [ 14 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 13 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 12 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 5 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 0 ]  ==  1'b1 )  )));  
assign dirty_bit_rs2 = rd_table_mem[RdInstr_RDRS_i[24:20]];   
assign data_hazard_rs2 =  !flush_i[0] && dirty_bit_rs2 && (( ((RdInstr_RDRS_i[6:0] !==7'b0110111) && (RdInstr_RDRS_i[6:0] !==7'b0010011) && (RdInstr_RDRS_i[6:0] !==7'b0000011) && (RdInstr_RDRS_i[6:0] !==7'b0010111) &&  (RdInstr_RDRS_i[6:0] !==7'b1100111)  &&  (RdInstr_RDRS_i[6:0] !==7'b1101111)) ||(  ( RdInstr_RDRS_i [ 14 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 13 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 12 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 5 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 0 ]  ==  1'b1 )  ) || (  ( RdInstr_RDRS_i [ 14 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 13 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 12 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 6 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 5 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 4 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 3 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 2 ]  ==  1'b0 )  &&  ( RdInstr_RDRS_i [ 1 ]  ==  1'b1 )  &&  ( RdInstr_RDRS_i [ 0 ]  ==  1'b1 )  )));  
assign stall_RDRS_o = data_hazard_rs1 || data_hazard_rs2 || data_hazard_rd || stall_fr_barrier;           
  
endmodule        

module SimpleFIFO #(
	parameter NR_ELEMENTS = 64,
	parameter DATAW = 5
)(
		input 				clk_i,  
	input 				rst_i,  
	input 				write_valid_i,  
	input 				read_valid_i,  
	input  [DATAW-1:0] 	data_i, 
	output              not_empty,  
	output [DATAW-1:0]	data_o 
 
); 
 
reg [DATAW-1:0] FIFOContent [NR_ELEMENTS-1:0]; 
reg [$clog2(NR_ELEMENTS)-1:0] write_pointer; 
reg [$clog2(NR_ELEMENTS)-1:0] read_pointer; 
assign not_empty = write_pointer != read_pointer; 
always @(posedge clk_i) begin  
	if(write_valid_i)  
		FIFOContent[write_pointer] <= data_i; 
end 
assign data_o = FIFOContent[read_pointer]; 
 
// FIFO ignores case when data is overwritten, as it shouldn't happen in our scenario 
always @(posedge clk_i) begin  
	if(rst_i) 
		write_pointer <=0;  
    else if(write_valid_i) begin  
		if(NR_ELEMENTS'(write_pointer) == (NR_ELEMENTS-1)) // useful if NR_ELEMENTS not a power of 2 -1 
			write_pointer <= 0; 
	    else  
			write_pointer <= write_pointer+1; 
	end 
end  
 
always @(posedge clk_i) begin  
	if(rst_i) 
		read_pointer <=0;  
    else if(read_valid_i) begin  
		if(NR_ELEMENTS'(read_pointer) == (NR_ELEMENTS-1)) // useful if NR_ELEMENTS not a power of 2 -1 
			read_pointer <= 0; 
	    else  
			read_pointer <= read_pointer+1; 
	end 
end endmodule

`define LATER_FLUSHES
module SimpleShift #(
	parameter NR_ELEMENTS = 64,
	parameter DATAW = 5,
	parameter START_STAGE = 2, 
	parameter WB_STAGE = 4
)(
	input 				clk_i,  
	input 				rst_i,  
 input  [31:0]       instr_i,	input  [DATAW-1:0]  data_i,  
	`ifdef LATER_FLUSHES 
		input  [WB_STAGE-START_STAGE:1] flush_i,  
	`endif input [WB_STAGE-START_STAGE:0] stall_i, 
	output [DATAW-1:0]  data_o  
	); 
	 
reg [DATAW-1:0] shift_reg [NR_ELEMENTS:1] ;	 
wire kill_spawn;  
assign kill_spawn = (7'b0001011 == instr_i[6:0]) && (3'b110 ==  instr_i[14:12]);  
always @(posedge clk_i) begin   
   if(!stall_i[0])  shift_reg[1] <= data_i;  
   if((flush_i[1] && stall_i[0]) || ( stall_i[0] && !stall_i[1])) //  (flush_i[0] && !stall_i[0]) not needed, data_i should be zero in case of flush for shift valid bits in case of flush  
        shift_reg[1] <= 0 ;
   for(int i=2;i<=NR_ELEMENTS;i = i+1) begin   
	    if((i+START_STAGE)<=WB_STAGE) begin   
		  if((flush_i[i] && stall_i[i-1]) || (flush_i[i-1] && !stall_i[i-1]) || ( stall_i[i-1] && !stall_i[i]))  
		    shift_reg[i] <=0;  
		  else if(!stall_i[i-1]) 
	        shift_reg[i] <= shift_reg[i-1];  
     end else 
	      shift_reg[i] <= shift_reg[i-1];  
   end 
   if(rst_i || kill_spawn) begin   
     for(int i=1;i<=NR_ELEMENTS;i=i+1)  
       shift_reg[i] = 0;  
   end  
end
 assign data_o = shift_reg[NR_ELEMENTS];
endmodule

module SimpleCounter #(
	parameter NR_CYCLES = 64
)(
	input 				clk_i, 
	input 				rst_i, 
 input               stall_i,
	input 				write_valid_i, 
	output          	zero_o

);

reg [$clog2(NR_CYCLES)-1:0] counter;
 
always @(posedge clk_i) begin  
	if(rst_i) 
	 counter <= 0; 
 else if(write_valid_i && !stall_i && counter==0)   
	 counter <= NR_CYCLES-1; 
 else if(counter>0)   
	 counter <= counter-1; 
end 
assign zero_o = (counter == 0 );endmodule



