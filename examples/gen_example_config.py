#!/usr/bin/env python3
import os
import argparse
import kconfiglib

"""
Generates a Nailgun configuration file for SCAIE-V examples. Outputs to: '.config_examplerun'
"""

if __name__ == "__main__":
    # Read in Kconfig
    kconf = kconfiglib.Kconfig("Kconfig")

    parser = argparse.ArgumentParser(formatter_class=argparse.RawDescriptionHelpFormatter, description=__doc__)
    parser.add_argument("--conf", action='append', help='An initial config for merging (can pass multiple --conf options)', required=False)
    parser.add_argument("--core", help='The core to use (refers to Nailgun Kconfig names)', required=True)
    parser.add_argument("--isaxdir", help='ISAX source directory', required=False)
    parser.add_argument("--isax", action='append', help='ISAX to build with (can pass multiple --isax options)', required=False)
    parser.add_argument("--testprog", help='Test program source file', required=False)
    parser.add_argument("--testprog_expected", help='Test program expected results file', required=False)
    parser.add_argument("--opcodes", help='Custom binutils opcode header directory', required=False)
    args = parser.parse_args()
    
    if args.conf is not None:
        kconf.warn_assign_override = False
        kconf.warn_assign_redun = False
        for conf_file in args.conf:
            print(f"Loading {conf_file}") 
            kconf.load_config(conf_file, replace=False)

    if args.core:
        kconf.syms[f"CORE_{args.core}"].set_value("y")
    if args.isax:
        if not args.isaxdir:
            parser.exit(1, "--isax is given but --isaxdir is missing")
        kconf.syms["SV_ENTRY_POINT"].set_value("y")
        kconf.syms["SV_ENTRY_POINT_PATH"].set_value(";".join((os.path.join(args.isaxdir, f"ISAX_{isax}.sv") for isax in args.isax)))
        kconf.syms["SV_ENTRY_POINT_ISAX_YAML_PATH"].set_value(";".join((os.path.join(args.isaxdir, f"ISAX_{isax}.yaml") for isax in args.isax)))
    if args.testprog:
        kconf.syms["SIM_TB_PATH"].set_value(args.testprog)
    if args.testprog_expected:
        kconf.syms["SIM_TB_EXPECTED_PATH"].set_value(args.testprog_expected)
    if args.opcodes:
        kconf.syms["SIM_GCC_OPCODES_DIR"].set_value(args.opcodes)

    # Write the generated .config file
    kconf.write_config(".config_examplerun")
