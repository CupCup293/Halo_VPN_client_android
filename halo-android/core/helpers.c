#include <android/log.h>
#include <jni.h>
#include <stdlib.h>
#include <string.h>

const char* halo_get(JNIEnv* env, jstring s) { return (*env)->GetStringUTFChars(env, s, NULL); }

// Настоящий UTF-8 строки (s.getBytes("UTF-8")); GetStringUTFChars даёт «модифицированный» UTF-8 и портит эмодзи.
// Возвращает malloc-буфер (освобождать free()), длина — в *len.
char* halo_utf8(JNIEnv* env, jstring s, int* len) {
    jclass str = (*env)->FindClass(env, "java/lang/String");
    jmethodID get = (*env)->GetMethodID(env, str, "getBytes", "(Ljava/lang/String;)[B");
    jstring charset = (*env)->NewStringUTF(env, "UTF-8");
    jbyteArray bytes = (jbyteArray)(*env)->CallObjectMethod(env, s, get, charset);
    (*env)->DeleteLocalRef(env, charset);
    (*env)->DeleteLocalRef(env, str);
    if (!bytes) { *len = 0; return NULL; }
    jsize n = (*env)->GetArrayLength(env, bytes);
    char* out = malloc(n + 1);
    (*env)->GetByteArrayRegion(env, bytes, 0, n, (jbyte*)out);
    out[n] = 0;
    (*env)->DeleteLocalRef(env, bytes);
    *len = n;
    return out;
}
void halo_release(JNIEnv* env, jstring s, const char* c) { (*env)->ReleaseStringUTFChars(env, s, c); }
// Java-строка из UTF-8 через new String(bytes, "UTF-8"): NewStringUTF не принимает 4-байтовые символы (эмодзи-флаги).
jstring halo_new(JNIEnv* env, const char* c) {
    jsize n = (jsize)strlen(c);
    jbyteArray bytes = (*env)->NewByteArray(env, n);
    if (!bytes) return NULL;
    (*env)->SetByteArrayRegion(env, bytes, 0, n, (const jbyte*)c);
    jclass str = (*env)->FindClass(env, "java/lang/String");
    jmethodID init = (*env)->GetMethodID(env, str, "<init>", "([BLjava/lang/String;)V");
    jstring charset = (*env)->NewStringUTF(env, "UTF-8");
    jstring out = (jstring)(*env)->NewObject(env, str, init, bytes, charset);
    (*env)->DeleteLocalRef(env, bytes);
    (*env)->DeleteLocalRef(env, charset);
    (*env)->DeleteLocalRef(env, str);
    return out;
}
void halo_log(int prio, const char* msg) { __android_log_write(prio, "HaloCore", msg); }

// Вызов из ядра обратно в Kotlin: Core.packageOf(...) — какому приложению принадлежит соединение.
static JavaVM* g_vm;
static jclass g_core;
static jmethodID g_package_of;
static jmethodID g_on_log;

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    JNIEnv* env;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    g_vm = vm;
    jclass cls = (*env)->FindClass(env, "app/halo/vpn/core/Core");
    if (cls) {
        g_core = (*env)->NewGlobalRef(env, cls);
        g_package_of = (*env)->GetStaticMethodID(env, cls, "packageOf", "(ILjava/lang/String;ILjava/lang/String;I)Ljava/lang/String;");
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        g_on_log = (*env)->GetStaticMethodID(env, cls, "onLog", "(I[B)V");
    }
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
    return JNI_VERSION_1_6;
}

// Возвращает имя пакета (освобождать free()) или NULL.
char* halo_package(int proto, const char* src, int sport, const char* dst, int dport) {
    if (!g_vm || !g_core || !g_package_of) return NULL;
    JNIEnv* env;
    int attached = 0;
    if ((*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return NULL;
        attached = 1;
    }
    char* out = NULL;
    jstring s = (*env)->NewStringUTF(env, src);
    jstring d = (*env)->NewStringUTF(env, dst);
    jstring r = (jstring)(*env)->CallStaticObjectMethod(env, g_core, g_package_of, proto, s, sport, d, dport);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        r = NULL;
    }
    if (r) {
        const char* c = (*env)->GetStringUTFChars(env, r, NULL);
        if (c) {
            out = strdup(c);
            (*env)->ReleaseStringUTFChars(env, r, c);
        }
        (*env)->DeleteLocalRef(env, r);
    }
    (*env)->DeleteLocalRef(env, s);
    (*env)->DeleteLocalRef(env, d);
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
    return out;
}

// Строка журнала ядра → Core.onLog(level, msg) → HaloLog.
void halo_emit(int level, const char* msg) {
    if (!g_vm || !g_core || !g_on_log) return;
    JNIEnv* env;
    int attached = 0;
    if ((*g_vm)->GetEnv(g_vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) {
        if ((*g_vm)->AttachCurrentThread(g_vm, &env, NULL) != JNI_OK) return;
        attached = 1;
    }
    // Байтами, а не NewStringUTF: в именах серверов эмодзи-флаги (4-байтовый UTF-8), их JNI-строки не принимают.
    jsize n = (jsize)strlen(msg);
    jbyteArray m = (*env)->NewByteArray(env, n);
    if (m) {
        (*env)->SetByteArrayRegion(env, m, 0, n, (const jbyte*)msg);
        (*env)->CallStaticVoidMethod(env, g_core, g_on_log, level, m);
        if ((*env)->ExceptionCheck(env)) (*env)->ExceptionClear(env);
        (*env)->DeleteLocalRef(env, m);
    } else if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
    }
    if (attached) (*g_vm)->DetachCurrentThread(g_vm);
}
