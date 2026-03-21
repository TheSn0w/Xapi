package com.botwithus.bot.api.antiban;

/**
 * Shared delay and sleep utilities for API classes.
 * <p>Provides static methods for human-like interaction timing using ex-Gaussian distributions.
 * All delay methods return millisecond values clamped to reasonable ranges.</p>
 *
 * <p>This class eliminates the duplicate {@code randomDelay()} and {@code sleep()} methods
 * previously found in Bank, DepositBox, Production, and other inventory wrappers.</p>
 *
 * @see DelayEngine#exGaussianSample(double, double, double)
 */
public final class Delays {

    private Delays() {}

    /**
     * Sleep the current (virtual) thread for the specified duration.
     * <p>Handles {@link InterruptedException} by restoring the interrupt flag.
     * Short-circuits immediately if {@code ms <= 0}.</p>
     *
     * @param ms the duration to sleep in milliseconds
     */
    public static void sleep(long ms) {
        if (ms <= 0) return;
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Standard interaction delay (~350–700ms, right-skewed).
     * <p>Ex-Gaussian(mu=400, sigma=60, tau=80), clamped to [250, 1200]ms.
     * Suitable for between-action pauses in inventory operations.</p>
     *
     * @return a random delay in milliseconds
     */
    public static int randomDelay() {
        return clamp(DelayEngine.exGaussianSample(400, 60, 80), 250, 1200);
    }

    /**
     * Short delay for quick UI interactions (~150–400ms, right-skewed).
     * <p>Ex-Gaussian(mu=200, sigma=30, tau=50), clamped to [100, 600]ms.
     * Suitable for slider button clicks, rapid sequential actions.</p>
     *
     * @return a random delay in milliseconds
     */
    public static int shortDelay() {
        return clamp(DelayEngine.exGaussianSample(200, 30, 50), 100, 600);
    }

    /**
     * Medium delay for inventory operations (~400–900ms, right-skewed).
     * <p>Ex-Gaussian(mu=500, sigma=80, tau=120), clamped to [300, 1500]ms.
     * Suitable for between deposits/withdrawals, interface transitions.</p>
     *
     * @return a random delay in milliseconds
     */
    public static int mediumDelay() {
        return clamp(DelayEngine.exGaussianSample(500, 80, 120), 300, 1500);
    }

    /**
     * Long delay for idle/thinking pauses (~800–2000ms, right-skewed).
     * <p>Ex-Gaussian(mu=1000, sigma=150, tau=300), clamped to [600, 3500]ms.
     * Suitable for decision pauses, interface opening waits.</p>
     *
     * @return a random delay in milliseconds
     */
    public static int longDelay() {
        return clamp(DelayEngine.exGaussianSample(1000, 150, 300), 600, 3500);
    }

    private static int clamp(double value, int min, int max) {
        return (int) Math.max(min, Math.min(max, Math.round(value)));
    }
}
