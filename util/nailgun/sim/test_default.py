import cocotb
from cocotb.triggers import Timer, RisingEdge
from cocotb.queue import Queue
from processortest import ProcessorTest
from testutil import test_envarg_true, get_envarg_or, gls_init_defaults

# Sensible values for 200MHz GLS: CLK_PERIOD=5000, SAMPLE_DELAY=2500, ASSIGN_DELAY=300
CLK_PERIOD = int(get_envarg_or(cocotb.plusargs, "CLK_PERIOD", "1000")) # Length of a clock period in ps
SAMPLE_DELAY = int(get_envarg_or(cocotb.plusargs, "SAMPLE_DELAY", "0")) # When to sample an output by the core
ASSIGN_DELAY = int(get_envarg_or(cocotb.plusargs, "ASSIGN_DELAY", "0")) # When to change an input to the core
TIMEOUT_PERIODS = int(get_envarg_or(cocotb.plusargs, "CYCLE_TIMEOUT", "1000")) # Number of clock periods until timeout
PRINT_CLK = test_envarg_true(cocotb.plusargs, "PRINT_CLK")

@cocotb.test()
async def run_test(dut):
    # dut._discover_all() works in recent versions of Verilator.
    # Note that _discover_all() on inner scopes can still cause issues
    #  trying to overwrite a signal value.
    # -> https://github.com/verilator/verilator/issues/3919
    dut._discover_all()

    is_gls = test_envarg_true(cocotb.plusargs, "GLS")

    if is_gls:
        gls_init_defaults(dut)

    core_trace_queue_toProcessorTest = Queue()
    iss_trace_queue = Queue()

    proctest = ProcessorTest(dut, CLK_PERIOD, SAMPLE_DELAY, ASSIGN_DELAY, TIMEOUT_PERIODS, core_trace_queue_toProcessorTest, iss_trace_queue, env=cocotb.plusargs)

    await proctest.run(PRINT_CLK)

    await RisingEdge(proctest.clk)
