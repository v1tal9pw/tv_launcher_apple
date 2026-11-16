# Personal TV updates

## TCL C6K — v58 (3.55)

This version is prepared for the TCL C6K with Google TV 14. The Home handover
and Remote Button Helper were checked on that TV. Other TV models may need
different package names and button mappings.

The Windows helpers are in this folder:

- `tcl-button-helper.bat` checks and repairs the remote button service.
- `tcl-home-handover.bat` checks Wi-Fi ownership, backs up TV settings, and
  makes this launcher Home by disabling the two competing Google TV Home apps.
- `tcl-home-restore.bat` restores Google TV as Home.

Keep each BAT beside its matching PS1 file. The Home handover stops before
changing packages if a saved Wi-Fi network belongs to the Google TV setup app.
AirPlay packages are left enabled; check AirPlay and physical remote buttons
after a handover and again after reboot. The BAT files contain no TV IP,
password, ADB key, or personal backup.

This copy checks the latest public release of
`v1tal9pw/tv_launcher_apple` when Home opens, at most once every
24 hours. If its tag is a higher `v<versionCode>` and it contains
`tvhome-<versionCode>.apk`, the TV offers to download and install it. Android
asks for approval to install from this app and to confirm each update.

## First-time setup

1. Create a **public, empty** GitHub repository named `tv_launcher_apple` in
   the `v1tal9pw` account. Do not add a README or licence on GitHub.
2. Push this source tree to its `main` branch. Keep local build output,
   `local.properties`, TV backups, and `.idea` files out of the commit.
3. In the repository, open **Settings → Secrets and variables → Actions** and
   add `TVHOME_DEBUG_KEYSTORE_B64`. Its value is a single-line Base64 encoding
   of this computer's `%USERPROFILE%\.android\debug.keystore`. Keep the
   keystore and its Base64 text private. The release job refuses to publish an
   APK unless its signing certificate matches the launcher already installed
   on the TCL C6K.
4. Make sure GitHub Actions is enabled and the default `GITHUB_TOKEN` can write
   repository contents (**Settings → Actions → General → Workflow permissions**).
5. Push the first successful release tag matching `versionCode`. The release
   job will run tests, lint and the size gate, then publish an APK named for
   that version code as a GitHub Release asset.

For each later release, increase `versionCode` in `app/build.gradle`, commit
and push the source, then push the matching `v<versionCode>` tag. Tag the
commit containing that version. The workflow is
`../.github/workflows/personal-tv-release.yml`.

The TV must be online. Open the launcher after the release is published; the
next check offers the update. If the previous check was less than 24 hours
ago, wait until the next day. Installation needs the TV's on-screen approval.
