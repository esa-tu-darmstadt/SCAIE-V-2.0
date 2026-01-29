#!/usr/bin/env python3
import os
import glob

import longnail
import scaiev
import error
import run_cmd

import ll_dse
import run_librelane

def run_yosys_slang(srcs, include_dirs, defines, out_src, top_module):
    include_dirs = [f"-I {d}" for d in include_dirs]
    defines = [f"-D {d}" for d in defines]
    # TODO having to run bwmuxmap manually might be a bug: https://github.com/YosysHQ/yosys/issues/4751
    run_cmd.run(".", f'yosys -m deps/yosys-slang/build/slang.so -p "read_slang --allow-dup-initial-drivers --top {top_module} {" ".join(include_dirs)} {" ".join(defines)} {" ".join(srcs)}; bwmuxmap; opt_clean; write_verilog \\"{out_src}\\""', "Failed to preprocess core files with yosys-slang", error.LIBRELANE_BASE + 1)

def run_synthesis(out_dir, core_name, kconfig_syms, isax_name, syn_dir_suffix):
    if kconfig_syms['LL_ENABLE'].str_value != "y":
        return None

    print("Running LibreLane")
    # Create the output directory
    syn_dir = os.path.abspath(os.path.join(out_dir, f"hw_syn{syn_dir_suffix}"))
    os.makedirs(syn_dir, exist_ok=False)

    _external_tb_srcs, core_srcs, _tb_top_module, core_top_module, include_dirs, defines,_extra_makefile_opts = scaiev.select_tb_wrapper_srcs(core_name, out_dir)

    core_base = os.path.abspath(os.path.join(out_dir, core_name))
    include_dirs = [os.path.join(core_base, d) for d in include_dirs]
    core_srcs = list(map(lambda s: os.path.join(core_base, s), core_srcs))

    isax_src = list(map(lambda s: os.path.abspath(s), glob.glob(os.path.join(out_dir, '*.sv'))))
    isax_src = isax_src + list(map(lambda s: os.path.abspath(s), glob.glob(os.path.join(out_dir, '*.v'))))

    if core_name == "CVA5" or core_name == "CVA6" or core_name == "CVA6_64":
        sv2v_dir = os.path.join(syn_dir, "sv2v")
        os.makedirs(sv2v_dir, exist_ok=False)
        sv2v_outfile = os.path.join(sv2v_dir, "core.v")
        defines.append("VERILATOR")
        # Workaround for: https://github.com/povik/yosys-slang/issues/76
        defines.append("DISABLE_ASSERT_PROPERTY")
        run_yosys_slang(core_srcs + isax_src, include_dirs, defines, sv2v_outfile, core_top_module)
        core_srcs = [sv2v_outfile]
        include_dirs = []
        isax_src = []

    verilog_srcs = core_srcs + isax_src

    algo_name = longnail.resolve_sched_algo(kconfig_syms)
    config_template = kconfig_syms["LL_CONFIG_TEMPLATE"].str_value
    clock_period = 1000.0 / int(kconfig_syms["LL_TARGET_FREQ"].str_value)
    fp_util = int(kconfig_syms["LL_TARGET_UTIL"].str_value)
    top_module = core_top_module
    clk_name = "clk"
    if kconfig_syms["LL_ONLY_SYN_ISAX"].str_value == "y":
        top_module = f"ISAX_{isax_name}"
        verilog_srcs = isax_src
        clk_name = "clk_i"
    
    if kconfig_syms["LL_DSE"].str_value == "y":
        # Start DSE
        ol_dse.run_dse(verilog_srcs, syn_dir, isax_name, algo_name, clock_period, fp_util, top_module, clk_name, config_template, defines, include_dirs)
    else:
        # Only perfom a single librelane run
        config_values = ol_dse.get_config_values(top_module, clk_name, clock_period, fp_util, defines, include_dirs)
        config_values['{{SRC_FILES}}'] = ol_dse.get_ol_config_src_entries(verilog_srcs, syn_dir)
        run_librelane.run_librelane(syn_dir, config_template, config_values, None, None)

    return None # NYI
