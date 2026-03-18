package dev.surma.parakeeb;

import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

import okhttp3.Call;

public class RustInputMethodService extends InputMethodService {
    private static final String TAG = "OfflineVoiceInput";
    private static final long REPEAT_INITIAL_DELAY = 400;
    private static final long REPEAT_INTERVAL = 50;
    private static final float SPACE_CURSOR_STEP_DP = 16f;

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
    private View rewriteButton;
    private ImageView rewriteIcon;
    private ProgressBar rewriteProgress;
    private Handler mainHandler;
    private boolean isRecording = false;
    private String lastStatus = "Initializing...";
    private Runnable backspaceRepeatRunnable;
    private Runnable spaceCursorLongPressRunnable;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private SpacebarCursorStepper spacebarCursorStepper;
    private boolean isSpaceCursorDragActive = false;
    private float lastSpaceTouchRawX = 0f;
    private boolean pauseAudioActive = false;
    private LayerDrawable recordButtonBackground;
    private int transcribeProgressPercent = -1;
    private LlmSettingsStore llmSettingsStore;
    private OpenAiChatClient openAiChatClient;
    private Call inFlightRewriteCall;
    private RewriteTarget inFlightRewriteTarget;
    private boolean rewriteCancelRequested = false;
    private String lastCommittedTranscription = "";

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        llmSettingsStore = new LlmSettingsStore(this);
        openAiChatClient = new OpenAiChatClient();
        Log.d(TAG, "Service onCreate");
        try {
            initNative(this);
        } catch (Exception e) {
            Log.e(TAG, "Error in initNative", e);
        }
    }

    @Override
    public void onStartInput(EditorInfo attribute, boolean restarting) {
        super.onStartInput(attribute, restarting);
        clearRewriteMemory();
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        cancelRewrite(false);
        clearRewriteMemory();
    }

    @Override
    public View onCreateInputView() {
        Log.d(TAG, "onCreateInputView");
        try {
            View view = getLayoutInflater().inflate(R.layout.ime_layout, null);
            int basePaddingBottom = view.getPaddingBottom();

            view.setOnApplyWindowInsetsListener((v, insets) -> {
                int paddingBottom = insets.getSystemWindowInsetBottom();
                v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), basePaddingBottom + paddingBottom);
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
            rewriteButton = view.findViewById(R.id.ime_rewrite);
            rewriteIcon = view.findViewById(R.id.ime_rewrite_icon);
            rewriteProgress = view.findViewById(R.id.ime_rewrite_progress);
            float cursorStepPx = SPACE_CURSOR_STEP_DP * getResources().getDisplayMetrics().density;
            spacebarCursorStepper = new SpacebarCursorStepper(cursorStepPx);

            rewriteButton.setOnClickListener(v -> startOrCancelRewrite());

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
                    default:
                        return false;
                }
            });

            spaceButton.setOnTouchListener((v, event) -> {
                switch (event.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        lastSpaceTouchRawX = event.getRawX();
                        isSpaceCursorDragActive = false;
                        if (spacebarCursorStepper != null) {
                            spacebarCursorStepper.reset();
                        }
                        mainHandler.postDelayed(spaceCursorLongPressRunnable, ViewConfiguration.getLongPressTimeout());
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
                    default:
                        return false;
                }
            });

            enterButton.setOnClickListener(v -> {
                InputConnection ic = getCurrentInputConnection();
                if (ic == null) {
                    return;
                }

                EditorInfo editorInfo = getCurrentInputEditorInfo();
                int imeOptions = editorInfo.imeOptions;
                int action = imeOptions & EditorInfo.IME_MASK_ACTION;
                boolean noEnterAction = (imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;

                if (!noEnterAction && (
                        action == EditorInfo.IME_ACTION_GO ||
                        action == EditorInfo.IME_ACTION_SEARCH ||
                        action == EditorInfo.IME_ACTION_SEND ||
                        action == EditorInfo.IME_ACTION_NEXT)) {
                    ic.performEditorAction(action);
                } else {
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                    ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                }
            });

            recordButton.setOnClickListener(v -> toggleRecordingFromImeTrigger());

            updateUiState();
            updateRewriteButtonUi();
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
        cancelRewrite(false);
        clearRewriteMemory();
        if (isRecording) {
            try {
                cancelRecording();
            } catch (Throwable t) {
                Log.w(TAG, "cancelRecording failed, falling back to stopRecording", t);
                try {
                    stopRecording();
                } catch (Throwable ignored) {
                }
            }
            transcribeProgressPercent = -1;
            updateRecordButtonUI(false);
        }
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean imeShown = isInputViewShown();
        int repeatCount = event == null ? 0 : event.getRepeatCount();
        if (ImeVolumeKeyHandler.shouldToggleRecordingOnKeyDown(imeShown, keyCode, repeatCount)) {
            toggleRecordingFromImeTrigger();
            return true;
        }
        if (ImeVolumeKeyHandler.shouldConsumeKeyDown(imeShown, keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (ImeVolumeKeyHandler.shouldConsumeKeyUp(isInputViewShown(), keyCode)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void toggleRecordingFromImeTrigger() {
        if (recordButton != null && !recordButton.isEnabled()) {
            return;
        }

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "No mic permission - grant in app", Toast.LENGTH_SHORT).show();
            return;
        }

        if (isRecording) {
            stopActiveRecording();
        } else {
            startActiveRecording();
        }
    }

    private void startActiveRecording() {
        transcribeProgressPercent = -1;
        if (isPauseAudioEnabled()) {
            audioPauser.request(this);
            pauseAudioActive = true;
        }
        startRecording();
        updateRecordButtonUI(true);
    }

    private void stopActiveRecording() {
        stopRecording();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        transcribeProgressPercent = 0;
        updateRecordButtonUI(false);
    }

    private void updateRecordButtonUI(boolean recording) {
        isRecording = recording;
        if (recordButton == null) {
            return;
        }

        if (recording) {
            recordButton.setColorFilter(0xFFF44336);
        } else {
            recordButton.setColorFilter(0xFF2196F3);
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

    private void updateRewriteButtonUi() {
        if (rewriteButton == null || rewriteIcon == null || rewriteProgress == null) {
            return;
        }

        boolean loading = inFlightRewriteCall != null;
        rewriteProgress.setVisibility(loading ? View.VISIBLE : View.GONE);
        rewriteIcon.setVisibility(loading ? View.INVISIBLE : View.VISIBLE);
        rewriteButton.setContentDescription(getString(loading ? R.string.ime_rewrite_cancel : R.string.ime_rewrite));
        rewriteButton.setAlpha(1.0f);
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

    private void startOrCancelRewrite() {
        if (inFlightRewriteCall != null) {
            cancelRewrite(true);
            return;
        }

        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            showRewriteFailure(getString(R.string.toast_rewrite_missing_target));
            return;
        }

        RewriteTarget target = resolveRewriteTarget(ic);
        if (target == null) {
            Toast.makeText(this, R.string.toast_rewrite_missing_target, Toast.LENGTH_SHORT).show();
            return;
        }
        if (target.parts.coreText.isEmpty()) {
            Toast.makeText(this, R.string.toast_rewrite_missing_text, Toast.LENGTH_SHORT).show();
            return;
        }

        LlmSettings settings;
        try {
            settings = llmSettingsStore.read();
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to read LLM settings", e);
            showRewriteFailure(getString(R.string.toast_rewrite_failed));
            return;
        }

        rewriteCancelRequested = false;
        inFlightRewriteTarget = target;
        Call call = openAiChatClient.rewriteAsync(settings, target.parts.coreText, new OpenAiChatClient.Callback() {
            @Override
            public void onSuccess(String text) {
                mainHandler.post(() -> handleRewriteSuccess(text));
            }

            @Override
            public void onFailure(String message, Throwable error) {
                mainHandler.post(() -> handleRewriteFailure(message));
            }

            @Override
            public void onCanceled() {
                mainHandler.post(() -> handleRewriteCanceled());
            }
        });
        inFlightRewriteCall = call;
        updateRewriteButtonUi();
    }

    private RewriteTarget resolveRewriteTarget(InputConnection ic) {
        RewriteTarget selectionTarget = RewriteTargetResolver.fromSelectedText(ic.getSelectedText(0));
        if (selectionTarget != null) {
            return selectionTarget;
        }

        if (lastCommittedTranscription.isEmpty()) {
            return null;
        }

        ExtractedText extractedText = ic.getExtractedText(new ExtractedTextRequest(), 0);
        int[] range = RewriteTargetResolver.findReplacementRange(extractedText, lastCommittedTranscription);
        if (range == null) {
            return null;
        }
        return new RewriteTarget(RewriteTarget.Kind.LAST_DICTATION, lastCommittedTranscription);
    }

    private void cancelRewrite(boolean userInitiated) {
        if (inFlightRewriteCall == null) {
            return;
        }
        rewriteCancelRequested = userInitiated;
        inFlightRewriteCall.cancel();
    }

    private void handleRewriteSuccess(String rewrittenCore) {
        RewriteTarget target = inFlightRewriteTarget;
        inFlightRewriteCall = null;
        inFlightRewriteTarget = null;
        rewriteCancelRequested = false;
        updateRewriteButtonUi();

        if (target == null) {
            showRewriteFailure(getString(R.string.toast_rewrite_failed));
            return;
        }

        if (!replaceTargetText(target, rewrittenCore)) {
            showRewriteFailure(getString(R.string.toast_rewrite_failed));
        }
    }

    private void handleRewriteFailure(String message) {
        inFlightRewriteCall = null;
        inFlightRewriteTarget = null;
        rewriteCancelRequested = false;
        updateRewriteButtonUi();
        showRewriteFailure(message);
    }

    private void handleRewriteCanceled() {
        inFlightRewriteCall = null;
        inFlightRewriteTarget = null;
        boolean shouldToast = rewriteCancelRequested;
        rewriteCancelRequested = false;
        updateRewriteButtonUi();
        if (shouldToast) {
            Toast.makeText(this, R.string.toast_rewrite_canceled, Toast.LENGTH_SHORT).show();
        }
    }

    private boolean replaceTargetText(RewriteTarget target, String rewrittenCore) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return false;
        }

        String replacement = target.parts.reassemble(rewrittenCore);
        CharSequence selectedText = ic.getSelectedText(0);
        if (selectedText != null && target.rawText.contentEquals(selectedText)) {
            ic.commitText(replacement, 1);
            lastCommittedTranscription = replacement;
            return true;
        }

        ExtractedText extractedText = ic.getExtractedText(new ExtractedTextRequest(), 0);
        int[] range = RewriteTargetResolver.findReplacementRange(extractedText, target.rawText);
        if (range == null) {
            return false;
        }

        ic.beginBatchEdit();
        try {
            ic.setSelection(range[0], range[1]);
            ic.commitText(replacement, 1);
        } finally {
            ic.endBatchEdit();
        }
        lastCommittedTranscription = replacement;
        return true;
    }

    private void showRewriteFailure(String message) {
        String text = getString(R.string.toast_rewrite_failed);
        if (message != null && !message.isEmpty() && !message.equals(text)) {
            text = text + ": " + message;
        }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private void clearRewriteMemory() {
        lastCommittedTranscription = "";
        inFlightRewriteTarget = null;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelRewrite(false);
        cleanupNative();
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
    }

    private native void initNative(RustInputMethodService service);
    private native void cleanupNative();
    private native void startRecording();
    private native void stopRecording();
    private native void cancelRecording();

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

            if (pauseAudioActive && status != null && status.startsWith("Error")) {
                audioPauser.abandon(this);
                pauseAudioActive = false;
            }
        });
    }

    private void updateUiState() {
        boolean isWaiting = lastStatus.contains("Waiting");

        if (recordButton != null) {
            boolean disable = isWaiting;
            recordButton.setEnabled(!disable);
            recordButton.setAlpha(disable ? 0.5f : 1.0f);
        }

        updateRecordButtonProgress();
        updateRewriteButtonUi();
    }

    public void onTextTranscribed(String text) {
        mainHandler.post(() -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                String committed = text + " ";
                lastCommittedTranscription = committed;
                ic.commitText(committed, 1);

                if (new File(getFilesDir(), "select_transcription").exists()) {
                    ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
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

    public void onAudioLevel(float level) {
    }

    private boolean isPauseAudioEnabled() {
        return new File(getFilesDir(), "pause_audio").exists();
    }
}
