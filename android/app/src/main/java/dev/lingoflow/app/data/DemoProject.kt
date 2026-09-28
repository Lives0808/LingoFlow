package dev.lingoflow.app.data

import android.content.Context
import dev.lingoflow.app.core.project.DemoProjectData
import dev.lingoflow.app.core.project.ProjectSource
import java.io.File

/** Materialises the bundled demo project on first use so the app works instantly. */
object DemoProject {

    fun ensure(context: Context): ProjectSource {
        val root = File(context.filesDir, "demo-project")
        val source = LocalFileSource(root, DemoProjectData.NAME)
        if (File(root, "lingoflow.config.json").exists()) return source
        DemoProjectData.files.forEach { (path, content) -> source.write(path, content) }
        return source
    }
}
