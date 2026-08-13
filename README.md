# Suzuki Mobile

Versão Android da Suzuki, standalone (não depende do PC ligado).
Chat por texto ou voz, usando a API da Groq — igual em espírito à
versão desktop (`SuzukiNakano`), mas roda sozinha no celular.

## Como baixar o APK direto no celular (sem instalar nada no PC)

Esse projeto já vem com um workflow do **GitHub Actions** configurado
(`.github/workflows/build-apk.yml`) que compila o APK sozinho na
nuvem. Você só precisa subir os arquivos pro GitHub — tudo pelo
navegador, sem linha de comando:

1. Crie uma conta gratuita em https://github.com (se ainda não tiver).
2. Clique em **"New repository"**, dê um nome (ex: `suzuki-mobile`),
   deixe como **privado** ou público, e crie (sem adicionar README,
   pra não conflitar).
3. Na página do repositório vazio, clique em **"uploading an existing
   file"** e arraste **a pasta `SuzukiMobile` inteira** (ou o
   conteúdo dela) pra lá. Confirme o commit.
4. Vá na aba **"Actions"** do repositório — o workflow **"Compilar
   APK da Suzuki"** já deve estar rodando sozinho (demora uns 3-6
   minutos na primeira vez).
5. Quando o círculo ficar verde ✅, clique em cima da execução, desça
   até **"Artifacts"** e baixe **`suzuki-mobile-apk`** — isso baixa um
   `.zip` com o `app-debug.apk` dentro.
6. Pelo **celular** (o navegador do GitHub funciona normal no
   Android), acesse a mesma aba Actions, baixe o mesmo artefato,
   extraia o `.apk` e toque nele pra instalar. O Android vai pedir
   pra liberar "instalar apps de fontes desconhecidas" pro navegador
   — é normal, autoriza só uma vez.

Depois disso, qualquer mudança que você (ou eu) fizer no código e
subir de novo pro GitHub gera um APK novo automaticamente, sem você
precisar compilar nada manualmente.

## Alternativa: compilar no Android Studio

Se preferir compilar localmente (ou for mexer bastante no código):


1. Baixe e instale o **Android Studio** (gratuito): https://developer.android.com/studio
2. Abra a pasta `SuzukiMobile` inteira no Android Studio (`File > Open`).
3. Deixe o Gradle sincronizar sozinho na primeira vez (pode demorar
   alguns minutos, ele baixa as dependências).
4. Conecte um celular Android via USB com "Depuração USB" ativada
   (ou use um emulador criado pelo próprio Android Studio).
5. Clique no botão verde "Run" (▶) — o app instala e abre no celular.
6. Na primeira abertura, ele vai pedir a **chave da API da Groq**.
   Gere uma de graça em https://console.groq.com/keys e cola no
   diálogo. Fica salva só no celular (SharedPreferences local).

## O que já funciona

- Chat por texto, com a mesma persona/tom da Suzuki desktop.
- Segurar o botão do microfone pra gravar, soltar pra enviar —
  transcrição feita pelo Whisper hospedado na própria Groq (não
  roda IA pesada localmente, então funciona em qualquer aparelho).
- Resposta falada em voz alta (TextToSpeech nativo do Android).
- Memória permanente salva em `memoria.json` na pasta privada do
  app: nome, gostos, fatos e projetos (com apelidos, pra reconhecer
  quando você não fala o nome exato — mesma ideia que implementamos
  na versão desktop).
- Captura automática de memória: depois de cada mensagem, um
  classificador (chamada extra e barata pra Groq) decide se algo
  ali vale guardar permanentemente.

## O que ainda falta / próximos passos possíveis

- **Sem "visão" (F9)**: não tem tela de PC pra comentar. Se quiser,
  dá pra trocar por foto da câmera do celular depois.
- **Sem watchdog de inatividade**: a versão desktop comenta sozinha
  depois de 2 min de silêncio olhando a tela; no celular isso exigiria
  rodar em segundo plano (WorkManager/Foreground Service), o que o
  Android restringe bastante pra apps não abertos — dá pra fazer, mas
  é a próxima etapa, não entrou nessa primeira versão.
- **Persistência mais robusta**: hoje é um JSON simples via
  `MemoryStore.kt`. Se a memória crescer muito, migrar pra um banco
  local (Room/SQLite) é o próximo passo natural.
- **Chave de API em texto puro**: pra simplificar essa primeira
  versão ela fica em `SharedPreferences` comum. Pra produção de
  verdade, trocar por `EncryptedSharedPreferences` (a dependência já
  está no `build.gradle.kts`, só falta usar).

## Estrutura

```
app/src/main/java/com/suzuki/mobile/
├── MainActivity.kt          -> UI (Compose): tela de chat, botão de mic, config da API key
├── SuzukiViewModel.kt       -> estado do chat, grava/transcreve áudio, TTS, dispara captura de memória
├── data/
│   ├── Persona.kt           -> system prompt / personalidade da Suzuki (portado do language.py desktop)
│   └── MemoryStore.kt       -> memória permanente em JSON (equivalente a long_term.json + projects.py)
└── net/
    └── GroqClient.kt        -> chamadas HTTP pra Groq (chat completion + transcrição de áudio)
```
