package com.example.privatenotes.feature.notes

import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.FakeNoteSettings
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.media.MediaRepository
import com.example.privatenotes.data.repository.FakeNoteRepository
import com.example.privatenotes.data.repository.FakeTagRepository
import com.example.privatenotes.data.repository.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MediaPickerLoadTest {
    @Test fun `picker result waits for load and reports load failure rather than dropping it`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        Dispatchers.setMain(dispatcher)
        val context: Context = ApplicationProvider.getApplicationContext()
        val db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val notes = FakeNoteRepository().apply { slowLoadMillis = 5000 }
            val clock = TimeSource { 1000L }
            val vm = NoteEditorViewModel(notes, FakeTagRepository(), FakeNoteSettings(), "missing", clock,
                backgroundScope, mediaRepository = MediaRepository(db, context, clock))
            vm.importMedia(Uri.parse("content://test.invalid/picture"), false, 0)
            runCurrent()
            assertTrue(vm.uiState.value.mediaBusy)
            assertFalse(vm.uiState.value.isLoaded)
            advanceTimeBy(5001)
            runCurrent()
            assertTrue(vm.uiState.value.isLoaded)
            assertFalse(vm.uiState.value.mediaBusy)
            assertEquals("笔记未能加载，无法插入媒体", vm.uiState.value.mediaError)
            assertTrue(notes.notes.value.isEmpty())
        } finally {
            db.close()
            Dispatchers.resetMain()
        }
    }
}
