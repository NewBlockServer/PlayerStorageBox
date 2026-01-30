package top.newblock.playerStorageBox;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.io.File;

public final class PlayerStorageBox extends JavaPlugin {

    private StorageManager storageManager;
    private FileConfiguration langConfig;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLangConfig();

        SQLiteManager.init(this);
        storageManager = new StorageManager(this);

        getServer().getPluginManager().registerEvents(
                new StorageListener(this, storageManager), this
        );

        startBackupTask();
        getLogger().info("PlayerStorageBox 已启用。");
    }

    @Override
    public void onDisable() {
        if (storageManager != null) {
            storageManager.saveAllAndClose();
        }
        SQLiteManager.close();
    }

    private void startBackupTask() {
        int interval = getConfig().getInt("backup.interval", 30);
        if (interval <= 0) return;

        new BukkitRunnable() {
            @Override
            public void run() {
                String fileName = SQLiteManager.backup(PlayerStorageBox.this);
                if (fileName != null) {
                    getLogger().info("自动备份成功: " + fileName);
                }
            }
        }.runTaskTimerAsynchronously(this, 20L * 60 * interval, 20L * 60 * interval);
    }

    public void loadLangConfig() {
        File langFile = new File(getDataFolder(), "lang.yml");
        if (!langFile.exists()) {
            saveResource("lang.yml", false);
        }
        langConfig = YamlConfiguration.loadConfiguration(langFile);
    }

    public String getLang(String path) {
        String msg = langConfig.getString(path, "Missing lang: " + path);
        String prefix = getConfig().getString("prefix", "&6NewBlock&e>> ");
        return ChatColor.translateAlternateColorCodes('&', prefix + msg);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) return true;

        if (args.length == 0) {
            storageManager.open(player, player.getUniqueId(), 1);
            return true;
        }

        String targetName = args[0];
        if (targetName.matches("\\d+")) {
            int page = Math.max(1, Integer.parseInt(targetName));
            storageManager.open(player, player.getUniqueId(), page);
            return true;
        }

        if (!player.hasPermission("playerstoragebox.admin")) {
            player.sendMessage(getLang("no-permission-others"));
            return true;
        }

        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            player.sendMessage(getLang("player-offline"));
            return true;
        }

        int page = 1;
        if (args.length > 1) {
            try {
                page = Math.max(1, Integer.parseInt(args[1]));
            } catch (Exception ignored) {}
        }

        storageManager.open(player, target.getUniqueId(), page);
        return true;
    }
}