The ISAX description is serialized as a yaml list, and each list entry is a dictionary with a fixed set of keys.
An entry describes either an ISAX or a custom register used by one or several ISAXes.

Note that the dictionary elements of each entry must be ordered as stated here to be applied as intended. 

# Instructions
An instruction entry provides the following elements:
- `instruction`: Name of the instruction, which is used in the interface pin names.
- `tags`: A string list of tags that are relevant for the ISAX interface. Possible values:
  - `"RdStallFlush-Is-Per-ISAX"` (strongly recommended): The ISAX interface has the ISAX name in its RdStall, RdFlush pins. Required for ISAXes that use RdFlush or semi-coupled spawn. Recommended in all cases (deprecation warning if missing).
  - `"ReadResults-Are-Per-ISAX"` (strongly recommended): The ISAX interface has the ISAX name in all read node pins (RdRS1/2, RdRD, RdMem, RdInstr, RdCustomReg, etc.). Recommended in all cases. If missing, name clash issues can occur if several ISAXes use the same interface but are handled differently - e.g. semi-coupled spawn ISAXes, RdRS1 if RdRD is used in CVA5, etc.
- `mask`: Encoding of the instruction as a string of bits (MSB first), where '-' stands for don't cares / encoding bits that are not used in instruction detection.
  SCAIE-V only processes the portions of the string corresponding to funct7, funct3 and opcode. All other bit positions are ignored.
  Example: `mask: "0000000----------100-----0001011"`
- `schedule`: The interface schedule.

SCAIE-V also supports instructions without an encoding, that can continuously manipulate processor state (_always_) such as the program counter.
SCAIE-V does not generate any hazard handling for such instructions.
Entries for such instructions are as follows: 
- `always`: Name of the instruction
- `schedule`: The interface schedule. Note that the stage number is ignored for `WrRD` and custom register accesses.

## Interface schedule
The interface schedule itself is a yaml list with dictionary entries. Some conditional entries ("has ..." / "is ...") are applied if present, since the boolean value is not checked; it is recommended to set the value to true, however.
- `interface`: Name of the interface, e.g. `WrRD`, `RdMem`, etc.
- `stage`: The pipeline stage (integer) in which to instantiate the interface.
- `stages`: The pipeline stages (list of integers). Use instead of `stage` to request the same interface for several stages.
- `has valid`: If present (regardless of value), adds a 'validReq' interface pin to applicable interfaces. Otherwise, the 'validReq' value is inferred from the interface schedule. Certain coupled operations such as WrCustomReg interpret 'validReq'=0 from the ISAX as cancellation, if contrary to the interface schedule.
- `has validResp`: If present (regardless of value), optionally adds a 'validResp' interface pin to applicable interfaces.
- `has addr`: If present (regardless of value), optionally adds an 'addr' interface pin to applicable interfaces. Otherwise, the address is inferred or pipelined by SCAIE-V.
- `has size`: Note: Not supported yet, most cores still only infer the size based on the funct3 width encoding of the standard Load/Store instructions. If present (regardless of value), optionally adds a 'size' interface pin to applicable interfaces. Otherwise, the size is inferred or pipelined by SCAIE-V.
- `is decoupled`: If present (regardless of value), instantiates the spawn variant of the interface (e.g. `WrRD_spawn` instead of `WrRD`). The execution latency is derived from `stage`.
  However, the interface pins are always named by the `maxStage+1` stage (`maxStage` being the highest latest stage listed in the core virtual datasheet yaml file).
- `is dynamic decoupled`: If present (regardless of value), instantiates the variable-latency decoupled variant of the interface with an implicit `validReq` pin.
- `is dynamic`: If present (regardless of value), instantiates the variable-latency semi-coupled variant of the interface with an implicit `validReq` pin. Note that the ISAX interface uses the same spawn pin names and stage numbers as with dynamic decoupled.

## Example instruction
An indirect jump instruction, which reads its instruction word and rs1 contents in the scheduled stage 2, reads from memory with an explicit address and writes the program counter in stage 3. In cores with RdMem latency 0 (= stage is stalled until the read result is available), WrPC could also be done in stage 2, combinationally with the read result.
```yaml
- instruction: ijmp
  mask: "-----------------010-----1111011"
  tags: ["RdStallFlush-Is-Per-ISAX", "ReadResults-Are-Per-ISAX"]
  schedule:
    - interface: RdInstr
      stage: 2
    - interface: RdRS1
      stage: 2
    - interface: RdMem
      stage: 2
      has addr: 1
    - interface: WrPC
      stage: 3
```
Corresponds with the following ISAX-side HDL interface, assuming a 32bit core:
```verilog
  input  [31:0] RdInstr_ijmp_2_i,
                RdRS1_ijmp_2_i,
                RdMem_ijmp_2_i,
  output [31:0] RdMem_addr_ijmp_2_o,
  output [31:0] WrPC_ijmp_3_o
```

# Custom Registers
A custom register entry provides the following elements:
- `register`: Name of the register.
- `width`: Bit-width of the register.
- `elements`: Depth of the register.

Each custom register has several operations that can be requested in the interface schedule of ISAXes.
- `Wr<register>.addr`: The address interface for the corresponding data operation. The stage number also defines down to which stage SCAIE-V generates its data hazard handling. Must be consistent across instructions.
- `Wr<register>.data`: The actual write interface through which data is provided.
- `Rd<register>`: The read interface.

In the core datasheet, the custom register operations have be within the constraints of RdCustomReg and WrCustomReg.addr/.data. Support for earlier reads/writes (e.g., zero-overhead loops) is still WIP.

## Example instructions with custom registers
Specifies two 1-element registers, ADDR and INCR, and two instructions accessing them. The setup instruction writes the ADDR and INCR registers and reads rs1, rs2. The lw_inc instruction reads the two registers, reads from memory, updates the ADDR register, and writes a result to rd.

Note that the WrINCR.addr, WrADDR.addr ports are all in stage 1, marking it as the issue stage for the two custom registers. However, as these are single registers, no actual address interface will be generated.

```yaml
- register: ADDR
  width: 32
  elements: 1
- register: INCR
  width: 32
  elements: 1
- instruction: setup
  mask: "0000001----------000-----0001011"
  tags: ["RdStallFlush-Is-Per-ISAX", "ReadResults-Are-Per-ISAX"]
  schedule:
    - interface: RdRS1
      stage: 2
    - interface: RdRS2
      stage: 2
    - interface: WrINCR.addr
      stage: 1
    - interface: WrINCR.data
      stage: 2
      has valid: 1
    - interface: WrADDR.addr
      stage: 1
    - interface: WrADDR.data
      stage: 2
      has valid: 1
- instruction: lw_inc
  mask: "00000100000000000010-----0001011"
  tags: ["RdStallFlush-Is-Per-ISAX", "ReadResults-Are-Per-ISAX"]
  schedule:
    - interface: RdMem
      stage: 2
      has valid: 1
      has addr: 1
    - interface: RdINCR
      stage: 2
    - interface: RdADDR
      stage: 2
    - interface: WrRD
      stage: 3
    - interface: WrADDR.addr
      stage: 1
    - interface: WrADDR.data
      stage: 2
      has valid: 1
```
Corresponds with the following ISAX-side HDL interface, assuming a 32bit core:
```verilog
  input  [31:0] RdRS1_setup_2_i,
                RdRS2_setup_2_i,
                RdADDR_lw_inc_2_i,
                RdMem_lw_inc_2_i,
                RdINCR_lw_inc_2_i,
  output [31:0] WrADDR_setup_2_o,
  output        WrADDR_validReq_setup_2_o,
  output [31:0] WrINCR_setup_2_o,
  output        WrINCR_validReq_setup_2_o,
  output [31:0] RdMem_addr_lw_inc_2_o,
  output        RdMem_validReq_lw_inc_2_o,
  output [31:0] WrADDR_lw_inc_2_o,
  output        WrADDR_validReq_lw_inc_2_o,
  output [31:0] WrRD_lw_inc_3_o
```

