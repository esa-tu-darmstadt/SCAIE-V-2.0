import struct
import cocotb
from cocotb.triggers import FallingEdge
from memutil import MemView, HierarchicalMemView, BytearrayMemView

class CLINT:
    def __init__(self, CLINT_BASE):
        self.CLINT_BASE = CLINT_BASE
        self.clint_mtime = 0
        self.clint_mtime_h = 0
        self.clint_mtimecmp = 0
        self.clint_mtimecmp_h = 0
        self.clint_software_irq = 0

    @cocotb.coroutine
    def run(self, dut, clk):
        while True:
            # test condition
            yield FallingEdge(clk)
            if ((self.clint_mtime_h << 32) + self.clint_mtime) >= ((self.clint_mtimecmp_h << 32) + self.clint_mtimecmp):
                dut.irq_i.value = (1 << 7)
            else:
                dut.irq_i.value = 0
            if self.clint_software_irq != 0:
                dut.irq_i.value += (1 << 3)

            # advance counter
            new_mtime = ((self.clint_mtime_h << 32) + self.clint_mtime) + 1
            self.clint_mtime = new_mtime & 0xffffffff
            self.clint_mtime_h = (new_mtime >> 32) & 0xffffffff
    def check_clint_write(self, addr_begin, addr_end, word, wstrb):
        print(f"INFO: CLINT WRITE: addr: {addr_begin:0X} word: {struct.unpack('<I', word)[0]}")
        print(f"CURRENT VALUES: clint_mtime={self.clint_mtime} clint_mtime_h={self.clint_mtime_h} clint_mtimecmp={self.clint_mtimecmp} clint_mtimecmp_h={self.clint_mtimecmp_h}")
        word = struct.unpack('<I', word)[0]
        if addr_begin == self.CLINT_BASE:
            self.clint_software_irq = word & 1
        if addr_begin == self.CLINT_BASE + 0x0BFF8:
            assert wstrb == 0xF
            self.clint_mtime = word
            return True
        elif addr_begin == self.CLINT_BASE + 0x0BFFC:
            assert wstrb == 0xF
            self.clint_mtime_h = word
            return True
        elif addr_begin == self.CLINT_BASE + 0x04000:
            assert wstrb == 0xF
            self.clint_mtimecmp = word
            return True
        elif addr_begin == self.CLINT_BASE + 0x04004:
            assert wstrb == 0xF
            self.clint_mtimecmp_h = word
            return True
        print(f"clint: Unexpected write to {addr_begin:0X} = {word:0X}")
        return False
    def check_clint_read(self, addr_begin, addr_end, big_endian):
        print(f"INFO: CLINT REAd: addr: {addr_begin:0X}")
        print(f"CURRENT VALUES: clint_mtime={self.clint_mtime} clint_mtime_h={self.clint_mtime_h} clint_mtimecmp={self.clint_mtimecmp} clint_mtimecmp_h={self.clint_mtimecmp_h}")
        if addr_begin == self.CLINT_BASE + 0x0BFF8:
            return self.clint_mtime.to_bytes(4, byteorder="little")
        elif addr_begin == self.CLINT_BASE + 0x0BFFC:
            return self.clint_mtime_h.to_bytes(4, byteorder="little")
        elif addr_begin == self.CLINT_BASE + 0x04000:
            return self.clint_mtimecmp.to_bytes(4, byteorder="little")
        elif addr_begin == self.CLINT_BASE + 0x04004:
            return self.clint_mtimecmp_h.to_bytes(4, byteorder="little")
        print(f"ERROR clint: Unexpected read to {addr_begin:0X}")
        return None

def start_clint(dut, clk, CLINT_BASE, CLINT_BUSIDX):
    try:
        dut.irq_i.value = 0
    except:
        return None
    clint = CLINT(CLINT_BASE)
    clint_memview = MemView(read_cb=clint.check_clint_read, write_cb=clint.check_clint_write)

    cocotb.start_soon(clint.run(dut, clk))

    return clint_memview
