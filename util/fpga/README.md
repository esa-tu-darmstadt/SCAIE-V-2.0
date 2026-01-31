Utilities and instructions to prepare a tapasco-riscv FPGA bitstream from an extended core, and to start a small assembler program.

Currently supports CVA6 only.

## Usage
Prepare tapasco-riscv with an extended core.
```bash
./tapasco-riscv_setup.sh --name lwdotprodb_zol2d ../nailgun/outputs/run_N
```

Setup [TaPaSCo](https://github.com/esa-tu-darmstadt/tapasco). See the TaPaSCo readme for prerequisites and details.
```bash
git clone https://github.com/esa-tu-darmstadt/tapasco.git

# It is recommended to choose a separate scratch path for tapasco_workdir.
mkdir tapasco_workdir
cd tapasco_workdir
../tapasco/tapasco-init.sh
source tapasco-setup.sh
tapasco-build-toolflow
```

Build a PE with the extended core and a small controller core
```bash
cd tapasco-riscv
source ../tapasco_workdir/tapasco-setup.sh
# According to the instructions output by tapasco-riscv-setup.sh.
# See tapasco-riscv/Makefile for additional options and device support.
make BRAM_SIZE=0 cva6sv_lwdotprodb_zol2d_pe
```

Build a bitstream with the PE. For instance, targeting the AU280 platform:
```bash
tapasco --maxThreads 8 compose [cva6sv_lwdotprodb_zol2d_pe x 1] @ 100 MHz --deleteProjects false -p AU280
```

See [device](device) and [host](host) for a small example for CVA6 with dotprodb+zol2d.
The device portion contains a test program adapted to interact with the tapasco-riscv controller. It reuses the GNU toolchain from the last run of Nailgun.
```bash
cd device
./build.sh lwdotprodb_zol2d_matmul_16x16.S CVA6
```

The host portion uses the TaPaSCo runtime to start the program on the PE.
Build:
```bash
source tapasco_workdir/tapasco-setup.sh
tapasco-build-libs
cd host
mkdir -p build
cd build
cmake ..
make
```

Use `tapasco-load-bitstream` to load the bitstream and the kernel driver on a supported platform. Use `tapasco-debug -d 0 monitor` to check if the bitstream and drivers have loaded properly.

Host software usage (insert the PE name from tapasco-debug):
```bash
source tapasco_workdir/tapasco-setup.sh
cd host/build
./tapascoriscv_scaiev_host "../../device/build/lwdotprodb_zol2d_matmul_16x16.bin" 0 "esa.informatik.tu-darmstadt.de:tapasco:cva6sv_lwdotprodb_zol2d_pe:1.0"
```

Expected output: (note that the return value may vary, as it is the number of execution cycles):
```
Finished reading binary file. Received 1049092 bytes.
Waiting for RISC-V
RISC-V return value: 12015       (number of execution cycles; can vary due to PE-external memory accesses)
Non-zero results size: 400 bytes
ffffab9a
ffff42ed
... (as in examples/test_programs/testmatrix_16x16_each_expected.txt)
```

See [tapasco-riscv/programming/examples](https://github.com/esa-tu-darmstadt/tapasco-riscv/tree/master/programming/examples) for some general tapasco-riscv programming examples.
Note that these examples assume BRAM to be present inside the PE, which the SCAIE-V core PE is not configured for.

