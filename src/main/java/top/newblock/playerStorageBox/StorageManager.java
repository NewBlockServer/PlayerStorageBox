package top.newblock.playerStorageBox;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.regex.Pattern;

public class StorageManager {

    private final PlayerStorageBox plugin;
    private final Gson gson = new Gson();
    private final Map<UUID, Map<Integer, Inventory>> activeInventories = new HashMap<>();
    private YamlConfiguration priceConfig;

    public StorageManager(PlayerStorageBox plugin) {
        this.plugin = plugin;
        reloadPriceConfig();
    }

    public void reloadPriceConfig() {
        File file = new File(plugin.getDataFolder(), "prices.yml");
        if (!file.exists()) plugin.saveResource("prices.yml", false);
        this.priceConfig = YamlConfiguration.loadConfiguration(file);
    }

    /* ================= 价值计算逻辑 (改为 long) ================= */

    public long calculateInventoryValue(Collection<Inventory> inventories) {
        long total = 0;
        Map<String, Object> namePrices = priceConfig.getConfigurationSection("prices.name") != null ?
                priceConfig.getConfigurationSection("prices.name").getValues(false) : new HashMap<>();
        Map<String, Object> lorePrices = priceConfig.getConfigurationSection("prices.lore") != null ?
                priceConfig.getConfigurationSection("prices.lore").getValues(false) : new HashMap<>();

        for (Inventory inv : inventories) {
            for (ItemStack item : inv.getContents()) {
                if (item == null || item.getType() == Material.AIR) continue;
                total += getItemValue(item, namePrices, lorePrices) * item.getAmount();
            }
        }
        return total;
    }

    private long getItemValue(ItemStack item, Map<String, Object> namePrices, Map<String, Object> lorePrices) {
        if (!item.hasItemMeta()) return 0;
        ItemMeta meta = item.getItemMeta();

        if (meta.hasDisplayName()) {
            String displayName = meta.getDisplayName();
            for (Map.Entry<String, Object> entry : namePrices.entrySet()) {
                String target = ChatColor.translateAlternateColorCodes('&', entry.getKey());
                if (displayName.equals(target)) return Long.parseLong(entry.getValue().toString());
            }
        }

        if (meta.hasLore()) {
            for (String line : meta.getLore()) {
                for (Map.Entry<String, Object> entry : lorePrices.entrySet()) {
                    String target = ChatColor.translateAlternateColorCodes('&', entry.getKey());
                    if (line.contains(target)) return Long.parseLong(entry.getValue().toString());
                }
            }
        }
        return 0;
    }

    /* ================= 数据库操作 ================= */

    public long getCachedValue(UUID uuid) {
        try (PreparedStatement ps = SQLiteManager.get().prepareStatement("SELECT total_value FROM player_vaults WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) return rs.getLong("total_value");
        } catch (Exception e) { e.printStackTrace(); }
        return 0;
    }

    public void saveToDatabase(UUID uuid, Map<Integer, Inventory> pages) {
        try {
            long totalValue = calculateInventoryValue(pages.values());
            Map<Integer, String> data = new HashMap<>();
            for (Map.Entry<Integer, Inventory> e : pages.entrySet()) {
                data.put(e.getKey(), serialize(e.getValue()));
            }

            try (PreparedStatement ps = SQLiteManager.get().prepareStatement(
                    "INSERT OR REPLACE INTO player_vaults(uuid, data, total_value) VALUES (?, ?, ?)")) {
                ps.setString(1, uuid.toString());
                ps.setString(2, gson.toJson(data));
                ps.setLong(3, totalValue);
                ps.executeUpdate();
            }
        } catch (Exception e) { e.printStackTrace(); }
    }

    public Map<Integer, Inventory> loadFromDatabase(UUID uuid) {
        Map<Integer, Inventory> pages = new HashMap<>();
        try (PreparedStatement ps = SQLiteManager.get().prepareStatement("SELECT data FROM player_vaults WHERE uuid=?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                Map<Integer, String> map = gson.fromJson(rs.getString("data"), new TypeToken<Map<Integer, String>>() {}.getType());
                for (Map.Entry<Integer, String> e : map.entrySet()) {
                    pages.put(e.getKey(), deserialize(e.getValue(), uuid, e.getKey()));
                }
            }
        } catch (Exception e) { e.printStackTrace(); }
        return pages;
    }

    public long getTotalValue(UUID uuid) {
        if (activeInventories.containsKey(uuid)) {
            return calculateInventoryValue(activeInventories.get(uuid).values());
        }
        return getCachedValue(uuid);
    }

    /* ================= 基础逻辑 ================= */

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
        for (Map.Entry<UUID, Map<Integer, Inventory>> entry : activeInventories.entrySet()) {
            saveToDatabase(entry.getKey(), entry.getValue());
        }
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

    private boolean isMatch(String text, String pattern) {
        if (text == null) return false;
        if (!pattern.contains("*")) return text.equals(pattern);
        String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q");
        return text.matches(regex);
    }

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
                List<Inventory> currentVaultItems = new ArrayList<>();

                for (Map.Entry<Integer, String> entry : pageData.entrySet()) {
                    Inventory inv = deserialize(entry.getValue(), uuid, entry.getKey());
                    boolean pageModified = false;
                    for (ItemStack item : inv.getContents()) {
                        if (item == null || !item.hasItemMeta()) continue;
                        ItemMeta meta = item.getItemMeta();
                        if (mode.equalsIgnoreCase("name")) {
                            if (meta.hasDisplayName() && isMatch(meta.getDisplayName(), oldTarget)) {
                                meta.setDisplayName(newTarget); item.setItemMeta(meta); pageModified = true; count++;
                            }
                        } else if (mode.equalsIgnoreCase("lore") && meta.hasLore()) {
                            List<String> lore = meta.getLore(); boolean loreChanged = false;
                            for (int i = 0; i < lore.size(); i++) {
                                if (isMatch(lore.get(i), oldTarget)) { lore.set(i, newTarget); loreChanged = true; count++; }
                            }
                            if (loreChanged) { meta.setLore(lore); item.setItemMeta(meta); pageModified = true; }
                        }
                    }
                    if (pageModified) { entry.setValue(serialize(inv)); modified = true; }
                    currentVaultItems.add(inv);
                }
                if (modified) {
                    long newValue = calculateInventoryValue(currentVaultItems);
                    try (PreparedStatement ups = SQLiteManager.get().prepareStatement("UPDATE player_vaults SET data=?, total_value=? WHERE uuid=?")) {
                        ups.setString(1, gson.toJson(pageData));
                        ups.setLong(2, newValue);
                        ups.setString(3, uuid.toString());
                        ups.executeUpdate();
                    }
                }
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
                List<Inventory> currentVaultItems = new ArrayList<>();

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
                        } else if (mode.equalsIgnoreCase("lore") && meta.hasLore()) {
                            for (String line : meta.getLore()) { if (isMatch(line, target)) { shouldDelete = true; break; } }
                        }
                        if (shouldDelete) { inv.setItem(i, null); pageModified = true; count++; }
                    }
                    if (pageModified) { entry.setValue(serialize(inv)); modified = true; }
                    currentVaultItems.add(inv);
                }
                if (modified) {
                    long newValue = calculateInventoryValue(currentVaultItems);
                    try (PreparedStatement ups = SQLiteManager.get().prepareStatement("UPDATE player_vaults SET data=?, total_value=? WHERE uuid=?")) {
                        ups.setString(1, gson.toJson(pageData));
                        ups.setLong(2, newValue);
                        ups.setString(3, uuid.toString());
                        ups.executeUpdate();
                    }
                }
            }
        }
        return count;
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