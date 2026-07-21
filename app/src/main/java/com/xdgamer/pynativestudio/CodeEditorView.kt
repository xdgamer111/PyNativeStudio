package com.xdgamer.pynativestudio

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.ArrayDeque
import java.util.regex.Pattern

/** A lightweight native Python editor with line numbers and auto-indentation. */
class CodeEditorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    private val lineNumbers = TextView(context)
    val editText = EditText(context)

    private val undoHistory = ArrayDeque<String>()
    private val redoHistory = ArrayDeque<String>()

    private var internalChange = false
    private var previousText = ""
    private var onChanged: ((String) -> Unit)? = null

    private val keywords = Pattern.compile(
        "\\b(False|None|True|and|as|assert|async|await|break|case|" +
            "class|continue|def|del|elif|else|except|finally|for|from|" +
            "global|if|import|in|is|lambda|match|nonlocal|not|or|pass|" +
            "raise|return|try|while|with|yield)\\b"
    )

    private val builtIns = Pattern.compile(
        "\\b(abs|all|any|bin|bool|bytearray|bytes|callable|chr|" +
            "classmethod|compile|complex|dict|dir|divmod|enumerate|eval|" +
            "exec|filter|float|format|frozenset|getattr|globals|hasattr|" +
            "hash|help|hex|id|input|int|isinstance|issubclass|iter|len|" +
            "list|locals|map|max|memoryview|min|next|object|oct|open|ord|" +
            "pow|print|property|range|repr|reversed|round|set|setattr|" +
            "slice|sorted|staticmethod|str|sum|super|tuple|type|vars|zip|" +
            "__import__)\\b"
    )

    private val numbers = Pattern.compile(
        "(?<![\\w.])(?:0[xX][0-9a-fA-F]+|0[bB][01]+|0[oO][0-7]+|" +
            "\\d+(?:\\.\\d+)?(?:[eE][+-]?\\d+)?)"
    )

    private val declarations = Pattern.compile(
        "(?m)(?<=\\bdef\\s)[A-Za-z_]\\w*|" +
            "(?<=\\bclass\\s)[A-Za-z_]\\w*"
    )

    private val operators = Pattern.compile(
        "[+\\-*/%@&|^~<>]=?|==|!=|:=|//|\\*\\*"
    )

    private val strings = Pattern.compile(
        "(?s)(?:[rubfRUBF]{0,2})(\"\"\".*?\"\"\"|'''.*?'''|" +
            "\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*')"
    )

    private val comments = Pattern.compile("(?m)#.*$")

    init {
        orientation = HORIZONTAL
        configureLineNumbers()
        configureEditor()
        configureTextWatcher()
        configureTabKey()
        applyTheme(light = false)
    }

    private fun configureLineNumbers() {
        lineNumbers.apply {
            gravity = Gravity.TOP or Gravity.END
            setPadding(10, 14, 10, 14)
            typeface = Typeface.MONOSPACE
            textSize = 14f
            alpha = 0.62f
            text = "1"
        }

        addView(
            lineNumbers,
            LayoutParams(
                LayoutParams.WRAP_CONTENT,
                LayoutParams.MATCH_PARENT
            )
        )
    }

    private fun configureEditor() {
        val horizontalScroller = HorizontalScrollView(context).apply {
            isFillViewport = true
        }

        editText.apply {
            gravity = Gravity.TOP or Gravity.START
            setPadding(12, 10, 24, 24)
            typeface = Typeface.MONOSPACE
            textSize = 15f
            setHorizontallyScrolling(true)
            isVerticalScrollBarEnabled = true
            imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI
            inputType =
                InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        }

        horizontalScroller.addView(
            editText,
            LayoutParams(
                LayoutParams.MATCH_PARENT,
                LayoutParams.MATCH_PARENT
            )
        )

        addView(
            horizontalScroller,
            LayoutParams(
                0,
                LayoutParams.MATCH_PARENT,
                1f
            )
        )
    }

    private fun configureTextWatcher() {
        editText.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(
                text: CharSequence?,
                start: Int,
                count: Int,
                after: Int
            ) {
                if (!internalChange && text != null) {
                    previousText = text.toString()
                }
            }

            override fun onTextChanged(
                text: CharSequence?,
                start: Int,
                before: Int,
                count: Int
            ) = Unit

            override fun afterTextChanged(editable: Editable?) {
                if (internalChange || editable == null) {
                    return
                }

                if (previousText != editable.toString()) {
                    undoHistory.addLast(previousText)
                    while (undoHistory.size > MAX_HISTORY_ITEMS) {
                        undoHistory.removeFirst()
                    }
                    redoHistory.clear()
                }

                handlePairsAndIndentation(editable)
                updateLineNumbers(editable)
                highlight(editable)
                onChanged?.invoke(editable.toString())
            }
        })
    }

    private fun configureTabKey() {
        editText.setOnKeyListener { _, keyCode, event ->
            if (
                keyCode == KeyEvent.KEYCODE_TAB &&
                event.action == KeyEvent.ACTION_DOWN
            ) {
                insertIndentation()
                true
            } else {
                false
            }
        }
    }

    fun setOnContentChanged(listener: (String) -> Unit) {
        onChanged = listener
    }

    fun setText(value: String) {
        internalChange = true
        editText.setText(value)
        editText.setSelection(value.length)
        undoHistory.clear()
        redoHistory.clear()
        previousText = value
        updateLineNumbers(editText.text)
        highlight(editText.text)
        internalChange = false
    }

    fun text(): String = editText.text.toString()

    fun setFontSize(sizeSp: Float) {
        editText.textSize = sizeSp
        lineNumbers.textSize = (sizeSp - 1f).coerceAtLeast(8f)
    }

    fun applyTheme(light: Boolean) {
        setBackgroundColor(
            if (light) {
                Color.rgb(250, 250, 252)
            } else {
                Color.rgb(17, 19, 24)
            }
        )

        editText.setTextColor(
            if (light) {
                Color.rgb(28, 27, 31)
            } else {
                Color.rgb(230, 225, 229)
            }
        )

        editText.setHintTextColor(
            if (light) Color.GRAY else Color.LTGRAY
        )
        lineNumbers.setTextColor(
            if (light) Color.DKGRAY else Color.LTGRAY
        )

        highlight(editText.text)
    }

    fun undo() {
        if (undoHistory.isNotEmpty()) {
            replaceFromHistory(
                value = undoHistory.removeLast(),
                oppositeHistory = redoHistory
            )
        }
    }

    fun redo() {
        if (redoHistory.isNotEmpty()) {
            replaceFromHistory(
                value = redoHistory.removeLast(),
                oppositeHistory = undoHistory
            )
        }
    }

    private fun insertIndentation() {
        val start = editText.selectionStart.coerceAtLeast(0)
        val end = editText.selectionEnd.coerceAtLeast(start)
        val editable = editText.text

        if (start == end) {
            editable.insert(start, INDENT)
            return
        }

        val text = editable.toString()
        val firstLineStart = text.lastIndexOf('\n', start - 1) + 1
        val lastLineEnd = text.indexOf('\n', end).let { index ->
            if (index == -1) text.length else index
        }
        val selectedLines = text.substring(firstLineStart, lastLineEnd)
        val indented = selectedLines
            .lineSequence()
            .joinToString("\n") { line -> INDENT + line }

        internalChange = true
        editable.replace(firstLineStart, lastLineEnd, indented)
        editText.setSelection(
            firstLineStart,
            firstLineStart + indented.length
        )
        internalChange = false
    }

    private fun replaceFromHistory(
        value: String,
        oppositeHistory: ArrayDeque<String>
    ) {
        oppositeHistory.addLast(text())

        internalChange = true
        editText.setText(value)
        editText.setSelection(value.length)
        internalChange = false

        updateLineNumbers(editText.text)
        highlight(editText.text)
        onChanged?.invoke(value)
    }

    private fun updateLineNumbers(editable: Editable) {
        val lineCount = editable.count { character -> character == '\n' } + 1
        lineNumbers.text = (1..lineCount).joinToString("\n")
    }

    private fun handlePairsAndIndentation(editable: Editable) {
        val cursorPosition = editText.selectionStart
        if (cursorPosition <= 0 || cursorPosition > editable.length) {
            return
        }

        val insertedCharacter = editable[cursorPosition - 1]
        val closingCharacter = when (insertedCharacter) {
            '(' -> ')'
            '[' -> ']'
            '{' -> '}'
            '"' -> '"'
            '\'' -> '\''
            else -> null
        }

        if (
            closingCharacter != null &&
            (
                cursorPosition == editable.length ||
                    editable[cursorPosition] != closingCharacter
                )
        ) {
            internalChange = true
            editable.insert(cursorPosition, closingCharacter.toString())
            editText.setSelection(cursorPosition)
            internalChange = false
            return
        }

        if (insertedCharacter == '\n') {
            applyNewLineIndentation(editable, cursorPosition)
        }
    }

    private fun applyNewLineIndentation(
        editable: Editable,
        cursorPosition: Int
    ) {
        val contentBeforeNewLine = editable.substring(0, cursorPosition - 1)
        val previousLine = contentBeforeNewLine.substringAfterLast('\n')
        val existingIndent = previousLine.takeWhile { character ->
            character == ' ' || character == '\t'
        }
        val blockIndent = if (previousLine.trimEnd().endsWith(':')) {
            INDENT
        } else {
            ""
        }
        val indentation = existingIndent + blockIndent

        if (indentation.isEmpty()) {
            return
        }

        internalChange = true
        editable.insert(cursorPosition, indentation)
        editText.setSelection(cursorPosition + indentation.length)
        internalChange = false
    }

    private fun highlight(editable: Editable) {
        val isLightTheme =
            (editText.currentTextColor and 0xFFFFFF) < 0x808080

        editable
            .getSpans(
                0,
                editable.length,
                ForegroundColorSpan::class.java
            )
            .forEach { span -> editable.removeSpan(span) }

        applySpan(
            editable,
            keywords,
            darkColor = Color.rgb(198, 120, 221),
            lightColor = Color.rgb(120, 40, 150),
            isLightTheme = isLightTheme
        )
        applySpan(
            editable,
            builtIns,
            darkColor = Color.rgb(86, 182, 194),
            lightColor = Color.rgb(0, 105, 125),
            isLightTheme = isLightTheme
        )
        applySpan(
            editable,
            numbers,
            darkColor = Color.rgb(181, 206, 168),
            lightColor = Color.rgb(20, 110, 40),
            isLightTheme = isLightTheme
        )
        applySpan(
            editable,
            declarations,
            darkColor = Color.rgb(220, 220, 170),
            lightColor = Color.rgb(120, 90, 0),
            isLightTheme = isLightTheme
        )
        applySpan(
            editable,
            operators,
            darkColor = Color.rgb(212, 212, 212),
            lightColor = Color.rgb(70, 70, 70),
            isLightTheme = isLightTheme
        )
        applySpan(
            editable,
            strings,
            darkColor = Color.rgb(206, 145, 120),
            lightColor = Color.rgb(165, 65, 30),
            isLightTheme = isLightTheme
        )
        applySpan(
            editable,
            comments,
            darkColor = Color.rgb(106, 153, 85),
            lightColor = Color.rgb(40, 125, 45),
            isLightTheme = isLightTheme
        )
    }

    private fun applySpan(
        editable: Editable,
        pattern: Pattern,
        darkColor: Int,
        lightColor: Int,
        isLightTheme: Boolean
    ) {
        val matcher = pattern.matcher(editable)
        val color = if (isLightTheme) lightColor else darkColor

        while (matcher.find()) {
            editable.setSpan(
                ForegroundColorSpan(color),
                matcher.start(),
                matcher.end(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
    }

    companion object {
        private const val INDENT = "    "
        private const val MAX_HISTORY_ITEMS = 100
    }
}
