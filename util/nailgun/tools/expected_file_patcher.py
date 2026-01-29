#!/usr/bin/env python
import sys

def process_line(line):
  if line.startswith('#'):
    return line
  else:
    try:
      split = line.strip().split()
      if len(split) > 1:
        val1 = int(split[0])
        val2 = int(split[1])
        hex_val1 = format(val1 & 0x0FFFF, '04X')
        hex_val2 = format(val2 & 0x0FFFF, '04X')
        comment = f'# Previous decimal values: {val1} {val2}\n'
        return comment + hex_val1 + hex_val2 + '\n'
      else:
        value = int(line.strip())
        hex_value = format(value & 0xFFFFFFFF, '08X')
        comment = f'# Previous decimal value: {value}\n'
        return comment + hex_value + '\n'
    except ValueError:
      return ''

if len(sys.argv) != 3:
  print("Usage: ./expected_file_patcher.py input_file output_file")
  sys.exit(1)

input_file_path = sys.argv[1]
output_file_path = sys.argv[2]

with open(input_file_path, 'r') as input_file:
  lines = input_file.readlines()

processed_lines = [process_line(line) for line in lines]

with open(output_file_path, 'w') as output_file:
  output_file.writelines(processed_lines)

print("Processing complete.")