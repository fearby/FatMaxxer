package online.fatmaxxer.fatmaxxer;

import android.content.Context;
import android.graphics.Color;

import com.github.mikephil.charting.charts.CombinedChart;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.components.Legend;
import com.github.mikephil.charting.components.LimitLine;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.components.YAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;

import java.util.ArrayList;
import java.util.List;

/**
 * Alpha1ChartManager — FatMaxxer (fearby fork)
 *
 * Replaces GraphView (jjoe64) with MPAndroidChart.
 *
 * WHY:
 *   GraphView has not had a meaningful update since 2019. It has
 *   known rendering bugs on modern Android, no dual-axis support
 *   without workarounds, and a quirky API. MPAndroidChart is
 *   actively maintained, has proper dual-Y-axis support, smooth
 *   real-time updates, and looks far better.
 *
 * USAGE:
 *   1. In your layout XML, replace:
 *        <com.jjoe64.graphview.GraphView ... />
 *      with:
 *        <com.github.mikephil.charting.charts.LineChart
 *            android:id="@+id/chart_alpha1"
 *            android:layout_width="match_parent"
 *            android:layout_height="match_parent" />
 *
 *   2. In MainActivity:
 *        LineChart chart = binding.chartAlpha1;
 *        Alpha1ChartManager chartManager = new Alpha1ChartManager(this, chart);
 *        chartManager.init();
 *
 *   3. On each α1 update:
 *        chartManager.addDataPoint(elapsedMinutes, alpha1Value, hrBpm, rmssd, artifacts);
 *
 *   4. On BLE disconnect / session end:
 *        chartManager.clear();
 */
public class Alpha1ChartManager {

    // ---- Chart viewport ----
    private static final float VIEWPORT_MINUTES = 2.0f;  // rolling 2-minute window

    // ---- Alpha1 threshold lines (× 100 for display, same as upstream) ----
    private static final float VT1_THRESHOLD = 75f;   // α1 = 0.75
    private static final float VT2_THRESHOLD = 50f;   // α1 = 0.50

    // ---- Dataset indices ----
    private static final int DS_ALPHA1    = 0;
    private static final int DS_HR        = 1;
    private static final int DS_RMSSD     = 2;
    private static final int DS_RR        = 3;
    private static final int DS_ARTIFACTS = 4;

    // ---- Colours (matching upstream conventions) ----
    private static final int COLOR_ALPHA1    = Color.parseColor("#00C853");  // green
    private static final int COLOR_HR        = Color.parseColor("#FF1744");  // red
    private static final int COLOR_RMSSD     = Color.parseColor("#00E5FF");  // cyan
    private static final int COLOR_RR        = Color.parseColor("#E040FB");  // magenta
    private static final int COLOR_ARTIFACTS = Color.parseColor("#2979FF");  // blue
    private static final int COLOR_VT1       = Color.parseColor("#FFD600");  // yellow
    private static final int COLOR_VT2       = Color.parseColor("#FF1744");  // red
    private static final int COLOR_GRID      = Color.parseColor("#33FFFFFF");
    private static final int COLOR_BG        = Color.parseColor("#0E1410");
    private static final int COLOR_TEXT      = Color.parseColor("#DEE4DA");

    private final Context context;
    private final LineChart chart;

    private LineDataSet dsAlpha1;
    private LineDataSet dsHr;
    private LineDataSet dsRmssd;
    private LineDataSet dsRr;
    private LineDataSet dsArtifacts;

    // Visibility flags — mirror user settings
    private boolean showAlpha1    = true;
    private boolean showHr        = true;
    private boolean showRmssd     = true;
    private boolean showRr        = false;
    private boolean showArtifacts = true;

    public Alpha1ChartManager(Context context, LineChart chart) {
        this.context = context;
        this.chart = chart;
    }

    /**
     * Call once after inflating the layout to configure the chart.
     */
    public void init() {
        // ---- Global chart appearance ----
        chart.setBackgroundColor(COLOR_BG);
        chart.setGridBackgroundColor(COLOR_BG);
        chart.setDrawGridBackground(true);
        chart.setDrawBorders(false);
        chart.setTouchEnabled(true);
        chart.setDragEnabled(false);   // real-time: no drag
        chart.setScaleEnabled(false);  // real-time: no pinch zoom
        chart.setPinchZoom(false);
        chart.setDescription(null);    // remove "Description Label"
        chart.setNoDataText("Waiting for sensor...");
        chart.setNoDataTextColor(COLOR_TEXT);

        // ---- Legend ----
        Legend legend = chart.getLegend();
        legend.setTextColor(COLOR_TEXT);
        legend.setTextSize(10f);
        legend.setForm(Legend.LegendForm.LINE);
        legend.setVerticalAlignment(Legend.LegendVerticalAlignment.BOTTOM);
        legend.setHorizontalAlignment(Legend.LegendHorizontalAlignment.LEFT);
        legend.setOrientation(Legend.LegendOrientation.HORIZONTAL);
        legend.setDrawInside(false);

        // ---- X axis (time in minutes) ----
        XAxis xAxis = chart.getXAxis();
        xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
        xAxis.setTextColor(COLOR_TEXT);
        xAxis.setGridColor(COLOR_GRID);
        xAxis.setAxisLineColor(COLOR_TEXT);
        xAxis.setLabelCount(5, true);
        xAxis.setValueFormatter((value, axis) ->
                String.format("%.1fm", value));

        // ---- Primary Y axis (left): α1×100, HR, RMSSD, RR ----
        YAxis leftAxis = chart.getAxisLeft();
        leftAxis.setTextColor(COLOR_TEXT);
        leftAxis.setGridColor(COLOR_GRID);
        leftAxis.setAxisLineColor(COLOR_TEXT);
        leftAxis.setAxisMinimum(0f);
        leftAxis.setAxisMaximum(200f);
        leftAxis.setLabelCount(9, true);  // 0, 25, 50, 75, 100, 125, 150, 175, 200
        leftAxis.setGridLineWidth(0.5f);

        // VT1 limit line (α1 = 0.75 → displayed as 75)
        LimitLine vt1Line = new LimitLine(VT1_THRESHOLD, "VT1 (α1=0.75)");
        vt1Line.setLineColor(COLOR_VT1);
        vt1Line.setLineWidth(1.5f);
        vt1Line.setTextColor(COLOR_VT1);
        vt1Line.setTextSize(9f);
        vt1Line.enableDashedLine(10f, 5f, 0f);
        leftAxis.addLimitLine(vt1Line);

        // VT2 limit line (α1 = 0.50 → displayed as 50)
        LimitLine vt2Line = new LimitLine(VT2_THRESHOLD, "VT2 (α1=0.50)");
        vt2Line.setLineColor(COLOR_VT2);
        vt2Line.setLineWidth(1.5f);
        vt2Line.setTextColor(COLOR_VT2);
        vt2Line.setTextSize(9f);
        vt2Line.enableDashedLine(10f, 5f, 0f);
        leftAxis.addLimitLine(vt2Line);

        // ---- Secondary Y axis (right): artifacts % ----
        YAxis rightAxis = chart.getAxisRight();
        rightAxis.setTextColor(COLOR_ARTIFACTS);
        rightAxis.setAxisLineColor(COLOR_ARTIFACTS);
        rightAxis.setGridColor(Color.TRANSPARENT);
        rightAxis.setAxisMinimum(0f);
        rightAxis.setAxisMaximum(10f);
        rightAxis.setLabelCount(6, true);
        rightAxis.setValueFormatter((value, axis) ->
                String.format("%.0f%%", value));

        // ---- Initialise datasets ----
        dsAlpha1    = makeDataSet("α1 ×100",    COLOR_ALPHA1,    2.5f, YAxis.AxisDependency.LEFT);
        dsHr        = makeDataSet("HR (BPM)",   COLOR_HR,        1.5f, YAxis.AxisDependency.LEFT);
        dsRmssd     = makeDataSet("RMSSD",      COLOR_RMSSD,     1.5f, YAxis.AxisDependency.LEFT);
        dsRr        = makeDataSet("RR/5",       COLOR_RR,        1.0f, YAxis.AxisDependency.LEFT);
        dsArtifacts = makeDataSet("Artifacts%", COLOR_ARTIFACTS, 1.5f, YAxis.AxisDependency.RIGHT);

        applyVisibility();
        updateChart();
    }

    /**
     * Add a new data point. Call this every time α1 is recalculated (every ~20s).
     *
     * @param elapsedMinutes Time since session start, in minutes
     * @param alpha1         Raw α1 value (0.0–2.0); will be × 100 for display
     * @param hrBpm          Heart rate in BPM
     * @param rmssd          RMSSD value (ms)
     * @param rrIntervalMs   Latest RR interval in ms (will be / 5 for display)
     * @param artifactPct    Artifact percentage (0–10)
     */
    public void addDataPoint(float elapsedMinutes, float alpha1, float hrBpm,
                             float rmssd, float rrIntervalMs, float artifactPct) {
        addEntry(dsAlpha1,    elapsedMinutes, alpha1 * 100f);
        addEntry(dsHr,        elapsedMinutes, hrBpm);
        addEntry(dsRmssd,     elapsedMinutes, rmssd);
        addEntry(dsRr,        elapsedMinutes, rrIntervalMs / 5f);
        addEntry(dsArtifacts, elapsedMinutes, artifactPct);

        // Slide the X viewport — always show the last 2 minutes
        if (elapsedMinutes > VIEWPORT_MINUTES) {
            chart.getXAxis().setAxisMinimum(elapsedMinutes - VIEWPORT_MINUTES);
            chart.getXAxis().setAxisMaximum(elapsedMinutes);
        }

        chart.notifyDataSetChanged();
        chart.invalidate();
    }

    /**
     * Toggle individual series on/off (mirrors the upstream settings).
     */
    public void setSeriesVisible(boolean alpha1, boolean hr, boolean rmssd,
                                 boolean rr, boolean artifacts) {
        this.showAlpha1    = alpha1;
        this.showHr        = hr;
        this.showRmssd     = rmssd;
        this.showRr        = rr;
        this.showArtifacts = artifacts;
        applyVisibility();
        chart.invalidate();
    }

    /** Clear all data (e.g. on disconnect or new session). */
    public void clear() {
        dsAlpha1.clear();
        dsHr.clear();
        dsRmssd.clear();
        dsRr.clear();
        dsArtifacts.clear();
        chart.getXAxis().resetAxisMinimum();
        chart.getXAxis().resetAxisMaximum();
        chart.notifyDataSetChanged();
        chart.invalidate();
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private LineDataSet makeDataSet(String label, int color, float width, YAxis.AxisDependency axis) {
        LineDataSet ds = new LineDataSet(new ArrayList<>(), label);
        ds.setColor(color);
        ds.setLineWidth(width);
        ds.setDrawCircles(false);
        ds.setDrawValues(false);
        ds.setMode(LineDataSet.Mode.LINEAR);
        ds.setAxisDependency(axis);
        return ds;
    }

    private void addEntry(LineDataSet ds, float x, float y) {
        ds.addEntry(new Entry(x, y));
        // Trim old entries outside the rolling window to keep memory bounded
        while (ds.getEntryCount() > 0 &&
               ds.getEntryForIndex(0).getX() < x - VIEWPORT_MINUTES - 0.5f) {
            ds.removeFirst();
        }
    }

    private void applyVisibility() {
        dsAlpha1.setVisible(showAlpha1);
        dsHr.setVisible(showHr);
        dsRmssd.setVisible(showRmssd);
        dsRr.setVisible(showRr);
        dsArtifacts.setVisible(showArtifacts);
    }

    private void updateChart() {
        List<LineDataSet> sets = new ArrayList<>();
        sets.add(dsAlpha1);
        sets.add(dsHr);
        sets.add(dsRmssd);
        sets.add(dsRr);
        sets.add(dsArtifacts);
        chart.setData(new LineData(sets.toArray(new LineDataSet[0])));
    }
}
