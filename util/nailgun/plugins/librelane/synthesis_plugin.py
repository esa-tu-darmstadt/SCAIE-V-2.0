import os
import sys

# Get the folder where the current script is located
script_folder = os.path.dirname(os.path.abspath(__file__))
# Add the script folder to sys.path so we can import modules from this directory
sys.path.append(script_folder)

import librelane_plugin

# Remove the script folder from the path again
sys.path.remove(script_folder)

def main(vars):
    out_dir = vars['out_dir']
    scaiev_core_name = vars['scaiev_core_name']
    kconf = vars['kconf']
    isax_name = vars['isax_name']
    syn_dir_suffix = vars['syn_dir_suffix']
    return librelane_plugin.run_synthesis(out_dir, scaiev_core_name, kconf.syms, isax_name, syn_dir_suffix)
