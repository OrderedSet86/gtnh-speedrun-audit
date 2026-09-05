package com.gtnhspeedrun.audit.mixins.late.railcraft;

import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.AuditSinks;

import mods.railcraft.common.blocks.machine.TileMultiBlock;

/**
 * Railcraft multiblocks (the coke oven of coke% among them) form in testIfMasterBlock: the tile that becomes
 * master when the pattern validates. Logged for every Railcraft multiblock; verifiers filter by class.
 */
@Mixin(value = TileMultiBlock.class, remap = false)
public class TileMultiBlockMixin {

    @Shadow(remap = false)
    private boolean isMaster;

    @Unique
    private boolean audit$wasMaster;

    @Inject(method = "testIfMasterBlock", at = @At("HEAD"), require = 1)
    private void audit$before(CallbackInfo ci) {
        audit$wasMaster = isMaster;
    }

    @Inject(method = "testIfMasterBlock", at = @At("RETURN"), require = 1)
    private void audit$after(CallbackInfo ci) {
        if (audit$wasMaster || !isMaster) {
            return;
        }
        final TileEntity self = (TileEntity) (Object) this;
        AuditSinks.multiblockFormed(
            "railcraft",
            self.getClass()
                .getSimpleName(),
            null,
            self.getWorldObj().provider.dimensionId,
            self.xCoord,
            self.yCoord,
            self.zCoord);
    }
}
