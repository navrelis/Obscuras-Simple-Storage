package com.obscura.storage.gametest;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.blaze3d.platform.NativeImage;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.renderer.v1.RendererAccess;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.world.phys.Vec3;

import com.obscura.storage.Content;
import com.obscura.storage.StorageMod;
import com.obscura.storage.block.IPaintable;
import com.obscura.storage.tile.PaintedBlockEntity;

/**
 * In-game visual proof that painted blocks show their paint with the renderer of the running client
 * (Indigo, Sodium, Sodium + Indium). Development only and inert unless the client is started with
 * {@code -Dobscuras.paintProof=<name>} (gradlew runPaintProof): it then creates a fresh superflat world,
 * lays out a grid of painted blocks next to vanilla reference blocks, takes a top-down and an angled
 * screenshot, compares the top face colour of every painted block with its reference and with stone,
 * writes {@code proof-out/<name>.txt} in the run directory and stops the client.
 *
 * Grid, seen from above (west to east = kinds, north to south = paints):
 * kinds: reference block, painted trim, painted framed cable, painted cable connector, painted proxy, stone;
 * paints: oak planks, bricks, glass, grass block, none (reference: the unpainted trim block).
 */
public final class PaintProofClient implements ClientModInitializer {
	private static final Logger LOG = LoggerFactory.getLogger("PaintProof");
	private static final String NAME = System.getProperty("obscuras.paintProof");
	private static final String WORLD = "paintproof";
	private static final int TIMEOUT_TICKS = 20 * 240;
	private static final double MATCH = 10.0;

	private static final String[] KINDS = { "reference", "painted_trim", "painted_cable", "painted_connector", "painted_proxy", "stone" };
	private static final String[] PAINTS = { "oak_planks", "bricks", "glass", "grass_block", "unpainted" };

	private enum Phase { TITLE, LOADING, BUILDING, SETTLE_TOP, SETTLE_ANGLE, DONE }

	private Phase phase = Phase.TITLE;
	private int ticks, phaseTicks, stableTicks;
	private final AtomicReference<String> sceneState = new AtomicReference<>();
	private volatile BlockPos origin;
	private Matrix4f viewProj;
	private Vec3 camPos;
	private final List<String> report = new ArrayList<>();

	@Override
	public void onInitializeClient() {
		if(NAME == null)return;
		writeDefaultOptions();
		WorldRenderEvents.END.register(ctx -> {
			viewProj = new Matrix4f(ctx.projectionMatrix()).mul(ctx.matrixStack().last().pose());
			camPos = ctx.camera().getPosition();
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if(phase == Phase.DONE)return;
			try {
				tick(mc);
			} catch (Throwable e) {
				LOG.error("Paint proof failed", e);
				finish(mc, "ERROR " + e);
			}
		});
	}

	/** First start of the proof run directory: no onboarding screen, no tutorial toast, no sound. */
	private static void writeDefaultOptions() {
		Path options = FabricLoader.getInstance().getGameDir().resolve("options.txt");
		if(Files.exists(options))return;
		try {
			Files.writeString(options, String.join("\n", "version:3465", "onboardAccessibility:false", "tutorialStep:none",
					"pauseOnLostFocus:false", "skipMultiplayerWarning:true", "soundCategory_master:0.0", "renderDistance:6",
					"simulationDistance:6", "enableVsync:false", "maxFps:60", "renderClouds:\"false\"", ""));
		} catch (IOException e) {
			LOG.warn("Could not write options.txt", e);
		}
	}

	private void tick(Minecraft mc) {
		ticks++;
		phaseTicks++;
		if(ticks > TIMEOUT_TICKS) {
			finish(mc, "ERROR timeout in phase " + phase);
			return;
		}
		switch (phase) {
		case TITLE -> {
			if(mc.getOverlay() != null || !(mc.screen instanceof TitleScreen) || phaseTicks < 20)return;
			mc.options.hideGui = true;
			mc.options.pauseOnLostFocus = false;
			mc.options.fov().set(70);
			mc.options.fovEffectScale().set(0.0);
			mc.options.bobView().set(false);
			next(Phase.LOADING);
			mc.tell(() -> createWorld(mc));
		}
		case LOADING -> {
			IntegratedServer server = mc.getSingleplayerServer();
			if(mc.player == null || mc.level == null || server == null || mc.screen != null || phaseTicks < 20)return;
			next(Phase.BUILDING);
			server.execute(() -> {
				try {
					buildScene(server);
					sceneState.set("ok");
				} catch (Throwable e) {
					LOG.error("Paint proof scene failed", e);
					sceneState.set("ERROR scene: " + e);
				}
			});
		}
		case BUILDING -> {
			String s = sceneState.get();
			if(s == null)return;
			if(!s.equals("ok")) {
				finish(mc, s);
				return;
			}
			next(Phase.SETTLE_TOP);
		}
		case SETTLE_TOP -> {
			if(!settled(mc, 100))return;
			describeEnvironment(mc);
			NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget());
			try {
				boolean ok = sample(mc, img);
				img.writeToFile(outDir().resolve(NAME + ".png"));
				if(!ok) {
					finish(mc, "ERROR grid not fully inside the screenshot");
					return;
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			} finally {
				img.close();
			}
			IntegratedServer server = mc.getSingleplayerServer();
			server.execute(() -> {
				ServerPlayer p = server.getPlayerList().getPlayers().get(0);
				p.teleportTo(p.serverLevel(), origin.getX() - 4.5, origin.getY() + 9 - 1.62, origin.getZ() - 12.5, 0, 42);
			});
			next(Phase.SETTLE_ANGLE);
		}
		case SETTLE_ANGLE -> {
			if(!settled(mc, 60))return;
			try(NativeImage img = Screenshot.takeScreenshot(mc.getMainRenderTarget())) {
				img.writeToFile(outDir().resolve(NAME + "_angle.png"));
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			finish(mc, "DONE");
		}
		default -> {}
		}
	}

	private void next(Phase p) {
		phase = p;
		phaseTicks = 0;
		stableTicks = 0;
	}

	/** True once the world has been on screen for {@code minTicks} with every chunk meshed for 40 ticks in a row. */
	private boolean settled(Minecraft mc, int minTicks) {
		if(mc.screen != null)mc.setScreen(null);
		stableTicks = mc.levelRenderer.hasRenderedAllChunks() && mc.screen == null && mc.getOverlay() == null ? stableTicks + 1 : 0;
		return phaseTicks >= minTicks && stableTicks >= 40 && viewProj != null;
	}

	private static void createWorld(Minecraft mc) {
		GameRules rules = new GameRules();
		rules.getRule(GameRules.RULE_DAYLIGHT).set(false, null);
		rules.getRule(GameRules.RULE_WEATHER_CYCLE).set(false, null);
		rules.getRule(GameRules.RULE_DOMOBSPAWNING).set(false, null);
		LevelSettings settings = new LevelSettings(WORLD, GameType.SPECTATOR, false, Difficulty.PEACEFUL, true, rules, WorldDataConfiguration.DEFAULT);
		mc.createWorldOpenFlows().createFreshLevel(WORLD, settings, new WorldOptions(0L, false, false),
				ra -> ra.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions());
	}

	private static BlockPos cell(BlockPos origin, int kind, int paint) {
		// Looking straight down with yaw 0, west is on the right of the screen and south at the top.
		return origin.offset(-2 * kind, 0, -2 * paint);
	}

	private static BlockState paintState(int paint) {
		return switch (paint) {
		case 0 -> Blocks.OAK_PLANKS.defaultBlockState();
		case 1 -> Blocks.BRICKS.defaultBlockState();
		case 2 -> Blocks.GLASS.defaultBlockState();
		case 3 -> Blocks.GRASS_BLOCK.defaultBlockState();
		default -> null;
		};
	}

	private void buildScene(IntegratedServer server) {
		ServerLevel level = server.overworld();
		level.setDayTime(6000);
		BlockPos o = new BlockPos(0, level.getHeight(Heightmap.Types.WORLD_SURFACE, 0, 0), 0);
		for(int x = -16; x <= 6; x++)
			for(int z = -14; z <= 6; z++)
				level.setBlockAndUpdate(new BlockPos(x, o.getY() - 1, z), Blocks.BLACK_CONCRETE.defaultBlockState());

		Block[] painted = { Content.paintedTrim.get(), StorageMod.invCablePainted.get(), StorageMod.invCableConnectorPainted.get(), StorageMod.invProxyPainted.get() };
		for(int p = 0; p < PAINTS.length; p++) {
			BlockState paint = paintState(p);
			level.setBlockAndUpdate(cell(o, 0, p), paint != null ? paint : Content.inventoryTrim.get().defaultBlockState());
			level.setBlockAndUpdate(cell(o, 5, p), Blocks.STONE.defaultBlockState());
			for(int k = 0; k < painted.length; k++) {
				BlockPos pos = cell(o, k + 1, p);
				level.setBlockAndUpdate(pos, painted[k].defaultBlockState());
				if(paint == null)continue;
				((IPaintable)painted[k]).paint(level, pos, paint);
				BlockEntity be = level.getBlockEntity(pos);
				if(!(be instanceof PaintedBlockEntity pbe) || pbe.getPaintedBlockState() != paint)
					throw new IllegalStateException(KINDS[k + 1] + " did not take the paint " + PAINTS[p] + ", block entity: " + be);
			}
		}
		origin = o;
		ServerPlayer player = server.getPlayerList().getPlayers().get(0);
		// Eye 10 blocks above the top faces, over the middle of the grid
		player.teleportTo(level, o.getX() - 4.5, o.getY() + 11 - 1.62, o.getZ() - 3.5, 0, 90);
	}

	private void describeEnvironment(Minecraft mc) {
		FabricLoader fl = FabricLoader.getInstance();
		for(String id : new String[] { "sodium", "indium", "fabric-renderer-indigo" })
			report.add("mod " + id + ": " + fl.getModContainer(id).map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("absent"));
		report.add("fabric renderer: " + (RendererAccess.INSTANCE.hasRenderer() ? RendererAccess.INSTANCE.getRenderer().getClass().getName() : "none"));
		report.add("level renderer chunk info: " + mc.levelRenderer.getChunkStatistics());
		String hook = "sodium BlockRenderer not present";
		try {
			Class<?> c = Class.forName("me.jellysquid.mods.sodium.client.render.chunk.compile.pipeline.BlockRenderer", false, getClass().getClassLoader());
			hook = "not merged";
			for(Method m : c.getDeclaredMethods())
				if(m.getName().contains("obscuras_storage$paintedQuads"))hook = "merged as " + m.getName();
		} catch (ClassNotFoundException | LinkageError e) {
		}
		report.add("SodiumBlockRendererMixin redirect: " + hook);
		report.add("camera: " + camPos);
	}

	/** Mean colour of the top face of every grid block; false when a face lies outside the screenshot. */
	private boolean sample(Minecraft mc, NativeImage img) {
		double[][][] mean = new double[KINDS.length][PAINTS.length][];
		for(int k = 0; k < KINDS.length; k++)
			for(int p = 0; p < PAINTS.length; p++) {
				mean[k][p] = meanTopFace(img, cell(origin, k, p));
				if(mean[k][p] == null)return false;
			}
		int paintShown = 0, stoneShown = 0, other = 0;
		for(int p = 0; p < PAINTS.length; p++) {
			report.add(String.format(Locale.ROOT, "%-12s reference rgb=%s  stone rgb=%s", PAINTS[p], rgb(mean[0][p]), rgb(mean[5][p])));
			for(int k = 1; k <= 4; k++) {
				double dRef = dist(mean[k][p], mean[0][p]), dStone = dist(mean[k][p], mean[5][p]);
				String look = dRef <= MATCH && dRef < dStone ? "PAINT" : dStone <= MATCH && dStone < dRef ? "STONE" : "OTHER";
				BlockEntity be = mc.level.getBlockEntity(cell(origin, k, p));
				String clientPaint = be instanceof PaintedBlockEntity pbe ? String.valueOf(pbe.getPaintedBlockState().getBlock()) : "no painted block entity";
				report.add(String.format(Locale.ROOT, "  %-18s %-12s rgb=%s dRef=%.1f dStone=%.1f looks=%s clientPaint=%s",
						KINDS[k], PAINTS[p], rgb(mean[k][p]), dRef, dStone, look, clientPaint));
				if(p == PAINTS.length - 1)continue;
				if(look.equals("PAINT"))paintShown++;
				else if(look.equals("STONE"))stoneShown++;
				else other++;
			}
		}
		report.add("summary: painted blocks=" + (paintShown + stoneShown + other) + " PAINT=" + paintShown + " STONE=" + stoneShown + " OTHER=" + other);
		return true;
	}

	private double[] meanTopFace(NativeImage img, BlockPos pos) {
		int w = img.getWidth(), h = img.getHeight();
		int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE;
		for(double dx : new double[] { 0.1, 0.9 })
			for(double dz : new double[] { 0.1, 0.9 }) {
				Vector4f v = new Vector4f((float)(pos.getX() + dx - camPos.x), (float)(pos.getY() + 1 - camPos.y), (float)(pos.getZ() + dz - camPos.z), 1).mul(viewProj);
				if(v.w <= 0)return null;
				int px = Math.round((v.x / v.w * 0.5f + 0.5f) * w), py = Math.round((0.5f - v.y / v.w * 0.5f) * h);
				x0 = Math.min(x0, px);
				x1 = Math.max(x1, px);
				y0 = Math.min(y0, py);
				y1 = Math.max(y1, py);
			}
		if(x0 < 0 || y0 < 0 || x1 >= w || y1 >= h || x1 <= x0 || y1 <= y0)return null;
		double r = 0, g = 0, b = 0;
		for(int y = y0; y <= y1; y++)
			for(int x = x0; x <= x1; x++) {
				int abgr = img.getPixelRGBA(x, y);
				r += abgr & 0xFF;
				g += abgr >> 8 & 0xFF;
				b += abgr >> 16 & 0xFF;
			}
		int n = (x1 - x0 + 1) * (y1 - y0 + 1);
		return new double[] { r / n, g / n, b / n };
	}

	private static double dist(double[] a, double[] b) {
		return Math.sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]));
	}

	private static String rgb(double[] c) {
		return String.format(Locale.ROOT, "(%3.0f,%3.0f,%3.0f)", c[0], c[1], c[2]);
	}

	private static Path outDir() throws IOException {
		return Files.createDirectories(FabricLoader.getInstance().getGameDir().resolve("proof-out"));
	}

	private void finish(Minecraft mc, String status) {
		phase = Phase.DONE;
		report.add("status: " + status);
		report.forEach(l -> LOG.info("[{}] {}", NAME, l));
		try {
			Files.write(outDir().resolve(NAME + ".txt"), report);
		} catch (IOException e) {
			LOG.error("Could not write the paint proof result", e);
		}
		mc.stop();
	}
}
