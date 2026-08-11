package dev.botview.remotebot;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Bounded 4-direction grid A* on the authoritative server world.
 * Supports walking, 1-block steps up and 1-block drops.
 * ponytail: no 2+ block drops, no diagonal movement; add when the bot
 * needs to descend cliffs (expand neighbor set with drop=2/3).
 */
public class AStar {
    private record Node(BlockPos pos, Node parent, int g) {}

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int MAX_EXPAND = 5000;

    public static List<BlockPos> find(ServerLevel level, BlockPos start, BlockPos goal) {
        start = nearestWalkable(level, start);
        goal = nearestWalkable(level, goal);
        if (start == null || goal == null) return List.of();
        if (start.equals(goal)) return List.of();
        final BlockPos target = goal;

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingInt(n -> n.g + manhattan(n.pos(), target)));
        Map<BlockPos, Node> best = new HashMap<>();
        open.add(new Node(start, null, 0));
        best.put(start, new Node(start, null, 0));

        int expanded = 0;
        while (!open.isEmpty() && expanded++ < MAX_EXPAND) {
            Node cur = open.poll();
            if (cur.pos().equals(goal)) return buildPath(cur);
            for (BlockPos n : neighbors(level, cur.pos())) {
                int g = cur.g() + 1;
                Node old = best.get(n);
                if (old == null || g < old.g()) {
                    Node nn = new Node(n, cur, g);
                    best.put(n, nn);
                    open.add(nn);
                }
            }
        }
        return List.of();
    }

    private static List<BlockPos> buildPath(Node n) {
        List<BlockPos> out = new ArrayList<>();
        for (Node c = n; c != null; c = c.parent()) out.add(c.pos());
        Collections.reverse(out);
        return out;
    }

    private static List<BlockPos> neighbors(ServerLevel level, BlockPos p) {
        List<BlockPos> out = new ArrayList<>();
        for (int[] d : DIRS) {
            BlockPos n = p.offset(d[0], 0, d[1]);
            if (isWalkable(level, n)) {
                out.add(n);
                continue;
            }
            BlockPos up = n.above();
            if (isWalkable(level, up)) out.add(up);                 // step up onto n
            BlockPos dn = n.below();
            if (!isWalkable(level, n) && isWalkable(level, dn)) out.add(dn); // drop 1
        }
        return out;
    }

    public static boolean isWalkable(ServerLevel level, BlockPos p) {
        return !solid(level, p) && !solid(level, p.above()) && solid(level, p.below());
    }

    private static boolean solid(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.isSolid();
    }

    private static BlockPos nearestWalkable(ServerLevel level, BlockPos p) {
        if (isWalkable(level, p)) return p;
        for (int r = 1; r <= 4; r++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != r) continue;
                    for (int dy = -2; dy <= 2; dy++) {
                        BlockPos c = p.offset(dx, dy, dz);
                        if (isWalkable(level, c)) return c;
                    }
                }
            }
        }
        return null;
    }

    private static int manhattan(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY()) + Math.abs(a.getZ() - b.getZ());
    }
}
