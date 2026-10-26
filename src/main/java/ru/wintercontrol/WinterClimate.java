package ru.wintercontrol;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.type.Snow;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.*;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class WinterClimate {

    private final WinterControl plugin;

    private final Map<Column, Cover> covers = new HashMap<>();
    private final ArrayDeque<Column> coverQueue = new ArrayDeque<>();

    private final Map<Point, String> ice = new HashMap<>();
    private final ArrayDeque<Point> iceQueue = new ArrayDeque<>();

    private final Map<ChunkKey, Cursor> cursors = new LinkedHashMap<>();
    private final Map<ChunkKey, Integer> manualPass = new HashMap<>();

    private final Map<UUID, Long> coldSince = new HashMap<>();
    private final Map<UUID, Long> lastDamage = new HashMap<>();

    private final Map<Material, Integer> heat = new EnumMap<>(Material.class);

    private List<Cursor> scan = new ArrayList<>();

    private int scanIndex;
    private int pulse;
    private int maxHeatRadius;
    private int manualTarget;

    private long nextStorm;
    private long stormEnds;

    private boolean previousWinter;

    private final BukkitTask task;

    public WinterClimate(WinterControl plugin) {
        this.plugin = plugin;

        defaults();
        rebuildHeat();
        loadState();
        refreshChunks();

        previousWinter = enabled();
        nextStorm = 0L;

        task = Bukkit.getScheduler().runTaskTimer(
                plugin, this::tick, 2L, 2L
        );
    }

    private void defaults() {
        var c = plugin.getConfig();

        int oldY = c.getInt("winter.cold-y", 50);

        c.addDefault("cold.players-y", oldY);
        c.addDefault("cold.passive-y", oldY);
        c.addDefault("cold.hostile-y", oldY);
        c.addDefault("cold.grace-seconds", 15);
        c.addDefault("cold.damage", 1.0);
        c.addDefault("cold.damage-interval-seconds", 2);

        c.addDefault("snow.interval-seconds", 90);
        c.addDefault("snow.duration-seconds", 45);
        c.addDefault("snow.grow-seconds", 4.0);
        c.addDefault("snow.melt-seconds", 0.6);
        c.addDefault("snow.paused", false);
        c.addDefault("snow.particles-per-step", 120);

        c.addDefault("performance.columns-per-step", 64);
        c.addDefault("performance.climate-updates-per-step", 128);

        c.options().copyDefaults(true);
        plugin.saveConfig();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("winter.enabled");
    }

    private boolean normal(World world) {
        return world.getEnvironment() == World.Environment.NORMAL;
    }

    private int integer(String path, int minimum, int maximum) {
        return Math.max(
                minimum,
                Math.min(maximum, plugin.getConfig().getInt(path))
        );
    }

    private long milliseconds(String path, double minimum) {
        double seconds = plugin.getConfig().getDouble(path);
        return (long) (Math.max(minimum, seconds) * 1000.0);
    }

    private void tick() {
        pulse++;

        // Сохранение состояния не зависит от остановки симуляции.
        if (pulse % 600 == 0) {
            saveState();
        }

        if (plugin.getServer().getServerTickManager().isFrozen()) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean winter = enabled();

        if (winter != previousWinter) {
            previousWinter = winter;
            stormEnds = 0L;
            nextStorm = 0L;
            manualPass.clear();

            for (Cover cover : covers.values()) {
                cover.requested = 0;
                cover.nextUpdate = 0L;
            }

            if (winter) {
                plugin.getConfig().set("snow.paused", false);

                for (World world : Bukkit.getWorlds()) {
                    if (normal(world)) {
                        world.setStorm(false);
                    }
                }
            } else {
                coldSince.clear();
                lastDamage.clear();
            }

            plugin.saveConfig();
        }

        if (pulse % 10 == 0) {
            rebuildHeat();

            if (winter) {
                updateCold(now);
            }
        }

        if (pulse % 50 == 0) {
            refreshChunks();
        }

        updateStorm(now);

        int columns = integer(
                "performance.columns-per-step", 1, 1024
        );

        // Чередуем чанки: один столбец здесь, один в следующем.
        // Не обрабатываем сначала все 256 столбцов одного чанка.
        for (int i = 0; i < columns && !scan.isEmpty(); i++) {
            if (scanIndex >= scan.size()) {
                scanIndex = 0;
            }

            Cursor cursor = scan.get(scanIndex++);
            scanColumn(cursor, now);
        }

        int updates = integer(
                "performance.climate-updates-per-step", 2, 2048
        );

        updateCovers(now, updates / 2);
        updateIce(updates / 2);

        if (snowing(now) || !manualPass.isEmpty()) {
            particles();
        }
    }

    private void updateStorm(long now) {
        if (!enabled()
                || plugin.getConfig().getBoolean("snow.paused")) {
            stormEnds = 0L;
            return;
        }

        if (plugin.getConfig().getBoolean("snow.long")) {
            return;
        }

        if (stormEnds != 0L && now >= stormEnds) {
            stormEnds = 0L;
            nextStorm = now + milliseconds(
                    "snow.interval-seconds", 1.0
            );
        }

        if (stormEnds == 0L && now >= nextStorm) {
            stormEnds = now + milliseconds(
                    "snow.duration-seconds", 1.0
            );
        }
    }

    private boolean snowing(long now) {
        return enabled()
                && !plugin.getConfig().getBoolean("snow.paused")
                && (plugin.getConfig().getBoolean("snow.long")
                    || now < stormEnds);
    }

    public int coverNow(int layers) {
        manualTarget = Math.max(1, Math.min(64, layers));
        manualPass.clear();

        refreshChunks();

        for (ChunkKey key : cursors.keySet()) {
            manualPass.put(key, 256);
        }

        plugin.getConfig().set("snow.paused", false);
        return manualPass.size();
    }

    public void stopSnow() {
        manualPass.clear();
        stormEnds = 0L;

        plugin.getConfig().set("snow.long", false);
        plugin.getConfig().set("snow.paused", true);
    }

    private void refreshChunks() {
        Set<ChunkKey> loaded = new HashSet<>();

        for (World world : Bukkit.getWorlds()) {
            if (!normal(world)) {
                continue;
            }

            for (Chunk chunk : world.getLoadedChunks()) {
                ChunkKey key = new ChunkKey(
                        world.getUID(), chunk.getX(), chunk.getZ()
                );

                loaded.add(key);
                cursors.computeIfAbsent(key, Cursor::new);
            }
        }

        cursors.keySet().removeIf(key -> !loaded.contains(key));
        manualPass.keySet().removeIf(key -> !loaded.contains(key));

        scan = new ArrayList<>(cursors.values());

        if (scanIndex >= scan.size()) {
            scanIndex = 0;
        }
    }

    private void scanColumn(Cursor cursor, long now) {
        World world = cursor.key.loadedWorld();

        if (world == null) {
            return;
        }

        // 73 взаимно просто с 256:
        // обходим все столбцы, но не рисуем последовательные полосы.
        int local = cursor.column;
        cursor.column = (cursor.column + 73) & 255;

        int x = cursor.key.x * 16 + (local & 15);
        int z = cursor.key.z * 16 + (local >> 4);

        int requested = 0;
        Integer remaining = manualPass.get(cursor.key);

        if (remaining != null) {
            requested = manualTarget;

            if (remaining <= 1) {
                manualPass.remove(cursor.key);
            } else {
                manualPass.put(cursor.key, remaining - 1);
            }
        }

        Column column = new Column(world.getUID(), x, z);

        Cover existing = covers.get(column);

        if (existing != null) {
            if (enabled()) {
                existing.requested = Math.max(
                        existing.requested, requested
                );
            }

            return;
        }

        if (!enabled()) {
            return;
        }

        Block top = world.getHighestBlockAt(
                x, z, HeightMap.WORLD_SURFACE
        );

        // Замерзает открытая поверхностная вода.
        if (top.getType() == Material.WATER) {
            if (top.getBlockData() instanceof Levelled water
                    && water.getLevel() == 0
                    && !warm(top)) {

                Point point = new Point(
                        world.getUID(), x, top.getY(), z
                );

                if (!ice.containsKey(point)) {
                    ice.put(point, top.getBlockData().getAsString());
                    iceQueue.addLast(point);
                }

                top.setType(Material.ICE, false);
            }

            return;
        }

        if (requested == 0 && !snowing(now)) {
            return;
        }

        // Старый или естественный снег не присваиваем плагину:
        // иначе выключение зимы уничтожало бы снежные биомы.
        if (top.getType() == Material.SNOW
                || top.getType() == Material.SNOW_BLOCK) {
            return;
        }

        // Находим опору под травой, цветами и другими
        // проходимыми декоративными блоками.
        int searched = 0;

        while (top.isPassable()
                && top.getType() != Material.WATER
                && top.getType() != Material.LAVA
                && top.getY() > world.getMinHeight()
                && searched++ < 16) {
            top = top.getRelative(0, -1, 0);
        }

        if (top.getType().isAir()
                || top.isLiquid()
                || top.getY() >= world.getMaxHeight() - 1
                || warm(top)) {
            return;
        }

        Block above = top.getRelative(0, 1, 0);
        BlockData snowData = Material.SNOW.createBlockData();

        boolean physical = above.getType().isAir()
                && above.canPlace(snowData);

        double height = top.getBoundingBox().getMaxY();

        if (height <= top.getY()) {
            return;
        }

        Cover cover = new Cover(
                column, top.getY(), height, !physical
        );

        cover.requested = requested;

        if (render(cover, 1)) {
            cover.layers = 1;
            cover.nextUpdate = now + milliseconds(
                    "snow.grow-seconds", 0.2
            );

            covers.put(column, cover);
            coverQueue.addLast(column);
        }
    }

    private void updateCovers(long now, int budget) {
        int count = Math.min(budget, coverQueue.size());

        for (int i = 0; i < count; i++) {
            Column key = coverQueue.removeFirst();
            Cover cover = covers.get(key);

            if (cover == null) {
                continue;
            }

            World world = key.loadedWorld();

            if (world == null) {
                coverQueue.addLast(key);
                continue;
            }

            // Визуальную сущность восстанавливаем после
            // перезапуска или повторной загрузки чанка.
            if (cover.visual
                    && (cover.display == null
                        || !cover.display.isValid())) {
                render(cover, cover.layers);
            }

            if (now >= cover.nextUpdate) {
                Block base = world.getBlockAt(
                        key.x, cover.baseY, key.z
                );

                boolean melting = !enabled() || warm(base);
                int next = cover.layers;

                if (melting) {
                    next--;
                    cover.nextUpdate = now + milliseconds(
                            "snow.melt-seconds", 0.1
                    );
                } else {
                    int target = cover.requested;

                    if (snowing(now)) {
                        target = Math.max(
                                target,
                                integer("snow.max-layers", 1, 64)
                        );
                    }

                    if (cover.layers < target) {
                        next++;
                    }

                    cover.nextUpdate = now + milliseconds(
                            "snow.grow-seconds", 0.2
                    );
                }

                if (next != cover.layers) {
                    if (render(cover, next)) {
                        cover.layers = next;
                    }
                }
            }

            if (cover.layers <= 0) {
                covers.remove(key);
            } else {
                coverQueue.addLast(key);
            }
        }
    }

    private BlockData layerData(int layers, int level) {
        int amount = Math.min(8, layers - level * 8);

        if (amount <= 0) {
            return Material.AIR.createBlockData();
        }

        if (amount == 8) {
            return Material.SNOW_BLOCK.createBlockData();
        }

        Snow data = (Snow) Material.SNOW.createBlockData();
        data.setLayers(amount);
        return data;
    }

    private boolean render(Cover cover, int layers) {
        World world = cover.key.loadedWorld();

        if (world == null) {
            return false;
        }

        if (cover.visual) {
            if (layers <= 0) {
                if (cover.display != null) {
                    cover.display.remove();
                    cover.display = null;
                }

                return true;
            }

            if (cover.display == null || !cover.display.isValid()) {
                cover.display = world.spawn(
                        new Location(
                                world,
                                cover.key.x,
                                cover.height,
                                cover.key.z
                        ),
                        BlockDisplay.class,
                        display -> {
                            display.setBlock(
                                    Material.SNOW_BLOCK.createBlockData()
                            );

                            // Состояние сохраняем сами, чтобы не
                            // оставлять бесхозные сущности в мире.
                            display.setPersistent(false);
                            display.setViewRange(1.5f);
                            display.setInterpolationDuration(8);
                        }
                );
            }

            cover.display.setInterpolationDelay(0);
            cover.display.setTransformation(new Transformation(
                    new Vector3f(),
                    new Quaternionf(),
                    new Vector3f(1.0f, layers / 8.0f, 1.0f),
                    new Quaternionf()
            ));

            return true;
        }

        int oldBlocks = (cover.layers + 7) / 8;
        int newBlocks = (layers + 7) / 8;
        int blocks = Math.max(oldBlocks, newBlocks);

        if (cover.baseY + blocks >= world.getMaxHeight()) {
            return false;
        }

        // Сначала проверяем весь диапазон.
        // Не перезаписываем постройки, появившиеся на месте снега.
        for (int level = 0; level < blocks; level++) {
            Block block = world.getBlockAt(
                    cover.key.x,
                    cover.baseY + 1 + level,
                    cover.key.z
            );

            BlockData expected = layerData(cover.layers, level);

            if (!block.getBlockData().getAsString()
                    .equals(expected.getAsString())) {
                return false;
            }
        }

        for (int level = 0; level < blocks; level++) {
            world.getBlockAt(
                    cover.key.x,
                    cover.baseY + 1 + level,
                    cover.key.z
            ).setBlockData(layerData(layers, level), false);
        }

        return true;
    }

    private void updateIce(int budget) {
        int count = Math.min(budget, iceQueue.size());

        for (int i = 0; i < count; i++) {
            Point point = iceQueue.removeFirst();
            String original = ice.get(point);

            if (original == null) {
                continue;
            }

            World world = point.loadedWorld();

            if (world == null) {
                iceQueue.addLast(point);
                continue;
            }

            Block block = world.getBlockAt(
                    point.x, point.y, point.z
            );

            if (block.getType() != Material.ICE) {
                ice.remove(point);
                continue;
            }

            if (!enabled() || warm(block)) {
                block.setBlockData(
                        Bukkit.createBlockData(original), false
                );

                ice.remove(point);
            } else {
                iceQueue.addLast(point);
            }
        }
    }

    private void particles() {
        int total = integer(
                "snow.particles-per-step", 0, 500
        );

        if (total == 0) {
            return;
        }

        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();

            if (!normal(world)) {
                continue;
            }

            Location origin = player.getLocation();

            // Несколько небольших облаков частиц вместо
            // одного редкого большого облака.
            int groups = Math.max(1, (total + 4) / 5);
            int remaining = total;

            for (int i = 0; i < groups; i++) {
                int amount = Math.min(5, remaining);
                remaining -= amount;

                double x = origin.getX()
                        + random.nextDouble(-14.0, 14.0);
                double z = origin.getZ()
                        + random.nextDouble(-14.0, 14.0);
                double y = origin.getY()
                        + random.nextDouble(2.0, 12.0);

                int bx = (int) Math.floor(x);
                int bz = (int) Math.floor(z);

                if (y >= world.getMaxHeight()
                        || y < world.getMinHeight()
                        || !world.isChunkLoaded(bx >> 4, bz >> 4)) {
                    continue;
                }

                int roof = world.getHighestBlockYAt(
                        bx, bz, HeightMap.WORLD_SURFACE
                );

                if (roof >= y) {
                    continue;
                }

                player.spawnParticle(
                        Particle.SNOWFLAKE,
                        x, y, z,
                        amount,
                        0.8, 0.8, 0.8,
                        0.025
                );
            }
        }
    }

    private String heatGroup(Material material) {
        return switch (material) {
            case TORCH, WALL_TORCH -> "torch";
            case LANTERN -> "lantern";
            case FIRE -> "fire";
            case CAMPFIRE -> "campfire";
            case LAVA -> "lava";
            case MAGMA_BLOCK -> "magma";
            case FURNACE, BLAST_FURNACE, SMOKER -> "furnace";

            case SOUL_TORCH, SOUL_WALL_TORCH, SOUL_LANTERN,
                 SOUL_FIRE, SOUL_CAMPFIRE -> "soul";

            default -> {
                String name = material.name();

                yield name.equals("CANDLE")
                        || name.endsWith("_CANDLE")
                        || name.endsWith("CANDLE_CAKE")
                        ? "candle" : null;
            }
        };
    }

    private void rebuildHeat() {
        heat.clear();
        maxHeatRadius = 0;

        for (Material material : Material.values()) {
            if (!material.isBlock()) {
                continue;
            }

            String group = heatGroup(material);

            if (group == null) {
                continue;
            }

            int radius = integer("heat." + group, 0, 8);

            heat.put(material, radius);
            maxHeatRadius = Math.max(maxHeatRadius, radius);
        }
    }

    private boolean warm(Block target) {
        World world = target.getWorld();
        int radius = maxHeatRadius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = target.getX() + dx;
                int z = target.getZ() + dz;

                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }

                for (int dy = -radius; dy <= radius; dy++) {
                    int distance = dx * dx + dy * dy + dz * dz;

                    if (distance > radius * radius) {
                        continue;
                    }

                    int y = target.getY() + dy;

                    if (y < world.getMinHeight()
                            || y >= world.getMaxHeight()) {
                        continue;
                    }

                    Block source = world.getBlockAt(x, y, z);
                    Integer heatRadius = heat.get(source.getType());

                    if (heatRadius == null
                            || heatRadius <= 0
                            || distance > heatRadius * heatRadius) {
                        continue;
                    }

                    if (source.getBlockData() instanceof Lightable lit
                            && !lit.isLit()) {
                        continue;
                    }

                    return true;
                }
            }
        }

        return false;
    }

    private boolean winterMob(LivingEntity entity) {
        return entity instanceof Stray
                || entity instanceof PolarBear
                || entity instanceof Snowman
                || entity instanceof Creeper
                || (entity instanceof Fox fox
                    && fox.getFoxType() == Fox.Type.SNOW);
    }

    private boolean hostile(LivingEntity entity) {
        return entity instanceof Monster
                || entity instanceof Slime
                || entity instanceof Ghast
                || entity instanceof Phantom;
    }

    private void updateCold(long now) {
        Set<UUID> present = new HashSet<>();

        long grace = milliseconds("cold.grace-seconds", 0.0);
        long interval = milliseconds(
                "cold.damage-interval-seconds", 0.5
        );

        double damage = Math.max(
                0.0,
                Math.min(20.0,
                        plugin.getConfig().getDouble("cold.damage"))
        );

        for (World world : Bukkit.getWorlds()) {
            if (!normal(world)) {
                continue;
            }

            for (LivingEntity entity : world.getLivingEntities()) {
                if (!entity.isValid()
                        || entity.isDead()
                        || winterMob(entity)
                        || entity instanceof ArmorStand
                        || entity instanceof Wither
                        || entity instanceof EnderDragon) {
                    continue;
                }

                String depth;

                if (entity instanceof Player player) {
                    if (player.getGameMode() == GameMode.CREATIVE
                            || player.getGameMode() == GameMode.SPECTATOR) {
                        continue;
                    }

                    depth = "cold.players-y";
                } else if (hostile(entity)) {
                    depth = "cold.hostile-y";
                } else if (entity instanceof Animals
                        || entity instanceof WaterMob
                        || entity instanceof Ambient) {
                    depth = "cold.passive-y";
                } else {
                    continue;
                }

                UUID id = entity.getUniqueId();
                present.add(id);

                boolean safe = entity.getLocation().getY()
                        < plugin.getConfig().getInt(depth)
                        || warm(entity.getLocation().getBlock());

                if (safe) {
                    coldSince.remove(id);
                    lastDamage.remove(id);
                    continue;
                }

                long start = coldSince.computeIfAbsent(
                        id, ignored -> now
                );

                long exposed = now - start;

                if (entity instanceof Player player) {
                    if (exposed < grace) {
                        long seconds = (grace - exposed + 999L) / 1000L;

                        player.sendActionBar(
                                "§bХолодно! Найди тепло или спустись ниже. §f"
                                        + seconds + " сек."
                        );
                    } else {
                        player.sendActionBar(
                                "§cТы замерзаешь! Нужны тепло или глубина."
                        );
                    }
                }

                if (damage > 0
                        && exposed >= grace
                        && now - lastDamage.getOrDefault(id, 0L)
                            >= interval) {
                    entity.damage(damage);
                    lastDamage.put(id, now);
                }
            }
        }

        coldSince.keySet().retainAll(present);
        lastDamage.keySet().retainAll(present);
    }

    private File stateFile() {
        return new File(plugin.getDataFolder(), "winter-state.yml");
    }

    private void saveState() {
        YamlConfiguration yaml = new YamlConfiguration();

        List<Map<String, Object>> snowRows = new ArrayList<>();

        for (Cover cover : covers.values()) {
            Map<String, Object> row = new LinkedHashMap<>();

            row.put("world", cover.key.world.toString());
            row.put("x", cover.key.x);
            row.put("z", cover.key.z);
            row.put("base-y", cover.baseY);
            row.put("height", cover.height);
            row.put("layers", cover.layers);
            row.put("visual", cover.visual);
            row.put("requested", cover.requested);

            snowRows.add(row);
        }

        List<Map<String, Object>> iceRows = new ArrayList<>();

        for (var entry : ice.entrySet()) {
            Point point = entry.getKey();
            Map<String, Object> row = new LinkedHashMap<>();

            row.put("world", point.world.toString());
            row.put("x", point.x);
            row.put("y", point.y);
            row.put("z", point.z);
            row.put("original", entry.getValue());

            iceRows.add(row);
        }

        yaml.set("snow", snowRows);
        yaml.set("ice", iceRows);

        try {
            yaml.save(stateFile());
        } catch (IOException exception) {
            plugin.getLogger().severe(
                    "Не удалось сохранить состояние зимы: "
                            + exception.getMessage()
            );
        }
    }

    private int rowInt(Map<?, ?> row, String key) {
        return ((Number) row.get(key)).intValue();
    }

    private void loadState() {
        YamlConfiguration yaml =
                YamlConfiguration.loadConfiguration(stateFile());

        for (Map<?, ?> row : yaml.getMapList("snow")) {
            try {
                Column column = new Column(
                        UUID.fromString(row.get("world").toString()),
                        rowInt(row, "x"),
                        rowInt(row, "z")
                );

                Cover cover = new Cover(
                        column,
                        rowInt(row, "base-y"),
                        ((Number) row.get("height")).doubleValue(),
                        Boolean.TRUE.equals(row.get("visual"))
                );

                cover.layers = Math.max(
                        1, Math.min(64, rowInt(row, "layers"))
                );

                cover.requested = row.containsKey("requested")
                        ? Math.max(0, Math.min(
                            64, rowInt(row, "requested")))
                        : 0;

                covers.put(column, cover);
                coverQueue.addLast(column);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning(
                        "Пропущена некорректная запись снега."
                );
            }
        }

        for (Map<?, ?> row : yaml.getMapList("ice")) {
            try {
                Point point = new Point(
                        UUID.fromString(row.get("world").toString()),
                        rowInt(row, "x"),
                        rowInt(row, "y"),
                        rowInt(row, "z")
                );

                String original = row.get("original").toString();
                Bukkit.createBlockData(original);

                ice.put(point, original);
                iceQueue.addLast(point);
            } catch (RuntimeException exception) {
                plugin.getLogger().warning(
                        "Пропущена некорректная запись льда."
                );
            }
        }
    }

    public void close() {
        task.cancel();
        saveState();

        for (Cover cover : covers.values()) {
            if (cover.display != null && cover.display.isValid()) {
                cover.display.remove();
            }
        }
    }

    private record Column(UUID world, int x, int z) {
        World loadedWorld() {
            World result = Bukkit.getWorld(world);

            return result != null
                    && result.isChunkLoaded(x >> 4, z >> 4)
                    ? result : null;
        }
    }

    private record Point(UUID world, int x, int y, int z) {
        World loadedWorld() {
            World result = Bukkit.getWorld(world);

            return result != null
                    && result.isChunkLoaded(x >> 4, z >> 4)
                    ? result : null;
        }
    }

    private record ChunkKey(UUID world, int x, int z) {
        World loadedWorld() {
            World result = Bukkit.getWorld(world);

            return result != null && result.isChunkLoaded(x, z)
                    ? result : null;
        }
    }

    private static final class Cursor {
        final ChunkKey key;
        int column;

        Cursor(ChunkKey key) {
            this.key = key;
            this.column = Math.floorMod(key.hashCode(), 256);
        }
    }

    private static final class Cover {
        final Column key;
        final int baseY;
        final double height;
        final boolean visual;

        int layers;
        int requested;

        long nextUpdate;
        BlockDisplay display;

        Cover(
                Column key,
                int baseY,
                double height,
                boolean visual
        ) {
            this.key = key;
            this.baseY = baseY;
            this.height = height;
            this.visual = visual;
        }
    }
}
