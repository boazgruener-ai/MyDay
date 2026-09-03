plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}

// Build outputs are redirected outside the OneDrive-synced project folder: OneDrive's file
// sync grabs locks on newly-created build directories mid-write, causing intermittent
// AccessDeniedException failures. Source code stays in the synced folder as normal.
allprojects {
    layout.buildDirectory.set(File("C:/Users/41793/myday-gradle-build/${project.name}"))
}
