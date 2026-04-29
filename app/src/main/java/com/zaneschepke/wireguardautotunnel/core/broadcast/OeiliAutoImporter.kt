package com.zaneschepke.wireguardautotunnel.core.broadcast

import android.content.Context
import com.zaneschepke.wireguardautotunnel.domain.model.TunnelConfig
import com.zaneschepke.wireguardautotunnel.domain.repository.GeneralSettingRepository
import com.zaneschepke.wireguardautotunnel.domain.repository.TunnelRepository
import com.zaneschepke.wireguardautotunnel.util.extensions.saveTunnelsUniquely
import java.io.File
import timber.log.Timber

/**
 * Auto-import tunnel configs and remote-control settings from a watched
 * directory. Called on BOOT_COMPLETED, package upgrade, AND on every cold
 * start of the application.
 *
 * Why every cold start? Android 13+ keeps fresh-installed apps in a
 * "stopped state" where the system filters out BOOT_COMPLETED until the
 * user opens the app from the launcher. For unattended provisioning this
 * means the boot path alone is unreliable. Running on Application.onCreate
 * closes the gap.
 *
 * Two locations are checked, INTERNAL first, EXTERNAL fallback:
 *   1. /data/data/<pkg>/files/oeili-configs/   (internal, app-private)
 *      Push via: adb shell run-as <pkg> sh -c "cat > files/oeili-configs/<name>"
 *      Files land owned by the app's UID — fully readable by the app.
 *   2. /sdcard/Android/data/<pkg>/files/oeili-configs/  (external)
 *      Push via: adb push <file> /sdcard/Android/data/<pkg>/files/oeili-configs/
 *      Files land owned by `shell` UID. The app's UID may not be in the
 *      `ext_data_rw` group on all firmware variants, so reads can fail.
 *      External is the fallback for non-debuggable builds where `run-as`
 *      isn't available.
 *
 * After importing, the first tunnel becomes the primary tunnel and
 * isRestoreOnBootEnabled is set so handleReboot() actually starts it.
 *
 * Idempotent — every tunnel goes through saveTunnelsUniquely which dedups
 * by name. Logs the directory it ended up reading from so debugging
 * provisioning issues from logcat is possible.
 *
 * Optional: drop oeili-remote-key.txt in the same dir to set the
 * RemoteControl security key + enable remote control with no UI. Lets a
 * controller fire START_TUNNEL/STOP_TUNNEL broadcasts with the captured
 * key for unattended operations.
 *
 * Returns true if anything new was imported or settings changed, so the
 * caller can decide whether to kick the tunnel manager.
 */
object OeiliAutoImporter {

    private const val OEILI_CONFIG_DIR = "oeili-configs"
    private const val OEILI_REMOTE_KEY_FILE = "oeili-remote-key.txt"

    suspend fun run(
        context: Context,
        tunnelRepository: TunnelRepository,
        settingsRepository: GeneralSettingRepository,
    ): Boolean {
        val internalDir = File(context.filesDir, OEILI_CONFIG_DIR)
        val externalDir = context.getExternalFilesDir(null)?.let { File(it, OEILI_CONFIG_DIR) }
        val configDir = when {
            internalDir.exists() && (internalDir.listFiles()?.isNotEmpty() == true) -> internalDir
            externalDir != null && externalDir.exists() -> externalDir
            else -> {
                Timber.d("oeili auto-import: no oeili-configs dir at internal or external paths")
                return false
            }
        }
        Timber.i("oeili auto-import: scanning ${configDir.absolutePath}")
        var changed = false
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
                    changed = true
                }
                if (tunnelRepository.getDefaultTunnel() == null) {
                    val firstByName = parsed.firstOrNull()?.name
                    val primary = firstByName?.let { tunnelRepository.findByTunnelName(it) }
                    if (primary != null) {
                        tunnelRepository.updatePrimaryTunnel(primary)
                        Timber.i("oeili auto-import: marked ${primary.name} as primary")
                        changed = true
                    }
                }
            }
            val keyFile = File(configDir, OEILI_REMOTE_KEY_FILE)
            val current = settingsRepository.getGeneralSettings()
            var nextSettings = current
            if (keyFile.exists()) {
                val key = keyFile.readText().trim()
                if (key.isNotEmpty() &&
                    (current.remoteKey != key || !current.isRemoteControlEnabled)) {
                    nextSettings = nextSettings.copy(
                        remoteKey = key,
                        isRemoteControlEnabled = true,
                    )
                }
            }
            if (!nextSettings.isRestoreOnBootEnabled) {
                nextSettings = nextSettings.copy(isRestoreOnBootEnabled = true)
            }
            if (nextSettings != current) {
                settingsRepository.upsert(nextSettings)
                Timber.i("oeili auto-import: updated general settings (restoreOnBoot, remoteKey)")
                changed = true
            }
        } catch (t: Throwable) {
            Timber.w(t, "oeili auto-import: non-fatal failure")
        }
        return changed
    }
}
