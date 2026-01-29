import os
import subprocess

from tools import replace_placeholders

def run_librelane(out_dir, config_template, replace_values, log_file = None, pdk_path = "~/.volare"):
    out_config = os.path.join(out_dir, "config.json")
    replace_placeholders.replace_placeholders(config_template, out_config, replace_values)
    opt_args = f"--manual-pdk --pdk-root '{os.path.expanduser(pdk_path)}'" if pdk_path else ""
    cmd = f"librelane {opt_args} config.json"
    exec_cmd = f"cd {out_dir} && {cmd}"
    if log_file:
        with open(log_file, "w") as log:
            subprocess.run(exec_cmd, shell=True, stdout=log, stderr=subprocess.STDOUT)
    else:
        subprocess.run(exec_cmd, shell=True)
