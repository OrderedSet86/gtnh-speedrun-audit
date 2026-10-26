package com.gtnhspeedrun.audit.mixins.late.gt;

import net.minecraft.block.Block;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.trackers.GtMachineSites;
import com.gtnhspeedrun.audit.trackers.MachinePlacementTracker;
import com.gtnhspeedrun.audit.trackers.MachineRemovals;
import com.gtnhspeedrun.audit.trackers.MachineSite;

import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.common.blocks.BlockMachines;

/**
 * Reports each removal of a GT machine, hatch, pipe or cable block to {@link MachineRemovals}. breakBlock() runs for
 * every removal of the block: a harvest, an explosion, a fire, the Matter Manipulator and setBlock().
 */
@Mixin(value = BlockMachines.class, remap = false)
public class BlockMachinesMixin {

    /**
     * Report the removal at the block's position. Chunk calls breakBlock() after the block is replaced and before
     * the tile is removed, so the GT tile is still readable at HEAD.
     */
    @Inject(method = "breakBlock", at = @At("HEAD"), remap = true, require = 1)
    private void audit$removed(World world, int x, int y, int z, Block block, int meta, // noqa: NAR130, NAR131
        CallbackInfo ci) {
        if (world.isRemote) {
            return;
        }
        try {
            final TileEntity tile = world.getTileEntity(x, y, z);
            if (tile instanceof IGregTechTileEntity base) {
                // getDamageValue() of the machine block returns the tile's meta tile ID
                MachineRemovals.logMachineRemoval(
                    GtMachineSites.readSite(base),
                    MachinePlacementTracker.readGtMachine(block, block.getDamageValue(world, x, y, z), tile));
            } else {
                // Matter Manipulator 0.1.59 removes the tile of a moved block, then sets the spot to air
                MachineRemovals
                    .logMachineRemoval(new MachineSite("?", null, world.provider.dimensionId, x, y, z), null);
            }
        } catch (RuntimeException | LinkageError e) { // noqa: NAR142, degrade and report over GT machine code
            GtnhSpeedrunAudit.LOG
                .error("machine_removed.failed dim={} x={} y={} z={}", world.provider.dimensionId, x, y, z, e);
        }
    }
}
