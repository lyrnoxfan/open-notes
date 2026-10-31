package com.example.privatenotes.feature.notes

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.FakeNoteSettings
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.local.NoteEntity
import com.example.privatenotes.data.media.MediaRepository
import com.example.privatenotes.data.media.NoteMediaEntity
import com.example.privatenotes.data.repository.FakeNoteRepository
import com.example.privatenotes.data.repository.FakeTagRepository
import com.example.privatenotes.data.repository.TimeSource
import kotlinx.coroutines.*
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class LegacyMediaBusyTest {
    @Test fun `legacy references are reconciled after busy picker returns during loading`() = runBlocking {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val notes = FakeNoteRepository()
            val id = notes.createNote("title", "body")
            db.noteDao().insert(NoteEntity(id = id, title = "title", content = "body", createdAt = 1, updatedAt = 1))
            db.mediaDao().insert(NoteMediaEntity("legacy", id, "pic.png", "image/png", 1, "unused"))
            notes.slowLoadMillis = 250
            val clock = TimeSource { 1000L }
            val vm = NoteEditorViewModel(notes, FakeTagRepository(), FakeNoteSettings(), id, clock,
                scope, mediaRepository = MediaRepository(db, context, clock))
            vm.importMedia(Uri.parse("content://test.invalid/picture"), false, 0)
            assertTrue(vm.uiState.value.mediaBusy)
            withTimeout(10000) {
                while (vm.uiState.value.mediaBusy || "legacy" !in InlineMediaSyntax.ids(vm.uiState.value.content)) delay(20)
            }
            assertEquals(1, InlineMediaSyntax.split(vm.uiState.value.content).count { it.mediaId == "legacy" })
            assertTrue(vm.uiState.value.content.startsWith("body"))
        } finally {
            scope.cancel()
            db.close()
            Dispatchers.resetMain()
        }
    }
    @Test fun `cross media deletion commits body and rows then survives refresh and reopen`() = runBlocking {
        crossMediaDelete(false)
    }
    @Test fun `failed cross media deletion restores body and all rows`() = runBlocking {
        crossMediaDelete(true)
    }
    private suspend fun crossMediaDelete(injectFailure: Boolean) {
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        try {
            val clock = TimeSource { 1000L }
            val notes = com.example.privatenotes.data.repository.RoomNoteRepository(db, clock)
            val body = "left" + InlineMediaSyntax.reference("one") + "middle" + InlineMediaSyntax.reference("two") + "right"
            val id = notes.createNote("title", body)
            listOf("one", "two").forEach {
                db.mediaDao().insert(NoteMediaEntity(it, id, "pic.png", "image/png", 8, "unused"))
            }
            val vm = NoteEditorViewModel(notes, FakeTagRepository(), FakeNoteSettings(), id, clock,
                scope, mediaRepository = MediaRepository(db, context, clock))
            withTimeout(10000) { while (!vm.uiState.value.isLoaded || vm.uiState.value.media.size != 2) delay(10) }
            if (injectFailure) db.openHelper.writableDatabase.execSQL(
                "CREATE TRIGGER stop_second BEFORE DELETE ON note_media WHEN OLD.id = 'two' BEGIN SELECT RAISE(ABORT, 'test rollback'); END")
            vm.onContentChange("leftright")
            withTimeout(10000) { while (vm.uiState.value.mediaBusy) delay(10) }
            vm.flush()
            delay(50)
            val expected = if (injectFailure) body else "leftright"
            assertEquals(expected, vm.uiState.value.content)
            assertEquals(expected, notes.findById(id)!!.content)
            assertEquals(if (injectFailure) 2 else 0, db.mediaDao().forNote(id).size)
            if (injectFailure) assertNotNull(vm.uiState.value.mediaError)
            val reopened = NoteEditorViewModel(notes, FakeTagRepository(), FakeNoteSettings(), id, clock,
                scope, mediaRepository = MediaRepository(db, context, clock))
            withTimeout(10000) { while (!reopened.uiState.value.isLoaded) delay(10) }
            delay(50)
            assertEquals(expected, reopened.uiState.value.content)
        } finally { scope.cancel(); db.close(); Dispatchers.resetMain() }
    }

}
