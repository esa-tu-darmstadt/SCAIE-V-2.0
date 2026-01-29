import yaml

def merge_chains(a, b):
    chains = a + b
    # Remove duplicate chains
    unique_chains = [list(t) for t in dict.fromkeys(tuple(lst) for lst in chains)]
    return unique_chains

def export_chains_as_yaml(yaml_path, chains):
    with open(yaml_path, 'w') as f:
        yaml.dump(chains, f, sort_keys=False)
