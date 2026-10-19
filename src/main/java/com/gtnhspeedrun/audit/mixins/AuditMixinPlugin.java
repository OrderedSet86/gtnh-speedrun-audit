package com.gtnhspeedrun.audit.mixins;

import java.util.List;
import java.util.Set;

import net.minecraft.launchwrapper.Launch;

import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Choose which mixins of the base (early) config apply.
 *
 * <p>
 * NEI is a coremod, so the game has defined the NEI classes before the late mixin phase starts. The NEI mixins are
 * therefore in the base config. The jar manifest registers the base config. In the late config, Mixin throws
 * MixinTargetAlreadyLoadedException for the NEI mixins (seen on daily 707). This plugin runs at coremod time, long
 * before Loader has a list of mod ids, so the plugin probes for NEI as a classpath resource. Class.forName() would
 * define the target class and cause the same exception. StructureLib is a coremod too, so the structure bounds mixin
 * is in the base config as well. With the system property `speedrunaudit.noStructureBounds` set, the structure bounds
 * mixin does not apply. The property is a kill switch, and the baseline for StructureBench.
 */
public class AuditMixinPlugin implements IMixinConfigPlugin {

    public static final String NO_STRUCTURE_BOUNDS_PROPERTY = "speedrunaudit.noStructureBounds";

    private Boolean neiPresent;

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".nei.")) {
            return isNeiPresent();
        }
        if (mixinClassName.contains(".structurelib.")) {
            return System.getProperty(NO_STRUCTURE_BOUNDS_PROPERTY) == null
                && Launch.classLoader.getResource("com/gtnewhorizon/structurelib/structure/StructureUtility.class")
                    != null;
        }
        return true;
    }

    private boolean isNeiPresent() {
        if (neiPresent == null) {
            neiPresent = Launch.classLoader.getResource("codechicken/nei/NEIServerUtils.class") != null;
        }
        return neiPresent;
    }

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    /** The plugin changes no target class before a mixin applies. */
    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    /** The plugin changes no target class after a mixin applies. */
    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
