#!/usr/bin/env python3
"""
Builds docs/demo-report.html, a sample Deju report for the Marketplace listing.

It reuses the REAL report template, stylesheet and script from the plugin resources and
performs the same substitutions HtmlReportGenerator does, so the demo cannot drift into
showing a layout the plugin does not actually produce. Only the payload is synthetic.

The trace is a coffee-shop ordering API, chosen so the code needs no domain knowledge and
every feature has an obvious reason to appear:

  * green / yellow / red      a validation guard whose failure branch never ran
  * per-call inclusive timing  and a hot path that leads to the real problem
  * SQL nodes                  including a textbook N+1 insert loop
  * identical-run folding      the five repeated menu lookups
  * excluded-type roll-up      the DTO mapping at the end
  * Files view                 method sections with line and timing counts
  * percentages                every duration's share of the 48 ms run
  * Customize panel            the header's slider button, over a full set of tabs

The demo keeps every tab and every default, because a Marketplace sample exists to show
what the plugin can do. To see what a trimmed export looks like instead, edit DEMO_TABS or
DEMO_PREFS below; both go through the same cutting and substitution a real export does.

Usage:  python3 scripts/make-demo-report.py
"""

import json
import base64
import gzip
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "plugin/src/main/resources"
OUT = ROOT / "docs/demo-report.html"

PROJECT = "example-api"
SRC_ROOT = "src/main/java"

# --------------------------------------------------------------------------- sources ---
# Full source of each traced file. Line numbers below are 1-based indices into these.

SOURCES = {
    "com.example.order.OrderController": ("OrderController.java", """\
package com.example.order;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Takes a coffee order and hands back a receipt. */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    @PostMapping
    public ResponseEntity<OrderDto> placeOrder(@RequestBody OrderRequest request) {
        if (request.items().isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        OrderDto receipt = orders.place(request);
        return ResponseEntity.ok(receipt);
    }
}
"""),
    "com.example.order.OrderService": ("OrderService.java", """\
package com.example.order;

import com.example.loyalty.LoyaltyService;
import com.example.menu.MenuRepository;
import com.example.pricing.DiscountPolicy;
import com.example.pricing.PriceCalculator;
import org.springframework.stereotype.Service;

@Service
public class OrderService {

    private final MenuRepository menu;
    private final PriceCalculator calculator;
    private final DiscountPolicy discounts;
    private final LoyaltyService loyalty;
    private final OrderRepository orders;

    /** Turns a list of drink names into a saved order and a receipt. */
    public OrderDto place(OrderRequest request) {
        Order order = new Order(request.customerId());
        for (String name : request.items()) {
            order.add(menu.findByName(name));   // one query per drink
        }
        Money subtotal = calculator.total(order, request.size());
        Money total = discounts.apply(subtotal, request.customerId());
        loyalty.award(request.customerId(), total);
        orders.save(order, total);
        return OrderDto.of(order, total);
    }
}
"""),
    "com.example.menu.MenuRepository": ("MenuRepository.java", """\
package com.example.menu;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class MenuRepository {

    private final JdbcTemplate jdbc;

    /** One lookup per name, the classic N+1 once it is called inside a loop. */
    public Drink findByName(String name) {
        return jdbc.queryForObject(
                "select id, name, base_price_pence from drink where name = ?",
                DRINK_ROW, name);
    }
}
"""),
    "com.example.pricing.PriceCalculator": ("PriceCalculator.java", """\
package com.example.pricing;

import com.example.menu.Drink;
import com.example.order.Order;
import com.example.order.OrderLine;
import org.springframework.stereotype.Component;

@Component
public class PriceCalculator {

    private static final Money LARGE_SURCHARGE = Money.pence(70);

    /** Adds the order up, applying the size surcharge to each drink. */
    public Money total(Order order, Size size) {
        Money sum = Money.ZERO;
        for (OrderLine line : order.lines()) {
            sum = sum.plus(priceOf(line.drink(), size));
        }
        return sum;
    }

    Money priceOf(Drink drink, Size size) {
        Money price = drink.basePrice();
        if (size == Size.LARGE) {
            price = price.plus(LARGE_SURCHARGE);
        }
        return price;
    }
}
"""),
    "com.example.pricing.DiscountPolicy": ("DiscountPolicy.java", """\
package com.example.pricing;

import com.example.loyalty.LoyaltyService;
import org.springframework.stereotype.Component;

@Component
public class DiscountPolicy {

    private final LoyaltyService loyalty;

    /** Members get 10% off; orders over 20.00 get a further 5%. */
    public Money apply(Money subtotal, long customerId) {
        Money total = subtotal;
        if (loyalty.isMember(customerId)) {
            total = total.minusPercent(10);
        }
        if (total.isOver(Money.pounds(20))) {
            total = total.minusPercent(5);
        }
        return total;
    }
}
"""),
    "com.example.loyalty.LoyaltyService": ("LoyaltyService.java", """\
package com.example.loyalty;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
public class LoyaltyService {

    private final JdbcTemplate jdbc;

    public boolean isMember(long customerId) {
        Integer rows = jdbc.queryForObject(
                "select count(*) from membership where customer_id = ?",
                Integer.class, customerId);
        return rows != null && rows > 0;
    }

    /** One point for every whole pound spent. */
    public void award(long customerId, Money total) {
        int points = total.wholePounds();
        jdbc.update("update customer set points = points + ? where id = ?",
                points, customerId);
    }
}
"""),
    "com.example.order.OrderRepository": ("OrderRepository.java", """\
package com.example.order;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class OrderRepository {

    private final JdbcTemplate jdbc;

    public void save(Order order, Money total) {
        jdbc.update("insert into customer_order (customer_id, total_pence) values (?, ?)",
                order.customerId(), total.pence());
        for (OrderLine line : order.lines()) {
            jdbc.update("insert into order_line (order_id, drink_id, price_pence) values (?, ?, ?)",
                    order.id(), line.drinkId(), line.pricePence());
        }
    }
}
"""),
    "com.example.order.OrderDto": ("OrderDto.java", """\
package com.example.order;

import java.util.List;

/** What the caller gets back. Data only, nothing in here is worth stepping through. */
public record OrderDto(long orderId, String currency, int totalPence, List<OrderLineDto> lines) {

    public static OrderDto of(Order order, Money total) {
        return new OrderDto(
                order.id(),
                total.currency(),
                total.pence(),
                order.lines().stream().map(OrderLineDto::of).toList());
    }
}
"""),
    "com.example.order.OrderLineDto": ("OrderLineDto.java", """\
package com.example.order;

public record OrderLineDto(String drink, int pricePence) {

    static OrderLineDto of(OrderLine line) {
        return new OrderLineDto(line.drink().name(), line.pricePence());
    }
}
"""),
}

# ------------------------------------------------------------------------- coverage ---
# (line, status, branchesCovered, branchesTotal, selfMicros) per method.
# The first tuple of each method also carries the method's inclusive/self total.

COVERAGE = {
    "com.example.order.OrderController": [
        ("placeOrder", 47900, 560, [
            (20, "FULL", None, None, 3),
            (22, "PARTIAL", 1, 2, 41),
            (23, "NONE", None, None, None),
            (25, "FULL", None, None, 47340),
            (26, "FULL", None, None, 12),
        ]),
    ],
    "com.example.order.OrderService": [
        ("place", 47340, 350, [
            (21, "FULL", None, None, 18),
            (22, "FULL", 2, 2, 9),
            (23, "FULL", None, None, 12650),
            (25, "FULL", None, None, 640),
            (26, "FULL", None, None, 3980),
            (27, "FULL", None, None, 2210),
            (28, "FULL", None, None, 27200),
            (29, "FULL", None, None, 310),
        ]),
    ],
    "com.example.menu.MenuRepository": [
        ("findByName", 12650, 190, [
            (13, "FULL", None, None, 12650),
        ]),
    ],
    "com.example.pricing.PriceCalculator": [
        ("total", 640, 90, [
            (15, "FULL", None, None, 11),
            (16, "FULL", 2, 2, 14),
            (17, "FULL", None, None, 512),
            (19, "FULL", None, None, 8),
        ]),
        ("priceOf", 512, 512, [
            (22, "FULL", None, None, 180),
            (23, "FULL", 2, 2, 121),
            (24, "FULL", None, None, 96),
            (26, "FULL", None, None, 115),
        ]),
    ],
    "com.example.pricing.DiscountPolicy": [
        ("apply", 3980, 70, [
            (13, "FULL", None, None, 9),
            (14, "PARTIAL", 1, 2, 3910),
            (15, "FULL", None, None, 22),
            (17, "PARTIAL", 1, 2, 27),
            (18, "NONE", None, None, None),
            (20, "FULL", None, None, 12),
        ]),
    ],
    "com.example.loyalty.LoyaltyService": [
        ("isMember", 3910, 50, [
            (13, "FULL", None, None, 3860),
            (16, "FULL", 2, 2, 14),
        ]),
        ("award", 2210, 60, [
            (20, "FULL", None, None, 18),
            (21, "FULL", None, None, 2150),
        ]),
    ],
    "com.example.order.OrderRepository": [
        ("save", 27200, 200, [
            (12, "FULL", None, None, 3100),
            (14, "FULL", 2, 2, 21),
            (15, "FULL", None, None, 23900),
        ]),
    ],
    "com.example.order.OrderDto": [
        ("of", 310, 120, [
            (9, "FULL", None, None, 14),
            (10, "FULL", None, None, 9),
            (11, "FULL", None, None, 11),
            (12, "FULL", None, None, 8),
            (13, "FULL", None, None, 190),
        ]),
    ],
    "com.example.order.OrderLineDto": [
        ("of", 190, 190, [
            (6, "FULL", None, None, 190),
        ]),
    ],
}

EXCLUDED = ["com.example.order.OrderDto", "com.example.order.OrderLineDto"]

DRINKS = ["Flat White", "Latte", "Cortado", "Mocha", "Espresso"]

# Tabs written into the demo, and the position it opens in. Mirrors ReportPrefs, whose
# defaults are the report's long-standing behaviour; the demo keeps all of them, because a
# Marketplace sample exists to show what the plugin can do.
#
# Kept as data rather than inlined so that regenerating the demo with a tab cut, to check
# what a trimmed export actually looks like, is a one-line edit rather than a rewrite.
ALL_TABS = ["trace", "graph", "flow", "timeline", "findings"]
DEMO_TABS = list(ALL_TABS)
DEMO_PREFS = {
    "openTab": "trace",
    "view": "tree",
    "density": "normal",
    "theme": "auto",
    "showTime": True,
    "showPercent": True,
    "showStep": True,
    "showLine": True,
    "sql": True,
    "groupRepeats": False,
    "collapseTree": False,
    "collapseSections": False,
}

SQL_DRINK = "select id, name, base_price_pence from drink where name = ?"
SQL_MEMBER = "select count(*) from membership where customer_id = ?"
SQL_POINTS = "update customer set points = points + ? where id = ?"
SQL_ORDER = "insert into customer_order (customer_id, total_pence) values (?, ?)"
SQL_LINE = "insert into order_line (order_id, drink_id, price_pence) values (?, ?, ?)"


def build_files():
    files = []
    for fqcn, methods in COVERAGE.items():
        source_name, source = SOURCES[fqcn]
        text = source.splitlines()
        lines = []
        for method, total, self_micros, entries in methods:
            first = True
            for line_no, status, bc, bt, micros in entries:
                lines.append({
                    "line": line_no,
                    "status": status,
                    "branchesCovered": bc,
                    "branchesTotal": bt,
                    "timeMicros": micros,
                    # Carried on the method's first covered line only; the report shows it
                    # as "▸ total" and uses self time for the "slowest first" ranking.
                    "methodTotalMicros": total if first else None,
                    "methodSelfMicros": self_micros if first else None,
                    "methodName": method,
                    "methodStart": first,
                    "code": text[line_no - 1],
                })
                first = False
        path = SRC_ROOT + "/" + fqcn.replace(".", "/") + ".java"
        files.append({
            "fqClassName": fqcn,
            "sourceFileName": source_name,
            "path": path,
            "absPath": "/Users/dev/" + PROJECT + "/" + path,
            "lines": lines,
        })
    return files


class Trace:
    """Emits call nodes in execution order, which is what the report relies on."""

    def __init__(self):
        self.calls = []

    def call(self, cls, method, parent, site, micros):
        return self._add({"className": cls, "methodName": method, "callSiteLine": site,
                          "totalMicros": micros, "sql": None}, parent)

    def sql(self, statement, parent, site, micros):
        return self._add({"className": None, "methodName": None, "callSiteLine": site,
                          "totalMicros": micros, "sql": statement}, parent)

    def _add(self, node, parent):
        seq = len(self.calls)
        node["seq"] = seq
        node["parentSeq"] = parent
        self.calls.append({
            "seq": seq, "parentSeq": parent, "className": node["className"],
            "methodName": node["methodName"], "callSiteLine": node["callSiteLine"],
            "totalMicros": node["totalMicros"], "sql": node["sql"],
        })
        return seq


def encode_calls(calls):
    """Columns plus string tables, matching HtmlReportGenerator.callModel().

    The demo has to be written the same way the plugin writes a real export, or it stops
    being a test of the reader. `seq` is the position, so it is not stored.
    """
    tables, index = {"class": [], "method": [], "sql": []}, {}

    def intern(kind, value):
        if value is None:
            return -1
        key = (kind, value)
        if key not in index:
            index[key] = len(tables[kind])
            tables[kind].append(value)
        return index[key]

    return {
        "n": len(calls),
        "classTable": tables["class"],
        "methodTable": tables["method"],
        "sqlTable": tables["sql"],
        "parentSeq": [c["parentSeq"] for c in calls],
        "className": [intern("class", c["className"]) for c in calls],
        "methodName": [intern("method", c["methodName"]) for c in calls],
        "sql": [intern("sql", c["sql"]) for c in calls],
        "callSiteLine": [c["callSiteLine"] for c in calls],
        "totalMicros": [c["totalMicros"] for c in calls],
    }


# Mirrors HtmlReportGenerator.GZIP_ABOVE_BYTES; keep the two in step or the demo stops
# being a test of the reader the plugin actually produces.
GZIP_ABOVE_BYTES = 256 * 1024


def payload_block(raw_json):
    """The <script> element carrying the payload, compressed once it is worth it."""
    utf8 = raw_json.encode("utf-8")
    if len(utf8) < GZIP_ABOVE_BYTES:
        safe = raw_json.replace("</", "<\\/")
        return f'<script type="application/json" id="deju-data">{safe}</script>'
    packed = gzip.compress(utf8, 9)
    b64 = base64.b64encode(packed).decode("ascii")
    return f'<script type="application/octet-stream" id="deju-data-gz">{b64}</script>'


def prefs_block(prefs, tabs):
    """The <script> element carrying the starting position, as ReportPrefs.toModel() writes it.

    The tab list is deliberately not in it: by the time the report runs, a dropped tab is
    simply not in the document, and openTab is corrected here so the file cannot open on a
    tab that was cut out from under it.
    """
    model = dict(prefs)
    if model["openTab"] not in tabs:
        model["openTab"] = next((t for t in ALL_TABS if t in tabs), "trace")
    return ('<script type="application/json" id="deju-prefs">'
            + json.dumps(model, ensure_ascii=False) + "</script>")


def drop_tabs(template, tabs):
    """Cuts the markup of every tab not in `tabs`, exactly as HtmlReportGenerator.dropTabs does.

    Mirrored rather than skipped even though the demo keeps every tab: the point of this
    script is that the demo goes through the same steps a real export does, so a template
    change that breaks the cutting is caught here too.
    """
    out = template
    for tab in ALL_TABS:
        if tab in tabs:
            continue
        opener, closer = f"<!--tab:{tab}-->", f"<!--/tab:{tab}-->"
        while True:
            start = out.find(opener)
            if start < 0:
                break
            end = out.find(closer, start)
            if end < 0:
                break       # unbalanced template; leave the rest of the document alone
            out = out[:start] + out[end + len(closer):]
    return out


def build_calls():
    t = Trace()
    root = t.call("com.example.order.OrderController", "placeOrder", -1, None, 47900)
    place = t.call("com.example.order.OrderService", "place", root, 25, 47340)

    # Five consecutive identical lookups: the report folds the run after three.
    for micros in (8120, 1180, 1090, 1150, 1110):
        find = t.call("com.example.menu.MenuRepository", "findByName", place, 23, micros)
        t.sql(SQL_DRINK, find, 13, micros - 120)

    total = t.call("com.example.pricing.PriceCalculator", "total", place, 25, 640)
    for micros in (140, 96, 92, 94, 90):
        t.call("com.example.pricing.PriceCalculator", "priceOf", total, 17, micros)

    apply_ = t.call("com.example.pricing.DiscountPolicy", "apply", place, 26, 3980)
    member = t.call("com.example.loyalty.LoyaltyService", "isMember", apply_, 14, 3910)
    t.sql(SQL_MEMBER, member, 13, 3860)

    award = t.call("com.example.loyalty.LoyaltyService", "award", place, 27, 2210)
    t.sql(SQL_POINTS, award, 21, 2150)

    save = t.call("com.example.order.OrderRepository", "save", place, 28, 27200)
    t.sql(SQL_ORDER, save, 12, 3100)
    # The N+1: one insert per order line, on the hot path.
    for micros in (4900, 4780, 4760, 4740, 4720):
        t.sql(SQL_LINE, save, 15, micros)

    # Everything below here is excluded, so the report rolls the whole subtree into one row.
    dto = t.call("com.example.order.OrderDto", "of", place, 29, 310)
    for micros in (52, 36, 34, 34, 34):
        t.call("com.example.order.OrderLineDto", "of", dto, 13, micros)

    return t.calls


def data_uri(path):
    if not path.exists():
        return ""
    return "data:image/png;base64," + base64.b64encode(path.read_bytes()).decode("ascii")


def main():
    model = {
        "target": "com.example.order.OrderController#placeOrder",
        "startedAtIso": "2026-07-31T09:24:18.221Z",
        "durationMs": 48,
        "cpuMicros": 43100,  # mostly CPU-bound: a little wait on the DB pool, no real I/O stall
        "projectName": PROJECT,
        "files": build_files(),
        "calls": encode_calls(build_calls()),
        "callsTruncated": False,
        "agentVersion": "2.1.0",
        "pluginVersion": "2.1.0",
        "excludedClasses": EXCLUDED,
        # The demo ships the full document so every feature is visible; the plugin's
        # Export dialog offers an "Essential" variant that omits this source.
        "excludedOmitted": False,
    }

    # Same block HtmlReportGenerator writes: plain JSON below the threshold, gzipped and
    # base64'd above it, and never both. Escaping only matters for the plain form; base64
    # cannot close the <script> element it sits in.
    payload = payload_block(json.dumps(model, ensure_ascii=False))

    logo = data_uri(RES / "icons/dejuLogo-report.png")   # 64px; see HtmlReportGenerator
    favicon = f'<link rel="icon" type="image/png" href="{logo}">' if logo else ""
    logo_img = f'<img class="logo" alt="" src="{logo}">' if logo else ""

    # Tabs are cut from the raw template, before anything is substituted into it, so the
    # markers can only ever match the template's own markup and never traced source.
    html = drop_tabs((RES / "report/report.html").read_text(encoding="utf-8"), DEMO_TABS)
    # Payload last, so traced source containing a placeholder token cannot be expanded.
    html = (html
            .replace("__STYLES__", (RES / "report/report.css").read_text(encoding="utf-8"))
            .replace("__SCRIPT__", (RES / "report/report.js").read_text(encoding="utf-8"))
            .replace("__FAVICON_LINK__", favicon)
            .replace("__LOGO_IMG__", logo_img)
            .replace("__PREFS_BLOCK__", prefs_block(DEMO_PREFS, DEMO_TABS))
            .replace("__PAYLOAD_BLOCK__", payload))

    # Every placeholder the template can carry. A new one added to report.html and forgotten
    # here would otherwise ship as literal text in the middle of the Marketplace sample.
    left = [tok for tok in ("__STYLES__", "__SCRIPT__", "__FAVICON_LINK__", "__LOGO_IMG__",
                            "__PREFS_BLOCK__", "__PAYLOAD_BLOCK__") if tok in html]
    if left:
        sys.exit("placeholder(s) left unsubstituted, the template changed shape: "
                 + ", ".join(left))

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(html, encoding="utf-8")
    calls = model["calls"]
    print(f"wrote {OUT.relative_to(ROOT)}  ({len(html):,} bytes)")
    print(f"  {len(model['files'])} files, {calls['n']} call nodes, "
          f"{sum(1 for q in calls['sql'] if q >= 0)} queries, {len(EXCLUDED)} excluded types")
    print(f"  tabs: {', '.join(DEMO_TABS)}  ·  opens on {DEMO_PREFS['openTab']}")


if __name__ == "__main__":
    main()
