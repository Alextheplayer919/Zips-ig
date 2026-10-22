#include <jni.h>
#include "process_identity.h"

extern "C" JNIEXPORT jint JNICALL
Java_dev_zips_clientbase_guest_DemoGuestEntry_nativeProcessId(JNIEnv*, jclass) {
    return static_cast<jint>(clientbase::current_identity().pid);
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_zips_clientbase_guest_DemoGuestEntry_nativeUserId(JNIEnv*, jclass) {
    return static_cast<jint>(clientbase::current_identity().uid);
}
