package com.gtnhspeedrun.audit.mixins;

import java.util.List;
import java.util.Set;

import org.spongepowered.asm.lib.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

/**
 * Gate for the EARLY (base-config) mixins. NEI is a coremod, so its classes are already defined by the time
 * the late phase prepares — the NEI mixins must ride the manifest-registered base config instead
 * (MixinTargetAlreadyLoadedException otherwise, found on daily 707). This runs at coremod time, long before
 * Loader knows any modids, so presence is probed as a classpath RESOURCE — Class.forName would define the
 * target class and recreate the exact problem being solved.
 */
public class AuditMixinPlugin implements IMixinConfigPlugin {

    private Boolean neiPresent;

    private boolean isNeiPresent() {
        if (neiPresent == null) {
            neiPresent = net.minecraft.launchwrapper.Launch.classLoader
                .getResource("codechicken/nei/NEIServerUtils.class") != null;
        }
        return neiPresent;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (mixinClassName.contains(".nei.")) {
            return isNeiPresent();
        }
        return true;
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

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
