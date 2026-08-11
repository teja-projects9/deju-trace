package org.deju.plugin.history;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.GZIPOutputStream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.fileEditor.FileDocumentManager;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.vfs.VirtualFile;
import org.jetbrains.annotations.Nullable;

import org.deju.plugin.contract.CallNode;
import org.deju.plugin.contract.DejuPayload;
import org.deju.plugin.contract.FileCoverage;
import org.deju.plugin.contract.LineCoverage;
import org.deju.plugin.exclude.DejuExclusions;
import org.deju.plugin.exclude.TypeExclusionMatcher;
import org.deju.plugin.run.DejuAgentBundle;
import org.deju.plugin.source.SourceResolver;

/**
 * Produces a single self-contained HTML report of the code traces, colored
 * green/yellow/red. Only the travelled (trace-point) lines are shown, each with its own
 * source text, never the whole file. The report is theme-aware (follows the viewer's
 * light/dark preference) and carries an in-page toggle to switch. No variable pane, ever.
 *
 * <p>The markup, styles and behaviour live in {@code /report/report.html}, {@code .css}
 * and {@code .js} on the classpath and are inlined here at generate time. Keeping them as
 * real files (rather than one Java string literal) is what makes them editable and
 * debuggable in a browser; the emitted report is still one file with no external
 * references.
 *
 * <p><b>Security:</b> traced source is treated as hostile input. It is never
 * concatenated into markup. Instead the whole report model (including each source
 * line) is embedded as a JSON blob and the inline script builds the DOM using
 * {@code textContent}, so {@code <}, {@code >}, quotes and anything resembling
 * {@code <script>} can never execute. The {@code </} sequence is additionally escaped
 * so the JSON cannot terminate the embedding {@code <script>} tag. There are no remote
 * resources and no network calls in the report.
 */
public final class HtmlReportGenerator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String TEMPLATE_RESOURCE = "/report/report.html";
    private static final String CSS_RESOURCE = "/report/report.css";
    private static final String JS_RESOURCE = "/report/report.js";

    private final Project project;

    public HtmlReportGenerator(Project project) {
        this.project = project;
    }

    /** Writes a report containing everything that was recorded. */
    public String generate(DejuPayload payload) throws IOException {
        return generate(payload, false);
    }

    /**
     * @param omitExcluded when true, the source of excluded types is left out of the file
     *                     rather than shipped and hidden by the report's script. A trace
     *                     through a DTO-heavy stack spends most of its bytes on code lines
     *                     nobody intends to read, and hiding them client-side still pays for
     *                     them on every download. The call tree keeps its frames either way,
     *                     so the roll-up rows still account for the time.
     */
    public String generate(DejuPayload payload, boolean omitExcluded) throws IOException {
        Map<String, Object> model = buildModel(payload, omitExcluded);
        String payloadBlock = payloadBlock(MAPPER.writeValueAsString(model));

        String logo = logoDataUri();
        String favicon = logo.isEmpty() ? "" : "<link rel=\"icon\" type=\"image/png\" href=\"" + logo + "\">";
        String logoImg = logo.isEmpty() ? "" : "<img class=\"logo\" alt=\"\" src=\"" + logo + "\">";

        // Substitution order matters: the payload goes in LAST, so that traced source
        // which happens to contain a placeholder token (e.g. the literal __SCRIPT__)
        // cannot be expanded by a later pass.
        return resource(TEMPLATE_RESOURCE)
                .replace("__STYLES__", resource(CSS_RESOURCE))
                .replace("__SCRIPT__", resource(JS_RESOURCE))
                .replace("__FAVICON_LINK__", favicon)
                .replace("__LOGO_IMG__", logoImg)
                .replace("__PAYLOAD_BLOCK__", payloadBlock);
    }

    /**
     * Payloads at or above this size are gzipped into the file instead of embedded as text.
     *
     * <p>Below it the compression is not worth what it costs: base64 gives back a third of
     * what gzip saves, and reading a compressed report needs {@code DecompressionStream},
     * which an older browser may not have. A small report should open anywhere; a 40 MB one
     * has a size problem worth trading for.
     */
    private static final int GZIP_ABOVE_BYTES = 256 * 1024;

    /**
     * The {@code <script>} element carrying the payload, compressed when it is large enough
     * to matter.
     *
     * <p>Two forms, and exactly one is ever written, because an empty element of the other
     * kind would be indistinguishable from a payload that is genuinely empty. Base64 uses
     * only {@code A-Za-z0-9+/=}, so the compressed form cannot close the element it sits in
     * and needs no escaping; the plain form still does.
     */
    private static String payloadBlock(String json) throws IOException {
        byte[] utf8 = json.getBytes(StandardCharsets.UTF_8);
        if (utf8.length < GZIP_ABOVE_BYTES) {
            return "<script type=\"application/json\" id=\"deju-data\">"
                    + json.replace("</", "<\\/") + "</script>";
        }
        ByteArrayOutputStream packed = new ByteArrayOutputStream(utf8.length / 4);
        try (GZIPOutputStream gz = new GZIPOutputStream(packed)) {
            gz.write(utf8);
        }
        return "<script type=\"application/octet-stream\" id=\"deju-data-gz\">"
                + Base64.getEncoder().encodeToString(packed.toByteArray()) + "</script>";
    }

    /** Reads a bundled report resource as UTF-8. Missing means a broken build, not bad input. */
    private static String resource(String name) throws IOException {
        try (InputStream in = HtmlReportGenerator.class.getResourceAsStream(name)) {
            if (in == null) {
                throw new IOException("Report resource missing from the plugin jar: " + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /**
     * The brand logo as a base64 {@code data:} URI, or empty string if the bundled logo can't
     * be read. Base64 uses only {@code A-Za-z0-9+/=}, so it is safe inside a double-quoted
     * attribute with no escaping. Shared by the page favicon and the header image.
     */
    private static String logoDataUri() {
        // The 64px asset, not the 1024px source. The report shows it at 28 CSS pixels and
        // embeds the data URI twice (favicon + header), so the full-size original cost
        // ~149 KB of base64 in every exported file, more than half the report's fixed
        // weight, for an icon nobody can see the detail of.
        try (InputStream in = HtmlReportGenerator.class.getResourceAsStream("/icons/dejuLogo-report.png")) {
            if (in == null) {
                return "";
            }
            String b64 = Base64.getEncoder().encodeToString(in.readAllBytes());
            return "data:image/png;base64," + b64;
        } catch (IOException e) {
            return "";
        }
    }

    /** Builds a plain data model: per file, per covered line, the source text + status. */
    private Map<String, Object> buildModel(DejuPayload payload, boolean omitExcluded) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("target", payload.getTarget());
        model.put("startedAtIso", payload.getStartedAtIso());
        model.put("durationMs", payload.getDurationMs());
        // Needed to build jetbrains://idea/navigate links, which address a file by the
        // name of the open project plus a project-relative path.
        model.put("projectName", project.getName());

        List<String> excluded = excludedClasses(payload);
        Set<String> omit = omitExcluded ? new LinkedHashSet<>(excluded) : Set.of();

        List<Map<String, Object>> files = new ArrayList<>();
        for (FileCoverage fc : payload.getFiles()) {
            if (omit.contains(fc.getFqClassName())) {
                continue;   // its frames stay in the call tree; only the source is dropped
            }
            VirtualFile vf = SourceResolver.resolve(project, fc.getFqClassName(), fc.getSourceFileName());
            Document document = documentOf(vf);

            Map<String, Object> fileModel = new LinkedHashMap<>();
            fileModel.put("fqClassName", fc.getFqClassName());
            fileModel.put("sourceFileName", fc.getSourceFileName());
            if (vf != null) {
                fileModel.put("path", projectRelativePath(vf));
                fileModel.put("absPath", vf.getPath());
            }

            List<Map<String, Object>> lines = new ArrayList<>();
            for (LineCoverage lc : fc.getLines()) {
                Map<String, Object> lineModel = new LinkedHashMap<>();
                lineModel.put("line", lc.getLine());
                lineModel.put("status", lc.getStatus() == null ? "NONE" : lc.getStatus().name());
                lineModel.put("branchesCovered", lc.getBranchesCovered());
                lineModel.put("branchesTotal", lc.getBranchesTotal());
                lineModel.put("timeMicros", lc.getTimeMicros());
                lineModel.put("methodTotalMicros", lc.getMethodTotalMicros());
                // Self time is what identifies the method actually doing the slow work;
                // total time always favours whatever is highest up the call stack.
                lineModel.put("methodSelfMicros", lc.getMethodSelfMicros());
                lineModel.put("methodName", lc.getMethodName());
                lineModel.put("methodStart", lc.getMethodStart());
                lineModel.put("code", sourceLine(document, lc.getLine()));
                lines.add(lineModel);
            }
            fileModel.put("lines", lines);
            files.add(fileModel);
        }
        model.put("files", files);
        model.put("calls", callModel(payload));
        model.put("callsTruncated", payload.isCallsTruncated());
        // Carried so the report can explain a missing call tree instead of just hiding it:
        // an older agent still loaded in the traced JVM is the usual cause.
        model.put("agentVersion", payload.getAgentVersion());
        model.put("pluginVersion", DejuAgentBundle.pluginVersion());
        model.put("excludedClasses", excluded);
        // Lets the report say the source was left out on purpose, instead of rendering
        // frames with no code and looking broken.
        model.put("excludedOmitted", omitExcluded);
        return model;
    }

    /**
     * The classes this project excludes, resolved to concrete names present in this payload.
     *
     * <p>Globs are expanded <b>here</b> rather than shipped to the report, so the browser
     * only ever does a set lookup. That keeps one glob implementation in Java where it is
     * unit-tested, instead of a second one in JavaScript that could quietly disagree with
     * it about, say, whether a dot is literal.
     *
     * <p>The trade-off is that the resolved set is a snapshot: editing the exclusion list
     * does not retro-fit an already-exported file. The report's "Full" detail level covers
     * that, since the complete call tree is always present in the payload regardless.
     */
    private List<String> excludedClasses(DejuPayload payload) {
        TypeExclusionMatcher matcher = DejuExclusions.getInstance(project).matcher();
        Set<String> observed = new LinkedHashSet<>();
        for (CallNode node : payload.getCalls()) {
            if (node.getClassName() != null) {
                observed.add(node.getClassName());
            }
        }
        for (FileCoverage file : payload.getFiles()) {
            if (file.getFqClassName() != null) {
                observed.add(file.getFqClassName());
            }
        }
        return matcher.filterExcluded(observed);
    }

    /**
     * The recorded call tree, flat and in execution order. Class/method names are carried
     * per node; the source file name and the lines themselves are looked up by class name
     * against the {@code files} array, so nothing is duplicated per invocation.
     */
    /**
     * The call list, column by column, with the repeated strings pulled into tables.
     *
     * <p>One object per call spends its bytes on the same words over and over: the key names
     * are re-spelled for every entry, and a class name is re-spelled for every invocation of
     * it. A 200,000-call run is around 30 MB written that way, and only a few hundred
     * distinct class names and statements are in it. Columns remove the repeated keys and the
     * tables remove the repeated strings; the same run comes out near 4 MB, with nothing
     * dropped, which is the difference between a report you can send someone and one you
     * cannot.
     *
     * <p>{@code seq} is not stored. It is the position in the columns, and the reader
     * restores it from there.
     */
    private static Map<String, Object> callModel(DejuPayload payload) {
        List<CallNode> nodes = payload.getCalls();
        List<String> classes = new ArrayList<>();
        List<String> methods = new ArrayList<>();
        List<String> statements = new ArrayList<>();
        Map<String, Integer> classIx = new LinkedHashMap<>();
        Map<String, Integer> methodIx = new LinkedHashMap<>();
        Map<String, Integer> sqlIx = new LinkedHashMap<>();

        List<Integer> parent = new ArrayList<>(nodes.size());
        List<Integer> cls = new ArrayList<>(nodes.size());
        List<Integer> mth = new ArrayList<>(nodes.size());
        List<Integer> sql = new ArrayList<>(nodes.size());
        List<Object> site = new ArrayList<>(nodes.size());
        List<Object> total = new ArrayList<>(nodes.size());

        for (CallNode node : nodes) {
            parent.add(node.getParentSeq());
            cls.add(intern(node.getClassName(), classIx, classes));
            mth.add(intern(node.getMethodName(), methodIx, methods));
            // Present only on query nodes; the report uses it to tell a query from a call.
            sql.add(intern(node.getSql(), sqlIx, statements));
            site.add(node.getCallSiteLine());
            total.add(node.getTotalMicros());
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("n", nodes.size());
        out.put("classTable", classes);
        out.put("methodTable", methods);
        out.put("sqlTable", statements);
        out.put("parentSeq", parent);
        out.put("className", cls);
        out.put("methodName", mth);
        out.put("sql", sql);
        out.put("callSiteLine", site);
        out.put("totalMicros", total);
        return out;
    }

    /** Index of {@code value} in its table, adding it if new. {@code -1} stands for absent. */
    private static int intern(String value, Map<String, Integer> index, List<String> table) {
        if (value == null) {
            return -1;
        }
        Integer at = index.get(value);
        if (at != null) {
            return at;
        }
        index.put(value, table.size());
        table.add(value);
        return table.size() - 1;
    }

    @Nullable
    private static Document documentOf(@Nullable VirtualFile vf) {
        if (vf == null) {
            return null;
        }
        // getDocument must run under a read action; the VirtualFile is already resolved.
        return ReadAction.compute(() -> FileDocumentManager.getInstance().getDocument(vf));
    }

    /**
     * The file's path relative to the project root, which is what the IDE's navigate URL
     * expects. Falls back to the absolute path for a file outside the project (a library
     * source, or a configured source root elsewhere on disk).
     */
    private String projectRelativePath(VirtualFile vf) {
        String base = project.getBasePath();
        String path = vf.getPath();
        if (base != null && !base.isEmpty()) {
            String prefix = base.endsWith("/") ? base : base + "/";
            if (path.startsWith(prefix)) {
                return path.substring(prefix.length());
            }
        }
        return path;
    }

    /** Raw source text of a 1-based line, or empty if unavailable. Kept as data, never markup. */
    private static String sourceLine(@Nullable Document document, int line) {
        if (document == null) {
            return "";
        }
        int line0 = line - 1;
        if (line0 < 0 || line0 >= document.getLineCount()) {
            return "";
        }
        int start = document.getLineStartOffset(line0);
        int end = document.getLineEndOffset(line0);
        return document.getText(new TextRange(start, end));
    }
}
