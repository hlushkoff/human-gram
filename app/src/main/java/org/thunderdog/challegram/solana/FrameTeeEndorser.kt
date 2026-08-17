package org.thunderdog.challegram.solana

import android.content.Context
import android.os.Build
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.Mac

/**
 * Variant B — per-frame HMAC-SHA256 TEE endorsement.
 *
 * Raw GSH-256 hashes from [NativeLib.computeFrameHash] are endorsed by a
 * device-unique HMAC-SHA256 key stored inside AndroidKeyStore (StrongBox if available).
 * The HMAC key never leaves the Keymaster/StrongBox hardware boundary.
 *
 * Endorsement chain per I-frame:
 *   I-frame NAL → computeFrameHash(θ) → gshHash (JVM heap)
 *              → HMAC-SHA256(TEE_key, gshHash.utf8) → endorsedHash → Merkle leaf
 *
 * Security over base v0.2.0:
 *   An attacker who steals θ can reproduce raw GSH-256 hashes.
 *   With TEE endorsement the attacker also needs the device HMAC key — which is
 *   non-exportable and hardware-isolated (even from a rooted OS).
 *   This resolves audit gap 2.2: hash computation output is now hardware-bound.
 *
 * Sidecar JSON stores the endorsed hashes (not raw).  Verifier uses addLeafHash()
 * to replay the same endorsed hashes — no TEE key required at verification time.
 */
object FrameTeeEndorser {

    private const val TAG       = "FrameTeeEndorser"
    const val KEY_ALIAS         = "ondozero_frame_hmac_v1"
    private const val ALGORITHM = "HmacSHA256"

    /** true when the HMAC key lives in StrongBox or hardware Keymaster (never software). */
    @Volatile var isHardwareBacked: Boolean = false
        private set

    @Volatile private var _ready = false

    fun requireHardwareBacked() {
        check(_ready) { "TEE signer is not initialized" }
        check(isHardwareBacked) { "Hardware-backed Android TEE is required for trusted media publish" }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Creates the HMAC key inside AndroidKeyStore if not already present.
     * Idempotent — safe to call at every app start.
     * Must be called from a background thread (Keystore ops can block).
     */
    fun getOrGenerate(context: Context) {
        if (_ready) return
        synchronized(this) {
            if (_ready) return
            ensureHmacKey()
            _ready = true
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Endorses [gshHex] (a 64-char hex GSH-256 hash) with the device TEE HMAC key.
     *
     * Returns: HMAC-SHA256(TEE_key, gshHex.utf8) as a 64-char lowercase hex string.
     * Fallback: returns [gshHex] unmodified if the key is unexpectedly absent, and
     *           logs a warning — so the flow degrades gracefully to v0.2.0 behaviour.
     */
    fun endorse(gshHex: String): String {
        val signature = signPublishAuthorizationPayload(gshHex.toByteArray(Charsets.UTF_8))
        require(signature.hardwareBacked) { "Hardware-backed TEE endorsement is required" }
        return signature.signatureBytes.joinToString("") { "%02x".format(it) }
    }

    fun signPublishAuthorizationPayload(payload: ByteArray): TeeSignatureResult {
        val key = ks().getKey(KEY_ALIAS, null)
            ?: throw IllegalStateException("TEE HMAC key is missing")
        requireHardwareBacked()
        val mac = Mac.getInstance(ALGORITHM)
        mac.init(key)
        return TeeSignatureResult(
            hardwareBacked = true,
            signatureBytes = mac.doFinal(payload),
            keyAttestationState = KeyAttestationState.HARDWARE_BACKED,
        )
    }

    fun signProofPayload(payload: ByteArray): TeeSignatureResult {
        return signPublishAuthorizationPayload(payload)
    }

    // ── Internal helpers ──────────────────────────────────────────────────────

    private fun ensureHmacKey() {
        val store = ks()
        if (store.containsAlias(KEY_ALIAS)) {
            isHardwareBacked = queryHardwareBacked()
            Log.i(TAG, "HMAC TEE key loaded (hwBacked=$isHardwareBacked)")
            return
        }
        // Prefer StrongBox (dedicated SE chip, API 28+).
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                generateHmacKey(strongBox = true)
                isHardwareBacked = queryHardwareBacked()
                Log.i(TAG, "HMAC TEE key created in StrongBox (hwBacked=$isHardwareBacked)")
                return
            } catch (e: Exception) {
                if (e.javaClass.name.endsWith("StrongBoxUnavailableException")) {
                    Log.w(TAG, "StrongBox unavailable — creating non-trusted software-backed AndroidKeyStore key")
                } else throw e
            }
        }
        generateHmacKey(strongBox = false)
        isHardwareBacked = queryHardwareBacked()
        Log.i(TAG, "HMAC TEE key created (hwBacked=$isHardwareBacked)")
    }

    private fun generateHmacKey(strongBox: Boolean) {
        val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
            .setDigests(KeyProperties.DIGEST_SHA256)
            .apply { if (strongBox && Build.VERSION.SDK_INT >= 28) setIsStrongBoxBacked(true) }
            .build()
        KeyGenerator.getInstance(ALGORITHM, "AndroidKeyStore")
            .also { it.init(spec) }
            .generateKey()
    }

    private fun queryHardwareBacked(): Boolean {
        return try {
            val entry = ks().getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry ?: return false
            val factory = javax.crypto.SecretKeyFactory.getInstance(
                entry.secretKey.algorithm, "AndroidKeyStore"
            )
            @Suppress("UNCHECKED_CAST")
            val info = factory.getKeySpec(
                entry.secretKey,
                android.security.keystore.KeyInfo::class.java
            ) as android.security.keystore.KeyInfo
            if (Build.VERSION.SDK_INT >= 31) {
                info.securityLevel !=
                        android.security.keystore.KeyProperties.SECURITY_LEVEL_SOFTWARE
            } else {
                @Suppress("DEPRECATION")
                info.isInsideSecureHardware
            }
        } catch (_: Exception) { false }
    }

    private fun ks() = KeyStore.getInstance("AndroidKeyStore").also { it.load(null) }
}
