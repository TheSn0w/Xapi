package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import com.botwithus.bot.api.inventory.ActionTypes;

import java.util.Set;

/**
 * Rule-based intent inference engine for recorded actions.
 * Analyzes backpack state, trigger signals, and action context to produce
 * a human-readable hypothesis for why an action was performed.
 */
public final class IntentEngine {

    private IntentEngine() {}

    // Known bank-related interface IDs
    private static final int BANK_INTERFACE = 517;
    private static final int DEPOSIT_BOX_INTERFACE = 11;
    private static final Set<String> BANK_OPTION_NAMES = Set.of(
            "bank", "use", "open", "deposit", "deposit-all");
    private static final Set<String> RESOURCE_OPTION_NAMES = Set.of(
            "chop down", "chop", "mine", "fish", "net", "lure", "bait", "cage",
            "harpoon", "harvest", "pick", "cut", "collect", "gather", "dig",
            "trap", "catch", "hunt", "snare", "siphon", "cook", "smelt", "smith",
            "craft", "fletch", "tan", "spin", "weave", "string",
            "clean", "grind", "crush", "mix", "fill", "empty", "bury",
            "rake", "prune", "inspect", "check-health", "clear",
            "chisel", "saw", "infuse");

    public static IntentHypothesis infer(int actionId, String entityName, String optionName,
                                          BackpackSnapshot backpack, TriggerSignals triggers,
                                          int openInterfaceId) {
        String option = optionName != null ? optionName.toLowerCase() : "";
        String entity = entityName != null ? entityName.toLowerCase() : "";
        boolean bpFull = backpack != null && backpack.full();
        boolean bpEmpty = backpack != null && backpack.freeSlots() >= 27;

        // ── Bank interface actions ─────────────────────────────────────
        if (openInterfaceId == BANK_INTERFACE || openInterfaceId == DEPOSIT_BOX_INTERFACE) {
            if (isComponentAction(actionId)) {
                if (option.contains("deposit") || option.contains("store")) {
                    return new IntentHypothesis("Depositing items", "high");
                }
                if (option.contains("withdraw")) {
                    return new IntentHypothesis("Withdrawing supplies", "high");
                }
                return new IntentHypothesis("Bank UI interaction", "medium");
            }
        }

        // ── Walk actions ───────────────────────────────────────────────
        if (actionId == ActionTypes.WALK) {
            if (triggers != null && triggers.inventoryChanged() && bpFull) {
                return new IntentHypothesis("Walking to bank — backpack full", "medium");
            }
            if (triggers != null && triggers.inventoryChanged() && bpEmpty) {
                return new IntentHypothesis("Walking to resource — backpack empty", "medium");
            }
            if (triggers != null && triggers.animationEnded()) {
                return new IntentHypothesis("Repositioning after activity ended", "medium");
            }
            return new IntentHypothesis("Walking", "low");
        }

        // ── NPC interactions ───────────────────────────────────────────
        if (isNpcAction(actionId)) {
            if (BANK_OPTION_NAMES.contains(option) || entity.contains("banker")
                    || entity.contains("bank chest") || entity.contains("bank booth")) {
                if (bpFull) return new IntentHypothesis("Banking — backpack full", "high");
                return new IntentHypothesis("Opening bank", "medium");
            }
            if (option.equals("talk-to") || option.equals("talk")) {
                return new IntentHypothesis("Talking to NPC", "medium");
            }
            if (option.equals("attack")) {
                return new IntentHypothesis("Attacking NPC", "high");
            }
            return new IntentHypothesis("NPC interaction: " + option, "medium");
        }

        // ── Object interactions ────────────────────────────────────────
        if (isObjectAction(actionId)) {
            if (entity.contains("bank") || entity.contains("deposit box")) {
                if (bpFull) return new IntentHypothesis("Banking — backpack full", "high");
                return new IntentHypothesis("Opening bank", "medium");
            }
            if (RESOURCE_OPTION_NAMES.contains(option)) {
                if (triggers != null && triggers.animationEnded()) {
                    return new IntentHypothesis("Re-gathering — resource depleted", "high");
                }
                if (bpEmpty) {
                    return new IntentHypothesis("Starting new gathering trip", "high");
                }
                return new IntentHypothesis("Gathering: " + option + " " + entity, "high");
            }
            return new IntentHypothesis("Object interaction: " + option, "medium");
        }

        // ── Ground item interactions ───────────────────────────────────
        if (isGroundItemAction(actionId)) {
            if (option.equals("take")) {
                return new IntentHypothesis("Picking up: " + entity, "high");
            }
            return new IntentHypothesis("Ground item: " + option, "medium");
        }

        // ── Player interactions ────────────────────────────────────────
        if (isPlayerAction(actionId)) {
            if (option.equals("attack")) {
                return new IntentHypothesis("Attacking player", "high");
            }
            return new IntentHypothesis("Player interaction: " + option, "medium");
        }

        // ── Component/UI actions ───────────────────────────────────────
        if (isComponentAction(actionId)) {
            if (triggers != null && triggers.varbitChanged()) {
                return new IntentHypothesis("UI interaction — state change", "medium");
            }
            return new IntentHypothesis("UI interaction", "low");
        }

        // ── Dialogue ──────────────────────────────────────────────────
        if (actionId == ActionTypes.DIALOGUE) {
            return new IntentHypothesis("Dialogue interaction", "medium");
        }

        // ── Fallback ──────────────────────────────────────────────────
        return new IntentHypothesis("Manual interaction", "low");
    }

    // ── Action type classification ────────────────────────────────────

    private static boolean isNpcAction(int actionId) {
        for (int i = 1; i < ActionTypes.NPC_OPTIONS.length; i++) {
            if (ActionTypes.NPC_OPTIONS[i] == actionId) return true;
        }
        return false;
    }

    private static boolean isObjectAction(int actionId) {
        for (int i = 1; i < ActionTypes.OBJECT_OPTIONS.length; i++) {
            if (ActionTypes.OBJECT_OPTIONS[i] == actionId) return true;
        }
        return false;
    }

    private static boolean isGroundItemAction(int actionId) {
        for (int i = 1; i < ActionTypes.GROUND_ITEM_OPTIONS.length; i++) {
            if (ActionTypes.GROUND_ITEM_OPTIONS[i] == actionId) return true;
        }
        return false;
    }

    private static boolean isPlayerAction(int actionId) {
        for (int i = 1; i < ActionTypes.PLAYER_OPTIONS.length; i++) {
            if (ActionTypes.PLAYER_OPTIONS[i] == actionId) return true;
        }
        return false;
    }

    private static boolean isComponentAction(int actionId) {
        return actionId == ActionTypes.COMPONENT
                || actionId == ActionTypes.SELECT_COMPONENT_ITEM
                || actionId == ActionTypes.CONTAINER_ACTION
                || actionId == ActionTypes.COMP_ON_PLAYER;
    }
}
