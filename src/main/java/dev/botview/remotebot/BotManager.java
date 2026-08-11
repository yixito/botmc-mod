package dev.botview.remotebot;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.Connection;
import net.minecraft.network.DisconnectionDetails;
import net.minecraft.network.PacketListener;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Owns the single fake-player bot: lifecycle, server-thread action queue,
 * and snapshot broadcasting. All world mutation happens on the server thread.
 */
public class BotManager {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final SecureRandom RNG = new SecureRandom();
    private static final int SNAPSHOT_RADIUS = 14;   // blocks in each x/z direction
    private static final int SNAPSHOT_HEIGHT = 16;   // layers of the snapshot column

    public ServerPlayer bot;
    public UUID owner;
    public ServerLevel level;
    public BotBrain brain;
    public BotListener listener;   // drives the bot's server-side physics

    private final List<Runnable> actionQueue = new ArrayList<>();
    private FakeConnection fakeConn;
    private int stateTimer, blockTimer;

    // ---- lifecycle (server thread) ----

    public void spawn(ServerPlayer requester) {
        if (bot != null) {
            requester.sendSystemMessage(Component.literal("A bot is already active. Use /remotebot stop first."));
            return;
        }
        spawnCore(requester.serverLevel(), requester.getX(), requester.getY(), requester.getZ(),
                requester.getUUID(), requester.getGameProfile().getName(),
                Component.literal("Bot spawned. Control it: " + RemoteBotMod.botUrl()));
    }

    /** Spawn from the console (no player source): owner is a pseudo-UUID. */
    public void spawnAt(ServerLevel lvl, double x, double y, double z, String ownerName) {
        if (bot != null) return;
        spawnCore(lvl, x, y, z, UUID.nameUUIDFromBytes(ownerName.getBytes()), ownerName, null);
    }

    private void spawnCore(ServerLevel lvl, double x, double y, double z, UUID ownerId, String ownerName, Component chatMsg) {
        MinecraftServer server = lvl.getServer();
        String name = "Bot" + (100 + RNG.nextInt(900));
        GameProfile profile = new GameProfile(UUID.randomUUID(), name);
        FakePlayer fake = new FakePlayer(server, lvl, profile);
        fake.moveTo(x, y, z, 0, 0);
        fake.yHeadRot = fake.yBodyRot = fake.getYRot();

        CommonListenerCookie cookie = CommonListenerCookie.createInitial(profile, false);
        fakeConn = new FakeConnection();
        server.getPlayerList().placeNewPlayer(fakeConn, fake, cookie);
        // placeNewPlayer installs its own vanilla listener; swap in ours afterwards
        BotListener listener = new BotListener(server, fakeConn, fake, cookie);
        fake.connection = listener;
        this.listener = listener;

        this.bot = fake;
        this.owner = ownerId;
        this.level = lvl;
        this.brain = new BotBrain();
        LOGGER.info("Bot '{}' spawned at {} (owner {})", name, fake.blockPosition(), ownerName);
        if (chatMsg != null) {
            ServerPlayer target = server.getPlayerList().getPlayer(ownerId);
            if (target != null) target.sendSystemMessage(chatMsg);
            else LOGGER.info("Control URL: {}", RemoteBotMod.botUrl());
        }
    }

    public void remove(String reason) {
        if (bot == null) return;
        ServerPlayer b = bot;
        bot = null;
        brain = null;
        listener = null;
        try {
            if (fakeConn != null) fakeConn.kill();
            MinecraftServer s = b.getServer();
            if (s != null) s.getPlayerList().remove(b);
        } catch (Exception e) {
            LOGGER.warn("Bot removal issue: {}", e.toString());
        }
        if (RemoteBotMod.web != null) {
            RemoteBotMod.web.broadcast("{\"type\":\"error\",\"message\":\"bot removed (" + reason + ")\"}");
        }
        LOGGER.info("Bot removed ({})", reason);
    }

    public String statusText() {
        if (bot == null) return "No bot active. URL: " + RemoteBotMod.botUrl();
        return "Bot '" + bot.getGameProfile().getName() + "' at " + bot.blockPosition()
                + ", mode=" + brain.mode + ", hp=" + bot.getHealth()
                + " | URL: " + RemoteBotMod.botUrl();
    }

    // ---- server tick ----

    public void tick(MinecraftServer server) {
        drainActions();
        if (bot == null) return;
        if (!bot.isAlive()) {
            remove("died");
            return;
        }
        brain.tick(this);
        ((FakePlayer) bot).setInput(brain.leftImpulse, brain.forwardImpulse, brain.jumping);
        // vanilla ticks player connections via ServerConnectionListener; our fake
        // connection isn't registered there, so drive the physics manually
        if (listener != null) listener.tick();
        if (brain.pathDirty) {
            brain.pathDirty = false;
            RemoteBotMod.web.broadcast(pathJson());
        }
        if (RemoteBotMod.web.hasClients()) {
            if (--stateTimer <= 0) { stateTimer = 4; RemoteBotMod.web.broadcast(stateJson()); }
            if (--blockTimer <= 0) { blockTimer = 20; RemoteBotMod.web.broadcast(blocksJson()); }
        }
    }

    /** Run a lambda on the server thread. Safe to call from any thread (e.g. Netty). */
    public void queueAction(Runnable r) {
        synchronized (actionQueue) {
            actionQueue.add(r);
        }
    }

    private void drainActions() {
        List<Runnable> q;
        synchronized (actionQueue) {
            if (actionQueue.isEmpty()) return;
            q = new ArrayList<>(actionQueue);
            actionQueue.clear();
        }
        q.forEach(r -> {
            try {
                r.run();
            } catch (Exception e) {
                LOGGER.error("Action failed: {}", e.toString());
            }
        });
    }

    // ---- remote control actions (server thread) ----

    public void setRemoteKeys(boolean f, boolean b, boolean l, boolean r, boolean j) {
        if (brain == null) return;
        brain.remoteF = f; brain.remoteB = b; brain.remoteL = l; brain.remoteR = r; brain.remoteJ = j;
    }

    public void setLook(double yaw, double pitch) {
        if (bot == null) return;
        bot.setYRot((float) yaw);
        bot.setXRot((float) Math.max(-90, Math.min(90, pitch)));
        bot.yHeadRot = bot.yBodyRot = bot.getYRot();
    }

    public void goTo(int x, int y, int z) {
        if (brain != null) brain.goTo(new BlockPos(x, y, z));
    }

    public void setMode(String mode) {
        if (brain == null) return;
        if ("FOLLOW".equalsIgnoreCase(mode)) brain.setMode(BotBrain.Mode.FOLLOW);
        else if ("IDLE".equalsIgnoreCase(mode)) brain.setMode(BotBrain.Mode.IDLE);
    }

    public void attack() {
        ServerPlayer b = bot;
        if (b == null) return;
        LivingEntity target = null;
        double best = Double.MAX_VALUE;
        Vec3 look = b.getLookAngle();
        for (LivingEntity e : b.level().getEntitiesOfClass(LivingEntity.class,
                b.getBoundingBox().inflate(4.0), e -> e != b)) {
            // prefer whatever is in front of the bot
            Vec3 to = e.position().subtract(b.position());
            double dot = to.normalize().dot(look);
            if (dot < 0.5) continue;
            double d = e.distanceToSqr(b);
            if (d < best) { best = d; target = e; }
        }
        if (target != null) {
            b.attack(target);
            b.swing(InteractionHand.MAIN_HAND);
        }
    }

    public void stopBot() {
        if (brain != null) brain.stopped = true;
    }

    public void resume() {
        if (brain != null) brain.stopped = false;
    }

    // ---- snapshots (server thread) ----

    private String pathJson() {
        StringBuilder sb = new StringBuilder("{\"type\":\"path\",\"points\":[");
        boolean first = true;
        for (BlockPos p : brain.path) {
            if (!first) sb.append(',');
            first = false;
            sb.append('[').append(p.getX()).append(',').append(p.getY()).append(',').append(p.getZ()).append(']');
        }
        return sb.append("]}").toString();
    }

    private String stateJson() {
        StringBuilder sb = new StringBuilder("{\"type\":\"state\",\"hasBot\":");
        if (bot == null) return sb.append("false}").toString();
        return sb.append("true,\"name\":\"").append(bot.getGameProfile().getName())
                .append("\",\"pos\":[").append(trim(bot.getX())).append(',').append(trim(bot.getY())).append(',').append(trim(bot.getZ())).append(']')
                .append(",\"yaw\":").append(trim(bot.getYRot()))
                .append(",\"pitch\":").append(trim(bot.getXRot()))
                .append(",\"hp\":").append(trim(bot.getHealth()))
                .append(",\"mode\":\"").append(brain.mode)
                .append("\",\"stopped\":").append(brain.stopped)
                .append('}').toString();
    }

    private String blocksJson() {
        BlockPos b = bot.blockPosition();
        int x0 = b.getX() - SNAPSHOT_RADIUS, y0 = b.getY() - 6, z0 = b.getZ() - SNAPSHOT_RADIUS;
        int w = SNAPSHOT_RADIUS * 2 + 1, h = SNAPSHOT_HEIGHT, d = SNAPSHOT_RADIUS * 2 + 1;
        byte[] grid = new byte[w * h * d];
        List<String> palette = new ArrayList<>();
        palette.add("air");
        Map<String, Integer> idx = new HashMap<>();
        for (int dy = 0; dy < h; dy++) {
            for (int dz = 0; dz < d; dz++) {
                for (int dx = 0; dx < w; dx++) {
                    BlockState s = level.getBlockState(new BlockPos(x0 + dx, y0 + dy, z0 + dz));
                    if (s.isAir()) continue;
                    String name = BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath();
                    int i = idx.computeIfAbsent(name, k -> { palette.add(k); return palette.size() - 1; });
                    grid[(dy * d + dz) * w + dx] = (byte) i;
                }
            }
        }
        StringBuilder sb = new StringBuilder("{\"type\":\"blocks\",\"x0\":").append(x0)
                .append(",\"y0\":").append(y0).append(",\"z0\":").append(z0)
                .append(",\"w\":").append(w).append(",\"h\":").append(h).append(",\"d\":").append(d)
                .append(",\"palette\":[");
        for (int i = 0; i < palette.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(palette.get(i)).append('"');
        }
        sb.append("],\"data\":\"").append(Base64.getEncoder().encodeToString(grid)).append('"');
        // nearby entities so the view is not empty
        sb.append(",\"entities\":[");
        boolean first = true;
        for (ServerPlayer p : level.getEntitiesOfClass(ServerPlayer.class, bot.getBoundingBox().inflate(24.0))) {
            if (p == bot) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"n\":\"").append(p.getGameProfile().getName())
                    .append("\",\"p\":[").append(trim(p.getX())).append(',').append(trim(p.getY())).append(',').append(trim(p.getZ())).append("]}");
        }
        return sb.append("]}").toString();
    }

    private static String trim(double v) {
        long r = Math.round(v * 100);
        return Long.toString(r / 100) + (r % 100 == 0 ? "" : "." + Math.abs(r % 100));
    }

    // ---- fake player ----

    public static class FakePlayer extends ServerPlayer {
        public FakePlayer(MinecraftServer server, ServerLevel level, GameProfile profile) {
            super(server, level, profile, ClientInformation.createDefault());
        }

        public void setInput(float leftImpulse, float forwardImpulse, boolean jumping) {
            // setPlayerInput only applies xxa/zza while riding; set the fields directly
            this.xxa = leftImpulse;
            this.zza = forwardImpulse;
            this.jumping = jumping;
        }

        @Override
        public void doTick() {
            super.doTick(); // runs physics (aiStep -> travel) using xxa/zza
            // vanilla's listener snaps the player back to the last client-acknowledged
            // position after doTick; a fake player has no client, so re-mark the
            // position after physics to make that snap-back a no-op
            if (this.connection instanceof BotListener bl) bl.markGoodPosition();
        }
    }

    /**
     * Listener for the fake bot. Vanilla's tick() reverts the player to the
     * position the client last confirmed; fake players have no client, so
     * {@link #markGoodPosition()} re-confirms the post-physics position.
     */
    public static class BotListener extends ServerGamePacketListenerImpl {
        public BotListener(MinecraftServer server, Connection conn, ServerPlayer player, CommonListenerCookie cookie) {
            super(server, conn, player, cookie);
        }

        public void markGoodPosition() {
            resetPosition();
        }
    }

    /** A connection that swallows every packet, so the bot is a real player without a socket. */
    private static class FakeConnection extends Connection {
        private volatile boolean dead;
        private final SocketAddress addr = new InetSocketAddress("127.0.0.1", 0);

        FakeConnection() {
            super(PacketFlow.SERVERBOUND);
        }

        @Override public void send(Packet<?> packet) {}
        @Override public void send(Packet<?> packet, PacketSendListener listener) {}
        @Override public void send(Packet<?> packet, PacketSendListener listener, boolean flush) {}
        @Override public void flushChannel() {}
        @Override public void disconnect(Component reason) {}
        @Override public void disconnect(DisconnectionDetails details) {}
        @Override public boolean isConnected() { return !dead; }
        @Override public boolean isMemoryConnection() { return true; }
        @Override public SocketAddress getRemoteAddress() { return addr; }
        @Override public void tick() {}
        @Override public void setReadOnly() {}
        @Override public <T extends PacketListener> void setupInboundProtocol(ProtocolInfo<T> info, T listener) {}
        @Override public void setupOutboundProtocol(ProtocolInfo<?> info) {}

        void kill() { dead = true; }
    }
}
