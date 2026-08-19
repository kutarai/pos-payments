plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    // Kotlin 2.x compiles Compose through this plugin. It replaces
    // composeOptions.kotlinCompilerExtensionVersion, which no longer exists.
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
}

android {
    namespace = "com.synergy.payments"
    compileSdk = 34

    defaultConfig {
        minSdk = 29
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    buildFeatures { compose = true }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
    }
}

// jvmTarget moved out of android.kotlinOptions in Kotlin 2.x; setting it there is an error
// rather than a warning, which is what stopped this library configuring inside a host build
// on a newer Kotlin.
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_1_8)
    }
}

dependencies {
    // api rather than implementation throughout: these types appear in this library's own
    // signatures — Money on a request, a Composable on a dialog — so an application cannot
    // consume it without them on its compile classpath.
    api(platform("androidx.compose:compose-bom:2024.10.00"))
    api("androidx.compose.ui:ui")
    api("androidx.compose.ui:ui-graphics")
    api("androidx.compose.material3:material3")
    api("androidx.compose.material:material-icons-extended")
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    api("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    api("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    api("org.jetbrains.kotlinx:kotlinx-datetime:0.5.0")

    // The switch link. gRPC types cross the boundary in SwitchClient's signatures.
    api("io.grpc:grpc-okhttp:1.62.2")
    api("io.grpc:grpc-protobuf-lite:1.62.2")
    api("io.grpc:grpc-stub:1.62.2")
    api("io.grpc:grpc-kotlin-stub:1.4.1")
    api("com.google.protobuf:protobuf-kotlin-lite:3.25.3")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")

    // QR bitmaps for the customer to scan.
    implementation("com.google.zxing:core:3.3.0")

    testImplementation("junit:junit:4.13.2")
}

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.3" }
    plugins {
        create("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.62.2" }
        create("grpckt") { artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar" }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") { option("lite") }
                create("kotlin") { option("lite") }
            }
            task.plugins {
                create("grpc") { option("lite") }
                create("grpckt") { option("lite") }
            }
        }
    }
}
