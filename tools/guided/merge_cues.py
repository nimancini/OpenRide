"""Merge single-class cue sheets into the app's catalog, newest class first.

Usage: merge_cues.py CLASS.json [CLASS.json ...]
Each file is one class object (an element of "classes"). A class already in the catalog is
replaced. Writes app/src/main/assets/guided/catalog.json with one cue per line, so a diff of a
re-timed sheet reads cue by cue.
"""
import json
import sys
from pathlib import Path

CATALOG = Path(__file__).resolve().parents[2] / "app/src/main/assets/guided/catalog.json"


def dump(catalog):
    out = ['{', f'  "version": {catalog.get("version", 1)},', '  "classes": [']
    for i, c in enumerate(catalog["classes"]):
        head = {k: v for k, v in c.items() if k != "cues"}
        out.append("    {")
        out += [f"      {json.dumps(k)}: {json.dumps(v, ensure_ascii=False)}," for k, v in head.items()]
        out.append('      "cues": [')
        cues = [f"        {json.dumps(q, ensure_ascii=False, separators=(', ', ': '))}" for q in c["cues"]]
        out.append(",\n".join(cues))
        out.append("      ]")
        out.append("    }" + ("," if i < len(catalog["classes"]) - 1 else ""))
    out += ["  ]", "}"]
    return "\n".join(out) + "\n"


def main(paths):
    catalog = json.loads(CATALOG.read_text())
    by_id = {c["id"]: c for c in catalog["classes"]}
    for p in paths:
        c = json.loads(Path(p).read_text())
        by_id[c["id"]] = c
    catalog["classes"] = sorted(by_id.values(), key=lambda c: -c.get("publishedEpochMs", 0))
    CATALOG.write_text(dump(catalog))
    assert json.loads(CATALOG.read_text()) == catalog  # the hand-rolled layout is still the same JSON
    print(f"{len(catalog['classes'])} classes in {CATALOG}")


if __name__ == "__main__":
    main(sys.argv[1:])
