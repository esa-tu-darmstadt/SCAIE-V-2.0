module cva6_topsv (
    input wire clk,
    input wire rst,
    output wire         m_axi_AWVALID,
    input  wire         m_axi_AWREADY,
    output wire [5:0]   m_axi_AWID,
    output wire [63:0]  m_axi_AWADDR,
    output wire [2:0]   m_axi_AWSIZE,
    output wire [7:0]   m_axi_AWLEN,
    output wire [1:0]   m_axi_AWBURST,
    output wire         m_axi_WVALID,
    input  wire         m_axi_WREADY,
    output wire [63:0]  m_axi_WDATA,
    output wire [7:0]   m_axi_WSTRB,
    output wire         m_axi_WLAST,
    input  wire         m_axi_BVALID,
    output wire         m_axi_BREADY,
    input  wire [5:0]   m_axi_BID,
    input  wire [1:0]   m_axi_BRESP,

    output wire         m_axi_ARVALID,
    input  wire         m_axi_ARREADY,
    output wire [5:0]   m_axi_ARID,
    output wire [63:0]  m_axi_ARADDR,
    output wire [2:0]   m_axi_ARSIZE,
    output wire [7:0]   m_axi_ARLEN,
    output wire [1:0]   m_axi_ARBURST,

    input  wire         m_axi_RVALID,
    output wire         m_axi_RREADY,
    input  wire [5:0]   m_axi_RID,
    input  wire [63:0]  m_axi_RDATA,
    input  wire [1:0]   m_axi_RRESP,
    input  wire         m_axi_RLAST //
);

cva6_ariane_wrapper  cva6_inst (
    .clk_i(clk),
    .rst_ni(~rst),
    .boot_addr_i( `ifdef CVA6_64 64'h1_0000_0000_0000 `else 64'h8000_0000 `endif),
    .hart_id_i(0),
    .irq_i(0),
    .ipi_i(0),
    .time_irq_i(0),
    .debug_req_i(0),
    
    .m_axi_ctrl_AWVALID(m_axi_AWVALID),
    .m_axi_ctrl_AWREADY(m_axi_AWREADY),
    .m_axi_ctrl_AWID(m_axi_AWID),
    .m_axi_ctrl_AWADDR(m_axi_AWADDR),
    .m_axi_ctrl_AWSIZE(m_axi_AWSIZE),
    .m_axi_ctrl_AWLEN(m_axi_AWLEN),
    .m_axi_ctrl_AWBURST(m_axi_AWBURST),
    .m_axi_ctrl_WVALID(m_axi_WVALID),
    .m_axi_ctrl_WREADY(m_axi_WREADY),
    .m_axi_ctrl_WDATA(m_axi_WDATA),
    .m_axi_ctrl_WSTRB(m_axi_WSTRB),
    .m_axi_ctrl_WLAST(m_axi_WLAST),
    .m_axi_ctrl_BVALID(m_axi_BVALID),
    .m_axi_ctrl_BREADY(m_axi_BREADY),
    .m_axi_ctrl_BID(m_axi_BID),
    .m_axi_ctrl_BRESP(m_axi_BRESP),
    
    .m_axi_ctrl_ARVALID(m_axi_ARVALID),
    .m_axi_ctrl_ARREADY(m_axi_ARREADY),
    .m_axi_ctrl_ARID(m_axi_ARID),
    .m_axi_ctrl_ARADDR(m_axi_ARADDR),
    .m_axi_ctrl_ARSIZE(m_axi_ARSIZE),
    .m_axi_ctrl_ARLEN(m_axi_ARLEN),
    .m_axi_ctrl_ARBURST(m_axi_ARBURST),
    
    .m_axi_ctrl_RVALID(m_axi_RVALID),
    .m_axi_ctrl_RREADY(m_axi_RREADY),
    .m_axi_ctrl_RID(m_axi_RID),
    .m_axi_ctrl_RDATA(m_axi_RDATA),
    .m_axi_ctrl_RRESP(m_axi_RRESP),
    .m_axi_ctrl_RLAST(m_axi_RLAST)
    );

endmodule


