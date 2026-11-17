#!/usr/bin/env python3
"""Prepare the two setup assets before creating an immutable GitHub Release."""

import argparse
from pathlib import Path
import re


def release_compose(compose, tag):
    """Change only the two required image tags into release-specific defaults."""
    if not re.fullmatch(r'v[0-9]+\.[0-9]+\.[0-9]+', tag):
        raise ValueError('Release tag must match vX.Y.Z exactly')
    tags = re.findall(r'\$\{IMAGE_TAG[^}]*\}', compose)
    if len(tags) != 2 or any(not value.startswith('${IMAGE_TAG:?') for value in tags):
        raise ValueError('Expected exactly two required IMAGE_TAG expressions')
    return re.sub(r'\$\{IMAGE_TAG:\?[^}]*\}', lambda _: '${IMAGE_TAG:-' + tag + '}', compose)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('tag', help='Exact release tag, for example v1.5.1')
    parser.add_argument('--compose', type=Path, default=Path('docker-compose.prod.yml'))
    parser.add_argument('--env-example', type=Path, default=Path('.env.example'))
    parser.add_argument('--output-dir', type=Path, default=Path('release-assets'))
    args = parser.parse_args()
    try:
        compose = release_compose(args.compose.read_bytes().decode('utf-8'), args.tag)
        env_example = args.env_example.read_bytes()
    except (OSError, ValueError) as error:
        parser.exit(1, f'{error}\n')
    args.output_dir.mkdir(parents=True, exist_ok=True)
    (args.output_dir / 'docker-compose.yml').write_bytes(compose.encode('utf-8'))
    (args.output_dir / '.env.example').write_bytes(env_example)


if __name__ == '__main__':
    main()
