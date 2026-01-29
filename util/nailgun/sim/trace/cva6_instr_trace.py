import os
from collections import namedtuple, deque
import cocotb
from cocotb.triggers import Timer, RisingEdge, ReadOnly, Event, with_timeout, First
from cocotb.binary import BinaryValue
from cocotb.queue import Queue
from trace.instr_trace import TracedInstr
from cocotb.handle import HierarchyObject

def _revertBitOrder(val: BinaryValue) -> BinaryValue:
    return BinaryValue(val.binstr[::-1])

class CVA6TracedInstr(TracedInstr):
    def __init__(self, pc : int, has_rd : bool, rd_regnum : int, rd_data : int, mtvec : int, is_exception_handler_entry: bool):
        super().__init__(pc, has_rd, rd_regnum, rd_data)
        self.mtvec = mtvec
        self.is_exception_handler_entry = is_exception_handler_entry #True: handling exception (not interrupt)
    def __eq__(self, other):
        return super().__eq__(other)
    def __str__(self):
        return super().__str__()

class CVA6RVFITracePins:
    def __init__(self, dut, cva6_wrapper):
        self.clk = cva6_wrapper.clk
        self.rst = cva6_wrapper.rst

        self.rvfi_valid = [cva6_wrapper.rvfi_o_valid_A, cva6_wrapper.rvfi_o_valid_B]
        self.rvfi_pc_rdata = [cva6_wrapper.rvfi_o_pc_rdata_A, cva6_wrapper.rvfi_o_pc_rdata_B]
        self.rvfi_rd_addr = [cva6_wrapper.rvfi_o_rd_addr_A, cva6_wrapper.rvfi_o_rd_addr_B]
        self.rvfi_rd_wdata = [cva6_wrapper.rvfi_o_rd_wdata_A, cva6_wrapper.rvfi_o_rd_wdata_B]

class CVA6Tracer:
    """
    Tracer for CVA6, recording the PC and writeback values for each retired instruction.
    Records SCAIE-V decoupled ISAXes with rd zero.
    """

    def __init__(self, dut, SAMPLE_DELAY, tracePins: CVA6RVFITracePins, shutdown_event: Event, trace_queue: Queue):
        self.dut = dut
        self.SAMPLE_DELAY = SAMPLE_DELAY
        self.rst = tracePins.rst
        self.clk = tracePins.clk
        self.tracePins = tracePins
        self.shutdown_event = shutdown_event

        self.trace_queue = trace_queue

        cocotb.start_soon(self._produce_trace())

    def has_unhandled_external_machine_interrupt(self):
        return False #TODO

    def is_entering_exception(self):
        return False #TODO

    async def _sample_delay(self):
        if self.SAMPLE_DELAY > 0:
            await Timer(self.SAMPLE_DELAY, units='ps')
        await ReadOnly()

    @cocotb.coroutine
    async def _produce_trace(self):
        clock_re = RisingEdge(self.clk)
        await clock_re
        await self._sample_delay()
        while self.rst.value == 1:
            await clock_re
            await self._sample_delay()
        await clock_re
        await self._sample_delay()

        while not self.shutdown_event.is_set():
            if self.tracePins.rvfi_valid[0].value:
                instr_pc = self.tracePins.rvfi_pc_rdata[0].value
                reg_write_addr = self.tracePins.rvfi_rd_addr[0].value
                reg_write_data = self.tracePins.rvfi_rd_wdata[0].value
                #use put_nowait, as we must be sure the receiver does not let timesteps pass in the meantime
                #TODO: Fill in mtvec, is_exception_handler_entry
                self.trace_queue.put_nowait(CVA6TracedInstr(instr_pc, reg_write_addr != 0, reg_write_addr, reg_write_data, 0, False))
            if self.tracePins.rvfi_valid[1].value:
                instr_pc = self.tracePins.rvfi_pc_rdata[1].value
                reg_write_addr = self.tracePins.rvfi_rd_addr[1].value
                reg_write_data = self.tracePins.rvfi_rd_wdata[1].value
                #TODO: Fill in mtvec, is_exception_handler_entry
                self.trace_queue.put_nowait(CVA6TracedInstr(instr_pc, reg_write_addr != 0, reg_write_addr, reg_write_data, 0, False))

            await clock_re
            await self._sample_delay()
        pass
