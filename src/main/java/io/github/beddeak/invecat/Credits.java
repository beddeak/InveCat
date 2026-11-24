package io.github.beddeak.invecat;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** The roll everyone sees once the Ender Dragon dies. */
final class Credits {

    static final String AUTHOR = "plugin by beddeak(침댁)";

    /** What the roll shows. {@code finisher} may be null. */
    record Roll(long seconds, int open, String finisher, List<String> players) {
    }

    private static final long STEP_TICKS = 80L;
    private static final int MAX_NAMES = 6;
    private static final Title.Times TIMES = Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(3000), Duration.ofMillis(400));
    private static final Title.Times LAST_TIMES = Title.Times.times(Duration.ofMillis(400), Duration.ofMillis(6000), Duration.ofMillis(1500));
    private static final Sound CLEAR_SOUND = Sound.sound(Key.key("ui.toast.challenge_complete"), Sound.Source.MASTER, 1f, 1f);
    private static final Sound STEP_SOUND = Sound.sound(Key.key("block.note_block.chime"), Sound.Source.MASTER, 0.7f, 1.2f);
    private static final Sound LAST_SOUND = Sound.sound(Key.key("entity.player.levelup"), Sound.Source.MASTER, 1f, 1f);

    private final Plugin plugin;
    private final List<BukkitTask> tasks = new ArrayList<>();

    Credits(Plugin plugin) {
        this.plugin = plugin;
    }

    void play(Roll roll) {
        cancel();
        List<Title> steps = new ArrayList<>();
        steps.add(Title.title(Component.text("클리어!", NamedTextColor.GOLD, TextDecoration.BOLD),
                Component.text("엔더 드래곤 처치", NamedTextColor.YELLOW), TIMES));
        if (roll.finisher() != null) {
            steps.add(step("마지막 일격", roll.finisher()));
        }
        steps.add(step("기록", LockTimer.words(roll.seconds())));
        steps.add(step("남은 인벤토리", roll.open() + " / " + SharedSlots.SIZE + "칸"));
        if (!roll.players().isEmpty()) {
            steps.add(step("함께한 사람", names(roll.players())));
        }
        for (int i = 0; i < steps.size(); i++) {
            Title title = steps.get(i);
            Sound sound = i == 0 ? CLEAR_SOUND : STEP_SOUND;
            later(i * STEP_TICKS, () -> {
                Bukkit.getServer().showTitle(title);
                Bukkit.getServer().playSound(sound);
            });
        }
        later(steps.size() * STEP_TICKS, () -> {
            Bukkit.getServer().showTitle(Title.title(Component.text("InveCat", NamedTextColor.GOLD, TextDecoration.BOLD),
                    Component.text(AUTHOR, NamedTextColor.WHITE), LAST_TIMES));
            Bukkit.getServer().playSound(LAST_SOUND);
            for (Component line : summary(roll)) {
                Bukkit.getServer().sendMessage(line);
            }
            tasks.clear();
        });
    }

    void cancel() {
        tasks.forEach(BukkitTask::cancel);
        tasks.clear();
    }

    static List<Component> summary(Roll roll) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.text("━━━━━━━━━━ InveCat ━━━━━━━━━━", NamedTextColor.GOLD));
        lines.add(Component.text(" 클리어! 엔더 드래곤 처치", NamedTextColor.YELLOW)
                .append(Component.text(roll.finisher() == null ? "" : " (마지막 일격: " + roll.finisher() + ")", NamedTextColor.GRAY)));
        lines.add(Component.text(" 기록 " + LockTimer.words(roll.seconds()) + " · 남은 칸 " + roll.open() + "/"
                + SharedSlots.SIZE, NamedTextColor.WHITE));
        if (!roll.players().isEmpty()) {
            lines.add(Component.text(" 함께한 사람: " + String.join(", ", roll.players()), NamedTextColor.WHITE));
        }
        lines.add(Component.text(" " + AUTHOR, NamedTextColor.AQUA));
        lines.add(Component.text("━━━━━━━━━━━━━━━━━━━━━━━━━━━━", NamedTextColor.GOLD));
        return lines;
    }

    private void later(long delay, Runnable action) {
        tasks.add(Bukkit.getScheduler().runTaskLater(plugin, action, Math.max(1L, delay)));
    }

    private static Title step(String title, String subtitle) {
        return Title.title(Component.text(title, NamedTextColor.GOLD), Component.text(subtitle, NamedTextColor.WHITE), TIMES);
    }

    private static String names(List<String> players) {
        if (players.size() <= MAX_NAMES) {
            return String.join(", ", players);
        }
        return String.join(", ", players.subList(0, MAX_NAMES)) + " 외 " + (players.size() - MAX_NAMES) + "명";
    }
}
