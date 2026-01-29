#!/usr/bin/env python3
import os
import kconfiglib
import shutil
import re
import importlib

import entrypoint
import error
import kconfig
import longnail
import scaiev
import simulation

from tools.critical_chains import merge_chains

def execute_plugins(entry_point_name):
    results = []
    plugin_folder = "plugins"
    # Walk through the directory, including subdirectories
    for root, dirs, files in os.walk(plugin_folder):
        # We calculate the depth by checking the number of separators in the path
        depth = root[len(plugin_folder):].count(os.sep)

        # We are only interested in directories at depth 1 (directories within the plugin folder)
        if depth == 1:
            for file in files:
                # Check if the file name matches the entry point
                if file == f"{entry_point_name}.py":
                    # Use the import statement to import the module by its name
                    entrymodule = importlib.import_module(f"{plugin_folder}.{os.path.basename(root)}.{entry_point_name}")
                    print(f"INFO: Running {entry_point_name} from {root}")
                    res = entrymodule.main(globals())
                    if res:
                        results.append(res)
    return results

def create_output_folder(base_path, base_name):
    # Start with the base path
    os.makedirs(base_path, exist_ok=True)
    path = os.path.join(base_path, base_name + "_0")
    suffix = 1

    # Check if the directory exists, and if so, add a suffix
    while os.path.exists(path):
        path = os.path.join(base_path, f"{base_name}_{suffix}")
        suffix += 1

    # Create the directory
    os.makedirs(path)
    return path

def get_output_folder(base_path = "outputs", base_name = "run"):
    out_dir = os.getenv("OUTPUT_PATH")
    if out_dir:
        if not os.path.exists(out_dir):
            error.exit_error(f"Explicitly specified output path {out_dir} does not exist!", error.USER_ERROR)
        out_dir = os.path.abspath(out_dir)
    else:
        out_dir = create_output_folder(base_path, base_name)
    return out_dir

def extract_isax_name(mlir_path):
    if not mlir_path:
        return None
    # Extract the isax name directly from the used mlir file
    # Read the entire file into one string variable
    with open(mlir_path, 'r') as file:
        mlir_text = file.read()
    # Define the regex pattern
    pattern = r'coredsl.isax\s+"(\w+)"\s*\{'
    # Search for the pattern
    match = re.search(pattern, mlir_text)
    # Extract the module name if found
    if match:
        isax_name = match.group(1)
    else:
        error.exit_error("Could not extract the module's ISAX name", error.INTERNAL_ERROR)
    return isax_name

if __name__ == "__main__":
    # Read in Kconfig & .config file
    kconf = kconfiglib.Kconfig("Kconfig")
    config_path = os.getenv("CONFIG_PATH") if os.getenv("CONFIG_PATH") else ".config"
    kconf.load_config(config_path)

    core_name = kconfig.extract_kconfig_enabled(kconfig.extract_core_from_config(kconf.syms))
    scaiev.register_cores()
    scaiev_core_name = scaiev.select_core(core_name)
    core_support = scaiev.get_core_support(scaiev_core_name)

    # Package all results in an output folder
    out_dir = get_output_folder()
    print(f"Output folder: {os.path.relpath(out_dir, '.')}")

    # Copy config to output folder to simplify reproducing it
    shutil.copy(config_path, os.path.join(out_dir, "config"))

    mlir_paths, isax_yamls = entrypoint.resolve_mlir_paths(scaiev_core_name, out_dir, kconf.syms)

    critical_chains = []
    iteration = 0
    while True:
        mlir_path = None
        if mlir_paths:
            # LN mlir to .sv
            longnail.build_longnail(kconf.syms)
            datasheet = longnail.select_core_datasheet(core_support)
            mlir_path = longnail.run_longnail(mlir_paths, datasheet, kconf.syms, out_dir, iteration, critical_chains)
            isax_yamls = [longnail.provide_isax_yaml(out_dir)]

        if kconf.syms["SIM_AWESOME_LLVM_OVERWRITE_ISAX_NAME"].str_value == "y":
            isax_name = kconf.syms["SIM_AWESOME_LLVM_ISAX_NAME"].str_value
        else:
            isax_name = extract_isax_name(mlir_path)

        only_add_cc_support = kconf.syms["ONLY_PATCH_CC"].str_value == "y"

        # SCAIE-V integrate into core
        if not only_add_cc_support:
            scaiev.build_scaiev(kconf.syms)
            scaiev.run_scaiev(scaiev_core_name, isax_yamls, out_dir, kconf.syms)

        # Optionally run the simulation
        simulation.run_simulation(out_dir, scaiev_core_name, kconf.syms, isax_name, mlir_path, only_add_cc_support)

        new_critical_chains = []
        if not only_add_cc_support:
            syn_dir_suffix = f"_{iteration}"
            # Optionally run synthesis plugins
            new_critical_chains = execute_plugins("synthesis_plugin")

        # None or empty
        if not new_critical_chains or len(new_critical_chains) == 0 or len(new_critical_chains[0]) == 0:
            print("Done!")
            break
        else:
            assert(len(new_critical_chains) == 1)
            assert(len(new_critical_chains[0]) > 0)
            print(f"Found critical chains: {new_critical_chains[0]}\n")
            critical_chains = merge_chains(critical_chains, new_critical_chains[0])

        iteration += 1
