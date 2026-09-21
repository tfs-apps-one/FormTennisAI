package tfsapps.formtennisai.camera;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import tfsapps.formtennisai.R;
import tfsapps.formtennisai.analyzer.TennisPoseAnalyzer;
import tfsapps.formtennisai.analyzer.TennisPoseValidator;
import tfsapps.formtennisai.model.TennisFormMode;
import tfsapps.formtennisai.model.TennisPhase;
import tfsapps.formtennisai.result.TennisResultActivity;
import tfsapps.formtennisai.viewmodel.TennisCameraViewModel;

/**
 * Full-screen camera / AR-diagnosis screen — tennis counterpart of
 * {@code tfsapps.formbaseballai.camera.CameraActivity}.
 *
 * UX flow:
 * 1. Permission check → camera binds Preview + ImageAnalysis (TennisPoseAnalyzer).
 * 2. User picks a stroke (Serve / Forehand / Backhand — with a two-handed /
 *    one-handed sub-toggle for Backhand) via {@link TennisFormMode}.
 * 3. User taps 開始 (Start) → {@link TennisCameraViewModel} begins a guided
 *    single-swing sequence: the user swings once, and each of the four
 *    {@link TennisPhase} checkpoints is auto-detected and scored live (see
 *    the ViewModel's class doc for why this replaces baseball's
 *    record-then-sample-8-frames flow).
 * 4. Once all four checkpoints are locked in, this Activity navigates to
 *    {@link TennisResultActivity} with the combined result.
 *
 * Unlike the baseball reference, this screen has no AdMob/billing/history
 * wiring — those are monetization/premium features that weren't part of
 * this task's instructions, so they were left out rather than guessed at.
 */
public class TennisCameraActivity extends AppCompatActivity {

    private static final String TAG = "TennisCameraActivity";

    private TennisCameraViewModel viewModel;
    private PreviewView previewView;
    private TennisPoseOverlayView overlayView;
    private MaterialButtonToggleGroup modeToggleGroup;
    private MaterialButtonToggleGroup backhandGripToggleGroup;
    private TextView tvCameraAngleHint;
    private TextView tvPhaseLabel;
    private TextView tvHint;
    private TextView tvLiveScoreNum;
    private ProgressBar pbLiveScore;
    private MaterialButton btnAction;
    private TextView btnSkipPhase;
    private LinearLayout layoutBottomContainer;

    private CameraSelector currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;

    private ExecutorService cameraExecutor;
    private TennisPoseAnalyzer poseAnalyzer;
    private ProcessCameraProvider cameraProvider;

    /** Non-cancelable "診断中…" popup shown during SequenceState.PROCESSING. */
    @Nullable
    private AlertDialog processingDialog;
    /** "検出できません" popup shown once a checkpoint has gone un-detected
     *  too long (see TennisCameraViewModel's stuck-timer). */
    @Nullable
    private AlertDialog stuckDialog;

    /** Guards against firing the result-screen navigation more than once
     *  for the same completed sequence (LiveData can re-deliver on
     *  configuration changes / re-observation). */
    private boolean navigatedForThisResult = false;

    private final ActivityResultLauncher<String> permissionLauncher = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(),
            granted -> {
                if (granted) {
                    startCamera();
                } else {
                    Toast.makeText(this, getString(R.string.permission_required), Toast.LENGTH_LONG).show();
                    finish();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tennis_camera);

        previewView = findViewById(R.id.previewView);
        overlayView = findViewById(R.id.poseOverlay);
        modeToggleGroup = findViewById(R.id.modeToggleGroup);
        backhandGripToggleGroup = findViewById(R.id.backhandGripToggleGroup);
        tvCameraAngleHint = findViewById(R.id.tvCameraAngleHint);
        tvPhaseLabel = findViewById(R.id.tvPhaseLabel);
        tvHint = findViewById(R.id.tvHint);
        tvLiveScoreNum = findViewById(R.id.tvLiveScoreNum);
        pbLiveScore = findViewById(R.id.pbLiveScore);
        btnAction = findViewById(R.id.btnAction);
        btnSkipPhase = findViewById(R.id.btnSkipPhase);
        layoutBottomContainer = findViewById(R.id.layoutBottomContainer);

        cameraExecutor = Executors.newSingleThreadExecutor();

        // ── ナビゲーションバー対応: paddingBottom を動的に設定 ────────────
        ViewCompat.setOnApplyWindowInsetsListener(layoutBottomContainer, (v, insets) -> {
            int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), navBarHeight);
            return insets;
        });

        viewModel = new ViewModelProvider(this).get(TennisCameraViewModel.class);

        // ── Mode selection ────────────────────────────────────────────────
        modeToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            TennisFormMode newMode;
            if (checkedId == R.id.btnServe) {
                newMode = TennisFormMode.SERVE;
            } else if (checkedId == R.id.btnForehand) {
                newMode = TennisFormMode.FOREHAND;
            } else {
                // Default grip when the user first switches into the Backhand family.
                newMode = TennisFormMode.BACKHAND_TWO_HANDED;
                backhandGripToggleGroup.check(R.id.btnGripTwo);
            }
            viewModel.setMode(newMode);
        });

        backhandGripToggleGroup.addOnButtonCheckedListener((group, checkedId, isChecked) -> {
            if (!isChecked) return;
            TennisFormMode m = (checkedId == R.id.btnGripOne)
                    ? TennisFormMode.BACKHAND_ONE_HANDED
                    : TennisFormMode.BACKHAND_TWO_HANDED;
            viewModel.setMode(m);
        });

        // ── ViewModel observers ──────────────────────────────────────────
        viewModel.getMode().observe(this, mode -> {
            backhandGripToggleGroup.setVisibility(mode.isBackhand() ? View.VISIBLE : View.GONE);
            overlayView.setMode(mode);
            updateAngleHint(mode);
            refreshHint();
        });

        viewModel.getPoseFrame().observe(this,
                frame -> overlayView.setPose(frame.pose, frame.imageWidth, frame.imageHeight));

        viewModel.getCurrentPhase().observe(this, phase -> {
            TennisFormMode m = viewModel.getMode().getValue();
            if (m == null) return;
            int stepNumber = phase.getIndex() + 1;
            tvPhaseLabel.setText(String.format(java.util.Locale.getDefault(),
                    "%d/%d  %s", stepNumber, TennisPhase.count(), m.getPhaseLabel(this, phase)));
            overlayView.setPhase(phase);
            // A new checkpoint just started — any error popup about the
            // previous one is no longer relevant.
            dismissStuckDialog();
        });

        viewModel.getLiveScore().observe(this, score -> {
            if (score == null || score < 0f) {
                tvLiveScoreNum.setText("--");
                pbLiveScore.setProgress(0);
                tvLiveScoreNum.setTextColor(Color.parseColor("#8800E5FF"));
                return;
            }
            int s = Math.max(0, Math.min(100, Math.round(score)));
            tvLiveScoreNum.setText(String.format(java.util.Locale.getDefault(), "%d", s));
            pbLiveScore.setProgress(s);
            int colour = scoreColour(s);
            pbLiveScore.setProgressTintList(ColorStateList.valueOf(colour));
            tvLiveScoreNum.setTextColor(colour);
        });

        viewModel.getValidationState().observe(this, state -> {
            refreshHint();
            if (state == TennisPoseValidator.ValidationState.VALID) dismissStuckDialog();
        });

        viewModel.getStuckEvent().observe(this, event -> {
            TennisPoseValidator.ValidationState state = event.getContentIfNotHandled();
            if (state != null) showStuckDialog(state);
        });

        viewModel.getSequenceState().observe(this, state -> {
            boolean inProgress = (state == TennisCameraViewModel.SequenceState.IN_PROGRESS);
            boolean processing = (state == TennisCameraViewModel.SequenceState.PROCESSING);
            btnAction.setText(inProgress ? R.string.btn_reset_sequence : R.string.btn_start_sequence);
            btnAction.setEnabled(!processing);
            btnAction.setBackgroundTintList(ColorStateList.valueOf(
                    getColor(inProgress ? R.color.btn_reset : R.color.btn_serve)));
            btnSkipPhase.setVisibility(inProgress ? View.VISIBLE : View.GONE);
            setControlsEnabled(!inProgress && !processing);
            refreshHint();

            if (processing) {
                showProcessingDialog();
            } else {
                dismissProcessingDialog();
            }
            if (!inProgress) {
                dismissStuckDialog();
            }

            if (state == TennisCameraViewModel.SequenceState.COMPLETE && !navigatedForThisResult) {
                navigatedForThisResult = true;
                Intent intent = new Intent(this, TennisResultActivity.class);
                intent.putExtra(TennisResultActivity.EXTRA_RESULT, viewModel.getStrokeResult());
                startActivity(intent);
            } else if (state != TennisCameraViewModel.SequenceState.COMPLETE) {
                navigatedForThisResult = false;
            }
        });

        // ── Buttons ──────────────────────────────────────────────────────
        btnAction.setOnClickListener(v -> {
            if (viewModel.getSequenceState().getValue() == TennisCameraViewModel.SequenceState.IN_PROGRESS) {
                viewModel.resetSequence();
            } else {
                viewModel.startSequence();
            }
        });

        btnSkipPhase.setOnClickListener(v -> viewModel.skipCurrentPhase());

        findViewById(R.id.btnHelp).setOnClickListener(v -> showHelpDialog());

        findViewById(R.id.btnSwitchCamera).setOnClickListener(v -> {
            if (viewModel.getSequenceState().getValue() == TennisCameraViewModel.SequenceState.IN_PROGRESS) return;
            currentCameraSelector = (currentCameraSelector == CameraSelector.DEFAULT_BACK_CAMERA)
                    ? CameraSelector.DEFAULT_FRONT_CAMERA
                    : CameraSelector.DEFAULT_BACK_CAMERA;
            if (cameraProvider != null) bindUseCases(cameraProvider);
        });

        // ── Permission ─────────────────────────────────────────────────────
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera();
        } else {
            permissionLauncher.launch(Manifest.permission.CAMERA);
        }
    }

    @Override
    protected void onDestroy() {
        cameraExecutor.shutdown();
        if (poseAnalyzer != null) poseAnalyzer.shutdown();
        if (cameraProvider != null) cameraProvider.unbindAll();
        dismissProcessingDialog();
        dismissStuckDialog();
        super.onDestroy();
    }

    // ── Hint text ────────────────────────────────────────────────────────

    private void refreshHint() {
        TennisFormMode m = viewModel.getMode().getValue();
        if (m == null) return;
        TennisCameraViewModel.SequenceState seq = viewModel.getSequenceState().getValue();

        if (seq != TennisCameraViewModel.SequenceState.IN_PROGRESS) {
            tvHint.setText(genericModeHint(m));
            return;
        }

        TennisPoseValidator.ValidationState vs = viewModel.getValidationState().getValue();
        if (vs == null) {
            tvHint.setText(genericModeHint(m));
            return;
        }
        switch (vs) {
            case NO_HUMAN:
                tvHint.setText(getString(R.string.hint_no_human));
                break;
            case WRONG_POSE:
                tvHint.setText(wrongPoseHint(m));
                break;
            case VALID:
                tvHint.setText(getString(R.string.hint_checkpoint_valid));
                break;
        }
    }

    /**
     * The always-visible banner instruction differs by stroke: SERVE needs
     * a side-on shot (like a pitcher's throwing motion), while FOREHAND /
     * BACKHAND need a frontal shot — see TennisPoseValidator's class
     * javadoc for why each checkpoint actually requires that angle.
     */
    private void updateAngleHint(TennisFormMode m) {
        tvCameraAngleHint.setText(m == TennisFormMode.SERVE
                ? R.string.camera_angle_hint_serve
                : R.string.camera_angle_hint_front);
    }

    private String genericModeHint(TennisFormMode m) {
        switch (m) {
            case SERVE: return getString(R.string.camera_hint_serve);
            case FOREHAND: return getString(R.string.camera_hint_forehand);
            default: return getString(R.string.camera_hint_backhand);
        }
    }

    private String wrongPoseHint(TennisFormMode m) {
        switch (m) {
            case SERVE: return getString(R.string.hint_wrong_pose_serve);
            case FOREHAND: return getString(R.string.hint_wrong_pose_forehand);
            default: return getString(R.string.hint_wrong_pose_backhand);
        }
    }

    private void setControlsEnabled(boolean enabled) {
        for (int i = 0; i < modeToggleGroup.getChildCount(); i++) {
            modeToggleGroup.getChildAt(i).setEnabled(enabled);
        }
        for (int i = 0; i < backhandGripToggleGroup.getChildCount(); i++) {
            backhandGripToggleGroup.getChildAt(i).setEnabled(enabled);
        }
        findViewById(R.id.btnSwitchCamera).setEnabled(enabled);
    }

    // ── Help ─────────────────────────────────────────────────────────────

    private void showHelpDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_body)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    // ── Detection-error / diagnosing popups ─────────────────────────────

    /** Shown once a checkpoint has gone un-recognized for several seconds
     *  (see TennisCameraViewModel's stuck timer) — the small hint text
     *  alone was easy to miss, so this surfaces the same guidance as a
     *  prominent, dismissible popup with a direct "skip this" escape hatch. */
    private void showStuckDialog(TennisPoseValidator.ValidationState state) {
        if (stuckDialog != null && stuckDialog.isShowing()) return;
        TennisFormMode m = viewModel.getMode().getValue();
        if (m == null) return;
        String message = (state == TennisPoseValidator.ValidationState.NO_HUMAN)
                ? getString(R.string.hint_no_human)
                : wrongPoseHint(m);
        stuckDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.error_title)
                .setMessage(message)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.btn_skip_phase, (d, w) -> viewModel.skipCurrentPhase())
                .show();
    }

    private void dismissStuckDialog() {
        if (stuckDialog != null && stuckDialog.isShowing()) stuckDialog.dismiss();
        stuckDialog = null;
    }

    /** Shown for a brief moment between the last checkpoint locking in and
     *  navigation to the result screen, so the transition doesn't feel
     *  like the app has simply stalled. Uses a custom card layout
     *  (dialog_processing.xml) instead of a plain text AlertDialog so it
     *  matches the camera screen's cyan-on-black HUD styling; the dialog
     *  window's own background is made transparent so only our rounded
     *  card shows, not the system dialog's default white/gray panel. */
    private void showProcessingDialog() {
        if (processingDialog != null && processingDialog.isShowing()) return;
        View view = getLayoutInflater().inflate(R.layout.dialog_processing, null);
        processingDialog = new AlertDialog.Builder(this)
                .setView(view)
                .setCancelable(false)
                .create();
        Window window = processingDialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        }
        processingDialog.show();
    }

    private void dismissProcessingDialog() {
        if (processingDialog != null && processingDialog.isShowing()) processingDialog.dismiss();
        processingDialog = null;
    }

    // ── Camera setup (same CameraX wiring pattern as the baseball app) ────

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                cameraProvider = future.get();
                bindUseCases(cameraProvider);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Camera provider init failed", e);
                Toast.makeText(this, getString(R.string.camera_init_failed), Toast.LENGTH_LONG).show();
                finish();
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void bindUseCases(@NonNull ProcessCameraProvider provider) {
        Preview preview = new Preview.Builder().build();
        preview.setSurfaceProvider(previewView.getSurfaceProvider());

        ImageAnalysis analysis = new ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build();

        poseAnalyzer = new TennisPoseAnalyzer((pose, imgW, imgH) ->
                viewModel.onPoseAnalyzed(pose, imgW, imgH));

        analysis.setAnalyzer(cameraExecutor, poseAnalyzer);

        provider.unbindAll();

        try {
            provider.bindToLifecycle(this, currentCameraSelector, preview, analysis);
        } catch (IllegalArgumentException e) {
            Log.e(TAG, "Camera not available, falling back", e);
            if (currentCameraSelector != CameraSelector.DEFAULT_BACK_CAMERA) {
                currentCameraSelector = CameraSelector.DEFAULT_BACK_CAMERA;
                try {
                    provider.unbindAll();
                    provider.bindToLifecycle(this, currentCameraSelector, preview, analysis);
                } catch (IllegalArgumentException e2) {
                    Log.e(TAG, "No back camera available", e2);
                    Toast.makeText(this, getString(R.string.camera_not_found), Toast.LENGTH_LONG).show();
                    finish();
                }
            } else {
                Toast.makeText(this, getString(R.string.back_camera_not_found), Toast.LENGTH_LONG).show();
                finish();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static int scoreColour(float score) {
        if (score >= 70) return Color.parseColor("#FF00E676");
        if (score >= 45) return Color.parseColor("#FFFFCA28");
        return Color.parseColor("#FFEF5350");
    }
}
