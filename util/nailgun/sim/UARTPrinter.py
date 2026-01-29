class UARTPrinter:
    def __init__(self):
        self.buffer = b''

    def write_byte(self, byte):
        char = chr(byte)
        if char == '\n':
            print(f"\n\n\nSIMULATION PERFORMED A PRINTF:\n{self.buffer.decode(errors='replace')}\n\n\n")
            self.buffer = b''
        else:
            self.buffer += bytes([byte])
    def flush(self):
        if self.buffer:
            print(f"\n\n\nSIMULATION PERFORMED A PRINTF:\n{self.buffer.decode(errors='replace')}\n\n\n")
            self.buffer = b''
