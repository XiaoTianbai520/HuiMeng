"""Compare captured, parsed protocol events and downloaded-file byte digests.

No endpoint is contacted. Only explicitly named JSON pointers may be ignored.
"""
import argparse
import copy
import json
from pathlib import Path


def normalize(recording, ignored):
    result = copy.deepcopy(recording)
    for pointer in ignored:
        if not pointer.startswith('/'):
            raise ValueError('ignored fields must be absolute JSON pointers')
        segments = [part.replace('~1', '/').replace('~0', '~') for part in pointer[1:].split('/')]
        node = result
        for part in segments[:-1]:
            node = node[int(part)] if isinstance(node, list) else node[part]
        last = int(segments[-1]) if isinstance(node, list) else segments[-1]
        node[last] = '<explicitly normalized>'
    return result


def compare(baseline, candidate, ignored=()):
    # Array order, business error codes, event terminators and file SHA-256 are significant.
    return normalize(baseline, ignored) == normalize(candidate, ignored)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('baseline', type=Path)
    parser.add_argument('candidate', type=Path)
    parser.add_argument('--ignore', action='append', default=[])
    args = parser.parse_args()
    baseline = json.loads(args.baseline.read_text(encoding='utf-8-sig'))
    candidate = json.loads(args.candidate.read_text(encoding='utf-8-sig'))
    passed = compare(baseline, candidate, args.ignore)
    print(json.dumps({'passed': passed, 'baseline': str(args.baseline), 'candidate': str(args.candidate),
                      'normalizedPointers': args.ignore}, ensure_ascii=False))
    return 0 if passed else 1


if __name__ == '__main__':
    raise SystemExit(main())
