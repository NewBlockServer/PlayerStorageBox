package top.newblock.playerStorageBox;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.Arrays;
import java.util.Comparator;

public final class PlayerStorageBox extends JavaPlugin {

    private StorageManager storageManager;
    private PriceCalculator priceCalculator;
    private FileConfiguration langConfig;
    private BukkitTask backupTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadLangConfig();
        SQLiteManager.init(this);

        // 初始化计算器
        priceCalculator = new PriceCalculator(this);
        // 初始化仓库管理器
        storageManager = new StorageManager(this, priceCalculator);

        if (Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null) {
            new StoragePlaceholder(this, storageManager).register();
        }

        CommandHandler cmdHandler = new CommandHandler(this, storageManager);
        getCommand("psb").setExecutor(cmdHandler);
        getCommand("psb").setTabCompleter(cmdHandler);

        getServer().getPluginManager().registerEvents(new StorageListener(this, storageManager), this);
        startAutoBackup();
    }

    @Override
    public void onDisable() {
        if (backupTask != null && !backupTask.isCancelled()) {
            backupTask.cancel();
        }
        if (storageManager != null) {
            storageManager.saveAllAndClose();
        }
        SQLiteManager.close();
    }

    public void loadLangConfig() {
        File f = new File(getDataFolder(), "lang.yml");
        if (!f.exists()) saveResource("lang.yml", false);
        langConfig = YamlConfiguration.loadConfiguration(f);
    }

    public void reloadAllConfigs() {
        reloadConfig();
        loadLangConfig();
        priceCalculator.reload();
        startAutoBackup();
    }

    private void startAutoBackup() {
        if (backupTask != null && !backupTask.isCancelled()) {
            backupTask.cancel();
        }
        boolean enabled = getConfig().getBoolean("backup.enabled", true);
        if (!enabled) return;

        long intervalMinutes = getConfig().getLong("backup.interval", 60);
        if (intervalMinutes <= 0) return;

        long intervalTicks = intervalMinutes * 60 * 20;

        getLogger().info("自动备份已启用，间隔: " + intervalMinutes + " 分钟。");

        backupTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
            String fileName = SQLiteManager.backup(this);
            if (fileName != null) {
                getLogger().info("自动备份成功: " + fileName);
                // 备份成功后，执行清理旧备份的逻辑
                cleanOldBackups();
            } else {
                getLogger().warning("自动备份失败，请检查文件权限或磁盘空间。");
            }
        }, intervalTicks, intervalTicks);
    }

    /**
     * 清理旧备份文件，只保留配置文件中指定的份数
     */
    private void cleanOldBackups() {
        int maxBackups = getConfig().getInt("backup.max-backups", 10);
        if (maxBackups <= 0) return;

        File backupDir = new File(getDataFolder(), "backups");
        if (!backupDir.exists() || !backupDir.isDirectory()) return;

        // 获取备份目录下所有的文件 (假设备份文件以 .db 结尾)
        File[] files = backupDir.listFiles((dir, name) -> name.endsWith(".db"));

        if (files != null && files.length > maxBackups) {
            // 按修改时间从小到大排序（最旧的在前）
            Arrays.sort(files, Comparator.comparingLong(File::lastModified));

            int deleteCount = files.length - maxBackups;
            for (int i = 0; i < deleteCount; i++) {
                if (files[i].delete()) {
                    getLogger().info("已清理旧备份文件: " + files[i].getName());
                }
            }
        }
    }

    public String getLang(String key) {
        String prefix = getConfig().getString("prefix", "&6NewBlock&e>> ");
        String msg = langConfig.getString(key, "Missing key: " + key);
        return ChatColor.translateAlternateColorCodes('&', prefix + msg);
    }
}