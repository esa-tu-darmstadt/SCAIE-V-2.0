#!/usr/bin/env python3
import kconfiglib
import os
import sys
import hashlib
import shutil

def calculate_checksum(file_path, algorithm='sha256'):
    """
    Calculate the checksum of a file using the specified algorithm.
    
    :param file_path: Path to the file
    :param algorithm: Hash algorithm to use (default: sha256)
    :return: Hex digest of the file
    """
    hash_func = hashlib.new(algorithm)
    with open(file_path, 'rb') as f:
        for chunk in iter(lambda: f.read(4096), b""):
            hash_func.update(chunk)
    return hash_func.hexdigest()

def deduplicate_files(directory):
    """
    Deduplicate files in a directory based on their checksums.
    
    :param directory: Path to the directory to process
    """
    seen_files = {}
    deduplicate_mapping = {}
    for root, _, files in os.walk(directory):
        for file in files:
            if file.startswith("config_"):
                file_path = os.path.join(root, file)
                file_checksum = calculate_checksum(file_path)

                if ".old" in file:
                    os.remove(file_path)
                elif file_checksum in seen_files:
                    # If checksum is already seen, delete the file
                    # print(f"Duplicate found: {file_path} (matches {seen_files[file_checksum]})")
                    os.remove(file_path)
                    deduplicate_mapping[file] = seen_files[file_checksum]
                else:
                    # Otherwise, store the checksum and file path
                    seen_files[file_checksum] = file
    return deduplicate_mapping


def write_config_for_choice(kconf, choice, choice_value, out_dir):
    """Set a choice to a specific value and write out the resulting .config"""
    # Set the choice to the selected value
    kconf.syms[choice_value.name].set_value("y")

    # Set default values for all other choices
    for c in kconf.unique_choices:
        if c is not choice:
            assert len(c.defaults) == 1
            default_val, cond = c.defaults[0]
            # for sym in c.syms:
            #     kconf.syms[sym.name].set_value("n")
            kconf.syms[default_val.name].set_value("y")

    # Write the configuration to a .config file
    config_filename = os.path.join(out_dir, f"config_{choice_value.name}")
    with open(config_filename, "w") as f:
        kconf.write_config(f.name)
    #print(f"Wrote {config_filename}")

def gen_solution_selections(kconfig_filename, out_dir):
    os.makedirs(out_dir, exist_ok = True)
    # Load the Kconfig file
    kconf = kconfiglib.Kconfig(kconfig_filename)

    # Iterate over all the choice blocks
    for choice in kconf.unique_choices:
        # Iterate over each option in the choice block
        for sym in choice.syms:
            # Generate and write out the .config for each choice
            write_config_for_choice(kconf, choice, sym, out_dir)
    
    # Deduplicate generated config files
    return deduplicate_files(out_dir)

if __name__ == "__main__":
    kconfig_filename = sys.argv[1]  # Replace with the path to your Kconfig file
    out_dir = sys.argv[2]
    gen_solution_selections(kconfig_filename, out_dir)
