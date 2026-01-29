import os

import run_cmd
import error

def get_picolibc_path():
    return "deps/picolibc"

def picolibc_repo_exists(version):
    picolibc_repo = os.path.abspath(f"{get_picolibc_path()}_{version}")

    # Ensure the picolibc repo is setup
    return os.path.exists(picolibc_repo), picolibc_repo

def prepare_picolibc(version="1.8.10"):
    exists, path = picolibc_repo_exists(version)
    if exists:
        return path

    print("   - Cloning picolibc")
    # Clone picolibc
    run_cmd.run(".", f"git clone --depth=1 -b {version} https://github.com/picolibc/picolibc {path}", f"Failed to clone picolibc {version}", error.PICOLIBC_BASE + 1, False)
    # Patch picolibc
    patch_file = os.path.abspath("patches/picolibc.patch")
    run_cmd.run(path, f"patch -p1 < {patch_file}", "Could not patch the picolibc sources", error.PICOLIBC_BASE + 2, False)

    return path

def get_build_dir_name(cc_name, march, mabi, CTRL_BASE):
    return f"build_{cc_name}_{march}_{mabi}_{CTRL_BASE}"

def compile_picolibc(path, cc_path, ar_path, as_path, nm_path, strip_path, march, mabi, CTRL_BASE):
    cc_name = os.path.basename(cc_path)
    build_dir = os.path.join(path, get_build_dir_name(cc_name, march, mabi, CTRL_BASE))

    if not os.path.exists(build_dir):
        os.makedirs(build_dir)
        build_script = os.path.join(build_dir, "build_picolibc.sh")
        with open(build_script, "w") as f:
            f.write(f"""#!/bin/sh

set -o errexit
set -o nounset
set -o pipefail

meson setup .. \\
    -Dincludedir=include \\
    -Dlibdir=lib \\
    -Dprefix=`pwd`/install \\
    -Dsemihost=false \\
    -Dfake-semihost=true \\
    -Dtinystdio=true \\
    -Dpicocrt=false \\
    -Dtests=false \\
    --cross-file MY_CROSS_CONF.txt \\
    "$@"

ninja
ninja install
""")
        # Make the script executable
        os.chmod(build_script, 0o755)

        cross_conf = os.path.join(build_dir, "MY_CROSS_CONF.txt")
        with open(cross_conf, "w") as f:
            f.write(f"""[binaries]
c = ['{cc_path}', '-march={march}', '-mabi={mabi}', '-mcmodel=medany', '-nostdlib', '-DNAILGUN_UART_BASE=0x{int(CTRL_BASE, 16) + 8:x}']
cpp = ['{cc_path}', '-march={march}', '-mabi={mabi}', '-mcmodel=medany', '-nostdlib', '-DNAILGUN_UART_BASE=0x{int(CTRL_BASE, 16) + 8:x}']

ar = '{ar_path}'
as = '{as_path}'
nm = '{nm_path}'
strip = '{strip_path}'

[host_machine]
system = 'none'
cpu_family = '{"riscv64" if "64" in march else "riscv32"}'
cpu = 'riscv'
endian = 'little'

[properties]
has_link_defsym = true
""")
        print("   - Compiling picolibc")
        run_cmd.run(build_dir, f"./{os.path.basename(build_script)}", f"Failed to compile picolibc", error.PICOLIBC_BASE + 3, False)

    return os.path.join(build_dir, "install")
