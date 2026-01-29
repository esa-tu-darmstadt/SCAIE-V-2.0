import cocotb
from cocotb.triggers import ReadOnly, Timer

class BusDelay():
    def __init__(self, SAMPLE_DELAY=0, ASSIGN_DELAY=0):
        self.SAMPLE_DELAY = SAMPLE_DELAY
        self.ASSIGN_DELAY = ASSIGN_DELAY
        if SAMPLE_DELAY < ASSIGN_DELAY:
            raise ValueError("SAMPLE_DELAY must be at or above ASSIGN_DELAY")

    async def sample_delay(self, assign_delay_applied:bool = False):
        actual_delay = self.SAMPLE_DELAY
        if assign_delay_applied:
            actual_delay -= self.ASSIGN_DELAY
        if actual_delay > 0:
            await Timer(actual_delay, units='ps')
        await ReadOnly()

    async def assign_delay(self):
        actual_delay = self.ASSIGN_DELAY
        if actual_delay <= 0:
            return
        await Timer(actual_delay, units='ps')
