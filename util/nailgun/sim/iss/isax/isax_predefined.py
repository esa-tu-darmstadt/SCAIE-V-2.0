from iss.iss_adapter import ISSTbAdapter, ISSISAXHandler, instr_opcode, instr_funct3, instr_funct7
from pyriscvvp import RVVI_TRUE, RVVI_FALSE
from typing import Callable

class ISSFenceKillISAX(ISSISAXHandler):
    """Stub handler for disaxkill and disaxfence"""
    def __init__(self, dut, has_isaxkill = True, has_isaxfence = True):
        self.dut = dut
        self.has_isaxkill = has_isaxkill
        self.has_isaxfence = has_isaxfence

    def handleInstr(self, adapter: ISSTbAdapter) -> int:
        instr = adapter.iss.rvviGetTransition()[1]
        if instr_opcode(instr) == 0b0001011:
            funct3 = instr_funct3(instr)
            if self.has_isaxkill and funct3 == 0b110:
                return RVVI_TRUE #disaxkill
            if self.has_isaxfence and funct3 == 0b111:
                return RVVI_TRUE #disaxfence
        return RVVI_FALSE

class ISSContextISAX(ISSISAXHandler):
    """Custom register context switch ISAX"""
    def __init__(self, dut, number_of_contexts: int, cbs_contextswitch: list[Callable[[int],None]] = []):
        """
        :param number_of_contexts: number of contexts in SCAIE-V
        :param cbs_contextswitch: callbacks for a context switch, each receives the new context number
        """
        self.dut = dut
        assert(number_of_contexts > 0)
        self.number_of_contexts = number_of_contexts
        self.cbs_contextswitch = cbs_contextswitch

    def handleInstr(self, adapter: ISSTbAdapter) -> int:
        instr = adapter.iss.rvviGetTransition()[1]
        if instr_opcode(instr) == 0b0001011 and instr_funct3(instr) == 0b101:
            ctxNum = ((instr >> 20) & 0b111111100000) | ((instr >> 7) & 0b000000011111)
            ctxNum = ctxNum % self.number_of_contexts
            for cb_contextswitch in self.cbs_contextswitch:
                cb_contextswitch(ctxNum)
            return RVVI_TRUE
        return RVVI_FALSE
