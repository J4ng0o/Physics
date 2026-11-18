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
import org.bukkit.util.BoundingBox;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.*;

public class PhysicsListener implements Listener {

    private final PhysicsRodPlugin plugin;

    // Все активные физические блоки
    private final Map<UUID, PhysicsBlock> activeBlocks = new HashMap<>();
    // Кто кого держит: playerUUID -> blockUUID
    private final Map<UUID, UUID> grabbedByPlayer = new HashMap<>();
    // Кто кем управляет (для обратной связи)
    private final Map<UUID, UUID> grabbedBlockOwner = new HashMap<>();

    private static final double GRAVITY = -0.045;
    private static final double AIR_DRAG = 0.998;
    private static final double GROUND_FRICTION = 0.85;
    private static final double BOUNCE = 0.35;
    private static final double GRAB_DISTANCE = 4.0;
    private static final double MAX_SPEED = 2.0;
    private static final float BLOCK_SCALE = 0.98f;

    public PhysicsListener(PhysicsRodPlugin plugin) {
        this.plugin = plugin;
    }

    public void cleanup() {
        for (PhysicsBlock pb : new ArrayList<>(activeBlocks.values())) {
            pb.remove();
        }
        activeBlocks.clear();
        grabbedByPlayer.clear();
        grabbedBlockOwner.clear();
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        ItemStack hand = player.getInventory().getItemInMainHand();
        if (hand.getType() != Material.BREEZE_ROD) return;
        if (!player.hasPermission("physicsrod.use")) return;

        UUID playerId = player.getUniqueId();

        // === ЛКМ: создать / вернуть блок ===
        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            event.setCancelled(true);
            if (event.getClickedBlock() == null) return;

            // Если игрок что-то держит — бросок
            if (grabbedByPlayer.containsKey(playerId)) {
                PhysicsBlock grabbed = activeBlocks.get(grabbedByPlayer.get(playerId));
                if (grabbed != null) {
                    releaseBlock(player, grabbed, true);
                }
                return;
            }

            // Если уже есть активный блок у игрока — вернуть его
            PhysicsBlock owned = findOwnedBlock(playerId);
            if (owned != null) {
                owned.stopAndSnap();
                activeBlocks.remove(owned.uuid);
                player.sendMessage("§eBlock returned to grid.");
                return;
            }

            // Создать новый физический блок
            Block clicked = event.getClickedBlock();
            Location spawnLoc = clicked.getLocation().add(0.5, 0.5, 0.5);
            Material type = clicked.getType();

            // Проверка, что тут ещё нет физического блока
            for (PhysicsBlock pb : activeBlocks.values()) {
                if (pb.display.getLocation().distanceSquared(spawnLoc) < 1.0) {
                    player.sendMessage("§cThis block is already physical.");
                    return;
                }
            }

            clicked.setType(Material.AIR);
            PhysicsBlock pb = createPhysicsBlock(spawnLoc, type, playerId);
            activeBlocks.put(pb.uuid, pb);
            player.sendMessage("§aBlock is now physical. LMB again to return, RMB to grab.");
            return;
        }

        // === ПКМ: захват / отпускание / перетаскивание ===
        if (event.getAction() == Action.RIGHT_CLICK_BLOCK || event.getAction() == Action.RIGHT_CLICK_AIR) {
            event.setCancelled(true);

            // Если уже держим — отпускаем (без броска, просто падает)
            if (grabbedByPlayer.containsKey(playerId)) {
                PhysicsBlock grabbed = activeBlocks.get(grabbedByPlayer.get(playerId));
                if (grabbed != null) {
                    releaseBlock(player, grabbed, false);
                }
                return;
            }

            // Ищем ближайший физический блок в радиусе действия
            PhysicsBlock target = findNearestBlock(player);
            if (target != null && !target.isGrabbed()) {
                grabBlock(player, target);
            }
        }
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

        for (PhysicsBlock pb : activeBlocks.values()) {
            if (pb.isGrabbed()) continue;
            Location loc = pb.display.getLocation();
            Vector toBlock = loc.clone().subtract(eye).toVector();
            double dist = toBlock.length();
            if (dist > bestDist) continue;
            double dot = toBlock.normalize().dot(dir);
            if (dot > 0.75) { // в пределах ~40 градусов от взгляда
                best = pb;
                bestDist = dist;
            }
        }
        return best;
    }

    private void grabBlock(Player player, PhysicsBlock pb) {
        pb.grabbed = true;
        pb.grabbedBy = player.getUniqueId();
        pb.velocity = new Vector(0, 0, 0);
        grabbedByPlayer.put(player.getUniqueId(), pb.uuid);
        grabbedBlockOwner.put(pb.uuid, player.getUniqueId());
        player.sendMessage("§bBlock grabbed. RMB to drop, LMB to throw.");
    }

    private void releaseBlock(Player player, PhysicsBlock pb, boolean throwIt) {
        pb.grabbed = false;
        pb.grabbedBy = null;
        grabbedByPlayer.remove(player.getUniqueId());
        grabbedBlockOwner.remove(pb.uuid);

        if (throwIt) {
            Vector dir = player.getEyeLocation().getDirection().normalize().multiply(1.6);
            pb.velocity = dir.add(new Vector(0, 0.15, 0));
            player.sendMessage("§aBlock thrown!");
        } else {
            pb.velocity = new Vector(0, 0, 0);
            player.sendMessage("§eBlock released.");
        }
    }

    private PhysicsBlock createPhysicsBlock(Location loc, Material type, UUID owner) {
        World world = loc.getWorld();
        BlockDisplay display = world.spawn(loc, BlockDisplay.class, entity -> {
            entity.setBlock(type.createBlockData());
            Transformation t = entity.getTransformation();
            t.getScale().set(new Vector3f(BLOCK_SCALE, BLOCK_SCALE, BLOCK_SCALE));
            entity.setTransformation(t);
            entity.setTeleportDuration(1);
            entity.setGravity(false);
            entity.setInvulnerable(true);
            entity.setPersistent(false);
            entity.setInterpolationDuration(1);
            entity.setInterpolationDelay(0);
        });

        PhysicsBlock pb = new PhysicsBlock(display, type, owner);
        pb.start();
        return pb;
    }

    /**
     * Внутренний класс: физический блок с ручной симуляцией.
     */
    private class PhysicsBlock {
        final UUID uuid = UUID.randomUUID();
        final BlockDisplay display;
        final Material material;
        final UUID owner;
        BukkitRunnable task;

        Vector velocity = new Vector(0, 0, 0);
        float rotationAngle = 0f;      // накопленный угол наклона
        float rotationSpeed = 0f;      // скорость вращения
        boolean onGround = false;
        boolean grabbed = false;
        UUID grabbedBy = null;

        PhysicsBlock(BlockDisplay display, Material material, UUID owner) {
            this.display = display;
            this.material = material;
            this.owner = owner;
        }

        boolean isGrabbed() {
            return grabbed;
        }

        BoundingBox getBoundingBox(Location loc) {
            double half = 0.49;
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

        /** Логика удержания блока игроком (как физган) */
        private void handleGrab(Location loc) {
            Player holder = Bukkit.getPlayer(grabbedBy);
            if (holder == null || !holder.isOnline()) {
                grabbed = false;
                grabbedBy = null;
                grabbedBlockOwner.remove(uuid);
                return;
            }

            Location eye = holder.getEyeLocation();
            Vector dir = eye.getDirection().normalize();
            Location target = eye.clone().add(dir.multiply(GRAB_DISTANCE));

            // Плавное движение к целевой точке
            Vector delta = target.toVector().subtract(loc.toVector());
            double dist = delta.length();
            if (dist > MAX_SPEED) {
                delta.normalize().multiply(MAX_SPEED);
            } else {
                delta.multiply(0.55);
            }

            Location newLoc = loc.clone().add(delta);
            display.teleport(newLoc);

            // Поворот блока вслед за взглядом (только по Y)
            float yaw = holder.getLocation().getYaw();
            Transformation t = display.getTransformation();
            t.getLeftRotation().set(new Quaternionf().rotateY((float) Math.toRadians(-yaw)));
            display.setTransformation(t);

            // Сохраняем скорость для инерции при броске
            velocity = delta.multiply(0.6);
        }

        /** Основная физика падения, коллизий и отталкивания */
        private void handlePhysics(Location loc) {
            // Гравитация
            velocity.setY(velocity.getY() + GRAVITY);

            // Ограничение скорости
            if (velocity.length() > MAX_SPEED * 1.5) {
                velocity.normalize().multiply(MAX_SPEED * 1.5);
            }

            // Применяем по осям отдельно для корректных коллизий
            Vector move = velocity.clone();

            // --- Y ---
            if (move.getY() != 0) {
                Location testY = loc.clone().add(0, move.getY(), 0);
                if (collides(testY)) {
                    // Приземление / удар головой
                    if (move.getY() < 0) {
                        onGround = true;
                        // Добавляем вращение при падении на край (если движемся по X/Z)
                        float horizontalSpeed = (float) Math.sqrt(velocity.getX() * velocity.getX() + velocity.getZ() * velocity.getZ());
                        if (horizontalSpeed > 0.05 && Math.abs(rotationAngle) < 45f) {
                            rotationSpeed = horizontalSpeed * 8f;
                        }
                    }
                    velocity.setY(-velocity.getY() * BOUNCE);
                    if (Math.abs(velocity.getY()) < 0.08) velocity.setY(0);
                } else {
                    loc.add(0, move.getY(), 0);
                    onGround = false;
                }
            }

            // --- X ---
            if (move.getX() != 0) {
                Location testX = loc.clone().add(move.getX(), 0, 0);
                if (collides(testX)) {
                    velocity.setX(-velocity.getX() * BOUNCE);
                    if (Math.abs(velocity.getX()) < 0.05) velocity.setX(0);
                } else {
                    loc.add(move.getX(), 0, 0);
                }
            }

            // --- Z ---
            if (move.getZ() != 0) {
                Location testZ = loc.clone().add(0, 0, move.getZ());
                if (collides(testZ)) {
                    velocity.setZ(-velocity.getZ() * BOUNCE);
                    if (Math.abs(velocity.getZ()) < 0.05) velocity.setZ(0);
                } else {
                    loc.add(0, 0, move.getZ());
                }
            }

            // Трение на земле
            if (onGround) {
                velocity.setX(velocity.getX() * GROUND_FRICTION);
                velocity.setZ(velocity.getZ() * GROUND_FRICTION);
                if (Math.abs(velocity.getX()) < 0.02) velocity.setX(0);
                if (Math.abs(velocity.getZ()) < 0.02) velocity.setZ(0);
            } else {
                velocity.multiply(AIR_DRAG);
            }

            // Применяем позицию
            display.teleport(loc);

            // --- Вращение ---
            if (Math.abs(rotationSpeed) > 0.01f) {
                rotationAngle += rotationSpeed;
                // Ограничиваем наклон, чтобы блок не улетал в космос
                if (rotationAngle > 90f) { rotationAngle = 90f; rotationSpeed = 0; }
                if (rotationAngle < -90f) { rotationAngle = -90f; rotationSpeed = 0; }
                rotationSpeed *= 0.92f;

                Transformation t = display.getTransformation();
                t.getLeftRotation().set(new Quaternionf().rotateX((float) Math.toRadians(rotationAngle)));
                display.setTransformation(t);
            }

            // Проверка на "успокоение" — если блок стоит на земле и скорость нулевая, можно не тикать
            // (оставляем тик, чтобы можно было схватить и толкнуть)
        }

        /** Проверка коллизий: мир + другие физические блоки */
        private boolean collides(Location testLoc) {
            World world = testLoc.getWorld();
            BoundingBox box = getBoundingBox(testLoc);

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
                            if (blockBox.overlaps(box)) return true;
                        }
                    }
                }
            }

            // Проверка с другими физическими блоками
            for (PhysicsBlock other : activeBlocks.values()) {
                if (other.uuid.equals(this.uuid)) continue;
                if (other.display.isDead()) continue;
                BoundingBox otherBox = other.getBoundingBox(other.display.getLocation());
                if (otherBox.overlaps(box)) {
                    // Отталкивание от другого физического блока
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

            // Ищем ближайшую свободную позицию для возврата блока
            int bx = loc.getBlockX();
            int by = loc.getBlockY();
            int bz = loc.getBlockZ();

            // Пытаемся поставить в текущую или ближайшую свободную позицию
            for (int dy = 0; dy <= 3; dy++) {
                Block target = world.getBlockAt(bx, by + dy, bz);
                if (target.getType() == Material.AIR || !target.getType().isSolid()) {
                    target.setType(material);
                    break;
                }
            }

            display.remove();
            activeBlocks.remove(uuid);
            if (grabbedBy != null) {
                grabbedByPlayer.remove(grabbedBy);
                grabbedBlockOwner.remove(uuid);
            }
        }

        void remove() {
            if (task != null) task.cancel();
            if (display.isValid()) display.remove();
        }
    }
}
