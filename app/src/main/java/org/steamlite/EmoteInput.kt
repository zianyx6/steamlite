package org.steamlite

import android.graphics.drawable.Drawable
import android.text.Editable
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ImageSpan
import android.widget.EditText
import android.content.Context
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import coil.imageLoader
import coil.request.ImageRequest

/** Lets the picker insert at the caret instead of always appending to the end. */
class EmoteInputHandle {
    internal var insertAtCursor: ((String) -> Unit)? = null
    fun insert(snippet: String) { insertAtCursor?.invoke(snippet) }
}

private val emoteToken = Regex("[:ː][^\\s:ː\\[\\]]{1,80}[:ː]")
private val stickerToken = Regex("""\[sticker\s+type="([^"]+)"[^\]]*\]\[/sticker\]""", RegexOption.IGNORE_CASE)

/** One inline replacement: [start, end) in the raw text, plus how big to draw it. */
private data class InlineSpan(val start: Int, val end: Int, val url: String, val scale: Float)

/** TextView probes the next editor when opening the IME. Do not traverse Compose lazy grids. */
internal class ChatEditText(context: Context) : EditText(context) {
    override fun focusSearch(direction: Int): View? = null
}

/**
 * The editable field renders emotes *and* stickers as ImageSpans while keeping the
 * original Steam markup underneath, so the server still receives correct codes and
 * cursor offsets stay valid.
 */
@Composable
fun EmoteInput(
    value: String,
    changed: (String) -> Unit,
    modifier: Modifier = Modifier,
    handle: EmoteInputHandle? = null
) {
    val context = LocalContext.current
    val callback by rememberUpdatedState(changed)
    var editor by remember { mutableStateOf<EditText?>(null) }

    val spans = remember(value) {
        buildList {
            emoteToken.findAll(value).forEach {
                add(InlineSpan(it.range.first, it.range.last + 1, ChatContent.emoteUrl(it.value), 1.4f))
            }
            stickerToken.findAll(value).forEach {
                add(InlineSpan(it.range.first, it.range.last + 1, ChatContent.stickerUrl(it.groupValues[1]), 3.2f))
            }
        }.sortedBy { it.start }
    }

    var images by remember { mutableStateOf<Map<String, Drawable>>(emptyMap()) }
    LaunchedEffect(spans.map { it.url }.distinct()) {
        val loaded = images.toMutableMap()
        spans.map { it.url }.distinct().take(40).forEach { url ->
            if (url !in loaded) {
                val result = context.imageLoader.execute(
                    ImageRequest.Builder(context).data(url).allowHardware(false).build()
                )
                result.drawable?.let { loaded[url] = it }
            }
        }
        images = loaded
    }

    LaunchedEffect(handle, editor) {
        handle?.insertAtCursor = { snippet ->
            val view = editor
            if (view == null) {
                callback(value + snippet)
            } else {
                val at = view.selectionStart.coerceIn(0, view.text.length)
                view.text.insert(at, snippet)
            }
        }
    }

    val textColor = Text1.toArgb()
    AndroidView(modifier = modifier, factory = { ctx ->
        ChatEditText(ctx).apply {
            setBackgroundColor(android.graphics.Color.TRANSPARENT)
            setPadding(0, 0, 0, 0)
            textSize = 13.5f
            maxLines = 4
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    callback(s.toString().take(6000))
                }
                override fun afterTextChanged(s: Editable?) = Unit
            })
        }
    }, update = { view ->
        editor = view
        if (view.currentTextColor != textColor) view.setTextColor(textColor)
        if (view.text.toString() != value) {
            view.setText(value)
            view.setSelection(value.length)
        }
        val editable = view.text
        editable.getSpans(0, editable.length, ImageSpan::class.java).forEach { editable.removeSpan(it) }
        spans.forEach { span ->
            if (span.end > editable.length) return@forEach
            val drawable = images[span.url]?.constantState?.newDrawable()?.mutate() ?: return@forEach
            val size = (view.textSize * span.scale).toInt()
            drawable.setBounds(0, 0, size, size)
            editable.setSpan(
                ImageSpan(drawable, ImageSpan.ALIGN_BASELINE),
                span.start,
                span.end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    })
}
