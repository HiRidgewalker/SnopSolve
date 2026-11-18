package com.jone.snapsolve;

import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        EditText baseUrl = findViewById(R.id.base_url);
        EditText apiKey = findViewById(R.id.api_key);
        EditText model = findViewById(R.id.model);
        EditText prompt = findViewById(R.id.prompt);
        EditText blur = findViewById(R.id.blur_threshold);
        Button save = findViewById(R.id.save);
        Button resetPrompt = findViewById(R.id.reset_prompt);

        baseUrl.setText(Prefs.baseUrl(this));
        apiKey.setText(Prefs.apiKey(this));
        model.setText(Prefs.model(this));
        prompt.setText(Prefs.prompt(this));
        blur.setText(String.valueOf(Prefs.blurThreshold(this)));

        resetPrompt.setOnClickListener(v -> prompt.setText(Prefs.DEFAULT_PROMPT));

        save.setOnClickListener(v -> {
            String b = baseUrl.getText().toString().trim();
            if (b.isEmpty()) {
                Toast.makeText(this, R.string.base_url_required, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!b.startsWith("http://") && !b.startsWith("https://")) {
                Toast.makeText(this, R.string.base_url_scheme, Toast.LENGTH_SHORT).show();
                return;
            }
            Prefs.save(this,
                    b,
                    apiKey.getText().toString(),
                    model.getText().toString(),
                    prompt.getText().toString());

            try {
                Prefs.setBlurThreshold(this, Integer.parseInt(blur.getText().toString().trim()));
            } catch (NumberFormatException ignored) {
                Prefs.setBlurThreshold(this, Sharpness.DEFAULT_THRESHOLD);
            }
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show();
            finish();
        });
    }
}
