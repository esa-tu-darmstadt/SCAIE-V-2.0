.section ".text.init"
.globl _start
_start:
    #ifdef ASM_PERCOREENTRY
    .include ASM_PERCOREENTRY
    #endif
    la s0, test_indata
    la s1, _test_resdata

    # t0 := 0xdeadbeef
    lw t0, 0(s0)
    # Write deadbeef to 0 and 4
    sw t0, 0(s1)
    sw t0, 4(s1)

    li t2, 5
    li t1, 4
    sw t0, 0(s1)

    # Should jump.
    brimm_cv_beqimm t2, 5, branch1
    j line1
    branch1:
    sw t2, 0(s1)

    line1:
    # Should not jump.
    brimm_cv_beqimm t2, 0, branch2
    sw t1, 4(s1)
    j line2
    branch2:
    line2:

    # t0 := 0xdeadbeef
    lw t0, 0(s0)
    li t1, 1

    # Should jump.
    bnez t0, succ3
    # Should not have an effect.
    brimm_cv_beqimm t0, 4, err3
    err3:
    # Error -> write deadbeef
    sw t0, 8(s1)
    j line3
    succ3:
    sw t1, 8(s1)

    line3:
    beqz t2, err4
    brimm_cv_beqimm t2, 5, succ4
    err4:
    # Error -> write deadbeef
    sw t0, 12(s1)
    j line4
    succ4:
    sw t1, 12(s1)
    line4:

# Completion interrupt.
# NOTE: For cores that can write memory out of order, a write fence may be required here.
    la s0, _ctrlmem
    li t0, 1
    sw t0, 0(s0)

    finloop:
    j finloop

.data
test_indata:
#0
.word 0xdeadbeef
