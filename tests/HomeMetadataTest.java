import com.github.tvbox.osc.util.HomeMetadata;

/** Standalone tests using the project's cached Gson and Jsoup jars. */
public final class HomeMetadataTest {
    public static void main(String[] args) {
        String response = "{\"list\":[{\"vod_id\":\"wrong\",\"vod_name\":\"同名电影\",\"vod_content\":\"错误简介\"},"
                + "{\"vod_id\":42,\"vod_name\":\"目标电影\",\"vod_content\":\"<p>第一段 &amp; 第二段</p><script>bad()</script>\"}]}";
        HomeMetadata metadata = HomeMetadata.fromSource(response, false, "42", "目标电影");
        check(metadata != null && metadata.description.equals("第一段 & 第二段"), "JSON selects the requested ID and cleans markup");
        check(HomeMetadata.fromSource(response, false, "absent", "同名电影") == null,
                "a different ID must never overwrite the focused movie's description");
        check(HomeMetadata.fromSource("{\"list\":[{\"vod_name\":\"目标电影\",\"vod_content\":\"简介\"}]}",
                false, "42", "目标电影") != null, "exact title fallback when ID is omitted");
        check(HomeMetadata.fromSource("{\"list\":[{\"vod_name\":\"其他电影\",\"vod_content\":\"简介\"}]}",
                false, "42", "目标电影") == null, "title fallback rejects another movie");
        String xml = "<rss><list><video><id>42</id><name>目标电影</name><des><![CDATA[<p>中文简介</p>]]></des></video></list></rss>";
        metadata = HomeMetadata.fromSource(xml, true, "42", "目标电影");
        check(metadata != null && metadata.description.equals("中文简介"), "XMLTV-style source detail and CDATA");
        check(HomeMetadata.fromSource("<rss><list><video><id>42</id><des/></video></list></rss>",
                true, "42", "目标电影") == null, "empty description remains unavailable");
        check(HomeMetadata.fromSource("not JSON", false, "42", "目标电影") == null, "malformed payload fails quietly");
        String search = "{\"list\":[{\"vod_id\":\"old\",\"vod_name\":\"目标电影\",\"vod_year\":\"2000\"},"
                + "{\"vod_id\":\"new\",\"vod_name\":\"<b>目标电影</b>\",\"vod_year\":\"2026\"}]}";
        check("new".equals(HomeMetadata.findExactSourceId(search, false, "目标电影", 2026)), "source search matches title and year");
        check(HomeMetadata.findExactSourceId(search, false, "目标电影", 0) == null, "ambiguous remakes are not guessed");
        check(HomeMetadata.findExactSourceId(search, false, "其他电影", 2026) == null, "different title is never borrowed");
        check("42".equals(HomeMetadata.findExactSourceId(xml, true, "目标电影", 0)), "XML search matching");
        String html = "<html><head><link rel='canonical' href='https://movie.douban.com/subject/123/'></head>"
                + "<body><div id='link-report'><span property='v:summary'>短版</span>"
                + "<span class='all hidden'>完整剧情 &amp; 简介</span></div></body></html>";
        check(HomeMetadata.fromDouban(html, "123").description.equals("完整剧情 & 简介"), "hot feed prefers complete summary");
        check(HomeMetadata.fromDouban(html, "456") == null, "redirected subject rejected");
        check(HomeMetadata.fromDouban("<html>请登录</html>", "123") == null, "blocked hot-feed page has no fake description");
        check(HomeMetadata.fromDouban(html, "../123") == null, "subject identity must be numeric");
        System.out.println("PASS: source JSON/XML, HTML cleanup, identity matching, full hot-feed summary, empty and malformed responses");
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
