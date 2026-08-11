package dev.botview.remotebot;

import com.google.gson.Gson;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.security.SecureRandom;
import java.util.HexFormat;

public class RemoteBotMod implements ModInitializer {
    public static final Gson GSON = new Gson();
    public static final int PORT = Integer.getInteger("remotebot.port", 8080);

    public static MinecraftServer server;
    public static BotManager bots;
    public static RemoteWebServer web;

    private static final SecureRandom RNG = new SecureRandom();
    public static volatile String token = newToken();

    static String newToken() {
        byte[] b = new byte[16];
        RNG.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    public static String botUrl() {
        return "http://localhost:" + PORT + "/?t=" + token;
    }

    @Override
    public void onInitialize() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(Commands.literal("remotebot")
                    .requires(src -> src.hasPermission(2))
                    .then(Commands.literal("spawn").executes(ctx -> spawn(ctx.getSource())))
                    .then(Commands.literal("stop").executes(ctx -> stop(ctx.getSource())))
                    .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                    .then(Commands.literal("token").executes(ctx -> newTokenCmd(ctx.getSource()))));
        });

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerTickEvents.END_SERVER_TICK.register(s -> {
            if (bots != null) bots.tick(s);
        });
    }

    private void onServerStarted(MinecraftServer s) {
        server = s;
        bots = new BotManager();
        web = new RemoteWebServer();
        web.start();
        s.getPlayerList().broadcastSystemMessage(
                Component.literal("RemoteBot ready: " + botUrl()
                        + "   (remote access: cloudflared tunnel --url http://localhost:" + PORT + ")"),
                false);
    }

    private static int spawn(CommandSourceStack src) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        if (bots == null) {
            src.sendFailure(Component.literal("Server not ready yet."));
            return 0;
        }
        ServerPlayer p;
        try {
            p = src.getPlayerOrException();
        } catch (Exception e) {
            // console: spawn where the first online player is, else world spawn
            MinecraftServer s = src.getServer();
            p = s.getPlayerList().getPlayers().stream().findFirst().orElse(null);
            if (p == null) {
                net.minecraft.server.level.ServerLevel overworld = s.getLevel(net.minecraft.world.level.Level.OVERWORLD);
                if (overworld == null) {
                    src.sendFailure(Component.literal("No overworld available — cannot spawn."));
                    return 0;
                }
                net.minecraft.core.BlockPos sp = overworld.getSharedSpawnPos();
                int top = 320;
                net.minecraft.world.level.block.state.BlockState ground;
                do {
                    ground = overworld.getBlockState(new net.minecraft.core.BlockPos(sp.getX(), top, sp.getZ()));
                    top--;
                } while (!ground.isSolid() && top > -64);
                bots.spawnAt(overworld, sp.getX() + 0.5, top + 2, sp.getZ() + 0.5, "console");
                src.sendSuccess(() -> Component.literal("Bot spawned at world spawn (no players online)."), false);
                return 1;
            }
        }
        bots.spawn(p);
        return 1;
    }

    private static int stop(CommandSourceStack src) {
        if (bots == null) return 0;
        bots.remove("stopped by command");
        src.sendSuccess(() -> Component.literal("Bot removed."), false);
        return 1;
    }

    private static int status(CommandSourceStack src) {
        src.sendSuccess(() -> Component.literal(bots == null ? "not ready" : bots.statusText()), false);
        return 1;
    }

    private static int newTokenCmd(CommandSourceStack src) {
        token = newToken();
        src.sendSuccess(() -> Component.literal("New control URL: " + botUrl()), false);
        return 1;
    }
}
