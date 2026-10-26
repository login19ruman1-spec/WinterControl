package ru.wintercontrol;

import org.bukkit.*;
import org.bukkit.command.*;
import org.bukkit.entity.*;
import org.bukkit.event.*;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.weather.LightningStrikeEvent;
import org.bukkit.inventory.*;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class WinterControl extends JavaPlugin
        implements Listener, CommandExecutor {

    private static final String PREFIX = "§b[Зима] §f";

    private WinterClimate climate;

    private boolean ownsFreeze;
    private long thawDeadline;
    private long nextFreezeDeadline;

    private static final List<HeatSetting> HEAT = List.of(
            new HeatSetting("torch", Material.TORCH, "Факелы"),
            new HeatSetting("lantern", Material.LANTERN, "Фонари"),
            new HeatSetting("fire", Material.FIRE_CHARGE, "Огонь"),
            new HeatSetting("campfire", Material.CAMPFIRE, "Горящие костры"),
            new HeatSetting("lava", Material.LAVA_BUCKET, "Лава"),
            new HeatSetting("magma", Material.MAGMA_BLOCK, "Магма"),
            new HeatSetting("furnace", Material.FURNACE, "Работающие печи"),
            new HeatSetting("candle", Material.CANDLE, "Горящие свечи"),
            new HeatSetting("soul", Material.SOUL_TORCH, "Огонь душ")
    );

    @Override
    public void onEnable() {
        setupConfig();

        Objects.requireNonNull(
                getCommand("winter"),
                "В plugin.yml отсутствует команда winter"
        ).setExecutor(this);

        getServer().getPluginManager().registerEvents(this, this);

        getLogger().info(
                "Подготовка хранилища WinterControl. "
                        + "При первом запуске переносится старое состояние."
        );

        getServer().getScheduler().runTaskAsynchronously(this, () -> {
            WinterStorage storage = new WinterStorage(
                    getDataFolder().toPath()
            );

            try {
                storage.prepare();
            } catch (Exception exception) {
                getLogger().log(
                        java.util.logging.Level.SEVERE,
                        "Не удалось подготовить хранилище. "
                                + "Климат не будет запущен.",
                        exception
                );
                return;
            }

            // Возвращаемся в основной поток: Bukkit API
            // (климат, планировщик, события) не потокобезопасен.
            getServer().getScheduler().runTask(this, () -> {
                if (!isEnabled()) {
                    return;
                }

                climate = new WinterClimate(this);

                resetFreezeTimer();

                getServer().getScheduler().runTaskTimer(
                        this, this::updateFreeze, 20L, 20L
                );

                getLogger().info("Хранилище готово, климат запущен.");
            });
        });
    }

    @Override
    public void onDisable() {
        if (climate != null) {
            climate.close();
        }

        if (ownsFreeze && ticks().isFrozen()) {
            ticks().setFrozen(false);
        }

        saveConfig();
    }

    private void setupConfig() {
        var c = getConfig();

        c.addDefault("winter.enabled", false);
        c.addDefault("winter.cold-y", 50);

        c.addDefault("freeze.auto", false);
        c.addDefault("freeze.interval-seconds", 300);
        c.addDefault("freeze.duration-seconds", 10);

        c.addDefault("snow.layers", 8);
        c.addDefault("snow.long", false);
        c.addDefault("snow.max-layers", 16);

        c.addDefault("heat.torch", 3);
        c.addDefault("heat.lantern", 3);
        c.addDefault("heat.fire", 4);
        c.addDefault("heat.campfire", 4);
        c.addDefault("heat.lava", 5);
        c.addDefault("heat.magma", 2);
        c.addDefault("heat.furnace", 3);
        c.addDefault("heat.candle", 1);
        c.addDefault("heat.soul", 1);

        c.options().copyDefaults(true);
        saveConfig();
    }

    private boolean winter() {
        return getConfig().getBoolean("winter.enabled");
    }

    private ServerTickManager ticks() {
        return getServer().getServerTickManager();
    }

    private long deadline(int seconds) {
        return System.nanoTime() + seconds * 1_000_000_000L;
    }

    private void resetFreezeTimer() {
        nextFreezeDeadline = deadline(
                Math.max(1, getConfig().getInt("freeze.interval-seconds"))
        );
    }

    private void freeze(boolean automatic) {
        if (ticks().isFrozen()) {
            return;
        }

        ownsFreeze = true;

        thawDeadline = automatic
                ? deadline(Math.max(
                        1, getConfig().getInt("freeze.duration-seconds")
                ))
                : 0L;

        ticks().setFrozen(true);

        Bukkit.broadcastMessage(PREFIX + "Тики остановлены.");
    }

    private void unfreeze() {
        ticks().setFrozen(false);
        ownsFreeze = false;
        thawDeadline = 0L;

        resetFreezeTimer();

        Bukkit.broadcastMessage(PREFIX + "Тики снова идут.");
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

            case "freeze" -> freeze(false);
            case "unfreeze" -> unfreeze();

            default -> sender.sendMessage(
                    PREFIX + "/winter [menu|freeze|unfreeze]"
            );
        }

        return true;
    }

    private ItemStack button(
            Material material,
            String title,
            String... lines
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName("§b" + title);
        meta.setLore(Arrays.stream(lines)
                .map(line -> "§7" + line)
                .toList());

        item.setItemMeta(meta);
        return item;
    }

    private String state(String path) {
        return getConfig().getBoolean(path)
                ? "§aВключено"
                : "§cВыключено";
    }

    private void openMenu(Player player, Page page) {
        Menu holder = new Menu(page);

        String title = switch (page) {
            case MAIN -> "§1Управление зимой";
            case SNOW -> "§1Снег и погода";
            case HEAT -> "§1Источники тепла";
        };

        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.inventory = inv;

        switch (page) {
            case MAIN -> {
                inv.setItem(10, button(
                        winter() ? Material.SNOW_BLOCK : Material.GRASS_BLOCK,
                        winter() ? "Зима: включена" : "Зима: выключена",
                        "Клик: переключить",
                        "После выключения биомы восстанавливаются",
                        "Снег и лёд плагина постепенно тают"
                ));

                inv.setItem(11, button(
                        Material.CLOCK, "Автоостановка тиков",
                        state("freeze.auto")
                ));

                inv.setItem(12, button(
                        Material.REPEATER,
                        "Интервал: "
                                + getConfig().getInt("freeze.interval-seconds")
                                + " сек.",
                        "ЛКМ +10; ПКМ -10; Shift ×10"
                ));

                inv.setItem(13, button(
                        Material.COMPASS,
                        "Остановка на "
                                + getConfig().getInt("freeze.duration-seconds")
                                + " сек.",
                        "ЛКМ +1; ПКМ -1; Shift ×10"
                ));

                inv.setItem(14, button(
                        Material.BLUE_ICE, "Остановить тики вручную"
                ));

                inv.setItem(15, button(
                        Material.SUNFLOWER, "Возобновить тики"
                ));

                inv.setItem(16, depthButton(
                        Material.LEATHER_CHESTPLATE,
                        "Игроки", "cold.players-y"
                ));

                inv.setItem(19, depthButton(
                        Material.WHEAT,
                        "Мирные мобы", "cold.passive-y"
                ));

                inv.setItem(20, depthButton(
                        Material.ROTTEN_FLESH,
                        "Враждебные мобы", "cold.hostile-y"
                ));

                inv.setItem(22, button(
                        Material.SNOWBALL, "Снег и погода",
                        "Открыть настройки"
                ));

                inv.setItem(23, button(
                        Material.SOUL_LANTERN,
                        "Усиленный ночной холод",
                        state("cold.night-enabled"),
                        "Ночью урон от холода сильнее",
                        "Глубина и тепло продолжают защищать"
                ));

                inv.setItem(31, button(
                        Material.CAMPFIRE, "Источники тепла",
                        "Настроить радиусы"
                ));
            }

            case SNOW -> {
                inv.setItem(10, button(
                        Material.SNOW,
                        "Разовое покрытие: "
                                + getConfig().getInt("snow.layers")
                                + " слоёв",
                        "ЛКМ +1; ПКМ -1; Shift ×10"
                ));

                inv.setItem(11, button(
                        Material.SNOW_BLOCK, "Запустить разовое покрытие",
                        "На деревьях и под ними",
                        "В загруженных чанках"
                ));

                inv.setItem(12, button(
                        Material.POWDER_SNOW_BUCKET,
                        "Непрерывный снегопад",
                        state("snow.long"),
                        "Выключено — периодические снегопады",
                        "Клик также снимает паузу"
                ));

                inv.setItem(13, button(
                        Material.BARRIER, "Приостановить снегопады"
                ));

                inv.setItem(14, button(
                        Material.WHITE_WOOL,
                        "Предел: "
                                + getConfig().getInt("snow.max-layers")
                                + " слоёв",
                        "ЛКМ +1; ПКМ -1; Shift ×10"
                ));

                inv.setItem(15, button(
                        Material.CLOCK,
                        "Пауза: "
                                + getConfig().getInt("snow.interval-seconds")
                                + " сек.",
                        "ЛКМ +10; ПКМ -10; Shift ×10"
                ));

                inv.setItem(16, button(
                        Material.COMPASS,
                        "Снегопад: "
                                + getConfig().getInt("snow.duration-seconds")
                                + " сек.",
                        "ЛКМ +5; ПКМ -5; Shift ×10"
                ));

                inv.setItem(19, button(
                        Material.QUARTZ,
                        "Частицы: "
                                + getConfig().getInt("snow.particles-per-step"),
                        "ЛКМ +10; ПКМ -10; Shift ×10",
                        "Большие значения нагружают клиент"
                ));

                inv.setItem(20, button(
                        Material.ICE,
                        "Скорость замерзания: "
                                + getConfig().getInt("performance.water-columns"),
                        "Столбцов за проход",
                        "ЛКМ +16; ПКМ -16; Shift ×10"
                ));

                inv.setItem(22, button(
                        Material.SUNFLOWER,
                        "Возобновить периодические снегопады"
                ));

                inv.setItem(24, button(
                        Material.SNOW_BLOCK,
                        "Настоящий снег и зимние биомы",
                        state("weather.real-snow"),
                        "Пустыня не меняется",
                        "Выключение восстанавливает исходные биомы"
                ));

                inv.setItem(25, button(
                        Material.GRAY_WOOL,
                        "Тёмное небо при снегопаде",
                        state("weather.dark-sky"),
                        "Работает с настоящей снежной погодой",
                        "Молнии во время такого снегопада запрещены"
                ));
            }

            case HEAT -> {
                for (int i = 0; i < HEAT.size(); i++) {
                    HeatSetting setting = HEAT.get(i);

                    inv.setItem(10 + i, button(
                            setting.icon(),
                            setting.title(),
                            "Радиус: "
                                    + getConfig().getInt(
                                            "heat." + setting.key()
                                    ),
                            "ЛКМ +1; ПКМ -1; Shift ×10",
                            "0 — источник отключён",
                            "Тепло проходит через стены"
                    ));
                }
            }
        }

        if (page != Page.MAIN) {
            inv.setItem(49, button(Material.ARROW, "Назад"));
        }

        player.openInventory(inv);
    }

    private ItemStack depthButton(
            Material material,
            String title,
            String path
    ) {
        return button(
                material,
                title + ": граница Y " + getConfig().getInt(path),
                "Ниже границы безопасно",
                "На границе и выше нужно тепло",
                "ЛКМ +1; ПКМ -1; Shift ×10"
        );
    }

    private void toggle(String path) {
        getConfig().set(path, !getConfig().getBoolean(path));
    }

    private void change(String path, int delta, int min, int max) {
        long value = (long) getConfig().getInt(path) + delta;
        getConfig().set(path, (int) Math.max(min, Math.min(max, value)));
    }

    private boolean requireWinter(Player player) {
        if (winter()) {
            return true;
        }

        player.sendMessage(PREFIX + "Сначала включи зиму.");
        return false;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();

        if (!(top.getHolder() instanceof Menu menu)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)
                || !player.hasPermission("wintercontrol.admin")) {
            return;
        }

        int slot = event.getRawSlot();

        if (slot < 0 || slot >= top.getSize()
                || (!event.isLeftClick() && !event.isRightClick())) {
            return;
        }

        int step = event.isRightClick() ? -1 : 1;

        if (event.isShiftClick()) {
            step *= 10;
        }

        Page next = menu.page;

        if (slot == 49) {
            next = Page.MAIN;
        } else {
            switch (menu.page) {
                case MAIN -> {
                    switch (slot) {
                        case 10 -> {
                            toggle("winter.enabled");
                            getConfig().set("snow.paused", false);
                        }
                        case 11 -> {
                            toggle("freeze.auto");
                            resetFreezeTimer();
                        }
                        case 12 -> {
                            change("freeze.interval-seconds",
                                    step * 10, 1, 86400);
                            resetFreezeTimer();
                        }
                        case 13 -> change(
                                "freeze.duration-seconds", step, 1, 3600
                        );
                        case 14 -> freeze(false);
                        case 15 -> unfreeze();
                        case 16 -> change(
                                "cold.players-y", step, -64, 320
                        );
                        case 19 -> change(
                                "cold.passive-y", step, -64, 320
                        );
                        case 20 -> change(
                                "cold.hostile-y", step, -64, 320
                        );
                        case 22 -> next = Page.SNOW;
                        case 23 -> toggle("cold.night-enabled");
                        case 31 -> next = Page.HEAT;
                        default -> { }
                    }
                }

                case SNOW -> {
                    switch (slot) {
                        case 10 -> change("snow.layers", step, 1, 64);
                        case 11 -> {
                            if (requireWinter(player)) {
                                climate.coverNow();
                            }
                        }
                        case 12 -> {
                            if (requireWinter(player)) {
                                toggle("snow.long");
                                getConfig().set("snow.paused", false);
                            }
                        }
                        case 13 -> climate.stopSnow();
                        case 14 -> change(
                                "snow.max-layers", step, 1, 64
                        );
                        case 15 -> change(
                                "snow.interval-seconds",
                                step * 10, 1, 86400
                        );
                        case 16 -> change(
                                "snow.duration-seconds",
                                step * 5, 1, 3600
                        );
                        case 19 -> change(
                                "snow.particles-per-step",
                                step * 10, 0, 500
                        );
                        case 20 -> change(
                                "performance.water-columns",
                                step * 16, 16, 1024
                        );
                        case 22 -> {
                            if (requireWinter(player)) {
                                getConfig().set("snow.long", false);
                                getConfig().set("snow.paused", false);
                            }
                        }
                        case 24 -> toggle("weather.real-snow");
                        case 25 -> toggle("weather.dark-sky");
                        default -> { }
                    }
                }

                case HEAT -> {
                    int index = slot - 10;

                    if (index >= 0 && index < HEAT.size()) {
                        change(
                                "heat." + HEAT.get(index).key(),
                                step, 0, 8
                        );
                    }
                }
            }
        }

        saveConfig();

        Page target = next;

        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()
                    && player.getOpenInventory().getTopInventory() == top) {
                openMenu(player, target);
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onSpawn(CreatureSpawnEvent event) {
        if (!winter()
                || event.getEntity().getWorld().getEnvironment()
                    != World.Environment.NORMAL
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
    }

    @EventHandler(ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (!winter()
                || event.getBlock().getWorld().getEnvironment()
                    != World.Environment.NORMAL) {
            return;
        }

        Material result = event.getNewState().getType();

        if (result == Material.SNOW || result == Material.ICE) {
            // Не даём ванильным осадкам создавать
            // неучтённый снег и лёд во время зимы.
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (!winter()
                || event.getBlock().getWorld().getEnvironment()
                    != World.Environment.NORMAL) {
            return;
        }

        Material type = event.getBlock().getType();

        if (type == Material.SNOW
                || type == Material.SNOW_BLOCK
                || type == Material.ICE) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onLightning(LightningStrikeEvent event) {
        if (climate != null
                && climate.blocksLightning(event.getWorld())) {
            event.setCancelled(true);
        }
    }

    private enum Page {
        MAIN, SNOW, HEAT
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
            String key, Material icon, String title
    ) { }
}
