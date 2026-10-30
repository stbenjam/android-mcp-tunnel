plugins { id("com.android.application") version "9.3.2" apply false }

// Optional local override keeps generated files out of cloud-synced source folders.
val localSettings = java.util.Properties().apply {
    val source = rootProject.file("local.properties")
    if (source.exists()) source.inputStream().use { load(it) }
}
localSettings.getProperty("phoneLink.buildRoot")?.let { directory ->
    allprojects { layout.buildDirectory.set(file("$directory/${if (this == rootProject) "root" else name}")) }
}
