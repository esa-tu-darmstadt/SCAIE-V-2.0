import os

import error
import run_cmd
from scaiev import CoreSupport, CoreExtensions

class VexSupport(CoreSupport):
    def __init__(self, with_mul : bool, datasheet : str):
        self.with_mul = with_mul
        self.datasheet = datasheet

    def copy_blacklist(self) -> list[str]:
        return [
            # VexRiscv
            "deps/scaie-v/CoresSrc/VexRiscv/assets", # unnecessary
            "deps/scaie-v/CoresSrc/VexRiscv/doc", # unnecessary
            "deps/scaie-v/CoresSrc/VexRiscv/.github", # unnecessary
            "deps/scaie-v/CoresSrc/VexRiscv/scripts", # unnecessary
        ]
    def has_isax_support(self) -> bool:
        return True
    def run_extra_build_steps(self, target_dir, kconf_syms) -> None:
        spinal_gen_args = kconf_syms['SPINAL_GEN_ARGS'].str_value if 'SPINAL_GEN_ARGS' in kconf_syms else ""

        # Patch the build system of VexRiscv
        patch_file = os.path.abspath("patches/Vex5.patch")
        run_cmd.run(target_dir, f"patch -p1 < {patch_file}", "Could not patch the VexRiscv sources", error.SCAIEV_BASE + 4, False)
        # Build VexRiscv
        run_cmd.run(target_dir, f'sbt "runMain vexriscv.demo.VexRiscvAhbLite3 {spinal_gen_args}"', "Could not generate VexRiscv.v", error.SCAIEV_BASE + 5, False, 100)

    def get_srcs_folder_name(self) -> str:
        return "VexRiscv"
    def get_maketop(self) -> str:
        return "Vex_maketop.py"
    def get_extensions(self) -> CoreExtensions:
        return CoreExtensions(['I','M','Zicsr'] if self.with_mul else ['I','Zicsr'], "ilp32", 32)

    # -> tb srcs, core srcs, tb top module, core top module, include dirs, defines, extra sim makefile args
    def get_core_srcs(self, scal_sources, core_dir) -> tuple[list[str], list[str], str, str, list[str], list[str], dict[str, str]]:
        defines = [
            "FORMAL"
        ]

        return ["Vex_tb_wrapper.sv"], ["VexRiscv.v", "Vex_top.sv"] + scal_sources, "vex_wrapper", "top", [], defines, {}

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
            "IMEM_BASE=80000000",
            # The address of the exception handler on the instruction bus, for error detection (optional).
            "EXCEPTION_BASE=00000020",
            # Bus SI index for DMEM.
            "DMEM_BUSIDX=1",
            # The base address of the data memory on the bus.
            "DMEM_BASE=80100000",
            # The physical size of the data memory.
            "DMEM_SIZE=00100000",
            # Bus SI index for CTRL.
            "CTRL_BUSIDX=1",
            # The base address of the MMIO control block on the bus (for completion IRQ).
            "CTRL_BASE=80200000",
        ]

    def get_linker_file(self) -> str:
        return self._get_linker_file("VexRiscv")
    def get_specific_startup_file(self) -> str:
        return self._get_specific_startup_file("VexRiscv")
    def get_longnail_datasheet_name(self) -> str:
        return self.datasheet

def get_supported_cores():
    return [
        ("CORE_VEX_4S", "VexRiscv_4s", VexSupport(False, "VexRiscv_4s.yaml")),
        ("CORE_VEX_5S", "VexRiscv_5s", VexSupport(True, "VexRiscv_5s.yaml")),
    ]
