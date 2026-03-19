package com.botwithus.bot.core.blueprint.serialization;

/**
 * Blueprint file format versioning and migration support.
 */
public final class BlueprintFormat {

    public static final int CURRENT_VERSION = 1;

    private BlueprintFormat() {
        // utility class
    }

    /**
     * Checks if the given version is supported by this implementation.
     *
     * @param version the format version from a blueprint file
     * @return true if the version can be loaded
     */
    public static boolean isSupported(int version) {
        return version >= 1 && version <= CURRENT_VERSION;
    }

    /**
     * Placeholder for future format migration. Currently a no-op since only v1 exists.
     *
     * @param json    the raw parsed JSON map
     * @param fromVersion the version found in the file
     * @return the (potentially migrated) JSON map ready for v1 deserialization
     */
    @SuppressWarnings("unchecked")
    public static java.util.Map<String, Object> migrate(java.util.Map<String, Object> json, int fromVersion) {
        if (fromVersion == CURRENT_VERSION) {
            return json;
        }
        // Future migration steps would go here, e.g.:
        // if (fromVersion < 2) { json = migrateV1ToV2(json); }
        throw new IllegalArgumentException("Unsupported blueprint format version: " + fromVersion);
    }
}
