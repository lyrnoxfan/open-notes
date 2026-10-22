package com.example.privatenotes

/**
 * Marks tests that a script runs against a dedicated install, not part of `connectedCheck`.
 *
 * They seed hundreds of rows, expect files pushed into the app's private directory, or kill their
 * own process mid-transaction. Running them in the shared instrumented suite would make other
 * tests' row counts depend on execution order, and one of them deliberately crashes the process.
 *
 * CI excludes them with `-Pandroid.testInstrumentationRunnerArguments.notAnnotation=...`; each one
 * is driven by scripts/canary-scan.sh or scripts/e2e-reinstall.sh.
 */
@Retention(AnnotationRetention.RUNTIME)
@Target(AnnotationTarget.CLASS)
annotation class ScriptDriven
