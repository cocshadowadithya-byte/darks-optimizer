package com.example.shieldbreaker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.ActionResult;

/**
 * Left click on a shielding player:
 *   1. switch to an axe from the hotbar
 *   2. the click's hit lands with the axe (breaks the shield)
 *   3. immediately after, switch to the mace and stay on it
 * The mace is never used for that click, and there is no switch back to the old slot.
 */
public class ShieldBreakerClient implements ClientModInitializer {
	private static final int NONE = -1;
	private static int pendingMaceSlot = NONE;

	@Override
	public void onInitializeClient() {
		// Fires on the client at the start of PlayerInteractionManager.attackEntity,
		// i.e. BEFORE the held slot is synced to the server and the attack packet is sent.
		// So changing the slot here makes the server see: select axe -> attack.
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (!world.isClient()) return ActionResult.PASS;
			if (!(entity instanceof PlayerEntity target)) return ActionResult.PASS;
			if (!target.isBlocking()) return ActionResult.PASS;

			PlayerInventory inv = player.getInventory();
			int axeSlot = NONE;
			int maceSlot = NONE;
			for (int i = 0; i < 9; i++) {
				ItemStack stack = inv.getStack(i);
				if (axeSlot == NONE && stack.isIn(ItemTags.AXES)) axeSlot = i;
				if (maceSlot == NONE && stack.isOf(Items.MACE)) maceSlot = i;
			}
			if (axeSlot == NONE || maceSlot == NONE) return ActionResult.PASS;

			inv.setSelectedSlot(axeSlot);   // 1. auto switch to axe (the hit goes out with it)
			pendingMaceSlot = maceSlot;     // 3. switch to mace right after the hit
			return ActionResult.PASS;       // let the normal attack continue
		});

		// End of the same tick: the axe hit has already been sent, so go to the mace and stay.
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (pendingMaceSlot == NONE) return;
			if (client.player != null) {
				client.player.getInventory().setSelectedSlot(pendingMaceSlot);
			}
			pendingMaceSlot = NONE;
		});
	}
}
