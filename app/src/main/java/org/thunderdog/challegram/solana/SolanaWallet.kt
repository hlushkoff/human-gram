package org.thunderdog.challegram.solana

import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/**
 * Thin signing shim used by wallet helpers.
 * Keys are passed in from caller; this object does not persist key material.
 */
object SolanaWallet {

    data class Keypair(val publicKey: ByteArray, val privateKey: ByteArray) {
        val publicKeyBase58: String get() = Base58.encode(publicKey)
    }

    fun sign(privateKeyBytes: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(privateKeyBytes))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun keypairFromSeed(seed: ByteArray): Keypair {
        require(seed.size == 32) { "Ed25519 seed must be exactly 32 bytes" }
        val privateKey = Ed25519PrivateKeyParameters(seed)
        val publicKey = privateKey.generatePublicKey().encoded
        return Keypair(publicKey = publicKey, privateKey = seed)
    }
}
