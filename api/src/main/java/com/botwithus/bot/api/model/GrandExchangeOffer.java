package com.botwithus.bot.api.model;

/**
 * An active Grand Exchange trade offer.
 *
 * @param slot           the GE slot index (0-based)
 * @param status         the offer status (e.g., empty, buying, selling, completed)
 * @param type           the offer type (0 = buy, 1 = sell)
 * @param itemId         the item being traded
 * @param price          the price per item in coins
 * @param count          the total quantity requested
 * @param completedCount the quantity that has been fulfilled so far
 * @param completedGold  the total gold exchanged so far
 * @see com.botwithus.bot.api.GameAPI#getGrandExchangeOffers
 */
public record GrandExchangeOffer(
        int slot, int status, int type, int itemId,
        int price, int count, int completedCount, int completedGold
) {}
