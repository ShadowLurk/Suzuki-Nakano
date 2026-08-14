package com.suzuki.mobile.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import kotlin.math.roundToLong

object EdgeTtsClient {

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val VOZ = "pt-BR-FranciscaNeural"
    private const val TOM = "+15Hz"
    private const val VELOCIDADE = "+10%"

    private const val CHROMIUM_FULL_VERSION = "130.0.2849.68"
    private const val CHROMIUM_MAJOR_VERSION = "130"
    private const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
    private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR_VERSION.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR_VERSION.0.0.0"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun gerarSecMsGec(): String {
        val winEpoch = 11644473600L
        val agoraSegundos = System.currentTimeMillis() / 1000.0

        var ticks = agoraSegundos + winEpoch
        ticks -= ticks % 300

        val ticksEm100ns = (ticks * 1e9 / 100).roundToLong()

        val paraHash = "$ticksEm100ns$TRUSTED_CLIENT_TOKEN"
        val hash = MessageDigest.getInstance("SHA-256").digest(paraHash.toByteArray(Charsets.US_ASCII))

        return hash.joinToString("") { "%02X".format(it) }
    }

    fun sintetizar(texto: String): ByteArray {
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val requestId = UUID.randomUUID().toString().replace("-", "")

        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1" +
            "?TrustedClientToken=$TRUSTED_CLIENT_TOKEN" +
            "&ConnectionId=$connectionId" +
            "&Sec-MS-GEC=${gerarSecMsGec()}" +
            "&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION"

        val audio = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        var erro: Throwable? = null

        val request = Request.Builder()
            .url(url)
            .addHeader("Pragma", "no-cache")
            .addHeader("Cache-Control", "no-cache")
            .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
            .addHeader("User-Agent", USER_AGENT)
            .build()

        val listener = object : WebSocketListener() {

            override fun onOpen(webSocket: WebSocket, response: Response) {
                val configMsg = "Content-Type:application/json; charset=utf-8\r\n" +
                    "Path:speech.config\r\n\r\n" +
                    "{\"context\":{\"synthesis\":{\"audio\":{" +
                    "\"metadataoptions\":{\"sentenceBoundaryEnabled\":false,\"wordBoundaryEnabled\":false}," +
                    "\"outputFormat\":\"audio-24khz-48kbitrate-mono-mp3\"}}}}"
                webSocket.send(configMsg)

                val textoEscapado = texto
                    .replace("&", "&amp;")
                    .replace("<", "&lt;")
                    .replace(">", "&gt;")

                val ssml = "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' " +
                    "xml:lang='pt-BR'><voice name='$VOZ'>" +
                    "<prosody pitch='$TOM' rate='$VELOCIDADE' volume='+0%'>$textoEscapado</prosody>" +
                    "</voice></speak>"

                val ssmlMsg = "X-RequestId:$requestId\r\n" +
                    "Content-Type:application/ssml+xml\r\n" +
                    "Path:ssml\r\n\r\n" + ssml
                webSocket.send(ssmlMsg)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                if (text.contains("Path:turn.end")) {
                    webSocket.close(1000, null)
                    latch.countDown()
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val dados = bytes.toByteArray()
                if (dados.size < 2) return

                val tamanhoHeader = ((dados[0].toInt() and 0xFF) shl 8) or (dados[1].toInt() and 0xFF)
                val inicioAudio = 2 + tamanhoHeader
                if (inicioAudio < dados.size) {
                    audio.write(dados, inicioAudio, dados.size - inicioAudio)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                erro = RuntimeException(
                    "onFailure: ${t.message} (http=${response?.code})", t
                )
                latch.countDown()
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        }

        val ws = http.newWebSocket(request, listener)

        val terminou = latch.await(20, TimeUnit.SECONDS)
        if (!terminou) {
            ws.cancel()
            throw RuntimeException("Timeout esperando o áudio do Edge TTS")
        }

        erro?.let { throw it }

        if (audio.size() == 0) {
            throw RuntimeException("Edge TTS não retornou áudio (0 bytes)")
        }

        return audio.toByteArray()
    }
}
