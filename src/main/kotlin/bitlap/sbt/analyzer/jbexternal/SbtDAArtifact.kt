package bitlap.sbt.analyzer.jbexternal

import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.externalSystem.dependency.analyzer.DependencyAnalyzerDependency as Dependency
import com.intellij.util.concurrency.AppExecutorUtil
import javax.swing.SwingUtilities

/** Artifact identity based only on group, artifact and version; asynchronously computed sizes do not affect equality.
 * Size getters return zero until computation finishes, which is also the value for a missing library.
 */
class SbtDAArtifact(
    private val project: com.intellij.openapi.project.Project,
    override val groupId: String,
    override val artifactId: String,
    override val version: String,
    private val ownSize: () -> Long,
    private val closureSize: () -> Long
) : UserDataHolderBase(), Dependency.Data.Artifact {
    @Volatile private var computedSize: Long? = null
    @Volatile private var computedTotal: Long? = null
    private val computing = java.util.concurrent.atomic.AtomicBoolean()
    val size: Long get() = computedSize ?: 0L
    val totalSize: Long get() = computedTotal ?: 0L
    /** Schedules at most one size calculation at a time and invokes onReady on the EDT after success.
     * Project disposal expires the read action; a failed calculation can be retried by a subsequent call.
     */
    fun loadSize(onReady: () -> Unit) {
        if (computedTotal != null || !computing.compareAndSet(false, true)) return
        project.getService(ArtifactSizeLoader::class.java).execute {
            try {
                val sizes = ReadAction.nonBlocking(java.util.concurrent.Callable { ownSize() to closureSize() })
                    .expireWith(project).executeSynchronously()
                computedSize = sizes.first
                computedTotal = sizes.second
                SwingUtilities.invokeLater { if (!project.isDisposed) onReady() }
            } finally { computing.set(false) }
        }
    }
    override fun equals(other: Any?) = other is SbtDAArtifact && groupId == other.groupId && artifactId == other.artifactId && version == other.version
    override fun hashCode() = arrayOf(groupId, artifactId, version).contentHashCode()
    override fun toString() = "$groupId:$artifactId:$version"
}

/** Bounds concurrent JAR-size work to two workers and shuts the executor down with the project. */
@com.intellij.openapi.components.Service(com.intellij.openapi.components.Service.Level.PROJECT)
class ArtifactSizeLoader(private val project: com.intellij.openapi.project.Project) : com.intellij.openapi.Disposable {
    private val executor = AppExecutorUtil.createBoundedApplicationPoolExecutor("SbtDependencyAnalyzer.sizes", 2)
    fun execute(task: () -> Unit) {
        if (!project.isDisposed) executor.execute { if (!project.isDisposed) task() }
    }
    override fun dispose() { executor.shutdownNow() }
}
