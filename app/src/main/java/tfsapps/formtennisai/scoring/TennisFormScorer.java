package tfsapps.formtennisai.scoring;

import android.content.Context;
import android.graphics.PointF;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import tfsapps.formtennisai.R;
import tfsapps.formtennisai.analyzer.TennisPoseValidator;
import tfsapps.formtennisai.model.TennisCoachModel;
import tfsapps.formtennisai.model.TennisCoachModel.Criterion;
import tfsapps.formtennisai.model.TennisCoachModel.CriterionId;
import tfsapps.formtennisai.model.TennisFormMode;
import tfsapps.formtennisai.model.TennisPhase;
import tfsapps.formtennisai.model.TennisPoseResult;

/**
 * Compares a detected {@link Pose} against {@link TennisCoachModel}'s ideal
 * values for one {@link TennisPhase} checkpoint of a {@link TennisFormMode}
 * stroke. Direct tennis counterpart of
 * {@code tfsapps.formbaseballai.scoring.FormScorer}.
 *
 * ── What's the same as FormScorer ──────────────────────────────────────
 *   • The same green / yellow / floor scoring shape (SCORE_FLOOR = 20,
 *     never punish a single frame to zero — an elite player still measures
 *     badly if captured at the wrong instant).
 *   • The same two-entry-point split: {@link #quickScore} for a fast,
 *     allocation-light live-bar number, and {@link #score} for the full,
 *     locale-aware result used once the user captures a checkpoint.
 *   • {@link TennisPoseValidator} is always run first, exactly like
 *     FormScorer defers to PoseValidator before scoring anything.
 *   • A criterion whose landmark(s) aren't confidently visible contributes
 *     a neutral score (50) rather than a worst-case one — the same idea as
 *     FormScorer's "measured == 0f → neutral" fallback. It's implemented
 *     with a {@code NaN} sentinel here instead of FormScorer's literal
 *     {@code 0f}, because several tennis criteria (balance lean, unit-turn
 *     twist) are naturally centered on 0° — a real 0 must stay a real,
 *     scoreable 0, not be mistaken for "not visible".
 *
 * ── What's different, and why ──────────────────────────────────────────
 *   • FormScorer always scored the same fixed 5 components. TENNIS_SPEC.md
 *     instead gives each (mode, phase) checkpoint its own 1-2 criteria, so
 *     scoring here is driven by {@link TennisCoachModel#criteriaFor}
 *     rather than a hard-coded component list.
 *   • TENNIS_SPEC.md phrases some criteria as "±tolerance" targets (knee
 *     bend 130-150°) and others as "≥X" / "should not exceed X" thresholds
 *     (elbow extension ≥160°, shoulder/hip twist ≥20°, chest not too
 *     open). {@link #scoreRange}, {@link #scoreAtLeast} and
 *     {@link #scoreAtMost} cover all three shapes while keeping the same
 *     green/yellow/floor feel as baseball's single scoreComponent.
 *
 * ── Assumptions (documented once, here) ─────────────────────────────────
 *   • Right-handed player throughout. A left-handed toggle would mirror
 *     every "RIGHT_*" landmark reference below to "LEFT_*".
 *   • Camera angle differs by stroke (see {@link TennisPoseValidator}'s
 *     class javadoc for the full reasoning): SERVE is filmed side-on, the
 *     same reason a pitcher's throwing motion is filmed from the side, so
 *     every SERVE measurement below is either a single limb's 2D joint
 *     angle or a tilt/lean from horizontal/vertical — none of it depends
 *     on left/right (X-axis) separation, so it reads the same from the
 *     side as it would from the front. FOREHAND/BACKHAND instead need the
 *     camera on the player's front-back axis — facing the player OR
 *     filming their back from behind both work (verified against real
 *     match footage filmed from behind), since either keeps the
 *     shoulders/hips' X-axis separation visible, which their twist and
 *     "impact position" measurements below rely on. Only a true side-on
 *     shot (the angle SERVE itself needs) breaks them.
 *   • A few TENNIS_SPEC.md checks (forearm pronation, precise torso twist)
 *     aren't directly observable from 2D body landmarks alone — ML Kit's
 *     Pose has no forearm-rotation or true-depth twist landmark. Those are
 *     approximated with the closest visible-skeleton proxy; each is called
 *     out at its measurement site below.
 */
public class TennisFormScorer {

    /** Per-component score floor — mirrors FormScorer.SCORE_FLOOR. */
    private static final float SCORE_FLOOR = TennisCoachModel.SCORE_FLOOR;

    /** ML Kit landmark confidence required before we trust a coordinate
     *  (same threshold FormScorer uses for angle estimation). */
    private static final float MIN_CONFIDENCE = 0.30f;

    /** Score at/above which a criterion is called out as a strength. */
    private static final float STRENGTH_CUTOFF = 80f;

    /** Score below which a criterion is called out as an improvement tip. */
    private static final float IMPROVE_CUTOFF = 60f;

    // ── quickScore ────────────────────────────────────────────────────────

    /**
     * Fast, allocation-light score used for a real-time live bar.
     * No Context needed, no string resources loaded.
     *
     * IMPACT is used as the representative checkpoint for the live bar
     * (as the moment every stroke in TENNIS_SPEC.md is judged most
     * heavily on), mirroring how baseball's quickScore judges a single
     * instant rather than a full multi-phase sequence.
     *
     * @return Weighted 0-100 score, 0 if no usable landmarks, or -1 if the
     *         validator rejects the frame (no human / wrong pose).
     */
    public static float quickScore(@NonNull Pose pose, @NonNull TennisFormMode mode) {
        return quickScore(pose, mode, TennisPhase.IMPACT);
    }

    public static float quickScore(@NonNull Pose pose, @NonNull TennisFormMode mode, @NonNull TennisPhase phase) {
        if (TennisPoseValidator.validate(pose, mode, phase) != TennisPoseValidator.ValidationState.VALID) return -1f;

        Criterion[] criteria = TennisCoachModel.criteriaFor(mode, phase);
        float[] measured = measure(pose, mode, phase);
        if (allMissing(measured)) return 0f;

        float overall = 0f;
        for (int i = 0; i < criteria.length; i++) {
            // A NaN reading means its landmark(s) weren't confidently
            // visible (see getPoint's MIN_CONFIDENCE gate) — same
            // neutral-fallback idea as baseball's FormScorer, see class
            // javadoc for why NaN is used here instead of a literal 0f.
            float s = Float.isNaN(measured[i]) ? 50f : scoreFor(criteria[i], measured[i]);
            overall += s * criteria[i].weight;
        }
        return overall;
    }

    // ── score (full result for one checkpoint) ───────────────────────────

    /**
     * Full analysis with locale-aware feedback strings for one checkpoint.
     * Call this when the user captures a phase — not every frame.
     *
     * @param imageWidth  Width of the analyzed frame {@code pose} came from,
     *                    used only to build the result's landmark snapshot
     *                    (see {@link TennisPoseResult.LandmarkSnapshot}) for
     *                    the result screen's skeleton comparison.
     * @param imageHeight Height of that same frame.
     * @return {@link TennisPoseResult}, or null if the checkpoint isn't
     *         valid yet (no human / wrong pose / no usable landmarks).
     */
    @Nullable
    public static TennisPoseResult score(
            @NonNull Context context,
            @NonNull Pose pose,
            @NonNull TennisFormMode mode,
            @NonNull TennisPhase phase,
            int imageWidth,
            int imageHeight) {

        if (TennisPoseValidator.validate(pose, mode, phase) != TennisPoseValidator.ValidationState.VALID) return null;

        Criterion[] criteria = TennisCoachModel.criteriaFor(mode, phase);
        float[] measured = measure(pose, mode, phase);
        if (allMissing(measured)) return null;

        CriterionId[] ids = new CriterionId[criteria.length];
        float[] scores = new float[criteria.length];
        float[] targets = new float[criteria.length];
        float overall = 0f;

        for (int i = 0; i < criteria.length; i++) {
            ids[i] = criteria[i].id;
            // See quickScore(): a NaN reading is treated as "landmark not
            // confidently visible" and scored neutrally rather than as a
            // worst-case deviation.
            scores[i] = Float.isNaN(measured[i]) ? 50f : scoreFor(criteria[i], measured[i]);
            targets[i] = criteria[i].displayTarget();
            overall += scores[i] * criteria[i].weight;
        }

        List<String> strengths = new ArrayList<>();
        List<String> improvements = new ArrayList<>();
        collectFeedback(context, criteria, scores, measured, strengths, improvements);

        TennisPoseResult.LandmarkSnapshot snapshot = captureSnapshot(pose, imageWidth, imageHeight);

        return new TennisPoseResult(mode, phase, ids, scores, measured, targets, overall, strengths, improvements, snapshot);
    }

    /** Captures every detected landmark's 2D position + confidence into a
     *  Serializable snapshot the result screen can redraw later — a raw
     *  MLKit {@link Pose} can't itself cross the Intent extra the result
     *  travels over. */
    private static TennisPoseResult.LandmarkSnapshot captureSnapshot(Pose pose, int imageWidth, int imageHeight) {
        List<PoseLandmark> all = pose.getAllPoseLandmarks();
        int[] types = new int[all.size()];
        float[] xs = new float[all.size()];
        float[] ys = new float[all.size()];
        float[] confidences = new float[all.size()];
        for (int i = 0; i < all.size(); i++) {
            PoseLandmark lm = all.get(i);
            types[i] = lm.getLandmarkType();
            xs[i] = lm.getPosition().x;
            ys[i] = lm.getPosition().y;
            confidences[i] = lm.getInFrameLikelihood();
        }
        return new TennisPoseResult.LandmarkSnapshot(types, xs, ys, confidences, imageWidth, imageHeight);
    }

    /**
     * Populates {@code strengths} / {@code improvements} in the exact same
     * "%.0f / %.0f → %.0f" style baseball's FormScorer used, generalized to
     * whichever unit ("°" or "%") the criterion declares. Criteria whose
     * landmark wasn't visible (measured is NaN) are skipped entirely —
     * there's nothing honest to tell the user about a check that couldn't
     * be made, so it's left out rather than printed as "NaN°".
     */
    private static void collectFeedback(
            Context context, Criterion[] criteria, float[] scores, float[] measured,
            List<String> strengths, List<String> improvements) {

        for (int i = 0; i < criteria.length; i++) {
            if (Float.isNaN(measured[i])) continue;
            Criterion c = criteria[i];
            if (scores[i] >= STRENGTH_CUTOFF) {
                strengths.add(context.getString(c.id.strengthRes) +
                        String.format(Locale.getDefault(), " (%.0f%s)", measured[i], c.unit));
            } else if (scores[i] < IMPROVE_CUTOFF) {
                improvements.add(context.getString(c.id.improveRes) +
                        String.format(Locale.getDefault(), " (%.0f%s → %.0f%s)",
                                measured[i], c.unit, c.displayTarget(), c.unit));
            }
        }

        if (strengths.isEmpty())
            strengths.add(context.getString(R.string.tennis_feedback_keep_practicing));
        if (improvements.isEmpty())
            improvements.add(context.getString(R.string.tennis_feedback_fine_tune));
    }

    // ── Scoring formulas ─────────────────────────────────────────────────

    private static float scoreFor(Criterion c, float measured) {
        switch (c.type) {
            case RANGE:    return scoreRange(measured, c.ideal, c.tolerance);
            case AT_LEAST: return scoreAtLeast(measured, c.threshold, c.softRange);
            case AT_MOST:  return scoreAtMost(measured, c.threshold, c.softRange);
        }
        return SCORE_FLOOR;
    }

    /**
     * Target-centered scoring — identical shape to baseball's
     * FormScorer.scoreComponent:
     *   dev ≤ tolerance         → 100  (green)
     *   tolerance < dev ≤ 4×tol → linear 100 → SCORE_FLOOR (yellow)
     *   dev > 4×tolerance       → SCORE_FLOOR (floor, never 0)
     */
    private static float scoreRange(float measured, float ideal, float tolerance) {
        float dev = Math.abs(measured - ideal);
        if (dev <= tolerance) return 100f;
        float maxDev = tolerance * 4f;
        if (dev >= maxDev) return SCORE_FLOOR;
        float ratio = (dev - tolerance) / (maxDev - tolerance);
        return 100f - ratio * (100f - SCORE_FLOOR);
    }

    /**
     * "≥ threshold" scoring (e.g. serve elbow extension ≥160°, unit-turn
     * twist ≥20°):
     *   measured ≥ threshold                       → 100  (green)
     *   threshold-softRange ≤ measured < threshold  → linear 100 → SCORE_FLOOR
     *   measured < threshold-softRange              → SCORE_FLOOR (floor, never 0)
     */
    private static float scoreAtLeast(float measured, float threshold, float softRange) {
        if (measured >= threshold) return 100f;
        float floorEdge = threshold - softRange;
        if (measured <= floorEdge) return SCORE_FLOOR;
        float ratio = (threshold - measured) / softRange;
        return 100f - ratio * (100f - SCORE_FLOOR);
    }

    /**
     * "≤ threshold" scoring — mirror image of scoreAtLeast, for specs
     * phrased as an upper bound (e.g. one-handed backhand: chest must not
     * open past a given angle at impact).
     */
    private static float scoreAtMost(float measured, float threshold, float softRange) {
        if (measured <= threshold) return 100f;
        float floorEdge = threshold + softRange;
        if (measured >= floorEdge) return SCORE_FLOOR;
        float ratio = (measured - threshold) / softRange;
        return 100f - ratio * (100f - SCORE_FLOOR);
    }

    // ── Measurement: (mode, phase) → float[] in TennisCoachModel's order ───
    // Every element is either a real measured value or Float.NaN, meaning
    // "the landmark(s) this criterion needs weren't confidently visible".

    private static float[] measure(Pose pose, TennisFormMode mode, TennisPhase phase) {
        if (phase == TennisPhase.READY) {
            return new float[]{ readyBalance(pose) };
        }
        switch (mode) {
            case SERVE:
                switch (phase) {
                    case TURN:   return serveTurn(pose);
                    case IMPACT: return serveImpact(pose);
                    case FINISH: return serveFinish(pose);
                    default: break;
                }
                break;
            case FOREHAND:
                switch (phase) {
                    case TURN:   return new float[]{ shoulderHipTwist(pose) };
                    case IMPACT: return forehandImpact(pose);
                    case FINISH: return new float[]{ swingThroughAngle(pose) };
                    default: break;
                }
                break;
            case BACKHAND_TWO_HANDED:
                switch (phase) {
                    case TURN:   return new float[]{ shoulderHipTwist(pose) };
                    case IMPACT: return new float[]{ bothElbowAverage(pose) };
                    case FINISH: return new float[]{ shoulderRotationMagnitude(pose) };
                    default: break;
                }
                break;
            case BACKHAND_ONE_HANDED:
                switch (phase) {
                    case TURN:   return new float[]{ shoulderHipTwist(pose) };
                    case IMPACT: return backhandOneHandedImpact(pose);
                    case FINISH: return new float[]{ swingThroughAngle(pose) };
                    default: break;
                }
                break;
        }
        float[] fallback = new float[TennisCoachModel.criteriaFor(mode, phase).length];
        java.util.Arrays.fill(fallback, Float.NaN);
        return fallback;
    }

    // ── SERVE measurements (filmed side-on — see class javadoc) ──────────

    private static float[] serveTurn(Pose pose) {
        // Upper-arm tilt from horizontal: 0° means the shoulder-to-elbow
        // line is parallel to the ground, i.e. level with the shoulder line.
        float elbowLine = tiltFromHorizontal(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW);
        // Baseball's own leadKnee check always used the LEFT leg regardless
        // of pitcher handedness; kept the same simplification here.
        float knee = angleAt(pose, PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE);
        return new float[]{ elbowLine, knee };
    }

    private static float[] serveImpact(Pose pose) {
        float elbowExtension = angleAt(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST);
        float torsoTilt = leanFromVertical(pose,
                PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER);
        return new float[]{ elbowExtension, torsoTilt };
    }

    private static float[] serveFinish(Pose pose) {
        float elbowRelax = angleAt(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST);
        // Pronation itself isn't visible from body landmarks; as a proxy we
        // check that the hitting wrist has crossed down and across toward
        // the opposite shoulder, which is the visible signature of a
        // completed pronating follow-through. Of every SERVE measurement
        // here, this is the one most sensitive to exactly which side of
        // the player the camera is placed for the side-on shot (it flips
        // sign if the camera is on the other side) — TennisCoachModel's
        // SERVE_FINISH_CROSS target (135°) assumes the same camera side
        // consistently used elsewhere in this file's original calibration,
        // so it's worth re-checking against real footage if the finish
        // score looks consistently off in one direction.
        float crossBody = lineAngleSigned(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_WRIST);
        return new float[]{ elbowRelax, crossBody };
    }

    // ── FOREHAND / shared groundstroke measurements ─────────────────────

    private static float readyBalance(Pose pose) {
        return leanFromVertical(pose,
                PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER);
    }

    /** |shoulder-line angle − hip-line angle|, folded into [0,90]. Used for
     *  every "unit turn / twist" criterion (forehand, both backhands). */
    private static float shoulderHipTwist(Pose pose) {
        Float shoulderFold = lineAngleFold(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER);
        Float hipFold = lineAngleFold(pose, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP);
        if (shoulderFold == null || hipFold == null) return Float.NaN;
        float diff = Math.abs(shoulderFold - hipFold);
        return diff > 90f ? 180f - diff : diff;
    }

    private static float[] forehandImpact(Pose pose) {
        PointF wrist = getPoint(pose, PoseLandmark.RIGHT_WRIST);
        PointF lHip = getPoint(pose, PoseLandmark.LEFT_HIP);
        PointF rHip = getPoint(pose, PoseLandmark.RIGHT_HIP);
        PointF lSh = getPoint(pose, PoseLandmark.LEFT_SHOULDER);
        PointF rSh = getPoint(pose, PoseLandmark.RIGHT_SHOULDER);
        float front = Float.NaN;
        if (wrist != null && lHip != null && rHip != null && lSh != null && rSh != null) {
            float hipMidX = (lHip.x + rHip.x) * 0.5f;
            float shoulderWidth = Math.abs(lSh.x - rSh.x);
            if (shoulderWidth > 1e-3f) {
                front = (wrist.x - hipMidX) / shoulderWidth * 100f;
            }
        }
        float elbowSoft = angleAt(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST);
        return new float[]{ front, elbowSoft };
    }

    /** Angle at the shoulder between hip and wrist — grows as the arm
     *  swings up and around toward "over the shoulder". */
    private static float swingThroughAngle(Pose pose) {
        return angleAt(pose, PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_WRIST);
    }

    // ── BACKHAND (two-handed) measurements ──────────────────────────────

    private static float bothElbowAverage(Pose pose) {
        float l = angleAt(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST);
        float r = angleAt(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST);
        boolean lOk = !Float.isNaN(l), rOk = !Float.isNaN(r);
        if (lOk && rOk) return (l + r) * 0.5f;
        if (lOk) return l;
        if (rOk) return r;
        return Float.NaN;
    }

    /** Magnitude of shoulder-line rotation, used as the "chest direction at
     *  finish" signal for the two-handed backhand. */
    private static float shoulderRotationMagnitude(Pose pose) {
        Float fold = lineAngleFold(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER);
        return fold == null ? Float.NaN : Math.abs(fold);
    }

    // ── BACKHAND (one-handed) measurements ──────────────────────────────

    private static float[] backhandOneHandedImpact(Pose pose) {
        // Same "how much has the chest turned" signal as the two-handed
        // finish check, applied at impact: a smaller value means the torso
        // is still relatively closed (not over-rotated toward the target).
        float chestOpen = shoulderRotationMagnitude(pose);
        float elbowLock = angleAt(pose, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST);
        return new float[]{ chestOpen, elbowLock };
    }

    // ── Geometry helpers ─────────────────────────────────────────────────
    // All return Float.NaN (not 0f) when a required landmark isn't
    // confidently visible — see the class javadoc for why 0 must stay a
    // real, scoreable value for several of these metrics.

    /** Interior angle (degrees) at B in A-B-C; NaN if any landmark is absent/low-conf. */
    private static float angleAt(Pose pose, int typeA, int typeB, int typeC) {
        PointF a = getPoint(pose, typeA);
        PointF b = getPoint(pose, typeB);
        PointF c = getPoint(pose, typeC);
        if (a == null || b == null || c == null) return Float.NaN;

        float bax = a.x - b.x, bay = a.y - b.y;
        float bcx = c.x - b.x, bcy = c.y - b.y;
        float dot   = bax * bcx + bay * bcy;
        float magBA = (float) Math.sqrt(bax * bax + bay * bay);
        float magBC = (float) Math.sqrt(bcx * bcx + bcy * bcy);
        if (magBA < 1e-6f || magBC < 1e-6f) return Float.NaN;
        return (float) Math.toDegrees(Math.acos(
                Math.max(-1f, Math.min(1f, dot / (magBA * magBC)))));
    }

    /** Deviation (0-90°) of line A→B from the horizontal axis. */
    private static float tiltFromHorizontal(Pose pose, int typeA, int typeB) {
        PointF a = getPoint(pose, typeA);
        PointF b = getPoint(pose, typeB);
        if (a == null || b == null) return Float.NaN;
        return (float) Math.toDegrees(Math.atan2(Math.abs(b.y - a.y), Math.abs(b.x - a.x)));
    }

    /** Lean (0-90°) of the shoulder-midpoint-to-hip-midpoint line from vertical. */
    private static float leanFromVertical(Pose pose, int lHipType, int rHipType, int lShType, int rShType) {
        PointF lHip = getPoint(pose, lHipType);
        PointF rHip = getPoint(pose, rHipType);
        PointF lSh = getPoint(pose, lShType);
        PointF rSh = getPoint(pose, rShType);
        if (lHip == null || rHip == null || lSh == null || rSh == null) return Float.NaN;
        float hipMidX = (lHip.x + rHip.x) * 0.5f, hipMidY = (lHip.y + rHip.y) * 0.5f;
        float shMidX  = (lSh.x + rSh.x) * 0.5f,   shMidY  = (lSh.y + rSh.y) * 0.5f;
        return (float) Math.toDegrees(Math.atan2(Math.abs(shMidX - hipMidX), Math.abs(shMidY - hipMidY)));
    }

    /** Orientation of line A-B folded into (-90, 90] — a line has no
     *  direction, so 10° and 190° describe the same tilt. Null if either
     *  landmark isn't confidently visible. */
    @Nullable
    private static Float lineAngleFold(Pose pose, int typeA, int typeB) {
        PointF a = getPoint(pose, typeA);
        PointF b = getPoint(pose, typeB);
        if (a == null || b == null) return null;
        float raw = (float) Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x));
        float folded = raw % 180f;
        if (folded > 90f) folded -= 180f;
        if (folded <= -90f) folded += 180f;
        return folded;
    }

    /** Full signed angle (-180,180] of line A→B, direction-sensitive
     *  (unlike lineAngleFold) — used where we need to know *which* way a
     *  limb points, not just the line's orientation. */
    private static float lineAngleSigned(Pose pose, int typeA, int typeB) {
        PointF a = getPoint(pose, typeA);
        PointF b = getPoint(pose, typeB);
        if (a == null || b == null) return Float.NaN;
        return (float) Math.toDegrees(Math.atan2(b.y - a.y, b.x - a.x));
    }

    @Nullable
    private static PointF getPoint(Pose pose, int type) {
        PoseLandmark lm = pose.getPoseLandmark(type);
        if (lm == null || lm.getInFrameLikelihood() < MIN_CONFIDENCE) return null;
        return lm.getPosition();
    }

    private static boolean allMissing(float[] values) {
        for (float v : values) if (!Float.isNaN(v)) return false;
        return true;
    }
}
