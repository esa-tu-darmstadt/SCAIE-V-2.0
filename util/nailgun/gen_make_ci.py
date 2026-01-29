#!/usr/bin/env python3
import os
import kconfiglib


if __name__ == "__main__":
    # Read in Kconfig & .config file
    kconf = kconfiglib.Kconfig("Kconfig")
    config_path = os.getenv("CONFIG_PATH") if os.getenv("CONFIG_PATH") else ".config"
    kconf.load_config(config_path)

    kconf_syms = kconf.syms

    env_var_str = ""

    for sym in kconf_syms:
        if kconf_syms[sym].visibility != 0:
            env_var_str += f'{str(sym)}="{kconf_syms[sym].str_value}" '

    env_var_str += "make ci"
    print(env_var_str)
