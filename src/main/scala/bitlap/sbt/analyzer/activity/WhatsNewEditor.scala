package bitlap.sbt.analyzer.activity

import java.beans.PropertyChangeListener
import javax.swing.{ JComponent, JEditorPane }
import javax.swing.event.HyperlinkEvent

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.{ DumbAware, Project }
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.JBScrollPane

/** Bundled HTML uses Swing so opening release notes never depends on a network connection or JCEF. */
private[analyzer] final class WhatsNewFile(title: String, val html: String) extends LightVirtualFile(title) {
  setWritable(false)
}

final class WhatsNewEditorProvider extends FileEditorProvider with DumbAware {
  override def accept(project: Project, file: VirtualFile): Boolean = file.isInstanceOf[WhatsNewFile]

  override def createEditor(project: Project, file: VirtualFile): FileEditor =
    new WhatsNewEditor(file.asInstanceOf[WhatsNewFile])
  override def getEditorTypeId: String     = "sbt-dependency-analyzer-whats-new"
  override def getPolicy: FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

private[analyzer] final class WhatsNewEditor(file: WhatsNewFile) extends UserDataHolderBase with FileEditor {
  private val pane = new JEditorPane("text/html", file.html)
  pane.setEditable(false)
  pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, java.lang.Boolean.TRUE)
  pane.setFont(com.intellij.util.ui.UIUtil.getLabelFont)
  pane.setCaretPosition(0)

  pane.addHyperlinkListener(event =>
    if (event.getEventType == HyperlinkEvent.EventType.ACTIVATED && event.getURL != null)
      BrowserUtil.browse(event.getURL)
  )
  private val scroll   = new JBScrollPane(pane)
  private var disposed = false

  override def getComponent: JComponent                                             = scroll
  override def getPreferredFocusedComponent: JComponent                             = pane
  override def getName: String                                                      = file.getName
  override def getFile: VirtualFile                                                 = file
  override def setState(state: FileEditorState): Unit                               = ()
  override def isModified: Boolean                                                  = false
  override def isValid: Boolean                                                     = !disposed
  override def addPropertyChangeListener(listener: PropertyChangeListener): Unit    = ()
  override def removePropertyChangeListener(listener: PropertyChangeListener): Unit = ()
  override def dispose(): Unit                                                      = { disposed = true }
}
