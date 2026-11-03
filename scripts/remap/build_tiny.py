#!/usr/bin/env python3
"""Convert docs/mapping/*.map (see docs/mapping/FORMAT.md) into a Tiny v2 mapping.

Every game class is moved into package `g3`; classes without a `class` entry keep
their obfuscated simple name (g3/A, ...).

usage: build_tiny.py <mapping-dir> <out.tiny> <ClassName>...
"""
import sys
from pathlib import Path

PACKAGE = "g3"


def parse(mapping_dir):
    classes, fields, methods = {}, {}, {}
    for path in sorted(Path(mapping_dir).glob("*.map")):
        for lineno, raw in enumerate(path.read_text().splitlines(), 1):
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            parts = line.split()
            where = f"{path.name}:{lineno}"
            kind = parts[0]
            if kind == "class" and len(parts) == 3:
                classes[parts[1]] = parts[2]
            elif kind == "field" and len(parts) == 5:
                fields.setdefault(parts[1], []).append((parts[2], parts[3], parts[4]))
            elif kind == "method" and len(parts) == 5:
                methods.setdefault(parts[1], []).append((parts[2], parts[3], parts[4]))
            else:
                sys.exit(f"{where}: cannot parse: {raw!r}")
    return classes, fields, methods


def main():
    mapping_dir, out, *all_classes = sys.argv[1:]
    classes, fields, methods = parse(mapping_dir)
    unknown = set(classes) | set(fields) | set(methods)
    unknown -= set(all_classes)
    if unknown:
        sys.exit(f"mapping refers to unknown classes: {sorted(unknown)}")
    lines = ["tiny\t2\t0\tofficial\tnamed"]
    for obf in all_classes:
        lines.append(f"c\t{obf}\t{PACKAGE}/{classes.get(obf, obf)}")
        for name, desc, new in fields.get(obf, []):
            lines.append(f"\tf\t{desc}\t{name}\t{new}")
        for name, desc, new in methods.get(obf, []):
            lines.append(f"\tm\t{desc}\t{name}\t{new}")
    Path(out).write_text("\n".join(lines) + "\n")
    n_f = sum(map(len, fields.values()))
    n_m = sum(map(len, methods.values()))
    print(f"{out}: {len(all_classes)} classes ({len(classes)} renamed), {n_f} fields, {n_m} methods")


if __name__ == "__main__":
    main()
