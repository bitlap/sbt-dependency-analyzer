package bitlap.sbt.analyzer

import bitlap.sbt.analyzer.activity.{ UpdatePagePolicy, WhatsNew }

import org.scalatest.funsuite.AnyFunSuite

class WhatsNewSpec extends AnyFunSuite {

  test("first install and upgrades open the page; repeated starts and downgrades do not") {
    assert(UpdatePagePolicy.shouldShow("0.11.0", null))
    assert(UpdatePagePolicy.shouldShow("0.11.0", ""))
    assert(UpdatePagePolicy.shouldShow("0.11.0", "0.0.0"))
    assert(UpdatePagePolicy.shouldShow("0.11.0", "0.10.0"))
    assert(!UpdatePagePolicy.shouldShow("0.11.0", "0.11.0"))
    assert(!UpdatePagePolicy.shouldShow("0.10.0", "0.11.0"))
    assert(!UpdatePagePolicy.shouldShow("", "0.10.0"))
  }

  test("bundled latest notes are available offline with escaped title and release link") {
    val html = WhatsNew.pageHtml(
      "What's New <test>",
      s"<ul><li>Latest feature</li></ul>${Constants.CHANGE_NOTES_SEPARATOR}<ul><li>Old feature</li></ul>",
      "https://example.org/?a=1&b=\"2\""
    )
    assert(html.contains("Latest feature"))
    assert(!html.contains("Old feature"))
    assert(html.contains("What&#39;s New &lt;test&gt;"))
    assert(html.contains("?a=1&amp;b=&quot;2&quot;"))
    assert(!html.contains("<iframe"))
    assert(WhatsNew.pageHtml("Title", null, "https://example.org").contains("Title"))
  }
}
