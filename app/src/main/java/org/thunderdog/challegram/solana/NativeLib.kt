package org.thunderdog.challegram.solana

import io.spectralcore.SpectralEngine

/**
 * JNI bridge to libhgspectral.so.
 * Implements [SpectralEngine] so it can be injected into SpectralIdStore (from spectral-core).
 * The C++ engine sources (spectral_engine.cpp, merkle_tree.cpp) live in
 * spectral-core/src/main/cpp/ and are compiled into this .so via app/jni/CMakeLists.txt,
 * mirroring ondo-zero-android's ondozero_native setup.
 */
class NativeLib : SpectralEngine {
    companion object {
        @Volatile private var loaded = false

        @JvmStatic
        @Synchronized
        fun ensureLoaded(): Boolean {
            if (loaded) return true
            return try {
                System.loadLibrary("hgspectral")
                loaded = true
                true
            } catch (error: Throwable) {
                android.util.Log.w("NativeLib", "hgspectral unavailable: ${error.message}")
                false
            }
        }
    }

    external override fun generateSpectralId(seed: ByteArray): String
    external override fun computeSpectralProofSeed(thetaSeed: ByteArray, challenge: ByteArray): ByteArray
}
