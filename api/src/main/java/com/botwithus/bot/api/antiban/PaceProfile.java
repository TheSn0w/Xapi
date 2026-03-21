package com.botwithus.bot.api.antiban;

/**
 * Ex-Gaussian distribution parameters for a named delay context.
 * <p>
 * The ex-Gaussian is the sum of a Gaussian (mu, sigma) and an Exponential (tau):
 * {@code sample = Gaussian(mu, sigma) + Exponential(tau)}
 * <p>
 * This produces the right-skewed distribution with occasional long-tail outliers
 * observed in human reaction time research.
 *
 * @param mu    Gaussian mean in milliseconds — the typical delay center
 * @param sigma Gaussian standard deviation — controls spread around the center
 * @param tau   Exponential mean — controls the weight of the right tail (long outliers)
 */
public record PaceProfile(double mu, double sigma, double tau) {

    public PaceProfile {
        if (mu < 0) throw new IllegalArgumentException("mu must be >= 0, got " + mu);
        if (sigma < 0) throw new IllegalArgumentException("sigma must be >= 0, got " + sigma);
        if (tau < 0) throw new IllegalArgumentException("tau must be >= 0, got " + tau);
    }

    /** Scales all three parameters by the given factor. Factor must be positive. */
    public PaceProfile scale(double factor) {
        if (factor <= 0) throw new IllegalArgumentException("factor must be > 0, got " + factor);
        return new PaceProfile(mu * factor, sigma * factor, tau * factor);
    }
}
