#!/usr/bin/env python3
"""Bounded, static inspection of the exact retained engine; never execute its code."""
import argparse
import hashlib
import json
from pathlib import Path
from capstone import Cs, CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN
from elftools.elf.elffile import ELFFile

EXPECTED = 'fac2be5b17ef79419ebd66fe4f9e362a48d7b2067d5e1f649e65c36edf2927e1'


def inspect(path):
    with path.open('rb') as stream:
        digest = hashlib.file_digest(stream, 'sha256').hexdigest()
        if digest != EXPECTED:
            raise ValueError('Wrong engine SHA-256; no address/signature guessing')
        stream.seek(0)
        elf = ELFFile(stream)
        segments = [p for p in elf.iter_segments() if p['p_type'] == 'PT_LOAD']
        symbols = list(elf.get_section_by_name('.dynsym').iter_symbols())
        exported = {s['st_value']: s.name for s in symbols if s['st_value']}
        imports = {}
        for section in elf.iter_sections():
            if section['sh_type'] == 'SHT_RELA':
                names = elf.get_section(section['sh_link'])
                for relocation in section.iter_relocations():
                    if relocation['r_info_sym']:
                        imports[relocation['r_offset']] = names.get_symbol(relocation['r_info_sym']).name

        def read(address, size):
            for p in segments:
                if p['p_vaddr'] <= address and address + size <= p['p_vaddr'] + p['p_filesz']:
                    stream.seek(p['p_offset'] + address - p['p_vaddr'])
                    return stream.read(size)
            raise ValueError(f'Unbacked ELF VA {address:#x}')

        def string(address):
            return read(address, 256).split(b'\0', 1)[0].decode('ascii')

        cs = Cs(CS_ARCH_ARM64, CS_MODE_LITTLE_ENDIAN)
        cs.detail = True

        def instructions(address, size):
            return list(cs.disasm(read(address, size), address))

        def resolve(address):
            for _ in range(4):
                ins = instructions(address, 16)
                if ins[0].mnemonic == 'b':
                    address = ins[0].operands[0].imm
                    continue
                if ins[0].mnemonic == 'adrp' and ins[1].mnemonic == 'ldr':
                    got = ins[0].operands[1].imm + ins[1].operands[1].mem.disp
                    if got in imports:
                        return imports[got], address
                return exported.get(address, f'internal@{address:#x}'), address
            raise ValueError('Unexpected branch-thunk depth')

        entry = next(s for s in symbols if s.name == 'JNI_OnLoad')
        address, size = entry['st_value'], entry['st_size']
        if (address, size) != (0x852e0e0, 288):
            raise ValueError('JNI export differs from inspected build')
        calls = []
        for ins in instructions(address, size):
            if ins.mnemonic == 'bl':
                name, target = resolve(ins.operands[0].imm)
                calls.append({'site': hex(ins.address), 'target': hex(target), 'name': name})
        # Selected literal references audited against these exact instruction windows.
        strings = {}
        for name, where in {
            'anchor_class': 0x23b2ac1, 'class_loader_class': 0x2674f04,
            'get_class_loader': 0x26e39cb, 'get_class_loader_signature': 0x2629b29,
            'load_class': 0x23a9ac0, 'load_class_signature': 0x2366f3c,
            'failure_log': 0x2424a6e,
        }.items():
            strings[name] = {'elf_va': hex(where), 'value': string(where)}
        assert strings['anchor_class']['value'] == 'com/mojang/minecraftpe/MainActivity'
        assert strings['get_class_loader_signature']['value'] == '()Ljava/lang/ClassLoader;'
        assert strings['load_class_signature']['value'] == '(Ljava/lang/String;)Ljava/lang/Class;'
        return {
            'library_sha256': digest,
            'method': 'Static disassembly only; addresses are ELF VAs, not process addresses.',
            'jni_onload': {'elf_va': hex(address), 'bytes': size, 'calls': calls},
            'literal_references': strings,
            'audited_windows': [
                {'role': role, 'elf_va': hex(a), 'bytes': n, 'sha256': hashlib.sha256(read(a, n)).hexdigest()}
                for role, a, n in [('JNI_OnLoad', address, size),
                                  ('class-loader helper including cleanup', 0x128db5b0, 0x348),
                                  ('store JavaVM', 0x128db910, 12),
                                  ('signal handler', 0x852e200, 4)]
            ],
            'manual_interpretation': {
                'success_jni_version': '0x00010004 (JNI_VERSION_1_4)',
                'class_loader_failure_return': 0,
                'sigaction_failure_return': 1,
                'signal': 'sigaction(15/SIGTERM, flags=4/SA_SIGINFO, handler=0x852e200/RET)',
                'class_loader_helper': ['JavaVM GetEnv(1.4), AttachCurrentThread fallback',
                                       'PushLocalFrame(128)', 'FindClass(anchor_class)',
                                       'GetObjectClass(anchor jclass)', 'FindClass(java/lang/ClassLoader)',
                                       'GetMethodID(getClassLoader)', 'CallObjectMethod(anchor jclass)',
                                       'NewGlobalRef(loader)', 'GetMethodID(loadClass)',
                                       'PopLocalFrame and exception diagnostics'],
                'not_proven': 'Actual installed Java initializer, reachable initializers, JNI execution, native binding or game startup',
            },
        }


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('library', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    text = json.dumps(inspect(args.library), indent=2) + '\n'
    if args.output:
        args.output.write_text(text)
    else:
        print(text, end='')


if __name__ == '__main__':
    main()
