package performance;

import jade.lang.acl.ACLMessage;
import java.io.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

public class RTTLogger {
    private static final String BASE_PATH = "agent_output/rtt_logs/";
    private final String agentName;
    private PrintWriter csvWriter;
    private final ConcurrentHashMap<String, RequestInfo> pendingRequests = new ConcurrentHashMap<>();

    private static class RequestInfo {
        final Instant startTime;
        final String performative;
        final String receiver;

        RequestInfo(Instant startTime, String performative, String receiver) {
            this.startTime = startTime;
            this.performative = performative;
            this.receiver = receiver;
        }
    }

    public RTTLogger(String agentName) {
        this.agentName = agentName;
        initializeLogger();
    }

    private void initializeLogger() {
        try {
            File directory = new File(BASE_PATH);
            if (!directory.exists()) {
                directory.mkdirs();
            }

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HH-mm-ss"));
            String filename = String.format("%srtt_measurements_%s.csv", BASE_PATH, timestamp);

            csvWriter = new PrintWriter(new FileWriter(filename));
            // Match SPADE's CSV format
            csvWriter.println("Timestamp,Sender,Receiver,ConversationID,Performative,RTT_ms,MessageSize_bytes,Success,AdditionalInfo,Ontology");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void startRequest(String conversationId, String performative, String receiver, String ontology) {
        pendingRequests.put(conversationId,
                new RequestInfo(Instant.now(), performative, receiver));
    }

    public void endRequest(String conversationId, String responsePerformative,
                           long messageSize, boolean success, String ontology) {
        RequestInfo request = pendingRequests.remove(conversationId);
        if (request != null) {
            long rtt = Duration.between(request.startTime, Instant.now()).toMillis();

            csvWriter.printf("%s,%s,%s,%s,%s,%.2f,%d,%b,{\"response_performative\":\"%s\"},%s%n",
                    LocalDateTime.now(),
                    agentName,
                    request.receiver,
                    conversationId,
                    request.performative,
                    (double)rtt,
                    messageSize,
                    success,
                    responsePerformative,
                    ontology
            );
            csvWriter.flush();
        }
    }

    public void close() {
        if (csvWriter != null) {
            csvWriter.close();
        }
    }
}