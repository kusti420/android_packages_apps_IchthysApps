# Ichthys Apps

The one installer and updater of IchthysOS. App stores (F-Droid, Aurora Store) are for finding
apps; whatever is on the phone, Ichthys Apps is what keeps it updated. There is no Obtainium and
no store bundled in the image.

It handles three kinds of app:

| Kind | What | Installs | Updates | Removes | Certificate pin |
|---|---|---|---|---|---|
| `app` | Catalog apps the ROM recommends but does not ship: the proprietary ones (so the ROM zip stays shareable), the FOSS ones that can't be system apps (Termux, CoMaps, Mullvad VPN, mpvEx, KernelSU Next) and the stores | Yes, once its id is in `apps.install` and the list is confirmed | Yes | Yes, when its id leaves `apps.install` | The developer's, from the catalog |
| `bundled` | The image's third-party system apps (Fossify Phone/Contacts/Messages/Clock/Voice Recorder, Aves, Vanilla, Arcade, BCR, microG Services and Companion) | Never | Yes, over the built-in copy | Never | The developer's, from the catalog (the built-in copy has the same signer) |
| adopted | Apps the user installed from elsewhere and told Ichthys Apps to keep updated (`apps.adopted`) | Never | Yes | Never ("Stop updating" leaves it installed) | The installed copy's, pinned when it is adopted: **trust on first use** |

Each app is downloaded on the phone, from its own source, and every APK is checked against the
app's pinned signing certificate before anything is installed. Ichthys Apps holds
`INSTALL_PACKAGES`, so installs and updates are silent. The one exception: the first update of an
app whose updates another installer owns (a store, for an app it installed) asks once.

Etar is built from source and signed by the ROM, so it is updated by the OTA, not here.

## Inputs

| What | Where | Owner |
|---|---|---|
| Catalog | `/product/etc/ichthys/apps-catalog.json`, generated from `packages/apps/IchthysPrebuilts/catalog.list` by `gen_catalog.py`. Each entry has a `kind`: `app` or `bundled` | ROM build |
| `apps.install` | Settings.Secure `ichthys_apps_install` (comma-separated ids). The option has no default: **unset means that nothing was confirmed yet**, and never triggers an install or a removal; `""` is an explicit "none" | ichthys.nix |
| `apps.autoUpdate` | `ichthys_apps_auto_update` (1/0, default 1) | ichthys.nix |
| `apps.wifiOnly` | `ichthys_apps_wifi_only` (1/0, default 0). Off still leaves background downloads over 100 MB for an unmetered network | ichthys.nix |
| `apps.confirmed` | `ichthys_apps_confirmed` (1/0, default 0) | ichthys.nix |
| `apps.adopted` | `ichthys_apps_adopted`: a list of `"<package>[@<sha256>]=<source>"` entries, no default | ichthys.nix |

The app reads apps.* from Settings.Secure. It writes them only through the `ichthys_config` binder
(`IIchthysConfig.apply(snippet, merge = true, "Ichthys Apps", myUserId)`), so every change is an
ichthys.nix generation that `cmd ichthys_config generations` lists and `rollback` can undo. The
snippets it writes are:

```nix
{ apps.install = [ "zarchiver" "curve" ]; apps.confirmed = true; }  # Recommended › Install
{ apps.install = [ "zarchiver" ]; }                                 # Remove, or an outside uninstall
{ apps.autoUpdate = false; }
{ apps.wifiOnly = true; }
{ apps.adopted = [ "org.example.app@3f31…b06e=fdroid:org.example.app" ]; }  # Keep updated / Stop updating
```

Nothing is installed or removed until `apps.confirmed = true` **and** `apps.install` is set
(updates of the system apps and of adopted apps don't wait for that: they follow
`apps.autoUpdate`). The catalog's `default: true` ids only pre-check the first-run Recommended
screen, together with the catalog apps that are on the phone already (earlier builds shipped
some of them as first-boot payloads; what is installed stays looked after unless it is unchecked).
Its Install button always writes the list out, even when it equals those defaults. To install
declaratively without the first-run prompt, set both `apps.install` and `apps.confirmed = true` in
ichthys.nix.

The catalog's `setup` actions (all-files access, default camera, storage access for Termux, …) are
done by the system (IchthysPreinstallController, for packages whose certificate matches the pin),
not by this app. They run once per install: an app that is removed and installed again is set up
again.

### apps.adopted

One entry per app: `"<package>[@<sha256>[+<sha256>…]]=<source>"`.

- `<source>` is a `<scheme>:<spec>` like a catalog entry's.
- `@<sha256>` is the pinned signing certificate. Ichthys Apps writes it when it adopts an app. An
  entry written by hand may leave it out; the certificate of the installed copy is then pinned the
  first time Ichthys Apps sees the entry, and remembered in its own store.
- In the source, `%`, `,` and line breaks are written `%25`, `%2C` and `%0A`, so that an entry
  never contains the separator the list is stored with.
- An entry whose package is not installed is kept and ignored: adopted apps are never installed.

## Layout (`src/org/ichthys/apps/`)

```
catalog/Catalog.kt          apps-catalog.json → CatalogApp, Kind (entries without certs are dropped)
config/AppsConfig.kt        Secure keys → AppsState; apps.adopted entries; writes through IIchthysConfig.apply
source/Source.kt            Source interface, Release / RemoteFile / DeviceSpec
source/SourceRegistry.kt    scheme → Source
source/Http.kt              HttpURLConnection: timeouts, plain UA, manual redirects, bounded and resumable downloads
source/ApkPureSource.kt     apkpure:<pkg>       XAPK; ABI / density / locale split selection
source/GithubSource.kt      github:<o>/<r>[#re[#entry-re]]  releases/latest (else the newest recent release with a
                            matching asset), asset regex, prefers arm64; with entry-re, the APK inside a zip asset;
                            reads the website instead when the API's request limit is hit
source/GitlabSource.kt      gitlab:<g>/<p>[#re] gitlab.com releases/permalink/latest: asset links and files attached to the notes
source/FdroidSource.kt      fdroid:<pkg>[@repo] entry.json + index-v2 (cached, streamed), SHA-256; lookup() for adoption
source/Zips.kt              bounded extraction from a bundle (XAPK, release zip)
source/HtmlSource.kt        html:<url>#<re>[#<re>]  first link whose address or text matches, page as Referer, https only
source/DirectUrlSource.kt   url:<url>
install/CertVerifier.kt     every APK must be signed by a pin (v3 lineage accepted); the installed copy's signers
install/Installer.kt        PackageInstaller session / uninstall, results awaited per session
install/InstallResultReceiver.kt   status PendingIntent target
sync/AppsManager.kt         reconcile: remove dropped ids, install missing, update; adopt / unadopt; OpState per app
sync/Adoption.kt            other user apps, the F-Droid lookup, what the user typed → a source, adopted entry → CatalogApp
sync/AppStore.kt            per-app last check / latest version / release fingerprint / last error / pending confirmation / first-use pin
sync/Network.kt             connected / metered
update/UpdateJob.kt         JobScheduler: daily, one-off sync, unmetered pass, settings watcher, user-initiated installs
notify/Notifications.kt     first-run "Recommended apps", failures, confirmations, job progress
notify/Receivers.kt         BOOT_COMPLETED / MY_PACKAGE_REPLACED, PACKAGE_FULLY_REMOVED
ui/…                        Compose screens on the Shoal tokens: Recommended, Installed (managed apps, system
                            apps, other apps and the "Keep updated" dialog)
```

## Flow

**Install or update one app** (`AppsManager.installOrUpdate`):

1. `Source.resolve(spec, device)` returns a `Release` (versionName/versionCode if known, a
   fingerprint and the files with their sizes), ideally without downloading the APK.
2. For an installed app, the work stops (up to date) when any of these holds:
   - the release's versionCode ≤ installed;
   - its versionName equals the installed one (a leading "v" aside);
   - its fingerprint equals the one recorded for the last install or the last release found not
     newer.

   It also stops if the installed copy has a signer that isn't pinned (an update would fail anyway).
3. A background pass stops here in two more cases:
   - the install of this very release (same fingerprint) is waiting for the user's confirmation,
     or they declined it: it is not downloaded again to ask the same question. "Update now" asks
     again;
   - the network is metered and the release downloads more than 100 MB: it is left for an
     unmetered network (a one-off job waits for one), whatever `apps.wifiOnly` says. What the user
     starts in the app is downloaded at once.
4. The files are downloaded to `cache/work/<id>/` and turned into APKs by `Source.unpack`
   (XAPK → base + splits, a release zip → its APK). Both are bounded (see "Download limits").
5. `CertVerifier.verify`:
   - the base is read with `getPackageArchiveInfo(GET_SIGNING_CERTIFICATES)`, and its package must
     match the app's;
   - the splits are read with `ApkSignatureVerifier`;
   - every APK's signer SHA-256 must be in `certs`, or nothing is installed.
6. If the base's versionCode isn't newer, the release fingerprint is recorded and nothing is
   installed.
7. `Installer.install` opens one session with:
   - `MODE_FULL_INSTALL`;
   - `setRequestUpdateOwnership(true)`;
   - `INSTALL_REASON_USER`;
   - `USER_ACTION_NOT_REQUIRED`;
   - `PACKAGE_SOURCE_STORE`.

   All APKs go into that session, and the result arrives through `InstallResultReceiver`.
8. If the system still wants a confirmation (another installer owns the app's updates: a store
   that installed an adopted app usually does), the confirmation starts directly when the UI is
   visible, and otherwise a notification is posted: once per package and version, not again by
   every later pass. Once the user has agreed, the system drops that update ownership, and later
   updates are silent.

`cache/work/<id>/` is deleted when the attempt ends, except when it was interrupted while
downloading (the network went, the job was stopped). Then the finished files and the partial one
stay, and the next attempt at the same release (same fingerprint) continues from them.

**Updating a system app** works like any update: the session installs over the built-in copy
(which stays in the image), and needs no confirmation, because Ichthys Apps holds
`INSTALL_PACKAGES` and a preinstalled app has no update owner. The update must be signed like the
built-in copy; `IchthysPrebuilts/gen_bp.sh` checks that every shipped APK's signer is the pin of
its `bundled` entry. Update ownership is only ever given on a first install, so Ichthys Apps does
not become the update owner of a system app or of an adopted app. It does not need to.

**Adopting an app** (`AppsManager.adopt`, Installed › Other apps › Keep updated):

1. The source is resolved by itself where it can be: the package is looked up in the f-droid.org
   index (`FdroidSource.lookup`), and F-Droid is the source if it serves a build signed with the
   key of the installed copy. F-Droid signs many apps with its own keys, and such a build can't
   update a copy from the developer.
2. Otherwise the user names the source: a GitHub or GitLab project, an https link to the APK, or
   any `<scheme>:<spec>` (the dialog offers `apkpure:<package>` for apps that came from the Play
   Store through Aurora). It is asked for its newest release before it is accepted.
3. The certificate(s) the installed copy is signed with are pinned: trust on first use, and the
   dialog and the Installed card say so. No key from the app's developer ships with the ROM, so
   what is trusted is the copy that is installed at that moment.
4. `apps.adopted` gets the entry (a new ichthys.nix generation), and the source is checked for an
   update at once. From then on only APKs signed with the pinned certificate install.

Uninstalling an adopted app drops its entry. "Stop updating" drops the entry and leaves the app.

**Download limits** (`Http`, `Source.unpack`):

- No file over 600 MB, by Content-Length and by the bytes actually received.
- Before a download, the cache volume must have room for it plus 64 MB.
- One download attempt may take 60 minutes, a page or API answer 2 minutes, on top of the per-read
  timeout.
- An interrupted download keeps its `.part` file and continues with a Range request, where the
  server had sent a Content-Length: under `If-Range` with its ETag or Last-Modified, else only if
  the total length is unchanged. Otherwise it starts over.
- From an XAPK, no APK over 600 MB and no more than 1200 MB in total, counted while writing; room
  for the declared sizes is checked first. The APK taken out of a release zip is bounded the same
  way.

**Reconcile** (`AppsManager.sync`, one at a time behind a mutex):

- A background pass does nothing while the setup wizard is up.
- Catalog apps (`app`): nothing is installed or removed until `apps.confirmed` is true and
  `apps.install` is set. Then ids that were managed at the last pass and have left `apps.install`
  are uninstalled, and missing ids are installed.
- System apps (`bundled`) and adopted apps are never installed or removed; one that isn't
  installed is skipped.
- Whatever is installed is updated when updates are requested and either `apps.autoUpdate` is on
  or the user asked.
- Background passes skip metered networks with `apps.wifiOnly`; without it they leave downloads
  over 100 MB for an unmetered one, and F-Droid sources make do with an index from the last three
  days instead of downloading the day's new one (20 MB).

**Triggers:**

| When | What runs |
|---|---|
| Recommended › Install | Writes the config, then a user-initiated job |
| Installed › Update now / Check for updates | User-initiated job, ignoring `apps.wifiOnly` |
| Installed › Remove | After a confirmation, writes the config and uninstalls in-process |
| Installed › Keep updated / Stop updating | Writes `apps.adopted`; adopting also checks the source at once (user-initiated job) |
| Unmetered job | Queued by a background pass that left a large download for later; runs once an unmetered network is up |
| Daily job | Persisted, battery not low, unmetered network with wifiOnly. Installs missing apps; updates with autoUpdate |
| Boot (`BOOT_COMPLETED`, which is after unlock) | Reschedules the jobs, queues a sync (with updates only if no full check ran within the last day), first-run check |
| Settings watcher (content-triggered on the `ichthys_apps_*` keys and `user_setup_complete`) | Removals, queues installs, fits the daily job to wifiOnly, first-run check |
| `PACKAGE_FULLY_REMOVED` of a catalog package still in `apps.install`, or of an adopted package | The user uninstalled it elsewhere, so the id leaves `apps.install` (the entry leaves `apps.adopted`). The receiver takes only a short config lock, never the sync mutex |

**User-initiated job** (`UpdateJob.JOB_USER`): installs and update checks the user starts in the
app run as a user-initiated data-transfer job, with a progress notification. It starts at once, is
not cut off after ten minutes and goes on after the user leaves the app. Each request is a work
item of the one job. A stopped job is rescheduled and continues from the files already in the
cache. Offline, or when the system refuses the job, the sync runs in-process as a fallback (and
Recommended › Install also queues the one-off sync job as a backstop).

**First run:** once setup has completed and until the list is confirmed, a "Recommended apps"
notification is posted once per boot. Tapping it opens the Recommended screen, pre-checked with the
defaults. On the first boot it is posted when `USER_SETUP_COMPLETE` flips, through the watcher.

**Notifications** appear only for failures (once per distinct error), for installs that need the
user's confirmation (once per package and version) and, while it runs, for the user-initiated job.
Successful updates are silent. A background update check that can't reach or read its source is
shown on the Installed tab at once, but notified only when it has failed the same way three passes
in a row: the app is installed and works, and sources have bad days.

## Adding a source

1. Create `source/<Name>Source.kt` with an `object` implementing `Source`:
   - `scheme`: the catalog prefix, e.g. `"gitlab"`;
   - `resolve(spec, device)`: return the newest `Release`. Fill in `versionCode`/`versionName`
     when the source exposes them, and always a `fingerprint` that changes exactly when the release
     changes (an asset id, a file hash, a file name plus size). Throw `IOException` on errors. Use
     `Http` for requests, which handles the UA, redirects, timeouts and `Referer` via
     `RemoteFile.referer`. Set `RemoteFile.size` when the source tells it (it decides whether a
     background download waits for an unmetered network; otherwise the server is asked);
   - `unpack(spec, ...)`: only needed for bundle formats; return base first, then splits.
2. Register it in `SourceRegistry.sources`.
3. Add the scheme to `SCHEMES` in `packages/apps/IchthysPrebuilts/gen_catalog.py` and document
   it in the `catalog.list` header.
4. Add catalog entries with the developer's certificate SHA-256 (for example,
   `apksigner verify --print-certs app.apk`, the "SHA-256 digest" line, lower case).

## Adding or bumping a catalog app

- An app that is not shipped: one `catalog.list` line of kind `app`, then `./gen_catalog.py`.
- A system app: its `apps.list` line (`./fetch.sh --update`) **and** a `catalog.list` line of kind
  `bundled` for its package, then `./gen_bp.sh`. That regenerates the catalog and `Android.bp`,
  and fails if a shipped APK has no `bundled` entry or isn't signed with the entry's pin.

## Packaging

- `IchthysApps` is a platform-signed priv-app in system_ext, built with platform APIs. It needs:
  - `IIchthysConfig` for writing the config;
  - `ApkSignatureVerifier` / `ParseTypeImpl` for split verification.
- `privapp-permissions-org.ichthys.apps.xml` lists `INSTALL_PACKAGES`, `DELETE_PACKAGES` and
  `MANAGE_ICHTHYS_CONFIG`. It is installed with an explicit `.xml` `filename`: without it,
  SystemConfig skips the file and enforce mode bootloops.
- `ENFORCE_UPDATE_OWNERSHIP` (without it PackageInstaller ignores `setRequestUpdateOwnership`)
  and `RUN_USER_INITIATED_JOBS` are normal permissions: they are in the manifest only, not in the
  privapp XML.
- `default-permissions-org.ichthys.apps.xml` pre-grants `POST_NOTIFICATIONS`, because the
  first-run notification is posted before the app has ever been opened.
- Both XMLs are in the app's `required`, and so is the catalog (`ichthys_apps_catalog`, from
  `packages/apps/IchthysPrebuilts`). Before flashing, check that target-files has
  `SYSTEM_EXT/etc/permissions/privapp-permissions-org.ichthys.apps.xml` and
  `PRODUCT/etc/ichthys/apps-catalog.json`.
- `QUERY_ALL_PACKAGES` (normal for a system app) is what lets it list the other user apps.
