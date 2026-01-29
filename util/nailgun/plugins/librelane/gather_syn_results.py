#!/usr/bin/env python
import os
import json
import sys
import re
from collections import defaultdict

# Function to check if a value is numeric and positive
def is_positive_numeric(value):
    try:
        return float(value) >= 0
    except ValueError:
        return False

# Function to process all runs in a directory
def process_runs(run_dir, project_name, algorithm, clock_period, utilization):
    run_data_list = []

    if not os.path.exists(run_dir) or not os.path.isdir(run_dir):
        return run_data_list

    # Get the timestamp-based directories within 'runs' directory
    timestamp_dirs = [d for d in os.listdir(run_dir) if os.path.isdir(os.path.join(run_dir, d))]

    for timestamp_dir in timestamp_dirs:
        run_data = {}
        timestamp_dir_path = os.path.join(run_dir, timestamp_dir)

        run_data['project_name'] = project_name
        run_data['clock_period'] = clock_period
        run_data['utilization'] = utilization
        run_data['algorithm'] = algorithm
        run_data['run_dnf'] = False
        run_data['run_failed'] = True
        run_data['timing_failed'] = True
        run_data['drc_failed'] = True

        # Check if the 'final' folder exists within the timestamp-based directory
        if not os.path.exists(os.path.join(timestamp_dir_path, 'final')):
            run_data['run_dnf'] = True
            run_data_list.append(run_data)
            continue

        metrics_file = os.path.join(timestamp_dir_path, 'final', 'metrics.json')

        # Check if 'metrics.json' exists
        if not os.path.exists(metrics_file):
            run_data['run_dnf'] = True
            run_data_list.append(run_data)
            continue

        with open(metrics_file, 'r') as f:
            metrics = json.load(f)

        # Check values and set flags
        run_data['run_failed'] = (
            metrics.get('design__lint_errors__count', 0) != 0 or
            metrics.get('synthesis__check_error__count', 0) != 0 or
            metrics.get('design__violations', 0) != 0
        )

        run_data['timing_failed'] = (
            metrics.get('timing__hold_vio__count', 0) != 0 or
            metrics.get('timing__hold_r2r_vio__count', 0) != 0 or
            metrics.get('timing__setup_vio__count', 0) != 0 or
            metrics.get('timing__setup_r2r_vio__count', 0) != 0 or
            not is_positive_numeric(metrics.get('timing__hold__ws', 0)) or
            not is_positive_numeric(metrics.get('timing__setup__ws', 0)) or
            not is_positive_numeric(metrics.get('timing__hold__tns', 0)) or
            not is_positive_numeric(metrics.get('timing__setup__tns', 0)) or
            not is_positive_numeric(metrics.get('timing__hold__wns', 0)) or
            not is_positive_numeric(metrics.get('timing__setup__wns', 0))
        )

        run_data['drc_failed'] = (
            metrics.get('design__max_slew_violation__count') != 0 or
            metrics.get('design__max_fanout_violation__count') != 0 or
            metrics.get('design__max_cap_violation__count') != 0
        )

        # Extract and store additional values
        run_data['design_die_bbox'] = metrics.get('design__die__bbox')
        run_data['design_core_bbox'] = metrics.get('design__core__bbox')
        run_data['design_die_area'] = metrics.get('design__die__area')
        run_data['design_core_area'] = metrics.get('design__core__area')
        run_data['design_stdcell_area'] = metrics.get('design__instance__area__stdcell')
        run_data['design_instance_utilization'] = metrics.get('design__instance__utilization')

        # Store requested timing metrics, counts, and violations
        run_data['design_max_slew_violation_count'] = metrics.get('design__max_slew_violation__count')
        run_data['design_max_fanout_violation_count'] = metrics.get('design__max_fanout_violation__count')
        run_data['design_max_cap_violation_count'] = metrics.get('design__max_cap_violation__count')
        run_data['timing_hold_vio_count'] = metrics.get('timing__hold_vio__count')
        run_data['timing_hold_r2r_vio_count'] = metrics.get('timing__hold_r2r_vio__count')
        run_data['timing_setup_vio_count'] = metrics.get('timing__setup_vio__count')
        run_data['timing_setup_r2r_vio_count'] = metrics.get('timing__setup_r2r_vio__count')
        run_data['timing_hold_ws'] = metrics.get('timing__hold__ws')
        run_data['timing_setup_ws'] = metrics.get('timing__setup__ws')
        run_data['timing_hold_tns'] = metrics.get('timing__hold__tns')
        run_data['timing_setup_tns'] = metrics.get('timing__setup__tns')
        run_data['timing_hold_wns'] = metrics.get('timing__hold__wns')
        run_data['timing_setup_wns'] = metrics.get('timing__setup__wns')

        run_data_list.append(run_data)

    return run_data_list

def gather_syn_results(syn_dir, additional_dirs = []):
    # Collect all process_runs results in one list
    all_run_data = []

    # Loop through subdirectories using regex to split the directory name
    for subdirectory in os.listdir(syn_dir) + additional_dirs:
        match = re.match(r'(.+?)_(LEGACY|MS|PAMS|RAMS|PARAMS|MI_MS|MI_PAMS|MI_RAMS|MI_PARAMS)_([\d.]+)ns_([\d.]+)%', subdirectory)

        runs_dir = os.path.join(syn_dir, subdirectory, 'runs')
        if os.path.exists(runs_dir):
            if match:
                project_name, algorithm, clock_period, utilization = match.groups()
                run_data_list = process_runs(runs_dir, project_name, algorithm, float(clock_period), float(utilization))
                all_run_data.extend(run_data_list)
            else:
                print(f"Folder does not match expected pattern: '{subdirectory}', using dummy values")
                algorithm = "Unknown"
                project_name = "Unknown Project"
                clock_period = -1
                utilization = -1
                run_data_list = process_runs(runs_dir, project_name, algorithm, float(clock_period), float(utilization))
                all_run_data.extend(run_data_list)
        else:
            print(f"{subdirectory} does not contain runs folder {runs_dir}, ignoring")

    # Group the results based on project_name, clock_period, and utilization
    grouped_data = defaultdict(lambda: defaultdict(lambda: defaultdict(lambda: defaultdict(list))))

    for run_data in all_run_data:
        project_name = run_data['project_name']
        algorithm = run_data['algorithm']
        clock_period = run_data['clock_period']
        utilization = run_data['utilization']
        grouped_data[project_name][algorithm][clock_period][utilization].append(run_data)
    return grouped_data

if __name__ == "__main__":
    # Check if the runs directory is provided as a command-line argument
    if len(sys.argv) < 2:
        print("Usage: python script.py /path/to/runs_directory <prj_name_filter>")
        sys.exit(1)

    prj_name_filter = sys.argv[2] if len(sys.argv) > 2 else None

    # Directory containing the runs
    runs_dir = sys.argv[1]
    grouped_data = gather_syn_results(runs_dir, ["."])
    # Now you have grouped_data with the results organized by project_name, clock_period, and utilization
    # You can access the data as needed
    best_results = []
    for project_name, algo_data in grouped_data.items():
        if prj_name_filter is None or prj_name_filter in project_name:
            for algorithm, clock_period_data in algo_data.items():
                best_util = None
                best_period = None
                best_run_data = None
                for clock_period, utilization_data in clock_period_data.items():
                    for utilization, run_data_list in utilization_data.items():
                        for run_data in run_data_list:
                            if not run_data['run_dnf'] and not run_data['run_failed']:
                                print(f"Project: {project_name}, Algorithm: {algorithm}, Clock Period: {clock_period}ns, Utilization: {utilization} %")
                                print(f"DRC Failed: {run_data['drc_failed']}")
                                if run_data['drc_failed']:
                                    print(f"  Slew violations   : #{run_data['design_max_slew_violation_count']}")
                                    print(f"  Fanout violations : #{run_data['design_max_fanout_violation_count']}")
                                    print(f"  Cap violations    : #{run_data['design_max_cap_violation_count']}")
                                print(f"Timing Failed: {run_data['timing_failed']}")
                                if run_data['timing_failed']:
                                    print(f"  timing_hold_tns   : {run_data['timing_hold_tns']} ns")
                                    print(f"  timing_setup_tns  : {run_data['timing_setup_tns']} ns")
                                    print(f"  timing_hold_wns   : {run_data['timing_hold_wns']} ns")
                                    print(f"  timing_setup_wns  : {run_data['timing_setup_wns']} ns")
                                print(f"Die     Area: {run_data['design_die_area']} µm²")
                                print(f"Core    Area: {run_data['design_core_area']} µm²")
                                print(f"Stdcell Area: {run_data['design_stdcell_area']} µm²")
                                if not run_data['timing_failed'] and (best_run_data is None or best_period > clock_period or (best_period == clock_period and best_util < utilization)):
                                    best_util = utilization
                                    best_period = clock_period
                                    best_run_data = run_data
                if best_run_data is not None:
                    best_results.append((project_name, algorithm, best_util, best_period, best_run_data))
    print(f"\n\n\n")
    for prj, algo, util, period, run_data in best_results:
        assert(not run_data['timing_failed'])
        print(f"Best result for {prj} ALGO={algo} Util={util}% Period={period}ns Area={run_data['design_die_area']} µm²")
