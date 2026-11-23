package dev.ankicraft.network;

import dev.ankicraft.AnkiCraftMod;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

public final class AnkiCraftNetwork {
    private AnkiCraftNetwork() {}

    public record ServerHelloPayload(int protocolVersion) implements CustomPacketPayload {
        public static final Type<ServerHelloPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(AnkiCraftMod.MOD_ID, "server_hello"));

        public static final StreamCodec<RegistryFriendlyByteBuf, ServerHelloPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, ServerHelloPayload::protocolVersion,
                        ServerHelloPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record QuizResultPayload(int ease, int streak, boolean rewardsEnabled, boolean penaltiesEnabled) implements CustomPacketPayload {
        public static final Type<QuizResultPayload> TYPE =
                new Type<>(Identifier.fromNamespaceAndPath(AnkiCraftMod.MOD_ID, "quiz_result"));

        public static final StreamCodec<RegistryFriendlyByteBuf, QuizResultPayload> CODEC =
                StreamCodec.composite(
                        ByteBufCodecs.VAR_INT, QuizResultPayload::ease,
                        ByteBufCodecs.VAR_INT, QuizResultPayload::streak,
                        ByteBufCodecs.BOOL, QuizResultPayload::rewardsEnabled,
                        ByteBufCodecs.BOOL, QuizResultPayload::penaltiesEnabled,
                        QuizResultPayload::new
                );

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public static void registerPayloads() {
        PayloadTypeRegistry.clientboundPlay().register(ServerHelloPayload.TYPE, ServerHelloPayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(QuizResultPayload.TYPE, QuizResultPayload.CODEC);
    }
}
