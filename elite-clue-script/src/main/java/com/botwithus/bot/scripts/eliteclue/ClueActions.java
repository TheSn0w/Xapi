package com.botwithus.bot.scripts.eliteclue;

/**
 * Verified component hashes and GameAction parameters for all elite clue interactions.
 * All values confirmed via in-game testing with the V2 action debugger.
 *
 * GameAction format: new GameAction(actionType, option, subComponentId, componentHash)
 * Action type 57 = COMPONENT interaction
 * Action type 30 = DIALOG continue
 *
 * Component hash formula: (interfaceId << 16) | componentId
 * Example: 1473 << 16 | 5 = 96534533
 */
public final class ClueActions {

    private ClueActions() {}

    // ══════════════════════════════════════════════════════════════
    //  CLUE SCROLL INTERACTION (interface 1473 component 5)
    //  Hash: 96534533 = (1473 << 16) | 5
    //  Sub 3 = the clue item slot in backpack widget
    // ══════════════════════════════════════════════════════════════

    /** Dig on a clue scroll (no familiar) — option 3 "Dig" */
    // api.queueAction(new GameAction(57, 3, 3, 96534533));
    public static final int BACKPACK_IFACE = 1473;
    public static final int BACKPACK_COMP = 5;
    public static final int BACKPACK_HASH = 96534533; // (1473 << 16) | 5

    /** "Dig" = option 3 on the backpack clue item */
    public static final int DIG_OPTION = 3;

    /** "Open" Scroll box / "Read" Clue scroll = option 1 */
    public static final int OPEN_READ_OPTION = 1;

    // ══════════════════════════════════════════════════════════════
    //  FAMILIAR SPECIAL ABILITY (interface 662)
    //  Used to cast meerkat fetch instead of manual dig — bypasses combat
    // ══════════════════════════════════════════════════════════════

    /** Cast familiar special ability (meerkat fetch scroll) */
    // api.queueAction(new GameAction(57, 1, -1, 43384949));
    public static final int FAMILIAR_IFACE = 662;
    public static final int FAMILIAR_CAST_COMP = 117;
    public static final int FAMILIAR_CAST_HASH = 43384949; // (662 << 16) | 117

    /** Store max scrolls in familiar */
    // api.queueAction(new GameAction(57, 1, -1, 43384910));
    public static final int FAMILIAR_SCROLL_COMP = 78;
    public static final int FAMILIAR_SCROLL_HASH = 43384910; // (662 << 16) | 78
    public static final int STORE_SCROLLS_OPTION = 1;

    /** Take all scrolls from familiar */
    // api.queueAction(new GameAction(57, 2, -1, 43384910));
    public static final int TAKE_SCROLLS_OPTION = 2;

    // ══════════════════════════════════════════════════════════════
    //  DIALOG CONTINUE (interface 1189)
    //  "You've found a scroll box!" and similar popups
    // ══════════════════════════════════════════════════════════════

    /** Continue dialog — action type 30 */
    // api.queueAction(new GameAction(30, 0, -1, 77922323));
    public static final int DIALOG_IFACE = 1189;
    public static final int DIALOG_CONTINUE_COMP = 19;
    public static final int DIALOG_CONTINUE_HASH = 77922323; // (1189 << 16) | 19
    public static final int DIALOG_ACTION_TYPE = 30;

    // ══════════════════════════════════════════════════════════════
    //  CELTIC KNOT (interface 519 — verified)
    //  Varbits 4941-4944 track ring rotation (0-15), goal is all 0
    //  CW: subtracts 1 from varbit, CCW: adds 1
    //  Choose direction with fewest clicks: min(value, 16-value)
    // ══════════════════════════════════════════════════════════════

    public static final int CELTIC_KNOT_IFACE = 519;

    // Varbit 4941: Ring 1
    public static final int CK_RING1_CW_COMP = 10;     // hash: 34013194
    public static final int CK_RING1_CCW_COMP = 11;    // hash: 34013195
    public static final int CK_RING1_CW_HASH = 34013194;
    public static final int CK_RING1_CCW_HASH = 34013195;

    // Varbit 4942: Ring 2
    public static final int CK_RING2_CCW_COMP = 12;    // hash: 34013196
    public static final int CK_RING2_CW_COMP = 13;     // hash: 34013197
    public static final int CK_RING2_CCW_HASH = 34013196;
    public static final int CK_RING2_CW_HASH = 34013197;

    // Varbit 4943: Ring 3
    public static final int CK_RING3_CCW_COMP = 15;    // hash: 34013199
    public static final int CK_RING3_CW_COMP = 14;     // hash: 34013198
    public static final int CK_RING3_CCW_HASH = 34013199;
    public static final int CK_RING3_CW_HASH = 34013198;

    // Varbit 4944: Ring 4 (not yet verified — may not exist for all puzzles)
    // TBD — needs testing with a 4-ring celtic knot

    // Unlock button
    public static final int CK_UNLOCK_COMP = 176;      // hash: 34013360
    public static final int CK_UNLOCK_HASH = 34013360;

    // ══════════════════════════════════════════════════════════════
    //  SLIDE PUZZLE (interface 1931)
    //  5x5 grid, tiles as children of component 18
    //  Each tile = subComponentId (0-24 = position in grid)
    //  Move action: GameAction(57, 1, position, 126550034)
    //  Hash: 126550034 = (1931 << 16) | 18
    // ══════════════════════════════════════════════════════════════

    public static final int SLIDE_IFACE = 1931;
    public static final int SLIDE_GRID_COMP = 18;
    public static final int SLIDE_GRID_HASH = 126550034; // (1931 << 16) | 18
    public static final int SLIDE_MOVE_OPTION = 1;

    /**
     * Build a GameAction to move a tile at the given position in the slide puzzle.
     * Only tiles adjacent to the empty slot can actually be moved.
     *
     * @param position the grid position (0-24) of the tile to move
     * @return formatted action string for logging
     */
    public static String slideMoveDesc(int position) {
        return "GameAction(57, 1, " + position + ", 126550034)";
    }

    // ══════════════════════════════════════════════════════════════
    //  COMPONENT INTERACTION (general action type)
    // ══════════════════════════════════════════════════════════════

    /** Standard component interaction action type */
    public static final int ACTION_TYPE_COMPONENT = 57;
}
