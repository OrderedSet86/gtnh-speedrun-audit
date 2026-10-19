package com.gtnhspeedrun.audit.mixins.late.bloodmagic;

import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.AuditSinks;
import com.gtnhspeedrun.audit.trackers.MachineSite;

import WayofTime.alchemicalWizardry.common.tileEntity.TEAltar;

/**
 * Logs Blood Altar tier changes. checkAndSetAltar() re-reads the altar's runes and sets upgradeLevel. Blood Magic calls
 * checkAndSetAltar() on activation, on dagger use and periodically, so a rebuilt altar's new tier is logged at the next
 * of those calls, not when the last rune is placed. upgradeLevel is saved with the tile, so an unchanged altar is not
 * logged again after a reload. The field and the method are the same from 1.7.52 (pack 2.8.4) through 1.9.13.
 */
@Mixin(value = TEAltar.class, remap = false)
public class TEAltarMixin {

    @Shadow
    private int upgradeLevel;

    @Unique
    private int audit$tierBefore; // noqa: NAR130

    @Inject(method = "checkAndSetAltar", at = @At("HEAD"), require = 1)
    private void audit$before(CallbackInfo ci) { // noqa: NAR130
        audit$tierBefore = upgradeLevel;
    }

    @Inject(method = "checkAndSetAltar", at = @At("RETURN"), require = 1)
    private void audit$after(CallbackInfo ci) { // noqa: NAR130
        if (upgradeLevel == audit$tierBefore) {
            return;
        }
        final TileEntity self = (TileEntity) (Object) this;
        if (!(self.getWorldObj() != null && !self.getWorldObj().isRemote)) {
            return;
        }
        AuditSinks.logBloodAltarTier(MachineSite.ofTile(self), audit$tierBefore, upgradeLevel);
    }
}
