package br.com.ubetec.tecnico;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PluginCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v1.10: voz do próprio Android para a plataforma. O navegador de dentro do app (WebView) não tem
 * reconhecimento nem síntese de voz; a página troca os dela por estes:
 *  - ouvir: SpeechRecognizer (o mesmo do microfone do teclado), em pt-BR, com textos parciais pelo evento
 *    "vozParcial" e o andamento pelo "vozEstado" (pronto, falando, processando). Termina devolvendo
 *    {sessao, texto, alternativas, erro?} — o erro no formato do navegador ("no-speech", "not-allowed",
 *    "network", "audio-capture", "aborted", "busy"…).
 *  - falar: TextToSpeech do celular; devolve {ok, erro?} quando termina de falar ou é interrompido.
 * O SpeechRecognizer só funciona na linha principal: tudo aqui roda nela.
 */
final class VozAndroid {

    interface Avisos {
        void avisar(String evento, JSObject dados);
    }

    private final Context ctx;
    private final Avisos avisos;
    private final Handler principal = new Handler(Looper.getMainLooper());

    // ---------- ouvir ----------
    private SpeechRecognizer reconhecedor;
    private PluginCall chamadaOuvir;
    private int sessaoOuvir;
    private String ultimoParcial = "";

    // ---------- falar ----------
    private TextToSpeech tts;
    private boolean ttsPronto = false;
    private Runnable falaEsperando;
    private PluginCall chamadaFalar;
    private String falaAtual;
    private int contFala = 0;

    VozAndroid(Context contexto, Avisos avisos) {
        this.ctx = contexto.getApplicationContext();
        this.avisos = avisos;
    }

    boolean reconhecimentoDisponivel() {
        try {
            return SpeechRecognizer.isRecognitionAvailable(ctx);
        } catch (Exception e) {
            return false;
        }
    }

    void ouvir(final PluginCall call) {
        final String idioma = textoOu(call.getString("idioma"), "pt-BR");
        final boolean parciais = !Boolean.FALSE.equals(call.getBoolean("parciais", true));
        final int alternativas = limite(inteiro(call, "alternativas", 1), 1, 5);
        final int silencioMs = limite(inteiro(call, "silencioMs", 0), 0, 10000);
        final int sessao = inteiro(call, "sessao", 0);
        principal.post(() -> {
            // uma escuta por vez: a anterior termina como "aborted"; e não ouvir a própria voz do celular
            encerrarOuvir("aborted");
            pararFala();
            if (!reconhecimentoDisponivel()) {
                resolverOuvir(call, sessao, "", null, "service-not-allowed");
                return;
            }
            SpeechRecognizer r;
            try {
                r = SpeechRecognizer.createSpeechRecognizer(ctx);
            } catch (Exception e) {
                r = null;
            }
            if (r == null) {
                resolverOuvir(call, sessao, "", null, "service-not-allowed");
                return;
            }
            reconhecedor = r;
            chamadaOuvir = call;
            sessaoOuvir = sessao;
            ultimoParcial = "";
            r.setRecognitionListener(new Ouvinte(sessao));
            Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, idioma);
            i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, idioma);
            i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, parciais);
            i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, alternativas);
            i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.getPackageName());
            if (silencioMs > 0) {
                // pausa maior antes de dar a fala por terminada (ditado); nem todo reconhecedor respeita
                i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, (long) silencioMs);
                i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, (long) silencioMs);
            }
            try {
                r.startListening(i);
            } catch (Exception e) {
                terminarOuvir(sessao, "", null, "audio-capture");
                return;
            }
            // trava de segurança: alguns aparelhos nunca respondem
            principal.postDelayed(() -> terminarOuvir(sessao, ultimoParcial, null, ultimoParcial.isEmpty() ? "no-speech" : null), 90000);
        });
    }

    /** "Parar": o reconhecedor entrega o que ouviu até aqui. */
    void pararOuvir() {
        principal.post(() -> {
            if (reconhecedor == null || chamadaOuvir == null) return;
            final int sessao = sessaoOuvir;
            try {
                reconhecedor.stopListening();
            } catch (Exception ignorado) { }
            // se o reconhecedor não responder depois do "parar", vale o que já tinha aparecido
            principal.postDelayed(() -> terminarOuvir(sessao, ultimoParcial, null, ultimoParcial.isEmpty() ? "no-speech" : null), 4000);
        });
    }

    void cancelarOuvir() {
        principal.post(() -> encerrarOuvir("aborted"));
    }

    /** Linha principal: interrompe a escuta atual (se houver) e devolve o erro à página. */
    private void encerrarOuvir(String erro) {
        if (chamadaOuvir != null) terminarOuvir(sessaoOuvir, "", null, erro);
        else liberarReconhecedor();
    }

    private void liberarReconhecedor() {
        final SpeechRecognizer r = reconhecedor;
        reconhecedor = null;
        if (r == null) return;
        // fora do retorno do próprio reconhecedor (alguns aparelhos travam com destroy dentro do onResults)
        principal.post(() -> {
            try {
                r.cancel();
            } catch (Exception ignorado) { }
            try {
                r.destroy();
            } catch (Exception ignorado) { }
        });
    }

    private void terminarOuvir(int sessao, String texto, List<String> alternativas, String erro) {
        if (chamadaOuvir == null || sessaoOuvir != sessao) return;
        PluginCall c = chamadaOuvir;
        chamadaOuvir = null;
        liberarReconhecedor();
        resolverOuvir(c, sessao, texto, alternativas, erro);
    }

    private static void resolverOuvir(PluginCall c, int sessao, String texto, List<String> alternativas, String erro) {
        JSObject r = new JSObject();
        r.put("sessao", sessao);
        r.put("texto", texto == null ? "" : texto);
        JSArray alts = new JSArray();
        if (alternativas != null) {
            for (String s : alternativas) {
                if (s != null && !s.trim().isEmpty()) alts.put(s.trim());
            }
        }
        r.put("alternativas", alts);
        if (erro != null) r.put("erro", erro);
        c.resolve(r);
    }

    private final class Ouvinte implements RecognitionListener {

        private final int sessao;

        Ouvinte(int sessao) {
            this.sessao = sessao;
        }

        private boolean atual() {
            return chamadaOuvir != null && sessaoOuvir == sessao;
        }

        private void estado(String e) {
            if (!atual()) return;
            JSObject d = new JSObject();
            d.put("sessao", sessao);
            d.put("estado", e);
            avisos.avisar("vozEstado", d);
        }

        @Override
        public void onReadyForSpeech(Bundle params) {
            estado("pronto");
        }

        @Override
        public void onBeginningOfSpeech() {
            estado("falando");
        }

        @Override
        public void onRmsChanged(float rmsdB) { }

        @Override
        public void onBufferReceived(byte[] buffer) { }

        @Override
        public void onEndOfSpeech() {
            estado("processando");
        }

        @Override
        public void onError(int error) {
            if (!atual()) return;
            // alguns aparelhos dão "sem correspondência" depois de já ter mostrado o texto: vale o que apareceu
            boolean semFala = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT || error == SpeechRecognizer.ERROR_CLIENT;
            if (semFala && !ultimoParcial.isEmpty()) {
                terminarOuvir(sessao, ultimoParcial, null, null);
                return;
            }
            terminarOuvir(sessao, "", null, erroWeb(error));
        }

        @Override
        public void onResults(Bundle results) {
            if (!atual()) return;
            ArrayList<String> lista = results == null ? null : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String melhor = (lista != null && !lista.isEmpty() && lista.get(0) != null) ? lista.get(0).trim() : "";
            if (melhor.isEmpty()) melhor = ultimoParcial;
            if (melhor.isEmpty()) {
                terminarOuvir(sessao, "", null, "no-speech");
                return;
            }
            terminarOuvir(sessao, melhor, lista, null);
        }

        @Override
        public void onPartialResults(Bundle partialResults) {
            if (!atual()) return;
            ArrayList<String> lista = partialResults == null ? null : partialResults.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            if (lista == null || lista.isEmpty() || lista.get(0) == null) return;
            String t = lista.get(0).trim();
            if (t.isEmpty() || t.equals(ultimoParcial)) return;
            ultimoParcial = t;
            JSObject d = new JSObject();
            d.put("sessao", sessao);
            d.put("texto", t);
            avisos.avisar("vozParcial", d);
        }

        @Override
        public void onEvent(int eventType, Bundle params) { }
    }

    /** Erros do Android no mesmo formato do reconhecimento do navegador (a página já sabe tratar). */
    private static String erroWeb(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "no-speech";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "not-allowed";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_SERVER:
            case 11: // ERROR_SERVER_DISCONNECTED (Android 12)
                return "network";
            case SpeechRecognizer.ERROR_AUDIO:
                return "audio-capture";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "busy";
            case 10: // ERROR_TOO_MANY_REQUESTS (Android 12)
                return "network";
            case 12: // ERROR_LANGUAGE_NOT_SUPPORTED (Android 12)
            case 13: // ERROR_LANGUAGE_UNAVAILABLE (Android 12)
                return "language-not-supported";
            default:
                return "aborted";
        }
    }

    // ---------- falar ----------
    void falar(final PluginCall call) {
        final String texto = call.getString("texto", "");
        if (texto == null || texto.trim().isEmpty()) {
            JSObject r = new JSObject();
            r.put("ok", true);
            call.resolve(r);
            return;
        }
        final String idioma = textoOu(call.getString("idioma"), "pt-BR");
        final float velocidade = limite(call.getFloat("velocidade", 1f), 0.5f, 2f, 1f);
        final float tom = limite(call.getFloat("tom", 1f), 0.5f, 2f, 1f);
        final float volume = limite(call.getFloat("volume", 1f), 0f, 1f, 1f);
        principal.post(() -> {
            pararFala();   // a fala nova substitui a anterior (a fila fica na página)
            final String id = "ubetec-" + (++contFala);
            chamadaFalar = call;
            falaAtual = id;
            Runnable fala = () -> falarAgora(id, texto, idioma, velocidade, tom, volume);
            if (ttsPronto && tts != null) fala.run();
            else {
                falaEsperando = fala;
                iniciarTts();
            }
        });
    }

    private void iniciarTts() {
        if (tts != null) return;
        try {
            tts = new TextToSpeech(ctx, status -> principal.post(() -> aoIniciarTts(status)));
        } catch (Exception e) {
            tts = null;
            aoIniciarTts(TextToSpeech.ERROR);
        }
    }

    private void aoIniciarTts(int status) {
        if (status == TextToSpeech.SUCCESS && tts != null) {
            ttsPronto = true;
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                @Override
                public void onStart(String id) { }

                @Override
                public void onDone(String id) {
                    principal.post(() -> terminarFala(id, true, null));
                }

                @Override
                @Deprecated
                public void onError(String id) {
                    principal.post(() -> terminarFala(id, false, "synthesis-failed"));
                }

                @Override
                public void onError(String id, int codigo) {
                    principal.post(() -> terminarFala(id, false, "synthesis-failed"));
                }

                @Override
                public void onStop(String id, boolean interrompido) {
                    principal.post(() -> terminarFala(id, false, "interrupted"));
                }
            });
            Runnable f = falaEsperando;
            falaEsperando = null;
            if (f != null) f.run();
        } else {
            // sem motor de voz no celular: avisa a página; a próxima fala tenta de novo
            falaEsperando = null;
            ttsPronto = false;
            if (tts != null) {
                try {
                    tts.shutdown();
                } catch (Exception ignorado) { }
                tts = null;
            }
            if (falaAtual != null) terminarFala(falaAtual, false, "sem-voz");
        }
    }

    private void falarAgora(String id, String texto, String idioma, float velocidade, float tom, float volume) {
        if (tts == null || !id.equals(falaAtual)) return;
        try {
            int lr = tts.setLanguage(Locale.forLanguageTag(idioma));
            if (lr == TextToSpeech.LANG_MISSING_DATA || lr == TextToSpeech.LANG_NOT_SUPPORTED) tts.setLanguage(Locale.forLanguageTag("pt-BR"));
            tts.setSpeechRate(velocidade);
            tts.setPitch(tom);
            Bundle p = new Bundle();
            p.putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volume);
            String t = texto.length() > 3900 ? texto.substring(0, 3900) : texto;
            if (tts.speak(t, TextToSpeech.QUEUE_FLUSH, p, id) != TextToSpeech.SUCCESS) terminarFala(id, false, "synthesis-failed");
        } catch (Exception e) {
            terminarFala(id, false, "synthesis-failed");
        }
    }

    private void terminarFala(String id, boolean ok, String erro) {
        if (id == null || !id.equals(falaAtual)) return;
        falaAtual = null;
        PluginCall c = chamadaFalar;
        chamadaFalar = null;
        if (c == null) return;
        JSObject r = new JSObject();
        r.put("ok", ok);
        if (erro != null) r.put("erro", erro);
        c.resolve(r);
    }

    /** Linha principal: interrompe a fala atual (se houver). */
    private void pararFala() {
        falaEsperando = null;
        String id = falaAtual;
        if (tts != null && ttsPronto) {
            try {
                tts.stop();
            } catch (Exception ignorado) { }
        }
        if (id != null) terminarFala(id, false, "interrupted");
    }

    void calar() {
        principal.post(this::pararFala);
    }

    /** O app saiu da frente: para de ouvir (a fala em andamento termina a frase). */
    void aoPausar() {
        principal.post(() -> encerrarOuvir("aborted"));
    }

    void encerrar() {
        principal.post(() -> {
            encerrarOuvir("aborted");
            pararFala();
            if (tts != null) {
                try {
                    tts.shutdown();
                } catch (Exception ignorado) { }
                tts = null;
                ttsPronto = false;
            }
        });
    }

    // ---------- ajudantes ----------
    private static String textoOu(String v, String padrao) {
        return v == null || v.trim().isEmpty() ? padrao : v.trim();
    }

    private static int inteiro(PluginCall c, String chave, int padrao) {
        Integer v = c.getInt(chave, padrao);
        return v == null ? padrao : v;
    }

    private static int limite(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static float limite(Float v, float min, float max, float padrao) {
        float x = (v == null || Float.isNaN(v)) ? padrao : v;
        return Math.max(min, Math.min(max, x));
    }
}
