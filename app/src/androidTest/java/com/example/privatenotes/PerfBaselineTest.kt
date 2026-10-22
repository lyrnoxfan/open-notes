package com.example.privatenotes

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.privatenotes.data.backup.AppVersionProvider
import com.example.privatenotes.data.backup.BackupArchiveService
import com.example.privatenotes.data.backup.BackupJson
import com.example.privatenotes.data.backup.BackupSnapshotReader
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.local.TagEntity
import com.example.privatenotes.data.local.NoteTagCrossRef
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Produces the numbers in `docs/perf-baseline.md` on a device.
 *
 * The dataset is 5,000 notes of about 2 KB plus one 500 KB note, generated here and only here -
 * nothing in `main` knows about it. Each measurement is printed as `PERF key=ms` and also written
 * to `files/perf-baseline.txt`, so the same class can be run again after an optimisation and the
 * two runs compared line by line.
 *
 * ScriptDriven: it fills the database with 5,000 rows, which no other test should see.
 */
@ScriptDriven
@RunWith(AndroidJUnit4::class)
class PerfBaselineTest {

    private val app: PrivateNotesApp
        get() = ApplicationProvider.getApplicationContext()

    private val results = linkedMapOf<String, Long>()

    @Test
    fun measureBaseline() = runBlocking {
        seedIfNeeded()

        measure("list_first_load") {
            println("PERF list_rows=${app.container.noteRepository.observeActiveNotes().first().size}")
        }
        measure("search_one_term") {
            println("PERF search_rows=${app.container.noteRepository.search("会议").first().size}")
        }
        measure("find_one_note") {
            app.container.noteRepository.findById(seededIds.first())
        }

        // Opening the database after the lock closed it: SQLCipher derives its key on every open.
        // No warm-up here: the point is the SQLCipher key derivation that happens on open.
        app.container.closeDatabase()
        measure("db_reopen_plus_first_query", warmUp = false) {
            app.container.noteRepository.activeNoteCount()
        }

        val archive = BackupArchiveService(
            snapshotReader = BackupSnapshotReader({ app.container.database }, app.container.timeSource),
            json = BackupJson(),
            appVersionProvider = AppVersionProvider(app),
        )
        measure("export_5000_plaintext") {
            val bytes = archive.create(null).bytes
            File(app.filesDir, "perf-export.json").writeBytes(bytes)
            println("PERF export_plain_bytes=${bytes.size}")
        }
        measure("export_5000_encrypted") {
            val bytes = archive.create(PASSWORD.toCharArray()).bytes
            File(app.filesDir, "perf-export.pnb").writeBytes(bytes)
            println("PERF export_encrypted_bytes=${bytes.size}")
        }
        measure("preview_render_200kb") {
            val markdown = "长文 " + "这是用于预览渲染测速的段落，包含**粗体**与`代码`。\n".repeat(4_000)
            val markwon = io.noties.markwon.Markwon.builder(app).build()
            val view = android.widget.TextView(app)
            val startParse = System.nanoTime()
            val spanned = markwon.render(markwon.parse(markdown))
            val parseMs = (System.nanoTime() - startParse) / 1_000_000
            markwon.setParsedMarkdown(view, spanned)
            view.measure(
                android.view.View.MeasureSpec.makeMeasureSpec(1080, android.view.View.MeasureSpec.EXACTLY),
                android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED),
            )
            view.layout(0, 0, view.measuredWidth, view.measuredHeight)
            println("PERF preview_chars=${markdown.length} parse_ms=$parseMs")
        }

        measure("import_5000_plaintext") {
            val outcome = app.container.backupImporter.import(
                android.net.Uri.fromFile(File(app.filesDir, "perf-export.json")),
                null,
            )
            println("PERF import_inserted=${outcome.report.insertedNotes} updated=${outcome.report.updatedNotes}")
        }

        writeResults()
    }

    private suspend fun seedIfNeeded() {
        val dao = app.container.database.noteDao()
        val existing = app.container.noteRepository.activeNoteCount()
        if (existing >= TARGET_NOTES) {
            seededIds.clear()
            seededIds += app.container.noteRepository.observeActiveNotes().first().take(20).map { it.id }
            println("PERF seed=reused existing=$existing")
            return
        }

        val now = app.container.timeSource.nowMillis()
        val body = buildString {
            repeat(20) { line ->
                append("第 ${line + 1} 行：项目会议记录，包含中文标点、数字 0123456789 和英文 mixed text。")
                append('\n')
            }
        }.let { it + "尾巴" }.take(2_000)

        val ids = ArrayList<String>(TARGET_NOTES)
        withContext(Dispatchers.IO) {
            for (index in 0 until TARGET_NOTES) {
                val id = "perf-$index"
                ids += id
                dao.insert(
                    NoteEntity(
                        id = id,
                        title = "会议记录 $index",
                        content = if (index == 0) "长文 " + "长".repeat(500_000) else body,
                        createdAt = now - index * 60_000L,
                        updatedAt = now - index * 60_000L,
                    ),
                )
                if (index % 500 == 0) println("PERF seeding=$index")
            }
            // A tag on every tenth note, so the list has relations to resolve.
            val tagId = "perf-tag"
            app.container.database.tagDao().insert(TagEntity(tagId, "压力样本", now, now))
            for (index in 0 until TARGET_NOTES step 10) {
                app.container.database.tagDao().attach(NoteTagCrossRef(ids[index], tagId))
            }
        }
        seededIds.clear()
        seededIds += ids
        println("PERF seeded=$TARGET_NOTES")
    }

    private suspend fun measure(key: String, warmUp: Boolean = true, block: suspend () -> Unit) {
        // One warm-up so the reported number is not the JIT compiling the query path.
        if (warmUp) runCatching { block() }
        val start = System.nanoTime()
        block()
        val millis = (System.nanoTime() - start) / 1_000_000
        results[key] = millis
        println("PERF $key=${millis}ms")
    }

    private fun writeResults() {
        val text = buildString {
            appendLine("device=${android.os.Build.MODEL} sdk=${android.os.Build.VERSION.SDK_INT} abi=${android.os.Build.SUPPORTED_ABIS.first()}")
            results.forEach { (key, value) -> appendLine("$key=$value") }
        }
        File(app.filesDir, "perf-baseline.txt").writeText(text)
        println("PERF written ${results.size} measurements")
    }

    private companion object {
        const val TARGET_NOTES = 5_000
        const val PASSWORD = "PERF-PASS"
        val seededIds = ArrayList<String>()
    }
}
