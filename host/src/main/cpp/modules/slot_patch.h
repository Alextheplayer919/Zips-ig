#pragma once

#include <cstdint>
#include <cstdio>
#include <fstream>
#include <string>
#include <sys/mman.h>
#include <unistd.h>

namespace clientbase::modules {

// Patches one pointer-aligned RELRO data slot (never executable .text instructions).
// Callers must serialize install/remove and verify module/RTTI/instruction fingerprints first.
class SlotPatch {
    uintptr_t* slot_{};
    uintptr_t original_{}, replacement_{};
    uintptr_t page_{};
    size_t pageSize_{};
    int permissions_{};
    bool active_{};
    std::string error_;

    bool exchange(uintptr_t expected, uintptr_t desired) {
        if (mprotect(reinterpret_cast<void*>(page_), pageSize_, permissions_ | PROT_WRITE) != 0) {
            error_ = "Cannot make RELRO slot writable";
            return false;
        }
        const bool changed = __atomic_compare_exchange_n(
            slot_, &expected, desired, false, __ATOMIC_SEQ_CST, __ATOMIC_SEQ_CST);
        if (mprotect(reinterpret_cast<void*>(page_), pageSize_, permissions_) != 0) {
            if (changed) __atomic_store_n(slot_, original_, __ATOMIC_SEQ_CST);
            mprotect(reinterpret_cast<void*>(page_), pageSize_, permissions_);
            active_ = false;
            error_ = "Permission restore failed; restart the game process";
            return false;
        }
        if (!changed) {
            error_ = "Target slot value changed; refusing foreign pointer overwrite";
            return false;
        }
        return true;
    }

public:
    const std::string& error() const { return error_; }
    bool active() const { return active_; }
    uintptr_t original() const { return original_; }

    bool install(uintptr_t* slot, uintptr_t expected, uintptr_t replacement) {
        if (active_) return slot == slot_ && replacement == replacement_;
        error_.clear();
        if (!slot || reinterpret_cast<uintptr_t>(slot) % alignof(uintptr_t) != 0 || !expected || !replacement) {
            error_ = "Invalid RELRO slot";
            return false;
        }
        const long pageSize = sysconf(_SC_PAGESIZE);
        if (pageSize <= 0) {
            error_ = "Page size unavailable";
            return false;
        }
        pageSize_ = static_cast<size_t>(pageSize);
        const uintptr_t address = reinterpret_cast<uintptr_t>(slot);
        page_ = address - (address % pageSize_);

        std::ifstream maps("/proc/self/maps");
        std::string line;
        bool found = false;
        while (std::getline(maps, line)) {
            unsigned long start = 0, end = 0;
            char flags[5]{};
            if (std::sscanf(line.c_str(), "%lx-%lx %4s", &start, &end, flags) != 3) continue;
            if (address < start || address + sizeof(uintptr_t) > end) continue;
            if (page_ < start || page_ + pageSize_ > end) break;
            if (flags[0] != 'r' || flags[2] == 'x' || flags[3] != 'p') break;
            permissions_ = PROT_READ | (flags[1] == 'w' ? PROT_WRITE : 0);
            found = true;
            break;
        }
        if (!found) {
            error_ = "Readable private non-executable RELRO mapping required";
            return false;
        }
        slot_ = slot;
        original_ = expected;
        replacement_ = replacement;
        if (!exchange(expected, replacement)) return false;
        active_ = true;
        return true;
    }

    bool remove() {
        if (!active_) return true;
        if (!exchange(replacement_, original_)) return false;
        active_ = false;
        return true;
    }
};

} // namespace clientbase::modules
