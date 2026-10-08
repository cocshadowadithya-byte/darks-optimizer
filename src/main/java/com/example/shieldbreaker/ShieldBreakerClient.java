package com.example.shieldbreaker;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
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
 * 1) You hold an AXE and click a shielding player: axe hit -> mace selected, stay on mace.
 *    (Does nothing if you hold anything other than an axe.)
 * 2) Holding any sword and you have a mace with Breach in the hotbar:
 *    the hit is sent with the Breach mace (attribute swap), then the sword is
 *    selected again straight away, all inside the same click.
 */
public class ShieldBreakerClient implements ClientModInitializer {
	private static final int NONE = -1;
	private static boolean busy = false;

	// 10 ticks = 0.5 s
	private static final long COMBO_LOCKOUT_TICKS = 10;
	private static java.util.UUID lastComboTarget = null;
	private static long lastComboTick = -1000;

	// Ticks to wait after the axe hit before switching to the mace (20 ticks = 1 s).
	// 1 = next tick, 2 = about 0.1 s, 3 = about 0.15 s ... change this number to tune it.
	private static final int MACE_SWITCH_DELAY_TICKS = 3;
	private static int pendingMaceSlot = NONE;
	private static int pendingTicks = 0;

	@Override
	public void onInitializeClient() {
		// Delayed switch to the mace after a shield break
		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			if (pendingMaceSlot == NONE) return;
			if (client.player == null) {
				pendingMaceSlot = NONE;
				return;
			}
			if (--pendingTicks <= 0) {
				client.player.getInventory().setSelectedSlot(pendingMaceSlot);
				pendingMaceSlot = NONE;
			}
		});

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

			// Right after a shield break, the target's shield state is still "blocking" on our
			// screen for a few ticks. Don't run the combo again on the same player in that
			// window, so the next click is a normal hit with the mace we're now holding.
			long now = world.getTime();
			boolean justBroke = target.getUuid().equals(lastComboTarget)
					&& now - lastComboTick >= 0 && now - lastComboTick < COMBO_LOCKOUT_TICKS;

			// 1) shield break combo: axe hit, then stay on the mace
			// Only when you are already HOLDING an axe; nothing else triggers it.
			boolean holdingAxe = player.getMainHandStack().isIn(ItemTags.AXES);
			if (holdingAxe) axeSlot = inv.getSelectedSlot(); // use the axe in your hand
			if (holdingAxe && !justBroke && target.isBlocking() && maceSlot != NONE) {
				lastComboTarget = target.getUuid();
				lastComboTick = now;
				busy = true;
				try {
					inv.setSelectedSlot(axeSlot);
					client.interactionManager.attackEntity(player, entity);
				} finally {
					busy = false;
				}
				// switch to the mace a few ticks AFTER the axe hit (see tick handler below)
				pendingMaceSlot = maceSlot;
				pendingTicks = MACE_SWITCH_DELAY_TICKS;
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
