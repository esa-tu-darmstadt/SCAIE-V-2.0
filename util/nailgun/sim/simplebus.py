"""
Minimal handshaked memory bus interface.

Intended for easy integration into any core, or as an example to implement other interface models.
"""

import cocotb
from cocotb.triggers import RisingEdge, ReadOnly, Lock
from cocotb_bus.drivers import BusDriver
from cocotb.binary import BinaryValue

from memutil import MemView
from busutil import BusDelay

import array

class SimpleBusProtocolError(Exception):
    pass

simple_mem_signals = [
    "req_valid", "req_ready",
    "req_addr",
    "req_write_be",
    "req_write_data",
    "resp_valid",
    "resp_data"
]

class SimpleBusSlave(BusDriver):
    """Minimal handshaked memory bus slave
    """

    def _get_signals_list(self):
        return simple_mem_signals
    def _retrieve_signals(self):
        self.req_valid = self.bus.req_valid
        self.req_ready = self.bus.req_ready
        self.req_addr = self.bus.req_addr
        self.req_write_be = self.bus.req_write_be
        self.req_read_en = None
        self.req_write_en = None
        self.req_write_data = self.bus.req_write_data
        self.resp_valid = self.bus.resp_valid
        self.resp_data = self.bus.resp_data

    def __init__(self, entity, name, clock, memview,
                 big_endian=False, SAMPLE_DELAY=0, ASSIGN_DELAY=0,
                 artificial_delay=0, enable_prints=True,
                 **kwargs):
        self._signals = self._get_signals_list()
        BusDriver.__init__(self, entity, name, clock, **kwargs)
        self._retrieve_signals()
        self.clock = clock
        self.busdelay = BusDelay(SAMPLE_DELAY, ASSIGN_DELAY)

        self.memview = memview

        self.big_endian = big_endian
        self.enable_prints = enable_prints

        self.artificial_delay = artificial_delay


        cocotb.start_soon(self._process())

    def _invalidate_dout(self):
        dout_val = BinaryValue(n_bits=self.resp_data.value.n_bits, bigEndian=self.big_endian)
        dout_val.buff = b'\xAA' * (self.resp_data.value.n_bits >> 3) #>>3 -> uint div by 8
        self.resp_data.value = dout_val
        self.resp_valid.value = 0

    @cocotb.coroutine
    async def _process(self):
        clock_re = RisingEdge(self.clock)

        self.req_ready.value = 0
        self._invalidate_dout()
        await clock_re
        self.busdelay.assign_delay()

        while True:
            self.req_ready.value = 1
            while True:
                self.busdelay.sample_delay(True)
                if self.req_valid.value:
                    break
                await clock_re
                self.busdelay.assign_delay()
                self._invalidate_dout()

            if self.req_write_en is not None:
                is_writing = (self.req_write_en.value != 0)
            elif self.req_write_be is not None:
                is_writing = (self.req_write_be.value != 0)
            else:
                is_writing = False

            if self.req_read_en is not None:
                if self.req_read_en.value == 0 and not is_writing:
                    # would be weird to post such a request
                    raise SimpleBusProtocolError("Bus request with neither read nor write")
                if self.req_read_en.value != 0 and is_writing:
                    # would also be weird, since it would just return the written data
                    raise SimpleBusProtocolError("Bus request with both read and write")
                # in the end, is_reading = not is_writing
                is_reading = (self.req_read_en.value != 0)
            else:
                is_reading = not is_writing

            if is_writing:
                _st = int(self.req_addr)
                _end = _st + (self.req_write_data.value.n_bits >> 3) #/ 8
                word = self.req_write_data.value
                if self.req_write_be is not None:
                    wstrb = self.req_write_be.value
                else:
                    wstrb = BinaryValue(("1" if (self.req_write_en.value != 0) else "0") * (_end-_st))

                word.big_endian = self.big_endian
                word = array.array('B', word.buff)

                self.memview.write(_st,_end,word,wstrb)

                if self.enable_prints:
                    print("simble bus write - addr %08x, data %08x, wstrb %s" % (_st, int(self.req_write_data), str(wstrb)))

            _st = int(self.req_addr)
            _end = _st + (self.resp_data.value.n_bits >> 3) #/ 8

            await clock_re
            self.busdelay.assign_delay()
            self._invalidate_dout()
            self.req_ready.value = 0
            for i in range(self.artificial_delay):
                await clock_re
                self.busdelay.assign_delay()

            if is_reading:
                read_val = self.memview.read(_st,_end, self.resp_data.value.n_bits, self.big_endian)
                self.resp_data.value = read_val
                self.resp_valid.value = 1

            if self.enable_prints:
                print("simple bus read - addr %08x, data %08x\n" % (_st, read_val.integer))

cva5_mem_signals = [
    "new_request",
    "addr",
    "re", "we", "be",
    "data_in", "data_out", "data_valid",
    "ready"
]

class CVA5SimpleBusSlave(SimpleBusSlave):
    """CVA5 exposed internal memory interface slave (not a standard configuration)
    """

    def _get_signals_list(self):
        return cva5_mem_signals
    def _retrieve_signals(self):
        self.req_valid = self.bus.new_request
        self.req_ready = self.bus.ready
        self.req_addr = self.bus.addr
        self.req_write_be = self.bus.be
        self.req_read_en = self.bus.re
        self.req_write_en = self.bus.we
        self.req_write_data = self.bus.data_in
        self.resp_valid = self.bus.data_valid
        self.resp_data = self.bus.data_out

    def __init__(self, entity, name, clock, memview,
                 big_endian=False, SAMPLE_DELAY=0, ASSIGN_DELAY=0,
                 artificial_delay=0, enable_prints=True,
                 **kwargs):
        SimpleBusSlave.__init__(self, entity, name, clock, memview,
                                big_endian, SAMPLE_DELAY, ASSIGN_DELAY,
                                artificial_delay, enable_prints,
                                **kwargs)
