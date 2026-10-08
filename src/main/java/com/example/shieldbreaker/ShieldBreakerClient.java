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

import java.util.UUID;

/**
 * Dark's Optimizer – hotbar helpers for left clicks on players.
 *
 * 1) Stun slam, two clicks while holding an AXE (needs a mace in the hotbar):
 *      click 1: normal axe hit (breaks the shield if they are blocking)
 *      click 2: switches to the mace and hits with it, whether they are shielding or not,
 *               and you stay on the mace.
 *    Does nothing if you hold anything other than an axe.
 * 2) Holding any sword and you have a mace with Breach in the hotbar:
 *    the hit is sent with the Breach mace (attribute swap), then the sword is
 *    selected again straight away, all inside the same click.
 */
public class ShieldBreakerClient implements ClientModInitializer {
	private static final int NONE = -1;
	private static boolean busy = false;

	// The second click has to come within this many ticks of the first axe hit (20 ticks = 1 s).
	private static final long SECOND_CLICK_WINDOW_TICKS = 40;
	private static UUID firstClickTarget = null;
	private static long firstClickTick = -1000;

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
			int maceSlot = NONE;
			int breachMaceSlot = NONE;
			for (int i = 0; i < 9; i++) {
				ItemStack stack = inv.getStack(i);
				if (stack.isOf(Items.MACE)) {
					if (maceSlot == NONE) maceSlot = i;
					if (breachMaceSlot == NONE && hasBreach(stack)) breachMaceSlot = i;
				}
			}

			long now = world.getTime();

			// 1) Stun slam: only while HOLDING an axe
			if (player.getMainHandStack().isIn(ItemTags.AXES) && maceSlot != NONE) {
				boolean secondClick = target.getUuid().equals(firstClickTarget)
						&& now - firstClickTick >= 0 && now - firstClickTick <= SECOND_CLICK_WINDOW_TICKS;

				if (secondClick) {
					// click 2: switch to the mace and hit with it, shield or not, then stay on it
					firstClickTarget = null;
					busy = true;
					try {
						inv.setSelectedSlot(maceSlot);
						client.interactionManager.attackEntity(player, entity);
					} finally {
						busy = false;
					}
					return ActionResult.FAIL; // click already handled
				}

				// click 1: normal axe hit, remember who we hit
				firstClickTarget = target.getUuid();
				firstClickTick = now;
				return ActionResult.PASS;
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
