.section ".text.init"
.globl _start
_start:
    #ifdef ASM_PERCOREENTRY
    .include ASM_PERCOREENTRY
    #endif

	la a0, matA #MxK, row-major (Each cell in a row has subsequent memory addresses), stride=dimK
	la a1, matB #KxN, column-major (Each cell in a column has subsequent memory addresses), stride=dimK
	la a2, _test_resdata #matC, MxN, column-major, should be zeroed already
	li a3, 102 #biasA
	li a4, -15 #biasB
	li s0, 16 #dimM size (must be divisible by 2)
	#dimK tile size: Hardcoded 16
	li s1, 16 #dimN size
	li s2, 64 #matC stride (to use if the input matrix is tiled)

	setup_dotpbias a3, a4

	la t0, mulloop_mdim_start
	la t1, mulloop_mdim_end
	setup_inner_zol2d t0, t1
	addi t1, s1, -1
	slli t0, t1, 8 #outer loop: dimN-1 jumps back
	srli t1, s0, 1 #dimM/2
	addi t1, t1, -1
	or t0, t0, t1  #inner loop: dimM/2-1 jumps back
	mv t1, a1 #matB cur pointer
	mv t2, a2 #matC cur pointer
	lw t3, 0(t1)
	setup_zol2d t0, mulloop_ndim_end
	.p2align 2 #32bit align loop start
mulloop_ndim_start:
	lw t4, 4(t1)
	lw t5, 8(t1)
	lw t6, 12(t1)
	setup_dotpaddr a0
mulloop_mdim_start:
	lwdotpbias s3, 0(t3)
	lwdotpbias s4, 4(t4)
	lwdotpbias s5, 8(t5)
	lwdotpbias s6, 12(t6)
	lwdotpbias s7, 16(t3)
	lwdotpbias s8, 20(t4)
	lwdotpbias s9, 24(t5)
	lwdotpbias s10, 28(t6)
	addi t0, t0, 32 #matA pointer += 2*dimK * sizeof(int8_t)
	incr_dotpaddr 32
	lw s11, 0(t2)
	add s3, s3, s4
	add s5, s5, s6
	add s3, s3, s5
	add s3, s3, s11
	sw s3, 0(t2)
	lw s11, 4(t2)
	add s7, s7, s8
	add s9, s9, s10
	add s7, s7, s9
	add s7, s7, s11
	sw s7, 4(t2)
	addi t2, t2, 8 #matC pointer += 2*sizeof(int32_t)
	.p2align 3 #64bit align loop end
mulloop_mdim_end:
	addi t1, t1, 16 #matB pointer += dimK * sizeof(int8_t)
	lw t3, 0(t1)
	mv t0, a0 #matA cur pointer
	.p2align 3 #64bit align loop end
mulloop_ndim_end:


# Completion interrupt.
# NOTE: For cores that can write memory out of order, a write fence may be required here.
    nop
    nop
    nop
    nop
    nop
    nop
    FENCE w,ow
	la s0, _ctrlmem
	li t0, 1
    sw t0, 0(s0)
finloop:
	j finloop

.data
test_indata:
#include "testmatrix_16x16_each.s"
