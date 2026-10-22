package com.example.privatenotes.data.repository

import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.local.NoteWithTags
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.util.UUID

/**
 * In-memory [NoteRepository] for editor tests.
 *
 * It can stall a write on demand, which is how the "older save must not overwrite newer text"
 * case gets exercised without depending on a real database timing.
 */
class FakeNoteRepository : NoteRepository {

    val notes = MutableStateFlow<List<NoteEntity>>(emptyList())

    /** Every content value written, in order, so ordering can be asserted. */
    val writeLog = mutableListOf<String>()

    var failWrites: Boolean = false
    var failDeletes: Boolean = false
    var slowUpdateMillis: Long = 0
    var slowCreateMillis: Long = 0
    var slowLoadMillis: Long = 0
    var createCount: Int = 0

    private var nextId = 0

    override fun observeActiveNotes(): Flow<List<NoteEntity>> = notes

    override fun observeTrash(): Flow<List<NoteEntity>> = notes

    override fun observeNotes(term: String, tagId: String?): Flow<List<NoteWithTags>> =
        notes.map { list -> list.map { NoteWithTags(it, emptyList()) } }

    override fun search(term: String): Flow<List<NoteEntity>> = notes

    override suspend fun findById(id: String): NoteEntity? {
        if (slowLoadMillis > 0) delay(slowLoadMillis)
        return notes.value.find { it.id == id }
    }

    override suspend fun createNote(title: String, content: String): String {
        if (slowCreateMillis > 0) delay(slowCreateMillis)
        if (failWrites) throw IllegalStateException("create failed")
        val id = "note-${nextId++}"
        createCount++
        writeLog += content
        notes.value = notes.value + NoteEntity(
            id = id,
            title = title,
            content = content,
            createdAt = 1L,
            updatedAt = 1L,
        )
        return id
    }

    override suspend fun updateNote(id: String, title: String, content: String) {
        if (slowUpdateMillis > 0) {
            val stall = slowUpdateMillis
            slowUpdateMillis = 0
            delay(stall)
        }
        if (failWrites) throw IllegalStateException("update failed")
        writeLog += content
        notes.value = notes.value.map {
            if (it.id == id) it.copy(title = title, content = content, updatedAt = it.updatedAt + 1) else it
        }
    }

    override suspend fun moveToTrash(id: String) {
        if (failDeletes) throw IllegalStateException("delete failed")
        notes.value = notes.value.filterNot { it.id == id }
    }

    override suspend fun moveToTrashAll(ids: List<String>) {
        if (failDeletes) throw IllegalStateException("delete failed")
        ids.forEach { moveToTrash(it) }
    }

    override suspend fun restoreFromTrash(id: String) = Unit

    override suspend fun permanentlyDelete(id: String) {
        if (failDeletes) throw IllegalStateException("delete failed")
        notes.value = notes.value.filterNot { it.id == id }
    }

    override suspend fun deletePermanentlyAll(ids: List<String>) {
        if (failDeletes) throw IllegalStateException("delete failed")
        ids.forEach { permanentlyDelete(it) }
    }

    override suspend fun purgeExpiredTrash(nowMillis: Long): Int = 0

    override suspend fun activeNoteCount(): Int = notes.value.size
}
