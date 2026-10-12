package com.obscura.storage.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Applies {@code SodiumBlockRendererMixin} only when Sodium 0.5.x is installed, so Mixin does not log a
 * class-not-found warning for the Sodium target on installs without Sodium (or with Sodium 0.6+).
 * Loaded very early: keep this class free of Minecraft and Sodium references.
 */
public class ObscuraMixinPlugin implements IMixinConfigPlugin {
	@Override
	public void onLoad(String mixinPackage) {
	}

	@Override
	public String getRefMapperConfig() {
		return null;
	}

	@Override
	public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
		if (mixinClassName.endsWith(".SodiumBlockRendererMixin"))
			return FabricLoader.getInstance().getModContainer("sodium")
					.map(m -> m.getMetadata().getVersion().getFriendlyString().startsWith("0.5")).orElse(false);
		return true;
	}

	@Override
	public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {
	}

	@Override
	public List<String> getMixins() {
		return null;
	}

	@Override
	public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}

	@Override
	public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {
	}
}
