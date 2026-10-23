package br.com.ubetec.tecnico;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Window;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * Ponte do app UBETEC com a plataforma (window.Capacitor.Plugins.UbetecApp):
 *  - bordas(): altura da barra de status/gestos em pixels de CSS;
 *  - barras({escuroTopo, escuroBase}): ícones claros ou escuros nas barras, conforme o tema da tela;
 *  - info(): versão do app e se o Firebase (avisos com o app fechado) está incluído nesta versão;
 *  - abrirAjustesAvisos(): abre os ajustes de notificação do UBETEC no Android;
 *  - atualizar({url}): v1.4 — baixa a versão nova do app (evento "atualizacao" com o progresso) e abre o
 *    instalador do Android. Se o Android ainda não deixa o UBETEC instalar apps, abre o ajuste e devolve
 *    {permissao:false}. Só aceita endereços https do GitHub (onde as versões são publicadas).
 */
@CapacitorPlugin(name = "UbetecApp")
public class UbetecAppPlugin extends Plugin {

    @PluginMethod
    public void bordas(PluginCall call) {
        call.resolve(MainActivity.bordasAtuais());
    }

    @PluginMethod
    public void barras(PluginCall call) {
        final boolean escuroTopo = Boolean.TRUE.equals(call.getBoolean("escuroTopo", false));
        final boolean escuroBase = Boolean.TRUE.equals(call.getBoolean("escuroBase", false));
        final Activity a = getActivity();
        if (a == null) { call.resolve(); return; }
        a.runOnUiThread(() -> {
            try {
                Window w = a.getWindow();
                WindowInsetsControllerCompat c = WindowCompat.getInsetsController(w, w.getDecorView());
                c.setAppearanceLightStatusBars(!escuroTopo);
                c.setAppearanceLightNavigationBars(!escuroBase);
            } catch (Exception ignorado) { }
            call.resolve();
        });
    }

    @PluginMethod
    public void info(PluginCall call) {
        JSObject r = new JSObject();
        try {
            PackageInfo p = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0);
            r.put("versao", p.versionName);
            r.put("codigo", Build.VERSION.SDK_INT >= 28 ? p.getLongVersionCode() : p.versionCode);
        } catch (Exception e) {
            r.put("versao", "");
        }
        // o plugin do Google gera o recurso "google_app_id" quando o google-services.json entra no app
        int id = getContext().getResources().getIdentifier("google_app_id", "string", getContext().getPackageName());
        r.put("firebase", id != 0);
        r.put("android", Build.VERSION.SDK_INT);
        r.put("atualizador", true);   // v1.4: sabe baixar e instalar a versão nova
        r.put("instalarPermitido", Build.VERSION.SDK_INT < 26 || getContext().getPackageManager().canRequestPackageInstalls());
        call.resolve(r);
    }

    @PluginMethod
    public void abrirAjustesAvisos(PluginCall call) {
        try {
            Intent i;
            if (Build.VERSION.SDK_INT >= 26) {
                i = new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS);
                i.putExtra(Settings.EXTRA_APP_PACKAGE, getContext().getPackageName());
            } else {
                i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getContext().getPackageName()));
            }
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
            call.resolve();
        } catch (Exception e) {
            call.reject("Não foi possível abrir os ajustes.");
        }
    }

    private volatile boolean baixando = false;

    @PluginMethod
    public void atualizar(final PluginCall call) {
        final String url = call.getString("url", "");
        if (url == null || !url.matches("^https://(github\\.com|objects\\.githubusercontent\\.com|release-assets\\.githubusercontent\\.com)/.+\\.apk$")) {
            call.reject("Endereço da atualização inválido.");
            return;
        }
        if (Build.VERSION.SDK_INT >= 26 && !getContext().getPackageManager().canRequestPackageInstalls()) {
            try {
                Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + getContext().getPackageName()));
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
            } catch (Exception ignorado) { }
            JSObject r = new JSObject();
            r.put("permissao", false);
            call.resolve(r);
            return;
        }
        if (baixando) { call.reject("A atualização já está sendo baixada."); return; }
        baixando = true;
        new Thread(() -> {
            HttpURLConnection con = null;
            try {
                File pasta = new File(getContext().getCacheDir(), "atualizacao");
                if (!pasta.exists()) pasta.mkdirs();
                File apk = new File(pasta, "UBETEC.apk");
                if (apk.exists()) apk.delete();
                URL u = new URL(url);
                int saltos = 0;
                while (true) {
                    con = (HttpURLConnection) u.openConnection();
                    con.setInstanceFollowRedirects(false);
                    con.setConnectTimeout(15000);
                    con.setReadTimeout(30000);
                    int st = con.getResponseCode();
                    if (st >= 300 && st < 400 && saltos < 6) {
                        String loc = con.getHeaderField("Location");
                        con.disconnect();
                        u = new URL(u, loc);
                        if (!"https".equals(u.getProtocol())) throw new Exception("Redirecionamento inseguro.");
                        saltos++;
                        continue;
                    }
                    if (st != 200) throw new Exception("O servidor respondeu " + st + ".");
                    break;
                }
                long total = con.getContentLengthLong();
                long feito = 0;
                int ultimo = -1;
                try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(apk)) {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        feito += n;
                        int pct = total > 0 ? (int) (feito * 100 / total) : -1;
                        if (pct != ultimo) {
                            ultimo = pct;
                            JSObject ev = new JSObject();
                            ev.put("progresso", pct);
                            ev.put("baixado", feito);
                            ev.put("total", total);
                            notifyListeners("atualizacao", ev);
                        }
                    }
                }
                if (total > 0 && feito != total) throw new Exception("Download incompleto.");
                Uri uri = FileProvider.getUriForFile(getContext(), getContext().getPackageName() + ".fileprovider", apk);
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(uri, "application/vnd.android.package-archive");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
                getContext().startActivity(i);
                JSObject r = new JSObject();
                r.put("permissao", true);
                r.put("instalador", true);
                call.resolve(r);
            } catch (Exception e) {
                call.reject("Não foi possível baixar a atualização: " + e.getMessage());
            } finally {
                if (con != null) con.disconnect();
                baixando = false;
            }
        }).start();
    }
}
