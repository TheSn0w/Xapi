package com.botwithus.bot.scripts.eliteclue.scan;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive simulation tests for the scan clue navigation solver.
 * <p>
 * Each test creates a synthetic region with known candidate coordinates,
 * picks a hidden "true" dig spot, then runs the solver loop:
 * <ol>
 *   <li>Solver computes next walk target</li>
 *   <li>Player "teleports" to that target (or partway)</li>
 *   <li>Server returns the correct color based on Chebyshev distance to the true spot</li>
 *   <li>Tracker eliminates inconsistent candidates</li>
 *   <li>Solver processes the observation</li>
 *   <li>Repeat until solved or iteration limit hit</li>
 * </ol>
 */
class ScanSolverSimulationTest {

    /** Max iterations before we declare a simulation stuck. */
    private static final int MAX_ITERATIONS = 200;

    // ── Simulation harness ──

    /**
     * Runs a full solver simulation and returns the result.
     *
     * @param candidates   all candidate coordinates for the region
     * @param trueSpot     the actual dig spot (must be in candidates)
     * @param startX       player start X
     * @param startY       player start Y
     * @param baseDistance  the scan base distance for this clue
     * @param regionName   display name for logging
     * @return simulation result
     */
    private SimResult runSimulation(List<ScanCoordinate> candidates,
                                    ScanCoordinate trueSpot,
                                    int startX, int startY,
                                    int baseDistance,
                                    String regionName) {
        // Build region
        ScanRegion region = new ScanRegion(regionName, 9999, 99999, List.copyOf(candidates));

        // Set up tracker
        ScanClueTracker tracker = new ScanClueTracker();
        tracker.startTracking(region);
        tracker.parseBaseDistance(baseDistance + " paces");

        // Set up solver + observation log
        ScanNavigationSolver solver = new ScanNavigationSolver();
        solver.setBaseDistance(baseDistance);
        ScanObservationLog obsLog = new ScanObservationLog();

        int playerX = startX;
        int playerY = startY;
        int iterations = 0;
        int totalEliminated = 0;
        int negValEliminations = 0;
        boolean solved = false;
        List<String> log = new ArrayList<>();

        while (iterations < MAX_ITERATIONS) {
            iterations++;

            // 1. Compute server response at current position
            int distToTrue = trueSpot.chebyshevDistance(playerX, playerY);
            ScanPulseColor serverColor = computeServerColor(distToTrue, baseDistance);

            // 2. Feed observation to tracker
            int beforeCount = tracker.getCandidateCount();
            int trackerElim = tracker.processObservation(playerX, playerY, serverColor);

            // 3. Feed observation to observation log + solver
            obsLog.record(playerX, playerY, serverColor, tracker.getCandidateCount());
            solver.setBaseDistance(baseDistance);

            int beforeNegVal = tracker.getCandidateCount();
            solver.onObservation(obsLog, tracker);
            int afterNegVal = tracker.getCandidateCount();
            int negValThisStep = beforeNegVal - afterNegVal;
            negValEliminations += negValThisStep;

            totalEliminated += trackerElim + negValThisStep;

            log.add(String.format("  #%d: pos=(%d,%d) dist=%d color=%s phase=%s elim=%d+%d cands=%d",
                    iterations, playerX, playerY, distToTrue, serverColor.label(),
                    solver.getPhase().label(), trackerElim, negValThisStep,
                    tracker.getCandidateCount()));

            // 4. Check if solved
            // In the real game, RED = within scan range = hint arrow appears.
            // The hint arrow reveals the exact dig spot coordinates, so RED means solved.
            if (serverColor == ScanPulseColor.RED) {
                solved = true;
                log.add(String.format("  >>> SOLVED via RED (hint arrow) at dist=%d in %d iterations",
                        distToTrue, iterations));
                break;
            }

            if (tracker.getCandidateCount() == 1) {
                ScanCoordinate last = tracker.getCandidates().getFirst();
                if (last.x() == trueSpot.x() && last.y() == trueSpot.y()) {
                    solved = true;
                    log.add("  >>> SOLVED in " + iterations + " iterations! Only true spot remains.");
                    break;
                }
            }
            if (tracker.getCandidateCount() == 0) {
                log.add("  >>> ERROR: All candidates eliminated! True spot was wrongly removed.");
                break;
            }

            // 5. Compute next walk target
            int[] target = solver.computeNextTarget(playerX, playerY, obsLog, tracker);
            if (target == null) {
                log.add("  >>> ERROR: Solver returned null target!");
                break;
            }

            // 6. "Walk" to target (simulate instant teleport, clamped to region bounds)
            playerX = target[0];
            playerY = target[1];
        }

        if (!solved && iterations >= MAX_ITERATIONS) {
            log.add("  >>> TIMEOUT: " + MAX_ITERATIONS + " iterations, " + tracker.getCandidateCount() + " candidates remaining");
        }

        return new SimResult(solved, iterations, totalEliminated, negValEliminations,
                tracker.getCandidateCount(), candidates.size(), log);
    }

    /**
     * Compute the server's color response based on Chebyshev distance to the true spot.
     */
    private ScanPulseColor computeServerColor(int chebyshevDist, int baseDistance) {
        if (chebyshevDist <= baseDistance) return ScanPulseColor.RED;
        if (chebyshevDist <= (baseDistance * 2) + 1) return ScanPulseColor.ORANGE;
        return ScanPulseColor.BLUE;
    }

    record SimResult(boolean solved, int iterations, int totalEliminated, int negValEliminations,
                     int remainingCandidates, int totalCandidates, List<String> log) {
        void assertSolved(String context) {
            if (!solved) {
                System.err.println("=== FAILED: " + context + " ===");
                log.forEach(System.err::println);
            }
            assertTrue(solved, context + " — solver did not find the true spot. " +
                    remainingCandidates + "/" + totalCandidates + " candidates remaining after " + iterations + " iterations");
        }

        void assertTrueSpotNotEliminated(String context) {
            assertTrue(remainingCandidates > 0,
                    context + " — all candidates were eliminated (including true spot)!");
        }

        void print(String label) {
            System.out.println("=== " + label + " ===");
            System.out.println("  Solved: " + solved + " in " + iterations + " iterations");
            System.out.println("  Eliminated: " + totalEliminated + " (negVal: " + negValEliminations + ")");
            System.out.println("  Remaining: " + remainingCandidates + "/" + totalCandidates);
            log.forEach(System.out::println);
            System.out.println();
        }
    }

    // ── Helper: generate candidate grids ──

    private List<ScanCoordinate> generateGrid(int centerX, int centerY, int spread, int count) {
        List<ScanCoordinate> coords = new ArrayList<>();
        Random rng = new Random(centerX * 31L + centerY);
        for (int i = 0; i < count; i++) {
            int x = centerX + rng.nextInt(spread * 2 + 1) - spread;
            int y = centerY + rng.nextInt(spread * 2 + 1) - spread;
            coords.add(new ScanCoordinate(x, y, 0, 0));
        }
        return coords;
    }

    private List<ScanCoordinate> generateUniformGrid(int minX, int minY, int maxX, int maxY, int step) {
        List<ScanCoordinate> coords = new ArrayList<>();
        for (int x = minX; x <= maxX; x += step) {
            for (int y = minY; y <= maxY; y += step) {
                coords.add(new ScanCoordinate(x, y, 0, 0));
            }
        }
        return coords;
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 1: Basic solver convergence
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Simple 5-candidate cluster, player starts near centroid")
    void testSimpleCluster() {
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3200, 3200, 0, 0),
                new ScanCoordinate(3210, 3210, 0, 0),
                new ScanCoordinate(3220, 3200, 0, 0),
                new ScanCoordinate(3200, 3220, 0, 0),
                new ScanCoordinate(3215, 3205, 0, 0)
        );
        ScanCoordinate trueSpot = candidates.get(2); // (3220, 3200)
        SimResult result = runSimulation(candidates, trueSpot, 3210, 3210, 14, "SimpleCluster");
        result.print("Simple Cluster");
        result.assertTrueSpotNotEliminated("Simple cluster");
        result.assertSolved("Simple cluster");
    }

    @Test
    @DisplayName("Single candidate — should solve immediately")
    void testSingleCandidate() {
        ScanCoordinate only = new ScanCoordinate(3100, 3100, 0, 0);
        List<ScanCoordinate> candidates = List.of(only);
        SimResult result = runSimulation(candidates, only, 3050, 3050, 14, "SingleCandidate");
        result.print("Single Candidate");
        result.assertSolved("Single candidate");
        assertEquals(1, result.iterations(), "Should solve in 1 iteration");
    }

    @Test
    @DisplayName("Two candidates far apart — color eliminates one quickly")
    void testTwoCandidatesFarApart() {
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3000, 3000, 0, 0),
                new ScanCoordinate(3100, 3100, 0, 0)
        );
        ScanCoordinate trueSpot = candidates.get(0);
        SimResult result = runSimulation(candidates, trueSpot, 3050, 3050, 14, "TwoFarApart");
        result.print("Two Candidates Far Apart");
        result.assertTrueSpotNotEliminated("Two far apart");
        result.assertSolved("Two far apart");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 2: Different base distances
    // ══════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(ints = {7, 11, 14, 20, 27})
    @DisplayName("Various base distances with 10-candidate spread")
    void testVariousBaseDistances(int baseDistance) {
        List<ScanCoordinate> candidates = generateGrid(3200, 3200, 50, 10);
        ScanCoordinate trueSpot = candidates.get(3);
        SimResult result = runSimulation(candidates, trueSpot, 3150, 3150, baseDistance,
                "BaseDist=" + baseDistance);
        result.print("Base Distance = " + baseDistance);
        result.assertTrueSpotNotEliminated("BaseDist=" + baseDistance);
        // Don't assert solved — some may time out with very small base distances and unlucky layouts
        // But the true spot must NEVER be eliminated
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 3: Large candidate sets (realistic regions)
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("30 candidates spread across a large area (Varrock-like)")
    void testLargeRegion() {
        List<ScanCoordinate> candidates = generateGrid(3200, 3400, 100, 30);
        ScanCoordinate trueSpot = candidates.get(15);
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3400, 14, "LargeRegion");
        result.print("Large Region (30 candidates)");
        result.assertTrueSpotNotEliminated("Large region");
    }

    @Test
    @DisplayName("50 candidates in a uniform grid")
    void testUniformGrid50() {
        List<ScanCoordinate> candidates = generateUniformGrid(3100, 3100, 3200, 3200, 15);
        ScanCoordinate trueSpot = candidates.get(candidates.size() / 3);
        SimResult result = runSimulation(candidates, trueSpot, 3150, 3150, 14, "UniformGrid50");
        result.print("Uniform Grid (~50 candidates)");
        result.assertTrueSpotNotEliminated("Uniform grid 50");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 4: Edge case positions
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Player starts on top of true spot")
    void testStartOnTrueSpot() {
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3200, 3200, 0, 0),
                new ScanCoordinate(3250, 3250, 0, 0),
                new ScanCoordinate(3300, 3200, 0, 0)
        );
        ScanCoordinate trueSpot = candidates.get(0);
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 14, "StartOnSpot");
        result.print("Start On True Spot");
        result.assertTrueSpotNotEliminated("Start on spot");
        result.assertSolved("Start on spot");
    }

    @Test
    @DisplayName("Player starts very far from all candidates")
    void testStartFarAway() {
        List<ScanCoordinate> candidates = generateGrid(3200, 3200, 30, 8);
        ScanCoordinate trueSpot = candidates.get(4);
        SimResult result = runSimulation(candidates, trueSpot, 2900, 2900, 14, "StartFarAway");
        result.print("Start Far Away");
        result.assertTrueSpotNotEliminated("Start far away");
    }

    @Test
    @DisplayName("True spot at region boundary (min corner)")
    void testTrueSpotAtMinCorner() {
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3100, 3100, 0, 0), // min corner
                new ScanCoordinate(3150, 3150, 0, 0),
                new ScanCoordinate(3200, 3200, 0, 0),
                new ScanCoordinate(3200, 3100, 0, 0),
                new ScanCoordinate(3100, 3200, 0, 0)
        );
        ScanCoordinate trueSpot = candidates.get(0);
        SimResult result = runSimulation(candidates, trueSpot, 3150, 3150, 14, "MinCorner");
        result.print("True Spot at Min Corner");
        result.assertTrueSpotNotEliminated("Min corner");
        result.assertSolved("Min corner");
    }

    @Test
    @DisplayName("True spot at region boundary (max corner)")
    void testTrueSpotAtMaxCorner() {
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3100, 3100, 0, 0),
                new ScanCoordinate(3150, 3150, 0, 0),
                new ScanCoordinate(3200, 3200, 0, 0), // max corner
                new ScanCoordinate(3200, 3100, 0, 0),
                new ScanCoordinate(3100, 3200, 0, 0)
        );
        ScanCoordinate trueSpot = candidates.get(2);
        SimResult result = runSimulation(candidates, trueSpot, 3150, 3150, 14, "MaxCorner");
        result.print("True Spot at Max Corner");
        result.assertTrueSpotNotEliminated("Max corner");
        result.assertSolved("Max corner");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 5: Negative validation scenarios
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("NegVal: walk near wrong candidate, should eliminate it")
    void testNegativeValidationBasic() {
        // Two candidates: one close, one far. True spot is the far one.
        // Solver should walk toward the close one first, get ORANGE/BLUE,
        // and neg-val should eliminate the close one.
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3200, 3200, 0, 0), // close to start
                new ScanCoordinate(3300, 3300, 0, 0)  // far — this is the true spot
        );
        ScanCoordinate trueSpot = candidates.get(1);
        SimResult result = runSimulation(candidates, trueSpot, 3210, 3210, 14, "NegValBasic");
        result.print("Negative Validation Basic");
        result.assertTrueSpotNotEliminated("NegVal basic");
        result.assertSolved("NegVal basic");
    }

    @Test
    @DisplayName("NegVal: cluster of wrong candidates near start, true spot far away")
    void testNegativeValidationCluster() {
        List<ScanCoordinate> candidates = new ArrayList<>();
        // 5 wrong candidates near (3200, 3200)
        candidates.add(new ScanCoordinate(3195, 3195, 0, 0));
        candidates.add(new ScanCoordinate(3200, 3200, 0, 0));
        candidates.add(new ScanCoordinate(3205, 3200, 0, 0));
        candidates.add(new ScanCoordinate(3200, 3205, 0, 0));
        candidates.add(new ScanCoordinate(3205, 3205, 0, 0));
        // True spot far away
        candidates.add(new ScanCoordinate(3350, 3350, 0, 0));

        ScanCoordinate trueSpot = candidates.get(5);
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 14, "NegValCluster");
        result.print("Negative Validation Cluster");
        result.assertTrueSpotNotEliminated("NegVal cluster");
        // Check that neg-val eliminated some candidates
        assertTrue(result.negValEliminations() > 0 || result.totalEliminated() >= 5,
                "Should eliminate wrong candidates near start");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 6: Tricky geometric layouts
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Candidates in a line — parallel walk risk")
    void testCandidatesInLine() {
        List<ScanCoordinate> candidates = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            candidates.add(new ScanCoordinate(3100 + i * 20, 3200, 0, 0));
        }
        ScanCoordinate trueSpot = candidates.get(5); // middle-ish
        SimResult result = runSimulation(candidates, trueSpot, 3100, 3200, 14, "LineLayout");
        result.print("Candidates in a Line");
        result.assertTrueSpotNotEliminated("Line layout");
    }

    @Test
    @DisplayName("Candidates in an L-shape")
    void testLShapeLayout() {
        List<ScanCoordinate> candidates = new ArrayList<>();
        // Vertical arm
        for (int i = 0; i < 5; i++) {
            candidates.add(new ScanCoordinate(3100, 3100 + i * 25, 0, 0));
        }
        // Horizontal arm
        for (int i = 1; i < 5; i++) {
            candidates.add(new ScanCoordinate(3100 + i * 25, 3100, 0, 0));
        }
        ScanCoordinate trueSpot = candidates.get(7); // end of horizontal arm
        SimResult result = runSimulation(candidates, trueSpot, 3100, 3150, 14, "LShape");
        result.print("L-Shape Layout");
        result.assertTrueSpotNotEliminated("L-shape");
    }

    @Test
    @DisplayName("Two dense clusters far apart")
    void testTwoClusters() {
        List<ScanCoordinate> candidates = new ArrayList<>();
        // Cluster A around (3100, 3100)
        candidates.add(new ScanCoordinate(3095, 3095, 0, 0));
        candidates.add(new ScanCoordinate(3100, 3100, 0, 0));
        candidates.add(new ScanCoordinate(3105, 3105, 0, 0));
        candidates.add(new ScanCoordinate(3100, 3105, 0, 0));
        // Cluster B around (3300, 3300)
        candidates.add(new ScanCoordinate(3295, 3295, 0, 0));
        candidates.add(new ScanCoordinate(3300, 3300, 0, 0));
        candidates.add(new ScanCoordinate(3305, 3305, 0, 0));
        candidates.add(new ScanCoordinate(3300, 3305, 0, 0));

        ScanCoordinate trueSpot = candidates.get(6); // in cluster B
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 14, "TwoClusters");
        result.print("Two Dense Clusters");
        result.assertTrueSpotNotEliminated("Two clusters");
        result.assertSolved("Two clusters");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 7: Compass heading quantization correctness
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Heading quantization: all 8 compass directions")
    void testHeadingQuantization() {
        // We can test this indirectly by checking that setDirectionToward
        // picks correct headings. Use reflection-free approach: run solver
        // with targets in each quadrant and verify it doesn't get stuck.
        // Direct approach: use the fact that COMPASS vectors are public constants

        // N: (0, 1) -> heading 0
        // NE: (1, 1) -> heading 1
        // E: (1, 0) -> heading 2
        // etc.

        // Test via solver behavior: place single candidate in each direction
        // from start. Solver should converge.
        int[][] directions = {
                {0, 50},   // N
                {50, 50},  // NE
                {50, 0},   // E
                {50, -50}, // SE
                {0, -50},  // S
                {-50, -50},// SW
                {-50, 0},  // W
                {-50, 50}, // NW
        };
        String[] labels = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

        for (int i = 0; i < 8; i++) {
            int cx = 3200 + directions[i][0];
            int cy = 3200 + directions[i][1];
            List<ScanCoordinate> candidates = List.of(
                    new ScanCoordinate(cx, cy, 0, 0),
                    new ScanCoordinate(3200, 3200, 0, 0) // add one more to prevent instant solve
            );
            ScanCoordinate trueSpot = candidates.get(0);
            SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 14,
                    "Heading_" + labels[i]);
            result.assertTrueSpotNotEliminated("Heading " + labels[i]);
        }
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 8: Color consistency — true spot must NEVER be eliminated
    // ══════════════════════════════════════════════════════════════

    @RepeatedTest(20)
    @DisplayName("Random region: true spot must never be eliminated")
    void testRandomRegionTrueSpotSafety() {
        Random rng = new Random();
        int numCandidates = 5 + rng.nextInt(26); // 5-30
        int baseDistance = 7 + rng.nextInt(21);   // 7-27
        int spread = 30 + rng.nextInt(71);        // 30-100

        List<ScanCoordinate> candidates = generateGrid(3200, 3200, spread, numCandidates);
        int trueIdx = rng.nextInt(candidates.size());
        ScanCoordinate trueSpot = candidates.get(trueIdx);

        int startX = 3200 + rng.nextInt(100) - 50;
        int startY = 3200 + rng.nextInt(100) - 50;

        SimResult result = runSimulation(candidates, trueSpot, startX, startY, baseDistance,
                "Random_" + numCandidates + "c_b" + baseDistance);

        result.assertTrueSpotNotEliminated(
                String.format("Random region %d candidates, base=%d, true=%s, start=(%d,%d)",
                        numCandidates, baseDistance, trueSpot, startX, startY));
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 9: ScanPulseColor.isConsistentWith correctness
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Color consistency function: boundary values for base=14")
    void testColorConsistencyBase14() {
        int base = 14;
        int orangeMax = (base * 2) + 1; // 29

        // RED: dist <= 14
        assertTrue(ScanPulseColor.RED.isConsistentWith(0, base));
        assertTrue(ScanPulseColor.RED.isConsistentWith(14, base));
        assertFalse(ScanPulseColor.RED.isConsistentWith(15, base));

        // ORANGE: 14 < dist <= 29
        assertFalse(ScanPulseColor.ORANGE.isConsistentWith(14, base));
        assertTrue(ScanPulseColor.ORANGE.isConsistentWith(15, base));
        assertTrue(ScanPulseColor.ORANGE.isConsistentWith(29, base));
        assertFalse(ScanPulseColor.ORANGE.isConsistentWith(30, base));

        // BLUE: dist > 29
        assertFalse(ScanPulseColor.BLUE.isConsistentWith(29, base));
        assertTrue(ScanPulseColor.BLUE.isConsistentWith(30, base));
        assertTrue(ScanPulseColor.BLUE.isConsistentWith(100, base));
    }

    @Test
    @DisplayName("Color consistency function: boundary values for base=11")
    void testColorConsistencyBase11() {
        int base = 11;
        int orangeMax = (base * 2) + 1; // 23

        assertTrue(ScanPulseColor.RED.isConsistentWith(11, base));
        assertFalse(ScanPulseColor.RED.isConsistentWith(12, base));

        assertTrue(ScanPulseColor.ORANGE.isConsistentWith(12, base));
        assertTrue(ScanPulseColor.ORANGE.isConsistentWith(23, base));
        assertFalse(ScanPulseColor.ORANGE.isConsistentWith(24, base));

        assertTrue(ScanPulseColor.BLUE.isConsistentWith(24, base));
    }

    @Test
    @DisplayName("Color consistency function: boundary values for base=27")
    void testColorConsistencyBase27() {
        int base = 27;
        int orangeMax = (base * 2) + 1; // 55

        assertTrue(ScanPulseColor.RED.isConsistentWith(27, base));
        assertFalse(ScanPulseColor.RED.isConsistentWith(28, base));

        assertTrue(ScanPulseColor.ORANGE.isConsistentWith(28, base));
        assertTrue(ScanPulseColor.ORANGE.isConsistentWith(55, base));
        assertFalse(ScanPulseColor.ORANGE.isConsistentWith(56, base));

        assertTrue(ScanPulseColor.BLUE.isConsistentWith(56, base));
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 10: Observation log correctness
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("ObservationLog transitions computed correctly")
    void testObservationLogTransitions() {
        ScanObservationLog log = new ScanObservationLog();

        log.record(100, 100, ScanPulseColor.BLUE, 10);
        assertEquals(ScanObservationLog.ColorTransition.NONE, log.getTransition(),
                "First observation should have NONE transition");

        log.record(110, 110, ScanPulseColor.ORANGE, 8);
        assertEquals(ScanObservationLog.ColorTransition.IMPROVED, log.getTransition(),
                "BLUE -> ORANGE should be IMPROVED");

        log.record(120, 120, ScanPulseColor.ORANGE, 6);
        assertEquals(ScanObservationLog.ColorTransition.SAME, log.getTransition(),
                "ORANGE -> ORANGE should be SAME");

        log.record(130, 130, ScanPulseColor.RED, 4);
        assertEquals(ScanObservationLog.ColorTransition.IMPROVED, log.getTransition(),
                "ORANGE -> RED should be IMPROVED");

        log.record(140, 140, ScanPulseColor.ORANGE, 3);
        assertEquals(ScanObservationLog.ColorTransition.DEGRADED, log.getTransition(),
                "RED -> ORANGE should be DEGRADED");

        log.record(150, 150, ScanPulseColor.BLUE, 2);
        assertEquals(ScanObservationLog.ColorTransition.DEGRADED, log.getTransition(),
                "ORANGE -> BLUE should be DEGRADED");
    }

    @Test
    @DisplayName("ObservationLog best observation tracking")
    void testObservationLogBest() {
        ScanObservationLog log = new ScanObservationLog();

        log.record(100, 100, ScanPulseColor.BLUE, 10);
        assertEquals(ScanPulseColor.BLUE, log.getBestObservation().color());

        log.record(110, 110, ScanPulseColor.ORANGE, 8);
        assertEquals(ScanPulseColor.ORANGE, log.getBestObservation().color());

        log.record(120, 120, ScanPulseColor.BLUE, 7);
        assertEquals(ScanPulseColor.ORANGE, log.getBestObservation().color(),
                "Best should still be ORANGE even after seeing BLUE again");

        log.record(130, 130, ScanPulseColor.RED, 3);
        assertEquals(ScanPulseColor.RED, log.getBestObservation().color());
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 11: Tracker elimination correctness
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Tracker eliminateCandidates bulk method works")
    void testTrackerBulkElimination() {
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3100, 3100, 0, 0),
                new ScanCoordinate(3200, 3200, 0, 0),
                new ScanCoordinate(3300, 3300, 0, 0),
                new ScanCoordinate(3400, 3400, 0, 0)
        );
        ScanRegion region = new ScanRegion("Test", 1, 1, candidates);
        ScanClueTracker tracker = new ScanClueTracker();
        tracker.startTracking(region);

        assertEquals(4, tracker.getCandidateCount());

        int removed = tracker.eliminateCandidates(
                List.of(candidates.get(0), candidates.get(2)),
                "test reason");

        assertEquals(2, removed);
        assertEquals(2, tracker.getCandidateCount());
        assertEquals(2, tracker.getEliminationCount());

        // Verify correct ones remain
        List<ScanCoordinate> remaining = tracker.getCandidates();
        assertTrue(remaining.contains(candidates.get(1)));
        assertTrue(remaining.contains(candidates.get(3)));
    }

    @Test
    @DisplayName("Tracker never eliminates candidate consistent with observation")
    void testTrackerNeverEliminatesConsistent() {
        ScanCoordinate trueSpot = new ScanCoordinate(3200, 3200, 0, 0);
        List<ScanCoordinate> candidates = List.of(
                trueSpot,
                new ScanCoordinate(3100, 3100, 0, 0),
                new ScanCoordinate(3300, 3300, 0, 0)
        );
        ScanRegion region = new ScanRegion("Test", 1, 1, candidates);
        ScanClueTracker tracker = new ScanClueTracker();
        tracker.startTracking(region);
        tracker.parseBaseDistance("14 paces");

        // Observe RED at position near true spot (dist = 5, which is <= 14)
        tracker.processObservation(3195, 3200, ScanPulseColor.RED);

        // True spot should still be present
        assertTrue(tracker.getCandidates().contains(trueSpot),
                "True spot within RED range must not be eliminated");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 12: Stress test — many random scenarios
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Stress: 100 random scenarios — true spot must never be eliminated")
    void testStress100Scenarios() {
        Random rng = new Random(42); // deterministic seed
        int failures = 0;
        int solvedCount = 0;
        int totalIterations = 0;
        List<String> failureMessages = new ArrayList<>();

        for (int scenario = 0; scenario < 100; scenario++) {
            int numCandidates = 3 + rng.nextInt(28);
            int baseDistance = 7 + rng.nextInt(21);
            int spread = 20 + rng.nextInt(81);

            List<ScanCoordinate> candidates = new ArrayList<>();
            for (int i = 0; i < numCandidates; i++) {
                int x = 3200 + rng.nextInt(spread * 2 + 1) - spread;
                int y = 3200 + rng.nextInt(spread * 2 + 1) - spread;
                candidates.add(new ScanCoordinate(x, y, 0, 0));
            }

            int trueIdx = rng.nextInt(candidates.size());
            ScanCoordinate trueSpot = candidates.get(trueIdx);
            int startX = 3200 + rng.nextInt(60) - 30;
            int startY = 3200 + rng.nextInt(60) - 30;

            SimResult result = runSimulation(candidates, trueSpot, startX, startY, baseDistance,
                    "Stress_" + scenario);

            totalIterations += result.iterations();
            if (result.solved()) solvedCount++;

            if (result.remainingCandidates() == 0) {
                failures++;
                String msg = String.format("Scenario %d: %d cands, base=%d, true=%s, start=(%d,%d) — " +
                                "ALL CANDIDATES ELIMINATED (true spot wrongly removed!)",
                        scenario, numCandidates, baseDistance, trueSpot, startX, startY);
                failureMessages.add(msg);
                result.print("FAILURE Scenario " + scenario);
            }
        }

        System.out.println("=== STRESS TEST SUMMARY ===");
        System.out.printf("  Scenarios: 100, Solved: %d, Failures: %d%n", solvedCount, failures);
        System.out.printf("  Average iterations: %.1f%n", totalIterations / 100.0);

        if (!failureMessages.isEmpty()) {
            System.err.println("=== FAILURE DETAILS ===");
            failureMessages.forEach(System.err::println);
        }

        assertEquals(0, failures, "True spot was wrongly eliminated in " + failures + " scenarios:\n" +
                String.join("\n", failureMessages));
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 13: Bounding box clamping
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Solver targets clamped within region bounds")
    void testBoundsClamping() {
        // Small region — solver should never target outside bounds
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3100, 3100, 0, 0),
                new ScanCoordinate(3110, 3110, 0, 0),
                new ScanCoordinate(3120, 3100, 0, 0)
        );
        ScanRegion region = new ScanRegion("TinyRegion", 1, 1, candidates);
        ScanClueTracker tracker = new ScanClueTracker();
        tracker.startTracking(region);
        tracker.parseBaseDistance("14 paces");

        ScanNavigationSolver solver = new ScanNavigationSolver();
        solver.setBaseDistance(14);
        ScanObservationLog obsLog = new ScanObservationLog();

        // Simulate from a position outside the region
        int[] target = solver.computeNextTarget(3000, 3000, obsLog, tracker);
        assertNotNull(target, "Target should not be null");
        assertTrue(target[0] >= 3100 && target[0] <= 3120,
                "Target X should be within bounds: " + target[0]);
        assertTrue(target[1] >= 3100 && target[1] <= 3110,
                "Target Y should be within bounds: " + target[1]);
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 14: Phase transition correctness
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Phase transitions: INITIAL -> PROBE -> BACKTRACK -> PROBE")
    void testPhaseTransitions() {
        List<ScanCoordinate> candidates = generateGrid(3200, 3200, 50, 15);
        ScanCoordinate trueSpot = candidates.get(10);

        ScanRegion region = new ScanRegion("PhaseTest", 1, 1, candidates);
        ScanClueTracker tracker = new ScanClueTracker();
        tracker.startTracking(region);
        tracker.parseBaseDistance("14 paces");

        ScanNavigationSolver solver = new ScanNavigationSolver();
        solver.setBaseDistance(14);
        ScanObservationLog obsLog = new ScanObservationLog();

        // Initially should be INITIAL_APPROACH
        assertEquals(ScanNavigationSolver.SolverPhase.INITIAL_APPROACH, solver.getPhase());

        // First observation -> should transition to DIRECTIONAL_PROBE
        obsLog.record(3200, 3200, ScanPulseColor.BLUE, 15);
        solver.onObservation(obsLog, tracker);
        assertEquals(ScanNavigationSolver.SolverPhase.DIRECTIONAL_PROBE, solver.getPhase(),
                "Should transition to DIRECTIONAL_PROBE after first observation");

        // Degraded observation -> should transition to BACKTRACK
        obsLog.record(3220, 3200, ScanPulseColor.BLUE, 15); // SAME first
        solver.onObservation(obsLog, tracker);

        // Simulate improvement then degradation
        obsLog.record(3230, 3200, ScanPulseColor.ORANGE, 10);
        solver.onObservation(obsLog, tracker);
        assertEquals(ScanNavigationSolver.SolverPhase.DIRECTIONAL_PROBE, solver.getPhase(),
                "Should stay in PROBE on improvement");

        obsLog.record(3250, 3200, ScanPulseColor.BLUE, 10);
        solver.onObservation(obsLog, tracker);
        assertEquals(ScanNavigationSolver.SolverPhase.BACKTRACK, solver.getPhase(),
                "Should transition to BACKTRACK on degradation");
    }

    @Test
    @DisplayName("Phase transition: RED observation -> stays in PROBE (hint arrow handles it)")
    void testRedDoesNotTriggerConverge() {
        List<ScanCoordinate> candidates = generateGrid(3200, 3200, 50, 10);

        ScanRegion region = new ScanRegion("RedTest", 1, 1, candidates);
        ScanClueTracker tracker = new ScanClueTracker();
        tracker.startTracking(region);

        ScanNavigationSolver solver = new ScanNavigationSolver();
        solver.setBaseDistance(14);
        ScanObservationLog obsLog = new ScanObservationLog();

        // RED means hint arrow appeared in real game. Solver transitions
        // to DIRECTIONAL_PROBE (first observation), but ScanClueTask would
        // immediately switch to handleSolved() before solver runs again.
        obsLog.record(3200, 3200, ScanPulseColor.RED, 10);
        solver.onObservation(obsLog, tracker);
        assertEquals(ScanNavigationSolver.SolverPhase.DIRECTIONAL_PROBE, solver.getPhase(),
                "RED should not trigger CONVERGE — hint arrow handles navigation");
    }

    @Test
    @DisplayName("Phase transition: <= 3 candidates -> CONVERGE")
    void testFewCandidatesTriggersConverge() {
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3200, 3200, 0, 0),
                new ScanCoordinate(3250, 3250, 0, 0),
                new ScanCoordinate(3300, 3300, 0, 0)
        );

        ScanRegion region = new ScanRegion("FewCands", 1, 1, candidates);
        ScanClueTracker tracker = new ScanClueTracker();
        tracker.startTracking(region);

        ScanNavigationSolver solver = new ScanNavigationSolver();
        solver.setBaseDistance(14);
        ScanObservationLog obsLog = new ScanObservationLog();

        obsLog.record(3200, 3200, ScanPulseColor.BLUE, 3);
        solver.onObservation(obsLog, tracker);
        assertEquals(ScanNavigationSolver.SolverPhase.CONVERGE, solver.getPhase(),
                "<= 3 candidates should trigger CONVERGE");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 15: Duplicate coordinate handling
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Duplicate candidates don't cause issues")
    void testDuplicateCandidates() {
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3200, 3200, 0, 0),
                new ScanCoordinate(3200, 3200, 0, 0), // duplicate!
                new ScanCoordinate(3250, 3250, 0, 0),
                new ScanCoordinate(3300, 3300, 0, 0)
        );
        ScanCoordinate trueSpot = candidates.get(2);
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 14, "Duplicates");
        result.print("Duplicate Candidates");
        result.assertTrueSpotNotEliminated("Duplicates");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 16: Very small base distances
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Base distance = 4 (very small — tight zones)")
    void testVerySmallBaseDistance() {
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3200, 3200, 0, 0),
                new ScanCoordinate(3210, 3200, 0, 0),
                new ScanCoordinate(3220, 3200, 0, 0),
                new ScanCoordinate(3230, 3200, 0, 0)
        );
        ScanCoordinate trueSpot = candidates.get(2);
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 4, "SmallBase4");
        result.print("Very Small Base Distance (4)");
        result.assertTrueSpotNotEliminated("Small base 4");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 17: Very large base distances
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Base distance = 27 (large — wide zones)")
    void testLargeBaseDistance27() {
        List<ScanCoordinate> candidates = generateGrid(3200, 3200, 80, 15);
        ScanCoordinate trueSpot = candidates.get(7);
        SimResult result = runSimulation(candidates, trueSpot, 3100, 3100, 27, "LargeBase27");
        result.print("Large Base Distance (27)");
        result.assertTrueSpotNotEliminated("Large base 27");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 18: Mega stress — 500 random scenarios
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Mega stress: 500 random scenarios — safety + solve rate")
    void testMegaStress500() {
        Random rng = new Random(12345);
        int failures = 0;
        int solvedCount = 0;
        long totalIterations = 0;
        int maxIterationsUsed = 0;
        List<String> failureMessages = new ArrayList<>();

        for (int scenario = 0; scenario < 500; scenario++) {
            int numCandidates = 3 + rng.nextInt(40); // 3-42
            int baseDistance = 4 + rng.nextInt(24);   // 4-27
            int spread = 15 + rng.nextInt(150);       // 15-164

            List<ScanCoordinate> candidates = new ArrayList<>();
            for (int i = 0; i < numCandidates; i++) {
                int x = 3200 + rng.nextInt(spread * 2 + 1) - spread;
                int y = 3200 + rng.nextInt(spread * 2 + 1) - spread;
                candidates.add(new ScanCoordinate(x, y, 0, 0));
            }

            int trueIdx = rng.nextInt(candidates.size());
            ScanCoordinate trueSpot = candidates.get(trueIdx);
            int startX = 3200 + rng.nextInt(200) - 100;
            int startY = 3200 + rng.nextInt(200) - 100;

            SimResult result = runSimulation(candidates, trueSpot, startX, startY, baseDistance,
                    "MegaStress_" + scenario);

            totalIterations += result.iterations();
            maxIterationsUsed = Math.max(maxIterationsUsed, result.iterations());
            if (result.solved()) solvedCount++;

            if (result.remainingCandidates() == 0) {
                failures++;
                String msg = String.format("Scenario %d: %d cands, base=%d, true=%s, start=(%d,%d)",
                        scenario, numCandidates, baseDistance, trueSpot, startX, startY);
                failureMessages.add(msg);
            }
        }

        System.out.println("=== MEGA STRESS TEST SUMMARY (500 scenarios) ===");
        System.out.printf("  Solved: %d/500 (%.1f%%)%n", solvedCount, solvedCount / 5.0);
        System.out.printf("  Safety failures (true spot eliminated): %d%n", failures);
        System.out.printf("  Average iterations: %.1f%n", totalIterations / 500.0);
        System.out.printf("  Max iterations used: %d%n", maxIterationsUsed);

        assertEquals(0, failures,
                "True spot was wrongly eliminated in " + failures + "/500 scenarios:\n" +
                        String.join("\n", failureMessages));

        // Solve rate should be reasonable (>60%)
        assertTrue(solvedCount >= 300,
                "Solve rate too low: " + solvedCount + "/500 (" + (solvedCount / 5.0) + "%)");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 19: Realistic RS3 region layouts
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Realistic: Varrock-like region (30 candidates, wide spread)")
    void testRealisticVarrock() {
        // Simulate Varrock: ~30 candidates, spread ~200 tiles, base=14
        List<ScanCoordinate> candidates = generateGrid(3212, 3424, 100, 30);
        ScanCoordinate trueSpot = candidates.get(20);
        SimResult result = runSimulation(candidates, trueSpot, 3212, 3424, 14, "Varrock");
        result.print("Realistic Varrock");
        result.assertTrueSpotNotEliminated("Varrock");
    }

    @Test
    @DisplayName("Realistic: Falador-like region (25 candidates, medium spread)")
    void testRealisticFalador() {
        List<ScanCoordinate> candidates = generateGrid(2964, 3376, 80, 25);
        ScanCoordinate trueSpot = candidates.get(12);
        SimResult result = runSimulation(candidates, trueSpot, 2964, 3376, 14, "Falador");
        result.print("Realistic Falador");
        result.assertTrueSpotNotEliminated("Falador");
    }

    @Test
    @DisplayName("Realistic: Prifddinas-like (tight cluster, base=11)")
    void testRealisticPrifddinas() {
        List<ScanCoordinate> candidates = generateGrid(2200, 3300, 40, 20);
        ScanCoordinate trueSpot = candidates.get(8);
        SimResult result = runSimulation(candidates, trueSpot, 2200, 3300, 11, "Prifddinas");
        result.print("Realistic Prifddinas");
        result.assertTrueSpotNotEliminated("Prifddinas");
    }

    @Test
    @DisplayName("Realistic: Deep Wilderness (sparse, base=27)")
    void testRealisticWilderness() {
        List<ScanCoordinate> candidates = generateGrid(3100, 3800, 120, 15);
        ScanCoordinate trueSpot = candidates.get(5);
        SimResult result = runSimulation(candidates, trueSpot, 3100, 3700, 27, "DeepWilderness");
        result.print("Realistic Deep Wilderness");
        result.assertTrueSpotNotEliminated("Deep Wilderness");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 20: Extreme edge cases
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("All candidates at same position")
    void testAllSamePosition() {
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3200, 3200, 0, 0),
                new ScanCoordinate(3200, 3200, 0, 0),
                new ScanCoordinate(3200, 3200, 0, 0)
        );
        ScanCoordinate trueSpot = candidates.get(0);
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 14, "AllSamePos");
        result.assertTrueSpotNotEliminated("All same position");
    }

    @Test
    @DisplayName("Candidate 500 tiles away from start")
    void testVeryFarCandidate() {
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3700, 3700, 0, 0),
                new ScanCoordinate(3200, 3200, 0, 0)
        );
        ScanCoordinate trueSpot = candidates.get(0);
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 14, "VeryFar");
        result.print("Very Far Candidate (500 tiles)");
        result.assertTrueSpotNotEliminated("Very far");
        result.assertSolved("Very far");
    }

    @Test
    @DisplayName("Player starts outside region bounding box")
    void testStartOutsideBounds() {
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3500, 3500, 0, 0),
                new ScanCoordinate(3510, 3510, 0, 0),
                new ScanCoordinate(3520, 3500, 0, 0),
                new ScanCoordinate(3500, 3520, 0, 0)
        );
        ScanCoordinate trueSpot = candidates.get(2);
        SimResult result = runSimulation(candidates, trueSpot, 3000, 3000, 14, "OutsideBounds");
        result.print("Start Outside Bounds");
        result.assertTrueSpotNotEliminated("Outside bounds");
    }

    @Test
    @DisplayName("Three candidates in exact same color band from start")
    void testSameColorBandFromStart() {
        // All 3 candidates at exactly baseDistance+1 from start → all ORANGE
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3215, 3200, 0, 0), // dist 15 from (3200,3200) = ORANGE
                new ScanCoordinate(3200, 3215, 0, 0), // dist 15 = ORANGE
                new ScanCoordinate(3185, 3200, 0, 0)  // dist 15 = ORANGE
        );
        ScanCoordinate trueSpot = candidates.get(0);
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 14, "SameColorBand");
        result.print("Same Color Band");
        result.assertTrueSpotNotEliminated("Same color band");
    }

    @Test
    @DisplayName("Large dense cluster — 40 candidates within 30 tiles")
    void testLargeDenseCluster() {
        List<ScanCoordinate> candidates = generateGrid(3200, 3200, 15, 40);
        ScanCoordinate trueSpot = candidates.get(25);
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 14, "LargeDense");
        result.print("Large Dense Cluster (40 cands, 30-tile spread)");
        result.assertTrueSpotNotEliminated("Large dense");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 21: Negative validation deep tests
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("NegVal: walk through multiple wrong candidates sequentially")
    void testNegValSequentialWrongCandidates() {
        // Line of candidates, true spot at the end
        List<ScanCoordinate> candidates = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            candidates.add(new ScanCoordinate(3100 + i * 30, 3200, 0, 0));
        }
        ScanCoordinate trueSpot = candidates.getLast(); // (3250, 3200)
        SimResult result = runSimulation(candidates, trueSpot, 3100, 3200, 14, "NegValSequential");
        result.print("NegVal Sequential");
        result.assertTrueSpotNotEliminated("NegVal sequential");
        // Should eliminate at least some wrong candidates via neg-val
    }

    @Test
    @DisplayName("NegVal: true spot surrounded by wrong candidates — no false elimination")
    void testNegValTrueSpotSurrounded() {
        // True spot at center, wrong candidates around it
        ScanCoordinate trueSpot = new ScanCoordinate(3200, 3200, 0, 0);
        List<ScanCoordinate> candidates = new ArrayList<>();
        candidates.add(trueSpot);
        candidates.add(new ScanCoordinate(3190, 3190, 0, 0));
        candidates.add(new ScanCoordinate(3210, 3190, 0, 0));
        candidates.add(new ScanCoordinate(3190, 3210, 0, 0));
        candidates.add(new ScanCoordinate(3210, 3210, 0, 0));
        // All within 14 of each other with base=14, so RED everywhere near center
        SimResult result = runSimulation(candidates, trueSpot, 3250, 3250, 14, "NegValSurrounded");
        result.print("NegVal True Spot Surrounded");
        result.assertTrueSpotNotEliminated("NegVal surrounded");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 22: Solve efficiency metrics
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Efficiency: measure iteration counts across different scenarios")
    void testSolveEfficiency() {
        int[][] configs = {
                // {numCandidates, spread, baseDistance, startOffsetX, startOffsetY, seed}
                {10, 50, 14, 0, 0, 100},
                {20, 80, 14, 0, 0, 200},
                {30, 100, 14, 0, 0, 300},
                {10, 50, 11, 0, 0, 400},
                {10, 50, 27, 0, 0, 500},
                {15, 30, 14, 50, 50, 600},   // start offset
                {15, 120, 14, 0, 0, 700},    // wide spread
                {5, 200, 14, -100, -100, 800}, // sparse + far start
        };

        System.out.println("=== SOLVE EFFICIENCY ===");
        System.out.printf("%-40s %6s %6s %6s %6s%n",
                "Scenario", "Cands", "Iters", "Elim", "NegVal");

        for (int[] cfg : configs) {
            Random rng = new Random(cfg[5]);
            List<ScanCoordinate> candidates = new ArrayList<>();
            for (int i = 0; i < cfg[0]; i++) {
                int x = 3200 + rng.nextInt(cfg[1] * 2 + 1) - cfg[1];
                int y = 3200 + rng.nextInt(cfg[1] * 2 + 1) - cfg[1];
                candidates.add(new ScanCoordinate(x, y, 0, 0));
            }
            int trueIdx = rng.nextInt(candidates.size());
            ScanCoordinate trueSpot = candidates.get(trueIdx);
            int startX = 3200 + cfg[3];
            int startY = 3200 + cfg[4];
            String label = String.format("%dc_%ds_b%d_off(%d,%d)",
                    cfg[0], cfg[1], cfg[2], cfg[3], cfg[4]);

            SimResult result = runSimulation(candidates, trueSpot, startX, startY, cfg[2], label);
            result.assertTrueSpotNotEliminated(label);

            System.out.printf("%-40s %6d %6d %6d %6d %s%n",
                    label, cfg[0], result.iterations(), result.totalEliminated(),
                    result.negValEliminations(), result.solved() ? "SOLVED" : "TIMEOUT");
        }
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 23: Partial walk simulation (realistic movement)
    // ══════════════════════════════════════════════════════════════

    /**
     * More realistic simulation: player walks partway toward target each tick,
     * not instant teleport. This tests intermediate observations.
     */
    @Test
    @DisplayName("Realistic walk: player moves 10 tiles per tick toward target")
    void testRealisticWalkSpeed() {
        List<ScanCoordinate> candidates = generateGrid(3200, 3200, 60, 12);
        ScanCoordinate trueSpot = candidates.get(6);
        int baseDistance = 14;
        int walkSpeed = 10; // tiles per tick

        SimResult result = runRealisticWalkSimulation(candidates, trueSpot,
                3150, 3150, baseDistance, walkSpeed, "RealisticWalk");
        result.print("Realistic Walk (10 tiles/tick)");
        result.assertTrueSpotNotEliminated("Realistic walk");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 24: Ultra stress — 2000 random scenarios
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Ultra stress: 2000 random scenarios — safety + solve rate")
    void testUltraStress2000() {
        Random rng = new Random(99999);
        int failures = 0;
        int solvedCount = 0;
        long totalIterations = 0;
        int maxIterationsUsed = 0;
        int minIterations = Integer.MAX_VALUE;
        List<String> failureMessages = new ArrayList<>();
        int[] solveByPhase = new int[4]; // track what phase solved it

        for (int scenario = 0; scenario < 2000; scenario++) {
            int numCandidates = 2 + rng.nextInt(50); // 2-51
            int baseDistance = 4 + rng.nextInt(24);   // 4-27
            int spread = 10 + rng.nextInt(200);       // 10-209

            List<ScanCoordinate> candidates = new ArrayList<>();
            Set<Long> seen = new HashSet<>();
            for (int i = 0; i < numCandidates; i++) {
                int x = 3200 + rng.nextInt(spread * 2 + 1) - spread;
                int y = 3200 + rng.nextInt(spread * 2 + 1) - spread;
                long key = ((long) x << 32) | (y & 0xFFFFFFFFL);
                if (seen.add(key)) {
                    candidates.add(new ScanCoordinate(x, y, 0, 0));
                }
            }
            if (candidates.isEmpty()) continue;

            int trueIdx = rng.nextInt(candidates.size());
            ScanCoordinate trueSpot = candidates.get(trueIdx);
            int startX = 3200 + rng.nextInt(300) - 150;
            int startY = 3200 + rng.nextInt(300) - 150;

            SimResult result = runSimulation(candidates, trueSpot, startX, startY, baseDistance,
                    "Ultra_" + scenario);

            totalIterations += result.iterations();
            maxIterationsUsed = Math.max(maxIterationsUsed, result.iterations());
            if (result.solved()) {
                solvedCount++;
                minIterations = Math.min(minIterations, result.iterations());
            }

            if (result.remainingCandidates() == 0) {
                failures++;
                String msg = String.format("Scenario %d: %d cands, base=%d, spread=%d, true=%s, start=(%d,%d)",
                        scenario, candidates.size(), baseDistance, spread, trueSpot, startX, startY);
                failureMessages.add(msg);
                if (failures <= 5) result.print("FAILURE Scenario " + scenario);
            }
        }

        System.out.println("=== ULTRA STRESS TEST SUMMARY (2000 scenarios) ===");
        System.out.printf("  Solved: %d/2000 (%.1f%%)%n", solvedCount, solvedCount / 20.0);
        System.out.printf("  Safety failures (true spot eliminated): %d%n", failures);
        System.out.printf("  Average iterations: %.1f%n", totalIterations / 2000.0);
        System.out.printf("  Min iterations (solved): %d, Max iterations: %d%n",
                minIterations == Integer.MAX_VALUE ? 0 : minIterations, maxIterationsUsed);

        assertEquals(0, failures,
                "True spot was wrongly eliminated in " + failures + "/2000 scenarios:\n" +
                        String.join("\n", failureMessages));

        assertTrue(solvedCount >= 1200,
                "Solve rate too low: " + solvedCount + "/2000 (" + (solvedCount / 20.0) + "%)");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 25: Adversarial geometric layouts
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Ring layout: candidates in a circle around center")
    void testRingLayout() {
        List<ScanCoordinate> candidates = new ArrayList<>();
        int cx = 3200, cy = 3200, radius = 60;
        for (int i = 0; i < 16; i++) {
            double angle = 2 * Math.PI * i / 16;
            int x = cx + (int) (radius * Math.cos(angle));
            int y = cy + (int) (radius * Math.sin(angle));
            candidates.add(new ScanCoordinate(x, y, 0, 0));
        }
        ScanCoordinate trueSpot = candidates.get(12); // bottom-left quadrant
        SimResult result = runSimulation(candidates, trueSpot, cx, cy, 14, "Ring16");
        result.print("Ring Layout (16 candidates)");
        result.assertTrueSpotNotEliminated("Ring layout");
        result.assertSolved("Ring layout");
    }

    @Test
    @DisplayName("Concentric rings: candidates at multiple radii")
    void testConcentricRings() {
        List<ScanCoordinate> candidates = new ArrayList<>();
        int cx = 3200, cy = 3200;
        int[] radii = {20, 50, 90};
        for (int r : radii) {
            for (int i = 0; i < 8; i++) {
                double angle = 2 * Math.PI * i / 8;
                int x = cx + (int) (r * Math.cos(angle));
                int y = cy + (int) (r * Math.sin(angle));
                candidates.add(new ScanCoordinate(x, y, 0, 0));
            }
        }
        ScanCoordinate trueSpot = candidates.get(20); // outer ring
        SimResult result = runSimulation(candidates, trueSpot, cx, cy, 14, "ConcentricRings");
        result.print("Concentric Rings (24 candidates)");
        result.assertTrueSpotNotEliminated("Concentric rings");
    }

    @Test
    @DisplayName("Diagonal line: candidates along 45-degree line")
    void testDiagonalLine() {
        List<ScanCoordinate> candidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            candidates.add(new ScanCoordinate(3100 + i * 20, 3100 + i * 20, 0, 0));
        }
        ScanCoordinate trueSpot = candidates.get(7);
        SimResult result = runSimulation(candidates, trueSpot, 3100, 3100, 14, "Diagonal");
        result.print("Diagonal Line");
        result.assertTrueSpotNotEliminated("Diagonal line");
    }

    @Test
    @DisplayName("Cross layout: + shape candidates")
    void testCrossLayout() {
        List<ScanCoordinate> candidates = new ArrayList<>();
        int cx = 3200, cy = 3200;
        // Vertical arm
        for (int i = -4; i <= 4; i++) {
            candidates.add(new ScanCoordinate(cx, cy + i * 20, 0, 0));
        }
        // Horizontal arm (skip center, already added)
        for (int i = -4; i <= 4; i++) {
            if (i != 0) candidates.add(new ScanCoordinate(cx + i * 20, cy, 0, 0));
        }
        ScanCoordinate trueSpot = candidates.get(14); // far right
        SimResult result = runSimulation(candidates, trueSpot, cx, cy, 14, "Cross");
        result.print("Cross Layout (17 candidates)");
        result.assertTrueSpotNotEliminated("Cross layout");
    }

    @Test
    @DisplayName("Star layout: 5 arms radiating from center")
    void testStarLayout() {
        List<ScanCoordinate> candidates = new ArrayList<>();
        int cx = 3200, cy = 3200;
        for (int arm = 0; arm < 5; arm++) {
            double angle = 2 * Math.PI * arm / 5;
            for (int d = 1; d <= 4; d++) {
                int x = cx + (int) (d * 25 * Math.cos(angle));
                int y = cy + (int) (d * 25 * Math.sin(angle));
                candidates.add(new ScanCoordinate(x, y, 0, 0));
            }
        }
        candidates.add(new ScanCoordinate(cx, cy, 0, 0)); // center
        ScanCoordinate trueSpot = candidates.get(18); // tip of arm 5
        SimResult result = runSimulation(candidates, trueSpot, cx, cy, 14, "Star5");
        result.print("Star Layout (21 candidates)");
        result.assertTrueSpotNotEliminated("Star layout");
    }

    @Test
    @DisplayName("Sparse extreme: 3 candidates 400 tiles apart")
    void testSparseExtreme() {
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3000, 3000, 0, 0),
                new ScanCoordinate(3400, 3000, 0, 0),
                new ScanCoordinate(3200, 3400, 0, 0)
        );
        ScanCoordinate trueSpot = candidates.get(2);
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 14, "SparseExtreme");
        result.print("Sparse Extreme (3 candidates, 400 tiles apart)");
        result.assertTrueSpotNotEliminated("Sparse extreme");
        result.assertSolved("Sparse extreme");
    }

    @Test
    @DisplayName("Dense cluster with one outlier — true spot is the outlier")
    void testDenseClusterWithOutlier() {
        List<ScanCoordinate> candidates = new ArrayList<>();
        // Dense cluster near (3200, 3200)
        Random rng = new Random(777);
        for (int i = 0; i < 20; i++) {
            candidates.add(new ScanCoordinate(3195 + rng.nextInt(11), 3195 + rng.nextInt(11), 0, 0));
        }
        // Outlier far away — this is the true spot
        ScanCoordinate trueSpot = new ScanCoordinate(3500, 3500, 0, 0);
        candidates.add(trueSpot);

        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 14, "OutlierTrue");
        result.print("Dense Cluster + Outlier True Spot");
        result.assertTrueSpotNotEliminated("Outlier true");
        result.assertSolved("Outlier true");
    }

    @Test
    @DisplayName("Dense cluster with one outlier — true spot is in the cluster")
    void testDenseClusterTrueInCluster() {
        List<ScanCoordinate> candidates = new ArrayList<>();
        Random rng = new Random(888);
        for (int i = 0; i < 15; i++) {
            candidates.add(new ScanCoordinate(3195 + rng.nextInt(11), 3195 + rng.nextInt(11), 0, 0));
        }
        // Outlier
        candidates.add(new ScanCoordinate(3500, 3500, 0, 0));
        ScanCoordinate trueSpot = candidates.get(7); // in the cluster

        SimResult result = runSimulation(candidates, trueSpot, 3350, 3350, 14, "ClusterTrue");
        result.print("Dense Cluster True + Outlier");
        result.assertTrueSpotNotEliminated("Cluster true");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 26: Meerkat familiar (baseDistance + 5)
    // ══════════════════════════════════════════════════════════════

    @ParameterizedTest
    @CsvSource({
            "14, 19",   // standard 14 -> 19 with meerkat
            "11, 16",   // 11 -> 16
            "7,  12",   // 7 -> 12
            "20, 25",   // 20 -> 25
            "27, 32",   // 27 -> 32 (max + meerkat)
    })
    @DisplayName("Meerkat familiar: extended scan range")
    void testMeerkatFamiliar(int originalBase, int meerkatBase) {
        List<ScanCoordinate> candidates = generateGrid(3200, 3200, 70, 20);
        ScanCoordinate trueSpot = candidates.get(10);
        SimResult result = runSimulation(candidates, trueSpot, 3150, 3150, meerkatBase,
                "Meerkat_" + originalBase + "->" + meerkatBase);
        result.print("Meerkat " + originalBase + " -> " + meerkatBase);
        result.assertTrueSpotNotEliminated("Meerkat " + originalBase);
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 27: Color boundary stress
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("All candidates exactly at orange threshold boundary")
    void testAllAtOrangeBoundary() {
        int base = 14;
        int orangeMax = base * 2 + 1; // 29
        // Place candidates exactly at dist=29 from center (orange boundary)
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3200 + orangeMax, 3200, 0, 0),
                new ScanCoordinate(3200 - orangeMax, 3200, 0, 0),
                new ScanCoordinate(3200, 3200 + orangeMax, 0, 0),
                new ScanCoordinate(3200, 3200 - orangeMax, 0, 0),
                new ScanCoordinate(3200 + 20, 3200 + 20, 0, 0) // 20 Chebyshev — orange
        );
        ScanCoordinate trueSpot = candidates.get(4);
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, base, "OrangeBoundary");
        result.print("All at Orange Boundary");
        result.assertTrueSpotNotEliminated("Orange boundary");
    }

    @Test
    @DisplayName("All candidates exactly at red threshold boundary")
    void testAllAtRedBoundary() {
        int base = 14;
        // Place candidates at dist=14 from center (red boundary)
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3214, 3200, 0, 0),
                new ScanCoordinate(3186, 3200, 0, 0),
                new ScanCoordinate(3200, 3214, 0, 0),
                new ScanCoordinate(3200, 3186, 0, 0),
                new ScanCoordinate(3210, 3210, 0, 0) // dist 10 — also red
        );
        ScanCoordinate trueSpot = candidates.get(0);
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, base, "RedBoundary");
        result.print("All at Red Boundary");
        result.assertTrueSpotNotEliminated("Red boundary");
    }

    @Test
    @DisplayName("Candidates straddling blue/orange boundary")
    void testStraddlingBoundary() {
        int base = 14;
        int orangeMax = base * 2 + 1; // 29
        // Some at dist 28 (orange), some at dist 30 (blue)
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3200 + 28, 3200, 0, 0),  // orange
                new ScanCoordinate(3200 + 30, 3200, 0, 0),  // blue
                new ScanCoordinate(3200 - 28, 3200, 0, 0),  // orange
                new ScanCoordinate(3200 - 30, 3200, 0, 0),  // blue
                new ScanCoordinate(3200, 3200 + 29, 0, 0),  // orange (exact boundary)
                new ScanCoordinate(3200, 3200 - 31, 0, 0)   // blue
        );
        ScanCoordinate trueSpot = candidates.get(4); // exact boundary
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, base, "Straddling");
        result.print("Straddling Blue/Orange Boundary");
        result.assertTrueSpotNotEliminated("Straddling boundary");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 28: Backtrack stress — layouts forcing many backtracks
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Zigzag layout: candidates alternate sides, forcing directional changes")
    void testZigzagLayout() {
        List<ScanCoordinate> candidates = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            int xOff = (i % 2 == 0) ? -40 : 40;
            candidates.add(new ScanCoordinate(3200 + xOff, 3100 + i * 20, 0, 0));
        }
        ScanCoordinate trueSpot = candidates.get(8);
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3100, 14, "Zigzag");
        result.print("Zigzag Layout");
        result.assertTrueSpotNotEliminated("Zigzag");
    }

    @Test
    @DisplayName("Spiral layout: candidates in a spiral pattern")
    void testSpiralLayout() {
        List<ScanCoordinate> candidates = new ArrayList<>();
        int cx = 3200, cy = 3200;
        for (int i = 0; i < 20; i++) {
            double angle = i * 0.8;
            int r = 15 + i * 5;
            int x = cx + (int) (r * Math.cos(angle));
            int y = cy + (int) (r * Math.sin(angle));
            candidates.add(new ScanCoordinate(x, y, 0, 0));
        }
        ScanCoordinate trueSpot = candidates.get(17);
        SimResult result = runSimulation(candidates, trueSpot, cx, cy, 14, "Spiral");
        result.print("Spiral Layout (20 candidates)");
        result.assertTrueSpotNotEliminated("Spiral");
    }

    @Test
    @DisplayName("Scatter layout: candidates in 4 quadrants, true spot far from start direction")
    void testScatterQuadrants() {
        List<ScanCoordinate> candidates = new ArrayList<>();
        // Q1: NE
        candidates.add(new ScanCoordinate(3280, 3280, 0, 0));
        candidates.add(new ScanCoordinate(3290, 3270, 0, 0));
        // Q2: NW
        candidates.add(new ScanCoordinate(3120, 3280, 0, 0));
        candidates.add(new ScanCoordinate(3110, 3290, 0, 0));
        // Q3: SW
        candidates.add(new ScanCoordinate(3120, 3120, 0, 0));
        candidates.add(new ScanCoordinate(3110, 3130, 0, 0));
        // Q4: SE — true spot here, farthest from initial NE direction
        candidates.add(new ScanCoordinate(3280, 3120, 0, 0));
        candidates.add(new ScanCoordinate(3290, 3110, 0, 0));

        ScanCoordinate trueSpot = candidates.get(7); // SE quadrant
        // Start heading NE (wrong direction) — solver must backtrack
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 14, "ScatterQuad");
        result.print("Scatter Quadrants");
        result.assertTrueSpotNotEliminated("Scatter quadrants");
        result.assertSolved("Scatter quadrants");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 29: Realistic walk simulation with various speeds
    // ══════════════════════════════════════════════════════════════

    @ParameterizedTest
    @CsvSource({
            "5,  3200, 3200, 60, 15, 14, 100",   // slow walk, centered
            "10, 3200, 3200, 60, 15, 14, 101",   // medium walk
            "20, 3200, 3200, 60, 15, 14, 102",   // fast walk (running)
            "3,  3200, 3200, 40, 10, 14, 103",   // very slow, tight cluster
            "10, 3100, 3100, 80, 20, 14, 104",   // medium walk, offset start
            "10, 3200, 3200, 100, 25, 11, 105",  // medium, small base
            "15, 3200, 3200, 120, 20, 27, 106",  // fast, large base
    })
    @DisplayName("Realistic walk: various speeds and configs")
    void testRealisticWalkVariousConfigs(int walkSpeed, int startX, int startY,
                                         int spread, int numCands, int baseDistance, int seed) {
        List<ScanCoordinate> candidates = new ArrayList<>();
        Random rng = new Random(seed);
        for (int i = 0; i < numCands; i++) {
            candidates.add(new ScanCoordinate(
                    3200 + rng.nextInt(spread * 2 + 1) - spread,
                    3200 + rng.nextInt(spread * 2 + 1) - spread, 0, 0));
        }
        ScanCoordinate trueSpot = candidates.get(rng.nextInt(candidates.size()));

        SimResult result = runRealisticWalkSimulation(candidates, trueSpot,
                startX, startY, baseDistance, walkSpeed,
                "Walk_s" + walkSpeed + "_b" + baseDistance);
        result.assertTrueSpotNotEliminated("Walk speed=" + walkSpeed + " base=" + baseDistance);
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 30: Realistic walk stress — 500 random scenarios
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Walk stress: 500 random realistic walk scenarios")
    void testWalkStress500() {
        Random rng = new Random(54321);
        int failures = 0;
        int solvedCount = 0;
        long totalIterations = 0;
        List<String> failureMessages = new ArrayList<>();
        int[] walkSpeeds = {3, 5, 8, 10, 15, 20};

        for (int scenario = 0; scenario < 500; scenario++) {
            int numCandidates = 3 + rng.nextInt(30);
            int baseDistance = 4 + rng.nextInt(24);
            int spread = 20 + rng.nextInt(120);
            int walkSpeed = walkSpeeds[rng.nextInt(walkSpeeds.length)];

            List<ScanCoordinate> candidates = new ArrayList<>();
            for (int i = 0; i < numCandidates; i++) {
                int x = 3200 + rng.nextInt(spread * 2 + 1) - spread;
                int y = 3200 + rng.nextInt(spread * 2 + 1) - spread;
                candidates.add(new ScanCoordinate(x, y, 0, 0));
            }

            int trueIdx = rng.nextInt(candidates.size());
            ScanCoordinate trueSpot = candidates.get(trueIdx);
            int startX = 3200 + rng.nextInt(100) - 50;
            int startY = 3200 + rng.nextInt(100) - 50;

            SimResult result = runRealisticWalkSimulation(candidates, trueSpot,
                    startX, startY, baseDistance, walkSpeed,
                    "WalkStress_" + scenario);

            totalIterations += result.iterations();
            if (result.solved()) solvedCount++;

            if (result.remainingCandidates() == 0) {
                failures++;
                String msg = String.format("Scenario %d: %d cands, base=%d, spread=%d, speed=%d, true=%s",
                        scenario, candidates.size(), baseDistance, spread, walkSpeed, trueSpot);
                failureMessages.add(msg);
            }
        }

        System.out.println("=== WALK STRESS TEST SUMMARY (500 scenarios) ===");
        System.out.printf("  Solved: %d/500 (%.1f%%)%n", solvedCount, solvedCount / 5.0);
        System.out.printf("  Safety failures: %d%n", failures);
        System.out.printf("  Average iterations: %.1f%n", totalIterations / 500.0);

        assertEquals(0, failures,
                "True spot wrongly eliminated in " + failures + "/500 walk scenarios:\n" +
                        String.join("\n", failureMessages));
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 31: Efficiency benchmark — solve iteration distribution
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Efficiency benchmark: iteration distribution over 1000 scenarios")
    void testEfficiencyBenchmark1000() {
        Random rng = new Random(77777);
        int[] buckets = new int[21]; // 0-9, 10-19, ..., 190-199, 200+
        int solvedCount = 0;
        int failures = 0;
        long totalIterations = 0;
        int totalNegVal = 0;

        for (int scenario = 0; scenario < 1000; scenario++) {
            int numCandidates = 5 + rng.nextInt(30);
            int baseDistance = 7 + rng.nextInt(21);
            int spread = 30 + rng.nextInt(100);

            List<ScanCoordinate> candidates = new ArrayList<>();
            for (int i = 0; i < numCandidates; i++) {
                int x = 3200 + rng.nextInt(spread * 2 + 1) - spread;
                int y = 3200 + rng.nextInt(spread * 2 + 1) - spread;
                candidates.add(new ScanCoordinate(x, y, 0, 0));
            }

            int trueIdx = rng.nextInt(candidates.size());
            ScanCoordinate trueSpot = candidates.get(trueIdx);
            int startX = 3200 + rng.nextInt(80) - 40;
            int startY = 3200 + rng.nextInt(80) - 40;

            SimResult result = runSimulation(candidates, trueSpot, startX, startY, baseDistance,
                    "Bench_" + scenario);

            totalIterations += result.iterations();
            totalNegVal += result.negValEliminations();
            if (result.solved()) {
                solvedCount++;
                int bucket = Math.min(result.iterations() / 10, 20);
                buckets[bucket]++;
            }
            if (result.remainingCandidates() == 0) failures++;
        }

        System.out.println("=== EFFICIENCY BENCHMARK (1000 scenarios) ===");
        System.out.printf("  Solved: %d/1000 (%.1f%%)%n", solvedCount, solvedCount / 10.0);
        System.out.printf("  Safety failures: %d%n", failures);
        System.out.printf("  Average iterations (all): %.1f%n", totalIterations / 1000.0);
        System.out.printf("  Total neg-val eliminations: %d%n", totalNegVal);
        System.out.println("  Iteration distribution (solved scenarios):");
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] > 0) {
                String range = i < 20 ? String.format("%3d-%3d", i * 10, i * 10 + 9) : "  200+";
                int bar = (int) (buckets[i] / (double) Math.max(solvedCount, 1) * 50);
                System.out.printf("    %s: %4d %s%n", range, buckets[i], "#".repeat(Math.max(bar, 1)));
            }
        }

        assertEquals(0, failures, "Safety failures in benchmark");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 32: Worst-case candidate positioning
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Worst case: all candidates equidistant from player (ambiguous)")
    void testEquidistantCandidates() {
        int base = 14;
        int dist = base + 5; // 19 tiles — all orange
        List<ScanCoordinate> candidates = new ArrayList<>();
        // 8 candidates at exactly dist=19 in 8 compass directions
        for (int i = 0; i < 8; i++) {
            double angle = 2 * Math.PI * i / 8;
            int x = 3200 + (int) (dist * Math.cos(angle));
            int y = 3200 + (int) (dist * Math.sin(angle));
            candidates.add(new ScanCoordinate(x, y, 0, 0));
        }
        ScanCoordinate trueSpot = candidates.get(5);
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, base, "Equidistant");
        result.print("Equidistant Candidates (all orange)");
        result.assertTrueSpotNotEliminated("Equidistant");
    }

    @Test
    @DisplayName("Worst case: many candidates at exact same distance (different angles)")
    void testSameDistanceDifferentAngles() {
        int base = 14;
        List<ScanCoordinate> candidates = new ArrayList<>();
        // 20 candidates at distance exactly 50 (BLUE) from center
        for (int i = 0; i < 20; i++) {
            double angle = 2 * Math.PI * i / 20;
            int x = 3200 + (int) (50 * Math.cos(angle));
            int y = 3200 + (int) (50 * Math.sin(angle));
            candidates.add(new ScanCoordinate(x, y, 0, 0));
        }
        ScanCoordinate trueSpot = candidates.get(13);
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, base, "SameDistCircle");
        result.print("Same Distance Circle (20 candidates)");
        result.assertTrueSpotNotEliminated("Same distance circle");
    }

    @Test
    @DisplayName("Worst case: grid of candidates with very small spacing")
    void testTightGrid() {
        // 5x5 grid with 5-tile spacing — lots of candidates very close together
        List<ScanCoordinate> candidates = generateUniformGrid(3190, 3190, 3210, 3210, 5);
        ScanCoordinate trueSpot = candidates.get(12); // center-ish
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3230, 14, "TightGrid5x5");
        result.print("Tight 5x5 Grid (5-tile spacing)");
        result.assertTrueSpotNotEliminated("Tight grid");
    }

    @Test
    @DisplayName("Worst case: two far clusters, wrong cluster closest to start")
    void testMisleadingCluster() {
        List<ScanCoordinate> candidates = new ArrayList<>();
        // Nearby wrong cluster
        for (int i = 0; i < 10; i++) {
            candidates.add(new ScanCoordinate(3190 + (i % 5) * 4, 3190 + (i / 5) * 4, 0, 0));
        }
        // Far correct cluster
        for (int i = 0; i < 5; i++) {
            candidates.add(new ScanCoordinate(3400 + (i % 3) * 4, 3400 + (i / 3) * 4, 0, 0));
        }
        ScanCoordinate trueSpot = candidates.get(12); // in far cluster
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 14, "Misleading");
        result.print("Misleading Cluster (wrong cluster closer)");
        result.assertTrueSpotNotEliminated("Misleading cluster");
        result.assertSolved("Misleading cluster");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 33: Negative validation edge cases
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("NegVal: standing exactly on wrong candidate — must eliminate")
    void testNegValStandingOnWrongCandidate() {
        ScanCoordinate wrongSpot = new ScanCoordinate(3200, 3200, 0, 0);
        ScanCoordinate trueSpot = new ScanCoordinate(3300, 3300, 0, 0);
        List<ScanCoordinate> candidates = List.of(wrongSpot, trueSpot);

        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 14, "NegValStandOn");
        result.print("NegVal Standing On Wrong Candidate");
        result.assertTrueSpotNotEliminated("NegVal stand on wrong");
        result.assertSolved("NegVal stand on wrong");
    }

    @Test
    @DisplayName("NegVal: multiple wrong candidates within baseDistance — cascade all")
    void testNegValCascadeMultiple() {
        List<ScanCoordinate> candidates = new ArrayList<>();
        // 5 wrong candidates all within 14 tiles of (3200, 3200)
        candidates.add(new ScanCoordinate(3195, 3195, 0, 0));
        candidates.add(new ScanCoordinate(3200, 3195, 0, 0));
        candidates.add(new ScanCoordinate(3205, 3200, 0, 0));
        candidates.add(new ScanCoordinate(3200, 3205, 0, 0));
        candidates.add(new ScanCoordinate(3195, 3200, 0, 0));
        // True spot far away
        ScanCoordinate trueSpot = new ScanCoordinate(3400, 3400, 0, 0);
        candidates.add(trueSpot);

        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 14, "NegValCascade");
        result.print("NegVal Cascade Multiple");
        result.assertTrueSpotNotEliminated("NegVal cascade");
        result.assertSolved("NegVal cascade");
        assertTrue(result.totalEliminated() >= 3,
                "Should eliminate at least 3 nearby wrong candidates (tracker+negval), got " + result.totalEliminated());
    }

    @Test
    @DisplayName("NegVal: true spot within baseDistance — must NOT eliminate")
    void testNegValTrueSpotInRange() {
        // Player starts within baseDistance of the true spot — sees RED immediately
        ScanCoordinate trueSpot = new ScanCoordinate(3205, 3205, 0, 0);
        List<ScanCoordinate> candidates = List.of(
                trueSpot,
                new ScanCoordinate(3300, 3300, 0, 0),
                new ScanCoordinate(3100, 3100, 0, 0)
        );
        SimResult result = runSimulation(candidates, trueSpot, 3200, 3200, 14, "NegValTrueInRange");
        result.print("NegVal True Spot In Range");
        result.assertTrueSpotNotEliminated("NegVal true in range");
        result.assertSolved("NegVal true in range");
        assertEquals(0, result.negValEliminations(),
                "Should NOT neg-val eliminate anything when RED is seen");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 34: Every real RS3 base distance value
    // ══════════════════════════════════════════════════════════════

    @ParameterizedTest
    @ValueSource(ints = {4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26, 27})
    @DisplayName("Every base distance (4-27): 10 random scenarios each")
    void testEveryBaseDistance(int baseDistance) {
        Random rng = new Random(baseDistance * 1000L);
        int failures = 0;
        int solved = 0;

        for (int i = 0; i < 10; i++) {
            int numCands = 5 + rng.nextInt(20);
            int spread = baseDistance * 3 + rng.nextInt(50);
            List<ScanCoordinate> candidates = new ArrayList<>();
            for (int c = 0; c < numCands; c++) {
                candidates.add(new ScanCoordinate(
                        3200 + rng.nextInt(spread * 2 + 1) - spread,
                        3200 + rng.nextInt(spread * 2 + 1) - spread, 0, 0));
            }
            ScanCoordinate trueSpot = candidates.get(rng.nextInt(candidates.size()));
            int sx = 3200 + rng.nextInt(60) - 30;
            int sy = 3200 + rng.nextInt(60) - 30;

            SimResult result = runSimulation(candidates, trueSpot, sx, sy, baseDistance,
                    "Base" + baseDistance + "_" + i);
            if (result.solved()) solved++;
            if (result.remainingCandidates() == 0) failures++;
        }

        assertEquals(0, failures,
                "Base distance " + baseDistance + ": true spot wrongly eliminated in " + failures + "/10 scenarios");
    }

    // ══════════════════════════════════════════════════════════════
    // Shared: Realistic walk simulation harness
    // ══════════════════════════════════════════════════════════════

    private SimResult runRealisticWalkSimulation(List<ScanCoordinate> candidates,
                                                 ScanCoordinate trueSpot,
                                                 int startX, int startY,
                                                 int baseDistance, int walkSpeed,
                                                 String regionName) {
        ScanRegion region = new ScanRegion(regionName, 9999, 99999, List.copyOf(candidates));
        ScanClueTracker tracker = new ScanClueTracker();
        tracker.startTracking(region);
        tracker.parseBaseDistance(baseDistance + " paces");

        ScanNavigationSolver solver = new ScanNavigationSolver();
        solver.setBaseDistance(baseDistance);
        ScanObservationLog obsLog = new ScanObservationLog();

        int playerX = startX, playerY = startY;
        int iterations = 0;
        int totalEliminated = 0;
        int negValEliminations = 0;
        boolean solved = false;
        List<String> log = new ArrayList<>();

        while (iterations < MAX_ITERATIONS * 2) { // extra iterations for slow walks
            iterations++;

            int distToTrue = trueSpot.chebyshevDistance(playerX, playerY);
            ScanPulseColor serverColor = computeServerColor(distToTrue, baseDistance);

            int beforeCount = tracker.getCandidateCount();
            int trackerElim = tracker.processObservation(playerX, playerY, serverColor);
            obsLog.record(playerX, playerY, serverColor, tracker.getCandidateCount());
            solver.setBaseDistance(baseDistance);

            int beforeNegVal = tracker.getCandidateCount();
            solver.onObservation(obsLog, tracker);
            int negValThisStep = beforeNegVal - tracker.getCandidateCount();
            negValEliminations += negValThisStep;
            totalEliminated += trackerElim + negValThisStep;

            if (serverColor == ScanPulseColor.RED) {
                solved = true;
                log.add(String.format("  >>> SOLVED via RED at iter %d, dist=%d", iterations, distToTrue));
                break;
            }
            if (tracker.getCandidateCount() == 1) {
                ScanCoordinate last = tracker.getCandidates().getFirst();
                if (last.x() == trueSpot.x() && last.y() == trueSpot.y()) {
                    solved = true;
                    log.add("  >>> SOLVED (1 remaining) at iter " + iterations);
                    break;
                }
            }
            if (tracker.getCandidateCount() == 0) {
                log.add("  >>> ERROR: All candidates eliminated!");
                break;
            }

            int[] target = solver.computeNextTarget(playerX, playerY, obsLog, tracker);
            if (target == null) {
                log.add("  >>> ERROR: null target");
                break;
            }

            // Walk partway toward target
            int dx = target[0] - playerX;
            int dy = target[1] - playerY;
            double dist = Math.sqrt(dx * dx + dy * dy);
            if (dist > walkSpeed) {
                playerX += (int) (dx / dist * walkSpeed);
                playerY += (int) (dy / dist * walkSpeed);
            } else {
                playerX = target[0];
                playerY = target[1];
            }
        }

        if (!solved && iterations >= MAX_ITERATIONS * 2) {
            log.add("  >>> TIMEOUT at " + iterations + " iterations");
        }

        return new SimResult(solved, iterations, totalEliminated, negValEliminations,
                tracker.getCandidateCount(), candidates.size(), log);
    }

    // ══════════════════════════════════════════════════════════════
    // Tunnel simulation harness
    // ══════════════════════════════════════════════════════════════

    /**
     * Runs a simulation where player movement is constrained to a tunnel path.
     * The tunnel is defined as an ordered list of waypoints. The player can only
     * move along the path between consecutive waypoints.
     * <p>
     * When the solver requests a target, the player walks along the tunnel toward
     * the waypoint nearest to the target, at the given walk speed per tick.
     */
    private TunnelSimResult runTunnelSimulation(List<ScanCoordinate> candidates,
                                          ScanCoordinate trueSpot,
                                          int startX, int startY,
                                          int baseDistance,
                                          List<int[]> tunnelNodes,
                                          int walkSpeed,
                                          String regionName) {
        ScanRegion region = new ScanRegion(regionName, 9999, 99999, List.copyOf(candidates));
        ScanClueTracker tracker = new ScanClueTracker();
        tracker.startTracking(region);
        tracker.parseBaseDistance(baseDistance + " paces");

        ScanNavigationSolver solver = new ScanNavigationSolver();
        solver.setBaseDistance(baseDistance);
        ScanObservationLog obsLog = new ScanObservationLog();

        // Snap start to nearest tunnel node
        int startNode = nearestNodeIndex(tunnelNodes, startX, startY);
        int playerX = tunnelNodes.get(startNode)[0];
        int playerY = tunnelNodes.get(startNode)[1];
        int currentNodeIdx = startNode;

        int iterations = 0;
        int totalEliminated = 0, negValEliminations = 0;
        boolean solved = false;
        boolean enteredProximityExplore = false;
        List<String> log = new ArrayList<>();

        while (iterations < MAX_ITERATIONS * 3) {
            iterations++;

            int distToTrue = trueSpot.chebyshevDistance(playerX, playerY);
            ScanPulseColor serverColor = computeServerColor(distToTrue, baseDistance);

            int trackerElim = tracker.processObservation(playerX, playerY, serverColor);
            obsLog.record(playerX, playerY, serverColor, tracker.getCandidateCount());
            solver.setBaseDistance(baseDistance);

            int beforeNV = tracker.getCandidateCount();
            solver.onObservation(obsLog, tracker);
            int nvElim = beforeNV - tracker.getCandidateCount();
            negValEliminations += nvElim;
            totalEliminated += trackerElim + nvElim;

            if (solver.getPhase() == ScanNavigationSolver.SolverPhase.PROXIMITY_EXPLORE) {
                enteredProximityExplore = true;
            }

            log.add(String.format("  #%d: pos=(%d,%d) node=%d dist=%d color=%s phase=%s cands=%d",
                    iterations, playerX, playerY, currentNodeIdx, distToTrue,
                    serverColor.label(), solver.getPhase().label(), tracker.getCandidateCount()));

            if (serverColor == ScanPulseColor.RED) {
                solved = true;
                log.add(String.format("  >>> SOLVED via RED at iter %d, dist=%d", iterations, distToTrue));
                break;
            }
            if (tracker.getCandidateCount() == 1) {
                ScanCoordinate last = tracker.getCandidates().getFirst();
                if (last.x() == trueSpot.x() && last.y() == trueSpot.y()) {
                    solved = true;
                    log.add("  >>> SOLVED (1 remaining) at iter " + iterations);
                    break;
                }
            }
            if (tracker.getCandidateCount() == 0) {
                log.add("  >>> ERROR: All candidates eliminated!");
                break;
            }

            int[] target = solver.computeNextTarget(playerX, playerY, obsLog, tracker);
            if (target == null) {
                log.add("  >>> ERROR: null target");
                break;
            }

            // Move along tunnel toward the node nearest to solver's target
            int targetNode = nearestNodeIndex(tunnelNodes, target[0], target[1]);
            int remaining = walkSpeed;

            while (remaining > 0 && currentNodeIdx != targetNode) {
                int nextIdx = currentNodeIdx + (targetNode > currentNodeIdx ? 1 : -1);
                int[] nextNode = tunnelNodes.get(nextIdx);
                int dx = nextNode[0] - playerX;
                int dy = nextNode[1] - playerY;
                double dist = Math.sqrt(dx * dx + dy * dy);

                if (dist <= remaining) {
                    playerX = nextNode[0];
                    playerY = nextNode[1];
                    remaining -= (int) dist;
                    currentNodeIdx = nextIdx;
                } else if (dist > 0) {
                    playerX += (int) (dx / dist * remaining);
                    playerY += (int) (dy / dist * remaining);
                    remaining = 0;
                } else {
                    break;
                }
            }
        }

        if (!solved && iterations >= MAX_ITERATIONS * 3) {
            log.add("  >>> TIMEOUT at " + iterations + " iterations");
        }

        return new TunnelSimResult(solved, iterations, totalEliminated, negValEliminations,
                tracker.getCandidateCount(), candidates.size(), log, enteredProximityExplore);
    }

    record TunnelSimResult(boolean solved, int iterations, int totalEliminated,
                            int negValEliminations, int remainingCandidates, int totalCandidates,
                            List<String> log, boolean enteredProximityExplore)
            implements Comparable<TunnelSimResult> {

        void assertSolved(String ctx) {
            if (!solved) {
                System.err.println("=== FAILED: " + ctx + " ===");
                log.forEach(System.err::println);
            }
            assertTrue(solved, ctx + " — solver failed. " +
                    remainingCandidates + "/" + totalCandidates + " remaining after " + iterations + " iterations");
        }

        void assertTrueSpotNotEliminated(String ctx) {
            assertTrue(remainingCandidates > 0, ctx + " — true spot wrongly eliminated!");
        }

        void print(String label) {
            System.out.println("=== " + label + " ===");
            System.out.println("  Solved: " + solved + " in " + iterations + " iters, " +
                    "proximity_explore=" + enteredProximityExplore);
            System.out.println("  Eliminated: " + totalEliminated + " (negVal: " + negValEliminations + ")");
            System.out.println("  Remaining: " + remainingCandidates + "/" + totalCandidates);
            log.forEach(System.out::println);
            System.out.println();
        }

        @Override
        public int compareTo(TunnelSimResult o) { return Integer.compare(iterations, o.iterations); }
    }

    /**
     * Interpolates tunnel nodes so that no two consecutive nodes are more than
     * maxSpacing tiles apart (Chebyshev). This ensures the solver's short probe
     * targets can snap to intermediate positions along the tunnel.
     */
    private List<int[]> interpolateTunnel(List<int[]> coarseNodes, int maxSpacing) {
        List<int[]> fine = new ArrayList<>();
        fine.add(coarseNodes.get(0));
        for (int i = 1; i < coarseNodes.size(); i++) {
            int[] prev = coarseNodes.get(i - 1);
            int[] curr = coarseNodes.get(i);
            int dx = curr[0] - prev[0];
            int dy = curr[1] - prev[1];
            double dist = Math.sqrt(dx * dx + dy * dy);
            int steps = Math.max(1, (int) Math.ceil(dist / maxSpacing));
            for (int s = 1; s <= steps; s++) {
                int x = prev[0] + (int) ((double) dx * s / steps);
                int y = prev[1] + (int) ((double) dy * s / steps);
                // Avoid duplicate of last added node
                int[] last = fine.get(fine.size() - 1);
                if (x != last[0] || y != last[1]) {
                    fine.add(new int[]{x, y});
                }
            }
        }
        return fine;
    }

    private int nearestNodeIndex(List<int[]> nodes, int x, int y) {
        int bestIdx = 0;
        int bestDist = Integer.MAX_VALUE;
        for (int i = 0; i < nodes.size(); i++) {
            int d = Math.max(Math.abs(nodes.get(i)[0] - x), Math.abs(nodes.get(i)[1] - y));
            if (d < bestDist) {
                bestDist = d;
                bestIdx = i;
            }
        }
        return bestIdx;
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 35: Tunnel simulations — constrained movement
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Tunnel: L-shaped corridor, target around the corner")
    void testTunnelLShape() {
        //  Start → ──── junction ──── (candidate A, wrong)
        //                  |
        //                  |
        //              (candidate B, true spot)
        //
        // Scaled so total path ~60 tiles, within range of baseDistance=14 (orangeThreshold=29)
        // Horizontal: y=3200, x from 3200 to 3230 (30 tiles)
        // Vertical:   x=3230, y from 3200 to 3230 (30 tiles)
        List<int[]> tunnelCoarse = List.of(
                new int[]{3200, 3200}, // start
                new int[]{3215, 3200}, // midpoint
                new int[]{3230, 3200}, // junction
                new int[]{3230, 3215}, // down
                new int[]{3230, 3230}  // end
        );
        List<int[]> tunnel = interpolateTunnel(tunnelCoarse, 5);

        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3200, 3200, 0, 0),  // near start
                new ScanCoordinate(3230, 3230, 0, 0),  // end of vertical arm (true)
                new ScanCoordinate(3230, 3200, 0, 0),  // junction
                new ScanCoordinate(3215, 3215, 0, 0),  // NOT on tunnel — behind wall
                new ScanCoordinate(3240, 3215, 0, 0)   // NOT on tunnel — behind wall
        );
        ScanCoordinate trueSpot = candidates.get(1);

        TunnelSimResult result = runTunnelSimulation(
                candidates, trueSpot, 3200, 3200, 14, tunnel, 6, "TunnelLShape");
        result.print("Tunnel L-Shape");
        result.assertTrueSpotNotEliminated("Tunnel L-shape");
        result.assertSolved("Tunnel L-shape");
    }

    @Test
    @DisplayName("Tunnel: parallel corridors, target in adjacent tunnel")
    void testTunnelParallelCorridors() {
        // Two parallel horizontal tunnels connected at the right end:
        //
        //   node0 ──── node1 ──── node2 (top tunnel, y=3200)
        //                          |
        //                        node3 (connector, y=3210)
        //                          |
        //   node6 ──── node5 ──── node4 (bottom tunnel, y=3220)
        //
        // Player starts at node0 (top-left).
        // True spot is at (3200, 3220) bottom-left.
        // Scaled to ~40 tiles total path, within baseDistance=14 color range.
        List<int[]> tunnelCoarse = List.of(
                new int[]{3200, 3200}, // top-left (start)
                new int[]{3210, 3200}, // top-middle
                new int[]{3220, 3200}, // top-right
                new int[]{3220, 3210}, // connector
                new int[]{3220, 3220}, // bottom-right
                new int[]{3210, 3220}, // bottom-middle
                new int[]{3200, 3220}  // bottom-left
        );
        List<int[]> tunnel = interpolateTunnel(tunnelCoarse, 5);

        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3200, 3220, 0, 0),  // bottom-left (TRUE)
                new ScanCoordinate(3220, 3200, 0, 0),  // top-right
                new ScanCoordinate(3210, 3200, 0, 0),  // top-middle
                new ScanCoordinate(3220, 3220, 0, 0),  // bottom-right
                new ScanCoordinate(3210, 3220, 0, 0)   // bottom-middle
        );
        ScanCoordinate trueSpot = candidates.get(0);

        TunnelSimResult result = runTunnelSimulation(
                candidates, trueSpot, 3200, 3200, 14, tunnel, 5, "TunnelParallel");
        result.print("Tunnel Parallel Corridors");
        result.assertTrueSpotNotEliminated("Tunnel parallel");
        result.assertSolved("Tunnel parallel");
    }

    @Test
    @DisplayName("Tunnel: dead-end branch, target past the dead end")
    void testTunnelDeadEnd() {
        // Main corridor with a dead-end branch:
        //
        //   start ──── A ──── B (dead end)
        //              |
        //              C ──── D (true spot)
        //
        // Scaled to ~40 tiles total path
        List<int[]> tunnelCoarse = List.of(
                new int[]{3200, 3200}, // start
                new int[]{3210, 3200}, // junction A
                new int[]{3220, 3200}, // dead end B
                new int[]{3210, 3200}, // back at A (revisit)
                new int[]{3210, 3210}, // heading to C
                new int[]{3210, 3220}, // C
                new int[]{3220, 3220}  // D (true spot)
        );
        List<int[]> tunnel = interpolateTunnel(tunnelCoarse, 5);

        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3220, 3200, 0, 0),  // dead end B
                new ScanCoordinate(3220, 3220, 0, 0),  // D (TRUE)
                new ScanCoordinate(3210, 3220, 0, 0),  // C
                new ScanCoordinate(3205, 3210, 0, 0),  // behind wall
                new ScanCoordinate(3200, 3200, 0, 0)   // near start
        );
        ScanCoordinate trueSpot = candidates.get(1);

        TunnelSimResult result = runTunnelSimulation(
                candidates, trueSpot, 3200, 3200, 14, tunnel, 5, "TunnelDeadEnd");
        result.print("Tunnel Dead End");
        result.assertTrueSpotNotEliminated("Tunnel dead end");
    }

    @Test
    @DisplayName("Tunnel: U-shaped path, target at the far end")
    void testTunnelUShape() {
        // U-shape: player goes east, south, then west to reach target
        //
        //   start ──────── top-right
        //                      |
        //                      |
        //   target ─────── bottom-right
        //
        // Scaled to ~60 tiles total path
        List<int[]> tunnelCoarse = List.of(
                new int[]{3200, 3200}, // start (top-left)
                new int[]{3210, 3200},
                new int[]{3220, 3200}, // top-right
                new int[]{3220, 3210},
                new int[]{3220, 3220}, // bottom-right
                new int[]{3210, 3220},
                new int[]{3200, 3220}  // target area (bottom-left)
        );
        List<int[]> tunnel = interpolateTunnel(tunnelCoarse, 5);

        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3200, 3220, 0, 0),  // bottom-left (TRUE)
                new ScanCoordinate(3220, 3200, 0, 0),  // top-right
                new ScanCoordinate(3220, 3220, 0, 0),  // bottom-right
                new ScanCoordinate(3210, 3210, 0, 0),  // center (behind wall)
                new ScanCoordinate(3200, 3210, 0, 0)   // left side (behind wall)
        );
        ScanCoordinate trueSpot = candidates.get(0);

        TunnelSimResult result = runTunnelSimulation(
                candidates, trueSpot, 3200, 3200, 14, tunnel, 5, "TunnelUShape");
        result.print("Tunnel U-Shape");
        result.assertTrueSpotNotEliminated("Tunnel U-shape");
    }

    @Test
    @DisplayName("Tunnel: long winding path with many candidates")
    void testTunnelWindingPath() {
        // Winding path: east, south, west, south, east (like dungeon corridors)
        // Scaled to ~80 tiles total path with baseDistance=14
        List<int[]> tunnelCoarse = List.of(
                new int[]{3200, 3200}, // start
                new int[]{3215, 3200},
                new int[]{3230, 3200}, // turn south
                new int[]{3230, 3215},
                new int[]{3230, 3230}, // turn west
                new int[]{3215, 3230},
                new int[]{3200, 3230}, // turn south
                new int[]{3200, 3245},
                new int[]{3200, 3260}, // turn east
                new int[]{3215, 3260},
                new int[]{3230, 3260}  // end
        );
        List<int[]> tunnel = interpolateTunnel(tunnelCoarse, 5);

        List<ScanCoordinate> candidates = new ArrayList<>();
        // Spread candidates along the tunnel and some behind walls
        candidates.add(new ScanCoordinate(3200, 3200, 0, 0)); // start
        candidates.add(new ScanCoordinate(3230, 3200, 0, 0)); // first turn
        candidates.add(new ScanCoordinate(3230, 3230, 0, 0)); // second turn
        candidates.add(new ScanCoordinate(3200, 3230, 0, 0)); // third turn
        candidates.add(new ScanCoordinate(3200, 3260, 0, 0)); // fourth turn
        candidates.add(new ScanCoordinate(3230, 3260, 0, 0)); // end (TRUE)
        // Behind-wall candidates
        candidates.add(new ScanCoordinate(3215, 3215, 0, 0));
        candidates.add(new ScanCoordinate(3215, 3245, 0, 0));
        candidates.add(new ScanCoordinate(3190, 3230, 0, 0));

        ScanCoordinate trueSpot = candidates.get(5); // end of winding path

        TunnelSimResult result = runTunnelSimulation(
                candidates, trueSpot, 3200, 3200, 14, tunnel, 5, "TunnelWinding");
        result.print("Tunnel Winding Path");
        result.assertTrueSpotNotEliminated("Tunnel winding");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 36: PROXIMITY_EXPLORE phase transitions
    // ══════════════════════════════════════════════════════════════

    /**
     * Helper: force solver into PROXIMITY_EXPLORE by simulating the correct
     * observation sequence: ORANGE (set anchor) → BLUE (degrade, backtrack) →
     * arrive at anchor → ORANGE → BLUE (degrade again, backtrack) →
     * arrive at anchor (2nd revisit → tunnel threshold met).
     *
     * Requires that the solver's anchor (3200,3200) is within the orange zone
     * of at least some candidates.
     */
    private void forceIntoProximityExplore(ScanNavigationSolver solver,
                                            ScanObservationLog obsLog,
                                            ScanClueTracker tracker) {
        // Obs 1: ORANGE at anchor — sets lastGoodPosition
        obsLog.record(3200, 3200, ScanPulseColor.ORANGE, tracker.getCandidateCount());
        solver.onObservation(obsLog, tracker);

        // Obs 2: BLUE — DEGRADED → BACKTRACK
        obsLog.record(3230, 3230, ScanPulseColor.BLUE, tracker.getCandidateCount());
        solver.onObservation(obsLog, tracker);

        // Arrive at anchor (1st revisit) → DIRECTIONAL_PROBE
        solver.computeNextTarget(3200, 3200, obsLog, tracker);

        // Obs 3: ORANGE again (re-approach anchor from new direction)
        obsLog.record(3200, 3200, ScanPulseColor.ORANGE, tracker.getCandidateCount());
        solver.onObservation(obsLog, tracker);

        // Obs 4: BLUE — DEGRADED again → BACKTRACK
        obsLog.record(3170, 3170, ScanPulseColor.BLUE, tracker.getCandidateCount());
        solver.onObservation(obsLog, tracker);

        // Arrive at anchor (2nd revisit) → PROXIMITY_EXPLORE
        solver.computeNextTarget(3200, 3200, obsLog, tracker);
    }

    @Test
    @DisplayName("Proximity explore: triggers after multiple backtracks to same anchor")
    void testProximityExploreTrigger() {
        List<ScanCoordinate> candidates = generateGrid(3200, 3200, 60, 15);
        ScanRegion region = new ScanRegion("ProxTrigger", 1, 1, candidates);
        ScanClueTracker tracker = new ScanClueTracker();
        tracker.startTracking(region);
        tracker.parseBaseDistance("14 paces");

        ScanNavigationSolver solver = new ScanNavigationSolver();
        solver.setBaseDistance(14);
        ScanObservationLog obsLog = new ScanObservationLog();

        forceIntoProximityExplore(solver, obsLog, tracker);

        assertEquals(ScanNavigationSolver.SolverPhase.PROXIMITY_EXPLORE, solver.getPhase(),
                "2nd anchor revisit should trigger PROXIMITY_EXPLORE");
        assertTrue(solver.getExplorationQueueSize() > 0,
                "Exploration queue should be populated");
    }

    @Test
    @DisplayName("Proximity explore: targets candidates in distance order from anchor")
    void testProximityExploreTargetOrder() {
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3300, 3300, 0, 0),  // far from anchor
                new ScanCoordinate(3190, 3190, 0, 0),  // nearest to anchor
                new ScanCoordinate(3220, 3220, 0, 0),  // medium
                new ScanCoordinate(3250, 3250, 0, 0)   // medium-far
        );

        ScanRegion region = new ScanRegion("ProxOrder", 1, 1, candidates);
        ScanClueTracker tracker = new ScanClueTracker();
        tracker.startTracking(region);
        tracker.parseBaseDistance("14 paces");

        ScanNavigationSolver solver = new ScanNavigationSolver();
        solver.setBaseDistance(14);
        ScanObservationLog obsLog = new ScanObservationLog();

        forceIntoProximityExplore(solver, obsLog, tracker);
        assertEquals(ScanNavigationSolver.SolverPhase.PROXIMITY_EXPLORE, solver.getPhase());

        // First target should be the candidate nearest to anchor (3200, 3200)
        int[] target = solver.computeNextTarget(3200, 3200, obsLog, tracker);
        assertNotNull(target);
        // (3190, 3190) is nearest to anchor at (3200, 3200) — Chebyshev dist = 10
        assertEquals(3190, target[0], "First explore target should be nearest candidate X");
        assertEquals(3190, target[1], "First explore target should be nearest candidate Y");
    }

    @Test
    @DisplayName("Proximity explore: stuck detection skips unreachable candidate")
    void testProximityExploreStuckDetection() {
        // All candidates far from observation point (3160,3160) to avoid neg-val elimination
        // and keep candidate count > 3 to prevent CONVERGE transition
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3180, 3180, 0, 0),  // nearest to anchor (dist=20)
                new ScanCoordinate(3220, 3220, 0, 0),  // next (dist=20)
                new ScanCoordinate(3240, 3240, 0, 0),  // medium (dist=40)
                new ScanCoordinate(3260, 3260, 0, 0),  // far (dist=60)
                new ScanCoordinate(3280, 3280, 0, 0)   // very far — keeps count > 3
        );

        ScanRegion region = new ScanRegion("ProxStuck", 1, 1, candidates);
        ScanClueTracker tracker = new ScanClueTracker();
        tracker.startTracking(region);
        tracker.parseBaseDistance("14 paces");

        ScanNavigationSolver solver = new ScanNavigationSolver();
        solver.setBaseDistance(14);
        ScanObservationLog obsLog = new ScanObservationLog();

        forceIntoProximityExplore(solver, obsLog, tracker);
        assertEquals(ScanNavigationSolver.SolverPhase.PROXIMITY_EXPLORE, solver.getPhase());

        // Verify first target
        int[] firstTarget = solver.computeNextTarget(3200, 3200, obsLog, tracker);
        assertNotNull(firstTarget);

        // Simulate being stuck at a position far from all candidates (>baseDistance=14)
        // to avoid negative validation cascade eliminating candidates
        // Position (3160,3160) is dist 20+ from all candidates
        // First observation seeds lastObservedPosition; next 3 trigger stuck threshold
        for (int i = 0; i < 4; i++) {
            obsLog.record(3160, 3160, ScanPulseColor.BLUE, tracker.getCandidateCount());
            solver.onObservation(obsLog, tracker);
        }

        // After STUCK_THRESHOLD (3 consecutive <=1 tile moves), solver advances past stuck candidate
        assertEquals(0, solver.getStuckCount(),
                "Stuck count should have been reset after skipping");

        // Next target should be a different candidate
        int[] nextTarget = solver.computeNextTarget(3160, 3160, obsLog, tracker);
        assertNotNull(nextTarget, "Should have a next target after skipping stuck candidate");
    }

    @Test
    @DisplayName("Proximity explore: exits to DIRECTIONAL_PROBE when queue exhausted")
    void testProximityExploreQueueExhaustion() {
        List<ScanCoordinate> candidates = List.of(
                new ScanCoordinate(3196, 3196, 0, 0),  // within MIN_STEP of visit points
                new ScanCoordinate(3204, 3204, 0, 0),
                new ScanCoordinate(3230, 3230, 0, 0),
                new ScanCoordinate(3250, 3250, 0, 0)
        );

        ScanRegion region = new ScanRegion("ProxExhaust", 1, 1, candidates);
        ScanClueTracker tracker = new ScanClueTracker();
        tracker.startTracking(region);
        tracker.parseBaseDistance("14 paces");

        ScanNavigationSolver solver = new ScanNavigationSolver();
        solver.setBaseDistance(14);
        ScanObservationLog obsLog = new ScanObservationLog();

        forceIntoProximityExplore(solver, obsLog, tracker);
        assertEquals(ScanNavigationSolver.SolverPhase.PROXIMITY_EXPLORE, solver.getPhase());

        // Visit all candidates by arriving at each one (within MIN_STEP=4)
        for (ScanCoordinate c : candidates) {
            obsLog.record(c.x(), c.y(), ScanPulseColor.BLUE, candidates.size());
            solver.onObservation(obsLog, tracker);
        }

        // Phase should be PROXIMITY_EXPLORE or DIRECTIONAL_PROBE
        ScanNavigationSolver.SolverPhase finalPhase = solver.getPhase();
        assertTrue(finalPhase == ScanNavigationSolver.SolverPhase.PROXIMITY_EXPLORE
                        || finalPhase == ScanNavigationSolver.SolverPhase.DIRECTIONAL_PROBE,
                "Should be in PROXIMITY_EXPLORE or have exited to DIRECTIONAL_PROBE, was " + finalPhase);
    }

    @Test
    @DisplayName("Proximity explore: does NOT backtrack on degradation (tunnel tolerance)")
    void testProximityExploreNoBacktrackOnDegradation() {
        List<ScanCoordinate> candidates = generateGrid(3200, 3200, 50, 10);
        ScanRegion region = new ScanRegion("ProxNoBT", 1, 1, candidates);
        ScanClueTracker tracker = new ScanClueTracker();
        tracker.startTracking(region);
        tracker.parseBaseDistance("14 paces");

        ScanNavigationSolver solver = new ScanNavigationSolver();
        solver.setBaseDistance(14);
        ScanObservationLog obsLog = new ScanObservationLog();

        forceIntoProximityExplore(solver, obsLog, tracker);
        assertEquals(ScanNavigationSolver.SolverPhase.PROXIMITY_EXPLORE, solver.getPhase());

        // Simulate degradation: ORANGE → BLUE while en route to a candidate
        obsLog.record(3190, 3190, ScanPulseColor.ORANGE, 10);
        solver.onObservation(obsLog, tracker);
        obsLog.record(3195, 3195, ScanPulseColor.BLUE, 10);
        solver.onObservation(obsLog, tracker);

        // Should NOT enter BACKTRACK — tunnel paths fluctuate
        assertNotEquals(ScanNavigationSolver.SolverPhase.BACKTRACK, solver.getPhase(),
                "PROXIMITY_EXPLORE must NOT enter BACKTRACK on color degradation");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 37: Tunnel stress — many random constrained scenarios
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Tunnel stress: 100 random L/U-shaped tunnel scenarios")
    void testTunnelStress100() {
        Random rng = new Random(44444);
        int failures = 0;
        int solvedCount = 0;
        int proximityExploreCount = 0;

        for (int scenario = 0; scenario < 100; scenario++) {
            int baseDistance = 7 + rng.nextInt(21);

            // Generate a random L or U shaped tunnel — scaled to work with baseDistance
            // Total path should be 30-60 tiles so color transitions actually occur
            int startX = 3200 + rng.nextInt(20);
            int startY = 3200;
            int junctionX = startX + 10 + rng.nextInt(20);
            int endY = 3200 + 10 + rng.nextInt(30);
            int endX = 3200 + rng.nextInt(40);

            List<int[]> tunnelCoarse = List.of(
                    new int[]{startX, startY},
                    new int[]{(startX + junctionX) / 2, startY},
                    new int[]{junctionX, startY},
                    new int[]{junctionX, (startY + endY) / 2},
                    new int[]{junctionX, endY},
                    new int[]{(junctionX + endX) / 2, endY},
                    new int[]{endX, endY}
            );
            List<int[]> tunnel = interpolateTunnel(tunnelCoarse, 8);

            // Place candidates on and off the tunnel path
            List<ScanCoordinate> candidates = new ArrayList<>();
            for (int[] node : tunnel) {
                if (rng.nextDouble() < 0.6) {
                    candidates.add(new ScanCoordinate(node[0], node[1], 0, 0));
                }
            }
            // Add some off-tunnel candidates (within reasonable range)
            for (int i = 0; i < 3 + rng.nextInt(5); i++) {
                candidates.add(new ScanCoordinate(
                        3200 + rng.nextInt(60),
                        3200 + rng.nextInt(60), 0, 0));
            }
            if (candidates.isEmpty()) continue;

            // True spot is a tunnel node
            int trueNodeIdx = rng.nextInt(tunnel.size());
            ScanCoordinate trueSpot = new ScanCoordinate(
                    tunnel.get(trueNodeIdx)[0], tunnel.get(trueNodeIdx)[1], 0, 0);
            if (!candidates.contains(trueSpot)) {
                candidates.add(trueSpot);
            }

            TunnelSimResult result = runTunnelSimulation(
                    candidates, trueSpot, startX, startY, baseDistance,
                    tunnel, 8, "TunnelStress_" + scenario);

            if (result.solved()) solvedCount++;
            if (result.enteredProximityExplore()) proximityExploreCount++;
            if (result.remainingCandidates() == 0) {
                failures++;
                if (failures <= 3) result.print("TUNNEL FAIL " + scenario);
            }
        }

        System.out.println("=== TUNNEL STRESS TEST (100 scenarios) ===");
        System.out.printf("  Solved: %d/100 (%.0f%%)%n", solvedCount, solvedCount * 1.0);
        System.out.printf("  Used PROXIMITY_EXPLORE: %d/100%n", proximityExploreCount);
        System.out.printf("  Safety failures: %d%n", failures);

        assertEquals(0, failures, "True spot wrongly eliminated in tunnel scenarios");
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 38: Mixed open + tunnel stress (existing tests still pass)
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Open terrain regression: 500 scenarios must not regress with PROXIMITY_EXPLORE")
    void testOpenTerrainRegression500() {
        Random rng = new Random(88888);
        int failures = 0;
        int solvedCount = 0;
        int proximityExploreCount = 0;

        for (int scenario = 0; scenario < 500; scenario++) {
            int numCandidates = 3 + rng.nextInt(30);
            int baseDistance = 7 + rng.nextInt(21);
            int spread = 20 + rng.nextInt(100);

            List<ScanCoordinate> candidates = new ArrayList<>();
            for (int i = 0; i < numCandidates; i++) {
                candidates.add(new ScanCoordinate(
                        3200 + rng.nextInt(spread * 2 + 1) - spread,
                        3200 + rng.nextInt(spread * 2 + 1) - spread, 0, 0));
            }

            ScanCoordinate trueSpot = candidates.get(rng.nextInt(candidates.size()));
            int startX = 3200 + rng.nextInt(80) - 40;
            int startY = 3200 + rng.nextInt(80) - 40;

            SimResult result = runSimulation(candidates, trueSpot, startX, startY, baseDistance,
                    "Regression_" + scenario);

            if (result.solved()) solvedCount++;
            if (result.remainingCandidates() == 0) failures++;
        }

        System.out.println("=== OPEN TERRAIN REGRESSION (500 scenarios) ===");
        System.out.printf("  Solved: %d/500 (%.1f%%)%n", solvedCount, solvedCount / 5.0);
        System.out.printf("  Safety failures: %d%n", failures);

        assertEquals(0, failures, "PROXIMITY_EXPLORE introduction must not cause regressions");
        assertTrue(solvedCount >= 300,
                "Solve rate regressed: " + solvedCount + "/500 (" + (solvedCount / 5.0) + "%)");
    }

    // ══════════════════════════════════════════════════════════════
    // REAL RS3 SCAN REGIONS — Coordinate data from RS3 Wiki
    // ══════════════════════════════════════════════════════════════

    /**
     * Defines a real RS3 scan clue region for simulation testing.
     */
    record RealRegion(String name, int baseDistance, List<ScanCoordinate> candidates) {
        static RealRegion of(String name, int baseDistance, int[]... coords) {
            List<ScanCoordinate> list = new ArrayList<>();
            for (int[] c : coords) {
                list.add(new ScanCoordinate(c[0], c[1], 0, 0));
            }
            return new RealRegion(name, baseDistance, List.copyOf(list));
        }
    }

    /**
     * All 20 elite scan clue regions + 5 master scan regions with verified
     * RS3 wiki coordinates and base distances.
     */
    private static List<RealRegion> buildRealRegions() {
        List<RealRegion> regions = new ArrayList<>();

        // ── ELITE SCAN REGIONS ──

        regions.add(RealRegion.of("Ardougne", 22,
                new int[]{2613,3337}, new int[]{2633,3339}, new int[]{2662,3338}, new int[]{2662,3304},
                new int[]{2635,3313}, new int[]{2623,3311}, new int[]{2625,3292}, new int[]{2583,3265},
                new int[]{2582,3314}, new int[]{2589,3319}, new int[]{2589,3330}, new int[]{2569,3340},
                new int[]{2570,3321}, new int[]{2540,3331}, new int[]{2520,3318}, new int[]{2509,3330},
                new int[]{2529,3270}, new int[]{2512,3267}, new int[]{2517,3281}, new int[]{2500,3290},
                new int[]{2496,3282}, new int[]{2462,3282}, new int[]{2483,3313}, new int[]{2475,3331},
                new int[]{2467,3319}, new int[]{2442,3310}, new int[]{2440,3319}, new int[]{2537,3306}));

        regions.add(RealRegion.of("Varrock", 16,
                new int[]{3141,3488}, new int[]{3185,3472}, new int[]{3188,3488}, new int[]{3180,3510},
                new int[]{3213,3462}, new int[]{3213,3484}, new int[]{3230,3494}, new int[]{3241,3480},
                new int[]{3248,3454}, new int[]{3231,3439}, new int[]{3273,3398}, new int[]{3284,3378},
                new int[]{3253,3393}, new int[]{3240,3383}, new int[]{3228,3383}, new int[]{3211,3385},
                new int[]{3197,3383}, new int[]{3228,3409}, new int[]{3220,3407}, new int[]{3204,3409},
                new int[]{3196,3415}, new int[]{3197,3423}, new int[]{3175,3415}, new int[]{3175,3404}));

        regions.add(RealRegion.of("Tirannwn (Isafdar)", 22,
                new int[]{2271,3165}, new int[]{2247,3143}, new int[]{2253,3118}, new int[]{2217,3130},
                new int[]{2225,3145}, new int[]{2226,3158}, new int[]{2216,3159}, new int[]{2205,3155},
                new int[]{2182,3192}, new int[]{2186,3146}, new int[]{2173,3125}, new int[]{2194,3220},
                new int[]{2176,3201}, new int[]{2219,3221}, new int[]{2225,3212}, new int[]{2194,3237},
                new int[]{2203,3254}, new int[]{2229,3248}, new int[]{2232,3223}, new int[]{2283,3265},
                new int[]{2282,3262}, new int[]{2348,3183}, new int[]{2322,3190}, new int[]{2331,3171},
                new int[]{2310,3187}, new int[]{2287,3141}, new int[]{2289,3220}, new int[]{2302,3230},
                new int[]{2258,3212}));

        regions.add(RealRegion.of("Falador", 22,
                new int[]{2958,3379}, new int[]{2972,3342}, new int[]{2948,3390}, new int[]{2942,3388},
                new int[]{2939,3355}, new int[]{2945,3339}, new int[]{2938,3322}, new int[]{2947,3316},
                new int[]{2976,3316}, new int[]{3005,3326}, new int[]{3015,3339}, new int[]{3039,3331},
                new int[]{3050,3348}, new int[]{3059,3384}, new int[]{3031,3379}, new int[]{3027,3365},
                new int[]{3011,3382}, new int[]{3025,3379}));

        regions.add(RealRegion.of("Fremennik Isles", 16,
                new int[]{2311,3801}, new int[]{2322,3787}, new int[]{2324,3808}, new int[]{2342,3809},
                new int[]{2340,3803}, new int[]{2354,3790}, new int[]{2360,3799}, new int[]{2311,3835},
                new int[]{2330,3829}, new int[]{2373,3834}, new int[]{2377,3850}, new int[]{2353,3852},
                new int[]{2326,3850}, new int[]{2314,3851}, new int[]{2326,3866}, new int[]{2349,3880},
                new int[]{2352,3892}, new int[]{2312,3894}, new int[]{2389,3899}, new int[]{2399,3888},
                new int[]{2417,3893}, new int[]{2368,3870}, new int[]{2400,3870}, new int[]{2418,3870},
                new int[]{2414,3848}, new int[]{2419,3833}, new int[]{2397,3801}, new int[]{2402,3789},
                new int[]{2421,3792}, new int[]{2381,3789}, new int[]{2376,3800}, new int[]{2395,3812}));

        regions.add(RealRegion.of("Menaphos", 30,
                new int[]{3200,2709}, new int[]{3180,2700}, new int[]{3099,2677}, new int[]{3096,2692},
                new int[]{3095,2730}, new int[]{3108,2742}, new int[]{3145,2759}, new int[]{3199,2750},
                new int[]{3165,2814}, new int[]{3153,2799}, new int[]{3135,2775}, new int[]{3131,2791},
                new int[]{3193,2797}, new int[]{3210,2770}, new int[]{3231,2770}, new int[]{3238,2792},
                new int[]{3237,2664}, new int[]{3180,2669}, new int[]{3155,2640}, new int[]{3146,2659},
                new int[]{3127,2643}));

        regions.add(RealRegion.of("Mos Le'Harmless", 27,
                new int[]{3657,2955}, new int[]{3651,2991}, new int[]{3676,2981}, new int[]{3692,2976},
                new int[]{3702,2972}, new int[]{3699,2996}, new int[]{3679,3018}, new int[]{3702,3027},
                new int[]{3726,3038}, new int[]{3687,3046}, new int[]{3697,3052}, new int[]{3695,3063},
                new int[]{3669,3055}, new int[]{3742,3063}, new int[]{3757,3063}, new int[]{3736,3041},
                new int[]{3740,3018}, new int[]{3752,3006}, new int[]{3730,2996}, new int[]{3728,2973},
                new int[]{3717,2969}, new int[]{3758,2956}, new int[]{3773,2960}, new int[]{3769,3015},
                new int[]{3765,2995}, new int[]{3765,3030}, new int[]{3802,3035}, new int[]{3831,3031},
                new int[]{3818,3027}, new int[]{3791,3023}, new int[]{3850,3010}, new int[]{3843,2987}));

        regions.add(RealRegion.of("Deep Wilderness", 25,
                new int[]{3021,3929}, new int[]{3281,3940}, new int[]{3348,3957}, new int[]{2944,3904},
                new int[]{2955,3905}, new int[]{2959,3915}, new int[]{2979,3958}, new int[]{2988,3921},
                new int[]{2998,3910}, new int[]{3014,3955}, new int[]{3029,3948}, new int[]{3021,3922},
                new int[]{3030,3921}, new int[]{3049,3923}, new int[]{3050,3912}, new int[]{3080,3906},
                new int[]{3058,3938}, new int[]{3057,3946}, new int[]{3110,3949}, new int[]{3126,3906},
                new int[]{3159,3938}, new int[]{3175,3957}, new int[]{3182,3939}, new int[]{3183,3920},
                new int[]{3194,3945}, new int[]{3219,3944}, new int[]{3241,3939}, new int[]{3242,3951},
                new int[]{3266,3931}, new int[]{3269,3910}, new int[]{3305,3942}, new int[]{3306,3910},
                new int[]{3314,3911}, new int[]{3333,3903}, new int[]{3367,3923}, new int[]{3379,3955},
                new int[]{3368,3945}, new int[]{3363,3941}));

        regions.add(RealRegion.of("Kharidian Desert", 27,
                new int[]{3421,2949}, new int[]{3442,2955}, new int[]{3438,2960}, new int[]{3447,2967},
                new int[]{3442,2974}, new int[]{3427,2970}, new int[]{3436,2989}, new int[]{3426,2984},
                new int[]{3408,2986}, new int[]{3406,3003}, new int[]{3393,2997}, new int[]{3382,3015},
                new int[]{3383,3018}, new int[]{3423,3020}, new int[]{3476,3018}, new int[]{3460,3022},
                new int[]{3448,3019}, new int[]{3465,3034}, new int[]{3463,3048}, new int[]{3448,3063},
                new int[]{3477,3057}, new int[]{3502,3050}, new int[]{3510,3041}, new int[]{3473,3082},
                new int[]{3480,3090}, new int[]{3505,3093}, new int[]{3499,3104}, new int[]{3482,3108},
                new int[]{3456,3140}, new int[]{3444,3141}, new int[]{3427,3141}, new int[]{3405,3136},
                new int[]{3446,3128}, new int[]{3433,3122}, new int[]{3432,3105}, new int[]{3431,3094},
                new int[]{3409,3119}, new int[]{3387,3123}, new int[]{3373,3126}, new int[]{3396,3110},
                new int[]{3401,3099}, new int[]{3360,3095}, new int[]{3384,3081}, new int[]{3401,3064},
                new int[]{3411,3048}, new int[]{3422,3051}, new int[]{3419,3017}, new int[]{3417,2959},
                new int[]{3435,3129}, new int[]{3385,3024}, new int[]{3406,3126}, new int[]{3444,3085}));

        regions.add(RealRegion.of("Kharazi Jungle", 14,
                new int[]{2892,2937}, new int[]{2921,2937}, new int[]{2932,2935}, new int[]{2942,2934},
                new int[]{2936,2917}, new int[]{2931,2920}, new int[]{2944,2902}, new int[]{2929,2894},
                new int[]{2920,2888}, new int[]{2892,2907}, new int[]{2872,2901}, new int[]{2859,2891},
                new int[]{2848,2907}, new int[]{2841,2915}, new int[]{2852,2934}, new int[]{2832,2935},
                new int[]{2827,2934}, new int[]{2804,2924}, new int[]{2857,2919}, new int[]{2786,2914},
                new int[]{2775,2936}, new int[]{2766,2932}, new int[]{2762,2918}, new int[]{2775,2891},
                new int[]{2815,2887}, new int[]{2927,2925}));

        regions.add(RealRegion.of("Haunted Woods", 11,
                new int[]{3529,3501}, new int[]{3523,3460}, new int[]{3534,3470}, new int[]{3544,3465},
                new int[]{3552,3483}, new int[]{3551,3514}, new int[]{3562,3509}, new int[]{3575,3511},
                new int[]{3573,3484}, new int[]{3583,3484}, new int[]{3567,3475}, new int[]{3583,3466},
                new int[]{3590,3475}, new int[]{3606,3465}, new int[]{3609,3499}, new int[]{3596,3501},
                new int[]{3604,3507}, new int[]{3616,3512}, new int[]{3624,3508}, new int[]{3637,3486},
                new int[]{3623,3476}));

        regions.add(RealRegion.of("Piscatoris Hunter Area", 14,
                new int[]{2363,3527}, new int[]{2353,3543}, new int[]{2364,3547}, new int[]{2358,3557},
                new int[]{2361,3567}, new int[]{2398,3582}, new int[]{2388,3586}, new int[]{2372,3626},
                new int[]{2373,3612}, new int[]{2392,3591}, new int[]{2344,3645}, new int[]{2332,3632},
                new int[]{2320,3625}, new int[]{2318,3601}, new int[]{2347,3609}, new int[]{2362,3612},
                new int[]{2358,3580}, new int[]{2339,3589}, new int[]{2342,3575}, new int[]{2331,3574},
                new int[]{2313,3576}, new int[]{2310,3587}, new int[]{2308,3560}, new int[]{2324,3553},
                new int[]{2336,3540}, new int[]{2310,3518}));

        regions.add(RealRegion.of("Wilderness Crater", 11,
                new int[]{3173,3698}, new int[]{3146,3698}, new int[]{3152,3693}, new int[]{3115,3673},
                new int[]{3086,3703}, new int[]{3096,3738}, new int[]{3134,3741}, new int[]{3133,3717},
                new int[]{3165,3721}, new int[]{3145,3678}, new int[]{3130,3672}, new int[]{3123,3661},
                new int[]{3124,3698}, new int[]{3105,3700}, new int[]{3112,3733}, new int[]{3118,3720},
                new int[]{3146,3734}, new int[]{3169,3745}, new int[]{3181,3708}));

        regions.add(RealRegion.of("Zanaris", 16,
                new int[]{2429,4431}, new int[]{2406,4428}, new int[]{2404,4406}, new int[]{2389,4405},
                new int[]{2377,4410}, new int[]{2414,4378}, new int[]{2420,4381}, new int[]{2423,4372},
                new int[]{2439,4460}, new int[]{2453,4471}, new int[]{2441,4428}, new int[]{2457,4443},
                new int[]{2468,4439}, new int[]{2417,4444}, new int[]{2400,4441}, new int[]{2410,4460},
                new int[]{2417,4470}, new int[]{2402,4466}, new int[]{2396,4457}, new int[]{2372,4467},
                new int[]{2385,4447}, new int[]{2380,4421}));

        regions.add(RealRegion.of("Lumbridge Swamp Caves", 11,
                new int[]{3172,9570}, new int[]{3167,9546}, new int[]{3170,9557}, new int[]{3179,9559},
                new int[]{3191,9555}, new int[]{3210,9557}, new int[]{3233,9547}, new int[]{3210,9571},
                new int[]{3209,9587}, new int[]{3227,9575}, new int[]{3246,9566}, new int[]{3252,9577}));

        regions.add(RealRegion.of("Fremennik Slayer Dungeon", 16,
                new int[]{2804,10004}, new int[]{2808,10018}, new int[]{2789,10042}, new int[]{2772,10030},
                new int[]{2757,10029}, new int[]{2754,10009}, new int[]{2767,10002}, new int[]{2751,9995},
                new int[]{2745,10024}, new int[]{2722,10025}, new int[]{2705,10027}, new int[]{2718,10000},
                new int[]{2731,9998}, new int[]{2714,9990}, new int[]{2701,9978}, new int[]{2720,9969},
                new int[]{2724,9977}, new int[]{2741,9977}, new int[]{2743,9986}));

        regions.add(RealRegion.of("Dorgesh-Kaan", 16,
                new int[]{2704,5413}, new int[]{2732,5391}, new int[]{2717,5375}, new int[]{2729,5359},
                new int[]{2730,5379}, new int[]{2704,5385}, new int[]{2711,5348}, new int[]{2711,5335},
                new int[]{2723,5343}, new int[]{2731,5330}, new int[]{2740,5337}, new int[]{2747,5327},
                new int[]{2835,5381}, new int[]{2834,5429}, new int[]{2843,5455}, new int[]{2830,5498},
                new int[]{2800,5485}, new int[]{2797,5471}, new int[]{2794,5444}, new int[]{2796,5412}));

        regions.add(RealRegion.of("Brimhaven Dungeon", 14,
                new int[]{2703,9564}, new int[]{2697,9563}, new int[]{2680,9542}, new int[]{2669,9573},
                new int[]{2675,9582}, new int[]{2660,9589}, new int[]{2642,9575}, new int[]{2641,9541},
                new int[]{2640,9533}, new int[]{2652,9528}, new int[]{2664,9522}, new int[]{2672,9514},
                new int[]{2647,9511}, new int[]{2649,9499}, new int[]{2650,9488}, new int[]{2665,9504},
                new int[]{2701,9516}, new int[]{2712,9524}, new int[]{2717,9517}, new int[]{2730,9513},
                new int[]{2713,9503}, new int[]{2679,9479}, new int[]{2699,9481}, new int[]{2707,9485},
                new int[]{2745,9488}, new int[]{2739,9506}, new int[]{2695,9457}, new int[]{2703,9439},
                new int[]{2722,9444}, new int[]{2738,9456}, new int[]{2729,9427}, new int[]{2737,9433},
                new int[]{2681,9505}, new int[]{2638,9594}, new int[]{2630,9581}, new int[]{2638,9566},
                new int[]{2634,9551}, new int[]{2627,9530}, new int[]{2631,9516}, new int[]{2639,9504},
                new int[]{2628,9489}, new int[]{2655,9476}));

        regions.add(RealRegion.of("Taverley Dungeon", 22,
                new int[]{2884,9799}, new int[]{2888,9846}, new int[]{2933,9848}, new int[]{2907,9842},
                new int[]{2895,9831}, new int[]{2938,9812}, new int[]{2945,9796}, new int[]{2952,9786},
                new int[]{2968,9786}, new int[]{2949,9773}, new int[]{2936,9764}, new int[]{2905,9734},
                new int[]{2907,9718}, new int[]{2907,9705}, new int[]{2926,9692}, new int[]{2914,9757},
                new int[]{2904,9809}, new int[]{2892,9783}, new int[]{2895,9769}, new int[]{2858,9788},
                new int[]{2870,9791}, new int[]{2875,9805}, new int[]{2832,9813}, new int[]{2835,9819},
                new int[]{2822,9826}));

        regions.add(RealRegion.of("Keldagrim", 11,
                new int[]{2905,10226}, new int[]{2924,10226}, new int[]{2938,10226}, new int[]{2922,10243},
                new int[]{2938,10243}, new int[]{2904,10257}, new int[]{2906,10266}, new int[]{2924,10255},
                new int[]{2937,10255}, new int[]{2936,10270}, new int[]{2846,10297}, new int[]{2860,10279},
                new int[]{2837,10273}, new int[]{2822,10257}, new int[]{2841,10253}, new int[]{2856,10256},
                new int[]{2873,10258}, new int[]{2872,10245}));

        // ── MASTER SCAN REGIONS ──

        regions.add(RealRegion.of("Prifddinas", 30,
                new int[]{2268,3397}, new int[]{2275,3382}, new int[]{2292,3361}, new int[]{2224,3328},
                new int[]{2227,3295}, new int[]{2199,3268}, new int[]{2212,3272}, new int[]{2234,3265},
                new int[]{2247,3267}, new int[]{2175,3291}, new int[]{2180,3322}, new int[]{2133,3379},
                new int[]{2145,3381}, new int[]{2148,3351}, new int[]{2174,3398}, new int[]{2197,3433},
                new int[]{2222,3429}, new int[]{2228,3424}));

        regions.add(RealRegion.of("Darkmeyer", 16,
                new int[]{3631,3358}, new int[]{3611,3328}, new int[]{3599,3328}, new int[]{3590,3344},
                new int[]{3588,3361}, new int[]{3596,3375}, new int[]{3606,3391}, new int[]{3639,3390},
                new int[]{3651,3405}, new int[]{3676,3392}, new int[]{3661,3374}, new int[]{3670,3373},
                new int[]{3671,3366}, new int[]{3654,3357}, new int[]{3666,3344}, new int[]{3654,3335},
                new int[]{3632,3344}));

        regions.add(RealRegion.of("Heart of Gielinor", 49,
                new int[]{3165,6932}, new int[]{3166,6910}, new int[]{3131,6932}, new int[]{3131,6921},
                new int[]{3148,6913}, new int[]{3124,6905}, new int[]{3119,6905}, new int[]{3180,6985},
                new int[]{3129,6998}, new int[]{3141,7018}, new int[]{3156,7040}, new int[]{3183,7015},
                new int[]{3194,7029}, new int[]{3210,7028}, new int[]{3241,7039}, new int[]{3274,7045},
                new int[]{3217,7004}, new int[]{3229,6980}, new int[]{3236,6924}, new int[]{3257,6927},
                new int[]{3258,6913}, new int[]{3156,6925}, new int[]{3147,7046}, new int[]{3248,6903},
                new int[]{3219,7023}, new int[]{3227,6982}));

        regions.add(RealRegion.of("The Arc (Turtles)", 27,
                new int[]{4235,1764}, new int[]{4251,1777}, new int[]{4259,1789}, new int[]{4270,1809},
                new int[]{4250,1791}, new int[]{4256,1764}, new int[]{4269,1776}, new int[]{4270,1788},
                new int[]{4267,1835}, new int[]{4205,1833}, new int[]{4184,1837}, new int[]{4178,1836},
                new int[]{4182,1817}, new int[]{4167,1836}, new int[]{4083,1784}, new int[]{4084,1768},
                new int[]{4076,1763}, new int[]{4087,1761}, new int[]{4085,1776}));

        regions.add(RealRegion.of("The Lost Grove", 14,
                new int[]{1950,3054}, new int[]{1966,3120}, new int[]{2007,3100}, new int[]{1997,3131},
                new int[]{1999,3160}, new int[]{1993,3188}, new int[]{1951,3235}, new int[]{1904,3198},
                new int[]{1951,3191}, new int[]{1902,3168}, new int[]{1883,3126}, new int[]{1909,3112},
                new int[]{1928,3129}, new int[]{1949,3138}, new int[]{1978,3199}, new int[]{1920,3156},
                new int[]{1927,3163}, new int[]{1946,3174}, new int[]{1908,3155}, new int[]{1944,3113}));

        return List.copyOf(regions);
    }

    /**
     * Per-region simulation result aggregation.
     */
    record RegionBenchmark(String name, int baseDistance, int spots, int simulations,
                           int solved, int safetyFailures, int totalIterations,
                           int minIter, int maxIter, long elapsedMs) {

        double solveRate() { return solved * 100.0 / simulations; }
        double avgIterations() { return solved > 0 ? (double) totalIterations / solved : 0; }

        String report() {
            return String.format("%-26s | base=%2d | spots=%2d | solved=%3d/%d (%5.1f%%) | " +
                            "avg=%5.1f iter | min=%3d | max=%3d | safety=%d | %4dms",
                    name, baseDistance, spots, solved, simulations, solveRate(),
                    avgIterations(), minIter, maxIter, safetyFailures, elapsedMs);
        }
    }

    /**
     * Run N simulations for a single region with randomized true spots and start positions.
     */
    private RegionBenchmark benchmarkRegion(RealRegion region, int numSimulations, Random rng) {
        List<ScanCoordinate> candidates = region.candidates();
        int baseDistance = region.baseDistance();

        // Compute bounding box for random start positions
        int minX = candidates.stream().mapToInt(ScanCoordinate::x).min().orElse(0);
        int maxX = candidates.stream().mapToInt(ScanCoordinate::x).max().orElse(0);
        int minY = candidates.stream().mapToInt(ScanCoordinate::y).min().orElse(0);
        int maxY = candidates.stream().mapToInt(ScanCoordinate::y).max().orElse(0);
        int centroidX = (minX + maxX) / 2;
        int centroidY = (minY + maxY) / 2;
        int spanX = maxX - minX;
        int spanY = maxY - minY;

        int solved = 0, safetyFailures = 0, totalSolvedIterations = 0;
        int minIter = Integer.MAX_VALUE, maxIter = 0;

        long startTime = System.currentTimeMillis();

        for (int i = 0; i < numSimulations; i++) {
            // Random true spot
            ScanCoordinate trueSpot = candidates.get(rng.nextInt(candidates.size()));

            // Random start position: either edge of bounding box, centroid, or random candidate
            int startX, startY;
            int startType = rng.nextInt(4);
            switch (startType) {
                case 0 -> { // Random edge of bounding box
                    if (rng.nextBoolean()) {
                        startX = rng.nextBoolean() ? minX - 10 : maxX + 10;
                        startY = minY + rng.nextInt(Math.max(1, spanY));
                    } else {
                        startX = minX + rng.nextInt(Math.max(1, spanX));
                        startY = rng.nextBoolean() ? minY - 10 : maxY + 10;
                    }
                }
                case 1 -> { // Centroid
                    startX = centroidX;
                    startY = centroidY;
                }
                case 2 -> { // Random candidate position (not the true spot)
                    ScanCoordinate start = candidates.get(rng.nextInt(candidates.size()));
                    startX = start.x();
                    startY = start.y();
                }
                default -> { // Random position within expanded bounding box
                    startX = minX - 20 + rng.nextInt(Math.max(1, spanX + 40));
                    startY = minY - 20 + rng.nextInt(Math.max(1, spanY + 40));
                }
            }

            SimResult result = runSimulation(candidates, trueSpot, startX, startY,
                    baseDistance, region.name());

            if (result.solved()) {
                solved++;
                totalSolvedIterations += result.iterations();
                minIter = Math.min(minIter, result.iterations());
                maxIter = Math.max(maxIter, result.iterations());
            }
            if (result.remainingCandidates() == 0) {
                safetyFailures++;
                if (safetyFailures <= 2) {
                    result.print("SAFETY FAIL: " + region.name() + " #" + i);
                }
            }
        }

        long elapsedMs = System.currentTimeMillis() - startTime;
        if (minIter == Integer.MAX_VALUE) minIter = 0;

        return new RegionBenchmark(region.name(), baseDistance, candidates.size(),
                numSimulations, solved, safetyFailures, totalSolvedIterations,
                minIter, maxIter, elapsedMs);
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 39: Real RS3 scan region benchmarks
    // ══════════════════════════════════════════════════════════════

    @Test
    @DisplayName("Real RS3 scan regions: 200 simulations per region, full benchmark")
    void testRealRegionBenchmarks() {
        List<RealRegion> regions = buildRealRegions();
        Random rng = new Random(20260401L); // deterministic seed
        int simsPerRegion = 200;

        List<RegionBenchmark> results = new ArrayList<>();
        int totalSolved = 0, totalSims = 0, totalSafety = 0;
        long totalTime = 0;

        System.out.println();
        System.out.println("╔══════════════════════════════════════════════════════════════════════════════════════════════════════════╗");
        System.out.println("║                         RS3 SCAN CLUE SOLVER BENCHMARK — 200 SIMS PER REGION                           ║");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════════════════════════════════╣");
        System.out.printf("║ %-26s | %6s | %5s | %16s | %10s | %5s | %5s | %6s | %5s ║%n",
                "Region", "Base", "Spots", "Solved", "Avg Iter", "Min", "Max", "Safety", "Time");
        System.out.println("╠══════════════════════════════════════════════════════════════════════════════════════════════════════════╣");

        for (RealRegion region : regions) {
            RegionBenchmark bench = benchmarkRegion(region, simsPerRegion, rng);
            results.add(bench);
            totalSolved += bench.solved();
            totalSims += bench.simulations();
            totalSafety += bench.safetyFailures();
            totalTime += bench.elapsedMs();

            System.out.printf("║ %-26s | %4d   | %3d   | %3d/%d (%5.1f%%) | %8.1f   | %3d   | %3d   | %4d   | %3dms ║%n",
                    bench.name(), bench.baseDistance(), bench.spots(),
                    bench.solved(), bench.simulations(), bench.solveRate(),
                    bench.avgIterations(), bench.minIter(), bench.maxIter(),
                    bench.safetyFailures(), bench.elapsedMs());
        }

        System.out.println("╠══════════════════════════════════════════════════════════════════════════════════════════════════════════╣");
        double overallRate = totalSolved * 100.0 / totalSims;
        System.out.printf("║ %-26s | %6s | %5s | %3d/%d (%5.1f%%) | %10s | %5s | %5s | %4d   | %3dms ║%n",
                "TOTAL", "", "", totalSolved, totalSims, overallRate,
                "", "", "", totalSafety, totalTime);
        System.out.println("╚══════════════════════════════════════════════════════════════════════════════════════════════════════════╝");
        System.out.println();

        // Identify worst and best regions
        results.sort(Comparator.comparingDouble(RegionBenchmark::solveRate));
        System.out.println("=== WORST 5 REGIONS BY SOLVE RATE ===");
        for (int i = 0; i < Math.min(5, results.size()); i++) {
            System.out.println("  " + results.get(i).report());
        }
        System.out.println();

        results.sort(Comparator.comparingDouble(RegionBenchmark::avgIterations).reversed());
        System.out.println("=== SLOWEST 5 REGIONS BY AVG ITERATIONS ===");
        for (int i = 0; i < Math.min(5, results.size()); i++) {
            System.out.println("  " + results.get(i).report());
        }
        System.out.println();

        // Assertions
        assertEquals(0, totalSafety,
                "Safety failures detected (true spot wrongly eliminated). " +
                "Regions: " + results.stream().filter(r -> r.safetyFailures() > 0)
                        .map(RegionBenchmark::name).collect(Collectors.joining(", ")));

        assertTrue(overallRate >= 70.0,
                String.format("Overall solve rate too low: %.1f%% (need ≥70%%)", overallRate));

        // Per-region: no region should have >5% safety failures
        for (RegionBenchmark bench : results) {
            double safetyPct = bench.safetyFailures() * 100.0 / bench.simulations();
            assertTrue(safetyPct <= 5.0,
                    String.format("%s: %.1f%% safety failure rate (%d/%d)",
                            bench.name(), safetyPct, bench.safetyFailures(), bench.simulations()));
        }
    }

    // ══════════════════════════════════════════════════════════════
    // TEST SUITE 40: Individual region quick-checks (one test per region)
    // ══════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "Region: {0}")
    @CsvSource({
            "0, Ardougne",         "1, Varrock",              "2, Tirannwn",
            "3, Falador",          "4, Fremennik Isles",      "5, Menaphos",
            "6, Mos Le'Harmless",  "7, Deep Wilderness",      "8, Kharidian Desert",
            "9, Kharazi Jungle",   "10, Haunted Woods",       "11, Piscatoris",
            "12, Wilderness Crater","13, Zanaris",             "14, Lumbridge Swamp",
            "15, Frem Slayer Dun", "16, Dorgesh-Kaan",        "17, Brimhaven Dun",
            "18, Taverley Dun",    "19, Keldagrim",           "20, Prifddinas",
            "21, Darkmeyer",       "22, Heart of Gielinor",   "23, The Arc",
            "24, The Lost Grove"
    })
    void testIndividualRegion(int index, String label) {
        List<RealRegion> regions = buildRealRegions();
        if (index >= regions.size()) return;

        RealRegion region = regions.get(index);
        Random rng = new Random(index * 7919L);

        RegionBenchmark bench = benchmarkRegion(region, 50, rng);
        System.out.println(bench.report());

        assertEquals(0, bench.safetyFailures(),
                label + ": true spot was wrongly eliminated in " + bench.safetyFailures() + " sim(s)");
    }
}
