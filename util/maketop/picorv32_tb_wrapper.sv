module testbench(
    input clk,
    input rst,
    input logic [31:0] irq_i,

    output trap,

    //AXI Instruction Bus
    output logic         m_axi_instr_AWVALID,
    input  logic         m_axi_instr_AWREADY,
    output logic [31:0]  m_axi_instr_AWADDR,
    output logic [2:0]   m_axi_instr_AWSIZE,

    output logic         m_axi_instr_WVALID,
    input  logic         m_axi_instr_WREADY,
    output logic [31:0]  m_axi_instr_WDATA,
    output logic [3:0]   m_axi_instr_WSTRB,

    input  logic         m_axi_instr_BVALID,
    output logic         m_axi_instr_BREADY,
    input  logic [1:0]   m_axi_instr_BRESP,

    output logic         m_axi_instr_ARVALID,
    input  logic         m_axi_instr_ARREADY,
    output logic [31:0]  m_axi_instr_ARADDR,
    output logic [2:0]   m_axi_instr_ARSIZE,

    input  logic         m_axi_instr_RVALID,
    output logic         m_axi_instr_RREADY,
    input  logic [31:0]  m_axi_instr_RDATA,
    input  logic [1:0]   m_axi_instr_RRESP,

    //AXI Data Bus
    output logic         m_axi_data_AWVALID,
    input  logic         m_axi_data_AWREADY,
    output logic [31:0]  m_axi_data_AWADDR,
    output logic [2:0]   m_axi_data_AWSIZE,

    output logic         m_axi_data_WVALID,
    input  logic         m_axi_data_WREADY,
    output logic [31:0]  m_axi_data_WDATA,
    output logic [3:0]   m_axi_data_WSTRB,

    input  logic         m_axi_data_BVALID,
    output logic         m_axi_data_BREADY,
    input  logic [1:0]   m_axi_data_BRESP,

    output logic         m_axi_data_ARVALID,
    input  logic         m_axi_data_ARREADY,
    output logic [31:0]  m_axi_data_ARADDR,
    output logic [2:0]   m_axi_data_ARSIZE,

    input  logic         m_axi_data_RVALID,
    output logic         m_axi_data_RREADY,
    input  logic [31:0]  m_axi_data_RDATA,
    input  logic [1:0]   m_axi_data_RRESP
);

    wire PICORV32_mem_valid;
    wire PICORV32_mem_ready;
    wire        PICORV32_mem_instr;
    wire [31:0] PICORV32_mem_addr;
    wire [31:0] PICORV32_mem_wdata;
    wire [3:0]  PICORV32_mem_wstrb;
    wire [31:0] PICORV32_mem_rdata;

    assign PICORV32_mem_ready = PICORV32_mem_instr ? (m_axi_instr_BVALID || m_axi_instr_RVALID) : (m_axi_data_BVALID || m_axi_data_RVALID);
    assign PICORV32_mem_rdata = PICORV32_mem_instr ? m_axi_instr_RDATA : m_axi_data_RDATA;

    logic mem_addr_started;
    logic mem_write_started;
    assign m_axi_data_AWVALID = PICORV32_mem_valid && !PICORV32_mem_instr && (|PICORV32_mem_wstrb) && !mem_addr_started;
    assign m_axi_data_WVALID = PICORV32_mem_valid && !PICORV32_mem_instr && (|PICORV32_mem_wstrb) && !mem_write_started;
    assign m_axi_data_ARVALID = PICORV32_mem_valid && !PICORV32_mem_instr && !(|PICORV32_mem_wstrb) && !mem_addr_started;
    assign m_axi_data_BREADY = 1;
    assign m_axi_data_RREADY = 1;
    assign m_axi_instr_WVALID = 0;
    assign m_axi_instr_AWVALID = 0;
    assign m_axi_instr_ARVALID = PICORV32_mem_valid && PICORV32_mem_instr && !mem_addr_started;
    assign m_axi_instr_BREADY = 0;
    assign m_axi_instr_RREADY = 1;

    assign m_axi_data_ARADDR = PICORV32_mem_addr;
    assign m_axi_data_AWADDR = PICORV32_mem_addr;
    assign m_axi_instr_ARADDR = PICORV32_mem_addr;
    assign m_axi_instr_AWADDR = PICORV32_mem_addr;
    assign m_axi_data_ARSIZE = 3'd2; //4 bytes
    assign m_axi_data_AWSIZE = 3'd2; //4 bytes
    assign m_axi_instr_ARSIZE = 3'd2; //4 bytes
    assign m_axi_instr_AWSIZE = 3'd2; //4 bytes

    assign m_axi_data_WDATA = PICORV32_mem_wdata;
    assign m_axi_data_WSTRB = PICORV32_mem_wstrb;

    always_ff @(posedge clk) begin
        if (rst) begin
            mem_addr_started <= 0;
            mem_write_started <= 0;
        end
        else begin
            mem_addr_started <= !rst && (mem_addr_started || ((m_axi_data_AWVALID && m_axi_data_AWREADY) || (m_axi_data_ARVALID && m_axi_data_ARREADY) || (m_axi_instr_ARVALID && m_axi_instr_ARREADY)));
            mem_write_started <= !rst && (mem_write_started || (m_axi_data_WVALID && m_axi_data_WREADY));
            if (PICORV32_mem_ready) begin
                mem_addr_started <= 0;
                mem_write_started <= 0;
            end
        end
    end

    wire [31:0] eoi;
    wire [35:0] trace_data;
    wire         trace_valid;

    top top_INST(
        .clk(clk),
        .rst(rst),
        .trap(trap),

        .mem_valid(PICORV32_mem_valid),
        .mem_instr(PICORV32_mem_instr),
        .mem_ready(PICORV32_mem_ready),

        .mem_addr(PICORV32_mem_addr),
        .mem_wdata(PICORV32_mem_wdata),
        .mem_wstrb(PICORV32_mem_wstrb),
        .mem_rdata(PICORV32_mem_rdata),

        .irq(irq_i),
        .eoi(eoi),

        .trace_valid(trace_valid),
        .trace_data(trace_data)

        );

    `ifdef SIM_DUMP_VCD
    initial begin
        $dumpfile ("dump.vcd");
        $dumpvars (0, testbench);
        #1;
    end
    `endif

endmodule
