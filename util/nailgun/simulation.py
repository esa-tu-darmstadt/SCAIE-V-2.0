#!/usr/bin/env python3
import os
import functools
import glob
import shutil

import error
import run_cmd
import scaiev
import longnail
import yaml
import renode
import toolchain

from tools.elftohex import elf_to_hex

def run_tb(kconfig_syms, out_dir, core_name, isax_yaml_paths, elf_files, tb_expected_paths, memory_config=None, gls=None, renode_isax_py_path=None):
    # Create the output directory
    sim_dir = os.path.abspath(os.path.join(out_dir, "sim"))
    os.makedirs(sim_dir, exist_ok=False)

    external_tb_srcs, core_srcs, tb_top_module, core_top_module, include_dirs, defines, extra_makefile_opts = scaiev.select_tb_wrapper_srcs(core_name, out_dir)

    # Convert extra_makefile_opts dictionary into strings
    extra_makefile_opts_strs = []
    # Check if "default" exists and add it unconditionally
    if "default" in extra_makefile_opts:
        extra_makefile_opts_strs.append(extra_makefile_opts["default"])

    # Generate the ifeq statements for other entries
    for key, value in extra_makefile_opts.items():
        if key != "default":
            extra_makefile_opts_strs.append(f"ifeq ($(SIM), {key})")
            extra_makefile_opts_strs.append(value)
            extra_makefile_opts_strs.append(f"endif # SIM == {key}")

    # Add absolute paths to the tb wrapper srcs
    wrapper_base = os.path.abspath("deps/scaie-v/util/maketop")
    external_tb_srcs = list(map(lambda s: os.path.join(wrapper_base, s), external_tb_srcs))
    # Copy the external tb_srcs to the sim folder, we do not want external dependencies!
    tb_srcs = []
    for s in external_tb_srcs:
        internal_tb_src = shutil.copy(s, sim_dir)
        tb_srcs.append(internal_tb_src)

    core_base = os.path.abspath(os.path.join(out_dir, core_name))
    core_srcs = list(map(lambda s: os.path.join(core_base, s), core_srcs))
    isax_src = list(map(lambda s: os.path.abspath(s), glob.glob(os.path.join(out_dir, '*.sv'))))
    isax_src = isax_src + list(map(lambda s: os.path.abspath(s), glob.glob(os.path.join(out_dir, '*.v'))))
    verilog_srcs = core_srcs + tb_srcs + isax_src

    # Find all .py files in the sim directory
    py_files = glob.glob(os.path.join("sim", '*.py'))
    # Also copy additionally required tools
    py_files.append(os.path.join("tools", "isax_yaml_tools.py"))

    # Copy each file and package to the output simulation folder
    for file in py_files:
        shutil.copy(file, sim_dir)
    for package in ("iss", "trace"):
        shutil.copytree(os.path.join("sim", package), os.path.join(sim_dir, package))

    assert(len(tb_expected_paths) == len(elf_files))
    # Copy expected output file next to the elf file
    copied_expected_paths = [os.path.join(os.path.dirname(get_target_elf_file_path(out_dir)), os.path.basename(tb_expected_path)) for tb_expected_path in tb_expected_paths]
    for in_path, out_path in zip(tb_expected_paths, copied_expected_paths):
        shutil.copy(in_path, out_path)

    # Convert the absolute verilog_srcs paths to relative paths from the sim directory
    verilog_srcs = [os.path.relpath(p, sim_dir) for p in verilog_srcs]

    def gen_testprog_arg(elf_file):
        return f"TESTPROG=\"{os.path.relpath(elf_file[:-len('.elf')], sim_dir)}\""
    def gen_expected_res_arg(expected_path):
        return f'EXPECTED="{os.path.relpath(expected_path, sim_dir)}"'

    num_ctxs = kconfig_syms['SCV_INTERNAL_CONTEXTS_AMOUNT'].str_value
    num_ctxs = num_ctxs if num_ctxs else "1"

    ext = scaiev.get_core_support(core_name).get_extensions()

    env_vars = [
        "TESTPROG=$(TESTPROG)",
        "EXPECTED=$(EXPECTED)",
        f"CORE_NAME={core_name}",
        f"CORE_ISA_EXT={','.join(ext.archext_list)}",
        f"ISAX_YAML={';'.join((os.path.relpath(isax_yaml_path, sim_dir) for isax_yaml_path in isax_yaml_paths))}",
        f"ISAX_RENODE={os.path.relpath(renode_isax_py_path, sim_dir)}" if renode_isax_py_path is not None else "",
        f"ISAX_PYTHON={kconfig_syms['SIM_ISS_PREDEFINED_ISAXES'].str_value}",
        f"NUMBER_OF_CONTEXTS={num_ctxs}",
        f"CYCLE_TIMEOUT={kconfig_syms['SIM_CYCLE_TIMEOUT'].str_value}",
        f"PRINT_CLK={1 if kconfig_syms['SIM_PRINT_CLK'].str_value == 'y' else 0}",
        f"PRINT_IMEM={1 if kconfig_syms['SIM_PRINT_IMEM'].str_value == 'y' else 0}",
        f"PRINT_DMEM={1 if kconfig_syms['SIM_PRINT_DMEM'].str_value == 'y' else 0}",
        f"PRINT_BRAM={1 if kconfig_syms['SIM_PRINT_BRAM'].str_value == 'y' else 0}",
        f"PRINT_AXI={1 if kconfig_syms['SIM_PRINT_AXI'].str_value == 'y' else 0}",
        f"PRINT_ISS={1 if kconfig_syms['SIM_PRINT_ISS'].str_value == 'y' else 0}",
    ] + scaiev.select_tb_env_vars(core_name)

    newline = "\n"

    defines_common = [f"EXTRA_ARGS += -D{d}" for d in defines]
    defines_questa = [f"EXTRA_ARGS += +define+{d}" for d in defines]
    include_dirs = [f"EXTRA_ARGS += -I{os.path.relpath(os.path.join(core_base, inc), sim_dir)}" for inc in include_dirs]

    # questa and GLS setup
    if memory_config is not None:
        memory_initializers = list(map(lambda hex_config: f"SIM_ARGS += -g{hex_config[1]['instance_parameter']}=\"../tb_bin/{hex_config[0]}\"", memory_config["convert_to_hex"].items()))
    else:
        memory_initializers = []
    
    memory_initializers = "\n".join(memory_initializers)
    standard_cell_sources = netlist_file = sdf = ""
    if gls is not None:
        netlist_file = gls["netlist"]
        sdf = gls.get("sdf", "")
        cell_paths = gls["cells"]
        standard_cell_sources = list(map(lambda cell_verilog: f"VERILOG_SOURCES += {cell_verilog}", cell_paths))
        standard_cell_sources = "\n".join(standard_cell_sources)

    sim_lockstep = kconfig_syms['SIM_ENABLE_ISS_LOCKSTEP'].str_value == 'y'

    # Create a makefile to run the simulation
    sim_mk = os.path.join(sim_dir, "Makefile")
    with open(sim_mk, 'w') as f:
        f.write(f"""
VERILOG_SOURCES = {functools.reduce(lambda a, b: a + " " + b, verilog_srcs)}
TOPLEVEL_LANG = verilog
TOPLEVEL = {tb_top_module}
MODULE ?= {"test_iss_lockstep" if sim_lockstep else "test_default"}
SIM ?= verilator
GLS ?= 0
GUI ?= 0
# dump waveforms for icarus, questa, ...
WAVES ?= 1

TESTPROG ?= {os.path.relpath(elf_files[0][:-len('.elf')], sim_dir)}
EXPECTED ?= {os.path.relpath(copied_expected_paths[0], sim_dir)}

# Verilator specific flags
ifeq ($(SIM), verilator)
ifeq ($(GLS), 1)
$(error GLS not implemented with verilator. Use SIM=questa to run GLS.)
endif # GLS == 1
# Do not treat warnings as fatal errors!
EXTRA_ARGS += -Wno-fatal
# Enable assertions
EXTRA_ARGS += --assert
# Tracing options
EXTRA_ARGS += --trace-fst --trace --trace-structs --trace-underscore
# Use more than one core to compile the simulation models
BUILD_ARGS += -j$(shell nproc)
EXTRA_ARGS += --no-timing
endif # SIM == verilator

# Questa specific flags
ifeq ($(SIM), questa)
ifeq ($(GLS), 1)
# It is recommended that you create a new testbench (e.g., test_gls.py) to change the testbench to your chip design and pass it with MODULE=test_gls to your make invocation.
NETLIST_FILE = {netlist_file}

ifeq ($(NETLIST_FILE),)
$(error NETLIST_FILE is undefined. Set to your netlist verilog file path.)
endif # NETLIST_FILE == ""
# Overwrite the verilog sources to use the synthesized netlist instead
VERILOG_SOURCES = $(NETLIST_FILE)
{standard_cell_sources}


SDF_FILE={sdf}
ifneq ($(SDF_FILE),)
SDFTYPE ?= -sdfmax
SIM_ARGS += $(SDFTYPE) $(SDF_FILE)
else
COMPILE_ARGS += +delay_mode_unit
EXTRA_ARGS += +define+IVCS_INIT_MEM
endif # SDF_FILE != ""
endif # GLS == 1
{memory_initializers}
endif # SIM == questa

# Include directories
{newline.join(include_dirs)}
# Defines
ifeq ($(SIM), questa)
{newline.join(defines_questa)}
else
{newline.join(defines_common)}
endif

# Extra Makefile options
{newline.join(extra_makefile_opts_strs)}

# test_default.py configuration settings
PLUSARGS = ""
{newline.join([f'PLUSARGS += "+{var}"' for var in env_vars if len(var)>0])}

ifeq ($(GLS), 1)
PLUSARGS += "+GLS=1"
endif # GLS == 1

# Additional arguments
# - Simulated clock period (clk signal) in ps, default: 1000
# PLUSARGS += "+CLK_PERIOD=1000"
# - To fix removal violations on reset paths (timing-annotated GLS):
#   Delay to hold the clk signal before the negedge of rst in CLK_PERIODs, default: 0
# PLUSARGS += "+RESET_CLKGATE_CYCLES_PRE=10"
# - To fix recovery violations on reset paths (timing-annotated GLS):
#   Delay to hold the clk signal after the negedge of rst in CLK_PERIODs, default: 0
# PLUSARGS += "+RESET_CLKGATE_CYCLES_POST=10"
# - To fix premature sampling by testbench (timing-annotated GLS):
#   Delay to sampling output pins from the core after each posedge of clk in ps, default: 0
# PLUSARGS += "+SAMPLE_DELAY=500"
# - To fix input hold violations inside the core (timing-annotated GLS):
#   Delay to setting input pins towards the core after each posedge of clk in ps, default: 0
# PLUSARGS += "+ASSIGN_DELAY=300"

include $(shell cocotb-config --makefiles)/Makefile.sim
""")

    results_xml_path = os.path.join(sim_dir, "results.xml")
    for elf_file, expected_path in zip(elf_files, copied_expected_paths):
        # We ALWAYS want colors, lol
        run_cmd.run(sim_dir, f"{gen_testprog_arg(elf_file)} {gen_expected_res_arg(expected_path)} OBJCACHE=ccache COCOTB_ANSI_OUTPUT=1 make sim && ! grep -nri 'Test failed' {results_xml_path}", f"The simulation of '{elf_file}' failed!", error.SIM_BASE + 1)

def setup_renode(py_isax_file, tb_paths, core_support, out_dir, yaml_files):
    env_vars = core_support.get_tb_env_vars()
    ext = core_support.get_extensions()
    march = f"rv{ext.xlen}{ext.get_compiler_extensions()}"
    py_isax_file_name = os.path.basename(py_isax_file) if py_isax_file else ""

    renode_dir = renode.gen_renode_confs(py_isax_file_name, out_dir, yaml_files, tb_paths, march, scaiev.get_env_value(env_vars, "IMEM_BASE"), scaiev.get_env_value(env_vars, "DMEM_BASE"), scaiev.get_env_value(env_vars, "DMEM_SIZE"), scaiev.get_env_value(env_vars, "CTRL_BASE"))

    isax_py_path = None
    # copy python ISAX model to renode directory
    if py_isax_file and os.path.exists(py_isax_file):
        isax_py_path = f"{renode_dir}/{py_isax_file_name}"
        shutil.copy(py_isax_file, renode_dir)
    if os.path.exists("deps/longnail"):
        shutil.copy("deps/longnail/sim/ArbInt.py", renode_dir)
    else:
        isax_py_path = None
    return isax_py_path

def find_yaml_files(out_dir):
    # Construct the search pattern
    search_pattern = os.path.join(out_dir, '*.yaml')
    # Use glob to find files matching the pattern
    yaml_files = glob.glob(search_pattern)
    if yaml_files:
        yaml_files = [f for f in yaml_files if "selected_solutions.yaml" not in f]
        return [os.path.abspath(yaml_file) for yaml_file in yaml_files]
    else:
        return None

def get_target_elf_file_path(out_dir):
    # Create the output directory
    bin_dir = os.path.abspath(os.path.join(out_dir, "tb_bin"))
    os.makedirs(bin_dir, exist_ok=True)

    # elf file path
    return os.path.join(bin_dir, "tb.elf")

def run_simulation(out_dir, core_name, kconfig_syms, isax_name, mlir_path, only_add_cc_support):
    core_support = scaiev.get_core_support(core_name)
    if not only_add_cc_support and kconfig_syms['SIM_ENABLE'].str_value != "y":
        return
    if not only_add_cc_support:
        print("Running full core + ISAX simulation:")
        if not os.path.exists(kconfig_syms['SIM_TB_PATH'].str_value):
            error.exit_error("Simulation testbench path is missing!", error.USER_ERROR)
        if not os.path.exists(kconfig_syms['SIM_TB_EXPECTED_PATH'].str_value):
            error.exit_error("Simulation testbench expected output path is missing!", error.USER_ERROR)
    else:
        print("Only adding ISAX compiler support")
    isax_yaml_paths = find_yaml_files(out_dir)
    tb_path = os.path.abspath(kconfig_syms['SIM_TB_PATH'].str_value)
    tb_expected_path = os.path.abspath(kconfig_syms['SIM_TB_EXPECTED_PATH'].str_value)
    tb_expected_paths = [tb_expected_path]
    additional_flags = kconfig_syms['SIM_TB_COMPILE_FLAGS'].str_value
    disassemble_tb = kconfig_syms['SIM_TB_DISASSEMBLE_ELF'].str_value == "y"

    def patch_and_compile_with_gcc(filepaths, custom_linker_script=None, include_startup_files=False):
        print(" - Adding ISAX assembly support to GCC")
        if kconfig_syms['SIM_SKIP_AWESOME_LLVM'].str_value != "y":
            toolchain.prepare_gcc(kconfig_syms, isax_yaml_paths)
        if not only_add_cc_support:
            print(" - Compiling assembly TB")
            return toolchain.gcc_compile_tb(filepaths, core_support, get_target_elf_file_path(out_dir), additional_flags, disassemble_tb, custom_linker_script, include_startup_files=include_startup_files)
    
    def patch_and_compile_with_llvm(filepaths, custom_linker_script=None):
        llvm_version = kconfig_syms['SIM_AWESOME_LLVM_VERSION'].str_value
        clang_exists, _ = toolchain.check_clang_exists(llvm_version)
        skip_clang_build = kconfig_syms['SIM_SKIP_AWESOME_LLVM'].str_value == "y" and clang_exists
        unpatched_clang = (not skip_clang_build) and (not mlir_path)
        if unpatched_clang:
            print("WARNING: Patching clang requires a ISAX MLIR input file!")
            print("INFO: Using unpatched clang!")
        else:
            print(" - Adding ISAX support to clang")
        llvm_build_dir = toolchain.prepare_llvm(kconfig_syms, mlir_path, llvm_version, not skip_clang_build, unpatched_clang)
        if not only_add_cc_support:
            print(" - Compiling C++ TB")
            if not unpatched_clang and not isax_name:
                error.exit_error("Compiling the TB with clang requires an ISAX name to select the correct extension! The ISAX name can manually be overwritten via the 'SIM_AWESOME_LLVM_OVERWRITE_ISAX_NAME' option", error.USER_ERROR)
            return toolchain.llvm_compile_tb(filepaths, core_support, get_target_elf_file_path(out_dir), llvm_build_dir, isax_name, additional_flags, llvm_version, disassemble_tb, custom_linker_script)
        else:
            # Ensure that picolibc exists for all cores
            toolchain.precompile_picolibc_for_all_cores(llvm_version)

    def process_bin_file(bin_file, elf_file, first_run):
        # Convert axf to elf_file
        if bin_file.endswith(".axf"):
            if first_run and kconfig_syms['SIM_SKIP_AWESOME_LLVM'].str_value != "y":
                toolchain.prepare_gcc(kconfig_syms, isax_yaml_paths)
            objcopy_path = toolchain.get_gcc_objcopy_path()
            run_cmd.run(".", f"{objcopy_path} {bin_file} {elf_file}", "Failed to convert axf file to an elf file!", error.GCC_BASE + 5, False)
        else:
            # copy the elf file to our target folder
            shutil.copy(bin_file, elf_file)
        # If requested disassemble the elf file
        if disassemble_tb:
            objdump_path = toolchain.get_gcc_objdump_path()
            toolchain.disas_tb(objdump_path, elf_file, error.GCC_BASE + 4)

    memory_config = None
    gls = None
    if tb_path.endswith(".axf") or tb_path.endswith(".elf"):
        elf_file = get_target_elf_file_path(out_dir)
        process_bin_file(tb_path, elf_file, first_run=True)
        elf_files = [elf_file]
    elif tb_path.endswith(".s") or tb_path.endswith(".S"):
        elf_files = [patch_and_compile_with_gcc([tb_path])]
    elif tb_path.endswith(".yml") or tb_path.endswith(".yaml"):
        shutil.copy(tb_path, out_dir)
        with open(tb_path, "r") as yamlfile:
            test_config = yaml.safe_load(yamlfile)

        gls = test_config.get("gls", None)
        compiler = test_config.get("compiler", None)
        if type(compiler) == str:
            error.exit_error(f"Specify the compiler via the `name` property", error.USER_ERROR)
        compiler_name = compiler.get("name", None)
        gcc_use_startup_files = compiler.get("gcc include startup asm", False)
        files = test_config.get("files", [])
        if len(files) == 0:
            error.exit_error(f"Field testbench `files` is missing or empty", error.USER_ERROR)

        multi_binary_test = all([f.endswith(".axf") or f.endswith(".elf") for f in files])

        tb_folder = os.path.dirname(tb_path)
        absolute_file_paths = [f if os.path.isabs(f) else os.path.join(tb_folder, f) for f in files]

        if multi_binary_test:
            first_run = True
            elf_files = []
            root, ext = os.path.splitext(get_target_elf_file_path(out_dir))
            for idx, f in enumerate(absolute_file_paths):
                target_elf_file = f"{root}_{idx}{ext}"
                elf_files.append(target_elf_file)
                process_bin_file(f, target_elf_file, first_run=first_run)
                first_run = False
            # For now just replicate the expected path... TODO add support for specifiying for each tb file a different expected file
            tb_expected_paths = tb_expected_paths * len(absolute_file_paths)
        else:
            custom_linker_script = None
            memory_config = test_config.get("memory", None)
            if memory_config is not None:
                custom_linker_script = memory_config.get("linker_script", None)
                if custom_linker_script is not None:
                    custom_linker_script = os.path.join(tb_folder, custom_linker_script)
                    print(f"Using custom linker script {custom_linker_script}")

            if compiler_name == "gcc":
                elf_file = patch_and_compile_with_gcc(absolute_file_paths, custom_linker_script, include_startup_files=gcc_use_startup_files)
            elif compiler_name == "clang":
                elf_file = patch_and_compile_with_llvm(absolute_file_paths, custom_linker_script)
            else:
                error.exit_error(f"Unknown compiler name '{compiler_name}'. Either use 'gcc' or 'clang'.", error.USER_ERROR)

            if memory_config is not None:
                for hex_name, hex_config in memory_config.get("convert_to_hex", {}).items():
                    section_names = hex_config["sections"]
                    print(f"Dumping sections {section_names} to file {hex_name}")
                    memory_size = int(hex_config["size"])
                    bytes_per_word = int(hex_config["word_width"])
                    elf_to_hex(elf_file, os.path.abspath(os.path.join(out_dir, "tb_bin", hex_name)), section_names, word_size=bytes_per_word, memory_size=memory_size)
            elf_files = [elf_file]
    else:
        elf_files = [patch_and_compile_with_llvm([tb_path])]

    if not only_add_cc_support:
        renode_py_file = kconfig_syms["SIM_ISS_RENODE_OVERRIDE"].str_value #ignored if ""
        if isax_name and not renode_py_file:
            renode_py_file = os.path.join(out_dir, f"{isax_name}.py")
        renode_isax_py_path = setup_renode(renode_py_file, elf_files, core_support, out_dir, isax_yaml_paths)
        print(" - Start simulation")
        run_tb(kconfig_syms, out_dir, core_name, isax_yaml_paths, elf_files, tb_expected_paths, memory_config, gls, renode_isax_py_path)
