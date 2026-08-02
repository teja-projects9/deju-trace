package org.deju.agent;

import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.util.concurrent.atomic.AtomicBoolean;

import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.modifier.SyntheticState;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.matcher.ElementMatchers;

import org.deju.agent.instrument.DejuAsmWrapper;
import org.deju.agent.runtime.CoverageRuntime;
import org.deju.agent.socket.SocketServer;
import org.deju.agent.sql.JdbcAdvice;
import org.deju.agent.sql.SqlCarrier;

/**
 * Agent entry point. Attach with:
 * <pre>
 *   -javaagent:deju-agent.jar=port=7391,token=devtoken,includes=com.example
 * </pre>
 *
 * <p>Phase 2 wires a stdout sink and (optionally) arms a target from config so
 * instrumentation can be proven on the console. The socket server (phase 3) later
 * replaces the sink and drives arm/disarm from the IDE.
 */
public final class DejuAgent {

    /**
     * Guards against the agent being installed more than once. The JVM calls
     * {@code premain} once per {@code -javaagent} flag, on the same class in the same
     * classloader, so a duplicated flag would otherwise install a second transformer
     * (double-probing every class) and then fail to bind the already-taken port,
     * whose fallback would replace the working socket sink with stdout.
     */
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

    private DejuAgent() {
    }

    public static void premain(String args, Instrumentation inst) {
        if (!INSTALLED.compareAndSet(false, true)) {
            System.err.println("[deju] agent already installed; ignoring duplicate -javaagent."
                    + " Remove the extra flag from your run configuration.");
            return;
        }

        AgentConfig cfg = AgentConfig.parse(args);

        installTransformer(inst, cfg);

        // Primary sink is the token-gated socket the plugin connects to, loopback unless
        // bind= says otherwise. If the port can't be bound we fall back to stdout so the
        // agent still works for console verification.
        try {
            SocketServer server = new SocketServer(cfg.getPort(), cfg.getToken(), cfg.getBind());
            server.start();
            CoverageRuntime.configure(server);
        } catch (IOException e) {
            String where = cfg.getBind() + ":" + cfg.getPort();
            // Only fall back when nothing is wired yet, replacing a live sink would
            // silently divert completed sessions away from an already-connected plugin.
            if (CoverageRuntime.hasSink()) {
                System.err.println("[deju] could not bind socket on " + where
                        + " (" + e.getMessage() + "); keeping the existing sink");
            } else {
                System.err.println("[deju] could not bind socket on " + where
                        + " (" + e.getMessage() + "); falling back to stdout");
                CoverageRuntime.configure(new StdoutSink());
            }
        }

        // Optional: arm from config at startup (handy for console testing).
        if (cfg.getArmAtStart() != null) {
            CoverageRuntime.arm(cfg.getArmAtStart());
        }

        System.out.println("[deju] agent ready. includes=" + cfg.getIncludes()
                + (cfg.getArmAtStart() != null ? " armed=" + cfg.getArmAtStart() : " (unarmed)"));
    }

    private static void installTransformer(Instrumentation inst, AgentConfig cfg) {
        // Build the include matcher from configured package prefixes. With no
        // includes, nothing is instrumented (recording is gated on a session anyway).
        // TODO(deju): retransform-on-arm would let the agent discover the target's
        // package automatically; the MVP asks the user to pass includes= up front.
        ElementMatcher.Junction<TypeDescription> included = ElementMatchers.none();
        for (String prefix : cfg.getIncludes()) {
            included = included.or(ElementMatchers.nameStartsWith(prefix));
        }

        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(AgentBuilder.TypeStrategy.Default.REDEFINE)
                // Never instrument our own code, the shaded deps (org.deju.agent.shaded.*),
                // or the JDK.
                .ignore(ElementMatchers.nameStartsWith("org.deju.agent.")
                        .or(ElementMatchers.nameStartsWith("java."))
                        .or(ElementMatchers.nameStartsWith("javax."))
                        .or(ElementMatchers.nameStartsWith("jdk."))
                        .or(ElementMatchers.nameStartsWith("sun."))
                        .or(ElementMatchers.nameStartsWith("com.sun.")))
                .type(included)
                .transform((builder, typeDescription, classLoader, module, protectionDomain)
                        -> builder.visit(new DejuAsmWrapper()))
                .installOn(inst);

        installJdbcTransformer(inst);
    }

    /**
     * Makes SQL queries visible in the call tree.
     *
     * <p>Matched by INTERFACE rather than by package, because a driver's package is not
     * knowable in advance, {@code org.postgresql}, {@code com.mysql}, {@code oracle.jdbc}
     * and the connection pool's own proxies all implement the same {@code java.sql} types.
     * They are therefore outside {@code includes=} and invisible to the transformer above,
     * which is why a query currently shows up only as unexplained time on its call-site line.
     *
     * <p>Deliberately without {@code RETRANSFORMATION}: this adds a field to carry the SQL
     * text (see {@link SqlCarrier}), and the JVM forbids adding fields when redefining an
     * already-loaded class. Loading order makes that fine, a {@code -javaagent} installs
     * during {@code premain}, long before any {@code DataSource} initialises.
     *
     * <p>Recording still costs nothing until a trace point is armed: the advice calls into
     * {@link CoverageRuntime#sqlEnter()}, which returns immediately when no session is open.
     */
    private static void installJdbcTransformer(Instrumentation inst) {
        ElementMatcher.Junction<TypeDescription> preparedStatement =
                ElementMatchers.hasSuperType(ElementMatchers.named("java.sql.PreparedStatement"));
        ElementMatcher.Junction<TypeDescription> statement =
                ElementMatchers.hasSuperType(ElementMatchers.named("java.sql.Statement"));
        ElementMatcher.Junction<TypeDescription> connection =
                ElementMatchers.hasSuperType(ElementMatchers.named("java.sql.Connection"));

        new AgentBuilder.Default()
                .ignore(ElementMatchers.nameStartsWith("org.deju.agent."))
                // Prepared statements: carry the SQL on the statement, then time execute*().
                .type(preparedStatement.and(ElementMatchers.not(ElementMatchers.isInterface())))
                .transform((builder, type, loader, module, pd) -> builder
                        .defineField("dejuSql$deju", String.class, Visibility.PRIVATE, SyntheticState.SYNTHETIC)
                        .implement(SqlCarrier.class)
                        .intercept(FieldAccessor.ofField("dejuSql$deju"))
                        .visit(Advice.to(JdbcAdvice.ExecutePrepared.class)
                                .on(ElementMatchers.isPublic()
                                        .and(ElementMatchers.takesArguments(0))
                                        .and(ElementMatchers.nameStartsWith("execute")))))
                // Plain statements: the SQL is the first argument of execute*(String, …).
                .type(statement.and(ElementMatchers.not(ElementMatchers.isInterface())))
                .transform((builder, type, loader, module, pd) -> builder
                        .visit(Advice.to(JdbcAdvice.ExecuteWithSql.class)
                                .on(ElementMatchers.isPublic()
                                        .and(ElementMatchers.nameStartsWith("execute"))
                                        .and(ElementMatchers.takesArgument(0, String.class)))))
                // Connections: staple the SQL onto the statement they hand back.
                .type(connection.and(ElementMatchers.not(ElementMatchers.isInterface())))
                .transform((builder, type, loader, module, pd) -> builder
                        .visit(Advice.to(JdbcAdvice.Prepare.class)
                                .on(ElementMatchers.isPublic()
                                        .and(ElementMatchers.named("prepareStatement")
                                                .or(ElementMatchers.named("prepareCall")))
                                        .and(ElementMatchers.takesArgument(0, String.class)))))
                .installOn(inst);
    }
}
