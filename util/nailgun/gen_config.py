#!/usr/bin/env python3
import os

import scaiev

def get_isaxes(directory):
    isaxes = []
    for root, _, files in os.walk(directory):
        for name in files:
            if name.endswith('.core_desc'):
                isaxes.append(os.path.join(root, name))
    return isaxes

def gen_isax_selection_menu():
    isaxes = get_isaxes("isaxes")

    # Extract the filename without the file extension
    isax_names = map(lambda isax: os.path.splitext(
        os.path.basename(isax))[0], isaxes)

    kconfig_outdir = "build/ISAX"
    os.makedirs(kconfig_outdir, exist_ok=True)

    with open(os.path.join(kconfig_outdir, "Kconfig"), 'w') as file:
        if len(isaxes) > 0:
            file.write(f"""
menu "Select ISAXes"

depends on DEFAULT_ENTRY_POINT

source "{kconfig_outdir}/*/Kconfig"

endmenu
""")

    # Generate KConfig files
    for name, path in zip(isax_names, isaxes):
        kconfig_dir = os.path.join(kconfig_outdir, name)
        os.makedirs(kconfig_dir, exist_ok=True)

        # Generate Kconfig file for the isax
        kconfig_file = os.path.join(kconfig_dir, "Kconfig")
        with open(kconfig_file, 'w') as file:
            file.write(f"""
config ISAX_{name.upper()}_EN
    bool "{name} ISAX"
    help
        enable {name} ISAX
""")
        # Generate paths.csv file for the isax
        path_csv_file = os.path.join(kconfig_dir, "paths.csv")
        with open(path_csv_file, 'w') as file:
            file.write(f"ISAX_{name.upper()}_EN;{path}\n")

def gen_core_selection_menu():
    results = []
    def callback(res):
        results.extend(res)
    scaiev._collect_available_cores(callback)


    kconfig_outdir = "build"
    os.makedirs(kconfig_outdir, exist_ok=True)
    kconfig_file = os.path.join(kconfig_outdir, "cores_Kconfig")

    with open(kconfig_file, 'w') as file:
        file.write("""
choice
	prompt "RISC-V core"
	default CORE_CVA5
""")
        for kconf_name, core_name, _ in results:
            file.write(f"""
config {kconf_name}
    bool "{core_name}"
""")

        file.write("""

menu "Core settings"
	depends on CORE_VEX_4S || CORE_VEX_5S || CORE_NAX
	config SPINAL_GEN_ARGS
		string "Additional SpinalHDL generation arguments"
		default ""
endmenu
""")

        file.write("endchoice\n")


if __name__ == "__main__":
    gen_isax_selection_menu()
    gen_core_selection_menu()
