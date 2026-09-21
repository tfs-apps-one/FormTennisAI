package tfsapps.formtennisai.result;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import tfsapps.formtennisai.R;
import tfsapps.formtennisai.camera.SkeletonGeometry;
import tfsapps.formtennisai.model.TennisFormMode;
import tfsapps.formtennisai.model.TennisPhase;
import tfsapps.formtennisai.model.TennisPoseResult;

/**
 * Static "your form vs. ideal form" skeleton comparison for the result
 * screen — a plain (non-camera) counterpart of the live camera overlay,
 * drawn from a {@link TennisPoseResult.LandmarkSnapshot} instead of a live
 * MLKit {@code Pose}.
 *
 *   🟢 GREEN = the pose actually captured at this checkpoint.
 *   🔵 CYAN  = the ideal position for whichever joint(s) this checkpoint
 *              judged (same {@link SkeletonGeometry} math the live camera
 *              overlay uses, so the two never disagree on what "ideal"
 *              looks like).
 *
 * When the checkpoint was skipped without ever validating, there is no
 * captured frame to compare — the snapshot is null and this view shows a
 * short explanatory message instead of an empty box.
 */
public class TennisResultSkeletonView extends View {

    private static final float MIN_CONFIDENCE = 0.4f;

    /** Enlarges the drawn user/coach skeletons relative to the panel so
     *  the pose comparison is easier to read. Raised from 1.2x to 1.4x
     *  (and the panel itself grew from 260dp to 400dp in the layout) after
     *  feedback that the figures were still too small to make out. */
    private static final float SKELETON_ZOOM = 1.4f;

    private final Paint panelBgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint userBonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint userDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coachBonePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint coachDotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint legendTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint noDataTextPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    @Nullable private TennisFormMode mode;
    @Nullable private TennisPhase phase;
    @Nullable private TennisPoseResult.LandmarkSnapshot snapshot;

    public TennisResultSkeletonView(Context context) {
        super(context); init();
    }
    public TennisResultSkeletonView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs); init();
    }
    public TennisResultSkeletonView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr); init();
    }

    private void init() {
        panelBgPaint.setColor(Color.parseColor("#FF14181C"));
        panelBgPaint.setStyle(Paint.Style.FILL);

        userBonePaint.setColor(Color.parseColor("#FF00E676"));
        userBonePaint.setStyle(Paint.Style.STROKE);
        userBonePaint.setStrokeWidth(6f);
        userBonePaint.setStrokeCap(Paint.Cap.ROUND);

        userDotPaint.setColor(Color.WHITE);
        userDotPaint.setStyle(Paint.Style.FILL);

        coachBonePaint.setColor(Color.parseColor("#FF26C6DA"));
        coachBonePaint.setStyle(Paint.Style.STROKE);
        coachBonePaint.setStrokeWidth(5f);
        coachBonePaint.setStrokeCap(Paint.Cap.ROUND);
        coachBonePaint.setAlpha(220);

        coachDotPaint.setColor(Color.parseColor("#FF26C6DA"));
        coachDotPaint.setStyle(Paint.Style.FILL);
        coachDotPaint.setAlpha(220);

        legendTextPaint.setColor(Color.WHITE);
        legendTextPaint.setTextSize(30f);

        noDataTextPaint.setColor(Color.parseColor("#FF888888"));
        noDataTextPaint.setTextSize(28f);
        noDataTextPaint.setTextAlign(Paint.Align.CENTER);
    }

    /** Sets which checkpoint's comparison to draw. {@code snapshot} may be
     *  null (checkpoint was skipped without ever validating). */
    public void setResult(TennisFormMode mode, TennisPhase phase, @Nullable TennisPoseResult.LandmarkSnapshot snapshot) {
        this.mode = mode;
        this.phase = phase;
        this.snapshot = snapshot;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float vw = getWidth();
        float vh = getHeight();
        if (vw <= 0 || vh <= 0) return;

        canvas.drawRoundRect(new RectF(0, 0, vw, vh), 12f, 12f, panelBgPaint);

        if (snapshot == null || snapshot.types.length == 0 || mode == null || phase == null) {
            canvas.drawText(getContext().getString(R.string.skeleton_no_data), vw / 2f, vh / 2f, noDataTextPaint);
            return;
        }

        final float sx = vw / Math.max(snapshot.imageWidth, 1);
        final float sy = vh / Math.max(snapshot.imageHeight, 1);

        SkeletonGeometry.LandmarkLookup lm = type -> findScaled(type, sx, sy);

        // The raw camera frame this maps from usually has some margin
        // around the player, so the two skeletons read a bit small inside
        // this compact panel. A modest zoom centered on the panel keeps
        // both figures clearer without changing the underlying geometry.
        canvas.save();
        canvas.scale(SKELETON_ZOOM, SKELETON_ZOOM, vw / 2f, vh / 2f);
        SkeletonGeometry.drawCoachSkeleton(canvas, mode, phase, lm, coachBonePaint, coachDotPaint);
        SkeletonGeometry.drawUserSkeleton(canvas, lm, userBonePaint, userDotPaint);
        canvas.restore();

        drawLegend(canvas, vw, vh);
    }

    @Nullable
    private PointF findScaled(int type, float sx, float sy) {
        if (snapshot == null) return null;
        for (int i = 0; i < snapshot.types.length; i++) {
            if (snapshot.types[i] == type) {
                if (snapshot.confidences[i] < MIN_CONFIDENCE) return null;
                return new PointF(snapshot.xs[i] * sx, snapshot.ys[i] * sy);
            }
        }
        return null;
    }

    private void drawLegend(Canvas canvas, float vw, float vh) {
        String lblYou = getContext().getString(R.string.legend_you);
        String lblCoach = getContext().getString(R.string.legend_coach);
        float lineLen = 30f;
        float textSize = legendTextPaint.getTextSize();
        float pad = 10f;

        float rowY1 = vh - pad - textSize;
        float rowY2 = vh - pad;

        Paint greenStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        greenStroke.setColor(Color.parseColor("#FF00E676"));
        greenStroke.setStyle(Paint.Style.STROKE);
        greenStroke.setStrokeWidth(4f);
        greenStroke.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(pad, rowY1 - textSize / 3f, pad + lineLen, rowY1 - textSize / 3f, greenStroke);
        legendTextPaint.setColor(Color.WHITE);
        canvas.drawText(lblYou, pad + lineLen + 8f, rowY1, legendTextPaint);

        Paint cyanStroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        cyanStroke.setColor(Color.parseColor("#FF26C6DA"));
        cyanStroke.setStyle(Paint.Style.STROKE);
        cyanStroke.setStrokeWidth(4f);
        cyanStroke.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(pad, rowY2 - textSize / 3f, pad + lineLen, rowY2 - textSize / 3f, cyanStroke);
        legendTextPaint.setColor(Color.parseColor("#FF26C6DA"));
        canvas.drawText(lblCoach, pad + lineLen + 8f, rowY2, legendTextPaint);
        legendTextPaint.setColor(Color.WHITE);
    }
}
