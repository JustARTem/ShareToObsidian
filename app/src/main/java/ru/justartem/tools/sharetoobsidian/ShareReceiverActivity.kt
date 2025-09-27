package ru.justartem.tools.sharetoobsidian

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
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
import java.net.HttpURLConnection
import java.net.URL
import android.graphics.Color
import android.graphics.PorterDuff
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.graphics.Typeface
import android.view.Gravity
import androidx.core.view.isVisible

class ShareReceiverActivity : AppCompatActivity() {

    private var webView: WebView? = null
    private var urlToProcess: String? = null
    private val TAG = "ShareReceiverActivity"
    private lateinit var rootView: FrameLayout
    private lateinit var progressBar: ProgressBar
    private lateinit var statusText: TextView

    private val sharedPreferences by lazy {
        getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        //  интерфейс для информации о загрузке
        rootView = FrameLayout(this).apply {
//            setBackgroundColor(Color.BLACK(alpha = 0.8f).toArgb())
//            setBackgroundColor(Color.BLACK)
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }

        progressBar = ProgressBar(this).apply {
            isIndeterminate = true
//            indeterminateDrawable.setColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN)
            indeterminateDrawable.setColorFilter(Color.GRAY, PorterDuff.Mode.SRC_IN)
            setPadding(48, 24, 48, 24)
        }
        container.addView(progressBar)
        statusText = TextView(this).apply {
            text = "Загрузка контента..."
//            setTextColor(Color.WHITE)
            textSize = 16f
            typeface = Typeface.DEFAULT_BOLD
            setPadding(48, 24, 48, 24)
        }
        container.addView(statusText)

        rootView.addView(container)
        setContentView(rootView)

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
        Log.d(TAG, "Начинаем парсить контент tryJsoup(): $url")
        runOnUiThread { statusText.text = "Загрузка контента...." }
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
                    val image = extractImage(doc)
                    Log.d(TAG, "Контент получен:\n$title\n$description\n$image")
                    runOnUiThread { saveToFile(title, description, image, url) }
                } else {
                    Log.w(TAG, "Контент пустой, запускаем WebView")
                    runOnUiThread {
                        statusText.text = "Загрузка через WebView..."
                        setupWebView(url)
                    }
                }

            } catch (e: Exception) {
                Log.w(TAG, "Контент парсится с ошибками, запускаем WebView", e)
                runOnUiThread {
                    statusText.text = "Ошибка сети. Загрузка через WebView..."
                    setupWebView(url)
                }
            }
        }.start()
    }

    /**
     * Парсит заголовок веб-страницы
     */
    private fun extractTitle(doc: Document): String {
        return doc.select("h1").firstOrNull()?.text()?.trim()
            ?.ifEmpty { doc.title().trim() }
            ?.ifEmpty { doc.select("meta[property='og:title']").attr("content").trim() }
            ?: ""
    }

    /**
     * Парсит описание веб-страницы
     */
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

    /**
     * Парсит картинку веб-страницы
     */
    private fun extractImage(doc: Document): String {
        Log.d(TAG, "extractImage()...\n$doc")
        return doc.select("meta[itemprop='image']").attr("content")
            .ifEmpty { doc.select("meta[property='og:image']").attr("content") }
            .ifEmpty { doc.select("meta[name='image']").attr("content") }
            .ifEmpty { doc.select("meta[name='twitter:image']").attr("content") }
            .ifEmpty { doc.select("link[rel='image_src']").attr("href") }
            ?: ""
    }

    /**
     * Парсит ключевые слова веб-страницы
     */
    private fun extractKeywords(doc: Document): String {
        Log.d(TAG, "extractKeywords()...\n$doc")
        return doc.select("meta[name='keywords']").attr("content")
            ?: ""
    }

    /**
     * Парсит контент веб-страницы через WebView, если она генерируется через JS
     */
    private fun setupWebView(url: String) {
        Log.d(TAG, "setupWebView: $url")
        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"

            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                    super.onPageFinished(view, loadedUrl)

                    evaluateJavascript(
                        """
                        (function() {
                            const h1 = document.querySelector('h1');
                            const title = h1 ? h1.innerText.trim() : document.title.trim();
                            const metaDesc = document.querySelector('meta[name="description"]')?.getAttribute('content') ||
                                             document.querySelector('meta[property="og:description"]')?.getAttribute('content') ||
                                             '';
                            const bodyText = document.body.innerText || '';
                            const preview = bodyText.substring(0, 300).trim().replace(/\s+/g, ' ');
                            const imageUrl = document.querySelector('meta[property="og:image"]')?.getAttribute('content') ||
                                             document.querySelector('meta[name="image"]')?.getAttribute('content') ||
                                             document.querySelector('meta[name="twitter:image"]')?.getAttribute('content') ||
                                             document.querySelector('link[rel="image_src"]')?.getAttribute('href') ||
                                             '';
                            return JSON.stringify({ title: title || 'Без названия', description: metaDesc || preview, image: imageUrl });
                        })();
                    """
                    ) { result ->
                        try {
                            Log.d(TAG, "setupWebView(), onPageFinished, result:\n$result")
                            val cleaned = result.replace("^\"|\"$".toRegex(), "").replace("\\\"", "\"")
                            Log.d(TAG, "setupWebView(), onPageFinished, cleaned result:\n$cleaned")
                            val json = JSONObject(cleaned)
                            Log.d(TAG, "setupWebView(), onPageFinished, cleaned result json:\n$json.toString(2)")
                            val title = json.getString("title")
                            val description = json.getString("description")
                            val image = json.getString("image")
                            runOnUiThread {
                                saveToFile(title, description, image, url)
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                saveToFile("Без названия", "Не удалось извлечь контент", "", url)
                            }
                        }
                        cleanupWebView()
                    }
                }

                override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                    saveToFile("Ошибка загрузки", "Не удалось загрузить страницу", "", url)
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

    /**
     * Скачивает картинки
     */
    private fun downloadImage(imageUrl: String): Bitmap? {
        return try {
            val connection = URL(imageUrl).openConnection() as HttpURLConnection
            connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            val inputStream = connection.inputStream
            val bitmap = BitmapFactory.decodeStream(inputStream)
            inputStream.close()
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Уменьшает картинку
     */
    private fun resizeImage(bitmap: Bitmap, maxWidth: Int, maxHeight: Int): Bitmap {
        var width = bitmap.width
        var height = bitmap.height

        val ratio = width.toFloat() / height.toFloat()
        if (width > height) {
            width = maxWidth
            height = (width / ratio).toInt()
        } else {
            height = maxHeight
            width = (height * ratio).toInt()
        }

        return Bitmap.createScaledBitmap(bitmap, width, height, true)
    }

    /**
     * Сохраняет картинку в WebP
     */
    private fun saveWebPImage(bitmap: Bitmap, parentDir: DocumentFile, fileName: String) {
        val file = parentDir.createFile("image/webp", fileName) ?: return

        contentResolver.openOutputStream(file.uri)?.use { output ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSY, 80, output)
            } else {
                bitmap.compress(Bitmap.CompressFormat.WEBP, 80, output)
            }
        }
    }

    /**
     * Сохраняет файл-markdown-заметку в Obsidian
     */
    private fun saveToFile(title: String, description: String, image: String, url: String) {
        Log.d(TAG, "saveToFile()...\ntitle:$title\ndescription:$description\nimage:$image\nurl: $url")
        runOnUiThread { statusText.text = "Контент получен. Сохраняем..." }
        Thread {
            try {
                // Получаем настройки
                val vaultRootUriStr = sharedPreferences.getString("obsidian_vault_path", null)
                val relativeSubfolder = sharedPreferences.getString("save_folder_relative", "").orEmpty()
                var tags = sharedPreferences.getString("save_tags", "").orEmpty()

                // Для сохранения картинки как вложения
//                var saveAttachments = sharedPreferences.getBoolean("save_attachments", true)
//                var relativeAttachmentsFolder = sharedPreferences.getString("save_attachments_folder", "")
//                var attachmentImageSize = sharedPreferences.getInt("save_attachments_image_size", 500)
//                Log.d(
//                    TAG,
//                    "Настройки: \n$vaultRootUriStr\n$relativeSubfolder\n$tags\n$saveAttachments\n$relativeAttachmentsFolder\n$attachmentImageSize"
//                )

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
                        Toast.makeText(
                            this,
                            "Vault некорректно настроен (пустое название). проверьте настройки.",
                            Toast.LENGTH_LONG
                        ).show()
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

                // Для сохранения картинки как вложения
                // Если в настройках включено сохранение картинки и есть картинка, сохраняем ее
                var imageFileName: String? = null
                var imageMarkdown = ""

//                Log.d(TAG, "saveAttachments: " + (if (saveAttachments) "Включено" else "Выключено"))
//                Log.d(TAG, "image: " + (if (image.isNotEmpty()) image else "--"))
//                Log.d(
//                    TAG,
//                    "relativeAttachmentsFolder: " + (if (relativeAttachmentsFolder.isNullOrEmpty()) relativeAttachmentsFolder else "--")
//                )
//                if (
//                    saveAttachments &&
//                    !relativeAttachmentsFolder.isNullOrEmpty() &&
//                    image.isNotEmpty()
//                    ) {
//                    // Создаём папку __вложения
//                    val attachmentsDir = parentDir.findOrCreateDirectory(relativeAttachmentsFolder)
////                    ?: throw IOException("Не удалось создать папку __вложения")
//                    if (attachmentsDir == null || !attachmentsDir.canWrite()) {
//                        Log.d(TAG, "Картинка не может быть сохранена, нет доступа к папке: $relativeSubfolder")
//                        runOnUiThread {
//                            Toast.makeText(
//                                this,
//                                "Картинка не может быть сохранена, нет доступа к папке: $relativeSubfolder",
//                                Toast.LENGTH_LONG
//                            ).show()
//                        }
////                        runOnUiThread { finish() }
////                        return@Thread
//                    } else {
//
//                        Log.d(TAG, "Сохраняем изображение ")
//                        // Сохраняем изображение (если есть)
//                        try {
//                            val downloadedImage = downloadImage(image)
//                            if (downloadedImage != null) {
//                                Log.d(
//                                    TAG,
//                                    "Изображение скачано: " + downloadedImage.width + "x" + downloadedImage.height
//                                )
//                                val resizedImage =
//                                    resizeImage(downloadedImage, attachmentImageSize, attachmentImageSize)
//                                Log.d(
//                                    TAG,
//                                    "Изображение уменьшено: " + resizedImage.width + "x" + downloadedImage.height
//                                )
//                                imageFileName = if (fileName.endsWith(".md")) {
//                                    fileName.substringBeforeLast(".md")
//                                } else {
//                                    fileName
//                                } + ".webp"
//                                saveWebPImage(resizedImage, attachmentsDir, imageFileName)
//                                Log.d(TAG, "Изображение сохранено как $imageFileName")
//                                resizedImage.recycle()
//                            }
//                        } catch (e: Exception) {
//                            Log.d(TAG, "Ошибка при сохранении изображении: " + e.message)
//                            e.printStackTrace()
//                        }
//
//                    }
//                }

                val documentFile = parentDir.createFile("text/markdown", fileName)
                    ?: throw IOException("Не удалось создать файл")

                // Финальное имя (может быть с (1), (2))
                val finalFileName = documentFile.name ?: fileName


                // Содержимое
                val now = LocalDateTime.now()
                val formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
                val timestamp = now.format(formatter)

                // Для сохраненной картинки
//                imageMarkdown = if (!imageFileName.isNullOrEmpty()) {
//                    "\n![[${imageFileName}]]\n"
//                } else {
//                    ""
//                }

                // Добавляем картинку с внешним URL
                if (image.isNotEmpty()) {
                    imageMarkdown = "![](${image})"
                }


                val shortHost = getShortHost(url)
                if (tags.isNotEmpty() && shortHost.isNotEmpty()) {
                    tags += "/" + shortHost.replace(
                        ".",
                        "∙"
                    ) // в Obsidian теги не могут содержать точки, поэтому заменяем на U+2219 (BULLET OPERATOR	маленькая точка по центру)
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

$imageMarkdown
                """.trimIndent()

                // Сохраняем
                contentResolver.openOutputStream(documentFile.uri)?.use { output ->
                    output.write(markdown.toByteArray())
                }

                // Открываем в Obsidian
                runOnUiThread {
                    showSaveSuccess(documentFile, relativeSubfolder, finalFileName, vaultName)
                }

            } catch (e: Exception) {
                e.printStackTrace()
                runOnUiThread {
                    statusText.text = "Ошибка сохранения: ${e.message}"
                    Toast.makeText(this, "Ошибка сохранения: ${e.message}", Toast.LENGTH_LONG).show()
                    finish()
                }
            }
        }.start()
    }

    private fun showSaveSuccess(file: DocumentFile, subfolder: String, finalFileName: String, vaultName: String) {
        val fileNameWithoutExt = finalFileName.replace(".md", "")
        val folderPart = if (subfolder.isEmpty()) "" else "$subfolder/"
        val relativePath = "${folderPart}${fileNameWithoutExt}".trim('/')

        val encodedPath = Uri.encode(relativePath)
//        val encodedAbsPath = file.uri.encodedPath
        val vault = sharedPreferences.getString("obsidian_vault_path", null)

        val intent = Intent(Intent.ACTION_VIEW).apply {
//            data = Uri.parse("obsidian://open?file=$encodedPath") //открыть по пути относительно хранилища (работает криво, если несколько хранилищ)
//            data = Uri.parse("obsidian://open?path=$encodedAbsPath") //открыть по абсолютному пути - не работает
            data =
                Uri.parse("obsidian://open?vault=$vaultName&file=$encodedPath") //открыть по пути относительно хранилища с указанием имени хранилища. Работает нормально

            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val rootView = findViewById<View>(android.R.id.content)

//        Snackbar.make(rootView, "Сохранено: $finalFileName\n encodedAbsPath: $encodedAbsPath", Snackbar.LENGTH_INDEFINITE)
        statusText.text = "Сохранено:\n\n$finalFileName \n\nв хранилище \"$vaultName\""
        progressBar.isVisible = false

//        Snackbar.make(rootView, "Сохранено: $finalFileName \nв хранилище \"$vaultName\"", Snackbar.LENGTH_INDEFINITE)
        Snackbar.make(rootView, "Сохранено: $finalFileName", Snackbar.LENGTH_INDEFINITE)
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

