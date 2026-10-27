package br.com.ubetec.tecnico;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** v1.6 — ao ligar o celular (ou depois de atualizar o app), o UBETEC volta a ficar ativo em segundo plano. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        String a = intent != null ? intent.getAction() : null;
        if (Intent.ACTION_BOOT_COMPLETED.equals(a) || Intent.ACTION_MY_PACKAGE_REPLACED.equals(a) || "android.intent.action.QUICKBOOT_POWERON".equals(a)) {
            AtivoService.iniciar(context);
        }
    }
}
