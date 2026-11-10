package com.szsn.srm;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class OfflineAssetTest {
    @Test
    void packagedUiDoesNotReferenceCdn() throws Exception {
        Path root = Path.of("src", "main", "resources", "static");
        if (!Files.exists(root.resolve("index.html"))) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                String name = path.getFileName().toString().toLowerCase();
                if (!(name.endsWith(".html") || name.endsWith(".js") || name.endsWith(".css"))) {
                    continue;
                }
                String text = Files.readString(path).toLowerCase();
                assertFalse(text.contains("fonts.googleapis"), path.toString());
                assertFalse(text.contains("cdn.jsdelivr"), path.toString());
                assertFalse(text.contains("unpkg.com"), path.toString());
                assertFalse(text.contains("cdnjs.cloudflare"), path.toString());
                if (name.endsWith(".html")) {
                    assertFalse(text.contains("src=\"http"), path.toString());
                    assertFalse(text.contains("href=\"http"), path.toString());
                }
            }
        }
    }
}
