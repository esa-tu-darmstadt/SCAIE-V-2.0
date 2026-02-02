Much like the SCAIE-V ISAX interface (SCIX), the Core interface (SCIF) consists of a series of operations tied to one of the core's pipeline stages.
Operations may exist in several stages, depending on the constraints set in the core datasheet. 

As the intermediate SCAL module performs the ISAX arbitration, the SCIF contains, at most, a single instance of the following operations per stage (except for RdIValid).

If the core has multiple ports (multi-decode, multi-issue, etc.), the pipeline graph in the core description file should be built with a `core_multi` base stage (e.g. "decode") and then `core` stages for each port (e.g. "decodeP0", "decodeP1").
The `multiportStall` attribute controls whether the stall, flush (+ WrPC) signals are in each port or just in the base stage. All other standard signals should exist in each port.

- Static instruction-bound data (SCIF->SCAL):
  - `RdPC` returns the program counter of the respective stage's current instruction.
  - `RdInstr` returns the instruction word of the respective stage's current instruction.
  - `RdInstr_RD` indicates the destination register for the current instruction: {is_valid (1 bit), register_number (5 bit)}. CoreBackend#Prepare should set the size and elements fields.
  - `RdInstr_RS` indicates the operand registers: {rs2_is_valid (1 bit), rs2_register_number (5 bit), rs1_is_valid, rs1_register_number}. May also contain rs3. CoreBackend#Prepare should set the size and elements fields.
  - `RdRS1` returns the first operand value (from the register referred to by rs1) of the respective stage's instruction.
  - `RdRS2` returns the second operand value (from the register referred to by rs2) of the respective stage's instruction.
  - (Optional) `RdRD` returns the destination register's original value (from the register referred to by rd) of the respective stage's instruction.
    A core implementation would transparently either use a third port of the core's register file (if present) or implement a second read stage.

- Instruction tracking (for cores with a ROB, where not all mispredictions are covered by RdFlush):
  - `RdIssueID` (SCIF->SCAL, stages tagged with Issue or CustReg.addr_constraint onwards): the instruction ID in the core's ROB
  - `RdIssueFlushID` (SCIF->SCAL): Indicates the next instruction ID that will be assigned after a flush. Required only for stages before Issue but within CustReg.addr_constraint.
  - `RdCommitID`, `RdCommitIDCount` (SCIF->SCAL): Signals the instruction IDs being committed in the core (first ID, number of subsequent IDs).
  - `RdCommitFlushID`, `RdCommitFlushIDCount` (SCIF->SCAL): Signals the instruction IDs being dropped by the core (first ID, number of subsequent IDs). Alternative to `RdCommitFlushMask`.
  - `RdCommitFlushMask` (SCIF->SCAL): Signals the instruction IDs being dropped by the core (bitmask over the ID space). Alternative to `RdCommitFlushID` (should only provide one of both if the information is equivalent).
  - `RdCommitFlushAll` (SCIF->SCAL): Indicactes if all still-pending instructions in the ROB are being dropped. Leave size=0 if not present.
  - `RdCommitFlushAllID` (SCIF->SCAL): The next ID that will be allocated to the ROB after a `RdCommitFlushAll`. Leave size=0 if not present.
  - The CoreBackend#Prepare method should set the size and elements fields of each node.

- Stage buffer handling (if there is a buffer between two stages in the core):
  - Buffer enqueue/dequeue ID (SCIF->SCAL). Custom signal name.
  - Buffer flush range (ID from & ID count or ID mask), or none if only RdFlush in the destination stage is relevant. Custom signal name.
  - CoreBackend#Prepare should override NodeRegPipelineStrategy#makePipelineBuilder_single, handling buffer stage transitions through custom instances of IDBasedPipelineStrategy.

- Multi-cycle execute (stage tag Execute):
  - `RdInStageID` (SCIF->SCIF): Instruction ID to use for semi-coupled execution. Usually the same as `RdIssueID`. Size should be set by CoreBackend#Prepare.
  - `WrDeqInstr` (SCAL->SCIF): Transfer the current instruction and its ID (`RdInStageID`) into SCAL's tracking.
  - `WrInStageID`, `WrInStageID_validReq` (SCAL->SCIF): Notification by SCAL that execution finished, alongside the stored ID.
  - `WrInStageID_validResp` (SCIF->SCAL): Indicates the core's readiness of finishing instruction execution.
  - If these are not provided, SCAL will use a fallback implementation that stalls the Execute stage until completion. The fallback does not support pipelining.

- Status and control operations:
  - `RdInStageValid` (SCIF->SCAL) indicates the presence of an instruction in the pipeline stage.
  - `RdStall` (SCIF->SCAL) indicates whether the respective pipeline stage is stalling (invalid instruction, hazard, etc.).
    The implementation must ensure that `RdStall` does not depend on the same stage's `WrStall`. In general, to determine if a stage is stalling, both `RdStall` and `WrStall` are to be checked.
  - `WrStall` (SCAL->SCIF) injects a pipeline stall into the respective stage.
    The implementation must ensure that, if set, the instruction in the stage must not enter the next stage and must not be overwritten by an instruction from the previous stage.
  - `RdFlush` (SCIF->SCAL) indicates whether the respective pipeline stage's instruction is being flushed.
    In most cases, SCAL assumes that `RdFlush` for a given in-order stage N+1 implies `RdFlush` for stage N.
    The implementation should ensure that `RdFlush` does not depend on the same stage's `WrFlush`. In general, to determine if a stage is flushing, both `RdFlush` and `WrFlush` are to be checked.
  - `WrFlush` (SCAL->SCIF) injects a flush into the processor pipeline, killing all instructions up until the given stage.
  - `RdPipeInto` (SCIF->SCAL) indicates whether the instruction ends up in the given successor stage. The ISAX field is repurposed: "stage_"+<name of next stage>. Also used to indicate port->port transitions. Not needed if there is only one successor.

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
  - A `WrPC` operation in the first stage ('next PC') is assumed to override the PC of the same stage (`RdPC` should simultaneously show the original PC).
  - `WrPC` in later stages, if latency is set to 1 in the core datasheet, only affects the next instruction.

- Spawn operations (without an attached pipeline instruction, in the dedicated 'decoupled' stage):
  - `RdMem_spawn`/`WrMem_spawn` performs a memory operation without any handling of data hazards. Behavior in case of exceptions is implementation-defined.
    - `RdMem_spawn` (SCIF->SCAL read result), `WrMem_spawn` (SCAL->SCIF write data)
    - The basic adjacent pins are shared for reads and writes.
      - `Mem_spawn_validReq`, `Mem_spawn_validResp`, `Mem_spawn_addr`, `Mem_spawn_size` behave as with regular memory operations. However, instead of `RdStall`, the acceptance condition is `ISAX_spawnAllowed`.
      - `Mem_spawn_write` (SCAL->SCIF) indicates whether the given operation is a read (`1'b0`) or a write (`1'b1`).
      - `Mem_spawn_validHandshakeResp` (optional SCIF->SCAL) indicates if a request has been accepted. Defaults to `Mem_spawn_validReq && Mem_spawn_spawnAllowed && ISAX_spawnAllowed`.
        To override, the CoreBackend#Prepare method should remove `NodeTypeTag.defaultNotprovidedByCore` from the BNode nodes.
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

It is recommended to minimize any code patching in the core's RTL. Instead, the CoreBackend class should only generate configuration files indicating the used features and, as needed, add interfacing glue logic to a separate module. The low-level extension interface would be statically integrated into the core, for instance as a fork of the original repository, greatly simplifying maintenance.

#### Example: CVA6 low-level interface
Initially, `scaiev_glue` is an empty module in CVA6 that receives all low-level interface pins for SCAIE-V. `scaiev_glue` is only instantiated if the `SCAIEV_ENABLE` definition is set. If `SCAIEV_ENABLE` is set, the core also instantiates a functional unit for all SCAIE-V custom instructions. The functional unit module is a small adapter that accepts results from SCAL, and is represented to SCAIE-V as the Execute stage.

The `CoreBackend` for CVA6 adds the actual SCIF pins to the module and adds the wiring logic to the low-level interface. For decoding, the glue module collects the RdIValid results from SCAL and sets the `scaiev_decode_isScaiev[port][<bit>]` bits depending on whether an ISAX is present, which operand registers it uses and if it uses the LSU. SCAL uses the stage status signals (isStalling / RdStall, isFlushing / RdFlush) as well as the buffer enqueue/dequeue IDs (`reqID` between Fetch and Realign, `instrqueueID` between Realign and Decode) and flushing information (`reqID_flushFrom`, `reqID_flushCount`) to pass its state in parallel to the processor pipeline.

The following table lists the pins of the low-level interface for CVA6. The full pin names are constructed as follows: scaiev_[STAGE]_[NAME](_valid, _addr, ...)

| Name | Width | Valid | Address/Size | SCAIE-V In/Out | Stage | Description |
| ---- | ----- | ----- | ------------ | -------------- | ----- | ----------- |
| wrPC | 32/64 | y | - | out | Fetch | Write PC |
| rdPC | 32/64 | - | - | in | Fetch | Read PC |
| isStalling | 1 | - | - | in | Fetch - Execute | Indicates a pipeline stall  |
| isValid | 1 | - | - | in | Issue -Execute | Indicates a valid instruction (even if stalled) |
| isFlushing | 1 | - | - | in | Fetch - Execute | Indicates a flush |
| pipeInto_scaievfu | 1 | - | - | in | Issue | Instruction issues into scaiev_fu (1) or other (0) |
| stall | 1 | - | - | out | Fetch - Execute | Stall the given stage |
| flush | 1 | - | - | out | Decode - Issue | Flush the given stage and its predecessors |
| rdInstr | 32 | - | - | in | Decode - Execute | Provides the instruction word |
| rdRS1/rdRS2 | 32/64 | - | - | in | Execute | Provides the specified register operand |
| wrRD       | 32/64 | y | - / - | out | Execute | Write destination register |
| wrRD_spawn | 32/64 | y | y / - | out | Execute | Write destination register |
| rdMem | - | y | (y / y) | out | Execute | Read from memory at specified address (address/size: memAddr, memSize)  |
| rdMem_transID | typ. 3 | y | - | out | Execute | Scoreboard index for rdMem |
| rdMem_result, rdMem_result_transID | 32/64, typ. 3 | y | - | in | Spawn | Result of read from memory |
| wrMem | 32/64 | y | (y / y) | out | Execute | Write to memory at specified address (address/size: memAddr, memSize) |
| wrMem_transID | typ. 3 | - | - | out | Execute | Scoreboard index for wrMem |
| memAddr | 32/64 | - | - / - | out | Execute | Address for the current memory operation |
| memSize | 3 | - | - / - | out | Execute | Size of the current memory operation, using the `sw`/`lw` funct3 encoding |
| mem_ready | 1 | - | - | in | Execute | Handshake response for rdMem/wrMem |
| mem_stall | 1 | - | - | out | Issue | Stall issue into LSU |
| isScaiev | 7 | - | - | out | Decode | SCAIE-V decoding flag and the use of rs1,rs2,rd, and memory. |
| decInstr | 25 | - | - | in | Decode | Decoded rs1,rs2,rs3,rd fields and address space |
| isNew | 1 | - | - | in | Execute | An new instruction has entered scaiev_fu |
| lsuValid | 1 | - | - | in | Execute | a Load/Store instruction enters the LSU (SCAIE-V  should hold any decoupled accesses) |
| hasWriteback | 1 | - | - | out | Execute | scaiev_fu should wait for writeback |
| semicoupled_(deq,reenq) | 1,1 | - | - | out | Execute | Passes multi-cycle instruction to SCAIE-V / commits result to scaiev_fu |
| transID | typ. 3 | - | - | in | Issue-Execute | Scoreboard index of the active instruction |
| transID | typ. 3 | y | - | in | Commit (all ports) | Scoreboard index of the instruction being committed |
| drop | 1 | - | - | in | Commit (all ports) | Instruction is cancelled (e.g. mispredict) |
| isFlushing | 1 | - | - | in | Scoreboard | Entire scoreboard is being cleared (e.g. exception) |
| reqID, instrqueueID | 2, typ. 3 | - | - | in | Fetch-Realign, Realign-Decode | Internal stage buffer index of the instruction |
| reqID_flushFrom, reqID_flushCount | 2, 3 |  |  | in | Fetch | Indicates the range of I$ requests that the core is flushing |
| isReplaying | 1 | - | - | in | Fetch | Indicates a 'fetch replay' by the core, if an instruction was dropped due to buffer constraints. If active, glue undoes ZOL (zero-overhead loop) jumps, ensuring  |consistency | with flushed custom register updates
| fully_unaligned | 1 | - | - | in | Realign | Indicates fully unaligned Fetch result, only half of the next instruction was read |
| pcOverride | 32/64 | y | - | out | Decode | Corrects the instruction PC in the core’s Decode stage, to be used following a wrPC without concurrent flush (e.g., ZOL). |


#### Decoding
To use the RdIValid signals from SCAL, the core backend should first request each RdIValid in its `Prepare` method. Use `SCALBackendAPI#RequestToCorePin(<BNode obj>.RdIValid, <decode stage>, <isax name>)` with the passed `SCALBackendAPI` object.

The core backend also needs to ensure the `RdIValid` interface pins reach the HDL module where they are used. For each ISAX, call `language.UpdateInterface(topModule, <BNode obj>.RdIValid.NodeNegInput(), <isax name>, <decode stage>, true, false)`, where `language` is the GenerateText object of the core backend (e.g., type `Verilog`). The `UpdateInterface` method will pass the RdIValid node down to the module specified in prior `PutNode` calls.

Then, in the decode logic, generate HDL for a logical OR across `language.CreateNodeName(BNode.RdIValid.NodeNegInput(), <decode stage>, <isax name>)` for each ISAX.

The core can also request RdIValid in later stages, if it needs to track certain hazards. For instance, if the core's branch speculation window ends in the Execute stage but an ISAX uses `WrPC` in a later stage (as listed in `op_stage_instr`, if allowed in the core datasheet), the core's Decode stage may need to be stalled until the ISAX is complete. Using `RdIValid_<isax>_<execute>`, the core backend can construct the stall condition.

Advanced: For application-class cores, which support multiple instructions running in each execution unit, the `RdAnyValid` node indicates whether any instruction of a given ISAX is currently running inside the execution unit. This is only relevant if the core supports the `WrDeqInstr, RdInStageID, RdInStageValid, WrInStageID` operations for SCAIE-V's semi-coupled mode; otherwise, SCAL defaults to running only one instruction in the execution stage/unit.