package controllers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.util.Locale;

import static utils.GsonHolder.GSON;
import static utils.GsonHolder.SCRIPT_SAFE;

/**
 * Makes every hosted app an installable PWA without touching its files: the
 * manifest is synthesized from {@code app.json} and the {@code <link rel="manifest">}
 * is injected as {@code index.html} is served, so apps the {@code app_install}
 * tool writes later are covered too. No service worker — Chrome's installability
 * check passes without one, and caching one would fight the {@code no-cache} policy.
 */
public final class AppPwa {

    private AppPwa() {}

    public static final String MANIFEST_FILE = "manifest.webmanifest";
    public static final String MANIFEST_CONTENT_TYPE = "application/manifest+json";

    public static String manifestJson(ApiAppsController.AppEntry app) {
        var m = new JsonObject();
        m.addProperty("id", app.url());
        m.addProperty("name", app.name());
        m.addProperty("short_name", app.name());
        if (app.description() != null) m.addProperty("description", app.description());
        m.addProperty("start_url", app.url());
        m.addProperty("scope", app.url());
        m.addProperty("display", "standalone");
        var icons = new JsonArray();
        if (app.icon() != null) {
            var icon = new JsonObject();
            icon.addProperty("src", app.icon());
            // Chrome accepts an SVG at sizes "any" as its install icon (verified on 153);
            // a raster icon's size is unknown here, so Chrome measures it itself.
            if (app.icon().toLowerCase(Locale.ROOT).endsWith(".svg")) {
                icon.addProperty("sizes", "any");
                icon.addProperty("type", "image/svg+xml");
            }
            icons.add(icon);
        }
        m.add("icons", icons);
        return GSON.toJson(m);
    }

    /** {@code html} with the manifest link (unless the app ships its own) and, when
     *  {@code installPrompt}, the install banner, placed before {@code </head>}. */
    public static String inject(String html, ApiAppsController.AppEntry app, boolean installPrompt) {
        var tags = new StringBuilder();
        if (!html.toLowerCase(Locale.ROOT).contains("rel=\"manifest\"")) {
            tags.append("<link rel=\"manifest\" href=\"").append(app.url()).append(MANIFEST_FILE).append("\">");
        }
        if (installPrompt) {
            tags.append("<script>").append(INSTALL_SCRIPT.replace("__NAME__", SCRIPT_SAFE.toJson(app.name()))) // "</script>" in a name stays escaped
                    .append("</script>");
        }
        if (tags.isEmpty()) return html;
        int head = html.toLowerCase(Locale.ROOT).indexOf("</head>");
        return head < 0 ? tags + html : html.substring(0, head) + tags + html.substring(head);
    }

    // beforeinstallprompt fires only on the app's own page, so the Apps listing links
    // here with ?install=1 and this banner spends the prompt on the user's click.
    private static final String INSTALL_SCRIPT = """
            (() => {
              if (matchMedia('(display-mode: standalone)').matches) return;
              const name = __NAME__;
              let deferred = null;
              const host = document.createElement('div');
              const root = host.attachShadow({ mode: 'open' });
              root.innerHTML = '<style>:host{all:initial}div{position:fixed;right:16px;bottom:16px;z-index:2147483647;'
                + 'display:flex;gap:10px;align-items:center;max-width:min(360px,calc(100vw - 32px));padding:10px 12px;'
                + 'border-radius:10px;background:#111827;color:#f9fafb;font:13px/1.4 system-ui,sans-serif;'
                + 'box-shadow:0 8px 24px rgba(0,0,0,.3)}button{font:inherit;border:0;border-radius:6px;cursor:pointer}'
                + '#go{padding:6px 10px;background:#047857;color:#fff}#x{background:none;color:#9ca3af;font-size:16px}</style>'
                + '<div role="status"><span id="msg"></span><button id="go" hidden>Install</button>'
                + '<button id="x" aria-label="Dismiss">\\u00d7</button></div>';
              const msg = root.getElementById('msg'), go = root.getElementById('go');
              const show = (text, withButton) => {
                msg.textContent = text;
                go.hidden = !withButton;
                if (!host.isConnected) (document.body || document.documentElement).appendChild(host);
              };
              root.getElementById('x').onclick = () => host.remove();
              go.onclick = async () => { if (deferred) { deferred.prompt(); await deferred.userChoice; host.remove(); } };
              addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); deferred = e; show('Install ' + name + ' as a desktop app.', true); });
              addEventListener('appinstalled', () => host.remove());
              setTimeout(() => {
                if (deferred || host.isConnected) return;
                show(isSecureContext
                  ? 'To install ' + name + ', use your browser menu: Install app (Chrome, Edge) or File \\u2192 Add to Dock (Safari).'
                  : 'Installing needs a secure connection: open JClaw at its https:// address.', false);
              }, 1500);
            })();
            """;
}
