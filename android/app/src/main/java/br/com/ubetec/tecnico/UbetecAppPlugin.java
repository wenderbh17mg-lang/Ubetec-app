package br.com.ubetec.tecnico;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.content.Context;
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
 *  - v1.6: ativo() → {ligado, bateriaLivre}; sempreAtivo({ligado}) liga/desliga o "UBETEC ativo" em segundo plano;
 *    liberarBateria() abre o pedido do Android para não restringir a bateria do UBETEC.
 *  - v1.7: vibrar({padrao:[vibra, pausa, vibra…] em ms, forca:1–255}) vibra o celular com a intensidade escolhida
 *    nos "Sons e avisos" da plataforma (o navegador do app não vibrava: faltava a permissão VIBRATE).
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
        r.put("sempreAtivo", true);   // v1.6: sabe ficar ativo em segundo plano
        r.put("vibrar", true);        // v1.7: sabe vibrar com intensidade
        Vibrator vb = vibrador();
        r.put("temVibrador", vb != null && vb.hasVibrator());
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

    private Vibrator vibrador() {
        try {
            if (Build.VERSION.SDK_INT >= 31) {
                VibratorManager vm = (VibratorManager) getContext().getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
                return vm != null ? vm.getDefaultVibrator() : null;
            }
            return (Vibrator) getContext().getSystemService(Context.VIBRATOR_SERVICE);
        } catch (Exception e) { return null; }
    }

    @PluginMethod
    public void vibrar(PluginCall call) {
        Vibrator vb = vibrador();
        if (vb == null || !vb.hasVibrator()) { call.reject("Este celular não tem motor de vibração."); return; }
        long[] tempos;
        try {
            com.getcapacitor.JSArray arr = call.getArray("padrao");
            int n = arr == null ? 0 : Math.min(arr.length(), 20);
            if (n == 0) tempos = new long[] { 0, 200 };
            else {
                tempos = new long[n + 1];
                tempos[0] = 0;   // começa já
                for (int i = 0; i < n; i++) tempos[i + 1] = Math.max(10, Math.min(2000, arr.getLong(i)));
            }
        } catch (Exception e) { tempos = new long[] { 0, 200 }; }
        int forca = Math.max(1, Math.min(255, call.getInt("forca", 170)));
        try {
            if (Build.VERSION.SDK_INT >= 26) {
                int[] amps = new int[tempos.length];
                for (int i = 0; i < tempos.length; i++) amps[i] = (i % 2 == 1) ? (vb.hasAmplitudeControl() ? forca : VibrationEffect.DEFAULT_AMPLITUDE) : 0;
                vb.vibrate(VibrationEffect.createWaveform(tempos, amps, -1));
            } else {
                vb.vibrate(tempos, -1);
            }
            call.resolve();
        } catch (Exception e) {
            call.reject("Não foi possível vibrar.");
        }
    }

    @PluginMethod
    public void ativo(PluginCall call) {
        JSObject r = new JSObject();
        r.put("ligado", AtivoService.ligado(getContext()));
        r.put("bateriaLivre", MainActivity.bateriaLivre(getContext()));
        call.resolve(r);
    }

    @PluginMethod
    public void sempreAtivo(PluginCall call) {
        boolean v = !Boolean.FALSE.equals(call.getBoolean("ligado", true));
        AtivoService.definir(getContext(), v);
        JSObject r = new JSObject();
        r.put("ligado", v);
        call.resolve(r);
    }

    @PluginMethod
    public void liberarBateria(PluginCall call) {
        MainActivity.pedirBateria(getContext());
        call.resolve();
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
