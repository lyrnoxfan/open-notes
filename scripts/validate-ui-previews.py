#!/usr/bin/env python3
"""Decode our Android bitmap PNGs with stdlib only; reject adb error text and corrupt pixels."""
import struct
import sys
import zlib
from pathlib import Path

NAMES = ('list-light', 'list-dark', 'editor-light', 'editor-dark', 'list-large-narrow', 'editor-large-narrow', 'settings-light', 'settings-dark', 'trash-light', 'trash-dark')

def validate(path):
    data = path.read_bytes()
    if data[:8] != b'\x89PNG\r\n\x1a\n':
        raise ValueError(f'{path}: not a PNG (adb error output is not a screenshot)')
    pos, compressed, width, height, channels, ended = 8, bytearray(), 0, 0, 0, False
    while pos < len(data):
        size = struct.unpack_from('>I', data, pos)[0]
        kind = data[pos + 4:pos + 8]
        body = data[pos + 8:pos + 8 + size]
        crc = struct.unpack_from('>I', data, pos + 8 + size)[0]
        if zlib.crc32(kind + body) & 0xffffffff != crc:
            raise ValueError(f'{path}: invalid PNG CRC')
        if kind == b'IHDR':
            width, height, bits, color, compression, filters, interlace = struct.unpack('>IIBBBBB', body)
            if bits != 8 or color not in (2, 6) or compression or filters or interlace or not (0 < width < 10000 and 0 < height < 10000):
                raise ValueError(f'{path}: unsupported/invalid bitmap encoding')
            channels = {2: 3, 6: 4}[color]
        elif kind == b'IDAT':
            compressed.extend(body)
        elif kind == b'IEND':
            ended = True
            break
        pos += size + 12
    if not ended or not channels:
        raise ValueError(f'{path}: incomplete PNG')
    raw = zlib.decompress(compressed)
    stride = width * channels
    if len(raw) != (stride + 1) * height:
        raise ValueError(f'{path}: decoded pixel length mismatch')
    previous = bytearray(stride)
    # Unfilter every row to exercise actual decoding, not only file signatures.
    for y in range(height):
        offset = y * (stride + 1)
        mode = raw[offset]
        row = bytearray(raw[offset + 1:offset + stride + 1])
        if mode not in range(5):
            raise ValueError(f'{path}: invalid PNG filter')
        for x in range(stride):
            a = row[x - channels] if x >= channels else 0
            b = previous[x]
            c = previous[x - channels] if x >= channels else 0
            if mode == 1: predictor = a
            elif mode == 2: predictor = b
            elif mode == 3: predictor = (a + b) // 2
            elif mode == 4:
                p = a + b - c
                pa, pb, pc = abs(p-a), abs(p-b), abs(p-c)
                predictor = a if pa <= pb and pa <= pc else b if pb <= pc else c
            else: predictor = 0
            row[x] = (row[x] + predictor) & 255
        previous = row
    print(f'{path.name}: decoded {width}x{height}')

if __name__ == '__main__':
    for name in NAMES:
        validate(Path(sys.argv[1]) / (name + '.png'))
