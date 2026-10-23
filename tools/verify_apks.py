#!/usr/bin/env python3
"""Build-time guard: proprietary engine/input and compile-only Bedrock definitions cannot ship."""
from pathlib import Path
import struct
import sys
import zipfile


def defined_classes(data):
    if not data.startswith(b'dex\n') or len(data) < 112:
        raise ValueError('Not a standard DEX')
    strings = struct.unpack_from('<I', data, 60)[0]
    types = struct.unpack_from('<I', data, 68)[0]
    count, classes = struct.unpack_from('<II', data, 96)
    if count > 100000 or classes + count * 32 > len(data):
        raise ValueError('Invalid class table')
    for i in range(count):
        type_id = struct.unpack_from('<I', data, classes + i * 32)[0]
        string_id = struct.unpack_from('<I', data, types + type_id * 4)[0]
        offset = struct.unpack_from('<I', data, strings + string_id * 4)[0]
        for _ in range(5):
            byte = data[offset]; offset += 1
            if not byte & 128:
                break
        else:
            raise ValueError('Invalid string length')
        end = data.index(b'\0', offset)
        yield data[offset:end].decode('utf-8', errors='replace')


def verify(apk):
    with zipfile.ZipFile(apk) as z:
        for entry in z.infolist():
            assert 'libminecraftpe' not in entry.filename.lower() and not entry.filename.lower().endswith('.7z'), (apk, entry.filename)
            if entry.filename.endswith('.dex'):
                assert entry.file_size < 128 * 1024 * 1024
                for name in defined_classes(z.read(entry)):
                    assert not name.startswith(('Lcom/mojang/', 'Lcom/google/androidgamesdk/')), (apk, 'Proprietary/compile-only class shipped', name)
    print(apk.name + ': no engine/archive or Minecraft/GameActivity class definitions')


if __name__ == '__main__':
    for apk in Path(sys.argv[1]).glob('*.apk'):
        verify(apk)
