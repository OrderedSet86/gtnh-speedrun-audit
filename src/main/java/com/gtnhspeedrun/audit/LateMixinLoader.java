package com.gtnhspeedrun.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

/**
 * Every integration mixin is conditional on its target mod actually being installed — the jar declares no hard
 * dependencies, so a dev environment or a stripped pack loads cleanly with whatever subset applies. The audit
 * coverage is only as wide as the mixins that loaded, so the selected set is logged.
 */
@LateMixin
public class LateMixinLoader implements ILateMixinLoader {

    private static final Logger LOG = LogManager.getLogger(GtnhSpeedrunAudit.MODID);

    @Override
    public String getMixinConfig() {
        return "mixins.gtnhspeedrunaudit.late.json";
    }

    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        final List<String> mixins = new ArrayList<>();
        // NEI mixins are NOT here: NEI is a coremod whose classes load before the late phase prepares, so they
        // ride the manifest-registered base config, gated by AuditMixinPlugin (daily-707 crash otherwise).
        if (loadedMods.contains("gregtech")) {
            mixins.add("gt.MTEMultiBlockBaseMixin");
            mixins.add("gt.MTEBrickedBlastFurnaceMixin");
            mixins.add("gt.MetaTileEntityMixin");
        }
        if (loadedMods.contains("Railcraft")) {
            mixins.add("railcraft.TileMultiBlockMixin");
        }
        // Coverage is only as wide as what loaded; every mixin is require=1, so each either applied or crashed loud.
        LOG.info("{} audit mixins selected for this pack: {}", mixins.size(), mixins);
        return mixins;
    }
}
