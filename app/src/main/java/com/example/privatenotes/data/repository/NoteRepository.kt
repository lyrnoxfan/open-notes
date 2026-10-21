package com.example.privatenotes.data.repository

import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.local.NoteWithTags
import kotlinx.coroutines.flow.Flow

/**
 * Contract for note access.
 *
 * The editor's save pipeline is the most failure-prone part of the app, so it is written
 * against this interface and covered by tests with a fake implementation that can fail and
 * stall on demand.
 */
interface NoteRepository {

    fun observeActiveNotes(): Flow<List<NoteEntity>>

    fun observeTrash(): Flow<List<NoteEntity>>

    /**
     * Notes for the list, with tags attached.
     *
     * [term] is an inclusive match over title and body; [tagId] filters by tag. Both can be
     * combined, and both may be empty/null (§7.3).
     */
    fun observeNotes(term: String = "", tagId: String? = null): Flow<List<NoteWithTags>>

    /** Inclusive search over title and body (§7.3). */
    fun search(term: String): Flow<List<NoteEntity>>

    suspend fun findById(id: String): NoteEntity?

    suspend fun createNote(title: String, content: String): String

    /** Overwrites title and body, stamping `updatedAt` centrally (§5.4). */
    suspend fun updateNote(id: String, title: String, content: String)

    suspend fun moveToTrash(id: String)

    suspend fun restoreFromTrash(id: String)

    /**
     * Permanently removes a trashed note and leaves a deletion marker behind (§5.3, §8).
     *
     * Both tables move inside one transaction, so there is never a state where the content is
     * gone but the tombstone is missing.
     */
    suspend fun permanentlyDelete(id: String)

    /** Clears notes whose 30-day trash window has expired, leaving markers (§8). */
    suspend fun purgeExpiredTrash(nowMillis: Long): Int

    suspend fun activeNoteCount(): Int

    companion object {
        /** §8: notes are kept in the trash for 30 days before automatic cleanup. */
        const val TRASH_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000
    }
}
