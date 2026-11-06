package com.gtnhspeedrun.audit.mixins.late.gt;

import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.GtRecipeStarts;
import com.gtnhspeedrun.audit.trackers.RecipeTallyCache;

import gregtech.api.interfaces.metatileentity.IMetaTileEntity;
import gregtech.api.interfaces.tileentity.IGregTechTileEntity;
import gregtech.api.metatileentity.implementations.MTEBasicMachine;

/**
 * Tallies every recipe start of a GT single-block machine. onPostTick() calls startProcess() after each successful
 * checkRecipe(), with mEUt (EU per tick) and mMaxProgresstime set. The hook is on the call, not in startProcess(), so a
 * subclass override of startProcess() does not hide the start. The call is in onPostTick() from GT 5.09.51.482 (pack
 * 2.8.4) through current.
 */
@Mixin(value = MTEBasicMachine.class, remap = false)
public class MTEBasicMachineMixin {

    @Shadow
    public int mEUt;

    @Shadow
    public int mMaxProgresstime;

    @Shadow
    @Final
    public ItemStack[] mOutputItems;

    @Shadow
    public FluidStack mOutputFluid;

    @Unique
    private RecipeTallyCache audit$recipeCache; // noqa: NAR130

    @Inject(
        method = "onPostTick",
        at = @At(
            value = "INVOKE",
            target = "Lgregtech/api/metatileentity/implementations/MTEBasicMachine;startProcess()V"),
        require = 1)
    private void audit$recipeStarted(IGregTechTileEntity aBaseMetaTileEntity, long aTick, // noqa: NAR130
        CallbackInfo ci) {
        if (audit$recipeCache == null) {
            audit$recipeCache = new RecipeTallyCache();
        }
        GtRecipeStarts.addStart(
            (IMetaTileEntity) (Object) this,
            audit$recipeCache,
            new GtRecipeStarts.RecipeStart(
                GtRecipeStarts.pickFirstOutput(mOutputItems, mOutputFluid),
                mEUt,
                mMaxProgresstime,
                null));
    }
}
