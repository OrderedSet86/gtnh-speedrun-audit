package com.gtnhspeedrun.audit.mixins.minecraft;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.play.client.C0EPacketClickWindow;
import net.minecraft.network.play.client.C10PacketCreativeInventoryAction;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.ContainerSessions;
import com.gtnhspeedrun.audit.trackers.InventoryChanges;
import com.gtnhspeedrun.audit.trackers.PatternSinks;

/**
 * Logs creative-inventory item spawns, and the clicks of a creative player in an open GUI. No Forge event covers the
 * creative tabs. Each slot that the player sets in the creative inventory arrives as one
 * C10PacketCreativeInventoryAction, and the server copies the stack in. The creative-slot hook runs at HEAD, before the
 * validity checks of vanilla, so a packet that vanilla rejects (not in creative, bad slot) is still logged. The mixin
 * is in the base (early) config because NetHandlerPlayServer is a vanilla class, remapped through the refmap.
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

    /**
     * Marks the clicking player for a pattern provider's slot change, and copies the open GUI's slots before a click by
     * a creative player, in a GUI that is not the inventory.
     */
    @Inject(method = "processClickWindow", at = @At("HEAD"), require = 1)
    private void audit$clickHead(C0EPacketClickWindow packet, CallbackInfo ci) { // noqa: NAR130
        final EntityPlayerMP playerEntity = ((NetHandlerPlayServer) (Object) this).playerEntity;
        PatternSinks.beginPlayerAction(playerEntity);
        if (audit$isLoggedClick(playerEntity, packet)) {
            ContainerSessions.clickHead(playerEntity);
        }
    }

    @Inject(method = "processClickWindow", at = @At("RETURN"), require = 1)
    private void audit$clickReturn(C0EPacketClickWindow packet, CallbackInfo ci) { // noqa: NAR130
        final EntityPlayerMP playerEntity = ((NetHandlerPlayServer) (Object) this).playerEntity;
        PatternSinks.endPlayerAction();
        if (audit$isLoggedClick(playerEntity, packet)) {
            ContainerSessions.clickReturn(
                playerEntity,
                new ContainerSessions.Click(packet.func_149544_d(), packet.func_149543_e(), packet.func_149542_h()));
        }
    }

    @Unique
    private static boolean audit$isLoggedClick(EntityPlayerMP player, C0EPacketClickWindow packet) { // noqa: NAR130
        return player.theItemInWorldManager.isCreative() && player.openContainer != player.inventoryContainer
            && player.openContainer.windowId == packet.func_149548_c();
    }
}
