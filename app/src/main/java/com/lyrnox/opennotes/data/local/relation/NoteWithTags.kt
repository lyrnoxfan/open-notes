package com.lyrnox.opennotes.data.local.relation

import androidx.room.Embedded
import androidx.room.Junction
import androidx.room.Relation
import com.lyrnox.opennotes.data.local.entity.NoteEntity
import com.lyrnox.opennotes.data.local.entity.NoteTagCrossRef
import com.lyrnox.opennotes.data.local.entity.TagEntity

data class NoteWithTags(
    @Embedded val note: NoteEntity,
    @Relation(
        parentColumn = "id",
        entityColumn = "id",
        associateBy = Junction(
            value = NoteTagCrossRef::class,
            parentColumn = "noteId",
            entityColumn = "tagId",
        ),
    )
    val tags: List<TagEntity>,
)
