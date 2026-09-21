package tfsapps.formtennisai.model;

/**
 * The four checkpoints every stroke in TENNIS_SPEC.md is evaluated at.
 *
 * Baseball's FrameCapture used a flat 0-7 phaseIndex supplied by an external
 * recording/sampling component that wasn't part of the reference sources
 * we were given, so it can't be mirrored 1:1. TENNIS_SPEC.md itself always
 * lists exactly four checkpoints per stroke (they're just named differently
 * per stroke — see {@link TennisFormMode#getPhaseLabel}), so a small enum
 * is the more faithful match for what the spec actually describes.
 */
public enum TennisPhase {
    /** SERVE: STANCE.  Groundstrokes: READY. */
    READY(0),
    /** SERVE: TROPHY_POSE.  Groundstrokes: UNIT_TURN. */
    TURN(1),
    /** IMPACT — same name for every stroke. */
    IMPACT(2),
    /** FINISH — same name for every stroke. */
    FINISH(3);

    private final int index;

    TennisPhase(int index) {
        this.index = index;
    }

    public int getIndex() {
        return index;
    }

    private static final TennisPhase[] ORDERED = {READY, TURN, IMPACT, FINISH};

    public static TennisPhase fromIndex(int index) {
        return ORDERED[index];
    }

    public static int count() {
        return ORDERED.length;
    }
}
