package com.bramhastra.baton.source.connector;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Component;

import com.bramhastra.baton.source.SourceType;

/** Markdown, text, PDF and Word documents, including meeting notes. */
@Component
public class DocumentConnector implements SourceConnector {

    static final Set<String> EXTENSIONS = Set.of("md", "markdown", "txt", "pdf", "docx", "doc", "rtf", "odt", "html");

    private static final Pattern ISO_DATE = Pattern.compile("\\b(20\\d{2})-(\\d{2})-(\\d{2})\\b");
    private static final int MAX_CHARS = 5_000_000;

    private final Tika tika = new Tika();

    @Override
    public boolean supports(SourceType type) {
        return type == SourceType.DOCUMENT || type == SourceType.MEETING_NOTES;
    }

    @Override
    public void validate(java.util.Map<String, Object> config, StagedFile file) {
        if (file == null) {
            throw new IllegalArgumentException("A file is required");
        }
        if (!EXTENSIONS.contains(file.extension())) {
            throw new UnsupportedOperationException("Supported documents: " + EXTENSIONS);
        }
    }

    @Override
    public List<RawDocument> fetch(FetchRequest request, Consumer<String> status) throws Exception {
        StagedFile file = request.file();
        status.accept("Reading " + file.filename());
        String text;
        Metadata metadata = new Metadata();
        String ext = file.extension();
        if (ext.equals("md") || ext.equals("markdown") || ext.equals("txt")) {
            text = new String(file.bytes(), StandardCharsets.UTF_8);
        } else {
            metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.filename());
            text = tika.parseToString(new ByteArrayInputStream(file.bytes()), metadata, MAX_CHARS);
        }
        text = text.replace("\r\n", "\n").strip();
        String author = metadata.get(TikaCoreProperties.CREATOR);
        Instant authoredAt = guessDate(file.filename(), text);
        if (authoredAt == null && metadata.getDate(TikaCoreProperties.CREATED) != null) {
            authoredAt = metadata.getDate(TikaCoreProperties.CREATED).toInstant();
        }
        return List.of(new RawDocument(file.filename(), titleOf(file.filename(), text), author, authoredAt, null,
                file.mime(), text));
    }

    /** Meeting notes usually carry their date in the file name or the first lines. */
    static Instant guessDate(String filename, String text) {
        Matcher m = ISO_DATE.matcher(filename);
        if (!m.find()) {
            m = ISO_DATE.matcher(text.length() > 400 ? text.substring(0, 400) : text);
            if (!m.find()) {
                return null;
            }
        }
        try {
            return LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                    Integer.parseInt(m.group(3))).atStartOfDay().toInstant(ZoneOffset.UTC);
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** First Markdown heading if there is one, else the file name. */
    static String titleOf(String filename, String text) {
        for (String line : text.lines().limit(10).toList()) {
            if (line.startsWith("# ")) {
                return line.substring(2).strip();
            }
        }
        return filename;
    }
}
