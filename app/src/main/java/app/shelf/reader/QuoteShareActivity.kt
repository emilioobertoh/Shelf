package app.shelf.reader

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import app.shelf.shelf
import app.shelf.ui.Accent
import app.shelf.ui.ShelfTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Preview of a quote Story, with Instagram, Share… and Save. */
class QuoteShareActivity : ComponentActivity() {

    private class Book(val title: String, val author: String, val cover: Bitmap?)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        val bookId = intent.getLongExtra(EXTRA_BOOK_ID, -1)
        val quote = intent.getStringExtra(EXTRA_QUOTE).orEmpty()
        val hasInstagram = isInstalled(INSTAGRAM)

        setContent {
            ShelfTheme {
                var book by remember { mutableStateOf<Book?>(null) }
                var style by remember { mutableStateOf(CardStyle.Light) }
                var image by remember { mutableStateOf<Bitmap?>(null) }
                val scope = rememberCoroutineScope()
                val renderer = remember { QuoteCardRenderer(this) }

                LaunchedEffect(Unit) {
                    book = withContext(Dispatchers.IO) {
                        val entity = shelf.db.books().get(bookId)
                        Book(
                            title = entity?.title.orEmpty(),
                            author = entity?.author.orEmpty(),
                            cover = entity?.coverPath?.let { BitmapFactory.decodeFile(it) },
                        )
                    }
                }
                LaunchedEffect(book, style) {
                    val b = book ?: return@LaunchedEffect
                    image = withContext(Dispatchers.Default) {
                        renderer.render(quote, b.title, b.author, b.cover, StoryPalette.from(b.cover, style))
                    }
                }

                QuotePreview(
                    image = image,
                    style = style,
                    hasInstagram = hasInstagram,
                    onStyle = { style = it },
                    onClose = { finish() },
                    onInstagram = { image?.let { scope.launch { share(it, toInstagram = true) } } },
                    onShare = { image?.let { scope.launch { share(it, toInstagram = false) } } },
                    onSave = { image?.let { scope.launch { save(it) } } },
                )
            }
        }
    }

    private suspend fun share(bitmap: Bitmap, toInstagram: Boolean) {
        val uri = withContext(Dispatchers.IO) { writeShareFile(bitmap) }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newRawUri(null, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (toInstagram) {
            // Instagram registers a separate share target per destination. Go straight to its
            // Story one when it's there; otherwise let Android show Instagram's targets.
            send.setPackage(INSTAGRAM)
            val story = packageManager.queryIntentActivities(send, 0)
                .map { it.activityInfo }
                .firstOrNull { it.name.endsWith(".StoryShareHandlerActivity") }
            if (story != null) send.setClassName(story.packageName, story.name)
            startActivity(send)
        } else {
            startActivity(Intent.createChooser(send, null))
        }
    }

    /** Each share gets a fresh file name so apps that cache by URI never show an older card. */
    private fun writeShareFile(bitmap: Bitmap): Uri {
        val dir = File(cacheDir, "shared").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "quote-${System.currentTimeMillis()}.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return FileProvider.getUriForFile(this, "$packageName.fileprovider", file)
    }

    private suspend fun save(bitmap: Bitmap) {
        val saved = withContext(Dispatchers.IO) {
            runCatching {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, "Shelf quote ${System.currentTimeMillis()}.png")
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Shelf")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)!!
                contentResolver.openOutputStream(uri)!!.use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
            }.isSuccess
        }
        Toast.makeText(this, if (saved) "Saved to Pictures/Shelf" else "Couldn't save the image", Toast.LENGTH_SHORT).show()
    }

    private fun isInstalled(pkg: String) =
        runCatching { packageManager.getPackageInfo(pkg, 0) }.isSuccess

    companion object {
        private const val EXTRA_BOOK_ID = "book_id"
        private const val EXTRA_QUOTE = "quote"
        private const val INSTAGRAM = "com.instagram.android"

        fun intent(context: Context, bookId: Long, quote: String) =
            Intent(context, QuoteShareActivity::class.java)
                .putExtra(EXTRA_BOOK_ID, bookId)
                .putExtra(EXTRA_QUOTE, quote)
    }
}

@Composable
private fun QuotePreview(
    image: Bitmap?,
    style: CardStyle,
    hasInstagram: Boolean,
    onStyle: (CardStyle) -> Unit,
    onClose: () -> Unit,
    onInstagram: () -> Unit,
    onShare: () -> Unit,
    onSave: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            Text("Share Quote", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        }

        Box(
            Modifier.weight(1f).fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (image == null) {
                CircularProgressIndicator(color = Accent)
            } else {
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = "Story preview",
                    modifier = Modifier
                        .aspectRatio(9f / 16f)
                        .clip(RoundedCornerShape(16.dp)),
                )
            }
        }

        SingleChoiceSegmentedButtonRow {
            CardStyle.entries.forEachIndexed { i, s ->
                SegmentedButton(
                    selected = style == s,
                    onClick = { onStyle(s) },
                    shape = SegmentedButtonDefaults.itemShape(i, CardStyle.entries.size),
                ) { Text(if (s == CardStyle.Light) "Light card" else "Dark card") }
            }
        }
        Spacer(Modifier.height(14.dp))

        if (hasInstagram) {
            Button(
                onClick = onInstagram,
                enabled = image != null,
                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Color.White),
                modifier = Modifier.fillMaxWidth().height(52.dp),
            ) { Text("Share to Instagram Story", fontSize = 16.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.height(10.dp))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val outlined = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
            OutlinedButton(onClick = onShare, enabled = image != null, colors = outlined,
                modifier = Modifier.weight(1f).height(48.dp)) { Text("Share…") }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                OutlinedButton(onClick = onSave, enabled = image != null, colors = outlined,
                    modifier = Modifier.weight(1f).height(48.dp)) { Text("Save") }
            }
        }
        Spacer(Modifier.size(8.dp))
    }
}
