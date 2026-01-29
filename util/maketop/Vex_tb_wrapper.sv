//Vex testbench wrapper
module vex_wrapper(
    input clk,
    input rst,
    input logic [31:0] irq_i,

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

    `ifdef RT_LIFE
        , output wire [32-1:0] rt_life_pc_o
        , output wire rt_life_valid_o
        , output wire [32-1:0] rt_life_inst_o
        , output wire [32-1:0] rt_life_next_pc_o
        , input  wire rt_life_stall_i
    `endif
);
    parameter IMEM_BASE_ADDR = 32'H80000000;
    parameter ADDR_IRQ = 32'h80100000;


    // DUT Signals
    logic   [31:0]    iBusAhb_HADDR;
    logic             iBusAhb_HWRITE;
    logic   [2:0]     iBusAhb_HSIZE;
    logic   [2:0]     iBusAhb_HBURST;
    logic   [3:0]     iBusAhb_HPROT;
    logic   [1:0]     iBusAhb_HTRANS;
    logic             iBusAhb_HMASTLOCK;
    logic   [31:0]    iBusAhb_HWDATA;
    logic   [31:0]    iBusAhb_HRDATA;
    logic   [31:0]    iBusAhb_HRDATA_r;
    logic             iBusAhb_HREADY;
    logic             iBusAhb_HRESP;
    logic             iBusAhb_HRESP_r;
    logic   [31:0]    dBusAhb_HADDR;
    logic             dBusAhb_HWRITE;
    logic   [2:0]     dBusAhb_HSIZE;
    logic   [2:0]     dBusAhb_HBURST;
    logic   [3:0]     dBusAhb_HPROT;
    logic   [1:0]     dBusAhb_HTRANS;
    logic             dBusAhb_HMASTLOCK;
    logic   [31:0]    dBusAhb_HWDATA;
    logic   [31:0]    dBusAhb_HRDATA;
    logic   [31:0]    dBusAhb_HRDATA_r;
    logic             dBusAhb_HREADY;
    logic             dBusAhb_HRESP;
    logic             dBusAhb_HRESP_r;

    // INSTANTIATE DUT
    top top_INST(
        .debug_bus_cmd_valid(0),
        .debug_bus_cmd_ready(),
        .debug_bus_cmd_payload_wr(0),
        .debug_bus_cmd_payload_address(),
        .debug_bus_cmd_payload_data(),
        .debug_bus_rsp_data(),
        .debug_resetOut(),
        .timerInterrupt(irq_i[7]),
        .externalInterrupt(irq_i[11]),
        .softwareInterrupt(irq_i[3]),
        .debugReset(rst),
        .iBusAhbLite3_HADDR                              (iBusAhb_HADDR),
        .iBusAhbLite3_HWRITE                             (iBusAhb_HWRITE),
        .iBusAhbLite3_HSIZE                              (iBusAhb_HSIZE),
        .iBusAhbLite3_HBURST                             (iBusAhb_HBURST),
        .iBusAhbLite3_HPROT                              (iBusAhb_HPROT),
        .iBusAhbLite3_HTRANS                             (iBusAhb_HTRANS),
        .iBusAhbLite3_HMASTLOCK                          (iBusAhb_HMASTLOCK),
        .iBusAhbLite3_HWDATA                             (iBusAhb_HWDATA),
        .iBusAhbLite3_HRDATA                             (iBusAhb_HRDATA),
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

        `ifdef RT_LIFE
            .rt_life_pc_o,
            .rt_life_valid_o,
            .rt_life_inst_o,
            .rt_life_next_pc_o,
            .rt_life_stall_i,
        `endif

        .clk(clk),
        .rst(rst)
    );


    localparam logic [1:0] HTRANS_IDLE = 2'b00;
    localparam logic [1:0] HTRANS_BUSY = 2'b01;
    localparam logic [1:0] HTRANS_NONSEQ = 2'b10;
    localparam logic [1:0] HTRANS_SEQ = 2'b11;

    logic ibus_phase; //0: Address only, 1: Data, maybe address (depending on HTRANS)
    logic ibus_write;
    logic ibus_written;
    logic ibus_response_received;
    logic dbus_phase;
    logic dbus_write;
    logic dbus_written;
    logic dbus_response_received;
    logic [3-1:0] dbus_size;
    logic [4-1:0] dbus_wstrb;
    logic [4-1:0] dbus_wstrb_next;

    logic iBusAhb_HRESP_next;
    logic dBusAhb_HRESP_next;

    logic ibus_started_next_addr;
    logic dbus_started_next_addr;

    always_ff @(posedge clk) begin
        if (rst) begin
            ibus_phase <= 0;
            ibus_write <= 0;
            ibus_written <= 0;
            dbus_phase <= 0;
            dbus_write <= 0;
            dbus_written <= 0;
            dbus_wstrb <= '0;
            dbus_size <= '0;
            ibus_started_next_addr <= 0;
            dbus_started_next_addr <= 0;
        end
        else begin
            dbus_wstrb <= dbus_wstrb_next;
            if (m_axi_instr_WVALID && m_axi_instr_WREADY) begin
                ibus_written <= 1;
            end
            if (ibus_phase) begin
                if (m_axi_instr_RVALID) begin
                    iBusAhb_HRDATA_r <= m_axi_instr_RDATA;
                    iBusAhb_HRESP_r <= m_axi_instr_RRESP[1];
                end
                if (m_axi_instr_RVALID || m_axi_instr_BVALID) begin
                    ibus_response_received <= 1;
                    iBusAhb_HRESP_r <= iBusAhb_HRESP_next;
                end
            end
            if (iBusAhb_HREADY) begin
                ibus_started_next_addr <= 0;
                ibus_written <= 0;
                ibus_phase <= (iBusAhb_HTRANS == HTRANS_NONSEQ);
                if (iBusAhb_HTRANS == HTRANS_NONSEQ) begin
`ifndef SYNTHESIS
                    if (iBusAhb_HSIZE != 3'd2) begin
                        $display("ERROR: Unsupported iBusAhb size (only 32bit supported) (%m)");
                        $stop;
                    end
`endif
                    ibus_write <= iBusAhb_HWRITE;
                    ibus_response_received <= 0;
                end
            end
            else begin
                if (m_axi_instr_ARVALID && m_axi_instr_ARREADY) ibus_started_next_addr <= 1;
                if (m_axi_instr_AWVALID && m_axi_instr_AWREADY) ibus_started_next_addr <= 1;
            end

            if (m_axi_data_WVALID && m_axi_data_WREADY) begin
                dbus_written <= 1;
            end
            if (dbus_phase) begin
                if (m_axi_data_RVALID) begin
                    dBusAhb_HRDATA_r <= m_axi_data_RDATA;
                end
                if (m_axi_data_RVALID || m_axi_data_BVALID) begin
                    dbus_response_received <= 1;
                    dBusAhb_HRESP_r <= dBusAhb_HRESP_next;
                end
            end
            if (dBusAhb_HREADY) begin
                dbus_started_next_addr <= 0;
                dbus_written <= 0;
                dbus_phase <= (dBusAhb_HTRANS == HTRANS_NONSEQ);
                dbus_size <= dBusAhb_HSIZE;
                if (dBusAhb_HTRANS == HTRANS_NONSEQ) begin
                    dbus_write <= dBusAhb_HWRITE;
                    dbus_response_received <= 0;
                end
            end
            else begin
                if (m_axi_data_ARVALID && m_axi_data_ARREADY) dbus_started_next_addr <= 1;
                if (m_axi_data_AWVALID && m_axi_data_AWREADY) dbus_started_next_addr <= 1;
            end
        end
    end
    always_comb begin
        dbus_wstrb_next = dbus_wstrb;
        if (m_axi_data_WVALID && m_axi_data_WREADY) begin
            //Rotate WSTRB for 1-/2-byte transfers (note: not actually needed, we don't do bursts)
            case (dbus_size)
                3'b000: dbus_wstrb_next = {dbus_wstrb_next[2:0], dbus_wstrb_next[3]};
                3'b001: dbus_wstrb_next = {dbus_wstrb_next[1:0], dbus_wstrb_next[3:2]};
                default: begin end
            endcase
        end
        if (dBusAhb_HREADY) begin
            //Assign WSTRB based on transfer size and alignment.
            case (dBusAhb_HSIZE)
                3'b000: dbus_wstrb_next = 4'b0001 << {2'b00,dBusAhb_HADDR[1:0]};
                3'b001: dbus_wstrb_next = 4'b0011 << {2'b00,dBusAhb_HADDR[1],1'b0}; //{{2{dBusAhb_HADDR[1]}},{2{~dBusAhb_HADDR[1]}}}
                default: dbus_wstrb_next = 4'b1111;
            endcase
        end
    end
    wire axi_instr_rresp_isok = m_axi_instr_RRESP[1]; //Icarus doesn't support constant selects in always blocks (?)
    wire axi_data_rresp_isok = m_axi_data_RRESP[1];
    wire axi_instr_bresp_isok = m_axi_instr_BRESP[1];
    wire axi_data_bresp_isok = m_axi_data_BRESP[1];
    always_comb begin
        iBusAhb_HRESP_next = m_axi_instr_RVALID ? axi_instr_rresp_isok : axi_instr_bresp_isok;
        dBusAhb_HRESP_next = m_axi_data_RVALID ? axi_data_rresp_isok : axi_data_bresp_isok;
    end

    assign iBusAhb_HREADY = m_axi_instr_ARREADY
        && (!ibus_phase || (ibus_write ? m_axi_instr_BVALID : (m_axi_instr_RVALID || ibus_response_received)));
    assign iBusAhb_HRDATA = ibus_response_received ? iBusAhb_HRDATA_r : m_axi_instr_RDATA;
    assign iBusAhb_HRESP = ibus_response_received ? iBusAhb_HRESP_r : iBusAhb_HRESP_next;
    assign m_axi_instr_ARADDR = iBusAhb_HADDR;
    assign m_axi_instr_AWADDR = iBusAhb_HADDR;
    assign m_axi_instr_ARSIZE = 3'd2; //4 bytes
    assign m_axi_instr_AWSIZE = 3'd2; //4 bytes
    assign m_axi_instr_AWVALID = !ibus_started_next_addr && iBusAhb_HWRITE && (iBusAhb_HTRANS == HTRANS_NONSEQ);
    assign m_axi_instr_ARVALID = !ibus_started_next_addr && !iBusAhb_HWRITE && (iBusAhb_HTRANS == HTRANS_NONSEQ);

    assign m_axi_instr_WVALID = ibus_write && ibus_phase && !ibus_written;
    assign m_axi_instr_WDATA = iBusAhb_HWDATA;
    assign m_axi_instr_WSTRB = 4'b1111;

    assign m_axi_instr_BREADY = ibus_phase && ibus_write;
    assign m_axi_instr_RREADY = ibus_phase && !ibus_write;


    assign dBusAhb_HREADY = m_axi_data_ARREADY
        && (!dbus_phase || (dbus_write ? m_axi_data_BVALID : (m_axi_data_RVALID || dbus_response_received)));
    assign dBusAhb_HRDATA = dbus_response_received ? dBusAhb_HRDATA_r : m_axi_data_RDATA;
    assign dBusAhb_HRESP = dbus_response_received ? dBusAhb_HRESP_r : dBusAhb_HRESP_next;
    assign m_axi_data_ARADDR = dBusAhb_HADDR;
    assign m_axi_data_AWADDR = dBusAhb_HADDR;
    assign m_axi_data_ARSIZE = dBusAhb_HSIZE;
    assign m_axi_data_AWSIZE = dBusAhb_HSIZE;
    assign m_axi_data_AWVALID = !dbus_started_next_addr && dBusAhb_HWRITE && (dBusAhb_HTRANS == HTRANS_NONSEQ);
    assign m_axi_data_ARVALID = !dbus_started_next_addr && !dBusAhb_HWRITE && (dBusAhb_HTRANS == HTRANS_NONSEQ);

    assign m_axi_data_WVALID = dbus_write && dbus_phase && !dbus_written;
    assign m_axi_data_WDATA = dBusAhb_HWDATA;
    assign m_axi_data_WSTRB = dbus_wstrb;

    assign m_axi_data_BREADY = dbus_phase && dbus_write;
    assign m_axi_data_RREADY = dbus_phase && !dbus_write;

    `ifdef SIM_DUMP_VCD
    initial begin
        $dumpfile ("dump.vcd");
        $dumpvars (0, testbench);
        #1;
    end
    `endif

    `ifndef __ICARUS__
    //Icarus doesn't support these assertions
    assert property (@(posedge clk) disable iff (rst) (
        (dBusAhb_HTRANS == HTRANS_IDLE || dBusAhb_HBURST == 3'b000)
        && (iBusAhb_HTRANS == HTRANS_IDLE || iBusAhb_HBURST == 3'b000)))
    else $error("Unsupported HBURST value");

    assert property (@(posedge clk) disable iff (rst) (
        (dBusAhb_HTRANS == HTRANS_IDLE || dBusAhb_HTRANS == HTRANS_NONSEQ)
        && (iBusAhb_HTRANS == HTRANS_IDLE || iBusAhb_HTRANS == HTRANS_NONSEQ)))
    else $error("Unsupported HTRANS value");

    assert property (@(posedge clk) disable iff (rst) (
        (dBusAhb_HTRANS == HTRANS_IDLE || !dBusAhb_HMASTLOCK)
        && (iBusAhb_HTRANS == HTRANS_IDLE || !iBusAhb_HMASTLOCK)))
    else $error("Unsupported HMASTLOCK");
    `endif

endmodule
