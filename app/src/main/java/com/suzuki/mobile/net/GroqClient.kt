package com.suzuki.mobile.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Cliente fino pra API da Groq (compatível com o formato OpenAI),
 * equivalente ao brain/ai_client.py da versão desktop — mas só com
 * o provedor Groq, já que o app roda direto no celular sem chave
 * própria de servidor.
 *
 * Modelos usados são os mesmos da versão desktop: "openai/gpt-oss-120b"
 * pra texto e "whisper-large-v3" (hospedado na própria Groq) pra
 * transcrever áudio — assim o celular não precisa rodar nenhum
 * modelo pesado localmente.
 */
class GroqClient(private val apiKey: String) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val jsonMedia = "application/json; charset=utf-8".toMediaType()

    class GroqException(message: String) : Exception(message)

    /**
     * Manda o system prompt + a fala do usuário, devolve o texto de
     * resposta. `temperatura` baixa (perto de 0) é usada pelo
     * classificador de memória; alta (padrão) pra conversa normal.
     */
    fun chatCompletion(systemPrompt: String, userText: String, temperatura: Double = 0.8): String {
        val body = JSONObject().apply {
            put("model", "openai/gpt-oss-120b")
            put("temperature", temperatura)
            put("max_tokens", 300)
            put("messages", JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", systemPrompt) })
                put(JSONObject().apply { put("role", "user"); put("content", userText) })
            })
        }

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()

        http.newCall(request).execute().use { resp ->
            val texto = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw GroqException("Groq respondeu ${resp.code}: $texto")
            }
            val json = JSONObject(texto)
            return json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content")
                .trim()
        }
    }

    /**
     * Transcreve um arquivo de áudio (gravado pelo MediaRecorder)
     * usando o Whisper hospedado na Groq — evita rodar Whisper local
     * no celular, que seria pesado demais na maioria dos aparelhos.
     */
    fun transcreverAudio(arquivo: File): String {
        val corpoAudio = arquivo.asRequestBody("audio/mp4".toMediaType())

        val multipart = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", arquivo.name, corpoAudio)
            .addFormDataPart("model", "whisper-large-v3")
            .addFormDataPart("language", "pt")
            .build()

        val request = Request.Builder()
            .url("https://api.groq.com/openai/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(multipart)
            .build()

        http.newCall(request).execute().use { resp ->
            val texto = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw GroqException("Groq (transcrição) respondeu ${resp.code}: $texto")
            }
            return JSONObject(texto).optString("text", "").trim()
        }
    }
}
