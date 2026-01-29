#!/usr/bin/env python3
import os

import error
import run_cmd

def run_treenail(isax_tag, coredsl_file, out_dir):
    # create build and tool directory
    mlir_dir = os.path.join(out_dir, "mlir")
    os.makedirs(mlir_dir, exist_ok=True)

    out_path = os.path.abspath(os.path.join(mlir_dir, f"{isax_tag}.mlir"))
    print(f" - Mapping {coredsl_file} to {out_path}")
    run_cmd.run(".", f"./deps/treenail/app/build/install/app/bin/app {coredsl_file} -o {out_path}", f"Treenail failed on {coredsl_file}", error.TN_BASE + 4, False)
    return out_path

def run_treenail_batch(isax_tags, coredsl_files, out_dir):
    print("Running Treenail:")
    mlir_paths = []
    for tag, file in zip(isax_tags, coredsl_files):
        mlir_paths.append(run_treenail(tag, file, out_dir))
    return mlir_paths

def build_treenail():
    # check that gradlew exists
    if not os.path.isfile("deps/treenail/gradlew"):
        error.exit_error("Treenail is not cloned. Check out submodules in this repo!", error.USER_ERROR)

    # build treenail
    if not os.path.isfile("./deps/treenail/app/build/install/app/bin/app"):
        print("Building Treenail...")
        run_cmd.run("deps/treenail", "./gradlew build", "Could not build treenail", error.TN_BASE + 1)
        run_cmd.run("deps/treenail", "./gradlew test", "Treenail failed tests", error.TN_BASE + 2)
        run_cmd.run("deps/treenail", "./gradlew install", "Treenail could not be installed", error.TN_BASE + 3)
