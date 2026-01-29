
class TracedInstr:
    def __init__(self, pc : int, has_rd : bool, rd_regnum : int, rd_data : int, gpr_check : bool = True):
        self.pc = pc
        self.has_rd = has_rd
        self.rd_regnum = rd_regnum
        self.rd_data = rd_data
        self.gpr_check = gpr_check
    def hasRdUpdate(self):
        return self.has_rd and self.rd_regnum != 0
    def __eq__(self, other):
        if not isinstance(other, TracedInstr):
            raise NotImplemented()
        return self.pc == other.pc and \
            (self.hasRdUpdate() == other.hasRdUpdate()) and \
            (self.rd_data == other.rd_data or not self.hasRdUpdate() or not self.gpr_check or not other.gpr_check)
    def __str__(self):
        writeback_str = None
        if self.has_rd:
            writeback_str = "RD %d: 0x%08x%s" % (self.rd_regnum, self.rd_data, "" if self.gpr_check else "(*)")
        else:
            writeback_str = "RD -: N/A"
        return "<pc 0x%08x %s>" % (self.pc, writeback_str)