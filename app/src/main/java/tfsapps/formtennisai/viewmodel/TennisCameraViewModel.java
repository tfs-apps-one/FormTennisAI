package tfsapps.formtennisai.viewmodel;

import android.app.Application;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.mlkit.vision.pose.Pose;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import tfsapps.formtennisai.R;
import tfsapps.formtennisai.analyzer.TennisPoseValidator;
import tfsapps.formtennisai.model.PoseFrame;
import tfsapps.formtennisai.model.TennisCoachModel;
import tfsapps.formtennisai.model.TennisFormMode;
import tfsapps.formtennisai.model.TennisPhase;
import tfsapps.formtennisai.model.TennisPoseResult;
import tfsapps.formtennisai.model.TennisStrokeResult;
import tfsapps.formtennisai.scoring.TennisFormScorer;

/**
 * Drives one guided, single-swing diagnosis: the user selects a
 * {@link TennisFormMode}, taps start, then swings once in front of the
 * camera while this ViewModel auto-detects and locks in each of the four
 * {@link TennisPhase} checkpoints in order (READY → TURN → IMPACT →
 * FINISH), using {@link TennisPoseValidator} to recognize each checkpoint
 * and {@link TennisFormScorer} to score it.
 *
 * ── Why this shape, and not a straight port of baseball's CameraViewModel ──
 * We weren't given baseball's CameraViewModel source, only CameraActivity's
 * usage of it (a LiveData-driven pose/score/validation/recording surface).
 * More importantly, baseball's own scoring API is a *single-shot* design —
 * FormScorer.score() judges one snapshot (a pitch's cocking position, a
 * swing's contact) — so CameraActivity's "record for N seconds, then
 * blindly sample 8 frames afterward" flow is a good match for it.
 * TennisFormScorer.score(), by contrast, takes an explicit
 * {@link TennisPhase} — the API itself is phase-aware. The natural
 * integration is therefore to let {@link TennisPoseValidator} tell us
 * *when* each named checkpoint is actually happening, live, and lock in
 * that phase's score at that moment — rather than guessing 4 evenly-spaced
 * timestamps out of a blind recording and hoping they land on the right
 * instants of a fast swing.
 *
 * ── Detection algorithm ────────────────────────────────────────────────
 * On every analyzed frame while a sequence is in progress, the current
 * target phase is re-validated. While it stays VALID we keep overwriting
 * a "candidate" pose (so we end up keeping the last — typically clearest —
 * valid instant). The moment validation stops being VALID right after a
 * run of VALID frames, that's read as "the swing has moved past this
 * checkpoint": the candidate is scored and locked in, and we advance to
 * the next phase. {@link #skipCurrentPhase()} is the manual escape hatch
 * if a checkpoint never validates (imperfect lighting/angle, etc.).
 */
public class TennisCameraViewModel extends AndroidViewModel {

    /** IN_PROGRESS → PROCESSING is a short, deliberate pause between the
     *  FINISH checkpoint locking in and navigation to the result screen,
     *  purely so the UI has a moment to show a "diagnosing" indicator
     *  instead of jumping to the result screen with no feedback. */
    public enum SequenceState { IDLE, IN_PROGRESS, PROCESSING, COMPLETE }

    /** How long PROCESSING is shown before COMPLETE fires. */
    private static final long PROCESSING_DISPLAY_MS = 700L;

    /** If a phase stays un-VALID this long after it starts, we tell the UI
     *  to show an explicit error popup instead of only the small hint text. */
    private static final long STUCK_THRESHOLD_MS = 6000L;

    /**
     * A LiveData payload that is only ever delivered once, to whichever
     * observer calls {@link #getContentIfNotHandled()} first — used for
     * one-shot UI events (like "show an error dialog now") where a plain
     * LiveData would incorrectly redeliver the last value to a newly
     * (re)attached observer, e.g. after a configuration change.
     */
    public static final class Event<T> {
        private final T content;
        private boolean handled = false;

        public Event(T content) { this.content = content; }

        @Nullable
        public T getContentIfNotHandled() {
            if (handled) return null;
            handled = true;
            return content;
        }
    }

    private final MutableLiveData<TennisFormMode> mode = new MutableLiveData<>(TennisFormMode.SERVE);
    private final MutableLiveData<TennisPhase> currentPhase = new MutableLiveData<>(TennisPhase.READY);
    private final MutableLiveData<PoseFrame> poseFrame = new MutableLiveData<>();
    private final MutableLiveData<Float> liveScore = new MutableLiveData<>(-1f);
    private final MutableLiveData<TennisPoseValidator.ValidationState> validationState = new MutableLiveData<>();
    private final MutableLiveData<SequenceState> sequenceState = new MutableLiveData<>(SequenceState.IDLE);
    private final MutableLiveData<Event<TennisPoseValidator.ValidationState>> stuckEvent = new MutableLiveData<>();

    private final TennisPoseResult[] phaseResults = new TennisPoseResult[TennisPhase.count()];
    private volatile Pose candidatePose;
    private volatile int candidateImageWidth;
    private volatile int candidateImageHeight;
    private volatile boolean hasCandidate;
    private volatile boolean wasValidLastFrame;
    private volatile long phaseStartTimeMs;
    private volatile boolean stuckNotified;

    @Nullable
    private TennisStrokeResult strokeResult;

    public TennisCameraViewModel(@NonNull Application application) {
        super(application);
    }

    // ── Exposed state ────────────────────────────────────────────────────

    public LiveData<TennisFormMode> getMode() { return mode; }
    public LiveData<TennisPhase> getCurrentPhase() { return currentPhase; }
    public LiveData<PoseFrame> getPoseFrame() { return poseFrame; }
    public LiveData<Float> getLiveScore() { return liveScore; }
    public LiveData<TennisPoseValidator.ValidationState> getValidationState() { return validationState; }
    public LiveData<SequenceState> getSequenceState() { return sequenceState; }

    /** Fires once whenever the current checkpoint has gone unrecognized for
     *  too long, so the UI can show a prominent error popup instead of
     *  relying on the small hint text alone. */
    public LiveData<Event<TennisPoseValidator.ValidationState>> getStuckEvent() { return stuckEvent; }

    @Nullable
    public TennisStrokeResult getStrokeResult() { return strokeResult; }

    // ── Controls (call from the main/UI thread) ─────────────────────────

    public synchronized void setMode(@NonNull TennisFormMode m) {
        resetSequence();
        mode.setValue(m);
    }

    public synchronized void startSequence() {
        Arrays.fill(phaseResults, null);
        hasCandidate = false;
        candidatePose = null;
        wasValidLastFrame = false;
        strokeResult = null;
        currentPhase.setValue(TennisPhase.READY);
        sequenceState.setValue(SequenceState.IN_PROGRESS);
        liveScore.setValue(-1f);
        armStuckTimer();
    }

    public synchronized void resetSequence() {
        Arrays.fill(phaseResults, null);
        hasCandidate = false;
        candidatePose = null;
        wasValidLastFrame = false;
        strokeResult = null;
        currentPhase.setValue(TennisPhase.READY);
        sequenceState.setValue(SequenceState.IDLE);
        liveScore.setValue(-1f);
        stuckNotified = true; // no active phase to report as stuck
    }

    /** (Re)starts the "has this checkpoint gone un-detected too long?" timer,
     *  called whenever a fresh checkpoint begins. */
    private void armStuckTimer() {
        phaseStartTimeMs = System.currentTimeMillis();
        stuckNotified = false;
    }

    /** Manual escape hatch: locks the current checkpoint now, using the best
     *  candidate seen so far, or a neutral placeholder if none validated. */
    public synchronized void skipCurrentPhase() {
        if (sequenceState.getValue() != SequenceState.IN_PROGRESS) return;
        lockCurrentPhase(true);
    }

    // ── Frame feed (call from the camera analyzer's background thread) ──

    public void onPoseAnalyzed(Pose pose, int imgW, int imgH) {
        poseFrame.postValue(new PoseFrame(pose, imgW, imgH));

        TennisFormMode m = mode.getValue();
        if (sequenceState.getValue() != SequenceState.IN_PROGRESS || m == null) {
            return;
        }
        TennisPhase phase = currentPhase.getValue();
        if (phase == null) return;

        TennisPoseValidator.ValidationState vs = TennisPoseValidator.validate(pose, m, phase);
        validationState.postValue(vs);

        if (vs == TennisPoseValidator.ValidationState.VALID) {
            liveScore.postValue(TennisFormScorer.quickScore(pose, m, phase));
            candidatePose = pose;
            candidateImageWidth = imgW;
            candidateImageHeight = imgH;
            hasCandidate = true;
            wasValidLastFrame = true;
        } else {
            liveScore.postValue(-1f);
            if (wasValidLastFrame && hasCandidate) {
                lockCurrentPhase(false);
            }
            wasValidLastFrame = false;

            if (!stuckNotified && (System.currentTimeMillis() - phaseStartTimeMs) > STUCK_THRESHOLD_MS) {
                stuckNotified = true;
                stuckEvent.postValue(new Event<>(vs));
            }
        }
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private synchronized void lockCurrentPhase(boolean allowNeutralFallback) {
        TennisPhase phase = currentPhase.getValue();
        TennisFormMode m = mode.getValue();
        if (phase == null || m == null) return;

        TennisPoseResult result = null;
        if (hasCandidate && candidatePose != null) {
            result = TennisFormScorer.score(getApplication(), candidatePose, m, phase,
                    candidateImageWidth, candidateImageHeight);
        }
        if (result == null) {
            if (!allowNeutralFallback) return; // keep waiting for a usable candidate
            result = neutralResult(getApplication(), m, phase);
        }

        phaseResults[phase.getIndex()] = result;
        hasCandidate = false;
        candidatePose = null;
        advancePhase(m, phase);
    }

    private void advancePhase(TennisFormMode m, TennisPhase justLocked) {
        if (justLocked == TennisPhase.FINISH) {
            strokeResult = TennisStrokeResult.combine(m, phaseResults.clone());
            // Show a brief "diagnosing" state before COMPLETE so the UI can
            // present a processing indicator rather than jumping straight
            // to the result screen with no feedback. The delayed post must
            // run on the main thread (LiveData.setValue requirement) even
            // though onPoseAnalyzed/advancePhase run on the camera's
            // background analyzer thread.
            sequenceState.postValue(SequenceState.PROCESSING);
            new Handler(Looper.getMainLooper()).postDelayed(
                    () -> sequenceState.setValue(SequenceState.COMPLETE),
                    PROCESSING_DISPLAY_MS);
        } else {
            currentPhase.postValue(TennisPhase.fromIndex(justLocked.getIndex() + 1));
            armStuckTimer();
        }
    }

    /** Placeholder used when a checkpoint is skipped without ever validating.
     *  No candidate frame ever existed, so there's nothing to snapshot for
     *  the result screen's skeleton comparison. */
    private static TennisPoseResult neutralResult(Context context, TennisFormMode mode, TennisPhase phase) {
        return new TennisPoseResult(
                mode, phase,
                new TennisCoachModel.CriterionId[0],
                new float[0], new float[0], new float[0],
                50f,
                new ArrayList<>(),
                new ArrayList<>(Collections.singletonList(context.getString(R.string.phase_not_detected))),
                null);
    }
}
