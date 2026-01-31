module cva6_top (
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
    input  wire         m_axi_RLAST
);

cva6_topsv  cva6_inst (
    .clk  (clk),
    .rst  (rst),
    
    .m_axi_AWVALID(m_axi_AWVALID),
    .m_axi_AWREADY(m_axi_AWREADY),
    .m_axi_AWID(m_axi_AWID),
    .m_axi_AWADDR(m_axi_AWADDR),
    .m_axi_AWSIZE(m_axi_AWSIZE),
    .m_axi_AWLEN(m_axi_AWLEN),
    .m_axi_AWBURST(m_axi_AWBURST),
    .m_axi_WVALID(m_axi_WVALID),
    .m_axi_WREADY(m_axi_WREADY), 
    .m_axi_WDATA(m_axi_WDATA),
    .m_axi_WSTRB(m_axi_WSTRB),
    .m_axi_WLAST(m_axi_WLAST),
    .m_axi_BVALID(m_axi_BVALID), 
    .m_axi_BREADY(m_axi_BREADY),
    .m_axi_BID(m_axi_BID), 
    .m_axi_BRESP(m_axi_BRESP),
     
    .m_axi_ARVALID(m_axi_ARVALID),
    .m_axi_ARREADY(m_axi_ARREADY),
    .m_axi_ARID(m_axi_ARID),
    .m_axi_ARADDR(m_axi_ARADDR),
    .m_axi_ARSIZE(m_axi_ARSIZE),
    .m_axi_ARLEN(m_axi_ARLEN),
    .m_axi_ARBURST(m_axi_ARBURST),
     
    .m_axi_RVALID(m_axi_RVALID), 
    .m_axi_RREADY(m_axi_RREADY),
    .m_axi_RID(m_axi_RID), 
    .m_axi_RDATA(m_axi_RDATA), 
    .m_axi_RRESP(m_axi_RRESP), 
    .m_axi_RLAST(m_axi_RLAST)
    );

endmodule


