package com.gtnhspeedrun.audit.harness;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.init.Blocks;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;

import org.jetbrains.annotations.Nullable;

import com.gtnhspeedrun.audit.trackers.GtMachineSites;
import com.gtnhspeedrun.audit.trackers.PowerUse;
import com.gtnhspeedrun.audit.trackers.PowerUseCounter;

import gregtech.api.enums.ItemList;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.MetaTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;
import gregtech.api.metatileentity.implementations.MTEMultiBlockBase;
import tectech.thing.metaTileEntity.multi.base.TTMultiblockBase;

/**
 * Give the self-test's power_use check real GT consumers: an LV Electric Furnace with cobblestone to smelt, and drains
 * from the Active Transformer's energy hatch inside a tick frame of the transformer's controller. The class references
 * GT types, so use the class only when GT is loaded.
 */
public final class PowerUseDrill {

    /** EU of each drain through the Active Transformer. */
    public static final long DRAIN_EU = 7;

    private PowerUseDrill() {}

    /**
     * Place an LV Electric Furnace at {@code pos} as {@code player}, with a full buffer and cobblestone in the
     * furnace's input slot.
     *
     * @return null on success, otherwise what went wrong
     */
    public static @Nullable String placeFurnace(EntityPlayer player, WorldPos pos) {
        final int COBBLESTONE = 16;
        final ItemStack furnace = ItemList.Machine_LV_E_Furnace.get(1);
        if (!TecTechFixture.place(player, furnace, pos)) {
            return "furnace not placed";
        }
        if (!(readTile(pos) instanceof IGregTechTileEntity base
            && base.getMetaTileEntity() instanceof MTEBasicMachine machine)) {
            return "no GT basic machine at the furnace, got " + readTile(pos);
        }
        base.increaseStoredEnergyUnits(base.getEUCapacity(), true);
        // setInventorySlotContents() of the base sets the inventory-changed flag. The furnace then checks for a recipe
        // on the next tick
        base.setInventorySlotContents(machine.getInputSlot(), new ItemStack(Blocks.cobblestone, COBBLESTONE));
        return null;
    }

    /**
     * Fix every maintenance issue of the multiblock controller at {@code pos}. A TecTech multiblock runs only with a
     * repair status of 3 or more.
     *
     * @return null on success, otherwise what went wrong
     */
    public static @Nullable String repairController(WorldPos pos) {
        if (!(readTile(pos) instanceof IGregTechTileEntity base
            && base.getMetaTileEntity() instanceof MTEMultiBlockBase multi)) {
            return "no GT multiblock at the controller, got " + readTile(pos);
        }
        multi.fixAllIssues();
        return null;
    }

    /**
     * Drain the energy hatch at {@code hatchPos} {@code drains} times, {@link #DRAIN_EU} each, each drain in a tick
     * frame of the controller at {@code controllerPos}. Outside the frames, the hatch is refilled when the hatch has
     * less EU than the drains take.
     */
    public static DrainResult drainThroughController(WorldPos controllerPos, WorldPos hatchPos, int drains) {
        if (!(readTile(controllerPos) instanceof IGregTechTileEntity controller
            && controller.getMetaTileEntity() instanceof MetaTileEntity controllerMachine)) {
            return new DrainResult("no GT machine at the controller, got " + readTile(controllerPos), 0);
        }
        if (!(readTile(hatchPos) instanceof IGregTechTileEntity hatch)) {
            return new DrainResult("no GT machine at the hatch, got " + readTile(hatchPos), 0);
        }
        final PowerUse powerUse = PowerUse.active();
        final PowerUseCounter counter = (PowerUseCounter) controller;
        if (hatch.getStoredEU() < DRAIN_EU * drains) {
            hatch.increaseStoredEnergyUnits(hatch.getEUCapacity(), true);
        }
        final long meterBytes = measureMeterBytes();
        final long before = AllocationMeter.readThreadAllocatedBytes();
        for (int i = 0; i < drains; i++) {
            powerUse.enterTile(counter, controllerMachine.getEUVar());
            hatch.decreaseStoredEnergyUnits(DRAIN_EU, false);
            powerUse.exitTile(controllerMachine.getEUVar());
        }
        final long after = AllocationMeter.readThreadAllocatedBytes();
        return new DrainResult(null, before < 0 ? -1 : after - before - meterBytes);
    }

    /**
     * Switch on the TecTech controller at {@code pos} and set the controller's power pass, so the controller starts a
     * routing recipe on the next tick. The Active Transformer copies the work switch into {@code ePowerPass} only on
     * every 32nd tick. A controller that loses the structure while {@code ePowerPass} is set and the buffer has more
     * than 2048 EU explodes, so call this method only after the last structure change.
     *
     * @return null on success, otherwise what went wrong
     */
    public static @Nullable String startController(WorldPos pos) {
        if (!(readTile(pos) instanceof IGregTechTileEntity base
            && base.getMetaTileEntity() instanceof TTMultiblockBase multi)) {
            return "no TecTech multiblock at the controller, got " + readTile(pos);
        }
        multi.ePowerPass = true;
        base.enableWorking();
        return null;
    }

    /** Return the machine type key of the GT machine at {@code pos}, as power_use writes the key. */
    public static String readTypeKey(WorldPos pos) {
        if (!(readTile(pos) instanceof IGregTechTileEntity base)) {
            return "no GT machine at " + pos;
        }
        return GtMachineSites.readSite(base)
            .formatTypeKey();
    }

    /** Return the bytes that a pair of {@link AllocationMeter} reads allocates by itself, about 800. */
    private static long measureMeterBytes() {
        final long before = AllocationMeter.readThreadAllocatedBytes();
        return AllocationMeter.readThreadAllocatedBytes() - before;
    }

    private static TileEntity readTile(WorldPos pos) {
        return pos.world()
            .getTileEntity(pos.x(), pos.y(), pos.z());
    }

    /**
     * @param problem        what went wrong, or null on success
     * @param allocatedBytes the bytes the measured drains allocated, -1 on a JVM with no allocation counter
     */
    public record DrainResult(@Nullable String problem, long allocatedBytes) {}
}
