package br.com.ubetec.tecnico;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.activity.EdgeToEdge;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.JSObject;
import com.getcapacitor.WebViewListener;

import java.util.Locale;

/**
 * UBETEC — abre a plataforma (server.url em capacitor.config.json).
 *
 * v1.3: tela de ponta a ponta (padrão do Android 15). A plataforma pinta o fundo por baixo da barra de
 * status e da barra de gestos, e recebe a altura delas em CSS (--ub-sa-top/bottom/left/right) para o
 * topo e a barra inferior não ficarem por baixo do relógio, da bateria ou dos gestos. Com o teclado
 * aberto, a WebView encolhe para o campo digitado continuar visível.
 * O GPS em segundo plano é feito pelo plugin BackgroundGeolocation, ligado pela plataforma só durante
 * o expediente ou o deslocamento/atendimento de uma O.S. Os avisos com o app fechado chegam pelo Firebase.
 */
public class MainActivity extends BridgeActivity {
    private static final int PEDIDO_NOTIFICACAO = 4101;
    private static volatile JSObject bordas = new JSObject();

    /** Última medida das bordas seguras, em pixels de CSS (lida pelo plugin UbetecApp). */
    static JSObject bordasAtuais() { return bordas; }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(UbetecAppPlugin.class);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);

        // Android 13+: as notificações (avisos e "localização ao vivo") precisam desta permissão
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{ Manifest.permission.POST_NOTIFICATIONS }, PEDIDO_NOTIFICACAO);
        }

        final WebView web = getBridge().getWebView();
        ViewCompat.setOnApplyWindowInsetsListener(web, (v, insets) -> {
            Insets barras = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets teclado = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean comTeclado = insets.isVisible(WindowInsetsCompat.Type.ime()) && teclado.bottom > 0;

            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                int baixo = comTeclado ? teclado.bottom : 0;
                if (m.bottomMargin != baixo) { m.bottomMargin = baixo; v.setLayoutParams(m); }
            }

            float d = getResources().getDisplayMetrics().density;
            JSObject b = new JSObject();
            b.put("top", barras.top / d);
            b.put("bottom", comTeclado ? 0 : barras.bottom / d);
            b.put("left", barras.left / d);
            b.put("right", barras.right / d);
            bordas = b;
            injetarBordas(web);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(web);

        getBridge().addWebViewListener(new WebViewListener() {
            @Override
            public void onPageLoaded(WebView webView) { injetarBordas(webView); }
        });
    }

    private void injetarBordas(final WebView web) {
        final JSObject b = bordas;
        final String js = String.format(Locale.US,
                "(function(b){try{var r=document.documentElement;if(!r)return;" +
                "for(var k in b){r.style.setProperty('--ub-sa-'+k,b[k]+'px');}" +
                "r.classList.add('ub-bordas-app');window.__ubBordas=b;" +
                "window.dispatchEvent(new Event('ubetec-bordas'));}catch(e){}})" +
                "({top:%.1f,bottom:%.1f,left:%.1f,right:%.1f});",
                num(b, "top"), num(b, "bottom"), num(b, "left"), num(b, "right"));
        web.post(() -> web.evaluateJavascript(js, null));
    }

    private static double num(JSObject o, String k) {
        try { return o.has(k) ? o.getDouble(k) : 0; } catch (Exception e) { return 0; }
    }
}
