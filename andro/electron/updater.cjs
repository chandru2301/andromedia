/**
 * Auto-update for the packaged desktop app.
 *
 * win32 / linux: real auto-update via electron-updater (works unsigned — NSIS
 * and AppImage don't require a code signature to check, download and apply).
 *
 * darwin: Squirrel.Mac (what electron-updater's autoUpdater uses under the
 * hood on macOS) refuses to apply updates to an unsigned app bundle — that is
 * an OS-level restriction, not something this app can route around. Until the
 * mac build is signed, we only check GitHub's latest release and prompt the
 * user to download it manually.
 */

const { app, dialog, shell } = require('electron');

const RELEASES_API_URL = 'https://api.github.com/repos/chandruSofttwig/andro-releases/releases/latest';
const CHECK_DELAY_MS = 10_000;
const CHECK_INTERVAL_MS = 4 * 60 * 60 * 1000;

/** Compare two "x.y.z"-style versions. Returns true if `latest` is newer than `current`. */
function isNewer(latest, current) {
  const a = String(latest).replace(/^v/, '').split('.').map(Number);
  const b = String(current).replace(/^v/, '').split('.').map(Number);
  for (let i = 0; i < Math.max(a.length, b.length); i += 1) {
    const av = a[i] || 0;
    const bv = b[i] || 0;
    if (av !== bv) return av > bv;
  }
  return false;
}

function initWindowsLinux() {
  const { autoUpdater } = require('electron-updater');
  autoUpdater.autoDownload = true;
  autoUpdater.autoInstallOnAppQuit = true;

  autoUpdater.on('update-downloaded', (info) => {
    dialog
      .showMessageBox({
        type: 'info',
        title: 'Update ready',
        message: `andro ${info.version} has been downloaded.`,
        detail: 'Restart now to install it, or it will install automatically next time you quit.',
        buttons: ['Restart now', 'Later'],
        defaultId: 0,
        cancelId: 1,
      })
      .then(({ response }) => {
        if (response === 0) {
          autoUpdater.quitAndInstall();
        }
      });
  });

  autoUpdater.on('error', (error) => {
    console.warn('[andro] Update check failed:', error?.message || error);
  });

  const check = () => {
    autoUpdater.checkForUpdates().catch((error) => {
      console.warn('[andro] Update check failed:', error?.message || error);
    });
  };

  setTimeout(check, CHECK_DELAY_MS);
  setInterval(check, CHECK_INTERVAL_MS);
}

function initMac() {
  const check = async () => {
    try {
      const res = await fetch(RELEASES_API_URL, {
        headers: { Accept: 'application/vnd.github+json' },
      });
      if (!res.ok) return;
      const release = await res.json();
      const latestVersion = release.tag_name;
      if (!latestVersion || !isNewer(latestVersion, app.getVersion())) return;

      const { response } = await dialog.showMessageBox({
        type: 'info',
        title: 'Update available',
        message: `andro ${latestVersion.replace(/^v/, '')} is available (you have ${app.getVersion()}).`,
        detail: 'Auto-update is not yet available for macOS — download the new version manually.',
        buttons: ['Download', 'Later'],
        defaultId: 0,
        cancelId: 1,
      });
      if (response === 0 && release.html_url) {
        await shell.openExternal(release.html_url);
      }
    } catch (error) {
      console.warn('[andro] Update check failed:', error?.message || error);
    }
  };

  setTimeout(check, CHECK_DELAY_MS);
  setInterval(check, CHECK_INTERVAL_MS);
}

function init() {
  if (!app.isPackaged) return;
  if (process.platform === 'darwin') {
    initMac();
  } else {
    initWindowsLinux();
  }
}

module.exports = { init, isNewer };
