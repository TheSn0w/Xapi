package com.botwithus.bot.api.antiban;

import java.util.Random;

/**
 * Deterministic personality derivation from an identity string (e.g., character name).
 * <p>
 * Same identity always produces the same parameters. Different identities produce
 * different parameters. All values stay within natural human ranges.
 * <p>
 * This creates per-account behavioral variation so two accounts running the same
 * script produce distinguishably different timing fingerprints.
 */
public final class PaceSeed {

    // ── Timing profile scale factors ──────────────────────
    private final double reactScale;
    private final double gatherScale;
    private final double bankScale;
    private final double walkScale;
    private final double idleScale;
    private final double combatScale;
    private final double menuScale;

    // ── Momentum & fatigue ────────────────────────────────
    private final double momentum;
    private final double fatigueMuEffect;
    private final double fatigueSigmaEffect;
    private final double fatigueTauEffect;

    // ── Break parameters ──────────────────────────────────
    private final double breakProbScale;
    private final double breakCooldownScale;

    // ── Warmup ────────────────────────────────────────────
    private final double warmupMultiplier;
    private final long warmupDurationMs;

    // ── Context-switch ────────────────────────────────────
    private final double contextSwitchPenalty;

    // ── Identity ──────────────────────────────────────────
    private final String identity;

    /**
     * Creates a personality deterministically derived from the given identity.
     *
     * @param identity the identity string (e.g., character name); null/blank uses defaults
     */
    public PaceSeed(String identity) {
        this.identity = (identity != null && !identity.isBlank()) ? identity : "default";

        if ("default".equals(this.identity)) {
            reactScale = 1.0;
            gatherScale = 1.0;
            bankScale = 1.0;
            walkScale = 1.0;
            idleScale = 1.0;
            combatScale = 1.0;
            menuScale = 1.0;
            momentum = 0.30;
            fatigueMuEffect = 0.30;
            fatigueSigmaEffect = 0.50;
            fatigueTauEffect = 1.50;
            breakProbScale = 1.0;
            breakCooldownScale = 1.0;
            warmupMultiplier = 1.20;
            warmupDurationMs = 900_000;
            contextSwitchPenalty = 0.30;
            return;
        }

        Random rng = new Random(this.identity.hashCode());

        reactScale = range(rng, 0.85, 1.15);
        gatherScale = range(rng, 0.85, 1.15);
        bankScale = range(rng, 0.85, 1.15);
        walkScale = range(rng, 0.85, 1.15);
        idleScale = range(rng, 0.85, 1.15);
        combatScale = range(rng, 0.85, 1.15);
        menuScale = range(rng, 0.85, 1.15);
        momentum = range(rng, 0.15, 0.45);
        fatigueMuEffect = range(rng, 0.25, 0.35);
        fatigueSigmaEffect = range(rng, 0.40, 0.60);
        fatigueTauEffect = range(rng, 1.20, 1.80);
        breakProbScale = range(rng, 0.70, 1.30);
        breakCooldownScale = range(rng, 0.80, 1.20);
        warmupMultiplier = range(rng, 1.10, 1.30);
        warmupDurationMs = rangeInt(rng, 600_000, 1_200_000);
        contextSwitchPenalty = range(rng, 0.20, 0.40);
    }

    /** Default seed with all parameters at identity values. */
    public PaceSeed() {
        this(null);
    }

    // ── Accessors ─────────────────────────────────────────

    public String identity()           { return identity; }

    /** Returns the scale factor for a named context, defaulting to 1.0 for unknown contexts. */
    public double scaleFor(String context) {
        return switch (context) {
            case "react" -> reactScale;
            case "gather" -> gatherScale;
            case "bank" -> bankScale;
            case "walk" -> walkScale;
            case "idle" -> idleScale;
            case "combat" -> combatScale;
            case "menu" -> menuScale;
            default -> 1.0;
        };
    }

    public double momentum()           { return momentum; }
    public double fatigueMuEffect()    { return fatigueMuEffect; }
    public double fatigueSigmaEffect() { return fatigueSigmaEffect; }
    public double fatigueTauEffect()   { return fatigueTauEffect; }
    public double breakProbScale()     { return breakProbScale; }
    public double breakCooldownScale() { return breakCooldownScale; }
    public double warmupMultiplier()   { return warmupMultiplier; }
    public long   warmupDurationMs()   { return warmupDurationMs; }
    public double contextSwitchPenalty() { return contextSwitchPenalty; }

    // ── Helpers ───────────────────────────────────────────

    private static double range(Random rng, double min, double max) {
        return min + rng.nextDouble() * (max - min);
    }

    private static int rangeInt(Random rng, int min, int max) {
        return min + rng.nextInt(max - min + 1);
    }
}
