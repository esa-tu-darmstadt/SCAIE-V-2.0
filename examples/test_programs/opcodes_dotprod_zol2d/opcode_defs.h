//Dot product bias setup
//rs1, rs2: 8bit biases (zero points) to subtract from each dotpbias rs1/rs2 vector element
{"setup_dotpbias", 0, INSN_CLASS_I, "s,t", MATCH_DOTPBIAS_SET, MASK_DOTPBIAS_SET, match_opcode, 0 },
//Biased dot product
//rs1, rs2: 4xint8 input vectors; rd: Result sum
{"dotpbias", 0, INSN_CLASS_I, "d,s,t", MATCH_DOTPBIAS, MASK_DOTPBIAS, match_opcode, 0 },
//Regular dot product
//rs1, rs2: 4xint8 input vectors; rd: Result sum
{"dotp", 0, INSN_CLASS_I, "d,s,t", MATCH_DOTP, MASK_DOTP, match_opcode, 0 },

//rs1: inner loop start, rs2: inner loop end
{"setup_inner_zol2d", 0, INSN_CLASS_I, "s,t", MATCH_SETUP_ZOL2DINNER, MASK_SETUP_ZOL2DINNER, match_opcode, 0 }, //count,pc-rel end offs
//rs1: counters {8'(outer),8'(inner)}
//branch immediate (but unsigned)
{"setup_zol2d", 0, INSN_CLASS_I, "s,p", MATCH_SETUP_ZOL2D, MASK_SETUP_ZOL2D, match_opcode, 0 }, //count,pc-rel end offs
