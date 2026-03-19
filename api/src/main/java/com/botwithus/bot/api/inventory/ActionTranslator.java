package com.botwithus.bot.api.inventory;

/**
 * Translates raw action parameters into copy-paste-ready Java code.
 * Output is two lines: a comment with the high-level entity API call,
 * followed by the raw {@code api.queueAction(new GameAction(...))} call.
 */
public final class ActionTranslator {

    private ActionTranslator() {}

    /**
     * Converts an action into script-ready Java code with resolved entity/option names.
     *
     * @param entityName resolved name (e.g. "Hill Giant"), or null/empty if unknown
     * @param optionName resolved option (e.g. "Attack"), or null/empty if unknown
     * @return a two-line string: comment with high-level call + raw GameAction call
     */
    public static String toCode(int actionId, int p1, int p2, int p3,
                                String entityName, String optionName) {
        String raw = "api.queueAction(new GameAction(" + actionId + ", " + p1 + ", " + p2 + ", " + p3 + "))";
        String nameSuffix = formatNames(entityName, optionName);

        // NPC options (9-13, 1003) — p1 is serverIndex (runtime, not hardcodable)
        int npcSlot = findSlot(ActionTypes.NPC_OPTIONS, actionId);
        if (npcSlot > 0) {
            String highLevel = hasName(entityName)
                    ? "npcs.query().name(\"" + entityName + "\").nearest().interact(\"" + orEmpty(optionName) + "\")"
                    : "npcs.query().index(" + p1 + ").nearest().interact(" + npcSlot + ")";
            return highLevel + "\n" + raw;
        }

        // Object options (3-6, 1001-1002) — p1 is typeId (stable, hardcodable)
        int objSlot = findSlot(ActionTypes.OBJECT_OPTIONS, actionId);
        if (objSlot > 0) {
            String highLevel = hasName(entityName)
                    ? "objects.query().name(\"" + entityName + "\").nearest().interact(\"" + orEmpty(optionName) + "\")"
                    : "objects.query().typeId(" + p1 + ").nearest().interact(" + objSlot + ")";
            return highLevel + "\n" + raw;
        }

        // Ground item options (18-22, 1004) — p1 is itemId (stable)
        int giSlot = findSlot(ActionTypes.GROUND_ITEM_OPTIONS, actionId);
        if (giSlot > 0) {
            String highLevel = hasName(entityName)
                    ? "groundItems.query().name(\"" + entityName + "\").nearest().interact(\"" + orEmpty(optionName) + "\")"
                    : "groundItems.query().itemId(" + p1 + ").nearest().interact(" + giSlot + ")";
            return highLevel + "\n" + raw;
        }

        // Player options (44-53) — p1 is serverIndex (runtime)
        int playerSlot = findSlot(ActionTypes.PLAYER_OPTIONS, actionId);
        if (playerSlot > 0) {
            String highLevel = hasName(entityName)
                    ? "players.query().name(\"" + entityName + "\").nearest().interact(" + playerSlot + ")"
                    : "players.query().index(" + p1 + ").nearest().interact(" + playerSlot + ")";
            return highLevel + "\n" + raw;
        }

        // Component (57)
        if (actionId == ActionTypes.COMPONENT) {
            return formatComponentCode(p1, p2, p3, optionName, raw);
        }

        // Select component item (58)
        if (actionId == ActionTypes.SELECT_COMPONENT_ITEM) {
            return formatComponentCode(p1, p2, p3, optionName, raw);
        }

        // Delegate to the no-names version for everything else
        return toCode(actionId, p1, p2, p3);
    }

    /**
     * Converts an action into script-ready Java code (without resolved names).
     *
     * @return a two-line string: comment with high-level call + raw GameAction call
     */
    public static String toCode(int actionId, int p1, int p2, int p3) {
        String raw = "api.queueAction(new GameAction(" + actionId + ", " + p1 + ", " + p2 + ", " + p3 + "))";

        // NPC options (9–13, 1003)
        int npcSlot = findSlot(ActionTypes.NPC_OPTIONS, actionId);
        if (npcSlot > 0) {
            String comment = "// npcs.query().index(" + p1 + ").nearest().interact(" + npcSlot + ")";
            return comment + "  -- " + ActionTypes.nameOf(actionId) + "\n" + raw;
        }

        // Object options (3–6, 1001–1002)
        int objSlot = findSlot(ActionTypes.OBJECT_OPTIONS, actionId);
        if (objSlot > 0) {
            String comment = "// objects.query().typeId(" + p1 + ").tile(" + p2 + ", " + p3 + ").nearest().interact(" + objSlot + ")";
            return comment + "  -- " + ActionTypes.nameOf(actionId) + "\n" + raw;
        }

        // Ground item options (18–22, 1004)
        int giSlot = findSlot(ActionTypes.GROUND_ITEM_OPTIONS, actionId);
        if (giSlot > 0) {
            String comment = "// groundItems.query().itemId(" + p1 + ").tile(" + p2 + ", " + p3 + ").nearest().interact(" + giSlot + ")";
            return comment + "  -- " + ActionTypes.nameOf(actionId) + "\n" + raw;
        }

        // Player options (44–53)
        int playerSlot = findSlot(ActionTypes.PLAYER_OPTIONS, actionId);
        if (playerSlot > 0) {
            String comment = "// players.query().index(" + p1 + ").nearest().interact(" + playerSlot + ")";
            return comment + "  -- " + ActionTypes.nameOf(actionId) + "\n" + raw;
        }

        // Component (57)
        if (actionId == ActionTypes.COMPONENT) {
            return formatComponentCode(p1, p2, p3, null, raw);
        }

        // Select component item (58)
        if (actionId == ActionTypes.SELECT_COMPONENT_ITEM) {
            return formatComponentCode(p1, p2, p3, null, raw);
        }

        // Walk (23)
        if (actionId == ActionTypes.WALK) {
            return "// WALK to (" + p2 + ", " + p3 + ", " + p1 + ")\n" + raw;
        }

        // Dialogue (30)
        if (actionId == ActionTypes.DIALOGUE) {
            return "// DIALOGUE option " + p1 + "\n" + raw;
        }

        // Select NPC (8)
        if (actionId == ActionTypes.SELECT_NPC) {
            return "// SELECT_NPC serverIndex:" + p1 + "\n" + raw;
        }

        // Select Object (2)
        if (actionId == ActionTypes.SELECT_OBJECT) {
            return "// SELECT_OBJECT typeId:" + p1 + " tile:(" + p2 + ", " + p3 + ")\n" + raw;
        }

        // Select Ground Item (17)
        if (actionId == ActionTypes.SELECT_GROUND_ITEM) {
            return "// SELECT_GROUND_ITEM itemId:" + p1 + " tile:(" + p2 + ", " + p3 + ")\n" + raw;
        }

        // Component key (5000)
        if (actionId == ActionTypes.COMPONENT_KEY) {
            return "// COMPONENT_KEY keyCode:" + p1 + "\n" + raw;
        }

        // Component drag (5001)
        if (actionId == ActionTypes.COMPONENT_DRAG) {
            return "// COMPONENT_DRAG\n" + raw;
        }

        // Select tile (59)
        if (actionId == ActionTypes.SELECT_TILE) {
            return "// SELECT_TILE (" + p1 + ", " + p2 + ")\n" + raw;
        }

        // Unknown — just the raw call
        return "// " + ActionTypes.nameOf(actionId) + "\n" + raw;
    }

    /**
     * Returns just the raw GameAction line (no comment), for clipboard copy.
     */
    public static String toRawCode(int actionId, int p1, int p2, int p3) {
        return "api.queueAction(new GameAction(" + actionId + ", " + p1 + ", " + p2 + ", " + p3 + "))";
    }

    /**
     * Generates complete, working component interaction code.
     * Includes the query to find the component AND the interaction call.
     */
    private static String formatComponentCode(int p1, int p2, int p3, String optionName, String raw) {
        int ifaceId = p3 >>> 16;
        int compId = p3 & 0xFFFF;

        String query = "api.queryComponents(ComponentFilter.builder().interfaceId(" + ifaceId + ").componentId(" + compId + ").build())";
        String interact;
        if (hasName(optionName)) {
            interact = "comp -> ComponentHelper.interactComponent(api, comp, \"" + optionName + "\")";
        } else {
            interact = "comp -> ComponentHelper.queueComponentAction(api, comp, " + p1 + ")";
        }

        String highLevel = query + ".stream().findFirst().ifPresent(" + interact + ")";
        String comment = "// iface:" + ifaceId + " comp:" + compId + " sub:" + p2 + " option:" + p1;
        return highLevel + "  " + comment + "\n" + raw;
    }

    /** Finds the 1-based slot index for an actionId in an options array, or -1. */
    private static int findSlot(int[] options, int actionId) {
        for (int i = 1; i < options.length; i++) {
            if (options[i] == actionId) return i;
        }
        return -1;
    }

    private static boolean hasName(String s) { return s != null && !s.isEmpty(); }
    private static String orEmpty(String s) { return s != null ? s : ""; }

    // ── Full Script Generation ────────────────────────────────────────────

    /**
     * Entry for script generation — mirrors XapiScript.LogEntry fields needed here.
     */
    public record ActionEntry(int actionId, int param1, int param2, int param3,
                              long timestamp, String entityName, String optionName) {}

    /**
     * Generates a complete, compilable BotScript from a list of recorded actions.
     *
     * @param entries  the recorded actions in order
     * @param className the class name for the generated script
     * @param useNames  if true, generates name-based queries (skeleton mode);
     *                  if false, uses raw GameAction calls (exact replay mode)
     * @return a complete Java source file as a string
     */
    public static String generateScript(java.util.List<ActionEntry> entries, String className, boolean useNames) {
        StringBuilder sb = new StringBuilder();
        sb.append("import com.botwithus.bot.api.*;\n");
        sb.append("import com.botwithus.bot.api.model.*;\n");
        sb.append("import com.botwithus.bot.api.inventory.*;\n");
        sb.append("import com.botwithus.bot.api.query.*;\n");
        sb.append("import com.botwithus.bot.api.ui.ScriptUI;\n");
        sb.append("import com.botwithus.bot.api.util.Conditions;\n\n");

        sb.append("@ScriptManifest(\n");
        sb.append("        name = \"").append(className).append("\",\n");
        sb.append("        version = \"1.0\",\n");
        sb.append("        author = \"Xapi Generated\",\n");
        sb.append("        description = \"Auto-generated from Xapi recording\",\n");
        sb.append("        category = ScriptCategory.UTILITY\n");
        sb.append(")\n");
        sb.append("public class ").append(className).append(" implements BotScript {\n\n");
        sb.append("    private ScriptContext ctx;\n");
        sb.append("    private int step = 0;\n\n");

        sb.append("    @Override\n");
        sb.append("    public void onStart(ScriptContext ctx) {\n");
        sb.append("        this.ctx = ctx;\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public int onLoop() {\n");
        sb.append("        GameAPI api = ctx.getGameAPI();\n");
        sb.append("        switch (step) {\n");

        for (int i = 0; i < entries.size(); i++) {
            ActionEntry e = entries.get(i);
            String code;
            if (useNames) {
                // Use the two-line output, take only the high-level line
                String full = toCode(e.actionId(), e.param1(), e.param2(), e.param3(),
                        e.entityName(), e.optionName());
                int nl = full.indexOf('\n');
                code = nl > 0 ? full.substring(0, nl) : full;
                // If it starts with "//", it's a comment-only line — fall back to raw
                if (code.startsWith("//")) {
                    code = toRawCode(e.actionId(), e.param1(), e.param2(), e.param3());
                }
            } else {
                code = toRawCode(e.actionId(), e.param1(), e.param2(), e.param3());
            }

            // Calculate delay to next action
            int delayMs = 600;
            if (i + 1 < entries.size()) {
                long delta = entries.get(i + 1).timestamp() - e.timestamp();
                delayMs = (int) Math.max(300, Math.min(delta, 10000));
            }

            // Human-readable comment
            String comment = "";
            if (hasName(e.entityName()) || hasName(e.optionName())) {
                comment = " // " + orEmpty(e.optionName())
                        + (hasName(e.entityName()) ? " -> " + e.entityName() : "");
            }

            sb.append("            case ").append(i).append(" -> {\n");

            // Walk actions get special handling
            if (e.actionId() == ActionTypes.WALK) {
                sb.append("                api.walkToAsync(").append(e.param2()).append(", ").append(e.param3()).append(");\n");
                sb.append("                Conditions.waitUntil(() -> !api.getLocalPlayer().isMoving(), 5000);\n");
            } else {
                sb.append("                ").append(code).append(";").append(comment).append("\n");
            }

            sb.append("                step++;\n");
            sb.append("                return ").append(delayMs).append(";\n");
            sb.append("            }\n");
        }

        sb.append("            default -> {\n");
        sb.append("                return -1; // Done — stop script\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        sb.append("    @Override\n");
        sb.append("    public void onStop() {}\n");
        sb.append("}\n");

        return sb.toString();
    }

    /** Formats resolved names as: ' | Attack -> "Hill Giant"' */
    private static String formatNames(String entityName, String optionName) {
        boolean hasEntity = entityName != null && !entityName.isEmpty();
        boolean hasOption = optionName != null && !optionName.isEmpty();
        if (!hasEntity && !hasOption) return "";
        StringBuilder sb = new StringBuilder(" | ");
        if (hasOption) sb.append(optionName);
        if (hasEntity) {
            if (hasOption) sb.append(" -> ");
            sb.append("\"").append(entityName).append("\"");
        }
        return sb.toString();
    }
}
