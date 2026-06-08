.section ".text.init"
.globl _start
_start:
    #ifdef ASM_PERCOREENTRY
    .include ASM_PERCOREENTRY
    #endif
    la s1, _test_resdata

    jal ra, test_ell
    add s1, s1, a0

    jal ra, test_rcon
    add s1, s1, a0

    jal ra, test_whole_enci
    add s1, s1, a0

# Completion interrupt.
# NOTE: For cores that can write memory out of order, a write fence may be required here.
    la s0, _ctrlmem
    li t0, 1
    sw t0, 0(s0)

    finloop:
    j finloop


.section ".text"
test_ell:
    # sparkle_ell rd, rs1, rs2
    la a0, test_ell_data

    lw t3, 0(a0)
    lw t4, 4(a0)

    #= ELL(0x10101010^0x19181716) = ELL(0x09080706) = ROTR(0x0E0E0706, 16) = 0x07060E0E
    sparkle_ell t0, t3, t4
    #= ELL(0x10101010^0x07060E0E) = ELL(0x17161E1E) = ROTR(0x09081E1E, 16) = 0x1E1E0908
    sparkle_ell t1, t3, t0

    sw t0, 0(s1)
    sw t1, 4(s1)
    #resdata[0..8] = [0x07060E0E, 0x1E1E0908]

    #8 bytes written to resdata
    li a0, 8
    jr ra

test_rcon:
    # sparkle_rcon rd, rs1, rs2, imm
    #  rd = rs1^rcon[imm]
    # rs2 present but unused
    la a0, test_rcon_data

    lw t3, 0(a0)
    lw t4, 4(a0)
    lw t5, 8(a0)

    #= 0xB7E15162 ^ 0xB7E15162 = 0x00000000
    sparkle_rcon t0, t4, t3, 0
    #= 0x10F0F010 ^ 0xBF715880 = 0xAF81A890
    sparkle_rcon t1, t5, t3, 1
    #= 0xAF81A890 ^ 0xC2B3293D = 0x6D3281AD
    sparkle_rcon t2, t1, t3, 7

    sw t0, 0(s1)
    sw t1, 4(s1)
    sw t2, 8(s1)
    #resdata[0..12] = [0x00000000, 0xAF81A890, 0x6D3281AD]

    #12 bytes written to resdata
    li a0, 12
    jr ra

test_whole_enci:
    # sparkle_whole_enci_x rd, rs1, rs2, imm
    # sparkle_whole_enci_y rd, rs1, rs2, imm
    la a0, test_whole_enci_data

    lw t3, 0(a0)
    lw t4, 4(a0)
    lw t5, 8(a0)

    sparkle_whole_enci_x t0, t3, t4, 2
    sparkle_whole_enci_y t1, t0, t5, 5
    sparkle_rcon t2, t0, zero, 4

    sw t0, 0(s1)
    sw t1, 4(s1)
    sw t2, 8(s1)
    #resdata[0..12] = [0x5D038CB0, 0x8079A7E5, 0xE612095B]

    #12 bytes written to resdata
    li a0, 12
    jr ra


.data
test_ell_data:
.word 0x10101010
.word 0x19181716
test_rcon_data:
.word 0xDEADBEEF
.word 0xB7E15162
.word 0x10F0F010
test_whole_enci_data:
.word 0xF0890CD7
.word 0xCDCDCDCD
.word 0x10F0F010
