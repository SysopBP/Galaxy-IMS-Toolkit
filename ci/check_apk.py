from pathlib import Path
from zipfile import ZipFile

apks = list(Path("app/build/outputs/apk").glob("**/app-*.apk"))
assert apks, "Main APK missing"
for apk in apks:
    with ZipFile(apk) as archive:
        entry = archive.read("assets/xposed_init").decode().strip()
        assert entry == "dev.bluehouse.enablevolte.xposed.ImsObserverEntry", entry
        dex = b"".join(archive.read(name) for name in archive.namelist() if name.endswith(".dex"))
        for descriptor in (
            b"Ldev/bluehouse/enablevolte/RootCarrierClient;",
            b"Ldev/bluehouse/enablevolte/ObserverProvider;",
            b"Ldev/bluehouse/enablevolte/xposed/ImsObserverEntry;",
        ):
            assert descriptor in dex, "Packaged component missing: " + descriptor.decode()
    print("Verified root worker, observer provider and LSPosed entry in", apk)
