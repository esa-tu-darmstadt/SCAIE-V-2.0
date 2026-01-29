module testbench(
    input wire clk,
    input wire rst,
    //AXI Control Bus
    output wire         m_axi_ctrl_AWVALID,
    input  wire         m_axi_ctrl_AWREADY, //
    output wire [5:0]   m_axi_ctrl_AWID,
    output wire [63:0]  m_axi_ctrl_AWADDR,
    output wire [2:0]   m_axi_ctrl_AWSIZE,
    output wire [7:0]   m_axi_ctrl_AWLEN,
    output wire [1:0]   m_axi_ctrl_AWBURST,
    output wire         m_axi_ctrl_WVALID,
    input  wire         m_axi_ctrl_WREADY, //
    output wire [63:0]  m_axi_ctrl_WDATA,
    output wire [7:0]   m_axi_ctrl_WSTRB,
    output wire         m_axi_ctrl_WLAST,
    input  wire         m_axi_ctrl_BVALID, //
    output wire         m_axi_ctrl_BREADY,
    input  wire [5:0]   m_axi_ctrl_BID, //
    input  wire [1:0]   m_axi_ctrl_BRESP, //

    output wire         m_axi_ctrl_ARVALID,
    input  wire         m_axi_ctrl_ARREADY,//
    output wire [5:0]   m_axi_ctrl_ARID,
    output wire [63:0]  m_axi_ctrl_ARADDR,
    output wire [2:0]   m_axi_ctrl_ARSIZE,
    output wire [7:0]   m_axi_ctrl_ARLEN,
    output wire [1:0]   m_axi_ctrl_ARBURST,

    input  wire         m_axi_ctrl_RVALID, //
    output wire         m_axi_ctrl_RREADY,
    input  wire [5:0]   m_axi_ctrl_RID, //
    input  wire [63:0]  m_axi_ctrl_RDATA, //
    input  wire [1:0]   m_axi_ctrl_RRESP, //
    input  wire         m_axi_ctrl_RLAST //

`ifdef CVA6_ENABLE_RVFI
    ,output wire [1-1:0]    rvfi_o_valid_A,
    output wire [1*32-1:0] rvfi_o_pc_rdata_A,
    output wire [1*5-1:0]  rvfi_o_rd_addr_A,
    output wire [1*32-1:0] rvfi_o_rd_wdata_A,

    output wire [1-1:0]    rvfi_o_valid_B,
    output wire [1*32-1:0] rvfi_o_pc_rdata_B,
    output wire [1*5-1:0]  rvfi_o_rd_addr_B,
    output wire [1*32-1:0] rvfi_o_rd_wdata_B
`endif
);

    cva6_ariane_wrapper  cva6_inst (
        .clk_i  (clk),
        .rst_ni  (~rst),
        .boot_addr_i(64'h8000_0000),
        .hart_id_i(0),
        .irq_i(0),
        .ipi_i(0),
        .time_irq_i(0),
        .debug_req_i(0),

        .m_axi_ctrl_AWVALID(m_axi_ctrl_AWVALID),
        .m_axi_ctrl_AWREADY(m_axi_ctrl_AWREADY),
        .m_axi_ctrl_AWID(m_axi_ctrl_AWID),
        .m_axi_ctrl_AWADDR(m_axi_ctrl_AWADDR),
        .m_axi_ctrl_AWSIZE(m_axi_ctrl_AWSIZE),
        .m_axi_ctrl_AWLEN(m_axi_ctrl_AWLEN),
        .m_axi_ctrl_AWBURST(m_axi_ctrl_AWBURST),
        .m_axi_ctrl_WVALID(m_axi_ctrl_WVALID),
        .m_axi_ctrl_WREADY(m_axi_ctrl_WREADY),
        .m_axi_ctrl_WDATA(m_axi_ctrl_WDATA),
        .m_axi_ctrl_WSTRB(m_axi_ctrl_WSTRB),
        .m_axi_ctrl_WLAST(m_axi_ctrl_WLAST),
        .m_axi_ctrl_BVALID(m_axi_ctrl_BVALID),
        .m_axi_ctrl_BREADY(m_axi_ctrl_BREADY),
        .m_axi_ctrl_BID(m_axi_ctrl_BID),
        .m_axi_ctrl_BRESP(m_axi_ctrl_BRESP),

        .m_axi_ctrl_ARVALID(m_axi_ctrl_ARVALID),
        .m_axi_ctrl_ARREADY(m_axi_ctrl_ARREADY),
        .m_axi_ctrl_ARID(m_axi_ctrl_ARID),
        .m_axi_ctrl_ARADDR(m_axi_ctrl_ARADDR),
        .m_axi_ctrl_ARSIZE(m_axi_ctrl_ARSIZE),
        .m_axi_ctrl_ARLEN(m_axi_ctrl_ARLEN),
        .m_axi_ctrl_ARBURST(m_axi_ctrl_ARBURST),

        .m_axi_ctrl_RVALID(m_axi_ctrl_RVALID),
        .m_axi_ctrl_RREADY(m_axi_ctrl_RREADY),
        .m_axi_ctrl_RID(m_axi_ctrl_RID),
        .m_axi_ctrl_RDATA(m_axi_ctrl_RDATA),
        .m_axi_ctrl_RRESP(m_axi_ctrl_RRESP),
        .m_axi_ctrl_RLAST(m_axi_ctrl_RLAST)

`ifdef CVA6_ENABLE_RVFI
        ,.rvfi_o_valid_A(rvfi_o_valid_A),
        .rvfi_o_pc_rdata_A(rvfi_o_pc_rdata_A),
        .rvfi_o_rd_addr_A(rvfi_o_rd_addr_A),
        .rvfi_o_rd_wdata_A(rvfi_o_rd_wdata_A),
        .rvfi_o_valid_B(rvfi_o_valid_B),
        .rvfi_o_pc_rdata_B(rvfi_o_pc_rdata_B),
        .rvfi_o_rd_addr_B(rvfi_o_rd_addr_B),
        .rvfi_o_rd_wdata_B(rvfi_o_rd_wdata_B)
`endif
    );

endmodule
