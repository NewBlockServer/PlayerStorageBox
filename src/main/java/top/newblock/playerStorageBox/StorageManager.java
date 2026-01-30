package top.newblock.playerStorageBox;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class StorageManager {

    private final PlayerStorageBox plugin;
    private final Gson gson = new Gson();
    private final Map<UUID, Map<Integer, Inventory>> activeInventories = new HashMap<>();

    public StorageManager(PlayerStorageBox plugin) {
        this.plugin = plugin;
    }

    public void open(org.bukkit.entity.Player viewer, UUID ownerUUID, int page) {
        // 如果缓存没有，则去数据库加载
        Map<Integer, Inventory> pages = activeInventories.computeIfAbsent(ownerUUID, k -> loadFromDatabase(ownerUUID));

        // 获取对应页码，如果不存在则创建新页面
        Inventory inv = pages.get(page);
        if (inv == null) {
            inv = Bukkit.createInventory(new StorageHolder(ownerUUID, page), 54, "仓库 - 第 " + page + " 页");
            pages.put(page, inv);
        }

        viewer.openInventory(inv);
    }

    public void saveSinglePage(UUID ownerUUID, int page, Inventory inv) {
        Map<Integer, Inventory> pages = activeInventories.computeIfAbsent(ownerUUID, k -> loadFromDatabase(ownerUUID));
        pages.put(page, inv);
        saveToDatabase(ownerUUID, pages);
    }

    public void removeActiveIfEmpty(UUID ownerUUID, int page, Inventory inv) {
        // 这里可以根据需要添加从内存卸载的逻辑
    }

    private void saveToDatabase(UUID uuid, Map<Integer, Inventory> pages) {
        try {
            Map<Integer, String> serializedData = new HashMap<>();
            for (Map.Entry<Integer, Inventory> entry : pages.entrySet()) {
                serializedData.put(entry.getKey(), serialize(entry.getValue()));
            }

            String json = gson.toJson(serializedData);
            try (PreparedStatement ps = SQLiteManager.get().prepareStatement(
                    "INSERT OR REPLACE INTO player_vaults (uuid, data) VALUES (?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, json);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            plugin.getLogger().severe("保存玩家 " + uuid + " 数据时发生错误!");
            e.printStackTrace();
        }
    }

    private Map<Integer, Inventory> loadFromDatabase(UUID uuid) {
        Map<Integer, Inventory> pages = new HashMap<>();
        try (PreparedStatement ps = SQLiteManager.get().prepareStatement(
                "SELECT data FROM player_vaults WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                String rawData = rs.getString("data");
                if (rawData == null || rawData.isEmpty()) return pages;

                // 判断是否是新版 JSON 格式
                if (rawData.trim().startsWith("{")) {
                    Map<Integer, String> serializedData = gson.fromJson(rawData, new TypeToken<Map<Integer, String>>() {}.getType());
                    for (Map.Entry<Integer, String> entry : serializedData.entrySet()) {
                        try {
                            pages.put(entry.getKey(), deserialize(entry.getValue(), uuid, entry.getKey()));
                        } catch (Exception e) {
                            plugin.getLogger().severe("解析玩家 " + uuid + " 第 " + entry.getKey() + " 页数据失败！");
                        }
                    }
                } else {
                    // 旧版单页数据兼容处理
                    try {
                        Inventory oldInv = deserialize(rawData, uuid, 1);
                        pages.put(1, oldInv);
                        plugin.getLogger().info("已成功兼容并加载玩家 " + uuid + " 的旧版仓库数据。");
                    } catch (Exception e) {
                        plugin.getLogger().severe("解析玩家 " + uuid + " 的旧版数据失败！Base64 可能损坏。");
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return pages;
    }

    public void saveAllAndClose() {
        for (Map.Entry<UUID, Map<Integer, Inventory>> entry : activeInventories.entrySet()) {
            saveToDatabase(entry.getKey(), entry.getValue());
        }
    }

    private String serialize(Inventory inv) throws Exception {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        BukkitObjectOutputStream dataOutput = new BukkitObjectOutputStream(outputStream);
        dataOutput.writeInt(inv.getSize());
        for (int i = 0; i < inv.getSize(); i++) {
            dataOutput.writeObject(inv.getItem(i));
        }
        dataOutput.close();
        // 使用标准 Base64 (不带换行)
        return Base64.getEncoder().encodeToString(outputStream.toByteArray());
    }

    private Inventory deserialize(String data, UUID owner, int page) throws Exception {
        // 【关键修复】使用 getMimeDecoder 以兼容旧版 SnakeYAML 产生的带换行符的 Base64 字符串
        byte[] bytes = Base64.getMimeDecoder().decode(data.replace("\r", "").replace("\n", ""));
        ByteArrayInputStream inputStream = new ByteArrayInputStream(bytes);
        BukkitObjectInputStream dataInput = new BukkitObjectInputStream(inputStream);

        int size = dataInput.readInt();
        Inventory inv = Bukkit.createInventory(new StorageHolder(owner, page), size, "仓库 - 第 " + page + " 页");

        for (int i = 0; i < size; i++) {
            inv.setItem(i, (ItemStack) dataInput.readObject());
        }
        dataInput.close();
        return inv;
    }
}