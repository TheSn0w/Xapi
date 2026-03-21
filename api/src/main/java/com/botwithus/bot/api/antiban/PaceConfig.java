package com.botwithus.bot.api.antiban;

import java.util.HashMap;
import java.util.Map;

/**
 * Builder for custom {@link Pace} configuration.
 * <p>
 * Use this to override default profiles, set a personality seed, or
 * adjust the global tempo before creating a Pace instance.
 *
 * <pre>{@code
 * Pace pace = PaceConfig.builder()
 *     .seed("PlayerName")
 *     .tune("gather", 500, 100, 200)
 *     .tempo(1.1)
 *     .build();
 * }</pre>
 */
public final class PaceConfig {

    private String identity;
    private double tempo = 1.0;
    private final Map<String, PaceProfile> overrides = new HashMap<>();

    private PaceConfig() {}

    /** Creates a new builder. */
    public static PaceConfig builder() {
        return new PaceConfig();
    }

    /** Sets the personality seed identity (e.g., character name). */
    public PaceConfig seed(String identity) {
        this.identity = identity;
        return this;
    }

    /** Overrides a context's base timing profile. */
    public PaceConfig tune(String context, double mu, double sigma, double tau) {
        this.overrides.put(context, new PaceProfile(mu, sigma, tau));
        return this;
    }

    /** Sets the global tempo scale (&lt; 1.0 = faster, &gt; 1.0 = slower). */
    public PaceConfig tempo(double scale) {
        this.tempo = scale;
        return this;
    }

    /** Builds a configured {@link Pace} instance. */
    public Pace build() {
        PaceSeed seed = new PaceSeed(identity);
        Rhythm rhythm = new Rhythm();
        DelayEngine engine = new DelayEngine(seed);
        engine.setFatigueSupplier(rhythm);
        engine.setTempo(tempo);
        Breaks breaks = new Breaks(seed, rhythm);

        Pace pace = new Pace(engine, rhythm, breaks, seed);
        for (Map.Entry<String, PaceProfile> entry : overrides.entrySet()) {
            pace.tune(entry.getKey(), entry.getValue().mu(), entry.getValue().sigma(), entry.getValue().tau());
        }
        return pace;
    }
}
