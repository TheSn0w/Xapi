package com.botwithus.bot.api.antiban;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.BooleanSupplier;

/**
 * Human-like break injection driven by an attention state machine.
 * <p>
 * Real players oscillate between focused engagement and distraction.
 * Rather than rolling fixed-probability breaks at uniform intervals,
 * this system models three attention states:
 * <ul>
 *   <li><b>Focused</b>  — player is actively engaged, few/no breaks</li>
 *   <li><b>Drifting</b> — attention is waning, micro-AFKs and short pauses</li>
 *   <li><b>Distracted</b> — player is on phone/alt-tabbed, longer gaps</li>
 * </ul>
 * Transitions are stochastic with time-varying rates (more distraction as
 * session progresses). Breaks are clustered/bursty — a player who just
 * checked their phone might also grab a drink and adjust music.
 * <p>
 * Based on: Warm et al. (2008) vigilance decrement, Barabasi (2005) bursty
 * human dynamics, Unsworth & Robison (2016) mind-wandering frequency.
 */
public final class Breaks {

    private static final Logger log = LoggerFactory.getLogger(Breaks.class);

    // ── Attention states ──────────────────────────────────

    public enum AttentionState {
        /** Actively engaged. Fast reactions, rare breaks. */
        FOCUSED("Focused"),
        /** Attention waning. Micro-AFKs, slightly slower. */
        DRIFTING("Drifting"),
        /** On phone / alt-tabbed / zoned out. Long gaps. */
        DISTRACTED("Distracted");

        private final String label;
        AttentionState(String label) { this.label = label; }
        public String label() { return label; }
    }

    // ── Transition probabilities per check ─────────────────
    // These are BASE rates at fatigue=1.0, scaled by fatigue.

    // From FOCUSED:
    private static final double FOCUSED_TO_DRIFTING    = 0.08;  // ~8% per check → drifts every ~2-4 min
    private static final double FOCUSED_TO_DISTRACTED  = 0.01;  // ~1% per check → rare direct jump

    // From DRIFTING:
    private static final double DRIFTING_TO_FOCUSED    = 0.12;  // can snap back
    private static final double DRIFTING_TO_DISTRACTED = 0.15;  // ~15% per check → often leads to distraction

    // From DISTRACTED:
    private static final double DISTRACTED_TO_FOCUSED  = 0.06;  // re-engagement burst
    private static final double DISTRACTED_TO_DRIFTING  = 0.20;  // gradually coming back

    // ── Break durations per state ─────────────────────────

    // DRIFTING: micro-AFKs and brief pauses (mind-wandering)
    private static final double DRIFT_BREAK_CHANCE = 0.35;  // 35% chance of a pause when drifting
    private static final double DRIFT_MU     = 3_000;    // 3s typical
    private static final double DRIFT_SIGMA  = 1_000;
    private static final double DRIFT_TAU    = 2_000;    // right tail to ~8s
    private static final long   DRIFT_MIN_MS = 1_500;
    private static final long   DRIFT_MAX_MS = 12_000;

    // DISTRACTED: phone check, alt-tab, extended zone-out
    private static final double DISTRACT_BREAK_CHANCE = 0.70;  // 70% chance of a pause when distracted
    private static final double DISTRACT_MU     = 15_000;   // 15s typical
    private static final double DISTRACT_SIGMA  = 5_000;
    private static final double DISTRACT_TAU    = 20_000;   // heavy right tail for phone scrolling
    private static final long   DISTRACT_MIN_MS = 5_000;
    private static final long   DISTRACT_MAX_MS = 180_000;  // up to 3 min

    // Cluster bonus: if we just took a break, higher chance of another
    private static final double CLUSTER_BONUS = 0.20;

    // Long break (get up, bathroom, food) — checked separately, time-driven
    private static final double LONG_BREAK_BASE_CHANCE = 0.003;  // per check
    private static final double LONG_MU     = 180_000;   // 3 min typical
    private static final double LONG_SIGMA  = 60_000;
    private static final double LONG_TAU    = 120_000;   // heavy tail up to 8+ min
    private static final long   LONG_MIN_MS = 60_000;    // at least 1 min
    private static final long   LONG_MAX_MS = 600_000;   // up to 10 min

    // ── Cooldown ──────────────────────────────────────────

    /** Minimum time between break checks. */
    private static final long COOLDOWN_MIN_MS = 12_000;
    private static final long COOLDOWN_MAX_MS = 25_000;

    // ── Components ────────────────────────────────────────

    private final PaceSeed seed;
    private final Rhythm rhythm;

    // ── State ─────────────────────────────────────────────

    private volatile AttentionState attention = AttentionState.FOCUSED;
    private volatile long lastCheckMs = 0;
    private volatile long currentCooldownMs;
    private volatile boolean justBroke = false; // for break clustering
    private volatile long minutesSinceLastLongBreak = 0;
    private volatile long lastLongBreakMs;

    // Break overlay state — readable from UI thread
    private volatile String breakLabel;
    private volatile long breakStartMs;
    private volatile long breakEndMs;

    public Breaks(PaceSeed seed, Rhythm rhythm) {
        this.seed = seed;
        this.rhythm = rhythm;
        this.currentCooldownMs = rollCooldown();
        this.lastLongBreakMs = System.currentTimeMillis();
    }

    // ── Public API ────────────────────────────────────────

    /**
     * Rolls for attention state transitions and possible breaks.
     * Call at natural pause points in script logic.
     */
    public void check() {
        check(null);
    }

    /**
     * Rolls for attention state transitions and possible breaks.
     *
     * @param interrupt if non-null, polled every 2s during long breaks
     */
    public void check(BooleanSupplier interrupt) {
        long now = System.currentTimeMillis();
        if (now - lastCheckMs < currentCooldownMs) return;
        lastCheckMs = now;
        currentCooldownMs = rollCooldown();

        double fatigue = Math.min(rhythm.getFatigue(), 1.6);
        double probScale = seed.breakProbScale();

        // 1. Attention state transition
        transitionAttention(fatigue, probScale);

        // 2. State-driven break roll
        boolean tookBreak = rollStateBreak(fatigue, probScale, interrupt);

        // 3. Long break check (independent of attention state, time-driven)
        if (!tookBreak) {
            double minutesSinceLong = (now - lastLongBreakMs) / 60_000.0;
            // Chance increases the longer since last long break
            double longChance = LONG_BREAK_BASE_CHANCE * fatigue * probScale * (1.0 + minutesSinceLong / 30.0);
            if (ThreadLocalRandom.current().nextDouble() < longChance) {
                takeLongBreak(interrupt);
            }
        }

        justBroke = tookBreak;
    }

    /** Returns the current attention state. */
    public AttentionState getAttentionState() {
        return attention;
    }

    /** Returns true if a break is currently in progress. */
    public boolean isOnBreak() {
        return breakLabel != null && getBreakRemainingMs() > 0;
    }

    /** Returns the current break label, or null if no break. */
    public String getBreakLabel() { return breakLabel; }

    /** Returns remaining break time in milliseconds, or 0. */
    public long getBreakRemainingMs() {
        long end = breakEndMs;
        if (end == 0) return 0;
        return Math.max(0, end - System.currentTimeMillis());
    }

    /** Returns total break duration in milliseconds, or 0. */
    public long getBreakTotalMs() {
        long start = breakStartMs;
        long end = breakEndMs;
        if (start == 0 || end == 0) return 0;
        return end - start;
    }

    // ── Attention transitions ─────────────────────────────

    private void transitionAttention(double fatigue, double probScale) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();
        double roll = rng.nextDouble();

        // Fatigue increases chance of drifting/distraction
        double fatigueScale = 1.0 + (fatigue - 1.0) * 1.5;  // amplify fatigue effect

        AttentionState prev = attention;
        switch (attention) {
            case FOCUSED -> {
                double toDrift = FOCUSED_TO_DRIFTING * fatigueScale * probScale;
                double toDistract = FOCUSED_TO_DISTRACTED * fatigueScale * probScale;
                if (roll < toDistract) {
                    attention = AttentionState.DISTRACTED;
                } else if (roll < toDistract + toDrift) {
                    attention = AttentionState.DRIFTING;
                }
            }
            case DRIFTING -> {
                double toFocused = DRIFTING_TO_FOCUSED / fatigueScale; // harder to refocus when tired
                double toDistract = DRIFTING_TO_DISTRACTED * fatigueScale * probScale;
                if (roll < toFocused) {
                    attention = AttentionState.FOCUSED;
                } else if (roll < toFocused + toDistract) {
                    attention = AttentionState.DISTRACTED;
                }
            }
            case DISTRACTED -> {
                double toFocused = DISTRACTED_TO_FOCUSED / fatigueScale;
                double toDrift = DISTRACTED_TO_DRIFTING * probScale;
                if (roll < toFocused) {
                    attention = AttentionState.FOCUSED;
                } else if (roll < toFocused + toDrift) {
                    attention = AttentionState.DRIFTING;
                }
            }
        }

        if (attention != prev) {
            log.debug("[Antiban] Attention: {} -> {} (fatigue={})", prev, attention, String.format("%.2f", fatigue));
        }
    }

    // ── State-driven breaks ───────────────────────────────

    private boolean rollStateBreak(double fatigue, double probScale, BooleanSupplier interrupt) {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Cluster bonus: if we just broke, more likely to break again
        double clusterBoost = justBroke ? CLUSTER_BONUS : 0.0;

        return switch (attention) {
            case FOCUSED -> false; // focused players don't take breaks
            case DRIFTING -> {
                double chance = (DRIFT_BREAK_CHANCE + clusterBoost) * fatigue * probScale;
                if (rng.nextDouble() < chance) {
                    takeBreak("Mind wandering", DRIFT_MU, DRIFT_SIGMA, DRIFT_TAU,
                            DRIFT_MIN_MS, DRIFT_MAX_MS, 0.1, null);
                    yield true;
                }
                yield false;
            }
            case DISTRACTED -> {
                double chance = (DISTRACT_BREAK_CHANCE + clusterBoost) * fatigue * probScale;
                if (rng.nextDouble() < chance) {
                    // Pick a label that feels natural
                    String label = pickDistractedLabel(rng);
                    takeBreak(label, DISTRACT_MU, DISTRACT_SIGMA, DISTRACT_TAU,
                            DISTRACT_MIN_MS, DISTRACT_MAX_MS, 0.3, interrupt);
                    yield true;
                }
                yield false;
            }
        };
    }

    private static String pickDistractedLabel(ThreadLocalRandom rng) {
        return switch (rng.nextInt(6)) {
            case 0 -> "Checking phone";
            case 1 -> "Reading message";
            case 2 -> "Alt-tabbed";
            case 3 -> "Zoned out";
            case 4 -> "Scrolling phone";
            default -> "Looking away";
        };
    }

    private void takeLongBreak(BooleanSupplier interrupt) {
        String label = switch (ThreadLocalRandom.current().nextInt(4)) {
            case 0 -> "Getting a drink";
            case 1 -> "Bathroom break";
            case 2 -> "Stretching";
            default -> "AFK";
        };
        takeBreak(label, LONG_MU, LONG_SIGMA, LONG_TAU,
                LONG_MIN_MS, LONG_MAX_MS, 0.6, interrupt);
        lastLongBreakMs = System.currentTimeMillis();
        // Long breaks snap attention back to focused
        attention = AttentionState.FOCUSED;
    }

    // ── Break execution ───────────────────────────────────

    private void takeBreak(String label, double mu, double sigma, double tau,
                           long minMs, long maxMs, double recoveryFraction,
                           BooleanSupplier interrupt) {
        double raw = DelayEngine.exGaussianSample(mu, sigma, tau);
        long durationMs = raw > 0
                ? Math.max(minMs, Math.min(maxMs, Math.round(raw)))
                : minMs;

        log.debug("[Antiban] {} ({}s, attention={})", label, durationMs / 1000, attention);

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

    private static void sleep(long ms) { Delays.sleep(ms); }

    private long rollCooldown() {
        double scale = seed.breakCooldownScale();
        long min = (long) (COOLDOWN_MIN_MS * scale);
        long max = (long) (COOLDOWN_MAX_MS * scale);
        return ThreadLocalRandom.current().nextLong(min, max + 1);
    }
}
