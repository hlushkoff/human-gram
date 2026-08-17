package org.thunderdog.challegram.solana

/**
 * Key attestation state for TEE-backed signing operations.
 */
enum class KeyAttestationState {
    HARDWARE_BACKED,
    SOFTWARE_ONLY,
    UNKNOWN,
}

/**
 * Result of a TEE signature operation.
 */
data class TeeSignatureResult(
    val hardwareBacked: Boolean,
    val signatureBytes: ByteArray,
    val keyAttestationState: KeyAttestationState,
)
