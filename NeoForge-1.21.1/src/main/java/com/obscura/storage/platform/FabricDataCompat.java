package com.obscura.storage.platform;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.obscura.storage.StorageMod;
import com.obscura.storage.block.entity.BlockFilterAttachment;
import com.obscura.storage.migration.LegacyBlockFilters;
import com.obscura.storage.migration.LegacyIds;

/**
 * Keeps Inventory Configurator filters when a world moves between the Fabric and the NeoForge build.
 *
 * <p>The Fabric build stores a block's filter as a Cardinal Components block component; CCA writes it
 * into the block entity's NBT as
 * <pre>cardinal_components: { "obscuras_storage:block_filter": { data: &lt;BlockFilter NBT&gt; } }</pre>
 * NeoForge stores the same {@link com.obscura.storage.inventory.BlockFilter} NBT as a data attachment
 * under {@code neoforge:attachments: { "obscuras_storage:block_filter": &lt;BlockFilter NBT&gt; }}. The
 * BlockFilter NBT itself is written by shared code, so it is identical on both loaders.
 *
 * <ul>
 * <li>Fabric to NeoForge: {@link #readFabricBlockFilter} turns the CCA entry into the attachment when
 * the block entity has no attachment of its own.</li>
 * <li>NeoForge to Fabric: {@link #writeFabricBlockFilter} also writes the CCA entry on every save, so
 * the Fabric build (CCA) finds the filter where it expects it. Vanilla ignores unknown keys, and
 * the next Fabric save drops the {@code neoforge:attachments} copy (and the next NeoForge save the
 * CCA copy), so the two can never disagree for long.</li>
 * </ul>
 * The Fabric build registers the component on every block entity. The mod's own block entities are
 * hooked one by one (PlatformBlockEntity, OpenCrateBlockEntity, and the Filing Cabinet through
 * {@link Platform#readCrossLoaderData}); all others (chests, barrels, other mods) go through the
 * chunk NBT, see {@link #onChunkSave} and {@link #onChunkLoad}.
 *
 * <p>Worlds from before v5.0.0 use the key {@code toms_storage:block_filter} in both places. The
 * attachment itself is found through the registry alias (see {@code GameObject#addLegacyAlias}); the
 * Cardinal Components entry is read here under either key. Only the current key is ever written.
 */
public final class FabricDataCompat {
	private static final String CCA_KEY = "cardinal_components";
	private static final String FILTER_ID = LegacyIds.blockFilterKey();
	private static final String LEGACY_FILTER_ID = LegacyIds.oldBlockFilterKey();
	private static final String DATA = "data";

	private FabricDataCompat() {}

	/** The block filter compound in {@code map} under the current key, else under the old key; null when neither. */
	private static CompoundTag findFilter(CompoundTag map) {
		if (map.contains(FILTER_ID, Tag.TAG_COMPOUND))return map.getCompound(FILTER_ID);
		if (map.contains(LEGACY_FILTER_ID, Tag.TAG_COMPOUND))return map.getCompound(LEGACY_FILTER_ID);
		return null;
	}

	/** The BlockFilter NBT of the Cardinal Components entry in a block entity tag, or null. */
	private static CompoundTag findCcaFilterData(CompoundTag beTag) {
		if (!beTag.contains(CCA_KEY, Tag.TAG_COMPOUND))return null;
		CompoundTag comp = findFilter(beTag.getCompound(CCA_KEY));
		if (comp == null || !comp.contains(DATA, Tag.TAG_COMPOUND))return null;
		return comp.getCompound(DATA);
	}

	/** Must run after {@code super.loadAdditional}, which reads NeoForge's own attachments. */
	public static void readFabricBlockFilter(BlockEntity be, CompoundTag tag, HolderLookup.Provider provider) {
		CompoundTag data = findCcaFilterData(tag);
		if (data == null)return;
		if (be.hasData(Platform.BLOCK_FILTER))return;// NeoForge's own copy wins
		try {
			BlockFilterAttachment a = new BlockFilterAttachment(be);
			a.deserializeNBT(provider, data);
			be.setData(Platform.BLOCK_FILTER, a);
		} catch (RuntimeException e) {
			StorageMod.LOGGER.warn("Could not read the Fabric block filter of {} at {}", be.getType(), be.getBlockPos(), e);
		}
	}

	public static void writeFabricBlockFilter(BlockEntity be, CompoundTag tag, HolderLookup.Provider provider) {
		if (!be.hasData(Platform.BLOCK_FILTER))return;
		CompoundTag data = be.getData(Platform.BLOCK_FILTER).serializeNBT(provider);
		CompoundTag comp = new CompoundTag();
		comp.put(DATA, data);
		CompoundTag cca = tag.contains(CCA_KEY, Tag.TAG_COMPOUND) ? tag.getCompound(CCA_KEY) : new CompoundTag();
		cca.remove(LEGACY_FILTER_ID);
		cca.put(FILTER_ID, comp);
		tag.put(CCA_KEY, cca);
	}

	private static final String ATTACHMENTS = "neoforge:attachments";
	private static final String BLOCK_ENTITIES = "block_entities";

	/**
	 * Vanilla and other mods' block entities (chests, barrels) do not call the hooks above, so their
	 * filter is carried through the chunk NBT instead: before a chunk is written, every block entity
	 * entry that has the filter attachment also gets the Cardinal Components entry the Fabric build
	 * reads. Runs on the chunk save path, only touches entries that carry a filter.
	 */
	public static void onChunkSave(CompoundTag chunk) {
		if (!chunk.contains(BLOCK_ENTITIES, Tag.TAG_LIST))return;
		ListTag list = chunk.getList(BLOCK_ENTITIES, Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag be = list.getCompound(i);
			if (!be.contains(ATTACHMENTS, Tag.TAG_COMPOUND))continue;
			CompoundTag filter = findFilter(be.getCompound(ATTACHMENTS));
			if (filter == null)continue;
			CompoundTag cca = be.contains(CCA_KEY, Tag.TAG_COMPOUND) ? be.getCompound(CCA_KEY) : new CompoundTag();
			if (cca.contains(FILTER_ID, Tag.TAG_COMPOUND))continue;
			CompoundTag comp = new CompoundTag();
			comp.put(DATA, filter.copy());
			cca.remove(LEGACY_FILTER_ID);
			cca.put(FILTER_ID, comp);
			be.put(CCA_KEY, cca);
		}
	}

	/**
	 * Counterpart of {@link #onChunkSave}, before the chunk's block entities are created: a block entity
	 * entry with a Fabric filter and no NeoForge attachment gets the attachment. The block entities
	 * of the chunk are built from these very tags afterwards.
	 */
	public static void onChunkLoad(CompoundTag chunk) {
		if (!chunk.contains(BLOCK_ENTITIES, Tag.TAG_LIST))return;
		ListTag list = chunk.getList(BLOCK_ENTITIES, Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag be = list.getCompound(i);
			CompoundTag data = findCcaFilterData(be);
			if (data == null)continue;
			CompoundTag att = be.contains(ATTACHMENTS, Tag.TAG_COMPOUND) ? be.getCompound(ATTACHMENTS) : new CompoundTag();
			if (findFilter(att) != null)continue;// NeoForge's own copy wins (an old key resolves through the alias)
			att.put(FILTER_ID, data.copy());
			be.put(ATTACHMENTS, att);
		}
	}
	/**
	 * Where {@link LegacyBlockFilters} puts the filters of a world upgraded from Minecraft 1.20.1: the data
	 * attachment entry NeoForge reads when the block entity loads. A block entity with a filter in either
	 * form (attachment or Fabric's Cardinal Components entry, any key) already has one.
	 */
	public static final LegacyBlockFilters.Target LEGACY_TARGET = new LegacyBlockFilters.Target() {
		@Override
		public boolean hasFilter(CompoundTag beTag) {
			if (beTag.contains(ATTACHMENTS, Tag.TAG_COMPOUND) && findFilter(beTag.getCompound(ATTACHMENTS)) != null)return true;
			return findCcaFilterData(beTag) != null;
		}

		@Override
		public void putFilter(CompoundTag beTag, CompoundTag filter) {
			CompoundTag att = beTag.contains(ATTACHMENTS, Tag.TAG_COMPOUND) ? beTag.getCompound(ATTACHMENTS) : new CompoundTag();
			att.put(FILTER_ID, filter);
			beTag.put(ATTACHMENTS, att);
		}
	};
}
