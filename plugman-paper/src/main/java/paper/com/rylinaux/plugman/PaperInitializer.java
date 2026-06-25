package paper.com.rylinaux.plugman;

/*
 * #%L
 * PlugMan
 * %%
 * Copyright (C) 2010 - 2014 PlugMan
 * %%
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 * #L%
 */

import bukkit.com.rylinaux.plugman.PlugManBukkit;
import bukkit.com.rylinaux.plugman.pluginmanager.BukkitPluginManager;
import core.com.rylinaux.plugman.config.PlugManConfigurationManager;
import core.com.rylinaux.plugman.plugins.PluginManager;
import core.com.rylinaux.plugman.util.reflection.ClassAccessor;
import lombok.RequiredArgsConstructor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import paper.com.rylinaux.plugman.pluginmanager.PaperPluginManager;
import paper.com.rylinaux.plugman.reloadstrategy.LegacyPaperReloadStrategy;
import paper.com.rylinaux.plugman.reloadstrategy.ModernPaperReloadStrategy;
import paper.com.rylinaux.plugman.reloadstrategy.PaperReloadStrategy;
import paper.com.rylinaux.plugman.util.PaperReflectionNames;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles paper-specific initialization logic for PlugMan.
 *
 * @author rylinaux
 */
@RequiredArgsConstructor
public class PaperInitializer {

    private final PlugManBukkit plugin;
    private static final Pattern VERSION_NUMBER_PATTERN = Pattern.compile("\\d+");
    private static final int MODERN_PAPER_VERSION = 2005;
    private static final String WARNING_BORDER = ChatColor.DARK_GRAY + "~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~";
    private static final String WARNING_COLOR = ChatColor.YELLOW.toString();
    private static final String DETAIL_COLOR = ChatColor.GRAY.toString();
    private static final String VALUE_COLOR = ChatColor.AQUA.toString();
    private static final String LINK_COLOR = ChatColor.BLUE.toString();
    private PaperReloadStrategy paperReloadStrategy;

    /**
     * Initialize the plugin manager based on server type, returning paper plugin manager if on paper
     */
    public PluginManager initializePaperPluginManager(BukkitPluginManager bukkitPluginManager) {
        if (!ClassAccessor.classExists(PaperReflectionNames.PAPER_PLUGIN_MANAGER)) {
            plugin.getLogger().warning("We're in a paper environment, but cannot find Paper's plugin manager?!");
            return bukkitPluginManager;
        }

        paperReloadStrategy = resolvePaperReloadStrategy();
        return paperReloadStrategy.createPluginManager(bukkitPluginManager);
    }

    private int parsePaperVersion(String bukkitVersion) {
        List<Integer> numbers = new ArrayList<>();
        Matcher matcher = VERSION_NUMBER_PATTERN.matcher(bukkitVersion == null ? "" : bukkitVersion);
        while (matcher.find()) {
            numbers.add(Integer.parseInt(matcher.group()));
        }
        if (numbers.isEmpty()) {
            plugin.getLogger().warning("Could not parse Paper version from '" + bukkitVersion + "'. Falling back to legacy plugin manager.");
            return 0;
        }
        if (numbers.get(0) == 1) {
            int minor = numbers.size() > 1 ? numbers.get(1) : 0;
            int patch = numbers.size() > 2 ? numbers.get(2) : 0;
            return minor * 100 + patch;
        }
        int major = numbers.get(0);
        int minor = numbers.size() > 1 ? numbers.get(1) : 0;
        return major * 100 + minor;
    }

    /**
     * Show Paper warning if running on Paper server
     */
    public void showPaperWarningIfNeeded(PluginManager pluginManager) {
        if (!(pluginManager instanceof PaperPluginManager)) return;

        var config = plugin.getServiceRegistry().get(PlugManConfigurationManager.class);
        if (!config.getPlugManConfig().isShowPaperWarning()) return;

        var strategy = paperReloadStrategy == null ? resolvePaperReloadStrategy() : paperReloadStrategy;

        sendColoredPaperWarning(WARNING_BORDER);
        sendColoredPaperWarning(WARNING_COLOR + "It seems like you're running on " + VALUE_COLOR + Bukkit.getName() + " (" + Bukkit.getVersion() + ")" + WARNING_COLOR + ".");
        if (config.getPlugManConfig().isPaperReloadDebug()) showPaperRuntimeDiagnostics(strategy);
        sendColoredPaperWarning(WARNING_COLOR + "PlugManX Paper plugin reload support is experimental.");
        sendColoredPaperWarning(WARNING_COLOR + "Also, if you encounter any issues, please join my discord: " + LINK_COLOR + "https://discord.gg/GxEFhVY6ff");
        sendColoredPaperWarning(WARNING_COLOR + "Or create an issue on GitHub: " + LINK_COLOR + "https://github.com/Test-Account666/PlugManX");
        sendColoredPaperWarning(WARNING_BORDER);

        plugin.getLogger().info("You can disable this warning by setting 'showPaperWarning' to false in the config.yml");
    }

    private void showPaperRuntimeDiagnostics(PaperReloadStrategy strategy) {
        sendColoredPaperWarning(DETAIL_COLOR + "Detected server software: " + VALUE_COLOR + Bukkit.getName());
        sendColoredPaperWarning(DETAIL_COLOR + "Paper version: " + VALUE_COLOR + Bukkit.getVersion());
        sendColoredPaperWarning(DETAIL_COLOR + "Bukkit version: " + VALUE_COLOR + Bukkit.getBukkitVersion());
        sendColoredPaperWarning(DETAIL_COLOR + "Paper reload strategy: " + VALUE_COLOR + strategy.getName());
        sendColoredPaperWarning(DETAIL_COLOR + "Modern Paper lifecycle events available: " + VALUE_COLOR + formatAvailability(strategy.isModernLifecycleAvailable()));
    }

    private void sendColoredPaperWarning(String message) {
        Bukkit.getConsoleSender().sendMessage(ChatColor.GREEN + "[PlugManX] " + message);
    }

    private PaperReloadStrategy resolvePaperReloadStrategy() {
        var paperVersion = parsePaperVersion(Bukkit.getBukkitVersion());
        if (paperVersion >= MODERN_PAPER_VERSION) return new ModernPaperReloadStrategy();
        return new LegacyPaperReloadStrategy();
    }

    private String formatAvailability(boolean available) {
        return available ? "yes" : "no";
    }

}
