#!/usr/bin/env python3
import sys
import kconfiglib
import re

def convert_selection_to_yaml(kconf, yaml_out_path):
    with open(yaml_out_path, "w") as file:
        # Iterate over all solution choices and export them as yaml file
        for choice in kconf.choices:
            # Check the selected choice
            selected_sym = choice.selection
            assert (selected_sym)

            # Define the regex pattern with capture groups
            pattern = r"SG_(\d+)_SOL_IDX_(\d+)"

            # Search for the pattern in the string
            match = re.search(pattern, selected_sym.name)

            # Extract the numbers using the groups
            assert (match)
            sg_id = int(match.group(1))
            sol_idx = int(match.group(2))
            selection_entry = f"""- sharing_group: {sg_id}
  solution_idx: {sol_idx}
"""
            file.write(selection_entry)

if __name__ == "__main__":
    # Read in Kconfig & .config file
    kconf_path = sys.argv[1]
    config_path = sys.argv[2]
    yaml_out_path = sys.argv[3]
    kconf = kconfiglib.Kconfig(kconf_path)
    kconf.load_config(config_path)
    convert_selection_to_yaml(kconf, yaml_out_path)
