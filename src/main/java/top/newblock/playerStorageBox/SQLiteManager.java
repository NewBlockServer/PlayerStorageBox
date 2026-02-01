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

            try { Class.forName("org.sqlite.JDBC"); } catch (ClassNotFoundException ignored) {}

            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.executeUpdate("CREATE TABLE IF NOT EXISTS player_vaults (uuid TEXT PRIMARY KEY, data TEXT NOT NULL)");
                updateSchema(st);
            }
        } catch (Exception e) {
            e.printStackTrace();
            plugin.getLogger().severe("数据库初始化失败！");
        }
    }

    private static void updateSchema(Statement st) {
        try {
            boolean hasTotalValue = false;
            try (ResultSet rs = connection.getMetaData().getColumns(null, null, "player_vaults", "total_value")) {
                if (rs.next()) hasTotalValue = true;
            }
            if (!hasTotalValue) {
                pluginInstance.getLogger().info("更新数据库: 添加 total_value 列...");
                st.executeUpdate("ALTER TABLE player_vaults ADD COLUMN total_value BIGINT DEFAULT 0");
            }
        } catch (Exception e) { pluginInstance.getLogger().warning("数据库结构检查失败: " + e.getMessage()); }
    }

    public static String backup(PlayerStorageBox plugin) {
        try {
            File folder = new File(plugin.getDataFolder(), "backup");
            if (!folder.exists()) folder.mkdirs();
            String time = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File backFile = new File(folder, "storage_" + time + ".db");
            Files.copy(dbFile.toPath(), backFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return backFile.getName();
        } catch (Exception e) { e.printStackTrace(); return null; }
    }

    public static Connection get() { return connection; }
    public static void close() { try { if (connection != null && !connection.isClosed()) connection.close(); } catch (Exception ignored) {} }
}