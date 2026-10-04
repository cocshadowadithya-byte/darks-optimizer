package com.example.shieldbreaker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.registry.tag.ItemTags;
import net.minecraft.util.ActionResult;

/**
 * Dark's Optimizer – hotbar helpers for left clicks on players.
 *
 * 1) Target is shielding: axe selected -> axe hit -> mace selected, stay on mace.
 * 2) Holding any sword and you have a mace with Breach in the hotbar:
 *    the hit is sent with the Breach mace (attribute swap), then the sword is
 *    selected again straight away, all inside the same click.
 */
public class ShieldBreakerClient implements ClientModInitializer {
	private static final int NONE = -1;
	private static boolean busy = false;

	@Override
	public void onInitializeClient() {
		AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
			if (busy) return ActionResult.PASS;
			if (!world.isClient()) return ActionResult.PASS;
			if (!(entity instanceof PlayerEntity target)) return ActionResult.PASS;

			MinecraftClient client = MinecraftClient.getInstance();
			if (client.interactionManager == null || client.getNetworkHandler() == null) {
				return ActionResult.PASS;
			}

			PlayerInventory inv = player.getInventory();
			int axeSlot = NONE;
			int maceSlot = NONE;
			int breachMaceSlot = NONE;
			for (int i = 0; i < 9; i++) {
				ItemStack stack = inv.getStack(i);
				if (axeSlot == NONE && stack.isIn(ItemTags.AXES)) axeSlot = i;
				if (stack.isOf(Items.MACE)) {
					if (maceSlot == NONE) maceSlot = i;
					if (breachMaceSlot == NONE && hasBreach(stack)) breachMaceSlot = i;
				}
			}

			// 1) shield break combo: axe hit, then stay on the mace
			if (target.isBlocking() && axeSlot != NONE && maceSlot != NONE) {
				busy = true;
				try {
					inv.setSelectedSlot(axeSlot);
					client.interactionManager.attackEntity(player, entity);
				} finally {
					busy = false;
				}
				inv.setSelectedSlot(maceSlot);
				// tell the server right away, in the same tick as the axe hit
				client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(maceSlot));
				return ActionResult.FAIL; // click already handled
			}

			// 2) sword -> Breach mace attribute swap, then back to the sword
			if (player.getMainHandStack().isIn(ItemTags.SWORDS) && breachMaceSlot != NONE) {
				int swordSlot = inv.getSelectedSlot();
				busy = true;
				try {
					inv.setSelectedSlot(breachMaceSlot);
					client.interactionManager.attackEntity(player, entity); // slot packet + attack packet
				} finally {
					busy = false;
				}
				inv.setSelectedSlot(swordSlot);
				// tell the server right away, in the same tick as the attack
				client.getNetworkHandler().sendPacket(new UpdateSelectedSlotC2SPacket(swordSlot));
				return ActionResult.FAIL; // click already handled
			}

			return ActionResult.PASS;
		});
	}

	private static boolean hasBreach(ItemStack stack) {
		return stack.getEnchantments().getEnchantments().stream()
				.anyMatch(entry -> entry.matchesKey(Enchantments.BREACH));
	}
}
