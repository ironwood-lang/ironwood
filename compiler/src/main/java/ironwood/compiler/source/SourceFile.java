// SPDX-License-Identifier: MIT OR Apache-2.0

package ironwood.compiler.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public final class SourceFile {
    private final Path path;
    private final String content;
    private final List<String> lines;

    private SourceFile(Path path, String content) {
        this.path = path;
        this.content = content;
        this.lines = splitLines(content);
    }

    public static SourceFile read(Path path) throws IOException {
        Path absolutePath = path.toAbsolutePath().normalize();
        return new SourceFile(absolutePath, Files.readString(absolutePath, StandardCharsets.UTF_8));
    }

    public static SourceFile of(String displayPath, String content) {
        return new SourceFile(Path.of(displayPath), content);
    }

    public Path path() {
        return path;
    }

    public String content() {
        return content;
    }

    public String lineText(int oneBasedLine) {
        if (oneBasedLine < 1 || oneBasedLine > lines.size()) {
            return "";
        }
        return lines.get(oneBasedLine - 1);
    }

    private static List<String> splitLines(String content) {
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < content.length(); index++) {
            char current = content.charAt(index);
            if (current == '\n' || current == '\r') {
                result.add(content.substring(start, index));
                if (current == '\r' && index + 1 < content.length()
                        && content.charAt(index + 1) == '\n') {
                    index++;
                }
                start = index + 1;
            }
        }
        result.add(content.substring(start));
        return List.copyOf(result);
    }
}
