#include "slot_patch.h"
#include <cassert>
using namespace clientbase::modules;
static unsigned original(const void*){return 0x90;}
static unsigned replacement(const void*){return 0x90;}
int main(){
    const size_t size=static_cast<size_t>(sysconf(_SC_PAGESIZE));
    auto* page=mmap(nullptr,size,PROT_READ|PROT_WRITE,MAP_PRIVATE|MAP_ANONYMOUS,-1,0);assert(page!=MAP_FAILED);
    auto* slot=static_cast<uintptr_t*>(page);*slot=reinterpret_cast<uintptr_t>(&original);
    assert(mprotect(page,size,PROT_READ)==0);
    SlotPatch patch;assert(!patch.install(nullptr,1,2));
    assert(!patch.install(slot,42,reinterpret_cast<uintptr_t>(&replacement)));
    assert(*slot==reinterpret_cast<uintptr_t>(&original));
    assert(patch.install(slot,*slot,reinterpret_cast<uintptr_t>(&replacement)));assert(patch.active());
    assert(reinterpret_cast<unsigned(*)(const void*)>(*slot)(nullptr)==0x90);
    assert(patch.install(slot,reinterpret_cast<uintptr_t>(&original),reinterpret_cast<uintptr_t>(&replacement)));
    assert(patch.remove());assert(!patch.active());assert(patch.remove());
    assert(*slot==reinterpret_cast<uintptr_t>(&original));
    // Refuse executable memory rather than treating instruction text as a data slot.
    assert(mprotect(page,size,PROT_READ|PROT_EXEC)==0);
    assert(!patch.install(slot,*slot,reinterpret_cast<uintptr_t>(&replacement)));
    assert(munmap(page,size)==0);
}
