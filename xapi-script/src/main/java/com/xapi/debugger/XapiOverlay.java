package com.xapi.debugger;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.model.EntityInfo;
import com.botwithus.bot.api.model.EntityScreenPosition;
import com.botwithus.bot.api.model.GameWindowRect;
import javafx.animation.AnimationTimer;
import javafx.application.Platform;
import javafx.geometry.VPos;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
import javafx.scene.layout.Pane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.TextAlignment;
import javafx.stage.Stage;
import javafx.stage.StageStyle;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * JavaFX transparent overlay that renders entity highlights on top of the game window.
 * Uses a dedicated background thread for smooth position updates (~120ms).
 */
final class XapiOverlay {

    private static final Logger log = LoggerFactory.getLogger(XapiOverlay.class);

    record EntityHighlight(double screenX, double screenY, String name, int combatLevel,
                           int health, int maxHealth, int animationId, Color color) {}

    private final CopyOnWriteArrayList<EntityHighlight> highlights = new CopyOnWriteArrayList<>();

    private volatile int gameClientX;
    private volatile int gameClientY;
    private volatile int gameClientWidth = 800;
    private volatile int gameClientHeight = 600;

    private volatile Stage overlayStage;
    private volatile Canvas canvas;
    private volatile boolean initQueued;
    private volatile boolean disposed;
    private volatile boolean userEnabled = true;
    private AnimationTimer repaintTimer;

    // Background position poller
    private ScheduledExecutorService positionPoller;
    private volatile GameAPI trackedApi;
    private volatile Supplier<Integer> handleSupplier;
    private volatile Supplier<EntityInfo> infoSupplier;

    void initFX() {
        if (disposed || overlayStage != null || initQueued) return;
        initQueued = true;
        runOnFxThread(() -> {
            initQueued = false;
            if (disposed || overlayStage != null) return;

            Platform.setImplicitExit(false);

            Stage stage = new Stage(StageStyle.TRANSPARENT);
            stage.setAlwaysOnTop(true);
            stage.setResizable(false);
            stage.setTitle("Xapi Overlay");

            Canvas overlayCanvas = new Canvas(gameClientWidth, gameClientHeight);
            overlayCanvas.setMouseTransparent(true);

            Pane root = new Pane(overlayCanvas);
            root.setPickOnBounds(false);
            root.setMouseTransparent(true);
            root.setStyle("-fx-background-color: transparent;");

            Scene scene = new Scene(root, gameClientWidth, gameClientHeight, Color.TRANSPARENT);
            scene.setFill(Color.TRANSPARENT);

            stage.setX(gameClientX);
            stage.setY(gameClientY);
            stage.setScene(scene);
            stage.show();

            canvas = overlayCanvas;
            overlayStage = stage;
            repaintTimer = new AnimationTimer() {
                @Override
                public void handle(long now) {
                    repaint();
                }
            };
            repaintTimer.start();
            log.debug("Xapi overlay initialized");
        });
    }

    /**
     * Start the background position poller for smooth overlay updates.
     * Called once from onStart() after initFX().
     */
    void startTracking(GameAPI api, Supplier<Integer> handleSup, Supplier<EntityInfo> infoSup) {
        this.trackedApi = api;
        this.handleSupplier = handleSup;
        this.infoSupplier = infoSup;

        positionPoller = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "XapiOverlay-Poller");
            t.setDaemon(true);
            return t;
        });
        positionPoller.scheduleAtFixedRate(this::pollPositions, 100, 120, TimeUnit.MILLISECONDS);
    }

    private void pollPositions() {
        if (disposed || !userEnabled) return;
        GameAPI api = trackedApi;
        Supplier<Integer> hSup = handleSupplier;
        Supplier<EntityInfo> iSup = infoSupplier;
        if (api == null || hSup == null || iSup == null) return;

        try {
            // Refresh game window position
            GameWindowRect rect = api.getGameWindowRect();
            if (rect != null && rect.clientWidth() > 0 && rect.clientHeight() > 0) {
                gameClientX = rect.clientX();
                gameClientY = rect.clientY();
                gameClientWidth = rect.clientWidth();
                gameClientHeight = rect.clientHeight();
            }

            // Fetch screen position for selected entity
            int handle = hSup.get();
            EntityInfo info = iSup.get();
            highlights.clear();

            if (handle >= 0 && info != null) {
                List<EntityScreenPosition> positions = api.getEntityScreenPositions(List.of(handle));
                if (positions != null && !positions.isEmpty()) {
                    EntityScreenPosition pos = positions.get(0);
                    if (pos.valid()) {
                        String name = info.name() != null ? info.name() : "Entity";
                        highlights.add(new EntityHighlight(
                                pos.screenX(), pos.screenY(),
                                name, info.combatLevel(),
                                info.health(), info.maxHealth(),
                                info.animationId(),
                                Color.rgb(0, 255, 255, 1.0)
                        ));
                    }
                }
            }

            // Show/hide overlay on FX thread
            if (!highlights.isEmpty()) {
                runOnFxThread(() -> {
                    Stage stage = overlayStage;
                    if (stage != null && !stage.isShowing()) stage.show();
                });
            } else {
                runOnFxThread(() -> {
                    Stage stage = overlayStage;
                    if (stage != null && stage.isShowing()) stage.hide();
                });
            }
        } catch (Exception e) {
            log.debug("Overlay poll error: {}", e.getMessage());
        }
    }

    void dispose() {
        disposed = true;
        if (positionPoller != null) {
            positionPoller.shutdownNow();
            positionPoller = null;
        }
        runOnFxThread(() -> {
            if (repaintTimer != null) {
                repaintTimer.stop();
                repaintTimer = null;
            }
            if (overlayStage != null) {
                overlayStage.close();
                overlayStage = null;
            }
            canvas = null;
        });
    }

    void setUserEnabled(boolean enabled) {
        this.userEnabled = enabled;
        if (!enabled) {
            runOnFxThread(() -> {
                Stage stage = overlayStage;
                if (stage != null) stage.hide();
            });
        }
    }

    boolean isUserEnabled() {
        return userEnabled;
    }

    /**
     * Called from onLoop() to update which entity is selected.
     * Position fetching is handled by the background poller.
     */
    void update(GameAPI api, int selectedHandle, EntityInfo selectedInfo) {
        // The background poller reads from the suppliers, so this method
        // no longer needs to do RPC calls. It's kept for backward compatibility
        // in case startTracking hasn't been called yet (fallback mode).
        if (positionPoller != null) return; // Background poller handles everything

        // Fallback: direct update (legacy path)
        refreshWindow(api);
        highlights.clear();

        if (selectedHandle >= 0 && selectedInfo != null) {
            try {
                List<EntityScreenPosition> positions = api.getEntityScreenPositions(List.of(selectedHandle));
                if (positions != null && !positions.isEmpty()) {
                    EntityScreenPosition pos = positions.get(0);
                    if (pos.valid()) {
                        String name = selectedInfo.name() != null ? selectedInfo.name() : "Entity";
                        highlights.add(new EntityHighlight(
                                pos.screenX(), pos.screenY(),
                                name, selectedInfo.combatLevel(),
                                selectedInfo.health(), selectedInfo.maxHealth(),
                                selectedInfo.animationId(),
                                Color.rgb(0, 255, 255, 1.0)
                        ));
                    }
                }
            } catch (Exception e) {
                log.debug("Overlay entity position error: {}", e.getMessage());
            }
        }

        if (!highlights.isEmpty() && userEnabled) {
            runOnFxThread(() -> {
                Stage stage = overlayStage;
                if (stage != null && !stage.isShowing()) stage.show();
            });
        } else if (highlights.isEmpty()) {
            runOnFxThread(() -> {
                Stage stage = overlayStage;
                if (stage != null && stage.isShowing()) stage.hide();
            });
        }
    }

    private void refreshWindow(GameAPI api) {
        try {
            GameWindowRect rect = api.getGameWindowRect();
            if (rect != null && rect.clientWidth() > 0 && rect.clientHeight() > 0) {
                gameClientX = rect.clientX();
                gameClientY = rect.clientY();
                gameClientWidth = rect.clientWidth();
                gameClientHeight = rect.clientHeight();
            }
        } catch (RuntimeException ignored) {}
    }

    private void repaint() {
        Canvas c = canvas;
        Stage stage = overlayStage;
        if (c == null || stage == null || disposed) return;

        // Sync overlay position/size with game window
        stage.setX(gameClientX);
        stage.setY(gameClientY);
        if (c.getWidth() != gameClientWidth || c.getHeight() != gameClientHeight) {
            c.setWidth(gameClientWidth);
            c.setHeight(gameClientHeight);
            stage.setWidth(gameClientWidth);
            stage.setHeight(gameClientHeight);
        }

        GraphicsContext gc = c.getGraphicsContext2D();
        gc.clearRect(0, 0, c.getWidth(), c.getHeight());

        if (!userEnabled || highlights.isEmpty()) return;

        for (EntityHighlight h : highlights) {
            drawEntityHighlight(gc, h);
        }
    }

    private void drawEntityHighlight(GraphicsContext gc, EntityHighlight h) {
        double x = h.screenX();
        double y = h.screenY();
        double size = 48;

        // Pulsing glow effect — faster and more pronounced
        double pulse = 0.3 + 0.7 * Math.sin(System.currentTimeMillis() / 200.0);

        // Outer glow circle
        gc.setStroke(h.color().deriveColor(0, 1, 1, 0.6 * pulse));
        gc.setLineWidth(5);
        gc.strokeOval(x - size, y - size, size * 2, size * 2);

        // Inner highlight circle
        gc.setStroke(h.color().deriveColor(0, 1, 1, 0.9 * pulse));
        gc.setLineWidth(3);
        gc.strokeOval(x - size * 0.7, y - size * 0.7, size * 1.4, size * 1.4);

        // Center dot
        gc.setFill(h.color().deriveColor(0, 1, 1, 1.0));
        gc.fillOval(x - 5, y - 5, 10, 10);

        // Name label
        gc.setFont(Font.font("System", FontWeight.BOLD, 14));
        gc.setTextAlign(TextAlignment.CENTER);
        gc.setTextBaseline(VPos.TOP);

        String label = h.name();
        if (h.combatLevel() > 0) label += " (Lvl " + h.combatLevel() + ")";

        // Text shadow
        gc.setFill(Color.rgb(0, 0, 0, 0.8));
        gc.fillText(label, x + 1, y + size + 6 + 1);
        // Text
        gc.setFill(h.color());
        gc.fillText(label, x, y + size + 6);

        // Health bar (if entity has health)
        if (h.maxHealth() > 0) {
            double barWidth = 70;
            double barHeight = 8;
            double barX = x - barWidth / 2;
            double barY = y + size + 26;
            float pct = (float) h.health() / h.maxHealth();

            // Background
            gc.setFill(Color.rgb(40, 40, 40, 0.8));
            gc.fillRoundRect(barX, barY, barWidth, barHeight, 4, 4);

            // Fill
            Color barColor = pct > 0.5 ? Color.rgb(0, 220, 0, 0.9) :
                             pct > 0.25 ? Color.rgb(220, 220, 0, 0.9) :
                             Color.rgb(220, 0, 0, 0.9);
            gc.setFill(barColor);
            gc.fillRoundRect(barX, barY, barWidth * pct, barHeight, 4, 4);

            // Health text
            gc.setFont(Font.font("System", FontWeight.NORMAL, 11));
            gc.setFill(Color.WHITE);
            gc.fillText(h.health() + "/" + h.maxHealth(), x, barY + barHeight + 12);
        }

        // Animation indicator
        if (h.animationId() >= 0) {
            gc.setFont(Font.font("System", FontWeight.NORMAL, 11));
            gc.setFill(Color.rgb(200, 200, 200, 0.9));
            double animY = h.maxHealth() > 0 ? y + size + 52 : y + size + 26;
            gc.fillText("Anim: " + h.animationId(), x, animY);
        }
    }

    private static void runOnFxThread(Runnable action) {
        if (Platform.isFxApplicationThread()) {
            action.run();
            return;
        }
        try {
            Platform.runLater(action);
        } catch (IllegalStateException first) {
            try {
                Platform.startup(action);
            } catch (IllegalStateException ignored) {
                // JavaFX toolkit already running, try runLater again
                try {
                    Platform.runLater(action);
                } catch (IllegalStateException alsoIgnored) {
                    log.debug("Failed to run on FX thread");
                }
            }
        }
    }
}
