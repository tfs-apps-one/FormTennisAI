package tfsapps.formtennisai.camera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.google.mlkit.vision.pose.Pose;
import com.google.mlkit.vision.pose.PoseLandmark;

import tfsapps.formtennisai.R;
import tfsapps.formtennisai.model.TennisFormMode;
import tfsapps.formtennisai.model.TennisPhase;

/**
 * Transparent AR overlay drawn over the CameraX preview:
 *
 *   🟢 GREEN = the user's detected pose (ML Kit landmarks), live.
 *   🔵 CYAN  = the coach's ideal position for whichever joint(s) the
 *              *current* checkpoint is scoring.
 *
 * The actual skeleton-drawing math (which joints to correct for a given
 * checkpoint, and how) lives in {@link SkeletonGeometry}, shared with the
 * result screen's static "your form vs. ideal form" comparison view — this
 * class is just that math wired up to a live analyzed frame plus the
 * reference-axis / legend chrome specific to the live camera screen.
 */
public class TennisPoseOverlayView extends View {

    // User skeleton — bright green, solid
    private final Paint userBonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    // User landmark dots — white
    private final Paint userDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Coach correction — cyan, solid (drawn first so the user skeleton is on top)
    private final Paint coachBonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coachDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Reference axis — cyan dashed vertical, solid horizontal
    private final Paint axisDashPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint axisSolidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // Legend
    private final Paint legendBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static final float MIN_CONFIDENCE = 0.4f;

    private Pose pose = null;
    private int imageWidth = 1;
    private int imageHeight = 1;

    @Nullable private TennisFormMode mode;
    @Nullable private TennisPhase phase;

    private String lblYou;
    private String lblCoach;

    public TennisPoseOverlayView(Context context) {
        super(context); init();
    }
    public TennisPoseOverlayView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs); init();
    }
    public TennisPoseOverlayView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        lblYou = getContext().getString(R.string.legend_you);
        lblCoach = getContext().getString(R.string.legend_coach);

        userBonePaint.setColor(Color.parseColor("#FF00E676"));
        userBonePaint.setStyle(Paint.Style.STROKE);
        userBonePaint.setStrokeWidth(5f);
        userBonePaint.setStrokeCap(Paint.Cap.ROUND);

        userDotPaint.setColor(Color.WHITE);
        userDotPaint.setStyle(Paint.Style.FILL);

        coachBonePaint.setColor(Color.parseColor("#FF26C6DA"));
        coachBonePaint.setStyle(Paint.Style.STROKE);
        coachBonePaint.setStrokeWidth(4f);
        coachBonePaint.setStrokeCap(Paint.Cap.ROUND);
        coachBonePaint.setAlpha(220);

        coachDotPaint.setColor(Color.parseColor("#FF26C6DA"));
        coachDotPaint.setStyle(Paint.Style.FILL);
        coachDotPaint.setAlpha(220);

        axisDashPaint.setColor(Color.parseColor("#FF00BCD4"));
        axisDashPaint.setStyle(Paint.Style.STROKE);
        axisDashPaint.setStrokeWidth(2f);
        axisDashPaint.setAlpha(180);
        axisDashPaint.setPathEffect(new DashPathEffect(new float[]{14f, 10f}, 0));

        axisSolidPaint.setColor(Color.parseColor("#FF00BCD4"));
        axisSolidPaint.setStyle(Paint.Style.STROKE);
        axisSolidPaint.setStrokeWidth(2f);
        axisSolidPaint.setAlpha(160);

        legendBgPaint.setColor(Color.parseColor("#CC000000"));
        legendBgPaint.setStyle(Paint.Style.FILL);

        legendTextPaint.setColor(Color.WHITE);
        legendTextPaint.setTextSize(28f);
    }

    // ── Public API ────────────────────────────────────────────────────────

    /** Push a new pose frame; must be called on the main thread. */
    public void setPose(Pose pose, int imageWidth, int imageHeight) {
        this.pose = pose;
        this.imageWidth = Math.max(imageWidth, 1);
        this.imageHeight = Math.max(imageHeight, 1);
        invalidate();
    }

    public void clearPose() {
        this.pose = null;
        invalidate();
    }

    /** Which stroke is active, so the coach correction matches it. */
    public void setMode(TennisFormMode mode) {
        this.mode = mode;
        invalidate();
    }

    /** Which checkpoint is active, so the coach correction matches it. */
    public void setPhase(TennisPhase phase) {
        this.phase = phase;
        invalidate();
    }

    // ── Drawing ───────────────────────────────────────────────────────────

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (pose == null) return;
        if (pose.getAllPoseLandmarks().isEmpty()) return;

        final float vw = getWidth();
        final float vh = getHeight();
        final float sx = vw / imageWidth;
        final float sy = vh / imageHeight;

        SkeletonGeometry.LandmarkLookup lm = type -> scaledPt(landmark(type), sx, sy);

        drawVerticalAxis(canvas, sx, sy, vh);
        drawHorizontalRefLines(canvas, sx, sy);
        if (mode != null && phase != null) {
            SkeletonGeometry.drawCoachSkeleton(canvas, mode, phase, lm, coachBonePaint, coachDotPaint);
        }
        SkeletonGeometry.drawUserSkeleton(canvas, lm, userBonePaint, userDotPaint);
        drawLegend(canvas, vw);
    }

    private void drawVerticalAxis(Canvas canvas, float sx, float sy, float vh) {
        PointF mid = midPoint(
                landmark(PoseLandmark.LEFT_SHOULDER),
                landmark(PoseLandmark.RIGHT_SHOULDER), sx, sy);
        if (mid == null) return;
        Path p = new Path();
        p.moveTo(mid.x, 0);
        p.lineTo(mid.x, vh);
        canvas.drawPath(p, axisDashPaint);
    }

    private void drawHorizontalRefLines(Canvas canvas, float sx, float sy) {
        PointF ls = scaledPt(landmark(PoseLandmark.LEFT_SHOULDER), sx, sy);
        PointF rs = scaledPt(landmark(PoseLandmark.RIGHT_SHOULDER), sx, sy);
        if (ls != null && rs != null)
            canvas.drawLine(ls.x - 30, ls.y, rs.x + 30, rs.y, axisSolidPaint);

        PointF lh = scaledPt(landmark(PoseLandmark.LEFT_HIP), sx, sy);
        PointF rh = scaledPt(landmark(PoseLandmark.RIGHT_HIP), sx, sy);
        if (lh != null && rh != null)
            canvas.drawLine(lh.x - 30, lh.y, rh.x + 30, rh.y, axisSolidPaint);
    }

    // ── Legend ────────────────────────────────────────────────────────────

    private void drawLegend(Canvas canvas, float vw) {
        float lineLen = 36f;
        float textSize = legendTextPaint.getTextSize();
        float rowH = textSize + 10f;
        float pad = 12f;

        float maxTextW = Math.max(legendTextPaint.measureText(lblYou), legendTextPaint.measureText(lblCoach));
        float boxW = pad + lineLen + 8f + maxTextW + pad;
        float boxH = pad + rowH + rowH + pad;

        float right = vw - 16f;
        float top = 24f;
        float left = right - boxW;
        float bottom = top + boxH;

        canvas.drawRoundRect(new RectF(left, top, right, bottom), 10f, 10f, legendBgPaint);

        // Row 1 — You (green)
        float row1Y = top + pad + textSize;
        float lineY1 = row1Y - textSize / 2f;
        Paint greenStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        greenStroke.setColor(Color.parseColor("#FF00E676"));
        greenStroke.setStyle(Paint.Style.STROKE);
        greenStroke.setStrokeWidth(4f);
        greenStroke.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(left + pad, lineY1, left + pad + lineLen, lineY1, greenStroke);
        legendTextPaint.setColor(Color.WHITE);
        canvas.drawText(lblYou, left + pad + lineLen + 8f, row1Y, legendTextPaint);

        // Row 2 — Coach (cyan)
        float row2Y = row1Y + rowH;
        float lineY2 = row2Y - textSize / 2f;
        Paint cyanStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        cyanStroke.setColor(Color.parseColor("#FF26C6DA"));
        cyanStroke.setStyle(Paint.Style.STROKE);
        cyanStroke.setStrokeWidth(4f);
        cyanStroke.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(left + pad, lineY2, left + pad + lineLen, lineY2, cyanStroke);
        legendTextPaint.setColor(Color.parseColor("#FF26C6DA"));
        canvas.drawText(lblCoach, left + pad + lineLen + 8f, row2Y, legendTextPaint);
        legendTextPaint.setColor(Color.WHITE);
    }

    // ── Geometry helpers ─────────────────────────────────────────────────

    @Nullable
    private PoseLandmark landmark(int type) {
        if (pose == null) return null;
        PoseLandmark lm = pose.getPoseLandmark(type);
        if (lm == null || lm.getInFrameLikelihood() < MIN_CONFIDENCE) return null;
        return lm;
    }

    @Nullable
    private static PointF scaledPt(@Nullable PoseLandmark lm, float sx, float sy) {
        if (lm == null) return null;
        return new PointF(lm.getPosition().x * sx, lm.getPosition().y * sy);
    }

    @Nullable
    private static PointF midPoint(@Nullable PoseLandmark a, @Nullable PoseLandmark b, float sx, float sy) {
        if (a == null || b == null) return null;
        return new PointF(
                (a.getPosition().x + b.getPosition().x) / 2f * sx,
                (a.getPosition().y + b.getPosition().y) / 2f * sy);
    }
}
