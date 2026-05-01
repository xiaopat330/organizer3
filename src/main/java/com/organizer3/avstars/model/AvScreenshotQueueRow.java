package com.organizer3.avstars.model;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AvScreenshotQueueRow {
    private long id;
    private long avVideoId;
    private long avActressId;
    private String enqueuedAt;
    private String startedAt;
    private String completedAt;
    private String status;
    private String error;
}
