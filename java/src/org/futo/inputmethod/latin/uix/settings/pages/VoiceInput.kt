package org.futo.inputmethod.latin.uix.settings.pages

import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.res.stringResource
import androidx.core.content.getSystemService
import com.google.mlkit.genai.common.DownloadStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.futo.inputmethod.latin.R
import org.futo.inputmethod.latin.uix.ANIMATE_BUBBLE
import org.futo.inputmethod.latin.uix.AUDIO_FOCUS
import org.futo.inputmethod.latin.uix.CAN_EXPAND_SPACE
import org.futo.inputmethod.latin.uix.CLEAN_UP_SPEECH
import org.futo.inputmethod.latin.uix.DISALLOW_SYMBOLS
import org.futo.inputmethod.latin.uix.ENABLE_SOUND
import org.futo.inputmethod.latin.uix.GEMINI_NANO_LAST_RESULT
import org.futo.inputmethod.latin.uix.LIVE_TRANSCRIPTION
import org.futo.inputmethod.latin.uix.PREFER_BLUETOOTH
import org.futo.inputmethod.latin.uix.SYSTEM_VOICE_INPUT_PACKAGE
import org.futo.inputmethod.latin.uix.USE_GEMINI_NANO_CLEANUP
import org.futo.inputmethod.latin.uix.USE_PERSONAL_DICT
import org.futo.inputmethod.latin.uix.USE_SYSTEM_VOICE_INPUT
import org.futo.inputmethod.latin.uix.USE_VAD_AUTOSTOP
import org.futo.inputmethod.latin.uix.USE_WHISTLE_FOR_ENGLISH
import org.futo.inputmethod.latin.uix.settings.DropDownPickerSettingItem
import org.futo.inputmethod.latin.uix.settings.NavigationItemStyle
import org.futo.inputmethod.latin.uix.settings.SettingItem
import org.futo.inputmethod.latin.uix.settings.Tip
import org.futo.inputmethod.latin.uix.settings.UserSetting
import org.futo.inputmethod.latin.uix.settings.UserSettingsMenu
import org.futo.inputmethod.latin.uix.settings.useDataStore
import org.futo.inputmethod.latin.uix.settings.useDataStoreValue
import org.futo.inputmethod.latin.uix.settings.userSettingNavigationItem
import org.futo.inputmethod.latin.uix.settings.userSettingToggleDataStore
import org.futo.inputmethod.latin.uix.utils.GeminiNanoCleanup

private val visibilityCheckNotSystemVoiceInput = @Composable {
    useDataStoreValue(USE_SYSTEM_VOICE_INPUT) == false
}

private data class VoiceIMEInfo(
    val builtin: Boolean,
    val name: String,
    val packageName: String,
)

@Composable
fun usePackageReadableName(pkg: String): String? {
    val context = LocalContext.current
    return remember(pkg) {
        try {
            context.packageManager.getPackageInfo(pkg, 0)
        } catch(e: Exception) {
            null
        }?.applicationInfo?.let {
            context.packageManager.getApplicationLabel(it).toString()
        }
    }
}

@Composable
private fun GeminiNanoStatus() {
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val lastKeyboardResult = useDataStoreValue(GEMINI_NANO_LAST_RESULT)

    var status by remember { mutableStateOf<GeminiNanoCleanup.Status?>(null) }
    var progress by remember { mutableStateOf<String?>(null) }
    var testResult by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { status = GeminiNanoCleanup.status() }

    val statusText = when(status) {
        null -> R.string.voice_input_settings_gemini_nano_status_checking
        GeminiNanoCleanup.Status.Available -> R.string.voice_input_settings_gemini_nano_status_available
        GeminiNanoCleanup.Status.Downloadable -> R.string.voice_input_settings_gemini_nano_status_downloadable
        GeminiNanoCleanup.Status.Downloading -> R.string.voice_input_settings_gemini_nano_status_downloading
        GeminiNanoCleanup.Status.Unavailable -> R.string.voice_input_settings_gemini_nano_status_unavailable
        GeminiNanoCleanup.Status.Unsupported -> R.string.voice_input_settings_gemini_nano_status_unsupported
    }

    SettingItem(
        title = stringResource(R.string.voice_input_settings_gemini_nano_status),
        subtitle = listOfNotNull(
            progress ?: stringResource(statusText),
            testResult,
            lastKeyboardResult.ifEmpty { null }?.let {
                stringResource(R.string.voice_input_settings_gemini_nano_last_keyboard, it)
            }
        ).joinToString("\n")
    ) {
        Row {
            if(status == GeminiNanoCleanup.Status.Downloadable) {
                TextButton(onClick = {
                    scope.launch {
                        try {
                            GeminiNanoCleanup.download().collect {
                                progress = when(it) {
                                    is DownloadStatus.DownloadProgress -> "${it.totalBytesDownloaded / 1_000_000} MB"
                                    is DownloadStatus.DownloadFailed -> it.e.message
                                    else -> null
                                }
                            }
                        } catch(e: CancellationException) {
                            throw e
                        } catch(e: Exception) {
                            progress = e.message ?: e.javaClass.simpleName
                        }
                        status = GeminiNanoCleanup.status()
                    }
                }) {
                    Text(stringResource(R.string.voice_input_settings_gemini_nano_download))
                }
            }

            if(status == GeminiNanoCleanup.Status.Available) {
                TextButton(onClick = {
                    scope.launch {
                        val sample = res.getString(R.string.voice_input_settings_gemini_nano_test_sample)
                        testResult = when(val result = GeminiNanoCleanup.clean(sample)) {
                            is GeminiNanoCleanup.Result.Success -> res.getString(R.string.voice_input_settings_gemini_nano_test_result, result.text)
                            is GeminiNanoCleanup.Result.Failure -> res.getString(R.string.voice_input_settings_gemini_nano_test_failed, result.reason)
                        }
                    }
                }) {
                    Text(stringResource(R.string.voice_input_settings_gemini_nano_test))
                }
            }
        }
    }
}

val VoiceInputMenu = UserSettingsMenu(
    title = R.string.voice_input_settings_title,
    navPath = "voiceInput", registerNavPath = true,
    settings = listOf(
        UserSetting(
            name = R.string.voice_input_settings_backend_system,
            searchTagList = listOf(
                R.string.voice_input_settings_disable_builtin_voice_input,
                R.string.voice_input_settings_disable_builtin_voice_input_subtitle
            )
        ) {
            val useExternal = useDataStore(USE_SYSTEM_VOICE_INPUT)
            val externalPkg = useDataStore(SYSTEM_VOICE_INPUT_PACKAGE)

            val context = LocalContext.current
            val res = LocalResources.current
            val options = remember(externalPkg.value) {
                val imm = context.getSystemService<InputMethodManager>()!!
                buildList {
                    add(VoiceIMEInfo(true, "", ""))
                    addAll(imm.enabledInputMethodList.filter { im ->
                        im.packageName == externalPkg.value ||
                            (0 until im.subtypeCount).map { im.getSubtypeAt(it) }
                                .any { it.mode.lowercase() == "voice" }
                    }.map {
                        VoiceIMEInfo(false, it.loadLabel(context.packageManager)?.toString() ?: it.packageName, it.packageName)
                    })
                }
            }

            val currOption = remember(externalPkg.value) {
                if(externalPkg.value == "") options[0] else
                options.find { it.packageName == externalPkg.value }
            }


            DropDownPickerSettingItem(
                stringResource(R.string.voice_input_settings_backend_system),
                options,
                currOption,
                {
                    useExternal.setValue(!it.builtin)
                    externalPkg.setValue(it.packageName ?: "")
                },
                {
                    if(it.builtin) res.getString(R.string.voice_input_settings_backend_system_internal)
                    else it.name
                }
            )

            if(useExternal.value && externalPkg.value.isNotEmpty()) {
                val privacyWhitelist = listOf(
                    "org.futo.voiceinput",
                    "org.futo.voiceinput.dev",
                    "dev.notune.transcribe",
                    "dev.soupslurpr.transcribro"
                )

                if(!privacyWhitelist.contains(externalPkg.value))
                    Tip(stringResource(R.string.voice_input_settings_backend_system_external_warning,
                        currOption?.name ?: externalPkg.value))
            }
        },

        //if(!systemVoiceInput.value) {
        userSettingToggleDataStore(
            title = R.string.voice_input_settings_use_whistle,
            subtitle = R.string.voice_input_settings_use_whistle_subtitle,
            setting = USE_WHISTLE_FOR_ENGLISH
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_live_transcription,
            subtitle = R.string.voice_input_settings_live_transcription_subtitle,
            setting = LIVE_TRANSCRIPTION
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_clean_up_speech,
            subtitle = R.string.voice_input_settings_clean_up_speech_subtitle,
            setting = CLEAN_UP_SPEECH
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_gemini_nano,
            subtitle = R.string.voice_input_settings_gemini_nano_subtitle,
            setting = USE_GEMINI_NANO_CLEANUP
        ).copy(visibilityCheck = {
            visibilityCheckNotSystemVoiceInput() && useDataStoreValue(CLEAN_UP_SPEECH)
        }),

        UserSetting(
            name = R.string.voice_input_settings_gemini_nano_status,
            visibilityCheck = {
                visibilityCheckNotSystemVoiceInput()
                        && useDataStoreValue(CLEAN_UP_SPEECH)
                        && useDataStoreValue(USE_GEMINI_NANO_CLEANUP)
            }
        ) {
            GeminiNanoStatus()
        },

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_indication_sounds,
            subtitle = R.string.voice_input_settings_indication_sounds_subtitle,
            setting = ENABLE_SOUND
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        /*
        userSettingToggleDataStore(
            title = R.string.voice_input_settings_verbose_progress,
            subtitle = R.string.voice_input_settings_verbose_progress_subtitle,
            setting = VERBOSE_PROGRESS
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),
         */

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_use_personal_dict,
            subtitle = R.string.voice_input_settings_use_personal_dict_subtitle,
            setting = USE_PERSONAL_DICT
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_use_bluetooth_mic,
            subtitle = R.string.voice_input_settings_use_bluetooth_mic_subtitle,
            setting = PREFER_BLUETOOTH
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_audio_focus,
            subtitle = R.string.voice_input_settings_audio_focus_subtitle,
            setting = AUDIO_FOCUS
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_suppress_symbols,
            setting = DISALLOW_SYMBOLS
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_long_form,
            subtitle = R.string.voice_input_settings_long_form_subtitle,
            setting = CAN_EXPAND_SPACE
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_autostop_vad,
            subtitle = R.string.voice_input_settings_autostop_vad_subtitle,
            setting = USE_VAD_AUTOSTOP
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingToggleDataStore(
            title = R.string.voice_input_settings_animate_bubble,
            subtitle = R.string.voice_input_settings_animate_bubble_subtitle,
            setting = ANIMATE_BUBBLE
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),

        userSettingNavigationItem(
            title = R.string.voice_input_settings_change_models,
            subtitle = R.string.voice_input_settings_change_models_subtitle,
            style = NavigationItemStyle.Misc,
            navigateTo = "languages"
        ).copy(visibilityCheck = visibilityCheckNotSystemVoiceInput),
        //}
    )
)