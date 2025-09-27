package ru.justartem.tools.sharetoobsidian

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.google.android.material.snackbar.Snackbar
import java.io.IOException
import java.net.URLDecoder
import androidx.core.widget.doAfterTextChanged

class SettingsActivity : AppCompatActivity() {

    private val REQUEST_VAULT_ROOT = 1001

    private lateinit var tvVaultRootPath: TextView
    private lateinit var editRelativeFolder: TextInputEditText
    private lateinit var editTags: TextInputEditText
    private lateinit var tvTags: TextView
//    private lateinit var editAttachmentsFolder: TextInputEditText

    private lateinit var btnSaveSettings: Button
    private lateinit var tvStatus: TextView

    private var vaultRootPathCurrent: String? = null

    private val sharedPreferences by lazy {
        getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)

        tvVaultRootPath = findViewById(R.id.tvVaultRootPath)
        editRelativeFolder = findViewById(R.id.editRelativeFolder)
        editTags = findViewById(R.id.editTags)
        tvTags = findViewById(R.id.tvTags)
//        editAttachmentsFolder = findViewById(R.id.editAttachmentsFolder)
        btnSaveSettings = findViewById(R.id.btnSaveSettings)
        tvStatus = findViewById(R.id.tvStatus)

        val btnSelectVaultRoot = findViewById<Button>(R.id.btnSelectVaultRoot)

        // Загружаем сохранённые настройки
        loadSettings()

        btnSelectVaultRoot.setOnClickListener {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            }
            startActivityForResult(intent, REQUEST_VAULT_ROOT)
        }

        btnSaveSettings.setOnClickListener {
            saveSettings()
        }
        editTags.doAfterTextChanged { text ->
            handleTextChangeEditTags(text?.toString() ?: "")
        }
    }

    private fun handleTextChangeEditTags(tags: String) {
        if (tags.isNotEmpty()) {
            tvTags.text = "Будет назначаться тег: $tags/[хост сайта-источника]"
        } else {
            tvTags.text = "Тег не будет назначаться"
        }
    }

    private fun loadSettings() {
        val vaultRoot = sharedPreferences.getString("obsidian_vault_path", null)
        val relativeFolder = sharedPreferences.getString("save_folder_relative", "Ссылки")
        val tags = sharedPreferences.getString("save_tags", "ссылки")
//        val attachmentsFolder = sharedPreferences.getString("save_attachments_folder", "__вложения")

        if (vaultRoot != null) {
            tvVaultRootPath.text = URLDecoder.decode(vaultRoot, "UTF-8")
            vaultRootPathCurrent = vaultRoot
        }

        editRelativeFolder.setText(relativeFolder)
        editTags.setText(tags)
//        editAttachmentsFolder.setText(attachmentsFolder)
        handleTextChangeEditTags(tags.toString())
    }

    private fun saveSettings() {
//        val vaultRootPath = Uri.encode(tvVaultRootPath.text.toString())
        val relativeFolder = editRelativeFolder.text?.toString()?.trim() ?: ""
        val tags = editTags.text?.toString()?.trim() ?: ""
//        val attachmentsFolder = editAttachmentsFolder.text?.toString()?.trim() ?: ""

        if (vaultRootPathCurrent == "Путь не выбран" || vaultRootPathCurrent.isNullOrEmpty()) {
            Toast.makeText(this, "Выберите корень хранилища Obsidian (Vault)", Toast.LENGTH_LONG).show()
            return
        }

        // Проверим доступ к папке
        val treeUri = Uri.parse(vaultRootPathCurrent)
        val rootDocument = DocumentFile.fromTreeUri(this, treeUri)
        if (rootDocument == null || !rootDocument.exists()) {
            Toast.makeText(this, "Не удалось получить доступ к папке хранилища Obsidian (Vault)", Toast.LENGTH_LONG).show()
            return
        }

        // Сохраняем
        sharedPreferences.edit()
            .putString("obsidian_vault_path", vaultRootPathCurrent)
            .putString("save_folder_relative", relativeFolder)
            .putString("save_tags", tags)
//            .putString("save_attachments_folder", attachmentsFolder)
            .apply()

        tvStatus.text = "Настройки сохранены"
        tvStatus.visibility = android.view.View.VISIBLE
        val rootView = findViewById<View>(android.R.id.content)
        Snackbar.make(rootView, "Настройки сохранены.\nТеперь можно делиться ссылками!", Snackbar.LENGTH_SHORT).show()

        //Toast.makeText(this, "Готово! Теперь можно делиться ссылками", Toast.LENGTH_SHORT).show()

        // Можно закрыть через пару секунд
        android.os.Handler(mainLooper).postDelayed({
            finish()
        }, 1500)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_VAULT_ROOT && resultCode == Activity.RESULT_OK) {
            data?.data?.let { treeUri ->
                // Сохраняем URI с постоянными правами
                contentResolver.takePersistableUriPermission(
                    treeUri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )

                tvVaultRootPath.text = URLDecoder.decode(treeUri.toString(),"UTF-8")
                vaultRootPathCurrent = treeUri.toString()

                // Обновляем отображение
                val documentFile = DocumentFile.fromTreeUri(this, treeUri)
                if (documentFile != null) {
                    Toast.makeText(this, "Vault выбран: ${documentFile.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}