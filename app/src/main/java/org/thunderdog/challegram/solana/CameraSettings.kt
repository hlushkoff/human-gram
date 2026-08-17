package org.thunderdog.challegram.solana

import android.content.Context

/**
 * Persistent video-recording (media proof) settings — port of ondo-zero-android's
 * CameraSettings, scoped to what Human Gram's video hook actually uses.
 *
 * hashesPerSecond controls how many GSH-256 frame hashes per second are computed
 * (and TEE-endorsed) when a video is published through [SolanaVideoHook].
 */
object CameraSettings {

    private const val PREFS = "camera_settings"

    private const val KEY_HASH_EVERY_N    = "hash_every_n_frames"
    private const val KEY_POST_INTERVAL   = "post_interval_minutes"
    private const val KEY_HASHES_PER_SEC  = "hashes_per_sec"

    // ── Defaults (match Ondo-Zero Video) ──────────────────────────────────────
    const val DEFAULT_HASH_EVERY_N: Int    = 10
    const val DEFAULT_POST_INTERVAL: Int   = 5
    const val DEFAULT_HASHES_PER_SEC: Int  = 2

    // ── Allowed ranges (enforced on save) ─────────────────────────────────────
    val HASH_FRAME_RANGE    = 1..120   // 1 = every frame, 120 = every 4 s at 30 fps
    val POST_INTERVAL_RANGE = 1..60    // minutes
    val HASHES_PER_SEC_RANGE = 1..4    // max 4 hashes per second

    // ── Getters ────────────────────────────────────────────────────────────────

    @JvmStatic
    fun hashEveryNFrames(context: Context): Int =
        prefs(context).getInt(KEY_HASH_EVERY_N, DEFAULT_HASH_EVERY_N)
            .coerceIn(HASH_FRAME_RANGE)

    @JvmStatic
    fun hashesPerSecond(context: Context): Int =
        prefs(context).getInt(KEY_HASHES_PER_SEC, DEFAULT_HASHES_PER_SEC)
            .coerceIn(HASHES_PER_SEC_RANGE)

    @JvmStatic
    fun postIntervalMinutes(context: Context): Int =
        prefs(context).getInt(KEY_POST_INTERVAL, DEFAULT_POST_INTERVAL)
            .coerceIn(POST_INTERVAL_RANGE)

    // ── Setters ────────────────────────────────────────────────────────────────

    @JvmStatic
    fun setHashEveryNFrames(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_HASH_EVERY_N, value.coerceIn(HASH_FRAME_RANGE)).apply()
    }

    @JvmStatic
    fun setHashesPerSecond(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_HASHES_PER_SEC, value.coerceIn(HASHES_PER_SEC_RANGE)).apply()
    }

    @JvmStatic
    fun setPostIntervalMinutes(context: Context, value: Int) {
        prefs(context).edit().putInt(KEY_POST_INTERVAL, value.coerceIn(POST_INTERVAL_RANGE)).apply()
    }

    // ── Private ────────────────────────────────────────────────────────────────

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
