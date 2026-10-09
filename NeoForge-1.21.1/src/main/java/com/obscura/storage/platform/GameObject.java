package com.obscura.storage.platform;

import java.util.Arrays;
import java.util.function.Supplier;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityType.BlockEntitySupplier;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

import com.obscura.storage.StorageMod;
import com.obscura.storage.migration.LegacyIds;

public class GameObject<T> {
	private final DeferredHolder<? super T, T> value;

	protected GameObject(DeferredHolder<? super T, T> value) {
		this.value = value;
	}

	public T get() {
		return value.get();
	}

	public static class GameRegistry<T> {
		protected final DeferredRegister<T> handle;

		public GameRegistry(ResourceKey<? extends Registry<T>> reg) {
			handle = DeferredRegister.create(reg, StorageMod.modid);
		}

		public <I extends T> GameObject<I> register(final String name, final Supplier<? extends I> sup) {
			addLegacyAlias(handle, name);
			return new GameObject<>(handle.register(name, sup));
		}

		public void register(IEventBus bus) {
			handle.register(bus);
		}
	}

	public ResourceLocation getId() {
		return value.getId();
	}

	/**
	 * Worlds saved before v5.0.0 name every entry {@code toms_storage:<name>}. NeoForge resolves a
	 * registry alias in every by-name lookup (Registry#get, getHolder, the byNameCodec and
	 * holderByNameCodec used for chunk palettes, item stacks, data component patches, block entity ids
	 * and data attachment keys) when the old name itself is not registered, so old worlds load the new
	 * entries. The few entries the 1.20.1 builds registered under another path also get an alias, see
	 * {@link LegacyIds#renamedPath}. Must be called before the registry's RegisterEvent, i.e. during mod
	 * construction.
	 */
	public static void addLegacyAlias(DeferredRegister<?> register, String name) {
		register.addAlias(LegacyIds.old(name), ResourceLocation.fromNamespaceAndPath(register.getNamespace(), name));
		// Entries the 1.20.1 builds registered under another path (inventory_proxy -> inv_proxy), both namespaces
		ResourceLocation current = ResourceLocation.fromNamespaceAndPath(register.getNamespace(), name);
		for (String old : LegacyIds.renamedFrom(register.getRegistryKey(), name)) {
			register.addAlias(ResourceLocation.fromNamespaceAndPath(register.getNamespace(), old), current);
			register.addAlias(LegacyIds.old(old), current);
		}
	}

	public static class GameRegistryBE extends GameRegistry<BlockEntityType<?>> {

		public GameRegistryBE() {
			super(Registries.BLOCK_ENTITY_TYPE);
		}

		@SuppressWarnings("unchecked")
		public <BE extends BlockEntity, I extends BlockEntityType<BE>> GameObjectBlockEntity<BE> registerBE(String name, BlockEntitySupplier<BE> sup, GameObject<? extends Block>... blocks) {
			addLegacyAlias(handle, name);
			return new GameObjectBlockEntity<>(handle.register(name, () -> {
				return BlockEntityType.Builder.<BE>of(sup, Arrays.stream(blocks).map(GameObject::get).toArray(Block[]::new)).build(null);
			}));
		}
	}

	public static class GameObjectBlockEntity<T extends BlockEntity> extends GameObject<BlockEntityType<T>> {

		protected GameObjectBlockEntity(DeferredHolder<BlockEntityType<?>, BlockEntityType<T>> value) {
			super(value);
		}
	}
}
