#!/usr/bin/env bash

arg_NAME=""

show_help() {
cat << EOF
Usage: ${0##*/} [--name NAME] [RUNDIR]
Prepares a tapasco-riscv directory with the core sources from the given Nailgun outputs/run_i directory (RUNDIR).
After updating SCAIE-V, clear out the tapasco-riscv subdirectory to get rid of any outdated files.

  Options:
    -h            Show help and exit
    --name        Name for the PE. CVA6: "baseline" disables SCAIE-V, suffix "_small" reduces the cache size. 
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
            show_help
            exit 1
        fi
    fi
}
while (( $i_arg < ${#ARGS_ARRAY[@]} )); do
    readarg
    case $cur_arg in
        -h|--help) show_help; exit 0; ;;
        --name=*) arg_NAME="${cur_arg#*=}" ;;
        --name) readarg "NAME"; arg_NAME="${cur_arg}" ;;
        -*) >&2 show_help; exit 1; ;;
        *) i_arg=$(( $i_arg - 1)); break; ;;
    esac
done

if [ -z "$arg_NAME" ]; then
    >&2 echo "Missing argument NAME, see -h"
    show_help
    exit 1
fi
readarg "RUNDIR"; RUNDIR="${cur_arg}"

set -e

if ! [ -d tapasco-riscv ]; then
    # Clone tapasco-riscv, checkout specific commit 
    git clone https://github.com/esa-tu-darmstadt/tapasco-riscv.git
    cd tapasco-riscv
    git checkout cb789f0dbebe5959d88414ae4f4b2195d070f809
    cd ..
fi

if ! [ -f tapasco-riscv/specific_tcl/cva6sv_pe_project.tcl ]; then
    # Apply patch, copy base project files
    cd tapasco-riscv
    git apply ../tapasco-riscv_patches/tapascoriscv.patch
    cp -r ../tapasco-riscv_patches/riscv/* riscv/
    cp ../tapasco-riscv_patches/specific_tcl/* specific_tcl/
    cd ..
fi

pe_name=""

shopt -s extglob


if [ -d "$RUNDIR/CVA6_bcb0f7d" ] || [ -d "$RUNDIR/CVA6_bcb0f7d_dual" ]; then
    dest="tapasco-riscv/riscv/cva6sv/CVA6_$arg_NAME"
    rm -rf "$dest"
    cp -r "$RUNDIR/CVA6_bcb0f7d"* "$dest"
    pe_name="cva6sv_${arg_NAME}_pe"
elif [ -d "$RUNDIR/CVA6_64_bcb0f7d" ] || [ -d "$RUNDIR/CVA6_64_bcb0f7d_dual" ]; then
    dest="tapasco-riscv/riscv/cva6sv/CVA6_64_${arg_NAME}"
    rm -rf "$dest"
    cp -r "$RUNDIR/CVA6_64_bcb0f7d"* "$dest"
    pe_name="cva6sv_64_${arg_NAME}_pe"
else
    >&2 echo "ERROR: Unsupported core"
    exit 1
fi

mkdir -p "$dest/isaxes/_"
cp "$RUNDIR/ISAX_"*.sv "$dest/isaxes/_" || :
# no need to copy yaml files

echo ""
echo "Done"
echo ""
echo "To use tapasco-riscv, first setup TaPaSCo: https://github.com/esa-tu-darmstadt/tapasco"
echo "In a shell with Vivado and a TaPaSCo work directory in the environment, run:"
echo " cd tapasco-riscv; make BRAM_SIZE=0 ${pe_name}"
echo "See tapasco-riscv/Makefile for additional configuration."

