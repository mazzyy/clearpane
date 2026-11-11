package com.local.webshell;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String DEFAULT_HOME = "https://www.wikipedia.org";
    private static final int BRAND = 0xFF231A63;        // toolbar and status bar
    private static final int BRAND_ACCENT = 0xFFF0478E;  // progress line

    private static final String COSMETIC_CSS =
            ".adsbygoogle, ins.adsbygoogle, [id^=\"google_ads_\"], [id^=\"div-gpt-ad\"], "
            + "iframe[src*=\"doubleclick.net\"], iframe[src*=\"googlesyndication\"], "
            + ".ad-banner, .ad-container, .ad-slot, .ad-wrapper, .advertisement, .adsbox, "
            + "[class^=\"ad-unit\"], [data-ad-slot], [aria-label=\"Advertisement\"]"
            + " { display: none !important; }";

    private SharedPreferences prefs;
    private FrameLayout root;
    private LinearLayout content;
    private WebView web;
    private EditText urlBar;
    private TextView titleView;
    private ImageButton backButton, starButton, moreButton;
    private ProgressBar progress;

    private final Set<String> blockedHosts = new HashSet<>();
    // Read from the WebView's background thread in shouldInterceptRequest.
    private volatile boolean blockAds = true;
    private boolean blockPopups = true;
    private boolean lockToSite = true;
    private boolean showUrlBar = false;
    private boolean skipYouTubeAds = true;
    private String historyMode = "all";   // all | sites | off

    private String youTubeScript = "";
    private String iconFinderScript = "";
    private String pagesHtml = "";

    // "Stay on site" lock: the sites the current page legitimately lives on.
    private final Set<String> lockedSites = new HashSet<>();
    private boolean appInitiatedLoad = false;
    private int blockedCount = 0;
    // True while a built-in page (favourites, history, about) is on screen.
    private boolean internalShowing = false;

    // Fullscreen video
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("webshell", MODE_PRIVATE);
        readPrefs();
        loadAssets();
        getWindow().setStatusBarColor(BRAND);
        buildUi();

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        // Multiple-window support ON so popups are routed to onCreateWindow, where we refuse them.
        s.setSupportMultipleWindows(true);
        s.setJavaScriptCanOpenWindowsAutomatically(false);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, false);

        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());

        if (savedInstanceState != null && web.restoreState(savedInstanceState) != null) {
            lockedSites.add(siteKey(hostOf(homeUrl())));
        } else {
            loadHome();
        }
    }

    // ---------------------------------------------------------------- UI

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private ImageButton iconButton(int drawable, String label, View.OnClickListener onClick) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(drawable);
        b.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        b.setContentDescription(label);
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        b.setBackgroundResource(ripple.resourceId);
        b.setOnClickListener(onClick);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(44), dp(44)));
        return b;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // Toolbar: back, address pill, favourite star, favourites grid, menu.
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BRAND);
        bar.setPadding(dp(4), dp(4), dp(4), dp(4));

        backButton = iconButton(R.drawable.ic_back, "Back", v -> { if (web.canGoBack()) web.goBack(); });

        FrameLayout pill = new FrameLayout(this);
        GradientDrawable pillShape = new GradientDrawable();
        pillShape.setColor(0x29FFFFFF);
        pillShape.setCornerRadius(dp(20));
        pill.setBackground(pillShape);
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(0, dp(40), 1f);
        pillParams.setMargins(dp(4), 0, dp(4), 0);

        urlBar = new EditText(this);
        urlBar.setBackground(null);
        urlBar.setSingleLine(true);
        urlBar.setHint("Search or enter address");
        urlBar.setHintTextColor(0x99FFFFFF);
        urlBar.setTextColor(Color.WHITE);
        urlBar.setTextSize(15);
        urlBar.setPadding(dp(16), 0, dp(16), 0);
        urlBar.setGravity(Gravity.CENTER_VERTICAL);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            goToTyped(urlBar.getText().toString());
            return true;
        });

        titleView = new TextView(this);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(15);
        titleView.setPadding(dp(16), 0, dp(16), 0);
        titleView.setGravity(Gravity.CENTER_VERTICAL);

        pill.addView(urlBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        pill.addView(titleView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        starButton = iconButton(R.drawable.ic_star_border, "Add to favourites", v -> toggleFavourite());
        ImageButton gridButton = iconButton(R.drawable.ic_grid, "Favourites", v -> showInternal("favourites"));
        moreButton = iconButton(R.drawable.ic_more, "Menu", v -> showMenu());

        bar.addView(backButton);
        bar.addView(pill, pillParams);
        bar.addView(starButton);
        bar.addView(gridButton);
        bar.addView(moreButton);
        content.addView(bar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // Thin page-load progress line under the toolbar.
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(ColorStateList.valueOf(BRAND_ACCENT));
        progress.setProgressBackgroundTintList(ColorStateList.valueOf(BRAND));
        progress.setVisibility(View.GONE);
        content.addView(progress, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(3)));

        web = new WebView(this);
        content.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
        applyBarMode();
    }

    /** Address bar setting: an editable address, or just the page name. */
    private void applyBarMode() {
        urlBar.setVisibility(showUrlBar ? View.VISIBLE : View.GONE);
        titleView.setVisibility(showUrlBar ? View.GONE : View.VISIBLE);
    }

    private void refreshToolbar() {
        String url = web.getUrl();
        backButton.setAlpha(web.canGoBack() ? 1f : 0.4f);
        boolean isFav = isWebUrl(url) && indexOfUrl(loadList(KEY_FAVS), url) >= 0;
        starButton.setImageResource(isFav ? R.drawable.ic_star : R.drawable.ic_star_border);
        starButton.setContentDescription(isFav ? "Remove from favourites" : "Add to favourites");
        starButton.setAlpha(isWebUrl(url) ? 1f : 0.4f);

        if (internalShowing) {
            titleView.setText(getString(R.string.app_name));
            if (!urlBar.hasFocus()) urlBar.setText("");
        } else {
            String title = web.getTitle();
            titleView.setText(title == null || title.isEmpty() || title.startsWith("http")
                    ? siteName(hostOf(url)) : title);
            if (!urlBar.hasFocus() && isWebUrl(url)) urlBar.setText(url);
        }
    }

    private void showMenu() {
        PopupMenu menu = new PopupMenu(this, moreButton);
        Menu m = menu.getMenu();
        m.add(0, 1, 0, "Forward").setEnabled(web.canGoForward());
        m.add(0, 2, 1, "Reload");
        m.add(0, 3, 2, "Home");
        m.add(0, 4, 3, "History");
        m.add(0, 5, 4, "Settings");
        m.add(0, 6, 5, "About");
        m.add(0, 7, 6, (blockAds ? "Ad blocking on" : "Ad blocking off")
                + " · " + blockedCount + " blocked").setEnabled(false);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: web.goForward(); return true;
                case 2: if (internalShowing) pushInternal(); else web.reload(); return true;
                case 3: loadHome(); return true;
                case 4: showInternal("history"); return true;
                case 5: showSettings(); return true;
                case 6: showInternal("about"); return true;
                default: return false;
            }
        });
        menu.show();
    }

    // ---------------------------------------------------------------- prefs / assets

    private void readPrefs() {
        blockAds = prefs.getBoolean("blockAds", true);
        blockPopups = prefs.getBoolean("blockPopups", true);
        lockToSite = prefs.getBoolean("lockToSite", true);
        showUrlBar = prefs.getBoolean("showUrlBar", false);
        skipYouTubeAds = prefs.getBoolean("skipYouTubeAds", true);
        historyMode = prefs.getString("historyMode", "all");
    }

    private String homeUrl() {
        return prefs.getString("homeURL", DEFAULT_HOME);
    }

    private String readAsset(String name) {
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(getAssets().open(name), "UTF-8"))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }

    private void loadAssets() {
        youTubeScript = readAsset("youtube-skip.js");
        iconFinderScript = readAsset("icon-finder.js");
        pagesHtml = readAsset("app-pages.html");
        // blocklist-extra.txt is optional; a missing file just reads as empty.
        for (String name : new String[] {"blocklist.txt", "blocklist-extra.txt"}) {
            for (String line : readAsset(name).split("\n")) {
                line = line.trim().toLowerCase(Locale.ROOT);
                if (line.isEmpty() || line.startsWith("#")) continue;
                blockedHosts.add(line);
            }
        }
    }

    private boolean isBlockedHost(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        while (true) {
            if (blockedHosts.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0) return false;
            h = h.substring(dot + 1);
        }
    }

    // ---------------------------------------------------------------- URL helpers

    private static String normalizeUrl(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return null;
        String lower = s.toLowerCase(Locale.ROOT);
        if (!lower.startsWith("http://") && !lower.startsWith("https://")) s = "https://" + s;
        String host = Uri.parse(s).getHost();
        if (host == null || !(host.contains(".") || host.equals("localhost"))) return null;
        return s;
    }

    private static boolean isWebUrl(String url) {
        return url != null && (url.startsWith("http://") || url.startsWith("https://"));
    }

    private static String hostOf(String url) {
        String h = url == null ? null : Uri.parse(url).getHost();
        return h == null ? "" : h;
    }

    /** Rough "registrable domain": example.com for www.example.com, example.co.uk for a.example.co.uk. */
    private static String siteKey(String host) {
        String[] p = host.toLowerCase(Locale.ROOT).split("\\.");
        if (p.length <= 2) return host.toLowerCase(Locale.ROOT);
        Set<String> second = new HashSet<>(Arrays.asList("co", "com", "org", "net", "gov", "ac", "edu"));
        int take = (p[p.length - 1].length() == 2 && second.contains(p[p.length - 2])) ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = p.length - take; i < p.length; i++) {
            if (sb.length() > 0) sb.append('.');
            sb.append(p[i]);
        }
        return sb.toString();
    }

    /** "Youtube" for m.youtube.com: the site's main name, capitalised. */
    private static String siteName(String host) {
        String key = siteKey(host);
        int dot = key.indexOf('.');
        String name = dot > 0 ? key.substring(0, dot) : key;
        if (name.isEmpty()) return host;
        return name.substring(0, 1).toUpperCase(Locale.ROOT) + name.substring(1);
    }

    // ---------------------------------------------------------------- navigation

    private void loadHome() {
        String url = normalizeUrl(homeUrl());
        openUrl(url == null ? DEFAULT_HOME : url);
    }

    /** A navigation the user asked for: resets the "stay on site" lock to the new site. */
    private void openUrl(String url) {
        if (!isWebUrl(url)) return;
        lockedSites.clear();
        lockedSites.add(siteKey(hostOf(url)));
        appInitiatedLoad = true;
        internalShowing = false;
        web.loadUrl(url);
    }

    /** Opens what was typed: a web address if it looks like one, otherwise a web search. */
    private void goToTyped(String typed) {
        String text = typed == null ? "" : typed.trim();
        if (text.isEmpty()) return;
        String url = text.contains(" ") ? null : normalizeUrl(text);
        if (url == null) url = "https://duckduckgo.com/?q=" + Uri.encode(text);
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        urlBar.clearFocus();
        web.requestFocus();
        openUrl(url);
    }

    /** Search engines exist to send you elsewhere, so they are not a site to be locked onto. */
    private static boolean isSearchSite(String host) {
        if (host == null || host.isEmpty()) return false;
        if (host.equalsIgnoreCase("search.brave.com")) return true;
        String name = siteKey(host).split("\\.")[0];
        return Arrays.asList("duckduckgo", "google", "bing", "startpage", "ecosia", "qwant", "yahoo")
                .contains(name);
    }

    /**
     * When the user follows a result from a search page, extend the "stay on this site"
     * lock to the site they chose, so the result opens instead of being blocked.
     */
    private void leaveSearchIfNeeded(Uri target, boolean userDriven) {
        if (!lockToSite || !userDriven) return;
        String scheme = target.getScheme();
        String host = target.getHost();
        if (host == null || !("http".equals(scheme) || "https".equals(scheme))) return;
        if (!isSearchSite(hostOf(web.getUrl()))) return;
        if (blockAds && isBlockedHost(host)) return;
        if (lockedSites.add(siteKey(host))) {
            // Lets the chosen site finish any redirects of its own (e.g. to its www. address).
            appInitiatedLoad = true;
        }
    }

    /** True if a top-level navigation to this URL should go ahead. */
    private boolean allowsTopLevel(Uri uri, boolean isRedirect) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (scheme.equals("about")) return true;
        // Drops intent://, market:// and other app-launching links that ads use.
        if (!scheme.equals("http") && !scheme.equals("https")) return false;
        String host = uri.getHost();
        if (blockAds && isBlockedHost(host)) return false;
        if (lockToSite && host != null && !lockedSites.contains(siteKey(host))) {
            // Redirects during a load the app itself started (Home, address bar) widen the lock.
            if (appInitiatedLoad && isRedirect) {
                lockedSites.add(siteKey(host));
                return true;
            }
            return false;
        }
        return true;
    }

    private class Client extends WebViewClient {
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            Uri target = request.getUrl();
            if ("clearpane".equals(target.getScheme())) {
                // Commands from the built-in pages; ignored anywhere else.
                if (internalShowing) handleInternalAction(target);
                return true;
            }
            if (!request.isForMainFrame()) return false;
            if (internalShowing && isWebUrl(target.toString())) {
                openUrl(target.toString());
                return true;
            }
            leaveSearchIfNeeded(target, request.hasGesture() || request.isRedirect());
            if (allowsTopLevel(target, request.isRedirect())) return false;
            blockedCount++;
            return true;
        }

        @Override
        public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
            if (blockAds && isBlockedHost(request.getUrl().getHost())) {
                return new WebResourceResponse("text/plain", "utf-8",
                        new ByteArrayInputStream(new byte[0]));
            }
            return null;
        }

        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            // Built-in pages load as about:blank; this also catches Back returning to one.
            internalShowing = !isWebUrl(url);
            if (appInitiatedLoad && isWebUrl(url)) lockedSites.add(siteKey(hostOf(url)));
            refreshToolbar();
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            if (internalShowing) return;
            injectCosmeticCss();
            injectYouTubeSkipper(url);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            appInitiatedLoad = false;
            if (internalShowing) {
                pushInternal();   // refresh the data if Back returned to a built-in page
            } else {
                injectCosmeticCss();
                injectYouTubeSkipper(url);
                recordHistory(url, view.getTitle());
                backfillIcon(url);
            }
            refreshToolbar();
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            refreshToolbar();   // keeps the address and star right on single-page sites
        }
    }

    private void injectCosmeticCss() {
        if (!blockAds) return;
        String js = "(function(){if(document.getElementById('__webshell_css'))return;"
                + "var s=document.createElement('style');s.id='__webshell_css';"
                + "s.textContent='" + COSMETIC_CSS.replace("\\", "\\\\").replace("'", "\\'") + "';"
                + "(document.head||document.documentElement).appendChild(s);})();";
        web.evaluateJavascript(js, null);
    }

    private void injectYouTubeSkipper(String url) {
        if (!skipYouTubeAds || youTubeScript.isEmpty()) return;
        String host = hostOf(url).toLowerCase(Locale.ROOT);
        if (host.equals("youtube.com") || host.endsWith(".youtube.com")) {
            web.evaluateJavascript(youTubeScript, null);
        }
    }

    private class Chrome extends WebChromeClient {
        @Override
        public void onProgressChanged(WebView view, int newProgress) {
            progress.setProgress(newProgress);
            progress.setVisibility(newProgress < 100 && !internalShowing ? View.VISIBLE : View.GONE);
        }

        @Override
        public void onReceivedTitle(WebView view, String title) {
            refreshToolbar();
        }

        @Override
        public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
            // Never open a second window. A tapped link (target=_blank) to an allowed page
            // opens in place; script-opened windows are dropped when popup blocking is on.
            WebView.HitTestResult hit = view.getHitTestResult();
            String link = hit == null ? null : hit.getExtra();
            boolean isLinkTap = isUserGesture && hit != null
                    && hit.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE && link != null;
            if (isLinkTap) leaveSearchIfNeeded(Uri.parse(link), true);
            if (link != null && (isLinkTap || !blockPopups) && allowsTopLevel(Uri.parse(link), false)) {
                view.loadUrl(link);
            } else {
                blockedCount++;
            }
            return false;
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (customView != null) {
                callback.onCustomViewHidden();
                return;
            }
            customView = view;
            customViewCallback = callback;
            root.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            content.setVisibility(View.GONE);
            getWindow().getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }

        @Override
        public void onHideCustomView() {
            exitFullscreen();
        }
    }

    private void exitFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        content.setVisibility(View.VISIBLE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        if (customViewCallback != null) customViewCallback.onCustomViewHidden();
        customViewCallback = null;
    }

    // ---------------------------------------------------------------- saved pages

    private static final String KEY_HISTORY = "history", KEY_FAVS = "favourites";
    private static final int HISTORY_LIMIT = 300;

    private JSONArray loadList(String key) {
        try {
            return new JSONArray(prefs.getString(key, "[]"));
        } catch (Exception e) {
            return new JSONArray();
        }
    }

    private void saveList(String key, JSONArray list) {
        prefs.edit().putString(key, list.toString()).apply();
    }

    private static int indexOfUrl(JSONArray list, String url) {
        if (url == null) return -1;
        for (int i = 0; i < list.length(); i++) {
            JSONObject o = list.optJSONObject(i);
            if (o != null && url.equals(o.optString("url"))) return i;
        }
        return -1;
    }

    /** Puts the page at the top of the list, removing any earlier entry for the same URL. */
    private void putFirst(String key, String url, String title, int limit) {
        JSONArray old = loadList(key), out = new JSONArray();
        try {
            out.put(new JSONObject().put("url", url).put("title", title == null ? "" : title));
            for (int i = 0; i < old.length() && out.length() < limit; i++) {
                JSONObject o = old.optJSONObject(i);
                if (o != null && !url.equals(o.optString("url"))) out.put(o);
            }
        } catch (Exception ignored) {
            return;
        }
        saveList(key, out);
    }

    private void recordHistory(String url, String title) {
        if (!isWebUrl(url) || historyMode.equals("off")) return;
        if (historyMode.equals("sites")) {
            // Keep one entry per website, named after the site.
            String host = hostOf(url);
            putFirst(KEY_HISTORY, Uri.parse(url).getScheme() + "://" + host + "/", siteName(host), HISTORY_LIMIT);
        } else {
            putFirst(KEY_HISTORY, url, title, HISTORY_LIMIT);
        }
    }

    private void toggleFavourite() {
        String url = web.getUrl();
        if (!isWebUrl(url)) return;
        JSONArray favs = loadList(KEY_FAVS);
        int at = indexOfUrl(favs, url);
        if (at >= 0) {
            favs.remove(at);
            saveList(KEY_FAVS, favs);
            Toast.makeText(this, "Removed from favourites", Toast.LENGTH_SHORT).show();
        } else {
            putFirst(KEY_FAVS, url, web.getTitle(), Integer.MAX_VALUE);
            fetchIcon(url);
            Toast.makeText(this, "Saved to favourites", Toast.LENGTH_SHORT).show();
        }
        refreshToolbar();
    }

    private void removeFavourite(String url) {
        JSONArray favs = loadList(KEY_FAVS);
        int at = indexOfUrl(favs, url);
        if (at < 0) return;
        favs.remove(at);
        saveList(KEY_FAVS, favs);
    }

    // ---------------------------------------------------------------- built-in pages

    /** Everything the built-in pages need, as JSON. page may be null to keep the current tab. */
    private String pageData(String page) {
        JSONObject data = new JSONObject();
        try {
            if (page != null) data.put("page", page);
            data.put("favourites", loadList(KEY_FAVS));
            data.put("history", loadList(KEY_HISTORY));
            data.put("historyMode", historyMode);
            data.put("grouped", prefs.getBoolean("historyGrouped", true));
            data.put("hideSuggested", prefs.getBoolean("hideSuggested", false));
            data.put("platform", "android");
            data.put("version", getPackageManager().getPackageInfo(getPackageName(), 0).versionName);
        } catch (Exception ignored) {
            // a missing field falls back to the page's own default
        }
        // Safe to embed inside a <script> block.
        return data.toString().replace("</", "<\\/").replace("\u2028", "\\u2028").replace("\u2029", "\\u2029");
    }

    /** page: "favourites", "history" or "about". */
    private void showInternal(String page) {
        internalShowing = true;
        web.loadDataWithBaseURL(null, pagesHtml.replace("/*CP_DATA*/null", pageData(page)),
                "text/html", "utf-8", null);
    }

    /** Redraws the built-in page in place after its data changed. */
    private void pushInternal() {
        web.evaluateJavascript("window.cpSet && window.cpSet(" + pageData(null) + ")", null);
    }

    private void handleInternalAction(Uri action) {
        String name = action.getHost() == null ? "" : action.getHost();
        switch (name) {
            case "remove-favourite":
                removeFavourite(action.getQueryParameter("u"));
                pushInternal();
                break;
            case "delete-history":
                deleteHistoryAt(action.getQueryParameter("i"));
                pushInternal();
                break;
            case "set":
                String grouped = action.getQueryParameter("grouped");
                if (grouped != null) prefs.edit().putBoolean("historyGrouped", grouped.equals("1")).apply();
                String suggested = action.getQueryParameter("suggested");
                if (suggested != null) prefs.edit().putBoolean("hideSuggested", suggested.equals("0")).apply();
                break;
            default:
                break;
        }
    }

    /** Deletes history entries by their position in the list, given as "0,3,7". */
    private void deleteHistoryAt(String indexes) {
        if (indexes == null || indexes.isEmpty()) return;
        Set<Integer> drop = new HashSet<>();
        for (String part : indexes.split(",")) {
            try {
                drop.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                // skip anything that is not a number
            }
        }
        JSONArray all = loadList(KEY_HISTORY), kept = new JSONArray();
        for (int i = 0; i < all.length(); i++) {
            if (!drop.contains(i)) kept.put(all.opt(i));
        }
        saveList(KEY_HISTORY, kept);
    }

    // ---------------------------------------------------------------- favourite icons

    /** Fetches an icon for a favourite that was saved without one, the next time it is visited. */
    private void backfillIcon(String url) {
        JSONArray favs = loadList(KEY_FAVS);
        int at = indexOfUrl(favs, url);
        if (at < 0) return;
        JSONObject o = favs.optJSONObject(at);
        if (o != null && o.optString("icon").isEmpty()) fetchIcon(url);
    }

    /** Finds the page's own icon, downloads it, and stores it with the favourite. */
    private void fetchIcon(final String pageUrl) {
        final Bitmap fallback = web.getFavicon();
        web.evaluateJavascript(iconFinderScript, value -> {
            String found = null;
            try {
                found = new JSONArray("[" + value + "]").optString(0, null);
            } catch (Exception ignored) {
                // fall through to the WebView's own favicon
            }
            final String iconUrl = found;
            new Thread(() -> {
                Bitmap bitmap = isWebUrl(iconUrl) ? downloadBitmap(iconUrl) : null;
                if (bitmap == null) bitmap = fallback;
                if (bitmap == null) return;
                final String encoded = encodeIcon(bitmap);
                runOnUiThread(() -> setFavouriteIcon(pageUrl, encoded));
            }).start();
        });
    }

    private static Bitmap downloadBitmap(String address) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "Mozilla/5.0");
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                    if (out.size() > 2_000_000) return null;
                }
                byte[] data = out.toByteArray();
                return BitmapFactory.decodeByteArray(data, 0, data.length);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static String encodeIcon(Bitmap bitmap) {
        int longest = Math.max(bitmap.getWidth(), bitmap.getHeight());
        if (longest > 128) {
            float k = 128f / longest;
            bitmap = Bitmap.createScaledBitmap(bitmap, Math.max(1, Math.round(bitmap.getWidth() * k)),
                    Math.max(1, Math.round(bitmap.getHeight() * k)), true);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
    }

    private void setFavouriteIcon(String url, String encoded) {
        JSONArray favs = loadList(KEY_FAVS);
        int at = indexOfUrl(favs, url);
        if (at < 0) return;
        try {
            favs.getJSONObject(at).put("icon", encoded);
        } catch (Exception e) {
            return;
        }
        saveList(KEY_FAVS, favs);
        if (internalShowing) pushInternal();
    }

    // ---------------------------------------------------------------- settings

    private CheckBox option(LinearLayout box, String label, boolean checked) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(checked);
        c.setMinHeight(dp(44));
        box.addView(c);
        return c;
    }

    private void heading(LinearLayout box, String text) {
        TextView t = new TextView(this);
        t.setText(text.toUpperCase(Locale.ROOT));
        t.setTextSize(12);
        t.setLetterSpacing(0.06f);
        t.setAlpha(0.6f);
        t.setPadding(0, dp(16), 0, dp(4));
        box.addView(t);
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), 0, dp(22), dp(8));

        heading(box, "Start page");
        final EditText url = new EditText(this);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setHint("https://example.com");
        url.setSingleLine(true);
        url.setText(homeUrl());
        box.addView(url);
        final CheckBox bar = option(box, "Show address bar at the top", showUrlBar);

        heading(box, "Blocking");
        final CheckBox ads = option(box, "Block ads and trackers", blockAds);
        final CheckBox popups = option(box, "Block popups and new windows", blockPopups);
        final CheckBox lock = option(box, "Stay on this site (block redirects)", lockToSite);
        final CheckBox yt = option(box, "Skip YouTube ads (best effort)", skipYouTubeAds);
        TextView hint = new TextView(this);
        hint.setText("Turn off \u201cStay on this site\u201d if sign-in sends you through another domain.");
        hint.setTextSize(12);
        hint.setAlpha(0.6f);
        box.addView(hint);

        heading(box, "History");
        final RadioGroup history = new RadioGroup(this);
        final String[] modes = {"all", "sites", "off"};
        String[] modeNames = {"Save every page", "Save only website names", "Don't save history"};
        final int[] modeIds = new int[modes.length];
        for (int i = 0; i < modes.length; i++) {
            RadioButton choice = new RadioButton(this);
            choice.setText(modeNames[i]);
            choice.setMinHeight(dp(44));
            modeIds[i] = View.generateViewId();
            choice.setId(modeIds[i]);
            history.addView(choice);
        }
        history.check(modeIds[Math.max(0, Arrays.asList(modes).indexOf(historyMode))]);
        box.addView(history);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(scroller)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String normalized = normalizeUrl(url.getText().toString());
                    if (normalized == null) {
                        Toast.makeText(this, "That doesn't look like a web address",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    boolean homeChanged = !normalized.equals(homeUrl());
                    for (int i = 0; i < modes.length; i++) {
                        if (modeIds[i] == history.getCheckedRadioButtonId()) historyMode = modes[i];
                    }
                    blockAds = ads.isChecked();
                    blockPopups = popups.isChecked();
                    lockToSite = lock.isChecked();
                    showUrlBar = bar.isChecked();
                    skipYouTubeAds = yt.isChecked();
                    prefs.edit()
                            .putString("homeURL", normalized)
                            .putString("historyMode", historyMode)
                            .putBoolean("blockAds", blockAds)
                            .putBoolean("blockPopups", blockPopups)
                            .putBoolean("lockToSite", lockToSite)
                            .putBoolean("showUrlBar", showUrlBar)
                            .putBoolean("skipYouTubeAds", skipYouTubeAds)
                            .apply();
                    applyBarMode();
                    // Only jump to the start page when it was actually changed.
                    if (homeChanged) loadHome();
                    else if (internalShowing) pushInternal();
                    else web.reload();
                })
                .show();
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onBackPressed() {
        if (customView != null) {
            exitFullscreen();
        } else if (web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        super.onPause();
        web.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        content.removeView(web);
        web.destroy();
        super.onDestroy();
    }
}
