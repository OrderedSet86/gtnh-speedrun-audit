package com.gtnhspeedrun.audit;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.gtnewhorizon.gtnhmixins.ILateMixinLoader;
import com.gtnewhorizon.gtnhmixins.LateMixin;

/**
 * Select the late mixins whose target mods are installed. The mod declares no hard dependency, so a dev environment or
 * a pack without some of the target mods still loads.
 */
@LateMixin
public class LateMixinLoader implements ILateMixinLoader {

    private static final Logger LOG = LogManager.getLogger(GtnhSpeedrunAudit.MODID);

    @Override
    public String getMixinConfig() {
        return "mixins.gtnhspeedrunaudit.late.json";
    }

    /**
     * Return the late mixins for the installed mods. NEI is a coremod. NEI classes load before the late phase, so the
     * NEI mixins are in the base mixin config, gated by AuditMixinPlugin. With the NEI mixins in this list, GTNH daily
     * 707 crashes.
     */
    @Override
    public List<String> getMixins(Set<String> loadedMods) {
        final List<String> mixins = new ArrayList<>();
        if (loadedMods.contains("gregtech")) {
            mixins.add("gt.MTEMultiBlockBaseMixin");
            mixins.add("gt.TTMultiblockBaseMixin");
            mixins.add("gt.MTEBrickedBlastFurnaceMixin");
            mixins.add("gt.MetaTileEntityMixin");
            mixins.add("gt.MetaPipeEntityMixin");
            mixins.add("gt.BaseTileEntityMixin");
            mixins.add("gt.BlockMachinesMixin");
            mixins.add("gt.GTPowerfailTrackerMixin");
        }
        if (loadedMods.contains("betterquesting")) {
            mixins.add("bq.ParticipantInfoMixin");
            mixins.add("bq.TaskBaseMixin");
            mixins.add("bq.QuestEditHandlersMixin");
        }
        if (loadedMods.contains("AWWayofTime")) {
            mixins.add("bloodmagic.TEAltarMixin");
        }
        if (loadedMods.contains("ExtraUtilities")) {
            mixins.add("extrautils.SlotTrashMixin");
        }
        if (loadedMods.contains("Railcraft")) {
            mixins.add("railcraft.TileMultiBlockMixin");
        }
        if (loadedMods.contains("serverutilities")) {
            mixins.add("serverutils.MessageEditNBTResponseMixin");
            mixins.add("serverutils.InvseeContainerMixin");
            mixins.add("serverutils.TeleportTaskMixin");
            mixins.add("serverutils.CmdTplastMixin");
        }
        // a pack without a target mod gets no audit of that mod's events, so log the selected list
        LOG.info("{} audit mixins selected for this pack: {}", mixins.size(), mixins);
        return mixins;
    }
}
