package org.alco.anet

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

// Моделька данных
data class AppInfo(
    val name: String,
    val packageName: String,
    var isSelected: Boolean
)

class AppSelectionActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var loadingSpinner: ProgressBar
    private lateinit var btnSave: Button

    private val appList = mutableListOf<AppInfo>()
    private val selectedApps = mutableSetOf<String>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_app_selection)

        recyclerView = findViewById(R.id.recyclerViewApps)
        loadingSpinner = findViewById(R.id.loadingSpinner)
        btnSave = findViewById(R.id.btnSave)

        recyclerView.layoutManager = LinearLayoutManager(this)

        // Достаем сохраненный выбор
        val prefs = getSharedPreferences("anet_prefs", Context.MODE_PRIVATE)
        prefs.getStringSet("allowed_apps", emptySet())?.let {
            selectedApps.addAll(it)
        }

        btnSave.setOnClickListener {
            // Сохраняем в Preferences
            prefs.edit().putStringSet("allowed_apps", selectedApps).apply()
            finish() // Закрываем экран
        }

        // Загрузка приложений может быть медленной, пускаем в фоне
        Thread { loadInstalledApps() }.start()
    }

    private fun loadInstalledApps() {
        val pm = packageManager

        // Запрашиваем список всех приложений ВМЕСТЕ с их запрошенными разрешениями (Permissions)
        val flags = PackageManager.GET_PERMISSIONS
        val packages = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.getInstalledPackages(PackageManager.PackageInfoFlags.of(flags.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getInstalledPackages(flags)
        }

        for (packInfo in packages) {
            val pkgName = packInfo.packageName
            val appInfo = packInfo.applicationInfo

            // Пропускаем мусор: системные сервисы без интерфейса или нас самих
            if (appInfo == null || pkgName == packageName) continue

            // 1-Й ФИЛЬТР: Отсекаем служебные либы и фоновые демоны.
            // Если Android не знает, как "открыть" это приложение кнопкой на экране — нам оно не нужно.
            if (pm.getLaunchIntentForPackage(pkgName) == null) continue

            // 2-Й ФИЛЬТР: СЕГРЕГАЦИЯ БЕЗ СЕТИ
            // Смотрим массив permissions из манифеста приложения.
            // Ищем строку "android.permission.INTERNET". Если её там нет — апп идёт в /dev/null
            val permissions = packInfo.requestedPermissions
            val hasInternet = permissions?.contains(android.Manifest.permission.INTERNET) == true

            if (!hasInternet) {
                // Если приложение не умеет в сеть (как "Калькулятор" или "Меню SIM-карты")
                continue
            }

            val appName = appInfo.loadLabel(pm).toString()

            appList.add(AppInfo(appName, pkgName, selectedApps.contains(pkgName)))
        }

        // Красивая сортировка: сначала включенные юзером, затем по алфавиту
        appList.sortBy { !it.isSelected }
        appList.sortWith(compareBy({ !it.isSelected }, { it.name.lowercase() }))

        runOnUiThread {
            loadingSpinner.visibility = View.GONE
            recyclerView.adapter = AppAdapter()
        }
    }

    // Внутренний Адаптер для отрисовки
    inner class AppAdapter : RecyclerView.Adapter<AppAdapter.ViewHolder>() {
        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.appIcon)
            val name: TextView = view.findViewById(R.id.appName)
            val checkbox: CheckBox = view.findViewById(R.id.appCheckBox)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            val app = appList[position]
            holder.name.text = app.name

            try { // Достаем иконки прям на лету (тяжелая операция, но для простого VPN - ок)
                holder.icon.setImageDrawable(packageManager.getApplicationIcon(app.packageName))
            } catch (e: Exception) { holder.icon.setImageDrawable(null) }

            // Обязательно снимаем листнер, прежде чем дергать isChecked
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = app.isSelected

            val toggleAction = {
                app.isSelected = !app.isSelected
                holder.checkbox.isChecked = app.isSelected
                if (app.isSelected) selectedApps.add(app.packageName) else selectedApps.remove(app.packageName)
            }

            holder.checkbox.setOnCheckedChangeListener { _, _ -> toggleAction() }
            holder.itemView.setOnClickListener { toggleAction() }
        }

        override fun getItemCount() = appList.size
    }
}
