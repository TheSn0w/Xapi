package com.botwithus.bot.api.antiban;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Ex-Gaussian delay generator with momentum, fatigue asymmetry,
 * context-switch penalty, and warmup modeling.
 * <p>
 * Each named context maintains its own momentum state so delays in one
 * context don't bleed into another.
 */
public final class DelayEngine {

    /** Minimum output delay in milliseconds. No human reacts in under 400ms to a boring task. */
    public static final long MIN_DELAY_MS = 400;
    /** Maximum output delay in milliseconds (for normal delays, not breaks). */
    public static final long MAX_DELAY_MS = 25_000;

    private final PaceSeed seed;
    private volatile double tempoScale = 1.0;

    // Per-context momentum state
    private final Map<String, MomentumState> momentumStates = new ConcurrentHashMap<>();

    // Context-switch tracking
    private volatile String lastContext;
    private volatile int actionsSinceSwitch;

    // Warmup tracking
    private final long startTimeMs;

    // External fatigue supplier (set by Pace when Rhythm is wired)
    private volatile FatigueSupplier fatigueSupplier;

    /** Functional interface for fatigue value supply. */
    @FunctionalInterface
    public interface FatigueSupplier {
        double getFatigue();
    }

    /** Per-context momentum tracking. */
    private static final class MomentumState {
        volatile double lastSample = -1;
    }

    public DelayEngine(PaceSeed seed) {
        this.seed = seed;
        this.startTimeMs = System.currentTimeMillis();
    }

    /**
     * Sets the fatigue supplier (typically from {@link Rhythm}).
     */
    public void setFatigueSupplier(FatigueSupplier supplier) {
        this.fatigueSupplier = supplier;
    }

    /**
     * Sets the global tempo scale. Values &lt; 1.0 speed up, &gt; 1.0 slow down.
     */
    public void setTempo(double scale) {
        this.tempoScale = Math.max(0.5, Math.min(2.0, scale));
    }

    /**
     * Generates a delay for the given context and profile.
     * Applies: personality scaling → fatigue asymmetry → momentum blending →
     * context-switch penalty → warmup → tempo → clamping.
     *
     * @param context the named context (e.g., "gather", "bank")
     * @param profile the ex-Gaussian parameters for this context
     * @return delay in milliseconds, clamped to [130, 15000]
     */
    public long sample(String context, PaceProfile profile) {
        return sample(context, profile, MIN_DELAY_MS, MAX_DELAY_MS);
    }

    /**
     * Generates a delay with custom output bounds.
     * Use for break durations or other non-standard delay ranges.
     */
    public long sample(String context, PaceProfile profile, long minMs, long maxMs) {
        // 1. Personality scaling
        double scale = seed.scaleFor(context);
        double mu = profile.mu() * scale;
        double sigma = profile.sigma() * scale;
        double tau = profile.tau() * scale;

        // 2. Fatigue asymmetry
        double fatigue = fatigue();
        mu *= (1.0 + (fatigue - 1.0) * seed.fatigueMuEffect());
        sigma *= (1.0 + (fatigue - 1.0) * seed.fatigueSigmaEffect());
        tau *= (1.0 + (fatigue - 1.0) * seed.fatigueTauEffect());

        // 3. Ex-Gaussian sample
        double raw = exGaussianSample(mu, sigma, tau);

        // 4. Momentum blending
        MomentumState state = momentumStates.computeIfAbsent(context, k -> new MomentumState());
        double blended;
        double m = seed.momentum();
        if (state.lastSample < 0) {
            blended = raw;
        } else {
            blended = (1.0 - m) * raw + m * state.lastSample;
        }
        state.lastSample = blended;

        // 5. Context-switch penalty
        blended *= contextSwitchMultiplier(context);

        // 6. Warmup
        blended *= warmupMultiplier();

        // 7. Tempo
        blended *= tempoScale;

        long result = Math.round(blended);
        return Math.max(minMs, Math.min(maxMs, result));
    }

    /**
     * Returns the current fatigue multiplier (1.0 = fresh, higher = fatigued).
     */
    public double fatigue() {
        FatigueSupplier supplier = this.fatigueSupplier;
        return (supplier != null) ? supplier.getFatigue() : 1.0;
    }

    /**
     * Tracks context and returns the context-switch penalty multiplier.
     * First 3 actions after a switch get decaying penalty.
     */
    private double contextSwitchMultiplier(String context) {
        if (!context.equals(lastContext)) {
            lastContext = context;
            actionsSinceSwitch = 0;
        }
        int n = actionsSinceSwitch;
        actionsSinceSwitch = Math.min(n + 1, 10);

        if (n >= 3) return 1.0;

        // Exponential decay: penalty * exp(-n / 1.5)
        double penalty = seed.contextSwitchPenalty();
        return 1.0 + penalty * Math.exp(-n / 1.5);
    }

    /**
     * Returns the warmup multiplier based on session elapsed time.
     * Decays linearly from warmupMultiplier to 1.0 over warmupDuration.
     */
    private double warmupMultiplier() {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        long duration = seed.warmupDurationMs();
        if (elapsed >= duration) return 1.0;

        double fraction = (double) elapsed / duration;
        double maxMult = seed.warmupMultiplier();
        return maxMult - (maxMult - 1.0) * fraction;
    }

    // =========================================================
    //  Ex-Gaussian sampling
    // =========================================================

    /**
     * Generates a single ex-Gaussian sample: Gaussian(mu, sigma) + Exponential(tau).
     * Uses Box-Muller transform for the Gaussian component and inverse CDF for exponential.
     */
    public static double exGaussianSample(double mu, double sigma, double tau) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Gaussian component via Box-Muller transform
        // Clamp u1 to (0, 1] to avoid log(0) → -Infinity
        double u1 = Math.max(Double.MIN_NORMAL, rng.nextDouble());
        double u2 = rng.nextDouble();
        double gaussianSample = mu + sigma * Math.sqrt(-2.0 * Math.log(u1)) * Math.cos(2.0 * Math.PI * u2);

        // Exponential component via inverse CDF
        double expSample = (tau > 0) ? -tau * Math.log(Math.max(Double.MIN_NORMAL, 1.0 - rng.nextDouble())) : 0;

        return gaussianSample + expSample;
    }
}
