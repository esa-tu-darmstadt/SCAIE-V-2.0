import os

from scaiev import CoreSupport, CoreExtensions
from cores.utils.helpers import read_file_lines

class CVA5Support(CoreSupport):
    def copy_blacklist(self) -> list[str]:
        return [
            # CVA5
            "deps/scaie-v/CoresSrc/CVA5/debug_module", # unnecessary
            "deps/scaie-v/CoresSrc/CVA5/examples", # unnecessary
            "deps/scaie-v/CoresSrc/CVA5/formal", # unnecessary
            "deps/scaie-v/CoresSrc/CVA5/scripts", # unnecessary
            "deps/scaie-v/CoresSrc/CVA5/test_benches", # unnecessary
        ]
    def run_extra_build_steps(self, target_dir, kconf_syms) -> None:
        pass
    def get_extensions(self) -> CoreExtensions:
        return CoreExtensions(['I','M','Zicsr'], "ilp32", 32)

    def has_isax_support(self) -> bool:
        return True
    def get_srcs_folder_name(self) -> str:
        return "CVA5"
    def get_maketop(self) -> str:
        return "CVA5_maketop.py"

    # -> tb srcs, core srcs, tb top module, core top module, include dirs, defines, extra sim makefile args
    def get_core_srcs(self, scal_sources, core_dir) -> tuple[list[str], list[str], str, str, list[str], list[str], dict[str, str]]:
        compile_order = os.path.join(core_dir, "tools/compile_order")
        core_srcs = read_file_lines(compile_order)
        blacklist = [
            "l2_arbiter/l2_fifo.sv",
        ]
        core_srcs = [f for f in core_srcs if not f in blacklist]
        extra_makefile_args = {
            "questa": """
EXTRA_ARGS += -suppress 7061 # Ignore some undriven signals
EXTRA_ARGS += -suppress 3601 # Ignore iteration timeout
"""
        }
        return ["CVA5_tb_wrapper.v"], core_srcs + ["core/cva5_wrapper.sv", "CVA5_top.v"] + scal_sources, "testbench", "cva5_top", [], [], extra_makefile_args

    def get_tb_env_vars(self) -> list[str]:
        return [
            # Number of Bus slave interfaces the simulator should instantiate.
            "NUM_BUSSI=3",
            # Types and port names for each Bus SI.
            "BUSSI0_TYPE=BRAM",
            "BUSSI0_SIGNAME=m_bram_instr",
            "BUSSI1_TYPE=BRAM",
            "BUSSI1_SIGNAME=m_bram_data",
            "BUSSI2_TYPE=AXI4",
            "BUSSI2_SIGNAME=m_axi_ctrl",
            # Bus SI index for IMEM.
            "IMEM_BUSIDX=0",
            # The base address of the instruction memory on the bus.
            "IMEM_BASE=80000000",
            # The address of the exception handler on the instruction bus, for error detection (optional).
            "EXCEPTION_BASE=8F000000",
            # Bus SI index for DMEM.
            "DMEM_BUSIDX=1",
            # The base address of the data memory on the bus.
            "DMEM_BASE=80100000",
            # The physical size of the data memory.
            "DMEM_SIZE=00100000",
            # Bus SI index for CTRL.
            "CTRL_BUSIDX=2",
            # The base address of the MMIO control block on the bus (for completion IRQ).
            "CTRL_BASE=60000000",
        ]

    def get_linker_file(self) -> str:
        return self._get_linker_file("CVA5")
    def get_specific_startup_file(self) -> str:
        return self._get_specific_startup_file("CVA5")
    def get_longnail_datasheet_name(self) -> str:
        return "CVA5.yaml"

def get_supported_cores():
    return [
        ("CORE_CVA5", "CVA5", CVA5Support()),
    ]
