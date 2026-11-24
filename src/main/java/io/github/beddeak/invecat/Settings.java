package io.github.beddeak.invecat;

import org.bukkit.GameMode;
import org.bukkit.configuration.file.FileConfiguration;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Logger;

record Settings(int lockSeconds, int countdownSeconds, boolean hotbarLast, LockedItemRule lockedItem,
                boolean showNextSlot, boolean bossBar, Set<GameMode> gameModes) {

    /** What happens to the item in a slot when that slot disappears. */
    enum LockedItemRule { MOVE, DROP, DELETE }

    static Settings load(FileConfiguration config, Logger logger) {
        int lock = config.getInt("lock-interval-seconds", 600);
        if (lock != LockTimer.clampInterval(lock)) {
            logger.warning("lock-interval-seconds는 " + LockTimer.MIN_INTERVAL + "~" + LockTimer.MAX_SECONDS + " 사이여야 합니다: " + lock);
            lock = LockTimer.clampInterval(lock);
        }
        int countdown = Math.clamp(config.getInt("countdown-seconds", 5), 0, 60);
        String order = config.getString("lock-order", "random").trim().toLowerCase(Locale.ROOT);
        if (!order.equals("random") && !order.equals("hotbar-last")) {
            logger.warning("lock-order는 random 또는 hotbar-last여야 합니다: " + order);
            order = "random";
        }
        LockedItemRule rule;
        String lockedItem = config.getString("locked-item", "move").trim().toUpperCase(Locale.ROOT);
        try {
            rule = LockedItemRule.valueOf(lockedItem);
        } catch (IllegalArgumentException e) {
            logger.warning("locked-item은 move, drop, delete 중 하나여야 합니다: " + lockedItem);
            rule = LockedItemRule.MOVE;
        }
        Set<GameMode> modes = EnumSet.noneOf(GameMode.class);
        for (String name : config.getStringList("game-modes")) {
            try {
                modes.add(GameMode.valueOf(name.trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                logger.warning("game-modes에 알 수 없는 게임 모드가 있습니다: " + name);
            }
        }
        if (modes.isEmpty()) {
            logger.warning("game-modes가 비어 있어 SURVIVAL, ADVENTURE를 사용합니다.");
            modes = EnumSet.of(GameMode.SURVIVAL, GameMode.ADVENTURE);
        }
        return new Settings(lock, countdown, order.equals("hotbar-last"), rule,
                config.getBoolean("show-next-slot", true),
                config.getBoolean("bossbar", true),
                Set.copyOf(modes));
    }
}
