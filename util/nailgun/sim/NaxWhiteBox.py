import re
import cocotb
from cocotb.triggers import FallingEdge


def get_cycle_count(CLK_PERIOD):
    return cocotb.utils.get_sim_time(units="ps") / CLK_PERIOD

def read_defines(file_path):
    defines = {}

    with open(file_path, 'r') as f:
        for line in f:
            # Match lines that start with #define, and capture the define name and its value
            match = re.match(r'#define\s+(\w+)\s+(.+)', line.strip())
            if match:
                define_name = match.group(1)
                define_value = match.group(2)
                defines[define_name] = define_value

    return defines

# Translated C++ code from: https://github.com/SpinalHDL/NaxRiscv/blob/1c50e84d9a6f7ea93d7153f12906c25552267b9d/src/test/cpp/naxriscv/src/main.cpp#L998
class NaxWhiteBox:
    def __init__(self, dut, CLK_PERIOD, disasm, defines_path, gem5_output_path):
        self.dut = dut.top_INST.NaxRiscv_inst

        defines = read_defines(defines_path)
        self.COMMIT_COUNT = int(defines["COMMIT_COUNT"])
        ROB_SIZE = int(defines["ROB_SIZE"])
        DISPATCH_COUNT = int(defines["DISPATCH_COUNT"])
        INTEGER_PHYSICAL_DEPTH = int(defines["INTEGER_PHYSICAL_DEPTH"])
        ISR_INTEGER_PHYSICAL_DEPTH = int(defines["ISR_INTEGER_PHYSICAL_DEPTH"]) if "ISR_INTEGER_PHYSICAL_DEPTH" in defines else INTEGER_PHYSICAL_DEPTH
        INTEGER_WRITE_COUNT = int(defines["INTEGER_WRITE_COUNT"])
        ROB_COMPLETIONS_PORTS = int(defines["ROB_COMPLETIONS_PORTS"])
        ISSUE_PORTS = int(defines["ISSUE_PORTS"])
        FLOAT_WRITE_COUNT = int(defines["FLOAT_WRITE_COUNT"]) if "FLOAT_WRITE_COUNT" in defines else None

        self.INTEGER_PHYSICAL_DEPTH = INTEGER_PHYSICAL_DEPTH
        self.ISR_INTEGER_PHYSICAL_DEPTH = ISR_INTEGER_PHYSICAL_DEPTH

        # Initialize the class with the nax object (could be a simulator entity)
        self.robCtx = [{} for _ in range(ROB_SIZE)]
        self.fetchCtx = [{} for _ in range(4096)]
        self.opCtx = [{} for _ in range(4096)]
        self.opIdInFlight = []
        self.sqToOp = [None] * 256

        # Initialize integer_write_valid using dynamic mapping based on nax attributes
        self.robToPc = [self.dut._id(f"robToPc_pc_{i}", extended=False) for i in range(DISPATCH_COUNT)]
        self.integer_write_valid = [self.dut._id(f"integer_write_{i}_valid", extended=False) for i in range(INTEGER_WRITE_COUNT)]
        self.integer_write_robId = [self.dut._id(f"integer_write_{i}_robId", extended=False) for i in range(INTEGER_WRITE_COUNT)]
        self.integer_write_data = [self.dut._id(f"integer_write_{i}_data", extended=False) for i in range(INTEGER_WRITE_COUNT)]

        try:
            self.float_write_valid = [self.dut._id(f"float_write_{i}_valid", extended=False) for i in range(FLOAT_WRITE_COUNT)]
            self.float_write_robId = [self.dut._id(f"float_write_{i}_robId", extended=False) for i in range(FLOAT_WRITE_COUNT)]
            self.float_write_data = [self.dut._id(f"float_write_{i}_data", extended=False) for i in range(FLOAT_WRITE_COUNT)]

            self.float_flags_robId = [self.dut._id(f"fpuRobToFlags_{i}_robId", extended=False) for i in range(FLOAT_WRITE_COUNT)]
            self.float_flags_mask = [self.dut._id(f"fpuRobToFlags_{i}_mask", extended=False) for i in range(FLOAT_WRITE_COUNT)]
        except:
            pass

        self.rob_completions_valid = [self.dut._id(f"RobPlugin_logic_whitebox_completionsPorts_{i}_valid", extended=False) for i in range(ROB_COMPLETIONS_PORTS)]
        self.rob_completions_payload = [self.dut._id(f"RobPlugin_logic_whitebox_completionsPorts_{i}_payload_id", extended=False) for i in range(ROB_COMPLETIONS_PORTS)]

        self.rob_phy_rs0 = [self.dut._id(f"RobPlugin_logic_storage_PHYS_RS_0_banks_{i}", extended=False) for i in range(DISPATCH_COUNT)]
        self.rob_phy_rs1 = [self.dut._id(f"RobPlugin_logic_storage_PHYS_RS_1_banks_{i}", extended=False) for i in range(DISPATCH_COUNT)]

        self.rf_uses_ffs = False

        try:
            self.rf0 = [self.dut._id(f"integer_RegFilePlugin_logic_regfile_latchBanks_0.latches_{i}_storage", extended=False) for i in range(INTEGER_PHYSICAL_DEPTH - 1)]
            try:
                self.rf1 = [self.dut._id(f"integer_RegFilePlugin_logic_regfile_latchBanks_1.latches_{i}_storage", extended=False) for i in range(ISR_INTEGER_PHYSICAL_DEPTH - 1)]
            except:
                self.rf1 = None

        except:
            try:
                self.rf0 = [self.dut._id(f"integer_RegFilePlugin_logic_regfile_latches.latches_{i}_storage", extended=False) for i in range(INTEGER_PHYSICAL_DEPTH - 1)]
                self.rf1 = None
            except:
                # RF is implemented using FFs
                self.rf_uses_ffs = True
                try:
                    self.rf0 = self.dut._id(f"integer_RegFilePlugin_logic_regfile_fpgaBanks_0.banks_0_ram", extended=False)
                    try:
                        self.rf1 = self.dut._id(f"integer_RegFilePlugin_logic_regfile_fpgaBanks_1.banks_0_ram", extended=False)
                    except:
                        self.rf1 = None
                except:
                    self.rf0 = [self.dut._id(f"integer_RegFilePlugin_logic_regfile_banks_0_ram", extended=False) for i in range(INTEGER_PHYSICAL_DEPTH - 1)]
                    self.rf1 = None

        self.issue_valid = [self.dut._id(f"DispatchPlugin_logic_whitebox_issuePorts_{i}_valid", extended=False) for i in range(ISSUE_PORTS)]
        self.issue_robId = [self.dut._id(f"DispatchPlugin_logic_whitebox_issuePorts_{i}_payload_robId", extended=False) for i in range(ISSUE_PORTS)]
        self.sq_alloc_valid = [self.dut._id(f"sqAlloc_{i}_valid", extended=False) for i in range(DISPATCH_COUNT)]
        self.sq_alloc_id = [self.dut._id(f"sqAlloc_{i}_id", extended=False) for i in range(DISPATCH_COUNT)]
        self.decoded_fetch_id = [self.dut._id(f"FrontendPlugin_decoded_FETCH_ID_{i}", extended=False) for i in range(DISPATCH_COUNT)]
        self.decoded_mask = [self.dut._id(f"FrontendPlugin_decoded_Frontend_DECODED_MASK_{i}", extended=False) for i in range(DISPATCH_COUNT)]
        self.decoded_instruction = [self.dut._id(f"FrontendPlugin_decoded_Frontend_INSTRUCTION_DECOMPRESSED_{i}", extended=False) for i in range(DISPATCH_COUNT)]
        self.decoded_pc = [self.dut._id(f"FrontendPlugin_decoded_PC_{i}", extended=False) for i in range(DISPATCH_COUNT)]
        self.dispatch_mask = [self.dut._id(f"FrontendPlugin_dispatch_Frontend_DISPATCH_MASK_{i}", extended=False) for i in range(DISPATCH_COUNT)]

        self.gem5 = open(gem5_output_path, 'w')
        self.disasm = disasm
        self.statsCaptureEnable = True
        class Stats:
            def __init__(self):
                self.cycles = 0
                self.commits = 0
                self.reschedules = 0
                self.reschedulesTrap = 0
                self.reschedulesBranch = 0
                self.pcHist = {}
        self.stats = Stats()

        # Variables for opCounter and period
        self.opCounter = 0
        self.period = CLK_PERIOD

    def read_reg(self, reg, idx, limit):
        if idx >= limit:
            return None
        if self.rf_uses_ffs:
            return reg[idx].value.integer
        if idx == 0:
            return 0
        return reg[idx - 1].value.integer

    def traceT2s(self, time):
        return str(time) if time != 0 else ""

    def trace(self, opId):
        op = self.opCtx[opId]

        assembly = self.disasm(op['instruction'].buff[::-1])[0]
        padding = max(4, 25 - len(assembly))

        detail = ""
        if "robId" in op:
            robId = op['robId']
            rob = self.robCtx[robId]
            phys_rs0 = rob['PHYS_RS0'] if 'PHYS_RS0' in rob else "?"
            phys_rs1 = rob['PHYS_RS1'] if 'PHYS_RS1' in rob else "?"
            rs0_0 = f"0x{op['RS0_VAL_0']:08x}" if 'RS0_VAL_0' in op else "?"
            rs0_1 = f"0x{op['RS0_VAL_1']:08x}" if 'RS0_VAL_1' in op else "?"
            rs1_0 = f"0x{op['RS1_VAL_0']:08x}" if 'RS1_VAL_0' in op else "?"
            rs1_1 = f"0x{op['RS1_VAL_1']:08x}" if 'RS1_VAL_1' in op else "?"
            detail = (' ' * padding) + f"ROB={robId} RS0={phys_rs0} RS1={phys_rs1} RS0_0={rs0_0} RS0_1={rs0_1} RS1_0={rs1_0} RS1_1={rs1_1}"

        fetch = self.fetchCtx[op['fetchId']]
        # Simulating gem5 trace output
        self.gem5.write(f"O3PipeView:fetch:{self.traceT2s(fetch['fetchAt'])}:0x{op['pc'].integer:08x}:0:{op['counter']}:{assembly}{detail}\n")
        self.gem5.write(f"O3PipeView:decode:{self.traceT2s(fetch['decodeAt'])}\n")
        self.gem5.write(f"O3PipeView:rename:{self.traceT2s(op['renameAt'])}\n")
        self.gem5.write(f"O3PipeView:dispatch:{self.traceT2s(op['dispatchAt'] if "dispatchAt" in op else 0)}\n")
        self.gem5.write(f"O3PipeView:issue:{self.traceT2s(op['issueAt'] if "issueAt" in op else 0)}\n")
        self.gem5.write(f"O3PipeView:complete:{self.traceT2s(op['completeAt'] if "completeAt" in op else 0)}\n")
        # NOTE: storeAt can currently be not inferred?
        # self.gem5.write(f"O3PipeView:retire:{self.traceT2s(op['commitAt'] if "commitAt" in op else 0)}:store:{self.traceT2s(op['storeAt'] if op['sqAllocated'] else 0)}\n")
        self.gem5.write(f"O3PipeView:retire:{self.traceT2s(op['commitAt'] if "commitAt" in op else 0)}:store:{self.traceT2s(0)}\n")

    def onReset(self):
        for rob in self.robCtx:
            rob.clear()

    def preCycle(self):
        if self.dut.Lsu2Plugin_logic_special_atomic_storeWhitebox_valid.value:
            if self.dut.Lsu2Plugin_logic_special_atomic_storeWhitebox_isSc.value:
                self.scValid = True
                self.scPassed = self.dut.Lsu2Plugin_logic_special_atomic_storeWhitebox_scPassed.value

        if self.dut.robToPc_valid.value:
            for i, robToPc in enumerate(self.robToPc):
                robId = self.dut.robToPc_robId.value + i
                self.robCtx[robId]['pc'] = robToPc.value

        try:
            for i, float_flags_mask in enumerate(self.float_flags_mask):
                robId = self.float_flags_robId[i].value
                self.robCtx[robId]['floatFlags'] |= float_flags_mask.value
        except:
            pass

        for i, valid in enumerate(self.integer_write_valid):
            if valid.value:
                robId = self.integer_write_robId[i].value
                self.robCtx[robId]['integerWriteValid'] = True
                self.robCtx[robId]['integerWriteData'] = self.integer_write_data[i].value

        try:
            for i, valid in enumerate(self.float_write_valid):
                if valid.value:
                    robId = self.float_write_robId[i].value
                    self.robCtx[robId]['floatWriteValid'] = True
                    self.robCtx[robId]['floatWriteData'] = self.float_write_data[i].value
        except:
            pass

        if self.dut.FetchPlugin_stages_1_isFirstCycle.value:
            fetchId = self.dut.FetchPlugin_stages_1_FETCH_ID.value
            self.fetchCtx[fetchId]['fetchAt'] = get_cycle_count(self.period) - self.period * 2

        if self.dut.fetchLastFire.value:
            fetchId = self.dut.fetchLastId.value
            self.fetchCtx[fetchId]['decodeAt'] = get_cycle_count(self.period)

        try:
            decoded_fire = self.dut.FrontendPlugin_decoded_isFireing
        except:
            decoded_fire = self.dut.FrontendPlugin_decoded_isFiring

        for i, decoded_fetch_id in enumerate(self.decoded_fetch_id):
            if decoded_fire.value:
                fetchId = decoded_fetch_id.value
                opId = self.dut.FrontendPlugin_decoded_OP_ID.value + i
                if self.decoded_mask[i].value:
                    self.opIdInFlight.append(opId)
                self.opCtx[opId] = {
                    'fetchId': fetchId,
                    'renameAt': get_cycle_count(self.period),
                    'instruction': self.decoded_instruction[i].value,
                    'pc': self.decoded_pc[i].value
                }

            try:
                allocated_fire = self.dut.FrontendPlugin_allocated_isFireing
            except:
                allocated_fire = self.dut.FrontendPlugin_allocated_isFiring

            if allocated_fire.value:
                robId = self.dut.FrontendPlugin_allocated_ROB_ID.value + i
                opId = self.dut.FrontendPlugin_allocated_OP_ID.value + i
                self.robCtx[robId]['opId'] = opId
                self.opCtx[opId]['robId'] = robId
        try:
            dispatch_fire = self.dut.FrontendPlugin_dispatch_isFireing
        except:
            dispatch_fire = self.dut.FrontendPlugin_dispatch_isFiring

        if dispatch_fire.value:
            for i, dispatch_mask in enumerate(self.dispatch_mask):
                if dispatch_mask.value:
                    robId = self.dut.FrontendPlugin_dispatch_ROB_ID.value + i
                    opId = self.robCtx[robId]['opId']
                    self.robCtx[robId]['PHYS_RS0'] = self.rob_phy_rs0[i][int(robId / self.COMMIT_COUNT)].value.integer
                    self.robCtx[robId]['PHYS_RS1'] = self.rob_phy_rs1[i][int(robId / self.COMMIT_COUNT)].value.integer
                    sqId = self.sq_alloc_id[i].value
                    self.opCtx[opId]['dispatchAt'] = get_cycle_count(self.period)
                    self.opCtx[opId]['sqAllocated'] = self.sq_alloc_valid[i].value
                    self.opCtx[opId]['sqId'] = sqId
                    if self.sq_alloc_valid[i]:
                        self.sqToOp[sqId] = opId

        for i, valid in enumerate(self.issue_valid):
            if valid.value:
                robId = self.issue_robId[i].value
                opId = self.robCtx[robId]['opId']
                self.opCtx[opId]['issueAt'] = get_cycle_count(self.period)
                phys_rs0 = self.robCtx[robId]['PHYS_RS0']
                phys_rs1 = self.robCtx[robId]['PHYS_RS1']
                self.opCtx[opId]['RS0_VAL_0'] = self.read_reg(self.rf0, phys_rs0, self.INTEGER_PHYSICAL_DEPTH)
                self.opCtx[opId]['RS1_VAL_0'] = self.read_reg(self.rf0, phys_rs1, self.INTEGER_PHYSICAL_DEPTH)
                if self.rf1:
                    tmp = self.read_reg(self.rf1, phys_rs0, self.ISR_INTEGER_PHYSICAL_DEPTH)
                    if tmp is not None:
                        self.opCtx[opId]['RS0_VAL_1'] = tmp
                    tmp = self.read_reg(self.rf1, phys_rs1, self.ISR_INTEGER_PHYSICAL_DEPTH)
                    if tmp is not None:
                        self.opCtx[opId]['RS1_VAL_1'] = tmp

        for i, valid in enumerate(self.rob_completions_valid):
            if valid.value:
                opId = self.robCtx[self.rob_completions_payload[i].value]['opId']
                self.opCtx[opId]['completeAt'] = get_cycle_count(self.period)

        for i in range(self.COMMIT_COUNT):
            if (self.dut.commit_mask.value >> i) & 1:
                robId = self.dut.commit_robId.value + i
                opId = self.robCtx[robId]['opId']
                self.opCtx[opId]['commitAt'] = get_cycle_count(self.period)
                while True:
                    front = self.opIdInFlight.pop(0)
                    self.opCtx[front]['counter'] = self.opCounter
                    self.opCounter += 1
                    self.trace(front)
                    if front == opId:
                        break

        self.stats.cycles += 1
        for i in range(self.COMMIT_COUNT):
            if (self.dut.commit_mask.value >> i) & 1:
                robId = self.dut.commit_robId.value + i
                pc = self.robCtx[robId]['pc'].integer
                self.stats.commits += 1
                if pc in self.stats.pcHist:
                    self.stats.pcHist[pc] += 1
                else:
                    self.stats.pcHist[pc] = 1

        if self.dut.reschedule_valid.value:
            self.stats.reschedules += 1
            reschedule_reason = self.dut.rescheduleReason.value
            if reschedule_reason == "trap":
                self.stats.reschedulesTrap += 1
            elif reschedule_reason == "branch":
                self.stats.reschedulesBranch += 1

    @cocotb.coroutine
    def run(self, clk):
        while True:
            yield FallingEdge(clk)
            self.preCycle()
