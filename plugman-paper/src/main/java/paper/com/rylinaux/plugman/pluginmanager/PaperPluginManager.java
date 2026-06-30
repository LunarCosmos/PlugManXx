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
import paper.com.rylinaux.plugman.util.PaperReflectionNames;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final String PREPARE_CONTEXT_METHOD = "prepareContext";
    private static final String PREPARE_CONTEXT_LOG_PREFIX = "prepareContext method=";
    private static final String REGISTER_PROVIDERS_METHOD = "registerProviders";
    private static final String REGISTER_METHOD = "register";
    private static final String ENTER_METHOD = "enter";
    private static final String CURRENT_CONTEXT_FIELD = "currentContext";
    private static final String SET_CURRENT_CONTEXT_METHOD = "setCurrentContext";
    private static final String COMMANDS_LOG_PREFIX = "commands ";
    private static final String LOAD_INVALID_PLUGIN_MESSAGE = "load.invalid-plugin";

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
            var launchEntryPointHandlerClass = ClassAccessor.getClass(PaperReflectionNames.LAUNCH_ENTRYPOINT_HANDLER);
            if (launchEntryPointHandlerClass == null) return false;

            var instance = FieldAccessor.getValue(launchEntryPointHandlerClass, PaperReflectionNames.INSTANCE_FIELD, null);

            var getMethod = MethodAccessor.findMethodByName(instance.getClass(), "get");

            if (getMethod == null) return false;

            var entrypointClass = ClassAccessor.getClass(PaperReflectionNames.ENTRYPOINT);
            if (entrypointClass == null) return false;

            var pluginFieldValue = FieldAccessor.getValue(entrypointClass, PaperReflectionNames.PLUGIN_FIELD, null);

            var providerStorage = getMethod.invoke(instance, pluginFieldValue);

            if (providerStorage == null) return false;

            var providers = MethodAccessor.<List<?>>invoke(ClassAccessor.getClass(PaperReflectionNames.SIMPLE_PROVIDER_STORAGE),
                    "getRegisteredProviders", providerStorage);

            for (var provider : providers)
                try {
                    var meta = MethodAccessor.<PluginMeta>invoke(provider.getClass(), "getMeta", provider);
                    if (!meta.getName().equalsIgnoreCase(plugin.getName())) continue;

                    return ClassAccessor.assignableFrom(PaperReflectionNames.PAPER_SERVER_PLUGIN_PROVIDER, provider.getClass());
                } catch (Throwable ignored) {
                    return false;
                }

        } catch (Throwable throwable) {
            PlugManBukkit.getInstance().getLogger().log(Level.SEVERE, "Failed to check if plugin is a Paper plugin", throwable);
        }

        return false;
    }

    public boolean isFolia() {
        return ClassAccessor.classExists(PaperReflectionNames.REGIONIZED_SERVER);
    }

    @Override
    public PluginResult enable(Plugin plugin) {
        if (plugin == null) return new PluginResult(false, "error.invalid-plugin");
        if (plugin.isEnabled()) return new PluginResult(false, "enable.already-enabled");

        var pluginName = plugin.getName();
        var unloadResult = unload(plugin);
        if (!unloadResult.success()) return unloadResult;

        var loadResult = load(pluginName);
        if (!loadResult.success()) return loadResult;

        return new PluginResult(true, "enable.enabled", pluginName);
    }

    /**
     * Loads and enables a plugin.
     *
     * @param name plugin's name
     * @return status message
     */
    @Override
    public PluginResult load(String name) {
        var preflight = preflightPluginLoad(name);
        if (!preflight.result().success()) return preflight.result();
        if (!beginPluginLoad(preflight.descriptor())) {
            return new PluginResult(false, "load.missing-dependencies", preflight.descriptor().name(), preflight.descriptor().name());
        }

        try {
            var dependencyResult = loadRequiredDependencies(preflight.descriptor());
            if (!dependencyResult.success()) return dependencyResult;

            var pluginFile = preflight.pluginFile();
            PlugManBukkit.getInstance().getLogger().info("Attempting to load " + pluginFile.getPath());

            var target = loadPluginWithPaper(pluginFile);
            if (target == null) {
                if (getPluginByName(preflight.descriptor().name()) != null) return new PluginResult(false, LOAD_INVALID_PLUGIN_MESSAGE, preflight.descriptor().name());
                if (preflight.descriptor().paperPlugin()) return new PluginResult(false, LOAD_INVALID_PLUGIN_MESSAGE, preflight.descriptor().name());
                target = loadAndEnablePlugin(pluginFile, true);
                if (target == null) return new PluginResult(false, LOAD_INVALID_PLUGIN_MESSAGE, preflight.descriptor().name());
            }

            scheduleCommandLoading();
            PlugManBukkit.getInstance().getFilePluginMap().put(pluginFile.getName(), target.getName());

            return new PluginResult(true, "load.loaded");
        } finally {
            finishPluginLoad(preflight.descriptor());
        }
    }

    @Override
    public PluginResult load(Plugin plugin) {
        if (plugin == null) return new PluginResult(false, "error.invalid-plugin");
        return load(plugin.getName());
    }

    private Plugin loadPluginWithPaper(File pluginFile) {
        try {
            if (isPaperPlugin(pluginFile)) return loadPaperPluginWithProviderStorage(pluginFile);

            var paper = ClassAccessor.getClass(PaperReflectionNames.PAPER_PLUGIN_MANAGER);
            if (paper == null) return null;

            var paperPluginManagerImpl = MethodAccessor.invoke(paper, "getInstance", null);

            var instanceManager = FieldAccessor.getValue(paperPluginManagerImpl.getClass(), "instanceManager", paperPluginManagerImpl);

            var target = MethodAccessor.<org.bukkit.plugin.Plugin>invoke(instanceManager.getClass(), "loadPlugin", instanceManager, new Class<?>[]{Path.class}, pluginFile.toPath());

            enablePluginWithPaperCommandContext(target, () ->
                    invokePaperInstanceManagerEnable(instanceManager, target));
            if (!target.isEnabled()) {
                PlugManBukkit.getInstance().getLogger().severe("Plugin failed to enable after Paper load: " + pluginFile.getName());
                return null;
            }

            return new BukkitPlugin(target);
        } catch (Exception exception) {
            logPaperLoadFailure("Failed to load plugin with Paper: " + pluginFile.getName(), exception);
            return null;
        }
    }

    private Plugin loadPaperPluginWithProviderStorage(File pluginFile) {
        var debugStep = "start";
        try {
            debugPaperReload("start provider load for " + pluginFile.getPath());
            var pluginName = readPaperPluginName(pluginFile);
            debugStep = "read paper-plugin.yml";
            debugPaperReload("paper-plugin.yml name=" + pluginName);
            if (pluginName == null) return null;

            debugStep = "resolve paper classes";
            var providerSourceClass = ClassAccessor.getClass(PaperReflectionNames.FILE_PROVIDER_SOURCE);
            var storageClass = ClassAccessor.getClass(PaperReflectionNames.SERVER_PLUGIN_PROVIDER_STORAGE);
            var bootstrapStorageClass = ClassAccessor.getClass(PaperReflectionNames.BOOTSTRAP_PROVIDER_STORAGE);
            var entrypointClass = ClassAccessor.getClass(PaperReflectionNames.ENTRYPOINT);
            debugPaperReload("classes providerSource=" + className(providerSourceClass)
                    + ", storage=" + className(storageClass)
                    + ", bootstrapStorage=" + className(bootstrapStorageClass)
                    + ", entrypoint=" + className(entrypointClass));
            if (providerSourceClass == null || storageClass == null || bootstrapStorageClass == null || entrypointClass == null) return null;

            debugStep = "construct provider source/storage/handler";
            Function<Path, String> contextFormatter = source -> "File '" + source + "'";
            var providerSource = newInstance(providerSourceClass, new Class<?>[]{Function.class}, contextFormatter);
            var storage = newInstance(storageClass);
            var bootstrapStorage = newInstance(bootstrapStorageClass);
            var pluginEntrypoint = FieldAccessor.getValue(entrypointClass, PaperReflectionNames.PLUGIN_FIELD, null);
            var bootstrapEntrypoint = FieldAccessor.getValue(entrypointClass, PaperReflectionNames.BOOTSTRAPPER_FIELD, null);
            var handler = createRuntimePaperEntrypointHandler(pluginEntrypoint, storage, bootstrapEntrypoint, bootstrapStorage);
            if (handler == null) return null;
            debugPaperReload("constructed providerSource=" + providerSource.getClass().getName()
                    + ", storage=" + storage.getClass().getName()
                    + ", bootstrapStorage=" + bootstrapStorage.getClass().getName()
                    + ", handler=" + handler.getClass().getName()
                    + ", pluginEntrypoint=" + pluginEntrypoint
                    + ", bootstrapEntrypoint=" + bootstrapEntrypoint);

            debugStep = "prepare file context";
            var preparedPath = prepareProviderContext(providerSourceClass, providerSource, pluginFile);
            debugPaperReload("prepared path=" + preparedPath);

            debugStep = "register providers";
            registerProviders(providerSourceClass, providerSource, handler, preparedPath);
            debugPaperReload("registered providers");

            debugStep = "enter bootstrap entrypoint";
            enterPaperRuntimeEntrypoint(handler, entrypointClass, bootstrapEntrypoint);
            debugPaperReload("entered bootstrap entrypoint");

            debugStep = "enter plugin entrypoint";
            enterPaperRuntimeEntrypoint(handler, entrypointClass, pluginEntrypoint);
            debugPaperReload("entered plugin entrypoint");

            debugStep = "lookup loaded plugin";
            var target = org.bukkit.Bukkit.getPluginManager().getPlugin(pluginName);
            debugPaperReload("lookup result=" + target);
            if (target == null) return null;

            debugStep = "enable plugin";
            enablePluginWithPaperCommandContext(target, () -> org.bukkit.Bukkit.getPluginManager().enablePlugin(target));
            if (!target.isEnabled()) {
                PlugManBukkit.getInstance().getLogger().severe("Plugin failed to enable after Paper provider load: " + pluginFile.getName());
                return null;
            }
            debugPaperReload("enabled plugin " + target.getName());
            debugKnownPaperCommands(target, "after enable before sync");
            debugStep = "fire paper command lifecycle";
            firePaperCommandsLifecycle(target);
            debugKnownPaperCommands(target, "after command lifecycle");
            debugStep = "sync commands";
            syncCommands();
            debugKnownPaperCommands(target, "after sync");
            debugStep = "replay join listeners";
            replayJoinListenersForOnlinePlayers(target);
            return new BukkitPlugin(target);
        } catch (InvocationTargetException exception) {
            var cause = exception.getTargetException() == null ? exception : exception.getTargetException();
            logPaperLoadFailure("Paper lifecycle rejected " + pluginFile.getName() + " at step '" + debugStep + "'", cause);
            return null;
        } catch (Exception exception) {
            logPaperLoadFailure("Paper lifecycle rejected " + pluginFile.getName() + " at step '" + debugStep + "'", exception);
            return null;
        }
    }

    private void logPaperLoadFailure(String message, Throwable throwable) {
        if (isPaperReloadDebugEnabled()) {
            PlugManBukkit.getInstance().getLogger().log(Level.SEVERE, "[PaperReloadDebug] " + message, throwable);
            return;
        }

        PlugManBukkit.getInstance().getLogger().warning(message + ". Enable paperReloadDebug for the full stacktrace.");
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

    private Object createRuntimePaperEntrypointHandler(Object pluginEntrypoint,
                                                      Object pluginStorage,
                                                      Object bootstrapEntrypoint,
                                                      Object bootstrapStorage) {
        try {
            var handlerType = ClassAccessor.getClass(PaperReflectionNames.ENTRYPOINT_HANDLER);
            if (handlerType == null || !handlerType.isInterface()) return null;

            return java.lang.reflect.Proxy.newProxyInstance(
                    handlerType.getClassLoader(),
                    new Class<?>[]{handlerType},
                    createRuntimePaperEntrypointInvocationHandler(pluginEntrypoint, pluginStorage, bootstrapEntrypoint, bootstrapStorage));
        } catch (Exception exception) {
            debugPaperReload("could not create runtime entrypoint handler: " + exception.getMessage());
            return null;
        }
    }

    private InvocationHandler createRuntimePaperEntrypointInvocationHandler(Object pluginEntrypoint,
                                                                           Object pluginStorage,
                                                                           Object bootstrapEntrypoint,
                                                                           Object bootstrapStorage) {
        return (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return handleRuntimeEntrypointObjectMethod(proxy, method, args);

            if (method.getName().equals(REGISTER_METHOD) && args != null && args.length == 2) {
                return handleRuntimeEntrypointRegister(args, pluginEntrypoint, pluginStorage, bootstrapEntrypoint, bootstrapStorage);
            }

            if (method.getName().equals(ENTER_METHOD) && args != null && args.length == 1) {
                return handleRuntimeEntrypointEnter(args, pluginEntrypoint, pluginStorage, bootstrapEntrypoint, bootstrapStorage);
            }

            throw new UnsupportedOperationException("Unsupported EntrypointHandler method " + method.getName());
        };
    }

    private Object handleRuntimeEntrypointObjectMethod(Object proxy, Method method, Object[] args) {
        return switch (method.getName()) {
            case "toString" -> "PlugManRuntimePaperEntrypointHandler";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> args != null && args.length == 1 && proxy == args[0];
            default -> null;
        };
    }

    private Object handleRuntimeEntrypointRegister(Object[] args,
                                                  Object pluginEntrypoint,
                                                  Object pluginStorage,
                                                  Object bootstrapEntrypoint,
                                                  Object bootstrapStorage) throws ReflectiveOperationException {
        var storage = resolveRuntimeEntrypointStorage(args[0], pluginEntrypoint, pluginStorage, bootstrapEntrypoint, bootstrapStorage);
        if (storage == null) {
            debugPaperReload("ignored unsupported runtime entrypoint " + args[0]);
            return null;
        }

        invokeStorageRegister(storage, args[1]);
        return null;
    }

    private Object handleRuntimeEntrypointEnter(Object[] args,
                                               Object pluginEntrypoint,
                                               Object pluginStorage,
                                               Object bootstrapEntrypoint,
                                               Object bootstrapStorage) throws ReflectiveOperationException {
        var storage = resolveRuntimeEntrypointStorage(args[0], pluginEntrypoint, pluginStorage, bootstrapEntrypoint, bootstrapStorage);
        if (storage == null) throw new IllegalArgumentException("Unsupported runtime entrypoint " + args[0]);

        invokeStorageEnter(storage);
        return null;
    }

    private Object resolveRuntimeEntrypointStorage(Object entrypoint,
                                                  Object pluginEntrypoint,
                                                  Object pluginStorage,
                                                  Object bootstrapEntrypoint,
                                                  Object bootstrapStorage) {
        if (entrypoint == bootstrapEntrypoint) return bootstrapStorage;
        if (entrypoint == pluginEntrypoint) return pluginStorage;
        return null;
    }

    private void enterPaperRuntimeEntrypoint(Object handler, Class<?> entrypointClass, Object entrypoint) throws Exception {
        MethodAccessor.invoke(handler.getClass(), ENTER_METHOD, handler, new Class<?>[]{entrypointClass}, entrypoint);
    }

    private void invokeStorageRegister(Object storage, Object provider) throws ReflectiveOperationException {
        var providerClass = ClassAccessor.getClass(PaperReflectionNames.PLUGIN_PROVIDER);
        var method = getAllMethods(storage.getClass()).stream()
                .filter(candidate -> candidate.getName().equals(REGISTER_METHOD))
                .filter(candidate -> candidate.getParameterTypes().length == 1)
                .filter(candidate -> providerClass == null || candidate.getParameterTypes()[0].isAssignableFrom(providerClass))
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(REGISTER_METHOD + " method not found in " + storage.getClass().getName()));

        method.setAccessible(true);
        method.invoke(storage, provider);
    }

    private void invokeStorageEnter(Object storage) throws ReflectiveOperationException {
        var method = getAllMethods(storage.getClass()).stream()
                .filter(candidate -> candidate.getName().equals(ENTER_METHOD))
                .filter(candidate -> candidate.getParameterTypes().length == 0)
                .findFirst()
                .orElseThrow(() -> new NoSuchMethodException(ENTER_METHOD + " method not found in " + storage.getClass().getName()));

        method.setAccessible(true);
        method.invoke(storage);
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

    private void debugKnownPaperCommands(org.bukkit.plugin.Plugin plugin, String phase) {
        if (!isPaperReloadDebugEnabled()) return;

        try {
            var knownCommands = getKnownCommands();
            if (knownCommands == null) {
                debugPaperReload(COMMANDS_LOG_PREFIX + phase + ": knownCommands unavailable");
                return;
            }

            var pluginName = plugin.getName().toLowerCase();
            var pluginLoader = plugin.getClass().getClassLoader();
            var matches = knownCommands.asMap().entrySet().stream()
                    .filter(entry -> commandMayBelongToPlugin(entry.getKey(), entry.getValue().getHandle(), pluginName, pluginLoader))
                    .map(entry -> entry.getKey() + "=" + entry.getValue().getHandle().getClass().getName())
                    .toList();

            debugPaperReload(COMMANDS_LOG_PREFIX + phase + " for " + plugin.getName() + ": " + matches);
        } catch (Exception exception) {
            debugPaperReload(COMMANDS_LOG_PREFIX + phase + " debug failed: " + exception.getClass().getName() + ": " + exception.getMessage());
        }
    }

    private boolean commandMayBelongToPlugin(String key, Object command, String pluginName, ClassLoader pluginLoader) {
        var lowerKey = key.toLowerCase();
        return lowerKey.contains(pluginName)
                || (command != null && command.getClass().getClassLoader() == pluginLoader);
    }

    private void firePaperCommandsLifecycle(org.bukkit.plugin.Plugin plugin) {
        try {
            var lifecycleEventsClass = ClassAccessor.getClass(PaperReflectionNames.LIFECYCLE_EVENTS);
            var lifecycleRunnerClass = ClassAccessor.getClass(PaperReflectionNames.LIFECYCLE_EVENT_RUNNER);
            var paperCommandsClass = ClassAccessor.getClass(PaperReflectionNames.PAPER_COMMANDS);
            var reloadableEventClass = ClassAccessor.getClass(PaperReflectionNames.RELOADABLE_REGISTRAR_EVENT);
            var paperRegistrarClass = ClassAccessor.getClass(PaperReflectionNames.PAPER_REGISTRAR);
            var lifecycleOwnerClass = ClassAccessor.getClass(PaperReflectionNames.LIFECYCLE_EVENT_OWNER);
            var causeClass = ClassAccessor.getClass(PaperReflectionNames.RELOADABLE_REGISTRAR_EVENT_CAUSE);

            if (lifecycleEventsClass == null || lifecycleRunnerClass == null || paperCommandsClass == null
                    || reloadableEventClass == null || paperRegistrarClass == null || lifecycleOwnerClass == null || causeClass == null) {
                debugPaperReload("paper command lifecycle unavailable");
                return;
            }

            var commandsEventType = FieldAccessor.getValue(lifecycleEventsClass, PaperReflectionNames.COMMANDS_FIELD, null);
            var runner = FieldAccessor.getValue(lifecycleRunnerClass, PaperReflectionNames.INSTANCE_FIELD, null);
            var paperCommands = FieldAccessor.getValue(paperCommandsClass, PaperReflectionNames.INSTANCE_FIELD, null);
            var reloadCause = FieldAccessor.getValue(causeClass, PaperReflectionNames.RELOAD_FIELD, null);
            preparePaperCommandsRegistrar(paperCommandsClass, paperCommands);

            var constructor = reloadableEventClass.getDeclaredConstructor(paperRegistrarClass, Class.class, causeClass);
            constructor.setAccessible(true);
            var event = constructor.newInstance(paperCommands, lifecycleOwnerClass, reloadCause);

            java.util.function.Predicate<Object> targetPluginOnly = owner -> owner == plugin;
            var callEvent = getAllMethods(lifecycleRunnerClass).stream()
                    .filter(method -> method.getName().equals("callEvent"))
                    .filter(method -> method.getParameterTypes().length == 3)
                    .filter(method -> method.getParameterTypes()[2].isAssignableFrom(java.util.function.Predicate.class))
                    .findFirst()
                    .orElseThrow(() -> new NoSuchMethodException("LifecycleEventRunner#callEvent with Predicate not found"));

            callEvent.setAccessible(true);
            callEvent.invoke(runner, commandsEventType, event, targetPluginOnly);
            debugPaperReload("fired Paper COMMANDS lifecycle for " + plugin.getName());
        } catch (InvocationTargetException exception) {
            var cause = exception.getTargetException() == null ? exception : exception.getTargetException();
            PlugManBukkit.getInstance().getLogger().log(Level.WARNING,
                    "[PaperReloadDebug] failed to fire Paper COMMANDS lifecycle for " + plugin.getName(), cause);
        } catch (Exception exception) {
            PlugManBukkit.getInstance().getLogger().log(Level.WARNING,
                    "[PaperReloadDebug] failed to fire Paper COMMANDS lifecycle for " + plugin.getName(), exception);
        }
    }

    private void preparePaperCommandsRegistrar(Class<?> paperCommandsClass, Object paperCommands) throws Exception {
        MethodAccessor.invoke(paperCommandsClass, "setValid", paperCommands);

        try {
            MethodAccessor.invoke(paperCommandsClass, "getDispatcher", paperCommands);
            debugPaperReload("PaperCommands dispatcher is available");
        } catch (InvocationTargetException exception) {
            var cause = exception.getTargetException() == null ? exception : exception.getTargetException();
            debugPaperReload("PaperCommands dispatcher unavailable after setValid: " + cause.getMessage());
        }
    }

    private void enablePluginWithPaperCommandContext(org.bukkit.plugin.Plugin plugin, ThrowingRunnable enableAction) throws ReflectiveOperationException {
        var paperCommandsClass = ClassAccessor.getClass(PaperReflectionNames.PAPER_COMMANDS);
        var lifecycleOwnerClass = ClassAccessor.getClass(PaperReflectionNames.LIFECYCLE_EVENT_OWNER);
        if (paperCommandsClass == null || lifecycleOwnerClass == null || !lifecycleOwnerClass.isInstance(plugin)) {
            enableAction.run();
            return;
        }

        var paperCommands = FieldAccessor.getValue(paperCommandsClass, PaperReflectionNames.INSTANCE_FIELD, null);
        if (paperCommands == null) {
            enableAction.run();
            return;
        }

        Object previousContext = null;
        var restoreContext = false;
        try {
            previousContext = FieldAccessor.getValue(paperCommandsClass, CURRENT_CONTEXT_FIELD, paperCommands);
            setPaperCommandContext(paperCommandsClass, paperCommands, lifecycleOwnerClass, plugin);
            restoreContext = true;
            debugPaperReload("set PaperCommands lifecycle owner context for " + plugin.getName());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            debugPaperReload("PaperCommands lifecycle owner context unavailable: "
                    + exception.getClass().getName() + ": " + exception.getMessage());
        }

        try {
            enableAction.run();
        } finally {
            if (restoreContext) restorePaperCommandContext(paperCommandsClass, paperCommands, lifecycleOwnerClass, previousContext);
        }
    }

    private void restorePaperCommandContext(Class<?> paperCommandsClass,
                                            Object paperCommands,
                                            Class<?> lifecycleOwnerClass,
                                            Object previousContext) {
        try {
            setPaperCommandContext(paperCommandsClass, paperCommands, lifecycleOwnerClass, previousContext);
        } catch (PaperCommandContextException exception) {
            debugPaperReload("failed to restore PaperCommands lifecycle owner context: "
                    + exception.getClass().getName() + ": " + exception.getMessage());
        }
    }

    private void setPaperCommandContext(Class<?> paperCommandsClass,
                                        Object paperCommands,
                                        Class<?> lifecycleOwnerClass,
                                        Object context) throws PaperCommandContextException {
        try {
            MethodAccessor.invoke(paperCommandsClass, SET_CURRENT_CONTEXT_METHOD, paperCommands, new Class<?>[]{lifecycleOwnerClass}, context);
        } catch (Exception exception) {
            throw new PaperCommandContextException("Failed to set PaperCommands lifecycle owner context", exception);
        }
    }

    private void invokePaperInstanceManagerEnable(Object instanceManager,
                                                  org.bukkit.plugin.Plugin target) throws PaperCommandContextException {
        try {
            MethodAccessor.invoke(instanceManager.getClass(), "enablePlugin", instanceManager, new Class<?>[]{org.bukkit.plugin.Plugin.class}, target);
        } catch (Exception exception) {
            throw new PaperCommandContextException("Failed to invoke Paper plugin enable", exception);
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws ReflectiveOperationException;
    }

    private static class PaperCommandContextException extends ReflectiveOperationException {
        PaperCommandContextException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private String className(Class<?> clazz) {
        return clazz == null ? "null" : clazz.getName();
    }

    private Path prepareProviderContext(Class<?> providerSourceClass, Object providerSource, File pluginFile) throws ReflectiveOperationException {
        var pathMethod = findSingleArgMethod(providerSourceClass, PREPARE_CONTEXT_METHOD, Path.class);
        if (pathMethod != null) {
            debugPaperReload(PREPARE_CONTEXT_LOG_PREFIX + methodSignature(pathMethod) + ", arg=Path");
            return (Path) pathMethod.invoke(providerSource, pluginFile.toPath());
        }

        var objectMethod = findExactSingleArgMethod(providerSourceClass, PREPARE_CONTEXT_METHOD, Object.class);
        if (objectMethod != null) {
            debugPaperReload(PREPARE_CONTEXT_LOG_PREFIX + methodSignature(objectMethod) + ", arg=Path/Object");
            return (Path) objectMethod.invoke(providerSource, pluginFile.toPath());
        }

        var stringMethod = findExactSingleArgMethod(providerSourceClass, PREPARE_CONTEXT_METHOD, String.class);
        if (stringMethod != null) {
            debugPaperReload(PREPARE_CONTEXT_LOG_PREFIX + methodSignature(stringMethod) + ", arg=String");
            return (Path) stringMethod.invoke(providerSource, pluginFile.getPath());
        }

        debugPaperReload("prepareContext methods available=" + methodSignatures(providerSourceClass, PREPARE_CONTEXT_METHOD));
        throw new IllegalArgumentException("No compatible prepareContext method found in " + providerSourceClass.getName());
    }

    private void registerProviders(Class<?> providerSourceClass, Object providerSource, Object handler, Path preparedPath) throws ReflectiveOperationException {
        var handlerType = ClassAccessor.getClass(PaperReflectionNames.ENTRYPOINT_HANDLER);
        Method bestMethod = null;

        for (var method : getAllMethods(providerSourceClass)) {
            if (!method.getName().equals(REGISTER_PROVIDERS_METHOD)) continue;
            var parameterTypes = method.getParameterTypes();
            if (parameterTypes.length != 2) continue;
            if (!parameterTypes[0].isAssignableFrom(handlerType)) continue;
            if (!parameterTypes[1].isAssignableFrom(preparedPath.getClass())) continue;
            bestMethod = method;
            break;
        }

        if (bestMethod == null) {
            debugPaperReload("registerProviders methods available=" + methodSignatures(providerSourceClass, REGISTER_PROVIDERS_METHOD));
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

    private String readPaperPluginName(File file) {
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

    private Object newInstance(Class<?> clazz, Class<?>[] parameterTypes, Object... args) throws ReflectiveOperationException {
        Constructor<?> constructor = clazz.getDeclaredConstructor(parameterTypes);
        constructor.setAccessible(true);
        return constructor.newInstance(args);
    }

    private Object newInstance(Class<?> clazz) throws ReflectiveOperationException {
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
        var pluginCommands = getPaperCommandsFromPlugin(plugin, modifiedKnownCommands);

        pluginCommands.forEach(entry -> {
            var command = entry.getValue().<Command>getHandle();

            command.unregister(data.commandMap());
            modifiedKnownCommands.remove(entry.getKey());
        });

        syncCommands();
    }

    private List<Map.Entry<String, core.com.rylinaux.plugman.plugins.Command>> getPaperCommandsFromPlugin(
            Plugin plugin,
            core.com.rylinaux.plugman.plugins.CommandMapWrap<Command> commands) {
        var pluginHandles = collectPaperCommandHandles(plugin, commands);
        var pluginRootCommands = collectPaperRootCommandNames(plugin, commands);

        return commands.asMap().entrySet().stream()
                .filter(entry -> pluginHandles.contains(entry.getValue().getHandle()) || pluginRootCommands.contains(entry.getKey().toLowerCase()))
                .toList();
    }

    private Set<Command> collectPaperCommandHandles(Plugin plugin, core.com.rylinaux.plugman.plugins.CommandMapWrap<Command> commands) {
        var pluginHandles = new HashSet<Command>();
        var pluginPrefix = plugin.getName().toLowerCase() + ":";

        for (var entry : commands.asMap().entrySet()) {
            var command = entry.getValue().<Command>getHandle();
            if (entry.getKey().toLowerCase().startsWith(pluginPrefix) || commandDirectlyBelongsToPlugin(command, plugin)) {
                pluginHandles.add(command);
            }
        }

        return pluginHandles;
    }

    private Set<String> collectPaperRootCommandNames(Plugin plugin, core.com.rylinaux.plugman.plugins.CommandMapWrap<Command> commands) {
        var rootCommands = new HashSet<String>();
        var pluginPrefix = plugin.getName().toLowerCase() + ":";

        for (var key : commands.asMap().keySet()) {
            var lowerKey = key.toLowerCase();
            if (!lowerKey.startsWith(pluginPrefix)) continue;

            var commandName = lowerKey.substring(pluginPrefix.length());
            if (!commandName.isBlank()) rootCommands.add(commandName);
        }

        return rootCommands;
    }

    private boolean commandDirectlyBelongsToPlugin(Command command, Plugin plugin) {
        var pluginHandle = plugin.<org.bukkit.plugin.Plugin>getHandle();
        var pluginLoader = pluginHandle.getClass().getClassLoader();

        if (command.getClass().getClassLoader() == pluginLoader) return true;

        var pluginField = FieldAccessor.getFirstFieldName(command.getClass(), org.bukkit.plugin.Plugin.class);
        if (pluginField == null) return false;

        try {
            return FieldAccessor.<org.bukkit.plugin.Plugin>getValue(command.getClass(), pluginField, command) == pluginHandle;
        } catch (LinkageError | RuntimeException | IllegalAccessException ignored) {
            return false;
        }
    }

    private void cleanupPaperPluginManager(Plugin plugin) {
        try {
            var paper = ClassAccessor.getClass(PaperReflectionNames.PAPER_PLUGIN_MANAGER);
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
