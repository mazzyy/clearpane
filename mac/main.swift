import Cocoa
import WebKit

// MARK: - Preferences

enum Prefs {
    static let d = UserDefaults.standard
    static let defaultHome = "https://www.wikipedia.org"

    static func register() {
        d.register(defaults: [
            "homeURL": defaultHome,
            "blockAds": true,
            "blockPopups": true,
            "lockToSite": true,
            "showAddressBar": false,
            "skipYouTubeAds": true,
            "historyMode": "all",
        ])
    }
    static var homeURL: String {
        get { d.string(forKey: "homeURL") ?? defaultHome }
        set { d.set(newValue, forKey: "homeURL") }
    }
    static var blockAds: Bool {
        get { d.bool(forKey: "blockAds") }
        set { d.set(newValue, forKey: "blockAds") }
    }
    static var blockPopups: Bool {
        get { d.bool(forKey: "blockPopups") }
        set { d.set(newValue, forKey: "blockPopups") }
    }
    /// "all" = every page, "sites" = one entry per website, "off" = nothing.
    static var historyMode: String {
        get { d.string(forKey: "historyMode") ?? "all" }
        set { d.set(newValue, forKey: "historyMode") }
    }
    static var skipYouTubeAds: Bool {
        get { d.bool(forKey: "skipYouTubeAds") }
        set { d.set(newValue, forKey: "skipYouTubeAds") }
    }
    static var showAddressBar: Bool {
        get { d.bool(forKey: "showAddressBar") }
        set { d.set(newValue, forKey: "showAddressBar") }
    }
    static var lockToSite: Bool {
        get { d.bool(forKey: "lockToSite") }
        set { d.set(newValue, forKey: "lockToSite") }
    }
}

// MARK: - Saved pages (history, favourites)

enum PageStore {
    static let historyKey = "history"
    static let favouritesKey = "favourites"

    static func load(_ key: String) -> [[String: String]] {
        Prefs.d.array(forKey: key) as? [[String: String]] ?? []
    }
    static func save(_ key: String, _ pages: [[String: String]]) { Prefs.d.set(pages, forKey: key) }
    static func contains(_ key: String, url: String) -> Bool { load(key).contains { $0["url"] == url } }
    static func remove(_ key: String, url: String) { save(key, load(key).filter { $0["url"] != url }) }

    static func setIcon(_ key: String, url: String, base64: String) {
        save(key, load(key).map { page in
            var page = page
            if page["url"] == url { page["icon"] = base64 }
            return page
        })
    }

    /// Puts the page at the top of the list, removing any earlier entry for the same URL.
    static func putFirst(_ key: String, url: String, title: String, limit: Int) {
        var pages = load(key).filter { $0["url"] != url }
        pages.insert(["url": url, "title": title], at: 0)
        if pages.count > limit { pages.removeLast(pages.count - limit) }
        save(key, pages)
    }
}

// MARK: - URL helpers

func normalizedURL(_ raw: String) -> URL? {
    var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
    if s.isEmpty { return nil }
    if !s.lowercased().hasPrefix("http://") && !s.lowercased().hasPrefix("https://") {
        s = "https://" + s
    }
    guard let url = URL(string: s), let host = url.host, host.contains(".") || host == "localhost" else {
        return nil
    }
    return url
}

/// Rough "registrable domain": example.com for www.example.com, example.co.uk for a.example.co.uk.
func siteKey(_ host: String) -> String {
    let parts = host.lowercased().split(separator: ".").map(String.init)
    if parts.count <= 2 { return parts.joined(separator: ".") }
    let secondLevel: Set<String> = ["co", "com", "org", "net", "gov", "ac", "edu"]
    let last = parts[parts.count - 1]
    let prev = parts[parts.count - 2]
    let take = (last.count == 2 && secondLevel.contains(prev)) ? 3 : 2
    return parts.suffix(take).joined(separator: ".")
}

/// "Youtube" for m.youtube.com: the site's main name, capitalised.
func siteName(_ host: String) -> String {
    let name = siteKey(host).split(separator: ".").first.map(String.init) ?? host
    return name.isEmpty ? host : name.prefix(1).uppercased() + name.dropFirst()
}

func htmlEscape(_ text: String) -> String {
    text.replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
        .replacingOccurrences(of: ">", with: "&gt;").replacingOccurrences(of: "\"", with: "&quot;")
}

func bundledText(_ name: String, _ ext: String) -> String {
    guard let url = Bundle.main.url(forResource: name, withExtension: ext) else { return "" }
    return (try? String(contentsOf: url, encoding: .utf8)) ?? ""
}

/// Redraws an image as a square PNG of the given pixel size.
func iconPNG(_ image: NSImage, side: Int) -> Data? {
    guard let rep = NSBitmapImageRep(bitmapDataPlanes: nil, pixelsWide: side, pixelsHigh: side,
                                     bitsPerSample: 8, samplesPerPixel: 4, hasAlpha: true, isPlanar: false,
                                     colorSpaceName: .deviceRGB, bytesPerRow: 0, bitsPerPixel: 0) else { return nil }
    rep.size = NSSize(width: side, height: side)
    NSGraphicsContext.saveGraphicsState()
    NSGraphicsContext.current = NSGraphicsContext(bitmapImageRep: rep)
    NSGraphicsContext.current?.imageInterpolation = .high
    image.draw(in: NSRect(x: 0, y: 0, width: side, height: side), from: .zero, operation: .sourceOver, fraction: 1)
    NSGraphicsContext.restoreGraphicsState()
    return rep.representation(using: .png, properties: [:])
}

// MARK: - Blocklist

final class Blocklist {
    private(set) var hosts = Set<String>()

    static let cosmeticSelectors = [
        ".adsbygoogle", "ins.adsbygoogle", "[id^=\"google_ads_\"]", "[id^=\"div-gpt-ad\"]",
        "iframe[src*=\"doubleclick.net\"]", "iframe[src*=\"googlesyndication\"]",
        ".ad-banner", ".ad-container", ".ad-slot", ".ad-wrapper", ".advertisement", ".adsbox",
        "[class^=\"ad-unit\"]", "[data-ad-slot]", "[aria-label=\"Advertisement\"]",
    ]

    init() {
        for name in ["blocklist", "blocklist-extra"] {   // the extra list is optional
            guard let url = Bundle.main.url(forResource: name, withExtension: "txt"),
                  let text = try? String(contentsOf: url, encoding: .utf8) else { continue }
            for line in text.split(whereSeparator: \.isNewline) {
                let h = line.trimmingCharacters(in: .whitespaces).lowercased()
                if h.isEmpty || h.hasPrefix("#") { continue }
                hosts.insert(h)
            }
        }
    }

    /// Stable across launches, so the compiled rule list can be reused until the lists change.
    var fingerprint: String {
        var hash: UInt64 = 5381
        for byte in (hosts.sorted().joined(separator: ",") + Blocklist.cosmeticSelectors.joined()).utf8 {
            hash = (hash &* 33) ^ UInt64(byte)
        }
        return String(hash, radix: 36)
    }

    func isBlocked(_ host: String?) -> Bool {
        guard var h = host?.lowercased() else { return false }
        while true {
            if hosts.contains(h) { return true }
            guard let dot = h.firstIndex(of: ".") else { return false }
            h = String(h[h.index(after: dot)...])
        }
    }

    /// Safari-style content blocker rules: network blocks + cosmetic hiding.
    func contentRulesJSON() -> String {
        var rules: [[String: Any]] = []
        for h in hosts.sorted() {
            let escaped = NSRegularExpression.escapedPattern(for: h)
            rules.append([
                "trigger": ["url-filter": "^[^:]+://+([^:/]+\\.)?\(escaped)[:/]"],
                "action": ["type": "block"],
            ])
        }
        rules.append([
            "trigger": ["url-filter": ".*"],
            "action": ["type": "css-display-none",
                       "selector": Blocklist.cosmeticSelectors.joined(separator: ", ")],
        ])
        let data = try! JSONSerialization.data(withJSONObject: rules)
        return String(data: data, encoding: .utf8)!
    }
}

// MARK: - App

final class AppDelegate: NSObject, NSApplicationDelegate, WKNavigationDelegate, WKUIDelegate, NSMenuDelegate {
    var window: NSWindow!
    var webView: WKWebView!
    var backButton: NSButton!
    var forwardButton: NSButton!
    var statusLabel: NSTextField!
    var starButton: NSButton!
    var addressField: NSTextField!
    var barSpacer: NSView!
    var addressCheck: NSButton!
    var youTubeCheck: NSButton!
    var historyPopup: NSPopUpButton!
    let historyModes = ["all", "sites", "off"]
    /// True while the built-in favourites page is on screen.
    var internalShowing = false
    var lastInternalPage: String?
    let favouritesMenu = NSMenu(title: "Favourites")
    let historyMenu = NSMenu(title: "History")
    var observations: [NSKeyValueObservation] = []

    let blocklist = Blocklist()
    var ruleList: WKContentRuleList?
    var blockedCount = 0 { didSet { updateStatus() } }

    // "Stay on site" lock: the sites the home page legitimately lives on.
    var lockedSites = Set<String>()
    var appInitiatedLoad = false

    // Settings window
    var settingsWindow: NSWindow?
    var urlField: NSTextField!
    var adsCheck: NSButton!
    var popupsCheck: NSButton!
    var lockCheck: NSButton!

    func applicationDidFinishLaunching(_ notification: Notification) {
        Prefs.register()
        buildMenu()
        buildWindow()
        let store = WKContentRuleListStore.default()!
        let identifier = "clearpane-rules-" + blocklist.fingerprint
        let ready: (WKContentRuleList?) -> Void = { [weak self] list in
            guard let self = self else { return }
            self.ruleList = list
            self.applyRules()
            self.loadHome()
        }
        store.lookUpContentRuleList(forIdentifier: identifier) { [weak self] cached, _ in
            if let cached = cached { ready(cached); return }
            guard let self = self else { return }
            store.compileContentRuleList(forIdentifier: identifier,
                                         encodedContentRuleList: self.blocklist.contentRulesJSON()) { list, error in
                if let error = error { NSLog("Rule list failed to compile: \(error)") }
                ready(list)
            }
        }
        NSApp.activate(ignoringOtherApps: true)
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    // MARK: UI construction

    func buildWindow() {
        let config = WKWebViewConfiguration()
        config.preferences.javaScriptCanOpenWindowsAutomatically = false
        config.preferences.isElementFullscreenEnabled = true
        config.mediaTypesRequiringUserActionForPlayback = []
        config.applicationNameForUserAgent = "Version/18.0 Safari/605.1.15"

        webView = WKWebView(frame: .zero, configuration: config)
        webView.navigationDelegate = self
        webView.uiDelegate = self
        webView.allowsBackForwardNavigationGestures = true
        webView.translatesAutoresizingMaskIntoConstraints = false

        backButton = barButton("chevron.left", "Back", #selector(goBack))
        forwardButton = barButton("chevron.right", "Forward", #selector(goForward))
        let reload = barButton("arrow.clockwise", "Reload", #selector(reloadPage))
        let home = barButton("house", "Home", #selector(loadHome))
        let settings = barButton("gearshape", "Settings", #selector(showSettings))
        starButton = barButton("star", "Add to Favourites", #selector(toggleFavourite))
        let gridButton = barButton("square.grid.2x2", "Favourites", #selector(showFavouritesPage))

        statusLabel = NSTextField(labelWithString: "")
        statusLabel.textColor = .secondaryLabelColor
        statusLabel.font = .systemFont(ofSize: 11)
        statusLabel.lineBreakMode = .byTruncatingTail

        addressField = NSTextField(string: "")
        addressField.placeholderString = "Enter address or search"
        addressField.bezelStyle = .roundedBezel
        addressField.lineBreakMode = .byTruncatingTail
        addressField.usesSingleLineMode = true
        addressField.target = self
        addressField.action = #selector(addressEntered)
        addressField.setContentHuggingPriority(NSLayoutConstraint.Priority(100), for: .horizontal)
        addressField.setContentCompressionResistancePriority(.defaultLow, for: .horizontal)

        barSpacer = NSView()
        barSpacer.setContentHuggingPriority(.defaultLow, for: .horizontal)
        statusLabel.setContentCompressionResistancePriority(.required, for: .horizontal)

        let bar = NSStackView(views: [backButton, forwardButton, reload, home, addressField, barSpacer, statusLabel, gridButton, starButton, settings])
        bar.orientation = .horizontal
        bar.spacing = 6
        bar.edgeInsets = NSEdgeInsets(top: 5, left: 10, bottom: 5, right: 10)
        bar.translatesAutoresizingMaskIntoConstraints = false

        let content = NSView()
        content.addSubview(bar)
        content.addSubview(webView)
        NSLayoutConstraint.activate([
            bar.topAnchor.constraint(equalTo: content.topAnchor),
            bar.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            bar.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            webView.topAnchor.constraint(equalTo: bar.bottomAnchor),
            webView.leadingAnchor.constraint(equalTo: content.leadingAnchor),
            webView.trailingAnchor.constraint(equalTo: content.trailingAnchor),
            webView.bottomAnchor.constraint(equalTo: content.bottomAnchor),
        ])

        window = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 1200, height: 800),
                          styleMask: [.titled, .closable, .miniaturizable, .resizable],
                          backing: .buffered, defer: false)
        window.title = "Clearpane"
        window.contentView = content
        window.setFrameAutosaveName("WebShellMain")
        window.center()
        window.makeKeyAndOrderFront(nil)

        observations = [
            webView.observe(\.title) { [weak self] wv, _ in
                let t = wv.title ?? ""
                self?.window.title = t.isEmpty ? "Clearpane" : t
            },
            webView.observe(\.canGoBack) { [weak self] wv, _ in self?.backButton.isEnabled = wv.canGoBack },
            webView.observe(\.canGoForward) { [weak self] wv, _ in self?.forwardButton.isEnabled = wv.canGoForward },
        ]
        applyAddressBar()
        backButton.isEnabled = false
        forwardButton.isEnabled = false
        updateStatus()
    }

    func barButton(_ symbol: String, _ label: String, _ action: Selector) -> NSButton {
        let image = NSImage(systemSymbolName: symbol, accessibilityDescription: label) ?? NSImage()
        let b = NSButton(image: image, target: self, action: action)
        b.bezelStyle = .texturedRounded
        b.toolTip = label
        return b
    }

    func buildMenu() {
        let main = NSMenu()

        let appItem = NSMenuItem()
        let appMenu = NSMenu()
        appMenu.addItem(withTitle: "About Clearpane", action: #selector(showAboutPage), keyEquivalent: "")
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "Settings…", action: #selector(showSettings), keyEquivalent: ",")
        appMenu.addItem(.separator())
        appMenu.addItem(withTitle: "Hide Clearpane", action: #selector(NSApplication.hide(_:)), keyEquivalent: "h")
        appMenu.addItem(withTitle: "Quit Clearpane", action: #selector(NSApplication.terminate(_:)), keyEquivalent: "q")
        appItem.submenu = appMenu
        main.addItem(appItem)

        let editItem = NSMenuItem()
        let edit = NSMenu(title: "Edit")
        edit.addItem(withTitle: "Undo", action: Selector(("undo:")), keyEquivalent: "z")
        edit.addItem(withTitle: "Redo", action: Selector(("redo:")), keyEquivalent: "Z")
        edit.addItem(.separator())
        edit.addItem(withTitle: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x")
        edit.addItem(withTitle: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c")
        edit.addItem(withTitle: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v")
        edit.addItem(withTitle: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a")
        editItem.submenu = edit
        main.addItem(editItem)

        let viewItem = NSMenuItem()
        let view = NSMenu(title: "View")
        view.addItem(withTitle: "Reload", action: #selector(reloadPage), keyEquivalent: "r")
        view.addItem(withTitle: "Home", action: #selector(loadHome), keyEquivalent: "H")
        view.addItem(withTitle: "Back", action: #selector(goBack), keyEquivalent: "[")
        view.addItem(withTitle: "Forward", action: #selector(goForward), keyEquivalent: "]")
        viewItem.submenu = view
        main.addItem(viewItem)

        for menu in [favouritesMenu, historyMenu] {
            menu.delegate = self
            let item = NSMenuItem()
            item.submenu = menu
            main.addItem(item)
        }

        let windowItem = NSMenuItem()
        let win = NSMenu(title: "Window")
        win.addItem(withTitle: "Minimize", action: #selector(NSWindow.performMiniaturize(_:)), keyEquivalent: "m")
        win.addItem(withTitle: "Close", action: #selector(NSWindow.performClose(_:)), keyEquivalent: "w")
        windowItem.submenu = win
        main.addItem(windowItem)

        NSApp.mainMenu = main
    }

    func updateStatus() {
        guard statusLabel != nil else { return }
        let shield = Prefs.blockAds ? "Ad blocking on" : "Ad blocking off"
        statusLabel.stringValue = blockedCount > 0
            ? "\(shield) · \(blockedCount) popups/redirects blocked" : shield
    }

    // MARK: Actions

    func applyRules() {
        let ucc = webView.configuration.userContentController
        ucc.removeAllContentRuleLists()
        if Prefs.blockAds, let list = ruleList { ucc.add(list) }
        // The YouTube skipper checks the host itself, so it is inert on every other site.
        ucc.removeAllUserScripts()
        if Prefs.skipYouTubeAds,
           let url = Bundle.main.url(forResource: "youtube-skip", withExtension: "js"),
           let source = try? String(contentsOf: url, encoding: .utf8) {
            ucc.addUserScript(WKUserScript(source: source, injectionTime: .atDocumentEnd, forMainFrameOnly: true))
        }
        updateStatus()
    }

    @objc func loadHome() {
        let url = normalizedURL(Prefs.homeURL) ?? URL(string: Prefs.defaultHome)!
        internalShowing = false
        lockedSites = [siteKey(url.host ?? "")]
        appInitiatedLoad = true
        webView.load(URLRequest(url: url))
    }

    @objc func goBack() { webView.goBack() }
    @objc func goForward() { webView.goForward() }
    @objc func reloadPage() { webView.reload() }

    // MARK: Address bar

    func applyAddressBar() {
        addressField.isHidden = !Prefs.showAddressBar
        barSpacer.isHidden = Prefs.showAddressBar
    }

    func syncAddress() {
        guard addressField != nil, addressField.currentEditor() == nil,
              let url = webView.url?.absoluteString, isWebURL(url) else { return }
        addressField.stringValue = url
    }

    /// Opens what was typed: a web address if it looks like one, otherwise a web search.
    @objc func addressEntered() {
        let text = addressField.stringValue.trimmingCharacters(in: .whitespacesAndNewlines)
        if text.isEmpty { return }
        var target = text.contains(" ") ? nil : normalizedURL(text)?.absoluteString
        if target == nil {
            var c = URLComponents(string: "https://duckduckgo.com/")!
            c.queryItems = [URLQueryItem(name: "q", value: text)]
            target = c.url?.absoluteString
        }
        window.makeFirstResponder(webView)
        if let target = target { openStored(target) }
    }

    // MARK: History & favourites

    func isWebURL(_ s: String) -> Bool { s.hasPrefix("http://") || s.hasPrefix("https://") }

    func openStored(_ string: String) {
        guard isWebURL(string), let url = URL(string: string) else { return }
        internalShowing = false
        lockedSites = [siteKey(url.host ?? "")]
        appInitiatedLoad = true
        webView.load(URLRequest(url: url))
    }

    @objc func openStoredItem(_ sender: NSMenuItem) {
        if let s = sender.representedObject as? String { openStored(s) }
    }

    @objc func toggleFavourite() {
        guard let url = webView.url?.absoluteString, isWebURL(url) else { return }
        if PageStore.contains(PageStore.favouritesKey, url: url) {
            PageStore.remove(PageStore.favouritesKey, url: url)
        } else {
            PageStore.putFirst(PageStore.favouritesKey, url: url, title: webView.title ?? "", limit: Int.max)
            fetchIcon(for: url)
        }
        updateStar()
    }

    @objc func removeFavouriteItem(_ sender: NSMenuItem) {
        if let s = sender.representedObject as? String { PageStore.remove(PageStore.favouritesKey, url: s) }
        updateStar()
    }

    @objc func clearHistory() { PageStore.save(PageStore.historyKey, []) }

    @objc func deleteSiteHistory(_ sender: NSMenuItem) {
        guard let site = sender.representedObject as? String else { return }
        PageStore.save(PageStore.historyKey, PageStore.load(PageStore.historyKey).filter {
            siteKey(URL(string: $0["url"] ?? "")?.host ?? "") != site
        })
    }

    func recordHistory() {
        guard let url = webView.url, isWebURL(url.absoluteString) else { return }
        switch Prefs.historyMode {
        case "off":
            return
        case "sites":
            // Keep one entry per website, named after the site.
            guard let host = url.host, let scheme = url.scheme else { return }
            PageStore.putFirst(PageStore.historyKey, url: "\(scheme)://\(host)/", title: siteName(host), limit: 300)
        default:
            PageStore.putFirst(PageStore.historyKey, url: url.absoluteString, title: webView.title ?? "", limit: 300)
        }
    }

    // MARK: Favourites page

    /// Everything the built-in pages need, as JSON. page may be nil to keep the current tab.
    func pageData(_ page: String?) -> String {
        var data: [String: Any] = [
            "favourites": PageStore.load(PageStore.favouritesKey),
            "history": PageStore.load(PageStore.historyKey),
            "historyMode": Prefs.historyMode,
            "grouped": Prefs.d.object(forKey: "historyGrouped") as? Bool ?? true,
            "platform": "mac",
            "version": Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "",
        ]
        if let page = page { data["page"] = page }
        let json = (try? JSONSerialization.data(withJSONObject: data))
            .flatMap { String(data: $0, encoding: .utf8) } ?? "{}"
        // Safe to embed inside a <script> block.
        return json.replacingOccurrences(of: "</", with: "<\\/")
            .replacingOccurrences(of: "\u{2028}", with: "\\u2028")
            .replacingOccurrences(of: "\u{2029}", with: "\\u2029")
    }

    /// page: "favourites", "history" or "about".
    func showInternal(_ page: String) {
        internalShowing = true
        lastInternalPage = page
        addressField.stringValue = ""
        let html = bundledText("app-pages", "html").replacingOccurrences(of: "/*CP_DATA*/null", with: pageData(page))
        webView.loadHTMLString(html, baseURL: nil)
        updateStar()
    }

    /// Redraws the built-in page in place after its data changed.
    func pushInternal() {
        webView.evaluateJavaScript("window.cpSet && window.cpSet(\(pageData(nil)))", completionHandler: nil)
    }

    @objc func showFavouritesPage() { showInternal("favourites") }
    @objc func showHistoryPage() { showInternal("history") }
    @objc func showAboutPage() { showInternal("about") }

    /// Commands sent by the built-in pages as clearpane:// links.
    func handleInternalAction(_ url: URL) {
        let items = URLComponents(url: url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        func value(_ name: String) -> String? { items.first { $0.name == name }?.value }
        switch url.host ?? "" {
        case "remove-favourite":
            if let target = value("u") { PageStore.remove(PageStore.favouritesKey, url: target) }
            pushInternal()
        case "delete-history":
            let drop = Set((value("i") ?? "").split(separator: ",").compactMap { Int($0) })
            let kept = PageStore.load(PageStore.historyKey).enumerated()
                .filter { !drop.contains($0.offset) }.map { $0.element }
            PageStore.save(PageStore.historyKey, kept)
            pushInternal()
        case "set":
            if let grouped = value("grouped") { Prefs.d.set(grouped == "1", forKey: "historyGrouped") }
        default:
            break
        }
    }

    /// Fetches an icon for a favourite that was saved without one, the next time it is visited.
    func backfillIcon() {
        guard let url = webView.url?.absoluteString, isWebURL(url) else { return }
        let match = PageStore.load(PageStore.favouritesKey).first { $0["url"] == url }
        if let match = match, (match["icon"] ?? "").isEmpty { fetchIcon(for: url) }
    }

    /// Finds the page's own icon, downloads it, and stores it with the favourite.
    func fetchIcon(for pageURL: String) {
        let script = bundledText("icon-finder", "js")
        guard !script.isEmpty else { return }
        webView.evaluateJavaScript(script) { [weak self] result, _ in
            guard let self = self, let found = result as? String, self.isWebURL(found),
                  let iconURL = URL(string: found) else { return }
            var request = URLRequest(url: iconURL)
            request.timeoutInterval = 8
            URLSession.shared.dataTask(with: request) { data, _, _ in
                guard let data = data, data.count < 2_000_000, let image = NSImage(data: data),
                      let png = iconPNG(image, side: 128) else { return }
                DispatchQueue.main.async {
                    PageStore.setIcon(PageStore.favouritesKey, url: pageURL, base64: png.base64EncodedString())
                    if self.internalShowing { self.pushInternal() }
                }
            }.resume()
        }
    }

    func currentIsFavourite() -> Bool {
        guard let url = webView?.url?.absoluteString else { return false }
        return PageStore.contains(PageStore.favouritesKey, url: url)
    }

    func updateStar() {
        guard starButton != nil else { return }
        let on = currentIsFavourite()
        starButton.image = NSImage(systemSymbolName: on ? "star.fill" : "star", accessibilityDescription: "Favourite")
        starButton.toolTip = on ? "Remove from Favourites" : "Add to Favourites"
    }

    func pageItem(_ page: [String: String], action: Selector) -> NSMenuItem {
        let url = page["url"] ?? ""
        var title = page["title"] ?? ""
        if title.isEmpty { title = url }
        if title.count > 70 { title = String(title.prefix(70)) + "…" }
        let item = NSMenuItem(title: title, action: action, keyEquivalent: "")
        item.target = self
        item.representedObject = url
        item.toolTip = url
        return item
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()
        if menu === favouritesMenu {
            let toggle = NSMenuItem(title: currentIsFavourite() ? "Remove This Page from Favourites" : "Add This Page to Favourites",
                                    action: #selector(toggleFavourite), keyEquivalent: "d")
            toggle.target = self
            menu.addItem(toggle)
            let showPage = NSMenuItem(title: "Show Favourites", action: #selector(showFavouritesPage), keyEquivalent: "B")
            showPage.target = self
            menu.addItem(showPage)
            let favs = PageStore.load(PageStore.favouritesKey)
            guard !favs.isEmpty else { return }
            menu.addItem(.separator())
            for page in favs { menu.addItem(pageItem(page, action: #selector(openStoredItem(_:)))) }
            menu.addItem(.separator())
            let removeItem = NSMenuItem(title: "Remove Favourite", action: nil, keyEquivalent: "")
            let removeMenu = NSMenu()
            for page in favs { removeMenu.addItem(pageItem(page, action: #selector(removeFavouriteItem(_:)))) }
            removeItem.submenu = removeMenu
            menu.addItem(removeItem)
        } else if menu === historyMenu {
            let showPage = NSMenuItem(title: "Show History", action: #selector(showHistoryPage), keyEquivalent: "y")
            showPage.target = self
            menu.addItem(showPage)
            menu.addItem(.separator())
            let history = PageStore.load(PageStore.historyKey)
            if history.isEmpty {
                menu.addItem(NSMenuItem(title: "No History Yet", action: nil, keyEquivalent: ""))
                return
            }
            // Grouped view: one submenu per website.
            var order: [String] = []
            var groups: [String: [[String: String]]] = [:]
            for page in history {
                let site = siteKey(URL(string: page["url"] ?? "")?.host ?? "")
                if groups[site] == nil { order.append(site) }
                groups[site, default: []].append(page)
            }
            let bySite = NSMenuItem(title: "By Site", action: nil, keyEquivalent: "")
            let bySiteMenu = NSMenu()
            for site in order {
                let pages = groups[site] ?? []
                let siteItem = NSMenuItem(title: "\(siteName(site))  ·  \(site)  (\(pages.count))", action: nil, keyEquivalent: "")
                let siteMenu = NSMenu()
                for page in pages.prefix(40) { siteMenu.addItem(pageItem(page, action: #selector(openStoredItem(_:)))) }
                siteMenu.addItem(.separator())
                let delete = NSMenuItem(title: "Delete History for \(site)", action: #selector(deleteSiteHistory(_:)), keyEquivalent: "")
                delete.target = self
                delete.representedObject = site
                siteMenu.addItem(delete)
                siteItem.submenu = siteMenu
                bySiteMenu.addItem(siteItem)
            }
            bySite.submenu = bySiteMenu
            menu.addItem(bySite)
            menu.addItem(.separator())
            menu.addItem(NSMenuItem(title: "All Pages", action: nil, keyEquivalent: ""))
            for page in history.prefix(60) { menu.addItem(pageItem(page, action: #selector(openStoredItem(_:)))) }
            menu.addItem(.separator())
            let clear = NSMenuItem(title: "Clear History", action: #selector(clearHistory), keyEquivalent: "")
            clear.target = self
            menu.addItem(clear)
        }
    }

    // MARK: Settings

    @objc func showSettings() {
        if settingsWindow == nil { buildSettings() }
        urlField.stringValue = Prefs.homeURL
        adsCheck.state = Prefs.blockAds ? .on : .off
        popupsCheck.state = Prefs.blockPopups ? .on : .off
        lockCheck.state = Prefs.lockToSite ? .on : .off
        addressCheck.state = Prefs.showAddressBar ? .on : .off
        youTubeCheck.state = Prefs.skipYouTubeAds ? .on : .off
        historyPopup.selectItem(at: historyModes.firstIndex(of: Prefs.historyMode) ?? 0)
        settingsWindow?.center()
        settingsWindow?.makeKeyAndOrderFront(nil)
    }

    func buildSettings() {
        let label = NSTextField(labelWithString: "Start page (opens every time the app starts)")
        urlField = NSTextField(string: "")
        urlField.placeholderString = "https://example.com"
        urlField.widthAnchor.constraint(equalToConstant: 380).isActive = true

        addressCheck = NSButton(checkboxWithTitle: "Show address bar at the top", target: nil, action: nil)
        youTubeCheck = NSButton(checkboxWithTitle: "Skip YouTube ads (best effort)", target: nil, action: nil)
        adsCheck = NSButton(checkboxWithTitle: "Block ads and trackers", target: nil, action: nil)
        popupsCheck = NSButton(checkboxWithTitle: "Block popups and new windows", target: nil, action: nil)
        lockCheck = NSButton(checkboxWithTitle: "Stay on this site (block redirects to other sites)", target: nil, action: nil)
        let hint = NSTextField(wrappingLabelWithString:
            "Turn off “Stay on this site” if sign-in sends you through another domain.")
        hint.textColor = .secondaryLabelColor
        hint.font = .systemFont(ofSize: 11)

        historyPopup = NSPopUpButton(frame: .zero, pullsDown: false)
        historyPopup.addItems(withTitles: ["Save every page", "Save only website names", "Don't save history"])
        let historyRow = NSStackView(views: [NSTextField(labelWithString: "History:"), historyPopup])
        historyRow.orientation = .horizontal

        let save = NSButton(title: "Save", target: self, action: #selector(saveSettings))
        save.keyEquivalent = "\r"
        let cancel = NSButton(title: "Cancel", target: self, action: #selector(closeSettings))
        cancel.keyEquivalent = "\u{1b}"
        let spacer = NSView()
        let buttons = NSStackView(views: [spacer, cancel, save])
        buttons.orientation = .horizontal

        let stack = NSStackView(views: [label, urlField, addressCheck, adsCheck, popupsCheck, lockCheck, youTubeCheck, hint, historyRow, buttons])
        stack.orientation = .vertical
        stack.alignment = .leading
        stack.spacing = 10
        stack.edgeInsets = NSEdgeInsets(top: 20, left: 20, bottom: 20, right: 20)
        buttons.widthAnchor.constraint(equalTo: urlField.widthAnchor).isActive = true

        let w = NSWindow(contentRect: NSRect(x: 0, y: 0, width: 420, height: 360),
                         styleMask: [.titled, .closable], backing: .buffered, defer: false)
        w.title = "Settings"
        w.contentView = stack
        w.isReleasedWhenClosed = false
        settingsWindow = w
    }

    @objc func closeSettings() { settingsWindow?.close() }

    @objc func saveSettings() {
        guard let url = normalizedURL(urlField.stringValue) else {
            let a = NSAlert()
            a.messageText = "That doesn't look like a web address"
            a.informativeText = "Enter something like https://example.com"
            a.beginSheetModal(for: settingsWindow!)
            return
        }
        Prefs.homeURL = url.absoluteString
        Prefs.blockAds = adsCheck.state == .on
        Prefs.blockPopups = popupsCheck.state == .on
        Prefs.lockToSite = lockCheck.state == .on
        Prefs.showAddressBar = addressCheck.state == .on
        Prefs.skipYouTubeAds = youTubeCheck.state == .on
        Prefs.historyMode = historyModes[max(0, historyPopup.indexOfSelectedItem)]
        applyAddressBar()
        settingsWindow?.close()
        applyRules()
        loadHome()
    }

    // MARK: Navigation policy

    /// Returns true if a top-level navigation to this URL should be allowed.
    func allowsTopLevel(_ url: URL) -> Bool {
        let scheme = (url.scheme ?? "").lowercased()
        if scheme == "about" { return true }
        guard scheme == "http" || scheme == "https" else { return false }
        if Prefs.blockAds && blocklist.isBlocked(url.host) { return false }
        if Prefs.lockToSite, let host = url.host, !lockedSites.contains(siteKey(host)) {
            // Redirects during a load the app itself started (Home / Settings) widen the lock.
            if appInitiatedLoad { lockedSites.insert(siteKey(host)); return true }
            return false
        }
        return true
    }

    func webView(_ webView: WKWebView, decidePolicyFor navigationAction: WKNavigationAction,
                 decisionHandler: @escaping (WKNavigationActionPolicy) -> Void) {
        guard navigationAction.targetFrame?.isMainFrame == true, let url = navigationAction.request.url else {
            decisionHandler(.allow)   // subframes: network rules handle these
            return
        }
        if url.scheme == "clearpane" {
            // Commands from the built-in pages; ignored anywhere else.
            decisionHandler(.cancel)
            if internalShowing { handleInternalAction(url) }
            return
        }
        if internalShowing, navigationAction.navigationType == .linkActivated, isWebURL(url.absoluteString) {
            decisionHandler(.cancel)
            openStored(url.absoluteString)
            return
        }
        if allowsTopLevel(url) {
            decisionHandler(.allow)
        } else {
            blockedCount += 1
            decisionHandler(.cancel)
        }
    }

    func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
        appInitiatedLoad = false
        if internalShowing {
            // Going Back to a built-in page gives an empty document; draw it again.
            webView.evaluateJavaScript("window.cpSet ? 1 : (document.body ? document.body.childElementCount : 0)") { [weak self] result, _ in
                guard let self = self, self.internalShowing else { return }
                if (result as? Int) == 0, let page = self.lastInternalPage { self.showInternal(page) } else { self.pushInternal() }
            }
        } else {
            recordHistory()
            backfillIcon()
        }
        updateStar()
        syncAddress()
    }

    func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
        // Built-in pages load as about:blank; this also catches Back returning to one.
        internalShowing = !isWebURL(webView.url?.absoluteString ?? "")
        updateStar()
        syncAddress()
    }
    func webView(_ webView: WKWebView, didFail navigation: WKNavigation!, withError error: Error) { appInitiatedLoad = false }

    func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
        appInitiatedLoad = false
        let e = error as NSError
        if e.code == NSURLErrorCancelled || e.code == 102 { return }   // 102 = policy cancelled (we blocked it)
        let msg = e.localizedDescription
            .replacingOccurrences(of: "&", with: "&amp;").replacingOccurrences(of: "<", with: "&lt;")
        webView.loadHTMLString("""
            <html><body style="font:15px -apple-system;color:#888;display:flex;height:90vh;\
            align-items:center;justify-content:center;text-align:center">
            <div><h2 style="color:#555">Couldn't load the page</h2><p>\(msg)</p>
            <p>Check the address in Settings (⌘,).</p></div></body></html>
            """, baseURL: nil)
    }

    // MARK: Popups & JS dialogs

    func webView(_ webView: WKWebView, createWebViewWith configuration: WKWebViewConfiguration,
                 for navigationAction: WKNavigationAction, windowFeatures: WKWindowFeatures) -> WKWebView? {
        // Never open a second window. A real link click (target=_blank) to an allowed
        // page opens in place; script-opened windows are dropped when popup blocking is on.
        let isClick = navigationAction.navigationType == .linkActivated
        if let url = navigationAction.request.url, (isClick || !Prefs.blockPopups), allowsTopLevel(url) {
            webView.load(URLRequest(url: url))
        } else {
            blockedCount += 1
        }
        return nil
    }

    func webView(_ webView: WKWebView, runJavaScriptAlertPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping () -> Void) {
        let a = NSAlert()
        a.messageText = message
        a.beginSheetModal(for: window) { _ in completionHandler() }
    }

    func webView(_ webView: WKWebView, runJavaScriptConfirmPanelWithMessage message: String,
                 initiatedByFrame frame: WKFrameInfo, completionHandler: @escaping (Bool) -> Void) {
        let a = NSAlert()
        a.messageText = message
        a.addButton(withTitle: "OK")
        a.addButton(withTitle: "Cancel")
        a.beginSheetModal(for: window) { completionHandler($0 == .alertFirstButtonReturn) }
    }
}

let app = NSApplication.shared
let delegate = AppDelegate()
app.delegate = delegate
app.setActivationPolicy(.regular)
app.run()
