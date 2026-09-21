package tfsapps.formtennisai.model;

import android.content.Context;

import tfsapps.formtennisai.R;

/**
 * Tennis strokes supported by the app.
 *
 * Mirrors {@code tfsapps.formbaseballai.model.FormMode}: baseball had two
 * flat modes (PITCHING / BATTING) because a pitch and a swing are each a
 * single, one-shot motion. A tennis stroke is also one continuous motion,
 * but per TENNIS_SPEC.md each stroke is additionally broken into four
 * checkpoints (see {@link TennisPhase}), so this enum only needs to say
 * *which* stroke is being analyzed — phase handling lives in TennisPhase /
 * TennisCoachModel / TennisFormScorer.
 *
 * BACKHAND is split into two distinct modes (rather than one mode with a
 * runtime flag) because TENNIS_SPEC.md gives the two grips genuinely
 * different reference logic (two-handed ~ baseball batting mechanics,
 * one-handed ~ its own stability-focused checklist) — keeping them as
 * separate enum constants keeps TennisCoachModel's per-mode tables simple.
 *
 * Display text is looked up via string resources (a {@code Context} must be
 * passed in) rather than hard-coded here, so the app can show Japanese on a
 * Japanese-locale device and English everywhere else (see values/strings.xml
 * vs values-ja/strings.xml) without any logic in this class knowing which
 * language is active.
 */
public enum TennisFormMode {
    SERVE(R.string.mode_display_serve),
    FOREHAND(R.string.mode_display_forehand),
    BACKHAND_TWO_HANDED(R.string.mode_display_backhand_two),
    BACKHAND_ONE_HANDED(R.string.mode_display_backhand_one);

    private final int displayNameRes;

    TennisFormMode(int displayNameRes) {
        this.displayNameRes = displayNameRes;
    }

    /** String resource id for {@link #getDisplayName}, for callers that
     *  need the raw id (e.g. to set it directly on a TextView). */
    public int getDisplayNameRes() {
        return displayNameRes;
    }

    public String getDisplayName(Context context) {
        return context.getString(displayNameRes);
    }

    /** True for either backhand grip variant. */
    public boolean isBackhand() {
        return this == BACKHAND_TWO_HANDED || this == BACKHAND_ONE_HANDED;
    }

    /**
     * String resource id for one of this mode's four checkpoints, per
     * TENNIS_SPEC.md's phase naming (STANCE/TROPHY_POSE/IMPACT/FINISH for
     * SERVE; READY/UNIT_TURN/IMPACT/FINISH for the groundstrokes).
     */
    public int getPhaseLabelRes(TennisPhase phase) {
        if (this == SERVE) {
            switch (phase) {
                case READY:  return R.string.serve_phase_ready;
                case TURN:   return R.string.serve_phase_turn;
                case IMPACT: return R.string.serve_phase_impact;
                case FINISH: return R.string.serve_phase_finish;
            }
        } else {
            switch (phase) {
                case READY:  return R.string.groundstroke_phase_ready;
                case TURN:   return R.string.groundstroke_phase_turn;
                case IMPACT: return R.string.groundstroke_phase_impact;
                case FINISH: return R.string.groundstroke_phase_finish;
            }
        }
        // Unreachable: TennisPhase has exactly the 4 cases handled above.
        throw new IllegalStateException("Unhandled phase " + phase + " for mode " + this);
    }

    public String getPhaseLabel(Context context, TennisPhase phase) {
        return context.getString(getPhaseLabelRes(phase));
    }
}
