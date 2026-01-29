import os
import functools

import error
import run_cmd
import scaiev
import longnail
import picolibc

def get_awesome_path():
    return "deps/awesome_llvm"

def llvm_repo_exists(version):
    llvm_repo = os.path.abspath(f"{get_awesome_path()}/compiler-patcher/build-tests/llvm-project/worktree/{version}")

    # Ensure the llvm repo is setup
    return os.path.exists(llvm_repo), llvm_repo

def llvm_build_dir(llvm_repo):
    return os.path.join(llvm_repo, "build")

def check_clang_exists(version):
    llvm_exists, llvm_repo = llvm_repo_exists(version)

    if not llvm_exists:
        return llvm_exists, None

    llvm_build_path = llvm_build_dir(llvm_repo)
    clang_path = os.path.join(llvm_build_path, "bin", "clang++")
    return os.path.exists(clang_path), clang_path

def prepare_llvm(kconf_syms, mlir_path, version, rebuild, do_not_patch):
    ccache_size = "25G"
    if os.getenv("AWESOME_CCACHE_SIZE"):
        ccache_size = os.getenv("AWESOME_CCACHE_SIZE")

    mlir_path = os.path.abspath(mlir_path) if (mlir_path is not None) else None
    awesome_path = get_awesome_path()
    awesome_ln_bin = longnail.get_longnail_bin(kconf_syms, "AWESOME", os.path.basename(get_awesome_path()))

    # Hard reset the llvm target repository
    llvm_exists, llvm_repo = llvm_repo_exists(version)

    # Ensure the llvm repo is setup
    commit = f"release/{version}.x"
    if not llvm_exists:
        # Clone llvm
        run_cmd.run(".", f"git clone --depth=1 -b {commit} https://github.com/llvm/llvm-project.git {llvm_repo}", f"Failed to clone LLVM {version}", error.AWESOME_BASE + 1, False)
        # Configure cmake
        ccache_path = os.path.abspath(f"{awesome_path}/compiler-patcher/build-tests/llvm-project/ccache")
        targets = [
            ("riscv32-unknown-elf", "-march=rv32i -mabi=ilp32"),
            ("riscv64-unknown-elf", "-march=rv64i -mabi=lp64"),
        ]
        build_type = "Debug"
        compiler_rt_arg_templates = [
            # We need compiler-rt as a baremetal version!
            "COMPILER_RT_BAREMETAL_BUILD=ON",
            # We are only interested in the builtins for software mul / div support
            "COMPILER_RT_BUILD_BUILTINS=ON",
            "COMPILER_RT_BUILD_MEMPROF=OFF",
            "COMPILER_RT_BUILD_LIBFUZZER=OFF",
            "COMPILER_RT_BUILD_PROFILE=OFF",
            "COMPILER_RT_BUILD_SANITIZERS=OFF",
            "COMPILER_RT_BUILD_XRAY=OFF",
            # "LIBCXX_USE_COMPILER_RT=YES",
            # "LIBCXXABI_USE_COMPILER_RT=YES",
            # "CLANG_DEFAULT_RTLIB=compiler-rt",
        ]
        compiler_rt_args = []
        if len(targets) > 1:
            compiler_rt_args = [ f"-DRUNTIMES_{t}_{a}" for a in compiler_rt_arg_templates for t, _ in targets]
        else:
            compiler_rt_args = compiler_rt_args + [ f"-D{a}" for a in compiler_rt_arg_templates]
        compiler_rt_args = compiler_rt_args + [ f"-DRUNTIMES_{t}_CMAKE_C_FLAGS=\"{min_support_flags}\"" for t, min_support_flags in targets] \
                                            + [ f"-DRUNTIMES_{t}_CMAKE_CXX_FLAGS=\"{min_support_flags}\"" for t, min_support_flags in targets] \
                                            + [ f"-DRUNTIMES_{t}_CMAKE_ASM_FLAGS=\"{min_support_flags}\"" for t, min_support_flags in targets]

        cmake_config = [
            "-DLLVM_ENABLE_PROJECTS=clang",
            "-DLLVM_TARGETS_TO_BUILD=RISCV",
            # When using enable runtimes, a default target triple is required
            f"-DLLVM_DEFAULT_TARGET_TRIPLE={targets[0][0]}",
            f"-DLLVM_RUNTIME_TARGETS=\"{';'.join([t for t, _ in targets])}\"",
            # "-DLLVM_ENABLE_PER_TARGET_RUNTIME_DIR=ON",
            "-DLLVM_ENABLE_RUNTIMES=compiler-rt",
            # We need compiler-rt as a baremetal version!
            "-DCOMPILER_RT_BAREMETAL_BUILD=ON",
            "-DBUILD_SHARED_LIBS=ON",
            "-DLLVM_CCACHE_BUILD=ON",
            f"-DLLVM_CCACHE_DIR='{ccache_path}'",
            f"-DLLVM_CCACHE_MAXSIZE={ccache_size}",
            f"-DCMAKE_BUILD_TYPE={build_type}",
        ] + compiler_rt_args
        run_cmd.run(llvm_repo, f"cmake -S llvm -B build -G Ninja {functools.reduce(lambda a, b: a + ' ' + b, cmake_config)}", f"Failed to configure cmake for LLVM {version}", error.AWESOME_BASE + 2, False)
        rebuild = True # The desired version did not exists -> force rebuild

    build_dir = llvm_build_dir(llvm_repo)

    if rebuild:
        run_cmd.run(".", f"git -C {llvm_repo} reset --hard origin/{commit}", "Failed to reset the llvm work directory", error.AWESOME_BASE + 3, False)
        # Patch LLVM
        if not do_not_patch:
            llvm_patcher = os.path.abspath(f"{awesome_path}/compiler-patcher/compiler-patcher.sh")
            datasheet_path = os.path.abspath(f"{awesome_path}/datasheets/CVA5.yaml") # Awesome runs prepare-schedule-lil internally and therefore needs a valid datasheet as argument, so lets choose the one with most capabilities
            pass_opts = f"disableISelGen=true datasheet={datasheet_path}" # No ISel patterns for now
            run_cmd.run(".", f"{llvm_patcher} --coredsl-input {mlir_path} --longail-bin {awesome_ln_bin} --llvm-project-dir {llvm_repo} --llvm-version {version} -pass-opts '{pass_opts}'", f"Failed to patch LLVM {version} to add support for the selected ISAXes", error.AWESOME_BASE + 4, False, 200)
        # Build LLVM
        run_cmd.run(".", f"cmake --build {build_dir} -- all", f"Failed to build the {'unpatched' if do_not_patch else 'patched'} LLVM {version}", error.AWESOME_BASE + 5, False, 200)

    return build_dir

def prepare_gcc(kconfig_syms, yaml_files):
    # Create GCC patches
    if kconfig_syms['SIM_GCC_USE_PATCHGEN'].str_value == "y":
        patched_files_dir = os.path.abspath("deps/riscv-gnu/custom_opcodes")
        run_cmd.run(".", f"tools/shady_gcc_patch_creator.py {';'.join(yaml_files)} {patched_files_dir}", "Could not patch gcc", error.GCC_BASE + 1, False)
    elif kconfig_syms['SIM_GCC_OPCODES_DIR'].str_value != "":
        patched_files_dir = os.path.abspath(kconfig_syms['SIM_GCC_OPCODES_DIR'].str_value)
    else:
        # Fail if auto-patching is disabled and no patch directory is provided
        # (user should provide an empty directory if no custom opcodes are desired)
        error.exit_error(f"If the GCC patch generator is disabled, a custom directory must be provided", error.USER_ERROR)
    build_args = ""
    if kconfig_syms['SIM_SKIP_GDB'].str_value == "y":
        build_args += "--disable-gdb"
    # Rebuild GCC
    run_cmd.run("deps", f"./riscv-gnu-build.sh --initial-make-parallel {build_args} {patched_files_dir}", "Recompiling the patched gcc failed!", error.GCC_BASE + 2, False)

def disas_tb(objdump_path, elf_file, error_code):
    disas_path = elf_file + "_disasm.txt"
    disasm_flags = "-D"
    run_cmd.run(".", f"{objdump_path} {disasm_flags} {elf_file} > {disas_path}", f"Failed to disassemble TB elf file '{elf_file}'!", error_code, False)

def compile_tb(tb_paths, core_support, elf_out_path, cc_path, objdump_path, flags, additional_flags, error_code_base, run_disassembly, custom_linker_script=None):
    # Build elf file
    linker_file = core_support.get_linker_file() if custom_linker_script is None else custom_linker_script
    if tb_paths:
        src_files_str = " ".join(tb_paths)
        run_cmd.run(".", f"{cc_path} {flags} {additional_flags} -T {linker_file} {src_files_str} -o {elf_out_path}", "Compiling the test program failed!", error_code_base + 1, False)

    # Build disassembly file
    if run_disassembly:
        disas_tb(objdump_path, elf_out_path, error_code_base + 2)

    return elf_out_path

GNU_PREFIXES=["riscv64-unknown-elf-", "riscv32-unknown-elf-"]
def get_gnu_util_path(pathformat, prefixes):
    """Get the absolute path to a GNU utility using the first prefix that exists.
    :param pathformat: a string with '%s' in place of the tool prefix
    :param prefixes: a list of tool prefixes to try (e.g., "riscv32-unknown-elf-")
    """
    for cur_prefix in prefixes:
        cur_path = os.path.abspath(pathformat % cur_prefix)
        if os.path.exists(cur_path):
            return cur_path
    return os.path.abspath(pathformat)
def get_gcc_objcopy_path():
    return get_gnu_util_path("deps/riscv-prefix/bin/%sobjcopy", GNU_PREFIXES)
def get_gcc_objdump_path():
    return get_gnu_util_path("deps/riscv-prefix/bin/%sobjdump", GNU_PREFIXES)

def gcc_compile_tb(tb_paths, core_support, elf_out_path, additional_flags, run_disassembly, custom_linker_script=None, include_startup_files=False):
    ext = core_support.get_extensions()
    gcc_path = get_gnu_util_path("deps/riscv-prefix/bin/%sgcc", GNU_PREFIXES)
    objdump_path = get_gcc_objdump_path()
    arch_flags = f"-march=rv{ext.xlen}{ext.get_compiler_extensions()} -mabi={ext.abi}"
    c_flags = "-nostdlib -nostartfiles"
    flags = f"{arch_flags} {c_flags} {core_support.get_specific_startup_file()}"
    # these sources should only be used when the input is not ASM but source compiled
    if include_startup_files:
        flags += " " + os.path.abspath(os.path.join("sim", "startup_scripts", "startup.S"))

    return compile_tb(tb_paths, core_support, elf_out_path, gcc_path, objdump_path, flags, additional_flags, error.GCC_BASE + 2, run_disassembly, custom_linker_script)

def llvm_compile_tb(tb_paths, core_support, elf_out_path, llvm_build_path, isax_name, additional_flags, llvm_version, run_disassembly, custom_linker_script=None):
    if isax_name:
        def legalize_isax_name(isax_name):
            return isax_name.lower().replace("_", "").replace(".", "")
        isax_name = legalize_isax_name(isax_name)

    ext = core_support.get_extensions()
    clang_exists, clang_path = check_clang_exists(llvm_version)
    assert clang_exists

    march = f"rv{ext.xlen}{ext.get_compiler_extensions()}"

    # Compile / prepare picolibc
    pico_dir = picolibc.prepare_picolibc()
    llvm_bin_dir = os.path.dirname(clang_path)
    ar_path = os.path.join(llvm_bin_dir, "llvm-ar")
    as_path = os.path.join(llvm_bin_dir, "llvm-as")
    nm_path = os.path.join(llvm_bin_dir, "llvm-nm")
    strip_path = os.path.join(llvm_bin_dir, "llvm-strip")

    env_vars = core_support.get_tb_env_vars()
    pico_inst_dir = picolibc.compile_picolibc(pico_dir, clang_path.removesuffix("++"), ar_path, as_path, nm_path, strip_path, march, ext.abi, scaiev.get_env_value(env_vars, "CTRL_BASE"))

    startup_asm = os.path.abspath(os.path.join("sim", "startup_scripts", "startup.S"))
    compiler_rt_flags = f"-lclang_rt.builtins -L {os.path.join(llvm_build_path, 'lib', 'clang', llvm_version, 'lib', f'riscv{ext.xlen}-unknown-elf')}"
    isax_ext_name = f"_x{isax_name}0p1" if isax_name else ""
    picolibc_flags = f"-mcmodel=medany -L{pico_inst_dir}/lib -isystem {pico_inst_dir}/include -lc -Wl,--whole-archive -lsemihost -Wl,--no-whole-archive"
    flags = f'--target="riscv{ext.xlen}-unknown-elf" -menable-experimental-extensions -mabi="{ext.abi}" -march="{march}{isax_ext_name}" -nostdlib -O3 {startup_asm} {core_support.get_specific_startup_file()} {compiler_rt_flags} {picolibc_flags}'
    objdump_path = os.path.join(llvm_build_path, "bin", "llvm-objdump")

    return compile_tb(tb_paths, core_support, elf_out_path, clang_path, objdump_path, flags, additional_flags, error.AWESOME_BASE + 5, run_disassembly, custom_linker_script)

def precompile_picolibc_for_all_cores(llvm_version):
    clang_exists, clang_path = check_clang_exists(llvm_version)
    assert clang_exists

    # Compile / prepare picolibc
    pico_dir = picolibc.prepare_picolibc()
    llvm_bin_dir = os.path.dirname(clang_path)
    ar_path = os.path.join(llvm_bin_dir, "llvm-ar")
    as_path = os.path.join(llvm_bin_dir, "llvm-as")
    nm_path = os.path.join(llvm_bin_dir, "llvm-nm")
    strip_path = os.path.join(llvm_bin_dir, "llvm-strip")

    for core_name in scaiev.get_known_cores():
        core_support = scaiev.get_core_support(core_name)
        env_vars = core_support.get_tb_env_vars()
        ext = core_support.get_extensions()
        march = f"rv{ext.xlen}{ext.get_compiler_extensions()}"
        pico_inst_dir = picolibc.compile_picolibc(pico_dir, clang_path.removesuffix("++"), ar_path, as_path, nm_path, strip_path, march, ext.abi, scaiev.get_env_value(env_vars, "CTRL_BASE"))
