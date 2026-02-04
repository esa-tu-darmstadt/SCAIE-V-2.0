#!/usr/bin/env bash
#Requires variables: $EXAMPLES_DIR, $NAILGUN_DIR

cd "$NAILGUN_DIR/.."
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
cd "$EXAMPLES_DIR"
