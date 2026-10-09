package com.obscura.storage.inventory;

import java.lang.ref.WeakReference;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelResource;

import com.obscura.storage.components.WorldPos;
import com.obscura.storage.migration.LegacyFiles;
import com.obscura.storage.migration.LegacyIds;

public class RemoteConnections extends SavedData {
	private static final String CONNECTIONS_TAG = "connections";
	public static final String CHANNEL_ID = "id";
	public static final String OWNER_ID = "owner";
	public static final String PUBLIC_TAG = "public";
	public static final String DISPLAY_NAME = "name";
	/** Saved data name, stored as {@code <world>/data/obscuras_storage_rc.dat}. */
	public static final String ID = "obscuras_storage_rc";
	/** Name used before v5.0.0, see {@link #migrateLegacyFile}. */
	public static final String LEGACY_ID = LegacyIds.OLD_NAMESPACE + "_rc";
	private static final SavedData.Factory<RemoteConnections> FACTORY = new SavedData.Factory<>(RemoteConnections::new, RemoteConnections::new, DataFixTypes.LEVEL);
	public static final String OWNER_NAME = "owner_name";
	private Map<UUID, Channel> connections = new HashMap<>();
	private Map<UUID, Set<UUID>> ownerToChannels = new HashMap<>();

	private RemoteConnections() {
	}

	private RemoteConnections(CompoundTag tag, HolderLookup.Provider provider) {
		var list = tag.getList(CONNECTIONS_TAG, Tag.TAG_COMPOUND);
		for (int i = 0; i < list.size(); i++) {
			CompoundTag t = list.getCompound(i);
			UUID channel = t.getUUID(CHANNEL_ID);
			Channel ch = new Channel(t);
			connections.put(channel, ch);
			ownerToChannels.computeIfAbsent(ch.owner, k -> new HashSet<>()).add(channel);
		}
	}

	/** The data storage whose legacy file was already looked for (the running server's overworld). */
	private static volatile WeakReference<DimensionDataStorage> checkedStorage = new WeakReference<>(null);

	public static RemoteConnections get(Level world) {
		ServerLevel sw = (ServerLevel) world;
		DimensionDataStorage storage = sw.getServer().overworld().getDataStorage();
		if (checkedStorage.get() != storage) {
			// Once per world, before the data is first read: the overworld keeps its saved data in <world>/data
			migrateLegacyFile(sw.getServer().getWorldPath(LevelResource.ROOT).resolve("data"));
			checkedStorage = new WeakReference<>(storage);
		}
		return load(storage);
	}

	/** Reads (or creates) the channels kept in {@code storage}. */
	public static RemoteConnections load(DimensionDataStorage storage) {
		return storage.computeIfAbsent(FACTORY, ID);
	}

	/**
	 * Inventory Link channels saved by a version before v5.0.0 live in {@code toms_storage_rc.dat}. The
	 * file content never changed (UUIDs, names and flags only), so it is moved to the new name once.
	 *
	 * @param dataDir the overworld's saved data directory ({@code <world>/data})
	 * @return whether an old file was moved
	 */
	public static boolean migrateLegacyFile(Path dataDir) {
		return LegacyFiles.migrate(dataDir.resolve(LEGACY_ID + ".dat"), dataDir.resolve(ID + ".dat"));
	}

	@Override
	public CompoundTag save(CompoundTag tag, HolderLookup.Provider provider) {
		ListTag list = new ListTag();
		connections.forEach((k, v) -> {
			CompoundTag t = new CompoundTag();
			t.putUUID(CHANNEL_ID, k);
			v.save(t);
			list.add(t);
		});
		tag.put(CONNECTIONS_TAG, list);
		return tag;
	}

	public static class Channel {
		public Set<WorldPos> connectors = new HashSet<>();
		public UUID owner;
		public String ownerName;
		public boolean publicChannel;
		public String displayName;

		public Channel(UUID owner, String ownerName, boolean publicChannel, String displayName) {
			this.owner = owner;
			this.ownerName = ownerName;
			this.publicChannel = publicChannel;
			this.displayName = displayName;
		}

		private Channel(CompoundTag t) {
			this(t.getUUID(OWNER_ID), t.getString(OWNER_NAME), t.getBoolean(PUBLIC_TAG), t.getString(DISPLAY_NAME));
		}

		public void register(ServerLevel world, BlockPos blockPos) {
			WorldPos pos = new WorldPos(world.dimension(), blockPos);
			connectors.add(pos);
		}

		public Set<IInventoryLink> findOthers(ServerLevel world, BlockPos blockPos, int lvl) {
			WorldPos pos = new WorldPos(world.dimension(), blockPos);
			connectors.add(pos);
			Set<IInventoryLink> found = new HashSet<>();
			Iterator<WorldPos> posItr = connectors.iterator();
			while (posItr.hasNext()) {
				WorldPos dimPos = posItr.next();
				if(!dimPos.equals(pos)) {
					BlockEntity te = dimPos.getBlockEntity(world);
					if(te instanceof IInventoryLink link) {
						if (link.isAccessibleFrom(world, blockPos, lvl))
							found.add(link);
					} else {
						posItr.remove();
					}
				}
			}
			return found;
		}

		public void save(CompoundTag t) {
			t.putUUID(OWNER_ID, owner);
			t.putBoolean(PUBLIC_TAG, publicChannel);
			t.putString(DISPLAY_NAME, displayName);
			t.putString(OWNER_NAME, ownerName);
		}

		public boolean canAccess(Player player) {
			return publicChannel || owner.equals(player.getUUID());
		}
	}

	public Channel getChannel(UUID connection) {
		return connections.get(connection);
	}

	public UUID makeChannel(String name, boolean isPublic, Player owner) {
		UUID playerId = owner.getGameProfile().getId();
		Set<UUID> owned = ownerToChannels.getOrDefault(playerId, Set.of());
		if (owned.size() >= 20) return null;
		UUID id = UUID.randomUUID();
		// Strip control, format and lone-surrogate characters. NOT \p{Z}: that would remove the
		// ordinary spaces out of legitimate channel names.
		name = name.replaceAll("[\\p{Cc}\\p{Cf}\\p{Cs}]", "").trim();
		// Blankness is tested on a separator-stripped copy, not on `name` itself: String.isBlank()
		// follows Character.isWhitespace, which deliberately excludes U+00A0, so a name made only of
		// non-breaking spaces would otherwise pass and render as an empty channel label.
		if(name.replaceAll("\\p{Z}", "").isBlank() || name.length() > 50)name = "Channel " + System.currentTimeMillis();
		connections.put(id, new Channel(owner.getGameProfile().getId(), owner.getGameProfile().getName(), isPublic, name));
		ownerToChannels.computeIfAbsent(playerId, k -> new HashSet<>()).add(id);
		setDirty();
		return id;
	}

	public void removeChannel(UUID id, UUID player) {
		Channel c = connections.get(id);
		if(c != null && c.owner.equals(player)) {
			connections.remove(id);
			Set<UUID> owned = ownerToChannels.get(c.owner);
			if (owned != null) {
				owned.remove(id);
				if (owned.isEmpty()) ownerToChannels.remove(c.owner);
			}
			setDirty();
		}
	}

	public void editChannel(UUID id, boolean pub, UUID player) {
		Channel c = connections.get(id);
		if(c != null && c.owner.equals(player)) {
			c.publicChannel = pub;
			setDirty();
		}
	}

	public void invalidateCache(UUID id) {
		Channel c = connections.get(id);
		if(c != null) {
			c.connectors.clear();
		}
	}

	public Stream<Entry<UUID, Channel>> streamChannels(Player player) {
		UUID playerId = player.getUUID();
		Set<UUID> ownedIds = ownerToChannels.getOrDefault(playerId, Set.of());
		return connections.entrySet().stream().filter(c -> ownedIds.contains(c.getKey()) || c.getValue().publicChannel);
	}
}
