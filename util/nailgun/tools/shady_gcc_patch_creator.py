#!/usr/bin/env python

import sys
import pathlib
import os

from isax_yaml_tools import *

if len(sys.argv) != 3:
    print("Usage: ./shady_gcc_patch_creator.py <ISAX.yaml> <patched_files_output_dir>")
    sys.exit(1)

file_names = sys.argv[1].split(';')
isax = dict()
for file_name in file_names:
    isax.update(extract_encodings(file_name))

# Handle placeholders for immediate values by enumerating all possible assignments
expanded_isax = dict()
def enumerate_enc(pref, enc):
    width = enc[:7].count('-')
    if width > 0:
        assert enc[7-width:7].count('-') == width
        possibilities = 2 ** width
        format_str = "{0:0" + str(width) +"b}"
        for i in range(possibilities):
            p = pref + str(i)
            new_enc = enc[:7-width] + format_str.format(i) + enc[7:]
            expanded_isax[p] = new_enc
    else:
        expanded_isax[pref] = enc

for k, v in isax.items():
    enumerate_enc(k, v)

def create_mask_and_match(enc):
    mask = '0b' + enc.replace('0', '1').replace('-', '0')
    value = '0b' + enc.replace('-', '0')
    return value, mask

# gather all required defines
defines = ""
for k, v in expanded_isax.items():
    val, mask = create_mask_and_match(v)
    defines += "#define MASK_{0} ({1})\n".format(k.upper(), mask)
    defines += "#define MATCH_{0} ({1})\n".format(k.upper(), val)

# gather all required assembler definitions
def computeAsmFormat(enc):
    format = []
    # Check for the usage of the rd field
    if '-' in enc[32-7-5:32-7]:
        print(f"rd={enc[32-7-5:32-7]}")
        format.append('d')
    # Check for the usage of the rs1 field
    if '-' in enc[32-7-8-5:32-7-8]:
        print(f"rs1={enc[32-7-8-5:32-7-8]}")
        format.append('s')
    # Check for the usage of the rs2 field
    if '-' in enc[32-7-8-5-5:32-7-8-5]:
        print(f"rs2={enc[32-7-8-5:32-7-8]}")
        format.append('t')
    return ",".join(format)

op_defs = ""
for k, v in expanded_isax.items():
    asmFormat = computeAsmFormat(v)
    op_defs += '{{"{0}", 0, INSN_CLASS_I, "{2}", MATCH_{1}, MASK_{1}, match_opcode, 0 }},\n'.format(k.lower(), k.upper(), asmFormat)

# Please ensure that out_dir points Scenario-HLS-DAC folder
out_dir = sys.argv[2]
pathlib.Path(out_dir).mkdir(parents=True, exist_ok=True)
with open(os.path.join(out_dir, "isax_defs.h"), "wt") as mask_file:
    mask_file.write(defines)

with open(os.path.join(out_dir, "opcode_defs.h"), "wt") as op_def_file:
    op_def_file.write(op_defs)
