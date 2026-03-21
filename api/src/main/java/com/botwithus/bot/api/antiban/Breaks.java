package com.botwithus.bot.api.antiban;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * Probabilistic micro-break injection at natural pause points.
 * <p>
 * Real players occasionally glance at their phone, take a sip of water,
 * or go AFK for a few minutes. This class models those pauses with four tiers:
 * <ul>
 *   <li><b>Small</b>    — 15% base chance, 2–5s     ("glanced away")</li>
 *   <li><b>Medium</b>   — 6% base chance,  4–25s    ("replied to a message")</li>
 *   <li><b>Long</b>     — 0.8% base chance, 20–60s  ("got a drink, AFK")</li>
 *   <li><b>Extended</b> — 0.1% base chance, 120–300s ("bathroom/food break")</li>
 * </ul>
 * All probabilities are scaled by the current fatigue multiplier (capped at 1.4x).
 * Break durations within each tier are right-skewed (ex-Gaussian).
 * <p>
 * A cooldown prevents breaks from clustering unnaturally.
 */
public final class Breaks {

    private static final Logger log = LoggerFactory.getLogger(Breaks.class);

    // ── Tier configuration ────────────────────────────────

    private static final double SMALL_CHANCE    = 0.15;
    private static final int    SMALL_MIN_MS    = 2_000;
    private static final int    SMALL_MAX_MS    = 5_000;

    private static final double MEDIUM_CHANCE   = 0.06;
    private static final int    MEDIUM_MIN_MS   = 4_000;
    private static final int    MEDIUM_MAX_MS   = 25_000;

    private static final double LONG_CHANCE     = 0.008;
    private static final int    LONG_MIN_MS     = 20_000;
    private static final int    LONG_MAX_MS     = 60_000;

    private static final double EXTENDED_CHANCE = 0.001;
    private static final int    EXTENDED_MIN_MS = 120_000;
    private static final int    EXTENDED_MAX_MS = 300_000;

    /** Maximum fatigue scaling factor. */
    private static final double MAX_FATIGUE_SCALE = 1.4;

    /** Minimum time between break checks (ms). */
    private static final long COOLDOWN_MIN_MS = 20_000;
    private static final long COOLDOWN_MAX_MS = 40_000;

    private final PaceSeed seed;
    private final Rhythm rhythm;

    // ── State ─────────────────────────────────────────────

    private volatile long lastCheckMs = 0;
    private volatile long currentCooldownMs;

    // Break overlay state — readable from ImGui thread
    private volatile String breakLabel;
    private volatile long breakStartMs;
    private volatile long breakEndMs;

    public Breaks(PaceSeed seed, Rhythm rhythm) {
        this.seed = seed;
        this.rhythm = rhythm;
        this.currentCooldownMs = rollCooldown();
    }

    // ── Public API ────────────────────────────────────────

    /**
     * Rolls for a micro-break. Call this at natural pause points in script logic.
     */
    public void check() {
        check(null);
    }

    /**
     * Rolls for a micro-break with an optional interrupt predicate.
     *
     * @param interrupt if non-null, polled every 2s during long/extended breaks;
     *                  return true to end the break early
     */
    public void check(BooleanSupplier interrupt) {
        long now = System.currentTimeMillis();

        // Enforce cooldown
        if (now - lastCheckMs < currentCooldownMs) return;
        lastCheckMs = now;
        currentCooldownMs = rollCooldown();

        // Fatigue scaling (capped)
        double rawFatigue = rhythm.getFatigue();
        double fatigue = Math.min(rawFatigue, MAX_FATIGUE_SCALE);
        double probScale = seed.breakProbScale();

        double extendedChance = EXTENDED_CHANCE * fatigue * probScale;
        double longChance     = LONG_CHANCE * fatigue * probScale;
        double mediumChance   = MEDIUM_CHANCE * fatigue * probScale;
        double smallChance    = SMALL_CHANCE * fatigue * probScale;

        double roll = ThreadLocalRandom.current().nextDouble();

        // Check tiers from rarest to most common
        if (roll < extendedChance) {
            takeBreak("Extended AFK break", 120_000, 30_000, 60_000,
                    EXTENDED_MIN_MS, EXTENDED_MAX_MS, 1.0, interrupt);
        } else if (roll < extendedChance + longChance) {
            takeBreak("Long AFK break", 20_000, 5_000, 10_000,
                    LONG_MIN_MS, LONG_MAX_MS, 0.6, interrupt);
        } else if (roll < extendedChance + longChance + mediumChance) {
            takeBreak("Medium pause", 8_000, 3_000, 4_000,
                    MEDIUM_MIN_MS, MEDIUM_MAX_MS, 0.3, interrupt);
        } else if (roll < extendedChance + longChance + mediumChance + smallChance) {
            takeBreak("Quick glance away", 2_000, 500, 1_000,
                    SMALL_MIN_MS, SMALL_MAX_MS, 0.3, interrupt);
        }
    }

    /** Returns true if a break is currently in progress. */
    public boolean isOnBreak() {
        return breakLabel != null && getBreakRemainingMs() > 0;
    }

    /** Returns the current break label, or null if no break is active. Safe for ImGui thread. */
    public String getBreakLabel() { return breakLabel; }

    /** Returns remaining break time in milliseconds, or 0 if no break. */
    public long getBreakRemainingMs() {
        long end = breakEndMs;
        if (end == 0) return 0;
        return Math.max(0, end - System.currentTimeMillis());
    }

    /** Returns total break duration in milliseconds, or 0 if no break. */
    public long getBreakTotalMs() {
        long start = breakStartMs;
        long end = breakEndMs;
        if (start == 0 || end == 0) return 0;
        return end - start;
    }

    // ── Internal ──────────────────────────────────────────

    private void takeBreak(String label, double mu, double sigma, double tau,
                           long minMs, long maxMs, double recoveryFraction,
                           BooleanSupplier interrupt) {
        double raw = DelayEngine.exGaussianSample(mu, sigma, tau);
        long durationMs = raw > 0
                ? Math.max(minMs, Math.min(maxMs, Math.round(raw)))
                : minMs;

        log.debug("[Antiban] {} ({}s)", label, durationMs / 1000);

        beginBreak(label, durationMs);
        delayInterruptible(durationMs, interrupt);
        endBreak();

        lastCheckMs = System.currentTimeMillis();
        rhythm.partialReset(recoveryFraction);
    }

    private void beginBreak(String label, long durationMs) {
        long now = System.currentTimeMillis();
        breakStartMs = now;
        breakEndMs = now + durationMs;
        breakLabel = label;
    }

    private void endBreak() {
        breakLabel = null;
        breakStartMs = 0;
        breakEndMs = 0;
    }

    /**
     * Delays for the specified duration, polling every 2s for an interrupt.
     */
    private static void delayInterruptible(long durationMs, BooleanSupplier interrupt) {
        if (interrupt == null) {
            sleep(durationMs);
            return;
        }
        long end = System.currentTimeMillis() + durationMs;
        while (System.currentTimeMillis() < end) {
            if (interrupt.getAsBoolean()) {
                log.debug("[Antiban] Break interrupted early");
                return;
            }
            long remaining = end - System.currentTimeMillis();
            sleep(Math.min(2_000, Math.max(100, remaining)));
        }
    }

    private static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private long rollCooldown() {
        double scale = seed.breakCooldownScale();
        long min = (long) (COOLDOWN_MIN_MS * scale);
        long max = (long) (COOLDOWN_MAX_MS * scale);
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }
}
