/*
 * This file is a part of HumanGram
 * Copyright © 2014 (tgx-android@pm.me)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Ondo-Zero settings screen: device identity (Local Device Name, Spectral ID),
 * wallet (balances, create/import/send/migrate) and video recording settings.
 * Functionally mirrors Ondo-Zero Video's SettingsScreen on top of spectral-core.
 */
package org.thunderdog.challegram.ui;

import android.content.Context;
import android.view.View;
import android.widget.Toast;

import androidx.collection.SparseArrayCompat;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.R;
import org.thunderdog.challegram.component.base.SettingView;
import org.thunderdog.challegram.core.Lang;
import org.thunderdog.challegram.data.TD;
import org.thunderdog.challegram.data.TGMessage;
import org.thunderdog.challegram.solana.CameraSettings;
import org.thunderdog.challegram.solana.KeystoreWallet;
import org.thunderdog.challegram.solana.LocalDeviceNameRules;
import org.thunderdog.challegram.solana.LocalDeviceNameStore;
import org.thunderdog.challegram.solana.OndoZeroIdentity;
import org.thunderdog.challegram.solana.SolanaConfig;
import org.thunderdog.challegram.solana.SolanaDirectClient;
import org.thunderdog.challegram.solana.SolanaWalletUi;
import org.thunderdog.challegram.telegram.Tdlib;
import org.thunderdog.challegram.theme.Theme;
import org.thunderdog.challegram.tool.UI;
import org.thunderdog.challegram.util.text.Text;
import org.thunderdog.challegram.util.text.TextColorSets;
import org.thunderdog.challegram.util.text.TextWrapper;
import org.thunderdog.challegram.v.CustomRecyclerView;
import org.thunderdog.challegram.widget.SliderWrapView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.spectralcore.DeviceIdentityService;
import io.spectralcore.solana.TxResult;
import me.vkryl.core.StringUtils;
import tgx.td.Td;

public class SettingsOndoZeroController extends RecyclerViewController<Void> implements View.OnClickListener {

  private static final double TOKEN_BASE_UNITS = 1_000_000_000.0; // PRC decimals = 9

  public SettingsOndoZeroController (Context context, Tdlib tdlib) {
    super(context, tdlib);
  }

  @Override
  public int getId () {
    return R.id.controller_ondoZero;
  }

  @Override
  public CharSequence getName () {
    return Lang.getString(R.string.OndoZeroMenuTitle);
  }

  // ── State ────────────────────────────────────────────────────────────────

  private SettingsAdapter adapter;
  private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "OndoZero-IO");
    t.setDaemon(true);
    return t;
  });

  private String walletPubkey;          // null when no wallet
  private String spectralId;            // null while generating / unsupported
  private boolean spectralIdUnsupported;
  private String deviceName;            // null when not set

  private boolean balancesLoading;
  private Double solBalance;            // SOL
  private Long tokenBalance;            // PRC base units
  private String tokenName = SolanaConfig.UTILITY_TOKEN_FALLBACK_NAME;

  private boolean bindingLoading = true;
  private DeviceIdentityService.DeviceAccountInfo deviceInfo; // null = not registered
  private byte[] walletRegistryGsh;     // GSH bound to wallet on-chain, null when unbound

  private boolean actionInProgress;

  private boolean hasWallet () {
    return walletPubkey != null;
  }

  private boolean hasTokens () {
    return tokenBalance != null && tokenBalance > 0L;
  }

  // ── View ─────────────────────────────────────────────────────────────────

  @Override
  protected void onCreateView (Context context, CustomRecyclerView recyclerView) {
    adapter = new SettingsAdapter(this) {
      @Override
      protected void setValuedSetting (ListItem item, SettingView view, boolean isUpdate) {
        final int itemId = item.getId();
        if (itemId == R.id.btn_ondoZero_deviceName) {
          // Full value, never truncated (multiline row).
          view.setText(obtainWrapper(deviceName != null ? deviceName : Lang.getString(R.string.OndoZeroLocalDeviceNameNotSet), itemId));
        } else if (itemId == R.id.btn_ondoZero_spectralId) {
          final String sidText;
          if (spectralId != null) {
            sidText = spectralId;
          } else if (spectralIdUnsupported) {
            sidText = Lang.getString(R.string.OndoZeroSpectralIdUnsupported);
          } else {
            sidText = Lang.getString(R.string.OndoZeroSpectralIdGenerating);
          }
          view.setText(obtainWrapper(sidText, itemId));
        } else if (itemId == R.id.btn_ondoZero_deviceStatus) {
          if (bindingLoading) {
            view.setData(R.string.OndoZeroDeviceStatusChecking);
          } else if (deviceInfo != null && (deviceInfo.getStatus() == 3 || !deviceInfo.isActive())) {
            view.setData(R.string.OndoZeroDeviceStatusTransferred);
          } else if (deviceInfo != null) {
            view.setData(Lang.getString(R.string.OndoZeroDeviceStatusRegistered, SolanaConfig.CLUSTER));
          } else {
            view.setData(R.string.OndoZeroDeviceStatusNotRegistered);
          }
        } else if (itemId == R.id.btn_ondoZero_walletAddress) {
          view.setText(obtainWrapper(walletPubkey != null ? walletPubkey : "", itemId));
        } else if (itemId == R.id.btn_ondoZero_solBalance) {
          if (balancesLoading) {
            view.setData(R.string.OndoZeroBalanceLoading);
          } else if (solBalance != null) {
            view.setData(Lang.getString(R.string.OndoZeroSolBalanceValue, String.format(Locale.US, "%.6f", solBalance)));
          } else {
            view.setData(R.string.OndoZeroBalanceUnavailable);
          }
        } else if (itemId == R.id.btn_ondoZero_tokenBalance) {
          if (balancesLoading) {
            view.setData(R.string.OndoZeroBalanceLoading);
          } else {
            // No token balance (or fetch failed) — show explicit zero, never a dash.
            view.setData(String.format(Locale.US, "%.4f", (tokenBalance != null ? tokenBalance : 0L) / TOKEN_BASE_UNITS));
          }
        } else if (itemId == R.id.btn_wallet_send) {
          view.setEnabledAnimated(hasTokens(), isUpdate);
          view.setData(hasTokens() ? null : Lang.getString(R.string.OndoZeroSendDisabledHint));
        } else if (itemId == R.id.btn_wallet_migrate) {
          view.setEnabledAnimated(hasTokens(), isUpdate);
          view.setData(hasTokens() ? null : Lang.getString(R.string.OndoZeroMigrateDisabledHint));
        }
      }

      @Override
      protected void onSliderValueChanged (ListItem item, SliderWrapView view, int value, int oldValue) {
        if (item.getId() == R.id.btn_ondoZero_hashRate) {
          int hashesPerSec = CameraSettings.INSTANCE.getHASHES_PER_SEC_RANGE().getFirst() + value;
          CameraSettings.setHashesPerSecond(context, hashesPerSec);
        }
      }
    };
    recyclerView.setAdapter(adapter);
    refreshAll();
  }

  @Override
  public void destroy () {
    super.destroy();
    io.shutdownNow();
  }

  @Override
  public void onFocus () {
    super.onFocus();
    refreshAll();
  }

  // ── Data loading ─────────────────────────────────────────────────────────

  private void refreshAll () {
    final Context context = context();
    walletPubkey = SolanaWalletUi.getWalletPublicKey(context);
    deviceName = LocalDeviceNameStore.read(context);
    // Never derive the Spectral ID on the main thread — attestation + keystore
    // operations block. Read the cached value; derivation happens in background.
    spectralId = OndoZeroIdentity.peekSpectralId();
    spectralIdUnsupported = spectralId == null && OndoZeroIdentity.isUnsupported();

    buildItems();

    // Spectral ID (may need background derivation on first run)
    if (spectralId == null && !spectralIdUnsupported) {
      OndoZeroIdentity.ensureSpectralIdAsync(context, () -> {
        if (isDestroyed()) return;
        spectralId = OndoZeroIdentity.peekSpectralId();
        spectralIdUnsupported = spectralId == null && OndoZeroIdentity.isUnsupported();
        updateIdentityItems();
        refreshDeviceBinding();
      });
    }

    refreshBalances();
    refreshDeviceBinding();
  }

  private void refreshBalances () {
    final String pubkey = walletPubkey;
    if (pubkey == null) {
      solBalance = null;
      tokenBalance = null;
      return;
    }
    balancesLoading = true;
    updateWalletItems();
    io.execute(() -> {
      Double sol = SolanaDirectClient.fetchSolBalance(SolanaConfig.RPC_URL, pubkey);
      Long tokens = SolanaDirectClient.fetchTokenBalance(SolanaConfig.RPC_URL, pubkey, SolanaConfig.PRC_MINT);
      String name = SolanaDirectClient.fetchTokenMetadataName(SolanaConfig.RPC_URL, SolanaConfig.PRC_MINT);
      UI.post(() -> {
        if (isDestroyed()) return;
        solBalance = sol;
        tokenBalance = tokens;
        if (!StringUtils.isEmpty(name)) {
          tokenName = name.trim();
        }
        balancesLoading = false;
        updateWalletItems();
      });
    });
  }

  private void refreshDeviceBinding () {
    final String sid = spectralId;
    bindingLoading = true;
    updateIdentityItems();
    io.execute(() -> {
      DeviceIdentityService.DeviceAccountInfo info = null;
      byte[] registryGsh = null;
      if (sid != null) {
        byte[] gsh = hexToBytes(sid);
        if (gsh != null) {
          info = SolanaDirectClient.fetchDeviceAccount(SolanaConfig.RPC_URL, SolanaConfig.ONDO_ZERO_REGISTRY_PROGRAM_ID, gsh);
        }
      }
      final String pubkey = walletPubkey;
      if (pubkey != null) {
        try {
          registryGsh = SolanaDirectClient.fetchWalletRegistryGsh(
            SolanaConfig.RPC_URL, SolanaConfig.ONDO_ZERO_REGISTRY_PROGRAM_ID,
            org.thunderdog.challegram.solana.Base58.INSTANCE.decode(pubkey));
        } catch (Throwable ignored) { }
      }
      final DeviceIdentityService.DeviceAccountInfo finalInfo = info;
      final byte[] finalRegistryGsh = registryGsh;
      UI.post(() -> {
        if (isDestroyed()) return;
        deviceInfo = finalInfo;
        walletRegistryGsh = finalRegistryGsh;
        bindingLoading = false;
        updateIdentityItems();
        buildItems(); // register-device button visibility depends on binding state
      });
    });
  }

  private void updateIdentityItems () {
    if (adapter == null) return;
    adapter.updateValuedSettingById(R.id.btn_ondoZero_deviceName);
    adapter.updateValuedSettingById(R.id.btn_ondoZero_spectralId);
    adapter.updateValuedSettingById(R.id.btn_ondoZero_deviceStatus);
  }

  private void updateWalletItems () {
    if (adapter == null) return;
    adapter.updateValuedSettingById(R.id.btn_ondoZero_walletAddress);
    adapter.updateValuedSettingById(R.id.btn_ondoZero_solBalance);
    adapter.updateValuedSettingById(R.id.btn_ondoZero_tokenBalance);
    adapter.updateValuedSettingById(R.id.btn_wallet_send);
    adapter.updateValuedSettingById(R.id.btn_wallet_migrate);
  }

  // ── Items ────────────────────────────────────────────────────────────────

  private void buildItems () {
    if (adapter == null) return;
    List<ListItem> items = new ArrayList<>();

    // ── Device Identity ────────────────────────────────────────────────────
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.OndoZeroSectionDeviceIdentity));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    items.add(new ListItem(ListItem.TYPE_INFO_MULTILINE, R.id.btn_ondoZero_deviceName, R.drawable.baseline_edit_24, R.string.OndoZeroLocalDeviceName));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_INFO_MULTILINE, R.id.btn_ondoZero_spectralId, R.drawable.baseline_identifier_24, R.string.OndoZeroSpectralId));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_ondoZero_deviceStatus, R.drawable.baseline_devices_other_24, R.string.OndoZeroDeviceStatus));
    if (canRegisterDevice()) {
      items.add(new ListItem(ListItem.TYPE_SEPARATOR));
      items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_ondoZero_registerDevice, R.drawable.baseline_check_circle_24, R.string.OndoZeroRegisterDevice));
    }
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));

    // ── Wallet ─────────────────────────────────────────────────────────────
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.OndoZeroSectionWallet));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    if (hasWallet()) {
      items.add(new ListItem(ListItem.TYPE_INFO_MULTILINE, R.id.btn_ondoZero_walletAddress, R.drawable.baseline_account_balance_wallet_24, R.string.OndoZeroWalletAddress));
      items.add(new ListItem(ListItem.TYPE_SEPARATOR));
      items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_ondoZero_solBalance, R.drawable.baseline_account_balance_wallet_24, R.string.OndoZeroSolBalance));
      items.add(new ListItem(ListItem.TYPE_SEPARATOR));
      items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_ondoZero_tokenBalance, R.drawable.baseline_account_balance_wallet_24, R.string.OndoZeroTokenBalance));
      items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    }
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_wallet_create, R.drawable.baseline_add_24, R.string.OndoZeroCreateWallet));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_SETTING, R.id.btn_wallet_import, R.drawable.baseline_file_download_24, R.string.OndoZeroImportWallet));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_wallet_send, R.drawable.baseline_send_24, R.string.OndoZeroSendTokens));
    items.add(new ListItem(ListItem.TYPE_SEPARATOR));
    items.add(new ListItem(ListItem.TYPE_VALUED_SETTING_COMPACT, R.id.btn_wallet_migrate, R.drawable.baseline_swap_horiz_24, R.string.OndoZeroMigrateWallet));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    if (!hasWallet()) {
      items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0, R.string.OndoZeroNoWallet));
    }

    // ── Video Recording ────────────────────────────────────────────────────
    items.add(new ListItem(ListItem.TYPE_HEADER, 0, 0, R.string.OndoZeroSectionVideo));
    items.add(new ListItem(ListItem.TYPE_SHADOW_TOP));
    int min = CameraSettings.INSTANCE.getHASHES_PER_SEC_RANGE().getFirst();
    int max = CameraSettings.INSTANCE.getHASHES_PER_SEC_RANGE().getLast();
    String[] sliderValues = new String[max - min + 1];
    for (int i = min; i <= max; i++) {
      sliderValues[i - min] = Lang.getString(R.string.OndoZeroHashRateValue, i);
    }
    int currentIndex = CameraSettings.hashesPerSecond(context()) - min;
    items.add(new ListItem(ListItem.TYPE_SLIDER, R.id.btn_ondoZero_hashRate).setSliderInfo(sliderValues, currentIndex));
    items.add(new ListItem(ListItem.TYPE_SHADOW_BOTTOM));
    items.add(new ListItem(ListItem.TYPE_DESCRIPTION, 0, 0,
      Lang.getString(R.string.OndoZeroHashRateHint, min, max), false));

    adapter.setItems(items, false);
  }

  private boolean canRegisterDevice () {
    return hasWallet()
      && !bindingLoading
      && deviceInfo == null
      && spectralId != null
      && walletRegistryGsh == null;
  }

  // ── Clicks ───────────────────────────────────────────────────────────────

  @Override
  public void onClick (View v) {
    final int viewId = v.getId();
    if (viewId == R.id.btn_ondoZero_deviceName) {
      showDeviceNameEditor();
    } else if (viewId == R.id.btn_ondoZero_spectralId) {
      if (spectralId != null) {
        UI.copyText(spectralId, R.string.OndoZeroSpectralIdCopied);
      }
    } else if (viewId == R.id.btn_ondoZero_walletAddress) {
      if (walletPubkey != null) {
        UI.copyText(walletPubkey, R.string.OndoZeroCopiedAddress);
      }
    } else if (viewId == R.id.btn_ondoZero_registerDevice) {
      registerDevice();
    } else if (viewId == R.id.btn_wallet_create) {
      onCreateWalletPressed();
    } else if (viewId == R.id.btn_wallet_import) {
      showImportDialog();
    } else if (viewId == R.id.btn_wallet_send) {
      onSendPressed();
    } else if (viewId == R.id.btn_wallet_migrate) {
      onMigratePressed();
    }
  }

  // ── Local Device Name ────────────────────────────────────────────────────

  private void showDeviceNameEditor () {
    openInputAlert(
      Lang.getString(R.string.OndoZeroLocalDeviceNameEditTitle),
      Lang.getString(R.string.OndoZeroLocalDeviceNameHint),
      R.string.Save,
      R.string.Cancel,
      deviceName != null ? deviceName : "",
      (inputView, result) -> {
        String value = result != null ? result.trim() : "";
        LocalDeviceNameRules.ValidationError error = LocalDeviceNameRules.INSTANCE.validationError(value);
        if (error != null) {
          int errorRes;
          switch (error) {
            case REQUIRED: errorRes = R.string.OndoZeroLocalDeviceNameRequired; break;
            case TOO_LONG: errorRes = R.string.OndoZeroLocalDeviceNameTooLong; break;
            default: errorRes = R.string.OndoZeroLocalDeviceNameInvalidChars; break;
          }
          UI.showToast(errorRes, Toast.LENGTH_SHORT);
          return false;
        }
        LocalDeviceNameStore.save(context(), value);
        deviceName = value;
        adapter.updateValuedSettingById(R.id.btn_ondoZero_deviceName);
        return true;
      },
      false
    );
  }

  // ── Import Wallet (with on-chain binding checks, mirrors Ondo-Zero Video) ──

  private void showImportDialog () {
    openInputAlert(
      Lang.getString(R.string.OndoZeroImportWallet),
      Lang.getString(R.string.OndoZeroImportWalletHint),
      R.string.OK,
      R.string.Cancel,
      null,
      (inputView, phrase) -> {
        String value = phrase != null ? phrase.trim() : "";
        if (value.isEmpty()) {
          return false;
        }
        importWallet(value);
        return true;
      },
      false
    );
  }

  private void importWallet (String phrase) {
    if (actionInProgress) return;
    actionInProgress = true;
    io.execute(() -> {
      String warning = null;
      try {
        // Derive the public key from the phrase WITHOUT saving it yet,
        // so we can look up chain state before committing to import.
        KeystoreWallet.Keypair preview = SolanaWalletUi.deriveKeypairFromMnemonic(phrase);
        String importedPubkey = preview.getPublicKeyBase58();

        String sid = spectralId;
        byte[] deviceGsh = sid != null ? hexToBytes(sid) : null;

        // Check 1: is THIS device already registered on-chain with a different wallet?
        String existingDeviceBoundWallet = null;
        if (deviceGsh != null) {
          DeviceIdentityService.DeviceAccountInfo info = SolanaDirectClient.fetchDeviceAccount(
            SolanaConfig.RPC_URL, SolanaConfig.ONDO_ZERO_REGISTRY_PROGRAM_ID, deviceGsh);
          if (info != null) {
            existingDeviceBoundWallet = info.getOwnerBase58();
          }
        }

        // Check 2: is the IMPORTED wallet already bound to another device?
        byte[] registryGsh = SolanaDirectClient.fetchWalletRegistryGsh(
          SolanaConfig.RPC_URL, SolanaConfig.ONDO_ZERO_REGISTRY_PROGRAM_ID, preview.getPublicKey());

        if (existingDeviceBoundWallet != null && !existingDeviceBoundWallet.equals(importedPubkey)) {
          warning = Lang.getString(R.string.OndoZeroImportWarningBoundDevice,
            existingDeviceBoundWallet, importedPubkey);
        } else if (registryGsh != null && deviceGsh != null && !java.util.Arrays.equals(registryGsh, deviceGsh)) {
          warning = Lang.getString(R.string.OndoZeroImportWarningBoundWallet, importedPubkey);
        }

        // Always import — never block at the keystore level (same as Ondo-Zero Video).
        SolanaWalletUi.importWalletWithTee(context(), phrase);
      } catch (Throwable error) {
        final String message = error.getMessage() != null ? error.getMessage() : "Invalid SID phrase";
        UI.post(() -> {
          if (isDestroyed()) return;
          actionInProgress = false;
          UI.showToast(message, Toast.LENGTH_LONG);
        });
        return;
      }
      final String finalWarning = warning;
      UI.post(() -> {
        if (isDestroyed()) return;
        actionInProgress = false;
        UI.showToast(R.string.OndoZeroWalletImported, Toast.LENGTH_SHORT);
        refreshAll();
        if (finalWarning != null) {
          showAlert(new android.app.AlertDialog.Builder(context(), Theme.dialogTheme())
            .setTitle(Lang.getString(R.string.OndoZeroCreateWalletWarningTitle))
            .setMessage(finalWarning)
            .setPositiveButton(Lang.getString(R.string.OK), null));
        }
      });
    });
  }

  // ── Create Wallet (with overwrite protection) ────────────────────────────

  private void onCreateWalletPressed () {
    if (!hasWallet()) {
      doCreateWallet();
      return;
    }
    // Wallet exists: never silently drop a wallet that holds Ondo-Zero tokens —
    // they bind this device to its Spectral ID in the on-chain registry.
    if (hasTokens()) {
      showAlert(new android.app.AlertDialog.Builder(context(), Theme.dialogTheme())
        .setTitle(Lang.getString(R.string.OndoZeroCreateWalletBlockedTitle))
        .setMessage(Lang.getString(R.string.OndoZeroCreateWalletBlockedBody))
        .setPositiveButton(Lang.getString(R.string.OK), null));
      return;
    }
    showConfirm(
      Lang.getString(R.string.OndoZeroCreateWalletWarningBody),
      Lang.getString(R.string.OndoZeroReplaceWallet),
      R.drawable.baseline_warning_24,
      OptionColor.RED,
      this::doCreateWallet
    );
  }

  private void doCreateWallet () {
    if (actionInProgress) return;
    actionInProgress = true;
    io.execute(() -> {
      try {
        kotlin.Pair<KeystoreWallet.Keypair, String> created = SolanaWalletUi.createWalletWithTee(context());
        final String mnemonic = created.getSecond();
        UI.post(() -> {
          if (isDestroyed()) return;
          actionInProgress = false;
          SolanaWalletUi.showCreatedPhraseDialog(context(), mnemonic, this::refreshAll);
        });
      } catch (Throwable error) {
        UI.post(() -> {
          if (isDestroyed()) return;
          actionInProgress = false;
          UI.showToast(error.getMessage() != null ? error.getMessage() : "Wallet create failed", Toast.LENGTH_LONG);
        });
      }
    });
  }

  // ── Send Tokens ──────────────────────────────────────────────────────────

  private void onSendPressed () {
    if (!hasWallet()) {
      UI.showToast(R.string.OndoZeroWalletActivateFirst, Toast.LENGTH_SHORT);
      return;
    }
    if (!hasTokens()) {
      UI.showToast(R.string.OndoZeroSendDisabledHint, Toast.LENGTH_SHORT);
      return;
    }
    openInputAlert(
      Lang.getString(R.string.OndoZeroSendTitle),
      Lang.getString(R.string.OndoZeroSendRecipientHint),
      R.string.OK,
      R.string.Cancel,
      null,
      (inputView, recipient) -> {
        final String recipientB58 = recipient != null ? recipient.trim() : "";
        try {
          byte[] decoded = org.thunderdog.challegram.solana.Base58.INSTANCE.decode(recipientB58);
          if (decoded.length != 32) throw new IllegalArgumentException("bad length");
        } catch (Throwable error) {
          UI.showToast(R.string.OndoZeroSendInvalidRecipient, Toast.LENGTH_SHORT);
          return false;
        }
        UI.post(() -> showSendAmountDialog(recipientB58));
        return true;
      },
      false
    );
  }

  private void showSendAmountDialog (String recipientB58) {
    openInputAlert(
      Lang.getString(R.string.OndoZeroSendTitle),
      Lang.getString(R.string.OndoZeroSendAmountHint, tokenName),
      R.string.OK,
      R.string.Cancel,
      null,
      (inputView, amountText) -> {
        double amount;
        try {
          amount = Double.parseDouble(amountText != null ? amountText.trim().replace(',', '.') : "");
        } catch (Throwable error) {
          UI.showToast(R.string.OndoZeroSendInvalidAmount, Toast.LENGTH_SHORT);
          return false;
        }
        if (amount <= 0) {
          UI.showToast(R.string.OndoZeroSendInvalidAmount, Toast.LENGTH_SHORT);
          return false;
        }
        long baseUnits = (long) Math.floor(amount * TOKEN_BASE_UNITS + 0.5);
        if (tokenBalance != null && baseUnits > tokenBalance) {
          UI.showToast(R.string.OndoZeroSendInsufficientFunds, Toast.LENGTH_SHORT);
          return false;
        }
        final long finalBaseUnits = baseUnits;
        UI.post(() -> confirmSend(recipientB58, finalBaseUnits, amount));
        return true;
      },
      false
    );
  }

  private void confirmSend (String recipientB58, long baseUnits, double amount) {
    showConfirm(
      Lang.getString(R.string.OndoZeroSendConfirm,
        String.format(Locale.US, "%.4f", amount), tokenName, recipientB58),
      Lang.getString(R.string.OK),
      () -> executeSend(recipientB58, baseUnits)
    );
  }

  private void executeSend (String recipientB58, long baseUnits) {
    if (actionInProgress) return;
    actionInProgress = true;
    UI.showToast(R.string.OndoZeroSendSubmitting, Toast.LENGTH_SHORT);
    io.execute(() -> {
      try {
        KeystoreWallet.Keypair kp = KeystoreWallet.load(context());
        if (kp == null) throw new IllegalStateException("No wallet");
        TxResult result = SolanaDirectClient.transferPrc(
          SolanaConfig.RPC_URL,
          SolanaConfig.PRC_MINT,
          SolanaConfig.TRANSFER_HOOK_PROGRAM_ID,
          SolanaConfig.ONDO_ZERO_REGISTRY_PROGRAM_ID,
          new org.thunderdog.challegram.solana.SolanaWallet.Keypair(kp.getPublicKey(), kp.getPrivateSeed()),
          SolanaConfig.PRC_DECIMALS,
          baseUnits,
          recipientB58,
          context()
        );
        UI.post(() -> {
          if (isDestroyed()) return;
          actionInProgress = false;
          if (result.getOk()) {
            UI.showToast(Lang.getString(R.string.OndoZeroSendSuccess, result.getSignature()), Toast.LENGTH_LONG);
          } else {
            UI.showToast(Lang.getString(R.string.OndoZeroSendFailed, result.getErrorDetail()), Toast.LENGTH_LONG);
          }
          refreshAll();
        });
      } catch (Throwable error) {
        UI.post(() -> {
          if (isDestroyed()) return;
          actionInProgress = false;
          UI.showToast(Lang.getString(R.string.OndoZeroSendFailed, error.getMessage()), Toast.LENGTH_LONG);
        });
      }
    });
  }

  // ── Migrate Wallet To New Device ─────────────────────────────────────────

  private void onMigratePressed () {
    if (!hasWallet()) {
      UI.showToast(R.string.OndoZeroWalletActivateFirst, Toast.LENGTH_SHORT);
      return;
    }
    if (!hasTokens()) {
      UI.showToast(R.string.OndoZeroMigrateDisabledHint, Toast.LENGTH_SHORT);
      return;
    }
    openInputAlert(
      Lang.getString(R.string.OndoZeroMigrateTitle),
      Lang.getString(R.string.OndoZeroMigrateTargetHint),
      R.string.OK,
      R.string.Cancel,
      null,
      (inputView, target) -> {
        String normalized = target != null ? target.trim().toLowerCase(Locale.US) : "";
        if (!normalized.matches("[0-9a-f]{64}")) {
          UI.showToast(R.string.OndoZeroMigrateInvalidTarget, Toast.LENGTH_SHORT);
          return false;
        }
        final String targetHex = normalized;
        UI.post(() -> confirmMigration(targetHex));
        return true;
      },
      false
    );
  }

  private void confirmMigration (String targetGshHex) {
    showConfirm(
      Lang.getString(R.string.OndoZeroMigrateBody),
      Lang.getString(R.string.OK),
      () -> executeMigration(targetGshHex)
    );
  }

  private void executeMigration (String targetGshHex) {
    if (actionInProgress) return;
    actionInProgress = true;
    UI.showToast(R.string.OndoZeroMigrateSubmitting, Toast.LENGTH_SHORT);
    io.execute(() -> {
      try {
        KeystoreWallet.Keypair kp = KeystoreWallet.load(context());
        if (kp == null) throw new IllegalStateException("No wallet");
        String sid = spectralId;
        byte[] oldGsh = sid != null ? hexToBytes(sid) : null;
        if (oldGsh == null) throw new IllegalStateException("Spectral ID unavailable");
        byte[] newGsh = hexToBytes(targetGshHex);
        if (newGsh == null) throw new IllegalStateException("Invalid target Spectral ID");

        org.thunderdog.challegram.solana.SolanaWallet.Keypair wallet =
          new org.thunderdog.challegram.solana.SolanaWallet.Keypair(kp.getPublicKey(), kp.getPrivateSeed());

        // Same order as Ondo-Zero Video: refresh authorization (challenge-proof),
        // then start the on-chain handover.
        TxResult authResult = SolanaDirectClient.refreshDeviceAuthorization(
          SolanaConfig.RPC_URL, SolanaConfig.ONDO_ZERO_REGISTRY_PROGRAM_ID, wallet, context(), oldGsh);
        if (!authResult.getOk()) {
          throw new IllegalStateException(authResult.getErrorDetail());
        }
        TxResult result = SolanaDirectClient.startHandover(
          SolanaConfig.RPC_URL, SolanaConfig.ONDO_ZERO_REGISTRY_PROGRAM_ID, wallet, oldGsh, newGsh, context());
        UI.post(() -> {
          if (isDestroyed()) return;
          actionInProgress = false;
          if (result.getOk()) {
            UI.showToast(Lang.getString(R.string.OndoZeroMigrateSuccess, targetGshHex), Toast.LENGTH_LONG);
          } else {
            UI.showToast(Lang.getString(R.string.OndoZeroMigrateFailed, result.getErrorDetail()), Toast.LENGTH_LONG);
          }
          refreshAll();
        });
      } catch (Throwable error) {
        UI.post(() -> {
          if (isDestroyed()) return;
          actionInProgress = false;
          UI.showToast(Lang.getString(R.string.OndoZeroMigrateFailed, error.getMessage()), Toast.LENGTH_LONG);
        });
      }
    });
  }

  // ── Register Device ──────────────────────────────────────────────────────

  private void registerDevice () {
    if (actionInProgress || !canRegisterDevice()) return;
    actionInProgress = true;
    UI.showToast(R.string.OndoZeroRegisterDeviceSubmitting, Toast.LENGTH_SHORT);
    io.execute(() -> {
      try {
        KeystoreWallet.Keypair kp = KeystoreWallet.load(context());
        if (kp == null) throw new IllegalStateException("No wallet");
        byte[] gsh = hexToBytes(spectralId);
        if (gsh == null) throw new IllegalStateException("Spectral ID unavailable");
        TxResult result = SolanaDirectClient.registerDevice(
          SolanaConfig.RPC_URL,
          SolanaConfig.ONDO_ZERO_REGISTRY_PROGRAM_ID,
          new org.thunderdog.challegram.solana.SolanaWallet.Keypair(kp.getPublicKey(), kp.getPrivateSeed()),
          gsh,
          context()
        );
        UI.post(() -> {
          if (isDestroyed()) return;
          actionInProgress = false;
          if (result.getOk()) {
            UI.showToast(Lang.getString(R.string.OndoZeroRegisterDeviceDone, SolanaConfig.CLUSTER), Toast.LENGTH_LONG);
          } else {
            UI.showToast(result.getErrorDetail(), Toast.LENGTH_LONG);
          }
          refreshAll();
        });
      } catch (Throwable error) {
        UI.post(() -> {
          if (isDestroyed()) return;
          actionInProgress = false;
          UI.showToast(error.getMessage(), Toast.LENGTH_LONG);
        });
      }
    });
  }

  // ── Helpers ──────────────────────────────────────────────────────────────

  private final SparseArrayCompat<TextWrapper> textWrappers = new SparseArrayCompat<>();
  private final SparseArrayCompat<TdApi.FormattedText> currentTexts = new SparseArrayCompat<>();

  private TextWrapper obtainWrapper (CharSequence text, int id) {
    return obtainWrapper(TD.toFormattedText(text, false), id);
  }

  private TextWrapper obtainWrapper (TdApi.FormattedText text, int id) {
    TextWrapper textWrapper = textWrappers.get(id);
    if (textWrapper == null || !Td.equalsTo(currentTexts.get(id), text)) {
      currentTexts.put(id, text);
      textWrapper = new TextWrapper(tdlib, text, TGMessage.simpleTextStyleProvider(), TextColorSets.Regular.NORMAL, null, null);
      textWrapper.addTextFlags(Text.FLAG_CUSTOM_LONG_PRESS | (Lang.rtl() ? Text.FLAG_ALIGN_RIGHT : 0));
      textWrappers.put(id, textWrapper);
    }
    return textWrapper;
  }

  private static byte[] hexToBytes (String hex) {
    if (hex == null || hex.length() % 2 != 0) return null;
    try {
      byte[] out = new byte[hex.length() / 2];
      for (int i = 0; i < out.length; i++) {
        out[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
      }
      return out;
    } catch (NumberFormatException e) {
      return null;
    }
  }
}
