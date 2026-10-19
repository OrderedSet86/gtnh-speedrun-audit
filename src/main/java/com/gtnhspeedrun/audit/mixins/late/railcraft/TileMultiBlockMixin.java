package com.gtnhspeedrun.audit.mixins.late.railcraft;

import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.MachineSite;
import com.gtnhspeedrun.audit.trackers.MultiblockSinks;

import mods.railcraft.common.blocks.machine.TileMultiBlock;

/**
 * Logs Railcraft multiblock formation. Railcraft multiblocks, the coke oven of the coke% category among them, form in
 * testIfMasterBlock(), on the tile that becomes master once Railcraft validates the pattern. Every Railcraft multiblock
 * is logged. Verifiers filter the lines by class.
 */
@Mixin(value = TileMultiBlock.class, remap = false)
public class TileMultiBlockMixin {

    @Shadow(remap = false)
    protected boolean isMaster;

    @Unique
    private boolean audit$wasMaster; // noqa: NAR130

    @Inject(method = "testIfMasterBlock", at = @At("HEAD"), require = 1)
    private void audit$before(CallbackInfo ci) { // noqa: NAR130
        audit$wasMaster = isMaster;
    }

    @Inject(method = "testIfMasterBlock", at = @At("RETURN"), require = 1)
    private void audit$after(CallbackInfo ci) { // noqa: NAR130
        if (!(!audit$wasMaster && isMaster)) {
            return;
        }
        MultiblockSinks.logMultiblockFormed("railcraft", MachineSite.ofTile((TileEntity) (Object) this));
    }
}
