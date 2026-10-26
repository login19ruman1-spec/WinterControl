package ru.wintercontrol;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.ServerTickManager;
import org.bukkit.World;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;

import org.bukkit.entity.EntityType;
import org.bukkit.entity.Fox;
import org.bukkit.entity.Player;
import org.bukkit.entity.Stray;

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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

public final class WinterControl extends JavaPlugin
        implements Listener, CommandExecutor {

    private static final String PREFIX = "§b[Зима] §f";

    private WinterClimate climate;

    private boolean ownsFreeze;
    private long thawDeadline;
    private long nextFreezeDeadline;

    private static final List<HeatSetting> HEAT_SETTINGS = List.of(
            new HeatSetting(
                    "torch",
                    Material.TORCH,
                    "Факелы"
            ),
            new HeatSetting(
                    "lantern",
                    Material.LANTERN,
                    "Фонари"
            ),
            new HeatSetting(
                    "fire",
                    Material.FIRE_CHARGE,
                    "Огонь"
            ),
            new HeatSetting(
                    "campfire",
                    Material.CAMPFIRE,
                    "Горящие костры"
            ),
            new HeatSetting(
                    "lava",
                    Material.LAVA_BUCKET,
                    "Лава"
            ),
            new HeatSetting(
                    "magma",
                    Material.MAGMA_BLOCK,
                    "Магмовые блоки"
            ),
            new HeatSetting(
                    "furnace",
                    Material.FURNACE,
                    "Работающие печи"
            ),
            new HeatSetting(
                    "candle",
                    Material.CANDLE,
                    "Горящие свечи"
            ),
            new HeatSetting(
                    "soul",
                    Material.SOUL_TORCH,
                    "Огонь душ"
            )
    );

    // =========================
    // Запуск и выключение
    // =========================

    @Override
    public void onEnable() {
        setupConfig();

        Objects.requireNonNull(
                getCommand("winter"),
                "Команда winter не зарегистрирована в plugin.yml"
        ).setExecutor(this);

        getServer().getPluginManager().registerEvents(this, this);

        // Весь климат обрабатывает отдельный класс.
        climate = new WinterClimate(this);

        resetFreezeTimer();

        // Эта задача управляет только остановкой тиков.
        getServer().getScheduler().runTaskTimer(
                this,
                this::heartbeat,
                20L,
                20L
        );

        getLogger().info("WinterControl включён.");
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
        var config = getConfig();

        config.addDefault("winter.enabled", false);
        config.addDefault("winter.cold-y", 50);

        config.addDefault("freeze.auto", false);
        config.addDefault("freeze.interval-seconds", 300);
        config.addDefault("freeze.duration-seconds", 10);

        config.addDefault("snow.layers", 8);
        config.addDefault("snow.long", false);
        config.addDefault("snow.max-layers", 32);

        config.addDefault("heat.torch", 3);
        config.addDefault("heat.lantern", 3);
        config.addDefault("heat.fire", 4);
        config.addDefault("heat.campfire", 4);
        config.addDefault("heat.lava", 5);
        config.addDefault("heat.magma", 2);
        config.addDefault("heat.furnace", 3);
        config.addDefault("heat.candle", 1);
        config.addDefault("heat.soul", 1);

        // Остальные настройки добавит WinterClimate.
        config.options().copyDefaults(true);
        saveConfig();
    }

    private boolean winterEnabled() {
        return getConfig().getBoolean("winter.enabled");
    }

    private boolean winterWorld(World world) {
        return world.getEnvironment() == World.Environment.NORMAL;
    }

    private int number(String path, int minimum, int maximum) {
        return Math.max(
                minimum,
                Math.min(maximum, getConfig().getInt(path))
        );
    }

    // =========================
    // Остановка тиков
    // =========================

    private ServerTickManager ticks() {
        return getServer().getServerTickManager();
    }

    private long deadline(int seconds) {
        return System.nanoTime() + seconds * 1_000_000_000L;
    }

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
                ? deadline(
                        number("freeze.duration-seconds", 1, 3600)
                )
                : 0L;

        Bukkit.broadcastMessage(
                PREFIX + (
                        automatic
                                ? "Тики остановлены автоматически."
                                : "Тики остановлены администратором."
                )
        );
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

        // Если тики возобновили внешней командой,
        // начинаем отсчёт интервала заново.
        if (ownsFreeze && !ticks().isFrozen()) {
            ownsFreeze = false;
            thawDeadline = 0L;
            resetFreezeTimer();
        }

        if (ownsFreeze
                && thawDeadline != 0L
                && now >= thawDeadline) {
            unfreeze();
        }

        if (getConfig().getBoolean("freeze.auto")
                && !ticks().isFrozen()
                && now >= nextFreezeDeadline) {
            freeze(true);
        }
    }

    private void heartbeat() {
        // Старую обработку снега и животных здесь не запускаем.
        // Иначе она будет конфликтовать с WinterClimate.
        updateFreeze();
    }

    // =========================
    // Команда /winter
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
                    sender.sendMessage(
                            PREFIX + "Меню доступно только игрокам."
                    );
                }
            }

            case "freeze" -> {
                if (ticks().isFrozen()) {
                    sender.sendMessage(
                            PREFIX + "Тики уже остановлены."
                    );
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
    // Создание кнопок
    // =========================

    private ItemStack button(
            Material material,
            String name,
            String... description
    ) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.setDisplayName("§b" + name);

        meta.setLore(
                Arrays.stream(description)
                        .map(line -> "§7" + line)
                        .toList()
        );

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
            case SNOW -> "§1Настройка снегопадов";
            case HEAT -> "§1Источники тепла";
        };

        Inventory inventory = Bukkit.createInventory(
                holder,
                54,
                title
        );

        holder.inventory = inventory;

        switch (page) {
            case MAIN -> fillMainMenu(inventory);
            case SNOW -> fillSnowMenu(inventory);
            case HEAT -> fillHeatMenu(inventory);
        }

        if (page != Page.MAIN) {
            inventory.setItem(
                    49,
                    button(Material.ARROW, "Назад")
            );
        }

        player.openInventory(inventory);
    }

    // =========================
    // Главное меню
    // =========================

    private void fillMainMenu(Inventory inventory) {
        // Слот 10: второй ряд, вторая ячейка.
        inventory.setItem(10, button(
                winterEnabled()
                        ? Material.SNOW_BLOCK
                        : Material.GRASS_BLOCK,
                winterEnabled()
                        ? "Зима: включена"
                        : "Зима: выключена",
                "Клик: включить или выключить зиму",
                "Зимой вода замерзает и идут снегопады",
                "После выключения снег и лёд плагина тают"
        ));

        inventory.setItem(11, button(
                Material.CLOCK,
                "Автоматическая остановка тиков",
                state(getConfig().getBoolean("freeze.auto")),
                "Клик: включить или выключить",
                "Работает независимо от зимнего режима"
        ));

        inventory.setItem(12, button(
                Material.REPEATER,
                "Интервал остановок: "
                        + getConfig().getInt(
                                "freeze.interval-seconds"
                        )
                        + " сек.",
                "От разморозки до следующей остановки",
                "ЛКМ: +10; ПКМ: -10 секунд",
                "С Shift: шаг 100 секунд"
        ));

        inventory.setItem(13, button(
                Material.COMPASS,
                "Длительность остановки: "
                        + getConfig().getInt(
                                "freeze.duration-seconds"
                        )
                        + " сек.",
                "Для автоматической остановки",
                "ЛКМ: +1; ПКМ: -1 секунда",
                "С Shift: шаг 10 секунд"
        ));

        inventory.setItem(14, button(
                Material.BLUE_ICE,
                "Остановить тики вручную",
                "Как /tick freeze",
                "До ручного возобновления"
        ));

        inventory.setItem(15, button(
                Material.SUNFLOWER,
                "Возобновить тики",
                ticks().isFrozen()
                        ? "§cСейчас тики остановлены"
                        : "§aСейчас тики идут",
                "Как /tick unfreeze"
        ));

        inventory.setItem(16, button(
                Material.LEATHER_CHESTPLATE,
                "Безопасная глубина игроков",
                "Граница Y: "
                        + getConfig().getInt("cold.players-y"),
                "Ниже границы холод не наносит урон",
                "На границе и выше нужно тепло",
                "ЛКМ: +1; ПКМ: -1; Shift: шаг 10"
        ));

        inventory.setItem(19, button(
                Material.WHEAT,
                "Безопасная глубина мирных мобов",
                "Граница Y: "
                        + getConfig().getInt("cold.passive-y"),
                "Ниже границы холод не наносит урон",
                "Источники тепла тоже защищают",
                "ЛКМ: +1; ПКМ: -1; Shift: шаг 10"
        ));

        inventory.setItem(20, button(
                Material.ROTTEN_FLESH,
                "Безопасная глубина враждебных мобов",
                "Граница Y: "
                        + getConfig().getInt("cold.hostile-y"),
                "Ниже границы холод не наносит урон",
                "Криперы и зимние мобы защищены",
                "ЛКМ: +1; ПКМ: -1; Shift: шаг 10"
        ));

        inventory.setItem(22, button(
                Material.SNOWBALL,
                "Снегопад",
                "Открыть настройки снега",
                "Периодичность, густота и накопление"
        ));

        inventory.setItem(31, button(
                Material.CAMPFIRE,
                "Тепло и таяние",
                "Настроить радиусы источников тепла"
        ));
    }

    // =========================
    // Меню снега
    // =========================

    private void fillSnowMenu(Inventory inventory) {
        inventory.setItem(10, button(
                Material.SNOW,
                "Разовая толщина: "
                        + getConfig().getInt("snow.layers")
                        + " слоёв",
                "8 слоёв = высота одного блока",
                "ЛКМ: +1; ПКМ: -1; Shift: шаг 10"
        ));

        inventory.setItem(11, button(
                Material.SNOW_BLOCK,
                "Запустить разовое покрытие",
                "Постепенно довести снег до выбранной толщины",
                "Только в загруженных чанках",
                "Зимний режим должен быть включён"
        ));

        inventory.setItem(12, button(
                Material.POWDER_SNOW_BUCKET,
                "Непрерывный снегопад",
                state(getConfig().getBoolean("snow.long")),
                "Клик: переключить и снять паузу",
                "Когда выключен — снегопады периодические"
        ));

        inventory.setItem(13, button(
                Material.BARRIER,
                "Приостановить снегопады",
                "Отменить разовый проход и непрерывный снег",
                "Для возобновления нажми кнопку справа"
        ));

        inventory.setItem(14, button(
                Material.WHITE_WOOL,
                "Предел накопления: "
                        + getConfig().getInt("snow.max-layers")
                        + " слоёв",
                "Максимум при обычных снегопадах",
                "ЛКМ: +1; ПКМ: -1; Shift: шаг 10"
        ));

        inventory.setItem(15, button(
                Material.CLOCK,
                "Пауза между снегопадами: "
                        + getConfig().getInt(
                                "snow.interval-seconds"
                        )
                        + " сек.",
                "Для периодического режима",
                "ЛКМ: +10; ПКМ: -10 секунд",
                "С Shift: шаг 100 секунд",
                "Применяется при следующем назначении паузы"
        ));

        inventory.setItem(16, button(
                Material.COMPASS,
                "Продолжительность снегопада: "
                        + getConfig().getInt(
                                "snow.duration-seconds"
                        )
                        + " сек.",
                "Для следующих периодических снегопадов",
                "ЛКМ: +5; ПКМ: -5 секунд",
                "С Shift: шаг 50 секунд"
        ));

        inventory.setItem(19, button(
                Material.QUARTZ,
                "Густота частиц: "
                        + getConfig().getInt(
                                "snow.particles-per-step"
                        ),
                "Количество за проход на одного игрока",
                "ЛКМ: +10; ПКМ: -10",
                "С Shift: шаг 100",
                "Большие значения нагружают клиент"
        ));

        inventory.setItem(22, button(
                Material.SUNFLOWER,
                "Возобновить периодические снегопады",
                getConfig().getBoolean("snow.paused")
                        ? "§cСейчас снегопады приостановлены"
                        : "§aПаузы нет",
                "Отключает непрерывный режим",
                "Снегопады снова будут идти периодически"
        ));
    }

    // =========================
    // Меню тепла
    // =========================

    private void fillHeatMenu(Inventory inventory) {
        for (int i = 0; i < HEAT_SETTINGS.size(); i++) {
            HeatSetting setting = HEAT_SETTINGS.get(i);

            inventory.setItem(10 + i, button(
                    setting.icon(),
                    setting.name(),
                    "Радиус: "
                            + getConfig().getInt(
                                    "heat." + setting.key()
                            )
                            + " блоков",
                    "ЛКМ: увеличить; ПКМ: уменьшить",
                    "0 — отключить источник",
                    "Тепло защищает от холода и топит снег",
                    "В этой версии проходит через стены"
            ));
        }
    }

    // =========================
    // Изменение настроек
    // =========================

    private void toggle(String path) {
        getConfig().set(
                path,
                !getConfig().getBoolean(path)
        );
    }

    private void change(
            String path,
            int delta,
            int minimum,
            int maximum
    ) {
        long value = (long) getConfig().getInt(path) + delta;

        int result = (int) Math.max(
                minimum,
                Math.min(maximum, value)
        );

        getConfig().set(path, result);
    }

    private boolean requireWinter(Player player) {
        if (winterEnabled()) {
            return true;
        }

        player.sendMessage(
                PREFIX + "Сначала включи зимний режим."
        );

        return false;
    }

    private void startSnowPass(Player player) {
        int chunks = climate.coverNow(
                number("snow.layers", 1, 64)
        );

        player.sendMessage(
                PREFIX
                        + "Разовое покрытие запущено. Чанков: "
                        + chunks
                        + "."
        );
    }

    // =========================
    // Нажатия в меню
    // =========================

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();

        if (!(top.getHolder() instanceof Menu menu)) {
            return;
        }

        // Запрещаем забирать кнопки и переносить предметы.
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        if (!player.hasPermission("wintercontrol.admin")) {
            return;
        }

        int slot = event.getRawSlot();

        if (slot < 0 || slot >= top.getSize()) {
            return;
        }

        if (!event.isLeftClick() && !event.isRightClick()) {
            return;
        }

        int step = event.isRightClick() ? -1 : 1;

        if (event.isShiftClick()) {
            step *= 10;
        }

        Page nextPage = menu.page;

        if (slot == 49 && menu.page != Page.MAIN) {
            nextPage = Page.MAIN;
        } else {
            switch (menu.page) {
                case MAIN -> {
                    switch (slot) {
                        case 10 -> {
                            toggle("winter.enabled");

                            if (winterEnabled()) {
                                getConfig().set(
                                        "snow.paused",
                                        false
                                );

                                player.sendMessage(
                                        PREFIX
                                                + "Зима включена."
                                );
                            } else {
                                player.sendMessage(
                                        PREFIX
                                                + "Зима выключена. "
                                                + "Созданные плагином "
                                                + "снег и лёд будут таять."
                                );
                            }
                        }

                        case 11 -> {
                            toggle("freeze.auto");
                            resetFreezeTimer();
                        }

                        case 12 -> {
                            change(
                                    "freeze.interval-seconds",
                                    step * 10,
                                    1,
                                    86400
                            );

                            resetFreezeTimer();
                        }

                        case 13 -> change(
                                "freeze.duration-seconds",
                                step,
                                1,
                                3600
                        );

                        case 14 -> {
                            if (ticks().isFrozen()) {
                                player.sendMessage(
                                        PREFIX
                                                + "Тики уже остановлены."
                                );
                            } else {
                                freeze(false);
                            }
                        }

                        case 15 -> unfreeze();

                        case 16 -> change(
                                "cold.players-y",
                                step,
                                -64,
                                320
                        );

                        case 19 -> change(
                                "cold.passive-y",
                                step,
                                -64,
                                320
                        );

                        case 20 -> change(
                                "cold.hostile-y",
                                step,
                                -64,
                                320
                        );

                        case 22 -> nextPage = Page.SNOW;

                        case 31 -> nextPage = Page.HEAT;

                        default -> {
                        }
                    }
                }

                case SNOW -> {
                    switch (slot) {
                        case 10 -> change(
                                "snow.layers",
                                step,
                                1,
                                64
                        );

                        case 11 -> {
                            if (requireWinter(player)) {
                                startSnowPass(player);
                            }
                        }

                        case 12 -> {
                            if (requireWinter(player)) {
                                toggle("snow.long");

                                getConfig().set(
                                        "snow.paused",
                                        false
                                );
                            }
                        }

                        case 13 -> {
                            climate.stopSnow();

                            player.sendMessage(
                                    PREFIX
                                            + "Снегопады приостановлены."
                            );
                        }

                        case 14 -> change(
                                "snow.max-layers",
                                step,
                                1,
                                64
                        );

                        case 15 -> change(
                                "snow.interval-seconds",
                                step * 10,
                                1,
                                86400
                        );

                        case 16 -> change(
                                "snow.duration-seconds",
                                step * 5,
                                1,
                                3600
                        );

                        case 19 -> change(
                                "snow.particles-per-step",
                                step * 10,
                                0,
                                500
                        );

                        case 22 -> {
                            if (requireWinter(player)) {
                                getConfig().set(
                                        "snow.long",
                                        false
                                );

                                getConfig().set(
                                        "snow.paused",
                                        false
                                );

                                player.sendMessage(
                                        PREFIX
                                                + "Периодические "
                                                + "снегопады разрешены."
                                );
                            }
                        }

                        default -> {
                        }
                    }
                }

                case HEAT -> {
                    int index = slot - 10;

                    if (index >= 0
                            && index < HEAT_SETTINGS.size()) {
                        HeatSetting setting =
                                HEAT_SETTINGS.get(index);

                        change(
                                "heat." + setting.key(),
                                step,
                                0,
                                8
                        );

                        // WinterClimate сам перечитает радиусы.
                    }
                }
            }
        }

        saveConfig();

        Page target = nextPage;

        // Открываем новое меню после обработки события.
        Bukkit.getScheduler().runTask(this, () -> {
            if (player.isOnline()
                    && player.hasPermission("wintercontrol.admin")
                    && player.getOpenInventory()
                            .getTopInventory() == top) {
                openMenu(player, target);
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView()
                .getTopInventory()
                .getHolder() instanceof Menu) {
            event.setCancelled(true);
        }
    }

    // =========================
    // Зимние варианты мобов
    // =========================

    @EventHandler(
            priority = EventPriority.HIGH,
            ignoreCancelled = true
    )
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
                    event.getLocation(),
                    Stray.class
            );
        } else if (event.getEntity() instanceof Fox fox) {
            fox.setFoxType(Fox.Type.SNOW);
        }

        // Криперы остаются обычными.
    }

    // =========================
    // Контроль погоды и снега
    // =========================

    @EventHandler(ignoreCancelled = true)
    public void onWeather(WeatherChangeEvent event) {
        if (winterEnabled()
                && winterWorld(event.getWorld())
                && event.toWeatherState()) {
            // Вместо ванильного дождя используется
            // снегопад из WinterClimate.
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFade(BlockFadeEvent event) {
        if (!winterEnabled()
                || !winterWorld(event.getBlock().getWorld())) {
            return;
        }

        Material material = event.getBlock().getType();

        if (material == Material.SNOW
                || material == Material.SNOW_BLOCK
                || material == Material.ICE) {
            // Во время зимы таянием управляет WinterClimate.
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onForm(BlockFormEvent event) {
        if (!winterEnabled()
                || !winterWorld(event.getBlock().getWorld())) {
            return;
        }

        Material result = event.getNewState().getType();

        if (result == Material.SNOW || result == Material.ICE) {
            // Образованием снега и льда управляет WinterClimate.
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
}
