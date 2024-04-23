package com.nabto.edge.sharedcode

import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.appcompat.content.res.AppCompatResources
import androidx.preference.DialogPreference
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import org.koin.android.ext.android.inject

class AppSettingsFragment : PreferenceFragmentCompat() {
    private val repo: NabtoRepository by inject()
    private val database: DeviceDatabase by inject()
    private val manager: NabtoConnectionManager by inject()

    private val displayNameKey = PreferenceKeys.displayName
    private val resetDatabaseKey = PreferenceKeys.resetDatabase
    private val resetPrivateKeyKey = PreferenceKeys.resetPrivateKey

    private fun getAppVersion(): String {
        val manager = activity?.packageManager
        val name = activity?.packageName
        val versionName = name?.let {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    manager?.getPackageInfo(it, PackageManager.PackageInfoFlags.of(0))?.versionName
                } else {
                    @Suppress("DEPRECATION") manager?.getPackageInfo(it, 0)?.versionName
                }
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }

        return versionName ?: "0.0"
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        val context = preferenceManager.context
        val screen = preferenceManager.createPreferenceScreen(context)
        val getDrawable = { drawable: Int -> AppCompatResources.getDrawable(context, drawable) }

        val prefs = listOf(
            EditTextPreference(context).apply {
                key = displayNameKey
                title = getString(R.string.display_name)
                icon = getDrawable(R.drawable.ic_person)
                summaryProvider = EditTextPreference.SimpleSummaryProvider.getInstance()
                dialogTitle = getString(R.string.display_name)
                dialogMessage = getString(R.string.settings_display_name_dialog_message)
            },

            ConfirmDialogPreference(context).apply {
                key = resetDatabaseKey
                title = getString(R.string.reset_database_title)
                icon = getDrawable(R.drawable.ic_list_remove)
                summary = getString(R.string.reset_database_summary)
                dialogTitle = getString(R.string.warning)
                dialogMessage = getString(R.string.app_settings_database_reset_dialog)
                onDialogClosed = { confirmed ->
                    if (confirmed) {
                        repo.getApplicationScope().launch {
                            database.deviceDao().deleteAll()
                        }
                        view?.snack(getString(R.string.settings_reset_database_snack), Snackbar.LENGTH_SHORT)
                    }
                }
            },

            ConfirmDialogPreference(context).apply {
                key = resetPrivateKeyKey
                title = getString(R.string.settings_reset_pk_title)
                icon = getDrawable(R.drawable.ic_key_reset)
                summary = getString(R.string.settings_reset_pk_summary)
                dialogTitle = getString(R.string.warning)
                dialogMessage = getString(R.string.settings_reset_pk_dialog_message)
                onDialogClosed = { confirmed ->
                    if (confirmed) {
                        repo.resetClientPrivateKey()
                        manager.releaseAll()
                        view?.snack(getString(R.string.settings_reset_pk_snack), Snackbar.LENGTH_SHORT);
                    }
                }
            },

            BasicDialogPreference(context).apply {
                title = getString(R.string.settings_about_title)
                icon = getDrawable(R.drawable.ic_questionmark)
                summary = getString(R.string.settings_about_summary)
                dialogTitle = "About ${context.getString(R.string.app_name)}"
                dialogMessage = """
                    
                    ${context.getString(R.string.app_name)} version ${getAppVersion()}
                    Nabto Client SDK version ${repo.getClientVersion()}
                    Edge-Client-Android version ${BuildConfig.NABTO_WRAPPER_VERSION}
                    
                """.trimIndent()
            }
        )
        prefs.forEach { pref -> screen.addPreference(pref) }
        preferenceScreen = screen
    }

}