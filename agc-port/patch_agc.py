#!/usr/bin/env python3
"""Apply the LineageOS/REA port to a decompiled AGC 9.2.14 v6.0.0 tree.

Takes the feature classes from a decompiled v4.1 build of the same fork (they are that
build's code, not redistributed here) and rewires the two Samsung framework calls that
do not exist outside One UI:

  OutputConfiguration.semCreateOutputConfiguration(...) -> com.agc.ReaOutputConfig
  CustomInterfaceHelper.setSamsungParameter(...)        -> com.agc.ReaPdkHelper (no-op)

then hooks the button to com.agc.Rea50Shot (single-shot 50 MP through the vendor HAL)
and adds the settings toggle that v6.0.0 dropped.

Usage:
    patch_agc.py --v6 <decompiled v6.0.0 dir> --v41 <decompiled v4.1 dir>
"""
import argparse
import pathlib
import shutil
import sys

FEATURE_PREFIXES = ("Eraw50", "LEraw50")
AGC = "smali_classes2/com/agc"


def read(p):
    return pathlib.Path(p).read_text()


def write(p, t):
    pathlib.Path(p).write_text(t)


def check(text, needle, path, count=1):
    if text.count(needle) != count:
        sys.exit("%s: expected %d occurrence(s) of %r, found %d"
                 % (path, count, needle[:60], text.count(needle)))


def copy_feature_classes(v6, v41):
    src = pathlib.Path(v41) / AGC
    dst = pathlib.Path(v6) / AGC
    copied = []
    for f in sorted(src.glob("*.smali")):
        if f.name.startswith(FEATURE_PREFIXES):
            shutil.copy2(f, dst / f.name)
            copied.append(f.name)
    print("feature classes copied from v4.1: %d" % len(copied))
    return copied


def install_helpers(v6, here):
    dst = pathlib.Path(v6) / AGC
    for name in ("ReaOutputConfig.smali", "ReaPdkHelper.smali"):
        shutil.copy2(pathlib.Path(here) / "smali" / name, dst / name)
    print("helpers installed: ReaOutputConfig, ReaPdkHelper")


def patch_bridge(v6):
    p = pathlib.Path(v6) / AGC / "Eraw50Bridge.smali"
    t = read(p)

    old = "const-class v2, Landroid/hardware/camera2/params/OutputConfiguration;"
    check(t, old, p)
    t = t.replace(old, "const-class v2, Lcom/agc/ReaOutputConfig;", 1)

    lines = t.splitlines(keepends=True)
    start = next(i for i, l in enumerate(lines)
                 if l.startswith(".method public static getSetParamsMethod()"))
    end = next(i for i in range(start, len(lines)) if lines[i].strip() == ".end method")
    body = """    .locals 6

    :try_start_0
    const-class v0, Lcom/agc/ReaPdkHelper;

    const-string v1, "setSamsungParameter"

    const/4 v2, 0x2

    new-array v2, v2, [Ljava/lang/Class;

    const/4 v3, 0x0

    const-class v4, Landroid/hardware/camera2/CameraDevice;

    aput-object v4, v2, v3

    const/4 v3, 0x1

    const-class v4, Ljava/lang/String;

    aput-object v4, v2, v3

    invoke-virtual {v0, v1, v2}, Ljava/lang/Class;->getMethod(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;

    move-result-object v0
    :try_end_0
    .catchall {:try_start_0 .. :try_end_0} :catchall_0

    return-object v0

    :catchall_0
    move-exception v0

    const-string v1, "LEraw50"

    invoke-static {v0}, Ljava/lang/String;->valueOf(Ljava/lang/Object;)Ljava/lang/String;

    move-result-object v0

    const-string v2, "pdk bridge load failed: "

    invoke-virtual {v2, v0}, Ljava/lang/String;->concat(Ljava/lang/String;)Ljava/lang/String;

    move-result-object v0

    invoke-static {v1, v0}, Landroid/util/Log;->e(Ljava/lang/String;Ljava/lang/String;)I

    const/4 v0, 0x0

    return-object v0
"""
    lines[start + 1:end] = [body]
    write(p, "".join(lines))
    print("Eraw50Bridge: semCreate -> ReaOutputConfig, PDK -> ReaPdkHelper")


def patch_burst(v6):
    """Eraw50Burst does its own getMethod lookup instead of using the bridge."""
    p = pathlib.Path(v6) / AGC / "Eraw50Burst.smali"
    lines = read(p).splitlines(keepends=True)
    hits = 0
    for i, l in enumerate(lines):
        if ("const-class v0, Landroid/hardware/camera2/params/OutputConfiguration;" in l
                and "semCreateOutputConfiguration" in "".join(lines[i:i + 4])):
            lines[i] = l.replace("Landroid/hardware/camera2/params/OutputConfiguration;",
                                 "Lcom/agc/ReaOutputConfig;")
            hits += 1
    if hits != 1:
        sys.exit("%s: expected 1 semCreate lookup, found %d" % (p, hits))
    write(p, "".join(lines))
    print("Eraw50Burst: semCreate -> ReaOutputConfig")


def patch_button(v6):
    p = pathlib.Path(v6) / AGC / "Eraw50UI.smali"
    t = read(p)
    old = "Lcom/agc/Eraw50Burst;->capture(Landroid/content/Context;)Z"
    check(t, old, p)
    write(p, t.replace(old, "Lcom/agc/Rea50Shot;->capture(Landroid/content/Context;)Z", 1))
    print("Eraw50UI: button -> Rea50Shot.capture (single shot)")


def insert_before(text, anchor, addition, path):
    lines = text.splitlines(keepends=True)
    hits = [i for i, l in enumerate(lines) if anchor in l]
    if len(hits) != 1:
        sys.exit("%s: anchor %r matched %d times" % (path, anchor[:50], len(hits)))
    lines.insert(hits[0], addition)
    return "".join(lines)


def insert_after(text, anchor, addition, path, after_locals=False):
    lines = text.splitlines(keepends=True)
    hits = [i for i, l in enumerate(lines) if anchor in l]
    if len(hits) != 1:
        sys.exit("%s: anchor %r matched %d times" % (path, anchor[:50], len(hits)))
    i = hits[0]
    if after_locals:
        i = next(j for j in range(i, len(lines))
                 if lines[j].strip().startswith((".locals", ".registers")))
    lines.insert(i + 1, addition)
    return "".join(lines)


def install_hooks(v6):
    root = pathlib.Path(v6)

    p = root / "smali/kzs.smali"
    t = read(p)
    write(p, insert_after(t,
        "    iput-object p1, p0, Lkzs;->a:Lcom/google/android/apps/camera/ui/shutterbutton/ShutterButton;",
        "\n    invoke-static {p1}, Lcom/agc/Eraw50UI;->install(Landroid/view/View;)V\n", p))

    p = root / "smali/sz.smali"
    t = read(p)
    write(p, insert_after(t,
        ".method public static final i(Landroid/hardware/camera2/CameraManager;",
        "\n    invoke-static {p1, p3}, Lcom/agc/LEraw50;->wrapOpenCb(Ljava/lang/String;Landroid/hardware/camera2/CameraDevice$StateCallback;)Landroid/hardware/camera2/CameraDevice$StateCallback;\n\n    move-result-object p3\n",
        p, after_locals=True))

    p = root / "smali/mxc.smali"
    t = read(p)
    write(p, insert_before(t,
        "    invoke-virtual {v4, v5, v6, v10}, Landroid/hardware/camera2/CameraManager;->openCamera(Ljava/lang/String;Landroid/hardware/camera2/CameraDevice$StateCallback;Landroid/os/Handler;)V",
        "    invoke-static {v5, v6}, Lcom/agc/LEraw50;->wrapOpenCb(Ljava/lang/String;Landroid/hardware/camera2/CameraDevice$StateCallback;)Landroid/hardware/camera2/CameraDevice$StateCallback;\n\n    move-result-object v6\n\n",
        p))

    p = root / "smali_classes2/com/agc/Application$1.smali"
    t = read(p)
    start = next(i for i, l in enumerate(t.splitlines()) if l.startswith(".method public onActivityCreated"))
    lines = t.splitlines(keepends=True)
    end = next(i for i in range(start, len(lines)) if lines[i].strip() == ".end method")
    ret = next(i for i in range(start, end) if lines[i].strip() == "return-void")
    lines.insert(ret, "    invoke-static {p1}, Lcom/agc/LEraw50;->setContext(Landroid/content/Context;)V\n\n")
    write(p, "".join(lines))
    print("hooks installed: kzs, sz.i, mxc, Application$1")


def add_toggle_resources(v6):
    s = pathlib.Path(v6) / "res/values/strings.xml"
    t = read(s)
    if "eraw50mp_title" not in t:
        add = ('    <string name="eraw50mp_summary">Samsung HighResolution pipeline '
               '(8160x6120 remosaic JPEG, main lens only). Takes 2-3s per shot.</string>\n'
               '    <string name="eraw50mp_title">50MP Expert RAW Mode</string>\n')
        write(s, t.replace("</resources>", add + "</resources>", 1))
        print("strings added: eraw50mp_title, eraw50mp_summary")

    x = pathlib.Path(v6) / "res/xml/camera_preferences.xml"
    lines = read(x).splitlines(keepends=True)
    if not any("pref_50mp_eraw_key" in l for l in lines):
        anchor = next(i for i, l in enumerate(lines) if 'android:key="pref_white_level_key"' in l)
        indent = " " * (len(lines[anchor]) - len(lines[anchor].lstrip()))
        lines.insert(anchor, indent + '<ListPreference android:icon="@drawable/outline_mp_24" '
                     'android:entries="@array/lib_global_on_and_off_entries" '
                     'android:layout="@layout/preference_with_margin" '
                     'android:title="@string/eraw50mp_title" android:key="pref_50mp_eraw_key" '
                     'android:summary="@string/eraw50mp_summary" android:defaultValue="0" '
                     'android:entryValues="@array/lib_global_on_and_off_entryvalues" />\n')
        write(x, "".join(lines))
        print("settings toggle added to camera_preferences.xml (Lens Setting -> Main)")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--v6", required=True, help="decompiled AGC 9.2.14 v6.0.0 tree")
    ap.add_argument("--v41", required=True, help="decompiled AGC 9.2.14 v4.1 tree (feature classes)")
    ap.add_argument("--here", default=str(pathlib.Path(__file__).resolve().parent))
    a = ap.parse_args()

    copy_feature_classes(a.v6, a.v41)
    install_helpers(a.v6, a.here)
    patch_bridge(a.v6)
    patch_burst(a.v6)
    patch_button(a.v6)
    install_hooks(a.v6)
    add_toggle_resources(a.v6)
    print("port applied to %s" % a.v6)


if __name__ == "__main__":
    main()
