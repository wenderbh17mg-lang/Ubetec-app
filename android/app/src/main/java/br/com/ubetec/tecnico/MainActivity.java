package br.com.ubetec.tecnico;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.BridgeActivity;

/**
 * UBETEC Técnico — abre a plataforma (server.url em capacitor.config.json).
 * O GPS em segundo plano é feito pelo plugin BackgroundGeolocation, ligado pela própria
 * plataforma só durante o expediente ou o deslocamento/atendimento de uma O.S.
 */
public class MainActivity extends BridgeActivity {
    private static final int PEDIDO_NOTIFICACAO = 4101;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Android 13+: a notificação "UBETEC — localização ao vivo" precisa desta permissão para aparecer
        if (Build.VERSION.SDK_INT >= 33
                && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{ Manifest.permission.POST_NOTIFICATIONS }, PEDIDO_NOTIFICACAO);
        }
    }
}
