import os
import functools

import error
import run_cmd
from scaiev import CoreSupport, CoreExtensions
from cores.utils.helpers import read_file_lines

class OrcaSupport(CoreSupport):
    def copy_blacklist(self) -> list[str]:
        return [
            # ORCA
            "deps/scaie-v/CoresSrc/ORCA/software", # broken symlinks
        ]
    def has_isax_support(self) -> bool:
        return True
    def run_extra_build_steps(self, target_dir, kconf_syms) -> None:
        # Things are getting wild
        patch_file = os.path.abspath("patches/ORCA_src_patch.diff")
        run_cmd.run(".", f'patch -u -p0 -N --directory="{target_dir}" < {patch_file}', "Could not apply patch to ORCA", error.SCAIEV_BASE + 9, False)
        vhd_files = [s for s in read_file_lines(os.path.join(target_dir, "ip/orca/hdl/Filelist")) if not s.startswith("#")]
        output_path = os.path.join(target_dir, "ORCA.v")
        ip_path = os.path.join(target_dir, "ip/orca/hdl")
        ghdl_module = "ghdl" if not ("GHDL_YOSYS_MODULE" in os.environ) else os.environ["GHDL_YOSYS_MODULE"]
        run_cmd.run(ip_path, f'yosys -m "{ghdl_module}" -p "ghdl -gAUX_MEMORY_REGIONS=0 -gUC_MEMORY_REGIONS=1 -gINTERRUPT_VECTOR=X\\"80000000\\" -gENABLE_EXCEPTIONS=1 -gMULTIPLY_ENABLE=1 -gDIVIDE_ENABLE=1 -fsynopsys --std=08 {functools.reduce(lambda a, b: a + " " + b, vhd_files)} -e orca; write_verilog \\"{output_path}\\""', "Could not compile ORCA vhd files to verilog", error.SCAIEV_BASE + 10, False)

    def get_srcs_folder_name(self) -> str:
        return "ORCA"
    def get_maketop(self) -> str:
        return "ORCA_maketop.py"
    def get_extensions(self) -> CoreExtensions:
        return CoreExtensions(['I','M','Zicsr'], "ilp32", 32)

    # -> tb srcs, core srcs, tb top module, core top module, include dirs, defines, extra sim makefile args
    def get_core_srcs(self, scal_sources, core_dir) -> tuple[list[str], list[str], str, str, list[str], list[str], dict[str, str]]:
        return ["ORCA_tb_wrapper.sv"], ["ORCA.v", "ORCA_top.v"] + scal_sources, "testbench", "top", [], [], {}

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
            # The address of the exception handler on the instruction bus, for error detection (optional).
            "EXCEPTION_BASE=80000000",
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
        return self._get_linker_file("ORCA")
    def get_specific_startup_file(self) -> str:
        return self._get_specific_startup_file("ORCA")
    def get_longnail_datasheet_name(self) -> str:
        return "ORCA.yaml"

def get_supported_cores():
    return [
        ("CORE_ORCA", "ORCA", OrcaSupport()),
    ]
