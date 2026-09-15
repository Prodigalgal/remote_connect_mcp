#!/usr/bin/env python3
"""Normalize the x86 GNU ISA-needed note emitted by Native Image.

Some GraalVM linkers copy the build host ISA into the ELF GNU property note
even when ``-march`` selects a portable target.  Older x86-64 hosts then
reject the executable before ``main`` runs.  This script changes only the
GNU_PROPERTY_X86_ISA_1_NEEDED value; all other ELF bytes and GNU properties
are left untouched.
"""

from __future__ import annotations

import argparse
import struct
import sys
from pathlib import Path


ELF_MAGIC = b"\x7fELF"
ELFCLASS64 = 2
ELFDATA2LSB = 1
EM_X86_64 = 62
PT_NOTE = 4
NT_GNU_PROPERTY_TYPE_0 = 5
GNU_PROPERTY_X86_ISA_1_NEEDED = 0xC0008002
X86_64_V2_MASK = 0x3


def align(value: int, boundary: int) -> int:
    return (value + boundary - 1) & ~(boundary - 1)


def isa_properties(data: bytes) -> tuple[int, list[tuple[int, int]]]:
    if len(data) < 64 or data[:4] != ELF_MAGIC:
        raise ValueError("not an ELF file")
    if data[4] != ELFCLASS64 or data[5] != ELFDATA2LSB:
        raise ValueError("only little-endian ELF64 is supported")

    # Elf64_Ehdr fields from e_type through e_shstrndx.
    header = struct.unpack_from("<HHIQQQIHHHHHH", data, 16)
    machine = header[1]
    phoff = header[4]
    phentsize = header[8]
    phnum = header[9]
    if phentsize < 56:
        raise ValueError("invalid ELF program-header size")
    if phoff + phentsize * phnum > len(data):
        raise ValueError("ELF program headers exceed file size")

    properties: list[tuple[int, int]] = []
    for index in range(phnum):
        offset = phoff + index * phentsize
        p_type, _, p_offset, _, _, p_filesz, _, _ = struct.unpack_from(
            "<IIQQQQQQ", data, offset
        )
        if p_type != PT_NOTE:
            continue
        end = p_offset + p_filesz
        if end > len(data):
            raise ValueError("ELF note segment exceeds file size")
        cursor = p_offset
        while cursor + 12 <= end:
            namesz, descsz, note_type = struct.unpack_from("<III", data, cursor)
            name_start = cursor + 12
            name_end = name_start + namesz
            desc_start = cursor + 12 + align(namesz, 4)
            desc_end = desc_start + descsz
            if name_end > end or desc_end > end:
                raise ValueError("truncated ELF note")
            name = data[name_start:name_end].rstrip(b"\0")
            if name == b"GNU" and note_type == NT_GNU_PROPERTY_TYPE_0:
                property_cursor = desc_start
                property_alignment = 8
                while property_cursor + 8 <= desc_end:
                    property_type, property_size = struct.unpack_from(
                        "<II", data, property_cursor
                    )
                    value_start = property_cursor + 8
                    value_end = value_start + property_size
                    if value_end > desc_end:
                        raise ValueError("truncated GNU property")
                    if (
                        property_type == GNU_PROPERTY_X86_ISA_1_NEEDED
                        and property_size >= 4
                    ):
                        value = struct.unpack_from("<I", data, value_start)[0]
                        properties.append((value_start, value))
                    property_cursor += align(8 + property_size, property_alignment)
            cursor = desc_start + align(descsz, 4)
    return machine, properties


def process(path: Path, mode: str) -> bool:
    data = bytearray(path.read_bytes())
    machine, properties = isa_properties(data)
    if machine != EM_X86_64:
        print(f"{path}: non-x86-64 ELF; no ISA note change needed")
        return True

    incompatible = [(offset, value) for offset, value in properties if value > X86_64_V2_MASK]
    if mode == "check":
        if incompatible:
            values = ", ".join(f"0x{value:x}" for _, value in incompatible)
            print(f"{path}: incompatible GNU ISA-needed value(s): {values}", file=sys.stderr)
            return False
        values = ", ".join(f"0x{value:x}" for _, value in properties) or "none"
        print(f"{path}: GNU ISA-needed value(s) {values}; <= x86-64-v2")
        return True

    changed = False
    for offset, value in incompatible:
        struct.pack_into("<I", data, offset, X86_64_V2_MASK)
        changed = True
    if changed:
        path.write_bytes(data)
        print(f"{path}: normalized {len(incompatible)} GNU ISA-needed value(s) to 0x3")
    else:
        print(f"{path}: GNU ISA-needed note already portable")
    return True


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    group = parser.add_mutually_exclusive_group(required=True)
    group.add_argument("--normalize", action="store_true", help="rewrite values above x86-64-v2")
    group.add_argument("--check", action="store_true", help="fail when a value above x86-64-v2 is present")
    parser.add_argument("paths", nargs="+", type=Path)
    args = parser.parse_args()
    mode = "normalize" if args.normalize else "check"
    success = True
    for path in args.paths:
        try:
            success = process(path, mode) and success
        except (OSError, ValueError, struct.error) as exc:
            print(f"{path}: {exc}", file=sys.stderr)
            success = False
    return 0 if success else 1


if __name__ == "__main__":
    raise SystemExit(main())
