package tfsapps.formtennisai.result;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import java.util.Locale;

import tfsapps.formtennisai.R;
import tfsapps.formtennisai.model.TennisCoachModel;
import tfsapps.formtennisai.model.TennisFormMode;
import tfsapps.formtennisai.model.TennisPhase;
import tfsapps.formtennisai.model.TennisPoseResult;
import tfsapps.formtennisai.model.TennisStrokeResult;

/**
 * Displays the combined 4-checkpoint result of one guided swing:
 *   • Mode badge + star rating + overall score
 *   • Phase score chart (READY / TURN / IMPACT / FINISH — tappable to
 *     browse that checkpoint's detail, worst one highlighted)
 *   • Per-criterion breakdown for the selected checkpoint
 *   • Strengths and improvements for the selected checkpoint
 *   • "Try Again" returns to TennisCameraActivity
 *
 * Tennis counterpart of {@code tfsapps.formbaseballai.result.ResultActivity}.
 * The baseball version also renders a worst-phase skeleton comparison image
 * (user vs. coach) via a SkeletonBitmapRenderer, and has History/Billing/
 * Weak-Point-Trend wiring; none of those supporting classes were part of
 * this task's reference files or instructions, so this screen focuses on
 * the numeric score / breakdown / advice content TENNIS_SPEC.md's UI
 * instructions actually asked for ("結果とアドバイスを表示").
 */
public class TennisResultActivity extends AppCompatActivity {

    public static final String EXTRA_RESULT = "extra_tennis_stroke_result";

    private TennisStrokeResult result;
    private int selectedPhaseIndex;

    private LinearLayout phaseLayout;
    private TextView tvSelectedPhaseLabel;
    private TextView tvPhaseSummary;
    private TennisResultSkeletonView resultSkeletonView;
    private LinearLayout breakdownLayout;
    private TextView tvStrengths;
    private TextView tvImprovements;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_tennis_result);

        result = (TennisStrokeResult) getIntent().getSerializableExtra(EXTRA_RESULT);
        if (result == null) { finish(); return; }

        TextView tvMode = findViewById(R.id.tvResultMode);
        TextView tvStars = findViewById(R.id.tvStars);
        TextView tvScore = findViewById(R.id.tvScore);

        tvMode.setText(result.mode.getDisplayName(this));
        tvMode.setBackgroundColor(getColor(modeColorRes(result.mode)));

        tvStars.setText(result.starsString());
        tvScore.setText(String.format(Locale.getDefault(),
                "%s: %.0f / 100", getString(R.string.label_avg_score), result.overallScore));

        ((TextView) findViewById(R.id.tvPhaseScoresHeader)).setText(getString(R.string.result_phase_scores));
        ((TextView) findViewById(R.id.tvBreakdownHeader)).setText(getString(R.string.result_details));
        ((TextView) findViewById(R.id.tvStrengthsHeader)).setText(getString(R.string.result_strengths));
        ((TextView) findViewById(R.id.tvImprovementsHeader)).setText(getString(R.string.result_improvements));

        phaseLayout = findViewById(R.id.layoutPhaseScores);
        tvSelectedPhaseLabel = findViewById(R.id.tvSelectedPhaseLabel);
        tvPhaseSummary = findViewById(R.id.tvPhaseSummary);
        resultSkeletonView = findViewById(R.id.resultSkeletonView);
        breakdownLayout = findViewById(R.id.layoutBreakdown);
        tvStrengths = findViewById(R.id.tvStrengths);
        tvImprovements = findViewById(R.id.tvImprovements);

        selectedPhaseIndex = worstPhaseIndex();

        renderPhaseRows();
        showPhaseDetail(selectedPhaseIndex);

        findViewById(R.id.btnTryAgain).setOnClickListener(v -> finish());

        View contentLayout = findViewById(R.id.resultContentLayout);
        ViewCompat.setOnApplyWindowInsetsListener(contentLayout, (v, insets) -> {
            int navBarHeight = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            int basePadding = (int) dpToPx(32);
            v.setPadding(v.getPaddingLeft(), v.getPaddingTop(), v.getPaddingRight(), basePadding + navBarHeight);
            return insets;
        });
    }

    private int worstPhaseIndex() {
        int worst = 0;
        for (int i = 1; i < result.phaseResults.length; i++) {
            if (result.phaseResults[i].overallScore < result.phaseResults[worst].overallScore) worst = i;
        }
        return worst;
    }

    private void selectPhase(int phaseIndex) {
        if (phaseIndex == selectedPhaseIndex) return;
        selectedPhaseIndex = phaseIndex;
        renderPhaseRows();
        showPhaseDetail(phaseIndex);
    }

    // ── Phase score chart ────────────────────────────────────────────────

    private void renderPhaseRows() {
        phaseLayout.removeAllViews();
        int worst = worstPhaseIndex();
        for (int i = 0; i < result.phaseResults.length; i++) {
            phaseLayout.addView(buildPhaseRow(i, result.phaseResults[i].overallScore,
                    i == worst, i == selectedPhaseIndex));
        }
    }

    private LinearLayout buildPhaseRow(int phaseIndex, float score, boolean isWorst, boolean isSelected) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding((int) dpToPx(6), (int) dpToPx(6), (int) dpToPx(6), (int) dpToPx(6));
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackgroundColor(isSelected ? Color.parseColor("#994CAF50") : Color.TRANSPARENT);
        row.setClickable(true);
        row.setFocusable(true);
        row.setOnClickListener(v -> selectPhase(phaseIndex));

        TennisPhase phase = TennisPhase.fromIndex(phaseIndex);
        TextView tvLabel = new TextView(this);
        tvLabel.setText(result.mode.getPhaseLabel(this, phase));
        tvLabel.setTextColor(isWorst ? Color.parseColor("#FFEF5350") : Color.WHITE);
        tvLabel.setTextSize(12f);
        if (isWorst) tvLabel.setTypeface(tvLabel.getTypeface(), Typeface.BOLD);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                (int) dpToPx(120), LinearLayout.LayoutParams.WRAP_CONTENT);
        tvLabel.setLayoutParams(labelParams);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress((int) score);
        int barColor = isWorst ? Color.parseColor("#FFEF5350") : scoreColor(score);
        bar.setProgressTintList(ColorStateList.valueOf(barColor));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FF303030")));
        LinearLayout.LayoutParams barParams = new LinearLayout.LayoutParams(0, (int) dpToPx(14), 1f);
        barParams.gravity = Gravity.CENTER_VERTICAL;
        barParams.leftMargin = (int) dpToPx(8);
        bar.setLayoutParams(barParams);

        TextView tvNum = new TextView(this);
        tvNum.setText(String.format(Locale.getDefault(), "%.0f", score));
        tvNum.setTextColor(barColor);
        tvNum.setTextSize(12f);
        LinearLayout.LayoutParams numParams = new LinearLayout.LayoutParams(
                (int) dpToPx(36), LinearLayout.LayoutParams.WRAP_CONTENT);
        numParams.gravity = Gravity.CENTER_VERTICAL;
        numParams.leftMargin = (int) dpToPx(6);
        tvNum.setLayoutParams(numParams);

        row.addView(tvLabel);
        row.addView(bar);
        row.addView(tvNum);
        return row;
    }

    // ── Selected-phase detail ────────────────────────────────────────────

    private void showPhaseDetail(int phaseIndex) {
        TennisPoseResult r = result.phaseResults[phaseIndex];
        boolean isWorst = (phaseIndex == worstPhaseIndex());

        tvSelectedPhaseLabel.setText(String.format(Locale.getDefault(),
                "%s%s  (%.0f%%)",
                result.mode.getPhaseLabel(this, r.phase),
                isWorst ? " " + getString(R.string.result_worst_phase) : "",
                r.overallScore));

        resultSkeletonView.setResult(result.mode, r.phase, r.landmarks);

        // One-line overall verdict for this checkpoint, shown above the
        // skeleton/breakdown so the user gets a quick take before reading
        // the detailed strengths/improvements below.
        tvPhaseSummary.setText(phaseSummaryText(r.phase, r.overallScore));
        tvPhaseSummary.setTextColor(scoreColor(r.overallScore));

        // ── Per-criterion breakdown ──────────────────────────────────────
        breakdownLayout.removeAllViews();
        TennisCoachModel.Criterion[] criteria = TennisCoachModel.criteriaFor(result.mode, r.phase);
        if (r.criterionIds.length == 0) {
            TextView tvNone = new TextView(this);
            tvNone.setText(R.string.result_no_data);
            tvNone.setTextColor(Color.parseColor("#FF888888"));
            tvNone.setTextSize(13f);
            breakdownLayout.addView(tvNone);
        } else {
            for (int i = 0; i < r.criterionIds.length; i++) {
                String unit = (i < criteria.length) ? criteria[i].unit : "°";
                breakdownLayout.addView(buildComponentRow(
                        getString(r.criterionIds[i].displayNameRes),
                        r.componentScores[i],
                        r.measuredValues[i],
                        r.targetValues[i],
                        unit));
            }
        }

        // ── Strengths / improvements ──────────────────────────────────────
        StringBuilder sb = new StringBuilder();
        for (String s : r.strengths) sb.append("• ").append(s).append("\n");
        tvStrengths.setText(sb.toString().trim());

        StringBuilder sb2 = new StringBuilder();
        for (String s : r.improvements) sb2.append("• ").append(s).append("\n");
        tvImprovements.setText(sb2.toString().trim());
    }

    private LinearLayout buildComponentRow(String name, float score, float measured, float target, String unit) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, 10, 0, 10);

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);

        TextView tvName = new TextView(this);
        tvName.setText(name);
        tvName.setTextColor(Color.WHITE);
        tvName.setTextSize(14f);
        tvName.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView tvVal = new TextView(this);
        tvVal.setText(String.format(Locale.getDefault(), "%.0f%%", score));
        tvVal.setTextColor(scoreColor(score));
        tvVal.setTextSize(14f);
        tvVal.setGravity(Gravity.END);

        header.addView(tvName);
        header.addView(tvVal);

        ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress((int) score);
        bar.setProgressTintList(ColorStateList.valueOf(scoreColor(score)));
        bar.setProgressBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#FF303030")));
        LinearLayout.LayoutParams barParams =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 20);
        barParams.topMargin = 6;
        bar.setLayoutParams(barParams);

        TextView tvAngles = new TextView(this);
        if (!Float.isNaN(measured)) {
            tvAngles.setText(String.format(Locale.getDefault(), "%.0f%s → %.0f%s", measured, unit, target, unit));
        } else {
            tvAngles.setText("—");
        }
        tvAngles.setTextColor(Color.parseColor("#FF888888"));
        tvAngles.setTextSize(12f);
        LinearLayout.LayoutParams angleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        angleParams.topMargin = 4;
        tvAngles.setLayoutParams(angleParams);

        row.addView(header);
        row.addView(bar);
        row.addView(tvAngles);
        return row;
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /** One-line verdict for a checkpoint's overall score, e.g. "Impact
     *  looks good overall" — tiers roughly line up with scoreColor's
     *  green/amber/red bands, with an extra top tier for a clearly strong
     *  checkpoint so a great result reads as more than just "good". */
    private String phaseSummaryText(TennisPhase phase, float score) {
        String phaseLabel = result.mode.getPhaseLabel(this, phase);
        int templateRes;
        if (score >= 85f) templateRes = R.string.phase_summary_excellent;
        else if (score >= 70f) templateRes = R.string.phase_summary_good;
        else if (score >= 55f) templateRes = R.string.phase_summary_fair;
        else templateRes = R.string.phase_summary_needs_work;
        return getString(templateRes, phaseLabel);
    }

    private static int modeColorRes(TennisFormMode mode) {
        switch (mode) {
            case SERVE: return R.color.btn_serve;
            case FOREHAND: return R.color.btn_forehand;
            default: return R.color.btn_backhand;
        }
    }

    /** Green ≥80, amber ≥55, red otherwise. */
    private static int scoreColor(float score) {
        if (score >= 80) return Color.parseColor("#FF00E676");
        if (score >= 55) return Color.parseColor("#FFFFCA28");
        return Color.parseColor("#FFEF5350");
    }

    private float dpToPx(float dp) {
        return dp * getResources().getDisplayMetrics().density;
    }
}
