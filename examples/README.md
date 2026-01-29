# SCAIE-V testbench examples
Contains sample ISAXes and tests for CVA5, CVA6, roughly covering the feature set of SCAIE-V: variations of dot product instructions and a 2D zero-overhead loop.

The dot product ISAXes cover the single-cycle, multi-cycle (semi-coupled) and dynamic-latency decoupled execution modes with reading from memory and custom registers.
The zero-overhead loop covers a special case for custom registers and custom control flow, performing custom register accesses in the Fetch stage while SCAIE-V handles any control and data hazards.

## Setup
Paths are relative to the SCAIE-V repository base.

All cores:
`git submodule update --init`

Requires Verilator 5.038 or newer (tested: 5.044). Build script:
```bash
cd util/nailgun/deps
./verilator-build.sh
```

Additional submodules for CVA6:
```bash
cd CoresSrc/CVA6
git submodule update --init core/cache_subsystem/hpdcache
git submodule update --init --recursive core/cvfpu
git submodule update --init --recursive corev_apu/riscv-dbg
git submodule update --init --recursive corev_apu/rv_plic
git submodule update --init --recursive corev_apu/axi_mem_if
git submodule update --init --recursive corev_apu/register_interface
```

Additional build step for ORCA (ghdl and ghdl-yosys-plugin):
```bash
cd util/nailgun/deps
./ghdl-yosys-build.sh
```

Additional build step for Piccolo (Bluespec Compiler):
```bash
cd util/nailgun/deps
./bsc-build.sh
```

RISC-V GNU toolchain, used and patched to assemble the test programs.
The initial build will take a while, while later builds only recompile a small portion of binutils.
Although this is built automatically, you can run the build manually to identify any build issues.
```bash
cd util/nailgun/deps
./riscv-gnu-build.sh --initial-make-parallel --disable-gdb
```

## Usage
The `examples/run.sh` script starts a series of predefined tests. It calls the Nailgun flow that sets up the patched core and the simulation environment, creating a new directory inside `util/nailgun/outputs`.
The simulation environment outputs a waveform to `util/nailgun/outputs/run_<i>/sim/dump.fst`.


