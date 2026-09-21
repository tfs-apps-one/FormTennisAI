package tfsapps.formtennisai.model;

import java.io.Serializable;
import java.util.List;

/**
 * Result of scoring ONE {@link TennisPhase} checkpoint of a stroke.
 *
 * Parallels {@code tfsapps.formbaseballai.model.PoseResult} field-for-field
 * (componentScores / measuredAngles / idealAngles / overallScore / stars /
 * strengths / improvements). The difference is baseball always had exactly
 * 5 fixed components for any mode, whereas a tennis checkpoint has however
 * many criteria TennisCoachModel.criteriaFor(mode, phase) defines (1 or 2
 * here), so the arrays are sized per-phase and each slot additionally
 * carries its {@link TennisCoachModel.CriterionId} so callers/UI can tell
 * which physical check a given slot refers to.
 */
public class TennisPoseResult implements Serializable {

    /** Score below which a checkpoint counts as "not passed" (mirrors the
     *  60-point cut FormScorer.addFeedback used to flag improvement areas). */
    public static final float PASS_THRESHOLD = 60f;

    public final TennisFormMode mode;
    public final TennisPhase phase;

    /** Which physical check each array slot below refers to. */
    public final TennisCoachModel.CriterionId[] criterionIds;

    /** 0-100 score per criterion, same order as criterionIds. */
    public final float[] componentScores;

    /** Measured value per criterion (degrees, or a normalized ratio — see the
     *  criterion's geometry helper in TennisFormScorer). */
    public final float[] measuredValues;

    /** Target value per criterion (ideal for RANGE, threshold for AT_LEAST/AT_MOST). */
    public final float[] targetValues;

    /** Weighted overall score 0-100 for this single checkpoint. */
    public final float overallScore;

    /** 1-5 star rating derived from overallScore. */
    public final int stars;

    /** True once overallScore reaches PASS_THRESHOLD. */
    public final boolean passed;

    /** Feedback lines for things done well at this checkpoint. */
    public final List<String> strengths;

    /** Feedback lines for what to fix at this checkpoint. */
    public final List<String> improvements;

    /**
     * A small, Serializable snapshot of the 2D landmark positions this
     * checkpoint was scored from (captured at the exact instant the
     * checkpoint locked in) — used by the result screen to draw a "your
     * form vs. the ideal form" skeleton comparison. Not a raw MLKit
     * {@code Pose} (which isn't Serializable, and can't cross the Intent
     * extra this result travels over), just the handful of 2D points and
     * confidences {@code TennisResultSkeletonView} needs. Null when the
     * checkpoint was skipped without ever validating (see
     * TennisCameraViewModel#neutralResult), since there was no candidate
     * frame to snapshot.
     */
    public static final class LandmarkSnapshot implements Serializable {
        public final int[] types;
        public final float[] xs;
        public final float[] ys;
        public final float[] confidences;
        public final int imageWidth;
        public final int imageHeight;

        public LandmarkSnapshot(int[] types, float[] xs, float[] ys, float[] confidences,
                                 int imageWidth, int imageHeight) {
            this.types = types;
            this.xs = xs;
            this.ys = ys;
            this.confidences = confidences;
            this.imageWidth = imageWidth;
            this.imageHeight = imageHeight;
        }
    }

    /** See {@link LandmarkSnapshot}. May be null. */
    public final LandmarkSnapshot landmarks;

    public TennisPoseResult(
            TennisFormMode mode,
            TennisPhase phase,
            TennisCoachModel.CriterionId[] criterionIds,
            float[] componentScores,
            float[] measuredValues,
            float[] targetValues,
            float overallScore,
            List<String> strengths,
            List<String> improvements,
            LandmarkSnapshot landmarks) {

        this.mode = mode;
        this.phase = phase;
        this.criterionIds = criterionIds;
        this.componentScores = componentScores;
        this.measuredValues = measuredValues;
        this.targetValues = targetValues;
        this.overallScore = overallScore;
        this.stars = scoreToStars(overallScore);
        this.passed = overallScore >= PASS_THRESHOLD;
        this.strengths = strengths;
        this.improvements = improvements;
        this.landmarks = landmarks;
    }

    /** Maps a 0-100 score onto a 1-5 star rating. Identical breakpoints to
     *  baseball's PoseResult.scoreToStars, kept for a consistent feel
     *  across both apps. */
    public static int scoreToStars(float score) {
        if (score >= 90) return 5;
        if (score >= 75) return 4;
        if (score >= 55) return 3;
        if (score >= 35) return 2;
        return 1;
    }

    /** Renders the star rating as an emoji string (e.g. "⭐⭐⭐☆☆"). */
    public String starsString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            sb.append(i <= stars ? "⭐" : "☆");
        }
        return sb.toString();
    }
}
