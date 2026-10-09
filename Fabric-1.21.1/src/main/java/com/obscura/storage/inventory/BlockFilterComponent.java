package com.obscura.storage.inventory;

import org.ladysnake.cca.api.v3.component.Component;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup.Provider;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import com.obscura.storage.migration.LegacyBlockFilters;
import com.obscura.storage.migration.LegacyIds;

public class BlockFilterComponent implements Component {
	/** Where Cardinal Components keeps the block components in a block entity tag. */
	public static final String CCA_KEY = "cardinal_components";

	private BlockFilter filter;
	private BlockEntity be;

	public BlockFilterComponent(BlockEntity be) {
		this.be = be;
	}

	@Override
	public void readFromNbt(CompoundTag tag, Provider registryLookup) {
		if (tag.contains("data")) {
			filter = new BlockFilter(be.getBlockPos());
			filter.deserializeNBT(registryLookup, tag.getCompound("data"));
		}
	}

	@Override
	public void writeToNbt(CompoundTag tag, Provider registryLookup) {
		if(filter != null)
			tag.put("data", filter.serializeNBT(registryLookup));
	}

	public BlockFilter getFilter(boolean make) {
		if (filter == null && make)
			filter = new BlockFilter(be.getBlockPos());
		return filter;
	}

	public void remove(Level level, BlockPos pos) {
		if (filter != null)
			filter.dropContents(level, pos);
		filter = null;
		BlockFilter.bumpConfigEpoch();
	}

	/**
	 * Moves a block filter saved before v5.0.0 ({@code cardinal_components/toms_storage:block_filter}) to
	 * the current key in a block entity tag that is about to be loaded, see BlockEntityMixin. An entry
	 * under the current key wins; the old entry is dropped either way.
	 *
	 * @return whether the tag was changed
	 */
	public static boolean migrateLegacyKey(CompoundTag beTag) {
		if (beTag == null || !beTag.contains(CCA_KEY, Tag.TAG_COMPOUND))return false;
		CompoundTag cca = beTag.getCompound(CCA_KEY);
		String old = LegacyIds.oldBlockFilterKey();
		if (!cca.contains(old, Tag.TAG_COMPOUND))return false;
		Tag legacy = cca.get(old);
		cca.remove(old);
		String current = LegacyIds.blockFilterKey();
		if (!cca.contains(current, Tag.TAG_COMPOUND))cca.put(current, legacy);
		return true;
	}
	/**
	 * Where {@link com.obscura.storage.migration.LegacyBlockFilters} puts the filters of a world upgraded
	 * from Minecraft 1.20.1: the Cardinal Components entry CCA reads when the block entity loads.
	 */
	public static final LegacyBlockFilters.Target LEGACY_TARGET = new LegacyBlockFilters.Target() {
		@Override
		public boolean hasFilter(CompoundTag beTag) {
			if (!beTag.contains(CCA_KEY, Tag.TAG_COMPOUND))return false;
			CompoundTag cca = beTag.getCompound(CCA_KEY);
			// CCA writes an empty entry for a block entity without a filter: only one with data counts
			return cca.getCompound(LegacyIds.blockFilterKey()).contains("data", Tag.TAG_COMPOUND)
					|| cca.getCompound(LegacyIds.oldBlockFilterKey()).contains("data", Tag.TAG_COMPOUND);
		}

		@Override
		public void putFilter(CompoundTag beTag, CompoundTag filter) {
			CompoundTag cca = beTag.contains(CCA_KEY, Tag.TAG_COMPOUND) ? beTag.getCompound(CCA_KEY) : new CompoundTag();
			CompoundTag comp = new CompoundTag();
			comp.put("data", filter);
			cca.put(LegacyIds.blockFilterKey(), comp);
			beTag.put(CCA_KEY, cca);
		}
	};
}
