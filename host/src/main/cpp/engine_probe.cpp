#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>
#include <sstream>

// dlopen runs ELF constructors, but unlike System.load it does not invoke the
// engine's JNI_OnLoad. Never call any address obtained below.
extern "C" JNIEXPORT jstring JNICALL
Java_dev_zips_clientbase_host_EngineNativeBridge_nativeProbe(JNIEnv* env, jclass, jstring path) {
    const char* raw = env->GetStringUTFChars(path, nullptr);
    if (!raw) return nullptr;
    void* handle = dlopen(raw, RTLD_NOW | RTLD_LOCAL);
    const char* failure = handle ? nullptr : dlerror();
    std::string failureText = failure ? failure : "Unknown linker error";
    env->ReleaseStringUTFChars(path, raw);
    std::ostringstream out;
    out << "Probe PID: " << getpid() << "\nProbe UID: " << getuid() << "\n";
    if (!handle) {
        out << "LINK_CHECK_FAILED\n" << failureText;
    } else {
        out << "LINK_CHECK_SUCCESS (not game startup)\n";
        for (const char* name : {"JNI_OnLoad", "ANativeActivity_onCreate"}) {
            void* symbol = dlsym(handle, name);
            out << name << ": " << (symbol ? "present" : "absent") << "\n";
            Dl_info info{};
            if (symbol && dladdr(symbol, &info)) {
                out << "  owner: " << (info.dli_fname ? info.dli_fname : "unknown")
                    << "\n  mapping base: " << info.dli_fbase << "\n";
            }
        }
        out << "ELF constructors ran. JNI_OnLoad/game entrypoints were NOT invoked.\n";
        // Intentionally retain the handle until the one-shot probe process exits.
        // Unloading a complex engine without its lifecycle is not assumed safe.
    }
    return env->NewStringUTF(out.str().c_str());
}
