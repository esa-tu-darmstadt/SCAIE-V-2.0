from __future__ import annotations
from abc import ABC, abstractmethod
import os
from pyriscvvp import ISSTbHandler32, ISSTbHandler64, RVVI_FALSE, RVVI_TRUE, RVVI_STEP_YIELD, RVVI_TRAP
from collections import deque
import cocotb
from cocotb.triggers import Combine

from trace.instr_trace import TracedInstr


class ISSInitError(Exception):
    pass
class ISSInvalidInterruptException(Exception):
    pass
class ISSUnknownError(Exception):
    pass
class ISSMultiFaultError(Exception):
    pass

ISAX_DECOUPLED_FLAG=1<<7

def instr_opcode(instr_word: int):
    return instr_word & 0b01111111
def instr_funct3(instr_word: int):
    return (instr_word >> 12) & 0b0111
def instr_funct7(instr_word: int):
    return (instr_word >> 25) & 0b01111111
def instr_rd(instr_word: int):
    return (instr_word >> 7) & 0b00011111
def instr_rs1(instr_word: int):
    return (instr_word >> 15) & 0b00011111
def instr_rs2(instr_word: int):
    return (instr_word >> 20) & 0b00011111

class ISSISAXHandler(ABC):
    async def wait_ready(self):
        """
        Awaitable to call if an ISS step yielded. The ISAX handler should make progress, so a future handleInstr call will eventually not yield
        """
        pass
    @abstractmethod
    def handleInstr(self, adapter: ISSTbAdapter) -> int:
        """
        :return: RVVI_TRUE, RVVI_FALSE or RVVI_STEP_YIELD
        """
        pass
    async def onPostInstr(self, adapter: ISSTbAdapter):
        """
        Awaitable to call after each ISS instruction (emulating always-style ISAXes)
        """
        pass

class ISSCSRHandler:
    async def wait_ready(self):
        """
        Awaitable to call if an ISS step yielded. The CSR handler should make progress, so a future handleCSR(Read|Write) call will eventually not yield
        """
        pass
    def handleCSRRead(self, adapter: ISSTbAdapter, addr: int) -> tuple[int,int]:
        """
        :param addr: CSR address
        :return: Tuple of {return code, read data if RVVI_TRUE else 0}
                 where return code may be RVVI_TRUE, RVVI_FALSE or RVVI_STEP_YIELD
        """
        return RVVI_FALSE
    def handleCSRWrite(self, adapter: ISSTbAdapter, addr: int, data: int) -> int:
        """
        :param addr: CSR address
        :param data: write data
        :return: RVVI_TRUE, RVVI_FALSE or RVVI_STEP_YIELD
        """
        return RVVI_FALSE

class ISSTbAdapter:
    def __init__(self, dut, isax_handlers: list[ISSISAXHandler], csr_handlers: list[ISSCSRHandler], env: dict = os.environ, IS_64: bool = False, HART_ID: int = 0, PRINT_ISS: bool = True):
        """
        :param dut: cocotb dut
        :param isax_handlers: A list of callbacks to handle instructions not supported by the ISS.
                              The callback receives the ISSTbAdapter and returns whether it has processed the instruction.

        """
        self.dut = dut
        self.IMEM_BASE=int(env["IMEM_BASE"], 16)
        self.DMEM_BASE=int(env["DMEM_BASE"], 16)
        self.DMEM_SIZE=int(env["DMEM_SIZE"], 16)
        self.CTRL_BASE=int(env["CTRL_BASE"], 16)
        self.IS_64 = IS_64
        self.PRINT_ISS = PRINT_ISS
        TESTPROG = env["TESTPROG"]
        isa_ext_list = env["CORE_ISA_EXT"].split(',')

        assert(self.DMEM_BASE >= self.IMEM_BASE)
        self.mem_base = self.IMEM_BASE
        self.mem_size = (self.DMEM_BASE + self.DMEM_SIZE) - self.IMEM_BASE
        assert(self.mem_size < 2**30) #assume less than 1G

        self.isax_handlers = isax_handlers.copy()
        self.csr_handlers = csr_handlers.copy()
        self.yielding_handlers = self.isax_handlers + self.csr_handlers
        self.isax_state = {}
        self.cur_is_decoupled_isax = False

        self.next_interrupt_delays = deque()

        self.traceSetCompareResult = True
        self.trapCount = 0

        self.dut._log.info(f"Requesting RV{64 if IS_64 else 32} ISS extensions: {",".join(isa_ext_list)}")
        if IS_64:
            self.iss = ISSTbHandler64(HART_ID, self.mem_size, self.mem_base, self.CTRL_BASE, self.PRINT_ISS, isa_ext_list)
        else:
            self.iss = ISSTbHandler32(HART_ID, self.mem_size, self.mem_base, self.CTRL_BASE, self.PRINT_ISS, isa_ext_list)
        self.IS_64 = IS_64
        success = self.iss.rvviRefInit(TESTPROG+".elf", "", "")
        if not success:
            raise ISSInitError("rvviRefInit failed")
        success = self.iss.rvviRefUnknownInstrSetCallback(self._instrCallbackArbiter)
        if not success:
            raise ISSInitError("rvviRefUnknownInstrSetCallback failed")
        success = self.iss.rvviRefUnknownCsrReadSetCallback(self._csrReadCallbackArbiter)
        if not success:
            raise ISSInitError("rvviRefUnknownCsrReadSetCallback failed")
        success = self.iss.rvviRefUnknownCsrWriteSetCallback(self._csrWriteCallbackArbiter)
        if not success:
            raise ISSInitError("rvviRefUnknownCsrWriteSetCallback failed")

    def _instrCallbackArbiter(self) -> int:
        for handler in self.isax_handlers:
            ret = handler.handleInstr(self)
            ret_noflags = ret & ~ISAX_DECOUPLED_FLAG
            if ret_noflags != RVVI_FALSE:
                self.cur_is_decoupled_isax = (ret & ISAX_DECOUPLED_FLAG) != 0
                return ret_noflags
        return RVVI_FALSE

    def _csrReadCallbackArbiter(self, addr: int) -> tuple[int,int]:
        for handler in self.csr_handlers:
            ret = handler.handleCSRRead(self, addr)
            if ret != RVVI_FALSE:
                return ret
        return (RVVI_FALSE, 0)

    def _csrWriteCallbackArbiter(self, addr: int, data: int) -> int:
        for handler in self.csr_handlers:
            ret = handler.handleCSRWrite(self, addr, data)
            if ret != RVVI_FALSE:
                return ret
        return RVVI_FALSE

    def handleInterruptInNumCommits(self, num_commits_before_transfer: int):
        """
        Transfer to handling an external machine interrupt after the given number of ISS instruction commits (i.e., trace entries).
        At minimum, one instruction is executed (and traced) after setting the interrupt signal before the ISS processes the interrupt.
        The MEIP interrupt flag (MIP CSR) is reset on entry to the handler.
        :param num_commits_before_transfer: the number of cycles before entering the interrupt handler (min. 1)
        """
        if num_commits_before_transfer < 1:
            raise ValueError("num_commits_before_transfer must be at least 1")
        if len(self.next_interrupt_delays) > 0 and self.next_interrupt_delays[0] == num_commits_before_transfer:
            raise ISSInvalidInterruptException("Can only issue one interrupt at a time")
        self.next_interrupt_delays.append(num_commits_before_transfer)

    async def processStep(self) -> list[TracedInstr] | None:
        """
        Processes a single simulation step in the ISS, executing up to one instruction (usually exactly one).
        In case of a trap, returns an empty list.
        Returns None if the simulation has been
         a) stopped by the test program having set the MMIO completion word, or
         b) shut down from a call to shutdown()
        """
        reset_interrupt_next: bool = False
        for i in range(len(self.next_interrupt_delays)):
            delay = self.next_interrupt_delays.popleft()
            assert(delay >= 1)
            if delay == 1:
                if self.iss.rvviSetResetExternalMachineInterrupt(True):
                    self.dut._log.warning("ISSTbAdapter: Cannot raise a new interrupt to the ISS: Interrupt flag already set")
                reset_interrupt_next = True
            else:
                self.next_interrupt_delays.append(delay - 1)
        while True:
            self.cur_is_decoupled_isax = False
            result = self.iss.rvviRefEventStepYieldable()
            if result == RVVI_FALSE:
                return None
            if result == RVVI_TRUE or result == RVVI_TRAP:
                break
            elif result == RVVI_STEP_YIELD:
                await Combine(*[cocotb.start_soon(handler.wait_ready()) for handler in self.yielding_handlers])
            else:
                raise ISSUnknownError("Unexpected rvviRefEventStepYieldable result %d" % result)
        if result == RVVI_TRAP:
            self.trapCount += 1
            if self.trapCount > 2:
                raise ISSMultiFaultError("ISS encountered more than two traps immediately after another")
            return []
        self.trapCount += 0
        pc = self.iss.rvviGetTransition()[0]
        gprs_written = self.iss.rvviRefGprsWrittenGet()
        addr, rdata, rmask, wdata, wmask = self.iss.rvviRefMemGetState()
        if self.PRINT_ISS and rmask != 0:
            self.dut._log.info("ISS data read {0:08x}: {1:08x} rmask {2:04b}".format(addr, rdata, rmask))
        if self.PRINT_ISS and wmask != 0:
            self.dut._log.info("ISS data write {0:08x}: {1:08x} wmask {2:04b}".format(addr, wdata, wmask))
        igpr = -1
        while gprs_written != 0:
            igpr += 1
            if (gprs_written & 1) != 0 and (gprs_written & ~1) != 0:
                self.dut._log.warning("ISSTbAdapter: The instruction at PC 0x{0:08x} wrote to several " + \
                    "registers (mask {1:b}); assuming only register x%d is used".format(pc, self.iss.rvviRefGprsWrittenGet(), igpr))
                break
            gprs_written >>= 1
        if reset_interrupt_next:
            enabled_prior = self.iss.rvviSetResetExternalMachineInterrupt(False)
            assert(enabled_prior)
            assert(not self.iss.rvviSetResetExternalMachineInterrupt(False))
        if igpr > 0 and not self.cur_is_decoupled_isax: #FIXME: Improve decoupled ISAX tracing
            gpr_val = self.iss.rvviRefGprGet(igpr)
            ret = [TracedInstr(pc, True, igpr, gpr_val, gpr_check=self.traceSetCompareResult)]
        else:
            ret = [TracedInstr(pc, False, 0, 0)]
        await Combine(*[cocotb.start_soon(handler.onPostInstr(self)) for handler in self.isax_handlers])
        return ret

    def shutdown(self):
        self.iss.rvviRefShutdown()
