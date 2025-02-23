package com.report;

import com.amazonaws.services.lambda.runtime.Context;
import com.amazonaws.services.lambda.runtime.RequestHandler;
import com.opencsv.CSVWriter;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.AttributeValue;
import software.amazon.awssdk.services.dynamodb.model.ScanRequest;
import software.amazon.awssdk.services.dynamodb.model.ScanResponse;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CSVReportLambda implements RequestHandler<Map<String, Object>, String> {

    private static final String DYNAMODB_TABLE = "trainers";
    private static final String S3_BUCKET = "olena-sinkevych-bucket3";

    private final DynamoDbClient dynamoDbClient = DynamoDbClient.create();
    private final S3Client s3Client = S3Client.create();

    @Override
    public String handleRequest(Map<String, Object> input, Context context) {
        try {
            System.out.println("Starting CSV report generation...");
            List<String[]> csvData = new ArrayList<>();
            csvData.add(new String[]{"Trainer First Name", "Trainer Last Name", "Training Duration (hours)"});

            LocalDate today = LocalDate.now();
            String currentMonth = today.getMonth().name();
            int currentYear = today.getYear();

            ScanRequest scanRequest = ScanRequest.builder().tableName(DYNAMODB_TABLE).build();
            ScanResponse scanResponse = dynamoDbClient.scan(scanRequest);
            System.out.println("Scanned DynamoDB, found " + scanResponse.items().size() + " items.");

            for (Map<String, AttributeValue> item : scanResponse.items()) {
                if (!item.containsKey("status") || !Boolean.parseBoolean(item.get("status").s())) {
                    continue;
                }

                String firstName = item.get("firstName").s();
                String lastName = item.get("lastName").s();
                double totalDuration = 0;

                if (item.containsKey("years")) {
                    List<AttributeValue> years = item.get("years").l();
                    for (AttributeValue yearData : years) {
                        Map<String, AttributeValue> yearMap = yearData.m();
                        int year = Integer.parseInt(yearMap.get("year").n());

                        if (year == currentYear && yearMap.containsKey("months")) {
                            List<AttributeValue> months = yearMap.get("months").l();
                            for (AttributeValue monthData : months) {
                                Map<String, AttributeValue> monthMap = monthData.m();
                                String month = monthMap.get("month").s().toUpperCase();
                                if (month.equals(currentMonth) && monthMap.containsKey("trainingSummaryDuration")) {
                                    totalDuration += Double.parseDouble(monthMap.get("trainingSummaryDuration").n());
                                }
                            }
                        }
                    }
                }

                if (totalDuration > 0) {
                    csvData.add(new String[]{firstName, lastName, String.valueOf(totalDuration)});
                }
            }

            if (csvData.size() > 0) {
                String fileName = generateCSV(csvData);
                uploadToS3(fileName);
                System.out.println("Lambda executed at: " + LocalDateTime.now());
                return "CSV Report Generated and Uploaded Successfully!";
            } else {
                return "No valid training data found for this month.";
            }

        } catch (Exception e) {
            System.err.println("Error generating CSV: " + e.getMessage());
            return "Error: " + e.getMessage();
        }
    }

    private String generateCSV(List<String[]> csvData) throws IOException {
        String fileName = "/tmp/Trainers_Trainings_summary_" + LocalDate.now().getYear() + "_" + LocalDate.now().getMonthValue() + ".csv";
        File file = new File(fileName);
        try (FileWriter fileWriter = new FileWriter(file);
             CSVWriter csvWriter = new CSVWriter(fileWriter)) {
            csvWriter.writeAll(csvData);
        }

        if (!file.exists()) {
            throw new IOException("File was not created: " + fileName);
        }
        System.out.println("CSV file created: " + fileName);
        return fileName;
    }

    private void uploadToS3(String filePath) {
        File file = new File(filePath);
        PutObjectRequest putRequest = PutObjectRequest.builder()
                .bucket(S3_BUCKET)
                .key(file.getName())
                .contentType("text/csv")
                .build();

        s3Client.putObject(putRequest, RequestBody.fromFile(file));
        System.out.println("File uploaded: " + file.getName());
    }
}
