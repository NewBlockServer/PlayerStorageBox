package top.newblock.playerStorageBox;

import org.bukkit.ChatColor;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PriceCalculator {

    private final PlayerStorageBox plugin;
    private final Map<String, Long> namePrices = new HashMap<>();
    private final Map<String, Long> lorePrices = new HashMap<>();
    private final List<Combination> combinations = new ArrayList<>();

    public PriceCalculator(PlayerStorageBox plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        namePrices.clear();
        lorePrices.clear();
        combinations.clear();

        File file = new File(plugin.getDataFolder(), "prices.yml");
        if (!file.exists()) plugin.saveResource("prices.yml", false);
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);

        if (config.getConfigurationSection("prices.lore") != null) {
            for (String key : config.getConfigurationSection("prices.lore").getKeys(false)) {
                lorePrices.put(ChatColor.translateAlternateColorCodes('&', key), config.getLong("prices.lore." + key));
            }
        }

        if (config.getConfigurationSection("prices.name") != null) {
            for (String key : config.getConfigurationSection("prices.name").getKeys(false)) {
                namePrices.put(ChatColor.translateAlternateColorCodes('&', key), config.getLong("prices.name." + key));
            }
        }

        ConfigurationSection comboSec = config.getConfigurationSection("combinations");
        if (comboSec != null) {
            for (String key : comboSec.getKeys(false)) {
                ConfigurationSection sec = comboSec.getConfigurationSection(key);
                if (sec == null) continue;

                List<String> rawLore = sec.getStringList("required-lore");
                List<String> coloredLore = new ArrayList<>();
                for (String s : rawLore) coloredLore.add(ChatColor.translateAlternateColorCodes('&', s));

                double lMult = sec.getDouble("lore-multiplier", 1.0);
                String reqName = sec.getString("required-name", null);
                if (reqName != null) reqName = ChatColor.translateAlternateColorCodes('&', reqName);
                double nMult = sec.getDouble("name-multiplier", 1.0);

                combinations.add(new Combination(coloredLore, lMult, reqName, nMult));
            }
        }
    }

    public long calculateItemValue(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return 0;
        ItemMeta meta = item.getItemMeta();

        long baseValue = 0;

        if (meta.hasLore()) {
            for (String line : meta.getLore()) {
                for (Map.Entry<String, Long> entry : lorePrices.entrySet()) {
                    if (line.contains(entry.getKey())) {
                        baseValue += entry.getValue();
                    }
                }
            }
        }

        if (meta.hasDisplayName()) {
            String dName = meta.getDisplayName();
            for (Map.Entry<String, Long> entry : namePrices.entrySet()) {
                if (dName.equals(entry.getKey())) {
                    baseValue += entry.getValue();
                }
            }
        }

        if (baseValue == 0) return 0;

        double maxMultiplier = 1.0;

        if (meta.hasLore()) {
            List<String> itemLore = meta.getLore();
            String itemName = meta.hasDisplayName() ? meta.getDisplayName() : "";

            for (Combination combo : combinations) {
                if (containsAllLore(itemLore, combo.requiredLore)) {
                    double currentMultiplier = combo.loreMultiplier;
                    if (combo.requiredName != null && !combo.requiredName.isEmpty()) {
                        if (itemName.equals(combo.requiredName)) {
                            currentMultiplier *= combo.nameMultiplier;
                        }
                    }
                    if (currentMultiplier > maxMultiplier) {
                        maxMultiplier = currentMultiplier;
                    }
                }
            }
        }

        return (long) (baseValue * maxMultiplier);
    }

    private boolean containsAllLore(List<String> itemLore, List<String> required) {
        if (required.isEmpty()) return false;
        for (String reqLine : required) {
            boolean found = false;
            for (String iLine : itemLore) {
                if (iLine.contains(reqLine)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private static class Combination {
        List<String> requiredLore;
        double loreMultiplier;
        String requiredName;
        double nameMultiplier;

        public Combination(List<String> requiredLore, double loreMultiplier, String requiredName, double nameMultiplier) {
            this.requiredLore = requiredLore;
            this.loreMultiplier = loreMultiplier;
            this.requiredName = requiredName;
            this.nameMultiplier = nameMultiplier;
        }
    }
}