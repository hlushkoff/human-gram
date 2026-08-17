package org.thunderdog.challegram.solana

import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * Minimal BIP39 implementation for OndoZero / Solana wallet management.
 *
 * Matches the behaviour of the `bip39` npm package used in ProfiCoinMobile:
 *   - generateMnemonic(256)  → 24 words
 *   - validateMnemonic(mnemonic)
 *   - mnemonicToSeed(mnemonic, passphrase="")  → 64 bytes via PBKDF2-HMAC-SHA512
 *
 * The "legacy" Solana keypair derivation is: seed = mnemonicToSeed()[0..32]
 * (matches `keypairFromMnemonicLegacySeed32` in ProfiCoinMobile WalletService.ts)
 */
object Bip39 {

    /**
     * Generates a new BIP39 mnemonic.
     * @param wordCount 12 (128-bit entropy) or 24 (256-bit entropy). ProfiCoinMobile uses 24.
     */
    fun generateMnemonic(wordCount: Int = 24): String {
        val entropyBytes = when (wordCount) {
            12 -> 16   // 128 bits
            24 -> 32   // 256 bits
            else -> throw IllegalArgumentException("wordCount must be 12 or 24, got $wordCount")
        }
        val entropy = ByteArray(entropyBytes)
        SecureRandom().nextBytes(entropy)
        return entropyToMnemonic(entropy)
    }

    /**
     * Returns true when the mnemonic is a valid BIP39 phrase (all words known + checksum OK).
     */
    fun validateMnemonic(mnemonicRaw: String): Boolean {
        val words = normalizeMnemonic(mnemonicRaw).split(" ")
        if (words.size != 12 && words.size != 24) return false
        val wordSet = Bip39WordList.WORDS.toHashSet()
        if (words.any { it !in wordSet }) return false
        return try {
            mnemonicToEntropy(words)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Derives a 64-byte seed from a mnemonic via PBKDF2-HMAC-SHA512 (2048 iterations).
     * Equivalent to the async `bip39.mnemonicToSeed(mnemonic)` in the npm package.
     */
    fun mnemonicToSeed(mnemonicRaw: String, passphrase: String = ""): ByteArray {
        val mnemonic = normalizeMnemonic(mnemonicRaw)
        val salt = "mnemonic$passphrase"
        val spec = PBEKeySpec(
            mnemonic.toCharArray(),
            salt.toByteArray(Charsets.UTF_8),
            2048,
            512   // key length in bits → 64 bytes
        )
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512")
            .generateSecret(spec)
            .encoded
    }

    /** Normalises whitespace and lowercases (matches `normalizeMnemonic` in WalletService.ts). */
    fun normalizeMnemonic(mnemonicRaw: String): String =
        mnemonicRaw.trim().lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    // ── Private helpers ────────────────────────────────────────────────────

    /** Converts raw entropy bytes to a space-separated BIP39 mnemonic. */
    private fun entropyToMnemonic(entropy: ByteArray): String {
        val checksumBits = entropy.size * 8 / 32     // ENT/32
        val checksum = MessageDigest.getInstance("SHA-256").digest(entropy)

        // Build full bit array: entropy bits + first checksumBits of SHA256 hash
        val totalBits = entropy.size * 8 + checksumBits
        val bits = BooleanArray(totalBits)
        for (i in entropy.indices) {
            for (j in 0 until 8) {
                bits[i * 8 + j] = (entropy[i].toInt() ushr (7 - j)) and 1 == 1
            }
        }
        for (i in 0 until checksumBits) {
            bits[entropy.size * 8 + i] = (checksum[i / 8].toInt() ushr (7 - i % 8)) and 1 == 1
        }

        // Group into 11-bit chunks → word indices
        val wordCount = totalBits / 11
        return (0 until wordCount).joinToString(" ") { wi ->
            var idx = 0
            for (j in 0 until 11) {
                idx = (idx shl 1) or (if (bits[wi * 11 + j]) 1 else 0)
            }
            Bip39WordList.WORDS[idx]
        }
    }

    /**
     * Converts word list back to entropy bytes, validating checksum.
     * Throws [IllegalArgumentException] on checksum failure or unknown word.
     */
    private fun mnemonicToEntropy(words: List<String>): ByteArray {
        val wordIndex = HashMap<String, Int>(2048).also {
            Bip39WordList.WORDS.forEachIndexed { i, w -> it[w] = i }
        }

        val totalBits = words.size * 11
        val bits = BooleanArray(totalBits)
        for ((wi, word) in words.withIndex()) {
            val idx = wordIndex[word]
                ?: throw IllegalArgumentException("Unknown BIP39 word: \"$word\"")
            for (j in 0 until 11) {
                bits[wi * 11 + j] = (idx ushr (10 - j)) and 1 == 1
            }
        }

        val checksumBits = totalBits / 33
        val entropyBits  = totalBits - checksumBits
        val entropy = ByteArray(entropyBits / 8)
        for (i in entropy.indices) {
            var b = 0
            for (j in 0 until 8) {
                if (bits[i * 8 + j]) b = b or (1 shl (7 - j))
            }
            entropy[i] = b.toByte()
        }

        // Verify checksum
        val checksum = MessageDigest.getInstance("SHA-256").digest(entropy)
        for (i in 0 until checksumBits) {
            val expected = (checksum[i / 8].toInt() ushr (7 - i % 8)) and 1 == 1
            if (bits[entropyBits + i] != expected) {
                throw IllegalArgumentException("BIP39 checksum mismatch")
            }
        }
        return entropy
    }
}
