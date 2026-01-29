import yaml
from typing import Callable

def _sched_is_decoupled(sched: dict):
    """Given an ISAX yaml schedule entry, returns whether it is considered decoupled."""
    return "is decoupled" in sched or "is dynamic decoupled" in sched

def yaml_instr_filter_decoupled_writeback(instr: dict):
    """Given an instruction yaml entry, returns True iff the instruction has a decoupled WrRD."""
    if 'schedule' in instr:
        for sched in instr['schedule']:
            if sched['interface'] == "WrRD" and _sched_is_decoupled(sched):
                return True
    return False

def extract_encodings(isax_yaml_path, cb_filter: Callable[[dict], bool] = None) -> dict[str,str]:
    """ Given an ISAX yaml path and an optional filter callback,
        returns a dict of (instr_name, encoding) for all matching regular instructions.
    """
    isax = dict()
    with open(isax_yaml_path, 'r') as file:
        isax_desc = yaml.safe_load(file)
        if isax_desc:
            for item in isax_desc:
                if 'instruction' in item and (not cb_filter or cb_filter(item)):
                    ins_name = item['instruction']
                    encoding = item['mask']
                    isax[ins_name] = encoding
    return isax

def match_instruction(binary_str, pattern):
    """Match a binary string with a given pattern."""
    min_len = min(len(binary_str), len(pattern))
    for i in range(min_len):
        if pattern[i] != '-' and binary_str[i] != pattern[i]:
            return False
    return True
