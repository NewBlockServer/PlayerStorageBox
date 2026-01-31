package top.newblock.playerStorageBox;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.regex.Pattern;

public class StorageManager {

    private final PlayerStorageBox plugin;
    private final Gson gson = new Gson();
    private final Map<UUID, Map<Integer, Inventory>> activeInventories = new HashMap<>();

    public StorageManager(PlayerStorageBox plugin) { this.plugin = plugin; }

    /* ================= 匹配算法 (支持通配符 *) ================= */

    private boolean isMatch(String text, String pattern) {
        if (text == null) return false;
        if (!pattern.contains("*")) return text.equals(pattern); // 无通配符时执行完整匹配

        // 将通配符 * 转换为正则 .* 并转义其他正则特殊字符
        String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q");
        return text.matches(regex);
    }

    /* ================= 批量管理功能 ================= */

    public int bulkReplace(String mode, String oldText, String newText) throws Exception {
        String oldTarget = ChatColor.translateAlternateColorCodes('&', oldText);
        String newTarget = ChatColor.translateAlternateColorCodes('&', newText);
        int count = 0;

        try (PreparedStatement ps = SQLiteManager.get().prepareStatement("SELECT uuid, data FROM player_vaults")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                Map<Integer, String> pageData = gson.fromJson(rs.getString("data"), new TypeToken<Map<Integer, String>>() {}.getType());
                boolean modified = false;

                for (Map.Entry<Integer, String> entry : pageData.entrySet()) {
                    Inventory inv = deserialize(entry.getValue(), uuid, entry.getKey());
                    boolean pageModified = false;

                    for (ItemStack item : inv.getContents()) {
                        if (item == null || !item.hasItemMeta()) continue;
                        ItemMeta meta = item.getItemMeta();

                        if (mode.equalsIgnoreCase("name")) {
                            if (meta.hasDisplayName() && isMatch(meta.getDisplayName(), oldTarget)) {
                                meta.setDisplayName(newTarget);
                                item.setItemMeta(meta);
                                pageModified = true; count++;
                            }
                        } else if (mode.equalsIgnoreCase("lore")) {
                            if (meta.hasLore()) {
                                List<String> lore = meta.getLore();
                                boolean loreChanged = false;
                                for (int i = 0; i < lore.size(); i++) {
                                    if (isMatch(lore.get(i), oldTarget)) {
                                        lore.set(i, newTarget);
                                        loreChanged = true; count++;
                                    }
                                }
                                if (loreChanged) {
                                    meta.setLore(lore);
                                    item.setItemMeta(meta);
                                    pageModified = true;
                                }
                            }
                        }
                    }
                    if (pageModified) { entry.setValue(serialize(inv)); modified = true; }
                }
                if (modified) saveRawDataToDB(uuid, pageData);
            }
        }
        return count;
    }

    public int bulkDelete(String mode, String targetText) throws Exception {
        String target = ChatColor.translateAlternateColorCodes('&', targetText);
        int count = 0;

        try (PreparedStatement ps = SQLiteManager.get().prepareStatement("SELECT uuid, data FROM player_vaults")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                Map<Integer, String> pageData = gson.fromJson(rs.getString("data"), new TypeToken<Map<Integer, String>>() {}.getType());
                boolean modified = false;

                for (Map.Entry<Integer, String> entry : pageData.entrySet()) {
                    Inventory inv = deserialize(entry.getValue(), uuid, entry.getKey());
                    boolean pageModified = false;
                    ItemStack[] contents = inv.getContents();

                    for (int i = 0; i < contents.length; i++) {
                        ItemStack item = contents[i];
                        if (item == null || !item.hasItemMeta()) continue;
                        ItemMeta meta = item.getItemMeta();

                        boolean shouldDelete = false;
                        if (mode.equalsIgnoreCase("name")) {
                            if (meta.hasDisplayName() && isMatch(meta.getDisplayName(), target)) shouldDelete = true;
                        } else if (mode.equalsIgnoreCase("lore")) {
                            if (meta.hasLore()) {
                                for (String line : meta.getLore()) {
                                    if (isMatch(line, target)) { shouldDelete = true; break; }
                                }
                            }
                        }

                        if (shouldDelete) { inv.setItem(i, null); pageModified = true; count++; }
                    }
                    if (pageModified) { entry.setValue(serialize(inv)); modified = true; }
                }
                if (modified) saveRawDataToDB(uuid, pageData);
            }
        }
        return count;
    }

    /* ================= 基础逻辑 & 序列化 (保持之前版本) ================= */

    public void open(org.bukkit.entity.Player viewer, UUID ownerUUID, int page) {
        Map<Integer, Inventory> pages = activeInventories.computeIfAbsent(ownerUUID, this::loadFromDatabase);
        Inventory inv = pages.get(page);
        if (inv == null) {
            inv = Bukkit.createInventory(new StorageHolder(ownerUUID, page), 54, "§0物品箱        第" + page + "页");
            pages.put(page, inv);
        }
        viewer.openInventory(inv);
    }

    public void saveSinglePage(UUID ownerUUID, int page, Inventory inv) {
        Map<Integer, Inventory> pages = activeInventories.computeIfAbsent(ownerUUID, this::loadFromDatabase);
        pages.put(page, inv);
        saveToDatabase(ownerUUID, pages);
    }

    public void saveAllAndClose() {
        for (Map.Entry<UUID, Map<Integer, Inventory>> entry : activeInventories.entrySet()) saveToDatabase(entry.getKey(), entry.getValue());
    }

    private void saveRawDataToDB(UUID uuid, Map<Integer, String> data) throws Exception {
        try (PreparedStatement ps = SQLiteManager.get().prepareStatement("INSERT OR REPLACE INTO player_vaults(uuid, data) VALUES (?, ?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, gson.toJson(data));
            ps.executeUpdate();
        }
    }

    private void saveToDatabase(UUID uuid, Map<Integer, Inventory> pages) {
        try {
            Map<Integer, String> data = new HashMap<>();
            for (Map.Entry<Integer, Inventory> e : pages.entrySet()) data.put(e.getKey(), serialize(e.getValue()));
            saveRawDataToDB(uuid, data);
        } catch (Exception e) { e.printStackTrace(); }
    }

    private Map<Integer, Inventory> loadFromDatabase(UUID uuid) {
        Map<Integer, Inventory> pages = new HashMap<>();
        try (PreparedStatement ps = SQLiteManager.get().prepareStatement("SELECT data FROM player_vaults WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<Integer, String> map = gson.fromJson(rs.getString("data"), new TypeToken<Map<Integer, String>>() {}.getType());
                for (Map.Entry<Integer, String> e : map.entrySet()) pages.put(e.getKey(), deserialize(e.getValue(), uuid, e.getKey()));
            }
        } catch (Exception e) { e.printStackTrace(); }
        return pages;
    }

    private String serialize(Inventory inv) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        BukkitObjectOutputStream out = new BukkitObjectOutputStream(baos);
        out.writeInt(inv.getSize());
        for (int i = 0; i < inv.getSize(); i++) out.writeObject(inv.getItem(i));
        out.close(); return Base64.getEncoder().encodeToString(baos.toByteArray());
    }

    private Inventory deserialize(String base64, UUID owner, int page) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(base64.replaceAll("\\s", ""));
        BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(bytes));
        int size = in.readInt();
        Inventory inv = Bukkit.createInventory(new StorageHolder(owner, page), size, "§0物品箱        第" + page + "页");
        for (int i = 0; i < size; i++) inv.setItem(i, (ItemStack) in.readObject());
        in.close(); return inv;
    }

    public List<SearchResult> searchItems(String type, String keyword) {
        List<SearchResult> results = new ArrayList<>();
        String query = ChatColor.translateAlternateColorCodes('&', keyword).toLowerCase();
        try (PreparedStatement ps = SQLiteManager.get().prepareStatement("SELECT uuid, data FROM player_vaults")) {
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                UUID uuid = UUID.fromString(rs.getString("uuid"));
                Map<Integer, String> pageData = gson.fromJson(rs.getString("data"), new TypeToken<Map<Integer, String>>() {}.getType());
                for (Map.Entry<Integer, String> entry : pageData.entrySet()) {
                    try {
                        Inventory inv = deserialize(entry.getValue(), uuid, entry.getKey());
                        for (ItemStack item : inv.getContents()) {
                            if (item == null || item.getType() == Material.AIR) continue;
                            if (checkMatch(item, type, query)) results.add(new SearchResult(uuid, entry.getKey(), item.clone()));
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return results;
    }

    private boolean checkMatch(ItemStack item, String type, String query) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return item.getType().name().toLowerCase().contains(query);
        return switch (type.toLowerCase()) {
            case "name" -> meta.hasDisplayName() && meta.getDisplayName().toLowerCase().contains(query);
            case "lore" -> meta.hasLore() && meta.getLore().stream().anyMatch(l -> l.toLowerCase().contains(query));
            case "data" -> meta.toString().toLowerCase().contains(query);
            default -> false;
        };
    }

    public record SearchResult(UUID owner, int page, ItemStack item) {}
}