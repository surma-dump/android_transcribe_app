package dev.surma.parakeeb;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.security.crypto.EncryptedSharedPreferences;
import androidx.security.crypto.MasterKey;

final class LlmSettingsStore {
    private static final String PREFS_NAME = "llm_settings";
    private static final String SECURE_PREFS_NAME = "llm_secure_settings";
    private static final String KEY_BASE_URL = "base_url";
    private static final String KEY_MODEL = "model";
    private static final String KEY_SYSTEM_PROMPT = "system_prompt";
    private static final String KEY_API_KEY = "api_key";

    private final Context appContext;

    LlmSettingsStore(Context context) {
        this.appContext = context.getApplicationContext();
    }

    LlmSettings read() {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences securePrefs = getSecurePreferences();
        return new LlmSettings(
                prefs.getString(KEY_BASE_URL, ""),
                securePrefs.getString(KEY_API_KEY, ""),
                prefs.getString(KEY_MODEL, ""),
                prefs.getString(KEY_SYSTEM_PROMPT, ""));
    }

    void save(LlmSettings settings) {
        SharedPreferences prefs = appContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences securePrefs = getSecurePreferences();

        prefs.edit()
                .putString(KEY_BASE_URL, settings.baseUrl)
                .putString(KEY_MODEL, settings.model)
                .putString(KEY_SYSTEM_PROMPT, settings.systemPrompt)
                .commit();

        securePrefs.edit()
                .putString(KEY_API_KEY, settings.apiKey)
                .commit();
    }

    private SharedPreferences getSecurePreferences() {
        try {
            MasterKey masterKey = new MasterKey.Builder(appContext)
                    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                    .build();
            return EncryptedSharedPreferences.create(
                    appContext,
                    SECURE_PREFS_NAME,
                    masterKey,
                    EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                    EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to open encrypted LLM settings", e);
        }
    }
}
