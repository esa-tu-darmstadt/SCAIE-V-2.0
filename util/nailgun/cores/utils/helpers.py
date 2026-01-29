import os

def read_file_lines(filename):
    lines = []
    with open(filename, 'r') as file:
        for line in file:
            # Strip newline character and truncate whitespace from both ends
            line = line.strip()
            # Skip empty lines
            if line:
                lines.append(line)
    return lines

def find_verilog_srcs(source_folder, blacklist = []):
    # Blacklist unnecessary files, ones that might break the build
    blacklist = [os.path.join(source_folder, f) for f in blacklist]

    v_sources = []
    # Iterate over the contents of the source folder
    for item in os.listdir(source_folder):
        source_item = os.path.join(source_folder, item)

        if source_item in blacklist or not os.path.isfile(source_item) or not source_item.endswith(".v"):
            continue

        v_sources.append(os.path.abspath(source_item))
    return v_sources
