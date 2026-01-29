package top.newblock.playerStorageBox;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

public class SQLiteManager {
    private static Connection connection;

    public static void init(PlayerStorageBox plugin) {
        try {
            File db = new File(plugin.getDataFolder(), "storage.db");
            if (!plugin.getDataFolder().exists()) plugin.getDataFolder().mkdirs();

            connection = DriverManager.getConnection("jdbc:sqlite:" + db.getAbsolutePath());

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

    public static Connection get() {
        return connection;
    }

    public static void close() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (Exception ignored) {}
    }
}