package io.github.beddeak.invecat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.stream.Collectors;

final class InveCatCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS = List.of("start", "stop", "pause", "resume", "interval", "time", "add",
            "lock", "unlock", "status", "credits", "reload", "help");
    private static final List<String> PUBLIC = List.of("status", "credits", "help");
    private static final Map<String, String> ALIASES = Map.ofEntries(
            Map.entry("시작", "start"), Map.entry("종료", "stop"), Map.entry("정지", "stop"),
            Map.entry("일시정지", "pause"), Map.entry("재개", "resume"), Map.entry("주기", "interval"),
            Map.entry("시간", "time"), Map.entry("추가", "add"), Map.entry("연장", "add"),
            Map.entry("잠금", "lock"), Map.entry("삭제", "lock"), Map.entry("해제", "unlock"), Map.entry("복구", "unlock"),
            Map.entry("상태", "status"), Map.entry("크레딧", "credits"), Map.entry("리로드", "reload"),
            Map.entry("도움말", "help"));

    private final InveCatPlugin plugin;

    InveCatCommand(InveCatPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : canonical(args[0]);
        boolean admin = sender.hasPermission(InveCatPlugin.ADMIN);
        if (!PUBLIC.contains(sub) && SUBCOMMANDS.contains(sub) && !admin) {
            error(sender, "권한이 없습니다.");
            return true;
        }
        Game game = plugin.game();
        LockTimer timer = game.timer();
        switch (sub) {
            case "start" -> {
                String problem = game.start();
                if (problem != null) {
                    error(sender, problem);
                }
            }
            case "stop" -> {
                if (game.phase() == Game.Phase.IDLE) {
                    error(sender, "진행 중인 게임이 없습니다.");
                    return true;
                }
                game.stop();
                Game.broadcast(Component.text("게임 종료! 사라진 칸이 돌아오고 각자 지금 인벤토리를 그대로 가집니다.", NamedTextColor.YELLOW));
            }
            case "pause" -> {
                if (game.pause()) {
                    Game.broadcast(Component.text("일시정지 (한 칸 사라지기까지 " + LockTimer.words(timer.remaining()) + " 남음)", NamedTextColor.YELLOW));
                } else {
                    error(sender, "진행 중인 게임이 없거나 이미 일시정지 상태입니다.");
                }
            }
            case "resume" -> {
                if (game.resume()) {
                    Game.broadcast(Component.text("재개! 한 칸 사라지기까지 " + LockTimer.words(timer.remaining()), NamedTextColor.GREEN));
                } else {
                    error(sender, "일시정지된 게임이 없습니다.");
                }
            }
            case "interval" -> interval(sender, args, label);
            case "time", "add" -> time(sender, args, label, sub.equals("add"));
            case "lock", "unlock" -> lock(sender, args, label, sub.equals("lock"));
            case "status" -> status(sender);
            case "credits" -> {
                if (admin) {
                    game.playCredits();
                } else {
                    Credits.summary(game.roll()).forEach(sender::sendMessage);
                }
            }
            case "reload" -> {
                plugin.reloadSettings();
                reply(sender, "config.yml을 다시 읽었습니다. 주기: " + LockTimer.words(timer.interval()));
            }
            default -> help(sender, label, admin);
        }
        return true;
    }

    private void interval(CommandSender sender, String[] args, String label) {
        LockTimer timer = plugin.game().timer();
        if (args.length < 2) {
            reply(sender, "지금 주기: " + LockTimer.words(timer.interval()) + "마다 한 칸. 바꾸려면 /" + label + " interval <분>  예: 10, 5, 30s, 1m30s");
            return;
        }
        OptionalInt seconds = LockTimer.parseSeconds(args[1], 60);
        if (seconds.isEmpty() || seconds.getAsInt() <= 0) {
            error(sender, "사용법: /" + label + " interval <시간>  숫자만 쓰면 분입니다. 예: 10 (=10분), 30s, 1m30s, 1h");
            return;
        }
        int interval = LockTimer.clampInterval(seconds.getAsInt());
        plugin.saveSetting("lock-interval-seconds", interval);
        Game.broadcast(Component.text("이제 " + LockTimer.words(interval) + "마다 한 칸씩 사라집니다. (다음까지 "
                + LockTimer.words(timer.remaining()) + ")", NamedTextColor.YELLOW));
        if (interval != seconds.getAsInt()) {
            error(sender, "주기는 " + LockTimer.MIN_INTERVAL + "초~24시간 사이로 맞췄습니다.");
        }
    }

    private void time(CommandSender sender, String[] args, String label, boolean add) {
        Game game = plugin.game();
        LockTimer timer = game.timer();
        if (game.phase() != Game.Phase.RUNNING) {
            error(sender, "진행 중인 게임이 없습니다. 먼저 /" + label + " start");
            return;
        }
        OptionalInt seconds = args.length < 2 ? OptionalInt.empty() : LockTimer.parseSeconds(args[1], 1);
        if (seconds.isEmpty() || (!add && seconds.getAsInt() <= 0)) {
            error(sender, add ? "사용법: /" + label + " add <시간>  예: 30, 1m, -10 (숫자만 쓰면 초, 음수면 줄이기)"
                    : "사용법: /" + label + " time <시간>  예: 10, 45s, 2m (숫자만 쓰면 초)");
            return;
        }
        if (add) {
            timer.add(seconds.getAsInt());
        } else {
            timer.setRemaining(seconds.getAsInt());
        }
        game.updateBar();
        String change = add ? (seconds.getAsInt() >= 0 ? "+" : "-") + LockTimer.words(Math.abs(seconds.getAsInt())) + " → " : "";
        Game.broadcast(Component.text("한 칸 사라지기까지 " + change + LockTimer.words(timer.remaining()), NamedTextColor.YELLOW));
    }

    private void lock(CommandSender sender, String[] args, String label, boolean lock) {
        Game game = plugin.game();
        if (game.phase() == Game.Phase.IDLE) {
            error(sender, "진행 중인 게임이 없습니다. 먼저 /" + label + " start");
            return;
        }
        int count = 1;
        if (args.length >= 2) {
            if (!lock && (args[1].equalsIgnoreCase("all") || args[1].equals("전부"))) {
                count = SharedSlots.LOCKABLE;
            } else {
                try {
                    count = Integer.parseInt(args[1]);
                } catch (NumberFormatException e) {
                    count = 0;
                }
            }
        }
        if (count < 1 || count > SharedSlots.LOCKABLE) {
            error(sender, "사용법: /" + label + (lock ? " lock [개수]  지금 바로 칸을 없앱니다." : " unlock [개수|all]  가장 최근에 사라진 칸부터 되돌립니다."));
            return;
        }
        int done = lock ? game.lock(count) : game.unlock(count);
        if (done == 0) {
            error(sender, lock ? "더 없앨 칸이 없습니다." : "사라진 칸이 없습니다.");
        }
    }

    private void status(CommandSender sender) {
        Game game = plugin.game();
        LockTimer timer = game.timer();
        String state = switch (game.phase()) {
            case IDLE -> "꺼짐";
            case CLEARED -> "클리어!";
            case RUNNING -> timer.paused() ? "일시정지" : game.participants().isEmpty() ? "참가자 기다리는 중" : "진행 중";
        };
        reply(sender, "상태: " + state + " · 주기 " + LockTimer.words(timer.interval()) + "마다 한 칸");
        if (game.phase() == Game.Phase.IDLE) {
            return;
        }
        String next = game.phase() == Game.Phase.RUNNING && game.nextSlot() >= 0
                ? " · 다음: " + Game.slotName(game.nextSlot()) + " (" + LockTimer.clock(timer.remaining()) + " 뒤)" : "";
        line(sender, "남은 칸 " + game.openCount() + "/" + SharedSlots.SIZE + next + " · 진행 시간 " + LockTimer.clock(game.elapsed()));
        List<Integer> locked = game.lockedSlots();
        if (!locked.isEmpty()) {
            line(sender, "사라진 칸: " + locked.stream().map(Game::slotName).collect(Collectors.joining(", ")));
        }
        List<String> names = game.participants().stream().map(Player::getName).toList();
        line(sender, "공유 중: " + (names.isEmpty() ? "없음" : String.join(", ", names) + " (" + names.size() + "명)"));
    }

    private void help(CommandSender sender, String label, boolean admin) {
        reply(sender, "InveCat - 인벤토리 공유, " + LockTimer.words(plugin.game().timer().interval()) + "마다 한 칸씩 사라짐, 엔더 드래곤 처치 시 클리어");
        if (admin) {
            line(sender, "/" + label + " start · stop  시작 (모두의 인벤토리를 비움) · 종료");
            line(sender, "pause · resume  타이머 일시정지 · 재개");
            line(sender, "interval <시간>  칸이 사라지는 주기 (저장됨). 숫자만 쓰면 분: 10 = 10분, 30s, 1m30s");
            line(sender, "time <시간> · add <시간>  다음 칸까지 남은 시간 지정 · 늘리기 (-10 이면 줄이기)");
            line(sender, "lock [개수] · unlock [개수|all]  지금 바로 칸 없애기 · 최근에 사라진 칸 되돌리기");
            line(sender, "credits  크레딧 다시 보기 · status  상태 · reload  설정 다시 읽기");
        } else {
            line(sender, "/" + label + " status  상태 · credits  크레딧");
        }
        sender.sendMessage(Component.text(Credits.AUTHOR, NamedTextColor.AQUA));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        boolean admin = sender.hasPermission(InveCatPlugin.ADMIN);
        if (args.length == 1) {
            return filter(admin ? SUBCOMMANDS : PUBLIC, args[0]);
        }
        if (!admin || args.length != 2) {
            return List.of();
        }
        return switch (canonical(args[0])) {
            case "interval" -> filter(List.of("10", "5", "15", "30s", "1m"), args[1]);
            case "time" -> filter(List.of("5s", "10s", "30s", "1m"), args[1]);
            case "add" -> filter(List.of("30s", "1m", "5m", "-30s", "-1m"), args[1]);
            case "lock" -> filter(List.of("1", "2", "3"), args[1]);
            case "unlock" -> filter(List.of("1", "2", "3", "all"), args[1]);
            default -> List.of();
        };
    }

    private static String canonical(String input) {
        String lower = input.toLowerCase(Locale.ROOT);
        return ALIASES.getOrDefault(lower, lower);
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        return options.stream().filter(option -> option.toLowerCase(Locale.ROOT).startsWith(lower)).toList();
    }

    private static void reply(CommandSender sender, String message) {
        sender.sendMessage(InveCatPlugin.PREFIX.append(Component.text(message, NamedTextColor.WHITE)));
    }

    private static void line(CommandSender sender, String message) {
        sender.sendMessage(Component.text(message, NamedTextColor.GRAY));
    }

    private static void error(CommandSender sender, String message) {
        sender.sendMessage(InveCatPlugin.PREFIX.append(Component.text(message, NamedTextColor.RED)));
    }
}
