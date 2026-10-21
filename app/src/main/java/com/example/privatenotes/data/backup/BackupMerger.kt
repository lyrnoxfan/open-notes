package com.example.privatenotes.data.backup

import androidx.room.withTransaction
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.DeletionMarkerEntity
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.local.NoteTagCrossRef
import com.example.privatenotes.data.local.TagEntity
import java.util.Locale

/**
 * What happened during a merge. Counts only; note bodies never leave the database to reach a
 * log or a status message.
 */
data class MergeReport(
    val insertedNotes: Int = 0,
    val updatedNotes: Int = 0,
    val keptLocalNotes: Int = 0,
    val unchangedNotes: Int = 0,
    val blockedNotes: Int = 0,
    val deletedLocalNotes: Int = 0,
    val addedTags: Int = 0,
    val remappedTags: Int = 0,
    val addedNoteTags: Int = 0,
    val skippedNoteTags: Int = 0,
    val conflicts: List<MergeConflict> = emptyList(),
) {
    val hasConflicts: Boolean get() = conflicts.isNotEmpty()
}

enum class MergeConflictKind {
    /** Same `updatedAt`, different content (§9.5): local wins, the other version is reported. */
    NOTE_TIMESTAMP_TIE,

    /** A backup tag name is already owned by a different local tag id. */
    TAG_NAME_COLLISION,

    /** The local note was edited after the backup tombstone; the edit is kept. */
    NOTE_EDITED_AFTER_TOMBSTONE,
}

data class MergeConflict(
    val kind: MergeConflictKind,
    val noteId: String? = null,
    val tagId: String? = null,
)

/**
 * Applies a validated backup to the live database (§9.5).
 *
 * Everything runs inside one transaction. A failure at any point rolls back the whole import;
 * there is no half-merged state where some notes were restored and the rest were not.
 */
class BackupMerger(
    private val database: AppDatabase,
    private val json: BackupJson,
) {

    suspend fun merge(backup: BackupDocument): MergeReport {
        json.validate(backup)
        return database.withTransaction {
            val dao = database.backupDao()
            val plan = MergePlanner.plan(
                localNotes = dao.allNotes(),
                localTags = dao.allTags(),
                localRefs = dao.allNoteTags(),
                localMarkers = dao.allDeletionMarkers(),
                backup = backup,
            )

            for (noteId in plan.notesToDelete) {
                dao.deleteNote(noteId)
            }
            for (tag in plan.tagsToUpsert) {
                if (dao.updateTag(tag) == 0) dao.insertTag(tag)
            }
            for (note in plan.notesToUpsert) {
                if (dao.updateNote(note) == 0) dao.insertNote(note)
            }
            for ((noteId, refs) in plan.refsToReplace) {
                dao.clearTagsForNote(noteId)
                for (ref in refs) dao.insertNoteTag(ref)
            }
            for (ref in plan.additiveRefs) {
                dao.insertNoteTag(ref)
            }
            for (marker in plan.markersToUpsert) {
                dao.upsertDeletionMarker(marker)
            }
            plan.report
        }
    }
}

internal data class MergePlan(
    val notesToUpsert: List<NoteEntity>,
    val notesToDelete: List<String>,
    val tagsToUpsert: List<TagEntity>,
    val refsToReplace: Map<String, List<NoteTagCrossRef>>,
    val additiveRefs: List<NoteTagCrossRef>,
    val markersToUpsert: List<DeletionMarkerEntity>,
    val report: MergeReport,
)

/**
 * Pure merge decision logic.
 *
 * Kept separate from Room so every conflict rule is directly testable:
 * - newer backup note wins, newer local note stays,
 * - equal timestamps with different text keep local and record a conflict,
 * - tombstones beat older notes in either direction,
 * - tags merge by id, with a name-based remap when two ids own the same name,
 * - references are only written when both endpoints exist.
 */
internal object MergePlanner {

    fun plan(
        localNotes: List<NoteEntity>,
        localTags: List<TagEntity>,
        localRefs: List<NoteTagCrossRef>,
        localMarkers: List<DeletionMarkerEntity>,
        backup: BackupDocument,
    ): MergePlan {
        var insertedNotes = 0
        var updatedNotes = 0
        var keptLocalNotes = 0
        var unchangedNotes = 0
        var blockedNotes = 0
        var deletedLocalNotes = 0
        var addedTags = 0
        var remappedTags = 0
        var addedNoteTags = 0
        var skippedNoteTags = 0
        val conflicts = mutableListOf<MergeConflict>()

        val finalNotes = HashMap<String, NoteEntity>(localNotes.size * 2).apply {
            localNotes.forEach { put(it.id, it) }
        }
        val notesToDelete = LinkedHashSet<String>()
        val notesToUpsert = LinkedHashMap<String, NoteEntity>()

        // 1. Tombstones first. A backup tombstone can delete a local note even when the backup
        //    contains no row for that note at all.
        val effectiveMarkers = HashMap<String, DeletionMarkerEntity>(localMarkers.size * 2)
        localMarkers.forEach { effectiveMarkers[it.noteId] = it }
        for (marker in backup.deletionMarkers.sortedBy { it.noteId }) {
            val previous = effectiveMarkers[marker.noteId]
            val effective = DeletionMarkerEntity(
                noteId = marker.noteId,
                deletedAt = maxOf(marker.deletedAt, previous?.deletedAt ?: marker.deletedAt),
                lastKnownUpdatedAt = maxOf(
                    marker.lastKnownUpdatedAt,
                    previous?.lastKnownUpdatedAt ?: marker.lastKnownUpdatedAt,
                ),
                schemaVersion = maxOf(marker.schemaVersion, previous?.schemaVersion ?: marker.schemaVersion),
            )
            effectiveMarkers[marker.noteId] = effective

            val local = finalNotes[marker.noteId] ?: continue
            if (local.updatedAt <= effective.deletedAt) {
                finalNotes.remove(marker.noteId)
                notesToUpsert.remove(marker.noteId)
                notesToDelete += marker.noteId
                deletedLocalNotes++
            } else {
                conflicts += MergeConflict(MergeConflictKind.NOTE_EDITED_AFTER_TOMBSTONE, noteId = marker.noteId)
            }
        }

        // 2. Tags. Merge by stable id; if a backup id is unknown but its name matches a local
        //    tag, remap to the local id instead of violating the NOCASE unique index.
        val finalTags = HashMap<String, TagEntity>(localTags.size * 2).apply {
            localTags.forEach { put(it.id, it) }
        }
        val tagIdsByName = HashMap<String, String>(localTags.size * 2).apply {
            localTags.forEach { put(it.name.lowercase(Locale.ROOT), it.id) }
        }
        val backupTagIdToLocalId = HashMap<String, String>(backup.tags.size * 2)
        val tagsToUpsert = LinkedHashMap<String, TagEntity>()

        for (tag in backup.tags.sortedBy { it.id }) {
            val sameId = finalTags[tag.id]
            if (sameId == null) {
                val existingId = tagIdsByName[tag.name.lowercase(Locale.ROOT)]
                if (existingId != null) {
                    backupTagIdToLocalId[tag.id] = existingId
                    remappedTags++
                    val existing = finalTags.getValue(existingId)
                    if (tag.updatedAt > existing.updatedAt) {
                        val refreshed = existing.copy(updatedAt = tag.updatedAt)
                        finalTags[existingId] = refreshed
                        tagsToUpsert[existingId] = refreshed
                    }
                } else {
                    val entity = TagEntity(
                        id = tag.id,
                        name = tag.name,
                        createdAt = tag.createdAt,
                        updatedAt = tag.updatedAt,
                    )
                    finalTags[tag.id] = entity
                    tagIdsByName[tag.name.lowercase(Locale.ROOT)] = tag.id
                    tagsToUpsert[tag.id] = entity
                    backupTagIdToLocalId[tag.id] = tag.id
                    addedTags++
                }
                continue
            }

            backupTagIdToLocalId[tag.id] = tag.id
            if (sameId.name.equals(tag.name, ignoreCase = true)) {
                if (tag.updatedAt > sameId.updatedAt) {
                    val refreshed = sameId.copy(updatedAt = tag.updatedAt)
                    finalTags[tag.id] = refreshed
                    tagsToUpsert[tag.id] = refreshed
                }
                continue
            }

            val collisionId = tagIdsByName[tag.name.lowercase(Locale.ROOT)]
            if (collisionId != null && collisionId != tag.id) {
                // Another local tag already owns this name. Renaming would merge two tag
                // identities behind the user's back, so keep the local tag and report it.
                conflicts += MergeConflict(MergeConflictKind.TAG_NAME_COLLISION, tagId = tag.id)
                continue
            }
            if (tag.updatedAt > sameId.updatedAt) {
                tagIdsByName.remove(sameId.name.lowercase(Locale.ROOT))
                tagIdsByName[tag.name.lowercase(Locale.ROOT)] = tag.id
                val renamed = sameId.copy(name = tag.name, updatedAt = tag.updatedAt)
                finalTags[tag.id] = renamed
                tagsToUpsert[tag.id] = renamed
            }
        }

        // 3. Notes.
        for (note in backup.notes.sortedBy { it.id }) {
            if (note.id in notesToDelete) {
                // The local copy was just removed by a tombstone; the backup row is the older
                // version that marker is protecting against.
                blockedNotes++
                continue
            }
            val tombstone = effectiveMarkers[note.id]
            if (tombstone != null && note.updatedAt <= tombstone.deletedAt) {
                blockedNotes++
                val local = finalNotes[note.id]
                if (local != null && local.updatedAt <= tombstone.deletedAt) {
                    finalNotes.remove(note.id)
                    notesToUpsert.remove(note.id)
                    notesToDelete += note.id
                    deletedLocalNotes++
                }
                continue
            }

            val local = finalNotes[note.id]
            when {
                local == null -> {
                    val entity = note.toEntity()
                    finalNotes[note.id] = entity
                    notesToUpsert[note.id] = entity
                    insertedNotes++
                }

                note.updatedAt > local.updatedAt -> {
                    val entity = note.toEntity()
                    finalNotes[note.id] = entity
                    notesToUpsert[note.id] = entity
                    updatedNotes++
                }

                note.updatedAt < local.updatedAt -> {
                    // Local is newer: import must not roll it back to an older backup.
                    keptLocalNotes++
                }

                else -> {
                    if (sameContent(local, note)) {
                        unchangedNotes++
                    } else {
                        keptLocalNotes++
                        conflicts += MergeConflict(MergeConflictKind.NOTE_TIMESTAMP_TIE, noteId = note.id)
                    }
                }
            }
        }

        // 4. References. For notes whose body was replaced, the backup's tag set is
        //    authoritative and the old rows are cleared. For notes kept local, backup tags are
        //    additive; nothing already attached is removed.
        val refsToReplace = LinkedHashMap<String, LinkedHashSet<Pair<String, String>>>()
        val additiveRefs = mutableListOf<NoteTagCrossRef>()
        val existingRefs = localRefs.mapTo(HashSet()) { it.noteId to it.tagId }

        // A replaced note adopts the backup's tag set, including the empty set. Create the
        // bucket for every upserted note so the apply step clears stale local references even
        // when the backup has none for that note.
        for (noteId in notesToUpsert.keys) {
            refsToReplace.getOrPut(noteId) { LinkedHashSet() }
        }

        for (ref in backup.noteTags.sortedWith(compareBy({ it.noteId }, { it.tagId }))) {
            if (ref.noteId in notesToDelete) {
                skippedNoteTags++
                continue
            }
            if (ref.noteId !in finalNotes) {
                skippedNoteTags++
                continue
            }
            val localTagId = backupTagIdToLocalId[ref.tagId]
            if (localTagId == null || localTagId !in finalTags) {
                skippedNoteTags++
                continue
            }
            val pair = ref.noteId to localTagId
            if (ref.noteId in notesToUpsert) {
                val bucket = refsToReplace.getOrPut(ref.noteId) { LinkedHashSet() }
                if (bucket.add(pair)) addedNoteTags++
            } else {
                if (pair in existingRefs) continue
                existingRefs += pair
                additiveRefs += NoteTagCrossRef(noteId = ref.noteId, tagId = localTagId)
                addedNoteTags++
            }
        }

        val report = MergeReport(
            insertedNotes = insertedNotes,
            updatedNotes = updatedNotes,
            keptLocalNotes = keptLocalNotes,
            unchangedNotes = unchangedNotes,
            blockedNotes = blockedNotes,
            deletedLocalNotes = deletedLocalNotes,
            addedTags = addedTags,
            remappedTags = remappedTags,
            addedNoteTags = addedNoteTags,
            skippedNoteTags = skippedNoteTags,
            conflicts = conflicts.distinct(),
        )
        return MergePlan(
            notesToUpsert = notesToUpsert.values.toList(),
            notesToDelete = notesToDelete.toList(),
            tagsToUpsert = tagsToUpsert.values.toList(),
            refsToReplace = refsToReplace.mapValues { (_, value) -> value.map { NoteTagCrossRef(it.first, it.second) } },
            additiveRefs = additiveRefs,
            markersToUpsert = effectiveMarkers.values.sortedBy { it.noteId },
            report = report,
        )
    }

    private fun sameContent(local: NoteEntity, backup: BackupNote): Boolean =
        local.title == backup.title && local.content == backup.content && local.deletedAt == backup.deletedAt
}

private fun BackupNote.toEntity(): NoteEntity = NoteEntity(
    id = id,
    title = title,
    content = content,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)
