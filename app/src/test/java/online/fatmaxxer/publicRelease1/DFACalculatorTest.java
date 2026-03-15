package online.fatmaxxer.publicRelease1;

import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Unit tests for DFACalculator.
 *
 * These run on the local JVM (no Android device needed).
 * In Android Studio: right-click this file → Run 'DFACalculatorTest'
 */
public class DFACalculatorTest {

    private DFACalculator calculator;

    // Known RR interval dataset with expected results
    private static final double[] SAMPLE_RR_INTERVALS = {
            635.0, 628.0, 627.0, 625.0, 624.0, 627.0, 624.0, 623.0, 633.0, 636.0,
            633.0, 628.0, 625.0, 628.0, 622.0, 621.0, 613.0, 608.0, 604.0, 612.0,
            620.0, 616.0, 611.0, 616.0, 614.0, 622.0, 627.0, 625.0, 622.0, 617.0,
            620.0, 622.0, 623.0, 615.0, 614.0, 627.0, 630.0, 632.0, 632.0, 632.0,
            631.0, 627.0, 629.0, 634.0, 628.0, 625.0, 629.0, 633.0, 632.0, 628.0,
            631.0, 631.0, 628.0, 623.0, 619.0, 618.0, 618.0, 628.0, 634.0, 631.0,
            626.0, 633.0, 637.0, 636.0, 632.0, 634.0, 625.0, 614.0, 610.0, 607.0,
            613.0, 616.0, 622.0, 625.0, 620.0, 633.0, 640.0, 639.0, 631.0, 626.0,
            634.0, 628.0, 615.0, 610.0, 607.0, 611.0, 613.0, 614.0, 611.0, 608.0,
            627.0, 625.0, 619.0, 618.0, 622.0, 625.0, 626.0, 625.0, 626.0, 624.0,
            631.0, 631.0, 619.0, 611.0, 608.0, 607.0, 602.0, 586.0, 583.0, 576.0,
            580.0, 571.0, 583.0, 591.0, 598.0, 607.0, 607.0, 621.0, 619.0, 622.0,
            613.0, 604.0, 607.0, 603.0, 604.0, 598.0, 595.0, 592.0, 589.0, 594.0,
            594.0, 602.0, 611.0, 614.0, 634.0, 635.0, 636.0, 628.0, 627.0, 628.0,
            626.0, 619.0, 616.0, 616.0, 622.0, 615.0, 607.0, 611.0, 610.0, 619.0,
            624.0, 625.0, 626.0, 633.0, 643.0, 647.0, 644.0, 644.0, 642.0, 645.0,
            637.0, 628.0, 632.0, 633.0, 625.0, 626.0, 623.0, 620.0, 620.0, 610.0,
            612.0, 612.0, 610.0, 614.0, 611.0, 609.0, 616.0, 624.0, 623.0, 618.0,
            622.0, 623.0, 625.0, 629.0, 621.0, 622.0, 617.0, 619.0, 618.0, 610.0,
            607.0, 606.0, 611.0
    };

    @Before
    public void setUp() {
        calculator = new DFACalculator(500);
    }

    // ========================================================================
    // DFA α1 TESTS
    // ========================================================================

    @Test
    public void selfTest_passes() {
        assertTrue(calculator.selfTest());
    }

    @Test
    public void dfaAlpha1V1_matchesReferenceValue() {
        double result = calculator.dfaAlpha1V1(SAMPLE_RR_INTERVALS, 2, 4, 30);
        assertEquals(1.5503173309573228, result, 0.0);
    }

    @Test
    public void dfaAlpha1V2_returnsReasonableValue() {
        double result = calculator.dfaAlpha1V2(SAMPLE_RR_INTERVALS, 2, 4, 30);
        // v2 uses smoothness priors — result should be in a reasonable α1 range
        assertTrue("α1 should be positive, got " + result, result > 0);
        assertTrue("α1 should be < 3.0 for normal RR data, got " + result, result < 3.0);
    }

    @Test
    public void dfaAlpha1_matrixCacheWorks() {
        calculator.dfaAlpha1V2(SAMPLE_RR_INTERVALS, 2, 4, 30);
        int firstRunMisses = calculator.getCacheMisses();
        assertTrue("First run should have cache misses", firstRunMisses > 0);

        calculator.dfaAlpha1V2(SAMPLE_RR_INTERVALS, 2, 4, 30);
        int secondRunMisses = calculator.getCacheMisses();
        assertEquals("Second run should reuse cache", firstRunMisses, secondRunMisses);
    }

    @Test
    public void dfaAlpha1_lambdaChangeInvalidatesNothing() {
        calculator.dfaAlpha1V2(SAMPLE_RR_INTERVALS, 2, 4, 30);
        int missesBefore = calculator.getCacheMisses();

        calculator.setLambda(300);
        calculator.dfaAlpha1V2(SAMPLE_RR_INTERVALS, 2, 4, 30);
        int missesAfter = calculator.getCacheMisses();

        assertTrue("Different lambda should cause new cache misses",
                missesAfter > missesBefore);
    }

    // ========================================================================
    // RMSSD TESTS
    // ========================================================================

    @Test
    public void rmssd_positiveForVariableData() {
        double rmssd = calculator.getRMSSD(SAMPLE_RR_INTERVALS);
        assertTrue("RMSSD should be positive, got " + rmssd, rmssd > 0);
    }

    @Test
    public void rmssd_zeroForConstantIntervals() {
        double[] constant = {600.0, 600.0, 600.0, 600.0, 600.0};
        double rmssd = calculator.getRMSSD(constant);
        assertEquals("RMSSD of constant intervals should be 0", 0.0, rmssd, 0.001);
    }

    @Test
    public void rmssd_knownCalculation() {
        // RR intervals: 600, 610, 600, 610
        // Successive diffs: 10, 10, 10
        // Squares: 100, 100, 100
        // Mean of squares: 100
        // RMSSD: sqrt(100) = 10.0
        double[] rrs = {600.0, 610.0, 600.0, 610.0};
        double rmssd = calculator.getRMSSD(rrs);
        assertEquals(10.0, rmssd, 0.01);
    }

    // ========================================================================
    // VECTOR MATH TESTS
    // ========================================================================

    @Test
    public void vMean_correct() {
        assertEquals(2.0, DFACalculator.vMean(new double[]{1, 2, 3}), 0.001);
        assertEquals(5.0, DFACalculator.vMean(new double[]{5}), 0.001);
    }

    @Test
    public void vSum_correct() {
        assertEquals(6.0, DFACalculator.vSum(new double[]{1, 2, 3}), 0.001);
        assertEquals(0.0, DFACalculator.vSum(new double[]{-1, 1}), 0.001);
    }

    @Test
    public void vCumsum_correct() {
        double[] result = DFACalculator.vCumsum(new double[]{1, 2, 3});
        assertArrayEquals(new double[]{1, 3, 6}, result, 0.001);
    }

    @Test
    public void vReverse_correct() {
        double[] result = DFACalculator.vReverse(new double[]{1, 2, 3});
        assertArrayEquals(new double[]{3, 2, 1}, result, 0.001);
    }

    @Test
    public void vDifferential_correct() {
        double[] result = DFACalculator.vDifferential(new double[]{10, 13, 11, 15});
        assertArrayEquals(new double[]{3, -2, 4}, result, 0.001);
    }

    @Test
    public void vAbs_correct() {
        double[] result = DFACalculator.vAbs(new double[]{-3, 2, -1});
        assertArrayEquals(new double[]{3, 2, 1}, result, 0.001);
    }

    @Test
    public void vSubScalar_correct() {
        double[] result = DFACalculator.vSubScalar(new double[]{5, 10, 15}, 5);
        assertArrayEquals(new double[]{0, 5, 10}, result, 0.001);
    }

    @Test
    public void vSlice_correct() {
        double[] result = DFACalculator.vSlice(new double[]{10, 20, 30, 40, 50}, 1, 3);
        assertArrayEquals(new double[]{20, 30, 40}, result, 0.001);
    }

    @Test
    public void vSubtract_correct() {
        double[] result = DFACalculator.vSubtract(
                new double[]{10, 20, 30}, new double[]{1, 2, 3});
        assertArrayEquals(new double[]{9, 18, 27}, result, 0.001);
    }

    @Test(expected = IllegalArgumentException.class)
    public void vSubtract_throwsOnUnequalLengths() {
        DFACalculator.vSubtract(new double[]{1, 2}, new double[]{1}, 0, 2);
    }

    @Test
    public void vLogN_base2() {
        double[] result = DFACalculator.vLogN(new double[]{1, 2, 4, 8}, 2);
        assertArrayEquals(new double[]{0, 1, 2, 3}, result, 0.001);
    }

    @Test
    public void arange_correct() {
        double[] result = DFACalculator.arange(0, 10, 5);
        assertEquals(5, result.length);
        assertEquals(0.0, result[0], 0.001);
        assertEquals(2.0, result[1], 0.001);
        assertEquals(8.0, result[4], 0.001);
    }
}
