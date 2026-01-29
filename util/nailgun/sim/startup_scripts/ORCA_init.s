# Set exception handler address to 0x80000000 (BASE=0x80000000, MODE=0 i.e. Direct "All exceptions set pc to BASE")
la t0, 0x80000000
CSRRW zero, mtvec, t0
