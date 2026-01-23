// Copyright 2017-2019 ETH Zurich and University of Bologna.
// Copyright and related rights are licensed under the Solderpad Hardware
// License, Version 0.51 (the "License"); you may not use this file except in
// compliance with the License.  You may obtain a copy of the License at
// http://solderpad.org/licenses/SHL-0.51. Unless required by applicable law
// or agreed to in writing, software, hardware and materials distributed under
// this License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
// CONDITIONS OF ANY KIND, either express or implied. See the License for the
// specific language governing permissions and limitations under the License.
//
// Author: Florian Zaruba, ETH Zurich
// Date: 19.03.2017
// Description: CVA6 Top-level module

`include "rvfi_types.svh"
`include "cvxif_types.svh"

module cva6_ariane_wrapper import ariane_pkg::*; #(
    // CVA6 config
    parameter config_pkg::cva6_cfg_t CVA6Cfg = build_config_pkg::build_config(
        cva6_config_pkg::cva6_cfg
    )
) (
    // Subsystem Clock - SUBSYSTEM
    input logic clk_i,
    // Asynchronous reset active low - SUBSYSTEM
    input logic rst_ni,
    // Reset boot address - SUBSYSTEM
    input logic [CVA6Cfg.VLEN-1:0] boot_addr_i,
    // Hard ID reflected as CSR - SUBSYSTEM
    input logic [CVA6Cfg.XLEN-1:0] hart_id_i,
    // Level sensitive (async) interrupts - SUBSYSTEM
    input logic [1:0] irq_i,
    // Inter-processor (async) interrupt - SUBSYSTEM
    input logic ipi_i,
    // Timer (async) interrupt - SUBSYSTEM
    input logic time_irq_i,
    // Debug (async) request - SUBSYSTEM
    input logic debug_req_i,


    output wire         m_axi_ctrl_AWVALID,
    input  wire         m_axi_ctrl_AWREADY,
    output wire [5:0]   m_axi_ctrl_AWID,
    output wire [63:0]  m_axi_ctrl_AWADDR,
    output wire [2:0]   m_axi_ctrl_AWSIZE,
    output wire [7:0]   m_axi_ctrl_AWLEN,
    output wire [1:0]   m_axi_ctrl_AWBURST,
    output wire         m_axi_ctrl_WVALID,
    input  wire         m_axi_ctrl_WREADY,
    output wire [63:0]  m_axi_ctrl_WDATA,
    output wire [7:0]   m_axi_ctrl_WSTRB,
    output wire         m_axi_ctrl_WLAST,
    input  wire         m_axi_ctrl_BVALID,
    output wire         m_axi_ctrl_BREADY,
    input  wire [5:0]   m_axi_ctrl_BID,
    input  wire [1:0]   m_axi_ctrl_BRESP,

    output wire         m_axi_ctrl_ARVALID,
    input  wire         m_axi_ctrl_ARREADY,
    output wire [5:0]   m_axi_ctrl_ARID,
    output wire [63:0]  m_axi_ctrl_ARADDR,
    output wire [2:0]   m_axi_ctrl_ARSIZE,
    output wire [7:0]   m_axi_ctrl_ARLEN,
    output wire [1:0]   m_axi_ctrl_ARBURST,

    input  wire         m_axi_ctrl_RVALID,
    output wire         m_axi_ctrl_RREADY,
    input  wire [5:0]   m_axi_ctrl_RID,
    input  wire [63:0]  m_axi_ctrl_RDATA,
    input  wire [1:0]   m_axi_ctrl_RRESP,
    input  wire         m_axi_ctrl_RLAST

`ifdef CVA6_ENABLE_RVFI
    ,output wire [1-1:0]              rvfi_o_valid_A,
    output wire [1*64-1:0]           rvfi_o_order_A,
    output wire [1*CVA6Cfg.XLEN-1:0] rvfi_o_pc_rdata_A,
    output wire [1*5-1:0]            rvfi_o_rd_addr_A,
    output wire [1*CVA6Cfg.XLEN-1:0] rvfi_o_rd_wdata_A,

    output wire [1-1:0]              rvfi_o_valid_B,
    output wire [1*64-1:0]           rvfi_o_order_B,
    output wire [1*CVA6Cfg.XLEN-1:0] rvfi_o_pc_rdata_B,
    output wire [1*5-1:0]            rvfi_o_rd_addr_B,
    output wire [1*CVA6Cfg.XLEN-1:0] rvfi_o_rd_wdata_B
`endif
    //SCAIEV MAKETOP WRAPPERIO
);

// AXI types
localparam type axi_ar_chan_t = struct packed {
  logic [CVA6Cfg.AxiIdWidth-1:0]   id;
  logic [CVA6Cfg.AxiAddrWidth-1:0] addr;
  axi_pkg::len_t                   len;
  axi_pkg::size_t                  size;
  axi_pkg::burst_t                 burst;
  logic                            lock;
  axi_pkg::cache_t                 cache;
  axi_pkg::prot_t                  prot;
  axi_pkg::qos_t                   qos;
  axi_pkg::region_t                region;
  logic [CVA6Cfg.AxiUserWidth-1:0] user;
};
localparam type axi_aw_chan_t = struct packed {
  logic [CVA6Cfg.AxiIdWidth-1:0]   id;
  logic [CVA6Cfg.AxiAddrWidth-1:0] addr;
  axi_pkg::len_t                   len;
  axi_pkg::size_t                  size;
  axi_pkg::burst_t                 burst;
  logic                            lock;
  axi_pkg::cache_t                 cache;
  axi_pkg::prot_t                  prot;
  axi_pkg::qos_t                   qos;
  axi_pkg::region_t                region;
  axi_pkg::atop_t                  atop;
  logic [CVA6Cfg.AxiUserWidth-1:0] user;
};
localparam type axi_w_chan_t = struct packed {
  logic [CVA6Cfg.AxiDataWidth-1:0]     data;
  logic [(CVA6Cfg.AxiDataWidth/8)-1:0] strb;
  logic                                last;
  logic [CVA6Cfg.AxiUserWidth-1:0]     user;
};
localparam type b_chan_t = struct packed {
  logic [CVA6Cfg.AxiIdWidth-1:0]   id;
  axi_pkg::resp_t                  resp;
  logic [CVA6Cfg.AxiUserWidth-1:0] user;
};
localparam type r_chan_t = struct packed {
  logic [CVA6Cfg.AxiIdWidth-1:0]   id;
  logic [CVA6Cfg.AxiDataWidth-1:0] data;
  axi_pkg::resp_t                  resp;
  logic                            last;
  logic [CVA6Cfg.AxiUserWidth-1:0] user;
};
localparam type noc_req_t = struct packed {
  axi_aw_chan_t aw;
  logic         aw_valid;
  axi_w_chan_t  w;
  logic         w_valid;
  logic         b_ready;
  axi_ar_chan_t ar;
  logic         ar_valid;
  logic         r_ready;
};
localparam type noc_resp_t = struct packed {
  logic    aw_ready;
  logic    ar_ready;
  logic    w_ready;
  logic    b_valid;
  b_chan_t b;
  logic    r_valid;
  r_chan_t r;
};

// RVFI
localparam type rvfi_instr_t = `RVFI_INSTR_T(CVA6Cfg);
localparam type rvfi_csr_elmt_t = `RVFI_CSR_ELMT_T(CVA6Cfg);
localparam type rvfi_csr_t = `RVFI_CSR_T(CVA6Cfg, rvfi_csr_elmt_t);
localparam type rvfi_to_iti_t = `RVFI_TO_ITI_T(CVA6Cfg);

// RVFI PROBES
localparam type rvfi_probes_instr_t = `RVFI_PROBES_INSTR_T(CVA6Cfg);
localparam type rvfi_probes_csr_t = `RVFI_PROBES_CSR_T(CVA6Cfg);
localparam type rvfi_probes_t = struct packed {
  rvfi_probes_csr_t csr;
  rvfi_probes_instr_t instr;
};

//
localparam type readregflags_t = `READREGFLAGS_T(CVA6Cfg);
localparam type writeregflags_t = `WRITEREGFLAGS_T(CVA6Cfg);
localparam type id_t = `ID_T(CVA6Cfg);
localparam type hartid_t = `HARTID_T(CVA6Cfg);
localparam type x_compressed_req_t = `X_COMPRESSED_REQ_T(CVA6Cfg, hartid_t);
localparam type x_compressed_resp_t = `X_COMPRESSED_RESP_T(CVA6Cfg);
localparam type x_issue_req_t = `X_ISSUE_REQ_T(CVA6Cfg, hartid_t, id_t);
localparam type x_issue_resp_t = `X_ISSUE_RESP_T(CVA6Cfg, writeregflags_t, readregflags_t);
localparam type x_register_t = `X_REGISTER_T(CVA6Cfg, hartid_t, id_t, readregflags_t);
localparam type x_commit_t = `X_COMMIT_T(CVA6Cfg, hartid_t, id_t);
localparam type x_result_t = `X_RESULT_T(CVA6Cfg, hartid_t, id_t, writeregflags_t);
localparam type cvxif_req_t =
`CVXIF_REQ_T(CVA6Cfg, x_compressed_req_t, x_issue_req_t, x_register_req_t, x_commit_t);
localparam type cvxif_resp_t =
`CVXIF_RESP_T(CVA6Cfg, x_compressed_resp_t, x_issue_resp_t, x_result_t);


noc_resp_t noc_resp;
noc_req_t noc_req;
rvfi_probes_t rvfi_probes;
rvfi_instr_t [CVA6Cfg.NrCommitPorts-1:0]  rvfi_instr;
rvfi_csr_t rvfi_csr;
cvxif_req_t cvxif_req;
cvxif_resp_t cvxif_resp;

assign cvxif_resp.compressed_ready = 1'b1;
assign cvxif_resp.compressed_resp.accept = 1'b0;
assign cvxif_resp.compressed_resp.instr = 32'd0;
assign cvxif_resp.issue_ready = 1'b1;
assign cvxif_resp.issue_resp.accept = 1'b0;
assign cvxif_resp.issue_resp.writeback = '0; //Bit for X_DUALWRITE and write
assign cvxif_resp.issue_resp.register_read = '0; //Bits for X_NUM_RS X_DUALREAD
assign cvxif_resp.register_ready = 1'b1;
assign cvxif_resp.result_valid = 1'b0;
assign cvxif_resp.result.hartid = '0;
assign cvxif_resp.result.id = '0;
assign cvxif_resp.result.data = '0;
assign cvxif_resp.result.rd = '0;
assign cvxif_resp.result.we = '0; //Bits for X_DUALWRITE and write

logic clk;
logic rst;
logic rst_i;

//SCAIEV MAKETOP COREWIRES

assign clk = clk_i;
assign rst = ~rst_ni;
assign rst_i = ~rst_ni;

assign noc_resp.aw_ready = m_axi_ctrl_AWREADY;
assign noc_resp.ar_ready = m_axi_ctrl_ARREADY;
assign noc_resp.w_ready = m_axi_ctrl_WREADY;
assign noc_resp.b_valid = m_axi_ctrl_BVALID;
assign noc_resp.b.id = m_axi_ctrl_BID[3:0];
assign noc_resp.b.resp = m_axi_ctrl_BRESP;
assign noc_resp.b.user = 0;
assign noc_resp.r_valid = m_axi_ctrl_RVALID;
assign noc_resp.r.id = m_axi_ctrl_RID[3:0];
assign noc_resp.r.data = m_axi_ctrl_RDATA;
assign noc_resp.r.resp = m_axi_ctrl_RRESP;
assign noc_resp.r.last = m_axi_ctrl_RLAST;

assign m_axi_ctrl_AWVALID =  noc_req.aw_valid;
assign m_axi_ctrl_AWID = {2'b00, noc_req.aw.id};
assign m_axi_ctrl_AWADDR =  noc_req.aw.addr;
assign m_axi_ctrl_AWSIZE =  noc_req.aw.size;
assign m_axi_ctrl_AWLEN =  noc_req.aw.len;
assign m_axi_ctrl_AWBURST =  noc_req.aw.burst;
assign m_axi_ctrl_WVALID =  noc_req.w_valid;
assign m_axi_ctrl_WDATA =  noc_req.w.data;
assign m_axi_ctrl_WSTRB =  noc_req.w.strb;
assign m_axi_ctrl_WLAST =  noc_req.w.last;
assign m_axi_ctrl_BREADY =  noc_req.b_ready;
assign m_axi_ctrl_ARVALID =  noc_req.ar_valid;
assign m_axi_ctrl_ARID = {2'b00, noc_req.ar.id};
assign m_axi_ctrl_ARADDR =  noc_req.ar.addr;
assign m_axi_ctrl_ARSIZE =  noc_req.ar.size;
assign m_axi_ctrl_ARLEN =  noc_req.ar.len;
assign m_axi_ctrl_ARBURST =  noc_req.ar.burst;
assign m_axi_ctrl_RREADY =  noc_req.r_ready;

  cva6_glue_wrapper #(.CVA6Cfg ( CVA6Cfg )) glue_wrapper(
        .clk_i,
        .rst_ni,
        .boot_addr_i,
        .hart_id_i,
        .irq_i,
        .ipi_i,
        .time_irq_i,
        .debug_req_i,
        .noc_resp_i(noc_resp),
        .noc_req_o(noc_req),
        .rvfi_probes_o(rvfi_probes),
        .cvxif_req_o(cvxif_req),
        .cvxif_resp_i(cvxif_resp)
        //SCAIEV MAKETOP COREPINS
  );

  //SCAIEV MAKETOP ISAXWIRES
    
  //SCAIEV MAKETOP SCAL
    
  //SCAIEV MAKETOP ISAXINST

`ifdef CVA6_ENABLE_RVFI
  cva6_rvfi #(
      .CVA6Cfg   (CVA6Cfg),
      .rvfi_instr_t(rvfi_instr_t),
      .rvfi_csr_t(rvfi_csr_t),
      .rvfi_probes_instr_t(rvfi_probes_instr_t),
      .rvfi_probes_csr_t(rvfi_probes_csr_t),
      .rvfi_probes_t(rvfi_probes_t),
      .rvfi_to_iti_t(rvfi_to_iti_t)
  ) i_cva6_rvfi (
      .clk_i     (clk_i),
      .rst_ni    (rst_ni),
      .rvfi_probes_i(rvfi_probes),
      .rvfi_instr_o(rvfi_instr),
      .rvfi_to_iti_o(),
      .rvfi_csr_o(rvfi_csr)
  );
  assign rvfi_o_valid_A = rvfi_instr[0].valid;
  assign rvfi_o_order_A = rvfi_instr[0].order;
  assign rvfi_o_pc_rdata_A = rvfi_instr[0].pc_rdata;
  assign rvfi_o_rd_addr_A = rvfi_instr[0].rd_addr;
  assign rvfi_o_rd_wdata_A = rvfi_instr[0].rd_wdata;
  assign rvfi_o_valid_B = rvfi_instr[1].valid;
  assign rvfi_o_order_B = rvfi_instr[1].order;
  assign rvfi_o_pc_rdata_B = rvfi_instr[1].pc_rdata;
  assign rvfi_o_rd_addr_B = rvfi_instr[1].rd_addr;
  assign rvfi_o_rd_wdata_B = rvfi_instr[1].rd_wdata;
`endif
endmodule
