package com.sintrb.webcam;

import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.widget.ImageButton;

import androidx.appcompat.app.AppCompatActivity;

public class PhotoViewerActivity extends AppCompatActivity {
    public static final String EXTRA_PATH = "path";

    private ZoomImageView imageView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().getDecorView().setSystemUiVisibility(android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | android.view.View.SYSTEM_UI_FLAG_FULLSCREEN | android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        setContentView(R.layout.activity_photo_viewer);
        imageView = findViewById(R.id.zoomImageView);
        ImageButton btnClose = findViewById(R.id.btnClose);
        btnClose.setOnClickListener(v -> finish());
        loadImage();
    }

    private void loadImage() {
        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null) {
            finish();
            return;
        }
        DisplayMetrics dm = getResources().getDisplayMetrics();
        Bitmap bitmap = ImageUtils.decodeSampledFile(path, dm.widthPixels, dm.heightPixels);
        imageView.setImageBitmap(bitmap);
    }

    @Override
    protected void onDestroy() {
        if (imageView.getDrawable() instanceof BitmapDrawable) {
            Bitmap bitmap = ((BitmapDrawable) imageView.getDrawable()).getBitmap();
            if (bitmap != null && !bitmap.isRecycled()) {
                bitmap.recycle();
            }
        }
        super.onDestroy();
    }
}
