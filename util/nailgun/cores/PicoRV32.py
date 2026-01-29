import os

import error
import run_cmd
from scaiev import CoreSupport, CoreExtensions

class PicoRV32Support(CoreSupport):
    def copy_blacklist(self) -> list[str]:
        return [
            # PicoRV32
            "deps/scaie-v/CoresSrc/PicoRV32/dhrystone", # unnecessary
            "deps/scaie-v/CoresSrc/PicoRV32/firmware", # unnecessary
            "deps/scaie-v/CoresSrc/PicoRV32/picosoc", # unnecessary
            "deps/scaie-v/CoresSrc/PicoRV32/scripts", # unnecessary
            "deps/scaie-v/CoresSrc/PicoRV32/tests", # unnecessary
        ]
    def has_isax_support(self) -> bool:
        return True
    def run_extra_build_steps(self, target_dir, kconf_syms) -> None:
        # Patch in fence support for picorv32: https://github.com/YosysHQ/picorv32/pull/229
        # TODO remove once the coresrcs in scaie-v were updated
        patch_file = os.path.abspath("patches/picorv32.patch")
        run_cmd.run(target_dir, f"patch -p1 < {patch_file}", "Could not patch the PicoRV32 sources", error.SCAIEV_BASE + 6, False)

    def get_srcs_folder_name(self) -> str:
        return "PicoRV32"
    def get_maketop(self) -> str:
        return "PicoRV32_maketop.py"
    def get_extensions(self) -> CoreExtensions:
        return CoreExtensions(['I','Zicsr'], "ilp32", 32)

    # -> tb srcs, core srcs, tb top module, core top module, include dirs, defines, extra sim makefile args
    def get_core_srcs(self, scal_sources, core_dir) -> tuple[list[str], list[str], str, str, list[str], list[str], dict[str, str]]:
        return ["picorv32_tb_wrapper.sv"], ["picorv32.v", "picorv32_top.v"] + scal_sources, "testbench", "top", [], [], {}

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
            "IMEM_BASE=00000000",
            # The core has a 'trap' output pin that indicates prior exceptions (optional).
            "HAS_TRAP_PIN=1",
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
        return self._get_linker_file("PicoRV32")
    def get_specific_startup_file(self) -> str:
        return self._get_specific_startup_file("PicoRV32")
    def get_longnail_datasheet_name(self) -> str:
        return "PicoRV32.yaml"

def get_supported_cores():
    return [
        ("CORE_PICORV32", "PicoRV32", PicoRV32Support()),
    ]
