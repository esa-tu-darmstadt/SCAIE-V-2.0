# Set exception handler address to 0x00000000 (BASE=0, MODE=0 i.e. Direct "All exceptions set pc to BASE")
CSRRW zero, mtvec, zero
