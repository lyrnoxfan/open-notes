package com.example.privatenotes.feature.tags

import android.content.Context
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.data.local.AppDatabase
import com.example.privatenotes.data.repository.NoteRepository
import com.example.privatenotes.data.repository.RoomNoteRepository
import com.example.privatenotes.data.repository.RoomTagRepository
import com.example.privatenotes.data.repository.TagRepository
import com.example.privatenotes.data.repository.TimeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The tag manager drives the existing repository; these tests pin what the screen promises.
 *
 * Real Room in memory, not a fake: the point is that renaming onto an existing name merges, and
 * that deleting a tag leaves its notes alone - both are repository behaviour this screen must not
 * change, only expose.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TagManagementViewModelTest {

    private lateinit var database: AppDatabase
    private lateinit var notes: NoteRepository
    private lateinit var tags: TagRepository
    private var clock = 1_000L
    private val viewModels = mutableListOf<TagManagementViewModel>()
    private val stores = mutableListOf<ViewModelStore>()
    private val observationScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @Before
    fun setUp() {
        // Room's flows emit on their own executor, which virtual time cannot advance; Unconfined
        // lets the state flow collect as soon as Room emits, and the helpers below poll for it.
        Dispatchers.setMain(Dispatchers.Unconfined)
        val context: Context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        notes = RoomNoteRepository(database, TimeSource { clock })
        tags = RoomTagRepository(database, TimeSource { clock })
    }

    @After
    fun tearDown() {
        // Cancel and JOIN all real Room-flow readers before changing Dispatchers.Main.
        // cancel() alone races resetMain when WhileSubscribed processes its final subscriber.
        runBlocking {
            observationScope.coroutineContext[Job]!!.cancelAndJoin()
            stores.forEach { it.clear() }
            viewModels.forEach { it.viewModelScope.coroutineContext[Job]!!.join() }
        }
        database.close()
        Dispatchers.resetMain()
    }

    /** Starts collecting, which is what makes the WhileSubscribed state flow hot. */
    private fun observe(viewModel: TagManagementViewModel) {
        viewModels += viewModel
        stores += ViewModelStore().also { it.put("tags", viewModel) }
        collector = observationScope.launch { viewModel.uiState.collect {} }
    }

    private var collector: Job? = null

    private fun awaitState(
        viewModel: TagManagementViewModel,
        predicate: (TagManagementUiState) -> Boolean,
    ) {
        val deadline = System.currentTimeMillis() + 3_000
        while (System.currentTimeMillis() < deadline) {
            if (predicate(viewModel.uiState.value)) return
            Thread.sleep(10)
        }
        throw AssertionError("state never satisfied the condition: ${viewModel.uiState.value}")
    }

    @Test
    fun countsNotesPerTag() = runBlocking {
        val work = tags.ensureTag("工作")!!
        repeat(3) { index ->
            val id = notes.createNote("笔记 $index", "正文")
            tags.attachTag(id, work)
        }
        val viewModel = TagManagementViewModel({ tags })
        observe(viewModel)

        awaitState(viewModel) { state -> state.tags.any { it.id == work } }
        val row = viewModel.uiState.value.tags.single { it.id == work }
        assertEquals(3, row.noteCount)
    }

    @Test
    fun renamingOntoAnExistingNameMergesAndSaysSo() = runBlocking {
        val work = tags.ensureTag("工作")!!
        val office = tags.ensureTag("办公室")!!
        val id = notes.createNote("一条笔记", "正文")
        tags.attachTag(id, office)

        val viewModel = TagManagementViewModel({ tags })
        observe(viewModel)

        viewModel.rename(office, "工作", setOf("工作", "办公室"))
        awaitState(viewModel) { it.mergedInto != null }

        assertEquals(
            "the merge must be reported, not silently successful",
            "工作",
            viewModel.uiState.value.mergedInto,
        )
        val remaining = tags.observeAll().first().map { it.name }
        assertEquals(listOf("工作"), remaining)
        assertEquals("the note keeps its tag through the merge", 1, tags.usageCount(work))
    }

    @Test
    fun deletingATagKeepsTheNotes() = runBlocking {
        val work = tags.ensureTag("工作")!!
        val first = notes.createNote("笔记一", "正文一")
        val second = notes.createNote("笔记二", "正文二")
        tags.attachTag(first, work)
        tags.attachTag(second, work)

        val viewModel = TagManagementViewModel({ tags })
        observe(viewModel)

        viewModel.delete(work)
        // The initial state has an empty tag list too, so this predicate is only meaningful
        // once a real emission has arrived: loading must be done, which cannot happen before
        // the delete's result has been read from the database.
        awaitState(viewModel) { !it.isLoading && it.tags.isEmpty() }

        assertTrue("the tag is gone", tags.observeAll().first().isEmpty())
        assertEquals("the notes are not", 2, notes.activeNoteCount())
        assertNotNull(notes.findById(first))
        assertNotNull(notes.findById(second))
    }

    @Test
    fun emptyAndOverlongNamesAreRejectedWithAReason() = runBlocking {
        val work = tags.ensureTag("工作")!!
        val viewModel = TagManagementViewModel({ tags })
        observe(viewModel)

        viewModel.rename(work, "   ", emptySet())
        awaitState(viewModel) { it.problem != null }
        assertNotNull("an empty name must be refused", viewModel.uiState.value.problem)

        viewModel.consumeMessage()
        awaitState(viewModel) { it.problem == null }
        assertNull(viewModel.uiState.value.problem)

        viewModel.rename(work, "字".repeat(TagRepository.MAX_NAME_LENGTH + 1), emptySet())
        awaitState(viewModel) { it.problem != null }
        assertNotNull("an overlong name must be refused", viewModel.uiState.value.problem)
    }
}
