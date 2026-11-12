package br.com.ubetec.tecnico;

import android.animation.ValueAnimator;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.getcapacitor.JSObject;

/**
 * v1.9 — Botão flutuante do UBETEC (igual ao do Uber/99).
 *
 * Quando o técnico sai do app (abre o Waze, o Google Maps, o WhatsApp…), aparece por cima das outras telas uma bolinha
 * com o ícone do UBETEC: um toque volta direto para o UBETEC. Dá para arrastar para qualquer altura (ela gruda na
 * borda da tela) e, arrastando até o "×" que aparece embaixo, ela some até a próxima vez que o técnico sair do app.
 * O ponto verde indica que há expediente/atividade em andamento.
 *
 * Só aparece se: a pessoa ligou o botão na plataforma (Meu perfil › Botão flutuante), o Android deu a permissão
 * "Sobrepor a outros apps", o técnico está com a sessão aberta e — no modo padrão — está em expediente, deslocamento
 * ou atendimento (modo "sempre": sempre que sair do app). A plataforma informa tudo isso pelo plugin UbetecApp.
 * Some sozinha quando o UBETEC volta para a frente.
 */
public class BolhaService extends Service {
    private static final String LIGADA = "bolha_ligada";
    private static final String MODO = "bolha_modo";            // "atividade" (padrão) ou "sempre"
    private static final String SESSAO = "bolha_sessao";        // técnico com a sessão aberta na plataforma
    private static final String ATIVIDADE = "bolha_atividade";  // expediente, deslocamento ou atendimento em andamento
    private static final String POS_Y = "bolha_y";
    private static final String LADO = "bolha_lado";            // "dir" ou "esq"

    /** Uma tela do próprio app vai abrir (ajustes do Android, instalador…): não mostrar a bolha nessa saída. */
    static volatile boolean ignorarProximaSaida = false;

    private static SharedPreferences prefs(Context c) { return AtivoService.prefs(c); }

    static boolean permitido(Context c) { return Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(c); }
    static boolean ligada(Context c) { return prefs(c).getBoolean(LIGADA, false); }
    static String modo(Context c) { return "sempre".equals(prefs(c).getString(MODO, "atividade")) ? "sempre" : "atividade"; }

    static boolean deveMostrar(Context c) {
        SharedPreferences p = prefs(c);
        if (!p.getBoolean(LIGADA, false) || !p.getBoolean(SESSAO, false) || !permitido(c)) return false;
        return "sempre".equals(modo(c)) || p.getBoolean(ATIVIDADE, false);
    }

    static JSObject estado(Context c) {
        SharedPreferences p = prefs(c);
        JSObject r = new JSObject();
        r.put("suportado", true);
        r.put("permissao", permitido(c));
        r.put("ligada", p.getBoolean(LIGADA, false));
        r.put("modo", modo(c));
        r.put("sessao", p.getBoolean(SESSAO, false));
        r.put("atividade", p.getBoolean(ATIVIDADE, false));
        return r;
    }

    static void configurar(Context c, Boolean ligada, String modo) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (ligada != null) e.putBoolean(LIGADA, ligada);
        if ("sempre".equals(modo) || "atividade".equals(modo)) e.putString(MODO, modo);
        e.apply();
    }

    static void sessao(Context c, Boolean ativa, Boolean atividade) {
        SharedPreferences.Editor e = prefs(c).edit();
        if (ativa != null) e.putBoolean(SESSAO, ativa);
        if (atividade != null) e.putBoolean(ATIVIDADE, atividade);
        if (Boolean.FALSE.equals(ativa)) e.putBoolean(ATIVIDADE, false);
        e.apply();
    }

    /** O app saiu da frente (MainActivity.onStop). */
    static void aoSairDoApp(Context c) {
        if (ignorarProximaSaida) { ignorarProximaSaida = false; return; }
        if (!deveMostrar(c)) return;
        try { c.startService(new Intent(c, BolhaService.class)); } catch (Exception ignorado) { }
    }

    /** O app voltou para a frente (MainActivity.onStart): a bolha some. */
    static void aoVoltarAoApp(Context c) {
        ignorarProximaSaida = false;
        try { c.stopService(new Intent(c, BolhaService.class)); } catch (Exception ignorado) { }
    }

    // ------------------------------------------------------------------

    private WindowManager wm;
    private FrameLayout bolha;
    private WindowManager.LayoutParams lpBolha;
    private FrameLayout alvo;
    private WindowManager.LayoutParams lpAlvo;
    private TextView alvoX;
    private boolean sobreAlvo = false;
    private ValueAnimator animando;

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private static int tipoJanela() {
        return Build.VERSION.SDK_INT >= 26 ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY : WindowManager.LayoutParams.TYPE_PHONE;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!deveMostrar(this)) { stopSelf(); return START_NOT_STICKY; }
        if (bolha == null) {
            try { criar(); } catch (Exception e) { stopSelf(); return START_NOT_STICKY; }
        }
        return START_NOT_STICKY;
    }

    private void criar() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        final int tam = dp(60);          // janela (deixa folga para a sombra)
        final int circ = dp(52);         // a bolinha

        bolha = new FrameLayout(this);
        bolha.setContentDescription("Voltar ao UBETEC");

        GradientDrawable fundo = new GradientDrawable();
        fundo.setShape(GradientDrawable.OVAL);
        fundo.setColor(Color.WHITE);
        fundo.setStroke(dp(2.5f), Color.parseColor("#1D4ED8"));
        View circulo = new View(this);
        circulo.setBackground(fundo);
        if (Build.VERSION.SDK_INT >= 21) circulo.setElevation(dp(5));
        bolha.addView(circulo, new FrameLayout.LayoutParams(circ, circ, Gravity.CENTER));

        ImageView icone = new ImageView(this);
        icone.setImageResource(R.mipmap.ic_launcher_round);
        icone.setScaleType(ImageView.ScaleType.CENTER_CROP);
        if (Build.VERSION.SDK_INT >= 21) icone.setElevation(dp(6));
        int ic = circ - dp(8);
        bolha.addView(icone, new FrameLayout.LayoutParams(ic, ic, Gravity.CENTER));

        if (prefs(this).getBoolean(ATIVIDADE, false)) {
            GradientDrawable verde = new GradientDrawable();
            verde.setShape(GradientDrawable.OVAL);
            verde.setColor(Color.parseColor("#16A34A"));
            verde.setStroke(dp(2), Color.WHITE);
            View ponto = new View(this);
            ponto.setBackground(verde);
            if (Build.VERSION.SDK_INT >= 21) ponto.setElevation(dp(7));
            FrameLayout.LayoutParams lpp = new FrameLayout.LayoutParams(dp(15), dp(15), Gravity.TOP | Gravity.END);
            lpp.topMargin = dp(3); lpp.rightMargin = dp(3);
            bolha.addView(ponto, lpp);
        }

        DisplayMetrics dm = getResources().getDisplayMetrics();
        lpBolha = new WindowManager.LayoutParams(tam, tam, tipoJanela(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lpBolha.gravity = Gravity.TOP | Gravity.START;
        boolean esquerda = "esq".equals(prefs(this).getString(LADO, "dir"));
        lpBolha.x = esquerda ? 0 : dm.widthPixels - tam;
        int y = prefs(this).getInt(POS_Y, Math.round(dm.heightPixels * 0.32f));
        lpBolha.y = Math.max(dp(24), Math.min(dm.heightPixels - tam - dp(96), y));

        bolha.setOnTouchListener(new Arrastar(tam));
        bolha.setAlpha(0f);
        bolha.setScaleX(0.6f); bolha.setScaleY(0.6f);
        wm.addView(bolha, lpBolha);
        bolha.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(220).setInterpolator(new OvershootInterpolator()).start();
    }

    private void mostrarAlvo() {
        if (alvo != null) { alvo.setVisibility(View.VISIBLE); return; }
        alvo = new FrameLayout(this);
        GradientDrawable f = new GradientDrawable();
        f.setShape(GradientDrawable.OVAL);
        f.setColor(Color.parseColor("#CC0F172A"));
        f.setStroke(dp(2), Color.parseColor("#66FFFFFF"));
        alvoX = new TextView(this);
        alvoX.setText("×");
        alvoX.setTextColor(Color.WHITE);
        alvoX.setTextSize(30);
        alvoX.setGravity(Gravity.CENTER);
        alvoX.setBackground(f);
        alvo.addView(alvoX, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        lpAlvo = new WindowManager.LayoutParams(dp(84), dp(84), tipoJanela(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        lpAlvo.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        lpAlvo.y = dp(56);
        try { wm.addView(alvo, lpAlvo); } catch (Exception e) { alvo = null; }
    }

    private void esconderAlvo() {
        if (alvo != null) alvo.setVisibility(View.GONE);
        sobreAlvo = false;
    }

    /** Centro do "×" na tela e se a bolinha está em cima dele. */
    private boolean pertoDoAlvo(int tam) {
        if (alvo == null) return false;
        DisplayMetrics dm = getResources().getDisplayMetrics();
        float cxAlvo = dm.widthPixels / 2f, cyAlvo = dm.heightPixels - dp(56) - dp(84) / 2f;
        float cx = lpBolha.x + tam / 2f, cy = lpBolha.y + tam / 2f;
        double d = Math.hypot(cx - cxAlvo, cy - cyAlvo);
        return d < dp(90);
    }

    private class Arrastar implements View.OnTouchListener {
        private final int tam;
        private final int folga;
        private float toqueX, toqueY;
        private int iniX, iniY;
        private boolean arrastando = false;

        Arrastar(int tam) { this.tam = tam; this.folga = ViewConfiguration.get(BolhaService.this).getScaledTouchSlop(); }

        @Override
        public boolean onTouch(View v, MotionEvent ev) {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    if (animando != null) animando.cancel();
                    toqueX = ev.getRawX(); toqueY = ev.getRawY();
                    iniX = lpBolha.x; iniY = lpBolha.y;
                    arrastando = false;
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start();
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = ev.getRawX() - toqueX, dy = ev.getRawY() - toqueY;
                    if (!arrastando && Math.hypot(dx, dy) > folga) { arrastando = true; mostrarAlvo(); }
                    if (arrastando) {
                        lpBolha.x = Math.round(iniX + dx);
                        lpBolha.y = Math.round(iniY + dy);
                        try { wm.updateViewLayout(bolha, lpBolha); } catch (Exception ignorado) { }
                        boolean perto = pertoDoAlvo(tam);
                        if (perto != sobreAlvo) {
                            sobreAlvo = perto;
                            if (alvoX != null) alvoX.animate().scaleX(perto ? 1.25f : 1f).scaleY(perto ? 1.25f : 1f).setDuration(120).start();
                            if (perto) v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                        }
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    if (!arrastando) {
                        if (ev.getActionMasked() == MotionEvent.ACTION_UP) abrirApp();
                        return true;
                    }
                    if (sobreAlvo) { fecharPorAgora(); return true; }
                    esconderAlvo();
                    grudarNaBorda(tam);
                    return true;
            }
            return false;
        }
    }

    private void grudarNaBorda(final int tam) {
        DisplayMetrics dm = getResources().getDisplayMetrics();
        final boolean esquerda = lpBolha.x + tam / 2 < dm.widthPixels / 2;
        final int destinoX = esquerda ? 0 : dm.widthPixels - tam;
        lpBolha.y = Math.max(dp(24), Math.min(dm.heightPixels - tam - dp(96), lpBolha.y));
        prefs(this).edit().putInt(POS_Y, lpBolha.y).putString(LADO, esquerda ? "esq" : "dir").apply();
        animando = ValueAnimator.ofInt(lpBolha.x, destinoX);
        animando.setDuration(260);
        animando.setInterpolator(new DecelerateInterpolator());
        animando.addUpdateListener(a -> {
            if (bolha == null) return;
            lpBolha.x = (Integer) a.getAnimatedValue();
            try { wm.updateViewLayout(bolha, lpBolha); } catch (Exception ignorado) { }
        });
        animando.start();
    }

    private void abrirApp() {
        try {
            Intent i = new Intent(this, MainActivity.class);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(i);
        } catch (Exception ignorado) { }
        stopSelf();
    }

    /** Arrastou até o "×": some até a próxima vez que o técnico sair do app. */
    private void fecharPorAgora() {
        esconderAlvo();
        if (bolha != null) {
            bolha.animate().alpha(0f).scaleX(0.3f).scaleY(0.3f).setDuration(160).withEndAction(this::stopSelf).start();
        } else {
            stopSelf();
        }
    }

    @Override
    public void onConfigurationChanged(Configuration nova) {
        super.onConfigurationChanged(nova);
        if (bolha != null && lpBolha != null) grudarNaBorda(lpBolha.width);
    }

    @Override
    public void onDestroy() {
        if (animando != null) animando.cancel();
        try { if (bolha != null) wm.removeView(bolha); } catch (Exception ignorado) { }
        try { if (alvo != null) wm.removeView(alvo); } catch (Exception ignorado) { }
        bolha = null; alvo = null;
        super.onDestroy();
    }
}
