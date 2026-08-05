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
(function () {
  'use strict';

  var data = JSON.parse(document.getElementById('deju-data').textContent);
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

  var counts = { FULL: 0, PARTIAL: 0, NONE: 0 };
  var statusOn = { FULL: true, PARTIAL: true, NONE: true };
  /* Declared with the other view state, not beside its button: applyTreeFilters reads it
     and is defined long before that wiring runs, so a `var` down there would be hoisted
     as undefined and hide every query on the first render. */
  var sqlOn = true;
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

  function inDate(iso) {
    if (!iso) return '—';
    var m = /^(\d{4})-(\d{2})-(\d{2})(.*)$/.exec(iso);
    return m ? (m[3] + '-' + m[2] + '-' + m[1] + m[4]) : iso;
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
   * Writes a formatted, coloured statement into a container.
   *
   * <p>Every fragment is a text node or a span set with textContent, exactly as
   * {@code paintCode} does: a traced query is untrusted input and can never become markup.
   * Placeholders are coloured distinctly because "the value is never captured" is the
   * report's central claim about SQL, and a visible {@code ?} is the evidence.
   */
  function paintSql(container, text) {
    if (!text) return;
    var lines = formatSqlLines(tokenizeSql(text));
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

  // Written to the left span, not to #meta itself: the count shares that row and a
  // textContent assignment on the parent would delete it.
  document.getElementById('metaText').textContent =
    'Trace point: ' + (data.target || '—') + '   |   Started ' + inDate(data.startedAtIso);

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

  var root = document.getElementById('root');
  var fileList = document.getElementById('fileList');
  var entries = [];
  /** Class name -> its entry, so the Tree view can honour the same file selection. */
  var entryByClass = {};

  /**
   * Whether a class is ticked in the Files dropdown, as the Tree view sees it.
   *
   * Unknown classes count as selected: a call can be recorded for a class whose source was
   * never resolved, and silently dropping those frames would hide real calls.
   *
   * Boxes the user has never touched also count as selected here. Excluded and generated
   * types open unticked so the Files view lands on the classes worth reading, but the Tree
   * already has its own Essential/Full control for exactly those, and filtering them twice
   * would leave Full with nothing extra to reveal.
   */
  function classSelected(name) {
    var e = entryByClass[name];
    if (!e) return true;
    return e.checkbox.checked || !e.userToggled;
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
    var table = el('table');
    var rows = [];
    var sections = [];
    var current = null;
    var totalWeight = 0;   // largest inclusive method time in this file
    var selfWeight = 0;    // time spent executing this file's own lines
    var red = 0;
    var partial = 0;

    (f.lines || []).forEach(function (l) {
      var st = l.status || 'NONE';
      if (counts[st] !== undefined) counts[st]++;
      if (st === 'NONE') red++;
      if (st === 'PARTIAL') partial++;
      totalLines++;
      if (l.timeMicros != null) selfWeight += l.timeMicros;

      var mname = l.methodName || null;
      if (mname !== null && (current === null || current.name !== mname)) {
        current = { name: mname, rows: [], declLine: l.line, total: null, collapsed: false };
        sections.push(current);

        var mtr = el('tr', 'mrow');
        var mtd = el('td');
        mtd.colSpan = 3;
        var head = el('div', 'mhead');
        var mcaret = el('span', 'mcaret', '▾');
        head.appendChild(mcaret);
        head.appendChild(el('span', 'mname', methodLabel(mname, f.fqClassName)));
        var kind = methodKind(mname);
        if (kind) head.appendChild(el('span', 'mkind', kind));
        var mmeta = el('span', 'mmeta');
        head.appendChild(mmeta);
        mtd.appendChild(head);
        mtr.appendChild(mtd);
        table.appendChild(mtr);

        current.tr = mtr;
        current.caret = mcaret;
        current.meta = mmeta;

        var sec = current;
        mtr.addEventListener('click', function () {
          sec.collapsed = !sec.collapsed;
          sec.caret.textContent = sec.collapsed ? '▸' : '▾';
          applyFilters();
        });
      } else if (mname === null) {
        current = null;
      }
      if (current && l.methodStart) current.declLine = l.line;
      if (current && l.methodTotalMicros != null) current.total = l.methodTotalMicros;

      var tr = el('tr', st);
      var tm = el('td', 'time');
      var micros = (l.methodTotalMicros != null) ? l.methodTotalMicros : l.timeMicros;
      if (micros != null) {
        if (l.methodTotalMicros != null && micros > totalWeight) totalWeight = micros;
        tm.textContent = (l.methodTotalMicros != null ? '▸ ' : '') + fmt(micros);
        tm.title = (l.methodTotalMicros != null ? 'method total' : 'line self time');
      }
      var code = el('td', 'code');
      var codeText = (l.code != null ? l.code : '');
      paintCode(code, codeText);
      if (l.branchesTotal) {
        code.appendChild(el('span', 'br', '   (' + l.branchesCovered + '/' + l.branchesTotal + ' branches)'));
      }
      tr.appendChild(tm);
      tr.appendChild(lineCell(f, l.line));
      tr.appendChild(code);
      table.appendChild(tr);

      var rec = { tr: tr, status: st, text: codeText.toLowerCase(), line: l.line, section: current, entry: null };
      rows.push(rec);
      if (current) current.rows.push(rec);
    });

    // The table is left detached until the file is near the viewport. Building it costs
    // little; laying out every line of every file the moment the Files view opens is what
    // used to cost, and a run touching a few hundred files never has them all on screen.
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

    // Excluded types start unticked so the Files view opens on the classes worth reading.
    // This is a starting selection, not a filter: the file is fully rendered and one click
    // in this dropdown brings it back, which is why the Tree's Detail control does not
    // reach in here and overwrite whatever the user has since chosen.
    var excluded = !!excludedClasses[f.fqClassName];

    var label = el('label');
    var cb = el('input');
    cb.type = 'checkbox';
    cb.checked = !generated && !excluded;
    label.appendChild(cb);
    label.appendChild(el('span', 'mtxt', displayName));
    if (generated) label.appendChild(el('span', 'gen', '(generated)'));
    else if (excluded) label.appendChild(el('span', 'gen', '(excluded)'));
    cb.addEventListener('change', function () { entry.userToggled = true; applyFilters(); });
    fileList.appendChild(label);

    var firstSeq = firstSeqByClass[f.fqClassName];
    var entry = {
      box: box, h: h, rows: rows, sections: sections, checkbox: cb, label: label,
      totalWeight: totalWeight, selfWeight: selfWeight, red: red, partial: partial,
      // Fall back to payload position when there is no call tree to order by.
      order: firstSeq === undefined ? 1e9 + idx : firstSeq,
      payloadIdx: idx, caret: caret, generated: generated,
      table: table, scroll: scroll, attached: false,
      name: displayName, lower: displayName.toLowerCase(),
      path: f.absPath || f.path || null
    };
    entryByClass[f.fqClassName] = entry;
    rows.forEach(function (r) { r.entry = entry; });
    sections.forEach(function (s) {
      s.meta.textContent = (s.total != null ? fmt(s.total) + ' · ' : '')
        + 'line ' + s.declLine + ' · ' + s.rows.length + (s.rows.length === 1 ? ' line' : ' lines');
    });
    entries.push(entry);
  });

  ordered = entries.slice();

  /**
   * Puts a file's rows into the document the first time they are wanted.
   *
   * <p>Everything the filters and counts read lives on the row objects, which are built up
   * front and are cheap; only the layout is deferred, so a file that is never scrolled to
   * is never laid out.
   */
  function attachEntry(e) {
    if (e.attached) return;
    e.attached = true;
    e.scroll.appendChild(e.table);
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

  var treeTable = document.getElementById('treeTable');
  var treeView = document.getElementById('treeView');
  var filesView = document.getElementById('filesView');
  var treeEmpty = document.getElementById('treeEmpty');
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
  var detailFull = false;        // false = Essential, excluded types folded away
  var expandedRollups = {};
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

    var more = el('button', 'sqlmore', 'Show full query');
    more.type = 'button';
    more.hidden = true;
    more.addEventListener('click', function () {
      var clamped = code.classList.toggle('clamped');
      more.textContent = clamped ? 'Show full query' : 'Show less';
    });
    td.appendChild(more);
    tr.appendChild(td);
    return {
      // Still a 'line' so it counts and propagates like one, but marked so the SQL
      // toggle can hide queries without touching the code lines around them.
      kind: 'line', sql: true, depth: depth, tr: tr, parent: parentRow, status: 'FULL',
      text: (node.sql || '').toLowerCase(), vis: true
    };
  }

  function frameRow(node, depth, parentRow) {
    var file = fileByClass[node.className];
    var kids = childrenBySeq[node.seq] || [];
    var lines = linesByMethod[node.className + '#' + node.methodName] || [];
    var foldable = kids.length > 0 || lines.length > 0;

    var r = { kind: 'frame', depth: depth, node: node, tr: null, parent: parentRow, vis: true };
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
    head.appendChild(time);

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
      tm.title = 'line self time';
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
      if (!detailFull && foldableSeq[kid.seq] && !expandedRollups[kid.seq]) {
        var exEnd = ki;
        while (exEnd < kids.length
               && foldableSeq[kids[exEnd].seq]
               && !expandedRollups[kids[exEnd].seq]) {
          exEnd++;
        }
        var group = kids.slice(ki, exEnd);
        hiddenFrameCount += countSubtree(group);
        treeRows.push(rollupRow(group, depth + 1, row));
        if (treeRows.length >= MAX_TREE_ROWS) { treeTruncated = true; return; }
        ki = exEnd;
        continue;
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
      var show = (runLen > FOLD_RUN_AFTER && !expandedFolds[foldId]) ? FOLD_RUN_AFTER : runLen;
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
    return searchEl.value.trim() !== ''
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

  function ensurePads() {
    if (padTop) return;
    treeWrapEl = treeTable.parentNode;
    padTop = el('tr', 'vpad');
    padBot = el('tr', 'vpad');
    padTop.appendChild(el('td'));
    padBot.appendChild(el('td'));
    padTop.firstChild.colSpan = 3;
    padBot.firstChild.colSpan = 3;
    treeWrapEl.addEventListener('scroll', renderTreeWindow);
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

  function renderTreeWindow() {
    if (!padTop) return;
    var n = visRows.length;
    var top = treeWrapEl.scrollTop;
    var h = treeWrapEl.clientHeight || 600;
    var from = Math.max(0, rowAt(top) - TREE_OVERSCAN);
    var to = Math.min(n, rowAt(top + h) + 1 + TREE_OVERSCAN);

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
    }
    syncSqlClamps();
  }

  /**
   * Reveals the expand control only on queries the clamp is actually cutting off.
   *
   * <p>Must run after the rows are in the document: a statement's rendered height depends
   * on how wide the window is, so the same SQL can need the control at one size and not at
   * another. Reading {@code scrollHeight} here forces one layout pass, which is why it is
   * done once for the whole table rather than per row as each is built.
   */
  function syncSqlClamps() {
    var blocks = treeTable.querySelectorAll('pre.sqltext');
    Array.prototype.forEach.call(blocks, function (code) {
      var more = code.nextElementSibling;
      if (!more || more.className !== 'sqlmore') return;
      var overflowing = code.scrollHeight > code.clientHeight + 1;
      more.hidden = !overflowing && code.classList.contains('clamped');
    });
  }

  function applyTreeFilters() {
    var q = searchEl.value.trim().toLowerCase();
    var active = filtering();
    var shownLines = 0, shownFrames = 0;
    var rollupTotal = 0;

    treeRows.forEach(function (r) {
      if (r.rollup) rollupTotal++;
      if ((r.sql && !sqlOn) || (r.rollup && !rollupOn)) {
        r.vis = false;   // hidden outright, and never revived by parent propagation
      } else if (r.kind === 'line') {
        r.vis = statusOn[r.status] && (q === '' || r.text.indexOf(q) !== -1);
      } else if (r.kind === 'fold') {
        r.vis = !active;
      } else {
        r.vis = !active;   // frames light back up via propagation below
      }
    });
    if (active) {
      treeRows.forEach(function (r) {
        if (r.vis && r.kind !== 'frame') {
          for (var p = r.parent; p; p = p.parent) p.vis = true;
        }
      });
    }
    // The visible rows become a list rather than a display flag on every row: the window
    // below indexes into it, and rows that never come near the viewport cost one array
    // slot instead of a table row nobody will look at.
    visRows = [];
    rowHeights = [];
    treeRows.forEach(function (r) {
      if (!r.vis) return;
      r.vi = visRows.length;
      visRows.push(r);
      rowHeights.push(r.tr ? r.tr.offsetHeight : 0);
      if (r.kind === 'line') shownLines++;
      else if (r.kind === 'frame') shownFrames++;
    });
    // A filter hides every fold row, so there is nothing for the toggle to act on then.
    // Deciding from the rows themselves is what keeps the control honest: it used to be
    // derived from why roll-ups might exist, and was wrong in both directions.
    rollupAvailable = active ? 0 : rollupTotal;
    syncRollupBtn();
    ensurePads();
    rebuildOffsets();
    treeWrapEl.scrollTop = 0;
    renderTreeWindow();

    // Counted in calls, not lines: a method invoked twice renders its lines twice, so
    // "x of totalLines" would be meaningless here.
    countEl.textContent = 'Showing ' + shownFrames + ' of ' + calls.length
      + ' call(s) · ' + shownLines + ' line(s)'
      + (hiddenFrameCount ? ' · ' + hiddenFrameCount + ' folded into excluded types' : '');
    treeEmpty.hidden = shownFrames !== 0 || shownLines !== 0;
    if (cursor && !cursor.vis) clearCursor();
  }

  function refreshTree() {
    buildTree();
    applyTreeFilters();
    updateNotice();
  }

  // ------------------------------------------------------------ shared state ---

  var searchEl = document.getElementById('search');
  var countEl = document.getElementById('count');
  var emptyEl = document.getElementById('empty');
  var foldBtn = document.getElementById('foldBtn');
  var noticeEl = document.getElementById('notice');
  var view = 'tree';

  function applyFileFilters() {
    var q = searchEl.value.trim().toLowerCase();
    var shownLines = 0, shownFiles = 0;
    entries.forEach(function (e) {
      if (!e.checkbox.checked) { e.box.hidden = true; return; }
      var anyVisible = false;
      e.rows.forEach(function (r) {
        var vis = statusOn[r.status]
          && (q === '' || r.text.indexOf(q) !== -1)
          && !(r.section && r.section.collapsed);
        r.tr.style.display = vis ? '' : 'none';
        if (vis) { anyVisible = true; shownLines++; }
      });
      e.sections.forEach(function (s) {
        var anyMatch = s.rows.some(function (r) {
          return statusOn[r.status] && (q === '' || r.text.indexOf(q) !== -1);
        });
        s.tr.style.display = anyMatch ? '' : 'none';
        if (anyMatch) anyVisible = true;
      });
      e.box.hidden = !anyVisible;
      if (anyVisible) shownFiles++;
    });
    attachVisibleFiles();
    countEl.textContent = 'Showing ' + shownLines + ' of ' + totalLines
      + ' line(s) · ' + shownFiles + ' file(s)';
    emptyEl.hidden = shownLines !== 0 && shownFiles !== 0;
    if (cursor && (cursor.tr.style.display === 'none' || cursor.entry.box.hidden)) clearCursor();
  }

  function applyFilters() {
    if (view === 'tree') refreshTree(); else applyFileFilters();
    // The Flow Graph reads the same file selection, so it has to follow it too. Only once
    // it exists: before the tab is first opened there is nothing to redraw, which is the
    // point of building it lazily.
    if (flowBuilt) drawFlow();
  }

  searchEl.addEventListener('input', applyFilters);

  // ------------------------------------------------------------------ legend ---

  var legend = document.getElementById('legend');
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

  var viewTree = document.getElementById('viewTree');
  var viewFiles = document.getElementById('viewFiles');

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

  var sortEl = document.getElementById('sort');
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
      entries.forEach(function (e) { setCollapsed(e, collapsed); });
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

  var problemsBtn = document.getElementById('problemsBtn');
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

  var hotBtn = document.getElementById('hotBtn');
  function setHot(on) {
    hotOn = on;
    hotBtn.classList.toggle('on', on);
    refreshTree();
  }
  hotBtn.addEventListener('click', function () { setHot(!hotOn); });

  // ------------------------------------------------------- time filter ---

  var minTime = document.getElementById('minTime');
  var minOp = document.getElementById('minTimeOp');
  var minCustom = document.getElementById('minTimeCustom');
  var minCustom2 = document.getElementById('minTimeCustom2');
  var minAnd = document.getElementById('minTimeAnd');
  var minCustomApply = document.getElementById('minTimeCustomApply');
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
    var opt = document.getElementById('minTimeCustomOpt');
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
  var sqlBtn = document.getElementById('sqlBtn');

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

  var detailSeg = document.getElementById('detailSeg');
  var detailEssentialBtn = document.getElementById('detailEssential');
  var detailFullBtn = document.getElementById('detailFull');
  function setDetail(full) {
    detailFull = full;
    detailEssentialBtn.classList.toggle('on', !full);
    detailFullBtn.classList.toggle('on', full);
    // Expanding a roll-up is a statement about one call site; switching to Full and back
    // should not silently keep every group open.
    expandedRollups = {};
    syncToolbarExtras();
    refreshTree();
  }
  detailEssentialBtn.addEventListener('click', function () { setDetail(false); });
  detailFullBtn.addEventListener('click', function () { setDetail(true); });

  // -------------------------------------------------------- excluded roll-ups ---

  /* The "… N excluded calls" rows are a deliberate trace of what Essential folded away, so
     they stay on by default. Hiding them is for reading a clean call flow once you have
     already accepted that the excluded types are not interesting. */
  var rollupBtn = document.getElementById('rollupBtn');

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
    var genToggle = document.getElementById('genToggle');
    document.getElementById('genCount').textContent = '(' + generatedCount + ')';
    genToggle.addEventListener('change', function () {
      entries.forEach(function (e) {
        if (e.generated) { e.checkbox.checked = genToggle.checked; e.userToggled = true; }
      });
      applyFilters();
    });
  }

  // ------------------------------------------------------------------- theme ---

  function toggleTheme() {
    var cur = document.documentElement.getAttribute('data-theme');
    var dark = cur ? cur === 'dark' : matchMedia('(prefers-color-scheme: dark)').matches;
    document.documentElement.setAttribute('data-theme', dark ? 'light' : 'dark');
  }
  document.getElementById('themeToggle').addEventListener('click', toggleTheme);

  // -------------------------------------------------------------------- help ---

  var help = document.getElementById('help');
  document.getElementById('helpBtn').addEventListener('click', function () { help.hidden = false; });
  document.getElementById('helpClose').addEventListener('click', function () { help.hidden = true; });
  help.addEventListener('click', function (e) { if (e.target === help) help.hidden = true; });

  // ------------------------------------------------------- files dropdown ---

  var fileBtn = document.getElementById('fileBtn');
  var fileMenu = document.getElementById('fileMenu');
  var fileSearch = document.getElementById('fileSearch');
  var fileNoMatch = document.getElementById('fileNoMatch');

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

  document.getElementById('fileAll').addEventListener('click', function () {
    menuMatches().forEach(function (e) { e.checkbox.checked = true; e.userToggled = true; });
    applyFilters();
  });
  document.getElementById('fileNone').addEventListener('click', function () {
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

  function setCursor(rec) {
    clearCursor();
    cursor = rec;
    // A tree row outside the window has no element yet, so the list is scrolled to it
    // first and the row exists by the time there is something to mark.
    if (view === 'tree' && rec.vi != null && rowOffsets) {
      var mid = rowOffsets[rec.vi] - (treeWrapEl.clientHeight || 600) / 2;
      treeWrapEl.scrollTop = Math.max(0, mid);
      renderTreeWindow();
      if (!rec.tr) return;
      rec.tr.classList.add('cursor');
      return;
    }
    if (rec.entry) attachEntry(rec.entry);
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
    (view === 'tree' ? s.tr : s.h).scrollIntoView({ block: 'start', behavior: 'smooth' });
    clearCursor();
  }

  function isTyping(t) {
    return t && (t.tagName === 'INPUT' || t.tagName === 'TEXTAREA' || t.tagName === 'SELECT');
  }

  document.addEventListener('keydown', function (e) {
    if (e.metaKey || e.ctrlKey || e.altKey) return;

    if (e.key === 'Escape') {
      if (!help.hidden) { help.hidden = true; return; }
      if (!fileMenu.hidden) { fileMenu.hidden = true; return; }
      if (searchEl.value !== '') { searchEl.value = ''; applyFilters(); }
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
      case '?': e.preventDefault(); help.hidden = false; break;
      case 'o': {
        e.preventDefault();
        var link = cursor && cursor.tr && cursor.tr.querySelector('td.ln a');
        if (link) link.click();
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

  var summary = document.getElementById('summary');
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
  if (treeAvailable) {
    summary.appendChild(stat(String(calls.length), calls.length === 1 ? 'Call' : 'Calls',
      'method invocations recorded, in execution order'));
  }
  summary.appendChild(stat(String(entries.length), entries.length === 1 ? 'File' : 'Files'));
  summary.appendChild(stat(String(totalLines), totalLines === 1 ? 'Line' : 'Lines'));
  summary.appendChild(stat(String(totalRed), 'Unexecuted'));

  // ------------------------------------------------- sticky offset + startup ---

  var toolbar = document.getElementById('toolbar');
  function measureToolbar() {
    document.documentElement.style.setProperty('--toolbarH', toolbar.offsetHeight + 'px');
  }
  measureToolbar();
  if (window.ResizeObserver) new ResizeObserver(measureToolbar).observe(toolbar);
  else window.addEventListener('resize', measureToolbar);

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
      msgs.push('The call tree hit the agent\'s recording cap, later invocations are missing.');
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

  document.getElementById('fidelity').textContent = treeAvailable
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

  // ============================================================ tab panels ===
  //
  // Graph and Flow are built the first time their tab is opened and never again: the
  // report is a single file that has to open instantly, and neither panel is worth any
  // DOM, layout or canvas work for a reader who only ever looks at the trace. Re-opening
  // a tab re-uses what was already built; only the flow canvas repaints, because a theme
  // change or a resize can invalidate the pixels.

  var tabTrace = document.getElementById('tabTrace');
  var tabGraph = document.getElementById('tabGraph');
  var tabFlow = document.getElementById('tabFlow');
  var tracePanel = document.getElementById('tracePanel');
  var graphPanel = document.getElementById('graphPanel');
  var flowPanel = document.getElementById('flowPanel');
  var traceControls = document.getElementById('traceControls');
  var graphControls = document.getElementById('graphControls');
  var flowControls = document.getElementById('flowControls');
  var tab = 'trace';
  var graphBuilt = false;
  var flowBuilt = false;

  function setTab(next) {
    if (next === tab) return;
    tab = next;
    tabTrace.classList.toggle('on', next === 'trace');
    tabGraph.classList.toggle('on', next === 'graph');
    tabFlow.classList.toggle('on', next === 'flow');
    tracePanel.hidden = next !== 'trace';
    graphPanel.hidden = next !== 'graph';
    flowPanel.hidden = next !== 'flow';
    traceControls.hidden = next !== 'trace';
    graphControls.hidden = next !== 'graph';
    flowControls.hidden = next !== 'flow';
    // The filter lives in the stats row, which is outside the panels and so survives a
    // tab change. It only filters code, so it goes away where there is no code to filter.
    searchEl.hidden = next !== 'trace';
    hideFlowTip();
    if (next === 'graph') {
      if (!graphBuilt) { graphBuilt = true; buildGraph(); }
      renderGraph();
    } else if (next === 'flow') {
      if (!flowBuilt) { flowBuilt = true; buildFlow(); }
      drawFlow();
    } else {
      applyFilters();   // restores the trace view's own count in the toolbar
    }
  }

  tabTrace.addEventListener('click', function () { setTab('trace'); });
  tabGraph.addEventListener('click', function () { setTab('graph'); });
  tabFlow.addEventListener('click', function () { setTab('flow'); });

  // ---------------------------------------------------------------- graph ---

  var GRAPH_TOP = 40;          // rows drawn before "Show all files" appears
  var graphRows = [];
  var graphSql = true;
  var graphAll = false;
  var graphBody = document.getElementById('graphBody');
  var graphEmpty = document.getElementById('graphEmpty');
  var graphNote = document.getElementById('graphNote');
  var graphSqlBtn = document.getElementById('graphSqlBtn');
  var graphAllBtn = document.getElementById('graphAllBtn');

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
  function buildGraph() {
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
          self: 0,
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
      var r = rowFor(f);
      r.sql += c.totalMicros || 0;
      r.queries++;
      // Identical statements issued from one file are the signature of an N+1: a loop
      // that queries once per row instead of once for the set. Whitespace and case are
      // normalised so the same statement built two ways still groups.
      var key = String(c.sql).replace(/\s+/g, ' ').trim().toLowerCase();
      var g = r.dups[key];
      if (!g) g = r.dups[key] = { n: 0, micros: 0, sql: String(c.sql).replace(/\s+/g, ' ').trim() };
      g.n++;
      g.micros += c.totalMicros || 0;
    });

    // Worst repeat per file, and the run-wide worst for the note line.
    var worst = null;
    graphRows.forEach(function (r) {
      Object.keys(r.dups).forEach(function (k) {
        var g = r.dups[k];
        if (g.n < 2) return;
        if (!r.dup || g.n > r.dup.n) r.dup = g;
        if (!worst || g.n > worst.g.n) worst = { g: g, file: r.label };
      });
    });

    var noteBits = [];
    if (worst) {
      noteBits.push('Possible N+1: ' + worst.g.n + ' identical queries from '
        + worst.file + ' costing ' + fmt(worst.g.micros) + ' in total.');
    }
    if (orphanSql > 0) {
      noteBits.push(fmt(orphanSql) + ' of SQL could not be charged to a file, its caller '
        + 'is not in this report.');
    }
    if (data.callsTruncated) {
      noteBits.push('The call list was truncated when this run was recorded, so SQL '
        + 'totals are a lower bound.');
    }
    graphNote.textContent = noteBits.join(' ');
    graphNote.hidden = noteBits.length === 0;
  }

  function renderGraph() {
    var useSql = graphSql;
    var rows = graphRows.filter(function (r) {
      return (r.self + (useSql ? r.sql : 0)) > 0;
    });
    rows.sort(function (a, b) {
      return (b.self + (useSql ? b.sql : 0)) - (a.self + (useSql ? a.sql : 0));
    });

    graphEmpty.hidden = rows.length > 0;
    graphAllBtn.hidden = rows.length <= GRAPH_TOP;
    graphAllBtn.classList.toggle('on', graphAll);
    graphAllBtn.textContent = graphAll ? 'Show top ' + GRAPH_TOP : 'Show all files';

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
      name.appendChild(document.createTextNode(r.label));
      if (r.sub) {
        name.title = r.sub;
        name.appendChild(el('span', 'sub', r.sub));
      }
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
      if (r.queries > 0) {
        meta.appendChild(el('span', 'qcount', r.queries + (r.queries === 1 ? ' query' : ' queries')));
      }
      if (r.dup) {
        var pill = el('span', 'nplus1', '×' + r.dup.n);
        pill.title = r.dup.n + ' identical queries from this file, ' + fmt(r.dup.micros)
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
      row.appendChild(time);

      frag.appendChild(row);
    });

    graphBody.textContent = '';
    graphBody.appendChild(frag);
    countEl.textContent = rows.length + (rows.length === 1 ? ' file' : ' files')
      + ' · ' + fmt(grand) + ' attributed';
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

  // ------------------------------------------------------------ flow graph ---

  var FLOW_ROW = 26;           // vertical pitch of one call
  var FLOW_BOX = 20;           // box height inside that pitch
  var FLOW_INDENT = 22;        // horizontal step per depth level
  var FLOW_PAD = 14;
  var FLOW_MINW = 150;
  var FLOW_MAXW = 460;
  var FLOW_GAP = 6;            // space between two boxes in tree mode
  var FLOW_LINE = 15;          // line height inside a multi-line SQL box
  var FLOW_SQL_MAX = 8;        // clause lines shown before a statement is cut off
  var FLOW_SQL_FONT = '11px ui-monospace,SFMono-Regular,Menlo,monospace';
  var FLOW_RULER = 22;         // headroom above the flame stack for the time axis
  var FLOW_FLAME_W = 1100;     // width of the time axis in flame mode, before zoom
  var ZOOM_MIN = 0.4;
  var ZOOM_MAX = 3;
  var ZOOM_STEP = 1.25;

  var flowWrap = document.getElementById('flowWrap');
  var flowCanvas = null;      // created on first open of the tab, not on load
  var flowEmpty = document.getElementById('flowEmpty');
  var flowNote = document.getElementById('flowNote');
  var flowTip = document.getElementById('flowTip');
  var flowSqlBtn = document.getElementById('flowSqlBtn');
  var flowGroupBtn = document.getElementById('flowGroupBtn');
  var flowHotBtn = document.getElementById('flowHotBtn');
  var flowFlameBtn = document.getElementById('flowFlameBtn');
  var flowCrumb = document.getElementById('flowCrumb');
  var flowFitBtn = document.getElementById('flowFitBtn');
  var flowOutBtn = document.getElementById('flowOutBtn');
  var flowInBtn = document.getElementById('flowInBtn');
  var flowPngBtn = document.getElementById('flowPngBtn');

  var flowSpacer = null;       // sized to the whole diagram; the canvas is not
  var flowContentW = 0;        // diagram size in its own coordinates, pre-scale
  var flowContentH = 0;
  var drawnBySeq = {};         // seq -> drawn node, for connectors to reach an off-screen parent
  var flowNodes = [];          // every call, pre-order, with subtree spans
  var flowBySeq = {};          // seq -> node, for walking back up the zoom trail
  var flowVisible = [];        // what survives the SQL, fold and group filters
  var flowDrawn = [];          // what is actually painted: flowVisible, or one subtree
  var flowByDepth = {};        // depth -> drawn nodes, for flame hit-testing
  var flameRoot = null;        // seq the flame view is zoomed into, null for the whole run
  var collapsedFlow = {};      // seq -> true, subtree folded away by a click
  var flowSql = true;
  var flowGroup = false;
  var flowHot = false;
  var flowFlame = false;
  var flowFit = false;
  var flowHover = null;        // seq under the cursor, for the highlight
  var flameSpan = 0;           // pixel width of the time axis, for the ruler
  var flameTotal = 0;          // micros that width represents
  var flowZoom = 1;
  var flowScale = 1;
  var flowBoxW = 320;
  var flowRootTotal = 0;

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
        sqlLines: c.sql ? formatSqlLines(tokenizeSql(String(c.sql))).slice(0, FLOW_SQL_MAX)
          : null,
        span: 1
      });
      var ch = childrenBySeq[c.seq] || [];
      for (var k = ch.length - 1; k >= 0; k--) {
        stack.push({ call: ch[k], depth: it.depth + 1 });
      }
    }
    // Subtree sizes, backwards. Each node hops its direct children by their own spans,
    // which are already final, so the whole pass is linear rather than quadratic.
    for (var j = flowNodes.length - 1; j >= 0; j--) {
      var n = flowNodes[j];
      var t = j + 1;
      var s = 1;
      while (t < flowNodes.length && flowNodes[t].depth > n.depth) {
        s += flowNodes[t].span;
        t += flowNodes[t].span;
      }
      n.span = s;
      flowBySeq[n.seq] = n;
    }

    flowRootTotal = roots.reduce(function (m, x) {
      return Math.max(m, x.totalMicros || 0);
    }, 0);

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
    var shown = flowVisible.length;
    // Deliberately not naming a cause: SQL, grouping, a fold and the file picker can each
    // take steps out, often at the same time, and guessing which would sometimes be wrong.
    var bits = [shown === flowNodes.length
      ? shown + ' steps in execution order'
      : shown + ' of ' + flowNodes.length + ' steps shown'];
    if (data.callsTruncated) {
      bits.push('the recording truncated the call list, so the tail is missing');
    }
    flowNote.textContent = bits.join(' · ') + '. Click a step to fold its calls away.';
  }

  /** First few words of a statement: enough to tell queries apart in a small box. */
  function sqlLabel(sql) {
    var s = String(sql).replace(/\s+/g, ' ').trim();
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
  function computeFlowVisible() {
    var out = [];
    var i = 0;
    while (i < flowNodes.length) {
      var n = flowNodes[i];
      // Unticking a file hides its calls and everything they went on to do, which is what
      // the same picker already means in the Tree. Dropping the frame but keeping its
      // children would leave callees floating under a caller that is no longer drawn.
      if (n.cname && !classSelected(n.cname)) { i += n.span; continue; }
      if (!flowSql && n.isSql) { i += n.span; continue; }

      var reps = 1;
      var sum = n.total;
      var j = i + n.span;
      if (flowGroup) {
        while (j < flowNodes.length) {
          var m = flowNodes[j];
          // Adjacent in pre-order at the same depth under the same parent is exactly
          // "next sibling", so no sibling list lookup is needed.
          if (m.depth !== n.depth || m.parentSeq !== n.parentSeq || m.key !== n.key) break;
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
   * Categorical fills for flame frames, one slot per owning class.
   *
   * A fixed, validated order rather than a hue spun out of a hash: hashed hues land
   * wherever they land, and two classes that happen to collide, or a colour that happens
   * to sit on top of the SQL orange, are indistinguishable with no way to fix it. These
   * six clear the colour-blindness and normal-vision separation floors in both themes as
   * an ordered list, and are assigned in first-appearance order so a class keeps its
   * colour no matter what the filters hide.
   *
   * Yellow is deliberately absent from the six. It measures ΔE 1.2 against the SQL orange
   * under protanopia, which would make a query and a method indistinguishable — the one
   * distinction this diagram cannot afford to lose.
   *
   * Solid, not a wash: composited down to a tint over the box fill, the same six collapse
   * below every separation floor.
   */
  var FLAME_LIGHT = ['#2a78d6', '#1baf7a', '#e34948', '#008300', '#e87ba4', '#4a3aa7'];
  var FLAME_DARK = ['#3987e5', '#199e70', '#e66767', '#008300', '#d55181', '#9085e9'];
  var FLAME_OTHER_LIGHT = '#8b8f94';   // the 7th class onward, deliberately colourless
  var FLAME_OTHER_DARK = '#6b7075';
  var ownerSlot = {};
  var ownerSlotCount = 0;

  function classFill(name, dark) {
    var slot = ownerSlot[name];
    if (slot === undefined) {
      // Past the palette the honest answer is "no colour", not a recycled one: two classes
      // sharing a hue would read as one.
      slot = ownerSlot[name] = ownerSlotCount < FLAME_LIGHT.length ? ownerSlotCount : -1;
      ownerSlotCount++;
    }
    if (slot < 0) return dark ? FLAME_OTHER_DARK : FLAME_OTHER_LIGHT;
    return (dark ? FLAME_DARK : FLAME_LIGHT)[slot];
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
      flowEmpty.textContent = 'Every step is filtered out.'
        + ' Re-tick files in the Files picker, or turn SQL back on.';
      flowEmpty.hidden = false;
      flowWrap.hidden = true;
      return;
    }
    flowEmpty.hidden = true;
    flowWrap.hidden = false;
    flowByDepth = {};
    var maxDepth = 0;
    flowVisible.forEach(function (n) { if (n.depth > maxDepth) maxDepth = n.depth; });

    var avail = Math.max(320, flowWrap.clientWidth || 900);
    var contentW;
    var contentH;

    if (flowFlame) {
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
        var d = n.depth - baseDepth;
        if (d > maxD) maxD = d;
        n.x = FLOW_PAD + (total > 0 ? ((n.t0 - baseT0) / total) * span : 0);
        // Never below a pixel: a 30 µs call inside a 48 ms request is far thinner than
        // that, and a box with no width is a box the reader cannot hover.
        n.w = Math.max(1, total > 0 ? (n.shown / total) * span : span);
        // Rows touch. The stack of columns is the thing being read, and a gap between
        // levels breaks the one line the eye follows from a caller down into its callee.
        n.y = FLOW_PAD + FLOW_RULER + d * FLOW_BOX;
        n.h = FLOW_BOX;
        (flowByDepth[d] || (flowByDepth[d] = [])).push(n);
      });
      flowDrawn = drawn;
      indexDrawn();
      flameSpan = span;
      flameTotal = total;
      contentW = FLOW_PAD * 2 + span;
      contentH = FLOW_PAD * 2 + FLOW_RULER + (maxD + 1) * FLOW_BOX;
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
    renderCrumb();

    flowContentW = contentW;
    flowContentH = contentH;
    // The scrollbar has to describe the whole diagram even though the canvas never is it.
    flowSpacer.style.width = Math.ceil(contentW * flowScale) + 'px';
    flowSpacer.style.height = Math.ceil(contentH * flowScale) + 'px';

    // The count sits in the trace-point row, which is outside the panels and so stays on
    // screen across tabs. Every view has to keep it true, or it would describe whichever
    // tab was opened last.
    countEl.textContent = 'Showing ' + flowDrawn.length + ' of ' + flowNodes.length
      + (flowNodes.length === 1 ? ' step' : ' steps');

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
  function paintFlow() {
    if (!flowCanvas || flowContentH === 0) return;

    var viewW = flowWrap.clientWidth || 1;
    var viewH = flowWrap.clientHeight || 1;
    var sx = flowWrap.scrollLeft;
    var sy = flowWrap.scrollTop;

    // Pinned back over the viewport it just scrolled away from.
    flowCanvas.style.left = sx + 'px';
    flowCanvas.style.top = sy + 'px';

    var dpr = window.devicePixelRatio || 1;
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

    ctx.font = '12px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif';
    ctx.textBaseline = 'middle';

    if (flowFlame) drawRuler(ctx, c, flameSpan, flameTotal, flowContentH);
    else drawConnectors(ctx, c, y0, y1);

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
   * <p>Tree mode stacks rows in order, so the range is found by bisection. Flame mode packs
   * every node of a depth onto one row and the array is not sorted by y at all, so there the
   * honest answer is the whole array and the per-node test below does the rejecting; that
   * costs one comparison per node, against re-laying-out all of them.
   */
  function visibleSlice(y0, y1) {
    if (flowFlame) return { from: 0, to: flowDrawn.length };
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
      var fill = n.isSql ? c.sql : classFill(n.owner, c.dark);
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

    var mid = n.y + h / 2;
    var right = n.x + n.w - 7;

    var time = fmt(n.shown);
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
    ctx.font = '12px -apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif';
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

  function flowHit(e) {
    // The canvas covers the viewport, not the diagram, so its own top-left is wherever the
    // wrap happens to be scrolled to. Adding the scroll offset puts the pointer back into
    // the diagram's coordinates, which is what every node position is expressed in.
    var r = flowCanvas.getBoundingClientRect();
    var x = (e.clientX - r.left + flowWrap.scrollLeft) / flowScale;
    var y = (e.clientY - r.top + flowWrap.scrollTop) / flowScale;
    var list;
    if (flowFlame) {
      // One row per depth there, so the band narrows the search to a single level.
      list = flowByDepth[Math.floor((y - FLOW_PAD - FLOW_RULER) / FLOW_BOX)] || [];
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

  function onFlowMove(e) {
    var n = flowHit(e);
    if (!n) { hideFlowTip(); return; }
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
    var sub = 'step ' + (n.seq + 1) + ' · ' + fmt(n.shown);
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
    function (v) { flowFlame = v; if (!v) flameRoot = null; });
  flowToggle(flowFitBtn, function () { return flowFit; }, function (v) { flowFit = v; });

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
   */
  var CANVAS_MAX = 32767;

  flowPngBtn.addEventListener('click', function () {
    if (!flowCanvas || flowContentH === 0) return;   // nothing drawn yet
    var dpr = window.devicePixelRatio || 1;
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
  });

  // A canvas holds pixels, not a description of them: both a repaint-worthy change and a
  // resize have to redraw by hand. Only when the tab is actually showing.
  window.addEventListener('resize', function () {
    if (tab === 'flow' && flowBuilt) drawFlow();
  });
  document.getElementById('themeToggle').addEventListener('click', function () {
    if (tab === 'flow' && flowBuilt) drawFlow();
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
    setView('tree');
  }
  applySort();
  updateNotice();
})();
