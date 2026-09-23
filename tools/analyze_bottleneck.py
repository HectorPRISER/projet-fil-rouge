#!/usr/bin/env python3
"""Quantifie la part du CPU dans le parsing/formatage hex vs le SHA-256 reel.
Usage: python3 tools/analyze_bottleneck.py profiles/cpu.jfr
"""
import sys
from collections import Counter

sys.path.insert(0, __file__.rsplit("/", 1)[0])
from flamegraph import parse_stacks

HEX_MARKERS = ("Formatter", "Matcher", "Pattern$")
DIGEST_MARKERS = ("sun.security.provider",)


def main():
    if len(sys.argv) != 2:
        print("Usage: analyze_bottleneck.py <fichier.jfr>", file=sys.stderr)
        sys.exit(1)

    stacks = parse_stacks(sys.argv[1])
    total = len(stacks)
    hex_related = sum(1 for s in stacks if any(any(m in f for m in HEX_MARKERS) for f in s))
    digest_real = sum(1 for s in stacks if any(any(m in f for m in DIGEST_MARKERS) for f in s))

    print(f"Echantillons totaux                    : {total}")
    print(f"Dans le parsing/formatage hex (gaspille) : {hex_related} ({100 * hex_related / total:.1f}%)")
    print(f"Dans le calcul SHA-256 reel (utile)      : {digest_real} ({100 * digest_real / total:.2f}%)")

    print("\nTop frames feuilles (ou le temps CPU est reellement passe) :")
    leaf_counter = Counter(s[-1] for s in stacks)
    for name, count in leaf_counter.most_common(10):
        print(f"  {100 * count / total:5.1f}%  {count:5d}  {name}")


if __name__ == "__main__":
    main()
