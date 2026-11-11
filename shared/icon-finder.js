// Clearpane: returns the address of the best icon the current page declares.
(function () {
    function pick(selector) {
        var best = null, bestSize = -1, links = document.querySelectorAll(selector);
        for (var i = 0; i < links.length; i++) {
            var size = parseInt(links[i].getAttribute('sizes') || '0', 10) || 0;
            if (links[i].href && size > bestSize) { bestSize = size; best = links[i].href; }
        }
        return best;
    }
    return pick('link[rel~="apple-touch-icon"], link[rel~="apple-touch-icon-precomposed"]') ||
        pick('link[rel~="icon"]') ||
        (location.origin + '/favicon.ico');
})()
