package com.botwithus.bot.api.model;

/**
 * Raw data read from the game cache.
 *
 * @param data the file contents as a byte array
 * @param size the number of valid bytes in the data array
 * @see com.botwithus.bot.api.GameAPI#getCacheFile
 * @see com.botwithus.bot.api.GameAPI#getNavigationArchive
 */
public record CacheFile(byte[] data, int size) {}
