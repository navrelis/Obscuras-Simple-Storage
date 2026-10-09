package com.obscura.storage.doctor;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;

import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import com.obscura.storage.Config;
import com.obscura.storage.StorageMod;
import com.obscura.storage.StorageTags;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;
import com.obscura.storage.doctor.DoctorScan.BlockCheck;
import com.obscura.storage.doctor.DoctorScan.Finding;
import com.obscura.storage.tidy.TidyJob;
import com.obscura.storage.tidy.TidyManager;
import com.obscura.storage.util.ItemLocator.Location;

/**
 * {@code /obscuras_storage doctor ...}: the Network Doctor commands (permission level 2). The tree is
 * shared by both loaders, which register it from their command registration event.
 *
 * <p>All player-facing text is translatable (keys {@code chat.obscuras_storage.doctor.*}). Positions
 * are clickable for players (click suggests a teleport command, shift-click inserts the matching
 * {@code doctor at} command); for the console, which has no click events, they carry the dimension.
 */
public final class DoctorCommand {
	public static final String ROOT = StorageMod.modid;
	private static final String K = "chat.obscuras_storage.doctor.";
	/** Positions listed per line before "+N more". */
	private static final int LISTED = 6;
	/** Tag entries listed by {@code doctor config} before "+N more". */
	private static final int TAG_LISTED = 16;
	private static final double TICK_MS = 50.0;
	/** The block tag of the trim blocks (the connector recognises trims by block type, the tag lists them). */
	private static final TagKey<Block> TRIMS = TagKey.create(Registries.BLOCK, new ResourceLocation(StorageMod.modid, "trims"));
	private static final DynamicCommandExceptionType UNKNOWN_BLOCK = new DynamicCommandExceptionType(id -> Component.translatable(K + "block.unknown", String.valueOf(id)));

	private DoctorCommand() {}

	public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
		dispatcher.register(Commands.literal(ROOT).requires(s -> s.hasPermission(2))
				.then(Commands.literal("doctor")
						.then(Commands.literal("top")
								.executes(c -> top(c.getSource(), 5))
								.then(Commands.argument("count", IntegerArgumentType.integer(1, 100))
										.executes(c -> top(c.getSource(), IntegerArgumentType.getInteger(c, "count")))))
						.then(Commands.literal("at")
								.executes(c -> at(c.getSource(), null, null))
								.then(Commands.argument("pos", BlockPosArgument.blockPos())
										.executes(c -> at(c.getSource(), BlockPosArgument.getBlockPos(c, "pos"), null))
										.then(Commands.argument("dimension", DimensionArgument.dimension())
												.executes(c -> at(c.getSource(), BlockPosArgument.getBlockPos(c, "pos"), DimensionArgument.getDimension(c, "dimension"))))))
						.then(Commands.literal("reset")
								.executes(c -> reset(c.getSource())))
						.then(Commands.literal("config")
								.executes(c -> config(c.getSource())))
						.then(Commands.literal("block")
								.executes(c -> blockLookedAt(c.getSource()))
								.then(Commands.literal("at")
										.then(Commands.argument("pos", BlockPosArgument.blockPos())
												.executes(c -> blockAt(c.getSource(), c.getSource().getLevel(), BlockPosArgument.getBlockPos(c, "pos")))
												.then(Commands.argument("dimension", DimensionArgument.dimension())
														.executes(c -> blockAt(c.getSource(), DimensionArgument.getDimension(c, "dimension"), BlockPosArgument.getBlockPos(c, "pos"))))))
								.then(Commands.argument("block", ResourceLocationArgument.id())
										.suggests((c, b) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.BLOCK.keySet(), b))
										.executes(c -> blockById(c.getSource(), ResourceLocationArgument.getId(c, "block")))))
						.then(Commands.literal("export")
								.executes(c -> export(c.getSource())))));
	}

	// ------------------------------------------------------------------ text helpers

	private static MutableComponent tr(String key, Object... args) {
		return Component.translatable(K + key, args);
	}

	private static MutableComponent label(String key, Object... args) {
		return tr(key, args).withStyle(ChatFormatting.GRAY);
	}

	private static MutableComponent val(String s) {
		return Component.literal(s).withStyle(ChatFormatting.WHITE);
	}

	private static MutableComponent val(long n) {
		return val(DoctorFormat.count(n));
	}

	private static MutableComponent unit(String key) {
		return tr("unit." + key).withStyle(ChatFormatting.DARK_GRAY);
	}

	private static ChatFormatting costColor(double ms) {
		return ms < 0.5 ? ChatFormatting.GREEN : ms < 2 ? ChatFormatting.YELLOW : ChatFormatting.RED;
	}

	/** A time value coloured by cost, followed by its unit. */
	private static MutableComponent ms(double ms, String unitKey) {
		return Component.literal(DoctorFormat.ms(ms)).withStyle(costColor(ms)).append(" ").append(unit(unitKey));
	}

	private static MutableComponent prefix() {
		return tr("prefix").withStyle(ChatFormatting.GOLD);
	}

	private static MutableComponent line(Component... parts) {
		MutableComponent c = Component.literal(" ");
		for (Component p : parts)c.append(p);
		return c;
	}

	private static void send(CommandSourceStack src, Component c) {
		src.sendSuccess(() -> c, false);
	}

	private static void header(CommandSourceStack src, Component content) {
		send(src, Component.empty().append(prefix()).append(" ").append(content));
	}

	/** "overworld" for vanilla dimensions, the full id otherwise. */
	public static String dimensionName(ResourceKey<Level> dim) {
		ResourceLocation l = dim.location();
		return "minecraft".equals(l.getNamespace()) ? l.getPath() : l.toString();
	}

	private static boolean isConsole(CommandSourceStack src) {
		return src.getPlayer() == null;
	}

	/**
	 * {@code [x, y, z]} in aqua. For a player it suggests a teleport on click and inserts the doctor at
	 * command on shift-click; the dimension follows when it is not the player's (always for the console).
	 */
	public static MutableComponent pos(CommandSourceStack src, ResourceKey<Level> dim, BlockPos p, @Nullable Component title) {
		MutableComponent c = Component.literal("[" + p.getX() + ", " + p.getY() + ", " + p.getZ() + "]").withStyle(ChatFormatting.AQUA);
		if (!isConsole(src)) {
			String tp = "/execute in " + dim.location() + " run tp @s " + p.getX() + " " + (p.getY() + 1) + " " + p.getZ();
			String at = "/" + ROOT + " doctor at " + p.getX() + " " + p.getY() + " " + p.getZ() + " " + dim.location();
			MutableComponent hover = Component.empty();
			if (title != null)hover.append(Component.empty().append(title).withStyle(ChatFormatting.WHITE)).append("\n");
			hover.append(tr("pos.click").withStyle(ChatFormatting.GRAY)).append("\n").append(tr("pos.shift_click").withStyle(ChatFormatting.GRAY));
			c.withStyle(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, tp))
					.withInsertion(at)
					.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, hover)));
		}
		if (isConsole(src) || !dim.equals(src.getLevel().dimension())) {
			c = Component.empty().append(c).append(" ").append(Component.literal(dimensionName(dim)).withStyle(ChatFormatting.DARK_PURPLE));
		}
		return c;
	}

	private static Component connectorTitle() {
		return Component.translatable("block.obscuras_storage.inventory_connector");
	}

	/** "3 cables", singular key for 1. */
	private static MutableComponent count(String key, long n) {
		return tr("count." + key + (n == 1 ? ".one" : ""), val(n)).withStyle(ChatFormatting.GRAY);
	}

	private static MutableComponent join(List<? extends Component> parts, String sep) {
		MutableComponent c = Component.empty();
		for (int i = 0; i < parts.size(); i++) {
			if (i > 0)c.append(Component.literal(sep).withStyle(ChatFormatting.GRAY));
			c.append(parts.get(i));
		}
		return c;
	}

	// ------------------------------------------------------------------ top

	static int top(CommandSourceStack src, int count) {
		MinecraftServer server = src.getServer();
		List<InventoryConnectorBlockEntity> list = DoctorScan.byCost(server);
		if (list.isEmpty()) {
			header(src, label("none"));
			return 0;
		}
		int shown = Math.min(count, list.size());
		double window = 0, total = 0;
		List<NetworkReport> reports = new ArrayList<>();
		for (InventoryConnectorBlockEntity c : list) {
			NetworkReport r = DoctorScan.quick(c, server);
			reports.add(r);
			window = Math.max(window, r.windowSeconds());
			total += r.avgMs();
		}
		header(src, Component.empty().append(tr("top.header").withStyle(ChatFormatting.WHITE)).append(" ")
				.append(tr("top.window", DoctorFormat.rate(window), shown, list.size()).withStyle(ChatFormatting.DARK_GRAY)));
		for (int i = 0; i < shown; i++) {
			NetworkReport r = reports.get(i);
			send(src, line(Component.literal((i + 1) + ". ").withStyle(ChatFormatting.GRAY),
					pos(src, DoctorScan.dimensionOf(r), new BlockPos(r.x(), r.y(), r.z()), connectorTitle()), Component.literal(" "),
					label("top.line", val(r.inventories()), val(r.slots()), ms(r.avgMs(), "ms_per_tick"), val(DoctorFormat.ms(r.peakMs())))));
		}
		send(src, line(label("top.total", val(list.size()), ms(total, "ms_per_tick"), val(DoctorFormat.share(total / TICK_MS)))));
		return list.size();
	}

	// ------------------------------------------------------------------ at

	private static @Nullable BlockPos lookedAt(CommandSourceStack src) {
		ServerPlayer p = src.getPlayer();
		if (p == null)return null;
		HitResult hit = p.pick(32, 1, false);
		if (hit instanceof BlockHitResult b && hit.getType() == HitResult.Type.BLOCK)return b.getBlockPos();
		return null;
	}

	static int at(CommandSourceStack src, @Nullable BlockPos pos, @Nullable ServerLevel dim) {
		ServerLevel level = dim != null ? dim : src.getLevel();
		if (pos == null)pos = lookedAt(src);
		if (pos == null) {
			header(src, label("no_target"));
			return 0;
		}
		InventoryConnectorBlockEntity c = DoctorScan.find(level, pos);
		if (c == null) {
			header(src, label("no_network", pos(src, level.dimension(), pos, null)));
			return 0;
		}
		NetworkReport r = DoctorScan.full(c, src.getServer());
		for (Component l : atLines(src, r))send(src, l);
		return Math.max(1, r.inventories());
	}

	/** The lines of {@code doctor at} for one network. */
	public static List<Component> atLines(CommandSourceStack src, NetworkReport r) {
		NetworkReport.Details d = r.details();
		ResourceKey<Level> dim = DoctorScan.dimensionOf(r);
		List<Component> out = new ArrayList<>();
		out.add(Component.empty().append(prefix()).append(" ")
				.append(tr("at.header", pos(src, dim, new BlockPos(r.x(), r.y(), r.z()), connectorTitle())).withStyle(ChatFormatting.WHITE)).append(" ")
				// The console already sees the dimension next to the position
				.append((isConsole(src) ? tr("at.header.detail.console", connectorTitle()) : tr("at.header.detail", connectorTitle(), dimensionName(dim))).withStyle(ChatFormatting.DARK_GRAY)));

		MutableComponent inv = label("at.inventories", val(r.inventories()));
		if (d.duplicates() > 0) {
			List<Component> parts = new ArrayList<>();
			if (d.dupSameStorage() > 0)parts.add(tr("dup.same_storage", DoctorFormat.count(d.dupSameStorage())));
			if (d.dupReachedTwice() > 0)parts.add(tr("dup.reached_twice", DoctorFormat.count(d.dupReachedTwice())));
			if (d.dupNetwork() > 0)parts.add(tr("dup.network", DoctorFormat.count(d.dupNetwork())));
			inv.append(" ").append(tr("at.duplicates", DoctorFormat.count(d.duplicates()), join(parts, ", ")).withStyle(ChatFormatting.DARK_GRAY));
		}
		if (d.labelled() > 0)inv.append(" ").append(tr("at.labelled", DoctorFormat.count(d.labelled())).withStyle(ChatFormatting.AQUA));
		out.add(line(inv));
		out.add(line(label("at.slots", val(r.slotsUsed()), val(r.slots()), val(DoctorFormat.percent(r.slotsUsed(), r.slots())), val(d.uniqueItems()))));

		List<Component> blocks = new ArrayList<>();
		blocks.add(count("cables", d.cables()));
		blocks.add(count("cable_connectors", d.cableConnectors()));
		if (d.interfaces() > 0)blocks.add(count("interfaces", d.interfaces()));
		if (d.proxies() > 0)blocks.add(count("proxies", d.proxies()));
		if (d.links() > 0)blocks.add(count("links", d.links()));
		if (d.linkedConnectors() > 0)blocks.add(count("linked_connectors", d.linkedConnectors()));
		MutableComponent bl = label("at.blocks", join(blocks, " · "));
		if (d.cablesTruncated())bl.append(" ").append(tr("at.blocks.truncated").withStyle(ChatFormatting.DARK_GRAY));
		out.add(line(bl));

		if (d.skipped().isEmpty()) {
			out.add(line(label("at.skipped.none")));
		} else {
			List<Component> sk = new ArrayList<>();
			for (int i = 0; i < d.skipped().size() && i < LISTED; i++) {
				NetworkReport.Skipped s = d.skipped().get(i);
				sk.add(Component.empty().append(pos(src, dim, new BlockPos(s.x(), s.y(), s.z()), tr("reason." + s.reason())))
						.append(" ").append(tr("reason." + s.reason()).withStyle(ChatFormatting.DARK_GRAY)));
			}
			MutableComponent l = label("at.skipped", Component.literal(DoctorFormat.count(d.skipped().size())).withStyle(ChatFormatting.YELLOW), join(sk, ", "));
			if (d.skipped().size() > LISTED)l.append(" ").append(tr("more", DoctorFormat.count(d.skipped().size() - LISTED)).withStyle(ChatFormatting.DARK_GRAY));
			out.add(line(l));
		}

		out.add(line(label("at.attached", join(List.of(count("terminals", d.terminals()), count("crafting_terminals", d.craftingTerminals()),
				count("hoppers", d.hoppers()), count("emitters", d.emitters()), count("filter_blocks", d.filterBlocks())), " · "))));
		out.add(line(label("at.cost", ms(r.avgMs(), "ms_per_tick"), Component.empty().append(val(DoctorFormat.ms(r.peakMs()))).append(" ").append(unit("ms")),
				val(DoctorFormat.rate(r.rescansPerMin())), val(DoctorFormat.rate(r.rebuildsPerMin()))),
				Component.literal(" "), tr("at.window", DoctorFormat.rate(r.windowSeconds())).withStyle(ChatFormatting.DARK_GRAY)));
		out.add(line(label("at.sync", val(r.playersWatching()), val(DoctorFormat.rate(r.packetsPerSec())),
				Component.empty().append(val(DoctorFormat.kilobytes(r.bytesPerSec()))).append(" ").append(unit("kb_per_s")))));
		if (d.cap() > 0)out.add(line(label("at.cap", val(d.cap()), val(d.overCap()))));
		else out.add(line(label("at.cap.off", tr("at.cap.off_value").withStyle(ChatFormatting.DARK_GRAY))));
		for (String h : d.hints()) {
			Object arg = switch (h) {
			case NetworkReport.Hints.RESCANS -> DoctorFormat.rate(r.rescansPerMin());
			case NetworkReport.Hints.REBUILDS -> DoctorFormat.rate(r.rebuildsPerMin());
			case NetworkReport.Hints.OVER_CAP -> DoctorFormat.count(d.overCap());
			case NetworkReport.Hints.SYNC -> DoctorFormat.rate(r.packetsPerSec());
			case NetworkReport.Hints.CABLES_TRUNCATED -> DoctorFormat.count(Config.get().invConnectorMaxCables);
			default -> "";
			};
			out.add(line(label("hint", tr("hint." + h, arg).withStyle(ChatFormatting.YELLOW))));
		}
		// A Tidy run on this network (its work is part of the cost above)
		TidyJob tidy = dim != null ? TidyManager.jobAt(dim, new BlockPos(r.x(), r.y(), r.z())) : null;
		if (tidy != null) {
			out.add(line(tidy.phase() == TidyJob.Phase.EXECUTE ?
					label("at.tidy.running", val(tidy.processed()), val(tidy.planned())) :
						label("at.tidy.planning", val(tidy.indexedInventories()), val(tidy.inventories()))));
		}
		out.add(line(tr("footnote").withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC)));
		return out;
	}

	// ------------------------------------------------------------------ reset

	static int reset(CommandSourceStack src) {
		List<InventoryConnectorBlockEntity> list = NetworkDoctor.connectors(src.getServer());
		for (InventoryConnectorBlockEntity c : list)c.getDoctorStats().reset();
		header(src, label("reset", val(list.size())));
		return list.size();
	}

	// ------------------------------------------------------------------ config

	private static String str(Object o) {
		if (o instanceof List<?> l)return l.isEmpty() ? "[]" : String.join(", ", l.stream().map(String::valueOf).toList());
		return String.valueOf(o);
	}

	private static void configLine(CommandSourceStack src, String name, Object value, Object def) {
		boolean changed = !Objects.equals(value, def);
		MutableComponent c = line(Component.literal(name + " = ").withStyle(ChatFormatting.GRAY),
				Component.literal(str(value)).withStyle(changed ? ChatFormatting.YELLOW : ChatFormatting.WHITE));
		if (changed)c.append(" ").append(tr("config.default", str(def)).withStyle(ChatFormatting.DARK_GRAY));
		send(src, c);
	}

	/** The options of the loader's Config (same field names on both loaders): public instance fields of simple types. */
	public static List<Field> configFields() {
		List<Field> out = new ArrayList<>();
		for (Field f : Config.class.getDeclaredFields()) {
			int m = f.getModifiers();
			if (!Modifier.isPublic(m) || Modifier.isStatic(m) || Modifier.isTransient(m))continue;
			if ("configVersion".equals(f.getName()))continue;
			Class<?> t = f.getType();
			if (t.isPrimitive() || t == String.class)out.add(f);
		}
		return out;
	}

	static int config(CommandSourceStack src) {
		Config cfg = Config.get();
		Config def = Config.defaults();
		header(src, Component.empty().append(tr("config.header").withStyle(ChatFormatting.WHITE)).append(" ").append(tr("config.legend").withStyle(ChatFormatting.DARK_GRAY)));
		int n = 0;
		for (Field f : configFields()) {
			try {
				configLine(src, f.getName(), f.get(cfg), f.get(def));
				n++;
			} catch (IllegalAccessException e) {
				// public fields only, cannot happen
			}
		}
		configLine(src, "blockedBlocks", List.copyOf(cfg.getBlockedBlockIds()), List.copyOf(def.getBlockedBlockIds()));
		send(src, line(label("config.blocked_resolved", val(cfg.getBlockedBlocks().size()))));
		configLine(src, "blockedMods", List.copyOf(cfg.getBlockedMods()), List.copyOf(def.getBlockedMods()));
		configLine(src, "multiblockInv", List.copyOf(cfg.multiblockInv), List.copyOf(def.multiblockInv));
		n += 3;

		header(src, tr("config.tags").withStyle(ChatFormatting.WHITE));
		tagLines(src, StorageTags.INV_CONNECTOR_SKIP, "connector_skip");
		tagLines(src, TRIMS, "trims");
		tagLines(src, StorageTags.INV_CONFIG_SKIP, "configurator_skip");
		tagLines(src, StorageTags.REMOTE_ACTIVATE, "remote_activate");
		tagLines(src, StorageTags.TIDY_SKIP, "tidy_skip");
		send(src, line(label("config.multiblock", val(Config.getMultiblockInvs().size()))));
		return n;
	}

	/** Block ids in a tag, as currently loaded. */
	public static List<String> tagContents(TagKey<Block> tag) {
		List<String> ids = new ArrayList<>();
		BuiltInRegistries.BLOCK.getTag(tag).ifPresent(set -> {
			for (Holder<Block> h : set)h.unwrapKey().ifPresent(k -> ids.add(k.location().toString()));
		});
		ids.sort(null);
		return ids;
	}

	private static void tagLines(CommandSourceStack src, TagKey<Block> tag, String purpose) {
		List<String> ids = tagContents(tag);
		send(src, line(Component.literal("#" + tag.location() + " ").withStyle(ChatFormatting.AQUA),
				label("config.tag_size", val(ids.size())), Component.literal(" "),
				tr("config.tag." + purpose).withStyle(ChatFormatting.DARK_GRAY)));
		if (ids.isEmpty())return;
		MutableComponent l = Component.literal("   " + String.join(", ", ids.subList(0, Math.min(TAG_LISTED, ids.size())))).withStyle(ChatFormatting.WHITE);
		if (ids.size() > TAG_LISTED)l.append(" ").append(tr("more", DoctorFormat.count(ids.size() - TAG_LISTED)).withStyle(ChatFormatting.DARK_GRAY));
		send(src, l);
	}

	// ------------------------------------------------------------------ block

	static int blockLookedAt(CommandSourceStack src) {
		BlockPos p = lookedAt(src);
		if (p == null) {
			header(src, label("no_target"));
			return 0;
		}
		return blockAt(src, src.getLevel(), p);
	}

	static int blockAt(CommandSourceStack src, ServerLevel level, BlockPos pos) {
		if (!level.isLoaded(pos)) {
			header(src, label("block.unloaded", pos(src, level.dimension(), pos, null)));
			return 0;
		}
		return showBlock(src, DoctorScan.checkBlock(level.getBlockState(pos).getBlock(), level, pos));
	}

	static int blockById(CommandSourceStack src, ResourceLocation id) throws CommandSyntaxException {
		Block b = BuiltInRegistries.BLOCK.getOptional(id).orElseThrow(() -> UNKNOWN_BLOCK.create(id));
		return showBlock(src, DoctorScan.checkBlock(b, null, null));
	}

	private static Object renderArg(CommandSourceStack src, Object o) {
		if (o instanceof Location l)return pos(src, l.dimension(), l.pos(), connectorTitle());
		if (o instanceof Integer || o instanceof Long)return val(((Number) o).longValue());
		return val(String.valueOf(o));
	}

	private static ChatFormatting verdictColor(DoctorScan.Verdict v) {
		return switch (v) {
		case JOINS -> ChatFormatting.GREEN;
		case CABLE_CONNECTOR_ONLY, CHECK_PLACED -> ChatFormatting.YELLOW;
		case CANNOT_JOIN, EXCLUDED -> ChatFormatting.RED;
		case NETWORK_BLOCK, TRIM -> ChatFormatting.AQUA;
		};
	}

	/** The lines of {@code doctor block}. */
	public static List<Component> blockLines(CommandSourceStack src, BlockCheck bc) {
		List<Component> out = new ArrayList<>();
		MutableComponent h = Component.empty().append(prefix()).append(" ").append(tr("block.header", Component.literal(bc.blockId()).withStyle(ChatFormatting.WHITE)).withStyle(ChatFormatting.WHITE));
		if (bc.at() != null)h.append(" ").append(tr("block.header.at", pos(src, bc.at().dimension(), bc.at().pos(), null)).withStyle(ChatFormatting.DARK_GRAY));
		out.add(h);
		for (Finding f : bc.findings()) {
			Object[] args = new Object[f.args().size()];
			for (int i = 0; i < args.length; i++)args[i] = renderArg(src, f.args().get(i));
			out.add(line(label("block." + f.code(), args)));
		}
		out.add(line(label("block.verdict", tr("verdict." + bc.verdict().name().toLowerCase(Locale.ROOT)).withStyle(verdictColor(bc.verdict())))));
		return out;
	}

	private static int showBlock(CommandSourceStack src, BlockCheck bc) {
		for (Component l : blockLines(src, bc))send(src, l);
		return bc.verdict().ordinal() + 1;
	}

	// ------------------------------------------------------------------ export

	private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT);

	/** Where {@code doctor export} writes: {@code <world>/obscuras_storage/doctor}. */
	public static Path exportDir(MinecraftServer server) {
		return server.getWorldPath(LevelResource.ROOT).resolve(StorageMod.modid).resolve("doctor").normalize();
	}

	/** Writes the export files and returns the JSON file's path (the CSV sits next to it). */
	public static Path writeExport(MinecraftServer server) throws IOException {
		List<NetworkReport> reports = new ArrayList<>();
		for (InventoryConnectorBlockEntity c : DoctorScan.byCost(server))reports.add(DoctorScan.full(c, server));
		LocalDateTime now = LocalDateTime.now();
		Path dir = exportDir(server);
		Files.createDirectories(dir);
		String base = "doctor-" + FILE_STAMP.format(now);
		Path json = dir.resolve(base + ".json");
		for (int i = 2; Files.exists(json); i++)json = dir.resolve(base + "-" + i + ".json");
		String name = json.getFileName().toString();
		Path csv = dir.resolve(name.substring(0, name.length() - 5) + ".csv");
		Files.writeString(json, DoctorExport.toJsonString(reports, now.withNano(0).toString(), 5), StandardCharsets.UTF_8);
		Files.writeString(csv, DoctorExport.toCsv(reports), StandardCharsets.UTF_8);
		return json;
	}

	static int export(CommandSourceStack src) {
		try {
			int n = NetworkDoctor.connectors(src.getServer()).size();
			Path json = writeExport(src.getServer());
			String p = json.toAbsolutePath().normalize().toString();
			String csv = p.substring(0, p.length() - 5) + ".csv";
			header(src, label("export.done", val(n), Component.literal(p).withStyle(s -> s.withColor(ChatFormatting.AQUA).withInsertion(p)),
					Component.literal(csv).withStyle(s -> s.withColor(ChatFormatting.AQUA).withInsertion(csv))));
			return Math.max(1, n);
		} catch (IOException | RuntimeException e) {
			StorageMod.LOGGER.warn("Network Doctor export failed", e);
			src.sendFailure(tr("export.failed", String.valueOf(e.getMessage())));
			return 0;
		}
	}

	// ------------------------------------------------------------------ Inventory Configurator

	/** The one line network summary the Inventory Configurator shows everyone (no timings). */
	public static Component summaryLine(InventoryConnectorBlockEntity c, MinecraftServer server) {
		DoctorScan.Summary s = DoctorScan.summary(c, server);
		int used = Math.max(0, s.slots() - s.slotsFree());
		return Component.empty().append(tr("network_prefix").withStyle(ChatFormatting.GOLD)).append(" ")
				.append(label("summary", val(s.inventories()), val(s.slots()), val(DoctorFormat.percent(used, s.slots())), val(s.terminals()), val(s.duplicates())));
	}
}
