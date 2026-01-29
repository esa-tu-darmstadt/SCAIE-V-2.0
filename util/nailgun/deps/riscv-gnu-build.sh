#!/usr/bin/env bash
#$1: Path to opcode file overrides
#Dependencies: texinfo
MAKEJOBS=$(nproc --all || echo 20)
BASE=$(pwd)

config_arch=""
config_abi="ilp32"
config_disable_gdb=0
config_parallel_fullmake=0
config_reconfigure=0

show_help() {
cat << EOF
Usage: ${0##*/} [--reconfigure] [--arch ARCH] [--abi ABI]
       [--disable-gdb] [--initial-make-parallel] [OPCODEDIR]
Builds riscv-gnu with custom opcode files in OPCODEDIR (default '.').
After the initial build, only rebuilds binutils and gdb.

Standard files, included into GDB and Binutils:
 opcode_defs.h -> riscv_opcodes array in riscv-opc.c
 isax_defs.h -> riscv-opc.h
Complete file replacements, Binutils only (not stable across versions):
 riscv-dis.c, riscv.h, tc-riscv.c

  Options:
    -h            Show help and exit
    --reconfigure Rerun configure in riscv-gnu
                  (should use after changing ARCH or ABI or --disable-gdb)
    --arch ARCH   Set target arch, e.g., rv32im (default: "" -> multilib)
    --abi  ABI    Set target abi (default: "ilp32" if not multilib)
    --disable-gdb            Disable gdb build
    --initial-make-parallel  Also run the full build with multiple jobs
                             (may cause problems)
EOF
}
ARGS_ARRAY=( "$@" )
i_arg=0
cur_arg=""
readarg() {
	local argname="$1"
	local arg_optional=${2:-0}
	if (( $i_arg < ${#ARGS_ARRAY[@]} )); then 
		cur_arg=${ARGS_ARRAY[$i_arg]}
		i_arg=$(( $i_arg + 1))
	else
		cur_arg=""
		if [ $arg_optional -eq 0 ]; then
			>&2 echo "Missing argument $argname"
			exit 1
		fi
	fi
}
while (( $i_arg < ${#ARGS_ARRAY[@]} )); do
	readarg
	case $cur_arg in
		-h|--help) show_help; exit 0; ;;
		--arch=*) config_arch="${cur_arg#*=}" ;;
		--arch) readarg "ARCH"; config_arch="${cur_arg}" ;;
		--abi=*) config_abi="${cur_arg#*=}" ;;
		--abi) readarg "ABI"; config_abi="${cur_arg}" ;;
		--disable-gdb) config_disable_gdb=1 ;;
		--initial-make-parallel) config_parallel_fullmake=1 ;;
		--reconfigure) config_reconfigure=1 ;;
		-*) >&2 show_help; exit 1; ;;
		*) i_arg=$(( $i_arg - 1)); break; ;;
	esac
done
if [ -z "$config_abi" ]; then
	config_abi="ilp32"
fi

readarg "OPCODEDIR" 1
OPCODESPATH="${cur_arg}"
if [ -z "$OPCODESPATH" ]; then
	OPCODESPATH="."
fi

if (( $i_arg < ${#ARGS_ARRAY[@]} )); then
	>&2 echo "${0##*/} ignoring additional arguments"
fi

if ! [ -d riscv-gnu ]; then
    git clone --branch 2026.01.23 https://github.com/riscv-collab/riscv-gnu-toolchain.git riscv-gnu
fi

cd riscv-gnu

# Fetch submodules that need patched opcodes
[ -f binutils/README ] || git submodule update --init -- binutils
[ -f gdb/README ] || [ $config_disable_gdb -eq 1 ] || git submodule update --init -- gdb

# Configure riscv-gnu-toolchain if not done already.
if [ $config_reconfigure -eq 1 ] || ! [ -f Makefile ]; then
	CONFIG_ARGS=("--prefix=$BASE/riscv-prefix")
	if [ $config_disable_gdb -eq 1 ]; then
		CONFIG_ARGS+=(--disable-gdb)
	fi
	
	if [ -z "$config_arch" ]; then
		CONFIG_ARGS+=(--enable-multilib)
	else
		CONFIG_ARGS+=("--with-arch=${config_arch}" "--with-abi=${config_abi}")
	fi
	
	./configure "${CONFIG_ARGS[@]}"
fi

cd ..

# Copy changed opcode-related source files into binutils.

BINUTILS_OPCODESPATH=riscv-gnu/binutils/opcodes
BINUTILS_OPCODESINCPATH=riscv-gnu/binutils/include/opcode
GDB_OPCODESPATH=riscv-gnu/gdb/opcodes
GDB_OPCODESINCPATH=riscv-gnu/gdb/include/opcode

restore_or_copy() {
	local ffrom=$1
	local fto=$2
	local restore_as_empty=${3:-0}
	if [ -n "$ffrom" ] && [ -f "$ffrom" ]; then
		if ! [ -f "$fto" ] && [ $restore_as_empty -eq 0 ]; then
			>&2 echo "WARN: Overwriting missing destination file $fto"
		fi
		# Only copy files if necessary (avoid changing the timestamp)
		if ! [ -f "$fto" ] || ! (diff "$fto" "$ffrom" > /dev/null); then
			cp "$ffrom" "$fto"
		fi
	elif [ $restore_as_empty -eq 1 ]; then
		rm -f "$fto"
		touch "$fto"
	else
		pushd "$(dirname "$fto")" > /dev/null
		git restore "$(basename "$fto")"
		popd > /dev/null
	fi
}

pre_copy_check=1
if [ -f "$OPCODESPATH/riscv-opc.c" ]; then
	>&2 echo "ERROR: Breaking change - Move new instructions from riscv-opc.c to opcode_defs.h"
	pre_copy_check=0
fi
if [ -f "$OPCODESPATH/riscv-opc.h" ]; then
	>&2 echo "ERROR: Breaking change - Move new definitions from riscv-opc.h to isax_defs.h"
	pre_copy_check=0
fi
if [ $pre_copy_check -eq 0 ]; then
	exit 1
fi

restore_or_copy "$OPCODESPATH/riscv-dis.c" "$BINUTILS_OPCODESPATH/riscv-dis.c"
restore_or_copy "$OPCODESPATH/riscv.h" "$BINUTILS_OPCODESINCPATH/riscv.h"
restore_or_copy "$OPCODESPATH/tc-riscv.c" riscv-gnu/binutils/gas/config/tc-riscv.c
restore_or_copy "$OPCODESPATH/opcode_defs.h" "$BINUTILS_OPCODESPATH/opcode_defs.h" 1
restore_or_copy "$OPCODESPATH/isax_defs.h" "$BINUTILS_OPCODESINCPATH/isax_defs.h" 1

restore_or_copy "" "$BINUTILS_OPCODESPATH/riscv-opc.c"
restore_or_copy "" "$BINUTILS_OPCODESINCPATH/riscv-opc.h"

if [ $config_disable_gdb -eq 0 ]; then
	# copy opcodes for GDB
	restore_or_copy "$OPCODESPATH/opcode_defs.h" "$GDB_OPCODESPATH/opcode_defs.h" 1
	restore_or_copy "$OPCODESPATH/isax_defs.h" "$GDB_OPCODESINCPATH/isax_defs.h" 1

	restore_or_copy "" "$GDB_OPCODESPATH/riscv-opc.c"
	restore_or_copy "" "$GDB_OPCODESINCPATH/riscv-opc.h"
fi

mkdir -p riscv-prefix
cd riscv-gnu

cd binutils
git apply ../../patches/riscv-opc.c.patch && git apply ../../patches/riscv-opc.h.patch
cd ..
if [ $config_disable_gdb -eq 0 ]; then
	cd gdb
	git apply ../../patches/riscv-opc.c.patch && git apply ../../patches/riscv-opc.h.patch
	cd ..
fi

# After opcode changes, only rebuild binutils if the toolchain was built before.
if [ -f build-binutils-newlib/gas/as-new ]; then
	make -j${MAKEJOBS} -C build-binutils-newlib
	if [ build-binutils-newlib/gas/as-new -nt $BASE/riscv-prefix/bin/riscv32-unknown-elf-as ] || ! [ -f build-binutils-newlib/gas/as-new ] ; then
		# If new assembler binary was built or the hardcoded paths in this script are wrong (or the build failed), install it.
		make -C build-binutils-newlib install
	fi
	if [ $config_disable_gdb -eq 0 ]; then
		make -j${MAKEJOBS} -C build-gdb-newlib install
	fi
else
	# Build toolchain with newlib.
	if [ $config_parallel_fullmake -eq 0 ]; then
		# Build sometimes fails with multiple jobs -> use sequential build to be safe
		make
	else
		make -j${MAKEJOBS}
	fi
fi

cd ..

