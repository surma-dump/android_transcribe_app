package dev.surma.parakeeb;

import android.app.Activity;
import android.os.Bundle;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

/**
 * Dialog-themed activity for editing a text setting from the IME.
 * Launched from the keyboard settings panel so that the system keyboard
 * (which may be Parakeeb itself) can provide normal text input.
 */
public class FieldEditorActivity extends Activity {
    static final String EXTRA_FIELD_KEY = "field_key";
    static final String EXTRA_TITLE = "title";
    static final String EXTRA_IS_SYSTEM_PROMPT = "is_system_prompt";

    private String fieldKey;
    private LlmSettingsStore llmSettingsStore;
    private EditText input;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_field_editor);

        llmSettingsStore = new LlmSettingsStore(this);
        fieldKey = getIntent().getStringExtra(EXTRA_FIELD_KEY);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        boolean isSystemPrompt = getIntent().getBooleanExtra(EXTRA_IS_SYSTEM_PROMPT, false);

        TextView titleView = findViewById(R.id.editor_title);
        input = findViewById(R.id.editor_input);
        View resetButton = findViewById(R.id.editor_reset);
        View cancelButton = findViewById(R.id.editor_cancel);
        View saveButton = findViewById(R.id.editor_save);

        if (titleView != null && title != null) {
            titleView.setText(title);
        }

        // Load current value
        String currentValue = readField();
        if (isSystemPrompt && (currentValue == null || currentValue.isEmpty())) {
            currentValue = LlmPromptRenderer.defaultSystemPrompt();
        }
        if (input != null && currentValue != null) {
            input.setText(currentValue);
            input.setSelection(currentValue.length());
        }

        // Show reset button only for system prompt
        if (resetButton != null) {
            if (isSystemPrompt) {
                resetButton.setVisibility(View.VISIBLE);
                resetButton.setOnClickListener(v -> {
                    if (input != null) {
                        String defaultPrompt = LlmPromptRenderer.defaultSystemPrompt();
                        input.setText(defaultPrompt);
                        input.setSelection(defaultPrompt.length());
                    }
                });
            } else {
                resetButton.setVisibility(View.GONE);
            }
        }

        if (cancelButton != null) {
            cancelButton.setOnClickListener(v -> finish());
        }

        if (saveButton != null) {
            saveButton.setOnClickListener(v -> {
                saveField();
                finish();
            });
        }
    }

    private String readField() {
        try {
            LlmSettings settings = llmSettingsStore.read();
            switch (fieldKey) {
                case "llm_base_url": return settings.baseUrl;
                case "llm_model": return settings.model;
                case "llm_api_key": return settings.apiKey;
                case "llm_system_prompt": return settings.systemPrompt;
                default: return "";
            }
        } catch (RuntimeException e) {
            return "";
        }
    }

    private void saveField() {
        if (input == null || fieldKey == null) return;
        String value = input.getText().toString();
        try {
            LlmSettings current = llmSettingsStore.read();
            LlmSettings updated;
            switch (fieldKey) {
                case "llm_base_url":
                    updated = new LlmSettings(value, current.apiKey, current.model, current.systemPrompt);
                    break;
                case "llm_model":
                    updated = new LlmSettings(current.baseUrl, current.apiKey, value, current.systemPrompt);
                    break;
                case "llm_api_key":
                    updated = new LlmSettings(current.baseUrl, value, current.model, current.systemPrompt);
                    break;
                case "llm_system_prompt":
                    updated = new LlmSettings(current.baseUrl, current.apiKey, current.model, value);
                    break;
                default:
                    return;
            }
            llmSettingsStore.save(updated);
        } catch (RuntimeException e) {
            // Ignore save errors — worst case the value is lost
        }
    }
}
