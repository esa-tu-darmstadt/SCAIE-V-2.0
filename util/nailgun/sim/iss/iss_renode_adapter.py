# Loads a Longnail-created .py ISAX file, originally intended for use in renode
# -> The adapter is very narrowly scoped to the Renode interfaces used by those scripts
from iss.iss_adapter import ISSTbAdapter, ISSISAXHandler, ISAX_DECOUPLED_FLAG
from pyriscvvp import RVVI_TRUE, RVVI_FALSE, RVVI_STEP_YIELD
import cocotb
from cocotb.queue import Queue
from cocotb.binary import BinaryValue
from collections import deque
import threading
import importlib.util
import sys
from copy import deepcopy
from typing import Callable

class _RegisterValueStub:
    def __init__(self, val, len):
        self.RawValue = val
        self.len = len
    def Create(val, len):
        return _RegisterValueStub(val, len)

class _SystemBusStub:
    def __init__(self, adapter: ISSTbAdapter):
        self.adapter = adapter
    def ReadByte(self, addr):
        return self.adapter.iss.rvviRefMemoryRead(addr, 1)
    def WriteByte(self, addr, data):
        self.adapter.iss.rvviRefMemoryWrite(addr, data, 1)

class _MachineStub:
    def __init__(self, adapter: ISSTbAdapter):
        self.SystemBus = _SystemBusStub(adapter)

class _CPUStub:
    def __init__(self, adapter: ISSTbAdapter):
        self.adapter = adapter
    def _update(self):
        self._PC_pre = self.adapter.iss.rvviGetTransition()[0]
        self.PC = _RegisterValueStub(self._PC_pre, 64 if self.adapter.IS_64 else 32)
    def _apply_pc(self):
        if self.PC.RawValue != self._PC_pre:
            assert(self.PC.len == (64 if self.adapter.IS_64 else 32))
            self.adapter.iss.rvviRefPcSet(self.PC.RawValue)
    def GetRegister(self, addr):
        return _RegisterValueStub(self.adapter.iss.rvviRefGprGet(addr), 64 if self.adapter.IS_64 else 32)
    def SetRegister(self, addr, data):
        assert(data.len == (64 if self.adapter.IS_64 else 32))
        self.adapter.iss.rvviRefGprSet(addr, data.RawValue)

def _compareStateDicts(a, b):
    """ basic diff of nested dicts with string keys, outputting a nested dict with the differences, using '(-)' and '(+)' as suffixes for individual changes """
    all_keys = set(a.keys())
    all_keys.update(b.keys())
    diff_dict = {}
    for k in all_keys:
        if not isinstance(k, str):
            raise ValueError("encountered non-string key")
        if k not in a:
            diff_dict[k+'(+)'] = b[k]
        elif k not in b:
            diff_dict[k+'(-)'] = a[k]
        else: # -> key exists in both
            _a = a[k]
            _b = b[k]
            if isinstance(_a, dict) and isinstance(_b, dict):
                subdiff = _compareStateDicts(_a, _b)
                if len(subdiff) > 0:
                    diff_dict[k] = subdiff
            elif _a != _b:
                diff_dict[k+'(-)'] = _a
                diff_dict[k+'(+)'] = _b
    return diff_dict


class ISSRenodeISAXAdapter(ISSISAXHandler):
    _NextUniqueID=1
    _NextUniqueID_lock = threading.Lock()
    def __init__(self, dut, script_path: str, cb_encoding_is_decoupled: Callable[[int], bool], cb_encoding_name: Callable[[int], str|None], PRINT_ISS: bool):
        self.dut = dut
        self.cb_encoding_is_decoupled = cb_encoding_is_decoupled
        self.cb_encoding_name = cb_encoding_name
        self.PRINT_ISS = PRINT_ISS
        self.states = dict()
        with ISSRenodeISAXAdapter._NextUniqueID_lock:
            moduleName = "renode_isax_%d" % ISSRenodeISAXAdapter._NextUniqueID
            ISSRenodeISAXAdapter._NextUniqueID += 1
        spec = importlib.util.spec_from_file_location(moduleName, script_path)
        self.module = importlib.util.module_from_spec(spec)
        self.module.__file__ = str(script_path)

        self.states[0] = dict()
        self.module.state = self.states[0]
        self.module.machine = None
        self.module.cpu = None
        self.module.RegisterValue = _RegisterValueStub

        sys.modules[moduleName] = self.module
        spec.loader.exec_module(self.module)

    def handleContextSwitch(self, context: int):
        """Selects the state field of the ISAX module by the given context number"""
        uninitialized = False
        if not context in self.states:
            self.states[context] = dict()
            uninitialized = True
        self.module.state = self.states[context]
        if uninitialized and "init_cust_regs" in self.module:
            # Reinit the state
            # Note: the initial state is initialized during exec_module
            self.module.init_cust_regs()

    def handleInstr(self, adapter: ISSTbAdapter) -> int:
        # Update the machine, cpu globals in the module
        self.module.machine = _MachineStub(adapter)
        _cpu = _CPUStub(adapter)
        _cpu._update()
        self.module.cpu = _cpu
        pc, instr = adapter.iss.rvviGetTransition()[0:2]
        decoupled_flag = ISAX_DECOUPLED_FLAG if self.cb_encoding_is_decoupled(instr) else 0
        try:
            if self.PRINT_ISS:
                state_pre = deepcopy(self.module.state)
            self.module.decode(instr)
            _cpu._apply_pc()
            if self.PRINT_ISS:
                encoding_name = self.cb_encoding_name(instr)
                self.dut._log.info("ISS pc %08x: Handled renode ISAX %s" % (pc, "<unknown>" if encoding_name is None else encoding_name))
                all_diffs = _compareStateDicts(state_pre, self.module.state)
                if len(all_diffs) > 0:
                    self.dut._log.info("ISS renode ISAX state changes: %s" % str(all_diffs))
            return RVVI_TRUE | decoupled_flag
        except ZeroDivisionError:
            # decode function does a zero division if it couldn't find the instruction
            return RVVI_FALSE
