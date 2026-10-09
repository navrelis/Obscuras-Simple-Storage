package com.obscura.storage.util;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.base.FilteringStorage;

/**
 * An inventory labelled by glow item frames (see {@link FrameLabels}): the network may only insert
 * the labelled items, and fills it before unlabelled inventories (the connector sorts it with
 * {@link FrameLabel#PRIORITY}). Unlike a block filter it leaves extraction, keep-last and what the
 * network lists untouched, so a hand-placed item stays visible and extractable.
 *
 * <p>Inserts through the merged network storage (terminals, hoppers, other mods' pipes into an
 * Inventory Interface or Connector) all go through {@link #insert}, which only accepts what the label
 * allows.
 */
public class LabelledStorage extends FilteringStorage<ItemVariant> implements IProxy, FrameLabel.Labelled {
	private final Storage<ItemVariant> backing;
	private final FrameLabel label;

	public LabelledStorage(Storage<ItemVariant> backing, FrameLabel label) {
		super(backing);
		this.backing = backing;
		this.label = label;
	}

	@Override
	protected boolean canInsert(ItemVariant resource) {
		if (resource.isBlank())return false;
		if (label.items().contains(resource.getItem()))return true;
		return label.hasFilters() && label.test(resource.toStack());
	}

	/** The storage behind the label, as a network member it stands for the same block. */
	@Override
	public Storage<ItemVariant> get() {
		return backing;
	}

	@Override
	public Object getLabelledStorage() {
		return backing;
	}

	@Override
	public FrameLabel getLabel() {
		return label;
	}

	@Override
	public String toString() {
		return "Labelled: {" + backing + " by " + label + "}";
	}
}
