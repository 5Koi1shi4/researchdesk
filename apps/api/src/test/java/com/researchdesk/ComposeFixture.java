package com.researchdesk;

import java.nio.file.Path;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/** Destructive fixtures are confined to the explicitly selected isolated stack. */
public final class ComposeFixture {
    private ComposeFixture() {}
    public static String project() {
        String project=System.getenv("COMPOSE_PROJECT_NAME");
        if (project==null || !project.matches("researchdesk-(delivery|test-[a-z0-9-]+)"))
            throw new IllegalStateException("Integration fixtures require an explicit isolated project");
        return project;
    }
    public static String baseUrl() {
        String project=project();
        String url=System.getenv().getOrDefault("ACCEPTANCE_URL", "http://127.0.0.1:18085");
        URI target=URI.create(url);
        int port=target.getPort();
        if (!"http".equals(target.getScheme()) || !java.util.Set.of("localhost","127.0.0.1").contains(target.getHost())
            || target.getRawUserInfo()!=null || target.getRawQuery()!=null || target.getRawFragment()!=null
            || !(target.getPath().isEmpty() || target.getPath().equals("/")) || port<1024 || port>65535
            || (port>=18080 && port<=18084) || (project.equals("researchdesk-delivery") ? port!=18085 : port==18085))
            throw new IllegalStateException("Integration HTTP requires a matching isolated loopback port");
        // Validate the actual deployment before registration or any other HTTP write.
        try {
            var process=new ProcessBuilder("docker","compose","-p",project,"-f",file(),"port","proxy","8080").redirectErrorStream(true).start();
            if (!process.waitFor(15,TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Proxy binding check timed out"); }
            String binding=new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8).strip();
            if (process.exitValue()!=0 || !binding.equals("127.0.0.1:"+port))
                throw new IllegalStateException("Integration HTTP does not match selected project proxy binding");
        } catch (java.io.IOException error) { throw new IllegalStateException("Cannot verify isolated proxy binding",error); }
        catch (InterruptedException error) { Thread.currentThread().interrupt();throw new IllegalStateException("Proxy binding check interrupted",error); }
        return url.endsWith("/") ? url.substring(0,url.length()-1) : url;
    }
    public static String file() {
        return Path.of(System.getProperty("user.dir"), "../../infra/compose.yaml").normalize().toString();
    }
}
