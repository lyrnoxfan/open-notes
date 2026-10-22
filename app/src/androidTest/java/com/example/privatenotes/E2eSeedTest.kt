package com.example.privatenotes

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.privatenotes.data.backup.AppVersionProvider
import com.example.privatenotes.data.backup.BackupArchiveService
import com.example.privatenotes.data.backup.BackupJson
import com.example.privatenotes.data.backup.BackupSnapshotReader
import com.example.privatenotes.data.repository.NoteRepository
import java.io.File
import java.security.MessageDigest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Seeds the dataset `scripts/e2e-reinstall.sh` survives an uninstall with.
 *
 * The manifest written here is the only thing the verification run trusts: it records every note's
 * id, title, content hash, tags and three timestamps. Verification compares the imported database
 * against those bytes, not against a second copy of the seed logic - otherwise both sides could be
 * wrong in the same direction.
 */
@ScriptDriven
@RunWith(AndroidJUnit4::class)
class E2eSeedTest {

    private val app: PrivateNotesApp
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun seedFiftyPlusNotesAndExport() = runBlocking {
        val notes = app.container.noteRepository
        val tags = app.container.tagRepository

        val manifest = JSONArray()

        suspend fun add(
            title: String,
            content: String,
            tagNames: List<String>,
            trash: Boolean = false,
        ): String {
            val id = notes.createNote(title, content)
            tagNames.forEach { name ->
                val tagId = tags.ensureTag(name) ?: error("could not create tag $name")
                tags.attachTag(id, tagId)
            }
            if (trash) notes.moveToTrash(id)
            manifest.put(manifestEntry(notes, id, trash))
            return id
        }

        // 400 ordinary notes: enough rows that the merge takes long enough to be killed mid-way.
        repeat(400) { index ->
            add(
                title = "第 ${index + 1} 条 · 会议与随想 ${"　".repeat(index % 3)}",
                content = "这是第 ${index + 1} 条笔记的正文。\n\n" +
                    "包含中文、标点（，。！？）、数字 ${index * 7}，以及 emoji " +
                    listOf("🌙", "🐈", "🍵", "✍️")[index % 4] + "。\n" +
                    "换行与 　全角空格 都要逐字节保留。",
                tagNames = listOfNotNull(
                    listOf("工作", "读书", "灵感")[index % 3],
                    if (index % 4 == 0) "长标签名-${index}" else null,
                ),
            )
        }

        // 8 notes already in the trash.
        repeat(8) { index ->
            add(
                title = "回收站里的第 ${index + 1} 条",
                content = "删除于不同时刻，恢复后要回到正确的时间戳。🌧",
                tagNames = listOf("回收站"),
                trash = true,
            )
        }

        // One 1 MB note: the size that makes a half-finished import visible.
        val big = buildString {
            while (length < 1_048_576) append("长文测试 0123456789 中文标点，换行\n")
        }
        add(title = "1MB 长文", content = big, tagNames = listOf("长文", "压力"))

        // One note created, moved to the trash and then deleted for good: its id must not come
        // back through the backup. The repository only allows the second step from the trash.
        val goneId = notes.createNote("这条会被永久删除", "永久删除的正文")
        notes.moveToTrash(goneId)
        notes.permanentlyDelete(goneId)

        assertEquals(401, notes.activeNoteCount())
        assertEquals(8, notes.observeTrash().first().size)

        File(app.filesDir, "e2e-manifest.json").writeText(
            JSONObject()
                .put("notes", manifest)
                .put("permanentlyDeletedId", goneId)
                .put("expectedActive", 401)
                .put("expectedTrash", 8)
                .toString(2),
        )

        val archive = BackupArchiveService(
            snapshotReader = BackupSnapshotReader({ app.container.database }, app.container.timeSource),
            json = BackupJson(),
            appVersionProvider = AppVersionProvider(app),
        )
        File(app.filesDir, "e2e-plain.json").writeBytes(archive.create(null).bytes)
        File(app.filesDir, "e2e-encrypted.pnb").writeBytes(archive.create(E2E_PASSWORD.toCharArray()).bytes)

        // A truncated copy for the fault-injection run: half an envelope must be rejected.
        val full = File(app.filesDir, "e2e-encrypted.pnb").readBytes()
        File(app.filesDir, "e2e-truncated.pnb").writeBytes(full.copyOf(full.size / 2))
    }

    /** Reads the row back from the database, so the manifest describes what was actually stored. */
    private suspend fun manifestEntry(
        notes: NoteRepository,
        id: String,
        trashed: Boolean,
    ): JSONObject {
        // findById sees trashed rows too; observeNotes() only lists the active ones.
        val note = notes.findById(id) ?: error("note $id vanished")
        val names = app.container.tagRepository.observeTagsForNote(id).first().map { it.name }.sorted()
        return JSONObject()
            .put("id", note.id)
            .put("title", note.title)
            .put("contentSha256", sha256(note.content))
            .put("contentBytes", note.content.toByteArray(Charsets.UTF_8).size)
            .put("tags", JSONArray(names))
            .put("createdAt", note.createdAt)
            .put("updatedAt", note.updatedAt)
            .put("deletedAt", note.deletedAt ?: JSONObject.NULL)
            .put("inTrash", trashed)
    }

    private companion object {
        const val E2E_PASSWORD = "E2E-PASS"

        fun sha256(text: String): String =
            MessageDigest.getInstance("SHA-256")
                .digest(text.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
    }
}
