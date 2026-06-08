.section ".text.init"
.globl _start
_start:
    #ifdef ASM_PERCOREENTRY
    .include ASM_PERCOREENTRY
    #endif
    la s0, test_data
    la s2, _test_resdata

    li t0, 0

    # In blocks of 4, apply sbox on its previous result (start with 0), until the third result equals 0.
    # (works with this particular SBOX, but not in general)
sbox_loop:
    sbox t1, t0
    sbox t2, t1
    sbox t3, t2
    sw t1, 0(s2)
    sw t2, 4(s2)
    sw t3, 8(s2)
    sbox t0, t3
    sw t0, 12(s2)
    addi s2, s2, 16
    bnez t3, sbox_loop

# Completion interrupt.
# NOTE: For cores that can write memory out of order, a write fence may be required here.
    la s0, _ctrlmem
    li t0, 1
    sw t0, 0(s0)

finloop:
    j finloop

.data
test_data:
.word 0
