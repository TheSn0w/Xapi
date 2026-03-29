package com.botwithus.bot.api.antiban;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Human-like timing API for bot scripts.
 * <p>
 * Replaces hardcoded {@code sleep(600)} calls with context-aware, personality-driven
 * delays that model realistic human pacing. Under the hood, delays are generated using
 * ex-Gaussian distributions (matching human reaction time research), modulated by
 * session fatigue, momentum autocorrelation, context-switch penalties, and warmup.
 *
 * <h3>Quick Start</h3>
 * <pre>{@code
 * // In onStart:
 * Pace pace = ctx.getPace();
 * pace.seed("MyCharacterName");
 *
 * // In onLoop:
 * pace.breakCheck();
 * pace.sleep("gather");          // deliberate action delay
 * pace.sleepAfter("bank");       // transition out of banking
 * return (int) pace.idle("fish"); // passive monitoring
 * }</pre>
 *
 * <h3>Built-in Contexts</h3>
 * <ul>
 *   <li>{@code "react"}  — quick reaction (280ms center, tail to 800ms)</li>
 *   <li>{@code "gather"} — deliberate action (400ms center, tail to 1200ms)</li>
 *   <li>{@code "bank"}   — banking interaction (350ms center, tail to 1000ms)</li>
 *   <li>{@code "walk"}   — walk clicks (800ms center, tail to 2000ms)</li>
 *   <li>{@code "idle"}   — passive monitoring (2000ms center, tail to 8000ms)</li>
 *   <li>{@code "combat"} — combat interaction (350ms center, tail to 1100ms)</li>
 *   <li>{@code "menu"}   — UI interaction (300ms center, tail to 900ms)</li>
 * </ul>
 * Unknown contexts use {@code "gather"} defaults. Register custom contexts via {@link #tune}.
 *
 * @see PaceConfig
 * @see DelayEngine
 * @see Rhythm
 * @see Breaks
 */
public final class Pace {

    // ── Default profiles ──────────────────────────────────
    //
    // Research-based ex-Gaussian profiles for human-like delays.
    // mu = Gaussian mean (attentive response), sigma = Gaussian SD (jitter),
    // tau = exponential tail (attentional lapses — larger = more long delays).
    //
    // A bored player clicking the next tree is a semi-attended repetitive task
    // with median RT ~1200-2500ms and a fat right tail, NOT a competitive
    // reaction time test. Based on: Warm et al. (2008) vigilance meta-analysis,
    // Luce (1986) RT distributions, Unsworth & Robison (2016) mind-wandering.

    private static final Map<String, PaceProfile> DEFAULTS = Map.of(
            "react",  new PaceProfile(900, 120, 400),
            "gather", new PaceProfile(1200, 200, 600),
            "bank",   new PaceProfile(800, 150, 350),
            "walk",   new PaceProfile(1200, 250, 500),
            "idle",   new PaceProfile(3000, 800, 2000),
            "combat", new PaceProfile(600, 100, 250),
            "menu",   new PaceProfile(700, 120, 300)
    );

    private static final PaceProfile FALLBACK = DEFAULTS.get("gather");

    // ── Components ────────────────────────────────────────

    private volatile DelayEngine engine;
    private final Rhythm rhythm;
    private volatile Breaks breaks;
    private volatile PaceSeed seed;

    // Custom profile overrides
    private final Map<String, PaceProfile> overrides = new ConcurrentHashMap<>();

    // Last context for queries
    private volatile String currentContext = "gather";

    // Idle timeout cap for breaks (persists across seed rebuilds)
    private volatile long idleTimeoutMs = 0;

    /**
     * Creates a Pace instance with the given components.
     * Prefer {@link PaceConfig#build()} or {@code ScriptContext.getPace()}.
     */
    Pace(DelayEngine engine, Rhythm rhythm, Breaks breaks, PaceSeed seed) {
        this.engine = engine;
        this.rhythm = rhythm;
        this.breaks = breaks;
        this.seed = seed;
        engine.setFatigueSupplier(rhythm);
    }

    /** Creates a Pace with default configuration. */
    public Pace() {
        this.seed = new PaceSeed();
        this.rhythm = new Rhythm();
        this.engine = new DelayEngine(seed);
        this.engine.setFatigueSupplier(rhythm);
        this.breaks = new Breaks(seed, rhythm);
    }

    // =========================================================
    //  Core delays
    // =========================================================

    /**
     * Returns a delay appropriate for the named context.
     * Applies personality, fatigue, momentum, context-switch, warmup, and tempo.
     *
     * @param context the activity context (e.g., "gather", "bank", "walk")
     * @return delay in milliseconds
     */
    public long delay(String context) {
        currentContext = context;
        return engine.sample(context, profileFor(context));
    }

    /**
     * Sleeps for a context-appropriate delay.
     *
     * @param context the activity context
     */
    public void sleep(String context) {
        doSleep(delay(context));
    }

    /**
     * Returns a transition delay after completing an activity.
     * Uses the "walk" profile scaled slightly for the transition context.
     *
     * @param activity the activity just completed (e.g., "bank", "gather")
     * @return delay in milliseconds
     */
    public long after(String activity) {
        currentContext = activity;
        PaceProfile transition = profileFor("walk");
        return engine.sample(activity + "_transition", transition);
    }

    /**
     * Sleeps for a transition delay after completing an activity.
     */
    public void sleepAfter(String activity) {
        doSleep(after(activity));
    }

    /**
     * Returns a delay for idle/passive monitoring.
     * Uses the "idle" profile, suitable for waiting while fishing, smelting, etc.
     *
     * @param context the idle context
     * @return delay in milliseconds
     */
    public long idle(String context) {
        currentContext = context;
        return engine.sample(context + "_idle", profileFor("idle"));
    }

    /**
     * Sleeps for an idle monitoring delay.
     */
    public void sleepIdle(String context) {
        doSleep(idle(context));
    }

    /**
     * Returns a quick reaction delay.
     * For when something just appeared or changed (spot respawned, inventory full detected).
     *
     * @return delay in milliseconds
     */
    public long react() {
        return engine.sample("react", profileFor("react"));
    }

    /**
     * Sleeps for a quick reaction delay.
     */
    public void sleepReact() {
        doSleep(react());
    }

    // =========================================================
    //  Breaks
    // =========================================================

    /**
     * Rolls for a micro-break. Call at natural pause points in script logic.
     * Does nothing most of the time; occasionally pauses for 2s–5min.
     */
    public void breakCheck() {
        breaks.check();
    }

    /**
     * Rolls for a micro-break with an interrupt predicate.
     *
     * @param interrupt return true to end the break early
     */
    public void breakCheck(BooleanSupplier interrupt) {
        breaks.check(interrupt);
    }

    /** Returns true if a break is currently in progress. */
    public boolean onBreak() {
        return breaks.isOnBreak();
    }

    /** Returns the current break label, or null. Safe for ImGui thread. */
    public String breakLabel() {
        return breaks.getBreakLabel();
    }

    /** Returns remaining break time in milliseconds, or 0. */
    public long breakRemainingMs() {
        return breaks.getBreakRemainingMs();
    }

    /** Returns the current attention state (Focused / Drifting / Distracted). */
    public Breaks.AttentionState attentionState() {
        return breaks.getAttentionState();
    }

    // =========================================================
    //  State & mood
    // =========================================================

    /** Returns the current fatigue multiplier (1.0 = fresh). */
    public double fatigue() {
        return rhythm.getFatigue();
    }

    /** Returns elapsed session time in minutes. */
    public double sessionMinutes() {
        return rhythm.sessionMinutes();
    }

    /** Returns the current session phase: "warmup", "active", "fatigued", "recovering". */
    public String phase() {
        return rhythm.phase();
    }

    /** Returns the most recent context passed to delay/sleep. */
    public String currentContext() {
        return currentContext;
    }

    // =========================================================
    //  Configuration
    // =========================================================

    /**
     * Sets the personality seed. Call once in {@code onStart} with the character name.
     * Rebuilds internal components with the new seed.
     *
     * @param identity the character name or other unique identity
     * @return this Pace instance for chaining
     */
    public Pace seed(String identity) {
        PaceSeed newSeed = new PaceSeed(identity);
        DelayEngine newEngine = new DelayEngine(newSeed);
        newEngine.setFatigueSupplier(rhythm);
        this.seed = newSeed;
        this.engine = newEngine;
        Breaks newBreaks = new Breaks(newSeed, rhythm);
        if (idleTimeoutMs > 0) {
            newBreaks.setIdleTimeout(idleTimeoutMs);
        }
        this.breaks = newBreaks;
        return this;
    }

    /**
     * Overrides a context's base timing profile.
     *
     * @param context the context name
     * @param mu      Gaussian mean (ms)
     * @param sigma   Gaussian std dev (ms)
     * @param tau     Exponential mean (ms)
     * @return this Pace instance for chaining
     */
    public Pace tune(String context, double mu, double sigma, double tau) {
        overrides.put(context, new PaceProfile(mu, sigma, tau));
        return this;
    }

    /**
     * Sets the server idle timeout (from varbit 54077).
     * All break durations will be capped to this value minus a safety margin
     * to prevent auto-logoff during breaks.
     *
     * @param timeoutMs idle timeout in milliseconds, or 0 to disable capping
     * @return this Pace instance for chaining
     */
    public Pace idleTimeout(long timeoutMs) {
        this.idleTimeoutMs = timeoutMs;
        breaks.setIdleTimeout(timeoutMs);
        return this;
    }

    /**
     * Adjusts the global tempo. Values &lt; 1.0 speed everything up, &gt; 1.0 slows down.
     *
     * @param scale tempo multiplier (clamped to 0.5–2.0)
     * @return this Pace instance for chaining
     */
    public Pace tempo(double scale) {
        engine.setTempo(scale);
        return this;
    }

    // =========================================================
    //  Internal
    // =========================================================

    /** Resolves the profile for a context: override → default → fallback. */
    private PaceProfile profileFor(String context) {
        PaceProfile override = overrides.get(context);
        if (override != null) return override;
        PaceProfile def = DEFAULTS.get(context);
        return def != null ? def : FALLBACK;
    }

    private static void doSleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
