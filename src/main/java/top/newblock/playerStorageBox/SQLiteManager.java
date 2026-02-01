package top.newblock.playerStorageBox;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;

public class SQLiteManager {
    private static Connection connection;
    private static File dbFile;
    private static PlayerStorageBox pluginInstance;

    public static void init(PlayerStorageBox plugin) {
        pluginInstance = plugin;
        try {
            dbFile = new File(plugin.getDataFolder(), "storage.db");
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();

            try {
                Class.forName("org.sqlite.JDBC");
            } catch (ClassNotFoundException e) {
                // 忽略
            }

            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                // 1. 创建表（如果不存在）
                st.executeUpdate("CREATE TABLE IF NOT EXISTS player_vaults (" +
                        "uuid TEXT PRIMARY KEY, " +
                        "data TEXT NOT NULL)"); // 先创建基础表

                // 2. 检查并更新表结构 (修复 missing column error)
                updateSchema(st);
            }
        } catch (Exception e) {
            e.printStackTrace();
            plugin.getLogger().severe("无法初始化数据库！");
        }
    }

    private static void updateSchema(Statement st) {
        try {
            // 检查 total_value 列是否存在
            boolean hasTotalValue = false;
            try (ResultSet rs = connection.getMetaData().getColumns(null, null, "player_vaults", "total_value")) {
                if (rs.next()) hasTotalValue = true;
            }

            // 如果不存在，则添加该列
            if (!hasTotalValue) {
                pluginInstance.getLogger().info("正在更新数据库结构: 添加 total_value 列...");
                st.executeUpdate("ALTER TABLE player_vaults ADD COLUMN total_value BIGINT DEFAULT 0");
                pluginInstance.getLogger().info("数据库更新完成。");
            }
        } catch (Exception e) {
            pluginInstance.getLogger().warning("检查数据库结构时出错: " + e.getMessage());
        }
    }

    public static String backup(PlayerStorageBox plugin) {
        try {
            File folder = new File(plugin.getDataFolder(), "backup");
            if (!folder.exists()) {
                folder.mkdirs();
            }

            String time = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File backFile = new File(folder, "storage_" + time + ".db");

            Files.copy(dbFile.toPath(), backFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

            return backFile.getName();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static Connection get() { return connection; }

    public static void close() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (Exception ignored) {}
    }
}