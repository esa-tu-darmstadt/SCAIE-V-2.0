/* Custom ISAX */
{"autoinc_setup", 0, INSN_CLASS_I, "s,t", MATCH_AUTOINC_SETUP, MASK_AUTOINC_SETUP, match_opcode, 0 }, //rs1 (addr),rs2 (incr)
{"autoinc_lw_inc", 0, INSN_CLASS_I, "d", MATCH_AUTOINC_LW_INC, MASK_AUTOINC_LW_INC, match_opcode, 0 }, //rd
{"autoinc_sw_inc", 0, INSN_CLASS_I, "s", MATCH_AUTOINC_SW_INC, MASK_AUTOINC_SW_INC, match_opcode, 0 }, //rs1 (data)

{"ijmp", 0, INSN_CLASS_I, "s,j", MATCH_IJMP, MASK_IJMP, match_opcode, 0 }, //rs1,imm

{"brimm_cv_beqimm", 0, INSN_CLASS_I, "s,Xts5@20,p", MATCH_BRIMM_CV_BEQIMM, MASK_BRIMM_CV_BEQIMM, match_opcode, 0 }, //rs1,imm,pc-rel

{"sbox", 0, INSN_CLASS_I, "d,s", MATCH_SBOX, MASK_SBOX, match_opcode, 0 }, //rd,rs1

{"sparkle_ell", 0, INSN_CLASS_I, "d,s,t", MATCH_SPARKLE_ELL, MASK_SPARKLE_ELL, match_opcode, 0 }, //rd,rs1,rs2
{"sparkle_rcon", 0, INSN_CLASS_I, "d,s,t,Xtu3@25", MATCH_SPARKLE_RCON, MASK_SPARKLE_RCON, match_opcode, 0 }, //rd,rs1,rs2,i_rcon
{"sparkle_whole_enci_x", 0, INSN_CLASS_I, "d,s,t,Xtu3@25", MATCH_SPARKLE_WHOLE_ENCI_X, MASK_SPARKLE_WHOLE_ENCI_X, match_opcode, 0 }, //rd,rs1,rs2,i_rcon
{"sparkle_whole_enci_y", 0, INSN_CLASS_I, "d,s,t,Xtu3@25", MATCH_SPARKLE_WHOLE_ENCI_Y, MASK_SPARKLE_WHOLE_ENCI_Y, match_opcode, 0 }, //rd,rs1,rs2,i_rcon

{"sqrt_stall", 0, INSN_CLASS_I, "d,s", MATCH_SQRT_STALL, MASK_SQRT_STALL, match_opcode, 0 }, //rd,rs1
{"sqrt_decoupled", 0, INSN_CLASS_I, "d,s", MATCH_SQRT_DECOUPLED, MASK_SQRT_DECOUPLED, match_opcode, 0 }, //rd,rs1

//ZOL: End offset has to be provided as absolute in 2 byte steps.
// Anything else would require a custom relocation type, touching many files in binutils (tc-riscv.c, bfd-in2.h, elfxx-riscv.c, maybe others).
{"setup_zol", 0, INSN_CLASS_I, "Xtu12@20,Xtu5@15", MATCH_SETUP_ZOL, MASK_SETUP_ZOL, match_opcode, 0 }, //count,pc-rel end offs

