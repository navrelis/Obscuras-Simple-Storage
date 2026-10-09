package com.obscura.storage.block.entity;

import java.util.function.Supplier;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.component.DataComponentMap.Builder;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;

import com.obscura.storage.Content;
import com.obscura.storage.block.FramedInventoryCableBlock;
import com.obscura.storage.migration.LegacyData;
import com.obscura.storage.platform.PlatformBlockEntity;

public class PaintedBlockEntity extends PlatformBlockEntity {
	public static final ModelProperty<Supplier<BlockState>> FACADE_STATE = new ModelProperty<>();
	private BlockState blockState;
	/** Loaded from data of the 1.20.1 builds: a framed cable works its connections out again, see onLoad. */
	private boolean legacyConnections;

	public PaintedBlockEntity(BlockPos pos, BlockState state) {
		super(Content.paintedBE.get(), pos, state);
	}

	public PaintedBlockEntity(BlockEntityType<?> tileEntityTypeIn, BlockPos pos, BlockState state) {
		super(tileEntityTypeIn, pos, state);
	}

	public boolean setPaintedBlockState(BlockState blockState) {
		BlockState old = getPaintedBlockState();
		this.blockState = blockState;
		boolean changed = !getPaintedBlockState().equals(old);
		if(changed)markDirtyClient();
		return changed;
	}

	@Override
	public ModelData getModelData() {
		return ModelData.builder().with(FACADE_STATE, this::getPaintedBlockState).build();
	}

	@Override
	public void loadAdditional(CompoundTag compound, HolderLookup.Provider provider) {
		super.loadAdditional(compound, provider);
		// Stored with its data version; a state saved by the 1.20.1 builds goes through the data fixers
		blockState = LegacyData.readBlockState(provider.lookupOrThrow(Registries.BLOCK), compound.getCompound("block"));
		CompoundTag b = compound.getCompound("block");
		legacyConnections = !b.isEmpty() && !b.contains(LegacyData.DATA_VERSION_KEY);
		markDirtyClient();
	}

	@Override
	public void saveAdditional(CompoundTag compound, HolderLookup.Provider provider) {
		super.saveAdditional(compound, provider);
		if (blockState != null) {
			compound.put("block", LegacyData.writeBlockState(blockState));
		}
	}

	@Override
	public void onLoad() {
		super.onLoad();
		if (legacyConnections && level instanceof ServerLevel sl) {
			legacyConnections = false;
			// A 1.20.1 painted cable (block without connection properties) loads as a framed cable without
			// connections; next tick, outside of chunk loading, they are worked out from the neighbours.
			sl.getServer().tell(new TickTask(sl.getServer().getTickCount(), () -> {
				if (!isRemoved())FramedInventoryCableBlock.refreshConnections(sl, worldPosition);
			}));
		}
	}

	private void markDirtyClient() {
		setChanged();
		if (getLevel() != null) {
			BlockState state = getLevel().getBlockState(getBlockPos());
			requestModelDataUpdate();
			getLevel().sendBlockUpdated(getBlockPos(), state, state, 3);
		}
	}

	@Override
	public ClientboundBlockEntityDataPacket getUpdatePacket() {
		return ClientboundBlockEntityDataPacket.create(this);
	}

	@Override
	public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
		return saveWithFullMetadata(provider);
	}

	public BlockState getPaintedBlockState() {
		return blockState == null ? Blocks.AIR.defaultBlockState() : blockState;
	}

	@Override
	protected void applyImplicitComponents(DataComponentInput dataComponentInput) {
		super.applyImplicitComponents(dataComponentInput);
		blockState = dataComponentInput.get(Content.paintComponent.get());
	}

	@Override
	protected void collectImplicitComponents(Builder builder) {
		super.collectImplicitComponents(builder);
		if (this.blockState != null) {
			builder.set(Content.paintComponent.get(), blockState);
		}
	}

	@SuppressWarnings("deprecation")
	@Override
	public void removeComponentsFromTag(CompoundTag compoundTag) {
		super.removeComponentsFromTag(compoundTag);
		compoundTag.remove("block");
	}
}
