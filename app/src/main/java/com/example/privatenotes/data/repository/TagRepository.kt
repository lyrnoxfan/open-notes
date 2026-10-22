package com.example.privatenotes.data.repository

import com.example.privatenotes.data.local.TagEntity
import kotlinx.coroutines.flow.Flow

/** Tag creation, renaming, removal and note assignment (§5.2). */
interface TagRepository {

    fun observeAll(): Flow<List<TagEntity>>

    suspend fun findByName(name: String): TagEntity?

    /**
     * Returns the id of a tag with this name, creating it when needed.
     *
     * Names are compared case-insensitively by the schema's unique index, so "Work" and "work"
     * resolve to the same tag instead of creating a near-duplicate the user cannot tell apart.
     */
    suspend fun ensureTag(name: String): String?

    suspend fun renameTag(tagId: String, newName: String): Boolean

    suspend fun deleteTag(tagId: String)

    /**
     * How many notes carry this tag.
     *
     * The tag manager shows it next to each name: deleting a tag that is used by thirty notes is a
     * different decision from deleting an empty one, and the confirmation has to say which.
     */
    suspend fun usageCount(tagId: String): Int

    fun observeTagsForNote(noteId: String): Flow<List<TagEntity>>

    suspend fun attachTag(noteId: String, tagId: String)

    suspend fun detachTag(noteId: String, tagId: String)

    companion object {
        const val MAX_NAME_LENGTH = 40
    }
}
