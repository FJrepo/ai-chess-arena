#!/usr/bin/env python3
"""Print a version's curated CHANGELOG entry as a GitHub Release body."""

import argparse
from pathlib import Path
import posixpath
import re
from urllib.parse import quote, urlsplit, urlunsplit


TITLE = r'''(?:"(?:\\.|[^"\\\n])*"|'(?:\\.|[^'\\\n])*'|\((?:\\.|[^)\\\n])*\))'''


def markdown_lines(markdown):
    """Yield lines with a flag protecting fenced code, including its delimiters."""
    fence = None
    for line in markdown.splitlines(keepends=True):
        marker = re.match(r'^ {0,3}(`{3,}|~{3,})(.*)$', line.rstrip('\r\n'))
        if fence:
            yield line, True
            if (marker and marker[1][0] == fence[0] and len(marker[1]) >= len(fence)
                    and not marker[2].strip()):
                fence = None
        elif marker and (marker[1][0] == '~' or '`' not in marker[2]):
            fence = marker[1]
            yield line, True
        else:
            yield line, False


def reference_label(label):
    return ' '.join(re.sub(r'\\([!"#$%&\'()*+,\-./:;<=>?@\[\]\\^_`{|}~])', r'\1', label)
                    .split()).casefold()


def destination_end(text, start):
    """Read a Markdown destination, allowing angle brackets and balanced parens."""
    if start < len(text) and text[start] == '<':
        match = re.match(r'<(?:\\.|[^<>\n\\])*>', text[start:])
        return start + len(match[0]) if match else start
    depth = 0
    index = start
    while index < len(text):
        char = text[index]
        if char == '\\' and index + 1 < len(text):
            index += 2
            continue
        if char.isspace() or (char == ')' and depth == 0):
            break
        if char == '(':
            depth += 1
        elif char == ')':
            depth -= 1
        index += 1
    return index if depth == 0 else start


def collect_definitions(changelog):
    """Collect global definitions and return the remaining fence-annotated lines."""
    lines = list(markdown_lines(changelog))
    definitions = {}
    body = []
    index = 0
    while index < len(lines):
        line, code = lines[index]
        match = None if code else re.match(r'^ {0,3}\[([^\]\n]+)\]:[ \t]*', line)
        if match:
            start = match.end()
            count = 1
            # Markdown permits the destination and title on following lines.
            if not line[start:].strip() and index + 1 < len(lines) and not lines[index + 1][1]:
                line += lines[index + 1][0]
                start += re.match(r'\r?\n[ \t]*', line[start:]).end()
                count += 1
            end = destination_end(line, start)
            suffix = line[end:].rstrip('\r\n')
            if end > start and (not suffix.strip() or re.fullmatch(r'[ \t]+' + TITLE + r'[ \t]*', suffix)):
                if not suffix.strip() and index + count < len(lines) and not lines[index + count][1]:
                    title = lines[index + count][0].rstrip('\r\n')
                    if re.fullmatch(r'[ \t]*' + TITLE + r'[ \t]*', title):
                        suffix = '\n' + title
                        count += 1
                definitions.setdefault(reference_label(match[1]), (match[1], line[start:end], suffix))
                index += count
                continue
        body.append(lines[index])
        index += 1
    return definitions, body


def pinned_destination(destination, repository, tag, image=False):
    angled = destination.startswith('<') and destination.endswith('>')
    url = destination[1:-1] if angled else destination
    url = re.sub(r'\\([!"#$%&\'()*+,\-./:;<=>?@\[\]\\^_`{|}~])', r'\1', url)
    parts = urlsplit(url)
    if not url or url.startswith('#') or parts.scheme or parts.netloc:
        return destination
    path = quote(posixpath.normpath('/' + parts.path).lstrip('/'), safe='/%:@!$&\'()*+,;=-._~')
    kind = 'raw' if image else 'blob'
    url = urlunsplit(('https', 'github.com', f'/{repository}/{kind}/{tag}/{path}',
                      parts.query, parts.fragment))
    return f'<{url}>' if angled else url


def closing_bracket(text, start):
    depth = 1
    index = start + 1
    while index < len(text):
        if text[index] == '\\':
            index += 2
            continue
        if text[index] == '[':
            depth += 1
        elif text[index] == ']':
            depth -= 1
            if depth == 0:
                return index
        index += 1
    return None


def rewrite_links(markdown, definitions, repository, tag):
    """Rewrite prose links and append only used definitions; leave code untouched."""
    used = {}
    # A reference shared by a link and an image needs separate blob/raw targets.
    image_labels = {}

    def image_label(label):
        if label not in image_labels:
            candidate = f'{label}-release-image'
            while reference_label(candidate) in definitions or candidate in image_labels.values():
                candidate += '-image'
            image_labels[label] = candidate
        return image_labels[label]

    def rewrite_prose(text):
        output = []
        index = 0
        while index < len(text):
            if text[index] == '\\':
                output.append(text[index:index + 2])
                index += 2
                continue
            if text[index] == '`':
                ticks = re.match(r'`+', text[index:])[0]
                closing = re.search(r'(?<!`)' + ticks + r'(?!`)', text[index + len(ticks):])
                end = index + len(ticks) + closing.end() if closing else index + len(ticks)
                output.append(text[index:end])
                index = end
                continue
            image = text.startswith('![', index)
            start = index + 1 if image else index
            if text[start:start + 1] != '[' or (end := closing_bracket(text, start)) is None:
                output.append(text[index])
                index += 1
                continue
            label_text = text[start + 1:end]
            after = end + 1
            if text[after:after + 1] == '(':
                dest_start = after + 1
                while dest_start < len(text) and text[dest_start].isspace():
                    dest_start += 1
                dest_end = destination_end(text, dest_start)
                tail = re.match(r'\s*(?:' + TITLE + r'\s*)?\)', text[dest_end:])
                if tail:
                    output.append(('!' if image else '') + '[' + rewrite_prose(label_text) + ']'
                                  + text[after:dest_start]
                                  + pinned_destination(text[dest_start:dest_end], repository, tag, image)
                                  + tail[0])
                    index = dest_end + len(tail[0])
                    continue
            label = label_text
            if text[after:after + 1] == '[':
                ref_end = closing_bracket(text, after)
                if ref_end is not None:
                    label = text[after + 1:ref_end] or label_text
                    after = ref_end + 1
            key = reference_label(label)
            if key in definitions:
                target = image_label(key) if image else key
                used[target] = (key, image)
                if image:
                    output.append(f'![{label_text}][{target}]')
                else:
                    output.append(text[index:after])
            else:
                output.append(text[index:after])
            index = after
        return ''.join(output)

    output = []
    prose = []
    for line, code in markdown_lines(markdown):
        if code:
            output.append(rewrite_prose(''.join(prose)))
            prose = []
            output.append(line)
        else:
            prose.append(line)
    output.append(rewrite_prose(''.join(prose)))
    notes = ''.join(output).rstrip('\r\n')
    for label, (key, image) in used.items():
        original_label, destination, suffix = definitions[key]
        notes += ('\n\n[' + (label if image else original_label) + ']: '
                  + pinned_destination(destination, repository, tag, image) + suffix.rstrip())
    return notes + '\n'


def extract_release_notes(version, changelog, repository):
    """Extract a release with links pinned to its vX.Y.Z tag in owner/repo."""
    version = version.removeprefix('v')
    if not re.fullmatch(r'[0-9]+\.[0-9]+\.[0-9]+', version):
        raise ValueError('Version must be X.Y.Z or vX.Y.Z')

    if not re.fullmatch(r'[A-Za-z0-9][A-Za-z0-9_.-]*/[A-Za-z0-9][A-Za-z0-9_.-]*', repository):
        raise ValueError('Repository must be owner/repo')

    definitions, lines = collect_definitions(changelog)
    body = []
    selected = False
    found = False
    for line, code in lines:
        if not code:
            if re.match(r'^##[ \t]+\[', line):
                selected = bool(re.fullmatch(
                    rf'## \[{re.escape(version)}\](?:[ \t]+-[ \t]+[^\n]+)?[ \t]*\n?', line))
                # Only the first matching entry is selected, but scan all definitions.
                selected = selected and not found
                found = found or selected
                continue
        if selected:
            body.append((line, code))
    if not found:
        raise ValueError(f'No CHANGELOG section found for version {version}')

    sections = [('Highlights', [])]
    for line, code in body:
        heading = None if code else re.match(r'^###[ \t]+([^\n]+)', line)
        if heading:
            sections.append((heading[1].strip(), []))
        else:
            if not code:
                line = re.sub(r'^(#{4,6}) ', lambda match: match[1][1:] + ' ', line)
            sections[-1][1].append(line)

    # Keep Highlights first, then move only Upgrade notes ahead of other sections.
    intro, *sections = sections
    sections.sort(key=lambda section: section[0].strip() != 'Upgrade notes')
    blocks = []
    for title, content in [intro, *sections]:
        content = ''.join(content).strip('\r\n')
        if content.strip() or title != 'Highlights':
            blocks.append(f'## {title}\n\n{content}'.rstrip('\r\n'))
    return rewrite_links('\n\n'.join(blocks) + '\n', definitions, repository, f'v{version}')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('version', help='release tag, with or without the v prefix')
    parser.add_argument('changelog', type=Path, help='path to CHANGELOG.md at the repository root')
    parser.add_argument('--repository', required=True, help='GitHub owner/repo for tag-pinned links')
    args = parser.parse_args()
    try:
        notes = extract_release_notes(args.version, args.changelog.read_text(encoding='utf-8'),
                                      args.repository)
    except (OSError, UnicodeError, ValueError) as error:
        parser.error(str(error))
    print(notes, end='')


if __name__ == '__main__':
    main()
