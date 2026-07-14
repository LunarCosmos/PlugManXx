package core.com.rylinaux.plugman.commands.executables;

import core.com.rylinaux.plugman.plugins.Plugin;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CascadingPluginCommandTest {

    @Test
    void createsDependencyFirstLoadOrderWithoutDuplicates() {
        var dependency = new TestPlugin("Dependency", List.of(), List.of());
        var softDependency = new TestPlugin("SoftDependency", List.of(), List.of());
        var plugin = new TestPlugin("Plugin", List.of("Dependency"), List.of("SoftDependency"));

        var order = CascadingPluginCommand.createLoadOrder(
                List.of(plugin, dependency, softDependency, plugin), true);

        assertEquals(List.of("Dependency", "SoftDependency", "Plugin"),
                order.stream().map(Plugin::getName).toList());
    }

    @Test
    void ignoresSoftDependenciesWhenDisabled() {
        var softDependency = new TestPlugin("SoftDependency", List.of(), List.of());
        var plugin = new TestPlugin("Plugin", List.of(), List.of("SoftDependency"));

        var order = CascadingPluginCommand.createLoadOrder(List.of(plugin, softDependency), false);

        assertEquals(List.of("Plugin", "SoftDependency"), order.stream().map(Plugin::getName).toList());
    }

    private record TestPlugin(String name, List<String> depend, List<String> softDepend) implements Plugin {
        @Override
        public String getName() {
            return name;
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public String getVersion() {
            return "1.0";
        }

        @Override
        public List<String> getDepend() {
            return depend;
        }

        @Override
        public List<String> getSoftDepend() {
            return softDepend;
        }

        @Override
        public List<String> getAuthors() {
            return List.of();
        }

        @Override
        public File getFile() {
            return null;
        }

        @Override
        public <T> T getHandle() {
            return null;
        }
    }
}
