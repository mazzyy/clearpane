// Clearpane: best-effort YouTube ad skipper. Runs only on youtube.com pages.
// YouTube changes its page often; if ads start getting through, the selectors
// below are the first thing to update.
(function () {
    if (window.__clearpaneYT) return;
    if (!/(^|\.)youtube\.com$/.test(location.hostname)) return;
    window.__clearpaneYT = true;

    // 1. Hide banner, feed and companion ad slots.
    var hidden = [
        'ytd-ad-slot-renderer', 'ytd-in-feed-ad-layout-renderer', 'ytd-display-ad-renderer',
        'ytd-promoted-sparkles-web-renderer', 'ytd-promoted-video-renderer',
        'ytd-banner-promo-renderer', 'ytd-statement-banner-renderer',
        'ytd-companion-slot-renderer', 'ytd-action-companion-ad-renderer',
        'ytd-player-legacy-desktop-watch-ads-renderer', '#masthead-ad', '#player-ads',
        'ytm-promoted-sparkles-web-renderer', 'ytm-promoted-sparkles-text-search-renderer',
        'ytm-promoted-video-renderer', 'ytm-companion-ad-renderer', 'ytm-companion-slot',
        'ad-slot-renderer', '.ytp-ad-overlay-container', '.ytp-ad-image-overlay',
        '.ytp-ad-text-overlay'
    ];
    function addStyle() {
        if (document.getElementById('__clearpane_yt_css')) return;
        if (!document.head && !document.documentElement) return;   // too early; tick() retries
        var style = document.createElement('style');
        style.id = '__clearpane_yt_css';
        style.textContent = hidden.join(',') + '{display:none!important}';
        (document.head || document.documentElement).appendChild(style);
    }

    // 2. Skip video ads: press Skip if it exists, otherwise jump the ad to its end.
    var skipButtons = '.ytp-skip-ad-button, .ytp-ad-skip-button, .ytp-ad-skip-button-modern, ' +
        '.ytp-ad-skip-button-container button, .ytp-ad-skip-button-slot button';
    var mutedByUs = false;

    function tick() {
        addStyle();
        var player = document.querySelector('.html5-video-player');
        if (!player) return;
        var video = player.querySelector('video');
        var adShowing = player.classList.contains('ad-showing') ||
            player.classList.contains('ad-interrupting');

        if (!adShowing) {
            if (mutedByUs && video) { video.muted = false; }
            mutedByUs = false;
            return;
        }
        if (video) {
            if (!video.muted) { video.muted = true; mutedByUs = true; }
            if (isFinite(video.duration) && video.duration > 0 &&
                video.currentTime < video.duration - 0.3) {
                video.currentTime = video.duration;
            }
        }
        var buttons = document.querySelectorAll(skipButtons);
        for (var i = 0; i < buttons.length; i++) { buttons[i].click(); }
    }

    addStyle();
    setInterval(tick, 300);
})();
