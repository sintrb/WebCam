package com.sintrb.webcam;

import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.switchmaterial.SwitchMaterial;

public class SettingsActivity extends AppCompatActivity {
    private EditText etPort;
    private Spinner spinnerSize;
    private Spinner spinnerRotation;
    private Spinner spinnerWatermarkPosition;
    private SwitchMaterial switchAutoFocus;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        etPort = findViewById(R.id.etPort);
        spinnerSize = findViewById(R.id.spinnerImageSize);
        spinnerRotation = findViewById(R.id.spinnerRotation);
        spinnerWatermarkPosition = findViewById(R.id.spinnerWatermarkPosition);
        switchAutoFocus = findViewById(R.id.switchAutoFocus);
        MaterialToolbar toolbar = findViewById(R.id.toolbarSettings);
        Button btnSave = findViewById(R.id.btnSave);
        toolbar.setNavigationOnClickListener(v -> finish());
        btnSave.setOnClickListener(v -> saveSettings());
        setupSpinners();
        fillCurrentValues();
    }

    private void setupSpinners() {
        ArrayAdapter<String> sizeAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, AppSettings.IMAGE_SIZE_OPTIONS);
        sizeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerSize.setAdapter(sizeAdapter);

        String[] rotations = new String[] {"0°", "90°", "180°", "270°"};
        ArrayAdapter<String> rotationAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, rotations);
        rotationAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerRotation.setAdapter(rotationAdapter);

        ArrayAdapter<String> watermarkAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, AppSettings.WATERMARK_POSITION_LABELS);
        watermarkAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerWatermarkPosition.setAdapter(watermarkAdapter);
    }

    private void fillCurrentValues() {
        etPort.setText(String.valueOf(AppSettings.getHttpPort(this)));
        String size = AppSettings.getDefaultImageSize(this);
        for (int i = 0; i < AppSettings.IMAGE_SIZE_OPTIONS.length; i++) {
            if (AppSettings.IMAGE_SIZE_OPTIONS[i].equals(size)) {
                spinnerSize.setSelection(i);
                break;
            }
        }
        switchAutoFocus.setChecked(AppSettings.isAutoFocusEnabled(this));
        int rotation = AppSettings.getDefaultRotation(this);
        for (int i = 0; i < AppSettings.ROTATION_OPTIONS.length; i++) {
            if (AppSettings.ROTATION_OPTIONS[i] == rotation) {
                spinnerRotation.setSelection(i);
                break;
            }
        }
        String watermarkPosition = AppSettings.getWatermarkPosition(this);
        for (int i = 0; i < AppSettings.WATERMARK_POSITION_VALUES.length; i++) {
            if (AppSettings.WATERMARK_POSITION_VALUES[i].equals(watermarkPosition)) {
                spinnerWatermarkPosition.setSelection(i);
                break;
            }
        }
    }

    private void saveSettings() {
        String portText = etPort.getText().toString().trim();
        if (TextUtils.isEmpty(portText)) {
            etPort.setError("请输入端口");
            return;
        }
        int port;
        try {
            port = Integer.parseInt(portText);
        } catch (Exception e) {
            etPort.setError("端口格式不正确");
            return;
        }
        port = AppSettings.sanitizePort(port);
        AppSettings.setHttpPort(this, port);
        AppSettings.setDefaultImageSize(this, String.valueOf(spinnerSize.getSelectedItem()));
        AppSettings.setDefaultRotation(this, AppSettings.ROTATION_OPTIONS[spinnerRotation.getSelectedItemPosition()]);
        AppSettings.setWatermarkPosition(this, AppSettings.WATERMARK_POSITION_VALUES[spinnerWatermarkPosition.getSelectedItemPosition()]);
        AppSettings.setAutoFocusEnabled(this, switchAutoFocus.isChecked());
        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        finish();
    }
}
