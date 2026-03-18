package dev.surma.parakeeb;

import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.InputConnection;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.File;

public class RustInputMethodService extends InputMethodService {
    
    private static final String TAG = "OfflineVoiceInput";

    static {
        try {
            System.loadLibrary("c++_shared");
            System.loadLibrary("onnxruntime");
            System.loadLibrary("android_transcribe_app");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Failed to load native libraries", e);
        }
    }

    private ImageView recordButton;
    private View backspaceButton;
    private View spaceButton;
    private View enterButton;
    private View switchKeyboardButton;
    private Handler mainHandler;
    private boolean isRecording = false;
    private boolean pendingSwitchBack = false;
    private String lastStatus = "Initializing...";
    // Key repeat settings
    private static final long REPEAT_INITIAL_DELAY = 400; // ms before repeat starts
    private static final long REPEAT_INTERVAL = 50; // ms between repeats
    private static final float SPACE_CURSOR_STEP_DP = 16f;
    private Runnable backspaceRepeatRunnable;
    private Runnable spaceCursorLongPressRunnable;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private SpacebarCursorStepper spacebarCursorStepper;
    private boolean isSpaceCursorDragActive = false;
    private float lastSpaceTouchRawX = 0f;
    private boolean pauseAudioActive = false;
    private LayerDrawable recordButtonBackground;
    private int transcribeProgressPercent = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        Log.d(TAG, "Service onCreate");
        try {
            initNative(this);
        } catch (Exception e) {
            Log.e(TAG, "Error in initNative", e);
        }
    }

    @Override
    public View onCreateInputView() {
        Log.d(TAG, "onCreateInputView");
        try {
            View view = getLayoutInflater().inflate(R.layout.ime_layout, null);
            
            // Handle window insets for avoiding navigation bar overlap
            view.setOnApplyWindowInsetsListener((v, insets) -> {
                int paddingBottom = insets.getSystemWindowInsetBottom();
                int originalPaddingBottom = v.getPaddingTop();
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), originalPaddingBottom + paddingBottom);
                return insets;
            });

            recordButton = view.findViewById(R.id.ime_record);
            Drawable background = recordButton.getBackground();
            if (background instanceof LayerDrawable) {
                recordButtonBackground = (LayerDrawable) background.mutate();
            } else {
                recordButtonBackground = null;
            }
            backspaceButton = view.findViewById(R.id.ime_backspace);
            spaceButton = view.findViewById(R.id.ime_space);
            enterButton = view.findViewById(R.id.ime_enter);
            switchKeyboardButton = view.findViewById(R.id.ime_switch_keyboard);
            float cursorStepPx = SPACE_CURSOR_STEP_DP * getResources().getDisplayMetrics().density;
            spacebarCursorStepper = new SpacebarCursorStepper(cursorStepPx);

            switchKeyboardButton.setOnClickListener(v -> {
                if (isRecording) {
                    pendingSwitchBack = true;
                    stopRecording();
                    if (pauseAudioActive) {
                        audioPauser.abandon(this);
                        pauseAudioActive = false;
                    }
                    transcribeProgressPercent = 0;
                    updateRecordButtonUI(false);
                } else {
                    switchToPreviousInputMethod();
                }
            });

            // Key repeat runnable for backspace
            backspaceRepeatRunnable = new Runnable() {
                @Override
                public void run() {
                    InputConnection ic = getCurrentInputConnection();
                    BackspaceEditor.performBackspace(ic);
                    mainHandler.postDelayed(this, REPEAT_INTERVAL);
                }
            };

            spaceCursorLongPressRunnable = () -> {
                isSpaceCursorDragActive = true;
                if (spacebarCursorStepper != null) {
                    spacebarCursorStepper.start(lastSpaceTouchRawX);
                }
                if (spaceButton != null) {
                    spaceButton.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                }
            };

            backspaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        InputConnection ic = getCurrentInputConnection();
                        BackspaceEditor.performBackspace(ic);
                        mainHandler.postDelayed(backspaceRepeatRunnable, REPEAT_INITIAL_DELAY);
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        mainHandler.removeCallbacks(backspaceRepeatRunnable);
                        return true;
                }
                return false;
            });

            spaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastSpaceTouchRawX = event.getRawX();
                        isSpaceCursorDragActive = false;
                        if (spacebarCursorStepper != null) {
                            spacebarCursorStepper.reset();
                        }
                        mainHandler.postDelayed(
                                spaceCursorLongPressRunnable,
                                ViewConfiguration.getLongPressTimeout());
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lastSpaceTouchRawX = event.getRawX();
                        if (!isSpaceCursorDragActive || spacebarCursorStepper == null) {
                            return true;
                        }

                        int steps = spacebarCursorStepper.moveTo(lastSpaceTouchRawX);
                        moveCursorBySteps(steps);
                        return true;
                    case MotionEvent.ACTION_UP:
                        finishSpaceTouch(!isSpaceCursorDragActive);
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        finishSpaceTouch(false);
                        return true;
                }
                return false;
            });

            enterButton.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    android.view.inputmethod.EditorInfo editorInfo = getCurrentInputEditorInfo();
                    int imeOptions = editorInfo.imeOptions;
                    int action = imeOptions & android.view.inputmethod.EditorInfo.IME_MASK_ACTION;
                    boolean noEnterAction = (imeOptions & android.view.inputmethod.EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

                    // If the editor flags IME_FLAG_NO_ENTER_ACTION (e.g. multi-line fields in
                    // messaging apps like Signal), or if there's no meaningful action, insert a
                    // newline. Otherwise perform the editor action (Go, Search, Send, etc.).
                    if (!noEnterAction && (
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_GO ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_SEND ||
                            action == android.view.inputmethod.EditorInfo.IME_ACTION_NEXT)) {
                        ic.performEditorAction(action);
                    } else {
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_ENTER));
                        ic.sendKeyEvent(new android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_ENTER));
                    }
                }
            });

            recordButton.setOnClickListener(v -> {
                if (!recordButton.isEnabled()) return;

                // Check microphone permission
                if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                        != PackageManager.PERMISSION_GRANTED) {
                    Toast.makeText(this, "No mic permission - grant in app", Toast.LENGTH_SHORT).show();
                    return;
                }

                if (isRecording) {
                    stopRecording();
                    if (pauseAudioActive) {
                        audioPauser.abandon(this);
                        pauseAudioActive = false;
                    }
                    transcribeProgressPercent = 0;
                    updateRecordButtonUI(false);
                } else {
                    transcribeProgressPercent = -1;
                    if (isPauseAudioEnabled()) {
                        audioPauser.request(this);
                        pauseAudioActive = true;
                    }
                    startRecording();
                    updateRecordButtonUI(true);
                }
            });

            updateUiState();
            return view;
        } catch (Exception e) {
            Log.e(TAG, "Error in onCreateInputView", e);
            TextView errorView = new TextView(this);
            errorView.setText("Error loading keyboard: " + e.getMessage());
            return errorView;
        }
    }

    @Override
    public void onWindowShown() {
        super.onWindowShown();
        if (!isRecording && new File(getFilesDir(), "auto_record").exists()) {
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                if (isPauseAudioEnabled()) {
                    audioPauser.request(this);
                    pauseAudioActive = true;
                }
                transcribeProgressPercent = -1;
                startRecording();
                updateRecordButtonUI(true);
            }
        }
    }

    @Override
    public void onWindowHidden() {
        super.onWindowHidden();
        finishSpaceTouch(false);
        if (isRecording) {
            try {
                cancelRecording();
            } catch (Throwable t) {
                Log.w(TAG, "cancelRecording failed, falling back to stopRecording", t);
                try { stopRecording(); } catch (Throwable ignored) { }
            }
            transcribeProgressPercent = -1;
            updateRecordButtonUI(false);
        }
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    private void updateRecordButtonUI(boolean recording) {
        isRecording = recording;
        if (recordButton == null) {
            return;
        }

        if (recording) {
            recordButton.setColorFilter(0xFFF44336); // Red while recording
        } else {
            recordButton.setColorFilter(0xFF2196F3); // Blue when idle/processing
        }

        updateRecordButtonProgress();
    }

    private void updateRecordButtonProgress() {
        if (recordButtonBackground == null) {
            return;
        }

        Drawable progressDrawable = recordButtonBackground.findDrawableByLayerId(android.R.id.progress);
        if (progressDrawable == null) {
            return;
        }

        int visiblePercent = (!isRecording && transcribeProgressPercent >= 0) ? transcribeProgressPercent : 0;
        progressDrawable.setLevel(visiblePercent * 100);
    }

    private void finishSpaceTouch(boolean shouldCommitSpace) {
        if (spaceCursorLongPressRunnable != null) {
            mainHandler.removeCallbacks(spaceCursorLongPressRunnable);
        }

        if (shouldCommitSpace) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                ic.commitText(" ", 1);
            }
        }

        if (spacebarCursorStepper != null) {
            spacebarCursorStepper.reset();
        }
        isSpaceCursorDragActive = false;
    }

    private void moveCursorBySteps(int steps) {
        if (steps == 0) {
            return;
        }

        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }

        int keyCode = steps > 0 ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT;
        int keyCount = Math.abs(steps);

        for (int i = 0; i < keyCount; i++) {
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
            ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cleanupNative();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    // Native methods
    private native void initNative(RustInputMethodService service);
    private native void cleanupNative();
    private native void startRecording();
    private native void stopRecording();
    private native void cancelRecording();

    // Called from Rust
    public void onStatusUpdate(String status) {
        mainHandler.post(() -> {
            Log.d(TAG, "Status: " + status);
            lastStatus = status;

            int parsedPercent = ImeProgressStatusParser.parseProgressPercent(status);
            if (parsedPercent >= 0) {
                transcribeProgressPercent = parsedPercent;
            } else if (status != null && (status.startsWith("Queued") || status.startsWith("Transcribing"))) {
                transcribeProgressPercent = 0;
            } else if (status == null || status.startsWith("Ready") || status.startsWith("Listening")
                    || status.startsWith("Canceled") || status.startsWith("Error")) {
                transcribeProgressPercent = -1;
            }

            updateUiState();

            if (pendingSwitchBack && status != null && (status.startsWith("Ready") || status.startsWith("Error"))) {
                pendingSwitchBack = false;
                switchToPreviousInputMethod();
            }
            if (pauseAudioActive && status != null && status.startsWith("Error")) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
        });
    }

    private void updateUiState() {
        boolean isWaiting = lastStatus.contains("Waiting");

        // Keep record button available while previous clips are transcribing.
        // Only disable during model-loading "Waiting..." states.
        if (recordButton != null) {
            boolean disable = isWaiting;
            recordButton.setEnabled(!disable);
            recordButton.setAlpha(disable ? 0.5f : 1.0f);
        }

        updateRecordButtonProgress();
    }

    // Called from Rust
    public void onTextTranscribed(String text) {
        mainHandler.post(() -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                String committed = text + " ";
                ic.commitText(committed, 1);

                if (!pendingSwitchBack && new File(getFilesDir(), "select_transcription").exists()) {
                    android.view.inputmethod.ExtractedText et = ic.getExtractedText(
                        new android.view.inputmethod.ExtractedTextRequest(), 0);
                    if (et != null) {
                        int end = et.selectionStart;
                        int start = end - committed.length();
                        if (start >= 0) {
                            ic.setSelection(start, end);
                        }
                    }
                }
            }
        });
    }
    public void onAudioLevel(float level) { }

    private boolean isPauseAudioEnabled() {
        return new File(getFilesDir(), "pause_audio").exists();
    }
}
