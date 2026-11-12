package br.com.ubetec.tecnico;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;

import androidx.activity.EdgeToEdge;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

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
 *
 * v1.6: o app fica ativo em segundo plano o tempo todo (AtivoService, notificação discreta "UBETEC ativo") e,
 * na primeira vez, pede ao Android para não restringir a bateria do UBETEC (senão Xiaomi/Samsung/Motorola
 * fecham o app e os avisos, a conversa e o GPS do trabalho param).
 *
 * v1.8: (1) sem a faixa branca com "UBETEC" no topo — a janela era criada ainda com o tema de abertura (com barra de
 * título e o fundo da splash), porque a tela de ponta a ponta era ligada antes de trocar o tema; agora o tema certo
 * entra primeiro e nenhum tema do app tem barra de título. (2) A tela se ajusta a cada celular: o próprio Android
 * mede a barra de status, o recorte da câmera (notch/furo), as bordas curvas e a barra de gestos, e a plataforma
 * fica DENTRO dessa área — nada fica por baixo da câmera, do relógio ou dos gestos, em qualquer modelo. As faixas
 * de fora são pintadas com a cor do topo e da base da tela que está aberta (lida da página), então o visual continua
 * de ponta a ponta. A plataforma recebe bordas 0 (não soma de novo).
 *
 * v1.9: botão flutuante (BolhaService) — ao sair do app, uma bolinha do UBETEC fica por cima do Waze/Google Maps e
 * volta para o app num toque; some quando o app volta para a frente. Também declara as câmeras de foto e de vídeo
 * (<queries> no manifesto) para o Android 11+ abrir a câmera do celular, não a galeria.
 */
public class MainActivity extends BridgeActivity {
    private static final int PEDIDO_NOTIFICACAO = 4101;
    private static volatile JSObject bordas = new JSObject();
    private static volatile MainActivity atual;
    private int corTopo = 0xFF0A1220, corBase = 0xFF0A1220;   // azul-marinho da plataforma até a página informar
    private int faixaTopo = 0, faixaBase = 0;                  // altura (px) das faixas fora da área segura
    private final Handler relogio = new Handler(Looper.getMainLooper());
    private boolean ativa = false;

    /** Última medida das bordas seguras, em pixels de CSS (lida pelo plugin UbetecApp). */
    static JSObject bordasAtuais() { return bordas; }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(UbetecAppPlugin.class);
        // v1.8: o tema sem barra de título entra ANTES de a janela existir (EdgeToEdge cria a janela com o tema atual)
        setTheme(R.style.AppTheme_NoActionBar);
        EdgeToEdge.enable(this);
        super.onCreate(savedInstanceState);
        atual = this;
        if (getSupportActionBar() != null) getSupportActionBar().hide();

        // Android 13+: as notificações (avisos e "localização ao vivo") precisam desta permissão
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{ Manifest.permission.POST_NOTIFICATIONS }, PEDIDO_NOTIFICACAO);
        } else {
            getWindow().getDecorView().postDelayed(this::pedirBateriaUmaVez, 1200);
        }
        // v1.6: UBETEC ativo em segundo plano
        AtivoService.iniciar(this);

        final WebView web = getBridge().getWebView();
        web.setBackgroundColor(corBase);
        ViewCompat.setOnApplyWindowInsetsListener(web, (v, insets) -> {
            // tudo o que pode cobrir a tela neste celular: barra de status, recorte da câmera, bordas e barra de gestos
            Insets seguro = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets teclado = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean comTeclado = insets.isVisible(WindowInsetsCompat.Type.ime()) && teclado.bottom > 0;
            int baixo = comTeclado ? Math.max(teclado.bottom, seguro.bottom) : seguro.bottom;

            ViewGroup.LayoutParams lp = v.getLayoutParams();
            if (lp instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams m = (ViewGroup.MarginLayoutParams) lp;
                if (m.topMargin != seguro.top || m.bottomMargin != baixo || m.leftMargin != seguro.left || m.rightMargin != seguro.right) {
                    m.topMargin = seguro.top; m.bottomMargin = baixo; m.leftMargin = seguro.left; m.rightMargin = seguro.right;
                    v.setLayoutParams(m);
                }
            }
            faixaTopo = seguro.top; faixaBase = baixo;
            pintarFaixas();

            // a página já está dentro da área segura: não precisa somar nada
            JSObject b = new JSObject();
            b.put("top", 0); b.put("bottom", 0); b.put("left", 0); b.put("right", 0);
            b.put("nativo", true);
            bordas = b;
            injetarBordas(web);
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(web);

        getBridge().addWebViewListener(new WebViewListener() {
            @Override
            public void onPageLoaded(WebView webView) { injetarBordas(webView); lerCoresDaPagina(); }
        });
    }

    @Override
    public void onStart() { super.onStart(); BolhaService.aoVoltarAoApp(this); }   // v1.9: de volta ao app, a bolinha some

    @Override
    public void onStop() {
        // v1.9: saiu do app (Waze, Google Maps, WhatsApp…): mostra o botão flutuante, se a pessoa ligou
        if (!isChangingConfigurations()) BolhaService.aoSairDoApp(this);
        super.onStop();
    }

    @Override
    public void onResume() {
        super.onResume();
        BolhaService.ignorarProximaSaida = false;   // v1.9: a tela do próprio app (ajuste/diálogo) já fechou
        ativa = true; relogio.removeCallbacks(lerCores); relogio.postDelayed(lerCores, 600);
    }

    @Override
    public void onPause() { ativa = false; relogio.removeCallbacks(lerCores); super.onPause(); }

    // ---------- v1.8: faixas fora da área segura com a cor da tela aberta ----------
    /** Chamado pelo plugin (barras) quando a plataforma informa as cores do topo e da base. */
    static void cores(final Integer topo, final Integer base) {
        final MainActivity a = atual;
        if (a == null) return;
        a.runOnUiThread(() -> {
            if (topo != null) a.corTopo = topo;
            if (base != null) a.corBase = base;
            a.pintarFaixas();
        });
    }

    private void pintarFaixas() {
        try {
            WebView web = getBridge() != null ? getBridge().getWebView() : null;
            if (web == null) return;
            View pai = (View) web.getParent();
            ColorDrawable cimaD = new ColorDrawable(corTopo), baixoD = new ColorDrawable(corBase);
            LayerDrawable fundo = new LayerDrawable(new Drawable[]{ new ColorDrawable(corBase), cimaD, baixoD });
            fundo.setLayerGravity(1, Gravity.TOP | Gravity.FILL_HORIZONTAL);
            fundo.setLayerHeight(1, Math.max(1, faixaTopo));
            fundo.setLayerGravity(2, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
            fundo.setLayerHeight(2, Math.max(1, faixaBase));
            if (pai != null) pai.setBackground(fundo);
            getWindow().getDecorView().setBackgroundColor(corBase);
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
            c.setAppearanceLightStatusBars(!escura(corTopo));
            c.setAppearanceLightNavigationBars(!escura(corBase));
        } catch (Exception ignorado) { }
    }

    private static boolean escura(int cor) {
        double l = (0.2126 * Color.red(cor) + 0.7152 * Color.green(cor) + 0.0722 * Color.blue(cor)) / 255.0;
        return l < 0.55;
    }

    /** Lê da página a cor do topo e da base (funciona mesmo se a ponte do Capacitor não estiver na página). */
    private final Runnable lerCores = new Runnable() {
        @Override public void run() {
            if (!ativa) return;
            lerCoresDaPagina();
            relogio.postDelayed(this, 1500);
        }
    };

    private void lerCoresDaPagina() {
        try {
            final WebView web = getBridge().getWebView();
            final String js = "(function(){try{function cor(el){for(var n=el;n&&n.nodeType===1;n=n.parentElement){var c=getComputedStyle(n).backgroundColor;"
                + "var m=/rgba?\\(([\\d.]+)[,\\s]+([\\d.]+)[,\\s]+([\\d.]+)(?:[,\\s/]+([\\d.]+))?/.exec(c||'');if(m&&(m[4]===undefined||+m[4]>0.5))return [+m[1],+m[2],+m[3]];}"
                + "var b=getComputedStyle(document.body).backgroundColor;var k=/(\\d+)[,\\s]+(\\d+)[,\\s]+(\\d+)/.exec(b||'');return k?[+k[1],+k[2],+k[3]]:[10,18,32];}"
                + "var w=innerWidth/2;var t=cor(document.elementFromPoint(w,1)||document.body);var b=cor(document.elementFromPoint(w,innerHeight-2)||document.body);"
                + "return t.concat(b).join(',');}catch(e){return '';}})()";
            web.evaluateJavascript(js, valor -> {
                try {
                    String v = valor == null ? "" : valor.replace("\"", "");
                    String[] p = v.split(",");
                    if (p.length != 6) return;
                    int t = Color.rgb((int) Double.parseDouble(p[0]), (int) Double.parseDouble(p[1]), (int) Double.parseDouble(p[2]));
                    int b = Color.rgb((int) Double.parseDouble(p[3]), (int) Double.parseDouble(p[4]), (int) Double.parseDouble(p[5]));
                    if (t != corTopo || b != corBase) { corTopo = t; corBase = b; pintarFaixas(); }
                } catch (Exception ignorado) { }
            });
        } catch (Exception ignorado) { }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PEDIDO_NOTIFICACAO) {
            AtivoService.iniciar(this);   // com a permissão, a notificação "UBETEC ativo" aparece
            getWindow().getDecorView().postDelayed(this::pedirBateriaUmaVez, 600);
        }
    }

    /** v1.6: pede uma vez para o Android não restringir a bateria do UBETEC (sem isso, o app é fechado em segundo plano). */
    private void pedirBateriaUmaVez() {
        try {
            if (Build.VERSION.SDK_INT < 23 || bateriaLivre(this)) return;
            if (AtivoService.prefs(this).getBoolean("pediu_bateria", false)) return;
            AtivoService.prefs(this).edit().putBoolean("pediu_bateria", true).apply();
            pedirBateria(this);
        } catch (Exception ignorado) { }
    }

    static boolean bateriaLivre(android.content.Context c) {
        if (Build.VERSION.SDK_INT < 23) return true;
        PowerManager pm = (PowerManager) c.getSystemService(POWER_SERVICE);
        return pm != null && pm.isIgnoringBatteryOptimizations(c.getPackageName());
    }

    static void pedirBateria(android.content.Context c) {
        BolhaService.ignorarProximaSaida = true;   // é uma tela do próprio app: sem bolinha
        try {
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + c.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            try {
                Intent i = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                c.startActivity(i);
            } catch (Exception ignorado) { }
        }
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
