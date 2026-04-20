package dev.surma.parakeeb;

import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
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
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

import okhttp3.Call;

public class RustInputMethodService extends InputMethodService {
    private static final String TAG = "OfflineVoiceInput";
    private static final long REPEAT_INITIAL_DELAY = 400;
    private static final long REPEAT_INTERVAL = 50;
    private static final long DOUBLE_TAP_TIMEOUT = ViewConfiguration.getDoubleTapTimeout();
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

    private View recordButton;
    private ImageView recordIcon;
    private ProgressBar recordProgress;
    private View backspaceButton;
    private View spaceButton;
    private View enterButton;
    private Handler mainHandler;
    private View togglePanelButton;
    private ImageView togglePanelIcon;
    private View expandedPanel;
    private ListView historyList;
    private TextView historyEmpty;
    private boolean panelExpanded = false;
    private TranscriptHistoryStore historyStore;
    private HistoryAdapter historyAdapter;
    private boolean isRecording = false;
    private String lastStatus = "Initializing...";
    private Runnable backspaceRepeatRunnable;
    private Runnable spaceCursorLongPressRunnable;
    private final AudioFocusPauser audioPauser = new AudioFocusPauser();
    private SpacebarCursorStepper spacebarCursorStepper;
    private boolean isSpaceCursorDragActive = false;
    private float lastSpaceTouchRawX = 0f;
    private boolean pauseAudioActive = false;
    private boolean isTranscribing = false;
    private LlmSettingsStore llmSettingsStore;
    private OpenAiChatClient openAiChatClient;
    private Call inFlightRewriteCall;
    private RewriteTarget inFlightRewriteTarget;
    private boolean rewriteCancelRequested = false;
    private boolean volumeUpLongPressTriggered = false;
    private Runnable pendingVolumeDownStartRunnable;
    private Runnable pendingVolumeDownPostStopGuardRunnable;
    private Runnable pendingVolumeUpRewriteRunnable;
    private boolean awaitingTranscriptionResult = false;
    private boolean pendingSendAfterTranscription = false;

    @Override
    public void onCreate() {
        super.onCreate();
        mainHandler = new Handler(Looper.getMainLooper());
        llmSettingsStore = new LlmSettingsStore(this);
        openAiChatClient = new OpenAiChatClient();
        historyStore = new TranscriptHistoryStore(this);
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
        clearInputSessionState();
    }

    @Override
    public void onFinishInput() {
        super.onFinishInput();
        cancelRewrite(false);
        clearInputSessionState();
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
            recordIcon = view.findViewById(R.id.ime_record_icon);
            recordProgress = view.findViewById(R.id.ime_record_progress);
            backspaceButton = view.findViewById(R.id.ime_backspace);
            spaceButton = view.findViewById(R.id.ime_space);
            enterButton = view.findViewById(R.id.ime_enter);
            float cursorStepPx = SPACE_CURSOR_STEP_DP * getResources().getDisplayMetrics().density;
            spacebarCursorStepper = new SpacebarCursorStepper(cursorStepPx);

            // Expanded panel
            togglePanelButton = view.findViewById(R.id.ime_toggle_panel);
            togglePanelIcon = view.findViewById(R.id.ime_toggle_panel_icon);
            expandedPanel = view.findViewById(R.id.expanded_panel);
            historyList = view.findViewById(R.id.history_list);
            historyEmpty = view.findViewById(R.id.history_empty);

            historyAdapter = new HistoryAdapter(this);
            historyList.setAdapter(historyAdapter);

            togglePanelButton.setOnClickListener(v -> togglePanel());

            historyList.setOnItemClickListener((parent, v, position, id) -> {
                TranscriptEntry entry = historyAdapter.getItem(position);
                if (entry == null) return;
                InputConnection ic = getCurrentInputConnection();
                if (ic != null) {
                    String committed = entry.text + " ";
                    ic.commitText(committed, 1);
                    ExtractedText et = ic.getExtractedText(new ExtractedTextRequest(), 0);
                    if (et != null) {
                        int end = et.selectionStart;
                        int start = end - committed.length();
                        if (start >= 0) {
                            ic.setSelection(start, end);
                        }
                    }
                }
            });

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

            enterButton.setOnClickListener(v -> performImeEnterAction());

            recordButton.setOnClickListener(v -> toggleRecordingFromImeTrigger());

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
                isTranscribing = false;
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
        collapsePanel();
        clearInputSessionState();
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
            isTranscribing = false;
            updateRecordButtonUI(false);
        }
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        volumeUpLongPressTriggered = false;
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        boolean imeShown = isInputViewShown();
        int repeatCount = event == null ? 0 : event.getRepeatCount();
        if (ImeVolumeKeyHandler.shouldTrackVolumeDownOnKeyDown(imeShown, keyCode, repeatCount)) {
            return true;
        }
        if (ImeVolumeKeyHandler.shouldTrackVolumeUpOnKeyDown(imeShown, keyCode, repeatCount)) {
            volumeUpLongPressTriggered = false;
            if (event != null) {
                event.startTracking();
            }
            return true;
        }
        if (ImeVolumeKeyHandler.shouldConsumeKeyDown(imeShown, keyCode)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyLongPress(int keyCode, KeyEvent event) {
        if (ImeVolumeKeyHandler.shouldTriggerSelectAllOnLongPress(isInputViewShown(), keyCode)) {
            volumeUpLongPressTriggered = true;
            cancelPendingVolumeUpRewrite();
            selectAllTextInCurrentField();
            return true;
        }
        return super.onKeyLongPress(keyCode, event);
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        boolean imeShown = isInputViewShown();
        if (ImeVolumeKeyHandler.shouldResolveVolumeDownOnKeyUp(imeShown, keyCode)) {
            handleVolumeDownKeyUp();
            return true;
        }
        if (ImeVolumeKeyHandler.shouldResolveVolumeUpOnKeyUp(imeShown, keyCode, volumeUpLongPressTriggered)) {
            volumeUpLongPressTriggered = false;
            handleVolumeUpKeyUp();
            return true;
        }
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP) {
            volumeUpLongPressTriggered = false;
        }
        if (ImeVolumeKeyHandler.shouldConsumeKeyUp(imeShown, keyCode)) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    private void toggleRecordingFromImeTrigger() {
        if (!canUseImeRecordingTrigger()) {
            return;
        }

        if (isRecording) {
            stopActiveRecording();
        } else {
            startActiveRecording();
        }
    }

    private boolean canUseImeRecordingTrigger() {
        if (recordButton != null && !recordButton.isEnabled()) {
            return false;
        }

        if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "No mic permission - grant in app", Toast.LENGTH_SHORT).show();
            return false;
        }

        return true;
    }

    private void handleVolumeDownKeyUp() {
        VolumeDownActionResolver.Action action = VolumeDownActionResolver.resolve(
                isRecording,
                isVolumeDownPostStopGuardActive(),
                pendingVolumeDownStartRunnable != null);

        switch (action) {
            case STOP_RECORDING:
                if (!canUseImeRecordingTrigger()) {
                    return;
                }
                stopActiveRecording();
                armVolumeDownPostStopGuard();
                return;
            case SEND:
                consumePendingVolumeDownStart();
                requestSendAfterTranscriptionOrImmediately();
                return;
            case SCHEDULE_START:
                if (!canUseImeRecordingTrigger()) {
                    return;
                }
                scheduleVolumeDownStart();
                return;
            case IGNORE:
            default:
                return;
        }
    }

    private void handleVolumeUpKeyUp() {
        if (consumePendingVolumeUpRewrite()) {
            collapseSelectionToEnd();
            return;
        }

        if (hasActiveSelection()) {
            scheduleVolumeUpRewrite();
            return;
        }

        startOrCancelRewrite();
    }

    private void startActiveRecording() {
        cancelPendingVolumeDownStart();
        cancelVolumeDownPostStopGuard();
        pendingSendAfterTranscription = false;
        awaitingTranscriptionResult = false;
        isTranscribing = false;
        if (isPauseAudioEnabled()) {
            audioPauser.request(this);
            pauseAudioActive = true;
        }
        startRecording();
        updateRecordButtonUI(true);
    }

    private void stopActiveRecording() {
        stopRecording();
        awaitingTranscriptionResult = true;
        isTranscribing = true;
        if (pauseAudioActive) {
            audioPauser.abandon(this);
            pauseAudioActive = false;
        }
        updateRecordButtonUI(false);
    }

    private void scheduleVolumeDownStart() {
        cancelPendingVolumeDownStart();
        pendingVolumeDownStartRunnable = () -> {
            pendingVolumeDownStartRunnable = null;
            if (canUseImeRecordingTrigger()) {
                startActiveRecording();
            }
        };
        mainHandler.postDelayed(pendingVolumeDownStartRunnable, DOUBLE_TAP_TIMEOUT);
    }

    private boolean consumePendingVolumeDownStart() {
        if (pendingVolumeDownStartRunnable == null) {
            return false;
        }

        mainHandler.removeCallbacks(pendingVolumeDownStartRunnable);
        pendingVolumeDownStartRunnable = null;
        return true;
    }

    private void cancelPendingVolumeDownStart() {
        if (pendingVolumeDownStartRunnable == null) {
            return;
        }

        mainHandler.removeCallbacks(pendingVolumeDownStartRunnable);
        pendingVolumeDownStartRunnable = null;
    }

    private void armVolumeDownPostStopGuard() {
        cancelVolumeDownPostStopGuard();
        pendingVolumeDownPostStopGuardRunnable = () -> pendingVolumeDownPostStopGuardRunnable = null;
        mainHandler.postDelayed(pendingVolumeDownPostStopGuardRunnable, DOUBLE_TAP_TIMEOUT);
    }

    private boolean isVolumeDownPostStopGuardActive() {
        return pendingVolumeDownPostStopGuardRunnable != null;
    }

    private void cancelVolumeDownPostStopGuard() {
        if (pendingVolumeDownPostStopGuardRunnable == null) {
            return;
        }

        mainHandler.removeCallbacks(pendingVolumeDownPostStopGuardRunnable);
        pendingVolumeDownPostStopGuardRunnable = null;
    }

    private void scheduleVolumeUpRewrite() {
        cancelPendingVolumeUpRewrite();
        pendingVolumeUpRewriteRunnable = () -> {
            pendingVolumeUpRewriteRunnable = null;
            startOrCancelRewrite();
        };
        mainHandler.postDelayed(pendingVolumeUpRewriteRunnable, DOUBLE_TAP_TIMEOUT);
    }

    private boolean consumePendingVolumeUpRewrite() {
        if (pendingVolumeUpRewriteRunnable == null) {
            return false;
        }

        mainHandler.removeCallbacks(pendingVolumeUpRewriteRunnable);
        pendingVolumeUpRewriteRunnable = null;
        return true;
    }

    private void cancelPendingVolumeUpRewrite() {
        if (pendingVolumeUpRewriteRunnable == null) {
            return;
        }

        mainHandler.removeCallbacks(pendingVolumeUpRewriteRunnable);
        pendingVolumeUpRewriteRunnable = null;
    }

    private boolean hasActiveSelection() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return false;
        }

        CharSequence selectedText = ic.getSelectedText(0);
        return selectedText != null && selectedText.length() > 0;
    }

    private void requestSendAfterTranscriptionOrImmediately() {
        if (awaitingTranscriptionResult) {
            pendingSendAfterTranscription = true;
            return;
        }

        performImeEnterAction();
    }

    private void collapseSelectionToEnd() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }

        ExtractedText extractedText = ic.getExtractedText(new ExtractedTextRequest(), 0);
        SelectionEditor.collapseSelectionToEnd(ic, extractedText);
    }

    private void selectAllTextInCurrentField() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }

        ExtractedText extractedText = ic.getExtractedText(new ExtractedTextRequest(), 0);
        SelectionEditor.selectAll(ic, extractedText);
    }

    private void updateRecordButtonUI(boolean recording) {
        isRecording = recording;
        if (recordButton == null) {
            return;
        }

        if (recordIcon != null) {
            if (recording) {
                recordIcon.setColorFilter(0xFFF44336);
            } else {
                recordIcon.setColorFilter(0xFF2196F3);
            }
            recordIcon.setVisibility(isTranscribing ? View.INVISIBLE : View.VISIBLE);
        }

        if (recordProgress != null) {
            recordProgress.setVisibility(isTranscribing ? View.VISIBLE : View.GONE);
        }
    }

    private void togglePanel() {
        panelExpanded = !panelExpanded;
        if (expandedPanel == null) return;

        if (panelExpanded) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int panelHeight = dm.heightPixels / 3;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) expandedPanel.getLayoutParams();
            lp.height = panelHeight;
            expandedPanel.setLayoutParams(lp);
            expandedPanel.setVisibility(View.VISIBLE);
            refreshHistoryList();
        } else {
            expandedPanel.setVisibility(View.GONE);
        }

        if (togglePanelIcon != null) {
            togglePanelIcon.setImageResource(
                    panelExpanded ? R.drawable.ic_collapse_panel : R.drawable.ic_expand_panel);
        }
    }

    private void collapsePanel() {
        if (!panelExpanded) return;
        panelExpanded = false;
        if (expandedPanel != null) {
            expandedPanel.setVisibility(View.GONE);
        }
        if (togglePanelIcon != null) {
            togglePanelIcon.setImageResource(R.drawable.ic_expand_panel);
        }
    }

    private void refreshHistoryList() {
        if (historyAdapter == null || historyStore == null) return;
        java.util.List<TranscriptEntry> entries = historyStore.getRecent();
        historyAdapter.setEntries(entries);
        if (historyEmpty != null) {
            historyEmpty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
        }
        if (historyList != null) {
            historyList.setVisibility(entries.isEmpty() ? View.GONE : View.VISIBLE);
        }
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

    private void performImeEnterAction() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) {
            return;
        }

        EditorInfo editorInfo = getCurrentInputEditorInfo();
        int imeOptions = editorInfo == null ? 0 : editorInfo.imeOptions;
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
    }

    private RewriteTarget resolveRewriteTarget(InputConnection ic) {
        RewriteTarget selectionTarget = RewriteTargetResolver.fromSelectedText(ic.getSelectedText(0));
        if (selectionTarget != null) {
            return selectionTarget;
        }

        ExtractedText extractedText = ic.getExtractedText(new ExtractedTextRequest(), 0);
        return RewriteTargetResolver.fromExtractedText(extractedText);
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
        showRewriteFailure(message);
    }

    private void handleRewriteCanceled() {
        inFlightRewriteCall = null;
        inFlightRewriteTarget = null;
        boolean shouldToast = rewriteCancelRequested;
        rewriteCancelRequested = false;
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
        if (target.kind == RewriteTarget.Kind.CURRENT_SELECTION
                && selectedText != null
                && target.rawText.contentEquals(selectedText)) {
            ic.commitText(replacement, 1);
            return true;
        }

        ExtractedText extractedText = ic.getExtractedText(new ExtractedTextRequest(), 0);
        int[] range = target.kind == RewriteTarget.Kind.CURRENT_FIELD
                ? RewriteTargetResolver.fullFieldRange(extractedText)
                : RewriteTargetResolver.findReplacementRange(extractedText, target.rawText);
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
        return true;
    }

    private void showRewriteFailure(String message) {
        String text = getString(R.string.toast_rewrite_failed);
        if (message != null && !message.isEmpty() && !message.equals(text)) {
            text = text + ": " + message;
        }
        Toast.makeText(this, text, Toast.LENGTH_LONG).show();
    }

    private void clearInputSessionState() {
        cancelPendingVolumeDownStart();
        cancelVolumeDownPostStopGuard();
        cancelPendingVolumeUpRewrite();
        inFlightRewriteTarget = null;
        awaitingTranscriptionResult = false;
        pendingSendAfterTranscription = false;
        isTranscribing = false;
        volumeUpLongPressTriggered = false;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        cancelRewrite(false);
        clearInputSessionState();
        cleanupNative();
        if (historyStore != null) {
            historyStore.close();
        }
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

            if (status != null && (status.startsWith("Queued") || status.startsWith("Transcribing"))) {
                isTranscribing = true;
            } else if (status == null || status.startsWith("Ready") || status.startsWith("Listening")
                    || status.startsWith("Canceled") || status.startsWith("Error")) {
                isTranscribing = false;
            }

            if (status == null || status.startsWith("Canceled") || status.startsWith("Error")) {
                awaitingTranscriptionResult = false;
                pendingSendAfterTranscription = false;
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

        updateRecordButtonUI(isRecording);
    }

    public void onTextTranscribed(String text) {
        mainHandler.post(() -> {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) {
                String committed = text + " ";
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

            // Save raw transcript to history
            if (historyStore != null && text != null && !text.isEmpty()) {
                historyStore.insert(text);
                if (panelExpanded) {
                    refreshHistoryList();
                }
            }

            awaitingTranscriptionResult = false;
            isTranscribing = false;
            updateRecordButtonUI(false);
            if (pendingSendAfterTranscription) {
                pendingSendAfterTranscription = false;
                performImeEnterAction();
            }
        });
    }

    public void onAudioLevel(float level) {
    }

    private boolean isPauseAudioEnabled() {
        return new File(getFilesDir(), "pause_audio").exists();
    }
}
