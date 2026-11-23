package dev.ankicraft.client;

import com.mojang.blaze3d.platform.InputConstants;
import dev.ankicraft.AnkiCraftMod;
import dev.ankicraft.client.ui.AnkiHudOverlay;
import dev.ankicraft.client.ui.ConfigScreen;
import dev.ankicraft.client.ui.DeckSelectScreen;
import dev.ankicraft.network.AnkiCraftNetwork.ServerHelloPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.BedBlock;

public class AnkiCraftClient implements ClientModInitializer {
    private static KeyMapping quizKey;
    private static KeyMapping deckKey;
    private static KeyMapping configKey;

    @Override
    public void onInitializeClient() {
        AnkiCraftMod.LOGGER.info("AnkiCraft client initializing...");

        AnkiManager manager = AnkiManager.getInstance();
        TriggerManager triggers = TriggerManager.getInstance();

        KeyMapping.Category category = KeyMapping.Category.MISC;
        quizKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.ankicraft.quiz",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_K,
                category
        ));
        deckKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.ankicraft.decks",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_J,
                category
        ));
        configKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.ankicraft.config",
                InputConstants.Type.KEYBOARD,
                InputConstants.KEY_O,
                category
        ));

        // HUD Overlay
        HudElementRegistry.attachElementAfter(
                VanillaHudElements.CHAT,
                Identifier.fromNamespaceAndPath(AnkiCraftMod.MOD_ID, "hud"),
                AnkiHudOverlay::render
        );

        // Server detection (works seamlessly on Vanilla servers where ServerHello is never sent)
        ClientPlayNetworking.registerGlobalReceiver(ServerHelloPayload.TYPE, (payload, context) ->
                context.client().execute(() -> {
                    manager.setServerSupportsAnkiCraft(true);
                    AnkiCraftMod.LOGGER.info("Server supports AnkiCraft (protocol v{})", payload.protocolVersion());
                })
        );

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            manager.setServerSupportsAnkiCraft(false);
            triggers.resetTimers();
            manager.syncNow();
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) ->
                manager.setServerSupportsAnkiCraft(false)
        );

        // Client Tick & Keybinds
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (quizKey.consumeClick()) {
                if (mc.gui.screen() == null) {
                    triggers.openManualQuiz(mc);
                }
            }
            while (deckKey.consumeClick()) {
                mc.gui.setScreen(new DeckSelectScreen(mc.gui.screen()));
            }
            while (configKey.consumeClick()) {
                mc.gui.setScreen(new ConfigScreen(mc.gui.screen()));
            }
            triggers.onClientTick(mc);
        });

        // Bed / Sleep trigger (works on Vanilla servers too)
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (world.isClientSide() && world.getBlockState(hitResult.getBlockPos()).getBlock() instanceof BedBlock) {
                if (triggers.onBedInteract(Minecraft.getInstance())) {
                    return InteractionResult.SUCCESS;
                }
            }
            return InteractionResult.PASS;
        });

        // Block break trigger (singleplayer / local server)
        PlayerBlockBreakEvents.AFTER.register((world, player, pos, state, blockEntity) -> {
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null && player.getUUID().equals(mc.player.getUUID())) {
                mc.execute(() -> triggers.onBlockBroken(mc));
            }
        });
    }
}
