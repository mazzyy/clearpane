package io.github.mazzyy.clearpane;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Message;
import android.provider.MediaStore;
import android.text.InputType;
import android.text.TextUtils;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.Base64;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.MimeTypeMap;
import android.webkit.URLUtil;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
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

import androidx.webkit.ScriptHandler;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

public class MainActivity extends Activity {

    private static final String DEFAULT_HOME = "https://www.wikipedia.org";
    private static final int BRAND = 0xFF231A63;        // toolbar and status bar
    private static final int BRAND_ACCENT = 0xFFF0478E;  // progress line
    private static final int REQUEST_FILE = 41;
    private static final String AD_OPTIONS_MARKER = "/*CP_AD_OPTIONS*/{ cosmetic: true, overlays: true }";

    /** A WebView that can keep believing it is on screen, so media keeps playing in the background. */
    static class KeepAliveWebView extends WebView {
        boolean keepVisible;

        KeepAliveWebView(Context context) {
            super(context);
        }

        @Override
        protected void onWindowVisibilityChanged(int visibility) {
            if (keepVisible && visibility != View.VISIBLE) return;
            super.onWindowVisibilityChanged(visibility);
        }
    }

    private SharedPreferences prefs;
    private FrameLayout root;
    private LinearLayout content;
    private LinearLayout bar;
    private KeepAliveWebView web;
    private EditText urlBar;
    private TextView titleView;
    private ImageButton starButton, moreButton;
    private ProgressBar progress;

    // Swapped as a whole once the large list has loaded; read from WebView worker threads.
    private volatile Set<String> blockedHosts = new HashSet<>();
    private volatile boolean blockAds = true;
    private boolean hideOverlays = true;
    private boolean blockPopups = true;
    private boolean lockToSite = true;
    private boolean showUrlBar = true;
    private boolean skipYouTubeAds = true;
    private boolean backgroundPlay = true;
    private String historyMode = "all";        // all | sites | off
    private String startMode = "favourites";   // favourites | last | home

    private String adScript = "";
    private String youTubeScript = "";
    private String iconFinderScript = "";
    private String pagesHtml = "";
    private ScriptHandler pageScriptHandler;
    private boolean pageScriptAtStart = false;

    // "Stay on site" lock: the sites the current page legitimately lives on.
    private final Set<String> lockedSites = new HashSet<>();
    private boolean appInitiatedLoad = false;
    private int blockedCount = 0;
    // True while a built-in page (favourites, history, about, error) is on screen.
    private boolean internalShowing = false;

    private boolean resumed = false;
    private long barToggledAt = 0;
    private ValueCallback<Uri[]> fileCallback;
    private long blobRequestedAt = 0;

    // Fullscreen video
    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("clearpane", MODE_PRIVATE);
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

        web.keepVisible = backgroundPlay;
        web.setWebViewClient(new Client());
        web.setWebChromeClient(new Chrome());
        web.setDownloadListener(this::startDownload);
        web.setOnLongClickListener(v -> onLongPress());
        web.setOnScrollChangeListener((v, x, y, oldX, oldY) -> onPageScrolled(y, y - oldY));
        applyPageScripts();
        addBlobBridge();

        String shortcut = urlFromIntent(getIntent());
        if (shortcut != null) {
            openUrl(shortcut);
        } else if (savedInstanceState != null && web.restoreState(savedInstanceState) != null) {
            lockedSites.add(siteKey(hostOf(homeUrl())));
        } else {
            openStartScreen();
        }
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        String shortcut = urlFromIntent(intent);
        if (shortcut != null) openUrl(shortcut);
    }

    /** A home-screen shortcut carries the page to open as the intent's data. */
    private static String urlFromIntent(Intent intent) {
        if (intent == null || !Intent.ACTION_VIEW.equals(intent.getAction()) || intent.getData() == null) return null;
        String url = intent.getData().toString();
        return isWebUrl(url) ? url : null;
    }

    private void openStartScreen() {
        String last = prefs.getString("lastUrl", null);
        if (startMode.equals("home")) loadHome();
        else if (startMode.equals("last") && isWebUrl(last)) openUrl(last);
        else showInternal("favourites");
    }

    // ---------------------------------------------------------------- UI

    private int dp(float value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics()));
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }

    private ImageButton iconButton(int drawable, String label, int size, View.OnClickListener onClick) {
        ImageButton b = new ImageButton(this);
        b.setImageResource(drawable);
        b.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN);
        b.setContentDescription(label);
        TypedValue ripple = new TypedValue();
        getTheme().resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true);
        b.setBackgroundResource(ripple.resourceId);
        b.setOnClickListener(onClick);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(size), dp(size)));
        return b;
    }

    private void buildUi() {
        root = new FrameLayout(this);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);

        // One slim row: favourites, the site name (tap to type an address), menu.
        bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BRAND);
        bar.setPadding(dp(4), dp(3), dp(4), dp(3));

        ImageButton gridButton = iconButton(R.drawable.ic_grid, "Favourites", 40, v -> showInternal("favourites"));

        LinearLayout pill = new LinearLayout(this);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable pillShape = new GradientDrawable();
        pillShape.setColor(0x24FFFFFF);
        pillShape.setCornerRadius(dp(18));
        pill.setBackground(pillShape);
        LinearLayout.LayoutParams pillParams = new LinearLayout.LayoutParams(0, dp(36), 1f);
        pillParams.setMargins(dp(4), 0, dp(4), 0);

        FrameLayout texts = new FrameLayout(this);

        // What you normally see: just the site's name, like other mobile browsers.
        titleView = new TextView(this);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setTextColor(Color.WHITE);
        titleView.setTextSize(14.5f);
        titleView.setGravity(Gravity.CENTER);
        titleView.setPadding(dp(36), 0, dp(4), 0);   // balances the star on the right
        titleView.setOnClickListener(v -> startTyping());

        // Shown only while typing an address.
        urlBar = new EditText(this);
        urlBar.setBackground(null);
        urlBar.setSingleLine(true);
        urlBar.setHint("Search or enter address");
        urlBar.setHintTextColor(0x99FFFFFF);
        urlBar.setTextColor(Color.WHITE);
        urlBar.setTextSize(14.5f);
        urlBar.setPadding(dp(14), 0, dp(4), 0);
        urlBar.setGravity(Gravity.CENTER_VERTICAL);
        urlBar.setSelectAllOnFocus(true);
        urlBar.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        urlBar.setImeOptions(EditorInfo.IME_ACTION_GO | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        urlBar.setVisibility(View.GONE);
        urlBar.setOnEditorActionListener((v, actionId, event) -> {
            goToTyped(urlBar.getText().toString());
            return true;
        });
        urlBar.setOnFocusChangeListener((v, hasFocus) -> { if (!hasFocus) stopTyping(); });

        texts.addView(titleView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        texts.addView(urlBar, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        starButton = iconButton(R.drawable.ic_star_border, "Add to favourites", 36, v -> toggleFavourite());
        pill.addView(texts, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f));
        pill.addView(starButton);

        moreButton = iconButton(R.drawable.ic_more, "Menu", 40, v -> showMenu());

        bar.addView(gridButton);
        bar.addView(pill, pillParams);
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

        web = new KeepAliveWebView(this);
        content.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        root.addView(content, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);
    }

    /** Tapping the site name swaps it for an editable address, if the setting allows. */
    private void startTyping() {
        if (!showUrlBar) return;
        String url = web.getUrl();
        titleView.setVisibility(View.GONE);
        urlBar.setVisibility(View.VISIBLE);
        urlBar.setText(isWebUrl(url) && !internalShowing ? url : "");
        urlBar.requestFocus();
        urlBar.selectAll();
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(urlBar, InputMethodManager.SHOW_IMPLICIT);
    }

    private void stopTyping() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(urlBar.getWindowToken(), 0);
        urlBar.setVisibility(View.GONE);
        titleView.setVisibility(View.VISIBLE);
    }

    private void refreshToolbar() {
        String url = web.getUrl();
        boolean onWebPage = isWebUrl(url) && !internalShowing;
        boolean isFav = onWebPage && indexOfUrl(loadList(KEY_FAVS), url) >= 0;
        starButton.setImageResource(isFav ? R.drawable.ic_star : R.drawable.ic_star_border);
        starButton.setContentDescription(isFav ? "Remove from favourites" : "Add to favourites");
        starButton.setVisibility(onWebPage ? View.VISIBLE : View.INVISIBLE);
        titleView.setText(onWebPage ? displayHost(hostOf(url)) : getString(R.string.app_name));
    }

    /** "youtube.com" for m.youtube.com: what the toolbar shows. */
    private static String displayHost(String host) {
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("www.")) h = h.substring(4);
        else if (h.startsWith("m.")) h = h.substring(2);
        return h;
    }

    /** Hides the toolbar while reading down a page and brings it back on the way up. */
    private void onPageScrolled(int y, int dy) {
        if (customView != null || urlBar.hasFocus()) return;
        long now = System.currentTimeMillis();
        if (now - barToggledAt < 450) return;   // the resize itself causes scroll events
        boolean visible = bar.getVisibility() == View.VISIBLE;
        if (visible && dy > dp(6) && y > dp(120)) setBarVisible(false);
        else if (!visible && (dy < -dp(6) || y <= dp(4))) setBarVisible(true);
    }

    private void setBarVisible(boolean show) {
        if ((bar.getVisibility() == View.VISIBLE) == show) return;
        barToggledAt = System.currentTimeMillis();
        TransitionManager.beginDelayedTransition(content, new AutoTransition().setDuration(140));
        bar.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void showMenu() {
        PopupMenu menu = new PopupMenu(this, moreButton);
        Menu m = menu.getMenu();
        boolean onWebPage = isWebUrl(web.getUrl()) && !internalShowing;
        m.add(0, 1, 0, "Forward").setEnabled(web.canGoForward());
        m.add(0, 2, 1, "Reload");
        m.add(0, 3, 2, "Start page");
        m.add(0, 8, 3, "Add to home screen").setEnabled(onWebPage && Build.VERSION.SDK_INT >= 26);
        m.add(0, 9, 4, "Share").setEnabled(onWebPage);
        m.add(0, 4, 5, "History");
        m.add(0, 5, 6, "Settings");
        m.add(0, 6, 7, "About");
        m.add(0, 7, 8, (blockAds ? "Ad blocking on" : "Ad blocking off")
                + " · " + blockedCount + " blocked").setEnabled(false);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: web.goForward(); return true;
                case 2: if (internalShowing) pushInternal(); else web.reload(); return true;
                case 3: loadHome(); return true;
                case 4: showInternal("history"); return true;
                case 5: showSettings(); return true;
                case 6: showInternal("about"); return true;
                case 8: pinShortcut(web.getUrl(), web.getTitle(), web.getFavicon()); return true;
                case 9: shareText(web.getUrl()); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void shareText(String text) {
        if (text == null) return;
        Intent send = new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text);
        startActivity(Intent.createChooser(send, "Share link"));
    }

    /** Press and hold a link on a web page: open, copy or share it. */
    private boolean onLongPress() {
        if (internalShowing) return false;   // built-in pages show their own menu
        WebView.HitTestResult hit = web.getHitTestResult();
        if (hit == null) return false;
        int type = hit.getType();
        final String link = hit.getExtra();
        if (link == null || !isWebUrl(link)
                || (type != WebView.HitTestResult.SRC_ANCHOR_TYPE
                && type != WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE)) {
            return false;
        }
        new AlertDialog.Builder(this)
                .setTitle(displayHost(hostOf(link)))
                .setItems(new CharSequence[] {"Open", "Copy link", "Share link"}, (d, which) -> {
                    if (which == 0) {
                        openUrl(link);
                    } else if (which == 1) {
                        ClipboardManager clip = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                        if (clip != null) clip.setPrimaryClip(ClipData.newPlainText("link", link));
                        toast("Link copied");
                    } else {
                        shareText(link);
                    }
                })
                .show();
        return true;
    }

    // ---------------------------------------------------------------- prefs / assets

    private void readPrefs() {
        blockAds = prefs.getBoolean("blockAds", true);
        hideOverlays = prefs.getBoolean("hideOverlays", true);
        blockPopups = prefs.getBoolean("blockPopups", true);
        lockToSite = prefs.getBoolean("lockToSite", true);
        showUrlBar = prefs.getBoolean("showUrlBar", true);
        skipYouTubeAds = prefs.getBoolean("skipYouTubeAds", true);
        backgroundPlay = prefs.getBoolean("backgroundPlay", true);
        historyMode = prefs.getString("historyMode", "all");
        startMode = prefs.getString("startMode", "favourites");
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

    private static void addHosts(Set<String> into, String text) {
        for (String line : text.split("\n")) {
            line = line.trim().toLowerCase(Locale.ROOT);
            if (line.isEmpty() || line.startsWith("#")) continue;
            into.add(line);
        }
    }

    private void loadAssets() {
        adScript = readAsset("adblock.js");
        youTubeScript = readAsset("youtube-skip.js");
        iconFinderScript = readAsset("icon-finder.js");
        pagesHtml = readAsset("app-pages.html");

        // The small lists load at once; the large one follows on a worker so startup stays quick.
        final Set<String> quick = new HashSet<>();
        addHosts(quick, readAsset("blocklist.txt"));
        addHosts(quick, readAsset("blocklist-extra.txt"));
        blockedHosts = quick;
        new Thread(() -> {
            Set<String> full = new HashSet<>(quick);
            addHosts(full, readAsset("blocklist-large.txt"));
            blockedHosts = full;
        }).start();
    }

    private boolean isBlockedHost(String host) {
        if (host == null) return false;
        Set<String> hosts = blockedHosts;
        String h = host.toLowerCase(Locale.ROOT);
        while (true) {
            if (hosts.contains(h)) return true;
            int dot = h.indexOf('.');
            if (dot < 0) return false;
            h = h.substring(dot + 1);
        }
    }

    // ---------------------------------------------------------------- page scripts

    /** The ad-hiding and YouTube scripts, with the current settings filled in. */
    private String pageScript() {
        StringBuilder sb = new StringBuilder();
        if (blockAds) {
            sb.append(adScript.replace(AD_OPTIONS_MARKER,
                    "{ cosmetic: true, overlays: " + hideOverlays + " }")).append('\n');
        }
        if (skipYouTubeAds) sb.append(youTubeScript).append('\n');
        return sb.toString();
    }

    /** Registers the scripts to run at the start of every frame, where the WebView supports it. */
    private void applyPageScripts() {
        if (pageScriptHandler != null) {
            pageScriptHandler.remove();
            pageScriptHandler = null;
        }
        pageScriptAtStart = false;
        String script = pageScript();
        if (script.isEmpty() || !WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) return;
        try {
            pageScriptHandler = WebViewCompat.addDocumentStartJavaScript(web, script, Collections.singleton("*"));
            pageScriptAtStart = true;
        } catch (Exception e) {
            pageScriptAtStart = false;
        }
    }

    /** Older WebViews: run the scripts in the top frame once the page is up. */
    private void injectPageScriptFallback() {
        if (pageScriptAtStart) return;
        String script = pageScript();
        if (!script.isEmpty()) web.evaluateJavascript(script, null);
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
        setBarVisible(true);
        web.loadUrl(url);
    }

    /** Opens what was typed: a web address if it looks like one, otherwise a web search. */
    private void goToTyped(String typed) {
        String text = typed == null ? "" : typed.trim();
        if (text.isEmpty()) return;
        String url = text.contains(" ") ? null : normalizeUrl(text);
        if (url == null) url = "https://duckduckgo.com/?q=" + Uri.encode(text);
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
            if (blockAds && !request.isForMainFrame() && isBlockedHost(request.getUrl().getHost())) {
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
            setBarVisible(true);
            refreshToolbar();
        }

        @Override
        public void onPageCommitVisible(WebView view, String url) {
            if (!internalShowing) injectPageScriptFallback();
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            appInitiatedLoad = false;
            if (internalShowing) {
                pushInternal();   // refresh the data if Back returned to a built-in page
            } else {
                injectPageScriptFallback();
                recordHistory(url, view.getTitle());
                backfillIcon(url);
                if (isWebUrl(url)) prefs.edit().putString("lastUrl", url).apply();
            }
            refreshToolbar();
        }

        @Override
        public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
            refreshToolbar();   // keeps the name and star right on single-page sites
            if (!internalShowing && isWebUrl(url)) prefs.edit().putString("lastUrl", url).apply();
        }

        @Override
        public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error) {
            if (!request.isForMainFrame()) return;
            final String failed = request.getUrl().toString();
            final String reason = String.valueOf(error.getDescription());
            view.post(() -> showErrorPage(failed, reason));
        }
    }

    private void showErrorPage(String url, String reason) {
        internalShowing = true;
        String html = "<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'>"
                + "<title>Couldn't load</title><style>body{font:15px Roboto,sans-serif;margin:0;display:flex;"
                + "min-height:90vh;align-items:center;justify-content:center;text-align:center;background:#f3f4f8;"
                + "color:#1b1c22}@media(prefers-color-scheme:dark){body{background:#111216;color:#ececf1}}"
                + "div{padding:24px;max-width:420px}p{color:#888c99;word-break:break-word}"
                + "a{display:inline-block;margin-top:14px;padding:10px 26px;border-radius:20px;background:#6d3aed;"
                + "color:#fff;text-decoration:none}</style><div><h2>Couldn't load this page</h2><p>"
                + TextUtils.htmlEncode(displayHost(hostOf(url))) + "<br>" + TextUtils.htmlEncode(reason)
                + "</p><a href=\"" + TextUtils.htmlEncode(url) + "\">Try again</a></div>";
        web.loadDataWithBaseURL(null, html, "text/html", "utf-8", null);
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

        /** A page asked for a file (for example to upload a PDF): show the system picker. */
        @Override
        public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
                startActivityForResult(params.createIntent(), REQUEST_FILE);
                return true;
            } catch (Exception e) {
                fileCallback = null;
                toast("No app available to pick a file");
                return false;
            }
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_FILE || fileCallback == null) return;
        Uri[] picked = null;
        if (resultCode == RESULT_OK && data != null) {
            ClipData many = data.getClipData();
            if (many != null && many.getItemCount() > 0) {
                picked = new Uri[many.getItemCount()];
                for (int i = 0; i < picked.length; i++) picked[i] = many.getItemAt(i).getUri();
            } else if (data.getData() != null) {
                picked = new Uri[] {data.getData()};
            }
        }
        fileCallback.onReceiveValue(picked);
        fileCallback = null;
    }

    // ---------------------------------------------------------------- downloads

    private void startDownload(String url, String userAgent, String disposition, String mimeType, long length) {
        if (url != null && url.startsWith("blob:")) {
            fetchBlob(url, mimeType);
            return;
        }
        if (!isWebUrl(url)) {
            toast("This kind of download isn't supported");
            return;
        }
        String name = URLUtil.guessFileName(url, disposition, mimeType);
        try {
            DownloadManager.Request request = new DownloadManager.Request(Uri.parse(url));
            request.setMimeType(mimeType);
            request.addRequestHeader("User-Agent", userAgent);
            String cookies = CookieManager.getInstance().getCookie(url);
            if (cookies != null) request.addRequestHeader("Cookie", cookies);
            request.setTitle(name);
            request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
            try {
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name);
            } catch (Exception noPublicFolder) {
                request.setDestinationInExternalFilesDir(this, Environment.DIRECTORY_DOWNLOADS, name);
            }
            DownloadManager manager = (DownloadManager) getSystemService(DOWNLOAD_SERVICE);
            if (manager == null) throw new IllegalStateException("no download manager");
            manager.enqueue(request);
            toast("Downloading " + name);
        } catch (Exception e) {
            toast("Couldn't start the download");
        }
    }

    /**
     * Files a page builds itself (an edited PDF, a converted image) exist only inside the page.
     * Ask the page to hand the bytes over through the message channel below.
     */
    private void fetchBlob(String blobUrl, String mimeType) {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
            toast("This download needs a newer Android System WebView");
            return;
        }
        blobRequestedAt = System.currentTimeMillis();
        toast("Preparing download…");
        String js = "(function(){var u=" + JSONObject.quote(blobUrl) + ";var name='';"
                + "var links=document.querySelectorAll('a[download]');"
                + "for(var i=0;i<links.length;i++){if(links[i].href===u){name=links[i].getAttribute('download')||'';}}"
                + "fetch(u).then(function(r){return r.blob();}).then(function(b){var f=new FileReader();"
                + "f.onloadend=function(){clearpaneBridge.postMessage(JSON.stringify({name:name,type:b.type||"
                + JSONObject.quote(mimeType == null ? "" : mimeType) + ",data:String(f.result)}));};"
                + "f.readAsDataURL(b);}).catch(function(){clearpaneBridge.postMessage('{\"error\":true}');});})();";
        web.evaluateJavascript(js, null);
    }

    private void addBlobBridge() {
        if (!WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) return;
        try {
            WebViewCompat.addWebMessageListener(web, "clearpaneBridge", Collections.singleton("*"),
                    (view, message, sourceOrigin, isMainFrame, replyProxy) -> {
                        // Only honoured straight after this app asked for a file, from the top frame.
                        if (!isMainFrame || System.currentTimeMillis() - blobRequestedAt > 60_000) return;
                        blobRequestedAt = 0;
                        saveBlob(message.getData());
                    });
        } catch (Exception ignored) {
            // without the bridge, page-built downloads report that they are unsupported
        }
    }

    private void saveBlob(String json) {
        try {
            JSONObject o = new JSONObject(json);
            String dataUrl = o.optString("data");
            int comma = dataUrl.indexOf(',');
            if (o.optBoolean("error") || comma < 0) throw new IllegalStateException("no data");
            final byte[] bytes = Base64.decode(dataUrl.substring(comma + 1), Base64.DEFAULT);
            final String type = o.optString("type").isEmpty() ? "application/octet-stream" : o.optString("type");
            String name = o.optString("name").replaceAll("[\\\\/:*?\"<>|]", "_").trim();
            if (name.isEmpty()) {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
                name = "clearpane-" + System.currentTimeMillis() + (ext == null ? "" : "." + ext);
            }
            final String fileName = name;
            new Thread(() -> {
                boolean ok = writeDownload(fileName, type, bytes);
                runOnUiThread(() -> toast(ok ? "Saved " + fileName + " to Downloads" : "Couldn't save the file"));
            }).start();
        } catch (Exception e) {
            toast("Couldn't save the file");
        }
    }

    private boolean writeDownload(String name, String type, byte[] bytes) {
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Downloads.DISPLAY_NAME, name);
                values.put(MediaStore.Downloads.MIME_TYPE, type);
                values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
                Uri target = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                if (target == null) return false;
                try (OutputStream out = getContentResolver().openOutputStream(target)) {
                    if (out == null) return false;
                    out.write(bytes);
                }
            } else {
                File folder = getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (folder == null) return false;
                try (OutputStream out = new FileOutputStream(new File(folder, name))) {
                    out.write(bytes);
                }
            }
            return true;
        } catch (Exception e) {
            return false;
        }
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
        if (!isWebUrl(url) || internalShowing) return;
        JSONArray favs = loadList(KEY_FAVS);
        int at = indexOfUrl(favs, url);
        if (at >= 0) {
            favs.remove(at);
            saveList(KEY_FAVS, favs);
            toast("Removed from favourites");
        } else {
            putFirst(KEY_FAVS, url, web.getTitle(), Integer.MAX_VALUE);
            fetchIcon(url);
            toast("Saved to favourites");
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

    // ---------------------------------------------------------------- home-screen shortcuts

    /** Puts a page on the phone's home screen as its own icon, opening straight into it. */
    private void pinShortcut(String url, String title, Bitmap fallbackIcon) {
        if (!isWebUrl(url)) return;
        if (Build.VERSION.SDK_INT < 26) {
            toast("Home screen shortcuts need Android 8 or newer");
            return;
        }
        ShortcutManager shortcuts = getSystemService(ShortcutManager.class);
        if (shortcuts == null || !shortcuts.isRequestPinShortcutSupported()) {
            toast("Your home screen doesn't support shortcuts");
            return;
        }
        // Prefer what was saved with the favourite: its icon, and its name.
        Bitmap icon = fallbackIcon;
        JSONArray favs = loadList(KEY_FAVS);
        int at = indexOfUrl(favs, url);
        if (at >= 0) {
            JSONObject fav = favs.optJSONObject(at);
            String encoded = fav == null ? "" : fav.optString("icon");
            if (!encoded.isEmpty()) {
                try {
                    byte[] raw = Base64.decode(encoded, Base64.DEFAULT);
                    Bitmap saved = BitmapFactory.decodeByteArray(raw, 0, raw.length);
                    if (saved != null) icon = saved;
                } catch (Exception ignored) {
                    // keep the fallback
                }
            }
        }
        Uri uri = Uri.parse(url);
        String host = hostOf(url);
        String path = uri.getPath();
        boolean isSiteRoot = (path == null || path.isEmpty() || path.equals("/")) && uri.getQuery() == null;
        String label = (isSiteRoot || title == null || title.isEmpty()) ? siteName(host) : title;
        if (label.length() > 24) label = label.substring(0, 24).trim();

        Intent open = new Intent(this, MainActivity.class)
                .setAction(Intent.ACTION_VIEW)
                .setData(uri);
        ShortcutInfo info = new ShortcutInfo.Builder(this, "page-" + Integer.toHexString(url.hashCode()))
                .setShortLabel(label)
                .setLongLabel(label)
                .setIcon(Icon.createWithAdaptiveBitmap(shortcutBitmap(icon, host)))
                .setIntent(open)
                .build();
        shortcuts.requestPinShortcut(info, null);
    }

    /** An adaptive-icon bitmap: the site's icon, or its initial on a coloured tile. */
    private static Bitmap shortcutBitmap(Bitmap icon, String host) {
        int size = 432;
        Bitmap out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        if (icon != null) {
            canvas.drawColor(Color.WHITE);
            // Keep the picture inside the part every launcher shape leaves visible.
            int box = Math.round(size * 0.5f);
            float scale = Math.min(box / (float) icon.getWidth(), box / (float) icon.getHeight());
            int w = Math.round(icon.getWidth() * scale), h = Math.round(icon.getHeight() * scale);
            int left = (size - w) / 2, top = (size - h) / 2;
            canvas.drawBitmap(icon, null, new Rect(left, top, left + w, top + h), paint);
        } else {
            canvas.drawColor(Color.HSVToColor(new float[] {Math.abs(host.hashCode() % 360), 0.6f, 0.72f}));
            String letter = siteName(host);
            letter = letter.isEmpty() ? "?" : letter.substring(0, 1);
            paint.setColor(Color.WHITE);
            paint.setTextSize(size * 0.3f);
            paint.setTypeface(Typeface.create(Typeface.DEFAULT, Typeface.BOLD));
            paint.setTextAlign(Paint.Align.CENTER);
            canvas.drawText(letter, size / 2f, size / 2f - (paint.descent() + paint.ascent()) / 2f, paint);
        }
        return out;
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
            data.put("canPin", Build.VERSION.SDK_INT >= 26);
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
        setBarVisible(true);
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
            case "pin":
                String url = action.getQueryParameter("u");
                JSONArray favs = loadList(KEY_FAVS);
                int at = indexOfUrl(favs, url);
                JSONObject fav = at >= 0 ? favs.optJSONObject(at) : null;
                pinShortcut(url, fav == null ? "" : fav.optString("title"), null);
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
        t.setPadding(0, dp(18), 0, dp(4));
        box.addView(t);
    }

    private void note(LinearLayout box, String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setAlpha(0.6f);
        box.addView(t);
    }

    /** A group of radio buttons; returns a function-like holder that reports the chosen value. */
    private RadioGroup choices(LinearLayout box, String[] values, String[] labels, String current, int[] idsOut) {
        RadioGroup group = new RadioGroup(this);
        for (int i = 0; i < values.length; i++) {
            RadioButton choice = new RadioButton(this);
            choice.setText(labels[i]);
            choice.setMinHeight(dp(44));
            idsOut[i] = View.generateViewId();
            choice.setId(idsOut[i]);
            group.addView(choice);
        }
        group.check(idsOut[Math.max(0, Arrays.asList(values).indexOf(current))]);
        box.addView(group);
        return group;
    }

    private static String chosen(RadioGroup group, String[] values, int[] ids, String fallback) {
        for (int i = 0; i < values.length; i++) {
            if (ids[i] == group.getCheckedRadioButtonId()) return values[i];
        }
        return fallback;
    }

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(22), 0, dp(22), dp(8));

        heading(box, "When the app opens");
        final String[] startValues = {"favourites", "last", "home"};
        final int[] startIds = new int[startValues.length];
        final RadioGroup start = choices(box, startValues,
                new String[] {"Show my favourites", "Continue where I left off", "Open the start page"},
                startMode, startIds);

        heading(box, "Start page");
        final EditText url = new EditText(this);
        url.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        url.setHint("https://example.com");
        url.setSingleLine(true);
        url.setText(homeUrl());
        box.addView(url);
        final CheckBox bar = option(box, "Tap the site name to type an address", showUrlBar);

        heading(box, "Blocking");
        final CheckBox ads = option(box, "Block ads and trackers", blockAds);
        final CheckBox overlays = option(box, "Hide floating banner ads in pages", hideOverlays);
        final CheckBox popups = option(box, "Block popups and new windows", blockPopups);
        final CheckBox lock = option(box, "Stay on this site (block redirects)", lockToSite);
        final CheckBox yt = option(box, "Skip YouTube ads (best effort)", skipYouTubeAds);
        note(box, "If a page looks broken, try turning off the floating banner option. "
                + "If sign-in gets stuck, turn off \u201cStay on this site\u201d.");

        heading(box, "Playback");
        final CheckBox background = option(box, "Keep playing with the screen off", backgroundPlay);

        heading(box, "History");
        final String[] historyValues = {"all", "sites", "off"};
        final int[] historyIds = new int[historyValues.length];
        final RadioGroup history = choices(box, historyValues,
                new String[] {"Save every page", "Save only website names", "Don't save history"},
                historyMode, historyIds);

        ScrollView scroller = new ScrollView(this);
        scroller.addView(box);

        new AlertDialog.Builder(this)
                .setTitle("Settings")
                .setView(scroller)
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (dialog, which) -> {
                    String normalized = normalizeUrl(url.getText().toString());
                    if (normalized == null) {
                        Toast.makeText(this, "That doesn't look like a web address", Toast.LENGTH_LONG).show();
                        return;
                    }
                    boolean homeChanged = !normalized.equals(homeUrl());
                    boolean turnedOnBackground = background.isChecked() && !backgroundPlay;
                    startMode = chosen(start, startValues, startIds, startMode);
                    historyMode = chosen(history, historyValues, historyIds, historyMode);
                    blockAds = ads.isChecked();
                    hideOverlays = overlays.isChecked();
                    blockPopups = popups.isChecked();
                    lockToSite = lock.isChecked();
                    showUrlBar = bar.isChecked();
                    skipYouTubeAds = yt.isChecked();
                    backgroundPlay = background.isChecked();
                    prefs.edit()
                            .putString("homeURL", normalized)
                            .putString("startMode", startMode)
                            .putString("historyMode", historyMode)
                            .putBoolean("blockAds", blockAds)
                            .putBoolean("hideOverlays", hideOverlays)
                            .putBoolean("blockPopups", blockPopups)
                            .putBoolean("lockToSite", lockToSite)
                            .putBoolean("showUrlBar", showUrlBar)
                            .putBoolean("skipYouTubeAds", skipYouTubeAds)
                            .putBoolean("backgroundPlay", backgroundPlay)
                            .apply();
                    web.keepVisible = backgroundPlay;
                    applyPageScripts();
                    if (turnedOnBackground) askForNotifications();
                    // Only jump to the start page when it was actually changed.
                    if (homeChanged) loadHome();
                    else if (internalShowing) pushInternal();
                    else web.reload();
                })
                .show();
    }

    // ---------------------------------------------------------------- background playback

    private static final String MEDIA_PLAYING_JS =
            "(function(){var m=document.querySelectorAll('video,audio');"
            + "for(var i=0;i<m.length;i++){if(!m[i].paused&&!m[i].ended)return true;}return false;})()";

    /** Android 13+ hides the "playing" notification unless the user allows notifications. */
    private void askForNotifications() {
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[] {"android.permission.POST_NOTIFICATIONS"}, 7);
        }
    }

    private void startPlaybackService() {
        try {
            Intent service = new Intent(this, PlaybackService.class);
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service);
            else startService(service);
        } catch (Exception e) {
            web.onPause();   // not allowed right now; fall back to pausing
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        if (!backgroundPlay) {
            web.onPause();
            return;
        }
        // Something playing in an embedded player is invisible to the page check, so also ask the system.
        AudioManager audio = (AudioManager) getSystemService(AUDIO_SERVICE);
        final boolean systemAudio = audio != null && audio.isMusicActive();
        web.evaluateJavascript(MEDIA_PLAYING_JS, value -> {
            if (resumed) return;
            if (systemAudio || "true".equals(value)) startPlaybackService();
            else web.onPause();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        web.onResume();
        stopService(new Intent(this, PlaybackService.class));
    }

    // ---------------------------------------------------------------- lifecycle

    @Override
    public void onBackPressed() {
        if (customView != null) {
            exitFullscreen();
        } else if (urlBar.hasFocus()) {
            urlBar.clearFocus();
            web.requestFocus();
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
    protected void onDestroy() {
        stopService(new Intent(this, PlaybackService.class));
        content.removeView(web);
        web.destroy();
        super.onDestroy();
    }
}
