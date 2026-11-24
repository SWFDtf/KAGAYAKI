package de.swfdtf.audio;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AudioDspTest {

    private static final float SAMPLE_RATE = 44100.0f;

    @Test
    void lowpassHighpassAndBandpassAttenuateExpectedFrequencies() {
        float[] lowTone = generateSine(150.0, 0.5, SAMPLE_RATE, 1.0f);
        float[] midTone = generateSine(1500.0, 0.5, SAMPLE_RATE, 1.0f);
        float[] highTone = generateSine(9000.0, 0.5, SAMPLE_RATE, 1.0f);

        // Lowpass at 800 Hz: 150 Hz passes cleanly, 9000 Hz heavily attenuated
        AudioDsp.BiquadFilter lowpass = AudioDsp.BiquadFilter.createLowpass(SAMPLE_RATE, 800.0f, 0.7071f);
        float[] lpLow = lowTone.clone();
        lowpass.processBuffer(lpLow);
        lowpass.reset();
        float[] lpHigh = highTone.clone();
        lowpass.processBuffer(lpHigh);

        float lpLowRms = AudioDsp.computeRms(lpLow, 4096, lpLow.length - 4096);
        float lpHighRms = AudioDsp.computeRms(lpHigh, 4096, lpHigh.length - 4096);
        assertTrue(lpLowRms > 0.65f, "150 Hz should pass 800 Hz lowpass, got RMS=" + lpLowRms);
        assertTrue(lpHighRms < 0.02f, "9000 Hz should be attenuated by 800 Hz lowpass, got RMS=" + lpHighRms);

        // Highpass at 2500 Hz: 9000 Hz passes cleanly, 150 Hz heavily attenuated
        AudioDsp.BiquadFilter highpass = AudioDsp.BiquadFilter.createHighpass(SAMPLE_RATE, 2500.0f, 0.7071f);
        float[] hpLow = lowTone.clone();
        highpass.processBuffer(hpLow);
        highpass.reset();
        float[] hpHigh = highTone.clone();
        highpass.processBuffer(hpHigh);

        float hpLowRms = AudioDsp.computeRms(hpLow, 4096, hpLow.length - 4096);
        float hpHighRms = AudioDsp.computeRms(hpHigh, 4096, hpHigh.length - 4096);
        assertTrue(hpHighRms > 0.65f, "9000 Hz should pass 2500 Hz highpass, got RMS=" + hpHighRms);
        assertTrue(hpLowRms < 0.02f, "150 Hz should be attenuated by 2500 Hz highpass, got RMS=" + hpLowRms);

        // Bandpass centered at 1500 Hz (Q=2.5): 1500 Hz passes near unity while 150 Hz and 9000 Hz drop
        AudioDsp.BiquadFilter bandpass = AudioDsp.BiquadFilter.createBandpass(SAMPLE_RATE, 1500.0f, 2.5f);
        float[] bpLow = lowTone.clone();
        bandpass.processBuffer(bpLow);
        bandpass.reset();
        float[] bpMid = midTone.clone();
        bandpass.processBuffer(bpMid);
        bandpass.reset();
        float[] bpHigh = highTone.clone();
        bandpass.processBuffer(bpHigh);

        float bpLowRms = AudioDsp.computeRms(bpLow, 4096, bpLow.length - 4096);
        float bpMidRms = AudioDsp.computeRms(bpMid, 4096, bpMid.length - 4096);
        float bpHighRms = AudioDsp.computeRms(bpHigh, 4096, bpHigh.length - 4096);
        assertTrue(bpMidRms > 0.65f, "1500 Hz should pass 1500 Hz bandpass, got RMS=" + bpMidRms);
        assertTrue(bpLowRms < 0.1f, "150 Hz should be attenuated by 1500 Hz bandpass, got RMS=" + bpLowRms);
        assertTrue(bpHighRms < 0.15f, "9000 Hz should be attenuated by 1500 Hz bandpass, got RMS=" + bpHighRms);
    }

    @Test
    void computesExactRmsForConstantAndSineSignals() {
        float[] constant = new float[1000];
        java.util.Arrays.fill(constant, -0.42f);
        assertEquals(0.42f, AudioDsp.computeRms(constant, 0, constant.length), 1e-5f);
        assertEquals(0.42f, AudioDsp.computeRms(constant, 250, 500), 1e-5f);
        assertEquals(0.0f, AudioDsp.computeRms(constant, 0, 0), 1e-6f);

        // Integer number of cycles of amplitude 0.8 -> RMS = 0.8 / sqrt(2)
        int n = 4410; // exactly 44 cycles at 440 Hz? 44100 / 100 = 441 -> 100 Hz = 10 cycles in 4410
        float[] sine = generateSine(100.0, 0.1, SAMPLE_RATE, 0.8f);
        assertEquals(n, sine.length);
        double expectedRms = 0.8 / Math.sqrt(2.0);
        assertEquals(expectedRms, AudioDsp.computeRms(sine, 0, sine.length), 1e-4);

        assertThrows(IllegalArgumentException.class, () -> AudioDsp.computeRms(constant, -1, 10));
        assertThrows(IllegalArgumentException.class, () -> AudioDsp.computeRms(constant, 900, 200));
    }

    @Test
    void spectrumBandsIsolatePeaksAcrossLogarithmicBands() {
        int bandCount = 16;

        float[] silence = new float[2048];
        float[] silenceBands = AudioDsp.computeSpectrumBands(silence, 0, silence.length, bandCount);
        assertEquals(bandCount, silenceBands.length);
        for (float v : silenceBands) {
            assertEquals(0.0f, v, 1e-6f);
        }

        float[] low = generateSine(120.0, 2048.0 / SAMPLE_RATE, SAMPLE_RATE, 0.9f);
        float[] mid = generateSine(1000.0, 2048.0 / SAMPLE_RATE, SAMPLE_RATE, 0.9f);
        float[] high = generateSine(7000.0, 2048.0 / SAMPLE_RATE, SAMPLE_RATE, 0.9f);

        float[] lowBands = AudioDsp.computeSpectrumBands(low, 0, low.length, bandCount);
        float[] midBands = AudioDsp.computeSpectrumBands(mid, 0, mid.length, bandCount);
        float[] highBands = AudioDsp.computeSpectrumBands(high, 0, high.length, bandCount);

        int lowPeak = argMax(lowBands);
        int midPeak = argMax(midBands);
        int highPeak = argMax(highBands);

        assertTrue(lowPeak < midPeak && midPeak < highPeak,
                "Expected monotonic peak bands low < mid < high, got: " + lowPeak + ", " + midPeak + ", " + highPeak);

        // Verify normalized bounds [0, 1] and strong peak isolation against distant bands
        assertAllInUnitInterval(lowBands);
        assertAllInUnitInterval(midBands);
        assertAllInUnitInterval(highBands);

        assertTrue(midBands[midPeak] > 0.7f, "Mid tone peak magnitude should be prominent, got " + midBands[midPeak]);
        assertTrue(midBands[0] < 0.08f, "Distant low band should be near zero for 1 kHz tone, got " + midBands[0]);
        assertTrue(midBands[bandCount - 1] < 0.05f, "Distant high band should be near zero for 1 kHz tone, got " + midBands[bandCount - 1]);
    }

    private static float[] generateSine(double freqHz, double durationSeconds, float sampleRate, float amplitude) {
        int count = (int) Math.round(durationSeconds * sampleRate);
        float[] out = new float[count];
        for (int i = 0; i < count; i++) {
            out[i] = (float) (amplitude * Math.sin((2.0 * Math.PI * freqHz * i) / sampleRate));
        }
        return out;
    }

    private static int argMax(float[] values) {
        int bestIdx = 0;
        for (int i = 1; i < values.length; i++) {
            if (values[i] > values[bestIdx]) {
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    private static void assertAllInUnitInterval(float[] values) {
        for (float v : values) {
            assertTrue(Float.isFinite(v) && v >= 0.0f && v <= 1.0f, "Value out of [0, 1]: " + v);
        }
    }
}
