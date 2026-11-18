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

    private static final double GRAVITY = -0.05;
    private static final double AIR_DRAG = 0.995;
    private static final double GROUND_FRICTION = 0.7;
    private static final double RESTITUTION = 0.25; // Упругость (0 - пластик, 1 - резина)
    private static final double GRAB_DISTANCE = 5.0;
    private static final double MAX_SPEED = 3.0;
    private static final float BLOCK_SCALE = 1.0f;
    
    // Параметры физики
    private static final double ANGULAR_DAMPING = 0.96;
    private static final double INERTIA_FACTOR = 0.5;

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
            Vector dir = player.getEyeLocation().getDirection().normalize().multiply(1.8);
            pb.velocity = dir.add(new Vector(0, 0.2, 0));
            // Добавляем угловой момент при броске
            pb.angularVelocity = new Vector(
                    (Math.random() - 0.5) * 0.2,
                    (Math.random() - 0.5) * 0.2,
                    (Math.random() - 0.5) * 0.2
            );
        } else {
            pb.velocity = new Vector(0, 0, 0);
            pb.angularVelocity = new Vector(0, 0, 0);
        }
    }

    private PhysicsBlock createPhysicsBlock(Location loc, Material type, UUID owner) {
        World world = loc.getWorld();
        BlockData data = type.createBlockData();
        
        BlockDisplay display = world.spawn(loc, BlockDisplay.class, entity -> {
            entity.setBlock(data);
            Transformation t = entity.getTransformation();
            // Центрируем модель
            t.getTranslation().set(new Vector3f(-0.5f, -0.5f, -0.5f));
            t.getScale().set(new Vector3f(BLOCK_SCALE, BLOCK_SCALE, BLOCK_SCALE));
            entity.setTransformation(t);
            entity.setTeleportDuration(0); // Мгновенное обновление для физики
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setPersistent(false);
            entity.setInterpolationDuration(0);
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
        Vector angularVelocity = new Vector(0, 0, 0); // Угловая скорость (X, Y, Z)
        Quaternionf rotation = new Quaternionf(); // Текущий поворот
        
        boolean onGround = false;
        boolean grabbed = false;
        UUID grabbedBy = null;
        
        // Для синхронизации визуала
        Location currentLoc;

        PhysicsBlock(BlockDisplay display, Material material, BlockData blockData, UUID owner) {
            this.display = display;
            this.material = material;
            this.blockData = blockData;
            this.owner = owner;
            this.currentLoc = display.getLocation();
        }

        boolean isGrabbed() {
            return grabbed;
        }

        Location getCenterLocation() {
            return display.getLocation();
        }

        BoundingBox getBoundingBox(Location loc) {
            // Хитбокс всегда 1x1x1, независимо от визуального поворота, для стабильной физики
            double half = 0.5;
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

                    if (grabbed) {
                        handleGrab();
                    } else {
                        handlePhysics();
                    }
                }
            };
            task.runTaskTimer(plugin, 0L, 1L);
        }

        private void handleGrab() {
            Player holder = Bukkit.getPlayer(grabbedBy);
            if (holder == null || !holder.isOnline()) {
                grabbed = false;
                grabbedBy = null;
                return;
            }

            Location eye = holder.getEyeLocation();
            Vector dir = eye.getDirection().normalize();
            Location target = eye.clone().add(dir.multiply(GRAB_DISTANCE));

            Vector delta = target.toVector().subtract(currentLoc.toVector());
            double dist = delta.length();
            if (dist > MAX_SPEED) {
                delta.normalize().multiply(MAX_SPEED);
            } else {
                delta.multiply(0.55);
            }

            currentLoc.add(delta);
            display.teleport(currentLoc);

            // Поворот за взглядом
            float yaw = holder.getLocation().getYaw();
            float pitch = holder.getLocation().getPitch();
            
            Quaternionf targetRot = new Quaternionf()
                    .rotateY((float) Math.toRadians(-yaw))
                    .rotateX((float) Math.toRadians(pitch));
            
            // Плавная интерполяция поворота
            rotation.slerp(targetRot, 0.4f);
            
            updateTransformation();
            velocity = delta.multiply(0.6);
        }

        private void handlePhysics() {
            // 1. Гравитация
            velocity.setY(velocity.getY() + GRAVITY);

            // 2. Ограничение скорости
            if (velocity.length() > MAX_SPEED * 1.5) {
                velocity.normalize().multiply(MAX_SPEED * 1.5);
            }

            // 3. Применение скорости
            Vector move = velocity.clone();
            Location nextLoc = currentLoc.clone().add(move);

            // 4. Разрешение коллизий (Collision Resolution)
            boolean collided = resolveCollisions(nextLoc);
            
            if (collided) {
                onGround = true;
                velocity.setX(velocity.getX() * GROUND_FRICTION);
                velocity.setZ(velocity.getZ() * GROUND_FRICTION);
                
                // Затухание вращения на земле
                angularVelocity.multiply(0.9);
                
                // Если скорость очень мала, останавливаем
                if (velocity.lengthSquared() < 0.001) {
                    velocity = new Vector(0, 0, 0);
                }
                if (angularVelocity.lengthSquared() < 0.001) {
                    angularVelocity = new Vector(0, 0, 0);
                    // Финальное выравнивание при остановке
                    snapRotation();
                }
            } else {
                onGround = false;
                velocity.multiply(AIR_DRAG);
                angularVelocity.multiply(ANGULAR_DAMPING);
            }

            currentLoc = nextLoc;
            display.teleport(currentLoc);

            // 5. Применение вращения
            if (angularVelocity.lengthSquared() > 0.0001) {
                Quaternionf dq = new Quaternionf().rotateXYZ(
                        (float) Math.toRadians(angularVelocity.getX()),
                        (float) Math.toRadians(angularVelocity.getY()),
                        (float) Math.toRadians(angularVelocity.getZ())
                );
                rotation.mul(dq);
                rotation.normalize();
                updateTransformation();
            }
        }

        private boolean resolveCollisions(Location nextLoc) {
            World world = nextLoc.getWorld();
            BoundingBox box = getBoundingBox(nextLoc);
            boolean collided = false;

            // Проверка с блоками мира
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
                            if (blockBox.overlaps(box)) {
                                collided = true;
                                
                                // Вычисляем минимальный вектор выталкивания (MTV)
                                Vector push = getOverlapPush(box, blockBox);
                                
                                // Применяем выталкивание
                                nextLoc.add(push);
                                box = getBoundingBox(nextLoc); // Обновляем хитбокс
                                
                                // Гасим скорость по нормали столкновения
                                if (Math.abs(push.getY()) > Math.abs(push.getX()) && Math.abs(push.getY()) > Math.abs(push.getZ())) {
                                    velocity.setY(0);
                                    // Создаем угловой момент при падении на край
                                    if (Math.random() < 0.3) {
                                        angularVelocity.add(new Vector(
                                                (Math.random() - 0.5) * 0.5,
                                                (Math.random() - 0.5) * 0.5,
                                                (Math.random() - 0.5) * 0.5
                                        ));
                                    }
                                } else if (Math.abs(push.getX()) > Math.abs(push.getZ())) {
                                    velocity.setX(velocity.getX() * -RESTITUTION);
                                } else {
                                    velocity.setZ(velocity.getZ() * -RESTITUTION);
                                }
                            }
                        }
                    }
                }
            }
            
            // Проверка с другими физическими блоками
            for (PhysicsBlock other : activeBlocks.values()) {
                if (other.uuid.equals(this.uuid)) continue;
                BoundingBox otherBox = other.getBoundingBox(other.currentLoc);
                if (otherBox.overlaps(box)) {
                    collided = true;
                    Vector push = getOverlapPush(box, otherBox);
                    nextLoc.add(push);
                    box = getBoundingBox(nextLoc);
                    
                    velocity.add(push.multiply(0.1));
                    other.velocity.subtract(push.multiply(0.1));
                    
                    angularVelocity.add(new Vector(
                        (Math.random() - 0.5) * 0.1,
                        (Math.random() - 0.5) * 0.1,
                        (Math.random() - 0.5) * 0.1
                    ));
                }
            }

            return collided;
        }

        private Vector getOverlapPush(BoundingBox a, BoundingBox b) {
            double dx1 = b.getMaxX() - a.getMinX();
            double dx2 = a.getMaxX() - b.getMinX();
            double dy1 = b.getMaxY() - a.getMinY();
            double dy2 = a.getMaxY() - b.getMinY();
            double dz1 = b.getMaxZ() - a.getMinZ();
            double dz2 = a.getMaxZ() - b.getMinZ();

            double minX = Math.min(dx1, dx2);
            double minY = Math.min(dy1, dy2);
            double minZ = Math.min(dz1, dz2);

            if (minX < minY && minX < minZ) {
                return new Vector(dx1 < dx2 ? -minX : minX, 0, 0);
            } else if (minY < minZ) {
                return new Vector(0, dy1 < dy2 ? -minY : minY, 0);
            } else {
                return new Vector(0, 0, dz1 < dz2 ? -minZ : minZ);
            }
        }

        private void snapRotation() {
            // Принудительное выравнивание по осям
            float yaw = rotation.getEulerAnglesYXZ(new Vector3f()).y;
            float pitch = rotation.getEulerAnglesYXZ(new Vector3f()).x;
            float roll = rotation.getEulerAnglesYXZ(new Vector3f()).z;

            pitch = Math.round(pitch / (float)(Math.PI/2)) * (float)(Math.PI/2);
            roll = Math.round(roll / (float)(Math.PI/2)) * (float)(Math.PI/2);

            rotation.identity().rotateYXZ(yaw, pitch, roll);
            updateTransformation();
        }

        private void updateTransformation() {
            Transformation t = display.getTransformation();
            
            // Компенсация смещения для вращения вокруг центра
            Vector3f offset = new Vector3f(-0.5f, -0.5f, -0.5f);
            rotation.transform(offset);
            
            t.getTranslation().set(offset);
            t.getLeftRotation().set(rotation);
            display.setTransformation(t);
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
