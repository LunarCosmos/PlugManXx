package core.com.rylinaux.plugman.config;

import core.com.rylinaux.plugman.logging.PluginLogger;
import lombok.RequiredArgsConstructor;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class MessageMigrationService {
    public static final String DEFAULT_MESSAGES_FILE = "messages.yml";
    public static final String GERMAN_MESSAGES_FILE = "messages_de.yml";

    private static final String MESSAGES_FOLDER = "messages";
    private static final MessageDefaults DEFAULT_MESSAGES = new MessageDefaults(
            "&9Paper Plugins (&b{0}&9): {1}",
            "&9Bukkit Plugins (&e{0}&9): {1}",
            "&c{0} could not be enabled. Check the server log for the plugin error.",
            "&cCould not load {0}. Missing required dependencies: {1}");
    private static final MessageDefaults GERMAN_MESSAGES = new MessageDefaults(
            "&9Paper-Plugins (&b{0}&9): {1}",
            "&9Bukkit-Plugins (&e{0}&9): {1}",
            "&c{0} konnte nicht aktiviert werden. Prüfe den Server-Log für den Plugin-Fehler.",
            "&c{0} konnte nicht geladen werden. Fehlende Pflicht-Abhängigkeiten: {1}");
    private static final Map<String, MessageDefaults> VERSION_4_MESSAGES = Map.of(
            DEFAULT_MESSAGES_FILE, DEFAULT_MESSAGES,
            GERMAN_MESSAGES_FILE, GERMAN_MESSAGES,
            "messages_es.yml", DEFAULT_MESSAGES,
            "messages_cn.yml", DEFAULT_MESSAGES,
            "messages_jp.yml", DEFAULT_MESSAGES,
            "messages_ru.yml", DEFAULT_MESSAGES,
            "messages_tw.yml", DEFAULT_MESSAGES
    );

    private final File dataFolder;
    private final PluginLogger logger;

    public void migrateToVersion4() {
        migrateMessagesFile(new File(dataFolder, DEFAULT_MESSAGES_FILE), VERSION_4_MESSAGES.get(DEFAULT_MESSAGES_FILE));

        var messagesFolder = new File(dataFolder, MESSAGES_FOLDER);
        for (var entry : VERSION_4_MESSAGES.entrySet()) {
            if (entry.getKey().equals(DEFAULT_MESSAGES_FILE)) continue;
            migrateMessagesFile(new File(messagesFolder, entry.getKey()), entry.getValue());
        }
    }

    private void migrateMessagesFile(File messagesFile, MessageDefaults defaults) {
        if (!messagesFile.exists()) return;

        try {
            var lines = Files.readAllLines(messagesFile.toPath(), StandardCharsets.UTF_8);
            var updatedLines = addMissingVersion4Entries(lines, defaults);
            if (updatedLines == null) return;

            Files.write(messagesFile.toPath(), updatedLines, StandardCharsets.UTF_8);
            logger.info("Added missing version 4 messages to " + messagesFile.getName() + ".");
        } catch (IOException exception) {
            logger.warning("Failed to migrate " + messagesFile.getName() + " messages to version 4: " + exception.getMessage());
        }
    }

    private List<String> addMissingVersion4Entries(List<String> lines, MessageDefaults defaults) {
        var updatedLines = new ArrayList<>(lines);
        var changed = false;

        changed |= addMissingMessageEntry(updatedLines, "list", "paper", defaults.paperMessage());
        changed |= addMissingMessageEntry(updatedLines, "list", "bukkit", defaults.bukkitMessage());
        changed |= addMissingMessageEntry(updatedLines, "enable", "failed", defaults.enableFailedMessage());
        changed |= addMissingMessageEntry(updatedLines, "load", "missing-dependencies", defaults.missingDependenciesMessage());

        return changed ? updatedLines : null;
    }

    private boolean addMissingMessageEntry(List<String> lines, String section, String key, String message) {
        var sectionIndex = findTopLevelSection(lines, section + ":");
        var sectionEnd = sectionIndex == -1 ? lines.size() : findSectionEnd(lines, sectionIndex);
        if (hasMessageEntry(lines, sectionIndex, sectionEnd, key)) return false;

        if (sectionIndex == -1) {
            if (!lines.isEmpty() && !lines.get(lines.size() - 1).isBlank()) lines.add("");
            lines.add(section + ":");
            lines.add("  " + key + ": '" + message + "'");
            return true;
        }

        lines.add(sectionEnd, "  " + key + ": '" + message + "'");
        return true;
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

    private boolean hasMessageEntry(List<String> lines, int sectionIndex, int sectionEnd, String key) {
        if (sectionIndex == -1) return false;

        var expectedPrefix = "  " + key + ":";
        for (var i = sectionIndex + 1; i < sectionEnd; i++) {
            if (lines.get(i).startsWith(expectedPrefix)) return true;
        }

        return false;
    }

    private record MessageDefaults(String paperMessage,
                                   String bukkitMessage,
                                   String enableFailedMessage,
                                   String missingDependenciesMessage) {
    }
}
