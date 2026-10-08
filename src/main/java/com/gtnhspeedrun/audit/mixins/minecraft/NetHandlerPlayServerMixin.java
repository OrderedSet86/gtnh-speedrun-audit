package com.gtnhspeedrun.audit.mixins.minecraft;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.C10PacketCreativeInventoryAction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.AuditSinks;

/**
 * Creative-inventory item spawns. No Forge event covers the creative tabs: the client sends one
 * C10PacketCreativeInventoryAction per slot it sets and the server copies the stack in. HEAD, before
 * vanilla's validity checks, so a packet vanilla rejects (not in creative, bad slot) is still recorded.
 * Rides the base (early) config: a vanilla class, remapped through the refmap.
 */
@Mixin(NetHandlerPlayServer.class)
public class NetHandlerPlayServerMixin {

    @Inject(method = "processCreativeInventoryAction", at = @At("HEAD"), require = 1)
    private void audit$creativeSlot(C10PacketCreativeInventoryAction packet, CallbackInfo ci) {
        // a plain field access, which reobfuscation renames. An @Shadow field would need a refmap entry
        final EntityPlayerMP playerEntity = ((NetHandlerPlayServer) (Object) this).playerEntity;
        final int slot = packet.func_149627_c();
        ItemStack previous = null;
        if (slot >= 0 && slot < playerEntity.inventoryContainer.inventorySlots.size()) {
            previous = playerEntity.inventoryContainer.getSlot(slot)
                .getStack();
        }
        AuditSinks.creativeSlot(
            playerEntity,
            slot,
            packet.func_149625_d(),
            previous,
            playerEntity.theItemInWorldManager.isCreative());
    }
}
