#!/usr/bin/env bash
set -e

EXAMPLES_DIR="$(realpath "$(dirname "$0")")"
NAILGUN_DIR="$(realpath "$(dirname "$0")/../util/nailgun")"

source "${EXAMPLES_DIR}/run_prepare.sh"
cd "$NAILGUN_DIR"

# A couple of different tests across CVA6 (dual-issue), CVA6, CVA5, ORCA.

for cva6variant in CVA6 CVA6_DUAL; do
    python3 "${EXAMPLES_DIR}/gen_example_config.py" --conf "${EXAMPLES_DIR}/configs/config_common" \
        --core $cva6variant \
        --isaxdir "${EXAMPLES_DIR}/isaxes/cva6" \
        --isax lwdotprod_bias --isax zol2d \
        --conf "${EXAMPLES_DIR}/configs/config_testprog_lwdotprodb_zol2d" \
        --conf "${EXAMPLES_DIR}/configs/config_lockstep_lwdotprodb_zol2d"

    CONFIG_PATH=".config_examplerun" make build

    python3 "${EXAMPLES_DIR}/gen_example_config.py" --conf "${EXAMPLES_DIR}/configs/config_common" \
        --core $cva6variant \
        --isaxdir "${EXAMPLES_DIR}/isaxes/cva6" \
        --isax lwdotprod_bias --isax zol2d \
        --conf "${EXAMPLES_DIR}/configs/config_testprog_lwdotprodb" \
        --conf "${EXAMPLES_DIR}/configs/config_lockstep_lwdotprodb_zol2d"

    CONFIG_PATH=".config_examplerun" make build

    python3 "${EXAMPLES_DIR}/gen_example_config.py" --conf "${EXAMPLES_DIR}/configs/config_common" \
        --core $cva6variant \
        --isaxdir "${EXAMPLES_DIR}/isaxes/cva6" \
        --isax dotprod_bias \
        --conf "${EXAMPLES_DIR}/configs/config_testprog_dotprodb" \
        --conf "${EXAMPLES_DIR}/configs/config_lockstep_dotprodb"

    CONFIG_PATH=".config_examplerun" make build

    python3 "${EXAMPLES_DIR}/gen_example_config.py" --conf "${EXAMPLES_DIR}/configs/config_common" \
        --core $cva6variant \
        --isaxdir "${EXAMPLES_DIR}/isaxes/cva6" \
        --isax dotprod \
        --conf "${EXAMPLES_DIR}/configs/config_testprog_dotprod" \
        --conf "${EXAMPLES_DIR}/configs/config_lockstep_dotprod_extra"

    CONFIG_PATH=".config_examplerun" make build

    for extraprog in autoinc brimm indirectjmp sbox sparkle sqrt-decoupled sqrt-semicoupled zol; do
        python3 "${EXAMPLES_DIR}/gen_example_config.py" --conf "${EXAMPLES_DIR}/configs/config_common" \
            --core $cva6variant \
            --isaxdir "${EXAMPLES_DIR}/isaxes/cva6/extra" \
            --isax autoinc --isax brimm --isax indirectjmp --isax sbox --isax sparkle --isax sqrt_decoupled --isax sqrt_semicoupled --isax zol \
            --conf "${EXAMPLES_DIR}/configs/extra/config_testprog_${extraprog}" \
            --conf "${EXAMPLES_DIR}/configs/config_lockstep_dotprod_extra"

        CONFIG_PATH=".config_examplerun" make build
    done
done
