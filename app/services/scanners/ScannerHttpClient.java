package services.scanners;

import java.io.IOException;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

@FunctionalInterface
public interface ScannerHttpClient {
    HttpResponse<String> send(HttpRequest request) throws IOException, InterruptedException;
}
