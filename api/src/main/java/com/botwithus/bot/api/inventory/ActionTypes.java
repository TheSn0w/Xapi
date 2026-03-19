package com.botwithus.bot.api.inventory;

import java.util.Map;

/**
 * Action type IDs corresponding to the game's mini menu system.
 */
public final class ActionTypes {

    private ActionTypes() {}

    // ========================== Movement ==========================

    public static final int WALK = 23;

    // ========================== NPC ==========================

    public static final int SELECT_NPC = 8;
    public static final int NPC1 = 9;
    public static final int NPC2 = 10;
    public static final int NPC3 = 11;
    public static final int NPC4 = 12;
    public static final int NPC5 = 13;
    public static final int NPC6 = 1003;

    /** NPC option action IDs indexed by 1-based option slot. */
    public static final int[] NPC_OPTIONS = { 0, NPC1, NPC2, NPC3, NPC4, NPC5, NPC6 };

    // ========================== Scene Object ==========================

    public static final int SELECT_OBJECT = 2;
    public static final int OBJECT1 = 3;
    public static final int OBJECT2 = 4;
    public static final int OBJECT3 = 5;
    public static final int OBJECT4 = 6;
    public static final int OBJECT5 = 1001;
    public static final int OBJECT6 = 1002;

    /** Object option action IDs indexed by 1-based option slot. */
    public static final int[] OBJECT_OPTIONS = { 0, OBJECT1, OBJECT2, OBJECT3, OBJECT4, OBJECT5, OBJECT6 };

    // ========================== Ground Item ==========================

    public static final int SELECT_GROUND_ITEM = 17;
    public static final int GROUND_ITEM1 = 18;
    public static final int GROUND_ITEM2 = 19;
    public static final int GROUND_ITEM3 = 20;
    public static final int GROUND_ITEM4 = 21;
    public static final int GROUND_ITEM5 = 22;
    public static final int GROUND_ITEM6 = 1004;

    /** Ground item option action IDs indexed by 1-based option slot. */
    public static final int[] GROUND_ITEM_OPTIONS = { 0, GROUND_ITEM1, GROUND_ITEM2, GROUND_ITEM3, GROUND_ITEM4, GROUND_ITEM5, GROUND_ITEM6 };

    // ========================== Player ==========================

    public static final int PLAYER_SELECT = 15;
    public static final int PLAYER1 = 44;
    public static final int PLAYER2 = 45;
    public static final int PLAYER3 = 46;
    public static final int PLAYER4 = 47;
    public static final int PLAYER5 = 48;
    public static final int PLAYER6 = 49;
    public static final int PLAYER7 = 50;
    public static final int PLAYER8 = 51;
    public static final int PLAYER9 = 52;
    public static final int PLAYER10 = 53;

    /** Player option action IDs indexed by 1-based option slot. */
    public static final int[] PLAYER_OPTIONS = { 0, PLAYER1, PLAYER2, PLAYER3, PLAYER4, PLAYER5, PLAYER6, PLAYER7, PLAYER8, PLAYER9, PLAYER10 };

    // ========================== Component / Interface ==========================

    public static final int SELECT_COMPONENT = 25;
    public static final int DIALOGUE = 30;
    public static final int COMPONENT = 57;
    public static final int SELECT_COMPONENT_ITEM = 58;
    public static final int SELECT_TILE = 59;
    public static final int COMP_ON_PLAYER = 16;

    // ========================== Advanced ==========================

    public static final int COMPONENT_KEY = 5000;
    public static final int COMPONENT_DRAG = 5001;
    public static final int RADIO_GROUP_SELECT = 5002;

    // ========================== Lookup ==========================

    private static final Map<Integer, String> NAMES = Map.ofEntries(
            Map.entry(WALK, "WALK"),
            Map.entry(SELECT_NPC, "SELECT_NPC"),
            Map.entry(NPC1, "NPC1"), Map.entry(NPC2, "NPC2"), Map.entry(NPC3, "NPC3"),
            Map.entry(NPC4, "NPC4"), Map.entry(NPC5, "NPC5"), Map.entry(NPC6, "NPC6"),
            Map.entry(SELECT_OBJECT, "SELECT_OBJECT"),
            Map.entry(OBJECT1, "OBJECT1"), Map.entry(OBJECT2, "OBJECT2"),
            Map.entry(OBJECT3, "OBJECT3"), Map.entry(OBJECT4, "OBJECT4"),
            Map.entry(OBJECT5, "OBJECT5"), Map.entry(OBJECT6, "OBJECT6"),
            Map.entry(SELECT_GROUND_ITEM, "SELECT_GROUND_ITEM"),
            Map.entry(GROUND_ITEM1, "GROUND_ITEM1"), Map.entry(GROUND_ITEM2, "GROUND_ITEM2"),
            Map.entry(GROUND_ITEM3, "GROUND_ITEM3"), Map.entry(GROUND_ITEM4, "GROUND_ITEM4"),
            Map.entry(GROUND_ITEM5, "GROUND_ITEM5"), Map.entry(GROUND_ITEM6, "GROUND_ITEM6"),
            Map.entry(PLAYER_SELECT, "PLAYER_SELECT"),
            Map.entry(PLAYER1, "PLAYER1"), Map.entry(PLAYER2, "PLAYER2"),
            Map.entry(PLAYER3, "PLAYER3"), Map.entry(PLAYER4, "PLAYER4"),
            Map.entry(PLAYER5, "PLAYER5"), Map.entry(PLAYER6, "PLAYER6"),
            Map.entry(PLAYER7, "PLAYER7"), Map.entry(PLAYER8, "PLAYER8"),
            Map.entry(PLAYER9, "PLAYER9"), Map.entry(PLAYER10, "PLAYER10"),
            Map.entry(SELECT_COMPONENT, "SELECT_COMPONENT"),
            Map.entry(DIALOGUE, "DIALOGUE"), Map.entry(COMPONENT, "COMPONENT"),
            Map.entry(SELECT_COMPONENT_ITEM, "SELECT_COMPONENT_ITEM"),
            Map.entry(SELECT_TILE, "SELECT_TILE"), Map.entry(COMP_ON_PLAYER, "COMP_ON_PLAYER"),
            Map.entry(COMPONENT_KEY, "COMPONENT_KEY"),
            Map.entry(COMPONENT_DRAG, "COMPONENT_DRAG"),
            Map.entry(RADIO_GROUP_SELECT, "RADIO_GROUP_SELECT")
    );

    /** Returns a human-readable name for the given action ID, or the numeric ID if unknown. */
    public static String nameOf(int actionId) {
        return NAMES.getOrDefault(actionId, String.valueOf(actionId));
    }
}
