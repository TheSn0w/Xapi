package com.botwithus.bot.api.blueprint;

/**
 * Metadata for a blueprint graph.
 *
 * @param name        display name of the blueprint
 * @param version     version string
 * @param author      author name
 * @param description brief description
 */
public record BlueprintMetadata(String name, String version, String author, String description) {}
