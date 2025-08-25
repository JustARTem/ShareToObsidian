package ru.justartem.tools.sharetoobsidian

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.textfield.TextInputEditText
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.io.IOException

class SettingsActivity : AppCompatActivity() {

    private val REQUEST_VAULT_ROOT = 1001

    private lateinit var tvVaultRootPath: TextView
    private lateinit var editRelativeFolder: TextInputEditText
    private lateinit var btnSaveSettings: Button
    private lateinit var tvStatus: TextView

    private val sharedPreferences by lazy {
        getSharedPreferences("AppSettings", Context.MODE_PRIVATE)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.settings_activity)

        tvVaultRootPath = findViewById(R.id.tvVaultRootPath)
        editRelativeFolder = findViewById(R.id.editRelativeFolder)
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
    }

    private fun loadSettings() {
        val vaultRoot = sharedPreferences.getString("obsidian_vault_path", null)
        val relativeFolder = sharedPreferences.getString("save_folder_relative", "")

        if (vaultRoot != null) {
            tvVaultRootPath.text = vaultRoot
        }

        editRelativeFolder.setText(relativeFolder)
    }

    private fun saveSettings() {
        val vaultRootPath = tvVaultRootPath.text.toString()
        val relativeFolder = editRelativeFolder.text?.toString()?.trim() ?: ""

        if (vaultRootPath == "Путь не выбран") {
            Toast.makeText(this, "Выберите корень хранилища Obsidian (Vault)", Toast.LENGTH_LONG).show()
            return
        }

        // Проверим доступ к папке
        val treeUri = Uri.parse(vaultRootPath)
        val rootDocument = DocumentFile.fromTreeUri(this, treeUri)
        if (rootDocument == null || !rootDocument.exists()) {
            Toast.makeText(this, "Не удалось получить доступ к папке хранилища Obsidian (Vault)", Toast.LENGTH_LONG).show()
            return
        }

        // Сохраняем
        sharedPreferences.edit()
            .putString("obsidian_vault_path", vaultRootPath)
            .putString("save_folder_relative", relativeFolder)
            .apply()

        tvStatus.text = "Настройки сохранены"
        tvStatus.visibility = android.view.View.VISIBLE

        Toast.makeText(this, "Готово! Теперь можно делиться ссылками", Toast.LENGTH_SHORT).show()

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

                tvVaultRootPath.text = treeUri.toString()

                // Обновляем отображение
                val documentFile = DocumentFile.fromTreeUri(this, treeUri)
                if (documentFile != null) {
                    Toast.makeText(this, "Vault выбран: ${documentFile.name}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }
}