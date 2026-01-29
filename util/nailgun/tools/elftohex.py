from elftools.elf.elffile import ELFFile

def elf_section_to_hex(hex_file, section, word_size=4, memory_size=0x4000):
    """
    Converts an ELF section to a Verilog HEX. The section address is automatically adjusted
    to be with respect to the memory.

    Parameters:
        hex_file (File): Opened hex file.
        section (Section): Section from ELFFile to convert to hex.
        word_size (int): Word size in bytes for the HEX file (default: 4).
        memory_size (int): Size of available memory.
    """
    try:
        if not (section['sh_flags'] & 0x2):  # Check if the section is allocatable
            print(f"WARNING: Provided section {section.name} is not allocatable. Skipping.")
            return
        
        section_addr = section['sh_addr'] # w.r.t. processor memory map
        section_addr = section_addr & (memory_size - 1) # remove memory map address bits
        section_addr = section_addr // word_size # byte address to word address
        data = section.data()
        # Convert section data to hexadecimal words
        hex_lines = []
        for i in range(0, len(data), word_size):
            word = data[i:i+word_size]
            hex_value = ''.join(f"{byte:02X}" for byte in reversed(word))
            hex_lines.append(hex_value)
        # Write all sections' HEX data to the HEX file
        hex_file.write(f"@{section_addr:X}\n")
        for line in hex_lines:
            hex_file.write(line + '\n')
        print(f"HEX of {section.name} successfully written.")

    except Exception as e:
        print(f"An error occurred: {e}")

def elf_to_hex(elf_path, hex_path, sections, word_size=4, memory_size=0x4000):
    """
    Dump sections of the provided ELF file to the desired hex file path for usage with verilog $loadmemh.

    Args:
        elf_path (str): File path to the ELF file.
        hex_path (str): File path to the resulting hex file.
        sections (list[str]): List of sections to dump inside the hex file. E.g., `[.text]`.
        word_size (int, optional): Word size in bytes inside the memory. Defaults to 4.
        memory_size (hexadecimal, optional): Total size of memory in bytes. Defaults to 0x4000.
    """
    try:
        with open(elf_path, "rb") as elf_file:
            elffile = ELFFile(elf_file)
            with open(hex_path, "w") as hex_file:
                for section in elffile.iter_sections():
                    section_name = section.name
                    if section_name not in sections:
                        continue
                    
                    elf_section_to_hex(hex_file, section, word_size=word_size, memory_size=memory_size)
    except FileNotFoundError as e:
        print(f"File not found. {e}")
        exit(-1)
