package com.shipalert;

import android.os.Bundle;
import android.text.InputType;

import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.EditTextPreference;
import androidx.preference.PreferenceFragmentCompat;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getSupportActionBar() != null) getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        getSupportFragmentManager().beginTransaction()
                .replace(android.R.id.content, new SettingsFragment())
                .commit();
    }

    @Override
    public boolean onSupportNavigateUp() {
        finish();
        return true;
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.prefs, rootKey);
            String[] ints = {"interval_sec", "confirm_frames", "cooldown_min", "stay_min", "stay_repeat_min", "notext_min"};
            for (String k : ints) {
                EditTextPreference p = findPreference(k);
                if (p != null) p.setOnBindEditTextListener(et ->
                        et.setInputType(InputType.TYPE_CLASS_NUMBER));
            }
            EditTextPreference diff = findPreference("diff_threshold");
            if (diff != null) diff.setOnBindEditTextListener(et ->
                    et.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));
            EditTextPreference rules = findPreference("rules");
            if (rules != null) rules.setOnBindEditTextListener(et -> {
                et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
                et.setSingleLine(false);
                et.setMinLines(6);
                et.setTextSize(13);
            });
        }
    }
}
