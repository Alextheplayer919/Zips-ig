#!/usr/bin/env python3
"""Bounded static linkage inventory. Never load or execute the engine."""
import argparse
import hashlib
import json
from pathlib import Path
from elftools.elf.elffile import ELFFile

SYSTEM = {'libGLESv2.so', 'libEGL.so', 'liblog.so', 'libdl.so', 'libandroid.so', 'libm.so', 'libc.so'}
EXPORTS = {'JNI_OnLoad', 'ANativeActivity_onCreate',
           'Java_com_mojang_minecraftpe_MainActivity_nativeOnDestroy',
           'Java_com_mojang_minecraftpe_MainActivity_nativeResize'}


def inspect(path):
    expected = json.loads((Path(__file__).resolve().parents[1] / 'docs/ENGINE_INPUT.json').read_text())
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(4 * 1024 * 1024), b''):
            digest.update(block)
    if digest.hexdigest() != expected['library_sha256']:
        raise ValueError('Not the fingerprinted supplied library')
    with path.open('rb') as stream:
        elf = ELFFile(stream)
        dynamic = elf.get_section_by_name('.dynamic')
        needed = [tag.needed for tag in dynamic.iter_tags() if tag.entry.d_tag == 'DT_NEEDED']
        sonames = [tag.soname for tag in dynamic.iter_tags() if tag.entry.d_tag == 'DT_SONAME']
        initializers = [tag.entry.d_val for tag in dynamic.iter_tags() if tag.entry.d_tag == 'DT_INIT_ARRAYSZ']
        symbols = {}
        for symbol in elf.get_section_by_name('.dynsym').iter_symbols():
            if symbol.name in EXPORTS and symbol['st_shndx'] != 'SHN_UNDEF':
                symbols[symbol.name] = {'elf_va': hex(symbol['st_value']), 'bytes': symbol['st_size']}
        return dict(sha256=digest.hexdigest(), bytes=path.stat().st_size,
                    elf_class=elf.elfclass, machine=elf['e_machine'], sonames=sonames,
                    needed=needed, non_system_needed=[name for name in needed if name not in SYSTEM],
                    selected_exports=symbols, init_array_bytes=initializers,
                    boundary='Static export presence is not a callable ABI or successful runtime initialization. '
                    'dlopen executes constructors; our probe does not invoke JNI_OnLoad or engine entrypoints.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('library', type=Path)
    parser.add_argument('--report', type=Path)
    args = parser.parse_args()
    result = json.dumps(inspect(args.library), indent=2) + '\n'
    if args.report:
        args.report.write_text(result)
    else:
        print(result, end='')


if __name__ == '__main__':
    main()
