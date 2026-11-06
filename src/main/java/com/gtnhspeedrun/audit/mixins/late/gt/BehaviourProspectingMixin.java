package com.gtnhspeedrun.audit.mixins.late.gt;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.audit.trackers.ProspectSinks;

import gregtech.api.items.MetaBaseItem;
import gregtech.common.items.behaviors.BehaviourProspecting;

/** Marks a prospector's hammer click on a block as one prospecting use. The hammer's results are chat lines. */
@Mixin(value = BehaviourProspecting.class, remap = false)
public class BehaviourProspectingMixin {

    private static final String ON_ITEM_USE_FIRST = "onItemUseFirst(Lgregtech/api/items/MetaBaseItem;"
        + "Lnet/minecraft/item/ItemStack;Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/world/World;III"
        + "Lnet/minecraftforge/common/util/ForgeDirection;FFF)Z";

    /** Opens a prospecting use for the click, on the server. */
    @Inject(method = ON_ITEM_USE_FIRST, at = @At("HEAD"), require = 1)
    private void audit$beginUse(MetaBaseItem item, ItemStack stack, EntityPlayer player, // noqa: NAR130, NAR131
        World world, int x, int y, int z, ForgeDirection side, float hitX, float hitY, float hitZ,
        CallbackInfoReturnable<Boolean> cir) {
        if (!world.isRemote) {
            ProspectSinks.beginToolUse(
                player,
                new ProspectSinks.Tool("prospecting_hammer", stack),
                new ProspectSinks.Place(world, x, y, z));
        }
    }

    /** Ends the prospecting use of the click, on the server. */
    @Inject(method = ON_ITEM_USE_FIRST, at = @At("RETURN"), require = 1)
    private void audit$endUse(MetaBaseItem item, ItemStack stack, EntityPlayer player, // noqa: NAR130, NAR131
        World world, int x, int y, int z, ForgeDirection side, float hitX, float hitY, float hitZ,
        CallbackInfoReturnable<Boolean> cir) {
        if (!world.isRemote) {
            ProspectSinks.endToolUse();
        }
    }
}
