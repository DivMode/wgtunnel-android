package com.zaneschepke.wireguardautotunnel.core.broadcast

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zaneschepke.logcatter.LogReader
import com.zaneschepke.wireguardautotunnel.core.tunnel.TunnelManager
import com.zaneschepke.wireguardautotunnel.di.Scope
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.domain.repository.AppStateRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.GeneralSettingRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.util.extensions.saveTunnelsUniquely
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.koin.core.component.inject
import org.koin.core.qualifier.named
import timber.log.Timber

class RestartReceiver : BroadcastReceiver(), KoinComponent {

    private val applicationScope: CoroutineScope = get(named(Scope.APPLICATION))

    private val tunnelManager: TunnelManager by inject()

    private val appStateRepository: AppStateRepository by inject()

    private val tunnelRepository: TunnelRepository by inject()

    private val settingsRepository: GeneralSettingRepository by inject()

    private val logReader: LogReader by inject()

    override fun onReceive(context: Context, intent: Intent) {
        Timber.d("RestartReceiver triggered with action: ${intent.action}")
        applicationScope.launch {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED,
                "android.intent.action.QUICKBOOT_POWERON",
                "com.htc.intent.action.QUICKBOOT_POWERON" -> {
                    autoImportFromExternalFiles(context)
                    tunnelManager.handleReboot()
                }
                Intent.ACTION_MY_PACKAGE_REPLACED -> {
                    Timber.i("Restoring state on package upgrade")
                    autoImportFromExternalFiles(context)
                    tunnelManager.handleRestore()
                    logReader.deleteAndClearLogs()
                    appStateRepository.setShouldShowDonationSnackbar(true)
                }
            }
        }
    }

    /**
     * Auto-import tunnel configs and remote-control settings from the app's
     * external-files directory at boot / package upgrade.
     *
     * Drop configs from a host machine via:
     *   adb push file.conf /sdcard/Android/data/com.zaneschepke.wireguardautotunnel/files/oeili-configs/
     *
     * The directory is scoped storage on Android 11+, writable by ADB shell
     * without READ_EXTERNAL_STORAGE/MANAGE_EXTERNAL_STORAGE permissions.
     * Idempotent — every tunnel goes through saveTunnelsUniquely which dedups
     * by name.
     *
     * Optional: drop oeili-remote-key.txt in the same dir to set the
     * RemoteControl security key + enable remote control with no UI. Lets a
     * controller fire START_TUNNEL/STOP_TUNNEL broadcasts with the captured
     * key for unattended operations.
     */
    private suspend fun autoImportFromExternalFiles(context: Context) {
        val baseDir = context.getExternalFilesDir(null) ?: return
        val configDir = File(baseDir, OEILI_CONFIG_DIR)
        if (!configDir.exists()) return
        try {
            val confFiles =
                configDir.listFiles { f -> f.isFile && f.extension == "conf" } ?: emptyArray()
            if (confFiles.isNotEmpty()) {
                val parsed =
                    confFiles.mapNotNull { f ->
                        runCatching {
                                TunnelConfig.tunnelConfFromQuick(f.readText(), f.nameWithoutExtension)
                            }
                            .onFailure { Timber.w(it, "oeili auto-import: failed to parse ${f.name}") }
                            .getOrNull()
                    }
                val existing = tunnelRepository.getAll().map { it.name }
                val incoming = parsed.filterNot { it.name in existing }
                if (incoming.isNotEmpty()) {
                    tunnelRepository.saveTunnelsUniquely(incoming, existing)
                    Timber.i("oeili auto-import: imported ${incoming.size} tunnel(s)")
                }
            }
            val keyFile = File(configDir, OEILI_REMOTE_KEY_FILE)
            if (keyFile.exists()) {
                val key = keyFile.readText().trim()
                if (key.isNotEmpty()) {
                    val current = settingsRepository.getGeneralSettings()
                    if (current.remoteKey != key || !current.isRemoteControlEnabled) {
                        settingsRepository.upsert(
                            current.copy(remoteKey = key, isRemoteControlEnabled = true)
                        )
                        Timber.i("oeili auto-import: remote control key set + enabled")
                    }
                }
            }
        } catch (t: Throwable) {
            Timber.w(t, "oeili auto-import: non-fatal failure")
        }
    }

    companion object {
        private const val OEILI_CONFIG_DIR = "oeili-configs"
        private const val OEILI_REMOTE_KEY_FILE = "oeili-remote-key.txt"
    }
}
