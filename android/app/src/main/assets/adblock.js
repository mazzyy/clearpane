// Clearpane: general in-page ad hiding. Runs in every frame of every page.
// Nothing here is written for a particular website: it hides elements that use common
// ad class names, and floating boxes whose only content is a banner pointing at another site.
(function () {
    if (window.__clearpaneAds || !/^https?:$/.test(location.protocol)) return;
    window.__clearpaneAds = true;

    // The app replaces this marker with the user's settings.
    var OPTIONS = /*CP_AD_OPTIONS*/{ cosmetic: true, overlays: true };

    // ---- 1. Common ad container names ---------------------------------------------------
    var SELECTORS = [
        '.adsbygoogle', 'ins.adsbygoogle', 'amp-ad', '[id^="google_ads_"]', '[id^="div-gpt-ad"]',
        'iframe[id^="google_ads_iframe"]', 'iframe[src*="doubleclick.net"]',
        'iframe[src*="googlesyndication"]', '[data-ad-slot]', '[data-ad-client]', '[data-adunit]',
        '[aria-label="Advertisement"]', '[aria-label="advertisement"]',
        '.ad-banner', '.ad-container', '.ad-wrapper', '.ad-unit', '.ad-slot', '.ad-placement',
        '.ad-overlay', '.ad-popup', '.adbox', '.adsbox', '.advert', '.advertisement',
        '.banner-ad', '.banner_ad', '.overlay-ad', '.popup-ad', '.preroll-ad', '.sponsored-ad',
        '.video-ads', '[class^="ad-unit"]', '[id^="ad-slot"]', '[id^="adslot"]',
        '#ad-banner', '#ad_banner', '#adbanner'
    ];
    function addStyle() {
        if (!OPTIONS.cosmetic || document.getElementById('__clearpane_css')) return;
        var root = document.head || document.documentElement;
        if (!root) return;
        var style = document.createElement('style');
        style.id = '__clearpane_css';
        style.textContent = SELECTORS.join(',') + '{display:none!important}';
        root.appendChild(style);
    }

    // ---- 2. Floating banner boxes -------------------------------------------------------
    function site(host) {
        var p = String(host || '').toLowerCase().split('.');
        if (p.length <= 2) return p.join('.');
        var second = ['co', 'com', 'org', 'net', 'gov', 'ac', 'edu'];
        var take = (p[p.length - 1].length === 2 && second.indexOf(p[p.length - 2]) >= 0) ? 3 : 2;
        return p.slice(-take).join('.');
    }
    var here = site(location.hostname);
    function isExternal(url) {
        try {
            var u = new URL(url, location.href);
            return /^https?:$/.test(u.protocol) && site(u.hostname) !== here;
        } catch (e) {
            return false;
        }
    }
    function area(el) {
        var r = el.getBoundingClientRect();
        return r.width * r.height;
    }
    /** True for a box whose substance is a banner image or frame leading to another site. */
    function looksLikeBanner(el) {
        // Anything a person interacts with is left alone: forms, players, drawings.
        if (el.querySelector('input, textarea, select, video, audio, canvas')) return false;
        var external = 0, i;
        var links = el.querySelectorAll('a[href]');
        for (i = 0; i < links.length && !external; i++) {
            var media = links[i].querySelector('img, picture, video');
            if (media && area(media) > 6000 && isExternal(links[i].href)) external++;
        }
        var frames = el.tagName === 'IFRAME' ? [el] : el.querySelectorAll('iframe');
        for (i = 0; i < frames.length && !external; i++) {
            if (frames[i].src && isExternal(frames[i].src) && area(frames[i]) > 6000) external++;
        }
        if (!external) return false;
        // Real dialogs carry text of their own; banners are almost all picture.
        return (el.innerText || '').trim().length < 300;
    }
    function sweep() {
        if (!OPTIONS.overlays || !document.body) return;
        var all = document.body.getElementsByTagName('*');
        var count = Math.min(all.length, 2500);
        var screen = window.innerWidth * window.innerHeight;
        for (var i = 0; i < count; i++) {
            var el = all[i];
            if (el.__clearpaneHidden) continue;
            var style = getComputedStyle(el);
            if (style.position !== 'fixed' && style.position !== 'absolute') continue;
            if (!(parseInt(style.zIndex, 10) >= 100)) continue;
            var a = area(el);
            if (a < 5000 || a < screen * 0.03) continue;
            if (looksLikeBanner(el)) {
                el.__clearpaneHidden = true;
                el.style.setProperty('display', 'none', 'important');
            }
        }
    }

    // ---- 3. Keep watching, cheaply ------------------------------------------------------
    var timer = null, last = 0;
    function schedule() {
        if (timer) return;
        var wait = Math.max(600, 2000 - (Date.now() - last));
        timer = setTimeout(function () {
            timer = null;
            last = Date.now();
            addStyle();
            sweep();
        }, wait);
    }
    function start() {
        addStyle();
        sweep();
        if (window.MutationObserver && document.documentElement) {
            new MutationObserver(schedule).observe(document.documentElement, { childList: true, subtree: true });
        }
    }
    addStyle();
    if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start);
    else start();
    window.addEventListener('load', schedule);
})();
