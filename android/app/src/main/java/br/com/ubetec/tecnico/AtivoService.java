package br.com.ubetec.tecnico;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * v1.6 — "UBETEC ativo": mantém o app vivo em segundo plano o tempo todo (o Android não fecha o UBETEC),
 * para os avisos e as conversas chegarem na hora e o GPS do trabalho não ser interrompido.
 * NÃO usa localização: o GPS continua sendo ligado pela plataforma só no expediente e nas atividades
 * (plugin BackgroundGeolocation). Notificação discreta e fixa, exigida pelo Android para isso.
 * Liga ao abrir o app e ao ligar o celular; a pessoa pode desligar (UbetecApp.sempreAtivo({ligado:false})).
 */
public class AtivoService extends Service {
    static final String CANAL = "ubetec_ativo";
    static final int ID = 7101;
    private static final String PREFS = "ubetec_app";
    private static final String CHAVE = "sempre_ativo";

    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    static boolean ligado(Context c) { return prefs(c).getBoolean(CHAVE, true); }
    static void definir(Context c, boolean v) { prefs(c).edit().putBoolean(CHAVE, v).apply(); if (v) iniciar(c); else parar(c); }

    static void iniciar(Context c) {
        if (!ligado(c)) return;
        try { ContextCompat.startForegroundService(c, new Intent(c, AtivoService.class)); } catch (Exception ignorado) { }
    }

    static void parar(Context c) {
        try { c.stopService(new Intent(c, AtivoService.class)); } catch (Exception ignorado) { }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (!ligado(this)) { stopSelf(); return START_NOT_STICKY; }
        criarCanal();
        Intent abrir = new Intent(this, MainActivity.class);
        abrir.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent pi = PendingIntent.getActivity(this, 0, abrir, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new NotificationCompat.Builder(this, CANAL)
                .setSmallIcon(R.drawable.ic_stat_avisos)
                .setColor(ContextCompat.getColor(this, R.color.ubetec_avisos))
                .setContentTitle("UBETEC ativo")
                .setContentText("Avisos e conversas chegam na hora")
                .setContentIntent(pi)
                .setOngoing(true)
                .setShowWhen(false)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build();
        try {
            int tipo = Build.VERSION.SDK_INT >= 34 ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE : 0;
            ServiceCompat.startForeground(this, ID, n, tipo);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        return START_STICKY;
    }

    private void criarCanal() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (nm == null || nm.getNotificationChannel(CANAL) != null) return;
        NotificationChannel ch = new NotificationChannel(CANAL, "UBETEC ativo", NotificationManager.IMPORTANCE_MIN);
        ch.setDescription("Mantém o UBETEC aberto em segundo plano para os avisos e as conversas chegarem na hora.");
        ch.setShowBadge(false);
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }
}
