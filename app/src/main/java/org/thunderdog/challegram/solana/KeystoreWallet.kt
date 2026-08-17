package org.thunderdog.challegram.solana

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.SecureRandom

/**
 * Manages the Solana Ed25519 wallet keypair using Android Keystore-backed storage.
 *
 * Wallet creation/import mirrors ProfiCoinMobile WalletService.ts:
 *   - createWithMnemonic()     — generates 24-word BIP39 mnemonic, derives keypair via
 *                                 mnemonicToSeed()[0..32] (legacy32 scheme), stores securely.
 *                                 Returns the mnemonic so the UI can show it ONCE.
 *   - importFromMnemonic()     — validates + derives keypair the same legacy32 way.
 *   - generate()               — random 32-byte seed, no mnemonic (used by SolanaWallet shim).
 *
 * Private key (32-byte Ed25519 seed) is stored in EncryptedSharedPreferences backed by
 * Android Keystore AES-256-GCM (hardware-backed where available, API 23+).
 */
object KeystoreWallet {

    private const val TAG = "KeystoreWallet"
    private const val PREFS_FILE = "ondozero_secure_wallet"
    private const val KEY_PRIVATE_SEED_HEX = "ed25519_seed_hex"
    private const val KEY_PUBLIC_HEX = "ed25519_pub_hex"

    data class Keypair(
        val publicKey: ByteArray,    // 32 bytes Ed25519 public key
        val privateSeed: ByteArray   // 32 bytes Ed25519 seed
    ) {
        val publicKeyBase58: String get() = Base58.encode(publicKey)
    }

    // ── Query ─────────────────────────────────────────────────────────────

    @JvmStatic
    fun hasWallet(context: Context): Boolean =
        prefsOrNull(context)?.contains(KEY_PRIVATE_SEED_HEX) == true

    @JvmStatic
    fun load(context: Context): Keypair? {
        val seedHex = prefsOrNull(context)?.getString(KEY_PRIVATE_SEED_HEX, null) ?: return null
        return keypairFromSeed(fromHex(seedHex))
    }

    // ── Create ────────────────────────────────────────────────────────────

    /**
     * Generates a fresh 24-word BIP39 mnemonic, derives the Solana keypair using the
     * ProfiCoinMobile legacy32 scheme (BIP39 seed → first 32 bytes → Ed25519), and stores
     * the private seed securely.
     *
     * @return Pair of (keypair, mnemonic). The mnemonic must be shown to the user ONCE
     *         and is NOT stored anywhere — only the derived private seed is kept.
     */
    @JvmStatic
    fun createWithMnemonic(context: Context): Pair<Keypair, String> {
        val mnemonic = Bip39.generateMnemonic(24)
        val kp       = keypairFromMnemonic(mnemonic)
        save(context, kp)
        Log.i(TAG, "Wallet created (mnemonic): ${kp.publicKeyBase58}")
        return kp to mnemonic
    }

    /**
     * Derives a Solana keypair from a BIP39 seed phrase and stores it securely.
     * Exactly mirrors ProfiCoinMobile `importWalletFromMnemonic` / `keypairFromMnemonicLegacySeed32`.
     *
     * @throws IllegalArgumentException if the phrase is invalid or the wrong word count.
     */
    @JvmStatic
    fun importFromMnemonic(context: Context, mnemonicRaw: String): Keypair {
        val mnemonic = Bip39.normalizeMnemonic(mnemonicRaw)
        if (!Bip39.validateMnemonic(mnemonic)) {
            throw IllegalArgumentException("Invalid seed phrase. Check all words and try again.")
        }
        val kp = keypairFromMnemonic(mnemonic)
        save(context, kp)
        Log.i(TAG, "Wallet imported (mnemonic): ${kp.publicKeyBase58}")
        return kp
    }

    /**
     * Generates a random keypair (no mnemonic) and stores it.
     * Used internally by SolanaWallet shim for backward compatibility.
     */
    @JvmStatic
    fun generate(context: Context): Keypair {
        val seed = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val kp   = keypairFromSeed(seed)
        save(context, kp)
        Log.i(TAG, "Wallet generated (random): ${kp.publicKeyBase58}")
        return kp
    }

    // ── Delete ────────────────────────────────────────────────────────────

    @JvmStatic
    fun wipe(context: Context) {
        prefsOrNull(context)?.edit()?.let { editor ->
            editor
                .remove(KEY_PRIVATE_SEED_HEX)
                .remove(KEY_PUBLIC_HEX)
                .commit()
        }
        Log.i(TAG, "Wallet wiped")
    }

    // ── Signing ──────────────────────────────────────────────────────────

    @JvmStatic
    fun sign(kp: Keypair, message: ByteArray): ByteArray =
        SolanaWallet.sign(kp.privateSeed, message)

    // ── Internal crypto ──────────────────────────────────────────────────

    /**
     * Derives Ed25519 seed from mnemonic using ProfiCoinMobile legacy32 scheme:
     *   seed64 = PBKDF2-HMAC-SHA512(mnemonic, "mnemonic", 2048 rounds)
     *   seed32 = seed64[0..32)   ← this becomes the Ed25519 private key seed
     */
    internal fun keypairFromMnemonic(mnemonic: String): Keypair {
        val seed64 = Bip39.mnemonicToSeed(mnemonic)
        val seed32 = seed64.copyOfRange(0, 32)
        return keypairFromSeed(seed32)
    }

    /**
     * Public mnemonic → keypair derivation for import preview (no storage side effects).
     * Expects an already-normalized and validated mnemonic.
     */
    @JvmStatic
    fun deriveKeypair(normalizedMnemonic: String): Keypair = keypairFromMnemonic(normalizedMnemonic)

    internal fun keypairFromSeed(seed: ByteArray): Keypair {
        require(seed.size == 32) { "Seed must be exactly 32 bytes, got ${seed.size}" }
        val lib = SolanaWallet.keypairFromSeed(seed)
        return Keypair(publicKey = lib.publicKey, privateSeed = seed)
    }

    // ── Storage helpers ──────────────────────────────────────────────────

    private fun prefsOrThrow(context: Context): SharedPreferences =
        prefsOrNull(context) ?: throw IllegalStateException("Encrypted wallet storage is unavailable")

    private fun prefsOrNull(context: Context): SharedPreferences? = try {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_FILE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (error: Exception) {
        Log.e(TAG, "Failed to open encrypted wallet storage", error)
        null
    }

    private fun save(context: Context, kp: Keypair) {
        prefsOrThrow(context).edit()
            .putString(KEY_PRIVATE_SEED_HEX, kp.privateSeed.toHex())
            .putString(KEY_PUBLIC_HEX,        kp.publicKey.toHex())
            .commit()
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }
    private fun fromHex(s: String) = ByteArray(s.length / 2) { i ->
        s.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
