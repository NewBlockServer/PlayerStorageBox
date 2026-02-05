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
import java.util.regex.Pattern;

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

        // 1. 加载 Lore 基础价值
        if (config.getConfigurationSection("prices.lore") != null) {
            for (String key : config.getConfigurationSection("prices.lore").getKeys(false)) {
                lorePrices.put(ChatColor.translateAlternateColorCodes('&', key), config.getLong("prices.lore." + key));
            }
        }

        // 2. 加载 Name 基础价值
        if (config.getConfigurationSection("prices.name") != null) {
            for (String key : config.getConfigurationSection("prices.name").getKeys(false)) {
                namePrices.put(ChatColor.translateAlternateColorCodes('&', key), config.getLong("prices.name." + key));
            }
        }

        // 3. 加载组合规则
        ConfigurationSection comboSec = config.getConfigurationSection("combinations");
        if (comboSec != null) {
            for (String key : comboSec.getKeys(false)) {
                ConfigurationSection sec = comboSec.getConfigurationSection(key);
                if (sec == null) continue;

                List<String> rawLore = sec.getStringList("required-lore");
                List<String> coloredLore = new ArrayList<>();
                for (String s : rawLore) coloredLore.add(ChatColor.translateAlternateColorCodes('&', s));

                double loreMult = sec.getDouble("lore-multiplier", 0.0);
                long loreVal = sec.getLong("lore-value", 0);
                boolean hasLoreMult = sec.contains("lore-multiplier");

                List<NameRule> nameRules = new ArrayList<>();
                ConfigurationSection nameSec = sec.getConfigurationSection("name-settings");
                if (nameSec != null) {
                    for (String namePattern : nameSec.getKeys(false)) {
                        String coloredNamePattern = ChatColor.translateAlternateColorCodes('&', namePattern);
                        double nMult = 0.0;
                        long nVal = 0;
                        boolean hasNMult = false;

                        if (nameSec.isConfigurationSection(namePattern)) {
                            ConfigurationSection sub = nameSec.getConfigurationSection(namePattern);
                            if (sub != null) {
                                if (sub.contains("multiplier")) {
                                    nMult = sub.getDouble("multiplier");
                                    hasNMult = true;
                                }
                                nVal = sub.getLong("value", 0);
                            }
                        } else {
                            nMult = nameSec.getDouble(namePattern);
                            hasNMult = true;
                        }
                        nameRules.add(new NameRule(coloredNamePattern, nMult, nVal, hasNMult));
                    }
                }
                combinations.add(new Combination(coloredLore, loreMult, loreVal, hasLoreMult, nameRules));
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
                    if (isMatch(line, entry.getKey(), false)) baseValue += entry.getValue();
                }
            }
        }
        if (meta.hasDisplayName()) {
            String dName = meta.getDisplayName();
            for (Map.Entry<String, Long> entry : namePrices.entrySet()) {
                if (isMatch(dName, entry.getKey(), true)) baseValue += entry.getValue();
            }
        }

        if (baseValue == 0) return 0;

        long totalAddedValue = 0;
        double totalMultiplier = 0.0;
        boolean anyMultiplierApplied = false;

        if (meta.hasLore()) {
            List<String> itemLore = meta.getLore();
            String itemName = meta.hasDisplayName() ? meta.getDisplayName() : "";

            for (Combination combo : combinations) {
                boolean isGlobal = combo.requiredLore.size() == 1 && combo.requiredLore.get(0).equals("*");
                if (isGlobal || containsAllLore(itemLore, combo.requiredLore)) {
                    totalAddedValue += combo.loreValue;
                    if (combo.hasLoreMultiplier) {
                        totalMultiplier += combo.loreMultiplier;
                        anyMultiplierApplied = true;
                    }

                    for (NameRule rule : combo.nameRules) {
                        if (isMatch(itemName, rule.pattern, true)) {
                            totalAddedValue += rule.value;
                            if (rule.hasMultiplier) {
                                totalMultiplier += rule.multiplier;
                                anyMultiplierApplied = true;
                            }
                            break;
                        }
                    }
                }
            }
        }

        if (!anyMultiplierApplied) {
            totalMultiplier = 1.0;
        }

        double finalResult = (baseValue + totalAddedValue) * totalMultiplier;
        return (long) finalResult;
    }

    private boolean containsAllLore(List<String> itemLore, List<String> required) {
        if (required.isEmpty()) return false;
        for (String reqLine : required) {
            boolean found = false;
            for (String iLine : itemLore) {
                if (isMatch(iLine, reqLine, false)) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
    }

    private boolean isMatch(String text, String pattern, boolean exactIfNoWildcard) {
        if (text == null || pattern == null) return false;
        if (pattern.contains("*")) {
            String regex = Pattern.quote(pattern).replace("*", "\\E.*\\Q");
            return text.matches(regex);
        }
        return exactIfNoWildcard ? text.equals(pattern) : text.contains(pattern);
    }

    private static class Combination {
        List<String> requiredLore;
        double loreMultiplier;
        long loreValue;
        boolean hasLoreMultiplier;
        List<NameRule> nameRules;

        public Combination(List<String> requiredLore, double loreMultiplier, long loreValue, boolean hasLoreMultiplier, List<NameRule> nameRules) {
            this.requiredLore = requiredLore;
            this.loreMultiplier = loreMultiplier;
            this.loreValue = loreValue;
            this.hasLoreMultiplier = hasLoreMultiplier;
            this.nameRules = nameRules;
        }
    }

    private static class NameRule {
        String pattern;
        double multiplier;
        long value;
        boolean hasMultiplier;

        public NameRule(String pattern, double multiplier, long value, boolean hasMultiplier) {
            this.pattern = pattern;
            this.multiplier = multiplier;
            this.value = value;
            this.hasMultiplier = hasMultiplier;
        }
    }
}