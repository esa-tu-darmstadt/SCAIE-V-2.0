.section ".text.init"
.globl _start
_start:
    #ifdef ASM_PERCOREENTRY
    .include ASM_PERCOREENTRY
    #endif
    la s0, test_sqrt_data
    la s1, _test_resdata

    lw a0, 0(s0)
    lw a1, 4(s0)
    lw a2, 8(s0)

    # sqrt rd, rs1
    sqrt_decoupled t0, a0
    sqrt_decoupled t1, t0
    sqrt_decoupled t2, a1
    sqrt_decoupled t3, a2
    sqrt_decoupled t4, t2

    sw t0, 0(s1)
    sw t1, 4(s1)
    sw t2, 8(s1)
    sw t3, 12(s1)
    sw t4, 16(s1)

    #B504100A
    #C0000000
    #44444442
    #00010000
    #8432A514

# Completion interrupt.
# NOTE: For cores that can write memory out of order, a write fence may be required here.
    la s0, _ctrlmem
    li t0, 1
    sw t0, 0(s0)

    finloop:
    j finloop

.data
test_sqrt_data:
.word 0x7FFFFFFF
.word 0x12345678
.word 0x00000001
