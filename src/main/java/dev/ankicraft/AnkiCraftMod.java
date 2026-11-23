package dev.ankicraft;

import dev.ankicraft.network.AnkiCraftNetwork;
import dev.ankicraft.network.AnkiCraftNetwork.QuizResultPayload;
import dev.ankicraft.network.AnkiCraftNetwork.ServerHelloPayload;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class AnkiCraftMod implements ModInitializer {
    public static final String MOD_ID = "ankicraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);
    public static final int PROTOCOL_VERSION = 1;

    @Override
    public void onInitialize() {
        LOGGER.info("AnkiCraft initializing...");
        AnkiCraftNetwork.registerPayloads();

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                sender.sendPacket(new ServerHelloPayload(PROTOCOL_VERSION))
        );

        ServerPlayNetworking.registerGlobalReceiver(QuizResultPayload.TYPE, (payload, context) -> {
            ServerPlayer player = context.player();
            context.server().execute(() -> handleQuizResult(player, payload));
        });
    }

    private static void handleQuizResult(ServerPlayer player, QuizResultPayload payload) {
        int ease = payload.ease();
        int streak = Math.max(0, payload.streak());

        if (ease >= 3 && payload.rewardsEnabled()) {
            int baseXp = (ease == 4) ? 12 : 8;
            int bonusXp = Math.min(15, streak * 2);
            player.giveExperiencePoints(baseXp + bonusXp);

            if (streak >= 3) {
                player.addEffect(new MobEffectInstance(MobEffects.HASTE, 20 * 30, 0, true, false));
            }
            if (streak >= 5) {
                player.addEffect(new MobEffectInstance(MobEffects.SPEED, 20 * 25, 0, true, false));
            }
            if (ease == 4 && streak >= 2) {
                player.addEffect(new MobEffectInstance(MobEffects.REGENERATION, 20 * 6, 0, true, false));
            }

            player.level().playSound(
                    null,
                    player.blockPosition(),
                    SoundEvents.EXPERIENCE_ORB_PICKUP,
                    SoundSource.PLAYERS,
                    0.5f,
                    1.15f
            );
        } else if (ease == 1 && payload.penaltiesEnabled()) {
            player.addEffect(new MobEffectInstance(MobEffects.HUNGER, 20 * 8, 0, true, false));
            player.addEffect(new MobEffectInstance(MobEffects.SLOWNESS, 20 * 4, 0, true, false));
        }
    }
}
