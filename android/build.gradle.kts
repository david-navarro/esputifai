// Security floor for the build tooling (not shipped in the APK). Only critical CVEs are patched
// here; revisit when AGP is upgraded and drop the line once AGP brings a fixed version itself.
buildscript {
    dependencies {
        constraints {
            // CVE-2026-8763 (critical, name-constraints bypass) in Bouncy Castle < 1.85, pulled in
            // by AGP for APK signing. The three modules move together.
            listOf("bcprov", "bcpkix", "bcutil").forEach {
                classpath("org.bouncycastle:$it-jdk18on:1.85")
            }
        }
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
