# Rounded interface refresh

The new visual direction is modern, inset and softly rounded, inspired by iOS grouping without replacing Android navigation or input behavior.

- Warm white `#F7F6F2`, clean white cards, dark green `#26332E`, sage `#668575` actions, mist blue `#DCE8EA` search/hints. Terracotta is reserved for attention/destructive semantics, not normal body text.
- System sans semibold titles/navigation; existing bundled writing face retained for body text. No new font, blur layer or animation dependency.
- 22dp inset list/settings/tag/trash cards, 14dp action buttons, 26dp large dialog/sheet shape. Filter chips and small switches keep their existing capsule shapes. Create button is a rounded square with restrained 2dp elevation rather than a floating circle.
- Removed redundant full-width dividers underneath already separated cards. Search has a soft fill and an accessible active outline. Selection, swipe deletion, secondary actions and destructive confirmation remain distinct.
- Both color schemes map all Material surface roles. Contrast tests require 7:1 body text and 4.5:1 controls/secondary text, including the mist-blue surface.
- No editor state/scroll, repository, encryption, authentication or FLAG_SECURE changes.

## Verification

CI runs unit tests, lint, Debug APK build, API26/API35 ordinary device suites, real MainActivity 100-line editing and repeated first-IME/re-entry cycles at default and 320dp/2x font, backup/reinstall/fault/privacy suites. The extended renderer produces 10 production-component previews with synthetic text (list/editor/settings/trash, light/dark plus narrow large-text list/editor). The settings renderer substitutes an inert text summary for the lock section; it contains no credential or authentication state.

Actual MainActivity long-document view captures remain separate, preserve FLAG_SECURE and exclude the system keyboard window. Their viewports/IME insets are independently asserted. The device script now copies these images before test-data resets and CI uploads them, fixing the previous missing PNG artifact path.

This file describes the test plan, not its result. Final CI/job results and screenshots are recorded in the PR after completion. Debug builds and emulator validation are not a production release or proof for all physical devices/OEM keyboards.
