package core.com.rylinaux.plugman.config;

import core.com.rylinaux.plugman.config.model.PlugManConfig;
import core.com.rylinaux.plugman.config.model.ResourceMappingsConfig;
import core.com.rylinaux.plugman.logging.PluginLogger;
import core.com.rylinaux.plugman.util.ImmutableWarnList;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;

/**
 * Platform-agnostic configuration manager for PlugMan including config validation, migration, and resource mapping.
 *
 * @author rylinaux
 */
@RequiredArgsConstructor
public class PlugManConfigurationManager {
    public static final int CURRENT_CONFIG_VERSION = 4;
    private static final Map<String, MessageListDefaults> VERSION_4_MESSAGES = Map.of(
            "messages.yml", new MessageListDefaults(
                    "&9Paper Plugins (&b{0}&9): {1}",
                    "&9Bukkit Plugins (&e{0}&9): {1}"),
            "messages_de.yml", new MessageListDefaults(
                    "&9Paper-Plugins (&b{0}&9): {1}",
                    "&9Bukkit-Plugins (&e{0}&9): {1}"),
            "messages_es.yml", new MessageListDefaults(
                    "&9Plugins Paper (&b{0}&9): {1}",
                    "&9Plugins Bukkit (&e{0}&9): {1}"),
            "messages_cn.yml", new MessageListDefaults(
                    "&9Paper 插件（&b{0}&9）：{1}",
                    "&9Bukkit 插件（&e{0}&9）：{1}"),
            "messages_jp.yml", new MessageListDefaults(
                    "&9Paper プラグイン（&b{0}&9）：{1}",
                    "&9Bukkit プラグイン（&e{0}&9）：{1}"),
            "messages_ru.yml", new MessageListDefaults(
                    "&9Paper-плагины (&b{0}&9): {1}",
                    "&9Bukkit-плагины (&e{0}&9): {1}"),
            "messages_tw.yml", new MessageListDefaults(
                    "&9Paper 插件（&b{0}&9）：{1}",
                    "&9Bukkit 插件（&e{0}&9）：{1}")
    );

    private final YamlConfigurationProvider configProvider;
    private final PluginLogger logger;
    private final JacksonConfigurationService jacksonConfigService;


    /**
     * List of plugins to ignore, partially.
     */
    @Getter
    private List<String> ignoredPlugins = null;
    /**
     * Jackson-based configuration object
     */
    @Getter
    private PlugManConfig plugManConfig;
    @Getter
    private ResourceMappingsConfig resourceMappingsConfig;

    /**
     * Initialize and validate configuration
     */
    public void initializeConfiguration() {
        configProvider.saveDefaultConfig();
        loadJacksonConfigurations();
        validateAndMigrateConfig();
        loadIgnoredPlugins();
    }

    /**
     * Load Jackson-based configurations
     */
    private void loadJacksonConfigurations() {
        try {
            var configFile = new File(configProvider.getDataFolder(), "config.yml");
            plugManConfig = jacksonConfigService.loadPlugManConfig(configFile);

            var resourceMappingsFile = new File(configProvider.getDataFolder(), "resourcemaps.yml");
            resourceMappingsConfig = jacksonConfigService.loadResourceMappings(resourceMappingsFile);
        } catch (Exception e) {
            logger.severe("Failed to load Jackson configurations: " + e.getMessage());
            // Fallback to default configurations
            plugManConfig = jacksonConfigService.createDefaultConfig();
            resourceMappingsConfig = jacksonConfigService.createDefaultResourceMappings();
        }
    }

    /**
     * Validate configuration and create new one if invalid
     */
    private void validateAndMigrateConfig() {
        if (!isConfigValid()) {
            logger.severe("Invalid PlugMan config detected! Creating new one...");
            backupOldConfig();
            configProvider.saveDefaultConfig();
            logger.info("New config created!");
        }

        migrateConfigIfNeeded();
    }

    /**
     * Check if current configuration is valid
     */
    private boolean isConfigValid() {
        return plugManConfig != null &&
                plugManConfig.getAutoLoad() != null &&
                plugManConfig.getAutoUnload() != null &&
                plugManConfig.getAutoReload() != null &&
                plugManConfig.getIgnoredPlugins() != null;
    }

    /**
     * Backup old configuration file
     */
    private void backupOldConfig() {
        var oldConfig = new File(configProvider.getDataFolder(), "config.yml");
        var backupConfig = new File(configProvider.getDataFolder(), "config.yml.old-" + System.currentTimeMillis());
        oldConfig.renameTo(backupConfig);
    }

    /**
     * Migrate configuration to newer version if needed
     */
    private void migrateConfigIfNeeded() {
        var configVersion = plugManConfig.getVersion();

        var startTime = System.currentTimeMillis();

        while (configVersion < CURRENT_CONFIG_VERSION) {
            if (System.currentTimeMillis() - startTime > 10000) {
                logger.severe("PlugMan failed to migrate config to version " + CURRENT_CONFIG_VERSION + "! (Timed out)");
                break;
            }

            configVersion = plugManConfig.getVersion();

            if (configVersion <= 1) {
                migrateToVersion2();
                continue;
            }

            if (configVersion == 2) {
                migrateToVersion3();
                continue;
            }

            if (configVersion == 3) migrateToVersion4();
        }
    }

    private void migrateToVersion4() {
        plugManConfig.setVersion(4);
        plugManConfig.setPaperReloadDebug(false);
        migrateMessagesToVersion4();
        saveJacksonConfiguration();

        logger.info("Migrated config to version 4, you can now enable Paper reload debug logs with 'paperReloadDebug: true'.");
    }

    private void migrateMessagesToVersion4() {
        var dataFolder = configProvider.getDataFolder();
        migrateMessagesFileToVersion4(new File(dataFolder, "messages.yml"), VERSION_4_MESSAGES.get("messages.yml"));

        var messagesFolder = new File(dataFolder, "messages");
        for (var entry : VERSION_4_MESSAGES.entrySet()) {
            if (entry.getKey().equals("messages.yml")) continue;
            migrateMessagesFileToVersion4(new File(messagesFolder, entry.getKey()), entry.getValue());
        }
    }

    private void migrateMessagesFileToVersion4(File messagesFile, MessageListDefaults defaults) {
        if (!messagesFile.exists()) return;

        try {
            var lines = Files.readAllLines(messagesFile.toPath(), StandardCharsets.UTF_8);
            var updatedLines = addMissingVersion4MessageEntries(lines, messagesFile.getName(), defaults);
            if (updatedLines == null) return;

            Files.write(messagesFile.toPath(), updatedLines, StandardCharsets.UTF_8);
            logger.info("Added missing version 4 messages to " + messagesFile.getName() + ".");
        } catch (IOException exception) {
            logger.warning("Failed to migrate " + messagesFile.getName() + " messages to version 4: " + exception.getMessage());
        }
    }

    private List<String> addMissingListMessageEntries(List<String> lines, MessageListDefaults defaults) {
        var listSectionIndex = findTopLevelSection(lines, "list:");
        var listSectionEnd = listSectionIndex == -1 ? lines.size() : findSectionEnd(lines, listSectionIndex);
        var hasPaperMessage = hasListMessage(lines, listSectionIndex, listSectionEnd, "paper");
        var hasBukkitMessage = hasListMessage(lines, listSectionIndex, listSectionEnd, "bukkit");

        if (hasPaperMessage && hasBukkitMessage) return null;

        var updatedLines = new ArrayList<>(lines);
        if (listSectionIndex == -1) {
            if (!updatedLines.isEmpty() && !updatedLines.get(updatedLines.size() - 1).isBlank()) updatedLines.add("");
            updatedLines.add("list:");
            listSectionEnd = updatedLines.size();
        }

        var insertAt = listSectionIndex == -1 ? listSectionEnd : findSectionEnd(updatedLines, listSectionIndex);
        if (!hasBukkitMessage) updatedLines.add(insertAt, "  bukkit: '" + defaults.bukkitMessage() + "'");
        if (!hasPaperMessage) updatedLines.add(insertAt, "  paper: '" + defaults.paperMessage() + "'");

        return updatedLines;
    }

    private List<String> addMissingVersion4MessageEntries(List<String> lines, String fileName, MessageListDefaults defaults) {
        var updatedLines = addMissingListMessageEntries(lines, defaults);
        var changed = updatedLines != null;
        if (updatedLines == null) updatedLines = new ArrayList<>(lines);
        changed |= addMissingMessageEntry(updatedLines, "enable", "failed", getEnableFailedMessage(fileName));
        changed |= addMissingMessageEntry(updatedLines, "load", "missing-dependencies", getMissingDependenciesMessage(fileName));

        return changed ? updatedLines : null;
    }

    private boolean addMissingMessageEntry(List<String> lines, String section, String key, String message) {
        var sectionIndex = findTopLevelSection(lines, section + ":");
        var sectionEnd = sectionIndex == -1 ? lines.size() : findSectionEnd(lines, sectionIndex);
        if (hasListMessage(lines, sectionIndex, sectionEnd, key)) return false;

        if (sectionIndex == -1) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) lines.add("");
            lines.add(section + ":");
            lines.add("  " + key + ": '" + message + "'");
            return true;
        }

        lines.add(sectionEnd, "  " + key + ": '" + message + "'");
        return true;
    }

    private String getEnableFailedMessage(String fileName) {
        if (fileName.equals("messages_de.yml")) {
            return "&c{0} konnte nicht aktiviert werden. Prüfe den Server-Log für den Plugin-Fehler.";
        }

        return "&c{0} could not be enabled. Check the server log for the plugin error.";
    }

    private String getMissingDependenciesMessage(String fileName) {
        if (fileName.equals("messages_de.yml")) {
            return "&c{0} konnte nicht geladen werden. Fehlende Pflicht-Abhängigkeiten: {1}";
        }

        return "&cCould not load {0}. Missing required dependencies: {1}";
    }

    private int findTopLevelSection(List<String> lines, String sectionName) {
        for (var i = 0; i < lines.size(); i++) {
            if (lines.get(i).trim().equals(sectionName) && !lines.get(i).startsWith(" ")) return i;
        }

        return -1;
    }

    private int findSectionEnd(List<String> lines, int sectionIndex) {
        for (var i = sectionIndex + 1; i < lines.size(); i++) {
            var line = lines.get(i);
            if (!line.isBlank() && !line.startsWith(" ") && !line.startsWith("#")) return i;
        }

        return lines.size();
    }

    private boolean hasListMessage(List<String> lines, int listSectionIndex, int listSectionEnd, String key) {
        if (listSectionIndex == -1) return false;

        var expectedPrefix = "  " + key + ":";
        for (var i = listSectionIndex + 1; i < listSectionEnd; i++) {
            if (lines.get(i).startsWith(expectedPrefix)) return true;
        }

        return false;
    }

    private record MessageListDefaults(String paperMessage, String bukkitMessage) {
    }

    private void migrateToVersion3() {
        plugManConfig.setVersion(3);
        plugManConfig.setShowPaperWarning(true);
        saveJacksonConfiguration();

        logger.info("Migrated config to version 3, you can now disable the Paper warning in the config.yml.");
    }

    /**
     * Migrate configuration to version 2
     */
    private void migrateToVersion2() {
        plugManConfig.setVersion(2);
        saveJacksonConfiguration();

        logger.warning("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
        logger.warning("As of 2.4.0, the download command has been removed!");
        logger.warning("If you weren't using it, you can just ignore this message.");
        logger.warning("This message will only display once!");
        logger.warning("~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~");
    }

    /**
     * Save Jackson configuration to file
     */
    private void saveJacksonConfiguration() {
        try {
            var configFile = new File(configProvider.getDataFolder(), "config.yml");
            jacksonConfigService.savePlugManConfig(plugManConfig, configFile);
        } catch (Exception e) {
            logger.severe("Failed to save Jackson configuration: " + e.getMessage());
        }
    }

    /**
     * Load ignored plugins from configuration
     */
    private void loadIgnoredPlugins() {
        var ignoredPluginsTemp = new java.util.ArrayList<>(plugManConfig.getIgnoredPlugins());
        ignoredPluginsTemp.add("PlugMan");
        ignoredPluginsTemp.add("PlugManX");
        ignoredPluginsTemp.add("PlugManVelocity");
        ignoredPluginsTemp.add("PlugManBungee");

        ignoredPlugins = new ImmutableWarnList<>(ignoredPluginsTemp);
    }


    /**
     * Get notification setting for broken command removal
     */
    public boolean shouldNotifyOnBrokenCommandRemoval() {
        return plugManConfig.isNotifyOnBrokenCommandRemoval();
    }
}
