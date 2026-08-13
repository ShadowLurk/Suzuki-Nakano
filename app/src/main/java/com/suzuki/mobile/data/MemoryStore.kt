package com.suzuki.mobile.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Memória permanente da Suzuki, salva num JSON dentro da pasta
 * privada do app (sobrevive a fechar o app; some só se desinstalar).
 * Estrutura espelha a versão desktop (memory/long_term.json +
 * memory/projects.py), simplificada:
 *
 * {
 *   "usuario": { "nome": "...", "gostos": ["..."], "fatos": ["..."] },
 *   "projetos": {
 *     "chave": { "nome_exibicao": "...", "detalhes": [...], "apelidos": [...] }
 *   }
 * }
 */
class MemoryStore(context: Context) {

    private val arquivo = File(context.filesDir, "memoria.json")

    // Palavras genéricas demais pra servir de "match" entre a fala
    // atual e um projeto (mesmo motivo do recall.py no desktop).
    private val stopwords = setOf(
        "aquele", "aquela", "aqueles", "aquelas", "sobre", "projeto",
        "então", "porque", "quando", "sempre", "nunca", "ainda", "tudo",
        "coisa", "coisas", "fazer", "fazendo", "estou", "você", "para",
        "isso", "essa", "esse", "essas", "esses", "onde", "como", "muito",
        "site", "lembra", "lembro", "sabe", "sabia",
    )

    @Synchronized
    private fun carregar(): JSONObject {
        if (!arquivo.exists()) {
            val vazio = JSONObject().apply {
                put("usuario", JSONObject().apply {
                    put("nome", "")
                    put("gostos", JSONArray())
                    put("fatos", JSONArray())
                })
                put("projetos", JSONObject())
            }
            arquivo.writeText(vazio.toString())
            return vazio
        }
        return JSONObject(arquivo.readText())
    }

    @Synchronized
    private fun salvar(memoria: JSONObject) {
        arquivo.writeText(memoria.toString())
    }

    // ---------- Usuário ----------

    fun getNome(): String = carregar().getJSONObject("usuario").optString("nome", "")

    fun setNome(nome: String) {
        val m = carregar()
        m.getJSONObject("usuario").put("nome", nome.trim())
        salvar(m)
    }

    fun addGosto(texto: String) {
        val m = carregar()
        val gostos = m.getJSONObject("usuario").getJSONArray("gostos")
        val existentes = (0 until gostos.length()).map { gostos.getString(it) }
        if (texto.trim() !in existentes) gostos.put(texto.trim())
        salvar(m)
    }

    fun addFato(texto: String) {
        val m = carregar()
        val fatos = m.getJSONObject("usuario").getJSONArray("fatos")
        val existentes = (0 until fatos.length()).map { fatos.getString(it) }
        if (texto.trim() !in existentes) fatos.put(texto.trim())
        salvar(m)
    }

    // ---------- Projetos (com apelidos, pra reconhecer referências indiretas) ----------

    fun addDetalheProjeto(nomeProjeto: String, detalhe: String) {
        val m = carregar()
        val projetos = m.getJSONObject("projetos")
        val chave = nomeProjeto.trim().lowercase()

        val projeto = if (projetos.has(chave)) {
            projetos.getJSONObject(chave)
        } else {
            JSONObject().apply {
                put("nome_exibicao", nomeProjeto.trim())
                put("detalhes", JSONArray())
                put("apelidos", JSONArray())
            }.also { projetos.put(chave, it) }
        }

        val detalhes = projeto.getJSONArray("detalhes")
        val existentes = (0 until detalhes.length()).map { detalhes.getString(it) }
        if (detalhe.trim() !in existentes) detalhes.put(detalhe.trim())

        salvar(m)
    }

    fun addApelidoProjeto(nomeProjeto: String, apelido: String) {
        val m = carregar()
        val projetos = m.getJSONObject("projetos")
        val chave = nomeProjeto.trim().lowercase()
        if (!projetos.has(chave)) return

        val projeto = projetos.getJSONObject(chave)
        val apelidos = projeto.getJSONArray("apelidos")
        val existentes = (0 until apelidos.length()).map { apelidos.getString(it) }
        val apNorm = apelido.trim().lowercase()
        if (apNorm.isNotEmpty() && apNorm !in existentes) apelidos.put(apNorm)

        salvar(m)
    }

    private fun palavrasRelevantes(texto: String): Set<String> =
        texto.lowercase()
            .split(Regex("[^a-zà-ú0-9]+"))
            .filter { it.length >= 4 && it !in stopwords }
            .toSet()

    /**
     * Monta o bloco de memória relevante pra injetar no prompt,
     * dado o texto da fala atual. Equivalente simplificado do
     * memory/recall.py do desktop: nome + gostos/fatos mais fortes +
     * qualquer projeto cujo nome/apelido/detalhes batam com a fala.
     */
    fun recall(textoAtual: String): String {
        val m = carregar()
        val usuario = m.getJSONObject("usuario")
        val linhas = mutableListOf<String>()

        val nome = usuario.optString("nome", "")
        if (nome.isNotBlank()) linhas += "- o nome do usuário é $nome"

        val gostos = usuario.getJSONArray("gostos")
        for (i in 0 until minOf(gostos.length(), 4)) {
            linhas += "- gosta de ${gostos.getString(i)}"
        }

        val fatos = usuario.getJSONArray("fatos")
        for (i in 0 until minOf(fatos.length(), 4)) {
            linhas += "- ${fatos.getString(i)}"
        }

        val palavrasTexto = palavrasRelevantes(textoAtual)
        val projetos = m.getJSONObject("projetos")
        val chaves = projetos.keys()

        while (chaves.hasNext()) {
            val chave = chaves.next()
            val projeto = projetos.getJSONObject(chave)
            val nomeExib = projeto.getString("nome_exibicao")
            val nomeNorm = nomeExib.lowercase()

            val apelidosArr = projeto.getJSONArray("apelidos")
            val apelidos = (0 until apelidosArr.length()).map { apelidosArr.getString(it) }

            val detalhesArr = projeto.getJSONArray("detalhes")
            val detalhes = (0 until detalhesArr.length()).map { detalhesArr.getString(it) }

            var bateu = nomeNorm.isNotBlank() && textoAtual.lowercase().contains(nomeNorm)
            bateu = bateu || apelidos.any { textoAtual.lowercase().contains(it) }

            if (!bateu) {
                val referencias = (listOf(nomeNorm) + apelidos + detalhes.take(3)).joinToString(" ")
                val palavrasProjeto = palavrasRelevantes(referencias)
                bateu = palavrasTexto.intersect(palavrasProjeto).isNotEmpty()
            }

            if (bateu && detalhes.isNotEmpty()) {
                linhas += "Detalhes que o usuário já explicou sobre o projeto \"$nomeExib\":"
                linhas += detalhes.map { "- $it" }
            }
        }

        return linhas.joinToString("\n")
    }
}
