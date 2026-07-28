package org.xpra.client.client

/**
 * Characters to X11 keysym names.
 *
 * We upload no keymap, so the server resolves every key through `find_matching_keycode`
 * (`xpra/x11/server/keyboard_config.py:551`) by looking the *name* up in its own keymap. That
 * means `keyname` has to be a real X11 keysym name such as `braceleft` — not the character the
 * key produces. Table transcribed from `xpra-html5/html5/js/Keycodes.js`.
 */
object Keysyms {

    private val punctuation = mapOf(
        ' ' to "space", '!' to "exclam", '"' to "quotedbl", '#' to "numbersign",
        '$' to "dollar", '%' to "percent", '&' to "ampersand", '\'' to "apostrophe",
        '(' to "parenleft", ')' to "parenright", '*' to "asterisk", '+' to "plus",
        ',' to "comma", '-' to "minus", '.' to "period", '/' to "slash",
        ':' to "colon", ';' to "semicolon", '<' to "less", '=' to "equal",
        '>' to "greater", '?' to "question", '@' to "at",
        '[' to "bracketleft", '\\' to "backslash", ']' to "bracketright",
        '^' to "asciicircum", '_' to "underscore", '`' to "grave",
        '{' to "braceleft", '|' to "bar", '}' to "braceright", '~' to "asciitilde",
    )

    /**
     * The name for a character. Letters and digits are their own keysym name; everything else
     * comes from the table. Characters outside Latin-1 use the `UXXXX` form that X11 defines
     * for direct Unicode keysyms.
     */
    fun forChar(c: Char): String = when {
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' -> c.toString()
        punctuation.containsKey(c) -> punctuation.getValue(c)
        c.code in 0xA0..0xFF -> "U%04X".format(c.code)
        c == '\n' || c == '\r' -> "Return"
        c == '\t' -> "Tab"
        c.code < 0x20 -> ""
        else -> "U%04X".format(c.code)
    }

    // Named keys the soft keyboard row and the IME bridge need.
    const val BACKSPACE = "BackSpace"
    const val RETURN = "Return"
    const val TAB = "Tab"
    const val ESCAPE = "Escape"
    const val CONTROL = "Control_L"
    const val ALT = "Alt_L"
    const val SHIFT = "Shift_L"
    const val SUPER = "Super_L"

    /**
     * Modifier names as the server expects them, in X11 mask-bit order:
     * `shift lock control mod1 mod2 mod3 mod4 mod5`. Alt is mod1 and Super is mod4.
     */
    const val MOD_SHIFT = "shift"
    const val MOD_CONTROL = "control"
    const val MOD_ALT = "mod1"
    const val MOD_SUPER = "mod4"

    /** The keysym name a latching modifier key should send. */
    fun modifierKeyName(modifier: String): String = when (modifier) {
        MOD_CONTROL -> CONTROL
        MOD_ALT -> ALT
        MOD_SHIFT -> SHIFT
        MOD_SUPER -> SUPER
        else -> ""
    }
}
