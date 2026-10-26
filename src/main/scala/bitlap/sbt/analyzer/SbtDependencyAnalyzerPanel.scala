package bitlap.sbt.analyzer

import java.awt.{ BorderLayout, GridBagConstraints, GridBagLayout, Insets }
import javax.swing.*

import com.intellij.openapi.options.ConfigurationException
import com.intellij.openapi.project.Project

/** Labels use the plugin bundle; layout follows font and UI scaling instead of fixed pixel sizes. */
final class SbtDependencyAnalyzerPanel(project: Project) {
  private val settings    = SettingsState.getSettings(project)
  val organization        = new JTextField(28)
  val fileCache           = new JTextField(10)
  val compileCheckBox     = new JCheckBox("Compile")
  val providedCheckBox    = new JCheckBox("Provided")
  val testCheckBox        = new JCheckBox("Test")
  val mainPanel           = new JPanel(new BorderLayout())
  private val fields      = new JPanel(new GridBagLayout())
  private val constraints = new GridBagConstraints()
  constraints.insets = new Insets(4, 4, 4, 4)
  constraints.anchor = GridBagConstraints.WEST

  private def row(key: String, component: JComponent, index: Int): Unit = {
    val label = new JLabel(SbtDependencyAnalyzerBundle.message(key))
    label.setLabelFor(component)
    constraints.gridy = index; constraints.gridx = 0; constraints.weightx = 0;
    constraints.fill = GridBagConstraints.NONE
    fields.add(label, constraints)
    constraints.gridx = 1; constraints.weightx = 1; constraints.fill = GridBagConstraints.HORIZONTAL
    fields.add(component, constraints)
  }
  row("analyzer.settings.cache", fileCache, 0)
  row("analyzer.settings.organization", organization, 1)
  val scopes = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEADING, 0, 0))
  scopes.add(compileCheckBox); scopes.add(providedCheckBox); scopes.add(testCheckBox)
  row("analyzer.settings.disabledScopes", scopes, 2)
  row("analyzer.settings.cacheHint", new JLabel(""), 3)
  mainPanel.add(fields, BorderLayout.NORTH)
  reset()

  def isModified: Boolean = settings.organization != organization.getText.trim ||
    settings.fileCacheTimeout.toString != fileCache.getText.trim ||
    settings.disableAnalyzeCompile != compileCheckBox.isSelected ||
    settings.disableAnalyzeProvided != providedCheckBox.isSelected || settings.disableAnalyzeTest != testCheckBox.isSelected

  def apply(): Unit = {
    val timeout = SettingsValidation
      .cacheTimeout(fileCache.getText)
      .getOrElse(
        throw new ConfigurationException(SbtDependencyAnalyzerBundle.message("analyzer.settings.invalidCache"))
      )
    val changed = isModified
    settings.organization = organization.getText.trim
    settings.fileCacheTimeout = timeout
    settings.disableAnalyzeCompile = compileCheckBox.isSelected
    settings.disableAnalyzeProvided = providedCheckBox.isSelected
    settings.disableAnalyzeTest = testCheckBox.isSelected
    if (changed) SettingsState.SettingsChangePublisher.onConfigurationChanged(project, settings)
  }

  def reset(): Unit = {
    organization.setText(Option(settings.organization).getOrElse(""))
    fileCache.setText(settings.fileCacheTimeout.toString)
    compileCheckBox.setSelected(settings.disableAnalyzeCompile)
    providedCheckBox.setSelected(settings.disableAnalyzeProvided)
    testCheckBox.setSelected(settings.disableAnalyzeTest)
  }
}

object SettingsValidation {
  def cacheTimeout(text: String): Option[Int] = Option(text).flatMap(_.trim.toIntOption).filter(_ >= 0)
}
