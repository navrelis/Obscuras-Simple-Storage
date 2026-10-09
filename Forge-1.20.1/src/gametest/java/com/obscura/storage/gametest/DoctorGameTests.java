package com.obscura.storage.gametest;

import static com.obscura.storage.gametest.GameTestUtil.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.LiteralContents;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;

import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;

import com.obscura.storage.Config;
import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.InventoryCableConnectorBlock;
import com.obscura.storage.doctor.DoctorCommand;
import com.obscura.storage.doctor.DoctorScan;
import com.obscura.storage.doctor.NetworkReport;
import com.obscura.storage.doctor.NetworkStats;
import com.obscura.storage.tile.InventoryConnectorBlockEntity;

/** Network Doctor: analysis, timing and the commands, run through the real command dispatcher. */
@GameTestHolder(StorageMod.modid)
@PrefixGameTestTemplate(false)
public class DoctorGameTests {
	// Connector with a terminal on top, a chest north of it and an enchanting table (a block entity
	// without an inventory) south of it; cables east to a cable connector facing a second chest.
	private static final BlockPos CONNECTOR = new BlockPos(1, 2, 3);
	private static final BlockPos TERMINAL = new BlockPos(1, 3, 3);
	private static final BlockPos LOCAL_CHEST = new BlockPos(1, 2, 2);
	private static final BlockPos TABLE = new BlockPos(1, 2, 4);
	private static final BlockPos CABLE_CONNECTOR = new BlockPos(5, 2, 3);
	private static final BlockPos REMOTE_CHEST = new BlockPos(6, 2, 3);

	private static void buildNetwork(GameTestHelper h) {
		chest(h, LOCAL_CHEST, new ItemStack(Items.GOLD_INGOT, 7));
		chest(h, REMOTE_CHEST, new ItemStack(Items.DIAMOND, 11), new ItemStack(Items.EMERALD, 3));
		h.setBlock(TABLE, Blocks.ENCHANTING_TABLE);
		h.setBlock(CONNECTOR, Content.connector.get());
		terminalAbove(h, TERMINAL);
		h.setBlock(CABLE_CONNECTOR, Content.invCableConnector.get().defaultBlockState().setValue(InventoryCableConnectorBlock.FACING, Direction.EAST));
		for (int x = 2; x <= 4; x++)place(h, new BlockPos(x, 2, 3), Content.invCable.get());
		refreshShape(h, CABLE_CONNECTOR);
	}

	/** Command output: the messages and the command's result. */
	record Run(int result, List<Component> lines, String text) {}

	/** Runs {@code command} (without slash) as a permission level 2 source without a player, like the console. */
	static Run run(GameTestHelper h, String command) {
		List<Component> lines = new ArrayList<>();
		CommandSource sink = new CommandSource() {
			@Override
			public void sendSystemMessage(Component c) {
				lines.add(c);
			}

			@Override
			public boolean acceptsSuccess() {
				return true;
			}

			@Override
			public boolean acceptsFailure() {
				return true;
			}

			@Override
			public boolean shouldInformAdmins() {
				return false;
			}
		};
		ServerLevel l = h.getLevel();
		CommandSourceStack src = new CommandSourceStack(sink, Vec3.atCenterOf(h.absolutePos(BlockPos.ZERO)), Vec2.ZERO, l, 2, "doctor-test", Component.literal("doctor-test"), l.getServer(), null);
		try {
			int r = l.getServer().getCommands().getDispatcher().execute(command, src);
			StringBuilder sb = new StringBuilder();
			StringBuilder shown = new StringBuilder();
			for (Component c : lines) {
				flatten(c, sb.append('\n'));
				shown.append('\n').append(c.getString());
			}
			// As the console would print it, for the log
			StorageMod.LOGGER.info("[DoctorGameTests] /{} -> {}{}", command, r, shown);
			return new Run(r, lines, sb.toString());
		} catch (CommandSyntaxException e) {
			throw new GameTestAssertException("/" + command + " failed: " + e.getMessage());
		}
	}

	/**
	 * The text of a component with the translation keys and all arguments spelled out, so the checks do
	 * not depend on the server having loaded the language files.
	 */
	static void flatten(Component c, StringBuilder sb) {
		if (c.getContents() instanceof TranslatableContents t) {
			sb.append('<').append(t.getKey()).append('>');
			for (Object a : t.getArgs()) {
				sb.append(' ');
				if (a instanceof Component ac)flatten(ac, sb);
				else sb.append(a);
			}
		} else if (c.getContents() instanceof LiteralContents p) {
			sb.append(p.text());
		}
		for (Component s : c.getSiblings())flatten(s, sb);
	}

	static String abs(GameTestHelper h, BlockPos rel) {
		BlockPos p = h.absolutePos(rel);
		return p.getX() + " " + p.getY() + " " + p.getZ();
	}

	static String bracket(GameTestHelper h, BlockPos rel) {
		BlockPos p = h.absolutePos(rel);
		return "[" + p.getX() + ", " + p.getY() + ", " + p.getZ() + "]";
	}

	static InventoryConnectorBlockEntity connector(GameTestHelper h, BlockPos rel) {
		return be(h, rel);
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400)
	public void doctorAtReportsNetwork(GameTestHelper h) {
		buildNetwork(h);
		InventoryConnectorBlockEntity c = connector(h, CONNECTOR);
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue(c.getDoctorStats() != null, "connector has not ticked yet");
			eq(c.getInventoryCount(), 2, "inventories (local chest + chest behind the cable connector)");
		})
		.thenWaitUntil(() -> {
			NetworkStats s = c.getDoctorStats();
			long now = h.getLevel().getGameTime();
			h.assertTrue(s.windowTicks(now) > 0 && s.samples(now) > 0, "no timing entries after ticking");
		})
		.thenExecute(() -> {
			NetworkReport r = DoctorScan.full(c, h.getLevel().getServer());
			NetworkReport.Details d = r.details();
			eq(r.inventories(), 2, "report inventories");
			eq(r.slots(), 54, "report slots");
			eq(r.slotsUsed(), 3, "report used slots");
			eq(d.uniqueItems(), 3, "unique items");
			eq(d.cables(), 3, "cables");
			eq(d.cableConnectors(), 1, "cable connectors");
			eq(d.terminals(), 1, "attached terminals");
			eq(d.cap(), 0, "cap is off by default");
			BlockPos table = h.absolutePos(TABLE);
			h.assertTrue(d.skipped().stream().anyMatch(s -> s.x() == table.getX() && s.y() == table.getY() && s.z() == table.getZ() && s.reason().equals(NetworkReport.Reasons.NOT_INVENTORY)),
					"enchanting table must be skipped as not an inventory: " + d.skipped());
			h.assertTrue(r.windowSeconds() > 0, "timing window is empty");

			// The command finds the network from the connector, a cable, the terminal and a chest behind the cable connector
			Run at = run(h, "obscuras_storage doctor at " + abs(h, CONNECTOR));
			eq(at.result(), 2, "doctor at result (inventories)");
			h.assertTrue(at.text().contains("chat.obscuras_storage.doctor.at.inventories> 2"), "inventories line: " + at.text());
			h.assertTrue(at.text().contains("chat.obscuras_storage.doctor.at.slots> 3 54"), "slots line: " + at.text());
			h.assertTrue(at.text().contains("chat.obscuras_storage.doctor.reason.not_inventory"), "skipped reason: " + at.text());
			h.assertTrue(at.text().contains(bracket(h, TABLE)), "skipped position: " + at.text());
			h.assertTrue(at.text().contains("chat.obscuras_storage.doctor.footnote"), "footnote");
			for (BlockPos p : List.of(new BlockPos(3, 2, 3), TERMINAL, REMOTE_CHEST, LOCAL_CHEST, CABLE_CONNECTOR)) {
				eq(run(h, "obscuras_storage doctor at " + abs(h, p)).result(), 2, "doctor at from " + p);
			}
			eq(run(h, "obscuras_storage doctor at " + abs(h, new BlockPos(7, 5, 7))).result(), 0, "no network in the air");

			Run top = run(h, "obscuras_storage doctor top 100");
			h.assertTrue(top.result() >= 1, "top lists networks");
			h.assertTrue(top.text().contains(bracket(h, CONNECTOR)), "top lists this network: " + top.text());
			h.assertTrue(top.text().contains("chat.obscuras_storage.doctor.top.total"), "top total line");

			// The one line summary the Inventory Configurator shows everyone
			StringBuilder summary = new StringBuilder();
			flatten(DoctorCommand.summaryLine(c, h.getLevel().getServer()), summary);
			h.assertTrue(summary.toString().contains("chat.obscuras_storage.doctor.summary> 2 54 6% 1 0"), "summary line: " + summary);
		})
		.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400)
	public void doctorExportWritesFiles(GameTestHelper h) {
		buildNetwork(h);
		InventoryConnectorBlockEntity c = connector(h, CONNECTOR);
		h.startSequence()
		.thenWaitUntil(() -> {
			h.assertTrue(c.getDoctorStats() != null, "connector has not ticked yet");
			eq(c.getInventoryCount(), 2, "inventories");
		})
		.thenExecute(() -> {
			Run ex = run(h, "obscuras_storage doctor export");
			h.assertTrue(ex.result() >= 1, "export result");
			Path dir = DoctorCommand.exportDir(h.getLevel().getServer());
			try (Stream<Path> files = Files.list(dir)) {
				List<Path> json = files.filter(p -> p.getFileName().toString().endsWith(".json")).toList();
				h.assertTrue(!json.isEmpty(), "no JSON export in " + dir);
				BlockPos abs = h.absolutePos(CONNECTOR);
				boolean found = false;
				for (Path p : json) {
					JsonObject o = JsonParser.parseString(Files.readString(p, StandardCharsets.UTF_8)).getAsJsonObject();
					JsonArray nets = o.getAsJsonArray("networks");
					for (JsonElement e : nets) {
						JsonObject n = e.getAsJsonObject();
						if (n.get("x").getAsInt() == abs.getX() && n.get("y").getAsInt() == abs.getY() && n.get("z").getAsInt() == abs.getZ()) {
							found = true;
							eq(n.get("inventories").getAsInt(), 2, "exported inventories");
							eq(n.getAsJsonObject("blocks").get("cables").getAsInt(), 3, "exported cables");
						}
					}
					String name = p.getFileName().toString();
					Path csv = p.resolveSibling(name.substring(0, name.length() - 5) + ".csv");
					h.assertTrue(Files.exists(csv), "CSV next to " + p);
					h.assertTrue(Files.readAllLines(csv, StandardCharsets.UTF_8).get(0).startsWith("rank,dimension,x,y,z"), "CSV header");
				}
				h.assertTrue(found, "this network is not in the export");
			} catch (IOException e) {
				throw new GameTestAssertException("export unreadable: " + e);
			}
			Run cfg = run(h, "obscuras_storage doctor config");
			h.assertTrue(cfg.result() > 5, "config lists the options");
			h.assertTrue(cfg.text().contains("invConnectorScanRange = "), "config value line: " + cfg.text());
			h.assertTrue(cfg.text().contains("doctorMaxInventories = 0"), "doctor cap option");
			h.assertTrue(cfg.text().contains("#obscuras_storage:inventory_connector_skip"), "skip tag listed");
			h.assertTrue(cfg.text().contains("obscuras_storage:inventory_connector"), "skip tag contents listed");
		})
		.thenSucceed();
	}

	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 300)
	public void doctorBlockExplains(GameTestHelper h) {
		buildNetwork(h);
		InventoryConnectorBlockEntity c = connector(h, CONNECTOR);
		h.startSequence()
		.thenWaitUntil(() -> eq(c.getInventoryCount(), 2, "inventories"))
		.thenExecute(() -> {
			Run chestId = run(h, "obscuras_storage doctor block minecraft:chest");
			eq(chestId.result(), DoctorScan.Verdict.JOINS.ordinal() + 1, "chest by id");
			h.assertTrue(chestId.text().contains("chat.obscuras_storage.doctor.block.container"), "chest decided by its class: " + chestId.text());
			eq(run(h, "obscuras_storage doctor block minecraft:barrel").result(), DoctorScan.Verdict.JOINS.ordinal() + 1, "barrel by id");
			// By id no block entity is created: a block entity that is not a known vanilla container is unknown until placed
			Run proxyId = run(h, "obscuras_storage doctor block obscuras_storage:inventory_proxy");
			eq(proxyId.result(), DoctorScan.Verdict.CHECK_PLACED.ordinal() + 1, "mod block entity by id");
			h.assertTrue(proxyId.text().contains("chat.obscuras_storage.doctor.block.block_entity"), "block entity finding: " + proxyId.text());
			eq(run(h, "obscuras_storage doctor block minecraft:ender_chest").result(), DoctorScan.Verdict.CHECK_PLACED.ordinal() + 1, "vanilla non-container block entity by id");
			eq(run(h, "obscuras_storage doctor block obscuras_storage:storage_terminal").result(), DoctorScan.Verdict.NETWORK_BLOCK.ordinal() + 1, "terminal by id");
			eq(run(h, "obscuras_storage doctor block obscuras_storage:inventory_cable").result(), DoctorScan.Verdict.NETWORK_BLOCK.ordinal() + 1, "cable by id");
			eq(run(h, "obscuras_storage doctor block obscuras_storage:trim").result(), DoctorScan.Verdict.TRIM.ordinal() + 1, "trim by id");
			Run table = run(h, "obscuras_storage doctor block at " + abs(h, TABLE));
			eq(table.result(), DoctorScan.Verdict.CANNOT_JOIN.ordinal() + 1, "placed enchanting table");
			h.assertTrue(table.text().contains("chat.obscuras_storage.doctor.block.no_inventory"), "no inventory finding: " + table.text());
			Run chest = run(h, "obscuras_storage doctor block at " + abs(h, REMOTE_CHEST));
			eq(chest.result(), DoctorScan.Verdict.JOINS.ordinal() + 1, "placed chest");
			h.assertTrue(chest.text().contains("chat.obscuras_storage.doctor.block.in_network") && chest.text().contains(bracket(h, CONNECTOR)), "network of the chest: " + chest.text());
			DoctorScan.BlockCheck conn = DoctorScan.checkBlock(Content.connector.get(), null, null);
			eq(conn.verdict(), DoctorScan.Verdict.NETWORK_BLOCK, "connector");
		})
		.thenSucceed();
	}

	/**
	 * The inventory cap. Its own batch: the cap is a server-wide option, no other test may run while it
	 * is set.
	 */
	@GameTest(template = GameTestUtil.EMPTY, timeoutTicks = 400, batch = "doctor_cap")
	public void doctorCapSkipsOverCap(GameTestHelper h) {
		BlockPos conn = new BlockPos(3, 2, 3);
		List<BlockPos> chests = List.of(new BlockPos(2, 2, 3), new BlockPos(4, 2, 3), new BlockPos(3, 2, 2));
		for (BlockPos p : chests)chest(h, p, new ItemStack(Items.IRON_INGOT, 1));
		h.setBlock(conn, Content.connector.get());
		InventoryConnectorBlockEntity c = connector(h, conn);
		int before = Config.get().doctorMaxInventories;
		h.startSequence()
		.thenWaitUntil(() -> eq(c.getInventoryCount(), 3, "inventories without a cap"))
		.thenExecute(() -> Config.get().doctorMaxInventories = 2)
		.thenWaitUntil(() -> eq(c.getInventoryCount(), 2, "inventories with cap 2"))
		.thenExecute(() -> {
			NetworkReport r = DoctorScan.full(c, h.getLevel().getServer());
			eq(r.details().cap(), 2, "cap in the report");
			eq(r.details().overCap(), 1, "over cap");
			h.assertTrue(r.details().skipped().stream().filter(s -> s.reason().equals(NetworkReport.Reasons.OVER_CAP)).count() == 1, "one skipped over cap: " + r.details().skipped());
			h.assertTrue(r.details().hints().contains(NetworkReport.Hints.OVER_CAP), "over cap hint");
			Run at = run(h, "obscuras_storage doctor at " + abs(h, conn));
			h.assertTrue(at.text().contains("chat.obscuras_storage.doctor.at.cap> 2 1"), "cap line: " + at.text());
			h.assertTrue(at.text().contains("chat.obscuras_storage.doctor.reason.over_cap"), "over cap reason: " + at.text());
			Config.get().doctorMaxInventories = before;
		})
		.thenWaitUntil(() -> eq(c.getInventoryCount(), 3, "inventories after the cap was lifted"))
		.thenExecute(() -> {
			Run reset = run(h, "obscuras_storage doctor reset");
			h.assertTrue(reset.result() >= 1, "reset clears at least this network");
			eq(c.getDoctorStats().samples(h.getLevel().getGameTime()), 0, "samples after reset");
		})
		.thenSucceed();
	}
}
