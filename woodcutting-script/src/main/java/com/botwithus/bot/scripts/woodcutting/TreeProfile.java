package com.botwithus.bot.scripts.woodcutting;

import com.botwithus.bot.api.entities.SceneObject;
import com.botwithus.bot.api.inventory.WoodBox;

import java.util.List;
import java.util.Set;

public final class TreeProfile {

    private final String id;
    private final String displayName;
    private final String objectName;
    private final String primaryAction;
    private final List<String> allActions;
    private final int requiredLevel;
    private final TreeMode mode;
    private final Set<Integer> activeIds;
    private final Set<Integer> helperIds;
    private final Set<Integer> ignoreIds;
    private final String productName;
    private final Integer logItemId;
    private final WoodBox.LogType woodBoxLogType;
    private final String requirementNote;
    private final String behaviourSummary;
    private final List<HotspotProfile> hotspots;
    private final String defaultHotspotId;

    public TreeProfile(
            String id,
            String displayName,
            String objectName,
            String primaryAction,
            List<String> allActions,
            int requiredLevel,
            TreeMode mode,
            Set<Integer> activeIds,
            Set<Integer> helperIds,
            Set<Integer> ignoreIds,
            String productName,
            Integer logItemId,
            WoodBox.LogType woodBoxLogType,
            String requirementNote,
            String behaviourSummary,
            List<HotspotProfile> hotspots,
            String defaultHotspotId
    ) {
        this.id = id;
        this.displayName = displayName;
        this.objectName = objectName;
        this.primaryAction = primaryAction;
        this.allActions = allActions == null ? List.of(primaryAction) : List.copyOf(allActions);
        this.requiredLevel = requiredLevel;
        this.mode = mode;
        this.activeIds = activeIds == null ? Set.of() : Set.copyOf(activeIds);
        this.helperIds = helperIds == null ? Set.of() : Set.copyOf(helperIds);
        this.ignoreIds = ignoreIds == null ? Set.of() : Set.copyOf(ignoreIds);
        this.productName = productName == null ? "" : productName;
        this.logItemId = logItemId;
        this.woodBoxLogType = woodBoxLogType;
        this.requirementNote = requirementNote == null ? "" : requirementNote;
        this.behaviourSummary = behaviourSummary == null ? "" : behaviourSummary;
        this.hotspots = hotspots == null ? List.of() : List.copyOf(hotspots);
        this.defaultHotspotId = defaultHotspotId;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    public String objectName() {
        return objectName;
    }

    public String primaryAction() {
        return primaryAction;
    }

    public List<String> allActions() {
        return allActions;
    }

    public int requiredLevel() {
        return requiredLevel;
    }

    public TreeMode mode() {
        return mode;
    }

    public Set<Integer> activeIds() {
        return activeIds;
    }

    public Set<Integer> helperIds() {
        return helperIds;
    }

    public Set<Integer> ignoreIds() {
        return ignoreIds;
    }

    public Integer logItemId() {
        return logItemId;
    }

    public String productName() {
        return productName;
    }

    public WoodBox.LogType woodBoxLogType() {
        return woodBoxLogType;
    }

    public String requirementNote() {
        return requirementNote;
    }

    public String behaviourSummary() {
        return behaviourSummary;
    }

    public List<HotspotProfile> hotspots() {
        return hotspots;
    }

    public String defaultHotspotId() {
        return defaultHotspotId;
    }

    public boolean supportsWoodBox() {
        return woodBoxLogType != null;
    }

    public boolean hasCollectibleProduct() {
        return logItemId != null;
    }

    public HotspotProfile defaultHotspot() {
        if (hotspots.isEmpty()) {
            return null;
        }
        if (defaultHotspotId == null || defaultHotspotId.isBlank()) {
            return hotspots.getFirst();
        }
        return hotspots.stream()
                .filter(h -> defaultHotspotId.equals(h.id()))
                .findFirst()
                .orElse(hotspots.getFirst());
    }

    public HotspotProfile hotspotById(String hotspotId) {
        if (hotspotId == null || hotspotId.isBlank()) {
            return defaultHotspot();
        }
        return hotspots.stream()
                .filter(h -> hotspotId.equals(h.id()))
                .findFirst()
                .orElse(defaultHotspot());
    }

    public boolean isActiveTree(SceneObject object) {
        if (object == null) {
            return false;
        }
        if (ignoreIds.contains(object.typeId())) {
            return false;
        }
        boolean actionMatch = allActions.stream().anyMatch(object::hasOption);
        if (!actionMatch) {
            return false;
        }
        return activeIds.isEmpty() || activeIds.contains(object.typeId());
    }

    public boolean isHelperObject(SceneObject object) {
        return object != null && helperIds.contains(object.typeId());
    }
}
