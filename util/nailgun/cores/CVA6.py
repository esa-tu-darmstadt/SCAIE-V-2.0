import os

from scaiev import CoreSupport, CoreExtensions
from cores.utils.helpers import read_file_lines

class CVA6Support_bcb0f7d(CoreSupport):
    def __init__(self, is_64_bit : bool, is_dual : bool):
        self.is_64_bit = is_64_bit
        self.is_dual = is_dual
        self.scaiev_core_name = f'CVA6{"_64" if self.is_64_bit else ""}_bcb0f7d{"_dual" if self.is_dual else ""}'
    def copy_blacklist(self) -> list[str]:
        return [
            # CVA6
            "deps/scaie-v/CoresSrc/cva6/ci", # unnecessary
            "deps/scaie-v/CoresSrc/cva6/config", # unnecessary
            "deps/scaie-v/CoresSrc/cva6/docs", # unnecessary
            "deps/scaie-v/CoresSrc/cva6/.git", # unnecessary
            "deps/scaie-v/CoresSrc/cva6/.github", # unnecessary
            "deps/scaie-v/CoresSrc/cva6/.gitlab-ci", # unnecessary
            "deps/scaie-v/CoresSrc/cva6/isaxes_test", # unnecessary
            "deps/scaie-v/CoresSrc/cva6/pd", # unnecessary
            "deps/scaie-v/CoresSrc/cva6/spyglass", # unnecessary
            "deps/scaie-v/CoresSrc/cva6/util", # unnecessary
            "deps/scaie-v/CoresSrc/cva6/verif", # unnecessary
        ]
    def run_extra_build_steps(self, target_dir, kconf_syms) -> None:
        pass
    def get_extensions(self) -> CoreExtensions:
        if self.is_64_bit:
            return CoreExtensions(['I','M','A','C','Zicsr','Zifencei'], "lp64", 64)
        return CoreExtensions(['I','M','A','C','Zicsr','Zifencei'], "ilp32", 32)

    def has_isax_support(self) -> bool:
        return True
    def get_srcs_folder_name(self) -> str:
        return "CVA6"
    def get_maketop(self) -> str:
        return "CVA6_maketop_bcb0f7d.py"

    # -> tb srcs, core srcs, tb top module, core top module, include dirs, defines, extra sim makefile args
    def get_core_srcs(self, scal_sources, core_dir) -> tuple[list[str], list[str], str, str, list[str], list[str], dict[str, str]]:
        core_srcs = [
            "core/scaiev_config.sv",
            "core/include/config_pkg.sv",
            "core/include/%s_scaiev_config_pkg.sv" % ("cv64a6_imac_sv39" if self.is_64_bit else "cv32a6_imac_sv32"),
            "core/include/riscv_pkg.sv",
            "corev_apu/riscv-dbg/src/dm_pkg.sv",
            "core/cvfpu/src/fpnew_pkg.sv",
            "core/include/ariane_pkg.sv",
            "core/include/build_config_pkg.sv",
            "corev_apu/tb/ariane_soc_pkg.sv",
            "vendor/pulp-platform/axi/src/axi_pkg.sv",
            "corev_apu/tb/ariane_axi_pkg.sv",
            "core/include/wt_cache_pkg.sv",
            "corev_apu/tb/axi_intf.sv",
            "vendor/pulp-platform/common_cells/src/cf_math_pkg.sv",
            "core/include/instr_tracer_pkg.sv",
            "core/cvxif_example/include/cvxif_instr_pkg.sv",
            "core/acc_dispatcher.sv",
            "corev_apu/rv_plic/rtl/rv_plic_reg_pkg.sv",
            "common/local/util/sram.sv",
            "vendor/pulp-platform/common_cells/src/deprecated/rrarbiter.sv",
            "vendor/pulp-platform/common_cells/src/deprecated/fifo_v1.sv",
            "vendor/pulp-platform/common_cells/src/deprecated/fifo_v2.sv",
            "vendor/pulp-platform/common_cells/src/fifo_v3.sv",
            "vendor/pulp-platform/common_cells/src/shift_reg.sv",
            "vendor/pulp-platform/common_cells/src/lfsr_8bit.sv",
            "vendor/pulp-platform/common_cells/src/lfsr.sv",
            "core/cva6_fifo_v3.sv",
            "core/scaiev_fu.sv",
            "core/include/aes_pkg.sv",
            "core/aes.sv",
            "vendor/pulp-platform/common_cells/src/lzc.sv",
            "vendor/pulp-platform/common_cells/src/exp_backoff.sv",
            "vendor/pulp-platform/common_cells/src/rr_arb_tree.sv",
            "vendor/pulp-platform/common_cells/src/rstgen_bypass.sv",
            "vendor/pulp-platform/common_cells/src/cdc_2phase.sv",
            "vendor/pulp-platform/common_cells/src/unread.sv",
            "vendor/pulp-platform/common_cells/src/popcount.sv",
            "corev_apu/axi_mem_if/src/axi2mem.sv",
            "vendor/pulp-platform/tech_cells_generic/src/deprecated/cluster_clk_cells.sv",
            "vendor/pulp-platform/tech_cells_generic/src/deprecated/pulp_clk_cells.sv",
            "common/local/util/tc_sram_wrapper.sv",
            "vendor/pulp-platform/tech_cells_generic/src/rtl/tc_sram.sv",
            "vendor/pulp-platform/tech_cells_generic/src/rtl/tc_clk.sv",
            "core/cache_subsystem/axi_adapter.sv",
            "core/alu.sv",
            "core/fpu_wrap.sv",
            "corev_apu/src/ariane.sv",
            "core/cva6_rvfi_probes.sv",
            "core/cva6.sv",
            "core/branch_unit.sv",
            "core/compressed_decoder.sv",
            "core/controller.sv",
            "core/csr_buffer.sv",
            "core/csr_regfile.sv",
            "core/decoder.sv",
            "core/ex_stage.sv",
            "core/frontend/btb.sv",
            "core/frontend/bht.sv",
            "core/frontend/bht2lvl.sv",
            "core/frontend/ras.sv",
            "core/frontend/instr_scan.sv",
            "core/frontend/instr_queue.sv",
            "core/frontend/frontend.sv",
            "core/id_stage.sv",
            "core/instr_realign.sv",
            "core/cvxif_issue_register_commit_if_driver.sv",
            "core/issue_read_operands.sv",
            "core/issue_stage.sv",
            "core/load_unit.sv",
            "core/load_store_unit.sv",
            "core/lsu_bypass.sv",
            "core/cva6_mmu/cva6_mmu.sv",
            "core/cva6_mmu/cva6_ptw.sv",
            "core/cva6_mmu/cva6_tlb.sv",
            "core/cva6_mmu/cva6_shared_tlb.sv",
            "core/mult.sv",
            "core/multiplier.sv",
            "core/serdiv.sv",
            "core/perf_counters.sv",
            "core/ariane_regfile_ff.sv",
            "core/scaiev_glue.sv",
            "core/cva6_glue_wrapper.sv",
            "cva6_ariane_wrapper.sv",
            "core/scoreboard.sv",
            "core/raw_checker.sv",
            "core/store_buffer.sv",
            "core/amo_buffer.sv",
            "core/store_unit.sv",
            "core/commit_stage.sv",
            "core/cache_subsystem/wt_dcache_ctrl.sv",
            "core/cache_subsystem/wt_dcache_mem.sv",
            "core/cache_subsystem/wt_dcache_missunit.sv",
            "core/cache_subsystem/wt_dcache_wbuffer.sv",
            "core/cache_subsystem/wt_dcache.sv",
            "core/cache_subsystem/cva6_icache.sv",
            "core/cache_subsystem/cva6_icache_axi_wrapper.sv",
            "core/cache_subsystem/wt_l15_adapter.sv",
            "core/axi_shim.sv",
            "core/cache_subsystem/wt_axi_adapter.sv",
            "core/cache_subsystem/wt_cache_subsystem.sv",
            "corev_apu/clint/clint.sv",
            "corev_apu/clint/axi_lite_interface.sv",
            "corev_apu/riscv-dbg/debug_rom/debug_rom.sv",
            "corev_apu/riscv-dbg/src/dm_csrs.sv",
            "corev_apu/riscv-dbg/src/dm_mem.sv",
            "corev_apu/riscv-dbg/src/dm_top.sv",
            "corev_apu/riscv-dbg/src/dmi_cdc.sv",
            "corev_apu/riscv-dbg/src/dmi_jtag.sv",
            "corev_apu/riscv-dbg/src/dm_sba.sv",
            "corev_apu/riscv-dbg/src/dmi_jtag_tap.sv",
            "corev_apu/rv_plic/rtl/rv_plic_target.sv",
            "corev_apu/rv_plic/rtl/rv_plic_gateway.sv",
            "corev_apu/rv_plic/rtl/plic_regmap.sv",
            "corev_apu/rv_plic/rtl/plic_top.sv",
            "corev_apu/register_interface/src/apb_to_reg.sv",
            "corev_apu/register_interface/src/reg_intf.sv",
            "core/cvfpu/src/fpu_div_sqrt_mvp/hdl/defs_div_sqrt_mvp.sv",
            "core/cvfpu/src/fpu_div_sqrt_mvp/hdl/control_mvp.sv",
            "core/cvfpu/src/fpu_div_sqrt_mvp/hdl/div_sqrt_mvp_wrapper.sv",
            "core/cvfpu/src/fpu_div_sqrt_mvp/hdl/div_sqrt_top_mvp.sv",
            "core/cvfpu/src/fpu_div_sqrt_mvp/hdl/iteration_div_sqrt_mvp.sv",
            "core/cvfpu/src/fpu_div_sqrt_mvp/hdl/norm_div_sqrt_mvp.sv",
            "core/cvfpu/src/fpu_div_sqrt_mvp/hdl/nrbd_nrsc_mvp.sv",
            "core/cvfpu/src/fpu_div_sqrt_mvp/hdl/preprocess_mvp.sv",
            "core/cvfpu/src/fpnew_cast_multi.sv",
            "core/cvfpu/src/fpnew_classifier.sv",
            "core/cvfpu/src/fpnew_divsqrt_multi.sv",
            "core/cvfpu/src/fpnew_fma_multi.sv",
            "core/cvfpu/src/fpnew_fma.sv",
            "core/cvfpu/src/fpnew_noncomp.sv",
            "core/cvfpu/src/fpnew_opgroup_block.sv",
            "core/cvfpu/src/fpnew_opgroup_fmt_slice.sv",
            "core/cvfpu/src/fpnew_opgroup_multifmt_slice.sv",
            "core/cvfpu/src/fpnew_rounding.sv",
            "core/cvfpu/src/fpnew_top.sv",
            "core/pmp/src/pmp.sv",
            "core/pmp/src/pmp_entry.sv",
            "core/pmp/src/pmp_data_if.sv",
            "common/local/util/instr_tracer.sv",
            "core/cvxif_example/cvxif_example_coprocessor.sv",
            "core/cvxif_example/instr_decoder.sv",
            "vendor/pulp-platform/common_cells/src/counter.sv",
            "vendor/pulp-platform/common_cells/src/delta_counter.sv",
            "core/cvxif_fu.sv"
        ]

        include_dirs = [
            "core",
            "core/include",
            "vendor/pulp-platform/common_cells/include",
            "vendor/pulp-platform/common_cells/src",
            "vendor/pulp-platform/axi/include",
            "common/local/util",
            "core/cache_subsystem/hpdcache/rtl/include",
        ]

        plus_defines = read_file_lines(os.path.join(core_dir, "SCAIE-V_Flags.txt"))
        assert len(plus_defines) == 1
        plus_defines = plus_defines[0]
        defines = [d for d in plus_defines.split("+") if d]
        defines.append("ENABLE_SIMULATION_ASSERTIONS")
        defines.append("CVA6_ENABLE_RVFI")

        extra_makefile_args = {
            "verilator": """
SRCDIR=../%s
# Mute some verilator warnings
EXTRA_ARGS+=-Wno-BLKANDNBLK $(SRCDIR)/verilator_config.vlt -Wno-fatal
""" % self.scaiev_core_name
        }

        return ["CVA6_tb_wrapper.v"], core_srcs + scal_sources, "testbench", "cva6_ariane_wrapper", include_dirs, defines, extra_makefile_args

    def get_tb_env_vars(self) -> list[str]:
        return [
            # Number of Bus slave interfaces the simulator should instantiate.
            "NUM_BUSSI=1",
            # Types and port names for each Bus SI.
            "BUSSI0_TYPE=AXI4",
            "BUSSI0_SIGNAME=m_axi_ctrl",
            # Bus SI index for IMEM.
            "IMEM_BUSIDX=0",
            # The base address of the instruction memory on the bus.
            "IMEM_BASE=80000000",
            # The address of the exception handler on the instruction bus, for error detection (optional).
            "EXCEPTION_BASE=808",
            # Bus SI index for DMEM.
            "DMEM_BUSIDX=0",
            # The base address of the data memory on the bus.
            "DMEM_BASE=80100000",
            # The physical size of the data memory.
            "DMEM_SIZE=00100000",
            # Bus SI index for CTRL.
            "CTRL_BUSIDX=0",
            # The base address of the MMIO control block on the bus (for completion IRQ).
            "CTRL_BASE=60000000",
        ]

    def get_linker_file(self) -> str:
        return self._get_linker_file("CVA6")
    def get_specific_startup_file(self) -> str:
        return self._get_specific_startup_file("CVA6")
    def get_longnail_datasheet_name(self) -> str:
        return "CVA6.yaml"

def get_supported_cores():
    def _config_variant_bcb0f7d(is_64_bit: bool, is_dual: bool) -> tuple[str, str, CVA6Support_bcb0f7d]:
        inst = CVA6Support_bcb0f7d(is_64_bit, is_dual)
        return (f'CORE_CVA6{"_64" if is_64_bit else ""}{"_DUAL" if is_dual else ""}', inst.scaiev_core_name, inst)
    return [
        _config_variant_bcb0f7d(False, False),
        _config_variant_bcb0f7d(True,  False),
        _config_variant_bcb0f7d(False, True),
        _config_variant_bcb0f7d(True,  True),
    ]
