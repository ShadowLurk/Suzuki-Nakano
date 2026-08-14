package com.suzuki.mobile.net

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch

object EdgeTtsClient {

    private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
    private const val VOZ = "pt-BR-FranciscaNeural"
    private const val TOM = "+15Hz"
    private const val VELOCIDADE = "+10%"

    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    fun sintetizar(texto: String): ByteArray {
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        val requestId = UUID.randomUUID().toString().replace("-", "")

        val url = "wss://speech.platform.bing.com/consumer/speech/synthesize/" +
            "readaloud/edge/v1?TrustedClientToken=$TRUSTED_CLIENT_TOKEN&ConnectionId=$connectionId"

        val audio = ByteArrayOutputStream()
        val latch = CountDownLatch(1)
        var erro: Throwable? = null

        val request = Request.Builder()
            .url(url)
            .addHeader("Origin", "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold")
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
                erro = t
                latch.countDown()
            }
        }

        val ws = http.newWebSocket(request, listener)

        val terminou = latch.await(20, TimeUnit.SECONDS)
        if (!terminou) {
            ws.cancel()
            throw RuntimeException("Timeout esperando o áudio do Edge TTS")
        }

        erro?.let { throw RuntimeException("Falha no Edge TTS: ${it.message}", it) }

        if (audio.size() == 0) {
            throw RuntimeException("Edge TTS não retornou áudio")
        }

        return audio.toByteArray()
    }
}
