package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.entity.BlockEntityType.BlockEntitySupplier;

import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.IForgeRegistry;
import net.minecraftforge.registries.RegisterEvent;
import net.minecraftforge.registries.RegistryObject;

import com.obscura.storage.StorageMod;
import com.obscura.storage.platform.LegacyRegistryAliases;

/** A registry entry of this mod. Forge twin: backed by a {@link DeferredRegister}, the value exists once the registry event ran. */
public class GameObject<T> {
	private final RegistryObject<T> value;

	protected GameObject(RegistryObject<T> value) {
		this.value = value;
	}

	public T get() {
		return value.get();
	}

	public ResourceLocation getId() {
		return value.getId();
	}

	public static class GameRegistry<T> {
		protected final DeferredRegister<T> handle;
		/** Every name registered here, for the aliases of the ids used before v5.0.0. */
		private final List<String> names = new ArrayList<>();

		public GameRegistry(IForgeRegistry<T> reg) {
			handle = DeferredRegister.create(reg, StorageMod.modid);
		}

		public <I extends T> GameObject<I> register(final String name, final Supplier<? extends I> sup) {
			names.add(name);
			return new GameObject<>(handle.register(name, sup));
		}

		/** The names registered so far (paths in this mod's namespace). */
		public List<String> names() {
			return Collections.unmodifiableList(names);
		}

		public void register(IEventBus bus) {
			handle.register(bus);
			bus.addListener(this::addLegacyAliases);
		}

		/**
		 * Worlds saved before v5.0.0 name every entry {@code toms_storage:<name>}: see
		 * {@link LegacyRegistryAliases}. Runs in the RegisterEvent of this registry, while it is unlocked.
		 */
		private void addLegacyAliases(RegisterEvent event) {
			if (!event.getRegistryKey().equals(handle.getRegistryKey()))return;
			LegacyRegistryAliases.addAliases(event.getForgeRegistry(), names);
		}
	}

	public static class GameRegistryBE extends GameRegistry<BlockEntityType<?>> {

		public GameRegistryBE(IForgeRegistry<BlockEntityType<?>> reg) {
			super(reg);
		}

		@SuppressWarnings("unchecked")
		public <BE extends BlockEntity, I extends BlockEntityType<BE>> GameObjectBlockEntity<BE> registerBE(String name, BlockEntitySupplier<BE> sup, GameObject<? extends Block>... blocks) {
			List<GameObject<? extends Block>> list = new ArrayList<>(Arrays.asList(blocks));
			// The block list is read when the type is built (block entity type registration), so
			// addBlocks() calls made during mod construction are included.
			GameObject<BlockEntityType<BE>> ro = register(name, () -> BlockEntityType.Builder.<BE>of(sup, list.stream().map(GameObject::get).toArray(Block[]::new)).build(null));
			return new GameObjectBlockEntity<>(ro.value, list);
		}
	}

	public static class GameObjectBlockEntity<T extends BlockEntity> extends GameObject<BlockEntityType<T>> {
		private final List<GameObject<? extends Block>> blocks;

		protected GameObjectBlockEntity(RegistryObject<BlockEntityType<T>> value, List<GameObject<? extends Block>> blocks) {
			super(value);
			this.blocks = blocks;
		}

		@SuppressWarnings("unchecked")
		public void addBlocks(GameObject<? extends Block>... blocks) {
			this.blocks.addAll(Arrays.asList(blocks));
		}
	}
}
