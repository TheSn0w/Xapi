package com.botwithus.bot.api.nav;

import com.botwithus.bot.api.log.BotLogger;
import com.botwithus.bot.api.log.LoggerFactory;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;

import static com.botwithus.bot.api.nav.CollisionFlags.*;

/**
 * Reads {@link NavRegion} data from binary {@code .dat} files (BNAV format v2/v3).
 * <p>
 * Binary format:
 * <pre>
 * [4B] magic "BNAV" (0x424E4156)
 * [2B] version (2 or 3)
 * [4B] regionId
 * [1B] plane
 * [4096B] tile flags
 * [4B] transition count → [N × transition] (skipped)
 * [4B] POI count → [N × POI] (skipped)
 * [4B] connector count → [N × connector] (skipped)
 * [4096B] diagonal flags (v3 only)
 * </pre>
 */
public final class RegionStore {

    private static final BotLogger log = LoggerFactory.getLogger(RegionStore.class);

    private final Path regionsDir;

    /**
     * @param regionsDir path to the directory containing region {@code .dat} files
     */
    public RegionStore(Path regionsDir) {
        this.regionsDir = regionsDir;
    }

    /**
     * Loads a region from its binary file. Returns {@code null} if the file
     * does not exist or cannot be read.
     */
    public NavRegion loadRegion(int regionId, int plane) {
        Path file = regionsDir.resolve(regionId + "_" + plane + ".dat");
        if (!Files.exists(file)) return null;

        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {

            // Header
            int magic = in.readInt();
            if (magic != MAGIC) {
                log.warn("[RegionStore] Invalid magic in {}: 0x{}", file.getFileName(), Integer.toHexString(magic));
                return null;
            }
            short version = in.readShort();
            if (version > VERSION) {
                log.warn("[RegionStore] Unsupported version {} in {}", version, file.getFileName());
                return null;
            }
            int loadedId = in.readInt();
            int loadedPlane = in.readByte();
            if (loadedId != regionId || loadedPlane != plane) {
                log.warn("[RegionStore] Header mismatch in {}: expected {}_{} got {}_{}",
                        file.getFileName(), regionId, plane, loadedId, loadedPlane);
                return null;
            }

            // Tile flags (4096 bytes)
            byte[] flags = new byte[TILES_PER_REGION];
            in.readFully(flags);

            NavRegion region = new NavRegion(loadedId, loadedPlane, flags, null);

            // Read transitions (skip data — we don't block doors because the A*
            // needs to walk through them to reach interior tiles like bank counters)
            int transCount = in.readInt();
            for (int i = 0; i < transCount; i++) {
                skipTransition(in);
            }

            // Skip POIs
            int poiCount = in.readInt();
            for (int i = 0; i < poiCount; i++) {
                skipPoi(in);
            }

            // Skip connectors
            int connCount = in.readInt();
            in.skipBytes(connCount * 16); // 4 ints × 4 bytes each

            // Diagonal flags (v3+)
            if (version >= 3) {
                byte[] diagFlags = new byte[TILES_PER_REGION];
                in.readFully(diagFlags);
                System.arraycopy(diagFlags, 0, region.getDiagFlagsArray(), 0, TILES_PER_REGION);
            }

            region.enforceWallReciprocity();
            return region;

        } catch (IOException e) {
            log.warn("[RegionStore] Failed to load {}_{}: {}", regionId, plane, e.getMessage());
            return null;
        }
    }

    /** Skips one transition entry in the stream. */
    private static void skipTransition(DataInputStream in) throws IOException {
        in.readByte();          // type ordinal
        in.skipBytes(6 * 4);    // 6 ints: srcX, srcY, srcPlane, dstX, dstY, dstPlane
        in.readInt();           // objectId
        in.readUTF();           // objectName
        in.readUTF();           // interactOption
        in.readInt();           // costTicks
        skipRequirements(in);
    }

    /** Skips one POI entry in the stream. */
    private static void skipPoi(DataInputStream in) throws IOException {
        in.readByte();          // category ordinal
        in.readUTF();           // name
        in.skipBytes(3 * 4);    // x, y, plane
        in.readInt();           // objectId
        in.readUTF();           // interactOption
        skipRequirements(in);
    }

    /** Skips a requirements block: 1 boolean + 4 ints. */
    private static void skipRequirements(DataInputStream in) throws IOException {
        in.readBoolean();       // memberRequired
        in.skipBytes(4 * 4);    // questId, varbitId, varbitValue, itemId
    }
}
