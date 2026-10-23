package br.com.ubetec.tecnico;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.Window;

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
 *  - abrirAjustesAvisos(): abre os ajustes de notificação do UBETEC no Android.
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
}
