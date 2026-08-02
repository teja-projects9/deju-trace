package org.deju.plugin.privacy;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import com.intellij.ide.AppLifecycleListener;
import com.intellij.ide.plugins.IdeaPluginDescriptor;
import com.intellij.ide.plugins.PluginInstaller;
import com.intellij.ide.plugins.PluginStateListener;
import com.intellij.openapi.diagnostic.Logger;
import org.jetbrains.annotations.NotNull;

/**
 * Removes Deju's stored data when the plugin is uninstalled.
 *
 * <p><b>This is best effort, and the UI says so.</b> {@link PluginStateListener} is called
 * when a plugin is uninstalled through the Plugins page of a running IDE. It is not called
 * when the plugin directory is deleted by hand, when the IDE is not running, or for
 * projects that happen to be closed at that moment, and the platform offers nothing that
 * would be. <b>Clear Deju data…</b> is the reliable path and exists precisely because this
 * one cannot be.
 *
 * <p>Registered from {@code appFrameCreated} rather than an extension point because
 * {@code PluginStateListener} is not one; it is a listener you add to
 * {@link PluginInstaller}.
 */
public final class DejuUninstallCleanup implements AppLifecycleListener {

    private static final Logger LOG = Logger.getInstance(DejuUninstallCleanup.class);
    private static final String PLUGIN_ID = "org.deju.trace";
    private static final AtomicBoolean REGISTERED = new AtomicBoolean(false);

    @Override
    public void appFrameCreated(@NotNull List<String> commandLineArgs) {
        register();
    }

    /** Idempotent: the listener has no removal API, so adding it twice would double the work. */
    private static void register() {
        if (!REGISTERED.compareAndSet(false, true)) {
            return;
        }
        try {
            PluginInstaller.addStateListener(new PluginStateListener() {
                @Override
                public void install(@NotNull IdeaPluginDescriptor descriptor) {
                    // Nothing to do: the agent jar is extracted lazily on first use.
                }

                @Override
                public void uninstall(@NotNull IdeaPluginDescriptor descriptor) {
                    if (!PLUGIN_ID.equals(descriptor.getPluginId().getIdString())) {
                        return;
                    }
                    LOG.info("Deju uninstalled; clearing stored data.");
                    try {
                        DejuReset.clearEverythingQuietly();
                    } catch (RuntimeException e) {
                        // The IDE is mid-uninstall. Failing here must not derail that.
                        LOG.warn("Deju uninstall cleanup did not complete", e);
                    }
                }
            });
        } catch (RuntimeException | LinkageError e) {
            // Never let a cleanup convenience stop the IDE frame from coming up.
            LOG.warn("Could not register Deju uninstall cleanup", e);
        }
    }
}
