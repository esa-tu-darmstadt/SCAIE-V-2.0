# Creates the VexRiscv top module file based on the netlist generated by SCAIE-V.
# For arguments, see maketop.py

from maketop import write_top

def translate_pin_name(name):
    # Identity, since no conversion step is required.
    return name

write_top("Vex_top_template.sv", "Vex_top.sv", translate_pin_name)
