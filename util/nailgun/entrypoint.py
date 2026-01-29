#!/usr/bin/env python3

import os
import shutil

import error
import treenail
import kconfig

# generate mapping from ISAX config name to isax description file
def gen_isax_map():
    isax_map = {}
    for root, _, files in os.walk(".", topdown=False):
        for name in files:
            if name == "paths.csv":
                csvpath = os.path.join(root, name)
                try:
                    csvfile = open(csvpath)
                    current_map = { line.split(";")[0]:line.split(";")[1].replace("\n","") for line in csvfile}
                    isax_map = {**isax_map, **current_map}
                except:
                    error.exit_error(f"could not parse ISAX path.csv file found in: {root}", error.INTERNAL_ERROR)
    return isax_map

def get_enabled_isaxes(kconf_syms):
    # get list of enabled ISAXes
    enabled_isaxes = kconfig.extract_kconfig_enabled(kconfig.extract_enabled_isax_from_config(kconf_syms))

    # get mapping from config option to mlir files
    isax_file_mapping = gen_isax_map()

    # map enabled isaxes to LN mlir input files
    isax_input_files = None
    try:
        isax_input_files = list(map(lambda x: isax_file_mapping[x], enabled_isaxes))
    except KeyError as ke:
        error.exit_error(f"Could not find .core_desc file for {str(ke)}. Check your paths.csv.", error.INTERNAL_ERROR)
    return enabled_isaxes, isax_input_files

def resolve_mlir_paths(scaiev_core_name, out_dir, kconf_syms):
    mlir_paths = None
    isax_yamls = []
    if kconf_syms["DEFAULT_ENTRY_POINT"].str_value == "y":
        # print enabled ISAXes
        print(f"Building {scaiev_core_name} with ISAXes:")
        enabled_isaxes, isax_input_files = get_enabled_isaxes(kconf_syms)
        for isax, mlir in zip(enabled_isaxes, isax_input_files):
            print(f" - {isax[len('ISAX_'):-len('_EN')]} (associated description: {mlir})")

        if len(enabled_isaxes) == 0:
            error.exit_error("No ISAXes were selected, nothing to do!", error.USER_ERROR)
        # TN CoreDSL to MLIR
        treenail.build_treenail()
        mlir_paths = treenail.run_treenail_batch(enabled_isaxes, isax_input_files, out_dir)
    elif kconf_syms["MLIR_ENTRY_POINT"].str_value == "y":
        # use the MLIR entry point path
        path = kconf_syms["MLIR_ENTRY_POINT_PATH"].str_value
        if not os.path.exists(path):
            error.exit_error(f"Could not find mlir file '{mlir_paths[0]}'. Please check your MLIR entry point path settings!", error.USER_ERROR)
        mlir_paths = [ os.path.abspath(path) ]
    elif kconf_syms["SV_ENTRY_POINT"].str_value == "y":
        for yaml_path_in in kconf_syms["SV_ENTRY_POINT_ISAX_YAML_PATH"].str_value.split(';'):
            if not os.path.exists(yaml_path_in):
                error.exit_error(f"Could not find ISAX YAML file '{yaml_path_in}'. Please check your SV entry point path settings!", error.USER_ERROR)
            # Copy ISAX YAML file to the output folder
            yaml_path_out = os.path.join(out_dir, os.path.basename(yaml_path_in))
            shutil.copy(yaml_path_in, yaml_path_out)
            isax_yamls.append(yaml_path_out)

        for sv_path_in in kconf_syms["SV_ENTRY_POINT_PATH"].str_value.split(';'):
            if not os.path.exists(sv_path_in):
                error.exit_error(f"Could not find ISAX SV file '{sv_path_in}'. Please check your SV entry point path settings!", error.USER_ERROR)
            # Copy ISAX SV file to the output folder
            sv_path_out = os.path.join(out_dir, os.path.basename(sv_path_in))
            shutil.copy(sv_path_in, sv_path_out)
    else:
        assert kconf_syms["NO_ISAX_ENTRY_POINT"].str_value == "y"
        # Create an empty ISAX yaml file and see what happens
        isax_yamls = [os.path.join(out_dir, "NO_ISAX.yaml")]
        with open(isax_yamls[0], 'w'):
            pass

    return mlir_paths, isax_yamls
