package org.spacebison.googleservicesjsonparser.service;

import com.google.common.base.Throwables;
import com.squareup.javapoet.MethodSpec;

import org.spacebison.googleservicesjsonparser.AndroidStringResourceParser;
import org.spacebison.googleservicesjsonparser.FirebaseOptionsGenerator;
import org.spacebison.googleservicesjsonparser.GoogleServicesJsonParser;
import org.xml.sax.SAXException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.Map;

import javax.servlet.MultipartConfigElement;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.Part;

import spark.Filter;
import spark.Request;
import spark.Response;
import spark.Route;
import spark.Spark;
import spark.utils.IOUtils;
import spark.utils.StringUtils;

public final class Service {
    private Service() {
        init();
    }

    public static void init() {
        initIllegalArgumentExceptionHandler();
        Spark.exception(Exception.class, (exception, request, response) -> response.body(Throwables.getStackTraceAsString(exception)));
        Spark.post("/stringResources", "multipart/form-data", new StringResourcesRoute());
        Spark.post("/firebaseOptions", new FirebaseOptionsRoute());
    }

    private static void initIllegalArgumentExceptionHandler() {
        Spark.exception(IllegalArgumentException.class, (exception, request, response) -> {
            response.status(HttpURLConnection.HTTP_BAD_REQUEST);
            response.body(exception.getMessage() + '\n');
        });
    }

    private static void initLogger() {
        Spark.before((Filter) new LoggerFilter());
    }

    private static class FirebaseOptionsRoute implements Route {
        @Override
        public Object handle(Request request, Response response) throws Exception {
            String stringResXml = request.body();
            if (StringUtils.isBlank(stringResXml)) {
                throw new IllegalArgumentException("Empty body");
            }

            try {
                String code = getFirebaseOptionsBuilderCode(stringResXml);
                response.type("text/x-java-source");
                return code;
            } catch (Exception e) {
                e.printStackTrace();
                throw new IllegalArgumentException("Error parsing xml: " + e.getMessage());
            }
        }

        private static String getFirebaseOptionsBuilderCode(String body) throws IOException, SAXException {
            Map<String, String> firebaseConfiguration = AndroidStringResourceParser.parseStringResXml(body);
            MethodSpec builderMethod = FirebaseOptionsGenerator.getFirebaseOptionsBuilderMethod(firebaseConfiguration);
            return builderMethod.code.toString();
        }
    }

    private static class StringResourcesRoute implements Route {
        private static final String PART_GOOGLE_SERVICES_JSON = "google-services.json";
        private static final String PART_PACKAGE_NAME = "packageName";
        private final MultipartConfigElement mMultipartConfigElement = new MultipartConfigElement(System.getProperty("java.io.tmpdir"));

        @Override
        public Object handle(Request request, Response response) throws Exception {
            HttpServletRequest rawRequest = request.raw();
            setMultipartConfigAttribute(rawRequest);

            Part packageNamePart = rawRequest.getPart(PART_PACKAGE_NAME);
            if (packageNamePart == null) {
                throw new IllegalArgumentException("Missing packageName part");
            }

            Part googleServicesJsonPart = rawRequest.getPart(PART_GOOGLE_SERVICES_JSON);
            if (googleServicesJsonPart == null) {
                throw new IllegalArgumentException("Missing google-services.json part");
            }

            String packageName = IOUtils.toString(packageNamePart.getInputStream());

            File tmpJsonFile = File.createTempFile("google-services", ".json");
            try (OutputStream os = new FileOutputStream(tmpJsonFile)) {
                IOUtils.copy(googleServicesJsonPart.getInputStream(), os);
            }

            try {
                String stringResXml = GoogleServicesJsonParser.getGoogleServicesStringResXml(packageName, tmpJsonFile);
                response.header("Content-Type", "application/xml; charset=utf-8");
                return stringResXml;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }

        private void setMultipartConfigAttribute(HttpServletRequest rawRequest) {
            if (rawRequest.getAttribute("org.eclipse.jetty.multipartConfig") == null) {
                rawRequest.setAttribute("org.eclipse.jetty.multipartConfig", mMultipartConfigElement);
            }
        }
    }

    private static HttpServletRequest getMultipartHttpServletRequest(Request request) {
        HttpServletRequest servletRequest = request.raw();
        MultipartConfigElement multipartConfig = new MultipartConfigElement(System.getProperty("java.io.tmpdir"));
        servletRequest.setAttribute("org.eclipse.jetty.multipartConfig", multipartConfig);
        return servletRequest;
    }

    private static class LoggerFilter implements Filter {
        @Override
        public void handle(Request request, Response response) throws Exception {
            System.out.println(request.requestMethod() + " " + request.uri());

            for (String header : request.headers()) {
                System.out.println(" " + header + ": " + request.headers(header));
            }

            System.out.println(request.body());
        }
    }
}
