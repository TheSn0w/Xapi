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
        String raw = "api.queueAction(new GameAction(" + actionId + ", " + p1 + ", " + p2 + ", " + p3 + "));";
        String nameSuffix = formatNames(entityName, optionName);

        // NPC options (9-13, 1003) — p1 is serverIndex (runtime, not hardcodable)
        int npcSlot = findSlot(ActionTypes.NPC_OPTIONS, actionId);
        if (npcSlot > 0) {
            String highLevel = hasName(entityName)
                    ? "npcs.query().named(\"" + entityName + "\").nearest().interact(\"" + orEmpty(optionName) + "\");"
                    : "npcs.query().index(" + p1 + ").nearest().interact(" + npcSlot + ");";
            return highLevel + "\n" + raw;
        }

        // Object options (3-6, 1001-1002) — p1 is typeId (stable, hardcodable)
        int objSlot = findSlot(ActionTypes.OBJECT_OPTIONS, actionId);
        if (objSlot > 0) {
            String highLevel = hasName(entityName)
                    ? "objects.query().named(\"" + entityName + "\").nearest().interact(\"" + orEmpty(optionName) + "\");"
                    : "objects.query().typeId(" + p1 + ").nearest().interact(" + objSlot + ");";
            return highLevel + "\n" + raw;
        }

        // Ground item options (18-22, 1004) — p1 is itemId (stable)
        int giSlot = findSlot(ActionTypes.GROUND_ITEM_OPTIONS, actionId);
        if (giSlot > 0) {
            String highLevel = hasName(entityName)
                    ? "groundItems.query().named(\"" + entityName + "\").nearest().interact(\"" + orEmpty(optionName) + "\");"
                    : "groundItems.query().itemId(" + p1 + ").nearest().interact(" + giSlot + ");";
            return highLevel + "\n" + raw;
        }

        // Player options (44-53) — p1 is serverIndex (runtime)
        int playerSlot = findSlot(ActionTypes.PLAYER_OPTIONS, actionId);
        if (playerSlot > 0) {
            String highLevel = hasName(entityName)
                    ? "players.query().named(\"" + entityName + "\").nearest().interact(" + playerSlot + ");"
                    : "players.query().index(" + p1 + ").nearest().interact(" + playerSlot + ");";
            return highLevel + "\n" + raw;
        }

        // Component (57)
        if (actionId == ActionTypes.COMPONENT) {
            return formatComponentCode(actionId, p1, p2, p3, entityName, optionName, raw);
        }

        // Select component item (58) — "use item on item" in backpack
        if (actionId == ActionTypes.SELECT_COMPONENT_ITEM) {
            return formatComponentCode(actionId, p1, p2, p3, entityName, optionName, raw);
        }

        // Container action (1007) — same layout as COMPONENT (e.g. wood box "Empty" at bank)
        if (actionId == ActionTypes.CONTAINER_ACTION) {
            return formatComponentCode(actionId, p1, p2, p3, entityName, optionName, raw);
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
        String raw = "api.queueAction(new GameAction(" + actionId + ", " + p1 + ", " + p2 + ", " + p3 + "));";

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
            return formatComponentCode(actionId, p1, p2, p3, null, null, raw);
        }

        // Select component item (58)
        if (actionId == ActionTypes.SELECT_COMPONENT_ITEM) {
            return formatComponentCode(actionId, p1, p2, p3, null, null, raw);
        }

        // Container action (1007) — same layout as COMPONENT
        if (actionId == ActionTypes.CONTAINER_ACTION) {
            return formatComponentCode(actionId, p1, p2, p3, null, null, raw);
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
        return "api.queueAction(new GameAction(" + actionId + ", " + p1 + ", " + p2 + ", " + p3 + "));";
    }

    /**
     * Generates complete, working component interaction code.
     * <ul>
     *   <li>No sub-component (p2 == -1): uses queryComponents to find the component directly</li>
     *   <li>With sub-component (p2 >= 0): uses getComponentChildren to find the specific slot/child</li>
     *   <li>Action 58 (SELECT_COMPONENT_ITEM): same as above but queues action type 58 instead of 57</li>
     * </ul>
     */
    private static String formatComponentCode(int actionId, int p1, int p2, int p3,
                                               String entityName, String optionName, String raw) {
        int ifaceId = p3 >>> 16;
        int compId = p3 & 0xFFFF;

        // Build a descriptive comment: e.g. // "Withdraw-5" Shrimps iface:517 comp:201 sub:1 option:3
        StringBuilder commentParts = new StringBuilder("//");
        if (hasName(optionName)) commentParts.append(" \"").append(optionName).append("\"");
        if (hasName(entityName)) commentParts.append(" ").append(entityName);
        commentParts.append(" iface:").append(ifaceId)
                .append(" comp:").append(compId)
                .append(" sub:").append(p2)
                .append(" option:").append(p1);
        String comment = commentParts.toString();

        String query;
        if (p2 >= 0) {
            // Sub-component involved (bank slot, inventory slot, etc.)
            // Must use getComponentChildren to find the child with the correct subComponentId
            query = "api.getComponentChildren(" + ifaceId + ", " + compId + ").stream()"
                    + ".filter(c -> c.subComponentId() == " + p2 + ").findFirst()";
        } else {
            // No sub-component — find the component itself
            query = "api.queryComponents(ComponentFilter.builder().interfaceId(" + ifaceId + ").build())"
                    + ".stream().filter(c -> c.componentId() == " + compId + ").findFirst()";
        }

        String interact;
        if (actionId == ActionTypes.SELECT_COMPONENT_ITEM || actionId == ActionTypes.CONTAINER_ACTION) {
            // Action 58/1007 — must queue with the correct action type, not 57
            interact = "comp -> api.queueAction(new GameAction(" + actionId + ", " + p1
                    + ", comp.subComponentId(), ComponentHelper.componentHash(comp)))";
        } else if (p2 >= 0) {
            // Sub-component (bank slot, inventory slot, etc.) — interactComponent fails
            // because getComponentOptions returns empty for child components.
            // Use queueComponentAction which skips option lookup and uses the index directly.
            interact = "comp -> ComponentHelper.queueComponentAction(api, comp, " + p1 + ")";
        } else if (hasName(optionName)) {
            // No sub-component, has option name — interactComponent works here
            interact = "comp -> ComponentHelper.interactComponent(api, comp, \"" + optionName + "\")";
        } else {
            interact = "comp -> ComponentHelper.queueComponentAction(api, comp, " + p1 + ")";
        }

        String highLevel = query + ".ifPresent(" + interact + ");";
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
                              long timestamp, String entityName, String optionName,
                              String intentDescription, boolean backpackFull,
                              boolean animationEnded, int openInterfaceId) {
        /** Backward-compatible constructor without snapshot data. */
        public ActionEntry(int actionId, int param1, int param2, int param3,
                           long timestamp, String entityName, String optionName) {
            this(actionId, param1, param2, param3, timestamp, entityName, optionName,
                    null, false, false, -1);
        }
    }

    /**
     * Generates a complete, compilable BotScript from a list of recorded actions.
     * <p>
     * Produces a resilient state-machine script with:
     * <ul>
     *   <li>Pace-based antiban delays (ex-Gaussian, fatigue, momentum)</li>
     *   <li>Location guards that walk back if the player wanders off</li>
     *   <li>Interface guards that re-open UIs if closed</li>
     *   <li>Animation awareness (idle while chopping/fishing/etc.)</li>
     *   <li>Null safety on entity queries</li>
     *   <li>Loop detection (walk-back to start = automatic loop)</li>
     *   <li>Movement guard (wait if player is moving)</li>
     *   <li>Micro-break injection via {@code pace.breakCheck()}</li>
     * </ul>
     *
     * @param entries   the recorded actions in order
     * @param className the class name for the generated script
     * @param useNames  if true, generates name-based queries with guards (skeleton mode);
     *                  if false, uses raw GameAction calls with Pace delays (replay mode)
     * @return the {@code onLoop()} method (and helper methods if needed), ready to paste into an existing BotScript class
     */
    public static String generateScript(java.util.List<ActionEntry> entries, String className, boolean useNames) {
        boolean isLoop = useNames && !entries.isEmpty() && detectLoop(entries);
        int lastIdx = entries.size() - 1;

        StringBuilder sb = new StringBuilder();

        // ── Imports ──
        sb.append("import com.botwithus.bot.api.*;\n");
        sb.append("import com.botwithus.bot.api.antiban.Pace;\n");
        sb.append("import com.botwithus.bot.api.entities.*;\n");
        sb.append("import com.botwithus.bot.api.model.*;\n");
        sb.append("import com.botwithus.bot.api.inventory.*;\n");
        sb.append("import com.botwithus.bot.api.query.*;\n");
        sb.append("import com.botwithus.bot.api.util.Conditions;\n\n");

        // ── Setup hint ──
        sb.append("// Required fields: private ScriptContext ctx; private Pace pace; private Backpack backpack; private int step = 0;\n");
        sb.append("// In onStart(): this.ctx = ctx; this.pace = ctx.getPace(); this.backpack = new Backpack(ctx.getGameAPI());\n\n");

        // ── onLoop ──
        sb.append("    @Override\n");
        sb.append("    public int onLoop() {\n");
        sb.append("        GameAPI api = ctx.getGameAPI();\n");
        sb.append("        var player = api.getLocalPlayer();\n");
        sb.append("        pace.breakCheck();\n\n");
        sb.append("        if (player.isMoving()) return (int) pace.idle(\"walk\");\n\n");
        sb.append("        switch (step) {\n");

        for (int i = 0; i < entries.size(); i++) {
            ActionEntry e = entries.get(i);
            int aid = e.actionId();
            String paceCtx = classifyContext(aid);
            boolean isLast = (i == lastIdx);

            // Recorded delay to next action
            int delayMs = 600;
            if (i + 1 < entries.size()) {
                long delta = entries.get(i + 1).timestamp() - e.timestamp();
                delayMs = (int) Math.max(300, Math.min(delta, 10000));
            }
            boolean longDelay = delayMs > 4000;

            // Human-readable comment (prefer intent description when available)
            String comment = "";
            if (hasName(e.intentDescription())) {
                comment = " // Intent: " + e.intentDescription();
            } else if (hasName(e.entityName()) || hasName(e.optionName())) {
                comment = " // " + orEmpty(e.optionName())
                        + (hasName(e.entityName()) ? " -> " + e.entityName() : "");
            }

            String advance = (isLast && isLoop) ? "step = 0;" : "step++;";
            String paceDelay = longDelay
                    ? "return (int) pace.idle(\"" + paceCtx + "\");"
                    : "return (int) pace.delay(\"" + paceCtx + "\");";

            sb.append("            case ").append(i).append(" -> {").append(comment).append("\n");

            // Intent-driven backpack guards (high confidence only)
            String intentLower = hasName(e.intentDescription()) ? e.intentDescription().toLowerCase() : "";
            if (e.backpackFull() && intentLower.contains("banking")) {
                sb.append("                if (!backpack.isFull()) { step++; return (int) pace.delay(\"react\"); }\n");
            } else if (!e.backpackFull() && intentLower.contains("gathering")) {
                sb.append("                if (backpack.isFull()) { step++; return (int) pace.delay(\"react\"); }\n");
            }

            if (aid == ActionTypes.WALK) {
                // ── Walk step ──
                generateWalkStep(sb, e, advance);

            } else if (isComponentAction(aid)) {
                // ── Component/UI step ──
                generateComponentStep(sb, e, entries, i, useNames, advance, paceDelay);

            } else if (useNames && isEntityAction(aid)) {
                // ── Entity step (NPC, object, ground item, player) ──
                generateEntityStep(sb, e, entries, i, longDelay, paceCtx, advance, paceDelay);

            } else {
                // ── Other (dialogue, raw mode, etc.) ──
                generateOtherStep(sb, e, useNames, advance, paceDelay);
            }

            sb.append("            }\n");
        }

        sb.append("            default -> {\n");
        sb.append("                return -1; // Done — stop script\n");
        sb.append("            }\n");
        sb.append("        }\n");
        sb.append("    }\n\n");

        // ── distanceTo helper (only needed in name-based mode) ──
        if (useNames) {
            sb.append("    private static int distanceTo(LocalPlayer p, int x, int y) {\n");
            sb.append("        return Math.max(Math.abs(p.tileX() - x), Math.abs(p.tileY() - y));\n");
            sb.append("    }\n\n");
        }

        // Remove trailing newline
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\n') {
            sb.setLength(sb.length() - 1);
        }

        return sb.toString();
    }

    // ── Step generators ──────────────────────────────────────────────────

    private static void generateWalkStep(StringBuilder sb, ActionEntry e, String advance) {
        sb.append("                api.walkToAsync(").append(e.param2()).append(", ").append(e.param3()).append(");\n");
        sb.append("                Conditions.waitUntil(() -> !api.getLocalPlayer().isMoving(), 10000);\n");
        sb.append("                ").append(advance).append("\n");
        sb.append("                return (int) pace.delay(\"walk\");\n");
    }

    private static void generateComponentStep(StringBuilder sb, ActionEntry e,
                                               java.util.List<ActionEntry> entries, int index,
                                               boolean useNames, String advance, String paceDelay) {
        int ifaceId = e.param3() >>> 16;

        // Interface guard
        if (useNames) {
            int opener = findInterfaceOpener(entries, index);
            if (opener >= 0) {
                sb.append("                if (!api.isInterfaceOpen(").append(ifaceId)
                        .append(")) { step = ").append(opener).append("; return (int) pace.delay(\"react\"); }\n");
            } else {
                sb.append("                if (!api.isInterfaceOpen(").append(ifaceId)
                        .append(")) return (int) pace.idle(\"menu\");\n");
            }
        }

        // Component interaction code
        String code = resolveCode(e, useNames);
        sb.append("                ").append(code).append("\n");
        sb.append("                ").append(advance).append("\n");
        sb.append("                ").append(paceDelay).append("\n");
    }

    private static void generateEntityStep(StringBuilder sb, ActionEntry e,
                                            java.util.List<ActionEntry> entries, int index,
                                            boolean longDelay, String paceCtx,
                                            String advance, String paceDelay) {
        int[] loc = extractLocation(e, entries, index);

        // Location guard
        if (loc != null) {
            sb.append("                if (distanceTo(player, ").append(loc[0]).append(", ").append(loc[1]).append(") > 15) {\n");
            sb.append("                    api.walkToAsync(").append(loc[0]).append(", ").append(loc[1]).append(");\n");
            sb.append("                    return (int) pace.delay(\"walk\");\n");
            sb.append("                }\n");
        }

        // Animation guard for long delays
        if (longDelay) {
            sb.append("                if (player.animationId() != -1) return (int) pace.idle(\"").append(paceCtx).append("\");\n");
        }

        // Entity query + null guard + interact
        String[] parts = generateEntityParts(e);
        if (parts != null) {
            sb.append("                var target = ").append(parts[0]).append(";\n");
            if (longDelay) {
                // Long delay: null target means work is done → advance
                sb.append("                if (target == null) { ").append(advance).append(" return (int) pace.delay(\"react\"); }\n");
                sb.append("                target").append(parts[1]).append(";\n");
                sb.append("                ").append(paceDelay).append("\n");
            } else {
                // Short delay: null guard then immediate advance
                sb.append("                if (target == null) return (int) pace.idle(\"").append(paceCtx).append("\");\n");
                sb.append("                target").append(parts[1]).append(";\n");
                sb.append("                ").append(advance).append("\n");
                sb.append("                ").append(paceDelay).append("\n");
            }
        } else {
            // Fallback to raw code
            sb.append("                ").append(toRawCode(e.actionId(), e.param1(), e.param2(), e.param3())).append("\n");
            sb.append("                ").append(advance).append("\n");
            sb.append("                ").append(paceDelay).append("\n");
        }
    }

    private static void generateOtherStep(StringBuilder sb, ActionEntry e,
                                           boolean useNames, String advance, String paceDelay) {
        String code = resolveCode(e, useNames);
        sb.append("                ").append(code).append("\n");
        sb.append("                ").append(advance).append("\n");
        sb.append("                ").append(paceDelay).append("\n");
    }

    /** Resolves a single line of action code (high-level or raw). */
    private static String resolveCode(ActionEntry e, boolean useNames) {
        if (useNames) {
            String full = toCode(e.actionId(), e.param1(), e.param2(), e.param3(),
                    e.entityName(), e.optionName());
            int nl = full.indexOf('\n');
            String code = nl > 0 ? full.substring(0, nl) : full;
            if (code.startsWith("//")) {
                return toRawCode(e.actionId(), e.param1(), e.param2(), e.param3());
            }
            return code;
        }
        return toRawCode(e.actionId(), e.param1(), e.param2(), e.param3());
    }

    // ── Script generation helpers ────────────────────────────────────────

    /** Returns [queryExpr, interactSuffix] for entity actions, or null. */
    private static String[] generateEntityParts(ActionEntry e) {
        int aid = e.actionId();

        int npcSlot = findSlot(ActionTypes.NPC_OPTIONS, aid);
        if (npcSlot > 0) {
            String query = hasName(e.entityName())
                    ? "npcs.query().named(\"" + e.entityName() + "\").nearest()"
                    : "npcs.query().index(" + e.param1() + ").nearest()";
            String interact = hasName(e.optionName())
                    ? ".interact(\"" + e.optionName() + "\")"
                    : ".interact(" + npcSlot + ")";
            return new String[]{query, interact};
        }

        int objSlot = findSlot(ActionTypes.OBJECT_OPTIONS, aid);
        if (objSlot > 0) {
            String query = hasName(e.entityName())
                    ? "objects.query().named(\"" + e.entityName() + "\").nearest()"
                    : "objects.query().typeId(" + e.param1() + ").nearest()";
            String interact = hasName(e.optionName())
                    ? ".interact(\"" + e.optionName() + "\")"
                    : ".interact(" + objSlot + ")";
            return new String[]{query, interact};
        }

        int giSlot = findSlot(ActionTypes.GROUND_ITEM_OPTIONS, aid);
        if (giSlot > 0) {
            String query = hasName(e.entityName())
                    ? "groundItems.query().named(\"" + e.entityName() + "\").nearest()"
                    : "groundItems.query().itemId(" + e.param1() + ").nearest()";
            String interact = hasName(e.optionName())
                    ? ".interact(\"" + e.optionName() + "\")"
                    : ".interact(" + giSlot + ")";
            return new String[]{query, interact};
        }

        int playerSlot = findSlot(ActionTypes.PLAYER_OPTIONS, aid);
        if (playerSlot > 0) {
            String query = hasName(e.entityName())
                    ? "players.query().named(\"" + e.entityName() + "\").nearest()"
                    : "players.query().index(" + e.param1() + ").nearest()";
            String interact = ".interact(" + playerSlot + ")";
            return new String[]{query, interact};
        }

        return null;
    }

    /** Maps action type to Pace context string. */
    private static String classifyContext(int actionId) {
        if (actionId == ActionTypes.WALK) return "walk";
        if (findSlot(ActionTypes.PLAYER_OPTIONS, actionId) > 0) return "combat";
        if (actionId == ActionTypes.COMPONENT || actionId == ActionTypes.SELECT_COMPONENT_ITEM
                || actionId == ActionTypes.CONTAINER_ACTION || actionId == ActionTypes.DIALOGUE) return "menu";
        return "gather";
    }

    /** Extracts tile coordinates [x, y] for an action, or null if unavailable. */
    private static int[] extractLocation(ActionEntry entry, java.util.List<ActionEntry> entries, int index) {
        int aid = entry.actionId();
        // Objects and ground items have tile coords directly in p2, p3
        if (findSlot(ActionTypes.OBJECT_OPTIONS, aid) > 0
                || findSlot(ActionTypes.GROUND_ITEM_OPTIONS, aid) > 0
                || aid == ActionTypes.WALK
                || aid == ActionTypes.SELECT_OBJECT
                || aid == ActionTypes.SELECT_GROUND_ITEM) {
            return new int[]{entry.param2(), entry.param3()};
        }
        // NPCs and players: use preceding WALK destination as location hint
        if (findSlot(ActionTypes.NPC_OPTIONS, aid) > 0
                || findSlot(ActionTypes.PLAYER_OPTIONS, aid) > 0
                || aid == ActionTypes.SELECT_NPC) {
            for (int j = index - 1; j >= 0; j--) {
                if (entries.get(j).actionId() == ActionTypes.WALK) {
                    return new int[]{entries.get(j).param2(), entries.get(j).param3()};
                }
            }
        }
        return null;
    }

    /** Detects if the action sequence loops back to the starting area. */
    private static boolean detectLoop(java.util.List<ActionEntry> entries) {
        int[] firstLoc = null;
        for (int i = 0; i < entries.size(); i++) {
            firstLoc = extractLocation(entries.get(i), entries, i);
            if (firstLoc != null) break;
        }
        if (firstLoc == null) return false;
        for (int i = entries.size() - 1; i >= 0; i--) {
            if (entries.get(i).actionId() == ActionTypes.WALK) {
                int dx = Math.abs(entries.get(i).param2() - firstLoc[0]);
                int dy = Math.abs(entries.get(i).param3() - firstLoc[1]);
                return Math.max(dx, dy) <= 20;
            }
        }
        return false;
    }

    /** Finds the step that likely opens a given interface (first non-component step before current). */
    private static int findInterfaceOpener(java.util.List<ActionEntry> entries, int currentIndex) {
        for (int j = currentIndex - 1; j >= 0; j--) {
            int aid = entries.get(j).actionId();
            if (!isComponentAction(aid)) return j;
        }
        return -1;
    }

    private static boolean isComponentAction(int actionId) {
        return actionId == ActionTypes.COMPONENT
                || actionId == ActionTypes.SELECT_COMPONENT_ITEM
                || actionId == ActionTypes.CONTAINER_ACTION;
    }

    private static boolean isEntityAction(int actionId) {
        return findSlot(ActionTypes.NPC_OPTIONS, actionId) > 0
                || findSlot(ActionTypes.OBJECT_OPTIONS, actionId) > 0
                || findSlot(ActionTypes.GROUND_ITEM_OPTIONS, actionId) > 0
                || findSlot(ActionTypes.PLAYER_OPTIONS, actionId) > 0;
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
