package com.github.tvbox.osc.util;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.XMLReader;
import org.xml.sax.helpers.DefaultHandler;

import java.io.Reader;
import java.io.StringReader;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.SAXParserFactory;

/** Streams XMLTV without building a DOM for every channel's entire schedule. */
public final class XmlTvParser {
    private static final Pattern CCTV_NAME = Pattern.compile("(?i)^(CCTV\\d+(?:\\+|K)?)(?:[\\u4e00-\\u9fa5].*|$)");

    public interface Cancellation {
        boolean isCancelled();
    }

    public static final class Programme {
        public final String title;
        public final Date start;
        public final Date end;

        Programme(String title, Date start, Date end) {
            this.title = title;
            this.start = start;
            this.end = end;
        }
    }

    public static String normalizeChannelName(String name) {
        if (name == null) return "";
        String trimmed = name.trim();
        String compact = trimmed.replace("-", "").replace(" ", "");
        Matcher matcher = CCTV_NAME.matcher(compact);
        if (matcher.matches()) return matcher.group(1).toUpperCase(Locale.ROOT);
        return compact.toUpperCase(Locale.ROOT).startsWith("CCTV")
                ? compact.toUpperCase(Locale.ROOT) : trimmed;
    }

    public static ArrayList<Programme> parse(Reader input, String channel, Date day,
                                            Cancellation cancellation) throws Exception {
        ScheduleHandler handler = new ScheduleHandler(channel, day, cancellation);
        XMLReader reader = SAXParserFactory.newInstance().newSAXParser().getXMLReader();
        // Android implementations differ in supported features. The resolver also blocks external IO.
        for (String feature : new String[]{"http://xml.org/sax/features/external-general-entities",
                "http://xml.org/sax/features/external-parameter-entities",
                "http://apache.org/xml/features/nonvalidating/load-external-dtd"}) {
            try {
                reader.setFeature(feature, false);
            } catch (SAXException ignored) {
            }
        }
        reader.setEntityResolver((publicId, systemId) -> new InputSource(new StringReader("")));
        reader.setContentHandler(handler);
        reader.parse(new InputSource(input));
        return handler.programmes;
    }

    private static final class ScheduleHandler extends DefaultHandler {
        final ArrayList<Programme> programmes = new ArrayList<>();
        final Set<String> channelIds = new HashSet<>();
        final Map<String, Boolean> channelMatches = new HashMap<>();
        final String target;
        final Cancellation cancellation;
        final long dayStart;
        final long dayEnd;
        final SimpleDateFormat zonedDate = new SimpleDateFormat("yyyyMMddHHmmss Z", Locale.ROOT);
        final SimpleDateFormat localDate = new SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT);
        String channelId;
        boolean matchingProgramme;
        boolean readingText;
        String textTag;
        final StringBuilder text = new StringBuilder();
        String title;
        Date start;
        Date end;

        ScheduleHandler(String channel, Date day, Cancellation cancellation) throws ParseException {
            target = normalizeChannelName(channel);
            this.cancellation = cancellation;
            SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
            date.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
            dayStart = date.parse(date.format(day)).getTime();
            dayEnd = dayStart + 86_400_000L;
            localDate.setTimeZone(TimeZone.getTimeZone("GMT+8:00"));
        }

        private void checkCancelled() {
            if (cancellation.isCancelled()) throw new CancellationException("EPG request superseded");
        }

        @Override
        public void startElement(String uri, String localName, String name, Attributes attributes) {
            checkCancelled();
            if ("channel".equals(name)) {
                channelId = attributes.getValue("id");
                if (target.equals(normalizeChannelName(channelId))) channelIds.add(channelId);
            } else if ("programme".equals(name)) {
                String id = attributes.getValue("channel");
                Boolean matches = channelMatches.get(id);
                if (matches == null) {
                    matches = channelIds.contains(id) || target.equals(normalizeChannelName(id));
                    channelMatches.put(id, matches);
                }
                matchingProgramme = matches;
                title = "";
                start = matchingProgramme ? parseDate(attributes.getValue("start")) : null;
                end = matchingProgramme ? parseDate(attributes.getValue("stop")) : null;
            } else if (("display-name".equals(name) && channelId != null)
                    || ("title".equals(name) && matchingProgramme && title.isEmpty())) {
                readingText = true;
                textTag = name;
                text.setLength(0);
            }
        }

        @Override
        public void characters(char[] chars, int offset, int length) {
            checkCancelled();
            if (readingText) text.append(chars, offset, length);
        }

        @Override
        public void endElement(String uri, String localName, String name) {
            checkCancelled();
            if (readingText && name.equals(textTag)) {
                if ("display-name".equals(name) && target.equals(normalizeChannelName(text.toString()))) {
                    channelIds.add(channelId);
                } else if ("title".equals(name)) {
                    title = text.toString();
                }
                readingText = false;
            }
            if ("channel".equals(name)) channelId = null;
            if ("programme".equals(name)) {
                if (matchingProgramme && start != null && end != null && end.after(start)
                        && start.getTime() < dayEnd && end.getTime() > dayStart) {
                    programmes.add(new Programme(title, start, end));
                }
                matchingProgramme = false;
            }
        }

        private Date parseDate(String value) {
            if (value == null || value.trim().isEmpty()) return null;
            try {
                return zonedDate.parse(value.trim());
            } catch (ParseException ignored) {
                try {
                    return localDate.parse(value.trim());
                } catch (ParseException invalidDate) {
                    return null;
                }
            }
        }
    }
}
