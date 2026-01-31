package top.newblock.playerStorageBox;

import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class PriceEvaluator {
    private final Map<String, Double> namePrices = new HashMap<>();
    private final Map<String, Double> lorePrices = new HashMap<>();
    private final PlayerStorageBox plugin;

    public PriceEvaluator(PlayerStorageBox plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        namePrices.clear();
        lorePrices.clear();
        File file = new File(plugin.getDataFolder(), "prices.yml");
        if (!file.exists()) plugin.saveResource("prices.yml", false);
        FileConfiguration config = YamlConfiguration.loadConfiguration(file);

        if (config.getConfigurationSection("prices.name") != null) {
            for (String key : config.getConfigurationSection("prices.name").getKeys(false)) {
                namePrices.put(ChatColor.translateAlternateColorCodes('&', key), config.getDouble("prices.name." + key));
            }
        }
        if (config.getConfigurationSection("prices.lore") != null) {
            for (String key : config.getConfigurationSection("prices.lore").getKeys(false)) {
                lorePrices.put(ChatColor.translateAlternateColorCodes('&', key), config.getDouble("prices.lore." + key));
            }
        }
    }

    public double getItemPrice(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        ItemMeta meta = item.getItemMeta();
        double price = 0;

        // 1. 名字完全匹配 (带颜色)
        if (meta.hasDisplayName()) {
            String name = meta.getDisplayName();
            if (namePrices.containsKey(name)) {
                price = namePrices.get(name);
            }
        }

        // 2. Lore 包含匹配
        if (price == 0 && meta.hasLore()) {
            for (String line : meta.getLore()) {
                for (Map.Entry<String, Double> entry : lorePrices.entrySet()) {
                    if (line.contains(entry.getKey())) {
                        return entry.getValue(); // 匹配到 Lore 立即返回
                    }
                }
            }
        }

        return price;
    }
}