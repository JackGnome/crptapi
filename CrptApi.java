package org.example;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class CrptApi {

    @Data
    static class Document {
        private Description description;
        @JsonProperty("")
        private String doc_id;

        @JsonProperty("doc_status")
        private String docStatus;

        @JsonProperty("doc_type")
        private String docType;

        private boolean importRequest;

        @JsonProperty("owner_inn")
        private String ownerInn;

        @JsonProperty("participant_inn")
        private String participantInn;

        @JsonProperty("producer_inn")
        private String producerInn;

        @JsonProperty("production_date")
        private LocalDate productionDate;

        @JsonProperty("production_type")
        private String productionType;

        private List<Product> products;

        @JsonProperty("reg_date")
        private LocalDate regDate;

        @JsonProperty("reg_number")
        private String regNumber;

        @Data
        static class Description {
            private String participantInn;
        }
    }

    @Data
    static class Product {

        @JsonProperty("certificate_document")
        private String certificateDocument;

        @JsonProperty("certificate_document_date")
        private LocalDate certificateDocumentDate;

        @JsonProperty("certificate_document_number")
        private String certificateDocumentNumber;

        @JsonProperty("owner_inn")
        private String ownerInn;

        @JsonProperty("producer_inn")
        private String producerInn;

        @JsonProperty("production_date")
        private LocalDate productionDate;

        @JsonProperty("tnved_code")
        private String tnvedCode;

        @JsonProperty("uit_code")
        private String uitCode;

        @JsonProperty("uitu_code")
        private String uituCode;
    }

    @Data
    @AllArgsConstructor
    static class DocumentResponse {
        private String value;
    }

    interface ICrptApi {
        DocumentResponse createDocument(Document document, String signature);
    }

    static class CrptApiImpl implements ICrptApi {

        private final static String CREATE_DOCUMENT_URI = "https://ismp.crpt.ru/api/v3/lk/documents/create";
        private final URI createDocumentUri;
        private final ObjectMapper objectMapper = new ObjectMapper();
        private final HttpClient httpClient = HttpClient.newHttpClient();

        public CrptApiImpl() {
            try {
                createDocumentUri = new URI(CREATE_DOCUMENT_URI);
            } catch (URISyntaxException e) {
                throw new RuntimeException(e);
            }
        }

        @Override
        @RequestLimit
        public DocumentResponse createDocument(Document document, String signature) {
            try {
                String body = objectMapper.writeValueAsString(document);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(createDocumentUri)
                        .header("signature", signature)
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build();
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                return new DocumentResponse(response.body());
            } catch(IOException | InterruptedException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    static class CrptApiFactory {

        public ICrptApi createCrptApi() {
            return new CrptApiImpl();
        }

        public ICrptApi createCrptApi(RequestLimitManager requestManager) {
            ICrptApi crptApi = new CrptApiImpl();
            CrptApiInvocationHandler handler = new CrptApiInvocationHandler(crptApi, requestManager);
            ClassLoader classLoader = crptApi.getClass().getClassLoader();
            var interfaces = crptApi.getClass().getInterfaces();
            ICrptApi api = (ICrptApi) Proxy.newProxyInstance(classLoader, interfaces, handler);
            return api;
        }
    }

    static class CrptApiInvocationHandler implements InvocationHandler {

        private final RequestLimitManager requestManager;
        private final ICrptApi api;

        public CrptApiInvocationHandler(ICrptApi api, RequestLimitManager requestManager) {
            this.requestManager = requestManager;
            this.api = api;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Method sourceMethod = api.getClass().getMethod(method.getName(), method.getParameterTypes());
            if (sourceMethod.isAnnotationPresent(RequestLimit.class)) {
                requestManager.incrementRequestCount();
            }
            return method.invoke(api, args);
        }
    }

    static class RequestLimitManager {
        private final TimeUnit interval;
        private final int requestLimit;
        private volatile int requestCount = 0;

        public RequestLimitManager(TimeUnit interval, int requestLimit) {
            this.interval = interval;
            this.requestLimit = requestLimit;
            runResetRequestCountScheduler();
        }

        private void runResetRequestCountScheduler() {
            Thread requestLimitThread = new Thread(() -> {
                while (true) {
                    try {
                        interval.sleep(5);
                    } catch (InterruptedException ex) {}
                    synchronized (this) {
                        requestCount = 0;
                        notifyAll();
                    }
                }
            });
            requestLimitThread.setDaemon(true);
            requestLimitThread.start();
        }

        private synchronized void incrementRequestCount() throws InterruptedException {
            while (requestCount >= requestLimit) {
                wait();
            }
            requestCount++;
        }
    }

    @Target(ElementType.METHOD)
    @Retention(RetentionPolicy.RUNTIME)
    @interface RequestLimit {}

    public static void main(String[] args) {
        CrptApiFactory factory = new CrptApiFactory();
        RequestLimitManager requestManager = new RequestLimitManager(TimeUnit.SECONDS, 2);
        ICrptApi api = factory.createCrptApi(requestManager);
        api.createDocument(new Document(), "signature");
    }
}