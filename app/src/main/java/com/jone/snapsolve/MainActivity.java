package com.jone.snapsolve;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.AspectRatio;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageCapture;
import androidx.camera.core.ImageCaptureException;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends AppCompatActivity {

    private static final int REQ_CAMERA = 1001;
    private static final int MAX_UPLOAD_SIDE = 1600;
    private static final int STABLE_FRAMES = 8;      // 连续稳定帧数门槛
    private static final long AUTO_DWELL_MS = 500;   // 稳定后再等 0.5s
    private static final long AUTO_COOLDOWN_MS = 2500;

    private PreviewView previewView;
    private OverlayView overlay;
    private Button shutter;
    private Button settingsBtn;
    private Button autoBtn;
    private Button splitBtn;
    private Button[] subjectBtns;
    private TextView hint;

    private ImageCapture imageCapture;
    private ExecutorService analysisExecutor;
    private ExecutorService captureExecutor;

    private final QuadTracker tracker = new QuadTracker();
    private final Handler main = new Handler(Looper.getMainLooper());

    private static final String[] SUBJECT_KEYS = {"auto", "math", "chinese", "english"};

    private volatile boolean splitEnabled = true;
    private int frameCount;
    private volatile boolean analyzing;
    private volatile boolean capturing;
    private volatile float imageAspect = 16f / 9f;
    private volatile long lastUiAt;

    private long stableSince;
    private long autoCooldownUntil;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 调试入口：adb 传入一张图直接进结果页，省去每次摆拍
        String debugImage = getIntent().getStringExtra("debugImage");
        if (debugImage != null) {
            Intent dbg = new Intent(this, ResultActivity.class);
            dbg.putExtra(ResultActivity.EXTRA_PATH, debugImage);
            dbg.putExtra(ResultActivity.EXTRA_SPLIT, true);
            dbg.putExtra(ResultActivity.EXTRA_GRADING,
                    getIntent().getBooleanExtra("grading", false)
                            || "grading".equals(getIntent().getStringExtra("mode")));
            startActivity(dbg);
            finish();
            return;
        }

        setContentView(R.layout.activity_main);

        previewView = findViewById(R.id.preview);
        // 部分机型（尤其华为/EMUI）在 SurfaceView 模式下预览会黑屏，TextureView 更稳
        previewView.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);
        overlay = findViewById(R.id.overlay);
        shutter = findViewById(R.id.shutter);
        settingsBtn = findViewById(R.id.settings);
        autoBtn = findViewById(R.id.auto);
        splitBtn = findViewById(R.id.split);
        hint = findViewById(R.id.hint);

        subjectBtns = new Button[]{
                findViewById(R.id.sub_auto),
                findViewById(R.id.sub_math),
                findViewById(R.id.sub_chinese),
                findViewById(R.id.sub_english)};

        for (int i = 0; i < subjectBtns.length; i++) {
            final String key = SUBJECT_KEYS[i];
            subjectBtns[i].setOnClickListener(v -> {
                Prefs.setSubject(this, key);
                updateSubjectUi();
            });
        }

        analysisExecutor = Executors.newSingleThreadExecutor();
        captureExecutor = Executors.newSingleThreadExecutor();

        shutter.setOnClickListener(v -> requestCapture(false));
        settingsBtn.setOnClickListener(v ->
                startActivity(new Intent(this, SettingsActivity.class)));
        splitBtn.setOnClickListener(v -> {
            boolean next = !Prefs.grading(this);
            Prefs.setGrading(this, next);
            updateSplitButton();
        });
        autoBtn.setOnClickListener(v -> {
            boolean next = !Prefs.autoCapture(this);
            Prefs.setAutoCapture(this, next);
            stableSince = 0;
            updateAutoButton();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateHint();
        updateAutoButton();
        updateSubjectUi();
        updateSplitButton();
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
                == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void updateHint() {
        if (Prefs.isConfigured(this)) {
            hint.setVisibility(View.GONE);
        } else {
            hint.setVisibility(View.VISIBLE);
            hint.setText(R.string.hint_need_api);
        }
    }

    private void updateSubjectUi() {
        String current = Prefs.subject(this);
        for (int i = 0; i < subjectBtns.length; i++) {
            boolean on = SUBJECT_KEYS[i].equals(current);
            subjectBtns[i].setBackgroundResource(on ? R.drawable.bg_pill_active : R.drawable.bg_pill);
            subjectBtns[i].setAlpha(on ? 1f : 0.7f);
        }
    }

    private void updateSplitButton() {
        boolean grading = Prefs.grading(this);
        splitBtn.setText(grading ? R.string.grade_on : R.string.grade_off);
        // 开启时用高亮底 + 满不透明，避免用户以为已经开了
        splitBtn.setBackgroundResource(grading ? R.drawable.bg_pill_active : R.drawable.bg_pill);
        splitBtn.setAlpha(grading ? 1f : 0.85f);
    }

    private void updateAutoButton() {
        boolean on = Prefs.autoCapture(this);
        autoBtn.setText(on ? R.string.auto_on : R.string.auto_off);
        autoBtn.setAlpha(on ? 1f : 0.55f);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startCamera();
            } else {
                Toast.makeText(this, R.string.need_camera, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future =
                ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                bindUseCases(future.get());
            } catch (Exception e) {
                Log.e("SnapSolve", "camera start failed", e);
                Toast.makeText(this, "相机启动失败: " + e.getMessage(),
                        Toast.LENGTH_LONG).show();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    /**
     * 三个用例统一使用 16:9，保证预览、分析、拍照的视野一致，
     * 叠加层的归一化坐标才能直接映射到成片上。
     */
    private void bindUseCases(ProcessCameraProvider provider) {
        Preview preview = new Preview.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();
        analysis.setAnalyzer(analysisExecutor, this::analyze);

        imageCapture = new ImageCapture.Builder()
                .setTargetAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetRotation(getWindowManager().getDefaultDisplay().getRotation())
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build();

        provider.unbindAll();
        provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA,
                preview, analysis, imageCapture);
        Log.i("SnapSolve", "camera bound: preview+analysis+capture");
    }

    /** 每帧：本地检测 → 稳定器 → 刷新叠加层 → 判断是否该自动拍。 */
    private void analyze(ImageProxy image) {
        try {
            if (analyzing) return;
            analyzing = true;

            ImageProxy.PlaneProxy plane = image.getPlanes()[0];
            ByteBuffer buffer = plane.getBuffer();
            int rot = image.getImageInfo().getRotationDegrees();
            int w = image.getWidth(), h = image.getHeight();

            long t0 = SystemClock.uptimeMillis();
            Quad detected = QuadDetector.detect(buffer, w, h,
                    plane.getRowStride(), plane.getPixelStride(), rot, false);
            long cost = SystemClock.uptimeMillis() - t0;

            if (++frameCount % 30 == 0) {
                Log.d("SnapSolve", "frame#" + frameCount + " " + w + "x" + h
                        + " rot=" + rot + " detect=" + cost + "ms quad="
                        + (detected == null ? "null" : String.format("%.3f,%.3f",
                            detected.x[0], detected.y[0])));
            }
            Quad tracked = tracker.update(detected);
            int stableFrames = tracker.stableFrames();

            imageAspect = (rot == 90 || rot == 270) ? (float) h / w : (float) w / h;

            long now = SystemClock.uptimeMillis();
            if (now - lastUiAt > 60) {
                lastUiAt = now;
                main.post(() -> {
                    boolean stable = stableFrames >= STABLE_FRAMES;
                    overlay.setQuad(tracked, imageAspect, stable);
                    maybeAutoCapture(stable, tracked);
                });
            }
        } catch (Throwable ignored) {
            // 单帧异常不打断预览
        } finally {
            analyzing = false;
            image.close();
        }
    }

    /** 稳定判定 → 起拍倒计时 → 自动拍照。全部在主线程执行。 */
    private void maybeAutoCapture(boolean stable, Quad tracked) {
        if (!Prefs.autoCapture(this) || tracked == null || capturing) {
            stableSince = 0;
            return;
        }
        if (!Prefs.isConfigured(this)) {
            stableSince = 0;
            return;
        }

        long now = SystemClock.uptimeMillis();
        if (now < autoCooldownUntil) {
            stableSince = 0;
            return;
        }

        if (!stable) {
            stableSince = 0;
            return;
        }

        if (stableSince == 0) {
            stableSince = now;
            return;
        }

        if (now - stableSince >= AUTO_DWELL_MS) {
            stableSince = 0;
            doCapture(true);
        }
    }

    private void requestCapture(boolean auto) {
        if (capturing) return;
        if (!Prefs.isConfigured(this)) {
            Toast.makeText(this, R.string.need_api_first, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SettingsActivity.class));
            return;
        }
        doCapture(auto);
    }

    private void doCapture(boolean auto) {
        if (imageCapture == null || capturing) return;
        capturing = true;
        shutter.setEnabled(false);

        final Quad boxed = tracker.get();

        imageCapture.takePicture(captureExecutor, new ImageCapture.OnImageCapturedCallback() {
            @Override
            public void onCaptureSuccess(@NonNull ImageProxy image) {
                try {
                    int rot = image.getImageInfo().getRotationDegrees();
                    ByteBuffer buf = image.getPlanes()[0].getBuffer();
                    byte[] jpeg = new byte[buf.remaining()];
                    buf.get(jpeg);
                    image.close();

                    Bitmap bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
                    if (bmp == null) {
                        fail(getString(R.string.decode_failed));
                        return;
                    }
                    if (rot != 0) bmp = rotate(bmp, rot);
                    Log.d("SnapSolve", "shot decoded " + bmp.getWidth() + "x" + bmp.getHeight()
                            + " rot=" + rot + " quad=" + (boxed == null ? "null" : boxed.bboxString()));

                    Bitmap out = boxed == null
                            ? centerCrop(bmp)
                            : warpByQuad(bmp, boxed);
                    if (out == null) out = centerCrop(bmp);
                    Log.d("SnapSolve", "after warp " + out.getWidth() + "x" + out.getHeight());

                    // 切题改到结果页做：这里保留整页，供多题分割与逐题裁切
                    Log.d("SnapSolve", "page " + out.getWidth() + "x" + out.getHeight());

                    final Bitmap shot = out;

                    // 上传前模糊拦截（对应原 App 的 BlurCheckLogic）
                    if (Sharpness.isBlurry(shot, Prefs.blurThreshold(MainActivity.this))) {
                        main.post(() -> promptBlurry(shot));
                        return;
                    }
                    deliver(shot);
                } catch (Throwable t) {
                    fail("处理失败: " + t.getMessage());
                }
            }

            @Override
            public void onError(@NonNull ImageCaptureException e) {
                fail("拍照失败: " + e.getMessage());
            }
        });
    }

    private void fail(String msg) {
        main.post(() -> {
            capturing = false;
            shutter.setEnabled(true);
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        });
    }

    private static Bitmap rotate(Bitmap src, int degrees) {
        Matrix m = new Matrix();
        m.postRotate(degrees);
        return Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), m, true);
    }

    /** 四边形 → 透视矫正成矩形；四角退化时返回 null。 */
    private static Bitmap warpByQuad(Bitmap src, Quad q) {
        float w = src.getWidth(), h = src.getHeight();
        float[] px = new float[4], py = new float[4];
        for (int i = 0; i < 4; i++) {
            px[i] = q.x[i] * w;
            py[i] = q.y[i] * h;
        }
        return Perspective.warp(src, px, py, MAX_UPLOAD_SIDE);
    }

    /** 没框时的兜底：取中间 90%。 */
    private static Bitmap centerCrop(Bitmap src) {
        int w = src.getWidth(), h = src.getHeight();
        int cw = (int) (w * 0.9f), ch = (int) (h * 0.9f);
        Bitmap crop = Bitmap.createBitmap(src, (w - cw) / 2, (h - ch) / 2, cw, ch);

        int longSide = Math.max(cw, ch);
        if (longSide > MAX_UPLOAD_SIDE) {
            float s = MAX_UPLOAD_SIDE / (float) longSide;
            crop = Bitmap.createScaledBitmap(crop,
                    Math.max(1, (int) (cw * s)), Math.max(1, (int) (ch * s)), true);
        }
        return crop;
    }

    /** 糊了就问一句：重拍还是硬传（对应原 App 的模糊拦截，但不强行卡死用户）。 */
    private void promptBlurry(Bitmap shot) {
        if (isFinishing()) {
            releaseCapture();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle(R.string.blur_title)
                .setMessage(R.string.blur_msg)
                .setCancelable(false)
                .setPositiveButton(R.string.blur_retry, (d, w) -> releaseCapture())
                .setNegativeButton(R.string.blur_send, (d, w) -> deliver(shot))
                .show();
    }

    /** 压缩到体积上限 → 落盘 → 跳结果页。都在后台线程完成。 */
    private void deliver(Bitmap shot) {
        try {
            // 整页按高质量落盘：结果页要在这张图上切 10+ 道题，压太狠会糊
            File f = new File(getCacheDir(), "shot.jpg");
            try (FileOutputStream out = new FileOutputStream(f)) {
                shot.compress(Bitmap.CompressFormat.JPEG, 88, out);
            }
            final String path = f.getAbsolutePath();
            final int size = (int) f.length();
            final String key = UploadCache.sha256((path + size).getBytes());

            main.post(() -> {
                releaseCapture();
                Intent i = new Intent(MainActivity.this, ResultActivity.class);
                i.putExtra(ResultActivity.EXTRA_PATH, path);
                i.putExtra(ResultActivity.EXTRA_SIZE, size);
                i.putExtra(ResultActivity.EXTRA_KEY, key);
                i.putExtra(ResultActivity.EXTRA_SPLIT, true);
                i.putExtra(ResultActivity.EXTRA_GRADING, Prefs.grading(this));
                startActivity(i);
            });
        } catch (Throwable t) {
            fail("处理失败: " + t.getMessage());
        }
    }

    private void releaseCapture() {
        capturing = false;
        shutter.setEnabled(true);
        autoCooldownUntil = SystemClock.uptimeMillis() + AUTO_COOLDOWN_MS;
        tracker.pauseAuto();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (analysisExecutor != null) analysisExecutor.shutdown();
        if (captureExecutor != null) captureExecutor.shutdown();
    }
}
