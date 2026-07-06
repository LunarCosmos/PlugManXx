package core.com.rylinaux.plugman.commands.executables;

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

import core.com.rylinaux.plugman.commands.AbstractCommand;
import core.com.rylinaux.plugman.commands.CommandSender;
import core.com.rylinaux.plugman.config.PlugManConfigurationManager;
import core.com.rylinaux.plugman.plugins.Plugin;
import core.com.rylinaux.plugman.services.ServiceRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

abstract class CascadingPluginCommand extends AbstractCommand {
    private static final int CONFIRM_ALL_PLUGIN_THRESHOLD = 10;
    private static final String CONFIRM_ARGUMENT = "confirm";

    protected CascadingPluginCommand(CommandSender sender,
                                     String name,
                                     String description,
                                     String permission,
                                     String[] subPermissions,
                                     String usage,
                                     ServiceRegistry registry) {
        super(sender, name, description, permission, subPermissions, usage, registry);
    }

    @Override
    public void execute(CommandSender sender, String label, String[] args) {
        if (!validateArguments(label, args, 2)) return;

        if (args[1].equalsIgnoreCase("all") || args[1].equalsIgnoreCase("*")) {
            if (!hasPermission("all")) {
                sendNoPermissionMessage();
                return;
            }

            runAllPlugins(sender, args);
            return;
        }

        var target = getPluginManager().getPluginByName(args, 1);
        if (!validatePlugin(label, target)) return;

        runPluginWithCommandBatch(sender, target);
    }

    protected abstract String allSuccessMessage();

    protected abstract String allFailedMessage();

    protected abstract String pluginSuccessMessage();

    protected boolean requiresAllConfirmation() {
        return false;
    }

    protected String allConfirmationMessage() {
        return null;
    }

    private void runAllPlugins(CommandSender sender, String[] args) {
        var plugins = getPluginManager().getPlugins().stream().filter(plugin ->
                plugin != null && !getPluginManager().isIgnored(plugin)).toList();

        if (requiresConfirmation(args, plugins.size())) {
            sender.sendMessage(allConfirmationMessage(), plugins.size(), CONFIRM_ARGUMENT);
            return;
        }

        var failedPlugins = new ArrayList<String>();

        getPluginManager().beginCommandUpdateBatch();
        try {
            for (var plugin : plugins) {
                var success = runPluginWithCommandBatch(sender, plugin);

                if (success) continue;
                failedPlugins.add(plugin.getName());
            }
        } finally {
            getPluginManager().endCommandUpdateBatch();
        }

        if (failedPlugins.isEmpty()) {
            sender.sendMessage(allSuccessMessage());
            return;
        }

        sender.sendMessage(allFailedMessage(), String.join(", ", failedPlugins));
    }

    private boolean requiresConfirmation(String[] args, int pluginCount) {
        return requiresAllConfirmation()
                && pluginCount > CONFIRM_ALL_PLUGIN_THRESHOLD
                && (args.length < 3 || !args[2].equalsIgnoreCase(CONFIRM_ARGUMENT));
    }

    private boolean runPluginWithCommandBatch(CommandSender sender, Plugin target) {
        getPluginManager().beginCommandUpdateBatch();
        try {
            return runPlugin(sender, target);
        } finally {
            getPluginManager().endCommandUpdateBatch();
        }
    }

    private boolean runPlugin(CommandSender sender, Plugin target) {
        if (target == null) {
            sendInvalidPluginMessage();
            return false;
        }

        var dependents = findEnabledDependents(target);
        var unloadedDependents = new ArrayList<Plugin>();

        for (var dependent : dependents) {
            var result = getPluginManager().unload(dependent);
            if (!result.success()) {
                sender.sendMessage(result.messageId(), dependent.getName());
                loadDependents(sender, unloadedDependents);
                return false;
            }

            unloadedDependents.add(dependent);
        }

        var result = getPluginManager().unload(target);
        if (!result.success()) {
            sender.sendMessage(result.messageId(), target.getName());
            loadDependents(sender, unloadedDependents);
            return false;
        }

        result = getPluginManager().load(target);
        if (!result.success()) {
            sender.sendMessage(result.messageId(), result.messageArgs().length == 0 ? new Object[]{target.getName()} : result.messageArgs());
            loadDependents(sender, unloadedDependents);
            return false;
        }

        if (!loadDependents(sender, unloadedDependents)) return false;

        sender.sendMessage(pluginSuccessMessage(), target.getName());
        return true;
    }

    private boolean loadDependents(CommandSender sender, List<Plugin> dependents) {
        for (var i = dependents.size() - 1; i >= 0; i--) {
            var dependent = dependents.get(i);

            var result = getPluginManager().load(dependent);
            if (result.success()) continue;

            sender.sendMessage(result.messageId(), result.messageArgs().length == 0 ? new Object[]{dependent.getName()} : result.messageArgs());
            return false;
        }

        return true;
    }

    private List<Plugin> findEnabledDependents(Plugin target) {
        var dependents = new ArrayList<Plugin>();
        if (!get(PlugManConfigurationManager.class).getPlugManConfig().shouldReloadHardDependents()) return dependents;

        var visited = new TreeSet<String>(String.CASE_INSENSITIVE_ORDER);
        visited.add(target.getName());
        collectEnabledDependents(target.getName(), dependents, visited);
        return dependents;
    }

    private void collectEnabledDependents(String targetName, List<Plugin> dependents, Set<String> visited) {
        for (var plugin : getPluginManager().getPlugins()) {
            if (plugin == null || !plugin.isEnabled()) continue;
            if (getPluginManager().isIgnored(plugin)) continue;
            if (visited.contains(plugin.getName())) continue;
            if (!dependsOn(plugin, targetName)) continue;

            visited.add(plugin.getName());
            collectEnabledDependents(plugin.getName(), dependents, visited);
            dependents.add(plugin);
        }
    }

    private boolean dependsOn(Plugin plugin, String targetName) {
        if (containsIgnoreCase(plugin.getDepend(), targetName)) return true;

        return get(PlugManConfigurationManager.class).getPlugManConfig().shouldReloadSoftDependents()
                && containsIgnoreCase(plugin.getSoftDepend(), targetName);
    }

    private boolean containsIgnoreCase(List<String> values, String expected) {
        return values != null && values.stream().anyMatch(value -> value.equalsIgnoreCase(expected));
    }
}
