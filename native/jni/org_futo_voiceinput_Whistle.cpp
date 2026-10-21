#include <string>
#include <vector>
#include <jni.h>
#include "defines.h"
#include "org_futo_voiceinput_Whistle.h"
#include "jni_common.h"
#include "jni_utils.h"

#ifdef HAS_NEEDLE
#include "needle.h"
#endif

// Whistle runs on the prebuilt Needle engine, which only exists for some ABIs (see CMakeLists.txt).
// Without it these methods report that Whistle is unavailable. The engine is process-global and
// not thread-safe; WhistleModel.kt serializes every call.

#define WHISTLE_OUTPUT_CAPACITY (1 << 16)

static jboolean Whistle_isAvailable(JNIEnv *env, jobject instance) {
#ifdef HAS_NEEDLE
    return JNI_TRUE;
#else
    return JNI_FALSE;
#endif
}

static jstring Whistle_lastError(JNIEnv *env, jobject instance) {
#ifdef HAS_NEEDLE
    const char *error = needle_last_error();
    return string2jstring(env, error ? error : "unknown error");
#else
    return string2jstring(env, "Whistle is not available in this build");
#endif
}

// Returns null on success, or the reason loading failed
static jstring Whistle_load(JNIEnv *env, jobject instance, jobject buffer) {
#ifdef HAS_NEEDLE
    void *buffer_address = env->GetDirectBufferAddress(buffer);
    jlong buffer_capacity = env->GetDirectBufferCapacity(buffer);
    if(!buffer_address || buffer_capacity <= 0) {
        return string2jstring(env, "not a direct buffer");
    }

    AKLOGI("Attempting to load Whistle model from buffer...");
    int res = needle_load(static_cast<const unsigned char *>(buffer_address), (unsigned long long)buffer_capacity);
    if(res < 0) {
        AKLOGE("needle_load failed with code %d: %s", res, needle_last_error());
        return Whistle_lastError(env, instance);
    }

    if(!(needle_models() & NEEDLE_SPEECH)) {
        return string2jstring(env, "the file does not contain a speech model");
    }

    return nullptr;
#else
    return Whistle_lastError(env, instance);
#endif
}

// Returns the engine's JSON output, or null on failure (see lastErrorNative)
static jstring Whistle_transcribe(JNIEnv *env, jobject instance, jfloatArray samples_array, jstring language, jstring keywords) {
#ifdef HAS_NEEDLE
    std::string language_str;
    if(language) language_str = jstring2string(env, language);

    std::string keywords_str;
    if(keywords) keywords_str = jstring2string(env, keywords);

    jsize num_samples = env->GetArrayLength(samples_array);
    jfloat *samples = env->GetFloatArrayElements(samples_array, nullptr);

    std::vector<char> output(WHISTLE_OUTPUT_CAPACITY, 0);

    AKLOGI("Calling needle_transcribe with %d samples", (int)num_samples);
    int res = needle_transcribe(
            samples,
            (int)num_samples,
            language ? language_str.c_str() : nullptr,
            keywords ? keywords_str.c_str() : nullptr,
            0,
            output.data(),
            (int)output.size());

    env->ReleaseFloatArrayElements(samples_array, samples, JNI_ABORT);

    if(res < 0) {
        AKLOGE("needle_transcribe failed with code %d: %s", res, needle_last_error());
        return nullptr;
    }

    output.back() = '\0';
    return string2jstring(env, output.data());
#else
    return nullptr;
#endif
}

// Feeds the next samples of a live stream. Returns the engine's JSON output with the newly
// committed words and the pending tail, or null on failure (see lastErrorNative)
static jstring Whistle_streamProcess(JNIEnv *env, jobject instance, jfloatArray samples_array, jstring language, jstring keywords) {
#ifdef HAS_NEEDLE
    std::string language_str;
    if(language) language_str = jstring2string(env, language);

    std::string keywords_str;
    if(keywords) keywords_str = jstring2string(env, keywords);

    jsize num_samples = env->GetArrayLength(samples_array);
    jfloat *samples = env->GetFloatArrayElements(samples_array, nullptr);

    std::vector<char> output(WHISTLE_OUTPUT_CAPACITY, 0);

    int res = needle_stream_transcribe_process(
            samples,
            (int)num_samples,
            language ? language_str.c_str() : nullptr,
            keywords ? keywords_str.c_str() : nullptr,
            output.data(),
            (int)output.size());

    env->ReleaseFloatArrayElements(samples_array, samples, JNI_ABORT);

    if(res < 0) {
        AKLOGE("needle_stream_transcribe_process failed with code %d: %s", res, needle_last_error());
        return nullptr;
    }

    output.back() = '\0';
    return string2jstring(env, output.data());
#else
    return nullptr;
#endif
}

// Ends the current stream so the next streamProcess call starts a new one
static jstring Whistle_streamStop(JNIEnv *env, jobject instance) {
#ifdef HAS_NEEDLE
    std::vector<char> output(WHISTLE_OUTPUT_CAPACITY, 0);

    int res = needle_stream_transcribe_stop(output.data(), (int)output.size());
    if(res < 0) {
        AKLOGE("needle_stream_transcribe_stop failed with code %d: %s", res, needle_last_error());
        return nullptr;
    }

    output.back() = '\0';
    return string2jstring(env, output.data());
#else
    return nullptr;
#endif
}

static const JNINativeMethod sMethods[] = {
        {
                const_cast<char *>("isAvailableNative"),
                const_cast<char *>("()Z"),
                reinterpret_cast<void *>(Whistle_isAvailable)
        },
        {
                const_cast<char *>("loadNative"),
                const_cast<char *>("(Ljava/nio/Buffer;)Ljava/lang/String;"),
                reinterpret_cast<void *>(Whistle_load)
        },
        {
                const_cast<char *>("transcribeNative"),
                const_cast<char *>("([FLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
                reinterpret_cast<void *>(Whistle_transcribe)
        },
        {
                const_cast<char *>("streamProcessNative"),
                const_cast<char *>("([FLjava/lang/String;Ljava/lang/String;)Ljava/lang/String;"),
                reinterpret_cast<void *>(Whistle_streamProcess)
        },
        {
                const_cast<char *>("streamStopNative"),
                const_cast<char *>("()Ljava/lang/String;"),
                reinterpret_cast<void *>(Whistle_streamStop)
        },
        {
                const_cast<char *>("lastErrorNative"),
                const_cast<char *>("()Ljava/lang/String;"),
                reinterpret_cast<void *>(Whistle_lastError)
        }
};

namespace voiceinput {
    int register_Whistle(JNIEnv *env) {
        const char *const kClassPathName = "org/futo/voiceinput/shared/whistle/WhistleModel";
        return latinime::registerNativeMethods(env, kClassPathName, sMethods, NELEMS(sMethods));
    }
}
