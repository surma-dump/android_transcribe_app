package dev.surma.parakeeb;

import android.content.Context;
import android.inputmethodservice.Keyboard;
import android.inputmethodservice.KeyboardView;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A composite view containing a standard QWERTY soft keyboard (via the
 * deprecated but functional {@link KeyboardView}) plus a bottom row of
 * sticky modifier-toggle buttons (Ctrl, Shift, Alt, Meta).
 *
 * <p>Modifiers are manual toggles: press to activate, press again to
 * deactivate. They do <em>not</em> auto-reset after a key press.
 */
@SuppressWarnings("deprecation")
public class SoftKeyboardView extends LinearLayout
        implements KeyboardView.OnKeyboardActionListener {

    /** Callback so the hosting IME can supply the current InputConnection. */
    public interface InputConnectionProvider {
        InputConnection getInputConnection();
    }

    // Key codes matching the XML layouts.
    private static final int KEYCODE_SHIFT  = -1;
    private static final int KEYCODE_MODE   = -2;  // toggle qwerty <-> symbols
    private static final int KEYCODE_DELETE  = -5;
    private static final int KEYCODE_ENTER  = -4;

    private KeyboardView keyboardView;
    private Keyboard qwertyKeyboard;
    private Keyboard symbolsKeyboard;
    private boolean isSymbolsMode = false;
    private boolean isShifted = false;

    // Sticky modifier state.
    private boolean ctrlActive  = false;
    private boolean shiftActive = false;
    private boolean altActive   = false;
    private boolean metaActive  = false;

    private TextView btnEsc;
    private TextView btnTab;
    private TextView btnCtrl;
    private TextView btnShift;
    private TextView btnAlt;
    private TextView btnMeta;

    private InputConnectionProvider icProvider;

    public SoftKeyboardView(Context context) {
        super(context);
        init(context);
    }

    public SoftKeyboardView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init(context);
    }

    public void setInputConnectionProvider(InputConnectionProvider provider) {
        this.icProvider = provider;
    }

    // -----------------------------------------------------------------------
    // Initialisation
    // -----------------------------------------------------------------------

    private void init(Context context) {
        setOrientation(VERTICAL);

        // 1. Build the KeyboardView programmatically.
        keyboardView = new KeyboardView(context, null);
        keyboardView.setOnKeyboardActionListener(this);
        keyboardView.setPreviewEnabled(false);

        qwertyKeyboard  = new Keyboard(context, R.xml.qwerty);
        symbolsKeyboard = new Keyboard(context, R.xml.symbols);
        keyboardView.setKeyboard(qwertyKeyboard);

        LayoutParams kvLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        addView(keyboardView, kvLp);

        // 2. Build the modifier row.
        LinearLayout modRow = new LinearLayout(context);
        modRow.setOrientation(HORIZONTAL);
        LayoutParams rowLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        rowLp.topMargin = dpToPx(4);

        btnEsc   = makeModifierButton(context, "Esc");
        btnTab   = makeModifierButton(context, "Tab");
        btnCtrl  = makeModifierButton(context, "Ctrl");
        btnShift = makeModifierButton(context, "Shift");
        btnAlt   = makeModifierButton(context, "Alt");
        btnMeta  = makeModifierButton(context, "Meta");

        modRow.addView(btnEsc);
        modRow.addView(btnTab);
        modRow.addView(btnCtrl);
        modRow.addView(btnShift);
        modRow.addView(btnAlt);
        modRow.addView(btnMeta);

        addView(modRow, rowLp);

        btnEsc.setOnClickListener(v   -> sendImmediateKey(KeyEvent.KEYCODE_ESCAPE));
        btnTab.setOnClickListener(v   -> sendImmediateKey(KeyEvent.KEYCODE_TAB));
        btnCtrl.setOnClickListener(v  -> toggleModifier(ModifierKind.CTRL));
        btnShift.setOnClickListener(v -> toggleModifier(ModifierKind.SHIFT));
        btnAlt.setOnClickListener(v   -> toggleModifier(ModifierKind.ALT));
        btnMeta.setOnClickListener(v  -> toggleModifier(ModifierKind.META));

        // 3. Build the arrow key row (vim hjkl order: left, down, up, right).
        LinearLayout arrowRow = new LinearLayout(context);
        arrowRow.setOrientation(HORIZONTAL);
        LayoutParams arrowLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        arrowLp.topMargin = dpToPx(4);

        TextView btnLeft  = makeModifierButton(context, "\u25C0");
        TextView btnDown  = makeModifierButton(context, "\u25BC");
        TextView btnUp    = makeModifierButton(context, "\u25B2");
        TextView btnRight = makeModifierButton(context, "\u25B6");

        arrowRow.addView(btnLeft);
        arrowRow.addView(btnDown);
        arrowRow.addView(btnUp);
        arrowRow.addView(btnRight);

        addView(arrowRow, arrowLp);

        btnLeft.setOnClickListener(v  -> sendImmediateKey(KeyEvent.KEYCODE_DPAD_LEFT));
        btnDown.setOnClickListener(v  -> sendImmediateKey(KeyEvent.KEYCODE_DPAD_DOWN));
        btnUp.setOnClickListener(v    -> sendImmediateKey(KeyEvent.KEYCODE_DPAD_UP));
        btnRight.setOnClickListener(v -> sendImmediateKey(KeyEvent.KEYCODE_DPAD_RIGHT));

        refreshModifierUi();
    }

    private TextView makeModifierButton(Context context, String label) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(0xFF333333);
        tv.setGravity(android.view.Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.bg_modifier_inactive);
        tv.setClickable(true);
        tv.setFocusable(true);

        LayoutParams lp = new LayoutParams(0, dpToPx(36), 1f);
        lp.setMargins(dpToPx(2), 0, dpToPx(2), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    // -----------------------------------------------------------------------
    // Modifier toggle logic
    // -----------------------------------------------------------------------

    private enum ModifierKind { CTRL, SHIFT, ALT, META }

    private void toggleModifier(ModifierKind kind) {
        switch (kind) {
            case CTRL:  ctrlActive  = !ctrlActive;  break;
            case SHIFT: shiftActive = !shiftActive; break;
            case ALT:   altActive   = !altActive;   break;
            case META:  metaActive  = !metaActive;  break;
        }
        refreshModifierUi();
    }

    private void refreshModifierUi() {
        applyModStyle(btnCtrl,  ctrlActive);
        applyModStyle(btnShift, shiftActive);
        applyModStyle(btnAlt,   altActive);
        applyModStyle(btnMeta,  metaActive);
    }

    private void applyModStyle(TextView btn, boolean active) {
        if (btn == null) return;
        btn.setBackgroundResource(
                active ? R.drawable.bg_modifier_active
                       : R.drawable.bg_modifier_inactive);
        btn.setTextColor(active ? 0xFFFFFFFF : 0xFF333333);
    }

    private int activeMetaState() {
        int meta = 0;
        if (ctrlActive)  meta |= KeyEvent.META_CTRL_ON  | KeyEvent.META_CTRL_LEFT_ON;
        if (shiftActive) meta |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        if (altActive)   meta |= KeyEvent.META_ALT_ON   | KeyEvent.META_ALT_LEFT_ON;
        if (metaActive)  meta |= KeyEvent.META_META_ON  | KeyEvent.META_META_LEFT_ON;
        return meta;
    }

    // -----------------------------------------------------------------------
    // KeyboardView.OnKeyboardActionListener
    // -----------------------------------------------------------------------

    @Override
    public void onKey(int primaryCode, int[] keyCodes) {
        InputConnection ic = (icProvider != null) ? icProvider.getInputConnection() : null;
        if (ic == null) return;

        if (primaryCode == KEYCODE_SHIFT) {
            isShifted = !isShifted;
            qwertyKeyboard.setShifted(isShifted);
            keyboardView.invalidateAllKeys();
            return;
        }

        if (primaryCode == KEYCODE_MODE) {
            isSymbolsMode = !isSymbolsMode;
            keyboardView.setKeyboard(isSymbolsMode ? symbolsKeyboard : qwertyKeyboard);
            return;
        }

        if (primaryCode == KEYCODE_DELETE) {
            sendKeyEvent(ic, KeyEvent.KEYCODE_DEL);
            return;
        }

        if (primaryCode == KEYCODE_ENTER) {
            sendKeyEvent(ic, KeyEvent.KEYCODE_ENTER);
            return;
        }

        // Regular character key.
        int meta = activeMetaState();
        if (meta != 0) {
            // With modifiers active: send as KeyEvent so apps see the meta state.
            int keyCode = charToKeyCode(primaryCode);
            if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                sendKeyEvent(ic, keyCode);
            }
        } else {
            // No modifiers: commit text for better compatibility.
            char ch = (char) primaryCode;
            if (isShifted && Character.isLetter(ch)) {
                ch = Character.toUpperCase(ch);
            }
            ic.commitText(String.valueOf(ch), 1);
        }
    }

    /** Send a single key immediately (for non-toggle keys like Esc and Tab). */
    private void sendImmediateKey(int keyCode) {
        InputConnection ic = (icProvider != null) ? icProvider.getInputConnection() : null;
        if (ic != null) {
            sendKeyEvent(ic, keyCode);
        }
    }

    private void sendKeyEvent(InputConnection ic, int keyCode) {
        int meta = activeMetaState();
        // Also fold in the keyboard-level shift when sending events.
        if (isShifted) {
            meta |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        }
        long now = android.os.SystemClock.uptimeMillis();
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta));
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta));
    }

    /** Best-effort ASCII char → Android KEYCODE mapping. */
    private static int charToKeyCode(int ch) {
        if (ch >= 'a' && ch <= 'z') return KeyEvent.KEYCODE_A + (ch - 'a');
        if (ch >= 'A' && ch <= 'Z') return KeyEvent.KEYCODE_A + (ch - 'A');
        if (ch >= '0' && ch <= '9') return KeyEvent.KEYCODE_0 + (ch - '0');
        switch (ch) {
            case ' ':  return KeyEvent.KEYCODE_SPACE;
            case '\t': return KeyEvent.KEYCODE_TAB;
            case ',':  return KeyEvent.KEYCODE_COMMA;
            case '.':  return KeyEvent.KEYCODE_PERIOD;
            case '-':  return KeyEvent.KEYCODE_MINUS;
            case '=':  return KeyEvent.KEYCODE_EQUALS;
            case '[':  return KeyEvent.KEYCODE_LEFT_BRACKET;
            case ']':  return KeyEvent.KEYCODE_RIGHT_BRACKET;
            case '\\': return KeyEvent.KEYCODE_BACKSLASH;
            case ';':  return KeyEvent.KEYCODE_SEMICOLON;
            case '\'': return KeyEvent.KEYCODE_APOSTROPHE;
            case '/':  return KeyEvent.KEYCODE_SLASH;
            case '`':  return KeyEvent.KEYCODE_GRAVE;
            case '@':  return KeyEvent.KEYCODE_AT;
            case '#':  return KeyEvent.KEYCODE_POUND;
            case '*':  return KeyEvent.KEYCODE_STAR;
            case '+':  return KeyEvent.KEYCODE_PLUS;
            default:   return KeyEvent.KEYCODE_UNKNOWN;
        }
    }

    // -----------------------------------------------------------------------
    // Unused listener stubs
    // -----------------------------------------------------------------------
    @Override public void onPress(int primaryCode) {}
    @Override public void onRelease(int primaryCode) {}
    @Override public void onText(CharSequence text) {}
    @Override public void swipeLeft() {}
    @Override public void swipeRight() {}
    @Override public void swipeDown() {}
    @Override public void swipeUp() {}

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
