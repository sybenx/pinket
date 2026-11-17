// Aaro's Wisp: own app id so our signed releases (Obtainium) never clash with upstream's
extra["baseApplicationId"] = "cc.aaro.wisp"
extra["debugApplicationIdSuffix"] = ".debug"

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.objectbox) apply false
}
