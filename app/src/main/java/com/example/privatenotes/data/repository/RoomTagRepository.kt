package com.example.privatenotes.data.repository

import androidx.room.withTransaction
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.NoteTagCrossRef
import com.example.privatenotes.data.local.TagEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID

class RoomTagRepository(
    private val database: AppDatabase,
    private val timeSource: TimeSource = TimeSource.System,
) : TagRepository {

    private val tagDao = database.tagDao()
    private val noteDao = database.noteDao()

    override fun observeAll(): Flow<List<TagEntity>> = tagDao.observeAll()

    override suspend fun findByName(name: String): TagEntity? = tagDao.findByName(name.trim())

    override suspend fun ensureTag(name: String): String? {
        val clean = normalize(name) ?: return null
        tagDao.findByName(clean)?.let { return it.id }

        val now = timeSource.nowMillis()
        val tag = TagEntity(
            id = UUID.randomUUID().toString(),
            name = clean,
            createdAt = now,
            updatedAt = now,
        )
        // The unique index decides races: if someone inserted the same name first, IGNORE keeps
        // the existing row and the lookup below returns it.
        tagDao.insert(tag)
        return tagDao.findByName(clean)?.id
    }

    override suspend fun renameTag(tagId: String, newName: String): Boolean {
        val clean = normalize(newName) ?: return false
        val existing = tagDao.findById(tagId) ?: return false
        if (existing.name == clean) return true

        val clash = tagDao.findByName(clean)
        if (clash != null && clash.id != tagId) {
            // Renaming onto an existing name merges the two tags rather than failing obscurely.
            database.withTransaction { mergeInto(targetTagId = clash.id, sourceTagId = tagId) }
            return true
        }

        tagDao.update(existing.copy(name = clean, updatedAt = timeSource.nowMillis()))
        return true
    }

    override suspend fun usageCount(tagId: String): Int = tagDao.usageCount(tagId)

    override suspend fun deleteTag(tagId: String) {
        // The join table has ON DELETE CASCADE on tagId, so references go with it.
        tagDao.delete(tagId)
    }

    override fun observeTagsForNote(noteId: String): Flow<List<TagEntity>> =
        noteDao.observeWithTags(noteId).map { it?.tags.orEmpty() }

    override suspend fun attachTag(noteId: String, tagId: String) {
        tagDao.attach(NoteTagCrossRef(noteId = noteId, tagId = tagId))
    }

    override suspend fun detachTag(noteId: String, tagId: String) {
        tagDao.detach(noteId, tagId)
    }

    private suspend fun mergeInto(targetTagId: String, sourceTagId: String) {
        val affected = database.noteTagRefsFor(sourceTagId)
        for (noteId in affected) {
            tagDao.attach(NoteTagCrossRef(noteId = noteId, tagId = targetTagId))
        }
        tagDao.delete(sourceTagId)
    }

    private fun normalize(raw: String): String? {
        val clean = raw.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return null
        return clean.take(TagRepository.MAX_NAME_LENGTH)
    }
}

/** Note ids currently carrying a tag; used when merging tags. */
private suspend fun AppDatabase.noteTagRefsFor(tagId: String): List<String> =
    tagDao().noteIdsForTag(tagId)
