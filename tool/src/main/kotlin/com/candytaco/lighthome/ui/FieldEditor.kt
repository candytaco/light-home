package com.candytaco.lighthome.ui

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.text.input.delete
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import com.thelightphone.lp3Keyboard.ui.KeyboardOptions
import com.thelightphone.lp3Keyboard.ui.LayoutOptions
import com.thelightphone.lp3Keyboard.ui.SpecialKey
import com.thelightphone.lp3Keyboard.ui.viewmodel.EnQwertyLp3KeyboardViewModel
import com.thelightphone.lp3Keyboard.ui.viewmodel.Lp3RepeatableKeyboardCallback
import com.thelightphone.sdk.rememberKeyboardOptions
import com.thelightphone.sdk.ui.LightTextInputEditor
import kotlinx.coroutines.flow.StateFlow

/**
 * One-field text entry on the LP3 keyboard. For [secret] fields the editor shows a dot per
 * character while the keyboard writes the real text into a separate state.
 *
 * [editorKey] must change whenever a fresh field is shown, so each gets its own keyboard state.
 */
@Composable
fun FieldEditor(
    title: String,
    initialValue: String,
    secret: Boolean,
    submitLabel: String,
    editorKey: String,
    onSubmit: (String) -> Unit,
    onBack: () -> Unit,
    onHaptic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val value = rememberTextFieldState(initialValue)
    val display = if (secret) rememberTextFieldState("") else value
    if (secret) {
        LaunchedEffect(value) {
            snapshotFlow { value.text.length }.collect { length ->
                display.setTextAndPlaceCursorAtEnd(MASK.repeat(length))
            }
        }
    }

    val currentOnSubmit by rememberUpdatedState(onSubmit)
    val currentOnHaptic by rememberUpdatedState(onHaptic)
    val submit = { currentOnSubmit(value.text.toString()) }

    val keyboardOptions = rememberKeyboardOptions()
    val callback = remember(value) {
        FieldKeyboardCallback(state = value, onReturn = submit, onHaptic = { currentOnHaptic() })
    }
    val keyboard: EnQwertyLp3KeyboardViewModel<*> = viewModel(
        key = "lighthome-field-$editorKey",
        factory = keyboardFactory(callback, keyboardOptions),
    )

    LightTextInputEditor(
        title = title,
        state = display,
        onSubmit = { submit() },
        onBack = onBack,
        viewModel = keyboard,
        modifier = modifier,
        submitLabel = submitLabel,
        singleLine = true,
    )
}

private const val MASK = "•"

private fun keyboardFactory(
    callback: Lp3RepeatableKeyboardCallback,
    keyboardOptions: StateFlow<KeyboardOptions>,
): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T =
        EnQwertyLp3KeyboardViewModel<Unit>(
            callback,
            keyboardOptionsFlow = keyboardOptions,
            optionsForLayout = { LayoutOptions(!it.isRootLayout) },
        ) as T
}

/**
 * Same editing behavior as the SDK's (internal) keyboard callback, single-line: Return submits.
 * Edits always go to [state], which for secret fields is not the state being displayed.
 */
private class FieldKeyboardCallback(
    private val state: TextFieldState,
    private val onReturn: () -> Unit,
    private val onHaptic: () -> Unit,
) : Lp3RepeatableKeyboardCallback {

    override fun onKeyPressed(code: Int) = onHaptic()

    override fun onSpecialKeyPressed(key: SpecialKey) {
        onHaptic()
        if (key == SpecialKey.Space) insertAtCursor(" ")
    }

    override fun onKeyReleased(code: Int) = insertCodePoint(code)

    override fun onSpecialKeyReleased(key: SpecialKey) {
        when (key) {
            SpecialKey.Backspace -> deleteBeforeCursor(charCountBeforeCursor())
            SpecialKey.Return -> onReturn()
            else -> Unit
        }
    }

    override fun onKeyLongPressed(code: Int) = Unit

    override fun onSpecialKeyLongPressed(key: SpecialKey) {
        if (key == SpecialKey.Backspace) deleteBeforeCursor(wordLengthBeforeCursor())
    }

    override fun onKeyRepeated(code: Int) = insertCodePoint(code)

    override fun onSpecialKeyRepeated(specialKey: SpecialKey) {
        if (specialKey == SpecialKey.Space) insertAtCursor(" ")
    }

    override fun onSubmitWord(word: CharSequence) = insertAtCursor(word.toString())

    private fun insertCodePoint(code: Int) = insertAtCursor(buildString { appendCodePoint(code) })

    private fun insertAtCursor(text: String) {
        state.edit {
            val start = selection.min
            replace(start, selection.max, text)
            selection = TextRange(start + text.length)
        }
    }

    private fun deleteBeforeCursor(count: Int) {
        if (count <= 0) return
        state.edit {
            val end = selection.min
            if (end == 0) return@edit
            val start = (end - count).coerceAtLeast(0)
            delete(start, end)
            selection = TextRange(start)
        }
    }

    private fun textBeforeCursor(): CharSequence = state.text.subSequence(0, state.selection.min)

    /** One character, or two for a surrogate pair (emoji). */
    private fun charCountBeforeCursor(): Int {
        val before = textBeforeCursor()
        if (before.isEmpty()) return 0
        return if (Character.isLowSurrogate(before[before.length - 1])) 2 else 1
    }

    private fun wordLengthBeforeCursor(): Int {
        val before = textBeforeCursor()
        val lastSpace = before.trimEnd().indexOfLast { it.isWhitespace() }
        return before.length - if (lastSpace >= 0) lastSpace + 1 else 0
    }
}
