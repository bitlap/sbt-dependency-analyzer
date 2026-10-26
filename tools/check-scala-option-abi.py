#!/usr/bin/env python3
"""Reject Option.orNull() bytecode calls that the IDE's Scala 2.13 runtime cannot link.

Inspect JVM method references rather than source text, so compiler-generated calls and stale packaged
classes are covered. Accept compiled class directories or plugin JARs as command-line arguments.
"""
from pathlib import Path
import struct
import sys
import zipfile


def incompatible_calls(data):
    if data[:4] != b"\xca\xfe\xba\xbe":
        raise ValueError("Not a class file")
    count = struct.unpack_from(">H", data, 8)[0]
    pool = [None] * count
    offset, index = 10, 1
    while index < count:
        tag = data[offset]
        offset += 1
        if tag == 1:
            length = struct.unpack_from(">H", data, offset)[0]
            offset += 2
            pool[index] = (tag, data[offset:offset + length].decode("utf-8", errors="replace"))
            offset += length
        elif tag in (7, 8, 16, 19, 20):
            pool[index] = (tag, struct.unpack_from(">H", data, offset)[0])
            offset += 2
        elif tag in (9, 10, 11, 12, 17, 18):
            pool[index] = (tag, *struct.unpack_from(">HH", data, offset))
            offset += 4
        elif tag in (3, 4):
            offset += 4
        elif tag in (5, 6):
            offset += 8
            index += 1
        elif tag == 15:
            offset += 3
        else:
            raise ValueError(f"Unknown constant-pool tag {tag}")
        index += 1
    for entry in pool:
        if entry and entry[0] in (10, 11):
            owner = pool[pool[entry[1]][1]][1]
            name_and_type = pool[entry[2]]
            name = pool[name_and_type[1]][1]
            signature = pool[name_and_type[2]][1]
            if owner == "scala/Option" and name == "orNull" and signature == "()Ljava/lang/Object;":
                yield f"{owner}.{name}{signature}"


def classes(path):
    if path.is_dir():
        for file in sorted((path / "bitlap").rglob("*.class")):
            yield str(file), file.read_bytes()
    else:
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                if name.startswith("bitlap/") and name.endswith(".class"):
                    yield f"{path}!/{name}", archive.read(name)


def main(paths):
    failures = []
    for path in paths:
        count = 0
        for name, data in classes(Path(path)):
            count += 1
            failures.extend(f"{name}: {call}" for call in incompatible_calls(data))
        if not count:
            raise ValueError(f"No plugin classes found in {path}")
        print(f"Scanned {count} plugin classes in {path}")
    if failures:
        sys.exit("Incompatible Scala runtime calls:\n" + "\n".join(failures))
    print("PASS no unsupported scala.Option.orNull() references")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("Usage: check-scala-option-abi.py <class-directory-or-jar> ...")
    main(sys.argv[1:])
