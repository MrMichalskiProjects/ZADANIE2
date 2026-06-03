package org.example;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Main {

    static final String AUTHOR = "Mateusz Michalski";

    record City(String name, double lat, double lon) {}

    static final Map<String, List<City>> CITIES = new LinkedHashMap<>();
    static {
        CITIES.put("Polska",  List.of(new City("Warszawa", 52.2297, 21.0122),
                                      new City("Kraków",   50.0647, 19.9450)));
        CITIES.put("Niemcy",  List.of(new City("Berlin",   52.5200, 13.4050),
                                      new City("Monachium",48.1351, 11.5820)));
        CITIES.put("Francja", List.of(new City("Paryż",    48.8566,  2.3522),
                                      new City("Lyon",     45.7640,  4.8357)));
    }

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        if (args.length > 0 && "healthcheck".equals(args[0])) {
            System.exit(runHealthcheck(port));
        }

        String startedAt = ZonedDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);

        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/", Main::handle);
        server.start();

        System.out.println("=== Aplikacja Pogoda ===");
        System.out.println("Data uruchomienia: " + startedAt);
        System.out.println("Autor:             " + AUTHOR);
        System.out.println("Nasłuch na porcie: " + port);
        System.out.println("Adres:             http://localhost:" + port);
    }

    static void handle(HttpExchange ex) throws IOException {
        try {
            String path = ex.getRequestURI().getPath();
            if (path.equals("/health")) {
                byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/plain; charset=utf-8");
                ex.sendResponseHeaders(200, body.length);
                ex.getResponseBody().write(body);
                ex.close();
                return;
            }
            if (path.equals("/")) {
                respond(ex, 200, renderForm(null, null));
            } else if (path.equals("/weather")) {
                Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
                String country = q.get("country");
                String cityName = q.get("city");
                City city = findCity(country, cityName);
                if (city == null) {
                    respond(ex, 400, renderForm("Wybierz poprawny kraj i miasto.", null));
                    return;
                }
                try {
                    String weatherHtml = fetchWeather(country, city);
                    respond(ex, 200, renderForm(null, weatherHtml));
                } catch (Exception e) {
                    respond(ex, 502, renderForm("Błąd pobierania pogody: " + e.getMessage(), null));
                }
            } else {
                respond(ex, 404, "<h1>404</h1>");
            }
        } catch (Exception e) {
            respond(ex, 500, "<pre>" + escape(e.toString()) + "</pre>");
        }
    }

    static int runHealthcheck(int port) {
        try (java.net.Socket s = new java.net.Socket()) {
            s.connect(new java.net.InetSocketAddress("127.0.0.1", port), 2000);
            s.setSoTimeout(2000);
            s.getOutputStream().write(
                    "GET /health HTTP/1.0\r\nHost: localhost\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            String line = new java.io.BufferedReader(
                    new java.io.InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8)).readLine();
            return (line != null && line.contains(" 200 ")) ? 0 : 1;
        } catch (Exception e) {
            return 1;
        }
    }

    static City findCity(String country, String name) {
        if (country == null || name == null) return null;
        List<City> list = CITIES.get(country);
        if (list == null) return null;
        for (City c : list) if (c.name().equals(name)) return c;
        return null;
    }

    static String fetchWeather(String country, City city) throws Exception {
        String url = ("https://api.open-meteo.com/v1/forecast"
                + "?latitude=%s&longitude=%s"
                + "&current=temperature_2m,relative_humidity_2m,apparent_temperature,"
                + "precipitation,weather_code,wind_speed_10m"
                + "&timezone=auto").formatted(city.lat(), city.lon());
        HttpClient http = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).GET().build();
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() / 100 != 2) throw new RuntimeException("HTTP " + r.statusCode());
        String current = extractObject(r.body(), "current");
        if (current == null) throw new RuntimeException("Brak pola 'current' w odpowiedzi API");

        Double temp   = num(current, "temperature_2m");
        Double feels  = num(current, "apparent_temperature");
        Double hum    = num(current, "relative_humidity_2m");
        Double rain   = num(current, "precipitation");
        Double wind   = num(current, "wind_speed_10m");
        Double code   = num(current, "weather_code");
        String time   = str(current, "time");

        int wc = code == null ? -1 : code.intValue();
        return """
            <div class="result">
              <h2>%s %s, %s</h2>
              <p class="desc">%s</p>
              <ul>
                <li><b>Temperatura:</b> %s °C (odczuwalna %s °C)</li>
                <li><b>Wilgotność:</b> %s %%</li>
                <li><b>Opady:</b> %s mm</li>
                <li><b>Wiatr:</b> %s km/h</li>
                <li><b>Pomiar:</b> %s</li>
              </ul>
            </div>
            """.formatted(
                icon(wc), escape(city.name()), escape(country),
                escape(describe(wc)),
                fmt(temp), fmt(feels), fmt(hum), fmt(rain), fmt(wind),
                escape(time == null ? "-" : time)
        );
    }

    static String renderForm(String error, String resultHtml) {
        StringBuilder options = new StringBuilder();
        for (String country : CITIES.keySet()) {
            options.append("<optgroup label=\"").append(escape(country)).append("\">");
            for (City c : CITIES.get(country)) {
                String val = escape(country) + "|" + escape(c.name());
                options.append("<option value=\"").append(val).append("\">")
                       .append(escape(c.name())).append("</option>");
            }
            options.append("</optgroup>");
        }

        return """
            <!doctype html>
            <html lang="pl"><head>
              <meta charset="utf-8">
              <title>Pogoda</title>
              <style>
                body { font-family: system-ui, sans-serif; max-width: 560px; margin: 2rem auto; padding: 0 1rem; color:#222; }
                h1 { margin-top: 0; }
                form { display:flex; gap:.5rem; align-items:center; }
                select, button { padding:.5rem .75rem; font-size:1rem; }
                button { cursor:pointer; background:#1e6feb; color:#fff; border:0; border-radius:4px; }
                .result { margin-top:1.5rem; padding:1rem 1.25rem; background:#f3f6fb; border-radius:8px; }
                .result h2 { margin:0 0 .25rem 0; }
                .desc { color:#555; margin:0 0 .75rem 0; }
                ul { margin:0; padding-left:1.1rem; }
                .err { color:#a40000; margin-top:1rem; }
                footer { margin-top:2rem; color:#888; font-size:.85rem; }
              </style>
            </head><body>
              <h1>Pogoda</h1>
              <form action="/weather" method="get" onsubmit="return splitChoice(this)">
                <select name="choice" required>
                  <option value="" disabled selected>-- wybierz miasto --</option>
                  %s
                </select>
                <button type="submit">Sprawdź</button>
                <input type="hidden" name="country"><input type="hidden" name="city">
              </form>
              <script>
                function splitChoice(f){
                  var v = f.choice.value || "";
                  var i = v.indexOf("|");
                  if (i < 0) return false;
                  f.country.value = v.substring(0, i);
                  f.city.value    = v.substring(i + 1);
                  f.choice.disabled = true;
                  return true;
                }
              </script>
              %s
              %s
              <footer>Autor: %s &middot; Dane: Open-Meteo</footer>
            </body></html>
            """.formatted(
                options.toString(),
                error == null ? "" : "<p class=\"err\">" + escape(error) + "</p>",
                resultHtml == null ? "" : resultHtml,
                escape(AUTHOR)
        );
    }

    // --- helpers ---

    static Map<String, String> parseQuery(String raw) {
        Map<String, String> m = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return m;
        for (String p : raw.split("&")) {
            int i = p.indexOf('=');
            String k = i < 0 ? p : p.substring(0, i);
            String v = i < 0 ? "" : p.substring(i + 1);
            m.put(URLDecoder.decode(k, StandardCharsets.UTF_8),
                  URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return m;
    }

    static void respond(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(code, bytes.length);
        ex.getResponseBody().write(bytes);
        ex.close();
    }

    static String extractObject(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return null;
        int brace = json.indexOf('{', idx);
        if (brace < 0) return null;
        int depth = 0;
        for (int i = brace; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return json.substring(brace, i + 1);
        }
        return null;
    }

    static Double num(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)").matcher(json);
        return m.find() ? Double.parseDouble(m.group(1)) : null;
    }

    static String str(String json, String key) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find() ? m.group(1) : null;
    }

    static String fmt(Double d) { return d == null ? "-" : String.valueOf(d); }

    static String escape(String s) {
        if (s == null) return "";
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;")
                .replace("\"","&quot;").replace("'","&#39;");
    }

    static String describe(int c) {
        return switch (c) {
            case 0 -> "Bezchmurnie";
            case 1, 2 -> "Częściowe zachmurzenie";
            case 3 -> "Pochmurno";
            case 45, 48 -> "Mgła";
            case 51, 53, 55 -> "Mżawka";
            case 61, 63, 65 -> "Deszcz";
            case 71, 73, 75 -> "Śnieg";
            case 80, 81, 82 -> "Przelotne opady";
            case 95, 96, 99 -> "Burza";
            default -> "Kod pogody: " + c;
        };
    }

    static String icon(int c) {
        return switch (c) {
            case 0 -> "☀";
            case 1, 2 -> "⛅";
            case 3 -> "☁";
            case 45, 48 -> "🌫";
            case 51, 53, 55 -> "🌦";
            case 61, 63, 65, 80, 81, 82 -> "🌧";
            case 71, 73, 75 -> "❄";
            case 95, 96, 99 -> "⛈";
            default -> "🌡";
        };
    }
}
