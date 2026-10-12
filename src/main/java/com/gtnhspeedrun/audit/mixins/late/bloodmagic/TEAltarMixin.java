package com.gtnhspeedrun.audit.mixins.late.bloodmagic;

import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.AuditSinks;

import WayofTime.alchemicalWizardry.common.tileEntity.TEAltar;

/**
 * Blood Altar tier changes. checkAndSetAltar is where the altar re-reads its runes and sets upgradeLevel; it
 * runs on activation, on dagger use and periodically, so a rebuilt altar's new tier shows up at the next of
 * those, not the moment the last rune is placed. upgradeLevel is saved with the tile, so a reload does not
 * re-log an unchanged altar. The field and method are the same from 1.7.52 (pack 2.8.4) through 1.9.13.
 */
@Mixin(value = TEAltar.class, remap = false)
public class TEAltarMixin {

    @Shadow
    private int upgradeLevel;

    @Unique
    private int audit$tierBefore;

    @Inject(method = "checkAndSetAltar", at = @At("HEAD"), require = 1)
    private void audit$before(CallbackInfo ci) {
        audit$tierBefore = upgradeLevel;
    }

    @Inject(method = "checkAndSetAltar", at = @At("RETURN"), require = 1)
    private void audit$after(CallbackInfo ci) {
        if (upgradeLevel == audit$tierBefore) {
            return;
        }
        final TileEntity self = (TileEntity) (Object) this;
        if (self.getWorldObj() == null || self.getWorldObj().isRemote) {
            return;
        }
        AuditSinks.bloodAltarTier(
            self.getWorldObj().provider.dimensionId,
            self.xCoord,
            self.yCoord,
            self.zCoord,
            audit$tierBefore,
            upgradeLevel);
    }
}
