package com.example.privatenotes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import com.example.privatenotes.feature.notes.NoteListTags
import com.example.privatenotes.feature.notes.EditorTags
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*

/** Real MainActivity, pointer gestures, encrypted persistence and snackbar undo. */
@ScriptDriven
class SwipeDeleteRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val container get() = ApplicationProvider.getApplicationContext<PrivateNotesApp>().container
    private val repo get() = container.noteRepository
    @Before fun setup() = runBlocking {
        container.noteSettings.setTrashEnabled(true)
        container.database.withTransaction {
            val db = container.database.openHelper.writableDatabase
            db.execSQL("DELETE FROM note_tag_refs")
            db.execSQL("DELETE FROM notes")
            db.execSQL("DELETE FROM tags")
            db.execSQL("DELETE FROM deletion_markers")
        }
    }
    private fun seed(title: String, body: String = "文章正文") = runBlocking { repo.createNote(title, body) }
    private fun row(id: String) = compose.onNodeWithTag("note_row_$id")
    private fun ready(id: String) {
        compose.waitUntil(10000) { compose.onAllNodesWithTag("note_row_$id").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun assertGestureKeptList(id: String) {
        compose.waitForIdle()
        compose.onNodeWithTag(EditorTags.CONTENT).assertDoesNotExist()
        row(id).assertExists()
        assertNotNull(runBlocking { repo.findById(id) })
        assertNull(runBlocking { repo.findById(id)!!.deletedAt })
    }
    private fun evidence(name: String) {
        compose.waitForIdle()
        compose.activityRule.scenario.onActivity { activity ->
            val view = activity.window.decorView
            val bitmap = android.graphics.Bitmap.createBitmap(view.width, view.height, android.graphics.Bitmap.Config.ARGB_8888)
            view.draw(android.graphics.Canvas(bitmap))
            val directory = java.io.File(activity.filesDir, "swipe-evidence").apply { mkdirs() }
            java.io.File(directory, "$name.png").outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
            bitmap.recycle()
        }
    }
    @Test fun leftSwipeMovesToTrashAndUndoRestoresSharedAndExclusiveTags() {
        val one = seed("要左滑的文章")
        val two = seed("保留的文章")
        val unique = runBlocking { container.tagRepository.ensureTag("独有标签")!! }
        val shared = runBlocking { container.tagRepository.ensureTag("共用标签")!! }
        runBlocking {
            container.tagRepository.attachTag(one, unique)
            container.tagRepository.attachTag(one, shared)
            container.tagRepository.attachTag(two, shared)
        }
        ready(one)
        evidence("01-before-left-swipe")
        row(one).performTouchInput { swipeLeft() }
        compose.waitUntil(10000) { runBlocking { repo.findById(one)?.deletedAt != null } }
        compose.waitUntil(10000) { compose.onAllNodesWithText("独有标签").fetchSemanticsNodes().isEmpty() }
        evidence("02-after-left-swipe-trash-with-undo")
        assertEquals(setOf(unique, shared), runBlocking { container.database.tagDao().tagIdsForNote(one).toSet() })
        compose.onNodeWithText(compose.activity.getString(R.string.note_list_undo)).performClick()
        ready(one)
        evidence("03-after-undo")
        assertNull(runBlocking { repo.findById(one)!!.deletedAt })
        assertEquals(listOf(shared), runBlocking { container.database.tagDao().tagIdsForNote(two) })
        assertEquals(setOf(unique, shared), runBlocking { container.database.tagDao().tagIdsForNote(one).toSet() })
    }
    @Test fun rightShortAndVerticalGesturesDoNotDeleteAndTapStillOpens() {
        val id = seed("不能误删的文章")
        ready(id)
        row(id).performTouchInput { swipeRight() }
        assertGestureKeptList(id)
        row(id).performTouchInput { swipe(Offset(width * .85f, centerY), Offset(width * .75f, centerY), 700) }
        assertGestureKeptList(id)
        row(id).performTouchInput { swipeUp() }
        assertGestureKeptList(id)
        row(id).performClick()
        compose.onNodeWithTag(EditorTags.CONTENT).assertExists()
    }
    @Test fun trashOffDisablesSwipeAndPermanentDeleteCanBeCancelled() {
        container.noteSettings.setTrashEnabled(false)
        val id = seed("关闭回收站保护")
        ready(id)
        // Updating SharedPreferences is synchronous, observing it in the list is not.
        // Wait for the actual screen to reflect "trash off" before the gesture.
        compose.waitUntil(10000) {
            compose.onAllNodesWithTag(NoteListTags.TRASH).fetchSemanticsNodes().isEmpty()
        }
        row(id).performTouchInput { swipeLeft() }
        compose.waitForIdle()
        assertNotNull(runBlocking { repo.findById(id) })
        assertNull("A disabled swipe must not soft-delete", runBlocking { repo.findById(id)!!.deletedAt })
        evidence("05-after-disabled-swipe")
        android.util.Log.i("SwipeRegression", compose.onRoot().printToString())
        assertGestureKeptList(id)
        row(id).performTouchInput { swipeRight() }
        assertGestureKeptList(id)
        row(id).performTouchInput { swipe(Offset(width * .85f, centerY), Offset(width * .75f, centerY), 700) }
        assertGestureKeptList(id)
        row(id).performTouchInput { swipeUp() }
        assertGestureKeptList(id)
        row(id).assertExists().performTouchInput { longClick() }
        row(id).assertIsSelected()
        row(id).performTouchInput { swipeLeft() }
        row(id).assertIsSelected()
        row(id).performTouchInput { swipeRight() }
        row(id).assertIsSelected()
        assertGestureKeptList(id)
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.note_list_delete_selected)).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.dialog_cancel)).performClick()
        assertNotNull(runBlocking { repo.findById(id) })
        assertNull(runBlocking { repo.findById(id)!!.deletedAt })
        compose.onNodeWithContentDescription(compose.activity.getString(R.string.note_list_exit_selection)).performClick()
        row(id).performTouchInput { click() }
        compose.onNodeWithTag(EditorTags.CONTENT).assertExists()
        container.noteSettings.setTrashEnabled(true)
    }
    @Test fun trashRestoreAndConfirmedPermanentDeletePreserveSharedTag() {
        val one = seed("回收站生命周期")
        val two = seed("共用标签的其他文章")
        val tag = runBlocking { container.tagRepository.ensureTag("保留共用标签")!! }
        runBlocking {
            container.tagRepository.attachTag(one, tag)
            container.tagRepository.attachTag(two, tag)
        }
        ready(one)
        row(one).performTouchInput { swipeLeft() }
        compose.waitUntil(10000) { runBlocking { repo.findById(one)?.deletedAt != null } }
        compose.onNodeWithTag(NoteListTags.TRASH).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.trash_restore)).performClick()
        compose.waitUntil(10000) { runBlocking { repo.findById(one)?.deletedAt == null } }
        runBlocking { repo.moveToTrash(one) }
        compose.waitUntil(10000) { compose.onAllNodesWithText("回收站生命周期").fetchSemanticsNodes().isNotEmpty() }
        val delete = compose.activity.getString(R.string.trash_delete_forever)
        compose.onNodeWithText(delete).performClick()
        compose.onNodeWithText(compose.activity.getString(R.string.trash_cancel)).performClick()
        assertNotNull(runBlocking { repo.findById(one) })
        compose.onNodeWithText(delete).performClick()
        compose.onNode(hasText(compose.activity.getString(R.string.trash_confirm_action)) and hasAnyAncestor(isDialog())).performClick()
        compose.waitUntil(10000) { runBlocking { repo.findById(one) == null } }
        assertTrue(runBlocking { container.database.tagDao().tagIdsForNote(one).isEmpty() })
        assertEquals(listOf(tag), runBlocking { container.database.tagDao().tagIdsForNote(two) })
        assertNotNull(runBlocking { container.database.tagDao().findById(tag) })
    }
    @Test fun chineseBodySearchWorksAndNoMatchDoesNotEraseTagFilters() {
        val a = seed("第一篇", "只有正文包含杭州西湖")
        val b = seed("第二篇", "正文写会议")
        runBlocking {
            val travel = container.tagRepository.ensureTag("旅行")!!
            val work = container.tagRepository.ensureTag("工作")!!
            container.tagRepository.attachTag(a, travel)
            container.tagRepository.attachTag(b, work)
        }
        ready(a)
        compose.onNodeWithText("搜索正文").assertExists()
        compose.onNodeWithTag(NoteListTags.SEARCH).performTextInput("杭州西湖")
        compose.waitUntil(10000) { compose.onAllNodesWithTag("note_row_$b").fetchSemanticsNodes().isEmpty() }
        row(a).assertExists()
        evidence("04-chinese-body-search")
        compose.onNodeWithTag(NoteListTags.SEARCH).performTextReplacement("完全没有的词")
        compose.waitUntil(10000) { compose.onAllNodesWithTag("note_row_$a").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("旅行").assertExists()
        compose.onNodeWithText("工作").assertExists()
    }
}
