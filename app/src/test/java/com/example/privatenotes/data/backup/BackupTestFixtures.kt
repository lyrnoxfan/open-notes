package com.example.privatenotes.data.backup

import android.net.Uri

internal fun backupNote(
    id: String,
    title: String = "title-$id",
    content: String = "body-$id",
    createdAt: Long = 100L,
    updatedAt: Long = createdAt,
    deletedAt: Long? = null,
): BackupNote = BackupNote(
    id = id,
    title = title,
    content = content,
    createdAt = createdAt,
    updatedAt = updatedAt,
    deletedAt = deletedAt,
)

internal fun backupTag(
    id: String,
    name: String = "tag-$id",
    createdAt: Long = 100L,
    updatedAt: Long = createdAt,
): BackupTag = BackupTag(id = id, name = name, createdAt = createdAt, updatedAt = updatedAt)

internal fun backupDocument(
    notes: List<BackupNote> = emptyList(),
    tags: List<BackupTag> = emptyList(),
    noteTags: List<BackupNoteTag> = emptyList(),
    markers: List<BackupDeletionMarker> = emptyList(),
    exportedAt: Long = 10_000L,
    schemaVersion: Int = BackupDocument.CURRENT_SCHEMA_VERSION,
): BackupDocument = BackupDocument(
    format = BackupDocument.FORMAT,
    schemaVersion = schemaVersion,
    appVersion = "test",
    exportedAt = exportedAt,
    notes = notes,
    tags = tags,
    noteTags = noteTags,
    deletionMarkers = markers,
)

internal class FakeBackupDirectory(
    var configured: Boolean = true,
    var authorized: Boolean = true,
    var failure: BackupException? = null,
) : BackupDirectory {

    val files = linkedMapOf<String, ByteArray>()
    val deleted = mutableListOf<String>()

    override fun displayName(): String? = "Test backup folder"

    override fun isConfigured(): Boolean = configured

    override fun isAuthorized(): Boolean = authorized

    override suspend fun listBackupFiles(): List<BackupDirectoryEntry> =
        files.keys.sortedDescending().map { name ->
            BackupDirectoryEntry(
                name = name,
                documentId = name,
                sizeBytes = files.getValue(name).size.toLong(),
                lastModified = 0L,
            )
        }

    override suspend fun writeBackup(
        baseName: String,
        mimeType: String,
        bytes: ByteArray,
    ): StoredBackupFile {
        failure?.let { throw it }
        var candidate = baseName
        var suffix = 1
        while (candidate in files) {
            suffix++
            candidate = BackupFileNames.withCollisionSuffix(baseName, suffix)
        }
        files[candidate] = bytes.copyOf()
        return StoredBackupFile(name = candidate, sizeBytes = bytes.size.toLong())
    }

    override suspend fun delete(entry: BackupDirectoryEntry) {
        deleted += entry.name
        files.remove(entry.name)
    }
}

internal class FakeDocumentSource(
    var bytes: ByteArray,
    private val displayName: String = "backup.json",
) : BackupDocumentSource {

    val uri: Uri = Uri.parse("content://test/backup")

    override suspend fun inspect(uri: Uri): BackupDocumentInfo = BackupDocumentInfo(
        displayName = displayName,
        sizeBytes = bytes.size.toLong(),
        kind = when {
            BackupCrypto.isEncrypted(bytes) -> BackupFileKind.ENCRYPTED
            String(bytes, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n').startsWith("{") ->
                BackupFileKind.PLAINTEXT_JSON
            else -> BackupFileKind.UNKNOWN
        },
    )

    override suspend fun read(uri: Uri): ByteArray = bytes.copyOf()
}
