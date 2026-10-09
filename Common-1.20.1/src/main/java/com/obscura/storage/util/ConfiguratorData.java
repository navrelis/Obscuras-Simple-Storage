package com.obscura.storage.util;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.ItemStack;

public class ConfiguratorData {
	private static final String ROOT_TAG = "ts_configurator";
	private static final String BOUND_TAG = "bound";
	private static final String BOUND_B = "is_bound";
	private static final String SELECTING_TAG = "selecting";
	private static final String SHOW_INV_TAG = "show_inv_box";
	private static final String MASS_SELECT_TAG = "mass_select";
	private static final String BOX_START_TAG = "box_start";
	private static final String SELECTION_TAG = "selection";
	private static final String LAST_ACTION_TAG = "last_action";

	public static final State EMPTY = new State(BlockPos.ZERO, false, false, false, false, BlockPos.ZERO, Collections.emptyList(), 0L);

	private ConfiguratorData() {
	}

	/**
	 * The parsed state of the last configurator tag that was read. The client asks for it several times a frame
	 * (outline, overlay) and the selection can hold many positions; a tag is never edited in place, {@link #set}
	 * puts a new one, so the same tag object always means the same state.
	 */
	private record Cached(CompoundTag tag, State state) {}
	private static volatile Cached cached;

	public static State get(ItemStack stack) {
		CompoundTag root = stack.getTag();
		if (root == null || !root.contains(ROOT_TAG, Tag.TAG_COMPOUND)) {
			return EMPTY;
		}
		CompoundTag tag = root.getCompound(ROOT_TAG);
		Cached c = cached;
		if (c != null && c.tag() == tag) {
			return c.state();
		}
		State state = read(tag);
		cached = new Cached(tag, state);
		return state;
	}

	public static void set(ItemStack stack, State state) {
		if (state.isEmpty()) {
			CompoundTag root = stack.getTag();
			if (root != null) {
				root.remove(ROOT_TAG);
				if (root.isEmpty()) {
					stack.setTag(null);
				}
			}
			return;
		}
		stack.getOrCreateTag().put(ROOT_TAG, write(state));
	}

	private static CompoundTag write(State state) {
		CompoundTag tag = new CompoundTag();
		tag.put(BOUND_TAG, NbtUtils.writeBlockPos(state.bound));
		tag.putBoolean(BOUND_B, state.isBound);
		tag.putBoolean(SELECTING_TAG, state.selecting);
		tag.putBoolean(SHOW_INV_TAG, state.showInvBox);
		tag.putBoolean(MASS_SELECT_TAG, state.massSelect);
		tag.put(BOX_START_TAG, NbtUtils.writeBlockPos(state.boxStart));
		ListTag list = new ListTag();
		for (BlockPos pos : state.selection) {
			list.add(NbtUtils.writeBlockPos(pos));
		}
		tag.put(SELECTION_TAG, list);
		tag.putLong(LAST_ACTION_TAG, state.lastAction);
		return tag;
	}

	private static State read(CompoundTag tag) {
		BlockPos bound = tag.contains(BOUND_TAG, Tag.TAG_COMPOUND) ? NbtUtils.readBlockPos(tag.getCompound(BOUND_TAG)) : BlockPos.ZERO;
		boolean isBound = tag.getBoolean(BOUND_B);
		boolean selecting = tag.getBoolean(SELECTING_TAG);
		boolean showInv = tag.getBoolean(SHOW_INV_TAG);
		boolean massSelect = tag.getBoolean(MASS_SELECT_TAG);
		BlockPos boxStart = tag.contains(BOX_START_TAG, Tag.TAG_COMPOUND) ? NbtUtils.readBlockPos(tag.getCompound(BOX_START_TAG)) : BlockPos.ZERO;
		ListTag list = tag.getList(SELECTION_TAG, Tag.TAG_COMPOUND);
		List<BlockPos> selection = new ArrayList<>(list.size());
		for (int i = 0; i < list.size(); i++) {
			selection.add(NbtUtils.readBlockPos(list.getCompound(i)));
		}
		long lastAction = tag.getLong(LAST_ACTION_TAG);
		return new State(bound, isBound, selecting, showInv, massSelect, boxStart, selection, lastAction);
	}

	public static class State {
		private final BlockPos bound;
		private final boolean isBound;
		private final boolean selecting;
		private final boolean showInvBox;
		private final boolean massSelect;
		private final BlockPos boxStart;
		private final List<BlockPos> selection;
		private final long lastAction;

		public State(BlockPos bound, boolean isBound, boolean selecting, boolean showInvBox, boolean massSelect, BlockPos boxStart, List<BlockPos> selection, long lastAction) {
			this.bound = bound;
			this.isBound = isBound;
			this.selecting = selecting;
			this.showInvBox = showInvBox;
			this.massSelect = massSelect;
			this.boxStart = boxStart;
			this.selection = selection;
			this.lastAction = lastAction;
		}

		public BlockPos bound() {
			return bound;
		}

		public boolean isBound() {
			return isBound;
		}

		public boolean selecting() {
			return selecting;
		}

		public boolean showInvBox() {
			return showInvBox;
		}

		public boolean massSelect() {
			return massSelect;
		}

		public BlockPos boxStart() {
			return boxStart;
		}

		public List<BlockPos> selection() {
			return selection;
		}

		public long lastAction() {
			return lastAction;
		}

		private boolean isEmpty() {
			return !isBound && !selecting && !showInvBox && !massSelect && selection.isEmpty();
		}

		public State hiddenItem(long actionTime) {
			return new State(bound, isBound, selecting, false, false, boxStart, selection, actionTime);
		}

		public State showInv(Collection<BlockPos> selection, long actionTime) {
			return new State(bound, isBound, selecting, true, massSelect, boxStart, new ArrayList<>(selection), actionTime);
		}

		public State massSelectStart(BlockPos start, long actionTime) {
			return new State(bound, isBound, selecting, showInvBox, true, start, selection, actionTime);
		}

		public State massSelectEnd(long actionTime) {
			return new State(bound, isBound, selecting, showInvBox, false, BlockPos.ZERO, selection, actionTime);
		}

		public State massSelectEnd(Collection<BlockPos> selection, long actionTime) {
			return new State(bound, isBound, selecting, showInvBox, false, BlockPos.ZERO, new ArrayList<>(selection), actionTime);
		}

		public State setSelection(Collection<BlockPos> selection, long actionTime) {
			return new State(bound, isBound, selecting, showInvBox, massSelect, boxStart, new ArrayList<>(selection), actionTime);
		}

		public State startSelection(BlockPos bind, Collection<BlockPos> selection, long actionTime) {
			return new State(bind, true, true, false, false, BlockPos.ZERO, new ArrayList<>(selection), actionTime);
		}
	}

	public static class Configurator {
		private final ItemStack stack;
		private final long actionTime;
		private State state;

		public Configurator(ItemStack stack, long actionTime) {
			this.stack = stack;
			this.actionTime = actionTime;
			this.state = ConfiguratorData.get(stack);
		}

		public boolean debounce() {
			return actionTime - state.lastAction() < 3;
		}

		public BlockPos bound() {
			return state.bound();
		}

		public BlockPos boxStart() {
			return state.boxStart();
		}

		public boolean isBound() {
			return state.isBound();
		}

		public boolean massSelect() {
			return state.massSelect();
		}

		public boolean selecting() {
			return state.selecting();
		}

		public boolean showInvBox() {
			return state.showInvBox();
		}

		public List<BlockPos> selection() {
			return state.selection();
		}

		public void massSelectStart(BlockPos pos) {
			set(state.massSelectStart(pos, actionTime));
		}

		public void massSelectEnd() {
			set(state.massSelectEnd(actionTime));
		}

		public void clear() {
			set(EMPTY);
		}

		public void showInvBox(Collection<BlockPos> sel) {
			set(state.showInv(sel, actionTime));
		}

		public void massSelectEnd(Collection<BlockPos> sel) {
			set(state.massSelectEnd(sel, actionTime));
		}

		public void setSelection(Collection<BlockPos> sel) {
			set(state.setSelection(sel, actionTime));
		}

		public void startSelection(BlockPos bind, Collection<BlockPos> sel) {
			set(state.startSelection(bind, sel, actionTime));
		}

		private void set(State value) {
			state = value;
			ConfiguratorData.set(stack, value);
		}
	}
}