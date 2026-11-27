package de.swfdtf.audio;

import java.util.Objects;

/**
 * Digital Signal Processing (DSP) utilities for KAGAYAKI's audio engine.
 *
 * <p>Provides second-order IIR biquad filters ({@link BiquadFilter}) for lowpass, highpass,
 * and bandpass shaping, RMS amplitude calculation for real-time lip-sync analysis, and
 * Hann-windowed logarithmic DFT spectrum band extraction for the Music Room visualizer.</p>
 */
public final class AudioDsp {

    /** Default sample rate assumed by {@link #computeSpectrumBands(float[], int, int, int)}. */
    public static final float DEFAULT_SAMPLE_RATE = 44100.0f;

    private static final double MIN_SPECTRUM_FREQ_HZ = 40.0;
    private static final double MAX_SPECTRUM_FREQ_HZ = 18000.0;
    private static final int MIN_FFT_SIZE = 4096;

    private AudioDsp() {
    }

    /**
     * Filter topology supported by {@link BiquadFilter}.
     */
    public enum FilterType {
        /** Attenuates frequencies above the cutoff frequency. */
        LOWPASS,
        /** Attenuates frequencies below the cutoff frequency. */
        HIGHPASS,
        /** Passes frequencies within a band around the center frequency. */
        BANDPASS
    }

    /**
     * Stateful second-order IIR biquad filter implemented in Direct Form II Transposed
     * with double-precision internal arithmetic and state registers for numerical stability.
     */
    public static final class BiquadFilter {

        private final FilterType type;
        private final float sampleRate;
        private final float cutoffHz;
        private final float q;

        private final double b0;
        private final double b1;
        private final double b2;
        private final double a1;
        private final double a2;

        private double z1;
        private double z2;

        private BiquadFilter(
                FilterType type,
                float sampleRate,
                float cutoffHz,
                float q,
                double b0,
                double b1,
                double b2,
                double a1,
                double a2
        ) {
            this.type = type;
            this.sampleRate = sampleRate;
            this.cutoffHz = cutoffHz;
            this.q = q;
            this.b0 = b0;
            this.b1 = b1;
            this.b2 = b2;
            this.a1 = a1;
            this.a2 = a2;
        }

        /**
         * Creates a second-order lowpass biquad filter.
         *
         * @param sampleRate sample rate in Hz ({@code > 0})
         * @param cutoffHz   cutoff frequency in Hz ({@code > 0})
         * @param q          resonance quality factor ({@code > 0}, {@code ~0.7071f} for Butterworth)
         * @return configured lowpass {@link BiquadFilter}
         */
        public static BiquadFilter createLowpass(float sampleRate, float cutoffHz, float q) {
            return create(FilterType.LOWPASS, sampleRate, cutoffHz, q);
        }

        /**
         * Creates a second-order highpass biquad filter.
         *
         * @param sampleRate sample rate in Hz ({@code > 0})
         * @param cutoffHz   cutoff frequency in Hz ({@code > 0})
         * @param q          resonance quality factor ({@code > 0}, {@code ~0.7071f} for Butterworth)
         * @return configured highpass {@link BiquadFilter}
         */
        public static BiquadFilter createHighpass(float sampleRate, float cutoffHz, float q) {
            return create(FilterType.HIGHPASS, sampleRate, cutoffHz, q);
        }

        /**
         * Creates a second-order constant-0 dB-peak bandpass biquad filter.
         *
         * @param sampleRate sample rate in Hz ({@code > 0})
         * @param cutoffHz   center frequency in Hz ({@code > 0})
         * @param q          quality factor ({@code > 0})
         * @return configured bandpass {@link BiquadFilter}
         */
        public static BiquadFilter createBandpass(float sampleRate, float cutoffHz, float q) {
            return create(FilterType.BANDPASS, sampleRate, cutoffHz, q);
        }

        private static BiquadFilter create(FilterType type, float sampleRate, float cutoffHz, float q) {
            Objects.requireNonNull(type, "type must not be null");
            if (!Float.isFinite(sampleRate) || sampleRate <= 0.0f) {
                throw new IllegalArgumentException("sampleRate must be finite and > 0, got: " + sampleRate);
            }
            if (!Float.isFinite(cutoffHz) || cutoffHz <= 0.0f) {
                throw new IllegalArgumentException("cutoffHz must be finite and > 0, got: " + cutoffHz);
            }
            if (!Float.isFinite(q) || q <= 0.0f) {
                throw new IllegalArgumentException("q must be finite and > 0, got: " + q);
            }

            float clampedCutoff = Math.min(cutoffHz, sampleRate * 0.499f);
            double w0 = (2.0 * Math.PI * clampedCutoff) / sampleRate;
            double cosW0 = Math.cos(w0);
            double sinW0 = Math.sin(w0);
            double alpha = sinW0 / (2.0 * q);

            double rb0;
            double rb1;
            double rb2;
            switch (type) {
                case LOWPASS -> {
                    rb0 = (1.0 - cosW0) * 0.5;
                    rb1 = 1.0 - cosW0;
                    rb2 = (1.0 - cosW0) * 0.5;
                }
                case HIGHPASS -> {
                    rb0 = (1.0 + cosW0) * 0.5;
                    rb1 = -(1.0 + cosW0);
                    rb2 = (1.0 + cosW0) * 0.5;
                }
                case BANDPASS -> {
                    rb0 = alpha;
                    rb1 = 0.0;
                    rb2 = -alpha;
                }
                default -> throw new IllegalStateException("Unexpected FilterType: " + type);
            }

            double ra0 = 1.0 + alpha;
            double ra1 = -2.0 * cosW0;
            double ra2 = 1.0 - alpha;

            return new BiquadFilter(
                    type,
                    sampleRate,
                    clampedCutoff,
                    q,
                    rb0 / ra0,
                    rb1 / ra0,
                    rb2 / ra0,
                    ra1 / ra0,
                    ra2 / ra0
            );
        }

        /**
         * Processes a single PCM sample through the filter and advances internal delay state.
         *
         * @param sample input sample
         * @return filtered sample
         */
        public float processSample(float sample) {
            double in = Float.isFinite(sample) ? sample : 0.0;
            double out = b0 * in + z1;
            z1 = b1 * in - a1 * out + z2;
            z2 = b2 * in - a2 * out;
            return (float) out;
        }

        /**
         * Filters all samples in {@code samples} in place.
         *
         * @param samples sample array modified in place (must not be {@code null})
         */
        public void processBuffer(float[] samples) {
            Objects.requireNonNull(samples, "samples must not be null");
            processBuffer(samples, 0, samples.length);
        }

        /**
         * Filters a contiguous slice of {@code samples} in place.
         *
         * @param samples sample array modified in place (must not be {@code null})
         * @param offset  starting index ({@code >= 0})
         * @param length  number of samples to process ({@code >= 0})
         */
        public void processBuffer(float[] samples, int offset, int length) {
            validateRange(samples, offset, length);
            for (int i = 0; i < length; i++) {
                samples[offset + i] = processSample(samples[offset + i]);
            }
        }

        /**
         * Clears the internal filter delay registers so subsequent audio begins cleanly from silence.
         */
        public void reset() {
            this.z1 = 0.0;
            this.z2 = 0.0;
        }

        /**
         * Returns an independent copy of this filter with identical coefficients and zeroed delay state.
         *
         * @return fresh {@link BiquadFilter} copy
         */
        public BiquadFilter copy() {
            return new BiquadFilter(type, sampleRate, cutoffHz, q, b0, b1, b2, a1, a2);
        }

        /**
         * Returns the filter type.
         *
         * @return filter type ({@link FilterType#LOWPASS}, {@link FilterType#HIGHPASS},
         *         or {@link FilterType#BANDPASS})
         */
        public FilterType getType() {
            return type;
        }

        /**
         * Returns the configured sample rate.
         *
         * @return configured sample rate in Hz
         */
        public float getSampleRate() {
            return sampleRate;
        }

        /**
         * Returns the configured cutoff or center frequency.
         *
         * @return configured cutoff/center frequency in Hz
         */
        public float getCutoffHz() {
            return cutoffHz;
        }

        /**
         * Returns the configured quality factor.
         *
         * @return configured quality factor {@code Q}
         */
        public float getQ() {
            return q;
        }
    }

    /**
     * Computes the Root Mean Square (RMS) amplitude of the specified sample slice.
     *
     * @param samples audio sample buffer (must not be {@code null})
     * @param offset  start index ({@code >= 0})
     * @param length  sample count ({@code >= 0})
     * @return RMS magnitude in {@code [0, +inf)}, or {@code 0.0f} if {@code length == 0}
     */
    public static float computeRms(float[] samples, int offset, int length) {
        validateRange(samples, offset, length);
        if (length == 0) {
            return 0.0f;
        }
        double sumSquares = 0.0;
        for (int i = 0; i < length; i++) {
            float sample = samples[offset + i];
            double s = Float.isFinite(sample) ? sample : 0.0;
            sumSquares += s * s;
        }
        return (float) Math.sqrt(sumSquares / length);
    }

    /**
     * Computes the Root Mean Square (RMS) amplitude over an entire sample array.
     *
     * @param samples audio sample buffer (must not be {@code null})
     * @return RMS magnitude
     */
    public static float computeRms(float[] samples) {
        Objects.requireNonNull(samples, "samples must not be null");
        return computeRms(samples, 0, samples.length);
    }

    /**
     * Computes normalized logarithmic frequency band magnitudes in {@code [0, 1]} using a Hann
     * window and radix-2 DFT spectrum at {@link #DEFAULT_SAMPLE_RATE} (44,100 Hz).
     *
     * @param samples   mono audio sample buffer (must not be {@code null})
     * @param offset    start index ({@code >= 0})
     * @param length    number of samples to analyze ({@code >= 0})
     * @param bandCount number of logarithmic frequency bands ({@code > 0})
     * @return array of length {@code bandCount} with normalized amplitudes in {@code [0, 1]}
     */
    public static float[] computeSpectrumBands(float[] samples, int offset, int length, int bandCount) {
        return computeSpectrumBands(samples, offset, length, bandCount, DEFAULT_SAMPLE_RATE);
    }

    /**
     * Computes normalized logarithmic frequency band magnitudes in {@code [0, 1]} using a Hann
     * window and radix-2 DFT spectrum for an arbitrary sample rate.
     *
     * @param samples    mono audio sample buffer (must not be {@code null})
     * @param offset     start index ({@code >= 0})
     * @param length     number of samples to analyze ({@code >= 0})
     * @param bandCount  number of logarithmic frequency bands ({@code > 0})
     * @param sampleRate audio sample rate in Hz ({@code > 0})
     * @return array of length {@code bandCount} with normalized amplitudes in {@code [0, 1]}
     */
    public static float[] computeSpectrumBands(
            float[] samples,
            int offset,
            int length,
            int bandCount,
            float sampleRate
    ) {
        validateRange(samples, offset, length);
        if (bandCount <= 0) {
            throw new IllegalArgumentException("bandCount must be > 0, got: " + bandCount);
        }
        if (!Float.isFinite(sampleRate) || sampleRate <= 0.0f) {
            throw new IllegalArgumentException("sampleRate must be finite and > 0, got: " + sampleRate);
        }

        float[] bands = new float[bandCount];
        if (length == 0) {
            return bands;
        }

        int fftSize = Math.max(MIN_FFT_SIZE, nextPowerOfTwo(length));
        double[] real = new double[fftSize];
        double[] imag = new double[fftSize];

        double windowSum = 0.0;
        for (int n = 0; n < length; n++) {
            double w = (length == 1)
                    ? 1.0
                    : 0.5 * (1.0 - Math.cos((2.0 * Math.PI * n) / (length - 1)));
            float raw = samples[offset + n];
            double s = Float.isFinite(raw) ? raw : 0.0;
            real[n] = s * w;
            windowSum += w;
        }

        if (windowSum <= 0.0) {
            return bands;
        }

        fftInPlace(real, imag);

        int halfSpectrum = fftSize / 2;
        double[] binMags = new double[halfSpectrum];
        for (int k = 1; k < halfSpectrum; k++) {
            binMags[k] = Math.hypot(real[k], imag[k]);
        }

        double maxFreq = Math.min(MAX_SPECTRUM_FREQ_HZ, sampleRate * 0.49);
        double minFreq = Math.min(MIN_SPECTRUM_FREQ_HZ, maxFreq * 0.1);
        if (minFreq <= 0.0) {
            minFreq = 1.0;
        }
        double freqRatio = maxFreq / minFreq;

        for (int b = 0; b < bandCount; b++) {
            double fLow = minFreq * Math.pow(freqRatio, (double) b / bandCount);
            double fHigh = minFreq * Math.pow(freqRatio, (double) (b + 1) / bandCount);

            int binLow = (int) Math.ceil((fLow * fftSize) / sampleRate);
            int binHigh = (b == bandCount - 1)
                    ? (int) Math.floor((fHigh * fftSize) / sampleRate)
                    : (int) Math.ceil((fHigh * fftSize) / sampleRate) - 1;

            binLow = Math.clamp(binLow, 1, halfSpectrum - 1);
            binHigh = Math.clamp(binHigh, 1, halfSpectrum - 1);

            double peakMag = 0.0;
            if (binHigh < binLow) {
                double fCenter = Math.sqrt(fLow * fHigh);
                int nearestBin = Math.clamp((int) Math.round((fCenter * fftSize) / sampleRate), 1, halfSpectrum - 1);
                peakMag = binMags[nearestBin];
            } else {
                for (int k = binLow; k <= binHigh; k++) {
                    if (binMags[k] > peakMag) {
                        peakMag = binMags[k];
                    }
                }
            }

            double normalized = (2.0 * peakMag) / windowSum;
            bands[b] = (float) Math.clamp(normalized, 0.0, 1.0);
        }

        return bands;
    }

    /**
     * Computes the single-frequency Hann-windowed Goertzel normalized amplitude in {@code [0, 1]}
     * for {@code targetFreqHz}.
     *
     * @param samples      mono audio sample buffer (must not be {@code null})
     * @param offset       start index ({@code >= 0})
     * @param length       sample count ({@code >= 0})
     * @param targetFreqHz target frequency in Hz ({@code > 0})
     * @param sampleRate   sample rate in Hz ({@code > 0})
     * @return normalized amplitude in {@code [0, 1]}
     */
    public static float computeGoertzelMagnitude(
            float[] samples,
            int offset,
            int length,
            float targetFreqHz,
            float sampleRate
    ) {
        validateRange(samples, offset, length);
        if (!Float.isFinite(targetFreqHz) || targetFreqHz <= 0.0f) {
            throw new IllegalArgumentException("targetFreqHz must be finite and > 0, got: " + targetFreqHz);
        }
        if (!Float.isFinite(sampleRate) || sampleRate <= 0.0f) {
            throw new IllegalArgumentException("sampleRate must be finite and > 0, got: " + sampleRate);
        }
        if (length == 0) {
            return 0.0f;
        }

        double omega = (2.0 * Math.PI * targetFreqHz) / sampleRate;
        double cosOmega = Math.cos(omega);
        double sinOmega = Math.sin(omega);
        double coeff = 2.0 * cosOmega;

        double sPrev = 0.0;
        double sPrev2 = 0.0;
        double windowSum = 0.0;

        for (int n = 0; n < length; n++) {
            double w = (length == 1)
                    ? 1.0
                    : 0.5 * (1.0 - Math.cos((2.0 * Math.PI * n) / (length - 1)));
            float raw = samples[offset + n];
            double x = (Float.isFinite(raw) ? raw : 0.0) * w;
            double s = x + coeff * sPrev - sPrev2;
            sPrev2 = sPrev;
            sPrev = s;
            windowSum += w;
        }

        if (windowSum <= 0.0) {
            return 0.0f;
        }
        double real = sPrev - sPrev2 * cosOmega;
        double imag = sPrev2 * sinOmega;
        double mag = Math.hypot(real, imag);
        return (float) Math.clamp((2.0 * mag) / windowSum, 0.0, 1.0);
    }

    private static void fftInPlace(double[] real, double[] imag) {
        int n = real.length;
        int j = 0;
        for (int i = 1; i < n; i++) {
            int bit = n >> 1;
            while ((j & bit) != 0) {
                j ^= bit;
                bit >>= 1;
            }
            j ^= bit;
            if (i < j) {
                double tmpR = real[i];
                real[i] = real[j];
                real[j] = tmpR;

                double tmpI = imag[i];
                imag[i] = imag[j];
                imag[j] = tmpI;
            }
        }

        for (int len = 2; len <= n; len <<= 1) {
            double angle = (-2.0 * Math.PI) / len;
            double wLenR = Math.cos(angle);
            double wLenI = Math.sin(angle);
            int halfLen = len >> 1;

            for (int i = 0; i < n; i += len) {
                double wR = 1.0;
                double wI = 0.0;
                for (int k = 0; k < halfLen; k++) {
                    int evenIdx = i + k;
                    int oddIdx = evenIdx + halfLen;

                    double oddR = real[oddIdx] * wR - imag[oddIdx] * wI;
                    double oddI = real[oddIdx] * wI + imag[oddIdx] * wR;

                    real[oddIdx] = real[evenIdx] - oddR;
                    imag[oddIdx] = imag[evenIdx] - oddI;
                    real[evenIdx] += oddR;
                    imag[evenIdx] += oddI;

                    double nextWR = wR * wLenR - wI * wLenI;
                    wI = wR * wLenI + wI * wLenR;
                    wR = nextWR;
                }
            }
        }
    }

    private static int nextPowerOfTwo(int value) {
        int p = Integer.highestOneBit(Math.max(1, value));
        return (p < value) ? (p << 1) : p;
    }

    private static void validateRange(float[] samples, int offset, int length) {
        Objects.requireNonNull(samples, "samples must not be null");
        if (offset < 0 || length < 0 || (long) offset + length > samples.length) {
            throw new IllegalArgumentException(
                    "Invalid range offset=" + offset + ", length=" + length + " for array length=" + samples.length
            );
        }
    }

    /**
     * Configurable equalizer presets for listening comfort and vocal intelligibility ({@code #0468}).
     */
    public enum EqPreset {
        /** Unmodified neutral pass-through. */
        FLAT,
        /** Night listening curve attenuating sub-bass rumble and gently lifting 1-4 kHz presence. */
        NIGHT_MODE,
        /** Crisp speech presence emphasizing 1-4 kHz voice band. */
        VOICE_CLARITY
    }

    /**
     * Applies a smooth {@code tanh}-based soft limiter clamping output peaks strictly within
     * {@code [-1.0, +1.0]} to prevent clipping distortion across stacked sounds ({@code #0445}).
     *
     * @param samples mutable sample buffer modified in place
     */
    public static void applySoftLimiter(float[] samples) {
        Objects.requireNonNull(samples, "samples must not be null");
        for (int i = 0; i < samples.length; i++) {
            float x = samples[i];
            if (!Float.isFinite(x)) {
                samples[i] = 0.0f;
            } else if (Math.abs(x) > 0.85f) {
                samples[i] = (float) Math.clamp(Math.tanh(x * 0.9), -1.0, 1.0);
            }
        }
    }

    /**
     * Applies a scene equalizer preset in place using stateful IIR biquad filters ({@code #0468}).
     *
     * @param samples    mutable mono float sample buffer
     * @param sampleRate sample rate in Hz
     * @param preset     equalizer preset
     */
    public static void applyEqPreset(float[] samples, float sampleRate, EqPreset preset) {
        Objects.requireNonNull(samples, "samples must not be null");
        Objects.requireNonNull(preset, "preset must not be null");
        if (preset == EqPreset.FLAT || samples.length == 0) {
            return;
        }
        float hpfCutoff = preset == EqPreset.NIGHT_MODE ? 140.0f : 90.0f;
        BiquadFilter hpf = BiquadFilter.createHighpass(sampleRate, hpfCutoff, 0.7f);
        BiquadFilter presenceBp = BiquadFilter.createBandpass(sampleRate, 2400.0f, 1.2f);
        float boost = preset == EqPreset.VOICE_CLARITY ? 0.45f : 0.25f;
        for (int i = 0; i < samples.length; i++) {
            float hp = hpf.processSample(samples[i]);
            float pr = presenceBp.processSample(hp);
            samples[i] = Math.clamp(hp + pr * boost, -1.0f, 1.0f);
        }
    }

    /**
     * Creates a band-limiting telephone/radio filter tuned for {@code 300-3400 Hz} vocal character ({@code #0493}).
     *
     * @param sampleRate stream sample rate in Hz
     * @return configured bandpass {@link BiquadFilter}
     */
    public static BiquadFilter createRadioTelephoneFilter(float sampleRate) {
        return BiquadFilter.createBandpass(sampleRate, 1350.0f, 1.1f);
    }

    /**
     * Applies the radio/telephone {@code 300-3400 Hz} bandpass effect in place ({@code #0493}).
     *
     * @param samples    mutable float sample buffer
     * @param sampleRate sample rate in Hz
     */
    public static void applyRadioTelephoneEffect(float[] samples, float sampleRate) {
        Objects.requireNonNull(samples, "samples must not be null");
        BiquadFilter hpf = BiquadFilter.createHighpass(sampleRate, 300.0f, 0.707f);
        BiquadFilter lpf = BiquadFilter.createLowpass(sampleRate, 3400.0f, 0.707f);
        hpf.processBuffer(samples);
        lpf.processBuffer(samples);
    }

    /**
     * Applies a micro fade-out over the trailing {@code fadeMs} milliseconds (minimum 15 ms if long enough)
     * to avoid audible DC-step clicks on sudden stop ({@code #0500}).
     *
     * @param samples    mutable float sample buffer
     * @param sampleRate sample rate in Hz
     * @param fadeMs     fade duration in milliseconds (e.g. {@code 15})
     */
    public static void applyMicroFadeOut(float[] samples, int sampleRate, int fadeMs) {
        Objects.requireNonNull(samples, "samples must not be null");
        if (samples.length == 0 || fadeMs <= 0 || sampleRate <= 0) {
            return;
        }
        int fadeSamples = Math.min(samples.length, Math.max(1, (sampleRate * fadeMs) / 1000));
        int start = samples.length - fadeSamples;
        for (int i = 0; i < fadeSamples; i++) {
            float gain = fadeSamples > 1 ? 1.0f - ((float) i / (fadeSamples - 1)) : 0.0f;
            samples[start + i] *= gain;
        }
    }

    /**
     * Estimates integrated LUFS loudness of an audio buffer relative to full scale ({@code #0473}).
     *
     * @param audio decoded audio buffer
     * @return loudness in LUFS (typically negative, or {@code -70.0} for silence)
     */
    public static double measureIntegratedLufs(AudioDecoder.DecodedAudio audio) {
        Objects.requireNonNull(audio, "audio must not be null");
        float rms = computeRms(audio.toMonoFloat());
        if (rms <= 1e-6f) {
            return -70.0;
        }
        return -0.691 + 20.0 * Math.log10(rms);
    }

    /**
     * Normalizes {@code source} to {@code targetLufs} (e.g., {@code -16.0 LUFS}) using two-pass RMS
     * measurement plus peak soft limiting ({@code #0473}).
     *
     * @param source     source decoded audio
     * @param targetLufs desired integrated loudness in LUFS (e.g. {@code -16.0})
     * @return normalized {@link AudioDecoder.DecodedAudio}
     */
    public static AudioDecoder.DecodedAudio normalizeToTargetLufs(
            AudioDecoder.DecodedAudio source,
            double targetLufs
    ) {
        Objects.requireNonNull(source, "source must not be null");
        double currentLufs = measureIntegratedLufs(source);
        if (currentLufs <= -69.5) {
            return source;
        }
        double gainDb = Math.clamp(targetLufs - currentLufs, -24.0, 24.0);
        float gainLinear = (float) Math.pow(10.0, gainDb / 20.0);
        float[] src = source.monoOrInterleavedFloat();
        float[] scaled = new float[src.length];
        for (int i = 0; i < src.length; i++) {
            scaled[i] = src[i] * gainLinear;
        }
        applySoftLimiter(scaled);
        return new AudioDecoder.DecodedAudio(
                scaled, AudioDecoder.floatToPcm16(scaled),
                source.channels(), source.sampleRate(), source.durationSeconds()
        );
    }
}
