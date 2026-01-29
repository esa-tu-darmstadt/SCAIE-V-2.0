import os
from tools.isax_yaml_tools import *

def gen_renode_confs(isax_model_filename, sim_dir, yaml_paths, tb_paths, march, IMEM_BASE, DMEM_BASE, DMEM_SIZE, CTRL_BASE):
    isax_patterns = dict()
    for yaml_path in yaml_paths:
        isax_patterns.update(extract_encodings(yaml_path))
    renode_dir = os.path.abspath(os.path.join(sim_dir, "renode"))
    os.makedirs(renode_dir, exist_ok=True)
    core_desc = os.path.join(renode_dir, "riscv.repl")
    with open(core_desc, 'w') as f:
        f.write(f"""
cpu: CPU.RiscV32 @ sysbus
    cpuType: "{march}"
    timeProvider: clint

imem: Memory.MappedMemory @ sysbus 0x{IMEM_BASE}
    size: 0x{DMEM_SIZE}

dmem: Memory.MappedMemory @ sysbus 0x{DMEM_BASE}
    size: 0x{DMEM_SIZE}

// Power/Reset/Clock/Interrupt
clint: IRQControllers.MiV_CoreLevelInterruptor  @ sysbus 0x40000000
    frequency: 100000000
    prescaler: 100
    [0, 1] -> cpu@[3, 7]

stop_sim: Python.PythonPeripheral @ sysbus 0x{CTRL_BASE}
    size: 0x08
    initable: false
    script: ""

uart: UART.MiV_CoreUART @ sysbus 0x{int(CTRL_BASE, 16) + 8:X}
    clockFrequency: 50000000

resdata: Memory.MappedMemory @ sysbus 0x{int(CTRL_BASE, 16) + 0x20000:X}
    size: 0x0E0000
""")

    custom_instruction_handlers = [ f'sysbus.cpu InstallCustomInstructionHandlerFromFile "{mask.replace('-', 'x')}" "{renode_dir}/{isax_model_filename}" # Custom instruction: {i}' for i, mask in isax_patterns.items() ]
    load_elfs = [ f'{"# " if i != 0 else ""}sysbus LoadELF @{os.path.abspath(tb_path)}' for i, tb_path in enumerate(tb_paths) ]
    newline = "\n"

    script = os.path.join(renode_dir, "run.resc")
    with open(script, 'w') as f:
        f.write(f"""
mach create
machine LoadPlatformDescription @{core_desc}
# sysbus LogAllPeripheralsAccess true

# Custom instruction handlers
{newline.join(custom_instruction_handlers)}

# Logging
sysbus.cpu LogFunctionNames true
# sysbus.cpu CreateExecutionTracing "cputracer" @{renode_dir}/trace.log Disassembly
# cputracer TrackMemoryAccesses

{newline.join(load_elfs)}
showAnalyzer sysbus.uart

# Do not auto start on gdb attach
# machine StartGdbServer 3333 false

sysbus SetHookBeforePeripheralWrite stop_sim "machine.PauseAndRequestEmulationPause()"

# start
""")

    script = os.path.join(renode_dir, "run.sh")
    with open(script, 'w') as f:
        f.write(f"""#!/bin/sh
renode --console --disable-gui run.resc
""")
    # Make the script executable
    os.chmod(script, 0o755)

    robot_dir = os.path.join(renode_dir, "robots")
    os.makedirs(robot_dir)
    global_timeout = 600
    test_timeout = 60
    for idx, tb_path in enumerate(tb_paths):
        script = os.path.join(robot_dir, f"run_tb_{idx}.robot")
        with open(script, 'w') as f:
            f.write(f"""*** Variables ***
${{SCRIPT}}         ${{CURDIR}}/../run.resc

*** Test Cases ***
RUN TB {idx}
    [Timeout]                  {test_timeout} seconds
    Execute Script             ${{SCRIPT}}
    Execute Command            sysbus LoadELF @${{CURDIR}}/{os.path.relpath(tb_path, robot_dir)}
    Create Log Tester          {test_timeout}
    Start Emulation
    Wait For Log Entry         Machine paused
""")

    script = os.path.join(renode_dir, "run_robots.sh")
    with open(script, 'w') as f:
        f.write(f"""#!/bin/sh
cd robots
renode-test --keep-renode-output --test-timeout {global_timeout} -j`nproc` {" ".join([f"run_tb_{i}.robot" for i in range(len(tb_paths))])}
""")
    # Make the script executable
    os.chmod(script, 0o755)


    return renode_dir
