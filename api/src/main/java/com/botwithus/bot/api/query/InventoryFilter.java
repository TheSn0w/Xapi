package com.botwithus.bot.api.query;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable filter for querying inventory items.
 *
 * <p>Use the {@link Builder} to construct a filter, then pass it to
 * {@link com.botwithus.bot.api.GameAPI#queryInventoryItems GameAPI.queryInventoryItems()}.</p>
 *
 * <p>Example:</p>
 * <pre>{@code
 * InventoryFilter filter = InventoryFilter.builder()
 *     .inventoryId(93)
 *     .nonEmpty(true)
 *     .build();
 * List<InventoryItem> items = api.queryInventoryItems(filter);
 * }</pre>
 *
 * @see com.botwithus.bot.api.GameAPI#queryInventoryItems
 */
public final class InventoryFilter {
    private final Map<String, Object> params;

    private InventoryFilter(Map<String, Object> params) {
        this.params = Map.copyOf(params);
    }

    /**
     * Returns the filter parameters as an unmodifiable map for RPC serialization.
     *
     * @return the filter parameter map
     */
    public Map<String, Object> toParams() { return params; }

    /**
     * Creates a new filter builder.
     *
     * @return a new {@link Builder} instance
     */
    public static Builder builder() { return new Builder(); }

    /**
     * Builder for constructing {@link InventoryFilter} instances.
     */
    public static final class Builder {
        private final Map<String, Object> params = new LinkedHashMap<>();

        private Builder() {}

        /**
         * Filters by inventory ID (e.g., 93 for backpack, 94 for equipment, 95 for bank).
         *
         * @param id the inventory ID
         * @return this builder
         */
        public Builder inventoryId(int id) { params.put("inventory_id", id); return this; }

        /**
         * Filters by item ID.
         *
         * @param id the item ID
         * @return this builder
         */
        public Builder itemId(int id) { params.put("item_id", id); return this; }

        /**
         * Filters to items with at least the specified quantity.
         *
         * @param qty the minimum quantity
         * @return this builder
         */
        public Builder minQuantity(int qty) { params.put("min_quantity", qty); return this; }

        /**
         * If {@code true}, excludes empty slots (item ID {@code -1}) from results.
         *
         * @param v {@code true} to exclude empty slots
         * @return this builder
         */
        public Builder nonEmpty(boolean v) { params.put("non_empty", v); return this; }

        /**
         * Limits the maximum number of results returned.
         *
         * @param max the maximum result count
         * @return this builder
         */
        public Builder maxResults(int max) { params.put("max_results", max); return this; }

        /**
         * Builds the immutable {@link InventoryFilter}.
         *
         * @return the constructed filter
         */
        public InventoryFilter build() { return new InventoryFilter(params); }
    }
}
