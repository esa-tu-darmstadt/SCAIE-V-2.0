import os

import error
import run_cmd
from scaiev import CoreSupport, CoreExtensions
from cores.utils.helpers import find_verilog_srcs

class PiccoloSupport(CoreSupport):
    def copy_blacklist(self) -> list[str]:
        return [
            # Piccolo
            "deps/scaie-v/CoresSrc/Piccolo/Tests", # unnecessary
        ]
    def has_isax_support(self) -> bool:
        return True
    def run_extra_build_steps(self, target_dir, kconf_syms) -> None:
        build_target_dir = os.path.join(target_dir, "builds/RV32ACIMU_Piccolo_verilator")
        run_cmd.run(build_target_dir, 'make clean', "Could not clean Piccolo build directory", error.SCAIEV_BASE + 7, False)
        run_cmd.run(build_target_dir, f'TOPFILE="{target_dir}/src_Core/Core/Core.bsv" TOPMODULE=mkCore BSC_COMPILATION_FLAGS=\'-verilog-filter "sed -i \\"/\\/\\/ synopsys translate_off/,/\\/\\/ synopsys translate_on/d\\""\' make compile', "Could not compile Piccolo bluespec sources to verilog", error.SCAIEV_BASE + 8, False)

    def get_srcs_folder_name(self) -> str:
        return "Piccolo"
    def get_maketop(self) -> str:
        return "Piccolo_maketop.py"
    def get_extensions(self) -> CoreExtensions:
        return CoreExtensions(['I','M','A','C','Zicsr'], "ilp32", 32)

    # -> tb srcs, core srcs, tb top module, core top module, include dirs, defines, extra sim makefile args
    def get_core_srcs(self, scal_sources, core_dir) -> tuple[list[str], list[str], str, str, list[str], list[str], dict[str, str]]:
        bsv_lib_sources = find_verilog_srcs(os.path.join(core_dir, "src_bsc_lib_RTL"), [
            # Blacklist:
            "main.v"
        ])
        core_srcs = find_verilog_srcs(os.path.join(core_dir, "builds/RV32ACIMU_Piccolo_verilator/Verilog_RTL"), [
            # Blacklist:
            "mkSoC_Top.v",
        ])
        extra_makefile_args = {
            "verilator": """
# Verilator throws lots of warnings on the BlueSpec-compiled core. Ignoring some of them.
EXTRA_ARGS+=-Wno-STMTDLY -Wno-UNSIGNED -Wno-CMPCONST -Wno-CASEINCOMPLETE
"""
        }
        return ["Piccolo_tb_wrapper.sv"], core_srcs + bsv_lib_sources + ["Piccolo_top.v"] + scal_sources, "testbench", "top", [], [], extra_makefile_args

    def get_tb_env_vars(self) -> list[str]:
        return [
            # Number of Bus slave interfaces the simulator should instantiate.
            "NUM_BUSSI=2",
            # Types and port names for each Bus SI.
            "BUSSI0_TYPE=AXI4",
            "BUSSI0_SIGNAME=m_axi_instr",
            "BUSSI1_TYPE=AXI4",
            "BUSSI1_SIGNAME=m_axi_data",
            # Bus SI index for IMEM.
            "IMEM_BUSIDX=0",
            # The base address of the instruction memory on the bus.
            "IMEM_BASE=00001000",
            # The address of the exception handler on the instruction bus, for error detection (optional).
            "EXCEPTION_BASE=00000000",
            # Bus SI index for DMEM.
            "DMEM_BUSIDX=1",
            # The base address of the data memory on the bus.
            "DMEM_BASE=00100000",
            # The physical size of the data memory.
            "DMEM_SIZE=00100000",
            # Bus SI index for CTRL.
            "CTRL_BUSIDX=1",
            # The base address of the MMIO control block on the bus (for completion IRQ).
            "CTRL_BASE=00200000",
        ]

    def get_linker_file(self) -> str:
        return self._get_linker_file("Piccolo")
    def get_specific_startup_file(self) -> str:
        return self._get_specific_startup_file("Piccolo")
    def get_longnail_datasheet_name(self) -> str:
        return "Piccolo.yaml"

def get_supported_cores():
    return [
        ("CORE_PICCOLO", "Piccolo", PiccoloSupport()),
    ]
