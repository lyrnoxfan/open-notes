# Right-swipe, search and tag-filter regression

Base: main `31e5b8e` (includes merged rounded UI and the long-document first-IME fix).

## Reproduction and scope

The old row only enabled EndToStart (left) dismiss. The required interaction is right swipe.
The recent tag change derived the tag catalog from the current search/tag-filtered rows.
Before production changes, the new `NoteListTagRegressionTest` ran three tests: two failed
(search/filter erased unrelated tag options; deleting the last selected-tag note left a dead
filter), and the shared-tag preservation test passed.

## Fix

- Enable right dismiss only; short/left/vertical gestures do not delete, undo remains available.
  Gestures are disabled with trash off; irreversible deletion still requires confirmation.
- Search placeholder is exactly `搜索正文`. Existing title/body matching remains compatible;
  Chinese body-only matches are tested using the actual MainActivity and encrypted database.
- Derive available tag chips from all non-deleted notes, independently of current search or
  selected tag. Clear a selection only if no active note uses that tag.
- Soft deletion keeps note-tag joins for restore; permanent deletion's existing foreign-key
  cascade removes only that note's joins. Other notes' shared labels and global tag records
  are not automatically destroyed. The UI/tag fix does not alter deletion schema, security, IME
  or editor behavior; the later connection-lifecycle follow-up is described below.

## Verification

Local and CI results will be recorded after verification, before merging to main.
The isolated `SwipeDeleteRegressionTest` uses physical pointer gestures in MainActivity,
checks undo, trash restore, cancel/confirm permanent delete, shared tag survival and body search.
Its PNGs are actual app-view captures with seeded test notes, not component previews or user
notes. FLAG_SECURE is unchanged; app-view captures omit system windows.
Existing API26/API35 long-document tests still run at default size and 320dp/2x fonts.

## Disabled swipe must not become a click

The original `27c2b33` device run failed on both API26 and API35 after a right drag with
trash disabled. The diagnostic `c7feb45` waited for the trash entry to disappear and proved
that the note still existed and was not soft-deleted, but `editor_content` was present.
Actual app-view captures show that same seeded note open in the editor.

In this mode the row had no horizontal drag recognizer: the dismiss wrapper was omitted,
leaving `combinedClickable` on a wide row. A horizontal move remaining within the clickable
bounds could therefore finish as a click. The plain wrapper now consumes horizontal drags
after touch slop, even when deletion is unavailable, while the list retains vertical scrolling
and the row retains click and long-click handling. Regression assertions cover no unintended
navigation after right/left/short/vertical gestures, with real tap and long press still usable.

The unchanged no-editor assertion failed on the preceding implementation. Final device
results for this fix are recorded below only after the corresponding CI completes.

## Transaction retirement and SQLCipher locks

The first gesture-fixed build (`45c21eb`) passed the isolated gesture/search tests on both
APIs and the API35 suite, but API26's five auto-save tests then hit SQLite database locks.
The logs indicated a connection still in use while its pool was closing.

The retired-helper check was executed for every writable/readable getter. Room also calls
that getter to finish an already-started transaction. Retirement between begin and end could
therefore throw during transaction cleanup, stranding a native write lock. The added
`retiringDuringATransactionLetsItFinishButRefusesNewAccess` regression is submitted separately
in `9d444b3` against the unchanged provider as a device-level control. CI `39883192725`
failed that regression on API26 with `IllegalStateException: test connection retired` from
`EncryptedDatabaseProvider.getWritableDatabase` through Room `internalEndTransaction`.
This directly verifies the cleanup failure; it does not prove every historical lock had this cause.

The proposed lifecycle fix caches only a connection the helper has already opened. A thread
already inside a transaction may reuse that live connection to finish; it never calls the
SQLCipher opener on that path. SQLCipher 4.12's `inTransaction()` reads its thread session,
so another thread cannot acquire this exception. All non-transactional or closed-handle
access still executes the original retirement/app-lock guard before opening. Additional
coverage checks cross-thread refusal, post-transaction refusal, persistence and a fresh write
through a distinct connection. Existing retired-DAO and app-lock tests remain unchanged.

Room also calls `inTransaction()` after ending a transaction. AppDatabase now answers that
state-only query from the provider's already-open connection, without opening or querying SQL.
This prevents a second retirement exception after the native transaction has finished.

Device results are pending; this section does not claim final validation or main integration.

The helper's close path also waits for transactions already begun on its connection. A shared
read lock spans each native begin/end pair; asynchronous close takes the write lock, so the
native pool is not torn down underneath a transaction. Retirement is still marked before
close, and new transaction starts recheck the guard. A separate device regression starts
actual close on another thread during a retired transaction, verifies it waits, then verifies
close completes and a new connection can persist writes. This closes the pool-shutdown race,
not just the helper-getter cleanup failure.
