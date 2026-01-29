import cocotb

class QueueBroadcast:
    """
    Very simple one-to-many queue adapter for cocotb.
    Assumes put_nowait always succeeds on the outbound queues (i.e., there are no size limits)
    """
    def __init__(self, queueIn, *queueOuts):
        self.queueIn = queueIn
        self.queuesOut = [x for x in queueOuts]

        cocotb.start_soon(self._observeIn())

    @cocotb.coroutine
    async def _observeIn(self):
        while True:
            entry = await self.queueIn.get()
            for queueOut in self.queuesOut:
                queueOut.put_nowait(entry)

def dump_32bithex(f, data):
    if (len(data) & 3) != 0: #Is not 4-aligned
        fill_to_4align = ((len(data) + 3) & ~3) - len(data)
        data = bytearray(data) + bytearray([0] * fill_to_4align)
    for i in range(0, len(data), 4):
        word = data[i:i+4]
        f.write('%02x%02x%02x%02x\n' % (word[3], word[2], word[1], word[0]))

def test_envarg_true(env, argname):
    if not (argname in env):
        return False
    argval = env[argname]
    try:
        return argval.lower() == "true" or int(argval) > 0
    except ValueError:
        return False #not an int

def get_envarg_or(env, argname, default):
    if not (argname in env):
        return default
    return env[argname]

def gls_init_defaults(dut):
    """
    Basic IO initialization for GLS (power, DFT)
    Assumes dut._discover_all() has been called.
    """
    if 'VSS' in dut._sub_handles:
        dut.VSS.setimmediatevalue(0)
        dut.VDD.setimmediatevalue(1)
    if 'shift_enable' in dut._sub_handles:
        dut.shift_enable.setimmediatevalue(0)
        dut.test_mode.setimmediatevalue(0)
    if 'DFT_sdi_1' in dut._sub_handles:
        dut.DFT_sdi_1.setimmediatevalue(0)
    if 'SI1' in dut._sub_handles:
        dut.SI1.setimmediatevalue(0)
