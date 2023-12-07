

////////////////////// LOGIC COMMON FOR ALL CORES - COPY PASTE //////////

parameter IMEM_BASE_ADDR = 32'H80000000;


`define	BNE_OP 7'b1100011
`define	BNE_F 3'b001

`define	ADDI_OP 7'b0010011
`define	ADDI_F3 3'b000

`define	ISAX1_OP 7'b0001011
`define ISAX1_F 3'b001
`define	ISAX2_OP 7'b0001011
`define ISAX2_F 3'b011
`define	ISAX3_OP 7'b0001011
`define ISAX3_F 3'b010
`define	ISAX4_OP 7'b1111011
`define ISAX4_F 3'b010 
`define	ISAX5_OP 7'b0001011 // wrrd spawn, rdmemspawn
`define ISAX5_F 3'b101 
`define	ISAX6_OP 7'b0101011 // wrmemspawn
`define ISAX6_F 3'b110 
`define	ISAX7_OP 7'b0101011 // wrpc
`define ISAX7_F 3'b111 
`define	ISAX8_OP 7'b0101011
`define ISAX8_F 3'b000 

`define	RD1 5'd1
`define	RD2 5'd2
`define	RD3 5'd3
`define	RD4 5'd4
`define	RD5 5'd5
`define	RD6 5'd6
`define	RD7 5'd7
`define	RD8 5'd8
`define	RD9 5'd9

`define	RS0 5'd0
`define	RS1 5'd1
`define	RS2 5'd2
`define	RS3 5'd3
`define	RS4 5'd4
`define	RS5 5'd5
`define	RS6 5'd6
`define	RS7 5'd7
`define	RS8 5'd8
`define	RS9 5'd9
`define	RS10 5'd10


module ISAXes(	
  input         clk_i,
                rst,
  // ISAX1 - rdrs and wrrd
  input  [31:0] RdRS1_n_i,
  input  [31:0] RdRS2_n_i,
  output [31:0] WrRd_m_o, 
  // ISAX2 -later rdrs and later wrrd with valid and RdIValid
  input  [31:0] RdRS1_mplus1_i,
  input  [31:0] RdRS2_mplus1_i,
  input 		RdIValid_ISAX2_mplus1_i,
  output [31:0] WrRd_mplus1_o,
  output        WrRd_mplus1_valid_o,
  // ISAX3 wrmem and memvalid
  output [31:0] WrMem_w_o,
  output [31:0] WrMem_w_addr_o,
  output        WrMem_w_valid_o,
  input  [31:0] RdInstr_w_i,
  input         RdIValid_ISAX3_w_i,
  // ISAX4 rdinstr and rdmem
  input  [31:0] RdMem_w_i,
  // ISAX5 MemSpawn WrRd spawn
  output [31:0] WrRD_spawn_o, 
  input  [31:0] RdMem_spawn_i,
  input         RdMem_spawn_resp_i,
  output [31:0] RdMem_spawn_addr_o,
  output        RdMem_spawn_valid_o,
  input 		RdIValid_ISAX5_i, 
  // ISAX6 MemSpawn WrRd spawn
  input  [31:0] WrMem_spawn_o,
  input         WrMem_spawn_resp_i,
  output [31:0] WrMem_spawn_addr_o,
  output        WrMem_spawn_valid_o,
  // ISAX7 WrPC, RdPC and stall 
  output 		WrStall,
  input 		RdStall, 
  output 		WrPC_valid, 
  output [31:0]  WrPC, 
  input [31:0]  RdPC, 
  // ISAX8 Rd all Regs 
  input [31:0]  RdRS_i, 
  input 		RdIValid_ISAX8_i
  
);
reg [2:0]  counter; 
reg [5:0]  counter_spawn, counter_allregs, counter_stall;
reg [31:0] data_from_mem,data_from_mem_spawn;
reg [31:0] allregs [31:0]; 
reg [31:0] RdInstr_w_i_old, RdIValid_ISAX2_mplus1_i_old;

always @(posedge clk_i) begin 
	if(rst) 
		RdInstr_w_i_old <= 0; 
	else 
		RdInstr_w_i_old <= RdInstr_w_i;
end

always @(posedge clk_i) begin 
	if(rst) 
		RdIValid_ISAX2_mplus1_i_old <= 0; 
	else 
		RdIValid_ISAX2_mplus1_i_old <= RdIValid_ISAX2_mplus1_i;
end

always @(posedge clk_i) begin 
	if(rst) 
		counter <= 0; 
	else if((RdIValid_ISAX2_mplus1_i && (RdIValid_ISAX2_mplus1_i_old != RdIValid_ISAX2_mplus1_i)) | ( counter<3 && RdInstr_w_i[14:12]==3'b010)) 
		counter <= counter +1;
end

always @(posedge clk_i) begin 
	if(rst) 
		counter_spawn <= 0; 
	else if(counter_spawn == 6)
		counter_spawn <= 0;
	else if(RdIValid_ISAX5_i | counter_spawn!=0)
		counter_spawn <= counter_spawn +1;
end


always @(posedge clk_i) begin 
	if(rst) 
		counter_allregs <= 0; 
	else if(RdIValid_ISAX8_i)
		counter_allregs <= counter_allregs +1;
end

wire [31:0] diff; 
assign diff = (RdPC-IMEM_BASE_ADDR);
always @(posedge clk_i) begin 
	if(rst) 
		counter_stall <= 0; 
	else if((diff) === 4 && counter_stall<5)
		counter_stall <= counter_stall +1;
end
assign WrStall =  ((diff) === 4) && counter_stall<5;

assign RdMem_spawn_addr_o = 16; 
assign RdMem_spawn_valid_o = counter_spawn == 5; 
assign WrMem_spawn_valid_o = counter_spawn == 5; 
assign WrMem_spawn_o = 80; 
assign WrMem_spawn_addr_o = 8; 

assign WrRD_spawn_o = 25; 

always @(posedge clk_i) begin 
	if(rst) 
		data_from_mem <= 0; 
	else if(RdInstr_w_i_old[14:12] == `ISAX4_F)
		data_from_mem <= RdMem_w_i;
end

always @(posedge clk_i) begin 
	if(rst) 
		data_from_mem_spawn <= 0; 
	else if(RdMem_spawn_resp_i)
		data_from_mem_spawn <= RdMem_spawn_i;
end


assign WrMem_w_o = (counter <3 && RdInstr_w_i[14:12]==3'b010)? 13 : 26;
assign WrMem_w_valid_o = (counter<3 && RdInstr_w_i[14:12]==3'b010) ? 1 : 0;
assign WrMem_w_addr_o = 32;

assign WrRd_mplus1_valid_o = RdIValid_ISAX2_mplus1_i & (~counter[0]);
assign WrRd_mplus1_o = RdRS2_mplus1_i + RdRS1_mplus1_i + 5; 
assign WrRd_m_o = RdRS1_n_i + RdRS2_n_i + 1;

assign WrPC = 76 + IMEM_BASE_ADDR; 
assign WrPC_valid = 1;

integer i;
always @(posedge clk_i) begin 
	if(rst) begin
		for(i=0;i<32;i=i+1)
			allregs[i] <= 0; 
	end else if(RdIValid_ISAX8_i)
		allregs[counter_allregs] <= RdRS_i;
end


endmodule


////////////////////////////////////    TESTBENCH ///////////////////////////////////////


module testbench();
parameter TEST_SPAWN_MEM = 1;
parameter STAGE_F = 0;
	   
    parameter SIZE_MEM = 48;
	
	
    reg clk_s;
    reg rst_s;
    reg [31:0] instruction[160:0]; // not really smart, only /4 are used, but it's a testbench, so we don't care 
    reg [31:0] data[SIZE_MEM-1:0]; 

    reg [31:0] expected_data_fr_mem; 
  
    ///// CORE INDEPENDENT SIGNALS USED IN ASSERTIONS /////////
    // original interface core independent
    wire [31:0] DMem_raddr; 
    reg         DMem_rvalid_ack;
    reg        DMem_wvalid;
    reg [31:0] DMem_waddr;
    wire [31:0] DMem_rdata;
    wire [31:0] DMem_wdata;
	
	wire [31:0] IMem_raddr;
	reg [31:0] IMem_rdata;
		
	
    //////////////   CLK RST //////////////////////
	
    
	always begin 
		clk_s = 1; #10 clk_s = 0; #10;
	end

	reg do_reset_piccolo; // not requried by other cores, but we can simply leave it here
	initial begin 
		do_reset_piccolo = 0;
		rst_s = 0;   #50;   
		rst_s = 1 ; #40; 
		rst_s = 0;  
		do_reset_piccolo = 1;   
		#8000; 
		$stop;
	end
	
	
	///// DMEM & IMEM /////////
	integer i;
	always @(posedge clk_s) begin 
		if(rst_s) begin 
			for(i=0;i<SIZE_MEM;i=i+1) begin
				data[i] = i ;
			end
		end else if(DMem_wvalid) begin   
			data[DMem_waddr] = DMem_wdata ;
		end
	end
		
	
		
	

		
		initial begin 
			instruction[0]  = {12'd5, 5'd0, `ADDI_F3,`RD1, `ADDI_OP};  //  X[1] = 5
			instruction[4]  = {12'd5, `RS1, `ADDI_F3,`RD2, `ADDI_OP};  //  X[2] = 10.       Scope: check DH between standard & standard instr  
			instruction[8]  = {`RS1 , `RS2, `ISAX1_F,`RD3, `ISAX1_OP}; //  X[3] = ISAX1(X[1],X[2]) = 16 Scope: check DH between standard & ISAX & result
			instruction[12] = {`RS3 , `RS2, `ISAX2_F,`RD4, `ISAX2_OP}; //  X[4] = ISAX2(X[3],X[2]) = 31 Scope: check DH between ISAX & ISAX 
			instruction[16] = {`RS3 , `RS4, `ISAX2_F,`RD4, `ISAX2_OP}; //  X[4] = ISAX2(X[3],X[4]) != 47+5 = 52 Scope: check valid bit, here should be 0 
			instruction[20] = {12'd5, `RS4, `ADDI_F3,`RD5, `ADDI_OP }; //  X[5] = x[4]+5 = 36 Scope: check DH between ISAX & standard instr, ISAX should not commit without valid bit 
			instruction[24] = {12'd0, 5'd0, `ISAX3_F,5'd0, `ISAX3_OP}; //  MEM[32] = 13 Scope: check wr mem wth valid bit
			instruction[28] = {12'd0, 5'd0, `ISAX3_F,5'd0, `ISAX3_OP}; //  MEM[32] = 26 Scope: check wr mem wth valid bit. This one should not write. 5d1 is just for isax counter to detect a new instr
			instruction[32] = {12'd32, 5'd0, `ISAX4_F,5'd0, `ISAX4_OP}; //  data_from_mem = MEM[32] = 13 Scope: check read mem after wrmem
			instruction[36] = {7'd0 , `RS1, `RS2, `BNE_F,5'b10000, `BNE_OP}; // as operands are different, should branch PC+ 16. Check that ISAXes coming afterwards are flushed 
			instruction[40] = {`RS1 , `RS2, `ISAX1_F,`RD1, `ISAX1_OP}; //  Scope: make sure is not run & is flushed
			instruction[44] = {`RS1 , `RS2, `ISAX1_F,`RD2, `ISAX1_OP}; //  Scope: make sure is not run & is flushed
            instruction[48] = {`RS1 , `RS2, `ISAX1_F,`RD3, `ISAX1_OP}; //  Scope: make sure is not run & is flushed
			instruction[52] = {12'd0, 5'd0, `ISAX5_F,`RD6, `ISAX5_OP}; //  data_from_mem_spawn = 16, X[6] = 25. Check spawn wrrd and mem incoming at the same time
			instruction[56] = {12'd0, 5'd0, `ISAX6_F,5'd0, `ISAX6_OP}; //  MEM[8] = 16. Check more mem spawn at same time (finishes at same time with isax5)
			instruction[60] = {12'd0, 5'd0, `ISAX7_F,5'd0, `ISAX7_OP}; //  WrPC, Check: valid always 1, but make sure it's actually flushed only when needed
			instruction[64] = {`RS1 , `RS2, `ISAX1_F,`RD1, `ISAX1_OP}; //  Scope: make sure is not run & is flushed
			instruction[68] = {`RS1 , `RS2, `ISAX1_F,`RD2, `ISAX1_OP}; //  Scope: make sure is not run & is flushed
            instruction[72] = {`RS1 , `RS2, `ISAX1_F,`RD3, `ISAX1_OP}; //  Scope: make sure is not run & is flushed
			instruction[76] = {7'd0 , `RS1, `RS2, `BNE_F,5'b10100, `BNE_OP}; // should jump over 96 and assertion will detect this (not desired, wrpc = 96 in I[60])
			instruction[80]  = {12'd0,`RS3, `ADDI_F3,`RD6, `ISAX5_OP}; // dummy -  RS3 should not stall due to DH because it.s flushed. Spawn ISAX5 should be flushed
			instruction[84]  = {12'd60, 5'd0, `ADDI_F3,`RD2, `ADDI_OP}; // dummy
			instruction[88]  = {12'd0, 5'd0, `ADDI_F3,5'd0, `ADDI_OP}; // dummy
			
			instruction[96] = {12'd5, 5'd0, `ADDI_F3,`RD7, `ADDI_OP};  //  X[7] = 5
			instruction[100]= {12'd0, `RS0, `ISAX8_F,5'd0, `ISAX8_OP}; //  Read RegF
			instruction[104]= {12'd0, `RS1, `ISAX8_F,5'd0, `ISAX8_OP}; //  Read RegF
			instruction[108]= {12'd0, `RS2, `ISAX8_F,5'd0, `ISAX8_OP}; //  Read RegF
			instruction[112]= {12'd0, `RS3, `ISAX8_F,5'd0, `ISAX8_OP}; //  Read RegF
			instruction[116]= {12'd0, `RS4, `ISAX8_F,5'd0, `ISAX8_OP}; //  Read RegF
			instruction[120]= {12'd0, `RS5, `ISAX8_F,5'd0, `ISAX8_OP}; //  Read RegF
			instruction[124]= {12'd0, `RS6, `ISAX8_F,5'd0, `ISAX8_OP}; //  Read RegF
			instruction[128]= {12'd0, `RS7, `ISAX8_F,5'd0, `ISAX8_OP}; //  Read RegF last useful
			instruction[132]= {12'd0, `RS8, `ISAX8_F,5'd0, `ISAX8_OP}; //  Read RegF
			instruction[136]= {12'd0, `RS9, `ISAX8_F,5'd0, `ISAX8_OP}; //  Read RegF
			instruction[140]= {12'd0, `RS10, `ISAX8_F,5'd0, `ISAX8_OP}; //  Read RegF
			instruction[144]  = {12'd5, 5'd0, `ADDI_F3,`RD1, `ADDI_OP}; //dummy
			instruction[148]  = {12'd5, 5'd0, `ADDI_F3,`RD1, `ADDI_OP}; //dummy
			instruction[152]  = {12'd5, 5'd0, `ADDI_F3,`RD1, `ADDI_OP}; //dummy
			instruction[156]  = {12'd5, 5'd0, `ADDI_F3,`RD1, `ADDI_OP}; //dummy
			instruction[160]  = {12'd5, 5'd0, `ADDI_F3,`RD1, `ADDI_OP}; //dummy
			
		end
		
		// Compute expected Data 
		always @(posedge clk_s) begin 
		  if(top.ISAXes_inst.WrMem_w_valid_o)
		      expected_data_fr_mem <=top.ISAXes_inst.WrMem_w_o;
		end
		
		// CHECK FUNCTIONALITYv

	assert property (@(posedge clk_s) ((top.ISAXes_inst.RdPC-IMEM_BASE_ADDR) == 148)  |->  ##1  (top.ISAXes_inst.allregs[1] == 5))  
		$display("PASSED instruction[0] worked correctly."); 
	else
		$display("ERR in instruction[0]");
		
	assert property (@(posedge clk_s) ((top.ISAXes_inst.RdPC-IMEM_BASE_ADDR) == 148)  |->  ##1  (top.ISAXes_inst.allregs[2] == 10))  
		$display("PASSED instruction[4] worked correctly."); 
	else
		$display("ERR in instruction[4]");
		
	assert property (@(posedge clk_s) ((top.ISAXes_inst.RdPC-IMEM_BASE_ADDR) == 148)  |->  ##1  (top.ISAXes_inst.allregs[3] == 16))  
		$display("PASSED instruction[8] worked correctly."); 
	else
		$display("ERR in instruction[8]: {`RS1 , `RS2, `ISAX1_F,`RD3, `ISAX1_OP}");
		
	assert property (@(posedge clk_s) ((top.ISAXes_inst.RdPC-IMEM_BASE_ADDR) == 148)  |->  ##1  (top.ISAXes_inst.allregs[4] == 31))  
		$display("PASSED instruction[12 and 16] worked correctly."); 
	else
		$display("ERR in instruction[12 or 16]");
		
	assert property (@(posedge clk_s) ((top.ISAXes_inst.RdPC-IMEM_BASE_ADDR) == 148)  |->  ##1  (top.ISAXes_inst.allregs[5] == 36))  
		$display("PASSED instruction[20] worked correctly."); 
	else
		$display("ERR in instruction[20]");
		
	assert property (@(posedge clk_s) ((top.ISAXes_inst.RdPC-IMEM_BASE_ADDR) == 148)  |->  ##1  (top.ISAXes_inst.allregs[6] == 25))  
		$display("PASSED instruction[52] (spawn wrrd) worked correctly."); 
	else
		$display("ERR in instruction[52] (spawn wrrd)");
		
	assert property (@(posedge clk_s) ((top.ISAXes_inst.RdPC-IMEM_BASE_ADDR) == 148)  |->  ##1  (top.ISAXes_inst.allregs[7] == 5))  
		$display("PASSED instruction[60] worked correctly."); 
	else
		$display("ERR in instruction[60] (WrPC)");

	assert property (@(posedge clk_s) ((top.ISAXes_inst.RdPC-IMEM_BASE_ADDR) == 148)  |->  ##1  (top.ISAXes_inst.data_from_mem == expected_data_fr_mem))  
		$display("PASSED instruction rdmem wrmem worked correctly."); 
	else
		$display("ERR in instruction rdmem wrmem");
		
	assert property (@(posedge clk_s) ((top.ISAXes_inst.RdPC-IMEM_BASE_ADDR) == 148)  |->  ##1  (top.ISAXes_inst.data_from_mem_spawn ==16  ))  
		$display("PASSED instruction rdspawn mem worked correctly."); 
	else
		$display("ERR in instruction rdspawn-mem");
		
		assert property (@(posedge clk_s) ((top.ISAXes_inst.RdPC-IMEM_BASE_ADDR) == 148)  |->  ##1  (data[8] ==80  ))  
		$display("PASSED instruction wrspawn mem worked correctly."); 
	else
		$display("ERR in instruction wrspawn-mem");

		
	assert property (@(posedge clk_s) ((top.ISAXes_inst.RdPC-IMEM_BASE_ADDR) == 148)  |->  ##1  (top.ISAXes_inst.counter_stall >= 5))  
		$display("PASSED stall worked correctly."); 
	else
		$display("ERR in WrStall ");


//////////////////////////////  STOP COPY PASTE ////////////////



		// DUT Signals 
		wire    [31:0]    iBusAhb_HADDR;      
		wire              iBusAhb_HWRITE;     
		wire    [2:0]     iBusAhb_HSIZE;     
		wire    [2:0]     iBusAhb_HBURST;     
		wire    [3:0]     iBusAhb_HPROT;     
		wire    [1:0]     iBusAhb_HTRANS;     
		wire              iBusAhb_HMASTLOCK;  
		wire    [31:0]    iBusAhb_HWDATA;     
		reg     [31:0]    iBusAhb_HRDATA;     
		wire              iBusAhb_HREADY;     
		wire              iBusAhb_HRESP;     
		wire    [31:0]    dBusAhb_HADDR;     
		wire              dBusAhb_HWRITE;     
		wire    [2:0]     dBusAhb_HSIZE;     
		wire    [2:0]     dBusAhb_HBURST;     
		wire    [3:0]     dBusAhb_HPROT;     
		wire    [1:0]     dBusAhb_HTRANS;     
		wire              dBusAhb_HMASTLOCK;  
		wire    [31:0]    dBusAhb_HWDATA;     
		reg     [31:0]    dBusAhb_HRDATA;     
		wire              dBusAhb_HREADY;    
		wire              dBusAhb_HRESP;

		
		 // core specific interface to DMEM, IMEM
		always@(posedge clk_s) 
			 IMem_rdata <= instruction[IMem_raddr-IMEM_BASE_ADDR];
		assign iBusAhb_HREADY = 1;
		assign iBusAhb_HRESP  = 0;
		always@(posedge clk_s) 
			 dBusAhb_HRDATA <= data[dBusAhb_HADDR];
		assign dBusAhb_HREADY = 1;
		assign dBusAhb_HRESP  = 0;
		always  @(posedge clk_s) DMem_wvalid <= dBusAhb_HWRITE && dBusAhb_HTRANS==2;
        assign DMem_wdata = dBusAhb_HWDATA;
		always  @(posedge clk_s)  DMem_waddr <= dBusAhb_HADDR;
			
		// INSTANTIATE DUT
		top top_INST(	
			.debug_bus_cmd_valid(0),              
			.debug_bus_cmd_ready(),              
			.debug_bus_cmd_payload_wr(0),         
			.debug_bus_cmd_payload_address(),    
			.debug_bus_cmd_payload_data(),       
			.debug_bus_rsp_data(),               
			.debug_resetOut(),                   
			.timerInterrupt(0),                   
			.externalInterrupt(0),                
			.softwareInterrupt(0),                
			.debugReset(0),
			.iBusAhbLite3_HADDR                              (IMem_raddr),
			.iBusAhbLite3_HWRITE                             (iBusAhb_HWRITE),
			.iBusAhbLite3_HSIZE                              (iBusAhb_HSIZE),
			.iBusAhbLite3_HBURST                             (iBusAhb_HBURST),
			.iBusAhbLite3_HPROT                              (iBusAhb_HPROT),
			.iBusAhbLite3_HTRANS                             (iBusAhb_HTRANS),
			.iBusAhbLite3_HMASTLOCK                          (iBusAhb_HMASTLOCK),
			.iBusAhbLite3_HWDATA                             (iBusAhb_HWDATA),
			.iBusAhbLite3_HRDATA                             (IMem_rdata),
			.iBusAhbLite3_HREADY                             (iBusAhb_HREADY),
			.iBusAhbLite3_HRESP                              (iBusAhb_HRESP),
			.dBusAhbLite3_HADDR                              (dBusAhb_HADDR),
			.dBusAhbLite3_HWRITE                             (dBusAhb_HWRITE),
			.dBusAhbLite3_HSIZE                              (dBusAhb_HSIZE),
			.dBusAhbLite3_HBURST                             (dBusAhb_HBURST),
			.dBusAhbLite3_HPROT                              (dBusAhb_HPROT),
			.dBusAhbLite3_HTRANS                             (dBusAhb_HTRANS),
			.dBusAhbLite3_HMASTLOCK                          (dBusAhb_HMASTLOCK),
			.dBusAhbLite3_HWDATA                             (dBusAhb_HWDATA),
			.dBusAhbLite3_HRDATA                             (dBusAhb_HRDATA),
			.dBusAhbLite3_HREADY                             (dBusAhb_HREADY),
			.dBusAhbLite3_HRESP                              (dBusAhb_HRESP),

			.clk_i                                      (clk_s),
			.rst_i                                      (rst_s) 	
		);


		
endmodule



//////////////////////////////////////////////////////    TOP MODULE  //////////////////////////////////////////////////////


module top( 
	input         		clk_i,
    input         		rst_i,
	input               debug_bus_cmd_valid,
	output reg          debug_bus_cmd_ready,
	input               debug_bus_cmd_payload_wr,
	input      [7:0]    debug_bus_cmd_payload_address,
	input      [31:0]   debug_bus_cmd_payload_data,
	output reg [31:0]   debug_bus_rsp_data,
	output              debug_resetOut,
	input               timerInterrupt,
	input               externalInterrupt,
	input               softwareInterrupt,
	output     [31:0]   iBusAhbLite3_HADDR,
	output              iBusAhbLite3_HWRITE,
	output     [2:0]    iBusAhbLite3_HSIZE,
	output     [2:0]    iBusAhbLite3_HBURST,
	output     [3:0]    iBusAhbLite3_HPROT,
	output     [1:0]    iBusAhbLite3_HTRANS,
	output              iBusAhbLite3_HMASTLOCK,
	output     [31:0]   iBusAhbLite3_HWDATA,
	input      [31:0]   iBusAhbLite3_HRDATA,
	input               iBusAhbLite3_HREADY,
	input               iBusAhbLite3_HRESP,
	output     [31:0]   dBusAhbLite3_HADDR,
	output              dBusAhbLite3_HWRITE,
	output     [2:0]    dBusAhbLite3_HSIZE,
	output     [2:0]    dBusAhbLite3_HBURST,
	output     [3:0]    dBusAhbLite3_HPROT,
	output reg [1:0]    dBusAhbLite3_HTRANS,
	output              dBusAhbLite3_HMASTLOCK,
	output     [31:0]   dBusAhbLite3_HWDATA,
	input      [31:0]   dBusAhbLite3_HRDATA,
	input               dBusAhbLite3_HREADY,
	input               dBusAhbLite3_HRESP, 
	input				debugReset, 
	output [31:0] res_3_val);
	
	// Interface from SCAL to the Core
    
    wire  [32 -1 : 0] RdPC_1_i;// ISAX
    wire [32 -1 : 0] WrMem_2_o;// ISAX
    wire [32 -1 : 0] WrMem_addr_2_o;// ISAX
    wire  WrMem_validReq_2_o;// ISAX
    wire  WrMem_validReq_1_o;// ISAX
    wire [32 -1 : 0] WrRD_2_o;// ISAX
    wire  WrRD_validReq_2_o;// ISAX
    wire [32 -1 : 0] WrRD_3_o;// ISAX
    wire  WrRD_validReq_3_o;// ISAX
    wire   RdStall_3_i;// ISAX
    wire  [32 -1 : 0] RdInstr_2_i;// ISAX
    wire [32 -1 : 0] WrPC_2_o;// ISAX
    wire  WrPC_validReq_2_o;// ISAX
    wire  WrStall_3_o;// ISAX
    wire  [32 -1 : 0] RdRS1_2_i;// ISAX
    wire  [32 -1 : 0] RdRS1_3_i;// ISAX
    wire  [32 -1 : 0] RdRS2_2_i;// ISAX
    wire  [32 -1 : 0] RdRS2_3_i;// ISAX
    wire  [32 -1 : 0] RdMem_2_i;// ISAX
    wire  RdMem_validReq_2_o;// ISAX
    wire  RdMem_validReq_1_o;// ISAX
    wire [32 -1 : 0] WrMem_spawn_5_o;// ISAX
    wire  Mem_spawn_write_5_o;// ISAX
    wire [32 -1 : 0] Mem_spawn_addr_5_o;// ISAX
    wire   Mem_spawn_validResp_5_i;// ISAX
    wire  Mem_spawn_validReq_5_o;// ISAX
    wire  [32 -1 : 0] RdMem_spawn_5_i;// ISAX
    wire [32 -1 : 0] WrRD_spawn_5_o;// ISAX
    wire [5 -1 : 0] WrRD_spawn_addr_5_o;// ISAX
    wire   WrRD_spawn_validResp_5_i;// ISAX
    wire  WrRD_spawn_validReq_5_o;// ISAX
    wire   RdFlush_1_i;// ISAX
    wire   RdFlush_2_i;// ISAX
    wire   RdFlush_3_i;// ISAX
    wire   RdFlush_4_i;// ISAX
    wire   RdStall_2_i;// ISAX
    wire   RdStall_4_i;// ISAX
    wire  [32 -1 : 0] RdInstr_1_i;// ISAX
    wire  [32 -1 : 0] RdInstr_3_i;// ISAX
    wire   WrFlush_0_o;// ISAX
    wire   WrFlush_1_o;// ISAX
    wire   WrStall_1_o;// ISAX
    wire   WrStall_2_o;// ISAX
    wire   WrStall_4_o;// ISAX
    wire   ISAX_spawnAllowed_2_i;// ISAX
    
	//////////////////////////////   COPY PASTE ////////////////
  // ISAX1 - rdrs and wrrd
  wire  [31:0] RdRS1_n_i;
  wire  [31:0] RdRS2_n_i;
  wire [31:0] WrRd_m_o; 
  // ISAX2 -later rdrs and later wrrd with valid and RdIValid
  wire  [31:0] RdRS1_mplus1_i;
  wire  [31:0] RdRS2_mplus1_i;
  wire 		RdIValid_ISAX2_mplus1_i;
  wire [31:0] WrRd_mplus1_o;
  wire        WrRd_mplus1_valid_o;
  // ISAX3 wrmem and memvalid
  wire [31:0] WrMem_w_o;
  wire [31:0] WrMem_w_addr_o;
  wire        WrMem_w_valid_o;
  wire  [31:0] RdInstr_w_i;
  wire         RdIValid_ISAX3_w_i;
  // ISAX4 rdinstr and rdmem
  wire  [31:0] RdMem_w_i;
  // ISAX5 MemSpawn WrRd spawn
  wire [31:0] WrRD_spawn_o; 
  wire  [31:0] RdMem_spawn_i;
  wire         RdMem_spawn_resp_i;
  wire [31:0] RdMem_spawn_addr_o;
  wire        RdMem_spawn_valid_o;
  wire      RdMem_addr_valid_2_o;
  wire 		RdIValid_ISAX5_i; 
  // ISAX6 MemSpawn WrRd spawn
  wire  [31:0] WrMem_spawn_o;
  wire         WrMem_spawn_resp_i;
  wire [31:0] WrMem_spawn_addr_o;
  wire        WrMem_spawn_valid_o;
  // ISAX7 WrPC; RdPC and stall 
  wire 		WrStall; 
  wire 		RdStall; 
  wire 		WrPC_valid; 
  wire [31:0]  WrPC; 
  wire [31:0]  RdPC; 
  // ISAX8 Rd all Regs 
  wire [31:0]  RdRS_i; 
  wire 		RdIValid_ISAX8_i;
  
	
// ISAX Module 
ISAXes ISAXes_inst(
	clk_i,
	rst_i,
	// ISAX1 - rdrs and wrrd
	RdRS1_n_i,
	RdRS2_n_i,
	WrRd_m_o, 
	// ISAX2 -later rdrs and later wrrd with valid and RdIValid
	RdRS1_mplus1_i,
	RdRS2_mplus1_i,
	RdIValid_ISAX2_mplus1_i,
	WrRd_mplus1_o,
	WrRd_mplus1_valid_o,
	// ISAX3 wrmem and memvalid
	WrMem_w_o,
	WrMem_w_addr_o,
	WrMem_w_valid_o,
	RdInstr_w_i,
	RdIValid_ISAX3_w_i,
	// ISAX4 rdinstr and rdmem
	RdMem_w_i,
	// ISAX5 MemSpawn WrRd spawn
	WrRD_spawn_o, 
	RdMem_spawn_i,
	RdMem_spawn_resp_i,
	RdMem_spawn_addr_o,
	RdMem_spawn_valid_o,
	RdIValid_ISAX5_i, 
	// ISAX6 MemSpawn WrRd spawn
	WrMem_spawn_o,
	WrMem_spawn_resp_i,
	WrMem_spawn_addr_o,
	WrMem_spawn_valid_o,
	// ISAX7 WrPC, RdPC and stall 
	WrStall,
	RdStall, 
	WrPC_valid, 
	WrPC, 
	RdPC, 
	// ISAX8 Rd all Regs 
	RdRS1_n_i, 
	RdIValid_ISAX8_i);

	
SCAL SCAL_inst(   
    .RdPC_1_o(RdPC),// ISAX
    .WrMem_ISAX3_2_i(WrMem_w_o),// ISAX	
    .WrMem_addr_ISAX3_2_i(WrMem_w_addr_o),// ISAX
    .WrMem_validReq_ISAX3_2_i(WrMem_w_valid_o),// ISAX
    .WrRD_ISAX1_2_i(WrRd_m_o),// ISAX
    .WrRD_ISAX2_3_i(WrRd_mplus1_o),// ISAX
    .WrRD_validReq_ISAX2_3_i(WrRd_mplus1_valid_o),// ISAX
    .RdStall_3_o(RdStall),// ISAX
    .RdInstr_2_o(RdInstr_w_i),// ISAX
    .WrPC_ISAX7_2_i(WrPC),// ISAX
    .WrPC_validReq_ISAX7_2_i(WrPC_valid),// ISAX
    .WrStall_ISAX7_3_i(WrStall),// ISAX
    .RdRS1_2_o(RdRS1_n_i),// ISAX
    .RdRS1_3_o(RdRS1_mplus1_i),// ISAX
    .RdRS2_2_o(RdRS2_n_i),// ISAX
    .RdRS2_3_o(RdRS2_mplus1_i),// ISAX
	.RdIValid_ISAX3_2_o(RdIValid_ISAX3_w_i),// ISAX
    .RdIValid_ISAX8_2_o(RdIValid_ISAX8_i),// ISAX
    .RdIValid_ISAX5_2_o(RdIValid_ISAX5_i),// ISAX
    .RdIValid_ISAX2_3_o(RdIValid_ISAX2_mplus1_i),// ISAX
    .RdMem_2_o(RdMem_w_i),// ISAX
    .WrMem_spawn_ISAX6_5_i(WrMem_spawn_o),// ISAX
    .WrMem_spawn_addr_ISAX6_5_i(WrMem_spawn_addr_o),// ISAX
    .WrMem_spawn_validResp_ISAX6_5_o(WrMem_spawn_resp_i),// ISAX
    .WrMem_spawn_validReq_ISAX6_5_i(WrMem_spawn_valid_o),// ISAX
    .RdMem_spawn_ISAX5_5_o(RdMem_spawn_i),// ISAX
	.RdMem_spawn_addr_ISAX5_5_i(RdMem_spawn_addr_o),// ISAX
    .RdMem_spawn_validReq_ISAX5_5_i(RdMem_spawn_valid_o),// ISAX
    .RdMem_spawn_validResp_ISAX5_5_o(RdMem_spawn_resp_i),// ISAX
    .WrRD_spawn_ISAX5_5_i(WrRD_spawn_o),// ISAX
    .Commited_rd_spawn_WrRD_spawn_5_o(Commited_rd_spawn_WrRD_spawn_5_o),// ISAX
    .Commited_rd_spawn_validReq_WrRD_spawn_5_o(Commited_rd_spawn_validReq_WrRD_spawn_5_o),// ISAX
    
/////////////////////////////////////////////////////////////    END OF COPY PASTE ///////////////
    .RdPC_1_i(RdPC_1_i),// ISAX
    .WrMem_2_o(WrMem_2_o),// ISAX
    .WrMem_addr_2_o(WrMem_addr_2_o),// ISAX
    .WrMem_addr_valid_2_o(WrMem_addr_valid_2_o),
    .WrMem_validReq_2_o(WrMem_validReq_2_o),// ISAX
    .WrMem_validReq_1_o(WrMem_validReq_1_o),// ISAX
    .WrRD_2_o(WrRD_2_o),// ISAX
    .WrRD_validReq_2_o(WrRD_validReq_2_o),// ISAX
    .WrRD_3_o(WrRD_3_o),// ISAX
    .WrRD_validReq_3_o(WrRD_validReq_3_o),// ISAX
    .RdStall_3_i(RdStall_3_i),// ISAX
    .RdInstr_2_i(RdInstr_2_i),// ISAX
    .WrPC_2_o(WrPC_2_o),// ISAX
    .WrPC_validReq_2_o(WrPC_validReq_2_o),// ISAX
    .WrStall_3_o(WrStall_3_o),// ISAX
    .RdRS1_2_i(RdRS1_2_i),// ISAX
    .RdRS1_3_i(RdRS1_3_i),// ISAX
    .RdRS2_2_i(RdRS2_2_i),// ISAX
    .RdRS2_3_i(RdRS2_3_i),// ISAX
    .RdMem_2_i(RdMem_2_i),// ISAX
    .RdMem_validReq_2_o(RdMem_validReq_2_o),// ISAX
    .RdMem_validReq_1_o(RdMem_validReq_1_o),// ISAX
    .RdMem_addr_valid_2_o(RdMem_addr_valid_2_o),
    .WrMem_spawn_5_o(WrMem_spawn_5_o),// ISAX
    .Mem_spawn_write_5_o(Mem_spawn_write_5_o),
    .Mem_spawn_addr_5_o(Mem_spawn_addr_5_o),// ISAX
    .Mem_spawn_validResp_5_i(Mem_spawn_validResp_5_i),// ISAX
    .Mem_spawn_validReq_5_o(Mem_spawn_validReq_5_o),// ISAX
    .RdMem_spawn_5_i(RdMem_spawn_5_i),// ISAX
    .WrRD_spawn_5_o(WrRD_spawn_5_o),// ISAX
    .WrRD_spawn_addr_5_o(WrRD_spawn_addr_5_o),// ISAX
    .WrRD_spawn_validResp_5_i(WrRD_spawn_validResp_5_i),// ISAX
    .WrRD_spawn_validReq_5_o(WrRD_spawn_validReq_5_o),// ISAX
    .RdFlush_1_i(RdFlush_1_i),// ISAX
    .RdFlush_2_i(RdFlush_2_i),// ISAX
    .RdFlush_3_i(RdFlush_3_i),// ISAX
    .RdFlush_4_i(RdFlush_4_i),// ISAX
    .RdStall_1_i(RdStall_1_i),
    .RdStall_2_i(RdStall_2_i),// ISAX
    .RdStall_4_i(RdStall_4_i),// ISAX
    .RdInstr_1_i(RdInstr_1_i),// ISAX
    .RdInstr_3_i(RdInstr_3_i),// ISAX
    .WrFlush_0_o(WrFlush_0_o),// ISAX
    .WrFlush_1_o(WrFlush_1_o),// ISAX
    .WrStall_1_o(WrStall_1_o),// ISAX
    .WrStall_2_o(WrStall_2_o),// ISAX
    .WrStall_4_o(WrStall_4_o),// ISAX
    .ISAX_spawnAllowed_2_i(ISAX_spawnAllowed_2_i),// ISAX
    
    .clk_i(clk_i),
    .rst_i(rst_i)
);
	
	
// Core    
VexRiscv VexRiscv_inst(
	.debug_bus_cmd_valid(debug_bus_cmd_valid),
	.debug_bus_cmd_ready(debug_bus_cmd_ready),
	.debug_bus_cmd_payload_wr(debug_bus_cmd_payload_wr),
	.debug_bus_cmd_payload_address(debug_bus_cmd_payload_address),
	.debug_bus_cmd_payload_data(debug_bus_cmd_payload_data),
	.debug_bus_rsp_data(debug_bus_rsp_data),
	.debug_resetOut(debug_resetOut),
	.timerInterrupt(timerInterrupt),
	.externalInterrupt(externalInterrupt),
	.softwareInterrupt(softwareInterrupt),
 
	
  .RdPC_1_o(RdPC_1_i),
  .RdFlush_1_o(RdFlush_1_i),
  .RdInstr_1_o(RdInstr_1_i),
  .WrStall_1_i(WrStall_1_o),
  .WrFlush_1_i(WrFlush_1_o),
  .RdFlush_2_o(RdFlush_2_i),
  .WrMem_2_i(WrMem_2_o),
  .WrMem_addr_valid_2_i(WrMem_addr_valid_2_o),
  .WrMem_addr_2_i(WrMem_addr_2_o),
  .WrMem_validReq_2_i(WrMem_validReq_2_o),
  .WrRD_2_i(WrRD_2_o),
  .WrRD_validReq_2_i(WrRD_validReq_2_o),
  .RdStall_2_o(RdStall_2_i),
  .RdInstr_2_o(RdInstr_2_i),
  .WrPC_2_i(WrPC_2_o),
  .WrPC_validReq_2_i(WrPC_validReq_2_o),
  .WrStall_2_i(WrStall_2_o),
  .RdRS1_2_o(RdRS1_2_i),
  .RdRS2_2_o(RdRS2_2_i),
  .RdMem_2_o(RdMem_2_i),
  .RdMem_addr_valid_2_i(RdMem_addr_valid_2_o),
  .RdMem_addr_2_i(),
  .RdMem_validReq_2_i(RdMem_validReq_2_o),
  .WrMem_spawn_5_i(WrMem_spawn_5_o),
  .RdMem_spawn_5_o(RdMem_spawn_5_i),
  .Mem_spawn_addr_5_i(Mem_spawn_addr_5_o),
  .Mem_spawn_validResp_5_o(Mem_spawn_validResp_5_i),
  .Mem_spawn_validReq_5_i(Mem_spawn_validReq_5_o),
  .Mem_spawn_write_5_i(Mem_spawn_write_5_o),
  .WrFlush_2_i(),
  .ISAX_spawnAllowed_2_o(ISAX_spawnAllowed_2_i),
  .RdMem_validReq_1_i(RdMem_validReq_1_o),
  .WrMem_validReq_1_i(WrMem_validReq_1_o),
  .RdFlush_3_o(RdFlush_3_i),
  .WrRD_3_i(WrRD_3_o),
  .WrRD_validReq_3_i(WrRD_validReq_3_o),
  .RdStall_3_o(RdStall_3_i),
  .RdInstr_3_o(RdInstr_3_i),
  .WrStall_3_i(WrStall_3_o),
  .RdRS1_3_o(RdRS1_3_i),
  .RdRS2_3_o(RdRS2_3_i),
  .WrFlush_3_i(),
  .RdFlush_4_o(RdFlush_4_i),
  .RdStall_4_o(RdStall_4_i),
  .WrStall_4_i(WrStall_4_o),
  .WrRD_spawn_5_i(WrRD_spawn_5_o),
  .WrRD_spawn_addr_5_i(WrRD_spawn_addr_5_o),
  .WrRD_spawn_validResp_5_o(WrRD_spawn_validResp_5_i),
  .WrRD_spawn_validReq_5_i(WrRD_spawn_validReq_5_o),
  .WrFlush_4_i(0),
  .RdStall_1_o(RdStall_1_i),
  
	.iBusAhbLite3_HADDR        (iBusAhbLite3_HADDR     ),
	.iBusAhbLite3_HWRITE       (iBusAhbLite3_HWRITE    ),
	.iBusAhbLite3_HSIZE        (iBusAhbLite3_HSIZE     ),
	.iBusAhbLite3_HBURST       (iBusAhbLite3_HBURST    ),
	.iBusAhbLite3_HPROT        (iBusAhbLite3_HPROT     ),
	.iBusAhbLite3_HTRANS       (iBusAhbLite3_HTRANS    ),
	.iBusAhbLite3_HMASTLOCK    (iBusAhbLite3_HMASTLOCK ),
	.iBusAhbLite3_HWDATA       (iBusAhbLite3_HWDATA    ),
	.iBusAhbLite3_HRDATA       (iBusAhbLite3_HRDATA    ),
	.iBusAhbLite3_HREADY       (iBusAhbLite3_HREADY    ),
	.iBusAhbLite3_HRESP        (iBusAhbLite3_HRESP     ),
	.dBusAhbLite3_HADDR        (dBusAhbLite3_HADDR     ),
	.dBusAhbLite3_HWRITE       (dBusAhbLite3_HWRITE    ),
	.dBusAhbLite3_HSIZE        (dBusAhbLite3_HSIZE     ),
	.dBusAhbLite3_HBURST       (dBusAhbLite3_HBURST    ),
	.dBusAhbLite3_HPROT        (dBusAhbLite3_HPROT     ),
	.dBusAhbLite3_HTRANS       (dBusAhbLite3_HTRANS    ),
	.dBusAhbLite3_HMASTLOCK    (dBusAhbLite3_HMASTLOCK ),
	.dBusAhbLite3_HWDATA       (dBusAhbLite3_HWDATA    ),
	.dBusAhbLite3_HRDATA       (dBusAhbLite3_HRDATA    ),
	.dBusAhbLite3_HREADY       (dBusAhbLite3_HREADY    ),
	.dBusAhbLite3_HRESP        (dBusAhbLite3_HRESP     ),
	.clk                       (clk_i                  ),
	.reset                     (rst_i                  ),
	.debugReset                (debugReset                  )
);	
    	
endmodule





