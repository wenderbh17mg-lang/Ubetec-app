# UBETEC — app Android (versão 1.4, empresas e técnicos)

App único **UBETEC** (`br.com.ubetec.app`): abre a plataforma em **https://ubetec.com.br** — empresas e técnicos
entram com o login de sempre. Para o técnico, continua com o GPS em segundo plano durante o expediente/deslocamento,
microfone e câmera (como o antigo "UBETEC Técnico").

## Novidades da versão 1.4

- **O app se atualiza**: a plataforma compara a versão instalada com a última publicada em Releases e mostra
  "Nova versão do app" com **Atualizar** — baixa dentro do app (com progresso) e abre o instalador do Android.
  Na primeira vez, o Android pede para permitir "Instalar apps desconhecidos" para o UBETEC.
- **Versão na etiqueta**: o `gerar-apk.yml` publica `UBETEC.apk` com a etiqueta `v<versão>-c<versionCode>-b<build>`
  (ex.: `v1.4-c5-b9`). Para lançar uma versão nova, aumente `versionCode` e `versionName` em `android/app/build.gradle`.

## Novidades da versão 1.3

- **Tela ajustada ao celular**: o app vai de ponta a ponta (padrão do Android 15) e informa à plataforma a altura
  da barra de status e da barra de gestos. O topo não fica mais por baixo do relógio/bateria, e a barra inferior
  fica acima dos gestos. Com o teclado aberto, a tela encolhe para o campo continuar visível.
- **Ícones das barras acompanham o tema** (claro/escuro) — plugin `UbetecApp` (`android/app/src/main/java/.../UbetecAppPlugin.java`).
- **Avisos com o app fechado** (Firebase Cloud Messaging) com três canais no Android: *Avisos*, *Alertas urgentes* e
  *Mensagens* — cada um com som e vibração que a pessoa ajusta no celular.
  Para ligar: coloque o `google-services.json` do Firebase em `android/app/` (é configuração pública do app,
  não é senha) e a conta de serviço do Firebase na variável **FCM_SERVICE_ACCOUNT** do painel da hospedagem.
  Sem o arquivo, o app funciona normalmente e mostra "Avisos com o app fechado — em preparação".


## Gerar o APK pelo GitHub (recomendado)

Envie TODO o conteúdo desta pasta para um repositório no GitHub (Add file › Upload files). O arquivo `.github/workflows/gerar-apk.yml` compila sozinho a cada envio; em ~8 min o `UBETEC-<versão>.apk` aparece em **Releases**.
A chave de teste `android/app/ubetec-debug.keystore` é fixa: cada APK novo instala por cima do anterior.

## Gerar o APK no Android Studio (alternativa)

Rode antes `npm install` e `npx cap sync android` (precisa do Node 22).

1. Abra o **Android Studio** › **Open** › escolha a pasta **`android`** desta pasta.
2. Espere o "Gradle sync" terminar (na primeira vez baixa coisas do Google — alguns minutos).
3. Menu **Build › Build App Bundle(s) / APK(s) › Build APK(s)** → **locate** → `app-debug.apk`.
4. Mande o `app-debug.apk` para o celular (WhatsApp, e-mail, cabo) e instale (permitir "instalar apps desta fonte").

Obs.: é um app novo (outro identificador). Quem tinha o "UBETEC Técnico" pode desinstalar o antigo depois de instalar este.
Se o endereço mudar, troque `server.url` em `capacitor.config.json` **e** em `android/app/src/main/assets/capacitor.config.json` e gere de novo.

---
# UBETEC Técnico — app Android (GPS em segundo plano)

O app abre a própria plataforma UBETEC (o mesmo endereço do site) e acrescenta o que o navegador não
consegue: **continuar enviando a posição com o Waze/Google Maps na frente ou a tela apagada**.

- O GPS em segundo plano só liga quando a plataforma manda: **expediente aberto** (equipe interna) ou
  **deslocamento/atendimento de uma O.S.** Fora disso, nada é enviado.
- Enquanto está ligado, o Android mostra a notificação fixa **"UBETEC — localização ao vivo"** (obrigatória).
- Ao chegar a até 120 m do local com o UBETEC atrás do Waze/Google, chega a notificação **"Você chegou ao local"**.
  Tocando nela (ou só voltando ao UBETEC), a tela de **check-in** já está aberta — com a opção **Reportar um problema**.
- Atualizações da plataforma (novas versões do zip) chegam sozinhas no app: ele carrega o site. Só é preciso
  gerar um app novo se mudar algo desta pasta.

## Novidade da versão 1.1 (v116 da plataforma)

- O app passa a pedir **microfone** e **câmera**: sem isso, o botão de áudio da conversa da O.S. dizia
  "microfone bloqueado" e não dava para tirar foto na hora de anexar. Na primeira vez que o técnico gravar
  um áudio (ou tirar uma foto pela conversa), o Android pergunta — é só tocar em **Permitir**.
- Para valer, **gere o app de novo** (passos abaixo) e instale por cima do anterior (versionCode 2).

## 1. Antes de tudo: o endereço da plataforma

Esta pasta é separada do zip do servidor (não envie para a StayCloud). Abra `capacitor.config.json` e confira o endereço em `"server" → "url"`. Ele precisa ser o endereço público
da UBETEC **com https**, por exemplo:

```json
"url": "https://app.ubetec.com.br"
```

(Hoje está `https://ubetec-backend-v12.stayai.space`. Se o endereço mudar, troque aqui e gere o app de novo.)

## 2. Gerar o app no seu computador (Windows) — uma vez

1. Instale o **Android Studio** (gratuito): https://developer.android.com/studio — aceite as opções padrão.
2. Instale o **Node.js 22** (se ainda não tiver): https://nodejs.org
3. Abra o **Prompt de Comando** na pasta `app-tecnico` e rode:
   ```
   npm install
   npx cap sync android
   npx cap open android
   ```
4. O Android Studio abre o projeto. Espere terminar o "Gradle sync" (barra de baixo; na primeira vez demora).
5. Menu **Build › Build App Bundle(s) / APK(s) › Build APK(s)**. No fim, clique em **locate**: o arquivo é
   `app-debug.apk`.

> Sem Android Studio: dá para gerar no GitHub. Veja `github-actions/gerar-apk.yml` (instruções no topo do arquivo).

## 3. Instalar nos celulares dos técnicos

**Teste / poucos técnicos (mais rápido):** mande o `app-debug.apk` pelo WhatsApp ou e-mail. No celular, abra o
arquivo e permita "instalar apps desta fonte".

**Google Play (recomendado para muitos técnicos):** conta de desenvolvedor Google (US$ 25, uma vez). Gere
**Build › Generate Signed App Bundle** (crie e GUARDE a chave/keystore — sem ela não dá para atualizar o app) e
envie o `.aab` no Play Console. A Google pede a declaração de uso de localização: explique que é para a empresa
acompanhar o técnico **durante o atendimento**, com notificação visível, e que só liga em atendimento/expediente.

## 4. Primeiro uso no celular do técnico

1. Abrir o **UBETEC Técnico** e entrar com o login de sempre.
2. Permitir **Notificações** e **Localização** ("Durante o uso do app" já basta — o serviço fica ativo com a notificação).
3. Em alguns celulares (Xiaomi, Samsung, Motorola…) vale tirar o UBETEC da **economia de bateria**:
   Configurações › Apps › UBETEC Técnico › Bateria › **Sem restrições**. Sem isso, alguns aparelhos param o GPS.

## iPhone

O mesmo projeto funciona no iPhone (`npx cap add ios`), mas exige um **Mac com Xcode** e a conta
**Apple Developer (US$ 99/ano)**. Fica para depois do Android.
