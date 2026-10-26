package ru.wintercontrol;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.Levelled;
import org.bukkit.block.data.Lightable;
import org.bukkit.block.data.type.Snow;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class WinterControl extends JavaPlugin
        implements Listener, CommandExecutor {

    private static final String PREFIX = "§b[Зима] §f";

    private final Map<Material, Integer> heat = new EnumMap<>(Material.class);

    private final Deque<ChunkTask> regularQueue = new ArrayDeque<>();
    private final Deque<ChunkTask> snowQueue = new ArrayDeque<>();

    private boolean ownsFreeze;
    private WinterClimate climate;            
    private long thawDeadline;
    private long nextFreezeDeadline;
    private int maxHeatRadius;

    private static final List<HeatSetting> HEAT_SETTINGS = List.of(
            new HeatSetting("torch", Material.TORCH, "Факелы"),
            new HeatSetting("lantern", Material.LANTERN, "Фонари"),
            new HeatSetting("fire", Material.FIRE_CHARGE, "Огонь"),
            new HeatSetting("campfire", Material.CAMPFIRE, "Костры"),
            new HeatSetting("lava", Material.LAVA_BUCKET, "Лава"),
            new HeatSetting("magma", Material.MAGMA_BLOCK, "Магмовые блоки"),
            new HeatSetting("furnace", Material.FURNACE, "Работающие печи"),
            new HeatSetting("candle", Material.CANDLE, "Свечи"),
            new HeatSetting("soul", Material.SOUL_TORCH, "Огонь душ")
    );

    @Override
    public void onEnable() {
        setupConfig();
        rebuildHeat();

        Objects.requireNonNull(getCommand("winter")).setExecutor(this);
        getServer().getPluginManager().registerEvents(this, this);

        resetFreezeTimer();

        getServer().getScheduler().runTaskTimer(
                this, this::heartbeat, 20L, 20L
        );

        getLogger().info("WinterControl включён.");
    }

    @Override
    public void onDisable() {
        // Не снимаем чужую остановку тиков.
        if (ownsFreeze && ticks().isFrozen()) {
            ticks().setFrozen(false);
        }

        saveConfig();
    }

    private ServerTickManager ticks() {
        return getServer().getServerTickManager();
    }

    private void setupConfig() {
        getConfig().addDefault("winter.enabled", false);
        getConfig().addDefault("winter.cold-y", 50);
        getConfig().addDefault("winter.animal-damage", 2.0);

        getConfig().addDefault("freeze.auto", false);
        getConfig().addDefault("freeze.interval-seconds", 300);
        getConfig().addDefault("freeze.duration-seconds", 10);

        getConfig().addDefault("snow.layers", 8);
        getConfig().addDefault("snow.long", false);
        getConfig().addDefault("snow.max-layers", 32);

        getConfig().addDefault("performance.columns-per-second", 24);

        getConfig().addDefault("heat.torch", 3);
        getConfig().addDefault("heat.lantern", 3);
        getConfig().addDefault("heat.fire", 4);
        getConfig().addDefault("heat.campfire", 4);
        getConfig().addDefault("heat.lava", 5);
        getConfig().addDefault("heat.magma", 2);
        getConfig().addDefault("heat.furnace", 3);
        getConfig().addDefault("heat.candle", 1);
        getConfig().addDefault("heat.soul", 1);

        getConfig().options().copyDefaults(true);
        saveConfig();
    }

    private boolean winterEnabled() {
        return getConfig().getBoolean("winter.enabled");
    }

    private boolean winterWorld(World world) {
        return world.getEnvironment() == World.Environment.NORMAL;
    }

    private int number(String path, int min, int max) {
        return Math.max(min, Math.min(max, getConfig().getInt(path)));
    }

    private long deadline(int seconds) {
        return System.nanoTime() + seconds * 1_000_000_000L;
    }

    // =========================
    // Остановка тиков
    // =========================

    private void resetFreezeTimer() {
        nextFreezeDeadline = deadline(
                number("freeze.interval-seconds", 1, 86400)
        );
    }

    private void freeze(boolean automatic) {
        if (ticks().isFrozen()) {
            return;
        }

        ticks().setFrozen(true);
        ownsFreeze = true;

        thawDeadline = automatic
                ? deadline(number("freeze.duration-seconds", 1, 3600))
                : 0L;

        Bukkit.broadcastMessage(PREFIX + (
                automatic
                        ? "Время остановлено автоматически."
                        : "Время остановлено администратором."
        ));
    }

    private void unfreeze() {
        ticks().setFrozen(false);
        ownsFreeze = false;
        thawDeadline = 0L;
        resetFreezeTimer();

        Bukkit.broadcastMessage(PREFIX + "Время снова идёт.");
    }

    private void updateFreeze() {
        long now = System.nanoTime();

        if (ownsFreeze && !ticks().isFrozen()) {
            ownsFreeze = false;
            thawDeadline = 0L;
            resetFreezeTimer();
        }

        if (ownsFreeze && thawDeadline != 0L && now >= thawDeadline) {
            unfreeze();
        }

        if (getConfig().getBoolean("freeze.auto")
                && !ticks().isFrozen()
                && now >= nextFreezeDeadline) {
            freeze(true);
        }
    }

    // =========================
    // Основная задача
    // =========================

    private void heartbeat() {
        updateFreeze();

        // Таймер работает, но зима не изменяет замороженный мир.
        if (!winterEnabled() || ticks().isFrozen()) {
            return;
        }

        for (World world : Bukkit.getWorlds()) {
            if (!winterWorld(world)) {
                continue;
            }

            if (world.hasStorm()) {
                world.setStorm(false);
            }

            updateAnimals(world);
        }

        int budget = number("performance.columns-per-second", 1, 256);

        if (regularQueue.isEmpty()) {
            fillQueue(regularQueue, 0);
        }

        processQueue(regularQueue, budget, false);
        processQueue(snowQueue, budget, true);

        if (getConfig().getBoolean("snow.long") || !snowQueue.isEmpty()) {
            snowParticles();
        }
    }

    private void fillQueue(Deque<ChunkTask> queue, int targetLayers) {
        for (World world : Bukkit.getWorlds()) {
            if (!winterWorld(world)) {
                continue;
            }

            for (Chunk chunk : world.getLoadedChunks()) {
                queue.addLast(new ChunkTask(
                        world.getUID(),
                        chunk.getX(),
                        chunk.getZ(),
                        targetLayers
                ));
            }
        }
    }

    private void processQueue(
            Deque<ChunkTask> queue,
            int budget,
            boolean snowOnly
    ) {
        for (int i = 0; i < budget && !queue.isEmpty(); i++) {
            ChunkTask task = queue.peekFirst();
            World world = Bukkit.getWorld(task.worldId);

            if (world == null
                    || !world.isChunkLoaded(task.chunkX, task.chunkZ)) {
                queue.removeFirst();
                continue;
            }

            int column = task.column++;

            int x = task.chunkX * 16 + (column & 15);
            int z = task.chunkZ * 16 + (column >> 4);

            Block top = surface(world, x, z);

            if (snowOnly) {
                if (!isWarm(top)) {
                    addSnow(top, task.targetLayers, task.targetLayers);
                }
            } else {
                updateSurface(top);
            }

            if (task.column >= 256) {
                queue.removeFirst();
            }
        }
    }

    private Block surface(World world, int x, int z) {
        int y = world.getHighestBlockYAt(
                x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES
        );

        return world.getBlockAt(x, y, z);
    }

    // =========================
    // Снег и вода
    // =========================

    private void updateSurface(Block top) {
        Material material = top.getType();
        boolean longSnow = getConfig().getBoolean("snow.long");

        boolean relevant = longSnow
                || material == Material.WATER
                || material == Material.ICE
                || material == Material.SNOW
                || material == Material.SNOW_BLOCK;

        if (!relevant) {
            return;
        }

        if (isWarm(top)) {
            melt(top);
            return;
        }

        if (material == Material.WATER
                && top.getBlockData() instanceof Levelled water
                && water.getLevel() == 0) {
            top.setType(Material.ICE, false);
            return;
        }

        if (longSnow) {
            addSnow(top, 1, number("snow.max-layers", 1, 64));
        }
    }

    private void melt(Block block) {
        if (block.getBlockData() instanceof Snow snow) {
            if (snow.getLayers() <= 1) {
                block.setType(Material.AIR, false);
            } else {
                snow.setLayers(snow.getLayers() - 1);
                block.setBlockData(snow, false);
            }
        } else if (block.getType() == Material.SNOW_BLOCK) {
            Snow snow = (Snow) Material.SNOW.createBlockData();
            snow.setLayers(7);
            block.setBlockData(snow, false);
        } else if (block.getType() == Material.ICE) {
            block.setType(Material.WATER, false);
        }
    }

    private int snowThickness(Block top) {
        int result = 0;
        Block current = top;

        for (int i = 0; i < 64; i++) {
            if (current.getBlockData() instanceof Snow snow) {
                result += snow.getLayers();
            } else if (current.getType() == Material.SNOW_BLOCK) {
                result += 8;
            } else {
                break;
            }

            if (current.getY() <= current.getWorld().getMinHeight()) {
                break;
            }

            current = current.getRelative(0, -1, 0);
        }

        return result;
    }

    private void addSnow(Block top, int amount, int limit) {
        int count = Math.min(amount, Math.max(0, limit - snowThickness(top)));

        for (int i = 0; i < count; i++) {
            if (top.getBlockData() instanceof Snow snow) {
                if (snow.getLayers() < snow.getMaximumLayers()) {
                    snow.setLayers(snow.getLayers() + 1);
                    top.setBlockData(snow, false);
                    continue;
                }

                if (!spaceAbove(top)) {
                    return;
                }

                top.setType(Material.SNOW_BLOCK, false);
            } else if (!top.getType().isOccluding()) {
                return;
            }

            if (!spaceAbove(top)) {
                return;
            }

            top = top.getRelative(0, 1, 0);
            top.setType(Material.SNOW, false);
        }
    }

    private boolean spaceAbove(Block block) {
        return block.getY() < block.getWorld().getMaxHeight() - 1
                && block.getRelative(0, 1, 0).getType().isAir();
    }

    private void startSnowPass(Player player) {
        snowQueue.clear();

        fillQueue(snowQueue, number("snow.layers", 1, 64));

        player.sendMessage(PREFIX
                + "Разовый снегопад запущен. Чанков: "
                + snowQueue.size() + ".");
    }

    private void snowParticles() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!winterWorld(player.getWorld())) {
                continue;
            }

            if (player.getLocation().getBlock().getLightFromSky() < 14) {
                continue;
            }

            player.spawnParticle(
                    Particle.SNOWFLAKE,
                    player.getLocation().add(0, 7, 0),
                    50,
                    10, 4, 10,
                    0.02
            );
        }
    }

    // =========================
    // Тепло
    // =========================

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

            int radius = number("heat." + group, 0, 8);

            heat.put(material, radius);
            maxHeatRadius = Math.max(maxHeatRadius, radius);
        }
    }

    private boolean isWarm(Block target) {
        World world = target.getWorld();
        int radius = maxHeatRadius;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                int x = target.getX() + dx;
                int z = target.getZ() + dz;

                // Не загружаем соседние чанки ради поиска тепла.
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
                    Integer sourceRadius = heat.get(source.getType());

                    if (sourceRadius == null
                            || sourceRadius <= 0
                            || distance > sourceRadius * sourceRadius) {
                        continue;
                    }

                    if (source.getBlockData() instanceof Lightable light
                            && !light.isLit()) {
                        continue;
                    }

                    return true;
                }
            }
        }

        return false;
    }

    // =========================
    // Животные и зимние мобы
    // =========================

    private void updateAnimals(World world) {
        int coldY = getConfig().getInt("winter.cold-y");

        double damage = Math.max(0.0, Math.min(
                100.0,
                getConfig().getDouble("winter.animal-damage")
        ));

        if (damage <= 0) {
            return;
        }

        for (Animals animal : world.getEntitiesByClass(Animals.class)) {
            if (!animal.isValid() || animal.isDead()) {
                continue;
            }

            if (animal instanceof PolarBear) {
                continue;
            }

            if (animal instanceof Fox fox
                    && fox.getFoxType() == Fox.Type.SNOW) {
                continue;
            }

            if (animal.getLocation().getY() >= coldY) {
                animal.damage(damage);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!winterEnabled()
                || !winterWorld(event.getEntity().getWorld())
                || event.getSpawnReason()
                != CreatureSpawnEvent.SpawnReason.NATURAL) {
            return;
        }

        if (event.getEntityType() == EntityType.SKELETON) {
            event.setCancelled(true);

            event.getEntity().getWorld().spawn(
                    event.getLocation(), Stray.class
            );
        } else if (event.getEntity() instanceof Fox fox) {
            fox.setFoxType(Fox.Type.SNOW);
        }

        // Криперы остаются обычными.
    }

    @EventHandler(ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent event) {
        if (winterEnabled()
                && winterWorld(event.getWorld())
                && event.toWeatherState()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (!winterEnabled()
                || !winterWorld(event.getBlock().getWorld())) {
            return;
        }

        Material type = event.getBlock().getType();

        if (type == Material.SNOW
                || type == Material.SNOW_BLOCK
                || type == Material.ICE) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (!winterEnabled()
                || !winterWorld(event.getBlock().getWorld())) {
            return;
        }

        Material type = event.getNewState().getType();

        if (type == Material.SNOW || type == Material.ICE) {
            event.setCancelled(true);
        }
    }

    // =========================
    // Команды
    // =========================

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        if (!sender.hasPermission("wintercontrol.admin")) {
            sender.sendMessage(PREFIX + "Нет прав.");
            return true;
        }

        String action = args.length == 0
                ? "menu"
                : args[0].toLowerCase(Locale.ROOT);

        switch (action) {
            case "menu" -> {
                if (sender instanceof Player player) {
                    openMenu(player, Page.MAIN);
                } else {
                    sender.sendMessage(PREFIX + "Меню доступно игрокам.");
                }
            }

            case "freeze" -> {
                if (ticks().isFrozen()) {
                    sender.sendMessage(PREFIX + "Тики уже остановлены.");
                } else {
                    freeze(false);
                }
            }

            case "unfreeze" -> unfreeze();

            default -> sender.sendMessage(
                    PREFIX + "/winter [menu|freeze|unfreeze]"
            );
        }

        return true;
    }

    // =========================
    // Русское меню
    // =========================

    private ItemStack button(
            Material material,
            String name,
            String... description
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName("§b" + name);
        meta.setLore(Arrays.stream(description)
                .map(line -> "§7" + line)
                .toList());

        item.setItemMeta(meta);
        return item;
    }

    private String state(boolean enabled) {
        return enabled ? "§aВключено" : "§cВыключено";
    }

    private void openMenu(Player player, Page page) {
        Menu holder = new Menu(page);

        String title = switch (page) {
            case MAIN -> "§1Управление зимой";
            case SNOW -> "§1Настройка снега";
            case HEAT -> "§1Источники тепла";
        };

        Inventory inventory = Bukkit.createInventory(holder, 54, title);
        holder.inventory = inventory;

        if (page == Page.MAIN) {
            inventory.setItem(10, button(
                    Material.SNOW_BLOCK,
                    "Зимний режим",
                    state(winterEnabled()),
                    "Клик: включить / выключить"
            ));

            inventory.setItem(11, button(
                    Material.CLOCK,
                    "Автоматическая остановка",
                    state(getConfig().getBoolean("freeze.auto")),
                    "Клик: включить / выключить"
            ));

            inventory.setItem(12, button(
                    Material.REPEATER,
                    "Интервал: "
                            + getConfig().getInt("freeze.interval-seconds")
                            + " сек.",
                    "От разморозки до следующей остановки",
                    "ЛКМ: +10; ПКМ: -10 секунд",
                    "С Shift: шаг 100 секунд"
            ));

            inventory.setItem(13, button(
                    Material.COMPASS,
                    "Длительность: "
                            + getConfig().getInt("freeze.duration-seconds")
                            + " сек.",
                    "ЛКМ: +1; ПКМ: -1 секунда",
                    "С Shift: шаг 10 секунд"
            ));

            inventory.setItem(14, button(
                    Material.BLUE_ICE,
                    "Остановить время",
                    "Ручная остановка тиков",
                    "До ручного возобновления"
            ));

            inventory.setItem(15, button(
                    Material.SUNFLOWER,
                    "Возобновить время",
                    ticks().isFrozen()
                            ? "Сейчас тики остановлены"
                            : "Сейчас тики идут"
            ));

            inventory.setItem(16, button(
                    Material.IRON_SHOVEL,
                    "Граница холода Y: "
                            + getConfig().getInt("winter.cold-y"),
                    "На этой высоте и выше животные мёрзнут",
                    "ЛКМ: +1; ПКМ: -1",
                    "С Shift: шаг 10"
            ));

            inventory.setItem(22, button(
                    Material.SNOWBALL,
                    "Снегопад",
                    "Открыть настройки"
            ));

            inventory.setItem(31, button(
                    Material.CAMPFIRE,
                    "Тепло и таяние",
                    "Открыть настройки"
            ));
        }

        if (page == Page.SNOW) {
            inventory.setItem(10, button(
                    Material.SNOW,
                    "Разовая толщина: "
                            + getConfig().getInt("snow.layers")
                            + " слоёв",
                    "ЛКМ: +1; ПКМ: -1",
                    "С Shift: шаг 10"
            ));

            inventory.setItem(11, button(
                    Material.SNOW_BLOCK,
                    "Запустить разовый снег",
                    "Довести снег до выбранной толщины",
                    "Только в загруженных чанках"
            ));

            inventory.setItem(12, button(
                    Material.POWDER_SNOW_BUCKET,
                    "Долгий снегопад",
                    state(getConfig().getBoolean("snow.long")),
                    "Клик: включить / выключить"
            ));

            inventory.setItem(13, button(
                    Material.BARRIER,
                    "Остановить снегопад",
                    "Отменяет и незавершённый разовый проход"
            ));

            inventory.setItem(14, button(
                    Material.WHITE_WOOL,
                    "Предел: "
                            + getConfig().getInt("snow.max-layers")
                            + " слоёв",
                    "Максимум для долгого снегопада",
                    "ЛКМ: +1; ПКМ: -1",
                    "С Shift: шаг 10"
            ));
        }

        if (page == Page.HEAT) {
            for (int i = 0; i < HEAT_SETTINGS.size(); i++) {
                HeatSetting setting = HEAT_SETTINGS.get(i);

                inventory.setItem(10 + i, button(
                        setting.icon(),
                        setting.name(),
                        "Радиус: "
                                + getConfig().getInt("heat." + setting.key())
                                + " блоков",
                        "ЛКМ: увеличить; ПКМ: уменьшить",
                        "0 — отключить источник",
                        "Тепло проходит через стены"
                ));
            }
        }

        if (page != Page.MAIN) {
            inventory.setItem(49, button(Material.ARROW, "Назад"));
        }

        player.openInventory(inventory);
    }

    private void toggle(String path) {
        getConfig().set(path, !getConfig().getBoolean(path));
    }

    private void change(String path, int delta, int min, int max) {
        long value = (long) getConfig().getInt(path) + delta;

        getConfig().set(path, (int) Math.max(min, Math.min(max, value)));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder()
                instanceof Menu menu)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!player.hasPermission("wintercontrol.admin")) {
            player.closeInventory();
            return;
        }

        int slot = event.getRawSlot();

        if (slot < 0
                || slot >= event.getView().getTopInventory().getSize()) {
            return;
        }

        // Не обрабатываем среднюю кнопку и цифровые клавиши.
        if (!event.isLeftClick() && !event.isRightClick()) {
            return;
        }

        int step = event.isRightClick() ? -1 : 1;

        if (event.isShiftClick()) {
            step *= 10;
        }

        Page target = menu.page;

        if (slot == 49) {
            target = Page.MAIN;
        } else if (menu.page == Page.MAIN) {
            switch (slot) {
                case 10 -> {
                    toggle("winter.enabled");
                    regularQueue.clear();

                    if (!winterEnabled()) {
                        snowQueue.clear();
                    }
                }

                case 11 -> {
                    toggle("freeze.auto");
                    resetFreezeTimer();
                }

                case 12 -> {
                    change("freeze.interval-seconds", step * 10, 1, 86400);
                    resetFreezeTimer();
                }

                case 13 ->
                        change("freeze.duration-seconds", step, 1, 3600);

                case 14 -> {
                    if (ticks().isFrozen()) {
                        player.sendMessage(PREFIX + "Тики уже остановлены.");
                    } else {
                        freeze(false);
                    }
                }

                case 15 -> unfreeze();

                case 16 ->
                        change("winter.cold-y", step, -64, 320);

                case 22 -> target = Page.SNOW;
                case 31 -> target = Page.HEAT;
                default -> { }
            }
        } else if (menu.page == Page.SNOW) {
            switch (slot) {
                case 10 -> change("snow.layers", step, 1, 64);

                case 11 -> {
                    if (winterEnabled()) {
                        startSnowPass(player);
                    } else {
                        player.sendMessage(
                                PREFIX + "Сначала включи зимний режим."
                        );
                    }
                }

                case 12 -> {
                    if (winterEnabled()) {
                        toggle("snow.long");
                    } else {
                        player.sendMessage(
                                PREFIX + "Сначала включи зимний режим."
                        );
                    }
                }

                case 13 -> {
                    getConfig().set("snow.long", false);
                    snowQueue.clear();
                }

                case 14 -> change("snow.max-layers", step, 1, 64);
                default -> { }
            }
        } else if (menu.page == Page.HEAT) {
            int index = slot - 10;

            if (index >= 0 && index < HEAT_SETTINGS.size()) {
                HeatSetting setting = HEAT_SETTINGS.get(index);

                change("heat." + setting.key(), step, 0, 8);
                rebuildHeat();
            }
        }

        saveConfig();

        Page finalTarget = target;

        getServer().getScheduler().runTask(this, () -> {
            if (player.isOnline()) {
                openMenu(player, finalTarget);
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder()
                instanceof Menu) {
            event.setCancelled(true);
        }
    }

    // =========================
    // Вспомогательные типы
    // =========================

    private enum Page {
        MAIN,
        SNOW,
        HEAT
    }

    private static final class Menu implements InventoryHolder {
        private final Page page;
        private Inventory inventory;

        private Menu(Page page) {
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }

    private record HeatSetting(
            String key,
            Material icon,
            String name
    ) {
    }

    private static final class ChunkTask {
        private final UUID worldId;
        private final int chunkX;
        private final int chunkZ;
        private final int targetLayers;

        private int column;

        private ChunkTask(
                UUID worldId,
                int chunkX,
                int chunkZ,
                int targetLayers
        ) {
            this.worldId = worldId;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.targetLayers = targetLayers;
        }
    }
}
