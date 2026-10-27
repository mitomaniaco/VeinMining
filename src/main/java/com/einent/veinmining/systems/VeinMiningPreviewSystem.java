package com.einent.veinmining.systems;

import com.einent.veinmining.config.VeinMiningConfig;
import com.einent.veinmining.gui.VeinMiningHud;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.math.matrix.Matrix4dUtil;
import com.hypixel.hytale.protocol.DebugShape;
import com.hypixel.hytale.protocol.MovementStates;
import com.hypixel.hytale.protocol.packets.player.ClearDebugShapes;
import com.hypixel.hytale.protocol.packets.player.DisplayDebug;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.movement.MovementStatesComponent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.modules.debug.DebugUtils;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.Config;
import com.hypixel.hytale.server.core.util.TargetUtil;
import org.joml.Matrix4d;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.HashSet;
import java.util.Set;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Mostra ao jogador, enquanto a tecla de ativacao esta apertada, o contorno dos blocos que o vein mining
 * quebraria ao minerar o bloco para o qual ele olha. Usa os pacotes de debug shape do cliente, enviados
 * somente a esse jogador.
 *
 * <p>O alvo e lido a cada tick; a selecao de blocos so e recalculada quando o alvo, a face, o padrao ou o
 * limite mudam (ou periodicamente, para refletir blocos quebrados). Toda mudanca limpa as formas anteriores
 * antes de enviar as novas, entao o contorno antigo some no mesmo instante.
 */
public class VeinMiningPreviewSystem extends EntityTickingSystem<EntityStore> {

    /** Recalcula a selecao (mesma chave) para refletir blocos quebrados. */
    private static final long RECOMPUTE_INTERVAL_MS = 250;
    /** Keep-alive: reenvia as formas antes de expirarem, mesmo sem mudanca. */
    private static final long RESEND_INTERVAL_MS = 1500;
    private static final float SHAPE_LIFETIME_SECONDS = 2.0f;
    private static final double LINE_THICKNESS = 0.02;
    private static final int MAX_SEGMENTS = 400;
    /** Enquanto agachado o jogo faz `walking` piscar; mantem o preview vivo por esse tempo. */
    private static final long CROUCH_GRACE_MS = 500;
    private static final double TARGET_REACH = 8.0;
    private static final Vector3f COLOR = new Vector3f(DebugUtils.COLOR_WHITE);

    private final Config<VeinMiningConfig> config;
    private final MiningManager miningManager;
    private final Map<String, PreviewState> states = new HashMap<>();
    private final Query<EntityStore> query = Query.any();

    public VeinMiningPreviewSystem(Config<VeinMiningConfig> config) {
        this.config = config;
        this.miningManager = new MiningManager(config);
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
        MovementStatesComponent moveComp = archetypeChunk.getComponent(index, MovementStatesComponent.getComponentType());
        UUIDComponent uuidComp = archetypeChunk.getComponent(index, UUIDComponent.getComponentType());
        if (player == null || playerRef == null || moveComp == null || uuidComp == null) return;

        String uuid = uuidComp.getUuid().toString();
        PreviewState state = states.computeIfAbsent(uuid, id -> new PreviewState());
        long now = System.currentTimeMillis();

        Set<Vector3i> blocks = computePreview(player, playerRef, archetypeChunk.getReferenceTo(index), moveComp.getMovementStates(), uuid, state, now, store);
        if (blocks == null) {
            hide(player, playerRef, state);
            return;
        }

        boolean changed = blocks != state.sentBlocks;
        if (changed) {
            World world = player.getWorld();
            List<OutlineBuilder.Edge> edges = world == null ? List.of() : OutlineBuilder.buildVisible(blocks, pos -> {
                BlockType t = MiningManager.getBlockType(world, pos.x, pos.y, pos.z);
                return t != null && t.getId().equals("Empty");
            });
            if (edges.isEmpty()) edges = OutlineBuilder.build(blocks);
            if (edges.size() > MAX_SEGMENTS) edges = OutlineBuilder.boundingBox(blocks);
            state.sentEdges = edges;
        }
        if (changed || now - state.lastSent >= RESEND_INTERVAL_MS) {
            sendEdges(playerRef, state.sentEdges);
            state.sentBlocks = blocks;
            state.lastSent = now;
        }
        updateHud(player, playerRef, state, blocks, uuid);
    }

    /**
     * Blocos a destacar (incluindo o bloco alvo), ou {@code null} se nao ha preview a mostrar. O conjunto retornado
     * e reaproveitado (mesma instancia) enquanto a selecao nao mudar.
     */
    private Set<Vector3i> computePreview(Player player, PlayerRef playerRef, Ref<EntityStore> ref, MovementStates movement,
                                         String uuid, PreviewState state, long now, Store<EntityStore> store) {
        VeinMiningConfig cfg = config.get();
        if (!cfg.isPreviewEnabled()) return null;

        boolean isAdmin = playerRef.hasPermission("veinmining.admin");
        if (!cfg.isModEnabled(uuid, isAdmin)) return null;

        String activationMode = cfg.getPlayerActivation(uuid);
        if ("always".equalsIgnoreCase(activationMode)) return null;

        boolean crouchActivation = "crouching".equalsIgnoreCase(activationMode);
        boolean pressed = crouchActivation ? movement.crouching : movement.walking;
        if (pressed) state.lastPressed = now;
        boolean active = pressed || (!crouchActivation && movement.crouching && now - state.lastPressed < CROUCH_GRACE_MS);
        if (!active) return null;

        VeinMiningConfig.GroupSettings group = cfg.resolveGroup(player);
        String targetMode = cfg.getValidatedTargetMode(uuid, group, isAdmin);
        if ("off".equalsIgnoreCase(targetMode)) return null;

        if (cfg.isRequireValidTool() && !holdsValidTool(store, ref, cfg)) return null;

        World world = player.getWorld();
        if (world == null) return null;

        Vector3i target = TargetUtil.getTargetBlock(ref, TARGET_REACH, store);
        if (target == null) return null;

        BlockType type = MiningManager.getBlockType(world, target.x, target.y, target.z);
        if (type == null) return null;
        String blockId = type.getId();
        if (blockId.equals("Empty")) return null;
        if ("ores".equalsIgnoreCase(targetMode) && (!blockId.contains("Ore_") || blockId.contains("_Cracked"))) return null;

        int limit = cfg.getEffectiveLimit(uuid, group, isAdmin);
        PreviewKey key = new PreviewKey(target, miningManager.getHitFace(target, store, ref),
                cfg.getValidatedPattern(uuid, null, isAdmin), cfg.getPlayerOrientation(uuid), limit);

        if (key.equals(state.key) && now - state.computedAt < RECOMPUTE_INTERVAL_MS) return state.blocks;

        List<Vector3i> extra = miningManager.selectBlocks(world, ref, store, target, blockId, uuid, isAdmin, limit);
        if (extra == null) return null;

        Set<Vector3i> blocks = new HashSet<>(extra);
        blocks.add(target);

        state.computedAt = now;
        state.key = key;
        if (blocks.equals(state.blocks)) return state.blocks;
        state.blocks = blocks;
        return blocks;
    }

    private static boolean holdsValidTool(Store<EntityStore> store, Ref<EntityStore> ref, VeinMiningConfig cfg) {
        ItemStack tool = InventoryComponent.getItemInHand(store, ref);
        if (tool == null || tool.isEmpty()) return false;
        String toolId = tool.getItem().getId();
        for (String allowed : cfg.getValidTools()) {
            if (toolId.contains(allowed)) return true;
        }
        return false;
    }

    private static void sendEdges(PlayerRef playerRef, List<OutlineBuilder.Edge> edges) {
        playerRef.getPacketHandler().write(new ClearDebugShapes());
        for (OutlineBuilder.Edge e : edges) {
            double len = e.length();
            double cx = e.x() + (e.axis() == 0 ? len / 2 : 0);
            double cy = e.y() + (e.axis() == 1 ? len / 2 : 0);
            double cz = e.z() + (e.axis() == 2 ? len / 2 : 0);
            double sx = e.axis() == 0 ? len + LINE_THICKNESS : LINE_THICKNESS;
            double sy = e.axis() == 1 ? len + LINE_THICKNESS : LINE_THICKNESS;
            double sz = e.axis() == 2 ? len + LINE_THICKNESS : LINE_THICKNESS;
            Matrix4d matrix = new Matrix4d().translate(cx, cy, cz).scale(sx, sy, sz);
            playerRef.getPacketHandler().write(new DisplayDebug(
                    DebugShape.Cube, Matrix4dUtil.asFloatData(matrix), COLOR,
                    SHAPE_LIFETIME_SECONDS, (byte) DebugUtils.FLAG_NO_WIREFRAME, null, 1.0f));
        }
    }

    private void updateHud(Player player, PlayerRef playerRef, PreviewState state, Set<Vector3i> blocks, String uuid) {
        VeinMiningConfig cfg = config.get();
        boolean isAdmin = playerRef.hasPermission("veinmining.admin");
        String pattern = cfg.getValidatedPattern(uuid, null, isAdmin);
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (Vector3i b : blocks) {
            minX = Math.min(minX, b.x); maxX = Math.max(maxX, b.x);
            minY = Math.min(minY, b.y); maxY = Math.max(maxY, b.y);
            minZ = Math.min(minZ, b.z); maxZ = Math.max(maxZ, b.z);
        }
        String details = blocks.size() + " blocks - " + (maxX - minX + 1) + "x" + (maxY - minY + 1) + "x" + (maxZ - minZ + 1);
        String text = pattern + "|" + details;
        if (state.hud == null) {
            state.hud = new VeinMiningHud(playerRef);
            state.hud.setState(pattern, details);
            player.getHudManager().addCustomHud(playerRef, state.hud);
            state.hudText = text;
        } else if (!text.equals(state.hudText)) {
            state.hud.setState(pattern, details);
            state.hud.show();
            state.hudText = text;
        }
    }

    private static void hide(Player player, PlayerRef playerRef, PreviewState state) {
        if (state.sentBlocks != null) {
            playerRef.getPacketHandler().write(new ClearDebugShapes());
        }
        if (state.hud != null) {
            player.getHudManager().removeCustomHud(playerRef, state.hud.getKey());
            state.hud = null;
            state.hudText = null;
        }
        state.sentBlocks = null;
        state.sentEdges = null;
        state.blocks = null;
        state.key = null;
    }

    private record PreviewKey(Vector3i target, Vector3i face, String pattern, String orientation, int limit) {}

    private static class PreviewState {
        long lastSent;
        long lastPressed;
        long computedAt;
        PreviewKey key;
        /** Selecao atual (calculada). */
        Set<Vector3i> blocks;
        /** Selecao que o cliente esta exibindo agora; {@code null} se nada esta exibido. */
        Set<Vector3i> sentBlocks;
        List<OutlineBuilder.Edge> sentEdges;
        VeinMiningHud hud;
        String hudText;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }
}
