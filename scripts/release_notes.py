#!/usr/bin/env python3
"""Print a version's curated CHANGELOG entry as a GitHub Release body."""

import argparse
from pathlib import Path
import re


def extract_release_notes(version, changelog):
    """Extract an exact release, keeping Markdown and changelog subsection order."""
    version = version.removeprefix('v')
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
        raise ValueError('Version must be X.Y.Z or vX.Y.Z')

    heading = re.search(
        rf'^## \[{re.escape(version)}\](?:[ \t]+-[ \t]+[^\n]+)?[ \t]*$',
        changelog, re.MULTILINE,
    )
    if heading is None:
        raise ValueError(f'No CHANGELOG section found for version {version}')

    # The final release may be followed by the changelog's link-reference table.
    body = re.split(r'^##[ \t]+|^ {0,3}\[[^\]\n]+\]:',
                    changelog[heading.end():], maxsplit=1, flags=re.MULTILINE)[0]
    parts = re.split(r'^###[ \t]+([^\n]+)\n?', body, flags=re.MULTILINE)
    blocks = []
    if parts[0].strip():
        blocks.append('## Highlights\n\n' + parts[0].strip())

    sections = list(zip(parts[1::2], parts[2::2]))
    # Stable sorting moves only Upgrade notes; Fixed before Changed stays that way.
    sections.sort(key=lambda section: section[0].strip() != 'Upgrade notes')
    for title, content in sections:
        content = re.sub(r'^(#{4,6}) ', lambda match: match[1][1:] + ' ',
                         content.strip(), flags=re.MULTILINE)
        blocks.append(f'## {title.strip()}\n\n{content}'.rstrip())
    return '\n\n'.join(blocks) + '\n'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version', help='release version, with or without the v prefix')
    parser.add_argument('changelog', type=Path, help='path to CHANGELOG.md')
    args = parser.parse_args()
    try:
        notes = extract_release_notes(args.version, args.changelog.read_text(encoding='utf-8'))
    except (OSError, UnicodeError, ValueError) as error:
        parser.error(str(error))
    print(notes, end='')


if __name__ == '__main__':
    main()
