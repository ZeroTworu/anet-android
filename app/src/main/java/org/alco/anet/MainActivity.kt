package org.alco.anet

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.PorterDuff
import android.graphics.drawable.Drawable
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.OpenableColumns
import android.provider.Settings
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListPopupWindow
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import androidx.annotation.Keep
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.codescanner.GmsBarcodeScannerOptions
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import android.widget.Toast
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import android.util.Log
import android.view.MotionEvent

// Вспомогательная структура данных для парсинга нод в Kotlin
data class ServerModel(val id: String, val name: String) {
    fun getFormattedName() = name
}

// Модель для хранения списка конфигураций
data class ConfigItem(
    val id: String = UUID.randomUUID().toString(),
    var name: String,
    var content: String
)

class MainActivity : AppCompatActivity() {

    // UI Элементы
    private lateinit var tvRtt: TextView
    private lateinit var tvRx: TextView
    private lateinit var tvTx: TextView
    private lateinit var tvRxm: TextView
    private lateinit var tvTxm: TextView

    private var isScanningQr = false
    private lateinit var connectionStatusLabel: TextView
    private lateinit var connectButton: Button
    private lateinit var spinner: ImageView
    private lateinit var btnCheckUpdate: Button
    private lateinit var btnShowLogs: Button
    private lateinit var btnSettings: Button

    private lateinit var serverSelectContainer: LinearLayout
    private lateinit var serverSelectTextView: TextView

    private lateinit var serverIndicator: ImageView
    private lateinit var serverSelectIcon: ImageView
    private var activeErrorDialog: AlertDialog? = null

    // Буфер и управление окном логов.
    // Каждая строка хранится отдельным элементом (а не одной гигантской SpannableStringBuilder),
    // чтобы ListView мог переиспользовать (recycle) view-элементы и не перекладывать весь текст
    // заново при каждой новой строке лога — именно это и вызывало тормоза при большом логе.
    private val logLines = ArrayDeque<CharSequence>().apply {
        val time = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        add("[$time] > System ready...")
    }
    private var activeLogAdapter: ArrayAdapter<CharSequence>? = null
    private var activeLogListView: ListView? = null

    // Управление окном списка конфигов
    private var activeConfigDialog: AlertDialog? = null
    private var refreshConfigListRunnable: Runnable? = null

    // Состояние
    private var selectedConfigContent: String? = null
    private var selectedConfigName: String = "Unknown"
    private var isCheckingUpdates = false
    private var isVpnConnected = false
    private var currentUiState = State.DISCONNECTED
    private var updateDialog: AlertDialog? = null
    private var progressBar: ProgressBar? = null

    private val mainHandler = Handler(Looper.getMainLooper())
    private val connectTimeoutRunnable = Runnable {
        if (currentUiState == State.CONNECTING) {
            logToConsole("Connection timeout: stopping VPN attempt")
            stopVpnService()
            showErrorDialog("Connection timed out. Please check your network or try a different server.")
        }
    }

    // Список распарсенных нод из активного конфига
    private val availableServers = mutableListOf<ServerModel>()
    private var selectedServerName: String = ""

    private external fun getAppVersion(): String
    private external fun getBuildInfo(): String
    private external fun checkUpdates(config: String?)
    private external fun startDownload(path: String)
    private external fun getPendingTag(): String
    private external fun getPendingBody(): String
    private external fun inspectConfig(config: String): String
    private external fun getVpnStateCode(): Int
    private external fun getVpnServerName(): String
    private external fun clearUiCallback()

    // Enum для состояний UI
    enum class State { DISCONNECTED, CONNECTING, CONNECTED }

    companion object {
        init {
            System.loadLibrary("anet_mobile")
        }

        // Защита от неограниченного роста памяти при очень долгой работе VPN.
        // "Сохранить лог" при этом всё равно пишет то, что реально накоплено в буфере.
        private const val MAX_LOG_LINES = 20000
        private const val PREF_SUBSCRIPTION_URL = "subscription_url"


    }

    private external fun initLogger()

    // --- УПРАВЛЕНИЕ СПИСКОМ КОНФИГУРАЦИЙ В PREFS ---

    private fun getSavedConfigs(): MutableList<ConfigItem> {
        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        val jsonStr = prefs.getString("saved_configs_list", null) ?: return mutableListOf()
        val list = mutableListOf<ConfigItem>()
        try {
            val array = JSONArray(jsonStr)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                list.add(
                    ConfigItem(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        name = obj.getString("name"),
                        content = obj.getString("content")
                    )
                )
            }
        } catch (e: Exception) {
            logToConsole("Ошибка чтения списка конфигов: ${e.message}")
        }
        return list
    }

    private fun saveConfigsToPrefs(configs: List<ConfigItem>, activeId: String? = null) {
        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        val array = JSONArray()
        for (item in configs) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("content", item.content)
            }
            array.put(obj)
        }
        val editor = prefs.edit().putString("saved_configs_list", array.toString())
        if (activeId != null) {
            editor.putString("active_config_id", activeId)
        }
        editor.apply()
    }

    private fun addAndActivateConfig(name: String, content: String) {
        val configs = getSavedConfigs()
        val newItem = ConfigItem(name = name, content = content)
        configs.add(newItem)

        selectedConfigContent = content
        selectedConfigName = name

        saveConfigsToPrefs(configs, newItem.id)
        saveConfigToPrefs(content, name)

        setupServerSelector()
        refreshConfigListRunnable?.run()
    }

    // Метод установки скачанного APK
    private fun installApk() {
        val apkFile = File(cacheDir, "update.apk")
        val contentUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", apkFile)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        startActivity(intent)
    }

    // Модалка обновления
    private fun showUpdateModal(version: String, body: String) {
        runOnUiThread {
            if (updateDialog?.isShowing == true) return@runOnUiThread

            val builder = AlertDialog.Builder(this)
            val dialogView = layoutInflater.inflate(R.layout.dialog_update, null)

            val versionView = dialogView.findViewById<TextView>(R.id.updateVersion)
            val changelogView = dialogView.findViewById<TextView>(R.id.updateChangelog)
            val btnUpdate = dialogView.findViewById<Button>(R.id.btnUpdateNow)
            val btnCancel = dialogView.findViewById<Button>(R.id.btnCancelUpdate)

            progressBar = dialogView.findViewById<ProgressBar>(R.id.updateProgress)

            versionView.text = "Version: $version"
            changelogView.text = body

            builder.setView(dialogView)
            builder.setCancelable(false)

            updateDialog = builder.create()
            updateDialog?.show()

            btnCancel.setOnClickListener {
                updateDialog?.dismiss()
            }

            btnUpdate.setOnClickListener {
                btnUpdate.isEnabled = false
                btnCancel.isEnabled = false
                progressBar?.visibility = View.VISIBLE

                logToConsole("Starting APK download...")
                val destination = File(cacheDir, "update.apk").absolutePath
                startDownload(destination)
            }
        }
    }

    private fun inspectServers(toml: String, reportError: Boolean = true): List<ServerModel>? {
        val result = inspectConfig(toml).lineSequence().toList()
        if (result.firstOrNull() != "OK") {
            val error = result.drop(1).joinToString("\n").ifBlank { "Invalid configuration" }
            logToConsole("Ошибка конфигурации: $error")
            if (reportError) showErrorDialog(error)
            return null
        }
        return result.drop(1).filter { it.isNotBlank() }.map { line ->
            val parts = line.split("|", limit = 2)
            if (parts.size == 2) {
                ServerModel(parts[0], parts[1])
            } else {
                ServerModel(parts[0], parts[0])
            }
        }
    }

    private fun onServerSelected(position: Int) {
        if (position !in availableServers.indices) return
        val server = availableServers[position]

        selectedServerName = server.id
        serverSelectTextView.text = server.name

        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        prefs.edit().putString("selected_server_${selectedConfigName}", server.id).apply()

        logToConsole("Выбран сервер/группа: ${server.name}")
    }

    private fun setupServerSelector() {
        val content = selectedConfigContent ?: return
        availableServers.clear()
        val servers = inspectServers(content) ?: return
        availableServers.addAll(servers)

        logToConsole("Найдено серверов/групп в конфиге: ${availableServers.size}")

        if (availableServers.isEmpty()) {
            serverSelectContainer.visibility = View.GONE
            return
        }

        serverSelectContainer.visibility = View.VISIBLE

        val formattedNames = availableServers.map { it.getFormattedName() }

        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        val lastSelected = prefs.getString("selected_server_${selectedConfigName}", "") ?: ""
        val index = availableServers.indexOfFirst { it.id == lastSelected }

        if (index >= 0) {
            selectedServerName = availableServers[index].id
            serverSelectTextView.text = availableServers[index].name
        } else {
            selectedServerName = availableServers.first().id
            serverSelectTextView.text = availableServers.first().name
            prefs.edit().putString("selected_server_${selectedConfigName}", selectedServerName)
                .apply()
        }

        val listPopupWindow =
            ListPopupWindow(this, null, androidx.appcompat.R.attr.listPopupWindowStyle)
        listPopupWindow.anchorView = serverSelectContainer

        val backgroundDrawable = ContextCompat.getDrawable(this, R.drawable.popup_bg)
        if (backgroundDrawable != null) {
            listPopupWindow.setBackgroundDrawable(backgroundDrawable)
        }

        listPopupWindow.verticalOffset = 0

        val adapter = ArrayAdapter(
            this,
            R.layout.spinner_dropdown_item,
            formattedNames
        )
        listPopupWindow.setAdapter(adapter)

        listPopupWindow.setOnItemClickListener { _, _, position, _ ->
            onServerSelected(position)
            listPopupWindow.dismiss()
        }

        var popupDismissTime = 0L

        listPopupWindow.setOnDismissListener {
            popupDismissTime = System.currentTimeMillis()
            serverSelectIcon.animate().rotation(0f).setDuration(200).start()
        }

        serverSelectContainer.setOnClickListener {
            if (isVpnConnected) {
                Toast.makeText(this, "Соединение активно, выбор ноды заблокирован", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            if (System.currentTimeMillis() - popupDismissTime < 250) {
                return@setOnClickListener
            }

            if (listPopupWindow.isShowing) {
                listPopupWindow.dismiss()
            } else {
                serverSelectIcon.animate().rotation(180f).setDuration(200).start()
                listPopupWindow.show()
            }
        }


    }

    // --- Google Barcode Scanner (ML Kit) ---
    private fun startQrScanner() {
        isScanningQr = true // Отмечаем, что открыт QR-сканер

        val options = GmsBarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build()

        val scanner = GmsBarcodeScanning.getClient(this, options)
        scanner.startScan()
            .addOnSuccessListener { barcode ->
                isScanningQr = false
                val url = barcode.rawValue
                if (!url.isNullOrBlank() && (url.startsWith("http://") || url.startsWith("https://"))) {
                    Toast.makeText(this, "QR распознан, загрузка профиля...", Toast.LENGTH_SHORT).show()
                    downloadConfigFromUrl(url)
                } else {
                    logToConsole("Неверный формат ссылки: $url")
                    Toast.makeText(this, "QR-код не содержит валидную ссылку", Toast.LENGTH_SHORT).show()
                    if (getSavedConfigs().isEmpty()) {
                        checkInitialUrl()
                    }
                }
            }
            .addOnCanceledListener {
                isScanningQr = false
                logToConsole("QR Сканирование отменено пользователем")
                Toast.makeText(this, "QR-сканирование отменено", Toast.LENGTH_SHORT).show()
                if (getSavedConfigs().isEmpty()) {
                    checkInitialUrl()
                }
            }
            .addOnFailureListener { e ->
                isScanningQr = false
                logToConsole("QR Сканирование ошибка: ${e.message}")
                Toast.makeText(this, "Ошибка QR-сканера: ${e.message}", Toast.LENGTH_SHORT).show()
                if (getSavedConfigs().isEmpty()) {
                    checkInitialUrl()
                }
            }
    }
    private fun downloadConfigFromUrl(url: String) {
        logToConsole("Загрузка конфигурации по ссылке...")
        spinner.visibility = View.VISIBLE

        Thread {
            var connection: HttpURLConnection? = null
            try {
                connection = URL(url).openConnection() as HttpURLConnection
                connection.connectTimeout = 8000
                connection.readTimeout = 8000
                connection.requestMethod = "GET"

                if (connection.responseCode == HttpURLConnection.HTTP_OK) {
                    val content = connection.inputStream.bufferedReader().use { it.readText() }

                    if (inspectServers(content, reportError = false) != null) {
                        runOnUiThread {
                            spinner.visibility = View.INVISIBLE
                            val configName = "QR-Imported"
                            addAndActivateConfig(configName, content)
                            logToConsole("Профиль импортирован по QR-коду!")
                            Toast.makeText(this@MainActivity, "Профиль успешно импортирован!", Toast.LENGTH_SHORT).show()

                            logToConsole(">>> Автозапуск соединения...")
                            checkPermissionsAndStart()
                        }
                    } else {
                        runOnUiThread {
                            spinner.visibility = View.INVISIBLE
                            val errorMsg = "Ошибка: файл по ссылке не является TOML-конфигом ANet"
                            logToConsole(errorMsg)
                            Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_LONG).show()
                            if (getSavedConfigs().isEmpty()) {
                                checkInitialUrl()
                            }
                        }
                    }
                } else {
                    runOnUiThread {
                        spinner.visibility = View.INVISIBLE
                        val errorMsg = "Сервер вернул ошибку: HTTP ${connection.responseCode}"
                        logToConsole(errorMsg)
                        Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_SHORT).show()
                        if (getSavedConfigs().isEmpty()) {
                            checkInitialUrl()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    spinner.visibility = View.INVISIBLE
                    val errorMsg = "Ошибка скачивания: ${e.localizedMessage ?: e.message}"
                    logToConsole(errorMsg)
                    Toast.makeText(this@MainActivity, errorMsg, Toast.LENGTH_SHORT).show()
                    if (getSavedConfigs().isEmpty()) {
                        checkInitialUrl()
                    }
                }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }
    // --- LAUNCHERS ---

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            val content = readTextFromUri(uri)
            val name = getFileName(uri)

            if (content.isNotEmpty() && inspectServers(content) != null) {
                addAndActivateConfig(name, content)
                logToConsole("Loaded config: $name (${content.length} bytes)")
                Toast.makeText(this, "Конфигурация \"$name\" успешно добавлена!", Toast.LENGTH_SHORT).show()
            } else {
                logToConsole("Failed to read config file")
                Toast.makeText(this, "Ошибка: некорректный .toml конфиг", Toast.LENGTH_LONG).show()
                // Если конфигов по-прежнему нет — возвращаем диалог
                if (getSavedConfigs().isEmpty()) {
                    checkInitialUrl()
                }
            }
        } else {
            // Пользователь нажал "Назад" в проводнике
            Toast.makeText(this, "Выбор файла отменён", Toast.LENGTH_SHORT).show()
            // Если конфигов нет — снова открываем окно ввода ссылки
            if (getSavedConfigs().isEmpty()) {
                checkInitialUrl()
            }
        }
    }

    private fun checkBatteryOptimizations() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                logToConsole("Запрос на отключение оптимизации батареи...")
                try {
                    val intent =
                        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                            data = Uri.parse("package:$packageName")
                        }
                    startActivity(intent)
                } catch (e: Exception) {
                    logToConsole("Не удалось открыть настройки батареи: ${e.message}")
                }
            }
        }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpnService()
        } else {
            logToConsole("VPN permission denied")
            setUiState(State.DISCONNECTED)
        }
    }

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (!isGranted) {
            logToConsole("Notification permission denied. Running silently.")
        }
        attemptVpnConnection()
    }

    private fun showErrorDialog(message: String) {
        runOnUiThread {
            if (activeErrorDialog?.isShowing == true) {
                return@runOnUiThread
            }

            val builder = AlertDialog.Builder(this)
            builder.setTitle("ОШИБКА ДОСТУПА")

            val cleanMsg = message
                .replace("ERROR:", "")
                .replace("WARN:", "")
                .replace("[CORE AUTH]", "")
                .replace(Regex("\\[AUTH\\].*failed:"), "")
                .trim()

            builder.setMessage(cleanMsg)
            builder.setPositiveButton("ПОНЯТНО") { dialog, _ ->
                dialog.dismiss()
                activeErrorDialog = null
            }

            builder.setOnCancelListener { activeErrorDialog = null }

            activeErrorDialog = builder.create()
            activeErrorDialog?.show()

            activeErrorDialog?.getButton(android.app.AlertDialog.BUTTON_POSITIVE)
                ?.setTextColor(Color.parseColor("#FF6400"))
        }
    }


    // Проверка ключа

    private fun showEnterUrlDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 24.dpToPx(), 24.dpToPx(), 16.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f * resources.displayMetrics.density
                setColor(Color.parseColor("#1C1C1E"))
            }
        }

        val titleTv = TextView(this).apply {
            text = "Введите ссылку"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16.dpToPx())
        }

        val input = EditText(this).apply {
            hint = "https://example.com/config.toml"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#7E7E7E"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10f * resources.displayMetrics.density
                setColor(Color.parseColor("#2C2C2E"))
            }
        }

        val errorTv = TextView(this).apply {
            setTextColor(Color.parseColor("#FF5252"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            visibility = View.GONE
            setPadding(4.dpToPx(), 6.dpToPx(), 4.dpToPx(), 0)
        }

        val loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8.dpToPx(), 0, 0)
            }
        }

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 14.dpToPx(), 0, 0)
            }
        }

        // 1. Кнопка "Отмена" -> Закрывает приложение
        val btnCancel = Button(this).apply {
            text = "Отмена"
            setTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        // 2. Кнопка "QR" -> Сканирование QR-кода
        val btnQr = Button(this).apply {
            text = "QR"
            setTextColor(Color.parseColor("#EEBC7A"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        // 3. Кнопка "Конфиг" -> Открытие выбора файла
        val btnFileConfig = Button(this).apply {
            text = "Конфиг"
            setTextColor(Color.parseColor("#EEBC7A"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        // 4. Кнопка "ОК" -> Загрузка .toml по ссылке и активация
        val btnOk = Button(this).apply {
            text = "ОК"
            setTextColor(Color.parseColor("#00E676"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        buttonBar.addView(btnCancel)
        buttonBar.addView(btnQr)
        buttonBar.addView(btnFileConfig)
        buttonBar.addView(btnOk)

        container.addView(titleTv)
        container.addView(input)
        container.addView(errorTv)
        container.addView(loadingBar)
        container.addView(buttonBar)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .setCancelable(false)
            .create()

        // Действие "Отмена"
        btnCancel.setOnClickListener {
            dialog.dismiss()
            finishAffinity()
        }

        // Действие "QR"
        btnQr.setOnClickListener {
            dialog.dismiss()
            startQrScanner()
        }

        // Действие "Конфиг" (выбор файла)
        btnFileConfig.setOnClickListener {
            dialog.dismiss()
            filePickerLauncher.launch(arrayOf("*/*"))
        }

        // Действие "ОК"
        btnOk.setOnClickListener {
            val url = input.text.toString().trim()
            if (url.isEmpty()) {
                errorTv.text = "Поле ссылки не может быть пустым"
                errorTv.visibility = View.VISIBLE
                return@setOnClickListener
            }

            if (!url.startsWith("http://", ignoreCase = true) && !url.startsWith("https://", ignoreCase = true)) {
                errorTv.text = "Ссылка должна начинаться с http:// или https://"
                errorTv.visibility = View.VISIBLE
                return@setOnClickListener
            }

            btnOk.isEnabled = false
            btnCancel.isEnabled = false
            btnQr.isEnabled = false
            btnFileConfig.isEnabled = false
            errorTv.visibility = View.GONE
            loadingBar.visibility = View.VISIBLE

            downloadAndApplyConfigFromUrl(
                initialUrl = url,
                onSuccess = {
                    dialog.dismiss()
                },
                onError = { error ->
                    btnOk.isEnabled = true
                    btnCancel.isEnabled = true
                    btnQr.isEnabled = true
                    btnFileConfig.isEnabled = true
                    loadingBar.visibility = View.GONE
                    errorTv.text = error
                    errorTv.visibility = View.VISIBLE
                }
            )
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }

    // --- BROADCAST RECEIVER ---

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getBooleanExtra("is_account_info", false) == true) {
                val billing = intent.getStringExtra("billing") ?: "—"
                val group = intent.getStringExtra("group") ?: "—"
                val sessions = intent.getStringExtra("sessions") ?: "—"
                val speed = intent.getStringExtra("speed") ?: "—"
                val consumed = intent.getStringExtra("consumed") ?: "—"
                val limit = intent.getStringExtra("limit") ?: "—"
                val expires = intent.getStringExtra("expires") ?: "—"

                runOnUiThread {
                    findViewById<TextView>(R.id.tvAccountGroup)?.text = "$billing • $group"
                    findViewById<TextView>(R.id.tvAccountExpires)?.text = expires
                    findViewById<TextView>(R.id.tvAccountTraffic)?.text = "$consumed\n/ $limit"
                    findViewById<TextView>(R.id.tvAccountSpeed)?.text = speed
                    findViewById<TextView>(R.id.tvAccountSessions)?.text = sessions
                }
                return
            }
            if (intent?.getBooleanExtra(ANetVpnService.EXTRA_IS_STATS, false) == true) {
                val rx = intent.getStringExtra(ANetVpnService.EXTRA_STATS_RX).orEmpty()
                val tx = intent.getStringExtra(ANetVpnService.EXTRA_STATS_TX).orEmpty()
                val rtt = intent.getStringExtra(ANetVpnService.EXTRA_STATS_RTT).orEmpty()
                val rxm = intent.getStringExtra(ANetVpnService.EXTRA_STATS_RXM).orEmpty()
                val txm = intent.getStringExtra(ANetVpnService.EXTRA_STATS_TXM).orEmpty()
                updateTrafficStats(rx, tx, rtt, rxm, txm)
                return
            }

            if (intent?.hasExtra(ANetVpnService.EXTRA_VPN_STATE) == true) {
                handleVpnState(
                    intent.getIntExtra(
                        ANetVpnService.EXTRA_VPN_STATE,
                        ANetVpnService.STATE_DISCONNECTED
                    ),
                    intent.getStringExtra(ANetVpnService.EXTRA_VPN_MESSAGE).orEmpty(),
                    intent.getStringExtra(ANetVpnService.EXTRA_SERVER_NAME).orEmpty()
                )
                return
            }

            val status = intent?.getStringExtra("status")

            if (status == null) return

            if (status.contains("Найдено обновление") ||
                status.contains("актуальная версия") ||
                status.contains("Ошибка обновления")
            ) {
                isCheckingUpdates = false
                btnCheckUpdate.isEnabled = currentUiState == State.DISCONNECTED
                btnCheckUpdate.alpha = if (btnCheckUpdate.isEnabled) 1.0f else 0.3f
            }

            status.let { msg ->
                if (msg.startsWith("PROGRESS:")) {
                    val progressValue = msg.substringAfter("PROGRESS:").toFloatOrNull() ?: 0f
                    runOnUiThread {
                        progressBar?.isIndeterminate = false
                        progressBar?.progress = (progressValue * 100).toInt()
                    }
                    return
                }

                if (msg.equals("VPN Stopped", ignoreCase = true)) {
                    return
                }

                logToConsole(msg)

                val isAuthError = msg.contains("сессий", ignoreCase = true) ||
                        msg.contains("истекло", ignoreCase = true) ||
                        msg.contains("denied", ignoreCase = true)

                when {
                    msg.contains("Active node:") -> {
                        val activeName = msg.substringAfter("Active node:").trim()
                        runOnUiThread {
                            val index = availableServers.indexOfFirst { it.name == activeName }
                            if (index >= 0) {
                                val server = availableServers[index]
                                serverSelectTextView.text = server.name
                                selectedServerName = server.id

                                val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
                                prefs.edit()
                                    .putString("selected_server_${selectedConfigName}", server.id)
                                    .apply()
                            }
                        }
                    }

                    msg.contains("Найдено обновление", ignoreCase = true) -> {
                        val tag = getPendingTag()
                        val body = getPendingBody()
                        showUpdateModal(tag, body)
                    }

                    msg.contains("Update downloaded to cache", ignoreCase = true) -> {
                        updateDialog?.dismiss()
                        installApk()
                    }

                    isAuthError -> {
                        stopVpnService()
                        setUiState(State.DISCONNECTED)
                        showErrorDialog(msg)
                    }
                }
            }
        }
    }

    private fun handleVpnState(state: Int, message: String, serverName: String) {
        if (state == ANetVpnService.STATE_FAILED && message.isNotBlank()) {
            logToConsole(message)
        }

        if (serverName.isNotBlank()) {
            val index = availableServers.indexOfFirst { it.name == serverName }
            if (index >= 0) {
                val server = availableServers[index]
                serverSelectTextView.text = server.name
                selectedServerName = server.id
                getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putString("selected_server_${selectedConfigName}", server.id)
                    .apply()
            }
        }

        when (state) {
            ANetVpnService.STATE_CONNECTING -> setUiState(State.CONNECTING, "CONNECTING...")
            ANetVpnService.STATE_RECONNECTING -> setUiState(State.CONNECTING, "RECONNECTING...")
            ANetVpnService.STATE_STOPPING -> setUiState(State.CONNECTING, "STOPPING...")

            ANetVpnService.STATE_CONNECTED -> setUiState(State.CONNECTED)

            ANetVpnService.STATE_DISCONNECTED,
            ANetVpnService.STATE_STOPPED -> setUiState(State.DISCONNECTED)

            ANetVpnService.STATE_FAILED -> {
                setUiState(State.DISCONNECTED)
                if (message.isNotBlank()) showErrorDialog(message)
            }
        }
    }

    private fun View.setupTvFocusAnimator() {
        this.isFocusable = true
        this.isClickable = true

        this.setOnFocusChangeListener { view, hasFocus ->
            if (hasFocus) {
                view.animate()
                    .scaleX(1.08f)
                    .scaleY(1.08f)
                    .translationZ(8f)
                    .setDuration(150)
                    .start()
            } else {
                view.animate()
                    .scaleX(1.0f)
                    .scaleY(1.0f)
                    .translationZ(0f)
                    .setDuration(150)
                    .start()
            }
        }
    }

    // --- LIFECYCLE ---

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvRtt = findViewById(R.id.tvRtt)
        tvRx = findViewById(R.id.tvRx)
        tvTx = findViewById(R.id.tvTx)
        tvRxm = findViewById(R.id.tvRxm)
        tvTxm = findViewById(R.id.tvTxm)

        connectionStatusLabel = findViewById(R.id.connectionStatus)
        connectButton = findViewById(R.id.connect)
        spinner = findViewById(R.id.connectSpinner)

        btnCheckUpdate = findViewById(R.id.btnCheckUpdate)
        btnShowLogs = findViewById(R.id.btnShowLogs)

        btnSettings = findViewById(R.id.btnSettings)
        btnSettings.setupTvFocusAnimator()

        btnSettings.setOnClickListener {
            showSettingsDialog()
        }

        serverSelectContainer = findViewById(R.id.serverSelectContainer)
        serverSelectTextView = findViewById(R.id.serverSelectTextView)
        serverIndicator = findViewById(R.id.serverIndicator)
        serverSelectIcon = findViewById(R.id.serverSelectIcon)

        btnShowLogs.setOnClickListener {
            showLogsDialog()
        }

        initLogger()

        loadConfigFromPrefs()

        checkInitialUrl()

        if (selectedConfigContent != null) {
            logToConsole("Config loaded: $selectedConfigName")
            setupServerSelector()
            checkBatteryOptimizations()
        } else {
            logToConsole("Welcome. Please select config file.")
        }

        val filter = IntentFilter("org.alco.anet.VPN_STATUS")
        ContextCompat.registerReceiver(
            this,
            statusReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        connectButton.setOnClickListener {
            when (currentUiState) {
                State.CONNECTED, State.CONNECTING -> {
                    stopVpnService()
                }

                State.DISCONNECTED -> {
                    checkPermissionsAndStart()
                }
            }
        }

        setUiState(State.DISCONNECTED)

        findViewById<TextView>(R.id.versionLabel).text = getAppVersion()
        findViewById<TextView>(R.id.buildDetailLabel).text = getBuildInfo()

        connectButton.setupTvFocusAnimator()
        btnCheckUpdate.setupTvFocusAnimator()
        serverSelectContainer.setupTvFocusAnimator()
        btnShowLogs.setupTvFocusAnimator()

        btnCheckUpdate.setOnClickListener {
            if (isCheckingUpdates) return@setOnClickListener

            isCheckingUpdates = true
            btnCheckUpdate.isEnabled = false
            btnCheckUpdate.alpha = 0.3f

            logToConsole("Checking for system updates...")

            Thread {
                try {
                    checkUpdates(selectedConfigContent)
                } catch (e: Exception) {
                    runOnUiThread {
                        isCheckingUpdates = false
                        btnCheckUpdate.isEnabled = currentUiState == State.DISCONNECTED
                        btnCheckUpdate.alpha = if (btnCheckUpdate.isEnabled) 1.0f else 0.3f
                        logToConsole("Update check error: ${e.message}")
                    }
                }
            }.start()
        }

        handleIntent(intent)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val currentFocus = currentFocus
            if (currentFocus != null) {
                currentFocus.performClick()
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val action = intent?.action
        val data = intent?.data

        if (Intent.ACTION_VIEW == action && data != null) {
            logToConsole("Импорт конфигурации из внешнего источника...")
            val content = readTextFromUri(data)
            val name = getFileName(data)

            if (content.isNotEmpty() && inspectServers(content) != null) {
                addAndActivateConfig(name, content)
                logToConsole("Конфигурация успешно импортирована: $name")
                logToConsole(">>> Автозапуск соединения...")
                checkPermissionsAndStart()
            } else {
                logToConsole("Ошибка импорта: Некорректный файл .toml")
            }
        }
    }

    @Keep
    fun onStatusChanged(status: String) {
        val intent = Intent("org.alco.anet.VPN_STATUS")
        intent.putExtra("status", status)
        intent.setPackage(packageName)
        sendBroadcast(intent)
    }

    @Keep
    fun onTrafficStats(rx: String, tx: String, rtt: String, rxm: String, txm: String) {
        updateTrafficStats(rx, tx, rtt, rxm, txm)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        unregisterReceiver(statusReceiver)
        clearUiCallback()
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        val stateCode =
            if (ANetVpnService.isServiceRunning) getVpnStateCode() else ANetVpnService.STATE_DISCONNECTED
        handleVpnState(stateCode, "", getVpnServerName())

        // ЕСЛИ ПОЛЬЗОВАТЕЛЬ ВЕРНУЛСЯ ИЗ QR-СКАНЕРА БЕЗ ВЫБОРА КОНФИГА:
        if (isScanningQr) {
            isScanningQr = false
            if (getSavedConfigs().isEmpty()) {
                mainHandler.postDelayed({
                    checkInitialUrl()
                }, 150)
            }
        }
    }

    // --- LOGIC ---

    private fun checkPermissionsAndStart() {
        if (selectedConfigContent == null) {
            logToConsole("Error: No config selected!")
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                attemptVpnConnection()
            }
        } else {
            attemptVpnConnection()
        }
    }

    private fun attemptVpnConnection() {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpnService()
        }
    }

    private fun startVpnService() {
        setUiState(State.CONNECTING)

        val intent = Intent(this, ANetVpnService::class.java)
        intent.action = ANetVpnService.ACTION_CONNECT
        intent.putExtra("CONFIG", selectedConfigContent)
        intent.putExtra("SELECTED_SERVER", selectedServerName)

        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        val appsSet = prefs.getStringSet("allowed_apps", emptySet())
        if (!appsSet.isNullOrEmpty()) {
            intent.putStringArrayListExtra("ALLOWED_APPS", ArrayList(appsSet))
        }

        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopVpnService() {
        mainHandler.removeCallbacks(connectTimeoutRunnable)
        val intent = Intent(this, ANetVpnService::class.java)
        intent.action = ANetVpnService.ACTION_STOP
        startService(intent)
        setUiState(State.CONNECTING, "STOPPING...")
    }

    fun TextView.setLeftIcon(iconRes: Int, text: String) {
        val icon = ContextCompat.getDrawable(context, iconRes)
        this.text = text
        setCompoundDrawablesWithIntrinsicBounds(icon, null, null, null)
    }

    fun TextView.setLeftIcon(
        @DrawableRes iconRes: Int,
        text: String,
        offsetX: Int = 0,
        offsetY: Int = 0,
        @ColorInt color: Int? = null,
        widthDp: Int = 12,
        heightDp: Int = 12
    ) {
        this.text = text
        val original = ContextCompat.getDrawable(context, iconRes) ?: return

        if (color != null) {
            original.mutate().setColorFilter(color, PorterDuff.Mode.SRC_IN)
        }

        val targetWidth = widthDp.dpToPx()
        val targetHeight = heightDp.dpToPx()

        val finalDrawable = if (offsetX == 0 && offsetY == 0) {
            original.apply { setBounds(0, 0, targetWidth, targetHeight) }
        } else {
            object : Drawable() {
                override fun draw(canvas: Canvas) {
                    canvas.save()
                    canvas.translate(offsetX.toFloat(), offsetY.toFloat())
                    original.draw(canvas)
                    canvas.restore()
                }

                override fun setBounds(left: Int, top: Int, right: Int, bottom: Int) {
                    super.setBounds(left, top, right, bottom)
                    original.setBounds(left, top, right, bottom)
                }

                override fun getIntrinsicWidth() = targetWidth
                override fun getIntrinsicHeight() = targetHeight
                override fun setAlpha(alpha: Int) {
                    original.alpha = alpha
                }

                override fun setColorFilter(filter: ColorFilter?) {
                    original.colorFilter = filter
                }

                override fun getOpacity() = original.opacity
            }.apply {
                setBounds(0, 0, targetWidth, targetHeight)
            }
        }

        setCompoundDrawables(finalDrawable, null, null, null)
    }

    fun TextView.removeLeftIcon() {
        setCompoundDrawables(null, null, null, null)
    }

    // --- UI HELPERS ---

    private fun setUiState(state: State, customStatusText: String? = null) {
        runOnUiThread {
            currentUiState = state
            val controlsEnabled = state == State.DISCONNECTED
            btnCheckUpdate.isEnabled = controlsEnabled && !isCheckingUpdates
            btnCheckUpdate.alpha = if (btnCheckUpdate.isEnabled) 1.0f else 0.3f

            when (state) {
                State.DISCONNECTED -> {
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    isVpnConnected = false
                    spinner.visibility = View.INVISIBLE
                    spinner.clearAnimation()

                    tvRtt.text = "0 ms"
                    tvRx.text = "0 B/s"
                    tvTx.text = "0 B/s"
                    tvRxm.text = "0 B"
                    tvTxm.text = "0 B"

                    connectButton.text = "CONNECT"
                    connectButton.isEnabled = true

                    val readyColors = intArrayOf(
                        Color.parseColor("#669D29"),
                        Color.parseColor("#3AA34B"), // ярко-зелёный
                        Color.parseColor("#1C7C3A"), // тёмный лесной
                        Color.parseColor("#1C7C3A"),
                        Color.parseColor("#3AA34B"),
                        Color.parseColor("#669D29")
                    )
                    connectButton.background = createNeonRingDrawable(readyColors)

                    connectionStatusLabel.setLeftIcon(
                        R.drawable.block,
                        "DISCONNECTED",
                        offsetX = -2,
                        offsetY = 0,
                        color = (0xFFFF5252.toInt()),
                        widthDp = 12,
                        heightDp = 12
                    )
                    connectionStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.grey_light))

                    serverSelectContainer.isEnabled = true
                    serverIndicator.imageTintList = ColorStateList.valueOf(Color.parseColor("#9E9E9E"))
                    btnSettings.alpha = 1.0f
                    serverSelectIcon.setImageResource(R.drawable.chevron_down)
                }

                State.CONNECTING -> {
                    val statusText = customStatusText ?: "CONNECTING..."
                    val isStopping = statusText.startsWith("STOPPING")
                    if (!isStopping) {
                        mainHandler.removeCallbacks(connectTimeoutRunnable)
                        mainHandler.postDelayed(connectTimeoutRunnable, 30_000L)
                    } else {
                        mainHandler.removeCallbacks(connectTimeoutRunnable)
                    }

                    spinner.visibility = View.VISIBLE
                    spinner.setImageDrawable(createAaaSpinnerDrawable())

                    if (spinner.animation == null) {
                        val animator = android.animation.ObjectAnimator.ofFloat(
                            spinner,
                            View.ROTATION,
                            0f,
                            360f
                        )
                        animator.duration = 1200
                        animator.repeatCount = android.animation.ValueAnimator.INFINITE
                        animator.interpolator = android.view.animation.LinearInterpolator()
                        animator.start()
                    }

                    if (isStopping) {
                        connectButton.text = "STOPPING"
                        connectButton.isEnabled = false
                    } else {
                        connectButton.text = "CANCEL"
                        connectButton.isEnabled = true
                    }

                    val workingColors = intArrayOf(
                        Color.parseColor("#669D29"),
                        Color.parseColor("#C4B12B"), // золотисто-жёлтый
                        Color.parseColor("#E67E22"), // яркий оранжевый
                        Color.parseColor("#E67E22"),
                        Color.parseColor("#C4B12B"),
                        Color.parseColor("#669D29")
                    )
                    connectButton.background = createNeonRingDrawable(workingColors)

                    connectionStatusLabel.setLeftIcon(
                        R.drawable.update,
                        "CONNECTING",
                        offsetX = -2,
                        offsetY = 0,
                        color = (0xFFFE6102.toInt()),
                        widthDp = 12,
                        heightDp = 12
                    )
                    connectionStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.grey_light))

                    serverSelectContainer.isEnabled = true
                    serverIndicator.imageTintList = ColorStateList.valueOf(Color.parseColor("#669D29"))
                    btnSettings.alpha = 0.3f
                }

                State.CONNECTED -> {
                    mainHandler.removeCallbacks(connectTimeoutRunnable)
                    isVpnConnected = true
                    spinner.visibility = View.INVISIBLE
                    spinner.clearAnimation()

                    connectButton.text = "STOP"
                    connectButton.isEnabled = true

                    val neonColors = intArrayOf(
                        Color.parseColor("#6A1B9A"),
                        Color.parseColor("#9C27B0"),
                        Color.parseColor("#E91E63"),
                        Color.parseColor("#D32F2F"),
                        Color.parseColor("#E91E63"),
                        Color.parseColor("#6A1B9A")
                    )
                    connectButton.background = createNeonRingDrawable(neonColors)

                    connectionStatusLabel.setLeftIcon(
                        R.drawable.dot,
                        "CONNECTED",
                        offsetX = -2,
                        offsetY = 0,
                        color = (0xFF4CAF50.toInt()),
                        widthDp = 12,
                        heightDp = 12
                    )
                    connectionStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.grey_light))

                    serverSelectContainer.isEnabled = true
                    serverSelectIcon.setImageResource(R.drawable.block)
                }
            }
        }
    }

    private fun logToConsole(msg: String) {
        runOnUiThread {
            val timestamp = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

            // Если в сообщении уже есть таймстемп вида [17:03:51], не дублируем его
            val formattedMsg = if (msg.startsWith("[") && msg.length >= 10 && msg[9] == ']') {
                msg
            } else {
                "[$timestamp] $msg"
            }

            val color = when {
                formattedMsg.contains("Config loaded", ignoreCase = true) ||
                        formattedMsg.contains(
                            "dead session",
                            ignoreCase = true
                        ) -> Color.parseColor("#FF9800")

                formattedMsg.contains("Connected", ignoreCase = true) -> Color.parseColor("#4CAF50")
                formattedMsg.contains("Stopped", ignoreCase = true) ||
                        formattedMsg.contains("Error", ignoreCase = true) ||
                        formattedMsg.contains("Ошибка", ignoreCase = true) -> Color.parseColor("#F44336")

                else -> null
            }

            val line = formattedMsg
            val entry: CharSequence = if (color != null) {
                SpannableString(line).apply {
                    setSpan(
                        ForegroundColorSpan(color),
                        0,
                        line.length,
                        Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
            } else {
                line
            }

            logLines.addLast(entry)
            if (logLines.size > MAX_LOG_LINES) {
                logLines.removeFirst()
            }

            activeLogAdapter?.notifyDataSetChanged()

            // Автопрокрутка в самый конец при получении новой записи
            activeLogListView?.let { lv ->
                lv.post {
                    if (logLines.isNotEmpty()) {
                        lv.setSelection(logLines.size - 1)
                    }
                }
            }
        }
    }

    // --- ДИАЛОГ МЕНЕДЖЕРА КОНФИГУРАЦИЙ ---

    private fun showConfigManagerDialog(returnToSettings: Boolean = false) {
        val rootLayout = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(20.dpToPx(), 20.dpToPx(), 20.dpToPx(), 20.dpToPx())
        }

        // 1. Шапка: Кнопка "Назад" + Заголовок
        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24.dpToPx())
            }
        }

        val btnClose = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#262626"))
            }
            val buttonSize = 40.dpToPx()
            val paddingSize = 10.dpToPx()
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                setMargins(0, 0, 16.dpToPx(), 0)
            }
            setPadding(paddingSize, paddingSize, paddingSize, paddingSize)
            setupTvFocusAnimator()
        }

        val titleView = TextView(this).apply {
            text = "Конфигурации"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        headerLayout.addView(btnClose)
        headerLayout.addView(titleView)

        // 2. Кнопка "+ Добавить конфигурацию" (из файла)
        val btnAddConfigLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * resources.displayMetrics.density
                setColor(Color.parseColor("#1C1C1E"))
                setStroke(1.dpToPx(), Color.parseColor("#2C2C2E"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 10.dpToPx())
            }
            isClickable = true
            isFocusable = true
            setupTvFocusAnimator()
            setOnClickListener {
                filePickerLauncher.launch(arrayOf("*/*"))
            }
        }

        val iconPlus = ImageView(this).apply {
            setImageResource(R.drawable.ic_pluse)
            setColorFilter(Color.parseColor("#669D29"))
            val iconSize = 18.dpToPx()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                setMargins(0, 0, 8.dpToPx(), 0)
            }
        }

        val textPlus = TextView(this).apply {
            text = "Добавить конфигурацию"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.WHITE)
        }

        btnAddConfigLayout.addView(iconPlus)
        btnAddConfigLayout.addView(textPlus)

        // 3. Кнопка "Импорт по QR" (QR-сканер)
        val btnAddQrLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER
            setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 12 * resources.displayMetrics.density
                setColor(Color.parseColor("#1C1C1E"))
                setStroke(1.dpToPx(), Color.parseColor("#2C2C2E"))
            }
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 20.dpToPx())
            }
            isClickable = true
            isFocusable = true
            setupTvFocusAnimator()
            setOnClickListener {
                startQrScanner()
            }
        }

        val iconQr = ImageView(this).apply {
            setImageResource(R.drawable.qr)
            setColorFilter(Color.parseColor("#EEBC7A"))
            val iconSize = 18.dpToPx()
            layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                setMargins(0, 0, 8.dpToPx(), 0)
            }
        }

        val textQr = TextView(this).apply {
            text = "Импорт по QR"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.WHITE)
        }

        btnAddQrLayout.addView(iconQr)
        btnAddQrLayout.addView(textQr)

        // 4. Заголовок раздела "ВАШИ КОНФИГУРАЦИИ"
        val sectionTitle = TextView(this).apply {
            text = "ВАШИ КОНФИГУРАЦИИ"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#7E7E7E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 4.dpToPx(), 0, 12.dpToPx())
            }
        }

        // 5. Прокручиваемый список карточек
        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        scrollView.addView(listContainer)

        rootLayout.addView(headerLayout)
        rootLayout.addView(btnAddConfigLayout)
        rootLayout.addView(btnAddQrLayout)
        rootLayout.addView(sectionTitle)
        rootLayout.addView(scrollView)

        fun populateList() {
            listContainer.removeAllViews()
            val configs = getSavedConfigs()
            val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
            val activeId = prefs.getString("active_config_id", null)

            if (configs.isEmpty()) {
                val emptyTv = TextView(this).apply {
                    text = "Список конфигураций пуст"
                    setTextColor(Color.GRAY)
                    setPadding(16.dpToPx(), 32.dpToPx(), 16.dpToPx(), 32.dpToPx())
                    gravity = android.view.Gravity.CENTER
                }
                listContainer.addView(emptyTv)
                return
            }

            for (item in configs) {
                val isActive =
                    item.id == activeId || (activeId == null && item.content == selectedConfigContent)

                val itemLayout = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    setPadding(8.dpToPx(), 12.dpToPx(), 8.dpToPx(), 12.dpToPx())
                    background = android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 14 * resources.displayMetrics.density
                        setColor(if (isActive) Color.parseColor("#375417") else Color.parseColor("#1C1C1E"))
                    }
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply {
                        setMargins(0, 6.dpToPx(), 0, 6.dpToPx())
                    }
                    setupTvFocusAnimator()
                }

                val ivIndicator = ImageView(this).apply {
                    setImageResource(if (isActive) R.drawable.leftchev else R.drawable.uncheck)
                    setColorFilter(if (isActive) Color.parseColor("#669D29") else Color.parseColor("#3b3b3b"))
                    val iconSize = 22.dpToPx()
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        setMargins(0, 0, 8.dpToPx(), 0)
                    }
                }

                val nameTv = TextView(this).apply {
                    text = item.name
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                    setTextColor(Color.WHITE)
                    layoutParams =
                        LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
                }

                itemLayout.setOnClickListener {
                    if (isVpnConnected) {
                        logToConsole("Нельзя менять конфигурацию во время активного подключения")
                        return@setOnClickListener
                    }
                    selectedConfigContent = item.content
                    selectedConfigName = item.name
                    saveConfigToPrefs(item.content, item.name)
                    prefs.edit().putString("active_config_id", item.id).apply()
                    setupServerSelector()
                    logToConsole("Выбрана конфигурация: ${item.name}")
                    populateList()
                }

                val btnRename = android.widget.ImageButton(this).apply {
                    setImageResource(R.drawable.pen)
                    setColorFilter(Color.parseColor("#AAAAAA"))
                    setBackgroundColor(Color.TRANSPARENT)
                    val iconSize = 22.dpToPx()
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                        setMargins(12.dpToPx(), 0, 12.dpToPx(), 0)
                    }
                    setOnClickListener {
                        showRenameDialog(item) {
                            populateList()
                        }
                    }
                }

                val btnDelete = android.widget.ImageButton(this).apply {
                    setImageResource(R.drawable.delete)
                    setColorFilter(Color.parseColor("#AAAAAA"))
                    setBackgroundColor(Color.TRANSPARENT)
                    val iconSize = 22.dpToPx()
                    layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
                    setOnClickListener {
                        if (isVpnConnected && isActive) {
                            logToConsole("Нельзя удалить активную конфигурацию во время подключения")
                            return@setOnClickListener
                        }
                        showDeleteConfirmation(item) {
                            populateList()
                        }
                    }
                }

                itemLayout.addView(ivIndicator)
                itemLayout.addView(nameTv)
                itemLayout.addView(btnRename)
                itemLayout.addView(btnDelete)

                listContainer.addView(itemLayout)
            }
        }

        refreshConfigListRunnable = Runnable { populateList() }
        populateList()

        val dialog = AlertDialog.Builder(this)
            .setOnDismissListener {
                activeConfigDialog = null
                refreshConfigListRunnable = null

                // Если список удалили до нуля — показываем окно ввода ссылки
                if (getSavedConfigs().isEmpty()) {
                    checkInitialUrl()
                }
            }
            .create()

        activeConfigDialog = dialog

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        dialog.show()
        dialog.setContentView(rootLayout)

        dialog.window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
    }

    private fun showRenameDialog(item: ConfigItem, onUpdated: () -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 24.dpToPx(), 24.dpToPx(), 16.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f * resources.displayMetrics.density
                setColor(Color.parseColor("#1C1C1E"))
            }
        }

        val titleTv = TextView(this).apply {
            text = "Переименовать"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16.dpToPx())
        }

        val input = EditText(this).apply {
            setText(item.name)
            setSelection(item.name.length)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#7E7E7E"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10f * resources.displayMetrics.density
                setColor(Color.parseColor("#2C2C2E"))
            }
        }

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10.dpToPx(), 0, 0)
            }
        }

        val btnCancel = Button(this).apply {
            text = "Отмена"
            setTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        val btnSave = Button(this).apply {
            text = "Сохранить"
            setTextColor(Color.parseColor("#00E676"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        buttonBar.addView(btnCancel)
        buttonBar.addView(btnSave)

        container.addView(titleTv)
        container.addView(input)
        container.addView(buttonBar)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnSave.setOnClickListener {
            val newName = input.text.toString().trim()
            if (newName.isNotEmpty()) {
                val configs = getSavedConfigs()
                val target = configs.find { it.id == item.id }
                if (target != null) {
                    target.name = newName
                    val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
                    val activeId = prefs.getString("active_config_id", null)
                    saveConfigsToPrefs(configs, activeId)

                    if (item.id == activeId || selectedConfigName == item.name) {
                        selectedConfigName = newName
                        saveConfigToPrefs(item.content, newName)
                    }
                    logToConsole("Конфигурация переименована в: $newName")
                    onUpdated()
                }
            }
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }

    private fun showDeleteConfirmation(item: ConfigItem, onUpdated: () -> Unit) {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 24.dpToPx(), 24.dpToPx(), 16.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f * resources.displayMetrics.density
                setColor(Color.parseColor("#1C1C1E"))
            }
        }

        val titleTv = TextView(this).apply {
            text = "Удаление"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 12.dpToPx())
        }

        val messageTv = TextView(this).apply {
            text = "Удалить конфигурацию \"${item.name}\"?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
            setTextColor(Color.parseColor("#CCCCCC"))
        }

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 20.dpToPx(), 0, 0)
            }
        }

        val btnCancel = Button(this).apply {
            text = "Отмена"
            setTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        val btnDelete = Button(this).apply {
            text = "Удалить"
            setTextColor(Color.parseColor("#FF5252"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        buttonBar.addView(btnCancel)
        buttonBar.addView(btnDelete)

        container.addView(titleTv)
        container.addView(messageTv)
        container.addView(buttonBar)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }
        btnDelete.setOnClickListener {
            val configs = getSavedConfigs()
            configs.removeAll { it.id == item.id }

            val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
            var activeId = prefs.getString("active_config_id", null)

            if (activeId == item.id) {
                val nextActive = configs.firstOrNull()
                if (nextActive != null) {
                    activeId = nextActive.id
                    selectedConfigContent = nextActive.content
                    selectedConfigName = nextActive.name
                    saveConfigToPrefs(nextActive.content, nextActive.name)
                    setupServerSelector()
                } else {
                    activeId = null
                    selectedConfigContent = null
                    selectedConfigName = "Unknown"
                    // Удаляем старые ключи вместо записи пустоты:
                    prefs.edit()
                        .remove("config_content")
                        .remove("config_name")
                        .remove("active_config_id")
                        .apply()
                    availableServers.clear()
                    serverSelectContainer.visibility = View.GONE
                }
            }

            // Очищаем ссылку, если список конфигов опустел
            if (configs.isEmpty()) {
                prefs.edit().remove(PREF_SUBSCRIPTION_URL).apply()
                logToConsole("Все конфигурации удалены: ссылка сброшена")
            }

            saveConfigsToPrefs(configs, activeId)
            logToConsole("Конфигурация \"${item.name}\" удалена")
            onUpdated()
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }

    // --- ДИАЛОГ ЛОГОВ ---

    // --- ДИАЛОГ ЛОГОВ ---

    private fun showLogsDialog() {
        val rootLayout = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(20, 20, 20, 20)
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24)
            }
        }

        val btnClose = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#262626"))
            }
            val buttonSize = 40.dpToPx()
            val paddingSize = 10.dpToPx()
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                setMargins(0, 0, 16.dpToPx(), 0)
            }
            setPadding(paddingSize, paddingSize, paddingSize, paddingSize)
            setupTvFocusAnimator()
        }

        val titleView = TextView(this).apply {
            text = "Системные логи"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams =
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        val btnSave = Button(this).apply {
            text = "Сохранить"
            isAllCaps = false
            setTextColor(Color.WHITE)
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 13f)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.RECTANGLE
                cornerRadius = 10.dpToPx().toFloat()
                setColor(Color.parseColor("#262626"))
            }
            setPadding(20.dpToPx(), 0, 20.dpToPx(), 0)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                40.dpToPx()
            ).apply {
                setMargins(16.dpToPx(), 0, 0, 0)
            }
            setupTvFocusAnimator()
        }

        headerLayout.addView(btnClose)
        headerLayout.addView(titleView)
        headerLayout.addView(btnSave)

        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
            divider = null
            dividerHeight = 0
            setSelector(android.R.color.transparent)
            transcriptMode = ListView.TRANSCRIPT_MODE_ALWAYS_SCROLL // Автоскролл списка при добавлении строк
            isStackFromBottom = false
        }

        val paddingV = 6.dpToPx()
        val adapter = object : ArrayAdapter<CharSequence>(this, 0, logLines) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = (convertView as? TextView) ?: TextView(context).apply {
                    setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
                    setTextColor(ContextCompat.getColor(context, R.color.white))
                    setPadding(0, paddingV, 0, paddingV)
                    setTextIsSelectable(true) // Включает выделение текста долгим тапом и меню копирования
                }
                view.text = getItem(position)
                return view
            }
        }
        listView.adapter = adapter

        rootLayout.addView(headerLayout)
        rootLayout.addView(listView)

        activeLogAdapter = adapter
        activeLogListView = listView

        val dialog = AlertDialog.Builder(this)
            .setOnDismissListener {
                activeLogAdapter = null
                activeLogListView = null
            }
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            showSaveLogDialog()
        }

        dialog.show()
        dialog.setContentView(rootLayout)
        dialog.window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }

        // Автопрокрутка в самый конец при открытии окна
        if (logLines.isNotEmpty()) {
            listView.post {
                listView.setSelection(logLines.size - 1)
            }
        }
    }

    // --- СОХРАНЕНИЕ ЛОГА В ФАЙЛ ---

    private val saveLogLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { writeLogToUri(it) }
    }

    private fun launchSaveLog(extension: String) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        try {
            saveLogLauncher.launch("anet_log_$timestamp.$extension")
        } catch (e: Exception) {
            logToConsole("Не удалось открыть диалог сохранения: ${e.message}")
        }
    }

    private fun writeLogToUri(uri: Uri) {
        try {
            contentResolver.openOutputStream(uri)?.use { outputStream ->
                OutputStreamWriter(outputStream).use { writer ->
                    for ((index, line) in logLines.withIndex()) {
                        if (index > 0) writer.write("\n")
                        writer.write(line.toString())
                    }
                }
                Toast.makeText(this, "Лог сохранён: ${getFileName(uri)}", Toast.LENGTH_SHORT).show()
                logToConsole("Лог сохранён: ${getFileName(uri)}")
            } ?: logToConsole("Не удалось открыть файл для записи лога")
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка сохранения лога: ${e.message}", Toast.LENGTH_SHORT).show()
            logToConsole("Ошибка сохранения лога: ${e.message}")
        }
    }


    // --- СОХРАНЕНИЕ ЛОГА В ФАЙЛ custom---

    private fun showSaveLogDialog() {
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 24.dpToPx(), 24.dpToPx(), 16.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f * resources.displayMetrics.density
                setColor(Color.parseColor("#1C1C1E"))
            }
        }

        val titleTv = TextView(this).apply {
            text = "В каком формате сохранить лог?"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 16.dpToPx())
        }


        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 10.dpToPx(), 0, 0)
            }
        }

        val btnCancel = Button(this).apply {
            text = "Отмена"
            setTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        val btnSaveTxt = Button(this).apply {
            text = ".txt"
            setTextColor(Color.parseColor("#00E676"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        val btnSaveLog = Button(this).apply {
            text = ".log"
            setTextColor(Color.parseColor("#00E676"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        buttonBar.addView(btnCancel)
        buttonBar.addView(btnSaveTxt)
        buttonBar.addView(btnSaveLog)

        container.addView(titleTv)
        container.addView(buttonBar)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnSaveTxt.setOnClickListener {
            launchSaveLog("txt")
            dialog.dismiss()
        }

        btnSaveLog.setOnClickListener {
            launchSaveLog("log")
            dialog.dismiss()
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }


    // --- FILE IO & PREFS ---

    private fun readTextFromUri(uri: Uri): String {
        return try {
            contentResolver.openInputStream(uri)?.use { inputStream ->
                BufferedReader(InputStreamReader(inputStream)).readText()
            } ?: ""
        } catch (e: Exception) {
            logToConsole("IO Error: ${e.message}")
            ""
        }
    }

    private fun getFileName(uri: Uri): String {
        var result: String? = null
        if (uri.scheme == "content") {
            val cursor = contentResolver.query(uri, null, null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) result = cursor.getString(index)
                }
            } finally {
                cursor?.close()
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/')
            if (cut != null && cut != -1) result = result?.substring(cut + 1)
        }
        return result ?: "config.toml"
    }

    private fun saveConfigToPrefs(content: String, name: String) {
        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        prefs.edit()
            .putString("config_content", content)
            .putString("config_name", name)
            .apply()
    }

    private fun loadConfigFromPrefs() {
        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        val configs = getSavedConfigs()

        // Миграция старых данных: создаем элемент ТОЛЬКО если есть РЕАЛЬНЫЙ непустой контент
        if (configs.isEmpty()) {
            val oldContent = prefs.getString("config_content", null)
            val oldName = prefs.getString("config_name", null)
            if (!oldContent.isNullOrBlank()) {
                val item = ConfigItem(name = oldName ?: "Config 1", content = oldContent)
                configs.add(item)
                saveConfigsToPrefs(configs, item.id)
            }
        }

        val activeId = prefs.getString("active_config_id", null)
        val activeItem = configs.find { it.id == activeId } ?: configs.firstOrNull()

        if (activeItem != null) {
            selectedConfigContent = activeItem.content
            selectedConfigName = activeItem.name
            prefs.edit().putString("active_config_id", activeItem.id).apply()
        } else {
            // Если конфигов нет совсем — полностью сбрасываем состояние
            selectedConfigContent = null
            selectedConfigName = "Unknown"
            prefs.edit()
                .remove("active_config_id")
                .remove("config_content")
                .remove("config_name")
                .apply()
        }
    }

    private fun formatSpeed(speedStr: String): String {
        val mbps = speedStr.toDoubleOrNull()
        if (mbps != null) {
            return when {
                mbps >= 1000.0 -> String.format(java.util.Locale.US, "%.2f Gbps", mbps / 1000.0)
                mbps >= 1.0 -> String.format(java.util.Locale.US, "%.2f Mbps", mbps)
                mbps >= 0.001 -> String.format(java.util.Locale.US, "%.1f Kbps", mbps * 1000.0)
                else -> "0 Kbps"
            }
        }
        return if (speedStr.isNotBlank()) speedStr else "0 B/s"
    }

    fun updateTrafficStats(
        rxTotal: String,
        txTotal: String,
        rtt: String,
        rxSpeedRaw: String,
        txSpeedRaw: String
    ) {
        val rxSpeed = formatSpeed(rxSpeedRaw)
        val txSpeed = formatSpeed(txSpeedRaw)

        runOnUiThread {
            tvRtt.text = if (rtt.isNotBlank()) rtt else "0 ms"
            tvRx.text = rxSpeed     // Скорость загрузки (напр. "18.47 Mbps" или "1.85 MiB/s")
            tvTx.text = txSpeed     // Скорость отдачи (напр. "1.86 Mbps" или "200 KiB/s")
            tvRxm.text =
                if (rxTotal.isNotBlank()) rxTotal else "0 B"   // Всего получено (напр. "2.20 MiB")
            tvTxm.text =
                if (txTotal.isNotBlank()) txTotal else "0 B"   // Всего отправлено (напр. "227.52 KiB")
        }
    }

    // --- GRAPHICS & ANIMATION DRAWABLES ---

    private fun createNeonRingDrawable(gradientColors: IntArray): Drawable {
        val strokeWidthPx = 3.dpToPx().toFloat()

        val solidBackground = android.graphics.drawable.GradientDrawable().apply {
            shape = android.graphics.drawable.GradientDrawable.OVAL
            setColor(Color.parseColor("#121212"))
        }

        val strokeDrawable = object : Drawable() {
            private val paint =
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = strokeWidthPx
                }

            override fun draw(canvas: Canvas) {
                val rect = android.graphics.RectF(
                    strokeWidthPx / 2f,
                    strokeWidthPx / 2f,
                    bounds.width() - strokeWidthPx / 2f,
                    bounds.height() - strokeWidthPx / 2f
                )
                if (paint.shader == null) {
                    paint.shader = android.graphics.SweepGradient(
                        bounds.exactCenterX(),
                        bounds.exactCenterY(),
                        gradientColors,
                        null
                    )
                }
                canvas.drawOval(rect, paint)
            }

            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(filter: ColorFilter?) {}
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }

        val layerDrawable =
            android.graphics.drawable.LayerDrawable(arrayOf(solidBackground, strokeDrawable))
        val rippleColor = android.content.res.ColorStateList.valueOf(Color.parseColor("#33FFFFFF"))

        return android.graphics.drawable.RippleDrawable(rippleColor, layerDrawable, null)
    }

    private fun Int.dpToPx(): Int {
        return (this * resources.displayMetrics.density).toInt()
    }

    private fun createAaaSpinnerDrawable(): Drawable {
        return object : Drawable() {
            private val paint =
                android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
                    style = android.graphics.Paint.Style.STROKE
                    strokeWidth = 4.dpToPx().toFloat()
                    strokeCap = android.graphics.Paint.Cap.ROUND
                }

            override fun draw(canvas: Canvas) {
                val inset = paint.strokeWidth
                val rect = android.graphics.RectF(
                    inset, inset,
                    bounds.width().toFloat() - inset,
                    bounds.height().toFloat() - inset
                )

                val centerX = bounds.exactCenterX()
                val centerY = bounds.exactCenterY()

                val colors = intArrayOf(
                    Color.TRANSPARENT,
                    Color.parseColor("#80FF7043"),
                    Color.parseColor("#FFFFCA28")
                )
                val positions = floatArrayOf(0f, 0.6f, 1f)

                val sweepGradient =
                    android.graphics.SweepGradient(centerX, centerY, colors, positions)

                val matrix = android.graphics.Matrix()
                matrix.setRotate(-90f, centerX, centerY)
                sweepGradient.setLocalMatrix(matrix)

                paint.shader = sweepGradient

                canvas.drawArc(rect, -90f, 300f, false, paint)
            }

            override fun setAlpha(alpha: Int) {}
            override fun setColorFilter(filter: ColorFilter?) {}
            override fun getOpacity(): Int = android.graphics.PixelFormat.TRANSLUCENT
        }
    }

    private fun checkInitialUrl() {
        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString(PREF_SUBSCRIPTION_URL, null)

        if (savedUrl.isNullOrBlank()) {
            showEnterUrlDialog()
        } else {
            logToConsole("Используется сохраненная ссылка: $savedUrl")
        }
    }

    // Модель пункта меню настроек
    data class SettingItem(
        val title: String,
        val subtitle: String,
        val iconRes: Int,
        val onClick: () -> Unit
    )

    // --- ГЛАВНОЕ ОКНО НАСТРОЕК (SETTINGS) ---
    private fun showSettingsDialog() {
        var dialogInstance: AlertDialog? = null
        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        val currentSubUrl = prefs.getString(PREF_SUBSCRIPTION_URL, null)

        val settingsList = listOf(
            SettingItem(
                title = "Конфигурации",
                subtitle = "Управление профилями, добавление файлов и QR",
                iconRes = R.drawable.file
            ) {
                // Открываем поверх окна настроек без dismiss
                showConfigManagerDialog()
            },
            SettingItem(
                title = "Ссылка на конфигурацию",
                subtitle = if (!currentSubUrl.isNullOrBlank()) currentSubUrl else "Не указана (нажмите для ввода)",
                iconRes = R.drawable.qr
            ) {
                // Открываем поверх окна настроек без dismiss
                showEditSubscriptionUrlDialog()
            },
            SettingItem(
                title = "Раздельное туннелирование",
                subtitle = "Выбор приложений, работающих через VPN",
                iconRes = R.drawable.apps_white
            ) {
                startActivity(Intent(this, AppSelectionActivity::class.java))
            }
        )

        val rootLayout = LinearLayout(this).apply {
            setBackgroundColor(Color.parseColor("#121212"))
            orientation = LinearLayout.VERTICAL
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setPadding(20.dpToPx(), 20.dpToPx(), 20.dpToPx(), 20.dpToPx())
        }

        val headerLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 0, 0, 24.dpToPx())
            }
        }

        val btnClose = android.widget.ImageButton(this).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            background = android.graphics.drawable.GradientDrawable().apply {
                shape = android.graphics.drawable.GradientDrawable.OVAL
                setColor(Color.parseColor("#262626"))
            }
            val buttonSize = 40.dpToPx()
            val paddingSize = 10.dpToPx()
            layoutParams = LinearLayout.LayoutParams(buttonSize, buttonSize).apply {
                setMargins(0, 0, 16.dpToPx(), 0)
            }
            setPadding(paddingSize, paddingSize, paddingSize, paddingSize)
            setupTvFocusAnimator()
        }

        val titleView = TextView(this).apply {
            text = "Настройки"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 20f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
        }

        headerLayout.addView(btnClose)
        headerLayout.addView(titleView)

        val sectionTitle = TextView(this).apply {
            text = "ПАРАМЕТРЫ ПРИЛОЖЕНИЯ"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.parseColor("#7E7E7E"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 4.dpToPx(), 0, 12.dpToPx())
            }
        }

        val scrollView = ScrollView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1.0f
            )
        }

        val listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }

        for (item in settingsList) {
            val itemCard = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = android.view.Gravity.CENTER_VERTICAL
                setPadding(16.dpToPx(), 14.dpToPx(), 16.dpToPx(), 14.dpToPx())
                background = android.graphics.drawable.GradientDrawable().apply {
                    cornerRadius = 14 * resources.displayMetrics.density
                    setColor(Color.parseColor("#1C1C1E"))
                }
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply {
                    setMargins(0, 6.dpToPx(), 0, 6.dpToPx())
                }
                isClickable = true
                isFocusable = true
                setupTvFocusAnimator()
                setOnClickListener {
                    item.onClick()
                }
            }

            val iconView = ImageView(this).apply {
                setImageResource(item.iconRes)
                setColorFilter(Color.parseColor("#EEBC7A"))
                val iconSize = 22.dpToPx()
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                    setMargins(0, 0, 14.dpToPx(), 0)
                }
            }

            val textContainer = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.0f)
            }

            val tvTitle = TextView(this).apply {
                text = item.title
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(null, android.graphics.Typeface.BOLD)
                setTextColor(Color.WHITE)
            }

            val tvSubtitle = TextView(this).apply {
                text = item.subtitle
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(Color.parseColor("#8E8E93"))
                setPadding(0, 2.dpToPx(), 0, 0)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }

            textContainer.addView(tvTitle)
            textContainer.addView(tvSubtitle)

            val chevron = ImageView(this).apply {
                setImageResource(R.drawable.chevron_down)
                setColorFilter(Color.parseColor("#5A5A5E"))
                rotation = 270f
                val iconSize = 18.dpToPx()
                layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)
            }

            itemCard.addView(iconView)
            itemCard.addView(textContainer)
            itemCard.addView(chevron)

            listContainer.addView(itemCard)
        }

        scrollView.addView(listContainer)
        rootLayout.addView(headerLayout)
        rootLayout.addView(sectionTitle)
        rootLayout.addView(scrollView)

        dialogInstance = AlertDialog.Builder(this).create()

        btnClose.setOnClickListener {
            dialogInstance.dismiss()
        }

        dialogInstance.show()
        dialogInstance.setContentView(rootLayout)
        dialogInstance.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        }
    }

    // --- ДИАЛОГ РЕДАКТИРОВАНИЯ ОСНОВНОЙ ССЫЛКИ В SETTINGS ---
    private fun showEditSubscriptionUrlDialog(returnToSettings: Boolean = true) {
        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        val currentUrl = prefs.getString(PREF_SUBSCRIPTION_URL, "") ?: ""

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dpToPx(), 24.dpToPx(), 24.dpToPx(), 16.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 16f * resources.displayMetrics.density
                setColor(Color.parseColor("#1C1C1E"))
            }
        }

        val titleTv = TextView(this).apply {
            text = "Ссылка на конфигурацию"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 18f)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(0, 0, 0, 6.dpToPx())
        }

        val subtitleTv = TextView(this).apply {
            text = "Используется для автоматического обновления профилей"
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            setTextColor(Color.parseColor("#8E8E93"))
            setPadding(0, 0, 0, 16.dpToPx())
        }

        val input = EditText(this).apply {
            setText(currentUrl)
            setSelection(currentUrl.length)
            hint = "https://example.com/config.toml"
            setTextColor(Color.WHITE)
            setHintTextColor(Color.parseColor("#7E7E7E"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f)
            setPadding(16.dpToPx(), 12.dpToPx(), 16.dpToPx(), 12.dpToPx())
            background = android.graphics.drawable.GradientDrawable().apply {
                cornerRadius = 10f * resources.displayMetrics.density
                setColor(Color.parseColor("#2C2C2E"))
            }
        }

        val errorTv = TextView(this).apply {
            setTextColor(Color.parseColor("#FF5252"))
            setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 12f)
            visibility = View.GONE
            setPadding(4.dpToPx(), 6.dpToPx(), 4.dpToPx(), 0)
        }

        val loadingBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = true
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 8.dpToPx(), 0, 0)
            }
        }

        val buttonBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                setMargins(0, 14.dpToPx(), 0, 0)
            }
        }

        // Кнопка "Очистить" (если ссылка уже была задана)
        val btnClear = Button(this).apply {
            text = "Очистить"
            setTextColor(Color.parseColor("#FF5252"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        // Кнопка "Отмена"
        val btnCancel = Button(this).apply {
            text = "Отмена"
            setTextColor(Color.parseColor("#AAAAAA"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        // Кнопка "Сохранить"
        val btnSave = Button(this).apply {
            text = "Сохранить"
            setTextColor(Color.parseColor("#00E676"))
            setBackgroundColor(Color.TRANSPARENT)
            setupTvFocusAnimator()
        }

        if (currentUrl.isNotEmpty()) {
            buttonBar.addView(btnClear)
        }
        buttonBar.addView(btnCancel)
        buttonBar.addView(btnSave)

        container.addView(titleTv)
        container.addView(subtitleTv)
        container.addView(input)
        container.addView(errorTv)
        container.addView(loadingBar)
        container.addView(buttonBar)

        val dialog = AlertDialog.Builder(this)
            .setView(container)
            .create()

        btnCancel.setOnClickListener {
            dialog.dismiss()
        }

        btnClear.setOnClickListener {
            prefs.edit().remove(PREF_SUBSCRIPTION_URL).apply()
            logToConsole("Основная ссылка удалена")
            Toast.makeText(this, "Ссылка удалена", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
        }

        btnSave.setOnClickListener {
            val newUrl = input.text.toString().trim()

            if (newUrl.isEmpty()) {
                errorTv.text = "Ссылка не может быть пустой. Используйте 'Очистить' для удаления."
                errorTv.visibility = View.VISIBLE
                return@setOnClickListener
            }

            if (!newUrl.startsWith("http://", ignoreCase = true) && !newUrl.startsWith("https://", ignoreCase = true)) {
                errorTv.text = "Ссылка должна начинаться с http:// или https://"
                errorTv.visibility = View.VISIBLE
                return@setOnClickListener
            }

            // Блокируем кнопки и проверяем загрузку
            btnSave.isEnabled = false
            btnCancel.isEnabled = false
            btnClear.isEnabled = false
            errorTv.visibility = View.GONE
            loadingBar.visibility = View.VISIBLE

            downloadAndApplyConfigFromUrl(
                initialUrl = newUrl,
                onSuccess = {
                    dialog.dismiss()
                },
                onError = { error ->
                    btnSave.isEnabled = true
                    btnCancel.isEnabled = true
                    btnClear.isEnabled = true
                    loadingBar.visibility = View.GONE
                    errorTv.text = error
                    errorTv.visibility = View.VISIBLE
                }
            )
        }

        dialog.show()
        dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
    }


    private fun downloadAndApplyConfigFromUrl(
        initialUrl: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        logToConsole("Загрузка конфигурации из URL: $initialUrl")

        Thread {
            var currentUrl = initialUrl
            var redirectsCount = 0
            val maxRedirects = 5
            var content: String? = null

            // 1. Отключаем строгую проверку SSL-сертификатов (для обхода "Not secure")
            val trustAllCerts = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<X509Certificate> = arrayOf()
                override fun checkClientTrusted(certs: Array<X509Certificate>, authType: String) {}
                override fun checkServerTrusted(certs: Array<X509Certificate>, authType: String) {}
            })

            try {
                val sc = SSLContext.getInstance("TLS")
                sc.init(null, trustAllCerts, SecureRandom())
                HttpsURLConnection.setDefaultSSLSocketFactory(sc.socketFactory)
                HttpsURLConnection.setDefaultHostnameVerifier { _, _ -> true }
            } catch (e: Exception) {
                Log.e("ANet", "SSL TrustAll setup failed: ${e.message}")
            }

            while (redirectsCount < maxRedirects) {
                var connection: HttpURLConnection? = null
                try {
                    val urlObj = URL(currentUrl)
                    connection = urlObj.openConnection() as HttpURLConnection
                    connection.connectTimeout = 12000
                    connection.readTimeout = 12000
                    connection.requestMethod = "GET"
                    connection.instanceFollowRedirects = false // Обрабатываем редиректы вручную
                    connection.setRequestProperty(
                        "User-Agent",
                        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
                    )
                    connection.setRequestProperty("Accept", "*/*")

                    val status = connection.responseCode

                    // 2. Обработка 301, 302, 303, 307, 308 редиректов
                    if (status == HttpURLConnection.HTTP_MOVED_PERM ||
                        status == HttpURLConnection.HTTP_MOVED_TEMP ||
                        status == HttpURLConnection.HTTP_SEE_OTHER ||
                        status == 307 || status == 308
                    ) {
                        val newUrl = connection.getHeaderField("Location")
                        if (!newUrl.isNullOrBlank()) {
                            // Если редирект относительный (напр. "/config.toml")
                            currentUrl = if (newUrl.startsWith("http://") || newUrl.startsWith("https://")) {
                                newUrl
                            } else {
                                URL(urlObj, newUrl).toString()
                            }
                            logToConsole("Редирект ($status) -> $currentUrl")
                            redirectsCount++
                            continue
                        }
                    }

                    if (status == HttpURLConnection.HTTP_OK) {
                        content = connection.inputStream.bufferedReader().use { it.readText() }
                        break
                    } else {
                        runOnUiThread {
                            onError("Сервер вернул код: HTTP $status")
                        }
                        return@Thread
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        val errorMsg = "Ошибка сети: ${e.localizedMessage ?: e.message}"
                        logToConsole(errorMsg)
                        onError(errorMsg)
                    }
                    return@Thread
                } finally {
                    connection?.disconnect()
                }
            }

            if (content.isNullOrBlank()) {
                runOnUiThread {
                    onError("Не удалось получить содержимое файла (превышен лимит редиректов или пустой ответ)")
                }
                return@Thread
            }

            // 3. Проверяем, является ли скачанный файл валидным TOML-конфигом ANet
            val servers = inspectServers(content, reportError = false)
            if (servers != null && servers.isNotEmpty()) {
                val rawName = currentUrl.substringAfterLast("/").substringBefore("?").ifBlank { "Subscription" }
                val configName = if (rawName.endsWith(".toml", ignoreCase = true)) {
                    rawName.substringBeforeLast(".toml")
                } else {
                    rawName
                }

                runOnUiThread {
                    // Добавляем и активируем конфигурацию
                    addAndActivateConfig(configName, content)

                    // Сохраняем исходный URL в SharedPreferences
                    val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
                    prefs.edit().putString(PREF_SUBSCRIPTION_URL, initialUrl).apply()

                    logToConsole("Конфигурация '$configName' успешно скачана и активирована!")
                    Toast.makeText(this@MainActivity, "Активирован профиль: $configName", Toast.LENGTH_SHORT).show()
                    onSuccess()
                }
            } else {
                runOnUiThread {
                    val errorMsg = "Файл по ссылке не является корректным TOML-конфигом ANet"
                    logToConsole("Ошибка: $errorMsg")
                    onError(errorMsg)
                }
            }
        }.start()
    }
}