package com.gtnhspeedrun.audit.mixins.late.gt;

import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.FacingSinks;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.BaseMetaTileEntity;

/**
 * Log each turn of a GT machine to a new front facing. A wrench rotation and a controller's rotation are such turns.
 * Turns in the machine's first second aren't logged. The facing of a placed machine is set before the machine's first
 * tick. When a world loads, readFromNBT() sets the facing field and doesn't call setFrontFacing().
 * setFrontFacing(ForgeDirection) has the same signature in GT 5.09.51.482 (pack 2.8.4), 5.09.54.265 (pack 2.9.0) and
 * 5.09.54.275 (pack 2.9.1).
 */
@Mixin(value = BaseMetaTileEntity.class, remap = false)
public abstract class BaseMetaTileEntityFacingMixin {

    @Shadow
    private ForgeDirection mFacing;

    @Unique
    private ForgeDirection audit$facingBefore;

    @Inject(method = "setFrontFacing", at = @At("HEAD"), require = 1)
    private void audit$readFacingBefore(ForgeDirection aFacing, CallbackInfo ci) {
        audit$facingBefore = mFacing;
    }

    @Inject(method = "setFrontFacing", at = @At("RETURN"), require = 1)
    private void audit$logFacingChange(ForgeDirection aFacing, CallbackInfo ci) {
        final long STARTUP_TICKS = 20;
        // javac resolves every supertype of BaseMetaTileEntity for a call through BaseMetaTileEntity, and
        // ITileWithModularUI isn't on the compile classpath. Call through the GT interface
        final IGregTechTileEntity self = (IGregTechTileEntity) this;
        if (!(mFacing != audit$facingBefore && self.isServerSide() && self.getTimer() > STARTUP_TICKS)) {
            return;
        }
        FacingSinks.logFacingChange((TileEntity) (Object) this, audit$facingBefore.name(), mFacing.name());
    }
}
