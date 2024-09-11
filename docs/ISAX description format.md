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
  SCAIE-V only processes the portions of the string corresponding to funct7, funct3 and opcode. Any other bits are ignored.
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
- `has size`: If present (regardless of value), optionally adds a 'size' interface pin to applicable interfaces. Otherwise, the size is inferred or pipelined by SCAIE-V.
- `is decoupled`: If present (regardless of value), instantiates the spawn variant of the interface (e.g. `WrRD_spawn` instead of `WrRD`). The execution latency is derived from `stage`.
  However, the interface pins are always named by the `maxStage+1` stage (`maxStage` being the highest latest stage listed in the core virtual datasheet yaml file).
- `is dynamic decoupled`: If present (regardless of value), instantiates the variable-latency decoupled variant of the interface with an implicit `validReq` pin.
- `is dynamic`: If present (regardless of value), instantiates the variable-latency semi-coupled variant of the interface with an implicit `validReq` pin. Note that the ISAX interface uses the same spawn pin names and stage numbers as with dynamic decoupled.

# Custom Registers
A custom register entry provides the following elements:
- `register`: Name of the register.
- `width`: Bit-width of the register.
- `elements`: Depth of the register.

Each custom register has several operations that can be requested in the interface schedule of ISAXes.
- `Wr<register>.addr`: The address interface for the corresponding data operation. The stage number also defines down to which stage SCAIE-V generates its data hazard handling.
- `Wr<register>.data`: The actual write interface through which data is provided.
- `Rd<register>`: The read interface.
