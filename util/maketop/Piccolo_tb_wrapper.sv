module testbench(
    input clk,
    input rst,
    input logic [31:0] irq_i,

    //AXI Instruction Bus
    output logic         m_axi_instr_AWVALID,
    input  logic         m_axi_instr_AWREADY,
    output logic [31:0]  m_axi_instr_AWADDR,
    output logic [2:0]   m_axi_instr_AWSIZE,
    output logic [3:0]   m_axi_instr_AWID,
    output logic [7:0]   m_axi_instr_AWLEN,
    output logic [1:0]   m_axi_instr_AWBURST,

    output logic         m_axi_instr_WVALID,
    input  logic         m_axi_instr_WREADY,
    output logic [63:0]  m_axi_instr_WDATA,
    output logic [7:0]   m_axi_instr_WSTRB,
    output logic         m_axi_instr_WLAST,

    input  logic         m_axi_instr_BVALID,
    output logic         m_axi_instr_BREADY,
    input  logic [1:0]   m_axi_instr_BRESP,
    input  logic [3:0]   m_axi_instr_BID,
    
    output logic         m_axi_instr_ARVALID,
    input  logic         m_axi_instr_ARREADY,
    output logic [31:0]  m_axi_instr_ARADDR,
    output logic [2:0]   m_axi_instr_ARSIZE,
    output logic [3:0]   m_axi_instr_ARID,
    output logic [7:0]   m_axi_instr_ARLEN,
    output logic [1:0]   m_axi_instr_ARBURST,
    
    input  logic         m_axi_instr_RVALID,
    output logic         m_axi_instr_RREADY,
    input  logic [63:0]  m_axi_instr_RDATA,
    input  logic [1:0]   m_axi_instr_RRESP,
    input  logic [3:0]   m_axi_instr_RID,
    input  logic         m_axi_instr_RLAST,

    //AXI Data Bus
    output logic         m_axi_data_AWVALID,
    input  logic         m_axi_data_AWREADY,
    output logic [31:0]  m_axi_data_AWADDR,
    output logic [2:0]   m_axi_data_AWSIZE,
    output logic [3:0]   m_axi_data_AWID,
    output logic [7:0]   m_axi_data_AWLEN,
    output logic [1:0]   m_axi_data_AWBURST,

    output logic         m_axi_data_WVALID,
    input  logic         m_axi_data_WREADY,
    output logic [63:0]  m_axi_data_WDATA,
    output logic [7:0]   m_axi_data_WSTRB,
    output logic         m_axi_data_WLAST,
    
    input  logic         m_axi_data_BVALID,
    output logic         m_axi_data_BREADY,
    input  logic [1:0]   m_axi_data_BRESP,
    input  logic [3:0]   m_axi_data_BID,
    
    output logic         m_axi_data_ARVALID,
    input  logic         m_axi_data_ARREADY,
    output logic [31:0]  m_axi_data_ARADDR,
    output logic [2:0]   m_axi_data_ARSIZE,
    output logic [3:0]   m_axi_data_ARID,
    output logic [7:0]   m_axi_data_ARLEN,
    output logic [1:0]   m_axi_data_ARBURST,
    
    input  logic         m_axi_data_RVALID,
    output logic         m_axi_data_RREADY,
    input  logic [63:0]  m_axi_data_RDATA,
    input  logic [1:0]   m_axi_data_RRESP,
    input  logic [3:0]   m_axi_data_RID,
    input  logic         m_axi_data_RLAST

    `ifdef RT_LIFE
        , output wire [32-1:0] rt_life_pc_o
        , output wire rt_life_valid_o
        , output wire [32-1:0] rt_life_inst_o
        , output wire [32-1:0] rt_life_next_pc_o
        , input  wire rt_life_stall_i
    `endif

);
    reg rst_r;
    always_ff @(posedge clk) begin
        rst_r <= rst;
    end
    wire do_reset_piccolo = rst_r && !rst;
    reg done;
    reg rst2;
    wire rdy_put;
    always@(posedge clk) begin
        rst2 <= 0;
        if(~done && rdy_put && do_reset_piccolo) begin
            rst2 <= 1;
            done <= 1;
        end
        if(rst2)
            rst2 <= 0;
        if(rst)
            done <= 0;
    end

    wire [7:0] cpu_imem_master_awlen;
    wire [7:0] cpu_imem_master_arlen;
    wire [7:0] cpu_dmem_master_awlen;
    wire [7:0] cpu_dmem_master_arlen;

    top top_piccolo_inst(.clk(clk),
        .rst(rst),
        .set_verbosity_verbosity(2),
        .set_verbosity_logdelay(2),
        .EN_set_verbosity(1),
        .RDY_set_verbosity(),

        .cpu_reset_server_request_put(1),
        .EN_cpu_reset_server_request_put(do_reset_piccolo && (~done)),
        .RDY_cpu_reset_server_request_put(rdy_put),

        .EN_cpu_reset_server_response_get(0),
        .cpu_reset_server_response_get(),
        .RDY_cpu_reset_server_response_get(),
        //.ndm_reset_client_response_put(0),
        .cpu_dmem_master_arready(m_axi_data_ARREADY),
        .cpu_dmem_master_awready(m_axi_data_AWREADY),
        .cpu_dmem_master_bid    (m_axi_data_BID),
        .cpu_dmem_master_bresp  (m_axi_data_BRESP),
        .cpu_dmem_master_bvalid (m_axi_data_BVALID),
        .cpu_dmem_master_rdata  (m_axi_data_RDATA),
        .cpu_dmem_master_rid    (m_axi_data_RID),
        .cpu_dmem_master_rlast  (m_axi_data_RLAST),
        .cpu_dmem_master_rresp  (m_axi_data_RRESP),
        .cpu_dmem_master_rvalid (m_axi_data_RVALID),
        .cpu_dmem_master_wready (m_axi_data_WREADY),

        .cpu_imem_master_arready(m_axi_instr_ARREADY),
        .cpu_imem_master_awready(m_axi_instr_AWREADY),
        .cpu_imem_master_bid    (m_axi_instr_BID),
        .cpu_imem_master_bresp  (m_axi_instr_BRESP),
        .cpu_imem_master_bvalid (m_axi_instr_BVALID),
        .cpu_imem_master_rdata  (m_axi_instr_RDATA),
        .cpu_imem_master_rid    (m_axi_instr_RID),
        .cpu_imem_master_rlast  (m_axi_instr_RLAST),
        .cpu_imem_master_rresp  (m_axi_instr_RRESP),
        .cpu_imem_master_rvalid (m_axi_instr_RVALID),
        .cpu_imem_master_wready (m_axi_instr_WREADY),

        .cpu_imem_master_awvalid(m_axi_instr_AWVALID),
        .cpu_imem_master_awid   (m_axi_instr_AWID),
        .cpu_imem_master_awaddr (m_axi_instr_AWADDR),
        .cpu_imem_master_awlen  (m_axi_instr_AWLEN),
        .cpu_imem_master_awsize (m_axi_instr_AWSIZE),
        .cpu_imem_master_awburst(m_axi_instr_AWBURST),
        .cpu_imem_master_awlock (),
        .cpu_imem_master_awcache(),
        .cpu_imem_master_awprot( ),
        .cpu_imem_master_awqos(  ),
        .cpu_imem_master_awregion( ),
        .cpu_imem_master_wvalid (m_axi_instr_WVALID),
        .cpu_imem_master_wdata  (m_axi_instr_WDATA),
        .cpu_imem_master_wstrb  (m_axi_instr_WSTRB),
        .cpu_imem_master_wlast  (m_axi_instr_WLAST),
        .cpu_imem_master_bready (m_axi_instr_BREADY),
        .cpu_imem_master_arvalid(m_axi_instr_ARVALID),
        .cpu_imem_master_arid   (m_axi_instr_ARID),
        .cpu_imem_master_araddr (m_axi_instr_ARADDR),
        .cpu_imem_master_arlen  (m_axi_instr_ARLEN),
        .cpu_imem_master_arsize (m_axi_instr_ARSIZE),
        .cpu_imem_master_arburst(m_axi_instr_ARBURST),
        .cpu_imem_master_arlock(),
        .cpu_imem_master_arcache(),
        .cpu_imem_master_arprot(),
        .cpu_imem_master_arqos(),
        .cpu_imem_master_arregion(),
        .cpu_imem_master_rready (m_axi_instr_RREADY),

        .cpu_dmem_master_awvalid(m_axi_data_AWVALID),
        .cpu_dmem_master_awid   (m_axi_data_AWID),
        .cpu_dmem_master_awaddr (m_axi_data_AWADDR),
        .cpu_dmem_master_awlen  (m_axi_data_AWLEN),
        .cpu_dmem_master_awsize (m_axi_data_AWSIZE),
        .cpu_dmem_master_awburst(m_axi_data_AWBURST),
        .cpu_dmem_master_awlock (),
        .cpu_dmem_master_awcache(),
        .cpu_dmem_master_awprot( ),
        .cpu_dmem_master_awqos(  ),
        .cpu_dmem_master_awregion( ),
        .cpu_dmem_master_wvalid (m_axi_data_WVALID),
        .cpu_dmem_master_wdata  (m_axi_data_WDATA),
        .cpu_dmem_master_wstrb  (m_axi_data_WSTRB),
        .cpu_dmem_master_wlast  (m_axi_data_WLAST),
        .cpu_dmem_master_bready (m_axi_data_BREADY),
        .cpu_dmem_master_arvalid(m_axi_data_ARVALID),
        .cpu_dmem_master_arid   (m_axi_data_ARID),
        .cpu_dmem_master_araddr (m_axi_data_ARADDR),
        .cpu_dmem_master_arlen  (m_axi_data_ARLEN),
        .cpu_dmem_master_arsize (m_axi_data_ARSIZE),
        .cpu_dmem_master_arburst(m_axi_data_ARBURST),
        .cpu_dmem_master_arlock(),
        .cpu_dmem_master_arcache(),
        .cpu_dmem_master_arprot(),
        .cpu_dmem_master_arqos(),
        .cpu_dmem_master_arregion(),
        .cpu_dmem_master_rready (m_axi_data_RREADY),

        .core_external_interrupt_sources_0_m_interrupt_req_set_not_clear(irq_i[0]),
        .core_external_interrupt_sources_1_m_interrupt_req_set_not_clear(irq_i[1]),
        .core_external_interrupt_sources_2_m_interrupt_req_set_not_clear(irq_i[2]),
        .core_external_interrupt_sources_3_m_interrupt_req_set_not_clear(irq_i[3]),
        .core_external_interrupt_sources_4_m_interrupt_req_set_not_clear(irq_i[4]),
        .core_external_interrupt_sources_5_m_interrupt_req_set_not_clear(irq_i[5]),
        .core_external_interrupt_sources_6_m_interrupt_req_set_not_clear(irq_i[6]),
        .core_external_interrupt_sources_7_m_interrupt_req_set_not_clear(irq_i[7]),
        .core_external_interrupt_sources_8_m_interrupt_req_set_not_clear(irq_i[8]),
        .core_external_interrupt_sources_9_m_interrupt_req_set_not_clear(irq_i[9]),
        .core_external_interrupt_sources_10_m_interrupt_req_set_not_clear(irq_i[10]),
        .core_external_interrupt_sources_11_m_interrupt_req_set_not_clear(irq_i[11]),
        .core_external_interrupt_sources_12_m_interrupt_req_set_not_clear(irq_i[12]),
        .core_external_interrupt_sources_13_m_interrupt_req_set_not_clear(irq_i[13]),
        .core_external_interrupt_sources_14_m_interrupt_req_set_not_clear(irq_i[14]),
        .core_external_interrupt_sources_15_m_interrupt_req_set_not_clear(irq_i[15]),

        `ifdef RT_LIFE
            .rt_life_pc_o,
            .rt_life_valid_o,
            .rt_life_inst_o,
            .rt_life_next_pc_o,
            .rt_life_stall_i,
        `endif

        .nmi_req_set_not_clear(0)

    );

    `ifdef SIM_DUMP_VCD
    initial begin
        $dumpfile ("dump.vcd");
        $dumpvars (0, testbench);
        #1;
    end
    `endif

endmodule
