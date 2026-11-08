import com.github.tvbox.osc.util.XmlTvParser;

import java.io.Reader;
import java.io.StringReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.TimeZone;
import java.util.concurrent.CancellationException;

/** Standalone regression tests: compile with XmlTvParser.java, then run with java -Xmx32m. */
public final class XmlTvParserTest {
    private static final String PROGRAMME = "<programme channel='other' start='20261108090000 +0800' "
            + "stop='20261108100000 +0800'><title>Other</title></programme>";

    public static void main(String[] args) throws Exception {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm Z");
        Date day = format.parse("2026-11-08 12:00 +0800");
        check("CCTV1".equals(XmlTvParser.normalizeChannelName("CCTV-1 综合")), "CCTV alias");
        check("CCTV5+".equals(XmlTvParser.normalizeChannelName("cctv-5+体育赛事")), "CCTV plus alias");
        check("CCTV4K".equals(XmlTvParser.normalizeChannelName("CCTV-4K超高清")), "4K alias");
        check("湖南卫视".equals(XmlTvParser.normalizeChannelName(" 湖南卫视 ")), "regional name");

        String xml = "<?xml version='1.0'?><tv><channel id='one'><display-name>Unmatched</display-name>"
                + "<display-name>CCTV-1 综合</display-name></channel>"
                + "<programme channel='one' start='20261107233000 +0800' stop='20261108003000 +0800'>"
                + "<title>跨日 &amp; 新闻</title></programme>"
                + "<programme channel='one' start='20261108090000' stop='20261108100000'>"
                + "<title>Morning</title></programme>"
                + "<programme channel='one' start='20261109000000 +0800' stop='20261109010000 +0800'>"
                + "<title>Tomorrow</title></programme>"
                + "<programme channel='one' start='invalid' stop='invalid'><title>Invalid</title></programme>"
                + PROGRAMME + "</tv>";
        ArrayList<XmlTvParser.Programme> result = XmlTvParser.parse(new StringReader(xml), "CCTV1", day, () -> false);
        check(result.size() == 2, "channel/date filtering and invalid timestamps");
        check("跨日 & 新闻".equals(result.get(0).title), "XML entities and cross-midnight programme");
        check(result.get(1).start.equals(format.parse("2026-11-08 09:00 +0800")), "default China timezone");
        check(XmlTvParser.parse(new StringReader(xml), "missing", day, () -> false).isEmpty(), "missing channel");

        String external = "<!DOCTYPE tv SYSTEM 'file:///does-not-exist/xmltv.dtd'><tv>"
                + "<programme channel='CCTV1' start='20261108090000 +0800' stop='20261108100000 +0800'>"
                + "<title>Safe</title></programme></tv>";
        check(XmlTvParser.parse(new StringReader(external), "CCTV1", day, () -> false).size() == 1,
                "external DTD is not fetched");
        boolean rejected = false;
        try {
            XmlTvParser.parse(new StringReader("<tv><programme>"), "CCTV1", day, () -> false);
        } catch (Exception expected) {
            rejected = true;
        }
        check(rejected, "malformed XML fails without returning a partial schedule");

        int[] callbacks = {0};
        boolean cancelled = false;
        try {
            XmlTvParser.parse(new RepeatedSchedule(200_000), "CCTV1", day, () -> ++callbacks[0] > 100);
        } catch (CancellationException expected) {
            cancelled = true;
        }
        check(cancelled && callbacks[0] < 200, "superseded request stops during parsing");

        long start = System.nanoTime();
        result = XmlTvParser.parse(new RepeatedSchedule(200_000), "CCTV1", day, () -> false);
        check(result.size() == 1 && "Selected".equals(result.get(0).title), "large feed retains only selected channel");
        System.out.println("PASS: aliases, channels, dates, entities, malformed XML, cancellation; 200000 programmes in "
                + (System.nanoTime() - start) / 1_000_000 + "ms with a 32MB heap");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** Generate a large feed without retaining it, so -Xmx32m catches a DOM regression. */
    private static final class RepeatedSchedule extends Reader {
        private final int count;
        private int segment;
        private int offset;

        RepeatedSchedule(int count) {
            this.count = count;
        }

        @Override
        public int read(char[] buffer, int start, int length) {
            if (length == 0) return 0;
            if (segment > count + 1) return -1;
            String value = segment == 0 ? "<tv>" : segment <= count ? PROGRAMME
                    : "<programme channel='CCTV1' start='20261108090000 +0800' stop='20261108100000 +0800'>"
                    + "<title>Selected</title></programme></tv>";
            int copied = Math.min(length, value.length() - offset);
            value.getChars(offset, offset + copied, buffer, start);
            offset += copied;
            if (offset == value.length()) {
                offset = 0;
                segment++;
            }
            return copied;
        }

        @Override
        public void close() {}
    }
}
