package tfsapps.formtennisai.analyzer;

import androidx.annotation.Nullable;

import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;

import tfsapps.formtennisai.model.TennisFormMode;
import tfsapps.formtennisai.model.TennisPhase;

/**
 * Three-state gate that runs BEFORE scoring — direct tennis counterpart of
 * {@code tfsapps.formbaseballai.analyzer.PoseValidator}.
 *
 * ValidationState:
 *   NO_HUMAN   – not enough high-confidence human landmarks.
 *   WRONG_POSE – a human is detected, but the pose doesn't match the
 *                required checkpoint for the current stroke/phase (e.g.
 *                arms still relaxed at the sides while checking the SERVE
 *                TROPHY_POSE checkpoint).
 *   VALID      – human anatomy confirmed AND the checkpoint's pose confirmed → score it.
 *
 * Design notes
 * ─────────────
 * • Same conventions as the baseball validator: image-space Y coordinates
 *   (smaller Y = higher in frame), HUMAN_CONF = 0.45 (still higher than
 *   TennisFormScorer's own MIN_CONFIDENCE, because anatomy reasoning needs
 *   reliable positions, not just angle estimates), and deliberately loose
 *   per-checkpoint thresholds so a genuine mid-motion frame isn't rejected
 *   over noisy keypoints.
 * • Baseball only ever validated "is this the right general pose for the
 *   sport" once per mode. TENNIS_SPEC.md instead defines four distinct
 *   checkpoints per stroke, each with its own required body configuration,
 *   so {@link #validate} additionally takes a {@link TennisPhase}.
 * • Camera angle differs by stroke, matching how these strokes are
 *   actually coached and filmed:
 *     - SERVE is filmed side-on (camera to the player's side, roughly
 *       perpendicular to the toss/swing plane) — the same reason a
 *       baseball pitcher's motion is filmed from the side rather than head
 *       -on. Every SERVE check below only ever compares Y-axis (height)
 *       positions or a single limb's 2D joint angle, so none of them
 *       depend on the left/right shoulder separation that a side view
 *       collapses to near zero.
 *     - FOREHAND / BACKHAND need the camera on the player's front-back
 *       axis — either facing the player OR filming their back from behind
 *       both work equally (verified against real match footage filmed
 *       from behind), because their "unit turn" and "impact position"
 *       checks below only need the shoulder/hip line's left/right (X-axis)
 *       separation to be visible, and that separation is just as visible
 *       from behind the player as from in front. Only a true side/profile
 *       shot breaks these checks, by collapsing that separation to near
 *       zero — which is exactly the shot SERVE requires instead.
 *   Right-handed player assumed throughout (consistent with
 *   TennisFormScorer's own documented assumption) — a left-handed toggle
 *   would mirror every "RIGHT_*" / X-axis comparison used here.
 * • "Unit turn" (forehand/backhand) can't see torso twist directly from
 *   one 2D camera on the front-back axis. As a heuristic, a real turn
 *   foreshortens the shoulder line in the image, so
 *   {@link #isGroundstrokeUnitTurn} looks for a shoulder width that has
 *   visibly shrunk relative to torso height, compared with the plain "is
 *   this a human" ratio in isHuman().
 * • Every per-checkpoint boundary check below (elbow above/below the
 *   shoulder line, wrist past the hip line, etc.) is given a tolerance
 *   band of {@link #MARGIN_RATIO} × torso height via {@link #marginFor},
 *   rather than an exact pixel line. Real footage never lands exactly on
 *   a boundary. {@code MIN_VISIBLE} is also lower for SERVE than for the
 *   groundstrokes, because a true side-on shot naturally self-occludes
 *   the far-side limb (the arm/leg nearer the camera can hide the other),
 *   which a frontal/rear shot mostly doesn't.
 * • SERVE's checkpoints deliberately never say "RIGHT_*" ("the hitting
 *   arm") — they read whichever of the two elbows/wrists is more raised
 *   (or, for FINISH, more dropped) via {@link #higherY}/{@link #lowerY}.
 *   This was a real bug, not just a tuning gap: from a side-on camera, ML
 *   Kit's left/right labeling is noticeably less reliable than head-on
 *   (it loses the face/torso asymmetry cues a frontal shot gives it), and
 *   the hitting arm is also the one more likely to be partly hidden
 *   behind the torso/head depending on which side the camera is on. A
 *   check hard-wired to RIGHT_ELBOW could therefore miss a textbook
 *   trophy pose whenever that specific landmark was mislabeled or
 *   low-confidence, no matter how loose its numeric threshold was —
 *   reading "either arm" fixes the actual cause instead of the symptom.
 */
public class TennisPoseValidator {

    public enum ValidationState { VALID, NO_HUMAN, WRONG_POSE }

    /** Minimum in-frame likelihood for a landmark to count as "seen".
     *  Loosened from 0.55 — side-on / occluded limbs routinely score lower
     *  confidence even when correctly detected. */
    private static final float HUMAN_CONF = 0.45f;

    /** We need at least this many of the 10 key landmarks at HUMAN_CONF.
     *  SERVE's side-on camera self-occludes the far-side limb, so it gets
     *  a lower bar than the groundstrokes' frontal/rear shot. */
    private static final int MIN_VISIBLE_SERVE = 5;
    private static final int MIN_VISIBLE_GROUNDSTROKE = 7;

    /** Tolerance band for every checkpoint boundary check below, as a
     *  fraction of torso height (shoulder-to-hip) — see class javadoc.
     *  Widened from 0.15 alongside the SERVE either-arm rework below. */
    private static final float MARGIN_RATIO = 0.20f;

    /** Extra-wide tolerance used only by {@link #isServeFinish}. Reported
     *  behavior: READY/TROPHY/IMPACT reliably fire (the "3/4" the user
     *  gets to), but FINISH often doesn't — real follow-throughs vary a
     *  lot by grip and serve type, and plenty of complete swings never
     *  bring the wrist all the way down to hip height, which is what
     *  MARGIN_RATIO's boundary effectively demanded. This constant moves
     *  that boundary further up toward the shoulder (see isServeFinish),
     *  so "arm has clearly dropped from the overhead hitting position" is
     *  enough, without reaching all the way down to the hip line. */
    private static final float SERVE_FINISH_MARGIN_RATIO = 0.45f;

    // ── Public entry point ────────────────────────────────────────────────

    public static ValidationState validate(Pose pose, TennisFormMode mode, TennisPhase phase) {
        if (!isHuman(pose, mode)) return ValidationState.NO_HUMAN;
        if (!isCheckpointPose(pose, mode, phase)) return ValidationState.WRONG_POSE;
        return ValidationState.VALID;
    }

    // ── isHuman (frontal shoulder-width ratio is skipped for SERVE, whose
    //    side-on camera collapses that ratio to near zero even for a
    //    correctly-framed shot — see class javadoc) ──────────────────────

    private static boolean isHuman(Pose pose, TennisFormMode mode) {
        int[] keyTypes = {
                PoseLandmark.LEFT_SHOULDER,  PoseLandmark.RIGHT_SHOULDER,
                PoseLandmark.LEFT_HIP,       PoseLandmark.RIGHT_HIP,
                PoseLandmark.LEFT_ELBOW,     PoseLandmark.RIGHT_ELBOW,
                PoseLandmark.LEFT_WRIST,     PoseLandmark.RIGHT_WRIST,
                PoseLandmark.LEFT_KNEE,      PoseLandmark.RIGHT_KNEE,
        };

        int visible = 0;
        for (int type : keyTypes) {
            PoseLandmark lm = pose.getPoseLandmark(type);
            if (lm != null && lm.getInFrameLikelihood() >= HUMAN_CONF) visible++;
        }
        int minVisible = (mode == TennisFormMode.SERVE) ? MIN_VISIBLE_SERVE : MIN_VISIBLE_GROUNDSTROKE;
        if (visible < minVisible) return false;

        PoseLandmark lSh = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
        PoseLandmark rSh = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
        PoseLandmark lHi = pose.getPoseLandmark(PoseLandmark.LEFT_HIP);
        PoseLandmark rHi = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP);
        if (lSh == null || rSh == null || lHi == null || rHi == null) return false;

        float shoulderY = (lSh.getPosition().y + rSh.getPosition().y) * 0.5f;
        float hipY      = (lHi.getPosition().y + rHi.getPosition().y) * 0.5f;
        if (shoulderY >= hipY) return false;

        if (mode == TennisFormMode.SERVE) {
            // Side-on camera: the two shoulders sit nearly on top of each
            // other in the image, so the frontal ratio below would reject
            // a correctly-framed side view. The visible-landmark count and
            // shoulder-above-hip check above are gate enough here.
            return true;
        }

        float shoulderWidth = Math.abs(lSh.getPosition().x - rSh.getPosition().x);
        float torsoHeight   = hipY - shoulderY;
        // Loosened from 0.30 — a player standing a bit off-axis, or simply
        // farther from the camera, was tripping NO_HUMAN unnecessarily.
        return shoulderWidth >= torsoHeight * 0.20f;
    }

    // ── Per (mode, phase) checkpoint gating ─────────────────────────────────

    private static boolean isCheckpointPose(Pose pose, TennisFormMode mode, TennisPhase phase) {
        if (mode == TennisFormMode.SERVE) {
            switch (phase) {
                case READY:  return isServeReady(pose);
                case TURN:   return isServeTrophyPose(pose);
                case IMPACT: return isServeImpact(pose);
                case FINISH: return isServeFinish(pose);
            }
        } else {
            // Both forehand and either backhand grip share the same
            // ready/turn/impact/finish silhouette from a frontal camera.
            switch (phase) {
                case READY:  return isGroundstrokeReady(pose);
                case TURN:   return isGroundstrokeUnitTurn(pose);
                case IMPACT: return isGroundstrokeImpact(pose);
                case FINISH: return isGroundstrokeFinish(pose);
            }
        }
        return false;
    }

    // ── SERVE (filmed side-on — see class javadoc) ──────────────────────────
    //
    // None of the checks below single out RIGHT_* ("the hitting arm") any
    // more. Reasoning: a side-on camera shows one arm clearly and the other
    // partly behind the torso/head, and which physical arm ML Kit's pose
    // model calls "left" vs "right" gets noticeably less reliable in
    // profile than head-on (it's a much easier call when it can see the
    // face/torso asymmetry of a frontal shot). In practice this meant a
    // real trophy pose could still fail here whenever the hitting arm
    // happened to be the far/occluded/mislabeled one for that particular
    // camera side — reported repeatedly even after loosening the numeric
    // thresholds, which was treating a design flaw as a tuning problem.
    // Reading "whichever elbow/wrist is more raised" (or more dropped, for
    // FINISH) sidesteps left/right entirely and is naturally forgiving of
    // one arm being partly hidden.

    /** Stance: neither arm raised above the shoulder line yet. */
    private static boolean isServeReady(Pose pose) {
        Float shoulderY = midY(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER);
        Float elbowY = higherY(pose, PoseLandmark.RIGHT_ELBOW, PoseLandmark.LEFT_ELBOW);
        if (shoulderY == null || elbowY == null) return false;
        return elbowY >= shoulderY - marginFor(pose);
    }

    /** Trophy pose: at least one elbow or wrist raised to/above shoulder
     *  height — the hitting arm cocked up, the toss arm still extended up,
     *  or (commonly, right at the top of the toss) both. */
    private static boolean isServeTrophyPose(Pose pose) {
        Float shoulderY = midY(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER);
        Float elbowY = higherY(pose, PoseLandmark.RIGHT_ELBOW, PoseLandmark.LEFT_ELBOW);
        Float wristY = higherY(pose, PoseLandmark.RIGHT_WRIST, PoseLandmark.LEFT_WRIST);
        if (shoulderY == null || (elbowY == null && wristY == null)) return false;
        float threshold = shoulderY + marginFor(pose);
        return (elbowY != null && elbowY <= threshold) || (wristY != null && wristY <= threshold);
    }

    /** Impact: a wrist reaches above the shoulder line (overhead contact). */
    private static boolean isServeImpact(Pose pose) {
        Float shoulderY = midY(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER);
        Float wristY = higherY(pose, PoseLandmark.RIGHT_WRIST, PoseLandmark.LEFT_WRIST);
        if (shoulderY == null || wristY == null) return false;
        return wristY < shoulderY + marginFor(pose);
    }

    /**
     * Finish: a wrist has dropped well down from the overhead hitting
     * position — the visible signature of a completed follow-through. Uses
     * {@link #SERVE_FINISH_MARGIN_RATIO} rather than the shared
     * {@link #marginFor} tolerance, so the wrist only needs to have
     * clearly come down (well below the shoulder line), not travel all the
     * way to hip height — see that constant's javadoc for why. (An earlier
     * version of this check additionally required the wrist to have
     * "crossed toward the non-hitting side" in X; that assumed a frontal
     * camera and, from a side-on serve shot, which way that looks in the
     * image depends on which side of the player the camera is on, so it
     * wasn't a reliable signal and was dropped. TennisFormScorer's own
     * SERVE_FINISH_CROSS criterion still scores the follow-through's
     * quality once this checkpoint is captured.)
     */
    private static boolean isServeFinish(Pose pose) {
        Float hipY = midY(pose, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP);
        Float wristY = lowerY(pose, PoseLandmark.RIGHT_WRIST, PoseLandmark.LEFT_WRIST);
        if (hipY == null || wristY == null) return false;
        Float torso = torsoHeight(pose);
        float margin = (torso == null || torso <= 0f) ? 0f : torso * SERVE_FINISH_MARGIN_RATIO;
        return wristY > hipY - margin;
    }

    // ── FOREHAND / BACKHAND (shared silhouette from a frontal camera) ──────

    /** Ready position: both hands held near the body's centerline, elbows relaxed. */
    private static boolean isGroundstrokeReady(Pose pose) {
        PoseLandmark lEl = pose.getPoseLandmark(PoseLandmark.LEFT_ELBOW);
        PoseLandmark rEl = pose.getPoseLandmark(PoseLandmark.RIGHT_ELBOW);
        Float shoulderY = midY(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER);
        if (lEl == null || rEl == null || shoulderY == null) return false;
        float margin = marginFor(pose);
        return lEl.getPosition().y >= shoulderY - margin && rEl.getPosition().y >= shoulderY - margin;
    }

    /**
     * Unit turn: the shoulder line has visibly foreshortened compared with a
     * plain front-on-axis stance (facing the camera or with the back to
     * it — see class javadoc), indicating the torso has rotated toward a
     * true side-on angle. A 2D-camera heuristic.
     */
    private static boolean isGroundstrokeUnitTurn(Pose pose) {
        PoseLandmark lSh = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
        PoseLandmark rSh = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
        PoseLandmark lHi = pose.getPoseLandmark(PoseLandmark.LEFT_HIP);
        PoseLandmark rHi = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP);
        if (lSh == null || rSh == null || lHi == null || rHi == null) return false;

        float shoulderY = (lSh.getPosition().y + rSh.getPosition().y) * 0.5f;
        float hipY      = (lHi.getPosition().y + rHi.getPosition().y) * 0.5f;
        float shoulderWidth = Math.abs(lSh.getPosition().x - rSh.getPosition().x);
        float torsoHeight   = Math.abs(hipY - shoulderY);
        if (torsoHeight < 1e-3f) return false;

        float ratio = shoulderWidth / torsoHeight;
        // isHuman() already guarantees ratio >= 0.20; a turn narrows it
        // further. Loose upper bound keeps a slight/partial turn valid.
        return ratio < 0.62f;
    }

    /** Impact: hitting wrist extended away from the body, roughly between hip and shoulder height. */
    private static boolean isGroundstrokeImpact(Pose pose) {
        PoseLandmark rWr = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST);
        PoseLandmark lSh = pose.getPoseLandmark(PoseLandmark.LEFT_SHOULDER);
        PoseLandmark rSh = pose.getPoseLandmark(PoseLandmark.RIGHT_SHOULDER);
        PoseLandmark lHi = pose.getPoseLandmark(PoseLandmark.LEFT_HIP);
        PoseLandmark rHi = pose.getPoseLandmark(PoseLandmark.RIGHT_HIP);
        if (rWr == null || lSh == null || rSh == null || lHi == null || rHi == null) return false;

        float shoulderY  = (lSh.getPosition().y + rSh.getPosition().y) * 0.5f;
        float hipY       = (lHi.getPosition().y + rHi.getPosition().y) * 0.5f;
        float hipMidX    = (lHi.getPosition().x + rHi.getPosition().x) * 0.5f;
        float shoulderWidth = Math.abs(lSh.getPosition().x - rSh.getPosition().x);
        float margin = (hipY - shoulderY) * MARGIN_RATIO;

        boolean inHittingHeight = rWr.getPosition().y <= hipY + margin && rWr.getPosition().y >= shoulderY - shoulderWidth - margin;
        // Loosened from 0.5 — a real contact point that's merely "out to
        // the side" rather than fully extended was being missed.
        boolean extendedOut     = Math.abs(rWr.getPosition().x - hipMidX) >= shoulderWidth * 0.35f;
        return inHittingHeight && extendedOut;
    }

    /** Finish: hitting wrist has swung up and across, ending near/above shoulder height. */
    private static boolean isGroundstrokeFinish(Pose pose) {
        PoseLandmark rWr = pose.getPoseLandmark(PoseLandmark.RIGHT_WRIST);
        Float shoulderY = midY(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER);
        if (rWr == null || shoulderY == null) return false;
        return rWr.getPosition().y <= shoulderY + marginFor(pose);
    }

    // ── Small helpers ────────────────────────────────────────────────────

    @Nullable
    private static Float midY(Pose pose, int typeA, int typeB) {
        PoseLandmark a = pose.getPoseLandmark(typeA);
        PoseLandmark b = pose.getPoseLandmark(typeB);
        if (a == null || b == null) return null;
        return (a.getPosition().y + b.getPosition().y) * 0.5f;
    }

    /** Shoulder-to-hip pixel height, or null if either isn't visible. Used
     *  only to size {@link #marginFor}'s tolerance band. */
    @Nullable
    private static Float torsoHeight(Pose pose) {
        Float shoulderY = midY(pose, PoseLandmark.LEFT_SHOULDER, PoseLandmark.RIGHT_SHOULDER);
        Float hipY = midY(pose, PoseLandmark.LEFT_HIP, PoseLandmark.RIGHT_HIP);
        if (shoulderY == null || hipY == null) return null;
        return hipY - shoulderY;
    }

    /** Tolerance band (pixels) for one checkpoint's boundary check — see
     *  class javadoc. Falls back to 0 (no tolerance) if the torso itself
     *  isn't measurable, which shouldn't happen once isHuman() has passed. */
    private static float marginFor(Pose pose) {
        Float t = torsoHeight(pose);
        return (t == null || t <= 0f) ? 0f : t * MARGIN_RATIO;
    }

    /** The more-raised (smaller-Y) of two landmarks, or whichever one is
     *  actually visible if only one is. Null only if neither is. Used so
     *  SERVE checks can ask "is an arm up?" without caring which physical
     *  arm ML Kit called left/right — see the SERVE section javadoc. */
    @Nullable
    private static Float higherY(Pose pose, int typeA, int typeB) {
        PoseLandmark a = pose.getPoseLandmark(typeA);
        PoseLandmark b = pose.getPoseLandmark(typeB);
        Float ay = a != null ? a.getPosition().y : null;
        Float by = b != null ? b.getPosition().y : null;
        if (ay == null) return by;
        if (by == null) return ay;
        return Math.min(ay, by);
    }

    /** The more-dropped (larger-Y) of two landmarks — the FINISH-side
     *  counterpart of {@link #higherY}. */
    @Nullable
    private static Float lowerY(Pose pose, int typeA, int typeB) {
        PoseLandmark a = pose.getPoseLandmark(typeA);
        PoseLandmark b = pose.getPoseLandmark(typeB);
        Float ay = a != null ? a.getPosition().y : null;
        Float by = b != null ? b.getPosition().y : null;
        if (ay == null) return by;
        if (by == null) return ay;
        return Math.max(ay, by);
    }
}
