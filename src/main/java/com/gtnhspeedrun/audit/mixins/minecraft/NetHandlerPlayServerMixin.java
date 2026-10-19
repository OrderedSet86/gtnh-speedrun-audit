package com.gtnhspeedrun.audit.mixins.minecraft;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.C10PacketCreativeInventoryAction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.InventoryChanges;

/**
 * Logs creative-inventory item spawns. No Forge event covers the creative tabs. The client sends one
 * C10PacketCreativeInventoryAction for each slot that the player sets in the creative inventory, and the server copies
 * the stack in. The hook runs at HEAD, before the validity checks of vanilla, so a packet that vanilla rejects (not in
 * creative, bad slot) is still logged.
 * The mixin is in the base (early) config because NetHandlerPlayServer is a vanilla class, remapped through the
 * refmap.
 */
@Mixin(NetHandlerPlayServer.class)
public class NetHandlerPlayServerMixin {

    @Inject(method = "processCreativeInventoryAction", at = @At("HEAD"), require = 1)
    private void audit$creativeSlot(C10PacketCreativeInventoryAction packet, CallbackInfo ci) { // noqa: NAR130
        // reobfuscation maps this plain field access to the SRG name. @Shadow on playerEntity would need a refmap
        // entry
        final EntityPlayerMP playerEntity = ((NetHandlerPlayServer) (Object) this).playerEntity;
        final int slot = packet.func_149627_c();
        ItemStack previous = null;
        if (slot >= 0 && slot < playerEntity.inventoryContainer.inventorySlots.size()) {
            previous = playerEntity.inventoryContainer.getSlot(slot)
                .getStack();
        }
        InventoryChanges.logCreativeSlot(
            playerEntity,
            new InventoryChanges.SlotChange(slot, packet.func_149625_d(), previous),
            playerEntity.theItemInWorldManager.isCreative());
    }
}
