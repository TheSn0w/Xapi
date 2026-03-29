package com.botwithus.bot.scripts.woodcutting;

import java.util.List;

public final class WoodcuttingConfig {

    private final List<TreeProfile> profiles = WoodcuttingProfiles.all();
    private volatile String selectedTreeId = "willow";
    private volatile String selectedHotspotId = "willow_draynor";

    public List<TreeProfile> profiles() {
        return profiles;
    }

    public TreeProfile selectedTree() {
        return profiles.stream()
                .filter(profile -> profile.id().equals(selectedTreeId))
                .findFirst()
                .orElse(profiles.getFirst());
    }

    public HotspotProfile selectedHotspot() {
        TreeProfile profile = selectedTree();
        return profile.hotspotById(selectedHotspotId);
    }

    public void selectTree(String treeId) {
        TreeProfile profile = profiles.stream()
                .filter(candidate -> candidate.id().equals(treeId))
                .findFirst()
                .orElse(profiles.getFirst());
        this.selectedTreeId = profile.id();
        HotspotProfile defaultHotspot = profile.defaultHotspot();
        this.selectedHotspotId = defaultHotspot != null ? defaultHotspot.id() : "";
    }

    public void selectHotspot(String hotspotId) {
        HotspotProfile hotspot = selectedTree().hotspotById(hotspotId);
        this.selectedHotspotId = hotspot != null ? hotspot.id() : "";
    }

    public String selectedTreeId() {
        return selectedTreeId;
    }

    public String selectedHotspotId() {
        return selectedHotspotId;
    }
}
