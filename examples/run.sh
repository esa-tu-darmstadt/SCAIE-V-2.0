#!/usr/bin/env bash
set -e

TESTIDX=$1
EXAMPLES_DIR="$(realpath "$(dirname "$0")")"
NAILGUN_DIR="$(realpath "$(dirname "$0")/../util/nailgun")"
cd "$(dirname "$0")/../util"
source build-pyenv.sh
cd "$NAILGUN_DIR"
if [ -f deps/verilator-source.sh ]; then
    source deps/verilator-source.sh;
else
    if command -v verilator &> /dev/null; then
        echo "Using verilator from $(which verilator). It is recommended to build a known-compatible verilator with verilator-build.sh in util/nailgun/deps." >&2
    else
        echo "Verilator is missing. Simulation will fail. See verilator-build.sh in util/nailgun/deps." >&2
        if [ -z ${IGNORE_VERILATOR} ]; then exit 1; fi
    fi
fi
# Optional: Only required for Orca core
if [ -f deps/ghdl-yosys-source.sh ]; then source deps/ghdl-yosys-source.sh; fi
# Optional: Only required for Piccolo core
if [ -f deps/bsc-source.sh ]; then source deps/bsc-source.sh; fi

make gen_config

# A couple of different tests across CVA6 (dual-issue), CVA6, CVA5, ORCA.

python3 "${EXAMPLES_DIR}/gen_example_config.py" --conf "${EXAMPLES_DIR}/configs/config_common" \
    --core CVA6_DUAL \
    --isaxdir "${EXAMPLES_DIR}/isaxes/cva6" \
    --isax lwdotprod_bias --isax zol2d \
    --conf "${EXAMPLES_DIR}/configs/config_testprog_lwdotprodb_zol2d"

CONFIG_PATH=".config_examplerun" make build

python3 "${EXAMPLES_DIR}/gen_example_config.py" --conf "${EXAMPLES_DIR}/configs/config_common" \
    --core CVA6 \
    --isaxdir "${EXAMPLES_DIR}/isaxes/cva6" \
    --isax dotprod_bias \
    --conf "${EXAMPLES_DIR}/configs/config_testprog_dotprodb"

CONFIG_PATH=".config_examplerun" make build

python3 "${EXAMPLES_DIR}/gen_example_config.py" --conf "${EXAMPLES_DIR}/configs/config_common" \
    --core CVA5 \
    --isaxdir "${EXAMPLES_DIR}/isaxes/cva5" \
    --isax lwdotprod_bias --isax zol2d \
    --conf "${EXAMPLES_DIR}/configs/config_testprog_lwdotprodb_zol2d"

CONFIG_PATH=".config_examplerun" make build

python3 "${EXAMPLES_DIR}/gen_example_config.py" --conf "${EXAMPLES_DIR}/configs/config_common" \
    --core CVA5 \
    --isaxdir "${EXAMPLES_DIR}/isaxes/cva5" \
    --isax dotprod \
    --conf "${EXAMPLES_DIR}/configs/config_testprog_dotprod"

CONFIG_PATH=".config_examplerun" make build

python3 "${EXAMPLES_DIR}/gen_example_config.py" --conf "${EXAMPLES_DIR}/configs/config_common" \
    --core ORCA \
    --isaxdir "${EXAMPLES_DIR}/isaxes/ORCA" \
    --isax dotprod \
    --conf "${EXAMPLES_DIR}/configs/config_testprog_dotprod"

CONFIG_PATH=".config_examplerun" make build
