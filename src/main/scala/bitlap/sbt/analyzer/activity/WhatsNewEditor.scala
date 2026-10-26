package bitlap.sbt.analyzer.activity

import java.awt.{ BorderLayout, Dimension, Rectangle }
import java.awt.event.{ ComponentAdapter, ComponentEvent }
import java.beans.PropertyChangeListener
import javax.swing.{ JComponent, JPanel, Scrollable, ScrollPaneConstants, SwingConstants }
import javax.swing.event.HyperlinkEvent

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.{ DumbAware, Project }
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.testFramework.LightVirtualFile
import com.intellij.ui.components.{ JBHtmlPane, JBHtmlPaneConfiguration, JBScrollPane }
import com.intellij.util.ui.{ JBUI, UIUtil }

/** Read-only virtual file containing bundled release-note HTML; creation requires no network or JCEF. */
private[analyzer] final class WhatsNewFile(title: String, val html: String) extends LightVirtualFile(title) {
  setWritable(false)
}

/** Replaces the default text editor only for bundled WhatsNewFile instances, including during indexing. */
final class WhatsNewEditorProvider extends FileEditorProvider with DumbAware {
  override def accept(project: Project, file: VirtualFile): Boolean = file.isInstanceOf[WhatsNewFile]

  override def createEditor(project: Project, file: VirtualFile): FileEditor =
    new WhatsNewEditor(file.asInstanceOf[WhatsNewFile])
  override def getEditorTypeId: String     = "sbt-dependency-analyzer-whats-new"
  override def getPolicy: FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}

/** Renders bundled HTML with Swing and opens activated hyperlinks in the external browser. */
private[analyzer] final class WhatsNewEditor(file: WhatsNewFile) extends UserDataHolderBase with FileEditor {

  private val pane = new JBHtmlPane {
    override protected def initializePaneConfiguration(builder: JBHtmlPaneConfiguration.Builder): Unit = {
      builder.customStyleSheetProvider(
        new kotlin.jvm.functions.Function1[JBHtmlPane, javax.swing.text.html.StyleSheet] {
          override def invoke(pane: JBHtmlPane): javax.swing.text.html.StyleSheet = {
            def color(value: java.awt.Color): String = f"#${value.getRGB & 0xffffff}%06x"
            val css                                  = new javax.swing.text.html.StyleSheet
            css.addRule(
              s"body, p, p-implied, li { font-family: '${UIUtil.getLabelFont.getFamily}'; font-weight: normal; color: ${color(UIUtil.getLabelForeground)}; }"
            )
            css.addRule(s"p.version { color: ${color(UIUtil.getContextHelpForeground)}; }")
            css.addRule(s"a { color: ${color(JBUI.CurrentTheme.Link.Foreground.ENABLED)}; text-decoration: none; }")
            css
          }
        }
      )
    }
    override def getScrollableTracksViewportWidth: Boolean = true
  }
  pane.setText(file.html)
  pane.setCaretPosition(0)
  pane.getAccessibleContext.setAccessibleName(file.getName)

  pane.addHyperlinkListener(event =>
    if (event.getEventType == HyperlinkEvent.EventType.ACTIVATED && event.getURL != null)
      BrowserUtil.browse(event.getURL)
  )

  private val content = new JPanel(new BorderLayout()) with Scrollable {
    override def doLayout(): Unit                              = { updatePageLayout(); super.doLayout() }
    override def getPreferredScrollableViewportSize: Dimension = getPreferredSize
    override def getScrollableTracksViewportWidth: Boolean     = true
    override def getScrollableTracksViewportHeight: Boolean    = false
    override def getScrollableUnitIncrement(rect: Rectangle, orientation: Int, direction: Int): Int  = JBUI.scale(20)
    override def getScrollableBlockIncrement(rect: Rectangle, orientation: Int, direction: Int): Int =
      math.max(
        JBUI.scale(20),
        (if (orientation == SwingConstants.VERTICAL) rect.height else rect.width) - JBUI.scale(20)
      )
  }
  content.add(pane, BorderLayout.CENTER)
  private val scroll = new JBScrollPane(content)
  scroll.setBorder(JBUI.Borders.empty())
  scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER)

  private def updatePageLayout(): Unit = {
    val margin = math.max(JBUI.scale(24), (scroll.getViewport.getWidth - JBUI.scale(760)) / 2)
    val insets = content.getInsets
    if (insets.left != margin || insets.top != JBUI.scale(36))
      content.setBorder(JBUI.Borders.empty(36, margin, 48, margin))
    content.setBackground(pane.getBackground)
    scroll.getViewport.setBackground(pane.getBackground)
  }

  scroll.getViewport.addComponentListener(new ComponentAdapter {
    override def componentResized(event: ComponentEvent): Unit = updatePageLayout()
  })
  pane.addPropertyChangeListener("background", _ => updatePageLayout())
  updatePageLayout()
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

  override def dispose(): Unit = if (!disposed) {
    disposed = true
    pane.dispose()
  }
}
