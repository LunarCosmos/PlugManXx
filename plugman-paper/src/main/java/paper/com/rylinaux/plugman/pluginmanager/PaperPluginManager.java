package paper.com.rylinaux.plugman.pluginmanager;

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
import bukkit.com.rylinaux.plugman.plugin.BukkitPlugin;
import bukkit.com.rylinaux.plugman.pluginmanager.BasePluginManager;
import bukkit.com.rylinaux.plugman.pluginmanager.BukkitPluginManager;
import core.com.rylinaux.plugman.PluginResult;
import core.com.rylinaux.plugman.config.PlugManConfigurationManager;
import core.com.rylinaux.plugman.plugins.Plugin;
import core.com.rylinaux.plugman.util.reflection.ClassAccessor;
import core.com.rylinaux.plugman.util.reflection.FieldAccessor;
import core.com.rylinaux.plugman.util.reflection.MethodAccessor;
import core.com.rylinaux.plugman.util.tuples.Tuple;
import io.papermc.paper.plugin.configuration.PluginMeta;
import lombok.experimental.Delegate;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.event.EventException;
import org.bukkit.event.player.PlayerJoinEvent;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.jar.JarFile;
import java.util.logging.Level;

/**
 * Utilities for managing paper plugins.
 *
 * @author rylinaux
 */
public class PaperPluginManager extends BasePluginManager {
    @Delegate
    private final BukkitPluginManager _bukkitPluginManager;

    public PaperPluginManager(BukkitPluginManager bukkitPluginManager) {
        _bukkitPluginManager = bukkitPluginManager;

        try {
            var pluginClassLoader = ClassAccessor.getClass("org.bukkit.plugin.java.PluginClassLoader");
            if (pluginClassLoader == null) throw new ClassNotFoundException("PluginClassLoader not found");
            var pluginClassLoaderPlugin = FieldAccessor.getField(pluginClassLoader, "plugin");
            if (pluginClassLoaderPlugin == null) throw new NoSuchFieldException("plugin field not found");
        } catch (ClassNotFoundException | NoSuchFieldException exception) {
            throw new RuntimeException(exception);
        }
    }

    public boolean isPaperPlugin(File file) {
        if (file == null) return false;

        JarFile jar = null;

        try {
            jar = new JarFile(file);
            var entry = jar.getJarEntry("paper-plugin.yml");

            return entry != null;
        } catch (IOException | YAMLException ex) {
            return false;
        } finally {
            if (jar != null) try {
                jar.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public boolean isPaperPlugin(Plugin plugin) {
        try {
            var launchEntryPointHandlerClass = ClassAccessor.getClass("io.papermc.paper.plugin.entrypoint.LaunchEntryPointHandler");
            if (launchEntryPointHandlerClass == null) return false;

            var instance = FieldAccessor.getValue(launchEntryPointHandlerClass, "INSTANCE", null);

            var getMethod = MethodAccessor.findMethodByName(instance.getClass(), "get");

            if (getMethod == null) return false;

            var entrypointClass = ClassAccessor.getClass("io.papermc.paper.plugin.entrypoint.Entrypoint");
            if (entrypointClass == null) return false;

            var pluginFieldValue = FieldAccessor.getValue(entrypointClass, "PLUGIN", null);

            var providerStorage = getMethod.invoke(instance, pluginFieldValue);

            if (providerStorage == null) return false;

            var providers = MethodAccessor.<List<?>>invoke(ClassAccessor.getClass("io.papermc.paper.plugin.storage.SimpleProviderStorage"),
                    "getRegisteredProviders", providerStorage);

            for (var provider : providers)
                try {
                    var meta = MethodAccessor.<PluginMeta>invoke(provider.getClass(), "getMeta", provider);
                    if (!meta.getName().equalsIgnoreCase(plugin.getName())) continue;

                    return ClassAccessor.assignableFrom("io.papermc.paper.plugin.provider.type.paper.PaperPluginParent$PaperServerPluginProvider", provider.getClass());
                } catch (Throwable ignored) {
                    return false;
                }

        } catch (Throwable throwable) {
            PlugManBukkit.getInstance().getLogger().log(Level.SEVERE, "Failed to check if plugin is a Paper plugin", throwable);
        }

        return false;
    }

    public boolean isFolia() {
        return ClassAccessor.classExists("io.papermc.paper.threadedregions.RegionizedServer");
    }

    /**
     * Loads and enables a plugin.
     *
     * @param name plugin's name
     * @return status message
     */
    @Override
    public PluginResult load(String name) {
        var pluginFile = findPluginFile(name);
        if (pluginFile == null) return new PluginResult(false, "load.cannot-find");

        var validationResult = validatePluginFile(pluginFile);
        if (!validationResult.success()) return validationResult;

        PlugManBukkit.getInstance().getLogger().info("Attempting to load " + pluginFile.getPath());

        var target = loadPluginWithPaper(pluginFile);
        if (target == null) {
            if (isPaperPlugin(pluginFile)) return new PluginResult(false, "load.invalid-plugin");

            target = loadAndEnablePlugin(pluginFile, true);
            if (target == null) return new PluginResult(false, "load.invalid-plugin");
        }

        scheduleCommandLoading();
        PlugManBukkit.getInstance().getFilePluginMap().put(pluginFile.getName(), target.getName());

        return new PluginResult(true, "load.loaded");
    }

    @Override
    public PluginResult load(Plugin plugin) {
        if (plugin == null) return new PluginResult(false, "error.invalid-plugin");
        return load(plugin.getName());
    }


    private PluginResult validatePluginFile(File pluginFile) {
        var pluginDir = new File("plugins");
        if (!pluginDir.isDirectory()) return new PluginResult(false, "load.plugin-directory");

        if (!pluginFile.isFile()) return new PluginResult(false, "load.cannot-find");

        return new PluginResult(true, "validation.success");
    }

    private Plugin loadPluginWithPaper(File pluginFile) {
        try {
            if (isPaperPlugin(pluginFile)) return loadPaperPluginWithProviderStorage(pluginFile);

            var paper = ClassAccessor.getClass("io.papermc.paper.plugin.manager.PaperPluginManagerImpl");
            if (paper == null) return null;

            var paperPluginManagerImpl = MethodAccessor.invoke(paper, "getInstance", null);

            var instanceManager = FieldAccessor.getValue(paperPluginManagerImpl.getClass(), "instanceManager", paperPluginManagerImpl);

            var target = MethodAccessor.<org.bukkit.plugin.Plugin>invoke(instanceManager.getClass(), "loadPlugin", instanceManager, new Class<?>[]{Path.class}, pluginFile.toPath());

            MethodAccessor.invoke(instanceManager.getClass(), "enablePlugin", instanceManager, new Class<?>[]{org.bukkit.plugin.Plugin.class}, target);

            return new BukkitPlugin(target);
        } catch (Exception exception) {
            PlugManBukkit.getInstance().getLogger().log(Level.SEVERE, "Failed to load plugin with Paper: " + pluginFile.getName(), exception);
            return null;
        }
    }

    private Plugin loadPaperPluginWithProviderStorage(File pluginFile) {
        var debugStep = "start";
        try {
            debugPaperReload("start provider load for " + pluginFile.getPath());
            var pluginName = getPaperPluginName(pluginFile);
            debugStep = "read paper-plugin.yml";
            debugPaperReload("paper-plugin.yml name=" + pluginName);
            if (pluginName == null) return null;

            debugStep = "resolve paper classes";
            var providerSourceClass = ClassAccessor.getClass("io.papermc.paper.plugin.provider.source.FileProviderSource");
            var storageClass = ClassAccessor.getClass("io.papermc.paper.plugin.storage.ServerPluginProviderStorage");
            var handlerClass = ClassAccessor.getClass("io.papermc.paper.plugin.manager.RuntimePluginEntrypointHandler");
            var entrypointClass = ClassAccessor.getClass("io.papermc.paper.plugin.entrypoint.Entrypoint");
            debugPaperReload("classes providerSource=" + className(providerSourceClass)
                    + ", storage=" + className(storageClass)
                    + ", handler=" + className(handlerClass)
                    + ", entrypoint=" + className(entrypointClass));
            if (providerSourceClass == null || storageClass == null || handlerClass == null || entrypointClass == null) return null;

            debugStep = "construct provider source/storage/handler";
            Function<Path, String> contextFormatter = source -> "File '" + source + "'";
            var providerSource = newInstance(providerSourceClass, new Class<?>[]{Function.class}, contextFormatter);
            var storage = newInstance(storageClass);
            var handler = newInstance(handlerClass, new Class<?>[]{ClassAccessor.getClass("io.papermc.paper.plugin.storage.ProviderStorage")}, storage);
            var pluginEntrypoint = FieldAccessor.getValue(entrypointClass, "PLUGIN", null);
            debugPaperReload("constructed providerSource=" + providerSource.getClass().getName()
                    + ", storage=" + storage.getClass().getName()
                    + ", handler=" + handler.getClass().getName()
                    + ", entrypoint=" + pluginEntrypoint);

            debugStep = "prepare file context";
            var preparedPath = prepareProviderContext(providerSourceClass, providerSource, pluginFile);
            debugPaperReload("prepared path=" + preparedPath);

            debugStep = "register providers";
            var runtimeHandler = filterRuntimeEntrypoints(handler, pluginEntrypoint);
            registerProviders(providerSourceClass, providerSource, runtimeHandler, preparedPath);
            debugPaperReload("registered providers");

            debugStep = "enter plugin entrypoint";
            MethodAccessor.invoke(handlerClass, "enter", handler, new Class<?>[]{entrypointClass}, pluginEntrypoint);
            debugPaperReload("entered plugin entrypoint");

            debugStep = "lookup loaded plugin";
            var target = org.bukkit.Bukkit.getPluginManager().getPlugin(pluginName);
            debugPaperReload("lookup result=" + target);
            if (target == null) return null;

            debugStep = "enable plugin";
            org.bukkit.Bukkit.getPluginManager().enablePlugin(target);
            debugPaperReload("enabled plugin " + target.getName());
            debugStep = "replay join listeners";
            replayJoinListenersForOnlinePlayers(target);
            return new BukkitPlugin(target);
        } catch (InvocationTargetException exception) {
            var cause = exception.getTargetException() == null ? exception : exception.getTargetException();
            PlugManBukkit.getInstance().getLogger().log(Level.SEVERE,
                    "[PaperReloadDebug] crash at step '" + debugStep + "' while loading " + pluginFile.getName(), cause);
            return null;
        } catch (Exception exception) {
            PlugManBukkit.getInstance().getLogger().log(Level.SEVERE,
                    "[PaperReloadDebug] crash at step '" + debugStep + "' while loading " + pluginFile.getName(), exception);
            return null;
        }
    }

    private void debugPaperReload(String message) {
        if (!isPaperReloadDebugEnabled()) return;
        PlugManBukkit.getInstance().getLogger().info("[PaperReloadDebug] " + message);
    }

    private boolean isPaperReloadDebugEnabled() {
        try {
            var config = PlugManBukkit.getInstance().get(PlugManConfigurationManager.class);
            return config != null && config.getPlugManConfig() != null && config.getPlugManConfig().isPaperReloadDebug();
        } catch (Exception ignored) {
            return false;
        }
    }

    private Object filterRuntimeEntrypoints(Object handler, Object pluginEntrypoint) {
        try {
            var handlerType = ClassAccessor.getClass("io.papermc.paper.plugin.entrypoint.EntrypointHandler");
            if (handlerType == null || !handlerType.isInterface()) return handler;

            return java.lang.reflect.Proxy.newProxyInstance(
                    handlerType.getClassLoader(),
                    new Class<?>[]{handlerType},
                    (proxy, method, args) -> {
                        if (method.getName().equals("register") && args != null && args.length > 0 && args[0] != pluginEntrypoint) {
                            debugPaperReload("ignored runtime entrypoint " + args[0] + " because Paper only allows PLUGIN reloads");
                            return null;
                        }

                        try {
                            return method.invoke(handler, args);
                        } catch (InvocationTargetException exception) {
                            throw exception.getTargetException();
                        }
                    });
        } catch (Exception exception) {
            debugPaperReload("could not create runtime entrypoint filter: " + exception.getMessage());
            return handler;
        }
    }

    private void replayJoinListenersForOnlinePlayers(org.bukkit.plugin.Plugin plugin) {
        var listeners = PlayerJoinEvent.getHandlerList().getRegisteredListeners();
        var replayed = 0;

        for (var player : Bukkit.getOnlinePlayers()) {
            var event = new PlayerJoinEvent(player, Component.empty());

            for (var listener : listeners) {
                if (listener.getPlugin() != plugin) continue;

                try {
                    listener.callEvent(event);
                    replayed++;
                } catch (EventException exception) {
                    PlugManBukkit.getInstance().getLogger().log(Level.WARNING,
                            "[PaperReloadDebug] failed to replay PlayerJoinEvent for " + plugin.getName()
                                    + " and player " + player.getName(), exception);
                }
            }
        }

        debugPaperReload("replayed " + replayed + " PlayerJoinEvent listener calls for " + plugin.getName()
                + " across " + Bukkit.getOnlinePlayers().size() + " online players");
    }

    private String className(Class<?> clazz) {
        return clazz == null ? "null" : clazz.getName();
    }

    private Path prepareProviderContext(Class<?> providerSourceClass, Object providerSource, File pluginFile) throws Exception {
        var pathMethod = findSingleArgMethod(providerSourceClass, "prepareContext", Path.class);
        if (pathMethod != null) {
            debugPaperReload("prepareContext method=" + methodSignature(pathMethod) + ", arg=Path");
            return (Path) pathMethod.invoke(providerSource, pluginFile.toPath());
        }

        var objectMethod = findExactSingleArgMethod(providerSourceClass, "prepareContext", Object.class);
        if (objectMethod != null) {
            debugPaperReload("prepareContext method=" + methodSignature(objectMethod) + ", arg=Path/Object");
            return (Path) objectMethod.invoke(providerSource, pluginFile.toPath());
        }

        var stringMethod = findExactSingleArgMethod(providerSourceClass, "prepareContext", String.class);
        if (stringMethod != null) {
            debugPaperReload("prepareContext method=" + methodSignature(stringMethod) + ", arg=String");
            return (Path) stringMethod.invoke(providerSource, pluginFile.getPath());
        }

        debugPaperReload("prepareContext methods available=" + methodSignatures(providerSourceClass, "prepareContext"));
        throw new IllegalArgumentException("No compatible prepareContext method found in " + providerSourceClass.getName());
    }

    private void registerProviders(Class<?> providerSourceClass, Object providerSource, Object handler, Path preparedPath) throws Exception {
        var handlerType = ClassAccessor.getClass("io.papermc.paper.plugin.entrypoint.EntrypointHandler");
        Method bestMethod = null;

        for (var method : getAllMethods(providerSourceClass)) {
            if (!method.getName().equals("registerProviders")) continue;
            var parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != 2) continue;
            if (!parameterTypes[0].isAssignableFrom(handlerType)) continue;
            if (!parameterTypes[1].isAssignableFrom(preparedPath.getClass())) continue;
            bestMethod = method;
            break;
        }

        if (bestMethod == null) {
            debugPaperReload("registerProviders methods available=" + methodSignatures(providerSourceClass, "registerProviders"));
            throw new IllegalArgumentException("No compatible registerProviders method found in " + providerSourceClass.getName());
        }

        bestMethod.setAccessible(true);
        debugPaperReload("registerProviders method=" + methodSignature(bestMethod));
        bestMethod.invoke(providerSource, handler, preparedPath);
    }

    private Method findSingleArgMethod(Class<?> clazz, String methodName, Class<?> preferredArgumentType) {
        for (var method : getAllMethods(clazz)) {
            if (!method.getName().equals(methodName)) continue;
            var parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != 1) continue;
            if (!parameterTypes[0].isAssignableFrom(preferredArgumentType)) continue;
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    private Method findExactSingleArgMethod(Class<?> clazz, String methodName, Class<?> argumentType) {
        for (var method : getAllMethods(clazz)) {
            if (!method.getName().equals(methodName)) continue;
            var parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != 1) continue;
            if (parameterTypes[0] != argumentType) continue;
            method.setAccessible(true);
            return method;
        }
        return null;
    }

    private List<Method> getAllMethods(Class<?> clazz) {
        var methods = new java.util.ArrayList<Method>();
        var current = clazz;
        while (current != null) {
            methods.addAll(List.of(current.getDeclaredMethods()));
            current = current.getSuperclass();
        }
        return methods;
    }

    private String methodSignatures(Class<?> clazz, String methodName) {
        return getAllMethods(clazz).stream()
                .filter(method -> method.getName().equals(methodName))
                .map(this::methodSignature)
                .toList()
                .toString();
    }

    private String methodSignature(Method method) {
        return method.getDeclaringClass().getName() + "#" + method.getName() + "(" +
                java.util.Arrays.stream(method.getParameterTypes()).map(Class::getName).collect(java.util.stream.Collectors.joining(", ")) + ")";
    }

    private String getPaperPluginName(File file) {
        try (var jar = new JarFile(file)) {
            var entry = jar.getJarEntry("paper-plugin.yml");
            if (entry == null) return null;

            try (var input = jar.getInputStream(entry)) {
                var data = new Yaml().load(input);
                if (!(data instanceof Map<?, ?> map)) return null;

                var name = map.get("name");
                return name == null ? null : name.toString();
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object newInstance(Class<?> clazz, Class<?>[] parameterTypes, Object... args) throws Exception {
        Constructor<?> constructor = clazz.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    private Object newInstance(Class<?> clazz) throws Exception {
        Constructor<?> constructor = clazz.getDeclaredConstructor();
        constructor.setAccessible(true);
        return constructor.newInstance();
    }


    @Override
    protected synchronized void scheduleCommandLoading() {
        if (isFolia()) {
            var foliaLib = new com.tcoded.folialib.FoliaLib(PlugManBukkit.getInstance());
            foliaLib.getScheduler().runLater(this::syncCommands, 500, TimeUnit.MILLISECONDS);
        } else super.scheduleCommandLoading();
    }


    /**
     * Unload a plugin.
     *
     * @param plugin the plugin to unload
     * @return the message to send to the user.
     */
    @Override
    public PluginResult unload(Plugin plugin) {
        var out = unloadWithPaper(plugin);
        if (!out.second().success()) return out.second();

        closeClassLoader(plugin);
        cleanupPaperPluginManager(plugin);
        System.gc();

        return new PluginResult(true, "unload.unloaded");
    }

    public Tuple<CommonUnloadData, PluginResult> unloadWithPaper(Plugin plugin) {
        if (!handleGentleUnload(plugin)) return new Tuple<>(null, new PluginResult(false, "unload.gentle-failed"));

        var unloadData = extractPluginManagerData(plugin);
        if (unloadData == null) return new Tuple<>(null, new PluginResult(false, "unload.failed"));

        cleanupListeners(plugin, unloadData);
        cleanupCommands(plugin, unloadData);
        removeFromPluginLists(plugin, unloadData);

        return new Tuple<>(unloadData, new PluginResult(true, "unload.common-success"));
    }

    private void cleanupListeners(Plugin plugin, CommonUnloadData data) {
        if (data.listeners() == null || !data.reloadListeners()) return;
        data.listeners().values().forEach(set -> set.removeIf(value -> value.getPlugin() == plugin.getHandle()));
    }

    private void cleanupCommands(Plugin plugin, CommonUnloadData data) {
        if (data.commandMap() == null) return;

        var modifiedKnownCommands = data.commands();
        var pluginCommands = getCommandsFromPlugin(plugin);

        pluginCommands.forEach(entry -> {
            var command = entry.getValue().<Command>getHandle();

            command.unregister(data.commandMap());
            modifiedKnownCommands.remove(entry.getKey());
        });

        syncCommands();
    }

    private void cleanupPaperPluginManager(Plugin plugin) {
        try {
            var paper = ClassAccessor.getClass("io.papermc.paper.plugin.manager.PaperPluginManagerImpl");
            if (paper == null) return;

            var paperPluginManagerImpl = MethodAccessor.invoke(paper, "getInstance", null);

            var instanceManager = FieldAccessor.getValue(paperPluginManagerImpl.getClass(), "instanceManager", paperPluginManagerImpl);

            var lookupNames = FieldAccessor.<Map<String, org.bukkit.plugin.Plugin>>getValue(instanceManager.getClass(), "lookupNames", instanceManager);

            MethodAccessor.invoke(instanceManager.getClass(), "disablePlugin", instanceManager, new Class<?>[]{org.bukkit.plugin.Plugin.class}, plugin.<org.bukkit.plugin.Plugin>getHandle());

            lookupNames.remove(plugin.getName().toLowerCase());

            var pluginList = FieldAccessor.<List<org.bukkit.plugin.Plugin>>getValue(instanceManager.getClass(), "plugins", instanceManager);
            pluginList.remove(plugin.<org.bukkit.plugin.Plugin>getHandle());
        } catch (Exception ignore) {
            // Paper most likely not loaded
        }
    }
}
