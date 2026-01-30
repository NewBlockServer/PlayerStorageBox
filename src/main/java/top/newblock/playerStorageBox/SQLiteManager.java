package top.newblock.playerStorageBox;

import java.io.File;
import java.io.IOException;
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
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();
            dbFile = new File(plugin.getDataFolder(), "storage.db");

            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());

            try (Statement st = connection.createStatement()) {
                st.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_vaults (
                        uuid TEXT PRIMARY KEY,
                        data TEXT NOT NULL
                    )
                """);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public static String backup(PlayerStorageBox plugin) {
        if (dbFile == null || !dbFile.exists()) return null;

        File backupFolder = new File(plugin.getDataFolder(), "backup");
        if (!backupFolder.exists()) backupFolder.mkdirs();

        String timeStamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        File backupFile = new File(backupFolder, "storage_backup_" + timeStamp + ".db");

        try {
            Files.copy(dbFile.toPath(), backupFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
            return backupFile.getName();
        } catch (IOException e) {
            plugin.getLogger().warning("备份数据库失败: " + e.getMessage());
            return null;
        }
    }

    public static Connection get() {
        return connection;
    }

    public static void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (Exception ignored) {}
    }
}