package org.thunderdog.challegram.solana

import android.content.Context
import android.util.Log
import io.spectralcore.Base58
import io.spectralcore.SolanaWallet
import io.spectralcore.solana.*
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * ProfiCoin (PRC) token transfer via Token-2022 with prc-transfer-hook.
 * Direct port of ondo-zero-android's TokenTransferService — identical transaction
 * layout and TEE message, only the dependency holder differs (OndoZeroDependencies).
 *
 * Transaction layout (instructions in order):
 *   [0] createAssociatedTokenAccount  ← only if dest ATA doesn't exist on-chain
 *   [1] Ed25519SigVerify              ← proof of TEE key, MUST be immediately before transferChecked
 *   [2] transferChecked               ← Token-2022 with 4 extra accounts from hook
 *
 * Extra accounts appended to transferChecked (indices 4..9):
 *   [4] transfer hook program             (prc-transfer-hook)
 *   [5] ExtraAccountMetaList PDA          ["extra-account-metas", mint] on hook program
 *   [6] config PDA           Extra[0]     ["config"] on hook program
 *   [7] ondo_zero_registry    Extra[1]     program ID (used to derive WalletRegistry PDA)
 *   [8] WalletRegistry PDA   Extra[2]     ["wallet_reg", authority] on ondo_zero_registry
 *   [9] sysvar::instructions Extra[3]     required by hook for Ed25519 lookup
 *
 * TEE message = SHA256("sz_transfer_v1" || amount_u64_le || recipient_wallet_pubkey)
 * This matches exactly what the hook's enforce_device_auth() computes on-chain.
 */
object TokenTransferService {

    private const val TAG = "TokenTransferService"

    // Well-known Solana constants
    private val TOKEN_2022_PROGRAM = Base58.decode("TokenzQdBNbLqP5VEhdkAS6EPFLC1PHnBqCXEpPxuEb")
    private val ATA_PROGRAM        = Base58.decode("ATokenGPvbdGVxr1b2hvZbsiqW5xWH25efTNsLJA8knL")

    /**
     * Transfer [amount] base-units of PRC token to [recipientB58].
     */
    fun transferPrc(
        rpcUrl: String,
        prcMintB58: String,
        hookProgB58: String,
        ondoZeroProgB58: String,
        wallet: SolanaWallet.Keypair,
        decimals: Int,
        amount: Long,
        recipientB58: String,
        context: Context
    ): TxResult = runCatching {
        require(amount > 0) { "amount must be positive" }
        val mint        = Base58.decode(prcMintB58)
        val hookProg    = Base58.decode(hookProgB58)
        val ondoZeroProg = Base58.decode(ondoZeroProgB58)
        val recipient   = Base58.decode(recipientB58)
        val sender      = wallet.publicKey

        // ── ATA addresses ─────────────────────────────────────────────────────
        val sourceAta = findAta(sender, mint)
        val destAta   = findAta(recipient, mint)
        Log.d(TAG, "sender=${Base58.encode(sender)} recipient=${Base58.encode(recipient)}")
        Log.d(TAG, "sourceAta=${Base58.encode(sourceAta)} destAta=${Base58.encode(destAta)}")

        // ── Dest ATA existence check ──────────────────────────────────────────
        val destAtaExists = runCatching {
            SolanaRpc.getAccountData(rpcUrl, Base58.encode(destAta)).isNotEmpty()
        }.getOrDefault(false)

        // ── PDAs ──────────────────────────────────────────────────────────────
        // ExtraAccountMetaList PDA ("validation state") — standard SPL hook seeds
        val metaListPda  = requirePda(
            SolanaTx.findPda(
                listOf("extra-account-metas".toByteArray(Charsets.UTF_8), mint),
                hookProg
            ),
            "meta list PDA",
        )
        // Hook config PDA
        val configPda    = requirePda(
            SolanaTx.findPda(
                listOf("config".toByteArray(Charsets.UTF_8)),
                hookProg
            ),
            "hook config PDA",
        )
        // WalletRegistry PDA for sender — ondo-zero-registry program
        val idSvc        = OndoZeroDependencies.deviceIdentityService
        val walletRegPda = requirePda(
            SolanaTx.findPda(idSvc.pdaWalletRegistry(sender), ondoZeroProg),
            "wallet registry PDA",
        )
        Log.d(TAG, "metaList=${Base58.encode(metaListPda)} config=${Base58.encode(configPda)} walletReg=${Base58.encode(walletRegPda)}")

        // ── TEE signing ───────────────────────────────────────────────────────
        // Message = SHA256("sz_transfer_v1" || amount_u64_le || recipient_pubkey)
        val amountLE   = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(amount).array()
        val teeMessage = sha256("sz_transfer_v1", amountLE, recipient)   // 32 bytes

        val teeKey     = OndoZeroDependencies.deviceTeeKey
        val teePubkey  = teeKey.publicKeyBytes(context, wallet.privateKey)          // 32 bytes
        val teeSig     = teeKey.sign(context, wallet.privateKey, teeMessage)        // 64 bytes
        Log.d(TAG, "teePubkey=${toHex(teePubkey)}")

        val ed25519IxData = buildEd25519VerifyIxData(teePubkey, teeMessage, teeSig)

        // ── Build instruction list ─────────────────────────────────────────────
        // Order: [createATA?]  [Ed25519]  [transferChecked]
        // Ed25519 MUST be at index (transferChecked_index - 1).
        val ixes = mutableListOf<Instruction>()

        if (!destAtaExists) {
            ixes += buildCreateAtaIx(sender, recipient, mint)
            Log.d(TAG, "Adding createATA for dest (index ${ixes.size - 1})")
        }

        // Ed25519 goes immediately before transferChecked
        ixes += Instruction(ED25519_PROGRAM, emptyList(), ed25519IxData)
        Log.d(TAG, "Ed25519 at index ${ixes.size - 1}")

        ixes += buildTransferCheckedIx(
            sourceAta   = sourceAta,
            mint        = mint,
            destAta     = destAta,
            authority   = sender,
            hookProg    = hookProg,
            metaListPda = metaListPda,
            configPda   = configPda,
            ondoZeroProg = ondoZeroProg,
            walletRegPda = walletRegPda,
            amount      = amount,
            decimals    = decimals.toByte()
        )
        Log.d(TAG, "transferChecked at index ${ixes.size - 1}")

        val txBase64 = buildAndSignTx(rpcUrl, sender, ixes, listOf(wallet.privateKey))
        val sig      = SolanaRpc.sendTransaction(rpcUrl, txBase64)
        Log.i(TAG, "transferPrc confirmed: $sig")
        TxResult(ok = true, signature = sig)

    }.getOrElse { e ->
        Log.e(TAG, "transferPrc failed", e)
        TxResult(ok = false, errorDetail = "${e.javaClass.simpleName}: ${e.message}")
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    /**
     * Derive Associated Token Account address.
     * Seeds: [wallet, token_program, mint] — program: ATA_PROGRAM
     */
    private fun findAta(wallet: ByteArray, mint: ByteArray): ByteArray =
        SolanaTx.findPda(listOf(wallet, TOKEN_2022_PROGRAM, mint), ATA_PROGRAM)
            .getOrElse { cause -> throw IllegalStateException("ATA PDA derivation failed: ${cause.message}", cause) }

    private fun requirePda(result: Result<ByteArray>, label: String): ByteArray {
        return result.getOrElse { cause ->
            throw IllegalStateException("$label derivation failed: ${cause.message}", cause)
        }
    }

    /**
     * Build createAssociatedTokenAccount instruction.
     * Uses instruction discriminator 0x00 (create, fails if exists).
     * Only called when dest ATA does not yet exist.
     */
    private fun buildCreateAtaIx(payer: ByteArray, recipient: ByteArray, mint: ByteArray): Instruction {
        val ata = findAta(recipient, mint)
        return Instruction(
            programId = ATA_PROGRAM,
            accounts  = listOf(
                AccountMeta(payer,              writable = true,  signer = true ),  // 0: fee payer
                AccountMeta(ata,                writable = true,  signer = false),  // 1: ATA to create
                AccountMeta(recipient,          writable = false, signer = false),  // 2: ATA owner
                AccountMeta(mint,               writable = false, signer = false),  // 3: mint
                AccountMeta(SYSTEM_PROGRAM,     writable = false, signer = false),  // 4: system program
                AccountMeta(TOKEN_2022_PROGRAM, writable = false, signer = false),  // 5: token program
            ),
            data = byteArrayOf(0)  // instruction discriminator: 0 = create
        )
    }

    /**
     * Build Token-2022 transferChecked instruction with all extra accounts
     * required by the prc-transfer-hook.
     *
     * Instruction data: [12u8 | amount_u64_le | decimals_u8] (10 bytes)
     */
    private fun buildTransferCheckedIx(
        sourceAta: ByteArray,
        mint: ByteArray,
        destAta: ByteArray,
        authority: ByteArray,
        hookProg: ByteArray,
        metaListPda: ByteArray,
        configPda: ByteArray,
        ondoZeroProg: ByteArray,
        walletRegPda: ByteArray,
        amount: Long,
        decimals: Byte
    ): Instruction {
        val data = ByteBuffer.allocate(10).order(ByteOrder.LITTLE_ENDIAN)
            .put(12.toByte())   // TransferChecked instruction type
            .putLong(amount)    // amount u64 LE
            .put(decimals)      // decimals u8
            .array()

        return Instruction(
            programId = TOKEN_2022_PROGRAM,
            accounts  = listOf(
                AccountMeta(sourceAta,           writable = true,  signer = false), // 0: source ATA
                AccountMeta(mint,                writable = true,  signer = false), // 1: mint (writable: fee tracking)
                AccountMeta(destAta,             writable = true,  signer = false), // 2: dest ATA
                AccountMeta(authority,           writable = false, signer = true),  // 3: authority (signer)
                AccountMeta(hookProg,            writable = false, signer = false), // 4: transfer hook program
                AccountMeta(metaListPda,         writable = false, signer = false), // 5: ExtraAccountMetaList PDA
                AccountMeta(configPda,           writable = false, signer = false), // 6: config PDA      [Extra 0]
                AccountMeta(ondoZeroProg,         writable = false, signer = false), // 7: ondo_zero_registry [Extra 1]
                AccountMeta(walletRegPda,        writable = false, signer = false), // 8: WalletRegistry PDA [Extra 2]
                AccountMeta(SYSVAR_INSTRUCTIONS, writable = false, signer = false), // 9: sysvar::instructions [Extra 3]
            ),
            data = data
        )
    }

    private fun sha256(prefix: String, vararg parts: ByteArray): ByteArray {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(prefix.toByteArray(Charsets.UTF_8))
        for (p in parts) md.update(p)
        return md.digest()
    }

    private fun buildAndSignTx(
        rpcUrl: String,
        feePayer: ByteArray,
        instructions: List<Instruction>,
        signerKeys: List<ByteArray>
    ): String {
        val blockhash  = SolanaRpc.getLatestBlockhash(rpcUrl)
        val message    = SolanaTx.buildMessage(feePayer, instructions, blockhash)
        val signatures = signerKeys.map { SolanaWallet.sign(it, message) }
        val tx         = SolanaTx.buildTransaction(signatures, message)
        return android.util.Base64.encodeToString(tx, android.util.Base64.NO_WRAP)
    }

    private fun toHex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }
}
