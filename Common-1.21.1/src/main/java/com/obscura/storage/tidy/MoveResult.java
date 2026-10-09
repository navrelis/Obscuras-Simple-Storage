package com.obscura.storage.tidy;

/**
 * What one Tidy move did ({@code com.obscura.storage.inventory.TidyIO#move} on each loader).
 *
 * @param moved items that arrived in the destination
 * @param slotsFilled empty destination slots that received items
 * @param sourceEmptied whether the source slot is empty afterwards
 */
public record MoveResult(int moved, int slotsFilled, boolean sourceEmptied) {
	public static final MoveResult NONE = new MoveResult(0, 0, false);
}
