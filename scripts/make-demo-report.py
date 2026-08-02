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

Usage:  python3 scripts/make-demo-report.py
"""

import json
import base64
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
RES = ROOT / "plugin/src/main/resources"
OUT = ROOT / "docs/demo-report.html"

PROJECT = "brewhaus-api"
SRC_ROOT = "src/main/java"

# --------------------------------------------------------------------------- sources ---
# Full source of each traced file. Line numbers below are 1-based indices into these.

SOURCES = {
    "com.brewhaus.order.OrderController": ("OrderController.java", """\
package com.brewhaus.order;

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
    "com.brewhaus.order.OrderService": ("OrderService.java", """\
package com.brewhaus.order;

import com.brewhaus.loyalty.LoyaltyService;
import com.brewhaus.menu.MenuRepository;
import com.brewhaus.pricing.DiscountPolicy;
import com.brewhaus.pricing.PriceCalculator;
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
    "com.brewhaus.menu.MenuRepository": ("MenuRepository.java", """\
package com.brewhaus.menu;

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
    "com.brewhaus.pricing.PriceCalculator": ("PriceCalculator.java", """\
package com.brewhaus.pricing;

import com.brewhaus.menu.Drink;
import com.brewhaus.order.Order;
import com.brewhaus.order.OrderLine;
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
    "com.brewhaus.pricing.DiscountPolicy": ("DiscountPolicy.java", """\
package com.brewhaus.pricing;

import com.brewhaus.loyalty.LoyaltyService;
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
    "com.brewhaus.loyalty.LoyaltyService": ("LoyaltyService.java", """\
package com.brewhaus.loyalty;

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
    "com.brewhaus.order.OrderRepository": ("OrderRepository.java", """\
package com.brewhaus.order;

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
    "com.brewhaus.order.OrderDto": ("OrderDto.java", """\
package com.brewhaus.order;

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
    "com.brewhaus.order.OrderLineDto": ("OrderLineDto.java", """\
package com.brewhaus.order;

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
    "com.brewhaus.order.OrderController": [
        ("placeOrder", 47900, 560, [
            (20, "FULL", None, None, 3),
            (22, "PARTIAL", 1, 2, 41),
            (23, "NONE", None, None, None),
            (25, "FULL", None, None, 47340),
            (26, "FULL", None, None, 12),
        ]),
    ],
    "com.brewhaus.order.OrderService": [
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
    "com.brewhaus.menu.MenuRepository": [
        ("findByName", 12650, 190, [
            (13, "FULL", None, None, 12650),
        ]),
    ],
    "com.brewhaus.pricing.PriceCalculator": [
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
    "com.brewhaus.pricing.DiscountPolicy": [
        ("apply", 3980, 70, [
            (13, "FULL", None, None, 9),
            (14, "PARTIAL", 1, 2, 3910),
            (15, "FULL", None, None, 22),
            (17, "PARTIAL", 1, 2, 27),
            (18, "NONE", None, None, None),
            (20, "FULL", None, None, 12),
        ]),
    ],
    "com.brewhaus.loyalty.LoyaltyService": [
        ("isMember", 3910, 50, [
            (13, "FULL", None, None, 3860),
            (16, "FULL", 2, 2, 14),
        ]),
        ("award", 2210, 60, [
            (20, "FULL", None, None, 18),
            (21, "FULL", None, None, 2150),
        ]),
    ],
    "com.brewhaus.order.OrderRepository": [
        ("save", 27200, 200, [
            (12, "FULL", None, None, 3100),
            (14, "FULL", 2, 2, 21),
            (15, "FULL", None, None, 23900),
        ]),
    ],
    "com.brewhaus.order.OrderDto": [
        ("of", 310, 120, [
            (9, "FULL", None, None, 14),
            (10, "FULL", None, None, 9),
            (11, "FULL", None, None, 11),
            (12, "FULL", None, None, 8),
            (13, "FULL", None, None, 190),
        ]),
    ],
    "com.brewhaus.order.OrderLineDto": [
        ("of", 190, 190, [
            (6, "FULL", None, None, 190),
        ]),
    ],
}

EXCLUDED = ["com.brewhaus.order.OrderDto", "com.brewhaus.order.OrderLineDto"]

DRINKS = ["Flat White", "Latte", "Cortado", "Mocha", "Espresso"]

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


def build_calls():
    t = Trace()
    root = t.call("com.brewhaus.order.OrderController", "placeOrder", -1, None, 47900)
    place = t.call("com.brewhaus.order.OrderService", "place", root, 25, 47340)

    # Five consecutive identical lookups: the report folds the run after three.
    for micros in (8120, 1180, 1090, 1150, 1110):
        find = t.call("com.brewhaus.menu.MenuRepository", "findByName", place, 23, micros)
        t.sql(SQL_DRINK, find, 13, micros - 120)

    total = t.call("com.brewhaus.pricing.PriceCalculator", "total", place, 25, 640)
    for micros in (140, 96, 92, 94, 90):
        t.call("com.brewhaus.pricing.PriceCalculator", "priceOf", total, 17, micros)

    apply_ = t.call("com.brewhaus.pricing.DiscountPolicy", "apply", place, 26, 3980)
    member = t.call("com.brewhaus.loyalty.LoyaltyService", "isMember", apply_, 14, 3910)
    t.sql(SQL_MEMBER, member, 13, 3860)

    award = t.call("com.brewhaus.loyalty.LoyaltyService", "award", place, 27, 2210)
    t.sql(SQL_POINTS, award, 21, 2150)

    save = t.call("com.brewhaus.order.OrderRepository", "save", place, 28, 27200)
    t.sql(SQL_ORDER, save, 12, 3100)
    # The N+1: one insert per order line, on the hot path.
    for micros in (4900, 4780, 4760, 4740, 4720):
        t.sql(SQL_LINE, save, 15, micros)

    # Everything below here is excluded, so the report rolls the whole subtree into one row.
    dto = t.call("com.brewhaus.order.OrderDto", "of", place, 29, 310)
    for micros in (52, 36, 34, 34, 34):
        t.call("com.brewhaus.order.OrderLineDto", "of", dto, 13, micros)

    return t.calls


def data_uri(path):
    if not path.exists():
        return ""
    return "data:image/png;base64," + base64.b64encode(path.read_bytes()).decode("ascii")


def main():
    model = {
        "target": "com.brewhaus.order.OrderController#placeOrder",
        "startedAtIso": "2026-07-31T09:24:18.221Z",
        "durationMs": 48,
        "projectName": PROJECT,
        "files": build_files(),
        "calls": build_calls(),
        "callsTruncated": False,
        "agentVersion": "1.3.0",
        "pluginVersion": "1.3.0",
        "excludedClasses": EXCLUDED,
        # The demo ships the full document so every feature is visible; the plugin's
        # Export dialog offers an "Essential" variant that omits this source.
        "excludedOmitted": False,
    }

    # Same escaping HtmlReportGenerator applies: the JSON must not be able to close the
    # <script> element it is embedded in.
    payload = json.dumps(model, ensure_ascii=False).replace("</", "<\\/")

    logo = data_uri(RES / "icons/dejuLogo-report.png")   # 64px; see HtmlReportGenerator
    favicon = f'<link rel="icon" type="image/png" href="{logo}">' if logo else ""
    logo_img = f'<img class="logo" alt="" src="{logo}">' if logo else ""

    html = (RES / "report/report.html").read_text(encoding="utf-8")
    # Payload last, so traced source containing a placeholder token cannot be expanded.
    html = (html
            .replace("__STYLES__", (RES / "report/report.css").read_text(encoding="utf-8"))
            .replace("__SCRIPT__", (RES / "report/report.js").read_text(encoding="utf-8"))
            .replace("__FAVICON_LINK__", favicon)
            .replace("__LOGO_IMG__", logo_img)
            .replace("__PAYLOAD_JSON__", payload))

    if "__" in html.replace("__", "", 0) and any(
            tok in html for tok in ("__STYLES__", "__SCRIPT__", "__PAYLOAD_JSON__")):
        sys.exit("a placeholder was left unsubstituted, the template changed shape")

    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text(html, encoding="utf-8")
    calls = model["calls"]
    print(f"wrote {OUT.relative_to(ROOT)}  ({len(html):,} bytes)")
    print(f"  {len(model['files'])} files, {len(calls)} call nodes, "
          f"{sum(1 for c in calls if c['sql'])} queries, {len(EXCLUDED)} excluded types")


if __name__ == "__main__":
    main()
