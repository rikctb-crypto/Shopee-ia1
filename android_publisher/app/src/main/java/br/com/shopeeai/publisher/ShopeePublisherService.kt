package br.com.shopeeai.publisher

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.Normalizer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class ShopeePublisherService : AccessibilityService() {
    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val fetching = AtomicBoolean(false)
    private var current: PublisherAd? = null
    private var phase = Phase.IDLE
    private var lastActionAt = 0L
    private var phaseStartedAt = 0L
    private var lastUiSignature = ""

    enum class Phase {
        IDLE,
        HOME,
        VIDEO_TAB,
        CREATE,
        PICKER,
        AFTER_PICK,
        PRODUCT,
        PRODUCT_SEARCH,
        PRODUCT_SELECT,
        CAPTION,
        PUBLISH,
        WAIT_RESULT
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        setStatus("Acessibilidade ativa. Aguardando anúncios prontos.")
        handler.post(pollRunnable)
    }

    override fun onInterrupt() {
        setStatus("Acessibilidade interrompida.")
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        io.shutdownNow()
        super.onDestroy()
    }

    private val pollRunnable = object : Runnable {
        override fun run() {
            tryFetchNext()
            handler.postDelayed(this, 5000)
        }
    }

    private fun tryFetchNext() {
        val prefs = getSharedPreferences("publisher", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("auto", false)) return
        if (!LicenseManager(this).isValid()) {
            setStatus("Licença de teste expirada ou relógio inválido.")
            return
        }
        if (current != null || fetching.getAndSet(true)) return

        io.execute {
            try {
                val client = BackendClient(this)
                val ad = client.claimNext()
                if (ad == null) {
                    setStatus("Automático ativo • aguardando vídeo pronto no PC.")
                    return@execute
                }
                setStatus("Baixando vídeo #${ad.id}: ${ad.productName.take(55)}")
                client.downloadVideo(ad)
                current = ad
                setPhase(Phase.HOME)
                setStatus("Vídeo #${ad.id} baixado • abrindo Shopee.")
                handler.post { launchShopee() }
            } catch (e: Exception) {
                setStatus("Erro ao buscar/baixar anúncio: ${e.message}")
            } finally {
                fetching.set(false)
            }
        }
    }

    private fun launchShopee() {
        val launch = packageManager.getLaunchIntentForPackage("com.shopee.br")
        if (launch == null) {
            failCurrent("Shopee Brasil não encontrada no aparelho")
            return
        }
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivity(launch)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val ad = current ?: return
        val root = rootInActiveWindow ?: return
        val pkg = event?.packageName?.toString() ?: root.packageName?.toString().orEmpty()
        val now = System.currentTimeMillis()

        if (now - phaseStartedAt > 90000L && phase != Phase.WAIT_RESULT) {
            failCurrent("Timeout no passo $phase. Tela atual: $pkg")
            return
        }
        if (now - lastActionAt < 700L) return

        if (pkg == "com.shopee.br") {
            driveShopee(root, ad)
        } else if (phase == Phase.PICKER && isMediaPickerPackage(pkg)) {
            drivePicker(root)
        }
    }

    private fun driveShopee(root: AccessibilityNodeInfo, ad: PublisherAd) {
        val screenText = collectText(root).joinToString(" | ")
        val sig = normalize(screenText).take(450)
        if (sig == lastUiSignature && System.currentTimeMillis() - lastActionAt < 1800L) return
        lastUiSignature = sig

        when (phase) {
            Phase.HOME -> {
                if (clickByAnyText(root, listOf("Shopee Video", "Vídeo", "Video"))) {
                    setPhase(Phase.VIDEO_TAB)
                    setStatus("#${ad.id} • entrando no Shopee Video")
                } else if (clickCreate(root)) {
                    setPhase(Phase.PICKER)
                    setStatus("#${ad.id} • abrindo seletor de vídeo")
                }
            }

            Phase.VIDEO_TAB -> {
                if (clickCreate(root)) {
                    setPhase(Phase.PICKER)
                    setStatus("#${ad.id} • selecionando vídeo")
                } else if (containsAny(root, listOf("Adicionar Produto", "Adicionar produto", "Publicar"))) {
                    setPhase(Phase.PRODUCT)
                }
            }

            Phase.CREATE -> {
                if (clickCreate(root)) setPhase(Phase.PICKER)
            }

            Phase.PICKER -> {
                // Algumas versões da Shopee usam seletor interno.
                if (clickByAnyText(root, listOf("Vídeos", "Videos"))) {
                    lastActionAt = System.currentTimeMillis()
                } else if (clickFirstMediaTile(root)) {
                    setPhase(Phase.AFTER_PICK)
                    setStatus("#${ad.id} • vídeo selecionado")
                }
            }

            Phase.AFTER_PICK -> {
                if (clickByAnyText(root, listOf("Próximo", "Avançar", "Next"))) {
                    setStatus("#${ad.id} • avançando edição")
                } else if (containsAny(root, listOf("Adicionar Produto", "Adicionar produto"))) {
                    setPhase(Phase.PRODUCT)
                } else if (containsAny(root, listOf("Publicar", "Postar"))) {
                    setPhase(Phase.PRODUCT)
                }
            }

            Phase.PRODUCT -> {
                if (clickByAnyText(root, listOf("Adicionar Produto", "Adicionar produto", "Adicionar produtos"))) {
                    setPhase(Phase.PRODUCT_SEARCH)
                    setStatus("#${ad.id} • vinculando produto")
                } else if (containsAny(root, listOf("Publicar", "Postar"))) {
                    // Se não há botão visível de produto, tenta legenda e publicação.
                    setPhase(Phase.CAPTION)
                }
            }

            Phase.PRODUCT_SEARCH -> {
                val edit = findEditable(root)
                if (edit != null && setText(edit, searchName(ad.productName))) {
                    setPhase(Phase.PRODUCT_SELECT)
                    setStatus("#${ad.id} • procurando produto na Shopee")
                    handler.postDelayed({ driveAgain() }, 2200)
                }
            }

            Phase.PRODUCT_SELECT -> {
                if (clickProductResult(root, ad.productName)) {
                    setStatus("#${ad.id} • produto localizado")
                    handler.postDelayed({ driveAgain() }, 1000)
                } else if (clickByAnyText(root, listOf("Adicionar", "Confirmar", "Concluir", "Feito"))) {
                    setPhase(Phase.CAPTION)
                } else if (containsAny(root, listOf("Publicar", "Postar"))) {
                    setPhase(Phase.CAPTION)
                }
            }

            Phase.CAPTION -> {
                // Primeiro tenta confirmar a seleção do produto, caso exista modal aberto.
                if (clickByAnyText(root, listOf("Adicionar", "Confirmar", "Concluir", "Feito"))) {
                    handler.postDelayed({ driveAgain() }, 1000)
                    return
                }
                val caption = buildCaption(ad)
                val edit = findCaptionEditable(root)
                if (edit != null) setText(edit, caption)
                if (clickByAnyText(root, listOf("Publicar", "Postar"))) {
                    setPhase(Phase.WAIT_RESULT)
                    setStatus("#${ad.id} • enviando publicação para a Shopee")
                    handler.postDelayed({ verifyPublished() }, 12000)
                }
            }

            Phase.PUBLISH -> {
                if (clickByAnyText(root, listOf("Publicar", "Postar"))) {
                    setPhase(Phase.WAIT_RESULT)
                    handler.postDelayed({ verifyPublished() }, 12000)
                }
            }

            Phase.WAIT_RESULT -> {
                if (containsAny(root, listOf("Publicado", "Publicação concluída", "Seu vídeo", "processando"))) {
                    completeCurrent()
                } else if (!containsAny(root, listOf("Publicar", "Postar")) &&
                    containsAny(root, listOf("Shopee Video", "Vídeo", "Video"))) {
                    completeCurrent()
                }
            }

            Phase.IDLE -> Unit
        }
    }

    private fun drivePicker(root: AccessibilityNodeInfo) {
        if (clickByAnyText(root, listOf("Vídeos", "Videos"))) return
        if (clickFirstMediaTile(root)) {
            setPhase(Phase.AFTER_PICK)
            current?.let { setStatus("#${it.id} • vídeo escolhido na galeria") }
        }
    }

    private fun verifyPublished() {
        if (phase != Phase.WAIT_RESULT || current == null) return
        val root = rootInActiveWindow
        if (root != null && root.packageName?.toString() == "com.shopee.br") {
            if (!containsAny(root, listOf("Publicar", "Postar"))) {
                completeCurrent()
                return
            }
        }
        failCurrent("Não foi possível confirmar a publicação automaticamente")
    }

    private fun completeCurrent() {
        val ad = current ?: return
        current = null
        setPhase(Phase.IDLE)
        io.execute {
            try {
                BackendClient(this).markPublished(ad.id)
                setStatus("✅ #${ad.id} publicado. Buscando o próximo...")
            } catch (e: Exception) {
                setStatus("Publicado, mas falhou ao confirmar no PC: ${e.message}")
            }
            handler.postDelayed({ tryFetchNext() }, 2500)
        }
    }

    private fun failCurrent(reason: String) {
        val ad = current ?: return
        current = null
        setPhase(Phase.IDLE)
        val requeue = ad.publishAttempts < 2
        io.execute {
            try { BackendClient(this).markFailed(ad.id, reason, requeue) } catch (_: Exception) {}
            setStatus("❌ #${ad.id}: $reason${if (requeue) " • será tentado novamente" else " • parado após 2 tentativas"}")
            handler.postDelayed({ tryFetchNext() }, 4000)
        }
    }

    private fun driveAgain() {
        val root = rootInActiveWindow ?: return
        current?.let { driveShopee(root, it) }
    }

    private fun setPhase(value: Phase) {
        phase = value
        phaseStartedAt = System.currentTimeMillis()
        lastUiSignature = ""
    }

    private fun setStatus(text: String) {
        getSharedPreferences("publisher", Context.MODE_PRIVATE).edit().putString("last_status", text).apply()
    }

    private fun isMediaPickerPackage(pkg: String): Boolean {
        val p = pkg.lowercase()
        return p.contains("documentsui") || p.contains("providers.media") || p.contains("gallery") || p.contains("photos")
    }

    private fun clickCreate(root: AccessibilityNodeInfo): Boolean {
        if (clickByAnyText(root, listOf("Criar", "Criar vídeo", "Adicionar vídeo", "Novo vídeo", "Publicar vídeo"))) return true
        val descriptions = listOf("criar", "adicionar", "create", "camera", "publicar")
        val node = findNode(root) { n ->
            val d = normalize(n.contentDescription?.toString().orEmpty())
            descriptions.any { d.contains(it) }
        }
        if (node != null) return clickNode(node)
        return false
    }

    private fun containsAny(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val wanted = labels.map { normalize(it) }
        return findNode(root) { n ->
            val t = normalize((n.text ?: n.contentDescription)?.toString().orEmpty())
            wanted.any { w -> t.contains(w) }
        } != null
    }

    private fun clickByAnyText(root: AccessibilityNodeInfo, labels: List<String>): Boolean {
        val wanted = labels.map { normalize(it) }
        val node = findNode(root) { n ->
            val t = normalize((n.text ?: n.contentDescription)?.toString().orEmpty())
            wanted.any { w -> t == w || t.contains(w) }
        } ?: return false
        return clickNode(node)
    }

    private fun clickProductResult(root: AccessibilityNodeInfo, productName: String): Boolean {
        val tokens = normalize(productName).split(" ").filter { it.length >= 4 }.take(5)
        if (tokens.isEmpty()) return false
        val node = findNode(root) { n ->
            val t = normalize(n.text?.toString().orEmpty())
            val hits = tokens.count { t.contains(it) }
            t.length > 8 && hits >= minOf(2, tokens.size)
        } ?: return false
        return clickNode(node)
    }

    private fun findEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? =
        findNode(root) { it.isEditable || it.className?.toString()?.contains("EditText") == true }

    private fun findCaptionEditable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val all = mutableListOf<AccessibilityNodeInfo>()
        walk(root) { n -> if (n.isEditable || n.className?.toString()?.contains("EditText") == true) all.add(n) }
        return all.lastOrNull()
    }

    private fun setText(node: AccessibilityNodeInfo, value: String): Boolean {
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value)
        }
        lastActionAt = System.currentTimeMillis()
        return node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        repeat(5) {
            if (n?.isClickable == true) {
                lastActionAt = System.currentTimeMillis()
                return n!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            n = n?.parent
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (!r.isEmpty) {
            tap(r.centerX().toFloat(), r.centerY().toFloat())
            return true
        }
        return false
    }

    private fun clickFirstMediaTile(root: AccessibilityNodeInfo): Boolean {
        val candidates = mutableListOf<Pair<AccessibilityNodeInfo, Rect>>()
        walk(root) { n ->
            val cls = n.className?.toString().orEmpty()
            val r = Rect(); n.getBoundsInScreen(r)
            if ((n.isClickable || cls.contains("Image", true) || cls.contains("Frame", true)) &&
                r.width() >= 120 && r.height() >= 120 && r.top > 180) {
                candidates.add(n to Rect(r))
            }
        }
        val chosen = candidates.sortedWith(compareBy<Pair<AccessibilityNodeInfo, Rect>> { it.second.top }.thenBy { it.second.left }).firstOrNull()
            ?: return false
        return clickNode(chosen.first)
    }

    private fun tap(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y) }
        val gesture = GestureDescription.Builder().addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build()
        lastActionAt = System.currentTimeMillis()
        dispatchGesture(gesture, null, null)
    }

    private fun findNode(root: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean): AccessibilityNodeInfo? {
        if (predicate(root)) return root
        for (i in 0 until root.childCount) {
            val c = root.getChild(i) ?: continue
            val found = findNode(c, predicate)
            if (found != null) return found
        }
        return null
    }

    private fun walk(root: AccessibilityNodeInfo, action: (AccessibilityNodeInfo) -> Unit) {
        action(root)
        for (i in 0 until root.childCount) root.getChild(i)?.let { walk(it, action) }
    }

    private fun collectText(root: AccessibilityNodeInfo): List<String> {
        val out = mutableListOf<String>()
        walk(root) { n ->
            n.text?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
            n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let(out::add)
        }
        return out
    }

    private fun normalize(value: String): String {
        val n = Normalizer.normalize(value, Normalizer.Form.NFD)
            .replace("\\p{Mn}+".toRegex(), "")
            .lowercase()
        return n.replace("[^a-z0-9 ]".toRegex(), " ").replace("\\s+".toRegex(), " ").trim()
    }

    private fun searchName(value: String): String {
        val stop = setOf("original", "oficial", "novo", "nova", "promocao", "oferta", "frete", "gratis", "kit")
        return normalize(value).split(" ").filter { it.length >= 3 && it !in stop }.take(7).joinToString(" ")
    }

    private fun buildCaption(ad: PublisherAd): String {
        val parts = listOf(ad.copy.trim(), ad.hashtags.trim()).filter { it.isNotBlank() }
        return parts.joinToString("\n\n").take(2000)
    }
}
