package app.shelf.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.util.Log
import android.util.Size
import androidx.documentfile.provider.DocumentFile
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.services.coverFitting
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.getOrElse
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.shared.util.mediatype.MediaType
import org.readium.r2.shared.util.toAbsoluteUrl
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.DefaultPublicationParser

private const val TAG = "ShelfLibrary"

/** Readium objects used to open EPUB files. */
class ReadiumServices(context: Context) {
    private val httpClient = DefaultHttpClient()
    val assetRetriever = AssetRetriever(context.contentResolver, httpClient)
    val publicationOpener = PublicationOpener(
        publicationParser = DefaultPublicationParser(
            context,
            httpClient = httpClient,
            assetRetriever = assetRetriever,
            pdfFactory = null,
        ),
    )

    /** Opens an EPUB from a content:// URI. Returns null when the file can't be read. */
    suspend fun open(uri: Uri): Publication? {
        val url = uri.toAbsoluteUrl() ?: return null
        val asset = assetRetriever.retrieve(url, MediaType.EPUB).getOrElse {
            Log.w(TAG, "Cannot retrieve $uri: $it")
            return null
        }
        return publicationOpener.open(asset, allowUserInteraction = false).getOrElse {
            Log.w(TAG, "Cannot open $uri: $it")
            asset.close()
            null
        }
    }
}

data class ScanResult(val added: Int, val failed: Int, val total: Int)

/** Finds EPUBs in the chosen folder and keeps the books table in sync with it. */
class LibraryScanner(
    private val context: Context,
    private val db: ShelfDatabase,
    private val readium: ReadiumServices,
) {
    private val coversDir = File(context.filesDir, "covers").apply { mkdirs() }

    suspend fun scan(treeUri: Uri): ScanResult = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext ScanResult(0, 0, 0)

        val files = mutableListOf<DocumentFile>()
        collectEpubs(root, files, depth = 0)

        val existing = db.books().all().associateBy { it.uri }
        val foundUris = files.map { it.uri.toString() }.toSet()
        var added = 0
        var failed = 0

        for (file in files) {
            val known = existing[file.uri.toString()]
            if (known != null) {
                if (known.missing) db.books().setMissing(known.id, false)
                continue
            }
            if (import(file)) added++ else failed++
        }
        for (book in existing.values) {
            if (book.uri !in foundUris && !book.missing) db.books().setMissing(book.id, true)
        }
        ScanResult(added = added, failed = failed, total = files.size)
    }

    private fun collectEpubs(dir: DocumentFile, out: MutableList<DocumentFile>, depth: Int) {
        if (depth > 4) return
        for (child in dir.listFiles()) {
            when {
                child.isDirectory -> collectEpubs(child, out, depth + 1)
                child.name?.endsWith(".epub", ignoreCase = true) == true -> out += child
            }
        }
    }

    private suspend fun import(file: DocumentFile): Boolean {
        val publication = readium.open(file.uri) ?: return false
        try {
            val fileName = file.name ?: "Untitled.epub"
            val title = publication.metadata.title?.takeIf { it.isNotBlank() }
                ?: fileName.removeSuffix(".epub")
            val author = publication.metadata.authors
                .mapNotNull { it.name.takeIf { n -> n.isNotBlank() } }
                .joinToString(", ")
                .ifBlank { null }
            val cover = runCatching { publication.coverFitting(Size(600, 900)) }.getOrNull()
            val coverPath = cover?.let { saveCover(it, file.uri) }

            db.books().insert(
                BookEntity(
                    uri = file.uri.toString(),
                    fileName = fileName,
                    title = title,
                    author = author,
                    coverPath = coverPath,
                    addedAt = System.currentTimeMillis(),
                )
            )
            return true
        } catch (e: Exception) {
            Log.w(TAG, "Import failed for ${file.uri}", e)
            return false
        } finally {
            publication.close()
        }
    }

    private fun saveCover(bitmap: Bitmap, uri: Uri): String? = runCatching {
        val out = File(coversDir, "${uri.toString().hashCode().toUInt()}.jpg")
        FileOutputStream(out).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        out.absolutePath
    }.getOrNull()
}

fun BookEntity.locator(): Locator? =
    locatorJson?.let { runCatching { Locator.fromJSON(JSONObject(it)) }.getOrNull() }
