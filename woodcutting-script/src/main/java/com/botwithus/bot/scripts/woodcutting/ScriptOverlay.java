package com.botwithus.bot.scripts.woodcutting;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class ScriptOverlay {

    private static final double WIDTH = 340;
    private static final double HEIGHT = 520;
    private static final double INDICATOR_RADIUS = 4.0;
    private static final Path POS_FILE = Path.of("scripts", "overlay-pos.txt");

    private static final Color GREEN = Color.web("#72dc8a");
    private static final Color GOLD = Color.web("#f0c15c");
    private static final Color CYAN = Color.web("#61d0e8");
    private static final Color ORANGE = Color.web("#f28d52");
    private static final Color RED = Color.web("#ee6a5f");
    private static final Color VIOLET = Color.web("#b892ff");
    private static final Color HOLLOW = Color.web("#5a5248");
    private static final Color TEXT = Color.web("#f4efe4");
    private static final Color MUTED = Color.web("#a89f90");

    private final WoodcuttingContext wctx;
    private Stage stage;
    private Timeline timeline;

    private double offsetX = Double.NaN;
    private double offsetY = Double.NaN;
    private volatile boolean dragging = false;

    private TreeItem<NodeData> rootItem;
    private TreeItem<NodeData> taskItem;
    private TreeItem<NodeData> choppingItem;
    private TreeItem<NodeData> fillWoodboxItem;
    private TreeItem<NodeData> bankingItem;
    private TreeItem<NodeData> walkingItem;
    private TreeItem<NodeData> delayItem;
    private TreeItem<NodeData> delayContextItem;
    private TreeItem<NodeData> breakItem;
    private TreeItem<NodeData> breakStatusItem;
    private TreeItem<NodeData> breakTimerItem;
    private TreeItem<NodeData> statsItem;
    private TreeItem<NodeData> logsItem;
    private TreeItem<NodeData> rateItem;
    private TreeItem<NodeData> woodboxItem;
    private TreeItem<NodeData> bankTripsItem;
    private TreeItem<NodeData> profileItem;
    private TreeItem<NodeData> inventoryItem;
    private TreeItem<NodeData> antibanItem;
    private TreeItem<NodeData> attentionItem;
    private TreeItem<NodeData> phaseItem;
    private TreeItem<NodeData> fatigueItem;
    private TreeItem<NodeData> sessionItem;
    private TreeItem<NodeData> lastQuirkItem;

    public ScriptOverlay(WoodcuttingContext wctx) {
        this.wctx = wctx;
    }

    public void show() {
        ensureFxRunning();
        Platform.runLater(this::buildAndShow);
    }

    public void close() {
        Platform.runLater(() -> {
            if (timeline != null) {
                timeline.stop();
                timeline = null;
            }
            if (stage != null) {
                savePosition(offsetX, offsetY);
                stage.close();
                stage = null;
            }
        });
    }

    private void buildAndShow() {
        stage = new Stage(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.setTitle("Woodcutting");

        rootItem = node("Woodcutting Atlas", true, GOLD);
        rootItem.setExpanded(true);

        taskItem = node("Tasks", false, GOLD);
        taskItem.setExpanded(true);
        choppingItem = leaf("Chopping", false, GREEN);
        fillWoodboxItem = leaf("Filling wood box", false, CYAN);
        bankingItem = leaf("Banking", false, GOLD);
        walkingItem = leaf("Walking to trees", false, CYAN);
        taskItem.getChildren().addAll(choppingItem, fillWoodboxItem, bankingItem, walkingItem);

        delayItem = node("Delay", false, CYAN);
        delayItem.setExpanded(true);
        delayContextItem = leaf("None", false, CYAN);
        delayItem.getChildren().add(delayContextItem);

        breakItem = node("Break", false, ORANGE);
        breakItem.setExpanded(true);
        breakStatusItem = leaf("None", false, ORANGE);
        breakTimerItem = leaf("", false, ORANGE);
        breakItem.getChildren().add(breakStatusItem);

        statsItem = node("Stats", true, GREEN);
        statsItem.setExpanded(true);
        logsItem = leaf("Collected: 0", false, GREEN);
        rateItem = leaf("Rate: 0/hr", false, GREEN);
        woodboxItem = leaf("Wood box: 0 / 0", false, GREEN);
        bankTripsItem = leaf("Bank trips: 0", false, CYAN);
        profileItem = leaf("Profile: -", true, GOLD);
        inventoryItem = leaf("Inventory: -", true, CYAN);
        statsItem.getChildren().addAll(logsItem, rateItem, woodboxItem, bankTripsItem, profileItem, inventoryItem);

        antibanItem = node("Antiban", true, VIOLET);
        antibanItem.setExpanded(true);
        attentionItem = leaf("Attention: Focused", true, GREEN);
        phaseItem = leaf("Phase: warmup", false, CYAN);
        fatigueItem = leaf("Fatigue: 0.0%", false, ORANGE);
        sessionItem = leaf("Session: 0m 0s", false, GOLD);
        lastQuirkItem = leaf("Last quirk: None", false, VIOLET);
        antibanItem.getChildren().addAll(attentionItem, phaseItem, fatigueItem, sessionItem, lastQuirkItem);

        rootItem.getChildren().addAll(taskItem, delayItem, breakItem, statsItem, antibanItem);

        TreeView<NodeData> treeView = new TreeView<>(rootItem);
        treeView.setShowRoot(true);
        treeView.setCellFactory(tv -> new IndicatorTreeCell());
        treeView.setStyle(
                "-fx-background-color: transparent;" +
                        "-fx-control-inner-background: transparent;" +
                        "-fx-focus-color: transparent;" +
                        "-fx-faint-focus-color: transparent;"
        );
        treeView.setFocusTraversable(false);
        VBox.setVgrow(treeView, Priority.ALWAYS);

        Label title = styledLabel("Woodcutting Atlas", 14, true, GOLD);
        Label subtitle = styledLabel("Tree profile + hotspot telemetry", 10, false, MUTED);
        Label close = styledLabel("x", 12, false, MUTED);
        close.setOnMouseEntered(event -> close.setTextFill(RED));
        close.setOnMouseExited(event -> close.setTextFill(MUTED));
        close.setOnMouseClicked(event -> close());

        VBox titleBlock = new VBox(1, title, subtitle);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox titleBar = new HBox(8, titleBlock, spacer, close);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(8, 10, 6, 10));
        titleBar.setStyle(
                "-fx-background-color: linear-gradient(to right, rgba(58,42,25,0.95), rgba(24,54,60,0.92));" +
                        "-fx-background-radius: 12 12 0 0;"
        );

        final double[] dragOffset = new double[2];
        titleBar.setOnMousePressed(event -> {
            dragging = true;
            dragOffset[0] = event.getScreenX() - stage.getX();
            dragOffset[1] = event.getScreenY() - stage.getY();
        });
        titleBar.setOnMouseDragged(event -> {
            stage.setX(event.getScreenX() - dragOffset[0]);
            stage.setY(event.getScreenY() - dragOffset[1]);
        });
        titleBar.setOnMouseReleased(event -> {
            offsetX = stage.getX() - wctx.gameWindowX;
            offsetY = stage.getY() - wctx.gameWindowY;
            savePosition(offsetX, offsetY);
            dragging = false;
        });

        Label footer = styledLabel("Warm overlay styling, same task roles", 9, false, MUTED);
        HBox footerBar = new HBox(footer);
        footerBar.setAlignment(Pos.CENTER_LEFT);
        footerBar.setPadding(new Insets(4, 10, 8, 10));

        VBox root = new VBox(0, titleBar, treeView, footerBar);
        root.setPadding(new Insets(0));
        root.setStyle(
                "-fx-background-color: linear-gradient(to bottom, rgba(22,18,14,0.96), rgba(12,25,29,0.94));" +
                        "-fx-background-radius: 12;" +
                        "-fx-border-color: rgba(240,193,92,0.55);" +
                        "-fx-border-width: 1;" +
                        "-fx-border-radius: 12;"
        );

        Scene scene = new Scene(root, WIDTH, HEIGHT);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.show();

        double[] savedOffset = loadPosition();
        if (savedOffset != null) {
            offsetX = savedOffset[0];
            offsetY = savedOffset[1];
        }
        var screen = Screen.getPrimary().getVisualBounds();
        stage.setX(screen.getMaxX() - WIDTH - 20);
        stage.setY(50);

        timeline = new Timeline(new KeyFrame(Duration.millis(250), event -> update()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    private void update() {
        if (wctx == null || stage == null) {
            return;
        }

        int gwX = wctx.gameWindowX;
        int gwY = wctx.gameWindowY;
        int gwW = wctx.gameWindowWidth;

        if (gwW > 0 && !dragging) {
            if (Double.isNaN(offsetX)) {
                offsetX = gwW - WIDTH - 12;
                offsetY = 42;
                savePosition(offsetX, offsetY);
            }
            stage.setX(gwX + offsetX);
            stage.setY(gwY + offsetY);
        }

        String task = wctx.currentTaskName;
        boolean breaking = wctx.onBreak;

        rootItem.getValue().active = !breaking;
        rootItem.getValue().color = breaking ? ORANGE : GOLD;

        setActive(choppingItem, "Chopping".equals(task) && !breaking, GREEN);
        setActive(fillWoodboxItem, "Filling wood box".equals(task) && !breaking, CYAN);
        setActive(bankingItem, "Banking".equals(task) && !breaking, GOLD);
        setActive(walkingItem, "Walking to trees".equals(task) && !breaking, CYAN);

        boolean delaying = wctx.animationId == -1 && !wctx.playerMoving && !breaking;
        delayItem.getValue().active = delaying;
        delayItem.getValue().color = delaying ? CYAN : null;
        delayContextItem.getValue().text = delaying ? delayLabel(wctx.delayContext) : "None";
        delayContextItem.getValue().active = delaying;
        delayContextItem.getValue().color = delaying ? CYAN : null;

        breakItem.getValue().active = breaking;
        breakItem.getValue().color = breaking ? ORANGE : null;
        if (breaking) {
            breakStatusItem.getValue().text = wctx.breakLabel == null ? "Taking a break" : wctx.breakLabel;
            breakStatusItem.getValue().active = true;
            breakStatusItem.getValue().color = ORANGE;

            long remaining = wctx.breakRemainingMs;
            if (remaining > 0) {
                long seconds = remaining / 1000;
                breakTimerItem.getValue().text = String.format("Resuming in %d:%02d", seconds / 60, seconds % 60);
            } else {
                breakTimerItem.getValue().text = "Resuming soon";
            }
            breakTimerItem.getValue().active = true;
            breakTimerItem.getValue().color = ORANGE;
            if (!breakItem.getChildren().contains(breakTimerItem)) {
                breakItem.getChildren().add(breakTimerItem);
            }
        } else {
            breakStatusItem.getValue().text = "None";
            breakStatusItem.getValue().active = false;
            breakItem.getChildren().remove(breakTimerItem);
        }

        long elapsed = System.currentTimeMillis() - wctx.startTime;
        double hours = elapsed / 3_600_000.0;
        int rate = hours > 0.001 ? (int) (wctx.logsChopped / hours) : 0;

        logsItem.getValue().text = "Collected: " + wctx.logsChopped;
        logsItem.getValue().active = wctx.logsChopped > 0;
        logsItem.getValue().color = GREEN;

        rateItem.getValue().text = "Rate: " + rate + "/hr";
        rateItem.getValue().active = rate > 0;
        rateItem.getValue().color = rate > 0 ? GREEN : null;

        if (wctx.woodBoxUnsupported) {
            woodboxItem.getValue().text = "Wood box: unsupported (will bank)";
            woodboxItem.getValue().active = true;
            woodboxItem.getValue().color = ORANGE;
        } else {
            woodboxItem.getValue().text = "Wood box: " + wctx.woodboxStored + " / " + wctx.woodboxCapacity;
            woodboxItem.getValue().active = wctx.hasWoodBox;
            woodboxItem.getValue().color = wctx.hasWoodBox ? GREEN : null;
        }

        bankTripsItem.getValue().text = "Bank trips: " + wctx.bankTrips;
        bankTripsItem.getValue().active = wctx.bankTrips > 0;
        bankTripsItem.getValue().color = wctx.bankTrips > 0 ? CYAN : null;

        profileItem.getValue().text = "Profile: " + wctx.selectedTreeName + " / " + wctx.selectedHotspotName;
        profileItem.getValue().active = true;
        profileItem.getValue().color = GOLD;

        inventoryItem.getValue().text = "Inventory: " + wctx.inventoryModeLabel + " / " + wctx.modeLabel;
        inventoryItem.getValue().active = true;
        inventoryItem.getValue().color = CYAN;

        attentionItem.getValue().text = "Attention: " + wctx.attentionState;
        attentionItem.getValue().active = true;
        attentionItem.getValue().color = switch (wctx.attentionState) {
            case "Focused" -> GREEN;
            case "Drifting" -> ORANGE;
            case "Distracted" -> RED;
            default -> MUTED;
        };

        phaseItem.getValue().text = "Phase: " + wctx.pacePhase;
        phaseItem.getValue().active = true;
        phaseItem.getValue().color = switch (wctx.pacePhase) {
            case "active" -> GREEN;
            case "warmup" -> CYAN;
            case "recovering" -> GOLD;
            case "fatigued" -> RED;
            default -> MUTED;
        };

        fatigueItem.getValue().text = String.format("Fatigue: %.1f%%", wctx.fatigue * 100);
        fatigueItem.getValue().active = true;
        fatigueItem.getValue().color = wctx.fatigue > 1.25 ? RED : (wctx.fatigue > 1.1 ? ORANGE : GOLD);

        long minutes = elapsed / 60000;
        long seconds = (elapsed / 1000) % 60;
        sessionItem.getValue().text = String.format("Session: %dm %02ds", minutes, seconds);
        sessionItem.getValue().active = true;
        sessionItem.getValue().color = GOLD;

        lastQuirkItem.getValue().text = "Last quirk: " + wctx.lastQuirk;
        lastQuirkItem.getValue().active = !"None".equals(wctx.lastQuirk);
        lastQuirkItem.getValue().color = !"None".equals(wctx.lastQuirk) ? VIOLET : MUTED;

        treeView().refresh();
    }

    @SuppressWarnings("unchecked")
    private TreeView<NodeData> treeView() {
        return (TreeView<NodeData>) ((VBox) stage.getScene().getRoot()).getChildren().get(1);
    }

    private static String delayLabel(String context) {
        return switch (context) {
            case "gather" -> "Post-chop delay";
            case "react" -> "Reaction delay";
            case "bank" -> "Banking delay";
            case "walk" -> "Walk delay";
            case "idle" -> "Idle wait";
            case "menu" -> "Menu delay";
            default -> context == null || context.isBlank() ? "None" : "Delay: " + context;
        };
    }

    private static void setActive(TreeItem<NodeData> item, boolean active, Color color) {
        item.getValue().active = active;
        item.getValue().color = active ? color : null;
    }

    static final class NodeData {
        String text;
        boolean active;
        Color color;

        NodeData(String text, boolean active, Color color) {
            this.text = text;
            this.active = active;
            this.color = color;
        }
    }

    private static TreeItem<NodeData> node(String text, boolean active, Color color) {
        return new TreeItem<>(new NodeData(text, active, color));
    }

    private static TreeItem<NodeData> leaf(String text, boolean active, Color color) {
        return new TreeItem<>(new NodeData(text, active, color));
    }

    private static final class IndicatorTreeCell extends TreeCell<NodeData> {

        private final Circle indicator = new Circle(INDICATOR_RADIUS);
        private final Label label = new Label();
        private final HBox box = new HBox(7, indicator, label);

        IndicatorTreeCell() {
            box.setAlignment(Pos.CENTER_LEFT);
            label.setFont(Font.font("Segoe UI", 11));
            setStyle("-fx-background-color: transparent; -fx-padding: 1 0 1 0;");
        }

        @Override
        protected void updateItem(NodeData item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setGraphic(null);
                setText(null);
                return;
            }

            label.setText(item.text);
            Color color = item.color == null ? HOLLOW : item.color;
            if (item.active && item.color != null) {
                indicator.setFill(color);
                indicator.setStroke(color.deriveColor(0, 1, 0.8, 1));
                label.setTextFill(color);
            } else {
                indicator.setFill(Color.TRANSPARENT);
                indicator.setStroke(HOLLOW);
                label.setTextFill(MUTED);
            }

            if (!getTreeItem().isLeaf()) {
                label.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
                if (!item.active || item.color == null) {
                    label.setTextFill(TEXT);
                }
            } else {
                label.setFont(Font.font("Consolas", FontWeight.NORMAL, 11));
            }

            setGraphic(box);
            setText(null);
        }
    }

    private static Label styledLabel(String text, double size, boolean bold, Color color) {
        Label label = new Label(text);
        label.setFont(Font.font("Segoe UI", bold ? FontWeight.BOLD : FontWeight.NORMAL, size));
        label.setTextFill(color);
        return label;
    }

    private static void ensureFxRunning() {
        try {
            Platform.startup(() -> {
            });
        } catch (IllegalStateException ignored) {
        }
        Platform.setImplicitExit(false);
    }

    private static void savePosition(double x, double y) {
        try {
            Files.writeString(POS_FILE, x + "," + y);
        } catch (IOException ignored) {
        }
    }

    private static double[] loadPosition() {
        try {
            if (Files.exists(POS_FILE)) {
                String[] parts = Files.readString(POS_FILE).trim().split(",");
                if (parts.length == 2) {
                    return new double[]{Double.parseDouble(parts[0]), Double.parseDouble(parts[1])};
                }
            }
        } catch (IOException | NumberFormatException ignored) {
        }
        return null;
    }
}
