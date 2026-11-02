'use strict';
const dialog = document.querySelector('.screenshot-dialog');
const dialogImage = document.querySelector('[data-dialog-image]');
document.querySelectorAll('[data-lightbox]').forEach(function (link) {
  link.addEventListener('click', function (event) {
    if (!dialog || typeof dialog.showModal !== 'function' || event.ctrlKey || event.metaKey || event.shiftKey || event.altKey) return;
    event.preventDefault();
    const image = link.querySelector('img');
    dialogImage.src = link.href;
    dialogImage.alt = image.alt;
    dialog.showModal();
  });
});
if (dialog) dialog.addEventListener('click', function (event) { if (event.target === dialog) dialog.close(); });

const themeControls = document.querySelector('[data-theme-controls]');
if (themeControls) {
  themeControls.hidden = false;
  themeControls.querySelectorAll('button').forEach(function (button) {
    button.addEventListener('click', function () {
      const dark = button.dataset.previewTheme === 'dark';
      const source = dark ? 'assets/android-shelf-dark.png' : 'assets/android-shelf.png';
      document.querySelector('[data-android-image]').src = source;
      document.querySelector('[data-android-link]').href = source;
      themeControls.querySelectorAll('button').forEach(function (item) {
        item.setAttribute('aria-pressed', String(item === button));
      });
    });
  });
}

fetch('release.json').then(function (response) {
  if (!response.ok) throw new Error('Release information unavailable');
  return response.json();
}).then(function (release) {
  for (const platform of ['mac', 'android']) {
    const item = release[platform];
    if (!item) continue;
    const url = new URL(item.url);
    if (url.protocol !== 'https:' || url.hostname !== 'github.com') continue;
    const link = document.querySelector('[data-download="' + platform + '"]');
    link.href = url.href;
    link.querySelector('[data-download-label]').textContent = item.label;
    document.querySelector('[data-download-note="' + platform + '"]').textContent = item.note;
  }
  document.querySelector('[data-release-status]').textContent = release.message;
}).catch(function () {
  // The static links remain usable when release metadata cannot be loaded.
});
