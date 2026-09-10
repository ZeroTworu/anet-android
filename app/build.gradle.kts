plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "org.alco.anet"
    compileSdk {
        version = release(34)
    }

    defaultConfig {
        applicationId = "org.alco.anet"
        minSdk = 24
        targetSdk = 34
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toInt() ?: 1
        versionName = "1.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // ВАЖНО: релизный ключ должен быть одним и тем же для всех сборок
    // (локальных и CI), иначе Android откажется устанавливать обновление
    // поверх ранее установленной версии ("Приложение не установлено" —
    // конфликт сертификатов подписи). Раньше здесь стоял debug-keystore,
    // который на каждом CI-раннере генерируется заново и каждый раз разный.
    //
    // Сам .jks в репозиторий не коммитится (см. .gitignore) — в CI он
    // восстанавливается из секрета ANDROID_KEYSTORE_BASE64 перед сборкой.
    // Пароли нигде не хардкодятся: без ANDROID_KEYSTORE_PASSWORD /
    // ANDROID_KEY_ALIAS / ANDROID_KEY_PASSWORD в окружении release-сборка
    // просто не соберётся — так и задумано. Локально положи свою копию
    // keystore в app/keystore/anet-release.jks и экспортируй те же
    // переменные окружения перед сборкой.
    signingConfigs {
        create("release") {
            storeFile = file("keystore/anet-release.jks")
            storePassword = System.getenv("ANDROID_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("ANDROID_KEY_ALIAS")
            keyPassword = System.getenv("ANDROID_KEY_PASSWORD")
            enableV1Signing = true
            enableV2Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)

    // Нативный сканер QR-кодов от Google
    implementation(libs.play.services.code.scanner)

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}