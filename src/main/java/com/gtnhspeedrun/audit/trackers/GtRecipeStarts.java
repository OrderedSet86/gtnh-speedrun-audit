package com.gtnhspeedrun.audit.trackers;

import java.math.BigInteger;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import org.jetbrains.annotations.Nullable;

import com.gtnhspeedrun.audit.GtnhSpeedrunAudit;
import com.gtnhspeedrun.audit.snapshot.ItemKey;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;

/**
 * Add a GT recipe start to the {@link RecipeTally} of the machine type and the recipe's first output. The GT types stay
 * in this class, for the recipe-start mixins of multiblocks and single-block machines.
 */
public final class GtRecipeStarts {

    private static final String NO_OUTPUT = "none";

    private GtRecipeStarts() {}

    /**
     * Add {@code start} for {@code machine}. A start whose GT state cannot be read is a read error of the interval, and
     * the server tick goes on.
     */
    public static void addStart(IMetaTileEntity machine, RecipeTallyCache cache, RecipeStart start) {
        final PowerUse powerUse = PowerUse.active();
        if (!powerUse.isRecording()) {
            return;
        }
        try {
            final Object output = start.output();
            final Object outputType = readOutputType(output);
            final int outputMeta = output instanceof ItemStack stack ? stack.getItemDamage() : 0;
            RecipeTally tally = cache.findTally(outputType, outputMeta);
            if (tally == null) {
                final String machineType = GtMachineSites.readSite(machine)
                    .formatTypeKey();
                tally = powerUse.findRecipeTally(new RecipeKey(machineType, formatOutputKey(output)));
                cache.remember(outputType, outputMeta, tally);
            }
            tally.addStart(readOutputAmount(output), start.euPerTick(), start.ticks());
            if (start.upfrontEu() != null) {
                tally.addEu(start.upfrontEu());
            }
        } catch (RuntimeException e) { // noqa: NAR142, degrade and report over GT machine code
            powerUse.noteReadError();
            GtnhSpeedrunAudit.LOG.debug(
                "power_use.recipe_unreadable machine={}",
                machine.getClass()
                    .getName(),
                e);
        }
    }

    /** Return the first non-null item output, otherwise the first non-null fluid output, otherwise null. */
    public static @Nullable Object pickFirstOutput(@Nullable ItemStack[] items, @Nullable FluidStack[] fluids) {
        final Object item = pickFirstItem(items);
        if (item != null || fluids == null) {
            return item;
        }
        for (FluidStack fluid : fluids) {
            if (fluid != null) {
                return fluid;
            }
        }
        return null;
    }

    /** Return the first non-null item output, otherwise {@code fluid}. */
    public static @Nullable Object pickFirstOutput(@Nullable ItemStack[] items, @Nullable FluidStack fluid) {
        final Object item = pickFirstItem(items);
        return item != null ? item : fluid;
    }

    private static @Nullable Object readOutputType(@Nullable Object output) {
        if (output instanceof ItemStack stack) {
            return stack.getItem();
        }
        if (output instanceof FluidStack fluid) {
            return fluid.getFluid();
        }
        return null;
    }

    private static String formatOutputKey(@Nullable Object output) {
        if (output instanceof ItemStack stack) {
            return ItemKey.formatBaseKey(stack);
        }
        if (output instanceof FluidStack fluid) {
            return "fluid:" + fluid.getFluid()
                .getName();
        }
        return NO_OUTPUT;
    }

    private static long readOutputAmount(@Nullable Object output) {
        if (output instanceof ItemStack stack) {
            return stack.stackSize;
        }
        if (output instanceof FluidStack fluid) {
            return fluid.amount;
        }
        return 0;
    }

    private static @Nullable ItemStack pickFirstItem(@Nullable ItemStack[] items) {
        if (items == null) {
            return null;
        }
        for (ItemStack item : items) {
            if (item != null) {
                return item;
            }
        }
        return null;
    }

    /**
     * One recipe start.
     *
     * @param output    an {@link ItemStack}, a {@link FluidStack}, or null for a recipe with no output
     * @param upfrontEu wireless EU drained in the recipe check, or null for none
     */
    public record RecipeStart(@Nullable Object output, long euPerTick, long ticks, @Nullable BigInteger upfrontEu) {}
}
