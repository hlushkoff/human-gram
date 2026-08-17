package org.thunderdog.challegram.solana

import io.spectralcore.ChallengeProofService
import io.spectralcore.DeviceIdentityService
import io.spectralcore.DeviceTeeKey
import io.spectralcore.RatchetKeyStore
import io.spectralcore.SpectralIdDerivationStrategy
import io.spectralcore.SpectralIdStore

/**
 * Human Gram counterpart of ondo-zero-android's AppDependencies.
 *
 * Reuses the spectral-core library (single source of truth) with app-specific
 * storage namespaces. The Spectral ID domain intentionally matches Ondo-Zero Video
 * ("ondozero_hw_v4"): the attestation fingerprint (cert[1]) is app-independent, so the
 * same physical device yields the SAME Spectral ID in both apps — this is what binds
 * the device to the wallet in the blockchain registry.
 */
object OndoZeroDependencies {
    val deviceTeeKey = DeviceTeeKey("humangram_tee_ed25519_v1", "humangram_tee_wrap_v1", "humangram_tee_prefs")

    // v4: ATTESTATION_PRIMARY — hardware TEE fingerprint as theta seed.
    // Domain MUST stay "ondozero_hw_v4" for cross-app Spectral ID consistency.
    val spectralIdStore = SpectralIdStore(
        "humangram_identity",
        "ondozero_hw_v4",
        true,
        NativeLib(),
        deviceTeeKey,
        SpectralIdDerivationStrategy.ATTESTATION_PRIMARY,
    )

    val deviceIdentityService = DeviceIdentityService(deviceTeeKey, SolanaConfig.ONDO_ZERO_GUARDIAN_PUBKEY)
    val ratchetKeyStore = RatchetKeyStore("humangram_ratchet_v1")
    val challengeProofService = ChallengeProofService(deviceIdentityService, spectralIdStore, NativeLib(), ratchetKeyStore)
}
