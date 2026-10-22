package com.example.privatenotes.data.repository

import com.example.privatenotes.data.local.TagEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/** In-memory [TagRepository] for ViewModel-level tests. */
class FakeTagRepository : TagRepository {

    data class Attachment(val noteId: String, val tagId: String)

    val tags = MutableStateFlow<List<TagEntity>>(emptyList())
    val attachments = MutableStateFlow<List<Attachment>>(emptyList())
    var failWrites: Boolean = false

    private var counter = 0

    override fun observeAll(): Flow<List<TagEntity>> = tags

    override suspend fun findByName(name: String): TagEntity? =
        tags.value.find { it.name.equals(name, ignoreCase = true) }

    override suspend fun ensureTag(name: String): String? {
        if (failWrites) throw IllegalStateException("tag write failed")
        tags.value.find { it.name.equals(name, ignoreCase = true) }?.let { return it.id }
        val tag = TagEntity(
            id = "tag-${counter++}",
            name = name,
            createdAt = 1L,
            updatedAt = 1L,
        )
        tags.value = tags.value + tag
        return tag.id
    }

    override suspend fun renameTag(tagId: String, newName: String): Boolean {
        val existing = tags.value.find { it.id == tagId } ?: return false
        tags.value = tags.value.map { if (it.id == tagId) existing.copy(name = newName) else it }
        return true
    }

    override suspend fun usageCount(tagId: String): Int = 0

    override suspend fun deleteTag(tagId: String) {
        tags.value = tags.value.filterNot { it.id == tagId }
    }

    override fun observeTagsForNote(noteId: String): Flow<List<TagEntity>> =
        attachments.map { list ->
            val ids = list.filter { it.noteId == noteId }.map { it.tagId }.toSet()
            tags.value.filter { it.id in ids }
        }

    override suspend fun attachTag(noteId: String, tagId: String) {
        if (failWrites) throw IllegalStateException("tag write failed")
        if (attachments.value.any { it.noteId == noteId && it.tagId == tagId }) return
        attachments.value = attachments.value + Attachment(noteId, tagId)
    }

    override suspend fun detachTag(noteId: String, tagId: String) {
        attachments.value = attachments.value.filterNot { it.noteId == noteId && it.tagId == tagId }
    }
}
