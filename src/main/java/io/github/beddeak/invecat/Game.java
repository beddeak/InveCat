package io.github.beddeak.invecat;

import io.github.beddeak.invecat.SharedSlots.Leftover;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** Every participant shares one inventory, and one slot disappears for good every interval until only hotbar slot 1 is left. */
final class Game {

    enum Phase { IDLE, RUNNING, CLEARED }

    private static final SharedSlots.Stacks<ItemStack> ITEMS = new SharedSlots.Stacks<>() {
        @Override
        public boolean similar(ItemStack a, ItemStack b) {
            return a.isSimilar(b);
        }

        @Override
        public boolean sameKind(ItemStack a, ItemStack b) {
            return a.getType() == b.getType();
        }

        @Override
        public int amount(ItemStack stack) {
            return stack.getAmount();
        }

        @Override
        public int max(ItemStack stack) {
            return stack.getMaxStackSize();
        }

        @Override
        public ItemStack withAmount(ItemStack stack, int amount) {
            return stack.asQuantity(amount);
        }
    };
    private static final Title.Times LOCK_TIMES = Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(2500), Duration.ofMillis(500));
    private static final Title.Times COUNTDOWN_TIMES = Title.Times.times(Duration.ZERO, Duration.ofMillis(900), Duration.ofMillis(200));
    private static final Sound TICK_SOUND = Sound.sound(Key.key("block.note_block.hat"), Sound.Source.MASTER, 1f, 1.4f);
    private static final Sound WARN_SOUND = Sound.sound(Key.key("block.note_block.bell"), Sound.Source.MASTER, 1f, 1f);
    private static final Sound LOCK_SOUND = Sound.sound(Key.key("block.anvil.land"), Sound.Source.MASTER, 0.7f, 1.2f);
    private static final Sound UNLOCK_SOUND = Sound.sound(Key.key("block.amethyst_block.chime"), Sound.Source.MASTER, 1f, 1f);
    private static final Sound START_SOUND = Sound.sound(Key.key("block.beacon.activate"), Sound.Source.MASTER, 1f, 1f);
    /** Slots 36-40. */
    private static final List<String> ARMOR_NAMES = List.of("신발", "바지", "상의", "투구", "왼손");
    private static final int WARN_AT = 60;
    private static final int SAVE_EVERY = 60;

    private final InveCatPlugin plugin;
    private final LockedItem lockedItem;
    private final SharedSlots<ItemStack> shared = new SharedSlots<>(ITEMS);
    private final LockTimer timer;
    private final Credits credits;
    private final Random random = new Random();
    /** Online players whose inventory is not the shared one yet; they are overwritten, never read. */
    private final Set<UUID> fresh = new HashSet<>();
    private final Map<UUID, String> players = new LinkedHashMap<>();
    private final BossBar bar = BossBar.bossBar(Component.empty(), 1f, BossBar.Color.GREEN, BossBar.Overlay.PROGRESS);
    private Phase phase = Phase.IDLE;
    private int nextSlot = -1;
    private long elapsed;
    private String finisher;
    private int sinceSave;

    Game(InveCatPlugin plugin) {
        this.plugin = plugin;
        this.lockedItem = new LockedItem(plugin);
        this.timer = new LockTimer(plugin.settings().lockSeconds());
        this.credits = new Credits(plugin);
    }

    LockedItem lockedItem() {
        return lockedItem;
    }

    LockTimer timer() {
        return timer;
    }

    Phase phase() {
        return phase;
    }

    int openCount() {
        return shared.openCount();
    }

    List<Integer> lockedSlots() {
        return shared.lockedSlots();
    }

    int nextSlot() {
        return nextSlot;
    }

    long elapsed() {
        return elapsed;
    }

    boolean plays(Player player) {
        return plugin.settings().gameModes().contains(player.getGameMode());
    }

    List<Player> participants() {
        List<Player> list = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!player.isDead() && plays(player)) {
                list.add(player);
            }
        }
        return list;
    }

    /** Returns an error message, or null when the game started. */
    String start() {
        if (phase == Phase.RUNNING) {
            return "이미 진행 중입니다. 처음부터 하려면 stop 후 다시 start 하세요.";
        }
        credits.cancel();
        shared.reset();
        players.clear();
        fresh.clear();
        elapsed = 0;
        finisher = null;
        phase = Phase.RUNNING;
        timer.setInterval(plugin.settings().lockSeconds());
        timer.start();
        nextSlot = shared.pickNext(random, plugin.settings().hotbarLast());
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!plays(player)) {
                continue;
            }
            if (player.isDead()) {
                fresh.add(player.getUniqueId());
                continue;
            }
            player.closeInventory();
            AttributeInstance maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
            player.setHealth(maxHealth == null ? 20 : maxHealth.getValue());
            player.setFoodLevel(20);
            player.setSaturation(5f);
        }
        pushAll(null);
        Bukkit.getServer().showTitle(Title.title(Component.text("인벤토리 공유 시작!", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text(LockTimer.words(timer.interval()) + "마다 한 칸씩 사라집니다 · 목표: 엔더 드래곤", NamedTextColor.YELLOW),
                LOCK_TIMES));
        Bukkit.getServer().playSound(START_SOUND);
        broadcast(Component.text("게임 시작! 모두 인벤토리 하나를 같이 씁니다. " + LockTimer.words(timer.interval())
                + "마다 한 칸씩 사라지고, 엔더 드래곤을 잡으면 클리어입니다.", NamedTextColor.GREEN));
        if (participants().isEmpty()) {
            broadcast(Component.text("지금은 참가자가 없습니다. 서바이벌·모험 모드인 사람이 들어오면 타이머가 돌아갑니다.", NamedTextColor.GRAY));
        }
        plugin.saveState();
        updateBar();
        return null;
    }

    /** Ends the game. Everyone keeps a copy of the shared inventory and the locked slots come back. */
    void stop() {
        if (phase == Phase.IDLE) {
            return;
        }
        sync();
        phase = Phase.IDLE;
        timer.stop();
        credits.cancel();
        fresh.clear();
        for (Player player : Bukkit.getOnlinePlayers()) {
            strip(player);
        }
        plugin.saveState();
        updateBar();
    }

    boolean pause() {
        if (phase != Phase.RUNNING || !timer.pause()) {
            return false;
        }
        plugin.saveState();
        updateBar();
        return true;
    }

    boolean resume() {
        if (phase != Phase.RUNNING || !timer.resume()) {
            return false;
        }
        plugin.saveState();
        updateBar();
        return true;
    }

    /** Called once per tick: merges what every participant changed into the shared inventory and hands it back out. */
    void sync() {
        sync(null);
    }

    /** {@code include} takes part even though it is dead or leaving. */
    private void sync(Player include) {
        if (phase == Phase.IDLE) {
            return;
        }
        List<Player> online = participants();
        if (include != null && !online.contains(include)) {
            online.add(include);
        }
        if (online.isEmpty()) {
            return;
        }
        List<ItemStack[]> raws = new ArrayList<>(online.size());
        List<Player> readers = new ArrayList<>();
        List<ItemStack[]> views = new ArrayList<>();
        List<Leftover<ItemStack>> leftovers = new ArrayList<>();
        List<Integer> locked = shared.lockedSlots();
        for (Player player : online) {
            ItemStack[] raw = read(player);
            raws.add(raw);
            if (fresh.contains(player.getUniqueId())) {
                continue;
            }
            for (int slot : locked) {
                if (raw[slot] != null && !lockedItem.is(raw[slot])) {
                    leftovers.add(new Leftover<>(readers.size(), raw[slot].clone()));
                }
            }
            readers.add(player);
            views.add(raw);
        }
        for (int slot = 0; slot < SharedSlots.SIZE; slot++) {
            if (shared.isLocked(slot) || !changed(slot, views)) {
                continue;
            }
            List<ItemStack> column = new ArrayList<>(views.size());
            for (ItemStack[] view : views) {
                ItemStack now = open(view[slot]);
                column.add(now == null ? null : now.clone());
            }
            leftovers.addAll(shared.merge(slot, column));
        }
        for (Leftover<ItemStack> leftover : leftovers) {
            ItemStack rest = shared.insert(leftover.stack());
            if (rest != null) {
                drop(leftover.owner() >= 0 ? readers.get(leftover.owner()) : anyone(online), rest);
            }
        }
        for (int i = 0; i < online.size(); i++) {
            write(online.get(i), raws.get(i));
        }
    }

    /** Called once per second: the lock timer, warnings, boss bar and autosave. */
    void tickSecond() {
        if (phase == Phase.RUNNING && !timer.paused()) {
            List<Player> online = participants();
            if (!online.isEmpty()) {
                elapsed++;
                if (shared.lockableCount() > 0) {
                    if (timer.tick()) {
                        lock(1);
                    } else {
                        warn(timer.remaining());
                    }
                }
            }
        }
        updateBar();
        if (phase != Phase.IDLE && ++sinceSave >= SAVE_EVERY) {
            plugin.saveState();
        }
    }

    /** Locks up to {@code count} slots right now and returns how many disappeared. */
    int lock(int count) {
        if (phase == Phase.IDLE) {
            return 0;
        }
        sync();
        boolean hotbarLast = plugin.settings().hotbarLast();
        List<Player> online = participants();
        List<Integer> done = new ArrayList<>();
        List<Component> notes = new ArrayList<>();
        for (int i = 0; i < count && shared.lockableCount() > 0; i++) {
            int slot = nextSlot >= 0 && !shared.isLocked(nextSlot) ? nextSlot : shared.pickNext(random, hotbarLast);
            ItemStack removed = shared.lock(slot);
            done.add(slot);
            if (removed != null) {
                notes.add(spill(slot, removed, online));
            }
            nextSlot = shared.pickNext(random, hotbarLast);
        }
        if (done.isEmpty()) {
            return 0;
        }
        pushAll(null);
        int open = shared.openCount();
        String names = done.stream().map(Game::slotName).collect(Collectors.joining(", "));
        Bukkit.getServer().showTitle(Title.title(
                Component.text(done.size() == 1 ? "한 칸이 사라졌다!" : done.size() + "칸이 사라졌다!", NamedTextColor.RED, TextDecoration.BOLD),
                Component.text(names + " · 남은 칸 " + open + "/" + SharedSlots.SIZE, NamedTextColor.YELLOW), LOCK_TIMES));
        Bukkit.getServer().playSound(LOCK_SOUND);
        broadcast(Component.text(names + " 칸이 사라졌습니다! 남은 칸 " + open + "/" + SharedSlots.SIZE, NamedTextColor.RED));
        notes.forEach(note -> Bukkit.getServer().sendMessage(note));
        if (shared.lockableCount() == 0) {
            broadcast(Component.text("남은 건 " + slotName(SharedSlots.KEPT) + " 한 칸뿐! 엔더 드래곤을 잡으세요!", NamedTextColor.DARK_RED));
        } else if (plugin.settings().showNextSlot()) {
            broadcast(Component.text("다음에 사라질 칸: " + slotName(nextSlot) + " (" + LockTimer.words(timer.remaining()) + " 뒤)", NamedTextColor.GRAY));
        }
        plugin.saveState();
        updateBar();
        return done.size();
    }

    /** Brings back up to {@code count} of the most recently locked slots and returns how many came back. */
    int unlock(int count) {
        if (phase == Phase.IDLE) {
            return 0;
        }
        sync();
        List<Integer> done = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            int slot = shared.unlockLast();
            if (slot < 0) {
                break;
            }
            done.add(slot);
        }
        if (done.isEmpty()) {
            return 0;
        }
        if (nextSlot < 0 || shared.isLocked(nextSlot)) {
            nextSlot = shared.pickNext(random, plugin.settings().hotbarLast());
        }
        pushAll(null);
        String names = done.stream().map(Game::slotName).collect(Collectors.joining(", "));
        Bukkit.getServer().playSound(UNLOCK_SOUND);
        broadcast(Component.text(names + " 칸이 돌아왔습니다! 남은 칸 " + shared.openCount() + "/" + SharedSlots.SIZE, NamedTextColor.GREEN));
        plugin.saveState();
        updateBar();
        return done.size();
    }

    void dragonKilled(Player killer) {
        if (phase != Phase.RUNNING) {
            return;
        }
        sync();
        phase = Phase.CLEARED;
        finisher = killer == null ? null : killer.getName();
        plugin.saveState();
        updateBar();
        credits.play(roll());
    }

    void playCredits() {
        credits.play(roll());
    }

    Credits.Roll roll() {
        return new Credits.Roll(elapsed, shared.openCount(), finisher, List.copyOf(players.values()));
    }

    void onJoin(Player player) {
        if (phase == Phase.IDLE) {
            strip(player);
            return;
        }
        fresh.add(player.getUniqueId());
        updateBar();
        if (phase == Phase.RUNNING && timer.paused() && player.hasPermission(InveCatPlugin.ADMIN)) {
            player.sendMessage(InveCatPlugin.PREFIX.append(Component.text(
                    "일시정지 상태입니다. 모두 들어오면 /invecat resume", NamedTextColor.YELLOW)));
        }
    }

    void onQuit(Player player) {
        if (phase == Phase.IDLE) {
            return;
        }
        if (plays(player) && !player.isDead() && !fresh.contains(player.getUniqueId())) {
            player.closeInventory();
            sync(player);
        }
        fresh.remove(player.getUniqueId());
    }

    void onDeath(PlayerDeathEvent event) {
        Player player = event.getPlayer();
        if (phase == Phase.IDLE || !plays(player)) {
            return;
        }
        UUID id = player.getUniqueId();
        if (fresh.contains(id)) {
            if (!event.getKeepInventory()) {
                event.getDrops().clear();
            }
            return;
        }
        if (event.getKeepInventory()) {
            fresh.add(id);
            return;
        }
        sync(player);
        fresh.add(id);
        List<ItemStack> drops = event.getDrops();
        drops.clear();
        boolean any = false;
        for (int slot = 0; slot < SharedSlots.SIZE; slot++) {
            ItemStack item = shared.get(slot);
            if (item != null && !shared.isLocked(slot)) {
                any = true;
                if (!item.containsEnchantment(Enchantment.VANISHING_CURSE)) {
                    drops.add(item.clone());
                }
            }
        }
        shared.clearItems();
        pushAll(player);
        if (any) {
            Location at = player.getLocation();
            broadcast(Component.text(player.getName() + "이(가) 죽어서 공유 인벤토리가 전부 떨어졌습니다! ("
                    + at.getWorld().getName() + " " + at.getBlockX() + ", " + at.getBlockY() + ", " + at.getBlockZ() + ")",
                    NamedTextColor.RED));
        }
        plugin.saveState();
    }

    void onRespawn(Player player) {
        if (phase != Phase.IDLE) {
            fresh.add(player.getUniqueId());
        }
    }

    void onGameModeChange(Player player, GameMode to) {
        if (phase == Phase.IDLE) {
            return;
        }
        boolean was = plays(player);
        boolean will = plugin.settings().gameModes().contains(to);
        if (was == will) {
            return;
        }
        UUID id = player.getUniqueId();
        if (will) {
            fresh.add(id);
            return;
        }
        if (!player.isDead() && !fresh.contains(id)) {
            sync(player);
        }
        fresh.remove(id);
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < SharedSlots.SIZE; slot++) {
            inventory.setItem(slot, null);
        }
    }

    /** Merges once more before the server stops. The state itself is saved by the plugin. */
    void shutdown() {
        sync();
        credits.cancel();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.hideBossBar(bar);
        }
    }

    void updateBar() {
        boolean show = phase != Phase.IDLE && plugin.settings().bossBar();
        if (show) {
            List<Player> online = participants();
            int open = shared.openCount();
            String slots = "남은 칸 " + open + "/" + SharedSlots.SIZE;
            String next = plugin.settings().showNextSlot() && nextSlot >= 0 ? " · 다음: " + slotName(nextSlot) : "";
            String clock = LockTimer.clock(timer.remaining());
            float progress = 1f;
            BossBar.Color color = BossBar.Color.WHITE;
            String text;
            if (phase == Phase.CLEARED) {
                text = "클리어! · 기록 " + LockTimer.clock(elapsed) + " · " + Credits.AUTHOR;
                color = BossBar.Color.PURPLE;
            } else if (shared.lockableCount() == 0) {
                text = "남은 건 " + slotName(SharedSlots.KEPT) + " 한 칸뿐 · 엔더 드래곤을 잡으세요!";
                color = BossBar.Color.RED;
            } else if (timer.paused()) {
                text = "일시정지 · 한 칸 사라지기까지 " + clock + next + " · " + slots;
            } else if (online.isEmpty()) {
                text = "참가자를 기다리는 중 · " + slots;
            } else {
                text = "한 칸 사라지기까지 " + clock + next + " · " + slots;
                progress = Math.clamp((float) timer.remaining() / timer.interval(), 0f, 1f);
                color = progress > 0.5f ? BossBar.Color.GREEN : progress > 0.2f ? BossBar.Color.YELLOW : BossBar.Color.RED;
            }
            bar.name(Component.text(text, NamedTextColor.WHITE));
            bar.progress(progress);
            bar.color(color);
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (show) {
                player.showBossBar(bar);
            } else {
                player.hideBossBar(bar);
            }
        }
    }

    void save(YamlConfiguration yaml) {
        sinceSave = 0;
        yaml.set("phase", phase.name());
        if (phase == Phase.IDLE) {
            return;
        }
        yaml.set("paused", timer.paused());
        yaml.set("remaining", timer.remaining());
        yaml.set("elapsed", elapsed);
        yaml.set("next-slot", nextSlot);
        yaml.set("locked", shared.lockedSlots());
        ItemStack[] items = new ItemStack[SharedSlots.SIZE];
        for (int slot = 0; slot < SharedSlots.SIZE; slot++) {
            items[slot] = shared.get(slot);
        }
        yaml.set("items", Base64.getEncoder().encodeToString(ItemStack.serializeItemsAsBytes(items)));
        ConfigurationSection section = yaml.createSection("players");
        players.forEach((id, name) -> section.set(id.toString(), name));
        yaml.set("finisher", finisher);
    }

    /** Restores a saved game. A running game comes back paused until everyone is back. Throws on broken data. */
    void load(YamlConfiguration yaml) {
        Phase saved = Phase.valueOf(yaml.getString("phase", "IDLE"));
        if (saved == Phase.IDLE) {
            return;
        }
        List<ItemStack> items = new ArrayList<>();
        String encoded = yaml.getString("items", "");
        if (!encoded.isEmpty()) {
            for (ItemStack item : ItemStack.deserializeItemsFromBytes(Base64.getDecoder().decode(encoded))) {
                items.add(item == null || item.isEmpty() ? null : item);
            }
        }
        List<Integer> locked = yaml.getIntegerList("locked").stream()
                .filter(slot -> slot >= 0 && slot < SharedSlots.SIZE && slot != SharedSlots.KEPT).distinct().toList();
        shared.restore(items, locked);
        players.clear();
        ConfigurationSection section = yaml.getConfigurationSection("players");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                players.put(UUID.fromString(key), section.getString(key, key));
            }
        }
        elapsed = Math.max(0, yaml.getLong("elapsed"));
        finisher = yaml.getString("finisher");
        nextSlot = yaml.getInt("next-slot", -1);
        if (nextSlot < 0 || nextSlot >= SharedSlots.SIZE || nextSlot == SharedSlots.KEPT || shared.isLocked(nextSlot)) {
            nextSlot = shared.pickNext(random, plugin.settings().hotbarLast());
        }
        phase = saved;
        timer.start();
        timer.setRemaining(Math.min(yaml.getInt("remaining", timer.interval()), timer.interval()));
        if (phase == Phase.RUNNING) {
            timer.pause();
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            fresh.add(player.getUniqueId());
        }
    }

    static String slotName(int slot) {
        if (slot < SharedSlots.HOTBAR) {
            return "핫바 " + (slot + 1) + "번";
        }
        if (slot >= SharedSlots.STORAGE) {
            return ARMOR_NAMES.get(slot - SharedSlots.STORAGE);
        }
        int index = slot - SharedSlots.HOTBAR;
        return "인벤토리 " + (index / 9 + 1) + "줄 " + (index % 9 + 1) + "번";
    }

    static void broadcast(Component message) {
        Bukkit.getServer().sendMessage(InveCatPlugin.PREFIX.append(message));
    }

    private void warn(int remaining) {
        boolean showNext = plugin.settings().showNextSlot() && nextSlot >= 0;
        if (remaining <= plugin.settings().countdownSeconds()) {
            Bukkit.getServer().showTitle(Title.title(Component.text(remaining, NamedTextColor.RED, TextDecoration.BOLD),
                    Component.text(showNext ? slotName(nextSlot) + " 칸이 곧 사라집니다" : "곧 한 칸이 사라집니다", NamedTextColor.YELLOW),
                    COUNTDOWN_TIMES));
            Bukkit.getServer().playSound(TICK_SOUND);
        } else if (remaining == WARN_AT && timer.interval() > WARN_AT) {
            broadcast(Component.text("1분 뒤 " + (showNext ? slotName(nextSlot) + " 칸이" : "한 칸이") + " 사라집니다!", NamedTextColor.YELLOW));
            Bukkit.getServer().playSound(WARN_SOUND);
        }
    }

    /** Deals with what was in a slot that just disappeared and says what happened to it. */
    private Component spill(int slot, ItemStack removed, List<Player> online) {
        String fate;
        switch (plugin.settings().lockedItem()) {
            case DELETE -> fate = "같이 사라졌습니다";
            case DROP -> {
                Player at = anyone(online);
                drop(at, removed);
                fate = at == null ? "떨어뜨릴 곳이 없어 사라졌습니다" : at.getName() + " 발밑에 떨어졌습니다";
            }
            default -> {
                ItemStack rest = shared.insert(removed);
                if (rest == null) {
                    fate = "다른 빈 칸으로 옮겼습니다";
                } else {
                    Player at = anyone(online);
                    drop(at, rest);
                    String where = at == null ? "떨어뜨릴 곳이 없어 사라졌습니다" : at.getName() + " 발밑에 떨어졌습니다";
                    fate = rest.getAmount() < removed.getAmount() ? "일부만 옮기고 나머지는 " + where : "빈 칸이 없어 " + where;
                }
            }
        }
        return Component.text(" " + slotName(slot) + "에 있던 ", NamedTextColor.GRAY)
                .append(removed.effectiveName().colorIfAbsent(NamedTextColor.WHITE))
                .append(Component.text(" ×" + removed.getAmount() + ": " + fate, NamedTextColor.GRAY));
    }

    private boolean changed(int slot, List<ItemStack[]> views) {
        ItemStack base = shared.get(slot);
        for (ItemStack[] view : views) {
            if (!shared.same(open(view[slot]), base)) {
                return true;
            }
        }
        return false;
    }

    /** A locked-slot barrier that shows up in an open slot counts as empty. */
    private ItemStack open(ItemStack raw) {
        return lockedItem.is(raw) ? null : raw;
    }

    private static ItemStack[] read(Player player) {
        PlayerInventory inventory = player.getInventory();
        ItemStack[] raw = new ItemStack[SharedSlots.SIZE];
        for (int slot = 0; slot < SharedSlots.SIZE; slot++) {
            ItemStack item = inventory.getItem(slot);
            raw[slot] = item == null || item.isEmpty() ? null : item;
        }
        return raw;
    }

    /** Makes {@code player}'s inventory match the shared one; {@code raw} is what they have now. */
    private void write(Player player, ItemStack[] raw) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < SharedSlots.SIZE; slot++) {
            ItemStack want = shared.isLocked(slot) ? lockedItem.item() : shared.get(slot);
            if (!shared.same(raw[slot], want)) {
                inventory.setItem(slot, want);
            }
        }
        fresh.remove(player.getUniqueId());
        players.putIfAbsent(player.getUniqueId(), player.getName());
    }

    /** Writes the shared inventory to every participant without reading their changes first. */
    private void pushAll(Player except) {
        for (Player player : participants()) {
            if (!player.equals(except)) {
                write(player, read(player));
            }
        }
    }

    private void strip(Player player) {
        PlayerInventory inventory = player.getInventory();
        for (int slot = 0; slot < SharedSlots.SIZE; slot++) {
            if (lockedItem.is(inventory.getItem(slot))) {
                inventory.setItem(slot, null);
            }
        }
    }

    private Player anyone(List<Player> online) {
        return online.isEmpty() ? null : online.get(random.nextInt(online.size()));
    }

    private void drop(Player at, ItemStack stack) {
        if (at == null) {
            plugin.getLogger().warning("떨어뜨릴 곳이 없어 아이템이 사라졌습니다: " + stack);
            return;
        }
        int max = Math.max(1, stack.getMaxStackSize());
        for (int left = stack.getAmount(); left > 0; left -= max) {
            at.getWorld().dropItem(at.getLocation(), stack.asQuantity(Math.min(left, max)));
        }
    }
}
