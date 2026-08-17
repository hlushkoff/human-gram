package org.thunderdog.challegram.solana

import android.content.Context
import android.os.Build
import android.util.Log

/**
 * Spectral ID (device identity) entry points for Human Gram.
 *
 * The Spectral ID is derived from the hardware attestation fingerprint via the
 * spectral-core engine (see [OndoZeroDependencies]). It is generated lazily and
 * cached; [ensureSpectralId] is called right after phone-number authorization
 * (MainActivity) so the identifier is ready before the user opens Ondo-Zero settings.
 *
 * Requires API 24+ (Android Key Attestation). On unsupported devices all methods
 * fail soft and return null / false.
 */
object OndoZeroIdentity {
    private const val TAG = "OndoZeroIdentity"

    @Volatile private var cachedSpectralId: String? = null
    @Volatile private var unsupported = false
    @Volatile private var generationStarted = false
    private val pendingCallbacks = java.util.concurrent.CopyOnWriteArrayList<Runnable>()

    /** True when this device can produce a hardware-rooted Spectral ID. */
    @JvmStatic
    fun isSupported(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        if (unsupported) return false
        return NativeLib.ensureLoaded()
    }

    /** Returns the already-cached Spectral ID without any computation (main-thread safe). */
    @JvmStatic
    fun peekSpectralId(): String? = cachedSpectralId

    /** True when a previous derivation attempt proved the device unsupported. */
    @JvmStatic
    fun isUnsupported(): Boolean = unsupported

    /**
     * Returns the Spectral ID (64-char hex) or null when unavailable/unsupported.
     * BLOCKING on first call (attestation + native derivation) — call from a
     * background thread, or use [ensureSpectralIdAsync].
     */
    @JvmStatic
    fun getSpectralIdOrNull(context: Context): String? {
        cachedSpectralId?.let { return it }
        if (!isSupported(context)) return null
        return try {
            val id = OndoZeroDependencies.spectralIdStore.getOrCreate(context.applicationContext)
            cachedSpectralId = id
            id
        } catch (error: Throwable) {
            Log.w(TAG, "Spectral ID derivation failed: ${error.message}")
            unsupported = true
            null
        }
    }

    /**
     * Kicks off Spectral ID generation on a background thread (idempotent).
     * Used at first start right after phone authorization.
     * Multiple callers may pass callbacks — all are invoked once the ID is ready
     * (or immediately if it is already cached).
     */
    @JvmStatic
    fun ensureSpectralIdAsync(context: Context, onReady: Runnable? = null) {
        if (cachedSpectralId != null || unsupported) {
            onReady?.let { org.thunderdog.challegram.tool.UI.post(it) }
            return
        }
        if (onReady != null) pendingCallbacks.add(onReady)
        synchronized(this) {
            if (generationStarted) return
            generationStarted = true
        }
        Thread {
            val id = getSpectralIdOrNull(context.applicationContext)
            if (id != null) {
                Log.i(TAG, "Spectral ID ready: ${id.take(8)}…")
            }
            val callbacks = pendingCallbacks.toTypedArray()
            pendingCallbacks.clear()
            for (callback in callbacks) {
                org.thunderdog.challegram.tool.UI.post(callback)
            }
        }.also { it.isDaemon = true; it.start() }
    }

    /** Drops the in-memory cache (e.g. after wallet wipe / identity change). */
    @JvmStatic
    fun invalidateCache() {
        cachedSpectralId = null
    }
}
