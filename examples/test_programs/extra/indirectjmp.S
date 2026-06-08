.section ".text.init"
.globl _start
.option norvc
_start:
    #ifdef ASM_PERCOREENTRY
    .include ASM_PERCOREENTRY
    #endif
    la s0, test_indata
    la s1, _test_resdata

    la s2, indata_to_refaddrA
    la s3, indata_to_refaddrB
    la s4, indata_to_refaddrC

    li t0, 0
    li t1, 5*4
    add t1, t1, s3

    ijmp s2, 0
    ori t0, t0, 0x001

refaddrA:
    ijmp s2, 12
testerrA:
    ori t0, t0, 0x002
    j testend

loopnext:
    ijmp t1, 0

refaddrB:
#case t1==0 (iter 6)
    j loopend
    ori t0, t0, 0x004
#case t1==1*4 (iter 4)
    addi t1, t1, 3*4
    j loopnext
#case t1==2*4 (iter 3, reached with t1==indata_to_refaddrB+1*4)
    # Try provoking a mispredict (untaken branch with neg offset).
    beq t1, x0, testerrA
    ijmp t1, 0
#case t1==3*4 (iter 2, reached with t1==indata_to_refaddrB+1*4)
    # Untaken branch with pos offset.
    beq t1, x0, testerrB
    ijmp t1, 4
#case t1==4*4 (iter 5)
    addi t1, t1, -4*4
    j loopnext
#case t1==5*4 (iter 1)
    addi t1, t1, -4*4
    ijmp t1, 2*4

    ori t0, t0, 0x008

loopend:
refaddrC:
    ijmp s4, 1*4
    ijmp s4, 2*4
    ijmp s4, 3*4
    ijmp s4, 5*4
    ori t0, t0, 0x010
    j testend

testerrB:
    ori t0, t0, 0x020

testend:
    sw t0, 0(s1)
# Completion interrupt.
# NOTE: For cores that can write memory out of order, a write fence may be required here.
    la s0, _ctrlmem
    li t0, 1
    sw t0, 0(s0)

    finloop:
    j finloop

.data
test_indata:
indata_to_refaddrA:
.word refaddrA
.word 0xdeadbeef
.word 0xdeadbeef
.word refaddrA+12
.word 0xdeadbeef

indata_to_refaddrB:
.word refaddrB
.word refaddrB+8
.word refaddrB+(2*8)
.word refaddrB+(3*8)
.word refaddrB+(4*8)
.word refaddrB+(5*8)

indata_to_refaddrC:
.word refaddrC
.word refaddrC+(1*4)
.word refaddrC+(2*4)
.word refaddrC+(3*4)
.word refaddrC+(4*4)
.word refaddrC+(5*4)
