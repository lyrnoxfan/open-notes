# Long-document editor / first keyboard regression

## Reported behaviour

A note with at least 100 identifiable lines works on the initial screen. Scrolling beyond that
screen and opening the keyboard for the first time can move the view back to the document top.
Subsequent taps during that editor entry behave differently; leaving for the list and opening the
same note again resets the problem. This test is intentionally not a storage-only long-text test.

## Baseline (old production editor, c5d019d)

Real `MainActivity`, soft keyboard, physical swipe/tap coordinates; security flags unchanged.
GitHub Actions run 39866403239, both Android API 26 and API 35:

- The line-100 tap selects offset 1584 correctly, but the rendered caret after the keyboard opens
  is at y=8217..8300 relative to the editable viewport, outside the visible height (945 on API 26,
  866 on API 35). The text view has moved back toward the beginning instead of following the
  tapped selection. This demonstrates a visual scrolling/IME error, not incorrect saved content.
- The mixed interaction test also failed visibility at line 31 on API 26 and line 60 on API 35.
- First-screen line 5 passed. Failure logs are preserved as `long-document.txt` in the respective
  isolated-device-reports artifacts.

These baseline results establish a genuine missing interaction regression in the earlier work.
They do not by themselves validate a repair or every detail of the user's repeated-entry cycle.

## Regression protocol

The current test uses 100 individually numbered Chinese/Latin lines in the real activity:

1. Physically scroll, tap lines 31, 60 and 100, assert the logical selection and visible caret.
2. Insert/delete characters and newlines; long-press row 100 to select its `abc` word and replace it.
3. Return to the list, reopen, and compare the complete saved content.
4. In a separate test, open an existing note, scroll to row 100, tap for the first IME invocation,
   sample rendered text-origin coordinates on actual UI frames, and assert no jump to top.
5. Dismiss/reopen the keyboard for the second tap. Then return to the list, reopen the same note,
   and repeat the first-invocation cycle three times.
6. Repeat on API 26 and 35, default geometry and 320dp width with 2x system fonts. Insets animations
   are enabled in the comparison run. A control restores only the baseline production editor,
   leaving the new tests identical.

The oracle uses the actual rendered text layout coordinates, not the text field's own hit-testing
answer. Layout-tree introspection is read-only and test-only, pinned to the current Compose
version. It does not disable `FLAG_SECURE`, authentication, encryption or other protections.

## Repair under validation

The body moves to the state-based text field, with one text/selection/composition buffer and one
native scroll state. Saved String echoes no longer replace that buffer or reset its selection.
On viewport-height changes only, the current caret is checked against the resized viewport and
brought into view through that same scroll state. Reading/dragging is not keyed to the old caret,
and no nested vertical scroll owner is added. Exit/preview/delete handoffs flush current text.

Validation is still in progress. The initial build compiles. The first comparison's baseline
setup failed because a shallow checkout did not contain the old commit; those failures are
infrastructure failures, not reproduction evidence. The old-editor run above remains the actual
baseline evidence. Do not call the patch accepted before the current interaction checks run.
