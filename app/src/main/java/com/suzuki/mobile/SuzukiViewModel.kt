package com.suzuki.mobile

import android.app.Application
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.speech.tts.TextToSpeech
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.suzuki.mobile.data.MemoryStore
import com.suzuki.mobile.data.Persona
import com.suzuki.mobile.net.EdgeTtsClient
import com.suzuki.mobile.net.GroqClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.util.Locale

data class ChatMessage(val deUsuario: Boolean, val texto: String)

data class SuzukiUiState(
    val mensagens: List<ChatMessage> = emptyList(),
    val processando: Boolean = false,
    val gravando: Boolean = false,
    val apiKeyConfigurada: Boolean = false,
    val erro: String? = null,
)

class SuzukiViewModel(application: Application) : AndroidViewModel(application) {

    private val memoria = MemoryStore(application)
    private val prefs = application.getSharedPreferences("suzuki_prefs", 0)

    private val _state = MutableStateFlow(SuzukiUiState())
    val state: StateFlow<SuzukiUiState> = _state

    private var tts: TextToSpeech? = null
    private var mediaPlayer: MediaPlayer? = null
    private var gravador: MediaRecorder? = null
    private var arquivoAudio: File? = null

    private val historicoSessao = mutableListOf<String>()

    init {
        val chave = prefs.getString("groq_api_key", "") ?: ""
        _state.update { it.copy(apiKeyConfigurada = chave.isNotBlank()) }

        tts = TextToSpeech(application) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale("pt", "BR")
            }
        }
    }

    fun salvarApiKey(chave: String) {
        prefs.edit().putString("groq_api_key", chave.trim()).apply()
        _state.update { it.copy(apiKeyConfigurada = chave.isNotBlank(), erro = null) }
    }

    private fun getApiKey(): String = prefs.getString("groq_api_key", "") ?: ""

    fun enviarTexto(texto: String) {
        if (texto.isBlank()) return
        val chave = getApiKey()
        if (chave.isBlank()) {
            _state.update { it.copy(erro = "Configura sua chave da Groq primeiro (ícone de engrenagem).") }
            return
        }

        _state.update {
            it.copy(
                mensagens = it.mensagens + ChatMessage(deUsuario = true, texto = texto),
                processando = true,
                erro = null,
            )
        }

        viewModelScope.launch {
            try {
                val resposta = withContext(Dispatchers.IO) {
                    processarMensagem(chave, texto)
                }

                _state.update {
                    it.copy(
                        mensagens = it.mensagens + ChatMessage(deUsuario = false, texto = resposta),
                        processando = false,
                    )
                }

                falar(resposta)

                viewModelScope.launch(Dispatchers.IO) {
                    capturarMemoria(chave, texto)
                }
            } catch (e: Exception) {
                _state.update { it.copy(processando = false, erro = e.message ?: "Erro desconhecido") }
            }
        }
    }

    private fun processarMensagem(chave: String, texto: String): String {
        val memoriaRelevante = memoria.recall(texto)
        val historicoTexto = historicoSessao.takeLast(8).joinToString("\n")

        val systemPrompt = Persona.montar(memoriaRelevante, historicoTexto)
        val client = GroqClient(chave)
        val resposta = client.chatCompletion(systemPrompt, texto)

        historicoSessao += "Usuário: \"$texto\""
        historicoSessao += "Suzuki: \"$resposta\""

        return resposta
    }

    private fun capturarMemoria(chave: String, texto: String) {
        if (texto.length < 20) return

        try {
            val client = GroqClient(chave)
            val bruto = client.chatCompletion(
                systemPrompt = Persona.PROMPT_CLASSIFICADOR,
                userText = texto,
                temperatura = 0.0,
            )

            val limpo = bruto.replace("```json", "").replace("```", "").trim()
            val json = JSONObject(limpo)

            if (!json.optBoolean("salvar", false)) return

            val tipo = json.optString("tipo", "")
            val textoFinal = json.optString("texto", "").trim()
            if (textoFinal.isEmpty()) return

            when (tipo) {
                "projeto" -> {
                    val nomeProjeto = json.optString("projeto", "").trim()
                    if (nomeProjeto.isNotEmpty()) {
                        memoria.addDetalheProjeto(nomeProjeto, textoFinal)
                        val apelidos = json.optJSONArray("apelidos")
                        if (apelidos != null) {
                            for (i in 0 until apelidos.length()) {
                                memoria.addApelidoProjeto(nomeProjeto, apelidos.getString(i))
                            }
                        }
                    }
                }
                "gosto" -> memoria.addGosto(textoFinal)
                "nome" -> memoria.setNome(textoFinal)
                else -> memoria.addFato(textoFinal)
            }
        } catch (_: Exception) {
        }
    }

    fun iniciarGravacao() {
        val app = getApplication<Application>()
        val arquivo = File(app.cacheDir, "gravacao_${System.currentTimeMillis()}.m4a")
        arquivoAudio = arquivo

        gravador = MediaRecorder().apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setOutputFile(arquivo.absolutePath)
            prepare()
            start()
        }

        _state.update { it.copy(gravando = true) }
    }

    fun pararGravacaoEEnviar() {
        val chave = getApiKey()
        if (chave.isBlank()) {
            _state.update { it.copy(erro = "Configura sua chave da Groq primeiro.") }
            return
        }

        try {
            gravador?.apply {
                stop()
                release()
            }
        } catch (_: Exception) {
        }
        gravador = null
        _state.update { it.copy(gravando = false, processando = true) }

        val arquivo = arquivoAudio ?: return

        viewModelScope.launch {
            try {
                val texto = withContext(Dispatchers.IO) {
                    GroqClient(chave).transcreverAudio(arquivo)
                }

                if (texto.isBlank()) {
                    _state.update { it.copy(processando = false, erro = "Não consegui entender o áudio.") }
                    return@launch
                }

                _state.update { it.copy(processando = false) }
                enviarTexto(texto)
            } catch (e: Exception) {
                _state.update { it.copy(processando = false, erro = e.message ?: "Erro ao transcrever áudio") }
            }
        }
    }

    private fun falar(texto: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val mp3 = EdgeTtsClient.sintetizar(texto)
                val arquivo = File(getApplication<Application>().cacheDir, "fala_${System.currentTimeMillis()}.mp3")
                arquivo.writeBytes(mp3)

                withContext(Dispatchers.Main) {
                    mediaPlayer?.release()
                    mediaPlayer = MediaPlayer().apply {
                        setDataSource(arquivo.absolutePath)
                        setOnCompletionListener { arquivo.delete() }
                        setOnErrorListener { _, _, _ -> arquivo.delete(); true }
                        prepare()
                        start()
                    }
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    tts?.speak(texto, TextToSpeech.QUEUE_FLUSH, null, null)
                }
            }
        }
    }

    override fun onCleared() {
        tts?.stop()
        tts?.shutdown()
        mediaPlayer?.release()
        super.onCleared()
    }
}
