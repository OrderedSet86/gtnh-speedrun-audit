package com.gtnhspeedrun.audit.mixins.late.extrautils;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.gtnhspeedrun.audit.trackers.AuditSinks;

/**
 * Items a player drops into an Extra Utilities trash can by hand. The can's GUI slot (SlotTrash) is the only way
 * a player reaches it — click-placing and shift-clicking both end in putStack — while pipes, hoppers and
 * conveyors insert through the tile's IInventory and never touch a Slot, so machine-line trash is not logged.
 * putStack hands the stack to the tile with instantAdd set, which voids it on the spot (a node-upgrade filter in
 * slot 0 is the one item the can keeps).
 *
 * <p>
 * Extra Utilities 1.2.12 is on no maven, so the target is @Pseudo with its SRG method name; the jar is the same
 * in pack 2.8.4 and 2.9.x.
 */
@Pseudo
@Mixin(targets = "com.rwtema.extrautils.gui.SlotTrash", remap = false)
public class SlotTrashMixin {

    @Inject(method = "func_75215_d(Lnet/minecraft/item/ItemStack;)V", at = @At("HEAD"), require = 1)
    private void audit$put(ItemStack stack, CallbackInfo ci) {
        if (stack == null) {
            return;
        }
        final Slot self = (Slot) (Object) this;
        EntityPlayerMP player = null;
        final MinecraftServer server = MinecraftServer.getServer();
        if (server != null) {
            for (Object o : server.getConfigurationManager().playerEntityList) {
                final EntityPlayerMP p = (EntityPlayerMP) o;
                if (p.openContainer != null && p.openContainer.inventorySlots.contains(self)) {
                    player = p;
                    break;
                }
            }
        }
        final TileEntity can = self.inventory instanceof TileEntity te ? te : null;
        if (can != null && can.getWorldObj() != null && can.getWorldObj().isRemote) {
            return;
        }
        AuditSinks.trashInsert(player, can, stack);
    }
}
