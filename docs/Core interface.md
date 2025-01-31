Much like the SCAIE-V ISAX interface (SCIX), the Core interface (SCIF) consists of a series of operations tied to one of the core's pipeline stages.
Operations may exist in several stages, depending on the constraints set in the core datasheet. 

As the intermediate SCAL module performs the ISAX arbitration, the SCIF contains, at most, a single instance of the following operations.

- Static instruction-bound data (SCIF->SCAL):
  - `RdPC` returns the program counter of the respective stage's current instruction.
  - `RdInstr` returns the instruction word of the respective stage's current instruction.
  - `RdRS1` returns the first operand value (from the register referred to by rs1) of the respective stage's instruction.
  - `RdRS2` returns the second operand value (from the register referred to by rs2) of the respective stage's instruction.
  - (Optional) `RdRD` returns the destination register's original value (from the register referred to by rd) of the respective stage's instruction.
    A core implementation would transparently either use a third port of the core's register file (if present) or implement a second read stage.

- Status and control operations:
  - `RdStall` (SCIF->SCAL) indicates whether the respective pipeline stage is stalling (invalid instruction, hazard, etc.).
    The implementation must ensure that `RdStall` does not depend on the same stage's `WrStall`. In general, to determine if a stage is stalling, both `RdStall` and `WrStall` are to be checked.
  - `WrStall` (SCAL->SCIF) injects a pipeline stall into the respective stage.
    The implementation must ensure that, if set, the instruction in the stage must not enter the next stage and must not be overwritten by an instruction from the previous stage.
  - `RdFlush` (SCIF->SCAL) indicates whether the respective pipeline stage's instruction is being flushed.
    In most cases, SCAL assumes that `RdFlush` for a given in-order stage N+1 implies `RdFlush` for stage N.
    The implementation should ensure that `RdFlush` does not depend on the same stage's `WrFlush`. In general, to determine if a stage is flushing, both `RdFlush` and `WrFlush` are to be checked.
  - `WrFlush` (SCAL->SCIF) injects a flush into the processor pipeline, killing all instructions up until the given stage.

- Register writeback:
  - `WrRD` (SCAL->SCIF) transports the value to store in the destination register.
  - `WrRD_validReq` (SCAL->SCIF) indicates a `WrRD` request.
  - (UNUSED) `WrRD_addr` (SCAL->SCIF), `WrRD_addr_valid` (SCAL->SCIF)

- Memory:
  - `RdMem` (SCIF->SCAL) transports the read result data from the core.
  - `WrMem` (SCAL->SCIF) transports the data to write to memory.
  - `RdMem_validReq`/`WrMem_validReq` (SCAL->SCIF) announces a new memory request. If the core has latency 0 for memory requests, the request is accepted when `!RdStall` even if `WrStall` is set in the operation stage. If the core isn't ready to accept another memory request, it should set `RdStall` and stall the processor pipeline. `RdStall` is allowed to have a combinational dependency on `(Rd|Wr)Mem_validReq`.
  - `RdMem_addr`/`WrMem_addr` (SCAL->SCIF) transports the virtual memory address of a memory operation to the core.
  - `RdMem_addr_valid`/`WrMem_addr_valid` (SCAL->SCIF) indicates the validity of the address signal overriding the default address from a `lw`/`sw`-like instruction encoding. However, in the current implementation, the address signal can be assumed to be valid based on `validReq` alone. 
  - `RdMem_size`/`WrMem_size` (SCAL->SCIF) transports the size (`lw`/`sw` funct3) of a memory operation to the core.
  - `RdMem_validResp` (SCIF->SCAL) indicates completion of a read and the validity of `RdMem`.
  - `WrMem_validResp` (SCIF->SCAL) indicates that a write has been passed to the core's LSU, and that the write will be visible to any following memory operations on the hart.

- Control flow:
  - `WrPC` (SCAL->SCIF) transports the intended program counter to write. The core should flush prior stages accordingly.
  - `WrPC_validReq` (SCAL->SCIF) indicates a `WrPC` request.
  - A `WrPC` operation in the first stage ('next PC') is assumed to override the `RdPC` value read from the same stage. `WrPC` in later stages, if latency is set to 1 in the core datasheet, only affects the next instruction.

- Spawn operations (without an attached pipeline instruction, in the dedicated 'decoupled' stage):
  - `RdMem_spawn`/`WrMem_spawn` performs a memory operation without any handling of data hazards. Behavior in case of exceptions is implementation-defined.
    - `RdMem_spawn` (SCIF->SCAL read result), `WrMem_spawn` (SCAL->SCIF write data)
    - The basic adjacent pins are shared for reads and writes.
      - `Mem_spawn_validReq`, `Mem_spawn_validResp`, `Mem_spawn_addr`, `Mem_spawn_size` behave as with regular memory operations. However, instead of `RdStall`, the acceptance condition is `ISAX_spawnAllowed`.
      - `Mem_spawn_write` (SCAL->SCIF) indicates whether the given operation is a read (`1'b0`) or a write (`1'b1`).
      - Note: From a SCAIE-V core backend implementation, the adjacent `SCAIEVNode`s, e.g., `BNode#RdMem_spawn_valid` and `BNode#WrMem_spawn_valid`, refer to the same pin.
  - `WrRD_spawn` performs a register writeback with full data hazard handling.
    - `WrRD_spawn_validReq` behaves as with other operations, but commits based on `ISAX_spawnAllowed`.
    - `WrRD_spawn_validResp` indicates when the register has been written or has become visible to all forwarding paths.
    - `WrRD_spawn_addr` contains the register address to write to. The destination register always is the original `rd` from the instruction (stored in a FIFO by SCAL).
      For cores with renaming or custom hazard handling, the core backend may modify `BNode#size` and use `SCALBackendAPI#OverrideSpawnRDAddr` to change the payload of `WrRD_spawn_addr`.
    - For MCU-class cores, the generic hazard handling logic from SCAL usually is sufficient. A core can also implement custom hazard handling and disable SCAL's by `SCALBackendAPI#SetUseCustomSpawnDH`.
  - `ISAX_spawnAllowed` (SCIF->SCAL) indicates if the core is ready to accept spawn operations.
    - If the condition differs between operations, the core backend can explicitly define `RdMem_spawn_spawnAllowed`, `WrMem_spawn_spawnAllowed`, and `WrRD_spawn_spawnAllowed` through `SCALBackendAPI#SetHasAdjSpawnAllowed`.

By default, SCAL stalls all stages of the core during a spawn operation's `validReq`. This can be disabled with `SCALBackendAPI#DisableStallForSpawnCommit`. 

The `SCALBackendAPI#OverrideEarliestValid` method makes `validReq` announce upcoming requests by the current instruction (in a later stage).
  In case of write operations, `validData` indicates validity of the supplied requests. The core should stall if it expects data while `!validData`.

The core can request `RdIValid` pins from SCAL for each ISAX. These are commonly used to feed the core's decoder and to apply per-ISAX hazard logic. 
Additionally, for cores supporting parallel execution in the semi-coupled mode, `RdAnyValid` indicates whether any ISAX instruction is running within the given execute stage.

### Core backend implementation
All SCAIE-V core backends extend the abstract `CoreBackend` class, containing management of interface pins and assignment (`Node*` methods and `PutNode`) as well as the core HDL module hierarchy (`Mod*` methods and `PutModule`).

The instantiation of each core backend is done by the `SCAIEV` class. It calls the `Prepare` method before generating of SCAL; `Prepare` and is where most `SCALBackendAPI` calls should be done. `op_stage_instr` contains the schedule for each operation and also lists the ISAX names using the operation. The `Generate` method then performs the actual code patching / generation.

Note that SCAL may change `op_stage_instr` between the `Prepare` and `Generate` calls of the core backend. For instance, SCAL may register static data from previous stages or request additional stall and flush signals.

The backend can use a language utility class such as `Verilog` (`UpdateInterface` and `GenerateAllInterfaces` methods) to add the required pins to the interface and, optionally, assign values to output pins (or assign local signals from input pins) in the innermost HDL module.
The `FileWriter#ReplaceContent` and `FileWriter#UpdateContent` methods provide a measure to replace or insert HDL code by matching for existing code locations. 

