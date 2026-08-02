package org.deju.plugin.run;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.intellij.execution.CommonJavaRunConfigurationParameters;
import com.intellij.execution.RunManager;
import com.intellij.execution.RunnerAndConfigurationSettings;
import com.intellij.execution.configurations.RunConfiguration;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;

/**
 * Points this project's Java run configurations at the current bundled agent.
 *
 * <p>A {@code -javaagent} flag pasted into a run configuration freezes a path. When the
 * plugin updates, the extracted jar gets a new content-hashed name and the old one is
 * pruned, so a configuration pinned to the previous name stops the application from
 * starting at all, the JVM refuses to boot when an agent jar is missing. Rewriting the
 * path is the fix; asking the user to re-copy the VM option by hand is not.
 *
 * <p>Only flags inside the plugin's own managed directory are touched, so a profiler or APM
 * agent in the same configuration is left exactly as it was.
 */
public final class RunConfigAgentUpdater {

    private static final Logger LOG = Logger.getInstance(RunConfigAgentUpdater.class);

    /** What a scan or an update found, for reporting back to the user. */
    public static final class Result {
        /** Names of configurations whose agent path was rewritten. */
        public final List<String> updated = new ArrayList<>();
        /** Names of configurations that already referenced the current agent. */
        public final List<String> alreadyCurrent = new ArrayList<>();

        public boolean isEmpty() {
            return updated.isEmpty() && alreadyCurrent.isEmpty();
        }
    }

    private RunConfigAgentUpdater() {
    }

    /**
     * Rewrites every stale Deju agent path in this project's run configurations.
     *
     * <p>Must be called on the EDT: it mutates run configurations, which IntelliJ then
     * persists to {@code .idea/workspace.xml}.
     *
     * @return what changed, or {@code null} if the plugin ships no agent to point at.
     */
    public static Result updateAll(Project project) {
        Path agent = DejuAgentBundle.ensureExtracted();
        if (agent == null) {
            return null;
        }
        String managedDir = DejuAgentBundle.managedDir().toString();
        String target = agent.toString();

        Result result = new Result();
        for (RunnerAndConfigurationSettings settings : RunManager.getInstance(project).getAllSettings()) {
            RunConfiguration configuration = settings.getConfiguration();
            if (!(configuration instanceof CommonJavaRunConfigurationParameters)) {
                continue;   // not a JVM configuration; it has no VM parameters to fix
            }
            CommonJavaRunConfigurationParameters java = (CommonJavaRunConfigurationParameters) configuration;
            String vmParameters = java.getVMParameters();
            if (!AgentVmOption.containsManagedAgent(vmParameters, managedDir)) {
                continue;
            }
            String rewritten = AgentVmOption.rewrite(vmParameters, managedDir, target);
            if (rewritten == null) {
                result.alreadyCurrent.add(configuration.getName());
                continue;
            }
            try {
                java.setVMParameters(rewritten);
                result.updated.add(configuration.getName());
            } catch (RuntimeException e) {
                // A configuration type may reject edits (a template, or one owned by another
                // plugin). One awkward configuration must not abort the rest.
                LOG.warn("Could not update the agent path in run configuration '"
                        + configuration.getName() + "'", e);
            }
        }
        return result;
    }
}
