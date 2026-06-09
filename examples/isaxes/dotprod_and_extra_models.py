
try:
  from Antmicro.Renode.Peripherals.CPU import RegisterValue
except:
  pass

def read_mem(addr):
  data = machine.SystemBus.ReadByte(addr)
  machine.InfoLog("ISAX: MEM Read from 0x{:08X} = 0x{:02X}".format(addr, int(data)))
  return data
def write_mem(addr, data):
  machine.SystemBus.WriteByte(addr, data)
  machine.InfoLog("ISAX: MEM Write to 0x{:08X} = 0x{:02X}".format(addr, data))

def read_pc():
  return cpu.PC.RawValue
def write_pc(new_pc):
  cpu.PC = RegisterValue.Create(new_pc, 32) #TODO do not hardcode 32 bit

def read_reg(addr):
  return cpu.GetRegister(addr).RawValue
def write_reg(addr, data):
  cpu.SetRegister(addr, RegisterValue.Create(data, 32)) #TODO do not hardcode 32 bit

def init_cust_regs():
  state["cust_regs"] = dict()
  state["cust_regs"]["ADDR"] = [0] * 1
  state["cust_regs"]["INCR"] = [0] * 1
ROM_MERGED4SBOX = [int(99), int(124), int(119), int(123), int(242), int(107), int(111), int(197), int(48), int(1), int(103), int(43), int(254), int(215), int(171), int(118), int(202), int(130), int(201), int(125), int(250), int(89), int(71), int(240), int(173), int(212), int(162), int(175), int(156), int(164), int(114), int(192), int(183), int(253), int(147), int(38), int(54), int(63), int(247), int(204), int(52), int(165), int(229), int(241), int(113), int(216), int(49), int(21), int(4), int(199), int(35), int(195), int(24), int(150), int(5), int(154), int(7), int(18), int(128), int(226), int(235), int(39), int(178), int(117), int(9), int(131), int(44), int(26), int(27), int(110), int(90), int(160), int(82), int(59), int(214), int(179), int(41), int(227), int(47), int(132), int(83), int(209), int(0), int(237), int(32), int(252), int(177), int(91), int(106), int(203), int(190), int(57), int(74), int(76), int(88), int(207), int(208), int(239), int(170), int(251), int(67), int(77), int(51), int(133), int(69), int(249), int(2), int(127), int(80), int(60), int(159), int(168), int(81), int(163), int(64), int(143), int(146), int(157), int(56), int(245), int(188), int(182), int(218), int(33), int(16), int(255), int(243), int(210), int(205), int(12), int(19), int(236), int(95), int(151), int(68), int(23), int(196), int(167), int(126), int(61), int(100), int(93), int(25), int(115), int(96), int(129), int(79), int(220), int(34), int(42), int(144), int(136), int(70), int(238), int(184), int(20), int(222), int(94), int(11), int(219), int(224), int(50), int(58), int(10), int(73), int(6), int(36), int(92), int(194), int(211), int(172), int(98), int(145), int(149), int(228), int(121), int(231), int(200), int(55), int(109), int(141), int(213), int(78), int(169), int(108), int(86), int(244), int(234), int(101), int(122), int(174), int(8), int(186), int(120), int(37), int(46), int(28), int(166), int(180), int(198), int(232), int(221), int(116), int(31), int(75), int(189), int(139), int(138), int(112), int(62), int(181), int(102), int(72), int(3), int(246), int(14), int(97), int(53), int(87), int(185), int(134), int(193), int(29), int(158), int(225), int(248), int(152), int(17), int(105), int(217), int(142), int(148), int(155), int(30), int(135), int(233), int(206), int(85), int(40), int(223), int(140), int(161), int(137), int(13), int(191), int(230), int(66), int(104), int(65), int(153), int(45), int(15), int(176), int(84), int(187), int(22), ]
ROM_MERGED5ROT_0 = [int(31), int(17), int(0), int(24), ]
ROM_MERGED5ROT_1 = [int(24), int(17), int(31), int(16), ]
ROM_MERGED5RCON = [int(3084996962), int(3211876480), int(951376470), int(844003128), int(3138487787), int(1333558103), int(3485442504), int(3266521405), ]

def read_cust_reg(name, addr):
  return state["cust_regs"][name][addr]
def write_cust_reg(name, addr, data):
  state["cust_regs"][name][addr] = int(data)

# Init ISAX state
if 'state' in locals() or 'state' in globals():
  if "cust_regs" not in state:
    init_cust_regs()

import os
import sys
sys.path.append(os.path.dirname(os.path.abspath(__file__)))
from ArbInt import *

def setup(opcode, read_reg=read_reg, write_reg=write_reg, read_cust_reg=read_cust_reg, write_cust_reg=write_cust_reg, read_pc=read_pc, write_pc=write_pc, read_mem=read_mem, write_mem=write_mem):
  bitvector_opcode = ArbInt.from_int(opcode, 32, False)
  VAR_TREENAIL_WAS_HERE_rs2_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 24, 20)
  VAR_TREENAIL_WAS_HERE_rs1_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 19, 15)
  VAR_TREENAIL_WAS_HERE_rd_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 11, 7)
  VAR_0 = VAR_TREENAIL_WAS_HERE_rs2_4_0.cast(5, False)
  VAR_1 = VAR_TREENAIL_WAS_HERE_rs1_4_0.cast(5, False)
  VAR_2 = ArbInt.from_int(read_reg(VAR_1.as_int()), 32, False)
  TMP_0 = VAR_2.bitextract(ArbInt.from_int(0, 1, False), 31, 0)
  write_cust_reg("ADDR", 0, TMP_0.as_int())
  VAR_3 = ArbInt.from_int(read_reg(VAR_0.as_int()), 32, False)
  TMP_1 = VAR_3.bitextract(ArbInt.from_int(0, 1, False), 31, 0)
  write_cust_reg("INCR", 0, TMP_1.as_int())
def lw_inc(opcode, read_reg=read_reg, write_reg=write_reg, read_cust_reg=read_cust_reg, write_cust_reg=write_cust_reg, read_pc=read_pc, write_pc=write_pc, read_mem=read_mem, write_mem=write_mem):
  bitvector_opcode = ArbInt.from_int(opcode, 32, False)
  VAR_TREENAIL_WAS_HERE_rd_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 11, 7)
  VAR_0 = VAR_TREENAIL_WAS_HERE_rd_4_0.cast(5, False)
  TMP_2 = ArbInt.from_int(read_cust_reg("ADDR", 0), 32, False)
  VAR_1 = TMP_2
  TMP_3 = ArbInt.from_int(read_mem(VAR_1.as_int() + 0), 8, False)
  TMP_4 = ArbInt.from_int(read_mem(VAR_1.as_int() + 1), 8, False)
  TMP_5 = ArbInt.from_int(read_mem(VAR_1.as_int() + 2), 8, False)
  TMP_6 = ArbInt.from_int(read_mem(VAR_1.as_int() + 3), 8, False)
  TMP_7 = TMP_6.concat(TMP_5)
  TMP_8 = TMP_7.concat(TMP_4)
  TMP_9 = TMP_8.concat(TMP_3)
  VAR_2 = TMP_9
  TMP_10 = ArbInt.from_int(read_cust_reg("INCR", 0), 32, False)
  VAR_3 = TMP_10
  VAR_4 = VAR_1.add(VAR_3)
  VAR_5 = VAR_4.cast(32, False)
  TMP_11 = VAR_5.bitextract(ArbInt.from_int(0, 1, False), 31, 0)
  write_cust_reg("ADDR", 0, TMP_11.as_int())
  write_reg(VAR_0.as_int(), VAR_2.as_int())
def sw_inc(opcode, read_reg=read_reg, write_reg=write_reg, read_cust_reg=read_cust_reg, write_cust_reg=write_cust_reg, read_pc=read_pc, write_pc=write_pc, read_mem=read_mem, write_mem=write_mem):
  bitvector_opcode = ArbInt.from_int(opcode, 32, False)
  VAR_TREENAIL_WAS_HERE_rs1_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 19, 15)
  VAR_TREENAIL_WAS_HERE_rd_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 11, 7)
  VAR_0 = VAR_TREENAIL_WAS_HERE_rs1_4_0.cast(5, False)
  TMP_12 = ArbInt.from_int(read_cust_reg("ADDR", 0), 32, False)
  VAR_1 = TMP_12
  VAR_2 = ArbInt.from_int(read_reg(VAR_0.as_int()), 32, False)
  TMP_13 = VAR_2.bitextract(ArbInt.from_int(0, 1, False), 7, 0)
  write_mem(VAR_1.as_int() + 0, TMP_13.as_int())
  TMP_14 = VAR_2.bitextract(ArbInt.from_int(0, 1, False), 15, 8)
  write_mem(VAR_1.as_int() + 1, TMP_14.as_int())
  TMP_15 = VAR_2.bitextract(ArbInt.from_int(0, 1, False), 23, 16)
  write_mem(VAR_1.as_int() + 2, TMP_15.as_int())
  TMP_16 = VAR_2.bitextract(ArbInt.from_int(0, 1, False), 31, 24)
  write_mem(VAR_1.as_int() + 3, TMP_16.as_int())
  TMP_17 = ArbInt.from_int(read_cust_reg("INCR", 0), 32, False)
  VAR_3 = TMP_17
  VAR_4 = VAR_1.add(VAR_3)
  VAR_5 = VAR_4.cast(32, False)
  TMP_18 = VAR_5.bitextract(ArbInt.from_int(0, 1, False), 31, 0)
  write_cust_reg("ADDR", 0, TMP_18.as_int())
def cv_beqimm(opcode, read_reg=read_reg, write_reg=write_reg, read_cust_reg=read_cust_reg, write_cust_reg=write_cust_reg, read_pc=read_pc, write_pc=write_pc, read_mem=read_mem, write_mem=write_mem):
  bitvector_opcode = ArbInt.from_int(opcode, 32, False)
  VAR_TREENAIL_WAS_HERE_imm12_11_11 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 31, 31)
  VAR_TREENAIL_WAS_HERE_imm12_9_4 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 30, 25)
  VAR_TREENAIL_WAS_HERE_imm5_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 24, 20)
  VAR_TREENAIL_WAS_HERE_rs1_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 19, 15)
  VAR_TREENAIL_WAS_HERE_imm12_3_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 11, 8)
  VAR_TREENAIL_WAS_HERE_imm12_10_10 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 7, 7)
  VAR_0 = ArbInt.from_int(0, 1, False)
  VAR_1 = VAR_TREENAIL_WAS_HERE_imm12_9_4.concat(VAR_TREENAIL_WAS_HERE_imm12_3_0)
  VAR_2 = VAR_TREENAIL_WAS_HERE_imm12_10_10.concat(VAR_1)
  VAR_3 = VAR_TREENAIL_WAS_HERE_imm12_11_11.concat(VAR_2)
  VAR_4 = VAR_3.cast(12, False)
  VAR_5 = VAR_TREENAIL_WAS_HERE_imm5_4_0.cast(5, False)
  VAR_6 = VAR_TREENAIL_WAS_HERE_rs1_4_0.cast(5, False)
  VAR_7 = VAR_4.concat(VAR_0)
  VAR_8 = VAR_7.cast(13, True)
  VAR_9 = ArbInt.from_int(read_reg(VAR_6.as_int()), 32, False)
  VAR_10 = VAR_5.cast(5, True)
  VAR_11 = VAR_9.eq(VAR_10)
  VAR_12 = VAR_11.cast(1, False)
  VAR_13 = VAR_12.cast(1, False)
  def helper_function_if_0_then():
    VAR_14 = ArbInt.from_int(read_pc(), 32, False)
    VAR_15 = VAR_14.add(VAR_8)
    VAR_16 = VAR_15.cast(32, False)
    write_pc(VAR_16.as_int())
    return 
  if VAR_13.is_true():
    helper_function_if_0_then()
def DOTP(opcode, read_reg=read_reg, write_reg=write_reg, read_cust_reg=read_cust_reg, write_cust_reg=write_cust_reg, read_pc=read_pc, write_pc=write_pc, read_mem=read_mem, write_mem=write_mem):
  bitvector_opcode = ArbInt.from_int(opcode, 32, False)
  VAR_TREENAIL_WAS_HERE_rs2_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 24, 20)
  VAR_TREENAIL_WAS_HERE_rs1_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 19, 15)
  VAR_TREENAIL_WAS_HERE_rd_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 11, 7)
  VAR_c8_i7 = ArbInt.from_int(8, 7, False)
  VAR_c32_i7 = ArbInt.from_int(32, 7, False)
  VAR_c0_i7 = ArbInt.from_int(0, 7, False)
  VAR_0 = ArbInt.from_int(0, 1, False)
  VAR_1 = VAR_TREENAIL_WAS_HERE_rs2_4_0.cast(5, False)
  VAR_2 = VAR_TREENAIL_WAS_HERE_rs1_4_0.cast(5, False)
  VAR_3 = VAR_TREENAIL_WAS_HERE_rd_4_0.cast(5, False)
  VAR_4 = VAR_0.cast(32, True)
  def helper_function_for_0():
    def helper_function_for_0_body(i, iter_args):
      VAR_arg0 = ArbInt.from_int(i, 32, False) #TODO do not hardcode type... what about negative values?
      VAR_arg1 = iter_args
      VAR_7 = VAR_arg0.cast(7, True)
      VAR_8 = VAR_7.cast(32, True)
      VAR_9 = VAR_8.cast(5, False)
      VAR_10 = ArbInt.from_int(read_reg(VAR_2.as_int()), 32, False)
      VAR_11 = VAR_10.bitextract(VAR_9, 7, 0)
      VAR_12 = VAR_11.cast(8, True)
      VAR_13 = VAR_8.cast(5, False)
      VAR_14 = ArbInt.from_int(read_reg(VAR_1.as_int()), 32, False)
      VAR_15 = VAR_14.bitextract(VAR_13, 7, 0)
      VAR_16 = VAR_15.cast(8, True)
      VAR_17 = VAR_12.mul(VAR_16)
      VAR_18 = VAR_arg1.add(VAR_17)
      VAR_19 = VAR_18.cast(32, True)
      return VAR_19
    iter_args = (VAR_4)
    for i in range(VAR_c0_i7.as_int(), VAR_c32_i7.as_int(), VAR_c8_i7.as_int()):
      iter_args = helper_function_for_0_body(i, iter_args)
    return iter_args
  VAR_5 = helper_function_for_0()
  VAR_6 = VAR_5.cast(32, False)
  write_reg(VAR_3.as_int(), VAR_6.as_int())
def ijmp(opcode, read_reg=read_reg, write_reg=write_reg, read_cust_reg=read_cust_reg, write_cust_reg=write_cust_reg, read_pc=read_pc, write_pc=write_pc, read_mem=read_mem, write_mem=write_mem):
  bitvector_opcode = ArbInt.from_int(opcode, 32, False)
  VAR_TREENAIL_WAS_HERE_offset_11_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 31, 20)
  VAR_TREENAIL_WAS_HERE_rs1_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 19, 15)
  VAR_TREENAIL_WAS_HERE_rd_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 11, 7)
  VAR_0 = VAR_TREENAIL_WAS_HERE_offset_11_0.cast(12, False)
  VAR_1 = VAR_TREENAIL_WAS_HERE_rs1_4_0.cast(5, False)
  VAR_2 = ArbInt.from_int(read_reg(VAR_1.as_int()), 32, False)
  VAR_3 = VAR_2.add(VAR_0)
  VAR_4 = VAR_3.cast(32, False)
  TMP_19 = ArbInt.from_int(read_mem(VAR_4.as_int() + 0), 8, False)
  TMP_20 = ArbInt.from_int(read_mem(VAR_4.as_int() + 1), 8, False)
  TMP_21 = ArbInt.from_int(read_mem(VAR_4.as_int() + 2), 8, False)
  TMP_22 = ArbInt.from_int(read_mem(VAR_4.as_int() + 3), 8, False)
  TMP_23 = TMP_22.concat(TMP_21)
  TMP_24 = TMP_23.concat(TMP_20)
  TMP_25 = TMP_24.concat(TMP_19)
  VAR_5 = TMP_25
  write_pc(VAR_5.as_int())
def sbox(opcode, read_reg=read_reg, write_reg=write_reg, read_cust_reg=read_cust_reg, write_cust_reg=write_cust_reg, read_pc=read_pc, write_pc=write_pc, read_mem=read_mem, write_mem=write_mem):
  bitvector_opcode = ArbInt.from_int(opcode, 32, False)
  VAR_TREENAIL_WAS_HERE_rs1_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 19, 15)
  VAR_TREENAIL_WAS_HERE_rd_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 11, 7)
  VAR_0 = VAR_TREENAIL_WAS_HERE_rs1_4_0.cast(5, False)
  VAR_1 = VAR_TREENAIL_WAS_HERE_rd_4_0.cast(5, False)
  VAR_2 = ArbInt.from_int(read_reg(VAR_0.as_int()), 32, False)
  VAR_3 = VAR_2.cast(8, False)
  TMP_26 = ArbInt.from_int(ROM_MERGED4SBOX[VAR_3.as_int() + 0], 8, False)
  VAR_4 = TMP_26
  VAR_5 = VAR_4.cast(32, False)
  write_reg(VAR_1.as_int(), VAR_5.as_int())
def sparkle_ell(opcode, read_reg=read_reg, write_reg=write_reg, read_cust_reg=read_cust_reg, write_cust_reg=write_cust_reg, read_pc=read_pc, write_pc=write_pc, read_mem=read_mem, write_mem=write_mem):
  bitvector_opcode = ArbInt.from_int(opcode, 32, False)
  VAR_TREENAIL_WAS_HERE_rs2_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 24, 20)
  VAR_TREENAIL_WAS_HERE_rs1_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 19, 15)
  VAR_TREENAIL_WAS_HERE_rd_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 11, 7)
  VAR_0 = ArbInt.from_int(16, 5, False)
  VAR_1 = ArbInt.from_int(32, 6, False)
  VAR_2 = VAR_TREENAIL_WAS_HERE_rs2_4_0.cast(5, False)
  VAR_3 = VAR_TREENAIL_WAS_HERE_rs1_4_0.cast(5, False)
  VAR_4 = VAR_TREENAIL_WAS_HERE_rd_4_0.cast(5, False)
  VAR_5 = ArbInt.from_int(read_reg(VAR_3.as_int()), 32, False)
  VAR_6 = ArbInt.from_int(read_reg(VAR_2.as_int()), 32, False)
  VAR_7 = VAR_5.xor(VAR_6)
  VAR_8 = VAR_7.shift_left(VAR_0)
  VAR_9 = VAR_7.xor(VAR_8)
  VAR_10 = VAR_0.cast(8, False)
  VAR_11 = VAR_9.shift_right(VAR_10)
  VAR_12 = VAR_1.sub(VAR_10)
  VAR_13 = VAR_9.shift_left(VAR_12)
  VAR_14 = VAR_11.or_(VAR_13)
  write_reg(VAR_4.as_int(), VAR_14.as_int())
def sparkle_rcon(opcode, read_reg=read_reg, write_reg=write_reg, read_cust_reg=read_cust_reg, write_cust_reg=write_cust_reg, read_pc=read_pc, write_pc=write_pc, read_mem=read_mem, write_mem=write_mem):
  bitvector_opcode = ArbInt.from_int(opcode, 32, False)
  VAR_TREENAIL_WAS_HERE_imm_2_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 27, 25)
  VAR_TREENAIL_WAS_HERE_rs2_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 24, 20)
  VAR_TREENAIL_WAS_HERE_rs1_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 19, 15)
  VAR_TREENAIL_WAS_HERE_rd_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 11, 7)
  VAR_0 = VAR_TREENAIL_WAS_HERE_imm_2_0.cast(3, False)
  VAR_1 = VAR_TREENAIL_WAS_HERE_rs1_4_0.cast(5, False)
  VAR_2 = VAR_TREENAIL_WAS_HERE_rd_4_0.cast(5, False)
  VAR_3 = ArbInt.from_int(read_reg(VAR_1.as_int()), 32, False)
  TMP_27 = ArbInt.from_int(ROM_MERGED5RCON[VAR_0.as_int() + 0], 32, False)
  VAR_4 = TMP_27
  VAR_5 = VAR_3.xor(VAR_4)
  write_reg(VAR_2.as_int(), VAR_5.as_int())
def sparkle_whole_enci_x(opcode, read_reg=read_reg, write_reg=write_reg, read_cust_reg=read_cust_reg, write_cust_reg=write_cust_reg, read_pc=read_pc, write_pc=write_pc, read_mem=read_mem, write_mem=write_mem):
  bitvector_opcode = ArbInt.from_int(opcode, 32, False)
  VAR_TREENAIL_WAS_HERE_imm_2_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 27, 25)
  VAR_TREENAIL_WAS_HERE_rs2_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 24, 20)
  VAR_TREENAIL_WAS_HERE_rs1_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 19, 15)
  VAR_TREENAIL_WAS_HERE_rd_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 11, 7)
  VAR_0 = ArbInt.from_int(32, 6, False)
  VAR_c1_i4 = ArbInt.from_int(1, 4, False)
  VAR_c4_i4 = ArbInt.from_int(4, 4, False)
  VAR_c0_i4 = ArbInt.from_int(0, 4, False)
  VAR_1 = VAR_TREENAIL_WAS_HERE_imm_2_0.cast(3, False)
  VAR_2 = VAR_TREENAIL_WAS_HERE_rs2_4_0.cast(5, False)
  VAR_3 = VAR_TREENAIL_WAS_HERE_rs1_4_0.cast(5, False)
  VAR_4 = VAR_TREENAIL_WAS_HERE_rd_4_0.cast(5, False)
  VAR_5 = ArbInt.from_int(read_reg(VAR_3.as_int()), 32, False)
  VAR_6 = ArbInt.from_int(read_reg(VAR_2.as_int()), 32, False)
  TMP_28 = ArbInt.from_int(ROM_MERGED5RCON[VAR_1.as_int() + 0], 32, False)
  VAR_7 = TMP_28
  def helper_function_for_1():
    def helper_function_for_1_body(i, iter_args):
      VAR_arg0 = ArbInt.from_int(i, 32, False) #TODO do not hardcode type... what about negative values?
      VAR_arg1, VAR_arg2 = iter_args
      VAR_9 = VAR_arg0.cast(4, False)
      VAR_10 = VAR_9.cast(3, False)
      VAR_11 = VAR_10.cast(2, False)
      TMP_29 = ArbInt.from_int(ROM_MERGED5ROT_0[VAR_11.as_int() + 0], 8, False)
      VAR_12 = TMP_29
      VAR_13 = VAR_arg2.shift_right(VAR_12)
      VAR_14 = VAR_0.sub(VAR_12)
      VAR_15 = VAR_arg2.shift_left(VAR_14)
      VAR_16 = VAR_13.or_(VAR_15)
      VAR_17 = VAR_arg1.add(VAR_16)
      VAR_18 = VAR_17.cast(32, False)
      VAR_19 = VAR_10.cast(2, False)
      TMP_30 = ArbInt.from_int(ROM_MERGED5ROT_1[VAR_19.as_int() + 0], 8, False)
      VAR_20 = TMP_30
      VAR_21 = VAR_18.shift_right(VAR_20)
      VAR_22 = VAR_0.sub(VAR_20)
      VAR_23 = VAR_18.shift_left(VAR_22)
      VAR_24 = VAR_21.or_(VAR_23)
      VAR_25 = VAR_arg2.xor(VAR_24)
      VAR_26 = VAR_18.xor(VAR_7)
      return VAR_26, VAR_25
    iter_args = (VAR_5, VAR_6)
    for i in range(VAR_c0_i4.as_int(), VAR_c4_i4.as_int(), VAR_c1_i4.as_int()):
      iter_args = helper_function_for_1_body(i, iter_args)
    return iter_args
  VAR_8_0, VAR_8_1 = helper_function_for_1()
  write_reg(VAR_4.as_int(), VAR_8_0.as_int())
def sparkle_whole_enci_y(opcode, read_reg=read_reg, write_reg=write_reg, read_cust_reg=read_cust_reg, write_cust_reg=write_cust_reg, read_pc=read_pc, write_pc=write_pc, read_mem=read_mem, write_mem=write_mem):
  bitvector_opcode = ArbInt.from_int(opcode, 32, False)
  VAR_TREENAIL_WAS_HERE_imm_2_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 27, 25)
  VAR_TREENAIL_WAS_HERE_rs2_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 24, 20)
  VAR_TREENAIL_WAS_HERE_rs1_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 19, 15)
  VAR_TREENAIL_WAS_HERE_rd_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 11, 7)
  VAR_0 = ArbInt.from_int(32, 6, False)
  VAR_c1_i4 = ArbInt.from_int(1, 4, False)
  VAR_c4_i4 = ArbInt.from_int(4, 4, False)
  VAR_c0_i4 = ArbInt.from_int(0, 4, False)
  VAR_1 = VAR_TREENAIL_WAS_HERE_imm_2_0.cast(3, False)
  VAR_2 = VAR_TREENAIL_WAS_HERE_rs2_4_0.cast(5, False)
  VAR_3 = VAR_TREENAIL_WAS_HERE_rs1_4_0.cast(5, False)
  VAR_4 = VAR_TREENAIL_WAS_HERE_rd_4_0.cast(5, False)
  VAR_5 = ArbInt.from_int(read_reg(VAR_3.as_int()), 32, False)
  VAR_6 = ArbInt.from_int(read_reg(VAR_2.as_int()), 32, False)
  TMP_31 = ArbInt.from_int(ROM_MERGED5RCON[VAR_1.as_int() + 0], 32, False)
  VAR_7 = TMP_31
  def helper_function_for_2():
    def helper_function_for_2_body(i, iter_args):
      VAR_arg0 = ArbInt.from_int(i, 32, False) #TODO do not hardcode type... what about negative values?
      VAR_arg1, VAR_arg2 = iter_args
      VAR_9 = VAR_arg0.cast(4, False)
      VAR_10 = VAR_9.cast(3, False)
      VAR_11 = VAR_10.cast(2, False)
      TMP_32 = ArbInt.from_int(ROM_MERGED5ROT_0[VAR_11.as_int() + 0], 8, False)
      VAR_12 = TMP_32
      VAR_13 = VAR_arg2.shift_right(VAR_12)
      VAR_14 = VAR_0.sub(VAR_12)
      VAR_15 = VAR_arg2.shift_left(VAR_14)
      VAR_16 = VAR_13.or_(VAR_15)
      VAR_17 = VAR_arg1.add(VAR_16)
      VAR_18 = VAR_17.cast(32, False)
      VAR_19 = VAR_10.cast(2, False)
      TMP_33 = ArbInt.from_int(ROM_MERGED5ROT_1[VAR_19.as_int() + 0], 8, False)
      VAR_20 = TMP_33
      VAR_21 = VAR_18.shift_right(VAR_20)
      VAR_22 = VAR_0.sub(VAR_20)
      VAR_23 = VAR_18.shift_left(VAR_22)
      VAR_24 = VAR_21.or_(VAR_23)
      VAR_25 = VAR_arg2.xor(VAR_24)
      VAR_26 = VAR_18.xor(VAR_7)
      return VAR_26, VAR_25
    iter_args = (VAR_5, VAR_6)
    for i in range(VAR_c0_i4.as_int(), VAR_c4_i4.as_int(), VAR_c1_i4.as_int()):
      iter_args = helper_function_for_2_body(i, iter_args)
    return iter_args
  VAR_8_0, VAR_8_1 = helper_function_for_2()
  write_reg(VAR_4.as_int(), VAR_8_1.as_int())
def sqrt_decoupled(opcode, read_reg=read_reg, write_reg=write_reg, read_cust_reg=read_cust_reg, write_cust_reg=write_cust_reg, read_pc=read_pc, write_pc=write_pc, read_mem=read_mem, write_mem=write_mem):
  bitvector_opcode = ArbInt.from_int(opcode, 32, False)
  VAR_TREENAIL_WAS_HERE_rs_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 19, 15)
  VAR_TREENAIL_WAS_HERE_rd_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 11, 7)
  VAR_0 = ArbInt.from_int(1, 1, False)
  VAR_c1_i7 = ArbInt.from_int(1, 7, False)
  VAR_c32_i7 = ArbInt.from_int(32, 7, False)
  VAR_c0_i7 = ArbInt.from_int(0, 7, False)
  VAR_1 = ArbInt.from_int(0, 1, False)
  VAR_2 = ArbInt.from_int(1073741824, 31, False)
  VAR_3 = VAR_TREENAIL_WAS_HERE_rs_4_0.cast(5, False)
  VAR_4 = VAR_TREENAIL_WAS_HERE_rd_4_0.cast(5, False)
  VAR_5 = ArbInt.from_int(read_reg(VAR_3.as_int()), 32, False)
  VAR_6 = VAR_2.cast(32, False)
  VAR_7 = VAR_1.cast(32, False)
  def helper_function_for_3():
    def helper_function_for_3_body(i, iter_args):
      VAR_arg0 = ArbInt.from_int(i, 32, False) #TODO do not hardcode type... what about negative values?
      VAR_arg1, VAR_arg2, VAR_arg3 = iter_args
      VAR_13 = VAR_arg2.add(VAR_arg3)
      VAR_14 = VAR_13.cast(32, False)
      VAR_15 = VAR_arg1.ge(VAR_14)
      VAR_16 = VAR_15.cast(1, False)
      VAR_17 = VAR_16.cast(1, False)
      def helper_function_if_1_then():
        VAR_21 = VAR_arg1.sub(VAR_14)
        VAR_22 = VAR_21.cast(32, False)
        VAR_23 = VAR_14.add(VAR_arg3)
        VAR_24 = VAR_23.cast(32, False)
        return VAR_22, VAR_24
      def helper_function_if_1_else():
        return VAR_arg1, VAR_arg2
      if VAR_17.is_true():
        VAR_18_0, VAR_18_1 = helper_function_if_1_then()
      else:
        VAR_18_0, VAR_18_1 = helper_function_if_1_else()
      VAR_19 = VAR_18_0.shift_left(VAR_0)
      VAR_20 = VAR_arg3.shift_right(VAR_0)
      return VAR_19, VAR_18_1, VAR_20
    iter_args = (VAR_5, VAR_7, VAR_6)
    for i in range(VAR_c0_i7.as_int(), VAR_c32_i7.as_int(), VAR_c1_i7.as_int()):
      iter_args = helper_function_for_3_body(i, iter_args)
    return iter_args
  VAR_8_0, VAR_8_1, VAR_8_2 = helper_function_for_3()
  VAR_9 = VAR_8_0.gt(VAR_8_1)
  VAR_10 = VAR_9.cast(1, False)
  VAR_11 = VAR_10.cast(1, False)
  def helper_function_if_2_then():
    VAR_13 = VAR_8_1.add(VAR_0)
    VAR_14 = VAR_13.cast(32, False)
    return VAR_14
  def helper_function_if_2_else():
    return VAR_8_1
  if VAR_11.is_true():
    VAR_12 = helper_function_if_2_then()
  else:
    VAR_12 = helper_function_if_2_else()
  write_reg(VAR_4.as_int(), VAR_12.as_int())
def sqrt_stall(opcode, read_reg=read_reg, write_reg=write_reg, read_cust_reg=read_cust_reg, write_cust_reg=write_cust_reg, read_pc=read_pc, write_pc=write_pc, read_mem=read_mem, write_mem=write_mem):
  bitvector_opcode = ArbInt.from_int(opcode, 32, False)
  VAR_TREENAIL_WAS_HERE_rs_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 19, 15)
  VAR_TREENAIL_WAS_HERE_rd_4_0 = bitvector_opcode.bitextract(ArbInt.from_int(0, 1, False), 11, 7)
  VAR_0 = ArbInt.from_int(1, 1, False)
  VAR_c1_i7 = ArbInt.from_int(1, 7, False)
  VAR_c32_i7 = ArbInt.from_int(32, 7, False)
  VAR_c0_i7 = ArbInt.from_int(0, 7, False)
  VAR_1 = ArbInt.from_int(0, 1, False)
  VAR_2 = ArbInt.from_int(1073741824, 31, False)
  VAR_3 = VAR_TREENAIL_WAS_HERE_rs_4_0.cast(5, False)
  VAR_4 = VAR_TREENAIL_WAS_HERE_rd_4_0.cast(5, False)
  VAR_5 = ArbInt.from_int(read_reg(VAR_3.as_int()), 32, False)
  VAR_6 = VAR_2.cast(32, False)
  VAR_7 = VAR_1.cast(32, False)
  def helper_function_for_4():
    def helper_function_for_4_body(i, iter_args):
      VAR_arg0 = ArbInt.from_int(i, 32, False) #TODO do not hardcode type... what about negative values?
      VAR_arg1, VAR_arg2, VAR_arg3 = iter_args
      VAR_13 = VAR_arg1.add(VAR_arg3)
      VAR_14 = VAR_13.cast(32, False)
      VAR_15 = VAR_arg2.ge(VAR_14)
      VAR_16 = VAR_15.cast(1, False)
      VAR_17 = VAR_16.cast(1, False)
      def helper_function_if_3_then():
        VAR_21 = VAR_arg2.sub(VAR_14)
        VAR_22 = VAR_21.cast(32, False)
        VAR_23 = VAR_14.add(VAR_arg3)
        VAR_24 = VAR_23.cast(32, False)
        return VAR_24, VAR_22
      def helper_function_if_3_else():
        return VAR_arg1, VAR_arg2
      if VAR_17.is_true():
        VAR_18_0, VAR_18_1 = helper_function_if_3_then()
      else:
        VAR_18_0, VAR_18_1 = helper_function_if_3_else()
      VAR_19 = VAR_18_1.shift_left(VAR_0)
      VAR_20 = VAR_arg3.shift_right(VAR_0)
      return VAR_18_0, VAR_19, VAR_20
    iter_args = (VAR_7, VAR_5, VAR_6)
    for i in range(VAR_c0_i7.as_int(), VAR_c32_i7.as_int(), VAR_c1_i7.as_int()):
      iter_args = helper_function_for_4_body(i, iter_args)
    return iter_args
  VAR_8_0, VAR_8_1, VAR_8_2 = helper_function_for_4()
  VAR_9 = VAR_8_1.gt(VAR_8_0)
  VAR_10 = VAR_9.cast(1, False)
  VAR_11 = VAR_10.cast(1, False)
  def helper_function_if_4_then():
    VAR_13 = VAR_8_0.add(VAR_0)
    VAR_14 = VAR_13.cast(32, False)
    return VAR_14
  def helper_function_if_4_else():
    return VAR_8_0
  if VAR_11.is_true():
    VAR_12 = helper_function_if_4_then()
  else:
    VAR_12 = helper_function_if_4_else()
  write_reg(VAR_4.as_int(), VAR_12.as_int())


decoder_table = [
  (0b11111110000000000111000001111111, 0b00000010000000000000000000001011, setup),
  (0b11111111111111111111000001111111, 0b00000100000000000010000000001011, lw_inc),
  (0b11111111111100000111000001111111, 0b00000110000000000010000000001011, sw_inc),
  (0b00000000000000000111000001111111, 0b00000000000000000100000000001011, cv_beqimm),
  (0b11111110000000000111000001111111, 0b00010100000000000000000000001011, DOTP),
  (0b00000000000000000111000001111111, 0b00000000000000000010000001111011, ijmp),
  (0b11111111111100000111000001111111, 0b00001000000000000000000000101011, sbox),
  (0b11111110000000000111000001111111, 0b00000100000000000111000001111011, sparkle_ell),
  (0b11110000000000000111000001111111, 0b00000000000000000110000001111011, sparkle_rcon),
  (0b11110000000000000111000001111111, 0b10000000000000000110000001111011, sparkle_whole_enci_x),
  (0b11110000000000000111000001111111, 0b10010000000000000110000001111011, sparkle_whole_enci_y),
  (0b11111111111100000111000001111111, 0b00001100000000000000000000001011, sqrt_decoupled),
  (0b11111111111100000111000001111111, 0b00001010000000000000000000001011, sqrt_stall),
]

def decode(instruction):
  for mask, match_value, handler in decoder_table:
    if (instruction & mask) == match_value:
      handler(instruction)
      return
  1/0 # Unknown instruction

# Init ISAX state
if 'instruction' in locals() or 'instruction' in globals():
  decode(instruction)
