import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;
import java.util.Properties;

/**
 * Reads the Discord Properties file.
 */
public final class DiscordPropertiesReader {

    /**
     * Reads the Discord Properties file from src/main/resources.
     *
     * @return return the {@link Properties}
     */
    public static Properties readProperties() throws IOException {
        final File file = new File(Objects.requireNonNull(DiscordPropertiesReader.class.getClassLoader()
                .getResource("discord.properties")).getFile());

        try (final InputStream inputStream = new FileInputStream(file)) {
            final Properties properties = new Properties();
            properties.load(inputStream);
            return properties;
        }
    }
}
