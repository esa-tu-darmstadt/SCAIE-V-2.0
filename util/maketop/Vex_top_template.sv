module top( 
	input         		clk,
    input         		rst,
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
	input				debugReset);

    //SCAIEV MAKETOP COREWIRES
    
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
        .clk                       (clk),
        .reset                     (rst),
        .debugReset                (debugReset)
        //SCAIEV MAKETOP COREPINS
    );

    //SCAIEV MAKETOP ISAXWIRES
    
    //SCAIEV MAKETOP SCAL
    
    //SCAIEV MAKETOP ISAXINST
    	
endmodule
