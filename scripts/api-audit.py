#!/usr/bin/env python3
"""Check the built plugin for IntelliJ Platform APIs the JetBrains Marketplace rejects.

    python3 scripts/api-audit.py                 # audit against the default platform
    python3 scripts/api-audit.py --version 2026.2.0.1
    python3 scripts/api-audit.py --jar path/to/plugin.jar

Exit code is 1 when a blocker is found, so this can gate a release.

WHY THIS EXISTS
---------------
`./gradlew verifyPlugin` cannot see the problem this catches. The IntelliJ Platform Gradle
Plugin resolves verifier IDEs from a repository whose newest IntelliJ IDEA Community is
2025.2.6, while the Marketplace also verifies against 2026.x. Those are not the same check,
because JetBrains adds @ApiStatus.Internal to methods over time:

    PluginManagerCore.getPlugin(PluginId)
        2025.2.6          @Nullable @JvmStatic          -> local verifier: clean
        2026.1.4 (261)    @Nullable @JvmStatic          -> Marketplace: warnings only
        2026.2   (262)    + @ApiStatus.Internal         -> Marketplace: REJECTED

Version 1.1.0-eap.1 shipped exactly that call, passed every local check, and was rejected
on upload. This script downloads the real platform and reads the annotations off its
bytecode, so the answer comes from the same source the Marketplace uses.

HOW IT WORKS
------------
1. Disassemble our own classes and collect every platform method we call, with its exact
   JVM descriptor.
2. For each, find the declaring member in the platform jars, walking supertypes, since
   Project.getService is really declared on ComponentManager.
3. Report any carrying @ApiStatus.Internal or @ApiStatus.ScheduledForRemoval.

Annotations are read ONLY from RuntimeVisible/RuntimeInvisibleAnnotations blocks. Both the
constant pool and the InnerClasses attribute *name* annotation types the class merely
references, so a plain text search reports most of the platform as internal, that mistake
produced 40 false blockers before this was tightened.

A self-check runs first and aborts if it cannot detect a known-internal method, so a broken
audit reports failure rather than a reassuring "0 blockers".
"""
import argparse
import os
import pathlib
import re
import shutil
import subprocess
import sys
import urllib.request
import zipfile

REPO = pathlib.Path(__file__).resolve().parent.parent
# Cached outside the repository: these are ~650 MB each and must never be committed.
CACHE = pathlib.Path(os.environ.get("DEJU_API_AUDIT_CACHE",
                                    pathlib.Path.home() / ".cache" / "deju" / "api-audit"))
BASE_URL = "https://www.jetbrains.com/intellij-repository/releases/com/jetbrains/intellij/idea/ideaIC"

# 2026.2 is the default because it is the build that rejected 1.1.0-eap.1: it carries the
# newest annotations, so it is the strictest check available.
DEFAULT_VERSION = "2026.2.0.1"

BLOCKERS = ("ApiStatus$Internal", "ApiStatus$ScheduledForRemoval")

# A method known to be @ApiStatus.Internal on 2026.2, the self-check's positive control.
CONTROL = ("com/intellij/ide/plugins/PluginManagerCore", "getPlugin",
           "(Lcom/intellij/openapi/extensions/PluginId;)Lcom/intellij/ide/plugins/IdeaPluginDescriptor;")
# Core public API that must NOT be flagged, the negative control.
CONTROL_CLEAN = ("com/intellij/openapi/project/Project", "getService",
                 "(Ljava/lang/Class;)Ljava/lang/Object;")

ANNOT_HEADER = re.compile(r'^(\s*)Runtime(In)?[Vv]isible(Type)?Annotations:\s*$')
TYPE_LINE = re.compile(r'^\s*([\w.$]+)\s*(\(.*\))?\s*$')


def annotations_in(text):
    """Annotation type names declared in this text's annotation attribute blocks."""
    found, lines, i = set(), text.split("\n"), 0
    while i < len(lines):
        m = ANNOT_HEADER.match(lines[i])
        if not m:
            i += 1
            continue
        indent = len(m.group(1))
        i += 1
        while i < len(lines):
            line = lines[i]
            if not line.strip():
                i += 1
                continue
            if len(line) - len(line.lstrip()) <= indent:
                break
            t = TYPE_LINE.match(line.strip())
            if t and "." in t.group(1):
                found.add(t.group(1))
            i += 1
    return found


class Platform:
    """An extracted IntelliJ distribution, queried for member annotations."""

    def __init__(self, lib_dirs, work):
        self.work = pathlib.Path(work)
        self.work.mkdir(parents=True, exist_ok=True)
        self.index, self.cache = {}, {}
        for d in lib_dirs:
            for jar in sorted(pathlib.Path(d).rglob("*.jar")):
                try:
                    with zipfile.ZipFile(jar) as z:
                        for n in z.namelist():
                            if n.endswith(".class") and n not in self.index:
                                self.index[n] = jar
                except zipfile.BadZipFile:
                    pass

    def _disasm(self, cls):
        if cls in self.cache:
            return self.cache[cls]
        jar = self.index.get(cls + ".class")
        out = None
        if jar is not None:
            with zipfile.ZipFile(jar) as z:
                (self.work / "probe.class").write_bytes(z.read(cls + ".class"))
            out = subprocess.run(["javap", "-v", "-p", str(self.work / "probe.class")],
                                 capture_output=True, text=True).stdout
        self.cache[cls] = out
        return out

    @staticmethod
    def _split(out):
        """(member lines, trailing class-attribute lines), see the module docstring."""
        lines = out.split("\n")
        try:
            start = next(i for i, l in enumerate(lines) if l.rstrip() == "{")
            end = len(lines) - 1 - next(i for i, l in enumerate(reversed(lines))
                                        if l.rstrip() == "}")
        except StopIteration:
            return [], []
        return lines[start + 1:end], lines[end + 1:]

    def class_annotations(self, cls):
        out = self._disasm(cls)
        if out is None:
            return None
        return annotations_in("\n".join(self._split(out)[1]))

    def _members(self, cls):
        out = self._disasm(cls)
        if out is None:
            return None
        body = self._split(out)[0]
        idx = [i for i, l in enumerate(body) if re.match(r'^  [a-zA-Z<].*;\s*$', l)] + [len(body)]
        return {body[a].strip(): "\n".join(body[a:b]) for a, b in zip(idx, idx[1:])}

    def _exact(self, cls, method, descriptor):
        ms = self._members(cls)
        if ms is None:
            return None
        for sig, blob in ms.items():
            d = re.search(r'^\s*descriptor:\s*(\S+)\s*$', blob, re.M)
            if not d or d.group(1) != descriptor:
                continue
            # Match the name too: overloads share a class, and NotificationGroup has six
            # createNotification methods of which only some are scheduled for removal.
            simple = cls.split("/")[-1].split("$")[-1]
            name_ok = (re.search(re.escape(simple) + r'\s*\(', sig) if method == "<init>"
                       else re.search(r'\b' + re.escape(method) + r'\s*\(', sig))
            if name_ok:
                return sig, annotations_in(blob)
        return None

    def _supertypes(self, cls):
        out = self._disasm(cls)
        if out is None:
            return []
        m = re.search(r'^(?:public |final |abstract |static |sealed |non-sealed )*'
                      r'(?:class|interface|@interface|enum|record)\s+\S+(.*?)\{', out, re.M | re.S)
        if not m:
            return []
        return [n.replace(".", "/")
                for n in re.findall(r'(?:extends|implements|,)\s+([\w.$]+)', m.group(1))]

    def resolve(self, cls, method, descriptor, _seen=None):
        """Find a member here or anywhere up the hierarchy. Returns (owner, sig, annots)."""
        _seen = _seen or set()
        if cls in _seen:
            return None
        _seen.add(cls)
        hit = self._exact(cls, method, descriptor)
        if hit:
            return (cls,) + hit
        for parent in self._supertypes(cls):
            got = self.resolve(parent, method, descriptor, _seen)
            if got:
                return got
        return None


def fetch_platform(version):
    """Download and extract the platform once; reuse it thereafter."""
    dest = CACHE / version
    marker = dest / ".extracted"
    if marker.exists():
        return dest
    CACHE.mkdir(parents=True, exist_ok=True)
    zip_path = CACHE / f"ideaIC-{version}.zip"
    if not zip_path.exists():
        url = f"{BASE_URL}/{version}/ideaIC-{version}.zip"
        print(f"downloading {url}\n  (~650 MB, once per version; cached in {CACHE})")
        tmp = zip_path.with_suffix(".part")
        with urllib.request.urlopen(url) as r, open(tmp, "wb") as f:
            shutil.copyfileobj(r, f)
        tmp.rename(zip_path)
    print(f"extracting {zip_path.name} …")
    dest.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(zip_path) as z:
        wanted = [n for n in z.namelist()
                  if n.endswith(".jar") and (n.startswith("lib/") or "/lib/" in n)]
        z.extractall(dest, wanted)
    marker.write_text(version)
    # The zip is no longer needed once extracted; keep the disk cost to the jars alone.
    zip_path.unlink(missing_ok=True)
    return dest


def self_check(platform):
    """Refuse to report success unless the audit demonstrably detects a real blocker."""
    got = platform.resolve(*CONTROL)
    if not got or not any("ApiStatus$Internal" in a for a in got[2]):
        print("SELF-CHECK FAILED: could not detect a method known to be @ApiStatus.Internal.")
        print(f"  probe: {CONTROL[0]}.{CONTROL[1]}  resolved: {got and got[0]}")
        print("  The audit cannot be trusted on this platform build; not reporting a result.")
        return False
    clean = platform.resolve(*CONTROL_CLEAN)
    if not clean or any("ApiStatus$Internal" in a for a in clean[2]):
        print("SELF-CHECK FAILED: ordinary public API was flagged as internal.")
        return False
    print("self-check ok (detects a known-internal method; does not flag public API)")
    return True


def main():
    ap = argparse.ArgumentParser(description=__doc__.split("\n")[0])
    ap.add_argument("--version", default=DEFAULT_VERSION, help=f"platform build (default {DEFAULT_VERSION})")
    ap.add_argument("--jar", help="plugin jar to audit (default: newest built instrumented jar)")
    args = ap.parse_args()

    jar = args.jar
    if not jar:
        candidates = sorted((REPO / "plugin" / "build" / "libs").glob("*-instrumented.jar"),
                            key=lambda p: p.stat().st_mtime, reverse=True)
        if not candidates:
            sys.exit("No built plugin jar found. Run ./gradlew :plugin:buildPlugin first.")
        jar = candidates[0]
    print(f"auditing : {pathlib.Path(jar).name}")

    root = fetch_platform(args.version)
    platform = Platform([root], CACHE / "_work")
    print(f"platform : IntelliJ IDEA {args.version}  ({len(platform.index)} classes indexed)")
    if not self_check(platform):
        sys.exit(2)

    work = CACHE / "_ours"
    shutil.rmtree(work, ignore_errors=True)
    work.mkdir(parents=True)
    with zipfile.ZipFile(jar) as z:
        ours = [n for n in z.namelist() if n.endswith(".class")]
        z.extractall(work, ours)
    dis = subprocess.run(["javap", "-c", "-p"] + [str(work / n) for n in ours],
                         capture_output=True, text=True).stdout
    # Proportional, not a fixed line count: javap emits one "Compiled from" header per class,
    # so this catches a partial or failed disassembly on a jar of any size. A fixed threshold
    # aborted on a legitimate single-class jar.
    disassembled = dis.count("Compiled from")
    if disassembled < len(ours):
        sys.exit(f"javap disassembled only {disassembled} of {len(ours)} classes, "
                 "the audit could falsely pass. Aborting.")

    refs = set()
    for m in re.finditer(r'//\s*(?:Interface)?Method\s+'
                         r'(com/intellij/[\w/$]+|org/jetbrains/[\w/$]+)\.([\w<>$]+):(\S+)', dis):
        refs.add(m.groups())
    classes = {c for c, _, _ in refs}
    for m in re.finditer(r'//\s*(?:class|Field)\s+(com/intellij/[\w/$]+|org/jetbrains/[\w/$]+)', dis):
        classes.add(m.group(1).split(".")[0])
    print(f"our code : {len(ours)} classes calling {len(refs)} platform members "
          f"across {len(classes)} platform classes")

    problems, resolved = [], 0
    for cls in sorted(classes):
        ann = platform.class_annotations(cls)
        if ann:
            for bad in BLOCKERS:
                if any(bad in a for a in ann):
                    problems.append((bad, cls, "<entire class>"))
    for cls, meth, desc in sorted(refs):
        hit = platform.resolve(cls, meth, desc)
        if not hit:
            continue          # inherited from a JDK class; cannot carry these annotations
        resolved += 1
        owner, sig, ann = hit
        for bad in BLOCKERS:
            if any(bad in a for a in ann):
                problems.append((bad, owner, sig))

    print(f"resolved : {resolved}/{len(refs)} members "
          f"(the remainder are inherited Swing/AWT methods)")
    print()
    if problems:
        print(f"FAIL, {len(set(problems))} blocker(s); the Marketplace will reject this build:")
        for bad, cls, sig in sorted(set(problems)):
            print(f"  {bad.split('$')[1]:20} {cls}")
            print(f"  {'':20} {sig}")
        return 1
    print("PASS, no @ApiStatus.Internal or @ApiStatus.ScheduledForRemoval member is called.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
