package ru.wintercontrol;

import org.bukkit.*;
import org.bukkit.block.Biome;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
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
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public final class WinterClimate {

    private final WinterControl plugin;
    private final BukkitTask task;

    private final Map<ChunkKey, Cursor> cursors = new LinkedHashMap<>();
    private final Map<ChunkKey, Boolean> biomeModes = new HashMap<>();

    private List<Cursor> loaded = new ArrayList<>();

    private final Map<Point, Cover> covers = new LinkedHashMap<>();
    private final ArrayDeque<Point> coverQueue = new ArrayDeque<>();

    private final Map<Point, String> ice = new LinkedHashMap<>();
    private final ArrayDeque<Point> iceQueue = new ArrayDeque<>();

    private final Map<ChunkKey, Integer> manual = new HashMap<>();
    private final Map<UUID, Long> coldSince = new HashMap<>();
    private final Map<UUID, Long> lastDamage = new HashMap<>();

    private final Map<Material, Integer> heat = new EnumMap<>(Material.class);
    private final Map<Point, Boolean> warmthCache = new HashMap<>();

    private final Map<UUID, Weather> weatherBackups = new HashMap<>();

    private int snowIndex;
    private int waterIndex;
    private int biomeIndex;
    private int pulse;
    private int maxHeat;

    private long stormUntil;
    private long nextStorm;

    private boolean previousWinter;

    public WinterClimate(WinterControl plugin) {
        this.plugin = plugin;

        defaults();
        rebuildHeat();
        loadState();
        refreshChunks();

        task = Bukkit.getScheduler().runTaskTimer(
                plugin, this::tick, 2L, 2L
        );
    }

    private void defaults() {
        var c = plugin.getConfig();
        int y = c.getInt("winter.cold-y", 50);

        c.addDefault("cold.players-y", y);
        c.addDefault("cold.passive-y", y);
        c.addDefault("cold.hostile-y", y);
        c.addDefault("cold.grace-seconds", 15);
        c.addDefault("cold.damage", 1.0);
        c.addDefault("cold.damage-interval-seconds", 2);
        c.addDefault("cold.night-enabled", true);
        c.addDefault("cold.night-multiplier", 2.0);

        c.addDefault("snow.interval-seconds", 90);
        c.addDefault("snow.duration-seconds", 45);
        c.addDefault("snow.grow-seconds", 4.0);
        c.addDefault("snow.melt-seconds", 0.6);
        c.addDefault("snow.paused", false);
        c.addDefault("snow.particles-per-step", 120);

        c.addDefault("weather.real-snow", true);
        c.addDefault("weather.dark-sky", true);

        // Новые названия: старые бюджеты не замедляют обновление.
        c.addDefault("performance.snow-columns", 64);
        c.addDefault("performance.water-columns", 192);
        c.addDefault("performance.cover-updates", 192);
        c.addDefault("performance.ice-updates", 96);

        c.options().copyDefaults(true);
        plugin.saveConfig();
    }

    private boolean enabled() {
        return plugin.getConfig().getBoolean("winter.enabled");
    }

    private boolean normal(World world) {
        return world.getEnvironment() == World.Environment.NORMAL;
    }

    private boolean realWeather() {
        return enabled()
                && plugin.getConfig().getBoolean("weather.real-snow");
    }

    private int number(String path, int min, int max) {
        return Math.max(min, Math.min(
                max, plugin.getConfig().getInt(path)
        ));
    }

    private long delay(String path, double min) {
        double value = plugin.getConfig().getDouble(path);
        if (!Double.isFinite(value)) {
            value = min;
        }
        return (long) (Math.max(min, value) * 1000.0);
    }

    private void tick() {
        pulse++;

        if (pulse % 300 == 0) {
            saveState();
        }

        if (plugin.getServer().getServerTickManager().isFrozen()) {
            return;
        }

        long now = System.currentTimeMillis();

        if (previousWinter != enabled()) {
            previousWinter = enabled();
            stormUntil = 0L;
            nextStorm = 0L;
            manual.clear();
            coldSince.clear();
            lastDamage.clear();

            for (Cover cover : covers.values()) {
                cover.requested = 0;
                cover.nextUpdate = 0L;
            }
        }

        if (pulse % 10 == 0) {
            warmthCache.clear();
            rebuildHeat();

            if (enabled()) {
                updateCold(now);
            }
        }

        if (pulse % 20 == 0) {
            refreshChunks();
        }

        updateStorm(now);
        updateOneBiomeChunk();

        if (pulse % 10 == 0 || pulse == 1) {
            updateWeather(now);
        }

        scanColumns(
                number("performance.water-columns", 16, 1024),
                true, now
        );

        if (enabled() && (snowing(now) || !manual.isEmpty())) {
            scanColumns(
                    number("performance.snow-columns", 1, 512),
                    false, now
            );
        }

        updateCovers(
                now, number("performance.cover-updates", 1, 2048)
        );

        updateIce(
                number("performance.ice-updates", 1, 1024)
        );

        if (snowing(now) || !manual.isEmpty()) {
            particles();
        }
    }

    // ========================
    // Общая очередь чанков
    // ========================

    private void refreshChunks() {
        Set<ChunkKey> present = new HashSet<>();

        for (World world : Bukkit.getWorlds()) {
            if (!normal(world)) {
                continue;
            }

            for (Chunk chunk : world.getLoadedChunks()) {
                ChunkKey key = new ChunkKey(
                        world.getUID(), chunk.getX(), chunk.getZ()
                );

                present.add(key);
                cursors.computeIfAbsent(key, Cursor::new);
            }
        }

        cursors.keySet().removeIf(key -> !present.contains(key));
        manual.keySet().removeIf(key -> !present.contains(key));

        loaded = new ArrayList<>(cursors.values());
    }

    private void scanColumns(int budget, boolean water, long now) {
        if (loaded.isEmpty() || !enabled()) {
            return;
        }

        for (int i = 0; i < budget; i++) {
            int index = water ? waterIndex++ : snowIndex++;
            Cursor cursor = loaded.get(Math.floorMod(index, loaded.size()));

            World world = cursor.key.worldLoaded();

            if (world == null) {
                continue;
            }

            int local = water
                    ? cursor.water.next()
                    : cursor.snow.next();

            int x = cursor.key.x * 16 + (local & 15);
            int z = cursor.key.z * 16 + (local >> 4);

            if (water) {
                freezeWater(world, x, z);
            } else {
                int target = 0;
                Integer left = manual.get(cursor.key);

                if (left != null) {
                    target = number("snow.layers", 1, 64);

                    if (left <= 1) {
                        manual.remove(cursor.key);
                    } else {
                        manual.put(cursor.key, left - 1);
                    }
                }

                snowColumn(world, x, z, target, now);
            }
        }
    }

    // ========================
    // Снегопады
    // ========================

    private void updateStorm(long now) {
        if (!enabled()
                || plugin.getConfig().getBoolean("snow.paused")) {
            stormUntil = 0L;
            return;
        }

        if (plugin.getConfig().getBoolean("snow.long")) {
            return;
        }

        if (stormUntil != 0L && now >= stormUntil) {
            stormUntil = 0L;
            nextStorm = now + delay("snow.interval-seconds", 1);
        }

        if (stormUntil == 0L && now >= nextStorm) {
            stormUntil = now + delay("snow.duration-seconds", 1);
        }
    }

    private boolean snowing(long now) {
        return enabled()
                && !plugin.getConfig().getBoolean("snow.paused")
                && (plugin.getConfig().getBoolean("snow.long")
                    || now < stormUntil);
    }

    public void coverNow() {
        refreshChunks();
        manual.clear();

        for (Cursor cursor : loaded) {
            // Начинаем полный перемешанный проход без повторов.
            cursor.snow.reset();
            manual.put(cursor.key, 256);
        }

        plugin.getConfig().set("snow.paused", false);
    }

    public void stopSnow() {
        manual.clear();
        stormUntil = 0L;

        plugin.getConfig().set("snow.long", false);
        plugin.getConfig().set("snow.paused", true);

        for (Cover cover : covers.values()) {
            cover.requested = 0;
        }
    }

    private boolean desert(Block block) {
        return block.getBiome() == Biome.DESERT;
    }

    private void freezeWater(World world, int x, int z) {
        int y = world.getHighestBlockYAt(
                x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES
        );

        // Пропускаем проходимые растения над поверхностью.
        while (y > world.getMinHeight()) {
            Block block = world.getBlockAt(x, y, z);

            if (block.getType() == Material.WATER) {
                if (!desert(block) && !warm(block)) {
                    Point point = new Point(world.getUID(), x, y, z);

                    if (!ice.containsKey(point)) {
                        ice.put(point, block.getBlockData().getAsString());
                        iceQueue.addLast(point);
                    }

                    block.setType(Material.ICE, false);
                }

                return;
            }

            if (!block.isPassable()
                    && !Tag.LEAVES.isTagged(block.getType())) {
                return;
            }

            y--;
        }
    }

    private void snowColumn(
            World world, int x, int z, int requested, long now
    ) {
        int highest = world.getHighestBlockYAt(
                x, z, HeightMap.WORLD_SURFACE
        );

        boolean canopyFound = false;

        for (int y = highest; y > world.getMinHeight(); y--) {
            Block block = world.getBlockAt(x, y, z);
            Material type = block.getType();

            if (type == Material.SNOW || type == Material.SNOW_BLOCK) {
                // Проходим собственное накопленное покрытие.
                continue;
            }

            if (Tag.LEAVES.isTagged(type)) {
                if (!canopyFound) {
                    addSurface(block, requested, now);
                    canopyFound = true;
                }

                // Продолжаем вниз: ищем землю под кроной.
                continue;
            }

            if (block.isLiquid()) {
                return;
            }

            if (block.isPassable()) {
                continue;
            }

            addSurface(block, requested, now);
            return;
        }
    }

    private void addSurface(Block base, int requested, long now) {
        if (base.getY() >= base.getWorld().getMaxHeight() - 1
                || desert(base)) {
            return;
        }

        Point point = Point.of(base);
        Cover existing = covers.get(point);

        if (existing != null) {
            existing.requested = Math.max(existing.requested, requested);
            return;
        }

        if (warm(base)) {
            return;
        }

        Block above = base.getRelative(0, 1, 0);

        // Не присваиваем себе естественный снег.
        if (above.getType() == Material.SNOW
                || above.getType() == Material.SNOW_BLOCK) {
            return;
        }

        if (above.isLiquid()
                || (!above.getType().isAir() && !above.isPassable())) {
            return;
        }

        BlockData snow = Material.SNOW.createBlockData();

        boolean physical = above.getType().isAir()
                && above.canPlace(snow);

        double height = base.getBoundingBox().getMaxY();

        if (height <= base.getY()) {
            return;
        }

        Cover cover = new Cover(point, height, !physical);
        cover.requested = requested;

        if (render(cover, 1)) {
            cover.layers = 1;
            cover.nextUpdate = now + delay("snow.grow-seconds", 0.2);

            covers.put(point, cover);
            coverQueue.addLast(point);
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
        World world = cover.point.worldLoaded();

        if (world == null) {
            return false;
        }

        if (cover.visual) {
            if (layers <= 0) {
                removeDisplay(cover);
                return true;
            }

            if (cover.display == null || !cover.display.isValid()) {
                cover.display = world.spawn(
                        new Location(
                                world,
                                cover.point.x,
                                cover.height,
                                cover.point.z
                        ),
                        BlockDisplay.class,
                        display -> {
                            display.setBlock(
                                    Material.SNOW_BLOCK.createBlockData()
                            );
                            display.setPersistent(false);
                            display.setInterpolationDuration(6);
                            display.setViewRange(1.5f);
                        }
                );
            }

            cover.display.setInterpolationDelay(0);
            cover.display.setTransformation(new Transformation(
                    new Vector3f(),
                    new Quaternionf(),
                    new Vector3f(1, layers / 8.0f, 1),
                    new Quaternionf()
            ));

            return true;
        }

        int blocks = Math.max(
                (cover.layers + 7) / 8, (layers + 7) / 8
        );

        if (cover.point.y + blocks >= world.getMaxHeight()) {
            return false;
        }

        // Никогда не перезаписываем чужие новые блоки.
        for (int level = 0; level < blocks; level++) {
            Block block = world.getBlockAt(
                    cover.point.x,
                    cover.point.y + 1 + level,
                    cover.point.z
            );

            if (!block.getBlockData().getAsString().equals(
                    layerData(cover.layers, level).getAsString())) {
                return false;
            }
        }

        for (int level = 0; level < blocks; level++) {
            world.getBlockAt(
                    cover.point.x,
                    cover.point.y + 1 + level,
                    cover.point.z
            ).setBlockData(layerData(layers, level), false);
        }

        return true;
    }

    private void removeDisplay(Cover cover) {
        if (cover.display != null) {
            cover.display.remove();
            cover.display = null;
        }
    }

    private void updateCovers(long now, int budget) {
        int count = Math.min(budget, coverQueue.size());

        for (int i = 0; i < count; i++) {
            Point point = coverQueue.removeFirst();
            Cover cover = covers.get(point);

            if (cover == null) {
                continue;
            }

            World world = point.worldLoaded();

            if (world == null) {
                coverQueue.addLast(point);
                continue;
            }

            if (cover.visual
                    && (cover.display == null
                        || !cover.display.isValid())) {
                render(cover, cover.layers);
            }

            if (now >= cover.nextUpdate) {
                Block base = world.getBlockAt(point.x, point.y, point.z);

                boolean melt = !enabled()
                        || base.getType().isAir()
                        || base.isLiquid()
                        || desert(base)
                        || warm(base);

                int target = cover.requested;

                if (snowing(now)) {
                    target = Math.max(
                            target, number("snow.max-layers", 1, 64)
                    );
                }

                int next = cover.layers;

                if (melt) {
                    next--;
                } else if (next < target) {
                    next++;
                }

                if (next != cover.layers && render(cover, next)) {
                    cover.layers = next;
                }

                cover.nextUpdate = now + delay(
                        melt ? "snow.melt-seconds" : "snow.grow-seconds",
                        0.1
                );
            }

            if (cover.layers <= 0) {
                removeDisplay(cover);
                covers.remove(point);
            } else {
                coverQueue.addLast(point);
            }
        }
    }

    private void updateIce(int budget) {
        int count = Math.min(budget, iceQueue.size());

        for (int i = 0; i < count; i++) {
            Point point = iceQueue.removeFirst();
            String original = ice.get(point);

            if (original == null) {
                continue;
            }

            World world = point.worldLoaded();

            if (world == null) {
                iceQueue.addLast(point);
                continue;
            }

            Block block = world.getBlockAt(point.x, point.y, point.z);

            if (block.getType() != Material.ICE) {
                ice.remove(point);
            } else if (!enabled() || desert(block) || warm(block)) {
                block.setBlockData(
                        Bukkit.createBlockData(original), false
                );
                ice.remove(point);
            } else {
                iceQueue.addLast(point);
            }
        }
    }

    // ========================
    // Биомы: резервирование
    // и обратное восстановление
    // ========================

    private File biomeFile(ChunkKey key) {
        File directory = new File(
                plugin.getDataFolder(), "biome-backups"
        );

        return new File(
                directory,
                key.world + "_" + key.x + "_" + key.z + ".yml"
        );
    }

    private void updateOneBiomeChunk() {
        if (loaded.isEmpty()) {
            return;
        }

        boolean winterBiomes = realWeather();

        // Не больше одного изменяемого чанка за проход.
        for (int tries = 0; tries < loaded.size(); tries++) {
            Cursor cursor = loaded.get(
                    Math.floorMod(biomeIndex++, loaded.size())
            );

            ChunkKey key = cursor.key;

            if (Objects.equals(biomeModes.get(key), winterBiomes)) {
                continue;
            }

            World world = key.worldLoaded();

            if (world == null) {
                continue;
            }

            try {
                applyBiomes(key, world, winterBiomes);
            } catch (Exception exception) {
                plugin.getLogger().severe(
                        "Не удалось изменить или восстановить биомы "
                                + key.x + ", " + key.z + ": "
                                + exception.getMessage()
                );
            }

            // При ошибке не повторяем дисковую операцию каждый тик.
            // Повторная попытка будет при переключении режима
            // или после перезапуска.
            biomeModes.put(key, winterBiomes);
            return;
        }
    }

    private void applyBiomes(
            ChunkKey key, World world, boolean winterBiomes
    ) throws Exception {
        File file = biomeFile(key);

        if (!winterBiomes && !file.exists()) {
            return;
        }

        int min = world.getMinHeight();
        int max = world.getMaxHeight();

        if (!file.exists()) {
            YamlConfiguration backup = new YamlConfiguration();
            List<String> original = new ArrayList<>();

            // Биомы Minecraft хранятся ячейками 4×4×4.
            for (int y = min; y < max; y += 4) {
                for (int x = 0; x < 16; x += 4) {
                    for (int z = 0; z < 16; z += 4) {
                        Biome biome = world.getBiome(
                                key.x * 16 + x, y, key.z * 16 + z
                        );

                        original.add(biome.getKey().toString());
                    }
                }
            }

            backup.set("min-y", min);
            backup.set("max-y", max);
            backup.set("biomes", original);

            // Сначала успешная запись на диск,
            // только затем меняем мир.
            atomicSave(backup, file);
        }

        YamlConfiguration backup = new YamlConfiguration();
        backup.load(file);

        if (backup.getInt("min-y") != min
                || backup.getInt("max-y") != max) {
            throw new IOException(
                    "Высота мира отличается от высоты резервной копии"
            );
        }

        List<String> names = backup.getStringList("biomes");
        int expected = ((max - min) / 4) * 16;

        if (names.size() != expected) {
            throw new IOException("Неполная резервная копия биомов");
        }

        // Проверяем все названия до первого изменения мира.
        List<Biome> originals = new ArrayList<>(names.size());

        for (String name : names) {
            NamespacedKey biomeKey = NamespacedKey.fromString(name);
            Biome biome = biomeKey == null
                    ? null
                    : Registry.BIOME.get(biomeKey);

            if (biome == null) {
                throw new IOException(
                        "Исходный биом недоступен: " + name
                );
            }

            originals.add(biome);
        }

        int index = 0;
        boolean changed = false;

        for (int y = min; y < max; y += 4) {
            for (int x = 0; x < 16; x += 4) {
                for (int z = 0; z < 16; z += 4) {
                    Biome original = originals.get(index++);

                    Biome target = winterBiomes
                            && original != Biome.DESERT
                            ? Biome.SNOWY_PLAINS
                            : original;

                    int bx = key.x * 16 + x;
                    int bz = key.z * 16 + z;

                    if (world.getBiome(bx, y, bz) != target) {
                        world.setBiome(bx, y, bz, target);
                        changed = true;
                    }
                }
            }
        }

        if (changed) {
            world.refreshChunk(key.x, key.z);
        }

        // Копию не удаляем: она нужна и для следующей зимы,
        // и для повторного восстановления после сбоя сервера.
    }

    private void atomicSave(
            YamlConfiguration yaml, File destination
    ) throws IOException {
        Path parent = destination.toPath().getParent();
        Files.createDirectories(parent);

        Path temporary = Files.createTempFile(
                parent, "winter-", ".tmp"
        );

        try {
            yaml.save(temporary.toFile());

            try {
                Files.move(
                        temporary,
                        destination.toPath(),
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporary,
                        destination.toPath(),
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    // ========================
    // Настоящая погода
    // ========================

    private void updateWeather(long now) {
        boolean manage = realWeather();
        boolean precipitation = snowing(now) || !manual.isEmpty();

        for (World world : Bukkit.getWorlds()) {
            if (!normal(world)) {
                continue;
            }

            UUID id = world.getUID();

            if (manage) {
                if (!weatherBackups.containsKey(id)) {
                    weatherBackups.put(id, new Weather(
                            world.hasStorm(),
                            world.isThundering(),
                            world.getWeatherDuration(),
                            world.getThunderDuration(),
                            world.getClearWeatherDuration()
                    ));

                    saveState();
                }

                world.setClearWeatherDuration(0);
                world.setStorm(precipitation);

                // Грозовой уровень затемнения.
                // Удары молний запрещает обработчик события.
                world.setThundering(
                        precipitation
                                && plugin.getConfig().getBoolean(
                                        "weather.dark-sky"
                                )
                );

                world.setWeatherDuration(200);
                world.setThunderDuration(200);
            } else {
                Weather original = weatherBackups.remove(id);

                if (original != null) {
                    world.setStorm(original.storm);
                    world.setThundering(original.thunder);
                    world.setWeatherDuration(original.weatherTicks);
                    world.setThunderDuration(original.thunderTicks);
                    world.setClearWeatherDuration(original.clearTicks);
                    saveState();
                }
            }
        }
    }

    public boolean blocksLightning(World world) {
        return normal(world)
                && realWeather()
                && plugin.getConfig().getBoolean("weather.dark-sky")
                && (snowing(System.currentTimeMillis())
                    || !manual.isEmpty());
    }

    // ========================
    // Частицы, включая пространство
    // под лиственной кроной
    // ========================

    private boolean roofAbove(World world, int x, int y, int z) {
        int highest = world.getHighestBlockYAt(
                x, z, HeightMap.WORLD_SURFACE
        );

        for (int yy = y; yy <= highest; yy++) {
            Block block = world.getBlockAt(x, yy, z);
            Material type = block.getType();

            if (Tag.LEAVES.isTagged(type)
                    || type == Material.SNOW
                    || type == Material.SNOW_BLOCK) {
                continue;
            }

            if (!block.isPassable()) {
                return true;
            }
        }

        return false;
    }

    private void particles() {
        int total = number("snow.particles-per-step", 0, 500);
        ThreadLocalRandom random = ThreadLocalRandom.current();

        for (Player player : Bukkit.getOnlinePlayers()) {
            World world = player.getWorld();

            if (!normal(world)) {
                continue;
            }

            Location location = player.getLocation();

            for (int left = total; left > 0; left -= 5) {
                double x = location.getX()
                        + random.nextDouble(-12, 12);
                double z = location.getZ()
                        + random.nextDouble(-12, 12);
                double y = location.getY()
                        + random.nextDouble(0.5, 10);

                int bx = (int) Math.floor(x);
                int by = (int) Math.floor(y);
                int bz = (int) Math.floor(z);

                if (by < world.getMinHeight()
                        || by >= world.getMaxHeight()
                        || !world.isChunkLoaded(bx >> 4, bz >> 4)) {
                    continue;
                }

                Block block = world.getBlockAt(bx, by, bz);

                if (desert(block)
                        || !block.isPassable()
                        || roofAbove(world, bx, by, bz)) {
                    continue;
                }

                player.spawnParticle(
                        Particle.SNOWFLAKE,
                        x, y, z,
                        Math.min(5, left),
                        0.7, 0.5, 0.7,
                        0.025
                );
            }
        }
    }

    // ========================
    // Тепло
    // ========================

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
        maxHeat = 0;

        for (Material material : Material.values()) {
            if (!material.isBlock()) {
                continue;
            }

            String group = heatGroup(material);

            if (group != null) {
                int radius = number("heat." + group, 0, 8);
                heat.put(material, radius);
                maxHeat = Math.max(maxHeat, radius);
            }
        }
    }

    private boolean warm(Block target) {
        Point point = Point.of(target);
        Boolean cached = warmthCache.get(point);

        if (cached != null) {
            return cached;
        }

        boolean result = findHeat(target);
        warmthCache.put(point, result);
        return result;
    }

    private boolean findHeat(Block target) {
        World world = target.getWorld();

        for (int dx = -maxHeat; dx <= maxHeat; dx++) {
            for (int dz = -maxHeat; dz <= maxHeat; dz++) {
                int x = target.getX() + dx;
                int z = target.getZ() + dz;

                if (!world.isChunkLoaded(x >> 4, z >> 4)) {
                    continue;
                }

                for (int dy = -maxHeat; dy <= maxHeat; dy++) {
                    int distance = dx * dx + dy * dy + dz * dz;

                    if (distance > maxHeat * maxHeat) {
                        continue;
                    }

                    int y = target.getY() + dy;

                    if (y < world.getMinHeight()
                            || y >= world.getMaxHeight()) {
                        continue;
                    }

                    Block source = world.getBlockAt(x, y, z);
                    Integer radius = heat.get(source.getType());

                    if (radius == null
                            || radius <= 0
                            || distance > radius * radius) {
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

    // ========================
    // Холод, глубина и ночь
    // ========================

    private boolean immune(LivingEntity entity) {
        return entity instanceof Stray
                || entity instanceof Creeper
                || entity instanceof PolarBear
                || entity instanceof Snowman
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

        long grace = delay("cold.grace-seconds", 0);
        long interval = delay("cold.damage-interval-seconds", 0.5);

        double basicDamage = Math.max(
                0, Math.min(20,
                        plugin.getConfig().getDouble("cold.damage"))
        );

        for (World world : Bukkit.getWorlds()) {
            if (!normal(world)) {
                continue;
            }

            long time = world.getTime();

            boolean night = time >= 13000 && time < 23000
                    && plugin.getConfig().getBoolean(
                            "cold.night-enabled"
                    );

            double multiplier = night
                    ? Math.max(1, Math.min(10,
                        plugin.getConfig().getDouble(
                                "cold.night-multiplier")))
                    : 1;

            for (LivingEntity entity : world.getLivingEntities()) {
                if (!entity.isValid() || entity.isDead() || immune(entity)) {
                    continue;
                }

                String path;

                if (entity instanceof Player player) {
                    if (player.getGameMode() == GameMode.CREATIVE
                            || player.getGameMode() == GameMode.SPECTATOR) {
                        continue;
                    }

                    path = "cold.players-y";
                } else if (hostile(entity)) {
                    path = "cold.hostile-y";
                } else if (entity instanceof Animals
                        || entity instanceof WaterMob
                        || entity instanceof Ambient) {
                    path = "cold.passive-y";
                } else {
                    continue;
                }

                UUID id = entity.getUniqueId();
                present.add(id);

                boolean safe = entity.getLocation().getY()
                        < plugin.getConfig().getInt(path)
                        || warm(entity.getLocation().getBlock());

                if (safe) {
                    coldSince.remove(id);
                    lastDamage.remove(id);
                    continue;
                }

                long start = coldSince.computeIfAbsent(
                        id, ignored -> now
                );

                long elapsed = now - start;

                if (entity instanceof Player player) {
                    String text = night
                            ? "§9Ночной мороз!"
                            : "§bХолодно!";

                    if (elapsed < grace) {
                        text += " §fДо урона: "
                                + ((grace - elapsed + 999) / 1000)
                                + " сек.";
                    } else {
                        text += " §cНайди тепло или спустись ниже!";
                    }

                    player.sendActionBar(text);
                }

                if (elapsed >= grace
                        && basicDamage > 0
                        && now - lastDamage.getOrDefault(id, 0L)
                            >= interval) {
                    entity.damage(basicDamage * multiplier);
                    lastDamage.put(id, now);
                }
            }
        }

        coldSince.keySet().retainAll(present);
        lastDamage.keySet().retainAll(present);
    }

    // ========================
    // Сохранение состояния
    // ========================

    private File stateFile() {
        return new File(plugin.getDataFolder(), "winter-state.yml");
    }

    private Map<String, Object> pointRow(Point point) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("world", point.world.toString());
        row.put("x", point.x);
        row.put("y", point.y);
        row.put("z", point.z);
        return row;
    }

    private void saveState() {
        YamlConfiguration yaml = new YamlConfiguration();

        List<Map<String, Object>> snowRows = new ArrayList<>();

        for (Cover cover : covers.values()) {
            Map<String, Object> row = pointRow(cover.point);

            // Совместимо с форматом предыдущей версии.
            row.put("base-y", cover.point.y);
            row.put("height", cover.height);
            row.put("layers", cover.layers);
            row.put("visual", cover.visual);
            row.put("requested", cover.requested);

            snowRows.add(row);
        }

        List<Map<String, Object>> iceRows = new ArrayList<>();

        for (var entry : ice.entrySet()) {
            Map<String, Object> row = pointRow(entry.getKey());
            row.put("original", entry.getValue());
            iceRows.add(row);
        }

        List<Map<String, Object>> weatherRows = new ArrayList<>();

        for (var entry : weatherBackups.entrySet()) {
            Weather weather = entry.getValue();
            Map<String, Object> row = new LinkedHashMap<>();

            row.put("world", entry.getKey().toString());
            row.put("storm", weather.storm);
            row.put("thunder", weather.thunder);
            row.put("weather-ticks", weather.weatherTicks);
            row.put("thunder-ticks", weather.thunderTicks);
            row.put("clear-ticks", weather.clearTicks);

            weatherRows.add(row);
        }

        yaml.set("snow", snowRows);
        yaml.set("ice", iceRows);
        yaml.set("weather", weatherRows);

        try {
            atomicSave(yaml, stateFile());
        } catch (IOException exception) {
            plugin.getLogger().severe(
                    "Не удалось сохранить состояние зимы: "
                            + exception.getMessage()
            );
        }
    }

    private int integer(Map<?, ?> row, String key) {
        return ((Number) row.get(key)).intValue();
    }

    private void loadState() {
        YamlConfiguration yaml = new YamlConfiguration();

        if (!stateFile().exists()) {
            return;
        }

        try {
            yaml.load(stateFile());
        } catch (Exception exception) {
            // Не продолжаем с пустым состоянием поверх
            // потенциально повреждённого файла.
            throw new IllegalStateException(
                    "Не удалось прочитать winter-state.yml. "
                            + "Сохрани копию файла и проверь журнал сервера.",
                    exception
            );
        }

        for (Map<?, ?> row : yaml.getMapList("snow")) {
            Point point = new Point(
                    UUID.fromString(row.get("world").toString()),
                    integer(row, "x"),
                    integer(row, "base-y"),
                    integer(row, "z")
            );

            Cover cover = new Cover(
                    point,
                    ((Number) row.get("height")).doubleValue(),
                    Boolean.TRUE.equals(row.get("visual"))
            );

            cover.layers = Math.max(
                    1, Math.min(64, integer(row, "layers"))
            );

            cover.requested = row.containsKey("requested")
                    ? Math.max(0, Math.min(
                        64, integer(row, "requested")))
                    : 0;

            covers.put(point, cover);
            coverQueue.addLast(point);
        }

        for (Map<?, ?> row : yaml.getMapList("ice")) {
            Point point = new Point(
                    UUID.fromString(row.get("world").toString()),
                    integer(row, "x"),
                    integer(row, "y"),
                    integer(row, "z")
            );

            String original = row.get("original").toString();
            Bukkit.createBlockData(original);

            ice.put(point, original);
            iceQueue.addLast(point);
        }

        for (Map<?, ?> row : yaml.getMapList("weather")) {
            UUID world = UUID.fromString(row.get("world").toString());

            weatherBackups.put(world, new Weather(
                    Boolean.TRUE.equals(row.get("storm")),
                    Boolean.TRUE.equals(row.get("thunder")),
                    integer(row, "weather-ticks"),
                    integer(row, "thunder-ticks"),
                    integer(row, "clear-ticks")
            ));
        }
    }

    public void close() {
        task.cancel();
        saveState();

        for (Cover cover : covers.values()) {
            removeDisplay(cover);
        }
    }

    // ========================
    // Вспомогательные типы
    // ========================

    private record Point(UUID world, int x, int y, int z) {
        static Point of(Block block) {
            return new Point(
                    block.getWorld().getUID(),
                    block.getX(),
                    block.getY(),
                    block.getZ()
            );
        }

        World worldLoaded() {
            World result = Bukkit.getWorld(world);

            return result != null
                    && result.isChunkLoaded(x >> 4, z >> 4)
                    ? result : null;
        }
    }

    private record ChunkKey(UUID world, int x, int z) {
        World worldLoaded() {
            World result = Bukkit.getWorld(world);

            return result != null && result.isChunkLoaded(x, z)
                    ? result : null;
        }
    }

    private static final class ShuffledColumns {
        private final int[] order = new int[256];
        private int position = 256;

        int next() {
            if (position >= 256) {
                reset();
            }

            return order[position++];
        }

        void reset() {
            for (int i = 0; i < 256; i++) {
                order[i] = i;
            }

            ThreadLocalRandom random = ThreadLocalRandom.current();

            for (int i = 255; i > 0; i--) {
                int other = random.nextInt(i + 1);
                int value = order[i];
                order[i] = order[other];
                order[other] = value;
            }

            position = 0;
        }
    }

    private static final class Cursor {
        final ChunkKey key;
        final ShuffledColumns snow = new ShuffledColumns();
        final ShuffledColumns water = new ShuffledColumns();

        Cursor(ChunkKey key) {
            this.key = key;
        }
    }

    private static final class Cover {
        final Point point;
        final double height;
        final boolean visual;

        int layers;
        int requested;
        long nextUpdate;
        BlockDisplay display;

        Cover(Point point, double height, boolean visual) {
            this.point = point;
            this.height = height;
            this.visual = visual;
        }
    }

    private record Weather(
            boolean storm,
            boolean thunder,
            int weatherTicks,
            int thunderTicks,
            int clearTicks
    ) { }
}
