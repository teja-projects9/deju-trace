/*
 * Deju HTML report behaviour. Inlined verbatim into a <script> element by
 * HtmlReportGenerator, so the exported file stays self-contained.
 *
 * Two views over one payload:
 *   Tree  (default) - every invocation in execution order, each method's own lines
 *                     interleaved with the calls it made, at the line that made them.
 *   Files           - per-file line coverage, grouped into method sections.
 *
 * SECURITY: every value that originates from the traced application (class names,
 * source text, file paths) is written with textContent - never innerHTML and never
 * concatenated into markup. The one place payload data reaches an attribute is the
 * "open in IDE" href, where the scheme is a hardcoded literal prefix and the payload
 * part is percent-encoded, so a hostile class name cannot produce a javascript: URI
 * or inject extra query parameters.
 */
(function boot() {
  'use strict';

  /*
   * A large payload is written gzipped and base64'd, so the report starts by unpacking it
   * and then re-entering this same function with the JSON in hand. Re-entry rather than
   * wrapping everything below in a callback: the bail-out is the first statement here, so
   * nothing has run yet and there is nothing to undo, and the 3,000 lines underneath stay
   * exactly as they are.
   */
  /**
   * An element by id, or a detached stand-in when the export left it out.
   *
   * <p>Dropping a tab at export time takes its button, its toolbar controls and its panel
   * with it, which would otherwise turn several dozen lookups below into null dereferences
   * spread over the whole file. A stand-in accepts listeners, classes and textContent like
   * any element and is never in the document, so nothing it is told to do can be seen,
   * measured or clicked. Tabs are the only markup that can go missing, and code that needs
   * to know asks tabPresent rather than testing a lookup for null.
   *
   * <p>The payload blocks are exempt. Their absence is real information rather than a tab
   * somebody chose to leave out, and the boot path has to be able to tell the difference.
   */
  var elementStubs = {};
  function byId(id) {
    var found = document.getElementById(id);
    if (found) return found;
    if (id.indexOf('deju-') === 0) return null;
    return elementStubs[id] || (elementStubs[id] = document.createElement('span'));
  }

  /** True when this id survived the export, i.e. its tab was included in the file. */
  function tabPresent(id) {
    return !!document.getElementById(id);
  }

  var payloadText = payloadJson();
  if (payloadText === null) return;          // unpacking; boot() runs again when it lands

  var data = JSON.parse(payloadText);
  delete window.__dejuJson;

  /** The payload as text, or null when it is being unpacked (or cannot be). */
  function payloadJson() {
    if (window.__dejuJson != null) return window.__dejuJson;
    var plain = byId('deju-data');
    if (plain) return plain.textContent;
    var packed = byId('deju-data-gz');
    if (!packed) {
      cannotOpen('This report has no data in it. It was probably truncated in transit.');
      return null;
    }
    inflate(packed.textContent, function (json) {
      window.__dejuJson = json;
      boot();
    }, cannotOpen);
    return null;
  }

  /** gzip -> text, via the platform's own decompressor; no library is shipped. */
  function inflate(base64, ok, fail) {
    if (typeof DecompressionStream !== 'function' || typeof Blob !== 'function') {
      fail('This report stores its data compressed, and this browser cannot unpack it.'
        + ' Open it in a current Chrome, Edge, Firefox or Safari.');
      return;
    }
    try {
      var bin = atob(base64.trim());
      var bytes = new Uint8Array(bin.length);
      for (var i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
      new Response(new Blob([bytes]).stream()
        .pipeThrough(new DecompressionStream('gzip'))).text()
        .then(ok, function () { fail('The data in this report could not be unpacked.'); });
    } catch (e) {
      fail('The data in this report could not be unpacked.');
    }
  }

  /** Says so on the page. A blank report with an error only in the console helps nobody. */
  function cannotOpen(message) {
    var meta = byId('metaText');
    if (meta) meta.textContent = 'Deju: this report could not be opened';
    var notice = byId('notice');
    if (notice) {
      notice.textContent = message;
      notice.hidden = false;
    }
  }
  var files = data.files || [];
  var calls = expandCalls(data.calls);

  /**
   * Restores the call list from the column-and-table form the exporter writes.
   *
   * <p>The wire form spends no bytes repeating key names or class names; this turns it back
   * into the plain objects the rest of the report reads, so nothing below has to know which
   * form the file was written in. {@code seq} comes from the position, which is why the
   * exporter does not store it.
   *
   * <p>An array is passed straight through: reports exported before this encoding existed
   * still carry one, and they have to keep opening.
   */
  function expandCalls(src) {
    if (!src) return [];
    if (Object.prototype.toString.call(src) === '[object Array]') return src;
    var out = new Array(src.n);
    var ct = src.classTable || [];
    var mt = src.methodTable || [];
    var qt = src.sqlTable || [];
    for (var i = 0; i < src.n; i++) {
      var ci = src.className[i];
      var mi = src.methodName[i];
      var qi = src.sql[i];
      out[i] = {
        seq: i,
        parentSeq: src.parentSeq[i],
        className: ci < 0 ? null : ct[ci],
        methodName: mi < 0 ? null : mt[mi],
        callSiteLine: src.callSiteLine[i],
        totalMicros: src.totalMicros[i],
        sql: qi < 0 ? null : qt[qi]
      };
    }
    return out;
  }

  // Classes the project excludes, already resolved from globs to concrete names by the
  // plugin at export time, so this is a plain set lookup, with no second glob engine
  // here that could disagree with the Java one.
  var excludedClasses = {};
  (data.excludedClasses || []).forEach(function (c) { excludedClasses[c] = true; });
  var hasExclusions = (data.excludedClasses || []).length > 0;
  // Export left the excluded types' source out of the file. Their frames are still here,
  // so the timings add up, but there is no code to reveal and the UI must say so rather
  // than offering a Full view that turns up blank rows.
  var excludedOmitted = !!data.excludedOmitted;

  // ------------------------------------------------------------------ prefs ---

  /*
   * How the report looks, from two sources that answer two different questions.
   *
   * The export dialog wrote a starting position into the file: what this run is best read
   * as, decided by whoever exported it. The reader's own choices live in localStorage,
   * stored sparsely, only the keys they actually touched, and layered on top. Storing the
   * whole object instead would freeze today's defaults into every report opened afterwards,
   * so a report exported next month to open on the Timeline would still open on the tree
   * because someone once clicked something.
   *
   * The store is shared by every Deju report rather than keyed per run, matching how the
   * theme toggle has always behaved: "I like percentages off" is a fact about the reader,
   * not about a trace.
   */
  var PREFS_DEFAULT = {
    openTab: 'trace', view: 'tree', density: 'normal', theme: 'auto',
    showTime: true, showPercent: true, showStep: true, showLine: true,
    sql: true, groupRepeats: false, collapseTree: false, collapseSections: false
  };
  var PREFS_KEY = 'deju.report.prefs';

  /** The starting position the export chose, or the long-standing defaults without one. */
  var exportedPrefs = (function () {
    var out = {};
    for (var k in PREFS_DEFAULT) out[k] = PREFS_DEFAULT[k];
    var block = document.getElementById('deju-prefs');
    if (block) {
      try {
        var sent = JSON.parse(block.textContent);
        for (var j in PREFS_DEFAULT) if (sent[j] !== undefined) out[j] = sent[j];
      } catch (e) {
        // A malformed block is a broken export, not something to fail the report over.
      }
    }
    return out;
  }());

  /** Only what the reader changed. Wrapped: a file:// page can have storage denied. */
  function readOverrides() {
    try {
      var raw = window.localStorage.getItem(PREFS_KEY);
      var parsed = raw ? JSON.parse(raw) : null;
      return parsed && typeof parsed === 'object' ? parsed : {};
    } catch (e) {
      return {};
    }
  }

  function writeOverrides(o) {
    try {
      window.localStorage.setItem(PREFS_KEY, JSON.stringify(o));
    } catch (e) {
      // Storage is a convenience here; the panel still works for this sitting.
    }
  }

  var prefOverrides = readOverrides();
  var prefs = {};
  (function () {
    for (var k in exportedPrefs) prefs[k] = exportedPrefs[k];
    for (var j in prefOverrides) if (exportedPrefs[j] !== undefined) prefs[j] = prefOverrides[j];
  }());

  var counts = { FULL: 0, PARTIAL: 0, NONE: 0 };
  var statusOn = { FULL: true, PARTIAL: true, NONE: true };
  /* Declared with the other view state, not beside its button: applyTreeFilters reads it
     and is defined long before that wiring runs, so a `var` down there would be hoisted
     as undefined and hide every query on the first render. */
  var sqlOn = prefs.sql;
  /* Same reason as sqlOn: applyTreeFilters reads it long before its button is wired. */
  var rollupOn = true;
  var rollupAvailable = 0;   // roll-up rows the current filters leave on screen

  var AUTO_COLLAPSE_ABOVE = 15;   // file count past which the Files view starts collapsed
  var AUTO_EXPAND_TOP = 5;        // slowest N left open in that case
  var FOLD_RUN_AFTER = 3;         // identical consecutive sibling calls shown before folding
  // Rows are virtualised now, so this is a guard on how much the tree walk will build,
  // not on what gets laid out. It sits above the agent's own 200k call ceiling so an
  // ordinary run is never truncated by the viewer.
  var MAX_TREE_ROWS = 400000;

  // ---------------------------------------------------------------- helpers ---

  function trim(v) {
    if (v >= 100) return v.toFixed(0);
    var s = v.toFixed(1);
    return s.slice(-2) === '.0' ? s.slice(0, -2) : s;
  }

  function fmt(u) {
    if (u == null) return '';
    if (u < 1000) return u + ' µs';
    if (u < 1e6) return trim(u / 1000) + ' ms';
    if (u < 6e7) return trim(u / 1e6) + ' s';
    if (u < 3.6e9) { var t = Math.floor(u / 1e6); return Math.floor(t / 60) + 'm ' + (t % 60) + 's'; }
    var tm = Math.floor(u / 6e7);
    return Math.floor(tm / 60) + 'h ' + (tm % 60) + 'm';
  }

  /**
   * The whole traced call in microseconds: the denominator for every share on the page.
   *
   * <p>Seeded from the wall time the agent reported and raised to the widest root once the
   * call tree is in, because the two are rounded differently and a frame reading 101% of
   * the request undermines every other number beside it. Both are the same measurement;
   * taking the larger is the only reading that can never overshoot.
   */
  var runMicrosTotal = (data.durationMs || 0) * 1000;

  /**
   * A duration as a share of the run.
   *
   * <p>The point of putting this beside every time is that "48 ms" needs the reader to
   * remember what the request cost before it means anything, and "31%" does not. Kept to
   * one decimal below ten percent, none above it: the extra digit on a big share is noise,
   * and on a small one it is the difference between "rounds to nothing" and "0.4%".
   */
  function pct(micros) {
    if (!(runMicrosTotal > 0) || micros == null) return '';
    var p = (micros / runMicrosTotal) * 100;
    if (p >= 10) return p.toFixed(0) + '%';
    if (p >= 0.1) return p.toFixed(1) + '%';
    return p > 0 ? '<0.1%' : '0%';
  }

  /** The same share as {@link pct}, as a raw 0..100 number for driving a CSS bar width. */
  function pctNum(micros) {
    if (!(runMicrosTotal > 0) || micros == null) return 0;
    return Math.max(0, Math.min(100, (micros / runMicrosTotal) * 100));
  }

  /**
   * A share badge with a fill bar behind it, the Glowroot-style read-at-a-glance version
   * of {@link pct}. Only used on the Tree's frame rows (method calls, SQL), where every
   * badge sits in the same {@code .ftime} slot and so shares a comparable track width;
   * the Files gutter and the Timeline (which already has its own proportional bar) do not
   * get this treatment.
   */
  function pctBadge(micros) {
    var p = pct(micros);
    if (!p) return null;
    var b = el('span', 'fpct bar', p);
    b.style.setProperty('--fpct-fill', pctNum(micros) + '%');
    return b;
  }

  /** Time and share together, the form used wherever both fit on one line. */
  function fmtPct(micros) {
    var p = pct(micros);
    return p ? fmt(micros) + '  ' + p : fmt(micros);
  }

  /**
   * The recording's start time, in the reader's own timezone.
   *
   * <p>The payload carries UTC. Rewriting only the date half of it produced
   * "31-07-2026T09:24:18.221Z", which is neither a format anybody uses nor the time on the
   * clock the developer was watching. The ISO string is kept on the element's title, so the
   * exact recorded instant is still one hover away.
   */
  /**
   * A recorded instant as {@code dd/MM/yy HH:mm:ss}, in the reader's own timezone.
   *
   * <p>Fixed rather than {@code toLocaleString}: a report is written on one machine and read
   * on others, and a date that means one thing to the person who exported it and another to
   * the person they sent it to is worse than one nobody's locale would have chosen. The
   * ambiguous case is real, {@code 03/04/25} is two different days on two sides of an
   * ocean, and day-first at least matches the ISO instant kept alongside it in the tooltip.
   */
  function inDate(iso) {
    if (!iso) return '—';
    var d = new Date(iso);
    if (isNaN(d.getTime())) return iso;
    function two(n) { return (n < 10 ? '0' : '') + n; }
    return two(d.getDate()) + '/' + two(d.getMonth() + 1) + '/' + two(d.getFullYear() % 100)
      + ' ' + two(d.getHours()) + ':' + two(d.getMinutes()) + ':' + two(d.getSeconds());
  }

  /**
   * {@code com.acme.billing.InvoiceService#post} shown as {@code InvoiceService#post}.
   *
   * <p>The package is the longest part of the label and the least useful: it is the same on
   * every run of the same application, and it pushes the class and method, the part that
   * says what was traced, past the width the row has. Where the class begins is decided by
   * case, the first segment starting with a capital, so a nested class keeps its outer
   * class rather than being cut to a name that matches no file. The full name stays in the
   * row's tooltip.
   */
  function shortTarget(fq) {
    if (!fq) return '—';
    var hash = fq.indexOf('#');
    var type = hash < 0 ? fq : fq.slice(0, hash);
    var rest = hash < 0 ? '' : fq.slice(hash);
    var parts = type.split('.');
    for (var i = 0; i < parts.length; i++) {
      if (parts[i] && parts[i].charAt(0) >= 'A' && parts[i].charAt(0) <= 'Z') {
        return parts.slice(i).join('.') + rest;
      }
    }
    return fq;
  }

  function isGeneratedName(fq) {
    return !!fq && /\$\$|\$Lambda|\$Proxy|CGLIB|ByteBuddy|MockitoMock|MapperImpl$|_Factory$|_MembersInjector$|_$|(^|\.)generated\.|Generated$/i.test(fq);
  }

  function simpleName(fq) {
    if (!fq) return '';
    var s = fq.substring(fq.lastIndexOf('.') + 1);
    var d = s.lastIndexOf('$');
    return d >= 0 ? s.substring(d + 1) : s;
  }

  function methodKind(name) {
    if (name === '<init>') return 'constructor';
    if (name === '<clinit>') return 'static initializer';
    if (/^lambda\$/.test(name)) return 'lambda';
    if (/^access\$/.test(name)) return 'synthetic accessor';
    return '';
  }

  function methodLabel(name, fq) {
    if (name === '<init>' || name === '<clinit>') return simpleName(fq);
    return name;
  }

  function el(tag, cls, text) {
    var e = document.createElement(tag);
    if (cls) e.className = cls;
    if (text != null) e.textContent = text;
    return e;
  }

  var JAVA_KEYWORDS = {};
  ('abstract assert boolean break byte case catch char class const continue default do double '
    + 'else enum extends final finally float for goto if implements import instanceof int '
    + 'interface long native new package private protected public return short static strictfp '
    + 'super switch synchronized this throw throws transient try void volatile while '
    + 'var record sealed permits yield true false null').split(' ')
    .forEach(function (k) { JAVA_KEYWORDS[k] = true; });

  /* One pass over a line of Java. Order matters: comments and strings are matched before
     anything else, so a keyword inside a string literal is not coloured as code. */
  var TOKEN = new RegExp(
    '(//[^\\n]*)'                        // line comment
    + '|(/\\*(?:[^*]|\\*(?!/))*(?:\\*/)?)'  // block comment, possibly unterminated on this line
    + '|("(?:\\\\.|[^"\\\\])*"?)'           // string, possibly unterminated
    + "|('(?:\\\\.|[^'\\\\])*'?)"           // char
    + '|(@[A-Za-z_$][\\w$]*)'               // annotation
    + '|(\\b\\d[\\w.]*)'                    // number (0x1F, 1_000, 3.14d …)
    + '|([A-Za-z_$][\\w$]*)',               // identifier
    'g');

  /**
   * Writes one line of source into a cell, coloured.
   *
   * Every fragment is added as a text node or as a span whose content is set with
   * textContent, never innerHTML, traced source is untrusted input, and the whole report
   * is built on the rule that it can never become markup. Highlighting is presentational
   * only; if the scanner fails to classify something it is emitted as plain text.
   */
  function paintCode(td, text) {
    if (!text) return;
    TOKEN.lastIndex = 0;
    var at = 0;
    var m;
    while ((m = TOKEN.exec(text)) !== null) {
      if (m.index > at) td.appendChild(document.createTextNode(text.slice(at, m.index)));
      var cls = null;
      if (m[1] || m[2]) cls = 'k-cmt';
      else if (m[3]) cls = 'k-str';
      else if (m[4]) cls = 'k-chr';
      else if (m[5]) cls = 'k-ann';
      else if (m[6]) cls = 'k-num';
      else if (m[7]) {
        // An identifier is a keyword, or a type by convention (Java capitalises them), or
        // just a name. Guessing beyond that would need a parser and would be wrong often.
        if (JAVA_KEYWORDS[m[7]]) cls = 'k-kw';
        else if (/^[A-Z]/.test(m[7])) cls = 'k-typ';
      }
      if (cls) td.appendChild(el('span', cls, m[0]));
      else td.appendChild(document.createTextNode(m[0]));
      at = m.index + m[0].length;
      if (m[0].length === 0) { TOKEN.lastIndex++; }   // never spin on a zero-width match
    }
    if (at < text.length) td.appendChild(document.createTextNode(text.slice(at)));
  }

  // ------------------------------------------------------------------- sql ---

  var SQL_KEYWORDS = {};
  ('select from where group by having order asc desc limit offset fetch next rows only '
    + 'inner left right full cross outer join on using natural union all except intersect '
    + 'insert into values update set delete returning with recursive as distinct top '
    + 'and or not in exists between like ilike is null true false case when then else end '
    + 'cast create table view index primary key foreign references default constraint '
    + 'add alter drop column unique check cascade grant revoke begin commit rollback '
    + 'for share nowait lateral over partition window filter within grouping rollup cube '
    + 'first last nulls any some row rownum dual').split(' ')
    .forEach(function (k) { SQL_KEYWORDS[k] = true; });

  /* Clauses that start a new line, with how far they are indented relative to the
     statement. AND/OR/ON hang under the clause they qualify rather than sitting level
     with it, which is what makes a long WHERE readable at a glance. */
  var SQL_BREAK = {
    select: 0, from: 0, where: 0, group: 0, having: 0, order: 0, limit: 0, offset: 0,
    fetch: 0, union: 0, except: 0, intersect: 0, insert: 0, update: 0, 'delete': 0,
    values: 0, set: 0, returning: 0, 'with': 0, window: 0,
    join: 0, inner: 0, left: 0, right: 0, full: 0, cross: 0, natural: 0,
    on: 1, and: 1, or: 1
  };
  /* A JOIN preceded by its qualifier stays on that qualifier's line: "LEFT JOIN", not
     "LEFT" then "JOIN". */
  var SQL_JOIN_PREFIX = { inner: 1, left: 1, right: 1, full: 1, cross: 1, outer: 1, natural: 1 };

  var SQL_TOKEN = new RegExp(
    '(--[^\\n]*)'                          // line comment
    + '|(/\\*(?:[^*]|\\*(?!/))*(?:\\*/)?)' // block comment
    + "|('(?:''|[^'])*'?)"                 // string, '' being the escaped quote
    + '|("(?:""|[^"])*"?|`[^`]*`?)'        // quoted identifier
    + '|(\\?)'                             // bound parameter placeholder
    + '|(\\b\\d+(?:\\.\\d+)?\\b)'          // number
    + '|([A-Za-z_][\\w$]*)'                // identifier or keyword
    + '|(\\s+)'                            // whitespace, collapsed on output
    + '|([(),;]|[-+*/<>=!|]+)',            // punctuation and operators
    'g');

  /** Splits a statement into typed tokens. Formatting works on these, never on raw text,
      so a clause keyword inside a string literal can never trigger a line break. */
  function tokenizeSql(text) {
    SQL_TOKEN.lastIndex = 0;
    var out = [];
    var m;
    var at = 0;
    while ((m = SQL_TOKEN.exec(text)) !== null) {
      if (m.index > at) out.push({ t: 'x', v: text.slice(at, m.index) });
      if (m[1] || m[2]) out.push({ t: 'cmt', v: m[0] });
      else if (m[3]) out.push({ t: 'str', v: m[0] });
      else if (m[4]) out.push({ t: 'id', v: m[0] });
      else if (m[5]) out.push({ t: 'ph', v: m[0] });
      else if (m[6]) out.push({ t: 'num', v: m[0] });
      else if (m[7]) {
        out.push({ t: SQL_KEYWORDS[m[0].toLowerCase()] ? 'kw' : 'id', v: m[0],
          k: m[0].toLowerCase() });
      } else if (m[8]) out.push({ t: 'ws', v: ' ' });
      else out.push({ t: 'op', v: m[0] });
      at = m.index + m[0].length;
      if (m[0].length === 0) SQL_TOKEN.lastIndex++;
    }
    if (at < text.length) out.push({ t: 'x', v: text.slice(at) });
    return out;
  }

  /**
   * Groups tokens into indented lines.
   *
   * <p>Deliberately not a full SQL formatter. Column lists are left to wrap rather than
   * exploded one per line, because a generated select over forty columns would otherwise
   * bury the call tree it sits in. Clauses get the line breaks; the rest wraps.
   */
  function formatSqlLines(tokens) {
    var lines = [];
    var line = { indent: 0, toks: [] };
    var paren = 0;
    var betweenOpen = false;   // the AND of "BETWEEN x AND y" is not a new clause
    var prevKw = null;

    function flush() {
      if (line.toks.length) lines.push(line);
      line = { indent: 0, toks: [] };
    }

    tokens.forEach(function (tok) {
      if (tok.t === 'ws') {
        if (line.toks.length) line.toks.push(tok);
        return;
      }
      if (tok.t === 'op') {
        if (tok.v === '(') paren++;
        else if (tok.v === ')') paren = Math.max(0, paren - 1);
      }
      if (tok.t === 'kw') {
        if (tok.k === 'between') betweenOpen = true;
        var breaks = SQL_BREAK[tok.k] !== undefined;
        if (tok.k === 'and' && betweenOpen) { betweenOpen = false; breaks = false; }
        if (tok.k === 'join' && prevKw && SQL_JOIN_PREFIX[prevKw]) breaks = false;
        if (tok.k === 'by' || tok.k === 'into' || tok.k === 'all') breaks = false;
        if (breaks && line.toks.length) {
          flush();
          line.indent = paren + SQL_BREAK[tok.k];
        } else if (breaks) {
          line.indent = paren + SQL_BREAK[tok.k];
        }
        prevKw = tok.k;
      } else if (tok.t !== 'ws') {
        prevKw = null;
      }
      line.toks.push(tok);
    });
    flush();
    return lines;
  }

  var SQL_CLASS = { kw: 'k-kw', str: 'k-str', num: 'k-num', cmt: 'k-cmt', ph: 'k-ann' };

  /**
   * Splits a driver's provenance comment off the front of a statement.
   *
   * <p>Datasource wrappers and ORM comment hooks stamp the origin onto a query as a line
   * comment: {@code -- FooServiceImpl L27 -- BarController L2062 select ...}, all on one
   * line. A {@code --} comment runs to the end of its line, so tokenising that verbatim
   * turned the entire statement into a single comment token and the whole query rendered
   * grey and italic. The colouring switched itself off precisely on the queries carrying
   * the most context, which is the wrong way round.
   *
   * <p>The split is made at the first place a statement keyword starts, so the note is
   * shown as a note and the SQL behind it is tokenised as SQL. A statement with no such
   * prefix, and a comment that genuinely occupies its own line, both come back untouched.
   */
  var SQL_LEAD = new RegExp(
    '^\\s*--[^\\n]*?(?=\\s+(?:select|insert|update|delete|merge|with|call|replace'
    + '|create|alter|drop|truncate|upsert|set|show|explain)\\b)', 'i');

  function splitSqlLead(text) {
    var s = String(text == null ? '' : text);
    var m = SQL_LEAD.exec(s);
    if (!m || !m[0].trim()) return { lead: null, sql: s };
    return { lead: m[0].trim(), sql: s.slice(m[0].length).replace(/^\s+/, '') };
  }

  /**
   * Writes a formatted, coloured statement into a container.
   *
   * <p>Every fragment is a text node or a span set with textContent, exactly as
   * {@code paintCode} does: a traced query is untrusted input and can never become markup.
   * Placeholders are coloured distinctly because "the value is never captured" is the
   * report's central claim about SQL, and a visible {@code ?} is the evidence.
   */
  function paintSql(container, text) {
    if (!text) return;
    var split = splitSqlLead(text);
    if (split.lead) {
      container.appendChild(el('span', 'k-cmt', split.lead));
      container.appendChild(document.createTextNode('\n'));
    }
    var lines = formatSqlLines(tokenizeSql(split.sql));
    lines.forEach(function (ln, i) {
      if (i > 0) container.appendChild(document.createTextNode('\n'));
      if (ln.indent > 0) {
        container.appendChild(document.createTextNode(
          new Array(ln.indent * 2 + 1).join(' ')));
      }
      // Leading whitespace on a wrapped clause is noise; trailing is invisible.
      var toks = ln.toks.slice();
      while (toks.length && toks[0].t === 'ws') toks.shift();
      while (toks.length && toks[toks.length - 1].t === 'ws') toks.pop();
      toks.forEach(function (tok) {
        var cls = SQL_CLASS[tok.t];
        if (tok.t === 'ph') {
          var ph = el('span', cls, tok.v);
          ph.title = 'a bound parameter; its value is never recorded';
          container.appendChild(ph);
        } else if (cls) {
          container.appendChild(el('span', cls, tok.v));
        } else {
          container.appendChild(document.createTextNode(tok.v));
        }
      });
    });
  }

  /* Percent-encode for a query value but keep / and : readable - JetBrains Toolbox
     accepts them raw and some builds do not decode them. Everything that could
     break out of the query (& # ? % space, non-ASCII) is still encoded. */
  function encPath(p) {
    return encodeURIComponent(p).replace(/%2F/g, '/').replace(/%3A/g, ':');
  }

  function ideUrl(path, line) {
    if (!path || !data.projectName) return null;
    return 'jetbrains://idea/navigate/reference?project='
      + encodeURIComponent(data.projectName)
      + '&path=' + encPath(path + ':' + line);
  }

  /** A line-number cell that opens the IDE when the source path is known. */
  function lineCell(file, line) {
    var td = el('td', 'ln');
    // A synthetic frame — a bridge method, a lambda body the compiler moved — carries no
    // real source line and arrives as -1. Printing it as a line number invited a click on
    // a jetbrains:// link that can only fail, so it is shown as "no line" instead.
    if (line == null || line < 0) {
      td.textContent = '—';
      td.title = 'no source line was recorded for this entry';
      return td;
    }
    var url = file ? ideUrl(file.path, line) : null;
    if (url) {
      var a = el('a', null, String(line));
      a.href = url;
      a.title = 'Open ' + (file.sourceFileName || '') + ':' + line
        + ' in the IDE (needs JetBrains Toolbox)';
      td.appendChild(a);
    } else {
      td.textContent = line;
    }
    return td;
  }

  function copyText(text, done) {
    if (navigator.clipboard && navigator.clipboard.writeText) {
      navigator.clipboard.writeText(text).then(function () { done(true); }, function () { done(false); });
      return;
    }
    var ta = el('textarea');
    ta.value = text;
    ta.style.position = 'fixed';
    ta.style.opacity = '0';
    document.body.appendChild(ta);
    ta.select();
    var ok = false;
    try { ok = document.execCommand('copy'); } catch (e) { ok = false; }
    document.body.removeChild(ta);
    done(ok);
  }

  function flash(btn, msg) {
    var old = btn.textContent;
    btn.textContent = msg;
    setTimeout(function () { btn.textContent = old; }, 1200);
  }

  /**
   * The small copy affordance that rides on a row.
   *
   * <p>Reading a trace usually ends in pasting one frame into a ticket or a chat, and the
   * alternative was selecting across a table whose columns are separate cells. Built per
   * rendered row rather than per recorded row, which is affordable now that only a
   * screenful is ever in the document.
   */
  function copyButton(textOf, title) {
    var b = el('button', 'rowcopy', '⧉');
    b.type = 'button';
    b.title = title || 'Copy this step as text';
    b.setAttribute('aria-label', title || 'Copy this step as text');
    b.addEventListener('click', function (ev) {
      ev.stopPropagation();          // the row itself collapses on click
      copyText(textOf(), function (ok) { flash(b, ok ? '✓' : '✕'); });
    });
    return b;
  }

  /** One frame as a line of text: what it was, how long it took, and who called it. */
  function frameAsText(node) {
    if (node.sql != null) {
      return '#' + (node.seq + 1) + '  SQL  ' + fmt(node.totalMicros) + '\n' + node.sql;
    }
    var parent = nodeBySeq[node.parentSeq];
    var parentFile = parent ? fileByClass[parent.className] : null;
    var site = node.callSiteLine != null
      ? '  (called at ' + ((parentFile && parentFile.sourceFileName) || simpleName(parent && parent.className) || '?')
        + ':' + node.callSiteLine + ')'
      : '';
    return '#' + (node.seq + 1) + '  ' + (node.className || '?') + '.'
      + methodLabel(node.methodName, node.className) + '()  '
      + (node.totalMicros != null ? fmt(node.totalMicros) : '') + site;
  }

  // Written to the left span, not to #meta itself: the count shares that row and a
  // textContent assignment on the parent would delete it.
  var metaTextEl = byId('metaText');
  metaTextEl.textContent =
    'Trace point: ' + shortTarget(data.target) + '   |   Started ' + inDate(data.startedAtIso);
  // The row truncates to stay one line and the package was dropped from the label above, so
  // the tooltip is the only place either is still recoverable.
  metaTextEl.title = 'Trace point: ' + (data.target || '—')
    + '\nStarted ' + inDate(data.startedAtIso)
    + (data.startedAtIso ? '\nRecorded at ' + data.startedAtIso : '');

  // ------------------------------------------------------------- file index ---

  var fileByClass = {};
  var linesByMethod = {};    // "class#method" -> line models, ascending by line
  files.forEach(function (f) {
    fileByClass[f.fqClassName] = f;
    (f.lines || []).forEach(function (l) {
      if (l.methodName == null) return;
      var key = f.fqClassName + '#' + l.methodName;
      (linesByMethod[key] = linesByMethod[key] || []).push(l);
    });
  });
  Object.keys(linesByMethod).forEach(function (k) {
    linesByMethod[k].sort(function (a, b) { return a.line - b.line; });
  });

  // Genuine "API order": the step at which each class was first entered. The payload's
  // own file order comes from iterating a HashSet of method ids, which preserves neither
  // execution order nor id order, so it can never be used for this.
  var firstSeqByClass = {};
  calls.forEach(function (c) {
    if (c.className != null && firstSeqByClass[c.className] === undefined) {
      firstSeqByClass[c.className] = c.seq;
    }
  });

  // -------------------------------------------------------- files view build ---

  var root = byId('root');
  var fileList = byId('fileList');
  var entries = [];
  /** Class name -> its entry, so the Tree view can honour the same file selection. */
  var entryByClass = {};

  /**
   * Whether a class is ticked in the Files dropdown.
   *
   * Unknown classes count as selected: a call can be recorded for a class whose source was
   * never resolved, and silently dropping those frames would hide real calls.
   *
   * <p>The tick is the whole answer, in both views. It used to mean one thing in the Files
   * view and another in the Tree, where an untouched box counted as ticked however it was
   * drawn: the picker showed a class unticked while its frames were plainly on screen, and
   * the same click produced different results depending on history the reader could not
   * see. Excluded and generated files now open <i>collapsed</i> instead of unticked, which
   * gets the Files view to the same clean landing without lying about what is filtered.
   */
  function classSelected(name) {
    var e = entryByClass[name];
    return e ? e.checkbox.checked : true;
  }
  var ordered = [];
  var totalLines = 0;
  var generatedCount = 0;

  files.forEach(function (f, idx) {
    var box = el('section', 'file');
    var displayName = f.fqClassName || f.sourceFileName || ('file ' + (idx + 1));

    var h = el('h2');
    var caret = el('span', 'caret', '▾ ');
    h.appendChild(caret);
    h.appendChild(el('span', 'fname', displayName));
    if (f.sourceFileName) h.appendChild(el('span', 'src', '(' + f.sourceFileName + ')'));
    var stats = el('div', 'stats');
    h.appendChild(stats);
    box.appendChild(h);

    var scroll = el('div', 'scroll');
    var rows = [];
    var sections = [];
    var current = null;
    var totalWeight = 0;   // largest inclusive method time in this file
    var selfWeight = 0;    // time spent executing this file's own lines
    var red = 0;
    var partial = 0;

    // Records only. Not one element is created here: a 37 MB export is a few hundred
    // thousand lines, and syntax-colouring every one of them into a detached table cost
    // the whole load whether or not the reader ever opened the Files view. The DOM for a
    // file is built by buildFileTable() when the file first comes near the viewport.
    (f.lines || []).forEach(function (l) {
      var st = l.status || 'NONE';
      if (counts[st] !== undefined) counts[st]++;
      if (st === 'NONE') red++;
      if (st === 'PARTIAL') partial++;
      totalLines++;
      if (l.timeMicros != null) selfWeight += l.timeMicros;
      if (l.methodTotalMicros != null && l.methodTotalMicros > totalWeight) {
        totalWeight = l.methodTotalMicros;
      }

      var mname = l.methodName || null;
      var startsSection = null;
      if (mname !== null && (current === null || current.name !== mname)) {
        current = { name: mname, rows: [], declLine: l.line, total: null, collapsed: false,
          tr: null, caret: null, meta: null, vis: true };
        sections.push(current);
        startsSection = current;
      } else if (mname === null) {
        current = null;
      }
      if (current && l.methodStart) current.declLine = l.line;
      if (current && l.methodTotalMicros != null) current.total = l.methodTotalMicros;

      var codeText = (l.code != null ? l.code : '');
      var rec = { model: l, tr: null, status: st, text: codeText.toLowerCase(), line: l.line,
        section: current, startsSection: startsSection, entry: null, vis: true };
      rows.push(rec);
      if (current) current.rows.push(rec);
    });

    box.appendChild(scroll);
    root.appendChild(box);

    stats.appendChild(el('span', 'pill', rows.length + (rows.length === 1 ? ' line' : ' lines')));
    if (red) stats.appendChild(el('span', 'pill red', red + ' red'));
    if (partial) stats.appendChild(el('span', 'pill amber', partial + ' partial'));
    if (selfWeight) {
      var sp = el('span', 'pill', fmt(selfWeight));
      sp.title = 'time executing this file\'s own lines (self)';
      stats.appendChild(sp);
    }

    if (f.path) {
      var openUrl = ideUrl(f.path, (f.lines && f.lines.length) ? f.lines[0].line : 1);
      if (openUrl) {
        var openLink = el('a', 'act', 'Open');
        openLink.href = openUrl;
        openLink.title = 'Open this file in the IDE (needs JetBrains Toolbox)';
        openLink.addEventListener('click', function (ev) { ev.stopPropagation(); });
        stats.appendChild(openLink);
      }
      var copyBtn = el('button', 'act', 'Copy path');
      copyBtn.type = 'button';
      copyBtn.title = 'Copy the source path (works without Toolbox)';
      copyBtn.addEventListener('click', function (ev) {
        ev.stopPropagation();
        copyText(f.absPath || f.path, function (ok) { flash(copyBtn, ok ? 'Copied' : 'Failed'); });
      });
      stats.appendChild(copyBtn);
    }

    var allBlank = rows.length > 0 && rows.every(function (r) { return r.text.trim() === ''; });
    var generated = isGeneratedName(f.fqClassName) || allBlank;
    if (generated) generatedCount++;

    var excluded = !!excludedClasses[f.fqClassName];

    // Ticked, always. Excluded and generated files still open out of the way, but they do
    // it by starting collapsed further down, so the tick keeps one meaning: what is
    // filtered out. See classSelected().
    var label = el('label');
    var cb = el('input');
    cb.type = 'checkbox';
    cb.checked = true;
    label.appendChild(cb);
    label.appendChild(el('span', 'mtxt', displayName));
    if (generated) label.appendChild(el('span', 'gen', '(generated)'));
    else if (excluded) label.appendChild(el('span', 'gen', '(excluded)'));
    cb.addEventListener('change', function () { entry.userToggled = true; applyFilters(); });
    fileList.appendChild(label);

    var firstSeq = firstSeqByClass[f.fqClassName];
    var entry = {
      file: f,
      box: box, h: h, rows: rows, sections: sections, checkbox: cb, label: label,
      totalWeight: totalWeight, selfWeight: selfWeight, red: red, partial: partial,
      // Fall back to payload position when there is no call tree to order by.
      order: firstSeq === undefined ? 1e9 + idx : firstSeq,
      payloadIdx: idx, caret: caret, generated: generated, excluded: excluded,
      table: null, scroll: scroll, attached: false,
      name: displayName, lower: displayName.toLowerCase(),
      path: f.absPath || f.path || null
    };
    entryByClass[f.fqClassName] = entry;
    rows.forEach(function (r) { r.entry = entry; });
    entries.push(entry);
  });

  ordered = entries.slice();

  /** The clickable header for one method section, built with the rows it introduces. */
  function sectionRow(section, fqClassName) {
    var mtr = el('tr', 'mrow');
    var mtd = el('td');
    mtd.colSpan = 3;
    var head = el('div', 'mhead');
    var mcaret = el('span', 'mcaret', section.collapsed ? '▸' : '▾');
    head.appendChild(mcaret);
    head.appendChild(el('span', 'mname', methodLabel(section.name, fqClassName)));
    var kind = methodKind(section.name);
    if (kind) head.appendChild(el('span', 'mkind', kind));
    var mmeta = el('span', 'mmeta',
      (section.total != null ? fmtPct(section.total) + ' · ' : '')
      + 'line ' + section.declLine + ' · ' + section.rows.length
      + (section.rows.length === 1 ? ' line' : ' lines'));
    head.appendChild(mmeta);
    mtd.appendChild(head);
    mtr.appendChild(mtd);

    section.tr = mtr;
    section.caret = mcaret;
    section.meta = mmeta;
    mtr.addEventListener('click', function () {
      section.collapsed = !section.collapsed;
      mcaret.textContent = section.collapsed ? '▸' : '▾';
      applyFilters();
    });
    return mtr;
  }

  /** One source line: the timing cell, the line number, and the coloured code. */
  function lineRow(rec, f) {
    var l = rec.model;
    var tr = el('tr', rec.status);
    var tm = el('td', 'time');
    var micros = (l.methodTotalMicros != null) ? l.methodTotalMicros : l.timeMicros;
    if (micros != null) {
      tm.textContent = (l.methodTotalMicros != null ? '▸ ' : '') + fmt(micros);
      // The share goes on the title rather than into the cell: this column is one narrow
      // gutter beside every line of source, and doubling its width to carry a second
      // number would take the width off the code it exists to annotate.
      var lp = pct(micros);
      tm.title = (l.methodTotalMicros != null ? 'method total' : 'line self time')
        + (lp ? ' · ' + lp + ' of the run' : '');
    }
    var code = el('td', 'code');
    paintCode(code, l.code != null ? l.code : '');
    if (l.branchesTotal) {
      code.appendChild(el('span', 'br',
        '   (' + l.branchesCovered + '/' + l.branchesTotal + ' branches)'));
    }
    tr.appendChild(tm);
    tr.appendChild(lineCell(f, l.line));
    tr.appendChild(code);
    rec.tr = tr;
    return tr;
  }

  /** Puts whatever the filters last decided onto the rows of a file just built. */
  function applyEntryVisibility(e) {
    e.sections.forEach(function (s) { if (s.tr) s.tr.style.display = s.vis ? '' : 'none'; });
    e.rows.forEach(function (r) { if (r.tr) r.tr.style.display = r.vis ? '' : 'none'; });
  }

  /**
   * Puts a file's rows into the document the first time they are wanted.
   *
   * <p>Everything the filters and counts read lives on the row records, which are built up
   * front and are cheap. The elements are not: colouring a line of Java is the single most
   * expensive thing this report does per line, so it waits until the file is near the
   * viewport, and a file nobody scrolls to costs nothing at all.
   */
  function attachEntry(e) {
    if (e.attached) return;
    e.attached = true;
    var table = el('table');
    for (var i = 0; i < e.rows.length; i++) {
      var r = e.rows[i];
      if (r.startsSection) table.appendChild(sectionRow(r.startsSection, e.file.fqClassName));
      table.appendChild(lineRow(r, e.file));
    }
    e.table = table;
    e.scroll.appendChild(table);
    applyEntryVisibility(e);
  }

  /**
   * Attaches every file whose header is within reach of the viewport.
   *
   * <p>Driven from the view switch, the filters and scrolling rather than from an
   * IntersectionObserver: the observer reports asynchronously, and a file that has not been
   * told to attach yet shows an empty section, so what the reader sees would depend on
   * whether a callback had run. This runs before the frame is shown, every time.
   *
   * <p>Rects are read for all candidates before any of them is attached. Attaching moves
   * everything below it, so measuring and mutating in one pass would force a layout per
   * file, which is the cost this exists to avoid.
   */
  function attachVisibleFiles() {
    if (view !== 'files') return;
    var reach = (window.innerHeight || 800) + 800;
    var pending = [];
    for (var i = 0; i < entries.length; i++) {
      var e = entries[i];
      if (e.attached || e.box.hidden) continue;
      var r = e.box.getBoundingClientRect();
      // Attaching pushes later files down, so anything already past the reach stays for a
      // later pass; scrolling towards it is what brings it in.
      if (r.top < reach) pending.push(e);
      else break;
    }
    pending.forEach(attachEntry);
    // Each file attached pushes the next ones down, so one pass only reaches the first
    // screenful. Asking for another frame lets it settle without blocking this one.
    if (pending.length) queueAttach();
  }

  var attachQueued = false;
  function queueAttach() {
    if (attachQueued) return;
    attachQueued = true;
    requestAnimationFrame(function () { attachQueued = false; attachVisibleFiles(); });
  }
  window.addEventListener('scroll', queueAttach, { passive: true });
  window.addEventListener('resize', queueAttach);

  // --------------------------------------------------------- tree view build ---

  var treeTable = byId('treeTable');
  var treeView = byId('treeView');
  var filesView = byId('filesView');
  var treeEmpty = byId('treeEmpty');
  var childrenBySeq = {};
  var nodeBySeq = {};
  var roots = [];
  var treeRows = [];
  var collapsedSeqs = {};
  var expandedFolds = {};
  var hotSeqs = {};
  var hotOn = false;
  var treeTruncated = false;
  var treeAvailable = calls.length > 0;
  // Queries are counted apart from invocations everywhere the report reports a total: a
  // SQL node is in calls[] but it is not a call, and mixing the two made "Showing 2285 of
  // 3000 call(s)" a number that could never add up against the rest of the row.
  var sqlTotal = 0;
  calls.forEach(function (c) { if (c.sql != null) sqlTotal++; });
  var frameTotal = calls.length - sqlTotal;
  var detailFull = false;        // false = Essential, excluded types folded away
  var expandedRollups = {};
  var expandedGroups = {};       // "parentSeq#key" -> repeats put back where they ran
  var treeGroup = prefs.groupRepeats;   // fold repeats of one call under their first occurrence
  var revealPath = null;         // seq -> true for a jumped-to step and its ancestors
  var hiddenFrameCount = 0;

  calls.forEach(function (c) {
    nodeBySeq[c.seq] = c;
    if (c.parentSeq < 0) {
      roots.push(c);
    } else {
      // calls[] is already in execution order, so pushing in order keeps siblings in it.
      (childrenBySeq[c.parentSeq] = childrenBySeq[c.parentSeq] || []).push(c);
    }
  });

  // The widest root is the same measurement as the reported wall time, rounded elsewhere.
  // Taking the larger of the two is what keeps a frame from reading 101% of its own run.
  roots.forEach(function (c) {
    if ((c.totalMicros || 0) > runMicrosTotal) runMicrosTotal = c.totalMicros || 0;
  });

  /**
   * How much of the run no recorded call accounts for, as a fraction of it.
   *
   * <p>Reported only on a capped recording, and that restraint is the whole point. The
   * number itself is just the entry point's own time — on a complete trace that is an
   * ordinary reading, a method that does its own work — but once the agent stopped
   * recording partway, the untraced remainder of the run has nowhere else to land, so the
   * same figure becomes the size of the hole.
   *
   * <p>Worth saying out loud because "capped" on its own reads like a footnote. A run that
   * kept 16% of itself and a run that kept 99% both say "capped", and only one of them is
   * still worth drawing conclusions from.
   */
  var unrecordedShare = 0;
  if (data.callsTruncated && roots.length) {
    var deepest = roots[0];
    roots.forEach(function (c) {
      if ((c.totalMicros || 0) > (deepest.totalMicros || 0)) deepest = c;
    });
    var rootTotal = deepest.totalMicros || 0;
    var kidsTotal = 0;
    (childrenBySeq[deepest.seq] || []).forEach(function (k) { kidsTotal += k.totalMicros || 0; });
    if (rootTotal > 0) unrecordedShare = Math.max(0, (rootTotal - kidsTotal) / rootTotal);
  }

  /** The unrecorded share as a rounded percentage, or '' when there is nothing to report. */
  function unrecordedPct() {
    if (unrecordedShare <= 0.005) return '';
    return (unrecordedShare >= 0.1
      ? Math.round(unrecordedShare * 100)
      : (unrecordedShare * 100).toFixed(1)) + '%';
  }

  /**
   * Whether a call, and everything it went on to do, is excluded.
   *
   * A frame is only foldable when its ENTIRE subtree is foldable. That rule is what makes
   * the feature safe: a getter that turns out to trigger a lazy load has a repository call
   * underneath it, so it stays on screen instead of hiding the one thing worth seeing.
   *
   * Computed once per build over calls[] in reverse. calls[] is emitted in execution order
   * and a child is always entered after its parent, so a single reverse pass visits every
   * child before its parent, no recursion, and no stack depth limit on a deep trace.
   */
  var foldableSeq = {};
  function computeFoldable() {
    foldableSeq = {};
    for (var i = calls.length - 1; i >= 0; i--) {
      var c = calls[i];
      var ok = !!excludedClasses[c.className];
      if (ok) {
        var kids = childrenBySeq[c.seq] || [];
        for (var k = 0; k < kids.length; k++) {
          if (!foldableSeq[kids[k].seq]) { ok = false; break; }
        }
      }
      foldableSeq[c.seq] = ok;
    }
  }
  computeFoldable();

  /** Total number of invocations in these subtrees, what a roll-up row stands in for. */
  function countSubtree(nodes) {
    var n = 0;
    var stack = nodes.slice();
    while (stack.length) {
      var cur = stack.pop();
      n++;
      var kids = childrenBySeq[cur.seq] || [];
      for (var i = 0; i < kids.length; i++) stack.push(kids[i]);
    }
    return n;
  }

  function computeHotPath() {
    hotSeqs = {};
    roots.forEach(function (r) {
      var cur = r;
      while (cur) {
        hotSeqs[cur.seq] = true;
        var kids = childrenBySeq[cur.seq] || [];
        var best = null;
        kids.forEach(function (k) {
          if (!best || (k.totalMicros || 0) > (best.totalMicros || 0)) best = k;
        });
        cur = best;
      }
    });
  }
  computeHotPath();

  /**
   * A row for one executed query.
   *
   * Rendered distinctly from a method frame because it is not a frame: it has no source of
   * its own to step into, and the thing worth reading is the statement text. The SQL is
   * written with textContent like every other traced value, so a query containing markup
   * cannot become markup.
   */
  function sqlRow(node, depth, parentRow) {
    // Deferred like every other row. This one used to build itself eagerly, so every
    // refreshTree() re-tokenised and re-coloured every statement in the run, virtualised
    // or not, and a repository-heavy trace paid that on each collapse and each keystroke.
    var r = {
      // Still a 'line' so it counts and propagates like one, but marked so the SQL
      // toggle can hide queries without touching the code lines around them.
      kind: 'line', sql: true, depth: depth, tr: null, parent: parentRow, status: 'FULL',
      text: (node.sql || '').toLowerCase(), vis: true, node: node
    };
    r.build = function () {
    var tr = el('tr', 'sqlrow');
    var td = el('td', 'tframe');
    td.colSpan = 3;
    td.style.setProperty('--depth', depth);

    // The statement is a block under the head rather than another item in the flex row:
    // once it is formatted onto several lines it needs the full width, and a multi-line
    // child in a baseline-aligned row drags the step number and timing out of place.
    var head = el('div', 'fhead');
    head.appendChild(el('span', 'step', String(node.seq + 1)));
    head.appendChild(el('span', 'sqltag', 'SQL'));
    if (node.totalMicros != null) {
      var time = el('span', 'ftime', fmt(node.totalMicros));
      time.title = 'time spent executing this query';
      var qp = pctBadge(node.totalMicros);
      if (qp) time.appendChild(qp);
      head.appendChild(time);
    }
    td.appendChild(head);

    // Clamped to three lines up front. A generated select runs to a dozen clauses, and a
    // trace with thirty queries would otherwise be mostly SQL. Whether the clamp actually
    // hides anything depends on the rendered width, so the control is revealed after
    // layout by syncSqlClamps() rather than guessed at from the text.
    var code = el('pre', 'sqltext clamped');
    paintSql(code, node.sql);
    td.appendChild(code);

    head.appendChild(copyButton(function () { return frameAsText(node); }));

    var more = el('button', 'sqlmore', 'Show full query');
    more.type = 'button';
    more.hidden = true;
    more.addEventListener('click', function () {
      var clamped = code.classList.toggle('clamped');
      more.textContent = clamped ? 'Show full query' : 'Show less';
    });
    td.appendChild(more);
    tr.appendChild(td);
    return tr;
    };
    return r;
  }

  function frameRow(node, depth, parentRow) {
    var file = fileByClass[node.className];
    var kids = childrenBySeq[node.seq] || [];
    var lines = linesByMethod[node.className + '#' + node.methodName] || [];
    var foldable = kids.length > 0 || lines.length > 0;

    // The signature is kept lower-cased on the record so the code filter can match a frame
    // by class or method name, not only by the source text underneath it.
    var r = { kind: 'frame', depth: depth, node: node, tr: null, parent: parentRow, vis: true,
      text: ((node.className || '') + '.' + (node.methodName || '')).toLowerCase() };
    r.build = function () {
    var tr = el('tr', 'frame' + (hotOn && hotSeqs[node.seq] ? ' hot' : ''));
    var td = el('td', 'tframe');
    td.colSpan = 3;
    td.style.setProperty('--depth', depth);

    var head = el('div', 'fhead');
    head.appendChild(el('span', 'step', String(node.seq + 1)));
    var caret = el('span', 'fcaret', foldable ? (collapsedSeqs[node.seq] ? '▸' : '▾') : ' ');
    head.appendChild(caret);
    head.appendChild(el('span', 'ffile', (file && file.sourceFileName) || simpleName(node.className) || '?'));
    head.appendChild(el('span', 'fsep', '·'));
    head.appendChild(el('span', 'fmethod', methodLabel(node.methodName, node.className) + '()'));
    var kind = methodKind(node.methodName);
    if (kind) head.appendChild(el('span', 'fkind', kind));

    // "called at :N" points at the CALLER's line, so it links into the caller's file.
    if (node.callSiteLine != null) {
      var site = el('span', 'fsite');
      var parentNode = nodeBySeq[node.parentSeq];
      var parentFile = parentNode ? fileByClass[parentNode.className] : null;
      var url = parentFile ? ideUrl(parentFile.path, node.callSiteLine) : null;
      if (url) {
        var a = el('a', null, 'called at :' + node.callSiteLine);
        a.href = url;
        a.title = 'Open the calling line in the IDE';
        a.addEventListener('click', function (ev) { ev.stopPropagation(); });
        site.appendChild(a);
      } else {
        site.textContent = 'called at :' + node.callSiteLine;
      }
      head.appendChild(site);
    }

    var time = el('span', 'ftime', node.totalMicros != null ? fmt(node.totalMicros) : '');
    time.title = 'total (inclusive) time of this call';
    // The share rides inside the time element rather than beside it, so the two never wrap
    // apart and the number keeps its own meaning: "48 ms, which is a third of the request".
    var fp = pctBadge(node.totalMicros);
    if (fp) time.appendChild(fp);
    head.appendChild(time);
    head.appendChild(copyButton(function () { return frameAsText(node); }));

    td.appendChild(head);
    tr.appendChild(td);

    if (foldable) {
      tr.addEventListener('click', function () {
        if (collapsedSeqs[node.seq]) delete collapsedSeqs[node.seq];
        else collapsedSeqs[node.seq] = true;
        refreshTree();
      });
    }
    return tr;
    };
    return r;
  }

  function codeRow(lineModel, file, depth, parentRow) {
    var st = lineModel.status || 'NONE';
    var text0 = lineModel.code != null ? lineModel.code : '';
    // Everything the filters read is computed now; only the row's DOM waits until the row
    // is actually near the viewport.
    var r = {
      kind: 'line', depth: depth, tr: null, parent: parentRow, status: st,
      text: text0.toLowerCase(), line: lineModel.line, file: file, vis: true
    };
    r.build = function () {
    var tr = el('tr', st);
    var tm = el('td', 'time');
    if (lineModel.timeMicros != null) {
      tm.textContent = fmt(lineModel.timeMicros);
      // The share on the title, the way the Files view already does it: the column is one
      // narrow gutter beside every line of source, and a second number in it would take
      // the width off the code it exists to annotate. The Tree used to say nothing at all
      // here, so the same line answered "what share of the run is this?" in one view and
      // not the other.
      var lp = pct(lineModel.timeMicros);
      tm.title = 'line self time' + (lp ? ' · ' + lp + ' of the run' : '');
    }
    var code = el('td', 'code tcode');
    code.style.setProperty('--depth', depth);
    var text = lineModel.code != null ? lineModel.code : '';
    paintCode(code, text);
    if (lineModel.branchesTotal) {
      code.appendChild(el('span', 'br',
        '   (' + lineModel.branchesCovered + '/' + lineModel.branchesTotal + ' branches)'));
    }
    tr.appendChild(tm);
    tr.appendChild(lineCell(file, lineModel.line));
    tr.appendChild(code);
    return tr;
    };
    return r;
  }

  function foldRow(nodes, depth, parentRow, foldId) {
    var first = nodes[0];
    var total = 0;
    nodes.forEach(function (n) { total += (n.totalMicros || 0); });
    // A folded run of identical queries is still SQL, so it hides with the rest.
    var r = { kind: 'fold', sql: first.sql != null, depth: depth, tr: null,
      parent: parentRow, vis: true };
    r.build = function () {
    var tr = el('tr', 'fold');
    var td = el('td', 'tframe');
    td.colSpan = 3;
    td.style.setProperty('--depth', depth);
    var head = el('div', 'foldhead');
    head.appendChild(el('span', 'fcaret', '⊕'));
    head.appendChild(el('span', 'fcount', '×' + nodes.length + ' more'));
    if (first.sql != null) {
      // A query has no method name, and a run of identical ones is the N+1 this fold
      // exists to make readable, so show the statement rather than an empty label.
      head.appendChild(el('span', 'sqltag', 'SQL'));
      head.appendChild(el('code', 'sqltext', first.sql));
      head.appendChild(el('span', null, ', ' + fmt(total) + ' total. Click to expand.'));
    } else {
      head.appendChild(el('span', null,
        methodLabel(first.methodName, first.className) + '()'
        + (first.callSiteLine != null ? ' from :' + first.callSiteLine : '')
        + ', ' + fmt(total) + ' total. Click to expand.'));
    }
    td.appendChild(head);
    tr.appendChild(td);
    tr.addEventListener('click', function () {
      expandedFolds[foldId] = true;
      refreshTree();
    });
    return tr;
    };
    return r;
  }

  /**
   * One row standing in for a run of folded calls into excluded types.
   *
   * Deliberately not a silent drop. The count, the classes involved and the total time
   * stay on screen at the call site, so time is still accounted for and nothing vanishes
   * without leaving a trace you can click.
   */
  function rollupRow(nodes, depth, parentRow) {
    var total = 0;
    // Inclusive time is summed over the top-level nodes only; their children are already
    // inside those totals and adding them again would invent time the run never spent.
    nodes.forEach(function (n) { total += (n.totalMicros || 0); });

    // The count and the class list, however, describe the WHOLE subtree: this row is the
    // only trace left of every frame underneath it. Counting just the run would report
    // "1 excluded call" where six invocations disappeared, and name none of the classes
    // the other five were in.
    var names = [];
    var seen = {};
    var hidden = 0;
    var queue = nodes.slice();
    for (var qi = 0; qi < queue.length; qi++) {
      var cur = queue[qi];
      hidden++;
      var s = simpleName(cur.className);
      if (s && !seen[s]) { seen[s] = true; names.push(s); }
      var kids = childrenBySeq[cur.seq] || [];
      for (var i = 0; i < kids.length; i++) queue.push(kids[i]);
    }
    var shownNames = names.slice(0, 3).join(', ') + (names.length > 3 ? ', +' + (names.length - 3) : '');

    var r = { kind: 'fold', rollup: true, depth: depth, tr: null, parent: parentRow, vis: true };
    r.build = function () {
    var tr = el('tr', 'fold rollup');
    var td = el('td', 'tframe');
    td.colSpan = 3;
    td.style.setProperty('--depth', depth);
    var head = el('div', 'foldhead');
    head.appendChild(el('span', 'fcaret', '⋯'));
    head.appendChild(el('span', 'fcount',
      hidden + (hidden === 1 ? ' excluded call' : ' excluded calls')));
    head.appendChild(el('span', null, shownNames + ', ' + fmt(total) + ' total. '
      + (excludedOmitted ? 'Source omitted at export.' : 'Click to expand.')));
    td.appendChild(head);
    tr.appendChild(td);
    if (!excludedOmitted) {
      tr.addEventListener('click', function () {
        // Every member is marked, not just the group: keying expansion by the group's
        // position would only release the first call, and the rest would immediately
        // re-roll into a fresh group behind it.
        nodes.forEach(function (n) { expandedRollups[n.seq] = true; });
        refreshTree();
      });
    } else {
      // Nothing to expand into; a row that looks clickable and does nothing is worse
      // than one that plainly is not.
      tr.style.cursor = 'default';
    }
    return tr;
    };
    return r;
  }

  /**
   * One summary row for the repeats of a call that were folded into their first occurrence.
   *
   * <p>Distinct from the {@code ×N more} run fold, which only ever catches repeats that
   * happen to be adjacent. A loop that alternates two repositories produces no adjacent
   * repeats at all and still issues the same call two hundred times, which is the shape
   * this exists to make visible.
   */
  function groupRow(rep, depth, parentRow) {
    var r = { kind: 'fold', group: true, depth: depth, tr: null, parent: parentRow, vis: true };
    r.build = function () {
      var tr = el('tr', 'fold grouped');
      var td = el('td', 'tframe');
      td.colSpan = 3;
      td.style.setProperty('--depth', depth);
      var head = el('div', 'foldhead');
      head.appendChild(el('span', 'fcaret', '⊕'));
      head.appendChild(el('span', 'fcount', '×' + (rep.n - 1) + ' more'));
      head.appendChild(el('span', null,
        ' of the same call, ' + fmt(rep.total) + ' total across all ' + rep.n
        + '. Click to put them back where they ran.'));
      td.appendChild(head);
      tr.appendChild(td);
      tr.addEventListener('click', function () {
        expandedGroups[rep.id] = true;
        refreshTree();
      });
      return tr;
    };
    return r;
  }

  /** Identity for "the same call, made again": the callee, not the call site. */
  function repeatKey(kid) {
    return kid.sql != null
      ? 'q:' + String(kid.sql).replace(/\s+/g, ' ').trim()
      : 'm:' + (kid.className || '') + '#' + (kid.methodName || '');
  }

  /** How often each call appears among one parent's children, and what it cost in total. */
  function countRepeats(node, kids) {
    var byKey = {};
    for (var i = 0; i < kids.length; i++) {
      var key = repeatKey(kids[i]);
      var rep = byKey[key];
      if (!rep) {
        rep = byKey[key] = { id: node.seq + '#' + key, n: 0, total: 0, first: i,
          hidden: 0, open: !!expandedGroups[node.seq + '#' + key] };
      }
      rep.n++;
      rep.total += kids[i].totalMicros || 0;
      if (i !== rep.first) rep.hidden += countSubtree([kids[i]]);
      // A step somebody asked to be shown is never folded away underneath them.
      if (onRevealPath(kids[i]) && i !== rep.first) rep.open = true;
    }
    return byKey;
  }

  /**
   * Whether a call is the step the reader asked to see, or an ancestor of it.
   *
   * <p>Findings and the Timeline both jump to a step, and that step is routinely inside
   * something the default view has folded away. Rather than have those views guess which
   * folds to open, the walk simply refuses to fold anything on the path to the target.
   */
  function onRevealPath(kid) {
    return revealPath !== null && revealPath[kid.seq] === true;
  }

  function anyOnRevealPath(kids, from, to) {
    if (revealPath === null) return false;
    for (var i = from; i < to; i++) {
      if (revealPath[kids[i].seq] === true) return true;
    }
    return false;
  }

  /**
   * Emits one invocation and everything under it.
   *
   * Children are walked in sequence (execution) order and never reordered, which is the
   * whole point of this view. The method's own lines are ascending by number, and each
   * line is emitted once the walk reaches the call made from it, which is what puts a
   * call inline at its call site.
   */
  function emitFrame(node, depth, parentRow, honourCollapse, timeOk) {
    if (treeRows.length >= MAX_TREE_ROWS) { treeTruncated = true; return; }

    // A query is a leaf: it has no children and no source lines of its own.
    if (node.sql) {
      treeRows.push(sqlRow(node, depth, parentRow));
      return;
    }

    var row = frameRow(node, depth, parentRow);
    treeRows.push(row);
    if (honourCollapse && collapsedSeqs[node.seq]) return;

    var file = fileByClass[node.className];
    var lines = linesByMethod[node.className + '#' + node.methodName] || [];
    var kids = (childrenBySeq[node.seq] || []).filter(function (k) {
      if (!timeOk(k.totalMicros || 0)) return false;
      // A query belongs to the frame that issued it, not to a file of its own, so it is
      // never hidden by the file picker, only by its caller disappearing.
      return k.sql != null || classSelected(k.className);
    });

    // With grouping on, every repeat of one call under this parent is counted up front so
    // the first occurrence can say how many followed it.
    var repeats = treeGroup ? countRepeats(node, kids) : null;

    var li = 0;
    var ki = 0;
    while (ki < kids.length) {
      var kid = kids[ki];
      // Flush the caller's own lines up to and including the line that made this call.
      while (li < lines.length
             && (kid.callSiteLine == null || lines[li].line <= kid.callSiteLine)) {
        treeRows.push(codeRow(lines[li++], file, depth + 1, row));
        if (treeRows.length >= MAX_TREE_ROWS) { treeTruncated = true; return; }
      }
      // Roll up a run of consecutive calls into excluded types. Checked before the
      // identical-run fold below because these runs are usually a mix of different
      // accessors (getId, getName, getPrice) that the identical-run rule would not catch.
      if (!detailFull && foldableSeq[kid.seq] && !expandedRollups[kid.seq]
          && !onRevealPath(kid)) {
        var exEnd = ki;
        while (exEnd < kids.length
               && foldableSeq[kids[exEnd].seq]
               && !expandedRollups[kids[exEnd].seq]
               && !onRevealPath(kids[exEnd])) {
          exEnd++;
        }
        var group = kids.slice(ki, exEnd);
        hiddenFrameCount += countSubtree(group);
        treeRows.push(rollupRow(group, depth + 1, row));
        if (treeRows.length >= MAX_TREE_ROWS) { treeTruncated = true; return; }
        ki = exEnd;
        continue;
      }

      // Grouped repeats. The first occurrence stays exactly where it ran, and the ones
      // after it are summarised on a row beneath it rather than scattered through the
      // parent; everything that is not a repeat keeps its position untouched.
      if (repeats) {
        var rep = repeats[repeatKey(kid)];
        if (rep && rep.n > 1 && !rep.open) {
          if (rep.first !== ki) { ki++; continue; }   // already accounted for above
          emitFrame(kid, depth + 1, row, honourCollapse, timeOk);
          if (treeRows.length >= MAX_TREE_ROWS) { treeTruncated = true; return; }
          hiddenFrameCount += rep.hidden;
          treeRows.push(groupRow(rep, depth + 1, row));
          ki++;
          continue;
        }
      }

      // Fold a run of identical consecutive calls so an N+1 loop cannot flood the page.
      var runEnd = ki;
      while (runEnd < kids.length
             && kids[runEnd].className === kid.className
             && kids[runEnd].methodName === kid.methodName
             && kids[runEnd].callSiteLine === kid.callSiteLine) {
        runEnd++;
      }
      var runLen = runEnd - ki;
      var foldId = node.seq + ':' + ki;
      // Full means full. The identical-run fold is there to stop an N+1 loop flooding the
      // default view, but it was applying in Full too, so a control documented as showing
      // every recorded call quietly kept hiding the repeats.
      var foldable = !detailFull && !treeGroup && runLen > FOLD_RUN_AFTER
        && !expandedFolds[foldId] && !anyOnRevealPath(kids, ki, runEnd);
      var show = foldable ? FOLD_RUN_AFTER : runLen;
      for (var n = 0; n < show; n++) {
        emitFrame(kids[ki + n], depth + 1, row, honourCollapse, timeOk);
        if (treeRows.length >= MAX_TREE_ROWS) { treeTruncated = true; return; }
      }
      if (show < runLen) {
        treeRows.push(foldRow(kids.slice(ki + show, runEnd), depth + 1, row, foldId));
      }
      ki = runEnd;
    }
    while (li < lines.length) {
      treeRows.push(codeRow(lines[li++], file, depth + 1, row));
      if (treeRows.length >= MAX_TREE_ROWS) { treeTruncated = true; return; }
    }
  }

  function filtering() {
    return queryFor('trace').trim() !== ''
      || !(statusOn.FULL && statusOn.PARTIAL && statusOn.NONE);
  }

  function buildTree() {
    treeRows = [];
    treeTruncated = false;
    hiddenFrameCount = 0;
    treeTable.textContent = '';
    if (!treeAvailable) return;
    // While a filter is active collapse is ignored: a match must never hide behind a
    // folded frame the user cannot see to open.
    var honourCollapse = !filtering();
    var timeOk = currentTimeFilter();
    roots.forEach(function (r) { emitFrame(r, 0, null, honourCollapse, timeOk); });

  }

  /** The row's element, built the first time it is actually needed and kept after that. */
  function rowEl(r) {
    if (!r.tr) r.tr = r.build();
    return r.tr;
  }

  // ------------------------------------------------------- virtual tree rows ---

  /**
   * Renders only the rows near the viewport.
   *
   * <p>A recording of any size used to become that many table rows, each with a dozen
   * elements under it, and the browser was asked to lay all of them out before the report
   * would show anything. Twenty thousand rows took the better part of a minute. Now the
   * table holds a screenful plus a margin, and two spacer rows stand in for the height of
   * everything above and below, so the scrollbar still describes the whole run.
   *
   * <p>Row heights are not uniform, a query occupies its clause lines, so measured heights
   * replace the estimate as rows are rendered and the offsets are recomputed from those.
   */
  var TREE_ROW_EST = 21;         // starting guess, replaced by measurement
  var TREE_OVERSCAN = 12;        // rows rendered beyond each edge, so scrolling is not bare
  var visRows = [];              // rows passing the filters, in order
  var rowHeights = [];           // measured height per visRows index, 0 until seen
  var rowOffsets = null;         // running sum of heights; rowOffsets[i] is the top of row i
  var treeWrapEl = null;
  var padTop = null;
  var padBot = null;
  var renderedFrom = -1;         // window currently in the document, so a scroll that
  var renderedTo = -1;           // does not move it costs nothing
  var renderDirty = true;        // set when the rows themselves changed under it

  function ensurePads() {
    if (padTop) return;
    treeWrapEl = treeTable.parentNode;
    padTop = el('tr', 'vpad');
    padBot = el('tr', 'vpad');
    padTop.appendChild(el('td'));
    padBot.appendChild(el('td'));
    padTop.firstChild.colSpan = 3;
    padBot.firstChild.colSpan = 3;
    // The PAGE is what scrolls, so that is what the window has to follow. This used to
    // listen on the wrapper and read its scrollTop, but the wrapper has no height limit and
    // therefore never scrolls: scrollTop stayed 0, the event never fired, and the tree
    // rendered exactly one 600px window at the top with the rest of the run standing as
    // hundreds of thousands of pixels of blank space nothing could reveal.
    window.addEventListener('scroll', onTreeScroll, { passive: true });
    window.addEventListener('resize', onTreeScroll);
  }

  /**
   * Painted straight from the scroll event, not behind a frame request.
   *
   * <p>The guard inside {@link renderTreeWindow} makes a scroll that does not move the
   * window free, so there is nothing to throttle, and a deferred render is one more piece
   * of state that can strand: a queue flag that is never cleared leaves the tree frozen on
   * whatever screenful it last drew, which is indistinguishable from the bug this replaced.
   */
  function onTreeScroll() {
    if (view !== 'tree' || tab !== 'trace') return;
    renderTreeWindow();
  }

  /**
   * The slice of the diagram's own coordinates that is currently on screen.
   *
   * <p>Measured from the wrapper's position in the viewport rather than from a scroll
   * offset, so it is correct whether the tree starts below the fold, is scrolled past
   * entirely, or is shorter than the window.
   */
  function treeBand() {
    var rect = treeWrapEl.getBoundingClientRect();
    var viewH = window.innerHeight || 800;
    var top = Math.max(0, -rect.top);
    return { top: top, bottom: top + viewH };
  }

  function rebuildOffsets() {
    var n = visRows.length;
    rowOffsets = new Float64Array(n + 1);
    for (var i = 0; i < n; i++) {
      rowOffsets[i + 1] = rowOffsets[i] + (rowHeights[i] || TREE_ROW_EST);
    }
  }

  /** First row whose bottom is past {@code y}. */
  function rowAt(y) {
    var lo = 0;
    var hi = visRows.length;
    while (lo < hi) {
      var mid = (lo + hi) >> 1;
      if (rowOffsets[mid + 1] <= y) lo = mid + 1;
      else hi = mid;
    }
    return lo;
  }

  /**
   * Draws the rows the page is currently over, and settles the estimates behind them.
   *
   * <p>Rows are not a uniform height, so the offsets start as estimates and are corrected
   * as rows are measured. Correcting them moves every row below, which can put a different
   * set of rows under the viewport, so the pass repeats until it stops changing. Bounded at
   * three: heights only ever become more accurate, so it converges immediately, and a cap
   * means a pathological layout costs a wasted pass rather than a hung tab.
   */
  function renderTreeWindow() {
    for (var pass = 0; pass < 3; pass++) {
      if (!paintTreeWindow()) break;
    }
    // Rows are recycled as the window moves, so a landing mark set before this ran is on
    // the wrong element, or on none. Re-applied here rather than in paintTreeWindow so it
    // survives all three measurement passes and is only put on once.
    if (landedSeq != null) paintLanded();
  }

  /** One pass. Returns true when measurement moved the rows and another pass is due. */
  function paintTreeWindow() {
    if (!padTop) return false;
    var n = visRows.length;
    var band = treeBand();
    var from = Math.max(0, rowAt(band.top) - TREE_OVERSCAN);
    var to = Math.min(n, rowAt(band.bottom) + 1 + TREE_OVERSCAN);
    // Nothing moved and nothing changed: skip the DOM churn, which matters because this
    // runs on every scroll event.
    if (renderedFrom === from && renderedTo === to && !renderDirty) return false;
    renderedFrom = from;
    renderedTo = to;
    renderDirty = false;

    var frag = document.createDocumentFragment();
    frag.appendChild(padTop);
    for (var i = from; i < to; i++) frag.appendChild(rowEl(visRows[i]));
    frag.appendChild(padBot);
    padTop.firstChild.style.height = rowOffsets[from] + 'px';
    padBot.firstChild.style.height = Math.max(0, rowOffsets[n] - rowOffsets[to]) + 'px';
    treeTable.textContent = '';
    treeTable.appendChild(frag);

    // Measure what was just laid out. Heights only ever become more accurate, so a change
    // means the rows below have moved and the offsets have to be redone; doing it once
    // after the pass rather than per row keeps this to a single extra reflow.
    var changed = false;
    for (var k = from; k < to; k++) {
      var hh = visRows[k].tr.offsetHeight;
      if (hh && rowHeights[k] !== hh) { rowHeights[k] = hh; changed = true; }
    }
    if (changed) {
      rebuildOffsets();
      padTop.firstChild.style.height = rowOffsets[from] + 'px';
      padBot.firstChild.style.height = Math.max(0, rowOffsets[n] - rowOffsets[to]) + 'px';
      // Every row below just moved, so the band may map onto different rows than it did a
      // moment ago; the caller runs another pass.
      renderDirty = true;
    }
    syncSqlClamps();
    return changed;
  }

  /**
   * Reveals the expand control only on queries the clamp is actually cutting off.
   *
   * <p>Must run after the rows are in the document: a statement's rendered height depends
   * on how wide the window is, so the same SQL can need the control at one size and not at
   * another. Reading {@code scrollHeight} here forces one layout pass, which is why it is
   * done once for the whole table rather than per row as each is built.
   */
  var sqlClampGeneration = 0;
  function syncSqlClamps() {
    var blocks = treeTable.querySelectorAll('pre.sqltext');
    Array.prototype.forEach.call(blocks, function (code) {
      var more = code.nextElementSibling;
      if (!more || more.className !== 'sqlmore') return;
      // Reading scrollHeight forces layout, and this now runs behind every scroll frame.
      // The answer only changes when the width does, so each block is measured once per
      // resize rather than once per frame.
      if (code.dejuClamped === sqlClampGeneration) return;
      code.dejuClamped = sqlClampGeneration;
      var overflowing = code.scrollHeight > code.clientHeight + 1;
      more.hidden = !overflowing && code.classList.contains('clamped');
    });
  }
  window.addEventListener('resize', function () { sqlClampGeneration++; });

  function applyTreeFilters() {
    var q = queryFor('trace').trim().toLowerCase();
    var active = filtering();
    var shownLines = 0, shownFrames = 0;
    var rollupTotal = 0;

    treeRows.forEach(function (r) {
      if (r.rollup) rollupTotal++;
      r.hit = false;
      if ((r.sql && !sqlOn) || (r.rollup && !rollupOn)) {
        r.vis = false;   // hidden outright, and never revived by parent propagation
      } else if (r.kind === 'line') {
        r.vis = statusOn[r.status] && (q === '' || r.text.indexOf(q) !== -1);
        r.hit = r.vis;
      } else if (r.kind === 'fold') {
        r.vis = !active;
      } else {
        // A frame matches on its own signature. Searching a call tree for "OrderService"
        // or "placeOrder" used to find nothing at all, because only source text was ever
        // compared and a frame carries none: the one thing a reader is most likely to type
        // was the one thing the filter could not answer.
        r.hit = active && q !== '' && r.text.indexOf(q) !== -1;
        r.vis = !active || r.hit;
      }
    });
    if (active) {
      treeRows.forEach(function (r) {
        if (r.hit) {
          for (var p = r.parent; p; p = p.parent) p.vis = true;
        }
      });
    }
    // The visible rows become a list rather than a display flag on every row: the window
    // below indexes into it, and rows that never come near the viewport cost one array
    // slot instead of a table row nobody will look at.
    visRows = [];
    rowHeights = [];
    var shownSql = 0;
    treeRows.forEach(function (r) {
      if (!r.vis) return;
      r.vi = visRows.length;
      visRows.push(r);
      // Only rows still in the document have a height worth keeping. The parentNode test
      // matters: after a rebuild every row is detached, and asking a detached row for its
      // offsetHeight forces a layout of an empty table, which makes the browser clamp the
      // page scroll to zero — that was what threw the reader back to the top of the run
      // every time a frame was collapsed.
      rowHeights.push(r.tr && r.tr.parentNode ? r.tr.offsetHeight : 0);
      if (r.sql && r.kind === 'line') shownSql++;
      else if (r.kind === 'line') shownLines++;
      else if (r.kind === 'frame') shownFrames++;
    });
    // A filter hides every fold row, so there is nothing for the toggle to act on then.
    // Deciding from the rows themselves is what keeps the control honest: it used to be
    // derived from why roll-ups might exist, and was wrong in both directions.
    rollupAvailable = active ? 0 : rollupTotal;
    syncRollupBtn();
    ensurePads();
    rebuildOffsets();
    // Deliberately does NOT scroll anywhere. This used to snap to the top on every
    // refresh, which meant collapsing a frame or expanding a fold five hundred rows down
    // threw the reader back to the start of the run.
    renderDirty = true;
    renderTreeWindow();

    // Counted in calls, not lines: a method invoked twice renders its lines twice, so
    // "x of totalLines" would be meaningless here. Queries are counted apart from both,
    // because they are neither: totting them into the call denominator made the three
    // numbers irreconcilable.
    countEl.textContent = 'Showing ' + shownFrames + ' of ' + frameTotal
      + (frameTotal === 1 ? ' call' : ' calls')
      + (sqlTotal ? ' · ' + shownSql + ' of ' + sqlTotal
          + (sqlTotal === 1 ? ' query' : ' queries') : '')
      + ' · ' + shownLines + (shownLines === 1 ? ' line' : ' lines')
      + (hiddenFrameCount ? ' · ' + hiddenFrameCount + ' folded into excluded types' : '');
    treeEmpty.hidden = shownFrames !== 0 || shownLines !== 0 || shownSql !== 0;
    if (cursor && !cursor.vis) clearCursor();
  }

  /**
   * Where the reader is, expressed as something that survives the rows being rebuilt.
   *
   * <p>A step number, plus how far it sits below the top of the viewport. Collapsing a
   * frame or expanding a fold throws away every row object and makes new ones, so an index
   * or a pixel offset would both point somewhere else afterwards; the step is the same step
   * before and after.
   */
  function treeAnchor() {
    if (!padTop || !rowOffsets || visRows.length === 0) return null;
    var band = treeBand();
    for (var k = rowAt(band.top); k < visRows.length; k++) {
      var r = visRows[k];
      if (r.kind === 'frame' && r.node) {
        return { seq: r.node.seq, gap: rowOffsets[k] - band.top };
      }
    }
    return null;
  }

  function restoreTreeAnchor(a) {
    if (!a || !rowOffsets || !treeWrapEl) return;
    var at = -1;
    for (var k = 0; k < visRows.length; k++) {
      var r = visRows[k];
      if (r.kind === 'frame' && r.node && r.node.seq === a.seq) { at = k; break; }
    }
    if (at < 0) return;   // the anchor was folded away by whatever just happened
    var rect = treeWrapEl.getBoundingClientRect();
    var pageTop = (window.pageYOffset || document.documentElement.scrollTop || 0) + rect.top;
    window.scrollTo(0, Math.max(0, pageTop + rowOffsets[at] - a.gap));
    renderDirty = true;
    renderTreeWindow();
  }

  var lastRefreshQuery = null;

  function refreshTree() {
    // Held across the rebuild only while the filter text is unchanged. Typing is a fresh
    // question and the answer belongs at the top; collapsing a frame is not, and used to
    // dump the reader back to the start of the trace.
    var sameQuery = lastRefreshQuery === queryFor('trace');
    lastRefreshQuery = queryFor('trace');
    var anchor = sameQuery ? treeAnchor() : null;
    buildTree();
    applyTreeFilters();
    restoreTreeAnchor(anchor);
    updateNotice();
  }

  // ------------------------------------------------------------ shared state ---

  var searchEl = byId('search');
  var countEl = byId('count');
  var emptyEl = byId('empty');
  var foldBtn = byId('foldBtn');
  var noticeEl = byId('notice');
  var view = prefs.view === 'files' ? 'files' : 'tree';

  /**
   * One filter box, one query per tab.
   *
   * <p>The box used to vanish on every tab but Code Trace, which left four views with no way
   * to narrow them down — on exactly the runs where narrowing them down is the only way to
   * read them. Keeping a separate query per tab rather than one shared string is what makes
   * that usable: the tabs filter different things by different rules, and a class name typed
   * into the tree would filter the Flow Graph's regex box down to nothing the moment the
   * reader switched.
   */
  var tabQuery = { trace: '', graph: '', flow: '', timeline: '', findings: '' };
  var TAB_SEARCH = {
    trace: { hint: 'Filter code\u2026 (press /)',
      title: 'Matches source text and SQL in both views. In the Tree it also matches a '
        + "frame's own class and method name." },
    graph: { hint: 'Filter files\u2026 (press /)',
      title: 'Matches a file name or the package it sits in.' },
    flow: { hint: 'Filter steps by pattern\u2026 (press /)',
      title: 'A regular expression over the step signature, or plain text when it is not a '
        + 'valid one. The dropdown beside the tab decides what a match brings with it.' },
    timeline: { hint: 'Filter steps\u2026 (press /)',
      title: 'Matches the step label, and for a query its statement.' },
    findings: { hint: 'Filter findings\u2026 (press /)',
      title: "Matches a finding's title and its detail." }
  };

  /** The query belonging to a tab, whether or not that tab is the one in front. */
  function queryFor(which) {
    return which === tab ? searchEl.value : tabQuery[which];
  }

  /** Empties the box and the query behind it, for Esc and for a jump to a step. */
  function clearSearch() {
    if (searchEl.value === '') return false;
    searchEl.value = '';
    if (tab) tabQuery[tab] = '';
    return true;
  }

  function applyFileFilters() {
    var q = queryFor('trace').trim().toLowerCase();
    var shownLines = 0, shownFiles = 0;
    entries.forEach(function (e) {
      if (!e.checkbox.checked) { e.box.hidden = true; return; }
      var anyVisible = false;
      e.rows.forEach(function (r) {
        r.vis = statusOn[r.status]
          && (q === '' || r.text.indexOf(q) !== -1)
          && !(r.section && r.section.collapsed);
        // The element only exists once the file has been attached; the record is the
        // truth either way, and attachEntry() replays it onto the rows it builds.
        if (r.tr) r.tr.style.display = r.vis ? '' : 'none';
        if (r.vis) { anyVisible = true; shownLines++; }
      });
      e.sections.forEach(function (s) {
        s.vis = s.rows.some(function (r) {
          return statusOn[r.status] && (q === '' || r.text.indexOf(q) !== -1);
        });
        if (s.tr) s.tr.style.display = s.vis ? '' : 'none';
        if (s.vis) anyVisible = true;
      });
      e.box.hidden = !anyVisible;
      if (anyVisible) shownFiles++;
    });
    attachVisibleFiles();
    countEl.textContent = 'Showing ' + shownLines + ' of ' + totalLines
      + ' line(s) · ' + shownFiles + ' file(s)';
    // Keyed on files, not lines: collapsing every method section leaves the files on
    // screen with no lines under them, and "No lines match the current filters" printed
    // over a page full of file headers reads as a bug in the report.
    emptyEl.hidden = shownFiles !== 0;
    updateNotice();
    if (cursor && cursor.tr
        && (cursor.tr.style.display === 'none' || (cursor.entry && cursor.entry.box.hidden))) {
      clearCursor();
    }
  }

  function applyFilters() {
    if (view === 'tree') refreshTree(); else applyFileFilters();
    // The Flow Graph reads the same file selection, so it has to follow it too. Only once
    // it exists: before the tab is first opened there is nothing to redraw, which is the
    // point of building it lazily.
    if (flowBuilt) drawFlow();
  }

  /** Routes a keystroke to whichever tab is in front. */
  function onSearchChanged() {
    if (tab) tabQuery[tab] = searchEl.value;
    if (tab === 'graph') renderGraph();
    else if (tab === 'flow') { if (setFlowFilter(searchEl.value)) { hideFlowTip(); drawFlow(); } }
    else if (tab === 'timeline') renderTimeline();
    else if (tab === 'findings') renderFindings();
    else applyFilters();
    rememberUrlState();
  }
  searchEl.addEventListener('input', onSearchChanged);

  // ------------------------------------------------------------------ legend ---

  var legend = byId('legend');
  [['full', 'FULL', 'fully executed'],
   ['partial', 'PARTIAL', 'only some branches taken'],
   ['none', 'NONE', 'entered method, line not executed']].forEach(function (x) {
    var c = el('span', 'chip ' + x[0], x[1] + ' ' + counts[x[1]]);
    c.title = 'Click to show/hide · ' + x[2];
    c.dataset.status = x[1];
    c.addEventListener('click', function () {
      statusOn[x[1]] = !statusOn[x[1]];
      syncLegend();
      applyFilters();
    });
    legend.appendChild(c);
  });

  function syncLegend() {
    Array.prototype.forEach.call(legend.children, function (c) {
      c.classList.toggle('off', !statusOn[c.dataset.status]);
    });
  }

  // ------------------------------------------------------------ view switch ---

  var viewTree = byId('viewTree');
  var viewFiles = byId('viewFiles');

  function setView(next) {
    if (next === 'tree' && !treeAvailable) return;
    view = next;
    clearCursor();
    fileIdx = -1;
    viewTree.classList.toggle('on', view === 'tree');
    viewFiles.classList.toggle('on', view === 'files');
    treeView.hidden = view !== 'tree';
    filesView.hidden = view !== 'files';
    Array.prototype.forEach.call(document.querySelectorAll('.t-only'), function (n) {
      // Some Tree-only controls have their own rule for when they apply at all, so being
      // in the Tree view is necessary but not sufficient. They opt out here and
      // syncToolbarExtras() decides for them.
      if (n.hasAttribute('data-owns-visibility')) return;
      n.hidden = view !== 'tree';
    });
    Array.prototype.forEach.call(document.querySelectorAll('.f-only'), function (n) {
      // The generated toggle stays hidden unless there is something generated to show.
      if (n.id === 'genToggleWrap' && generatedCount === 0) { n.hidden = true; return; }
      n.hidden = view !== 'files';
    });
    syncToolbarExtras();
    foldBtn.textContent = 'Collapse all';
    applyFilters();
    rememberUrlState();
  }

  /**
   * Visibility for the Tree controls that do not apply unconditionally.
   *
   * <p>One place decides all of them, because the alternative is each feature setting
   * {@code hidden} from its own handler and the view switch quietly overriding the lot.
   */
  function syncToolbarExtras() {
    var tree = view === 'tree';
    // Nothing to switch between when this project excludes nothing, and equally when the
    // export dropped that source, since Full would expand roll-ups into empty frames.
    var canFold = hasExclusions && !excludedOmitted;
    detailSeg.hidden = !tree || !canFold;
    // Roll-up rows only exist in Essential, so the control that hides them does too.
    //
    // Deliberately NOT tied to canFold. An export that omitted excluded source cannot offer
    // Full, because there would be nothing to expand into, but it still draws the roll-up
    // rows, and hiding their control along with the detail switch left those rows on screen
    // with no way to turn them off.
    rollupBtn.hidden = !tree || !hasExclusions || detailFull;
    syncRollupBtn();
    // Derived from the select rather than tracked separately: every path that leaves the
    // custom entry already puts the select back to a real value.
    var custom = tree && minTime.value === 'custom';
    minOp.hidden = !custom;
    minCustom.hidden = !custom;
    minCustomApply.hidden = !custom;
    // The upper bound only exists for "between", so it appears with that operator alone.
    var range = custom && minOp.value === 'bt';
    minAnd.hidden = !range;
    minCustom2.hidden = !range;
  }

  viewTree.addEventListener('click', function () { setView('tree'); });
  viewFiles.addEventListener('click', function () { setView('files'); });

  // -------------------------------------------------------------------- sort ---

  var sortEl = byId('sort');
  function applySort() {
    var list = entries.slice();
    var v = sortEl.value;
    if (v === 'total') {
      list.sort(function (a, b) { return (b.totalWeight - a.totalWeight) || (a.order - b.order); });
    } else if (v === 'self') {
      list.sort(function (a, b) { return (b.selfWeight - a.selfWeight) || (a.order - b.order); });
    } else if (v === 'red') {
      list.sort(function (a, b) { return (b.red - a.red) || (b.partial - a.partial) || (a.order - b.order); });
    } else if (v === 'name') {
      list.sort(function (a, b) { return a.name.localeCompare(b.name); });
    } else {
      list.sort(function (a, b) { return a.order - b.order; });
    }
    ordered = list;
    list.forEach(function (e) { root.appendChild(e.box); });
  }
  sortEl.addEventListener('change', applySort);

  // -------------------------------------------------------------------- fold ---

  function setCollapsed(entry, collapsed) {
    entry.box.classList.toggle('collapsed', collapsed);
    entry.caret.textContent = collapsed ? '▸ ' : '▾ ';
  }

  function foldAll(collapsed) {
    if (view === 'tree') {
      collapsedSeqs = {};
      if (collapsed) {
        // Collapse every frame that has something under it, except the roots, a fully
        // collapsed tree with no visible entry point would be a dead end.
        calls.forEach(function (c) {
          if (c.parentSeq >= 0) collapsedSeqs[c.seq] = true;
        });
      }
      refreshTree();
    } else {
      // Sections fold with their files. "Collapse all" that shut the file boxes and left
      // every method inside them open was only half a fold: expanding one forty-method
      // file still gave back the wall of code the button existed to put away. Folding both
      // levels means expanding a file lands you on its list of methods, which is the index
      // this view is actually navigated by.
      entries.forEach(function (e) {
        setCollapsed(e, collapsed);
        e.sections.forEach(function (s) {
          s.collapsed = collapsed;
          if (s.caret) s.caret.textContent = collapsed ? '▸' : '▾';
        });
      });
      applyFilters();
    }
    foldBtn.textContent = collapsed ? 'Expand all' : 'Collapse all';
  }

  foldBtn.addEventListener('click', function () {
    foldAll(foldBtn.textContent === 'Collapse all');
  });

  entries.forEach(function (e) {
    e.h.addEventListener('click', function () {
      setCollapsed(e, !e.box.classList.contains('collapsed'));
    });
  });

  // ----------------------------------------------------------- problems only ---

  var problemsBtn = byId('problemsBtn');
  var problemsOn = false;
  var savedStatus = null;

  function setProblems(on) {
    problemsOn = on;
    problemsBtn.classList.toggle('on', on);
    if (on) {
      savedStatus = { FULL: statusOn.FULL, PARTIAL: statusOn.PARTIAL, NONE: statusOn.NONE };
      statusOn.FULL = false;
      statusOn.PARTIAL = true;
      statusOn.NONE = true;
      if (view === 'files') {
        entries.forEach(function (e) {
          e.sections.forEach(function (s) { s.collapsed = false; s.caret.textContent = '▾'; });
          setCollapsed(e, e.red === 0 && e.partial === 0);
        });
        foldBtn.textContent = 'Collapse all';
      }
    } else if (savedStatus) {
      statusOn.FULL = savedStatus.FULL;
      statusOn.PARTIAL = savedStatus.PARTIAL;
      statusOn.NONE = savedStatus.NONE;
      savedStatus = null;
    }
    syncLegend();
    applyFilters();
  }

  problemsBtn.addEventListener('click', function () { setProblems(!problemsOn); });

  // ---------------------------------------------------------- tree controls ---

  var hotBtn = byId('hotBtn');
  function setHot(on) {
    hotOn = on;
    hotBtn.classList.toggle('on', on);
    refreshTree();
  }
  hotBtn.addEventListener('click', function () { setHot(!hotOn); });

  // ------------------------------------------------------- time filter ---

  var minTime = byId('minTime');
  var minOp = byId('minTimeOp');
  var minCustom = byId('minTimeCustom');
  var minCustom2 = byId('minTimeCustom2');
  var minAnd = byId('minTimeAnd');
  var minCustomApply = byId('minTimeCustomApply');
  /* The value the select carried before "Custom…" was picked, so cancelling restores the
     filter that was actually in force rather than silently resetting to All calls. */
  var minTimePrevious = '0';

  var UNITS = { us: 1, 'µs': 1, ms: 1000, s: 1e6, m: 6e7 };

  /**
   * Parses "750us", "1.5 ms", "2s", "1m" or a bare number, which is read as milliseconds
   * because that is the unit most of the presets use.
   *
   * @return microseconds, or null when the text is not a positive duration
   */
  function parseDuration(text) {
    var m = /^\s*(\d+(?:\.\d+)?)\s*(µs|us|ms|s|m)?\s*$/i.exec(text || '');
    if (!m) return null;
    var n = parseFloat(m[1]);
    if (!isFinite(n) || n <= 0) return null;
    var micros = Math.round(n * (UNITS[m[2] ? m[2].toLowerCase() : 'ms'] || 1000));
    return micros > 0 ? micros : null;
  }

  /**
   * Turns a select value into a predicate over a call's own total time.
   *
   * <p>Encoded in the option value rather than held in separate state, so the control and
   * the filter cannot disagree: {@code ge:1000}, {@code lt:1000}, {@code bt:1000:10000},
   * or {@code 0} for everything. Upper bounds are exclusive, so the "between" presets tile
   * without a call falling into two of them.
   */
  function parseTimeSpec(value) {
    var parts = String(value || '0').split(':');
    var lo = parseInt(parts[1], 10);
    var hi = parseInt(parts[2], 10);
    if (parts[0] === 'ge' && isFinite(lo)) return function (t) { return t >= lo; };
    if (parts[0] === 'lt' && isFinite(lo)) return function (t) { return t < lo; };
    if (parts[0] === 'bt' && isFinite(lo) && isFinite(hi)) {
      return function (t) { return t >= lo && t < hi; };
    }
    return null;
  }

  function currentTimeFilter() {
    // "Custom…" is the control being open, not a filter: nothing is hidden until Apply.
    return parseTimeSpec(minTime.value) || function () { return true; };
  }

  /** Human wording for a spec, used as the label of the generated Custom option. */
  function describeSpec(op, lo, hi) {
    if (op === 'lt') return '< ' + fmt(lo);
    if (op === 'bt') return fmt(lo) + ' to ' + fmt(hi);
    return '≥ ' + fmt(lo);
  }

  function showCustomInput(show) {
    syncToolbarExtras();
    if (show) { minCustom.focus(); minCustom.select(); }
  }

  /** Adds or updates the one option that carries a hand-entered filter. */
  function setCustomOption(value, label) {
    var opt = byId('minTimeCustomOpt');
    if (!opt) {
      opt = el('option');
      opt.id = 'minTimeCustomOpt';
      minTime.insertBefore(opt, minTime.lastElementChild);
    }
    opt.value = value;
    opt.textContent = label;
    minTime.value = value;
    minTimePrevious = value;
  }

  function flagBad(field) {
    field.classList.add('bad');
    field.focus();
  }

  function applyCustom() {
    var op = minOp.value;
    var lo = parseDuration(minCustom.value);
    if (lo === null) { flagBad(minCustom); return; }
    var hi = null;
    if (op === 'bt') {
      hi = parseDuration(minCustom2.value);
      if (hi === null) { flagBad(minCustom2); return; }
      if (hi <= lo) {
        // Silently swapping would apply a filter the user did not ask for; better to
        // point at the field that has to change.
        flagBad(minCustom2);
        return;
      }
    }
    minCustom.classList.remove('bad');
    minCustom2.classList.remove('bad');
    setCustomOption(op === 'bt' ? 'bt:' + lo + ':' + hi : op + ':' + lo,
      describeSpec(op, lo, hi));
    showCustomInput(false);
    refreshTree();
  }

  function cancelCustom() {
    minCustom.classList.remove('bad');
    minCustom2.classList.remove('bad');
    minTime.value = minTimePrevious;
    showCustomInput(false);
    minTime.focus();
  }

  minTime.addEventListener('change', function () {
    if (minTime.value === 'custom') {
      showCustomInput(true);
      return;   // nothing is filtered until a value is actually entered
    }
    minTimePrevious = minTime.value;
    showCustomInput(false);
    refreshTree();
  });
  // Switching to "between" reveals the second field, so the row has to re-sync.
  minOp.addEventListener('change', function () { syncToolbarExtras(); minCustom.focus(); });
  [minCustom, minCustom2].forEach(function (field) {
    field.addEventListener('input', function () { field.classList.remove('bad'); });
    field.addEventListener('keydown', function (e) {
      e.stopPropagation();   // j/k/n/p are page shortcuts; here they are just letters
      if (e.key === 'Enter') { e.preventDefault(); applyCustom(); }
      else if (e.key === 'Escape') { e.preventDefault(); cancelCustom(); }
    });
  });
  minCustomApply.addEventListener('click', applyCustom);

  // ---------------------------------------------------------- sql visibility ---

  /* On by default: queries are the reason SQL is recorded at all. The toggle is for
     reading pure control flow, where a chatty repository drowns the calls around it. */
  var sqlBtn = byId('sqlBtn');

  /**
   * Opens the Code Trace on one step, whatever is currently folded over it.
   *
   * <p>The entry point for every "show me" link in Findings and the Timeline. Turning the
   * filters off is deliberate: a link that lands on a blank tree because a time filter was
   * still narrowing it would read as broken, and the reader asked for this step, not for
   * their previous question.
   */
  function goToStep(seq) {
    var node = nodeBySeq[seq];
    if (!node) return;
    revealPath = {};
    for (var cur = node; cur; cur = nodeBySeq[cur.parentSeq]) {
      revealPath[cur.seq] = true;
      delete collapsedSeqs[cur.seq];
      expandedRollups[cur.seq] = true;
    }
    clearSearch();
    setTab('trace');
    if (view !== 'tree') setView('tree');
    else refreshTree();

    for (var i = 0; i < treeRows.length; i++) {
      var r = treeRows[i];
      if (r.node && r.node.seq === seq && r.vis) { setCursor(r); break; }
    }
    // Held only for the build that just ran; leaving it set would quietly disable folding
    // for the rest of the session.
    revealPath = null;
    landOn(seq);
  }

  /*
   * Marks the row a cross-tab link just arrived at.
   *
   * <p>Arriving from the Timeline changes the tab, the view and the folding all at once, and
   * the cursor outline alone is easy to lose in a screen of rows that all just moved. A wash
   * that fades out says "here" without leaving a second permanent highlight competing with
   * the cursor.
   *
   * <p>Held as a seq rather than pinned to the element, because the tree is virtualised: the
   * row scrolled to may not be built yet, and the one that exists now can be recycled for a
   * different frame before the animation ends. renderTreeWindow re-applies it to whichever
   * element currently carries that seq, so the mark follows the frame and not the DOM node.
   */
  var landedSeq = null;
  var landedTimer = 0;

  /** How long the yellow outline (not the 1.6s background fade) marks the landed row. */
  var LAND_BORDER_MS = 60_000;

  function landOn(seq) {
    landedSeq = seq;
    paintLanded();
    if (landedTimer) clearTimeout(landedTimer);
    landedTimer = setTimeout(function () {
      landedSeq = null;
      landedTimer = 0;
      paintLanded();
    }, LAND_BORDER_MS);
  }

  /** Puts the mark on the row holding landedSeq right now, and takes it off every other. */
  function paintLanded() {
    var rows = treeWrapEl ? treeWrapEl.querySelectorAll('tr.landed') : [];
    for (var i = 0; i < rows.length; i++) rows[i].classList.remove('landed');
    if (landedSeq == null) return;
    for (var k = 0; k < treeRows.length; k++) {
      var r = treeRows[k];
      if (r.node && r.node.seq === landedSeq && r.tr && r.tr.parentNode) {
        // Restarting the animation matters when the same row is landed on twice: without
        // the reflow between removals the browser treats it as the same running animation.
        r.tr.classList.remove('landed');
        void r.tr.offsetWidth;
        r.tr.classList.add('landed');
        return;
      }
    }
  }

  var treeGroupBtn = byId('treeGroupBtn');
  treeGroupBtn.addEventListener('click', function () {
    treeGroup = !treeGroup;
    treeGroupBtn.classList.toggle('on', treeGroup);
    expandedGroups = {};
    refreshTree();
  });

  function setSql(on) {
    sqlOn = on;
    sqlBtn.classList.toggle('on', on);
    sqlBtn.title = on
      ? 'Hide every SQL statement in the tree'
      : 'Show SQL statements again';
    applyTreeFilters();
  }
  sqlBtn.addEventListener('click', function () { setSql(!sqlOn); });

  // --------------------------------------------------------- detail level ---

  var detailSeg = byId('detailSeg');
  var detailEssentialBtn = byId('detailEssential');
  var detailFullBtn = byId('detailFull');
  function setDetail(full) {
    detailFull = full;
    detailEssentialBtn.classList.toggle('on', !full);
    detailFullBtn.classList.toggle('on', full);
    // Expanding a roll-up is a statement about one call site; switching to Full and back
    // should not silently keep every group open.
    expandedRollups = {};
    syncToolbarExtras();
    refreshTree();
    rememberUrlState();
  }
  detailEssentialBtn.addEventListener('click', function () { setDetail(false); });
  detailFullBtn.addEventListener('click', function () { setDetail(true); });

  // -------------------------------------------------------- excluded roll-ups ---

  /* The "… N excluded calls" rows are a deliberate trace of what Essential folded away, so
     they stay on by default. Hiding them is for reading a clean call flow once you have
     already accepted that the excluded types are not interesting. */
  var rollupBtn = byId('rollupBtn');

  /**
   * Puts the button in the state the tree is actually in.
   *
   * <p>Greyed rather than hidden when a filter has taken the roll-up rows away: a control
   * that disappears while you type reads as a glitch, and one that stays lit while doing
   * nothing reads as broken. This says plainly that there is nothing to act on.
   */
  function syncRollupBtn() {
    if (!rollupBtn) return;      // called once during setup, before the lookup below runs
    rollupBtn.classList.toggle('on', rollupOn);
    rollupBtn.disabled = rollupAvailable === 0;
    rollupBtn.title = rollupAvailable === 0
      ? 'No "N excluded calls" rows to hide while a filter is narrowing the tree'
      : (rollupOn ? 'Hide the "N excluded calls" summary rows'
                  : 'Show the "N excluded calls" summary rows again');
  }

  function setRollups(on) {
    rollupOn = on;
    applyTreeFilters();      // recounts, then repaints the button through syncRollupBtn
  }
  rollupBtn.addEventListener('click', function () {
    if (rollupAvailable === 0) return;
    setRollups(!rollupOn);
  });

  // --------------------------------------------------------------- generated ---

  if (generatedCount > 0) {
    var genToggle = byId('genToggle');
    byId('genCount').textContent = '(' + generatedCount + ')';
    // Opens and closes them rather than filtering them away. Generated files are never
    // hidden from the Tree or from the counts, they are just folded shut so the Files view
    // is not eight screens of MapperImpl before the first line anybody wants to read.
    genToggle.addEventListener('change', function () {
      entries.forEach(function (e) {
        if (e.generated) setCollapsed(e, !genToggle.checked);
      });
      applyFilters();
    });
  }

  // ------------------------------------------------------------------- theme ---

  /* Remembered across reopenings. A report is a file you come back to, and re-picking the
     theme every time is a small annoyance repeated forever. Wrapped because a page on
     file:// may have no storage at all, in which case the toggle simply stops persisting
     rather than throwing. */
  var THEME_KEY = 'deju.report.theme';
  function storedTheme(value) {
    try {
      if (value === undefined) return window.localStorage.getItem(THEME_KEY);
      window.localStorage.setItem(THEME_KEY, value);
    } catch (e) { /* private mode, or no storage for this origin */ }
    return null;
  }

  function toggleTheme() {
    var cur = document.documentElement.getAttribute('data-theme');
    var dark = cur ? cur === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
    var next = dark ? 'light' : 'dark';
    document.documentElement.setAttribute('data-theme', next);
    storedTheme(next);
    // Keep the Customize panel's dropdown telling the truth: the toggle and the dropdown
    // are two controls over one setting, and either can be used at any time.
    prefs.theme = next;
    if (typeof syncCustomize === 'function') syncCustomize();
  }
  /**
   * Settles the theme from the reader's last choice, then the export's, then the OS.
   *
   * <p>The reader wins because the toggle has always been sticky and taking that away would
   * be a regression. The exported preference is only consulted for a reader who has never
   * expressed one, which is exactly the case it was meant for: sending a report to somebody
   * and having it open the way it was written.
   */
  function applyTheme() {
    var saved = storedTheme();
    var pick = (saved === 'dark' || saved === 'light') ? saved : prefs.theme;
    if (pick === 'dark' || pick === 'light') {
      document.documentElement.setAttribute('data-theme', pick);
    } else {
      document.documentElement.removeAttribute('data-theme');
    }
  }
  applyTheme();
  byId('themeToggle').addEventListener('click', toggleTheme);

  // -------------------------------------------------------------------- help ---

  var help = byId('help');
  byId('helpBtn').addEventListener('click', function () { help.hidden = false; });
  byId('helpClose').addEventListener('click', function () { help.hidden = true; });
  help.addEventListener('click', function (e) { if (e.target === help) help.hidden = true; });

  // --------------------------------------------------------------- customize ---

  /*
   * Everything the reader is allowed to change about this report, in one panel.
   *
   * <p>Each control is declared once, here, with the pref key it writes and how to apply it.
   * The panel's markup, the wiring, the persistence and the reset are all derived from this
   * list, so adding a setting is one entry rather than four edits in four places that can
   * disagree with each other.
   *
   * <p>Deliberately excluded: anything that changes what the numbers mean. Filters, the
   * time threshold and Essential/Full stay in the toolbar where they are visible, because a
   * reader who forgot they set one in a panel would misread the report rather than just
   * dislike the look of it.
   */
  var CUSTOM_GROUPS = [
    {
      head: 'Show', items: [
        { key: 'showTime', label: 'Time' },
        { key: 'showPercent', label: 'Percent' },
        { key: 'showStep', label: 'Step number' },
        { key: 'showLine', label: 'Line number' },
        { key: 'sql', label: 'SQL statements' }
      ]
    },
    {
      head: 'Layout', selects: [
        {
          key: 'openTab', label: 'Opens on', options: [
            ['trace', 'Call Tree'], ['graph', 'Breakdown'], ['flow', 'Flame Graph'],
            ['timeline', 'Timeline'], ['findings', 'Findings']
          ], tabs: true
        },
        { key: 'view', label: 'Call Tree view', options: [['tree', 'Tree'], ['files', 'Files']] },
        { key: 'density', label: 'Density', options: [['normal', 'Normal'], ['compact', 'Compact']] },
        { key: 'theme', label: 'Theme', options: [['auto', 'Follow my system'], ['light', 'Light'], ['dark', 'Dark']] }
      ],
      note: 'Opens on and Call Tree view take effect the next time this report is opened.'
    },
    {
      head: 'Starts folded', items: [
        { key: 'groupRepeats', label: 'Group repeated calls' },
        { key: 'collapseTree', label: 'Call tree' },
        { key: 'collapseSections', label: 'Files and sections' }
      ]
    }
  ];

  var customPanel = byId('custom');
  var customBody = byId('customBody');
  var customInputs = {};      // pref key -> the control showing it

  /**
   * Applies every pref that can be applied without rebuilding anything.
   *
   * <p>Column visibility and density are CSS-only on purpose: a class on the body reaches
   * every row that exists and every row built later, where hiding cells in the row builder
   * would mean re-rendering the tree, the timeline and the findings list to change one
   * checkbox. The virtualised tree measures its own row heights and corrects them on the
   * next pass, so a density change needs no help beyond a repaint.
   */
  function applyPrefs() {
    var body = document.body;
    body.classList.toggle('no-time', !prefs.showTime);
    body.classList.toggle('no-pct', !prefs.showPercent);
    body.classList.toggle('no-step', !prefs.showStep);
    body.classList.toggle('no-line', !prefs.showLine);
    body.classList.toggle('compact', prefs.density === 'compact');
    applyTheme();
  }

  /** Records one reader choice, applies it, and remembers it for the next report. */
  function setPref(key, value) {
    if (prefs[key] === value) return;
    prefs[key] = value;
    prefOverrides[key] = value;
    writeOverrides(prefOverrides);

    if (key === 'theme') {
      // Shares a key with the header's toggle, which is the same setting by another route.
      if (value === 'auto') {
        try { window.localStorage.removeItem(THEME_KEY); } catch (e) { /* no storage */ }
      } else {
        storedTheme(value);
      }
      applyTheme();
      return;
    }
    if (key === 'sql') {
      setSql(value);
      return;
    }
    if (key === 'groupRepeats') {
      if (treeGroup !== value) treeGroupBtn.click();
      return;
    }
    if (key === 'collapseTree' || key === 'collapseSections') {
      applyFoldPrefs();
      return;
    }
    applyPrefs();
    // The tree's cached row heights were measured at the old density, and the timeline
    // draws its bars from measured widths; both have to be taken again.
    if (view === 'tree') { renderDirty = true; renderTreeWindow(); }
    if (tab === 'timeline' && timelineBuilt) renderTimeline();
  }

  /**
   * Folds what the prefs say should start folded.
   *
   * <p>Only ever folds. Unticking the box does not unfold, because by then the reader has
   * been opening and closing rows by hand and throwing that away to honour a checkbox they
   * just cleared would be the opposite of what they asked for.
   */
  function applyFoldPrefs() {
    if (view === 'tree' ? prefs.collapseTree : prefs.collapseSections) foldAll(true);
  }

  /** Builds the panel from CUSTOM_GROUPS. Called once, after everything it drives exists. */
  function buildCustomize() {
    CUSTOM_GROUPS.forEach(function (group) {
      customBody.appendChild(el('div', 'cuhead', group.head));
      var row = el('div', 'curow');
      (group.items || []).forEach(function (item) {
        var label = el('label', 'culabel');
        var box = document.createElement('input');
        box.type = 'checkbox';
        box.checked = !!prefs[item.key];
        box.addEventListener('change', function () { setPref(item.key, box.checked); });
        customInputs[item.key] = box;
        label.appendChild(box);
        label.appendChild(document.createTextNode(item.label));
        row.appendChild(label);
      });
      (group.selects || []).forEach(function (sel) {
        var label = el('label', 'culabel');
        label.appendChild(document.createTextNode(sel.label));
        var input = document.createElement('select');
        input.className = 'cusel';
        sel.options.forEach(function (opt) {
          // A tab this export left out is not offered as somewhere the report could open.
          if (sel.tabs && !tabPresent(TAB_BUTTON_ID[opt[0]])) return;
          var o = document.createElement('option');
          o.value = opt[0];
          o.textContent = opt[1];
          input.appendChild(o);
        });
        input.value = prefs[sel.key];
        input.addEventListener('change', function () { setPref(sel.key, input.value); });
        customInputs[sel.key] = input;
        label.appendChild(input);
        row.appendChild(label);
      });
      if (group.note) row.appendChild(el('p', 'cunote', group.note));
      customBody.appendChild(row);
    });
    syncCustomize();
  }

  /** Puts the controls back in step with prefs, after a reset or the header's theme toggle. */
  function syncCustomize() {
    for (var key in customInputs) {
      var input = customInputs[key];
      if (input.type === 'checkbox') input.checked = !!prefs[key];
      else input.value = prefs[key];
    }
  }

  byId('customizeBtn').addEventListener('click', function () { customPanel.hidden = false; });
  byId('customClose').addEventListener('click', function () { customPanel.hidden = true; });
  customPanel.addEventListener('click', function (e) {
    if (e.target === customPanel) customPanel.hidden = true;
  });

  byId('customReset').addEventListener('click', function () {
    // Back to what the export chose, not to the report's built-in defaults: "the exported
    // defaults" is what the button says, and it is the state the sender intended.
    var keys = [];
    for (var k in prefOverrides) keys.push(k);
    prefOverrides = {};
    writeOverrides(prefOverrides);
    try { window.localStorage.removeItem(THEME_KEY); } catch (e) { /* no storage */ }
    keys.forEach(function (key) {
      if (exportedPrefs[key] === undefined || prefs[key] === exportedPrefs[key]) return;
      // Routed through setPref so each one is applied the same way it would be by hand,
      // then the override it writes back is cleared below.
      setPref(key, exportedPrefs[key]);
    });
    prefOverrides = {};
    writeOverrides(prefOverrides);
    applyPrefs();
    syncCustomize();
  });

  // ------------------------------------------------------- files dropdown ---

  var fileBtn = byId('fileBtn');
  var fileMenu = byId('fileMenu');
  var fileSearch = byId('fileSearch');
  var fileNoMatch = byId('fileNoMatch');

  // Works in both views: in Files it picks what renders, in Tree it hides those frames.
  function openMenu() {
    fileMenu.hidden = false;
    fileSearch.focus();
    fileSearch.select();
  }

  fileBtn.addEventListener('click', function (e) {
    e.stopPropagation();
    if (fileMenu.hidden) openMenu(); else fileMenu.hidden = true;
  });
  fileMenu.addEventListener('click', function (e) { e.stopPropagation(); });
  document.addEventListener('click', function () { fileMenu.hidden = true; });

  function menuMatches() {
    return entries.filter(function (e) { return !e.label.hidden; });
  }

  fileSearch.addEventListener('input', function () {
    var q = fileSearch.value.trim().toLowerCase();
    var shown = 0;
    entries.forEach(function (e) {
      var hit = q === '' || e.lower.indexOf(q) !== -1;
      e.label.hidden = !hit;
      if (hit) shown++;
    });
    fileNoMatch.hidden = shown !== 0;
  });
  fileSearch.addEventListener('keydown', function (e) {
    if (e.key === 'Escape') { e.stopPropagation(); fileMenu.hidden = true; fileBtn.focus(); }
  });

  byId('fileAll').addEventListener('click', function () {
    menuMatches().forEach(function (e) { e.checkbox.checked = true; e.userToggled = true; });
    applyFilters();
  });
  byId('fileNone').addEventListener('click', function () {
    menuMatches().forEach(function (e) { e.checkbox.checked = false; e.userToggled = true; });
    applyFilters();
  });

  // ---------------------------------------------------------------- keyboard ---

  var cursor = null;
  var fileIdx = -1;

  function clearCursor() {
    if (cursor && cursor.tr) cursor.tr.classList.remove('cursor');
    cursor = null;
  }

  /**
   * Brings a virtualised tree row on screen and returns its element.
   *
   * <p>The page is the scroller, so the row's position is its offset inside the table plus
   * wherever the table happens to sit on the page. Rendering after the scroll is what makes
   * the element exist: a row outside the window has no {@code tr} until it is asked for.
   */
  function scrollTreeToRow(rec) {
    if (rec.vi == null || !rowOffsets || !treeWrapEl) return rec.tr;
    var rect = treeWrapEl.getBoundingClientRect();
    var pageTop = (window.pageYOffset || document.documentElement.scrollTop || 0) + rect.top;
    var target = pageTop + rowOffsets[rec.vi] - (window.innerHeight || 800) / 2;
    window.scrollTo(0, Math.max(0, target));
    renderDirty = true;
    renderTreeWindow();
    return rec.tr;
  }

  function setCursor(rec) {
    clearCursor();
    cursor = rec;
    if (view === 'tree') {
      var tr = scrollTreeToRow(rec);
      if (tr) tr.classList.add('cursor');
      rememberUrlState();
      return;
    }
    if (rec.entry) attachEntry(rec.entry);
    if (!rec.tr) return;
    rec.tr.classList.add('cursor');
    rec.tr.scrollIntoView({ block: 'center', behavior: 'smooth' });
  }

  function problemRows() {
    var out = [];
    if (view === 'tree') {
      treeRows.forEach(function (r) {
        if (r.kind === 'line' && r.status !== 'FULL' && r.vis) out.push(r);
      });
    } else {
      ordered.forEach(function (e) {
        if (!e.checkbox.checked || e.box.hidden) return;
        e.rows.forEach(function (r) {
          if (r.status !== 'FULL' && statusOn[r.status]) out.push(r);
        });
      });
    }
    return out;
  }

  function jumpProblem(delta) {
    var list = problemRows();
    if (!list.length) return;
    var i = cursor ? list.indexOf(cursor) : -1;
    var next = i === -1 ? (delta > 0 ? 0 : list.length - 1) : (i + delta + list.length) % list.length;
    var rec = list[next];
    if (view === 'files') {
      setCollapsed(rec.entry, false);
      if (rec.section && rec.section.collapsed) {
        rec.section.collapsed = false;
        rec.section.caret.textContent = '▾';
        applyFileFilters();
      }
    }
    setCursor(rec);
  }

  /** Frames (Tree) or files (Files) currently on screen, in display order. */
  function stops() {
    if (view === 'tree') {
      return treeRows.filter(function (r) { return r.kind === 'frame' && r.vis; });
    }
    return ordered.filter(function (e) { return e.checkbox.checked && !e.box.hidden; });
  }

  function jumpStop(delta) {
    var list = stops();
    if (!list.length) return;
    fileIdx = fileIdx === -1 ? (delta > 0 ? 0 : list.length - 1)
      : (fileIdx + delta + list.length) % list.length;
    if (fileIdx >= list.length) fileIdx = list.length - 1;
    var s = list[fileIdx];
    clearCursor();
    if (view === 'tree') {
      // Never s.tr directly: a frame outside the rendered window has no element, and
      // reaching for scrollIntoView on it threw a TypeError on every j and k press.
      scrollTreeToRow(s);
      return;
    }
    s.h.scrollIntoView({ block: 'start', behavior: 'smooth' });
  }

  function isTyping(t) {
    return t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT');
  }

  document.addEventListener('keydown', function (e) {
    if (e.metaKey || e.ctrlKey || e.altKey) return;

    if (e.key === 'Escape') {
      if (!help.hidden) { help.hidden = true; return; }
      if (!customPanel.hidden) { customPanel.hidden = true; return; }
      if (!fileMenu.hidden) { fileMenu.hidden = true; return; }
      if (clearSearch()) onSearchChanged();
      if (isTyping(e.target)) e.target.blur();
      clearCursor();
      return;
    }
    if (isTyping(e.target)) return;

    switch (e.key) {
      case '/':
        e.preventDefault();
        searchEl.focus();
        searchEl.select();
        break;
      case 'v': e.preventDefault(); setView(view === 'tree' ? 'files' : 'tree'); break;
      case 'n': e.preventDefault(); jumpProblem(1); break;
      case 'p': e.preventDefault(); jumpProblem(-1); break;
      case 'j': e.preventDefault(); jumpStop(1); break;
      case 'k': e.preventDefault(); jumpStop(-1); break;
      case 'e': e.preventDefault(); foldAll(false); break;
      case 'c': e.preventDefault(); foldAll(true); break;
      case 'h': e.preventDefault(); if (view === 'tree') setHot(!hotOn); break;
      case 'f': e.preventDefault(); openMenu(); break;
      case 'x': e.preventDefault(); setProblems(!problemsOn); break;
      case 't': e.preventDefault(); toggleTheme(); break;
      case 'g': e.preventDefault(); customPanel.hidden = false; break;
      case '?': e.preventDefault(); help.hidden = false; break;
      case 'o': {
        e.preventDefault();
        var link = cursor && cursor.tr && cursor.tr.querySelector('td.ln a');
        if (link) link.click();
        break;
      }
      case 'C': {
        e.preventDefault();
        var node = cursor && cursor.node;
        if (node) {
          copyText(frameAsText(node), function (ok) {
            flash(problemsBtn, ok ? 'Step copied' : 'Copy failed');
          });
        }
        break;
      }
      case 'y': {
        e.preventDefault();
        var path = null;
        if (cursor && view === 'tree') path = cursor.file && (cursor.file.absPath || cursor.file.path);
        else if (cursor) path = cursor.entry.path;
        else if (view === 'files') { var f0 = stops()[0]; path = f0 && f0.path; }
        if (path) copyText(path, function (ok) { flash(problemsBtn, ok ? 'Path copied' : 'Copy failed'); });
        break;
      }
      default: break;
    }
  });

  // ----------------------------------------------------------------- summary ---

  var summary = byId('summary');
  function stat(value, label, title, alt) {
    var d = el('div', 'stat');
    var b = el('b', null, value);
    // The larger unit rides inside the value, so it stays on the number's line rather
    // than competing with the caption underneath.
    if (alt) b.appendChild(el('span', 'statalt', ' ' + alt));
    d.appendChild(b);
    d.appendChild(el('span', null, label));
    if (title) d.title = title;
    return d;
  }

  /**
   * A larger unit for a millisecond total, or {@code null} when it does not reach one.
   *
   * <p>Milliseconds are what a developer compares requests in, so they are always shown.
   * But "94812 ms" is a number you have to stop and divide, so anything past a second
   * carries the human reading alongside rather than instead.
   */
  function coarserThanMs(ms) {
    return ms >= 1000 ? '(' + fmt(ms * 1000) + ')' : null;
  }

  var totalRed = entries.reduce(function (a, e) { return a + e.red; }, 0);
  var durationMs = data.durationMs || 0;
  summary.appendChild(stat(durationMs + ' ms', 'Total time',
    'wall time for the whole traced call', coarserThanMs(durationMs)));
  // -1 means the JVM could not report per-thread CPU time (rare), or the connected agent
  // predates this field; either way there is nothing honest to show, so it is left out
  // rather than printed as a misleading 0.
  if (data.cpuMicros != null && data.cpuMicros >= 0) {
    var cpuMs = data.cpuMicros / 1000;
    var cpuPct = durationMs > 0 ? Math.round(Math.min(100, (cpuMs / durationMs) * 100)) : null;
    summary.appendChild(stat(fmt(data.cpuMicros), 'CPU time',
      'CPU this thread actually burned during the call, as opposed to the wall time above.'
      + ' The gap between them is time spent blocked: I/O, a lock, or another thread.',
      cpuPct != null ? cpuPct + '% of wall' : null));
  }
  if (treeAvailable) {
    summary.appendChild(stat(String(calls.length), calls.length === 1 ? 'Call' : 'Calls',
      'method invocations recorded, in execution order'));
  }
  summary.appendChild(stat(String(entries.length), entries.length === 1 ? 'File' : 'Files'));
  summary.appendChild(stat(String(totalLines), totalLines === 1 ? 'Line' : 'Lines'));
  summary.appendChild(stat(String(totalRed), 'Unexecuted'));
  // A capped recording makes every number beside it a floor rather than a total, and the
  // stats row is where they are read. Saying so here, next to the counts it qualifies, is
  // the only place it cannot be scrolled past.
  if (data.callsTruncated) {
    // The percentage rather than the word, when it can be worked out. "Capped" says a
    // limit was reached; the number says whether what is left is most of the run or a
    // sixth of it, which is the difference between a caveat and a warning.
    var missingPct = unrecordedPct();
    var capped = stat(missingPct || 'capped', missingPct ? 'Unrecorded' : 'Recording',
      'The agent hit its recording cap, so later invocations were never recorded.'
      + ' Every count and total above is a lower bound, not the whole run.'
      + (missingPct
        ? ' ' + missingPct + ' of the run\'s time is not accounted for by any call in this'
          + ' report: the recording stopped before that work was reached.'
        : ''));
    capped.classList.add('warn');
    summary.appendChild(capped);
  }

  // ------------------------------------------------- sticky offset + startup ---

  var toolbar = byId('toolbar');
  var metaBar = byId('meta');
  function measureToolbar() {
    document.documentElement.style.setProperty('--toolbarH', toolbar.offsetHeight + 'px');
  }
  // The meta row sticks above the toolbar now that the tabs live in it, so anything that
  // parks itself under the toolbar (sticky file headers) has to clear both.
  function measureMeta() {
    document.documentElement.style.setProperty('--metaH', metaBar.offsetHeight + 'px');
  }
  measureToolbar();
  measureMeta();
  if (window.ResizeObserver) {
    new ResizeObserver(measureToolbar).observe(toolbar);
    new ResizeObserver(measureMeta).observe(metaBar);
  } else {
    window.addEventListener('resize', measureToolbar);
    window.addEventListener('resize', measureMeta);
  }

  /* An older agent still loaded in the traced JVM is the usual reason a recording has no
     call tree, and it used to be completely invisible - the Tree button simply did nothing. */
  function agentMismatch() {
    var a = data.agentVersion, p = data.pluginVersion;
    if (!p || (a && a === p)) return null;
    return 'Recorded by agent ' + (a || 'a version older than 1.1.0')
      + ', but the plugin is ' + p
      + '. Restart the traced application so it loads the current agent, then record again.';
  }

  function updateNotice() {
    var msgs = [];
    var mismatch = agentMismatch();
    if (mismatch) msgs.push(mismatch);
    if (data.callsTruncated) {
      var missing = unrecordedPct();
      msgs.push('The call tree hit the agent\'s recording cap, later invocations are missing.'
        + (missing ? ' About ' + missing + ' of the run is not accounted for by any recorded call.' : ''));
    }
    if (treeTruncated) {
      msgs.push('Tree display truncated at ' + MAX_TREE_ROWS
        + ' rows. Use "≥ 1 ms" to prune fast calls, or collapse frames.');
    }
    if (view === 'files' && entries.length > AUTO_COLLAPSE_ABOVE) {
      msgs.push(entries.length + ' files. Press e to expand everything, ? for shortcuts.');
    }
    noticeEl.hidden = msgs.length === 0;
    noticeEl.textContent = msgs.join('  ');
  }

  byId('fidelity').textContent = treeAvailable
    ? 'Steps are in exact execution order. Lines within a method are in source order and '
      + 'merged across invocations, so a loop body appears once rather than once per iteration.'
    : '';

  // Files view starts collapsed when long, with the slowest few open, so it opens as an
  // index rather than a wall of code.
  if (entries.length > AUTO_COLLAPSE_ABOVE) {
    var hottest = entries.slice()
      .sort(function (a, b) { return (b.selfWeight - a.selfWeight) || (a.order - b.order); })
      .slice(0, AUTO_EXPAND_TOP);
    entries.forEach(function (e) { setCollapsed(e, true); });
    hottest.forEach(function (e) { setCollapsed(e, false); });
  }
  // Data classes and generated code fold shut on arrival, whatever the file count. This is
  // what the file picker used to do by starting them unticked, moved somewhere it cannot be
  // mistaken for a filter: the header, the line counts and the timings all stay on screen,
  // and one click on the header opens the file.
  entries.forEach(function (e) {
    if (e.generated || e.excluded) setCollapsed(e, true);
  });

  // ============================================================ tab panels ===
  //
  // Graph and Flow are built the first time their tab is opened and never again: the
  // report is a single file that has to open instantly, and neither panel is worth any
  // DOM, layout or canvas work for a reader who only ever looks at the trace. Re-opening
  // a tab re-uses what was already built; only the flow canvas repaints, because a theme
  // change or a resize can invalidate the pixels.

  var tabTrace = byId('tabTrace');
  var tabGraph = byId('tabGraph');
  var tabFlow = byId('tabFlow');
  var tabTimeline = byId('tabTimeline');
  var tabFindings = byId('tabFindings');
  var tracePanel = byId('tracePanel');
  var graphPanel = byId('graphPanel');
  var flowPanel = byId('flowPanel');
  var timelinePanel = byId('timelinePanel');
  var findingsPanel = byId('findingsPanel');
  var traceControls = byId('traceControls');
  var graphControls = byId('graphControls');
  var flowControls = byId('flowControls');
  var timelineControls = byId('timelineControls');
  var findingsControls = byId('findingsControls');
  var tab = 'trace';
  var graphBuilt = false;
  var flowBuilt = false;
  var timelineBuilt = false;
  var findingsBuilt = false;

  /** Tab ids in toolbar order, paired with the button whose presence proves the tab exists. */
  var TAB_BUTTON_ID = {
    trace: 'tabTrace', graph: 'tabGraph', flow: 'tabFlow',
    timeline: 'tabTimeline', findings: 'tabFindings'
  };
  var TAB_ORDER = ['trace', 'graph', 'flow', 'timeline', 'findings'];

  /**
   * The nearest tab to {@code want} that is actually in this file.
   *
   * <p>An export can leave tabs out, and the requests that arrive here do not know that: a
   * shared URL, a stored preference and a link from a findings row were all written when
   * five tabs existed. Falling back to the first surviving tab keeps every one of those
   * working, where honouring the request would show a header over an empty page.
   */
  function nearestTab(want) {
    if (tabPresent(TAB_BUTTON_ID[want])) return want;
    for (var i = 0; i < TAB_ORDER.length; i++) {
      if (tabPresent(TAB_BUTTON_ID[TAB_ORDER[i]])) return TAB_ORDER[i];
    }
    return 'trace';
  }

  function setTab(want) {
    var next = nearestTab(want);
    if (next === tab) return;
    // Park the outgoing tab's query before the box is handed to the incoming one.
    tabQuery[tab] = searchEl.value;
    tab = next;
    tabTrace.classList.toggle('on', next === 'trace');
    tabGraph.classList.toggle('on', next === 'graph');
    tabFlow.classList.toggle('on', next === 'flow');
    tabTimeline.classList.toggle('on', next === 'timeline');
    tabFindings.classList.toggle('on', next === 'findings');
    // The marker's rAF loop paints the flow canvas every frame; nothing needs that while
    // another tab is in front, and the canvas it targets may be mid-resize on the way back.
    if (next !== 'flow') stopChartAnim();
    tracePanel.hidden = next !== 'trace';
    graphPanel.hidden = next !== 'graph';
    flowPanel.hidden = next !== 'flow';
    timelinePanel.hidden = next !== 'timeline';
    findingsPanel.hidden = next !== 'findings';
    traceControls.hidden = next !== 'trace';
    graphControls.hidden = next !== 'graph';
    flowControls.hidden = next !== 'flow';
    timelineControls.hidden = next !== 'timeline';
    findingsControls.hidden = next !== 'findings';
    // The filter lives in the trace-point row, outside the panels, so one box can serve
    // every tab. It stays on all of them and carries that tab's own query and its own
    // description of what a match means there.
    var hint = TAB_SEARCH[next] || TAB_SEARCH.trace;
    searchEl.value = tabQuery[next] || '';
    searchEl.placeholder = hint.hint;
    searchEl.title = hint.title;
    searchEl.hidden = false;
    // Tabs are a radio group to a screen reader, and .on is a class it cannot see.
    [[tabTrace, 'trace'], [tabGraph, 'graph'], [tabFlow, 'flow'],
     [tabTimeline, 'timeline'], [tabFindings, 'findings']].forEach(function (p) {
      p[0].setAttribute('aria-selected', next === p[1] ? 'true' : 'false');
    });
    hideFlowTip();
    if (next === 'graph') {
      if (!graphBuilt) { graphBuilt = true; buildGraph(); }
      renderGraph();
    } else if (next === 'flow') {
      if (!flowBuilt) { flowBuilt = true; buildFlow(); }
      setFlowFilter(searchEl.value);
      drawFlow();
    } else if (next === 'timeline') {
      if (!timelineBuilt) { timelineBuilt = true; buildTimeline(); }
      renderTimeline();
    } else if (next === 'findings') {
      if (!findingsBuilt) { findingsBuilt = true; buildFindings(); }
      renderFindings();
    } else {
      applyFilters();   // restores the trace view's own count in the toolbar
    }
    rememberUrlState();
  }

  tabTrace.addEventListener('click', function () { setTab('trace'); });
  tabGraph.addEventListener('click', function () { setTab('graph'); });
  tabFlow.addEventListener('click', function () { setTab('flow'); });
  tabTimeline.addEventListener('click', function () { setTab('timeline'); });
  tabFindings.addEventListener('click', function () { setTab('findings'); });

  // ------------------------------------------------------------- timeline ---
  //
  // Where the wall clock actually went, as a waterfall in execution order.
  //
  // The Graph answers "which file", the Flow Graph answers "what called what". Neither
  // answers "what was the request doing at 300 ms", and that is the question you ask when
  // something is slow. Each row is one step, positioned along the run and split into the
  // time it spent in its own code and the time it spent inside the calls it made.
  //
  // HONEST LIMIT: the agent records how long each call took, not when it started, so a
  // step's position is derived by laying siblings end to end in the order they ran. That
  // is exact for the durations and for the ordering, and it cannot show a gap where the
  // request was idle between two calls, because nothing in the payload records one. Time
  // a frame did not spend in its children shows up as its own work, which is where the
  // useful answer usually is anyway.

  var TIMELINE_MAX_ROWS = 600;     // beyond this the page stops being a waterfall
  var TIMELINE_MIN_ROWS = 12;      // below this the threshold has told the reader nothing
  var TIMELINE_FALLBACK_ROWS = 60; // the biggest steps, when no threshold separates them
  var timelineBody = byId('timelineBody');
  var timelineRuler = byId('timelineRuler');
  var timelineEmpty = byId('timelineEmpty');
  var timelineNote = byId('timelineNote');
  var timelineAllBtn = byId('timelineAllBtn');
  var timelineFoldBtn = byId('timelineFoldBtn');
  var timelineRows = [];
  var timelineSpan = 0;
  var timelineAll = false;
  var collapsedTl = {};        // seq -> true, this step's callees folded away

  function buildTimeline() {
    if (!treeAvailable) {
      timelineEmpty.hidden = false;
      return;
    }
    var childTotal = {};
    calls.forEach(function (c) {
      if (c.parentSeq >= 0) {
        childTotal[c.parentSeq] = (childTotal[c.parentSeq] || 0) + (c.totalMicros || 0);
      }
    });

    // Pre-order with the same end-to-end packing the flame view uses, so the two agree
    // about where a step sits in the run.
    var cursor = [0];
    var stack = [];
    for (var i = roots.length - 1; i >= 0; i--) stack.push({ call: roots[i], depth: 0 });
    while (stack.length) {
      var it = stack.pop();
      var c = it.call;
      var d = it.depth;
      if (cursor[d] == null) cursor[d] = 0;
      var total = c.totalMicros || 0;
      timelineRows.push({
        seq: c.seq, depth: d, t0: cursor[d], total: total,
        own: Math.max(0, total - (childTotal[c.seq] || 0)),
        isSql: c.sql != null,
        // The class this step is filtered by. A query has none of its own, so it takes its
        // caller's and lives or dies with it. The Tree and the Flow Graph get that for free
        // by hiding whole subtrees; a flat list has to be told, and without this a hidden
        // repository left its five queries stranded on the timeline.
        cname: c.className || (nodeBySeq[c.parentSeq] && nodeBySeq[c.parentSeq].className) || null,
        excluded: !!foldableSeq[c.seq]
          || (c.sql != null && !!foldableSeq[c.parentSeq]),
        label: c.sql != null ? sqlLabel(c.sql)
          : (simpleName(c.className) + '.' + methodLabel(c.methodName, c.className) + '()'),
        sub: c.sql != null ? String(c.sql).replace(/\s+/g, ' ').trim()
          : (c.className || '')
      });
      cursor[d] = cursor[d] + total;
      cursor[d + 1] = timelineRows[timelineRows.length - 1].t0;
      var ch = childrenBySeq[c.seq] || [];
      for (var k = ch.length - 1; k >= 0; k--) stack.push({ call: ch[k], depth: d + 1 });
    }
    timelineSpan = 0;
    timelineRows.forEach(function (r) {
      if (r.t0 + r.total > timelineSpan) timelineSpan = r.t0 + r.total;
    });
  }

  function renderTimeline() {
    if (!treeAvailable || timelineRows.length === 0) {
      timelineEmpty.hidden = false;
      return;
    }
    timelineEmpty.hidden = true;
    timelineAllBtn.classList.toggle('on', timelineAll);
    syncExcludedButtons();

    // The file picker and the excluded types apply here too. They used to not, which made
    // this the one view that answered a question nobody had asked: the whole recording,
    // whatever you had told the report to leave out.
    var tq = queryFor('timeline').trim().toLowerCase();
    var eligible = timelineRows.filter(function (r) {
      if (r.cname && !classSelected(r.cname)) return false;
      if (tq && (r.label + ' ' + (r.sub || '')).toLowerCase().indexOf(tq) === -1) return false;
      return showExcluded || !r.excluded;
    });
    // Only the steps that own a slice of the clock, unless asked otherwise. A waterfall of
    // sixty thousand rows is not a waterfall, and the 4 µs accessors in it are noise.
    var floor = timelineAll ? 0 : Math.max(100, timelineSpan * 0.005);
    var shown = eligible.filter(function (r) { return r.own >= floor; });
    var topped = false;
    // A run with no single expensive step, thousands of small ones adding up, would leave
    // this blank, which reads as "no data" rather than "evenly spread". Fall back to the
    // biggest handful, still in execution order.
    if (!timelineAll && shown.length < TIMELINE_MIN_ROWS) {
      shown = eligible.slice()
        .sort(function (a, b) { return b.own - a.own; })
        .slice(0, TIMELINE_FALLBACK_ROWS)
        .filter(function (r) { return r.own > 0; })
        .sort(function (a, b) { return a.t0 - b.t0 || a.depth - b.depth; });
      topped = true;
    }
    var trimmed = shown.length > TIMELINE_MAX_ROWS;
    if (trimmed) shown = shown.slice(0, TIMELINE_MAX_ROWS);

    // Which listed steps have listed steps underneath them. Nesting is walked on the real
    // call tree, not on this list: the list is already thinned to the steps that own time,
    // so two rows sitting next to each other on screen are usually many frames apart in the
    // run, and the row above is very often not the caller.
    var listed = {};
    shown.forEach(function (r) { listed[r.seq] = true; });
    var hasKids = {};
    shown.forEach(function (r) {
      for (var p = ancestorSeq(r.seq); p >= 0; p = ancestorSeq(p)) {
        if (listed[p]) { hasKids[p] = true; break; }
      }
    });
    var visible = shown.filter(function (r) { return !foldedAbove(r.seq); });

    timelineNote.textContent = 'Steps laid out in the order they ran, over '
      + fmt(timelineSpan) + '. The solid part of each bar is time in that step\'s own '
      + 'code; the faint part is time inside the calls it made. '
      + (timelineAll ? ''
        : topped ? 'No single step dominates this run, so these are the '
            + shown.length + ' that own the most. '
        : 'Steps owning less than ' + fmt(floor) + ' are left out. ')
      + (trimmed ? 'Showing the first ' + TIMELINE_MAX_ROWS + '. ' : '')
      + 'Positions are derived from durations, so an idle gap cannot be shown.';
    timelineNote.hidden = false;

    var frag = document.createDocumentFragment();
    visible.forEach(function (r) {
      var row = el('div', 'tlrow' + (r.isSql ? ' sql' : ''));

      // The caret has a column of its own so a leaf and a parent still line their names up;
      // on a leaf it is present but invisible rather than absent.
      var kids = !!hasKids[r.seq];
      var caret = el('span', 'tlcaret' + (kids ? '' : ' leaf'),
        kids ? (collapsedTl[r.seq] ? '\u25b8' : '\u25be') : '\u25be');
      if (kids) {
        caret.title = collapsedTl[r.seq]
          ? 'Show the steps this one called' : 'Fold away the steps this one called';
        caret.addEventListener('click', function (ev) {
          ev.stopPropagation();
          if (collapsedTl[r.seq]) delete collapsedTl[r.seq];
          else collapsedTl[r.seq] = true;
          renderTimeline();
        });
      }
      row.appendChild(caret);

      // Toggles the full-text block: the formatted statement for a query, or the
      // fully-qualified signature for everything else, since the name column truncates
      // long ones. Shared by the name itself and its button, so clicking either does it —
      // the name is the bigger, easier target; the button is there so it can still be found
      // once "sql"/"expand" is the only label left, with no row of text around it.
      function toggleFull() {
        var already = row.querySelector('.tlfull');
        if (already) { row.removeChild(already); return; }
        if (r.isSql) {
          var pre = el('pre', 'tlfull');
          paintSql(pre, nodeBySeq[r.seq] && nodeBySeq[r.seq].sql);
          row.appendChild(pre);
        } else {
          var node = nodeBySeq[r.seq];
          var full = node
            ? (node.className || '') + '.' + methodLabel(node.methodName, node.className) + '()'
            : r.label;
          row.appendChild(el('pre', 'tlfull', full));
        }
      }

      var name = el('div', 'tlname');
      name.style.setProperty('--depth', Math.min(r.depth, 12));
      name.appendChild(document.createTextNode(r.label));
      name.title = r.sub + '\nstep ' + (r.seq + 1) + ' · starts at ' + fmt(r.t0)
        + ' · total ' + fmtPct(r.total) + ' · own ' + fmtPct(r.own);
      name.addEventListener('click', toggleFull);
      row.appendChild(name);

      // Two more things a reader wants from a row here: the line that made the call, and
      // the step itself in the tree. Kept beside the name rather than off at the far edge
      // of what is often a very wide row, so reaching them is not a trip across the bars
      // and times.
      var acts = el('div', 'tlacts');
      var url = callSiteUrl(r.seq);
      if (url) {
        var open = el('a', null, 'line');
        open.href = url;
        open.title = 'Open the calling line in the IDE';
        open.addEventListener('click', function (ev) { ev.stopPropagation(); });
        acts.appendChild(open);
      }
      var fullBtn = el('button', null, r.isSql ? 'sql' : 'expand');
      fullBtn.type = 'button';
      fullBtn.title = r.isSql
        ? 'Show the statement, formatted and coloured'
        : 'Show the full, fully-qualified line';
      fullBtn.addEventListener('click', function (ev) { ev.stopPropagation(); toggleFull(); });
      acts.appendChild(fullBtn);
      var step = el('button', null, 'step');
      step.type = 'button';
      step.title = 'Show this step in the Call Tree';
      step.addEventListener('click', function (ev) { ev.stopPropagation(); goToStep(r.seq); });
      acts.appendChild(step);
      row.appendChild(acts);

      var track = el('div', 'tltrack');
      var bar = el('i', 'tlbar');
      bar.style.left = pctWidth(r.t0, timelineSpan);
      bar.style.width = pctWidth(Math.max(r.total, timelineSpan / 2000), timelineSpan);
      var ownBar = el('i', 'tlown');
      ownBar.style.width = r.total > 0
        ? (Math.min(100, (r.own / r.total) * 100)).toFixed(3) + '%' : '100%';
      bar.appendChild(ownBar);
      track.appendChild(bar);
      row.appendChild(track);

      var time = el('div', 'tltime');
      time.appendChild(el('b', null, fmt(r.own)));
      time.appendChild(document.createTextNode(' of ' + fmt(r.total)));
      var op = pct(r.own);
      if (op) time.appendChild(el('span', 'fpct', op));
      row.appendChild(time);

      frag.appendChild(row);
    });

    // A scale the eye can read the bars against, at quarters of the run.
    timelineRuler.textContent = '';
    for (var q = 0; q <= 4; q++) {
      var tick = el('span', 'tltick', fmt(timelineSpan * q / 4));
      tick.style.left = (q * 25) + '%';
      timelineRuler.appendChild(tick);
    }

    timelineBody.textContent = '';
    timelineBody.appendChild(frag);
    countEl.textContent = visible.length + ' of ' + timelineRows.length
      + (timelineRows.length === 1 ? ' step' : ' steps');
  }

  /** A CSS width/offset as a percentage of the timeline's span. Not the report's pct(),
      which is a human-readable share of the run. */
  function pctWidth(value, span) {
    return span > 0 ? ((value / span) * 100).toFixed(4) + '%' : '0%';
  }

  function timelineAsText() {
    return timelineRows.filter(function (r) { return r.own > 0; })
      .map(function (r) {
        return fmt(r.t0) + '\t' + fmt(r.own) + ' own\t' + fmt(r.total) + ' total\t'
          + new Array(r.depth + 1).join('  ') + r.label;
      }).join('\n');
  }

  /** The caller of a step, by seq, or -1 at a root. */
  function ancestorSeq(seq) {
    var n = nodeBySeq[seq];
    return n && n.parentSeq != null ? n.parentSeq : -1;
  }

  /** Whether any caller of this step is folded, however far up the run it is. */
  function foldedAbove(seq) {
    for (var p = ancestorSeq(seq); p >= 0; p = ancestorSeq(p)) {
      if (collapsedTl[p]) return true;
    }
    return false;
  }

  /**
   * A jetbrains:// link to the line that made this call.
   *
   * <p>The caller's file, not the callee's, exactly as the tree's "called at :44" does: the
   * line worth opening is the one you would put a breakpoint on. A query has no call site
   * of its own, so it borrows the frame that issued it.
   */
  function callSiteUrl(seq) {
    var n = nodeBySeq[seq];
    if (!n) return null;
    if (n.sql != null) n = nodeBySeq[n.parentSeq] || n;
    if (n.callSiteLine == null) return null;
    var parent = nodeBySeq[n.parentSeq];
    var file = parent ? fileByClass[parent.className] : null;
    return file ? ideUrl(file.path, n.callSiteLine) : null;
  }

  timelineFoldBtn.addEventListener('click', function () {
    var collapse = timelineFoldBtn.textContent === 'Collapse all';
    collapsedTl = {};
    if (collapse) {
      // Every step that called something, except the roots: a fully folded waterfall with
      // no entry point left on it is a blank tab.
      timelineRows.forEach(function (r) {
        if (ancestorSeq(r.seq) >= 0) collapsedTl[r.seq] = true;
      });
    }
    timelineFoldBtn.textContent = collapse ? 'Expand all' : 'Collapse all';
    timelineFoldBtn.classList.toggle('on', collapse);
    renderTimeline();
  });

  timelineAllBtn.addEventListener('click', function () {
    timelineAll = !timelineAll;
    renderTimeline();
  });
  byId('timelineCopyBtn').addEventListener('click', function () {
    var btn = this;
    copyText(timelineAsText(), function (ok) { flash(btn, ok ? 'Copied' : 'Failed'); });
  });

  // ------------------------------------------------------------- findings ---
  //
  // The report shows you everything and leaves you to spot the problem. This tab does the
  // spotting: a handful of rules over the same payload, each one naming a concrete thing
  // in this run and linking to the step it happened at. Nothing here is a judgement about
  // your code, only an observation about what the recording contains, which is why every
  // card carries the numbers it was derived from.

  var findingsBody = byId('findingsBody');
  var findingsEmpty = byId('findingsEmpty');
  var findingsNote = byId('findingsNote');
  var findingsBadge = byId('findingsBadge');
  var findings = [];

  /** Repeats of one call worth mentioning, and queries worth mentioning, start here. */
  var FIND_REPEAT_CALLS = 20;      // same method this many times over
  var FIND_N_PLUS_ONE = 3;         // identical statements from one file
  var FIND_SHARE = 0.15;           // a step must own this share of the run to be "hot"

  function addFinding(sev, title, detail, seq) {
    findings.push({ sev: sev, title: title, detail: detail, seq: seq == null ? null : seq });
  }

  function buildFindings() {
    var runMicros = (data.durationMs || 0) * 1000;
    findNPlusOne();
    findSlowQueries(runMicros);
    findOwnTime(runMicros);
    findRepeatedCalls();
    findCoverageGaps();
    findRecordingLimits();
    // Worst first, and within a severity the order the rules ran, which is roughly the
    // order a reader would want to act in.
    var rank = { high: 0, med: 1, info: 2 };
    findings.sort(function (a, b) { return rank[a.sev] - rank[b.sev]; });
  }

  /** The same statement issued over and over from one file: a query inside a loop. */
  function findNPlusOne() {
    var groups = {};
    calls.forEach(function (c) {
      if (c.sql == null) return;
      var parent = nodeBySeq[c.parentSeq];
      var owner = parent && parent.className ? simpleName(parent.className) : 'an unknown caller';
      var key = owner + '\u0001' + String(c.sql).replace(/\s+/g, ' ').trim().toLowerCase();
      var g = groups[key] || (groups[key] = { owner: owner, n: 0, micros: 0, first: c.seq,
        sql: String(c.sql).replace(/\s+/g, ' ').trim() });
      g.n++;
      g.micros += c.totalMicros || 0;
    });
    Object.keys(groups).map(function (k) { return groups[k]; })
      .filter(function (g) { return g.n >= FIND_N_PLUS_ONE; })
      .sort(function (a, b) { return b.micros - a.micros; })
      .slice(0, 5)
      .forEach(function (g) {
        addFinding(g.n >= 10 ? 'high' : 'med',
          g.n + ' identical queries from ' + g.owner,
          'The same statement ran ' + g.n + ' times and cost ' + fmt(g.micros)
          + ' altogether. That is the shape of a query inside a loop; fetching the set in '
          + 'one statement would replace all ' + g.n + '.\n' + shorten(g.sql, 240),
          g.first);
      });
  }

  /** One query that is a large share of the whole request on its own. */
  function findSlowQueries(runMicros) {
    var worst = null;
    calls.forEach(function (c) {
      if (c.sql == null) return;
      if (!worst || (c.totalMicros || 0) > (worst.totalMicros || 0)) worst = c;
    });
    if (!worst || !worst.totalMicros) return;
    var share = runMicros > 0 ? worst.totalMicros / runMicros : 0;
    if (share < FIND_SHARE && worst.totalMicros < 50000) return;
    addFinding(share >= 0.3 ? 'high' : 'med',
      'One query took ' + fmt(worst.totalMicros),
      (runMicros > 0 ? 'That is ' + Math.round(share * 100) + '% of the whole request. ' : '')
      + 'Worth an execution plan before anything else in this trace.\n'
      + shorten(String(worst.sql).replace(/\s+/g, ' ').trim(), 240),
      worst.seq);
  }

  /**
   * A frame whose own code, not the calls it made, is where the time went.
   *
   * <p>Total time always favours whatever sits nearest the entry point, so it names the
   * controller in every trace and is useless. The gap between a frame's total and the sum
   * of its children is the part that is actually attributable to it.
   */
  function findOwnTime(runMicros) {
    var childTotal = {};
    calls.forEach(function (c) {
      if (c.parentSeq >= 0) {
        childTotal[c.parentSeq] = (childTotal[c.parentSeq] || 0) + (c.totalMicros || 0);
      }
    });
    var worst = null;
    calls.forEach(function (c) {
      if (c.sql != null) return;
      var own = (c.totalMicros || 0) - (childTotal[c.seq] || 0);
      if (own <= 0) return;
      if (!worst || own > worst.own) worst = { node: c, own: own };
    });
    if (!worst) return;
    var share = runMicros > 0 ? worst.own / runMicros : 0;
    if (share < FIND_SHARE && worst.own < 20000) return;
    addFinding(share >= 0.4 ? 'high' : 'med',
      fmt(worst.own) + ' spent inside ' + simpleName(worst.node.className) + '.'
      + methodLabel(worst.node.methodName, worst.node.className) + '()',
      'Time this frame spent in its own code rather than in anything it called'
      + (runMicros > 0 ? ', ' + Math.round(share * 100) + '% of the request' : '')
      + '. If you are looking for something to optimise, it is here, not in the callers '
      + 'above it whose totals merely contain it.',
      worst.node.seq);
  }

  /** One method called far more often than a reader would guess from the code. */
  function findRepeatedCalls() {
    var byMethod = {};
    calls.forEach(function (c) {
      if (c.sql != null || !c.className) return;
      var key = c.className + '#' + c.methodName;
      var m = byMethod[key] || (byMethod[key] = { n: 0, micros: 0, first: c.seq, node: c });
      m.n++;
      m.micros += c.totalMicros || 0;
    });
    Object.keys(byMethod).map(function (k) { return byMethod[k]; })
      .filter(function (m) { return m.n >= FIND_REPEAT_CALLS; })
      .sort(function (a, b) { return b.micros - a.micros; })
      .slice(0, 3)
      .forEach(function (m) {
        addFinding(m.n >= 200 ? 'med' : 'info',
          simpleName(m.node.className) + '.'
          + methodLabel(m.node.methodName, m.node.className) + '() ran ' + m.n + ' times',
          'Costing ' + fmt(m.micros) + ' in total. One request making the same call '
          + m.n + ' times is usually a loop that could hoist it, or a cache that is not '
          + 'being hit. Turn on <b>Group repeats</b> to see them folded together.',
          m.first);
      });
  }

  /** Branches never taken and lines never reached, in the code this request touched. */
  function findCoverageGaps() {
    if (counts.PARTIAL > 0) {
      addFinding('info', counts.PARTIAL + ' branch'
        + (counts.PARTIAL === 1 ? '' : 'es') + ' only went one way',
        'These lines ran, but at least one of their branches did not. In the code this '
        + 'request executed, that is the part no test of this path covers. Press '
        + '<b>x</b> for Problems only.', null);
    }
    if (counts.NONE > 0) {
      addFinding('info', counts.NONE + ' line'
        + (counts.NONE === 1 ? '' : 's') + ' inside executed methods never ran',
        'The method was entered and these lines were skipped, so they are guards, error '
        + 'paths, or dead code on this route.', null);
    }
  }

  /** Anything that makes the numbers above a lower bound rather than the truth. */
  function findRecordingLimits() {
    if (data.callsTruncated) {
      var gone = unrecordedPct();
      addFinding('high', gone
        ? 'The recording hit the agent\'s cap — ' + gone + ' of the run is missing'
        : 'The recording hit the agent\'s cap',
        (gone
          ? gone + ' of the run\'s time is not accounted for by any call below: the cap was '
            + 'reached before that work ran. Every total on this page is a lower bound. '
          : 'Later invocations were dropped, so every total on this page is a lower bound. ')
        + 'Narrow Includes, or put the trace point deeper in, and record again.', null);
    }
    if (data.excludedOmitted) {
      addFinding('info', 'Excluded types were exported without their source',
        'Their frames are still counted in the timings, but there is no code to expand '
        + 'them into. Re-export without "Essential" if you need to read them.', null);
    }
    var mismatch = agentMismatch();
    if (mismatch) addFinding('med', 'Agent and plugin versions differ', mismatch, null);
  }

  /** Trims a statement for a card. Named apart from the flow renderer's own clip(). */
  function shorten(text, n) {
    return text.length <= n ? text : text.slice(0, n - 1) + '…';
  }

  function renderFindings() {
    // The badge always counts every finding: it is a claim about the run, not about the
    // filter, and a badge that shrank as you typed would be reporting on the search box.
    findingsBadge.textContent = String(findings.length);
    findingsBadge.hidden = findings.length === 0;
    var fq = queryFor('findings').trim().toLowerCase();
    var shown = fq === '' ? findings : findings.filter(function (f) {
      return (f.title + ' ' + f.detail).toLowerCase().indexOf(fq) !== -1;
    });
    findingsEmpty.hidden = shown.length > 0;
    findingsEmpty.textContent = findings.length === 0
      ? 'Nothing stood out in this run.'
      : 'No finding matches that filter.';
    findingsNote.textContent = shown.length === 0 ? ''
      : (fq === '' ? '' : shown.length + ' of ' + findings.length + ' findings · ')
        + 'Derived from this recording alone. Each one links to the step it came from.';
    findingsNote.hidden = shown.length === 0;

    var frag = document.createDocumentFragment();
    shown.forEach(function (f) {
      var card = el('div', 'finding ' + f.sev);
      card.appendChild(el('span', 'fsev', f.sev === 'high' ? 'Look here'
        : (f.sev === 'med' ? 'Worth a look' : 'For information')));
      card.appendChild(el('h4', null, f.title));
      // The detail carries <b> from a couple of rules and a statement from others, so it
      // is split on the tags we emit ourselves and everything else is a text node. Traced
      // SQL never reaches innerHTML.
      var p = el('p');
      appendRich(p, f.detail);
      card.appendChild(p);
      if (f.seq != null) {
        var go = el('button', 'act', 'Show the step');
        go.type = 'button';
        go.addEventListener('click', function () { goToStep(f.seq); });
        card.appendChild(go);
      }
      frag.appendChild(card);
    });
    findingsBody.textContent = '';
    findingsBody.appendChild(frag);
    countEl.textContent = findings.length
      + (findings.length === 1 ? ' finding' : ' findings');
  }

  /**
   * Writes text that may contain the two tags the rules above emit, and nothing else.
   *
   * <p>Everything between them is a text node, so a class name or a statement that happens
   * to look like markup stays text, exactly as it does everywhere else in this report.
   */
  function appendRich(into, text) {
    String(text).split(/(<b>|<\/b>|\n)/).forEach(function (part) {
      if (part === '<b>') { into.appendChild(document.createElement('b')); return; }
      if (part === '</b>' || part === '') return;
      if (part === '\n') { into.appendChild(document.createElement('br')); return; }
      var last = into.lastChild;
      var target = (last && last.tagName === 'B' && !last.firstChild) ? last : into;
      target.appendChild(document.createTextNode(part));
    });
  }

  function findingsAsText() {
    return findings.map(function (f) {
      return '[' + f.sev.toUpperCase() + '] ' + f.title + '\n'
        + f.detail.replace(/<\/?b>/g, '') + '\n';
    }).join('\n');
  }

  byId('findingsCopyBtn').addEventListener('click', function () {
    var btn = this;
    copyText(findingsAsText(), function (ok) { flash(btn, ok ? 'Copied' : 'Failed'); });
  });

  // ---------------------------------------------------------------- graph ---

  var GRAPH_TOP = 40;          // rows drawn before "Show all files" appears
  var graphRows = [];
  var graphSql = true;
  var graphAll = false;
  // The Timers-panel read Glowroot opens on: the same bars, keyed by method instead of
  // file, so "which method is the bottleneck" doesn't require opening a file first to
  // find out it has six of them and only one is slow.
  var graphByMethod = false;
  var graphBody = byId('graphBody');
  var graphEmpty = byId('graphEmpty');
  var graphNote = byId('graphNote');
  var graphSqlBtn = byId('graphSqlBtn');
  var graphAllBtn = byId('graphAllBtn');
  var graphMethodBtn = byId('graphMethodBtn');

  // ---------------------------------------------------- graph name column resize ---
  //
  // The name column is a CSS grid track capped by --graph-name-w (report.css), so dragging
  // this handle is nothing but moving that one number; the handle itself just tracks where
  // the rendered column actually ended up, since the minmax() floor can still win on a
  // narrow window regardless of what was dragged.

  var GRAPH_NAME_W_KEY = 'deju.report.graphNameW';
  var GRAPH_NAME_MIN = 100;    // px
  var GRAPH_NAME_MAX = 480;    // px
  var graphList = byId('graphList');
  var graphResizer = byId('graphResizer');
  var graphDrag = null;

  function setGraphNameW(px) {
    var clamped = Math.max(GRAPH_NAME_MIN, Math.min(GRAPH_NAME_MAX, px));
    graphList.style.setProperty('--graph-name-w', clamped + 'px');
    return clamped;
  }

  function persistGraphNameW(px) {
    try { window.localStorage.setItem(GRAPH_NAME_W_KEY, String(px)); } catch (e) { /* no storage */ }
  }

  (function () {
    try {
      var stored = parseFloat(window.localStorage.getItem(GRAPH_NAME_W_KEY));
      if (stored > 0) setGraphNameW(stored);
    } catch (e) { /* no storage */ }
  }());

  /** Snaps the handle to the boundary the first row's name cell actually rendered at. */
  function positionGraphResizer() {
    var row = graphBody.firstElementChild;
    var cell = row && row.querySelector('.graphname');
    if (!cell) { graphResizer.style.display = 'none'; return; }
    graphResizer.style.display = '';
    var listRect = graphList.getBoundingClientRect();
    var cellRect = cell.getBoundingClientRect();
    graphResizer.style.left = (cellRect.right - listRect.left) + 'px';
  }

  graphResizer.addEventListener('pointerdown', function (ev) {
    var row = graphBody.firstElementChild;
    var cell = row && row.querySelector('.graphname');
    graphDrag = { startX: ev.clientX, startW: cell ? cell.getBoundingClientRect().width : GRAPH_NAME_MIN };
    graphResizer.setPointerCapture(ev.pointerId);
    graphResizer.classList.add('active');
    ev.preventDefault();
    graphResizer.focus();   // preventDefault above suppresses the mousedown's own focus step
  });
  graphResizer.addEventListener('pointermove', function (ev) {
    if (!graphDrag) return;
    setGraphNameW(graphDrag.startW + (ev.clientX - graphDrag.startX));
    positionGraphResizer();
  });
  function endGraphDrag(ev) {
    if (!graphDrag) return;
    persistGraphNameW(setGraphNameW(graphDrag.startW + (ev.clientX - graphDrag.startX)));
    graphDrag = null;
    graphResizer.classList.remove('active');
    positionGraphResizer();
  }
  graphResizer.addEventListener('pointerup', endGraphDrag);
  graphResizer.addEventListener('pointercancel', endGraphDrag);
  graphResizer.addEventListener('keydown', function (ev) {
    var cell = graphBody.firstElementChild && graphBody.firstElementChild.querySelector('.graphname');
    if (!cell) return;
    var step = 0;
    if (ev.key === 'ArrowLeft') step = -16;
    else if (ev.key === 'ArrowRight') step = 16;
    else return;
    ev.preventDefault();
    persistGraphNameW(setGraphNameW(cell.getBoundingClientRect().width + step));
    positionGraphResizer();
  });
  window.addEventListener('resize', function () { if (!graphPanel.hidden) positionGraphResizer(); });

  /**
   * Time per file, split into the file's own code and the SQL it caused.
   *
   * Only methodSelfMicros is additive. A method's total time contains everything it
   * called, so adding totals up would count the same microseconds once for every frame
   * on the stack and make whichever file sits nearest the entry point look like the
   * bottleneck. Self time alone, though, hides the database completely: a query's time
   * belongs to no file at all, because a SQL frame has no class. So each statement is
   * charged to the method that issued it, which is the line a reader would go and change.
   */
  /**
   * Charges one query to the row it belongs on, and grows that row's N+1 detector.
   * Identical between the two grouping modes — a repeated statement is a repeated
   * statement whether the bar above it says "OrderRepository.java" or "save()".
   */
  function chargeSql(r, c) {
    r.sql += c.totalMicros || 0;
    r.queries++;
    var key = String(c.sql).replace(/\s+/g, ' ').trim().toLowerCase();
    var g = r.dups[key];
    if (!g) g = r.dups[key] = { n: 0, micros: 0, sql: String(c.sql).replace(/\s+/g, ' ').trim() };
    g.n++;
    g.micros += c.totalMicros || 0;
  }

  /** The worst-repeat-per-row pass and the note line, shared by both grouping modes. */
  function finishGraphRows(orphanSql, unit) {
    var worst = null;
    graphRows.forEach(function (r) {
      Object.keys(r.dups).forEach(function (k) {
        var g = r.dups[k];
        if (g.n < 2) return;
        if (!r.dup || g.n > r.dup.n) r.dup = g;
        if (!worst || g.n > worst.g.n) worst = { g: g, label: r.label };
      });
    });

    var noteBits = [];
    if (worst) {
      noteBits.push('Possible N+1: ' + worst.g.n + ' identical queries from '
        + worst.label + ' costing ' + fmt(worst.g.micros) + ' in total.');
    }
    if (orphanSql > 0) {
      noteBits.push(fmt(orphanSql) + ' of SQL could not be charged to a ' + unit
        + ', its caller is not in this report.');
    }
    if (data.callsTruncated) {
      noteBits.push('The call list was truncated when this run was recorded, so SQL '
        + 'totals are a lower bound.');
    }
    graphNote.textContent = noteBits.join(' ');
    graphNote.hidden = noteBits.length === 0;
  }

  /**
   * Time per file, split into the file's own code and the SQL it caused.
   *
   * Only methodSelfMicros is additive. A method's total time contains everything it
   * called, so adding totals up would count the same microseconds once for every frame
   * on the stack and make whichever file sits nearest the entry point look like the
   * bottleneck. Self time alone, though, hides the database completely: a query's time
   * belongs to no file at all, because a SQL frame has no class. So each statement is
   * charged to the method that issued it, which is the line a reader would go and change.
   */
  function buildGraphByFile() {
    var byClass = {};
    files.forEach(function (f) { byClass[f.fqClassName] = f; });

    // Nested classes are separate entries in files[] but one file on disk, so they merge
    // here. Path is the identity when the plugin resolved one; two same-named files in
    // different packages must not collapse into a single bar.
    var rows = {};
    function rowFor(f) {
      var key = f.path || f.sourceFileName || f.fqClassName;
      var r = rows[key];
      if (!r) {
        r = rows[key] = {
          label: f.sourceFileName || simpleName(f.fqClassName) || key,
          sub: f.path || '',
          className: f.fqClassName || null,
          methodName: null,
          self: 0, total: 0, calls: 0,
          sql: 0,
          queries: 0,
          dups: {},      // normalised statement -> { n, micros, sql }
          dup: null      // the worst repeat in this file, once counted
        };
        graphRows.push(r);
      }
      return r;
    }

    files.forEach(function (f) {
      var r = rowFor(f);
      (f.lines || []).forEach(function (l) { r.self += l.methodSelfMicros || 0; });
    });

    var orphanSql = 0;
    calls.forEach(function (c) {
      if (!c.sql) return;
      var parent = nodeBySeq[c.parentSeq];
      var f = parent && parent.className ? byClass[parent.className] : null;
      // A query whose caller was excluded from the export, or trimmed by callsTruncated,
      // has no file to land on. Counting it against some other file would be a lie, so it
      // is reported separately instead.
      if (!f) { orphanSql += c.totalMicros || 0; return; }
      chargeSql(rowFor(f), c);
    });

    finishGraphRows(orphanSql, 'file');
  }

  /**
   * Time per method: the same bars as {@link buildGraphByFile}, but the row a reader
   * actually wants when a file turns out to have five methods and only one of them is
   * slow. Self and total both come straight off {@code linesByMethod}, already summed
   * across every invocation of that method by the agent (see Session.methodNanos); the
   * call count is the one thing not already there, so it is the only pass over calls[].
   */
  function buildGraphByMethod() {
    var rows = {};
    function rowFor(key, cls, mth) {
      var r = rows[key];
      if (!r) {
        r = rows[key] = {
          label: (mth || '?') + '()',
          sub: simpleName(cls) || cls || '',
          className: cls || null,
          methodName: mth || null,
          self: 0, total: 0, calls: 0,
          sql: 0,
          queries: 0,
          dups: {},
          dup: null
        };
        graphRows.push(r);
      }
      return r;
    }

    Object.keys(linesByMethod).forEach(function (key) {
      var hash = key.lastIndexOf('#');
      var cls = key.slice(0, hash);
      var mth = key.slice(hash + 1);
      var r = rowFor(key, cls, mth);
      linesByMethod[key].forEach(function (l) {
        if (l.methodSelfMicros != null) r.self = l.methodSelfMicros;
        if (l.methodTotalMicros != null) r.total = l.methodTotalMicros;
      });
    });

    var orphanSql = 0;
    calls.forEach(function (c) {
      if (c.sql) {
        var parent = nodeBySeq[c.parentSeq];
        var pkey = parent && parent.className && parent.methodName
          ? parent.className + '#' + parent.methodName : null;
        var pr = pkey ? rows[pkey] : null;
        if (!pr) { orphanSql += c.totalMicros || 0; return; }
        chargeSql(pr, c);
        return;
      }
      if (c.className == null || c.methodName == null) return;
      var r = rows[c.className + '#' + c.methodName];
      if (r) r.calls++;
    });

    finishGraphRows(orphanSql, 'method');
  }

  function buildGraph() {
    graphRows = [];
    if (graphByMethod) buildGraphByMethod();
    else buildGraphByFile();
  }

  function renderGraph() {
    var useSql = graphSql;
    var gq = queryFor('graph').trim().toLowerCase();
    var rows = graphRows.filter(function (r) {
      if (!((r.self + (useSql ? r.sql : 0)) > 0)) return false;
      // Name or package: on a run touching two hundred files the bar chart is a scroll,
      // and "which of my repositories cost what" is the question it is opened with.
      return gq === '' || (r.label + ' ' + (r.sub || '')).toLowerCase().indexOf(gq) !== -1;
    });
    rows.sort(function (a, b) {
      return (b.self + (useSql ? b.sql : 0)) - (a.self + (useSql ? a.sql : 0));
    });

    var unit = graphByMethod ? 'method' : 'file';

    graphEmpty.hidden = rows.length > 0;
    graphAllBtn.hidden = rows.length <= GRAPH_TOP;
    graphAllBtn.classList.toggle('on', graphAll);
    graphAllBtn.textContent = graphAll ? 'Show top ' + GRAPH_TOP : 'Show all ' + unit + 's';

    var shown = (graphAll || rows.length <= GRAPH_TOP) ? rows : rows.slice(0, GRAPH_TOP);
    var peak = 0;
    var grand = 0;
    rows.forEach(function (r) {
      var t = r.self + (useSql ? r.sql : 0);
      if (t > peak) peak = t;
      grand += t;
    });

    // One pass into a fragment: the panel is replaced wholesale rather than mutated, so a
    // metric flip cannot leave a stale row behind.
    var frag = document.createDocumentFragment();
    shown.forEach(function (r) {
      var total = r.self + (useSql ? r.sql : 0);
      var row = el('div', 'graphrow');

      var name = el('div', 'graphname');
      var label = el('span', 'graphlabel');
      label.appendChild(document.createTextNode(r.label));
      if (r.sub) {
        name.title = r.sub;
        label.appendChild(el('span', 'sub', r.sub));
      }
      name.appendChild(label);
      name.appendChild(copyButton(function () { return graphCopyText(r); },
        r.methodName ? 'Copy class and method name' : 'Copy class name'));
      row.appendChild(name);

      // Widths are percentages of the widest bar, so the longest row always fills the
      // track and the rest stay readable however small the absolute numbers are.
      var bar = el('div', 'graphbar');
      var selfPct = peak > 0 ? (r.self / peak) * 100 : 0;
      var sqlPct = peak > 0 && useSql ? (r.sql / peak) * 100 : 0;
      var bSelf = el('i', 'b-self');
      bSelf.style.width = selfPct.toFixed(3) + '%';
      bar.appendChild(bSelf);
      if (sqlPct > 0) {
        var bSql = el('i', 'b-sql');
        bSql.style.width = sqlPct.toFixed(3) + '%';
        bar.appendChild(bSql);
      }
      bar.title = useSql
        ? r.label + ' — own code ' + fmt(r.self) + ', SQL ' + fmt(r.sql)
        : r.label + ' — own code ' + fmt(r.self);
      row.appendChild(bar);

      var meta = el('div', 'graphmeta');
      if (graphByMethod && r.calls > 0) {
        meta.appendChild(el('span', 'qcount', r.calls + (r.calls === 1 ? ' call' : ' calls')));
      }
      if (r.queries > 0) {
        meta.appendChild(el('span', 'qcount', r.queries + (r.queries === 1 ? ' query' : ' queries')));
      }
      if (r.dup) {
        var pill = el('span', 'nplus1', '×' + r.dup.n);
        pill.title = r.dup.n + ' identical queries from this ' + unit + ', ' + fmt(r.dup.micros)
          + ' in total — often a query inside a loop:\n\n' + r.dup.sql;
        meta.appendChild(pill);
      }
      row.appendChild(meta);

      var time = el('div', 'graphtime');
      time.appendChild(el('b', null, fmt(total)));
      if (grand > 0) {
        time.appendChild(document.createTextNode(
          '  ' + ((total / grand) * 100).toFixed(1) + '%'));
      }
      // Total (inclusive) time only means something once it stands alone against this one
      // method's own self+SQL — summed across different methods on a stack it would double
      // count, which is exactly why the bar above never uses it.
      if (graphByMethod && r.total > total) {
        time.appendChild(el('span', 'gtotal', fmt(r.total) + ' total'));
      }
      row.appendChild(time);

      frag.appendChild(row);
    });

    graphBody.textContent = '';
    graphBody.appendChild(frag);
    countEl.textContent = rows.length + (rows.length === 1 ? ' ' + unit : ' ' + unit + 's')
      + ' · ' + fmt(grand) + ' attributed';
    positionGraphResizer();
  }

  /** What the row's copy button puts on the clipboard: the fully-qualified class, and the
   *  method too once the Breakdown is grouped by method rather than by file. */
  function graphCopyText(r) {
    var cls = r.className || r.sub || r.label;
    if (!r.methodName) return cls;
    return cls + '.' + methodLabel(r.methodName, r.className) + '()';
  }

  graphSqlBtn.addEventListener('click', function () {
    graphSql = !graphSql;
    graphSqlBtn.classList.toggle('on', graphSql);
    renderGraph();
  });
  graphAllBtn.addEventListener('click', function () {
    graphAll = !graphAll;
    renderGraph();
  });
  graphMethodBtn.addEventListener('click', function () {
    graphByMethod = !graphByMethod;
    graphMethodBtn.classList.toggle('on', graphByMethod);
    graphMethodBtn.textContent = graphByMethod ? 'By file' : 'By method';
    graphAll = false;
    buildGraph();
    renderGraph();
  });

  // ------------------------------------------------------------ flow graph ---

  var FLOW_ROW = 26;           // vertical pitch of one call
  var FLOW_BOX = 20;           // box height inside that pitch, in the Tree layout
  var FLAME_BOX = 25;          // row height in the Flame layout
  var FLOW_INDENT = 22;        // horizontal step per depth level
  var FLOW_PAD = 14;
  var FLOW_MINW = 150;
  var FLOW_MAXW = 460;
  var FLOW_GAP = 6;            // space between two boxes in tree mode
  var FLOW_LINE = 15;          // line height inside a multi-line SQL box
  var FLOW_SQL_FONT = '11px ui-monospace,SFMono-Regular,Menlo,monospace';
  /**
   * The face every frame label is drawn in.
   *
   * <p>Named rather than repeated, because a canvas font is a property of the context and
   * not of the call: a helper that sets its own and returns has changed the font for
   * everything drawn after it. The ruler and the query box both do exactly that, and both
   * run before the frames, so the labels used to come out in whichever face happened to be
   * left behind — 10px in the flame, 11px monospace in the tree.
   */
  var FLOW_FONT = '12px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif';
  var FLOW_RULER = 22;         // headroom above the flame stack for the time axis
  var FLOW_FLAME_W = 1100;     // width of the time axis in flame mode, before zoom
  var ZOOM_MIN = 0.4;
  var ZOOM_MAX = 3;
  var ZOOM_STEP = 1.25;

  var flowWrap = byId('flowWrap');
  var flowCanvas = null;      // created on first open of the tab, not on load
  var flowEmpty = byId('flowEmpty');
  var flowNote = byId('flowNote');
  var flowTip = byId('flowTip');
  var flowSqlBtn = byId('flowSqlBtn');
  var flowGroupBtn = byId('flowGroupBtn');
  var flowHotBtn = byId('flowHotBtn');
  var flowFlameBtn = byId('flowFlameBtn');
  var flowChartBtn = byId('flowChartBtn');
  var chartOrientBtn = byId('chartOrientBtn');
  var chartPlaySeg = byId('chartPlaySeg');
  var chartPlayBtn = byId('chartPlayBtn');
  var chartResetBtn = byId('chartResetBtn');
  var chartSpeedSel = byId('chartSpeedSel');
  var chartSpeedCustom = byId('chartSpeedCustom');
  var chartSpeedVal = byId('chartSpeedVal');
  var flowCrumb = byId('flowCrumb');
  var flowFitBtn = byId('flowFitBtn');
  var flowOutBtn = byId('flowOutBtn');
  var flowInBtn = byId('flowInBtn');
  var flowPngBtn = byId('flowPngBtn');
  var flowSvgBtn = byId('flowSvgBtn');
  var flowScopeSel = byId('flowScopeSel');
  var flowColorSel = byId('flowColorSel');

  var flowSpacer = null;       // sized to the whole diagram; the canvas is not
  var flowContentW = 0;        // diagram size in its own coordinates, pre-scale
  var flowContentH = 0;
  var drawnBySeq = {};         // seq -> drawn node, for connectors to reach an off-screen parent
  var flowNodes = [];          // every call, pre-order, with subtree spans
  var flowBySeq = {};          // seq -> node, for walking back up the zoom trail
  var flowVisible = [];        // what survives the SQL, fold and group filters
  var flowDrawn = [];          // what is actually painted: flowVisible, or one subtree
  var flowByDepth = {};        // drawn ROW -> nodes on it, for flame hit-testing and paint
  var flowIdxBySeq = {};       // seq -> index into flowNodes, for the filter's tree walks
  var flowParentIdx = null;    // index -> parent's index, or -1; built with the spans
  var flowKeep = null;         // index -> 1 when the pattern filter keeps this step
  var flowFilterText = '';
  var flowFilterLiteral = false;   // the pattern would not compile, so it is a substring
  var flowScope = 'sub';       // how a match pulls its neighbours in: sub | only | pkg
  var flowColorMode = 'glowroot';
  var flameRoot = null;        // seq the flame view is zoomed into, null for the whole run
  var collapsedFlow = {};      // seq -> true, subtree folded away by a click
  var flowSql = true;
  var flowGroup = true;
  var flowHot = false;
  /* Shared by the Flow Graph and the Timeline, so the two never disagree about whether the
     data classes are on screen. Off by default: excluding a type is a statement that it is
     noise, and a diagram is where noise costs the most. */
  var showExcluded = false;
  var flowExcludedBtn = byId('flowExcludedBtn');
  var timelineExcludedBtn = byId('timelineExcludedBtn');

  /**
   * Keeps both copies of the control saying the same thing.
   *
   * <p>Hidden outright when this project excludes nothing, for the same reason the Tree's
   * Essential/Full pair is: a switch between two identical views is a switch worth not
   * having. Also hidden when the export dropped the excluded source, since the frames are
   * still countable but there is nothing behind them to read.
   */
  function syncExcludedButtons() {
    [flowExcludedBtn, timelineExcludedBtn].forEach(function (b) {
      if (!b) return;
      b.hidden = !hasExclusions;
      b.classList.toggle('on', showExcluded);
      b.title = showExcluded
        ? 'Hide the types you excluded again'
        : 'Include the types you excluded (entities, DTOs and other data classes)';
    });
  }

  function setShowExcluded(on) {
    showExcluded = on;
    syncExcludedButtons();
    if (flowBuilt) drawFlow();
    if (timelineBuilt && tab === 'timeline') renderTimeline();
  }

  [flowExcludedBtn, timelineExcludedBtn].forEach(function (b) {
    if (b) b.addEventListener('click', function () { setShowExcluded(!showExcluded); });
  });
  var flowFlame = false;

  // -------------------------------------------------------------- API Flow chart ---
  // A separate, lightweight layout: one box per distinct call site (not per raw call,
  // which would be a hairball on any real trace), boxes arranged by nesting depth and
  // joined by their real caller/callee edges, coloured by the same coverage rollup the
  // Tree view uses. A marker walks the edges in actual execution order to animate it.
  var flowChart = false;
  var chartNodes = [];      // deduped: {key, cls, mth, isSql, sql, status, x,y,w,h, n, total, order, depth}
  var chartByKey = {};
  var chartEdges = [];      // {from, to, x1,y1,x2,y2}
  var chartPath = [];       // chartNodes entries, one per visit, in execution order (sampled if huge)
  var chartContentW = 0;
  var chartContentH = 0;
  var chartHoverKey = null;
  var chartPlaying = false;
  var chartPlayIdx = 0;
  var chartProgress = 0;    // 0..1 along the edge from chartPath[chartPlayIdx] to the next
  var chartSpeed = 1;
  var chartRAF = 0;
  var chartLastTick = 0;
  // Which step of this playback run reached a given node first, and how many distinct
  // nodes have been reached so far. Keyed by node key rather than kept on the node itself,
  // since buildChart() throws chartNodes away and rebuilds them on every redraw.
  var chartVisitOrder = {};
  var chartVisitCount = 0;
  var CHART_STEP_MS = 500;  // time to cross one edge at 1x speed
  var CHART_MAX_PATH = 4000; // playback is sampled down past this so it finishes in a sane time
  var CHART_PAD = 24;
  var CHART_NODE_W = 200;
  var CHART_NODE_H = 34;
  // Named for the two roles a gap plays, not for an axis, since the two orientations
  // swap which axis each one falls on. LEVEL_GAP separates one call depth from the next —
  // it carries the elbow an edge bends through, so it stays generous. SIB_GAP separates
  // boxes that never route anything (siblings, or two wrapped rows of them), so it can
  // stay tight even when a level has forty of them.
  var CHART_LEVEL_GAP = 64;
  var CHART_SIB_GAP = 16;
  var CHART_DOT_R = 6;
  var chartVertical = false;  // false: depth -> columns (left to right). true: depth -> rows (top to bottom).

  /**
   * Narrowest a flame frame may be drawn, in pixels.
   *
   * <p>One is the floor rather than zero because the alternative is a frame that cannot be
   * seen, hovered or clicked, which is indistinguishable from one the report decided not to
   * show. Larger settings trade the width-is-time reading for the guarantee that every
   * frame carries something readable; 34 is where a label fits.
   */
  var flameMinW = 1;
  var flameWidened = false;   // true once the floor has had to stretch the diagram
  var flowFit = true;
  var flowHover = null;        // seq under the cursor, for the highlight
  var flameSpan = 0;           // pixel width of the time axis, for the ruler
  var flameTotal = 0;          // micros that width represents
  var flowZoom = 1;
  var flowScale = 1;
  var flowBoxW = 320;
  var flowRootTotal = 0;

  /** 32-bit djb2, used only to tell two subtrees apart, never to identify one on its own. */
  function flowHash(s) {
    var h = 5381;
    for (var i = 0; i < s.length; i++) h = ((h << 5) + h + s.charCodeAt(i)) | 0;
    return h;
  }

  /**
   * Flattens the call tree into a pre-order array with subtree sizes.
   *
   * Re-uses the roots, children and hot path the Tree view already computed rather than
   * walking calls[] a second time: two copies of the same structure is two things to keep
   * agreeing with each other. Pre-order means array order IS execution order, and the
   * span on each node makes "skip this subtree" a single addition rather than a search.
   */
  function buildFlow() {
    if (!treeAvailable) {
      flowEmpty.hidden = false;
      flowWrap.hidden = true;
      return;
    }
    // An explicit stack, not recursion: a deeply nested trace would otherwise risk
    // overflowing the JS stack inside a file the reader has no way to re-export.
    var stack = [];
    for (var i = roots.length - 1; i >= 0; i--) stack.push({ call: roots[i], depth: 0 });
    while (stack.length) {
      var it = stack.pop();
      var c = it.call;
      flowNodes.push({
        seq: c.seq,
        parentSeq: c.parentSeq,
        depth: it.depth,
        isSql: !!c.sql,
        total: c.totalMicros || 0,
        cls: c.sql ? '' : simpleName(c.className),
        mth: c.sql ? sqlLabel(c.sql) : ('.' + (c.methodName || '?') + '()'),
        sig: c.sql ? String(c.sql)
          : ((c.className || '') + '.' + (c.methodName || '?') + '()'),
        sql: c.sql ? String(c.sql) : null,
        site: c.callSiteLine,
        // Who the box belongs to, which is what its colour is derived from. A query is
        // charged to its caller so a repository and its statements share a hue.
        owner: c.className || (nodeBySeq[c.parentSeq] && nodeBySeq[c.parentSeq].className) || '',
        // The class as the Files picker knows it, kept raw for the selection test. A query
        // has none of its own; it lives or dies with the frame that issued it.
        cname: c.sql ? null : (c.className || null),
        // Identity for "these two frames are the same call made twice".
        key: c.sql ? ('q:' + String(c.sql).replace(/\s+/g, ' ').trim())
          : ('m:' + (c.className || '') + '#' + (c.methodName || '')),
        // Queries are laid out on their clause lines in the box itself rather than
        // abbreviated to a hover: the statement IS the step, and a select trimmed at
        // forty characters tells you nothing about the where clause that made it slow.
        // Uncapped: the box that draws these grows to fit them (nodeHeight), so cutting
        // the list here would hide clauses the layout had already made room for.
        sqlLines: c.sql
          ? formatSqlLines(tokenizeSql(splitSqlLead(c.sql).sql))
          : null,
        span: 1
      });
      var ch = childrenBySeq[c.seq] || [];
      for (var k = ch.length - 1; k >= 0; k--) {
        stack.push({ call: ch[k], depth: it.depth + 1 });
      }
    }
    // Subtree sizes, backwards. Each node hops its direct children by their own spans,
    // which are already final, so the whole pass is linear rather than quadratic. Shape is
    // built the same way, one hop per direct child: two calls only merge into one flame box
    // when they are the same method AND did the same thing underneath, so a loop that calls
    // one method down two different branches stays two boxes rather than an averaged one.
    for (var j = flowNodes.length - 1; j >= 0; j--) {
      var n = flowNodes[j];
      var t = j + 1;
      var s = 1;
      var shape = flowHash(n.key);
      while (t < flowNodes.length && flowNodes[t].depth > n.depth) {
        shape = ((shape << 5) - shape + flowNodes[t].shape) | 0;
        s += flowNodes[t].span;
        t += flowNodes[t].span;
      }
      n.span = s;
      n.shape = shape;
      flowBySeq[n.seq] = n;
      flowIdxBySeq[n.seq] = j;
    }
    // Parent by index rather than by seq, so the filter's two passes are array reads. In
    // pre-order a parent always sits at a lower index than its children, which is what lets
    // "any descendant matched" and "any ancestor matched" each be a single linear sweep.
    flowParentIdx = new Int32Array(flowNodes.length);
    for (var pi = 0; pi < flowNodes.length; pi++) {
      var pp = flowIdxBySeq[flowNodes[pi].parentSeq];
      flowParentIdx[pi] = pp === undefined ? -1 : pp;
    }

    flowRootTotal = roots.reduce(function (m, x) {
      return Math.max(m, x.totalMicros || 0);
    }, 0);

    computeFlowStats();

    // The canvas is made here rather than shipped in the markup. A report that is never
    // opened on this tab should not carry the element, and one opened on it pays for the
    // element once; either way the exported file is that much smaller.
    //
    // The spacer sits between the two: it is sized to the whole diagram so the scrollbar
    // describes the real thing, while the canvas stays the size of the visible area.
    flowSpacer = document.createElement('div');
    flowSpacer.id = 'flowSpacer';
    flowCanvas = document.createElement('canvas');
    flowCanvas.id = 'flowCanvas';
    flowSpacer.appendChild(flowCanvas);
    flowWrap.appendChild(flowSpacer);
    flowWrap.addEventListener('scroll', paintFlow);

    flowCanvas.addEventListener('mousemove', onFlowMove);
    flowCanvas.addEventListener('mouseleave', hideFlowTip);
    flowCanvas.addEventListener('click', onFlowClick);
    flowWrap.addEventListener('scroll', hideFlowTip);
  }

  /**
   * The line above the diagram, rewritten on every draw.
   *
   * It has to be, now that the file picker can remove steps: a count fixed at build time
   * would keep claiming the whole run while the reader looks at a filtered slice of it, and
   * a number that disagrees with what is on screen is worse than no number.
   */
  function updateFlowNote() {
    if (flowChart) { updateChartNote(); return; }
    var shown = flowVisible.length;
    // Deliberately not naming a cause: SQL, grouping, a fold and the file picker can each
    // take steps out, often at the same time, and guessing which would sometimes be wrong.
    var bits = [shown === flowNodes.length
      ? shown + ' steps in execution order'
      : shown + ' of ' + flowNodes.length + ' steps shown'];
    if (!showExcluded && hasExclusions) {
      bits.push('excluded types are folded away');
    }
    if (flowFilterText.trim()) {
      bits.push('filtered by ' + (flowFilterLiteral ? 'text' : 'pattern')
        + ' "' + flowFilterText.trim() + '"'
        + (flowScope === 'only' ? ', matches and their callers'
          : flowScope === 'pkg' ? ', matched on package names'
          : ', with their callers and callees'));
    }
    if (data.callsTruncated) {
      var lost = unrecordedPct();
      bits.push(lost
        ? 'the recording was capped, and ' + lost + ' of the run is not on this diagram'
        : 'the recording truncated the call list, so the tail is missing');
    }
    // Said plainly, because it changes how the picture must be read: once a frame has been
    // widened to reach the floor, a wide box is no longer necessarily a slow one. The
    // percentage on each box still is, which is why it stays.
    if (flowFlame && flameWidened) {
      bits.push('every frame widened to at least ' + flameMinW + 'px, so width is no longer'
        + ' time — read the percentages, not the boxes');
    }
    flowNote.textContent = bits.join(' · ') + '. '
      + (flowFlame ? 'Click a step to zoom into it; callers are at the bottom.'
        : 'Click a step to fold its calls away.');
  }

  /**
   * First few words of a statement: enough to tell queries apart in a small box.
   *
   * <p>The driver's provenance comment is dropped first. Forty-four characters of
   * "-- FooServiceImpl L27 -- BarController L20" is a label that identifies nothing, and
   * it is the same label on every query the class issued.
   */
  function sqlLabel(sql) {
    var s = splitSqlLead(sql).sql.replace(/\s+/g, ' ').trim();
    return s.length > 44 ? s.slice(0, 44) + '…' : s;
  }

  /**
   * Decides what is on screen: one pass over the pre-order array.
   *
   * Four things can remove a node. An unticked file takes its frame and everything under
   * it, exactly as the Tree view does; hiding SQL drops those leaves; a collapsed node keeps
   * itself but skips its subtree; and grouping folds a run of identical siblings into the
   * first of them. Repeats are worth folding because the shape they make, the same call
   * five times over, is exactly the shape of an N+1, and five near-identical boxes say it
   * far less clearly than one box marked ×5.
   */
  /**
   * The pattern filter: which steps survive a regex typed into the filter box.
   *
   * <p>The Flow Graph is the one tab that cannot be read by scrolling. A run of two hundred
   * thousand steps is a wall, and the reader almost always arrives knowing roughly what
   * they are looking for — a repository, a package, a method name. This turns that into
   * the question the diagram answers.
   *
   * <p>Three scopes, because a match on its own is not usually what you want to see. The
   * default keeps a match, everything it went on to call, and the chain of callers above
   * it: a subtree hanging off nothing would be a diagram that no longer says how the code
   * got there. "Matches only" drops the callees for when the shape of the calls into a
   * layer is the question. "Whole package" matches on the package rather than the
   * signature, which is the grouping a layered codebase is actually navigated by.
   *
   * <p>An invalid pattern filters by substring instead of failing. Typing {@code get(} into
   * a box is not a request for a syntax error.
   */
  function computeFlowFilter() {
    flowKeep = null;
    flowFilterLiteral = false;
    var q = flowFilterText.trim();
    if (!q || !flowNodes.length) return;

    var re;
    try {
      re = new RegExp(q, 'i');
    } catch (err) {
      flowFilterLiteral = true;
      re = new RegExp(q.replace(/[.*+?^${}()|[\]\\]/g, '\\$&'), 'i');
    }

    var n = flowNodes.length;
    var self = new Uint8Array(n);
    var up = new Uint8Array(n);      // this step, or something under it, matched
    for (var i = 0; i < n; i++) {
      var node = flowNodes[i];
      var hay = flowScope === 'pkg' ? packageOf(node.owner) : (node.sig || '');
      if (re.test(hay)) { self[i] = 1; up[i] = 1; }
    }
    // Backwards: a parent is always at a lower index, so one sweep carries every match up
    // to the root.
    for (var j = n - 1; j > 0; j--) {
      if (up[j] && flowParentIdx[j] >= 0) up[flowParentIdx[j]] = 1;
    }
    if (flowScope === 'only') { flowKeep = up; return; }
    // Forwards for the other direction: everything under a match comes with it.
    var down = new Uint8Array(n);
    for (var k = 0; k < n; k++) {
      down[k] = self[k] || (flowParentIdx[k] >= 0 ? down[flowParentIdx[k]] : 0);
      if (down[k]) up[k] = 1;
    }
    flowKeep = up;
  }

  /**
   * What each method and each statement cost across the whole run.
   *
   * <p>A frame on the diagram is one invocation, and one invocation is rarely the question
   * being asked of it. "8.1 ms" beside a repository lookup says nothing useful until you
   * know it is the first of five and that the five come to 12.7 ms — a quarter of the
   * request. The five boxes are scattered across the diagram and four of them are too
   * narrow to label, so adding them up is not something the reader can do by looking.
   *
   * <p>Counted over every call in the trace rather than over what is currently drawn.
   * Filtering the diagram is a question about where to look, not a claim that the rest of
   * the run stopped happening, and a total that moved when the filter changed would be the
   * second kind of number while looking like the first.
   */
  var flowStats = {};

  function computeFlowStats() {
    flowStats = {};
    var childTotals = {};
    var i;
    var n;
    for (i = 0; i < flowNodes.length; i++) {
      n = flowNodes[i];
      if (n.parentSeq >= 0) {
        childTotals[n.parentSeq] = (childTotals[n.parentSeq] || 0) + n.total;
      }
    }
    for (i = 0; i < flowNodes.length; i++) {
      n = flowNodes[i];
      var s = flowStats[n.key];
      if (!s) s = flowStats[n.key] = { n: 0, total: 0, self: 0, max: 0, times: [] };
      s.n++;
      s.total += n.total;
      // Clamped: a parent whose children were measured across a clock edge can come out a
      // microsecond short of them, and a negative "own time" is not a thing to report.
      s.self += Math.max(0, n.total - (childTotals[n.seq] || 0));
      if (n.total > s.max) s.max = n.total;
      s.times.push(n.total);
    }
  }

  /**
   * The middle call of a set, worked out on demand.
   *
   * <p>Deliberately not the mean. The interesting shape here is a first call that pays for
   * a cache or a connection and four that do not, and a mean quietly splits that difference
   * into a number describing neither. Sorted on first ask and kept, because the hover card
   * asks for the same method repeatedly and almost never for most of them.
   */
  function flowMedian(s) {
    if (s.median == null) {
      s.times.sort(function (a, b) { return a - b; });
      var m = s.times.length >> 1;
      s.median = s.times.length % 2
        ? s.times[m]
        : Math.round((s.times[m - 1] + s.times[m]) / 2);
    }
    return s.median;
  }

  function computeFlowVisible() {
    var out = [];
    var i = 0;
    while (i < flowNodes.length) {
      var n = flowNodes[i];
      // Nothing under a dropped step can have matched — "kept" already propagated upward
      // from every match — so its whole subtree goes with it.
      if (flowKeep && !flowKeep[flowIdxBySeq[n.seq]]) { i += n.span; continue; }
      // Unticking a file hides its calls and everything they went on to do, which is what
      // the same picker already means in the Tree. Dropping the frame but keeping its
      // children would leave callees floating under a caller that is no longer drawn.
      if (n.cname && !classSelected(n.cname)) { i += n.span; continue; }
      // Excluded types, on the same terms the Tree's Essential view folds them: only when
      // the whole subtree is excluded, so a getter that turned out to trigger a lazy load
      // stays on the diagram along with the query it caused. Whole subtrees, so nothing is
      // ever left hanging under a caller that is no longer drawn.
      if (!showExcluded && foldableSeq[n.seq]) { i += n.span; continue; }
      if (!flowSql && n.isSql) { i += n.span; continue; }

      var reps = 1;
      var sum = n.total;
      var j = i + n.span;
      if (flowGroup) {
        while (j < flowNodes.length) {
          var m = flowNodes[j];
          // Adjacent in pre-order at the same depth under the same parent is exactly
          // "next sibling", so no sibling list lookup is needed. Shape, not just key: two
          // calls to the same method only fold into one box when they did the same thing
          // underneath, so a loop that branches internally stays visible as separate calls
          // rather than being averaged into a single misleading box.
          if (m.depth !== n.depth || m.parentSeq !== n.parentSeq || m.key !== n.key
              || m.shape !== n.shape) break;
          if (!flowSql && m.isSql) break;
          reps++;
          sum += m.total;
          j += m.span;
        }
      }
      n.reps = reps;
      n.shown = sum;
      n.hasKids = n.span > 1;
      out.push(n);

      if (reps > 1) i = j;                              // the run, subtrees and all
      else if (collapsedFlow[n.seq]) i += n.span;       // folded by a click
      else i += 1;                                      // descend
    }
    return out;
  }

  /**
   * Flame layout: depth down the page, time across it.
   *
   * Each node occupies its own slice of its parent's span, laid out in call order, so a
   * wide box is a slow one and the widest chain from top to bottom is where the request
   * actually went. One pass is enough: in pre-order a node is always visited after its
   * parent, so the parent has already set the cursor its children start from.
   */
  function layoutFlame(vis) {
    var cursor = [0];
    for (var i = 0; i < vis.length; i++) {
      var n = vis[i];
      var d = n.depth;
      if (cursor[d] == null) cursor[d] = 0;
      n.t0 = cursor[d];
      cursor[d] = n.t0 + n.shown;   // the next sibling starts where this one ends
      cursor[d + 1] = n.t0;         // this node's children start where it starts
    }
  }

  /**
   * Flame widths in pixels, with a floor no frame may fall below.
   *
   * <p>Time alone cannot lay this out. A frame's share of the run spans six orders of
   * magnitude — a 1 µs getter inside a 103 s request is a millionth of a pixel — and the
   * agent records nothing at all under a microsecond, so a proportional layout hands some
   * frames a width of exactly zero. Flooring only the width, which is what this used to do,
   * leaves the positions proportional: 199 frames on one row of a real trace were all
   * assigned their own pixel and then drawn on top of each other at 27 distinct positions.
   * A frame nobody can see or hover is a frame the diagram has hidden.
   *
   * <p>So the floor is applied to the layout instead of to the drawing. A parent is at
   * least as wide as its children laid side by side, which is what keeps a widened frame
   * from spilling out from under the call that made it, and the extra width propagates up
   * the tree rather than overlapping anything.
   *
   * <p>The cost is honest and worth stating: above the floor a box is still time, but a
   * frame that had to be widened to reach it is not. That is why the ruler is dropped and
   * the note says so whenever the floor is doing any work.
   *
   * @return the width of the whole diagram, in pixels
   */
  function layoutFlamePx(drawn, baseDepth, baseT0, total, span, floor) {
    var len = drawn.length;
    if (!len) return 0;
    var pw = new Float64Array(len);
    var kids = new Float64Array(len);
    var parent = new Int32Array(len);
    var stack = [];
    var i;
    var d;

    // Pre-order means a parent is always seen before its children, so the nearest entry one
    // level up is the parent and a single sweep finds every one of them.
    for (i = 0; i < len; i++) {
      d = drawn[i].depth - baseDepth;
      stack[d] = i;
      parent[i] = d > 0 && stack[d - 1] != null ? stack[d - 1] : -1;
    }

    // Backwards, so a node's children have all reported in before it is sized.
    for (i = len - 1; i >= 0; i--) {
      var prop = total > 0 ? (drawn[i].shown / total) * span : span;
      var w = Math.max(floor, prop, kids[i]);
      pw[i] = w;
      if (parent[i] >= 0) kids[parent[i]] += w;
    }

    // Forwards again, each node starting where its parent's previous child finished.
    var pen = new Float64Array(len);
    var widest = 0;
    for (i = 0; i < len; i++) {
      var p = parent[i];
      var x;
      if (p < 0) {
        // A root keeps the place time gave it, so several roots stay in call order.
        x = total > 0 ? ((drawn[i].t0 - baseT0) / total) * span : 0;
      } else {
        x = pen[p];
        pen[p] += pw[i];
      }
      pen[i] = x;
      drawn[i].x = FLOW_PAD + x;
      drawn[i].w = pw[i];
      if (x + pw[i] > widest) widest = x + pw[i];
    }
    return widest;
  }

  function flowColors() {
    // Read from the stylesheet rather than hardcoded, so one theme toggle repaints the
    // canvas in the new palette with no second source of truth for the colours.
    var cs = getComputedStyle(document.documentElement);
    function v(n, fallback) { return (cs.getPropertyValue(n) || fallback).trim(); }
    // Which way the page is themed decides how a class tint has to be mixed: a wash that
    // reads as a tint on white turns to mud on near-black. Taken from the resolved
    // background rather than a media query, so the in-page toggle counts too.
    var bg = v('--bg', '#fff');
    var m = /^#?([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(bg.replace('#', '#'));
    var dark = m
      ? (parseInt(m[1], 16) * 0.299 + parseInt(m[2], 16) * 0.587 + parseInt(m[3], 16) * 0.114) < 128
      : false;
    return {
      dark: dark,
      bg: bg,
      fg: v('--fg', '#1f2328'),
      muted: v('--muted', '#656d76'),
      line: v('--border', '#d0d7de'),
      box: v('--control', '#fff'),
      accent: v('--accent', '#4a90d9'),
      cls: v('--flow-cls', '#2e9e4f'),
      mth: v('--flow-mth', '#2f6fd0'),
      sql: v('--sql-ink', '#ff9933'),
      hoverFill: dark ? 'rgba(255,255,255,0.16)' : 'rgba(255,255,255,0.28)',
      badge: v('--badge-bg', '#ff9933'),
      heat: v('--heat', 'rgba(74,144,217,.18)'),
      // Same tri-colour the Tree view marks line coverage with, reused so a step's colour
      // in the API Flow chart means the same thing it does everywhere else in the report.
      full: v('--full', '#2da44e'),
      partial: v('--partial', '#bf8700'),
      none: v('--none', '#cf4a4a'),
      // Statement syntax, the same tokens the tree colours a query with.
      tok: {
        kw: v('--k-kw', '#9b2393'),
        str: v('--k-str', '#c41a16'),
        num: v('--k-num', '#1c00cf'),
        cmt: v('--k-cmt', '#5d6c79'),
        ph: v('--k-ann', '#8a5a1a')
      }
    };
  }

  /**
   * The worst line status inside one method: NONE beats PARTIAL beats FULL. A query has
   * no lines of its own and is coloured separately, so this is never asked about one.
   *
   * <p>A node with no line data at all (source not exported, or the method has no lines
   * the coverage pass tracked) defaults to FULL rather than NONE: it is on the executed
   * call graph, so it ran, and "not touched" would say the opposite of what happened.
   */
  function rollupStatus(className, methodName) {
    if (!className || !methodName) return 'FULL';
    var lines = linesByMethod[className + '#' + methodName];
    if (!lines || !lines.length) return 'FULL';
    var worst = 'FULL';
    for (var i = 0; i < lines.length; i++) {
      var st = lines[i].status || 'NONE';
      if (st === 'NONE') return 'NONE';
      if (st === 'PARTIAL') worst = 'PARTIAL';
    }
    return worst;
  }

  /**
   * Collapses {@link flowVisible} into the API Flow chart's graph: one node per distinct
   * call site (the same {@code key} {@link buildFlow} already dedupes repeats with), one
   * edge per distinct caller/callee pair, and the full visit sequence for the animation to
   * walk. Rebuilt on every draw rather than cached: the node count is small (call sites,
   * not calls), so redoing it is cheap next to what re-filtering the tree already costs.
   */
  function buildChart() {
    chartByKey = {};
    chartNodes = [];
    chartEdges = [];
    chartPath = [];
    var edgeSeen = {};
    var order = 0;
    for (var i = 0; i < flowVisible.length; i++) {
      var n = flowVisible[i];
      var cn = chartByKey[n.key];
      if (!cn) {
        var raw = nodeBySeq[n.seq] || {};
        cn = chartByKey[n.key] = {
          key: n.key, isSql: n.isSql, sql: n.sql, cls: n.cls, mth: n.mth,
          className: n.cname, methodName: raw.methodName,
          status: n.isSql ? null : rollupStatus(n.cname, raw.methodName),
          depth: n.depth, order: order++, firstSeq: n.seq,
          n: 0, total: 0, recursive: false
        };
        chartNodes.push(cn);
      }
      cn.n++;
      cn.total += n.total || 0;
      chartPath.push(cn);
      var pn = flowBySeq[n.parentSeq];
      if (pn) {
        if (pn.key === n.key) {
          cn.recursive = true;
        } else if (chartByKey[pn.key]) {
          var ek = pn.key + '>' + n.key;
          if (!edgeSeen[ek]) { edgeSeen[ek] = true; chartEdges.push({ from: pn.key, to: n.key }); }
        }
      }
    }
    // Sampled evenly rather than truncated, so a slow, rare tail step still gets a turn
    // instead of the animation only ever showing the first slice of a huge run.
    if (chartPath.length > CHART_MAX_PATH) {
      var sampled = [];
      var step = chartPath.length / CHART_MAX_PATH;
      for (var p = 0; p < CHART_MAX_PATH; p++) sampled.push(chartPath[Math.floor(p * step)]);
      sampled.push(chartPath[chartPath.length - 1]);
      chartPath = sampled;
    }
    chartPlayIdx = Math.min(chartPlayIdx, Math.max(0, chartPath.length - 1));
    chartProgress = 0;
  }

  /** Wipes the numbered trail a previous playback left, for a fresh run to write over. */
  function resetChartVisits() {
    chartVisitOrder = {};
    chartVisitCount = 0;
  }

  /** Gives the node at this step its number, the first time playback ever reaches it. */
  function markChartVisit(idx) {
    var node = chartPath[idx];
    if (!node || chartVisitOrder[node.key] != null) return;
    chartVisitCount++;
    chartVisitOrder[node.key] = chartVisitCount;
  }

  /**
   * Two synthetic bookends, added after every rebuild: not calls, so they carry no timing
   * or coverage and never enter the playback path, but the depth given to each — one
   * below the shallowest real step, one past the deepest — drops it into its own column
   * or band in either orientation for free, on exactly the same layout code as everything
   * else. Start points at every depth-0 step (normally just the trace point); End
   * gathers every step nothing calls onward from, however many there turn out to be.
   */
  function addChartTerminals() {
    var hasOutgoing = {};
    var maxDepth = 0;
    chartEdges.forEach(function (e) { hasOutgoing[e.from] = true; });
    chartNodes.forEach(function (n) { if (n.depth > maxDepth) maxDepth = n.depth; });

    var start = { key: '__start__', term: 'start', label: 'Start', depth: -1, order: 0,
                  n: 0, total: 0, calls: 0, isSql: false, recursive: false };
    var end = { key: '__end__', term: 'end', label: 'End', depth: maxDepth + 1, order: 0,
                n: 0, total: 0, calls: 0, isSql: false, recursive: false };
    chartByKey[start.key] = start;
    chartByKey[end.key] = end;

    chartNodes.forEach(function (n) {
      if (n.depth === 0) chartEdges.push({ from: start.key, to: n.key });
      if (!hasOutgoing[n.key]) chartEdges.push({ from: n.key, to: end.key });
    });
    chartNodes.unshift(start);
    chartNodes.push(end);
  }

  function layoutChart(avail) {
    if (chartVertical) layoutChartVertical(avail);
    else layoutChartHorizontal();
  }

  /**
   * Positions the chart's nodes: columns by first-seen call depth, rows by first-seen
   * order within the column. No crossing-minimisation or force layout, deliberately: the
   * node count here is call sites, not calls, and a simple deterministic grid is what
   * "very lightweight" means for a diagram this small.
   *
   * <p>Depth drives the axis the viewport can't grow past its own width in without
   * shrinking every label to fit — a call tree is rarely more than a handful of levels
   * deep, however many methods live at any one of them. Siblings stack downward instead,
   * where forty repositories at the same depth just means a taller diagram to scroll,
   * never a narrower box to read.
   */
  function layoutChartHorizontal() {
    var cols = {};
    chartNodes.forEach(function (n) {
      (cols[n.depth] || (cols[n.depth] = [])).push(n);
    });
    var colDepths = Object.keys(cols).map(Number).sort(function (a, b) { return a - b; });
    var maxBottom = 0;
    var x = CHART_PAD;
    colDepths.forEach(function (depth) {
      var list = cols[depth].sort(function (a, b) { return a.order - b.order; });
      var y = CHART_PAD;
      list.forEach(function (n) {
        n.x = x; n.y = y; n.w = CHART_NODE_W; n.h = CHART_NODE_H;
        y += CHART_NODE_H + CHART_SIB_GAP;
      });
      if (y > maxBottom) maxBottom = y;
      x += CHART_NODE_W + CHART_LEVEL_GAP;
    });
    chartContentW = x - CHART_LEVEL_GAP + CHART_PAD;
    chartContentH = maxBottom - CHART_SIB_GAP + CHART_PAD;
    chartEdges.forEach(function (e) {
      var a = chartByKey[e.from];
      var b = chartByKey[e.to];
      if (!a || !b) return;
      e.x1 = a.x + a.w; e.y1 = a.y + a.h / 2;
      e.x2 = b.x; e.y2 = b.y + b.h / 2;
    });
  }

  /**
   * The top-to-bottom alternative: bands by call depth running down the page instead of
   * across it, with a real flowchart's Start/End at the ends. Depth no longer buys the
   * crowding fix on its own — a band can still hold forty siblings — so a band wraps onto
   * more rows once it would run past the viewport, exactly the way this chart used to
   * grow sideways before the horizontal layout replaced it.
   *
   * <p>Two passes rather than one: every row in a band centres on the width of the widest
   * row anywhere in the diagram, and that width isn't known until every band has been
   * measured. Getting that wrong would centre each row on its own width instead, leaving a
   * ladder of boxes that all drift sideways against each other rather than reading as one
   * diagram.
   */
  function layoutChartVertical(avail) {
    var bands = {};
    chartNodes.forEach(function (n) {
      (bands[n.depth] || (bands[n.depth] = [])).push(n);
    });
    var depths = Object.keys(bands).map(Number).sort(function (a, b) { return a - b; });
    var usableW = Math.max(CHART_NODE_W, (avail || 900) - CHART_PAD * 2);
    var perRow = Math.max(1, Math.floor((usableW + CHART_SIB_GAP) / (CHART_NODE_W + CHART_SIB_GAP)));

    var plan = [];
    var maxRowW = CHART_NODE_W;
    depths.forEach(function (depth) {
      var list = bands[depth].sort(function (a, b) { return a.order - b.order; });
      var rowCount = Math.ceil(list.length / perRow);
      for (var r = 0; r < rowCount; r++) {
        var count = Math.min(perRow, list.length - r * perRow);
        var w = count * CHART_NODE_W + (count - 1) * CHART_SIB_GAP;
        if (w > maxRowW) maxRowW = w;
      }
      plan.push({ list: list, rowCount: rowCount });
    });

    var y = CHART_PAD;
    plan.forEach(function (band) {
      for (var i = 0; i < band.list.length; i++) {
        var row = Math.floor(i / perRow);
        var col = i % perRow;
        var thisRowCount = Math.min(perRow, band.list.length - row * perRow);
        var thisRowW = thisRowCount * CHART_NODE_W + (thisRowCount - 1) * CHART_SIB_GAP;
        var startX = CHART_PAD + (maxRowW - thisRowW) / 2;
        var n = band.list[i];
        n.x = startX + col * (CHART_NODE_W + CHART_SIB_GAP);
        n.y = y + row * (CHART_NODE_H + CHART_SIB_GAP);
        n.w = CHART_NODE_W; n.h = CHART_NODE_H;
      }
      y += band.rowCount * CHART_NODE_H + (band.rowCount - 1) * CHART_SIB_GAP + CHART_LEVEL_GAP;
    });

    chartContentW = maxRowW + CHART_PAD * 2;
    chartContentH = y - CHART_LEVEL_GAP + CHART_PAD;
    chartEdges.forEach(function (e) {
      var a = chartByKey[e.from];
      var b = chartByKey[e.to];
      if (!a || !b) return;
      e.x1 = a.x + a.w / 2; e.y1 = a.y + a.h;
      e.x2 = b.x + b.w / 2; e.y2 = b.y;
    });
  }

  /** chartNodes minus Start/End, which are bookends, not steps this run took. */
  function chartStepCount() {
    return Math.max(0, chartNodes.length - 2);
  }

  function updateChartNote() {
    var stepCount = chartStepCount();
    var bits = [stepCount + ' distinct step' + (stepCount === 1 ? '' : 's')
      + ' from ' + flowVisible.length + ' calls in this run'];
    if (chartPath.length < flowVisible.length) {
      bits.push('playback sampled to ' + chartPath.length + ' points so it stays watchable');
    }
    flowNote.textContent = bits.join(' · ') + '. '
      + 'Click a step to jump to it in the Call Tree; use Play to animate the run.';
  }

  /**
   * Categorical fills for flame frames, one slot per owner.
   *
   * <p>Three schemes rather than one, because the diagram gets read two ways. Glowroot's
   * warm ramp is what a JVM developer recognises on sight, so it is the default; the Deju
   * scheme is the report's own palette, for a screenshot that has to sit beside the
   * product; "by package" gives up per-class identity for per-layer identity, which is the
   * only honest answer once a run touches two hundred classes and no palette can name them
   * all.
   *
   * <p>SQL keeps a fill of its own in every scheme, and in the warm ones it is deliberately
   * the only cool colour on the canvas. Telling a query apart from a method is the one
   * distinction this diagram cannot afford to lose, and a warm ramp has no shade left over
   * that could carry it.
   *
   * <p>Slots are handed out in first-appearance order, so a class keeps its colour whatever
   * the filters hide. Past the eight base fills the scheme cycles through three lightness
   * steps of itself rather than stopping: thirty-two distinguishable fills, and only then
   * grey. The previous six-slot cap was principled about never recycling a hue, but on a
   * real trace it painted the seventh class onward — nearly all of them — the same grey,
   * which loses far more than a repeated colour at two different lightnesses ever did.
   */
  var FLAME_SCHEMES = {
    glowroot: {
      light: ['#e8703a', '#d9534f', '#ec9a3c', '#c0392b', '#e2854a', '#b7472a',
              '#f0a95c', '#9c3520'],
      dark:  ['#f0844f', '#e06a64', '#f2ab55', '#d1503f', '#ea9462', '#c85a3c',
              '#f5bb74', '#b8543c'],
      sqlLight: '#3d6ea8', sqlDark: '#6f9fdd'
    },
    deju: {
      light: ['#2f6fd0', '#2e9e4f', '#c9457a', '#6c5ce7', '#0f9b9b', '#d94a3d',
              '#8a6d3b', '#4a3aa7'],
      dark:  ['#5b93e0', '#4bb56a', '#df6a99', '#8f81f0', '#2bb3b3', '#e8695c',
              '#b8955c', '#9085e9'],
      // Deju's own SQL ink is already the brand orange and is nowhere in the eight above,
      // so the tree's colour carries straight over.
      sqlLight: null, sqlDark: null
    }
  };
  var FLAME_OTHER_LIGHT = '#8b8f94';   // past every cycle, deliberately colourless
  var FLAME_OTHER_DARK = '#6b7075';
  var FLAME_CYCLES = [0, 0.30, -0.26, 0.55];   // lightness steps applied per wrap

  var ownerSlot = {};
  var ownerSlotCount = 0;

  /** Resets slot assignment: a scheme change has to re-deal from the top of the new list. */
  function resetFlameSlots() {
    ownerSlot = {};
    ownerSlotCount = 0;
  }

  /** Lifts a fill toward white (positive) or down toward black (negative). */
  function shade(hex, amt) {
    if (!amt) return hex;
    var m = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(hex);
    if (!m) return hex;
    var out = '#';
    for (var i = 1; i <= 3; i++) {
      var v = parseInt(m[i], 16);
      v = amt >= 0 ? v + (255 - v) * amt : v * (1 + amt);
      out += ('0' + Math.max(0, Math.min(255, Math.round(v))).toString(16)).slice(-2);
    }
    return out;
  }

  /** The package a class belongs to, which is what "by package" colours by. */
  function packageOf(fq) {
    var i = String(fq || '').lastIndexOf('.');
    return i > 0 ? fq.slice(0, i) : (fq || '(default)');
  }

  function flameScheme() {
    return FLAME_SCHEMES[flowColorMode === 'deju' ? 'deju' : 'glowroot'];
  }

  function classFill(name, dark) {
    var slot = ownerSlot[name];
    if (slot === undefined) slot = ownerSlot[name] = ownerSlotCount++;
    var sc = flameScheme();
    var pal = dark ? sc.dark : sc.light;
    var cycle = Math.floor(slot / pal.length);
    if (cycle >= FLAME_CYCLES.length) return dark ? FLAME_OTHER_DARK : FLAME_OTHER_LIGHT;
    return shade(pal[slot % pal.length], FLAME_CYCLES[cycle]);
  }

  /** What a frame is coloured by: its class normally, its package in "by package" mode. */
  function fillKey(n) {
    return flowColorMode === 'pkg' ? packageOf(n.owner) : (n.owner || '');
  }

  /**
   * Ink that survives its own background.
   *
   * With solid fills the label can no longer wear the class and method colours the tree
   * uses; on #008300 they would be invisible. Perceived luminance picks near-black or
   * white per box, which is the only way one text rule works across six fills.
   */
  function inkOn(fill) {
    var m = /^#([0-9a-f]{2})([0-9a-f]{2})([0-9a-f]{2})$/i.exec(fill);
    if (!m) return '#ffffff';
    var lum = parseInt(m[1], 16) * 0.299 + parseInt(m[2], 16) * 0.587
      + parseInt(m[3], 16) * 0.114;
    return lum > 150 ? '#12161a' : '#ffffff';
  }

  /**
   * The time axis across the top of the flame view, with its gridlines.
   *
   * Width means duration in this layout and nothing else on screen says how much, so the
   * ruler is what turns "that box is wide" into "that box is 27 ms". Steps are the usual
   * 1/2/5 progression, chosen so roughly seven of them fit.
   */
  function drawRuler(ctx, c, span, total, height) {
    if (total <= 0 || span <= 0) return;
    var raw = total / 7;
    var mag = Math.pow(10, Math.floor(Math.log(raw) / Math.LN10));
    var norm = raw / mag;
    var step = (norm >= 5 ? 5 : norm >= 2 ? 2 : 1) * mag;

    ctx.font = '10px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif';
    ctx.textBaseline = 'middle';
    for (var t = 0; t <= total + 1e-9; t += step) {
      var x = FLOW_PAD + (t / total) * span;
      ctx.strokeStyle = c.line;
      ctx.globalAlpha = 0.35;
      ctx.beginPath();
      ctx.moveTo(Math.round(x) + 0.5, FLOW_PAD + FLOW_RULER - 6);
      ctx.lineTo(Math.round(x) + 0.5, height - FLOW_PAD);
      ctx.stroke();
      ctx.globalAlpha = 1;
      ctx.fillStyle = c.muted;
      var label = fmt(t);
      var w = ctx.measureText(label).width;
      // The last tick would hang off the right edge, so it tucks back inside.
      ctx.fillText(label, Math.min(x + 3, FLOW_PAD + span - w), FLOW_PAD + 6);
    }
  }

  function drawFlow() {
    if (!treeAvailable || flowNodes.length === 0 || !flowCanvas) return;
    flowVisible = computeFlowVisible();
    updateFlowNote();

    // Filtering can empty the diagram completely, and unticking the entry point alone does
    // it, since its subtree is the whole run. A blank canvas looks like a broken report, so
    // say what happened and how to undo it.
    if (flowVisible.length === 0) {
      flowEmpty.textContent = flowFilterText.trim()
        ? 'No step matches ' + (flowFilterLiteral ? 'the text' : 'the pattern')
          + ' "' + flowFilterText.trim() + '". Clear the filter box, or widen it with the '
          + 'dropdown beside the tabs.'
        : 'Every step is filtered out.'
          + ' Re-tick files in the Files picker, or turn SQL back on.';
      flowEmpty.hidden = false;
      flowWrap.hidden = true;
      return;
    }
    flowEmpty.hidden = true;
    flowWrap.hidden = false;
    syncExcludedButtons();
    flowByDepth = {};
    // Cleared per draw: the tree layout never sets it, and a stale true would leave the
    // note claiming a floor that only the flame applies.
    flameWidened = false;
    var maxDepth = 0;
    flowVisible.forEach(function (n) { if (n.depth > maxDepth) maxDepth = n.depth; });

    var avail = Math.max(320, flowWrap.clientWidth || 900);
    var contentW;
    var contentH;

    if (flowChart) {
      buildChart();
      addChartTerminals();
      layoutChart(avail);
      flowDrawn = chartNodes;
      contentW = chartContentW;
      contentH = chartContentH;
    } else if (flowFlame) {
      layoutFlame(flowVisible);

      // Zoomed into a frame: a subtree is contiguous in pre-order, so the focused run is
      // a slice, and re-basing depth and time onto it is all "make this the new 100%"
      // means.
      var drawn = flowVisible;
      var baseDepth = 0;
      var baseT0 = 0;
      var total = flowRootTotal;
      if (flameRoot != null) {
        var fi = -1;
        for (var i = 0; i < flowVisible.length; i++) {
          if (flowVisible[i].seq === flameRoot) { fi = i; break; }
        }
        if (fi < 0) {
          // The focused frame was filtered out from under us, by hiding SQL or grouping.
          flameRoot = null;
        } else {
          var f = flowVisible[fi];
          var end = fi + 1;
          while (end < flowVisible.length && flowVisible[end].depth > f.depth) end++;
          drawn = flowVisible.slice(fi, end);
          baseDepth = f.depth;
          baseT0 = f.t0;
          total = f.shown || 1;
        }
      }

      // Zoom stretches the time axis rather than the canvas. Scaling the whole canvas
      // would shrink the labels along with the boxes, which defeats zooming out to
      // compare frames: the shape survives and the names become unreadable.
      var span = flowFit ? (avail - FLOW_PAD * 2)
        : Math.max(FLOW_FLAME_W, avail - FLOW_PAD * 2) * flowZoom;
      var maxD = 0;
      drawn.forEach(function (n) {
        var dd = n.depth - baseDepth;
        if (dd > maxD) maxD = dd;
      });
      // x and w together, because a floor on one without the other is what let frames pile
      // up on a single pixel. Returns how wide the diagram came out: at the smallest floor
      // that is the span it was given, and at larger ones it is however much room every
      // frame needed to be visible.
      var laidW = layoutFlamePx(drawn, baseDepth, baseT0, total, span, flameMinW);
      flameWidened = laidW > span + 0.5;
      drawn.forEach(function (n) {
        var d = n.depth - baseDepth;
        // Flame, not icicle: the caller sits at the BOTTOM and the stack grows upward, the
        // way Glowroot, async-profiler and every other flame graph a JVM developer has read
        // draws it. Depth is inverted into a row rather than used as one, so the deepest
        // frame lands on row 0 at the top and the entry point holds the floor. Rows touch;
        // a gap between levels would break the column the eye follows from a callee back
        // down to whatever called it.
        var row = maxD - d;
        n.y = FLOW_PAD + FLOW_RULER + row * FLAME_BOX;
        n.h = FLAME_BOX;
        // Keyed by drawn row, not by depth: hit-testing and painting both work in screen
        // rows, and after the flip those are no longer the same number.
        (flowByDepth[row] || (flowByDepth[row] = [])).push(n);
      });
      flowDrawn = drawn;
      indexDrawn();
      flameSpan = span;
      flameTotal = total;
      contentW = FLOW_PAD * 2 + Math.max(span, laidW);
      contentH = FLOW_PAD * 2 + FLOW_RULER + (maxD + 1) * FLAME_BOX;
    } else {
      var slack = avail - FLOW_PAD * 2 - maxDepth * FLOW_INDENT;
      flowBoxW = Math.max(FLOW_MINW, Math.min(FLOW_MAXW, slack));
      // Heights vary now that a query occupies its clause lines, so rows are stacked by
      // running total rather than by a fixed pitch.
      var y = FLOW_PAD;
      flowVisible.forEach(function (n) {
        n.x = FLOW_PAD + n.depth * FLOW_INDENT;
        n.w = flowBoxW;
        n.h = nodeHeight(n);
        n.y = y;
        y += n.h + FLOW_GAP;
      });
      flowDrawn = flowVisible;
      indexDrawn();
      contentW = FLOW_PAD + maxDepth * FLOW_INDENT + flowBoxW + FLOW_PAD;
      contentH = y - FLOW_GAP + FLOW_PAD;
    }

    // Flame already spent the zoom on its time axis, so the canvas itself stays 1:1 there.
    flowScale = flowFlame ? 1
      : (flowFit && contentW > avail ? avail / contentW : flowZoom);
    // Again, now that the layout has run: whether the width floor had to stretch anything
    // is not known until the frames have been sized, and it is the note's job to say so.
    updateFlowNote();
    renderCrumb();

    flowContentW = contentW;
    flowContentH = contentH;
    // The scrollbar has to describe the whole diagram even though the canvas never is it.
    flowSpacer.style.width = Math.ceil(contentW * flowScale) + 'px';
    flowSpacer.style.height = Math.ceil(contentH * flowScale) + 'px';

    // The count sits in the trace-point row, which is outside the panels and so stays on
    // screen across tabs. Only the tab actually in front may write to it: drawFlow also
    // runs whenever a file filter changes, so once the Flow tab had been opened the Code
    // Trace counter was overwritten with a step count on every keystroke.
    if (tab === 'flow') {
      countEl.textContent = flowChart
        ? 'Showing ' + chartStepCount() + ' distinct step'
          + (chartStepCount() === 1 ? '' : 's') + ' in this flow'
        : 'Showing ' + flowDrawn.length + ' of ' + flowNodes.length
          + (flowNodes.length === 1 ? ' step' : ' steps');
    }

    paintFlow();
  }

  /**
   * Paints the part of the diagram that is actually on screen.
   *
   * <p>Split from the layout above because the two run at very different rates: layout
   * changes when a filter or a fold does, painting happens on every scroll and every hover.
   * Re-deriving 200,000 node positions to move a highlight was affordable at 36 steps and
   * is not at 200,000.
   *
   * <p>It is also the only way the diagram can exist at that size at all. A canvas is capped
   * at 65535px in each direction, which at one 26px row per step is about 2,500 steps, or
   * half that on a retina display where every CSS pixel is two device pixels. Past it the
   * browser accepts the size and hands back a canvas that silently draws nothing. Sizing to
   * the viewport instead keeps the canvas a few hundred pixels tall however long the run is.
   */
  /**
   * Device pixels to put behind each CSS pixel of canvas.
   *
   * <p>{@code devicePixelRatio} is not a floor. Zoom a browser below 100% and it drops with
   * the zoom — 0.8 at 80%, 0.67 at 67% — so the backing store comes out smaller than the box
   * it is stretched across and every label on the diagram is upscaled from fewer pixels than
   * it was drawn with. Text is what shows it first, which is why the flame went soft in
   * Chrome on Windows at 80% and nowhere else.
   *
   * <p>Capped as well as floored: a 3x phone painting a wide flame at full ratio buys
   * sharpness nobody can see with memory that is very much felt.
   */
  function flowDpr() {
    return Math.min(Math.max(window.devicePixelRatio || 1, 1), 3);
  }

  function paintFlow() {
    if (!flowCanvas || flowContentH === 0) return;

    var viewW = flowWrap.clientWidth || 1;
    var viewH = flowWrap.clientHeight || 1;
    var sx = flowWrap.scrollLeft;
    var sy = flowWrap.scrollTop;

    // Pinned back over the viewport it just scrolled away from.
    flowCanvas.style.left = sx + 'px';
    flowCanvas.style.top = sy + 'px';

    var dpr = flowDpr();
    var needW = Math.ceil(viewW * dpr);
    var needH = Math.ceil(viewH * dpr);
    // Assigning width/height clears the canvas, so it is only done when it really changed.
    if (flowCanvas.width !== needW || flowCanvas.height !== needH) {
      flowCanvas.width = needW;
      flowCanvas.height = needH;
      flowCanvas.style.width = viewW + 'px';
      flowCanvas.style.height = viewH + 'px';
    }

    var ctx = flowCanvas.getContext('2d');
    // The scroll offset lives in the transform, so everything below still draws in the
    // diagram's own coordinates and knows nothing about scrolling.
    ctx.setTransform(dpr * flowScale, 0, 0, dpr * flowScale,
                     -sx * dpr, -sy * dpr);
    renderFlow(ctx, sx / flowScale, sy / flowScale,
               (sx + viewW) / flowScale, (sy + viewH) / flowScale);
  }

  /**
   * Draws the diagram between two content rows onto whatever context is handed in.
   *
   * <p>Shared by the on-screen paint, which passes the viewport, and the PNG export, which
   * passes the whole thing. One renderer, so the image saved is the image seen.
   */
  function renderFlow(ctx, x0, y0, x1, y1) {
    var c = flowColors();
    // Painted rather than cleared: a transparent canvas exports to a PNG that is
    // unreadable on anything but the theme it was taken in.
    ctx.fillStyle = c.bg;
    ctx.fillRect(x0, y0, x1 - x0, y1 - y0);

    ctx.font = FLOW_FONT;
    ctx.textBaseline = 'middle';

    if (flowChart) {
      renderChartFrame(ctx, c, x0, y0, x1, y1);
      return;
    }

    if (flowFlame) {
      // Only while the axis still means something. Once the floor has widened a frame past
      // its share, position no longer maps to time, and a ruler would be a scale printed
      // against distances it does not describe.
      if (!flameWidened) drawRuler(ctx, c, flameSpan, flameTotal, flowContentH);
      // Flame rows are a fixed pitch and every node on one is already indexed by that row,
      // so the band on screen is arithmetic and only the nodes actually in it are touched.
      // This used to walk the whole drawn array on every scroll and every hover, which on a
      // 200,000-step run meant 200,000 rejections per repaint to move one highlight.
      var r0 = Math.max(0, Math.floor((y0 - FLOW_PAD - FLOW_RULER) / FLAME_BOX));
      var r1 = Math.floor((y1 - FLOW_PAD - FLOW_RULER) / FLAME_BOX);
      for (var r = r0; r <= r1; r++) {
        var list = flowByDepth[r];
        if (!list) continue;
        for (var k = 0; k < list.length; k++) {
          var fn = list[k];
          // Horizontal culling too: zoomed in, most of a row is off to one side.
          if (fn.x > x1 || fn.x + fn.w < x0) continue;
          drawNode(ctx, c, fn);
        }
      }
      return;
    }

    drawConnectors(ctx, c, y0, y1);
    var slice = visibleSlice(y0, y1);
    for (var j = slice.from; j < slice.to; j++) {
      var n = flowDrawn[j];
      if (n.y > y1 || n.y + n.h < y0) continue;
      drawNode(ctx, c, n);
    }
  }

  /**
   * The index range of {@link flowDrawn} that can appear between two content rows.
   *
   * <p>Tree mode only: rows are stacked in order there, so the range is a bisection. Flame
   * mode packs a whole depth onto one row and is not sorted by y at all, so it is painted
   * from {@link flowByDepth} instead, which indexes exactly that.
   */
  function visibleSlice(y0, y1) {
    var lo = 0;
    var hi = flowDrawn.length;
    while (lo < hi) {
      var mid = (lo + hi) >> 1;
      if (flowDrawn[mid].y + flowDrawn[mid].h < y0) lo = mid + 1;
      else hi = mid;
    }
    var from = lo;
    hi = flowDrawn.length;
    while (lo < hi) {
      var m2 = (lo + hi) >> 1;
      if (flowDrawn[m2].y <= y1) lo = m2 + 1;
      else hi = m2;
    }
    return { from: from, to: lo };
  }

  /** seq -> drawn node. Built once per layout, not once per paint. */
  function indexDrawn() {
    drawnBySeq = {};
    for (var i = 0; i < flowDrawn.length; i++) drawnBySeq[flowDrawn[i].seq] = flowDrawn[i];
  }

  function drawConnectors(ctx, c, y0, y1) {
    ctx.strokeStyle = c.line;
    ctx.lineWidth = 1;
    ctx.beginPath();
    // Elbows are drawn for the rows on screen, but a parent well above the fold still owns
    // the line coming down to its child, so the parent is looked up rather than scanned to.
    var slice = visibleSlice(y0, y1);
    for (var i = slice.from; i < slice.to; i++) {
      var n = flowDrawn[i];
      if (n.y > y1 || n.y + n.h < y0) continue;
      var parent = drawnBySeq[n.parentSeq];
      // Anchored to the real parent, not to the row above: the second and later children
      // of a call are separated from it by their siblings' whole subtrees, so a spine
      // drawn one row up would sprout from an unrelated box.
      if (n.depth === 0 || !parent) continue;
      var px = FLOW_PAD + parent.depth * FLOW_INDENT + 9;
      var my = n.y + Math.min(FLOW_BOX, n.h || FLOW_BOX) / 2;
      // Half-pixel offsets keep a 1px line on one device pixel rather than smeared
      // across two.
      ctx.moveTo(Math.round(px) + 0.5, Math.round(parent.y + (parent.h || FLOW_BOX)) + 0.5);
      ctx.lineTo(Math.round(px) + 0.5, Math.round(my) + 0.5);
      ctx.lineTo(Math.round(FLOW_PAD + n.depth * FLOW_INDENT) + 0.5, Math.round(my) + 0.5);
    }
    ctx.stroke();
  }

  /** Tall enough for a statement's clause lines; one row otherwise. */
  function nodeHeight(n) {
    if (flowFlame || !n.sqlLines || n.sqlLines.length < 2) return FLOW_BOX;
    return 9 + n.sqlLines.length * FLOW_LINE;
  }

  function drawNode(ctx, c, n) {
    var hot = flowHot && hotSeqs[n.seq];
    var over = flowHover === n.seq;
    var h = n.h || FLOW_BOX;
    // Flame boxes touch, so square them off; rounded corners on a solid stack would
    // punch holes along every seam.
    var radius = flowFlame ? 2 : 5;
    ctx.fillStyle = c.box;
    roundRect(ctx, n.x, n.y, n.w, h, radius);
    ctx.fill();
    // Flame only. There the boxes touch and most are too narrow to label, so colour is
    // the only thing telling one class from the next. The tree layout already separates
    // frames by indent and gives every one of them a readable name, so a wash there would
    // be decoration competing with the coverage colours the report uses on code rows.
    var ink = null;
    if (flowFlame) {
      var sc = flameScheme();
      var sqlFill = c.dark ? (sc.sqlDark || c.sql) : (sc.sqlLight || c.sql);
      var fill = n.isSql ? sqlFill : classFill(fillKey(n), c.dark);
      ctx.fillStyle = fill;
      ctx.fill();
      ink = inkOn(fill);
    }
    if (over) {
      ctx.fillStyle = c.hoverFill;
      ctx.fill();
    }
    ctx.strokeStyle = over ? c.fg : (hot ? c.accent : c.line);
    ctx.lineWidth = (over || hot) ? 2 : 1;
    ctx.stroke();
    ctx.lineWidth = 1;

    // Share of the whole run along the bottom edge. Redundant in flame mode, where the
    // width already says it.
    if (!flowFlame && flowRootTotal > 0 && n.shown > 0) {
      ctx.fillStyle = n.isSql ? c.sql : c.accent;
      ctx.globalAlpha = 0.30;
      ctx.fillRect(n.x + 1, n.y + h - 4,
        (n.w - 2) * Math.min(1, n.shown / flowRootTotal), 3);
      ctx.globalAlpha = 1;
    }

    if (n.w < 34) return;    // a sliver: a truncated word would be noise

    if (n.sqlLines && n.sqlLines.length >= 2 && !flowFlame) {
      drawSqlBox(ctx, c, n, h);
      return;
    }

    // Claimed here rather than once before the loop. Set once, it survives only until the
    // first helper that wants a font of its own, and both of them run inside this loop:
    // every label after a query box came out monospace, and in the flame the ruler's 10px
    // reached every frame on the diagram. Assigning the same string is a no-op the browser
    // already caches, so the cost of being certain is nothing.
    ctx.font = FLOW_FONT;

    var mid = n.y + h / 2;
    var right = n.x + n.w - 7;

    // Width already says how long a flame box took, but only relative to whatever is beside
    // it; the number is what makes it comparable to anything else in the report, and the
    // share is what makes it comparable without remembering the run total. Both go in when
    // the box can hold them, the time alone when it cannot.
    var time = fmt(n.shown);
    if (flowFlame) {
      var sharePct = flameTotal > 0
        ? (n.shown / flameTotal) * 100 : 0;
      var shareTxt = sharePct >= 10 ? sharePct.toFixed(0) + '%'
        : sharePct >= 0.1 ? sharePct.toFixed(1) + '%' : '';
      if (shareTxt && ctx.measureText(time + '  ' + shareTxt).width < n.w - 60) {
        time = time + '  ' + shareTxt;
      }
    }
    var timeW = ctx.measureText(time).width;
    ctx.fillStyle = ink || c.muted;
    if (ink) ctx.globalAlpha = 0.75;
    ctx.fillText(time, right - timeW, mid);
    ctx.globalAlpha = 1;
    right -= timeW + 6;

    if (n.reps > 1) {
      var rep = '×' + n.reps;
      var repW = ctx.measureText(rep).width;
      ctx.fillStyle = c.badge;
      ctx.fillText(rep, right - repW, mid);
      right -= repW + 6;
    }

    var x = n.x + 7;
    if (n.hasKids && !flowFlame) {
      // Folded subtrees get a filled marker, expanded ones an outline, so the diagram
      // says what it is hiding without a legend.
      ctx.fillStyle = ink || c.muted;
      ctx.fillText((collapsedFlow[n.seq] || n.reps > 1) ? '▸' : '▾', x, mid);
      x += 12;
    }

    var room = right - x;
    if (room <= 0) return;
    if (n.isSql) {
      ctx.fillStyle = ink || c.sql;
      ctx.fillText(clip(ctx, n.mth, room), x, mid);
      return;
    }
    // Type and member get their own hues, the way an editor colours them, so the class a
    // call belongs to is findable by colour when scanning a column of boxes.
    var clsW = Math.min(ctx.measureText(n.cls).width, room);
    // On the flame the fill already carries identity, so the label wears one readable ink;
    // colouring it too would be identity said twice, in colours that fight the box.
    ctx.fillStyle = ink || c.cls;
    ctx.fillText(clip(ctx, n.cls, room), x, mid);
    if (room - clsW > 8) {
      ctx.fillStyle = ink || c.mth;
      ctx.fillText(clip(ctx, n.mth, room - clsW), x + clsW, mid);
    }
  }

  /**
   * A query drawn on its own clause lines, coloured by token.
   *
   * The same {@code formatSqlLines}/{@code tokenizeSql} pair the tree uses, so a statement
   * breaks in the same places and colours the same way in both views, and the placeholders
   * stay visible as {@code ?} because never capturing their values is the point.
   */
  function drawSqlBox(ctx, c, n, h) {
    var time = fmt(n.shown);
    ctx.font = FLOW_FONT;
    var timeW = ctx.measureText(time).width;
    ctx.fillStyle = c.muted;
    ctx.fillText(time, n.x + n.w - 7 - timeW, n.y + 9);
    if (n.reps > 1) {
      var rep = '×' + n.reps;
      ctx.fillStyle = c.badge;
      ctx.fillText(rep, n.x + n.w - 13 - timeW - ctx.measureText(rep).width, n.y + 9);
    }

    ctx.font = FLOW_SQL_FONT;
    var right = n.x + n.w - 10;
    for (var i = 0; i < n.sqlLines.length; i++) {
      var ln = n.sqlLines[i];
      var x = n.x + 8 + ln.indent * 10;
      var y = n.y + 9 + i * FLOW_LINE;
      var lineRight = i === 0 ? right - timeW - 8 : right;
      for (var t = 0; t < ln.toks.length; t++) {
        var tk = ln.toks[t];
        var w = ctx.measureText(tk.v).width;
        if (x + w > lineRight) {
          // Out of room on this line: mark the cut instead of overprinting the time.
          ctx.fillStyle = c.muted;
          ctx.fillText('…', Math.min(x, lineRight), y);
          break;
        }
        ctx.fillStyle = c.tok[tk.t] || c.fg;
        ctx.fillText(tk.v, x, y);
        x += w;
      }
    }
  }

  function roundRect(ctx, x, y, w, h, r) {
    var rr = Math.min(r, w / 2, h / 2);
    ctx.beginPath();
    ctx.moveTo(x + rr, y);
    ctx.arcTo(x + w, y, x + w, y + h, rr);
    ctx.arcTo(x + w, y + h, x, y + h, rr);
    ctx.arcTo(x, y + h, x, y, rr);
    ctx.arcTo(x, y, x + w, y, rr);
    ctx.closePath();
  }

  function clip(ctx, text, max) {
    if (max <= 0) return '';
    if (ctx.measureText(text).width <= max) return text;
    var lo = 0;
    var hi = text.length;
    while (lo < hi) {
      var mid = (lo + hi + 1) >> 1;
      if (ctx.measureText(text.slice(0, mid) + '…').width <= max) lo = mid;
      else hi = mid - 1;
    }
    return text.slice(0, lo) + '…';
  }

  function renderChartFrame(ctx, c, x0, y0, x1, y1) {
    ctx.strokeStyle = c.line;
    ctx.lineWidth = 1.5;
    for (var i = 0; i < chartEdges.length; i++) {
      var e = chartEdges[i];
      if (e.x1 == null) continue;
      if (Math.max(e.x1, e.x2) < x0 || Math.min(e.x1, e.x2) > x1) continue;
      if (Math.max(e.y1, e.y2) < y0 || Math.min(e.y1, e.y2) > y1) continue;
      drawChartEdge(ctx, c, e);
    }
    ctx.lineWidth = 1;
    ctx.font = FLOW_FONT;
    for (var k = 0; k < chartNodes.length; k++) {
      var n = chartNodes[k];
      if (n.x + n.w < x0 || n.x > x1 || n.y + n.h < y0 || n.y > y1) continue;
      drawChartNode(ctx, c, n);
    }
    drawChartMarker(ctx, c);
  }

  /**
   * A caller-to-callee connector, elbowed through the gap between one depth and the next.
   * Horizontal mode bends the elbow vertically (right, down-or-up, right) since depth runs
   * left to right there; vertical mode bends it horizontally (down, across, down) since
   * depth runs top to bottom instead — the arrowhead always ends up pointing into whichever
   * side of the callee's box actually faces its caller.
   */
  function drawChartEdge(ctx, c, e) {
    var ah = 5;
    if (chartVertical) {
      var midY = (e.y1 + e.y2) / 2;
      ctx.beginPath();
      ctx.moveTo(Math.round(e.x1) + 0.5, Math.round(e.y1) + 0.5);
      ctx.lineTo(Math.round(e.x1) + 0.5, Math.round(midY) + 0.5);
      ctx.lineTo(Math.round(e.x2) + 0.5, Math.round(midY) + 0.5);
      ctx.lineTo(Math.round(e.x2) + 0.5, Math.round(e.y2) + 0.5);
      ctx.stroke();
      ctx.beginPath();
      ctx.moveTo(e.x2, e.y2);
      ctx.lineTo(e.x2 - ah, e.y2 - ah);
      ctx.lineTo(e.x2 + ah, e.y2 - ah);
      ctx.closePath();
      ctx.fillStyle = c.line;
      ctx.fill();
      return;
    }
    var midX = (e.x1 + e.x2) / 2;
    ctx.beginPath();
    ctx.moveTo(Math.round(e.x1) + 0.5, Math.round(e.y1) + 0.5);
    ctx.lineTo(Math.round(midX) + 0.5, Math.round(e.y1) + 0.5);
    ctx.lineTo(Math.round(midX) + 0.5, Math.round(e.y2) + 0.5);
    ctx.lineTo(Math.round(e.x2) + 0.5, Math.round(e.y2) + 0.5);
    ctx.stroke();
    ctx.beginPath();
    ctx.moveTo(e.x2, e.y2);
    ctx.lineTo(e.x2 - ah, e.y2 - ah);
    ctx.lineTo(e.x2 - ah, e.y2 + ah);
    ctx.closePath();
    ctx.fillStyle = c.line;
    ctx.fill();
  }

  function chartNodeFill(c, n) {
    if (n.isSql) return c.sql;
    if (n.status === 'NONE') return c.none;
    if (n.status === 'PARTIAL') return c.partial;
    return c.full;
  }

  /**
   * The numbered circle a step earns once playback has passed through it: not just where
   * the marker is now, but the trail of where it has already been, so a reader can look at
   * a paused or finished run and reconstruct the order without replaying it.
   */
  function drawChartVisitBadge(ctx, c, n) {
    var num = chartVisitOrder[n.key];
    if (num == null) return;
    // No ctx.save()/restore() or ctx.arc(): the SVG export runs every draw call through a
    // context shim (svgContext, below) that stands in for a canvas well enough for paths,
    // fills and text, but was never built to implement those two — every property this
    // touches has to be set back by hand instead. textAlign is one such property the shim
    // does not honour at all, so the label is centred with measureText rather than relied
    // on to centre itself.
    var r = 9;
    var cx = n.x, cy = n.y;
    roundRect(ctx, cx - r, cy - r, r * 2, r * 2, r);
    ctx.fillStyle = c.accent;
    ctx.fill();
    roundRect(ctx, cx - r, cy - r, r * 2, r * 2, r);
    ctx.strokeStyle = c.bg;
    ctx.lineWidth = 2;
    ctx.stroke();
    ctx.lineWidth = 1;
    ctx.font = 'bold 10px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif';
    var label = String(num);
    var w = ctx.measureText(label).width;
    ctx.fillStyle = '#fff';
    ctx.fillText(label, cx - w / 2, cy + 1);
    ctx.font = FLOW_FONT;
  }

  /** Start or End: a pill rather than a rounded rect, so a real flowchart's bookends read
   *  as bookends and not as one more step in the run. Centred in the same grid cell an
   *  ordinary node would occupy, so it needs no special case anywhere in either layout. */
  function drawChartTerminator(ctx, c, n) {
    var over = chartHoverKey === n.key;
    var w = 108, h = 34;
    var cx = n.x + n.w / 2, cy = n.y + n.h / 2;
    var x = cx - w / 2, y = cy - h / 2;
    roundRect(ctx, x, y, w, h, h / 2);
    ctx.fillStyle = c.heat;
    ctx.fill();
    if (over) {
      roundRect(ctx, x, y, w, h, h / 2);
      ctx.fillStyle = c.hoverFill;
      ctx.fill();
    }
    roundRect(ctx, x, y, w, h, h / 2);
    ctx.strokeStyle = c.accent;
    ctx.lineWidth = over ? 2.5 : 1.5;
    ctx.stroke();
    ctx.lineWidth = 1;
    ctx.font = 'bold 12px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif';
    var lw = ctx.measureText(n.label).width;
    ctx.fillStyle = c.accent;
    ctx.fillText(n.label, cx - lw / 2, cy + 1);
    ctx.font = FLOW_FONT;
  }

  function drawChartNode(ctx, c, n) {
    if (n.term) { drawChartTerminator(ctx, c, n); return; }
    var over = chartHoverKey === n.key;
    var fill = chartNodeFill(c, n);

    roundRect(ctx, n.x, n.y, n.w, n.h, 6);
    ctx.fillStyle = c.box;
    ctx.fill();

    roundRect(ctx, n.x, n.y, n.w, n.h, 6);
    ctx.globalAlpha = 0.20;
    ctx.fillStyle = fill;
    ctx.fill();
    ctx.globalAlpha = 1;

    if (over) {
      roundRect(ctx, n.x, n.y, n.w, n.h, 6);
      ctx.fillStyle = c.hoverFill;
      ctx.fill();
    }

    roundRect(ctx, n.x, n.y, n.w, n.h, 6);
    ctx.strokeStyle = over ? c.fg : fill;
    ctx.lineWidth = over ? 2 : 1.5;
    ctx.stroke();
    ctx.lineWidth = 1;

    drawChartVisitBadge(ctx, c, n);

    var mid = n.y + n.h / 2;
    var pad = 8;
    var right = n.x + n.w - pad;

    if (n.n > 1) {
      var rep = '×' + n.n;
      var repW = ctx.measureText(rep).width;
      ctx.fillStyle = c.badge;
      ctx.fillText(rep, right - repW, mid);
      right -= repW + 6;
    }
    if (n.recursive) {
      var loop = '↻';
      var loopW = ctx.measureText(loop).width;
      ctx.fillStyle = c.accent;
      ctx.fillText(loop, right - loopW, mid);
      right -= loopW + 6;
    }

    var x = n.x + pad;
    var room = right - x;
    if (room <= 0) return;
    if (n.isSql) {
      ctx.fillStyle = c.sql;
      ctx.fillText(clip(ctx, n.mth, room), x, mid);
      return;
    }
    var clsW = Math.min(ctx.measureText(n.cls).width, room);
    ctx.fillStyle = c.cls;
    ctx.fillText(clip(ctx, n.cls, room), x, mid);
    if (room - clsW > 8) {
      ctx.fillStyle = c.mth;
      ctx.fillText(clip(ctx, n.mth, room - clsW), x + clsW, mid);
    }
  }

  /** Where the marker sits right now: on a node when idle, sliding along an edge mid-step. */
  function chartMarkerPos() {
    if (!chartPath.length) return null;
    var a = chartPath[chartPlayIdx];
    var b = chartPath[Math.min(chartPlayIdx + 1, chartPath.length - 1)];
    var ax = a.x + a.w / 2, ay = a.y + a.h / 2;
    var bx = b.x + b.w / 2, by = b.y + b.h / 2;
    var t = chartProgress;
    return { x: ax + (bx - ax) * t, y: ay + (by - ay) * t, node: t < 0.5 ? a : b };
  }

  function drawChartMarker(ctx, c) {
    var pos = chartMarkerPos();
    if (!pos) return;
    roundRect(ctx, pos.x - CHART_DOT_R, pos.y - CHART_DOT_R, CHART_DOT_R * 2, CHART_DOT_R * 2, CHART_DOT_R);
    ctx.fillStyle = c.accent;
    ctx.fill();
    roundRect(ctx, pos.x - CHART_DOT_R - 2, pos.y - CHART_DOT_R - 2,
      CHART_DOT_R * 2 + 4, CHART_DOT_R * 2 + 4, CHART_DOT_R + 2);
    ctx.strokeStyle = c.bg;
    ctx.lineWidth = 2;
    ctx.stroke();
    ctx.lineWidth = 1;
  }

  function flowHit(e) {
    // The canvas covers the viewport, not the diagram, so its own top-left is wherever the
    // wrap happens to be scrolled to. Adding the scroll offset puts the pointer back into
    // the diagram's coordinates, which is what every node position is expressed in.
    var r = flowCanvas.getBoundingClientRect();
    var x = (e.clientX - r.left + flowWrap.scrollLeft) / flowScale;
    var y = (e.clientY - r.top + flowWrap.scrollTop) / flowScale;
    if (flowChart) {
      // Not sorted by y (chart nodes are pushed in first-appearance order, not row order),
      // and small enough — call sites, not calls — that a linear scan costs nothing.
      for (var ci = 0; ci < chartNodes.length; ci++) {
        var cn = chartNodes[ci];
        if (x >= cn.x && x <= cn.x + cn.w && y >= cn.y && y <= cn.y + cn.h) return cn;
      }
      return null;
    }
    var list;
    if (flowFlame) {
      // One row per depth there, so the band narrows the search to a single level.
      list = flowByDepth[Math.floor((y - FLOW_PAD - FLOW_RULER) / FLAME_BOX)] || [];
    } else {
      // Rows are not a fixed pitch once a query occupies several lines, so the row is
      // found by bisecting on y rather than dividing by it.
      var lo = 0;
      var hi = flowDrawn.length - 1;
      var found = -1;
      while (lo <= hi) {
        var mid = (lo + hi) >> 1;
        var m = flowDrawn[mid];
        if (y < m.y) hi = mid - 1;
        else if (y > m.y + m.h) lo = mid + 1;
        else { found = mid; break; }
      }
      list = found >= 0 ? [flowDrawn[found]] : [];
    }
    for (var i = 0; i < list.length; i++) {
      var n = list[i];
      if (y >= n.y && y <= n.y + (n.h || FLOW_BOX) && x >= n.x && x <= n.x + n.w) return n;
    }
    return null;
  }

  /**
   * Click means different things in the two layouts, because the two layouts answer
   * different questions. Nesting is the subject in tree mode, so a click folds a subtree
   * away. Time is the subject in flame mode, where folding would only make a frame
   * thinner; there a click zooms into it, which is what actually makes a 200 µs call
   * inside a 48 ms request readable.
   */
  function onFlowClick(e) {
    var n = flowHit(e);
    if (!n) return;
    if (flowChart) {
      if (n.term) return;   // Start/End aren't a step this run actually took
      hideFlowTip();
      goToStep(n.firstSeq);
      return;
    }
    if (flowFlame) {
      // Clicking the frame you are already zoomed into steps back out to its parent.
      flameRoot = (flameRoot === n.seq)
        ? (flowBySeq[n.parentSeq] ? n.parentSeq : null)
        : n.seq;
      hideFlowTip();
      drawFlow();
      return;
    }
    if (!n.hasKids || n.reps > 1) return;
    if (collapsedFlow[n.seq]) delete collapsedFlow[n.seq];
    else collapsedFlow[n.seq] = true;
    hideFlowTip();
    drawFlow();
  }

  /** The trail back out of a flame zoom: every ancestor of the focused frame. */
  var crumbKey = '';
  function renderCrumb() {
    var key = (flowFlame ? 'f' : 't') + ':' + flameRoot;
    if (key === crumbKey) return;
    crumbKey = key;
    if (!flowFlame || flameRoot == null) {
      flowCrumb.hidden = true;
      flowCrumb.textContent = '';
      return;
    }
    var chain = [];
    for (var n = flowBySeq[flameRoot]; n; n = flowBySeq[n.parentSeq]) chain.unshift(n);

    flowCrumb.textContent = '';
    flowCrumb.appendChild(crumbBtn('Whole run', null, false));
    chain.forEach(function (n, i) {
      flowCrumb.appendChild(el('span', 'sep', '▸'));
      flowCrumb.appendChild(crumbBtn(
        n.isSql ? sqlLabel(n.sql) : (n.cls + n.mth), n.seq, i === chain.length - 1));
    });
    flowCrumb.hidden = false;
  }

  function crumbBtn(label, seq, current) {
    var b = el('button', null, label);
    b.type = 'button';
    b.disabled = current;          // you are already here
    if (!current) {
      b.title = seq == null ? 'Back to the whole run' : 'Zoom out to ' + label;
      b.addEventListener('click', function () {
        flameRoot = seq;
        hideFlowTip();
        drawFlow();
      });
    }
    return b;
  }

  function onChartMove(n, e) {
    if (chartHoverKey !== n.key) { chartHoverKey = n.key; paintFlow(); }
    flowTip.textContent = '';
    if (n.term) {
      // Not a step this run took, so none of the per-call facts below apply to it — a
      // plain caption is honest about what it is instead of showing "0× · not executed".
      flowCanvas.style.cursor = 'default';
      flowTip.appendChild(el('div', 't-sig', n.term === 'start' ? 'Start of the run' : 'End of the run'));
      flowTip.hidden = false;
      var tw = flowTip.offsetWidth, th = flowTip.offsetHeight;
      var tleft = e.clientX + 14, ttop = e.clientY + 16;
      if (tleft + tw > window.innerWidth - 8) tleft = e.clientX - tw - 14;
      if (ttop + th > window.innerHeight - 8) ttop = e.clientY - th - 16;
      flowTip.style.left = Math.max(8, tleft) + 'px';
      flowTip.style.top = Math.max(8, ttop) + 'px';
      return;
    }
    flowCanvas.style.cursor = 'pointer';
    if (n.isSql) {
      var q = el('div', 't-sql');
      paintSql(q, n.sql);
      flowTip.appendChild(q);
    } else {
      flowTip.appendChild(el('div', 't-sig', (n.cls || '') + (n.mth || '')));
    }
    var facts = [];
    if (n.n > 1) facts.push('called ' + n.n + '× in this run');
    facts.push(fmtPct(n.total) + ' altogether');
    if (!n.isSql) {
      facts.push(n.status === 'FULL' ? 'fully executed'
        : n.status === 'PARTIAL' ? 'only some branches taken' : 'not fully executed');
    }
    if (n.recursive) facts.push('calls itself');
    if (chartVisitOrder[n.key] != null) facts.push('step ' + chartVisitOrder[n.key] + ' in the playback');
    flowTip.appendChild(el('div', 't-stat', facts.join(' · ')));
    flowTip.appendChild(el('div', 't-sub', 'click to jump to this step in the Call Tree'));
    flowTip.hidden = false;
    var w = flowTip.offsetWidth;
    var h = flowTip.offsetHeight;
    var left = e.clientX + 14;
    var top = e.clientY + 16;
    if (left + w > window.innerWidth - 8) left = e.clientX - w - 14;
    if (top + h > window.innerHeight - 8) top = e.clientY - h - 16;
    flowTip.style.left = Math.max(8, left) + 'px';
    flowTip.style.top = Math.max(8, top) + 'px';
  }

  function onFlowMove(e) {
    var n = flowHit(e);
    if (!n) { hideFlowTip(); return; }
    if (flowChart) { onChartMove(n, e); return; }
    if (flowHover !== n.seq) {
      // Repaint only on a change of box. Every pixel of movement would otherwise redraw
      // the whole canvas, and the highlight is the only thing that differs.
      flowHover = n.seq;
      paintFlow();
    }
    flowCanvas.style.cursor = (flowFlame || (n.hasKids && n.reps === 1))
      ? 'pointer' : 'default';
    flowTip.textContent = '';
    if (n.isSql) {
      var q = el('div', 't-sql');
      paintSql(q, n.sql);      // same coloured, escaped rendering the tree uses
      flowTip.appendChild(q);
    } else {
      flowTip.appendChild(el('div', 't-sig', n.sig));
    }
    // What this call cost the run as a whole, which is the thing the box cannot say. A
    // frame is one invocation of something that usually ran many times, and its own width
    // is measured against whatever is on screen rather than against the request.
    var st = flowStats[n.key];
    if (st) {
      var facts = [];
      if (st.n > 1) {
        facts.push('called ' + st.n + '× in this run');
        facts.push(fmtPct(st.total) + ' altogether');
        facts.push('slowest ' + fmt(st.max));
        facts.push('median ' + fmt(flowMedian(st)));
      }
      // Only once the two differ enough to matter. On a frame that calls nothing they are
      // the same number, and printing it twice would suggest a distinction that is not
      // there; on one that calls plenty, the gap is the whole point — the widest box in a
      // trace is usually the one doing the least of the work itself.
      if (st.self < st.total * 0.98) {
        facts.push('own code ' + fmtPct(st.self));
      }
      if (facts.length) flowTip.appendChild(el('div', 't-stat', facts.join(' · ')));
    }
    var sub = 'step ' + (n.seq + 1) + ' · ' + fmtPct(n.shown);
    if (n.reps > 1) sub += ' · ' + n.reps + ' identical calls combined';
    if (n.site != null) sub += ' · called at line ' + n.site;
    if (flowFlame) sub += (flameRoot === n.seq) ? ' · click to zoom out' : ' · click to zoom in';
    else if (n.hasKids && n.reps === 1) {
      sub += collapsedFlow[n.seq] ? ' · click to expand' : ' · click to fold';
    }
    flowTip.appendChild(el('div', 't-sub', sub));
    flowTip.hidden = false;
    // Positioned after unhiding so the measured size is real, then flipped when it would
    // run off the right or bottom edge.
    var w = flowTip.offsetWidth;
    var h = flowTip.offsetHeight;
    var left = e.clientX + 14;
    var top = e.clientY + 16;
    if (left + w > window.innerWidth - 8) left = e.clientX - w - 14;
    if (top + h > window.innerHeight - 8) top = e.clientY - h - 16;
    flowTip.style.left = Math.max(8, left) + 'px';
    flowTip.style.top = Math.max(8, top) + 'px';
  }

  function hideFlowTip() {
    flowTip.hidden = true;
    if (flowHover !== null) { flowHover = null; if (flowBuilt) paintFlow(); }
    if (chartHoverKey !== null) { chartHoverKey = null; if (flowBuilt) paintFlow(); }
  }

  function flowToggle(btn, get, set) {
    btn.addEventListener('click', function () {
      set(!get());
      btn.classList.toggle('on', get());
      hideFlowTip();
      drawFlow();
    });
  }
  flowToggle(flowSqlBtn, function () { return flowSql; }, function (v) { flowSql = v; });
  flowToggle(flowGroupBtn, function () { return flowGroup; }, function (v) { flowGroup = v; });
  flowToggle(flowHotBtn, function () { return flowHot; }, function (v) { flowHot = v; });
  flowToggle(flowFlameBtn, function () { return flowFlame; },
    function (v) {
      flowFlame = v;
      if (!v) flameRoot = null;
      if (v && flowChart) setFlowChart(false);
    });
  flowToggle(flowFitBtn, function () { return flowFit; }, function (v) { flowFit = v; });

  /** Stops the marker animation and puts the Play button back to its resting state. */
  function stopChartAnim() {
    chartPlaying = false;
    if (chartRAF) { cancelAnimationFrame(chartRAF); chartRAF = 0; }
    chartLastTick = 0;
    syncChartPlayBtn();
  }

  function syncChartPlayBtn() {
    if (!chartPlayBtn) return;
    chartPlayBtn.innerHTML = chartPlaying ? '&#9208;' : '&#9654;';
    chartPlayBtn.title = chartPlaying ? 'Pause' : 'Play the run through the flowchart';
    chartPlayBtn.classList.toggle('on', chartPlaying);
  }

  /**
   * Advances the marker one animation frame and repaints. requestAnimationFrame rather
   * than setInterval, so a backgrounded tab throttles or stops the animation on its own
   * instead of racking up ticks nobody sees.
   */
  function chartTick(ts) {
    if (!chartPlaying) { chartRAF = 0; return; }
    if (!chartLastTick) chartLastTick = ts;
    var dt = ts - chartLastTick;
    chartLastTick = ts;
    var stepMs = CHART_STEP_MS / chartSpeed;
    chartProgress += dt / stepMs;
    while (chartProgress >= 1 && chartPlayIdx < chartPath.length - 1) {
      chartProgress -= 1;
      chartPlayIdx++;
      markChartVisit(chartPlayIdx);
    }
    if (chartPlayIdx >= chartPath.length - 1) {
      chartProgress = 0;
      stopChartAnim();
    }
    paintFlow();
    if (chartPlaying) chartRAF = requestAnimationFrame(chartTick);
  }

  /** Turns the API Flow chart on or off, mutually exclusive with Flame. */
  function setFlowChart(on) {
    flowChart = on;
    flowChartBtn.classList.toggle('on', on);
    if (on && flowFlame) { flowFlame = false; flameRoot = null; flowFlameBtn.classList.remove('on'); }
    stopChartAnim();
    chartPlayIdx = 0;
    chartProgress = 0;
    resetChartVisits();
    if (on) markChartVisit(0);
    chartOrientBtn.hidden = !on;
    chartPlaySeg.hidden = !on;
    chartSpeedSel.hidden = !on;
    chartSpeedCustom.hidden = !on || chartSpeedSel.value !== 'custom';
    chartSpeedVal.hidden = chartSpeedCustom.hidden;
    hideFlowTip();
    drawFlow();
  }
  flowChartBtn.addEventListener('click', function () { setFlowChart(!flowChart); });

  chartOrientBtn.addEventListener('click', function () {
    chartVertical = !chartVertical;
    chartOrientBtn.classList.toggle('on', chartVertical);
    chartOrientBtn.textContent = chartVertical ? 'Left → right' : 'Top → bottom';
    chartOrientBtn.title = chartVertical
      ? 'Switch back to the left-to-right layout'
      : 'Switch to a top-to-bottom layout with Start/End';
    hideFlowTip();
    drawFlow();
  });

  chartPlayBtn.addEventListener('click', function () {
    if (!chartPath.length) return;
    if (chartPlaying) {
      stopChartAnim();
      return;
    }
    if (chartPlayIdx >= chartPath.length - 1) {
      chartPlayIdx = 0;
      chartProgress = 0;
      resetChartVisits();
      markChartVisit(0);
    }
    chartPlaying = true;
    chartLastTick = 0;
    syncChartPlayBtn();
    chartRAF = requestAnimationFrame(chartTick);
  });

  chartResetBtn.addEventListener('click', function () {
    stopChartAnim();
    chartPlayIdx = 0;
    chartProgress = 0;
    resetChartVisits();
    markChartVisit(0);
    paintFlow();
  });

  function applyChartSpeed() {
    var custom = chartSpeedSel.value === 'custom';
    chartSpeedCustom.hidden = !custom;
    chartSpeedVal.hidden = !custom;
    var v = custom ? parseFloat(chartSpeedCustom.value) : parseFloat(chartSpeedSel.value);
    chartSpeed = v > 0 ? v : 1;
    if (custom) chartSpeedVal.textContent = chartSpeed.toFixed(2) + '×';
  }
  chartSpeedSel.addEventListener('change', applyChartSpeed);
  chartSpeedCustom.addEventListener('input', applyChartSpeed);

  /** Applies a new pattern. Returns whether anything actually changed. */
  function setFlowFilter(text) {
    if (text === flowFilterText) return false;
    flowFilterText = text;
    if (flowBuilt) computeFlowFilter();
    return true;
  }

  if (flowScopeSel) {
    flowScopeSel.addEventListener('change', function () {
      flowScope = flowScopeSel.value;
      if (flowBuilt) { computeFlowFilter(); hideFlowTip(); drawFlow(); }
    });
  }
  if (flowColorSel) {
    flowColorSel.addEventListener('change', function () {
      flowColorMode = flowColorSel.value;
      // A new scheme, or a switch between class and package identity, has to re-deal the
      // slots from the top: keeping the old assignment would colour the first package with
      // whatever the first class happened to be given.
      resetFlameSlots();
      if (flowBuilt) { hideFlowTip(); drawFlow(); }
    });
  }

  var flowMinWSel = byId('flowMinWSel');
  if (flowMinWSel) {
    flowMinWSel.addEventListener('change', function () {
      flameMinW = Math.max(1, parseFloat(flowMinWSel.value) || 1);
      // Fit-width and a width floor are contradictory instructions — one says the diagram
      // must be as wide as the window, the other says it must be as wide as its frames
      // need — so asking for room drops the request to fit.
      if (flameMinW > 1 && flowFit) {
        flowFit = false;
        flowFitBtn.classList.remove('on');
      }
      if (flowBuilt) { hideFlowTip(); drawFlow(); }
    });
  }

  function zoomBy(factor) {
    // Zoom and fit are two answers to the same question, so asking for one drops the
    // other rather than compounding into a scale nobody chose.
    flowFit = false;
    flowFitBtn.classList.remove('on');
    flowZoom = Math.max(ZOOM_MIN, Math.min(ZOOM_MAX, flowZoom * factor));
    hideFlowTip();
    drawFlow();
  }
  flowOutBtn.addEventListener('click', function () { zoomBy(1 / ZOOM_STEP); });
  flowInBtn.addEventListener('click', function () { zoomBy(ZOOM_STEP); });

  /**
   * Largest canvas edge worth attempting for an export.
   *
   * <p>Chrome tops out at 65535px and hands back a silently blank canvas past it; Safari
   * gives up sooner. 32767 is the value every engine in use manages, and a diagram past it
   * is exported as the visible region rather than as a blank PNG.
   *
   * <p>The SVG export has no equivalent limit, which is most of why it exists.
   */
  var CANVAS_MAX = 32767;

  // --------------------------------------------------------- svg export ---
  //
  // A 2D-context stand-in that writes SVG elements instead of pixels.
  //
  // The point of the shim is that renderFlow stays the only renderer in the file. An
  // exporter that walked flowDrawn itself would be a second description of the same
  // diagram, free to drift from the one on screen every time a box gains a badge; this one
  // cannot drift, because it is handed to the same function the canvas is.
  //
  // Only the members renderFlow actually reaches for are implemented. That is deliberate:
  // if the diagram later grows a feature drawn with some other call, the export throws
  // instead of quietly saving a picture with the feature missing.

  // Character-for-character what the canvas font strings carry after the size, so the
  // common case compares equal and the stack is written once in a rule instead of on
  // every one of a thousand labels.
  var SVG_FONT = '-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif';

  /** Two decimals is under a tenth of a pixel and roughly halves the file. */
  function svgNum(v) {
    return String(Math.round(v * 100) / 100);
  }

  function xmlText(s) {
    return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  }

  function xmlAttr(s) {
    return xmlText(s).replace(/"/g, '&quot;');
  }

  /**
   * Spaces that survive being rendered.
   *
   * <p>Every renderer collapses a run of spaces to one and drops them at the edges, and
   * {@code xml:space} is honoured in a standalone file but not once the same markup is
   * pasted into an HTML page. A label was positioned against the width {@code measureText}
   * gave it, so "8.1 ms  17%" losing one of its two spaces moves the text off the place it
   * was measured for. A non-breaking space is never collapsed by anything.
   */
  function svgSpaces(s) {
    // Written as an escape rather than the character itself: a literal non-breaking
    // space in the source is indistinguishable from an ordinary one to anyone reading it.
    var NB = '\u00a0';
    return s.replace(/ {2,}/g, function (run) { return run.replace(/ /g, NB); })
      .replace(/^ /, NB).replace(/ $/, NB);
  }

  /**
   * True when a CSS colour paints at full strength.
   *
   * <p>Only used to decide whether one fill may replace another on the same box, which is
   * safe for an opaque colour and wrong for a translucent one.
   */
  function svgOpaque(colour) {
    return !/rgba|hsla/i.test(colour) && !/^#(?:[0-9a-f]{4}|[0-9a-f]{8})$/i.test(colour.trim());
  }

  /** Splits "12px family, stack" into the two SVG attributes that describe it. */
  function svgFont(font) {
    var m = /^\s*(?:\w+\s+)*?(\d+(?:\.\d+)?)px\s+(.+)$/.exec(font);
    return m ? { size: m[1], family: m[2] } : { size: '12', family: SVG_FONT };
  }

  /**
   * The arc canvas draws for {@code arcTo}, as an SVG path segment.
   *
   * <p>Canvas takes two control points and a radius and works the tangents out; SVG wants
   * the endpoint. Rounded corners are the only arcs this diagram draws, so the degenerate
   * cases collapse to a straight line rather than trying to be clever.
   */
  function svgArc(x0, y0, x1, y1, x2, y2, r) {
    var v1x = x0 - x1;
    var v1y = y0 - y1;
    var v2x = x2 - x1;
    var v2y = y2 - y1;
    var l1 = Math.sqrt(v1x * v1x + v1y * v1y);
    var l2 = Math.sqrt(v2x * v2x + v2y * v2y);
    if (!l1 || !l2 || r <= 0) return { d: 'L' + svgNum(x1) + ' ' + svgNum(y1), x: x1, y: y1 };
    v1x /= l1; v1y /= l1; v2x /= l2; v2y /= l2;
    var cos = v1x * v2x + v1y * v2y;
    if (cos > 0.9999 || cos < -0.9999) {
      return { d: 'L' + svgNum(x1) + ' ' + svgNum(y1), x: x1, y: y1 };
    }
    var d = r / Math.tan(Math.acos(cos) / 2);
    var t1x = x1 + v1x * d;
    var t1y = y1 + v1y * d;
    var t2x = x1 + v2x * d;
    var t2y = y1 + v2y * d;
    // Which way the corner turns decides the sweep flag; y grows downward in both
    // coordinate systems, so a negative cross product is the clockwise one.
    var sweep = (v1x * v2y - v1y * v2x) > 0 ? 0 : 1;
    return {
      d: 'L' + svgNum(t1x) + ' ' + svgNum(t1y)
        + 'A' + svgNum(r) + ' ' + svgNum(r) + ' 0 0 ' + sweep + ' '
        + svgNum(t2x) + ' ' + svgNum(t2y),
      x: t2x,
      y: t2y
    };
  }

  function svgContext(measurer) {
    var out = [];        // finished elements, in paint order
    var ops = [];        // the path being built
    var last = null;     // element a following fill/stroke may still merge into

    /**
     * The path as a rectangle, when it is one.
     *
     * <p>Every box on the diagram arrives here as roundRect's four arcs. Recognising that
     * shape turns roughly two hundred bytes of path data into a forty-byte {@code <rect>},
     * which on a diagram of a thousand frames is the difference between a file that opens
     * instantly and one that does not.
     */
    function asRect() {
      if (ops.length !== 6 || ops[0][0] !== 'M' || ops[5][0] !== 'Z') return null;
      var xs = [];
      var ys = [];
      for (var i = 1; i < 5; i++) {
        if (ops[i][0] !== 'A') return null;
        xs.push(ops[i][1]);
        ys.push(ops[i][2]);
      }
      var x = Math.min.apply(null, xs);
      var y = Math.min.apply(null, ys);
      var w = Math.max.apply(null, xs) - x;
      var h = Math.max.apply(null, ys) - y;
      // roundRect starts on the top edge. Anything else is a path that merely has four
      // arcs in it, and guessing a rectangle out of it would draw the wrong shape.
      if (Math.abs(ops[0][2] - y) > 0.01) return null;
      return { tag: 'rect', x: x, y: y, w: w, h: h, r: ops[1][5] };
    }

    function asPath() {
      var d = '';
      var cx = 0;
      var cy = 0;
      for (var i = 0; i < ops.length; i++) {
        var op = ops[i];
        if (op[0] === 'M') {
          d += 'M' + svgNum(op[1]) + ' ' + svgNum(op[2]);
          cx = op[1]; cy = op[2];
        } else if (op[0] === 'L') {
          d += 'L' + svgNum(op[1]) + ' ' + svgNum(op[2]);
          cx = op[1]; cy = op[2];
        } else if (op[0] === 'A') {
          var seg = svgArc(cx, cy, op[1], op[2], op[3], op[4], op[5]);
          d += seg.d;
          cx = seg.x; cy = seg.y;
        } else {
          d += 'Z';
        }
      }
      return { tag: 'path', d: d };
    }

    /** A shape's identity, so a fill and the stroke that follows it become one element. */
    function shape() {
      var s = asRect() || asPath();
      s.key = s.tag === 'rect'
        ? 'r' + svgNum(s.x) + ',' + svgNum(s.y) + ',' + svgNum(s.w) + ',' + svgNum(s.h) + ',' + svgNum(s.r)
        : 'p' + s.d;
      return s;
    }

    function paint(kind) {
      if (!ops.length) return;
      var s = shape();
      if (last && last.key === s.key) {
        if (kind === 'stroke' && last.stroke == null) {
          last.stroke = api.strokeStyle;
          last.strokeWidth = api.lineWidth;
          last.strokeAlpha = api.globalAlpha;
          return;
        }
        // Flame boxes are filled twice, once in the neutral box colour and again in the
        // frame's own. The second covers the first exactly, so only the second is written.
        if (kind === 'fill' && last.stroke == null && last.fill != null
            && api.globalAlpha === 1 && last.fillAlpha === 1
            && svgOpaque(api.fillStyle) && svgOpaque(last.fill)) {
          last.fill = api.fillStyle;
          return;
        }
      }
      if (kind === 'fill') {
        s.fill = api.fillStyle;
        s.fillAlpha = api.globalAlpha;
      } else {
        s.stroke = api.strokeStyle;
        s.strokeWidth = api.lineWidth;
        s.strokeAlpha = api.globalAlpha;
      }
      out.push(s);
      last = s;
    }

    var api = {
      fillStyle: '#000',
      strokeStyle: '#000',
      lineWidth: 1,
      globalAlpha: 1,
      font: '12px sans-serif',
      textBaseline: 'alphabetic',

      beginPath: function () { ops = []; last = null; },
      moveTo: function (x, y) { ops.push(['M', x, y]); },
      lineTo: function (x, y) { ops.push(['L', x, y]); },
      arcTo: function (x1, y1, x2, y2, r) { ops.push(['A', x1, y1, x2, y2, r]); },
      closePath: function () { ops.push(['Z']); },
      fill: function () { paint('fill'); },
      stroke: function () { paint('stroke'); },

      fillRect: function (x, y, w, h) {
        out.push({
          tag: 'rect', key: null, x: x, y: y, w: w, h: h, r: 0,
          fill: api.fillStyle, fillAlpha: api.globalAlpha
        });
        last = null;
      },

      fillText: function (text, x, y) {
        if (text === '' || text == null) return;
        out.push({
          tag: 'text', key: null, x: x, y: y, text: String(text),
          fill: api.fillStyle, fillAlpha: api.globalAlpha,
          font: api.font, baseline: api.textBaseline
        });
        last = null;
      },

      // Delegated to a real context so a label is cut at exactly the character it is cut
      // at on screen. Measuring any other way would give the export its own idea of what
      // fits, and the two pictures would disagree about where the ellipsis goes.
      measureText: function (text) {
        measurer.font = api.font;
        return measurer.measureText(text);
      },

      // The canvas puts scroll and device pixels in the transform. An SVG carries the same
      // information in width/height against viewBox, so there is nothing to apply here.
      setTransform: function () {},

      serialize: function (width, height, viewW, viewH) {
        var body = [];
        for (var i = 0; i < out.length; i++) {
          var e = out[i];
          var at = '';
          if (e.tag === 'text') {
            var f = svgFont(e.font);
            at = ' x="' + svgNum(e.x) + '" y="' + svgNum(e.y) + '"'
              + ' fill="' + xmlAttr(e.fill) + '"'
              + (e.fillAlpha < 1 ? ' fill-opacity="' + svgNum(e.fillAlpha) + '"' : '')
              + ' font-size="' + f.size + '"'
              + (f.family === SVG_FONT ? '' : ' font-family="' + xmlAttr(f.family) + '"')
              + (e.baseline === 'middle' ? '' : ' dominant-baseline="auto"');
            body.push('<text' + at + '>' + xmlText(svgSpaces(e.text)) + '</text>');
            continue;
          }
          if (e.tag === 'rect') {
            at = ' x="' + svgNum(e.x) + '" y="' + svgNum(e.y) + '"'
              + ' width="' + svgNum(Math.max(0, e.w)) + '" height="' + svgNum(Math.max(0, e.h)) + '"'
              + (e.r > 0 ? ' rx="' + svgNum(e.r) + '"' : '');
          } else {
            at = ' d="' + e.d + '"';
          }
          at += e.fill != null ? ' fill="' + xmlAttr(e.fill) + '"' : ' fill="none"';
          if (e.fill != null && e.fillAlpha < 1) at += ' fill-opacity="' + svgNum(e.fillAlpha) + '"';
          if (e.stroke != null) {
            at += ' stroke="' + xmlAttr(e.stroke) + '"';
            if (e.strokeWidth !== 1) at += ' stroke-width="' + svgNum(e.strokeWidth) + '"';
            if (e.strokeAlpha < 1) at += ' stroke-opacity="' + svgNum(e.strokeAlpha) + '"';
          }
          body.push('<' + e.tag + at + '/>');
        }
        // Carried on the root and inherited, rather than set by a stylesheet rule. A rule
        // would win over the font-family attribute on the handful of labels that need a
        // different one — presentation attributes lose to CSS — and every query in the
        // diagram would silently come out in the proportional face it is not measured in.
        return '<?xml version="1.0" encoding="UTF-8"?>\n'
          + '<svg xmlns="http://www.w3.org/2000/svg" width="' + width + '" height="' + height + '"'
          + ' viewBox="0 0 ' + svgNum(viewW) + ' ' + svgNum(viewH) + '"'
          + ' font-family="' + xmlAttr(SVG_FONT) + '" dominant-baseline="central"'
          // Honoured by a standalone .svg and ignored once the markup is pasted into an
          // HTML page, which is why the spaces that matter are also written as
          // non-breaking ones rather than trusting this alone.
          + ' xml:space="preserve">\n'
          + body.join('\n') + '\n</svg>\n';
      },

      count: function () { return out.length; }
    };
    return api;
  }

  flowPngBtn.addEventListener('click', function () {
    if (!flowCanvas || flowContentH === 0) return;   // nothing drawn yet
    // Floored too: a PNG saved from a zoomed-out window used to be a picture of a smaller
    // diagram, sized as if it were the full one.
    var dpr = flowDpr();
    var w = Math.ceil(flowContentW * flowScale * dpr);
    var h = Math.ceil(flowContentH * flowScale * dpr);
    var src = flowCanvas;
    // The on-screen canvas is only the viewport now, so a full-diagram PNG is rendered
    // again into an off-screen one. Only when the result can actually hold it.
    if (w <= CANVAS_MAX && h <= CANVAS_MAX) {
      var off = document.createElement('canvas');
      off.width = w;
      off.height = h;
      var octx = off.getContext('2d');
      octx.setTransform(dpr * flowScale, 0, 0, dpr * flowScale, 0, 0);
      renderFlow(octx, 0, 0, flowContentW, flowContentH);
      src = off;
    }
    var a = document.createElement('a');
    a.download = 'deju-flow.png';
    a.href = src.toDataURL('image/png');
    a.click();
    // Past the canvas limit the export silently became a picture of the viewport, which
    // looks exactly like a successful save of the whole diagram and is not one. Say so.
    if (src !== flowCanvas) flash(flowPngBtn, 'Saved');
    else flash(flowPngBtn, 'Saved the visible part only — too large for one image');
  });

  flowSvgBtn.addEventListener('click', function () {
    if (!flowCanvas || flowContentH === 0) return;   // nothing drawn yet
    var g = svgContext(flowCanvas.getContext('2d'));
    // The whole diagram, in its own coordinates: no CANVAS_MAX to duck under, so unlike
    // the PNG this never quietly becomes a picture of the viewport.
    renderFlow(g, 0, 0, flowContentW, flowContentH);
    var svg = g.serialize(
      Math.ceil(flowContentW * flowScale), Math.ceil(flowContentH * flowScale),
      flowContentW, flowContentH);
    var blob = new Blob([svg], { type: 'image/svg+xml;charset=utf-8' });
    var url = URL.createObjectURL(blob);
    var a = document.createElement('a');
    a.download = 'deju-flow.svg';
    a.href = url;
    a.click();
    // Revoked on the next turn: the click has taken the URL by then, and holding it would
    // pin the whole serialised diagram in memory for as long as the report stays open.
    setTimeout(function () { URL.revokeObjectURL(url); }, 0);
    flash(flowSvgBtn, 'Saved');
  });

  // A canvas holds pixels, not a description of them: both a repaint-worthy change and a
  // resize have to redraw by hand. Only when the tab is actually showing.
  window.addEventListener('resize', function () {
    if (tab === 'flow' && flowBuilt) drawFlow();
  });
  byId('themeToggle').addEventListener('click', function () {
    if (tab === 'flow' && flowBuilt) drawFlow();
  });

  // ------------------------------------------------------------ url state ---
  //
  // The address bar describes what you are looking at, so "look at step 4312" is something
  // you can send to somebody instead of describing. Written with replaceState so scrolling
  // through a trace does not fill the back button with history.

  var urlStateReady = false;
  var applyingUrlState = false;

  function rememberUrlState() {
    if (!urlStateReady || applyingUrlState) return;
    var parts = [];
    if (tab !== 'trace') parts.push('tab=' + tab);
    if (tab === 'trace' && view !== 'tree') parts.push('view=' + view);
    if (detailFull) parts.push('detail=full');
    var q = searchEl.value.trim();
    if (q !== '') parts.push('q=' + encodeURIComponent(q));
    if (tab === 'trace' && cursor && cursor.node) parts.push('step=' + (cursor.node.seq + 1));
    var hash = parts.length ? '#' + parts.join('&') : '';
    if (location.hash === hash || (hash === '' && location.hash === '')) return;
    try {
      history.replaceState(null, '', location.pathname + location.search + hash);
    } catch (e) {
      // Some browsers refuse replaceState on file://. The hash still works, it just
      // leaves history entries behind, which is the lesser problem.
      applyingUrlState = true;
      location.hash = hash;
      applyingUrlState = false;
    }
  }

  function applyUrlState() {
    var raw = location.hash.replace(/^#/, '');
    if (!raw) return;
    var q = {};
    raw.split('&').forEach(function (kv) {
      var at = kv.indexOf('=');
      if (at > 0) {
        try { q[kv.slice(0, at)] = decodeURIComponent(kv.slice(at + 1)); } catch (e) { /* junk */ }
      }
    });
    applyingUrlState = true;
    try {
      if (q.q) searchEl.value = q.q;
      if (q.detail === 'full' && hasExclusions && !excludedOmitted) setDetail(true);
      if (q.view === 'files' && treeAvailable) setView('files');
      else if (q.q) applyFilters();
      var wanted = q.tab || 'trace';
      if (wanted !== 'trace' && tabPresent('tab' + capitalise(wanted))) {
        setTab(wanted);
      } else if (q.step) {
        var step = parseInt(q.step, 10) - 1;
        if (step >= 0 && nodeBySeq[step]) goToStep(step);
      }
    } finally {
      applyingUrlState = false;
    }
  }

  function capitalise(s) {
    return s.charAt(0).toUpperCase() + s.slice(1);
  }

  /**
   * Puts the whole tree in the document while the page is being printed.
   *
   * <p>Virtualisation and printing are natural enemies: the printer asks for the document,
   * and the document only ever holds a screenful. Bounded, because rendering a 200,000-row
   * trace into a print job would hang the browser, and a run that long is not something
   * anybody meant to put on paper.
   */
  var PRINT_ROW_LIMIT = 5000;
  window.addEventListener('beforeprint', function () {
    if (!padTop || view !== 'tree' || tab !== 'trace') return;
    if (visRows.length === 0 || visRows.length > PRINT_ROW_LIMIT) return;
    var frag = document.createDocumentFragment();
    for (var i = 0; i < visRows.length; i++) frag.appendChild(rowEl(visRows[i]));
    treeTable.textContent = '';
    treeTable.appendChild(frag);
    renderedFrom = -1;
    renderedTo = -1;
  });
  window.addEventListener('afterprint', function () {
    if (!padTop) return;
    renderDirty = true;
    renderTreeWindow();
  });

  if (!treeAvailable) {
    // Payload from an agent with no call tree: neither the Tree view nor the flow
    // diagram has anything to draw, so both are taken out of reach.
    tabFlow.disabled = true;
    tabFlow.title = 'This recording has no call tree, so there is no flow to draw.';
    viewTree.disabled = true;
    viewTree.title = agentMismatch()
      || 'This recording has no call tree, re-record with a current agent.';
    setView('files');
  } else {
    // Honours the exported preference, but only where there is a choice: the branch above
    // is a run with no call tree at all, where Tree is not a view that can be shown.
    setView(prefs.view === 'files' ? 'files' : 'tree');
  }
  applySort();
  updateNotice();

  // Counted at startup, not on first open: the badge is how the reader learns the tab has
  // anything in it, and a badge that only appears once you have already looked is useless.
  if (treeAvailable || files.length > 0) {
    findingsBuilt = true;
    buildFindings();
    findingsBadge.textContent = String(findings.length);
    findingsBadge.hidden = findings.length === 0;
    tabFindings.classList.toggle('hasfindings', findings.some(function (f) {
      return f.sev === 'high';
    }));
  }

  // The export's starting position, applied before the URL is read so that a shared link
  // still wins: the link describes a specific thing somebody wanted to show, the export
  // default only describes how the report should look when opened cold.
  applyPrefs();
  // sqlOn and treeGroup were seeded from the prefs so the first build was already right,
  // but their buttons carry their "on" state in markup and would otherwise claim the
  // opposite of what the tree is doing.
  setSql(prefs.sql);
  treeGroupBtn.classList.toggle('on', treeGroup);
  applyFoldPrefs();
  setTab(prefs.openTab);
  buildCustomize();

  urlStateReady = true;
  applyUrlState();
})();
