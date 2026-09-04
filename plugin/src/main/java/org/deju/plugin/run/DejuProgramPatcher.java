package org.deju.plugin.run;

import java.nio.file.Path;

import com.intellij.execution.Executor;
import com.intellij.execution.configurations.JavaParameters;
import com.intellij.execution.configurations.ParametersList;
import com.intellij.execution.configurations.RunProfile;
import com.intellij.execution.runners.JavaProgramPatcher;
import com.intellij.openapi.diagnostic.Logger;

import org.deju.plugin.DejuSettings;

/**
 * Auto-attaches the bundled agent to Java run configurations that IntelliJ launches
 * locally, so the user never edits VM options by hand. This is the "plugin manages the
 * agent" path, and it can only exist for JVMs the IDE actually starts. A container
 * launched by docker-compose is out of the IDE's reach; that path still needs the agent
 * flag in its own compose/env.
 *
 * <p>Injection is gated on {@link DejuSettings#autoAttach}, and is skipped if the run already
 * carries a deju agent flag (a manual one, or a re-patch of the same launch), so the agent is
 * never installed twice. The bundled agent is nevertheless extracted on every launch, so a
 * pre-existing flag can never block the refresh that keeps it current.
 *
 * <p>{@link DejuSettings#includes} is used when set; when it is empty, {@link IncludesGuess}
 * is tried against this run's own main class before giving up, so a Spring Boot run
 * configuration &mdash; the common case &mdash; auto-attaches with no Settings visit at all.
 */
public final class DejuProgramPatcher extends JavaProgramPatcher {

    private static final Logger LOG = Logger.getInstance(DejuProgramPatcher.class);

    @Override
    public void patchJavaParameters(Executor executor, RunProfile configuration, JavaParameters javaParameters) {
        DejuSettings settings = DejuSettings.getInstance();
        if (!settings.autoAttach) {
            return;
        }
        if (javaParameters == null) {
            return;
        }
        String includes = settings.includes == null ? "" : settings.includes.trim();
        boolean guessed = false;
        if (includes.isEmpty()) {
            includes = IncludesGuess.fromMainClass(javaParameters.getMainClass());
            if (includes.isEmpty()) {
                return; // Nothing configured and nothing to guess it from; still a no-op.
            }
            guessed = true;
        }

        ParametersList vmParameters = javaParameters.getVMParametersList();
        String existing = vmParameters.getParameters().stream()
                .filter(p -> p.contains("deju-agent"))
                .findFirst()
                .orElse(null);

        // Extract FIRST, even when an agent flag is already present. The old order returned
        // before this call, so a hand-written -javaagent pointing into our own managed
        // directory suppressed the very refresh that keeps that file current: the plugin
        // could be updated repeatedly while the traced app kept loading a months-old agent,
        // silently losing features. Extraction also refreshes the fixed-name alias that such
        // a flag points at, so the next JVM start picks up the new agent.
        Path agentJar = DejuAgentBundle.ensureExtracted();
        if (agentJar == null) {
            return; // Warned once in the bundle; nothing to attach.
        }

        if (existing != null) {
            // Someone else owns this flag. Don't attach twice, but say which agent is
            // really in play, because a stale one is otherwise invisible.
            if (DejuAgentBundle.isManagedPath(existing)) {
                LOG.info("Deju: run '" + safeName(configuration) + "' already carries a"
                        + " -javaagent from the plugin's own directory; refreshed it to "
                        + agentJar.getFileName() + ". Restart the traced JVM for it to load.");
            } else {
                LOG.warn("Deju: run '" + safeName(configuration) + "' carries a -javaagent"
                        + " outside the plugin's directory (" + existing + "). The plugin does"
                        + " not manage that jar, so it will NOT be updated by plugin updates."
                        + " The current bundled agent is at " + agentJar);
            }
            return;
        }

        // includes= is colon-separated on the agent side (commas delimit top-level pairs).
        String includesArg = includes.replace(',', ':');
        String arg = "-javaagent:" + agentJar
                + "=port=" + settings.port
                + ",token=" + settings.token
                + ",includes=" + includesArg;
        vmParameters.add(arg);
        LOG.info("Deju: auto-attached agent to run '" + safeName(configuration)
                + "' (port=" + settings.port + ", includes=" + includesArg
                + (guessed ? ", guessed from the run's main class - set Includes in"
                        + " Settings > Tools > Deju Trace to override" : "") + ")");
    }

    private static String safeName(RunProfile configuration) {
        try {
            return configuration == null ? "?" : configuration.getName();
        } catch (Exception e) {
            return "?";
        }
    }
}
