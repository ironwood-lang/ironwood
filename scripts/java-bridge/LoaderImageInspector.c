/* SPDX-License-Identifier: MIT OR Apache-2.0 */
/* Test-only inspection of already mapped producer images; never loads one. */
#include <jni.h>
#include <dlfcn.h>

static void *mapped_image(JNIEnv *env, jstring path) {
    const char *name = (*env)->GetStringUTFChars(env, path, NULL);
    if (name == NULL) return NULL;
    void *image = dlopen(name, RTLD_NOW | RTLD_NOLOAD);
    (*env)->ReleaseStringUTFChars(env, path, name);
    return image;
}

JNIEXPORT jint JNICALL Java_LoaderQualificationConsumer_reloadHook(JNIEnv *env, jclass type, jstring path) {
    (void)type;
    void *image = mapped_image(env, path);
    if (image == NULL) return -2;
    jint (*hook)(JavaVM *, void *) = (jint (*)(JavaVM *, void *))dlsym(image, "JNI_OnLoad");
    JavaVM *vm = NULL;
    jint result = -3;
    if (hook != NULL && (*env)->GetJavaVM(env, &vm) == JNI_OK) result = hook(vm, NULL);
    dlclose(image);
    return result;
}

JNIEXPORT jint JNICALL Java_LoaderQualificationConsumer_faultStat(JNIEnv *env, jclass type, jstring path, jint slot) {
    (void)type;
    void *image = mapped_image(env, path);
    if (image == NULL) return -2;
    int (*stat)(int) = (int (*)(int))dlsym(image, "iw_fixture_stat");
    jint result = stat == NULL ? -3 : stat(slot);
    dlclose(image);
    return result;
}
