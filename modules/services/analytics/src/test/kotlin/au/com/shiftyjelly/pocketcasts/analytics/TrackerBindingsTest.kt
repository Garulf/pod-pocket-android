package au.com.shiftyjelly.pocketcasts.analytics

import au.com.shiftyjelly.pocketcasts.analytics.di.AnalyticsModule
import dagger.multibindings.IntoSet
import java.io.File
import java.util.zip.ZipFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class TrackerBindingsTest {
    @Test
    fun `the only tracker in the analytics module is the no-op tracker`() {
        val trackerBindings = diPackageClasses()
            .flatMap { it.declaredMethods.asList() }
            .filter { it.isAnnotationPresent(IntoSet::class.java) && it.returnType == AnalyticsTracker::class.java }
            .map { "${it.declaringClass.simpleName}.${it.name}" }

        assertEquals(listOf("Companion.provideNoOpTracker"), trackerBindings)
        assertSame(NoOpTracker, AnalyticsModule.provideNoOpTracker())
    }

    private fun diPackageClasses(): List<Class<*>> {
        val packageName = AnalyticsModule::class.java.`package`!!.name
        val packagePath = packageName.replace('.', '/')
        val classesRoot = File(AnalyticsModule::class.java.protectionDomain!!.codeSource.location.toURI())
        val classPaths = if (classesRoot.isDirectory) {
            classesRoot.resolve(packagePath).listFiles().orEmpty().map { "$packagePath/${it.name}" }
        } else {
            ZipFile(classesRoot).use { zip -> zip.entries().toList().map { it.name } }
        }
        return classPaths
            .filter { it.startsWith("$packagePath/") && it.endsWith(".class") }
            .map { Class.forName(it.removeSuffix(".class").replace('/', '.')) }
    }
}
