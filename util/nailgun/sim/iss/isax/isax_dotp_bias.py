from iss.iss_adapter import ISSTbAdapter, ISSISAXHandler, instr_opcode, instr_funct3, instr_rd, instr_rs1, instr_rs2, instr_funct7, ISAX_DECOUPLED_FLAG
from pyriscvvp import RVVI_TRUE, RVVI_FALSE, RVVI_STEP_YIELD
import cocotb
from cocotb.queue import Queue
from cocotb.binary import BinaryValue
from collections import deque

def _int8_as_pyint(int8_in: int):
    if (int8_in & 0x80) != 0:
        return -((int8_in ^ 0xff) + 1)
    return int8_in
def _int12_as_pyint(int12_in: int):
    if (int12_in & 0x800) != 0:
        return -((int12_in ^ 0xfff) + 1)
    return int12_in

class ISSDOTPBHandler(ISSISAXHandler):
    def __init__(self, dut):
        self.dut = dut
        self.biasX = 0
        self.biasY = 0

    def setup_dotpbias_execute(self, adapter: ISSTbAdapter, pc: int, instr: int) -> int:
        if instr_rd(instr) != 0:
            self.dut._log.warning("WARN: setup_dotpbias - instruction 0b%b has unused fields set to non-zero at PC 0x%08x" % (instr, pc))

        self.biasX = adapter.iss.rvviRefGprGet(instr_rs1(instr))
        if (self.biasX & ~0xff) != 0 and (self.biasX & ~0x7f) != 0xffffff80: #allow (but don't require) sign extension beyond 8bit
            self.dut._log.warning("WARN: setup_dotpbias - biasX (rs1) value has data beyond 8bit int (%08x), PC 0x%08x" % (self.biasX, pc))
        self.biasX = _int8_as_pyint(self.biasX & 0xff)

        self.biasY = adapter.iss.rvviRefGprGet(instr_rs2(instr))
        if (self.biasY & ~0xff) != 0 and (self.biasY & ~0x7f) != 0xffffff80: #allow (but don't require) sign extension beyond 8bit
            self.dut._log.warning("WARN: setup_dotpbias - biasY (rs2) value has data beyond 8bit int (%08x), PC 0x%08x" % (self.biasY, pc))
        self.biasY = _int8_as_pyint(self.biasY & 0xff)
        self.dut._log.info("ISS - setup_dotpbias: [pc=%08x] biasX=%d, biasY=%d" % (pc, self.biasX, self.biasY))
        return RVVI_TRUE
    def doptbias_execute(self, adapter: ISSTbAdapter, xvec: int, yvec: int, pc: int, rd: int) -> int:
        xvec_tuple = (_int8_as_pyint(xvec & 0xff), _int8_as_pyint((xvec & 0xff00) >> 8), _int8_as_pyint((xvec & 0xff0000) >> 16), _int8_as_pyint((xvec & 0xff000000) >> 24))
        yvec_tuple = (_int8_as_pyint(yvec & 0xff), _int8_as_pyint((yvec & 0xff00) >> 8), _int8_as_pyint((yvec & 0xff0000) >> 16), _int8_as_pyint((yvec & 0xff000000) >> 24))
        res = 0
        res += (xvec_tuple[0] - self.biasX) * (yvec_tuple[0] - self.biasY)
        res += (xvec_tuple[1] - self.biasX) * (yvec_tuple[1] - self.biasY)
        res += (xvec_tuple[2] - self.biasX) * (yvec_tuple[2] - self.biasY)
        res += (xvec_tuple[3] - self.biasX) * (yvec_tuple[3] - self.biasY)
        assert(res <= 260100 and res >= -260100) #4*((127-(-128))^2)
        adapter.iss.rvviRefGprSet(rd, res & 0xFFFFFFFF)
        self.dut._log.info("ISS - dotpbias: [pc=%08x] xvec=%08x=(%d,%d,%d,%d), yvec=(%d,%d,%d,%d) (biasX=%d, biasY=%d), res=%d (%08x)" % (pc, \
                            xvec & 0xffffffff, xvec_tuple[0], xvec_tuple[1], xvec_tuple[2], xvec_tuple[3], \
                            yvec_tuple[0], yvec_tuple[1], yvec_tuple[2], yvec_tuple[3], \
                            self.biasX, self.biasY, res, res & 0xffffffff))
        return RVVI_TRUE

    def handleInstr(self, adapter: ISSTbAdapter) -> int:
        pc,instr = adapter.iss.rvviGetTransition()[0:2]
        if instr_opcode(instr) != 0b0001011:
            return RVVI_FALSE
        if instr_funct7(instr) != 0b0001001:
            return RVVI_FALSE
        match instr_funct3(instr):
            case 0b001: #setup_dotpbias
                return self.setup_dotpbias_execute(adapter, pc, instr)
            case 0b000: #dotpbias
                xvec = adapter.iss.rvviRefGprGet(instr_rs1(instr))
                yvec = adapter.iss.rvviRefGprGet(instr_rs2(instr))
                return self.doptbias_execute(adapter, xvec, yvec, pc, instr_rd(instr))
            case _:
                return RVVI_FALSE
        return RVVI_TRUE

class ISSLWDOTPBHANDLER(ISSDOTPBHandler):
    def __init__(self, dut):
        super().__init__(dut)
        self.memAddr = 0

    def handleInstr(self, adapter: ISSTbAdapter) -> int:
        pc,instr = adapter.iss.rvviGetTransition()[0:2]
        if instr_opcode(instr) != 0b0001011:
            return RVVI_FALSE
        match instr_funct3(instr):
            case 0b001: #setup_dotpbias
                return self.setup_dotpbias_execute(adapter, pc, instr)
            case 0b010: #setup_dotpaddr
                if instr_rs2(instr) != 0 or instr_funct7(instr) != 0 or instr_rd(instr) != 0:
                    self.dut._log.warning("WARN: setup_dotpaddr - instruction 0b%b has unused fields set to non-zero at PC 0x%08x" % (instr, pc))
                self.memAddr = adapter.iss.rvviRefGprGet(instr_rs1(instr))
                self.dut._log.info("ISS - setup_dotpaddr: [pc=%08x] memAddr=%08x" % (pc, self.memAddr))
                return RVVI_TRUE
            case 0b011: #incr_dotpaddr
                if instr_rs1(instr) != 0 or instr_rd(instr) != 0:
                    self.dut._log.warning("WARN: incr_dotpaddr - instruction 0b%b has unused fields set to non-zero at PC 0x%08x" % (instr, pc))
                offs = _int12_as_pyint((instr >> 20) & 0xfff)
                self.memAddr += offs
                self.dut._log.info("ISS - incr_dotpaddr: [pc=%08x] offset=%d, new memAddr=%08x" % (pc, offs, self.memAddr))
                return RVVI_TRUE
            case 0b000: #lwdotpbias
                offs = _int12_as_pyint((instr >> 20) & 0xfff)
                readAddr = self.memAddr + offs
                xvec = adapter.iss.rvviRefMemoryRead(readAddr, 4)
                yvec = adapter.iss.rvviRefGprGet(instr_rs1(instr))
                self.dut._log.info("ISS - lwdotpbias: [pc=%08x] ADDR=%08x offset=%d -> address=%08x" % (pc, \
                                    self.memAddr, offs, readAddr))
                return self.doptbias_execute(adapter, xvec, yvec, pc, instr_rd(instr)) | ISAX_DECOUPLED_FLAG
            case _:
                return RVVI_FALSE
        return RVVI_TRUE

def getHandlers(dut) -> list[ISSISAXHandler]:
    return {"dotpb": ISSDOTPBHandler(dut), "lwdotpb": ISSLWDOTPBHANDLER(dut)}