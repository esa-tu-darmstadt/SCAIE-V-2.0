from iss.iss_adapter import ISSTbAdapter, ISSISAXHandler, instr_opcode, instr_funct3, instr_rd, instr_rs1, instr_rs2, instr_funct7
from pyriscvvp import RVVI_TRUE, RVVI_FALSE, RVVI_STEP_YIELD
import cocotb
from cocotb.queue import Queue
from cocotb.binary import BinaryValue
from collections import deque

class ISSZOLHandler(ISSISAXHandler):
    def __init__(self, dut):
        self.dut = dut
        self.loop_remain = 0
        self.loop_pcs = None

    def handleInstr(self, adapter: ISSTbAdapter) -> int:
        pc,instr = adapter.iss.rvviGetTransition()[0:2]
        if instr_opcode(instr) != 0b0001011:
            return RVVI_FALSE
        match instr_funct3(instr):
            case 0b101: #setup_zol
                if instr_rd(instr) != 0:
                    self.dut._log.warning("WARN: setup_zol - instruction 0b%b has unused fields set to non-zero at PC 0x%08x" % (instr, pc))
                self.loop_remain = (instr >> 20) & 0xfff
                uimmS = ((instr >> 15) & 0b11111) << 1
                if (pc & 3) != 0 or (uimmS & 3) != 0:
                    self.dut._log.info("NOTE: setup_zol - Start or destination are not 4-byte-aligned, PC 0x%08x" % (pc))
                self.loop_pcs = (pc+4, pc+uimmS)
                self.dut._log.info("ISS - setup_zol: [pc=%08x] start=%08x end=%08x, count=%d" % \
                                   (pc, self.loop_pcs[0], self.loop_pcs[1], self.loop_remain))
                return RVVI_TRUE
            case _:
                return RVVI_FALSE
        return RVVI_TRUE

    async def wait_ready(self):
        pass
    async def onPostInstr(self, adapter: ISSTbAdapter):
        next_pc = adapter.iss.rvviGetTransition()[2]
        if self.loop_remain != 0 and next_pc == self.loop_pcs[1]:
            self.loop_remain = self.loop_remain - 1
            adapter.iss.rvviRefPcSet(self.loop_pcs[0])
            self.dut._log.info("ISS - zol: [pc=%08x] next iter, remain %d" % (next_pc, self.loop_remain))


class ISSZOL2DHandler(ISSISAXHandler):
    def __init__(self, dut):
        self.dut = dut
        self.outerloop_remain = 0
        self.innerloop_remain = 0
        self.innerloop_max = 0
        self.innerloop_pcs = None
        self.outerloop_pcs = None

    def handleInstr(self, adapter: ISSTbAdapter) -> int:
        pc,instr = adapter.iss.rvviGetTransition()[0:2]
        if instr_opcode(instr) != 0b0001011:
            return RVVI_FALSE
        match instr_funct3(instr):
            case 0b110: #setup_zol2dinner
                if instr_rd(instr) != 0 or instr_funct7(instr) != 0:
                    self.dut._log.warning("WARN: setup_zol2dinner - instruction 0b%b has unused fields set to non-zero at PC 0x%08x" % (instr, pc))
                if self.innerloop_remain != 0 or self.outerloop_remain != 0:
                    self.dut._log.warning("WARN: setup_zol2dinner - a loop is still running at PC 0x%08x" % (instr, pc))
                inner_start_pc = adapter.iss.rvviRefGprGet(instr_rs1(instr))
                inner_end_pc = adapter.iss.rvviRefGprGet(instr_rs2(instr))
                self.innerloop_pcs = (inner_start_pc, inner_end_pc)
                self.dut._log.info("ISS - setup_zol2dinner: [pc=%08x] start=%08x end=%08x" % (pc, inner_start_pc, inner_end_pc))
                return RVVI_TRUE
            case 0b101: #setup_zol2d
                if instr_rs2(instr) != 0:
                    self.dut._log.warning("WARN: setup_zol2d - instruction 0b%b has unused fields set to non-zero at PC 0x%08x" % (instr, pc))
                packed_counts = adapter.iss.rvviRefGprGet(instr_rs1(instr))
                if (packed_counts & ~(0xffff)) != 0:
                    self.dut._log.warning("WARN: setup_zol2d - count operand has unused bits set, PC 0x%08x" % (pc))
                self.innerloop_max = packed_counts & 0xff
                self.outerloop_remain = (packed_counts & 0xff00) >> 8
                self.innerloop_remain = self.innerloop_max
                uimmS = (((instr >> 31) & 1) << 12) | (((instr >> 7) & 1) << 11) | (((instr >> 25) & 0b111111) << 5) | (((instr >> 8) & 0b1111) << 1)
                if (packed_counts & ~(0xffff)) != 0:
                    self.dut._log.info("NOTE: setup_zol2d - MSB of unsigned immediate set (i.e., still positive), PC 0x%08x" % (pc))
                if (pc & 3) != 0 or (uimmS & 3) != 0:
                    self.dut._log.info("NOTE: setup_zol2d - Start or destination are not 4-byte-aligned, PC 0x%08x" % (pc))
                self.outerloop_pcs = (pc+4, pc+uimmS)
                self.dut._log.info("ISS - setup_zol2d: [pc=%08x] start=%08x end=%08x, count_outer=%d, count_inner=%d" % \
                                   (pc, self.outerloop_pcs[0], self.outerloop_pcs[1], self.outerloop_remain, self.innerloop_max))
                return RVVI_TRUE
            case _:
                return RVVI_FALSE
        return RVVI_TRUE

    async def wait_ready(self):
        pass
    async def onPostInstr(self, adapter: ISSTbAdapter):
        next_pc = adapter.iss.rvviGetTransition()[2]
        if self.innerloop_remain != 0:
            if next_pc == self.innerloop_pcs[1]:
                self.innerloop_remain = self.innerloop_remain - 1
                adapter.iss.rvviRefPcSet(self.innerloop_pcs[0])
                self.dut._log.info("ISS - zol2d: [pc=%08x] next iter of inner loop, remain %d (outer %d)" % (next_pc, self.innerloop_remain, self.outerloop_remain))
        elif self.outerloop_remain != 0 and next_pc == self.outerloop_pcs[1]:
            self.outerloop_remain = self.outerloop_remain - 1
            self.innerloop_remain = self.innerloop_max
            self.dut._log.info("ISS - zol2d: [pc=%08x] next iter of outer loop, remain %d (inner loop counter %d)" % (next_pc, self.outerloop_remain, self.innerloop_remain))
            adapter.iss.rvviRefPcSet(self.outerloop_pcs[0])

def getHandlers(dut) -> dict[str,ISSISAXHandler]:
    return {"zol": ISSZOLHandler(dut), "zol2d": ISSZOL2DHandler(dut)}
