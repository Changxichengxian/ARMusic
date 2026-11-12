"""Prepare a signed ARMusic APK and its delta assets for a GitHub release."""

import argparse
import gzip
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import struct
import subprocess
import zipfile


MAGIC = b"ARD1"
COPY = 0
LITERAL = 1
BLOCK_SIZE = 16 * 1024
APK_LIMIT = 256 * 1024 * 1024
PACKAGE_NAME = "com.armusic"


def apk_info(path, build_tools):
    aapt = build_tools / ("aapt.exe" if os.name == "nt" else "aapt")
    badging = subprocess.check_output([str(aapt), "dump", "badging", str(path)])
    package = badging.splitlines()[0].decode("ascii", errors="replace")
    match = re.match(
        r"package: name='([^']+)' versionCode='([0-9]+)' versionName='([0-9]+\.[0-9]+\.[0-9]+)'",
        package,
    )
    if match is None or match.group(1) != PACKAGE_NAME:
        raise ValueError(f"Expected a stable {PACKAGE_NAME} APK: {path}")

    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin" / "java") if java_home else "java"
    verified = subprocess.run(
        [java, "-jar", str(build_tools / "lib" / "apksigner.jar"),
         "verify", "--print-certs", str(path)],
        check=True,
        capture_output=True,
        text=True,
    )
    signers = frozenset(re.findall(
        r"^Signer #\d+ certificate SHA-256 digest: ([a-f0-9]{64})$",
        verified.stdout,
        re.MULTILINE,
    ))
    if not signers:
        raise ValueError(f"Could not determine APK signing certificate: {path}")
    return int(match.group(2)), match.group(3), signers


def apk_blocks(path, data):
    """Align blocks to each ZIP entry's payload, even when entry offsets move."""
    with zipfile.ZipFile(path) as archive:
        entries = sorted(archive.infolist(), key=lambda entry: entry.header_offset)
    position = 0
    for entry in entries:
        header = entry.header_offset
        name_size, extra_size = struct.unpack_from("<HH", data, header + 26)
        payload = header + 30 + name_size + extra_size
        for start in range(position, header, BLOCK_SIZE):
            yield start, data[start:min(header, start + BLOCK_SIZE)]
        yield header, data[header:payload]
        position = payload + entry.compress_size
        for start in range(payload, position, BLOCK_SIZE):
            yield start, data[start:min(position, start + BLOCK_SIZE)]
    for start in range(position, len(data), BLOCK_SIZE):
        yield start, data[start:start + BLOCK_SIZE]


def write_delta(base_path, base, target_path, target, destination):
    offsets = {block: offset for offset, block in apk_blocks(base_path, base)}
    literal = bytearray()
    copy_offset = 0
    copy_size = 0
    with destination.open("wb") as file, gzip.GzipFile(
        fileobj=file, filename="", mode="wb", mtime=0
    ) as patch:
        patch.write(MAGIC)
        for _, block in apk_blocks(target_path, target):
            offset = offsets.get(block)
            if offset is not None:
                if literal:
                    patch.write(struct.pack(">BI", LITERAL, len(literal)))
                    patch.write(literal)
                    literal.clear()
                if copy_size and offset != copy_offset + copy_size:
                    patch.write(struct.pack(">BII", COPY, copy_offset, copy_size))
                    copy_size = 0
                if not copy_size:
                    copy_offset = offset
                copy_size += len(block)
            else:
                if copy_size:
                    patch.write(struct.pack(">BII", COPY, copy_offset, copy_size))
                    copy_size = 0
                literal.extend(block)
        if copy_size:
            patch.write(struct.pack(">BII", COPY, copy_offset, copy_size))
        if literal:
            patch.write(struct.pack(">BI", LITERAL, len(literal)))
            patch.write(literal)


def verify_delta(base, target, path):
    offset = 0
    with gzip.open(path, "rb") as patch:
        if patch.read(4) != MAGIC:
            raise ValueError("Invalid patch header")
        while operation := patch.read(1):
            if operation == bytes([COPY]):
                start, length = struct.unpack(">II", patch.read(8))
                block = base[start:start + length]
            elif operation == bytes([LITERAL]):
                length, = struct.unpack(">I", patch.read(4))
                block = patch.read(length)
            else:
                raise ValueError("Unknown patch operation")
            if not length or len(block) != length or block != target[offset:offset + length]:
                raise ValueError("Patch did not reconstruct the signed APK")
            offset += length
    if offset != len(target):
        raise ValueError("Patch reconstructed an incomplete APK")


def build_assets(args):
    code, version, signers = apk_info(args.apk, args.build_tools)
    if not 0 < args.apk.stat().st_size <= APK_LIMIT:
        raise ValueError("APK exceeds the supported update size")
    target = args.apk.read_bytes()
    args.output.mkdir(parents=True, exist_ok=True)
    name = f"ARMusic-Android-{version}"
    apk = args.output / f"{name}.apk"
    if args.apk.resolve() != apk.resolve():
        shutil.copyfile(args.apk, apk)
    assets = [apk]
    patches = {}
    for previous in args.previous:
        previous_code, _, previous_signers = apk_info(previous, args.build_tools)
        if previous_code >= code or previous_signers != signers:
            raise ValueError(f"Base APK must be older and use the same certificate: {previous}")
        if not 0 < previous.stat().st_size <= APK_LIMIT:
            raise ValueError(f"Base APK exceeds the supported update size: {previous}")
        base = previous.read_bytes()
        base_hash = hashlib.sha256(base).hexdigest()
        if base_hash in patches:
            continue
        patch = args.output / f"{name}-from-{base_hash}.ard.gz"
        write_delta(previous, base, apk, target, patch)
        verify_delta(base, target, patch)
        size = patch.stat().st_size
        if size >= len(target):
            patch.unlink()
            print(f"No smaller patch for {previous.name}")
            continue
        with patch.open("rb") as file:
            patches[base_hash] = {
                "sha256": hashlib.file_digest(file, "sha256").hexdigest(),
                "size": size,
            }
        assets.append(patch)
        print(f"{previous.name} -> {apk.name}: {size:,} / {len(target):,} bytes")

    manifest = args.output / "armusic-android-update.json"
    manifest.write_text(json.dumps({
        "schema": 1,
        "packageName": PACKAGE_NAME,
        "versionName": version,
        "versionCode": code,
        "apkSize": len(target),
        "apkSha256": hashlib.sha256(target).hexdigest(),
        "patches": patches,
    }, indent=2) + "\n", encoding="utf-8")
    return [*assets, manifest]


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", required=True, type=Path)
    parser.add_argument("--previous", action="append", default=[], type=Path)
    parser.add_argument("--build-tools", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    for asset in build_assets(parser.parse_args()):
        print(asset)
