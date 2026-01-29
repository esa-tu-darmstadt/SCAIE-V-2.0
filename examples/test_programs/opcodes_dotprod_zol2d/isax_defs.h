/* Custom ISAX */

//biased int8 dot product
//Bias setup instruction
//rs1: 8bit bias (zero point) to subtract from each 'DOTPB rs1' vector element
//rs2: 8bit bias (zero point) to subtract from each 'DOTPB rs2' vector element
#define MATCH_DOTPBIAS_SET (0b00010010000000000001000000001011)
#define MASK_DOTPBIAS_SET  (0b11111110000000000111111111111111)
//Dot product instruction (with biases)
//rs1, rs2: 4xint8 input vectors; rd: Result sum
#define MATCH_DOTPBIAS     (0b00010010000000000000000000001011)
#define MASK_DOTPBIAS      (0b11111110000000000111000001111111)
//Dot product instruction (no biases)
//rs1, rs2: 4xint8 input vectors; rd: Result sum
#define MATCH_DOTP         (0b00010100000000000000000000001011)
#define MASK_DOTP          (0b11111110000000000111000001111111)

//ZOL inner setup
//rs1: inner loop start, rs2: inner loop end
#define MATCH_SETUP_ZOL2DINNER (0b00000000000000000110000000001011) //rs1,rs2
#define MASK_SETUP_ZOL2DINNER  (0b11111110000000000111111111111111)
//ZOL setup, B-type with rs2=0
//rs1: counters {8'(outer),8'(inner)}
//uimm12: PC-rel offset like  (unsigned)"-------00000-----101-----0001011"
//Test: Force MSB to 0, since GAS assumes a signed immediate.
// Clear the mask bit if that causes general issues even for intended forward edges.
#define MATCH_SETUP_ZOL2D (0b00000000000000000101000000001011)
#define MASK_SETUP_ZOL2D  (0b10000001111100000111000001111111)

