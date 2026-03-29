package com.botwithus.bot.scripts.woodcutting;

import java.util.ArrayList;
import java.util.List;

public final class HotspotProfile {

    private final String id;
    private final String label;
    private final String hotspotType;
    private final TileAnchor treeAnchor;
    private final TileAnchor travelAnchor;
    private final int radius;
    private final InventoryMode inventoryMode;
    private final TileAnchor bankAnchor;
    private final List<String> bankObjectNames;
    private final List<Integer> bankObjectIds;
    private final String bankAction;
    private final TileAnchor depositAnchor;
    private final List<String> depositObjectNames;
    private final List<Integer> depositObjectIds;
    private final String depositAction;
    private final List<TileAnchor> routeAnchors;
    private final String note;
    private final String requirementNote;

    public HotspotProfile(
            String id,
            String label,
            String hotspotType,
            TileAnchor treeAnchor,
            TileAnchor travelAnchor,
            int radius,
            InventoryMode inventoryMode,
            TileAnchor bankAnchor,
            List<String> bankObjectNames,
            List<Integer> bankObjectIds,
            String bankAction,
            TileAnchor depositAnchor,
            List<String> depositObjectNames,
            List<Integer> depositObjectIds,
            String depositAction,
            List<TileAnchor> routeAnchors,
            String note,
            String requirementNote
    ) {
        this.id = id;
        this.label = label;
        this.hotspotType = hotspotType;
        this.treeAnchor = treeAnchor;
        this.travelAnchor = travelAnchor != null ? travelAnchor : treeAnchor;
        this.radius = radius;
        this.inventoryMode = inventoryMode;
        this.bankAnchor = bankAnchor;
        this.bankObjectNames = bankObjectNames == null ? List.of() : List.copyOf(bankObjectNames);
        this.bankObjectIds = bankObjectIds == null ? List.of() : List.copyOf(bankObjectIds);
        this.bankAction = bankAction == null ? "Bank" : bankAction;
        this.depositAnchor = depositAnchor;
        this.depositObjectNames = depositObjectNames == null ? List.of() : List.copyOf(depositObjectNames);
        this.depositObjectIds = depositObjectIds == null ? List.of() : List.copyOf(depositObjectIds);
        this.depositAction = depositAction == null ? "Deposit" : depositAction;
        this.routeAnchors = routeAnchors == null ? List.of() : List.copyOf(routeAnchors);
        this.note = note == null ? "" : note;
        this.requirementNote = requirementNote == null ? "" : requirementNote;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public String hotspotType() {
        return hotspotType;
    }

    public TileAnchor treeAnchor() {
        return treeAnchor;
    }

    public TileAnchor travelAnchor() {
        return travelAnchor;
    }

    public int radius() {
        return radius;
    }

    public InventoryMode inventoryMode() {
        return inventoryMode;
    }

    public TileAnchor bankAnchor() {
        return bankAnchor;
    }

    public List<String> bankObjectNames() {
        return bankObjectNames;
    }

    public List<Integer> bankObjectIds() {
        return bankObjectIds;
    }

    public String bankAction() {
        return bankAction;
    }

    public TileAnchor depositAnchor() {
        return depositAnchor;
    }

    public List<String> depositObjectNames() {
        return depositObjectNames;
    }

    public List<Integer> depositObjectIds() {
        return depositObjectIds;
    }

    public String depositAction() {
        return depositAction;
    }

    public List<TileAnchor> routeAnchors() {
        return routeAnchors;
    }

    public String note() {
        return note;
    }

    public String requirementNote() {
        return requirementNote;
    }

    public boolean hasRoute() {
        return !routeAnchors.isEmpty();
    }

    public boolean hasBankTarget() {
        return bankAnchor != null && inventoryMode == InventoryMode.BANK;
    }

    public boolean hasDepositTarget() {
        return depositAnchor != null && inventoryMode == InventoryMode.DEPOSIT_BOX;
    }

    public String inventorySummary() {
        StringBuilder summary = new StringBuilder(inventoryMode.displayName());
        if (hotspotType != null && !hotspotType.isBlank()) {
            summary.append(" / ").append(hotspotType);
        }
        return summary.toString();
    }

    public static List<String> defaultBankNames() {
        return List.of("Counter", "Bank booth", "Bank chest");
    }

    public static List<Integer> defaultBankIds() {
        return List.of(2012, 782, 2213, 5276, 11758, 25808, 34752, 42377);
    }

    public static List<String> defaultDepositNames() {
        return List.of("Deposit box", "Bank deposit box", "Bank chest");
    }

    public static List<Integer> defaultDepositIds() {
        return List.of(2045, 2132, 2133, 6836, 9398, 24995, 25937, 32924, 70512);
    }

    public static List<TileAnchor> route(TileAnchor... anchors) {
        List<TileAnchor> list = new ArrayList<>();
        if (anchors != null) {
            for (TileAnchor anchor : anchors) {
                if (anchor != null) {
                    list.add(anchor);
                }
            }
        }
        return list;
    }
}
