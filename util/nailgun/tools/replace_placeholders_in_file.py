import re
import sys

def replace_placeholders_in_file(file_path, replacements_dict, output_file_path):
    # Read the content of the file
    with open(file_path, 'r') as file:
        content = file.read()
    
    # Function to replace each placeholder with its corresponding value from the dictionary
    def replace_placeholder(match):
        placeholder = match.group(0)  # Get the matched placeholder
        if placeholder not in replacements_dict:
            print(f"Error: Placeholder '{placeholder}' not found in replacements dictionary.")
            sys.exit(1)  # Exit with error code 1 if placeholder is not found
        return str(replacements_dict[placeholder])  # Replace with the value from the dictionary

    # Use regex to find all occurrences of placeholders (assuming placeholders are in the format <<placeholder>>)
    pattern = r'<<([^>]+)>>'  # This assumes placeholders are in the format <<placeholder>>
    modified_content = re.sub(pattern, replace_placeholder, content)
    
    # Write the modified content back to the output file
    with open(output_file_path, 'w') as output_file:
        output_file.write(modified_content)
    
    print(f"Template file processed and saved as '{output_file_path}'.")
