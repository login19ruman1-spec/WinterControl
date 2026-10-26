package ru.wintercontrol;

import org.bukkit.configuration.file.YamlConfiguration;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.CRC32;

public final class WinterStorage {

    private static final int MAGIC = 0x57494E32;
    private static final int VERSION = 2;

    private static final int MAX_RECORD_BYTES = 8192;
    private static final int MAX_RECORDS_PER_CHUNK = 16384;
    private static final int MAX_LEGACY_RECORD_CHARS = 65536;

    private final Path pluginDirectory;
    private final Path directory;

    /*
     * Здесь хранятся только короткие контрольные суммы
     * обрабатываемых чанков, а не всё состояние мира.
     */
    private final Map<Key, byte[]> signatures = new HashMap<>();

    public WinterStorage(Path pluginDirectory) {
        this.pluginDirectory = pluginDirectory;
        this.directory = pluginDirectory.resolve("chunk-state-v2");
    }

    public record Key(UUID world, int x, int z) {
    }

    public record Row(
            int type,
            int x,
            int y,
            int z,
            double height,
            int layers,
            boolean visual,
            int requested,
            String original
    ) {
        public static Row snow(
                int x,
                int y,
                int z,
                double height,
                int layers,
                boolean visual,
                int requested
        ) {
            return new Row(
                    1, x, y, z, height,
                    layers, visual, requested, ""
            );
        }

        public static Row ice(
                int x,
                int y,
                int z,
                String original
        ) {
            return new Row(
                    2, x, y, z, 0,
                    0, false, 0, original
            );
        }
    }

    /*
     * Вызывать до запуска климатического обработчика.
     * Метод можно выполнять в фоновом потоке:
     * здесь нет операций с миром Minecraft.
     */
    public void prepare() throws Exception {
        Files.createDirectories(pluginDirectory);

        if (Files.exists(directory)) {
            checkFormat(directory);
            return;
        }

        Path staging = pluginDirectory.resolve(
                "chunk-state-v2-building"
        );

        /*
         * После прерванного переноса промежуточная папка
         * создаётся заново. Исходный YAML не изменяется.
         */
        if (Files.exists(staging)) {
            deleteTree(staging);
        }

        Files.createDirectories(staging);

        Path legacy = pluginDirectory.resolve(
                "winter-state.yml"
        );

        try {
            if (Files.exists(legacy)) {
                migrateLegacy(legacy, staging);
            } else {
                YamlConfiguration weather =
                        new YamlConfiguration();

                weather.set("weather", new ArrayList<>());
                weather.save(
                        staging.resolve("weather.yml").toFile()
                );
            }

            Files.writeString(
                    staging.resolve("format.txt"),
                    Integer.toString(VERSION),
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW
            );

            try {
                Files.move(
                        staging,
                        directory,
                        StandardCopyOption.ATOMIC_MOVE
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(staging, directory);
            }
        } catch (Exception exception) {
            /*
             * Не создаём готовую папку при ошибке переноса.
             * Исходный winter-state.yml остаётся на месте.
             */
            throw new IOException(
                    "Не удалось подготовить новое хранилище. "
                            + "Исходный файл состояния сохранён.",
                    exception
            );
        }
    }

    private void checkFormat(Path root) throws IOException {
        Path marker = root.resolve("format.txt");

        if (!Files.isRegularFile(marker)) {
            throw new IOException(
                    "У хранилища отсутствует отметка "
                            + "завершённого переноса"
            );
        }

        String version = Files.readString(
                marker, StandardCharsets.UTF_8
        ).trim();

        if (!version.equals(Integer.toString(VERSION))) {
            throw new IOException(
                    "Неизвестная версия хранилища"
            );
        }
    }

    private Path chunkFile(Path root, Key key) {
        return root.resolve(key.world().toString())
                .resolve(key.x() + "_" + key.z() + ".bin");
    }

    public synchronized List<Row> read(Key key)
            throws Exception {
        Path file = chunkFile(directory, key);

        if (!Files.exists(file)) {
            return new ArrayList<>();
        }

        List<Row> result = new ArrayList<>();

        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(
                        Files.newInputStream(file)
                )
        )) {
            if (input.readInt() != MAGIC
                    || input.readInt() != VERSION) {
                throw new IOException(
                        "Неверный заголовок состояния чанка"
                );
            }

            while (true) {
                /*
                 * Первый байт читаем отдельно:
                 * отличаем нормальный конец файла
                 * от оборванной записи.
                 */
                int first = input.read();

                if (first == -1) {
                    break;
                }

                int length = (first << 24)
                        | (input.readUnsignedByte() << 16)
                        | (input.readUnsignedByte() << 8)
                        | input.readUnsignedByte();

                if (length <= 0
                        || length > MAX_RECORD_BYTES) {
                    throw new IOException(
                            "Неверная длина записи состояния"
                    );
                }

                int expectedCrc = input.readInt();

                byte[] bytes = input.readNBytes(length);

                if (bytes.length != length) {
                    throw new EOFException(
                            "Запись состояния оборвана"
                    );
                }

                CRC32 crc = new CRC32();
                crc.update(bytes);

                if ((int) crc.getValue() != expectedCrc) {
                    throw new IOException(
                            "Не совпадает контрольная сумма"
                    );
                }

                Row row = decode(bytes);

                if ((row.x() >> 4) != key.x()
                        || (row.z() >> 4) != key.z()) {
                    throw new IOException(
                            "Запись относится к другому чанку"
                    );
                }

                result.add(row);

                if (result.size() > MAX_RECORDS_PER_CHUNK) {
                    throw new IOException(
                            "В чанке слишком много записей. "
                                    + "Автоматическая загрузка остановлена"
                    );
                }
            }
        }

        return result;
    }

    public synchronized void save(
            Key key,
            List<Row> rows
    ) throws Exception {
        if (rows.size() > MAX_RECORDS_PER_CHUNK) {
            throw new IOException(
                    "Превышен безопасный размер состояния чанка"
            );
        }

        Path file = chunkFile(directory, key);

        if (rows.isEmpty()) {
            Files.deleteIfExists(file);
            signatures.remove(key);
            return;
        }

        ByteArrayOutputStream buffer =
                new ByteArrayOutputStream();

        try (DataOutputStream output =
                     new DataOutputStream(buffer)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);

            for (Row row : rows) {
                writeRecord(output, row);
            }
        }

        /*
         * Здесь сериализован только один чанк.
         * Весь мир в этот массив не собирается.
         */
        byte[] bytes = buffer.toByteArray();
        byte[] signature = MessageDigest
                .getInstance("SHA-256")
                .digest(bytes);

        if (Arrays.equals(
                signatures.get(key), signature
        ) && Files.exists(file)) {
            return;
        }

        atomicWrite(file, bytes);
        signatures.put(key, signature);
    }

    public synchronized void forget(Key key) {
        signatures.remove(key);
    }

    public YamlConfiguration readWeather()
            throws Exception {
        YamlConfiguration yaml =
                new YamlConfiguration();

        Path file = directory.resolve("weather.yml");

        if (Files.exists(file)) {
            yaml.load(file.toFile());
        }

        return yaml;
    }

    public void saveWeather(
            YamlConfiguration yaml
    ) throws IOException {
        byte[] bytes = yaml.saveToString().getBytes(
                StandardCharsets.UTF_8
        );

        atomicWrite(
                directory.resolve("weather.yml"), bytes
        );
    }

    private static byte[] encode(Row row)
            throws IOException {
        ByteArrayOutputStream buffer =
                new ByteArrayOutputStream();

        try (DataOutputStream output =
                     new DataOutputStream(buffer)) {
            output.writeByte(row.type());
            output.writeInt(row.x());
            output.writeInt(row.y());
            output.writeInt(row.z());

            if (row.type() == 1) {
                output.writeDouble(row.height());
                output.writeByte(row.layers());
                output.writeBoolean(row.visual());
                output.writeByte(row.requested());
            } else if (row.type() == 2) {
                output.writeUTF(row.original());
            } else {
                throw new IOException(
                        "Неизвестный вид записи"
                );
            }
        }

        byte[] bytes = buffer.toByteArray();

        if (bytes.length > MAX_RECORD_BYTES) {
            throw new IOException(
                    "Слишком большая запись состояния"
            );
        }

        return bytes;
    }

    private static Row decode(byte[] bytes)
            throws IOException {
        try (DataInputStream input =
                     new DataInputStream(
                             new ByteArrayInputStream(bytes)
                     )) {
            int type = input.readUnsignedByte();
            int x = input.readInt();
            int y = input.readInt();
            int z = input.readInt();

            Row row;

            if (type == 1) {
                double height = input.readDouble();
                int layers = input.readUnsignedByte();
                boolean visual = input.readBoolean();
                int requested = input.readUnsignedByte();

                if (!Double.isFinite(height)
                        || layers < 1 || layers > 64
                        || requested > 64) {
                    throw new IOException(
                            "Неверные параметры снежного покрытия"
                    );
                }

                row = Row.snow(
                        x, y, z, height,
                        layers, visual, requested
                );
            } else if (type == 2) {
                row = Row.ice(
                        x, y, z, input.readUTF()
                );
            } else {
                throw new IOException(
                        "Неизвестный вид записи"
                );
            }

            if (input.available() != 0) {
                throw new IOException(
                        "Лишние данные в записи состояния"
                );
            }

            return row;
        }
    }

    private static void writeRecord(
            DataOutputStream output,
            Row row
    ) throws IOException {
        byte[] bytes = encode(row);

        CRC32 crc = new CRC32();
        crc.update(bytes);

        output.writeInt(bytes.length);
        output.writeInt((int) crc.getValue());
        output.write(bytes);
    }

    private static void atomicWrite(
            Path destination,
            byte[] bytes
    ) throws IOException {
        Files.createDirectories(destination.getParent());

        Path temporary = Files.createTempFile(
                destination.getParent(),
                "winter-",
                ".tmp"
        );

        try {
            Files.write(temporary, bytes);

            try {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(
                        temporary,
                        destination,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /*
     * Переносчик рассчитан на структуру winter-state.yml,
     * которую записывал предыдущий код:
     * snow, ice и weather со списками плоских записей.
     *
     * Неизвестную структуру он не пропускает молча:
     * перенос остановится, исходник останется нетронутым.
     */
    private void migrateLegacy(
            Path source,
            Path destination
    ) throws Exception {
        List<Map<?, ?>> weatherRows = new ArrayList<>();

        Set<String> sections = new HashSet<>();
        String section = null;
        StringBuilder record = new StringBuilder();

        try (
                LegacyWriters writers =
                        new LegacyWriters(destination);
                BufferedReader reader =
                        Files.newBufferedReader(
                                source, StandardCharsets.UTF_8
                        )
        ) {
            String line;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();

                if (trimmed.isEmpty()
                        || trimmed.startsWith("#")) {
                    continue;
                }

                boolean root = !Character.isWhitespace(
                        line.charAt(0)
                ) && !line.startsWith("-");

                if (root) {
                    importRecord(
                            section, record,
                            writers, weatherRows
                    );

                    int colon = line.indexOf(':');

                    if (colon <= 0) {
                        throw new IOException(
                                "Неизвестная строка старого состояния"
                        );
                    }

                    String name = line.substring(0, colon);
                    String suffix =
                            line.substring(colon + 1).trim();

                    if (!Set.of(
                            "snow", "ice", "weather"
                    ).contains(name)) {
                        throw new IOException(
                                "Неизвестный раздел старого состояния: "
                                        + name
                        );
                    }

                    if (!suffix.isEmpty()
                            && !suffix.equals("[]")) {
                        throw new IOException(
                                "Неподдерживаемая структура раздела "
                                        + name
                        );
                    }

                    if (!sections.add(name)) {
                        throw new IOException(
                                "Раздел состояния повторяется: " + name
                        );
                    }

                    section = name;
                    continue;
                }

                if (section == null) {
                    throw new IOException(
                            "Запись без раздела состояния"
                    );
                }

                if (trimmed.startsWith("- ")) {
                    importRecord(
                            section, record,
                            writers, weatherRows
                    );
                }

                record.append(line).append('\n');

                if (record.length()
                        > MAX_LEGACY_RECORD_CHARS) {
                    throw new IOException(
                            "Слишком большая запись старого состояния"
                    );
                }
            }

            importRecord(
                    section, record,
                    writers, weatherRows
            );
        }

        if (!sections.contains("snow")
                || !sections.contains("ice")) {
            throw new IOException(
                    "В старом состоянии не найдены "
                            + "ожидаемые разделы snow и ice"
            );
        }

        YamlConfiguration weather =
                new YamlConfiguration();

        weather.set("weather", weatherRows);
        weather.save(
                destination.resolve("weather.yml").toFile()
        );
    }

    private void importRecord(
            String section,
            StringBuilder text,
            LegacyWriters writers,
            List<Map<?, ?>> weatherRows
    ) throws Exception {
        if (text.isEmpty()) {
            return;
        }

        if (section == null) {
            throw new IOException(
                    "Запись без названия раздела"
            );
        }

        YamlConfiguration yaml =
                new YamlConfiguration();

        /*
         * Разбирается только одна короткая запись,
         * а не весь старый YAML.
         */
        yaml.loadFromString("rows:\n" + text);
        text.setLength(0);

        List<Map<?, ?>> rows = yaml.getMapList("rows");

        if (rows.size() != 1) {
            throw new IOException(
                    "Не удалось разобрать запись старого состояния"
            );
        }

        Map<?, ?> row = rows.get(0);

        UUID world = UUID.fromString(
                required(row, "world").toString()
        );

        if (section.equals("weather")) {
            if (weatherRows.size() >= 1024) {
                throw new IOException(
                        "Неожиданно много записей погоды"
                );
            }

            weatherRows.add(row);
            return;
        }

        int x = integer(row, "x");
        int z = integer(row, "z");
        Row converted;

        if (section.equals("snow")) {
            int y = integer(row, "base-y");
            int layers = integer(row, "layers");
            int requested = row.containsKey("requested")
                    ? integer(row, "requested") : 0;

            double height = ((Number) required(
                    row, "height"
            )).doubleValue();

            if (layers < 1 || layers > 64
                    || requested < 0 || requested > 64
                    || !Double.isFinite(height)) {
                throw new IOException(
                        "Неверные параметры старого снега"
                );
            }

            converted = Row.snow(
                    x, y, z, height, layers,
                    Boolean.TRUE.equals(row.get("visual")),
                    requested
            );
        } else if (section.equals("ice")) {
            converted = Row.ice(
                    x, integer(row, "y"), z,
                    required(row, "original").toString()
            );
        } else {
            throw new IOException(
                    "Неизвестный раздел состояния"
            );
        }

        writers.append(
                new Key(world, x >> 4, z >> 4),
                converted
        );
    }

    private static Object required(
            Map<?, ?> row,
            String key
    ) throws IOException {
        Object value = row.get(key);

        if (value == null) {
            throw new IOException(
                    "В старой записи отсутствует поле " + key
            );
        }

        return value;
    }

    private static int integer(
            Map<?, ?> row,
            String key
    ) throws IOException {
        Object value = required(row, key);

        if (!(value instanceof Number number)) {
            throw new IOException(
                    "Ожидалось число в поле " + key
            );
        }

        return number.intValue();
    }

    private final class LegacyWriters implements AutoCloseable {
        private final Path root;

        /*
         * Ограничиваем количество одновременно
         * открытых файлов во время переноса.
         */
        private final LinkedHashMap<Key, DataOutputStream>
                streams = new LinkedHashMap<>(
                        16, 0.75f, true
                );

        private LegacyWriters(Path root) {
            this.root = root;
        }

        void append(Key key, Row row) throws IOException {
            DataOutputStream output = streams.get(key);

            if (output == null) {
                if (streams.size() >= 16) {
                    Iterator<Map.Entry<Key, DataOutputStream>>
                            iterator =
                            streams.entrySet().iterator();

                    Map.Entry<Key, DataOutputStream> oldest =
                            iterator.next();

                    oldest.getValue().close();
                    iterator.remove();
                }

                Path file = chunkFile(root, key);
                Files.createDirectories(file.getParent());

                boolean fresh = !Files.exists(file);

                output = new DataOutputStream(
                        new BufferedOutputStream(
                                Files.newOutputStream(
                                        file,
                                        StandardOpenOption.CREATE,
                                        StandardOpenOption.APPEND
                                )
                        )
                );

                if (fresh) {
                    output.writeInt(MAGIC);
                    output.writeInt(VERSION);
                }

                streams.put(key, output);
            }

            writeRecord(output, row);
        }

        @Override
        public void close() throws IOException {
            IOException failure = null;

            for (DataOutputStream output : streams.values()) {
                try {
                    output.close();
                } catch (IOException exception) {
                    failure = exception;
                }
            }

            streams.clear();

            if (failure != null) {
                throw failure;
            }
        }
    }

    private static void deleteTree(Path path)
            throws IOException {
        if (Files.isDirectory(
                path, LinkOption.NOFOLLOW_LINKS
        )) {
            try (DirectoryStream<Path> children =
                         Files.newDirectoryStream(path)) {
                for (Path child : children) {
                    deleteTree(child);
                }
            }
        }

        Files.deleteIfExists(path);
    }
}
