//Dot product bias setup
//rs1, rs2: 8bit biases (zero points) to subtract from each dotpbias rs1/rs2 vector element
{"setup_dotpbias", 0, INSN_CLASS_I, "s,t", MATCH_DOTPBIAS_SET, MASK_DOTPBIAS_SET, match_opcode, 0 },
//Set ADDR to rs1
{"setup_dotpaddr", 0, INSN_CLASS_I, "s", MATCH_DOTPADDR_SET, MASK_DOTPADDR_SET, match_opcode, 0 },
//Increment ADDR by imm12
{"incr_dotpaddr", 0, INSN_CLASS_I, "Xtu12@20", MATCH_DOTPINCADDR_SET, MASK_DOTPINCADDR_SET, match_opcode, 0 },
//Load word + biased dot product instruction
//MEM[ADDR+imm12]: 4xint8 input vector A
//rs1: 4xint8 input vector B
//rd: Result sum
//NOTE: notation may be confusing, the address 'o' is added to is the internal ADDR reg, not 's' (rs1)
{"lwdotpbias", 0, INSN_CLASS_I, "d,o(s)", MATCH_LWDOTPBIAS, MASK_LWDOTPBIAS, match_opcode, 0 },

//rs1: inner loop start, rs2: inner loop end
{"setup_inner_zol2d", 0, INSN_CLASS_I, "s,t", MATCH_SETUP_ZOL2DINNER, MASK_SETUP_ZOL2DINNER, match_opcode, 0 }, //count,pc-rel end offs
//rs1: counters {8'(outer),8'(inner)}
//branch immediate (but unsigned)
{"setup_zol2d", 0, INSN_CLASS_I, "s,p", MATCH_SETUP_ZOL2D, MASK_SETUP_ZOL2D, match_opcode, 0 }, //count,pc-rel end offs
