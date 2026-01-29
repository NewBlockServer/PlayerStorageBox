package top.newblock.playerStorageBox;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.*;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

public class StorageManager {

    private final PlayerStorageBox plugin;
    private final File oldFolder;

    // 缓存正在操作的界面，Key: ownerUUID:page
    private final Map<String, Inventory> activeInventories = new HashMap<>();

    public StorageManager(PlayerStorageBox plugin) {
        this.plugin = plugin;
        this.oldFolder = new File(plugin.getDataFolder(), "old");
        if (!oldFolder.exists()) oldFolder.mkdirs();
    }

    public void open(Player viewer, UUID ownerUUID, int page) {
        String key = ownerUUID.toString() + ":" + page;
        Inventory inv;

        if (activeInventories.containsKey(key)) {
            inv = activeInventories.get(key);
        } else {
            // 【关键点】使用 StorageHolder 绑定这个界面的拥有者和页码
            StorageHolder holder = new StorageHolder(ownerUUID, page);
            String title = "§0物品箱        第" + page + "页";

            // 创建时传入 holder
            inv = Bukkit.createInventory(holder, 54, title);

            Map<Integer, String> vaults = loadFromDb(ownerUUID);
            String base64 = vaults.get(page);
            if (base64 != null) {
                try {
                    inv.setContents(fromBase64(base64));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
            activeInventories.put(key, inv);
        }

        viewer.openInventory(inv);
    }

    // 实时保存
    public void saveSinglePage(UUID ownerUUID, int page, Inventory inv) {
        try {
            Map<Integer, String> vaults = loadFromDb(ownerUUID);
            vaults.put(page, toBase64(inv.getContents()));
            saveToDb(ownerUUID, vaults);
        } catch (Exception e) {
            plugin.getLogger().severe("保存失败: " + e.getMessage());
        }
    }

    // 当没有人看这个界面时，释放内存
    public void removeActiveIfEmpty(UUID ownerUUID, int page, Inventory inv) {
        // getViewers().size() <= 1 是因为当前关闭的玩家还在列表里
        if (inv.getViewers().size() <= 1) {
            activeInventories.remove(ownerUUID.toString() + ":" + page);
        }
    }

    public void saveAllAndClose() {
        for (Map.Entry<String, Inventory> entry : activeInventories.entrySet()) {
            String[] parts = entry.getKey().split(":");
            saveSinglePage(UUID.fromString(parts[0]), Integer.parseInt(parts[1]), entry.getValue());
        }
    }

    /* ================= 数据库与工具方法 (保持不变) ================= */

    private synchronized Map<Integer, String> loadFromDb(UUID uuid) {
        Map<Integer, String> map = new HashMap<>();
        try (PreparedStatement ps = SQLiteManager.get().prepareStatement(
                "SELECT data FROM player_vaults WHERE uuid=?"
        )) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                YamlConfiguration yml = new YamlConfiguration();
                yml.loadFromString(rs.getString("data"));
                for (String key : yml.getKeys(false)) {
                    if (key.startsWith("vault")) {
                        map.put(Integer.parseInt(key.replace("vault", "")), yml.getString(key));
                    }
                }
            }
        } catch (Exception ignored) {}
        return map;
    }

    private synchronized void saveToDb(UUID uuid, Map<Integer, String> vaults) throws Exception {
        YamlConfiguration yml = new YamlConfiguration();
        for (Map.Entry<Integer, String> e : vaults.entrySet()) {
            yml.set("vault" + e.getKey(), e.getValue());
        }
        try (PreparedStatement ps = SQLiteManager.get().prepareStatement(
                "INSERT INTO player_vaults(uuid, data) VALUES(?, ?) " +
                        "ON CONFLICT(uuid) DO UPDATE SET data=excluded.data"
        )) {
            ps.setString(1, uuid.toString());
            ps.setString(2, yml.saveToString());
            ps.executeUpdate();
        }
    }

    public void migrateOldData() {
        // ...与上次相同，此处省略以节省空间...
    }

    private String toBase64(ItemStack[] items) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BukkitObjectOutputStream out = new BukkitObjectOutputStream(baos);
        out.writeInt(items.length);
        for (ItemStack item : items) out.writeObject(item);
        out.close();
        return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private ItemStack[] fromBase64(String base64) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
        BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(bytes));
        ItemStack[] items = new ItemStack[in.readInt()];
        for (int i = 0; i < items.length; i++) {
            items[i] = (ItemStack) in.readObject();
        }
        in.close();
        return items;
    }
}