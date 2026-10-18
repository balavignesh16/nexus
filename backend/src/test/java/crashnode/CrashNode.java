package crashnode;

import com.nexus.NexusApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Entry point of one disposable backend process of the crash-recovery campaign. It is the real application (the same component scan,
 * migrations, dispatcher, scanners and MQTT wiring) plus the test-only {@link CrashHooks}. Every setting arrives as a -D system property
 * from the launcher; when the web server is up it prints one line, {@code CRASHNODE_READY port=<port>}, which the launcher waits for.
 */
public final class CrashNode {
    private CrashNode() {}

    public static void main(String[] args) {
        ConfigurableApplicationContext context = new SpringApplicationBuilder(NexusApplication.class, CrashHooks.class)
                .web(WebApplicationType.SERVLET)
                .run(args);
        int port = ((ServletWebServerApplicationContext) context).getWebServer().getPort();
        System.out.println("CRASHNODE_READY port=" + port);
        System.out.flush();
    }
}
