package org.thunderdog.challegram.solana

import android.content.Context
import android.util.Log

object AppWallet {
    private const val TAG = "AppWallet"

    @JvmStatic
    fun get(context: Context): SolanaWallet.Keypair? {
        val kp = KeystoreWallet.load(context) ?: return null
        return SolanaWallet.Keypair(publicKey = kp.publicKey, privateKey = kp.privateSeed)
    }

    @JvmStatic
    fun getOrCreate(context: Context): SolanaWallet.Keypair {
        val kp = KeystoreWallet.load(context)
            ?: KeystoreWallet.generate(context).also {
                Log.i(TAG, "Auto-generated wallet (first launch)")
            }
        return SolanaWallet.Keypair(publicKey = kp.publicKey, privateKey = kp.privateSeed)
    }

    @JvmStatic
    fun sign(privateKeyBytes: ByteArray, message: ByteArray): ByteArray =
        SolanaWallet.sign(privateKeyBytes, message)

    @JvmStatic
    fun generateKeypair(): SolanaWallet.Keypair {
        val seed = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        return SolanaWallet.keypairFromSeed(seed)
    }
}
