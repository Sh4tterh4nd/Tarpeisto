package io.kellermann.tarpeisto.security;

import static org.assertj.core.api.Assertions.assertThat;

import io.kellermann.tarpeisto.model.OrganizationRole;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Old JDBC principals must deserialize as permanent sessions after adding temporary metadata. */
class PrincipalSerializationCompatibilityTests {
    @TempDir
    Path directory;

    @Test
    void fiveFieldPrincipalFromPreviousReleaseRemainsReadable() throws Exception {
        Path source = directory.resolve("TarpeistoPrincipal.java");
        Files.writeString(source, """
                package io.kellermann.tarpeisto.security;
                import io.kellermann.tarpeisto.model.OrganizationRole;
                import java.io.Serializable;
                import java.util.UUID;
                public record TarpeistoPrincipal(UUID userId, String username, String displayName,
                    UUID organizationId, OrganizationRole role) implements Serializable {}
                """);
        String classpath = Path.of(OrganizationRole.class
                        .getProtectionDomain()
                        .getCodeSource()
                        .getLocation()
                        .toURI())
                .toString();
        int compiled = ToolProvider.getSystemJavaCompiler()
                .run(null, null, null, "-classpath", classpath, "-d", directory.toString(), source.toString());
        assertThat(compiled).isZero();
        UUID user = UUID.randomUUID();
        UUID organization = UUID.randomUUID();
        byte[] serialized;
        try (var oldLoader = new URLClassLoader(
                new URL[] {directory.toUri().toURL()}, getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.equals(TarpeistoPrincipal.class.getName())) {
                    Class<?> type = findLoadedClass(name);
                    if (type == null) type = findClass(name);
                    if (resolve) resolveClass(type);
                    return type;
                }
                return super.loadClass(name, resolve);
            }
        }) {
            Object previous = oldLoader
                    .loadClass(TarpeistoPrincipal.class.getName())
                    .getConstructor(UUID.class, String.class, String.class, UUID.class, OrganizationRole.class)
                    .newInstance(user, "phone-owner", "Phone Owner", organization, OrganizationRole.OWNER);
            var bytes = new ByteArrayOutputStream();
            try (var output = new ObjectOutputStream(bytes)) {
                output.writeObject(previous);
            }
            serialized = bytes.toByteArray();
        }
        try (var input = new ObjectInputStream(new ByteArrayInputStream(serialized))) {
            var restored = (TarpeistoPrincipal) input.readObject();
            assertThat(restored.userId()).isEqualTo(user);
            assertThat(restored.organizationId()).isEqualTo(organization);
            assertThat(restored.role()).isEqualTo(OrganizationRole.OWNER);
            assertThat(restored.temporaryAccess()).isNull();
            assertThat(restored.temporary()).isFalse();
        }
    }
}
