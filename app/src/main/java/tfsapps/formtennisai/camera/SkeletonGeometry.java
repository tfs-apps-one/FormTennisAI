package tfsapps.formtennisai.camera;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PointF;

import androidx.annotation.Nullable;

import com.google.mlkit.vision.pose.PoseLandmark;

import tfsapps.formtennisai.model.TennisCoachModel;
import tfsapps.formtennisai.model.TennisCoachModel.Criterion;
import tfsapps.formtennisai.model.TennisFormMode;
import tfsapps.formtennisai.model.TennisPhase;

/**
 * Shared skeleton-drawing math used by both {@link TennisPoseOverlayView}
 * (the live camera overlay) and {@code TennisResultSkeletonView} (the
 * static "your form vs. ideal form" comparison on the result screen) —
 * pulled out into one place so the two views can never quietly drift apart
 * on how a correction is computed.
 *
 * Everything here works against view-space (already-scaled) {@link PointF}
 * coordinates, fetched through the small {@link LandmarkLookup} interface
 * rather than a live MLKit {@code Pose} directly, so the exact same code
 * runs whether the source is a live analyzed frame or a
 * {@code TennisPoseResult.LandmarkSnapshot} replayed from a finished
 * result.
 */
public final class SkeletonGeometry {

    private SkeletonGeometry() { }

    /** Skeleton bone connections drawn for the user's own pose. */
    public static final int[][] CONNECTIONS = {
            {PoseLandmark.LEFT_SHOULDER,  PoseLandmark.RIGHT_SHOULDER},
            {PoseLandmark.LEFT_SHOULDER,  PoseLandmark.LEFT_HIP},
            {PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_HIP},
            {PoseLandmark.LEFT_HIP,       PoseLandmark.RIGHT_HIP},
            {PoseLandmark.LEFT_SHOULDER,  PoseLandmark.LEFT_ELBOW},
            {PoseLandmark.LEFT_ELBOW,     PoseLandmark.LEFT_WRIST},
            {PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW},
            {PoseLandmark.RIGHT_ELBOW,    PoseLandmark.RIGHT_WRIST},
            {PoseLandmark.LEFT_HIP,       PoseLandmark.LEFT_KNEE},
            {PoseLandmark.LEFT_KNEE,      PoseLandmark.LEFT_ANKLE},
            {PoseLandmark.RIGHT_HIP,      PoseLandmark.RIGHT_KNEE},
            {PoseLandmark.RIGHT_KNEE,     PoseLandmark.RIGHT_ANKLE},
            {PoseLandmark.LEFT_SHOULDER,  PoseLandmark.NOSE},
            {PoseLandmark.RIGHT_SHOULDER, PoseLandmark.NOSE},
    };

    /** Every distinct landmark type referenced by {@link #CONNECTIONS}, for
     *  drawing joint dots without needing "all landmarks in the pose". */
    public static final int[] JOINT_TYPES;
    static {
        java.util.LinkedHashSet<Integer> set = new java.util.LinkedHashSet<>();
        for (int[] conn : CONNECTIONS) {
            set.add(conn[0]);
            set.add(conn[1]);
        }
        JOINT_TYPES = new int[set.size()];
        int i = 0;
        for (int t : set) JOINT_TYPES[i++] = t;
    }

    /** Resolves a landmark type to its already view-scaled position, or
     *  null if that landmark isn't confidently visible right now. */
    public interface LandmarkLookup {
        @Nullable PointF get(int landmarkType);
    }

    // ── User skeleton ────────────────────────────────────────────────────

    public static void drawUserSkeleton(Canvas canvas, LandmarkLookup lm, Paint bonePaint, Paint dotPaint) {
        for (int[] conn : CONNECTIONS) {
            PointF p1 = lm.get(conn[0]);
            PointF p2 = lm.get(conn[1]);
            if (p1 != null && p2 != null) canvas.drawLine(p1.x, p1.y, p2.x, p2.y, bonePaint);
        }
        for (int type : JOINT_TYPES) {
            PointF p = lm.get(type);
            if (p != null) canvas.drawCircle(p.x, p.y, 8f, dotPaint);
        }
    }

    // ── Coach skeleton (phase-aware) ─────────────────────────────────────

    /**
     * Draws the current checkpoint's ideal joint/line position(s) in cyan,
     * anchored to whatever {@code lm} resolves each landmark to — mirrors
     * exactly which joints {@code TennisFormScorer.measure()} reads for
     * each {@link TennisCoachModel.CriterionId}, so "what's drawn" always
     * matches "what's scored".
     *
     * A few criteria (the "unit turn" shoulder/hip-twist checks, and the
     * forehand impact-point lateral position) don't reduce to a single
     * correctable bone in an anatomically honest way, so no cyan
     * correction is drawn for those specific components.
     */
    public static void drawCoachSkeleton(Canvas canvas, TennisFormMode mode, TennisPhase phase,
                                          LandmarkLookup lm, Paint bonePaint, Paint dotPaint) {
        Criterion[] criteria;
        try {
            criteria = TennisCoachModel.criteriaFor(mode, phase);
        } catch (IllegalArgumentException e) {
            return;
        }
        for (Criterion c : criteria) {
            drawCoachForCriterion(canvas, lm, bonePaint, dotPaint, c);
        }
    }

    private static void drawCoachForCriterion(Canvas canvas, LandmarkLookup lm, Paint bonePaint, Paint dotPaint, Criterion c) {
        switch (c.id) {
            case SERVE_TROPHY_ELBOW:
                drawLevelCorrection(canvas, lm, bonePaint, dotPaint,
                        PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, c.ideal);
                break;
            case SERVE_TROPHY_KNEE:
                drawJointCorrection(canvas, lm, bonePaint, dotPaint,
                        PoseLandmark.LEFT_HIP, PoseLandmark.LEFT_KNEE, PoseLandmark.LEFT_ANKLE, c.ideal);
                break;
            case SERVE_IMPACT_ELBOW:
                drawJointCorrection(canvas, lm, bonePaint, dotPaint,
                        PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST, c.threshold);
                break;
            case SERVE_IMPACT_TILT:
                drawTorsoLeanCorrection(canvas, lm, bonePaint, dotPaint, c.ideal);
                break;
            case SERVE_FINISH_ELBOW:
                drawJointCorrection(canvas, lm, bonePaint, dotPaint,
                        PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST, c.ideal);
                break;
            case SERVE_FINISH_CROSS:
                drawSignedLineCorrection(canvas, lm, bonePaint, dotPaint,
                        PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_WRIST, c.ideal);
                break;
            case FH_IMPACT_ELBOW:
                drawJointCorrection(canvas, lm, bonePaint, dotPaint,
                        PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST, c.ideal);
                break;
            case FH_FINISH_SWING:
                drawJointCorrection(canvas, lm, bonePaint, dotPaint,
                        PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_WRIST, c.threshold);
                break;
            case BH2_IMPACT_ELBOW:
                drawJointCorrection(canvas, lm, bonePaint, dotPaint,
                        PoseLandmark.LEFT_SHOULDER, PoseLandmark.LEFT_ELBOW, PoseLandmark.LEFT_WRIST, c.ideal);
                drawJointCorrection(canvas, lm, bonePaint, dotPaint,
                        PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST, c.ideal);
                break;
            case BH2_FINISH_CHEST:
                drawShoulderRotationCorrection(canvas, lm, bonePaint, dotPaint, c.ideal);
                break;
            case BH1_IMPACT_CHEST:
                drawShoulderRotationCorrection(canvas, lm, bonePaint, dotPaint, c.threshold);
                break;
            case BH1_IMPACT_ELBOW_LOCK:
                drawJointCorrection(canvas, lm, bonePaint, dotPaint,
                        PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_ELBOW, PoseLandmark.RIGHT_WRIST, c.ideal);
                break;
            case BH1_FINISH_SWING:
                drawJointCorrection(canvas, lm, bonePaint, dotPaint,
                        PoseLandmark.RIGHT_HIP, PoseLandmark.RIGHT_SHOULDER, PoseLandmark.RIGHT_WRIST, c.threshold);
                break;
            case READY_BALANCE:
            case FH_TURN_TWIST:
            case BH2_TURN_LOWER:
            case BH1_TURN_TWIST:
            case FH_IMPACT_FRONT:
            default:
                break;
        }
    }

    private static void drawJointCorrection(Canvas canvas, LandmarkLookup lm, Paint bonePaint, Paint dotPaint,
                                             int typeA, int typeB, int typeC, float idealDeg) {
        PointF a = lm.get(typeA);
        PointF b = lm.get(typeB);
        PointF c = lm.get(typeC);
        if (a == null || b == null) return;
        PointF target = coachEndpoint(a, b, c, idealDeg);
        if (target == null) return;
        drawLine(canvas, bonePaint, a, b);
        drawLine(canvas, bonePaint, b, target);
        drawDot(canvas, dotPaint, target);
    }

    private static void drawLevelCorrection(Canvas canvas, LandmarkLookup lm, Paint bonePaint, Paint dotPaint,
                                             int typeProximal, int typeDistal, float idealDeg) {
        PointF p = lm.get(typeProximal);
        PointF d = lm.get(typeDistal);
        if (p == null || d == null) return;
        float len = dist(p, d);
        float dirX = (d.x >= p.x) ? 1f : -1f;
        float dirY = (d.y >= p.y) ? 1f : -1f;
        float rad = (float) Math.toRadians(idealDeg);
        PointF target = new PointF(
                p.x + dirX * len * (float) Math.cos(rad),
                p.y + dirY * len * (float) Math.sin(rad));
        drawLine(canvas, bonePaint, p, target);
        drawDot(canvas, dotPaint, target);
    }

    private static void drawTorsoLeanCorrection(Canvas canvas, LandmarkLookup lm, Paint bonePaint, Paint dotPaint, float idealDeg) {
        PointF lHip = lm.get(PoseLandmark.LEFT_HIP);
        PointF rHip = lm.get(PoseLandmark.RIGHT_HIP);
        PointF lSh = lm.get(PoseLandmark.LEFT_SHOULDER);
        PointF rSh = lm.get(PoseLandmark.RIGHT_SHOULDER);
        if (lHip == null || rHip == null || lSh == null || rSh == null) return;
        PointF hipMid = new PointF((lHip.x + rHip.x) / 2f, (lHip.y + rHip.y) / 2f);
        PointF shMid = new PointF((lSh.x + rSh.x) / 2f, (lSh.y + rSh.y) / 2f);
        float len = dist(hipMid, shMid);
        float dirX = (shMid.x >= hipMid.x) ? 1f : -1f;
        float rad = (float) Math.toRadians(idealDeg);
        PointF target = new PointF(
                hipMid.x + dirX * len * (float) Math.sin(rad),
                hipMid.y - len * (float) Math.cos(rad));
        drawLine(canvas, bonePaint, hipMid, target);
        drawDot(canvas, dotPaint, target);
    }

    private static void drawSignedLineCorrection(Canvas canvas, LandmarkLookup lm, Paint bonePaint, Paint dotPaint,
                                                  int typeA, int typeB, float idealDeg) {
        PointF a = lm.get(typeA);
        PointF b = lm.get(typeB);
        if (a == null || b == null) return;
        float len = dist(a, b);
        float rad = (float) Math.toRadians(idealDeg);
        PointF target = new PointF(
                a.x + len * (float) Math.cos(rad),
                a.y + len * (float) Math.sin(rad));
        drawLine(canvas, bonePaint, a, target);
        drawDot(canvas, dotPaint, target);
    }

    private static void drawShoulderRotationCorrection(Canvas canvas, LandmarkLookup lm, Paint bonePaint, Paint dotPaint, float idealDeg) {
        PointF lSh = lm.get(PoseLandmark.LEFT_SHOULDER);
        PointF rSh = lm.get(PoseLandmark.RIGHT_SHOULDER);
        if (lSh == null || rSh == null) return;
        PointF mid = new PointF((lSh.x + rSh.x) / 2f, (lSh.y + rSh.y) / 2f);
        float halfLen = dist(lSh, rSh) / 2f;
        float actualRad = (float) Math.atan2(rSh.y - lSh.y, rSh.x - lSh.x);
        float sign = (actualRad >= 0) ? 1f : -1f;
        float rad = sign * (float) Math.toRadians(idealDeg);
        PointF p1 = new PointF(mid.x - halfLen * (float) Math.cos(rad), mid.y - halfLen * (float) Math.sin(rad));
        PointF p2 = new PointF(mid.x + halfLen * (float) Math.cos(rad), mid.y + halfLen * (float) Math.sin(rad));
        drawLine(canvas, bonePaint, p1, p2);
        drawDot(canvas, dotPaint, p1);
        drawDot(canvas, dotPaint, p2);
    }

    private static void drawLine(Canvas canvas, Paint paint, @Nullable PointF a, @Nullable PointF b) {
        if (a != null && b != null) canvas.drawLine(a.x, a.y, b.x, b.y, paint);
    }

    private static void drawDot(Canvas canvas, Paint paint, @Nullable PointF p) {
        if (p != null) canvas.drawCircle(p.x, p.y, 11f, paint);
    }

    /**
     * Given bone A→B as the proximal segment, returns the coach's ideal
     * position for endpoint C such that the interior angle at B equals
     * idealDeg — the same trick baseball's PoseOverlayView uses for its 3
     * fixed joints, generalized here to any (A, B, C) triple.
     */
    @Nullable
    private static PointF coachEndpoint(PointF a, PointF b, @Nullable PointF cActual, float idealDeg) {
        float abAngle = (float) Math.atan2(b.y - a.y, b.x - a.x);

        float bcLen;
        float actualBCAngle;
        if (cActual != null) {
            float dx = cActual.x - b.x;
            float dy = cActual.y - b.y;
            bcLen = (float) Math.sqrt(dx * dx + dy * dy);
            actualBCAngle = (float) Math.atan2(dy, dx);
        } else {
            float dx = b.x - a.x;
            float dy = b.y - a.y;
            bcLen = (float) Math.sqrt(dx * dx + dy * dy);
            actualBCAngle = abAngle + (float) Math.PI * 0.75f;
        }
        if (bcLen < 1e-3f) return null;

        float baAngle = abAngle + (float) Math.PI;
        float idealRad = (float) Math.toRadians(idealDeg);

        float candidate1 = baAngle + idealRad;
        float candidate2 = baAngle - idealRad;

        float chosenAngle = (angleDiff(candidate1, actualBCAngle) <= angleDiff(candidate2, actualBCAngle))
                ? candidate1 : candidate2;

        return new PointF(
                b.x + bcLen * (float) Math.cos(chosenAngle),
                b.y + bcLen * (float) Math.sin(chosenAngle));
    }

    private static float angleDiff(float a, float b) {
        float d = Math.abs(a - b) % (2f * (float) Math.PI);
        return (d > Math.PI) ? 2f * (float) Math.PI - d : d;
    }

    private static float dist(PointF a, PointF b) {
        float dx = a.x - b.x, dy = a.y - b.y;
        return (float) Math.sqrt(dx * dx + dy * dy);
    }
}
