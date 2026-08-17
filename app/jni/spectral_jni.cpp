#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include "spectral_engine.h"

#define LOG_TAG "HGSpectralNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

// JNI bridge for org.thunderdog.challegram.solana.NativeLib.
// Mirrors ondo-zero-android/app/src/main/cpp/ondozero_jni.cpp, but only exposes
// the SpectralEngine surface (Spectral ID + proof seed) — Human Gram does not
// run the camera pipeline, so frame/merkle globals are intentionally omitted.

// Computes the 32-byte Spectral Proof Seed: θ_temp = θ + R (mod range), spectral invariant.
// Implements off-chain Proof Generation (Patent §[0019]).
extern "C" JNIEXPORT jbyteArray JNICALL
Java_org_thunderdog_challegram_solana_NativeLib_computeSpectralProofSeed(JNIEnv* env, jobject, jbyteArray theta_seed, jbyteArray challenge) {
    jsize seed_len = env->GetArrayLength(theta_seed);
    jbyte* seed_body = env->GetByteArrayElements(theta_seed, nullptr);

    // Expand seed to N=256 doubles, same as generateSpectralId.
    constexpr int N = 256;
    std::vector<double> theta(N);
    for (int i = 0; i < N; ++i) {
        uint8_t b = static_cast<uint8_t>(seed_body[i % seed_len]);
        theta[i] = (static_cast<double>(b) / 127.5) - 1.0;
    }
    env->ReleaseByteArrayElements(theta_seed, seed_body, JNI_ABORT);

    SpectralEngine eng(theta.data(), N);

    jsize ch_len = env->GetArrayLength(challenge);
    jbyte* ch_body = env->GetByteArrayElements(challenge, nullptr);

    auto proof_seed = eng.compute_spectral_proof_seed(
        reinterpret_cast<const uint8_t*>(ch_body), static_cast<int>(ch_len));

    env->ReleaseByteArrayElements(challenge, ch_body, JNI_ABORT);

    jbyteArray result = env->NewByteArray(32);
    env->SetByteArrayRegion(result, 0, 32, reinterpret_cast<const jbyte*>(proof_seed.data()));
    return result;
}

// Generates a deterministic Spectral ID from a 32-byte seed.
// The canonical probe vector is 256 bytes of 0x80 (mid-gray luminance).
extern "C" JNIEXPORT jstring JNICALL
Java_org_thunderdog_challegram_solana_NativeLib_generateSpectralId(JNIEnv* env, jobject, jbyteArray seed) {
    jsize seed_len = env->GetArrayLength(seed);
    jbyte* seed_body = env->GetByteArrayElements(seed, nullptr);

    // Expand seed bytes to N=256 doubles in [-1, 1].
    constexpr int N = 256;
    std::vector<double> theta(N);
    for (int i = 0; i < N; ++i) {
        uint8_t b = static_cast<uint8_t>(seed_body[i % seed_len]);
        theta[i] = (static_cast<double>(b) / 127.5) - 1.0;
    }
    env->ReleaseByteArrayElements(seed, seed_body, JNI_ABORT);

    // Canonical probe: 256 bytes of 0x80 (mid-gray).
    std::vector<uint8_t> probe(256, 0x80);

    SpectralEngine tmp(theta.data(), N);
    std::string id = tmp.process_frame(
        reinterpret_cast<const uint8_t*>(probe.data()), static_cast<int>(probe.size()));
    return env->NewStringUTF(id.c_str());
}
