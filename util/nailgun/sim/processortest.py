import os
import struct
import cocotb
from cocotb.clock import Clock
from cocotb.triggers import Timer, RisingEdge, ReadOnly, Event, with_timeout, First
from cocotb.queue import Queue
from amba import AXI4Slave
from bram import BRAMSlave
from simplebus import SimpleBusSlave
from memutil import HierarchicalMemView, BytearrayMemView
from TestLoader import TestLoader
from trace.instr_trace import TracedInstr
from testutil import dump_32bithex, test_envarg_true, get_envarg_or
import clint
import disas
from UARTPrinter import UARTPrinter

class ConfigException(Exception):
    pass
class InputBinaryException(Exception):
    pass
class CoreExceptionException(Exception):
    pass
class ResultMismatchException(Exception):
    pass
class TraceMismatchException(Exception):
    pass

@cocotb.coroutine
def clock_print(clk):
    while True:
        yield RisingEdge(clk)
        print ("-", flush=True)

class ProcessorTest:

    def __init__(self, dut, CLK_PERIOD, SAMPLE_DELAY, ASSIGN_DELAY, TIMEOUT_PERIODS, core_trace_queue: Queue[TracedInstr], iss_trace_queue: Queue[TracedInstr],
                 env=os.environ):
        self.dut = dut
        self.CLK_PERIOD = CLK_PERIOD
        self.SAMPLE_DELAY = SAMPLE_DELAY
        self.ASSIGN_DELAY = ASSIGN_DELAY
        self.TIMEOUT_PERIODS = TIMEOUT_PERIODS
        self.core_trace_queue = core_trace_queue
        self.iss_trace_queue = iss_trace_queue
        self.clk = dut.clk
        self.rst = dut.rst
        self.rst.setimmediatevalue(1) # High active reset
        self.trap = dut.trap if ("HAS_TRAP_PIN" in env) else None

        self.PRINT_IMEM = test_envarg_true(env, "PRINT_IMEM")
        self.PRINT_DMEM = test_envarg_true(env, "PRINT_DMEM")
        self.PRINT_BRAM = test_envarg_true(env, "PRINT_BRAM")
        self.PRINT_AXI = test_envarg_true(env, "PRINT_AXI")
        self.PRINT_ISS = test_envarg_true(env, "PRINT_ISS")
        self.CORE_NAME = env["CORE_NAME"]
        self.ISAX_YAMLS = env["ISAX_YAML"].split(';')
        self.RESET_CYCLES = 20
        self.RESET_CLKGATE_CYCLES_PRE = int(get_envarg_or(cocotb.plusargs, "RESET_CLKGATE_CYCLES_PRE", "0"))
        self.RESET_CLKGATE_CYCLES_POST = int(get_envarg_or(cocotb.plusargs, "RESET_CLKGATE_CYCLES_POST", "0"))

        for isax_yaml in self.ISAX_YAMLS:
            disas.register_isax_yaml(isax_yaml)
        # Optionally you can manually add patterns like so:
        # disas.register_isax_patterns({
        #     'HW_CTX_LOAD':  "0000000----------000-----0001011",
        #     'HW_CTX_STORE_ONLY_SWITCH_BACK': "0000000----------101-----0001011",
        #     'HW_SCHED_SET_FIRST_CTX_ID': "0000000----------001-----0001011",
        #     'HW_SCHED_ADD_RDY_TASK': "0000000----------011-----0001011",
        #     'HW_SCHED_RM_RDY_TASK': "0000000----------100-----0001011",
        #     'HW_SCHED_NEXT_TASK': "0000000----------111-----0001011",
        # })

        self.NUM_BUSSI = int(env["NUM_BUSSI"])
        self.memsi = []
        for i_bussi in range(self.NUM_BUSSI):
            bussi_type = env["BUSSI%d_TYPE" % i_bussi]
            bussi_signame = env["BUSSI%d_SIGNAME" % i_bussi]
            match bussi_type:
                case "AXI4":
                    self.memsi.append(AXI4Slave(dut, bussi_signame, self.clk, HierarchicalMemView([]),
                                                SAMPLE_DELAY=SAMPLE_DELAY, ASSIGN_DELAY=ASSIGN_DELAY,
                                                big_endian=False, enable_prints=self.PRINT_AXI))
                case "BRAM":
                    self.memsi.append(BRAMSlave(dut, bussi_signame, self.clk, HierarchicalMemView([]),
                                                SAMPLE_DELAY=SAMPLE_DELAY, ASSIGN_DELAY=ASSIGN_DELAY,
                                                big_endian=False, enable_prints=self.PRINT_BRAM))
                case "Simple":
                    self.memsi.append(SimpleBusSlave(dut, bussi_signame, self.clk, HierarchicalMemView([]),
                                                     SAMPLE_DELAY=SAMPLE_DELAY, ASSIGN_DELAY=ASSIGN_DELAY,
                                                     big_endian=False, enable_prints=self.PRINT_BRAM))
                case _:
                    raise ConfigException("Unknown BusSI type " + bussi_type)

        #NOTE: Expects that IMEM and DMEM are not the same memories (i.e. on a different bus or with disjoint address ranges)

        self.IMEM_BUSIDX=[int(idxstr) for idxstr in env["IMEM_BUSIDX"].split(',')]
        self.IMEM_BASE=int(env["IMEM_BASE"], 16)
        self.EXCEPTION_BASE = int(env["EXCEPTION_BASE"], 16) if ("EXCEPTION_BASE" in env) else None

        self.DMEM_BUSIDX=[int(idxstr) for idxstr in env["DMEM_BUSIDX"].split(',')]
        self.DMEM_BASE=int(env["DMEM_BASE"], 16)
        self.DMEM_SIZE=int(env["DMEM_SIZE"], 16)

        self.CTRL_BUSIDX=[int(idxstr) for idxstr in env["CTRL_BUSIDX"].split(',')]
        self.CTRL_BASE=int(env["CTRL_BASE"], 16)
        self.CTRL_RESULTS_OFFS=int(env["CTRL_RESULTS_OFFS"], 16) if ("CTRL_RESULTS_OFFS" in env) else 0x20000

        self.IMEM_SIZE = 0x10000000
        if self.DMEM_BASE > self.IMEM_BASE:
            self.IMEM_SIZE = self.DMEM_BASE - self.IMEM_BASE
        if self.CTRL_BASE > self.IMEM_BASE:
            self.IMEM_SIZE = min(self.IMEM_SIZE, self.CTRL_BASE - self.IMEM_BASE)
        self.CTRL_RESULTS_SIZE=self.IMEM_SIZE

        TESTPROG = env["TESTPROG"]
        EXPECTED = get_envarg_or(env, "EXPECTED", None)

        self.allowSpeculativeReads = test_envarg_true(env, "ALLOW_SPECULATIVE_READS")

        self.uart_printer = UARTPrinter()

        self.expected_data = bytearray()
        if EXPECTED is None:
            pass
        elif EXPECTED.endswith(".bin"):
            with open(EXPECTED, "rb") as f:
                self.expected_data = bytearray(f.read())
                fill_to_4align = ((len(self.expected_data) + 3) & ~3) - len(self.expected_data)
                self.expected_data += bytearray([0] * fill_to_4align)
        else:
            with open(EXPECTED, "r") as f:
                for line in f:
                    line_lstrip = line.lstrip()
                    if len(line_lstrip) != 0 and line_lstrip[0] != '#':
                        self.expected_data += bytearray(int(line_lstrip, 16).to_bytes(4, byteorder='little'))

        self._event_irq = Event('core_irq')

        self.testStarted = False

        self.instr_mem = bytearray()
        instr_memview = BytearrayMemView(self.instr_mem, 0, self.IMEM_SIZE, self.IMEM_BASE, read_cb=self.check_instr_read, auto_resize=True)
        for busidx in self.IMEM_BUSIDX:
            self.memsi[busidx].memview.children.append(instr_memview)

        self.data_mem = bytearray(self.DMEM_SIZE)
        self.past_speculative_reads = set()
        data_memview = BytearrayMemView(self.data_mem, 0, self.DMEM_SIZE, self.DMEM_BASE, read_cb=self.check_data_read, write_cb=self.check_data_write)
        for busidx in self.DMEM_BUSIDX:
            self.memsi[busidx].memview.children.append(data_memview)

        ctrl_resdata_mem = bytearray()
        ctrl_memview = BytearrayMemView(ctrl_resdata_mem, 0, self.CTRL_RESULTS_SIZE, self.CTRL_BASE, read_cb=self.check_ctrl_read, write_cb=self.check_ctrl_write, auto_resize=True)
        for busidx in self.CTRL_BUSIDX:
            self.memsi[busidx].memview.children.append(ctrl_memview)

        elfLoader = TestLoader(dut._log, [instr_memview, data_memview], [(self.IMEM_BASE,self.IMEM_BASE+self.IMEM_SIZE),(self.DMEM_BASE,self.DMEM_BASE+self.DMEM_SIZE)])
        self.resdata_location = elfLoader.load_test_case(TESTPROG+".elf")

        resdata_allowed_max_size = 0
        if self.resdata_location is not None:
            if self.resdata_location >= self.DMEM_BASE and self.resdata_location < self.DMEM_BASE + self.DMEM_SIZE:
                resdata_allowed_max_size = (self.DMEM_BASE + self.DMEM_SIZE) - self.resdata_location
                self.resdata_memview = data_memview
            elif self.resdata_location >= self.CTRL_BASE and self.resdata_location < self.CTRL_BASE + self.CTRL_RESULTS_SIZE:
                resdata_allowed_max_size = (self.CTRL_BASE + self.CTRL_RESULTS_SIZE) - self.resdata_location
                self.resdata_memview = ctrl_memview
            else:
                raise InputBinaryException("_test_resdata symbol points outside the DMEM range")
        else:
            self.resdata_location = self.CTRL_BASE + self.CTRL_RESULTS_OFFS
            self.resdata_memview = ctrl_memview
            resdata_allowed_max_size = (self.CTRL_BASE + self.CTRL_RESULTS_SIZE) - self.resdata_location
        if resdata_allowed_max_size < len(self.expected_data):
            raise InputBinaryException("Expected results file is larger than the physical memory section")

        # Create and register a CLINT
        self.CLINT_BASE = int(env["CLINT_BASE"], 16) if ("CLINT_BASE" in env) else 0x40000000
        self.CLINT_BUSIDX = [int(idxstr) for idxstr in env["CLINT_BUSIDX"].split(',')] if ("CLINT_BUSIDX" in env) else self.DMEM_BUSIDX
        clint_memview = clint.start_clint(dut, self.clk, self.CLINT_BASE, self.CLINT_BUSIDX)
        if clint_memview:
            for busidx in self.CLINT_BUSIDX:
                self.memsi[busidx].memview.children.append(clint_memview)

        if self.CORE_NAME == "NaxRiscv":
            from NaxWhiteBox import NaxWhiteBox
            naxWhiteBox = NaxWhiteBox(dut, self.CLK_PERIOD,
                                      disasm=disas.disassemble,
                                      defines_path=os.path.abspath(os.path.join("..", self.CORE_NAME, "nax.h")),
                                      gem5_output_path="gem5_output.log")
            cocotb.start_soon(naxWhiteBox.run(self.clk))

        #Append to end of instruction memory, needed as cores tend to prefetch instructions
        self.instr_mem += bytearray(min(self.IMEM_SIZE-len(self.instr_mem),4*32))

    async def run(self, print_clk):
        clkdriver = Clock(self.clk, self.CLK_PERIOD, units='ps')
        assert(self.RESET_CYCLES > self.RESET_CLKGATE_CYCLES_PRE)
        if print_clk:
            cocotb.start_soon(clock_print(self.clk))

        # Reset the core
        cocotb.start_soon(clkdriver.start(self.RESET_CYCLES - self.RESET_CLKGATE_CYCLES_PRE))
        self.dut._log.info("Setting rst")

        self.rst.value = 1
        await Timer(self.CLK_PERIOD * self.RESET_CYCLES + self.ASSIGN_DELAY, units='ps')
        self.rst.value = 0
        self.dut._log.info("Reset done")

        if self.ASSIGN_DELAY > 0:
            await Timer(self.CLK_PERIOD - self.ASSIGN_DELAY, units='ps')

        if self.RESET_CLKGATE_CYCLES_POST > 0:
            self.dut._log.info("Waiting for %d ps" % (self.CLK_PERIOD * self.RESET_CLKGATE_CYCLES_POST))
            await Timer(self.CLK_PERIOD * self.RESET_CLKGATE_CYCLES_POST, units='ps')

        # Start the simulation until completion / timeout
        cocotb.start_soon(clkdriver.start())
        self.dut._log.info("Driving clock again")
        self.testStarted = True

        cocotb.start_soon(self._observe_traces())
        await self._run_test()

    def check_instr_read(self, addr_begin, addr_end, big_endian):
        if self.PRINT_IMEM and self.testStarted and addr_begin >= self.IMEM_BASE and addr_end < (self.IMEM_BASE + len(self.instr_mem)):
            print("instruction read %08x" % addr_begin)
            # Initialize the disassembler for RISC-V (32-bit)
            instr_data = self.instr_mem[addr_begin - self.IMEM_BASE:addr_end - self.IMEM_BASE]
            for i in range(0, len(instr_data), 4):
                assembly = disas.disassemble(instr_data[i:i+4])
                for idx, asm in enumerate(assembly):
                    print(f"Address: 0x{addr_begin + i + idx * int(4 / len(assembly)):08x}, Instruction: {asm}")
        if (self.EXCEPTION_BASE is not None) and addr_begin == self.EXCEPTION_BASE:
            raise CoreExceptionException("The core fetched the exception handler address 0x%08x" % addr_begin)
        return None

    def check_data_read(self, addr_begin, addr_end, big_endian):
        if self.testStarted:
            if addr_begin >= self.DMEM_BASE and addr_end <= (self.DMEM_BASE + self.DMEM_SIZE):
                if self.PRINT_DMEM:
                    print("data read %08x" % addr_begin)
            elif self.allowSpeculativeReads:
                print("WARNING: unmapped (speculative?) read at: %08x" % addr_begin)
                self.past_speculative_reads.add(addr_begin)
                return bytes([0] * (addr_end-addr_begin))
        return None
    def check_data_write(self, addr_begin, addr_end, word, wstrb):
        if self.testStarted:
            if addr_begin >= self.DMEM_BASE and addr_end <= (self.DMEM_BASE + self.DMEM_SIZE):
                if self.PRINT_DMEM:
                    print("data write %08x: %s strb %s" % (addr_begin, ' '.join([('%02x' % _byte) for _byte in word]), str(wstrb)))
            elif addr_begin in self.past_speculative_reads:
                self.past_speculative_reads.remove(addr_begin)
                print("WARNING: allowing write-back of previously speculatively unmapped read: %08x - %08X" % (addr_begin, addr_end))
                return True
        return False

    def check_ctrl_write(self, addr_begin, addr_end, word, wstrb):
        if addr_begin == self.CTRL_BASE or addr_begin == self.CTRL_BASE + 0x4000:
            # Assuming little endian
            if word[0] != 0 and wstrb[0] != 0:
                # Handle IRQ write
                self._event_irq.set()
                return True
            print("check_ctrl_write: Unexpected write to IRQ")
            return False
        elif addr_begin == self.CTRL_BASE + 4:
            try:
                # Set debug pin!
                self.dut.debugState.value = word
            except:
                pass
            return True
        elif addr_begin == self.CTRL_BASE + 8:
            if word[0] != 0 and wstrb[0] != 0:
                for i in range(len(wstrb.binstr)):
                    if wstrb[i] != 0:
                        self.uart_printer.write_byte(word[i])
                return True
        return False
    def check_ctrl_read(self, addr_begin, addr_end, big_endian):
        # For now, always return 0 for read access on the CTRL memory space.
        if addr_begin >= self.CTRL_BASE and addr_end <= self.CTRL_BASE+8:
            return bytes([0] * (addr_end - addr_begin))
        return None

    @cocotb.coroutine
    async def _observe_traces(self):
        while True:
            core_trace_entry = await self.core_trace_queue.get()
            iss_trace_entry = await self.iss_trace_queue.get()
            if not (core_trace_entry == iss_trace_entry):
                self.dut._log.error("-TRACE mismatch ISS %s; Core %s" % (str(iss_trace_entry), str(core_trace_entry)))
                raise TraceMismatchException("ISS trace not matching core")
            elif self.PRINT_ISS:
                self.dut._log.info("-TRACE match ISS %s; Core %s" % (str(iss_trace_entry), str(core_trace_entry)))

    async def _sample_delay(self):
        if self.SAMPLE_DELAY > 0:
            await Timer(self.SAMPLE_DELAY, units='ps')
        await ReadOnly()

    async def _run_test(self):
        # Make sure the trap output stabilizes before sampling it.
        timeout_periods = self.TIMEOUT_PERIODS
        if self.trap is not None:
            await Timer(self.CLK_PERIOD * 5, units='ps')
            timeout_periods -= 5
        await self._sample_delay()

        core_done_trigger = self._event_irq.wait()
        if self.trap is not None:
            if self.trap.value == 1:
                raise CoreExceptionException("The core set its trap pin")
            core_done_trigger = First(core_done_trigger, RisingEdge(self.trap))

        if timeout_periods > 0:
            await with_timeout(core_done_trigger, timeout_periods * self.CLK_PERIOD - self.SAMPLE_DELAY, timeout_unit='ps')
        else:
            await core_done_trigger

        if (self.trap is not None) and self.trap.value == 1:
            raise CoreExceptionException("The core set its trap pin")

        self.dut._log.info("expected_data: " + str(self.expected_data))
        got_all = bytearray()
        mismatch_exception = None
        for i in range(0, len(self.expected_data), 4):
            got = self.resdata_memview.read(self.resdata_location+i,self.resdata_location+i+4, 32).buff
            got_all += got
            expected = self.expected_data[i:i+4]
            self.dut._log.info("got: 0x%s, expected: 0x%s%s" % (str(got.hex()), str(expected.hex()), " (ERR)" if (got != expected) else ""))
            if mismatch_exception is None and got != expected:
                mismatch_exception = ResultMismatchException(
                    "At 0x%X: Got 0x%08x, expected 0x%08x. Wrote complete results to outputs_got.txt and outputs_expected.txt." % (
                        i, struct.unpack('<L', got)[0], struct.unpack('<L', expected)[0]))
        with open("outputs_got.txt", "w") as f:
            dump_32bithex(f, got_all)
        with open("outputs_expected.txt", "w") as f:
            dump_32bithex(f, self.expected_data)
        if mismatch_exception is not None:
            raise mismatch_exception

        self.uart_printer.flush()
