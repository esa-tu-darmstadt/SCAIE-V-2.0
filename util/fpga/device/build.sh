#!/usr/bin/env bash

#./build.sh lwdotprodb_zol2d_matmul_16x16.S CVA6

TESTPROG=$1
CORENAME=$2
SELFDIR_ABS="$(realpath "$(dirname "$0")")"
SELFDIR="$(dirname "$0")"

PROGNAME="${TESTPROG%.*}"

GNU_PREFIX="${SELFDIR}/../../nailgun/deps/riscv-prefix/bin/riscv64-unknown-elf-"

mkdir -p "${SELFDIR}/build"

set -e

${GNU_PREFIX}gcc -march=rv32im_zicsr -mabi=ilp32 -nostdlib -nostartfiles -T "${SELFDIR}/${CORENAME}_link_tapascoriscv.ld" "$TESTPROG" -o "${SELFDIR}/build/${PROGNAME}.elf"
${GNU_PREFIX}objdump -d "${SELFDIR}/build/${PROGNAME}.elf" > "${SELFDIR}/build/${PROGNAME}_disasm.txt"

${GNU_PREFIX}objcopy -O binary "${SELFDIR}/build/${PROGNAME}.elf" "${SELFDIR}/build/${PROGNAME}.bin"

