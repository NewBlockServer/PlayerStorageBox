package top.newblock.playerStorageBox;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.text.SimpleDateFormat;
import java.util.Date;

public class SQLiteManager {
    private static Connection connection;
    private static File dbFile;

    public static void init(PlayerStorageBox plugin) {
        try {
            dbFile = new File(plugin.getDataFolder(), "storage.db");
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                // 使用 BIGINT 存储整数价值
                st.executeUpdate("CREATE TABLE IF NOT EXISTS player_vaults (" +
                        "uuid TEXT PRIMARY KEY, " +
                        "data TEXT NOT NULL, " +
                        "total_value BIGINT DEFAULT 0)");
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    public static String backup(PlayerStorageBox plugin) {
        try {
            File folder = new File(plugin.getDataFolder(), "backup");
            if (!folder.exists()) folder.mkdirs();
            String time = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
            File backFile = new File(folder, "storage_" + time + ".db");
            Files.copy(dbFile.toPath(), backFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return backFile.getName();
        } catch (Exception e) { return null; }
    }

    public static Connection get() { return connection; }
    public static void close() { try { if (connection != null) connection.close(); } catch (Exception ignored) {} }
}