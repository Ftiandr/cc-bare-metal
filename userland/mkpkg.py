#!/usr/bin/env python3
"""Build a .ccpkg (same format as 'pkg build'): mkpkg.py MANIFEST.json OUT.ccpkg [path=source ...]"""
import json
import sys

manifest_path, out_path, *files = sys.argv[1:]
manifest = json.load(open(manifest_path))
with open(out_path, "wb") as out:
    out.write(b"CCPKG1\n" + json.dumps(manifest).encode() + b"\n")
    dirs = set()
    for spec in files:
        dest, src = spec.split("=", 1)
        parts = dest.split("/")[:-1]
        for i in range(1, len(parts) + 1):
            d = "/".join(parts[:i])
            if d not in dirs:
                dirs.add(d)
                out.write(b"D " + d.encode() + b"\n")
        data = open(src, "rb").read()
        out.write(b"F %s %d\n" % (dest.encode(), len(data)) + data)
    out.write(b"E\n")
