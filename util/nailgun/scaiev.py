#!/usr/bin/env python3
import os
import shutil
from abc import ABC, abstractmethod
import importlib.util

import error
import run_cmd

class CoreExtensions:
    def __init__(self, archext_list: list[str], abi: str, xlen: int):
        self.archext_list = archext_list.copy()
        self.abi = abi
        self.xlen = xlen
        if xlen not in (32,64):
            raise 
    def get_compiler_extensions(self) -> str:
        return '_'.join([ext.lower() for ext in self.archext_list])

class CoreSupport(ABC):
    @abstractmethod
    def copy_blacklist(self) -> list[str]:
        pass
    @abstractmethod
    def has_isax_support(self) -> bool:
        pass
    @abstractmethod
    def run_extra_build_steps(self, target_dir, kconf_syms) -> None:
        pass
    @abstractmethod
    def get_srcs_folder_name(self) -> str:
        pass
    @abstractmethod
    def get_maketop(self) -> str:
        pass
    @abstractmethod
    def get_extensions(self) -> CoreExtensions:
        pass
    # -> tb srcs, core srcs, tb top module, core top module, include dirs, defines, extra sim makefile args
    @abstractmethod
    def get_core_srcs(self, scal_sources, core_dir) -> tuple[list[str], list[str], str, str, list[str], list[str], dict[str, str]]:
        pass
    @abstractmethod
    def get_tb_env_vars(self) -> list[str]:
        pass
    @abstractmethod
    def get_linker_file(self) -> str:
        pass
    @abstractmethod
    def get_longnail_datasheet_name(self) -> str:
        pass

    def _get_linker_file(self, core) -> str:
        base_path = os.path.abspath(os.path.join("sim", "linker_scripts"))
        ld_file = os.path.join(base_path, f"{core}_link.ld")
        if not os.path.exists(ld_file):
            error.exit_error(f"No linker file found for '{core}'! Expected path: {ld_file}", error.INTERNAL_ERROR)
        return ld_file
    @abstractmethod
    def get_specific_startup_file(self) -> str:
        pass

    def _get_specific_startup_file(self, core) -> str:
        core_specific_asm = os.path.abspath(os.path.join("sim", "startup_scripts", f"{core}_init.s"))
        if os.path.exists(core_specific_asm):
            return f"-DASM_PERCOREENTRY=\\\"{core_specific_asm}\\\""
        return ""


supported_cores = dict()
kconfig_to_core_name = dict()

def _collect_available_cores(callback):
    plugin_folder = "cores"
    # Walk through the directory, including subdirectories
    for root, dirs, files in os.walk(plugin_folder):
        # We calculate the depth by checking the number of separators in the path
        depth = root[len(plugin_folder):].count(os.sep)

        # We are only interested in directories at depth 1 (directories within the plugin folder)
        if depth == 0:
            for file in files:
                if file.endswith(".py"):
                    # Use importlib to import the py file
                    py_mod_name = os.path.basename(file)[:-3]
                    py_mod_spec = importlib.util.spec_from_file_location(py_mod_name, f"{plugin_folder}/{file}")
                    py_mod = importlib.util.module_from_spec(py_mod_spec)
                    py_mod_spec.loader.exec_module(py_mod)

                    res = py_mod.get_supported_cores()
                    callback(res)

def register_cores():
    def callback(res):
        for kconf_name, core_name, core_support in res:
            print(f"INFO: Registering core {core_name}")
            assert(kconf_name not in kconfig_to_core_name)
            assert(core_name not in supported_cores)
            kconfig_to_core_name[kconf_name] = core_name
            supported_cores[core_name] = core_support
    _collect_available_cores(callback)

def read_file_lines(filename):
    lines = []
    with open(filename, 'r') as file:
        for line in file:
            # Strip newline character and truncate whitespace from both ends
            line = line.strip()
            # Skip empty lines
            if line:
                lines.append(line)
    return lines

def get_core_support(core) -> CoreSupport:
    if not core in supported_cores:
        error.exit_error(f"No CoreSupport instance found for '{core}'", error.SCAIEV_BASE)
    return supported_cores[core]

def copy_folder_contents(core_support, source_folder, target_folder):
    # Blacklist to avoid unnecessary files or simply broken symlinks due to non recursive clones
    blacklist = core_support.copy_blacklist()

    # Iterate over the contents of the source folder
    for item in os.listdir(source_folder):
        source_item = os.path.join(source_folder, item)
        target_item = os.path.join(target_folder, item)

        if source_item in blacklist:
            continue

        # Copy file or directory to the target folder
        if os.path.isfile(source_item):
            shutil.copy(source_item, target_folder)
        elif os.path.isdir(source_item):
            shutil.copytree(source_item, target_item, dirs_exist_ok=True, ignore_dangling_symlinks=True)

def run_scaiev(core, isax_descs, out_dir, kconf_syms):
    print(f"Invoking SCAIEV:")
    # create build and tool directory
    target_dir = os.path.abspath(f"{out_dir}/{core}")
    os.makedirs(target_dir, exist_ok=True)

    isax_descs = [os.path.abspath(isax_desc) for isax_desc in isax_descs]
    isax_dirs = [os.path.dirname(isax_desc) for isax_desc in isax_descs]
    assert(all((isax_dir == isax_dirs[0] for isax_dir in isax_dirs)))

    num_ctxs = kconf_syms['SCV_INTERNAL_CONTEXTS_AMOUNT'].str_value
    num_ctxs = num_ctxs if num_ctxs else 1

    # set scaie-v parameters
    scv_args = [
        f"-c {core}",
        *[f"-i {isax_desc}" for isax_desc in isax_descs],
        f"-o {os.path.abspath(out_dir)}",
        f"-ctx {num_ctxs}"
    ]

    # read config and add scaie-v parameters
    if kconf_syms[f"SCV_DISABLE_DECOUPLED_HAZARD_HANDLING"].str_value == "y":
        scv_args.append("-decoupled_without_DH")
    if kconf_syms[f"SCV_DISABLE_DECOUPLED_INPUT_FIFO"].str_value == "y":
        scv_args.append("-decoupled_without_input_fifo")
    if kconf_syms[f"SCV_DISABLE_DECOUPLED_ISAXKILL"].str_value == "y":
        scv_args.append("-decoupled_disable_disaxkill")
    if kconf_syms[f"SCV_DISABLE_DECOUPLED_ISAXFENCE"].str_value == "y":
        scv_args.append("-decoupled_disable_disaxfence")
    if kconf_syms[f"SCV_RT_LIFE_SUPPORT"].str_value == "y":
        scv_args.append("-rt_life_support")
    
    core_support = get_core_support(core)

    # Copy the unchanged core source file to our target directory
    copy_folder_contents(core_support, f"deps/scaie-v/CoresSrc/{core_support.get_srcs_folder_name()}", target_dir)

    if (core_support.has_isax_support()):
        run_cmd.run("deps/scaie-v/", f"java -enableassertions -jar ./target/SCAIEV-0.0.1-SNAPSHOT-jar-with-dependencies.jar {' '.join(scv_args)}", "SCAIEV failed", error.SCAIEV_BASE + 2)
    else:
        print(f"WARNING: {core} has no SCAIE-V support! There won't be ISAXes!")
        # Create an empty scaiev_netlist.yaml file and see what happens
        scaiev_netlist = os.path.join(target_dir, "scaiev_netlist.yaml")
        with open(scaiev_netlist, 'w'):
            pass
        # Create an empty CommonLogicModule.sv file and see what happens
        scal_sv = os.path.join(target_dir, "CommonLogicModule.sv")
        with open(scal_sv, 'w') as scal:
            scal.write("""
module SCAL (
    input clk_i,
    input rst_i
);
endmodule
""")

    print(f" - Creating wrapper module")
    run_cmd.run("deps/scaie-v/util/maketop", f"python3 {core_support.get_maketop()} {target_dir} {isax_dirs[0]}", "Could not generate top module", error.SCAIEV_BASE + 3)
    print(f" - Building the extended core")

    # Perform extra build steps that are required for the target core!
    core_support.run_extra_build_steps(target_dir, kconf_syms)

def select_tb_wrapper_srcs(core, out_dir):
    core_dir = os.path.join(out_dir, core)
    scal_sources = [ "CommonLogicModule.sv" ] #TODO can this also be CommonLogicModule.v?
    core_support = get_core_support(core)
    return core_support.get_core_srcs(scal_sources, core_dir)

def get_env_value(env_vars, key):
    prefix = f"{key}="
    for entry in env_vars:
        if entry.startswith(prefix):
            return entry[len(prefix):]
    error.exit_error(f"Setup renode: scaiev.select_tb_env_vars variable '{key}' not found", error.INTERNAL_ERROR)

def get_known_cores():
    return list(supported_cores.keys())

def select_tb_env_vars(core):
    assert(core in get_known_cores())
    return get_core_support(core).get_tb_env_vars()

def build_scaiev(kconf_syms):
    if kconf_syms["SCAIEV_DO_NOT_REBUILD"].str_value == "y" and os.path.isfile("./deps/scaie-v/target/SCAIEV-0.0.1-SNAPSHOT.jar"):
        return
    # build scaiev
    print("Building SCAIE-V...")
    run_cmd.run("deps/scaie-v/", "mvn package", "Could not build SCAIE-V", error.SCAIEV_BASE + 1)

# Selects the core
def select_core(kconfig_core):
    if len(kconfig_core) != 1:
        error.exit_error(f"No or more than one core selected in Kconfig: {kconfig_core}", error.USER_ERROR)
    kconfig_core = kconfig_core[0]

    if kconfig_core in kconfig_to_core_name:
        core_name = kconfig_to_core_name[kconfig_core]
        assert(core_name in supported_cores)
        return core_name
    else:
        error.exit_error(f"No datasheet for selected core '{kconfig_core}' found!", error.INTERNAL_ERROR)
