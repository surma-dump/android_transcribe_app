package dev.surma.parakeeb;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
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
import android.content.Intent;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

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
    private View panelHistory;
    private SoftKeyboardView panelKeyboard;
    private ImageView tabHistory;
    private ImageView tabKeyboard;
    private ImageView tabSettings;
    private ScrollView panelSettings;
    private View imeRootView;
    private boolean panelExpanded = false;
    private static final int TAB_KEYBOARD = 0;
    private static final int TAB_HISTORY  = 1;
    private static final int TAB_SETTINGS = 2;
    private static final String PREFS_NAME = "ime_ui_state";
    private static final String PREF_PANEL_EXPANDED = "panel_expanded";
    private static final String PREF_ACTIVE_TAB = "active_tab";
    private static final String PREF_AUTO_REWRITE = "auto_rewrite";
    private static final String PREF_PASTE_MODE = "paste_mode";
    private static final String PREF_AUTO_RECORD = "auto_record";
    private static final String PREF_PAUSE_AUDIO = "pause_audio";
    private static final String PREF_SELECT_TRANSCRIPTION = "select_transcription";
    private int activeTab = TAB_KEYBOARD;
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
    private boolean isRewriting = false;
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
            imeRootView = view;
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
            panelHistory = view.findViewById(R.id.panel_history);
            panelKeyboard = view.findViewById(R.id.panel_keyboard);
            panelSettings = view.findViewById(R.id.panel_settings);
            tabHistory = view.findViewById(R.id.tab_history);
            tabKeyboard = view.findViewById(R.id.tab_keyboard);
            tabSettings = view.findViewById(R.id.tab_settings);

            historyAdapter = new HistoryAdapter(this);
            historyList.setAdapter(historyAdapter);

            // Wire all toggle settings
            SharedPreferences uiPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            wireToggleSetting(view, R.id.setting_auto_rewrite_row,
                    R.string.setting_auto_rewrite, R.string.setting_auto_rewrite_desc,
                    PREF_AUTO_REWRITE, uiPrefs);
            wireToggleSetting(view, R.id.setting_paste_mode_row,
                    R.string.setting_paste_mode, R.string.setting_paste_mode_desc,
                    PREF_PASTE_MODE, uiPrefs);
            wireToggleSetting(view, R.id.setting_auto_record_row,
                    R.string.setting_auto_record, R.string.setting_auto_record_desc,
                    PREF_AUTO_RECORD, uiPrefs);
            wireToggleSetting(view, R.id.setting_pause_audio_row,
                    R.string.setting_pause_audio, R.string.setting_pause_audio_desc,
                    PREF_PAUSE_AUDIO, uiPrefs);
            wireToggleSetting(view, R.id.setting_select_transcription_row,
                    R.string.setting_select_transcription, R.string.setting_select_transcription_desc,
                    PREF_SELECT_TRANSCRIPTION, uiPrefs);

            // Wire LLM text settings
            wireTextSetting(view, R.id.setting_llm_base_url_row,
                    R.string.llm_base_url, "llm_base_url", false, false);
            wireTextSetting(view, R.id.setting_llm_model_row,
                    R.string.llm_model, "llm_model", false, false);
            wireTextSetting(view, R.id.setting_llm_api_key_row,
                    R.string.llm_api_key, "llm_api_key", true, false);
            wireTextSetting(view, R.id.setting_llm_system_prompt_row,
                    R.string.llm_system_prompt, "llm_system_prompt", false, true);

            // Wire LLM Test button
            View llmTestBtn = view.findViewById(R.id.btn_llm_test);
            if (llmTestBtn != null) {
                llmTestBtn.setOnClickListener(v2 -> startLlmTestConnection());
            }

            togglePanelButton.setOnClickListener(v -> togglePanel());

            // Tab switching
            tabHistory.setOnClickListener(v -> switchTab(TAB_HISTORY));
            tabKeyboard.setOnClickListener(v -> switchTab(TAB_KEYBOARD));
            tabSettings.setOnClickListener(v -> switchTab(TAB_SETTINGS));

            // Wire keyboard panel to our InputConnection
            if (panelKeyboard != null) {
                panelKeyboard.setInputConnectionProvider(() -> getCurrentInputConnection());
            }

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

            restorePanelState();
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
        restorePanelState();
        refreshTextSettingDisplays();
        if (!isRecording && getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_AUTO_RECORD, false)) {
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

        boolean showSpinner = isTranscribing || isRewriting;

        if (recordIcon != null) {
            if (recording) {
                recordIcon.setColorFilter(getResources().getColor(R.color.record_red, getTheme()));
            } else {
                recordIcon.setColorFilter(getResources().getColor(R.color.colorPrimary, getTheme()));
            }
            recordIcon.setVisibility(showSpinner ? View.INVISIBLE : View.VISIBLE);
        }

        if (recordProgress != null) {
            recordProgress.setVisibility(showSpinner ? View.VISIBLE : View.GONE);
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
            applyTabVisibility();
        } else {
            expandedPanel.setVisibility(View.GONE);
        }

        if (togglePanelIcon != null) {
            togglePanelIcon.setImageResource(
                    panelExpanded ? R.drawable.ic_collapse_panel : R.drawable.ic_expand_panel);
        }

        persistUiState();
    }

    /**
     * Restore the panel expanded/collapsed state and active tab from
     * SharedPreferences. Called on initial creation and every time the
     * keyboard window is shown again.
     */
    private void restorePanelState() {
        SharedPreferences uiPrefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        panelExpanded = uiPrefs.getBoolean(PREF_PANEL_EXPANDED, false);
        activeTab = uiPrefs.getInt(PREF_ACTIVE_TAB, TAB_KEYBOARD);

        if (expandedPanel == null) return;

        if (panelExpanded) {
            DisplayMetrics dm = getResources().getDisplayMetrics();
            int panelHeight = dm.heightPixels / 3;
            LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) expandedPanel.getLayoutParams();
            lp.height = panelHeight;
            expandedPanel.setLayoutParams(lp);
            expandedPanel.setVisibility(View.VISIBLE);
            applyTabVisibility();
        } else {
            expandedPanel.setVisibility(View.GONE);
        }

        if (togglePanelIcon != null) {
            togglePanelIcon.setImageResource(
                    panelExpanded ? R.drawable.ic_collapse_panel : R.drawable.ic_expand_panel);
        }
    }

    /**
     * Visually collapse the panel (e.g. when the keyboard window hides).
     * Does NOT persist the change — the user's preferred state is only
     * saved when they explicitly toggle via {@link #togglePanel()}.
     */
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

    private void switchTab(int tab) {
        if (activeTab == tab) return;
        activeTab = tab;
        applyTabVisibility();
        persistUiState();
    }

    private void applyTabVisibility() {
        boolean showHistory  = (activeTab == TAB_HISTORY);
        boolean showKeyboard = (activeTab == TAB_KEYBOARD);
        boolean showSettings = (activeTab == TAB_SETTINGS);

        if (panelHistory  != null) panelHistory.setVisibility(showHistory   ? View.VISIBLE : View.GONE);
        if (panelKeyboard != null) panelKeyboard.setVisibility(showKeyboard ? View.VISIBLE : View.GONE);
        if (panelSettings != null) panelSettings.setVisibility(showSettings ? View.VISIBLE : View.GONE);
        if (tabHistory  != null) tabHistory.setBackgroundResource(showHistory   ? R.drawable.bg_tab_selected : 0);
        if (tabKeyboard != null) tabKeyboard.setBackgroundResource(showKeyboard ? R.drawable.bg_tab_selected : 0);
        if (tabSettings != null) tabSettings.setBackgroundResource(showSettings ? R.drawable.bg_tab_selected : 0);

        if (showHistory) {
            refreshHistoryList();
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

    @SuppressWarnings("deprecation")
    private void wireToggleSetting(View root, int rowId, int titleRes, int descRes,
                                    String prefKey, SharedPreferences prefs) {
        View row = root.findViewById(rowId);
        if (row == null) return;
        TextView title = row.findViewById(R.id.setting_title);
        TextView desc = row.findViewById(R.id.setting_desc);
        Switch toggle = row.findViewById(R.id.setting_toggle);
        if (title != null) title.setText(titleRes);
        if (desc != null) desc.setText(descRes);
        if (toggle != null) {
            toggle.setChecked(prefs.getBoolean(prefKey, false));
            toggle.setOnCheckedChangeListener((btn, checked) ->
                    getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                            .edit().putBoolean(prefKey, checked).apply());
        }
    }

    private void wireTextSetting(View root, int rowId, int titleRes,
                                  String fieldKey, boolean masked, boolean isSystemPrompt) {
        View row = root.findViewById(rowId);
        if (row == null) return;
        TextView title = row.findViewById(R.id.setting_title);
        TextView value = row.findViewById(R.id.setting_value);
        if (title != null) title.setText(titleRes);
        updateTextSettingDisplay(value, fieldKey, masked);
        row.setOnClickListener(v -> launchFieldEditor(titleRes, fieldKey, isSystemPrompt));
    }

    private void updateTextSettingDisplay(TextView valueView, String fieldKey, boolean masked) {
        if (valueView == null) return;
        String current = readLlmField(fieldKey);
        if (current == null || current.isEmpty()) {
            if ("llm_system_prompt".equals(fieldKey)) {
                valueView.setText(R.string.setting_value_default);
            } else {
                valueView.setText(R.string.setting_value_not_set);
            }
        } else if (masked) {
            valueView.setText(R.string.setting_value_hidden);
        } else {
            valueView.setText(current);
        }
    }

    private String readLlmField(String fieldKey) {
        LlmSettings s = llmSettingsStore.read();
        switch (fieldKey) {
            case "llm_base_url": return s.baseUrl;
            case "llm_model": return s.model;
            case "llm_api_key": return s.apiKey;
            case "llm_system_prompt": return s.systemPrompt;
            default: return "";
        }
    }

    private void launchFieldEditor(int titleRes, String fieldKey, boolean isSystemPrompt) {
        Intent intent = new Intent(this, FieldEditorActivity.class);
        intent.putExtra(FieldEditorActivity.EXTRA_FIELD_KEY, fieldKey);
        intent.putExtra(FieldEditorActivity.EXTRA_TITLE, getString(titleRes));
        intent.putExtra(FieldEditorActivity.EXTRA_IS_SYSTEM_PROMPT, isSystemPrompt);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
    }

    private void refreshTextSettingDisplays() {
        if (imeRootView == null) return;
        refreshOneTextSetting(imeRootView, R.id.setting_llm_base_url_row, "llm_base_url", false);
        refreshOneTextSetting(imeRootView, R.id.setting_llm_model_row, "llm_model", false);
        refreshOneTextSetting(imeRootView, R.id.setting_llm_api_key_row, "llm_api_key", true);
        refreshOneTextSetting(imeRootView, R.id.setting_llm_system_prompt_row, "llm_system_prompt", false);
    }

    private void refreshOneTextSetting(View root, int rowId, String fieldKey, boolean masked) {
        View row = root.findViewById(rowId);
        if (row == null) return;
        TextView value = row.findViewById(R.id.setting_value);
        updateTextSettingDisplay(value, fieldKey, masked);
    }



    private void startLlmTestConnection() {
        LlmSettings settings;
        try {
            settings = llmSettingsStore.read();
        } catch (RuntimeException e) {
            Toast.makeText(this, R.string.toast_llm_settings_error, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!settings.hasRequiredFields()) {
            Toast.makeText(this, R.string.toast_rewrite_no_llm_config, Toast.LENGTH_SHORT).show();
            return;
        }

        Toast.makeText(this, R.string.btn_llm_testing, Toast.LENGTH_SHORT).show();
        openAiChatClient.testConnectionAsync(settings, new OpenAiChatClient.Callback() {
            @Override
            public void onSuccess(String text) {
                mainHandler.post(() ->
                    Toast.makeText(RustInputMethodService.this, R.string.toast_llm_test_success, Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onFailure(String message, Throwable error) {
                mainHandler.post(() -> {
                    String msg = getString(R.string.toast_llm_test_failure);
                    if (message != null && !message.isEmpty()) msg = msg + ": " + message;
                    Toast.makeText(RustInputMethodService.this, msg, Toast.LENGTH_LONG).show();
                });
            }

            @Override
            public void onCanceled() {
                mainHandler.post(() ->
                    Toast.makeText(RustInputMethodService.this, R.string.toast_llm_test_canceled, Toast.LENGTH_SHORT).show());
            }
        });
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

        LlmSettings settings = checkRewritePreconditions();
        if (settings == null) return;

        rewriteCancelRequested = false;
        isRewriting = true;
        inFlightRewriteTarget = target;
        updateUiState();
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
        isRewriting = false;
        updateUiState();

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
        isRewriting = false;
        updateUiState();
        showRewriteFailure(message);
    }

    private void handleRewriteCanceled() {
        inFlightRewriteCall = null;
        inFlightRewriteTarget = null;
        boolean shouldToast = rewriteCancelRequested;
        rewriteCancelRequested = false;
        isRewriting = false;
        updateUiState();
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
        isRewriting = false;
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
            boolean disable = isWaiting || isRewriting;
            recordButton.setEnabled(!disable);
            recordButton.setAlpha(disable ? 0.5f : 1.0f);
        }

        updateRecordButtonUI(isRecording);
    }

    public void onTextTranscribed(String text) {
        mainHandler.post(() -> {
            // Always save raw transcript to history first.
            if (historyStore != null && text != null && !text.isEmpty()) {
                historyStore.insert(text);
                if (panelExpanded) {
                    refreshHistoryList();
                }
            }

            awaitingTranscriptionResult = false;
            isTranscribing = false;

            if (isAutoRewriteEnabled()) {
                LlmSettings settings = checkRewritePreconditions();
                if (settings == null) {
                    // Can't rewrite — fall back to committing raw text.
                    commitTranscription(text, true);
                    updateRecordButtonUI(false);
                    if (pendingSendAfterTranscription) {
                        pendingSendAfterTranscription = false;
                        performImeEnterAction();
                    }
                    return;
                }

                // Show rewrite spinner.
                isRewriting = true;
                updateRecordButtonUI(false);

                Call call = openAiChatClient.rewriteAsync(settings, text, new OpenAiChatClient.Callback() {
                    @Override
                    public void onSuccess(String rewritten) {
                        mainHandler.post(() -> {
                            isRewriting = false;
                            updateUiState();
                            commitTranscription(rewritten, false);
                            if (pendingSendAfterTranscription) {
                                pendingSendAfterTranscription = false;
                                performImeEnterAction();
                            }
                        });
                    }

                    @Override
                    public void onFailure(String message, Throwable error) {
                        mainHandler.post(() -> {
                            isRewriting = false;
                            updateUiState();
                            showRewriteFailure(message);
                            // Fall back to committing raw text.
                            commitTranscription(text, true);
                            if (pendingSendAfterTranscription) {
                                pendingSendAfterTranscription = false;
                                performImeEnterAction();
                            }
                        });
                    }

                    @Override
                    public void onCanceled() {
                        mainHandler.post(() -> {
                            isRewriting = false;
                            updateUiState();
                            // Fall back to committing raw text.
                            commitTranscription(text, true);
                            if (pendingSendAfterTranscription) {
                                pendingSendAfterTranscription = false;
                                performImeEnterAction();
                            }
                        });
                    }
                });
                // Store the call so it can be canceled if the keyboard hides.
                inFlightRewriteCall = call;
            } else {
                commitTranscription(text, true);
                updateRecordButtonUI(false);
                if (pendingSendAfterTranscription) {
                    pendingSendAfterTranscription = false;
                    performImeEnterAction();
                }
            }
        });
    }

    private void commitTranscription(String text, boolean selectAfterCommit) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        String committed = text + " ";

        if (isPasteModeEnabled()) {
            // Clipboard paste: put text on clipboard and simulate Ctrl+V.
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText("transcription", committed));
                long now = android.os.SystemClock.uptimeMillis();
                int meta = KeyEvent.META_CTRL_ON | KeyEvent.META_CTRL_LEFT_ON;
                ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN,
                        KeyEvent.KEYCODE_V, 0, meta));
                ic.sendKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP,
                        KeyEvent.KEYCODE_V, 0, meta));
            }
        } else {
            ic.commitText(committed, 1);
        }

        if (selectAfterCommit && !isPasteModeEnabled()
                && getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .getBoolean(PREF_SELECT_TRANSCRIPTION, false)) {
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

    private boolean isPasteModeEnabled() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_PASTE_MODE, false);
    }

    public void onAudioLevel(float level) {
    }

    private boolean isPauseAudioEnabled() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_PAUSE_AUDIO, false);
    }

    private boolean isAutoRewriteEnabled() {
        return getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean(PREF_AUTO_REWRITE, false);
    }

    private void persistUiState() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(PREF_PANEL_EXPANDED, panelExpanded)
                .putInt(PREF_ACTIVE_TAB, activeTab)
                .apply();
    }

    /**
     * Check that we have a usable network connection.
     * Returns true if connected, false otherwise.
     */
    private boolean hasNetworkConnection() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.Network network = cm.getActiveNetwork();
        if (network == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(network);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
    }

    /**
     * Pre-flight check for rewrite: verifies network and LLM config.
     * Shows a toast and returns null if either is missing; returns valid
     * LlmSettings otherwise.
     */
    private LlmSettings checkRewritePreconditions() {
        if (!hasNetworkConnection()) {
            Toast.makeText(this, R.string.toast_rewrite_no_network, Toast.LENGTH_SHORT).show();
            return null;
        }

        LlmSettings settings;
        try {
            settings = llmSettingsStore.read();
        } catch (RuntimeException e) {
            Log.e(TAG, "Failed to read LLM settings", e);
            showRewriteFailure(getString(R.string.toast_rewrite_failed));
            return null;
        }

        if (!settings.hasRequiredFields()) {
            Toast.makeText(this, R.string.toast_rewrite_no_llm_config, Toast.LENGTH_SHORT).show();
            return null;
        }

        return settings;
    }
}
