package com.botwithus.bot.scripts.woodcutting;

import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

import java.util.concurrent.ThreadLocalRandom;

/**
 * Per-session behavioral personality that makes the bot feel human.
 * <p>
 * Each session rolls a unique personality with tendencies for various quirks.
 * Some sessions the player is an "early filler" who fills the wood box before
 * the backpack is full. Other sessions they're a "nearest-tree optimizer" who
 * always picks the closest tree. This variance between sessions is key —
 * a bot that always behaves identically across sessions is detectable.
 * <p>
 * Quirks:
 * <ol>
 *   <li>Early wood box fill — fill before backpack is full</li>
 *   <li>Not-nearest tree — sometimes pick 2nd/3rd closest tree</li>
 *   <li>Idle stare — pause 3-8s before clicking next tree</li>
 *   <li>Early banking — bank with a few empty slots</li>
 *   <li>Mis-ordered banking — try fill before deposit sometimes</li>
 *   <li>Tree loyalty — click a depleted tree, fail, then find another</li>
 *   <li>Inventory fidgeting — inspect wood box or examine tree mid-session</li>
 *   <li>Overshoot walking — walk past trees then come back</li>
 *   <li>Superstitious fill count — per-session preferred fill threshold</li>
 * </ol>
 */
public final class Quirks {

    private static final BotLogger log = LoggerFactory.getLogger(Quirks.class);

    // ── Per-session personality (rolled once at startup) ──────────

    /** How likely this session is to fill wood box early (0.0 = never, 1.0 = always). */
    public final double earlyFillTendency;

    /** Preferred backpack log count to trigger early fill (8-20). */
    public final int earlyFillThreshold;

    /** How likely to NOT pick the nearest tree (0.0 = always nearest, 0.5 = often random). */
    public final double notNearestTendency;

    /** How likely to idle-stare between chops (0.0 = never, 0.3 = frequent). */
    public final double idleStareTendency;

    /** How many empty slots this session tolerates before banking (0-4). */
    public final int earlyBankSlots;

    /** How likely to mis-order bank operations (0.0 = never, 0.3 = sometimes). */
    public final double misorderTendency;

    /** How likely to click a depleted tree spot (0.0 = never, 0.2 = sometimes). */
    public final double treeLoyaltyTendency;

    /** How likely to fidget (inspect woodbox, examine tree) per action (0.0-0.15). */
    public final double fidgetTendency;

    /** How likely to overshoot when walking to trees (0.0-0.25). */
    public final double overshootTendency;

    /** Last quirk that fired, for overlay display. */
    private volatile String lastQuirk = "None";

    // ── Latch state for early-bank decision ─────────────────────
    private boolean earlyBankDecided = false;
    private boolean earlyBankResult = false;
    private int earlyBankDecidedAtFreeSlots = -1;

    public Quirks() {
        ThreadLocalRandom rng = ThreadLocalRandom.current();

        // Each tendency is rolled per-session so behavior varies between runs
        this.earlyFillTendency   = rng.nextDouble(0.0, 0.6);    // 0-60% sessions are early fillers
        this.earlyFillThreshold  = rng.nextInt(8, 21);           // fill when backpack has 8-20 logs
        this.notNearestTendency  = rng.nextDouble(0.03, 0.12);   // 3-12% chance of not-nearest
        this.idleStareTendency   = rng.nextDouble(0.02, 0.15);   // 2-15% chance of idle stare
        this.earlyBankSlots      = rng.nextInt(0, 5);            // 0-4 empty slots tolerated
        this.misorderTendency    = rng.nextDouble(0.0, 0.15);    // 0-15% chance of mis-order
        this.treeLoyaltyTendency = rng.nextDouble(0.02, 0.12);   // 2-12% chance of clicking depleted
        this.fidgetTendency      = rng.nextDouble(0.01, 0.08);   // 1-8% chance of fidgeting
        this.overshootTendency   = rng.nextDouble(0.03, 0.15);   // 3-15% chance of overshoot

        log.info("[Quirks] Session personality: earlyFill={}% (at {}), notNearest={}%, " +
                        "idleStare={}%, earlyBank={}slots, fidget={}%",
                (int) (earlyFillTendency * 100), earlyFillThreshold,
                (int) (notNearestTendency * 100), (int) (idleStareTendency * 100),
                earlyBankSlots, (int) (fidgetTendency * 100));
    }

    // ── Per-action roll methods ──────────────────────────────────

    /** Should we fill the wood box early (before backpack is full)? */
    public boolean shouldEarlyFill(int backpackLogs, int boxStored, int boxCapacity) {
        if (earlyFillTendency <= 0 || backpackLogs < earlyFillThreshold) return false;
        // More likely when box is close to capacity (OCD about round numbers)
        double proximityBonus = boxCapacity > 0 ? (double) boxStored / boxCapacity * 0.3 : 0;
        boolean result = roll(earlyFillTendency + proximityBonus);
        if (result) setLastQuirk("Early fill (" + backpackLogs + " logs)");
        return result;
    }

    /** Should we pick a non-nearest tree? Returns 0 for nearest, or 1-2 for offset. */
    public int treeSelectionOffset() {
        if (roll(notNearestTendency)) {
            int offset = ThreadLocalRandom.current().nextInt(1, 3); // 1 or 2
            setLastQuirk("Picked " + ordinal(offset + 1) + " closest tree");
            return offset;
        }
        return 0;
    }

    /** Should we idle-stare before clicking the next tree? Returns ms or 0. */
    public long idleStareMs() {
        if (roll(idleStareTendency)) {
            long ms = ThreadLocalRandom.current().nextLong(3000, 8500);
            setLastQuirk("Idle stare (" + (ms / 1000) + "s)");
            return ms;
        }
        return 0;
    }

    /**
     * Should we bank early (with empty slots remaining)?
     * Latched: rolls once per threshold crossing, remembers the decision
     * until free slots increase (i.e. after banking/filling resets inventory).
     */
    public boolean shouldEarlyBank(int freeSlots) {
        if (earlyBankSlots <= 0 || freeSlots > earlyBankSlots) {
            // Above threshold — reset latch so it can re-roll next time slots drop
            earlyBankDecided = false;
            earlyBankResult = false;
            return false;
        }
        // Within threshold — roll once, then latch the result
        if (!earlyBankDecided || freeSlots != earlyBankDecidedAtFreeSlots) {
            earlyBankDecided = true;
            earlyBankDecidedAtFreeSlots = freeSlots;
            earlyBankResult = roll(0.6);
            if (earlyBankResult) setLastQuirk("Early bank (" + freeSlots + " slots left)");
        }
        return earlyBankResult;
    }

    /** Reset early-bank latch (call after banking/filling completes). */
    public void resetEarlyBankLatch() {
        earlyBankDecided = false;
        earlyBankResult = false;
        earlyBankDecidedAtFreeSlots = -1;
    }

    /** Should we mis-order bank operations (try fill before deposit)? */
    public boolean shouldMisorderBank() {
        boolean result = roll(misorderTendency);
        if (result) setLastQuirk("Mis-ordered bank ops");
        return result;
    }

    /** Should we click a depleted tree? */
    public boolean shouldClickDepletedTree() {
        boolean result = roll(treeLoyaltyTendency);
        if (result) setLastQuirk("Clicked depleted tree");
        return result;
    }

    /** Should we fidget (inspect woodbox or examine nearby tree)? */
    public boolean shouldFidget() {
        boolean result = roll(fidgetTendency);
        if (result) setLastQuirk("Fidgeting");
        return result;
    }

    /** Should we overshoot when walking to trees? */
    public boolean shouldOvershoot() {
        boolean result = roll(overshootTendency);
        if (result) setLastQuirk("Overshot trees");
        return result;
    }

    /** Returns the most recent quirk that fired, for overlay display. */
    public String getLastQuirk() {
        return lastQuirk;
    }

    private void setLastQuirk(String quirk) {
        this.lastQuirk = quirk;
        log.debug("[Quirks] {}", quirk);
    }

    private static boolean roll(double chance) {
        return ThreadLocalRandom.current().nextDouble() < chance;
    }

    private static String ordinal(int n) {
        return switch (n) {
            case 1 -> "1st";
            case 2 -> "2nd";
            case 3 -> "3rd";
            default -> n + "th";
        };
    }
}
