package org.xpra.client.ui.session

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import org.xpra.client.client.Keysyms
import org.xpra.client.client.XpraClient

/**
 * The bridge between Android's IME and `keyboard-event` packets.
 *
 * We upload no keymap, so the server resolves keys by X11 keysym *name*. An IME gives us
 * committed text rather than key events, so this follows the same trick the html5 client uses
 * for tablets (`index.html:1025`): keep a hidden text field, watch it change, and turn the
 * difference into press/release pairs.
 */
@Composable
fun HiddenKeyboardInput(
    client: XpraClient,
    modifiers: () -> List<String>,
    modifier: Modifier = Modifier,
    onEditorReady: (EditText) -> Unit,
) {
    // The factory below runs once, so the listeners it installs would keep the `modifiers`
    // lambda from the first composition -- and with it the latched set as it was then, which
    // is empty. Read it through rememberUpdatedState so a latched Ctrl is actually seen.
    val currentModifiers by rememberUpdatedState(modifiers)
    val liveModifiers = { currentModifiers() }
    AndroidView(
        modifier = modifier,
        factory = { context ->
            EditText(context).apply {
                // No autocorrect or suggestions: they rewrite text after the fact, which would
                // reach the server as a burst of backspaces and retyped characters.
                inputType = InputType.TYPE_CLASS_TEXT or
                    InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or
                    EditorInfo.IME_FLAG_NO_FULLSCREEN or
                    EditorInfo.IME_ACTION_NONE
                isSingleLine = true
                // Invisible but focusable: it exists only to summon the IME.
                alpha = 0f
                setBackgroundColor(0)
                setTextColor(0)

                // A hardware keyboard, or the IME's own Enter/Backspace, arrive as key events
                // and can be forwarded directly.
                setOnKeyListener { _, keyCode, event ->
                    val name = when (keyCode) {
                        KeyEvent.KEYCODE_DEL -> Keysyms.BACKSPACE
                        KeyEvent.KEYCODE_ENTER -> Keysyms.RETURN
                        KeyEvent.KEYCODE_TAB -> Keysyms.TAB
                        KeyEvent.KEYCODE_ESCAPE -> Keysyms.ESCAPE
                        KeyEvent.KEYCODE_DPAD_LEFT -> "Left"
                        KeyEvent.KEYCODE_DPAD_RIGHT -> "Right"
                        KeyEvent.KEYCODE_DPAD_UP -> "Up"
                        KeyEvent.KEYCODE_DPAD_DOWN -> "Down"
                        else -> ""
                    }
                    if (name.isEmpty()) return@setOnKeyListener false
                    if (event.action == KeyEvent.ACTION_DOWN) {
                        client.typeNamedKey(name, liveModifiers())
                    }
                    true
                }

                // Seed the sentinel *before* attaching the watcher, so the first character the
                // user types is seen as an insertion rather than as the sentinel going missing.
                setText(KeyForwarder.SENTINEL)
                setSelection(KeyForwarder.SENTINEL.length)
                addTextChangedListener(KeyForwarder(this, client, liveModifiers))
                onEditorReady(this)
            }
        },
    )
}

/**
 * Diffs the field against a sentinel and emits the characters that changed.
 *
 * The field is reset to [SENTINEL] after every change, with the cursor at the end, so that a
 * backspace on an "empty" field still produces an observable edit — otherwise the IME would
 * swallow it and the remote side would never see a BackSpace.
 */
private class KeyForwarder(
    private val field: EditText,
    private val client: XpraClient,
    private val modifiers: () -> List<String>,
) : TextWatcher {

    companion object {
        /**
         * A zero-width space that always sits at the start of the field. Without it a
         * backspace on an empty field produces no edit at all, and the remote side would never
         * see a BackSpace.
         */
        const val SENTINEL = "​"
    }

    private var suppress = false

    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit

    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit

    override fun afterTextChanged(s: Editable?) {
        if (suppress) return
        val text = s?.toString().orEmpty()
        val mods = modifiers()

        if (text.length < SENTINEL.length || !text.startsWith(SENTINEL)) {
            // The sentinel itself was deleted: that is a backspace.
            client.typeNamedKey(Keysyms.BACKSPACE, mods)
        } else {
            for (c in text.substring(SENTINEL.length)) {
                when (c) {
                    '\n' -> client.typeNamedKey(Keysyms.RETURN, mods)
                    '\t' -> client.typeNamedKey(Keysyms.TAB, mods)
                    else -> client.typeChar(c, mods)
                }
            }
        }

        suppress = true
        field.setText(SENTINEL)
        field.setSelection(SENTINEL.length)
        suppress = false
    }
}

fun EditText.showIme() {
    requestFocus()
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
}

fun EditText.hideIme() {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    imm.hideSoftInputFromWindow(windowToken, 0)
    clearFocus()
}

/**
 * The keys a phone keyboard has no room for, plus latching Ctrl/Alt/Super.
 *
 * A latched modifier is both included in the `modifiers` list of the next keyboard-event *and*
 * bracketed with a press/release of its own keysym, which is what the server's keymap lookup
 * expects for a chord like Ctrl-C.
 */
@Composable
fun ModifierRow(
    client: XpraClient,
    latched: Set<String>,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        listOf(
            "Ctrl" to Keysyms.MOD_CONTROL,
            "Alt" to Keysyms.MOD_ALT,
            "Super" to Keysyms.MOD_SUPER,
        ).forEach { (label, mod) ->
            FilterChip(
                selected = mod in latched,
                onClick = { onToggle(mod) },
                label = { Text(label) },
            )
        }
        listOf(
            "Esc" to Keysyms.ESCAPE,
            "Tab" to Keysyms.TAB,
            "←" to "Left",
            "↓" to "Down",
            "↑" to "Up",
            "→" to "Right",
            "PgUp" to "Page_Up",
            "PgDn" to "Page_Down",
            "Home" to "Home",
            "End" to "End",
            "Del" to "Delete",
        ).forEach { (label, keyname) ->
            AssistChip(
                onClick = { client.typeNamedKey(keyname, latched.toList()) },
                label = { Text(label) },
            )
        }
    }
}

/** Tracks which modifiers are currently latched. */
@Composable
fun rememberLatchedModifiers(): Pair<Set<String>, (String) -> Unit> {
    var latched by remember { mutableStateOf(emptySet<String>()) }
    return latched to { mod: String ->
        latched = if (mod in latched) latched - mod else latched + mod
    }
}
