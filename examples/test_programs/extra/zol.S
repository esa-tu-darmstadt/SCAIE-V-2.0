.section ".text.init"
.globl _start
.option norvc
_start:
    #ifdef ASM_PERCOREENTRY
    .include ASM_PERCOREENTRY
    #endif
    la s1, _test_resdata

    li t0, 0

# should be 7 iterations (due to post-decrement in the ISAX)
# setup_zol backjump_count[11:0], pc_offs[5:1]

#zol0: 7x 2xnop (throughput measurement)
.align 3
    nop
    setup_zol 6,6
    add zero, zero, zero
    add zero, zero, zero
endl_zol0:

#zol1: t0 += 27
.align 3
    nop
    setup_zol 8,10
    addi t0, t0, 1
    addi t0, t0, 1
    addi t0, t0, 1
    nop
endl_zol1:

#zol2: t0 -= 27
.align 3
    nop
    setup_zol 26,6
    addi t0, t0, 1
    addi t0, t0, -2
endl_zol2:
.align 3
    nop
#zol3: t0 += 21
    setup_zol 6,14
#zol3 iteration: t0 += 3
    li t1, 3
    reloop:
    beqz t1, endl_zol3
    addi t1, t1, -1
    addi t0, t0, 1
    nop
    j reloop
endl_zol3:

#zol4: t0 += 28
.align 3
    nop
    setup_zol 6,6
    addi t0, t0, 4
    nop
endl_zol4:

    addi t0, t0, -28
    # should be 0x15
    sw t0, 0(s1)

# Completion interrupt.
# NOTE: For cores that can write memory out of order and don't differentiate the I/O, a write fence may be required here.
    la s0, _ctrlmem
    li t0, 1
    sw t0, 0(s0)

    finloop:
    j finloop

