package pl.autokopanie;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.block.Block;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.util.InputUtil;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import org.lwjgl.glfw.GLFW;

import java.text.Normalizer;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AutoKopanie implements ClientModInitializer {

    public static final Config CONFIG = Config.load();

    /** Napis typu "/kopanie abc123" - ukosnik wymagany, zeby nie lapac zwyklych zdan ze slowem "kopanie". */
    private static final Pattern CODE_PATTERN = Pattern.compile("/kopanie\\s+(\\S+)", Pattern.CASE_INSENSITIVE);

    /** Male kapitaliki uzywane na serwerze do ladnego wygladu kodu -> zwykle litery a-z. */
    private static final String SMALL_CAPS =
            "\u1D00\u0299\u1D04\u1D05\u1D07\uA730\u0262\u029C\u026A\u1D0A\u1D0B\u029F\u1D0D"
          + "\u0274\u1D0F\u1D18\u01EB\u0280\uA731\u1D1B\u1D1C\u1D20\u1D21x\u028F\u1D22";

    private static volatile boolean enabled = false;
    private static volatile boolean debug = false;
    private static volatile String pendingCode = null;
    private static volatile long sendAt = 0;
    private static volatile long pausedUntil = 0;
    private static String lastCode = null;
    private static long lastCodeTime = 0;
    private static boolean walking = false;

    // ---- aktualny cel kopania (staly, zeby glowa nie skakala miedzy blokami)
    private static BlockPos target = null;
    private static Vec3d aimPoint = null;
    private static long targetSince = 0;
    private static long reactionUntil = 0;
    private static int blockedTicks = 0;

    // ---- czekanie na regeneracje i chodzenie wokol generatora
    private static boolean waiting = false;
    private static long lastScan = 0;
    private static int remainingCache = 0;
    private static long lastBreak = 0;
    private static int walkStuck = 0;
    private static BlockPos planTarget = null;
    private static Config.Region planRegion = null;
    private static long lastPlan = 0;

    // ---- automatyczne wykrywanie generatora
    private static Config.Region autoRegion = null;
    private static long lastBoxScan = 0;
    private static long lastBoxBuild = 0;
    private static long lastHint = 0;

    /** Cache: czy dany rodzaj bloku jest na liscie kopanych (szybkie sprawdzanie). */
    private static final Map<Block, Boolean> targetCache = new HashMap<>();

    /** Bloki, ktorych nie probujemy kopac (serwer pisze "Nie mozesz zniszczyc tego bloku" albo sie nie kopie). */
    private static final Map<BlockPos, Long> ignored = new HashMap<>();
    /** Czasy ostatnich odmow serwera - przy zbyt wielu mod sam sie wylacza (spam odmow = ban). */
    private static final ArrayDeque<Long> denials = new ArrayDeque<>();

    private static KeyBinding toggleKey;

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.autokopanie.toggle", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_8, "category.autokopanie"));

        ClientTickEvents.END_CLIENT_TICK.register(AutoKopanie::tick);

        // wiadomosci z czatu i actionbar
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onText(message));
        ClientReceiveMessageEvents.CHAT.register((message, signed, sender, params, ts) -> onText(message));

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            for (String root : new String[]{"autokopanie", "ak"}) {
                LiteralArgumentBuilder<FabricClientCommandSource> node = ClientCommandManager.literal(root);

                for (int g = 1; g <= 2; g++) {
                    final int gen = g;
                    node.then(ClientCommandManager.literal("gen" + g)
                            .then(ClientCommandManager.literal("a").executes(ctx -> {
                                setCorner(gen, true);
                                return 1;
                            }))
                            .then(ClientCommandManager.literal("b").executes(ctx -> {
                                setCorner(gen, false);
                                return 1;
                            })));
                }

                node.then(ClientCommandManager.literal("start").executes(ctx -> {
                    setEnabled(true);
                    return 1;
                }));
                node.then(ClientCommandManager.literal("stop").executes(ctx -> {
                    setEnabled(false);
                    return 1;
                }));
                node.then(ClientCommandManager.literal("toggle").executes(ctx -> {
                    setEnabled(!enabled);
                    return 1;
                }));
                node.then(ClientCommandManager.literal("walk").executes(ctx -> {
                    CONFIG.sweep = !CONFIG.sweep;
                    CONFIG.save();
                    msg("Chodzenie wokol generatora: " + (CONFIG.sweep ? "WLACZONE" : "WYLACZONE"));
                    return 1;
                }));
                node.then(ClientCommandManager.literal("fast").executes(ctx -> {
                    CONFIG.fast = !CONFIG.fast;
                    CONFIG.save();
                    msg("Tryb szybki: " + (CONFIG.fast ? "WLACZONY (szybsza glowa - wieksze ryzyko bana)" : "WYLACZONY"));
                    return 1;
                }));
                node.then(ClientCommandManager.literal("add").executes(ctx -> {
                    String id = lookedBlockId();
                    if (id == null) {
                        msg("Spojrz na blok generatora (celownik na bloku) i wpisz /ak add.");
                    } else if (CONFIG.addBlock(id)) {
                        targetCache.clear();
                        msg("Dodano do kopanych: " + id);
                    } else {
                        msg("Ten blok juz jest na liscie: " + id);
                    }
                    return 1;
                }));
                node.then(ClientCommandManager.literal("remove").executes(ctx -> {
                    String id = lookedBlockId();
                    if (id == null) {
                        msg("Spojrz na blok (celownik na bloku) i wpisz /ak remove.");
                    } else if (CONFIG.removeBlock(id)) {
                        targetCache.clear();
                        msg("Usunieto z kopanych: " + id);
                    } else {
                        msg("Tego bloku nie ma na liscie: " + id);
                    }
                    return 1;
                }));
                node.then(ClientCommandManager.literal("auto").executes(ctx -> {
                    CONFIG.auto = !CONFIG.auto;
                    CONFIG.save();
                    autoRegion = null;
                    msg(CONFIG.auto ? "Tryb AUTO: mod sam wykrywa generator (nie trzeba zaznaczac rogow)."
                            : "Tryb RECZNY: generator zaznaczasz komendami /ak gen1 a, /ak gen1 b itd.");
                    return 1;
                }));
                node.then(ClientCommandManager.literal("scan").executes(ctx -> {
                    MinecraftClient mc = MinecraftClient.getInstance();
                    if (mc.world == null || mc.player == null) return 0;
                    lastScan = 0;
                    int rem = countRemaining(mc, System.currentTimeMillis());
                    StringBuilder sb = new StringBuilder();
                    sb.append("tryb: ").append(CONFIG.auto ? "AUTO" : "RECZNY");
                    int idx = 0;
                    for (Config.Region r : regions()) {
                        if (!r.isSet()) continue;
                        idx++;
                        sb.append(" | generator ").append(idx).append(": x ").append(r.minX()).append("..").append(r.maxX())
                          .append(" y ").append(r.minY()).append("..").append(r.maxY())
                          .append(" z ").append(r.minZ()).append("..").append(r.maxZ());
                    }
                    if (idx == 0) sb.append(" | generator: BRAK");
                    String look = lookedBlockId();
                    sb.append(" | blokow do skopania: ").append(rem)
                      .append(" | patrzysz na: ").append(look == null ? "-" : look + (CONFIG.isTarget(look) ? " (na liscie)" : " (NIE na liscie)"))
                      .append(" | ignorowane: ").append(ignored.size());
                    msg(sb.toString());
                    return 1;
                }));
                node.then(ClientCommandManager.literal("blocks").executes(ctx -> {
                    msg("Kopane bloki: " + String.join(", ", CONFIG.blocks));
                    return 1;
                }));
                node.then(ClientCommandManager.literal("debug").executes(ctx -> {
                    debug = !debug;
                    msg("Debug kodu: " + (debug ? "WLACZONY (pokaze co wykryl i co wpisal)" : "WYLACZONY"));
                    return 1;
                }));
                node.then(ClientCommandManager.literal("clear").executes(ctx -> {
                    CONFIG.gen1 = new Config.Region();
                    CONFIG.gen2 = new Config.Region();
                    CONFIG.save();
                    msg("Wyczyszczono oba generatory.");
                    return 1;
                }));
                node.then(ClientCommandManager.literal("status").executes(ctx -> {
                    msg("Wlaczony: " + (enabled ? "TAK" : "NIE")
                            + " | gen1: " + (CONFIG.gen1.isSet() ? "ustawiony" : "BRAK")
                            + " | gen2: " + (CONFIG.gen2.isSet() ? "ustawiony" : "BRAK")
                            + " | tryb: " + (CONFIG.auto ? "AUTO" : "RECZNY")
                            + " | chodzenie: " + (CONFIG.sweep ? "TAK" : "NIE")
                            + " | szybki: " + (CONFIG.fast ? "TAK" : "NIE")
                            + " | ignorowane bloki: " + ignored.size());
                    return 1;
                }));

                dispatcher.register(node);
            }
        });
    }

    // ------------------------------------------------------------------ tekst / kod

    /** Zamienia kod z ekranu na dokladnie to, co trzeba wpisac (male kapitaliki, pelnoszerokie znaki itp. -> zwykle znaki). */
    static String normalizeCode(String raw) {
        String s = Normalizer.normalize(raw, Normalizer.Form.NFKC);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); ) {
            int cp = s.codePointAt(i);
            i += Character.charCount(cp);
            // niewidzialne znaki pomijamy
            if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0x2060 || cp == 0xFEFF || cp == 0x00AD) continue;
            int idx = cp < 0x10000 ? SMALL_CAPS.indexOf((char) cp) : -1;
            if (idx >= 0) {
                sb.append((char) ('a' + idx));
            } else {
                sb.appendCodePoint(cp);
            }
        }
        return sb.toString();
    }

    private static String describe(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (cp < 128) sb.append((char) cp);
            else sb.append("[U+").append(Integer.toHexString(cp).toUpperCase()).append("]");
        });
        return sb.toString();
    }

    public static void onText(Text text) {
        if (text == null) return;
        String s = Formatting.strip(text.getString());
        if (s == null || s.isEmpty()) return;

        // serwer odmowil zniszczenia bloku -> nie probujemy go dalej (inaczej spam w czacie i ban)
        String low = s.toLowerCase();
        if (low.contains("zniszczy") && low.contains("bloku")) {
            onDenied();
            return;
        }

        Matcher m = CODE_PATTERN.matcher(s);
        if (!m.find()) return;

        String raw = m.group(1);
        String code = normalizeCode(raw).replaceAll("[\\p{Punct}&&[^_\\-]]+$", "");
        if (code.isEmpty()) return;

        long now = System.currentTimeMillis();
        // ten sam kod z title/actionbar/czatu moze przyjsc kilka razy - bierzemy raz
        if (code.equals(lastCode) && now - lastCodeTime < 4000) return;
        lastCode = code;
        lastCodeTime = now;

        if (debug) {
            msg("Wykryto: " + describe(raw) + "  ->  wpisze: /kopanie " + code);
        }

        int min = Math.min(CONFIG.minDelayMs, CONFIG.maxDelayMs);
        int max = Math.max(CONFIG.minDelayMs, CONFIG.maxDelayMs);
        int delay = min + (max > min ? ThreadLocalRandom.current().nextInt(max - min + 1) : 0);

        pendingCode = code;
        sendAt = now + delay;
    }

    private static void onDenied() {
        long now = System.currentTimeMillis();
        if (target != null) {
            ignored.put(target, now + CONFIG.ignoreSeconds * 1000L);
            target = null;
            aimPoint = null;
        }
        denials.addLast(now);
        while (!denials.isEmpty() && now - denials.peekFirst() > 6000) denials.pollFirst();
        if (denials.size() >= 6 && enabled) {
            denials.clear();
            setEnabled(false);
            msg("Serwer odrzuca kopanie (\"Nie mozesz zniszczyc tego bloku\") - wylaczam mod. Sprawdz, czy generatory sa ustawione na wlasciwych blokach.");
        }
    }

    // ------------------------------------------------------------------ tick

    private static void tick(MinecraftClient mc) {
        while (toggleKey.wasPressed()) {
            setEnabled(!enabled);
        }

        ClientPlayerEntity player = mc.player;
        if (player == null || mc.world == null || mc.interactionManager == null) {
            walking = false;
            target = null;
            return;
        }

        long now = System.currentTimeMillis();

        // wpisanie /kopanie <kod> (dziala tez, gdy kopanie jest wylaczone - to tylko odpowiedz na weryfikacje)
        String code = pendingCode;
        if (code != null && now >= sendAt && mc.getNetworkHandler() != null) {
            pendingCode = null;
            stopWalking(mc);
            mc.interactionManager.cancelBlockBreaking();
            mc.getNetworkHandler().sendChatCommand("kopanie " + code);
            pausedUntil = now + CONFIG.resumeAfterMs;
            target = null;
            return;
        }

        if (!enabled) {
            stopWalking(mc);
            target = null;
            return;
        }

        // czekamy na wpisanie kodu / chwile po nim - nie kopiemy ani nie chodzimy
        if (pendingCode != null || now < pausedUntil) {
            stopWalking(mc);
            mc.interactionManager.cancelBlockBreaking();
            return;
        }
        if (mc.currentScreen != null) {
            stopWalking(mc);
            return;
        }

        // sprzatanie listy ignorowanych
        if (!ignored.isEmpty()) ignored.values().removeIf(t -> t < now);

        // automatyczne wykrywanie generatora (co ok. 1 s)
        if (CONFIG.auto) refreshAutoBox(mc, player, now);

        Vec3d eye = player.getEyePos();
        double reach = CONFIG.reach;

        // 1) czy aktualny cel jest nadal dobry? (blok istnieje, w zasiegu, nie ignorowany, nie kopie sie za dlugo)
        if (target != null) {
            if (!isTargetBlock(mc, target)) lastBreak = now;
            boolean ok = isTargetBlock(mc, target)
                    && !ignored.containsKey(target)
                    && Vec3d.ofCenter(target).squaredDistanceTo(eye) <= reach * reach
                    && now - targetSince < 15000;
            if (!ok) {
                if (now - targetSince >= 15000) {
                    ignored.put(target, now + CONFIG.ignoreSeconds * 1000L);
                }
                target = null;
                aimPoint = null;
                mc.interactionManager.cancelBlockBreaking();
            }
        }

        // 2) wybierz nowy cel (ten, do ktorego najmniej trzeba obracac glowe)
        if (target == null) {
            pickTarget(mc, player, now);
        }

        if (target == null) {
            mc.interactionManager.cancelBlockBreaking();
            boolean anyRegion = hasRegion();
            if (!anyRegion) {
                stopWalking(mc);
                if (CONFIG.auto && now - lastHint > 10000) {
                    lastHint = now;
                    msg("Nie widze generatora w poblizu (brak blokow z listy w promieniu 12). Podejdz blizej albo wpisz /ak add patrzac na blok generatora.");
                }
                return;
            }
            if (countRemaining(mc, now) == 0) {
                // generator skopany - stoimy i czekamy, az sie odnowi
                stopWalking(mc);
                setWaiting(true, "Generator skopany - stoje i czekam na regeneracje.");
                return;
            }
            if (CONFIG.sweep) {
                boolean moving = walkToNearest(mc, player, now);
                if (!moving && planTarget == null) {
                    setWaiting(true, "Pozostale bloki sa poza zasiegiem - stoje i czekam.");
                } else {
                    setWaiting(false, null);
                }
            } else {
                stopWalking(mc);
            }
            return;
        }

        // cel w zasiegu - stoimy i kopiemy
        setWaiting(false, null);
        walkStuck = 0;
        stopWalking(mc);

        // 3) plynnie obracaj glowe do celu
        float[] err = aimAt(mc, player, aimPoint);

        // krotka "pauza na reakcje" po wybraniu nowego bloku
        if (now < reactionUntil) return;

        // 4) kop tylko, gdy celownik naprawde jest na wybranym bloku
        Vec3d look = player.getRotationVec(1.0f);
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
                eye, eye.add(look.multiply(reach + 0.5)),
                RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, player));

        if (hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(target)) {
            blockedTicks = 0;
            if (mc.interactionManager.updateBlockBreakingProgress(target, hit.getSide())) {
                player.swingHand(Hand.MAIN_HAND);
            }
        } else if (Math.abs(err[0]) < 4f && Math.abs(err[1]) < 4f) {
            // patrzymy w dobra strone, ale cos zaslania blok - po chwili odpuszczamy ten blok
            blockedTicks++;
            if (blockedTicks > 25) {
                ignored.put(target, now + CONFIG.ignoreSeconds * 1000L);
                target = null;
                aimPoint = null;
                blockedTicks = 0;
            }
        }
    }

    // ------------------------------------------------------------------ wybor celu i celowanie

    private static boolean isTargetBlock(MinecraftClient mc, BlockPos pos) {
        Block b = mc.world.getBlockState(pos).getBlock();
        Boolean v = targetCache.get(b);
        if (v == null) {
            v = CONFIG.isTarget(Registries.BLOCK.getId(b).toString());
            targetCache.put(b, v);
        }
        return v;
    }

    /** Obszary generatorow: w trybie AUTO wykryty automatycznie, w RECZNYM zaznaczone komendami. */
    private static Config.Region[] regions() {
        if (CONFIG.auto) {
            return autoRegion != null ? new Config.Region[]{autoRegion} : new Config.Region[0];
        }
        return new Config.Region[]{CONFIG.gen1, CONFIG.gen2};
    }

    private static boolean hasRegion() {
        for (Config.Region r : regions()) {
            if (r.isSet()) return true;
        }
        return false;
    }

    private static void pickTarget(MinecraftClient mc, ClientPlayerEntity player, long now) {
        Vec3d eye = player.getEyePos();
        double reach = CONFIG.reach;
        Vec3d look = player.getRotationVec(1.0f);

        BlockPos best = null;
        Direction bestSide = null;
        double bestScore = Double.MAX_VALUE;

        for (Config.Region r : regions()) {
            if (!r.isSet()) continue;

            int x0 = Math.max(r.minX(), (int) Math.floor(eye.x - reach));
            int x1 = Math.min(r.maxX(), (int) Math.ceil(eye.x + reach));
            int y0 = Math.max(r.minY(), (int) Math.floor(eye.y - reach));
            int y1 = Math.min(r.maxY(), (int) Math.ceil(eye.y + reach));
            int z0 = Math.max(r.minZ(), (int) Math.floor(eye.z - reach));
            int z1 = Math.min(r.maxZ(), (int) Math.ceil(eye.z + reach));

            BlockPos.Mutable pos = new BlockPos.Mutable();
            for (int x = x0; x <= x1; x++) {
                for (int y = y0; y <= y1; y++) {
                    for (int z = z0; z <= z1; z++) {
                        pos.set(x, y, z);
                        if (!isTargetBlock(mc, pos)) continue;
                        if (ignored.containsKey(pos)) continue;

                        Vec3d center = Vec3d.ofCenter(pos);
                        double d2 = center.squaredDistanceTo(eye);
                        if (d2 > reach * reach) continue;

                        BlockHitResult hit = mc.world.raycast(new RaycastContext(
                                eye, center, RaycastContext.ShapeType.OUTLINE,
                                RaycastContext.FluidHandling.NONE, player));
                        if (hit.getType() != HitResult.Type.BLOCK || !hit.getBlockPos().equals(pos)) continue;

                        // wynik = kat miedzy aktualnym kierunkiem patrzenia a blokiem (+ male uwzglednienie dystansu)
                        Vec3d dir = center.subtract(eye).normalize();
                        double dot = MathHelper.clamp(dir.dotProduct(look), -1.0, 1.0);
                        double angle = Math.toDegrees(Math.acos(dot));
                        double score = angle + Math.sqrt(d2) * 2.0;
                        if (score < bestScore) {
                            best = pos.toImmutable();
                            bestSide = hit.getSide();
                            bestScore = score;
                        }
                    }
                }
            }
        }

        if (best == null) return;

        target = best;
        targetSince = now;
        blockedTicks = 0;

        // punkt celowania: troche obok srodka sciany (jak czlowiek), a nie zawsze idealnie w srodek
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        double ox = bestSide.getAxis() == Direction.Axis.X ? 0 : rnd.nextDouble(-0.22, 0.22);
        double oy = bestSide.getAxis() == Direction.Axis.Y ? 0 : rnd.nextDouble(-0.22, 0.22);
        double oz = bestSide.getAxis() == Direction.Axis.Z ? 0 : rnd.nextDouble(-0.22, 0.22);
        aimPoint = Vec3d.ofCenter(best)
                .add(bestSide.getOffsetX() * 0.45, bestSide.getOffsetY() * 0.45, bestSide.getOffsetZ() * 0.45)
                .add(ox, oy, oz);

        // pauza "na reakcje" tylko przy pierwszym bloku po przerwie - kolejne bloki bez przerwy (szybciej)
        int rmin = Math.min(CONFIG.reactionMinMs, CONFIG.reactionMaxMs);
        int rmax = Math.max(CONFIG.reactionMinMs, CONFIG.reactionMaxMs);
        long react = (CONFIG.fast || now - lastBreak < 1200) ? 0 : rmin + (rmax > rmin ? rnd.nextInt(rmax - rmin + 1) : 0);
        reactionUntil = now + react;
    }

    /**
     * Plynnie obraca glowe w strone punktu. Maksymalna predkosc obrotu jest ograniczona, ruch zwalnia przy celu,
     * a kazdy krok jest wielokrotnoscia kroku myszy (GCD), tak jak przy prawdziwym ruchu myszka.
     * Zwraca pozostaly blad {yaw, pitch} w stopniach.
     */
    private static float[] aimAt(MinecraftClient mc, ClientPlayerEntity p, Vec3d point) {
        Vec3d eye = p.getEyePos();
        double dx = point.x - eye.x, dy = point.y - eye.y, dz = point.z - eye.z;
        double h = Math.sqrt(dx * dx + dz * dz);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float targetPitch = (float) -Math.toDegrees(Math.atan2(dy, h));

        double sens = mc.options.getMouseSensitivity().getValue();
        double f = sens * 0.6 + 0.2;
        double gcd = f * f * f * 8.0 * 0.15;

        float dYaw = MathHelper.wrapDegrees(targetYaw - p.getYaw());
        float dPitch = targetPitch - p.getPitch();

        p.setYaw(p.getYaw() + turnStep(dYaw, gcd));
        p.setPitch(MathHelper.clamp(p.getPitch() + turnStep(dPitch, gcd), -90f, 90f));

        return new float[]{MathHelper.wrapDegrees(targetYaw - p.getYaw()), targetPitch - p.getPitch()};
    }

    private static float turnStep(float delta, double gcd) {
        float max = CONFIG.maxTurnSpeed * (CONFIG.fast ? 2.4f : 1.8f) * (0.75f + 0.5f * ThreadLocalRandom.current().nextFloat());
        float step = delta * 0.40f;
        if (Math.abs(step) > max) step = Math.signum(step) * max;

        long n = Math.round(step / gcd);
        if (n == 0 && Math.abs(delta) >= gcd) n = (long) Math.signum(delta);
        return (float) (n * gcd);
    }

    // ------------------------------------------------------------------ czekanie i chodzenie wokol generatora

    private static void setWaiting(boolean w, String text) {
        if (w == waiting) return;
        waiting = w;
        msg(w ? text : "Mozna kopac - kopie dalej.");
    }

    /** Ile blokow do skopania zostalo w obu generatorach (sprawdzane co 250 ms, zeby nie obciazac gry). */
    private static int countRemaining(MinecraftClient mc, long now) {
        if (now - lastScan < 250) return remainingCache;
        lastScan = now;
        int n = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (Config.Region r : regions()) {
            if (!r.isSet()) continue;
            for (int x = r.minX(); x <= r.maxX(); x++) {
                for (int y = r.minY(); y <= r.maxY(); y++) {
                    for (int z = r.minZ(); z <= r.maxZ(); z++) {
                        pos.set(x, y, z);
                        if (isTargetBlock(mc, pos) && !ignored.containsKey(pos)) n++;
                    }
                }
            }
        }
        remainingCache = n;
        return n;
    }

    /** Petla pol dookola generatora (1 blok od krawedzi), po kolei, kazde nastepne pole sasiaduje z poprzednim. */
    private static List<int[]> ringOf(Config.Region r) {
        int x0 = r.minX() - 1, x1 = r.maxX() + 1, z0 = r.minZ() - 1, z1 = r.maxZ() + 1;
        List<int[]> l = new ArrayList<>();
        for (int x = x0; x < x1; x++) l.add(new int[]{x, z0});
        for (int z = z0; z < z1; z++) l.add(new int[]{x1, z});
        for (int x = x1; x > x0; x--) l.add(new int[]{x, z1});
        for (int z = z1; z > z0; z--) l.add(new int[]{x0, z});
        return l;
    }

    private static boolean isExposed(MinecraftClient mc, BlockPos p) {
        for (Direction d : Direction.values()) {
            BlockPos n = p.offset(d);
            if (mc.world.getBlockState(n).getCollisionShape(mc.world, n).isEmpty()) return true;
        }
        return false;
    }

    private static boolean isWalkable(MinecraftClient mc, int x, int y, int z) {
        BlockPos p = new BlockPos(x, y, z);
        return mc.world.getBlockState(p).getCollisionShape(mc.world, p).isEmpty()
                && mc.world.getBlockState(p.up()).getCollisionShape(mc.world, p.up()).isEmpty()
                && !mc.world.getBlockState(p.down()).getCollisionShape(mc.world, p.down()).isEmpty();
    }

    /**
     * Gdy w zasiegu nie ma juz blokow, a gdzies dalej w generatorze jeszcze sa - idziemy dookola generatora
     * (po jego obwodzie, nigdy przez srodek) do miejsca, z ktorego te bloki da sie dosiegnac.
     * Idziemy tylko do blokow, ktore z obwodu naprawde da sie dosiegnac (inaczej stoimy i czekamy).
     * Zwraca true, jesli aktualnie idziemy.
     */
    private static boolean walkToNearest(MinecraftClient mc, ClientPlayerEntity player, long now) {
        double px = player.getX(), pz = player.getZ();
        double reach = CONFIG.reach;

        // plan (cel + generator) liczymy co pol sekundy, zeby nie obciazac gry
        if (now - lastPlan > 500 || planTarget == null || planRegion == null
                || !isTargetBlock(mc, planTarget) || ignored.containsKey(planTarget)) {
            lastPlan = now;
            planTarget = null;
            planRegion = null;
            double eyeY = player.getEyeY();
            double best = Double.MAX_VALUE;

            for (Config.Region r : regions()) {
                if (!r.isSet()) continue;
                List<int[]> ring = ringOf(r);
                BlockPos.Mutable pos = new BlockPos.Mutable();
                for (int x = r.minX(); x <= r.maxX(); x++) {
                    for (int z = r.minZ(); z <= r.maxZ(); z++) {
                        // tylko blizej niz 5 blokow od krawedzi obwodu (dalej i tak nie dosiegniemy)
                        int edge = Math.min(Math.min(x - r.minX(), r.maxX() - x), Math.min(z - r.minZ(), r.maxZ() - z));
                        if (edge > 4) continue;
                        for (int y = r.minY(); y <= r.maxY(); y++) {
                            pos.set(x, y, z);
                            if (!isTargetBlock(mc, pos) || ignored.containsKey(pos)) continue;
                            // chodzimy tylko do blokow odslonietych (te ukryte w srodku same sie odslonia po skopaniu zewnetrznych)
                            if (!isExposed(mc, pos)) continue;

                            double cx = x + 0.5, cy = y + 0.5, cz = z + 0.5;
                            boolean ok = false;
                            for (int[] q : ring) {
                                double ddx = q[0] + 0.5 - cx, ddz = q[1] + 0.5 - cz, ddy = eyeY - cy;
                                if (ddx * ddx + ddy * ddy + ddz * ddz <= reach * reach) {
                                    ok = true;
                                    break;
                                }
                            }
                            if (!ok) continue;

                            double hx = cx - px, hz = cz - pz;
                            double d = hx * hx + hz * hz;
                            if (d < best) {
                                best = d;
                                planTarget = pos.toImmutable();
                                planRegion = r;
                            }
                        }
                    }
                }
            }
        }

        if (planTarget == null || planRegion == null) {
            stopWalking(mc);
            return false;
        }
        BlockPos nearest = planTarget;

        List<int[]> ring = ringOf(planRegion);
        int n = ring.size();
        int cur = -1, dest = -1;
        double cd = Double.MAX_VALUE, dd = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) {
            int[] q = ring.get(i);
            double ax = q[0] + 0.5 - px, az = q[1] + 0.5 - pz;
            double dp = ax * ax + az * az;
            if (dp < cd) { cd = dp; cur = i; }
            double bx = q[0] - nearest.getX(), bz = q[1] - nearest.getZ();
            double dt = bx * bx + bz * bz;
            if (dt < dd) { dd = dt; dest = i; }
        }

        // nie stoisz przy generatorze - nigdzie nie idziemy
        if (cd > 3.0 * 3.0) {
            stopWalking(mc);
            return false;
        }

        // jestesmy juz najblizej tego bloku, a mimo to nie da sie go dosiegnac - odpuszczamy go na chwile
        if (cur == dest) {
            ignored.put(nearest, now + CONFIG.ignoreSeconds * 1000L);
            planTarget = null;
            stopWalking(mc);
            return false;
        }

        int fwd = ((dest - cur) % n + n) % n;
        int dir = fwd <= n / 2 ? 1 : -1;
        int[] w = ring.get(((cur + dir) % n + n) % n);

        // nastepne pole nieprzejezdne (np. sciana, dziura) - stoimy
        if (!isWalkable(mc, w[0], player.getBlockY(), w[1])) {
            stopWalking(mc);
            return false;
        }

        // zablokowany o sciane przez dluzszy czas - odpuszczamy
        if (player.horizontalCollision) {
            walkStuck++;
            if (walkStuck > 30) {
                walkStuck = 0;
                ignored.put(nearest, now + CONFIG.ignoreSeconds * 1000L);
                planTarget = null;
                stopWalking(mc);
                return false;
            }
        } else {
            walkStuck = 0;
        }

        double dx = w[0] + 0.5 - px, dz = w[1] + 0.5 - pz;
        float wantedYaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) - 90.0);
        float diff = MathHelper.wrapDegrees(wantedYaw - player.getYaw());

        double f = mc.options.getMouseSensitivity().getValue() * 0.6 + 0.2;
        double gcd = f * f * f * 8.0 * 0.15;
        player.setYaw(player.getYaw() + turnStep(diff, gcd));
        player.setPitch(MathHelper.clamp(player.getPitch() + turnStep(10f - player.getPitch(), gcd), -90f, 90f));

        // idziemy do przodu dopiero, gdy patrzymy mniej wiecej w dobra strone (bez cofania i krecenia sie)
        mc.options.forwardKey.setPressed(Math.abs(diff) < 35f);
        walking = true;
        return true;
    }

    // ------------------------------------------------------------------ automatyczne wykrywanie generatora

    private static boolean inRegion(Config.Region r, BlockPos p) {
        return p.getX() >= r.minX() && p.getX() <= r.maxX()
                && p.getY() >= r.minY() && p.getY() <= r.maxY()
                && p.getZ() >= r.minZ() && p.getZ() <= r.maxZ();
    }

    /** Zbiera caly polaczony kawalek blokow z listy zaczynajac od seed. box = {minX,minY,minZ,maxX,maxY,maxZ,liczba}. */
    private static HashSet<Long> flood(MinecraftClient mc, BlockPos seed, int[] box) {
        HashSet<Long> seen = new HashSet<>();
        ArrayDeque<Long> queue = new ArrayDeque<>();
        long s0 = seed.asLong();
        seen.add(s0);
        queue.add(s0);
        box[0] = seed.getX(); box[3] = seed.getX();
        box[1] = seed.getY(); box[4] = seed.getY();
        box[2] = seed.getZ(); box[5] = seed.getZ();
        box[6] = 0;
        BlockPos.Mutable pos = new BlockPos.Mutable();

        while (!queue.isEmpty() && seen.size() < 12000) {
            long cur = queue.poll();
            BlockPos cp = BlockPos.fromLong(cur);
            int cx = cp.getX(), cy = cp.getY(), cz = cp.getZ();
            box[6]++;
            if (cx < box[0]) box[0] = cx;
            if (cy < box[1]) box[1] = cy;
            if (cz < box[2]) box[2] = cz;
            if (cx > box[3]) box[3] = cx;
            if (cy > box[4]) box[4] = cy;
            if (cz > box[5]) box[5] = cz;

            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        pos.set(cx + dx, cy + dy, cz + dz);
                        long k = pos.asLong();
                        if (seen.contains(k)) continue;
                        if (!isTargetBlock(mc, pos)) continue;
                        seen.add(k);
                        queue.add(k);
                    }
                }
            }
        }
        return seen;
    }

    private static Config.Region boxRegion(int[] b) {
        Config.Region r = new Config.Region();
        r.a = new int[]{b[0], b[1], b[2]};
        r.b = new int[]{b[3], b[4], b[5]};
        r.exact = true;
        return r;
    }

    /** Szuka w poblizu gracza duzego skupiska blokow z listy (generatora) i zapamietuje jego pelny obrys. */
    private static void refreshAutoBox(MinecraftClient mc, ClientPlayerEntity player, long now) {
        if (now - lastBoxScan < 1000) return;
        lastBoxScan = now;

        double px = player.getX(), py = player.getY(), pz = player.getZ();

        // zapamietany generator jest daleko (np. inny swiat/serwer) - zapominamy
        if (autoRegion != null) {
            double ddx = Math.max(Math.max(autoRegion.minX() - px, 0), px - (autoRegion.maxX() + 1));
            double ddy = Math.max(Math.max(autoRegion.minY() - py, 0), py - (autoRegion.maxY() + 1));
            double ddz = Math.max(Math.max(autoRegion.minZ() - pz, 0), pz - (autoRegion.maxZ() + 1));
            if (ddx * ddx + ddy * ddy + ddz * ddz > 60.0 * 60.0) autoRegion = null;
        }

        int radius = 12;
        BlockPos pp = player.getBlockPos();
        BlockPos.Mutable pos = new BlockPos.Mutable();
        HashSet<Long> skip = new HashSet<>();
        int[] accept = null;
        int[] fallback = null;

        for (int attempt = 0; attempt < 4; attempt++) {
            BlockPos seed = null;
            double sd = Double.MAX_VALUE;
            for (int x = pp.getX() - radius; x <= pp.getX() + radius; x++) {
                for (int y = pp.getY() - radius; y <= pp.getY() + radius; y++) {
                    for (int z = pp.getZ() - radius; z <= pp.getZ() + radius; z++) {
                        pos.set(x, y, z);
                        if (!isTargetBlock(mc, pos)) continue;
                        if (skip.contains(pos.asLong())) continue;
                        double dx = x + 0.5 - px, dy = y + 0.5 - py, dz = z + 0.5 - pz;
                        double d = dx * dx + dy * dy + dz * dz;
                        if (d < sd) {
                            sd = d;
                            seed = pos.toImmutable();
                        }
                    }
                }
            }
            if (seed == null) break;

            // blok nalezy do znanego generatora i obrys byl niedawno liczony - nic nie robimy
            if (attempt == 0 && autoRegion != null && inRegion(autoRegion, seed) && now - lastBoxBuild < 10000) return;

            int[] box = new int[7];
            skip.addAll(flood(mc, seed, box));
            if (box[6] >= 30) {
                accept = box;
                break;
            }
            if (fallback == null || box[6] > fallback[6]) fallback = box;
        }

        int[] box = accept != null ? accept : fallback;
        if (box == null) return;
        lastBoxBuild = now;

        if (autoRegion == null) {
            autoRegion = boxRegion(box);
        } else if (accept != null) {
            boolean overlaps = box[0] <= autoRegion.maxX() + 1 && box[3] >= autoRegion.minX() - 1
                    && box[1] <= autoRegion.maxY() + 1 && box[4] >= autoRegion.minY() - 1
                    && box[2] <= autoRegion.maxZ() + 1 && box[5] >= autoRegion.minZ() - 1;
            if (overlaps) {
                // ten sam generator - tylko powiekszamy obrys (nigdy nie zmniejszamy)
                int[] a = autoRegion.a, b = autoRegion.b;
                autoRegion.a = new int[]{Math.min(a[0], box[0]), Math.min(a[1], box[1]), Math.min(a[2], box[2])};
                autoRegion.b = new int[]{Math.max(b[0], box[3]), Math.max(b[1], box[4]), Math.max(b[2], box[5])};
            } else {
                // inny generator
                autoRegion = boxRegion(box);
            }
        }
    }

    private static void stopWalking(MinecraftClient mc) {
        if (walking) {
            mc.options.forwardKey.setPressed(false);
            mc.options.jumpKey.setPressed(false);
            walking = false;
        }
    }

    // ------------------------------------------------------------------ pomocnicze

    /** Id bloku, na ktory patrzysz (np. minecraft:oak_log) albo null. */
    private static String lookedBlockId() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null) return null;
        if (mc.crosshairTarget instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            return Registries.BLOCK.getId(mc.world.getBlockState(bhr.getBlockPos()).getBlock()).toString();
        }
        return null;
    }

    private static void setEnabled(boolean value) {
        enabled = value;
        MinecraftClient mc = MinecraftClient.getInstance();
        target = null;
        aimPoint = null;
        waiting = false;
        if (!value) {
            stopWalking(mc);
            if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        } else {
            ignored.clear();
            denials.clear();
        }
        if (value && !CONFIG.auto && !CONFIG.gen1.isSet() && !CONFIG.gen2.isSet()) {
            msg("Wlaczone, ale zaden generator nie jest ustawiony! Uzyj /ak gen1 a, /ak gen1 b, /ak gen2 a, /ak gen2 b (albo /ak auto).");
            return;
        }
        msg("AutoKopanie: " + (value ? "WLACZONE" : "WYLACZONE"));
    }

    private static void setCorner(int gen, boolean cornerA) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;

        BlockPos p;
        if (mc.crosshairTarget instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            p = bhr.getBlockPos();
        } else {
            p = mc.player.getBlockPos().down();
        }

        if (CONFIG.auto) {
            CONFIG.auto = false;
            msg("Zaznaczasz generator recznie - wylaczam tryb AUTO (wlaczysz go z powrotem: /ak auto).");
        }
        Config.Region r = gen == 1 ? CONFIG.gen1 : CONFIG.gen2;
        int[] arr = new int[]{p.getX(), p.getY(), p.getZ()};
        if (cornerA) r.a = arr; else r.b = arr;
        CONFIG.save();

        String bid = Registries.BLOCK.getId(mc.world.getBlockState(p).getBlock()).toString();
        msg("gen" + gen + " rog " + (cornerA ? "A" : "B") + " = " + p.getX() + " " + p.getY() + " " + p.getZ()
                + (r.isSet() ? "  (generator gotowy)" : "  (ustaw jeszcze drugi rog)")
                + " | blok: " + bid + (CONFIG.isTarget(bid) ? " (jest na liscie)" : " (NIE ma na liscie - spojrz na blok generatora i wpisz /ak add)"));
    }

    private static void msg(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(Text.literal("[AutoKopanie] " + s).formatted(Formatting.GOLD), false);
        }
    }
}
