package bitlap.sbt.analyzer

import bitlap.sbt.analyzer.activity.{ UpdatePagePolicy, WhatsNew }

import org.scalatest.funsuite.AnyFunSuite

/** Checks update eligibility and bundled release-note markup without opening an IDE editor. */
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

  test("latest release stops at the next heading even when separators are missing") {
    val html = WhatsNew.pageHtml(
      "Updates",
      "<H1>0.11.0</H1><h2>Fixes</h2><ul><li>Newest</li></ul><h1>0.10.0</h1><p>Previous</p>",
      "https://example.org"
    )
    assert(html.contains("Newest") && html.contains("Fixes"))
    assert(!html.contains("Previous") && !html.contains("0.10.0"))
    assert(!html.contains("<H1>0.11.0</H1>"))
    assert(html.contains("0.11.0"))
  }

  test("explicit version and semantic release link are escaped without hard-coded theme colors or remote assets") {
    val html = WhatsNew.pageHtml("Updates", "<h1>old</h1><p>Notes</p>", "https://example.org", "1<2")
    assert(html.contains("1&lt;2") && !html.contains(">old<"))
    assert(html.contains("class=\"release-link\"") && !html.contains(">https://example.org<"))
    assert(
      !html.contains("background:") && !html.contains("color:") && !html.contains("<script") && !html.contains("<img")
    )
    assert(WhatsNew.pageHtml("Updates", "", "https://example.org").contains("GitHub"))
  }

}
