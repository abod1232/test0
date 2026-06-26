plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    // لا نحتاج لـ protobuf plugin هنا، سنضيف المكتبة فقط
}

android {
    namespace = "com.arabseed" // أو com.cimatn حسب مشروعك
    compileSdk = 34
    defaultConfig {
        minSdk = 21
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
        freeCompilerArgs.add("-XXLanguage:+BreakContinueInInlineLambdas")
    }
}

    // إعدادات لتجنب تعارض الملفات
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
        }
    }
}

// لا حاجة لتعريف repositories هنا، الملف الرئيسي يتولى المهمة

dependencies {
    val cloudstream by configurations

    // --- Android Standard Libraries ---
    implementation("com.google.android.material:material:1.13.0")
    implementation("androidx.browser:browser:1.9.0")
    implementation("com.squareup.duktape:duktape-android:1.4.0")
    implementation("androidx.room:room-ktx:2.8.0")
    testImplementation("junit:junit:4.13.2")

    // --- CloudStream Core ---
        cloudstream("com.lagradost:cloudstream3:pre-release")



    // --- Desugaring (Time/Date Support) ---
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")



    implementation("org.jsoup:jsoup:1.17.2")

    // 3. JavaScript Engine (مهم جداً لفك التشفير)
    implementation("org.mozilla:rhino:1.7.14")
    // implementation("org.mozilla:rhino-engine:1.7.14") // عادة لا نحتاجه في أندرويد

    // 4. Protobuf (يستخدمه يوتيوب للبيانات الحديثة)
    // يجب استخدام النسخة 'javalite' للأندرويد لتجنب تضخم حجم التطبيق
    implementation("com.google.protobuf:protobuf-javalite:3.25.1")

    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.265.2")
    implementation("com.github.spotbugs:spotbugs-annotations:4.8.2")
    implementation("com.google.code.findbugs:jsr305:3.0.2")

    // 6. Kotlin Standard Library (مضمنة عادة، لكن للتأكيد)
    implementation(kotlin("stdlib-jdk8"))
}