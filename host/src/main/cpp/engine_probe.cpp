#include <jni.h>
#include <dlfcn.h>
#include <unistd.h>
#include <sstream>
#include <atomic>

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

// Our own bridge's JNI callback, also exercised by the copied-library JVM fixture.
// This is not Minecraft's JNI_OnLoad and never calls into the engine.
static std::atomic<jint> bridgeLoads{0};
extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    ++bridgeLoads;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_zips_clientbase_host_EngineNativeBridge_nativeInitializationCount(JNIEnv*, jclass) {
    return bridgeLoads.load();
}

extern "C" JNIEXPORT jstring JNICALL
Java_dev_zips_clientbase_host_EngineNativeBridge_nativeDescribeLoaded(JNIEnv* env, jclass, jstring path) {
    const char* raw = env->GetStringUTFChars(path, nullptr);
    if (!raw) return nullptr;
    // RTLD_NOLOAD is mandatory: this observation must never run a second load.
    void* handle = dlopen(raw, RTLD_NOW | RTLD_LOCAL | RTLD_NOLOAD);
    env->ReleaseStringUTFChars(path, raw);
    std::ostringstream out;
    if (!handle) {
        const char* failure = dlerror();
        out << "ENGINE_MAPPING_NOT_CONFIRMED\n" << (failure ? failure : "No existing handle");
    } else {
        out << "ENGINE_ALREADY_MAPPED (RTLD_NOLOAD lookup only)\n";
        void* symbol = dlsym(handle, "JNI_OnLoad");
        Dl_info info{};
        if (symbol && dladdr(symbol, &info)) {
            out << "JNI_OnLoad owner: " << (info.dli_fname ? info.dli_fname : "unknown")
                << "\nMapping base (this run only): " << info.dli_fbase << "\n";
        } else {
            out << "JNI_OnLoad ownership not resolved\n";
        }
        out << "Lookup did not call the engine's JNI_OnLoad or any game entrypoint.\n";
        // Retain the observation handle until this one-shot process exits.
    }
    return env->NewStringUTF(out.str().c_str());
}
