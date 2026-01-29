#!/usr/bin/env python3
import subprocess
from collections import deque

import error

def run(dir, cmd, err_msg, error_code, output=True, n_lines=100):
    proc = subprocess.Popen(f"cd {dir} && {cmd}", shell=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    output_lines = deque(maxlen=n_lines)  # Store the last N lines

    def proc_line(output_line):
        if output_line:
            decoded_line = output_line.decode("utf-8")
            if (output):
                print(decoded_line, end='')
            else:
                output_lines.append(decoded_line)

    while proc.poll() is None:
        output_line = proc.stdout.readline()
        proc_line(output_line)
    # Ensure any remaining lines are captured
    for output_line in proc.stdout:
        proc_line(output_line)

    if proc.wait() != 0:
        print(f"Running command: '{cmd}' failed")
        if not output:
            print(f"Last {n_lines} lines of the output:")
            for line in output_lines:
                print(line, end='')
        error.exit_error(err_msg, error_code)
