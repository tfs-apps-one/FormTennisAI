package tfsapps.formtennisai.model;

import androidx.annotation.NonNull;

import tfsapps.formtennisai.R;

/**
 * Ideal values / tolerances for every (TennisFormMode, TennisPhase) checkpoint
 * defined in TENNIS_SPEC.md.
 *
 * Mirrors {@code tfsapps.formbaseballai.model.CoachModel}'s role of holding
 * pure reference data (idealX / toleranceX per component) that FormScorer
 * looks up and compares measured angles against. Baseball's CoachModel had a
 * fixed shape — every mode always scored the same 5 components (elbow,
 * elbow, shoulder, hip, knee) — because a pitch/swing is judged as a single
 * snapshot. TENNIS_SPEC.md instead gives each (mode, phase) checkpoint its
 * own, differently-shaped list of criteria (1-2 metrics each, sometimes a
 * "±tolerance" target, sometimes a "≥X" or "≤X" threshold), so this class
 * returns a {@link Criterion}[] per (mode, phase) rather than a fixed-size
 * array of five idealX fields.
 *
 * ── Scoring types ───────────────────────────────────────────────────────
 *  RANGE     — spec gives a target with a ± band (e.g. knee bend 130-150°,
 *              i.e. ideal 140° ± 10°). Scored by
 *              {@code TennisFormScorer.scoreRange}.
 *  AT_LEAST  — spec gives a "≥ X°" style minimum (e.g. elbow extension
 *              ≥160°, shoulder/hip twist ≥20°). Scored by
 *              {@code TennisFormScorer.scoreAtLeast}.
 *  AT_MOST   — spec gives a "should not exceed" style ceiling (e.g.
 *              one-handed backhand: chest must not open too far at
 *              impact). Scored by {@code TennisFormScorer.scoreAtMost}.
 *
 * All three scoring functions share the same green / yellow / floor shape
 * as baseball's {@code FormScorer.scoreComponent} — full credit inside the
 * good zone, a linear taper through a "soft" zone, and a floor beyond that
 * (never punished to zero, for the same single-frame-capture reason
 * documented in baseball's FormScorer).
 */
public final class TennisCoachModel {

    private TennisCoachModel() { }

    /** Per-component score floor — mirrors FormScorer.SCORE_FLOOR (20). */
    public static final float SCORE_FLOOR = 20f;

    public enum ScoreType { RANGE, AT_LEAST, AT_MOST }

    /**
     * Stable identity for one measured criterion, carrying the string
     * resources used to build its locale-aware feedback line (same
     * "strength if score is high / improvement tip if score is low"
     * pattern as baseball's FormScorer, just addressed by id instead of
     * array position since the number of criteria varies per phase).
     */
    public enum CriterionId {
        READY_BALANCE(R.string.tennis_ready_balance_strength, R.string.tennis_ready_balance_improve, R.string.criterion_ready_balance),

        SERVE_TROPHY_ELBOW(R.string.tennis_serve_trophy_elbow_strength, R.string.tennis_serve_trophy_elbow_improve, R.string.criterion_serve_trophy_elbow),
        SERVE_TROPHY_KNEE(R.string.tennis_serve_trophy_knee_strength, R.string.tennis_serve_trophy_knee_improve, R.string.criterion_serve_trophy_knee),
        SERVE_IMPACT_ELBOW(R.string.tennis_serve_impact_elbow_strength, R.string.tennis_serve_impact_elbow_improve, R.string.criterion_serve_impact_elbow),
        SERVE_IMPACT_TILT(R.string.tennis_serve_impact_tilt_strength, R.string.tennis_serve_impact_tilt_improve, R.string.criterion_serve_impact_tilt),
        SERVE_FINISH_ELBOW(R.string.tennis_serve_finish_elbow_strength, R.string.tennis_serve_finish_elbow_improve, R.string.criterion_serve_finish_elbow),
        SERVE_FINISH_CROSS(R.string.tennis_serve_finish_cross_strength, R.string.tennis_serve_finish_cross_improve, R.string.criterion_serve_finish_cross),

        FH_TURN_TWIST(R.string.tennis_fh_turn_twist_strength, R.string.tennis_fh_turn_twist_improve, R.string.criterion_fh_turn_twist),
        FH_IMPACT_FRONT(R.string.tennis_fh_impact_front_strength, R.string.tennis_fh_impact_front_improve, R.string.criterion_fh_impact_front),
        FH_IMPACT_ELBOW(R.string.tennis_fh_impact_elbow_strength, R.string.tennis_fh_impact_elbow_improve, R.string.criterion_fh_impact_elbow),
        FH_FINISH_SWING(R.string.tennis_fh_finish_swing_strength, R.string.tennis_fh_finish_swing_improve, R.string.criterion_fh_finish_swing),

        BH2_TURN_LOWER(R.string.tennis_bh2_turn_lower_strength, R.string.tennis_bh2_turn_lower_improve, R.string.criterion_bh2_turn_lower),
        BH2_IMPACT_ELBOW(R.string.tennis_bh2_impact_elbow_strength, R.string.tennis_bh2_impact_elbow_improve, R.string.criterion_bh2_impact_elbow),
        BH2_FINISH_CHEST(R.string.tennis_bh2_finish_chest_strength, R.string.tennis_bh2_finish_chest_improve, R.string.criterion_bh2_finish_chest),

        BH1_TURN_TWIST(R.string.tennis_bh1_turn_twist_strength, R.string.tennis_bh1_turn_twist_improve, R.string.criterion_bh1_turn_twist),
        BH1_IMPACT_CHEST(R.string.tennis_bh1_impact_chest_strength, R.string.tennis_bh1_impact_chest_improve, R.string.criterion_bh1_impact_chest),
        BH1_IMPACT_ELBOW_LOCK(R.string.tennis_bh1_impact_elbow_lock_strength, R.string.tennis_bh1_impact_elbow_lock_improve, R.string.criterion_bh1_impact_elbow_lock),
        BH1_FINISH_SWING(R.string.tennis_bh1_finish_swing_strength, R.string.tennis_bh1_finish_swing_improve, R.string.criterion_bh1_finish_swing);

        public final int strengthRes;
        public final int improveRes;
        /** Short, locale-aware label (Japanese) for the per-criterion
         *  breakdown row header on the result screen. Previously this was
         *  derived from the enum constant name itself (e.g.
         *  "Serve Trophy Elbow"), which showed up in English even on a
         *  Japanese device — now it's a real string resource like every
         *  other user-facing label in this app. */
        public final int displayNameRes;

        CriterionId(int strengthRes, int improveRes, int displayNameRes) {
            this.strengthRes = strengthRes;
            this.improveRes = improveRes;
            this.displayNameRes = displayNameRes;
        }
    }

    /** One measured criterion's reference data. Immutable. */
    public static final class Criterion {
        public final CriterionId id;
        public final ScoreType type;
        public final float ideal;      // RANGE only
        public final float tolerance;  // RANGE only (± band; 4x = floor edge)
        public final float threshold;  // AT_LEAST / AT_MOST only (the "good" cutoff)
        public final float softRange;  // AT_LEAST / AT_MOST only (width of the taper zone)
        public final float weight;     // weights within one phase must sum to 1.0
        public final String unit;      // display unit for feedback text ("°" or "%")

        private Criterion(CriterionId id, ScoreType type, float ideal, float tolerance,
                           float threshold, float softRange, float weight, String unit) {
            this.id = id;
            this.type = type;
            this.ideal = ideal;
            this.tolerance = tolerance;
            this.threshold = threshold;
            this.softRange = softRange;
            this.weight = weight;
            this.unit = unit;
        }

        public static Criterion range(CriterionId id, float ideal, float tolerance, float weight) {
            return range(id, ideal, tolerance, weight, "°");
        }

        public static Criterion range(CriterionId id, float ideal, float tolerance, float weight, String unit) {
            return new Criterion(id, ScoreType.RANGE, ideal, tolerance, 0f, 0f, weight, unit);
        }

        public static Criterion atLeast(CriterionId id, float threshold, float softRange, float weight) {
            return atLeast(id, threshold, softRange, weight, "°");
        }

        public static Criterion atLeast(CriterionId id, float threshold, float softRange, float weight, String unit) {
            return new Criterion(id, ScoreType.AT_LEAST, 0f, 0f, threshold, softRange, weight, unit);
        }

        public static Criterion atMost(CriterionId id, float threshold, float softRange, float weight) {
            return new Criterion(id, ScoreType.AT_MOST, 0f, 0f, threshold, softRange, weight, "°");
        }

        /** The number FormScorer prints as "目標" (target) in feedback text. */
        public float displayTarget() {
            return type == ScoreType.RANGE ? ideal : threshold;
        }
    }

    private static final Criterion[] READY_ONLY = {
            Criterion.range(CriterionId.READY_BALANCE, 0f, 8f, 1.0f)
    };

    private static final Criterion[] SERVE_TURN = {
            Criterion.range(CriterionId.SERVE_TROPHY_ELBOW, 0f, 15f, 0.5f),
            Criterion.range(CriterionId.SERVE_TROPHY_KNEE, 140f, 10f, 0.5f)
    };
    private static final Criterion[] SERVE_IMPACT = {
            Criterion.atLeast(CriterionId.SERVE_IMPACT_ELBOW, 160f, 20f, 0.6f),
            Criterion.range(CriterionId.SERVE_IMPACT_TILT, 15f, 10f, 0.4f)
    };
    private static final Criterion[] SERVE_FINISH = {
            Criterion.range(CriterionId.SERVE_FINISH_ELBOW, 110f, 30f, 0.5f),
            Criterion.range(CriterionId.SERVE_FINISH_CROSS, 135f, 30f, 0.5f)
    };

    private static final Criterion[] FH_TURN = {
            Criterion.atLeast(CriterionId.FH_TURN_TWIST, 20f, 15f, 1.0f)
    };
    private static final Criterion[] FH_IMPACT = {
            Criterion.atLeast(CriterionId.FH_IMPACT_FRONT, 15f, 25f, 0.5f, "%"),
            Criterion.range(CriterionId.FH_IMPACT_ELBOW, 120f, 20f, 0.5f)
    };
    private static final Criterion[] FH_FINISH = {
            Criterion.atLeast(CriterionId.FH_FINISH_SWING, 100f, 40f, 1.0f)
    };

    private static final Criterion[] BH2_TURN = {
            Criterion.atLeast(CriterionId.BH2_TURN_LOWER, 15f, 15f, 1.0f)
    };
    private static final Criterion[] BH2_IMPACT = {
            Criterion.range(CriterionId.BH2_IMPACT_ELBOW, 110f, 25f, 1.0f)
    };
    private static final Criterion[] BH2_FINISH = {
            Criterion.range(CriterionId.BH2_FINISH_CHEST, 45f, 20f, 1.0f)
    };

    private static final Criterion[] BH1_TURN = {
            Criterion.atLeast(CriterionId.BH1_TURN_TWIST, 20f, 15f, 1.0f)
    };
    private static final Criterion[] BH1_IMPACT = {
            Criterion.atMost(CriterionId.BH1_IMPACT_CHEST, 30f, 30f, 0.5f),
            Criterion.range(CriterionId.BH1_IMPACT_ELBOW_LOCK, 165f, 15f, 0.5f)
    };
    private static final Criterion[] BH1_FINISH = {
            Criterion.atLeast(CriterionId.BH1_FINISH_SWING, 90f, 40f, 1.0f)
    };

    /** Returns the criteria to score for one (mode, phase) checkpoint, in a fixed order. */
    @NonNull
    public static Criterion[] criteriaFor(@NonNull TennisFormMode mode, @NonNull TennisPhase phase) {
        if (phase == TennisPhase.READY) return READY_ONLY;

        switch (mode) {
            case SERVE:
                switch (phase) {
                    case TURN:   return SERVE_TURN;
                    case IMPACT: return SERVE_IMPACT;
                    case FINISH: return SERVE_FINISH;
                    default: break;
                }
                break;
            case FOREHAND:
                switch (phase) {
                    case TURN:   return FH_TURN;
                    case IMPACT: return FH_IMPACT;
                    case FINISH: return FH_FINISH;
                    default: break;
                }
                break;
            case BACKHAND_TWO_HANDED:
                switch (phase) {
                    case TURN:   return BH2_TURN;
                    case IMPACT: return BH2_IMPACT;
                    case FINISH: return BH2_FINISH;
                    default: break;
                }
                break;
            case BACKHAND_ONE_HANDED:
                switch (phase) {
                    case TURN:   return BH1_TURN;
                    case IMPACT: return BH1_IMPACT;
                    case FINISH: return BH1_FINISH;
                    default: break;
                }
                break;
        }
        throw new IllegalArgumentException("No criteria for " + mode + " / " + phase);
    }
}
