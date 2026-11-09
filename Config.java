package pl.autokopanie;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("autokopanie.json");

    /** Zasieg kopania w blokach (vanilla max ok. 4.5). */
    public double reach = 4.5;
    /** Losowe opoznienie przed wpisaniem /kopanie <kod> (ms). */
    public int minDelayMs = 700;
    public int maxDelayMs = 1600;
    /** Po wpisaniu komendy kopanie wznawia sie po tylu ms. */
    public int resumeAfterMs = 1500;

    /** Chodzenie wokol generatora, gdy w zasiegu nie ma juz blokow (po obwodzie, bez skakania). Wylaczysz: /ak walk */
    public boolean sweep = true;
    /** Automatyczne wykrywanie generatora (bez zaznaczania rogow). Wylaczysz/wlaczysz: /ak auto */
    public boolean auto = true;
    /** Tryb szybki (/ak fast): szybsza glowa i brak pauz - kopie szybciej, ale wieksze ryzyko bana. */
    public boolean fast = false;
    /** Mod chodzi tylko, gdy stoisz maks. tyle blokow od krawedzi generatora. */
    public int walkMargin = 3;

    /** Maks. obrot glowy w stopniach na tick (20 tickow/s). Mniej = plynniej i bezpieczniej. */
    public float maxTurnSpeed = 6.0f;
    /** Pauza "na reakcje" po wybraniu nowego bloku, zanim zacznie kopac (ms). */
    public int reactionMinMs = 80;
    public int reactionMaxMs = 220;
    /** Na ile sekund ignorowac blok, ktorego serwer nie pozwolil zniszczyc albo ktory sie nie kopie. */
    public int ignoreSeconds = 8;

    /** Tylko te bloki beda kopane (drewno z netheru + brzoza). */
    public List<String> blocks = new ArrayList<>(List.of(
            "minecraft:crimson_stem",
            "minecraft:warped_stem",
            "minecraft:crimson_hyphae",
            "minecraft:warped_hyphae",
            "minecraft:stripped_crimson_stem",
            "minecraft:stripped_warped_stem",
            "minecraft:stripped_crimson_hyphae",
            "minecraft:stripped_warped_hyphae",
            "minecraft:crimson_planks",
            "minecraft:warped_planks",
            "minecraft:birch_log",
            "minecraft:birch_wood",
            "minecraft:stripped_birch_log",
            "minecraft:stripped_birch_wood",
            "minecraft:birch_planks"
    ));

    /** Czy do listy blokow dodano juz zwykle drewno (dab, swierk itd.) - dodajemy raz, takze do starych configow. */
    public boolean woodsAdded = false;

    private static final String[] EXTRA_WOODS = {
            "minecraft:oak_log", "minecraft:oak_wood", "minecraft:stripped_oak_log", "minecraft:stripped_oak_wood",
            "minecraft:spruce_log", "minecraft:spruce_wood", "minecraft:stripped_spruce_log", "minecraft:stripped_spruce_wood",
            "minecraft:dark_oak_log", "minecraft:dark_oak_wood", "minecraft:stripped_dark_oak_log", "minecraft:stripped_dark_oak_wood",
            "minecraft:jungle_log", "minecraft:jungle_wood", "minecraft:stripped_jungle_log", "minecraft:stripped_jungle_wood",
            "minecraft:acacia_log", "minecraft:acacia_wood", "minecraft:stripped_acacia_log", "minecraft:stripped_acacia_wood",
            "minecraft:mangrove_log", "minecraft:mangrove_wood", "minecraft:stripped_mangrove_log", "minecraft:stripped_mangrove_wood",
            "minecraft:cherry_log", "minecraft:cherry_wood", "minecraft:stripped_cherry_log", "minecraft:stripped_cherry_wood"
    };

    private void addExtraWoods() {
        for (String id : EXTRA_WOODS) {
            if (!blocks.contains(id)) blocks.add(id);
        }
        woodsAdded = true;
        blockSet = null;
    }

    public Region gen1 = new Region();
    public Region gen2 = new Region();

    public static class Region {
        public int[] a;
        public int[] b;
        /** true = Y dokladnie z rogow (generator wykryty automatycznie); false = Y rozszerzone (zaznaczony recznie). */
        public boolean exact = false;

        public boolean isSet() {
            return a != null && b != null;
        }

        public int minX() { return Math.min(a[0], b[0]); }
        public int maxX() { return Math.max(a[0], b[0]); }
        // zaznaczony recznie: Y rozszerzone (3 w dol, 10 w gore - wysokie generatory)
        public int minY() { return Math.min(a[1], b[1]) - (exact ? 0 : 3); }
        public int maxY() { return Math.max(a[1], b[1]) + (exact ? 0 : 10); }
        public int minZ() { return Math.min(a[2], b[2]); }
        public int maxZ() { return Math.max(a[2], b[2]); }
    }

    private transient Set<String> blockSet;

    public boolean isTarget(String id) {
        if (blockSet == null) blockSet = new HashSet<>(blocks);
        return blockSet.contains(id);
    }

    /** Dodaje blok do listy kopanych. Zwraca false, jesli juz byl. */
    public boolean addBlock(String id) {
        if (blocks.contains(id)) return false;
        blocks.add(id);
        blockSet = null;
        save();
        return true;
    }

    /** Usuwa blok z listy kopanych. Zwraca false, jesli go nie bylo. */
    public boolean removeBlock(String id) {
        boolean removed = blocks.remove(id);
        blockSet = null;
        if (removed) save();
        return removed;
    }

    public static Config load() {
        try {
            if (Files.exists(FILE)) {
                Config c = GSON.fromJson(Files.readString(FILE), Config.class);
                if (c != null) {
                    if (c.gen1 == null) c.gen1 = new Region();
                    if (c.gen2 == null) c.gen2 = new Region();
                    if (c.blocks == null) c.blocks = new Config().blocks;
                    if (!c.woodsAdded) {
                        c.addExtraWoods();
                        c.save();
                    }
                    return c;
                }
            }
        } catch (Exception e) {
            System.err.println("[AutoKopanie] Nie udalo sie wczytac configu: " + e);
        }
        Config c = new Config();
        c.addExtraWoods();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(this));
        } catch (IOException e) {
            System.err.println("[AutoKopanie] Nie udalo sie zapisac configu: " + e);
        }
    }
}
