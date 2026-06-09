class ArbInt:
    def __init__(self, value, bitwidth, signed):
        self.bitwidth = int(bitwidth)
        self.signed = signed
        self.mask = (1 << self.bitwidth) - 1
        self.value = self._truncate(int(value))

    def reverse(self):
        # bit_str = f"{self.value:0{self.bitwidth}b}"
        bit_str = "{{:0{}b}}".format(self.bitwidth).format(self.value)
        reversed_str = bit_str[::-1]
        reversed_val = int(reversed_str, 2)
        return ArbInt(reversed_val, self.bitwidth, self.signed)

    @classmethod
    def from_int(cls, value, bitwidth, signed):
        return cls(value, bitwidth, signed)

    def as_int(self):
        return self._sign_extend(self.value) if self.signed else self.value

    def _truncate(self, value):
        return value & self.mask

    def _sign_extend(self, value):
        if self.signed and (value & (1 << (self.bitwidth - 1))):
            return value | ~self.mask
        return value

    def _extend(self, new_bitwidth, new_signed):
        return ArbInt(self.as_int(), new_bitwidth, new_signed)

    def is_true(self):
        return self.value != 0

    def _bin_op(self, other, op, get_res_type):
        if not isinstance(other, ArbInt):
            raise TypeError("Operands must be ArbInt instances")
        result_width, result_signed = get_res_type(other)
        a = self._extend(result_width, result_signed)
        b = other._extend(result_width, result_signed)
        result_val = op(a.as_int(), b.as_int())
        return ArbInt(result_val, result_width, result_signed)

    def _get_add_res_type(a, b):
        diff_signed = a.signed != b.signed
        a_extend = 1 if diff_signed and not a.signed else 0
        b_extend = 1 if diff_signed and not b.signed else 0
        return max(a.bitwidth + a_extend, b.bitwidth + b_extend) + 1, True if diff_signed else a.signed
    def _get_sub_res_type(a, b):
        w, _ = a._get_add_res_type(b)
        return w, True
    def _get_mul_res_type(a, b):
        return a.bitwidth + b.bitwidth, True if a.signed != b.signed else a.signed
    def _get_div_res_type(a, b):
        extend = 1 if (a.signed and b.signed) or (not a.signed and b.signed) else 0
        return a.bitwidth + extend, a.signed or b.signed
    def _get_mod_res_type(a, b):
        if a.signed == b.signed:
            return min(a.bitwidth, b.bitwidth), a.signed
        if a.signed:
            return min(a.bitwidth, b.bitwidth + 1), a.signed
        return min(a.bitwidth, max(1, b.bitwidth - 1)), a.signed
    def _get_bitwise_res_type(a, b):
        return max(a.bitwidth, b.bitwidth), True if a.signed != b.signed else a.signed

    def add(self, other): return self._bin_op(other, lambda a, b: a + b, self._get_add_res_type)
    def sub(self, other): return self._bin_op(other, lambda a, b: a - b, self._get_sub_res_type)
    def mul(self, other): return self._bin_op(other, lambda a, b: a * b, self._get_mul_res_type)
    def div(self, other): return self._bin_op(other, lambda a, b: int(a / b), self._get_div_res_type)

    #TODO we probably have to implement this differently
    def mod(self, other): return self._bin_op(other, lambda a, b: a % b, self._get_mod_res_type)

    def or_(self, other): return self._bin_op(other, lambda a, b: a | b, self._get_bitwise_res_type)
    def xor(self, other): return self._bin_op(other, lambda a, b: a ^ b, self._get_bitwise_res_type)
    def and_(self, other): return self._bin_op(other, lambda a, b: a & b, self._get_bitwise_res_type)

    # def _shift_res_type(a, b):
    #     return a.bitwidth, a.signed
    # def shift_left(self, other):
    #     return self._bin_op(other, lambda a, b: a << b, self._shift_res_type)
    # def shift_right(self, other):
    #     return self._bin_op(other, lambda a, b: a >> b, self._shift_res_type)

    def shift_left(self, other):
        if not isinstance(other, ArbInt):
            raise TypeError("Shift amount must be an ArbInt")
        shift_amount = other.as_int()
        if shift_amount < 0:
            # Flip direction: x << (-k) == x >> k
            return self.shift_right(ArbInt(-shift_amount, other.bitwidth, False))
        if shift_amount >= self.bitwidth:
            # Shifting all bits out results in zero
            return ArbInt(0, self.bitwidth, self.signed)
        shifted_val = (self.as_int() << shift_amount) & self.mask
        return ArbInt(shifted_val, self.bitwidth, self.signed)

    def shift_right(self, other):
        if not isinstance(other, ArbInt):
            raise TypeError("Shift amount must be an ArbInt")
        shift_amount = other.as_int()
        if shift_amount < 0:
            # Flip direction: x >> (-k) == x << k
            return self.shift_left(ArbInt(-shift_amount, other.bitwidth, False))
        if shift_amount >= self.bitwidth:
            # All bits shifted out
            if self.signed:
                # Arithmetic right shift: fill with sign bit
                sign_bit = (self.value >> (self.bitwidth - 1)) & 1
                if sign_bit == 1:
                    # All bits set to 1
                    return ArbInt(self.mask, self.bitwidth, True)
                else:
                    return ArbInt(0, self.bitwidth, True)
            else:
                # Logical right shift: all zero
                return ArbInt(0, self.bitwidth, False)
        
        if self.signed:
            # Arithmetic right shift: preserve sign
            val = self._sign_extend(self.value) >> shift_amount
            return ArbInt(val, self.bitwidth, True)
        else:
            # Logical right shift: shift in zeros from left
            val = self.value >> shift_amount
            return ArbInt(val, self.bitwidth, False)


    def _cmp_res_type(a, b):
        w, s = a._get_add_res_type(b)
        return w - 1, s

    def eq(self, other): return self._bin_op(other, lambda a, b: int(a == b), self._cmp_res_type)._extend(1, False)
    def ne(self, other): return self._bin_op(other, lambda a, b: int(a != b), self._cmp_res_type)._extend(1, False)
    def lt(self, other): return self._bin_op(other, lambda a, b: int(a < b), self._cmp_res_type)._extend(1, False)
    def le(self, other): return self._bin_op(other, lambda a, b: int(a <= b), self._cmp_res_type)._extend(1, False)
    def gt(self, other): return self._bin_op(other, lambda a, b: int(a > b), self._cmp_res_type)._extend(1, False)
    def ge(self, other): return self._bin_op(other, lambda a, b: int(a >= b), self._cmp_res_type)._extend(1, False)

    def cast(self, new_bitwidth, new_signed):
        return self._extend(new_bitwidth, new_signed)

    def bitextract(self, offset, frm, to):
        assert isinstance(offset, ArbInt)
        offset_val = offset.as_int()
        from_bit, to_bit = frm, to
        lo, hi = sorted((from_bit, to_bit))
        width = hi - lo + 1
        val = (self.value >> (offset_val + lo)) & ((1 << width) - 1)
        if frm < to:
            # Reverse bits
            val = int('{:0{w}b}'.format(val, w=width)[::-1], 2)
        return ArbInt(val, width, False)

    def bitset(self, offset, frm, to, value):
        assert isinstance(offset, ArbInt) and isinstance(value, ArbInt)
        offset_val = offset.as_int()
        lo, hi = sorted((frm, to))
        width = hi - lo + 1
        assert width == value.bitwidth
        mask = ((1 << width) - 1) << (offset_val + lo)
        if frm < to:
            # Reverse bits
            value = value.reverse()
        new_val = (self.value & ~mask) | ((value.value & ((1 << width) - 1)) << (offset_val + lo))
        return ArbInt(new_val, self.bitwidth, self.signed)

    def concat(self, other):
        new_val = (self.value << other.bitwidth) | other.value
        return ArbInt(new_val, self.bitwidth + other.bitwidth, False)

    def __repr__(self):
        # return f"ArbInt.from_int({self.as_int()}, {self.bitwidth}, {self.signed})"
        return "ArbInt.from_int({}, {}, {})".format(self.as_int(), self.bitwidth, self.signed)
