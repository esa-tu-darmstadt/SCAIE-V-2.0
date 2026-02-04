#!/usr/bin/env bash
set -e

TESTIDX=$1
EXAMPLES_DIR="$(realpath "$(dirname "$0")")"
NAILGUN_DIR="$(realpath "$(dirname "$0")/../util/nailgun")"

source "${EXAMPLES_DIR}/run_prepare.sh"
cd "$NAILGUN_DIR"

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
