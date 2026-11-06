package com.gtnhspeedrun.audit.mixins.late.gt;

import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.gtnhspeedrun.audit.trackers.ProspectSinks;

import detrav.items.behaviours.BehaviourDetravToolElectricProspector;
import detrav.items.behaviours.BehaviourDetravToolProspector;
import gregtech.api.items.MetaBaseItem;

/**
 * Marks a click on a block with a Prospector's Scanner, electric or not, as one prospecting use. The results of both
 * tools are chat lines. BehaviourDetravToolElectricProspector has an override of onItemUse(), so both classes are
 * targets.
 */
@Mixin(value = { BehaviourDetravToolProspector.class, BehaviourDetravToolElectricProspector.class }, remap = false)
public class DetravProspectorMixin {

    private static final String ON_ITEM_USE = "onItemUse(Lgregtech/api/items/MetaBaseItem;Lnet/minecraft/item/ItemStack;"
        + "Lnet/minecraft/entity/player/EntityPlayer;Lnet/minecraft/world/World;IIIIFFF)Z";

    /** Opens a prospecting use for the click, on the server. */
    @Inject(method = ON_ITEM_USE, at = @At("HEAD"), require = 1)
    private void audit$beginUse(MetaBaseItem item, ItemStack stack, EntityPlayer player, // noqa: NAR130, NAR131
        World world, int x, int y, int z, int side, float hitX, float hitY, float hitZ,
        CallbackInfoReturnable<Boolean> cir) {
        if (!world.isRemote) {
            ProspectSinks.beginToolUse(
                player,
                new ProspectSinks.Tool("prospector_scanner", stack),
                new ProspectSinks.Place(world, x, y, z));
        }
    }

    /** Ends the prospecting use of the click, on the server. */
    @Inject(method = ON_ITEM_USE, at = @At("RETURN"), require = 1)
    private void audit$endUse(MetaBaseItem item, ItemStack stack, EntityPlayer player, // noqa: NAR130, NAR131
        World world, int x, int y, int z, int side, float hitX, float hitY, float hitZ,
        CallbackInfoReturnable<Boolean> cir) {
        if (!world.isRemote) {
            ProspectSinks.endToolUse();
        }
    }
}
