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
	li s0, 16 #dimM size
	#dimK tile size: Hardcoded 32
	li s1, 16 #dimN size
	li s2, 64 #matC stride (to use if the input matrix is tiled)

	li t0, 16 #dimK * sizeof(int8_t)
	mul s0, s0, t0 #dimM * dimK * sizeof(int8_t)
	add s0, s0, a0 #s0 = matA + sizeof(matA)
	mul s1, s1, t0 #dimN * dimK * sizeof(int8_t)
	add s1, s1, a1 #s1 = matB + sizeof(matB)

	mv t2, a2 #matC cur pointer
	mv t1, a1 #matB cur pointer
mulloop_ndim_start:
	mv t0, a0 #matA cur pointer
mulloop_mdim_start:
	lw s11, 0(t2)
	addi t3, t0, 16 #end: matA pointer + dimK * sizeof(int8_t)
mulloop_kdim_start:
	lb s3, 0(t0)
	sub s3, s3, a3
	lb s4, 0(t1)
	sub s4, s4, a4
	mul s5, s3, s4

	lb s3, 1(t0)
	sub s3, s3, a3
	lb s4, 1(t1)
	sub s4, s4, a4
	mul s6, s3, s4

	lb s3, 2(t0)
	sub s3, s3, a3
	lb s4, 2(t1)
	sub s4, s4, a4
	mul s7, s3, s4

	lb s3, 3(t0)
	sub s3, s3, a3
	lb s4, 3(t1)
	sub s4, s4, a4
	mul s8, s3, s4
	add s5, s5, s6
	add s7, s7, s8
	add s5, s5, s7
	addi t0, t0, 4 #matA pointer += 4
	addi t1, t1, 4 #matB pointer += 4
	add s11, s11, s5
	bne t0, t3, mulloop_kdim_start
mulloop_kdim_end:
	sw s11, 0(t2)
	addi t2, t2, 4 #matC pointer += sizeof(int32_t)
	addi t1, t1, -16 #matB pointer -= dimK * sizeof(int8_t)
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
.include "testmatrix_16x16_each.s"
