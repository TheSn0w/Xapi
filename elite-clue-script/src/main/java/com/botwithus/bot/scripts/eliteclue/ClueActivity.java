package com.botwithus.bot.scripts.eliteclue;

/**
 * Elite clue scroll activity states.
 * Drives the state machine in the main script loop.
 */
public enum ClueActivity {

    /** No clue active, waiting */
    IDLE("Idle"),

    /** Have a clue scroll, need to open it */
    OPEN_CLUE("Opening Clue"),

    /** Scanner interface open (1752) - compass/scan puzzle */
    SCANNER("Scanner"),

    /** Compass/direct clue interface open (996) */
    COMPASS("Compass"),

    /** Celtic knot puzzle interface open (394/519/525/526/529/1000-1003) */
    CELTIC_KNOT("Celtic Knot"),

    /** Slide puzzle interface open (1931) */
    SLIDE_PUZZLE("Slide Puzzle"),

    /** Hint arrow visible - dig at location */
    DIG("Dig"),

    /** Dialog open - handle NPC/reward dialog */
    DIALOG("Dialog"),

    /** Managing familiar (meerkat summoning/scrolls) */
    FAMILIAR("Familiar"),

    /** Walking to a scan/compass target coordinate */
    NAVIGATING("Navigating"),

    /** In combat (e.g. Guthix wizard after compass dig) */
    COMBAT("Combat");

    private final String label;

    ClueActivity(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
