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
  var calls = data.calls || [];

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

  var AUTO_COLLAPSE_ABOVE = 15;   // file count past which the Files view starts collapsed
  var AUTO_EXPAND_TOP = 5;        // slowest N left open in that case
  var FOLD_RUN_AFTER = 3;         // identical consecutive sibling calls shown before folding
  var MAX_TREE_ROWS = 20000;      // ceiling on rendered tree rows

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

  document.getElementById('meta').textContent =
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

    scroll.appendChild(table);
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
    return { kind: 'frame', depth: depth, node: node, tr: tr, parent: parentRow, vis: true };
  }

  function codeRow(lineModel, file, depth, parentRow) {
    var st = lineModel.status || 'NONE';
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
    return {
      kind: 'line', depth: depth, tr: tr, parent: parentRow, status: st,
      text: text.toLowerCase(), line: lineModel.line, file: file, vis: true
    };
  }

  function foldRow(nodes, depth, parentRow, foldId) {
    var first = nodes[0];
    var total = 0;
    nodes.forEach(function (n) { total += (n.totalMicros || 0); });
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
    // A folded run of identical queries is still SQL, so it hides with the rest.
    return { kind: 'fold', sql: first.sql != null, depth: depth, tr: tr,
      parent: parentRow, vis: true };
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
    return { kind: 'fold', rollup: true, depth: depth, tr: tr, parent: parentRow, vis: true };
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

    var frag = document.createDocumentFragment();
    treeRows.forEach(function (r) { frag.appendChild(r.tr); });
    treeTable.appendChild(frag);
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

    treeRows.forEach(function (r) {
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
    treeRows.forEach(function (r) {
      r.tr.style.display = r.vis ? '' : 'none';
      if (!r.vis) return;
      if (r.kind === 'line') shownLines++;
      else if (r.kind === 'frame') shownFrames++;
    });

    // Counted in calls, not lines: a method invoked twice renders its lines twice, so
    // "x of totalLines" would be meaningless here.
    countEl.textContent = 'Showing ' + shownFrames + ' of ' + calls.length
      + ' call(s) · ' + shownLines + ' line(s)'
      + (hiddenFrameCount ? ' · ' + hiddenFrameCount + ' folded into excluded types' : '');
    treeEmpty.hidden = shownFrames !== 0 || shownLines !== 0;
    if (cursor && (cursor.tr.style.display === 'none' || !cursor.tr.isConnected)) clearCursor();
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
    countEl.textContent = 'Showing ' + shownLines + ' of ' + totalLines
      + ' line(s) · ' + shownFiles + ' file(s)';
    emptyEl.hidden = shownLines !== 0 && shownFiles !== 0;
    if (cursor && (cursor.tr.style.display === 'none' || cursor.entry.box.hidden)) clearCursor();
  }

  function applyFilters() {
    if (view === 'tree') refreshTree(); else applyFileFilters();
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
    rollupBtn.hidden = !tree || !canFold || detailFull;
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

  function setRollups(on) {
    rollupOn = on;
    rollupBtn.classList.toggle('on', on);
    rollupBtn.title = on
      ? 'Hide the "N excluded calls" summary rows'
      : 'Show the "N excluded calls" summary rows again';
    applyTreeFilters();
  }
  rollupBtn.addEventListener('click', function () { setRollups(!rollupOn); });

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
    if (cursor) cursor.tr.classList.remove('cursor');
    cursor = null;
  }

  function setCursor(rec) {
    clearCursor();
    cursor = rec;
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
        var link = cursor && cursor.tr.querySelector('td.ln a');
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

  if (!treeAvailable) {
    // Payload from an agent with no call tree: Tree cannot be built, so fall back.
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
