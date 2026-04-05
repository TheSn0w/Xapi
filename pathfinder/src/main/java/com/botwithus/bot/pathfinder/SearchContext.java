package com.botwithus.bot.pathfinder;

import java.util.Arrays;

/**
 * Reusable A* search state with generation-based lazy reset and a primitive binary min-heap.
 * <p>
 * The generation counter avoids clearing all arrays between searches — only the
 * counter is incremented, and tiles are lazily initialized on first access.
 * This gives O(1) reset cost.
 * <p>
 * The min-heap uses parallel primitive arrays (no boxing, no object allocation in the hot loop).
 */
final class SearchContext {

    static final int UNVISITED = 0;
    static final int OPEN = 1;
    static final int CLOSED = 2;

    final int capacity;
    final int[] gCost;
    final int[] parent;
    final byte[] state;
    private final int[] visitedGen;
    private int generation;

    // ── Binary min-heap ──────────────────────────────────────────
    final int[] heapNodes;
    final int[] heapPrio;
    final int[] heapPos;
    int heapSize;

    SearchContext(int capacity) {
        this.capacity = capacity;
        gCost = new int[capacity];
        parent = new int[capacity];
        state = new byte[capacity];
        visitedGen = new int[capacity];
        heapNodes = new int[capacity];
        heapPrio = new int[capacity];
        heapPos = new int[capacity];
        Arrays.fill(heapPos, -1);
    }

    /** O(1) reset — just bumps the generation counter. */
    void reset() {
        generation++;
        if (generation == Integer.MIN_VALUE) {
            // Wraparound: force full clear
            Arrays.fill(visitedGen, 0);
            generation = 1;
        }
        heapSize = 0;
    }

    /** Lazily initializes a tile if it hasn't been touched this generation. */
    void touch(int idx) {
        if (visitedGen[idx] != generation) {
            visitedGen[idx] = generation;
            gCost[idx] = Integer.MAX_VALUE;
            parent[idx] = -1;
            state[idx] = UNVISITED;
            heapPos[idx] = -1;
        }
    }

    boolean isClosed(int idx) {
        return visitedGen[idx] == generation && state[idx] == CLOSED;
    }

    int getGCost(int idx) {
        return visitedGen[idx] == generation ? gCost[idx] : Integer.MAX_VALUE;
    }

    // ── Heap operations ──────────────────────────────────────────

    int heapExtractMin() {
        int min = heapNodes[0];
        heapPos[min] = -1;
        heapSize--;
        if (heapSize > 0) {
            heapNodes[0] = heapNodes[heapSize];
            heapPrio[0] = heapPrio[heapSize];
            heapPos[heapNodes[0]] = 0;
            siftDown(0);
        }
        return min;
    }

    void heapInsert(int node, int priority) {
        int p = heapSize++;
        heapNodes[p] = node;
        heapPrio[p] = priority;
        heapPos[node] = p;
        siftUp(p);
    }

    void heapDecrease(int node, int priority) {
        int p = heapPos[node];
        if (p >= 0 && priority < heapPrio[p]) {
            heapPrio[p] = priority;
            siftUp(p);
        }
    }

    void heapInsertOrDecrease(int node, int priority) {
        int p = heapPos[node];
        if (p == -1) {
            heapInsert(node, priority);
        } else if (priority < heapPrio[p]) {
            heapPrio[p] = priority;
            siftUp(p);
        }
    }

    private void siftUp(int p) {
        while (p > 0) {
            int pp = (p - 1) >> 1;
            if (heapPrio[p] >= heapPrio[pp]) break;
            swap(p, pp);
            p = pp;
        }
    }

    private void siftDown(int p) {
        while (true) {
            int left = (p << 1) + 1;
            int right = left + 1;
            int smallest = p;
            if (left < heapSize && heapPrio[left] < heapPrio[smallest]) smallest = left;
            if (right < heapSize && heapPrio[right] < heapPrio[smallest]) smallest = right;
            if (smallest == p) break;
            swap(p, smallest);
            p = smallest;
        }
    }

    private void swap(int a, int b) {
        int tn = heapNodes[a]; int tp = heapPrio[a];
        heapNodes[a] = heapNodes[b]; heapPrio[a] = heapPrio[b]; heapPos[heapNodes[a]] = a;
        heapNodes[b] = tn; heapPrio[b] = tp; heapPos[heapNodes[b]] = b;
    }
}
