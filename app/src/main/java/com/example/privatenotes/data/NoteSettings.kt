package com.example.privatenotes.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Plain app preferences that are neither secrets nor backup configuration.
 *
 * An interface, like [com.example.privatenotes.security.AppLockSettings]: the list screen reads
 * the trash switch reactively, and tests drive the ViewModels with an in-memory fake instead of
 * a SharedPreferences file.
 */
interface NoteSettings {

    /**
     * Whether deleting a note moves it to the 30-day trash (§8). Off means delete is immediate
     * and permanent; the trash screen is hidden and existing trash rows still expire on time.
     */
    val trashEnabled: StateFlow<Boolean>

    fun setTrashEnabled(enabled: Boolean)
}

/** SharedPreferences-backed [NoteSettings]; this file holds only user preferences. */
class SharedPreferencesNoteSettings(context: Context) : NoteSettings {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _trashEnabled = MutableStateFlow(readTrashEnabled())
    override val trashEnabled: StateFlow<Boolean> = _trashEnabled.asStateFlow()

    override fun setTrashEnabled(enabled: Boolean) {
        synchronized(this) {
            prefs.edit().putBoolean(KEY_TRASH_ENABLED, enabled).commit()
            _trashEnabled.value = enabled
        }
    }

    private fun readTrashEnabled(): Boolean =
        prefs.getBoolean(KEY_TRASH_ENABLED, DEFAULT_TRASH_ENABLED)

    companion object {
        const val PREFS_NAME = "private_notes_settings"

        /** The trash is §8's default behaviour; turning it off is the deliberate choice. */
        const val DEFAULT_TRASH_ENABLED = true

        private const val KEY_TRASH_ENABLED = "trash_enabled"
    }
}
