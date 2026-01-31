#!/bin/bash

set -e

if [ -z "$1" ]; then
	>&2 echo "Requires argument: core variant name"
	exit 1
fi
RV_NAME_SUFFIX=$1
RV_CORECFG_SUFFIX=${RV_NAME_SUFFIX}
RV_SMALL=0
RV_XLEN=32
case ${RV_NAME_SUFFIX} in *_small) RV_SMALL=1; RV_CORECFG_SUFFIX=${RV_NAME_SUFFIX::-6};; esac
case ${RV_NAME_SUFFIX} in 64_*) RV_XLEN=64;; esac


mkdir -p IP/riscv/
cd riscv/cva6sv

cd "CVA6_${RV_NAME_SUFFIX}"

export RV_ROOT=$(pwd)

echo "Applying CVA6 include patches if needed (may already be applied)"
patch -p0 -f --dry-run < ../cva6_scaiev_fpga_patches.diff  && patch -p0 < ../cva6_scaiev_fpga_patches.diff

if [ $RV_SMALL -eq 1 ]; then
	echo "Applying small-core patch (if needed)"
	patch -p0 -f --dry-run < ../cva6_scaiev_fpga_patches_small.diff  && patch -p0 < ../cva6_scaiev_fpga_patches_small.diff
else
	echo "Reverting small-core patch (if needed)"
	patch -R -p0 -f --dry-run < ../cva6_scaiev_fpga_patches_small.diff  && patch -R -p0 < ../cva6_scaiev_fpga_patches_small.diff
fi

if [ "${RV_CORECFG_SUFFIX}" = "baseline" ]; then
	vivado -nolog -nojournal -mode batch -source ../package_baseline.tcl -tclargs "${RV_NAME_SUFFIX}" ${RV_XLEN}
else
	vivado -nolog -nojournal -mode batch -source ../package.tcl -tclargs "${RV_NAME_SUFFIX}" ${RV_XLEN}
fi
mv "risc-v_cva6sv_${RV_NAME_SUFFIX}.zip" ..
cd ..

rm -rf "../../IP/riscv/CVA6SV/cva6sv_${RV_NAME_SUFFIX}"
mkdir -p "../../IP/riscv/CVA6SV/cva6sv_${RV_NAME_SUFFIX}"
echo "Unzipping CVA6 (SCAIE-V) IP"
unzip "risc-v_cva6sv_${RV_NAME_SUFFIX}.zip" -d ../../IP/riscv/CVA6SV/cva6sv_${RV_NAME_SUFFIX}

cd ../../..

echo "Finished CVA6 (SCAIE-V) Setup!"
