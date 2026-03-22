package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import com.botwithus.bot.api.model.MiniMenuEntry;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Matches actions against cached mini menu entries to find option text and item names.
 */
final class MiniMenuMatcher {

    private static final Logger log = LoggerFactory.getLogger(MiniMenuMatcher.class);

    private final XapiState state;
    private final NameResolver nameResolver;

    MiniMenuMatcher(XapiState state, NameResolver nameResolver) {
        this.state = state;
        this.nameResolver = nameResolver;
    }

    /**
     * Matches an action against the cached mini menu to find option text and item name.
     * Searches the current menu first, then recent menu snapshots (last 500).
     * Returns {optionText, itemName} or null if no match.
     */
    String[] matchMiniMenu(int actionId, int p1, int p2, int p3) {
        try {
            // 1. Check current mini menu (exact match)
            List<MiniMenuEntry> menu = state.lastMiniMenu;
            String[] result = searchMenuEntries(menu, actionId, p1, p2, p3);
            if (result != null) return result;

            // 2. Search recent menu snapshots (exact match, most recent first)
            for (int i = state.menuLog.size() - 1; i >= 0 && i >= state.menuLog.size() - 50; i--) {
                MenuSnapshot snap = state.menuLog.get(i);
                result = searchMenuEntries(snap.entries(), actionId, p1, p2, p3);
                if (result != null) return result;
            }

            // 3. Relaxed container match: same (actionId, p1, p3) but ANY slot (p2).
            //    Within a container, all slots share the same option labels (Withdraw-1, Deposit-All, etc.)
            //    so we can borrow the optionText from a different slot in the same container.
            if (NameResolver.isComponentAction(actionId)) {
                // Try current menu first
                result = searchMenuEntriesRelaxed(menu, actionId, p1, p3);
                if (result != null) return result;

                // Then recent snapshots
                for (int i = state.menuLog.size() - 1; i >= 0 && i >= state.menuLog.size() - 50; i--) {
                    MenuSnapshot snap = state.menuLog.get(i);
                    result = searchMenuEntriesRelaxed(snap.entries(), actionId, p1, p3);
                    if (result != null) return result;
                }

                // Also search state.preResolvedActions for relaxed match (same actionId, p1, p3, any p2)
                for (var entry : state.preResolvedActions.entrySet()) {
                    var key = entry.getKey();
                    if (key.actionId() == actionId && key.p1() == p1 && key.p3() == p3) {
                        String[] val = entry.getValue();
                        if (val != null && val[1] != null && !val[1].isEmpty()) {
                            // Return option text only (entity name doesn't transfer across slots)
                            return new String[]{val[1], null};
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private String[] searchMenuEntries(List<MiniMenuEntry> entries, int actionId, int p1, int p2, int p3) {
        if (entries == null || entries.isEmpty()) return null;
        for (MiniMenuEntry entry : entries) {
            if (entry.actionId() == actionId
                    && entry.param1() == p1
                    && entry.param2() == p2
                    && entry.param3() == p3) {
                String optionText = entry.optionText();
                String itemName = null;
                if (entry.itemId() > 0) {
                    itemName = nameResolver.lookupItemName(state.ctx.getGameAPI(), entry.itemId());
                }
                return new String[]{optionText, itemName};
            }
        }
        return null;
    }

    /**
     * Relaxed container search: matches (actionId, p1, p3) ignoring p2 (slot index).
     * All items in a container share the same option labels, so the optionText from any slot applies.
     * Returns {optionText, null} -- entity name is NOT transferred (it's slot-specific).
     */
    private String[] searchMenuEntriesRelaxed(List<MiniMenuEntry> entries, int actionId, int p1, int p3) {
        if (entries == null || entries.isEmpty()) return null;
        for (MiniMenuEntry entry : entries) {
            if (entry.actionId() == actionId
                    && entry.param1() == p1
                    && entry.param3() == p3) {
                String optionText = entry.optionText();
                if (optionText != null && !optionText.isEmpty()) {
                    return new String[]{optionText, null};
                }
            }
        }
        return null;
    }
}
