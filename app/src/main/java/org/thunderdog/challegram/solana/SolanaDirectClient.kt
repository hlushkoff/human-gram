package org.thunderdog.challegram.solana

import android.content.Context
import io.spectralcore.DeviceIdentityService.DeviceAccountInfo
import io.spectralcore.solana.SolanaRpc
import io.spectralcore.solana.TxResult

/**
 * Backward-compatible façade over spectral-core services — direct counterpart of
 * ondo-zero-android's SolanaDirectClient, scoped to the wallet/identity surface
 * that Human Gram exposes in the Ondo-Zero menu.
 *
 *   DeviceIdentityService  — device registration, handover (migration)
 *   ChallengeProofService  — challenge-response identity proof
 *   SolanaRpc              — SOL / token balances, token metadata
 *   TokenTransferService   — PRC token transfers (Token-2022 + TEE)
 */
object SolanaDirectClient {

    // ── Balances & metadata (read-only) ───────────────────────────────────────

    @JvmStatic
    fun fetchSolBalance(rpcUrl: String, pubkeyBase58: String): Double? =
        SolanaRpc.fetchSolBalance(rpcUrl, pubkeyBase58)

    @JvmStatic
    fun fetchTokenBalance(rpcUrl: String, ownerBase58: String, mintBase58: String): Long? =
        SolanaRpc.fetchTokenBalance(rpcUrl, ownerBase58, mintBase58)

    @JvmStatic
    fun fetchTokenMetadataName(rpcUrl: String, mintBase58: String): String? =
        SolanaRpc.fetchTokenMetadataName(rpcUrl, mintBase58)

    // ── Device identity (on-chain registry) ───────────────────────────────────

    /** Returns on-chain DeviceAccount info, or null if device is not registered. Never throws. */
    @JvmStatic
    fun fetchDeviceAccount(rpcUrl: String, programIdB58: String, gshBytes: ByteArray): DeviceAccountInfo? =
        OndoZeroDependencies.deviceIdentityService.fetchDeviceAccount(rpcUrl, programIdB58, gshBytes)

    /**
     * Returns the 32-byte GSH bound to [walletPubkey] in WalletRegistry,
     * or null if the wallet is not registered. Never throws.
     */
    @JvmStatic
    fun fetchWalletRegistryGsh(rpcUrl: String, programIdB58: String, walletPubkey: ByteArray): ByteArray? =
        OndoZeroDependencies.deviceIdentityService.fetchWalletRegistryGsh(rpcUrl, programIdB58, walletPubkey)

    @JvmStatic
    fun registerDevice(
        rpcUrl: String, programIdB58: String,
        wallet: SolanaWallet.Keypair, gshBytes: ByteArray, context: Context
    ): TxResult {
        val authHashTip = OndoZeroDependencies.ratchetKeyStore.generateAndPersistChain(context)
        val libWallet = io.spectralcore.SolanaWallet.Keypair(wallet.publicKey, wallet.privateKey)
        return OndoZeroDependencies.deviceIdentityService.registerDevice(
            rpcUrl, programIdB58, libWallet, gshBytes, context, authHashTip
        )
    }

    @JvmStatic
    fun refreshDeviceAuthorization(
        rpcUrl: String,
        programIdB58: String,
        wallet: SolanaWallet.Keypair,
        context: Context,
        gshBytes: ByteArray,
    ): TxResult {
        val libWallet = io.spectralcore.SolanaWallet.Keypair(wallet.publicKey, wallet.privateKey)
        return OndoZeroDependencies.challengeProofService.refreshDeviceAuthorization(
            rpcUrl, programIdB58, libWallet, context, gshBytes
        )
    }

    // ── Device migration (handover) ───────────────────────────────────────────

    @JvmStatic
    fun startHandover(
        rpcUrl: String,
        programIdB58: String,
        wallet: SolanaWallet.Keypair,
        oldGshBytes: ByteArray,
        newGshBytes: ByteArray,
        context: Context,
    ): TxResult {
        val libWallet = io.spectralcore.SolanaWallet.Keypair(wallet.publicKey, wallet.privateKey)
        return OndoZeroDependencies.deviceIdentityService.startHandover(
            rpcUrl, programIdB58, libWallet, oldGshBytes, newGshBytes,
            authPreimage = OndoZeroDependencies.ratchetKeyStore.nextPreimage(context)
        )
    }

    // ── PRC token transfer ────────────────────────────────────────────────────

    /**
     * Transfer [amount] base-units of PRC token from [wallet] to [recipientB58].
     * Builds Ed25519 TEE pre-instruction + Token-2022 transferChecked with hook extra accounts.
     */
    @JvmStatic
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
    ): TxResult {
        val libWallet = io.spectralcore.SolanaWallet.Keypair(wallet.publicKey, wallet.privateKey)
        return TokenTransferService.transferPrc(
            rpcUrl, prcMintB58, hookProgB58, ondoZeroProgB58,
            libWallet, decimals, amount, recipientB58, context
        )
    }
}
