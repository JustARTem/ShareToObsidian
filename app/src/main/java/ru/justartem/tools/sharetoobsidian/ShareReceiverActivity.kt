package ru.justartem.tools.sharetoobsidian

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.snackbar.Snackbar
import org.json.JSONObject
import androidx.coordinatorlayout.widget.CoordinatorLayout
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.io.IOException
import java.net.URL

class ShareReceiverActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var urlToProcess: String? = null

    private val sharedPreferences by lazy {
        getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        if (Intent.ACTION_SEND == intent.action && intent.type == "text/plain") {
            urlToProcess = intent.getStringExtra(Intent.EXTRA_TEXT)
            if (urlToProcess?.startsWith("http") == true) {
                tryJsoup(urlToProcess!!)
            } else {
                showError("Некорректная ссылка")
                finish()
            }
        } else {
            finish()
        }
    }

    private fun tryJsoup(url: String) {
        Thread {
            try {
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .timeout(10000)
                    .followRedirects(true)
                    .get()

                val title = extractTitle(doc)
                if (title.isNotEmpty()) {
                    val description = extractDescription(doc)
                    runOnUiThread { saveToFile(title, description, url) }
                } else {
                    runOnUiThread { setupWebView(url) }
                }

            } catch (e: Exception) {
                runOnUiThread { setupWebView(url) }
            }
        }.start()
    }

    private fun extractTitle(doc: Document): String {
        return doc.select("h1").firstOrNull()?.text()?.trim()
            ?.ifEmpty { doc.title().trim() }
            ?.ifEmpty { doc.select("meta[property='og:title']").attr("content").trim() }
            ?: ""
    }

    private fun extractDescription(doc: Document): String {
        return doc.select("meta[name='description']").attr("content")
            .ifEmpty { doc.select("meta[property='og:description']").attr("content") }
            .ifEmpty {
                doc.select("p, div, article, section")
                    .filter { !it.select("h1, h2, h3").isNotEmpty() } // исключаем заголовки
                    .filter { it.text().length in 50..500 }
                    .firstOrNull()?.text()?.trim()?.take(300)
                    ?: "Нет описания"
            }
    }

    private fun setupWebView(url: String) {
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)

                    evaluateJavascript("""
                        (function() {
                            const h1 = document.querySelector('h1');
                            const title = h1 ? h1.innerText.trim() : document.title.trim();
                            const metaDesc = document.querySelector('meta[name="description"]')?.getAttribute('content') ||
                                             document.querySelector('meta[property="og:description"]')?.getAttribute('content') ||
                                             '';
                            const bodyText = document.body.innerText || '';
                            const preview = bodyText.substring(0, 300).trim().replace(/\s+/g, ' ');
                            return JSON.stringify({ title: title || 'Без названия', description: metaDesc || preview });
                        })();
                    """) { result ->
                        try {
                            val cleaned = result.replace("^\"|\"$".toRegex(), "").replace("\\\"", "\"")
                            val json = JSONObject(cleaned)
                            val title = json.getString("title")
                            val description = json.getString("description")
                            runOnUiThread {
                                saveToFile(title, description, url)
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                saveToFile("Без названия", "Не удалось извлечь контент", url)
                            }
                        }
                        cleanupWebView()
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    saveToFile("Ошибка загрузки", "Не удалось загрузить страницу", url)
                    cleanupWebView()
                }
            }
        }


        addContentView(webView, ViewGroup.LayoutParams(1, 1))
        webView?.loadUrl(url)
    }

    private fun cleanupWebView() {
        runOnUiThread {
            webView?.stopLoading()
//            @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
//            webView?.webViewClient = null
              webView?.webViewClient = WebViewClient() // пустой клиент
            webView?.loadData("", "text/html", "utf-8")
            webView = null
        }
    }

    private fun saveToFile(title: String, description: String, url: String) {
        Thread {
            try {
                // Получаем настройки
                val vaultRootUriStr = sharedPreferences.getString("obsidian_vault_path", null)
                val relativeSubfolder = sharedPreferences.getString("save_folder_relative", "").orEmpty()

                if (vaultRootUriStr == null) {
                    runOnUiThread {
                        Toast.makeText(this, "Vault не настроен. Установите в настройках.", Toast.LENGTH_LONG).show()
                    }
                    runOnUiThread { finish() }
                    return@Thread
                }

                val vaultRootUri = Uri.parse(vaultRootUriStr)
                var parentDir = DocumentFile.fromTreeUri(this, vaultRootUri)
//                val vaultName = vaultRootUri.lastPathSegment // Название хранилища
                val vaultName = parentDir?.name // Название хранилища
                if (vaultName.isNullOrEmpty()) {
                    runOnUiThread {
                        Toast.makeText(this, "Vault некорректно настроен (пустое название). проверьте настройки.", Toast.LENGTH_LONG).show()
                    }
                    runOnUiThread { finish() }
                    return@Thread
                }

                // Переходим в подпапку (например, /Clips)
                if (relativeSubfolder.isNotEmpty()) {
                    parentDir = parentDir?.findOrCreateDirectory(relativeSubfolder)
                }

                if (parentDir == null || !parentDir.canWrite()) {
                    runOnUiThread {
                        Toast.makeText(this, "Нет доступа к папке: $relativeSubfolder", Toast.LENGTH_LONG).show()
                    }
                    runOnUiThread { finish() }
                    return@Thread
                }

                // Генерация имени
                val fileName = generateFileName(title, url)

                val documentFile = parentDir.createFile("text/markdown", fileName)
                    ?: throw IOException("Не удалось создать файл")

                // Финальное имя (может быть с (1), (2))
                val finalFileName = documentFile.name ?: fileName

                // Содержимое
                val now = LocalDateTime.now()
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                val timestamp = now.format(formatter)

                var tags = "ссылка" //TODO: сделать установку названия тега в настройках
                val shortHost = getShortHost(url)
                if (shortHost.isNotEmpty()) {
                    tags += "/"+shortHost.replace(".", "∙") // в Obsidian теги не могут содержать точки, поэтому заменяем на U+2219 (BULLET OPERATOR	маленькая точка по центру)
                }
                val titleFixed = title.replace(":", "∶") // U+2236 - наиболее похожий

                val markdown = """
                    ---
                    title: $titleFixed
                    created: $timestamp
                    source: $url
                    tags: $tags
                    ---
                    
                    $description

                """.trimIndent()

                // Сохраняем
                contentResolver.openOutputStream(documentFile.uri)?.use { output ->
                    output.write(markdown.toByteArray())
                }

                // Открываем в Obsidian
                runOnUiThread {
                    showSaveSuccess(documentFile, relativeSubfolder, finalFileName,vaultName)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun showSaveSuccess(file: DocumentFile, subfolder: String, finalFileName: String,vaultName: String) {
        val fileNameWithoutExt = finalFileName.replace(".md", "")
        val folderPart = if (subfolder.isEmpty()) "" else "$subfolder/"
        val relativePath = "${folderPart}${fileNameWithoutExt}".trim('/')

        val encodedPath = Uri.encode(relativePath)
//        val encodedAbsPath = file.uri.encodedPath
        val vault = sharedPreferences.getString("obsidian_vault_path", null)

        val intent = Intent(Intent.ACTION_VIEW).apply {
//            data = Uri.parse("obsidian://open?file=$encodedPath") //открыть по пути относительно хранилища (работает криво, если несколько хранилищ)
//            data = Uri.parse("obsidian://open?path=$encodedAbsPath") //открыть по абсолютному пути - не работает
            data = Uri.parse("obsidian://open?vault=$vaultName&file=$encodedPath") //открыть по пути относительно хранилища с указанием имени хранилища. Работает нормально

            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val rootView = findViewById<View>(android.R.id.content)

//        Snackbar.make(rootView, "Сохранено: $finalFileName\n encodedAbsPath: $encodedAbsPath", Snackbar.LENGTH_INDEFINITE)
        Snackbar.make(rootView, "Сохранено: $finalFileName \nв хранилище \"$vaultName\"", Snackbar.LENGTH_INDEFINITE)
            .setAction("Открыть") {
                if (intent.resolveActivity(packageManager) != null) {
                    try {
                        startActivity(intent)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(this, "Obsidian не установлен", Toast.LENGTH_SHORT).show()
                    } catch (e: Exception) {
                        Toast.makeText(this, "Ошибка при открытии", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this, "Obsidian не установлен", Toast.LENGTH_SHORT).show()
                }
                finish()
            }
            .setActionTextColor(ContextCompat.getColor(this, R.color.snackbar_text))
            .show()
    }

    private fun getShortHost(url: String): String {
        return try {
            val uri = URL(url)
            val host = uri.host
            val cleanHost = host.removePrefix("www.").removePrefix("m.").removePrefix("mobile.")
            val parts = cleanHost.split('.')
            var shortHost = cleanHost;
            if (parts.size >= 2) {
                shortHost = parts.takeLast(2).joinToString(".")
            }

            // Ограничиваем длину домена (например, до 20 символов)
            if (shortHost.length > 20) {
                shortHost = shortHost.substring(0, 20)
            }
            shortHost
        } catch (e: Exception) {
            ""
        }
    }

    private fun generateFileName(title: String, url: String): String {
        // Очищаем заголовок
        var cleanTitle = sanitizeFileName(title).trim()

        var shortHost = getShortHost(url)

        // Формируем имя: "Заголовок (host)"
        val fileName = if (cleanTitle.endsWith(".md")) {
            cleanTitle.substringBeforeLast(".md")
        } else {
            cleanTitle
        }

        val withHost = fileName + (if (shortHost.isNotEmpty()) " ($shortHost)" else "")

        // Ещё раз очищаем, на случай, если host добавил недопустимые символы
        return sanitizeFileName(withHost) + ".md"
    }

    private fun sanitizeFileName(name: String): String {
        // Удаляем или заменяем только недопустимые символы для имён файлов в Android/Windows/FAT/NTFS

        var cleaned = name
            .replace(":", "∶")
            .replace("\"", "'")
            .replace("*", "∙")
            .replace("?", "？")
//            .replace(".", "⋅")

        val illegalChars = Regex("[<>:\"/\\\\|?*\\x00-\\x1F]") // включая управляющие символы
        cleaned = cleaned.replace(illegalChars, "_")

        // Заменяем множественные пробелы и подчёркивания на одно подчёркивание
        cleaned = cleaned.replace(Regex("[ \\t]+"), " ")   // множественные пробелы → один пробел
        cleaned = cleaned.replace(Regex("_+"), "_")        // множественные `_` → одно

        cleaned = cleaned.trim(' ', '_') // Убираем пробелы и подчёркивания в начале и конце
        cleaned = cleaned.trimStart('.') // Убираем точки в начале

        // Заменяем пустое имя
        if (cleaned.isEmpty()) {
            val now = LocalDateTime.now()
            val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
            val timestamp = now.format(formatter)
            return timestamp
        }

        // Ограничиваем длину (Android обычно до 255 байт, но лучше 100 символов для надёжности)
        return if (cleaned.length > 150) cleaned.substring(0, 150) else cleaned
    }

    private fun showError(message: String) {
        runOnUiThread {
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    // Расширение: найти или создать директорию
    fun DocumentFile.findOrCreateDirectory(name: String): DocumentFile? {
        val existing = this.findFile(name)
        if (existing?.isDirectory == true) return existing

        val newDir = this.createDirectory(name)
        return newDir ?: this.findFile(name)
    }
}

