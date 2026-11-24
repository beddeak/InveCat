package io.github.beddeak.invecat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.PluginCommand;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.logging.Level;

public final class InveCatPlugin extends JavaPlugin {

    static final Component PREFIX = Component.text("[인벤캣] ", NamedTextColor.GOLD);
    static final String ADMIN = "invecat.admin";

    private Settings settings;
    private Game game;
    private File dataFile;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        settings = Settings.load(getConfig(), getLogger());
        dataFile = new File(getDataFolder(), "data.yml");
        game = new Game(this);
        loadState();
        getServer().getPluginManager().registerEvents(new GameListener(this), this);
        InveCatCommand command = new InveCatCommand(this);
        PluginCommand pluginCommand = getCommand("invecat");
        if (pluginCommand != null) {
            pluginCommand.setExecutor(command);
            pluginCommand.setTabCompleter(command);
        }
        for (Player player : getServer().getOnlinePlayers()) {
            game.onJoin(player);
        }
        getServer().getScheduler().runTaskTimer(this, game::sync, 1L, 1L);
        getServer().getScheduler().runTaskTimer(this, game::tickSecond, 20L, 20L);
        getLogger().info("InveCat " + getPluginMeta().getVersion() + " - " + Credits.AUTHOR);
    }

    @Override
    public void onDisable() {
        if (game != null) {
            game.shutdown();
            saveState();
        }
    }

    Settings settings() {
        return settings;
    }

    Game game() {
        return game;
    }

    void reloadSettings() {
        reloadConfig();
        settings = Settings.load(getConfig(), getLogger());
        game.timer().setInterval(settings.lockSeconds());
        game.updateBar();
    }

    void saveSetting(String path, Object value) {
        getConfig().set(path, value);
        saveConfig();
        reloadSettings();
    }

    void saveState() {
        YamlConfiguration yaml = new YamlConfiguration();
        game.save(yaml);
        try {
            yaml.save(dataFile);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "게임 상태를 저장하지 못했습니다: " + dataFile, e);
        }
    }

    private void loadState() {
        if (!dataFile.isFile()) {
            return;
        }
        try {
            YamlConfiguration yaml = new YamlConfiguration();
            yaml.load(dataFile);
            game.load(yaml);
        } catch (IOException | InvalidConfigurationException | RuntimeException e) {
            File broken = new File(getDataFolder(), "data-broken-" + System.currentTimeMillis() + ".yml");
            getLogger().log(Level.SEVERE, "게임 상태를 읽지 못해 새로 시작합니다. 원래 파일은 " + broken.getName() + "에 남겨 둡니다.", e);
            try {
                Files.copy(dataFile.toPath(), broken.toPath());
            } catch (IOException copyError) {
                getLogger().log(Level.SEVERE, "data.yml을 백업하지 못했습니다.", copyError);
            }
            game = new Game(this);
        }
    }
}
