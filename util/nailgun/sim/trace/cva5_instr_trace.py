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

class CVA5TracedInstr(TracedInstr):
    def __init__(self, pc : int, has_rd : bool, rd_regnum : int, rd_data : int, mtvec : int, is_exception_handler_entry: bool):
        super().__init__(pc, has_rd, rd_regnum, rd_data)
        self.mtvec = mtvec
        self.is_exception_handler_entry = is_exception_handler_entry #True: handling exception (not interrupt)
    def __eq__(self, other):
        return super().__eq__(other)
    def __str__(self):
        return super().__str__()

class CVA5TracePins:
    #typedef struct packed{
    #    id_t id;
    #    logic valid;
    #    phys_addr_t phys_addr;
    #    logic [31:0] data;
    #} commit_packet_t;
    @staticmethod
    def commit_packet_id(commit_packet) -> BinaryValue:
        if isinstance(commit_packet, HierarchyObject):
            return commit_packet.id.value
        val = commit_packet.value
        assert(len(val) == 3+1+6+32)
        #note: packed structs are presented to cocotb as big-endian, so counting is reversed
        return val[0:3-1]
    @staticmethod
    def commit_packet_valid(commit_packet) -> BinaryValue:
        #valid -> writeback (preliminary, may still get rolled back until retire)
        if isinstance(commit_packet, HierarchyObject):
            return commit_packet.valid.value
        val = commit_packet.value
        assert(len(val) == 3+1+6+32)
        return val[3]
    @staticmethod
    def commit_packet_phys_addr(commit_packet) -> BinaryValue:
        if isinstance(commit_packet, HierarchyObject):
            return commit_packet.phys_addr.value
        val = commit_packet.value
        assert(len(val) == 3+1+6+32)
        return val[3+1:3+1+6-1]
    @staticmethod
    def commit_packet_data(commit_packet) -> BinaryValue:
        if isinstance(commit_packet, HierarchyObject):
            return commit_packet.data.value
        val = commit_packet.value
        assert(len(val) == 3+1+6+32)
        return val[3+1+6:3+1+6+32-1]

    #typedef struct packed{
    #    logic valid;
    #    id_t phys_id;
    #    logic [LOG2_RETIRE_PORTS : 0] count;
    #} retire_packet_t;
    def retire_next_valid(self) -> BinaryValue:
        if isinstance(self.retire_next, HierarchyObject):
            return self.retire_next.valid.value
        val = self.retire_next.value
        assert(len(val) == 1+3+2)
        return val[0]
    def retire_next_phys_id(self) -> BinaryValue:
        if isinstance(self.retire_next, HierarchyObject):
            return self.retire_next.phys_id.value
        val = self.retire_next.value
        assert(len(val) == 1+3+2)
        return val[1:1+3-1]
    def retire_next_count(self) -> BinaryValue:
        if isinstance(self.retire_next, HierarchyObject):
            return self.retire_next.count.value
        val = self.retire_next.value
        assert(len(val) == 1+3+2)
        return val[1+3:1+3+2-1]

    def csr_mip_meip(self) -> bool:
        if isinstance(self.csr_mip, HierarchyObject):
            return not (not self.csr_mip.meip.value)
        val = self.csr_mip.value
        assert(len(val) == 32)
        return not (not val[20])

    #typedef struct packed {
    #    logic [1:0] rw_bits;
    #    logic [1:0] privilege;
    #    logic [7:0] sub_addr;
    #} csr_addr_t;
    #typedef struct packed{
    #    csr_addr_t addr;
    #    logic[1:0] op;
    #    logic reads;
    #    logic writes;
    #    logic [XLEN-1:0] data;
    #} csr_inputs_t;
    def csr_mwrite_en_mip(self) -> bool:
        mwrite_val = self.csr_mwrite.value
        MIP_addr=0x344
        if isinstance(self.csr_inputs_r, HierarchyObject):
            csr_inputs_r_sub_addr_val = csr_inputs_r.addr.sub_addr.value
        else:
            csr_inputs_r_val = self.csr_inputs_r.value
            assert(len(csr_inputs_r_val) == 48)
            csr_inputs_r_addr_val = csr_inputs_r_val[0:11]
            csr_inputs_r_sub_addr_val = csr_inputs_r_addr_val[4:11]
        return mwrite_val and csr_inputs_r_sub_addr_val.integer == (MIP_addr & 0xFF)
    def csr_write_data(self) -> BinaryValue:
        return self.csr_updated_csr.value

    #typedef struct packed{
    #    logic init_clear;
    #    logic fetch_hold;
    #    logic rename_hold;
    #    logic issue_hold;
    #    logic fetch_flush;
    #    logic writeback_supress;
    #    logic retire_hold;
    #    logic sq_flush;
    #    logic tlb_flush;
    #    logic exception_pending;
    #    exception_packet_t exception;
    #    logic pc_override;
    #    logic [31:0] pc;
    #} gc_outputs_t; #43 + 73
    #typedef struct packed{
    #    logic valid;
    #    exception_code_t code; //logic [4:0]
    #    logic [31:0] tval;
    #    logic [31:0] pc;
    #    id_t id; //logic [2:0]
    #} exception_packet_t;
    def gc_writeback_supress(self) -> bool:
        """
        Returns True iff the core is discarding all post-issue instructions,
         held to True as long as any post-issue instructions are pending for retire/discarding,
         or during initialization where no commits may appear.
        All 'retired' seen on retire_next must be ignored while this is True.
        """
        if isinstance(self.gc, HierarchyObject):
            return not (not self.gc.writeback_supress.value)
        val = self.gc.value
        assert(len(val) == 116)
        return not (not val[5])

    #typedef struct packed {
    #    logic valid;
    #
    #    exception_code_t code; //logic [4:0]
    #    id_t id; //logic [2:0]
    #    logic [31:0] tval;
    #} exception_interface_fromunit;
    def lsu_exception_valid(self) -> bool:
        if self.lsu_exception is not None:
            return not (not self.lsu_exception.valid.value)
        if isinstance(self.lsu_exception_fromunit, HierarchyObject):
            return not (not self.lsu_exception_fromunit.valid.value)
        val = self.lsu_exception_fromunit.value
        assert(len(val) == 41)
        return not (not val[0])
    def lsu_exception_id(self) -> BinaryValue:
        if self.lsu_exception is not None:
            return self.lsu_exception.id.value
        if isinstance(self.lsu_exception_fromunit, HierarchyObject):
            return self.lsu_exception_fromunit.valid.id
        val = self.lsu_exception_fromunit.value
        assert(len(val) == 41)
        return val[6:6+3-1]

class CVA5TracePins_Standard(CVA5TracePins):
    def __init__(self, dut, cva5_wrapper):
        self.clk = cva5_wrapper.clk
        self.rst = cva5_wrapper.rst
        #self.issue_id = cva5_wrapper.cva5.issue.id
        self.issue_id = cva5_wrapper.scaiev.issue_ID
        self.issue_RD_valid = cva5_wrapper.scaiev.issue_RD_valid #writing to reg
        self.issue_RD_id = cva5_wrapper.scaiev.issue_RD_id #logical RD reg ID
        self.issue_PC = cva5_wrapper.scaiev.issue_PC
        self.issue_isStalling = cva5_wrapper.scaiev.issue_isStalling
        self.issue_stall = cva5_wrapper.scaiev.issue_stall
        self.issue_isFlushing = cva5_wrapper.scaiev.issue_isFlushing
        self.issue_flush = cva5_wrapper.scaiev.issue_flush
        self.issue_valid = cva5_wrapper.scaiev.issue_valid
        self.pre_issue_exception_pending = cva5_wrapper.cva5.decode_and_issue_block.pre_issue_exception_pending

        self.commit_packets = [cva5_wrapper.cva5.commit_packet[0], cva5_wrapper.cva5.commit_packet[1]]
        assert(len(cva5_wrapper.cva5.commit_packet) == 2)
        self.scaiev_wrReg_decoupled = cva5_wrapper.scaiev.rf_wrReg

        self.retire_id_next = cva5_wrapper.cva5.id_block.retire_ids_next[0]
        try:
            self.retire_next = cva5_wrapper.cva5.id_block.gen_vanilla.id_block_vanilla.retire_next
        except AttributeError:
            self.retire_next = cva5_wrapper.cva5.id_block.gen_scaiev_injectable.retire_next

        # Necessary, but may break other things with Verilator.. related: https://github.com/verilator/verilator/issues/3919
        cva5_wrapper.cva5._discover_all()
        if 'gen_csrs' in cva5_wrapper.cva5._sub_handles:
            csr_unit_block = cva5_wrapper.cva5.gen_csrs.csr_unit_block
        else:
            csr_unit_block = cva5_wrapper.cva5.csr_unit_block
        csr_unit_block._discover_all()

        self.csr_mip = csr_unit_block.mip
        self.csr_mwrite = csr_unit_block.mwrite
        self.csr_inputs_r = csr_unit_block.csr_inputs_r
        self.csr_updated_csr = csr_unit_block.updated_csr
        self.csr_interrupt_pending = csr_unit_block.interrupt_pending
        self.csr_mtvec =  csr_unit_block.mtvec

        self.gc = cva5_wrapper.cva5.gc
        self.gc_exception_ack = cva5_wrapper.cva5.gc_unit_block.gen_gc_m_mode.exception_ack
        # CVA5 fork's ASIC branch split the exception interface in two structs
        try:
            self.lsu_exception_fromunit = cva5_wrapper.cva5.load_store_unit_block.exception_fromunit
            self.lsu_exception = None
        except AttributeError:
            self.lsu_exception_fromunit = None
            self.lsu_exception = cva5_wrapper.cva5._sub_handles["exception[0]"]

class CVA5TracePins_GLS(CVA5TracePins):
    def __init__(self, dut):
        self.clk = dut.clk
        self.rst = dut.rst
        self.issue_id = dut.export_issue_ID
        self.issue_RD_valid = dut.export_issue_RD_valid #writing to reg
        self.issue_RD_id = dut.export_issue_RD_id #logical RD reg ID
        self.issue_PC = dut.export_issue_PC
        self.issue_isStalling = dut.export_issue_isStalling
        self.issue_stall = dut.export_issue_stall
        self.issue_isFlushing = dut.export_issue_isFlushing
        self.issue_flush = dut.export_issue_flush
        self.issue_valid = dut.export_issue_valid
        self.pre_issue_exception_pending = dut.export_pre_issue_exception_pending

        self.commit_packets = [dut.export_commit_packet_0, dut.export_commit_packet_1]
        self.scaiev_wrReg_decoupled = dut.export_scaiev_wrReg_decoupled

        self.retire_next = dut.export_retire_next
        self.retire_id_next = dut.export_retire_ids_next_0

        self.csr_mip = dut.export_csr_mip
        self.csr_mwrite = dut.export_csr_mwrite
        self.csr_inputs_r = dut.export_csr_inputs_r
        self.csr_updated_csr = dut.export_csr_updated_csr
        self.csr_interrupt_pending = dut.export_csr_interrupt_pending
        self.csr_mtvec = dut.export_csr_mtvec

        self.gc = dut.export_gc
        self.gc_exception_ack = dut.export_gc_exception_ack
        self.lsu_exception_fromunit = dut.export_lsu_exception_fromunit

class _CVA5PendingInstr:
    def __init__(self, instr_id : int):
        self.possiblyValid = False
        self.instr_id = instr_id
        self.has_rd = False
        self.rd_id_logical = None
        self.rd_data = None
        self.pc = None
        self.mtvec = None

    def onIssue(self, pc : int, has_rd : bool, rd_id_logical : int, mtvec : int):
        self.possiblyValid = True
        #note: possiblyValid is not being reset on flush
        self.pc = pc
        self.has_rd = has_rd
        self.rd_id_logical = rd_id_logical
        self.rd_data = None
        self.mtvec = mtvec

    def onWriteback(self, rd_data : int):
        assert(self.rd_data is None)
        assert(self.pc is not None)
        self.rd_data = rd_data

    def onRetire(self):
        assert(self.pc is not None)
        assert((self.rd_data is None) or (self.rd_data >= 0) or (self.rd_id_logical == 0))
        self.possiblyValid = False
        self.rd_id_logical = None
        self.pc = None
        self.rd_data = None
        self.mtvec = None

class CVA5Tracer:
    """
    Tracer for CVA5, recording the PC and writeback values for each retired instruction.
    Records SCAIE-V decoupled ISAXes with rd zero.
    """

    def __init__(self, dut, SAMPLE_DELAY, trace_pins: CVA5TracePins, shutdown_event: Event, trace_queue: Queue, print_events: bool):
        self.dut = dut
        self.SAMPLE_DELAY = SAMPLE_DELAY
        self.rst = trace_pins.rst
        self.clk = trace_pins.clk
        self.trace_pins = trace_pins
        self.shutdown_event = shutdown_event

        self.trace_queue = trace_queue
        self.print_events = print_events

        self.pending_instrs_by_id = [_CVA5PendingInstr(i) for i in range(8)] #instr id 0..7

        cocotb.start_soon(self._produce_trace())

    def has_unhandled_external_machine_interrupt(self):
        #MIP writes always have meip masked off.
        return self.trace_pins.csr_mip_meip() and not self.trace_pins.csr_mwrite_en_mip()

    def is_entering_exception(self):
        return not (not self.trace_pins.gc_exception_ack.value)

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
        entering_exception = False

        while not self.shutdown_event.is_set():
            if self.is_entering_exception():
                entering_exception = True
            if self.trace_pins.issue_valid.value:
                #Ignore stalling&flushing, as the register file writeback may still occur for ALU instructions
                instr_id = self.trace_pins.issue_id.value.integer
                pc = self.trace_pins.issue_PC.value.integer
                has_rd = not(not self.trace_pins.issue_RD_valid.value)
                rd_id_logical = self.trace_pins.issue_RD_id.value.integer
                mtvec = self.trace_pins.csr_mtvec.value.integer
                if self.print_events:
                    self.dut._log.info("(Issue ID %d PC 0x%08x, has_rd=%d, rd_id=%d)" % (instr_id,pc,has_rd,rd_id_logical if has_rd else 0))
                self.pending_instrs_by_id[instr_id].onIssue(pc, has_rd, rd_id_logical, mtvec)
            for i in range(len(self.trace_pins.commit_packets)):
                commit_packet=self.trace_pins.commit_packets[i]
                if self.trace_pins.commit_packet_valid(commit_packet):
                    if i == 0 and self.trace_pins.pre_issue_exception_pending.value:
                        continue
                    instr_id = self.trace_pins.commit_packet_id(commit_packet).integer
                    rd_data = self.trace_pins.commit_packet_data(commit_packet)
                    is_decoupled = self.trace_pins.scaiev_wrReg_decoupled.value or False
                    idname = "decoupled [ignoring]" if (i == 1 and is_decoupled) else str(i)
                    if 'x' in rd_data.binstr:
                        rd_data = -1
                        if self.print_events:
                            self.dut._log.info("(Commit/Wb port %d: ID %s Data X)" % (i,idname))
                    else:
                        rd_data = rd_data.integer
                        if self.print_events:
                            self.dut._log.info("(Commit/Wb port %d: ID %s Data 0x%08x)" % (i,idname,rd_data))
                    if not (i == 1 and is_decoupled):
                        self.pending_instrs_by_id[instr_id].onWriteback(rd_data)
            # CVA5: retire_next.valid and .phys_id indicate presence/ID of a retirement with a register writeback
            # Retirements in general are indicated by retire_next.count and retire_ids_next[0]
            if self.trace_pins.retire_next_count().integer > 0 and not self.trace_pins.gc_writeback_supress():
                num_retires = self.trace_pins.retire_next_count().integer
                assert(num_retires <= 2)
                instr_id_first = self.trace_pins.retire_id_next.value.integer
                assert(instr_id_first < len(self.pending_instrs_by_id))
                if self.print_events:
                    self.dut._log.info("(Retire First ID %d Count %d)" % (instr_id_first,num_retires))
                for i in range(num_retires):
                    instr_id = (instr_id_first + i) % len(self.pending_instrs_by_id)
                    if self.trace_pins.lsu_exception_valid() and (self.trace_pins.lsu_exception_id().integer == instr_id):
                        # Early retire of store instructions
                        # Can happen in the same cycle where the LSU notices it should raise an exception instead
                        assert(i == num_retires-1) # No later instruction should retire
                        if self.print_events:
                            self.dut._log.info("(Ignoring Retire ID %d: LSU exception)" % (instr_id))
                        continue
                    pending_instr = self.pending_instrs_by_id[instr_id]
                    if pending_instr.has_rd and pending_instr.rd_id_logical == 0:
                        pending_instr.rd_data = 0
                    #use put_nowait, as we must be sure the receiver does not let timesteps pass in the meantime
                    self.trace_queue.put_nowait(CVA5TracedInstr(pending_instr.pc,
                                                                pending_instr.has_rd, pending_instr.rd_id_logical, pending_instr.rd_data,
                                                                pending_instr.mtvec, entering_exception))
                    self.pending_instrs_by_id[instr_id].onRetire()
                    entering_exception = False

            await clock_re
            await self._sample_delay()
        pass

