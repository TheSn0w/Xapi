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
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Always-on-top JavaFX overlay using a TreeView to display script status.
 * Each node has a status indicator circle: green = active, hollow = inactive.
 * <p>
 * Reads exclusively from {@link WoodcuttingContext} volatile fields (no RPC calls).
 * Updated every 250ms via a JavaFX Timeline.
 */
public final class ScriptOverlay {

    private static final String FONT_FAMILY = "Consolas";
    private static final double WIDTH = 300;
    private static final double INDICATOR_RADIUS = 4.0;
    private static final Path POS_FILE = Path.of("scripts", "overlay-pos.txt");

    private static final Color GREEN = Color.web("#4ae04a");
    private static final Color YELLOW = Color.web("#ffd966");
    private static final Color RED = Color.web("#ff6655");
    private static final Color BLUE = Color.web("#66aaff");
    private static final Color HOLLOW_STROKE = Color.web("#555566");
    private static final Color TEXT_DIM = Color.web("#888899");
    private static final Color TEXT_NORMAL = Color.web("#ccccdd");

    private final WoodcuttingContext wctx;
    private Stage stage;
    private Timeline timeline;

    // Offset of overlay relative to game window top-left
    private double offsetX = Double.NaN;
    private double offsetY = Double.NaN;
    private volatile boolean dragging = false;

    // Tree items we update in-place
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
            if (timeline != null) { timeline.stop(); timeline = null; }
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
        stage.setTitle("Woodcutting");
        stage.setResizable(false);

        // ── Build tree ───────────────────────────────────────────
        rootItem = node("Woodcutting v1.0", true);
        rootItem.setExpanded(true);

        // Tasks branch
        taskItem = node("Tasks", false);
        taskItem.setExpanded(true);
        choppingItem   = leaf("Chopping", false);
        fillWoodboxItem = leaf("Fill wood box", false);
        bankingItem    = leaf("Banking", false);
        walkingItem    = leaf("Walking to trees", false);
        taskItem.getChildren().addAll(choppingItem, fillWoodboxItem, bankingItem, walkingItem);

        // Delay branch (human-like pause between actions)
        delayItem = node("Delay", false);
        delayItem.setExpanded(true);
        delayContextItem = leaf("Idle", false);
        delayItem.getChildren().add(delayContextItem);

        // Break branch — single child when idle, two when active
        breakItem = node("Break", false);
        breakItem.setExpanded(true);
        breakStatusItem = leaf("None", false);
        breakTimerItem  = leaf("", false);
        breakItem.getChildren().add(breakStatusItem);

        // Stats branch
        statsItem = node("Stats", true);
        statsItem.setExpanded(true);
        logsItem      = leaf("Logs: 0", false);
        rateItem      = leaf("Rate: 0/hr", false);
        woodboxItem   = leaf("Wood box: 0 / 0", false);
        bankTripsItem = leaf("Bank trips: 0", false);
        statsItem.getChildren().addAll(logsItem, rateItem, woodboxItem, bankTripsItem);

        // Antiban branch
        antibanItem = node("Antiban", true);
        antibanItem.setExpanded(true);
        attentionItem = leaf("Attention: Focused", true);
        phaseItem   = leaf("Phase: warmup", false);
        fatigueItem = leaf("Fatigue: 0.0%", false);
        sessionItem = leaf("Session: 0m 0s", false);
        lastQuirkItem = leaf("Last quirk: None", false);
        antibanItem.getChildren().addAll(attentionItem, phaseItem, fatigueItem, sessionItem, lastQuirkItem);

        rootItem.getChildren().addAll(taskItem, delayItem, breakItem, statsItem, antibanItem);

        // ── TreeView ─────────────────────────────────────────────
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

        // ── Title bar (draggable) ────────────────────────────────
        HBox titleBar = new HBox(8);
        titleBar.setAlignment(Pos.CENTER_LEFT);
        titleBar.setPadding(new Insets(6, 8, 2, 8));

        Label title = styledLabel("Woodcutting", 13, true, GREEN);
        Label version = styledLabel("v1.0", 9, false, TEXT_DIM);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Label closeBtn = styledLabel("x", 12, false, TEXT_DIM);
        closeBtn.setOnMouseClicked(e -> close());
        closeBtn.setOnMouseEntered(e -> closeBtn.setTextFill(RED));
        closeBtn.setOnMouseExited(e -> closeBtn.setTextFill(TEXT_DIM));

        titleBar.getChildren().addAll(title, version, spacer, closeBtn);

        final double[] dragOffset = new double[2];
        titleBar.setOnMousePressed(e -> {
            dragging = true;
            dragOffset[0] = e.getScreenX() - stage.getX();
            dragOffset[1] = e.getScreenY() - stage.getY();
        });
        titleBar.setOnMouseDragged(e -> {
            double newX = e.getScreenX() - dragOffset[0];
            double newY = e.getScreenY() - dragOffset[1];
            stage.setX(newX);
            stage.setY(newY);
        });
        titleBar.setOnMouseReleased(e -> {
            // Recalculate offset relative to game window
            int gwX = wctx.gameWindowX;
            int gwY = wctx.gameWindowY;
            offsetX = stage.getX() - gwX;
            offsetY = stage.getY() - gwY;
            savePosition(offsetX, offsetY);
            dragging = false;
        });

        // ── Root container ───────────────────────────────────────
        VBox root = new VBox(0, titleBar, treeView);
        root.setPadding(new Insets(6, 4, 8, 4));
        root.setStyle(
                "-fx-background-color: rgba(18, 18, 24, 0.94);" +
                "-fx-background-radius: 10;" +
                "-fx-border-color: #3a3a50;" +
                "-fx-border-width: 1;" +
                "-fx-border-radius: 10;"
        );

        Scene scene = new Scene(root, WIDTH, 460);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
        stage.show();

        // Restore saved offset — actual positioning deferred to update() once game window is known
        double[] savedOffset = loadPosition();
        if (savedOffset != null) {
            offsetX = savedOffset[0];
            offsetY = savedOffset[1];
        }
        // Place at screen top-right initially; update() will snap to game window
        var screen = javafx.stage.Screen.getPrimary().getVisualBounds();
        stage.setX(screen.getMaxX() - WIDTH - 20);
        stage.setY(40);

        // Update loop
        timeline = new Timeline(new KeyFrame(Duration.millis(250), e -> update()));
        timeline.setCycleCount(Timeline.INDEFINITE);
        timeline.play();
    }

    // ── Update ───────────────────────────────────────────────────

    private void update() {
        if (wctx == null) return;

        // ── Track game window position ─────────────────────────
        int gwX = wctx.gameWindowX;
        int gwY = wctx.gameWindowY;
        int gwW = wctx.gameWindowWidth;
        boolean hasGameWindow = gwW > 0;


        if (hasGameWindow && !dragging) {
            // Initialize default offset if none saved/set yet
            if (Double.isNaN(offsetX)) {
                // Default: top-right corner inside the game window
                offsetX = gwW - WIDTH - 10;
                offsetY = 40;
                savePosition(offsetX, offsetY);
            }
            // Always position relative to game window
            stage.setX(gwX + offsetX);
            stage.setY(gwY + offsetY);
        }

        String task = wctx.currentTaskName;
        boolean breaking = wctx.onBreak;

        // Root indicator
        rootItem.getValue().active = !breaking;
        rootItem.getValue().color = breaking ? YELLOW : GREEN;

        // Task indicators — light up the active one
        setActive(choppingItem,    "Chopping".equals(task) && !breaking);
        setActive(fillWoodboxItem, "Filling wood box".equals(task) && !breaking);
        setActive(bankingItem,     "Banking".equals(task) && !breaking);
        setActive(walkingItem,     "Walking to trees".equals(task) && !breaking);

        // Delay — computed from tick-updated player state (fresh every ~600ms game tick)
        // Player is delaying when: not animating, not moving, not on break
        boolean delaying = wctx.animationId == -1 && !wctx.playerMoving && !breaking;
        String ctx = wctx.delayContext;
        delayItem.getValue().active = delaying;
        delayItem.getValue().color = delaying ? BLUE : null;
        if (delaying) {
            String label = switch (ctx) {
                case "gather" -> "Post-chop delay";
                case "react"  -> "Reaction delay";
                case "bank"   -> "Banking delay";
                case "walk"   -> "Walk delay";
                case "idle"   -> "Idle wait";
                case "menu"   -> "Menu delay";
                default       -> "Delay (" + ctx + ")";
            };
            delayContextItem.getValue().text = label;
            delayContextItem.getValue().active = true;
            delayContextItem.getValue().color = BLUE;
        } else {
            delayContextItem.getValue().text = "None";
            delayContextItem.getValue().active = false;
            delayContextItem.getValue().color = null;
        }

        // Break — show timer child only when active
        breakItem.getValue().active = breaking;
        breakItem.getValue().color = breaking ? YELLOW : null;
        if (breaking) {
            String label = wctx.breakLabel;
            breakStatusItem.getValue().text = label != null ? label : "Taking a break...";
            breakStatusItem.getValue().active = true;
            breakStatusItem.getValue().color = YELLOW;

            long remaining = wctx.breakRemainingMs;
            if (remaining > 0) {
                long sec = remaining / 1000;
                breakTimerItem.getValue().text = String.format("Resuming in %d:%02d", sec / 60, sec % 60);
            } else {
                breakTimerItem.getValue().text = "Resuming soon...";
            }
            breakTimerItem.getValue().active = true;
            breakTimerItem.getValue().color = YELLOW;

            // Add timer child if not already there
            if (!breakItem.getChildren().contains(breakTimerItem)) {
                breakItem.getChildren().add(breakTimerItem);
            }
        } else {
            breakStatusItem.getValue().text = "None";
            breakStatusItem.getValue().active = false;
            // Remove timer child when not on break
            breakItem.getChildren().remove(breakTimerItem);
        }

        // Stats
        long elapsed = System.currentTimeMillis() - wctx.startTime;
        int logs = wctx.logsChopped;
        double hours = elapsed / 3_600_000.0;
        int rate = hours > 0.001 ? (int) (logs / hours) : 0;

        logsItem.getValue().text = "Logs: " + logs;
        logsItem.getValue().active = logs > 0;
        logsItem.getValue().color = logs > 0 ? GREEN : null;

        rateItem.getValue().text = "Rate: " + rate + "/hr";
        rateItem.getValue().active = rate > 0;
        rateItem.getValue().color = rate > 0 ? GREEN : null;

        woodboxItem.getValue().text = "Wood box: " + wctx.woodboxStored + " / " + wctx.woodboxCapacity;
        woodboxItem.getValue().active = wctx.hasWoodBox;
        woodboxItem.getValue().color = wctx.hasWoodBox ? GREEN : null;

        bankTripsItem.getValue().text = "Bank trips: " + wctx.bankTrips;
        bankTripsItem.getValue().active = wctx.bankTrips > 0;

        // Antiban
        String attn = wctx.attentionState;
        attentionItem.getValue().text = "Attention: " + attn;
        attentionItem.getValue().active = true;
        attentionItem.getValue().color = switch (attn) {
            case "Focused" -> GREEN;
            case "Drifting" -> YELLOW;
            case "Distracted" -> RED;
            default -> null;
        };

        String phase = wctx.pacePhase;
        phaseItem.getValue().text = "Phase: " + phase;
        phaseItem.getValue().active = true;
        phaseItem.getValue().color = switch (phase) {
            case "warmup" -> BLUE;
            case "active" -> GREEN;
            case "fatigued" -> RED;
            case "recovering" -> YELLOW;
            default -> null;
        };

        double fatigue = wctx.fatigue;
        fatigueItem.getValue().text = String.format("Fatigue: %.1f%%", fatigue * 100);
        fatigueItem.getValue().active = true;
        fatigueItem.getValue().color = fatigue > 1.3 ? RED : fatigue > 1.12 ? YELLOW : GREEN;

        long min = elapsed / 60000;
        long sec = (elapsed / 1000) % 60;
        sessionItem.getValue().text = String.format("Session: %dm %ds", min, sec);
        sessionItem.getValue().active = true;
        sessionItem.getValue().color = GREEN;

        // Last quirk
        String quirk = wctx.lastQuirk;
        lastQuirkItem.getValue().text = "Last quirk: " + quirk;
        boolean quirkActive = !"None".equals(quirk);
        lastQuirkItem.getValue().active = quirkActive;
        lastQuirkItem.getValue().color = quirkActive ? Color.web("#cc88ff") : null;

        // Force TreeView to repaint cells
        treeView().refresh();
    }

    @SuppressWarnings("unchecked")
    private TreeView<NodeData> treeView() {
        return (TreeView<NodeData>) ((VBox) stage.getScene().getRoot()).getChildren().get(1);
    }


    private static void setActive(TreeItem<NodeData> item, boolean active) {
        item.getValue().active = active;
        item.getValue().color = active ? GREEN : null;
    }

    // ── Data model ───────────────────────────────────────────────

    static final class NodeData {
        String text;
        boolean active;
        Color color; // null = hollow/inactive

        NodeData(String text, boolean active) {
            this.text = text;
            this.active = active;
        }
    }

    private static TreeItem<NodeData> node(String text, boolean active) {
        return new TreeItem<>(new NodeData(text, active));
    }

    private static TreeItem<NodeData> leaf(String text, boolean active) {
        return new TreeItem<>(new NodeData(text, active));
    }

    // ── Custom TreeCell with indicator circle ────────────────────

    private static final class IndicatorTreeCell extends TreeCell<NodeData> {

        private final Circle indicator = new Circle(INDICATOR_RADIUS);
        private final Label label = new Label();
        private final HBox box = new HBox(6, indicator, label);

        IndicatorTreeCell() {
            box.setAlignment(Pos.CENTER_LEFT);
            label.setFont(Font.font(FONT_FAMILY, 11));
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

            if (item.active && item.color != null) {
                // Filled circle with active color
                indicator.setFill(item.color);
                indicator.setStroke(item.color.darker());
                indicator.setStrokeWidth(1.0);
                label.setTextFill(TEXT_NORMAL);
                label.setFont(Font.font(FONT_FAMILY, FontWeight.NORMAL, 11));
            } else {
                // Hollow circle — inactive
                indicator.setFill(Color.TRANSPARENT);
                indicator.setStroke(HOLLOW_STROKE);
                indicator.setStrokeWidth(1.0);
                label.setTextFill(TEXT_DIM);
                label.setFont(Font.font(FONT_FAMILY, FontWeight.NORMAL, 11));
            }

            // Branch headers get bold text
            if (!getTreeItem().isLeaf()) {
                label.setFont(Font.font(FONT_FAMILY, FontWeight.BOLD, 11));
                if (item.active && item.color != null) {
                    label.setTextFill(item.color);
                }
            }

            setGraphic(box);
            setText(null);
        }
    }

    // ── Helpers ──────────────────────────────────────────────────

    private static Label styledLabel(String text, double size, boolean bold, Color color) {
        Label l = new Label(text);
        l.setFont(Font.font(FONT_FAMILY, bold ? FontWeight.BOLD : FontWeight.NORMAL, size));
        l.setTextFill(color);
        return l;
    }

    private static void ensureFxRunning() {
        try { Platform.startup(() -> {}); }
        catch (IllegalStateException ignored) { }
        Platform.setImplicitExit(false);
    }

    private static void savePosition(double x, double y) {
        try {
            Files.writeString(POS_FILE, x + "," + y);
        } catch (IOException ignored) { }
    }

    private static double[] loadPosition() {
        try {
            if (Files.exists(POS_FILE)) {
                String[] parts = Files.readString(POS_FILE).trim().split(",");
                if (parts.length == 2) {
                    return new double[]{ Double.parseDouble(parts[0]), Double.parseDouble(parts[1]) };
                }
            }
        } catch (IOException | NumberFormatException ignored) { }
        return null;
    }
}
