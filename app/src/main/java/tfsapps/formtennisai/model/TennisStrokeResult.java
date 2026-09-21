package tfsapps.formtennisai.model;

import androidx.annotation.NonNull;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 * Combines the four {@link TennisPhase} checkpoints of one stroke into a
 * single whole-swing summary.
 *
 * Baseball never needed this: a pitch or a swing was judged from one
 * snapshot, so {@code PoseResult} alone was the whole story. TENNIS_SPEC.md
 * explicitly defines every stroke as four checkpoints, so after
 * TennisFormScorer scores each one (producing four {@link TennisPoseResult}
 * instances), this class is the "necessary extension" that rolls them up
 * into the one overall score / star rating / feedback list a results screen
 * would actually show the user.
 */
public class TennisStrokeResult implements Serializable {

    public final TennisFormMode mode;

    /** Length 4, ordered by {@link TennisPhase#getIndex()}. */
    public final TennisPoseResult[] phaseResults;

    /** Simple average of the four checkpoint scores — every checkpoint in
     *  TENNIS_SPEC.md is presented as an equally-necessary part of the
     *  stroke, so no phase is weighted above another. */
    public final float overallScore;

    public final int stars;

    /** Every strength line collected across all four checkpoints. */
    public final List<String> strengths;

    /** Every improvement line collected across all four checkpoints. */
    public final List<String> improvements;

    private TennisStrokeResult(TennisFormMode mode, TennisPoseResult[] phaseResults,
                                float overallScore, List<String> strengths, List<String> improvements) {
        this.mode = mode;
        this.phaseResults = phaseResults;
        this.overallScore = overallScore;
        this.stars = TennisPoseResult.scoreToStars(overallScore);
        this.strengths = strengths;
        this.improvements = improvements;
    }

    /**
     * @param phaseResults exactly 4 results, one per {@link TennisPhase}, all for the same mode.
     */
    @NonNull
    public static TennisStrokeResult combine(@NonNull TennisFormMode mode, @NonNull TennisPoseResult[] phaseResults) {
        if (phaseResults.length != TennisPhase.count()) {
            throw new IllegalArgumentException("Expected " + TennisPhase.count() + " phase results, got " + phaseResults.length);
        }
        float sum = 0f;
        List<String> strengths = new ArrayList<>();
        List<String> improvements = new ArrayList<>();
        for (TennisPoseResult r : phaseResults) {
            sum += r.overallScore;
            strengths.addAll(r.strengths);
            improvements.addAll(r.improvements);
        }
        float overall = sum / phaseResults.length;
        return new TennisStrokeResult(mode, phaseResults, overall, strengths, improvements);
    }

    public String starsString() {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= 5; i++) {
            sb.append(i <= stars ? "⭐" : "☆");
        }
        return sb.toString();
    }
}
