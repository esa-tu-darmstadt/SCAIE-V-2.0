# Based on tapasco-pe-tb
import os
from typing import Callable
from collections import deque
import importlib
import cocotb
from cocotb.triggers import RisingEdge, Event, First
from cocotb.queue import Queue
from trace.cva5_instr_trace import CVA5Tracer, CVA5TracePins_Standard, CVA5TracePins_GLS
from trace.cva6_instr_trace import CVA6Tracer, CVA6RVFITracePins
from trace.instr_trace import TracedInstr
from iss.iss_adapter import ISSTbAdapter, ISSISAXHandler
from iss.iss_renode_adapter import ISSRenodeISAXAdapter
from iss.isax.isax_predefined import ISSFenceKillISAX, ISSContextISAX
from processortest import ProcessorTest
from testutil import QueueBroadcast, test_envarg_true, get_envarg_or, gls_init_defaults
from isax_yaml_tools import extract_encodings, yaml_instr_filter_decoupled_writeback, match_instruction

# Sensible values for 200MHz GLS: CLK_PERIOD=5000, SAMPLE_DELAY=2500, ASSIGN_DELAY=300
CLK_PERIOD = int(get_envarg_or(cocotb.plusargs, "CLK_PERIOD", "1000")) # Length of a clock period in ps
SAMPLE_DELAY = int(get_envarg_or(cocotb.plusargs, "SAMPLE_DELAY", "0")) # When to sample an output by the core
ASSIGN_DELAY = int(get_envarg_or(cocotb.plusargs, "ASSIGN_DELAY", "0")) # When to change an input to the core
TIMEOUT_PERIODS = int(get_envarg_or(cocotb.plusargs, "CYCLE_TIMEOUT", "1000")) # Number of clock periods until timeout
PRINT_CLK = test_envarg_true(cocotb.plusargs, "PRINT_CLK")
PRINT_ISS = test_envarg_true(cocotb.plusargs, "PRINT_ISS")

class ISSDriver:
    def __init__(self, dut, shutdown_event: Event, \
            core_trace_queue: Queue[TracedInstr], iss_trace_queue_out: Queue[TracedInstr], \
            pred_is_start_of_interrupt: Callable[[TracedInstr],bool],
            isaxes: list[ISSISAXHandler],
            env: dict, IS_64: bool):
        self.dut = dut
        self.shutdown_event = shutdown_event
        self.core_trace_queue = core_trace_queue
        self.iss_trace_queue_out = iss_trace_queue_out
        self.iss_adapter = ISSTbAdapter(dut, isaxes, [], env=env, IS_64=IS_64, PRINT_ISS=PRINT_ISS)
        self.core_interrupt_pending = False
        self.pred_is_start_of_interrupt = pred_is_start_of_interrupt
        cocotb.start_soon(self._handle_iss())

    def set_core_interrupt_pending(self):
        """
        NOTE: Must only be called if a new interrupt will actually be triggered in the core.
        -> if the core's sticky interrupt flag (meip) is not reset yet, and interrupts are currently disabled,
           nothing will happen if a new interrupt is asserted
        """
        self.core_interrupt_pending = True

    @cocotb.coroutine
    async def _handle_iss(self):
        shutdown_trigger = self.shutdown_event.wait()
        core_trace_queue_delayed: deque[TracedInstr] = deque()
        shutdown_iss: bool = False
        while not shutdown_iss:
            # Get the next trace event from the HDL core (or break on shutdown)
            core_traced: TracedInstr|None = None
            if self.core_trace_queue.qsize() > 0:
                core_traced = self.core_trace_queue.get_nowait()
            else:
                get_trigger = cocotb.start_soon(self.core_trace_queue.get())
                core_traced = await First(shutdown_trigger, get_trigger)
            if self.shutdown_event.is_set():
                break
            # Check if the core transferred to the interrupt handler
            if self.core_interrupt_pending and self.pred_is_start_of_interrupt(core_traced):
                self.dut._log.info("ISS transferring to interrupt handler in %d cycle(s)" % len(core_trace_queue_delayed))
                # Tell the ISS when to process the interrupt
                self.iss_adapter.handleInterruptInNumCommits(len(core_trace_queue_delayed))
                self.core_interrupt_pending = False
            core_trace_queue_delayed.append(core_traced)
            # Since the ISS needs to know about the interrupt jump one tick in advance,
            # keep it one step back from the core while an interrupt is pending.
            while len(core_trace_queue_delayed) > (1 if self.core_interrupt_pending else 0):
                #self.iss_adapter.traceSetCompareResult = not self.chickenbit_csr_handler.disableTraceCompare
                self.iss_adapter.traceSetCompareResult = True
                res:list[TracedInstr]|None = await self.iss_adapter.processStep()
                if res is None:
                    shutdown_iss = True
                    break
                # Assuming that one ISS step never does several instructions,
                #  required for the loop condition above to keep
                #  the correct distance from handled interrupts.
                assert(len(res) <= 1)
                for i in range(len(res)):
                    core_traced = core_trace_queue_delayed.popleft()
                    await self.iss_trace_queue_out.put(res[i])

        self.iss_adapter.shutdown()

def _compare_pc_mtvec_interrupt(coreTraceEntry):
    #Assume mode == DIRECT -> interrupt handler PC == mtvec
    # (always true in CVA5)
    assert((coreTraceEntry.mtvec & 3) == 0)
    return coreTraceEntry.mtvec == coreTraceEntry.pc and not coreTraceEntry.is_exception_handler_entry

@cocotb.test()
async def run_test(dut):
    # dut._discover_all() works in recent versions of Verilator.
    # Note that _discover_all() on inner scopes can still cause issues
    #  trying to overwrite a signal value.
    # -> https://github.com/verilator/verilator/issues/3919
    dut._discover_all()

    is_gls = test_envarg_true(cocotb.plusargs, "GLS")

    core_name = cocotb.plusargs["CORE_NAME"]
    isax_yamls = get_envarg_or(cocotb.plusargs, "ISAX_YAML", None)
    isax_python = get_envarg_or(cocotb.plusargs, "ISAX_PYTHON", "")
    isax_renode = get_envarg_or(cocotb.plusargs, "ISAX_RENODE", None)
    number_of_contexts = int(get_envarg_or(cocotb.plusargs, "NUMBER_OF_CONTEXTS", "1"))
    if number_of_contexts <= 0:
        raise ValueError("NUMBER_OF_CONTEXTS must be at least 1")
    if number_of_contexts >= 10**12:
        raise ValueError("NUMBER_OF_CONTEXTS must be below 10**12")

    if is_gls:
        gls_init_defaults(dut)

    core_trace_queue_toProcessorTest = Queue()
    core_trace_queue_toISSDriver = Queue()
    core_trace_queue: Queue[TracedInstr] = Queue()
    _trace_queue_broadcaster = QueueBroadcast(core_trace_queue, *[core_trace_queue_toProcessorTest, core_trace_queue_toISSDriver])

    completion_event = Event()
    iss_trace_queue: Queue[TracedInstr] = Queue()

    proctest = ProcessorTest(dut, CLK_PERIOD, SAMPLE_DELAY, ASSIGN_DELAY, TIMEOUT_PERIODS, core_trace_queue_toProcessorTest, iss_trace_queue, env=cocotb.plusargs)

    core_is_64 = False
    if core_name.startswith("CVA6"):
        cva6trace_pins = CVA6RVFITracePins(dut, dut)
        cva6trace = CVA6Tracer(dut, SAMPLE_DELAY, cva6trace_pins, completion_event, core_trace_queue)
        core_is_64 = core_name.startswith("CVA6_64")
    elif core_name.startswith("CVA5"):
        cva5trace_pins = CVA5TracePins_GLS(dut) if is_gls else CVA5TracePins_Standard(dut, dut.cva5_inst.cva5)
        cva5trace = CVA5Tracer(dut, SAMPLE_DELAY, cva5trace_pins, completion_event, core_trace_queue, PRINT_ISS)
    else:
        raise Exception("Unsupported core: Missing execution trace implementation")

    # Retrieve ISAX encodings
    if isax_yamls is not None:
        all_encodings = dict()
        decoupled_encodings = dict()
        for isax_yaml in isax_yamls.split(';'):
            all_encodings.update(extract_encodings(isax_yaml))
            decoupled_encodings.update(extract_encodings(isax_yaml, yaml_instr_filter_decoupled_writeback))
    else:
        all_encodings = dict()
        decoupled_encodings = dict()

    # Load predefined ISAX handlers
    pyhandlers = dict()
    for pymodule_name in ("iss.isax.isax_dotp_bias","iss.isax.isax_zol"):
        try:
            pymodule = importlib.import_module(pymodule_name)
            pyhandlers.update(pymodule.getHandlers(dut))
        except ImportError:
            dut._log.warning("Could not import ISAX module %s" % pymodule_name)
    dut._log.info("Found predefined ISAX handlers: %s (selection: %s)" % (','.join(pyhandlers.keys()),
                                                                          isax_python if isax_python else "<none>"))

    isaxes = []
    context_switch_callbacks = []
    if isax_python != "":
        # Add requested predefined ISAX handlers
        for pyhandler_name in isax_python.split(','):
            if pyhandler_name == "":
                continue
            if pyhandler_name not in pyhandlers:
                dut._log.warning("Could not find requested predefined ISAX handler %s" % pyhandler_name)
                continue
            isaxes.append(pyhandlers[pyhandler_name])

    if isax_renode is not None:
        # Use the auto-generated renode ISAX script
        def find_encoding_match(instr, encodings):
            # Check for matching encodings
            for name, pattern in encodings.items():
                matches = True
                for i in range(32):
                    if pattern[i] != "-" and int(pattern[i]) != ((instr >> 31-i) & 1):
                        matches = False
                        break
                if matches:
                    return True, name
            return False, None
        def cb_instr_decoupled(instr):
            found, name = find_encoding_match(instr, decoupled_encodings)
            return found
        def cb_instr_name(instr):
            found, name = find_encoding_match(instr, all_encodings)
            return name
        renode_adapter = ISSRenodeISAXAdapter(dut, isax_renode, cb_instr_decoupled, cb_instr_name, PRINT_ISS)
        isaxes.append(renode_adapter)
        context_switch_callbacks.append(lambda ctxnum: renode_adapter.handleContextSwitch(ctxnum))
    if len(decoupled_encodings) > 0:
        # For now, always add disaxkill/disaxfence (can't tell if it was disabled in SCAIE-V)
        # Add as last in case it produces an ISAX encoding clash
        isaxes.append(ISSFenceKillISAX(dut, True, True))
    if number_of_contexts > 1:
        isaxes.append(ISSContextISAX(dut, number_of_contexts, context_switch_callbacks))

    issdriver = ISSDriver(dut, completion_event, core_trace_queue_toISSDriver, iss_trace_queue, _compare_pc_mtvec_interrupt, isaxes,
                          cocotb.plusargs, core_is_64)

    await proctest.run(PRINT_CLK)
    completion_event.set()

    await RisingEdge(dut.clk)
