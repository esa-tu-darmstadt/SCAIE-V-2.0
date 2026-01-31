
  # Create instance: cva6_0, and set properties
  set cva6_0 [ create_bd_cell -type ip -vlnv [dict get $cpu_vlnv $project_name] cva6_0 ]
  set cpu_clk [get_bd_pins cva6_0/clk]

  # Create interface connections
  set cva6_mem_splitter [ create_bd_cell -type ip -vlnv xilinx.com:ip:smartconnect:1.0 cva6_mem_splitter ]
  set_property CONFIG.NUM_MI 2 [get_bd_cells /cva6_mem_splitter]
  set_property CONFIG.NUM_SI 1 [get_bd_cells /cva6_mem_splitter]
  set_property CONFIG.ADVANCED_PROPERTIES {} [get_bd_cells /cva6_mem_splitter]
  set_bus_buffer_fifos [get_bd_cells /cva6_mem_splitter] S00_Buffer 4 4 4 4 4
  set_bus_buffer_fifos [get_bd_cells /cva6_mem_splitter] M00_Buffer 4 4 4 4 4
  set_bus_buffer_fifos [get_bd_cells /cva6_mem_splitter] M01_Buffer 4 4 4 4 4

  # Axi masters
  connect_bd_intf_net [get_bd_intf_pins cva6_0/m_axi] -boundary_type upper [get_bd_intf_pins cva6_mem_splitter/S00_AXI]
  #connect_bd_intf_net [get_bd_intf_pins cva6_dm_0/axi_dm_master] -boundary_type upper [get_bd_intf_pins cva6_mem_splitter/S01_AXI]
  set data_width [get_property CONFIG.DATA_WIDTH [get_bd_intf_pins cva6_0/m_axi]]
  set addr_width [get_property CONFIG.ADDR_WIDTH [get_bd_intf_pins cva6_0/m_axi]]

  # Axi slaves
  set axi_io_port [get_bd_intf_pins cva6_mem_splitter/M00_AXI]
  set axi_mem_port [get_bd_intf_pins cva6_mem_splitter/M01_AXI]
  connect_bd_intf_net -boundary_type upper [get_bd_intf_pins cva6_mem_splitter/M01_AXI] [get_bd_intf_pins axi_mem_intercon_1/S00_AXI]
  # Connect clocks
  connect_bd_net [get_bd_ports CLK] [get_bd_pins cva6_mem_splitter/ACLK] [get_bd_pins cva6_mem_splitter/S00_ACLK] [get_bd_pins cva6_mem_splitter/M00_ACLK] [get_bd_pins cva6_mem_splitter/M01_ACLK]
  connect_bd_net [get_bd_pins rst_CLK_100M/interconnect_aresetn] [get_bd_pins cva6_mem_splitter/ARESETN]
  #connect_bd_net [get_bd_pins rst_CLK_100M/peripheral_aresetn] [get_bd_pins cva6_mem_splitter/S00_ARESETN] [get_bd_pins cva6_mem_splitter/M00_ARESETN] [get_bd_pins cva6_mem_splitter/M01_ARESETN]

  # imem connection is done via the iaxi variable
  set iaxi [get_bd_intf_pins cva6_mem_splitter/M02_AXI]

  # Create port connections
  connect_bd_net [get_bd_pins RVController_0/rv_reset] [get_bd_pins cva6_0/rst]

proc create_specific_addr_segs {} {
  variable lmem

  # Create specific address segments
  create_bd_addr_seg -range 0x00010000 -offset 0x11000000 [get_bd_addr_spaces cva6_0/m_axi] [get_bd_addr_segs RVController_0/saxi/reg0] SEG_RVController_0_reg0
  if { $lmem > 0 } {
    create_bd_addr_seg -range $lmem -offset $lmem [get_bd_addr_spaces cva6_0/m_axi] [get_bd_addr_segs rv_dmem_ctrl/S_AXI/Mem0] SEG_rv_dmem_ctrl_Mem0
    create_bd_addr_seg -range $lmem -offset 0x00000000 [get_bd_addr_spaces cva6_0/m_axi] [get_bd_addr_segs rv_imem_ctrl/S_AXI/Mem0] SEG_rv_imem_ctrl_Mem0
  }
}

proc get_external_mem_addr_space {} {
  return [get_bd_addr_spaces cva6_0/m_axi]
}
