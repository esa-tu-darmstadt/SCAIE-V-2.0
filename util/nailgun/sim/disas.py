from capstone import *
from isax_yaml_tools import *

# Define the patterns for each instruction
isax_patterns = dict()
diassembler = Cs(CS_ARCH_RISCV, CS_MODE_32)

def register_isax_yaml(yaml_file):
    global isax_patterns
    # Merge the dicts
    isax_patterns = isax_patterns | extract_encodings(yaml_file)

def register_isax_patterns(patterns):
    global isax_patterns
    # Merge the dicts
    isax_patterns = isax_patterns | patterns

def byte_array_to_binary(byte_array):
    """Convert a byte array to a binary string."""
    return ''.join(format(byte, '08b') for byte in byte_array)

def find_matching_isax(byte_array):
    """Match a byte array to a corresponding instruction."""
    binary_str = byte_array_to_binary(byte_array)
    
    # Check against all patterns
    for instruction, pattern in isax_patterns.items():
        if match_instruction(binary_str, pattern):
            return instruction

    return None

def disassemble(instr_bytes):
    # Reverse the bytes using slicing
    isax_name = find_matching_isax(instr_bytes[::-1])
    if isax_name:
        return [isax_name]
    assembly = "Unknown Instr"
    disases = []
    for i in diassembler.disasm(instr_bytes, 0x0):
        disases.append(f"{i.mnemonic} {i.op_str}")

    if len(disases) > 0:
        return disases

    return [assembly]
