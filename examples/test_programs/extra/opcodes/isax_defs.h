/* Custom ISAX */
#define MATCH_AUTOINC_SETUP 0x0200000b //R-type with rd=0
#define MASK_AUTOINC_SETUP 0xfe007fff
#define MATCH_AUTOINC_LW_INC 0x0400200b //R-type with rs1=rs2=0
#define MASK_AUTOINC_LW_INC 0xfffff07f
#define MATCH_AUTOINC_SW_INC 0x0600200b //R-type with rd=rs2=0
#define MASK_AUTOINC_SW_INC 0xfff07fff

#define MATCH_IJMP 0x207B //I-type with rd=0
#define MASK_IJMP 0x7fff

#define MATCH_BRIMM_CV_BEQIMM 0x400b //B-type with signed imm[4:0] in place of rs2
#define MASK_BRIMM_CV_BEQIMM 0x707f

#define MATCH_SBOX 0x0800002B //R-type with rs2=0
#define MASK_SBOX 0xfff0707f

#define MATCH_SPARKLE_ELL 0x0400707b //R-type
#define MASK_SPARKLE_ELL 0xfe00707f
#define MATCH_SPARKLE_RCON 0x0000607b //R-type but with imm[2:0] in place of funct7[2:0]
#define MASK_SPARKLE_RCON 0xf000707f
#define MATCH_SPARKLE_WHOLE_ENCI_X 0x8000607b //R-type but with imm[2:0] in place of funct7[2:0]
#define MASK_SPARKLE_WHOLE_ENCI_X 0xf000707f
#define MATCH_SPARKLE_WHOLE_ENCI_Y 0x9000607b //R-type but with imm[2:0] in place of funct7[2:0]
#define MASK_SPARKLE_WHOLE_ENCI_Y 0xf000707f

#define MATCH_SQRT_STALL 0x0a00000b//R-type without rs2
#define MASK_SQRT_STALL 0xfff0707f
#define MATCH_SQRT_DECOUPLED 0x0c00000b//R-type without rs2
#define MASK_SQRT_DECOUPLED 0xfff0707f

#define MATCH_SETUP_ZOL 0x500b //Custom, funct3 and opcode present, rd=0; uimmL[11:0] = instr[31:20], uimmS[4:0] = instr[19:15]
#define MASK_SETUP_ZOL 0x7fff
