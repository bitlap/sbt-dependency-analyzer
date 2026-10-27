package bitlap.sbt.analyzer

import java.util.*

import org.jetbrains.annotations.Nls
import org.jetbrains.annotations.NotNull
import org.jetbrains.annotations.PropertyKey

import com.intellij.*
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.registry.Registry

import SbtDependencyAnalyzerBundle.*

final class SbtDependencyAnalyzerBundle(private val pathToBundle: String) extends AbstractBundle(pathToBundle):
  private val adaptedControl = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES)

  private lazy val adaptedBundle: AbstractBundle = {
    val dynamicLocale = getDynamicLocale
    if dynamicLocale != null then
      if (dynamicLocale.toLanguageTag == Locale.ENGLISH.toLanguageTag) {
        new AbstractBundle(pathToBundle) {
          override def findBundle(
            pathToBundle: String,
            loader: ClassLoader,
            control: ResourceBundle.Control
          ): ResourceBundle = {
            val dynamicBundle = ResourceBundle.getBundle(pathToBundle, dynamicLocale, loader, adaptedControl)
            if dynamicBundle == null then super.findBundle(pathToBundle, loader, control) else dynamicBundle
          }
        }
      } else null
    else null
  }

  def getAdaptedMessage(@PropertyKey(resourceBundle = BUNDLE) key: String, params: Any*): String = {
    if (adaptedBundle != null) adaptedBundle.getMessage(key, params*) else getMessage(key, params*)
  }

  override def findBundle(
    pathToBundle: String,
    loader: ClassLoader,
    control: ResourceBundle.Control
  ): ResourceBundle =
    val dynamicLocale = getDynamicLocale
    if dynamicLocale != null then
      if forceFollowLanguagePack then ResourceBundle.getBundle(pathToBundle, dynamicLocale, loader, adaptedControl)
      else ResourceBundle.getBundle(pathToBundle, dynamicLocale, loader, control)
    else super.findBundle(pathToBundle, loader, control)

  end findBundle

end SbtDependencyAnalyzerBundle

/** Resolves localized analyzer messages from the plugin resource bundle. */
object SbtDependencyAnalyzerBundle:
  private val LOG = Logger.getInstance(classOf[SbtDependencyAnalyzerBundle])

  private lazy val forceFollowLanguagePack: Boolean = {
    Registry.get("bitlap.sbt.analyzer.SbtDependencyAnalyzerBundle").asBoolean()
  }

  private lazy val getDynamicLocale: Locale = {
    try {
      DynamicBundle.getLocale
    } catch {
      case _: NoSuchMethodError =>
        LOG.debug("NoSuchMethodError: DynamicBundle.getLocale()")
        null
    }
  }

  final val BUNDLE = "messages.SbtDependencyAnalyzerBundle"

  final val INSTANCE = new SbtDependencyAnalyzerBundle(BUNDLE)

  @Nls def message(@NotNull @PropertyKey(resourceBundle = BUNDLE) key: String, @NotNull params: AnyRef*): String =
    INSTANCE.getAdaptedMessage(key, params*)

end SbtDependencyAnalyzerBundle
