package com.xapi.debugger;

import static com.xapi.debugger.XapiData.*;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.inventory.ActionTypes;
import com.botwithus.bot.api.model.GameAction;
import com.botwithus.bot.api.query.ComponentFilter;
import com.botwithus.bot.api.query.EntityFilter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles replay of recorded action sequences.
 */
final class ReplayController {

    private static final Logger log = LoggerFactory.getLogger(ReplayController.class);

    private final XapiState state;
    private volatile long replayNextTime;

    ReplayController(XapiState state) {
        this.state = state;
    }

    void setReplayNextTime(long time) {
        this.replayNextTime = time;
    }

    void doReplayStep(GameAPI api) {
        if (state.replayIndex >= state.actionLog.size()) {
            state.replaying = false;
            return;
        }
        long now = System.currentTimeMillis();
        if (now < replayNextTime) return;

        LogEntry entry = state.actionLog.get(state.replayIndex);

        // Validate target still exists before queueing
        String skipReason = validateReplayAction(api, entry);
        if (skipReason != null) {
            log.warn("Replay step {} skipped -- {}: {} (action {})",
                    state.replayIndex, skipReason, entry.optionName(), ActionTypes.nameOf(entry.actionId()));
        } else {
            try {
                api.queueAction(new GameAction(entry.actionId(), entry.param1(), entry.param2(), entry.param3()));
            } catch (Exception e) {
                log.debug("Replay action failed at step {}: {}", state.replayIndex, e.getMessage());
            }
        }

        state.replayIndex++;
        if (state.replayIndex < state.actionLog.size()) {
            long delta = state.actionLog.get(state.replayIndex).timestamp() - entry.timestamp();
            delta = (long) (delta / state.replaySpeed);
            delta = Math.max(100, Math.min(delta, 15000));
            replayNextTime = now + delta;
        } else {
            state.replaying = false;
        }
    }

    /** Validates a replay action target. Returns null if valid, or a skip reason string. */
    private String validateReplayAction(GameAPI api, LogEntry entry) {
        try {
            int actionId = entry.actionId();

            // Component actions -- check interface is open and visible
            if (actionId == ActionTypes.COMPONENT || actionId == ActionTypes.SELECT_COMPONENT_ITEM
                    || actionId == ActionTypes.CONTAINER_ACTION) {
                int ifaceId = entry.param3() >>> 16;
                if (!api.isInterfaceOpen(ifaceId)) {
                    return "interface " + ifaceId + " not open";
                }
                try {
                    var visible = api.queryComponents(
                            ComponentFilter.builder().interfaceId(ifaceId).visibleOnly(true).maxResults(1).build());
                    if (visible == null || visible.isEmpty()) {
                        return "interface " + ifaceId + " hidden";
                    }
                } catch (Exception ignored) {}
            }

            // NPC actions -- check NPC exists
            int npcSlot = NameResolver.findSlot(ActionTypes.NPC_OPTIONS, actionId);
            if (npcSlot > 0) {
                var nearbyNpcList = api.queryEntities(EntityFilter.builder().type("npc")
                        .visibleOnly(true).maxResults(500).build());
                boolean found = nearbyNpcList != null && nearbyNpcList.stream().anyMatch(e -> e.serverIndex() == entry.param1());
                if (!found) return "NPC not found (serverIndex=" + entry.param1() + ")";
            }

            // Object actions -- check object exists
            int objSlot = NameResolver.findSlot(ActionTypes.OBJECT_OPTIONS, actionId);
            if (objSlot > 0) {
                var objs = api.queryEntities(EntityFilter.builder().type("location")
                        .visibleOnly(true).maxResults(200).build());
                boolean found = objs != null && objs.stream().anyMatch(e -> e.typeId() == entry.param1());
                if (!found) return "Object not found (typeId=" + entry.param1() + ")";
            }
        } catch (Exception e) {
            // Validation failed -- don't skip, just proceed with the action
            log.debug("Replay validation error at step {}: {}", state.replayIndex, e.getMessage());
        }
        return null; // Valid
    }
}
