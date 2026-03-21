package com.botwithus.bot.api.antiban;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Models session-level fatigue, warmup, and rhythmic drift.
 * <p>
 * The fatigue curve follows ultradian ~90-minute cycles observed in human
 * performance research: focus gradually wanes, hits a trough around 90 minutes,
 * partially recovers, then declines again. Each session generates a uniquely
 * jittered curve so no two sessions follow identical progression.
 * <p>
 * A sinusoidal wave overlay adds micro-variation within each cycle segment,
 * and ±3% Gaussian noise prevents identical consecutive values.
 * <p>
 * This class implements {@link DelayEngine.FatigueSupplier} so it can be
 * wired directly into the delay engine.
 */
public final class Rhythm implements DelayEngine.FatigueSupplier {

    /**
     * Canonical piecewise-linear fatigue curve: [minutes, multiplier] control points.
     * Each session gets a jittered copy via {@link #rollSessionCurve()}.
     */
    private static final double[][] FATIGUE_CURVE = {
            {   0, 1.00 },   // Fresh start
            {  30, 1.08 },   // Slight warmup wearing off
            {  60, 1.18 },   // Moderate fatigue
            {  90, 1.35 },   // First ultradian trough
            { 120, 1.15 },   // Partial recovery
            { 150, 1.25 },   // Second decline
            { 180, 1.45 },   // Second trough
            { 210, 1.25 },   // Second recovery
            { 240, 1.55 },   // Extended session fatigue
    };

    /** Maximum fatigue multiplier. */
    private static final double MAX_FATIGUE = 1.80;

    /** Session phases based on elapsed time. */
    private static final String PHASE_WARMUP     = "warmup";
    private static final String PHASE_ACTIVE     = "active";
    private static final String PHASE_FATIGUED   = "fatigued";
    private static final String PHASE_RECOVERING = "recovering";

    private volatile long sessionStartMs;
    private volatile double[][] sessionCurve;

    // Wave overlay parameters — randomized per session
    private final double waveFrequency;
    private final double wavePhase;

    public Rhythm() {
        this.sessionStartMs = System.currentTimeMillis();
        this.sessionCurve = rollSessionCurve();

        ThreadLocalRandom rng = ThreadLocalRandom.current();
        this.waveFrequency = 2.5 + rng.nextDouble() * 3.0;  // 2.5–5.5 half-cycles
        this.wavePhase = rng.nextDouble() * Math.PI * 2.0;   // random phase offset
    }

    // =========================================================
    //  FatigueSupplier implementation
    // =========================================================

    /**
     * Returns the current fatigue multiplier based on elapsed session time.
     * Includes wave overlay and ±3% Gaussian noise.
     *
     * @return fatigue multiplier >= 1.0 (1.0 = fresh, higher = more fatigued)
     */
    @Override
    public double getFatigue() {
        double minutesElapsed = sessionMinutes();
        double base = interpolate(minutesElapsed);

        // Wave overlay: small sinusoidal drift ±3%
        double wave = Math.sin(waveFrequency * minutesElapsed / 60.0 * Math.PI + wavePhase) * 0.03;

        // ±3% Gaussian noise
        double noise = 1.0 + ThreadLocalRandom.current().nextGaussian() * 0.03;

        return Math.max(1.0, (base + wave) * noise);
    }

    // =========================================================
    //  Session state queries
    // =========================================================

    /** Returns elapsed session time in minutes. */
    public double sessionMinutes() {
        return (System.currentTimeMillis() - sessionStartMs) / 60_000.0;
    }

    /**
     * Returns the current session phase based on elapsed time.
     * Uses the fatigue curve shape to determine phase transitions.
     */
    public String phase() {
        double minutes = sessionMinutes();
        double fatigue = interpolate(minutes);

        if (minutes < 15) return PHASE_WARMUP;
        if (fatigue < 1.12) return PHASE_ACTIVE;
        if (fatigue > 1.30) return PHASE_FATIGUED;
        // Between 1.12 and 1.30 — check if rising or falling
        double prevFatigue = interpolate(Math.max(0, minutes - 5));
        return fatigue < prevFatigue ? PHASE_RECOVERING : PHASE_ACTIVE;
    }

    // =========================================================
    //  Break recovery
    // =========================================================

    /**
     * Partially resets fatigue by rolling the session start forward.
     * A recoveryFraction of 0.3 recovers 30%: if 100 minutes have elapsed,
     * fatigue is recalculated as if 70 minutes have elapsed.
     *
     * @param recoveryFraction fraction of elapsed time to recover (0.0–1.0)
     */
    public void partialReset(double recoveryFraction) {
        recoveryFraction = Math.max(0.0, Math.min(1.0, recoveryFraction));
        long now = System.currentTimeMillis();
        long elapsed = now - sessionStartMs;
        sessionStartMs += (long) (elapsed * recoveryFraction);
    }

    /**
     * Fully resets fatigue as if the player just started fresh.
     * Re-rolls the session curve so the new session has different progression.
     */
    public void reset() {
        this.sessionStartMs = System.currentTimeMillis();
        this.sessionCurve = rollSessionCurve();
    }

    // =========================================================
    //  Session curve generation
    // =========================================================

    /**
     * Generates a per-session jittered copy of the fatigue curve.
     * Each control point's multiplier gets ±10% random variation.
     * The fresh-start point (t=0) is always fixed at 1.0.
     */
    private static double[][] rollSessionCurve() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double[][] curve = new double[FATIGUE_CURVE.length][2];
        for (int i = 0; i < FATIGUE_CURVE.length; i++) {
            curve[i][0] = FATIGUE_CURVE[i][0];
            if (i == 0) {
                curve[i][1] = 1.0;
            } else {
                double jitter = 1.0 + rng.nextDouble(-0.10, 0.10);
                curve[i][1] = FATIGUE_CURVE[i][1] * jitter;
            }
        }
        return curve;
    }

    // =========================================================
    //  Interpolation
    // =========================================================

    private double interpolate(double minutes) {
        if (minutes <= 0) return 1.0;

        double[][] curve = this.sessionCurve;
        for (int i = 0; i < curve.length - 1; i++) {
            double t0 = curve[i][0];
            double v0 = curve[i][1];
            double t1 = curve[i + 1][0];
            double v1 = curve[i + 1][1];

            if (minutes >= t0 && minutes <= t1) {
                double frac = (minutes - t0) / (t1 - t0);
                return v0 + frac * (v1 - v0);
            }
        }

        // Beyond last control point: slow linear growth, capped
        double lastMin = curve[curve.length - 1][0];
        double lastVal = curve[curve.length - 1][1];
        double extra = minutes - lastMin;
        return Math.min(lastVal + extra * (0.02 / 30.0), MAX_FATIGUE);
    }
}
