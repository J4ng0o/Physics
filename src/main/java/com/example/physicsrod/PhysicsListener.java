package com.example.physicsrod;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Transformation;
import org.joml.Vector3f;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PhysicsListener implements Listener {

    private final PhysicsRodPlugin plugin;
    // 记录每个玩家当前"抓取"的物理方块（简化版：一人同时只能处理一个）
    private final Map<UUID, PhysicsBlock> activeBlocks = new HashMap<>();

    public PhysicsListener(PhysicsRodPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();

        if (hand.getType() != Material.BREEZE_ROD) return;
        if (!player.hasPermission("physicsrod.use")) return;
        if (event.getAction() != Action.LEFT_CLICK_BLOCK) return;
        if (event.getClickedBlock() == null) return;

        event.setCancelled(true);
        Block clicked = event.getClickedBlock();
        UUID uuid = player.getUniqueId();

        // 如果玩家已经有活跃的物理方块，再次左键 = 回收并归位
        if (activeBlocks.containsKey(uuid)) {
            PhysicsBlock existing = activeBlocks.remove(uuid);
            existing.stopAndSnap();
            player.sendMessage("§eBlock returned to grid.");
            return;
        }

        // 检查该方块是否已经被物理化
        Location blockLoc = clicked.getLocation();
        for (PhysicsBlock pb : activeBlocks.values()) {
            if (pb.display.getLocation().distanceSquared(blockLoc.clone().add(0.5, 0.5, 0.5)) < 1.0) {
                player.sendMessage("§cThis block is already physical.");
                return;
            }
        }

        // 创建物理方块
        Material type = clicked.getType();
        clicked.setType(Material.AIR);

        BlockDisplay display = blockLoc.getWorld().spawn(
                blockLoc.clone().add(0.5, 0, 0.5), // 居中
                BlockDisplay.class,
                entity -> {
                    entity.setBlock(type.createBlockData());
                    // 可选：稍微缩小一点避免与相邻方块穿插
                    Transformation t = entity.getTransformation();
                    t.getScale().set(new Vector3f(0.98f, 0.98f, 0.98f));
                    entity.setTransformation(t);
                    entity.setTeleportDuration(1); // 关键：1 tick 插值，让手动更新看起来平滑
                    entity.setGravity(false);
                    entity.setInvulnerable(true);
                    entity.setPersistent(false);
                }
        );

        PhysicsBlock pb = new PhysicsBlock(display, type);
        activeBlocks.put(uuid, pb);
        pb.start();

        player.sendMessage("§aBlock is now physical. Left-click again to return it.");
    }

    /**
     * 内部类：管理一个物理方块的简单下落模拟
     */
    private class PhysicsBlock {
        final BlockDisplay display;
        final Material material;
        BukkitRunnable task;
        double velocityY = 0;
        static final double GRAVITY = -0.04;
        static final double GROUND_Y_OFFSET = 0.5; // 方块中心到地面的距离

        PhysicsBlock(BlockDisplay display, Material material) {
            this.display = display;
            this.material = material;
        }

        void start() {
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    if (display.isDead() || !display.isValid()) {
                        cancel();
                        return;
                    }

                    Location loc = display.getLocation();
                    World world = loc.getWorld();

                    // 简单重力
                    velocityY += GRAVITY;
                    double newY = loc.getY() + velocityY;

                    // 检查下方是否有固体方块（简单的地面检测）
                    Location below = loc.clone();
                    below.setY(newY - GROUND_Y_OFFSET);
                    Block groundBlock = world.getBlockAt(below);
                    boolean hitGround = groundBlock.getType().isSolid()
                            || newY <= world.getMinHeight() + 1;

                    if (hitGround && velocityY < 0) {
                        // 落地：停住
                        newY = groundBlock.getY() + 1 + GROUND_Y_OFFSET;
                        velocityY = 0;
                        loc.setY(newY);
                        display.teleport(loc);
                        // 停止任务，但方块保持存在，直到玩家再次点击
                        cancel();
                        return;
                    }

                    loc.setY(newY);
                    display.teleport(loc);
                }
            };
            task.runTaskTimer(plugin, 0L, 1L);
        }

        void stopAndSnap() {
            if (task != null) task.cancel();

            Location loc = display.getLocation();
            // 对齐到方块网格
            int blockX = loc.getBlockX();
            int blockY = loc.getBlockY();
            int blockZ = loc.getBlockZ();

            // 如果 y 是小数且靠近下方方块，贴地
            if (loc.getY() - Math.floor(loc.getY()) < 0.5) {
                blockY = (int) Math.floor(loc.getY());
            } else {
                blockY = (int) Math.ceil(loc.getY());
            }

            World world = loc.getWorld();
            world.getBlockAt(blockX, blockY, blockZ).setType(material);
            display.remove();
        }
    }
}
