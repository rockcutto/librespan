package eu.siacs.conversations.ui.widget;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.Spanned;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

import androidx.appcompat.widget.AppCompatEditText;
import androidx.core.view.inputmethod.EditorInfoCompat;
import androidx.core.view.inputmethod.InputConnectionCompat;
import androidx.core.view.inputmethod.InputContentInfoCompat;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import eu.siacs.conversations.Config;
import eu.siacs.conversations.R;
import eu.siacs.conversations.ui.util.QuoteHelper;

public class EditMessage extends AppCompatEditText {

    private static final InputFilter SPAN_FILTER = (source, start, end, dest, dstart, dend) -> source instanceof Spanned ? source.toString() : source;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    protected Handler mTypingHandler = new Handler();
    protected KeyboardListener keyboardListener;
    private OnCommitContentListener mCommitContentListener = null;
    private OnSelectionChangedListener onSelectionChangedListener = null;
    private boolean suppressSelectionCallbacks = false;
    private String[] mimeTypes = null;
    private boolean isUserTyping = false;
    private final Runnable mTypingTimeout = new Runnable() {
        @Override
        public void run() {
            if (isUserTyping && keyboardListener != null) {
                keyboardListener.onTypingStopped();
                isUserTyping = false;
            }
        }
    };
    private boolean lastInputWasTab = false;

    public EditMessage(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public EditMessage(Context context) {
        super(context);
    }

    @Override
    public boolean onKeyDown(final int keyCode, final KeyEvent e) {
        final boolean isCtrlPressed = e.isCtrlPressed();
        if (keyCode == KeyEvent.KEYCODE_ENTER && !e.isShiftPressed()) {
            lastInputWasTab = false;
            if (keyboardListener != null && keyboardListener.onEnterPressed(isCtrlPressed)) {
                return true;
            }
        } else if (keyCode == KeyEvent.KEYCODE_TAB && !e.isAltPressed() && !isCtrlPressed) {
            if (keyboardListener != null && keyboardListener.onTabPressed(this.lastInputWasTab)) {
                lastInputWasTab = true;
                return true;
            }
        } else {
            lastInputWasTab = false;
        }
        return super.onKeyDown(keyCode, e);
    }

    @Override
    public int getAutofillType() {
        return AUTOFILL_TYPE_NONE;
    }

    @Override
    protected void onSelectionChanged(final int selStart, final int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        if (!suppressSelectionCallbacks && onSelectionChangedListener != null) {
            onSelectionChangedListener.onSelectionChanged(selStart, selEnd);
        }
    }

    public void setOnSelectionChangedListener(OnSelectionChangedListener listener) {
        this.onSelectionChangedListener = listener;
    }

    public boolean hasFormattableSelection() {
        return normalizedFormattingSelection() != null;
    }

    public boolean isSelectionStyled(final char marker) {
        final int[] range = normalizedFormattingSelection();
        if (range == null) {
            return false;
        }
        final Editable editable = getEditableText();
        final int start = range[0];
        final int end = range[1];
        return hasMarkerOutside(editable, start, end, marker)
                || hasMarkerInside(editable, start, end, marker);
    }

    public boolean toggleSelectionStyle(final char marker) {
        final int[] range = normalizedFormattingSelection();
        if (range == null) {
            return false;
        }
        final Editable editable = getEditableText();
        int start = range[0];
        int end = range[1];
        suppressSelectionCallbacks = true;
        try {
            if (hasMarkerOutside(editable, start, end, marker)) {
                editable.delete(end, end + 1);
                editable.delete(start - 1, start);
                start--;
                end--;
            } else if (hasMarkerInside(editable, start, end, marker)) {
                editable.delete(end - 1, end);
                editable.delete(start, start + 1);
                end -= 2;
            } else {
                editable.insert(end, String.valueOf(marker));
                editable.insert(start, String.valueOf(marker));
                start++;
                end++;
            }
            setSelection(start, end);
        } finally {
            suppressSelectionCallbacks = false;
        }
        if (onSelectionChangedListener != null) {
            onSelectionChangedListener.onSelectionChanged(getSelectionStart(), getSelectionEnd());
        }
        return true;
    }

    private int[] normalizedFormattingSelection() {
        final Editable editable = getEditableText();
        if (editable == null) {
            return null;
        }
        int start = Math.min(getSelectionStart(), getSelectionEnd());
        int end = Math.max(getSelectionStart(), getSelectionEnd());
        if (start < 0 || end <= start || end > editable.length()) {
            return null;
        }
        while (start < end && Character.isWhitespace(editable.charAt(start))) {
            start++;
        }
        while (end > start && Character.isWhitespace(editable.charAt(end - 1))) {
            end--;
        }
        if (end <= start) {
            return null;
        }
        for (int i = start; i < end; i++) {
            final char c = editable.charAt(i);
            if (c == '\n' || c == '\r') {
                return null;
            }
        }
        return new int[] {start, end};
    }

    private static boolean hasMarkerOutside(
            final CharSequence text, final int start, final int end, final char marker) {
        return start > 0
                && end < text.length()
                && text.charAt(start - 1) == marker
                && text.charAt(end) == marker;
    }

    private static boolean hasMarkerInside(
            final CharSequence text, final int start, final int end, final char marker) {
        return end - start > 2
                && text.charAt(start) == marker
                && text.charAt(end - 1) == marker;
    }


    @Override
    public void onTextChanged(CharSequence text, int start, int lengthBefore, int lengthAfter) {
        super.onTextChanged(text, start, lengthBefore, lengthAfter);
        lastInputWasTab = false;
        if (this.mTypingHandler != null && this.keyboardListener != null) {
            executor.execute(() -> triggerKeyboardEvents(text.length()));
        }
    }

    private void triggerKeyboardEvents(final int length) {
        final KeyboardListener listener = this.keyboardListener;
        if (listener == null) {
            return;
        }
        this.mTypingHandler.removeCallbacks(mTypingTimeout);
        this.mTypingHandler.postDelayed(mTypingTimeout, Config.TYPING_TIMEOUT * 1000);
        if (!isUserTyping && length > 0) {
            this.isUserTyping = true;
            listener.onTypingStarted();
        } else if (length == 0) {
            this.isUserTyping = false;
            listener.onTextDeleted();
        }
        listener.onTextChanged();
    }

    public void setKeyboardListener(KeyboardListener listener) {
        this.keyboardListener = listener;
        if (listener != null) {
            this.isUserTyping = false;
        }
    }

    @Override
    public boolean onTextContextMenuItem(int id) {
        if (id == android.R.id.paste) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                return super.onTextContextMenuItem(android.R.id.pasteAsPlainText);
            } else {
                Editable editable = getEditableText();
                InputFilter[] filters = editable.getFilters();
                InputFilter[] tempFilters = new InputFilter[filters != null ? filters.length + 1 : 1];
                if (filters != null) {
                    System.arraycopy(filters, 0, tempFilters, 1, filters.length);
                }
                tempFilters[0] = SPAN_FILTER;
                editable.setFilters(tempFilters);
                try {
                    return super.onTextContextMenuItem(id);
                } finally {
                    editable.setFilters(filters);
                }
            }
        } else {
            return super.onTextContextMenuItem(id);
        }
    }

    public void setRichContentListener(String[] mimeTypes, OnCommitContentListener listener) {
        this.mimeTypes = mimeTypes;
        this.mCommitContentListener = listener;
    }

    public void insertAsQuote(String text) {
        text = QuoteHelper.replaceAltQuoteCharsInText(text);
        text = text
                // first replace all '>' at the beginning of the line with nice and tidy '>>'
                // for nested quoting
                .replaceAll("(^|\n)(" + QuoteHelper.QUOTE_CHAR + ")", "$1$2$2")
                // then find all other lines and have them start with a '> '
                .replaceAll("(^|\n)(?!" + QuoteHelper.QUOTE_CHAR + ")(.*)", "$1> $2")
        ;
        Editable editable = getEditableText();
        int position = getSelectionEnd();
        if (position == -1) position = editable.length();
        if (position > 0 && editable.charAt(position - 1) != '\n') {
            editable.insert(position++, "\n");
        }
        editable.insert(position, text);
        position += text.length();
        editable.insert(position++, "\n");
        if (position < editable.length() && editable.charAt(position) != '\n') {
            editable.insert(position, "\n");
        }
        setSelection(position);
    }

    @Override
    public InputConnection onCreateInputConnection(EditorInfo editorInfo) {
        final InputConnection ic = super.onCreateInputConnection(editorInfo);

        if (mimeTypes != null && mCommitContentListener != null && ic != null) {
            EditorInfoCompat.setContentMimeTypes(editorInfo, mimeTypes);
            return InputConnectionCompat.createWrapper(ic, editorInfo, (inputContentInfo, flags, opts) -> EditMessage.this.mCommitContentListener.onCommitContent(inputContentInfo, flags, opts, mimeTypes));
        } else {
            return ic;
        }
    }

    public void refreshIme() {
        SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(getContext());
        final boolean usingEnterKey = p.getBoolean("display_enter_key", getResources().getBoolean(R.bool.display_enter_key));
        final boolean enterIsSend = p.getBoolean("enter_is_send", getResources().getBoolean(R.bool.enter_is_send));

        if (usingEnterKey && enterIsSend) {
            setInputType(getInputType() & (~InputType.TYPE_TEXT_FLAG_MULTI_LINE));
            setInputType(getInputType() & (~InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE));
        } else if (usingEnterKey) {
            setInputType(getInputType() | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            setInputType(getInputType() & (~InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE));
        } else {
            setInputType(getInputType() | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            setInputType(getInputType() | InputType.TYPE_TEXT_VARIATION_SHORT_MESSAGE);
        }
    }

    public interface OnCommitContentListener {
        boolean onCommitContent(InputContentInfoCompat inputContentInfo, int flags, Bundle opts, String[] mimeTypes);
    }

    public interface OnSelectionChangedListener {
        void onSelectionChanged(int selectionStart, int selectionEnd);
    }

    public interface KeyboardListener {
        boolean onEnterPressed(boolean isCtrlPressed);

        void onTypingStarted();

        void onTypingStopped();

        void onTextDeleted();

        void onTextChanged();

        boolean onTabPressed(boolean repeated);
    }
}
