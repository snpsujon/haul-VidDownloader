package app.haul.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CrashReportsTest {
    @Test fun summaryNamesTheExceptionAndMessage() {
        val report = """
            Haul crash report
            Thread: main

            java.lang.IllegalStateException: Expected v1.2 but got null
            	at app.haul.Foo.bar(Foo.kt:12)
        """.trimIndent()
        assertEquals("IllegalStateException: Expected v1.2 but got null", CrashReports.summary(report))
    }

    @Test fun summaryWithoutMessage() {
        assertEquals("NullPointerException", CrashReports.summary("header\n\njava.lang.NullPointerException\n\tat x"))
        assertEquals("", CrashReports.summary("only a header"))
    }
}
