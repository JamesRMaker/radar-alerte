package fr.revisionsanglais;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Appli « Révisions d'anglais ».
 *
 * La page et les sons sont dans l'APK (marche hors connexion dès le premier lancement).
 * Quand il y a Internet, l'appli va chercher la dernière version de la page et les sons des
 * nouveaux mots sur GitHub (dossier anglais/site du dépôt) et les garde sur le téléphone :
 * les nouveaux mots arrivent sans réinstaller l'APK.
 */
public class MainActivity extends Activity {

    /** Adresse interne de l'appli (fixe : les progrès enregistrés restent attachés à elle). */
    static final String HOST = "revisions-anglais.app";
    static final String HOME = "https://" + HOST + "/index.html";
    /** Source en ligne des fichiers. */
    static final String REMOTE =
        "https://raw.githubusercontent.com/JamesRMaker/radar-alerte/main/anglais/site/";

    private WebView web;
    private File cacheDir;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        cacheDir = new File(getFilesDir(), "site");
        web = new WebView(this);
        web.setBackgroundColor(Color.rgb(0xE9, 0xED, 0xFA));
        setContentView(web);

        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);            // progrès (localStorage)
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setTextZoom(100);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (HOST.equalsIgnoreCase(u.getHost())) return false;
                try { startActivity(new Intent(Intent.ACTION_VIEW, u)); } catch (Exception ignored) { }
                return true;
            }

            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                Uri u = request.getUrl();
                if (!HOST.equalsIgnoreCase(u.getHost())) return null;   // polices Google, etc.
                return serve(u);
            }
        });

        if (savedInstanceState == null || web.restoreState(savedInstanceState) == null) {
            web.loadUrl(HOME);
        }
    }

    // ------------------------------------------------------------------------------------
    // Fichiers de l'appli : page = en ligne d'abord ; sons = copie gardée d'abord.
    // ------------------------------------------------------------------------------------

    private WebResourceResponse serve(Uri u) {
        String path = u.getPath() == null ? "" : u.getPath().replaceFirst("^/+", "");
        if (path.isEmpty()) path = "index.html";
        if (path.contains("..")) return notFound();
        String query = u.getQuery();
        // La clé de cache tient compte de « ?v=… » : un son refait change de version.
        String key = path + (query == null ? "" : "__" + query.replaceAll("[^A-Za-z0-9=._-]", "_"));
        File cached = new File(cacheDir, key);
        boolean page = path.endsWith(".html");

        byte[] data = null;
        if (page) {
            data = download(REMOTE + path, 4000);
            if (data != null) save(cached, data);
            else data = read(cached);
        } else {
            data = read(cached);
            if (data == null) {
                data = download(REMOTE + path, 8000);
                if (data != null) save(cached, data);
            }
        }
        if (data == null) data = asset(path);
        if (data == null) return notFound();
        return response(mime(path), data);
    }

    private static byte[] download(String url, int timeoutMs) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(timeoutMs);
            c.setReadTimeout(timeoutMs);
            c.setUseCaches(false);
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) {
                byte[] d = readAll(in);
                return d.length > 0 ? d : null;
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    private static void save(File f, byte[] data) {
        try {
            File dir = f.getParentFile();
            if (dir != null) dir.mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); }
            if (!tmp.renameTo(f)) tmp.delete();
        } catch (Exception ignored) { }
    }

    private static byte[] read(File f) {
        try { return f.isFile() ? Files.readAllBytes(f.toPath()) : null; }
        catch (Exception e) { return null; }
    }

    private byte[] asset(String path) {
        try (InputStream in = getAssets().open(path)) { return readAll(in); }
        catch (Exception e) { return null; }
    }

    private static byte[] readAll(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static String mime(String path) {
        if (path.endsWith(".html")) return "text/html";
        if (path.endsWith(".mp3")) return "audio/mpeg";
        if (path.endsWith(".js")) return "text/javascript";
        if (path.endsWith(".css")) return "text/css";
        if (path.endsWith(".json")) return "application/json";
        if (path.endsWith(".png")) return "image/png";
        if (path.endsWith(".svg")) return "image/svg+xml";
        return "application/octet-stream";
    }

    private static WebResourceResponse response(String mime, byte[] data) {
        Map<String, String> h = new HashMap<>();
        h.put("Cache-Control", "no-cache");
        h.put("Access-Control-Allow-Origin", "*");
        String enc = mime.startsWith("text/") || mime.equals("application/json") ? "utf-8" : null;
        return new WebResourceResponse(mime, enc, 200, "OK", h, new ByteArrayInputStream(data));
    }

    private static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found",
            new HashMap<>(), new ByteArrayInputStream(new byte[0]));
    }

    // ------------------------------------------------------------------------------------

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    public void onBackPressed() {
        if (web.canGoBack()) web.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }
}
