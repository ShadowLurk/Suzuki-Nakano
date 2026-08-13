package com.suzuki.mobile

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

class MainActivity : ComponentActivity() {

    private val viewModel: SuzukiViewModel by viewModels()

    private val pedirPermissaoMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* resultado tratado no momento do toque no botão */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            pedirPermissaoMic.launch(Manifest.permission.RECORD_AUDIO)
        }

        setContent {
            MaterialTheme {
                TelaChat(viewModel = viewModel, temPermissaoMic = {
                    ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                }, pedirPermissao = {
                    pedirPermissaoMic.launch(Manifest.permission.RECORD_AUDIO)
                })
            }
        }
    }
}

@Composable
fun TelaChat(
    viewModel: SuzukiViewModel,
    temPermissaoMic: () -> Boolean,
    pedirPermissao: () -> Unit,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var textoInput by remember { mutableStateOf("") }
    var mostrarConfig by remember { mutableStateOf(!state.apiKeyConfigurada) }
    val listState = rememberLazyListState()

    LaunchedEffect(state.mensagens.size) {
        if (state.mensagens.isNotEmpty()) {
            listState.animateScrollToItem(state.mensagens.size - 1)
        }
    }

    if (mostrarConfig) {
        DialogoApiKey(
            onSalvar = { chave ->
                viewModel.salvarApiKey(chave)
                mostrarConfig = false
            },
            onFechar = { mostrarConfig = false },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Suzuki", fontWeight = FontWeight.Medium) },
                actions = {
                    IconButton(onClick = { mostrarConfig = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Configurações")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize(),
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 12.dp),
            ) {
                items(state.mensagens) { msg -> BolhaMensagem(msg) }

                if (state.processando) {
                    item {
                        Text(
                            "Suzuki tá digitando...",
                            color = Color.Gray,
                            modifier = Modifier.padding(start = 4.dp),
                        )
                    }
                }
            }

            state.erro?.let { erro ->
                Text(
                    erro,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }

            LinhaEntrada(
                texto = textoInput,
                onTextoMudou = { textoInput = it },
                onEnviar = {
                    if (textoInput.isNotBlank()) {
                        viewModel.enviarTexto(textoInput.trim())
                        textoInput = ""
                    }
                },
                gravando = state.gravando,
                onIniciarGravacao = {
                    if (temPermissaoMic()) viewModel.iniciarGravacao() else pedirPermissao()
                },
                onPararGravacao = { viewModel.pararGravacaoEEnviar() },
            )
        }
    }
}

@Composable
private fun BolhaMensagem(msg: ChatMessage) {
    val alinhamento = if (msg.deUsuario) Arrangement.End else Arrangement.Start
    val cor = if (msg.deUsuario) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val corTexto = if (msg.deUsuario) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant

    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = alinhamento) {
        Box(
            modifier = Modifier
                .background(cor, RoundedCornerShape(14.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .widthIn(max = 280.dp),
        ) {
            Text(msg.texto, color = corTexto)
        }
    }
}

@Composable
private fun LinhaEntrada(
    texto: String,
    onTextoMudou: (String) -> Unit,
    onEnviar: () -> Unit,
    gravando: Boolean,
    onIniciarGravacao: () -> Unit,
    onPararGravacao: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = texto,
            onValueChange = onTextoMudou,
            modifier = Modifier.weight(1f),
            placeholder = { Text("Manda uma mensagem pra Suzuki...") },
            maxLines = 4,
        )

        Spacer(Modifier.width(6.dp))

        // Segura pra gravar, solta pra enviar — igual áudio de WhatsApp.
        val corMic = if (gravando) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        Box(
            modifier = Modifier
                .size(48.dp)
                .background(corMic, RoundedCornerShape(24.dp))
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            onIniciarGravacao()
                            tryAwaitRelease()
                            onPararGravacao()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Mic, contentDescription = "Segurar para falar", tint = Color.White)
        }

        Spacer(Modifier.width(6.dp))

        IconButton(onClick = onEnviar, enabled = texto.isNotBlank()) {
            Icon(Icons.Filled.Send, contentDescription = "Enviar")
        }
    }
}

@Composable
private fun DialogoApiKey(onSalvar: (String) -> Unit, onFechar: () -> Unit) {
    var chave by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onFechar,
        title = { Text("Chave da API Groq") },
        text = {
            Column {
                Text(
                    "Cole aqui sua chave gratuita da Groq (console.groq.com/keys). " +
                        "Ela fica salva só no seu celular.",
                    color = Color.Gray,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = chave,
                    onValueChange = { chave = it },
                    placeholder = { Text("gsk_...") },
                    singleLine = true,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSalvar(chave) }, enabled = chave.isNotBlank()) {
                Text("Salvar")
            }
        },
        dismissButton = {
            TextButton(onClick = onFechar) { Text("Cancelar") }
        },
    )
}
