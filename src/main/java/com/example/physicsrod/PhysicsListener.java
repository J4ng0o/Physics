package com.example.physicsrod;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public class PhysicsListener implements Listener {

    private final PhysicsRodPlugin plugin;

    private final Map<UUID, PhysicsBlock> activeBlocks = new HashMap<>();
    private final Map<UUID, UUID> grabbedByPlayer = new HashMap<>();

    private static final double GRAVITY = -0.045;
    private static final double AIR_DRAG = 0.998;
    private static final double GROUND_FRICTION = 0.85;
    private static final double BOUNCE = 0.35;
    private static final double GRAB_DISTANCE = 5.0;
    private static final double MAX_SPEED = 2.0;
    private static final float BLOCK_SCALE = 0.98f;

    public PhysicsListener(PhysicsRodPlugin plugin) {
        this.plugin = plugin;
        startActionBarTask();
    }

    public void cleanup() {
        for (PhysicsBlock pb : new ArrayList<>(activeBlocks.values())) {
            pb.remove();
        }
        activeBlocks.clear();
        grabbedByPlayer.clear();
    }

    private void startActionBarTask() {
        new BukkitRunnable() {
            @Override
            public void run() {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    ItemStack hand = player.getInventory().getItemInMainHand();
                    if (hand.getType() == Material.BREEZE_ROD && player.hasPermission("physicsrod.use")) {
                        String msg;
                        UUID uuid = player.getUniqueId();
                        if (grabbedByPlayer.containsKey(uuid)) {
                            msg = "§bУдерживается §7| §fЛКМ §7- Бросок §8| §fПКМ §7- Отпустить";
                        } else {
                            msg = "§bPhysics Rod §7| §fЛКМ §7- Создать/Вернуть §8| §fПКМ §7- Притянуть";
                        }
                        player.sendActionBar(msg);
                    }
                }
            }
        }.runTaskTimer(plugin, 0L, 10L);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.BREEZE_ROD) return;
        if (!player.hasPermission("physicsrod.use")) return;

        UUID playerId = player.getUniqueId();
        Action action = event.getAction();

        if (action == Action.LEFT_CLICK_AIR || action == Action.LEFT_CLICK_BLOCK) {
            if (grabbedByPlayer.containsKey(playerId)) {
                event.setCancelled(true);
                PhysicsBlock grabbed = activeBlocks.get(grabbedByPlayer.get(playerId));
                if (grabbed != null) releaseBlock(player, grabbed, true);
                return;
            }
            
            if (action == Action.LEFT_CLICK_BLOCK) {
                event.setCancelled(true);
                handleLeftClickBlock(player, event.getClickedBlock());
            }
            return;
        }

        if (action == Action.RIGHT_CLICK_AIR || action == Action.RIGHT_CLICK_BLOCK) {
            event.setCancelled(true);
            if (grabbedByPlayer.containsKey(playerId)) {
                PhysicsBlock grabbed = activeBlocks.get(grabbedByPlayer.get(playerId));
                if (grabbed != null) releaseBlock(player, grabbed, false);
                return;
            }
            PhysicsBlock target = findNearestBlock(player);
            if (target != null && !target.isGrabbed()) {
                grabBlock(player, target);
            }
        }
    }

    private void handleLeftClickBlock(Player player, Block clickedBlock) {
        if (clickedBlock == null) return;
        UUID playerId = player.getUniqueId();

        PhysicsBlock owned = findOwnedBlock(playerId);
        if (owned != null) {
            owned.stopAndSnap();
            return;
        }

        // Используем точный центр блока для спавна
        Location spawnLoc = clickedBlock.getLocation().add(0.5, 0.5, 0.5);
        Material type = clickedBlock.getType();

        for (PhysicsBlock pb : activeBlocks.values()) {
            if (pb.getCenterLocation().distanceSquared(spawnLoc) < 1.0) return;
        }

        clickedBlock.setType(Material.AIR);
        PhysicsBlock pb = createPhysicsBlock(spawnLoc, type, playerId);
        activeBlocks.put(pb.uuid, pb);
    }

    private PhysicsBlock findOwnedBlock(UUID playerId) {
        for (PhysicsBlock pb : activeBlocks.values()) {
            if (pb.owner.equals(playerId)) return pb;
        }
        return null;
    }

    private PhysicsBlock findNearestBlock(Player player) {
        Location eye = player.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        PhysicsBlock best = null;
        double bestDist = GRAB_DISTANCE;
        double bestDot = 0.85;

        for (PhysicsBlock pb : activeBlocks.values()) {
            if (pb.isGrabbed()) continue;
            Location loc = pb.getCenterLocation();
            Vector toBlock = loc.clone().subtract(eye).toVector();
            double dist = toBlock.length();
            if (dist > bestDist) continue;
            double dot = toBlock.normalize().dot(dir);
            if (dot > bestDot) {
                best = pb;
                bestDist = dist;
                bestDot = dot;
            }
        }
        return best;
    }

    private void grabBlock(Player player, PhysicsBlock pb) {
        pb.grabbed = true;
        pb.grabbedBy = player.getUniqueId();
        pb.velocity = new Vector(0, 0, 0);
        grabbedByPlayer.put(player.getUniqueId(), pb.uuid);
    }

    private void releaseBlock(Player player, PhysicsBlock pb, boolean throwIt) {
        pb.grabbed = false;
        pb.grabbedBy = null;
        grabbedByPlayer.remove(player.getUniqueId());

        if (throwIt) {
            Vector dir = player.getEyeLocation().getDirection().normalize().multiply(1.6);
            pb.velocity = dir.add(new Vector(0, 0.15, 0));
        } else {
            pb.velocity = new Vector(0, 0, 0);
        }
    }

    private PhysicsBlock createPhysicsBlock(Location loc, Material type, UUID owner) {
        World world = loc.getWorld();
        BlockData data = type.createBlockData();
        
        BlockDisplay display = world.spawn(loc, BlockDisplay.class, entity -> {
            entity.setBlock(data);
            // Инициализируем трансформацию с учетом смещения
            Transformation t = entity.getTransformation();
            t.getTranslation().set(new Vector3f(-0.5f, -0.5f, -0.5f)); // Сдвиг к центру
            t.getScale().set(new Vector3f(BLOCK_SCALE, BLOCK_SCALE, BLOCK_SCALE));
            entity.setTransformation(t);
            entity.setTeleportDuration(1);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setPersistent(false);
            entity.setInterpolationDuration(1);
            entity.setInterpolationDelay(0);
        });

        PhysicsBlock pb = new PhysicsBlock(display, type, data, owner);
        pb.start();
        return pb;
    }

    private class PhysicsBlock {
        final UUID uuid = UUID.randomUUID();
        final BlockDisplay display;
        final Material material;
        final BlockData blockData;
        final UUID owner;
        BukkitRunnable task;

        Vector velocity = new Vector(0, 0, 0);
        float rotationPitch = 0f;
        float rotationRoll = 0f;
        float rotationSpeedPitch = 0f;
        float rotationSpeedRoll = 0f;
        
        boolean onGround = false;
        boolean grabbed = false;
        UUID grabbedBy = null;

        PhysicsBlock(BlockDisplay display, Material material, BlockData blockData, UUID owner) {
            this.display = display;
            this.material = material;
            this.blockData = blockData;
            this.owner = owner;
        }

        boolean isGrabbed() {
            return grabbed;
        }

        Location getCenterLocation() {
            return display.getLocation(); // display.getLocation() возвращает центр, так как мы применили смещение трансформацией
        }

        BoundingBox getBoundingBox(Location loc) {
            double half = 0.49;
            // Возвращаем AABB, который всегда 1x1x1, но мы будем проверять его на пересечение с блоками
            return new BoundingBox(
                    loc.getX() - half, loc.getY() - half, loc.getZ() - half,
                    loc.getX() + half, loc.getY() + half, loc.getZ() + half
            );
        }

        void start() {
            task = new BukkitRunnable() {
                @Override
                public void run() {
                    if (display.isDead() || !display.isValid()) {
                        cancel();
                        activeBlocks.remove(uuid);
                        return;
                    }

                    Location loc = display.getLocation();

                    if (grabbed) {
                        handleGrab(loc);
                    } else {
                        handlePhysics(loc);
                    }
                }
            };
            task.runTaskTimer(plugin, 0L, 1L);
        }

        private void handleGrab(Location loc) {
            Player holder = Bukkit.getPlayer(grabbedBy);
            if (holder == null || !holder.isOnline()) {
                grabbed = false;
                grabbedBy = null;
                return;
            }

            Location eye = holder.getEyeLocation();
            Vector dir = eye.getDirection().normalize();
            Location target = eye.clone().add(dir.multiply(GRAB_DISTANCE));

            Vector delta = target.toVector().subtract(loc.toVector());
            double dist = delta.length();
            if (dist > MAX_SPEED) {
                delta.normalize().multiply(MAX_SPEED);
            } else {
                delta.multiply(0.55);
            }

            Location newLoc = loc.clone().add(delta);
            display.teleport(newLoc);

            // Поворот блока вслед за взглядом
            float yaw = holder.getLocation().getYaw();
            float pitch = holder.getLocation().getPitch();
            
            Transformation t = display.getTransformation();
            Quaternionf quat = new Quaternionf()
                    .rotateY((float) Math.toRadians(-yaw))
                    .rotateX((float) Math.toRadians(pitch));
            t.getLeftRotation().set(quat);
            display.setTransformation(t);

            velocity = delta.multiply(0.6);
        }

        private void handlePhysics(Location loc) {
            velocity.setY(velocity.getY() + GRAVITY);

            if (velocity.length() > MAX_SPEED * 1.5) {
                velocity.normalize().multiply(MAX_SPEED * 1.5);
            }

            Vector move = velocity.clone();

            // Проверка по Y
            if (move.getY() != 0) {
                Location testY = loc.clone().add(0, move.getY(), 0);
                if (collides(testY)) {
                    if (move.getY() < 0) {
                        onGround = true;
                        // Расчет опрокидывания, если упал на край
                        calculateTipping(testY);
                    }
                    velocity.setY(-velocity.getY() * BOUNCE);
                    if (Math.abs(velocity.getY()) < 0.08) velocity.setY(0);
                } else {
                    loc.add(0, move.getY(), 0);
                    onGround = false;
                }
            }

            // Проверка по X
            if (move.getX() != 0) {
                Location testX = loc.clone().add(move.getX(), 0, 0);
                if (collides(testX)) {
                    velocity.setX(-velocity.getX() * BOUNCE);
                    if (Math.abs(velocity.getX()) < 0.05) velocity.setX(0);
                } else {
                    loc.add(move.getX(), 0, 0);
                }
            }

            // Проверка по Z
            if (move.getZ() != 0) {
                Location testZ = loc.clone().add(0, 0, move.getZ());
                if (collides(testZ)) {
                    velocity.setZ(-velocity.getZ() * BOUNCE);
                    if (Math.abs(velocity.getZ()) < 0.05) velocity.setZ(0);
                } else {
                    loc.add(0, 0, move.getZ());
                }
            }

            if (onGround) {
                velocity.setX(velocity.getX() * GROUND_FRICTION);
                velocity.setZ(velocity.getZ() * GROUND_FRICTION);
                if (Math.abs(velocity.getX()) < 0.02) velocity.setX(0);
                if (Math.abs(velocity.getZ()) < 0.02) velocity.setZ(0);
            } else {
                velocity.multiply(AIR_DRAG);
            }

            display.teleport(loc);

            // Применяем вращение
            if (Math.abs(rotationSpeedPitch) > 0.01f || Math.abs(rotationSpeedRoll) > 0.01f) {
                rotationPitch += rotationSpeedPitch;
                rotationRoll += rotationSpeedRoll;
                
                // Ограничиваем углы
                if (rotationPitch > 90f) { rotationPitch = 90f; rotationSpeedPitch = 0; }
                if (rotationPitch < -90f) { rotationPitch = -90f; rotationSpeedPitch = 0; }
                if (rotationRoll > 90f) { rotationRoll = 90f; rotationSpeedRoll = 0; }
                if (rotationRoll < -90f) { rotationRoll = -90f; rotationSpeedRoll = 0; }
                
                rotationSpeedPitch *= 0.92f;
                rotationSpeedRoll *= 0.92f;

                Transformation t = display.getTransformation();
                Quaternionf quat = new Quaternionf()
                        .rotateX((float) Math.toRadians(rotationPitch))
                        .rotateZ((float) Math.toRadians(rotationRoll));
                t.getLeftRotation().set(quat);
                display.setTransformation(t);
            }
        }

        private void calculateTipping(Location loc) {
            // Проверяем, есть ли блок под центром.
            // Если под центром пусто, а под краем есть — блок должен упасть на бок.
            World world = loc.getWorld();
            Block centerBlock = world.getBlockAt(loc.clone().subtract(0, 0.5, 0));
            
            if (centerBlock.getType().isAir()) {
                // Блок висит на краю. Добавляем случайный наклон, чтобы он упал
                rotationSpeedPitch = (float) (Math.random() * 0.2 - 0.1);
                rotationSpeedRoll = (float) (Math.random() * 0.2 - 0.1);
                velocity.setY(-0.1); // Толкаем вниз
            }
        }

        private boolean collides(Location testLoc) {
            World world = testLoc.getWorld();
            BoundingBox box = getBoundingBox(testLoc);

            int minX = (int) Math.floor(box.getMinX());
            int minY = (int) Math.floor(box.getMinY());
            int minZ = (int) Math.floor(box.getMinZ());
            int maxX = (int) Math.floor(box.getMaxX());
            int maxY = (int) Math.floor(box.getMaxY());
            int maxZ = (int) Math.floor(box.getMaxZ());

            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        Block b = world.getBlockAt(x, y, z);
                        if (b.getType().isSolid()) {
                            BoundingBox blockBox = b.getBoundingBox();
                            if (blockBox.overlaps(box)) return true;
                        }
                    }
                }
            }

            for (PhysicsBlock other : activeBlocks.values()) {
                if (other.uuid.equals(this.uuid)) continue;
                if (other.display.isDead()) continue;
                BoundingBox otherBox = other.getBoundingBox(other.display.getLocation());
                if (otherBox.overlaps(box)) {
                    Vector push = testLoc.toVector().subtract(other.display.getLocation().toVector());
                    if (push.lengthSquared() > 0.0001) {
                        push.normalize().multiply(0.08);
                        velocity.add(push);
                    }
                    return true;
                }
            }
            return false;
        }

        void stopAndSnap() {
            if (task != null) task.cancel();

            Location loc = display.getLocation();
            World world = loc.getWorld();

            int bx = loc.getBlockX();
            int by = loc.getBlockY();
            int bz = loc.getBlockZ();

            for (int dy = 0; dy <= 3; dy++) {
                Block target = world.getBlockAt(bx, by + dy, bz);
                if (target.getType().isAir() || !target.getType().isSolid()) {
                    target.setBlockData(blockData, false);
                    break;
                }
            }

            display.remove();
            activeBlocks.remove(uuid);
            if (grabbedBy != null) {
                grabbedByPlayer.remove(grabbedBy);
            }
        }

        void remove() {
            if (task != null) task.cancel();
            if (display.isValid()) display.remove();
        }
    }
}
