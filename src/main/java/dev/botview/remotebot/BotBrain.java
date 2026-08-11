package dev.botview.remotebot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * Small state machine: IDLE / FOLLOW / GOTO, with manual-keys override.
 * Steering runs every tick on the server thread; pathfinding is A* in {@link AStar}.
 */
public class BotBrain {
    public enum Mode { IDLE, FOLLOW, GOTO }

    public Mode mode = Mode.IDLE;
    public boolean stopped;          // emergency stop from the web UI
    public BlockPos goal;
    public List<BlockPos> path = List.of();
    public boolean pathDirty;

    // remote keyboard override (true = user is holding the key)
    public boolean remoteF, remoteB, remoteL, remoteR, remoteJ;

    // resulting movement this tick
    public float leftImpulse, forwardImpulse;
    public boolean jumping;

    private int pathIndex, replanTimer;

    public void setMode(Mode m) {
        mode = m;
        goal = null;
        path = List.of();
    }

    public void goTo(BlockPos p) {
        mode = Mode.GOTO;
        goal = p;
        path = List.of();
        pathDirty = true;
    }

    public void tick(BotManager mgr) {
        leftImpulse = forwardImpulse = 0;
        jumping = false;
        ServerPlayer bot = mgr.bot;
        if (bot == null || stopped) return;
        ServerLevel level = mgr.level;

        // manual keys always beat autopilot
        if (remoteF || remoteB || remoteL || remoteR || remoteJ) {
            forwardImpulse = (remoteF ? 1 : 0) - (remoteB ? 1 : 0);
            leftImpulse = (remoteL ? 1 : 0) - (remoteR ? 1 : 0);
            jumping = remoteJ;
            return;
        }

        switch (mode) {
            case FOLLOW -> {
                ServerPlayer owner = level.getServer().getPlayerList().getPlayer(mgr.owner);
                if (owner == null) return;
                if (bot.distanceToSqr(owner) > 9) {
                    if (replanTimer-- <= 0 || path.isEmpty()) {
                        path = AStar.find(level, bot.blockPosition(), owner.blockPosition());
                        pathDirty = true;
                        replanTimer = 40;
                        pathIndex = 0;
                    }
                } else {
                    path = List.of();
                }
            }
            case GOTO -> {
                if (goal == null) {
                    mode = Mode.IDLE;
                    return;
                }
                double dx = goal.getX() + 0.5 - bot.getX();
                double dz = goal.getZ() + 0.5 - bot.getZ();
                double dy = goal.getY() - bot.getY();
                if (dx * dx + dz * dz < 2.25 && Math.abs(dy) < 3) {   // arrived
                    path = List.of();
                    mode = Mode.IDLE;
                    return;
                }
                if (replanTimer-- <= 0 || path.isEmpty()) {
                    path = AStar.find(level, bot.blockPosition(), goal);
                    pathDirty = true;
                    replanTimer = 40;
                    pathIndex = 0;
                }
            }
            case IDLE -> {}
        }
        steer(bot, level);
    }

    private void steer(ServerPlayer bot, ServerLevel level) {
        while (!path.isEmpty() && pathIndex < path.size()) {
            BlockPos w = path.get(pathIndex);
            double dx = w.getX() + 0.5 - bot.getX();
            double dz = w.getZ() + 0.5 - bot.getZ();
            double dy = w.getY() - bot.getY();
            if (dx * dx + dz * dz < 1.0 && Math.abs(dy) < 2.5) pathIndex++;
            else break;
        }
        if (pathIndex >= path.size()) {
            path = List.of();
            return;
        }
        BlockPos target = path.get(pathIndex);
        double dx = target.getX() + 0.5 - bot.getX();
        double dz = target.getZ() + 0.5 - bot.getZ();
        float desired = (float) Math.toDegrees(Math.atan2(-dx, dz)); // MC yaw convention
        float cur = bot.getYRot();
        float diff = (desired - cur + 540) % 360 - 180;
        float turn = Math.copySign(Math.min(Math.abs(diff), 12f), diff);
        bot.setYRot(cur + turn);
        bot.yHeadRot = bot.yBodyRot = bot.getYRot();

        if (Math.abs(diff) > 30) {
            leftImpulse = diff > 0 ? 1 : -1;   // strafe until roughly facing the waypoint
        } else {
            forwardImpulse = 1;
        }
        // jump when the next waypoint is a step up, or a wall is in the way
        Vec3 look = bot.getLookAngle();
        BlockPos front = bot.blockPosition().offset(
                (int) Math.round(look.x * 1.6), 0, (int) Math.round(look.z * 1.6));
        boolean blocked = isSolid(level, front) || isSolid(level, front.above());
        if ((blocked && bot.onGround()) || target.getY() > bot.getY() + 0.5) jumping = true;
    }

    private static boolean isSolid(ServerLevel level, BlockPos p) {
        return level.getBlockState(p).isSolid();
    }
}
