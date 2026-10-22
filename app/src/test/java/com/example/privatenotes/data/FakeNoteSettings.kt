package com.example.privatenotes.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory [NoteSettings] for tests. */
class FakeNoteSettings(trashEnabled: Boolean = true) : NoteSettings {

    private val _trashEnabled = MutableStateFlow(trashEnabled)
    override val trashEnabled: StateFlow<Boolean> = _trashEnabled.asStateFlow()

    override fun setTrashEnabled(enabled: Boolean) {
        _trashEnabled.value = enabled
    }
}
