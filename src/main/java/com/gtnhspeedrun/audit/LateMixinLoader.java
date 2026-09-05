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
        // Phase 4 fills this in: NEI cheat logging, GT5U multiblock formation + explosions, Railcraft coke oven.
        LOG.info("{} audit mixins selected for this pack: {}", mixins.size(), mixins);
        return mixins;
    }
}
