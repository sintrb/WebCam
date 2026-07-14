package com.sintrb.webcam;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Surface;
import android.view.MotionEvent;
import android.view.TextureView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.google.android.material.appbar.MaterialToolbar;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CAMERA_PERMISSION = 1001;
    private static final long DIM_DELAY_MS = 60_000L;
    private static final long PREVIEW_IDLE_CLOSE_MS = 30_000L;
    private static final float DIM_BRIGHTNESS = 0.01f;

    private AutoFitTextureView textureView;
    private TextView tvPreviewHint;
    private TextView tvServerInfo;
    private Button btnPreview;
    private Button btnStop;
    private Button btnCapture;
    private Button btnClearRecords;
    private MaterialToolbar toolbar;

    private final List<PhotoRecord> photoRecords = new ArrayList<>();
    private final Handler uiHandler = new Handler();
    private PhotoRecordAdapter adapter;

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private CameraManager cameraManager;
    private String cameraId;
    private Size jpegSize;
    private Size previewSize;
    private ImageReader imageReader;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewRequestBuilder;
    private boolean previewRequested = false;
    private boolean previewRunning = false;
    private boolean pendingStartPreview = false;
    private boolean flashSupported = false;
    private Surface previewSurface;

    private final Object cameraLock = new Object();
    private final AtomicReference<CaptureTask> currentCaptureTask = new AtomicReference<>();

    private SimpleHttpCameraServer httpServer;
    private File photoDir;
    private File thumbDir;
    private int currentServerPort;
    private boolean dimmed = false;
    private boolean httpPreviewKeepAlive = false;


    private final Runnable previewAutoStopRunnable = () -> {
        previewRequested = false;
        httpPreviewKeepAlive = false;
        stopPreviewInternal();
    };

    private final Runnable dimScreenRunnable = () -> {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = DIM_BRIGHTNESS;
        getWindow().setAttributes(lp);
        dimmed = true;
    };

    private final TextureView.SurfaceTextureListener surfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            if (previewRequested) {
                startPreviewInternal();
            }
        }

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture surface, int width, int height) {
        }

        @Override
        public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture surface) {
            stopPreviewInternal();
            return true;
        }

        @Override
        public void onSurfaceTextureUpdated(@NonNull SurfaceTexture surface) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        setContentView(R.layout.activity_main);
        bindViews();
        setupRecyclerView();
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        photoDir = new File(getExternalFilesDir(null), "captures");
        thumbDir = new File(photoDir, ".thumbs");
        if (!photoDir.exists()) photoDir.mkdirs();
        if (!thumbDir.exists()) thumbDir.mkdirs();
        loadExistingRecords();
        startCameraThread();
        ensureCameraConfigured();
        setupButtons();
        textureView.setSurfaceTextureListener(surfaceTextureListener);
        refreshSettingsAndServer(true);
        updatePreviewUi();
        resetInactivityDimming();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshSettingsAndServer(false);
        if (previewRequested && textureView.isAvailable()) {
            startPreviewInternal();
        }
        resetInactivityDimming();
    }

    @Override
    protected void onPause() {
        super.onPause();
        uiHandler.removeCallbacks(dimScreenRunnable);
        uiHandler.removeCallbacks(previewAutoStopRunnable);
        restoreBrightness();
        if (!isFinishing()) {
            stopPreviewInternal();
        }
    }

    @Override
    protected void onDestroy() {
        if (httpServer != null) {
            httpServer.stop();
        }
        closeCamera();
        stopCameraThread();
        super.onDestroy();
    }

    private void bindViews() {
        textureView = findViewById(R.id.textureView);
        tvPreviewHint = findViewById(R.id.tvPreviewHint);
        tvServerInfo = findViewById(R.id.tvServerInfo);
        btnPreview = findViewById(R.id.btnPreview);
        btnStop = findViewById(R.id.btnStop);
        btnCapture = findViewById(R.id.btnCapture);
        btnClearRecords = findViewById(R.id.btnClearRecords);
        toolbar = findViewById(R.id.toolbar);
    }

    private void setupRecyclerView() {
        RecyclerView recyclerView = findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        adapter = new PhotoRecordAdapter();
        adapter.setOnRecordClickListener(this::openPhotoViewer);
        recyclerView.setAdapter(adapter);
    }

    private void setupButtons() {
        btnPreview.setOnClickListener(v -> {
            if (!hasCameraPermission()) {
                requestCameraPermission();
                return;
            }
            previewRequested = true;
            httpPreviewKeepAlive = false;
            startPreviewInternal();
            resetPreviewAutoStop();
        });
        btnStop.setOnClickListener(v -> {
            previewRequested = false;
            httpPreviewKeepAlive = false;
            stopPreviewInternal();
        });
        btnCapture.setOnClickListener(v -> {
            if (!hasCameraPermission()) {
                requestCameraPermission();
                return;
            }
            new Thread(() -> {
                try {
                    SimpleHttpCameraServer.RequestOptions options = buildAppliedOptions(
                            new SimpleHttpCameraServer.RequestOptions(0, 0, false, 0, null)
                    );
                    resetPreviewAutoStop();
                    File file = capturePhoto("手动拍照", true, options, false);
                    runOnUiThread(() -> toast("拍照成功: " + file.getName()));
                } catch (Exception e) {
                    runOnUiThread(() -> toast("拍照失败: " + e.getMessage()));
                }
            }, "manual-capture").start();
        });
        btnClearRecords.setOnClickListener(v -> confirmClearRecords());
        toolbar.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == R.id.action_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });
    }

    private void refreshSettingsAndServer(boolean forceRestart) {
        int port = AppSettings.getHttpPort(this);
        if (forceRestart || currentServerPort != port || httpServer == null) {
            restartHttpServer(port);
        } else {
            updateServerInfo();
        }
    }

    private void restartHttpServer(int port) {
        currentServerPort = port;
        if (httpServer != null) {
            httpServer.stop();
        }
        httpServer = new SimpleHttpCameraServer(
                port,
                options -> {
                    httpPreviewKeepAlive = true;
                    resetPreviewAutoStop();
                    return capturePhoto(buildHttpSource(options), false, buildAppliedOptions(options), true);
                },
                this::buildHomePageHtml,
                this::applyRemoteSettingsAndRender,
                this::resolveMediaFile,
                this::resolveBinaryContent
        );
        httpServer.start();
        updateServerInfo();
    }

    private void updateServerInfo() {
        tvServerInfo.setText("HTTP 服务: http://" + SimpleHttpCameraServer.getDeviceIp() + ":" + currentServerPort + "/snapshot.jpg");
    }

    private SimpleHttpCameraServer.RequestOptions buildAppliedOptions(SimpleHttpCameraServer.RequestOptions raw) {
        AppSettings.SizeOption sizeOption = AppSettings.parseSize(AppSettings.getDefaultImageSize(this));
        int width = raw.width > 0 ? raw.width : sizeOption.width;
        int height = raw.height > 0 ? raw.height : sizeOption.height;
        Integer rotation = raw.rotationDegrees != null ? raw.rotationDegrees : AppSettings.getDefaultRotation(this);
        return new SimpleHttpCameraServer.RequestOptions(width, height, raw.useFlash, raw.delayMs, rotation);
    }

    private String buildHttpSource(SimpleHttpCameraServer.RequestOptions options) {
        StringBuilder builder = new StringBuilder("HTTP 抓拍");
        List<String> parts = new ArrayList<>();
        if (options.width > 0 || options.height > 0) {
            parts.add("尺寸=" + options.width + "x" + options.height);
        }
        if (options.useFlash) {
            parts.add("闪光灯=开");
        }
        if (options.delayMs > 0) {
            parts.add("延时=" + options.delayMs + "ms");
        }
        if (options.rotationDegrees != null) {
            parts.add("旋转=" + options.rotationDegrees + "°");
        }
        if (!parts.isEmpty()) {
            builder.append(" (").append(joinParts(parts)).append(")");
        }
        return builder.toString();
    }

    private String joinParts(List<String> parts) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(parts.get(i));
        }
        return sb.toString();
    }

    private void loadExistingRecords() {
        photoRecords.clear();
        File[] files = photoDir.listFiles((dir, name) -> name.endsWith(".jpg") && !name.endsWith(".thumb.jpg"));
        if (files == null) {
            adapter.setItems(photoRecords);
            return;
        }
        Arrays.sort(files, (o1, o2) -> Long.compare(o2.lastModified(), o1.lastModified()));
        for (File file : files) {
            int[] dims = ImageUtils.readDimensions(file.getAbsolutePath());
            photoRecords.add(new PhotoRecord("历史记录", file.lastModified(), file, ensureThumbnail(file), dims[0], dims[1]));
        }
        adapter.setItems(photoRecords);
        updatePreviewUi();
    }

    private File ensureThumbnail(File photoFile) {
        File thumbFile = new File(thumbDir, photoFile.getName().replace(".jpg", ".thumb.jpg"));
        if (thumbFile.exists()) {
            return thumbFile;
        }
        try {
            Bitmap bitmap = ImageUtils.decodeSampledFile(photoFile.getAbsolutePath(), 240, 240);
            if (bitmap != null) {
                ImageUtils.saveJpeg(bitmap, thumbFile, 80);
                bitmap.recycle();
            }
        } catch (Exception ignored) {
        }
        return thumbFile;
    }

    private boolean hasCameraPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestCameraPermission() {
        ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                ensureCameraConfigured();
                toast("摄像头权限已授予");
            } else {
                toast("需要摄像头权限才能使用预览和抓拍");
            }
        }
    }

    private void startCameraThread() {
        cameraThread = new HandlerThread("camera-thread");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());
    }

    private void stopCameraThread() {
        if (cameraThread != null) {
            cameraThread.quitSafely();
            try {
                cameraThread.join();
            } catch (InterruptedException ignored) {
            }
            cameraThread = null;
            cameraHandler = null;
        }
    }

    private void ensureCameraConfigured() {
        if (!hasCameraPermission()) {
            return;
        }
        try {
            for (String id : cameraManager.getCameraIdList()) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(id);
                Integer lensFacing = characteristics.get(CameraCharacteristics.LENS_FACING);
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map == null) continue;
                if (lensFacing != null && lensFacing == CameraCharacteristics.LENS_FACING_BACK) {
                    applyCameraCharacteristics(id, characteristics, map);
                    return;
                }
            }
            String[] ids = cameraManager.getCameraIdList();
            if (ids.length > 0) {
                CameraCharacteristics characteristics = cameraManager.getCameraCharacteristics(ids[0]);
                StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                if (map != null) applyCameraCharacteristics(ids[0], characteristics, map);
            }
        } catch (Exception e) {
            toast("初始化摄像头失败: " + e.getMessage());
        }
    }

    private void applyCameraCharacteristics(String id, CameraCharacteristics characteristics, StreamConfigurationMap map) {
        cameraId = id;
        Boolean flashInfo = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        flashSupported = flashInfo != null && flashInfo;
        jpegSize = chooseLargest(map.getOutputSizes(ImageFormat.JPEG));
        previewSize = choosePortraitFriendlyPreviewSize(map.getOutputSizes(SurfaceTexture.class));
        if (previewSize != null) {
            textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
        }
        createImageReader();
    }

    private void createImageReader() {
        if (jpegSize == null) jpegSize = new Size(1920, 1080);
        if (imageReader != null) imageReader.close();
        imageReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
        imageReader.setOnImageAvailableListener(reader -> {
            Image image = null;
            try {
                image = reader.acquireNextImage();
                if (image == null) return;
                CaptureTask task = currentCaptureTask.get();
                if (task == null) return;
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                try (FileOutputStream fos = new FileOutputStream(task.file)) {
                    fos.write(bytes);
                }
                task.resultFile = task.file;
            } catch (Exception e) {
                CaptureTask task = currentCaptureTask.get();
                if (task != null) task.error = e;
            } finally {
                if (image != null) image.close();
                CaptureTask task = currentCaptureTask.get();
                if (task != null && task.latch != null) task.latch.countDown();
            }
        }, cameraHandler);
    }

    private Size chooseLargest(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(1920, 1080);
        return Collections.max(Arrays.asList(sizes), Comparator.comparingLong(size -> (long) size.getWidth() * size.getHeight()));
    }

    private Size choosePortraitFriendlyPreviewSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return new Size(720, 1280);
        List<Size> candidates = new ArrayList<>();
        for (Size size : sizes) {
            int longEdge = Math.max(size.getWidth(), size.getHeight());
            int shortEdge = Math.min(size.getWidth(), size.getHeight());
            if (longEdge >= 1280 && shortEdge >= 720) {
                candidates.add(size);
            }
        }
        if (candidates.isEmpty()) {
            candidates.addAll(Arrays.asList(sizes));
        }
        return Collections.min(candidates, Comparator.comparingLong(size -> Math.abs((long) Math.max(size.getWidth(), size.getHeight()) - 1280)));
    }

    private void startPreviewInternal() {
        if (!hasCameraPermission()) return;
        if (cameraId == null) ensureCameraConfigured();
        if (cameraId == null) {
            toast("没有可用摄像头");
            return;
        }
        previewRequested = true;
        if (!textureView.isAvailable()) {
            pendingStartPreview = true;
            updatePreviewUi();
            return;
        }
        cameraHandler.post(() -> openCamera(true));
    }

    private void stopPreviewInternal() {
        pendingStartPreview = false;
        uiHandler.removeCallbacks(previewAutoStopRunnable);
        cameraHandler.post(() -> {
            if (previewRunning) {
                closeSession();
                closeCameraDevice();
                previewRunning = false;
                runOnUiThread(this::updatePreviewUi);
            }
        });
        updatePreviewUi();
    }

    @SuppressLint("MissingPermission")
    private void openCamera(boolean forPreview) {
        synchronized (cameraLock) {
            if (cameraDevice != null) {
                if (forPreview && !previewRunning) createPreviewSession();
                return;
            }
            try {
                pendingStartPreview = forPreview;
                cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                    @Override
                    public void onOpened(@NonNull CameraDevice camera) {
                        cameraDevice = camera;
                        if (pendingStartPreview) createPreviewSession();
                    }

                    @Override
                    public void onDisconnected(@NonNull CameraDevice camera) {
                        camera.close();
                        cameraDevice = null;
                        previewRunning = false;
                        runOnUiThread(MainActivity.this::updatePreviewUi);
                    }

                    @Override
                    public void onError(@NonNull CameraDevice camera, int error) {
                        camera.close();
                        cameraDevice = null;
                        previewRunning = false;
                        runOnUiThread(() -> {
                            updatePreviewUi();
                            toast("打开摄像头失败: " + error);
                        });
                    }
                }, cameraHandler);
            } catch (CameraAccessException e) {
                runOnUiThread(() -> toast("打开摄像头失败: " + e.getMessage()));
            }
        }
    }

    private void createPreviewSession() {
        try {
            if (cameraDevice == null || !textureView.isAvailable()) return;
            SurfaceTexture surfaceTexture = textureView.getSurfaceTexture();
            if (surfaceTexture == null) return;
            surfaceTexture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            previewSurface = new Surface(surfaceTexture);
            previewRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewRequestBuilder.addTarget(previewSurface);
            previewRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    try {
                        session.setRepeatingRequest(previewRequestBuilder.build(), null, cameraHandler);
                        previewRunning = true;
                        pendingStartPreview = false;
                        resetPreviewAutoStop();
                        runOnUiThread(MainActivity.this::updatePreviewUi);
                    } catch (CameraAccessException e) {
                        runOnUiThread(() -> toast("启动预览失败: " + e.getMessage()));
                    }
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    runOnUiThread(() -> toast("配置预览失败"));
                }
            }, cameraHandler);
        } catch (Exception e) {
            runOnUiThread(() -> toast("创建预览失败: " + e.getMessage()));
        }
    }

    private File capturePhoto(String source, boolean allowKeepPreview, SimpleHttpCameraServer.RequestOptions options, boolean requireWarmPreview) throws Exception {
        if (!hasCameraPermission()) throw new IllegalStateException("没有摄像头权限");
        if (cameraId == null || imageReader == null) ensureCameraConfigured();
        if (cameraId == null || imageReader == null) throw new IllegalStateException("摄像头未初始化");
        if (requireWarmPreview) {
            previewRequested = true;
            ensurePreviewSessionReady();
            resetPreviewAutoStop();
            Thread.sleep(350);
        }
        if (options.delayMs > 0) Thread.sleep(options.delayMs);

        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch captureLatch = new CountDownLatch(1);
        AtomicReference<Exception> openError = new AtomicReference<>();
        File outFile = createOutputFile(source);
        CaptureTask task = new CaptureTask(source, outFile, captureLatch, options);
        currentCaptureTask.set(task);

        cameraHandler.post(() -> {
            try {
                if (cameraDevice == null) {
                    pendingStartPreview = false;
                    cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                        @Override
                        public void onOpened(@NonNull CameraDevice camera) {
                            cameraDevice = camera;
                            openLatch.countDown();
                        }

                        @Override
                        public void onDisconnected(@NonNull CameraDevice camera) {
                            camera.close();
                            cameraDevice = null;
                            openError.set(new IOException("摄像头已断开"));
                            openLatch.countDown();
                        }

                        @Override
                        public void onError(@NonNull CameraDevice camera, int error) {
                            camera.close();
                            cameraDevice = null;
                            openError.set(new IOException("打开摄像头失败: " + error));
                            openLatch.countDown();
                        }
                    }, cameraHandler);
                } else {
                    openLatch.countDown();
                }
            } catch (Exception e) {
                openError.set(e);
                openLatch.countDown();
            }
        });

        if (!openLatch.await(5, TimeUnit.SECONDS)) {
            currentCaptureTask.set(null);
            throw new IOException("等待摄像头打开超时");
        }
        if (openError.get() != null) {
            currentCaptureTask.set(null);
            throw openError.get();
        }

        CountDownLatch sessionLatch = new CountDownLatch(1);
        AtomicReference<Exception> sessionError = new AtomicReference<>();
        boolean shouldCloseAfterCapture = !previewRunning && !requireWarmPreview;

        cameraHandler.post(() -> createCaptureSessionForShot(sessionLatch, sessionError, options));
        if (!sessionLatch.await(5, TimeUnit.SECONDS)) {
            currentCaptureTask.set(null);
            throw new IOException("等待拍照会话超时");
        }
        if (sessionError.get() != null) {
            currentCaptureTask.set(null);
            throw sessionError.get();
        }

        if (!captureLatch.await(8, TimeUnit.SECONDS)) {
            currentCaptureTask.set(null);
            throw new IOException("拍照超时");
        }
        if (task.error != null) {
            currentCaptureTask.set(null);
            throw task.error;
        }
        if (task.resultFile == null) {
            currentCaptureTask.set(null);
            throw new IOException("未获取到照片数据");
        }

        File finalFile = postProcessImage(task.resultFile, options);
        File thumbFile = ensureThumbnail(finalFile);

        if (shouldCloseAfterCapture) {
            cameraHandler.post(() -> {
                closeSession();
                closeCameraDevice();
                previewRunning = false;
                runOnUiThread(this::updatePreviewUi);
            });
        } else if (allowKeepPreview && previewRunning) {
            cameraHandler.post(() -> {
                try {
                    if (captureSession != null && previewRequestBuilder != null) {
                        captureSession.setRepeatingRequest(previewRequestBuilder.build(), null, cameraHandler);
                    }
                } catch (Exception ignored) {
                }
            });
        }

        if (requireWarmPreview || allowKeepPreview || previewRunning) {
            resetPreviewAutoStop();
        }
        addPhotoRecord(source, finalFile, thumbFile);
        currentCaptureTask.set(null);
        return finalFile;
    }

    private File postProcessImage(File file, SimpleHttpCameraServer.RequestOptions options) throws IOException {
        Bitmap bitmap = ImageUtils.decodeSampledFile(file.getAbsolutePath(), 3000, 3000);
        if (bitmap == null) throw new IOException("无法解码抓拍图片");
        if (options.rotationDegrees != null) {
            bitmap = ImageUtils.rotateBitmap(bitmap, options.rotationDegrees);
        }
        if (options.hasResize()) {
            bitmap = ImageUtils.resizeCenterCropBitmap(bitmap, options.width, options.height);
        }
        bitmap = drawTimestampWatermark(bitmap);
        ImageUtils.saveJpeg(bitmap, file, 92);
        bitmap.recycle();
        return file;
    }

    private Bitmap drawTimestampWatermark(Bitmap bitmap) {
        String watermarkPosition = AppSettings.getWatermarkPosition(this);
        if ("none".equals(watermarkPosition)) {
            return bitmap;
        }
        String timeText = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date());
        Bitmap mutable = bitmap.copy(Bitmap.Config.ARGB_8888, true);
        if (mutable != bitmap) {
            bitmap.recycle();
        }
        Canvas canvas = new Canvas(mutable);
        Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.WHITE);
        textPaint.setTextSize(Math.max(26f, mutable.getWidth() / 28f));
        Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(0x80000000);
        Rect bounds = new Rect();
        textPaint.getTextBounds(timeText, 0, timeText.length(), bounds);
        int padding = (int) (textPaint.getTextSize() * 0.35f);
        float margin = 16f;
        float boxWidth = bounds.width() + padding * 2f;
        float boxHeight = bounds.height() + padding * 2f;
        float left = margin;
        float top = margin;
        float right = left + boxWidth;
        float bottom = top + boxHeight;
        switch (watermarkPosition) {
            case "left_bottom":
                left = margin;
                top = mutable.getHeight() - boxHeight - margin;
                break;
            case "right_top":
                left = mutable.getWidth() - boxWidth - margin;
                top = margin;
                break;
            case "right_bottom":
                left = mutable.getWidth() - boxWidth - margin;
                top = mutable.getHeight() - boxHeight - margin;
                break;
            case "left_top":
            default:
                left = margin;
                top = margin;
                break;
        }
        right = left + boxWidth;
        bottom = top + boxHeight;
        canvas.drawRoundRect(left, top, right, bottom, 12f, 12f, bgPaint);
        float textX = left + padding;
        float textY = top + padding + bounds.height();
        canvas.drawText(timeText, textX, textY, textPaint);
        return mutable;
    }

    private void createCaptureSessionForShot(CountDownLatch sessionLatch, AtomicReference<Exception> sessionError,
                                             SimpleHttpCameraServer.RequestOptions options) {
        try {
            if (cameraDevice == null) {
                sessionError.set(new IOException("摄像头未打开"));
                sessionLatch.countDown();
                return;
            }
            if (previewRunning && captureSession != null) {
                runAutoFocusIfNeeded(sessionLatch, sessionError, options, captureSession);
                return;
            }
            closeSession();
            cameraDevice.createCaptureSession(Collections.singletonList(imageReader.getSurface()), new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession session) {
                    captureSession = session;
                    previewRunning = false;
                    runAutoFocusIfNeeded(sessionLatch, sessionError, options, session);
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession session) {
                    sessionError.set(new IOException("创建拍照会话失败"));
                    sessionLatch.countDown();
                }
            }, cameraHandler);
        } catch (Exception e) {
            sessionError.set(e);
            sessionLatch.countDown();
        }
    }

    private void runAutoFocusIfNeeded(CountDownLatch sessionLatch, AtomicReference<Exception> sessionError,
                                      SimpleHttpCameraServer.RequestOptions options, CameraCaptureSession session) {
        if (!AppSettings.isAutoFocusEnabled(this)) {
            captureStill(sessionLatch, sessionError, session, options);
            return;
        }
        try {
            CountDownLatch focusLatch = new CountDownLatch(1);
            AtomicReference<Exception> focusError = new AtomicReference<>();
            CaptureRequest.Builder focusBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            if (previewRunning && previewSurface != null) {
                focusBuilder.addTarget(previewSurface);
            } else {
                focusBuilder.addTarget(imageReader.getSurface());
            }
            focusBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            focusBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            focusBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            session.capture(focusBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    focusLatch.countDown();
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull android.hardware.camera2.CaptureFailure failure) {
                    focusError.set(new IOException("自动对焦失败"));
                    focusLatch.countDown();
                }
            }, cameraHandler);

            cameraHandler.post(() -> {
                try {
                    focusLatch.await(1200, TimeUnit.MILLISECONDS);
                    if (focusError.get() != null) {
                        // 对焦失败时继续拍，避免完全阻塞
                    }
                    Thread.sleep(250);
                } catch (Exception ignored) {
                }
                captureStill(sessionLatch, sessionError, session, options);
            });
        } catch (Exception e) {
            captureStill(sessionLatch, sessionError, session, options);
        }
    }

    private void captureStill(CountDownLatch sessionLatch, AtomicReference<Exception> sessionError,
                              CameraCaptureSession session, SimpleHttpCameraServer.RequestOptions options) {
        try {
            CaptureRequest.Builder builder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.addTarget(imageReader.getSurface());
            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.JPEG_ORIENTATION, 0);
            if (options.useFlash && flashSupported) {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE);
            } else {
                builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
            }
            session.capture(builder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session,
                                               @NonNull CaptureRequest request,
                                               @NonNull TotalCaptureResult result) {
                    sessionLatch.countDown();
                }

                @Override
                public void onCaptureFailed(@NonNull CameraCaptureSession session,
                                            @NonNull CaptureRequest request,
                                            @NonNull android.hardware.camera2.CaptureFailure failure) {
                    sessionError.set(new IOException("拍照失败"));
                    sessionLatch.countDown();
                }
            }, cameraHandler);
        } catch (Exception e) {
            sessionError.set(e);
            sessionLatch.countDown();
        }
    }

    private File createOutputFile(String source) {
        String prefix = source.contains("HTTP") ? "http" : "manual";
        String name = prefix + "_" + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.getDefault()).format(new Date()) + ".jpg";
        return new File(photoDir, name);
    }

    private void addPhotoRecord(String source, File file, File thumbFile) {
        int[] dims = ImageUtils.readDimensions(file.getAbsolutePath());
        PhotoRecord record = new PhotoRecord(source, System.currentTimeMillis(), file, thumbFile, dims[0], dims[1]);
        runOnUiThread(() -> {
            photoRecords.add(0, record);
            adapter.setItems(photoRecords);
            updatePreviewUi();
        });
    }

    private void openPhotoViewer(PhotoRecord record) {
        Intent intent = new Intent(this, PhotoViewerActivity.class);
        intent.putExtra(PhotoViewerActivity.EXTRA_PATH, record.file.getAbsolutePath());
        startActivity(intent);
    }

    private void confirmClearRecords() {
        new AlertDialog.Builder(this)
                .setTitle("清空记录")
                .setMessage("确定要删除全部照片记录吗？此操作不可恢复。")
                .setPositiveButton("确定", (dialog, which) -> clearAllRecords())
                .setNegativeButton("取消", null)
                .show();
    }

    private void clearAllRecords() {
        File[] files = photoDir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    File[] subFiles = file.listFiles();
                    if (subFiles != null) {
                        for (File sub : subFiles) sub.delete();
                    }
                }
                file.delete();
            }
        }
        photoDir.mkdirs();
        thumbDir.mkdirs();
        photoRecords.clear();
        adapter.setItems(photoRecords);
        updatePreviewUi();
        toast("记录已清空");
    }

    private void updatePreviewUi() {
        boolean active = previewRunning || pendingStartPreview || previewRequested || httpPreviewKeepAlive;
        tvPreviewHint.setVisibility(active ? View.GONE : View.VISIBLE);
        btnPreview.setEnabled(!active);
        btnStop.setEnabled(active);
        btnCapture.setEnabled(hasCameraPermission());
        btnClearRecords.setEnabled(!photoRecords.isEmpty());
    }

    private void closeSession() {
        if (captureSession != null) {
            try {
                captureSession.stopRepeating();
            } catch (Exception ignored) {
            }
            try {
                captureSession.abortCaptures();
            } catch (Exception ignored) {
            }
            captureSession.close();
            captureSession = null;
        }
    }

    private void closeCameraDevice() {
        if (cameraDevice != null) {
            cameraDevice.close();
            cameraDevice = null;
        }
    }

    private void closeCamera() {
        cameraHandler.post(() -> {
            closeSession();
            closeCameraDevice();
            if (imageReader != null) {
                imageReader.close();
                imageReader = null;
            }
        });
    }


    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        resetInactivityDimming();
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onUserInteraction() {
        super.onUserInteraction();
        resetInactivityDimming();
        if (previewRunning || previewRequested) {
            resetPreviewAutoStop();
        }
    }

    private void resetInactivityDimming() {
        restoreBrightness();
        uiHandler.removeCallbacks(dimScreenRunnable);
        uiHandler.removeCallbacks(previewAutoStopRunnable);
        uiHandler.postDelayed(dimScreenRunnable, DIM_DELAY_MS);
    }

    private void restoreBrightness() {
        if (!dimmed && getWindow().getAttributes().screenBrightness == WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
            return;
        }
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
        dimmed = false;
    }

    private void resetPreviewAutoStop() {
        uiHandler.removeCallbacks(previewAutoStopRunnable);
        if (previewRunning || previewRequested || httpPreviewKeepAlive) {
            uiHandler.postDelayed(previewAutoStopRunnable, PREVIEW_IDLE_CLOSE_MS);
        }
    }

    private void ensurePreviewSessionReady() throws Exception {
        if (previewRunning && captureSession != null) {
            return;
        }
        if (!textureView.isAvailable()) {
            return;
        }
        CountDownLatch previewLatch = new CountDownLatch(1);
        AtomicReference<Exception> previewError = new AtomicReference<>();
        cameraHandler.post(() -> {
            try {
                pendingStartPreview = true;
                if (cameraDevice == null) {
                    openCamera(true);
                } else {
                    createPreviewSession();
                }
                cameraHandler.postDelayed(() -> {
                    if (previewRunning) {
                        previewLatch.countDown();
                    } else {
                        previewError.set(new IOException("预览启动超时"));
                        previewLatch.countDown();
                    }
                }, 700);
            } catch (Exception e) {
                previewError.set(e);
                previewLatch.countDown();
            }
        });
        previewLatch.await(2, TimeUnit.SECONDS);
        if (previewError.get() != null) {
            throw previewError.get();
        }
    }

    private String buildHomePageHtml() {
        String ip = SimpleHttpCameraServer.getDeviceIp();
        String size = AppSettings.getDefaultImageSize(this);
        int rotation = AppSettings.getDefaultRotation(this);
        boolean autofocus = AppSettings.isAutoFocusEnabled(this);
        String watermarkPosition = AppSettings.getWatermarkPosition(this);
        StringBuilder recordsHtml = new StringBuilder();
        List<PhotoRecord> recentRecords = getRecentPhotoRecords(12);
        if (recentRecords.isEmpty()) {
            recordsHtml.append("<div class='empty'>暂时还没有抓拍记录，先点一次“立即抓拍”试试。</div>");
        } else {
            recordsHtml.append("<div class='gallery'>");
            SimpleDateFormat sdf = new SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault());
            for (PhotoRecord record : recentRecords) {
                String name = escapeHtml(record.file.getName());
                String thumbUrl = "/media?thumb=1&name=" + urlEncode(record.file.getName());
                String photoUrl = "/media?name=" + urlEncode(record.file.getName());
                recordsHtml.append("<a class='shot-card' href='").append(photoUrl).append("' onclick=\"return openViewer(event,'")
                        .append(photoUrl).append("','")
                        .append(name.replace("'", "\\'")).append("','")
                        .append(record.width).append(" × ").append(record.height).append("')\">")
                        .append("<div class='thumb-wrap'><img loading='lazy' src='").append(thumbUrl).append("' alt='").append(name).append("'></div>")
                        .append("<div class='shot-meta'>")
                        .append("<div class='shot-name'>").append(name).append("</div>")
                        .append("<div class='shot-sub'>").append(record.width).append(" × ").append(record.height).append("</div>")
                        .append("<div class='shot-time'>").append(escapeHtml(sdf.format(new Date(record.timestamp)))).append("</div>")
                        .append("</div></a>");
            }
            recordsHtml.append("</div>");
        }

        return "<!doctype html><html><head><meta charset='utf-8'>"
                + "<meta name='viewport' content='width=device-width,initial-scale=1,viewport-fit=cover'>"
                + "<link rel='icon' type='image/png' href='/favicon.png'>"
                + "<link rel='shortcut icon' href='/favicon.png'>"
                + "<link rel='apple-touch-icon' href='/favicon.png'>"
                + "<title>WebCam</title>"
                + "<style>"
                + ":root{color-scheme:light;--bg:#f3f6fb;--card:#ffffff;--text:#1f2937;--muted:#667085;--line:#e6eaf2;--primary:#4f46e5;--primary2:#7c3aed;--shadow:0 12px 32px rgba(46,61,89,.10)}"
                + "*{box-sizing:border-box}body{margin:0;font-family:-apple-system,BlinkMacSystemFont,'Segoe UI',Roboto,'PingFang SC','Hiragino Sans GB','Microsoft YaHei',sans-serif;background:linear-gradient(180deg,#eef3ff 0%,#f7f9fc 45%,#f3f6fb 100%);color:var(--text)}"
                + "a{text-decoration:none;color:inherit}img{display:block;max-width:100%}"
                + ".page{max-width:1220px;margin:0 auto;padding:16px}.hero,.card{background:rgba(255,255,255,.94);backdrop-filter:blur(12px);border:1px solid rgba(255,255,255,.8);border-radius:24px;box-shadow:var(--shadow)}"
                + ".hero{padding:20px 20px 18px;margin-bottom:16px}.hero-top{display:flex;gap:14px;align-items:flex-start;justify-content:space-between;flex-wrap:wrap}.title{font-size:28px;font-weight:800;letter-spacing:.2px;margin:0}.subtitle{margin:8px 0 0;color:var(--muted);line-height:1.6}"
                + ".action-row{display:flex;gap:12px;flex-wrap:wrap;margin-top:16px}.btn{display:inline-flex;align-items:center;justify-content:center;padding:12px 16px;border-radius:14px;font-weight:700;border:1px solid transparent}"
                + ".btn-primary{background:linear-gradient(135deg,var(--primary),var(--primary2));color:#fff}.btn-secondary{background:#fff;border-color:var(--line);color:var(--text)}"
                + ".chips{display:flex;gap:10px;flex-wrap:wrap;margin-top:14px}.chip{padding:8px 12px;border-radius:999px;background:#f8faff;border:1px solid var(--line);color:#475467;font-size:13px}"
                + ".layout{display:grid;grid-template-columns:1.2fr .8fr;gap:16px}.card{padding:18px}.section-title{font-size:18px;font-weight:800;margin:0 0 14px}.section-desc{color:var(--muted);margin:0 0 14px;line-height:1.6}"
                + ".gallery{display:grid;grid-template-columns:repeat(auto-fill,minmax(148px,1fr));gap:14px}.shot-card{display:block;background:#fff;border:1px solid var(--line);border-radius:18px;overflow:hidden;transition:transform .18s ease,box-shadow .18s ease,border-color .18s ease}"
                + ".shot-card:hover{transform:translateY(-2px);box-shadow:0 10px 24px rgba(31,41,55,.12);border-color:#d5dcf0}.thumb-wrap{aspect-ratio:3/4;background:#eef2f8;overflow:hidden}.thumb-wrap img{width:100%;height:100%;object-fit:cover}"
                + ".shot-meta{padding:10px 11px 12px}.shot-name{font-size:12px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.shot-sub,.shot-time{font-size:12px;color:var(--muted);margin-top:4px}"
                + ".info-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.info-item{padding:14px;border-radius:16px;background:#f8faff;border:1px solid var(--line)}.label{font-size:12px;color:var(--muted);margin-bottom:6px}.value{font-size:16px;font-weight:800}"
                + "form{display:grid;gap:12px}.field{display:grid;gap:6px}.field label{font-size:13px;font-weight:700;color:#344054}input,select{width:100%;padding:12px 13px;border-radius:14px;border:1px solid #d7ddea;background:#fff;font-size:14px;color:var(--text)}"
                + ".checkbox{display:flex;gap:10px;align-items:center;padding:4px 2px;color:#344054}.checkbox input{width:auto;transform:scale(1.1)}.submit{margin-top:4px}.empty{padding:26px 18px;border:1px dashed #cfd7e6;border-radius:18px;background:#fbfcfe;color:var(--muted);text-align:center}"
                + ".tips{margin:0;padding-left:18px;color:var(--muted);line-height:1.8}.status{margin-top:10px;min-height:22px;color:var(--muted);font-size:13px}"
                + ".pending-wrap{margin-bottom:14px}.pending-card{display:flex;gap:12px;align-items:center;padding:14px 16px;border-radius:18px;border:1px dashed #c8d2e4;background:linear-gradient(135deg,#f8fbff,#eef4ff)}"
                + ".pending-thumb{width:72px;height:96px;border-radius:14px;background:linear-gradient(135deg,#dbe7ff,#edf3ff);position:relative;overflow:hidden;flex:0 0 auto}.pending-thumb:after{content:'';position:absolute;inset:0;background:linear-gradient(90deg,transparent,rgba(255,255,255,.75),transparent);transform:translateX(-100%);animation:shine 1.3s infinite}"
                + ".pending-title{font-size:15px;font-weight:800}.pending-sub{font-size:12px;color:var(--muted);margin-top:6px}.pending-actions{margin-top:8px}.hidden{display:none!important}@keyframes shine{100%{transform:translateX(100%)}}"
                + ".viewer{position:fixed;inset:0;background:rgba(15,23,42,.88);display:flex;align-items:center;justify-content:center;padding:0;z-index:9999}.viewer.hidden{display:none!important}.viewer-panel{position:relative;width:100%;height:100%;background:#0b1220;overflow:hidden}.viewer-top{position:absolute;left:12px;right:12px;top:12px;display:flex;align-items:flex-start;justify-content:space-between;gap:10px;z-index:3}.viewer-info{min-width:0;max-width:min(62vw,560px);padding:10px 12px;border-radius:16px;background:rgba(15,23,42,.52);backdrop-filter:blur(10px);color:#fff;border:1px solid rgba(255,255,255,.08)}.viewer-title{font-size:14px;font-weight:700;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.viewer-sub{font-size:12px;color:#c7d0e0;margin-top:4px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}.viewer-actions{display:flex;gap:8px;flex-wrap:nowrap;align-items:center;padding:8px;border-radius:18px;background:rgba(15,23,42,.52);backdrop-filter:blur(10px);border:1px solid rgba(255,255,255,.08)}.viewer-btn{width:42px;height:42px;border-radius:14px;border:1px solid rgba(255,255,255,.14);background:rgba(255,255,255,.10);color:#fff;font-weight:800;font-size:16px;display:inline-flex;align-items:center;justify-content:center}.viewer-stage{position:absolute;inset:0;overflow:hidden;background:radial-gradient(circle at center,#243247 0%,#0f172a 72%);touch-action:none}.viewer-canvas{position:absolute;left:50%;top:50%;transform:translate(-50%,-50%)}.viewer-stage img{display:block;max-width:min(100vw,100%);max-height:min(100vh,100%);width:auto;height:auto;transform-origin:center center;user-select:none;-webkit-user-drag:none}.viewer-hint{position:absolute;left:12px;bottom:12px;color:#d0d7e6;font-size:12px;background:rgba(15,23,42,.45);padding:8px 10px;border-radius:999px;z-index:3}.viewer-badge{position:absolute;right:12px;bottom:12px;color:#d0d7e6;font-size:12px;background:rgba(15,23,42,.45);padding:8px 10px;border-radius:999px;z-index:3}"
                + "@media (max-width:920px){.layout{grid-template-columns:1fr}.info-list{grid-template-columns:1fr 1fr}}"
                + "@media (max-width:640px){.page{padding:12px}.hero,.card{border-radius:20px}.title{font-size:22px}.gallery{grid-template-columns:repeat(2,minmax(0,1fr))}.info-list{grid-template-columns:1fr}.action-row .btn{flex:1}.viewer-top{left:10px;right:10px;top:10px}.viewer-info{max-width:calc(100vw - 170px);padding:9px 10px;border-radius:14px}.viewer-title{font-size:13px}.viewer-sub{font-size:11px}.viewer-actions{gap:6px;padding:6px;border-radius:16px}.viewer-btn{width:38px;height:38px;border-radius:12px;font-size:15px}.viewer-hint{left:10px;right:10px;bottom:10px;text-align:center}.viewer-badge{right:10px;bottom:56px}}"
                + "</style>"
                + "<script>"
                + "function setStatus(text){var el=document.getElementById('statusText');if(el)el.textContent=text||'';}"
                + "function refreshPage(){location.reload();}"
                + "var viewerScale=1,viewerTranslateX=0,viewerTranslateY=0,viewerDragging=false,viewerLastX=0,viewerLastY=0,viewerPinching=false,viewerPinchDistance=0,viewerStartScale=1,viewerStartMidX=0,viewerStartMidY=0,viewerStartTranslateX=0,viewerStartTranslateY=0;"
                + "function qs(id){return document.getElementById(id);}"
                + "function clamp(v,min,max){return Math.max(min,Math.min(max,v));}"
                + "function setViewerBadge(){var badge=qs('viewerBadge');if(badge)badge.textContent=Math.round(viewerScale*100)+'%';}"
                + "function applyViewerTransform(){var img=qs('viewerImage');if(img)img.style.transform='translate('+viewerTranslateX+'px,'+viewerTranslateY+'px) scale('+viewerScale+')';setViewerBadge();}"
                + "function resetViewerZoom(){viewerScale=1;viewerTranslateX=0;viewerTranslateY=0;applyViewerTransform();}"
                + "function zoomViewer(delta){viewerScale=clamp(viewerScale+delta,.5,6);applyViewerTransform();}"
                + "function closeViewer(){qs('imageViewer').classList.add('hidden');document.body.style.overflow='';resetViewerZoom();}"
                + "function openViewer(event,url,title,sub){if(event)event.preventDefault();var viewer=qs('imageViewer');var img=qs('viewerImage');img.src=url;qs('viewerTitle').textContent=title||'图片预览';qs('viewerSub').textContent=sub||'';viewer.classList.remove('hidden');document.body.style.overflow='hidden';resetViewerZoom();return false;}"
                + "function pointDistance(a,b){var dx=a.clientX-b.clientX,dy=a.clientY-b.clientY;return Math.sqrt(dx*dx+dy*dy);}"
                + "function pointMid(a,b){return {x:(a.clientX+b.clientX)/2,y:(a.clientY+b.clientY)/2};}"
                + "function startViewerDrag(x,y){viewerDragging=true;viewerLastX=x;viewerLastY=y;}"
                + "function moveViewerDrag(x,y){if(!viewerDragging)return;viewerTranslateX+=x-viewerLastX;viewerTranslateY+=y-viewerLastY;viewerLastX=x;viewerLastY=y;applyViewerTransform();}"
                + "function stopViewerDrag(){viewerDragging=false;}"
                + "function attachViewerInteractions(){var stage=qs('viewerStage');if(!stage||stage.dataset.bound==='1')return;stage.dataset.bound='1';stage.addEventListener('wheel',function(e){e.preventDefault();zoomViewer((e.deltaY<0?0.18:-0.18));},{passive:false});stage.addEventListener('mousedown',function(e){e.preventDefault();startViewerDrag(e.clientX,e.clientY);});window.addEventListener('mousemove',function(e){moveViewerDrag(e.clientX,e.clientY);});window.addEventListener('mouseup',function(){stopViewerDrag();});stage.addEventListener('touchstart',function(e){if(e.touches.length===1){var t=e.touches[0];viewerPinching=false;startViewerDrag(t.clientX,t.clientY);}else if(e.touches.length===2){viewerDragging=false;viewerPinching=true;viewerPinchDistance=pointDistance(e.touches[0],e.touches[1]);viewerStartScale=viewerScale;var mid=pointMid(e.touches[0],e.touches[1]);viewerStartMidX=mid.x;viewerStartMidY=mid.y;viewerStartTranslateX=viewerTranslateX;viewerStartTranslateY=viewerTranslateY;}},{passive:false});stage.addEventListener('touchmove',function(e){if(e.touches.length===1&&!viewerPinching){var t=e.touches[0];moveViewerDrag(t.clientX,t.clientY);e.preventDefault();}else if(e.touches.length===2){var dist=pointDistance(e.touches[0],e.touches[1]);var mid=pointMid(e.touches[0],e.touches[1]);viewerScale=clamp(viewerStartScale*(dist/Math.max(1,viewerPinchDistance)),.5,6);viewerTranslateX=viewerStartTranslateX+(mid.x-viewerStartMidX);viewerTranslateY=viewerStartTranslateY+(mid.y-viewerStartMidY);applyViewerTransform();e.preventDefault();}},{passive:false});stage.addEventListener('touchend',function(e){if(e.touches.length===0){viewerPinching=false;stopViewerDrag();}else if(e.touches.length===1){viewerPinching=false;var t=e.touches[0];startViewerDrag(t.clientX,t.clientY);}});stage.addEventListener('dblclick',function(){if(viewerScale>1.05){resetViewerZoom();}else{viewerScale=2;applyViewerTransform();}});}"
                + "function ensurePendingCard(){var wrap=document.getElementById('pendingCaptureWrap');if(!wrap)return null;wrap.innerHTML=\"<div class='pending-card' id='pendingCaptureCard'><div class='pending-thumb'></div><div><div class='pending-title'>正在抓拍...</div><div class='pending-sub'>摄像头正在对焦和拍照，完成后会插入到下方记录列表最前面。</div></div></div>\";wrap.classList.remove('hidden');return wrap;}"
                + "function esc(text){return String(text||'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/\"/g,'&quot;').replace(/'/g,'&#39;');}"
                + "function prependShotCard(name,timeText,thumbUrl,photoUrl,sizeText){var gallery=document.getElementById('recentGallery');if(!gallery)return;var empty=document.getElementById('recentEmpty');if(empty)empty.remove();var card=document.createElement('a');card.className='shot-card';card.href=photoUrl;card.onclick=function(ev){return openViewer(ev,photoUrl,name,sizeText);};card.innerHTML=\"<div class='thumb-wrap'><img loading='lazy' src='\"+thumbUrl+\"' alt='\"+esc(name)+\"'></div><div class='shot-meta'><div class='shot-name'>\"+esc(name)+\"</div><div class='shot-sub'>\"+esc(sizeText)+\"</div><div class='shot-time'>\"+timeText+\"</div></div>\";gallery.insertBefore(card,gallery.firstChild);while(gallery.children.length>12){gallery.removeChild(gallery.lastChild);}}"
                + "function formatNow(){var d=new Date();var p=function(v){return String(v).padStart(2,'0')};return p(d.getMonth()+1)+'-'+p(d.getDate())+' '+p(d.getHours())+':'+p(d.getMinutes())+':'+p(d.getSeconds());}"
                + "function captureSnapshot(url){ensurePendingCard();setStatus('正在抓拍，请稍候...');fetch(url,{cache:'no-store'})"
                + ".then(function(r){if(!r.ok)throw new Error('HTTP '+r.status);return Promise.all([Promise.resolve(r.headers),r.blob()]);})"
                + ".then(function(result){var headers=result[0]; var pending=document.getElementById('pendingCaptureWrap'); if(pending)pending.classList.add('hidden'); var fileName=headers.get('X-Capture-File')||('capture_'+Date.now()+'.jpg'); var width=headers.get('X-Capture-Width')||''; var height=headers.get('X-Capture-Height')||''; var photoUrl='/media?name='+encodeURIComponent(fileName); var thumbUrl='/media?thumb=1&name='+encodeURIComponent(fileName); var sizeText=(width&&height)?(width+' × '+height):'最新抓拍'; prependShotCard(fileName,formatNow(),thumbUrl,photoUrl,sizeText); setStatus('抓拍完成，已插入到最近记录顶部。'); openViewer(null,photoUrl,fileName,sizeText);})"
                + ".catch(function(e){var wrap=document.getElementById('pendingCaptureWrap');if(wrap){wrap.classList.remove('hidden');wrap.innerHTML=\"<div class='pending-card'><div class='pending-thumb'></div><div><div class='pending-title'>抓拍失败</div><div class='pending-sub'>\"+(e&&e.message?e.message:'未知错误')+\"</div><div class='pending-actions'><a class='btn btn-secondary' href='/' onclick=\\\"refreshPage();return false;\\\">刷新页面</a></div></div></div>\";}setStatus('抓拍失败：'+e.message);});return false;}"
                + "document.addEventListener('DOMContentLoaded',function(){attachViewerInteractions();var img=qs('viewerImage');if(img){img.addEventListener('load',function(){resetViewerZoom();});}});"
                + "</script></head><body><div class='page'>"
                + "<section class='hero'>"
                + "<div class='hero-top'><div><h1 class='title'>WebCam 远程抓拍</h1>"
                + "<p class='subtitle'>当前设备 IP：<strong>" + escapeHtml(ip) + ":" + currentServerPort + "</strong><br>抓拍接口相对地址：<strong>/snapshot.jpg</strong></p></div></div>"
                + "<div class='action-row'>"
                + "<a class='btn btn-primary' href='/snapshot.jpg' onclick=\"return captureSnapshot('/snapshot.jpg')\">立即抓拍</a>"
                + "<a class='btn btn-secondary' href='/snapshot.jpg?width=720&height=1280&flash=1&delayMs=500&rotate=90' onclick=\"return captureSnapshot('/snapshot.jpg?width=720&height=1280&flash=1&delayMs=500&rotate=90')\">示例抓拍</a>"
                + "<a class='btn btn-secondary' href='/' onclick='refreshPage();return false;'>刷新页面</a>"
                + "</div>"
                + "<div class='status' id='statusText'></div>"
                + "<div class='chips'>"
                + "<div class='chip'>支持尺寸：width / height</div>"
                + "<div class='chip'>闪光灯：flash=1</div>"
                + "<div class='chip'>延时：delayMs=500</div>"
                + "<div class='chip'>旋转：rotate=0/90/180/270</div>"
                + "</div></section>"
                + "<div class='layout'>"
                + "<section class='card'><h2 class='section-title'>最近抓拍</h2>"
                + "<p class='section-desc'>点击缩略图可直接查看原图，新记录会自动显示在最前面。</p>"
                + "<div class='pending-wrap hidden' id='pendingCaptureWrap'></div>"
                + recordsHtml.toString().replace("<div class='empty'>", "<div class='empty' id='recentEmpty'>").replace("<div class='gallery'>", "<div class='gallery' id='recentGallery'>")
                + "</section>"
                + "<div style='display:grid;gap:16px'>"
                + "<section class='card'><h2 class='section-title'>当前设置</h2>"
                + "<div class='info-list'>"
                + "<div class='info-item'><div class='label'>HTTP 端口</div><div class='value'>" + currentServerPort + "</div></div>"
                + "<div class='info-item'><div class='label'>默认尺寸</div><div class='value'>" + escapeHtml(size) + "</div></div>"
                + "<div class='info-item'><div class='label'>默认旋转</div><div class='value'>" + rotation + "°</div></div>"
                + "<div class='info-item'><div class='label'>自动对焦</div><div class='value'>" + (autofocus ? "开启" : "关闭") + "</div></div>"
                + "<div class='info-item'><div class='label'>时间水印</div><div class='value'>" + escapeHtml(AppSettings.getWatermarkLabel(watermarkPosition)) + "</div></div>"
                + "</div></section>"
                + "<section class='card'><h2 class='section-title'>远程设置</h2>"
                + "<form action='/settings' method='get'>"
                + "<div class='field'><label>HTTP 端口</label><input name='port' inputmode='numeric' value='" + currentServerPort + "' /></div>"
                + "<div class='field'><label>默认图片尺寸</label><select name='imageSize'>" + buildSizeOptionsHtml(size) + "</select></div>"
                + "<div class='field'><label>默认旋转角度</label><select name='rotation'>" + buildRotationOptionsHtml(rotation) + "</select></div>"
                + "<div class='field'><label>时间水印位置</label><select name='watermarkPosition'>" + buildWatermarkPositionOptionsHtml(watermarkPosition) + "</select></div>"
                + "<label class='checkbox'><input type='checkbox' name='autoFocus' value='1' " + (autofocus ? "checked" : "") + "/><span>拍照前自动对焦</span></label>"
                + "<button class='btn btn-primary submit' type='submit'>保存设置</button></form></section>"
                + "<section class='card'><h2 class='section-title'>接口说明</h2>"
                + "<ul class='tips'><li>/snapshot.jpg</li><li>参数：width、height、flash、delayMs、rotate</li><li>示例：/snapshot.jpg?width=1080&height=1440&rotate=90</li></ul>"
                + "</section></div></div>"
                + "<div class='viewer hidden' id='imageViewer' onclick=\"if(event.target===this)closeViewer()\">"
                + "<div class='viewer-panel'>"
                + "<div class='viewer-top'><div class='viewer-info'><div class='viewer-title' id='viewerTitle'>图片预览</div><div class='viewer-sub' id='viewerSub'></div></div>"
                + "<div class='viewer-actions'><button class='viewer-btn' type='button' onclick='zoomViewer(-0.2)'>－</button><button class='viewer-btn' type='button' onclick='zoomViewer(0.2)'>＋</button><button class='viewer-btn' type='button' onclick='resetViewerZoom()'>1</button><button class='viewer-btn' type='button' onclick='closeViewer()'>×</button></div></div>"
                + "<div class='viewer-stage' id='viewerStage'><div class='viewer-canvas'><img id='viewerImage' alt='preview'></div></div><div class='viewer-hint'>双指缩放 / 单指拖动 / 双击放大</div><div class='viewer-badge' id='viewerBadge'>100%</div>"
                + "</div></div>"
                + "</div></body></html>";
    }

    private String buildSizeOptionsHtml(String selected) {
        StringBuilder sb = new StringBuilder();
        for (String option : AppSettings.IMAGE_SIZE_OPTIONS) {
            sb.append("<option value='").append(option).append("'");
            if (option.equals(selected)) sb.append(" selected");
            sb.append(">").append(option).append("</option>");
        }
        return sb.toString();
    }

    private String buildRotationOptionsHtml(int selected) {
        StringBuilder sb = new StringBuilder();
        for (int option : AppSettings.ROTATION_OPTIONS) {
            sb.append("<option value='").append(option).append("'");
            if (option == selected) sb.append(" selected");
            sb.append(">").append(option).append("</option>");
        }
        return sb.toString();
    }

    private String buildWatermarkPositionOptionsHtml(String selected) {
        StringBuilder sb = new StringBuilder();
        String normalized = AppSettings.sanitizeWatermarkPosition(selected);
        for (int i = 0; i < AppSettings.WATERMARK_POSITION_VALUES.length; i++) {
            String value = AppSettings.WATERMARK_POSITION_VALUES[i];
            sb.append("<option value='").append(value).append("'");
            if (value.equals(normalized)) sb.append(" selected");
            sb.append(">").append(AppSettings.WATERMARK_POSITION_LABELS[i]).append("</option>");
        }
        return sb.toString();
    }

    private String applyRemoteSettingsAndRender(java.util.Map<String, String> params) {
        if (!params.isEmpty()) {
            if (params.containsKey("port")) {
                int newPort = AppSettings.sanitizePort(parseIntSafely(params.get("port"), currentServerPort));
                AppSettings.setHttpPort(this, newPort);
                if (newPort != currentServerPort) {
                    uiHandler.postDelayed(() -> refreshSettingsAndServer(true), 500);
                }
            }
            if (params.containsKey("imageSize")) {
                AppSettings.setDefaultImageSize(this, params.get("imageSize"));
            }
            if (params.containsKey("rotation")) {
                AppSettings.setDefaultRotation(this, AppSettings.sanitizeRotation(parseIntSafely(params.get("rotation"), AppSettings.getDefaultRotation(this))));
            }
            if (params.containsKey("watermarkPosition")) {
                AppSettings.setWatermarkPosition(this, params.get("watermarkPosition"));
            }
            AppSettings.setAutoFocusEnabled(this, params.containsKey("autoFocus"));
            runOnUiThread(() -> updateServerInfo());
        }
        return buildHomePageHtml();
    }

    private File resolveMediaFile(Map<String, String> params) throws Exception {
        String name = params.get("name");
        if (name == null || name.trim().isEmpty()) {
            throw new IOException("缺少 name 参数");
        }
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            throw new IOException("非法文件名");
        }
        File photoFile = new File(photoDir, name);
        if (!photoFile.exists() || !photoFile.isFile()) {
            throw new IOException("文件不存在");
        }
        boolean thumb = "1".equals(params.get("thumb")) || "true".equalsIgnoreCase(params.get("thumb"));
        if (!thumb) {
            return photoFile;
        }
        File thumbFile = ensureThumbnail(photoFile);
        if (thumbFile == null || !thumbFile.exists()) {
            return photoFile;
        }
        return thumbFile;
    }

    private SimpleHttpCameraServer.BinaryContent resolveBinaryContent(String path) throws Exception {
        if ("/favicon.png".equals(path) || "/favicon.ico".equals(path)) {
            try (InputStream inputStream = getResources().openRawResource(R.drawable.ic_launcher_logo)) {
                byte[] data = readAllBytes(inputStream);
                return new SimpleHttpCameraServer.BinaryContent(data, "image/png");
            }
        }
        throw new IOException("资源不存在");
    }

    private byte[] readAllBytes(InputStream inputStream) throws IOException {
        java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = inputStream.read(buffer)) != -1) {
            outputStream.write(buffer, 0, count);
        }
        return outputStream.toByteArray();
    }

    private List<PhotoRecord> getRecentPhotoRecords(int limit) {
        LinkedHashMap<String, PhotoRecord> merged = new LinkedHashMap<>();
        for (PhotoRecord record : photoRecords) {
            if (record != null && record.file != null && record.file.exists()) {
                merged.put(record.file.getName(), record);
            }
            if (merged.size() >= limit) {
                break;
            }
        }
        File[] files = photoDir.listFiles((dir, name) -> name.endsWith(".jpg") && !name.endsWith(".thumb.jpg"));
        if (files != null) {
            Arrays.sort(files, (o1, o2) -> Long.compare(o2.lastModified(), o1.lastModified()));
            for (File file : files) {
                if (merged.containsKey(file.getName())) {
                    continue;
                }
                int[] dims = ImageUtils.readDimensions(file.getAbsolutePath());
                merged.put(file.getName(), new PhotoRecord("历史记录", file.lastModified(), file, ensureThumbnail(file), dims[0], dims[1]));
                if (merged.size() >= limit) {
                    break;
                }
            }
        }
        return new ArrayList<>(merged.values()).subList(0, Math.min(limit, merged.size()));
    }

    private String escapeHtml(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private String urlEncode(String text) {
        try {
            return java.net.URLEncoder.encode(text, "UTF-8");
        } catch (Exception e) {
            return text;
        }
    }

    private int parseIntSafely(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private static class CaptureTask {
        final String source;
        final File file;
        final CountDownLatch latch;
        final SimpleHttpCameraServer.RequestOptions options;
        volatile File resultFile;
        volatile Exception error;

        CaptureTask(String source, File file, CountDownLatch latch, SimpleHttpCameraServer.RequestOptions options) {
            this.source = source;
            this.file = file;
            this.latch = latch;
            this.options = options;
        }
    }
}
