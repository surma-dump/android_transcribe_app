package dev.surma.parakeeb;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.InputConnection;
import android.widget.LinearLayout;
import android.widget.Space;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * A composite keyboard view built entirely from styled buttons (no deprecated
 * {@code KeyboardView}). Contains a QWERTY/symbols character grid, a sticky
 * modifier row, and an arrow key row.
 */
public class SoftKeyboardView extends LinearLayout {

    /** Callback so the hosting IME can supply the current InputConnection. */
    public interface InputConnectionProvider {
        InputConnection getInputConnection();
    }

    private static final long DELETE_REPEAT_INITIAL_DELAY = 400;
    private static final long DELETE_REPEAT_INTERVAL = 50;

    private boolean isSymbolsMode = false;
    private boolean isShifted = false;

    // Sticky modifier state.
    private boolean ctrlActive  = false;
    private boolean shiftActive = false;
    private boolean altActive   = false;
    private boolean metaActive  = false;

    private InputConnectionProvider icProvider;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable deleteRepeatRunnable;

    // UI references for dynamic updates.
    private LinearLayout keyRowsContainer;
    private final List<TextView> letterKeys = new ArrayList<>();
    private TextView shiftKeyView;

    private TextView btnCtrl;
    private TextView btnShift;
    private TextView btnAlt;
    private TextView btnMeta;

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

        // 1. Container for keyboard character rows (rebuilt on mode switch).
        keyRowsContainer = new LinearLayout(context);
        keyRowsContainer.setOrientation(VERTICAL);
        addView(keyRowsContainer, new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        buildKeyRows(context);

        // 2. Modifier row.
        LinearLayout modRow = new LinearLayout(context);
        modRow.setOrientation(HORIZONTAL);
        LayoutParams modLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        modLp.topMargin = dpToPx(4);

        TextView btnEsc = makeKeyButton(context, "Esc", 1f);
        TextView btnTab = makeKeyButton(context, "Tab", 1f);
        btnCtrl  = makeKeyButton(context, "Ctrl",  1f);
        btnShift = makeKeyButton(context, "Shift", 1f);
        btnAlt   = makeKeyButton(context, "Alt",   1f);
        TextView btnMeta_ = makeKeyButton(context, "Meta", 1f);
        btnMeta = btnMeta_;

        modRow.addView(btnEsc);
        modRow.addView(btnTab);
        modRow.addView(btnCtrl);
        modRow.addView(btnShift);
        modRow.addView(btnAlt);
        modRow.addView(btnMeta);

        addView(modRow, modLp);

        btnEsc.setOnClickListener(v   -> sendImmediateKey(KeyEvent.KEYCODE_ESCAPE));
        btnTab.setOnClickListener(v   -> sendImmediateKey(KeyEvent.KEYCODE_TAB));
        btnCtrl.setOnClickListener(v  -> toggleModifier(ModifierKind.CTRL));
        btnShift.setOnClickListener(v -> toggleModifier(ModifierKind.SHIFT));
        btnAlt.setOnClickListener(v   -> toggleModifier(ModifierKind.ALT));
        btnMeta.setOnClickListener(v  -> toggleModifier(ModifierKind.META));

        // 3. Arrow key row (vim hjkl order: left, down, up, right).
        LinearLayout arrowRow = new LinearLayout(context);
        arrowRow.setOrientation(HORIZONTAL);
        LayoutParams arrowLp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        arrowLp.topMargin = dpToPx(4);

        TextView btnLeft  = makeKeyButton(context, "\u25C0", 1f);
        TextView btnDown  = makeKeyButton(context, "\u25BC", 1f);
        TextView btnUp    = makeKeyButton(context, "\u25B2", 1f);
        TextView btnRight = makeKeyButton(context, "\u25B6", 1f);

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

    // -----------------------------------------------------------------------
    // Keyboard rows
    // -----------------------------------------------------------------------

    private void buildKeyRows(Context context) {
        keyRowsContainer.removeAllViews();
        letterKeys.clear();
        shiftKeyView = null;

        if (isSymbolsMode) {
            buildSymbolRows(context);
        } else {
            buildQwertyRows(context);
        }
    }

    private void buildQwertyRows(Context context) {
        // Row 1: q w e r t y u i o p  (10 × 1.0 = 10.0)
        String[] r1 = {"q","w","e","r","t","y","u","i","o","p"};
        keyRowsContainer.addView(
                buildLetterRow(context, r1, 1f, 0f), rowLp(0));

        // Row 2: a s d f g h j k l  (spacer(0.5) + 9×1 + spacer(0.5) = 10.0)
        String[] r2 = {"a","s","d","f","g","h","j","k","l"};
        keyRowsContainer.addView(
                buildLetterRow(context, r2, 1f, 0.5f), rowLp(2));

        // Row 3: ⇧(1.5) z x c v b n m(7×1) ⌫(1.5) = 10.0
        LinearLayout r3 = new LinearLayout(context);
        r3.setOrientation(HORIZONTAL);

        shiftKeyView = makeKeyButton(context, "\u21E7", 1.5f);
        shiftKeyView.setOnClickListener(v -> toggleShift());
        r3.addView(shiftKeyView);

        for (String ch : new String[]{"z","x","c","v","b","n","m"}) {
            TextView tv = makeKeyButton(context, ch, 1f);
            tv.setOnClickListener(v -> onCharKey(ch.charAt(0)));
            letterKeys.add(tv);
            r3.addView(tv);
        }

        TextView delKey = makeKeyButton(context, "\u232B", 1.5f);
        setupDeleteKey(delKey);
        r3.addView(delKey);

        keyRowsContainer.addView(r3, rowLp(2));

        // Row 4: ?123(1.5) ,(1) SPACE(5) .(1) ⏎(1.5) = 10.0
        keyRowsContainer.addView(buildBottomRow(context, "?123"), rowLp(2));
    }

    private void buildSymbolRows(Context context) {
        // Row 1: 1-0  (10 × 1.0 = 10.0)
        String[] r1 = {"1","2","3","4","5","6","7","8","9","0"};
        keyRowsContainer.addView(
                buildCharRow(context, r1, 1f, 0f), rowLp(0));

        // Row 2: @ # $ % & - + ( )  (spacer(0.5) + 9×1 + spacer(0.5) = 10.0)
        String[] r2 = {"@","#","$","%","&","-","+","(",")"};
        keyRowsContainer.addView(
                buildCharRow(context, r2, 1f, 0.5f), rowLp(2));

        // Row 3: = * " ' : ; ! ?  (spacer(1) + 8×1 + spacer(1) = 10.0)
        String[] r3 = {"=","*","\"","'",":",";","!","?"};
        keyRowsContainer.addView(
                buildCharRow(context, r3, 1f, 1f), rowLp(2));

        // Row 4: ABC(1.5) ,(1) SPACE(5) .(1) ⏎(1.5) = 10.0
        keyRowsContainer.addView(buildBottomRow(context, "ABC"), rowLp(2));
    }

    /** Build a row of letter keys (shift-aware). */
    private LinearLayout buildLetterRow(Context context, String[] letters,
                                         float keyWeight, float spacerWeight) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);

        if (spacerWeight > 0) {
            row.addView(makeSpacer(context, spacerWeight));
        }

        for (String ch : letters) {
            String display = isShifted ? ch.toUpperCase() : ch;
            TextView tv = makeKeyButton(context, display, keyWeight);
            tv.setOnClickListener(v -> onCharKey(ch.charAt(0)));
            letterKeys.add(tv);
            row.addView(tv);
        }

        if (spacerWeight > 0) {
            row.addView(makeSpacer(context, spacerWeight));
        }

        return row;
    }

    /** Build a row of character/symbol keys (not shift-aware). */
    private LinearLayout buildCharRow(Context context, String[] chars,
                                       float keyWeight, float spacerWeight) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);

        if (spacerWeight > 0) {
            row.addView(makeSpacer(context, spacerWeight));
        }

        for (String ch : chars) {
            TextView tv = makeKeyButton(context, ch, keyWeight);
            tv.setOnClickListener(v -> onCharKey(ch.charAt(0)));
            row.addView(tv);
        }

        if (spacerWeight > 0) {
            row.addView(makeSpacer(context, spacerWeight));
        }

        return row;
    }

    /** Build the bottom row: mode-switch, comma, space, period, enter. */
    private LinearLayout buildBottomRow(Context context, String modeLabel) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(HORIZONTAL);

        TextView modeKey = makeKeyButton(context, modeLabel, 1.5f);
        modeKey.setTextSize(11);
        modeKey.setOnClickListener(v -> toggleMode());
        row.addView(modeKey);

        TextView commaKey = makeKeyButton(context, ",", 1f);
        commaKey.setOnClickListener(v -> onCharKey(','));
        row.addView(commaKey);

        TextView spaceKey = makeKeyButton(context, " ", 5f);
        spaceKey.setOnClickListener(v -> onCharKey(' '));
        row.addView(spaceKey);

        TextView periodKey = makeKeyButton(context, ".", 1f);
        periodKey.setOnClickListener(v -> onCharKey('.'));
        row.addView(periodKey);

        TextView enterKey = makeKeyButton(context, "\u23CE", 1.5f);
        enterKey.setOnClickListener(v -> onEnterKey());
        row.addView(enterKey);

        return row;
    }

    // -----------------------------------------------------------------------
    // Key actions
    // -----------------------------------------------------------------------

    private void onCharKey(char ch) {
        InputConnection ic = (icProvider != null) ? icProvider.getInputConnection() : null;
        if (ic == null) return;

        int meta = activeMetaState();
        if (meta != 0) {
            int keyCode = charToKeyCode(ch);
            if (keyCode != KeyEvent.KEYCODE_UNKNOWN) {
                sendKeyEvent(ic, keyCode);
            }
        } else {
            char out = ch;
            if (isShifted && Character.isLetter(ch)) {
                out = Character.toUpperCase(ch);
            }
            ic.commitText(String.valueOf(out), 1);
        }
    }

    private void onEnterKey() {
        InputConnection ic = (icProvider != null) ? icProvider.getInputConnection() : null;
        if (ic != null) {
            sendKeyEvent(ic, KeyEvent.KEYCODE_ENTER);
        }
    }

    private void toggleShift() {
        isShifted = !isShifted;
        updateShiftVisuals();
    }

    private void toggleMode() {
        isSymbolsMode = !isSymbolsMode;
        isShifted = false;
        buildKeyRows(getContext());
    }

    private void updateShiftVisuals() {
        // Update shift key appearance.
        if (shiftKeyView != null) {
            shiftKeyView.setBackgroundResource(
                    isShifted ? R.drawable.bg_modifier_active
                              : R.drawable.bg_key_normal);
            shiftKeyView.setTextColor(isShifted ? 0xFFFFFFFF : 0xFF333333);
        }
        // Update letter key labels.
        for (TextView tv : letterKeys) {
            String current = tv.getText().toString();
            tv.setText(isShifted ? current.toUpperCase() : current.toLowerCase());
        }
    }

    private void setupDeleteKey(TextView delKey) {
        deleteRepeatRunnable = new Runnable() {
            @Override
            public void run() {
                InputConnection ic = (icProvider != null) ? icProvider.getInputConnection() : null;
                if (ic != null) {
                    sendKeyEvent(ic, KeyEvent.KEYCODE_DEL);
                }
                handler.postDelayed(this, DELETE_REPEAT_INTERVAL);
            }
        };

        delKey.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    InputConnection ic = (icProvider != null) ? icProvider.getInputConnection() : null;
                    if (ic != null) {
                        sendKeyEvent(ic, KeyEvent.KEYCODE_DEL);
                    }
                    handler.postDelayed(deleteRepeatRunnable, DELETE_REPEAT_INITIAL_DELAY);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(deleteRepeatRunnable);
                    return true;
                default:
                    return false;
            }
        });
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
                       : R.drawable.bg_key_normal);
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

    /** Send a single key immediately (for non-toggle keys like Esc, Tab, arrows). */
    private void sendImmediateKey(int keyCode) {
        InputConnection ic = (icProvider != null) ? icProvider.getInputConnection() : null;
        if (ic != null) {
            sendKeyEvent(ic, keyCode);
        }
    }

    private void sendKeyEvent(InputConnection ic, int keyCode) {
        int meta = activeMetaState();
        if (isShifted) {
            meta |= KeyEvent.META_SHIFT_ON | KeyEvent.META_SHIFT_LEFT_ON;
        }
        long now = SystemClock.uptimeMillis();
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, meta));
        ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, meta));
    }

    /** Best-effort ASCII char to Android KEYCODE mapping. */
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
    // View factory helpers
    // -----------------------------------------------------------------------

    private TextView makeKeyButton(Context context, String label, float weight) {
        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTextColor(0xFF333333);
        tv.setGravity(Gravity.CENTER);
        tv.setBackgroundResource(R.drawable.bg_key_normal);
        tv.setClickable(true);
        tv.setFocusable(true);

        LayoutParams lp = new LayoutParams(0, dpToPx(36), weight);
        lp.setMargins(dpToPx(2), 0, dpToPx(2), 0);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View makeSpacer(Context context, float weight) {
        Space spacer = new Space(context);
        LayoutParams lp = new LayoutParams(0, dpToPx(36), weight);
        spacer.setLayoutParams(lp);
        return spacer;
    }

    private LayoutParams rowLp(int topMarginDp) {
        LayoutParams lp = new LayoutParams(
                LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT);
        if (topMarginDp > 0) {
            lp.topMargin = dpToPx(topMarginDp);
        }
        return lp;
    }

    private int dpToPx(int dp) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(dp * density);
    }
}
