package org.thunderdog.challegram.solana

import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.Toast
import org.thunderdog.challegram.R
import org.thunderdog.challegram.tool.UI

object SolanaWalletUi {
    private const val PREFS = "human_gram_solana_wallet"
    private const val KEY_ONBOARDING_SHOWN = "onboarding_shown"

    @JvmStatic
    fun hasWallet(context: Context): Boolean = KeystoreWallet.hasWallet(context)

    @JvmStatic
    fun isOnboardingShown(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ONBOARDING_SHOWN, false)

    @JvmStatic
    fun markOnboardingShown(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_SHOWN, true)
            .apply()
    }

    @JvmStatic
    fun getWalletLabel(context: Context): String {
        val kp = KeystoreWallet.load(context) ?: return "Inactive"
        val pub = kp.publicKeyBase58
        if (pub.length <= 14) return pub
        return pub.substring(0, 7) + "..." + pub.substring(pub.length - 7)
    }

    @JvmStatic
    fun getWalletPublicKey(context: Context): String? =
        KeystoreWallet.load(context)?.publicKeyBase58

    /**
     * Derives a keypair from a SID phrase WITHOUT saving it — used to preview
     * on-chain binding state before committing to an import (mirrors Ondo-Zero Video).
     */
    @JvmStatic
    fun deriveKeypairFromMnemonic(mnemonicRaw: String): KeystoreWallet.Keypair {
        val mnemonic = Bip39.normalizeMnemonic(mnemonicRaw)
        if (!Bip39.validateMnemonic(mnemonic)) {
            throw IllegalArgumentException("Invalid seed phrase. Check all words and try again.")
        }
        return KeystoreWallet.deriveKeypair(mnemonic)
    }

    /**
     * Re-initializes the TEE device key after wallet create/import/wipe,
     * exactly like Ondo-Zero Video does (deviceTeeKey.clearLocalState + getOrGenerate).
     * Must be called off the main thread (Keystore ops can block).
     */
    @JvmStatic
    fun reinitDeviceTeeKey(context: Context, kp: KeystoreWallet.Keypair) {
        val teeKey = OndoZeroDependencies.deviceTeeKey
        teeKey.clearLocalState(context)
        teeKey.getOrGenerate(context, kp.privateSeed)
    }

    /**
     * Creates a wallet and re-initializes the TEE key. Returns keypair + SID phrase.
     * Caller is responsible for overwrite warnings / token guards BEFORE calling this.
     */
    @JvmStatic
    fun createWalletWithTee(context: Context): Pair<KeystoreWallet.Keypair, String> {
        val (kp, mnemonic) = KeystoreWallet.createWithMnemonic(context)
        reinitDeviceTeeKey(context, kp)
        return kp to mnemonic
    }

    /**
     * Imports a wallet from a SID phrase and re-initializes the TEE key.
     */
    @JvmStatic
    fun importWalletWithTee(context: Context, mnemonicRaw: String): KeystoreWallet.Keypair {
        val kp = KeystoreWallet.importFromMnemonic(context, mnemonicRaw)
        reinitDeviceTeeKey(context, kp)
        return kp
    }

    /**
     * Shows the one-time SID phrase dialog after wallet creation.
     */
    @JvmStatic
    fun showCreatedPhraseDialog(context: Context, mnemonic: String, onDone: Runnable? = null) {
        val message = context.getString(R.string.OndoZeroWalletCreatedMessage, mnemonic)
        AlertDialog.Builder(context)
            .setTitle(R.string.OndoZeroCreateWallet)
            .setMessage(message)
            .setCancelable(false)
            .setPositiveButton(R.string.Copy) { _, _ ->
                copyToClipboard(context, "SID phrase", mnemonic)
                UI.showToast(R.string.CopiedText, Toast.LENGTH_SHORT)
                onDone?.run()
            }
            .setNegativeButton(android.R.string.ok) { _, _ ->
                onDone?.run()
            }
            .show()
    }

    @JvmStatic
    fun showCreateWalletDialog(context: Context, onWalletChanged: Runnable? = null) {
        try {
            val (_, mnemonic) = createWalletWithTee(context)
            showCreatedPhraseDialog(context, mnemonic, onWalletChanged)
        } catch (error: Exception) {
            UI.showToast(error.message ?: "Wallet create failed", Toast.LENGTH_LONG)
        }
    }

    @JvmStatic
    fun showImportWalletDialog(context: Context, onWalletChanged: Runnable? = null) {
        val input = EditText(context)
        input.hint = context.getString(R.string.OndoZeroImportWalletHint)
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        input.minLines = 2
        input.maxLines = 5

        AlertDialog.Builder(context)
            .setTitle(R.string.OndoZeroImportWallet)
            .setView(input)
            .setPositiveButton(R.string.OndoZeroImportWallet) { _, _ ->
                val phrase = input.text?.toString().orEmpty()
                try {
                    importWalletWithTee(context, phrase)
                    UI.showToast(R.string.OndoZeroWalletImported, Toast.LENGTH_SHORT)
                    onWalletChanged?.run()
                } catch (error: Exception) {
                    UI.showToast(error.message ?: "Invalid SID phrase", Toast.LENGTH_LONG)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    @JvmStatic
    fun showSendTokensDialog(context: Context) {
        if (!hasWallet(context)) {
            UI.showToast(R.string.OndoZeroWalletActivateFirst, Toast.LENGTH_SHORT)
            return
        }
        UI.showToast(R.string.OndoZeroSendTokensNotImplemented, Toast.LENGTH_LONG)
    }

    @JvmStatic
    fun showMigrationDialog(context: Context) {
        if (!hasWallet(context)) {
            UI.showToast(R.string.OndoZeroWalletActivateFirst, Toast.LENGTH_SHORT)
            return
        }
        UI.showToast(R.string.OndoZeroMigrationNotImplemented, Toast.LENGTH_LONG)
    }

    @JvmStatic
    fun showFirstRunOnboarding(context: Context, onWalletChanged: Runnable? = null) {
        if (isOnboardingShown(context) || hasWallet(context)) return

        AlertDialog.Builder(context)
            .setTitle(R.string.OndoZeroWalletOnboardingTitle)
            .setMessage(R.string.OndoZeroWalletOnboardingBody)
            .setPositiveButton(R.string.OndoZeroCreateWallet) { _, _ ->
                markOnboardingShown(context)
                showCreateWalletDialog(context, onWalletChanged)
            }
            .setNeutralButton(R.string.OndoZeroImportWallet) { _, _ ->
                markOnboardingShown(context)
                showImportWalletDialog(context, onWalletChanged)
            }
            .setNegativeButton(R.string.OndoZeroLater) { _, _ ->
                markOnboardingShown(context)
            }
            .setOnCancelListener {
                markOnboardingShown(context)
            }
            .show()
    }

    private fun copyToClipboard(context: Context, label: String, value: String) {
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    }
}
