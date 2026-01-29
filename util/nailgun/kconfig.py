#!/usr/bin/env python3

# discard all read kconfig entries which are not boolean
# returns list of enabled booleans
def extract_kconfig_enabled(kconfig_dict):
    return [k for k, v in kconfig_dict.items() if v == "y"]

# filter kconfig dictionary for config options related to loading MLIR files
def extract_enabled_isax_from_config(kconfig_syms):
    return {str(sym): kconfig_syms[sym].str_value for sym in kconfig_syms if str(sym).startswith("ISAX_") and str(sym).endswith("_EN")}

def extract_core_from_config(kconfig_syms):
    return {str(sym): kconfig_syms[sym].str_value for sym in kconfig_syms if str(sym).startswith("CORE_")}
