.section ".text.init"
.globl _start
_start:
    #ifdef ASM_PERCOREENTRY
    .include ASM_PERCOREENTRY
    #endif
    la s0, test_indata
    la s1, _test_resdata

    li a0, 4
    li a1, -4
    li a2, 8
    li a3, -8

    #Load input data.
    # - Repeat autoinc_setup to help detect data races in setting ADDR.
    # - Add an lw and sw in between to help detect implementation corner cases, as well as memory RaW hazards.

    autoinc_setup s0, a0
    #t0=t1=[indata+0]
    autoinc_lw_inc t0
    autoinc_setup s0, a0
    autoinc_lw_inc t1
    #t2=[indata+4]
    lw t2, 4(s0)
    #t3=[indata+8]
    autoinc_lw_inc t3
    autoinc_lw_inc t3
    #t4=[indata+12]=[indata+4]
    sw t2, 12(s0)
    autoinc_lw_inc t4
    #t0..t4 = [0x01100220, 0x01100220, 0x02200330, 0x87654321, 0x02200330]

    addi s3, s1, 4

    # Store first output dataset

    autoinc_setup s1, a0
    #[resdata+0] = t0
    autoinc_sw_inc t0
    autoinc_sw_inc t2
    #[resdata+8] = t2
    sw t3, 8(s1)
    autoinc_sw_inc t2
    #[resdata+4] = t1
    autoinc_setup s3, a2
    autoinc_sw_inc t1
    #[resdata+12] = t3
    autoinc_sw_inc t3
    #[resdata+20] = t3
    autoinc_sw_inc t3
    #[resdata+16] = t4
    sw t4, 16(s1)
    #resdata[0..24] = [0x01100220, 0x01100220, 0x02200330, 0x87654321, 0x02200330, 0x87654321]

# Completion interrupt.
# NOTE: For cores that can write memory out of order, a write fence may be required here.
    la s0, _ctrlmem
    autoinc_setup s0, zero
    li t0, 1
    autoinc_sw_inc t0

    finloop:
    j finloop

.data
test_indata:
#0
.word 0x01100220
#4
.word 0x02200330
#8
.word 0x87654321
#12
.word 0xdeadbeef
#16
.word 0xdeadbeef
#20
.word 0xdeadbeef
#24
.word 0xdeadbeef
#28
.word 0xdeadbeef

