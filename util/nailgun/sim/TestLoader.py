# Taken from risc-v-verification-framework (ESA TU Darmstadt)
# Authors: Yannick Lavan (original author)
#          Florian Meisel
from elftools.elf.elffile import ELFFile, Section
from cocotb.binary import BinaryValue
from logging import Logger
from memutil import MemView
from typing import List, Tuple

class TestLoader:
    """
    Component responsible for loading the current testcase's ELF-file into the DUT's instruciton and data
    memory representations.
    """

    def __init__(self, logger: Logger, memories: List[MemView], memoryRanges: Tuple[int,int]):
        """
        :param logger: Logger
        :type logger: Logger
        :param memories: Memories to attempt writing to
        :type memories: List[MemView]
        :param memoryRanges: Memory ranges corresponding to memory, tuples of min (incl.) and max (excl.) addr.
        :type memoryRanges: Tuple[int,int]
        """
        self.logger = logger
        assert(len(memories) == len(memoryRanges))
        self.memories = [(memories[i], memoryRanges[i][0], memoryRanges[i][1]) for i in range(len(memories))]

    def write_sections(self, memview: MemView, sections: List[Section]):
        """
        Write the specified sections into the provided DUT memory model.

        :param mem: Target DUT memory view.
        :type mem: MemView
        :param sections: List of ELF file sections which are written to DUT memory.
        :type sections: List[Section]
        :note: Under normal circumstances you do not need to call this method as it is used internally
               for loading entire testcases.
        """
        for section in sections:
            start_addr = section["sh_addr"]
            sec_size = section["sh_size"]
            section_data = section.data()
            step_size = 1
            while sec_size % step_size == 0:
                step_size *= 2
            step_size //= 2
            for i in range(0, sec_size, step_size): #TODO: configurable for big-endian?
                strb = BinaryValue("1" * step_size, n_bits=step_size, bigEndian=False)
                memview.write(start_addr + i, start_addr + i + step_size, section_data[i:i + step_size], strb)

    def load_test_case(self, path: str) -> int|None:
        """
        Load the specified ELF file into the memory model. Make sure that the ELF files entrypoint matches
        the DUT's PC reset value

        :param path: Path to the current testcase ELF file.
        :type path: str
        :return: The _test_resdata symbol location, or None if not present
        """
        ret = None
        with open(path, "rb") as f:
            elf = ELFFile(f)
            sections = [elf.get_section(i) for i in range(0, elf.num_sections())]
            for mem in self.memories:
                memview = mem[0]
                mem_start_addr = mem[1]
                mem_end_addr = mem[2]
                self.logger.debug("Initializing memory {:08X}..{:08X}".format(mem_start_addr,mem_end_addr))
                relevant_sections = list(filter(lambda x: x["sh_flags"] & 2 == 2 and x["sh_size"] > 0 and x["sh_addr"] >= mem_start_addr and x["sh_addr"] + x["sh_size"] < mem_end_addr, sections)) # this assumes that an entire section fits into the same memory
                self.logger.debug("Copying section {}".format(relevant_sections))
                self.write_sections(memview, relevant_sections)
            symtab = elf.get_section_by_name('.symtab')
            if symtab is not None:
                resdata_syms = symtab.get_symbol_by_name('_test_resdata')
                if resdata_syms is not None and len(resdata_syms) == 1:
                    ret = resdata_syms[0]['st_value']
        return ret

