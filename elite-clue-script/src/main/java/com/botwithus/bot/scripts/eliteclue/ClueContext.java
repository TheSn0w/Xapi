package com.botwithus.bot.scripts.eliteclue;

import com.botwithus.bot.api.GameAPI;
import com.botwithus.bot.api.antiban.Pace;
import com.botwithus.bot.api.entities.Npcs;
import com.botwithus.bot.api.entities.SceneObjects;
import com.botwithus.bot.api.event.TickEvent;
import com.botwithus.bot.api.inventory.Backpack;
import com.botwithus.bot.api.inventory.Equipment;
import com.botwithus.bot.api.inventory.banking.Bank;
import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;
import com.botwithus.bot.api.model.Component;
import com.botwithus.bot.api.model.HintArrow;
import com.botwithus.bot.api.model.LocalPlayer;
import com.botwithus.bot.api.model.SpotAnim;
import com.botwithus.bot.api.Navigation;

import com.botwithus.bot.scripts.eliteclue.scan.ScanClueData;
import com.botwithus.bot.scripts.eliteclue.scan.ScanClueTracker;

import java.util.Arrays;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Shared state for the Elite Clue script.
 * <p>
 * collectUIState() is called on the script thread each loop to cache RPC values.
 * Task validate() methods read only volatile fields (no RPC).
 */
public final class ClueContext {

    private static final BotLogger log = LoggerFactory.getLogger(ClueContext.class);
    private static final int MAX_LOG_ENTRIES = 300;

    // ── Core API ──
    public final GameAPI api;
    public final Pace pace;
    public final Navigation nav;
    public final Backpack backpack;
    public final Equipment equipment;
    public final Bank bank;
    public final Npcs npcs;
    public final SceneObjects objects;

    // ── Clue State ──
    public volatile ClueActivity activity = ClueActivity.IDLE;
    public volatile ClueActivity previousActivity = ClueActivity.IDLE;

    // ── Player State (cached per loop) ──
    public volatile int playerX = 0;
    public volatile int playerY = 0;
    public volatile int playerPlane = 0;
    public volatile int animationId = -1;
    public volatile boolean playerMoving = false;
    public volatile boolean inCombat = false;

    // ── Interface State ──
    public volatile boolean scannerOpen = false;       // 1752
    public volatile boolean slidePuzzleOpen = false;   // 1931
    public volatile boolean directClueOpen = false;    // 996
    public volatile boolean dialogOpen = false;
    public volatile boolean bankOpen = false;

    // Celtic knot interfaces: 394, 519, 525, 526, 529, 1000, 1001, 1002, 1003
    public volatile int celticKnotInterfaceId = -1;    // Which one is open, or -1

    // ── Scanner State ──
    public volatile int spotAnimState = 0;  // 0=none, 1=blue, 2=orange, 3=red
    public volatile String scannerDistanceText = "";

    // ── Hint Arrow ──
    public volatile boolean hasHintArrow = false;
    public volatile int hintArrowX = 0;
    public volatile int hintArrowY = 0;
    public volatile int hintArrowPlane = 0;

    // ── Slide Puzzle State (read from component sprites) ──
    // 5x5 grid: slidePuzzleTiles[position] = tile number (0-23), 24 = empty
    public volatile int[] slidePuzzleTiles = new int[25];
    public volatile int[] slidePuzzleSpriteIds = new int[25];  // Raw sprite IDs for debugging
    public volatile int slidePuzzleEmptySlot = -1;             // Position of the empty tile (0-24)
    public volatile int slidePuzzleBaseSprite = -1;            // Lowest sprite ID (maps to tile 0)
    public static final int SLIDE_EMPTY_SPRITE = 65535;        // Empty slot sprite value

    // ── Slide Puzzle Solver State (for UI display and control) ──
    public volatile boolean slideSolveRequested = false;       // UI button sets this to trigger solving
    public volatile boolean slideSolving = false;              // True while task is actively executing moves
    public volatile String slideSolverStatus = "Idle";         // Current solver status text
    public volatile int slideSolverMTMCount = 0;               // Number of MTM moves in solution
    public volatile int slideSolverSTMCount = 0;               // Number of STM clicks in solution
    public volatile long slideSolverTimeMs = 0;                // Time to compute solution
    public volatile int slideSolverMovesExecuted = 0;          // Moves sent to game so far
    public volatile int slideSolverMovesTotal = 0;             // Total moves in current solution
    public volatile int slideSolverTilesCorrect = 0;           // Tiles currently in correct position

    // ── Varbit State ──
    public volatile int celticKnot4941 = 0;
    public volatile int celticKnot4942 = 0;
    public volatile int celticKnot4943 = 0;
    public volatile int celticKnot4944 = 0;
    public volatile int compassVarc1323 = 0;  // Tetracompass/compass hash

    // ── Clue Item Detection ──
    public volatile boolean hasClueInBackpack = false;
    public volatile int clueItemId = -1;

    // ── Familiar State ──
    public volatile int familiarTimeMinutes = 0;   // varbit 6055
    public volatile int familiarScrollsStored = 0;  // varbit 25412

    // ── Scan Clue System ──
    public final ScanClueData scanData;
    public final ScanClueTracker scanTracker;

    // ── Diagnostics ──
    final CopyOnWriteArrayList<String> actionLog = new CopyOnWriteArrayList<>();
    public volatile long startTime = 0;
    public volatile int cluesCompleted = 0;
    public volatile int stepsCompleted = 0;

    // ── Celtic Knot Interface IDs ──
    private static final int[] CELTIC_KNOT_IDS = {394, 519, 525, 526, 529, 1000, 1001, 1002, 1003};

    public ClueContext(GameAPI api, Pace pace, Navigation nav) {
        this.api = api;
        this.pace = pace;
        this.nav = nav;
        this.backpack = new Backpack(api);
        this.equipment = new Equipment(api);
        this.bank = new Bank(api);
        this.npcs = new Npcs(api);
        this.objects = new SceneObjects(api);
        this.scanData = new ScanClueData(api);
        this.scanTracker = new ScanClueTracker();
        this.startTime = System.currentTimeMillis();
    }

    // ───────────────────────────────────────────────────────────────
    //  Called every loop iteration on the script thread (RPC safe)
    // ───────────────────────────────────────────────────────────────
    public void collectUIState() {
        // Player position and state
        LocalPlayer player = api.getLocalPlayer();
        this.playerX = player.tileX();
        this.playerY = player.tileY();
        this.playerPlane = player.plane();
        this.animationId = player.animationId();
        this.playerMoving = player.isMoving();
        this.inCombat = player.targetIndex() > 0;

        // Bank
        this.bankOpen = bank.isOpen();

        // Interfaces
        this.scannerOpen = api.isInterfaceOpen(1752);
        this.slidePuzzleOpen = api.isInterfaceOpen(1931);
        this.directClueOpen = api.isInterfaceOpen(996);

        // Celtic knot - check all 9 interfaces
        this.celticKnotInterfaceId = -1;
        for (int id : CELTIC_KNOT_IDS) {
            if (api.isInterfaceOpen(id)) {
                this.celticKnotInterfaceId = id;
                break;
            }
        }

        // Dialog detection (interface 1184 is common dialog, 1189 is "You've found..." dialog)
        this.dialogOpen = api.isInterfaceOpen(1184) || api.isInterfaceOpen(1188) || api.isInterfaceOpen(1189);

        // Slide puzzle - read tile state from component sprites
        if (slidePuzzleOpen) {
            readSlidePuzzleState();
        }

        // Scanner spot animations on local player
        this.spotAnimState = detectSpotAnimState();

        // Scanner distance text from interface 1752 component 3
        if (scannerOpen) {
            String text = api.getComponentText(1752, 3);
            this.scannerDistanceText = text != null ? text : "";
        }

        // Hint arrows
        List<HintArrow> arrows = api.queryHintArrows(1);
        if (arrows != null && !arrows.isEmpty()) {
            HintArrow arrow = arrows.getFirst();
            this.hasHintArrow = true;
            this.hintArrowX = arrow.tileX();
            this.hintArrowY = arrow.tileY();
            this.hintArrowPlane = arrow.tileZ();
        } else {
            this.hasHintArrow = false;
            this.hintArrowX = 0;
            this.hintArrowY = 0;
            this.hintArrowPlane = 0;
        }

        // Celtic knot varbits
        this.celticKnot4941 = api.getVarbit(4941);
        this.celticKnot4942 = api.getVarbit(4942);
        this.celticKnot4943 = api.getVarbit(4943);
        this.celticKnot4944 = api.getVarbit(4944);

        // Compass varc
        this.compassVarc1323 = api.getVarcInt(1323);

        // Familiar state
        this.familiarTimeMinutes = api.getVarbit(6055);
        this.familiarScrollsStored = api.getVarbit(25412);

        // Clue item detection - check for elite clue categories in backpack
        detectClueItem();
    }

    /**
     * Check spot animations on/near the local player for scanner colors.
     * 6841 = blue (far), 6842 = orange (closer), 6843 = red (very close)
     */
    private int detectSpotAnimState() {
        // Check each anim - red first (highest priority)
        List<SpotAnim> reds = api.querySpotAnims(6843, playerPlane, 1);
        if (reds != null && !reds.isEmpty()) return 3;

        List<SpotAnim> oranges = api.querySpotAnims(6842, playerPlane, 1);
        if (oranges != null && !oranges.isEmpty()) return 2;

        List<SpotAnim> blues = api.querySpotAnims(6841, playerPlane, 1);
        if (blues != null && !blues.isEmpty()) return 1;

        return 0;
    }

    /**
     * Look for a clue scroll in the backpack.
     * <p>
     * Priority order (highest first):
     * 1. Opened clue scroll — "Clue scroll (elite)" (e.g. 19042) — this is the active step
     * 2. Scroll box — "Scroll box (elite)" — intermediary reward container
     * 3. Sealed clue — "Sealed clue scroll (elite)" (e.g. 42009) — NOT openable if active clue exists
     * 4. Category 1864 — elite-specific step items (fallback match)
     * <p>
     * The script should only try to open the ACTIVE clue step, never the sealed one
     * (RS3 only allows one active clue per tier at a time).
     */
    private void detectClueItem() {
        var items = backpack.getItems();
        this.hasClueInBackpack = false;
        this.clueItemId = -1;

        if (items == null) return;

        // Two-pass: first find opened/active clue, then fall back to sealed
        int openedClueId = -1;
        int scrollBoxId = -1;
        int sealedClueId = -1;
        int categoryMatchId = -1;

        for (var item : items) {
            int id = item.itemId();
            if (id <= 0) continue;
            var itemType = api.getItemType(id);
            if (itemType == null) continue;

            String name = itemType.name();
            int category = itemType.category();

            if (name != null) {
                // Opened/active clue step: "Clue scroll (elite)" but NOT "Sealed clue scroll (elite)"
                if (name.contains("Clue scroll (elite)") && !name.contains("Sealed")) {
                    openedClueId = id;
                }
                // Scroll box (intermediary reward)
                else if (name.contains("Scroll box (elite)")) {
                    scrollBoxId = id;
                }
                // Sealed clue (can't open if active clue exists)
                else if (name.contains("Sealed clue scroll (elite)")) {
                    sealedClueId = id;
                }
            }

            // Category 1864 = elite clue step items (fallback)
            if (category == 1864 && categoryMatchId == -1) {
                categoryMatchId = id;
            }
        }

        // Pick the best match in priority order
        if (openedClueId > 0) {
            this.hasClueInBackpack = true;
            this.clueItemId = openedClueId;
        } else if (scrollBoxId > 0) {
            this.hasClueInBackpack = true;
            this.clueItemId = scrollBoxId;
        } else if (categoryMatchId > 0) {
            this.hasClueInBackpack = true;
            this.clueItemId = categoryMatchId;
        } else if (sealedClueId > 0) {
            // Only use sealed if there's no active clue at all
            this.hasClueInBackpack = true;
            this.clueItemId = sealedClueId;
        }
    }

    /**
     * Read the slide puzzle state from interface 1931 component 18 children.
     * Each child's subComponentId is the grid position (0-24, left-to-right top-to-bottom).
     * The spriteId on each child tells us which tile image is at that position.
     * The empty slot will have spriteId == -1 or 0.
     *
     * Tile movement: GameAction(57, 1, subComponentId, 126550034)
     * Only tiles adjacent to the empty slot can be moved.
     */
    private void readSlidePuzzleState() {
        int[] tiles = new int[25];
        int[] sprites = new int[25];
        Arrays.fill(tiles, -1);
        Arrays.fill(sprites, -1);
        int emptySlot = -1;
        int minSprite = Integer.MAX_VALUE;

        List<Component> children = api.getComponentChildren(1931, 18);
        if (children != null) {
            // First pass: collect all sprite IDs and find the minimum (= tile 0)
            for (Component child : children) {
                int pos = child.subComponentId();
                if (pos >= 0 && pos < 25) {
                    int sprite = child.spriteId();
                    sprites[pos] = sprite;
                    if (sprite == SLIDE_EMPTY_SPRITE) {
                        emptySlot = pos;
                    } else if (sprite > 0 && sprite < minSprite) {
                        minSprite = sprite;
                    }
                }
            }

            // Second pass: convert sprite IDs to tile numbers (0-23)
            // Tile number = spriteId - baseSprite
            // Solved state: tile 0 at pos 0, tile 1 at pos 1, ..., empty at pos 24
            if (minSprite != Integer.MAX_VALUE) {
                for (int i = 0; i < 25; i++) {
                    if (sprites[i] == SLIDE_EMPTY_SPRITE) {
                        tiles[i] = 24; // Empty = tile 24 (the "hole")
                    } else if (sprites[i] > 0) {
                        tiles[i] = sprites[i] - minSprite;
                    }
                }
            }
            this.slidePuzzleBaseSprite = (minSprite != Integer.MAX_VALUE) ? minSprite : -1;
        }

        this.slidePuzzleTiles = tiles;
        this.slidePuzzleSpriteIds = sprites;
        this.slidePuzzleEmptySlot = emptySlot;
    }

    /**
     * Determine the current clue activity based on interface state and game context.
     */
    public ClueActivity determineActivity() {
        // Puzzle interfaces take priority
        if (slidePuzzleOpen) return ClueActivity.SLIDE_PUZZLE;
        if (celticKnotInterfaceId > 0) return ClueActivity.CELTIC_KNOT;
        if (scannerOpen) return ClueActivity.SCANNER;
        if (directClueOpen) return ClueActivity.COMPASS;

        // Hint arrow means dig
        if (hasHintArrow) return ClueActivity.DIG;

        // Dialog open
        if (dialogOpen) return ClueActivity.DIALOG;

        // Has clue but no interface - need to open it
        if (hasClueInBackpack) return ClueActivity.OPEN_CLUE;

        return ClueActivity.IDLE;
    }

    /**
     * Get a color-coded label for the current spot anim state.
     */
    public String getSpotAnimLabel() {
        return switch (spotAnimState) {
            case 1 -> "BLUE (far)";
            case 2 -> "ORANGE (closer)";
            case 3 -> "RED (very close!)";
            default -> "NONE";
        };
    }

    public void logAction(String message) {
        String entry = String.format("[%tT] %s", System.currentTimeMillis(), message);
        actionLog.addFirst(entry);
        while (actionLog.size() > MAX_LOG_ENTRIES) {
            actionLog.removeLast();
        }
        log.info("[EliteClue] {}", message);
    }
}
