puts "package  OpenHW Group's CVA6"

set name cva6sv
if { $::argc >= 1 } {
	append name _[lindex $::argv 0]
}
set xlen 32
if { $::argc >= 2 } {
	set xlen [lindex $::argv 1]
}
set version 0.1
create_project -in_memory

add_files {"core/scaiev_config.sv" "core/include/config_pkg.sv"}
if {$xlen == 32} {
	add_files "core/include/cv32a6_imac_sv32_scaiev_config_pkg.sv"
} {
	add_files "core/include/cv64a6_imac_sv39_scaiev_config_pkg.sv"
}
add_files [exec cat ../core.files]
add_files "core/cvfpu/src/common_cells/include/common_cells/registers.svh"
if {[file exists "CommonLogicModule.sv"]} {
	add_files "CommonLogicModule.sv"
}

add_files {"cva6_ariane_wrapper.sv" "../cva6_fpga_wrapper.sv" "../cva6_fpga_wrapper2.v"}
set_property file_type {Verilog Header} [get_files *.svh]

set_property include_dirs {"core/include" "core/cvfpu/src/common_cells/include" "core/cache_subsystem/hpdcache/common/local/util" "core/cache_subsystem/hpdcache/vendor/pulp-platform/common_cells/include" "core/cache_subsystem/hpdcache/vendor/pulp-platform/axi/include" "core/cache_subsystem/hpdcache/core/include" "core/cache_subsystem/hpdcache/rtl/include"} [current_fileset]
set defines {FPGA_TARGET_XILINX=1}
if {$xlen == 64} { lappend defines {CVA6_64=1} }
set_property verilog_define $defines [current_fileset]

# optionally remove unneeded files

update_compile_order -fileset sources_1
set_property top cva6_top [current_fileset]
update_compile_order -fileset sources_1

ipx::package_project -root_dir [pwd] -import_files -force
set core [ipx::current_core]
set_property vendor user.org $core
set_property library cva6sv $core
set_property name $name $core
set_property display_name $name $core
set_property description $name $core
set_property version $version $core
set_property core_revision 1 $core

ipx::create_xgui_files $core
ipx::update_checksums $core
ipx::save_core $core
ipx::check_integrity $core

ipx::archive_core risc-v_$name.zip $core
ipx::unload_core component_1
