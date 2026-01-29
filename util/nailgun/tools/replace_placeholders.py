import json

def replace_placeholders(template_file, output_file, values):
    # Load the template JSON file
    with open(template_file, 'r') as file:
        data = json.load(file)
    
    # Replace placeholders in the JSON data
    def replace_values(obj):
        if isinstance(obj, dict):
            return {k: replace_values(v) for k, v in obj.items()}
        elif isinstance(obj, list):
            return [replace_values(i) for i in obj]
        elif isinstance(obj, str) and obj in values:
            return values[obj]  # Replace with value from values dictionary
        return obj

    updated_data = replace_values(data)
    
    # Write the updated data to the output JSON file
    with open(output_file, 'w') as file:
        json.dump(updated_data, file, indent=2)
