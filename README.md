# SCAIE-V
## Welcome to SCAIE-V 2.0: An improved Open-Source SCAlable Interface for ISA Extensions for RISC-V Processors!

## What is SCAIE-V?
SCAIE-V is a: 
- Portable =  supports different microarchitectures
- Scalable = hardware cost scales with the ISAX requirements
- Flexible = supports simple and advanced features (custom control flow, decoupled, multi-cycle instructions, memory instructions)

interface for custom instructions for RISC-V processors. 

## Why 2.0?
The original SCAIE-V repo was updated to support more features, and now it becomes SCAIE-V 2.0. 

## Which cores do you support?
We currently support
- VexRiscv (https://github.com/SpinalHDL/VexRiscv)
- ORCA (https://github.com/cahz/orca)
- Piccolo (https://github.com/bluespec/Piccolo)
- PicoRV32 (https://github.com/YosysHQ/picorv32)

These cores provide different configurations. While testing & evaluating our tool we used the following setup: 
| Core     | Nr. of pipeline stages | Interface |
|----------|------------------------|-----------|
| ORCA     | 5                      | AXI       |
| VexRiscv | 5                      | AHB       |
| Piccolo  | 3                      | AXI       |
| PicoRV32 | non-pipelined          | Native    |

## What is the design structure?
Let us consider that user wants to develop a new instruction  ISAX1, which must be integrated in the VexRiscv core. The design will have the following hierarchy: 

ISAX1 <--> CommonLogicModule (SCAL) <--> Top module of SCAIE-V extended Core

Currently SCAIE-V generates the  CommonLogicModule (SCAL)  and updates the design files of the core. User must implement the wrapper to connect these submodules. ISAX1 will only be connected to the interface of the CommonLogicModule (SCAL) .


## Which operations are supported in SCAIE-V? 
| Operation     | Meaning | Bitwidth |
|----------|------------------------|-----------|
| RdRS1/2     | Reads operands | 32       |
| RdPC | Reads program counter *                      | 32       |
| RdInstr  | Reads Instruction                      | 32       |
| RdIValid | Status bit informing the custom logic that a certain pipeline stage currently contains an instruction of type X          | 1    |
| WrRD     | Write register file * | 32       |
| Wr/RdMem     | Load/Store operations * | 32       |

\* optionally, the user may request addr. and valid bit for this interface. For WrPC, and WrRD the user can not request an addr. signal.


## How can I use the SCAIE-V tool for my custom instructions?
Let us consider the following example. The user wants to implement a module which conducts load/stores using custom addresses. He wishes to have a base value for the custom address and then increment/decrement it after/before each load/store. Therefore, he will use an internal register for storing the custom address: `custom_addr`. In order to load the base address into the internal register, he wants to implement a SETADDR custom instruction. For this instruction, he needs to know in which clock cycle he may read the operands from the register file.  He also needs to know when there is a SETADDR instruction in the pipeline, to update the `custom_addr` register. 

### Step 1: 
First, the user has to read the metadata of the core (=when is it allowed to read/update the core's state). He may either read this info in the *Cores* folder, or run the following lines (java): 
```
SCAIEV shim = new SCAIEV();
shim.PrintCoreNames(); // prints the names of the supported cores
shim.PrintCoreInfo("VexRiscv_5s"); // prints metadata for a specific core (using the name from previous command)
```
From the metadata output of one node, only two values are relevant:
- the first value = the earliest clock cycle in which the user may read/update this data
- the third value = the latest clock cycle in which the user may read/update this data

The rest of the values are currently used in internal research projects. 

### Step 2: 
Using the metadata in Step 1, the user decides in which clock cycles to read/update core's state. The custom ISAX module has to be designed based on this information. In our simple example, it would be something like: 
```verilog
module SETADDR (
    input        clk_i,
    input        rdIValid_SETADDR_2_i, 
    input [31:0] rdRS1_2_i, 
   //..value of custom regs as outputs used by other ISAXes. Inputs from other ISAXes to update custom_addr after/before a load/store
   ); 
    reg [31:0] custom_addr; 
    always @(posedge clk_i) begin 
        if(rdIValid_SETADDR_2_i)
            custom_addr <= rdRS1_2_i ;
       // rest of the logic for updating custom_addr in case of load/store ISAXes
    end 
endmodule 
```
### Step 3: 
The third step implies generating the custom instructions interface using the SCAIE-V tool. Let us consider that the user decided to read operands in the third cycle (numbering starts at 0). He/She does not have to modify anything in the core, but just let SCAIE-V do the work: 
```
SCAIEV shim = new SCAIEV();
SCAIEVInstr setaddr  = shim.addInstr("SETADDRGEN","-------", "000", "0001011", "I");  
setaddr.PutSchedNode(FNode.RdRS1, 2);  
setaddr.PutSchedNode(FNode.RdIValid, 2); // valid bit for updating the custom_addr register
shim.Generate("VexRiscv_5s"); // generates all the code
```
The files of the 5 stage VexRiscv core will be modified so that it supports the new interface. (to set the core, use: "VexRiscv_5s", "VexRiscv_4s", "PicoRV32", "ORCA")

## How can I try it out fast? 

There is a DEMO that you can run. It contains an ISAX module which requires multiple SCAIE-V interfaces and simply generates some patterns. These patterns are then checked within a simple testbench. In our evaluation flow we used multiple real ISAXes and evaluated them using cocotb. In this DEMO the ISAX module is just there to show how to use the SCAIE-V tool. For this: 
- run the DemoTest_VexRiscv5.java class. This will update the VexRiscv source files and generate CommonLogicModule (SCAL) 
- generate verilog from the VexRiscv files 
- use the wrapper in the DemoFiles folder (testbench.v)
- simulate the testbench module.  

The DemoFiles folder also contains already generated VexRiscv verilog code and CommonLogicModule.sv as reference. 

## A more detailed specification of the SCAIE-V Interface 
If you want to design your own ISAX, you need to comply to the SCAIE-V Specification. In the following are defined its main characteristics. 
The specification bellow defines the interface between the user-designed ISAX Module and the SCAIE-V Extended Core. Apart from the interfaces below, SCAIE-V is now capable of instantiating internal states on its own. This is, however, covered in a separate subsection below. 

| Operation     | Allowed adjacent signals | Bitwidth | Characteristics 
|----------|------------------------|-----------|-----------|
| RdRS1/2  | None | 32b       | One interface per stage for all ISAXes. If the current stage is flushed, this signal is not set to zero. User must check RdFlush & RdStall signals to evaluate the validity of this data. Datahazards should already be resolved (RdRS must point to the current data).
| RdPC | None | 32b       | One interface per stage for all ISAXes. If the current stage is flushed, this signal is not set to zero. User must check RdFlush & RdStall signals to evaluate the validity of this data. If WrPC has latency 0 in stage 0, do NOT create a combinational path between RdPC and WrPC.
| RdInstr  | None | 32b       |  One interface per stage for all ISAXes. If the current stage is flushed, this signal is not set to zero. User must check RdFlush & RdStall signals to evaluate the validity of this data.
| RdIValid | None | 1b    |   One interface per stage for all ISAXes. If the current stage is flushed, this signal is set to 0.
| WrPC | Valid | 32b data, 1b valid | One interface per stage per ISAX. SCAL always multiplexes among them based on the opcode of the current instruction. Avoid comb. logic between WrPC and RdPC in stage 0. User does not have to set WrFlush in case of WrPC. This is done automatically within SCAL.  
| WrRD     | Valid | 32b data, 1b valid  | One interface per ISAX (no multiplexing across multiple ISAXes required). Data and valid bit are always together (in the same stage). Valid is evaluated when there is no flushing and no stalling.   
| Wr/RdMem     | Valid, Addr | 32b data, 32b addr, 1b valid |  One interface (either Rd or Wr, not both) per ISAX (no multiplexing across multiple ISAXes required). Data and valid bit are always together (in the same stage). Valid is evaluated when there is no flushing and no stalling. Currently, custom Wr/RdMem addr is not evaluated against addr alignment and is assumed by SCAIE-V to be correct. There are separate interfaces for read and write (RdMem, RdMem_addr, RdMem_valid, WrMem, WrMem_addr, WrMem_valid). There is only one memory resource available in the memory stage. This means one ISAX may either read OR write, but not do both. For common (non-decoupled instructions), the transfer length is based on the instruction bits 13:12. |
| RdFlush     | None | 1b |  One interface per Stage for all ISAXes. RdFlush in stage N flushes only stage N. No combinational logic is required within ISAX to flush previous stages. SCAIE-V will make sure the RdFlush signal is also set in previous stages when needed. A RdFlush = 1 in stage N can also imply that the current instruction in stage N is not valid and must be ignored (is not allowed to update any internal state). Whether the flush is 1 due to a branch or because the current instruction is not valid does not matter, for the ISAX module it has the same semantics: in this stage, in this cycle ignore the current instruction.  |
| WrFlush     | None | 1b |  One interface per Stage for all ISAXes. WrFlush in stage N implies for the SCAIE-V interface that all previous stages must also be flushed. However, ISAX module does not have to set the WrFlush in previous stages too. SCAIE-V "spreads" the WrFlush signal in previous stages (for exp. WrFlush in stage 0 = WrFlush stage 1 OR WrFlush stage 2 OR...). No combinational path is allowed within ISAX between WrFlush and RdFlush. If ISAX sets WrFlush, SCAIE-V makes sure RdFlush is also set. (implementation Info: SCAL does NOT compute RdFlush for ISAX based on WrFlush of ISAX. This is done by SCAIE-V within the core. This means, within the core SCAIE-V must implement the following logic: "RdFlush = Flush_sig_of_Core or WrFlush", SCAL only forwards the RdFlush from core directly to ISAX). |
| Rd/WrStall     | None | 1b |  Exactly the same concepts apply as for Rd/Wr flush, just that the semantics of Stall is different. |
| WrRD (decoupled) |Valid, Addr, Valid Response |  32b Data,1b Valid, 5b Addr, 1b Valid Response,1b Commited done, 5b Commited Address | One interface per ISAX with decoupled execution. Addr comes from instruction field. When ISAX commits, it must return a valid signal (like a trigger), address signal and payload. Valid and addr can be generated within SCAL. On the SCAIE-V interface there is also a valid response Signal which may be used if needed by ISAX. If the user does not want the SCAIE-V datahazard mechanism (scoreboard), 2 additional signals on the interface can be used (commited..). These inform if a result was commited and if yes, to which address (as there might be multiple results in the que). Other signals on the ISAX interface related to this functionality are deprecated and will be removed in next releases. |
| Mem (decoupled) |Valid, Addr, Valid Response  | 32b Data,1b Valid, 32b Addr, 1b Valid Response | One interface per ISAX with decoupled execution. When ISAX commits, it must return a valid signal (like a trigger), address signal and payload. Currently valid and addr can NOT be generated within SCAL. On the SCAIE-V interface there is also a valid response Signal which may be used if needed by ISAX. For the moment, the priority of multiple concurrent accesses is decided by SCAL randomly (when RTL is generated)|

## Which "decoupled execution" modes are supported? 

We currently support three different modes to achieve decoupled execution: 
  - Continuous: ISAX may read/write a state at any point in time, independent of the current instruction in the pipeline. 
  - Decoupled: ISAX may update the state at a later point in time, while the core processes in parallel new instructions. To use this variant, set the instruction's parameter "decoupled" to true (or in .yaml format write "is decoupled: 1").
  - Decoupled with stall: ISAX may update the state at a later point in time, while the core is stalled.  This is the default version, so you may leave the default settings as they are. Currently this is supported only for Register File Writes, not for memory. 

## From which stage is an interface considered decoupled? 
This subsection does NOT describe the "Continuous" mode. If you are interested for that strategy, go to the next subsection. 
Definitions: 
- max (stage of core) = from all nodes which define a latest parameter, max stage = max(latest) 
- spawn stage = max+1 

From which stage is it considered to be decoupled:
- WrRd - max + 1 
- WrMem/RdMem - earliest of WrMem/RdMem + 1. Yet, the interface contains max +1 in its naming
- internal state - max+1

## How to use the "Continuous" decoupled mode? 
In this strategy, a write may happen at any given time, and this write is not associated with any instruction. Yet, it must still be synchronized with stall-flush mechanism. Hence, if the last stage is stalled, a continuous write is also stalled, and the write is not committed. ISAX must hold that valid bit stable until stalling is removed. If another write happens in the pipeline, the "continuous" variant has priority. Be aware that a "continuous" mechanism for WrRD uses as destination the address given in the instruction field. Hence, a  write may happen, but the destination address is given by the current instruction in the pipeline. 

This was currently tested only by using the yaml file as input. To use this strategy, you must define an "always" block in yaml like: 
``` 
- always: my_continous_write
  schedule:
    - interface: RdPC
      stage: 0
    - interface: WrPC
      stage: 0
      has valid: 1
``` 

## Can I disable some logic within SCAL for decoupled execution? 
Yes. The SCAL class has some parameters that may be set: 
```
public boolean SETTINGWithScoreboard = true; // true = Scoreboard instantiated within SCAL,  false = scoreboard implemented within ISAX by user   
public boolean SETTINGWithValid = true;      // true = generate shift register for user valid bit within SCAL. False is a setting used by old SCAIEV version, possibly not stable. If false, user generates valid trigger 
public boolean SETTINGWithAddr = true;		 // true = FIFO for storing dest addr instantiated within SCAL, If false, user must store this info and provide it toghether with result. 
public boolean SETTINGwithInputFIFO = true;  // true =  it may happen that multiple ISAXes commit result, input FIFO to store them instantiated within SCAL. false = no input FIFOs instantiated
```

## What do I have to do to support internal states? 
This is currently supported only in yaml file (see TestMe.yaml & AutomaticDemoTest.java, which reads the yaml and starts SCAIE-V tool based on it). 
In the TestMe.yaml, define in the beginning the internal state, with its name, width and depth:
```
- register: Myreg
  width: 32
  elements: 1
```
Make sure the name does not have any underlines or spaces. 
In the instruction using this state, mention the scheduling of RdMyreg and WrMyreg:
``` 
- instruction: MY_INSTRUCTION
  mask: "1100000----------010000--0110011"
  schedule:
    - interface: RdMyreg
      stage: 2
	- interface: WrMyreg.data
      stage: 3
	- interface: WrMyreg.addr
      stage: 1
```
The WrMyreg interface must provide the address in the earliest stage in which a read is allowed. Yet, the result may be returned also in later stages. Hence, data and address could be in different stages and their schedule is presented separately. Based on the above specification, the earliest stage in which a read is allowed is stage 1, the MY_INSTRUCTION reads the state in stage 2 and returns a result in stage 3. Address signal is mandatory for arrays. 



## What do I have to consider when extending SCAIE-V for new cores? 
Here are some examples that must be considered when adding SCAIE-V to a core: 
- Generate SCAIE-V IOs
- make sure no illegal instruction is generated when an ISAX is in the pipeline
- make sure no result is commited when user valid bit is not set & ensure that core's data hazard mechanism does not take the result if valid = 0
- make sure no illegal addr exception is generated when memory transfer uses user addr 
- make sure WrPC in later stages does not happen while Rd/WrMem results in prev stages were commited
- no RdIValid required on core's side. This is handled by SCAL 
- no RdInstr/RdRS/RdPC required in stages where not present (within core). This is handled by SCAL 

## Which versions of the cores were used for testing? 
- Piccolo: RV32ACIMU_Piccolo_verilator 
- PicoRV32: core with default params and interface (no AXI..) 
- Vex: VexRiscvAhbLite3 version from demo folder
- ORCA: please check the Demo folder where it is instantiated

For ORCA Verilog was generated for testing & area evaluation: 
```
docker run -it -t   -v $(pwd):/src   -w /src    hdlc/ghdl:yosys   yosys -m ghdl -p 'ghdl -fsynopsys --std=08  all_orca_files_pasted.vhd -e orca; write_verilog ORCAMem.v'
```

## How is the tool structured? Main concepts: 
- **ISAX**: instruction set architecture extension (within tool denotes a new instr)
- **SCAL**: module between core and ISAX
- **node/operation**: these 2 terms are currently used within tool to denote one interfance-bundle. So for exp. WrRD is a SCAIE-V node which writes the register file. WrRD_valid is a SCAIE-V node which signals whether the result may be commited to the register file or not. 
- **AdjacentNode** - while WrRD is a main node for writing register file, signals like address and valid are considered adjacent nodes (adjacent to WrRD). WrRD is considered to be a parent of WrRD_addr and WrRd_valid
- **FNode/BNode**: SCAIE-V is made of multiple interfaces between ISAX and core. One such interface is for writing data to RegFile. Another one is for writing the memory, and so on. Only for writing the memory, the interface is actually made of multiple signals: data, addr, valid. FNode in this case is WrMem. BNode also includes the adjacent signals (addr, valid). BNode may also include signals required between SCAL and core, that are not visible to user. 
- **frontend/backend**: frontend is used by all cores, backend is more core-specific 
- **op_stage_instr**: hash map containing all operations required by user, with their stage number in which they were required and the instructions for each they were required. This is used across the entire tool to generate logic 
- **instrSet/ISAXes** - while op_stage_instr has as value only a string of the instruction name, this hashmap contains all metadata for each instruction. It has as key a String with the instruction name, and as value a SCAIEVInstr object. This is also an important hash map used across the tool. It's a database which stores all requirements for each instruction. For exp, op_stage_instr does not contain info like: for instr ISAX_new, does the user require a valid signal for WrRd? 
- **file parsing**: currently, the tool greps for certain words and can replace the line before/after the grep-ed text. It's also able to replace the line with grep. 

**Package: scaiev** 
Class: SCAIEV - This is the "glue" of the tool. This class is instantiated within demo. User adds new instructions through "addInstr" function. After adding all instructions, the "Generate" function is called and this generates the entire SCAIE-V logic. This means, it instantiates the SCAL class to generate the middle layer and then instantiates the correct backend core to update the core's logic. 

**Package: scaiev.frontend**
Class SCAIEVNode - it's the "core" of a SCAIE-V node. It defines the main properties of a SCAIE-V node and this is instantiated then within FNode and BNode classes to define actual interface nodes like WrRD or WrMem. This class also defines all possible adjacent signals within AdjacentNode enum. 
Class FNode - contains main nodes for all interfaces, without their adjacent nodes (valid request, valid response, address...). 
Class Scheduled - defines the schedule desired by the user for a specific node. Schedule = in which cycle the user wants this node (for WrRD for exp, in which cycle the user wants to write the result to register file). This class also stores info like: for this node, which adjacent signals are required by user? Each node desired by user must have a schedule. 
Class SCAIEVInstr -  it's a class corresponding to a single new instruction. For each new instruction, this object stores its name, its encoding and all the nodes (interfaces) which it requires. For each such node (interface), a "Schedule" object is instantiated to store in which cycle this interface is required.
Class SCAL - generates SCAL logic (between core and ISAX).  
Class SCALState - this generates a module for all ISAX new registers. This new module is instantiated within the SCAL Module. The interface is similar to a WrRD interface, just that the address must be given by user. 

**Package: scaiev.backend**: this contains BNode and all classes for each supported core 
Clas BNode contains all adjacent nodes of FNode. It also contains nodes that represent interfaces between core and SCAL. These are not visible to user.
Classes Piccolo/ORCA/picorv32/VexRiscv - each of this class updates the design files of the core to support the new instructions. 

**Package: scaiev.util** - package containing classes able to generate text/update files 
Class GenerateText - extended by all language classes. It contains "schelet" for all languages. It uses a dictionary that is then defined in each specific language class. Based on this dictionary it creates text which is required by all languages, like signal name for a node.
Class FileWriter - this class is able to parse/update files. For exp. function "UpdateContent" is useful to update a file. It has 3 parameters: name of file to be updated, the "grep" text which must be searched in order to make the update. An object of type ToWrite which contains the information about the text to be added. 
Class ToWrite - this stores information about the new text to be added within the file. "text" is the new String to be added; "prereq" is a boolean and if it's set to true it implies that the tool is not allowed to add "text", unless "prereq_text" was already seen once during parsing; "before" is a boolean saying that "text" must be added before "grep"; "replace" is a boolean which states that "text" will replace "grep". 
Package: scaiev.coreconstr - this package contains metadata of cores. This metadata is stored in yaml files in folder "Cores". CoreDatab class reads all these yaml files and parses this info so that each core becomes an object of "Core" class. Hence, "Core" class stores the metadata for one core.


## What is the current status of the project? 
The project is quite new and we are constantly working on improving it & testing it with different configurations. We already evaluated multiple configurations through automatic testing (cocotb). 

## How can I cite this work? 
You can cite the following paper, which used the first SCAIE-V version of the tool:
Mihaela Damian, Julian Oppermann, Christoph Spang, Andreas Koch, "SCAIE-V: An Open-Source SCAlable Interface for ISA Extensions
for RISC-V Processors"

## Do you have further questions?
For any questions, remarks or complaints, you can reach me at  damian@esa.tu-darmstadt.de. :) 
