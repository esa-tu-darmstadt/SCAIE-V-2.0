#!/usr/bin/env python
import concurrent.futures
import subprocess
import sys
import os
import glob

from gather_syn_results import gather_syn_results  # Import the gather_syn_results function
from run_librelane import run_librelane

def run_synth_jobs(jobs):
    if len(jobs) == 0:
        return []

    # Use the maximum number of threads available on the system
    num_threads = min(len(jobs), os.cpu_count())

    results = []
    # Create a ThreadPoolExecutor with the desired number of threads
    with concurrent.futures.ThreadPoolExecutor(max_workers=num_threads) as executor:
        # Submit tasks to the executor and store the Future objects
        futures = [executor.submit(run_librelane, dir_name, template, args, log_file) for dir_name, template, args, log_file in jobs]

        # Wait for all jobs to complete
        for future in concurrent.futures.as_completed(futures):
            result = future.result()
            results.append(result)
    return results

def get_config_values(design_name, clk_name, clock_period, util, defines, include_dirs):
    syn_values = {
        "{{DESIGN_NAME}}": design_name,
        "{{CLK_NAME}}": clk_name,
        "{{CLK_PERIOD}}": clock_period,
        "{{FP_UTIL}}": util,
        "{{VERILOG_DEFINES}}": defines,
        "{{VERILOG_INCLUDE_DIRS}}": include_dirs,
    }
    return syn_values

def get_ol_config_src_entries(src_files, syn_dir):
    return [f"dir::{os.path.relpath(f, syn_dir)}" for f in src_files]

def test_freq(src_files, syn_dir, design_name, algorithm, project_name, clock_period, initial_utilization, clk_name, config_template, defines, include_dirs):
    step = 5
    end_util = 80
    found_solution = False
    last_working_util = None

    log_dir = os.path.abspath(os.path.join(syn_dir, "logs"))
    os.makedirs(log_dir, exist_ok = True)

    # Define placeholders and their replacement values
    syn_values = get_config_values(design_name, clk_name, clock_period, initial_utilization, defines, include_dirs)

    def create_args_and_log_name(util):
        # Construct the LibreLane run command
        args = syn_values.copy()
        args["{{FP_CORE_UTIL}}"] = util
        dir_name = os.path.join(syn_dir, f"{project_name}_{algorithm}_{clock_period}ns_{util}%")
        log_file = f"{log_dir}/librelane_run_{project_name}_{algorithm}_{clock_period}ns_{util}%.log"
        # Setup syn dir
        os.makedirs(dir_name, exist_ok = True)
        args['{{SRC_FILES}}'] = get_ol_config_src_entries(src_files, dir_name)
        return dir_name, config_template, args, log_file

    jobs = []
    util = initial_utilization
    while util < end_util + 1:
        jobs.append(create_args_and_log_name(util))
        util += step

    print(f"Running all synthesis jobs for CLK_PERIOD={clock_period}ns")
    run_synth_jobs(jobs)

    # Call gather_syn_results to check the run status
    grouped_data = gather_syn_results(syn_dir)  # Replace with the actual path
    results = grouped_data[project_name][algorithm][clock_period]
    jobs = []
    util = initial_utilization

    grouped_data = gather_syn_results(syn_dir)  # Replace with the actual path
    results = grouped_data[project_name][algorithm][clock_period]
    util = initial_utilization
    while util < end_util + 1:
        run_data = results[util]
        if (not run_data or len(run_data) == 0 or run_data[0]['run_dnf'] or run_data[0]['run_failed']):
            util += step
            continue

        # Check if the run succeeded
        # TODO drc_failed?
        if not any(run['run_dnf'] or run['run_failed'] or run['timing_failed'] for run in run_data):
            print(f"Run succeeded with parameters: CLK_PERIOD={clock_period}ns and FP_UTIL={util}%!")
            found_solution = True
            last_working_util = util

        if not run_data[0]['run_dnf'] and not run_data[0]['run_failed'] and run_data[0]['timing_failed']:
            print(f"Util={util} Failed due to timing violations")
        util += step

    return found_solution, last_working_util

def run_dse(src_files, syn_dir, project_name, algorithm, initial_clock_period, initial_utilization, design_name, clk_name, config_template, defines, include_dirs):
    clock_period = initial_clock_period
    found_solution = False
    last_working_clk_period = None
    last_working_util = None
    found_working = False

    while True:
        _found_solution, _last_working_util = test_freq(src_files, syn_dir, design_name, algorithm, project_name, clock_period, initial_utilization, clk_name, config_template, defines, include_dirs)

        if _found_solution:
            found_solution = True
            found_working = True
            last_working_clk_period = clock_period
            last_working_util = _last_working_util

        if last_working_clk_period is not None and not found_working:
            print(f"Max frequency was already found with CLK_PERIOD={last_working_clk_period}ns and FP_UTIL={last_working_util}%")
            break
        # If utilization reaches 80% and the run still fails, decrease the clock frequency by 5MHz
        clock_freq_mhz = 1000.0 / clock_period
        next_freq = clock_freq_mhz - 5 if not found_solution else clock_freq_mhz + 1
        clock_period = 1000.0 / next_freq
        found_working = False
        if next_freq < 5:
            print("Aborting. Minimum clock frequency reached.")
            sys.exit(1)

if __name__ == "__main__":
    if len(sys.argv) < 8:
        print("Usage: ./dse.py src_dir syn_dir project_name algorithm initial_clock_period initial_utilization config_template <design_name> <clk_name>")
        sys.exit(1)

    src_dir = sys.argv[1]
    syn_dir = sys.argv[2]
    project_name = sys.argv[3]
    algorithm = sys.argv[4]
    initial_clock_period = float(sys.argv[5])
    initial_utilization = float(sys.argv[6])
    config_template = sys.argv[7]
    design_name = sys.argv[8] if len(sys.argv) >= 9 else 'top'
    clk_name = sys.argv[9] if len(sys.argv) >= 10 else 'clk'

    src_files = glob.glob(os.path.join(src_dir, "*.sv"))
    src_files += glob.glob(os.path.join(src_dir, "*.v"))
    run_dse(src_files, syn_dir, project_name, algorithm, initial_clock_period, initial_utilization, design_name, clk_name, config_template, [], [])
