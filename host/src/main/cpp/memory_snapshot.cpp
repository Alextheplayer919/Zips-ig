#include <jni.h>
#include "loaded_identity.h"
#include <link.h>
#include <elf.h>
#include <fcntl.h>
#include <unistd.h>
#include <cerrno>
#include <cstring>
#include <cstdint>
#include <limits>
#include <sstream>
#include <iomanip>
#include <string>
#include <vector>
#include <cstdlib>

namespace {
struct Module {
    std::string path, canonical;
    bool found = false;
    uintptr_t bias = 0, header = 0, note = 0;
    size_t noteSize = 0;
    std::string segments;
};
bool address(uintptr_t bias, ElfW(Addr) relative, uintptr_t* result) {
    if (relative > std::numeric_limits<uintptr_t>::max() - bias) return false;
    *result = bias + relative; return true;
}
std::string canonicalPath(const char* path) {
    char* resolved = realpath(path, nullptr);
    if (!resolved) return path;
    std::string value(resolved); std::free(resolved); return value;
}
int findModule(dl_phdr_info* info, size_t, void* data) {
    auto& module = *static_cast<Module*>(data);
    if (!info->dlpi_name || (module.path != info->dlpi_name && module.canonical != canonicalPath(info->dlpi_name))) return 0;
    module.found = true; module.bias = info->dlpi_addr;
    std::ostringstream segments;
    unsigned count = 0;
    for (ElfW(Half) i = 0; i < info->dlpi_phnum; ++i) {
        const auto& p = info->dlpi_phdr[i]; uintptr_t start = 0, end = 0;
        if (!address(module.bias, p.p_vaddr, &start) || !address(start, p.p_memsz, &end)) continue;
        if (p.p_type == PT_LOAD && count++ < 16) {
            segments << "0x" << std::hex << start << "–0x" << end << " "
                     << ((p.p_flags & PF_R) ? 'r' : '-') << ((p.p_flags & PF_W) ? 'w' : '-')
                     << ((p.p_flags & PF_X) ? 'x' : '-') << " (ELF load segment)\n";
            if (p.p_offset == 0 && p.p_filesz >= 64 && (p.p_flags & PF_R)) module.header = start;
        }
        // Only read a note wholly contained in a readable mapped load segment.
        if (p.p_type == PT_NOTE && p.p_filesz > 0 && p.p_filesz <= 65536) {
            for (ElfW(Half) j = 0; j < info->dlpi_phnum; ++j) {
                const auto& load = info->dlpi_phdr[j];
                if (load.p_type == PT_LOAD && (load.p_flags & PF_R) && p.p_vaddr >= load.p_vaddr
                        && p.p_vaddr - load.p_vaddr <= load.p_memsz
                        && p.p_filesz <= load.p_memsz - (p.p_vaddr - load.p_vaddr)) {
                    module.note = start; module.noteSize = p.p_filesz; break;
                }
            }
        }
    }
    module.segments = segments.str(); return 1;
}
std::string hex(const unsigned char* bytes, size_t count) {
    std::ostringstream out;
    for (size_t i = 0; i < count; ++i) out << std::hex << std::setw(2) << std::setfill('0') << unsigned(bytes[i]);
    return out.str();
}
std::string buildId(const std::vector<unsigned char>& notes) {
    for (size_t offset = 0; offset + sizeof(Elf64_Nhdr) <= notes.size();) {
        Elf64_Nhdr header{}; std::memcpy(&header, notes.data() + offset, sizeof(header)); offset += sizeof(header);
        const uint64_t name = (uint64_t(header.n_namesz) + 3) & ~uint64_t(3);
        const uint64_t desc = (uint64_t(header.n_descsz) + 3) & ~uint64_t(3);
        if (name > notes.size() - offset || desc > notes.size() - offset - name) break;
        if (header.n_type == NT_GNU_BUILD_ID && header.n_namesz == 4 && header.n_descsz > 0
                && header.n_descsz <= 64 && std::memcmp(notes.data() + offset, "GNU", 4) == 0)
            return hex(notes.data() + offset + name, header.n_descsz);
        offset += size_t(name + desc);
    }
    return "unavailable";
}
}
namespace clientbase {
LoadedIdentity loadedIdentity(const std::string& path) {
    Module module;module.path=path;module.canonical=canonicalPath(path.c_str());
    dl_iterate_phdr(findModule,&module);
    LoadedIdentity result;
    if(!module.found || !module.header || !module.noteSize)return result;
    int fd=open("/proc/self/mem",O_RDONLY|O_CLOEXEC);
    if(fd<0)return result;
    unsigned char header[64]{};std::vector<unsigned char> notes(module.noteSize);
    bool ok=pread(fd,header,sizeof(header),static_cast<off_t>(module.header))==static_cast<ssize_t>(sizeof(header))
        && pread(fd,notes.data(),notes.size(),static_cast<off_t>(module.note))==static_cast<ssize_t>(notes.size());
    close(fd);
    if(!ok || std::memcmp(header,ELFMAG,SELFMAG)!=0 || header[EI_CLASS]!=ELFCLASS64 || header[EI_DATA]!=ELFDATA2LSB)return result;
    result.machine=unsigned(header[18])|(unsigned(header[19])<<8);
    result.buildId=buildId(notes);result.bias=module.bias;result.confirmed=true;return result;
}
}
extern "C" JNIEXPORT jstring JNICALL
Java_dev_zips_clientbase_host_EngineNativeBridge_nativeMemorySnapshot(JNIEnv* env, jclass, jstring path) {
    const char* raw = env->GetStringUTFChars(path, nullptr);
    if (!raw) return nullptr;
    Module module; module.path = raw; module.canonical = canonicalPath(raw); env->ReleaseStringUTFChars(path, raw);
    // Inspect already loaded objects only. Never dlopen, dlsym/call an engine entrypoint, or accept an address.
    dl_iterate_phdr(findModule, &module);
    std::ostringstream out;
    out << "READ-ONLY ENGINE MEMORY\nPID: " << getpid() << "  UID: " << getuid() << "\n";
    if (!module.found) out << "MODULE_NOT_LOADED: exact selected path not in this process\n";
    else {
        out << "Module: " << module.path << "\nLoad bias (this run): 0x" << std::hex << module.bias
            << "\n" << module.segments;
        // Kernel-mediated reads of our own memory fail cleanly if unavailable; never dereference a game pointer.
        int fd = open("/proc/self/mem", O_RDONLY | O_CLOEXEC);
        if (fd < 0) out << "MEMORY_READ_UNAVAILABLE: " << std::strerror(errno) << "\n";
        else {
            unsigned char header[64]{};
            if (module.header && pread(fd, header, sizeof(header), static_cast<off_t>(module.header)) == static_cast<ssize_t>(sizeof(header))
                    && std::memcmp(header, ELFMAG, SELFMAG) == 0 && header[EI_CLASS] == ELFCLASS64) {
                out << "ELF64_HEADER_READ_OK\nHeader (first 16 bytes): " << hex(header, 16) << "\n";
                if (module.noteSize) {
                    std::vector<unsigned char> notes(module.noteSize);
                    if (pread(fd, notes.data(), notes.size(), static_cast<off_t>(module.note)) == static_cast<ssize_t>(notes.size()))
                        out << "Build ID: " << buildId(notes) << "\n";
                    else out << "Build ID: unavailable (note read failed)\n";
                } else out << "Build ID: unavailable (no readable note)\n";
            } else out << "ELF_HEADER_NOT_CONFIRMED (read failed or unexpected header)\n";
            close(fd);
        }
    }
    out << "No writes, hooks, arbitrary address reads, player offsets or engine function calls.\n";
    return env->NewStringUTF(out.str().c_str());
}
