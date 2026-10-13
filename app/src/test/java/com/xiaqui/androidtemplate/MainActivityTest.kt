package com.xiaqui.androidtemplate

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class MainActivityTest {
    @Test
    fun helloAndroidScreenUsesRedBackgroundAndWhiteText() {
        val sourcePath = Paths.get(
            "src/main/java/com/xiaqui/androidtemplate/MainActivity.kt"
        )
        val source = String(Files.readAllBytes(sourcePath))

        assertTrue(source.contains("setBackgroundColor(Color.RED)"))
        assertTrue(source.contains("setTextColor(Color.WHITE)"))
    }
}
