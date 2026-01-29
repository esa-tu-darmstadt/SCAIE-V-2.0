.section ".text.init"
.globl _start
_start:
    #ifdef ASM_PERCOREENTRY
    .include ASM_PERCOREENTRY
    #endif

	la a0, matA #MxK, row-major (Each cell in a row has subsequent memory addresses), stride=dimK
	la a1, matB #KxN, column-major (Each cell in a column has subsequent memory addresses), stride=dimK
	la a2, _test_resdata #matC, MxN, column-major, should be zeroed already
	li s0, 16 #dimM size (must be divisible by 4)
	#dimK tile size: Hardcoded 16
	li s1, 16 #dimN size

	li t0, 16 #dimK * sizeof(int8_t)
	mul s0, s0, t0 #dimM * dimK * sizeof(int8_t)
	add s0, s0, a0 #s0 = matA + sizeof(matA)
	mul s1, s1, t0 #dimN * dimK * sizeof(int8_t)
	add s1, s1, a1 #s1 = matB + sizeof(matB)

	mv t1, a1 #matB cur pointer
	mv t2, a2 #matC cur pointer
mulloop_ndim_start:
	lw t3, 0(t1)
	lw t4, 4(t1)
	lw t5, 8(t1)
	lw t6, 12(t1)
	mv t0, a0 #matA cur pointer
mulloop_mdim_start:
	lw s3, 0(t0)
	lw s4, 4(t0)
	lw s5, 8(t0)
	lw s6, 12(t0)
	lw s7, 16(t0)
	addi t0, t0, 32 #matA pointer += 2*dimK * sizeof(int8_t)
	lw s8, -12(t0)
	lw s9, -8(t0)
	lw s10, -4(t0)
	lw s11, 0(t2)
	dotp s3, s3, t3
	dotp s4, s4, t4
	dotp s5, s5, t5
	dotp s6, s6, t6
	add s3, s3, s4
	add s5, s5, s6
	add s3, s3, s5
	add s3, s3, s11
	sw s3, 0(t2)
	lw s11, 4(t2)
	dotp s7, s7, t3
	dotp s8, s8, t4
	dotp s9, s9, t5
	dotp s10, s10, t6
	add s7, s7, s8
	add s9, s9, s10
	add s7, s7, s9
	add s7, s7, s11
	sw s7, 4(t2)
	addi t2, t2, 8 #matC pointer += 2*sizeof(int32_t)
	bne t0, s0, mulloop_mdim_start
mulloop_mdim_end:
	addi t1, t1, 16 #matB pointer += dimK * sizeof(int8_t)
	bne t1, s1, mulloop_ndim_start
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
